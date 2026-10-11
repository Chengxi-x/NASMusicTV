package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song

/** 解析失败后可用的替换层（§3.8 的层级 1 与层级 2）。 */
enum class ReplaceLayer { SAME_SOURCE, CROSS_SOURCE }

/**
 * 替换链的**纯决策件**（艺人全量方案 §3.8 / Q7③）。
 *
 * `PlayerViewModel` 是不可单测的 AndroidViewModel，所以"该跑哪几层、什么时候停"
 * 抽到这里用纯 JVM 测试钉住（存在理由同 `NetworkPlaybackResolver`）。
 * ⛔ 这里不做任何 IO，也不碰 generation。
 */
object ReplaceChain {

    /**
     * 本次失败该按序尝试哪几层。
     *
     * ⛔ 同源必须在跨源**之前**：同源换的是同一首歌的另一版本，跨源换的可能是**另一位演唱者的同名歌**
     * （`resolvePlayUrlWithCrossSourceFallback` 只按 `title + artist` 搜，`jamendo` 上的同名异人不是原曲）。
     *
     * 防死循环闸门在这里：`songId == lastReplacedId` 表示本首已是某次替换的产物且仍然失败，
     * 返回空列表 ⇒ 一层都不跑，调用方直接跳下一首（§6 验收 15）。
     * ⚠️ 该闸门现在覆盖**两层**（既有代码只管跨源），这是有意的收紧：
     * 替换产物再触发替换，最坏情况是 A→B→A 的回环，而每一环都要付"1 次搜索 + ≤3 次解析"。
     */
    fun plan(songId: String, lastReplacedId: String?): List<ReplaceLayer> =
        if (lastReplacedId != null && songId == lastReplacedId) emptyList()
        else listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)

    /**
     * 按序尝试，**第一个成功的层即停**（⛔ 不许把后面的层也跑一遍浪费请求，R3）。
     *
     * @return 命中的层与替代曲；全失败返回 null
     */
    suspend fun run(
        layers: List<ReplaceLayer>,
        attempt: suspend (ReplaceLayer) -> Song?
    ): Pair<ReplaceLayer, Song>? {
        for (layer in layers) {
            val replacement = attempt(layer)
            if (replacement != null) return layer to replacement
        }
        return null
    }
}
