package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * E46「迷雾之战 · 雾中三脚巨影」K8 §十五 的**光柱 / 火星 / 落点**闸门：
 * §十二 的 **T17（火星接缝·形态·锚点）、T18（掠过—揭示—绕后剧本）、T23（落点只剩贴地亮斑）、
 * T29（光柱前端无光 / 亮斑逐帧成对 / 火星密度随 emberN）、T30（发射口无亮帽）**，
 * 逐条移植自原型 `docs/archive/e46-tripod-fog-preview.html` 的 `selfTest()`
 * （T17 :3279、T18 :3412、T23 :4040、T29 :4965、T30 :4115）。
 *
 * ## 三条共同的纪律
 * - ⛔ 像素回读：JVM 侧 `Bitmap` 回读要么 `not mocked` 要么恒空 ⇒ 永远绿的假闸门（§十四 1）。
 *   本文件全部走**发射器自己写下的那份记录**：`plan.emb / plan.anchor`（ANCH 七元组）/
 *   `plan.beamRecs`（BEAM_REC）/ `plan.dustN` / `plan.profOps[TripodOpItem.ordinal]` /
 *   `plan.muzzleOps`，以及 `TripodGait.sceneState` 的解析量 + `TripodFogMath.pr()` 的投影。
 * - R12：`sceneState()` 的 `legs/bldMid/bldNear/crowd` 与 `plan` 的每条定容数组都是
 *   **跨帧复用的缓冲** ⇒ 需要跨帧比较的一律当场摘抄（[embField] / [catsAt] / [emberAnchorSig] 抄出，
 *   [measureT18] 逐帧即判）。
 * - 每条负样本各配**自己的**旋钮（`EmGeom.anchor/cap/seedVar`、`BmGeom.muzzle/pool`、
 *   `DustGeom.on/tierK`、`PoolGeom.sq`、`P.emberW/beamFoot/beamPeakAt/beamHalfNear/beamI/
 *   escAmp/yawAmp/tiltMax/fireT0/fireT1/fireNear`），`try/finally` 还原 + 还原自检
 *   （⚠️ `EmGeom/DustGeom/BmGeom/PoolGeom` 是 `internal object` 单例 ⇒ 漏还原会污染
 *   同一个 JVM 里后面的闸门）。
 *
 * ## ⚠️ 生产钩子缺口（T17 的 c 项「划长多样」与负样本 B/C、见该测试 KDoc）
 * `TripodFogPlan` 的每粒火星参数表 `dA/dPh/dPer/dRise/dSpread/dLen/dBr` 与 `dustCount` 全是
 * `private val/var` ⇒ 闸门既读不到 `dLen`（原型 `nLen` 数的是**参数表里不同的划长个数**，
 * 屏幕上 `dx,dy` 的模长还额外乘了 `q.s` 与 `(0.45+0.55(1−u))`，改判它就是换口径），
 * 也注入不进「per→7」「全部同周期同划长」这两条负样本。按简报「缺钩子就报告、⛔ 自己造一条
 * 近似判据」的规矩，这两项留在报告里等主会话加钩子（`internal fun emberParam(i, which)` 或
 * 把这七张表改 `internal` 即可），⛔ 在测试里重跑一遍 `Lcg(4242)` 复刻参数表（E44 D24 的
 * 「两套口径分叉」）。寿命周期那半条（b 项整除 + c 项的 `nPer`）改从 `ANCH` 的 `tb`
 * **换代跳变** + 当帧年龄 `mod(t−tb, LOOP)` 的**上界**反解（两者与公开的
 * `TripodFogPlan.EMBER_PER` 三重自洽才算确认，见 [scanGap]），读的还是画面自己写下那份。
 */
internal class TripodFogBeamGatesTest {

    private lateinit var world: TripodFogWorld
    private lateinit var table: TripodGateBmpTable
    private lateinit var plan: TripodFogPlan

    @Before
    fun setup() {
        world = TripodGateFixtures.boot()
        table = TripodGateBmpTable()
        // ⚠️ 48 栋烘焙位图必须按**对象身份**登记：查不到槽位时 blit() 连记账一起跳过
        //    ⇒ 建筑层的图元数会读到 0 =「没采到」被读成「没落笔」（§十四 1 的 Kotlin 形态）。
        for (b in world.bMid) b.bmp?.let { table.put(it) }
        for (b in world.bNear) b.bmp?.let { table.put(it) }
        plan = TripodFogPlan(world, table)
    }

