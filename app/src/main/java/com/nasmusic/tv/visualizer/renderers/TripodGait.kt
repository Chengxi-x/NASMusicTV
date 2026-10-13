package com.nasmusic.tv.visualizer.renderers

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * E46「迷雾之战 · 雾中三脚巨影」—— **K4 步态 / 场景装配层**
 * （`docs/archive/e46-tripod-fog-plan.md` §十五 K4；原型 `docs/archive/e46-tripod-fog-preview.html`）。
 *
 * ## 本文件放什么（§十五 K4 的逐项落点 + 原型模块函数的其余部分）
 * - [buildCum]：**整数步 + 3 的倍数**的步态积分（N=3600 梯形表 → `CUM_T`/`Gscale`/`GTOT`），
 *   编舞 `CH_TX`/接管 `ESC_TX` 经 `gAt` 的 **t→G 映射**（R17）、`TAIM/GAIM` 开火段最近位扫描、
 *   三个带的 `span` 覆写、`GW` 反解。⭐ R29 后它吃的是**当前剧本**那张表 ⇒ 换戏必须重建
 *   （`applyVariant` 第 ⑤ 步调的正是本函数，见 [TripodFogMath.applyVariant]）。
 * - [legState]：`G` 距离驱动的三足步态（摆动相弧线前探 + 站立相线性后移）。
 * - [sceneState]：一帧场景的**总装配**（aimState 平铺 + 脚位钉地面系 + 建筑部分选择 +
 *   抓取/人群 + 镜头微抖与步频冲击 + `tilt`/`C` 导出量）。
 * - [tripodPose]：髋/膝/脚的**世界系**几何（T2/T10 与渲染器读同一份，⛔ 两套口径）。
 * - [crowdTable] / [crowdAt]：R18 抓取与人群（坑位表 + 当帧渲染状态）。
 * - [fingerprint]：T4 接缝指纹的**摘抄器**（178 字段 = 18 标量 + 6×circ + 3×6 + 2×5 + 4n）。
 *
 * ## ⭐ 为什么这些函数在本文件而不是 K3
 * K3 放的是**无状态数学与世界状态的属主**（`TripodFogWorld`）；本文件放的是**读世界状态、
 * 产出每帧场景**的行为。判据只有一条：会不会每帧被调用且要读写 `world`。
 * `crowdTable` 读 `gAt/DH/routeMX`（换戏即失效）、`crowdAt` 读 `crowdTable` 与 `lapN`、
 * `tripodPose` 读 `st.legs` —— 全是行为，⛔ 塞进 K3 会让"数学层"膨胀成第二个 god-object。
 *
 * ## ⚠️ R12 零分配纪律（`PerfBudgetContractTest` 红线，移植自原型的三处分配）
 * 原型在这里有三处每帧分配，Kotlin 侧全部换成**预分配缓冲**：
 * ① `legState` 返回新对象 → 填 [TripodFogWorld.legsBuf]（容量 3）；
 * ② `sceneState.push()` 建候选数组 + `sort` + 建 `bld` 对象 → **部分选择**写
 *    [TripodFogWorld.bldSelMid] / [TripodFogWorld.bldSelNear]（容量 48，⛔ 全排序）；
 * ③ `crowdAt` 建 `out` 数组 → 填 [TripodFogWorld.crowdBuf]（容量 32）。
 * 本文件内的私有暂存（`wrapHit`/`ident`/`legU`/`legImp`/`keyBuf`）同理帧内复用。
 * ⚠️ 每帧仍会分配的各只有一份：`TripodScene` / `AimState`（K3）/ `GrabState` /
 * `tripodPose` 的三个数组 —— 与原型同级，⛔ 再放大。
 *
 * ## ⚠️ K5/K6 的接线契约（本文件不管，但这里钉住顺序）
 * - **K5 `genBuildings` 必须在 `buildCum` 之后跑**：原型 `boot()` 的顺序是
 *   `layout → buildCum → setGrabKnots → syncRouteEpoch(true) → genBuildings → bakeAll`
 *   （`z0 = r() * GTOT` 读的是 buildCum 刚钉死的 GTOT，⛔ 反过来会让建筑跨度对不上）。
 * - **K6 画建筑要跨带合并**：本文件产出的是**两个各自按 z 升序**的缓冲（近 → 远），
 *   而原型的 `drawBuildings` 把两带拼起来后按 `z` 降序画（远 → 近，画家算法）。
 *   ⭐ mid 带 `z ∈ [105, 705]` 与 near 带 `z ∈ [42, 342]` **有重叠** ⇒
 *   ⛔ "先画完 mid 再画 near"（会在重叠区画错遮挡）；K6 用**双指针合并**把两个升序缓冲
 *   并成一个降序绘制序列（O(n)、零分配、⛔ sort）。
 * - `BldSel.src` 是借来的形状本体，K6 经它取 K5 烘的位图与雾裙/羽化记账（⛔ 另建查找表）。
 */
