package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.FIXED_DT
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.MAX_BIRDS
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.MAX_PERCH
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.MAX_TREES
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.NWIND
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.TIER_BIRDS
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.TIER_GRID_CELL_CAP
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.TIER_PERCH_CAP
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.sepGuard
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.wrap
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.wrap01
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * ============================================================================
 *  E49「鸟群」仿真状态层（K3）—— 方案 §四 / §5.7 / §5.8 的原型逐字移植
 *  （原型：docs/archive/e49-birds-flock-preview.html，种子 / 系数以 §10.2 harness 归档为准）
 *
 *  ⛔ 红线（§5.7）：热循环零分配 —— soa 全 `FloatArray` 字段、邻居表 `IntArray`、
 *  空间哈希 `IntArray`；`steerBoids / findNeighbors / windAt / updatePerch / step`
 *  的可达路径上一个对象都不许 new（`PerfBudgetContractTest` 与本包源码门禁盯着）。
 *  分配只发生在 [layout] / [resetFlock]（画幅 / 档位变化时，非逐帧）。
 *
 *  ⛔ 不读 `maxParticles`（§十二）：数量只按档位表 [TIER_BIRDS] 调，
 *  枚举 `needsParticleBudget = false` 与此一致（`ParticleBudgetGateTest` 反推真值）。
 * ==========================================================================
 */

/**
 * 参数表（原型 `E49.P` 的**真实消费子集**）。
 *
 * ⛔ §8.3 A 的死参数（`turnR` / `cruiseJit` / `flapAmp` / `flapHyst` / `glideDuty` /
 * `vGust` / `maxSpeedNM`）**不迁移**；音频系数只带已接线的 `kBass` / `kEnergy`
 * （`kMid` / `kTreble` 为 §7 待实现量，原型 0 实现 ⇒ 不带假默认值进来）。
 */
internal class BirdsFlockParams {
    /** 地平线占 H 的位置 —— ⛔ **只读锚点**（§5.6 契约：不进任何可写参数面、不受鸟群影响）。 */
    val horizonFrac = 0.800f

    /** 群体活动带（归一 y）。 */
    var spanYTop = 0.15f
    var spanYBot = 0.75f
    var spanX0 = 0.05f
    var spanX1 = 0.95f

    /** 天空亮度锚（§2.2：天顶偏冷、地平线附近最亮；烘进位图，逐帧不变）。 */
    var skyTopL = 0.22f
    var skyHorizonL = 0.72f

    /** 三规则权重（§8.3 F 精修后的实测值；对齐主导 ⇒ "椋鸟群读感"的命脉）。 */
    var wSep = 1.20f
    var wAli = 2.00f
    var wCoh = 1.10f

    /** 分离半径 px（§8.3 F：38→30）；`nbrR` 只作格子尺寸（§8.3 B2 纯拓扑）。 */
    var sepR = 30f
    var nbrR = 60f

    /** 侧倾增益（原型实证 0.90；§4.9 的合法补偿旋钮，⛔ 不在 φ 公式里另加系数）。 */
    var g0 = 0.90f

    /** curl 风场（§8.3 F：0.00080→0.00045）。 */
    var curlAmp = 0.00045f
    var curlDrift = 0.025f

    /** 扑动频率（单态 `sin(flapPh)`，§8.3 A/R4 决策）。 */
    var flapFreq = 6.50f

    /** 软带内缘（归一 H）。 */
    var bandInner = 0.120f

    /** 边界三系数（§4.7 原型实测 2.5 / 1.2 / 0.7；切向只在 x 侧，y 向切向不恢复——有实测反例）。 */
    var kHard = 2.50f
    var kSoft = 1.20f
    var kTangent = 0.70f

    /** 游走引力点增益（§8.3 F：0.50→0.85）。 */
    var goalGain = 0.85f

    /** 停栖时长区间（秒）与错峰幅度。 */
    var perchDwellMin = 3.0f
    var perchDwellMax = 9.0f
    var takeoffSpread = 4.0f

    /** 归一巡航速（px/s，除以 H 使用）。 */
    var maxSpeedN = 160f

    /** 音频系数：bass → 风场幅值；energy → 视差漂移速度（§七 的环境映射，⛔ 不碰行为决策）。 */
    var kBass = 0.80f
    var kEnergy = 0.50f
}

/** 枯树几何（`treeGeom` 的移植宿主；只在 layout / 烘焙期构造，⌛ 非热路径可分配）。 */
internal class BirdsFlockTreeGeom {
    var baseJit = 0f
    var w0f = 0f
    var bendf = 0f
    var nBr = 0
    val brYf = FloatArray(4)
    val brLf = FloatArray(4)
    val brSide = FloatArray(4)
    val brUpA = FloatArray(4)
    val brFork = BooleanArray(4)
    val brFks = FloatArray(4)
}

/**
 * 鸟群仿真：soa 状态 + 空间哈希 + 拓扑 top-7 + Boids 三规则 + 航点巡航 + 停栖状态机。
 *
 * ## 坐标系
 * 归一 `[0,1]²` 存储（`px/py`），热循环里按需乘 `w/h` 变像素 —— 与原型一致，
 * 好处是画幅变化时布局量不漂移。
 */
internal class BirdsFlockFlock(val p: BirdsFlockParams) {

