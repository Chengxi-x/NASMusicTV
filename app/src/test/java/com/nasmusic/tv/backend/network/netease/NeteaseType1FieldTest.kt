package com.nasmusic.tv.backend.network.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §2 C.1 的 0/100 落在代码上的形状（验收项 8 的单测护栏） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeteaseType1FieldTest {

    private val api = NeteaseOpenApi(baseUrlProvider = { "https://music.163.com" })

    /** type=1 的真实形态：**无 privilege、无 album.picUrl**，但 artists[].id / duration / album.name 齐备 */
    private val type1Body = """
      {"api_code":200,"result":{"songs":[
        {"id":1001,"name":"晴天","duration":269000,
         "artists":[{"id":6452,"name":"周杰伦"}],
         "album":{"id":99,"name":"叶惠美"},"fee":0}],
       "artistIds":[6452]}}
    """.trimIndent()

    @Test
    fun `type1 分支必须产出 null 封面且带 artistId`() {
        val songs = api.parseType1Songs(type1Body)
        val s = songs.single()
        assertNull("type=1 没有 album.picUrl → coverUrl 必须为 null，走既有无封面占位", s.coverUrl)
        assertEquals("叶惠美", s.album)          // 有的字段照旧要用
        assertEquals(269_000L, s.durationMs)
        // §2 C：type=1 自带 artists[].id → 强命中判定零额外请求的依据（Task 4 用它）
        assertEquals("6452", s.artistId)
        assertEquals("周杰伦", s.artist)
        assertEquals("ntwk_meting_1001", s.id)   // 与艺人分支同格式（红线 1）
    }
}
