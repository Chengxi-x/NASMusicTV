package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E46「迷雾之战 · 雾中三脚巨影」—— **K3 数学 / 世界状态层**
 * （`docs/archive/e46-tripod-fog-plan.md` §十五 K3；原型 `docs/archive/e46-tripod-fog-preview.html`）。
 *
 * ## 本文件放什么（§十五 K3 的逐项落点）
 * 投影 / yaw 正反变换 / Fritsch–Carlson 单调三次（`hermite/hEval/hDeriv`）/ `pw` /
 * `fogOf/shade/contrastAt` / 两连杆 IK（**显式膝点目标**，R11/D32）/ `bodyFrame` /
 * `routeMX/routePSI`（**种子导出 + 接缝坡道**，R16/D36）/ 「带 + 两端窗口」的**公共结构**
 * （`bandWindow` 与 `crowdWindow` 共用一份，⛔ 两套回收数学）/ `tendrilTip`
 * （含 `L = min(D, tendReach·reach)` 的够得着钳位）/ `scanCross`（变号求根，零穿越退回全局最近位）/
 * `variantOf` + `applyVariant` 的**六步重建**（R29/D48）/ `aimState` 光链（R27）/
 * `impProgress + footImp`（R22/D40）/ `joltOf`（R26/D44）/ `crowdEpochOf`（R21/D41）。
 *
 * ## ⭐ 为什么有一个 `TripodFogWorld` 类（⛔ 不是"顺手重构"）
 * 原型是**单个 IIFE**：`DH/YH/TCROSS/GW/RA/RP/CUM_T/GTOT/CHG/ESCG/GAIM/lapN/CURVAR`
 * 全是模块级可变状态，route / aimState / applyVariant 全是闭包。Kotlin 侧按 §十五 拆成
 * K3（数学）/ K4（步态）/ K5（烘焙）/ K6（绘制）四个文件后，这些状态**必须有个唯一属主**，
 * 否则就是本项目反复踩的「两套口径」。属主放在 K3：route / aimState / applyVariant 按计划
 * 都在本文件，而它们要读的状态（`gAt`/`DH`/`YH`/`ESCG`/`CHG`）由 **K4 的 `buildCum` 填**。
 * ⇒ 字段名与原型变量**逐字对应**（`gtot` ↔ `GTOT`、`cumT` ↔ `CUM_T`…），
 * 移植保真度可以逐字段对读，⛔ 不要在 Kotlin 侧"顺手"改名或合并字段。
 *
 * ## ⚠️ 与 K4 的前向引用（编译顺序）
 * [applyVariant] 的六步重建第 ⑤ 步是 `TripodGait.buildCum`（§十五 K4，`TripodGait.kt`）——
 * 同包内文件间无顺序约束，但 **K3 单独不可编译**，必须与 K4 一起落地（本会话连续实现）。
 *
 * ## 硬约束（承 E43/E44 同类条款）
 * 1. ⛔ **零 Android import、零 Compose import** —— 全部签名走 `Double` / `Int` / 打包 ARGB `Int`，
 *    保证 `TripodFogMathTest` 是纯 JVM 单测（`TripodOpBudget` 同一条理由）。
 * 2. ⛔ **零 `Random`**：唯一随机源是 [Lcg]（与原型 `lcg` 逐位同构的 32 位 LCG）。
 * 3. 数学一律 **Double**（原型是 JS double；⛔ 中途降 Float —— 接缝判据的量级是 1e-13）。
 */
// ══════════════════════════════════════════════════════════════════════════════
//  数据类（⛔ 不带行为、不带 Android 类型）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 单调三次 Hermite 表（Fritsch–Carlson，§八）。
 *
 * ⭐ R29：**原地重建**语义 —— [set] 只改 `ky`/`m`（`kx` 是剧本间共用的网格），
 * ⛔ 不换实例：`window.__E46`、`crowdTable` 的 `DH.ky[0]` 与闸门拿的是**同一份引用**，
 * 换成新实例就等于让画面读旧表、闸门读新表（两套口径）。
 */
internal class Hermite {
    /** 关键帧横坐标（网格；剧本之间共用同一份数组引用）。 */
    var kx: DoubleArray = DoubleArray(0)

    /** 关键帧取值（[set] 时重建）。 */
    var ky: DoubleArray = DoubleArray(0)

    /** 逐点切线（Fritsch–Carlson 半径钳制后的结果）。 */
    var m: DoubleArray = DoubleArray(0)

    /** 区间数 = `kx.size - 1`。 */
    var n: Int = 0

