package com.nasmusic.tv.backend.network

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

/**
 * Meting 直链解析测试（多码率方案 §10.1 / §3.2）。
 *
 * 用 MockWebServer 打桩，验证：
 * - `br` 参数按档位正确拼接（999/192 带上，AUTO 不带）
 * - 降级链按顺序回退并返回**实际命中档位**
 * - 302 取 `Location`、200 走 JSON `url` 两种响应形态
 * - AUTO 档不触发降级语义
 *
 * ⚠️ **本类不打真实公网**（2026-10-11 起）：`MetingApiService` 新增了带默认值的构造参数
 * `presetEndpoints`（默认 = `PRESET_ENDPOINTS`，线上逐字符等价），本类通过 [service] 把它注入成
 * **同一台 MockWebServer 的另一个路径**（[FALLBACK_PATH]），于是端点 fallback 链
 * "首选端点 → fallback 端点" 的**两级结构与生产一致**，但两端都是本地桩。
 *
 * 改造前这里走的是 `PRESET_ENDPOINTS` 的 3 个真实公网端点
 * （meting.mikus.ink / meting.api.redcha.cn / api.qijieya.cn），只要其中任意一个在线并应答了，
 * 同一份断言就会在 CI 与本机给出不同结果——这正是 `Unit tests` job 偶发红的真正来源。
 * 需要"没有 fallback 端点"的场景时传 `service(fallbackBase = null)`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetingResolveTest {

    private companion object {
        /** 兼作"fallback 端点"的路径前缀（与首选端点的 `/api` 区分，仍在同一台 MockWebServer 上） */
        const val FALLBACK_PATH = "/api-fallback"
    }

    /** fallback 端点基址（读 [server]，故必须在 [setup] 之后取） */
    private val fallbackEndpointBase get() = server.url(FALLBACK_PATH).toString().trimEnd('/')

    private lateinit var server: MockWebServer

    /** 记录每次请求的 br 参数（null 表示 URL 中不含 br） */
    private val seenBr = mutableListOf<Int?>()
    private val seenPaths = mutableListOf<String>()

    /** 首选端点（`/api`）每个 tier → 直链；未配置的 tier 视为不可用 */
    private var urlByTier: Map<Int?, String> = emptyMap()

    /**
     * fallback 端点（[FALLBACK_PATH]）每个 tier → 直链。
     *
     * 与 [urlByTier] 分开，才能表达"首选端点全档失败、换端点后才拿到直链"这个真实场景。
     */
    private var fallbackUrlByTier: Map<Int?, String> = emptyMap()

    /** true 时所有请求返回 302 + Location（而非 200 + JSON） */
    private var use302 = false

    @Before
    fun setup() {
        seenBr.clear()
        seenPaths.clear()
        urlByTier = emptyMap()
        fallbackUrlByTier = emptyMap()
        use302 = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.path ?: ""
                seenPaths += url
                val br = Regex("[?&]br=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                seenBr += br
                // 同一台 MockWebServer 兼作"首选端点"与"fallback 端点"，靠路径区分
                val target = if (url.startsWith(FALLBACK_PATH)) fallbackUrlByTier[br] else urlByTier[br]
                return when {
                    target == null -> MockResponse().setResponseCode(200).setBody("{}")
                    use302 -> MockResponse().setResponseCode(302).addHeader("Location", target)
                    else -> MockResponse().setResponseCode(200)
                        .setBody("""{"url":"$target"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * 构造被测服务。
     *
     * @param fallbackBase 预设端点列表的唯一元素；默认 = 同一台 MockWebServer 的 [FALLBACK_PATH]，
     *   传 null 则**不注入任何预设端点**（端点链只剩首选端点）。
     *   ⛔ 两者都**不打真实公网**——本类不再使用默认的 `PRESET_ENDPOINTS`（见类 KDoc）。
     */
    private fun service(
        qualityTier: Int = QualityTiers.AUTO,
        fallbackBase: String? = fallbackEndpointBase
    ) = MetingApiService(
        baseUrlProvider = { server.url("/api").toString().trimEnd('/') },
        serverProvider = { "netease" },
        qualityTierProvider = { qualityTier },
        presetEndpoints = fallbackBase?.let { listOf(0 to it) } ?: emptyList()
    )

    private fun song(id: String = "1001") = Song(
        id = "ntwk_meting_$id",
        title = "南方姑娘",
        artist = "赵雷",
        isNetworkSong = true,
        networkSource = "meting",
        networkId = id
    )

    // ── br 参数拼接 ─────────────────────────────────────────

    @Test
    fun `quality 999 请求 URL 含 br=999`() = runBlocking {
        urlByTier = mapOf(999 to "https://cdn/999.flac")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals("https://cdn/999.flac", r.url)
        assertEquals(999, r.actualQuality)
        assertTrue("首个请求应带 br=999", seenBr.firstOrNull() == 999)
    }

    @Test
    fun `quality 192 请求 URL 含 br=192`() = runBlocking {
        urlByTier = mapOf(192 to "https://cdn/192.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 192)
        assertEquals("https://cdn/192.mp3", r.url)
        assertEquals(192, r.actualQuality)
        assertTrue("首个请求应带 br=192", seenBr.firstOrNull() == 192)
    }

    @Test
    fun `quality 0 AUTO 请求 URL 不含 br 参数`() = runBlocking {
        urlByTier = mapOf(null to "https://cdn/auto.mp3")
        val r = service().resolvePlayUrlDetailed(song(), QualityTiers.AUTO)
        assertEquals("https://cdn/auto.mp3", r.url)
        assertTrue("AUTO 档首个请求不应带 br", seenBr.firstOrNull() == null)
    }

    // ── 降级链 ──────────────────────────────────────────────

    @Test
    fun `999 不可用降级到 320 并返回实际档位`() = runBlocking {
        // 只有 320 可用
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals("https://cdn/320.mp3", r.url)
        assertEquals("必须返回实际命中的 320", 320, r.actualQuality)
        assertTrue("应发生降级", r.isDowngradedFrom(999))
        assertEquals("应先试 999 再试 320", listOf(999, 320), seenBr.take(2))
    }

    @Test
    fun `降级链包含 192 档`() = runBlocking {
        // 只有 192 可用 → 请求 999 应经 999 → 320 → 192
        urlByTier = mapOf(192 to "https://cdn/192.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals(192, r.actualQuality)
        assertEquals("降级链必须经过 192（v1.3 更正点）", listOf(999, 320, 192), seenBr.take(3))
    }

    @Test
    fun `降级到 128 时按 999 320 192 128 顺序尝试`() = runBlocking {
        urlByTier = mapOf(128 to "https://cdn/128.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals(128, r.actualQuality)
        assertEquals(listOf(999, 320, 192, 128), seenBr.take(4))
    }

    @Test
    fun `320 档请求不尝试 999`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        service().resolvePlayUrlDetailed(song(), 320)
        assertTrue("请求 320 时不应出现 br=999", !seenBr.take(1).contains(999))
        assertEquals(320, seenBr.first())
    }

    // ── 响应形态 ────────────────────────────────────────────

    @Test
    fun `302 响应取 Location 头`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/302-target.mp3")
        use302 = true
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals("https://cdn/302-target.mp3", r.url)
        assertEquals(320, r.actualQuality)
    }

    @Test
    fun `200 响应取 JSON 的 url 字段`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/200-target.mp3")
        use302 = false
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals("https://cdn/200-target.mp3", r.url)
    }

    // ── AUTO 档语义 ────────────────────────────────────────

    @Test
    fun `AUTO 档命中时 actualQuality 为 AUTO 不触发降级`() = runBlocking {
        urlByTier = mapOf(null to "https://cdn/auto.mp3")
        val r = service().resolvePlayUrlDetailed(song(), QualityTiers.AUTO)
        assertEquals(QualityTiers.AUTO, r.actualQuality)
        assertTrue("AUTO 档不应被判定为降级", !r.isDowngradedFrom(QualityTiers.AUTO))
    }

    // ── 失败路径 ────────────────────────────────────────────

    @Test
    fun `首选端点全档不可用时 会继续尝试后续端点（fallback 行为）`() = runBlocking {
        // ⚠️ 这条用例曾因"打真实公网"而 flaky：首选端点失败后会遍历 PRESET_ENDPOINTS 的
        // 3 个公网端点（meting.mikus.ink / meting.api.redcha.cn / api.qijieya.cn），
        // 它们可能真的返回直链，任何"最终 url == null"的断言都不可靠。
        // 现在 fallback 端点被换成本地 `/api-fallback`（见类 KDoc），两端都不出网。
        //
        // 断言仍只取与环境无关的那部分性质：**首选端点**被完整试过 999→320→192→128 四档才换端点。
        urlByTier = emptyMap()
        service().resolvePlayUrlDetailed(song(), 999)
        assertEquals(
            "首选端点应按降级链试完 4 档后才换端点",
            listOf(999, 320, 192, 128), seenBr.take(4)
        )
        assertTrue("之后确实换到了 fallback 端点", seenPaths.any { it.startsWith(FALLBACK_PATH) })
    }

    /**
     * 端点级 fallback：首选端点整条链拿不到直链 → 换到 fallback 端点后命中，返回**实际命中档位**。
     *
     * ⚠️ 这条用例 2026-10-11 之前是 **CI flaky 的源头**，两个缺陷叠在一起：
     * 1. 它没注入预设端点，于是首选端点失败后会去打 3 个真实公网端点
     *    （meting.mikus.ink / meting.api.redcha.cn / api.qijieya.cn）。其中任意一个在
     *    **br=192** 应答 → `actualQuality = 192`；
     * 2. 而当时的断言写死了 `== 320 || == 128`，**漏掉了 192**——可
     *    `QualityTiers.fallbackChainOf(320)` 明明是 `[320, 192, 128]`。
     *
     * 于是：本机打不通公网 → 全链失败 → `ResolveResult.failure(320)` → 320 → **绿**；
     * CI 上公网通 → 192 → **红**。**同一个 commit 的 tag run 能过，正是这个原因。**
     *
     * 现在 fallback 端点是**同一台 MockWebServer 的 `/api-fallback`**，结果完全确定：
     * - 首选端点：`urlByTier` 空 → 320/192/128 三档各回一次 `{}`（无 url），共 3 次请求
     * - fallback 端点：320 再失败 → **192 命中** → 立即返回，`actualQuality = 192`
     * - 因此 seenBr = [320, 192, 128] ++ [320, 192]，不再往后试
     */
    @Test
    fun `首选端点不可用时 fallback 到后续端点并返回可用结果`() = runBlocking {
        urlByTier = emptyMap()                                   // 首选端点全档失败
        fallbackUrlByTier = mapOf(192 to "https://cdn/fallback-192.mp3")   // 换端点后在 192 命中

        val r = service().resolvePlayUrlDetailed(song(), 320)

        assertEquals("https://cdn/fallback-192.mp3", r.url)
        assertEquals("换端点后必须返回实际命中的 192", 192, r.actualQuality)
        // 首选端点确实被完整试过 320→192→128 三档后才换端点
        assertEquals(listOf(320, 192, 128), seenBr.take(3))
        assertTrue("确实打过 fallback 端点", seenPaths.any { it.startsWith(FALLBACK_PATH) })
        // ⛔ 降级链不变式（任务要求的写法）：actualQuality 必须落在 HIGH 的降级链内
        //   （`QualityTiers.fallbackChainOf(320)` = `[320, 192, 128]`；全链失败时
        //   `ResolveResult.failure` 返回的也是请求档 320，同样落在链内）。
        //   ⛔ 写成链不变式而**不是**写死某两个值——将来链增删档（如补 256）不会再把本用例打红。
        assertTrue(
            "actualQuality 应落在 320 的降级链内，实际=${r.actualQuality}",
            r.actualQuality in QualityTiers.fallbackChainOf(320)
        )
    }

    @Test
    fun `无 networkId 时回退 song streamUrl`() = runBlocking {
        val noId = Song(
            id = "x", title = "T", isNetworkSong = true,
            networkSource = "meting", networkId = null,
            streamUrl = "https://preset/already.mp3"
        )
        val r = service().resolvePlayUrlDetailed(noId, 320)
        assertEquals("https://preset/already.mp3", r.url)
    }

    @Test
    fun `String 版本与 detailed 版本结果一致`() = runBlocking {
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        val svc = service()
        val detailed = svc.resolvePlayUrlDetailed(song(), 320)
        val plain = svc.resolvePlayUrl(song(), 320)
        assertEquals(detailed.url, plain)
    }

    @Test
    fun `resolvePlayUrl 无参版本使用 qualityTierProvider`() = runBlocking {
        urlByTier = mapOf(192 to "https://cdn/192.mp3")
        val svc = service(qualityTier = 192)
        val url = svc.resolvePlayUrl(song())
        assertEquals("https://cdn/192.mp3", url)
        assertEquals("应按 provider 的 192 档请求", 192, seenBr.first())
    }
}
