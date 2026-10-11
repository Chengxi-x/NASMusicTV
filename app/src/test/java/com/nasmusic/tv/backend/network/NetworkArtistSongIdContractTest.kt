package com.nasmusic.tv.backend.network

import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.download.model.downloadKeyOf
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkArtistSongIdContractTest {

    /** Meting 端点真实形状的一条搜索结果（title/author/url 里带 id） */
    private val metingSearchBody = """
      [{"title":"晴天","author":"周杰伦",
        "url":"https://music.163.com/song?id=1001",
        "pic":"https://p.example/pic?1","lrc":"https://lrc.example/1"}]
    """.trimIndent()

    @Test
    fun `meting 搜索结果的 id 必须等于唯一构造点的产物`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(metingSearchBody).setHeader("Content-Type", "application/json"))
        try {
            val svc = MetingApiService(baseUrlProvider = { server.url("/api").toString() })
            val songs: List<Song> = svc.search("周杰伦", limit = 30)
            assertEquals(1, songs.size)
            val song = songs.first()
            // 红线 1：格式逐字符
            assertEquals("ntwk_meting_1001", song.id)
            // 红线 2：路由键
            assertEquals("meting", song.networkSource)
            assertEquals("1001", song.networkId)
            assertEquals(NetworkSongId.forNetworkSong("meting", "1001"), song.id)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `构造点不会被误写成平台名`() {
        assertEquals("ntwk_meting_1001", NetworkSongId.forNetworkSong("meting", "1001"))
        // 负面自检：若有人把 sourceId 写成 netease，本断言会抓住格式漂移（红线 2 的姊妹条）
        org.junit.Assert.assertNotEquals("ntwk_meting_1001", NetworkSongId.forNetworkSong("netease", "1001"))
    }

    @Test
    fun `同一 netId 下网易侧与 Meting 侧生成的 id 必须逐字符相等`() {
        // 红线 1 的正体：两条链路共用 NetworkSongId，任何一侧改写格式都会在这里被抓到
        val netease = com.nasmusic.tv.backend.network.netease.NeteaseOpenApi(
            baseUrlProvider = { "https://music.163.com" }
        )
        val item = com.google.gson.JsonObject().apply {
            addProperty("id", 1001L)
            addProperty("name", "晴天")
            addProperty("duration", 269000L)
            add("artists", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("id", 6452L); addProperty("name", "周杰伦")
                })
            })
        }
        val s = netease.toSong(item)!!
        assertEquals(NetworkSongId.forNetworkSong("meting", "1001"), s.id)
        assertEquals("ntwk_meting_1001", s.id)
        assertEquals("meting", s.networkSource)   // 红线 2
    }

    @Test
    fun `网易侧字符串形态的 id 同样必须逐字符等于构造点产物`() {
        // 评审 I2：网易会把 id 发成 JSON 字符串，JsonPrimitive.toString() 带引号 → 红线 1 直接破
        val netease = com.nasmusic.tv.backend.network.netease.NeteaseOpenApi(
            baseUrlProvider = { "https://music.163.com" }
        )
        val item = com.google.gson.JsonObject().apply {
            addProperty("id", "1001")
            addProperty("name", "晴天")
            addProperty("duration", 269000L)
            add("artists", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("id", "6452"); addProperty("name", "周杰伦")
                })
            })
            add("album", com.google.gson.JsonObject().apply {
                addProperty("id", "99"); addProperty("name", "叶惠美")
            })
        }
        val s = netease.toSong(item)!!
        assertEquals(NetworkSongId.forNetworkSong("meting", "1001"), s.id)
        assertEquals("ntwk_meting_1001", s.id)
        assertEquals("1001", s.networkId)
        assertEquals("6452", s.artistId)
        assertEquals("99", s.albumId)
        assertEquals("meting", s.networkSource)   // 红线 2
    }

    // ── F5（终审 B1）：红线 2 合法值收口到 NetworkSongId.METING_SOURCE 后的等价门 ──

    @Test
    fun `F5 权威常量与枚举 key 和 sourceId 三者必须一致`() {
        // main 源集的 8 个声明点收口后，剩余两个「合法独立位」必须恒等于权威常量：
        // ① NetworkSource 枚举 key（UI 侧，⛔ 不改枚举本身）；② MetingApiService.sourceId（接口实现值）
        assertEquals("红线 2 唯一合法值漂移", "meting", NetworkSongId.METING_SOURCE)
        assertEquals(NetworkSongId.METING_SOURCE, NetworkSource.METING.key)
        val svc = MetingApiService(baseUrlProvider = { "https://invalid.invalid/api" })
        assertEquals(NetworkSongId.METING_SOURCE, svc.sourceId)
        // 注册表 key（NasMusicApp services map 的键）就是同一个常量；负自检：
        // 若有人把枚举 key 或 sourceId 改漂，上面两条断言之一必红
        assertNotEquals(NetworkSongId.METING_SOURCE, NetworkSource.ALAPI.key)
    }

    @Test
    fun `F5 同一首 Meting 网络歌的下载主键必须逐字符等于 forNetworkSong`() {
        // DownloadKeys 的同族缺口：downloadKey 用「networkSource + networkId」独立拼主键，
        // 今天与 NetworkSongId.forNetworkSong 等价，但没有测试钉 —— 这条就是那个钉。
        // ⚠️ 比对的是不带 `:q` 后缀的形态（downloadKeyOf 在 quality ≤ AUTO 时原样返回 downloadKey，
        // 见 DownloadKeys.kt:43；AUTO 档是存量行的天然键，带后缀形态拿去比对是错的）。
        val song = Song(
            id = NetworkSongId.forNetworkSong(NetworkSongId.METING_SOURCE, "1001"),
            title = "晴天",
            artist = "周杰伦",
            isNetworkSong = true,
            networkSource = NetworkSongId.METING_SOURCE,
            networkId = "1001",
        )
        assertEquals(
            NetworkSongId.forNetworkSong(song.networkSource!!, song.networkId!!),
            song.downloadKey
        )
        assertEquals("AUTO 档的 downloadKeyOf 必须原样等于 downloadKey", song.downloadKey, song.downloadKeyOf(QualityTiers.AUTO))
    }
}
