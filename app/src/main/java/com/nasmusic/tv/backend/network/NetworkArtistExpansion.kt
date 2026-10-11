package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.util.ArtistSplitter

/**
 * 艺人全量展开的**状态件**（艺人全量方案 §3.5）。
 *
 * 装进独立类而不是往 `SearchViewModel` 堆字段：VM 已 359 行、刚做完 R-1 拆分，
 * 且 AndroidViewModel 在本仓库**不可单测**——本类无 Android 依赖，纯 JVM 测试直接打。
 *
 * ⛔ 不做 IO：所有取数走构造期注入的两个 suspend lambda，
 * 由 `SearchViewModel` 接到 `NetworkMusicManager`（存在理由同 `NetworkPlaybackResolver`）。
 */
class NetworkArtistExpansion(
    private val getArtistSongs: suspend (artistId: String, offset: Int, limit: Int) -> ArtistSongsPage?,
    private val findArtist: suspend (name: String) -> NetworkArtistInfo?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    /** 命中来源，仅供诊断日志与单测断言（决定"这次展开有没有多花一次 type=100 请求"） */
    enum class HitSource { NONE, NATIVE_FIELD, FIND_ARTIST }

    var lastHitSource: HitSource = HitSource.NONE
        private set

    private var artistId: String? = null
    private var total = 0
    private var more = false
    private val unplayable = linkedSetOf<String>()
    private val fetched = mutableListOf<Song>()

    val unplayableIds: Set<String> get() = unplayable.toSet()

    fun isActive(): Boolean = artistId != null

    /** 交 UI 的分页状态（`hasMore` **只**来自接口 `more`，§3.4） */
    val paging: SongsPagingState
        get() = SongsPagingState(
            songs = fetched.toList(),
            totalCount = total,
            isLoading = false,
            hasMore = more,
            currentPage = if (pageSize <= 0) 0 else fetched.size / pageSize
        )

    /** 换词 / 换艺人即重置（R7：分页状态只存当前艺人，不入全局缓存） */
    fun reset() {
        artistId = null
        total = 0
        more = false
        unplayable.clear()
        fetched.clear()
        lastHitSource = HitSource.NONE
    }

    /**
     * 关键词命中艺人则取第一页全量，否则**原样返回**网络段。
     *
     * @param networkPart 融合结果里 `isNetworkSong` 的那一段（`type=1` 或 Meting 端点兜底）
     */
    suspend fun expand(keyword: String, networkPart: List<Song>): List<Song> {
        reset()
        val id = hitArtistId(keyword, networkPart)
        if (id != null) {
            lastHitSource = HitSource.NATIVE_FIELD
        } else {
            // §10.2 规则 3：只有首屏无命中才发这**一次**兜底请求（端点兜底分支没有 artistId 时走这里）
            val found = findArtist(keyword)?.takeIf {
                ArtistSplitter.normalizeKey(it.name) == ArtistSplitter.normalizeKey(keyword)
            }
            if (found == null) return networkPart
            artistId = found.id
            lastHitSource = HitSource.FIND_ARTIST
        }
        // 两条分支必有且仅有一条给出 artistId（NATIVE_FIELD 给 id、FIND_ARTIST 给 artistId），
        // 第三个 `?: return` 只是让编译器确信这里非空，运行时恒不可达。
        val artist = artistId ?: id ?: return networkPart
        val page = getArtistSongs(artist, 0, pageSize) ?: run {
            reset()
            return networkPart
        }
        accumulate(page)
        return playableFirst(fetched.toList(), unplayable)
    }

    /** 触底续页。未命中 / 已到底 → 原样返回现有行，⛔ 不发请求（§6 验收 5 + §2 D） */
    suspend fun loadMore(): List<Song> {
        val artist = artistId ?: return emptyList()
        if (!more) return playableFirst(fetched.toList(), unplayable)
        val page = getArtistSongs(artist, fetched.size, pageSize) ?: run {
            more = false
            return playableFirst(fetched.toList(), unplayable)
        }
        accumulate(page)
        return playableFirst(fetched.toList(), unplayable)
    }

    private fun accumulate(page: ArtistSongsPage) {
        fetched += page.songs
        unplayable += page.unplayableIds
        total = page.total
        more = page.hasMore
        if (artistId == null) artistId = page.songs.firstOrNull()?.artistId
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * 强命中判定（§10.2）：归一化全等 + 取"首屏出现次数最多"的 artistId。
         *
         * ⛔ 不做前缀/模糊匹配；⛔ 不新建匹配器（`ArtistSplitter` 已处理 `／ & 、 feat.`）。
         */
        internal fun hitArtistId(keyword: String, songs: List<Song>): String? {
            val key = ArtistSplitter.normalizeKey(keyword)
            if (key.isBlank()) return null
            val ids = songs.mapNotNull { s ->
                val id = s.artistId
                if (id != null && ArtistSplitter.containsArtistWithKey(s.artist, key)) id else null
            }
            if (ids.isEmpty()) return null
            return ids.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }

        /**
         * 可播置顶（Q1 的 c）：`st == 0` 在前、打标行在后，**两组内部各自保序**。
         *
         * ⚠️ 只对**艺人分支**调用；打标集为空时等价于原序返回（普通搜索分支/端点兜底分支保持上游顺序）。
         * ⛔ 这里绝不 `filter` —— T2 明令 `privilege.st` 只能打标不能过滤。
         */
        internal fun playableFirst(songs: List<Song>, unplayableIds: Set<String>): List<Song> {
            if (unplayableIds.isEmpty()) return songs
            val (ok, dead) = songs.partition { it.id !in unplayableIds }
            return ok + dead
        }

        /**
         * 非网络段（NAS / 百度 / Jamendo / 本地）+ 展开后的网络段，按 id 去重。
         *
         * ⚠️ 去重不是洁癖：`SearchTab` 的网格用 `key = { searchResults[it].id }`
         * （`ui/screens/library/SearchTab.kt:146`），**同 id 两次出现会直接抛
         * "Key was already used"**。展开段与 `type=1` 首屏共享 id 命名空间（红线 1），
         * 撞车是**必然**而不是偶然。
         */
        internal fun mergeWithExpansion(otherPart: List<Song>, expanded: List<Song>): List<Song> =
            (otherPart + expanded).distinctBy { it.id }
    }
}
