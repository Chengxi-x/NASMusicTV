package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
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

/**
 * 判据接线测试（§6 验收 12/16 的代码侧形态）。
 *
 * ⚠️ **本类不打真实公网**（2026-10-11 起）：`MetingApiService` 有带默认值的构造参数
 * `presetEndpoints`（默认 = `PRESET_ENDPOINTS`，线上逐字符等价），本类通过 [service]
 * **注入 `emptyList()`** ⇒ 端点链只剩首选 MockWebServer 一个。
 *
 * 改造前这里**真的会出网**：`302 直链探测 DEAD` 那条在首选端点被判 DEAD 后，`continue`
 * 只跳内层 br 循环，AUTO 档内层只有 1 次迭代 → 外层立刻轮到 `buildEndpointFallbackOrder`
 * 追加的 3 个**真实公网预设端点**，它们若回 302 会再次触发探测、结果取决于第三方可用性。
 * 当时的断言纪律因此被迫写成"只断言 `asked.firstOrNull()`，不断言 `asked` 整体"——
 * **那个将就现在可以去掉了**（见 `302 直链探测 DEAD` 那条）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetingProbeWiringTest {

    private lateinit var server: MockWebServer

    /** 假探测：按 URL 查表，未登记的档位默认 UNKNOWN；记录被问过哪些 URL */
    private class FakeProbe(var verdicts: Map<String, Playability> = emptyMap()) : PlayabilityProbe {
        val asked = mutableListOf<String>()
        override fun probe(url: String): Playability {
            asked += url
            return verdicts[url] ?: Playability.UNKNOWN
        }
    }

    private val deadUrl get() = server.url("/fake-redirect.html").toString()
    private val okUrl get() = server.url("/real.mp3").toString()

    /** true ⇒ 端点回 200 JSON；false ⇒ 端点回 302 + Location */
    private var asJson = false
    private var location: String? = null

    @Before
    fun setup() {
        asJson = false
        location = null
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = location ?: return MockResponse().setResponseCode(200).setBody("{}")
                return if (asJson) MockResponse().setResponseCode(200).setBody("""{"url":"$target"}""")
                else MockResponse().setResponseCode(302).addHeader("Location", target)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /**
     * ⛔ `presetEndpoints = emptyList()` 是**必需**的，不是洁癖：
     * AUTO 档下"首选端点判 DEAD → continue"会立刻跨到 fallback 端点，
     * 不注入就会去打真实公网（见类 KDoc）。需要验端点级 fallback 请用 `MetingResolveTest`，
     * 它用同一台 MockWebServer 的两个路径搭了两级本地链。
     */
    private fun service(probe: PlayabilityProbe?) = MetingApiService(
        baseUrlProvider = { server.url("/api").toString().trimEnd('/') },
        serverProvider = { "netease" },
        probe = probe,
        presetEndpoints = emptyList()
    )

    private val song = Song(
        id = "ntwk_meting_1001", title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "meting", networkId = "1001"
    )

    @Test
    fun `302 直链探测 DEAD ⇒ 不作为结果返回`() = runBlocking {
        location = deadUrl
        val probe = FakeProbe(mapOf(deadUrl to Playability.DEAD))
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        // 注入 emptyList() 之后这条断言**可以收紧到逐字节相等**（改造前不敢这么写）：
        // 当时链上还有 3 个真实公网端点，它们回 302 会再次触发探测，asked 的长度不可控。
        // 现在端点链只有首选端点，AUTO 档内层 br 循环也只有 1 次迭代 ⇒ 恰好问一次。
        assertEquals("DEAD 直链必须被问过一次，且**只**问这一次", listOf(deadUrl), probe.asked)
        assertTrue("DEAD 直链绝不能当成功返回（R4 的本体）", r.url != deadUrl)
        assertTrue("全链判 DEAD 后应带回 probedDead 语义", r.probedDead)
    }

    @Test
    fun `302 直链探测 PLAYABLE ⇒ 原样返回`() = runBlocking {
        location = okUrl
        val probe = FakeProbe(mapOf(okUrl to Playability.PLAYABLE))
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals(okUrl, r.url)
        assertEquals(listOf(okUrl), probe.asked)
    }

    @Test
    fun `探测 UNKNOWN ⇒ 维持旧的'非空即成功'，⛔ 不得劣化`() = runBlocking {
        location = okUrl
        val probe = FakeProbe()          // 全部 UNKNOWN
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals("探测拿不准时必须放行（否则超时抖动会误杀好歌）", okUrl, r.url)
    }

    @Test
    fun `200-JSON 分支不产生重定向链 ⇒ 一次都不探测`() = runBlocking {
        asJson = true                    // §6 验收 16：这条分支必须保持原判据
        location = okUrl
        val probe = FakeProbe(mapOf(okUrl to Playability.DEAD))
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals(okUrl, r.url)
        assertTrue("200 JSON 直返不该跟链，不该被探测", probe.asked.isEmpty())
    }

    @Test
    fun `probe 为 null ⇒ 与改造前逐字节一致`() = runBlocking {
        location = deadUrl
        val r = service(null).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals("未注入探测时旧行为不变（MetingResolveTest 的 15 例全靠这条护栏）", deadUrl, r.url)
    }
}