// ══════════════════════════════════════════════════════════════════════════════
//  TripodGait —— object（单例；所有可变暂存都是帧内复用的私有字段）
// ══════════════════════════════════════════════════════════════════════════════
internal object TripodGait {

    // ─────────────────────── 私有暂存（帧内复用，⛔ 每帧新建） ───────────────────────

    /**
     * 三条腿的方位角（原型 `LEG_AZ = [π, τ/6, 5τ/6]`）。
     * ⭐ 不是 `[0°, 120°, 240°]`：th=0 那条腿正对相机（lat=0）会挂在机身和两道光中间，
     * 压迫位读成「一根棍子插着头」（原型 :855 的判读注释）。
     * ⚠️ `internal` 只为 **T10 负样本 D**（把方位角改回第一版的 `[180°,120°,240°]` ⇒ 三腿全拖在
     * 身后）；生产路径恒用这份常量，⛔ 上滑杆、⛔ 在别处改。
     */
    internal val LEG_AZ = doubleArrayOf(PI, TripodFogMath.TAU / 6, 5 * TripodFogMath.TAU / 6)

    /** `buildCum` 的梯形积分格数（原型 `const N = 3600`）。 */
    private const val CUM_N = 3600

    /** TAIM 扫描的 t 域步长（原型 `t += 0.05`）。 */
    private const val TAIM_STEP = 0.05

    /** `instWrap` 的输出暂存（原型每栋建一个新对象；R12 改复用）。 */
    private class WrapHit {
        var z: Double = 0.0

        /** 回收次数（`floor(u)`；T13 读它，sceneState 不用）。 */
        var k: Int = 0

        /** ⭐ 这一趟**借来的**形状（同带的另一栋；接缝两侧必须是同一个）。 */
        var s: TripodBuilding? = null

        /** 本栋自己的跨度 = `GTOT / b.k`（⛔ 传整带的 span）。 */
        var span: Double = 0.0
    }

    private val wrapHit = WrapHit()

    /**
     * `crowdIdent` 的输出暂存（R12：⛔ 每次现算新建对象）。
     * ⚠️ 必须是 `internal` 而不是 `private`：[crowdIdent] 是 internal 函数，
     *    参数类型不能是 private-in-class（否则编译不过）。
     */
    internal class CrowdIdent(var x: Double = 0.0, var kind: Int = 0)

    private val ident = CrowdIdent()

    /** [TripodFogMath.joltOf] 的入参暂存（它按数组收，⛔ 为它改 K3 的签名）。 */
    private val legU = DoubleArray(3)
    private val legImp = DoubleArray(3)

    /** `crowdTable` 的缓存键暂存（稳态零分配：只在真的重建时 `toString()`）。 */
    private val keyBuf = StringBuilder(96)

    // ══════════════════════════════════════════════════════════════════════════
    //  buildCum（R17/R29 后它吃当前剧本的表）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 原型 `buildCum()`：t 域步速表 → G 域行程，并把一切 **G 域的派生量**重建一遍。
     *
     * ⭐ 为什么必须是**整数步**、还必须是 **3 的整数倍**：
     * ① 机头微摆 `headYaw` 的周期是 3 步（三条腿轮一遍）—— 只保证「整数步」会让
     *    `sin(TAU·s/3)` 在 t=90 处差一个 3 步相位 ⇒ T4 接缝直接开；
     * ② 步相位 `s = G/STRIDE` 是循环量，`GTOT = steps·STRIDE` 才让 `s(LOOP)` 落回整数。
     * ⚠️ `steps` 取整方向差一格 = GTOT 差一个 STRIDE = 整条路线/建筑/人群全部错位 ⇒
     *    [jsRound] 用 `floor(x+0.5)` 逐位复刻 JS `Math.round`（ties → +∞）。
     *
     * ⚠️ `CHG/ESCG` ⛔ 缓存成常量：`STRIDE` 滑杆一动 `GTOT` 就变，而关键帧是 **t 域 authoring、
     *    G 域求值**（`gAt` 严格单调 ⇒ 映射后不会乱序）—— 必须在每次 buildCum 重建。
     */
    fun buildCum(world: TripodFogWorld) {
        val p = world.params
        val dt = p.loop / CUM_N
        val cum = DoubleArray(CUM_N + 1)
        var acc = 0.0
        var i = 1
        while (i <= CUM_N) {
            val t = i * dt
            acc += 0.5 * (TripodFogMath.pw(TRIPOD_VKX, TRIPOD_VKY, t - dt) +
                TripodFogMath.pw(TRIPOD_VKX, TRIPOD_VKY, t)) * dt
            cum[i] = acc
            i++
        }
        world.cumT = cum
        world.cumV = acc
        val steps = max(3.0, 3.0 * jsRound(acc / (p.stride * 3))).toInt()
        world.gscale = steps * p.stride / acc
        world.gtot = steps * p.stride
        world.bandMid.span = world.gtot // ΔC 恰为一个跨度
        world.bandNear.span = world.gtot / 2 // 恰为两个跨度
        world.crowdBand.span = world.gtot // R18 人群带：一圈一回收，与 mid 带同理
        world.chG = TripodFogMath.hermite(
            DoubleArray(TRIPOD_CH_TX.size) { world.gAt(TRIPOD_CH_TX[it]) },
            world.chTy,
        )
        world.escG = TripodFogMath.hermite(
            DoubleArray(TRIPOD_ESC_TX.size) { world.gAt(TRIPOD_ESC_TX[it]) },
            world.escTy,
        )
        // 开火段的最近位：fireEnv 是 t 的函数，所以先在 t 域扫，再映射到 G
        var tAim = 0.0
        var dAim = Double.POSITIVE_INFINITY
        var t = 0.0
        while (t <= p.loop) {
            if (TripodFogMath.fireEnv(p, t) >= 0.5) {
                val v = world.dh.eval(t)
                if (v < dAim) {
                    dAim = v
                    tAim = t
                }
            }
            t += TAIM_STEP
        }
        world.taim = tAim
        world.gaim = world.gAt(tAim)
        TripodFogMath.setWanderCenter(world) // GW 是 GTOT 的函数，STRIDE 滑杆一动就必须重算
    }