    private fun fmt(x: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", x)

    /** 原型的 `x.toExponential(1)` ⇒ 科学计数法（断言消息里的「6.1e-13 px」这类微增量）。 */
    private fun ex(x: Double): String = String.format(Locale.ROOT, "%.1e", x)

    // ══════════════════════════ 共享口径：逐帧图元台账（原型 catsAt / catOps）══════════════════════════

    /**
     * 原型共享口径 `catsAt(t, tier)` 返回的那份 PROF 的 Kotlin 形态：跑**一帧完整 `build()`**
     * 并把该帧的台账与记录抄出来（数组跨帧复用 ⇒ 必须抄，R12）。
     *
     * ⚠️ 档位临时改写 `world.tier`（火星只在 HIGH 落笔、LOW 不画 B 层雾与 grade），
     * 跑完立刻还原 —— 原型 `catsAt` 换入换出 `PROF/OPS/FILL + P.tier` 是同一件事。
     */
    private class FrameCats {
        val ops = IntArray(TripodFogPlan.PROF_N)
        var muzzleOps = 0
        var dustN = 0
        var beamN = 0
        var embN = 0
        val poolOn = BooleanArray(2)
        val sq = DoubleArray(2)
        val rr = DoubleArray(2)
        val footRatio = DoubleArray(2)
        val peakAt = DoubleArray(2)
        val nearM = DoubleArray(2)
        val aPeak = DoubleArray(2)
        val beamI = DoubleArray(2)

        /** 原型 `catOps(c, '光束柱体')`。 */
        fun columnOps(): Int = ops[TripodOpItem.BEAM_COLUMN.ordinal]

        /** 原型 `catOps(c, '地面落点亮斑')`。 */
        fun poolOps(): Int = ops[TripodOpItem.BEAM_POOL.ordinal]

        /** 原型 `catOps(c, '火星短划')`。 */
        fun emberOps(): Int = ops[TripodOpItem.EMBER.ordinal]

        /** 原型 `catOps(c, '光束发射口')`（Kotlin 侧没有 `TripodOpItem` 行 ⇒ 独立计数器）。 */
        fun muzzleOps(): Int = muzzleOps

        /**
         * 原型 `catOps(c, '命中扬尘')` 的 Kotlin 等价：**落点那一坨里超出亮斑的那几笔**。
         * ⚠️ 发射器把尘球记进 [TripodOpItem.BEAM_POOL] 那一行（「尘球与亮斑同属落点那一坨」），
         * 而亮斑与柱体逐笔成对（同一个 `j` 分支）⇒ 多出来的就是尘球落的笔。
         */
        fun dustOps(): Int = max(0, poolOps() - columnOps())
    }

    private fun catsAt(t: Double, tier: TripodLevel): FrameCats {
        val saved = world.tier
        world.tier = tier
        try {
            plan.build(TripodGait.sceneState(world, t))
            val c = FrameCats()
            System.arraycopy(plan.profOps, 0, c.ops, 0, TripodFogPlan.PROF_N)
            c.muzzleOps = plan.muzzleOps
            c.dustN = plan.dustN
            c.beamN = plan.beamN
            c.embN = plan.embN
            val n = min(2, plan.beamN)
            for (i in 0 until n) {
                val r = plan.beamRecs[i]
                c.poolOn[i] = r.poolOn
                c.sq[i] = r.sq
                c.rr[i] = r.rr
                c.footRatio[i] = r.footRatio
                c.peakAt[i] = r.peakAt
                c.nearM[i] = r.nearM
                c.aPeak[i] = r.aPeak
                c.beamI[i] = r.beamI
            }
            return c
        } finally {
            world.tier = saved
        }
    }

    /** 原型 `has(w, tag)`：负样本必须咬住**自己那条标号**（R30 的教训：一条假红能糊弄三条判据）。 */
    private fun hasTag(w: List<String>, tag: String): Boolean = w.any { it.startsWith("$tag ") }

    private fun joinW(w: List<String>): String =
        if (w.isEmpty()) "⚠️ 假负样本（判据空转）" else w.joinToString("；").take(220)

    /**
     * 原型 `withVariant(0, fn)`：整段钉在 V0 并**精确还原**（三套剧本的开火窗不同，
     * 这里要印的是能写进方案文档的定数）。`boot()` 落在 epoch 0 ⇒ `variantOf(0) = 0`，
     * 正常路径下这里不换戏；真的换了就跑完 `applyVariant(原戏, true) + syncRouteEpoch(true)` 复位。
     */
    private fun <T> withVariant0(fn: () -> T): T {
        val p = world.params
        val savedVar = world.curVar
        val savedT0 = p.fireT0
        val savedT1 = p.fireT1
        val savedGrab = p.grabT
        val savedYaw = p.yawAmp
        if (savedVar != 0) {
            TripodFogMath.applyVariant(world, 0, true)
            TripodFogMath.syncRouteEpoch(world, true)
        }
        try {
            return fn()
        } finally {
            if (savedVar != 0) {
                TripodFogMath.applyVariant(world, savedVar, true)
                TripodFogMath.syncRouteEpoch(world, true)
            }
            p.fireT0 = savedT0
            p.fireT1 = savedT1
            p.grabT = savedGrab
            if (p.yawAmp != savedYaw) TripodFogMath.setYawAmp(world, savedYaw)
        }
    }

    // ══════════════════════════════════════ T17 火星接缝 / 形态 / 锚点 ══════════════════════════════════════

    /** 一圈扫帧步长与四条限值（⚠️ 这些是**闸门判据**，原型同值，⛔ 生产码里的常数表）。 */
    private companion object {
        const val LIM_SEAM = 1e-6
        const val LIM_ASPECT = 4.0
        const val LIM_N = 8
        const val LIM_GAP = 18.0
        const val LIM_SPD = 4.0
        const val LIM_KINDS = 4
        const val GSTEP = 0.5
        const val DT_T18 = 0.05
        const val DT_CATS = 1.0 / 6.0
        const val ND_DENS = 180
    }

    /** 原型 T17 的 `field(t)`：跑一帧，把 `EMB` 整条抄出来（⛔ 下一条指令就覆写同一份数组）。 */
    private fun embField(t: Double): DoubleArray {
        plan.build(TripodGait.sceneState(world, t))
        return plan.emb.copyOf(plan.embN * 6)
    }

    /** 原型 T17 的 `scanGap()` 输出（世界系两项锚定量 + 反解出的寿命周期）。 */
    private class T17Scan {
        var gapMin = 0.0
        var spdMax = 0.0
        var nRec = 0
        var nSpark = 0
        var records = 0
        var frames = 0

        /** 反解**确认**到寿命周期的粒数（见 [scanGap] 的三重自洽条件）。 */
        var derived = 0

        /** 观察到过换代跳变、但确认不出周期的粒数（跨代漏采样 ⇒ 跳变是周期的 2 倍以上）。 */
        var unconfirmed = 0

        /** 确认到的周期种类数（= 原型 `nPer`）。 */
        var kinds = 0
        var perOk = true
    }

    /**
     * 原型 T17 的 `scanGap()`：走完一圈（`t ∈ [0, LOOP)`，步长 0.5 s = 180 帧），逐帧读 `ANCH`，
     * 对每粒火星（`k = si·2 + bi`，两道光各一个锚点）量三件事：
     * ① 与**当帧光脚**（`fx/fz` = `st.beams[bi]` 的落点）的间距取**一生里的最大值** ——
     *    钉在出生点 ⇒ 光脚扫到 34 m 外，间距必然一路涨；跟着光脚跑 ⇒ 间距恒等于出射外扩；
     * ② 自身速度（相邻记录之间的位移 / GSTEP，只在「出生时刻连续」`|Δtb| ≤ GSTEP·1.5` 时计）；
     * ③ ⭐ 寿命周期：发射器写的是 `back = u·per`、`tb = mod(t − back, LOOP)` ⇒
     *    同一代里 `tb` **恒定**、换代时正好跳 `per`，而**当帧年龄** `back = mod(t − tb, LOOP)`
     *    在换代前最后一帧取到 `per − δ`（`δ ≤ GSTEP`）。于是每粒的周期要**三重自洽**才算确认：
     *    `跳变最小值 = per`、`backMax < per ≤ backMax + GSTEP`、`per ∈ TripodFogPlan.EMBER_PER`
     *    （那张表是公开的 `companion val`，⛔ 在这里重跑 `Lcg(4242)` 复刻私有表）。
     *    ⚠️ 只看到「跨两代」跳变（`2·per`，暗段里那代没落笔）的粒 = **确认不了**，计入
     *    [T17Scan.unconfirmed] 而不是拿 `2·per` 去比整除 —— 那会把绿判成红（虚假缺陷）。
     */
    private fun scanGap(): T17Scan {
        val p = world.params
        val loop = p.loop
        val m = T17Scan()
        val nSpark = min(p.emberN, TripodFogPlan.EM_MAX)
        m.nSpark = nSpark
        val ng = nSpark * 2
        val gap = DoubleArray(ng)
        val spd = DoubleArray(ng)
        val cnt = IntArray(ng)
        val backMax = DoubleArray(ng)
        val jumpMin = DoubleArray(ng) { Double.POSITIVE_INFINITY }
        val jumpN = IntArray(ng)
        val prevTb = DoubleArray(ng)
        val prevX = DoubleArray(ng)
        val prevZ = DoubleArray(ng)
        val prevIdx = IntArray(ng) { -1 }
        val hasPrev = BooleanArray(ng)
        var idx = 0
        var records = 0
        var t = 0.0
        while (t < loop) {
            plan.build(TripodGait.sceneState(world, t))
            val a = plan.anchor
            val n = plan.anchorN
            records += n
            var i = 0
            while (i < n) {
                val o = i * 7
                val k = (a[o] * 2 + a[o + 1]).toInt()
                val tb = a[o + 2]
                val sx = a[o + 3]
                val sz = a[o + 4]
                if (k in 0 until ng) {
                    gap[k] = max(gap[k], hypot(sx - a[o + 5], sz - a[o + 6]))
                    backMax[k] = max(backMax[k], TripodFogMath.mod(t - tb, loop))
                    if (hasPrev[k] && abs(prevTb[k] - tb) <= GSTEP * 1.5) {
                        spd[k] = max(spd[k], hypot(sx - prevX[k], sz - prevZ[k]) / GSTEP)
                    }
                    if (prevIdx[k] == idx - 1) {
                        val delta = TripodFogMath.mod(tb - prevTb[k] + loop / 2, loop) - loop / 2
                        if (delta > 1.0) {
                            jumpMin[k] = min(jumpMin[k], delta)
                            jumpN[k]++
                        }
                    }
                    prevTb[k] = tb
                    prevX[k] = sx
                    prevZ[k] = sz
                    prevIdx[k] = idx
                    hasPrev[k] = true
                    cnt[k]++
                }
                i++
            }
            idx++
            m.frames++
            t += GSTEP
        }
        m.records = records
        var liveN = 0
        for (k in 0 until ng) if (cnt[k] > 1) liveN++
        m.nRec = liveN / 2
        var gapMin = Double.POSITIVE_INFINITY
        var spdMax = 0.0
        val kinds = HashSet<Double>()
        var derived = 0
        var unconfirmed = 0
        var perOk = true
        val table = TripodFogPlan.EMBER_PER
        for (k in 0 until ng) {
            if (cnt[k] <= 1) continue
            gapMin = min(gapMin, gap[k])
            spdMax = max(spdMax, spd[k])
            if (jumpN[k] == 0) continue
            val per = jumpMin[k]
            // 三重自洽：跳变 = 年龄上界给出的区间 = 公开表里的某个值
            var inTable = false
            for (v in table) if (abs(v - per) < 1e-6) inTable = true
            val bracketed = backMax[k] < per && per <= backMax[k] + GSTEP
            if (!inTable || !bracketed) {
                unconfirmed++
                continue
            }
            derived++
            val ratio = loop / per
            if (abs(ratio - ratio.roundToInt().toDouble()) > 1e-6) perOk = false
            kinds.add((per * 1000.0).roundToInt() / 1000.0)
        }
        m.gapMin = if (gapMin.isInfinite()) 0.0 else gapMin
        m.spdMax = spdMax
        m.derived = derived
        m.unconfirmed = unconfirmed
        m.kinds = kinds.size
        m.perOk = perOk
        return m
    }

    /** 原型 T17 的 `judge(g)`（`g == null` = 负样本 B/C/D 那一类不跑 180 帧扫描的调用）。 */
    private class T17Result(val seam: Double, val minAspect: Double, val n: Int, val g: T17Scan?) {
        val why = ArrayList<String>()
    }

    private fun judgeT17(g: T17Scan?): T17Result {
        val p = world.params
        val a = embField(0.0)
        val b = embField(p.loop)
        val unequal = a.size != b.size
        var seam = if (unequal) Double.POSITIVE_INFINITY else 0.0
        val lim = min(a.size, b.size)
        var i = 0
        while (i < lim) {
            seam = max(seam, abs(a[i] - b[i]))
            i++
        }
        var aspect = Double.POSITIVE_INFINITY
        i = 0
        while (i + 5 < a.size) {
            aspect = min(aspect, hypot(a[i + 2], a[i + 3]) / a[i + 4])
            i += 6
        }
        val r = T17Result(seam, aspect, a.size / 6, g)
        if (seam > LIM_SEAM) {
            r.why.add(
                "a 接缝跳变 " +
                    if (unequal) "t=0/t=LOOP 条数不等（${a.size / 6} vs ${b.size / 6}）"
                    else "${ex(seam)} px > $LIM_SEAM"
            )
        }
        if (aspect < LIM_ASPECT) r.why.add("d 最小长宽比 ${fmt(aspect, 2)} < $LIM_ASPECT")
        if (r.n < LIM_N) r.why.add("接缝帧只有 ${r.n} 道火星（判据空转）")
        if (g != null) {
            // ⚠️ b/c 走反解周期 ⇒ `anchor=2`（恒定 3 s 回溯，`tb` 从不换代）时 `derived = 0`，
            //    这两条**结构上不适用**（原型那边它们读的是与锚点无关的参数表，同样不会红）。
            if (g.derived > 0 && !g.perOk) r.why.add("b 存在不整除 LOOP=${fmt(p.loop, 0)} s 的寿命周期")
            if (g.derived >= LIM_N && g.kinds < LIM_KINDS) {
                r.why.add("c 只有 ${g.kinds} 种寿命周期（反解出 ${g.derived} 粒，判 ≥$LIM_KINDS 种）")
            }
            if (g.nRec < g.nSpark) {
                r.why.add("e 只有 ${g.nRec}/${g.nSpark} 粒火星在一圈里连续落笔（判据空转）")
            }
            if (g.gapMin < LIM_GAP) {
                r.why.add(
                    "e 锚定不足：最弱一粒一生里离当帧光脚的最大间距只有 ${fmt(g.gapMin)} m < " +
                        "$LIM_GAP ⇒ 它还在跟着光柱跑（上限 = 出射外扩 spread）"
                )
            }
            if (g.spdMax > LIM_SPD) {
                r.why.add(
                    "e′ 世界系速度 ${fmt(g.spdMax, 2)} m/s > $LIM_SPD ⇒ 火星在奔，不是在原地等消失" +
                        "（锚定时只剩抛物线 ≈ 2.5 m/s）"
                )
            }
        }
        return r
    }

    /**
     * **T17**：火星接缝、形态与锚点（R15 + R27）—— `t=0` 与 `t=LOOP` 逐元素同 + 周期整除 +
     * 周期多样 + 长宽比 ≥ 4 + 钉在出生那一刻的落点上。
     *
     * 实测（§十二 T17 行）：接缝帧 **30** 道火星，逐元素最大差 **6.1e-13 px ≤ 1e-6**；
     * **4** 种上升周期 / 24 种划长；最小长宽比 **12.5 ≥ 4**（圆泡 = 1）；一圈 **180 帧 /
     * 6032 条**锚点记录，**24/24** 粒连续落笔，最弱一粒离当帧光脚 **80.9 m ≥ 18 m**；
     * 世界系最大速度 **1.79 m/s ≤ 4**（横扫光脚 ≈ 21 m/s）。
     * 负样本：**A** `EM.anchor=0`（退回 R15 的「当帧光脚」）⇒ 锚定只有 **3.9 m**、速度 **92.9 m/s**；
     * **D** `P.emberW=0.8` ⇒ 最小长宽比 **1.88 < 4**；**E′** `EM.anchor=2`（滞后 3 s 的半成品，
     * `e` 抓不到）⇒ 速度 **92.9 m/s > 4**。
     * ⛔ 移植不动的两项（缺生产钩子，见类 KDoc）：**B**（某粒 `per→7`，不整除 90）与
     * **C**（全部同周期同划长）要改 `dPer/dLen`，**c 项的 `nLen ≥ 4`** 要读 `dLen`。
     *
     * ⚠️ b/c 两条走反解 ⇒ 假设「一圈里至少有 8 粒火星的两代连着一同开火」（`derived ≥ $LIM_N`
     * 就是这条假设自己的反空转检查）。真红的话先看消息里的「周期确认 N 粒/跨代 M 粒」：
     * M 大 = 火星在暗段里跳代（假设不成立，⚠️ 要改的是**扫描口径**，⛔ 把 `LIM_N` 调小），
     * kinds 少于 4 = 某种周期的火星全圈没出现（R30 的密度改过 `EMBER_PER` 分配就会这样）。
     */
    @Test
    fun t17_emberSeamShapeAndAnchor() {
        val p = world.params
        val savedTier = world.tier
        world.tier = TripodLevel.HIGH // ⚠️ 火星只在 HIGH 档画（原型同句 `P.tier = 'HIGH'`）
        try {
            val gPos = scanGap()
            val pos = judgeT17(gPos)
            assertTrue("T17 反解寿命周期只覆盖 ${gPos.derived} 粒（判 ≥$LIM_N，判据空转）", gPos.derived >= LIM_N)

            // A 是这条判据的正身：锚点退回「当帧光脚」（R15 的手环写法）。
            //   ⚠️ a/d/n 三条在 anchor=0 下一项都不会红 —— 跟着光柱跑仍然是 t 的纯函数。
            val savedAnchor = EmGeom.anchor
            val negA: T17Result
            try {
                EmGeom.anchor = 0
                negA = judgeT17(scanGap())
            } finally {
                EmGeom.anchor = savedAnchor
            }
            // D 线宽 0.8 m（圆泡）：原型 `judge()` 不带扫描，跑的就是 a/d/n 三条。
            val savedW = p.emberW
            val negD: T17Result
            try {
                p.emberW = 0.8
                negD = judgeT17(null)
            } finally {
                p.emberW = savedW
            }
            // E′ `anchor=2`（滞后 3 s，每帧重新锚一次的半成品）：e 抓不到，只有 e′ 抓得到。
            val savedAnchor2 = EmGeom.anchor
            val negE: T17Result
            try {
                EmGeom.anchor = 2
                negE = judgeT17(scanGap())
            } finally {
                EmGeom.anchor = savedAnchor2
            }

            assertTrue("T17 正样本：${joinW(pos.why)}（接缝差 ${ex(pos.seam)} px、" +
                "长宽比 ${fmt(pos.minAspect, 1)}、${pos.n} 道、${gPos.records} 条锚点记录、" +
                "${gPos.nRec}/${gPos.nSpark} 粒、周期确认 ${gPos.derived} 粒/跨代 ${gPos.unconfirmed} 粒、" +
                "${gPos.kinds} 种周期、最弱间距 ${fmt(gPos.gapMin)} m、" +
                "最大速度 ${fmt(gPos.spdMax, 2)} m/s）", pos.why.isEmpty())
            assertTrue("T17(b/c) 反解出的周期种类 ${gPos.kinds} 种、整除 LOOP = ${gPos.perOk}" +
                "（§十二：4 种上升周期）", gPos.perOk && gPos.kinds >= LIM_KINDS)
            assertTrue("负样本 A（锚点=当帧光脚）没咬红：${joinW(negA.why)}", negA.why.isNotEmpty())
            assertTrue("负样本 A 必须踩 e/e′（实测 3.9 m 与 92.9 m/s）：${joinW(negA.why)}",
                hasTag(negA.why, "e") && negA.why.any { it.startsWith("e′") })
            assertTrue("负样本 D（线宽 0.8 m）没咬红：${joinW(negD.why)}", hasTag(negD.why, "d"))
            assertTrue("负样本 E′（锚点滞后 3 s）没踩到 e′：${joinW(negE.why)}",
                negE.why.any { it.startsWith("e′") })
            assertTrue("负样本 E′ 必须是 e′ 的**独有**负样本（a/d/e 都得放过它）：${joinW(negE.why)}",
                negE.why.none { it.startsWith("a ") || it.startsWith("d ") || it.startsWith("e ") })

            // 还原自检：旋钮不许留副作用（单例对象跨测试共用）。
            assertEquals(1, EmGeom.anchor)
            assertEquals(0.12, p.emberW, 1e-12)
            val back = judgeT17(scanGap())
            assertTrue("还原后又量到跳变/锚定不足：${joinW(back.why)}", back.why.isEmpty())
        } finally {
            world.tier = savedTier
        }
        assertEquals(TripodLevel.MEDIUM, world.tier)
    }

    // ══════════════════════════════════ T18 掠过—揭示—绕后剧本 ══════════════════════════════════════

    /** 原型 T18 的 `measure()` 输出。 */
    private class T18Meas {
        var aMin = Double.POSITIVE_INFINITY
        var aT = 0.0
        var aCrop = 0.0
        var runMax = 0
        var runA = 0.0
        var runB = 0.0
        var runF = 0
        var runFA = 0.0
        var runFB = 0.0
        var fracMin = 1.0
        var dLo = Double.POSITIVE_INFINITY
        var dHi = Double.NEGATIVE_INFINITY
        var cN = 0
        var cBad = 0
        val cWhich = IntArray(5)
        var fMax = 0.0
        var eMax = 0
        var eA = 0.0
        var eB = 0.0
    }

    /** 原型 `hFracOf`：整机投影（屏）—— >1 = 被裁。 */
    private fun hFracOf(st: TripodScene): Double {
        val p = world.params
        val cam = world.cam
        return TripodFogMath.topY(p) * cam.ppm * (p.fz / (p.fz + st.dist)) / cam.h
    }

    /** 原型 `litOf`：剪影落在背景上的对比度 ≥ 5%（与 T7/T3 同一口径）。 */
    private fun litOf(st: TripodScene): Boolean = TripodGateFixtures.visibleEnough(world, st) >= 0.05

    /** 原型 `bodyOf`。 */
    private fun bodyOf(st: TripodScene): BodyPt =
        TripodFogMath.bodyFrame(0.0, 0.5, st.bodyY, st.psi, st.d, st.mx)

    /** 原型 `onFracOf`：整机竖直投影落在视口内的比例（含 tilt 之后的 `hz`）。 */
    private fun onFracOf(st: TripodScene): Double {
        val p = world.params
        val cam = world.cam
        cam.hz = TripodGateFixtures.hzBase(world) + st.tilt
        val bc = bodyOf(st)
        val tp = TripodFogMath.pr(cam, p, bc.x, bc.y + (TripodFogMath.topY(p) - p.bodyY), bc.z, st.yaw)
        val ft = TripodFogMath.pr(cam, p, bc.x, 0.0, bc.z, st.yaw)
        if (tp == null || ft == null) return 0.0
        val lo = min(tp.Y, ft.Y)
        val hi = max(tp.Y, ft.Y)
        if (hi - lo <= 0.0) return 0.0
        return (min(hi, cam.h) - max(lo, 0.0)) / (hi - lo)
    }

    /**
     * 原型 `shownIdxOf`：⛔ 只判机身中心 —— R17 让机器贴身绕后，10 m 处 15 m 的胯宽与机身纵深
     * 同量级 ⇒ 机身已被 `pr()` 剔除、脚钉却还在相机前方，画面里凭空多出一条几十米高的巨腿。
     * 返回露馅部件下标（0 机身 / 1 机顶 / 2…4 三只脚钉），全不可见返回 −1。
     */
    private fun shownIdxOf(st: TripodScene): Int {
        if (!litOf(st)) return -1
        val p = world.params
        val cam = world.cam
        val bc = bodyOf(st)
        val top = TripodFogMath.topY(p) - p.bodyY
        val xs = doubleArrayOf(bc.x, bc.x, st.legs[0].fx, st.legs[1].fx, st.legs[2].fx)
        val ys = doubleArrayOf(bc.y, bc.y + top, 0.0, 0.0, 0.0)
        val zs = doubleArrayOf(bc.z, bc.z, st.legs[0].fz, st.legs[1].fz, st.legs[2].fz)
        for (i in 0 until 5) {
            val c = TripodFogMath.pr(cam, p, xs[i], ys[i], zs[i], st.yaw) ?: continue
            if (abs(c.X - cam.cx) <= cam.w * 0.5 + 40 && c.Y >= -40 && c.Y <= cam.h + 40) return i
        }
        return -1
    }

    /** 原型 `reveal`：在镜头背后（`d < 0`）+ 25…80 m + 看得见 + 横向居中 ±15% 宽 + 在画 ≥70%。 */
    private fun revealOf(st: TripodScene): Boolean {
        val p = world.params
        val cam = world.cam
        if (!(st.d < 0 && st.dist >= 25 && st.dist <= 80) || !litOf(st)) return false
        val bc = bodyOf(st)
        val tp = TripodFogMath.pr(cam, p, bc.x, bc.y + (TripodFogMath.topY(p) - p.bodyY), bc.z, st.yaw)
            ?: return false
        return abs(tp.X - cam.cx) <= cam.w * 0.15 && onFracOf(st) >= 0.7
    }

    /** 原型 `full`：⭐ b2「含机顶整机完整入画」—— 竖直视场只有 ±11.6° ⇒ 机顶入画的最近距离 ≈ 70 m。 */
    private fun fullOf(st: TripodScene): Boolean = revealOf(st) && onFracOf(st) >= 0.995

    /** 原型 `anyContentOf`：「盲跑段」= 画面里**既没有机器也没有任何一栋楼**（建筑判据与绘制同源）。 */
    private fun anyContentOf(st: TripodScene): Boolean {
        if (shownIdxOf(st) >= 0) return true
        if (bandHasContent(st.bldMid, st.bldMidN)) return true
        return bandHasContent(st.bldNear, st.bldNearN)
    }

    private fun bandHasContent(arr: Array<BldSel>, n: Int): Boolean {
        val p = world.params
        val cam = world.cam
        for (i in 0 until n) {
            val b = arr[i]
            if (b.a <= 0.002 || b.z < 2) continue
            val p0 = TripodFogMath.pr(cam, p, b.x, 0.0, b.z, lastYaw) ?: continue
            val p1 = TripodFogMath.pr(cam, p, b.x, b.h, b.z, lastYaw) ?: continue
            val wpx = b.w * cam.ppm * p0.s
            val x = p0.X - wpx / 2
            val hh = p0.Y - p1.Y
            if (x + wpx < -20 || x > cam.w + 20 || hh < 0.5) continue
            val ink = TripodFogMath.clamp(
                TripodFogMath.shadeA(hypot(b.x, b.z), lastD0, p) * b.a * b.ink, 0.0, 1.0
            )
            if (ink >= 0.004) return true
        }
        return false
    }

    /** ⚠️ `bandHasContent` 借用的一对当帧参数（⛔ 传参只为少一层，逐帧由 [measureT18] 刷新）。 */
    private var lastYaw = 0.0
    private var lastD0 = 0.0

    /** 原型 T18 的 `measure()`：一圈 1801 帧（DT=0.05，⚠️ 掠过/揭示要按亚帧找极值，0.1 s 会漏）。 */
    private fun measureT18(): T18Meas {
        val p = world.params
        val cam = world.cam
        val t1 = world.tcross.t1
        val t2 = world.tcross.t2
        val m = T18Meas()
        var cur = 0
        var bA = 0.0
        var curF = 0
        var bF = 0.0
        var curE = 0
        var bE = 0.0
        var t = 0.0
        while (t <= p.loop) {
            val st = TripodGait.sceneState(world, t)
            cam.hz = TripodGateFixtures.hzBase(world) + st.tilt // ⭐ 原型 LAND_HZ：与 build() 同一处落地
            lastYaw = st.yaw
            lastD0 = st.d0
            if (t >= t1 - 4 && t <= t1 + 4) {
                if (st.dist < m.aMin) {
                    m.aMin = st.dist
                    m.aT = t
                }
                m.aCrop = max(m.aCrop, hFracOf(st))
            }
            if (abs(st.d) <= 12 && t > t1 + 5) {
                m.cN++
                val si = shownIdxOf(st)
                if (si >= 0) {
                    m.cBad++
                    m.cWhich[si]++
                }
            }
            if (t >= t1 - 2 && t <= t2 + 2) m.fMax = max(m.fMax, st.fire)
            if (revealOf(st)) {
                if (cur == 0) bA = t
                cur++
                if (cur > m.runMax) {
                    m.runMax = cur
                    m.runA = bA
                    m.runB = t
                }
                m.fracMin = min(m.fracMin, onFracOf(st))
                m.dLo = min(m.dLo, st.dist)
                m.dHi = max(m.dHi, st.dist)
            } else {
                cur = 0
            }
            if (fullOf(st)) {
                if (curF == 0) bF = t
                curF++
                if (curF > m.runF) {
                    m.runF = curF
                    m.runFA = bF
                    m.runFB = t
                }
            } else {
                curF = 0
            }
            if (anyContentOf(st)) {
                curE = 0
            } else {
                if (curE == 0) bE = t
                curE++
                if (curE > m.eMax) {
                    m.eMax = curE
                    m.eA = bE
                    m.eB = t
                }
            }
            t += DT_T18
        }
        cam.hz = TripodGateFixtures.hzBase(world)
        return m
    }

    /** 原型 T18 的 `why(p)`（十条，逐字同口径）。 */
    private fun whyT18(p: T18Meas): List<String> {
        val t1 = world.tcross.t1
        val w = ArrayList<String>()
        if (!(p.aMin >= 9 && p.aMin <= 16)) w.add("a 掠过窗内真实距离 ${fmt(p.aMin, 2)} m ∉ 9~16 m 贴身带")
        if (!(p.aCrop > 1)) w.add("a 掠过时整机投影 ${fmt(p.aCrop, 2)} 屏 ≤ 1 ⇒ 没有被裁 = 俯瞰视角")
        if (!(p.runMax * DT_T18 >= 1)) w.add("b 回头揭示最长连续居中段 ${fmt(p.runMax * DT_T18, 2)} s < 1 s")
        if (!(p.fracMin >= 0.7)) w.add("b 揭示段整机在画面内最低 ${fmt(p.fracMin * 100, 0)}% < 70%")
        if (!(p.runF * DT_T18 >= 1)) {
            w.add("b2 揭示段内“含机顶整机完整入画”最长连续 ${fmt(p.runF * DT_T18, 2)} s < 1 s ⇒ " +
                "只看见腹部与腿，认不出那是机器")
        }
        if (!(p.cN * DT_T18 >= 0.5)) w.add("c 穿越②窗只覆盖 ${fmt(p.cN * DT_T18, 2)} s（判据空转）")
        if (p.cBad != 0) {
            w.add("c 绕后有 ${p.cBad}/${p.cN} 帧部件仍可见（部件分布 机身${p.cWhich[0]}/机顶${p.cWhich[1]}" +
                "/脚${p.cWhich[2] + p.cWhich[3] + p.cWhich[4]}）= 露馅")
        }
        if (!(p.fMax < 0.004)) w.add("d 掠过—绕后段最大开火量 ${fmt(p.fMax, 3)} ≥ 0.004")
        if (!(p.eMax * DT_T18 <= 9)) {
            w.add("e 空雾（机器与建筑都不可见）最长连续 ${fmt(p.eMax * DT_T18)} s > 9 s ⇒ " +
                "循环里有一段纯白幕（@t=${fmt(p.eA)}→${fmt(p.eB)}）")
        }
        if (!(p.eA >= t1)) {
            w.add("e 最长空雾窗 @t=${fmt(p.eA)} 早于掠过位 t1=${fmt(t1)} ⇒ 逼近/揭示段出现无主体空窗")
        }
        return w
    }

    /**
     * **T18**：掠过—揭示—绕后剧本闸门（R17）。
     * 贴身裁屏 + 居中揭示**收尾于整机入画** + 绕后零露馅 + 段内熄灯 + 盲跑有界。
     *
     * 实测（§十二 T18 行，V0：`t1=59.6 / t2=79.0`）：掠过窗最小真实距离 **11.88 m @t=59.30**、
     * 同窗最大整机投影 **1.15 屏**；回头揭示连续 **t=62.9→71.9 s（9.05 s ≥ 1 s）**、真距
     * **25.1→54.1 m**、整机在画最低 **85.8%**；其中含机顶完整入画 **t=69.3→71.9 s（2.60 s）**；
     * 穿越②窗 **17 帧 / 0.85 s** 可见 **0 帧**（机身0/机顶0/脚0）；`[t1−2, t2+2]` 最大开火量
     * **0.0e+0**；最长空雾窗 **4.7 s @t=75.6→80.3**（≤9 s 且晚于 t1）。
     * 负样本：**A** `escAmp=1.6` ⇒ 掠过 **18.7 m** ∉ 9~16；**B** `yawAmp=0` ⇒ 居中段 **0.00 s** +
     * b2 **0.00 s** + 空雾窗 @t=0 早于 t1；**C** `escAmp=0.5` ⇒ **6.0 m** + 绕后 **1/17** 帧露馅（脚）；
     * **D** 拆 `fireEnv` 双闸 ⇒ 段内开火量 **1.000**；**E** `tiltMax=0` ⇒ b2 **0.00 s**；
     * **F** `yawAmp=0.55` ⇒ b **0.70 s** + b2 **0.70 s** + 绕后 **17/17** 露馅 + 空雾 **10.5 s**。
     */
    @Test
    fun t18_sweepRevealThenPassBehind() {
        val p = world.params
        val pos = measureT18()
        val pw = whyT18(pos)

        val savedEsc = p.escAmp
        val aw: List<String>
        try {
            p.escAmp = 1.6
            aw = whyT18(measureT18())
        } finally {
            p.escAmp = savedEsc
        }
        val savedYaw = p.yawAmp
        val bw: List<String>
        try {
            TripodFogMath.setYawAmp(world, 0.0) // 镜头不转 ⇒ 身后揭示根本不存在，穿越②也不再被剔除
            bw = whyT18(measureT18())
        } finally {
            TripodFogMath.setYawAmp(world, savedYaw)
        }
        val cw: List<String>
        try {
            p.escAmp = 0.5
            cw = whyT18(measureT18())
        } finally {
            p.escAmp = savedEsc
        }
        val sT0 = p.fireT0
        val sT1 = p.fireT1
        val sNear = p.fireNear
        val dw: List<String>
        try {
            p.fireT0 = 80.0
            p.fireT1 = 200.0
            p.fireNear = 0.0
            dw = whyT18(measureT18())
        } finally {
            p.fireT0 = sT0
            p.fireT1 = sT1
            p.fireNear = sNear
        }
        val sTilt = p.tiltMax
        val ew: List<String>
        try {
            p.tiltMax = 0.0
            ew = whyT18(measureT18())
        } finally {
            p.tiltMax = sTilt
        }
        val fw: List<String>
        try {
            TripodFogMath.setYawAmp(world, 0.55) // 镜头只转半圈 ⇒ 既没把机器转到身前、又一直背对建筑带
            fw = whyT18(measureT18())
        } finally {
            TripodFogMath.setYawAmp(world, savedYaw)
        }

        assertTrue("T18 正样本十条判据没全绿：${joinW(pw)}（掠过 ${fmt(pos.aMin, 2)} m @${fmt(pos.aT, 2)}、" +
            "投影 ${fmt(pos.aCrop, 2)} 屏、居中段 ${fmt(pos.runMax * DT_T18, 2)} s @${fmt(pos.runA)}→" +
            "${fmt(pos.runB)}、在画最低 ${fmt(pos.fracMin * 100)}%、完整入画 ${fmt(pos.runF * DT_T18, 2)} s " +
            "@${fmt(pos.runFA)}→${fmt(pos.runFB)}、穿越②窗 ${pos.cN} 帧可见 ${pos.cBad} 帧、开火量 " +
            "${ex(pos.fMax)}、空雾 ${fmt(pos.eMax * DT_T18)} s @${fmt(pos.eA)}→${fmt(pos.eB)}）",
            pw.isEmpty())
        assertTrue("负样本 A（编舞弧 ×1.6）没咬红：${joinW(aw)}", aw.isNotEmpty())
        assertTrue("负样本 B（镜头不转 yawAmp=0）没咬红：${joinW(bw)}", bw.isNotEmpty())
        assertTrue("负样本 C（编舞弧 ×0.5）没咬红：${joinW(cw)}", cw.isNotEmpty())
        assertTrue("负样本 D（拆 fireEnv 双闸）没咬红：${joinW(dw)}", dw.isNotEmpty())
        assertTrue("负样本 E（拆仰视 tiltMax=0）没咬红：${joinW(ew)}", ew.isNotEmpty())
        assertTrue("负样本 F（镜头只转半圈 yawAmp=0.55）没咬红：${joinW(fw)}", fw.isNotEmpty())
        // E 的唯一性：b2 是「拆掉仰视」唯一能抓到的缺陷（b 只看 ≥70%，裁掉机顶的腹部特写照样过）。
        assertTrue("负样本 E 必须踩 b2：${joinW(ew)}", ew.any { it.startsWith("b2 ") })
        // D 的唯一性：段内熄灯只有 d 项管（T5 只判开火帧，兜不到这里）。
        assertTrue("负样本 D 必须踩 d：${joinW(dw)}", hasTag(dw, "d"))

        // 还原自检
        assertEquals(1.0, p.escAmp, 1e-12)
        assertEquals(1.0, p.yawAmp, 1e-12)
        assertEquals(0.20, p.tiltMax, 1e-12)
        assertEquals(54.5, p.fireT0, 1e-12)
        assertEquals(87.0, p.fireT1, 1e-12)
        assertEquals(45.0, p.fireNear, 1e-12)
        assertEquals("镜头仰角必须落回未加 tilt 的基线", TripodGateFixtures.hzBase(world), world.cam.hz, 1e-9)
        val backW = whyT18(measureT18())
        assertTrue("还原后正样本又红了：${joinW(backW)}", backW.isEmpty())
    }

    // ══════════════════════════════ T23 落点只剩贴地亮斑（R31）══════════════════════════════

    /** 原型 T23 的 `run(tier)` 输出。 */
    private class T23Run {
        var dust = 0
        var dustOps = 0
        var beamMax = 0
        var poolN = 0
        var sqMax = 0.0
        var rrLo = Double.POSITIVE_INFINITY
        var rrHi = 0.0
        var tPeak = 0.0
        var n = 0
    }

    private fun runT23(tier: TripodLevel): T23Run {
        val p = world.params
        val r = T23Run()
        val dt = DT_CATS
        val total = (p.loop / dt).roundToInt()
        var i = 0
        while (i <= total) {
            val t = i * dt
            val c = catsAt(t, tier) // ⭐ render() 顺带把 BEAM_REC 填成**这一帧**的
            val nd = c.dustOps()
            val nb = c.columnOps()
            val np = c.poolOps()
            if (nd > 0) {
                r.dust++
                r.dustOps += nd
            }
            if (nb > r.beamMax) {
                r.beamMax = nb
                r.tPeak = t
            }
            if (np > 0) r.poolN++
            for (j in 0 until min(2, c.beamN)) {
                if (!c.poolOn[j]) continue
                r.n++
                if (c.sq[j] > r.sqMax) r.sqMax = c.sq[j]
                if (c.rr[j] < r.rrLo) r.rrLo = c.rr[j]
                if (c.rr[j] > r.rrHi) r.rrHi = c.rr[j]
            }
            i++
        }
        return r
    }

    /** 原型 T23 的 `why(r)`。 */
    private fun whyT23(r: T23Run, nFrames: Int): List<String> {
        val w = ArrayList<String>()
        if (r.beamMax == 0) w.add("a/b 一圈 $nFrames 帧一道光柱都没落笔 ⇒ 判据无对象")
        if (r.dust > 0) {
            w.add("a ${r.dust}/$nFrames 帧在落点上方落笔命中扬尘（共 ${r.dustOps} 条）⇒ " +
                "光柱前端顶着一团光")
        }
        if (r.poolN == 0) w.add("b 全圈亮斑一次没落笔 ⇒ (b) 是恒真式（没有对象可量）")
        if (!(r.sqMax <= 0.45)) {
            w.add("b 亮斑竖向/横向跨度比最大 ${fmt(r.sqMax, 2)} > 0.45 ⇒ 落点那层不是贴地的斑，" +
                "是悬在落点上的圆团")
        }
        return w
    }

    /**
     * **T23**：落点只剩贴地亮斑 —— 悬空尘球 **0 落笔** + 亮斑跨度比 **≤ 0.45**（R31 改造 R23）。
     *
     * 实测（§十二 T23 行）：三档 × 全圈 **541 帧**（1/6 s）——「命中扬尘」落笔 **0 / 0 / 0** 次
     * （Kotlin 侧尘球记进 `BEAM_POOL` 行 ⇒ 量的是「落点那一坨里超出柱体配对的那几笔」，见
     * [FrameCats.dustOps]），`plan.dustN` 同样恒 0；亮斑落笔帧 **369 / 369 / 369**，跨度比最大
     * **0.30**（判据 ≤0.45）、半径 **21.7→75.9 px**、一圈亮斑记录 **736** 条。
     * 负样本：**A** `DustGeom.on=1` **且** `tierK=0`（⚠️ LOW 的「0 次」有两道成因 —— 层关掉 vs
     * 档位裁掉，只开 `on` 不动 `tierK` 的话 (a) 在 LOW 是恒真式）⇒ 各档柱体峰值帧落笔
     * LOW **2 / MED 2 / HIGH 2** 条；**B** `PoolGeom.sq=1` ⇒ 跨度比 **1.00 > 0.45**（只让 (b) 红）。
     */
    @Test
    fun t23_impactIsFlatPoolOnly() {
        val p = world.params
        val tiers = listOf(TripodLevel.LOW, TripodLevel.MEDIUM, TripodLevel.HIGH)
        val names = listOf("LOW", "MED", "HIGH")
        val nFrames = (p.loop / DT_CATS).roundToInt() + 1
        val runs = tiers.map { runT23(it) }
        val w0 = ArrayList<String>()
        for (k in tiers.indices) w0.addAll(whyT23(runs[k], nFrames).map { names[k] + " " + it })
        val med = runs[1]

        // 负样本 A：把尘球补回**真代码路径**（on=1 且 tierK=0），三档各自峰值帧必须抓到落笔。
        val caught = ArrayList<String>()
        val missed = ArrayList<String>()
        for (k in tiers.indices) {
            val tk = DustGeom.tierK
            val on = DustGeom.on
            val nd: Int
            try {
                DustGeom.on = 1
                DustGeom.tierK = 0
                nd = catsAt(runs[k].tPeak, tiers[k]).dustOps()
            } finally {
                DustGeom.on = on
                DustGeom.tierK = tk
            }
            if (nd == 0) missed.add(names[k]) else caught.add("${names[k]} $nd 条")
        }

        // 负样本 B：亮斑从「贴地的斑」变回「圆团」（sq→1），必须**只**让 (b) 红。
        val sq0 = PoolGeom.sq
        val wB: List<String>
        try {
            PoolGeom.sq = 1.0
            wB = whyT23(runT23(TripodLevel.MEDIUM), nFrames)
        } finally {
            PoolGeom.sq = sq0
        }

        assertTrue("T23 正样本：${joinW(w0)}（尘球 ${runs.map { it.dust }} 帧、亮斑帧 " +
            "${runs.map { it.poolN }}、跨度比最大 ${fmt(med.sqMax, 2)}、半径 ${fmt(med.rrLo)}→" +
            "${fmt(med.rrHi)} px、一圈记录 ${med.n} 条、柱体峰值帧 t=${fmt(med.tPeak)}）", w0.isEmpty())
        assertTrue("负样本 A 没抓到（${missed.joinToString(",")} 档的 (a) 是恒真式）：${caught}",
            missed.isEmpty())
        assertTrue("负样本 A 必须三档全 >0 条：${caught}", caught.size == 3)
        assertEquals("负样本 B 的旋钮还原", 0.30, PoolGeom.sq, 1e-12)
        assertEquals("尘球层旋钮还原", 0, DustGeom.on)
        assertEquals("尘球档纪律还原", 1, DustGeom.tierK)
        assertTrue("负样本 B（POOL.sq=1）没让 (b) 红：${joinW(wB)}", hasTag(wB, "b"))
        assertFalse("负样本 B 连带把 (a) 也弄红 = 两条判据纠缠，不是独立负样本：${joinW(wB)}",
            hasTag(wB, "a"))
    }

    // ══════════════════ T29 前端无光 / 亮斑逐帧成对 / 火星密度随 emberN ══════════════════

    /** 原型 T29 的 `sweep(tier)` 输出。 */
    private class T29Sweep {
        var muzzle = 0
        var beam = 0
        var pairBad = 0
        var maxB = 0
        var maxP = 0
        var two = 0
        var tPeak = 0.0
    }

    private fun sweepT29(tier: TripodLevel): T29Sweep {
        val p = world.params
        val s = T29Sweep()
        val total = (p.loop / DT_CATS).roundToInt()
        var i = 0
        while (i <= total) {
            val t = i * DT_CATS
            val c = catsAt(t, tier)
            val nb = c.columnOps()
            val np = c.poolOps()
            val nm = c.muzzleOps()
            if (nm > 0) s.muzzle++
            if (nb > 0) {
                s.beam++
                if (nb == 2) s.two++
            }
            if (nb > s.maxB) {
                s.maxB = nb
                s.tPeak = t
            }
            if (np > s.maxP) s.maxP = np
            if (nb != np) s.pairBad++
            i++
        }
        return s
    }

    /** 原型 T29 的 `why(s)`。 */
    private fun whyT29(s: T29Sweep, nFrames: Int): List<String> {
        val w = ArrayList<String>()
        if (s.beam == 0) w.add("a/b 一圈 $nFrames 帧一道光柱都没落笔 ⇒ 判据无对象")
        if (s.muzzle > 0) w.add("a ${s.muzzle}/$nFrames 帧在发射口落笔 ⇒ 前端顶着一团光")
        if (s.pairBad > 0) {
            w.add("b ${s.pairBad}/$nFrames 帧柱体与亮斑条数不等（峰值 ${s.maxB} vs ${s.maxP}）⇒ " +
                "光打进了地里但地上没有那圈斑")
        }
        if (s.two == 0) w.add("b 全圈没有一帧同时落笔两道光柱 ⇒ “两道热光”没成立")
        return w
    }

    /** 原型 T29 的 `densAt(n)`：`emberN=n`，HIGH 档扫 180 帧，取单帧峰值与一圈累计。 */
    private class Dens(val pk: Int, val sum: Int)

    private fun densAt(n: Int): Dens {
        val p = world.params
        val saved = p.emberN
        p.emberN = n
        try {
            var pk = 0
            var sum = 0
            for (i in 0 until ND_DENS) {
                val v = catsAt(i * p.loop / ND_DENS, TripodLevel.HIGH).emberOps()
                if (v > pk) pk = v
                sum += v
            }
            return Dens(pk, sum)
        } finally {
            p.emberN = saved
        }
    }

    /** 原型 T29 的 `dens()` 那四条（严格递增 + 24/8 的单帧与累计双 ≥2×）。 */
    private fun densWhy(a: Dens, b: Dens, c: Dens): List<String> {
        val w = ArrayList<String>()
        if (!(a.pk < b.pk && b.pk < c.pk)) {
            w.add("c 单帧火星峰值 ${a.pk}→${b.pk}→${c.pk}（emberN 8→24→48）不严格递增 ⇒ 条数与参数脱钩")
        }
        if (b.pk < 2 * a.pk) w.add("c emberN=24 的单帧峰值 ${b.pk} < 2×8 粒基线(${a.pk})")
        if (b.sum < 2 * a.sum) w.add("c emberN=24 的一圈累计 ${b.sum} < 2×8 粒基线(${a.sum})")
        return w
    }

    /**
     * 原型 T29 的 `sigAt(n)` 的等价物：`emberN=n`、HIGH 档扫 180 帧，把每粒火星的
     * **世界系锚点** `(sx, sz)` 按「帧号 + k」存下来。
     *
     * ⚠️ 原型的签名是尘球参数表前 8 行的七个值，Kotlin 侧那张表是 `private`（缺钩子，见类 KDoc）
     * ⇒ 这里改判那张表**唯一对外可见的落点**：`sx/sz` 由 `dA/dPer/dRise/dSpread/dPh` 与
     * `aimState(tb)` 合成 ⇒ 「前缀逐位相同」⇔「加密是往后追加、⛔ 整批重掷」，
     * `EmGeom.seedVar=1` 换的是同一批参数 ⇒ 照样咬得住（实测口径：8 粒 × 2 道光 × 连续帧）。
     */
    private fun emberAnchorSig(n: Int, maxSi: Int): HashMap<Long, DoubleArray> {
        val p = world.params
        val savedN = p.emberN
        val savedTier = world.tier
        p.emberN = n
        world.tier = TripodLevel.HIGH
        try {
            val sig = HashMap<Long, DoubleArray>()
            var idx = 0
            var t = 0.0
            while (idx < ND_DENS) {
                plan.build(TripodGait.sceneState(world, t))
                val a = plan.anchor
                val k = plan.anchorN
                var i = 0
                while (i < k) {
                    val o = i * 7
                    val si = a[o].toInt()
                    if (si < maxSi) {
                        val bi = a[o + 1].toInt()
                        sig.put(idx * 1000L + si * 2L + bi.toLong(), doubleArrayOf(a[o + 3], a[o + 4]))
                    }
                    i++
                }
                idx++
                t += p.loop / ND_DENS
            }
            return sig
        } finally {
            p.emberN = savedN
            world.tier = savedTier
        }
    }

    /** 两份签名的前缀是否逐位相同（`maxSi` 之前的键、双方都有 ⇒ 比 `(sx, sz)`）。 */
    private fun sigSame(x: Map<Long, DoubleArray>, y: Map<Long, DoubleArray>): Pair<Boolean, Int> {
        var common = 0
        var bad = 0
        for ((key, v) in x) {
            val w = y[key] ?: continue
            common++
            if (abs(v[0] - w[0]) > 1e-9 || abs(v[1] - w[1]) > 1e-9) bad++
        }
        return Pair(bad == 0 && common > 0, common)
    }

    /**
     * **T29**：光柱前端无光 / 亮斑逐帧成对 / 火星密度随 `emberN`（R30，整段钉在 V0）。
     *
     * 实测（§十二 T29 行）：三档 × 全圈 **541 帧**：「光束发射口」落笔 **LOW 0 / MED 0 / HIGH 0**
     * 次；开火帧 **369 / 369 / 369**，柱体峰值 vs 亮斑峰值 = **2/2 / 2/2 / 2/2**、逐帧不等
     * **0 / 0 / 0** 帧；两道光同时落笔 **367 / 367 / 367** 帧；火星单帧峰值 **16→48→96**、
     * 一圈 180 帧累计 **1776→5433→10949**（`emberN` 8→24→48，比值 **3.00× / 3.06×** 双双 ≥2）；
     * `emberN=24` 的前 8 粒与 `emberN=8` **逐位相同**（= 加密是追加）。
     * 负样本：**A** `BmGeom.muzzle=1` ⇒ 各档峰值帧落笔 **2 / 2 / 2** 条；**B** `BmGeom.pool=1`
     * ⇒ 柱体/亮斑 **2/0 ×3**；**C** `EmGeom.cap=8` ⇒ 峰值 **16→16→16**、累计 **1776×3** 不递增；
     * **D** `EmGeom.seedVar=1` ⇒ 前缀相同 = **false**。
     */
    @Test
    fun t29_noMuzzleCapPoolsPairAndEmberDensity() {
        withVariant0 {
            val p = world.params
            val tiers = listOf(TripodLevel.LOW, TripodLevel.MEDIUM, TripodLevel.HIGH)
            val names = listOf("LOW", "MED", "HIGH")
            val nFrames = (p.loop / DT_CATS).roundToInt() + 1
            val rs = tiers.map { sweepT29(it) }
            val wBad = ArrayList<String>()
            for (k in tiers.indices) wBad.addAll(whyT29(rs[k], nFrames).map { names[k] + " " + it })

            // 负样本 A/B：只在正样本量到的峰值帧上各跑一帧（一帧落笔就足以证明「0 次」是量出来的）。
            val nAd = ArrayList<String>()
            val nA = ArrayList<String>()
            val nBd = ArrayList<String>()
            val nB = ArrayList<String>()
            for (k in tiers.indices) {
                val t = rs[k].tPeak
                val mM: Int
                val savedM = BmGeom.muzzle
                try {
                    BmGeom.muzzle = 1
                    mM = catsAt(t, tiers[k]).muzzleOps()
                } finally {
                    BmGeom.muzzle = savedM
                }
                if (mM == 0) nA.add(names[k]) else nAd.add("${names[k]} $mM 条")

                val c2: FrameCats
                val savedP = BmGeom.pool
                try {
                    BmGeom.pool = 1
                    c2 = catsAt(t, tiers[k])
                } finally {
                    BmGeom.pool = savedP
                }
                if (c2.columnOps() == c2.poolOps()) nB.add(names[k]) else {
                    nBd.add("${names[k]} ${c2.columnOps()}/${c2.poolOps()}")
                }
            }

            val baseA = densAt(8)
            val baseB = densAt(24)
            val baseC = densAt(48)
            val d0w = densWhy(baseA, baseB, baseC)
            val savedCap = EmGeom.cap
            val capW: List<String>
            try {
                EmGeom.cap = 8
                capW = densWhy(densAt(8), densAt(24), densAt(48))
            } finally {
                EmGeom.cap = savedCap
            }

            val sig8 = emberAnchorSig(8, 8)
            val sig24 = emberAnchorSig(24, 8)
            val (pSame, common) = sigSame(sig8, sig24)
            val savedSeed = EmGeom.seedVar
            val pSameN: Boolean
            try {
                EmGeom.seedVar = 1
                pSameN = sigSame(emberAnchorSig(8, 8), emberAnchorSig(24, 8)).first
            } finally {
                EmGeom.seedVar = savedSeed
            }
            // 原型结尾的 `dust = null`：旋钮期间重掷的那批必须丢掉 —— 参数表只在条数变化时重建，
            // 先把条数挪一格再挪回来，逼发射器用**还原后的种子**重建一次。
            val savedN = p.emberN
            try {
                p.emberN = savedN + 1
                catsAt(0.0, TripodLevel.HIGH)
                p.emberN = savedN
                catsAt(0.0, TripodLevel.HIGH)
            } finally {
                p.emberN = savedN
            }

            assertTrue("T29 正样本：${joinW(wBad)}（发射口 ${rs.map { it.muzzle }}、开火帧 " +
                "${rs.map { it.beam }}、峰值成对 ${rs.map { "${it.maxB}/${it.maxP}" }}、逐帧不等 " +
                "${rs.map { it.pairBad }}、双光帧 ${rs.map { it.two }}）", wBad.isEmpty())
            assertTrue("T29(c) 密度判据没全绿：${joinW(d0w)}（单帧 ${baseA.pk}→${baseB.pk}→${baseC.pk}、" +
                "累计 ${baseA.sum}→${baseB.sum}→${baseC.sum}、比值 ${fmt(baseB.pk / max(1.0, baseA.pk.toDouble()), 2)}× / " +
                "${fmt(baseB.sum / max(1.0, baseA.sum.toDouble()), 2)}×）", d0w.isEmpty())
            assertTrue("负样本 A（BM.muzzle=1）没抓到（${nA.joinToString(",")} 档 ⇒ (a) 是恒真式）：${nAd}",
                nA.isEmpty())
            assertEquals("负样本 A 必须三档都落笔", 3, nAd.size)
            assertTrue("负样本 B（BM.pool=1）没让柱体/亮斑脱钩（${nB.joinToString(",")} 档仍然相等）：${nBd}",
                nB.isEmpty())
            assertEquals("负样本 B 必须三档都不等", 3, nBd.size)
            assertTrue("负样本 C（EM.cap=8）没咬红：${joinW(capW)}", capW.isNotEmpty())
            assertTrue("T29(d) 前缀不稳定：${common} 个可比的锚点里有不等的前 8 粒", pSame)
            assertTrue("T29(d) 前缀可比样本太少（$common 个，判 ≥8 ⇒ 判据空转）", common >= 8)
            assertFalse("负样本 D（EM.seedVar=1）没抓到整批重掷", pSameN)

            // 还原自检：⛔ 旋钮单例留残值会污染后面的闸门。
            assertEquals(0, BmGeom.muzzle)
            assertEquals(0, BmGeom.pool)
            assertEquals(0, EmGeom.cap)
            assertEquals(0, EmGeom.seedVar)
            assertEquals(24, p.emberN)
            assertEquals(0.12, p.emberW, 1e-12)
            val again = sweepT29(TripodLevel.MEDIUM)
            val againW = whyT29(again, nFrames)
            assertTrue("还原后 MED 又红了：${joinW(againW)}", againW.isEmpty())
            assertEquals("还原后发射口必须回到 0 落笔", 0, again.muzzle)
            assertTrue("还原后前缀稳定性没回来", sigSame(emberAnchorSig(8, 8), emberAnchorSig(24, 8)).first)
            assertEquals("withVariant0 结束后必须回到 V0", 0, world.curVar)
        }
    }

    // ══════════════════════════════════ T30 发射口无亮帽（R31）══════════════════════════════

    /** 原型 T30 的 `run(tier)` 输出。 */
    private class T30Run {
        var n = 0
        var footMax = 0.0
        var peakAtMin = 1.0
        var nearMax = 0.0
        var aPeakMax = 0.0
        var frames = 0
    }

    private fun runT30(tier: TripodLevel): T30Run {
        val p = world.params
        val r = T30Run()
        val total = (p.loop / DT_CATS).roundToInt()
        var i = 0
        while (i <= total) {
            val t = i * DT_CATS
            val c = catsAt(t, tier)
            var kk = 0
            for (j in 0 until min(2, c.beamN)) {
                r.n++
                kk++
                if (c.footRatio[j] > r.footMax) r.footMax = c.footRatio[j]
                if (c.peakAt[j] < r.peakAtMin) r.peakAtMin = c.peakAt[j]
                if (c.nearM[j] > r.nearMax) r.nearMax = c.nearM[j]
                if (c.aPeak[j] > r.aPeakMax) r.aPeakMax = c.aPeak[j]
            }
            if (kk > 0) r.frames++
            i++
        }
        return r
    }

    /** 原型 T30 的 `why(r)`（⚠️ (d) 必须是**绝对数**：比值判据会跟着 `P.beamI` 一起降）。 */
    private fun whyT30(r: T30Run, nFrames: Int): List<String> {
        val w = ArrayList<String>()
        if (r.n == 0) w.add("a/b/c/d 一圈 $nFrames 帧没有一道柱体落笔 ⇒ 判据无对象")
        if (!(r.footMax <= 0.25)) {
            w.add("a 发射口/峰值 α 比 ${fmt(r.footMax, 2)} > 0.25 ⇒ 柱体最亮的一档又压回喷口上" +
                "（两个满亮楔子并成一团）")
        }
        if (!(r.peakAtMin >= 0.06)) {
            w.add("b 峰值位置最靠前的那道光在柱长 ${fmt(r.peakAtMin * 100, 0)}% < 6% ⇒ 端点即峰值，" +
                "爬升等于没写")
        }
        if (!(r.nearMax <= 0.25)) {
            w.add("c 近端半宽 ${fmt(r.nearMax, 2)} m > 0.25 m ⇒ 出射端是一个楔子口而不是一条细线")
        }
        if (!(r.aPeakMax >= 0.25)) {
            w.add("d 一圈峰值 α 最大只有 ${fmt(r.aPeakMax, 3)} < 0.25 ⇒ 靠调暗整条光束骗过 (a)" +
                "（热光本身没了）")
        }
        return w
    }

    /**
     * **T30**：发射口无亮帽 —— 柱体起亮爬升 + 近端收窄 + 峰值不许调暗（R31，整段钉在 V0）。
     * 四条判据全部读 `beams()` 记进 `BEAM_REC` 的那一份（⛔ 闸门重推渐变 = 第二套口径）。
     *
     * 实测（§十二 T30 行）：三档 × 全圈 **541 帧**、柱体记录 **736** 条：起亮比 **0.12**
     * （判据 ≤0.25）、峰位 ≥ **12%** 柱长（判据 ≥6%）、近端半宽 **0.18 m**（判据 ≤0.25）、
     * 一圈峰值 α 最大 **0.292**（判据 ≥0.25）。
     * 负样本：**A** `beamFoot→1` ⇒ 起亮比 **1.00 > 0.25**；**B** `beamPeakAt→0` ⇒ 峰位 **0% < 6%**；
     * **C** `beamHalfNear→0.38`（旧值）⇒ 近端半宽 **0.38 > 0.25 m**；
     * **D** `beamI→0.4` ⇒ 一圈峰值 α 最大 **0.188 < 0.25**。
     */
    @Test
    fun t30_columnRisesFromTheMuzzle() {
        val out = withVariant0 {
            val p = world.params
            val tiers = listOf(TripodLevel.LOW, TripodLevel.MEDIUM, TripodLevel.HIGH)
            val names = listOf("LOW", "MED", "HIGH")
            val nFrames = (p.loop / DT_CATS).roundToInt() + 1
            val rs = tiers.map { runT30(it) }
            val w0 = ArrayList<String>()
            for (k in tiers.indices) w0.addAll(whyT30(rs[k], nFrames).map { names[k] + " " + it })

            val negs = ArrayList<List<String>>()
            val f0 = p.beamFoot
            val pk0 = p.beamPeakAt
            val n0 = p.beamHalfNear
            val bi0 = p.beamI
            negs.add(t30Neg({ p.beamFoot = 1.0 }, { p.beamFoot = f0 }).w)
            negs.add(t30Neg({ p.beamPeakAt = 0.0 }, { p.beamPeakAt = pk0 }).w)
            negs.add(t30Neg({ p.beamHalfNear = 0.38 }, { p.beamHalfNear = n0 }).w)
            negs.add(t30Neg({ p.beamI = 0.4 }, { p.beamI = bi0 }).w)
            T30Out(w0, negs, rs, nFrames)
        }
        val med = out.rs[1]
        assertTrue("T30 正样本：${joinW(out.w0)}（柱体记录 ${med.n} 条、起亮比 ${fmt(med.footMax, 2)}、" +
            "峰位 ${fmt(med.peakAtMin * 100, 0)}%、近端半宽 ${fmt(med.nearMax, 2)} m、峰值 α 最大 " +
            "${fmt(med.aPeakMax, 3)}、有柱体的帧 ${med.frames}/${out.nFrames}）", out.w0.isEmpty())
        val tags = listOf("a", "b", "c", "d")
        for (i in tags.indices) {
            val w = out.negs[i]
            assertTrue("负样本 ${tags[i]} 没咬红自己那条标号：${joinW(w)}", hasTag(w, tags[i]))
        }
        val p = world.params
        assertEquals("beamFoot 还原", 0.12, p.beamFoot, 1e-12)
        assertEquals("beamPeakAt 还原", 0.12, p.beamPeakAt, 1e-12)
        assertEquals("beamHalfNear 还原", 0.18, p.beamHalfNear, 1e-12)
        assertEquals("beamI 还原", 0.62, p.beamI, 1e-12)
        val backR = whyT30(runT30(TripodLevel.MEDIUM), out.nFrames)
        assertTrue("还原后正样本又红了：${joinW(backR)}", backR.isEmpty())
        assertEquals("withVariant0 结束后必须回到 V0", 0, world.curVar)
    }

    private class T30Out(val w0: List<String>, val negs: List<List<String>>, val rs: List<T30Run>, val nFrames: Int)

    /** 原型 T30 的 `neg(mut, restore, tag)`：只在 MED 上重跑一圈，返回 `why` 与该条是否咬住。 */
    private fun t30Neg(mut: () -> Unit, restore: () -> Unit): Pair2 {
        mut()
        val w = try {
            val p = world.params
            whyT30(runT30(TripodLevel.MEDIUM), (p.loop / DT_CATS).roundToInt() + 1)
        } finally {
            restore()
        }
        return Pair2(w)
    }

    private class Pair2(val w: List<String>)
}
