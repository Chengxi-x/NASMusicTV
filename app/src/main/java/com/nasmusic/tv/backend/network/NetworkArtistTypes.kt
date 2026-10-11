package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song

/**
 * 网络源的"艺人"概念载体（艺人全量方案 §3.3）。
 *
 * ⛔ 不复用 `data/model/Artist.kt`：那个模型的 `id` 属于 NAS 后端命名空间，
 * 与网易的 `6452` 混用会让"同一 id 在两个来源指向不同的人"成为可能（R8 同源教训）。
 */
data class NetworkArtistInfo(
    val id: String,
    val name: String,
    val songCount: Int
)

/**
 * 艺人作品的一页。
 *
 * @param total 接口声明的总数（§2 D：`total=true` 时与逐页累加一致，566=566）
 * @param hasMore **取自接口的 `more` 字段**，⛔ 不许用 `size == pageSize` 猜（§3.4）
 * @param unplayableIds 疑似无版权曲目 id 集合；**仅用于打标/置顶，⛔ 不可用于过滤**（T2）。
 *        不支持该概念的源 / `type=1` 形态返回空集（§2 C.1 覆盖 0/100）。
 */
data class ArtistSongsPage(
    val songs: List<Song>,
    val total: Int,
    val hasMore: Boolean,
    val unplayableIds: Set<String>
)
