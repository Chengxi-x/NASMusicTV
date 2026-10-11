package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
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
}
