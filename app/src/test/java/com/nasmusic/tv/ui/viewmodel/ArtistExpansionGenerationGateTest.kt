package com.nasmusic.tv.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.backend.SearchAggregator
import com.nasmusic.tv.backend.network.ArtistSongsPage
import com.nasmusic.tv.backend.network.NetworkArtistInfo
import com.nasmusic.tv.backend.network.NetworkMusicService
import com.nasmusic.tv.backend.network.NetworkSongId
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 终审 F2（`SearchViewModel` 的展开/续页**代数闸门**）+ F9（未命中艺人时不得重排来源）。
 *
 * `SearchViewModel` 是 `AndroidViewModel`，本仓库既有口径是「不可单测」，但那条结论对
 * Robolectric 不成立：`@Config(application = NasMusicApp::class)`（同 `PlayModeToggleEventTest`）
 * 能拿到真实 Application，`SearchAggregator` 用**真实类 + 全空依赖**（五个源依赖都可空，
 * 只喂 networkMusicManager ⇒ NAS / 百度 / Jamendo / 本地四条支路天然空转），艺人取数走注入到
 * `NetworkMusicManager` 的假 `NetworkMusicService`，于是这里做的是**真行为测试**——
 * 终审 B2 的两条可达路径（换词、清空）只有行为测试能证伪，源码门证不了
 * 「在途协程回写时读到的是哪个艺人的数据」。
 *
 * ⚠️ 不要用 mockito 打假 `SearchAggregator`：`search` 是 suspend，编译后多出一个
 * `Continuation` 实参，`when(...)` 记录的匹配器个数（9）与实际参数个数（10）对不上，
 * 桩永远不命中 ⇒ 表现为「结果为空」而不是报错，极易误判成产品缺陷。
 *
 * ⚠️ 协程调度：`viewModelScope` 走 `Dispatchers.Main`，Robolectric 的主线程 Looper 在
 * `runBlocking` 期间不会自己转 ⇒ 必须 `Dispatchers.setMain(UnconfinedTestDispatcher())`，
 * 否则在途协程永远醒不过来（表现为超时而不是断言失败，极易误判）。
 * 等待一律轮询（与 `PlayModeToggleEventTest` 同法），⛔ 不靠 `advanceUntilIdle()` 猜调度次序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NasMusicApp::class)
class ArtistExpansionGenerationGateTest {

    private lateinit var service: FakeMetingService
    private lateinit var vm: SearchViewModel

    // ───────────────────────── 夹具 ─────────────────────────

    /**
     * 可编程的假 meting 源（红线 2 的注册键）：
     * - `pagesByArtist` 按 (artistId, offset) 回页
     * - `gateAt` + `gate` 让指定 offset 的那一页**挂在 CompletableDeferred 上**，制造「在途」窗口
     * - ⛔ 不打网络
     */
    private class FakeMetingService : NetworkMusicService {
        override val sourceId = METING
        val pagesByArtist = mutableMapOf<String, MutableMap<Int, ArtistSongsPage?>>()
        val findArtistResult = mutableMapOf<String, NetworkArtistInfo?>()
        val offsets = mutableListOf<Pair<String, Int>>()
        /** (artistId, offset) → 闸门：那一页**挂在 Deferred 上**，用来制造「在途」窗口 */
        val gates = mutableMapOf<Pair<String, Int>, CompletableDeferred<Unit>>()
        /** 哪些被挂住的页**真的醒过来并返回了**（⛔ 没有它，用例可能是空跑） */
        val resumed = mutableSetOf<Pair<String, Int>>()
        /** 统一搜索入口（`searchNativeFirst`）走这里；F2/F9 只关心艺人与顺序 */
        var rowsByKeyword = mutableMapOf<String, List<Song>>()

        override suspend fun search(keyword: String, limit: Int): List<Song> =
            rowsByKeyword[key0(keyword)] ?: emptyList()

        override suspend fun searchNativeFirst(keyword: String, limit: Int): List<Song> =
            search(keyword)

        override suspend fun findArtist(name: String): NetworkArtistInfo? = findArtistResult[name]

