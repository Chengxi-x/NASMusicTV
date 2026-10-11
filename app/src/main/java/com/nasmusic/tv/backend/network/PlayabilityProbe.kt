package com.nasmusic.tv.backend.network

import com.nasmusic.tv.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一次直链可播性判定的三态结果（艺人全量方案 §3.8 第 1 步）。 */
enum class Playability {
    /** 最终响应是音频/视频——真的能出声 */
    PLAYABLE,

    /** 最终响应明确不是媒体（404/403/410/451、html/json/text）——即 R4 的假 302 */
    DEAD,

    /** 探测本身失败、超时或类型未知。⛔ 绝不按 DEAD 处理 */
    UNKNOWN
}

/**
 * 可播判据。
 *
 * 之所以是**接口**而不是类：§3.8 要求"单测全部用 lambda 注入假探测、不打网络"，
 * 而真实探测必须跟一次公网 CDN。生产代码只认 [HttpPlayabilityProbe]。
 */
interface PlayabilityProbe {
    fun probe(url: String): Playability
}

/**
 * 真实探测：跟随重定向一跳，用**最终响应的 Content-Type** 判定。
 *
 * 请求带 `Range: bytes=0-0`——只需要响应头，⛔ 不允许把整首歌拉下来（R3 限流 + 电视带宽）。
 * ⚠️ 探测**只用当前配置的端点**、并发由调用方串行保证（Task 6 的候选循环一次只探一个）。
 */
class HttpPlayabilityProbe(
    private val client: OkHttpClient = defaultClient()
) : PlayabilityProbe {

    override fun probe(url: String): Playability = try {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { response ->
            classify(response.code, response.header("Content-Type"))
        }
    } catch (e: Exception) {
        // 超时/DNS/TLS 失败 → UNKNOWN：让上游维持"非空即成功"的旧行为，
        // 一次网络抖动绝不能把一首好歌打成"不可播"并缓存 5 分钟
        AppLog.w(DIAG, "probe: ${e.javaClass.simpleName} ${e.message} → UNKNOWN")
        Playability.UNKNOWN
    }

    companion object {
        private const val DIAG = "MetingDiag"
        private const val PROBE_TIMEOUT_MS = 3_000L

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 判据的本体：必须**跟随**重定向才能量到 CDN，而不是端点自己
            .followRedirects(true)
            .build()

        /**
         * 纯判据（无 IO，单测直打）。
         *
         * 保守方向是**单向**的：不确定一律 UNKNOWN，由调用方按"可播"放行。
         * 理由——UNKNOWN 误判成 DEAD 会让本可播的歌被跳过（用户体感"莫名切歌"，且会被负面缓存钉住 5 分钟）；
         * DEAD 误判成可播则只是 ExoPlayer 自己报错、走既有跳过链。前者更糟。
         */
        internal fun classify(code: Int, contentType: String?): Playability = when {
            code == 404 || code == 403 || code == 410 || code == 451 -> Playability.DEAD
            code !in 200..299 -> Playability.UNKNOWN
            contentType.isNullOrBlank() -> Playability.UNKNOWN
            else -> {
                val ct = contentType.lowercase()
                when {
                    ct.startsWith("audio") || ct.startsWith("video") -> Playability.PLAYABLE
                    ct.contains("html") || ct.contains("json") || ct.startsWith("text/") -> Playability.DEAD
                    else -> Playability.UNKNOWN
                }
            }
        }
    }
}
