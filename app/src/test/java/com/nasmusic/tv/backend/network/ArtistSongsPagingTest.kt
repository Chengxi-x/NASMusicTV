package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 艺人全量展开的状态件（艺人全量方案 §3.5）。
 *
 * 纯 JVM 类，不加 Robolectric——`NetworkArtistExpansion` 无 Android 依赖，
 * 省掉一轮 sandbox 启动。
 */
class ArtistSongsPagingTest {

    private fun song(id: String, artist: String, artistId: String? = "6452") = Song(
        id = "ntwk_meting_$id", title = "t$id", artist = artist, artistId = artistId,
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    private fun page(ids: List<String>, dead: List<String>, more: Boolean, total: Int) = ArtistSongsPage(
        songs = ids.map { song(it, "周杰伦") },
        total = total,
        hasMore = more,
        unplayableIds = dead.map { "ntwk_meting_$it" }.toSet()
    )

    /** 记录调用次序的假后端：fetches 存 (artistId, offset) */
    private class Fake(val pages: MutableMap<Int, ArtistSongsPage?>, val fetches: MutableList<Pair<String, Int>>) {
        var findArtistCalls = 0
        fun expansion() = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; pages[offset] },
            findArtist = { _ -> findArtistCalls++; NetworkArtistInfo("6452", "周杰伦", 566) }
        )
    }

    @Test
    fun `强命中读首屏自带的 artistId 且零额外 findArtist`() = runBlocking {
        val f = Fake(
            mutableMapOf(0 to page((1..100).map { "$it" }, dead = listOf("7"), more = true, total = 566)),
            mutableListOf()
        )
        val exp = f.expansion()
        val out = exp.expand("周杰伦", listOf(song("900", "周杰伦"), song("901", "周杰伦")))
        assertEquals(0, f.findArtistCalls)
        assertEquals(NetworkArtistExpansion.HitSource.NATIVE_FIELD, exp.lastHitSource)
        assertEquals(100, out.size)
        assertEquals(listOf("6452" to 0), f.fetches)
        assertEquals(setOf("ntwk_meting_7"), exp.unplayableIds)
    }

    @Test
    fun `末页恰满 100 且 more=false 时不得再多发一次请求`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page((1..100).map { "$it" }, emptyList(), more = false, total = 100) },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        val afterFirst = fetches.size
        exp.loadMore()
        assertEquals("§2 D：more 已转 false，照 size==pageSize 猜就会多发一次空请求", afterFirst, fetches.size)
        assertFalse(exp.paging.hasMore)
    }

    @Test
    fun `续页按已加载条数取 offset 且累积打标集`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ ->
                fetches += id to offset
                page(((offset + 1)..(offset + 100)).map { "$it" }, dead = listOf("${offset + 1}"), more = offset < 400, total = 566)
            },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        exp.loadMore()
        exp.loadMore()
        assertEquals(listOf("6452" to 0, "6452" to 100, "6452" to 200), fetches)
        assertEquals(300, exp.paging.songs.size)
        assertEquals(3, exp.unplayableIds.size)
    }

    @Test
    fun `可播置顶保持稳定序且只作用于艺人分支`() {
        val dead = setOf("ntwk_meting_2", "ntwk_meting_3")
        val songs = listOf(song("1", "周杰伦"), song("2", "周杰伦"), song("3", "周杰伦"), song("4", "周杰伦"))
        val out = NetworkArtistExpansion.playableFirst(songs, dead)
        assertEquals(listOf("ntwk_meting_1", "ntwk_meting_4", "ntwk_meting_2", "ntwk_meting_3"), out.map { it.id })
        // 打标集为空（普通搜索分支 / 端点兜底分支的形状）→ 顺序必须原样不动
        val plain = listOf(song("5", "周杰伦"), song("6", "周杰伦"))
        assertEquals(plain.map { it.id }, NetworkArtistExpansion.playableFirst(plain, emptySet()).map { it.id })
    }

    @Test
    fun `弱匹配不算命中——周杰不得展开周杰伦的名录`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page(listOf("1"), emptyList(), more = false, total = 1) },
            findArtist = { null }      // type=100 兜底也没命中
        )
        val networkPart = listOf(song("900", "周杰伦"))
        val out = exp.expand("周杰", networkPart)
        assertEquals("§10.2 规则 4：前缀匹配是产品级事故", networkPart, out)
        assertTrue(fetches.isEmpty())
        assertFalse(exp.isActive())
        assertTrue(exp.unplayableIds.isEmpty())
    }

    @Test
    fun `feat 合唱行不算主艺人的反例要成立`() {
        // 关键词是主唱本人时，含 feat 的行仍应命中（ArtistSplitter 已拆 feat.）
        val withFeat = listOf(Song(id = "ntwk_meting_1", title = "x", artist = "周杰伦 / 彭濼",
            artistId = "6452", isNetworkSong = true, networkSource = "meting", networkId = "1"))
        assertEquals("6452", NetworkArtistExpansion.hitArtistId("彭濼", withFeat))
        // 但"周杰伦 / 彭濼"整体作为一个 artist 字段时，关键词"周杰伦"同样命中
        assertEquals("6452", NetworkArtistExpansion.hitArtistId("周杰伦", withFeat))
    }

    @Test
    fun `合并结果 id 必须唯一——重复 key 会让 LazyVerticalGrid 直接崩`() {
        val expanded = listOf(song("1", "周杰伦"), song("2", "周杰伦"))
        val other = listOf(song("1", "周杰伦"), song("9", "蔡依林"))     // 非网络段与展开段撞同一 netId
        val merged = NetworkArtistExpansion.mergeWithExpansion(other, expanded)
        assertEquals(3, merged.size)
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
        // distinctBy 保 first-wins：非网络段在前（mergeWithExpansion 的入参次序），撞车的展开段被丢弃
        assertEquals(listOf("ntwk_meting_1", "ntwk_meting_9", "ntwk_meting_2"), merged.map { it.id })
    }

    @Test
    fun `reset 后不再续取（R7 切艺人即重置）`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page(listOf("1"), emptyList(), more = true, total = 566) },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        exp.reset()
        assertFalse(exp.isActive())
        assertEquals(0, exp.loadMore().size)
        assertTrue(exp.unplayableIds.isEmpty())
    }

    // ── Task 4 评审修复的回归护栏（C1 / I1 / I2）────────────────────────

    /**
     * C1：网络段的判据**只能是 Meting 家族**。
     *
     * Jamendo（`JamendoService`）与百度网盘（`BaiduFile`）的歌同样 `isNetworkSong == true`，
     * 旧判据 `partition { it.isNetworkSong }` 会把它们一起喂给 expansion，
     * 命中艺人时整段被替换 ⇒ 统一搜索网格里百度/Jamendo 的命中行凭空消失。
     */
    @Test
    fun `C1 网络段判据只认 Meting 家族——Jamendo 百度 NAS 行必须存活`() = runBlocking {
        val meting = song("900", "周杰伦")
        val jamendo = Song(
            id = "ntwk_jamendo_7", title = "j", artist = "周杰伦",
            isNetworkSong = true, networkSource = "jamendo", networkId = "7"
        )
        val baidu = Song(
            id = "ntwk_baidu_8", title = "b", artist = "周杰伦",
            isNetworkSong = true, networkSource = "baidu", networkId = "8"
        )
        val nas = Song(id = "nas_9", title = "n", artist = "周杰伦")
        val fused = listOf(meting, jamendo, baidu, nas)

        val (metingPart, otherPart) = NetworkArtistExpansion.splitMetingSegment(fused)
        assertEquals(listOf(meting), metingPart)
        assertEquals("别的源必须整条透传，⛔ 不得进 expansion", listOf(jamendo, baidu, nas), otherPart)

        val f = Fake(
            mutableMapOf(0 to page((1..100).map { "$it" }, emptyList(), more = true, total = 566)),
            mutableListOf()
        )
        val expanded = f.expansion().expand("周杰伦", metingPart)
        val merged = NetworkArtistExpansion.mergeWithExpansion(otherPart, expanded)
        val ids = merged.map { it.id }
        for (survivor in listOf(jamendo, baidu, nas)) {
            assertTrue("C1 回归：${survivor.id} 被艺人展开吃掉了", survivor.id in ids)
        }
        assertEquals(103, merged.size)
        assertEquals(merged.size, ids.distinct().size)
    }

    /**
     * C1（空段分支）：本词没有 Meting 行（网络源被熄灭 / 首屏全是别的源）时
     * **一个请求都不发**——旧实现会在每次普通搜索（只搜歌名）时白打一次 `music.163.com`。
     */
    @Test
    fun `C1 空 Meting 段必须整体跳过展开且一次 findArtist 都不发`() = runBlocking {
        val f = Fake(
            mutableMapOf(0 to page((1..100).map { "$it" }, emptyList(), more = true, total = 566)),
            mutableListOf()
        )
        val exp = f.expansion()
        val out = exp.expand("周杰伦", emptyList())
        assertEquals("C1：空段不得触发 type=100 兜底请求", 0, f.findArtistCalls)
        assertTrue("C1：空段不得触发艺人取数", f.fetches.isEmpty())
        assertEquals(emptyList<Song>(), out)
        assertFalse(exp.isActive())
        assertFalse(exp.paging.hasMore)
        assertEquals(0, exp.paging.songs.size)
        assertTrue(exp.unplayableIds.isEmpty())
    }

    /**
     * I1：`loadMore()` 必须真单飞。
     *
     * 旧实现的在途门读 `paging.isLoading`，而那个字段被硬编码为 false ⇒ 门恒不生效，
     * 两次并发调用读到同一个 `fetched.size` 当 offset、各自 accumulate 一次，
     * `fetched.size` 被虚高 ⇒ **下一页的 offset 永久跳过一整页**（`distinctBy` 只是把重复藏进网格）。
     */
    @Test
    fun `I1 并发 loadMore 只发一次请求且下一页 offset 不被抬高`() = runBlocking {
        val offsets = mutableListOf<Int>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { _, offset, _ ->
                offsets += offset
                if (offset > 0 && offsets.size == 2) entered.complete(Unit)
                if (offset > 0) release.await()
                page(((offset + 1)..(offset + 100)).map { "$it" }, emptyList(), more = offset < 200, total = 566)
            },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        assertEquals(listOf(0), offsets)

        val inFlight = launch { exp.loadMore() }
        entered.await()
        assertTrue("I1：在途期间 paging.isLoading 必须为真（UI 脚注靠它）", exp.paging.isLoading)
        val snapshot = exp.loadMore()                       // 第二次调用必须被单飞挡下
        assertEquals("I1：在途期间的第二次调用不得再打接口", listOf(0, 100), offsets)
        release.complete(Unit)
        inFlight.join()

        assertFalse(exp.isLoadingMore())
        assertEquals(200, exp.paging.songs.size)
        assertEquals("在途期间第二次调用只拿到当时的快照", 100, snapshot.size)
        // 关键：第三次续页的 offset 必须是 200；旧实现这里是 300（一整页被永久跳过）
        exp.loadMore()
        assertEquals(listOf(0, 100, 200), offsets)
        assertEquals(300, exp.paging.songs.size)
        assertEquals(300, exp.paging.songs.map { it.id }.distinct().size)
    }

    /**
     * I2：命中艺人但首页零行 + `more == true` 的矛盾态。
     *
     * 旧实现 `artistId` 只从 `page.songs.firstOrNull()` 反推，首页为空 ⇒ `isActive()==false`
     * 而 `hasMore==true`：分页永久卡死，且 `expand()` 返回 `playableFirst(emptyList, …) == emptyList`
     * ⇒ **把手里的网络段整段丢掉**。
     */
    @Test
    fun `I2 首页空的命中必须原样交回网络段并把 more 转 false`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ ->
                fetches += id to offset
                ArtistSongsPage(songs = emptyList(), total = 566, hasMore = true, unplayableIds = emptySet())
            },
            findArtist = { null }
        )
        val networkPart = listOf(song("900", "周杰伦"), song("901", "周杰伦"))
        val out = exp.expand("周杰伦", networkPart)
        assertEquals("I2：空首页不得丢弃交进来的网络段", networkPart, out)
        assertFalse("I2：isActive 与 hasMore 必须一致", exp.isActive())
        assertFalse("I2：不得留下 hasMore=true 却无以为继的卡死态", exp.paging.hasMore)
        assertEquals(0, exp.paging.songs.size)
        assertTrue(exp.unplayableIds.isEmpty())
        // 兜底：即使 Task 8 的 UI 触底也不会再发请求
        exp.loadMore()
        assertEquals(listOf("6452" to 0), fetches)
    }

    /** FIND_ARTIST 兜底命中同样受 I2 保护：空首页 ⇒ 原样交回 + more=false */
    @Test
    fun `I2 findArtist 兜底命中后首页为空时同样交回原段`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ ->
                fetches += id to offset
                ArtistSongsPage(songs = emptyList(), total = 566, hasMore = true, unplayableIds = emptySet())
            },
            findArtist = { NetworkArtistInfo("6452", "周杰伦", 566) }
        )
        val networkPart = listOf(song("900", "蔡依林"))        // 首屏没有周杰伦的行 ⇒ 走 findArtist
        val out = exp.expand("周杰伦", networkPart)
        assertEquals(networkPart, out)
        assertFalse(exp.isActive())
        assertFalse(exp.paging.hasMore)
        assertEquals(NetworkArtistExpansion.HitSource.NONE, exp.lastHitSource)
    }

    /**
     * C1 / I1 的 **VM 侧源码门**：钉的是 VM 里的**接线形状**（判据用哪个字段、闸门顺序）。
     * ⚠️ 「`SearchViewModel` 是 `AndroidViewModel`、本仓库不可单测」是**旧结论，已作废**——
     * 行为级测试见 `ui/viewmodel/ArtistExpansionGenerationGateTest`（Robolectric + 真 VM + 假服务）。
     * 本门留着是因为「日志打在闸门哪一侧」这类文本形状行为断言抓不到（范本同 `ci/LocalMusicGateTest.kt`）。
     */
    @Test
    fun `C1 I1 源码门 SearchViewModel 必须按 Meting 家族分段并带点亮与在途闸门`() {
        val src = mainSourceFile("ui/viewmodel/SearchViewModel.kt")
        assertFalse(
            "C1：VM 不得再用裸 isNetworkSong 当网络段判据（会把 Jamendo/百度 一起喂给 expansion）",
            src.contains("partition { it.isNetworkSong }")
        )
        assertTrue(
            "C1：VM 必须走 NetworkArtistExpansion.splitMetingSegment（判据与红线 2 同源）",
            src.contains("NetworkArtistExpansion.splitMetingSegment(")
        )
        assertTrue(
            "C1：熄灭 NETWORK_MUSIC 后不得被任何路径复活，VM 必须读点亮集合",
            src.contains("MusicSourceType.NETWORK_MUSIC in _enabledSearchSources.value")
        )
        assertTrue(
            "I1：VM 的在途门必须读 expansion 的真实单飞标志",
            src.contains("artistExpansion.isLoadingMore()")
        )
        assertFalse(
            "I1：⛔ 不得再拿 paging.isLoading 当判据（该门曾恒不生效）",
            src.contains("artistExpansion.paging.isLoading")
        )
    }

    /**
     * F6 的头号静默路径**必须被真实执行**（终审 F5F6F8 审查 I-2）：
     * 命中艺人 + 取作品返回 null ⇒ 原样交回网络段，并把留痕信号置起来。
     *
     * ⚠️ 这条分支此前**全仓 0 个测试覆盖**（所有假件的 `getArtistSongs` 恒非空），
     * 于是「F6 修的那个路径」与它新加的信号位都没跑过：改坏它编译过、lint 过、其余测试全绿，
     * 用户在电视上看到的还是「周杰伦只有 30 行」而 release 一条日志都没有。
     */
    @Test
    fun `F6 命中艺人但取作品返回 null——交回原段并置起留痕信号`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; null },
            findArtist = { null }
        )
        val networkPart = listOf(song("900", "周杰伦"), song("901", "周杰伦"))
        val out = exp.expand("周杰伦", networkPart)
        assertEquals("取数失败必须原样交回网络段（⛔ 不得丢行）", networkPart, out)
        assertFalse("放弃展开 ⇒ 不活跃", exp.isActive())
        assertFalse("放弃展开 ⇒ 不得残留 hasMore=true 的卡死态", exp.paging.hasMore)
        assertTrue("F6 留痕信号必须置位——release 上这是唯一权威痕迹", exp.lastArtistPageFetchFailed)
        assertEquals(listOf("6452" to 0), fetches)
    }

    /** I2 的「接口答了、只是答空」**不是** F6 那条路径 ⇒ 信号不得置位（否则留痕会说谎）。 */
    @Test
    fun `I2 首页零行不算取数失败——F6 信号不得置位`() = runBlocking {
        val exp = NetworkArtistExpansion(
            getArtistSongs = { _, _, _ ->
                ArtistSongsPage(songs = emptyList(), total = 566, hasMore = true, unplayableIds = emptySet())
            },
            findArtist = { null }
        )
        val out = exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        assertEquals(1, out.size)
        assertFalse("空首页 ≠ 取数失败", exp.lastArtistPageFetchFailed)
    }

    /**
     * 审查 I-1 的**重叠展开**回归网：成功返回的那一次，⛔ 不得读到前一个失败调用留下的信号。
     *
     * 现场：A 词命中艺人后挂起在 `getArtistSongs` 上；期间 B 词取作品返回 null（置位 true）；
     * A 随后**成功**返回。信号若只在入口复位（改造前就是这个写法），A 的调用侧读到的就是 B 的结论
     * ⇒ release 上会出现「正常展开成功的词也在报取作品失败」。留痕是唯一权威痕迹，**说谎比没有更糟**。
     */
    @Test
    fun `I-1 重叠展开——成功返回的那次不得继承前一个失败调用的信号`() = runBlocking {
        val gateA = CompletableDeferred<Unit>()
        var aCalls = 0
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, _, _ ->
                if (id == "6452" && aCalls++ == 0) {
                    gateA.await()                                     // A 停在取数上
                    page(listOf("1", "2"), dead = emptyList(), more = false, total = 2)
                } else null                                           // B 取作品返回 null
            },
            findArtist = { null }
        )
        val a = async { exp.expand("周杰伦", listOf(song("900", "周杰伦"))) }
        yield()                                                       // ⛔ 不 yield 的话 A 根本没开始 ⇒ 假自证
        val bOut = exp.expand("蔡依林", listOf(song("950", "蔡依林", "777")))
        assertEquals("B 取数失败 ⇒ 原样交回自己的网络段", 1, bOut.size)
        assertTrue("B 自己的失败必须置位", exp.lastArtistPageFetchFailed)

        gateA.complete(Unit)
        val outA = a.await()
        assertEquals("A 成功 ⇒ 取回展开的两行", 2, outA.size)
        assertFalse("I-1 回归：A 成功返回后不得残留 B 的失败信号", exp.lastArtistPageFetchFailed)
    }

    /**
     * 审查 I-1 的 **VM 侧源码门**：F6 留痕必须①紧接着 `expand()` 取进局部量、
     * ②打在代数闸门**之后**、③用 `AppLog.e`（`AppLog.d/i/w` 方法体有 `if (BuildConfig.DEBUG)` 守卫，
     * release 下 R8 连字符串一起折掉，只有 `e` 必然存活）。
     * ⚠️ VM 的**行为**可测（`ArtistExpansionGenerationGateTest`），但「日志落在闸门哪一侧」
     * 不是可观测行为 ⇒ 这一条只能靠源码门钉。
     */
    @Test
    fun `I-1 源码门 F6 留痕必须先取信号、过闸门后再打且必须是 AppLog_e`() {
        val flat = Regex("""\s+""").replace(mainSourceFile("ui/viewmodel/SearchViewModel.kt"), " ")
        val expandAt = flat.indexOf("artistExpansion.expand(query, metingPart)")
        val readAt = flat.indexOf("artistExpansion.lastArtistPageFetchFailed")
        val logAt = flat.indexOf("命中艺人但 getArtistSongs 返回 null")
        assertTrue("锚点缺失：expand 调用没找到 ⇒ 读的是错的函数/文件", expandAt >= 0)
        assertTrue("F6 留痕信号没被 VM 读取 ⇒ 静默路径又无痕了", readAt >= 0)
        assertTrue("F6 留痕日志不在源码里 ⇒ 头号路径 release 仍然 0 痕迹", logAt >= 0)
        assertTrue("I-1：信号必须在 expand **返回后立刻**取走（跨词读会读到别人的结论）", readAt > expandAt)
        val gate = flat.indexOf("if (gen != searchGeneration) return@launch", readAt)
        assertTrue("I-1：取信号之后找不到代数闸门 ⇒ 陈旧发布会带着留痕一起出去", gate >= 0)
        assertTrue("I-1：留痕必须打在闸门**之后**（被改词覆盖的陈旧搜索不该在 release 里留痕）", logAt > gate)
        // 「最近的一次 AppLog.* 调用必须是 .e」——⛔ 不要按固定字符窗口找：
        // tag / `$query` 插值的长度一变就会假红（本仓库第一次跑就栽在 40 字符窗口上）。
        val head = flat.substring((logAt - 200).coerceAtLeast(0), logAt)
        val lastCall = head.lastIndexOf("AppLog.")
        assertTrue(
            "F6：留痕日志前找不到任何 AppLog 调用 ⇒ 文案可能被搬出调用：$head",
            lastCall >= 0 && head.substring(lastCall).startsWith("AppLog.e")
        )
    }

    /** 与 `ci/LocalMusicGateTest.kt` 同一套路径兜底（user.dir 可能是 app/ 也可能是仓库根） */
    private fun mainSourceFile(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        val file = root!!.resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }
}