    var w = 1f
    var h = 1f
    var n = 0
    var nPerched = 0

    // ── soa（§5.7 的 14 条名单里 Kotlin 实际消费的子集；accX/accY 在原型里也从未被读 ⇒ 不带）──
    val px = FloatArray(MAX_BIRDS)
    val py = FloatArray(MAX_BIRDS)
    val vx = FloatArray(MAX_BIRDS)
    val vy = FloatArray(MAX_BIRDS)
    val flapPh = FloatArray(MAX_BIRDS)
    val bank = FloatArray(MAX_BIRDS)
    val dIdx = FloatArray(MAX_BIRDS)   // 深度态 [0,1)：层 = floor(d*4)，缓慢漂移穿层
    val dSpd = FloatArray(MAX_BIRDS)
    val speedK = FloatArray(MAX_BIRDS)
    val respK = FloatArray(MAX_BIRDS)
    val omegaK = FloatArray(MAX_BIRDS)
    val timer = FloatArray(MAX_BIRDS)
    val state = ByteArray(MAX_BIRDS)   // 0=飞行 2=在栖 3=进近落栖
    val isPerched = ByteArray(MAX_BIRDS)
    val perchSlotOf = IntArray(MAX_BIRDS)

    // ── 邻居（max-heap top-7 by d²；交错 {j, d2} 对）──
    val nid = IntArray(MAX_BIRDS * 14)
    val nbrK = ByteArray(MAX_BIRDS)

    // ── 树与栖点 ──
    val treeX = FloatArray(MAX_TREES)
    val treeH = FloatArray(MAX_TREES)
    var nTree = 0
    val perchTree = IntArray(MAX_PERCH)
    val perchDx = FloatArray(MAX_PERCH)
    val perchSy = FloatArray(MAX_PERCH)
    val perchTaken = ByteArray(MAX_PERCH)
    var nPerch = 0
    var perchCap = 0

    // ── curl 风场（固定种子生成，§4.8.2）──
    val windA = FloatArray(NWIND)
    val windK = FloatArray(NWIND)
    val windDX = FloatArray(NWIND)
    val windDY = FloatArray(NWIND)
    val windPH = FloatArray(NWIND)

    /** 音频：bass → 风场幅值乘子（1 + kBass·bass）；无声 = 1 ⇒ 风有纯时间基线（§七 无声零漂移）。 */
    var windScale = 1f

    // ── 空间哈希 ──
    var cellOf = IntArray(0)
    private val cellList = IntArray(MAX_BIRDS * 2)
    var gridCX = 0
    var gridCY = 0
    private var gridCellW = 72f
    private var gridCellH = 72f
    private var gridCellCap = 16

    // ── 航点巡航（原型 `_wp*`）──
    private var wpX = -1f
    private var wpY = -1f
    private var prevGoalX = -1f
    private var prevGoalY = -1f
    private var goalX = -1f
    private var goalY = -1f
    private var wpFade = 1f
    private var wpEpoch = 0f
    private var wpN = 0
    var centX = 0.5f
        private set
    var centY = 0.45f
        private set

    // ── 时间与视差 ──
    var simTime = 0f
        private set
    var nanInfFrames = 0
        private set

    /** 三条带横向滚动累计（px，已按各自速率推进并按 [0,w) 环绕；draw 侧与停栖跟随共用同一值）。 */
    var treeOff = 0f
        private set
    var farTreeOff = 0f
        private set
    var tuftOff = 0f
        private set

    /** 音频：energy → 视差速度乘子（1 + kEnergy·energy）。 */
    var parallaxScale = 1f

    private var simRng = BfRng(987654321)
    private val windOut = FloatArray(2)
    private val geomScratch = BirdsFlockTreeGeom()

    // ─────────────────────── 布局 / 重置 ───────────────────────

    /**
     * 画幅 / 档位变化时的全量重建（原型 `applyTier` 的 `rebuildLayout + resetFlock`）。
     * ⛔ 只在 boot 路径调用（分配发生在这里），热路径不许进来。
     */
    fun layout(wNew: Int, hNew: Int, tier: Int) {
        w = wNew.coerceAtLeast(1).toFloat()
        h = hNew.coerceAtLeast(1).toFloat()
        perchCap = TIER_PERCH_CAP[tier]
        gridCellCap = TIER_GRID_CELL_CAP[tier]
        // D2（2026-10-15 真机返工）：分离半径随画幅缩放（30px 是 1600×900 的定值）
        p.sepR = 30f * (h / 900f)
        rebuildLayout()
        resetFlock(tier)
    }

    /** 设计高度缩放因子（原型所有 px 几何量按 1600×900 定值 ⇒ 真机统一乘 `h/900`）。 */
    fun scale(): Float = h / 900f

