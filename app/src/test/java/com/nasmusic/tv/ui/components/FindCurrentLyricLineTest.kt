package com.nasmusic.tv.ui.components

import com.nasmusic.tv.data.model.LyricsLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * findCurrentLyricLine 单元测试 —— 全屏可视化舞台顶部歌词行的二分查找。
 *
 * 该函数被 `derivedStateOf` 包裹，在播放期间随进度频繁求值；
 * 语义错误不会崩溃，但会让歌词行错位一整句，因此边界必须钉死。
 */
class FindCurrentLyricLineTest {

    private fun lines(vararg times: Long): List<LyricsLine> =
        times.mapIndexed { i, t -> LyricsLine(time = t, text = "line$i") }

    @Test
    fun `null and empty lyrics return -1`() {
        assertEquals(-1, findCurrentLyricLine(null, 10_000L))
        assertEquals(-1, findCurrentLyricLine(emptyList(), 10_000L))
    }

    @Test
    fun `progress before the first line returns -1`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(-1, findCurrentLyricLine(l, 0L))
        assertEquals(-1, findCurrentLyricLine(l, 999L))
    }

    @Test
    fun `exact boundary belongs to the newer line`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(0, findCurrentLyricLine(l, 1_000L))
        assertEquals(1, findCurrentLyricLine(l, 5_000L))
        assertEquals(2, findCurrentLyricLine(l, 9_000L))
    }

    @Test
    fun `progress between lines keeps the previous line`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(0, findCurrentLyricLine(l, 1_001L))
        assertEquals(0, findCurrentLyricLine(l, 4_999L))
        assertEquals(1, findCurrentLyricLine(l, 5_001L))
        assertEquals(1, findCurrentLyricLine(l, 8_999L))   // 差 1ms 仍属第 2 句
        assertEquals(2, findCurrentLyricLine(l, 9_000L))
    }

    @Test
    fun `progress past the last line clamps to the last index`() {
        val l = lines(1_000L, 5_000L, 9_000L)
        assertEquals(2, findCurrentLyricLine(l, 9_001L))
        assertEquals(2, findCurrentLyricLine(l, 1_000_000L))
    }

    @Test
    fun `single line behaves like an on off switch`() {
        val l = lines(2_000L)
        assertEquals(-1, findCurrentLyricLine(l, 1_999L))
        assertEquals(0, findCurrentLyricLine(l, 2_000L))
        assertEquals(0, findCurrentLyricLine(l, 99_999L))
    }

    @Test
    fun `duplicate timestamps resolve to the last matching line`() {
        val l = lines(1_000L, 1_000L, 1_000L, 2_000L)
        assertEquals(2, findCurrentLyricLine(l, 1_000L))
        assertEquals(3, findCurrentLyricLine(l, 2_000L))
    }

    @Test
    fun `long lyrics stay correct across the whole progress range`() {
        // 1000 行，每行间隔 300ms；覆盖 10000 个进度点求值
        val count = 1_000
        val l = List(count) { LyricsLine(time = it * 300L, text = "line$it") }

        var mismatches = 0
        for (k in 0 until 10_000) {
            val progress = (k * 37L) % (count * 300L)
            val expected = (progress / 300L).toInt()
            if (findCurrentLyricLine(l, progress) != expected) mismatches++
        }

        assertEquals("binary search must match the arithmetic answer", 0, mismatches)
        // ⛔ 这里**故意没有**性能断言（原先是 `assertTrue(elapsedMs < 100)`）：
        // 墙钟门槛测的是 runner 的 CPU，不是我们的代码——2 核 runner 并行跑 2000+ 例时
        // 它纯属运气，会让 CI 随机变红而代码一行没动（本项目已吃过一次，机制见
        // MetingApiService 的 presetEndpoints 接缝 KDoc）。二分查找的复杂度由上面的
        // 正确性断言 + 代码本身保证；要盯性能请放到 instrumented benchmark / profiling。
    }
}