    /** JS `Math.round` 语义（ties → +∞）。⭐ 步数取整方向是接缝硬约束，⛔ 用 ties-to-even。 */
    private fun jsRound(x: Double): Double = floor(x + 0.5)

    // ══════════════════════════════════════════════════════════════════════════
    //  步态（legState）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 原型 `legState(i, s)`：一条腿在步相 `s` 下的**机身系**状态（R12：填 [out]，⛔ 返回新对象）。
     *
     * 三条腿彼此错开 1/3 步幅（`c = s − i/3`）；`u < 1/3` 为摆动相。
     * ⭐ 摆动相沿**腿自己的方位角**向外前探 `reach` 米（`q` 与 `lat` 同时动）⇒ 抬腿走弧线；
     *    ⛔ 只平移 `q` 不抬 `lat`：腿会在原地直上直下，读成雨刷而不是迈步。
     * ⚠️ `u = frac(c)` 由 `floor` 取（⛔ `%`：负 operand 会出负数）。
     */
    fun legState(world: TripodFogWorld, out: LegState, i: Int, s: Double) {
        val p = world.params
        val th = LEG_AZ[i]
        val ct = cos(th)
        val sth = sin(th)
        val u = frac(s - i / 3.0)
        val qA = p.rf * ct - p.stride / 3
        val qB = p.rf * ct + p.stride / 3
        var q: Double
        var lift = 0.0
        var reach = 0.0
        val swing: Boolean
        if (u < 1.0 / 3.0) {
            swing = true
            val w = u * 3
            lift = p.lift * sin(PI * w)
            reach = p.outSwing * sin(PI * w)
            q = TripodFogMath.lerp(qA, qB, TripodFogMath.easeInOut(w)) + reach * ct
        } else {
            swing = false
            q = qB - (u - 1.0 / 3.0) * p.stride
        }
        out.th = th
        out.swing = swing
        out.u = u
        out.q = q
        out.lift = lift
        out.lat = p.rf * sth + reach * sth
    }

    /** 小数部分（原型 `c - Math.floor(c)`；⛔ Kotlin `%` —— 负 operand 出负数）。 */
    private fun frac(c: Double): Double = c - floor(c)

