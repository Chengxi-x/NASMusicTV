package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * §八 G8 — E24 心搏波形表形态学门禁（§A8 第 0 条 · P0）。
 *
 * **正向**：直接引用生产表 [EcgWaveRenderer.HB]（51 点，一列一采样、索引 = 整数列龄），
 * 断言临床时程与形态（等电位平线 / R 峰整列满幅 / S 谷深度 / T 波单峰且慢升快降 / P 波圆钝）。
 * ⛔ 不许在测试里复制表 —— 那门禁就盯不住生产代码了（§A8 明写）。
 *
 * **负向自证**：把改造前的旧实现（14 点 `OLD_T`/`OLD_V` + 线性插值）按**同一显示网格**
 * 重采样，喂进**与正向完全相同**的谓词函数，必须**不通过** —— 否则门禁是空转（§八 开头规矩）。
 * 每条负向用例都先断言「旧表确实具备让它挂掉的那个性质」（**前提自证**），
 * 这样即便有人日后改了谓词，自证也不会悄悄退化成空断言。
 *
 * ⛔ **宽度比的口径（两处必须统一，踩过坑）**：
 * - **各波自身峰值的 4%**，⛔ 不是全表峰值：QRS 的远场列（S 谷、Q 谷的绝对值远小于 R）
 *   若按全表阈值算会整片落在带内、把 QRS 撑长、比值塌掉。
 * - 只数峰周围的**连续**超门限列，⛔ 不是区间内所有超门限列：后者会把别的波渗进来的
 *   尾巴算成波宽（本仓库旧表就有个长 P 波尾巴，T 峰 4% 门限下会被算进 QRS 窗口）。
 * - 窗口 `[lo, hi]` 由调用方显式传入（本表 QRS = `[14, 22]`、T = `[26, 50]`），
 *   谓词本身不猜哪个波在哪。
 *
 * ⛔ 地标常量（`R_PEAK_COL` 等）只是**断言锚点**，数值真源是
 * `docs/archive/verification/scripts/ecg_waveform_gen.py`（§E44：数字只能由脚本再生）。
 * 两者不一致时以脚本为准，改脚本 ⇒ 重跑 ⇒ 同步本文件的常量与谓词。
 *
 * 全部断言用 `assertTrue/assertFalse/assertEquals`（Kotlin 的 `assert` 在测试 JVM 是空操作）；
 * 避开已弃用的 `FloatArray.max()/min()`（Kotlin 1.7 起弃用）⇒ 全部显式循环。
 */
class EcgWaveformTest {

    private val HB = EcgWaveRenderer.HB

    // ── 临床地标锚点（列；ms = 列 × 1000/90，与生成脚本同源）──
    private val colMs = 1000f / EcgWaveRenderer.SPEED_COLS_PER_SEC

    // ═══════════════════ 正向：生产表形态学 ═══════════════════

    /** ① 表长 51 列，且必须短于最短心搏间隔（防「改 speed 忘重建表」） */
    @Test
    fun `① 表长 51 列且短于最短心搏间隔`() {
        assertEquals(COL_COUNT, HB.size)
        // 列级判据（与 speed 无关，最精确）：心搏占列数必须 < 两次心搏的最短间隔
        assertTrue(
            "HB.size(${HB.size}) 必须 < MIN_GAP_COLS(${EcgWaveRenderer.MIN_GAP_COLS})，否则相邻心搏粘连",
            HB.size < EcgWaveRenderer.MIN_GAP_COLS
        )
        // 时间级判据（与 speed 绑定）：同一件事换算成 ms，改 speed 时这条会先炸
        val durationMs = HB.size / EcgWaveRenderer.SPEED_COLS_PER_SEC * 1000f
        val gapMs = EcgWaveRenderer.MIN_GAP_COLS / EcgWaveRenderer.SPEED_COLS_PER_SEC * 1000f
        assertTrue(
            "心搏时长 ${durationMs}ms 必须 < 最短间隔 ${gapMs}ms",
            durationMs < gapMs
        )
        assertEquals("$COL_COUNT 列 @ 90 列/秒 = 566.7ms", 566.7f, durationMs, 0.1f)
    }

