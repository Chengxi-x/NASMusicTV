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

    /**
     * F6（终审 M-B1）：`expand()` **命中艺人但取作品返回 null ⇒ 放弃展开**的专用信号。
     *
     * 该类是 ⛔ 不做 IO 的纯状态件、无 AppLog 依赖，自己不打日志；由调用侧（`SearchViewModel`）
     * 读到 true 时打 `AppLog.e` 留痕。⚠️ `reset()` 会把 `lastHitSource` 清成 NONE，
     * 这条路径在 VM 侧与「普通未命中艺人」无从区分，所以单独设这个位。
     *
     * ⚠️ **读取契约（终审 F5F6F8 审查 I-1）**：它由 [expand] 在**自己返回前**写入，
     * 所以调用方必须在 `expand()` 返回后**立刻**取进局部量。⛔ 不得跨词读、⛔ 不得在代数闸门之后
     * 才回头读这个字段——重叠搜索下那时读到的是别的关键词留下的值。
     */
    var lastArtistPageFetchFailed: Boolean = false
        private set

    private var artistId: String? = null
    private var total = 0
    private var more = false
    private val unplayable = linkedSetOf<String>()
    private val fetched = mutableListOf<Song>()

    /**
     * I1：`loadMore()` 的**真实单飞标志**。
     *
     * ⚠️ 曾经没有这个字段——VM 的门读的是 `paging.isLoading`，而 `paging` 把 `isLoading` 硬编码成
     * `false`（`copy(isLoading = true)` 只改 VM 自己的 StateFlow，改不到 expansion），
     * 那道门**恒不生效**：两次并发 `loadMore()` 读到同一个 `fetched.size` 当 offset、各自
     * `accumulate` 一次 ⇒ `fetched.size` 虚高 ⇒ 下一页 offset 永久跳过一整页
     * （`mergeWithExpansion` 的 `distinctBy` 只是把重复藏进网格，丢失是**静默**的）。
     */
    private var loadingMore = false

    /** I1：VM 的在途门读这个，⛔ 别再读 `paging.isLoading` */
    fun isLoadingMore(): Boolean = loadingMore

    val unplayableIds: Set<String> get() = unplayable.toSet()

    fun isActive(): Boolean = artistId != null

    /** 交 UI 的分页状态（`hasMore` **只**来自接口 `more`，§3.4） */
    val paging: SongsPagingState
        get() = SongsPagingState(
            songs = fetched.toList(),
            totalCount = total,
            isLoading = loadingMore,
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
        loadingMore = false
    }

    /**
     * 关键词命中艺人则取第一页全量，否则**原样返回**网络段。
     *
     * @param networkPart 融合结果里 **Meting 家族**那一段（`type=1` 或 Meting 端点兜底，
     *   由 `splitMetingSegment` 切出）。⛔ 调用方不得把 Jamendo / 百度网盘 / NAS 行混进来
     *   （C1：本类会**整段替换**入参，混进来就等于把它们从统一搜索里删掉）。
     */
    suspend fun expand(keyword: String, networkPart: List<Song>): List<Song> {
        val (songs, pageFetchFailed) = runExpand(keyword, networkPart)
        // F6 信号**只在本次调用的出口写**（终审 F5F6F8 审查 I-1）。⛔ 不要在入口清零：
        // 先发起的那次会挂起在 getArtistSongs 上，期间另一个关键词跑完并把标志写成 true，
        // 等它回来时读到的就是**别人的结论** ⇒ release 上「正常展开成功的词也在报取作品失败」。
        // 改成出口写之后：每次调用 leave 的都是自己的结论，入口复位既不需要也不正确。
        lastArtistPageFetchFailed = pageFetchFailed
        return songs
    }

    /** [expand] 的本体；第二值 = **本次**是否「命中艺人但取作品返回 null」。 */
    private suspend fun runExpand(
        keyword: String,
        networkPart: List<Song>
    ): Pair<List<Song>, Boolean> {
        reset()
        // C1：空段 = 本词压根没有 Meting 行（网络源被熄灭，或首屏全是别的源）。
        // 这时连那**一次** findArtist 兜底都不能发——否则每一次普通歌名搜索都会白打一次
        // music.163.com，并且精确同名命中时还会把用户显式过滤掉的网络源塞回结果里。
        if (networkPart.isEmpty()) return networkPart to false
        val id = hitArtistId(keyword, networkPart)
        if (id != null) {
            lastHitSource = HitSource.NATIVE_FIELD
            // I2：命中即写 artistId，不依赖首页是否非空（旧写法只从 page.songs 反推，
            // 首页空 ⇒ isActive()=false 而 more=true 的矛盾态，分页永久卡死）。
            artistId = id
        } else {
            // §10.2 规则 3：只有首屏无命中才发这**一次**兜底请求（端点兜底分支没有 artistId 时走这里）
            val found = findArtist(keyword)?.takeIf {
                ArtistSplitter.normalizeKey(it.name) == ArtistSplitter.normalizeKey(keyword)
            }
            if (found == null) return networkPart to false
            artistId = found.id
            lastHitSource = HitSource.FIND_ARTIST
        }
        val artist = artistId ?: return networkPart to false
        val page = getArtistSongs(artist, 0, pageSize) ?: run {
            // 本信号的**唯一**置位点（F6 的头号静默路径）。交出去的是返回值而⛔ 不是共享字段，
            // 于是「必须在 reset() 之后」这条次序约束从此**结构上不可能被改坏**（审查 I-2）。
            reset()
            return networkPart to true
        }
        accumulate(page)
        if (fetched.isEmpty()) {
            // I2：命中艺人但首页零行。此时 `more` 可能仍是接口给的 true，那会是
            // "hasMore=true 却无以为继"的卡死态，而 `playableFirst(emptyList, …) == emptyList`
            // 会把手里的 networkPart **整段丢掉**。交回原段并复位（isActive 与 hasMore 重新一致）。
            // ⛔ 这不算 F6 的那条路径：接口**答了、只是答空**（取数没失败），别混进留痕判据。
            reset()
            return networkPart to false
        }
        return playableFirst(fetched.toList(), unplayable) to false
    }

    /**
     * 触底续页。未命中 / 已到底 / 已在途 → 原样返回现有行，⛔ 不发请求（§6 验收 5 + §2 D + I1）
     */
    suspend fun loadMore(): List<Song> {
        val artist = artistId ?: return emptyList()
        if (!more) return playableFirst(fetched.toList(), unplayable)
        if (loadingMore) return playableFirst(fetched.toList(), unplayable)
        loadingMore = true
        try {
            val page = getArtistSongs(artist, fetched.size, pageSize) ?: run {
                more = false
                return playableFirst(fetched.toList(), unplayable)
            }
            // 终审 B2 的**状态侧**：取页在途期间 `expand()`/`clearSearch()` 已 reset 或换了艺人
            // ⇒ 这一页属于上一个艺人，⛔ 不得 accumulate（混进 fetched、覆盖 total/more，
            //   且 VM 的代数门只挡发布、挡不住这里的污染——后续续页会带着它一起少一整页）。
            if (artistId != artist) return playableFirst(fetched.toList(), unplayable)
            accumulate(page)
            return playableFirst(fetched.toList(), unplayable)
        } finally {
            loadingMore = false
        }
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
         * 统一搜索的**网络段判据**（C1）：只有 Meting 家族归本类管辖，其余一切原样透传。
         *
         * ⛔ 不能用 `isNetworkSong` 当判据：
         * - Jamendo（`JamendoService`）与百度网盘（`BaiduFile` / `BaiduFileIndex`）的歌
         *   **同样** `isNetworkSong == true`，而 [expand] 命中艺人时会**整段替换**入参，
         *   于是每一次艺人命中都会把百度/Jamendo 的命中行从统一搜索网格里删掉；
         * - 用户熄灭 `NETWORK_MUSIC` 后，`isNetworkSong` 判据仍会把手里的空段送进来，
         *   触发一次**无条件**的 `findArtist` 远程请求，命中时还会把被过滤掉的源塞回结果。
         *
         * 返回的 `first` 是要交给 expansion 的 Meting 段，`second` 是必须原样保留的其余段
         * （NAS / 百度 / Jamendo / 电台 / 本地）。
         */
        internal fun splitMetingSegment(songs: List<Song>): Pair<List<Song>, List<Song>> =
            songs.partition { it.isNetworkSong && it.networkSource == NetworkSongId.METING_SOURCE }

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
