package com.nasmusic.tv.backend.network

import com.nasmusic.tv.backend.network.netease.NeteaseOpenApi
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一搜索的「网易优先 + 端点兜底」分支（艺人全量方案 §3.2 / Q2 / 验收 6）。
 *
 * MockWebServer 同域双路径：
 * - `/api/search/get`（POST）= 网易原生接口
 * - `/api`（GET，带 query）= Meting 端点
 *
 * ⚠️ 基址 `server.url("/api")` 同时喂给两个客户端：Meting 直接用基址当端点，
 * `NeteaseOpenApi` 会在基址后再拼 `/api/search/get`，因此原生请求的真实路径是
 * `/api/api/search/get` —— 判定分支用 `contains("/api/search/get")`，
 * 顺序必须在 `startsWith("/api")` 之前。
 *
 * ⛔ 不写"端点链全挂 ⇒ 结果为空"这类断言：`buildEndpointFallbackOrder` 会追加
 * 三个真实公网预设端点，那样写天然 flaky（见 MetingResolveTest 的同类注释）。
 *
 * F1（分支终审）：「网易优先」只属于 `searchNativeFirst`，`search()` 必须回到改造前的端点链语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeteasePrioritySearchTest {

    private lateinit var server: MockWebServer
    private val nativeCalls = AtomicInteger()
    private val endpointCalls = AtomicInteger()
    /** 模拟 R1：网易原生接口下线 / 5xx */
    private var nativeDown = false

    private val type1Body = """{"api_code":200,"result":{"songs":[
      {"id":1001,"name":"晴天","duration":269000,
       "artists":[{"id":6452,"name":"周杰伦"}],
       "album":{"id":99,"name":"叶惠美"}}]}}"""

    private val endpointBody = """[{"title":"安静","author":"周杰伦",
      "url":"http://x/api?server=netease&type=url&id=2002",
      "pic":"http://x/api?server=netease&type=pic&id=2002"}]"""

    @Before
    fun setUp() {
        nativeCalls.set(0); endpointCalls.set(0); nativeDown = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("/api/search/get") -> {
                        nativeCalls.incrementAndGet()
                        if (nativeDown) MockResponse().setResponseCode(500)
                        else MockResponse().setBody(type1Body)
                    }
                    path.startsWith("/api") -> {
                        endpointCalls.incrementAndGet()
                        MockResponse().setBody(endpointBody)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(): MetingApiService {
        val base = server.url("/api").toString().trimEnd('/')
        return MetingApiService(
            baseUrlProvider = { base },                       // 当前端点 = mock，端点链第一跳就命中
            serverProvider = { "netease" },
            netease = NeteaseOpenApi(baseUrlProvider = { base })   // 原生接口也指到同一个 mock
        )
    }

    @Test
    fun `网易原生可用时优先取原生结果且完全不打端点`() = runBlocking {
        val songs = service().searchNativeFirst("周杰伦")
        assertEquals(1, songs.size)
        assertEquals("晴天", songs.first().title)
        assertEquals(1, nativeCalls.get())
        assertEquals("⛔ 网易命中后不得再打端点（这就是 Q2 的'优先'语义）", 0, endpointCalls.get())
    }

    @Test
    fun `网易原生失败必须自动回落端点且不得整批空`() = runBlocking {
        nativeDown = true
        val songs = service().searchNativeFirst("周杰伦")
        assertEquals("验收 6-②：网易挂了也要出结果", 1, songs.size)
        assertEquals("安静", songs.first().title)
        assertTrue(endpointCalls.get() >= 1)
    }

    @Test
    fun `两条分支的结果形状逐字段同构（Q5 回归护栏）`() = runBlocking {
        val native = service().searchNativeFirst("周杰伦")
        nativeDown = true
        val endpoint = service().searchNativeFirst("周杰伦")
        for (list in listOf(native, endpoint)) {
            val s = list.single()
            assertTrue("网络段必须是 isNetworkSong", s.isNetworkSong)
            assertEquals("红线 2：两处都恒为 meting", "meting", s.networkSource)
            assertTrue("红线 1：两处同前缀", s.id.startsWith("ntwk_meting_"))
        }
    }

    /**
     * C2 回归：`searchCoverUrl()` 必须继续走**端点链**取 `pic`。
     *
     * 网易 `type=1` 响应里没有 `album.picUrl`（§2 C.1 实测 0/100），
     * 它一旦改走 `search()`，在原生分支命中时就恒返回 null，
     * 打的是既有、无关的两张表面（在线歌词封面轮播 / 下载封面兜底链）。
     */
    @Test
    fun `原生分支在途时 searchCoverUrl 仍必须拿到端点 pic 封面`() = runBlocking {
        val svc = service()
        assertEquals(
            "前置条件：native 是通的（searchNativeFirst() 本身会走原生分支）",
            "晴天", svc.searchNativeFirst("周杰伦").first().title
        )
        assertEquals(1, nativeCalls.get())
        assertEquals("前置条件：searchNativeFirst() 命中原生后没打端点", 0, endpointCalls.get())

        val cover = svc.searchCoverUrl("安静", "周杰伦")
        assertEquals(
            "C2：封面必须来自端点链的 pic，⛔ 不能是原生 type=1 的 null",
            "http://x/api?server=netease&type=pic&id=2002", cover
        )
        assertTrue("C2：searchCoverUrl 应打端点链", endpointCalls.get() >= 1)
    }

    /** C2 的第二半：`searchNativeFirst()` 自身的行为与搬动前完全一致（原生命中就不打端点） */
    @Test
    fun `抽出端点链后 searchNativeFirst 的网易优先语义不变`() = runBlocking {
        val svc = service()
        assertEquals("晴天", svc.searchNativeFirst("周杰伦").first().title)
        assertEquals(1, nativeCalls.get())
        assertEquals(0, endpointCalls.get())
        // 原生下线后才回落端点
        nativeDown = true
        assertEquals("安静", svc.searchNativeFirst("周杰伦").first().title)
        assertTrue(endpointCalls.get() >= 1)
    }

    // ── F1（分支终审）：「网易优先」必须被关在统一搜索入口里 ─────────────
    // Q5 裁定「其他页面功能不受影响」：天气电台 / 歌单补全 / MediaLibraryTree /
    // 手机遥控搜索 / 换一批 全部走 `search()`，池子不得从「~30 首带封面」换成「≤100 首无封面」。

    @Test
    fun `F1 负向：search 在原生可用时绝不触碰原生接口`() = runBlocking {
        val songs = service().search("周杰伦")
        assertEquals(
            "search() 必须回到改造前的端点链结果（带封面的 安静）",
            "安静", songs.first().title
        )
        assertEquals("Q5 违规：search() 打了 music.163.com 原生接口", 0, nativeCalls.get())
        assertTrue(endpointCalls.get() >= 1)
    }

    @Test
    fun `F1 正向：同一个服务实例上 searchNativeFirst 才会打原生`() = runBlocking {
        val svc = service()
        svc.search("周杰伦")
        assertEquals(0, nativeCalls.get())
        val songs = svc.searchNativeFirst("周杰伦")
        assertEquals("晴天", songs.first().title)
        assertEquals(1, nativeCalls.get())
    }

    /** 接口默认实现：非 Meting 源（Jamendo / 百度）不覆写 `searchNativeFirst` 也必须原样回落到 `search` */
    @Test
    fun `F1 默认实现回落到 search——非 Meting 源零改动`() = runBlocking {
        var searchCalls = 0
        val fake = object : NetworkMusicService {
            override val sourceId = "plain"
            override suspend fun search(keyword: String, limit: Int): List<Song> {
                searchCalls++
                return listOf(songViaEndpoint())
            }
            override suspend fun resolvePlayUrl(song: Song): String? = null
            override suspend fun resolveLyrics(song: Song): String? = null
            override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()
        }
        val viaNative = fake.searchNativeFirst("周杰伦")
        assertEquals(1, searchCalls)
        assertEquals(1, viaNative.size)
    }

    private fun songViaEndpoint(): Song = Song(
        id = NetworkSongId.forNetworkSong("plain", "1"), title = "t", artist = "a",
        isNetworkSong = true, networkSource = "plain", networkId = "1"
    )

    // ── F8（终审 M-B4）：searchCoverUrl 的空关键词短路 ─────────────────────
    // keyword.isBlank() 的检查原本只留在 search() 与 searchNativeFirst()，searchCoverUrl 直接喂
    // searchViaEndpoints ⇒ 空标题的 NAS 曲会打满整条端点链（当前端点 + 3 预设 = 最多 4 跳）`id=` 为空的请求。

    @Test
    fun `F8 标题与歌手都为空必须短路返回 null 且一个端点请求都不发`() = runBlocking {
        val cover = service().searchCoverUrl("", "")
        assertNull("F8：空关键词必须短路返回 null", cover)
        assertEquals("F8：短路后不得打端点链（mock 计数必须为 0）", 0, endpointCalls.get())
        assertEquals(0, nativeCalls.get())
    }

    @Test
    fun `F8 只有歌手名非空不得被短路挡掉（封面补全能力不回归）`() = runBlocking {
        val cover = service().searchCoverUrl("", "周杰伦")
        assertEquals(
            "短路条件是**两个都空**；title 空但 artist 非空时 keyword 仍非空，必须照常走端点链拿 pic",
            "http://x/api?server=netease&type=pic&id=2002", cover
        )
        assertTrue(endpointCalls.get() >= 1)
    }
}
