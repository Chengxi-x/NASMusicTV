package com.nasmusic.tv.backend.network.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeteaseArtistSongsParseTest {

    // 解析器是**实例方法**（toSong 依赖 METING_SOURCE 常量与后续可能的实例配置），
    // 单测构造一个永不发起请求的实例直打纯函数——baseUrlProvider 只是占位。
    private val api = NeteaseOpenApi(baseUrlProvider = { "https://music.163.com" })

    private fun pageBody(items: String, total: Int, more: Boolean) =
        """{"api_code":200,"data":{"songs":[$items],"total":$total,"more":$more}}"""

    private val twoSongs = """
      {"id":1001,"name":"晴天","duration":269000,
       "artists":[{"id":6452,"name":"周杰伦"},{"id":7,"name":"彭濼"}],
       "album":{"id":99,"name":"叶惠美","picUrl":"http://p/a.jpg"},"fee":0,
       "privilege":{"st":0}},
      {"id":1002,"name":"安静","duration":300000,
       "artists":[{"id":6452,"name":"周杰伦"}],
       "album":{"id":99,"name":"叶惠美","picUrl":"http://p/a.jpg"},"fee":0,
       "privilege":{"st":-200}}
    """.trimIndent()

    @Test
    fun `字段映射与分页标志`() {
        val p = api.parseArtistSongs(pageBody(twoSongs, 566, true))
        assertEquals(566, p.total)
        assertTrue(p.hasMore)                       // hasMore 取接口的 more，不猜
        assertEquals(2, p.songs.size)
        val first = p.songs.first()
        assertEquals("晴天", first.title)
        assertEquals("周杰伦 / 彭濼", first.artist)   // artists[] 多人名拼接
        assertEquals(269_000L, first.durationMs)     // 网易 duration 已是毫秒
        assertEquals("叶惠美", first.album)
        assertEquals("99", first.albumId)
        assertEquals("http://p/a.jpg", first.coverUrl)
        assertEquals("6452", first.artistId)
        // 打标集只含 st != 0 的那首
        assertEquals(setOf("ntwk_meting_1002"), p.unplayableIds)
    }

    @Test
    fun `limit 入参即钳制且末页 more=false 时 hasMore 为 false`() {
        val last = api.parseArtistSongs(pageBody("""{"id":1,"name":"x","duration":1000,
            "artists":[{"id":6452,"name":"A"}],"album":{"id":2,"name":"b"},"privilege":{"st":0}}""", 100, false))
        assertTrue(!last.hasMore)
        // 钳制函数是纯函数，直接测（网络请求侧由 NeteasePrioritySearchTest 覆盖）
        assertEquals(100, NeteaseOpenApi.clampLimit(500))
        assertEquals(100, NeteaseOpenApi.clampLimit(100))
        assertEquals(1, NeteaseOpenApi.clampLimit(0))
        assertEquals(1, NeteaseOpenApi.clampLimit(-5))
    }
}