    /**
     * 原地重建（对应原型 `setHermite` / `setGrabKnots` 的写回段）。
     *
     * ⚠️ `kx` 一并赋值：`setGrabKnots` 的网格随 `grabT` 变（`grabKx` 的节点是相对的），
     * 而 `setYawAmp` 传的是共用网格（同一引用，赋回去无副作用）。
     */
    fun set(kx: DoubleArray, ky: DoubleArray) {
        this.kx = kx
        this.ky = ky.copyOf()
        val m = DoubleArray(kx.size)
        val dl = DoubleArray(kx.size - 1)
        var i = 0
        while (i < n_()) {
            dl[i] = (ky[i + 1] - ky[i]) / (kx[i + 1] - kx[i])
            i++
        }
        val n = dl.size
        m[0] = dl[0]
        m[n] = dl[n - 1]
        i = 1
        while (i < n) {
            m[i] = if (dl[i - 1] * dl[i] <= 0.0) 0.0 else (dl[i - 1] + dl[i]) / 2.0
            i++
        }
        i = 0
        while (i < n) {
            if (dl[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
            } else {
                val a = m[i] / dl[i]
                val b = m[i + 1] / dl[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val t = 3.0 / sqrt(s)
                    m[i] = t * a * dl[i]
                    m[i + 1] = t * b * dl[i]
                }
            }
            i++
        }
        this.m = m
        this.n = n
    }

    private fun n_(): Int = kx.size - 1

    /** 区间下标（原型 `hIdx`）：`while (i < n-1 && x > kx[i+1]) i++`。 */
    private fun idx(x: Double): Int {
        var i = 0
        while (i < n - 1 && x > kx[i + 1]) i++
        return i
    }

    /** 求值（原型 `hEval`；两端之外取端点值 ⇒ 钳位不外推）。 */
    fun eval(x: Double): Double {
        if (x <= kx[0]) return ky[0]
        if (x >= kx[n]) return ky[n]
        val i = idx(x)
        val h = kx[i + 1] - kx[i]
        val t = (x - kx[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ky[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * ky[i + 1] + (t3 - t2) * h * m[i + 1]
    }

    /**
     * 导数（原型 `hDeriv`）。
     * ⚠️ 两端之外取**首/末割线**（不是 0）：`camSpeed = −v_walk − hDeriv(DH)` 与
     * 接缝判据都读它，V1/V2 的"末拍爬升"就是这样在 `shakeY` 上留下 0.57/0.82 px 跳变的（R30/T4）。
     */
    fun deriv(x: Double): Double {
        if (x <= kx[0]) return (ky[1] - ky[0]) / (kx[1] - kx[0])
        if (x >= kx[n]) return (ky[n] - ky[n - 1]) / (kx[n] - kx[n - 1])
        val i = idx(x)
        val h = kx[i + 1] - kx[i]
        val t = (x - kx[i]) / h
        val t2 = t * t
        return (6 * t2 - 6 * t) * ky[i] / h + (3 * t2 - 4 * t + 1) * m[i] +
            (-6 * t2 + 6 * t) * ky[i + 1] / h + (3 * t2 - 2 * t) * m[i + 1]
    }
}

/** [TripodFogMath.shade] 的输出：雾色混合结果（`col` 为打包 ARGB Int）。 */
internal class Shade(var col: Int, var a: Double, var fog: Double)

/** 机身组局部坐标 → 相机系（原型 `bodyFrame` 的返回值）。 */
internal class BodyPt(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0)

/**
 * 投影点（原型 `pr` 的返回值）。
 * ⚠️ `along < 0.5`（相机背后）时原型返回 `null` 由调用方剔除 ⇒ Kotlin 侧 [TripodFogMath.pr] 返回可空。
 */
internal class ProjPt(
    var X: Double = 0.0,
    var Y: Double = 0.0,
    var s: Double = 0.0,
    var along: Double = 0.0,
)

/**
 * `TCROSS`：`d` 的过零时刻（原型 `scanCross` 的产出，**原地改字段**不换对象）。
 *
 * ⭐ R29：**零穿越的剧本（V1/V2）合法** —— `t1` 退回"全局最近位"（在新剧本里那正是
 * "面对面"或"横穿最近端"的那一刻），`t2 = t1` 让所有 `[t1−k, t2+k]` 形态的窗口自动退化成
 * "贴身邻域"，⛔ 各写一套分支。
 */
internal class CrossPt {
    var t1: Double = 0.0
    var t2: Double = 0.0
    var n: Int = 0
    var cross: Boolean = true
}

/** 相机布局（原型 `layout` 的落点：`W/H/cx/hz/PPM` 五件套）。 */
internal class CamLayout {
    var w: Double = 960.0
    var h: Double = 540.0
    var cx: Double = 480.0
    var hz: Double = 313.0
    var ppm: Double = 22.0
}

/** 三频段音频包络（原型 `audioEnv` 的返回值；K7 的 `AudioSmoother` 喂真值）。 */
internal class TripodAudioEnv(val bass: Double, val treble: Double, val energy: Double)

/** R27「光链」的一道光（原型 `aimState` 的 `beams[j]`）。 */
internal class AimBeam(
    val ex: Double, val ey: Double, val ez: Double,
    val hx: Double, val hz: Double,
    /** 俯角（度）—— T5 的俯角下限判据读它。 */
    val depression: Double,
)

/**
 * R27「光链」整份平铺（原型 `aimState` 的返回值）。
 *
 * ⭐ 从 `sceneState` 里拆出来的唯一理由：R15 的火星要按**出生时刻**回溯落点
 * （判读轮 6 意见 3「已经溅射出的火星，怎么还能跟着光柱走」）。⛔ 在 `drawParticles`
 * 里复制一份落点式子 —— 改了 `aimState`，火星还钉在旧几何上，而且它照样能过一切解析判据。
 */
internal class AimState(
    val t: Double,
    /** 有符号纵向间距（R17：一圈穿越 0 两次）。 */
    val d: Double,
    /** 真距离 = `hypot(mx, d)`（雾/明暗/对比度一律用这个，⛔ 用 `d`）。 */
    val dist: Double,
    /** 对地行程（m）。 */
    val g: Double,
    /** 步相 = `G / STRIDE`。 */
    val s: Double,
    /** 机身地面系横向偏移（routeMX(G)）。 */
    val mx: Double,
    /** 行进方向 = atan(dx/dG)。 */
    val psi: Double,
    val bodyY: Double,
    val roll: Double,
    val bodyLat: Double,
    val leanFwd: Double,
    /** 机头微摆（R14：主项 = 航向滞后；R28：残差项归零）。 */
    val headYaw: Double,
    /** 滞后量（度未饱和前的弧度差）—— T16 判"头是否**滞后**于转弯"。 */
    val dPsiLag: Double,
    /** 腺囊（两道光的共同出处，与 `drawBody` 同一个 `bodyFrame`）。 */
    val gland: BodyPt,
    val beams: Array<AimBeam>,
    val hitFwd: Double,
    /** 横扫位移（beamSw）。 */
    val beamSw: Double,
    /** R17：熄灯包络（T18 (d) 判"掠过段确实熄灯"，⛔ 从 beamI 反推）。 */
    val fire: Double,
    val beamI: Double,
    /** 扫**速**（解析导数，⛔ 差分/累积 ⇒ 会漂）。 */
    val beamSwV: Double,
    /** 没吃 fireEnv 的那一份（只给 DUST.fireK=0 的负样本对照用）。 */
    val beamRaw: Double,
) {
    // ⚠️ Array 字段的 data-class 式 equals/hashCode 会被编译器警告掉；本类是每帧新建的值对象，
    //    ⛔ 不需要结构相等 —— 但 beams 是 Array，显式禁用默认 equals 的自动生成不可行，
    //    所以不标 data class（原型同样每次返回新对象字面量）。
}

/** R22 落地冲击包络的几何常量（⛔ 散在绘制代码里）。
 *
 * ## ⚠️ 为什么 `WIN / PHASE / R_OUT / ATTACK` 是 **var** 而不是 const
 * §A.10 的负样本 A/B/E/F 要在单测里把它们推到越界值（`win→0.5`、`phase→0`、`rOut→40 m`、
 * `attack→0`）再走**真实绘制路径**，const 根本注入不进去 ⇒ 负样本会退化成"恒真式"。
 * ⛔ 上滑杆、⛔ 当观感参数调：出厂值恒等于下表，平时走的就是发布路径；
 * 改完必须复位（`Tripod*Test` 里用 try/finally），否则负样本会污染后面的判据。
 */
internal object ImpactGeom {
    /** 冲击窗宽（步相单位）。⚠️ 必须 < 1/3：三腿彼此错开 1/3 步幅，`win < 1/3` ⇔ 同帧最多一条腿在窗内。 */
    var WIN = 0.22

    /** 冲击相位 = `legState` 里"u<1/3 为摆动相"的那个换相点（⛔ 滑杆：改它 = 改步态）。 */
    var PHASE = 1.0 / 3.0

    const val R_IN = 2.2

    /** 环的外扩半径上限（m）—— E 负样本推到 40 m ⇒ 环半径 12.1→678.9 px。 */
    var R_OUT = 8.0
    const val SQUASH = 0.30

    /** 中心压暗深度（露出更暗的地面）。 */
    const val DIP = 0.55

    /** 起波攻击时间（步相单位）—— F 负样本（`attack→0`）踩的就是它。 */
    var ATTACK = 0.06
}

/** R26 步频冲击的**形状**参数（幅度在 `P.joltA`，是观感旋钮；这些不是，⛔ 滑杆）。
 *
 * ⚠️ `CYC / D_OUT / X_K / LEG_K` 是 **var** 的唯一理由 = §A.10 的 T25 负样本 A/B/C/E
 * 必须在单测里注入越界值再走真实绘制路径；出厂值恒为下表，改完必须复位。
 */
internal object JoltGeom {
    /** 一记冲击里含几个完整回弹周期（1 = 下沉一次、回抽一次、归零）。 */
    var CYC = 1

    /** 距离包络两端（m）。⛔ 用 `st.d`：机器绕到侧面时两者差 20%+。 */
    const val D_IN = 20.0

    /** 包络远端（`fd ≥ 此值 ⇒ 0`）；C 负样本推到 `1e9` = 拆掉距离包络。 */
    var D_OUT = 90.0

    /** 横向串扰比 —— 纯竖直晃读成地震，掺一点横向才像**手持被顶了一下**。 */
    var X_K = 0.3

    /** 三条腿的落地都顶相机。置 1 = 只挂第一条腿（T25(a) 的负样本）。 */
    var LEG_K = 3
}

/** `fireEnv` 的熄灯斜坡宽度（s）。 */
internal const val TRIPOD_FIRE_RAMP = 3.0

/** R21 人群纪元旋钮（⛔ 不给滑杆，平时恒为默认值 —— 每个旋钮只服务一条负样本）。
 *
 * ⚠️ 三条全是 **var**：T22 的 A/A′/C/D 四条负样本靠注入越界值再走真实 `crowdAt` 才咬得住
 * （const 注入不进去 ⇒ 负样本退化成恒真式）。出厂值即发布值，改完必须复位。
 */
internal object CrowdEpoch {
    /**
     * `w = floor(raw/span) + lapK·lapN`。
     * ⭐ 符号是**接缝安全**，⛔ 靠推导定（实测：C(0)=0.00 → C(LOOP−1/12)=−599.99）：
     * C 是负向递减量，一圈走满 −GTOT ⇒ raw 递增、圈内恰跨一个 span ⇒ floor 圈内 +1 一次，
     * 而那一刻正是 mod 归零、z 从带尾跳带头（两侧 α 都贴 0）⇒ 每圈只换一次、在隐形处换。
     * 跨缝那一帧 C 从 −GTOT 跳回 0 ⇒ raw 退整整一个 span ⇒ floor −1，而 lapN 在缝上 +1
     * ⇒ 取 **+1** 才抵得掉（w 不变脸）。取 0 ⇒ 缝上换脸（可见）且逐圈同一批。
     */
    var LAP_K = 1

    /** 把纪元种子混进 `z0` 的开关（T22(c) 负样本 —— 换种子只该换侧向与姿态，⛔ 换纵向位置）。 */
    var Z_SEED_K = 0

    /** 让受害人跟着纪元换人的开关（T22(d) 负样本 —— 他的锚点是抓取几何的一部分）。 */
    var VICTIM_K = 0
}

/** R18 抓取时间轴的 reach 曲线节点（缆须伸出比例，0 = 悬垂）。 */
internal val TRIPOD_GR_R = doubleArrayOf(0.0, 0.0, 0.45, 1.00, 1.00, 0.30, 0.0, 0.0)

/** R18 抓取时间轴的 lift 曲线节点（人从地面被提到腹下的进度）。 */
internal val TRIPOD_GR_L = doubleArrayOf(0.0, 0.0, 0.0, 0.05, 0.45, 1.00, 1.00, 1.0)

// ─────────────────────── 时间轴脚本（§八，VARS 是数据） ───────────────────────

/** 关键帧网格在剧本之间**共用**（R29 硬约束 ①：GTOT 由 VKY 积分决定，换剧本不能换步速曲线）。 */
internal val TRIPOD_DH_KX =
    doubleArrayOf(0.0, 8.0, 26.0, 38.0, 48.0, 54.0, 56.0, 57.5, 59.6, 62.0, 64.0, 68.0, 72.0, 76.0, 79.0, 82.0, 85.0, 88.0, 90.0)

internal val TRIPOD_YH_KX =
    doubleArrayOf(0.0, 54.0, 56.0, 57.5, 59.6, 62.0, 64.0, 68.0, 72.0, 76.0, 79.0, 82.0, 84.0, 86.0, 88.0, 90.0)

internal val TRIPOD_CH_TX =
    doubleArrayOf(0.0, 40.0, 52.0, 54.0, 56.0, 57.5, 59.6, 62.0, 64.0, 68.0, 72.0, 76.0, 79.0, 82.0, 85.0, 88.0, 90.0)

internal val TRIPOD_ESC_TX = doubleArrayOf(0.0, 40.0, 56.0, 58.0, 90.0)

/** `v_walk(t)` m/s：巨影对地速度。⚠️ 撤离段（t>68）**不归零**（站着的机器 = 在看我们跑）。 */
internal val TRIPOD_VKX = doubleArrayOf(0.0, 8.0, 14.0, 30.0, 44.0, 52.0, 55.0, 58.0, 62.0, 68.0, 76.0, 84.0, 90.0)

internal val TRIPOD_VKY = doubleArrayOf(0.0, 0.0, 7.0, 6.0, 5.0, 4.0, 9.0, 12.0, 8.0, 7.0, 10.0, 16.0, 0.0)

/** 时间轴刻度（HUD 用；换戏 = 换刻度，R29）。 */
internal class TripodPhase(val name: String, val t0: Double, val t1: Double)

/**
 * ⭐ R29 剧本表（判读轮 6 意见 1：「现在每次都是从身边路过，应该是随机呀…还可以直接面对面过来」）。
 *
 * 三套**作者手写的骨架**，每个路线纪元轮换一套。⛔ 不是"随机生成 DH/YH/CH"：
 * 这三张表定的是一整段戏（掠过位、揭示位、藏穿越②的偏航区间），随机抽出来的表几乎必然
 * 不满足 T18 的口径（T12 已示范过拒绝采样在高维骨架上的代价）。分工是：
 * **随机留在怎么拐（RA/RP 四条谐波），剧本决定拐去哪**。
 *
 * ⚠️ 每套表的结构前提（闸门 T28(c) 逐套量，⛔ 不写在注释里当已知）：
 * - `dh` **首末两拍同值且两端都平**（`dh[1]=dh[0]` 且 `dh[n−1]=dh[n]`；只"同值"不够 ——
 *   `hDeriv` 两端取首/末**割线**，末段还在爬 ⇒ `camSpeed` 两端不等 ⇒ 抖动幅度在接缝上跳，
 *   R30 的 T4 就是被 V1/V2 的 212/210 末拍挡下的）；
 * - `d` 过零次数**偶数**（⭐ **0 合法** ⇒ V1/V2 零穿越，`scanCross` 退回**全局最近位**）；
 * - `yh`/`ch` 首末两对为 0（⛔ 编舞项不许乘 `seamEnv`，它靠自己闭合）。
 */
internal class TripodScript(
    val name: String,
    /** `d(t)`：巨影与相机的**纵向间距**（有符号；R17 起一圈可穿越 0 两次）。 */
    val dh: DoubleArray,
    /** `yaw(t)` 度：相机偏航（R17 起扫满整圈）。 */
    val yh: DoubleArray,
    /** 编舞横向表（t 域 authoring → G 域求值）。 */
    val ch: DoubleArray,
    /** 「编舞接管」包络（1 = 随机游走让位）。 */
    val esc: DoubleArray,
    /** 熄灯窗 `[fireT0, fireT1]`（s）。⭐ V2 的 `[93,99]` 在 LOOP 之外 ⇒ 全程开火。 */
    val fireT0: Double,
    val fireT1: Double,
    /** 抓取瞬间（s；`grabKx` 的节点相对它排）。 */
    val grabT: Double,
    val phases: Array<TripodPhase>,
)

/** 三套剧本（`VARS[0..2]`，`variantOf(ep) = mod(ep, 3)` 轮转）。 */
internal val TRIPOD_VARS: Array<TripodScript> = arrayOf(
    TripodScript(
        name = "V0 贴身掠过",
        // V0 = R17 定稿原样：两次穿越 → 掠过 12 m → 身后 25 m 揭示 → 偏航扫满 360° 藏穿越②。
        dh = doubleArrayOf(220.0, 220.0, 145.0, 172.0, 110.0, 54.0, 30.0, 14.0, 0.0, -14.0, -26.0, -31.0, -50.0, -70.0, 0.0, 95.0, 178.0, 220.0, 220.0),
        yh = doubleArrayOf(0.0, 0.0, 12.0, 26.6, 90.0, 135.0, 152.5, 152.5, 170.0, 205.0, 250.0, 258.0, 262.0, 285.0, 360.0, 360.0),
        ch = doubleArrayOf(0.0, 0.0, 0.0, 4.0, 8.0, 10.0, 12.0, 14.0, 16.0, 16.0, 22.0, 34.0, 40.0, 24.0, 0.0, 0.0, 0.0),
        esc = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0),
        fireT0 = 54.5, fireT1 = 87.0, grabT = 52.0,
        phases = arrayOf(
            TripodPhase("P0 雾中初现", 0.0, 10.0), TripodPhase("P1 逼近", 10.0, 27.0),
            TripodPhase("P2 转身游走", 27.0, 41.0), TripodPhase("P3 回身再逼近", 41.0, 54.0),
            TripodPhase("P4 冲刺", 54.0, 58.0), TripodPhase("P5 贴身掠过", 58.0, 62.0),
            TripodPhase("P6 身后揭示", 62.0, 70.0), TripodPhase("P7 隐没绕后", 70.0, 79.0),
            TripodPhase("P8 回正归位", 79.0, 90.0),
        ),
    ),
    TripodScript(
        name = "V1 正面逼近",
        // 所有者要的"像以前一样面对面过来"：**零穿越**（d 恒 ≥ 24 ⇒ 偶数 ✓）⇒ YH 全 0
        // （镜头不甩）、CH 全 0 且 esc≡0（没有编舞层，横向完全交给随机游走）。
        // 唯一收住它的东西是 **wanderEnv 的谷**（GW = gAt(t1)）：越贴近越把最近位那一拍
        // 12.9 m 量级的随机侧移压到 0 ⇒ 最近位必然正对镜头（T27 判据 f 钉在设计拍上）。
        // ⚠️ 末三拍 212→220（R30 修 T4）：hDeriv 两端取割线，末拍爬升会在 shakeY 上跳 0.57 px。
        dh = doubleArrayOf(220.0, 220.0, 205.0, 188.0, 165.0, 148.0, 132.0, 118.0, 92.0, 60.0, 34.0, 24.0, 26.0, 48.0, 78.0, 118.0, 168.0, 220.0, 220.0),
        yh = DoubleArray(16),
        ch = DoubleArray(17),
        esc = DoubleArray(5),
        fireT0 = 60.0, fireT1 = 78.0, grabT = 60.0,
        phases = arrayOf(
            TripodPhase("P0 雾中初现", 0.0, 10.0), TripodPhase("P1 直面包抄", 10.0, 30.0),
            TripodPhase("P2 且行且近", 30.0, 50.0), TripodPhase("P3 加速冲来", 50.0, 62.0),
            TripodPhase("P4 面对面", 62.0, 74.0), TripodPhase("P5 退开归位", 74.0, 90.0),
        ),
    ),
    TripodScript(
        name = "V2 侧向横穿",
        // 机器保持 72~96 m 的间距从画面左翼横穿到右翼（机身中心 X 扫过 ≈24%→76% 宽）：
        // 这是"它把整片城区扫了一遍"的读法，⛔ 不是贴身压迫感。镜头**小部分跟随**（YH ≈ 0.3·β，
        // 判据是双边带 0.15·|β| ≤ |yaw| ≤ 0.6·|β|，T27 量过）。
        // ⭐ 熄灯窗 [93,99] 在 LOOP 之外 ⇒ off ≡ 0 ⇒ **V2 全程开火**：横切人群正是这两道热光的戏，
        //    贴身洗白由 fireNear=45 m 兜底（最近位 72 m，衰减项不触发）。
        // ⚠️ 末三拍 210→220 同 V1（接缝闭合要求首末两拍都平，R30 修 T4）。
        dh = doubleArrayOf(220.0, 220.0, 200.0, 180.0, 150.0, 120.0, 105.0, 92.0, 80.0, 74.0, 72.0, 72.0, 76.0, 84.0, 96.0, 120.0, 160.0, 220.0, 220.0),
        yh = doubleArrayOf(0.0, 0.0, -1.0, -2.95, -5.8, -7.8, -8.35, -4.65, 1.35, 5.9, 6.8, 4.2, 2.45, 1.35, 0.0, 0.0),
        ch = doubleArrayOf(0.0, 0.0, 0.0, 0.0, -6.0, -16.0, -28.0, -36.0, -38.0, -20.0, 6.0, 30.0, 40.0, 30.0, 10.0, 0.0, 0.0),
        esc = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 1.0),
        fireT0 = 93.0, fireT1 = 99.0, grabT = 56.0,
        phases = arrayOf(
            TripodPhase("P0 雾中初现", 0.0, 10.0), TripodPhase("P1 逼近", 10.0, 40.0),
            TripodPhase("P2 压到左翼", 40.0, 58.0), TripodPhase("P3 横切正面", 58.0, 72.0),
            TripodPhase("P4 扫过右翼", 72.0, 82.0), TripodPhase("P5 收线归位", 82.0, 90.0),
        ),
    ),
)

// ══════════════════════════════════════════════════════════════════════════════
//  参数 / 调色（附录 A 的逐项转录，⛔ 只认这一份）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * `P` 表的 Kotlin 版（附录 A.1–A.4 的逐项转录，原型 `const P = {...}`）。
 *
 * ⚠️ 字段名与原型**逐字对应**（`bodyY` ↔ `P.bodyY`），⛔ 顺手改名 —— 保真度靠对读。
 * ⚠️ 可写：闸门旋钮 / 滑杆 / `applyVariant` 都会改它（`fireT0`/`fireT1`/`grabT`/`yawAmp`）。
 */
internal class TripodParams {
    // ── A.1 世界 / 相机 / 时间 ──
    var loop: Double = 90.0
    var horizonK: Double = 0.58
    var fz: Double = 60.0
    var ppm: Double = 22.0
    var eye: Double = 1.7

    /** ⚠️ R17 起是**倍率**（`setYawAmp` 把整张 YH_KY 乘它），⛔ 回退成"绝对角度"的旧口径。 */
    var yawAmp: Double = 1.0
    var tiltMax: Double = 0.20

    /** ⛔ 1/45：两层雾漂移周期（s⁻¹），必须整除 LOOP。 */
    var driftA: Double = 1.0 / 45.0
    var driftB: Double = 1.0 / 30.0

    // ── A.2 雾与调色 ──
    var d0: Double = 70.0
    var fogNear: Double = 8.0
    var fogA: Double = 0.24
    var fogB: Double = 0.28
    var groundBand: Double = 0.40

    /** R25 掠过段负压强度（⚠️ 隔离项：0 即整条效果不存在）。 */
    var wakeA: Double = 0.55

    /** R19 雾裙：世界系固定厚度的一层贴地雾（⛔ 屏幕固定高度 = "每栋楼在同一行被切齐"的成因）。 */
    var skirtH: Double = 8.0
    var skirtA: Double = 0.45

    /** R22 落地冲击压雾强度（0.50 是图判出来的：0.30 读成灰、0.80 连成实心黑饼）。 */
    var impactA: Double = 0.50

    /** R23 命中扬尘强度（⚠️ 整层已由 R31 默认关闭 `DUST.on=0`）。 */
    var dustA: Double = 0.55
    var grain: Double = 0.055

    /** 地平线余烬（**背景光**，⛔ 灯）。 */
    var ember: Double = 0.30

    /** 火星短划线宽（m；T17(d) 判长宽比 ≥ 4，⛔ 调粗成圆泡）。 */
    var emberW: Double = 0.12

    /** R30 火星条数（滑杆 8–48，默认 24；⚠️ 表按**量程上界** 48 落，见 `TripodOpItem`）。 */
    var emberN: Int = 24

    // ── A.3 巨影解剖 ──
    var bodyY: Double = 30.0

    /** ⛔ 10 / 34：大腿 / 小腿（D32，R11 后；实测 L2/L1 = 3.40）。⛔ 回等长 20/20。 */
    var l1: Double = 10.0
    var l2: Double = 34.0
    var rh: Double = 4.0

    /** ⛔ 15 m：落脚半径（T10 的 IK 余量对它最敏感）。 */
    var rf: Double = 15.0

    /** ⛔ 13 / 7 m：显式膝点目标的上抬 / 外展（只给方向，⛔ 给长度）。 */
    var kneeUp: Double = 13.0
    var kneeOut: Double = 7.0
    var bodyRx: Double = 6.4
    var bodyRy: Double = 2.6
    var bodyLump: Double = 0.16

    /** R24 机身呼吸：只调 bodyLump 的幅度（⛔ 调半径、⛔ 调位置）。 */
    var breathA: Double = 0.45
    var breathHz: Double = 1.0 / 18.0
    var cowlRx: Double = 3.1
    var cowlRy: Double = 1.6
    var cowlFwd: Double = 1.4

    /** ⛔ 3.7 / 2.4 m：前下腺囊半轴（R28 头身一体：放大成甲壳轮廓上的一道鼓包）。 */
    var headRx: Double = 3.7
    var headRy: Double = 2.4

    /** ⛔ 3.5 / 0.8 m：腺囊前伸 / 下垂（⚠️ 3.5 是滑杆格点值，T26 专盯吸附）。 */
    var headFwd: Double = 3.5
    var headDown: Double = 0.8

    /** 腹下悬垂缆须（MED+）。⭐ R18 复用同一束当抓取器官（⛔ 不新增第四种器官）。 */
    var tendN: Int = 3
    var tendLen: Double = 5.4

    // ── A.8 抓取与人群 ──
    var crowdN: Int = 8
    var personH: Double = 1.7

    /** 抓取那一刻机器的间距（⛔ 写死秒数：拖滑杆只会让人和时间轴脱钩）。 */
    var grabT: Double = 52.0

    /** 人在抓取瞬间位于机器**正前方**的距离。 */
    var grabDz: Double = -25.0

    /** 保留为闸门 T19 的负样本旋钮（把人挪开 ⇒ 够不着），⛔ 不给滑杆。 */
    var grabDx: Double = 0.0

    /** 缆须解剖极限（⛔ 无限长的橡皮管：一旦"够多远都够得着"，T19(b) 就成了恒真式）。 */
    var tendReach: Double = 46.0
    var crowdInk: Double = 1.0

    /** 人被提到腹下后**开始溶解进外壳**的 lift 位置（窗口宽固定 0.28，终点恒为 1）。 */
    var crowdSoak: Double = 0.72

    /** R21 人群纪元种子（与 routeSeed0 同构 —— 每圈换一批人）。 */
    var crowdSeed0: Int = 1

    // ── A.4 步态与姿态 ──
    var stride: Double = 10.0
    var lift: Double = 4.6
    var outSwing: Double = 2.8
    var rollA: Double = 4.0

    /** ⛔ 0.6 m：腿半径基准（⚠️ 原为 0.62，R28 的 T26 抓到的滑杆吸附）。 */
    var legR: Double = 0.6

    /** 膝外弓（× 段长）—— 贝塞尔控制点的横向偏移，与显式膝点目标是两套独立的弯量。 */
    var kneeBow: Double = 0.14

    /** ⛔ 机身垂直起伏 = 0（所有者判读轮 3 意见 2「机器人的头不需要上下运动」）。 */
    var bobA: Double = 0.0

    /** 横向重心转移（1 步周期）—— "迈过去"的主要来源。 */
    var swayA: Double = 1.9

    /** 俯仰等效纵移（1 步周期）。 */
    var pitchA: Double = 1.05

    /** 恒幅手持微抖幅度（px）。 */
    var shakeA: Double = 1.0

    /** ⭐ R26 每记落地给相机一记阻尼回弹（px，20 m 内满幅、90 m 外为 0）。⚠️ 抬的是**相机**不是机身。 */
    var joltA: Double = 9.0

    /** ⭐ R14 机头滞后：`headYaw = −headLagK·[ψ(G) − ψ(G−headLagArm)]`（滞后**距离**⛔ 时间）。 */
    var headLagArm: Double = 7.0
    var headLagK: Double = 0.13

    /** 机械止挡（R16 后 dψ/dG 峰值随种子变 3.7~6.9 °/m，不限幅 T16 必红）。 */
    var headLagCap: Double = 25.0

    /** 步相残差幅值（度）—— ⭐ R28 归 **0**（与转弯无关的残差是"万向节摄像头"的成因）。 */
    var headYawA: Double = 0.0

    /** ⛔ 恒 0：把**绝对航向**灌进轮廓旋转的开关，只作为 T16 的负样本。 */
    var headSpinK: Double = 0.0

    // ── A.5 热光（R31 定稿） ──
    var beamAmp: Double = 34.0
    var beamPeriod: Double = 10.0
    var beamGap: Double = 2.6
    var beamDiv: Double = 0.055

    /** ⭐ R31：近端半宽 0.38 → 0.18 m（出射端是一条细线而不是楔子口）。 */
    var beamHalfNear: Double = 0.18
    var beamHalfFar: Double = 1.8
    var beamI: Double = 0.62

    /** 柱体起亮比 / 峰位（stop 0 / 0.12 / 0.55 / 1）—— R31 修"枪口灯"。 */
    var beamFoot: Double = 0.12
    var beamPeakAt: Double = 0.12

    // ── A.6 行进路线 ──
    /** 随机游走总开关（0 = 退回旧行为：正对镜头直着走过来，T12 的负样本靠它）。 */
    var wK: Double = 1.0
    var routeSeed0: Int = 1

    /** 每 routeLaps 圈换一条路线（默认 2 圈 = 180 s）。 */
    var routeLaps: Int = 2

    /** ⛔ 恒 true：关掉拒绝采样 = T12 的负样本。 */
    var routeReject: Boolean = true

    /** ⭐ R29：−1 = 按纪元轮转三套剧本；≥0 = 手动钉住某一档（⛔ 不是调试开关，驱动脚本逐档截图要用）。 */
    var routeVar: Int = -1

    /** 接缝坡道宽度（m；R16：只作用于随机游走项）。 */
    var seamRamp: Double = 20.0

    /** 编舞项总开关（0 = 没有掠过也没有绕后，T18 的负样本靠它）。 */
    var escAmp: Double = 1.0

    /** 「越接近掠过点越收进正前方」的谷壁（G 占 GTOT 的比例；R17：0.06/0.28 ⇒ ±36~168 m）。 */
    var wValleyA: Double = 0.06
    var wValleyB: Double = 0.28

    // ── A.7 熄灯与建筑 ──
    var fireT0: Double = 54.5
    var fireT1: Double = 87.0

    /** dist < fireNear 时强制熄灯（⛔ 只靠时间窗：改了 DH 让机器提前贴近就会漏）。 */
    var fireNear: Double = 45.0

    /** 每帧建筑上限（中景/近景）—— ⚠️ §A.7 的 22/8 是**滑杆名义值**，实测最坏 19（`TripodOpItem.BUILDINGS`）。 */
    var nMid: Int = 22
    var nNear: Int = 8

    // ── 音频三通道（K7 的 AudioSmoother 喂真值；breathe = 原型演示模式的自振荡） ──
    var audioBass: Double = 0.5
    var audioTreble: Double = 0.5
    var audioEnergy: Double = 0.5
    var audioBreathe: Boolean = false
}

/**
 * `CFG` 调色（附录 A.2；原型 `const CFG = {...}`）。
 *
 * ⚠️ `FOG` 一旦取近白色，两层流动雾（合计 ~0.5 α）会把 60 m 处的巨影洗成和远景一样亮
 * ⇒ 压迫位失去对比度（D12）。`EMBER` 是**背景光**不是灯（判读轮 2 意见 2）。
 * 每色一个 `IntArray(3)` RGB（0..255）—— 与原型 `[r,g,b]` 三元组同构。
 */
internal class TripodPalette {
    val fog = intArrayOf(178, 174, 168)
    val sky = intArrayOf(78, 82, 92)
    val gnd = intArrayOf(58, 55, 53)
    val silh = intArrayOf(18, 19, 23)
    val rim = intArrayOf(206, 212, 222)
    val beam = intArrayOf(255, 250, 234)

    /** 地平线余烬的暖光（背景光）。 */
    val ember = intArrayOf(206, 122, 62)
}

/**
 * 建筑带配置（原型 `BAND.mid` / `BAND.near`；`span` 由 K4 `buildCum` 覆写成 GTOT 的整数分之一）。
 *
 * ⚠️ `zMin / xLo / ink` 的可变性只为 **T14 的负样本**（D19 之前那两项必须能"改真实参数再重测"，
 * ⛔ 手喂常量）；生产路径恒用 [TripodFogWorld] 里那一份，⛔ 上滑杆。
 */
internal class TripodBandCfg(
    var zMin: Double,
    /** ⚠️ 可变：`buildCum` 里 `mid.span = GTOT`、`near.span = GTOT/2`（ΔC 恰为一个跨度 ⇒ T4 逐栋重合）。 */
    var span: Double,
    var xLo: Double,
    val xHalf: Double,
    val wLo: Double,
    val wHi: Double,
    val hLo: Double,
    val hHi: Double,
    val n: Int,
    var ink: Double,
)

/** 人群带配置（原型 `CBAND`；§七 回收机制的**同构第二实例**，⛔ 第二套回收数学）。 */
internal class TripodCrowdBandCfg(
    val zMin: Double,
    /**
     * 淡入宽度（m）—— "从雾里显形"，按相机退移速度约 5~6 s 走完。
     * ⚠️ 可变只为 **T19 负样本 D**（拆掉近端淡入 ⇒ 回收接缝帧人能看见 = 瞬移）。
     */
    var fin: Double,
    /** 淡出宽度（m）—— 在远端收干净。 */
    val fout: Double,
    val xLo: Double,
    val xHalf: Double,
    /** ⚠️ 可变：`buildCum` 里覆写成 GTOT（一圈一回收，与 mid 带同理）。 */
    var span: Double,
)

/** 人群表的一个坑位（原型 `crowdTable` 的输出元素；R21：表里只留**坑位**，侧向与姿态由 `crowdIdent` 现算）。 */
internal class CrowdSlot(
    val i: Int,
    /** 受害人 = 抓取几何的一部分（锚点是**剧本**的量、不是纪元的量，R21 纪律 ②）。 */
    val victim: Boolean,
    /** 世界系纵向坑位（⛔ 把纪元种子混进它 —— 只有在这里换人才是隐形的）。 */
    val z0: Double,
    /** 受害人的世界系横向锚点（非受害人是 0，由 `crowdIdent` 现算）。 */
    val x: Double,
    /** 受害人的姿态（非受害人是 0）。 */
    val kind: Int,
)

/**
 * ⭐ 世界状态 —— 原型模块级可变状态的**唯一属主**（见文件 KDoc「为什么有一个 TripodFogWorld」）。
 *
 * 构造时完成原型模块级的初始化（`DH/YH` 建表、`scanCross()`、`GREN/GRLIFT`、默认 RA/RP）；
 * `cumT/gtot/chG/escG/gaim` 等由 **K4 `buildCum`** 填；`cam` 由 [TripodFogMath.layout] 填。
 */
internal class TripodFogWorld {

    // ── 参数 / 调色 / 档位 ──
    val params = TripodParams()
    val palette = TripodPalette()

    /** 画质档（原型 `P.tier`；K6 做 `FxLevel → TripodLevel` 的一次性映射）。 */
    var tier: TripodLevel = TripodLevel.MEDIUM

    // ── 相机布局（原型 W/H/cx/hz/PPM） ──
    val cam = CamLayout()

    // ── 时间轴脚本（当前剧本持有的四张表 + 刻度） ──
    var curVar: Int = -1
    var varSwitches: Int = 0

    /** 当前剧本的 `dh` 引用（`DH_KY`）。 */
    var dhKy: DoubleArray = TRIPOD_VARS[0].dh
    var yhKy: DoubleArray = TRIPOD_VARS[0].yh
    var chTy: DoubleArray = TRIPOD_VARS[0].ch
    var escTy: DoubleArray = TRIPOD_VARS[0].esc
    var phases: Array<TripodPhase> = TRIPOD_VARS[0].phases

    /** `d` 的有符号纵深表（原地重建，⛔ 换实例）。 */
    val dh = Hermite()

    /** 偏航表（度；`setYawAmp` 原地重建）。 */
    val yh = Hermite()

    /** R18 抓取曲线（reach / lift；`setGrabKnots` 原地重建）。 */
    val gren = Hermite()
    val grlift = Hermite()

    /** `d` 的过零时刻（原地改字段）。 */
    val tcross = CrossPt()

    // ── 行进路线（R16 种子导出） ──
    /** 四条谐波的幅值（默认值 = 原型 `let RA = [38, 9, 5, 3]`）。 */
    var ra = doubleArrayOf(38.0, 9.0, 5.0, 3.0)

    /** 四条谐波的相位（默认值 = 原型 `let RP = [0.7, 2.4, 5.1, 1.9]`）。 */
    var rp = doubleArrayOf(0.7, 2.4, 5.1, 1.9)

    /** 曲折度包络的谷心 = 掠过瞬间的 G（R17：⛔ 不再是"全局最小 d"）。 */
    var gw: Double = 120.0

    var routeEpoch: Int = -1
    var routeTries: Int = 0

    /** 圈数（K7 每过一圈 +1；驱动纪元轮转与人群换脸）。 */
    var lapN: Int = 0

    // ── 步态积分（K4 buildCum 填） ──
    var cumT: DoubleArray = DoubleArray(0)
    var cumV: Double = 0.0
    var gscale: Double = 1.0

    /** `G(LOOP)` = 整数步 × STRIDE（当前 600 m = 60 步 × 10）。 */
    var gtot: Double = 240.0
    var chG: Hermite? = null
    var escG: Hermite? = null

    /** 开火段里机器最贴近我们的那一刻（t 域）及其 G 域映射。 */
    var taim: Double = 0.0
    var gaim: Double = 0.0

    // ── 建筑 / 人群带（span 由 buildCum 覆写） ──
    val bandMid = TripodBandCfg(
        zMin = 105.0, span = 240.0, xLo = 16.0, xHalf = 210.0,
        wLo = 14.0, wHi = 30.0, hLo = 16.0, hHi = 38.0, n = 34, ink = 1.00,
    )
    val bandNear = TripodBandCfg(
        zMin = 42.0, span = 120.0, xLo = 70.0, xHalf = 175.0,
        wLo = 16.0, wHi = 34.0, hLo = 22.0, hHi = 46.0, n = 14, ink = 0.72,
    )
    val crowdBand = TripodCrowdBandCfg(
        zMin = 20.0, fin = 35.0, fout = 110.0, xLo = 4.0, xHalf = 30.0, span = 600.0,
    )

    // ── 人群表缓存（K4 crowdTable 读写；applyVariant 第 ⑥ 步作废） ──
    var crowdT: Array<CrowdSlot>? = null
    var crowdKey: String = ""
    var crowdBuilds: Int = 0

    // ── 建筑实例表（K5 genBuildings 填；K4 sceneState 只读 + 借用） ──
    var bMid: Array<TripodBuilding> = emptyArray()
    var bNear: Array<TripodBuilding> = emptyArray()

    // ── R12 预分配缓冲（K4 sceneState 帧首覆写；⛔ 跨帧持有 scene 再读，见 TripodScene KDoc） ──
    /** 三条腿的步态状态（容量 3 = 三足）。 */
    val legsBuf: Array<LegState> = Array(3) { LegState() }

    /** 建筑入选缓冲（容量 48 = 单带栋数上限；部分选择只保留最近 n，⛔ 全排序）。 */
    val bldSelMid: Array<BldSel> = Array(48) { BldSel() }
    val bldSelNear: Array<BldSel> = Array(48) { BldSel() }

    /** 人群渲染缓冲（容量 32 > 闸门负样本用过的 crowdN=24）。 */
    val crowdBuf: Array<CrowdAt> = Array(32) { CrowdAt() }

    /** 换戏回调（原型 `onVariant`；K7 用它驱动纪元计数）。 */
    var onVariant: (() -> Unit)? = null

    init {
        // 原型模块级初始化顺序：DH/YH 建表 → scanCross()（:511 在 boot 前就跑）→ GREN/GRLIFT（:1059）
        dh.set(TRIPOD_DH_KX, dhKy)
        yh.set(TRIPOD_YH_KX, yhKy)
        val gkx = TripodFogMath.grabKx(params.grabT)
        gren.set(gkx, TRIPOD_GR_R)
        grlift.set(gkx, TRIPOD_GR_L)
        TripodFogMath.scanCrossInto(this)
    }

    // ── 路线函数（原型模块函数，闭包在本状态上） ──

    /** G 是**圆周量**（周期 GTOT）：所有按 G 取值的入口一律先回绕（原型 `Gw`）。 */
    fun gw(g: Double): Double = TripodFogMath.mod(g, gtot)

    /** 四条整次谐波叠出"看不出周期"的曲折感（原型 `pathBase`）。 */
    fun pathBase(g: Double): Double {
        val k = TripodFogMath.TAU / gtot
        var v = 0.0
        var i = 0
        while (i < ra.size) {
            v += ra[i] * sin((i + 1) * k * g + rp[i])
            i++
        }
        return v
    }

    /**
     * ⭐ R16 接缝包络：在 G=0 与 G=GTOT 处**值与一阶导都为 0**（smoothstep 两端零导）
     * ⇒ 无论 RA/RP 抽成什么样，**随机游走项**都严格闭合（T4 不需要任何条件化）。
     * ⛔ 坡道不能太长：它会把路线"捋直"一段（R=20 m ≈ 4 s 的雾中行程，正好全埋在 4% 里）。
     * ⚠️ R17：**只作用于随机游走项**（⛔ 编舞项不许乘它 —— 见文件头 CH_TY 注释）。
     *
     * ⚠️ **不回绕**（原型 `seamEnv` 同样不）：`min(G, GTOT−G)` 对负 G / G>GTOT 给出负 x，
     *    smoothstep 再钳到 0。回绕是 [wanderPart] / [choreoPart] 入口的事（原型 `G = Gw(G)`），
     *    ⛔ 在 seamEnv 里回绕 = 出界点的值从 0 变成坡道中段（599.9 会被算成 5.9 的平滑值）。
     */
    fun seamEnv(g: Double): Double {
        val x = min(g, gtot - g)
        return if (x >= params.seamRamp) 1.0
        else TripodFogMath.smoothstep(0.0, params.seamRamp, x)
    }

    /** 曲折度包络 —— **越接近掠过点越要收进我们正前方**（原型 `wanderEnv`）。 */
    fun wanderEnv(g: Double): Double =
        TripodFogMath.smoothstep(
            gtot * params.wValleyA, gtot * params.wValleyB,
            abs(TripodFogMath.dWrap(g, gw, gtot)),
        )

    /** 「编舞接管」开关（原型 `escEnv`；CHG/ESCG 由 buildCum 建，建之前恒 0）。 */
    fun escEnv(g: Double): Double = escG?.eval(g) ?: 0.0

    /** R17 编舞横向表（原型 `choreoMX`）。 */
    fun choreoMX(g: Double): Double = chG?.eval(g) ?: 0.0

    /**
     * 随机游走项（原型 `wanderPart`）。
     * `(1−escEnv)` 把随机游走在编舞段**压掉**，`escEnv` 把编舞接进来 —— ⛔ 两件事必须同时做，
     * 只做后者会让 ±55 m 的随机幅值叠在 16 m 的编舞小弧上。
     *
     * ⭐ 入口先 `gw(g)` 回绕（原型 `G = Gw(G)`）：`escEnv`/`choreoMX` 走 Hermite 求值，
     *    **出界即钳制**（hEval 的行为）⇒ `routePSI(0)` 的中心差分读到 `routeMX(−0.5)`，
     *    不回绕就会钳到 ky[0] 而 JS 绕到 GTOT−0.5 取 ky[末] —— 接缝处 ψ 凭空差 2.6°。
     */
    fun wanderPart(g: Double): Double {
        val gg = gw(g)
        return params.wK * seamEnv(gg) * (1.0 - escEnv(gg)) * wanderEnv(gg) * pathBase(gg)
    }

    /** 编舞项（原型 `choreoPart`；同样先回绕，理由同 [wanderPart]）。 */
    fun choreoPart(g: Double): Double {
        val gg = gw(g)
        return params.escAmp * escEnv(gg) * choreoMX(gg)
    }

    /** 机身在地面系里的横向偏移 = 随机游走项 + 编舞项（原型 `routeMX`）。 */
    fun routeMX(g: Double): Double = wanderPart(g) + choreoPart(g)

    /**
     * ψ = 行进方向 = atan(dx/dG)（原型 `routePSI`）。
     * ⛔ 归一化成弧长参数：`|x′| ≤ 0.6` 时对地速度与步幅的误差 ≤ 17%，肉眼读不出，
     * 而省掉一张数值积分表。中心差分而不是解析导数（包络是 smoothstep×谐波）。
     */
    fun routePSI(g: Double): Double {
        val h = 0.5
        return atan((routeMX(g + h) - routeMX(g - h)) / (2 * h))
    }

    /**
     * ⭐ R17：T12 (c) / scanRoute 的「回到正前方」判据取值层 —— **不含 escEnv 门**的随机游走项。
     * ⛔ 判据不能取 `routeMX`：GAIM = gAt(56) 正好落在 escEnv 的 0→1 交接坡道上
     * （实测 routePSI(GAIM) = 9.8°，整条坡道最大 24.9°），量的是交接而不是"路线有没有收回来"
     * ⇒ 200 个种子全部误判为不达标（拒绝采样连抽 80 次也抽不到）。
     */
    fun wanderRaw(g: Double): Double {
        val gg = gw(g)
        return params.wK * seamEnv(gg) * wanderEnv(gg) * pathBase(gg)
    }

    /** [wanderRaw] 的中心差分方向（原型 `wanderRawPSI`）。 */
    fun wanderRawPSI(g: Double): Double {
        val h = 0.5
        return atan((wanderRaw(g + h) - wanderRaw(g - h)) / (2 * h))
    }

    /**
     * T12 (g) 换路接缝判据的取值层：只含 `seamEnv·pathBase`（原型 `routeRaw`）。
     * ⛔ 判据不能取 `routeMX`（编舞项在接缝处本来就是 0，拆掉 seamEnv 也量不出跳变 ⇒ 假判据）；
     * ⛔ 也不含 `P.wK`（总开关为 0 时同样恒 0）。
     */
    fun routeRaw(g: Double): Double {
        val gg = gw(g)
        return seamEnv(gg) * pathBase(gg)
    }

    // ── 步态积分读取（cumT 由 K4 buildCum 填） ──

    /** `cumAt`（原型同名）：CUM_T 的线性插值（N = 3600 格的梯形积分表）。 */
    fun cumAt(t: Double): Double {
        val n = cumT.size - 1
        val x = TripodFogMath.clamp(t, 0.0, params.loop) / params.loop * n
        val i = min(n - 1, kotlin.math.floor(x).toInt())
        return TripodFogMath.lerp(cumT[i], cumT[i + 1], x - i)
    }

    /** `gAt`（原型同名）：`Gscale·cumAt(t)`。 */
    fun gAt(t: Double): Double = gscale * cumAt(t)
}

// ══════════════════════════════════════════════════════════════════════════════
//  纯函数（原型模块函数，逐字移植）
// ══════════════════════════════════════════════════════════════════════════════

internal object TripodFogMath {

    val TAU: Double = Math.PI * 2

    // ─────────────────────── 小工具 ───────────────────────

    fun clamp(v: Double, a: Double, b: Double): Double = if (v < a) a else if (v > b) b else v

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** 数学模（原型 `mod`：结果恒与 `n` 同号，⛔ 用 `%` —— 负operand 会出负数）。 */
    fun mod(a: Double, n: Double): Double = ((a % n) + n) % n

    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val d = e1 - e0
        val t = clamp((x - e0) / (if (d != 0.0) d else 1e-9), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun easeInOut(w: Double): Double =
        if (w < 0.5) 2 * w * w else 1 - (-2 * w + 2).pow(2.0) / 2

    /** 打包 ARGB（与 `android.graphics.Bitmap.getPixels` / Compose `Color(Int)` 同格式）。 */
    fun packArgb(r: Int, g: Int, b: Int, a: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    /** 相对亮度（原型 `lum`；入参 0..255 标量）。 */
    fun lum(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

    /**
     * 32 位 LCG（原型 `lcg`：`Math.imul(s, 1664525) + 1013904223`，输出 `s / 2^32`）。
     *
     * ⭐ 与 JS 版**逐位同构**：Kotlin `Int` 运算按 2^32 回绕，与 `Math.imul` + `>>> 0` 同 bits；
     * 取输出时用 `and 0xFFFFFFFFL` 还原 uint32 语义。⛔ 换 `kotlin.random.Random` ——
     * 路线 / 建筑 / 人群的抽样必须可复现（闸门与负样本都钉在这份序列上）。
     */
    class Lcg(seed: Int) {
        private var s: Int = seed

        fun next(): Double {
            s = s * 1664525 + 1013904223
            return (s.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    // ─────────────────────── Hermite ───────────────────────

    /** Fritsch–Carlson 单调三次（原型 `hermite`；`ky` 复制一份，⛔ 与调用方共享可变数组）。 */
    fun hermite(kx: DoubleArray, ky: DoubleArray): Hermite {
        val h = Hermite()
        h.set(kx, ky)
        return h
    }

    /** 分段线性（原型 `pw`；`VKY` 步速表走它）。 */
    fun pw(kx: DoubleArray, ky: DoubleArray, x: Double): Double {
        if (x <= kx[0]) return ky[0]
        if (x >= kx[kx.size - 1]) return ky[ky.size - 1]
        var i = 0
        while (i < kx.size - 2 && x > kx[i + 1]) i++
        return lerp(ky[i], ky[i + 1], (x - kx[i]) / (kx[i + 1] - kx[i]))
    }

    // ─────────────────────── 距离雾（§3.2 核心机制） ───────────────────────

    /**
     * ⚠️ `d0` 必须可从外部传入：`energy → 雾密度` 是本设计仅有的两条音频响应之一，
     * `sceneState` 算出的 `st.D0` 若无人消费，那条滑杆就是死的（原型第一版正是如此）。
     *
     * ⚠️ `d0 == 0.0` 时回落 `params.d0`（原型 `D0 || P.D0` 的假值语义）。
     */
    fun fogOf(d: Double, d0: Double, params: TripodParams): Double {
        val dd = if (d0 != 0.0) d0 else params.d0
        return 1 - exp(-max(0.0, d - params.fogNear) / dd)
    }

    /**
     * 背景本身已是雾色 ⇒ "降 alpha" 与 "混进雾色" 是同一件事的两种写法；
     * Kotlin 侧只留 alpha 一条（原型 `shade`）。
     */
    fun shade(d: Double, d0: Double, params: TripodParams, pal: TripodPalette): Shade {
        val out = Shade(0, 0.0, 0.0)
        return shadeInto(out, d, d0, params, pal)
    }

    /** [shade] 的**写入版本**（绘制热路径，⛔ 每图元新建 [Shade]）。 */
    fun shadeInto(
        out: Shade,
        d: Double,
        d0: Double,
        params: TripodParams,
        pal: TripodPalette,
    ): Shade {
        val f = clamp(fogOf(d, d0, params), 0.0, 0.995)
        out.col = mixPacked(pal.silh, pal.fog, f * 0.45, 1.0)
        out.a = 1 - f
        out.fog = f
        return out
    }

    /** [shade] 的 alpha（`1 − fog`）—— 绘制热路径用这个，⛔ 每图元建 Shade 对象。 */
    fun shadeA(d: Double, d0: Double, params: TripodParams): Double =
        1 - clamp(fogOf(d, d0, params), 0.0, 0.995)

    /** 剪影落在背景上的实际对比度（原型 `contrastAt`；T3 的判据口径，scanRoute 也读它）。 */
    fun contrastAt(d: Double, d0: Double, params: TripodParams, pal: TripodPalette): Double {
        val bgR = lerp(pal.gnd[0].toDouble(), pal.fog[0].toDouble(), 0.55)
        val bgG = lerp(pal.gnd[1].toDouble(), pal.fog[1].toDouble(), 0.55)
        val bgB = lerp(pal.gnd[2].toDouble(), pal.fog[2].toDouble(), 0.55)
        val a = shadeA(d, d0, params)
        val resR = lerp(bgR, pal.silh[0].toDouble(), a)
        val resG = lerp(bgG, pal.silh[1].toDouble(), a)
        val resB = lerp(bgB, pal.silh[2].toDouble(), a)
        val bgL = lum(bgR, bgG, bgB)
        return abs(lum(resR, resG, resB) - bgL) / bgL
    }

    /** 三通道线性插值（原型 `mix3`；结果 0..255 的 Int 三元组，调用方自行钳位）。 */
    fun mix3(c0: IntArray, c1: IntArray, t: Double): IntArray = intArrayOf(
        lerp(c0[0].toDouble(), c1[0].toDouble(), t).toInt(),
        lerp(c0[1].toDouble(), c1[1].toDouble(), t).toInt(),
        lerp(c0[2].toDouble(), c1[2].toDouble(), t).toInt(),
    )

    /**
     * [mix3] + `rgba(…, α)` 的**打包**合体（原型 `rgba(mix3(c0,c1,t), a)` 的 Kotlin 形态）。
     *
     * ⭐ 与 [mix3] 逐通道同一个表达式，只是不落 `IntArray` ⇒ 绘制侧每图元零分配
     * （R12 / §十五 K6「⛔ draw 内零分配」）。⛔ 在 K6 里再手抄一遍混色式子。
     */
    fun mixPacked(c0: IntArray, c1: IntArray, t: Double, a: Double): Int = packArgb(
        lerp(c0[0].toDouble(), c1[0].toDouble(), t).toInt(),
        lerp(c0[1].toDouble(), c1[1].toDouble(), t).toInt(),
        lerp(c0[2].toDouble(), c1[2].toDouble(), t).toInt(),
        alpha8(a),
    )

    /** 不混色、只给 α（原型 `rgba(CFG.BEAM, a)` 那一类调用）。 */
    fun rgba(c: IntArray, a: Double): Int = packArgb(c[0], c[1], c[2], alpha8(a))

    /**
     * [mixPacked] 的**首色已是打包 Int** 的重载（原型 `mix3(sh.col, …)` 那一类调用）。
     *
     * ⭐ 为什么单独一个重载：Kotlin 侧 [Shade.col] 是**打包 ARGB**（[shadeInto] 直接用
     * `mixPacked` 落地，不落 `IntArray`），而原型那边是 `[r,g,b]` 数组 ⇒ 绘制点要么每图元
     * 解包成数组（= 每图元一次分配，违 R12 / §十五 K6），要么在这里解包。
     * 通道次序与掩码必须与 [packArgb] 互逆，⛔ 不要照抄 `android.graphics.Color` 的 RGB。
     * 逐通道表达式与 [mixPacked] 逐字一致（含 `.toInt()` 的**向零截断** = 原型 `rgba` 里的 `| 0`）。
     */
    fun mixPacked(c0: Int, c1: IntArray, t: Double, a: Double): Int = packArgb(
        lerp(((c0 shr 16) and 0xFF).toDouble(), c1[0].toDouble(), t).toInt(),
        lerp(((c0 shr 8) and 0xFF).toDouble(), c1[1].toDouble(), t).toInt(),
        lerp((c0 and 0xFF).toDouble(), c1[2].toDouble(), t).toInt(),
        alpha8(a),
    )

    /** [rgba] 的打包 Int 版（`rgba(sh.col, a)` 那一类调用；α 量化同 [alpha8]）。 */
    fun rgba(c: Int, a: Double): Int = packArgb(
        (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, alpha8(a)
    )

    /** α（0..1）→ 8 bit（与浏览器 `rgba()` 字符串落地时的量化同级）。 */
    fun alpha8(a: Double): Int = (clamp(a, 0.0, 1.0) * 255.0 + 0.5).toInt()

    // ─────────────────────── 相机与投影（§三） ───────────────────────

    /** 原型 `layout`：按画布尺寸重算 `cx/hz/PPM`（原地改 [CamLayout]，⛔ 换对象）。 */
    fun layout(world: TripodFogWorld, w: Double, h: Double) {
        world.cam.w = w
        world.cam.h = h
        world.cam.cx = w / 2
        world.cam.hz = h * world.params.horizonK
        world.cam.ppm = world.params.ppm * min(w, h) / 540
    }

    /** 世界系 → 相机系（原型 `toCam`）。 */
    fun toCam(x: Double, z: Double, yaw: Double): DoubleArray =
        DoubleArray(2).also { toCamInto(it, x, z, yaw) }

    /** [toCam] 的**写入版本**（绘制热路径，⛔ 每图元建 `DoubleArray`）。 */
    fun toCamInto(out: DoubleArray, x: Double, z: Double, yaw: Double) {
        val c = cos(yaw)
        val s = sin(yaw)
        out[0] = x * c - z * s
        out[1] = z * c + x * s
    }

    /** 相机系 → 世界系（原型 `fromCam`）。 */
    fun fromCam(lat: Double, along: Double, yaw: Double): DoubleArray {
        val c = cos(yaw)
        val s = sin(yaw)
        return doubleArrayOf(lat * c + along * s, -lat * s + along * c)
    }

    /**
     * 针孔投影（原型 `pr`：`s = fz/(fz+along)`）。
     * ⚠️ `along < 0.5` ⇒ 已在相机背后，返回 `null` 由调用方剔除。
     */
    fun pr(cam: CamLayout, params: TripodParams, x: Double, y: Double, z: Double, yaw: Double): ProjPt? {
        val out = ProjPt()
        return prInto(out, cam, params, x, y, z, yaw, DoubleArray(2))
    }

    /**
     * [pr] 的**写入版本** —— K6 绘制侧的唯一入口（⛔ 每图元新建 [ProjPt] 与 `DoubleArray`）。
     *
     * ⚠️ `tmp` 必须由调用方持有（长度 2）：它只是 [toCamInto] 的落点，⛔ 跨调用复用同一个
     * 实例给**嵌套**的两次投影（内外层会互相覆写）。
     */
    fun prInto(
        out: ProjPt,
        cam: CamLayout,
        params: TripodParams,
        x: Double,
        y: Double,
        z: Double,
        yaw: Double,
        tmp: DoubleArray,
    ): ProjPt? {
        toCamInto(tmp, x, z, yaw)
        val lat = tmp[0]
        val along = tmp[1]
        if (along < 0.5) return null
        val s = params.fz / (params.fz + along)
        out.X = cam.cx + lat * cam.ppm * s
        out.Y = cam.hz + (params.eye - y) * cam.ppm * s
        out.s = s
        out.along = along
        return out
    }

    // ─────────────────────── 两连杆 IK（§3.5） ───────────────────────

    /**
     * 解膝点（原型 `ikKnee`）。
     *
     * ⭐ **第 7~9 个参数是膝的目标点**（R11），⛔ 不是旧版的"外法向提示向量"。
     * 目标点决定"朝哪一侧弯"：把目标放在髋**上方且偏外**，膝就抬到髋之上、落在脚的外侧
     * ⇒ 前视读成马蹄拱形。段长恒为 L1/L2（定长两连杆），目标点只给方向、
     * ⛔ 不参与长度 —— 所以调 kneeUp/kneeOut 不会把腿拉长，只会改弯曲方向。
     * 目标点退化（正落在髋→脚轴上）时回落到"水平外法向"，保证 pl≠0。
     */
    fun ikKnee(
        hx: Double, hy: Double, hzz: Double,
        fx: Double, fy: Double, fzz: Double,
        tx: Double, ty: Double, tz: Double,
        params: TripodParams,
    ): BodyPt {
        val vx = fx - hx
        val vy = fy - hy
        val vz = fzz - hzz
        val d = max(1e-6, sqrt(vx * vx + vy * vy + vz * vz))
        val ux = vx / d
        val uy = vy / d
        val uz = vz / d
        val a = (d * d + params.l1 * params.l1 - params.l2 * params.l2) / (2 * d)
        val h = sqrt(max(0.0, params.l1 * params.l1 - a * a)) // D > L1+L2 时 h→0，腿自然伸直
        val jx = hx + ux * a
        val jy = hy + uy * a
        val jz = hzz + uz * a // 未弯曲时的膝（轴上投影点）
        var px = tx - jx
        var py = ty - jy
        var pz = tz - jz
        val dot = px * ux + py * uy + pz * uz // 剔掉轴向分量 ⇒ 只留"弯向哪边"
        px -= ux * dot
        py -= uy * dot
        pz -= uz * dot
        var pl = sqrt(px * px + py * py + pz * pz)
        if (pl < 1e-4) {
            px = -uz
            py = 0.0
            pz = ux
            pl = sqrt(px * px + py * py + pz * pz).let { if (it != 0.0) it else 1.0 }
        }
        return BodyPt(jx + px / pl * h, jy + py / pl * h, jz + pz / pl * h)
    }

    // ─────────────────────── 「带 + 两端窗口」的公共结构 ───────────────────────

    /**
     * ⭐ 两端都收敛到 0 的窗口 ⇒ 回收 pop 藏进雾里（§七 R4 / §4.1）。
     *
     * **`bandWindow` 与 `crowdWindow` 共用这一份**（⛔ 两套回收数学）：
     * 建筑带传 `fin = span·0.25` / `fout = span·0.28`（每一栋**自己的** `{zMin, span}`，
     * ⛔ 传整带的 BAND.x —— 整带共用跨度正是"天际线每 90 s 整块重放"的根源）；
     * 人群带传 `CBAND.fin` / `CBAND.fout`（35 / 110 m）。
     */
    fun windowAlpha(z: Double, zMin: Double, span: Double, fin: Double, fout: Double): Double {
        val hi = zMin + span
        return smoothstep(zMin, zMin + fin, z) * (1 - smoothstep(hi - fout, hi, z))
    }

    /** 建筑带窗口（原型 `bandWindow`，fin/fout 按 span 取比例）。 */
    fun bandWindow(z: Double, zMin: Double, span: Double): Double =
        windowAlpha(z, zMin, span, span * 0.25, span * 0.28)

    /** 人群带窗口（原型 `crowdWindow`）。 */
    fun crowdWindow(world: TripodFogWorld, z: Double): Double {
        val c = world.crowdBand
        return windowAlpha(z, c.zMin, c.span, c.fin, c.fout)
    }

    // ─────────────────────── 过零扫描（scanCross） ───────────────────────

    /**
     * `d` 的过零时刻（原型 `scanCross` + `TCROSS` 的原地写回）。
     *
     * ⚠️ 判据必须是**变号**，⛔ 不是"从负到正"：穿越①是 +→−（d 由正转负），只找上升沿会漏掉它，
     * 于是 t1/t2 全部错位（这条 bug 在 R17 第一版里真实发生过，量出"掠过 9.87 m@78.18"= 穿越②）。
     * ⭐ R29：**零穿越的剧本（V1/V2）合法**，t1 退回"全局最近位"，t2 = t1。
     */
    fun scanCrossInto(world: TripodFogWorld) {
        val tc = world.tcross
        val z = DoubleArray(64)
        var zn = 0
        var prev = world.dh.eval(0.0)
        var t = 0.01
        while (t <= world.params.loop) {
            val v = world.dh.eval(t)
            if ((prev < 0.0) != (v < 0.0)) {
                if (zn < z.size) {
                    z[zn] = t - 0.01
                    zn++
                }
            }
            prev = v
            t += 0.01
        }
        tc.n = zn
        tc.cross = zn >= 2
        if (tc.cross) {
            tc.t1 = z[0]
            tc.t2 = z[1]
            return
        }
        var best = Double.POSITIVE_INFINITY
        var bt = 0.0
        t = 0.0
        while (t <= world.params.loop) {
            val v = abs(world.dh.eval(t))
            if (v < best) {
                best = v
                bt = t
            }
            t += 0.01
        }
        tc.t1 = bt
        tc.t2 = bt
    }

    // ─────────────────────── 剧本轮转（R29） ───────────────────────

    /**
     * ⭐ R29 剧本选择（原型 `variantOf`）：`routeVar ≥ 0` = 手动钉住一档，−1 = **按纪元轮转** `ep mod N`。
     * 轮转而不是随机抽，三条理由：① 保证三种戏都出现（独立均匀抽可以连抽十圈同一种）；
     * ② 相邻纪元必然不同（mod 的相邻整数不同余）⇒ 不会出现"换了种子还是同一条路"；
     * ③ 可复现：圈数 → 剧本是纯函数，⛔ 引入第二个随机源（随机已经归 RA/RP 管）。
     */
    fun variantOf(ep: Int, params: TripodParams): Int {
        val n = TRIPOD_VARS.size
        return if (params.routeVar >= 0) mod(params.routeVar.toDouble(), n.toDouble()).toInt()
        else mod(ep.toDouble(), n.toDouble()).toInt()
    }

    /** `setYawAmp(k)`（原型同名）：倍率（YH_KY 已含 R17 的全部幅度，k=1 即定稿）。⚠️ 读**当前剧本**那一份。 */
    fun setYawAmp(world: TripodFogWorld, k: Double) {
        val scaled = DoubleArray(world.yhKy.size) { world.yhKy[it] * k }
        world.yh.set(TRIPOD_YH_KX, scaled)
        world.params.yawAmp = k
    }

    /** R18 抓取曲线的节点（相对 grabT 排，⛔ 写死秒数）。 */
    fun grabKx(g: Double): DoubleArray =
        doubleArrayOf(0.0, g - 3.5, g - 1.5, g, g + 1.5, g + 3.0, g + 4.5, 90.0)

    /** 原地重建两条抓取曲线（原型 `setGrabKnots`；⛔ 换新对象：`__E46` 与 T19 拿的是同一份引用）。 */
    fun setGrabKnots(world: TripodFogWorld) {
        val kx = grabKx(clamp(world.params.grabT, 4.0, world.params.loop - 5.0))
        world.gren.set(kx, TRIPOD_GR_R)
        world.grlift.set(kx, TRIPOD_GR_L)
    }

    /**
     * ⭐ R29 换戏 = **所有派生层一起重建**（原型 `applyVariant`），顺序是硬约束（⛔ 随意重排）：
     * ① 四张表 + PHASES + 熄灯窗 + grabT —— 纯赋值；
     * ② DH / YH 原地重建（`setYawAmp` 读的是刚换上的 YH_KY —— ⛔ 还读 V0 那张表就等于换了戏没换镜头）；
     * ③ scanCross —— TCROSS 原地改字段，GW / 熄灯窗 / 抓取收尾全从它派生；
     * ④ setGrabKnots —— 动作节点相对 grabT；
     * ⑤ buildCum —— CHG/ESCG 要 gAt、GAIM/TAIM 要新 DH、GW 要新 TCROSS（**K4**）；
     * ⑥ 作废人群表 —— 受害人锚点是 gAt(grabT)/DH/routeMX 的函数，换戏就换了人质站的位置。
     *
     * ⚠️ `setParam` 写 P 的**同时回填滑杆**：grabT 是有滑杆的量，剧本改了 P 而滑杆停在上一个剧本的
     * 读数上 = 两个事实源（R28 的 markup 反向覆盖就是同一类坑，见闸门 T26）。
     */
    fun applyVariant(world: TripodFogWorld, vi0: Int, force: Boolean) {
        val vi = mod(vi0.toDouble(), TRIPOD_VARS.size.toDouble()).toInt()
        if (!force && vi == world.curVar) return
        world.curVar = vi
        val v = TRIPOD_VARS[vi]
        world.dhKy = v.dh
        world.yhKy = v.yh
        world.chTy = v.ch
        world.escTy = v.esc
        world.phases = v.phases
        world.dh.set(TRIPOD_DH_KX, world.dhKy)
        setYawAmp(world, world.params.yawAmp)
        scanCrossInto(world)
        world.params.fireT0 = v.fireT0
        world.params.fireT1 = v.fireT1
        world.params.grabT = v.grabT
        setGrabKnots(world)
        // ⑤ 步态积分重建（K4）：CHG/ESCG 要 gAt、GAIM/TAIM 要新 DH、GW 要新 TCROSS
        TripodGait.buildCum(world)
        world.crowdT = null
        world.crowdKey = ""
        world.varSwitches++
        world.onVariant?.invoke()
    }

    /** 圈数 → 路线纪元：每 routeLaps 圈换一次（戏 + 走位）。force = 滑杆改种子/间隔/剧本时立即重抽。 */
    fun syncRouteEpoch(world: TripodFogWorld, force: Boolean) {
        val ep = kotlin.math.floor(world.lapN.toDouble() / max(1, world.params.routeLaps)).toInt()
        if (!force && ep == world.routeEpoch) return
        world.routeEpoch = ep
        // ⛔ 先换戏再挑路线：scanRoute 读的是 escEnv/DH/YH
        applyVariant(world, variantOf(ep, world.params), force)
        applyRoute(world, world.params.routeSeed0 + ep * 101)
    }

    // ─────────────────────── 路线体检与种子导出（R16/R17） ───────────────────────

    /**
     * R16/R17 的路线体检（原型 `scanRoute`）：拒绝采样的判据 = T12 的判据（同一份，⛔ 两处各写一套会分叉）。
     *
     * ⭐ R17 三处改口径，每处都是"把判据限定在它真正描述的对象上"，⛔ 不是放宽：
     * ① **航向只在随机游走段判**（escEnv < 0.5）—— 编舞段机器要绕到相机背后，ψ 在那里
     *    本来就是设计意图的一部分，判它"侧身走"没有意义；
     * ② **wMax 只统计"看得见"的帧**（contrastAt ≥ 5% 且入画）—— R16 用的是 G 域窗口
     *    `GW ± routeVis·GTOT`，R17 之后 d 一圈穿越 0 两次、dist 与 G 不再单调对应，
     *    那个窗口既框不住"看得见"也框不住"看得见之外"，改成逐帧投影判定（与 T7/T18 同口径）；
     * ③ **nVis ≥ 50 反空判**：一条把机器全程藏在浓雾里的路线会让 wMax=0 而"通过"，那不是好路线。
     */
    fun scanRoute(world: TripodFogWorld): RouteCheck {
        val p = world.params
        val cam = world.cam
        val d2r = Math.PI / 180
        val r = RouteCheck()
        var t = 0.0
        while (t <= p.loop) {
            val g = world.gAt(t)
            val m = world.routeMX(g)
            val d = world.dh.eval(t)
            r.maxMX = max(r.maxMX, abs(m))
            val esc = world.escEnv(g)
            val skip = esc >= 0.5 ||
                contrastAt(kotlin.math.hypot(m, d), 0.0, p, world.palette) < 0.05
            if (!skip) {
                val a = world.yh.eval(t) * d2r
                val along = d * cos(a) + m * sin(a)
                val lat = m * cos(a) - d * sin(a)
                val visible = along > 0.5 && abs(lat * cam.ppm * (p.fz / (p.fz + along))) <= cam.w * 0.5
                if (visible) {
                    r.nVis++
                    val deg = world.routePSI(g) * 180 / Math.PI
                    r.wMax = max(r.wMax, abs(deg))
                    r.maxPSI = max(r.maxPSI, deg)
                    r.minPSI = min(r.minPSI, deg)
                }
            }
            t += 0.1
        }
        r.mxAt = world.wanderRaw(world.gaim)
        r.psiAt = world.wanderRawPSI(world.gaim) * 180 / Math.PI
        r.ok = r.maxMX >= 20 && r.maxPSI > 8 && r.minPSI < -8 && r.wMax <= 55 && r.nVis >= 50 &&
            abs(r.mxAt) <= 15 && abs(r.psiAt) <= 8
        return r
    }

    /**
     * 按种子抽一条路线（原型 `applyRoute`）；⛔ 拒绝采样：不满足 [scanRoute] 就重抽（最多 80 次）。
     * 幅值区间是**有意**的：基波 ≥ 28 m 保证"真拐出去"，高次 ≤ 14/8/5 m 保证转身不碎。
     * `routeReject=false` 时退化为"抽到什么用什么" = T12 的负样本。
     */
    fun applyRoute(world: TripodFogWorld, seed: Int) {
        val r = Lcg(seed * 7919 + 13)
        world.routeTries = 0
        var tries = 0
        while (tries < 80) {
            world.ra = doubleArrayOf(28 + r.next() * 20, 6 + r.next() * 8, 3 + r.next() * 5, 2 + r.next() * 3)
            world.rp = doubleArrayOf(r.next() * TAU, r.next() * TAU, r.next() * TAU, r.next() * TAU)
            world.routeTries = tries + 1
            if (!world.params.routeReject || scanRoute(world).ok) return
            tries++
        }
    }

    /** `GW` 反解（原型 `setWanderCenter`）：谷心 = 掠过瞬间 `gAt(TCROSS.t1)`（R17：⛔ 不再是"全局最小 d"）。 */
    fun setWanderCenter(world: TripodFogWorld) {
        world.gw = world.gAt(world.tcross.t1)
    }

    /** 圆周回绕的差（原型 `dWrap`）：结果 ∈ (−GTOT/2, GTOT/2]。 */
    fun dWrap(a: Double, b: Double, gtot: Double): Double =
        mod(a - b + gtot / 2, gtot) - gtot / 2

    // ─────────────────────── 熄灯包络（R17） ───────────────────────

    /**
     * `fireEnv(t) ∈ [0,1]`：1 = 两道热光正常，0 = 全熄（原型 `fireEnv`）。
     * ⚠️ 两条独立的熄灭理由，**都要**：
     * ① t 窗 `[fireT0, fireT1]`（覆盖冲刺到回正归位的全程）；
     * ② `dist < fireNear`（45 m）—— ⛔ 只靠时间窗，改了 DH 让机器提前贴近就会漏。
     * `fireEnv(0) = fireEnv(LOOP) = 1` 且两端平地 ⇒ beamI 进 T4 指纹照样闭合。
     */
    fun fireEnv(params: TripodParams, t: Double, dist: Double? = null): Double {
        val off = smoothstep(params.fireT0, params.fireT0 + TRIPOD_FIRE_RAMP, t) *
            (1 - smoothstep(params.fireT1 - TRIPOD_FIRE_RAMP, params.fireT1, t))
        var f = 1 - off
        if (dist != null && dist < params.fireNear) {
            f *= smoothstep(params.fireNear * 0.55, params.fireNear, dist)
        }
        return f
    }

    // ─────────────────────── 音频包络 ───────────────────────

    /** 三带音频包络（原型 `audioEnv`）。⛔ 在 sceneState 与 aimState 里各写一份（beamI 吃 treble）。 */
    fun audioEnv(params: TripodParams, t: Double): TripodAudioEnv =
        if (params.audioBreathe) {
            TripodAudioEnv(
                bass = 0.5 + 0.35 * sin(TAU * mod(t, 6.0) / 6),
                treble = 0.5 + 0.3 * sin(TAU * mod(t, 6.0) / 6 + 1.1),
                energy = 0.5 + 0.3 * sin(TAU * mod(t, 6.0) / 6 + 2.2),
            )
        } else {
            TripodAudioEnv(params.audioBass, params.audioTreble, params.audioEnergy)
        }

    // ─────────────────────── 落地冲击（R22） ───────────────────────

    /** 换相以来的进度（原型 `impProgress`）：0 = 触地那一瞬，1 = 冲击窗结束（≥1 ⇒ 本帧无冲击）。 */
    fun impProgress(u: Double): Double = mod(u - ImpactGeom.PHASE, 1.0) / ImpactGeom.WIN

    /**
     * 快起慢落的冲击包络（原型 `footImp`），**两端恒为 0**。
     * ⛔ 用阶跃起波：那会在换相那一帧留一个不连续点，而 t=0 恰好落在腿 2 的触地上
     * （u=1/3）⇒ 循环接缝会被这个跳变咬到。
     */
    fun footImp(u: Double): Double {
        val w = impProgress(u)
        if (!(w >= 0.0 && w < 1.0)) return 0.0
        return clamp((1 - w).pow(1.5) * min(1.0, w / ImpactGeom.ATTACK), 0.0, 1.0)
    }

    // ─────────────────────── 步频冲击（R26） ───────────────────────

    /**
     * R26 步频驱动镜头（原型 sceneState 里的内联段，K3 收成纯函数）。
     *
     * 冲击源**直接取 R22 那份 `imp`**（同一事件、同一时点、同一条包络，⛔ 另起一条时间轴）。
     * 乘 cos 把"单峰下陷"变成"下沉→回抽→归零"：窗的两端 imp 恒为 0 ⇒ 起落两点都回零，
     * **不新增圆周量、不改 T4 指纹**（shakeX/Y 与 imp 本来就在指纹里）。
     * ⚠️ `joltK` 吃**真距离** dist，⛔ `st.d`（R17 之后 d 会是负的，负数进 smoothstep
     * 等于"身后=最远处"⇒ 贴身时反而不晃）。
     */
    fun joltOf(legU: DoubleArray, legImp: DoubleArray, dist: Double, params: TripodParams): DoubleArray {
        val joltK = 1 - smoothstep(JoltGeom.D_IN, JoltGeom.D_OUT, dist)
        val joltEnv = params.joltA * joltK
        var jolt = 0.0
        var k = 0
        while (k < JoltGeom.LEG_K) {
            val w = impProgress(legU[k])
            if (w >= 0.0 && w < 1.0) jolt += legImp[k] * cos(TAU * JoltGeom.CYC * w)
            k++
        }
        val joltY = jolt * joltEnv
        return doubleArrayOf(joltY * JoltGeom.X_K, joltY, joltK)
    }

    // ─────────────────────── 人群纪元（R21） ───────────────────────

    /**
     * 坑位 i 在第 w 次回收时**是谁**的轮次（原型 crowdAt 里的 `w` 式子，K3 收成纯函数）。
     *
     * ⚠️ `w` 由本式给出，换人的**时刻**就是该坑位 mod 归零、z 从带尾跳带头的那一刻
     * ⇒ 两侧渲染 α 都贴 0 ⇒ 隐形。⛔ 让 w 在任何别的时刻变化（那等于让一个正在画面中段的人瞬移，
     * 闸门 T22(a) 量的就是它）。⛔ 把轮次混进 z0（见 [CrowdEpoch.Z_SEED_K]）。
     */
    fun crowdEpochOf(raw: Double, span: Double, lapN: Int): Int =
        kotlin.math.floor(raw / span).toInt() + CrowdEpoch.LAP_K * lapN

    // ─────────────────────── 机身几何 ───────────────────────

    /** 顶罩中心（原型 `cowlY`）：仰角公式、T7 与 §3.1 尺度表的**唯一**口径，⛔ 两处各写魔数。 */
    fun cowlY(params: TripodParams): Double = params.bodyY + 0.3 + params.bodyRy * 0.72

    /** 名义机顶高度（原型 `topY`；T10 的"着地膝顶 ≤ 机顶 + 1 m"拿它当基准）。 */
    fun topY(params: TripodParams): Double = cowlY(params) + params.cowlRy

    /**
     * 机身组局部 (lat, fwd) → 相机系（原型 `bodyFrame`）：绕机心转 hy（= 航向 ψ + 机头微摆），
     * 再平移到 (mx, d)。
     *
     * ⛔ 腿的**着地脚**不走这里的当前帧 —— 它要用着地那一刻的 (ψ, mx, d) 求值，
     * 否则机身一转身脚就跟着画弧 = 滑步（判读轮 3 意见 3 加路线后 T2 升级为二维口径的根因）。
     */
    fun bodyFrame(lat: Double, fwd: Double, y: Double, hy: Double, d: Double, mx: Double): BodyPt {
        val c = cos(hy)
        val s = sin(hy)
        return BodyPt((mx) + lat * c + fwd * s, y, d - (fwd * c - lat * s))
    }

    // ─────────────────────── 缆须与抓取（R18） ───────────────────────

    /**
     * 缆须梢点的世界系位置上下文（原型 `tendrilRoot(st, u)` / `tendrilTip(st, u)` 的入参打包）。
     * ⭐ `victimX/Y/Z` 是 `st.crowd[0]`（受害人）的世界系坐标 —— 与 `crowdAt` 导出的**同一份**，
     * ⛔ 调用方另算一套（T19(b) 判的就是"画面里的缆须够不够得着"）。
     */
    internal class TendrilCtx(
        val bodyLat: Double,
        val leanFwd: Double,
        val bodyY: Double,
        val psi: Double,
        val d: Double,
        val mx: Double,
        val bodyRx: Double,
        val personH: Double,
        val tendReach: Double,
        val victimX: Double,
        val victimY: Double,
        val victimZ: Double,
        /** 抓取 reach（0 = 悬垂）。 */
        val reach: Double,
    )

    /**
     * 缆须**根部**（原型 `tendrilRoot`）。
     * `u` = 根点在腹下的横向偏移（±1 ≈ 半个甲壳半径），⛔ 两根缆须共用一个根（那是一条线）。
     */
    fun tendrilRoot(ctx: TendrilCtx, u: Double): BodyPt =
        bodyFrame(
            ctx.bodyLat + u * ctx.bodyRx * 0.45,
            u * 1.2 + 1.0 + ctx.leanFwd,
            ctx.bodyY - 1.6,
            ctx.psi,
            ctx.d,
            ctx.mx,
        )

    /**
     * 缆须**梢点**（原型 `tendrilTip`）：沿"根→目标"方向最多伸 `reach·tendReach` 米
     * （⛔ 一步插到目标 —— 那样人再远也贴得上去，T19(b) 恒绿）。
     *
     * 与 `drawBody` 里画的那条曲线**同一个公式**，T19(b) 判的就是它够不够得着：
     * `grabGap = D − L`（还差几米）是真实测量量，⛔ 恒 0。
     */
    fun tendrilTip(ctx: TendrilCtx, u: Double): TendrilTip {
        val root = tendrilRoot(ctx, u)
        val dx = ctx.victimX - root.x
        val dy = ctx.victimY + ctx.personH * 0.75 - root.y
        val dz = ctx.victimZ - root.z
        val d = kotlin.math.hypot(kotlin.math.hypot(dx, dy), dz).let { if (it != 0.0) it else 1e-6 }
        val l = min(d, ctx.tendReach * ctx.reach)
        return TendrilTip(
            x = root.x + dx / d * l,
            y = root.y + dy / d * l,
            z = root.z + dz / d * l,
            root = root,
            D = d,
            L = l,
        )
    }

    // ─────────────────────── 光链（R27） ───────────────────────

    /**
     * ⭐ R27「光链」（原型 `aimState`）：机身姿态 → 腺囊 / 两道光的出处与落点 / 光束亮度，
     * **t 的纯函数**（火星按出生时刻回溯落点时喂进过去的 tb，⛔ 在 drawParticles 里复制一份）。
     *
     * 走路"活着"的三条线索全部是**步相 s 的周期函数** ⇒ 闭环自动成立（buildCum 已把
     * 总步数钉成 3 的倍数，所以 /3 那条也闭）。⛔ 按时间驱动（那会和滑步判据打架）。
     */
    fun aimState(world: TripodFogWorld, t: Double, en: TripodAudioEnv? = null): AimState {
        val p = world.params
        val e = en ?: audioEnv(p, t)
        val d = world.dh.eval(t)
        val g = world.gAt(t)
        val s = g / p.stride
        val bodyY = p.bodyY + p.bobA * cos(TAU * s)
        val roll = p.rollA * sin(TAU * s + 0.6) * Math.PI / 180
        // 横向重心转移：机身整体侧移，脚仍钉在地上 ⇒ IK 自动把支撑腿绷成斜撑
        // （观感上"迈过去"九成来自这一条，⛔ 只留起伏就是原地颠簸）。
        val bodyLat = p.swayA * sin(TAU * s + 0.9)
        val leanFwd = p.pitchA * sin(TAU * s + 2.1)
        // ⭐ R14：主项 = 航向滞后（滞后**距离**⛔ 时间 ⇒ 站定时头不甩）；残差 R28 归零。
        val dPsiLag = world.routePSI(g) - world.routePSI(g - p.headLagArm)
        val lagDeg = clamp(dPsiLag * 180 / Math.PI, -p.headLagCap, p.headLagCap)
        val headYaw = (-p.headLagK * lagDeg + p.headYawA * sin(TAU * s / 3 + 1.2)) * Math.PI / 180
        val mx = world.routeMX(g)
        val psi = world.routePSI(g)
        val dist = kotlin.math.hypot(mx, d) // ⚠️ 雾/明暗/对比度一律用真距离
        val fire = fireEnv(p, t, dist)
        val hitFwd = clamp(d * 0.55, 8.0, 90.0) // §六：俯角恒 > 12° 的来源
        val sw = p.beamAmp * sin(TAU * mod(t, p.beamPeriod) / p.beamPeriod)
        val gy = bodyY - p.headDown
        val hy = psi + headYaw
        val fwd0 = p.headFwd + leanFwd
        // 腺囊 = 两道光的**共同出处**，与 drawBody 走同一个 bodyFrame ⇒ 光跟头一起摆，
        // ⛔ 第一版"头不动、光在扫"的分离感（喷口是硬编码的 emZ = d − 6.5）。
        val gland = bodyFrame(0.0, fwd0, gy, hy, d, mx)
        // ⚠️ 两道光必须**在地面上就分得开**（⛔ 一道粗光）：beamGap 0.6 m 的旧值在最近位
        //    两落点相距 1.5 m ≈ 26 px 而半宽已有 38 px ⇒ 读成一道探照灯（闸门 T9）。
        // ⚠️ 落点也走 bodyFrame ⇒ 机器转身时两道光**跟着扫向别处**，⛔ 身子朝东、光还在照我们。
        val beams = Array(2) { j ->
            val jj = if (j == 0) -1.0 else 1.0
            val em = bodyFrame(jj * p.beamGap / 2, fwd0, gy, hy, d, mx)
            val ht = bodyFrame(sw + jj * p.beamGap * (1 + hitFwd * p.beamDiv), hitFwd, 0.0, hy, d, mx)
            AimBeam(
                ex = em.x, ey = em.y, ez = em.z, hx = ht.x, hz = ht.z,
                depression = atan2(gy, hitFwd) * 180 / Math.PI,
            )
        }
        return AimState(
            t = t, d = d, dist = dist, g = g, s = s, mx = mx, psi = psi,
            bodyY = bodyY, roll = roll, bodyLat = bodyLat, leanFwd = leanFwd,
            headYaw = headYaw, dPsiLag = dPsiLag,
            gland = gland, beams = beams, hitFwd = hitFwd, beamSw = sw,
            fire = fire,
            beamI = p.beamI * fire * lerp(0.85, 1.15, e.treble),
            beamSwV = p.beamAmp * cos(TAU * mod(t, p.beamPeriod) / p.beamPeriod) * TAU / p.beamPeriod,
            beamRaw = p.beamI * lerp(0.85, 1.15, e.treble),
        )
    }
}

/** R18 抓取曲线的拒绝采样体检输出（原型 `scanRoute` 的 `m`）。 */
internal class RouteCheck {
    var maxMX: Double = 0.0
    var maxPSI: Double = -1e9
    var minPSI: Double = 1e9
    var wMax: Double = 0.0
    var nVis: Int = 0
    var mxAt: Double = 0.0
    var psiAt: Double = 0.0
    var ok: Boolean = false
}

/** [TripodFogMath.tendrilTip] 的输出（含够得着的钳位量）。 */
internal class TendrilTip(
    val x: Double,
    val y: Double,
    val z: Double,
    val root: BodyPt,
    /** 根 → 目标的真实距离（m）。 */
    val D: Double,
    /** 实际伸出长度 = `min(D, tendReach·reach)`（m）。 */
    val L: Double,
)

// ══════════════════════════════════════════════════════════════════════════════
//  场景状态的数据形状（由 K4 `TripodGait.sceneState` 填充）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 建筑实例（原型 `genBuildings` 的 `b`）。
 *
 * 几何由 **K5 `genBuildings`** 用 `lcg(20261011)` 抽定；`skirtFrac` / `skirtA` / `featherW` /
 * `featherTop` 由 **K5 `bakeBuilding`** 回填（⛔ 在 K4/K6 里另算一套 —— 那是 E44 D24
 * 「表与几何各说各话」的同一条错）。
 */
internal class TripodBuilding {
    var i: Int = 0

    /** 所在带的实例表（借用索引用；原型 `pool: arr`）。 */
    var pool: Array<TripodBuilding> = emptyArray()

    /** 世界系横向锚点。 */
    var x: Double = 0.0

    /** 回收基准深度。 */
    var z0: Double = 0.0
    var w: Double = 0.0
    var h: Double = 0.0

    /** 纵深厚度（投影压扁用）。 */
    var dep: Double = 0.0
    var seed: Int = 0

    /**
     * 回收次数 k（跨度 = GTOT/k）。
     * ⭐ 同一条带里让每栋的周期**互相错开**，天际线就不会作为一整块每 90 s 重放一次
     * （旧写法 mid=GTOT / near=GTOT÷2 是最糟的组合 —— near 带那一栋会在同一圈里出现两次）。
     * k 取整数 ⇒ GTOT/span 恰为整数 ⇒ T4 的 wrap 位移照样逐栋重合。
     */
    var k: Int = 1

    // ── 以下由 K5 bakeBuilding 回填 ──
    var skirtFrac: Double = 0.0
    var skirtA: Double = 0.0
    var featherW: Double = 0.0
    var featherTop: Double = 0.0

    /**
     * 本栋的烘焙位图（原型 `b.bmp = bakeBuilding(b)`）。
     *
     * ⭐ K6 的建筑层经 `BldSel.src.bmp` 取它（⛔ 另建一张「下标 → 位图」的查找表 ——
     * 借形机制下同一个 `i` 在两带各烘一份，表要带带号，而 `src` 本来就指向正确的那一份）。
     */
    var bmp: TripodFogBake.BuildingBmp? = null
}

/** 一条腿的步态状态（原型 `legState` 的返回值 + `sceneState` 补的 `fx/fy/fz/imp`）。 */
internal class LegState {
    /** 腿的方位角（`LEG_AZ[i]`）。 */
    var th: Double = 0.0

    /** 摆动相（`u < 1/3`）。 */
    var swing: Boolean = false

    /** 换相以来的步相进度（0 = 换相点）。 */
    var u: Double = 0.0

    /** 沿腿方位角的纵向前伸量（机身系）。 */
    var q: Double = 0.0

    /** 抬腿高（摆动相之外恒 0）。 */
    var lift: Double = 0.0

    /** 机身系横向偏移（含外探）。 */
    var lat: Double = 0.0

    // ── 以下由 sceneState 钉死（世界系；站立相整个站立相恒定 ⇒ 零滑步） ──
    var fx: Double = 0.0
    var fy: Double = 0.0
    var fz: Double = 0.0

    /** R22 触地冲击强度（0 = 不在窗内）。 */
    var imp: Double = 0.0
}

/** 当帧入选的一栋楼（原型 `sceneState` 的 `bld` 元素；绘制序 = 缓冲内的插入序，⛔ 再排序）。 */
internal class BldSel {
    /** 借来的形状在池里的下标（T13 的 c 项判"接缝两侧借同一个"）。 */
    var id: Int = 0
    var x: Double = 0.0

    /** 当前深度（相机系）。 */
    var z: Double = 0.0
    var w: Double = 0.0
    var h: Double = 0.0
    var dep: Double = 0.0

    /** 0 = 中景 / 1 = 近景。 */
    var band: Int = 0

    /** 回收窗口 α（两端收敛到 0 ⇒ 雾里换形）。 */
    var a: Double = 0.0
    var ink: Double = 0.0

    /** 雾裙分数 / 强度（K5 烘进位图的那一份的记账，T20 读它）。 */
    var skirtFrac: Double = 0.0
    var skirtA: Double = 0.0

    /** 借来的形状本体（K6 经它取位图与羽化，⛔ 另建查找表）。 */
    var src: TripodBuilding? = null
}

/** 当帧的一个人的渲染状态（原型 `crowdAt` 的输出元素；⛔ 屏幕系）。 */
internal class CrowdAt {
    var i: Int = 0
    var victim: Boolean = false

    /** 第几次回收（T22 判"换人只发生在 α=0 的那一帧"读它）。 */
    var w: Int = 0
    var x: Double = 0.0
    var y: Double = 0.0
    var z: Double = 0.0

    /** 真距离 = hypot(x, z)（雾化用）。 */
    var dist: Double = 0.0

    /** 姿态（0..3）。 */
    var kind: Int = 0

    /** 渲染 α。 */
    var a: Double = 0.0
}

/** 抓取时间轴状态（原型 `sceneState` 的 `grab`）。 */
internal class GrabState(var reach: Double = 0.0, var lift: Double = 0.0)

/**
 * 髋 / 膝 / 脚的**世界系**坐标（原型 `tripodPose` 的返回值）。
 *
 * ⚠️ 单独成函数是为了让 T2/T10 和渲染器读**同一份**几何 —— 判据自算一套、画面另一套
 * 是本项目反复踩到的坑（E44 D24 同源）。加顶罩/改 RH 时不需要动闸门。
 */
internal class TripodPose(
    val hips: Array<BodyPt>,
    val knees: Array<BodyPt>,
    val feet: Array<BodyPt>,
)

/**
 * 每帧场景状态（原型 `sceneState` 的返回值；aimState 的字段**整份平铺**进来）。
 *
 * ⭐ R27：⛔ 逐字段再列一遍 —— 漏一个字段就是 T4 指纹少一项，而画面上完全看不出少了什么。
 *
 * ## ⚠️ 跨帧持有纪律（R12 预分配缓冲的代价）
 * `legs` / `bldMid` / `bldNear` / `crowd` 是 [TripodFogWorld] 上的**预分配缓冲**，
 * 帧首被覆写 ⇒ ⛔ 持有上一帧的 scene 再读这几个字段（读到的是当前帧）。
 * 闸门一律**先摘抄**（原型闸门本来就摘进数组，`fingerprint` 即摘抄器）。
 */
internal class TripodScene(
    // ── aimState 平铺（R27） ──
    val t: Double,
    val d: Double,
    val dist: Double,
    val g: Double,
    val s: Double,
    val mx: Double,
    val psi: Double,
    val bodyY: Double,
    val roll: Double,
    val bodyLat: Double,
    val leanFwd: Double,
    val headYaw: Double,
    val dPsiLag: Double,
    val gland: BodyPt,
    val beams: Array<AimBeam>,
    val hitFwd: Double,
    val beamSw: Double,
    val fire: Double,
    val beamI: Double,
    val beamSwV: Double,
    val beamRaw: Double,
    // ── 相机 / 行程 ──
    /** 偏航（弧度）。⚠️ 圆周量：fingerprint 里必须走 `circ(yaw/TAU)`（R17 起扫满 360°）。 */
    val yaw: Double,

    /** 相机行程 = `−(G + (d − DH.ky[0]))`（§3.3 的导出量）。 */
    val c: Double,
    val vWalk: Double,
    val camSpeed: Double,
    val tilt: Double,
    // ── 步态 / 建筑 / 人群（缓冲引用，见类 KDoc 的跨帧持有纪律） ──
    val legs: Array<LegState>,
    val bldMid: Array<BldSel>,
    val bldMidN: Int,
    val bldNear: Array<BldSel>,
    val bldNearN: Int,
    /** 距离雾尺度（`energy → 雾密度 ±12%` 的落点）。 */
    val d0: Double,
    val crowd: Array<CrowdAt>,
    val crowdN: Int,
    val grab: GrabState,
    // ── 镜头（R26：恒幅微抖 + 每记落地的回弹，合成后仍是一处 translate） ──
    val shakeX: Double,
    val shakeY: Double,
    val joltX: Double,
    val joltY: Double,

    /** 距离包络（闸门读这份，⛔ 重推）。 */
    val joltK: Double,
    val fogPhA: Double,
    val fogPhB: Double,
    val breathPh: Double,

    /** 落点亮斑半径（`3.6·lerp(0.92, 1.08, bass)`，R23）。 */
    val poolR: Double,
)
