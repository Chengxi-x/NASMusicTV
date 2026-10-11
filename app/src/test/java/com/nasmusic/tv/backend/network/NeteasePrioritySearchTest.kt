package com.nasmusic.tv.backend.network

import com.nasmusic.tv.backend.network.netease.NeteaseOpenApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
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
        val songs = service().search("周杰伦")
        assertEquals(1, songs.size)
        assertEquals("晴天", songs.first().title)
        assertEquals(1, nativeCalls.get())
        assertEquals("⛔ 网易命中后不得再打端点（这就是 Q2 的'优先'语义）", 0, endpointCalls.get())
    }

    @Test
    fun `网易原生失败必须自动回落端点且不得整批空`() = runBlocking {
        nativeDown = true
        val songs = service().search("周杰伦")
        assertEquals("验收 6-②：网易挂了也要出结果", 1, songs.size)
        assertEquals("安静", songs.first().title)
        assertTrue(endpointCalls.get() >= 1)
    }

    @Test
    fun `两条分支的结果形状逐字段同构（Q5 回归护栏）`() = runBlocking {
        val native = service().search("周杰伦")
        nativeDown = true
        val endpoint = service().search("周杰伦")
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
            "前置条件：native 是通的（search() 本身会走原生分支）",
            "晴天", svc.search("周杰伦").first().title
        )
        assertEquals(1, nativeCalls.get())
        assertEquals("前置条件：search() 命中原生后没打端点", 0, endpointCalls.get())

        val cover = svc.searchCoverUrl("安静", "周杰伦")
        assertEquals(
            "C2：封面必须来自端点链的 pic，⛔ 不能是原生 type=1 的 null",
            "http://x/api?server=netease&type=pic&id=2002", cover
        )
        assertTrue("C2：searchCoverUrl 应打端点链", endpointCalls.get() >= 1)
    }

    /** C2 的第二半：`search()` 自身的行为与 a861880 完全一致（原生命中就不打端点） */
    @Test
    fun `抽出端点链后 search 的网易优先语义不变`() = runBlocking {
        val svc = service()
        assertEquals("晴天", svc.search("周杰伦").first().title)
        assertEquals(1, nativeCalls.get())
        assertEquals(0, endpointCalls.get())
        // 原生下线后才回落端点
        nativeDown = true
        assertEquals("安静", svc.search("周杰伦").first().title)
        assertTrue(endpointCalls.get() >= 1)
    }
}