    // ══════════════════════════════════════════════════════════════════════════
    //  sceneState（一帧场景的总装配）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 原型 `sceneState(t)`：把 K3 的各纯函数装配成**当帧场景**（aimState 整份平铺）。
     *
     * ⭐ 两条"钉住"是本函数的全部难点：
     * ① **摆动相**的脚在**机身系**里被甩出去（跟着当前航向 ψ 走）；
     *    **站立相**的脚钉在**地面系** —— 用**着地那一刻**的 `ψ(Gp)` 与间距 `d+(G−Gp)` 求值
     *    （`Gp = G − (u−1/3)·STRIDE`）⇒ 整个站立相 `(fx,fz)` 恒为常数 = 零滑步（T2 二维口径）。
     *    ⛔ 用当前 ψ 求值：机身一转身，钉住的脚就绕机心画弧。
     * ② **建筑**每栋自带回收周期（[wrapInto] 的 `span = GTOT/b.k`）再按当前深度取**最近 n 栋**
     *    （部分选择，⛔ 全排序）—— ⛔ 列表固定前缀：那是把「随机」接成了「截断」。
     *
     * ⚠️ `tilt` 的纵深用**相机系** `along = d·cos(yaw) + mx·sin(yaw)`，⛔ 用 `d`：
     *    机器绕到侧面 38 m 时 d=60 与真纵深差 20%（T7 的判据正是"应可见帧不出画"）。
     */
    fun sceneState(world: TripodFogWorld, t: Double): TripodScene {
        val p = world.params
        val en = TripodFogMath.audioEnv(p, t)
        val d0 = p.d0 * (1 + 0.24 * (en.energy - 0.5)) // energy → 雾密度 ±12%
        val a = TripodFogMath.aimState(world, t, en)
        val d = a.d
        val g = a.g
        val s = a.s
        val mx = a.mx
        val psi = a.psi
        val dist = a.dist
        val yaw = world.yh.eval(t) * PI / 180
        val c = -(g + (d - world.dh.ky[0])) // §3.3：相机行程是**导出量**
        val vWalk = world.gscale * TripodFogMath.pw(TRIPOD_VKX, TRIPOD_VKY, t)
        val camSpeed = -vWalk - world.dh.deriv(t)
        // ⭐ 手持微抖：整周期正弦叠加（37/61/29/53 个周期每 90 s），⛔ 随机数（循环接缝会开）。
        //    幅度 ∝ |相机速度|：站定时几乎不抖，P4 全速撤离时抖得最凶 = "在逃"的体感。
        val sh = p.shakeA * TripodFogMath.clamp(abs(camSpeed) / 14, 0.12, 1.0)
        val shakeX = sh * 3.4 * (sin(TripodFogMath.TAU * 37 * t / p.loop) +
            0.62 * sin(TripodFogMath.TAU * 61 * t / p.loop + 1.7))
        val shakeY = sh * 2.6 * (cos(TripodFogMath.TAU * 29 * t / p.loop + 0.4) +
            0.55 * sin(TripodFogMath.TAU * 53 * t / p.loop + 2.3))

        // ── 三条腿：机身系步态 → 世界系钉点 ──
        for (i in 0 until 3) {
            val l = world.legsBuf[i]
            legState(world, l, i, s)
            if (l.swing) {
                val pt = TripodFogMath.bodyFrame(l.lat, l.q, l.lift, psi, d, mx)
                l.fx = pt.x
                l.fy = l.lift
                l.fz = pt.z
            } else {
                val gp = g - (l.u - 1.0 / 3.0) * p.stride
                val lp = p.rf * sin(l.th)
                val qp = p.rf * cos(l.th) + p.stride / 3
                val pt = TripodFogMath.bodyFrame(lp, qp, 0.0, world.routePSI(gp), d + (g - gp), world.routeMX(gp))
                l.fx = pt.x
                l.fy = 0.0
                l.fz = pt.z
            }
            l.imp = TripodFogMath.footImp(l.u) // R22：触地冲击强度（0 = 不在窗内）
        }

        // ⭐ R26 步频驱动镜头：每一记落地给相机一个**阻尼回弹**（冲击源直接取 R22 那份 imp）。
        for (i in 0 until 3) {
            legU[i] = world.legsBuf[i].u
            legImp[i] = world.legsBuf[i].imp
        }
        val jolt = TripodFogMath.joltOf(legU, legImp, dist, p)
        val joltX = jolt[0]
        val joltY = jolt[1]
        val joltK = jolt[2]

        // 仰角：把机顶按到画面上沿 6% 处（⛔ 手工关键帧 —— 它是 d 的导出量，d 闭合则它闭合）
        val sHead = p.fz / (p.fz + d * cos(yaw) + mx * sin(yaw))
        val yTopRaw = world.cam.h * p.horizonK + (p.eye - TripodFogMath.topY(p)) * world.cam.ppm * sHead
        val tilt = TripodFogMath.clamp(world.cam.h * 0.06 - yTopRaw, 0.0, world.cam.h * p.tiltMax)

        // ── 建筑：两带各自部分选择（升序 = 近 → 远；K6 双指针合并成降序绘制） ──
        val midN = pushBand(world, world.bMid, world.bandMid, 0, min(p.nMid, world.bldSelMid.size), world.bldSelMid, c)
        val nearN = pushBand(world, world.bNear, world.bandNear, 1, min(p.nNear, world.bldSelNear.size), world.bldSelNear, c)

        // ⭐ R18：抓取时间轴与人群是**场景状态**的一部分，⛔ 只在绘制函数里算
        val grab = GrabState(world.gren.eval(t), world.grlift.eval(t))
        val crowdN = crowdAt(world, c, d, psi, mx, a.bodyY, a.bodyLat, a.leanFwd, d0, grab, world.crowdBuf)

        return TripodScene(
            t = t, d = d, dist = dist, g = g, s = s, mx = mx, psi = psi,
            bodyY = a.bodyY, roll = a.roll, bodyLat = a.bodyLat, leanFwd = a.leanFwd,
            headYaw = a.headYaw, dPsiLag = a.dPsiLag,
            gland = a.gland, beams = a.beams, hitFwd = a.hitFwd, beamSw = a.beamSw,
            fire = a.fire, beamI = a.beamI, beamSwV = a.beamSwV, beamRaw = a.beamRaw,
            yaw = yaw, c = c, vWalk = vWalk, camSpeed = camSpeed, tilt = tilt,
            legs = world.legsBuf,
            bldMid = world.bldSelMid, bldMidN = midN,
            bldNear = world.bldSelNear, bldNearN = nearN,
            d0 = d0,
            crowd = world.crowdBuf, crowdN = crowdN,
            grab = grab,
            shakeX = shakeX + joltX, shakeY = shakeY + joltY, // R26：恒幅微抖 + 每记落地的回弹
            joltX = joltX, joltY = joltY, joltK = joltK,
            fogPhA = TripodFogMath.mod(t * p.driftA, 1.0),
            fogPhB = TripodFogMath.mod(t * p.driftB, 1.0),
            breathPh = TripodFogMath.mod(t * p.breathHz, 1.0),
            poolR = 3.6 * TripodFogMath.lerp(0.92, 1.08, en.bass),
        )
    }