    /** 树 / 栖点 / 风场 / 网格（原型 `rebuildLayout`，种子 31415926 / 27182818）。 */
    private fun rebuildLayout() {
        var rng = BfRng(31415926)
        nTree = 3 + (rng.next() * 3f).toInt()
        val hY = h * p.horizonFrac
        // D2（2026-10-15 真机返工）：树高随设计高度缩放（原型 60~150px 是 1600×900 的定值，
        // 真机 1080p+ 上偏小）⇒ 统一乘 s = h/900；烘焙与栖点共用 flock.treeH ⇒ 自动一致
        val s = h / 900f
        for (i in 0 until nTree) {
            treeX[i] = w * (0.12f + rng.next() * 0.76f)
            treeH[i] = (60f + rng.next() * 90f) * s
        }
        // 栖点 = 枝尖（与树烘焙共享 treeGeom ⇒ 鸟必然坐在枝上）+ 树梢；低位枝过滤
        //（⛔ 栖点必须高于飞行带下缘一半带宽：低位枝起飞会坠入地面带、暗鸟贴暗地"消失"）
        nPerch = 0
        java.util.Arrays.fill(perchTaken, 0)
        val perchMaxY = hY - p.bandInner * h * 0.55f
        for (t in 0 until nTree) {
            if (nPerch >= perchCap) break
            treeGeom(t, geomScratch)
            val bandH = h - hY
            val baseY = hY + bandH * geomScratch.baseJit
            val topY = hY - treeH[t]
            val midY = baseY - (baseY - topY) * 0.5f
            // 候选：树梢 + 各枝尖 + 枝尖分杈（与原型 rebuildLayout 同序）
            val candX = FloatArray(1 + geomScratch.nBr * 2)
            val candY = FloatArray(1 + geomScratch.nBr * 2)
            var nc = 0
            candX[nc] = treeX[t] + geomScratch.bendf * bandH * geomScratch.w0f * 0.3f
            candY[nc] = topY
            nc++
            for (b in 0 until geomScratch.nBr) {
                val by = midY - (midY - topY) * geomScratch.brYf[b]
                val bLen = treeH[t] * geomScratch.brLf[b]
                val tipX = treeX[t] + geomScratch.brSide[b] * sin(geomScratch.brUpA[b]) * bLen
                val tipY = by - cos(geomScratch.brUpA[b]) * bLen * 0.85f
                candX[nc] = tipX; candY[nc] = tipY; nc++
                if (geomScratch.brFork[b]) {
                    candX[nc] = tipX + geomScratch.brSide[b] * bLen * 0.26f
                    candY[nc] = tipY - bLen * 0.26f
                    nc++
                }
            }
            for (c in 0 until nc) {
                if (nPerch >= perchCap) break
                if (candY[c] > perchMaxY) continue
                perchTree[nPerch] = t
                perchDx[nPerch] = candX[c] - treeX[t]   // dx 相对树干：随 treeOff 滚动换算
                perchSy[nPerch] = candY[c]
                nPerch++
            }
        }
        // 风场（固定种子 27182818，§4.8.2）
        rng = BfRng(27182818)
        for (i in 0 until NWIND) {
            windA[i] = (0.5f + rng.next() * 0.8f) * p.curlAmp
            windK[i] = 3f + (rng.next() * 5f).toInt()
            val dir = rng.next() * 2f * PI.toFloat()
            windDX[i] = cos(dir)
            windDY[i] = sin(dir)
            windPH[i] = rng.next() * 2f * PI.toFloat()
        }
        // 空间哈希网格：格子宽 = max(40, nbrR·1.2)（⛔ 半径不是选择判据，§8.3 B2）
        val cw = maxOf(40f, p.nbrR * 1.2f)
        gridCX = (w / cw).toInt() + 2
        gridCY = (h / cw).toInt() + 2
        gridCellW = w / gridCX
        gridCellH = h / gridCY
        cellOf = IntArray(gridCX * gridCY)
    }

    /** 群体重置（原型 `resetFlock`，种子 987654321；3 子团近高斯初始化 + 全员同向）。 */
    private fun resetFlock(tier: Int) {
        simRng = BfRng(987654321)
        n = TIER_BIRDS[tier].coerceAtMost(MAX_BIRDS)
        nPerched = 0
        simTime = 0f
        nanInfFrames = 0
        treeOff = 0f
        farTreeOff = 0f
        tuftOff = 0f
        val spdN = p.maxSpeedN / h
        val bandT = p.spanYTop + 0.04f
        val bandB = p.spanYBot - 0.03f
        // 3 子团初始化：开局即有"团"的读感，子团间留空隙，Boids 自然融合/吞吐
        val scX = FloatArray(3)
        val scY = FloatArray(3)
        for (s in 0 until 3) {
            scX[s] = p.spanX0 + (0.22f + 0.28f * s) * (p.spanX1 - p.spanX0) +
                (simRng.next() - 0.5f) * 0.06f
            scY[s] = bandT + (0.25f + 0.24f * s) * (bandB - bandT) +
                (simRng.next() - 0.5f) * 0.05f
        }
        for (i in 0 until n) {
            val s = i % 3
            val gx = gauss()
            val gy = gauss()
            px[i] = BirdsFlockMath.clamp(scX[s] + gx * 0.24f, p.spanX0, p.spanX1)
            py[i] = BirdsFlockMath.clamp(scY[s] + gy * 0.15f, bandT, bandB)
            val sp = spdN * (0.85f + simRng.next() * 0.30f)
            vx[i] = sp * (0.7f + simRng.next() * 0.3f)
            vy[i] = (simRng.next() - 0.5f) * sp * 0.15f
            flapPh[i] = simRng.next() * 2f * PI.toFloat()
            dIdx[i] = i.toFloat() / n            // 深度态均匀铺满 → 4 层各 ≈ N/4
            dSpd[i] = (0.4f + simRng.next()) * 0.02f * (if (simRng.next() < 0.5f) -1f else 1f)
            speedK[i] = 0.82f + simRng.next() * 0.36f
            omegaK[i] = 0.92f + simRng.next() * 0.16f   // 扑动频率抖动 ±8%（Kuramoto K<Kc）
            respK[i] = 0.75f + simRng.next() * 0.50f
            state[i] = 0
            timer[i] = 0f
            isPerched[i] = 0
            perchSlotOf[i] = -1
            bank[i] = 0f
            nbrK[i] = 0
        }
        // 航点状态复位
        wpX = -1f; wpY = -1f
        prevGoalX = -1f; prevGoalY = -1f
        goalX = -1f; goalY = -1f
        wpFade = 1f
        wpEpoch = 0f
        wpN = 0
    }

