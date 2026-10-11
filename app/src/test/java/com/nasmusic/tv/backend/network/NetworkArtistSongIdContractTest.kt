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
}