    // ─────────────────────── 建筑回收（instWrap） ───────────────────────

    /**
     * 原型 `instWrap(b, C, cfg)`（R12：填 [wrapHit]，⛔ 每栋建新对象）。
     *
     * 第 i 栋在相机行程 `C` 下的当前深度、窗口跨度，以及**这一趟借哪一栋的形状**。
     * ⭐ 借用索引用 `mod(k, b.k)` 而不是 `k`：`b.k` 恰是一圈内回收的次数 ⇒ 接缝处 `k` 增加 `b.k`，
     *    `mod` 后回到同一个值 ⇒ 第二圈的第 1 帧与第一圈逐字节相同。
     *    ⛔ 直接用 `k` = 每次回收都整体平移一格，接缝那一帧会**在可见深度就地换形**（T13 的 c 项）。
     * ⚠️ `span = GTOT / b.k` 是**每栋自己的**（⛔ 整带共用一个跨度 —— 那是"天际线每 90 s
     *    整块重放"的根源）；`k` 取整数 ⇒ `GTOT/span` 恰为整数 ⇒ T4 的 wrap 位移照样逐栋重合。
     */
    private fun wrapInto(world: TripodFogWorld, b: TripodBuilding, c: Double, cfg: TripodBandCfg) {
        val span = world.gtot / b.k
        val u = (b.z0 - c - cfg.zMin) / span
        val k = floor(u)
        wrapHit.z = cfg.zMin + (u - k) * span
        wrapHit.k = k.toInt()
        wrapHit.span = span
        val pool = b.pool
        wrapHit.s = if (pool.isEmpty()) {
            null
        } else {
            val m = TripodFogMath.mod(k, b.k.toDouble()).toInt()
            pool[(b.i + m * 11) % pool.size]
        }
    }

    /**
     * 原型 `sceneState` 里的 `push(list, cfg, bandIdx, n)`（R12 改写）。
     *
     * ⭐ **部分选择**：候选逐个插入一个**按 z 升序**、容量 `n` 的窗口 ——
     * ① 窗口未满：插到应处（其后元素整体后移）；
     * ② 窗口已满且比最远的还近：顶掉最远的再插到应处；
     * ③ 否则丢弃。
     * ⇒ 留下的是**最近 n 栋**且缓冲内自始升序（近 → 远），⛔ 全排序（`sort` 会进红线）。
     * ⚠️ 插入序必须与原型 `cand.sort((p,q)=>p.z-q.z)` 后取前 n **完全一致**：
     *    T4 指纹逐字段读 `bld` 数组，顺序差一项就是整条指纹错位。
     */
    private fun pushBand(
        world: TripodFogWorld,
        list: Array<TripodBuilding>,
        cfg: TripodBandCfg,
        bandIdx: Int,
        n: Int,
        out: Array<BldSel>,
        c: Double,
    ): Int {
        if (list.isEmpty() || n <= 0) return 0
        var count = 0
        var bi = 0
        while (bi < list.size) {
            val b = list[bi]
            wrapInto(world, b, c, cfg)
            val z = wrapHit.z
            if (z >= 2) {
                val alpha = TripodFogMath.bandWindow(z, cfg.zMin, wrapHit.span)
                // 窗口两端收敛到 0 ⇒ 雾里换形，看不见
                if (alpha > 0.002) {
                    val s = wrapHit.s
                    if (s != null) {
                        if (count < n) {
                            var j = count - 1
                            while (j >= 0 && out[j].z > z) {
                                copySel(out[j + 1], out[j])
                                j--
                            }
                            fillSel(out[j + 1], s, z, alpha, bandIdx, cfg.ink)
                            count++
                        } else if (z < out[n - 1].z) {
                            var j = n - 2
                            while (j >= 0 && out[j].z > z) {
                                copySel(out[j + 1], out[j])
                                j--
                            }
                            fillSel(out[j + 1], s, z, alpha, bandIdx, cfg.ink)
                        }
                    }
                }
            }
            bi++
        }
        return count
    }

    private fun fillSel(sel: BldSel, s: TripodBuilding, z: Double, a: Double, bandIdx: Int, ink: Double) {
        sel.id = s.i
        sel.x = s.x
        sel.z = z
        sel.w = s.w
        sel.h = s.h
        sel.dep = s.dep
        sel.band = bandIdx
        sel.a = a
        sel.ink = ink
        sel.skirtFrac = s.skirtFrac
        sel.skirtA = s.skirtA
        sel.src = s
    }

