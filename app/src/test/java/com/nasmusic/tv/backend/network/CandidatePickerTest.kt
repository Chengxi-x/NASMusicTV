package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同源重搜候选测试（§6 验收 14）。
 *
 * fake 只暴露 search / resolve 两个注入点，**断言的正是"除了这两个什么都没碰"**——
 * 替换产物不落库（收藏/下载）这件事在结构上由"没有第三个口"保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidatePickerTest {

    /**
     * 红线 1：id 一律由 [NetworkSongId] 生成，⛔ 不在 fixture 里手拼字符串
     *（产物与 `ntwk_meting_<netId>` 逐字符相同，下面的字面量断言因此仍然成立）。
     */
    private fun song(id: String, title: String, artist: String = "周杰伦") = Song(
        id = NetworkSongId.forNetworkSong("meting", id), title = title, artist = artist,
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    /** 记录调用轨迹的假搜索/假解析 */
    private class Harness(
        val pool: List<Song>,
        val playable: Set<String>            // 这些 id 判 PLAYABLE，其余判 DEAD
    ) {
        val queries = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        fun picker() = CandidatePicker(
            search = { q -> queries += q; pool },
            resolve = { s ->
                resolved += s.id
                if (s.id in playable) ResolveResult("https://cdn/${s.networkId}.mp3", QualityTiers.AUTO)
                else ResolveResult.failure(QualityTiers.AUTO, probedDead = true)
            }
        )
    }

    @Test
    fun `一次搜索一个关键词——title 与 artist 都带上`() = runBlocking {
        val origin = song("1", "晴天")
        val h = Harness(listOf(song("2", "晴天 (Live)")), playable = setOf("ntwk_meting_2"))
        val r = h.picker().pick(origin.title, origin.artist, excludeId = origin.id)
        assertNotNull(r)
        assertEquals(listOf("晴天 周杰伦"), h.queries)     // 只 1 次搜索（§6 验收 16 ③）
        assertEquals("ntwk_meting_2", r?.song?.id)
    }

    @Test
    fun `排除原曲 id`() = runBlocking {
        val origin = song("1", "晴天")
        val h = Harness(listOf(song("1", "晴天"), song("2", "晴天")), playable = setOf("ntwk_meting_2"))
        val r = h.picker().pick(origin.title, origin.artist, excludeId = origin.id)
        assertEquals(listOf("ntwk_meting_2"), h.resolved)   // 原曲一次都不该被解析
        assertEquals("ntwk_meting_2", r?.song?.id)
    }

    @Test
    fun `候选数上限 3 生效——不许演变成全池扫描`() = runBlocking {
        val pool = (1..10).map { song("${it + 1}", "晴天") }
        val h = Harness(pool, playable = emptySet())
        assertNull(h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1"))
        assertEquals("最多 3 次解析", listOf("ntwk_meting_2", "ntwk_meting_3", "ntwk_meting_4"), h.resolved)
    }

    @Test
    fun `只探到第一个可播即停，不再验证后面的候选`() = runBlocking {
        val pool = (1..6).map { song("${it + 1}", "晴天") }
        val h = Harness(pool, playable = setOf("ntwk_meting_4"))
        val r = h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1")
        assertEquals("ntwk_meting_4", r?.song?.id)
        assertEquals(listOf("ntwk_meting_2", "ntwk_meting_3", "ntwk_meting_4"), h.resolved)
    }

    @Test
    fun `替换产物自带已解析直链，⛔ 不写回收藏与下载`() = runBlocking {
        val h = Harness(listOf(song("2", "晴天")), playable = setOf("ntwk_meting_2"))
        val r = h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1")!!
        // ① 替换即可播：直链已经解析好并挂在 song.streamUrl 上
        assertEquals("https://cdn/2.mp3", r.song.streamUrl)
        assertEquals("https://cdn/2.mp3", r.playUrl)
        // ② 副作用为零：全程只有 1 次搜索 + 1 次解析，没有第三个调用口
        assertEquals(listOf("晴天 周杰伦"), h.queries)
        assertEquals(listOf("ntwk_meting_2"), h.resolved)
        // ③ 红线不撕裂：来源与 id 命名空间仍是 meting（Task 1 的构造点负责）
        assertEquals("meting", r.song.networkSource)
        assertEquals("ntwk_meting_2", r.song.id)
    }

    @Test
    fun `同名不同歌必须挡住——重搜不能把用户导到别的曲子`() = runBlocking {
        val h = Harness(listOf(song("2", "安静")), playable = setOf("ntwk_meting_2"))
        assertNull(h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1"))
        assertTrue("标题不符的候选一次都不该被解析", h.resolved.isEmpty())
    }

    @Test
    fun `artist 为空时退化为纯 title，且不残留多余空格`() = runBlocking {
        val h = Harness(listOf(song("2", "晴天")), playable = setOf("ntwk_meting_2"))
        h.picker().pick("晴天", "", excludeId = "ntwk_meting_1")
        assertEquals(listOf("晴天"), h.queries)
    }

    @Test
    fun `搜索抛异常等于没有替代，⛔ 绝不上抛`() = runBlocking {
        val picker = CandidatePicker(
            search = { throw java.io.IOException("endpoint down") },
            resolve = { ResolveResult.failure(QualityTiers.AUTO) }
        )
        assertNull(picker.pick("晴天", "周杰伦", excludeId = "ntwk_meting_1"))
    }

    // ── titleMatches 的边界（纯函数，无 fixture） ─────────────
    @Test
    fun `titleMatches 只认全等与前缀，不做模糊`() {
        fun m(c: String, o: String) = CandidatePicker.titleMatches(c, o)
        assertTrue(m("晴天", "晴天"))
        assertTrue(m("晴天 (Live)", "晴天"))          // 版本差异正是同源重搜要找的东西
        assertTrue(m("晴天MV", "晴天"))
        assertFalse(m("安静", "晴天"))
        assertFalse(m("晴天", "晴天雨"))               // 反方向不算
        assertFalse(m("晴", "晴天"))                   // 短标题不当前缀用
    }

    /**
     * F10（终审裁定 (a)）：前缀匹配必须带 CJK 续写护栏——
     * "告白"→"告白气球" 是换了一首歌，在 Q7③ 的自动替换语义下不可发现，必须拒；
     * 拉丁/数字后缀是**有意放行**的版本变体（正是同源重搜的目标形态），钉住防后来者收紧过头。
     */
    @Test
    fun `titleMatches 前缀之后不得是 CJK 续写，但拉丁数字后缀有意放行`() {
        fun m(c: String, o: String) = CandidatePicker.titleMatches(c, o)
        assertFalse("本次修复的正主：《告白》不能被自动换成《告白气球》", m("告白气球", "告白"))
        assertFalse("反方向：候选更短同样不算", m("告白", "告白气球"))
        assertTrue(m("晴天remix", "晴天"))              // 拉丁后缀 = 版本变体，放行
        assertTrue(m("晴天2", "晴天"))                  // 数字后缀，放行
        assertTrue("全角括号经 NFKC 归一后首字符是 '('，非 CJK ⇒ 放行", m("告白（live）", "告白"))
    }

    @Test
    fun `pick 不把 CJK 前缀续写当同名——告白不会自动换成告白气球`() = runBlocking {
        val h = Harness(listOf(song("2", "告白气球")), playable = setOf("ntwk_meting_2"))
        assertNull(h.picker().pick("告白", "周杰伦", excludeId = "ntwk_meting_1"))
        assertTrue("护栏在自动替换链（ReplaceChain 同源层）上必须可达：续写候选一次都不该被解析", h.resolved.isEmpty())
    }
}
