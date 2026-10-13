package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁 **G4**（§十）：E46「迷雾之战」的提交 / 填充 / native 堆三张预算表。
 *
 * ## 每条断言都配一条**负向自证**（「喂破实现 ⇒ 必须失败」）
 * 只断言通过、不证明它能失败的门禁等于没有门禁（E43/E44 两个 OpBudgetTest 同一条纪律）。
 *
 * ## ⭐ 本表的两个口径（⛔ 不要混）
 * | 口径 | 数 | 用途 |
 * |---|---|---|
 * | **逐类最坏帧**（[TripodOpItem] 各行） | 44 / 73 / 121 图元，4.845 / 6.551 / 6.559 屏 | 表的本体，`estimate` / `overdrawEstimate` 的来源 |
 * | **单帧最坏**（`MEASURED_*`） | 36 / 56 / 104 图元，2.958 / 5.412 / 5.414 屏 | 探针 `profAt(t)` 的单帧原样，"表 ≥ 实测"的锚 |
 *
 * 两个口径差 21% ~ 64%，成因是**逐类最坏帧互不同刻**（腿最大时机器在 12 m 内、
 * 建筑最大时在甩镜段、雾带最大时仰角最大）⇒ 逐类上界之和必然高出任何单帧。
 * 这不是记错，`G4 表是实测的上界` 与 `负向自证 旧上限…` 两条断言守的就是这个分工。
 *
 * ## ⚠️ 本表没有 `legacy` 列 ⇒ 负向自证靠**显式注入**
 * E43 有"改造前形态"可切；E46 ⛔ 没有改造前 —— 表是从定稿原型实测出来的。
 * 所以破实现注入全部走显式常数与减法：火星按**默认**条数（而非滑杆量程上限）落表、
 * 建筑按**名义**栋数落表、走基类暗角（+1.000 屏）、`FILL_REF_HIGH` 照抄 E44 的
 * "REF = 实测最坏"口径、以及 E44 的旧上限 `3.45 / 4.00` 对本表的判别。
 */
class TripodOpBudgetTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  契约自检
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `契约常量等于 §十 定稿值`() {
        // ops 三值与 E43/E44 同值（设备级提交预算，⛔ 不为单效果调）
        assertEquals(90, TripodOpBudget.OPS_MAX_LOW)
        assertEquals(200, TripodOpBudget.OPS_MAX_MEDIUM)
        assertEquals(320, TripodOpBudget.OPS_MAX_HIGH)
        // ⭐ 2026-10-13 K2 定稿：本效果**不能**沿用 E44 的 3.45 / 4.00（见各常量 KDoc）
        assertEquals(5.00f, TripodOpBudget.OVERDRAW_MAX_LOW, 1e-6f)
        assertEquals(6.75f, TripodOpBudget.OVERDRAW_MAX_MEDIUM, 1e-6f)
        assertTrue(
            "HIGH 的绝对上限必须是无穷（照 E44 所有者裁决口径）",
            TripodOpBudget.overdrawMax(TripodLevel.HIGH).isInfinite()
        )
        assertEquals(6.00f, TripodOpBudget.FILL_REF_HIGH, 1e-6f)
        assertEquals(1.10f, TripodOpBudget.FILL_RATCHET, 1e-6f)
        assertEquals(3_000_000, TripodOpBudget.NATIVE_PX_MAX)
        assertEquals(1920, TripodOpBudget.NATIVE_PX_OK_MAX_LONG_EDGE)
        assertEquals(2560, TripodOpBudget.NATIVE_PX_BREAK_LONG_EDGE)
        // 单帧最坏锚（e46_fog_worstframe.js --frames 1800 --variants，跨三套剧本）
        assertEquals(36, TripodOpBudget.MEASURED_OPS_LOW)
        assertEquals(56, TripodOpBudget.MEASURED_OPS_MEDIUM)
        assertEquals(104, TripodOpBudget.MEASURED_OPS_HIGH)
        assertEquals(2.958f, TripodOpBudget.MEASURED_FILL_LOW, 1e-6f)
        assertEquals(5.412f, TripodOpBudget.MEASURED_FILL_MEDIUM, 1e-6f)
        assertEquals(5.414f, TripodOpBudget.MEASURED_FILL_HIGH, 1e-6f)
    }

    /**
     * ⭐ 「HIGH 不设上限」在代码里的**形态**：[TripodOpBudget.overdrawMax] 在 HIGH 返回无穷，
     * 判据搬到 [TripodOpBudget.fillRatchetMax]（棘轮）+ 单调方向断言。
     *
     * 本测试守的是**判据存在性** —— 若哪天有人把 `OVERDRAW_MAX_HIGH` 写成一个很大的有限数
     * （"用大数字冒充无上限"），棘轮就变成装饰，门静默失效。
     */
    @Test
    fun `HIGH 的门是棘轮而不是绝对上限`() {
        assertTrue(
            "HIGH 的绝对上限必须是无穷",
            TripodOpBudget.overdrawMax(TripodLevel.HIGH).isInfinite()
        )
        val ratchet = TripodOpBudget.fillRatchetMax()
        assertTrue("棘轮必须是有限数，否则 HIGH 根本没有判据", ratchet.isFinite())
        // 可满足：表值必须在棘轮内
        assertTrue("棘轮 $ratchet 必须容得下表值 ${overdraw(TripodLevel.HIGH)}",
            ratchet >= overdraw(TripodLevel.HIGH))
        // 有判别力：多铺一次全屏 pass（+1.00 屏）必须撞破棘轮
        assertTrue(
            "棘轮必须紧到『多一次全屏 pass 就破门』：$ratchet vs ${overdraw(TripodLevel.HIGH) + 1.0}",
            ratchet < overdraw(TripodLevel.HIGH) + 1.0
        )
        // ⛔ 绝对上限的**排序**不能反过来（E44 旧表 `MAX_HIGH 3.50 < MAX_MED 3.60` 的符号错）
        assertTrue(TripodOpBudget.OVERDRAW_MAX_LOW < TripodOpBudget.OVERDRAW_MAX_MEDIUM)
    }

    @Test
    fun `逐元素清单覆盖 §十 表的 25 项`() {
        val ids = TripodOpItem.entries.map { it.name }.toSet()
        val required = listOf(
            // §十 逐类别表的 23 类（与原型 op() 的 tag 一一对应）
            "FOG_STRIP", "SKY_BASE", "GRADE", "BUILDINGS", "LEGS", "GROUND_BAND", "BEAM_COLUMN",
            "BUILDING_SCAN", "LEG_RIM", "SHELL", "BEAM_POOL", "WAKE_RIDGE", "IMPACT_RING",
            "GLAND", "BACK_HILITE", "WAKE_CHANNEL", "EMBER", "COWL", "FOOT_SHADOW", "TENDRIL",
            "GRAB_TENDRIL", "CROWD", "GRAB_HOOK",
            // §十一 第 4 行要求比 §十 多的两行
            "POST_FX", "DAMAGE_COALESCER"
        )
        for (r in required) assertTrue("缺元素：$r", ids.contains(r))
        assertEquals(required.size, TripodOpItem.entries.size)
    }

    @Test
    fun `逐元素系数全部非负`() {
        for (it in TripodOpItem.entries) {
            assertTrue("${it.name} opsLow<0", it.opsLow >= 0)
            assertTrue("${it.name} opsMed<0", it.opsMed >= 0)
            assertTrue("${it.name} opsHigh<0", it.opsHigh >= 0)
            assertTrue("${it.name} fillLow<0", it.fillLow >= 0.0)
            assertTrue("${it.name} fillMed<0", it.fillMed >= 0.0)
            assertTrue("${it.name} fillHigh<0", it.fillHigh >= 0.0)
        }
    }

    /**
     * 防「漏画被当成省预算」。两张豁免名单**刻意不同**，且 LOW 的 12 项零是**逐项列名**的
     * （⛔ 不写"除……之外都为正"那种循环豁免）—— 列名本身就是 §十 LOW 砍层清单的机器化，
     * 任何人往 LOW 加一层或从 LOW 撤一层，都会在这里显形。
     */
    @Test
    fun `每档都要真的画东西`() {
        // HIGH 只豁免两个"结构性零"：本效果不走基类后处理 + 洼地合成是 LOW 专属
        for (it in TripodOpItem.entries) {
            if (it != TripodOpItem.POST_FX && it != TripodOpItem.DAMAGE_COALESCER) {
                assertTrue("${it.name} HIGH 提交数必须为正", it.opsHigh > 0)
                assertTrue("${it.name} HIGH 填充必须为正", it.fillHigh > 0.0)
            }
        }
        // MED 只豁免火星（R30-b 裁决 HIGH-only）+ 上面那两个
        for (it in TripodOpItem.entries) {
            if (it != TripodOpItem.EMBER && it != TripodOpItem.POST_FX &&
                it != TripodOpItem.DAMAGE_COALESCER
            ) {
                assertTrue("${it.name} MED 提交数必须为正", it.opsMed > 0)
                assertTrue("${it.name} MED 填充必须为正", it.fillMed > 0.0)
            }
        }
        // LOW 的 12 项合法零 = §十 LOW 砍层清单逐项（含建筑运动模糊那一刀，见 BUILDINGS KDoc）
        val lowZero = listOf(
            "GRADE", "BUILDING_SCAN", "LEG_RIM", "WAKE_RIDGE", "BACK_HILITE", "WAKE_CHANNEL",
            "EMBER", "FOOT_SHADOW", "TENDRIL", "GRAB_TENDRIL", "GRAB_HOOK", "POST_FX"
        )
        val byName = TripodOpItem.entries.associateBy { it.name }
        for (n in lowZero) {
            assertEquals("$n LOW 提交数必须为 0（LOW 砍层清单）", 0, byName.getValue(n).opsLow)
            assertEquals("$n LOW 填充必须为 0", 0.0, byName.getValue(n).fillLow, 1e-12)
        }
        // LOW 必须在场的 12 项（天际线本体 + 重量感 + 人群，⛔ 不许为省预算砍）
        val lowOn = listOf(
            "FOG_STRIP", "SKY_BASE", "BUILDINGS", "LEGS", "GROUND_BAND", "BEAM_COLUMN", "SHELL",
            "BEAM_POOL", "IMPACT_RING", "GLAND", "COWL", "CROWD"
        )
        for (n in lowOn) {
            assertTrue("$n LOW 提交数必须为正", byName.getValue(n).opsLow > 0)
            assertTrue("$n LOW 填充必须为正", byName.getValue(n).fillLow > 0.0)
        }
        assertEquals(25, lowZero.size + lowOn.size + 1) // +1 = DAMAGE_COALESCER
        // 洼地合成是 LOW 独有：LOW 不画暗角 ⇒ 基类补一次全屏合成（同 E44 同名行）
        assertEquals(1, TripodOpItem.DAMAGE_COALESCER.opsLow)
        assertEquals(1.00, TripodOpItem.DAMAGE_COALESCER.fillLow, 1e-12)
        assertEquals(0, TripodOpItem.DAMAGE_COALESCER.opsMed)
        assertEquals(0, TripodOpItem.DAMAGE_COALESCER.opsHigh)
        assertEquals(0, TripodOpItem.POST_FX.opsHigh)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·① 提交
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G4 ops 三档都在门内`() {
        for (level in TripodLevel.entries) {
            val n = TripodOpBudget.estimate(level)
            assertTrue(
                "estimate($level) = $n 超出 ${TripodOpBudget.opsMax(level)}",
                n <= TripodOpBudget.opsMax(level)
            )
        }
    }

    /** ⛔ 精确合计（不是门限）：任何一行系数被改动都会在这里显形。 */
    @Test
    fun `G4 逐档合计被钉死`() {
        assertEquals(44, TripodOpBudget.estimate(TripodLevel.LOW))
        assertEquals(73, TripodOpBudget.estimate(TripodLevel.MEDIUM))
        assertEquals(121, TripodOpBudget.estimate(TripodLevel.HIGH))
        assertEquals(4.845f, overdraw(TripodLevel.LOW), 1e-3f)
        assertEquals(6.551f, overdraw(TripodLevel.MEDIUM), 1e-3f)
        assertEquals(6.559f, overdraw(TripodLevel.HIGH), 1e-3f)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·② 填充
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G4 fill 的 LOW 与 MED 在绝对门内`() {
        assertTrue(
            "LOW = ${overdraw(TripodLevel.LOW)} 必须 ≤ ${TripodOpBudget.OVERDRAW_MAX_LOW}",
            overdraw(TripodLevel.LOW) <= TripodOpBudget.OVERDRAW_MAX_LOW
        )
        assertTrue(
            "MED = ${overdraw(TripodLevel.MEDIUM)} 必须 ≤ ${TripodOpBudget.OVERDRAW_MAX_MEDIUM}",
            overdraw(TripodLevel.MEDIUM) <= TripodOpBudget.OVERDRAW_MAX_MEDIUM
        )
    }

    /**
     * HIGH 档的**单调方向**断言（接替绝对上限）：HIGH 必须仍是铺得最满的一档 ——
     * 表口径与实测口径**都**验，缺一边都可能被绕。
     *
     * ⚠️ 填充单调写 **`≤`** 而**不是** `<`：R31 之后 MED 与 HIGH 的表值只差火星那一行
     * `0.008` 屏，而实测口径 MED/HIGH 是 `5.412 / 5.414`（差 0.002）—— 但**表口径**下
     * 若哪天火星被裁到 0 屏，两者就相等。写 `<` 会做出一条永远红的假闸门（§十四 2 同一条口径）。
     */
    @Test
    fun `G4 fill 的 HIGH 必须铺得比 MED 与 LOW 更满`() {
        assertTrue(
            "表口径单调：${overdraw(TripodLevel.LOW)} ≤ ${overdraw(TripodLevel.MEDIUM)} ≤ ${overdraw(TripodLevel.HIGH)}",
            overdraw(TripodLevel.HIGH) >= overdraw(TripodLevel.MEDIUM) &&
                overdraw(TripodLevel.MEDIUM) >= overdraw(TripodLevel.LOW)
        )
        assertTrue(
            "实测口径单调：${TripodOpBudget.MEASURED_FILL_LOW} ≤ ${TripodOpBudget.MEASURED_FILL_MEDIUM} ≤ ${TripodOpBudget.MEASURED_FILL_HIGH}",
            TripodOpBudget.MEASURED_FILL_HIGH >= TripodOpBudget.MEASURED_FILL_MEDIUM &&
                TripodOpBudget.MEASURED_FILL_MEDIUM >= TripodOpBudget.MEASURED_FILL_LOW
        )
        assertTrue("棘轮：${overdraw(TripodLevel.HIGH)} ≤ ${TripodOpBudget.fillRatchetMax()}",
            overdraw(TripodLevel.HIGH) <= TripodOpBudget.fillRatchetMax())
        // 提交数严格递增（三档各加东西，⛔ 没有"高画质反而少画"的形态）
        assertTrue(TripodOpBudget.estimate(TripodLevel.LOW) < TripodOpBudget.estimate(TripodLevel.MEDIUM))
        assertTrue(TripodOpBudget.estimate(TripodLevel.MEDIUM) < TripodOpBudget.estimate(TripodLevel.HIGH))
    }

    /**
     * ⭐ **表不得低于实测**（§十一 T2「输入必须取实测那组」的机器化）。
     * 这一条专门拦"把某行悄悄改小"：门内断言只防超标，不防**低算** ——
     * 而低算才是"门禁在守一个不存在的帧"的那种失效（E43 `POST_FX.fill` 记 0 同源）。
     */
    @Test
    fun `G4 表是实测的上界`() {
        for (level in TripodLevel.entries) {
            val measuredOps = when (level) {
                TripodLevel.LOW -> TripodOpBudget.MEASURED_OPS_LOW
                TripodLevel.MEDIUM -> TripodOpBudget.MEASURED_OPS_MEDIUM
                TripodLevel.HIGH -> TripodOpBudget.MEASURED_OPS_HIGH
            }
            assertTrue(
                "$level 表 ops ${TripodOpBudget.estimate(level)} 必须 ≥ 实测 $measuredOps（表是上界）",
                TripodOpBudget.estimate(level) >= measuredOps
            )
            val fill = when (level) {
                TripodLevel.LOW -> TripodOpBudget.MEASURED_FILL_LOW
                TripodLevel.MEDIUM -> TripodOpBudget.MEASURED_FILL_MEDIUM
                TripodLevel.HIGH -> TripodOpBudget.MEASURED_FILL_HIGH
            }
            assertTrue(
                "$level 表 fill ${overdraw(level)} 必须 ≥ 实测 $fill",
                overdraw(level) >= fill
            )
        }
    }

    @Test
    fun `G4 填充与画幅尺度无关`() {
        assertEquals(
            overdraw(TripodLevel.HIGH),
            TripodOpBudget.overdrawEstimate(3840f, 2160f, TripodLevel.HIGH),
            1e-4f
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  逐行对账：探针 6 位原值 → ceilToMilli → 表值
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * ⭐ 本表**每一行**都能从探针原值再生（`e46_fog_worstframe.js` 的 6 位小数列）。
     *
     * 这是"表不是手抄数"的机器化：把探针原值逐个喂给 [TripodOpBudget.ceilToMilli]，
     * 必须逐位等于表里的 `fill*`。任何一行被人为改大/改小都会在这里显形
     * （E44 D10/D24「表与几何各说各话」就是这么防的）。
     *
     * ⚠️ 探针原值取自 `logs_temp/e46_worstframe_v012.log` 的「逐类最坏帧（跨剧本最大）」段，
     * 跨 V0/V1/V2 三套剧本 × 1800 帧均匀采样。⛔ 不要在这里改期望值去找齐 ——
     * 改了原探针就必须重跑脚本并同步本表与 `TripodOpBudget.kt` 的 KDoc。
     */
    @Test
    fun `逐行对账 探针原值经 ceilToMilli 等于表值`() {
        // 类别 → (opsLow, fillLow原值, opsMed, fillMed原值, opsHigh, fillHigh原值)
        // 原值 null = 该档不付这一类（探针该档无记录）
        val probe: List<Pair<String, List<Any?>>> = listOf(
            "FOG_STRIP" to listOf<Any?>(3, 1.0000004, 7, 2.0000004, 7, 2.0000004),
            "SKY_BASE" to listOf<Any?>(1, 1.0000000, 1, 1.0000000, 1, 1.0000000),
            "GRADE" to listOf<Any?>(null, null, 1, 1.0000000, 1, 1.0000000),
            "BUILDINGS" to listOf<Any?>(19, 0.470863, 19, 0.966409, 19, 0.966409),
            "LEGS" to listOf<Any?>(3, 0.533680, 3, 0.533680, 3, 0.533680),
            "GROUND_BAND" to listOf<Any?>(1, 0.394815, 1, 0.394815, 1, 0.394815),
            "BEAM_COLUMN" to listOf<Any?>(2, 0.349249, 2, 0.349249, 2, 0.349249),
            "BUILDING_SCAN" to listOf<Any?>(null, null, 2, 0.089107, 2, 0.089107),
            "LEG_RIM" to listOf<Any?>(null, null, 3, 0.064042, 3, 0.064042),
            "SHELL" to listOf<Any?>(1, 0.034728, 1, 0.034728, 1, 0.034728),
            "BEAM_POOL" to listOf<Any?>(2, 0.020300, 2, 0.020300, 2, 0.020300),
            "WAKE_RIDGE" to listOf<Any?>(null, null, 6, 0.017574, 6, 0.017574),
            "IMPACT_RING" to listOf<Any?>(1, 0.014117, 1, 0.014117, 1, 0.014117),
            "GLAND" to listOf<Any?>(1, 0.013589, 1, 0.013589, 1, 0.013589),
            "BACK_HILITE" to listOf<Any?>(null, null, 1, 0.011673, 1, 0.011673),
            "WAKE_CHANNEL" to listOf<Any?>(null, null, 3, 0.007118, 3, 0.007118),
            "EMBER" to listOf<Any?>(null, null, null, null, 48, 0.007083),
            "COWL" to listOf<Any?>(1, 0.006592, 1, 0.006592, 1, 0.006592),
            "FOOT_SHADOW" to listOf<Any?>(null, null, 3, 0.005756, 3, 0.005756),
            "TENDRIL" to listOf<Any?>(null, null, 3, 0.004800, 3, 0.004800),
            "GRAB_TENDRIL" to listOf<Any?>(null, null, 2, 0.003200, 2, 0.003200),
            "CROWD" to listOf<Any?>(8, 0.001415, 8, 0.001415, 8, 0.001415),
            "GRAB_HOOK" to listOf<Any?>(null, null, 2, 0.001200, 2, 0.001200)
        )
        val byName = TripodOpItem.entries.associateBy { it.name }
        for ((name, v) in probe) {
            val it = byName.getValue(name)
            // 提交数是整数，逐位相等；null = 探针该档无这一类（该档不画）⇒ 表值必须为 0
            assertEquals("$name opsLow", (v[0] as Int?) ?: 0, it.opsLow)
            assertEquals("$name opsMed", (v[2] as Int?) ?: 0, it.opsMed)
            assertEquals("$name opsHigh", (v[4] as Int?) ?: 0, it.opsHigh)
            // 填充 = ceilToMilli(探针原值)；该档不付 ⇒ 表值必须是 0
            val lo = v[1] as Double?
            val med = v[3] as Double?
            val hi = v[5] as Double?
            assertEquals("$name fillLow", if (lo == null) 0.0 else TripodOpBudget.ceilToMilli(lo), it.fillLow, 1e-12)
            assertEquals("$name fillMed", if (med == null) 0.0 else TripodOpBudget.ceilToMilli(med), it.fillMed, 1e-12)
            assertEquals("$name fillHigh", if (hi == null) 0.0 else TripodOpBudget.ceilToMilli(hi), it.fillHigh, 1e-12)
        }
        // 后两行（POST_FX / DAMAGE_COALESCER）原型探针里没有 ⇒ 单独钉
        assertEquals(0, TripodOpItem.POST_FX.opsLow + TripodOpItem.POST_FX.opsMed + TripodOpItem.POST_FX.opsHigh)
        assertEquals(
            0.0,
            TripodOpItem.POST_FX.fillLow + TripodOpItem.POST_FX.fillMed + TripodOpItem.POST_FX.fillHigh,
            1e-12
        )
        assertEquals(1, TripodOpItem.DAMAGE_COALESCER.opsLow)
        assertEquals(1.000, TripodOpItem.DAMAGE_COALESCER.fillLow, 1e-12)
    }

    /** 向上取整的方向性：`x.xxx5` 之下的值必须被抬，⛔ 就近（E44 `GLITTER` MED 同源的错法）。 */
    @Test
    fun `ceilToMilli 必须向上取整`() {
        assertEquals(0.967, TripodOpBudget.ceilToMilli(0.966409), 1e-12)
        assertEquals(0.534, TripodOpBudget.ceilToMilli(0.533680), 1e-12)
        assertEquals(0.350, TripodOpBudget.ceilToMilli(0.349249), 1e-12)
        // 就近取整会把这两个砍到实测以下 ⇒ 做一条显式的负向自证
        assertEquals(0.966, Math.round(0.966409 * 1000.0) / 1000.0, 1e-12)
        assertTrue(
            "就近取整必须低于向上取整（否则本条的负向自证是空的）",
            Math.round(0.966409 * 1000.0) / 1000.0 < TripodOpBudget.ceilToMilli(0.966409)
        )
        assertEquals(1.001, TripodOpBudget.ceilToMilli(1.0000004), 1e-12)
        assertEquals(1.000, TripodOpBudget.ceilToMilli(1.0), 1e-12)
        assertEquals(0.0, TripodOpBudget.ceilToMilli(0.0), 1e-12)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  负向自证
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * §十六 R30 那条「表按**滑杆量程上限** 48 落而不是默认值 24」的机器化。
     * 按默认 24 落表 ⇒ HIGH 表 ops 掉到 **97 < 实测 104** ⇒ `G4 表是实测的上界` 当场红。
     */
    @Test
    fun `负向自证 火星按默认条数落表则表掉到实测以下`() {
        val measured = TripodOpBudget.opsOf(TripodOpItem.EMBER, TripodLevel.HIGH)
        assertEquals("EMBER 必须按量程上限 48 落表", 48, measured)
        val broken = TripodOpBudget.estimate(TripodLevel.HIGH) - measured + 24
        assertEquals(97, broken)
        assertTrue("按默认 24 落表后 $broken 必须 < 实测 ${TripodOpBudget.MEASURED_OPS_HIGH}",
            broken < TripodOpBudget.MEASURED_OPS_HIGH)
        // 铺屏那一侧同理（虽然只差 0.004 屏，方向必须对）
        val brokenFill = overdraw(TripodLevel.HIGH) - TripodOpItem.EMBER.fillHigh
        assertTrue(brokenFill < overdraw(TripodLevel.HIGH))
    }

    /**
     * §A.7 的 `nMid 22 / nNear 8 = 30` 是**滑杆名义值**，⛔ 不是上界（实测最坏 19）。
     * 按名义落表不会让门红（它比实测**高**）⇒ 它是一道**松**门而不是假门，
     * 但它会让 `BUILDINGS` 行虚高 11 个提交 —— 这条断言钉住"虚高量"，
     * 以便日后真机上若出现 >19 栋，先改表而不是先改这条断言。
     */
    @Test
    fun `负向自证 建筑按名义栋数落表则虚高提交`() {
        val measured = TripodOpBudget.opsOf(TripodOpItem.BUILDINGS, TripodLevel.HIGH)
        assertEquals("BUILDINGS 必须按实测最坏 19 落表", 19, measured)
        val nominal = 22 + 8
        val inflated = TripodOpBudget.estimate(TripodLevel.HIGH) - measured + nominal
        assertEquals(132, inflated)
        assertTrue("名义值把表虚高 ${nominal - measured} 个提交", nominal > measured)
        // 虚高后仍在 ops 门内 ⇒ 它是松门不是假门（⛔ 不要说成"会爆表"）
        assertTrue(inflated <= TripodOpBudget.OPS_MAX_HIGH)
    }

    /**
     * ⭐ **K1 那个 postFx 决策的机器证明**：`TripodFogRenderer` 把 `vignetteOverride` "修"回
     * `0.42f`（走基类暗角）⇒ MED/HIGH 各多一次全屏 `drawRect` = +1.000 屏。
     * 这一笔就足以把 MED 顶出绝对门（`6.551 + 1.000 = 7.551 > 6.75`），
     * HIGH 撞破棘轮（`6.559 + 1.000 = 7.559 > 6.60`）⇒ §十六 R8 明令不许发生的那件事
     * 有**数字**依据，而不只是一句注释。
     */
    @Test
    fun `负向自证 走基类暗角则 MED 顶破绝对门且 HIGH 撞破棘轮`() {
        val extra = 1.000f
        assertTrue(
            "MED + 基类暗角 = ${overdraw(TripodLevel.MEDIUM) + extra} 必须 > ${TripodOpBudget.OVERDRAW_MAX_MEDIUM}",
            overdraw(TripodLevel.MEDIUM) + extra > TripodOpBudget.OVERDRAW_MAX_MEDIUM
        )
        assertTrue(
            "HIGH + 基类暗角 = ${overdraw(TripodLevel.HIGH) + extra} 必须 > 棘轮 ${TripodOpBudget.fillRatchetMax()}",
            overdraw(TripodLevel.HIGH) + extra > TripodOpBudget.fillRatchetMax()
        )
        // LOW 本来就不画后处理（FxLevel.OFF 整段跳过）⇒ 那一档不受影响
        assertTrue(overdraw(TripodLevel.LOW) + extra > TripodOpBudget.OVERDRAW_MAX_LOW)
    }

    /**
     * ⭐ **E46 与 E44 在 `FILL_REF_HIGH` 上的关键差别**（见该常量 KDoc）：
     * 若照抄 E44 的「REF = 实测单帧最坏」（`5.414`），**表值会越过自己的棘轮**
     * （`6.559 > 5.414 × 1.10 = 5.955`）—— 门会自己红，而红的是口径不是代码。
     * 所以 REF 只能按表值反解（`6.559 ÷ 1.10 = 5.963 ⇒ 6.00`）。
     */
    @Test
    fun `负向自证 REF 照抄 E44 口径则表越过自己的棘轮`() {
        val e44Style = TripodOpBudget.MEASURED_FILL_HIGH * TripodOpBudget.FILL_RATCHET
        assertTrue(
            "E44 口径的棘轮 $e44Style 必须容不下表值 ${overdraw(TripodLevel.HIGH)}",
            e44Style < overdraw(TripodLevel.HIGH)
        )
        // 现口径的推导可复现：表值 ÷ 1.10 向上取整 = 6.00
        val derived = Math.ceil(
            (overdraw(TripodLevel.HIGH) / TripodOpBudget.FILL_RATCHET).toDouble()
        )
        assertEquals(6.0, derived, 1e-9)
        assertEquals(6.00f, TripodOpBudget.FILL_REF_HIGH, 1e-6f)
        assertTrue(TripodOpBudget.fillRatchetMax() >= overdraw(TripodLevel.HIGH))
    }

    /**
     * 新上限**不是**为了让门变绿而放宽的：E44 的旧上限 `3.45 / 4.00` 对本表逐档判别。
     * 两条都红 ⇒ 抬上限是"由实测推出"，⛔ 不是"把门涂绿"（E44 §十六裁决 + 偏差 D5 同一条纪律）。
     */
    @Test
    fun `负向自证 E44 的旧上限会把本表逐档判红`() {
        assertTrue("LOW ${overdraw(TripodLevel.LOW)} 必须 > E44 LOW 上限 3.45",
            overdraw(TripodLevel.LOW) > 3.45f)
        assertTrue("MED ${overdraw(TripodLevel.MEDIUM)} 必须 > E44 MED 上限 4.00",
            overdraw(TripodLevel.MEDIUM) > 4.00f)
        // 且实测单帧最坏也已越过 E44 的 LOW 上限 ⇒ 不是"表保守"造成的红
        assertTrue(TripodOpBudget.MEASURED_FILL_MEDIUM > 3.45f)
    }

    @Test
    fun `负向自证 建筑按结构上界落表则 native 爆表`() {
        val spec = TripodOpBudget.nativePxBudget(1920f, 1080f)
        val wrong = TripodOpBudget.nativePxBudgetWithBuildingPx(
            1920f, 1080f, TRIPOD_BUILDING_PX_MAX
        )
        assertTrue("规格口径 = $spec 必须 ≤ 3,000,000", spec <= TripodOpBudget.NATIVE_PX_MAX)
        assertTrue("按结构上界 = $wrong 必须 > 3,000,000", wrong > TripodOpBudget.NATIVE_PX_MAX)
        assertEquals(1_474_560, TRIPOD_BUILDING_PX_MAX)
        assertEquals(567_264, TRIPOD_BUILDING_PX_MEASURED)
    }

    @Test
    fun `拒绝非法画布尺寸`() {
        var threw = 0
        try {
            TripodOpBudget.overdrawEstimate(0f, 900f, TripodLevel.HIGH)
        } catch (e: IllegalArgumentException) {
            threw++
        }
        try {
            TripodOpBudget.nativePxBudgetWithBuildingPx(-1f, 900f, 567264)
        } catch (e: IllegalArgumentException) {
            threw++
        }
        try {
            TripodOpBudget.nativePxBudgetWithBuildingPx(1920f, 1080f, -1)
        } catch (e: IllegalArgumentException) {
            threw++
        }
        assertEquals("三处入参校验必须都生效", 3, threw)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·③ native 堆
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `G4 native 在适用区间内达标`() {
        // 16:9、长边 ≤ 1920（§十 的实测口径）
        val sizes = listOf(1280 to 720, 1600 to 900, 1920 to 1080)
        for ((w, h) in sizes) {
            val n = TripodOpBudget.nativePxBudget(w.toFloat(), h.toFloat())
            assertTrue("nativePxBudget(${w}x$h) = $n 超出 ${TripodOpBudget.NATIVE_PX_MAX}",
                n <= TripodOpBudget.NATIVE_PX_MAX)
        }
        assertEquals(2_777_168, TripodOpBudget.nativePxBudget(1920f, 1080f))
        // 分解式必须恰好等于合计（⛔ 没有第五张位图）
        val parts = TripodOpBudget.gradePx(1920f, 1080f) +
            TRIPOD_BUILDING_PX_MEASURED +
            TRIPOD_FOG_STRIP_PX +
            TRIPOD_PERSON_PX
        assertEquals(parts, TripodOpBudget.nativePxBudget(1920f, 1080f))
    }

    /**
     * ⚠️ **门的适用区间是 ≤1080p，破点在 1440p**。
     *
     * 与 E44 **破点相同、成因不同**：E44 破在跨效果共享的全屏 `Id.STARFIELD`
     * （`ProceduralTexture.kt:199` 没有降采样），本效果破在**本效果自己**的全屏 grade 位图
     * （`bakeGrade` 按 `cv.width × cv.height` 烘，`2560×1440 = 3,686,400` **单项**即超 3,000,000）。
     * 本测试是**哨兵**，钉住"破点在哪"这个事实：若哪天给 grade 加了按比例降采样，它会红 ——
     * 那时该做的是把 `NATIVE_PX_OK_MAX_LONG_EDGE` 抬上去并在 §十 留痕，⛔ 不是删掉这条。
     */
    @Test
    fun `G4 native 的破点是 1440p 哨兵`() {
        val grade1440 = TripodOpBudget.gradePx(2560f, 1440f)
        assertTrue("1440p 的 grade 单项 $grade1440 必须已破 3,000,000",
            grade1440 > TripodOpBudget.NATIVE_PX_MAX)
        val total1440 = TripodOpBudget.nativePxBudget(2560f, 1440f)
        assertTrue("1440p 合计 $total1440 必须破", total1440 > TripodOpBudget.NATIVE_PX_MAX)
        // 1080p 的 grade 占预算 69%（与 E44 的 STARFIELD 占比同一量级，但成因不同）
        val grade1080 = TripodOpBudget.gradePx(1920f, 1080f)
        assertEquals(2_073_600, grade1080)
        assertTrue(grade1080 > TripodOpBudget.NATIVE_PX_MAX * 0.6)
        // 门的适用区间常量与实测边界一致
        assertEquals(1920, TripodOpBudget.NATIVE_PX_OK_MAX_LONG_EDGE)
        assertEquals(2560, TripodOpBudget.NATIVE_PX_BREAK_LONG_EDGE)
        // ⛔ 门内测试⛔ 不许把 1440p 也列进去
        assertFalse(TripodOpBudget.NATIVE_PX_BREAK_LONG_EDGE <= TripodOpBudget.NATIVE_PX_OK_MAX_LONG_EDGE)
    }

    /**
     * 建筑位图的尺寸必须落在 `bakeBuilding` 的钳位区间内（`clamp(round(96·h/w), 48, 320)`）：
     * 实测 mid `[59, 204]` / near `[71, 211]`（`e46_bitmap_inventory.js`）。
     * 这一条同时是 `TripodFogBake` 移植保真度的间接锚 —— 48 栋的 `h/w` 抽样若与原型分叉，
     * 均高会漂，`TRIPOD_BUILDING_PX_MEASURED` 就守不住了。
     */
    @Test
    fun `建筑位图尺寸落在钳位区间`() {
        val range = TripodOpBudget.buildingTexHeightRange()
        assertTrue("区间下界", range.first == 48)
        assertTrue("区间上界", range.last == 320)
        // 实测两侧都在区间内（探针原值）
        assertTrue(59 in range && 204 in range)
        assertTrue(71 in range && 211 in range)
        // 均高 = 567264 ÷ 48 ÷ 96 = 123.104166…
        // ⚠️ 首版把期望值写成 123.5 是 567264/4608 的除法错误（单测当场抓住）：两带均值
        //    124.9（mid 34 栋）/ 118.7（near 14 栋）按 34:14 加权，总均值必落在两者之间。
        assertEquals(123.10416666666667, TripodOpBudget.buildingTexMeanHeight(), 1e-9)
        assertEquals(134_400, TRIPOD_FOG_STRIP_PX)
        assertEquals(1_904, TRIPOD_PERSON_PX)
    }

    /** 便捷：本测试里"表口径"的填充一律用 1600×900（与原型判读画幅同值）。 */
    private fun overdraw(level: TripodLevel): Float =
        TripodOpBudget.overdrawEstimate(1600f, 900f, level)
}
