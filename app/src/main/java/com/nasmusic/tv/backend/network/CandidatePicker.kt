package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter

/**
 * 同源重搜的候选挑选（艺人全量方案 §3.8 第 2 步，补错位 4）。
 *
 * 纯逻辑件：搜索与解析都在构造期注入，单测用 lambda 打假（§3.8 的"单测不打网络"要求）。
 * 与 `resolvePlayUrlWithCrossSourceFallback` 的分工：那一个是**跨注册源**（找同名另一演唱者），
 * 本件是**同源内**换版本（找同一首歌的另一个可播版本）——Q8 撤出跨 `server` 后，
 * 这一层是本期唯一新增的重搜路径。
 */
class CandidatePicker(
    private val search: suspend (String) -> List<Song>,
    private val resolve: suspend (Song) -> ResolveResult,
    private val maxCandidates: Int = MAX_CANDIDATES
) {

    /** 替代曲（已带直链，替换即可播） */
    data class Replacement(val song: Song, val playUrl: String)

    /**
     * @return 第一个可播的替代曲；无可播替代 / 搜索失败返回 null（由调用方决定跳下一首）
     */
    suspend fun pick(title: String, artist: String, excludeId: String): Replacement? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) {
            AppLog.w(DIAG, "CandidatePicker: title 与 artist 均为空，跳过同源重搜")
            return null
        }
        // 一次搜索、一个关键词（§6 验收 16 ③）。⚠️ 这里**故意不做变体后缀**：
        // §3.8 设想的"复用 SearchViewModel 那张 24 后缀表"会把 1 次搜索放大成 N 次，
        // 在公共端点上直接撞 R3 限流，而本期收益未实测（§9 T1）。
        val pool = try {
            search(query)
        } catch (e: Exception) {
            AppLog.w(DIAG, "CandidatePicker: search '$query' threw ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        val candidates = pool
            .filter { it.id != excludeId && titleMatches(it.title, title) }
            .distinctBy { it.id }
            .take(maxCandidates)
        for (c in candidates) {
            val r = try {
                resolve(c)
            } catch (e: Exception) {
                AppLog.w(DIAG, "CandidatePicker: resolve ${c.title} threw ${e.message}")
                null
            }
            val url = r?.url
            if (r != null && r.isSuccess && !url.isNullOrBlank()) {
                // 替换即可播：直链挂在副本上，⛔ 不写回收藏/下载（R8）
                return Replacement(c.copy(streamUrl = url), url)
            }
        }
        // F6（终审 M-B1）：降级链的最终放弃路径，release 下 d/i/w 连字符串都被折掉，必须用 e 留痕
        AppLog.e(DIAG, "CandidatePicker: 候选全不可播（pool=${pool.size} 校验=${candidates.size}）title='$title' artist='$artist'")
        return null
    }

    companion object {
        private const val DIAG = "MetingDiag"

        /** 候选上限（§3.8 边界：⛔ 不做端点 × 平台 × 候选的笛卡尔积） */
        const val MAX_CANDIDATES = 3

        /**
         * 标题判定：**归一化全等** 或 **候选以原标题为前缀且前缀之后的首字符不是 CJK 汉字**
         * （版本后缀是同源重搜的主要目标形态）。
         *
         * ⛔ 不做包含/编辑距离之类的模糊——"晴天"重搜回"安静"并自动替用户播放，
         * 在 Q7③（自动处理、无需用户关注）下是不可接受的事故。
         * F10（终审裁定 (a)）：光认前缀还放行**中文前缀续写**——"告白" ⊂ "告白气球"，
         * 那是另一首歌，必须挡下；拉丁/数字后缀（"晴天MV"/"晴天remix"/"晴天2"）是**有意放行**的版本变体。
         * 归一化复用 `ArtistSplitter.normalizeKey`（它只是 NFKC + 空白折叠 + lowercase，与"艺人"无关）。
         */
        internal fun titleMatches(candidateTitle: String, originalTitle: String): Boolean {
            val c = ArtistSplitter.normalizeKey(candidateTitle)
            val o = ArtistSplitter.normalizeKey(originalTitle)
            if (c.isEmpty() || o.isEmpty()) return false
            val rest = if (c.startsWith(o)) c.substring(o.length) else ""
            // 终审裁定 (a)：只挡 CJK 续写（"告白" → "告白气球"），⛔ 不挡拉丁/数字后缀
            // （既有测试 `titleMatches("晴天MV", "晴天") == true` 是**有意的**：
            //   "晴天MV"/"晴天remix"/"晴天2" 都属于版本变体，正是同源重搜的目标形态。）
            // rest 为空只可能是 c == o（由左支覆盖）或非前缀（startsWith 支本就不成立），`?: false` 取保守值。
            val boundaryOk = rest.firstOrNull()?.let { ch -> ch !in '\u4E00'..'\u9FFF' } ?: false
            return c == o || (o.length >= 2 && c.startsWith(o) && boundaryOk)
        }
    }
}