    /** 近高斯（IRN 中心极限，原型 `g()`：三次均匀均值 − 0.5）。 */
    private fun gauss(): Float =
        (simRng.next() + simRng.next() + simRng.next()) / 3f - 0.5f

    /** 枯树几何（原型 `treeGeom`：同一 seed 确定性生成，画枝与栖点共用 ⇒ 鸟坐在枝尖上）。 */
    fun treeGeom(i: Int, g: BirdsFlockTreeGeom) {
        val rng = BfRng(((90210 + i).toLong() * 2654435761L).toInt())
        g.baseJit = 0.10f + 0.05f * rng.next()
        // D2（2026-10-15 真机返工）：w0f 0.030+0.016 → 0.045+0.024（干宽 ×1.5）——
        // 原型 1600×900 上 6~10px 的干在真机高 DPI 下读成细棍（截图实测"树没显示出来"）
        g.w0f = 0.045f + 0.024f * rng.next()
        g.bendf = (rng.next() - 0.5f) * 2.4f
        g.nBr = 3 + (rng.next() * 2f).toInt()
        for (b in 0 until g.nBr) {
            g.brYf[b] = 0.30f + 0.60f * rng.next()
            // D2：lf 0.26+0.24 → 0.32+0.26（枝长加长，撑出"枯树"读感；栖点 tip 同源自动一致）
            g.brLf[b] = 0.32f + 0.26f * rng.next()
            g.brSide[b] = if (rng.next() < 0.5f) -1f else 1f
            g.brUpA[b] = 0.40f + 0.70f * rng.next()
            g.brFork[b] = rng.next() < 0.7f
            g.brFks[b] = if (rng.next() < 0.5f) -1f else 1f
        }
    }

    // ─────────────────────── 固定步推进 ───────────────────────

    /**
     * 一个固定时间步（§5.8：`FIXED_DT = 1/60`，最多 3 子步，超出丢余量——宁可慢放不追赶）。
     * 航点巡航 → 空间哈希 → 三规则 → 停栖 → NaN 守卫。
     * ⛔ 扑动相位**不在这里**推进（§5.8：扑动不参与动力学决策，归渲染阶段 [stepVisual]）。
     */
    fun step(dt: Float) {
        simTime += dt
        waypoints(dt)
        rebuildGrid()
        steerBoids(dt)
        updatePerch(dt)
        // NaN 守卫（§5.7：一个 NaN 毒化全群 ⇒ 当帧钳回）
        for (i in 0 until n) {
            if (!(px[i].isFinite() && py[i].isFinite() && vx[i].isFinite() && vy[i].isFinite())) {
                nanInfFrames++
                px[i] = BirdsFlockMath.clamp(if (px[i].isFinite()) px[i] else 0.5f, 0f, 1f)
                py[i] = BirdsFlockMath.clamp(if (py[i].isFinite()) py[i] else 0.5f, 0f, 1f)
                vx[i] = if (vx[i].isFinite()) vx[i] else 0f
                vy[i] = if (vy[i].isFinite()) vy[i] else 0f
            }
        }
    }

    /**
     * 航点巡航（原型 `simulate` 的 `_wp*` 段）：飞向当前航点，到达（或超时）换下一点。
     * ⚠️ 连续正弦目标会让「引力内聚 vs 分离外推」互相搏动、速度方向来回翻 ⇒ γ 崩（原型实测 0.056）；
     * 航点模式段间是直线巡航（γ 高），转弯只发生在到点瞬间——椋鸟群的经典读感。
     */
    private fun waypoints(dt: Float) {
        wpEpoch += dt
        if (wpX < 0f || wpEpoch > 14f ||
            (hypot2(goalX - centX, goalY - centY) < 0.12f * 0.12f && wpEpoch > 4f)
        ) {
            wpEpoch = 0f
            prevGoalX = if (wpX < 0f) 0.5f else wpX
            prevGoalY = if (wpY < 0f) 0.45f else wpY
            wpFade = 0f
            wpN++
            val wr = BfRng(wpN * 7919 + 12345)
            wpX = 0.20f + 0.60f * wr.next()
            wpY = 0.25f + 0.40f * wr.next()
        }
        if (prevGoalX < 0f) {
            prevGoalX = wpX
            prevGoalY = wpY
        }
        // 航点渐入（1.5s smoothstep）：避免全群同帧急转把 γ 打到谷底（原型实测转弯期 γ 跌到 0.43）
        wpFade = min(1f, wpFade + dt / 1.5f)
        val fb = wpFade * wpFade * (3f - 2f * wpFade)
        goalX = prevGoalX + (wpX - prevGoalX) * fb
        goalY = prevGoalY + (wpY - prevGoalY) * fb
        // 三条带视差推进（地面不滚——原型地面只有竖向渐变，无横向纹理可滚）
        // ⚠️ 2026-10-15 真机返工 D3 连带：px 系数必须再乘设计高度缩放 `s = h/900` ——
        //    原型的 `vTree=6 / vTuft=22` 是 **1600×900 的设计 px 系数**，不乘 s 时在 1080p/1220p 上
        //    视差慢 20~36%（"极缓慢"会慢成"看不出在动"，与 §5.6 的漂移速率契约不符）。
        val pv = parallaxScale
        val ps = scale()
        treeOff = wrap(treeOff + p.vTreePx() * ps * 0.10f * 30f * dt * pv, w)
        farTreeOff = wrap(farTreeOff + p.vTreePx() * ps * 0.035f * 30f * dt * pv, w)
        tuftOff = wrap(tuftOff + p.vTuftPx() * ps * 0.18f * 30f * dt * pv, w)
    }

