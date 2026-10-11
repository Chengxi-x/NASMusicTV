package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺人全量展开的状态件（艺人全量方案 §3.5）。
 *
 * 纯 JVM 类，不加 Robolectric——`NetworkArtistExpansion` 无 Android 依赖，
 * 省掉一轮 sandbox 启动。
 */
class ArtistSongsPagingTest {

    private fun song(id: String, artist: String, artistId: String? = "6452") = Song(
        id = "ntwk_meting_$id", title = "t$id", artist = artist, artistId = artistId,
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    private fun page(ids: List<String>, dead: List<String>, more: Boolean, total: Int) = ArtistSongsPage(
        songs = ids.map { song(it, "周杰伦") },
        total = total,
        hasMore = more,
        unplayableIds = dead.map { "ntwk_meting_$it" }.toSet()
    )

    /** 记录调用次序的假后端：fetches 存 (artistId, offset) */
    private class Fake(val pages: MutableMap<Int, ArtistSongsPage?>, val fetches: MutableList<Pair<String, Int>>) {
        var findArtistCalls = 0
        fun expansion() = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; pages[offset] },
            findArtist = { _ -> findArtistCalls++; NetworkArtistInfo("6452", "周杰伦", 566) }
        )
    }

    @Test
    fun `强命中读首屏自带的 artistId 且零额外 findArtist`() = runBlocking {
        val f = Fake(
            mutableMapOf(0 to page((1..100).map { "$it" }, dead = listOf("7"), more = true, total = 566)),
            mutableListOf()
        )
        val exp = f.expansion()
        val out = exp.expand("周杰伦", listOf(song("900", "周杰伦"), song("901", "周杰伦")))
        assertEquals(0, f.findArtistCalls)
        assertEquals(NetworkArtistExpansion.HitSource.NATIVE_FIELD, exp.lastHitSource)
        assertEquals(100, out.size)
        assertEquals(listOf("6452" to 0), f.fetches)
        assertEquals(setOf("ntwk_meting_7"), exp.unplayableIds)
    }

    @Test
    fun `末页恰满 100 且 more=false 时不得再多发一次请求`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page((1..100).map { "$it" }, emptyList(), more = false, total = 100) },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        val afterFirst = fetches.size
        exp.loadMore()
        assertEquals("§2 D：more 已转 false，照 size==pageSize 猜就会多发一次空请求", afterFirst, fetches.size)
        assertFalse(exp.paging.hasMore)
    }

    @Test
    fun `续页按已加载条数取 offset 且累积打标集`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ ->
                fetches += id to offset
                page(((offset + 1)..(offset + 100)).map { "$it" }, dead = listOf("${offset + 1}"), more = offset < 400, total = 566)
            },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        exp.loadMore()
        exp.loadMore()
        assertEquals(listOf("6452" to 0, "6452" to 100, "6452" to 200), fetches)
        assertEquals(300, exp.paging.songs.size)
        assertEquals(3, exp.unplayableIds.size)
    }

    @Test
    fun `可播置顶保持稳定序且只作用于艺人分支`() {
        val dead = setOf("ntwk_meting_2", "ntwk_meting_3")
        val songs = listOf(song("1", "周杰伦"), song("2", "周杰伦"), song("3", "周杰伦"), song("4", "周杰伦"))
        val out = NetworkArtistExpansion.playableFirst(songs, dead)
        assertEquals(listOf("ntwk_meting_1", "ntwk_meting_4", "ntwk_meting_2", "ntwk_meting_3"), out.map { it.id })
        // 打标集为空（普通搜索分支 / 端点兜底分支的形状）→ 顺序必须原样不动
        val plain = listOf(song("5", "周杰伦"), song("6", "周杰伦"))
        assertEquals(plain.map { it.id }, NetworkArtistExpansion.playableFirst(plain, emptySet()).map { it.id })
    }

    @Test
    fun `弱匹配不算命中——周杰不得展开周杰伦的名录`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page(listOf("1"), emptyList(), more = false, total = 1) },
            findArtist = { null }      // type=100 兜底也没命中
        )
        val networkPart = listOf(song("900", "周杰伦"))
        val out = exp.expand("周杰", networkPart)
        assertEquals("§10.2 规则 4：前缀匹配是产品级事故", networkPart, out)
        assertTrue(fetches.isEmpty())
        assertFalse(exp.isActive())
        assertTrue(exp.unplayableIds.isEmpty())
    }

    @Test
    fun `feat 合唱行不算主艺人的反例要成立`() {
        // 关键词是主唱本人时，含 feat 的行仍应命中（ArtistSplitter 已拆 feat.）
        val withFeat = listOf(Song(id = "ntwk_meting_1", title = "x", artist = "周杰伦 / 彭濼",
            artistId = "6452", isNetworkSong = true, networkSource = "meting", networkId = "1"))
        assertEquals("6452", NetworkArtistExpansion.hitArtistId("彭濼", withFeat))
        // 但"周杰伦 / 彭濼"整体作为一个 artist 字段时，关键词"周杰伦"同样命中
        assertEquals("6452", NetworkArtistExpansion.hitArtistId("周杰伦", withFeat))
    }

    @Test
    fun `合并结果 id 必须唯一——重复 key 会让 LazyVerticalGrid 直接崩`() {
        val expanded = listOf(song("1", "周杰伦"), song("2", "周杰伦"))
        val other = listOf(song("1", "周杰伦"), song("9", "蔡依林"))     // 非网络段与展开段撞同一 netId
        val merged = NetworkArtistExpansion.mergeWithExpansion(other, expanded)
        assertEquals(3, merged.size)
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
        // distinctBy 保 first-wins：非网络段在前（mergeWithExpansion 的入参次序），撞车的展开段被丢弃
        assertEquals(listOf("ntwk_meting_1", "ntwk_meting_9", "ntwk_meting_2"), merged.map { it.id })
    }

    @Test
    fun `reset 后不再续取（R7 切艺人即重置）`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page(listOf("1"), emptyList(), more = true, total = 566) },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        exp.reset()
        assertFalse(exp.isActive())
        assertEquals(0, exp.loadMore().size)
        assertTrue(exp.unplayableIds.isEmpty())
    }
}
