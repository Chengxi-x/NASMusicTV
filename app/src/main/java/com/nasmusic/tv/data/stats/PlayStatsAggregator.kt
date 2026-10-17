package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.ArtistSplitter

/**
 * 播放统计聚合结果模型（F2-1）
 */
data class ArtistStat(
    val artist: String,
    val playCount: Int,
    val songCount: Int,
    val coverUrl: String? = null
)

data class GenreStat(
    val genre: String,
    val playCount: Int,
    val songCount: Int
)

data class StatsBundle(
    val month: String,
    val totalPlays: Int,
    val distinctSongs: Int,
    val topArtists: List<ArtistStat>,
    val genreDistribution: List<GenreStat>
)

/**
 * 播放统计聚合器（F2-1）——纯函数，可单测。
 *
 * 输入：月度 songId 次数表 + Song 元数据来源（recentSongObjects / PlayRecord 历史等）。
 * 聚合策略（见 docs/archive/feature-dev-plan-2026-09.md §1.6）：
 * - artist 先用 [ArtistSplitter] 拆分再归并：合唱曲目（"林忆莲/李宗盛"）同时记给每位歌手，
 *   与歌库歌手列表 [com.nasmusic.tv.ui.screens.library.browse.ArtistList] 同口径，
 *   否则统计页会出现 "A/B" 这类合并名，点不进任何歌手
 * - 归并键走 [ArtistSplitter.normalizeKey]（NFKC + trim + 折叠空白 + 小写），
 *   GBK mojibake 变体不强行归并，设计取舍
 * - genre 为 null/blank 归入"未分类"
 */
object PlayStatsAggregator {

    private const val UNTAGGED_GENRE = "未分类"
    private const val UNTAGGED_ARTIST = "未知歌手"

    fun aggregate(
        month: String,
        playCounts: Map<String, Int>,
        songMeta: List<Song>,
        topArtistLimit: Int = 10,
        genreLimit: Int = 8
    ): StatsBundle {
        val metaById = songMeta.associateBy { it.id }
        var totalPlays = 0

        // artist 归并（归一化 key → 展示名取首次命中的写法）
        data class ArtistAcc(
            var plays: Int,
            var songs: MutableSet<String>,
            var cover: String?,
            var display: String
        )
        val artistAcc = mutableMapOf<String, ArtistAcc>()
        // genre 归并
        data class GenreAcc(var plays: Int, var songs: MutableSet<String>)
        val genreAcc = mutableMapOf<String, GenreAcc>()
        val countedSongIds = mutableSetOf<String>()

        for ((songId, count) in playCounts) {
            totalPlays += count
            val song = metaById[songId] ?: continue
            countedSongIds.add(songId)

            val names = ArtistSplitter.split(song.artist).ifEmpty { listOf(UNTAGGED_ARTIST) }
            for (name in names) {
                val artistKey = ArtistSplitter.normalizeKey(name).ifBlank { UNTAGGED_ARTIST }
                artistAcc.getOrPut(artistKey) { ArtistAcc(0, mutableSetOf(), song.coverUrl, name) }
                    .apply {
                        plays += count
                        songs.add(songId)
                    }
            }

            val genreKey = song.genre?.trim()?.ifBlank { null } ?: UNTAGGED_GENRE
            genreAcc.getOrPut(genreKey) { GenreAcc(0, mutableSetOf()) }.apply {
                plays += count
                songs.add(songId)
            }
        }

        val topArtists = artistAcc.entries
            .sortedWith(compareByDescending<MutableMap.MutableEntry<String, ArtistAcc>> { it.value.plays }.thenBy { it.key })
            .take(topArtistLimit)
            .map { (_, acc) ->
                ArtistStat(
                    artist = acc.display,
                    playCount = acc.plays,
                    songCount = acc.songs.size,
                    coverUrl = acc.cover
                )
            }

        val genres = genreAcc.entries
            .sortedWith(compareByDescending<MutableMap.MutableEntry<String, GenreAcc>> { it.value.plays }.thenBy { it.key })
            .take(genreLimit)
            .map { (key, acc) -> GenreStat(genre = key, playCount = acc.plays, songCount = acc.songs.size) }

        return StatsBundle(
            month = month,
            totalPlays = totalPlays,
            distinctSongs = countedSongIds.size,
            topArtists = topArtists,
            genreDistribution = genres
        )
    }
}