    private fun hypot2(dx: Float, dy: Float): Float = dx * dx + dy * dy

    // ─────────────────────── 空间哈希（§4.5）───────────────────────

    /** 空间哈希重建（internal：`BirdsFlockGateTest` 的小 N 交叉验证直接调用）。 */
    internal fun rebuildGrid() {
        java.util.Arrays.fill(cellOf, -1)
        for (i in 0 until n) {
            if (isPerched[i].toInt() != 0) continue
            val cx = (px[i] * w / gridCellW).toInt().coerceIn(0, gridCX - 1)
            val cy = (py[i] * h / gridCellH).toInt().coerceIn(0, gridCY - 1)
            val ci = cy * gridCX + cx
            // 链表头插 + 逐格容量上限（⛔ 上限只防病态格子，不是选择判据）
            var depth = 0
            var cur = cellOf[ci]
            while (cur != -1 && depth < gridCellCap) {
                cur = cellList[cur * 2]
                depth++
            }
            if (depth < gridCellCap) {
                cellList[i * 2] = cellOf[ci]
                cellList[i * 2 + 1] = i
                cellOf[ci] = i
            }
        }
    }

    /**
     * 拓扑 top-7 邻居（§4.5 / §8.3 B2）：3×3 邻格**全部**候选里，前向锥（`dot(r,v) > 0`，
     * 90° 半平面，§4.6 实证够破对称死锁）内按 `d²` 取最近 7 只。
     * ⛔ `nbrR` 不作半径过滤（半径过滤会让大群边缘漏选更远真实邻居）。
     * tie-break：`d²` 相等按插入序（max-heap 稳定）⇒ 同一次运行逐位可复现。
     * ⭐ `internal`（非 private）：`BirdsFlockGateTest` 的小 N 暴力 O(N²) 交叉验证（§11.2 ②）直接调用它。
     */
    internal fun findNeighbors(i: Int) {
        val xi = px[i] * w
        val yi = py[i] * h
        val cx = (xi / gridCellW).toInt().coerceIn(0, gridCX - 1)
        val cy = (yi / gridCellH).toInt().coerceIn(0, gridCY - 1)
        val vxI = vx[i]
        val vyI = vy[i]
        val vlen = sqrt(vxI * vxI + vyI * vyI) + 1e-9f
        val vnx = vxI / vlen
        val vny = vyI / vlen
        val base = i * 14
        var top = 0
        for (gy in -1..1) {
            val ay = cy + gy
            if (ay < 0 || ay >= gridCY) continue
            for (gx in -1..1) {
                val ax = cx + gx
                if (ax < 0 || ax >= gridCX) continue
                var j = cellOf[ay * gridCX + ax]
                while (j != -1) {
                    if (j != i && isPerched[j].toInt() == 0 && state[j].toInt() != 3) {
                        val dxj = px[j] * w - xi
                        val dyj = py[j] * h - yi
                        val d2 = dxj * dxj + dyj * dyj
                        if (d2 > 0.1f && dxj * vnx + dyj * vny > 0f) {
                            val d2i = d2.toInt()
                            if (top < 7) {
                                nid[base + top * 2] = j
                                nid[base + top * 2 + 1] = d2i
                                // 上滤（max-heap：parent ≥ child，root = 最远的 d²）
                                var wI = top
                                while (wI > 0) {
                                    val pi = (wI - 1) shr 1
                                    if (nid[base + pi * 2 + 1] < nid[base + wI * 2 + 1]) {
                                        val t1 = nid[base + pi * 2]; nid[base + pi * 2] = nid[base + wI * 2]; nid[base + wI * 2] = t1
                                        val t2 = nid[base + pi * 2 + 1]; nid[base + pi * 2 + 1] = nid[base + wI * 2 + 1]; nid[base + wI * 2 + 1] = t2
                                        wI = pi
                                    } else break
                                }
                                top++
                            } else if (d2i < nid[base + 1]) {
                                // 替换 root（当前 7 只里最远的），下滤恢复堆
                                nid[base] = j
                                nid[base + 1] = d2i
                                var wI = 0
                                while (true) {
                                    val l = 2 * wI + 1
                                    val r = 2 * wI + 2
                                    var lg = wI
                                    if (l < top && nid[base + l * 2 + 1] > nid[base + lg * 2 + 1]) lg = l
                                    if (r < top && nid[base + r * 2 + 1] > nid[base + lg * 2 + 1]) lg = r
                                    if (lg != wI) {
                                        val t1 = nid[base + lg * 2]; nid[base + lg * 2] = nid[base + wI * 2]; nid[base + wI * 2] = t1
                                        val t2 = nid[base + lg * 2 + 1]; nid[base + lg * 2 + 1] = nid[base + wI * 2 + 1]; nid[base + wI * 2 + 1] = t2
                                        wI = lg
                                    } else break
                                }
                            }
                        }
                    }
                    j = cellList[j * 2]
                }
            }
        }
        nbrK[i] = top.toByte()
    }