    /** ② R 峰精确落在第 18 显示列上、峰值恰 1.0（采样削平是「不像心电图」的头号来源） */
    @Test
    fun `② R 峰落在显示列 18 上且恰为满幅`() {
        assertTrue("R 峰列号判据必须对生产表通过", rPeakAtColOk(HB))
        assertTrue("R 峰满幅判据必须对生产表通过", rPeakFullAmpOk(HB))
        assertTrue("合并判据必须对生产表通过", rPeakOk(HB))
        assertEquals("R 峰应落在列 18", R_PEAK_COL, argMaxRange(HB, 0, HB.size - 1))
        assertEquals(1.0f, HB[R_PEAK_COL], 1e-6f)
        // 派生时程：R 峰时 44.4 ms，占 QRS 88.9 ms 的 47%（AHA：左胸导联 ≤45%）
        val rPeakMs = R_PEAK_COL * colMs
        val qrsMs = (J_COL - QRS_ONSET_COL) * colMs
        assertEquals("QRS 宽应为 88.9ms", 88.9f, qrsMs, 0.1f)
        assertEquals("R 峰时应为 200.0ms", 200.0f, rPeakMs, 0.1f)
        assertEquals("PR 间期应为 155.6ms", 155.6f, QRS_ONSET_COL * colMs, 0.1f)
        assertEquals("QT 应为 400.0ms", 400.0f, (T_END_COL - QRS_ONSET_COL) * colMs, 0.1f)
        assertEquals("J→T 峰应为 200.0ms", 200.0f, (T_APEX_COL - J_COL) * colMs, 0.1f)
    }

    /** ③ S 谷 = 全表最低点、落在列 21、深度 0.10–0.25×R（旧表 −0.28 过深） */
    @Test
    fun `③ S 谷为全表最低点且深度合规`() {
        val rPeak = peakOf(HB)
        assertTrue("S 谷列号判据必须对生产表通过", sNadirAtColOk(HB))
        assertTrue("S 谷深度判据必须对生产表通过", sDepthOk(HB))
        assertTrue("合并判据必须对生产表通过", sNadirOk(HB))
        assertEquals("S 谷应落在列 21", S_NADIR_COL, argMinRange(HB, 0, HB.size - 1))
        assertEquals(-0.17f, minOfArray(HB), 1e-6f)
        assertEquals(-0.17f, HB[S_NADIR_COL], 1e-6f)
        assertEquals(-0.15f, HB[Q_NADIR_COL], 1e-6f)  // Q 谷 −0.15（窄而锐，≤0.25）
        assertEquals("S 深度/R 应 ≈0.17", 0.17f, abs(minOfArray(HB)) / rPeak, 1e-6f)
    }

    /** ④ Q 谷窄而尖：半深以下的列 ≤ 2（临床 Q 宽 <40 ms） */
    @Test
    fun `④ Q 谷窄于 2 列`() {
        assertTrue("Q 谷宽度判据必须对生产表通过", qNadirNarrowOk(HB))
        val qIdx = argMinRange(HB, 0, R_PEAK_COL)
        assertEquals("Q 谷应落在列 16", Q_NADIR_COL, qIdx)
        assertEquals("Q 谷半深以下的列数应 ≤2", 1, qNadirHalfDepthCols(HB))
    }

    /** ⑤ P 波是圆钝小丘（幅度 0.07–0.15×R、峰顶只占 1 列），不是三角尖也不是高丘 */
    @Test
    fun `⑤ P 波幅度合规且峰顶只占单列`() {
        assertTrue("P 波判据必须对生产表通过", pWaveOk(HB))
        val pIdx = argMaxRange(HB, 0, Q_NADIR_COL)
        assertEquals("P 峰应落在列 4", 4, pIdx)
        assertEquals("P 幅度应为 0.10×R", 0.10f, HB[pIdx], 1e-6f)
        // 单列峰顶：两侧必须严格更低（旧表是跨列平台，尖峰感来自这个差别）
        assertTrue("P 峰左侧应更低：HB[${pIdx - 1}]=${HB[pIdx - 1]} < HB[$pIdx]", HB[pIdx - 1] < HB[pIdx])
        assertTrue("P 峰右侧应更低：HB[${pIdx + 1}]=${HB[pIdx + 1]} < HB[$pIdx]", HB[pIdx + 1] < HB[pIdx])
    }

