package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import android.util.Log
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
import org.robolectric.shadows.ShadowLog

/**
 * Meting 直链解析测试（多码率方案 §10.1 / §3.2）。
 *
 * 用 MockWebServer 打桩，验证：
 * - `br` 参数按档位正确拼接（999/192 带上，AUTO 不带）
 * - 降级链按顺序回退并返回**实际命中档位**
 * - 302 取 `Location`、200 走 JSON `url` 两种响应形态
 * - AUTO 档不触发降级语义
 * - **301/303/307/308 跟一跳**后再按 302/200 口径取直链（`use301` 一组，见下方章节）
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

        /** [use301Br] 时端点 `Location` 指向的路径：301 落点，仍在这台 MockWebServer 上 */
        const val HOP_PATH = "/api-hop"
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

    /**
     * 非 null 时：br **命中该集合**的端点请求返回 `301 + Location: [HOP_PATH]`，其余档位照常应答。
     *
     * 复刻 `api.qijieya.cn/meting` 的真实形态。2026-10-13 电视端「无法播放」的根因：实现只认
     * 302/200，301 落到 `else -> null` 被当成空结果丢掉 —— 而跟完这一跳 301 后拿到的，
     * 恰恰是**该电视唯一能校验证书**的端点给出的可用直链。
     */
    private var use301Br: Set<Int?>? = null

    /** 301 落点（[HOP_PATH]）的应答形态 */
    private enum class HopShape {
        /** 302 + [hopTarget]：落点是正常的 Meting 端点 */
        REDIRECT,

        /** 200 + `{}`：落点里没有 url ⇒ 该档位必须判失败 */
        EMPTY,

        /** 再 301 回自己：验证跟跳有**跳数上限**、不会死循环 */
        LOOP
    }

    private var hopShape = HopShape.REDIRECT

    /** [HopShape.REDIRECT] 时的直链 */
    private var hopTarget = "https://cdn/after-301.mp3"

    /** true 时 301 的 `Location` 写成**相对路径**（`[HOP_PATH]`），而不是绝对 URL */
    private var use301Relative = false

    /** 非 null 时**首选端点**恒返回该状态码（fallback 端点不受影响）；用于验证失败原因进了日志 */
    private var primaryStatusCode: Int? = null

    /** 非法 URL 的端点：只会走异常路径，不出网（用于验证失败原因进了日志） */
    private val invalidEndpoint = "not a valid url"

    @Before
    fun setup() {
        seenBr.clear()
        seenPaths.clear()
        urlByTier = emptyMap()
        fallbackUrlByTier = emptyMap()
        use302 = false
        use301Br = null
        use301Relative = false
        primaryStatusCode = null
        hopShape = HopShape.REDIRECT
        hopTarget = "https://cdn/after-301.mp3"
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.path ?: ""
                seenPaths += url
                if (url.startsWith(HOP_PATH)) {
                    // 301 落点：不计入 seenBr（跟跳产生的请求不是档位请求）
                    return when (hopShape) {
                        HopShape.REDIRECT -> MockResponse().setResponseCode(302).addHeader("Location", hopTarget)
                        HopShape.EMPTY -> MockResponse().setResponseCode(200).setBody("{}")
                        HopShape.LOOP -> MockResponse().setResponseCode(301)
                            .addHeader("Location", server.url(HOP_PATH).toString())
                    }
                }
                val br = Regex("[?&]br=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                seenBr += br
                // 同一台 MockWebServer 兼作"首选端点"与"fallback 端点"，靠路径区分
                val target = if (url.startsWith(FALLBACK_PATH)) fallbackUrlByTier[br] else urlByTier[br]
                val hop301Br = use301Br
                val hopLocation = if (use301Relative) HOP_PATH else server.url(HOP_PATH).toString()
                return when {
                    hop301Br != null && br in hop301Br ->
                        MockResponse().setResponseCode(301).addHeader("Location", hopLocation)
                    primaryStatusCode != null && !url.startsWith(FALLBACK_PATH) ->
                        MockResponse().setResponseCode(primaryStatusCode!!)
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

    // ── 非-302 的 3xx 跟跳（2026-10-13 电视端「无法播放」修复）──

    @Test
    fun `301 跟一跳后取到落点的 302 直链`() = runBlocking {
        use301Br = setOf(320)
        hopShape = HopShape.REDIRECT
        hopTarget = "https://cdn/after-301.mp3"
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals("跟随 301 后必须拿到落点 302 的直链，而不是把 301 当空结果丢掉", "https://cdn/after-301.mp3", r.url)
        assertEquals("跟跳不改变档位判定，仍应记为请求档位 320", 320, r.actualQuality)
    }

    @Test
    fun `301 的 Location 是相对路径时也能跟`() = runBlocking {
        use301Br = setOf(320)
        use301Relative = true
        hopShape = HopShape.REDIRECT
        hopTarget = "https://cdn/after-relative-301.mp3"
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals("https://cdn/after-relative-301.mp3", r.url)
        assertTrue("相对 Location 必须按请求基址解析后再请求", seenPaths.any { it.startsWith(HOP_PATH) })
    }

    /**
     * 回归护栏：302 的口径**不许**因为本次改造而变成"再跟一跳"。
     *
     * 302 的 `Location` 本身就是可播直链，交给 ExoPlayer 由它自己跟；
     * 若改成跟一跳，探测判据（§3.8 第 1 步）作用的对象就换了。
     */
    @Test
    fun `302 的 Location 原样返回 不会被再跟一跳`() = runBlocking {
        val target = server.url(HOP_PATH).toString()   // 一个"跟过去还会再 302"的地址
        urlByTier = mapOf(320 to target)
        use302 = true
        val r = service().resolvePlayUrlDetailed(song(), 320)
        assertEquals(target, r.url)
        assertTrue("302 分支不应产生跟跳请求", seenPaths.none { it.startsWith(HOP_PATH) })
    }

    @Test
    fun `301 跟跳后仍无直链时按失败处理并继续降级链`() = runBlocking {
        // 999 走 301 → 落点里没有 url ⇒ 该档判失败；320 正常应答 ⇒ 命中 320
        use301Br = setOf(999)
        hopShape = HopShape.EMPTY
        urlByTier = mapOf(320 to "https://cdn/320.mp3")
        val r = service().resolvePlayUrlDetailed(song(), 999)
        assertEquals("https://cdn/320.mp3", r.url)
        assertEquals(320, r.actualQuality)
        assertEquals("301 落点失败后必须继续往下降档", listOf(999, 320), seenBr.take(2))
    }

    @Test
    fun `301 重定向链有跳数上限 不会死循环`() = runBlocking {
        // 端点 301 → 落点又 301 回自己：必须在上限处停手并按失败处理
        use301Br = setOf(128)
        hopShape = HopShape.LOOP
        val r = service(fallbackBase = null).resolvePlayUrlDetailed(song(), QualityTiers.STANDARD)
        assertNull(r.url)
        val hopRequests = seenPaths.count { it.startsWith(HOP_PATH) }
        assertTrue("跟跳次数必须有界（实际 $hopRequests 次）", hopRequests in 1..4)
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

    // ── 失败原因必须进 e 级日志（release 侧唯一可见的一级）──

    /**
     * 全端点失败时，聚合错误行要带上**逐端点×逐档**的失败原因。
     *
     * 为什么这是闸门而不是装饰：`AppLog.w` 被 `if (BuildConfig.DEBUG)` 守卫，release 包里
     * R8 连字符串常量一起折掉 ⇒ 真机上"为什么解析不到"完全不可见（2026-10-13 那次
     * 只能靠把电视的 cacerts 拉到 PC 上复刻，见 §10.229）。HTTP 状态码是那条线里
     * 唯一能区分"端点挂了 / 端点回了没认的状态码 / 端点压根没应答"的信息。
     */
    @Test
    fun `全端点失败时错误日志带上 HTTP 状态码`() = runBlocking {
        ShadowLog.clear()
        primaryStatusCode = 503
        val r = service(fallbackBase = null).resolvePlayUrlDetailed(song(), QualityTiers.STANDARD)
        assertNull(r.url)
        val err = errorLog()
        assertTrue("聚合错误行必须带状态码，实际=$err", err.contains("503"))
        assertTrue(err.contains("all endpoints failed"))
    }

    @Test
    fun `异常端点的失败原因也带上异常类型名与端点`() = runBlocking {
        ShadowLog.clear()
        // 首选端点回 200 但 JSON 里没有 url；fallback 端点是非法 URL ⇒ 请求构造期就抛异常（不出网）
        val r = service(fallbackBase = invalidEndpoint).resolvePlayUrlDetailed(song(), QualityTiers.STANDARD)
        assertNull(r.url)
        val err = errorLog()
        assertTrue("异常必须被记成原因而不是被吞掉，实际=$err", err.contains("Exception"))
        assertTrue("原因必须标出是哪个端点，实际=$err", err.contains(invalidEndpoint))
    }

    /** 本类 TAG 下的所有 **ERROR** 级日志拼成一段文本 */
    private fun errorLog(): String =
        ShadowLog.getLogs()
            ?.filter { it.tag == "MetingApiService" && it.type == Log.ERROR }
            ?.joinToString(" | ") { it.msg ?: "" }
            ?: ""
}
