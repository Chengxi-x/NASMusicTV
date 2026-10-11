package com.nasmusic.tv.backend.network

/**
 * 网络音乐直链解析结果（多码率方案 §3.2）。
 *
 * 承载**降级信号**：`actualQuality != requestedQuality` 时表示发生了静默降级，
 * 播放侧据此提示用户，下载侧据此决定文件名后缀与落库档位。
 *
 * @param url 可直接播放/下载的直链；null 表示所有档位与端点均失败（无源可降）
 * @param actualQuality 实际命中的档位值（0/128/192/320/999）。
 *        解析失败时等于请求档位；AUTO 档请求时恒为 [QualityTiers.AUTO]，
 *        因此 AUTO 档永远不会被判定为"降级"。
 */
data class ResolveResult(
    val url: String?,
    val actualQuality: Int,
    /**
     * 失败是否由**探测确认的 DEAD** 造成（艺人全量方案 §3.8 第 1 步）。
     *
     * ⚠️ 只有 true 才允许写负面缓存："端点没给链接"（网络抖动、id 过期）与
     *   "给了但跟链确认是假 302" 是两件事，前者钉 5 分钟会把偶发失败放大成静默跳曲。
     */
    val probedDead: Boolean = false
) {
    /** 是否解析成功 */
    val isSuccess: Boolean get() = !url.isNullOrBlank()

    /**
     * 相对请求档位是否发生降级。
     *
     * AUTO 档恒返回 false —— "端点默认给什么"不构成降级语义。
     */
    fun isDowngradedFrom(requested: Int): Boolean =
        isSuccess && requested != QualityTiers.AUTO && actualQuality != requested

    companion object {
        /** 解析失败（无源可降）；`probedDead=true` 表示失败原因是探测判定的假 302 */
        fun failure(requested: Int, probedDead: Boolean = false): ResolveResult =
            ResolveResult(null, requested, probedDead)
    }
}

/**
 * 音质档位的生效范围（两级模型，方案 §2.3）。
 */
enum class QualityScope {
    /** 全部歌曲：写全局默认档位 */
    ALL,

    /** 仅本次播放：写单曲覆盖，不污染全局默认 */
    THIS_SONG
}