    /** ⑥ PR 段 [8..13] 与 ST 段 [22..25] 严格等电位（逐列必须精确 0，不是「很小」） */
    @Test
    fun `⑥ PR 段与 ST 段严格等电位`() {
        assertTrue("等电位判据必须对生产表通过", isoelectricOk(HB))
        // 比 1e-3 更严：生成脚本在这两段直接写 +0.000000f，任何斜坡/残留都会被抓住
        for (i in PR_LO..PR_HI) {
            assertEquals("PR 段 HB[$i] 必须精确为 0", 0f, HB[i], 0f)
        }
        for (i in ST_LO..ST_HI) {
            assertEquals("ST 段 HB[$i] 必须精确为 0", 0f, HB[i], 0f)
        }
    }

    /** ⑦ T 波是单峰圆丘：上升支单调不减、下降支单调不增；峰在列 40 */
    @Test
    fun `⑦ T 波为单峰圆丘`() {
        assertTrue("T 单峰判据必须对生产表通过", tSingleMoundOk(HB))
        val l = tLands(HB)
        assertEquals("T 起应落在列 27（S 谷后首个正值列）", 27, l.onset)
        assertEquals("T 峰应落在列 40", T_APEX_COL, l.apex)
        assertEquals("T 终应落在列 50", T_END_COL, l.end)
        assertEquals("T 幅度应为 0.25×R", 0.25f, HB[T_APEX_COL], 1e-6f)
    }

    /**
     * ⑧ **T 必须慢升快降**（本轮重写新增的关键判据）。
     * 临床：ecg.utah「slow ascending ramp and faster descending ramp」，
     * 本表升 155.6 ms / 降 111.2 ms = 1.40 : 1 ⇒ 升支段数必须明显多于降支段数。
     * 旧表是 **7 : 10（快升慢降）** —— 这正是「看着不像心电图」的主因。
     */
    @Test
    fun `⑧ T 波慢升快降`() {
        assertTrue("T 不对称判据必须对生产表通过", tAsymmetryOk(HB))
        val (up, down) = tRiseFallSteps(HB)
        assertEquals("T 升支应 13 段（列 27..39 逐段严格上升）", 13, up)
        assertEquals("T 降支应 10 段（列 40..49 逐段严格下降）", 10, down)
        assertTrue("升支 $up 段 / 降支 $down 段 = ${up.toFloat() / down} 必须 > 1.2", up.toFloat() / down > 1.2f)
        // 地标跨度复核：列 26→40 升、40→50 降（14 : 10 列 = 155.6ms : 111.2ms）
        assertEquals(14, T_APEX_COL - T_ONSET_COL)
        assertEquals(10, T_END_COL - T_APEX_COL)
    }

    /** ⑨ T/QRS 有效宽度比落在 2.0–4.0（临床 T 266.7ms ÷ QRS 88.9ms ≈ 3.0） */
    @Test
    fun `⑨ T 波有效宽度比 QRS 大 2 到 4 倍`() {
        val ratio = tOverQrsWidthRatio(HB)
        assertTrue("T/QRS 宽度比 $ratio 必须落在 2.0..4.0", tOverQrsWidthOk(HB))
        assertEquals("QRS 有效支撑应为 7 列", 7, supportBand(HB, QRS_LO, QRS_HI))
        assertEquals("T 有效支撑应为 21 列", 21, supportBand(HB, T_LO, T_HI))
        assertEquals("比值应为 21/7 = 3.0", 3.0f, ratio, 1e-6f)
    }

