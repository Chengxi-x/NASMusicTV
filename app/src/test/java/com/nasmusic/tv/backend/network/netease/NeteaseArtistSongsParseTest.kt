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

    /**
     * 夹具助手：**线上实测形态**（顶层平铺，无 `data` 包装）。
     *
     * ⛔⛔ 不要再手写 `{"api_code":200,"data":{...}}` 这类**虚构信封**做夹具——
     * `api_code` 这个字段线上根本不存在，而它正是"测试全绿、线上全坏"的原因：
     * 单测用虚构形态喂解析器，解析器的信封假设就永远得不到证伪，
     * 于是 `parseArtistSongs` 只认 `data` 包裹这个 bug 藏了很久都没被测出来。
     *
     * 真实抓取见 [REAL_PAGE] 的来源说明。
     */
    private fun pageBody(items: String, total: Int, more: Boolean, code: String = "200") =
        """{"songs":[$items],"more":$more,"total":$total,"code":$code}"""

    /**
     * 历史实现唯一认识的 `data` 包裹形态——**容错行为**的覆盖，不是主路径。
     * 线上已实测不再返回该形态（见下），保留是为了防止它退化成"无数据"。
     */
    private fun dataWrappedBody(items: String, total: Int, more: Boolean) =
        """{"code":200,"data":{"songs":[$items],"total":$total,"more":$more}}"""

    /**
     * **线上真实抓取的响应原文**（裁剪成最小样本，只保留代码真正读取的字段）。
     *
     * - 来源：`GET https://music.163.com/api/v1/artist/songs
     *   ?id=6452&offset=0&limit=100&total=true&order=hot`
     *   （请求头 `Referer: https://music.163.com/` + 桌面 Chrome UA）
     * - 抓取时间：2026-10-11
     * - 原始响应顶层字段恰为 `songs, more, total, code`，**没有 `data` 键**；
     *   顶层 `songs` 数 100、`more=true`、`total=566`、含 `privilege` 的条目 100/100、
     *   `st` 分布 `-200 × 78 / 0 × 22`。
     * - 本样本取其中两条**真实多艺人**曲目，且 `privilege.st` 一非零（-200）一为零，
     *   正好覆盖打标集的两个分支。
     */
    private val REAL_PAGE = """
      {"songs":[
        {"id":298317,"name":"屋顶","duration":319039,
         "artists":[{"id":9612,"name":"温岚"},{"id":6452,"name":"周杰伦"}],
         "album":{"id":29597,"name":"有点野",
                  "picUrl":"https://p2.music.126.net/t1KaAJmIphZKdPq8JfItbA==/109951170514287417.jpg"},
         "privilege":{"st":-200}},
        {"id":210049,"name":"布拉格广场","duration":294600,
         "artists":[{"id":7219,"name":"蔡依林"},{"id":6452,"name":"周杰伦"}],
         "album":{"id":21349,"name":"看我72变",
                  "picUrl":"https://p2.music.126.net/8D2Pd7EuvGboMyE2xWc47A==/109951172453712025.jpg"},
         "privilege":{"st":0}}
       ],"more":true,"total":566,"code":200}
    """.trimIndent()

    /**
     * ⛔ **本文件的守门测试**：线上真实响应是**顶层平铺**的 `songs`，**没有 `data` 包装**。
     *
     * 解析器若只认 `{"data":{"songs":[…]}}`，对这个响应会恒定返回 `EMPTY_PAGE`，
     * 而 `NetworkArtistExpansion.runExpand` 拿到的是 `page != null` 但 `fetched.isEmpty()`
     * → `reset()` 后把原始 Meting 段原样交回，**全程不打日志、不报错**：
     * 艺人全量展开静默失效，用户在歌曲条目上永远看不到「可能无版权」角标与可播置顶。
     *
     * 所以这条用例的价值不在字段映射，而在**信封层**：它一旦变红，
     * 说明有人又把解析改回了单一形态。
     */
    @Test
    fun `回归-线上真实形态是顶层songs没有data包装-信封读错会让艺人全量展开静默失效`() {
        val p = api.parseArtistSongs(REAL_PAGE)

        assertEquals(2, p.songs.size)
        assertEquals(566, p.total)
        assertTrue(p.hasMore)
        // 打标集覆盖两种 st 分支：-200 进、0 不进
        assertEquals(setOf("ntwk_meting_298317"), p.unplayableIds)
    }

    /** 字段映射直接跑在真实响应原文上（不再经过手写信封）。 */
    @Test
    fun `字段映射与分页标志`() {
        val p = api.parseArtistSongs(REAL_PAGE)
        assertEquals(566, p.total)
        assertTrue(p.hasMore)                       // hasMore 取接口的 more，不猜
        assertEquals(2, p.songs.size)
        val first = p.songs.first()
        assertEquals("屋顶", first.title)
        assertEquals("温岚 / 周杰伦", first.artist)   // artists[] 多人名拼接（真实数据就是双艺人）
        assertEquals(319_039L, first.durationMs)     // 网易 duration 已是毫秒
        assertEquals("有点野", first.album)
        assertEquals("29597", first.albumId)
        assertEquals(
            "https://p2.music.126.net/t1KaAJmIphZKdPq8JfItbA==/109951170514287417.jpg",
            first.coverUrl
        )
        // artistId 取 artists[0].id（真实样本里第一位是合作歌手温岚，不是周杰伦本人）
        assertEquals("9612", first.artistId)
        // 打标集只含 st != 0 的那首
        assertEquals(setOf("ntwk_meting_298317"), p.unplayableIds)
    }

    /**
     * 容错：`data` 包裹形态虽然**线上已不再出现**（实测顶层平铺），
     * 但解析器仍应认它，而不是退化成"无数据"。
     *
     * ⚠️ 这条**不是**主用例——主用例是上面的真实形态。别再把这条当唯一覆盖。
     */
    @Test
    fun `容错-data包裹形态仍可解析`() {
        val p = api.parseArtistSongs(
            dataWrappedBody("""{"id":298317,"name":"屋顶","duration":319039,
                "artists":[{"id":9612,"name":"温岚"},{"id":6452,"name":"周杰伦"}],
                "album":{"id":29597,"name":"有点野","picUrl":"http://p/a.jpg"},
                "privilege":{"st":-200}}""", 566, true)
        )
        assertEquals(1, p.songs.size)
        assertEquals(566, p.total)
        assertTrue(p.hasMore)
        assertEquals(setOf("ntwk_meting_298317"), p.unplayableIds)
    }

    /**
     * ⛔ 分页标志必须与 `songs` 取自**同一个信封对象**。
     *
     * 根对象同时存在 `songs` 与 `data` 时（混合形态），选中的必须是根对象——
     * 绝不能"songs 读根、more/total 读 data"，那会拼出一个线上从不存在的组合，
     * 让 `hasMore`/`total` 静默错位。
     */
    @Test
    fun `根与data同时存在时songs与分页标志必须同源`() {
        val mixed = """
          {"songs":[{"id":298317,"name":"屋顶","duration":319039,
            "artists":[{"id":9612,"name":"温岚"}],
            "album":{"id":29597,"name":"有点野","picUrl":"http://p/a.jpg"},
            "privilege":{"st":-200}}],
           "more":true,"total":566,
           "data":{"songs":[],"more":false,"total":999999}}
        """.trimIndent()

        val p = api.parseArtistSongs(mixed)
        assertEquals(1, p.songs.size)
        assertEquals(566, p.total)            // ⛔ 不是 data 里的 999999
        assertTrue(p.hasMore)                 // ⛔ 不是 data 里的 false
    }

    /** 顶层 `code != 200` → 按取数失败处理（返回 EMPTY_PAGE）。 */
    @Test
    fun `顶层code非200时按取数失败处理`() {
        val body = pageBody("""{"id":298317,"name":"屋顶","duration":319039,
            "artists":[{"id":9612,"name":"温岚"}],
            "album":{"id":29597,"name":"有点野"},"privilege":{"st":0}}""", 1, true, code = "301")
        val p = api.parseArtistSongs(body)
        assertEquals(NeteaseOpenApi.EMPTY_PAGE, p)
    }

    /**
     * ⛔ `code` **缺失**不是失败：不得因为缺 `code` 就拒掉整页。
     *
     * 另附两条：两种信封都取不到 `songs` → `EMPTY_PAGE`（含 `songs` 为 JSON null 的形态，
     * 裸 `getAsJsonArray` 会在这里抛 `IllegalStateException` 一路逸出）。
     */
    @Test
    fun `code缺失照常解析且两种形态都无songs时返回空页`() {
        val noCode = """{"songs":[{"id":298317,"name":"屋顶","duration":319039,
            "artists":[{"id":9612,"name":"温岚"}],
            "album":{"id":29597,"name":"有点野"},"privilege":{"st":0}}],
            "more":true,"total":566}""".trimIndent()
        assertEquals(1, api.parseArtistSongs(noCode).songs.size)

        assertEquals(NeteaseOpenApi.EMPTY_PAGE, api.parseArtistSongs("""{"more":true,"total":566}"""))
        assertEquals(NeteaseOpenApi.EMPTY_PAGE, api.parseArtistSongs("""{"songs":null}"""))
        assertEquals(NeteaseOpenApi.EMPTY_PAGE, api.parseArtistSongs("not json at all"))
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
          {"id":298317,"name":"屋顶","duration":319039,
           "artists":[{"id":9612,"name":"温岚"},{"id":6452,"name":"周杰伦"}],
           "album":{"id":29597,"name":"有点野","picUrl":"http://p/a.jpg"},"privilege":{"st":0}},
          {"id":210049,"name":"privilege是JSONnull","duration":1000,
           "artists":[{"id":9612,"name":"温岚"}],
           "album":{"id":29597,"name":"有点野"},"privilege":null},
          {"id":186016,"name":"st是JSONnull","duration":1000,
           "artists":[{"id":9612,"name":"温岚"}],
           "album":{"id":29597,"name":"有点野"},"privilege":{"st":null}},
          {"id":186139,"name":"没有privilege","duration":1000,
           "artists":[{"id":9612,"name":"温岚"}],
           "album":{"id":29597,"name":"有点野"}},
          {"id":29803531,"name":"不可播","duration":1000,
           "artists":[{"id":9612,"name":"温岚"}],
           "album":{"id":29597,"name":"有点野"},"privilege":{"st":-200}},
          5
        """.trimIndent()

        val p = api.parseArtistSongs(pageBody(malformed, 6, false))

        assertEquals(6, p.total)
        assertTrue(!p.hasMore)
        // 畸形只影响打标，⛔ 不影响歌曲本身（5 条对象记录全部保留，裸数字 5 被丢弃）
        assertEquals(5, p.songs.size)
        assertEquals(
            listOf("298317", "210049", "186016", "186139", "29803531"),
            p.songs.map { it.networkId }
        )
        // 打标集只认真正的非零数字 st
        assertEquals(setOf("ntwk_meting_29803531"), p.unplayableIds)
    }

    /**
     * 评审 I2：网易会把一部分 id 发成 **JSON 字符串**。
     * `JsonPrimitive.toString()` 对字符串形态会**连引号一起返回**，
     * 于是 `Song.id` 变成 `ntwk_meting_"298317"`、`artistId` 变成 `"6452"`——
     * 红线 1 的逐字符格式被破坏，跨源 id 相等与 Task 4 的艺人 id 强匹配会静默失效。
     *
     * ⚠️ 2026-10-11 线上实测那 100 条**全是数字形态**（0/100 字符串），
     * 所以这条是防御性归一（防上游哪天改口径），不是当前形态的复现。
     */
    @Test
    fun `字符串形态的 id 必须不带引号`() {
        val stringIds = """
          {"id":"298317","name":"屋顶","duration":319039,
           "artists":[{"id":"9612","name":"温岚"}],
           "album":{"id":"29597","name":"有点野","picUrl":"http://p/a.jpg"},"privilege":{"st":-200}}
        """.trimIndent()

        val p = api.parseArtistSongs(pageBody(stringIds, 1, true))
        val s = p.songs.single()

        assertEquals("ntwk_meting_298317", s.id)
        assertEquals("298317", s.networkId)
        assertEquals("9612", s.artistId)
        assertEquals("29597", s.albumId)
        assertEquals(setOf("ntwk_meting_298317"), p.unplayableIds)
    }
}
