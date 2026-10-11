package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 负面探测缓存测试（§6 验收 13）。
 *
 * 三件事必须成立：DEAD 在 TTL 内不重探、**forceRefresh 与切档位必须重探**、
 * 普通失败（probedDead=false）⛔ 不进缓存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkDeadProbeCacheTest {

    private class FakeService : NetworkMusicService {
        override val sourceId = "meting"
        var verdict: ResolveResult = ResolveResult.failure(320, probedDead = true)
        val calls = mutableListOf<Int>()
        override suspend fun search(keyword: String, limit: Int): List<Song> = emptyList()
        override suspend fun resolvePlayUrl(song: Song): String? = null
        override suspend fun resolveLyrics(song: Song): String? = null
        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()
        override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
            calls += quality
            return verdict
        }
    }

    private lateinit var svc: FakeService
    private lateinit var manager: NetworkMusicManager

    private val song = Song(
        id = "ntwk_meting_1001", title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "meting", networkId = "1001"
    )

    @Before
    fun setup() {
        svc = FakeService()
        manager = NetworkMusicManager(
            services = mapOf("meting" to svc),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 }
        )
    }

    @Test
    fun `DEAD 判定在 TTL 内不再重复探测`() = runBlocking {
        val first = manager.resolvePlayUrlDetailed(song, 320)
        val second = manager.resolvePlayUrlDetailed(song, 320)
        assertEquals("服务只应被打一次", listOf(320), svc.calls)
        assertFalse(second.isSuccess)
        assertTrue("缓存命中也必须带回 DEAD 语义", second.probedDead)
        assertTrue(first.probedDead)
    }

    @Test
    fun `forceRefresh 必须重探（重试与候选校验路径的命门）`() = runBlocking {
        manager.resolvePlayUrlDetailed(song, 320)
        manager.resolvePlayUrlDetailed(song, 320, forceRefresh = true)
        assertEquals(listOf(320, 320), svc.calls)
    }

    @Test
    fun `切档位即换 key，必须重探`() = runBlocking {
        manager.resolvePlayUrlDetailed(song, 320)
        manager.resolvePlayUrlDetailed(song, 128)
        assertEquals(listOf(320, 128), svc.calls)
    }

    @Test
    fun `clearPlayUrlCache 连负面缓存一起清（对齐 NasMusicApp 的切档清缓存）`() = runBlocking {
        manager.resolvePlayUrlDetailed(song, 320)
        manager.clearPlayUrlCache()
        manager.resolvePlayUrlDetailed(song, 320)
        assertEquals(listOf(320, 320), svc.calls)
    }

    @Test
    fun `普通失败不得进负面缓存——偶发故障不能钉 5 分钟`() = runBlocking {
        svc.verdict = ResolveResult.failure(320, probedDead = false)
        manager.resolvePlayUrlDetailed(song, 320)
        val r = manager.resolvePlayUrlDetailed(song, 320)
        assertEquals("没被探测确认就该重试", listOf(320, 320), svc.calls)
        assertFalse(r.probedDead)
    }

    @Test
    fun `成功结果仍走直链缓存（既有语义零回归）`() = runBlocking {
        svc.verdict = ResolveResult("https://cdn/320.mp3", 320)
        manager.resolvePlayUrlDetailed(song, 320)
        val second = manager.resolvePlayUrlDetailed(song, 320)
        assertEquals(listOf(320), svc.calls)
        assertEquals("https://cdn/320.mp3", second.url)
    }
}