    /** ⑩ QRS 升快降慢：R 峰落在 QRS 中点，上升支比下降支短 */
    @Test
    fun `⑩ QRS 升快降慢`() {
        val qIdx = argMinRange(HB, 0, R_PEAK_COL)
        val sIdx = argMinRange(HB, R_PEAK_COL, HB.size - 1)
        assertEquals("Q 谷应落在列 16", Q_NADIR_COL, qIdx)
        assertEquals("R 峰应落在列 18", R_PEAK_COL, argMaxRange(HB, 0, HB.size - 1))
        assertEquals("S 谷应落在列 21", S_NADIR_COL, sIdx)
        assertTrue(
            "升支 ${R_PEAK_COL - qIdx} 列 必须 < 降支 ${sIdx - R_PEAK_COL} 列（升快降慢）",
            (R_PEAK_COL - qIdx) < (sIdx - R_PEAK_COL)
        )
    }

    /** ⑪ 查表边界：负列龄与越界一律返回 0（无假信号泄漏） */
    @Test
    fun `⑪ 查表越界返回 0`() {
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(0), 1e-6f)
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(-1), 1e-6f)
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(HB.size), 1e-6f)
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(HB.size + 1), 1e-6f)
        // 哨兵列龄（NO_BEAT 量级）查表自然得 0 ⇒ 开播瞬间不漏 HB[1]=0.0146 这种假信号
        assertEquals(0f, EcgWaveRenderer.heartbeatAt(EcgWaveRenderer.NO_BEAT), 1e-6f)
    }

    /** ⑫ 查表严格等于生产表 —— 「一列一采样、无插值、无平滑」这一契约本身 */
    @Test
    fun `⑫ 查表严格等于生产表无插值`() {
        for (i in HB.indices) {
            assertEquals(
                "heartbeatAt($i) 必须严格等于 HB[$i]（任何插值/平滑都会被抓住）",
                HB[i], EcgWaveRenderer.heartbeatAt(i), 0f
            )
        }
    }

    // ═══════════════════ 负向自证：旧表必须挂 ═══════════════════
    // ⛔ 每个负向用例都复用正向用例的**同一个**谓词函数，否则自证无效。

    /** 负向 ②：旧表 R 峰真值在 0.214s = 列 19.26 ⇒ 采样峰落在列 19 且被削到 0.897 → 两条子判据都挂 */
    @Test
    fun `负向② 旧表 R 峰既不在列 18 也被削平`() {
        val old = oldTableResampledToCols()
        // 前提自证：旧表的采样峰确实落在列 19、且取值只有 0.897（真值 1.0 落在两列之间）
        assertEquals("旧表采样峰应落在列 19", 19, argMaxRange(old, 0, old.size - 1))
        assertTrue("数值复核：旧表列 19 应 ≈0.897，实测 ${old[19]}", old[19] in 0.86f..0.93f)
        assertTrue(
            "前提自证：旧表真的没把 R 峰落在列 18（old[18]=${old[18]}，old[19]=${old[19]}）",
            old[R_PEAK_COL] < old[19]
        )
        assertFalse("R 峰列号判据对旧表必须不通过", rPeakAtColOk(old))
        assertFalse("R 峰满幅判据对旧表必须不通过（旧采样削到 ${old[19]}）", rPeakFullAmpOk(old))
        assertFalse("合并判据对旧表必须不通过", rPeakOk(old))
    }

    /** 负向 ③：旧表 S 谷在列 24、深度 0.32×R ⇒ 列号与深度双双越界 */
    @Test
    fun `负向③ 旧表 S 谷过深`() {
        val old = oldTableResampledToCols()
        val rPeak = peakOf(old)
        val depth = abs(minOfArray(old)) / rPeak
        // 前提自证：旧表 S 谷真值 −0.30 在 0.268s = 列 24.1 ⇒ 采样谷落在列 24、深度 ≈0.32×R
        assertEquals("旧表谷底应落在列 24", 24, argMinRange(old, 0, old.size - 1))
        assertTrue("数值复核：旧表谷底 ≈−0.289，实测 ${minOfArray(old)}", minOfArray(old) in -0.30f..-0.28f)
        assertTrue("前提自证：旧表 S 深度 ${"%.3f".format(depth)}×R 超过 0.25 上限", depth > 0.25f)
        assertFalse("S 谷列号判据对旧表必须不通过", sNadirAtColOk(old))
        assertFalse("S 深度判据对旧表必须不通过", sDepthOk(old))
        assertFalse("合并判据对旧表必须不通过", sNadirOk(old))
    }

    /** 负向 ④：旧表 PR/ST 段不是等电位平线（P 波长斜坡 + S 谷拖尾）→ 判失败 */
    @Test
    fun `负向④ 旧表必须挂等电位判据`() {
        val old = oldTableResampledToCols()
        // 前提自证：旧表在这两段确实有非零采样（P 波斜坡 / S 谷拖尾），否则自证失去意义
        val prNotFlat = (PR_LO..PR_HI).any { abs(old[it]) > 1e-3f }
        val stNotFlat = (ST_LO..ST_HI).any { abs(old[it]) > 1e-3f }
        assertTrue("旧表 PR/ST 段应有非零采样（P 波斜坡 / S 谷拖尾）", prNotFlat)
        assertTrue("旧表 ST 段应有非零采样（S 谷拖尾）", stNotFlat)
        assertFalse("等电位判据对旧表必须不通过（否则门禁空转）", isoelectricOk(old))
    }

    /** 负向 ⑧：旧表 T 是**快升慢降**（7 段升 / 10 段降 = 0.7）→ 不对称判据失败 */
    @Test
    fun `负向⑧ 旧表 T 波升降倒置`() {
        val old = oldTableResampledToCols()
        val l = tLands(old)
        val (up, down) = tRiseFallSteps(old)
        // 前提自证：旧表 T 峰真值 0.240 在 0.392s = 列 35.3，起于 0.298s 后（列 28）、终于 0.5s（列 45）
        assertEquals("旧表 T 起应落在列 28", 28, l.onset)
        assertEquals("旧表 T 峰应落在列 35", 35, l.apex)
        assertEquals("旧表 T 终应落在列 45", 45, l.end)
        assertTrue("前提自证：旧表确实快升慢降（升 $up 段 / 降 $down 段）", down > up)
        assertTrue("数值复核：旧表升:降 = ${up.toFloat() / down}", up.toFloat() / down < 1.2f)
        assertFalse("T 不对称判据对旧表必须不通过（否则门禁空转）", tAsymmetryOk(old))
    }

    /**
     * 负向 ⑨：旧表 T/QRS 有效宽度比只有 **16 / 9 = 1.78 < 2.0** → 宽度比判据失败。
     *
     * ⚠️ 这里刻意用**新表的列区间**（QRS `[14,22]`、T `[26,50]`）去量旧表：
     * 旧表的 QRS 真值落在列 19–26、T 落在列 28–45，按新区间量时**区间边缘反而帮了旧表**
     * （把旧 QRS 的远端列裁掉，只剩 9 列）。即便在这个最宽松的口径下旧表仍然 < 2.0，
     * 说明「T 不够宽」是旧表的固有形态问题，而不是量法挑出来的。
     */
    @Test
    fun `负向⑨ 旧表 T 相对 QRS 不够宽`() {
        val old = oldTableResampledToCols()
        val qrsBand = supportBand(old, QRS_LO, QRS_HI)
        val tBand = supportBand(old, T_LO, T_HI)
        val ratio = tBand.toFloat() / qrsBand
        // 前提自证：旧表 T 支撑带确实是连续的一段（列 29..44），且比 QRS 带宽不到 2 倍
        assertEquals("旧表 QRS 有效支撑应为 9 列", 9, qrsBand)
        assertEquals("旧表 T 有效支撑应为 16 列", 16, tBand)
        assertTrue("数值复核：旧表比值 ${"%.2f".format(ratio)} 必须 < 2.0", ratio < 2.0f)
        assertFalse("T/QRS 宽度比判据对旧表必须不通过（否则门禁空转）", tOverQrsWidthOk(old))
    }

    /** 负向 ⑫：旧实现确实在做线性插值 → 「严格等于表值」判据必然不通过 */
    @Test
    fun `负向⑫ 旧表必须挂无插值判据`() {
        val old = oldTableResampledToCols()
        val offTable = old.count { v -> OLD_V.none { abs(it - v) <= 1e-6f } }
        assertTrue("旧实现应产出插值值（实测 $offTable 个非表值）", offTable > 0)
        assertFalse("「严格等于表值」判据对旧表必须不通过", allValuesInTable(old, OLD_V))
    }

    // ═══════════════════ 谓词（正向 / 负向共用）═══════════════════

    /** R 峰落在指定列 */
    private fun rPeakAtColOk(t: FloatArray): Boolean =
        argMaxRange(t, 0, t.size - 1) == R_PEAK_COL

    /** 指定列取到满幅 1.0（未被削平） */
    private fun rPeakFullAmpOk(t: FloatArray): Boolean = abs(t[R_PEAK_COL] - 1.0f) <= 1e-6f

    private fun rPeakOk(t: FloatArray): Boolean = rPeakAtColOk(t) && rPeakFullAmpOk(t)

    /** S 谷落在指定列（且必然是全表最低点） */
    private fun sNadirAtColOk(t: FloatArray): Boolean =
        argMinRange(t, 0, t.size - 1) == S_NADIR_COL

    /** S 深度 0.10–0.25 × R（临床 −0.10~−0.25；旧表 −0.28~−0.32 过深） */
    private fun sDepthOk(t: FloatArray): Boolean {
        val depth = abs(minOfArray(t)) / peakOf(t)
        return depth in S_DEPTH_MIN..S_DEPTH_MAX
    }

    private fun sNadirOk(t: FloatArray): Boolean = sNadirAtColOk(t) && sDepthOk(t)

    /** 等电位：PR 段 [P 终 .. QRS 起-1] 与 ST 段 [J 点 .. T 起-1] 全为 0 */
    private fun isoelectricOk(t: FloatArray): Boolean {
        for (i in PR_LO..PR_HI) if (abs(t[i]) > 1e-3f) return false
        for (i in ST_LO..ST_HI) if (abs(t[i]) > 1e-3f) return false
        return true
    }

    /** Q 谷宽度：半深（`t[q] * 0.5`）以下的列数，⛔ 不要写成 `-t[q] * 0.5`（t[q] 本身为负，取负号会把判据翻成正阈值） */
    private fun qNadirHalfDepthCols(t: FloatArray): Int {
        val qIdx = argMinRange(t, 0, R_PEAK_COL)
        val half = t[qIdx] * 0.5f
        var n = 0
        for (i in qIdx until R_PEAK_COL) if (t[i] < half) n++
        return n
    }

    /** Q 谷窄而锐：半深以下 ≤ 2 列（临床 Q 宽 <40 ms） */
    private fun qNadirNarrowOk(t: FloatArray): Boolean = qNadirHalfDepthCols(t) <= 2

    /** P 波：幅度 0.07–0.15 × R，且峰顶**只占 1 列**（两侧严格更低） */
    private fun pWaveOk(t: FloatArray): Boolean {
        val pIdx = argMaxRange(t, 0, Q_NADIR_COL)
        if (pIdx <= 0 || pIdx >= t.size - 1) return false
        val amp = t[pIdx] / peakOf(t)
        return amp in P_AMP_MIN..P_AMP_MAX &&
            t[pIdx - 1] < t[pIdx] && t[pIdx + 1] < t[pIdx]
    }

    /** T 波地标：起 = S 谷之后首个正值列；峰 = S 谷之后的最大值列；终 = 峰之后首个非正列 */
    private class TLands(val onset: Int, val apex: Int, val end: Int)

    private fun tLands(t: FloatArray): TLands {
        val s = argMinRange(t, 0, t.size - 1)
        var apex = s + 1
        for (i in s + 1 until t.size) if (t[i] > t[apex]) apex = i
        var onset = s + 1
        while (onset < apex && t[onset] <= 0f) onset++
        var end = apex + 1
        while (end < t.size && t[end] > 0f) end++
        return TLands(onset, apex, end)
    }

    /** T 单峰圆丘：起→峰单调不减，峰→终单调不增 */
    private fun tSingleMoundOk(t: FloatArray): Boolean {
        val l = tLands(t)
        if (l.onset > l.apex || l.apex > l.end) return false
        for (i in l.onset until l.apex) if (t[i] > t[i + 1] + 1e-6f) return false
        for (i in l.apex until l.end) if (t[i] < t[i + 1] - 1e-6f) return false
        return true
    }

    /** T 升支/降支的严格单调步数（与生成脚本同口径：本表 13:10 = 1.3，旧表 7:10 = 0.7） */
    private fun tRiseFallSteps(t: FloatArray): Pair<Int, Int> {
        val l = tLands(t)
        var up = 0
        for (i in l.onset until l.apex) if (t[i] > t[i - 1]) up++
        var down = 0
        for (i in l.apex until l.end) if (t[i + 1] < t[i]) down++
        return up to down
    }

    /** T 不对称：慢升快降 ⇒ 升支步数 / 降支步数 > 1.2 */
    private fun tAsymmetryOk(t: FloatArray): Boolean {
        val (up, down) = tRiseFallSteps(t)
        return up > 0 && down > 0 && up.toFloat() / down > T_ASYM_MIN
    }

    /**
     * 有效支撑宽（列）：在 `[lo, hi]` 区间内，以**本区间自身峰值**（按 |v| 取）的
     * [SUPPORT_REL] 为门限，量**峰周围连续**超门限的列数。
     *
     * ⛔ 门限取自身峰值、不取全表峰值：QRS 窗口里的远场列（S 谷 −0.17 之类）若按全表
     * 阈值算会整片计入，把 QRS 宽度撑长、T/QRS 比值压塌。
     * ⛔ 只数连续段：区间里零散超门限的列是别的波渗进来的尾巴，不是本波宽度。
     */
    private fun supportBand(t: FloatArray, lo: Int, hi: Int): Int {
        val hiC = minOf(hi, t.size - 1)
        var peak = lo
        for (i in lo..hiC) if (abs(t[i]) > abs(t[peak])) peak = i
        val thr = abs(t[peak]) * SUPPORT_REL
        if (thr <= 0f) return 0
        var loB = peak
        while (loB > lo && abs(t[loB - 1]) > thr) loB--
        var hiB = peak
        while (hiB < hiC && abs(t[hiB + 1]) > thr) hiB++
        return hiB - loB + 1
    }

    /** T/QRS 有效宽度比（窗口由调用方给定 ⇒ 谓词不猜哪个波在哪） */
    private fun tOverQrsWidthRatio(t: FloatArray): Float {
        val qrs = supportBand(t, QRS_LO, QRS_HI)
        return if (qrs == 0) 0f else supportBand(t, T_LO, T_HI).toFloat() / qrs
    }

    /** 落在 2.0–4.0：临床 T 266.7ms ÷ QRS 88.9ms ≈ 3.0，不是设计意图 */
    private fun tOverQrsWidthOk(t: FloatArray): Boolean {
        val r = tOverQrsWidthRatio(t)
        return r >= T_OVER_QRS_MIN && r <= T_OVER_QRS_MAX
    }

    /** t 的每个值都能在 table 里找到（⇒ 没有插值/平滑） */
    private fun allValuesInTable(t: FloatArray, table: FloatArray): Boolean =
        t.all { v -> table.any { abs(it - v) <= 1e-6f } }

    // ── 显式循环（避开已弃用的 `FloatArray.max()/min()`）──

    private fun peakOf(t: FloatArray): Float {
        var m = t[0]
        for (v in t) if (v > m) m = v
        return m
    }

    private fun minOfArray(t: FloatArray): Float {
        var m = t[0]
        for (v in t) if (v < m) m = v
        return m
    }

    private fun argMaxRange(t: FloatArray, a: Int, b: Int): Int {
        var k = a
        for (i in a..b) if (t[i] > t[k]) k = i
        return k
    }

    private fun argMinRange(t: FloatArray, a: Int, b: Int): Int {
        var k = a
        for (i in a..b) if (t[i] < t[k]) k = i
        return k
    }

    // ═══════════════════ 旧实现（照抄改造前，仅供负向自证）═══════════════════

    private val OLD_T = floatArrayOf(
        0.000f, 0.048f, 0.090f, 0.132f, 0.164f, 0.186f,
        0.214f, 0.238f, 0.268f, 0.298f, 0.330f, 0.392f, 0.452f, 0.500f
    )
    private val OLD_V = floatArrayOf(
        0.000f, 0.000f, 0.130f, 0.000f, -0.120f, 0.000f,
        1.000f, -0.060f, -0.300f, -0.040f, 0.080f, 0.240f, 0.060f, 0.000f
    )

    /** 旧 `heartbeatAt`：线性扫描 + 分段线性插值（入参为秒） */
    private fun oldHeartbeatAt(ageSec: Float): Float {
        if (ageSec <= 0f) return 0f
        val last = OLD_T.size - 1
        if (ageSec >= OLD_T[last]) return 0f
        var i = 1
        while (i < last && OLD_T[i] < ageSec) i++
        val t0 = OLD_T[i - 1]
        val t1 = OLD_T[i]
        val v0 = OLD_V[i - 1]
        val v1 = OLD_V[i]
        return v0 + (v1 - v0) * ((ageSec - t0) / (t1 - t0))
    }

    /**
     * 把旧表按**同一显示网格**（1 列 = 1000/90 ms、长度 = [HB.size]）重采样成「一列一个采样」，
     * 这样正负两套表才能喂进同一批谓词。
     */
    private fun oldTableResampledToCols(): FloatArray {
        val out = FloatArray(HB.size)
        for (c in out.indices) out[c] = oldHeartbeatAt(c / EcgWaveRenderer.SPEED_COLS_PER_SEC)
        return out
    }

    // ═══════════════════ 门禁常量（与生成脚本同源）═══════════════════

    private companion object {
        const val COL_COUNT = 51          // P 起 0 → T 终 50 = 566.7 ms
        const val P_END_COL = 8
        const val QRS_ONSET_COL = 14      // PR 间期起点
        const val Q_NADIR_COL = 16
        const val R_PEAK_COL = 18
        const val S_NADIR_COL = 21
        const val J_COL = 22              // J 点 / QRS 终
        const val T_ONSET_COL = 26
        const val T_APEX_COL = 40
        const val T_END_COL = 50

        /** 支撑窗口：QRS 14..22、T 26..50（临床列区间，谓词不猜） */
        const val QRS_LO = QRS_ONSET_COL
        const val QRS_HI = J_COL
        const val T_LO = T_ONSET_COL
        const val T_HI = T_END_COL

        /** 等电位段：[P 终 .. QRS 起-1]、[J 点 .. T 起-1] */
        const val PR_LO = P_END_COL
        const val PR_HI = QRS_ONSET_COL - 1
        const val ST_LO = J_COL
        const val ST_HI = T_ONSET_COL - 1

        const val SUPPORT_REL = 0.04f    // 有效支撑门限 = 各自峰值的 4%
        const val T_OVER_QRS_MIN = 2.0f  // 临床 T/QRS ≈ 3.0；下限卡「T 必须是宽圆丘」
        const val T_OVER_QRS_MAX = 4.0f  // 上限防 T 摊成一段长弧
        const val T_ASYM_MIN = 1.2f      // 慢升快降（临床 1.40）
        const val S_DEPTH_MIN = 0.10f    // S 深度 / R
        const val S_DEPTH_MAX = 0.25f
        const val P_AMP_MIN = 0.07f      // P 幅度 / R
        const val P_AMP_MAX = 0.15f
    }
}