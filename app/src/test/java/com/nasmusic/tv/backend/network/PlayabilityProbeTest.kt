package com.nasmusic.tv.backend.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 可播判据测试（艺人全量方案 §3.8 第 1 步，§6 验收 12）。
 *
 * 这条测试是 **R4 的护栏**：改造前"302 → /404.html"会被当成解析成功，
 * 缺它则整轮判据改动可能被"看起来能播"的回归悄悄掩盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayabilityProbeTest {

    private lateinit var server: MockWebServer

    // 判据的本质是"跟一跳拿最终响应的 Content-Type"，所以这个 client **必须** followRedirects
    private val probe = HttpPlayabilityProbe(
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    )

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** §6 验收 12 上半：302 → 404 text/html ⇒ DEAD */
    @Test
    fun `假 302 落到 html 页面必须判 DEAD`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/404.html").toString())
        )
        server.enqueue(
            MockResponse().setResponseCode(404)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><body>Not Found</body></html>")
        )
        assertEquals(
            Playability.DEAD,
            probe.probe(server.url("/api?server=netease&type=url&id=1").toString())
        )
    }

    /** §6 验收 12 下半：302 → audio/mpeg ⇒ PLAYABLE */
    @Test
    fun `真 302 落到音频必须判 PLAYABLE`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/real.mp3").toString())
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg")
                .setBody("ID3")
        )
        assertEquals(Playability.PLAYABLE, probe.probe(server.url("/api?type=url&id=2").toString()))
    }

    /** 探测只取 1 字节，⛔ 不许把整首歌下下来 */
    @Test
    fun `探测请求带 Range 只取首字节`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg").setBody("x")
        )
        assertEquals(Playability.PLAYABLE, probe.probe(server.url("/a.mp3").toString()))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("bytes=0-0", recorded?.getHeader("Range"))
    }

    @Test
    fun `探测失败只能是 UNKNOWN——⛔ 不许把网络抖动判成不可播`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(Playability.UNKNOWN, probe.probe(server.url("/api").toString()))
    }

    /** classify 的完整档位表（无 IO，R4 护栏的全覆盖面） */
    @Test
    fun `classify 只认证据、不确定一律留给上游`() {
        fun c(code: Int, ct: String?) = HttpPlayabilityProbe.classify(code, ct)
        // 明确的"这里没有歌"
        assertEquals(Playability.DEAD, c(404, null))
        assertEquals(Playability.DEAD, c(403, "text/html"))
        assertEquals(Playability.DEAD, c(410, "text/html"))
        assertEquals(Playability.DEAD, c(451, "text/html"))       // 地域版权屏蔽
        assertEquals(Playability.DEAD, c(200, "text/html; charset=utf-8"))
        assertEquals(Playability.DEAD, c(200, "application/json"))
        // 媒体
        assertEquals(Playability.PLAYABLE, c(200, "audio/mpeg"))
        assertEquals(Playability.PLAYABLE, c(206, "Audio.MPEG"))   // Range 命中 + 大小写
        assertEquals(Playability.PLAYABLE, c(200, "video/mp4"))
        // 不确定 ⇒ UNKNOWN（绝不能当成 DEAD，见实现注释）
        assertEquals(Playability.UNKNOWN, c(200, null))
        assertEquals(Playability.UNKNOWN, c(200, "  "))
        assertEquals(Playability.UNKNOWN, c(200, "application/octet-stream"))
        assertEquals(Playability.UNKNOWN, c(500, "text/html"))     // 服务端故障是环境态，不是"这首歌没了"
        assertEquals(Playability.UNKNOWN, c(302, "audio/mpeg"))    // 跟链没走完
    }
}
