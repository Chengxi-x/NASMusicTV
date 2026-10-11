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

    /**
     * 评审 I1：一条畸形记录不得拖垮整页。
     *
     * `unplayableIdsOf` 原先在 `parseArtistSongs` 的 `runCatching` **之外**被调用，
     * 而下面这三种形状都会抛（`privilege` 为 JSON null → ClassCastException、
     * `st` 为 JSON null → UnsupportedOperationException、元素不是对象 → IllegalStateException），
     * 异常会一路逸出到搜索/艺人页。打标集只是参考数据，读不出来就当作「未打标」。
     */
    @Test
    fun `畸形记录只当未打标——解析器不抛且一首歌都不丢`() {
        val malformed = """
          {"id":1001,"name":"晴天","duration":269000,
           "artists":[{"id":6452,"name":"周杰伦"}],
           "album":{"id":99,"name":"叶惠美","picUrl":"http://p/a.jpg"},"privilege":{"st":0}},
          {"id":1002,"name":"privilege是JSONnull","duration":1000,
           "artists":[{"id":6452,"name":"周杰伦"}],
           "album":{"id":99,"name":"叶惠美"},"privilege":null},
          {"id":1003,"name":"st是JSONnull","duration":1000,
           "artists":[{"id":6452,"name":"周杰伦"}],
           "album":{"id":99,"name":"叶惠美"},"privilege":{"st":null}},
          {"id":1004,"name":"没有privilege","duration":1000,
           "artists":[{"id":6452,"name":"周杰伦"}],
           "album":{"id":99,"name":"叶惠美"}},
          {"id":1005,"name":"不可播","duration":1000,
           "artists":[{"id":6452,"name":"周杰伦"}],
           "album":{"id":99,"name":"叶惠美"},"privilege":{"st":-200}},
          5
        """.trimIndent()

        val p = api.parseArtistSongs(pageBody(malformed, 6, false))

        assertEquals(6, p.total)
        assertTrue(!p.hasMore)
        // 畸形只影响打标，⛔ 不影响歌曲本身（5 条对象记录全部保留，裸数字 5 被丢弃）
        assertEquals(5, p.songs.size)
        assertEquals(
            listOf("1001", "1002", "1003", "1004", "1005"),
            p.songs.map { it.networkId }
        )
        // 打标集只认真正的非零数字 st
        assertEquals(setOf("ntwk_meting_1005"), p.unplayableIds)
    }

    /**
     * 评审 I2：网易会把一部分 id 发成 **JSON 字符串**。
     * `JsonPrimitive.toString()` 对字符串形态会**连引号一起返回**，
     * 于是 `Song.id` 变成 `ntwk_meting_"1001"`、`artistId` 变成 `"6452"`——
     * 红线 1 的逐字符格式被破坏，跨源 id 相等与 Task 4 的艺人 id 强匹配会静默失效。
     */
    @Test
    fun `字符串形态的 id 必须不带引号`() {
        val stringIds = """
          {"id":"1001","name":"晴天","duration":269000,
           "artists":[{"id":"6452","name":"周杰伦"}],
           "album":{"id":"99","name":"叶惠美","picUrl":"http://p/a.jpg"},"privilege":{"st":-200}}
        """.trimIndent()

        val p = api.parseArtistSongs(pageBody(stringIds, 1, true))
        val s = p.songs.single()

        assertEquals("ntwk_meting_1001", s.id)
        assertEquals("1001", s.networkId)
        assertEquals("6452", s.artistId)
        assertEquals("99", s.albumId)
        assertEquals(setOf("ntwk_meting_1001"), p.unplayableIds)
    }
}
