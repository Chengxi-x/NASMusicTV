package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * E46「迷雾之战 · 雾中三脚巨影」的**落地冲击 / 机身呼吸 / 落地顶机 / 掠过段负压**闸门
 * （§十二 T21、T24、T25、T31）。
 *
 * 四条闸门全部**只判解析记录**（§十四 1）：读 [TripodFogPlan.impactRecs] / [TripodFogPlan.breathRecs] /
 * [TripodFogPlan.wakeRecs] 与 [TripodGait.sceneState] 导出的那几个量，⛔ 不回读像素、⛔ 不用 Robolectric。
 * 记录点就在发射器落笔的那一处 ⇒ 闸门与画面共用同一条代码路径（⛔ 在测试里重推一遍公式）。
 *
 * 负样本一律走**真实绘制路径**：旋钮都在 `internal object`（[ImpactGeom] / [BreathGeom] /
 * [JoltGeom] / [WakeGeom] 与 `TripodParams`）里，每条各配 `try/finally` 复位，⛔ 手喂常量。
 * ⚠️ [TripodGait.sceneState] 返回的是世界里的**预分配缓冲**（`legs` 等，R12）⇒ 只在当帧内读标量，
 * ⛔ 把 `st` 或 `st.legs[i]` 存到下一帧再用。
 */
internal class TripodFogImpactGatesTest {

    private lateinit var world: TripodFogWorld
    private lateinit var params: TripodParams
    private lateinit var table: TripodGateBmpTable
    private lateinit var plan: TripodFogPlan

    /** 采样步长（原型各闸门的 DT）：一圈帧数 = `round(LOOP / DT) + 1`。 */
    private val impactDt = 1.0 / 12.0        // T21 ⇒ 1081 帧
    private val breathDt = 1.0 / 24.0        // T24 ⇒ 2161 帧
    private val joltDt = 1.0 / 48.0          // T25 ⇒ 4321 帧
    private val wakeDt = 1.0 / 6.0           // T31 慢采样 ⇒ 541 帧
    private val wakeFineDt = 1.0 / 12.0      // T31(b) 速度采样 ⇒ 1081 帧

    /** T25 的「远处」= **物理**判据（m），⛔ 跟着 `JoltGeom.D_OUT` 走（否则负样本 C 自己把判据拆没）。 */
    private val joltFarD = 150.0

    @Before
    fun setup() {
        world = TripodGateFixtures.boot()
        params = world.params
        table = TripodGateBmpTable()
        for (b in world.bMid) b.bmp?.let { table.put(it) }
        for (b in world.bNear) b.bmp?.let { table.put(it) }
        plan = TripodFogPlan(world, table)
        // ⚠️ 档位写在 world 上，发射器读它（⛔ 以为 plan 有自己的 tier）
        world.tier = TripodLevel.MEDIUM
    }