        override suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int): ArtistSongsPage? {
            val key = artistId to offset
            offsets += key
            gates[key]?.await()
            val page = pagesByArtist[artistId]?.get(offset)
            if (gates.containsKey(key)) resumed += key
            return page
        }

        override suspend fun resolvePlayUrl(song: Song): String? = null
        override suspend fun resolveLyrics(song: Song): String? = null
        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()

        private fun key0(keyword: String): String = keyword.substringBefore(' ').trim()
    }

    private fun metingSong(netId: String, title: String, artist: String, artistId: String?) = Song(
        id = NetworkSongId.forNetworkSong(METING, netId),
        title = title,
        artist = artist,
        artistId = artistId,
        isNetworkSong = true,
        networkSource = METING,
        networkId = netId
    )

    /** 一页 100 行；`from` 决定行 id 的起始序号（jay-1…jay-100），打标第 `from` 行 */
    private fun artistPage(
        prefix: String,
        artist: String,
        artistId: String,
        from: Int = 1,
        hasMore: Boolean = true
    ) = ArtistSongsPage(
        songs = (from..from + 99).map { metingSong("$prefix-$it", "t$prefix$it", artist, artistId) },
        total = 566,
        hasMore = hasMore,
        unplayableIds = setOf(NetworkSongId.forNetworkSong(METING, "$prefix-$from"))
    )

    /**
     * 聚合器用**真实**类：`SearchAggregator` 的五个依赖全部可空，
     * 只喂 networkMusicManager ⇒ NAS / 百度 / Jamendo / 本地四条支路天然空转，
     * 一个远程请求都不会发（⛔ 不要 mockito 打假：`search` 是 suspend，
     * 编译后多出一个 Continuation 实参，`when(...)` 的匹配器个数对不上）。
     */
    private fun realAggregator(): SearchAggregator = SearchAggregator(
        backendRegistry = null,
        networkMusicManager = app().networkMusicManager,
        baiduService = null,
        jamendoService = null,
        localMusicRepository = null,
        isTVDevice = false
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // 覆盖 NasMusicApp 在 onCreate 里注册的**真实** MetingApiService：
        // NetworkMusicManager.findArtist / getArtistSongs 按 defaultSource 精确路由，注册表可运行时替换
        service = FakeMetingService()
        app().networkMusicManager.registerService(service)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        app().networkMusicManager.unregisterService(METING)
    }

    private fun app(): NasMusicApp = ApplicationProvider.getApplicationContext()

    private fun newVm(): SearchViewModel = SearchViewModel(
        app(), app().backendRegistry, realAggregator()
    ).apply {
        // 默认 provider 是 ALBUMS ⇒ searchType=ALBUM，精细过滤按专辑名匹配会把假行全滤掉。
        // 搜索页的 SONGS 页签 = SONG_NAME_OR_ARTIST（按标题/艺术家匹配），本测试用的形态。
        libraryActiveTabProvider = { com.nasmusic.tv.ui.screens.LibraryTab.SONGS }
    }

    /** 轮询等待（真实调度器跨线程，见类 KDoc） */
    private suspend fun awaitUntil(what: String, cond: () -> Boolean) {
        withTimeout(10_000) {
            while (!cond()) delay(10)
        }
        assertTrue("等待超时：$what", cond())
    }

    private fun results(): List<Song> = (vm.searchResults.value as? UiState.Success)?.data ?: emptyList()
    private fun titles(): List<String> = results().map { it.title }

    /** 释放闸门并**证明那一页真的跑完了**（⛔ 不许用裸 delay 当同步手段） */
    private suspend fun releaseAndAwait(key: Pair<String, Int>) {
        service.gates.getValue(key).complete(Unit)
        awaitUntil("挂住的页 $key 已返回") { service.resumed.contains(key) }
        delay(200)   // 给陈旧发布/陈旧 accumulate 一点机会（闸门失效时它一定会跑到）
    }

    // ───────────────────────── F2 ─────────────────────────

    /**
     * 终审 B2 路径①：停底连拉途中换关键词 ⇒ 在途那一页属于**上一个艺人**。
     *
     * 两道门各挡一半，缺一半都会漏：
     * - `NetworkArtistExpansion.loadMore()` 的艺人比对挡**状态侧**（陈旧页不得 accumulate，
     *   否则 `fetched` 被混入旧艺人、下一页 offset 抬高 ⇒ 少一整页，且这条污染是发布门挡不住的）；
     * - `SearchViewModel` 的代数门挡**发布侧**（陈旧页不得覆盖 `_searchResults`）。
     * 末段再续一页：那是污染**唯一可观测**的出口（offset 从 200 起 ⇒ 拿不到 777@100）。
     */
    @Test
    fun `F2 连拉在途时换关键词——旧艺人的页既不入状态也不入网格`() = runBlocking {
        service.rowsByKeyword["周杰伦"] = listOf(metingSong("seed-jay", "seed", "周杰伦", "6452"))
        service.rowsByKeyword["蔡依林"] = listOf(metingSong("seed-jolin", "seed", "蔡依林", "777"))
        service.pagesByArtist["6452"] = mutableMapOf(
            0 to artistPage("jay", "周杰伦", "6452"),
            100 to artistPage("jay", "周杰伦", "6452", from = 101)
        )
        service.pagesByArtist["777"] = mutableMapOf(
            0 to artistPage("jolin", "蔡依林", "777"),
            100 to artistPage("jolin", "蔡依林", "777", from = 101, hasMore = false)
        )
        vm = newVm()

        vm.searchSongsOnServer("周杰伦")
        awaitUntil("首页 100 行已发布") { results().size == 100 }

        val staleKey = "6452" to 100
        service.gates[staleKey] = CompletableDeferred()
        vm.loadMoreArtistSongs()
        awaitUntil("第二页请求已发出（在途）") { service.offsets.contains(staleKey) }

        // 在途中换关键词：新词命中**另一位**艺人
        vm.searchSongsOnServer("蔡依林")
        awaitUntil("新艺人的首页已发布") { results().size == 100 && titles().first().startsWith("tjolin") }

        releaseAndAwait(staleKey)

        assertTrue("F2 回归：旧艺人的行混进了网格 ${titles()}", titles().none { it.startsWith("tjay") })
        assertEquals(100, results().size)
        assertTrue(vm.artistPaging.value.songs.none { it.title.startsWith("tjay") })

        // 状态侧探针：换新词后再续一页，offset 必须仍是 100（被污染过就会跳到 200 ⇒ 静默丢一整页）
        vm.loadMoreArtistSongs()
        awaitUntil("新艺人续到 200 行") { results().size == 200 }
        assertEquals(
            listOf("6452" to 0, staleKey, "777" to 0, "777" to 100),
            service.offsets
        )
        assertTrue(titles().none { it.startsWith("tjay") })
    }

    /** 终审 B2 路径②：连拉在途时 clearSearch() ⇒ 已清空的页面不得自己长出行 */
    @Test
    fun `F2 连拉在途时 clearSearch——清空后的页面不被复活`() = runBlocking {
        service.rowsByKeyword["周杰伦"] = listOf(metingSong("seed-jay", "seed", "周杰伦", "6452"))
        service.pagesByArtist["6452"] = mutableMapOf(
            0 to artistPage("jay", "周杰伦", "6452"),
            100 to artistPage("jay", "周杰伦", "6452", from = 101)
        )
        vm = newVm()

        vm.searchSongsOnServer("周杰伦")
        awaitUntil("首页 100 行已发布") { results().size == 100 }

        val staleKey = "6452" to 100
        service.gates[staleKey] = CompletableDeferred()
        vm.loadMoreArtistSongs()
        awaitUntil("第二页请求已发出（在途）") { service.offsets.contains(staleKey) }

        vm.clearSearch()
        assertEquals(0, results().size)
        releaseAndAwait(staleKey)

        assertTrue("F2 回归：clearSearch 后被在途续页复活 ${titles()}", results().isEmpty())
        assertTrue(vm.searchUnplayableIds.value.isEmpty())
        assertEquals(0, vm.artistPaging.value.songs.size)
        assertEquals(0, vm.artistPaging.value.totalCount)
    }

    /** 两次搜索重叠：先发的（上一个词的）结果不得覆盖后发的 */
    @Test
    fun `F2 重叠搜索——上一个词的展开不得覆盖当前词`() = runBlocking {
        service.rowsByKeyword["周杰伦"] = listOf(metingSong("seed-jay", "seed", "周杰伦", "6452"))
        service.rowsByKeyword["蔡依林"] = listOf(metingSong("seed-jolin", "seed", "蔡依林", "777"))
        service.pagesByArtist["6452"] = mutableMapOf(0 to artistPage("jay", "周杰伦", "6452"))
        service.pagesByArtist["777"] = mutableMapOf(0 to artistPage("jolin", "蔡依林", "777"))
        vm = newVm()

        val slowFirst = "6452" to 0
        service.gates[slowFirst] = CompletableDeferred()
        vm.searchSongsOnServer("周杰伦")
        awaitUntil("第一个词的展开请求已发出（在途）") { service.offsets.contains(slowFirst) }

        vm.searchSongsOnServer("蔡依林")
        awaitUntil("后一个词已发布") { results().size == 100 && titles().first().startsWith("tjolin") }

        releaseAndAwait(slowFirst)

        assertEquals("上一个词的结果覆盖了当前词", 100, results().size)
        assertTrue(titles().all { it.startsWith("tjolin") || it == "seed" })
    }

    // ───────────────────────── F9 ─────────────────────────

    /** 一条**非 Meting 家族**的网络行（`splitMetingSegment` 会把它归入 otherPart） */
    private fun baiduSong(netId: String, title: String, artist: String) = Song(
        id = "baidu_$netId",
        title = title,
        artist = artist,
        isNetworkSong = true,
        networkSource = "baidu",
        networkId = netId
    )

    /**
     * 终审 I-1：网络源亮着但**没命中艺人**时，⛔ 不得把非网络段整体提到网络行前面。
     * `skipExpansion` 在这种场景仍是 false（有 Meting 行），旧代码因此照样走 `mergeWithExpansion`
     * ⇒ 百度/本地行从聚合器原序的「网络之后」被搬到「网络之前」，覆盖来源优先级（NAS=1、网络=2）。
     */
    @Test
    fun `F9 未命中艺人时保持聚合器原序——网络行不被整体沉底`() = runBlocking {
        // 原序：[Meting 行, 百度行]（artistId=null ⇒ 不命中艺人，findArtist 也回 null）
        service.rowsByKeyword["周杰伦"] = listOf(
            metingSong("net-1", "tmting-1", "周杰伦", null),
            baiduSong("bd-1", "tbaidu-1", "周杰伦")
        )
        vm = newVm()

        vm.searchSongsOnServer("周杰伦")
        awaitUntil("两行都已发布") { results().size == 2 }

        assertEquals(listOf("tmting-1", "tbaidu-1"), titles())
        assertTrue("未命中艺人时 expansion 必须是不活跃态", vm.artistPaging.value.songs.isEmpty())
    }

    /** 对偶用例：真的命中艺人时段替换语义不动（m2 裁定），非网络段仍在艺人段之前 */
    @Test
    fun `F9 命中艺人时仍走段替换——非网络段前置语义不回归`() = runBlocking {
        service.rowsByKeyword["周杰伦"] = listOf(
            metingSong("net-1", "tmting-1", "周杰伦", "6452"),
            baiduSong("bd-1", "tbaidu-1", "周杰伦")
        )
        service.pagesByArtist["6452"] = mutableMapOf(0 to artistPage("jay", "周杰伦", "6452"))
        vm = newVm()

        vm.searchSongsOnServer("周杰伦")
        awaitUntil("艺人段 100 行 + 百度行已发布") { results().size == 101 }

        assertEquals("tbaidu-1", titles().first())
        assertTrue(titles().drop(1).all { it.startsWith("tjay") })
        assertEquals(100, vm.artistPaging.value.songs.size)
    }

    private companion object {
        /** 红线 2：这类歌曲的 networkSource 恒为字面 meting（NetworkSongId 的注册源 key） */
        const val METING = "meting"
    }
}
