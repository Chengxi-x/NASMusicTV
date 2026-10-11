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
 * ⚠️ 断言纪律沿用 `MetingResolveTest.kt:191-202` 的既有教训：
 * **绝不断言"全链失败 ⇒ url == null"**——`buildEndpointFallbackOrder` 会在首选端点之后
 * 追加 3 个真实公网预设端点，测试环境可能真的拿到直链，那样断言必然 flaky。
 * 因此这里只在**首选端点就能定案**的路径上断言最终 url（PLAYABLE / UNKNOWN / 200-JSON），
 * DEAD 那条只断言"探测被问过、且假 302 直链没被当成结果返回"。
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

    private fun service(probe: PlayabilityProbe?) = MetingApiService(
        baseUrlProvider = { server.url("/api").toString().trimEnd('/') },
        serverProvider = { "netease" },
        probe = probe
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
        // ⚠️ 不断言 asked 恰为 [deadUrl]：首选端点 DEAD 后循环仍会试 fallback 的公网预设端点，
        // 它们若回 302 会再次触发探测（默认 UNKNOWN 放行）。"首选端点的假 302 必然第一个被问"
        // 才是与环境无关的确定性断言（同一纪律见类注释与 MetingResolveTest:191-202）。
        assertTrue("假 302 必须最先被问过探测", probe.asked.firstOrNull() == deadUrl)
        assertTrue("DEAD 直链绝不能当成功返回（R4 的本体）", r.url != deadUrl)
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