    private fun fmt(x: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", x)

    private fun whyText(w: List<String>): String =
        if (w.isEmpty()) "（判据全绿）" else w.joinToString("；").take(240)

    private fun hasTag(w: List<String>, tag: String): Boolean = w.any { it.startsWith("$tag ") }

    private fun lapN(dt: Double): Int = (params.loop / dt).roundToInt()

    // ══════════════════════════════════════ T21 落地冲击压雾（R22）══════════════════════════════════════

    /** 原型 T21 的 `scanPure()`：只读 `sceneState` 的纯量（⛔ 为此跑整帧绘制）。 */
    private class ImpactPure {
        var frames = 0
        var maxPerFrame = 0
        var swing = 0
        var events = 0
        var seam = 0.0
        var impHi = 0.0
    }

    private fun scanImpactPure(n: Int): ImpactPure {
        val r = ImpactPure()
        val prev = IntArray(3)
        for (i in 0..n) {
            val st = TripodGait.sceneState(world, i * impactDt)
            var k = 0
            for (j in 0 until 3) {
                val imp = st.legs[j].imp
                val swing = st.legs[j].swing
                val on = if (imp > 0.004) 1 else 0
                if (on == 1) {
                    k++
                    if (swing) r.swing++
                    if (prev[j] == 0) r.events++      // 上升沿 = 一记触地
                }
                prev[j] = on
                if (imp > r.impHi) r.impHi = imp
            }
            if (k > 0) r.frames++
            if (k > r.maxPerFrame) r.maxPerFrame = k
        }
        for (t in doubleArrayOf(0.0, params.loop)) {
            val st = TripodGait.sceneState(world, t)
            for (j in 0 until 3) {
                val imp = st.legs[j].imp
                if (imp > r.seam) r.seam = imp
            }
        }
        return r
    }

    /** 原型 T21 的 `scanDraw()`：走真实发射路径，读 `IMPACT_REC` 的落笔侧统计。 */
    private class ImpactDraw {
        var on = 0
        var off = 0
        var rxLo = 1e9
        var rxHi = 0.0
        var aLo = 1.0
        var aHi = 0.0

        /** `PROF[落地冲击环]` 全圈累计 —— 与 [on] 必须相等（记了账却没落笔 = R25 的 CTM 事故）。 */
        var ringOps = 0
    }

    private fun scanImpactDraw(n: Int): ImpactDraw {
        val r = ImpactDraw()
        val ringOrd = TripodOpItem.IMPACT_RING.ordinal
        for (i in 0..n) {
            val st = TripodGait.sceneState(world, i * impactDt)
            plan.build(st)                          // hz 在 build 帧首落地，与原型 render() 同一处
            r.ringOps += plan.profOps[ringOrd]
            for (j in 0 until plan.impactN) {
                val x = plan.impactRecs[j]
                val rx = x.rx
                val a = x.a
                val on = x.on
                if (!on) {
                    r.off++
                    continue
                }
                r.on++
                if (rx < r.rxLo) r.rxLo = rx
                if (rx > r.rxHi) r.rxHi = rx
                if (a < r.aLo) r.aLo = a
                if (a > r.aHi) r.aHi = a
            }
        }
        return r
    }

    /** 原型 T21 的 `envCheck()`：包络三个端点（触地前一刻 / 触地后一瞬 / 窗末）。 */
    private class ImpactEnv(val at: Double, val just: Double, val end: Double)

    private fun impactEnv(): ImpactEnv = ImpactEnv(
        at = TripodFogMath.footImp(TripodFogMath.mod(ImpactGeom.PHASE - 1e-6, 1.0)),
        just = TripodFogMath.footImp(TripodFogMath.mod(ImpactGeom.PHASE + 1e-6, 1.0)),
        end = TripodFogMath.footImp(TripodFogMath.mod(ImpactGeom.PHASE + ImpactGeom.WIN - 1e-9, 1.0)),
    )

    /** 原型 T21 的 `why(pu, dr, ev)`（八条判据，顺序与文案逐条对齐）。 */
    private fun impactWhy(pu: ImpactPure, dr: ImpactDraw, ev: ImpactEnv): List<String> {
        val bad = ArrayList<String>()
        val n = lapN(impactDt)
        val steps = (world.gtot / params.stride).roundToInt()
        val rxMax = TripodGateFixtures.W * 0.22
        if (dr.on == 0) {
            bad.add(
                "d 全圈 ${n + 1} 帧里冲击环**一次都没落笔**（强度 ${fmt(params.impactA, 2)}、" +
                    "峰值 imp ${fmt(pu.impHi, 3)}）⇒ 包络存在但画面没付代价，本闸门无对象",
            )
        }
        if (pu.swing > 0) bad.add("a 有 ${pu.swing} 次冲击落在**摆动相**的脚上 ⇒ 雾被还抬着的脚排开")
        if (pu.maxPerFrame > 1) {
            bad.add(
                "b 同帧最多 ${pu.maxPerFrame} 条冲击同时开 ⇒ 这一层一次吃掉 ${pu.maxPerFrame} 个图元，" +
                    "且三个落点糊成一片",
            )
        }
        if (pu.events != 3 * steps) {
            bad.add(
                "c 一轮触发 ${pu.events} 次 ≠ 3 腿 × $steps 步 ⇒ 冲击频率不是由步频导出" +
                    "（漏触发，或自己带了个频率）",
            )
        }
        if (!(dr.rxLo >= 8 && dr.rxHi <= rxMax)) {
            bad.add(
                "d 环半径 ${fmt(dr.rxLo)}→${fmt(dr.rxHi)} px 越出 [8, ${fmt(rxMax, 0)}] " +
                    "⇒ 要么看不见、要么横扫小半屏",
            )
        }
        // ⚠️ 判**峰值** α，⛔ 判最小值：包络快起慢落，尾巴必然一路衰减到绘制自己的 0.01 剔除阈为止。
        if (!(dr.aHi >= 0.25 && dr.aHi <= 0.60)) {
            bad.add("d 环峰值 α ${fmt(dr.aHi, 3)} 越出 [0.25, 0.60] ⇒ 要么整圈读不出来、要么一枚实心白饼")
        }
        if (!(ev.at < 1e-6 && ev.just < 0.05 && ev.end < 0.05)) {
            bad.add(
                "e 包络端点 ${fmt(ev.at, 3)} / ${fmt(ev.just, 3)} / ${fmt(ev.end, 3)}（触地前 / 触地后一瞬 / 窗末）" +
                    "⇒ 起跳阶跃或窗尾被截断，换相那一帧会闪一下",
            )
        }
        if (pu.seam > 0.004) {
            bad.add("e t=0 与 t=LOOP 两侧仍有 imp=${fmt(pu.seam, 3)} 的冲击在画 ⇒ 跨圈残留，接缝处凭空多出一圈")
        }
        return bad
    }

    /**
     * T21 落地冲击压雾（R22）：只在站立相、同帧一条、频率由步频导出、有界。
     *
     * 原型实测（§十二 T21 行）：一轮触发 **180 次 = 3 腿 × 60 步**（`GTOT 600 m ÷ STRIDE 10 m`）；
     * 同帧最多 **1** 条（`win 0.22` 步幅 < 1/3）；有冲击的帧 **624 / 1081（57.7%）**、落笔 **490** 条
     * （被剔 134 条）；落笔环半径 **11.0→140.4 px** ∈ [8, 211]、α **0.010→0.400**（峰值判 [0.25, 0.60]）；
     * 包络端点 **0.000/0.000/0.000**、接缝两侧最大 `imp` **0.0000**；摆动相触发 **0**。
     *
     * 负样本（§十二）：**A** `win→0.5` ⇒ 同帧 **2** 条 + 一轮 **181** 次 + 接缝 `imp=0.192`；
     * **B** 触地相位→0 ⇒ **624** 次冲击落在摆动相；**C** 强度→0 ⇒ **一次都没落笔** + 峰值 α 0.000；
     * **C′** 强度→0.06（原型这条才是本闸门的正身：环照样落笔、ops 照样记，只有 α 掉到肉眼阈下）；
     * **D** 强度→1.2 ⇒ 峰值 α **0.961**；**E** `rOut→40 m` ⇒ 半径 **12.1→678.9 px**；
     * **F** `attack→0` ⇒ 包络端点 **0.000/1.000/0.000**。
     */
    @Test
    fun t21_impactRingIsStrideDerivedStandingPhaseOnly() {
        val n = lapN(impactDt)
        val steps = (world.gtot / params.stride).roundToInt()
        val savedWin = ImpactGeom.WIN
        val savedPhase = ImpactGeom.PHASE
        val savedA = params.impactA
        val savedROut = ImpactGeom.R_OUT
        val savedAttack = ImpactGeom.ATTACK

        val pu0 = scanImpactPure(n)
        val dr0 = scanImpactDraw(n)
        val ev0 = impactEnv()
        val w0 = impactWhy(pu0, dr0, ev0)

        // ── 正样本 ──
        assertTrue(
            "T21 正样本判据未全绿：${whyText(w0)}｜一轮触发 ${pu0.events} 次（应 = 3×$steps）、" +
                "同帧最多 ${pu0.maxPerFrame} 条、摆动相 ${pu0.swing}、有冲击的帧 ${pu0.frames}/${n + 1}、" +
                "落笔 ${dr0.on} 条（被剔 ${dr0.off}）、环半径 ${fmt(dr0.rxLo)}→${fmt(dr0.rxHi)} px、" +
                "α ${fmt(dr0.aLo, 3)}→${fmt(dr0.aHi, 3)}、包络端点 ${fmt(ev0.at, 3)}/${fmt(ev0.just, 3)}/${fmt(ev0.end, 3)}、" +
                "接缝最大 imp ${fmt(pu0.seam, 4)}",
            w0.isEmpty(),
        )
        // 反空判据：⛔「没采到」读成「通过」
        assertTrue("T21 无对象：一圈 ${n + 1} 帧里一个冲击帧都没采到", pu0.frames > 0)
        assertTrue("T21 无对象：一圈一次都没落笔（落笔 ${dr0.on} 条）", dr0.on > 0)
        assertTrue("T21 无对象：一圈触发 ${pu0.events} 次 = 0 ⇒ 频率判据在空转（应 3×$steps）", pu0.events > 0)
        assertEquals(
            "T21 记账与落笔脱钩：IMPACT_RING 图元 ${dr0.ringOps} 次 ≠ 落笔 ${dr0.on} 条（§十四 1）",
            dr0.on, dr0.ringOps,
        )

        // ── A win→0.5：同帧双条 + 触发数脱钩 + 跨圈残留 ──
        val aw: List<String> = try {
            ImpactGeom.WIN = 0.5
            impactWhy(scanImpactPure(n), dr0, impactEnv())
        } finally {
            ImpactGeom.WIN = savedWin
        }
        assertTrue("T21(A) 负样本假绿：win→0.5 后判据全绿（应当出现同帧双条/触发数/接缝之一）", aw.isNotEmpty())

        // ── B 触地相位→0：冲击落在摆动相 ──
        val bw: List<String> = try {
            ImpactGeom.PHASE = 0.0
            impactWhy(scanImpactPure(n), dr0, impactEnv())
        } finally {
            ImpactGeom.PHASE = savedPhase
        }
        assertTrue("T21(B) 负样本假绿：相位推到摆动相后判据全绿", bw.isNotEmpty())

        // ── C 强度→0（判的是「有没有这一层」）──
        val cw: List<String> = try {
            params.impactA = 0.0
            impactWhy(pu0, scanImpactDraw(n), ev0)
        } finally {
            params.impactA = savedA
        }
        assertTrue("T21(C) 负样本假绿：impactA→0 后仍有落笔/判据全绿", cw.isNotEmpty())

        // ── C′ 强度→0.06（本闸门的正身：照样落笔、ops 照样记，只有 α 掉到肉眼阈下）──
        val cg: List<String> = try {
            params.impactA = 0.06
            impactWhy(pu0, scanImpactDraw(n), ev0)
        } finally {
            params.impactA = savedA
        }
        assertTrue("T21(C′) 负样本假绿：impactA→0.06 时峰值 α 仍在 [0.25,0.60] 内 ⇒ 下沿没在守肉眼阈", cg.isNotEmpty())

        // ── D 强度→1.2：一枚实心白饼 ──
        val dw: List<String> = try {
            params.impactA = 1.2
            impactWhy(pu0, scanImpactDraw(n), ev0)
        } finally {
            params.impactA = savedA
        }
        assertTrue("T21(D) 负样本假绿：impactA→1.2 后峰值 α 未越 0.60", dw.isNotEmpty())

        // ── E rOut→40 m：横扫小半屏 ──
        val ew: List<String> = try {
            ImpactGeom.R_OUT = 40.0
            impactWhy(pu0, scanImpactDraw(n), ev0)
        } finally {
            ImpactGeom.R_OUT = savedROut
        }
        assertTrue("T21(E) 负样本假绿：rOut→40 m 后环半径未越 [8, ${fmt(TripodGateFixtures.W * 0.22, 0)}]", ew.isNotEmpty())

        // ── F attack→0：阶跃起波（换相那一帧闪一下）──
        val fw: List<String> = try {
            ImpactGeom.ATTACK = 1e-9
            impactWhy(pu0, dr0, impactEnv())
        } finally {
            ImpactGeom.ATTACK = savedAttack
        }
        assertTrue("T21(F) 负样本假绿：attack→0 后端点仍然贴 0（触地后一瞬 ${fmt(ev0.just, 3)}）", fw.isNotEmpty())

        // ── 复位自检：⛔ 让负样本污染后面的闸门 ──
        assertEquals(savedWin, ImpactGeom.WIN, 1e-12)
        assertEquals(savedPhase, ImpactGeom.PHASE, 1e-12)
        assertEquals(savedA, params.impactA, 1e-12)
        assertEquals(savedROut, ImpactGeom.R_OUT, 1e-12)
        assertEquals(savedAttack, ImpactGeom.ATTACK, 1e-12)
    }

    // ══════════════════════════════════════ T24 机身呼吸（R24）══════════════════════════════════════

    /** 原型 T24 的 `scan()`：逐帧采 `BREATH_REC[0]`（投影不到机身的帧记 miss）。 */
    private class BreathScan {
        val ts = ArrayList<Double>()
        val m0 = ArrayList<Double>()
        val m1 = ArrayList<Double>()
        val m2 = ArrayList<Double>()
        val top = ArrayList<Double>()
        val bot = ArrayList<Double>()
        var ops = 0
        var miss = 0
    }

    private fun scanBreath(n: Int): BreathScan {
        val r = BreathScan()
        for (i in 0..n) {
            val t = i * breathDt
            val st = TripodGait.sceneState(world, t)
            plan.build(st)
            // ⚠️ 口径差：原型只跑 drawBody 这一层、这里跑整帧 ⇒ 两个数只差一个常数，
            //    相等关系（呼吸 vs 无呼吸）不受影响；且「零新增图元」按原型纪律**只当观测**。
            r.ops += plan.frameOps
            if (plan.breathN == 0) {
                r.miss++
                continue
            }
            val b = plan.breathRecs[0]
            r.ts.add(t)
            r.m0.add(b.m0)
            r.m1.add(b.m1)
            r.m2.add(b.m2)
            r.top.add(b.top)
            r.bot.add(b.bot)
        }
        return r
    }

    /** 一个周期内取**第一个**极大时刻（一圈 5 个等高峰，全局 argmax 读的是采样网格而不是相位差）。 */
    private fun firstMax(ts: List<Double>, arr: List<Double>, cyc: Double): Double {
        var k = -1
        var i = 0
        while (i < arr.size && ts[i] < cyc) {
            if (k < 0 || arr[i] > arr[k]) k = i
            i++
        }
        return if (k < 0) Double.NaN else ts[k]
    }

    private fun breathLag(a: Double, b: Double, cyc: Double): Double {
        if (a.isNaN() || b.isNaN()) return Double.NaN
        val d = abs(a - b) % cyc
        val alt = cyc - d
        return if (d < alt) d else alt
    }

    private class BreathStat(val p2p: Double, val l01: Double, val l02: Double, val l12: Double)

    private fun breathStat(h: BreathScan, cyc: Double): BreathStat {
        var lo = 1e9
        var hi = -1e9
        for (v in h.m0) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val t0 = firstMax(h.ts, h.m0, cyc)
        val t1 = firstMax(h.ts, h.m1, cyc)
        val t2 = firstMax(h.ts, h.m2, cyc)
        return BreathStat(hi - lo, breathLag(t0, t1, cyc), breathLag(t0, t2, cyc), breathLag(t1, t2, cyc))
    }

    /** 真实落笔顶/底缘相对**无呼吸**那一趟的最大偏移（分母 = 无呼吸时的机高）。 */
    private fun driftOf(a: BreathScan, z: BreathScan): Double {
        if (a.top.size != z.top.size) return Double.NaN   // 帧数不齐 = 两次看到的不是同一批帧
        var m = 0.0
        var hh = 0.0
        for (i in a.top.indices) {
            val dt = abs(a.top[i] - z.top[i])
            val db = abs(a.bot[i] - z.bot[i])
            if (dt > m) m = dt
            if (db > m) m = db
            val h = z.bot[i] - z.top[i]
            if (h > hh) hh = h
        }
        return m / hh
    }

    /** 原型 T24 的 `why(s, dr)`。 */
    private fun breathWhy(s: BreathStat, dr: Double): List<String> {
        val bad = ArrayList<String>()
        if (!(s.p2p >= 0.5 && s.p2p <= 1.3)) {
            bad.add(
                "a 甲壳谐波乘值峰峰值 ${fmt(s.p2p, 3)} 越出 [0.5, 1.3] ⇒ " +
                    (if (s.p2p < 0.5) "基本没在动，这条细节等于没做" else "整机在明显胀缩（气球）"),
            )
        }
        val lmin = if (s.l01.isNaN() || s.l02.isNaN() || s.l12.isNaN()) {
            Double.NaN
        } else {
            min(min(s.l01, s.l02), s.l12)
        }
        if (!(lmin >= 1)) {
            bad.add(
                "b 三段最大时刻最近的一对只差 ${fmt(lmin, 2)} s（< 1 s）⇒ 同相缩放，" +
                    "读成气球而不是皮下蠕动",
            )
        }
        if (!(dr <= 0.08)) {
            bad.add(
                "c 真实落笔顶/底缘相对无呼吸时最大偏移 ${if (dr.isNaN()) "帧数不齐（读不出）" else fmt(dr * 100, 2) + "%"} " +
                    "机高（> 8%）⇒ §3.1 的整机高度口径被呼吸推翻",
            )
        }
        return bad
    }

    /**
     * T24 机身呼吸（R24）：只在动且有界、三段错相、⛔ 推翻整机高度口径。
     *
     * 原型实测（§十二 T24 行）：一圈 **2161 帧**（1/24 s），机身在画 **1978** 帧；甲壳谐波乘值峰峰值
     * **0.900** ∈ [0.5, 1.3]（`breathA 0.45` × 周期 18 s ⇒ 一圈 **5** 次呼吸）；三段最大时刻两两错开
     * **6.67 / 5.21 / 6.13 s**（判 ≥1 s）；真实落笔顶/底缘最大偏移 **2.27% 机高**（判 ≤8%）；
     * ⚠️ **零新增图元**（一圈 **13839 vs 13839**）按原型纪律**只作观测、⛔ 写成判据**（呼吸不新增任何
     * ctx 调用，结构上不可能失败 = 恒真式）；`breathPh` 已进 T4 指纹（178 字段，⛔ 在此重复判定）。
     *
     * 负样本：**A** 深度→0 ⇒ 峰峰值 **0.000** + 最近一对 **0.00 s**；**B** 深度→1.2 ⇒ 峰峰值 **2.400**；
     * **C** 三相归零 ⇒ 最近一对 **0.00 s < 1 s**；**D** `radK→1`（呼吸进半径）⇒ 顶/底缘偏移 **16.3% > 8%**。
     */
    @Test
    fun t24_bodyBreathMovesWithPhaseAndStaysBounded() {
        val n = lapN(breathDt)
        val cyc = 1.0 / params.breathHz
        val savedA = params.breathA
        val savedPh = BreathGeom.ph.copyOf()
        val savedRad = BreathGeom.radK

        val h0 = scanBreath(n)
        val z0: BreathScan = try {
            params.breathA = 0.0
            scanBreath(n)                     // z0 既是 A 的负样本，也是 (c) 漂移的基准
        } finally {
            params.breathA = savedA
        }
        val d0 = driftOf(h0, z0)
        val s0 = breathStat(h0, cyc)
        val w0 = breathWhy(s0, d0)

        assertTrue(
            "T24 正样本判据未全绿：${whyText(w0)}｜一圈 ${n + 1} 帧、机身在画 ${h0.ts.size} 帧（投影不到 ${h0.miss} 帧）、" +
                "峰峰值 ${fmt(s0.p2p, 3)} ∈ [0.5,1.3]、错相 ${fmt(s0.l01, 2)}/${fmt(s0.l02, 2)}/${fmt(s0.l12, 2)} s、" +
                "顶底缘偏移 ${if (d0.isNaN()) "帧数不齐" else fmt(d0 * 100, 2) + "%"}（判 ≤8%）；" +
                "零新增图元只作观测（⛔ 判据 = 恒真式）：一圈图元 ${h0.ops} vs 无呼吸 ${z0.ops}",
            w0.isEmpty(),
        )
        assertTrue("T24 无对象：一圈没有一帧采到呼吸记录", h0.m0.isNotEmpty())

        val wa = breathWhy(breathStat(z0, cyc), d0)
        assertTrue("T24(A) 负样本假绿：breathA→0 后峰峰值 ${fmt(breathStat(z0, cyc).p2p, 3)} 仍判得过", wa.isNotEmpty())

        val wB: List<String> = try {
            params.breathA = 1.2
            breathWhy(breathStat(scanBreath(n), cyc), d0)
        } finally {
            params.breathA = savedA
        }
        assertTrue("T24(B) 负样本假绿：breathA→1.2 后峰峰值未越 1.3", wB.isNotEmpty())

        val wb: List<String> = try {
            BreathGeom.ph = doubleArrayOf(0.0, 0.0, 0.0)
            breathWhy(breathStat(scanBreath(n), cyc), d0)
        } finally {
            BreathGeom.ph = savedPh
        }
        assertTrue("T24(C) 负样本假绿：三相归零后最近一对仍 ≥ 1 s（同相缩放没被抓到）", wb.isNotEmpty())

        val hc: BreathScan = try {
            BreathGeom.radK = 1.0
            scanBreath(n)
        } finally {
            BreathGeom.radK = savedRad
        }
        val dD = driftOf(hc, z0)
        val wc = breathWhy(s0, dD)
        assertTrue(
            "T24(D) 负样本假绿：radK→1 后顶/底缘偏移未越 8%" +
                "（实测 ${if (dD.isNaN()) "帧数不齐" else fmt(dD * 100, 2)}%，基准 ${if (d0.isNaN()) "帧数不齐" else fmt(d0 * 100, 2)}%）",
            wc.isNotEmpty(),
        )

        assertEquals(savedA, params.breathA, 1e-12)
        assertEquals(savedPh.size, BreathGeom.ph.size)
        assertEquals(savedPh[0], BreathGeom.ph[0], 1e-12)
        assertEquals(savedPh[1], BreathGeom.ph[1], 1e-12)
        assertEquals(savedPh[2], BreathGeom.ph[2], 1e-12)
        assertEquals(savedRad, BreathGeom.radK, 1e-12)
    }

    // ══════════════════════════════════════ T25 落地顶机（R26）══════════════════════════════════════

    /** 原型 T25 的 `scanJolt()`：全部读 `sceneState` **导出的那一份**（⛔ 闸门里重推包络）。 */
    private class JoltScan {
        var ev = 0
        var pk = 0
        var hi = 0.0
        var nearHi = 0.0
        var farHi = 0.0
        var seam = 0.0

        /** 与恒幅微抖的 Pearson 相关系数（观测值，⛔ 当判据）。 */
        var rho = 0.0
    }

    private fun scanJolt(n: Int): JoltScan {
        val r = JoltScan()
        val jy = DoubleArray(n + 1)
        val ms = DoubleArray(n + 1)
        val kk = DoubleArray(n + 1)
        val prev = IntArray(3)
        for (i in 0..n) {
            val st = TripodGait.sceneState(world, i * joltDt)
            val a = abs(st.joltY)
            jy[i] = st.joltY
            ms[i] = st.shakeY - st.joltY              // 恒幅微抖那一份
            kk[i] = st.joltK
            if (a > r.hi) r.hi = a
            // ⚠️ 远近按**物理距离**判，⛔ 按包络自己判（否则 D_OUT→1e9 后没有帧算「远处」= 假负样本）
            if (st.dist <= JoltGeom.D_IN && a > r.nearHi) r.nearHi = a
            if (st.dist >= joltFarD && a > r.farHi) r.farHi = a
            for (j in 0 until 3) {
                val on = if (st.legs[j].imp > 1e-9) 1 else 0
                if (on == 1 && prev[j] == 0 && st.joltK >= 0.25) r.ev++   // 上升沿 = 一记落地
                prev[j] = on
            }
        }
        // ⚠️ 峰检测必须**第二遍**做（采样循环里 jy[i+1] 还不存在）；地板取当帧包络的 0.15 倍
        for (i in 1 until n) {
            if (kk[i] < 0.25) continue
            if (jy[i] >= 0.15 * params.joltA * kk[i] && jy[i] >= jy[i - 1] && jy[i] > jy[i + 1]) r.pk++
        }
        r.seam = abs(TripodGait.sceneState(world, 0.0).joltY) +
            abs(TripodGait.sceneState(world, params.loop).joltY)
        var m1 = 0.0
        var m2 = 0.0
        for (i in 0..n) {
            m1 += jy[i]
            m2 += ms[i]
        }
        m1 /= (n + 1)
        m2 /= (n + 1)
        var c = 0.0
        var v1 = 0.0
        var v2 = 0.0
        for (i in 0..n) {
            val a1 = jy[i] - m1
            val a2 = ms[i] - m2
            c += a1 * a2
            v1 += a1 * a1
            v2 += a2 * a2
        }
        r.rho = if (v1 > 0.0 && v2 > 0.0) c / sqrt(v1 * v2) else 0.0
        return r
    }

    /** 原型 T25 的 `why(x)`。 */
    private fun joltWhy(x: JoltScan): List<String> {
        val bad = ArrayList<String>()
        val n = lapN(joltDt)
        if (x.hi < 1e-9) {
            bad.add(
                "一圈 ${n + 1} 帧里相机**一次都没被顶过**（joltA ${fmt(params.joltA)}）" +
                    "⇒ 本闸门无对象",
            )
        }
        if (abs(x.pk - x.ev) > 1) {
            bad.add(
                "joltY 正峰 ${x.pk} 个 vs 包络内落地 ${x.ev} 记 ⇒ 回弹不是落地的导出量" +
                    "（漏腿，或自己带了一条频率）",
            )
        }
        if (x.farHi > 1e-9) {
            bad.add(
                "远处（dist ≥ ${fmt(joltFarD, 0)} m，包络本该为零）的帧里最大 |joltY| = ${fmt(x.farHi, 3)} px ≠ 0 " +
                    "⇒ 机器还在雾那头镜头就先晃，穿帮",
            )
        }
        if (!(x.nearHi >= 0.6 * params.joltA)) {
            bad.add(
                "贴身段（dist ≤ ${fmt(JoltGeom.D_IN, 0)} m）峰值只有 ${fmt(x.nearHi, 2)} px < " +
                    "0.6·joltA = ${fmt(0.6 * params.joltA)} ⇒ 最该有重量感的那几秒被包络掐掉了",
            )
        }
        if (x.hi > params.joltA + 1e-9) {
            bad.add(
                "max|joltY| = ${fmt(x.hi, 2)} px 超过 joltA ${fmt(params.joltA)} ⇒ 多条腿的冲击叠在了一起" +
                    "（IMPACT.win 必须 < 1/3）",
            )
        }
        if (x.seam > 1e-9) {
            bad.add("接缝两侧 |joltY| 合计 ${fmt(x.seam, 6)} ≠ 0 ⇒ 跨圈残留，换圈那一瞬凭空多出一记")
        }
        return bad
    }

    /**
     * T25 落地顶机（R26）：峰数 = 落地数、远处为零近处吃满、不叠爆、接缝不残留。
     *
     * 原型实测（§十二 T25 行）：一圈 **4321 帧**（1/48 s）；包络内（`joltK ≥ 0.25`）落地 **68** 记 ⇒
     * `joltY` 正峰 **68** 个（判相等，容差 1）；峰值 **7.18 px ≤ joltA 9.0**；贴身段（`dist ≤ 20 m`）
     * **7.18 px**、远处（`dist ≥ 150 m`）**0.0000 px**；接缝两侧合计 **0.0e+0**；横向串扰 `xK 0.30`；
     * ⚠️ 与恒幅微抖的相关系数 **0.002** 只当**观测**（证明这层不是把 `shakeY` 重画一遍），⛔ 当判据；
     * `joltY` 折进 `shakeY` 后仍由 T4 指纹闭合（178 字段，未新增 ⇒ ⛔ 在本闸门重复判定）。
     *
     * 负样本：**A** `legK→1` ⇒ 正峰 **23** vs 落地 **68**；**B** `cyc→2` ⇒ **134** vs **68**；
     * **C** `dOut→1e9` ⇒ 远处最大 |joltY| **7.624 px ≠ 0**；**D** `joltA→0` ⇒ 一次都没被顶过 + 正峰 **0**；
     * **E** `IMPACT.win→0.9`（三腿叠加）⇒ 贴身段峰值 **4.30 px < 5.4**。
     */
    @Test
    fun t25_landingJoltPeaksEqualLandingCount() {
        val n = lapN(joltDt)
        val savedLegK = JoltGeom.LEG_K
        val savedCyc = JoltGeom.CYC
        val savedDOut = JoltGeom.D_OUT
        val savedJoltA = params.joltA
        val savedWin = ImpactGeom.WIN

        val x0 = scanJolt(n)
        val w0 = joltWhy(x0)
        assertTrue(
            "T25 正样本判据未全绿：${whyText(w0)}｜包络内落地 ${x0.ev} 记 ⇒ 正峰 ${x0.pk} 个、" +
                "峰值 ${fmt(x0.hi, 2)} px ≤ joltA ${fmt(params.joltA)}、贴身 ${fmt(x0.nearHi, 2)} px、" +
                "远处 ${fmt(x0.farHi, 4)} px、接缝 ${fmt(x0.seam, 8)}、rho ${fmt(x0.rho, 3)}（观测）",
            w0.isEmpty(),
        )
        // 反空判据
        assertTrue("T25 无对象：一圈 ${n + 1} 帧一个 joltY 峰都没数到（峰 ${x0.pk}、落地 ${x0.ev}）", x0.pk > 0)

        val wa: List<String> = try {
            JoltGeom.LEG_K = 1
            joltWhy(scanJolt(n))
        } finally {
            JoltGeom.LEG_K = savedLegK
        }
        assertTrue("T25(A) 负样本假绿：只挂一条腿后峰数仍等于落地数", wa.isNotEmpty())

        val wb: List<String> = try {
            JoltGeom.CYC = 2
            joltWhy(scanJolt(n))
        } finally {
            JoltGeom.CYC = savedCyc
        }
        assertTrue("T25(B) 负样本假绿：一记里两个回弹后峰数仍等于落地数", wb.isNotEmpty())

        val wc: List<String> = try {
            JoltGeom.D_OUT = 1e9
            joltWhy(scanJolt(n))
        } finally {
            JoltGeom.D_OUT = savedDOut
        }
        assertTrue("T25(C) 负样本假绿：距离包络拆掉后远处仍然 0 px（判据按包络自己算了 = 恒真）", wc.isNotEmpty())

        val wd: List<String> = try {
            params.joltA = 0.0
            joltWhy(scanJolt(n))
        } finally {
            params.joltA = savedJoltA
        }
        assertTrue("T25(D) 负样本假绿：幅度→0 后仍有 |joltY|", wd.isNotEmpty())

        val we: List<String> = try {
            ImpactGeom.WIN = 0.9
            joltWhy(scanJolt(n))
        } finally {
            ImpactGeom.WIN = savedWin
        }
        assertTrue("T25(E) 负样本假绿：冲击窗放到 0.9 步幅后既不叠爆也不失重", we.isNotEmpty())

        assertEquals(savedLegK.toLong(), JoltGeom.LEG_K.toLong())
        assertEquals(savedCyc.toLong(), JoltGeom.CYC.toLong())
        assertEquals(savedDOut, JoltGeom.D_OUT, 1e-12)
        assertEquals(savedJoltA, params.joltA, 1e-12)
        assertEquals(savedWin, ImpactGeom.WIN, 1e-12)
    }

    // ══════════════════════════════════════ T31 掠过段负压（R25）══════════════════════════════════════

    /** 原型 T31 的 `run(tier, dt)`：走真实 `render()`（这里是 [TripodFogPlan.build]），读 `WAKE_REC`。 */
    private class WakeRun(val label: String) {
        var n = 0
        var frames = 0
        var maxPerFrame = 0
        var dup = 0
        var aHi = 0.0
        var rawHi = 0.0
        var fdLo = 1e9
        var fdHi = 0.0
        var ancErr = 0.0
        var vrLo = 1e9
        var vrHi = 0.0
        var vN = 0
        var vT = 0
    }

    /**
     * 一帧 `WAKE_REC` 的**快照**（⚠️ [TripodFogPlan.wakeRecs] 是复用的对象池，R12 ⇒ ⛔ 把 rec 引用
     * 留到下一帧再用）。槽位：`0=X 1=rx 2=fx 3=k 4=a 5=wk 6=fd`。
     */
    private fun runWake(tier: TripodLevel, label: String, dt: Double): WakeRun {
        val n = lapN(dt)
        val savedTier = world.tier
        world.tier = tier
        val r = WakeRun(label)
        val side = WakeGeom.side
        val prev = HashMap<Int, DoubleArray>()
        try {
            for (i in 0..n) {
                val st = TripodGait.sceneState(world, i * dt)
                plan.build(st)
                val cur = HashMap<Int, DoubleArray>()
                val seen = HashSet<Int>()
                for (j in 0 until plan.wakeN) {
                    val x = plan.wakeRecs[j]
                    val key = x.i * 3 + (x.k + 1)
                    r.n++
                    cur[key] = doubleArrayOf(x.X, x.rx, x.fx, x.k.toDouble(), x.a, x.wk, x.fd)
                    // ⭐ 锚定误差：落笔点必须挂在**本只脚** + k·side·rx 上（⛔ 中心/均值锚定）
                    val e = abs(x.X - (x.fx + x.k * side * x.rx))
                    if (e > r.ancErr) r.ancErr = e
                    if (x.a > r.aHi) r.aHi = x.a
                    val raw = params.wakeA * x.wk           // 钳位**前**的原始值
                    if (raw > r.rawHi) r.rawHi = raw
                    if (x.fd < r.fdLo) r.fdLo = x.fd
                    if (x.fd > r.fdHi) r.fdHi = x.fd
                    if (!seen.add(key)) r.dup++
                }
                val c = seen.size
                if (c > r.maxPerFrame) r.maxPerFrame = c
                if (c > 0) r.frames++
                for ((key, p) in prev) {
                    val x = cur[key] ?: continue
                    val df = x[2] - p[2]
                    // ⚠️ 先**剥掉尺度项**（X − k·side·rx）再比位移：原始 ΔX/Δfx 是恒等式 ⇒ 恒红
                    val uc = x[0] - x[3] * side * x[1]
                    val up = p[0] - p[3] * side * p[1]
                    val dU = uc - up
                    r.vT++
                    if (abs(df) >= 2.0) {
                        val v = dU / df
                        r.vN++
                        if (v < r.vrLo) r.vrLo = v
                        if (v > r.vrHi) r.vrHi = v
                    }
                }
                prev.keys.retainAll(cur.keys)
                prev.putAll(cur)
            }
        } finally {
            world.tier = savedTier
        }
        return r
    }

    /** 原型 T31 的 `why(r, nn)`（⛔ 整层没落笔时让 fdLo/1e9、aHi/0 这些哨兵值冒充「判据越界」）。 */
    private fun wakeWhy(r: WakeRun, nn: Int): List<String> {
        val bad = ArrayList<String>()
        val frac = r.frames.toDouble() / (nn + 1)
        if (r.n == 0) {
            return listOf(
                "a/b/c/d/e/f 全圈 ${nn + 1} 帧（档 ${r.label}、强度 ${fmt(params.wakeA, 2)}）没有一处负压落笔" +
                    " ⇒ 判据无对象",
            )
        }
        if (!(r.ancErr <= 0.5)) {
            bad.add(
                "a 落笔点离\"本只脚 + k·side·rx\"最大偏 ${fmt(r.ancErr)} px > 0.5 ⇒ 雾脊不再挂在自己那只脚上" +
                    "（回到中心锚定的旧写法，贴近时会横着从两脚之间滑过去）",
            )
        }
        if (r.vN > 0 && !(r.vrLo >= 0.7 && r.vrHi <= 1.3)) {
            bad.add(
                "b 脊锚横向位移/脚横向位移 = ${fmt(r.vrLo, 2)}→${fmt(r.vrHi, 2)} 越出 [0.7, 1.3]" +
                    "（${r.vN}/${r.vT} 个帧对有横向脚速）⇒ 脊自己在滑，⛔ 跟着脚走",
            )
        }
        if (!(frac <= 0.60)) bad.add("c 全圈 ${fmt(frac * 100)}% 的帧在画负压 > 60% ⇒ 贴身段专属变成了常驻领圈")
        if (!(r.fdHi <= WakeGeom.d0 + 0.2)) {
            bad.add(
                "c 最远落笔距离 ${fmt(r.fdHi)} m > d0 ${fmt(WakeGeom.d0, 0)} m ⇒ 包络门被绕过，远处也在排雾",
            )
        }
        if (!(r.fdLo <= WakeGeom.d1 + 1)) {
            bad.add(
                "c 最近落笔距离 ${fmt(r.fdLo)} m > d1+1 ${fmt(WakeGeom.d1 + 1, 0)} m ⇒ 最强的一记没压在掠过那一瞬" +
                    "（d1 与最近位脱钩）",
            )
        }
        if (!(r.aHi >= 0.25 && r.aHi <= 0.32)) {
            bad.add("d 落笔峰值 α ${fmt(r.aHi, 3)} 越出 [0.25, 0.32] ⇒ 要么整层读不出来、要么压成一块白饼")
        }
        if (!(r.rawHi > 0.30)) {
            bad.add(
                "d 钳位前的原始值 wakeA·wk 一圈最大只有 ${fmt(r.rawHi, 3)} ≤ 0.30 ⇒ WAKE.cap 是死代码，" +
                    "\"不越过 cap\"没有任何东西在守",
            )
        }
        if (r.dup > 0) {
            bad.add("f 同帧重复落笔 (脚,侧) 组合 ${r.dup} 次 ⇒ 同一道脊画了两遍，画面不变而图元翻倍")
        }
        if (!(r.maxPerFrame <= 9)) {
            bad.add("f 单帧最多 ${r.maxPerFrame} 处落笔 > 9（3 脚 ×(2 脊+1 通道)）⇒ disjoint 图元超预算")
        }
        return bad
    }

    /** 原型 T31 的 `loBad(r)`：LOW 档必须**量出** 0 处（档纪律，R23 同规）。 */
    private fun wakeLowBad(r: WakeRun): List<String> =
        if (r.n > 0) {
            listOf("e LOW 档全圈落笔 ${r.n} 处 > 0 ⇒ 档纪律失效（低端机连第二层雾和暗角都省了，负压更不该例外）")
        } else {
            emptyList()
        }

    /**
     * 原型 T31 的 `neg(mut, restore, tag, tier, dt)`：改旋钮 → 跑一遍 → **先复位** → 再判
     * （⚠️ 复位后判才有意义：阈值本身被推走的负样本，判据读的是**恢复后**的 d0/d1/cap）。
     */
    private fun wakeNeg(
        tag: String,
        tier: TripodLevel,
        dt: Double,
        mut: () -> Unit,
        undo: () -> Unit,
    ): Pair<List<String>, Boolean> {
        val r: WakeRun = try {
            mut()
            runWake(tier, if (tier == TripodLevel.LOW) "LOW" else "MED", dt)
        } finally {
            undo()
        }
        val w = if (tier == TripodLevel.LOW) wakeLowBad(r) else wakeWhy(r, lapN(dt))
        return w to hasTag(w, tag)
    }

    /**
     * T31 掠过段负压（R25，裁决 = 留；判在 **V0** —— 三套剧本里唯一有贴身掠过段的那一套）。
     *
     * 原型实测（§十二 T31 行）：V0 全圈 **541 帧**（1/6 s）：MED 落笔 **672 处 / 111 帧（占 20.5%，判 ≤60%）**、
     * HIGH **672 处**、⭐ LOW **0 处**（档纪律）；落笔点离「本只脚 + `k·side·rx`」最大 **≤0.00 px**（判 ≤0.5）；
     * 细采样 1/12 s 下脊锚/脚横向位移比 **1.00→1.00**（判 ∈[0.7,1.3]，1290/1326 帧对）；落笔最近 **5.7 m**
     * （判 ≤13）、最远 **42.3 m**（判 ≤ `d0` 45）；峰值 α **0.300**（判 ∈[0.25,0.32]）；⭐ **钳位前最大原始值
     * 0.550 > 0.30**（判 `a ≤ cap` 是恒真式 ⇒ 必须判原始值真的越过 cap）；单帧最多 **9** 处、同帧重复 **0** 次。
     *
     * 负样本：**A** 锚→三脚均值 ⇒ a 偏 **328.3 px** + b 比 **−4.91→6.28**；**B** `slipK→0.5` ⇒ a 偏 **217.2 px**
     * + b **−0.15→1.15**；**C** `d0→400 m` ⇒ c **93.0%** + 最远 **238.5 m**；**D** `cap→0.90` ⇒ d 峰值 α **0.550**；
     * **E** `wakeA→0` ⇒ 一次都没落笔；**F** `tierK→0` ⇒ e LOW 全圈 **672 处 > 0**；**G** `dupK→2` ⇒ f 重复 **672** 次。
     * 两条恒真式陷阱（⛔ 犯）：判 `a ≤ cap`；拿**原始** `ΔX/Δfx` 比位移（`ΔX = Δfx + k·side·Δrx` 是恒等式）。
     */
    @Test
    fun t31_footWakeAnchoredPerFootAndGatedByDistance() {
        // ⭐ 整段钉在 V0：V2 最近位 73.6 m > d0，负压本来就不该在那套戏里出现
        withVariant0 {
            val n = lapN(wakeDt)          // 540 ⇒ 541 帧
            val nf = lapN(wakeFineDt)     // 1080 ⇒ 1081 帧
            val savedAnchorK = WakeGeom.anchorK
            val savedSlipK = WakeGeom.slipK
            val savedD0 = WakeGeom.d0
            val savedCap = WakeGeom.cap
            val savedWakeA = params.wakeA
            val savedTierK = WakeGeom.tierK
            val savedDupK = WakeGeom.dupK

            val med = runWake(TripodLevel.MEDIUM, "MED", wakeDt)
            val high = runWake(TripodLevel.HIGH, "HIGH", wakeDt)
            val low = runWake(TripodLevel.LOW, "LOW", wakeDt)
            val fast = runWake(TripodLevel.MEDIUM, "MED", wakeFineDt)
            val w0 = wakeWhy(med, n) + wakeWhy(high, n) + wakeLowBad(low) + wakeWhy(fast, nf)

            assertTrue(
                "T31 正样本判据未全绿：${whyText(w0)}｜MED ${med.n} 处/${med.frames} 帧" +
                    "（占 ${fmt(100.0 * med.frames / (n + 1))}%）、HIGH ${high.n} 处、LOW ${low.n} 处、" +
                    "锚定误差 ${fmt(med.ancErr, 2)} px、细采样位移比 ${fmt(fast.vrLo, 2)}→${fmt(fast.vrHi, 2)}" +
                    "（${fast.vN}/${fast.vT} 帧对）、最近 ${fmt(med.fdLo)} m、最远 ${fmt(med.fdHi)} m、" +
                    "峰值 α ${fmt(med.aHi, 3)}、钳位前原始值 ${fmt(med.rawHi, 3)}、单帧最多 ${med.maxPerFrame} 处、" +
                    "同帧重复 ${med.dup} 次",
                w0.isEmpty(),
            )
            // 反空判据 + 档纪律
            assertTrue("T31 无对象：MED 档一圈没有一处负压落笔", med.n > 0)
            assertTrue("T31 无对象：HIGH 档一圈没有一处负压落笔", high.n > 0)
            assertEquals("T31(e) LOW 档落笔 ${low.n} 处 > 0 ⇒ 档纪律失效", 0, low.n)
            assertTrue("T31(b) 无对象：细采样里没有任何帧对有横向脚速（fast.vN ${fast.vN}）", fast.vN > 0)

            // ── A 锚退回三脚均值（= R25 第一版的真实缺陷）──
            val nA = wakeNeg("a", TripodLevel.MEDIUM, wakeDt, { WakeGeom.anchorK = 1.0 }, { WakeGeom.anchorK = savedAnchorK })
            assertTrue("T31(A) 负样本假绿：均值锚定下 a 条判据没响（${whyText(nA.first)}）", nA.second)

            // ── B slipK→0.5（脊朝屏幕中心滑 = 「粘在屏幕上」）──
            val nB = wakeNeg("b", TripodLevel.MEDIUM, wakeFineDt, { WakeGeom.slipK = 0.5 }, { WakeGeom.slipK = savedSlipK })
            assertTrue("T31(B) 负样本假绿：slipK=0.5 下 b 条判据没响（${whyText(nB.first)}）", nB.second)

            // ── C d0→400 m（包络门拆掉，整圈常驻）──
            val nC = wakeNeg("c", TripodLevel.MEDIUM, wakeDt, { WakeGeom.d0 = 400.0 }, { WakeGeom.d0 = savedD0 })
            assertTrue("T31(C) 负样本假绿：d0=400 m 下 c 条判据没响（${whyText(nC.first)}）", nC.second)

            // ── D cap→0.90（钳位松开）──
            val nD = wakeNeg("d", TripodLevel.MEDIUM, wakeDt, { WakeGeom.cap = 0.90 }, { WakeGeom.cap = savedCap })
            assertTrue("T31(D) 负样本假绿：cap=0.90 下 d 条判据没响（${whyText(nD.first)}）", nD.second)

            // ── E wakeA→0（整层不落笔 ⇒ 只报那一条，⛔ 哨兵值刷屏）──
            val nE = wakeNeg("a/b/c/d/e/f", TripodLevel.MEDIUM, wakeDt, { params.wakeA = 0.0 }, { params.wakeA = savedWakeA })
            assertTrue("T31(E) 负样本假绿：wakeA=0 竟然还有落笔/判据空转（${whyText(nE.first)}）", nE.second)

            // ── F tierK→0（LOW 也画）──
            val nF = wakeNeg("e", TripodLevel.LOW, wakeDt, { WakeGeom.tierK = 0 }, { WakeGeom.tierK = savedTierK })
            assertTrue("T31(F) 负样本假绿：tierK=0 时 LOW 档仍 0 落笔（${whyText(nF.first)}）", nF.second)

            // ── G dupK→2（每只脚画两遍：画面不变而图元翻倍）──
            val nG = wakeNeg("f", TripodLevel.MEDIUM, wakeDt, { WakeGeom.dupK = 2 }, { WakeGeom.dupK = savedDupK })
            assertTrue("T31(G) 负样本假绿：dupK=2 下 f 条判据没响（${whyText(nG.first)}）", nG.second)

            // ── 复位自检 ──
            assertEquals(savedAnchorK, WakeGeom.anchorK, 1e-12)
            assertEquals(savedSlipK, WakeGeom.slipK, 1e-12)
            assertEquals(savedD0, WakeGeom.d0, 1e-12)
            assertEquals(savedCap, WakeGeom.cap, 1e-12)
            assertEquals(savedWakeA, params.wakeA, 1e-12)
            assertEquals(savedTierK.toLong(), WakeGeom.tierK.toLong())
            assertEquals(savedDupK.toLong(), WakeGeom.dupK.toLong())
            assertEquals(TripodLevel.MEDIUM, world.tier)
        }
    }

    // ══════════════════════════════════════ 共用：把整段判据钉在 V0 ══════════════════════════════════════

    /**
     * 原型 `withVariant(0, fn)`：进之前记下当前剧本的几份事实源，出去以后原样还原。
     * `boot()` 的默认剧本本就是 V0（`routeVar = -1` ⇒ `variantOf(0) = 0`），这里只是把「⛔ 拿 V2
     * 跑负压正样本 = 判据无对象」这件事写死在闸门里（与 T29 那份 `withVariant0` 同一形）。
     */
    private fun <T> withVariant0(fn: () -> T): T {
        val savedVar = world.curVar
        val savedFire0 = params.fireT0
        val savedFire1 = params.fireT1
        val savedGrab = params.grabT
        val savedYaw = params.yawAmp
        if (savedVar != 0) {
            TripodFogMath.applyVariant(world, 0, true)
            TripodFogMath.syncRouteEpoch(world, true)
        }
        try {
            return fn()
        } finally {
            params.fireT0 = savedFire0
            params.fireT1 = savedFire1
            params.grabT = savedGrab
            if (world.curVar != savedVar) {
                TripodFogMath.applyVariant(world, savedVar, true)
                TripodFogMath.setYawAmp(world, savedYaw)
                TripodFogMath.syncRouteEpoch(world, true)
            }
        }
    }
}
