package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.fx.AudioSmoother
import com.nasmusic.tv.visualizer.fx.FxLevel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/*
 * ============================================================================
 *  E46「迷雾之战 · 雾中三脚巨影」绘制侧（K6）
 *  `docs/archive/e46-tripod-fog-plan.md` §五（十一步图层序）/ §A.10（负样本旋钮）/
 *  §十四 1（闸门判解析记录，⛔ 回读像素）
 *  原型：`docs/archive/e46-tripod-fog-preview.html` 的 `render()` 与全部 `draw*`
 * ==========================================================================
 *
 * ## 为什么拆成「计划器 + 解释器」两半
 * §十四 1 要求 **T7–T31 全部判解析记录**（`IMPACT_REC / DUST_REC / BEAM_REC /
 * BREATH_REC / WAKE_REC / ANCH / EMB / SHELL / BANDY / PROF / st.bld / st.crowd / st.grab`），
 * ⛔ 图形设备与像素回读（Android 单测里 `Bitmap` 回读要么 `not mocked` 要么恒空 ⇒ 假绿）。
 * 而 `DrawScope` 侧又受 §五 的 **零分配** 红线约束。两者叠在一起的唯一干净解法是：
 *
 *   [TripodFogPlan] —— **纯 Kotlin、零 Android 依赖的指令发射器**：照原型 `render()` 的
 *   图层序把每一笔「落在哪个坐标、用哪几个色标、 α 多少」算清楚，写进**定容并行数组**，
 *   并在发射的**同一处**填闸门记录与 `PROF`。单测跑的就是这一半（`plan.build(scene)` 之后
 *   直接读 `plan.impactRecs / beamRecs / cmdG / cmdCol / profOps …`），不需要任何图形栈。
 *
 *   [TripodFogRenderer] —— **零分配解释器**：把上面那串指令重放到原生 `Canvas` 上。
 *   `PerfBudgetContractTest` 扫的 `fun DrawScope.draw*` 函数体只覆盖这一半。
 *
 * ⚠️ 两半共用**同一份**几何与同一份色标数组 ⇒ ⛔「闸门重推一套、画面另画一套」
 * （E44 的 D24、本文件 §3.6 的 `wanderRaw` 都是这条教训）。
 */

// ══════════════════════════ 附录 A.10：负样本旋钮（K6 侧）══════════════════════════
//
// ⛔ 上滑杆、⛔ 当观感参数调：这些数**出厂即发布值**，平时走的就是真代码路径；
// 每条旋钮只服务一条负样本，单测里推到越界值后必须复位（try/finally）。
// 之所以是 `var` 而不是 `const`：§A.10 的负样本要注入越界值再走**真实绘制路径**，
// const 根本注入不进去 ⇒ 负样本会退化成恒真式。

/** R23 命中扬尘（⛔ R31 意见 1 起整层默认不画，`on = 0`）—— 旋钮表见 §五 (10)。 */
internal object DustGeom {
    /** 0 = 整段（含 `DUST_REC` 记账）一行都不执行。T23(a) 的负样本置 1。 */
    var on = 0

    /** 尘球半径 = 亮斑半径的倍率（1.75 正是"比光柱还显眼"的成因）。 */
    var r = 1.75

    /** 中心抬起量 = 亮斑半径的倍率（悬空感的另一半）。 */
    var rise = 1.15

    /** 扫速侧倾系数。 */
    var leanK = 0.11

    /** 竖向压缩比。 */
    var squash = 0.62

    /** 1 = 亮度挂 `st.beamI`（含 `fireEnv`）；0 = 只挂未熄灯的原始亮度 ⇒ 熄灯段还亮着。 */
    var fireK = 1

    /** 自带亮度底（负样本：给尘一条自己的亮度曲线，比例判据咬不住）。 */
    var floor = 0.0

    /** 1 = LOW 档不画（与 R23 同档纪律）；0 = 无视档位 ⇒ 低端机一帧多两个全屏级渐变。 */
    var tierK = 1

    /** 1 = 用**扫速** `beamSwV`；0 = 拿**位移** `beamSw·ω` 冒充（同幅、相位差 90°）。 */
    var derK = 1
}

/** R24 机身呼吸：三段各自的相位偏移 + 「呼吸乘到半径上」的负样本旋钮。 */
internal object BreathGeom {
    /** ⛔ 整齐的 1/3（甲壳鼓出去时顶罩正好凹进来 = 屏幕上读成整机缩放）。 */
    var ph = doubleArrayOf(0.0, 0.37, 0.71)

    /** 0 = 半径一律不动（发布路径）。`radK→1` 是 T24(c) 的红线本身。 */
    var radK = 0.0
}

/** R25 掠过段负压（⚠️ 隔离项：`plan.footWakeOn = false` 即整层退，⛔ 拆散进别的函数）。 */
internal object WakeGeom {
    /** 负压包络两端（m）：`fd ≥ d0 ⇒ 0`、`≤ d1 ⇒ 1`。 */
    var d0 = 45.0
    var d1 = 12.0

    /** 雾脊横向偏移（以接触影落笔半宽 `rx` 为单位）。 */
    var side = 1.35

    /** 雾脊半径（同样以 `rx` 为单位）。 */
    var thick = 1.1

    /** 中心通道压暗深度。 */
    var dip = 0.45

    /** α 上限（钳位前后两条 α 都进 `WAKE_REC`）。 */
    var cap = 0.30

    /** 1 = LOW 档不画。 */
    var tierK = 1

    /** T31(a)：1 = 锚退回三脚均值（= R25 第一版的真实缺陷）。 */
    var anchorK = 0.0

    /** T31(b)：>0 = 脊朝屏幕中心滑（"粘在屏幕上"）。 */
    var slipK = 0.0

    /** T31(f)：2 = 每只脚画两遍（画面不变而图元翻倍）。 */
    var dupK = 1
}

/** T29 的两条负样本桩（平时恒 0 ⇒ 0 值下走的就是发布路径，且不产生任何图元）。 */
internal object BmGeom {
    /** 1 = 补回判读轮 7 删掉的「发射口一团光」（T29(a)）。 */
    var muzzle = 0

    /** 1 = 整层跳过地面落点亮斑（T29(b)：所有者点名要留的那层被顺手删掉的等价形态）。 */
    var pool = 0
}

/** R31：落点亮斑的竖向压缩比。写成表项而非字面量，是为了让绘制侧把它记进 `BEAM_REC`。 */
internal object PoolGeom {
    var sq = 0.30
}

/** R27/R30 火星锚点与密度旋钮（⛔ 上滑杆）。 */
internal object EmGeom {
    /** 1 = 钉在**出生那一瞬**的落点（发布值）；0 = 落点取当帧光脚（R15 的手环）；2 = 锚点滞后 3 s。 */
    var anchor = 1

    /** >0 = 只画前 N 粒 ⇒ 条数与参数脱钩（T29(c)）。 */
    var cap = 0

    /** 1 = 种子随条数变（整批重掷，T29(d)）。 */
    var seedVar = 0
}

/** 贴地雾带亮带的三个停点（⛔ 只在这里出现一次；`BANDY` 的 lo/hi 由它派生，T20 读这四个数）。 */
internal object BandStops {
    var lo = 0.0
    var pk = 0.28
    var hi = 1.0
}

// ══════════════════════════════ 调色板字面量 ══════════════════════════════

/** 原型里以 `[r,g,b]` 数组字面量出现、但**不在 `CFG` 表里**的五个色（一次分配，⛔ 逐帧新建）。 */
internal object TripodLiterals {
    /** 贴地雾带亮带的暖白（`[255,253,244]`）。 */
    val WARM = intArrayOf(255, 253, 244)

    /** 负压雾脊 / 冲击环外缘的暖雾白（`[255,250,238]`）。 */
    val HEATH = intArrayOf(255, 250, 238)

    /** 压暗用的纯黑（`[0,0,0]`）。 */
    val BLACK = intArrayOf(0, 0, 0)

    /** 发射口白（`[255,255,245]`，只服务 `BM.muzzle` 负样本桩）。 */
    val MUZZLE = intArrayOf(255, 255, 245)

    /** 落点亮斑内圈「被烧亮的地面」掺白（`[255,251,238]`）。 */
    val POOL_IN = intArrayOf(255, 251, 238)
}

// ══════════════════════════════ 位图槽位 ══════════════════════════════

/** 发射器只认**整数槽位**（纯 Kotlin ⇒ ⛔ 直接持有 `Bitmap`，否则单测要拖图形栈）。 */
internal object TripodSlot {
    const val FOG_A = 0
    const val FOG_B = 1
    const val GRADE = 2
    const val PERSON0 = 3
    const val PERSON_N = 4
    const val BUILDING0 = 7
    const val BUILDING_N = 48
    const val COUNT = BUILDING0 + BUILDING_N

    /** 空槽（⛔ 解释器遇到它必须**连记账一起跳过**，否则 `PROF` 会报一个画面里没有的图元）。 */
    const val NONE = -1
}

/** 烘焙产物 → 槽位。解释器（[TripodFogRenderer]）实现；单测可用固定表代的桩。 */
internal interface TripodBmpTable {
    /** 按**对象身份**返回槽位，未注册返回 [TripodSlot.NONE]。 */
    fun slotOf(bmp: Any?): Int
}

// ══════════════════════════════ 指令编码 ══════════════════════════════

/** 指令类型（发射器与解释器共用的一份契约）。 */
internal object TripodCmd {
    /** g = x,y,w,h —— 位图缩放绘制。 */
    const val BLIT = 0

    /** g = x0,y0,x1,y1, rx,ry,rw,rh —— 线性渐变铺满一个矩形。 */
    const val LIN_RECT = 1

    /** g = x0,y0,x1,y1 —— 线性渐变铺一个多边形。 */
    const val LIN_POLY = 2

    /** 纯色铺一个多边形（顶罩 / 腺囊）。 */
    const val SOLID_POLY = 3

    /** g0 = 线宽 —— 纯色描一个闭合多边形（腿背光轮廓光）。 */
    const val STROKE_POLY = 4

    /** g0 = 线宽，g1..g6 = mx,my,cx,cy,ex,ey —— 纯色描一条二次曲线。 */
    const val QUAD = 5

    /** g0 = cx, g1 = cy, g2 = R, g3 = squash —— 径向渐变铺一个竖向压扁的椭圆。 */
    const val RAD_ELLIPSE = 6

    /** g0..g7 = cx,cy,rx,ry,rotRad,startRad,**endRad**,lineWidth —— 纯色描一段椭圆弧（机背高光）。
     *
     * ⚠️ g6 是**终止角**不是扫角（原型 `:1936` 的 `ctx.ellipse(…, st.roll, Math.PI*1.08, TAU*0.96)`
     * 第三、四参就是 start/end）⇒ 解释器侧扫角 = `g6 − g5`。写成扫角会把机背高光甩到机身下面。
     */
    const val ARC = 7

    /** g0..g3 = x0,y0,x1,y1，g4 = 线宽 —— 纯色描一条直段（火星短划）。 */
    const val SEG = 8

    const val ADDITIVE = 1
    const val ROUND_CAP = 2
}

/**
 * 渐变模板 id —— 解释器的**着色器缓存键**之一（同 id + 同桶 ⇒ 复用同一个 `Shader`，
 * 逐帧只换 `LocalMatrix` 与 `Paint.alpha`，⛔ 逐帧建 `LinearGradient`）。
 *
 * ⭐ 缓存的键是**桶**，而桶里的内容**逐字来自发射器写下的色标**（见 [TripodFogPlan.cmdCol] /
 * [TripodFogPlan.cmdPos]）⇒ 画面用的和闸门读到的是同一份。
 * ⚠️ 发射器在算色标时把**雾系数 `f` 量化到 [TripodFogPlan.FOG_B] 档**、把**地平线位置 `f`
 * 量化到 [TripodFogPlan.SKY_B] 档**，否则 `f` 逐帧连续漂移 ⇒ 缓存每帧必失、退化成就地建渐变。
 * 量化只动 **RGB 两个通道的最后一位**（最大误差 ≈ 1.1/255），α 与一切几何量**不量化**；
 * 全部闸门读的都不是渐变 RGB（T23/T29/T30 = `BEAM_REC` + `PROF`，T20 = 烘焙值 + blit α，
 * T24/T31 = 几何与 α，T14 = `shade()` 的 α 比）⇒ 基线数字一位不动。
 */
internal object TripodGrad {
    const val SKY = 0
    const val BAND = 1
    const val LEG = 2
    const val SHELL = 3
    const val IMPACT = 4
    const val SHADOW = 5
    const val RIDGE = 6
    const val CHANNEL = 7
    const val SCAN = 8
    const val COLUMN = 9
    const val POOL = 10
    const val DUST = 11
    const val MUZZLE = 12
    const val N = 13

    /** 每个模板的桶数（径向 / 线性一律按 `RADIAL` 表判定）。 */
    val BUCKETS = intArrayOf(128, 1, 64, 64, 64, 64, 1, 64, 1, 1, 1, 1, 1)

    /** true = 径向（g0=cx g1=cy g2=R g3=squash）；false = 线性（g0..g3 = 轴两端）。 */
    val RADIAL = booleanArrayOf(false, false, false, false, true, true, true, true, false, false, true, true, true)
}

// ══════════════════════════════ 闸门记录 ══════════════════════════════

/** `IMPACT_REC` 的一条（§五 (7)）：⚠️ **每条 `imp ≥ 0.004` 的腿都记**，含没落笔的那几帧。 */
internal class TripodImpactRec {
    var i = 0
    var swing = false
    var rx = 0.0
    var a = 0.0
    var on = false
}

/** `DUST_REC` 的一条（§五 (10)）：只在 `DUST.on` 时才有 ⇒ 发布路径恒空。 */
internal class TripodDustRec {
    var r = 0.0
    var a = 0.0
    var lean = 0.0
    var swv = 0.0
    var s = 0.0
    var bi = 0.0
    var beamI = 0.0
    var pv = 0.0
    var fire = 0.0
    var tier: TripodLevel = TripodLevel.LOW
}

/** `BEAM_REC` 的一条（R31）：**本道光真正落笔用的**那几个量，⛔ 闸门重推 α 式子。 */
internal class TripodBeamRec {
    var poolOn = true
    var rr = 0.0
    var sq = 0.0
    var footRatio = 0.0
    var peakAt = 0.0
    var nearM = 0.0
    var farM = 0.0
    var aPeak = 0.0
    var beamI = 0.0
}

/** `BREATH_REC` 的一条（R24）：三段**真的用出去的**谐波乘值 + 真实落笔顶/底缘。 */
internal class TripodBreathRec {
    var m0 = 0.0
    var m1 = 0.0
    var m2 = 0.0
    var top = 0.0
    var bot = 0.0
    var cx = 0.0
    var cy = 0.0
    var ph = 0.0
}

/** `WAKE_REC` 的一条（R25/T31）：`kind = "ridge" | "channel"`。 */
internal class TripodWakeRec {
    var i = 0
    var k = 0

    /** `"ridge"` = 被推开的压缩雾脊，`"channel"` = 中心低压通道。 */
    var kind = "ridge"

    var X = 0.0
    var Y = 0.0
    var R = 0.0
    var rx = 0.0

    /** **本只脚**的投影横位（⛔ 闸门自己重投影一份）。 */
    var fx = 0.0

    var a = 0.0
    var wk = 0.0
    var fd = 0.0
}

/** `BANDY`（T20）：本帧亮带**真的用了**的那四个 Y。 */
internal class TripodBandY {
    var top = 0.0
    var peak = 0.0
    var lo = 0.0
    var hi = 0.0
}

/**
 * E46「迷雾之战 · 雾中三脚巨影」的**指令发射器**（§五 十一步图层序的唯一实现）。
 *
 * 用法：`plan.build(scene)` ⇒ 读 `plan.cmdN / cmdKind / cmdG / cmdCol / cmdPos / verts`
 * 重放，读 `plan.impactRecs … plan.profOps / plan.frameOps / plan.frameFill` 判闸门。
 *
 * ## 与原型逐条对齐的纪律
 * - 图层序 = 契约（§五）：`hz` 帧首落地 → 底色 → 雾 A → 建筑 → 贴地雾带 → 三脚 → 负压
 *   → 人群 → 热光 → 火星 → 雾 B → grade，⛔ 换序；
 * - 六张闸门记录 + `ANCH` **在帧首清空**，且清空点落在任何档位早退**之前**（§十四 1：
 *   记录点在早退之后的话，LOW 档的"0 次"是**没采到**而不是**没落笔**）；
 * - `BREATH_REC` 写在 `if (low) return` **之前**（LOW 档同样在呼吸）；
 * - 档位裁掉的层一律**连记账一起**不执行（`op()` 与落笔同生同灭）。
 *
 * ⚠️ 本类**没有任何 Android 依赖** ⇒ 单测里可以直接 `new` 出来跑全圈，不需要 Robolectric。
 */
