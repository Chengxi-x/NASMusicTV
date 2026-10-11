package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.NetworkMusicService
import com.nasmusic.tv.backend.network.ResolveResult
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 终审 B3 —— 直链缓存失效回调的**行为**测试（Robolectric 直接驱动真 `AppPreferences`）。
 *
 * 缓存 key 是 `networkSource:networkId:quality`，不认识「Meting 端点」与「平台」两维：
 * 端点换了 / netease 切到 qq 后，旧 key 上的 DEAD 判定会在 5 分钟 TTL 内套到
 * **另一平台的另一首歌**上，而 §4 给用户的唯一补救（换自建端点）随之最长 5 分钟无效。
 * 所以这两个 setter 写盘成功后必须把缓存整体作废。
 *
 * 钉住五件事：
 * 1. `setMusicSource` 触发恰好一次；
 * 2. `setMetingApiBaseUrl` 合法 URL 与「空串=恢复默认」各触发一次；
 * 3. **被非法 URL 拒绝的写入不得触发**（回调必须在 `dataStore.edit` 之后，不在守卫之前）；
 * 4. 回调抛出的异常不得外泄给调用方（`runCatching` 包住，对齐既有 `onQualityTierChanged` 样板）；
 * 5. 注册体接上真 `NetworkMusicManager` 后，先前钉住的 DEAD 判定必须消失、同一首歌可重新解析。
 *
 * ⚠️ 本类一律**不**用 `startProviderMirrors` + 轮询内存镜像那种写法：全量单测 2000 例挤在同一个
 * JVM fork 里跑时 10s 窗口会超时（实测红过一次），镜像语义已由同目录 `ProviderMirrorTest` 钉住。
 *
 * 注册点本身（`NasMusicApp` 里那一行）由 `PlayUrlCacheInvalidationWiringGateTest` 的源码门钉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayUrlCacheInvalidateTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
    }

    // ─────────────── 1｜换平台 ───────────────

    @Test
    fun `切换音乐平台来源必须触发一次直链缓存失效`() = runBlocking {
        var hits = 0
        prefs.setOnPlayUrlCacheInvalidated { hits++ }
        prefs.setMusicSource("qq")
        assertEquals("setMusicSource 必须作废直链缓存（终审 B3）", 1, hits)
    }

    @Test
    fun `连续两次换平台各自触发一次（回调不得一次性失效）`() = runBlocking {
        var hits = 0
        prefs.setOnPlayUrlCacheInvalidated { hits++ }
        prefs.setMusicSource("qq")
        prefs.setMusicSource("kugou")
        assertEquals(2, hits)
    }

    // ─────────────── 2/3｜换端点 ───────────────

    @Test
    fun `换自建 Meting 端点必须触发失效`() = runBlocking {
        var hits = 0
        prefs.setOnPlayUrlCacheInvalidated { hits++ }
        prefs.setMetingApiBaseUrl("http://192.168.0.99:8090/api")
        assertEquals("换端点后可播率变了，旧 DEAD 判定必须消失", 1, hits)
    }

    @Test
    fun `空串等于恢复默认端点，同样必须触发失效`() = runBlocking {
        var hits = 0
        prefs.setOnPlayUrlCacheInvalidated { hits++ }
        prefs.setMetingApiBaseUrl("")
        assertEquals(1, hits)
    }

    @Test
    fun `被拒绝的非法端点写入不得作废缓存`() = runBlocking {
        var hits = 0
        prefs.setOnPlayUrlCacheInvalidated { hits++ }
        prefs.setMetingApiBaseUrl("not-a-valid-url")
        assertEquals("非法 URL 必须在守卫处直接 return，一次写入都没发生就不许清缓存", 0, hits)
    }

    // ─────────────── 4｜回调异常不许外泄给调用方 ───────────────

    @Test
    fun `回调抛异常不得外泄给调用方`() = runBlocking {
        prefs.setOnPlayUrlCacheInvalidated { throw IllegalStateException("boom") }
        // runCatching 必须吃掉它：异常一旦外泄，setMusicSource 就会把 IllegalStateException 抛给调用方
        val failure = runCatching { prefs.setMusicSource("qq") }.exceptionOrNull()
        assertNull("回调里的异常必须被 runCatching 包住（对齐 onQualityTierChanged 样板）：$failure", failure)
    }

    // ─────────────── 5｜接上真 manager：DEAD 判定真的消失 ───────────────

    /** 恒返回「探测确认的假 302」的 Fake，并计数被真实打了几次。 */
    private class DeadService : NetworkMusicService {
        override val sourceId = "meting"
        val calls = mutableListOf<Int>()
        override suspend fun search(keyword: String, limit: Int): List<Song> = emptyList()
        override suspend fun resolvePlayUrl(song: Song): String? = null
        override suspend fun resolveLyrics(song: Song): String? = null
        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()
        override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
            calls += quality
            return ResolveResult.failure(quality, probedDead = true)
        }
    }

    @Test
    fun `换平台后先前钉住的 DEAD 判定消失且同一首歌可重新解析`() = runBlocking {
        val svc = DeadService()
        val manager = NetworkMusicManager(
            services = mapOf("meting" to svc),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 }
        )
        val song = Song(
            id = "ntwk_meting_1001", title = "晴天", artist = "周杰伦",
            isNetworkSong = true, networkSource = "meting", networkId = "1001"
        )
        manager.resolvePlayUrlDetailed(song, 320)
        assertTrue("前置条件：该曲已被判据钉为 DEAD", manager.isProbedDead(song))

        // 与 NasMusicApp 注册的那一行同形（那一行本身由源码门钉）
        prefs.setOnPlayUrlCacheInvalidated { manager.clearPlayUrlCache() }
        prefs.setMusicSource("qq")

        assertFalse("换平台后 A 曲的 DEAD 不得跟着 networkId 套到新的 B 曲上（终审 B3）", manager.isProbedDead(song))
        val r = manager.resolvePlayUrlDetailed(song, 320)
        assertFalse(r.isSuccess)
        assertEquals("判定消失后必须重新真实探测一次", listOf(320, 320), svc.calls)
    }
}