    // ─────────────────────── Boids 三规则（§4.1）───────────────────────

    /**
     * 三规则 steering（原型 `steerBoids` 逐字移植）。
     *
     * 实现顺序是**契约**（§4.1 的 T1 判据钉死）：
     * ① 每条规则各自的 desired 归一化 → ② 各自截断 → ③ 优先级累加（分离→对齐→聚合）
     * → ④ 累加结果再截断到 `accelCap`。⛔ 不得把三条 steer 先平均（Reynolds 的两条批评）。
     */
    private fun steerBoids(dt: Float) {
        val spdN = p.maxSpeedN / h
        val sepR = p.sepR
        val wS = p.wSep
        val wA = p.wAli
        val wC = p.wCoh
        val bandN = p.bandInner * h
        val hY = h * p.horizonFrac
        // 游走引力：群质心→目标的**单一**转向向量，全员共用（⛔ 逐鸟指向会差动剪切，撕碎极化）
        var gcx = 0f
        var gcy = 0f
        for (i in 0 until n) {
            gcx += px[i]
            gcy += py[i]
        }
        gcx /= n.coerceAtLeast(1)
        gcy /= n.coerceAtLeast(1)
        centX = gcx
        centY = gcy
        val gxS = (goalX - gcx) * 1.20f * p.goalGain
        val gyS = (goalY - gcy) * 1.20f * p.goalGain
        val centerY = h * (p.spanYTop + p.spanYBot) * 0.5f
        val mEdge = h * 0.02f
        val bandFloor = hY - bandN

        for (i in 0 until n) {
            if (isPerched[i].toInt() != 0) {
                vx[i] *= 0.9f
                vy[i] *= 0.9f
                continue
            }
            if (state[i].toInt() == 3) continue   // 进近落栖：运动由 updatePerch 接管
            val xi = px[i] * w
            val yi = py[i] * h
            val vxI = vx[i]
            val vyI = vy[i]
            findNeighbors(i)
            val k = nbrK[i].toInt()
            val base = i * 14
            var sX = 0f; var sY = 0f
            var aX = 0f; var aY = 0f
            var cX = 0f; var cY = 0f
            for (nn in 0 until k) {
                val j = nid[base + nn * 2]
                val dxj = px[j] * w - xi
                val dyj = py[j] * h - yi
                val d = nid[base + nn * 2 + 1].toFloat()
                val dsqrt = sqrt(d)
                // 有界分离（§4.2，n=2）：`1/d` 近处增强 × 守卫 `(1−d/R)²` 有界
                val guard = sepGuard(dsqrt, sepR)
                if (guard > 0f) {
                    sX -= dxj * guard / (dsqrt + 0.01f)
                    sY -= dyj * guard / (dsqrt + 0.01f)
                }
                aX += vx[j]; aY += vy[j]
                cX += px[j] * w; cY += py[j] * h
            }
            if (k > 0) {
                aX /= k; aY /= k
                cX /= k; cY /= k
            }
            // ① 各自归一化 + 各自截断
            var sLim = spdN * 4f   // 分离冲刺上限（原 *3 压不住稠密团的聚合）
            var sMag = sqrt(sX * sX + sY * sY)
            if (sMag > sLim) {
                sX *= sLim / sMag; sY *= sLim / sMag
            }
            sX *= wS; sY *= wS

            var alX = (aX - vxI) * wA
            var alY = (aY - vyI) * wA
            sLim = spdN * 1.5f
            var alMag = sqrt(alX * alX + alY * alY)
            if (alMag > sLim) {
                alX *= sLim / alMag; alY *= sLim / alMag
            }

            var coX = (cX - xi) * wC * 0.01f
            var coY = (cY - yi) * wC * 0.01f
            var coMag = sqrt(coX * coX + coY * coY)
            if (coMag > sLim) {
                coX *= sLim / coMag; coY *= sLim / coMag
            }

            // ③ 累加 ④ 再截断到 maxForce（`accelCap`，§4.4 推荐①：转弯半径由加速度上界隐性约束）
            var stx = sX + alX + coX
            var sty = sY + alY + coY
            val accelCap = spdN * speedK[i] * respK[i] * 5.5f
            var stMag = sqrt(stx * stx + sty * sty)
            if (stMag > accelCap) {
                stx *= accelCap / stMag
                sty *= accelCap / stMag
            }

            // 游走引力点 + 风场（curl noise：无散度 ⇒ 沿流线运动，读成"风"而非"乱推"，§4.8.2）
            stx += gxS * spdN
            sty += gyS * spdN
            windAt(xi, yi, simTime * p.curlDrift, windOut)
            stx += windOut[0] * spdN * 0.6f
            sty += windOut[1] * spdN * 0.6f

            // 软带：回到活动带中心（⚠️ 原型的简化 y 向线性项，非 smoothstep 双带——照抄）
            sty -= (yi - centerY) * p.kSoft * 0.0008f

            // 切向：只 x 侧竖边（原 y 向切向块实测是贴墙帮凶，已删，§4.7）
            if (xi < bandN) {
                vy[i] += (if (vyI > 0f) 1f else -1f) * p.kTangent * 0.04f
            } else if (xi > w - bandN) {
                vy[i] += (if (vyI > 0f) 1f else -1f) * p.kTangent * 0.04f
            }

            // 速度积分前先整体钳到 accelCap，再叠边界硬约束（⛔ 顺序反了掉队鸟甩不掉——原型实测 47 只沉底）
            if (stx > accelCap) stx = accelCap else if (stx < -accelCap) stx = -accelCap
            if (sty > accelCap) sty = accelCap else if (sty < -accelCap) sty = -accelCap
            // 硬边界：x 两侧 + 天顶 + 地板（地板力必须**向上**：y 向下为正）
            if (xi < mEdge) stx += p.kHard * (mEdge - xi) / mEdge
            if (xi > w - mEdge) stx -= p.kHard * (xi - (w - mEdge)) / mEdge
            if (yi < mEdge) sty += p.kHard * (mEdge - yi) / mEdge
            if (yi > bandFloor) sty -= p.kHard * (yi - bandFloor) / bandN

            var nsX = vxI + stx * dt
            var nsY = vyI + sty * dt
            val spdMax = spdN * maxOf(0.5f, speedK[i] * 2.0f)
            val nsMag = sqrt(nsX * nsX + nsY * nsY)
            if (nsMag > spdMax) {
                nsX *= spdMax / nsMag
                nsY *= spdMax / nsMag
            }
            val spdMin = spdN * 0.35f
            if (nsMag < spdMin && nsMag > 1e-9f) {
                nsX *= spdMin / nsMag
                nsY *= spdMin / nsMag
            }
            vx[i] = nsX
            vy[i] = nsY

            px[i] += nsX * dt
            py[i] += nsY * dt
            if (px[i] < 0f) { px[i] = 0f; vx[i] *= 0.5f }
            if (px[i] > 1f) { px[i] = 1f; vx[i] *= 0.5f }
            if (py[i] < 0f) { py[i] = 0f; vy[i] *= 0.5f }
            if (py[i] > 1f) { py[i] = 1f; vy[i] *= 0.5f }

            // 侧倾（§4.9：滚入:滚出 = 2:1，钳 ±45°；⛔ 不掉高度）
            val aLat = stx * vyI - sty * vxI
            val phiTarget = BirdsFlockMath.bankTarget(aLat, p.g0, spdN)
            bank[i] = BirdsFlockMath.bankStep(bank[i], phiTarget, dt)
        }
    }