internal class TripodFogPlan(
    val world: TripodFogWorld,
    private val bmpTable: TripodBmpTable,
) {
    val params = world.params
    val palette = world.palette
    val cam = world.cam

    // ─────────────────────── 指令缓冲（定容并行数组，⛔ 逐帧新建列表）───────────────────────

    val cmdKind = IntArray(CAP)
    val cmdGrad = IntArray(CAP)
    val cmdBucket = IntArray(CAP)
    val cmdFlags = IntArray(CAP)
    val cmdSlot = IntArray(CAP)
    val cmdSolid = IntArray(CAP)
    val cmdAlpha = IntArray(CAP)
    val cmdNstop = IntArray(CAP)
    val cmdV0 = IntArray(CAP)
    val cmdVCnt = IntArray(CAP)
    val cmdG = DoubleArray(CAP * 8)

    /** 本条指令的色标（**打包 ARGB，α 是本帧真用到的那个 α**）—— 闸门读这份，画面也画这份。 */
    val cmdCol = IntArray(CAP * 6)

    /** 本条指令的停点（0…1，首 0 末 1 单调不减）。 */
    val cmdPos = DoubleArray(CAP * 6)

    /** 顶点池：`(x, y)` 成对，多边形指令用 `[cmdV0, cmdV0 + 2·cmdVCnt)` 这段。 */
    val verts = DoubleArray(VCAP)
    var cmdN = 0
        private set
    var vN = 0
        private set
    var cmdOverflow = 0
        private set
    var vOverflow = 0
        private set

    /** 槽位查不到的位图：**连落笔带记账一起跳过**的次数（正常恒 0）。 */
    var droppedBlits = 0
        private set

    // ─────────────────────── PROF（§十 逐类别表的 Kotlin 侧）───────────────────────

    /** 按 [TripodOpItem.ordinal] 索引的本帧落笔次数。 */
    val profOps = IntArray(PROF_N)

    /** 按 [TripodOpItem.ordinal] 索引的本帧铺屏（与视口求交后的面积比）。 */
    val profFill = DoubleArray(PROF_N)

    var frameOps = 0
        private set
    var frameFill = 0.0
        private set

    /** 「光束发射口」的原型标签没有 `TripodOpItem` 行 ⇒ 单独一个计数器（T29(a) 读它）。 */
    var muzzleOps = 0
        private set

    // ─────────────────────── 闸门记录 ───────────────────────

    val impactRecs = Array(3) { TripodImpactRec() }
    var impactN = 0
    val dustRecs = Array(2) { TripodDustRec() }
    var dustN = 0
    val beamRecs = Array(2) { TripodBeamRec() }
    var beamN = 0
    val breathRecs = Array(1) { TripodBreathRec() }
    var breathN = 0
    val wakeRecs = Array(24) { TripodWakeRec() }
    var wakeN = 0
    val bandY = TripodBandY()

    /** `SHELL`：机身组三个部件本帧**真的用出去**的轮廓旋转角（每帧覆写，⛔ 不累积）。 */
    var shellBody = 0.0
    var shellCowl = 0.0
    var shellGland = 0.0

    /** `EMB`：每条火星短划当帧的 `(X, Y, dx, dy, 线宽, α)`，⛔ 屏幕外的坐标。 */
    val emb = DoubleArray(EM_MAX * 6)
    var embN = 0

    /** `ANCH`：七元组 `(si, bi, tb, sx, sz, fx, fz)`，⭐ 一律**世界系**（相机在转头）。 */
    val anchor = DoubleArray(EM_MAX * 7)
    var anchorN = 0

    /**
     * R25 掠过段的**整层开关**（隔离项的 Kotlin 形态：⛔ 把判断散进别处，真要退就是这一行）。
     * 原型对应「删掉 `render` 里 `drawFootWake(st)` 那一行」。
     */
    var footWakeOn = true

    /** 人群精灵表（发射器只取 `bh` 与槽位 ⇒ 由渲染器注入；null = 还没烘好，整层不画）。 */
    var persons: Array<TripodFogBake.PersonBmp>? = null

    // ─────────────────────── 复用暂存（⛔ 逐帧新建）───────────────────────

    private val eg = DoubleArray(8)
    private val ec = IntArray(6)
    private val ep = DoubleArray(6)
    private var en = 0
    private var eKind = 0
    private var eGrad = -1
    private var eBucket = 0
    private var eFlags = 0
    private var eSlot = TripodSlot.NONE
    private var eSolid = 0
    private var eAlpha = 1.0
    private var vBase = 0
    private var vCnt = 0

    private val shadeScr = Shade(0, 0.0, 0.0)
    private val prA = ProjPt()
    private val prB = ProjPt()
    private val prC = ProjPt()
    private val prD = ProjPt()
    private val tmpXY = DoubleArray(2)
    private val tmpXY2 = DoubleArray(2)

    // legCurve 的采样缓冲：19 个世界点 + 19 个屏幕点（两段各 8 步 + 共用中点）
    private val lqx = DoubleArray(LN)
    private val lqy = DoubleArray(LN)
    private val lqz = DoubleArray(LN)
    private val lqw = DoubleArray(LN)
    private val lsx = DoubleArray(LN)
    private val lsy = DoubleArray(LN)
    private val lsR = DoubleArray(LN)
    private val lsS = DoubleArray(LN)
    private val lLx = DoubleArray(LN)
    private val lLy = DoubleArray(LN)
    private val lRx = DoubleArray(LN)
    private val lRy = DoubleArray(LN)

    /** 三脚画家算法：3 条腿 + 1 个机身。 */
    private val itZ = DoubleArray(4)
    private val itLeg = IntArray(4)

    /** 人群按 z 降序的稳定序（插入排序，⛔ `sortedBy`）。 */
    private val crOrder = IntArray(32)
    private val crZ = DoubleArray(32)

    // 火星参数表（`dust`）：只在条数变化时重建 ⇒ 加密是追加（T29(d)）
    // ⛔ internal 只为 **T17 负样本 B/C** —— 那两条要求改的是**这套表本身**
    //   （`dPer[0]=7` ⇒ 寿命不整除 LOOP；全表 `dPer=9 / dLen=1.6` ⇒ `nPer/nLen` 塌成 1），
    //   在测试里另抄一份公式 = 一条不咬自己标签的假负样本。口径与 `TripodGait.LEG_AZ` 同：
    //   生产路径只由 `particles()` 的 `n != dustCount` 分支写入，⛔ 上滑杆、⛔ 在别处改。
    internal val dA = DoubleArray(EM_MAX)
    internal val dPh = DoubleArray(EM_MAX)
    internal val dPer = DoubleArray(EM_MAX)
    internal val dRise = DoubleArray(EM_MAX)
    internal val dSpread = DoubleArray(EM_MAX)
    internal val dLen = DoubleArray(EM_MAX)
    internal val dBr = DoubleArray(EM_MAX)
    internal var dustCount = -1

    /** 抓取缆须的解剖上下文（`TendrilCtx` 每次抓取现建一个 ⇒ 放在字段上复用不了，见方法注释）。 */
    private var cAxl = 0.0
    private var cAyl = 0.0
    private var cAzl = 0.0
    private var cBxl = 0.0
    private var cByl = 0.0
    private var cBzl = 0.0

    // ═══════════════════════════ 发射公共设施 ═══════════════════════════

    private fun beginStops() {
        en = 0
        eg[0] = 0.0; eg[1] = 0.0; eg[2] = 0.0; eg[3] = 0.0
        eg[4] = 0.0; eg[5] = 0.0; eg[6] = 0.0; eg[7] = 0.0
    }

    /**
     * 追加一个色标。⚠️ 停点单调不减化：Skia 要求 positions 递增，而天幕渐变的停点是
     * `f` 的一次函数（`f = hz/H`，随仰角逐帧变）——出厂量程 `f ∈ [0.38, 0.78]` 内**天然单调**，
     * 但滑杆把 `horizonK` 推到极端值时 `max(0.01, f·0.72)` 会越过 `clamp(f−0.055, 0.02, 0.99)`，
     * 原生侧就会拿到一个 nullptr 着色器（画了但一个像素不落）。这条钳位**只在越界时生效**，
     * 出厂量程内逐位等于原型。
     */
    private fun stop(color: Int, at: Double) {
        if (en >= 6) return
        val prev = if (en == 0) 0.0 else ep[en - 1]
        ep[en] = if (at < prev) prev else at
        ec[en] = color
        en++
    }

    private fun beginPoly() {
        vBase = vN
        vCnt = 0
    }

    private fun vx(x: Double, y: Double) {
        if (vN + 2 > VCAP) {
            vOverflow++
            return
        }
        verts[vN] = x
        verts[vN + 1] = y
        vN += 2
        vCnt++
    }

    private fun emit() {
        if (cmdN >= CAP) {
            cmdOverflow++
            return
        }
        val b = cmdN * 8
        val cb = cmdN * 6
        cmdKind[cmdN] = eKind
        cmdGrad[cmdN] = eGrad
        cmdBucket[cmdN] = eBucket
        cmdFlags[cmdN] = eFlags
        cmdSlot[cmdN] = eSlot
        cmdSolid[cmdN] = eSolid
        cmdAlpha[cmdN] = TripodFogMath.alpha8(eAlpha)
        cmdNstop[cmdN] = en
        cmdV0[cmdN] = vBase
        cmdVCnt[cmdN] = vCnt
        var i = 0
        while (i < 8) {
            cmdG[b + i] = eg[i]
            i++
        }
        i = 0
        while (i < en) {
            cmdCol[cb + i] = ec[i]
            cmdPos[cb + i] = ep[i]
            i++
        }
        cmdN++
    }

    /** 与视口求交后的面积比（原型 `vis`：屏外像素不产生填充，按原始尺寸记账会虚报 2 倍以上）。 */
    private fun vis(x: Double, y: Double, w: Double, h: Double): Double {
        val W = cam.w
        val H = cam.h
        val x0 = max(0.0, min(W, x))
        val x1 = max(0.0, min(W, x + w))
        val y0 = max(0.0, min(H, y))
        val y1 = max(0.0, min(H, y + h))
        return max(0.0, x1 - x0) * max(0.0, y1 - y0) / (W * H)
    }

    /** 原型 `op(a, tag)`：一次落笔 = 一个图元 + 一份铺屏，逐类进 [profOps]。 */
    private fun op(fill: Double, item: TripodOpItem) {
        frameOps++
        frameFill += fill
        profOps[item.ordinal]++
        profFill[item.ordinal] += fill
    }

    /** 没有 `TripodOpItem` 行的那一类（光束发射口）走独立计数器。 */
    private fun opMuzzle(fill: Double) {
        frameOps++
        frameFill += fill
        muzzleOps++
    }

    private fun fogBucket(f: Double): Int =
        (f * FOG_B).toInt().coerceIn(0, FOG_B - 1)

    /** 桶中心的雾系数（⛔ 用当帧连续的 `f`：那样缓存每帧必失）。 */
    private fun fogOfBucket(b: Int): Double = (b + 0.5) / FOG_B

    private fun skyBucket(f: Double): Int = (f * SKY_B).toInt().coerceIn(0, SKY_B - 1)

    private fun skyCenter(f: Double): Double = (f.coerceIn(0.0, 1.0) * SKY_B).let {
        (kotlin.math.floor(it) + 0.5) / SKY_B
    }

    // ═══════════════════════════ 帧入口：十一步图层序 ═══════════════════════════

    /**
     * 跑一帧的**全部几何与记账**（不含任何 Android 绘制）。返回本帧指令条数。
     *
     * ⚠️ 参数是 [TripodGait.sceneState] 的产物；本函数**不推进**世界状态（档位由
     * `world.tier` 决定，时间轴在 K7 的渲染器侧）。
     */
    fun build(st: TripodScene): Int {
        // ① 帧首清空：计数 + 六张闸门记录 + ANCH（⛔ 放在任何早退之后）
        cmdN = 0
        vN = 0
        vBase = 0
        vCnt = 0
        cmdOverflow = 0
        vOverflow = 0
        droppedBlits = 0
        frameOps = 0
        frameFill = 0.0
        muzzleOps = 0
        var i = 0
        while (i < PROF_N) {
            profOps[i] = 0
            profFill[i] = 0.0
            i++
        }
        impactN = 0
        dustN = 0
        beamN = 0
        breathN = 0
        wakeN = 0
        embN = 0
        anchorN = 0
        bandY.top = 0.0
        bandY.peak = 0.0
        bandY.lo = 0.0
        bandY.hi = 0.0
        shellBody = 0.0
        shellCowl = 0.0
        shellGland = 0.0

        // ② 仰角落地：地平线整体下移 = 相机抬头。⚠️ 必须在任何图层之前改 `cam.hz`，
        //    天幕渐变 / 贴地雾带 / 投影三处共用同一个 hz，漏一处就会出现「楼浮在半空」。
        cam.hz = cam.h * params.horizonK + st.tilt
        val low = world.tier == TripodLevel.LOW

        skyBase()
        fogLayer(TripodSlot.FOG_A, 0.62, st.fogPhA, min(0.60, params.fogA * (if (low) 1.35 else 1.0)))
        buildings(st)
        groundBand(st)
        tripod(st)
        if (footWakeOn) footWake(st)
        crowd(st)
        beams(st)
        particles(st)
        if (!low) {
            fogLayer(TripodSlot.FOG_B, 0.44, st.fogPhB, params.fogB)
            grade()
        }
        return cmdN
    }

    // ═══════════════════════════ 图层 1：天幕底色渐变 ═══════════════════════════

    /** 原型 `drawBase`：6 停靠线性渐变铺满全屏（含地平线余烬暖光），1 图元 / 1.000 屏。 */
    private fun skyBase() {
        val H = cam.h
        val fSky = skyCenter(cam.hz / H)
        val e = params.ember
        val pal = palette
        beginStops()
        eKind = TripodCmd.LIN_RECT
        eGrad = TripodGrad.SKY
        eBucket = skyBucket(fSky)
        eFlags = 0
        eAlpha = 1.0
        stop(TripodFogMath.mixPacked(pal.sky, pal.fog, 0.15, 1.0), 0.0)
        stop(TripodFogMath.mixPacked(pal.sky, pal.fog, 0.78, 1.0), max(0.01, fSky * 0.72))
        stop(TripodFogMath.mixPacked(pal.fog, pal.ember, e * 0.5, 1.0), TripodFogMath.clamp(fSky - 0.055, 0.02, 0.99))
        stop(TripodFogMath.mixPacked(pal.fog, pal.ember, e, 1.0), TripodFogMath.clamp(fSky - 0.004, 0.02, 0.99))
        stop(TripodFogMath.mixPacked(pal.fog, pal.ember, e * 0.26, 1.0), TripodFogMath.clamp(fSky + 0.014, 0.03, 0.995))
        stop(TripodFogMath.mixPacked(pal.gnd, pal.fog, 0.18, 1.0), 1.0)
        // 铺满整幅（原型 `fillRect(-10,-10,W+20,H+20)`：留出 10 px 给手持平移的余边）
        eg[0] = 0.0; eg[1] = 0.0; eg[2] = 0.0; eg[3] = H
        eg[4] = -10.0; eg[5] = -10.0; eg[6] = cam.w + 20.0; eg[7] = H + 20.0
        emit()
        op(1.0, TripodOpItem.SKY_BASE)
    }

    // ═══════════════════════════ 图层 2 / 10：流动雾条 ═══════════════════════════

    /** 原型 `drawFogLayer`：横向平铺的整幅雾条，逐块记账（一块 = 一次 blit = 一个图元）。 */
    private fun fogLayer(slotName: Int, wK: Double, ph: Double, alpha: Double) {
        if (alpha <= 0.001) return
        val W = cam.w
        val H = cam.h
        val tw = W * wK
        val th = H * 1.06
        val x0 = -TripodFogMath.mod(ph, 1.0) * tw
        var n = 0
        while (n * tw < W + tw) {
            val x = x0 + n * tw
            beginStops()
            eKind = TripodCmd.BLIT
            eGrad = -1
            eBucket = 0
            eFlags = 0
            eSlot = slotName
            eAlpha = alpha
            eg[0] = x; eg[1] = -H * 0.03; eg[2] = tw; eg[3] = th
            emit()
            op(vis(x, -H * 0.03, tw, th), TripodOpItem.FOG_STRIP)
            n++
        }
    }

    // ═══════════════════════════ 图层 3：建筑剪影 ═══════════════════════════

    /**
     * 原型 `drawBuildings`。⛔ 逐帧小矩形（创维 API 22 的 SIGSEGV 触发器）——一栋**一次 blit**。
     *
     * 排序口径：两带各按 z **升序**存放（K4 的 `pushBand`），此处从两条尾巴往前并 =
     * 按 z 降序（远 → 近）；平局先中景带 ⇒ 与原型的**稳定**降序排序逐位一致。
     */
    private fun buildings(st: TripodScene) {
        val yaw = st.yaw
        val p = params
        val PPM = cam.ppm
        val yawRate = world.yh.deriv(st.t) * Math.PI / 180
        val low = world.tier == TripodLevel.LOW
        val blur = if (low) 0.0 else TripodFogMath.clamp(abs(yawRate) * 3.0, 0.0, 1.5)
        val signYaw = if (yawRate == 0.0) 1.0 else sign(yawRate)
        var iMid = st.bldMidN - 1
        var iNear = st.bldNearN - 1
        while (iMid >= 0 || iNear >= 0) {
            val b: BldSel
            if (iNear < 0 || (iMid >= 0 && st.bldMid[iMid].z >= st.bldNear[iNear].z)) {
                b = st.bldMid[iMid]
                iMid--
            } else {
                b = st.bldNear[iNear]
                iNear--
            }
            if (b.a <= 0.002 || b.z < 2) continue          // ⛔ 几何前早退
            val p0 = TripodFogMath.prInto(prA, cam, p, b.x, 0.0, b.z, yaw, tmpXY)
                ?: continue
            val p1 = TripodFogMath.prInto(prB, cam, p, b.x, b.h, b.z, yaw, tmpXY)
                ?: continue
            var wpx = b.w * PPM * p0.s
            if (b.band == 1) wpx *= 1 + blur * 1.6
            val X = p0.X - wpx / 2 - (if (b.band == 1) blur * 30.0 * signYaw else 0.0)
            val Y = p1.Y
            val hh = p0.Y - p1.Y
            if (X + wpx < -20 || X > cam.w + 20 || hh < 0.5) continue
            TripodFogMath.shadeInto(shadeScr, hypot(b.x, b.z), st.d0, p, palette)
            val A = TripodFogMath.clamp(shadeScr.a * b.a * b.ink, 0.0, 1.0)
            if (A < 0.004) continue
            blit(bmpTable.slotOf(b.src?.bmp), X, Y, wpx, hh, A, TripodOpItem.BUILDINGS)
            val area = vis(X, Y, wpx, hh)
            if (low) continue
            var j = 0
            while (j < 2) {
                val bm = st.beams[j]
                if (abs(bm.hz - b.z) < b.dep * 0.6 + 9 && abs(bm.hx - b.x) < b.w * 0.5 + 6) {
                    beginStops()
                    eKind = TripodCmd.LIN_RECT
                    eGrad = TripodGrad.SCAN
                    eBucket = 0
                    eFlags = 0
                    eAlpha = 1.0
                    stop(TripodFogMath.rgba(palette.beam, 0.30 * A), 0.0)
                    stop(TripodFogMath.rgba(palette.beam, 0.0), 1.0)
                    eg[0] = X; eg[1] = Y; eg[2] = X; eg[3] = Y + hh
                    eg[4] = X; eg[5] = Y; eg[6] = wpx; eg[7] = hh
                    emit()
                    op(area * 0.6, TripodOpItem.BUILDING_SCAN)
                }
                j++
            }
        }
    }

    /** 位图类落笔：槽位查不到 ⇒ **连记账一起跳过**（⛔ 报一个画面里不存在的图元）。 */
    private fun blit(slot: Int, x: Double, y: Double, w: Double, h: Double, alpha: Double, item: TripodOpItem) {
        if (slot == TripodSlot.NONE) {
            droppedBlits++
            return
        }
        beginStops()
        eKind = TripodCmd.BLIT
        eGrad = -1
        eBucket = 0
        eFlags = 0
        eSlot = slot
        eAlpha = alpha
        eg[0] = x; eg[1] = y; eg[2] = w; eg[3] = h
        emit()
        op(vis(x, y, w, h), item)
    }

    // ═══════════════════════════ 图层 4：贴地雾带（R19）═══════════════════════════

    /**
     * 原型 `drawGroundBand`：带顶锚在**远带内沿的楼基线**（`BAND.mid.zMin` 的地面投影），
     * ⛔ 跨在 hz 上（那会把每条远立面同一高度各切一刀 = 「半透明玻璃盒子」）。
     */
    private fun groundBand(st: TripodScene) {
        val p = params
        val H = cam.h
        val zRef = world.bandMid.zMin
        val yTop = cam.hz + p.eye * cam.ppm * (p.fz / (p.fz + zRef))
        val span = H + 10 - yTop
        bandY.top = yTop
        bandY.peak = yTop + BandStops.pk * span
        bandY.lo = yTop + (BandStops.pk / 2) * span
        bandY.hi = yTop + (BandStops.pk + (1 - BandStops.pk) / 2) * span
        if (p.groundBand <= 0.001) {
            bandY.peak = H + 10
            return
        }
        val pal = palette
        beginStops()
        eKind = TripodCmd.LIN_RECT
        eGrad = TripodGrad.BAND
        eBucket = 0
        eFlags = 0
        eAlpha = 1.0
        stop(TripodFogMath.rgba(pal.fog, 0.0), BandStops.lo)
        stop(TripodFogMath.mixPacked(pal.fog, TripodLiterals.WARM, 0.4, p.groundBand), BandStops.pk)
        stop(TripodFogMath.rgba(pal.fog, 0.0), BandStops.hi)
        eg[0] = 0.0; eg[1] = yTop; eg[2] = 0.0; eg[3] = yTop + span
        eg[4] = -10.0; eg[5] = yTop; eg[6] = cam.w + 20.0; eg[7] = span
        emit()
        op(vis(0.0, yTop, cam.w, span), TripodOpItem.GROUND_BAND)
    }

    // ═══════════════════════════ 图层 5：三脚巨影 ═══════════════════════════

    /** 原型 `drawTripod`：画家算法（远 → 近）+ R22 落地冲击 + 接触影 + 机身组 + 抓取。 */
    private fun tripod(st: TripodScene) {
        val p = params
        val yaw = st.yaw
        val pose = TripodGait.tripodPose(world, st)
        itZ[0] = (pose.feet[0].z + pose.hips[0].z) / 2; itLeg[0] = 0
        itZ[1] = (pose.feet[1].z + pose.hips[1].z) / 2; itLeg[1] = 1
        itZ[2] = (pose.feet[2].z + pose.hips[2].z) / 2; itLeg[2] = 2
        itZ[3] = st.d + 8.5; itLeg[3] = -1
        var i = 1
        while (i < 4) {
            val z = itZ[i]
            val l = itLeg[i]
            var j = i - 1
            while (j >= 0 && itZ[j] < z) {          // 插入排序降序（⛔ sortedBy）
                itZ[j + 1] = itZ[j]
                itLeg[j + 1] = itLeg[j]
                j--
            }
            itZ[j + 1] = z
            itLeg[j + 1] = l
            i++
        }
        // ── R22 落地冲击压雾（⚠️ 记录在 `on` 判定**之前**写：闸门要能判"为什么没落笔"）──
        i = 0
        while (i < 3) {
            val L = st.legs[i]
            val imp = L.imp
            if (imp >= 0.004) {
                val ft = pose.feet[i]
                val q = TripodFogMath.prInto(prA, cam, p, ft.x, 0.06, ft.z, yaw, tmpXY)
                val fd = hypot(ft.x, ft.z)
                val w = TripodFogMath.clamp(TripodFogMath.impProgress(L.u), 0.0, 1.0)
                val rx = (ImpactGeom.R_IN + (ImpactGeom.R_OUT - ImpactGeom.R_IN) * w) * cam.ppm *
                    (if (q != null) q.s else 0.0)
                val a = if (q != null) {
                    TripodFogMath.shadeInto(shadeScr, fd, st.d0, p, palette)
                    TripodFogMath.clamp(p.impactA * imp * (1 - shadeScr.fog * 0.8), 0.0, 1.0)
                } else {
                    0.0
                }
                val on = q != null && rx >= 1 && a >= 0.01
                val rec = impactRecs[impactN.coerceAtMost(2)]
                if (impactN < 3) impactN++
                rec.i = i
                rec.swing = L.swing
                rec.rx = rx
                rec.a = a
                rec.on = on
                if (on && q != null) {
                    val ry = rx * ImpactGeom.SQUASH
                    TripodFogMath.shadeInto(shadeScr, fd, st.d0, p, palette)
                    val bkt = fogBucket(shadeScr.fog)
                    val dk = TripodFogMath.mixPacked(palette.silh, palette.fog, shadeScr.fog * 0.35, a * ImpactGeom.DIP)
                    val lt = TripodFogMath.mixPacked(palette.fog, TripodLiterals.HEATH, 0.3, a)
                    beginStops()
                    eKind = TripodCmd.RAD_ELLIPSE
                    eGrad = TripodGrad.IMPACT
                    eBucket = bkt
                    eFlags = 0
                    eAlpha = 1.0
                    stop(dk, 0.0)
                    stop(TripodFogMath.mixPacked(palette.silh, palette.fog, shadeScr.fog * 0.35, 0.0), 0.45)
                    stop(lt, 0.78)
                    stop(TripodFogMath.mixPacked(palette.fog, TripodLiterals.HEATH, 0.3, 0.0), 1.0)
                    eg[0] = q.X; eg[1] = q.Y; eg[2] = rx; eg[3] = ImpactGeom.SQUASH
                    emit()
                    op(Math.PI * rx * ry * 0.35 / (cam.w * cam.h), TripodOpItem.IMPACT_RING)
                }
            }
            i++
        }
        // ── 脚底接触影（LOW 不画；软径向渐变，⛔ 实心椭圆 = 「贴纸」的卡通感）──
        if (world.tier != TripodLevel.LOW) {
            i = 0
            while (i < 3) {
                val ft = pose.feet[i]
                val q = TripodFogMath.prInto(prA, cam, p, ft.x, 0.06, ft.z, yaw, tmpXY)
                if (q != null) {
                    val fd = hypot(ft.x, ft.z)        // ⚠️ 真距离，⛔ ft.z
                    TripodFogMath.shadeInto(shadeScr, fd, st.d0, p, palette)
                    val a = (0.34 + 0.20 * st.legs[i].imp) * (1 - shadeScr.fog * 0.8)
                    if (a >= 0.01) {
                        val rx = p.legR * 4.6 * cam.ppm * q.s
                        val ry = rx * 0.32
                        val bkt = fogBucket(shadeScr.fog)
                        val sc = TripodFogMath.mixPacked(palette.silh, palette.fog, shadeScr.fog * 0.45, 1.0)
                        beginStops()
                        eKind = TripodCmd.RAD_ELLIPSE
                        eGrad = TripodGrad.SHADOW
                        eBucket = bkt
                        eFlags = 0
                        eAlpha = 1.0
                        stop(TripodFogMath.rgba(sc, a), 0.0)
                        stop(TripodFogMath.rgba(sc, a * 0.45), 0.55)
                        stop(TripodFogMath.rgba(sc, 0.0), 1.0)
                        eg[0] = q.X; eg[1] = q.Y; eg[2] = rx; eg[3] = ry / rx
                        emit()
                        op(Math.PI * rx * ry * 0.35 / (cam.w * cam.h), TripodOpItem.FOOT_SHADOW)
                    }
                }
                i++
            }
        }
        i = 0
        while (i < 4) {
            val leg = itLeg[i]
            if (leg < 0) {
                body(st, yaw)
            } else {
                val L = st.legs[leg]
                legCurve(pose.hips[leg], pose.knees[leg], pose.feet[leg], L.th + st.psi, yaw, st.d0)
            }
            i++
        }
        grab(st, yaw)
    }

    /**
     * 原型 `legCurve`：髋 → 外弓膝 → 尖脚 两段二次贝塞尔，沿弧按 `[2.1, 1.55, 0.16]·legR` 连续变宽。
     * 一次渐变填充 + 一次描边 = 2 图元 / 腿。
     *
     * ⚠️ **与原型的一处已知分歧**（原型 :1840 的静默 bug）：描边宽度原型读 `k.s`，而 `scr` 的条目
     * 只有 `{X, Y, r, z}` ⇒ `undefined` ⇒ `NaN` ⇒ Canvas2D **忽略该赋值**、沿用上一笔的 `lineWidth`
     * （画出来"看着还行"纯粹是巧合，且随上一笔是谁而变）。Kotlin 侧存了投影比 `lsS` 并用它
     * （`max(0.8, legR·0.35·PPM·s·0.25)`）。图元数 / 铺屏 / 闸门记录三者都不受影响 ⇒ 无基线漂移。
     */
    private fun legCurve(hip: BodyPt, knee: BodyPt, foot: BodyPt, th: Double, yaw: Double, d0: Double): Boolean {
        val p = params
        val PPM = cam.ppm
        val nX = sin(th)
        val nZ = -cos(th)
        val bow = p.kneeBow
        ctrlPoint(hip.x, hip.y, hip.z, knee.x, knee.y, knee.z, nX, nZ, bow)
        cAxl = cx1; cAyl = cy1; cAzl = cz1
        ctrlPoint(knee.x, knee.y, knee.z, foot.x, foot.y, foot.z, nX, nZ, bow)
        cBxl = cx1; cByl = cy1; cBzl = cz1
        val wr0 = p.legR * 2.1
        val wr1 = p.legR * 1.55
        val wr2 = p.legR * 0.16
        var i = 0
        while (i <= NSEG) {
            val u = i.toDouble() / NSEG
            quadPoint(hip.x, hip.y, hip.z, cAxl, cAyl, cAzl, knee.x, knee.y, knee.z, u)
            lqx[i] = qx; lqy[i] = qy; lqz[i] = qz
            lqw[i] = TripodFogMath.lerp(wr0, wr1, u)
            i++
        }
        i = 1
        while (i <= NSEG) {
            val u = i.toDouble() / NSEG
            quadPoint(knee.x, knee.y, knee.z, cBxl, cByl, cBzl, foot.x, foot.y, foot.z, u)
            lqx[NSEG + i] = qx; lqy[NSEG + i] = qy; lqz[NSEG + i] = qz
            lqw[NSEG + i] = TripodFogMath.lerp(wr1, wr2, u)
            i++
        }
        i = 0
        while (i < LN) {
            val c = TripodFogMath.prInto(prA, cam, p, lqx[i], lqy[i], lqz[i], yaw, tmpXY) ?: return false
            lsx[i] = c.X
            lsy[i] = c.Y
            lsR[i] = lqw[i] * PPM * c.s
            lsS[i] = c.s
            i++
        }
        // 屏幕法向：端点单侧差分、中间中心差分 ⇒ 弯处不会把宽度拧歪
        i = 0
        while (i < LN) {
            val a = if (i == 0) 0 else i - 1
            val b = if (i == LN - 1) LN - 1 else i + 1
            val dx = lsx[b] - lsx[a]
            val dy = lsy[b] - lsy[a]
            val m = hypot(dx, dy).let { if (it != 0.0) it else 1e-6 }
            val nx = -dy / m
            val ny = dx / m
            lLx[i] = lsx[i] + nx * lsR[i]
            lLy[i] = lsy[i] + ny * lsR[i]
            lRx[i] = lsx[i] - nx * lsR[i]
            lRy[i] = lsy[i] - ny * lsR[i]
            i++
        }
        TripodFogMath.shadeInto(shadeScr, hypot((hip.x + foot.x) / 2, (hip.z + foot.z) / 2), d0, p, palette)
        beginPoly()
        i = 0
        while (i < LN) {
            vx(lLx[i], lLy[i])
            i++
        }
        i = LN - 1
        while (i >= 0) {
            vx(lRx[i], lRy[i])
            i--
        }
        var x0 = 1e9
        var x1 = -1e9
        var y0 = 1e9
        var y1 = -1e9
        i = 0
        while (i < LN) {
            if (lLx[i] < x0) x0 = lLx[i]
            if (lLx[i] > x1) x1 = lLx[i]
            if (lLy[i] < y0) y0 = lLy[i]
            if (lLy[i] > y1) y1 = lLy[i]
            if (lRx[i] < x0) x0 = lRx[i]
            if (lRx[i] > x1) x1 = lRx[i]
            if (lRy[i] < y0) y0 = lRy[i]
            if (lRy[i] > y1) y1 = lRy[i]
            i++
        }
        // 横向渐变 = 圆柱：内侧（靠机身一侧）压暗，外侧受天光提亮
        val mid = LN / 2
        val half = lqw[mid] * PPM
        var gx = lsx[mid] - cam.cx
        if (gx == 0.0) gx = 1e-6
        val dir = if (gx > 0) 1.0 else -1.0
        val pal = palette
        val fB = fogBucket(shadeScr.fog)
        val fq = fogOfBucket(fB)
        val colMid = TripodFogMath.mixPacked(pal.silh, pal.fog, TripodFogMath.clamp(fq, 0.0, 0.995) * 0.45, 1.0)
        beginStops()
        eKind = TripodCmd.LIN_POLY
        eGrad = TripodGrad.LEG
        eBucket = fB
        eFlags = 0
        eAlpha = 1.0
        stop(TripodFogMath.mixPacked(colMid, TripodLiterals.BLACK, 0.42, shadeScr.a), 0.0)
        stop(TripodFogMath.rgba(colMid, shadeScr.a), 0.62)
        stop(TripodFogMath.mixPacked(colMid, pal.rim, 0.30, shadeScr.a), 1.0)
        eg[0] = lsx[mid] - dir * half; eg[1] = lsy[mid]
        eg[2] = lsx[mid] + dir * half; eg[3] = lsy[mid]
        emit()
        op(vis(x0, y0, x1 - x0, y1 - y0), TripodOpItem.LEGS)
        if (world.tier == TripodLevel.LOW) return true
        beginStops()
        eKind = TripodCmd.STROKE_POLY
        eGrad = -1
        eBucket = 0
        eFlags = 0
        eSolid = TripodFogMath.mixPacked(pal.rim, pal.fog, shadeScr.fog * 0.55, shadeScr.a * 0.42)
        eAlpha = 1.0
        eg[0] = max(0.8, p.legR * 0.35 * PPM * lsS[mid] * 0.25)
        emit()
        op(vis(x0, y0, x1 - x0, y1 - y0) * 0.12, TripodOpItem.LEG_RIM)
        return true
    }

    // ── 贝塞尔暂存（⛔ 每点新建对象）──
    private var cx1 = 0.0
    private var cy1 = 0.0
    private var cz1 = 0.0
    private var qx = 0.0
    private var qy = 0.0
    private var qz = 0.0

    /** 原型 `ctrl(a, b)`：把 `b − 0.30·(b−a)` 再沿外法向偏 `bow·L`（y 不参与弓形）。 */
    private fun ctrlPoint(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, nX: Double, nZ: Double, bow: Double) {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        val l = hypot(hypot(dx, dy), dz).let { if (it != 0.0) it else 1.0 }
        cx1 = bx - dx * 0.30 + nX * bow * l
        cy1 = by - dy * 0.30
        cz1 = bz - dz * 0.30 + nZ * bow * l
    }

    private fun quadPoint(ax: Double, ay: Double, az: Double, cx: Double, cy: Double, cz: Double, bx: Double, by: Double, bz: Double, u: Double) {
        val m = 1 - u
        val m2 = m * m
        val t = 2 * m * u
        val u2 = u * u
        qx = m2 * ax + t * cx + u2 * bx
        qy = m2 * ay + t * cy + u2 * by
        qz = m2 * az + t * cz + u2 * bz
    }

    // ── 机身组（原型 `lumpPath` 的落笔顶/底缘直接进 [breathRecs]）──
    private var lumpY0 = 0.0
    private var lumpY1 = 0.0

    /** 原型 `lumpPath`：N=28 的三重谐波不规则甲壳（形状是常值函数，R24 只给**幅度**包络）。 */
    private fun lumpPath(ccx: Double, ccy: Double, rx: Double, ry: Double, roll: Double, lump: Double) {
        beginPoly()
        val cr = cos(roll)
        val sr = sin(roll)
        var y0 = 1e9
        var y1 = -1e9
        var i = 0
        while (i <= LUMP_N) {
            val a = TripodFogMath.TAU * i / LUMP_N
            val k = 1 + lump * (sin(3 * a + 0.7) + 0.62 * sin(5 * a - 1.3) + 0.34 * sin(2 * a + 2.4))
            val u = cos(a) * rx * k
            val v = sin(a) * ry * k
            val X = ccx + u * cr - v * sr
            val Y = ccy + u * sr + v * cr
            vx(X, Y)
            if (Y < y0) y0 = Y
            if (Y > y1) y1 = Y
            i++
        }
        lumpY0 = y0
        lumpY1 = y1
    }

    /** 原型 `tendrilSway`：⛔ 在 `drawBody` 与 `drawGrab` 里各写一遍（同相才是一条束）。 */
    private fun tendrilSway(st: TripodScene): Double =
        st.bodyLat * 0.9 + params.tendLen * 0.22 * sin(TripodFogMath.TAU * st.s + 1.4)

    /** 原型 `drawBody`：甲壳（线性渐变）+ 顶罩 + 腺囊 + 机背高光 + 悬垂缆须。 */
    private fun body(st: TripodScene, yaw: Double) {
        val p = params
        val PPM = cam.ppm
        val low = world.tier == TripodLevel.LOW
        val pal = palette
        TripodFogMath.shadeInto(shadeScr, st.dist, st.d0, p, pal)
        val hy = st.headYaw
        val psi = st.psi
        val bc = TripodFogMath.bodyFrame(st.bodyLat, 0.5 + st.leanFwd, st.bodyY + 0.3, psi, st.d, st.mx)
        val c = TripodFogMath.prInto(prA, cam, p, bc.x, bc.y, bc.z, yaw, tmpXY) ?: return
        val bm0 = 1 + p.breathA * sin(TripodFogMath.TAU * (st.breathPh + BreathGeom.ph[0]))
        val bm1 = 1 + p.breathA * sin(TripodFogMath.TAU * (st.breathPh + BreathGeom.ph[1]))
        val bm2 = 1 + p.breathA * sin(TripodFogMath.TAU * (st.breathPh + BreathGeom.ph[2]))
        val rs = 1 + BreathGeom.radK * (bm0 - 1)
        val rx = p.bodyRx * PPM * c.s * rs
        val ry = p.bodyRy * PPM * c.s * rs
        val fB = fogBucket(shadeScr.fog)
        val colShell = TripodFogMath.mixPacked(
            pal.silh, pal.fog, TripodFogMath.clamp(fogOfBucket(fB), 0.0, 0.995) * 0.45, 1.0,
        )
        lumpPath(c.X, c.Y, rx, ry, st.roll, p.bodyLump * bm0)
        var bTop = lumpY0
        var bBot = lumpY1
        beginStops()
        eKind = TripodCmd.LIN_POLY
        eGrad = TripodGrad.SHELL
        eBucket = fB
        eFlags = 0
        eAlpha = 1.0
        stop(TripodFogMath.mixPacked(colShell, pal.rim, 0.16, shadeScr.a), 0.0)
        stop(TripodFogMath.rgba(colShell, shadeScr.a), 0.5)
        stop(TripodFogMath.mixPacked(colShell, TripodLiterals.BLACK, 0.45, shadeScr.a), 1.0)
        eg[0] = c.X; eg[1] = c.Y - ry * 1.3; eg[2] = c.X; eg[3] = c.Y + ry * 1.3
        emit()
        op(vis(c.X - rx * 1.2, c.Y - ry * 1.3, rx * 2.4, ry * 2.6), TripodOpItem.SHELL)
        shellBody = st.roll
        // 顶罩（cowl）：`topY()` 的口径来源
        val cl = TripodFogMath.bodyFrame(
            st.bodyLat + sin(hy) * p.cowlFwd,
            0.5 + st.leanFwd + cos(hy) * p.cowlFwd,
            TripodFogMath.cowlY(p), psi, st.d, st.mx,
        )
        val cw = TripodFogMath.prInto(prB, cam, p, cl.x, cl.y, cl.z, yaw, tmpXY)
        if (cw != null) {
            val wrx = p.cowlRx * PPM * cw.s
            val wry = p.cowlRy * PPM * cw.s
            val roll2 = st.roll + p.headSpinK * hy * 0.5
            lumpPath(cw.X, cw.Y, wrx, wry, roll2, p.bodyLump * 1.6 * bm1)
            beginStops()
            eKind = TripodCmd.SOLID_POLY
            eGrad = -1
            eBucket = 0
            eFlags = 0
            eSolid = TripodFogMath.mixPacked(colShell, TripodLiterals.BLACK, 0.10, shadeScr.a)
            eAlpha = 1.0
            emit()
            op(vis(cw.X - wrx, cw.Y - wry, wrx * 2, wry * 2), TripodOpItem.COWL)
            if (lumpY0 < bTop) bTop = lumpY0
            if (lumpY1 > bBot) bBot = lumpY1
            shellCowl = roll2
        }
        // 前下腺囊 = 两道热光的出处，与 sceneState.gland **同一个 bodyFrame**
        val gl = TripodFogMath.prInto(prC, cam, p, st.gland.x, st.gland.y, st.gland.z, yaw, tmpXY)
        if (gl != null) {
            val hrx = p.headRx * PPM * gl.s
            val hry = p.headRy * PPM * gl.s
            val roll3 = st.roll + hy
            lumpPath(gl.X, gl.Y, hrx, hry, roll3, p.bodyLump * 1.2 * bm2)
            beginStops()
            eKind = TripodCmd.SOLID_POLY
            eGrad = -1
            eBucket = 0
            eFlags = 0
            eSolid = TripodFogMath.mixPacked(colShell, TripodLiterals.BLACK, 0.16, shadeScr.a)
            eAlpha = 1.0
            emit()
            op(vis(gl.X - hrx, gl.Y - hry, hrx * 2, hry * 2), TripodOpItem.GLAND)
            if (lumpY0 < bTop) bTop = lumpY0
            if (lumpY1 > bBot) bBot = lumpY1
            shellGland = roll3
        }
        // ⚠️ 呼吸的落笔记录必须在 `if (low) return` **之前**（LOW 档同样在呼吸，闸门也要能判它）
        val br = breathRecs[0]
        breathN = 1
        br.m0 = bm0; br.m1 = bm1; br.m2 = bm2
        br.top = bTop; br.bot = bBot
        br.cx = c.X; br.cy = c.Y; br.ph = st.breathPh
        if (low) return
        // 机背逆光：沿**顶缘**的一弯细弧（⛔ 浮在机身中间的扁椭圆 = 甜甜圈）
        beginStops()
        eKind = TripodCmd.ARC
        eGrad = -1
        eBucket = 0
        eFlags = 0
        eSolid = TripodFogMath.mixPacked(pal.rim, pal.fog, shadeScr.fog * 0.7, shadeScr.a * 0.55)
        eAlpha = 1.0
        eg[0] = c.X; eg[1] = c.Y; eg[2] = rx * 0.93; eg[3] = ry * 0.93
        eg[4] = st.roll; eg[5] = Math.PI * 1.08; eg[6] = TripodFogMath.TAU * 0.96
        eg[7] = max(1.0, ry * 0.17)
        emit()
        op(vis(c.X - rx, c.Y - ry, rx * 2, ry), TripodOpItem.BACK_HILITE)
        // 悬垂缆须：随重心转移一起甩 ⇒ 免费的"在走路"线索
        val sway = tendrilSway(st)
        val lw = max(0.8, 0.16 * PPM * c.s)
        val colTendril = TripodFogMath.mixPacked(colShell, TripodLiterals.BLACK, 0.25, shadeScr.a * 0.85)
        var i = 0
        while (i < p.tendN) {
            val u = if (p.tendN > 1) (i / (p.tendN - 1.0) - 0.5) * 2 else 0.0
            val tl = TripodFogMath.bodyFrame(
                st.bodyLat + u * p.bodyRx * 0.7,
                u * 2.2 + 1.2 + st.leanFwd,
                st.bodyY - 1.4, psi, st.d, st.mx,
            )
            val a0 = TripodFogMath.prInto(prD, cam, p, tl.x, tl.y, tl.z, yaw, tmpXY)
            if (a0 != null) {
                val len = p.tendLen * (0.62 + 0.38 * abs(u)) * PPM * a0.s
                beginStops()
                eKind = TripodCmd.QUAD
                eGrad = -1
                eBucket = 0
                eFlags = 0
                eSolid = colTendril
                eAlpha = 1.0
                eg[0] = lw
                eg[1] = a0.X; eg[2] = a0.Y
                eg[3] = a0.X + u * 6 + sway * PPM * a0.s * 0.5
                eg[4] = a0.Y + len * 0.55
                eg[5] = a0.X + u * 3 + sway * PPM * a0.s
                eg[6] = a0.Y + len
                emit()
                op(0.0016, TripodOpItem.TENDRIL)
            }
            i++
        }
    }

    /**
     * 原型 `drawGrab`：抓取缆须 = 悬垂缆须的**伸长**（同一支笔、同一个 sway 相位）。
     * LOW 档整段不画；`reach ≤ 0.02` 早退。
     */
    private fun grab(st: TripodScene, yaw: Double) {
        if (world.tier == TripodLevel.LOW || persons == null || st.grab.reach <= 0.02) return
        if (st.crowdN < 1) return
        val p = params
        val PPM = cam.ppm
        val v = st.crowd[0]
        val ctx = TripodFogMath.TendrilCtx(
            st.bodyLat, st.leanFwd, st.bodyY, st.psi, st.d, st.mx,
            p.bodyRx, p.personH, p.tendReach, v.x, v.y, v.z, st.grab.reach,
        )
        val sway = tendrilSway(st)
        var i = 0
        while (i < 2) {
            val tp = TripodFogMath.tendrilTip(ctx, if (i == 1) 0.62 else -0.62)
            val a0 = TripodFogMath.prInto(prA, cam, p, tp.root.x, tp.root.y, tp.root.z, yaw, tmpXY)
            val a1 = TripodFogMath.prInto(prB, cam, p, tp.x, tp.y, tp.z, yaw, tmpXY2)
            if (a0 != null && a1 != null) {
                TripodFogMath.shadeInto(shadeScr, hypot(tp.root.x, tp.root.z), st.d0, p, palette)
                val colG = TripodFogMath.mixPacked(shadeScr.col, TripodLiterals.BLACK, 0.25, shadeScr.a * 0.9)
                val lw = max(0.9, 0.2 * PPM * a0.s)
                val qx2 = (a0.X + a1.X) / 2 + sway * PPM * a0.s * 0.45
                val qy2 = (a0.Y + a1.Y) / 2 + (1 - st.grab.reach) * p.tendLen * PPM * a0.s * 0.5 + 5
                beginStops()
                eKind = TripodCmd.QUAD
                eGrad = -1
                eBucket = 0
                eFlags = 0
                eSolid = colG
                eAlpha = 1.0
                eg[0] = lw
                eg[1] = a0.X; eg[2] = a0.Y; eg[3] = qx2; eg[4] = qy2; eg[5] = a1.X; eg[6] = a1.Y
                emit()
                op(0.0016, TripodOpItem.GRAB_TENDRIL)
                if (tp.L >= tp.D - 0.02) {           // 够到了：梢端两笔小钩，⛔ 不算新器官
                    val dx = a1.X - qx2
                    val dy = a1.Y - qy2
                    val ll = hypot(dx, dy).let { if (it != 0.0) it else 1e-6 }
                    val k = max(1.8, 0.55 * PPM * a1.s)
                    val ux = dx / ll
                    val uy = dy / ll
                    beginStops()
                    // ⚠️ 原型的两笔小钩是**同一个 beginPath 里两次 moveTo** ⇒ 一次 stroke = 一个图元
                    // ⇒ 这里编成一条 SEG 指令 + `ROUND_CAP` 之外的双段标志（解释器一次画两段）。
                    eKind = TripodCmd.SEG
                    eGrad = -1
                    eBucket = 0
                    eFlags = 0
                    eSolid = colG
                    eAlpha = 1.0
                    eg[0] = a1.X; eg[1] = a1.Y
                    eg[2] = a1.X - ux * k - uy * k * 0.8
                    eg[3] = a1.Y - uy * k + ux * k * 0.8
                    eg[4] = k
                    eg[5] = a1.X - ux * k + uy * k * 0.8
                    eg[6] = a1.Y - uy * k - ux * k * 0.8
                    eg[7] = 2.0                      // 段数（梢钩的两笔小钩）
                    emit()
                    op(0.0006, TripodOpItem.GRAB_HOOK)
                }
            }
            i++
        }
    }

    // ═══════════════════════════ 图层 6：掠过段负压（R25，隔离项）═══════════════════════════

    /** 原型 `drawFootWake`：逐脚锚定（⛔ 锚在三脚均值 = R25 第一版「灰斑」的成因）。 */
    private fun footWake(st: TripodScene) {
        wakeN = 0
        if (WakeGeom.tierK == 1 && world.tier == TripodLevel.LOW) return
        if (!(params.wakeA > 0.001)) return
        val p = params
        val PPM = cam.ppm
        val pose = TripodGait.tripodPose(world, st)
        var meanX = 0.0
        var mn = 0
        var i = 0
        while (i < 3) {
            val q = TripodFogMath.prInto(prA, cam, p, pose.feet[i].x, 0.06, pose.feet[i].z, st.yaw, tmpXY)
            if (q != null) {
                // 把三脚的投影横位先存进 wakeRecs 的备用位（⛔ 第二次投影：只投一次）
                fWx[i] = q.X
                fWy[i] = q.Y
                fWs[i] = q.s
                fWok[i] = true
                meanX += q.X
                mn++
            } else {
                fWok[i] = false
            }
            i++
        }
        if (mn != 0) meanX /= mn
        val nTotal = 3 * WakeGeom.dupK
        var n = 0
        while (n < nTotal) {
            val fi = n % 3
            val ft = pose.feet[fi]
            if (!fWok[fi]) {
                n++
                continue
            }
            val fd = hypot(ft.x, ft.z)
            val wk = TripodFogMath.smoothstep(WakeGeom.d0, WakeGeom.d1, fd)   // d0 > d1 ⇒ 越近越满
            TripodFogMath.shadeInto(shadeScr, fd, st.d0, p, palette)
            val a = TripodFogMath.clamp(p.wakeA * wk, 0.0, WakeGeom.cap)
            val rx = p.legR * 4.6 * PPM * fWs[fi]
            val R = WakeGeom.thick * rx
            val ry = R * 0.42
            if (wk > 0.004 && a >= 0.01 && R >= 2) {
                val anc = fWx[fi] + WakeGeom.anchorK * ((if (mn != 0) meanX else fWx[fi]) - fWx[fi])
                val lt = TripodFogMath.mixPacked(palette.fog, TripodLiterals.HEATH, 0.3, 1.0)
                val dk = TripodFogMath.mixPacked(palette.silh, palette.fog, shadeScr.fog * 0.35, 1.0)
                val bkt = fogBucket(shadeScr.fog)
                var k = -1
                while (k <= 1) {
                    if (k != 0) {
                        val raw = anc + k * WakeGeom.side * rx
                        val cx2 = raw + WakeGeom.slipK * (cam.cx - raw)
                        beginStops()
                        eKind = TripodCmd.RAD_ELLIPSE
                        eGrad = TripodGrad.RIDGE
                        eBucket = 0
                        eFlags = 0
                        eAlpha = 1.0
                        stop(TripodFogMath.rgba(lt, a), 0.0)
                        stop(TripodFogMath.rgba(lt, 0.0), 1.0)
                        eg[0] = cx2; eg[1] = fWy[fi]; eg[2] = R; eg[3] = 0.42
                        emit()
                        val rec = wakeRecs[wakeN.coerceAtMost(23)]
                        if (wakeN < 24) wakeN++
                        rec.i = fi; rec.k = k; rec.kind = "ridge"
                        rec.X = cx2; rec.Y = fWy[fi]; rec.R = R; rec.rx = rx; rec.fx = fWx[fi]
                        rec.a = a; rec.wk = wk; rec.fd = fd
                        op(Math.PI * R * ry * 0.35 / (cam.w * cam.h), TripodOpItem.WAKE_RIDGE)
                    }
                    k += 2
                }
                val rc = R * 0.9
                val cx1 = anc + WakeGeom.slipK * (cam.cx - anc)
                beginStops()
                eKind = TripodCmd.RAD_ELLIPSE
                eGrad = TripodGrad.CHANNEL
                eBucket = bkt
                eFlags = 0
                eAlpha = 1.0
                stop(TripodFogMath.rgba(dk, a * WakeGeom.dip), 0.0)
                stop(TripodFogMath.rgba(dk, 0.0), 1.0)
                eg[0] = cx1; eg[1] = fWy[fi]; eg[2] = rc; eg[3] = 0.42
                emit()
                val rec = wakeRecs[wakeN.coerceAtMost(23)]
                if (wakeN < 24) wakeN++
                rec.i = fi; rec.k = 0; rec.kind = "channel"
                rec.X = cx1; rec.Y = fWy[fi]; rec.R = rc; rec.rx = rx; rec.fx = fWx[fi]
                rec.a = a * WakeGeom.dip; rec.wk = wk; rec.fd = fd
                op(Math.PI * rc * rc * 0.42 * 0.35 / (cam.w * cam.h), TripodOpItem.WAKE_CHANNEL)
            }
            n++
        }
    }

    private val fWx = DoubleArray(3)
    private val fWy = DoubleArray(3)
    private val fWs = DoubleArray(3)
    private val fWok = BooleanArray(3)

    // ═══════════════════════════ 图层 7：人群剪影（R18）═══════════════════════════

    /** 原型 `drawCrowd`：一人**一张烘焙位图、一次 blit**；早退口径与建筑一致。 */
    private fun crowd(st: TripodScene) {
        val ps = persons ?: return
        val p = params
        val PPM = cam.ppm
        var n = st.crowdN
        if (n > crOrder.size) n = crOrder.size
        var i = 0
        while (i < n) {
            crOrder[i] = i
            crZ[i] = st.crowd[i].z
            var j = i - 1
            while (j >= 0 && crZ[j] < st.crowd[i].z) {     // 稳定插入排序降序
                crOrder[j + 1] = crOrder[j]
                crZ[j + 1] = crZ[j]
                j--
            }
            crOrder[j + 1] = i
            crZ[j + 1] = st.crowd[i].z
            i++
        }
        i = 0
        while (i < n) {
            val q = st.crowd[crOrder[i]]
            if (q.a < 0.004 || q.z < 1.5) {
                i++
                continue
            }
            val f = TripodFogMath.prInto(prA, cam, p, q.x, q.y, q.z, st.yaw, tmpXY)
            if (f != null) {
                val kind = q.kind.coerceIn(0, TripodSlot.PERSON_N - 1)
                val bmp = ps[kind]
                val hp = p.personH * bmp.bh * PPM * f.s
                val wp = hp * 0.42
                val X = f.X - wp / 2
                val Y = f.Y - hp
                if (!(X + wp < 0 || X > cam.w || Y + hp < 0 || Y > cam.h || hp < 2)) {
                    blit(TripodSlot.PERSON0 + kind, X, Y, wp, hp, q.a, TripodOpItem.CROWD)
                }
            }
            i++
        }
    }

    // ═══════════════════════════ 图层 8：两道热光（走 ADD）═══════════════════════════

    /**
     * 原型 `drawBeams`：柱体 = 一条**加色**四边形（`lighter` ⇢ [TripodCmd.ADDITIVE]），
     * 落点 = 一张贴地的竖向压斑（所有者点名要留的那层）；发射口白球**已整层删除**。
     */
    private fun beams(st: TripodScene) {
        if (st.beamI < 0.004) return                     // 8bit 量化后为 0 ⇒ 画了也看不见
        val p = params
        val PPM = cam.ppm
        val yaw = st.yaw
        val pal = palette
        var j = 0
        while (j < 2) {
            val bm = st.beams[j]
            val E = TripodFogMath.prInto(prA, cam, p, bm.ex, bm.ey, bm.ez, yaw, tmpXY)
            val G2 = TripodFogMath.prInto(prB, cam, p, bm.hx, 0.0, bm.hz, yaw, tmpXY2)
            if (E != null && G2 != null) {
                // ⚠️ 消光一律用**到相机的真距离** hypot(x,z)，⛔ 用 z
                val colVis = TripodFogMath.clamp(
                    1 - TripodFogMath.fogOf((hypot(bm.ex, bm.ez) + hypot(bm.hx, bm.hz)) / 2, st.d0, p) * 0.85,
                    0.0, 1.0,
                ) * 0.9 + 0.1
                val poolVis = TripodFogMath.clamp(
                    1 - TripodFogMath.fogOf(hypot(bm.hx, bm.hz), st.d0, p) * 0.55, 0.0, 1.0,
                )
                val dx = G2.X - E.X
                val dy = G2.Y - E.Y
                val L = hypot(dx, dy).let { if (it != 0.0) it else 1e-6 }
                val nx = -dy / L
                val ny = dx / L
                val r0 = p.beamHalfNear * PPM * E.s
                val r1 = p.beamHalfFar * PPM * G2.s
                // ⭐ R31：柱体亮度**从发射口爬升**，峰值不再压在 stop 0
                val aPeak = TripodFogMath.clamp(0.62 * st.beamI * colVis, 0.0, 0.95)
                val aFoot = aPeak * p.beamFoot
                beginPoly()
                vx(E.X + nx * r0, E.Y + ny * r0)
                vx(G2.X + nx * r1, G2.Y + ny * r1)
                vx(G2.X - nx * r1, G2.Y - ny * r1)
                vx(E.X - nx * r0, E.Y - ny * r0)
                beginStops()
                eKind = TripodCmd.LIN_POLY
                eGrad = TripodGrad.COLUMN
                eBucket = 0
                eFlags = TripodCmd.ADDITIVE
                eAlpha = 1.0
                stop(TripodFogMath.rgba(pal.beam, aFoot), 0.0)
                stop(TripodFogMath.rgba(pal.beam, aPeak), p.beamPeakAt)
                stop(TripodFogMath.rgba(pal.beam, aPeak * (0.22 / 0.62)), 0.55)
                stop(TripodFogMath.rgba(pal.beam, 0.0), 1.0)
                eg[0] = E.X; eg[1] = E.Y; eg[2] = G2.X; eg[3] = G2.Y
                emit()
                op(L * (r0 + r1) * 1.6 / (cam.w * cam.h), TripodOpItem.BEAM_COLUMN)
                val rr = st.poolR * PPM * G2.s
                val poolOn = BmGeom.pool == 0
                if (poolOn) {
                    // ⛔ 原型的 `clamp(0.85·beamI·poolVis, 0, 0.95)` / `clamp(0.35·… , 0, 0.7)`
                    // 在发布路径上**永不生效**（beamI 上限 = 0.62×1.15 ≈ 0.713 ⇒ 0.606 / 0.250），
                    // 钳位原样保留：一旦哪天亮度上限被抬高，这里仍然是对的。
                    beginStops()
                    eKind = TripodCmd.RAD_ELLIPSE
                    eGrad = TripodGrad.POOL
                    eBucket = 0
                    eFlags = TripodCmd.ADDITIVE
                    eAlpha = 1.0
                    stop(
                        TripodFogMath.mixPacked(pal.beam, TripodLiterals.POOL_IN, 0.5,
                            TripodFogMath.clamp(0.85 * st.beamI * poolVis, 0.0, 0.95)),
                        0.0,
                    )
                    stop(
                        TripodFogMath.rgba(pal.beam, TripodFogMath.clamp(0.35 * st.beamI * poolVis, 0.0, 0.7)),
                        0.35,
                    )
                    stop(TripodFogMath.rgba(pal.beam, 0.0), 1.0)
                    eg[0] = G2.X; eg[1] = G2.Y; eg[2] = rr; eg[3] = PoolGeom.sq
                    emit()
                    op(Math.PI * rr * rr * PoolGeom.sq / (cam.w * cam.h), TripodOpItem.BEAM_POOL)
                }
                // ⚠️ 这四组量就是**本道光真正落笔用的**那些（⛔ 闸门重推式子）
                val rec = beamRecs[beamN.coerceAtMost(1)]
                if (beamN < 2) beamN++
                rec.poolOn = poolOn
                rec.rr = rr; rec.sq = PoolGeom.sq
                rec.footRatio = p.beamFoot; rec.peakAt = p.beamPeakAt
                rec.nearM = p.beamHalfNear; rec.farM = p.beamHalfFar
                rec.aPeak = aPeak; rec.beamI = st.beamI
                if (DustGeom.on != 0) {
                    val bi = if (DustGeom.fireK != 0) st.beamI else st.beamRaw
                    val dA2 = TripodFogMath.clamp(p.dustA * bi * poolVis + DustGeom.floor, 0.0, 0.85)
                    val dr = rr * DustGeom.r
                    // derK=0 的负样本：位移 × ω ⇒ 幅值与真导数同级，只有相位差 90°
                    val swv = if (DustGeom.derK != 0) st.beamSwV else st.beamSw * TripodFogMath.TAU / p.beamPeriod
                    val lean = swv * DustGeom.leanK * PPM * G2.s
                    val drec = dustRecs[dustN.coerceAtMost(1)]
                    if (dustN < 2) dustN++
                    drec.r = dr; drec.a = dA2; drec.lean = lean; drec.swv = swv
                    drec.s = G2.s; drec.bi = bi; drec.beamI = st.beamI
                    drec.pv = poolVis; drec.fire = st.fire; drec.tier = world.tier
                    if ((DustGeom.tierK == 0 || world.tier != TripodLevel.LOW) && dr >= 1 && dA2 >= 0.01) {
                        val lit = TripodFogMath.mixPacked(pal.fog, pal.beam, 0.5, 1.0)
                        beginStops()
                        eKind = TripodCmd.RAD_ELLIPSE
                        eGrad = TripodGrad.DUST
                        eBucket = 0
                        eFlags = TripodCmd.ADDITIVE
                        eAlpha = 1.0
                        stop(TripodFogMath.rgba(lit, dA2), 0.0)
                        stop(TripodFogMath.rgba(lit, dA2 * 0.42), 0.55)
                        stop(TripodFogMath.rgba(lit, 0.0), 1.0)
                        eg[0] = G2.X + lean; eg[1] = G2.Y - rr * DustGeom.rise
                        eg[2] = dr; eg[3] = DustGeom.squash
                        emit()
                        // 尘球与亮斑同属"落点那一坨" ⇒ 记进 BEAM_POOL 行（§十 表同口径）
                        op(Math.PI * dr * dr * DustGeom.squash * 0.45 / (cam.w * cam.h), TripodOpItem.BEAM_POOL)
                    }
                }
                if (BmGeom.muzzle != 0) {
                    val rm = r0 * 3.4
                    beginStops()
                    eKind = TripodCmd.RAD_ELLIPSE
                    eGrad = TripodGrad.MUZZLE
                    eBucket = 0
                    eFlags = TripodCmd.ADDITIVE
                    eAlpha = 1.0
                    stop(
                        TripodFogMath.mixPacked(pal.beam, TripodLiterals.MUZZLE, 0.55,
                            TripodFogMath.clamp(st.beamI, 0.0, 1.0)),
                        0.0,
                    )
                    stop(TripodFogMath.rgba(pal.beam, TripodFogMath.clamp(0.35 * st.beamI, 0.0, 0.6)), 0.5)
                    stop(TripodFogMath.rgba(pal.beam, 0.0), 1.0)
                    eg[0] = E.X; eg[1] = E.Y; eg[2] = rm; eg[3] = 1.0
                    emit()
                    opMuzzle(Math.PI * rm * rm * 0.5 / (cam.w * cam.h))
                }
            }
            j++
        }
    }

    // ═══════════════════════════ 图层 9：火星短划（⛔ HIGH-only）═══════════════════════════

    /**
     * 原型 `drawParticles`。⭐ R27：每粒钉在**自己出生那一瞬**的落点上（`aimState(tb)` 回溯），
     * 沿固定方位角飞出 + 抛物线落地，⛔ 跟着光柱走（那不是溅射是投掷）。
     *
     * ⚠️ 每粒每帧一次 `aimState(world, tb, null)` 是**计划层的**分配（不在 `DrawScope` 扫描范围内），
     * 原型同样是每次现算；`AimState` 里 `beams`/`gland` 是新建对象 ⇒ 48 粒 × 2 道 = 96 次/帧。
     * 逐帧重算是**语义要求**（锚点必须回到出生时刻的落点），⛔ 缓存成当帧一份来"省分配"。
     */
    private fun particles(st: TripodScene) {
        if (world.tier != TripodLevel.HIGH) return
        val p = params
        val PPM = cam.ppm
        var n = p.emberN
        if (n > EM_MAX) n = EM_MAX
        if (n < 0) n = 0
        if (n != dustCount) {
            val r = TripodFogMath.Lcg(4242 + (if (EmGeom.seedVar != 0) n else 0))
            var i = 0
            while (i < n) {
                dA[i] = r.next() * TripodFogMath.TAU
                dPh[i] = r.next()
                dPer[i] = EMBER_PER[i % EMBER_PER.size]
                dRise[i] = 2.2 + r.next() * 5.2
                dSpread[i] = 3.5 + r.next() * 7.5
                dLen[i] = 1.6 + r.next() * 2.6
                dBr[i] = 0.5 + r.next() * 0.7
                i++
            }
            dustCount = n
        }
        val shear = TripodFogMath.clamp(st.camSpeed / 14.0, -1.0, 1.0) * 0.5
        val nDraw = if (EmGeom.cap > 0) min(n, EmGeom.cap) else n
        var si = 0
        while (si < nDraw) {
            val per = dPer[si]
            val u = TripodFogMath.mod(st.t / per + dPh[si], 1.0)
            // 出生时刻 = 当前寿命进度倒推一格，再回绕进 [0, LOOP)
            val back = if (EmGeom.anchor == 1) u * per else (if (EmGeom.anchor == 2) 3.0 else 0.0)
            val tb = TripodFogMath.mod(st.t - back, p.loop)
            val B = TripodFogMath.aimState(world, tb, null)
            val born = B.beamI                      // ⛔ 当帧亮度：出生时没开火 ⇒ 这一代根本不该存在
            if (born >= 0.004) {
                val ca = cos(dA[si])
                val sa = sin(dA[si])
                val spread = dSpread[si]
                val riseH = dRise[si]
                var bi = 0
                while (bi < 2) {
                    val bm = B.beams[bi]
                    val rw = spread * (1 - (1 - u) * (1 - u))
                    val wx = bm.hx + ca * rw
                    val wy = 4 * riseH * u * (1 - u)
                    val wz = bm.hz + sa * rw
                    if (anchorN < EM_MAX) {
                        val o = anchorN * 7
                        anchor[o] = si.toDouble()
                        anchor[o + 1] = bi.toDouble()
                        anchor[o + 2] = tb
                        anchor[o + 3] = wx
                        anchor[o + 4] = wz
                        val live = st.beams[bi]
                        anchor[o + 5] = live.hx
                        anchor[o + 6] = live.hz
                        anchorN++
                    }
                    val q = TripodFogMath.prInto(prA, cam, p, wx, wy, wz, st.yaw, tmpXY)
                    if (q != null) {
                        // 划子的方向 = **投影后**的速度方向（⛔ 恒朝上）：起飞近竖直、落地近水平
                        val ut = max(0.0, u - 0.12)
                        val rwt = spread * (1 - (1 - ut) * (1 - ut))
                        val wtx = bm.hx + ca * rwt
                        val wty = 4 * riseH * ut * (1 - ut)
                        val wtz = bm.hz + sa * rwt
                        val qt = TripodFogMath.prInto(prB, cam, p, wtx, wty, wtz, st.yaw, tmpXY2)
                        var vx2 = if (qt != null) q.X - qt.X else 0.0
                        var vy2 = if (qt != null) q.Y - qt.Y else 0.0
                        var vl = hypot(vx2, vy2)
                        if (!(vl > 0.35)) {
                            vx2 = 0.0; vy2 = -1.0; vl = 1.0
                        }
                        val a = TripodFogMath.clamp(
                            sin(Math.PI * u) * 0.55 * dBr[si] *
                                (1 - TripodFogMath.fogOf(hypot(wx, wz), st.d0, p)) * born,
                            0.0, 0.6,
                        )
                        if (a >= 0.004) {
                            val len = dLen[si] * PPM * q.s * (0.45 + 0.55 * (1 - u))
                            val wdt = max(0.8, p.emberW * PPM * q.s)
                            val ex2 = -vx2 / vl * len + shear * len * 0.5
                            val ey2 = -vy2 / vl * len + shear * len * 0.5
                            beginStops()
                            eKind = TripodCmd.SEG
                            eGrad = -1
                            eBucket = 0
                            eFlags = TripodCmd.ADDITIVE or TripodCmd.ROUND_CAP
                            eSolid = TripodFogMath.rgba(palette.beam, a)
                            eAlpha = 1.0
                            eg[0] = q.X; eg[1] = q.Y; eg[2] = q.X + ex2; eg[3] = q.Y + ey2
                            eg[4] = wdt
                            eg[7] = 1.0
                            emit()
                            if (embN < EM_MAX) {
                                val o = embN * 6
                                emb[o] = q.X; emb[o + 1] = q.Y; emb[o + 2] = ex2; emb[o + 3] = ey2
                                emb[o + 4] = wdt; emb[o + 5] = a
                                embN++
                            }
                            op(len * wdt * 1.2 / (cam.w * cam.h), TripodOpItem.EMBER)
                        }
                    }
                    bi++
                }
            }
            si++
        }
    }

    // ═══════════════════════════ 图层 11：暗角 + 颗粒 ═══════════════════════════

    /** 原型 `drawGrade`：暗角与颗粒**烘在同一张位图**里 ⇒ 1 图元 / 1.000 屏（⛔ 两次全屏 pass）。 */
    private fun grade() {
        if (params.grain + 0.42 < 0.001) return
        blit(TripodSlot.GRADE, -10.0, -10.0, cam.w + 20.0, cam.h + 20.0, 1.0, TripodOpItem.GRADE)
    }

    companion object {
        /** 指令上限（最坏一帧：96 栋 × (1 blit + 2 扫光) + 96 火星 + 机身组 + 负压 ≈ 207）。 */
        const val CAP = 512

        /** 顶点池上限（3 条腿 × 76 + 三段甲壳 × 58 ≈ 402 个 double）。 */
        const val VCAP = 1024

        const val PROF_N = 25
        const val LN = 17
        const val NSEG = 8
        const val LUMP_N = 28
        const val EM_MAX = 96
        const val FOG_B = 64
        const val SKY_B = 128

        /** 每粒寿命（s）：⛔ 取不整除 `LOOP` 的值（接缝处会跳相位）。 */
        val EMBER_PER = doubleArrayOf(9.0, 10.0, 15.0, 18.0)
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  解释器（K6 第二半）：把 [TripodFogPlan] 的指令串重放到原生 [Canvas]
// ══════════════════════════════════════════════════════════════════════════

/**
 * 「烘焙产物 → 槽位」的**身份表**（§五 图层 3）。
 *
 * ⭐ 键是 [TripodFogBake.BuildingBmp] 的**对象身份**，⛔ 不是「带号 + 下标」：
 * `TripodBuilding.i` 只在**自己那条带里**唯一（中景带 0…33 与近景带 0…13 各有 `i=0`），
 * 拿 `i` 直接当槽位 = 两带的第一栋共用一张位图（近景楼长出中景楼的破口）。
 * 而 `BldSel.src` 本来就指向正确的那一栋 ⇒ 身份查表既对、又不需要带号。
 *
 * ⚠️ 线性扫描（48 次引用比较）而不是 `HashMap`：装箱 key + `get` 在逐帧路径上要分配，
 * 而 48 项扫描比一次哈希便宜且**零分配**。查不到返回 [TripodSlot.NONE] ⇒ 发射器
 * **连落笔带记账一起跳过**（⛔ 报一个画面里不存在的图元）。
 */
private class TripodSlotTable : TripodBmpTable {
    private val keys = arrayOfNulls<Any>(TripodSlot.COUNT)

    override fun slotOf(bmp: Any?): Int {
        if (bmp === null) return TripodSlot.NONE
        var i = TripodSlot.BUILDING0
        while (i < TripodSlot.COUNT) {
            if (keys[i] === bmp) return i
            i++
        }
        return TripodSlot.NONE
    }

    fun put(slot: Int, bmp: Any?) {
        if (slot in 0 until TripodSlot.COUNT) keys[slot] = bmp
    }

    fun clear() {
        var i = 0
        while (i < TripodSlot.COUNT) {
            keys[i] = null
            i++
        }
    }
}

/**
 * E46「迷雾之战 · 雾中三脚巨影」——**解释器 + 时间轴 + 音频头**（§五 / §九 / §十五 K6·K7）。
 *
 * 用法与契约见文件头的「计划器 / 解释器」split：本类**只做重放**，
 * 一帧的几何、色标、档位早退、闸门记录、`PROF` 全部由 [TripodFogPlan.build] 算完，
 * 于是 T7–T31 全部能在纯 JVM 单测里判 `plan` 侧的记录（§十四 1，⛔ 像素回读）。
 *
 * ## 三条纪律
 * 1. **逐帧零分配**（R12）：指令缓冲、位图槽、着色器缓存、`Paint`/`Path`/`Rect(F)`/`Matrix`
 *    全是字段；唯一的分配点是着色器缓存**未命中**时重建 native 对象（[gradOf]，
 *    与 E44 `MoonlitRenderer.softShader` 同口径 —— 桶设计把未命中压到"色标真的变了"才发生）。
 * 2. **档位由 [tripodLevelOf] 一次性映射**（`OFF/LITE/FULL → LOW/MEDIUM/HIGH`），
 *    写进 [TripodFogWorld.tier] 后由发射器读；⛔ 解释器侧不再判档（两处判档必分叉）。
 * 3. **时间轴只有一个自变量** `tSec`（推进口径 = 原型 `frame()`：`dt` 已被 `FrameClock`
 *    钳到 100 ms ⇒ 掉帧不会让循环跳相位），圈数 / 纪元 / 剧本全走 K3 的
 *    [TripodFogMath.syncRouteEpoch]，⛔ 在这里另算一套。
 *
 * ⚠️ 真机验收待办：加色那一档走 `PorterDuff.Mode.ADD`（§六 / D15）。HWUI 对**非离屏**
 * 目标上的 ADD 可能按 SrcOver 处理（Seaside 的既录行为）—— 若真机上光束/火星不像原型那样
 * "烧白"，那是这一条，不是几何。
 */
class TripodFogRenderer : RendererFx() {

    override val theme = VisualizerTheme.TRIPOD_FOG

    /**
     * 暗角 0.42 —— ⚠️ 这个数字**从不直接落画面**（本效果的暗角与颗粒**烘在同一张 grade
     * 位图**里，一次铺屏；基类那一路由 [vignetteOverride] 恒关），但这一行必须存在且有**三重身份**：
     *
     * ① [FxCoverageScanTest] 认定「本效果走了基类后处理」的**唯一**依据（判据 A 只认
     *    `override val postFx = PostFx(…)` 里的裸 `f` 数字字面量 ⇒ ⛔ 具名常量）；
     * ② [RendererFx.needsDamageCoalescer] 的输入：`vignette > 0` ⇒ `hasFullScreenPass()` 为真
     *    ⇒ MEDIUM/HIGH **不**再补脏区合并占位（grade 那一笔全屏 blit 天然把脏区并成一整块）；
     * ③ 静默帧的锚（覆盖值必须能复现它）。
     *
     * ⛔ 不要因为"反正逐帧被覆盖成 0"就把这里改成 `0f`：那会让 ②翻案 ⇒ MED/HIGH 各多一次
     * 全屏填充（§十一 的 4.66 屏变 5.66 屏，正撞 §十六 R8 的预算），同时 ① 当场挂。
     */
    override val postFx = PostFx(
        vignette = 0.42f,
    )

    /**
     * 恒关基类暗角：图层 11 的 grade 位图里已经含暗角（`bakeGrade` 把 `a0 + a1` 合成一层，
     * ⛔ 拆成两次全屏 pass 就多付 1.00 屏），基类再叠一次 = **两次暗角**（四角死黑）。
     *
     * ⚠️ 走"逐帧覆盖为 0f"而⛔ 不是"把 `postFx.vignette` 写 0"，理由见 [postFx] 的三重身份。
     */
    override fun vignetteOverride(fx: FxFrame): Float = 0f

    // ─────────────────────── 世界 / 计划器 / 素材 ───────────────────────

    private val world = TripodFogWorld()
    private val slotTable = TripodSlotTable()
    private val plan = TripodFogPlan(world, slotTable)

    /** 槽位即下标的原生位图（[TripodSlot]：雾条 A/B、grade、4 张人群、48 栋楼）。 */
    private val bitmaps = arrayOfNulls<Bitmap>(TripodSlot.COUNT)

    /** 已经建好分帧烘焙队列（⛔ 判"素材齐不齐"请用 `bakeQueue?.done`；⛔ 每帧判 `plan.persons`，那是发射器的"能不能画人群"） */
    private var booted = false

    /** grade 位图当前的画幅（暗角半径是**绝对像素** ⇒ 画幅一变必须整张重烘，§十一(d)） */
    private var gradeW = 0
    private var gradeH = 0

    /**
     * 首帧之后逐帧消费的**分帧烘焙队列**（null = 尚未 `boot()` 或已离效果）。
     *
     * 存在的全部理由见 [bakeStep]：进入效果的旧路径把 ~2.77 M px 压在**同一帧**同步烘完，
     * 而那一帧 Compose 还没提交过任何画面 ⇒ 冷进入黑屏、切换则把上一个效果的最后一帧钉住。
     */
    private var bakeQueue: TripodBakeQueue? = null

    /**
     * 行块暂存（一次分配、整轮复用）：容量 [TripodFogBake.BAKE_UNIT_PX_CAP] 个 int
     * ⇒ 任何**一个**行单元都装得下（⛔ 逐单元 `IntArray(...)` = 每帧分配）。
     */
    private var bakePx: IntArray? = null

    /** 上一次 `layout` 的画幅（[RendererFx] 的 `sizeCache` 是 private ⇒ 这里自备一份） */
    private var laidW = 0f
    private var laidH = 0f

    /** 循环相位（秒）。⚠️ 与 [TripodFogWorld.lapN] 分开存：`lapN` 只用于纪元，`tSec` 只用于画面。 */
    private var tSec = 0.0

    // ─────────────────────── §九 音频头（三条包络）───────────────────────

    /**
     * ⚠️ `fx.dt` **本身已是秒**（`FrameClock`：`dt = dtMs / 1000f`）⇒ 直接传给
     * [AudioSmoother.updateDt]，⛔ 不要再除 1000（E44 的偏差 D31 就是照抄了文档里的 `fx.dt/1000`）。
     * 三条各**只推进一次**，下游全部消费 `params` 里那一份（⛔ 二级 EMA）。
     */
    private val smBass = AudioSmoother()
    private val smTreb = AudioSmoother()
    private val smEner = AudioSmoother()

    // ─────────────────────── 渐变缓存（454 槽 = Σ TripodGrad.BUCKETS）───────────────────────

    /** 模板 `k` 的槽起始下标（前缀和）⇒ 槽 = `gradBase[kind] + bucket`，与发射器同一份桶口径。 */
    private val gradBase = IntArray(TripodGrad.N + 1).also { g ->
        var k = 0
        while (k < TripodGrad.N) {
            g[k + 1] = g[k] + TripodGrad.BUCKETS[k]
            k++
        }
    }

    private val shaderSlots = gradBase[TripodGrad.N]
    private val shaders = arrayOfNulls<Shader>(shaderSlots)

    /** 指纹存的是**派生后**的色标（见 [gradSlot]）：α 归一到 `amax` 后的 ARGB + 停点。 */
    private val cacheCol = IntArray(shaderSlots * 6)
    private val cachePos = FloatArray(shaderSlots * 6)
    private val cacheN = IntArray(shaderSlots)

    /**
     * 同一份色标的**精确长度副本**（`bc`/`bp`，与单位空间着色器共用，不额外分配）：
     * [LinearGradient] 的构造器只认"数组长度 == 色标数"，扁平的 `cacheCol` 切片要用
     * `Arrays.copyOf` ⇒ 每帧分配。屏幕空间渐变（[drawLinPoly]）直接拿这两个数组建笔。
     */
    private val stopCol = arrayOfNulls<IntArray>(shaderSlots)
    private val stopPos = arrayOfNulls<FloatArray>(shaderSlots)

    /** [gradSlot] 的输出旁路：本次取色要用的 paint α（`amax`，0 ⇒ 整条指令跳过） */
    private var gradAm = 0

    private val scrCol = IntArray(6)
    private val scrPos = FloatArray(6)

    // ─────────────────────── 画笔与暂存（⛔ 逐帧新建）───────────────────────

    private val blitPaint = Paint().apply { isFilterBitmap = true }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val polyPath = Path()
    private val blitSrc = Rect()
    private val blitDst = RectF()
    private val ovalRect = RectF()
    private val axisMatrix = Matrix()
    private val mVals = FloatArray(9)

    /** ⭐ 唯一一份加色混合模式对象（`Paint.setXfermode` 不拷贝，复用安全） */
    private val addMode = PorterDuffXfermode(PorterDuff.Mode.ADD)

    // ─────────────────────── 生命周期 ───────────────────────

    /**
     * 进效果：把时间轴与包络归零。⚠️ **不**在这里烘素材 —— 画幅要到首帧 [drawContent]
     * 才拿得到（[TripodFogMath.layout] 的入参就是它），而 `genBuildings` 又必须在
     * `syncRouteEpoch` 之后（`z0 = r()·GTOT` 读的是 `buildCum` 的产物）。
     */
    override fun onEnterContent(ctx: RenderContext) {
        tSec = 0.0
        world.lapN = 0
        world.routeEpoch = -1
        laidW = 0f
        laidH = 0f
        smBass.reset()
        smTreb.reset()
        smEner.reset()
    }

    /**
     * 离效果：**显式 recycle 全部位图**。API 22–25 的 `ARGB_8888` 像素在 native 堆、
     * 不受 GC 自愈（§11.3.6 / `RendererFx.onExit` 对 `ProceduralTexture` 的同一条理由），
     * 而本效果的常驻量按 §十一(d) 是 11.1 MB @1080p —— 全靠这里回收。
     *
     * ⚠️ `shaders` 不回收：native 着色器随实例走（工厂每次切换新建渲染器），
     * 而 [TripodFogPlan] 的指令缓冲只是普通数组。
     */
    override fun onExitContent() {
        var i = 0
        while (i < TripodSlot.COUNT) {
            bitmaps[i]?.recycle()
            bitmaps[i] = null
            i++
        }
        slotTable.clear()
        plan.persons = null
        booted = false
        gradeW = 0
        gradeH = 0
        // 分帧状态一并交出：队列里可能还压着没烘的单元，行块缓冲是 96 KB 的 Java 堆
        bakeQueue = null
        bakePx = null
    }

    // ─────────────────────── 烘焙与上传（分帧路径）───────────────────────

    /**
     * 首帧**只建队列**（原型 `boot()`：`layout → buildCum → setGrabKnots →
     * syncRouteEpoch(true) → genBuildings → bakeAll` 的最后一环被拆出去逐帧烘）。
     *
     * K3 的 [TripodFogMath.syncRouteEpoch] 内部已经把 `buildCum` / `setGrabKnots` /
     * `scanCross` 串好了 ⇒ 这里只剩两步真顺序，⛔ 换序：`genBuildings` 的 `z0` 读 `GTOT`
     * （`buildCum` 的产物）。位图**不**再登记在这里，改由 [bakeBuildingAt] 烘好一栋登记一栋。
     *
     * ## 为什么首帧一像素都不烘（所有者报障，2026-10-13）
     * 旧路径把 48 栋楼 + 2 张雾条 + 4 张人群 + **一整张全屏暗角颗粒**压在进入效果的那一帧
     * 同步烘完（逐阶段普查 ≈ 2.77 M px，grade 独占 2.07 M @1080p，见 [TripodBakeCostProbeTest]
     * 的口径）。桌面 JVM ≈ 290 ms 已经让「切换」把上一个效果的最后一帧钉在屏幕上，
     * 创维 API 22 慢一个量级 ⇒ 以**秒**计，而这期间 Compose 的 draw 块一帧都没提交 ⇒
     * 冷进入就是黑屏。⛔ 不要再把它"统一"回同步。
     *
     * @param w h 首帧画幅（`ctx.canvasSize` 在 `onEnter` 时还是 `Size.Zero` ⇒ 只能在这里拿）
     */
    private fun boot(w: Int, h: Int) {
        val p = world.params
        TripodFogMath.syncRouteEpoch(world, true)
        // ⭐ 只出几何，⛔ 顺手烘位图
        TripodFogBake.genBuildings(world, p, false)

        // 雾条与 grade 是**按槽位直取**的图层 ⇒ 位图先建成全透明的，行块逐帧往里长；
        // 建筑相反 —— 未烘的 `b.bmp` 恒 null ⇒ `slotOf(null) = NONE`，发射器连记账一起跳过，
        // 于是槽位天然按扁平下标预留（`BUILDING0 + index`，⛔ 依赖"跳空位顺排"的旧登记口径）。
        bitmaps[TripodSlot.FOG_A] = blankBitmap(TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H)
        bitmaps[TripodSlot.FOG_B] = blankBitmap(TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H)
        gradeW = w
        gradeH = h
        if (w > 0 && h > 0) bitmaps[TripodSlot.GRADE] = blankBitmap(w, h)

        bakeQueue = TripodBakeQueue(
            TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H,
            TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H,
            world.bMid.size, world.bNear.size, w, h,
        )
        booted = true
    }

    /** 全透明的占位位图（native 像素清零 ⇒ 未烘的行不盖任何东西）。 */
    private fun blankBitmap(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /**
     * 每帧在**固定时间预算**内推进分帧烘焙队列（[bakeQueue]），烘完即空转。
     *
     * 照 E43 沙纹理 / E44 月盘的先例：**分帧、留在渲染线程**（本仓库没有"离线线程烘位图"的
     * 先例，跨线程 `Bitmap` 在 API 22 上要自己管同步与回收，收益不抵风险）。
     * 与 E43 的**自适应步长**不同，这里用**时间预算 + 单元像素上界**（[TripodFogBake.BAKE_UNIT_PX_CAP]）：
     * 单元粒度已经是解析式的（一行 grade ≈ 1920 px），超调最多**一个单元**，
     * 比"实测单行耗时反推行数"少一套状态，也⛔ 不会再踩 E43 那个"纳秒当毫秒 ⇒ 步长钉死在下限"的坑。
     */
    private fun bakeStep() {
        val q = bakeQueue ?: return
        if (q.done) return
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        while (!q.done) {
            val u = q.next() ?: break
            runBakeUnit(u)
            // ⚠️ 单调钟是**纳秒**，BAKE_BUDGET_MS 是**毫秒** ⇒ 换算这一步⛔ 不能省（E43 根因修复的原话）
            if (android.os.SystemClock.elapsedRealtimeNanos() - t0 >= BAKE_BUDGET_MS * 1_000_000L) break
        }
    }

    /** 消费一个单元 ⇒ 把该行的像素 `setPixels` 进对应的槽位位图。 */
    private fun runBakeUnit(u: TripodBakeUnit) {
        val p = world.params
        when (u.kind) {
            TripodBakeKind.STRIP_A -> bakeStripRows(
                TripodSlot.FOG_A, TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H,
                TripodFogBake.STRIP_A_CELLS, TripodFogBake.STRIP_A_SEED, u,
            )

            TripodBakeKind.STRIP_B -> bakeStripRows(
                TripodSlot.FOG_B, TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H,
                TripodFogBake.STRIP_B_CELLS, TripodFogBake.STRIP_B_SEED, u,
            )

            TripodBakeKind.PERSONS -> {
                val ps = TripodFogBake.bakePersons()
                plan.persons = ps            // 发射器读 `bh`（⛔ 在绘制侧另存一份高度修正）
                var k = 0
                while (k < TripodSlot.PERSON_N) {
                    bitmaps[TripodSlot.PERSON0 + k] =
                        upload(TripodFogBake.PERSON_W, TripodFogBake.PERSON_H, ps[k].px)
                    k++
                }
            }

            TripodBakeKind.BUILDING -> bakeBuildingAt(u.a, p)

            else -> {
                val bmp = bitmaps[TripodSlot.GRADE] ?: return
                val rows = u.b - u.a
                if (rows <= 0 || gradeW < 1) return
                val px = bakeBuf(rows * gradeW)
                TripodFogBake.bakeGradeRows(px, gradeW, gradeH, gradeW / 2.0, p.grain, u.a, u.b)
                bmp.setPixels(px, 0, gradeW, 0, u.a, gradeW, rows)
            }
        }
    }

    private fun bakeStripRows(slot: Int, w: Int, h: Int, cells: Int, seed: Int, u: TripodBakeUnit) {
        val bmp = bitmaps[slot] ?: return
        val rows = u.b - u.a
        if (rows <= 0) return
        val px = bakeBuf(rows * w)
        TripodFogBake.bakeFogStripRows(px, w, h, cells, seed, TripodFogBake.STRIP_OCT, 1.0, u.a, u.b)
        bmp.setPixels(px, 0, w, 0, u.a, w, rows)
    }

    /** 烘一栋楼并登记 `身份 → 槽位`（队列里的扁平下标 = 中带在前、近带在后）。 */
    private fun bakeBuildingAt(index: Int, p: TripodParams) {
        val mid = world.bMid
        val b = if (index < mid.size) {
            mid[index]
        } else {
            val j = index - mid.size
            if (j >= world.bNear.size) return
            world.bNear[j]
        }
        val slot = TripodSlot.BUILDING0 + index
        if (slot >= TripodSlot.COUNT) return
        val bb = TripodFogBake.bakeBuilding(b, p)
        b.bmp = bb
        bitmaps[slot]?.recycle()
        bitmaps[slot] = upload(bb.w, bb.h, bb.px)
        slotTable.put(slot, bb)
    }

    /** 行块暂存（一次分配、整轮复用；容量由 [TripodBakeQueue] 的像素上界给定，⛔ 逐单元新建）。 */
    private fun bakeBuf(need: Int): IntArray {
        val cur = bakePx
        if (cur != null && cur.size >= need) return cur
        val next = IntArray(max(need, TripodFogBake.BAKE_UNIT_PX_CAP))
        bakePx = next
        return next
    }

    /**
     * 烘焙产物（**非预乘** ARGB 的 `IntArray`）→ 原生位图。
     *
     * `setPixels` 吃的就是非预乘值（与 `getPixels` 同布局）⇒ K5 的打包口径直接对上。
     * ⚠️ 先 `recycle` 旧的再分配新的像素数组：重烘一帧会瞬时占两份（§十一(d) 的余量只有 7.4%）。
     */
    private fun upload(w: Int, h: Int, px: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * 画幅变了 ⇒ 暗角/颗粒位图整张作废**重排队**（半径是**绝对像素**，⛔ 不能拉伸复用）。
     *
     * ⚠️ 只重排 grade：`requeueGrade` 把**尚未消费**的场景单元原样带过去。⛔ 不要写成
     * `TripodBakeQueue.gradeOnly(...)` —— 那会把还没烘的楼与雾从队列里抹掉，它们的 `bmp`
     * 就永远是 null ⇒ 画面缺一块且再也长不回来。
     */
    private fun prepareGrade(w: Int, h: Int) {
        if (w < 1 || h < 1) return
        if (gradeW == w && gradeH == h && bitmaps[TripodSlot.GRADE] != null) return
        bitmaps[TripodSlot.GRADE]?.recycle()
        gradeW = w
        gradeH = h
        bitmaps[TripodSlot.GRADE] = blankBitmap(w, h)
        val q = bakeQueue
        bakeQueue = if (q == null) TripodBakeQueue.gradeOnly(w, h) else q.requeueGrade(w, h)
    }

    // ─────────────────────── 帧头：尺寸 / 档位 / 时间轴 / 音频 ───────────────────────

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        world.tier = tripodLevelOf(fx.level)
        val p = world.params

        // ① 画幅：只重算投影常数与重排 grade。⛔ 绝不重跑 syncRouteEpoch / genBuildings
        //    —— 换画幅既不是换圈也不是换剧本，重跑会把观众眼前的巨影换成另一条路线。
        if (w != laidW || h != laidH) {
            laidW = w
            laidH = h
            TripodFogMath.layout(world, w.toDouble(), h.toDouble())
            gradeW = 0
        }
        if (!booted) boot(w.toInt(), h.toInt())
        prepareGrade(w.toInt(), h.toInt())
        // ⭐ 分帧烘焙：首帧之后每帧最多推进 BAKE_BUDGET_MS，烘完即一次「读两个字段比较」的空转
        bakeStep()

        // ② §九 音频头（三条包络 ⇒ `params.audio*`；静默兜底在 `SpectrumRepository`，
        //    所以生产路径 `audioBreathe` **恒 false** —— 那是原型的脱机演示档）
        p.audioBass = smBass.updateDt(frame.bass, fx.dt).toDouble()
        p.audioTreble = smTreb.updateDt(frame.treble, fx.dt).toDouble()
        p.audioEnergy = smEner.updateDt(frame.energy, fx.dt).toDouble()
        p.audioBreathe = false

        // ③ 时间轴（原型 `frame()`）：⚠️ **一帧最多进一圈**，且纪元同步必须在
        //    `sceneState` **之前**（sceneState 读的是当帧剧本的 DH/YH/CH/ESC）。
        val nt = tSec + fx.dt
        if (nt >= p.loop) world.lapN++
        tSec = TripodFogMath.mod(nt, p.loop)
        TripodFogMath.syncRouteEpoch(world, false)

        // ④ 发射 + 重放
        val st = TripodGait.sceneState(world, tSec)
        val n = plan.build(st)
        val c = drawContext.canvas.nativeCanvas
        // ⭐ 唯一一处平移：`TripodScene.shakeX/Y` 已经把恒幅微抖与落地回弹**合成完**了
        //    （R26，⛔ 第二个 translate 通道 —— 那两个通道的相位在接缝处不会互相补偿）
        c.save()
        c.translate(st.shakeX.toFloat(), st.shakeY.toFloat())
        var i = 0
        while (i < n) {
            drawCommand(c, i)
            i++
        }
        c.restore()
    }

    // ─────────────────────── 指令重放 ───────────────────────

    /** 图层序由 [TripodFogPlan.build] 决定（§五 十一步）；这里只按发射顺序重放，⛔ 重排。 */
    private fun DrawScope.drawCommand(c: Canvas, i: Int) {
        when (plan.cmdKind[i]) {
            TripodCmd.BLIT -> drawBlit(c, i)
            TripodCmd.LIN_RECT -> drawLinRect(c, i)
            TripodCmd.LIN_POLY -> drawLinPoly(c, i)
            TripodCmd.SOLID_POLY -> drawSolidPoly(c, i)
            TripodCmd.STROKE_POLY -> drawStrokePoly(c, i)
            TripodCmd.QUAD -> drawQuad(c, i)
            TripodCmd.RAD_ELLIPSE -> drawRadEllipse(c, i)
            TripodCmd.ARC -> drawArc(c, i)
            TripodCmd.SEG -> drawSeg(c, i)
        }
    }

    /** 位图缩放绘制（雾条 / 建筑 / 人群 / grade）。α 用发射器量化好的 [TripodFogPlan.cmdAlpha]。 */
    private fun DrawScope.drawBlit(c: Canvas, i: Int) {
        val slot = plan.cmdSlot[i]
        if (slot < 0 || slot >= TripodSlot.COUNT) return
        val bmp = bitmaps[slot] ?: return
        val g = plan.cmdG
        val b = i * 8
        val x = g[b].toFloat()
        val y = g[b + 1].toFloat()
        val ww = g[b + 2].toFloat()
        val hh = g[b + 3].toFloat()
        if (!(ww > 0f) || !(hh > 0f)) return
        blitSrc.set(0, 0, bmp.width, bmp.height)
        blitDst.set(x, y, x + ww, y + hh)
        blitPaint.alpha = plan.cmdAlpha[i]
        c.drawBitmap(bmp, blitSrc, blitDst, blitPaint)
    }

    /**
     * 线性渐变铺一个矩形：`g0..g3` 轴、`g4..g7` 矩形。
     *
     * ⭐ 着色器**恒建在单位轴** `(0,0)→(1,0)` 上（[gradOf]），逐帧挪动改由 **`Canvas` 变换**承载
     * —— 画的是"矩形在轴空间里的像"（四角经逆仿射后的路径）。
     * ⛔ 不用 `Shader.setLocalMatrix`：那是**改共享对象**。同一桶的两条指令（三道腿的圆柱渐变、
     * 两道光柱、逐栋扫光）会拿到**同一个**着色器实例，而硬件画布把绘制项与 paint 一起录进
     * display list、重放时才读 local matrix ⇒ 后设的那一次会**覆盖**先录的那几笔。
     * `Canvas.concat` 的矩阵则在录制当刻就折进绘制项，天然逐笔独立（E44 `drawSoftEllipse`
     * 的 `translate/scale` 就是同一条已验路径）。
     *
     * ⚠️ 该结论**只对 4 点凸矩形成立**：多顶点的凹/自交路径走同一个 `concat` 机制在 API 22 创维
     *    上**一个像素都不落**（真机实测），所以 [drawLinPoly] 必须留在屏幕空间。别把两者"统一"回去。
     */
    private fun DrawScope.drawLinRect(c: Canvas, i: Int) {
        val s = gradOf(i) ?: return
        val g = plan.cmdG
        val b = i * 8
        val ax0 = g[b].toFloat()
        val ay0 = g[b + 1].toFloat()
        val dx = (g[b + 2] - g[b]).toFloat()
        val dy = (g[b + 3] - g[b + 1]).toFloat()
        val len2 = dx * dx + dy * dy
        if (!(len2 > 1e-6f)) return        // 退化轴：渐变无方向 ⇒ 画了也是纯色一块，跳过（不影响 PROF）
        val rx = g[b + 4]
        val ry = g[b + 5]
        val rw = g[b + 6]
        val rh = g[b + 7]
        polyPath.reset()
        mapIntoPath(polyPath, rx, ry, ax0, ay0, dx, dy, len2)
        mapIntoPath(polyPath, rx + rw, ry, ax0, ay0, dx, dy, len2)
        mapIntoPath(polyPath, rx + rw, ry + rh, ax0, ay0, dx, dy, len2)
        mapIntoPath(polyPath, rx, ry + rh, ax0, ay0, dx, dy, len2)
        polyPath.close()
        paintFor(fillPaint, i).shader = s
        fillPaint.alpha = gradAm
        c.save()
        c.concat(axisMatrixOf(ax0, ay0, dx, dy))
        c.drawPath(polyPath, fillPaint)
        c.restore()
    }

    /**
     * 线性渐变铺一个多边形（腿的圆柱面、机身甲壳、光柱四边形）。
     *
     * ⭐ **路径与渐变都在屏幕空间**：路径直接取顶点池里的屏幕点，渐变轴就是指令自带的
     *    `g0..g3`（发射器存的那两个点本来就是屏幕坐标）。轴空间/单位空间的写法见 [drawLinRect]。
     *
     * ⛔ **不得改回"轴空间路径 + `Canvas.concat`"**（所有者报障的根因，2026-10-13 真机实测）：
     * 同一条 34 点腿多边形，屏幕空间每帧落笔 1000–9800 px，经 `concat(轴矩阵)` 摆到轴空间后
     * **80 帧全部 0 像素**；纯色、无 shader 也一样不落笔 ⇒ 与渐变无关，是"多顶点凹/自交路径 +
     * 画布变换"这一交叉在 API 22 hwui 上不出图。对照：`drawLinRect` 的 4 点凸矩形走同一个
     * `concat` 机制在电视上**能**画，所以只有本函数需要绕开。护栏 `TripodFillMechanismGateTest`。
     *
     * ⚠️ 代价：色标缓存只能复用到数组，`LinearGradient` 必须按指令的轴逐条新建（每帧腿 3 +
     * 机身 1 + 光柱 ≤2 ≈ 6 个 native 对象）。
     */
    private fun DrawScope.drawLinPoly(c: Canvas, i: Int) {
        val ci = gradSlot(i)
        if (ci < 0) return
        val bc = stopCol[ci] ?: return
        val bp = stopPos[ci] ?: return
        val g = plan.cmdG
        val b = i * 8
        val ax0 = g[b].toFloat()
        val ay0 = g[b + 1].toFloat()
        val ax1 = g[b + 2].toFloat()
        val ay1 = g[b + 3].toFloat()
        val dx = ax1 - ax0
        val dy = ay1 - ay0
        if (!(dx * dx + dy * dy > 1e-6f)) return   // 退化轴：渐变无方向 ⇒ 画了也是纯色一块，跳过
        val cnt = plan.cmdVCnt[i]
        if (cnt < 3) return
        buildScreenPath(cnt, plan.cmdV0[i])
        val pt = paintFor(fillPaint, i)
        pt.shader = LinearGradient(ax0, ay0, ax1, ay1, bc, bp, Shader.TileMode.CLAMP)
        pt.alpha = gradAm
        c.drawPath(polyPath, pt)
    }

    /** 纯色闭合多边形（顶罩、腺囊）。 */
    private fun DrawScope.drawSolidPoly(c: Canvas, i: Int) {
        val cnt = plan.cmdVCnt[i]
        if (cnt < 3) return
        buildScreenPath(cnt, plan.cmdV0[i])
        val pt = paintFor(fillPaint, i)
        pt.shader = null
        pt.color = plan.cmdSolid[i]
        c.drawPath(polyPath, pt)
    }

    /** 纯色描闭合多边形（腿背光轮廓光）。 */
    private fun DrawScope.drawStrokePoly(c: Canvas, i: Int) {
        val cnt = plan.cmdVCnt[i]
        if (cnt < 3) return
        buildScreenPath(cnt, plan.cmdV0[i])
        val g = plan.cmdG
        val pt = paintFor(strokePaint, i)
        pt.shader = null
        pt.color = plan.cmdSolid[i]
        pt.strokeWidth = g[i * 8].toFloat()
        c.drawPath(polyPath, pt)
    }

    /** 纯色描一条二次曲线（悬垂缆须 / 抓取缆须）。 */
    private fun DrawScope.drawQuad(c: Canvas, i: Int) {
        val g = plan.cmdG
        val b = i * 8
        polyPath.reset()
        polyPath.moveTo(g[b + 1].toFloat(), g[b + 2].toFloat())
        polyPath.quadTo(g[b + 3].toFloat(), g[b + 4].toFloat(), g[b + 5].toFloat(), g[b + 6].toFloat())
        val pt = paintFor(strokePaint, i)
        pt.shader = null
        pt.color = plan.cmdSolid[i]
        pt.strokeWidth = g[b].toFloat()
        c.drawPath(polyPath, pt)
    }

    /** 径向渐变铺一个竖向压扁的椭圆（落地冲击环、接触影、负压脊/槽、落点亮斑、火星团）。 */
    private fun DrawScope.drawRadEllipse(c: Canvas, i: Int) {
        val s = gradOf(i) ?: return
        val g = plan.cmdG
        val b = i * 8
        val r = g[b + 2].toFloat()
        val sq = g[b + 3].toFloat()
        if (!(r > 0f) || !(sq > 0f)) return
        val pt = paintFor(fillPaint, i)
        pt.shader = s
        pt.alpha = gradAm
        c.save()
        c.translate(g[b].toFloat(), g[b + 1].toFloat())
        c.scale(r, r * sq)
        // ⭐ 单位圆 + canvas 缩放 = 一张 shader service 任意尺寸的斑（E44 `drawSoftEllipse` 同构）
        c.drawCircle(0f, 0f, 1f, pt)
        c.restore()
    }

    /** 纯色描一段椭圆弧（机背逆光）。⚠️ `g6` 是**终止角**，扫角 = `g6 − g5`。 */
    private fun DrawScope.drawArc(c: Canvas, i: Int) {
        val g = plan.cmdG
        val b = i * 8
        val rx = g[b + 2].toFloat()
        val ry = g[b + 3].toFloat()
        if (!(rx > 0f) || !(ry > 0f)) return
        val pt = paintFor(strokePaint, i)
        pt.shader = null
        pt.color = plan.cmdSolid[i]
        pt.strokeWidth = g[b + 7].toFloat()
        ovalRect.set(-rx, -ry, rx, ry)
        c.save()
        c.translate(g[b].toFloat(), g[b + 1].toFloat())
        c.rotate(Math.toDegrees(g[b + 4]).toFloat())
        c.drawArc(ovalRect, Math.toDegrees(g[b + 5]).toFloat(),
            Math.toDegrees(g[b + 6] - g[b + 5]).toFloat(), false, pt)
        c.restore()
    }

    /**
     * 纯色描 1–2 段直段（火星短划 / 抓取梢端两笔小钩）。
     *
     * ⚠️ 段数 `g7` 的存在理由：原型的两笔小钩是**同一个 `beginPath` 里两次 `moveTo`**
     * ⇒ 一次 `stroke` = 一个图元。编成两条指令会把 `PROF` 的 GRAB_HOOK 行翻倍。
     */
    private fun DrawScope.drawSeg(c: Canvas, i: Int) {
        val g = plan.cmdG
        val b = i * 8
        polyPath.reset()
        polyPath.moveTo(g[b].toFloat(), g[b + 1].toFloat())
        polyPath.lineTo(g[b + 2].toFloat(), g[b + 3].toFloat())
        if (g[b + 7] > 1.5) {
            polyPath.moveTo(g[b].toFloat(), g[b + 1].toFloat())
            polyPath.lineTo(g[b + 5].toFloat(), g[b + 6].toFloat())
        }
        val pt = paintFor(strokePaint, i)
        pt.shader = null
        pt.color = plan.cmdSolid[i]
        pt.strokeWidth = g[b + 4].toFloat()
        c.drawPath(polyPath, pt)
    }

    // ─────────────────────── 重放侧的公共小工具 ───────────────────────

    /** 按指令的 `flags` 配好混合模式与线帽，返回同一支画笔（⛔ 新建：`Paint` 是 native 对象）。 */
    private fun paintFor(pt: Paint, i: Int): Paint {
        val fl = plan.cmdFlags[i]
        pt.xfermode = if ((fl and TripodCmd.ADDITIVE) != 0) addMode else null
        pt.strokeCap = if ((fl and TripodCmd.ROUND_CAP) != 0) Paint.Cap.ROUND else Paint.Cap.BUTT
        return pt
    }

    /** 屏幕点 → 轴空间点（单位轴的逆仿射），追加到路径上。 */
    private fun mapIntoPath(
        path: Path,
        px: Double,
        py: Double,
        ax0: Float,
        ay0: Float,
        dx: Float,
        dy: Float,
        len2: Float,
    ) {
        val u = px.toFloat() - ax0
        val v = py.toFloat() - ay0
        val t = (dx * u + dy * v) / len2
        val s = (dx * v - dy * u) / len2
        if (path.isEmpty) path.moveTo(t, s) else path.lineTo(t, s)
    }

    /** 顶点池里的屏幕点 → 路径（⛔ 不做轴变换的纯色类指令用）。 */
    private fun buildScreenPath(cnt: Int, v0: Int) {
        val vs = plan.verts
        polyPath.reset()
        polyPath.moveTo(vs[v0].toFloat(), vs[v0 + 1].toFloat())
        var k = 1
        while (k < cnt) {
            polyPath.lineTo(vs[v0 + k * 2].toFloat(), vs[v0 + k * 2 + 1].toFloat())
            k++
        }
        polyPath.close()
    }

    /** `(t,s) → 屏幕` 的正仿射：单位段 `(1,0)` 映到轴向量、`(0,1)` 映到其左手法向。 */
    private fun axisMatrixOf(ax0: Float, ay0: Float, dx: Float, dy: Float): Matrix {
        mVals[0] = dx; mVals[1] = -dy; mVals[2] = ax0
        mVals[3] = dy; mVals[4] = dx; mVals[5] = ay0
        mVals[6] = 0f; mVals[7] = 0f; mVals[8] = 1f
        axisMatrix.setValues(mVals)
        return axisMatrix
    }

    /**
     * 取指令 [i] 的着色器（命中缓存则复用**同一个实例**），并把本次要用的 paint α 写进 [gradAm]。
     * 返回 `null` = 本条指令**不落笔**（纯色指令 / 全透明 / 色标不足）。
     *
     * ⚠️ 只对**凸路径**（`LIN_RECT` / `RAD_ELLIPSE`）安全：着色器建在单位空间，靠 `Canvas` 变换
     *    摆位。多顶点的凹/自交路径不能这么画，见 [drawLinPoly]。
     */
    private fun gradOf(i: Int): Shader? {
        val ci = gradSlot(i)
        return if (ci < 0) null else shaders[ci]
    }

    /**
     * 派生指令 [i] 的色标并刷新缓存，返回槽下标；`< 0` = 本条指令**不落笔**。
     * 副产物：[gradAm]（paint α）与 [stopCol]/[stopPos]（精确长度副本）。
     *
     * ⭐ 指纹用**派生后的色标**（RGB + 归一化到 `amax` 的 α + 停点），⛔ 用原始 `cmdCol`：
     * α 是逐帧连续漂移的量，而发射器已经把 RGB 与雾系数**量化进桶**（`fogBucket`/`skyBucket`）——
     * 拿原始 α 当键等于每帧必失，那 454 个槽就是摆设；归一化后"同一支笔不同亮度"共用一个实例，
     * 亮度只走 paint α（Skia 用 paint 的 α 调制着色器输出 ⇒ [TripodFogPlan.cmdAlpha] 对渐变类
     * 恒 255，真正的强度在色标里，除以 `amax` 后剩下的就是形状）。
     *
     * ⚠️ 缓存**未命中**时才分配（每桶从"没变过"到"变了"才建一次）：内部 `IntArray`/`FloatArray`
     * 各一个小数组 + 一个 native 着色器对象 —— 与 E44 `softShader` 同口径。
     */
    private fun gradSlot(i: Int): Int {
        val kind = plan.cmdGrad[i]
        if (kind < 0) return -1
        val n = plan.cmdNstop[i]
        if (n < 2) return -1
        val cb = i * 6
        val cols = plan.cmdCol
        var amax = 0
        var k = 0
        while (k < n) {
            val a = (cols[cb + k] ushr 24) and 0xFF
            if (a > amax) amax = a
            k++
        }
        if (amax == 0) return -1                 // 全透明 ⇒ 画了也不落一个像素
        k = 0
        while (k < n) {
            val col = cols[cb + k]
            val q = (col ushr 24) and 0xFF
            // 255·q/amax 的四舍五入（q == amax ⇒ 恰为 255）
            scrCol[k] = (col and 0xFFFFFF) or (((q * 510 + amax) / (2 * amax)) shl 24)
            scrPos[k] = plan.cmdPos[cb + k].toFloat()
            k++
        }
        gradAm = amax
        val ci = gradBase[kind] + plan.cmdBucket[i]
        val off = ci * 6
        var s = shaders[ci]
        if (s != null && cacheN[ci] == n) {
            k = 0
            while (k < n) {
                if (cacheCol[off + k] != scrCol[k] || cachePos[off + k] != scrPos[k]) {
                    s = null
                    break
                }
                k++
            }
        } else {
            s = null
        }
        if (s == null) {
            val bc = IntArray(n)
            val bp = FloatArray(n)
            System.arraycopy(scrCol, 0, bc, 0, n)
            System.arraycopy(scrPos, 0, bp, 0, n)
            s = if (TripodGrad.RADIAL[kind]) {
                RadialGradient(0f, 0f, 1f, bc, bp, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, 0f, 1f, 0f, bc, bp, Shader.TileMode.CLAMP)
            }
            shaders[ci] = s
            stopCol[ci] = bc
            stopPos[ci] = bp
            cacheN[ci] = n
            System.arraycopy(scrCol, 0, cacheCol, off, n)
            System.arraycopy(scrPos, 0, cachePos, off, n)
        }
        return ci
    }

    companion object {
        /**
         * `FxLevel → [TripodLevel]` 的**一次性映射**（`TripodOpBudget.kt` 规定的三行）。
         *
         * ⛔ 不要写进 `TripodOpBudget` / `TripodFogMath`：那些文件的前提是**零 Android import**
         * （纯 JVM 单测直接 `new` 出来跑全圈），一旦 import `FxLevel` 就破了 §十四 1 的可测性。
         * ⚠️ `OFF → LOW`（不是"关掉画面"）：浓雾与巨影是本效果的主体，LOW 档砍的是
         * 雾 B / grade / 建筑扫光 / 缆须 / 接触影 / 负压 / 火星这些**装饰层**（§五 早退表）。
         */
        internal fun tripodLevelOf(level: FxLevel): TripodLevel = when (level) {
            FxLevel.OFF -> TripodLevel.LOW
            FxLevel.LITE -> TripodLevel.MEDIUM
            FxLevel.FULL -> TripodLevel.HIGH
        }

        /**
         * 分帧烘焙的**每帧时间预算**（毫秒）。
         *
         * 取 24 ms 的依据：真机（创维 E46）跑起来的帧时实测 42~53 ms（§10 的 fps 采样，
         * HIGH 档 19~24 fps）⇒ 预算压在**半帧**，让烘焙叠加之后仍然不会把帧时推到 100 ms 的
         * `FrameClock` 钳位（钳位 = 掉相位，观感是巨影"跳步"）。
         * ⚠️ 与 E43 的 `SAND_BAKE_BUDGET_MS = 34` 不同档：沙纹理是**一块整色**在顶上，
         * 而这里的占位是**完全透明**，画面本来就逐帧在长东西，稳一点没代价。
         */
        const val BAKE_BUDGET_MS = 24L
    }
}