    private fun copySel(dst: BldSel, src: BldSel) {
        dst.id = src.id
        dst.x = src.x
        dst.z = src.z
        dst.w = src.w
        dst.h = src.h
        dst.dep = src.dep
        dst.band = src.band
        dst.a = src.a
        dst.ink = src.ink
        dst.skirtFrac = src.skirtFrac
        dst.skirtA = src.skirtA
        dst.src = src.src
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  tripodPose（世界系几何；T2/T10 与渲染器读同一份）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 原型 `tripodPose(st)`：髋 / 膝 / 脚的**世界系**坐标。
     *
     * ⚠️ 髋点跟着**重心转移**（`bodyLat`）一起侧移、跟 `leanFwd` 一起前后移，但**脚点不动** ——
     *    于是 IK 自动把支撑腿绷成斜撑，这就是"体重压上去"的几何来源。
     *    髋环先被**横滚**压扁（横向缩 `cos(roll)`、同时把侧移换成等量的升降），
     *    再交给 `bodyFrame` 按**航向 ψ** 旋进世界系 —— 顺序反了转身时腿会散架。
     *    ⛔ 脚点绝不能读机身系（那等于取消零滑步，T2 二维口径立刻抓到）。
     * ⭐ R11 起膝点也在这里解出来（⛔ 留在绘制函数里）：闸门要判"膝高于髋"，
     *    若膝只在绘制里算，闸门就得复制一遍 IK ⇒ 又是两套几何。
     */
    fun tripodPose(world: TripodFogWorld, st: TripodScene): TripodPose {
        val p = world.params
        val cr = cos(st.roll)
        val sr = sin(st.roll)
        val hips = Array(3) { i ->
            val l = st.legs[i]
            val lat = p.rh * sin(l.th) + st.bodyLat
            TripodFogMath.bodyFrame(
                lat * cr, p.rh * cos(l.th) + st.leanFwd, st.bodyY + lat * sr,
                st.psi, st.d, st.mx,
            )
        }
        val feet = Array(3) { i -> BodyPt(st.legs[i].fx, st.legs[i].fy, st.legs[i].fz) }
        val knees = Array(3) { i ->
            val l = st.legs[i]
            val hip = hips[i]
            // 膝目标点：沿**外法向**（跟机身航向一起转 ⇒ 侧面走位不读成螃蟹）偏出 kneeOut，
            // 同时在世界竖直方向上抬 kneeUp。目标只给"往哪弯"，段长由 ikKnee 的定长两连杆保证。
            val th = l.th + st.psi
            val nX = sin(th)
            val nZ = -cos(th)
            TripodFogMath.ikKnee(
                hip.x, hip.y, hip.z, l.fx, l.fy, l.fz,
                hip.x + nX * p.kneeOut, hip.y + p.kneeUp, hip.z + nZ * p.kneeOut,
                p,
            )
        }
        return TripodPose(hips, knees, feet)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  人群与抓取（R18 / R21）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 原型 `crowdTable()`：人群**坑位表**（R12：结果缓存进 [TripodFogWorld.crowdT]）。
     *
     * ⚠️ 种子里带 `crowdSeed0` 只为 T22(c) 的负样本服务（`zSeedK≠0` 时 `z0` 真的依赖种子）；
     *    `zSeedK=0` 时它进键是**惰性的**（表内容与之无关），⛔ 靠它改变默认布局。
     * ⭐ R29：键里带 **curVar** —— 受害人锚点 = `gAt(grabT) / DH / routeMX(Gg)` 的函数，
     *    换剧本就是换人质站的位置。`grabT` 每套不同本来就能让键变化，但那是**巧合**
     *    （两套剧本撞同一个 grabT 的瞬间就会读到上一个剧本的锚点，缆须伸向空处）
     *    ⇒ 键必须直接写剧本号。`crowdBuilds` 数的是**真的重算了几次**，闸门 T28(d) 拿它对账。
     *
     * ⚠️ `r()` 的调用顺序是**保真度的一部分**：受害人只抽 `kind`（1 次），
     *    其余坑位只抽 `z0`（1 次）—— 与原型对象字面量的求值顺序逐项对齐。
     */
    fun crowdTable(world: TripodFogWorld): Array<CrowdSlot> {
        val p = world.params
        keyBuf.setLength(0)
        keyBuf.append(world.curVar).append('|')
        keyBuf.append(world.ra[0])
        for (i in 1 until world.ra.size) keyBuf.append(',').append(world.ra[i])
        keyBuf.append('|')
        keyBuf.append(world.rp[0])
        for (i in 1 until world.rp.size) keyBuf.append(',').append(world.rp[i])
        keyBuf.append('|')
        keyBuf.append(world.gtot).append('|')
        keyBuf.append(p.crowdN).append('|')
        keyBuf.append(p.grabT).append('|')
        keyBuf.append(p.grabDz).append('|')
        keyBuf.append(p.grabDx).append('|')
        keyBuf.append(CrowdEpoch.Z_SEED_K).append('|')
        keyBuf.append(if (CrowdEpoch.Z_SEED_K != 0) p.crowdSeed0 else 0)
        val cached = world.crowdT
        if (cached != null && world.crowdKey.length == keyBuf.length &&
            world.crowdKey.contentEquals(keyBuf)
        ) {
            return cached
        }
        world.crowdKey = keyBuf.toString()
        world.crowdBuilds++
        val gg = world.gAt(p.grabT)
        // ⚠️ 原型这里还算了 dg = hEval(DH, P.grabT) 但**从未使用**（锚点只用 ky[0] 与 Gg）
        val zx = world.dh.ky[0] - gg + p.grabDz
        val xx = world.routeMX(gg) + p.grabDx
        val r = TripodFogMath.Lcg(20261012)
        val out = Array(p.crowdN) { i ->
            val victim = i == 0
            CrowdSlot(
                i = i,
                victim = victim,
                z0 = if (victim) zx else r.next() * world.gtot + CrowdEpoch.Z_SEED_K * p.crowdSeed0 * 1.7,
                x = if (victim) xx else 0.0,
                kind = if (victim) (r.next() * 3).toInt() else 0,
            )
        }
        world.crowdT = out
        return out
    }

    /**
     * 原型 `crowdIdent(i, w)`：坑位 i 在第 w 次回收时**是谁** —— 侧向位置 + 姿态。
     *
     * ⚠️ `w` 由 [crowdAt] 给出（式子见 [TripodFogMath.crowdEpochOf]），换人的**时刻**就是该人
     *    `mod` 归零、`z` 从带尾跳到带头的那一刻 ⇒ 两侧渲染 α 都贴 0 ⇒ 隐形。
     *    ⛔ 让 w 在任何别的时刻变化：那等于让一个正在画面中段的人瞬移（闸门 T22(a) 量的就是它）。
     */
    fun crowdIdent(world: TripodFogWorld, i: Int, w: Int, out: CrowdIdent) {
        val r = TripodFogMath.Lcg(world.params.crowdSeed0 * 7919 + i * 131 + w * 613)
        val side = if (r.next() < 0.5) -1.0 else 1.0
        out.x = side * TripodFogMath.lerp(
            world.crowdBand.xLo, world.crowdBand.xHalf,
            r.next().pow(0.7),
        )
        out.kind = (r.next() * 3).toInt()
    }

    /**
     * 原型 `crowdAt(s)`：坑位表 + 抓取时间轴 → **当帧**的人群（世界系 + 渲染 α，⛔ 屏幕系）。
     * 导出进 [TripodFogWorld.crowdBuf] 并返回人数（R12：⛔ 每帧建数组）。
     *
     * ⭐ R21：受害人的锚点是**抓取几何**的一部分（`grabDz/grabDx` 与缆须长度对死），⛔ 跟着纪元换；
     *    其余坑位每回收一次换一个人（轮次式子与符号见 [TripodFogMath.crowdEpochOf]）。
     * ⚠️ 人被提到腹下的过程是 `α→0`（⛔ 在画面上"消失/瞬移"），`crowdSoak` 是溶解窗口的起点。
     * ⛔ 不在这里算 `grabGap`：够不够得着由 [TripodFogMath.tendrilTip] 的
     *    `L = min(D, reach·tendReach)` 决定，闸门 T19(b) 直接调**同一支笔画的梢点**量 `D−L`。
     */
    fun crowdAt(
        world: TripodFogWorld,
        c: Double,
        d: Double,
        psi: Double,
        mx: Double,
        bodyY: Double,
        bodyLat: Double,
        leanFwd: Double,
        d0: Double,
        grab: GrabState,
        out: Array<CrowdAt>,
    ): Int {
        val p = world.params
        val cb = world.crowdBand
        val tb = crowdTable(world)
        val lift = grab.lift
        val belly = TripodFogMath.bodyFrame(0.0, 0.5, bodyY - 2.2, psi, d, mx)
        var n = 0
        for (slot in tb) {
            if (n >= out.size) break
            val raw = slot.z0 - c - cb.zMin
            val z = cb.zMin + TripodFogMath.mod(raw, cb.span)
            val w = floor(raw / cb.span).toInt() + CrowdEpoch.LAP_K * world.lapN
            // ⚠️ 受害人（victimK=0）读**坑位表里钉死的锚点**，其余坑位按轮次现算 ——
            //    两支的来源类型不同（CrowdSlot / CrowdIdent），⛔ 用一个联合类型变量
            //    （Kotlin 没有联合类型，`id.x` 会解析失败）⇒ 先落到两个局部量。
            val idX: Double
            val idKind: Int
            if (slot.victim && CrowdEpoch.VICTIM_K == 0) {
                idX = slot.x
                idKind = slot.kind
            } else {
                crowdIdent(world, slot.i, w, ident)
                idX = ident.x
                idKind = ident.kind
            }
            var x = idX
            var y = 0.0
            var zz = z
            var k = idKind
            if (slot.victim && lift > 0) {
                x = TripodFogMath.lerp(x, belly.x, lift)
                zz = TripodFogMath.lerp(z, belly.z, lift)
                y = TripodFogMath.lerp(0.0, belly.y, lift)
                k = if (lift > 0.03) 3 else idKind
            }
            val dist = hypot(x, zz)
            val win = TripodFogMath.crowdWindow(world, z)
            val soak = if (slot.victim) {
                1 - TripodFogMath.smoothstep(p.crowdSoak, p.crowdSoak + 0.28, lift)
            } else {
                1.0
            }
            val alpha = TripodFogMath.clamp(win * soak * TripodFogMath.shadeA(dist, d0, p) * p.crowdInk, 0.0, 1.0)
            val o = out[n]
            o.i = slot.i
            o.victim = slot.victim
            o.w = w
            o.x = x
            o.y = y
            o.z = zz
            o.dist = dist
            o.kind = k
            o.a = alpha
            n++
        }
        return n
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  fingerprint（T4 接缝指纹的摘抄器）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 原型 `fingerprint(st)`：把一帧场景摘抄成**可对账的标量数组**（闸门 T4 逐字段比对接缝）。
     *
     * ⛔ 不含 `t` 本身，也不含任何**累积量原值**：`G` 与 `C` 都是累计行程
     * （`G(LOOP) = GTOT`、`C(LOOP) = −GTOT`，直接进指纹等于必不闭合），而 mod 到端点又会被
     * 浮点尾差甩到周期的另一端 ⇒ 所有**圆周量**一律用 `sin/cos` 对表示（原型 `circ`）。
     * ⭐ R17：**yaw 也进 circ** —— 偏航扫满 0 → 152.5° → 250° → 360°（≡0°），
     *    于是 `yaw(LOOP) = 2π ≠ yaw(0) = 0`，拿原值进指纹 T4 立刻开缝。
     * ⚠️ `id` 也在指纹里：**借来的形状**必须在接缝两侧是同一个（T13 的 c 项）。
     *
     * 字段数 = 18 标量 + 6×2 circ + 3×6 腿 + 2×5 光 + 4×n 楼 = **58 + 4n**
     * （默认 n = 22 + 8 = 30 ⇒ 178 字段，与 §十四 T4 的「178 字段」对得上）。
     *
     * @param out 容量必须 ≥ `58 + 4·(st.bldMidN + st.bldNearN)`。
     * @param gtot 当前 [TripodFogWorld.gtot]（`circ(G/GTOT)` / `circ(C/GTOT)` 的分母；
     *             ⛔ 让本函数自己猜 —— 场景里不携带它，猜错就是整条指纹量错周期）。
     * @return 实际写入的字段数。
     */
    fun fingerprint(st: TripodScene, out: DoubleArray, gtot: Double): Int {
        val need = 58 + 4 * (st.bldMidN + st.bldNearN)
        require(out.size >= need) { "fingerprint 缓冲容量不足：需要 $need，实得 ${out.size}" }
        var n = 0
        out[n++] = st.d
        out[n++] = st.dist
        out[n++] = st.bodyY
        out[n++] = st.roll
        out[n++] = st.beamI
        out[n++] = st.beamSwV
        out[n++] = st.poolR
        out[n++] = st.tilt
        out[n++] = st.fire
        out[n++] = st.bodyLat
        out[n++] = st.leanFwd
        out[n++] = st.headYaw
        out[n++] = st.gland.x
        out[n++] = st.gland.z
        out[n++] = st.shakeX
        out[n++] = st.shakeY
        out[n++] = st.mx
        out[n++] = st.psi
        circ(out, n, st.yaw / TripodFogMath.TAU); n += 2
        circ(out, n, st.g / gtot); n += 2
        circ(out, n, st.c / gtot); n += 2
        circ(out, n, st.fogPhA); n += 2
        circ(out, n, st.fogPhB); n += 2
        circ(out, n, st.breathPh); n += 2
        for (i in 0 until 3) {
            val l = st.legs[i]
            out[n++] = l.q
            out[n++] = l.lift
            out[n++] = l.lat
            out[n++] = l.fx
            out[n++] = l.fz
            out[n++] = l.imp
        }
        for (j in 0 until 2) {
            val b = st.beams[j]
            out[n++] = b.ex
            out[n++] = b.ey
            out[n++] = b.ez
            out[n++] = b.hx
            out[n++] = b.hz
        }
        for (i in 0 until st.bldMidN) {
            val x = st.bldMid[i]
            out[n++] = x.id.toDouble()
            out[n++] = x.x
            out[n++] = x.z
            out[n++] = x.a
        }
        for (i in 0 until st.bldNearN) {
            val x = st.bldNear[i]
            out[n++] = x.id.toDouble()
            out[n++] = x.x
            out[n++] = x.z
            out[n++] = x.a
        }
        return n
    }

    /** 原型 `circ(v) = [sin(TAU·v), cos(TAU·v)]` —— 圆周量的单射表示（⛔ 原值）。 */
    private fun circ(out: DoubleArray, at: Int, v: Double) {
        out[at] = sin(TripodFogMath.TAU * v)
        out[at + 1] = cos(TripodFogMath.TAU * v)
    }
}