    /** curl 风场（§4.8.2：主导横向平流 + 4 项解析无散度涡；⛔ 只做纯涡会恢复双群对跖）。 */
    private fun windAt(x: Float, y: Float, t: Float, out: FloatArray) {
        val baseFx = 0.20f * sin(y * 0.0012f - t * 0.06f) + 0.07f
        val baseFy = 0.05f * sin(x * 0.0010f + t * 0.04f)
        var fx = baseFx
        var fy = baseFy
        for (i in 0 until NWIND) {
            val ph = windK[i] * (x * windDX[i] + y * windDY[i]) - windPH[i] - windK[i] * t * 0.00002f
            val c = cos(ph)
            fx += windK[i] * windDY[i] * windA[i] * c * windScale
            fy += -windK[i] * windDX[i] * windA[i] * c * windScale
        }
        out[0] = fx
        out[1] = fy
    }

    // ─────────────────────── 停栖状态机（§5.9）───────────────────────

    private fun pickPerchSlot(): Int {
        for (attempt in 0 until 20) {
            val k = (simRng.next() * nPerch).toInt()
            if (k in 0 until nPerch && perchTaken[k].toInt() == 0) {
                perchTaken[k] = 1
                return k
            }
        }
        return -1
    }

    /**
     * 停栖状态机（原型 `updatePerch`）：在栖跟随树滚动 → 停够时长起飞（错峰 + 上仰初速）；
     * 飞行鸟随机发起进近（不瞬移，寻的枝尖到位/超时才落）。
     * ⛔ 栖鸟跟随树滚动必须与树带绘制**同源**（同一 `treeOff`），否则树滚走鸟悬空（原型 2026-10-14 实测缺陷）。
     */
    private fun updatePerch(dt: Float) {
        val spdN = p.maxSpeedN / h
        val offT = treeOff
        for (i in 0 until n) {
            if (isPerched[i].toInt() != 0) {
                val slot = perchSlotOf[i]
                if (slot in 0 until nPerch) {
                    val tx = wrap(treeX[perchTree[slot]] - offT, w)
                    px[i] = wrap(tx + perchDx[slot], w) / w
                    py[i] = perchSy[slot] / h
                }
                timer[i] -= dt
                if (timer[i] <= 0f) {
                    // 起飞：归还 slot + 上仰初速（低位枝起飞防坠入地面带）
                    isPerched[i] = 0
                    if (slot >= 0 && slot < MAX_PERCH) {
                        perchTaken[slot] = 0
                        perchSlotOf[i] = -1
                    }
                    state[i] = 0
                    timer[i] = p.takeoffSpread * (0.5f + simRng.next())
                    vx[i] = (if (simRng.next() < 0.5f) -1f else 1f) * spdN * 0.5f
                    vy[i] = -spdN * 0.8f
                    nPerched--
                }
            } else if (state[i].toInt() == 3) {
                // 进近落栖：寻的枝尖（滚动同步），到位/超时才落 —— ⛔ 禁止瞬移（读作闪烁）
                val slot = perchSlotOf[i]
                if (slot < 0 || slot >= nPerch) {
                    state[i] = 0
                    perchSlotOf[i] = -1
                    continue
                }
                val tx = wrap(treeX[perchTree[slot]] - offT, w)
                val tX = wrap(tx + perchDx[slot], w)
                val tY = perchSy[slot]
                val dx = tX - px[i] * w
                val dy = tY - py[i] * h
                val dist = sqrt(dx * dx + dy * dy)
                timer[i] -= dt
                if (dist < 12f || timer[i] <= 0f) {
                    px[i] = tX / w
                    py[i] = tY / h
                    vx[i] = 0f
                    vy[i] = 0f
                    isPerched[i] = 1
                    state[i] = 2
                    timer[i] = p.perchDwellMin + simRng.next() * (p.perchDwellMax - p.perchDwellMin)
                    nPerched++
                } else {
                    val dxn = dx / w
                    val dyn = dy / h
                    val distN = sqrt(dxn * dxn + dyn * dyn) + 1e-9f
                    val sp = min(spdN * 1.4f, spdN * (0.25f + distN * 18f))
                    val kk = min(1f, dt * 3f)
                    vx[i] += ((dxn / distN) * sp - vx[i]) * kk
                    vy[i] += ((dyn / distN) * sp - vy[i]) * kk
                    px[i] = BirdsFlockMath.clamp(px[i] + vx[i] * dt, 0f, 1f)
                    py[i] = BirdsFlockMath.clamp(py[i] + vy[i] * dt, 0f, 1f)
                    bank[i] *= exp(-dt * 3f)
                }
            } else {
                // 随机发起落栖：先进入进近态（不瞬移）
                if (nPerched < perchCap && simRng.next() < 0.0005f && nPerch > 0) {
                    val slot = pickPerchSlot()
                    if (slot >= 0) {
                        perchSlotOf[i] = slot
                        state[i] = 3
                        timer[i] = 6f   // 进近上限（秒），超时强制落
                    }
                }
            }
        }
    }

    // ─────────────────────── 渲染阶段推进（§5.8）───────────────────────

    /**
     * 扑动相位 + 弱同步 + 穿层漂移：**渲染阶段每帧一次**（⛔ 不是模拟阶段）。
     * 扑动不参与任何动力学决策 ⇒ 模拟以任意倍率跑都不影响扑动连续性；
     * 弱同步（Kuramoto，K<Kc：频率抖动 ±8% 保证不全局塌缩——原型两轮实测 flap% 塌到 97%/4% 的教训）。
     */
    fun stepVisual(dt: Float) {
        val omega = 2f * PI.toFloat() * p.flapFreq * 0.6f
        for (i in 0 until n) {
            if (isPerched[i].toInt() != 0) {
                // 在栖：扑动衰减到收翼（sin→0）
                flapPh[i] += (0f - sin(flapPh[i])) * min(1f, dt * 8f)
            } else {
                flapPh[i] += omega * omegaK[i] * dt
                val k = nbrK[i].toInt()
                if (k > 0) {
                    val j = nid[i * 14]
                    if (j in 0 until n && isPerched[j].toInt() == 0 && state[j].toInt() != 3) {
                        val dph = BirdsFlockMath.angleWrapPi(flapPh[j] - flapPh[i])
                        flapPh[i] += dph * min(1f, dt * 0.05f)
                    }
                }
                dIdx[i] = wrap01(dIdx[i] + dSpd[i] * dt)
            }
        }
    }

    companion object {
        /** 状态机常量。 */
        const val STATE_FLY = 0
        const val STATE_PERCHED = 2
        const val STATE_APPROACH = 3

        /** 固定步长（供单测引用，与 [FIXED_DT] 同源）。 */
        const val DT = FIXED_DT
    }
}

/**
 * 视差速率（原型 P 表 `vTree=6 / vTuft=22` 的 px 系数；放在扩展属性避免 Params 变宽）。
 *
 * ⚠️ 这两个数是 **1600×900 的设计 px 系数**，调用点必须再乘 `scale() = h/900`
 * （2026-10-15 真机返工 D3 连带；⛔ 别把它们改成带 s 的版本 —— 那样调用点就重复缩放了）。
 */
internal fun BirdsFlockParams.vTreePx(): Float = 6f

internal fun BirdsFlockParams.vTuftPx(): Float = 22f
