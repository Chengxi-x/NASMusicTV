package com.nasmusic.tv.visualizer.renderers

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E47「雪松」的雪场（原型 §5 `LAYERS`/`FL`/`GL` + `allocFlakes`/`stepFlakes`/`allocGlints`）。
 *
 * ## 为什么是**五层**而不是"一层里逐粒变大小"
 * 一次批量提交（Kotlin 侧 = 一次 `drawPoints` / 一条 `Path` 一次 `fill`）内
 * `strokeWidth`/`color` 恒定 ⇒ "同层大小不一"只能靠**分更多层**拿到。逐粒提交会撞
 * TV 图元红线（原型 T5 计的就是"每层提交次数必须恒 1"）。
 * 半径抖动 [SnowField.allocFlakes] 的 `rz` 拿到的正是"同一层内大小不一"这一维。
 *
 * ## ⭐ `land` 是"落雪深度"，同时是消隐线与绘制先后的唯一口径
 * 每层的消隐 y = `hz + land·(H−hz)`（加雪原起伏与半个粒半径），**不是** `H`。
 * `land` 小于人物深度 ⇒ 该层画在人**后**，反之画在人**前**（P17/D9：⛔ 全层共用一条消隐线，
 * 那会把"漫天"读成"上半屏下雪"）。
 *
 * ## ⚠️ 状态数组是 `FloatArray`（= 原型的 `Float32Array`）
 * 逐帧位置**存**的是 float32。所有运算先在 `Double` 域做完再 `.toFloat()` 落存，
 * ⛔ 直接用 `Float` 参与连加——那和 JS 的舍入次序不同，初值对账（[SnowCedarGoldenTest] 的
 * K4 组探针）当场红。
 */
internal enum class SnowShape { DOT, STREAK, BOKEH }

/** 一层的静态定义（原型 `LAYERS[i]`）。 */
internal class SnowLayerDef(
    /** 速度因子（× 风速/落速）。 */
    val f: Double,
    /** 基准半径（× `L.s` 得像素）。 */
    val r: Double,
    /** 该层恒定 alpha。 */
    val a: Double,
    /** 占总粒数的份额。 */
    val share: Double,
    /** 粒子颜色（RGB，无 alpha）。冷暖 = 大气透视：远层偏冷灰，近层偏暖白。 */
    val rgb: Int,
    val shape: SnowShape,
    /** 该层在雪原上的**落雪深度**（0=地平线 … 1=画面底边）。 */
    val land: Double,
)

internal object SnowLayers {
    /** 五层，`land` **严格递增**（[SnowFieldTest] 盯着）——绘制次序就靠它排。 */
    val ALL: Array<SnowLayerDef> = arrayOf(
        SnowLayerDef(0.30, 1.05, 0.300, 0.280, 0xE0E7F1.toInt(), SnowShape.DOT, 0.06),     // 极远
        SnowLayerDef(0.50, 1.60, 0.520, 0.270, 0xECF2FA.toInt(), SnowShape.DOT, 0.17),     // 远
        SnowLayerDef(0.76, 2.45, 0.780, 0.250, 0xF7FAFE.toInt(), SnowShape.DOT, 0.36),     // 中（≈树基深度）
        SnowLayerDef(1.00, 3.10, 0.970, 0.165, 0xFFFDF8.toInt(), SnowShape.STREAK, 0.92),  // 近（带拖尾）
        SnowLayerDef(1.42, 11.0, 0.135, 0.035, 0xFFFCF6.toInt(), SnowShape.BOKEH, 1.00),   // 前景虚化（贴镜头）
    )

    /** bokeh 层的半径抖动收一半：贴镜头的大粒再抖 11 px×0.42 会有一批糊到看不见。 */
    fun rzScale(shape: SnowShape): Double = if (shape == SnowShape.BOKEH) 0.85 else 1.0
}

/**
 * 雪场状态：五层雪粒 + 一张固定的冰晶闪光点表。
 *
 * ⛔ 逐帧分配任何东西（含 `floatArrayOf`）——两条数组只在 [allocFlakes]/[allocGlints] 里换。
 */
internal class SnowField(private val p: SnowCedarP, private val l: SnowCedarLayout) {

    /** 实际总粒数：⚠️ 不等于 `P.snow.flakes` 时（各层 `max(8, …)` 抬高过）以本字段为准。 */
    var n = 0
        private set

    var flakeOff = IntArray(0); private set
    var flakeCount = IntArray(0); private set

    var x = FloatArray(0); private set
    var y = FloatArray(0); private set
    var vx = FloatArray(0); private set
    var vy = FloatArray(0); private set
    private var ph = FloatArray(0)
    private var rz = FloatArray(0)
    private var layer = ByteArray(0)

    var glintN = 0; private set
    private var gu = FloatArray(0)
    private var gx = FloatArray(0)
    private var gy = FloatArray(0)
    private var gph = FloatArray(0)

    /**
     * 原型 `allocFlakes(total)`：按 `share` 切片，**余数补给末层**。
     *
     * ⚠️ 余数只动末层的**右端点** ⇒ `n` 恰等于 `total` 且各层区间首尾相接；
     * 补给第 0 层会留一段永不动的前缀（原版的坑，注释写在那儿）。
     * 每层至少 8 粒：`share` 很小的高档位下"末层只有一粒"会读成画面右下角一个孤点。
     */
    fun allocFlakes(total: Int) {
        val nl = SnowLayers.ALL.size
        val bounds = Array(nl) { IntArray(2) }
        var acc = 0
        for (i in 0 until nl) {
            // ⛔ 不能直接 `roundToInt()`：那是四舍五入**后截断到 Int**，而这里要的正是原型的
            // `Math.max(8, Math.round(…))` 语义 ⇒ 在 Double 域取整再收窄。
            val c = max(8.0, SnowCedarMath.jsRound(total * SnowLayers.ALL[i].share)).toInt()
            bounds[i][0] = acc
            bounds[i][1] = acc + c
            acc += c
        }
        bounds[nl - 1][1] += total - acc
        flakeOff = IntArray(nl) { bounds[it][0] }
        flakeCount = IntArray(nl) { bounds[it][1] - bounds[it][0] }
        n = flakeOff[nl - 1] + flakeCount[nl - 1]

        x = FloatArray(n)
        y = FloatArray(n)
        vx = FloatArray(n)
        vy = FloatArray(n)
        ph = FloatArray(n)
        rz = FloatArray(n)
        layer = ByteArray(n)

        val rnd = SnowCedarMath.rng(p.tree.seed xor 0x1234)
        for (li in 0 until nl) {
            val a = flakeOff[li]
            val b = a + flakeCount[li]
            var i = a
            while (i < b) {
                layer[i] = li.toByte()
                x[i] = (-0.06 * l.w + rnd.next() * l.w * 1.12).toFloat()
                y[i] = (-0.05 * l.h + rnd.next() * l.h * 1.05).toFloat()
                ph[i] = (rnd.next() * SnowCedarMath.TAU).toFloat()
                // 逐粒半径抖动：dot/bokeh 靠它拿到"同层大小不一"；streak 层不用 ⇒ 拖尾才等宽
                rz[i] = (1.0 + (rnd.next() * 2.0 - 1.0) * p.snow.rVar *
                    SnowLayers.rzScale(SnowLayers.ALL[li].shape)).toFloat()
                vx[i] = 0.0f
                vy[i] = 0.0f
                i++
            }
        }
    }

    /**
     * 原型 `stepFlakes(dt, t, windPx, fallPx)`。
     *
     * ⚠️ 重生随机数的种子是 `(seed ^ 0x9e37) + ((t*60)|0)` —— **帧号在种子里**。
     * 只取 `seed ^ 0x9e37` 会让每一帧抽到同一条序列（黄金测试 R1 专门抽了 frame 0 与 frame 7
     * 两条流来钉这件事）。
     */
    fun stepFlakes(dt: Double, t: Double, windPx: Double, fallPx: Double) {
        val frame = (t * 60.0).toInt()          // JS `|0` = 向零截断（t ≥ 0 ⇒ 与 toInt() 同）
        val rnd = SnowCedarMath.rng((p.tree.seed xor 0x9e37) + frame)
        var i = 0
        while (i < n) {
            val li = layer[i].toInt()
            val lf = SnowLayers.ALL[li]
            val phi = ph[i].toDouble()
            val sway = sin(t * 1.7 + phi) * 0.22 + sin(t * 0.43 + phi * 2.1) * 0.12
            val vxi = windPx * lf.f * (1.0 + sway)
            val vyi = fallPx * lf.f * (0.85 + 0.30 * sin(phi + t * 1.3))
            vx[i] = vxi.toFloat()
            vy[i] = vyi.toFloat()
            // ⚠️ 速度**回读**存储值再乘 dt：JS 侧 `FL.x[i] += FL.vx[i] * dt` 吃的是已经落进
            // Float32Array 的那个值，⛔ 用未舍入的 Double——那就是另一套轨迹（T4 的越界数会变）。
            var xi = x[i].toDouble() + vx[i].toDouble() * dt
            var yi = y[i].toDouble() + vy[i].toDouble() * dt

            // 消隐边界 = 该层**自己的**落雪深度（⛔ 全层共用一条线 = D9 的回归）
            val gY = hideY(li, xi)
            if (yi > gY) {
                yi = -0.05 * l.h - rnd.next() * 0.05 * l.h
                xi = -0.06 * l.w + rnd.next() * l.w * 1.12
            }
            if (xi > l.w * 1.07) xi -= l.w * 1.14
            else if (xi < -l.w * 0.07) xi += l.w * 1.14

            x[i] = xi.toFloat()
            y[i] = yi.toFloat()
            i++
        }
    }

    /**
     * 一层的消隐 y（像素）：`hz + land·(H−hz)` 再加半个雪原起伏与半个粒半径。
     *
     * ⚠️ `min(H·1.045, …)` 是**上界**：近层/前景层的 `land` 接近 1，起伏项能把消隐线推到画面底边
     * 之外，没有上界这些粒子会永不重生（越积越多 ⇒ 满屏白点）。
     */
    fun hideY(li: Int, xPos: Double): Double = min(
        l.h * 1.045,
        l.hz + (l.h - l.hz) * SnowLayers.ALL[li].land +
            0.5 * (l.snowSurfaceY(xPos) - l.hz) +
            SnowLayers.ALL[li].r * l.s * 0.5,
    )

    /** 原型 `allocGlints()`：固定点表 ⇒ 逐帧**不吃**随机数（随机闪烁 = 频闪且不可复现）。 */
    fun allocGlints() {
        // 原型 `Math.max(0, Math.round(P.fx.glint))`：Kotlin 侧 `fx.glint` 已是 Int ⇒ 只剩非负钳位
        val cnt = max(0, p.fx.glint)
        glintN = cnt
        gu = FloatArray(cnt)
        gx = FloatArray(cnt)
        gy = FloatArray(cnt)
        gph = FloatArray(cnt)
        val rnd = SnowCedarMath.rng(p.tree.seed xor 0x77a1)
        var i = 0
        while (i < cnt) {
            // 深度 0=贴近地平线 … 1=画面底；指数 0.62 把分布推近 ⇒ 远处长不了晶粒
            val u = 0.04 + 0.96 * rnd.next().pow(0.62)
            gu[i] = u.toFloat()
            val xp = (rnd.next() * 1.1 - 0.05) * l.w
            gx[i] = xp.toFloat()
            // ⚠️ 吃**已落存**的 float32 横坐标（JS 侧就是 `snowSurfaceY(GL.x[i])`）
            gy[i] = (l.hz + (l.h - l.hz) * u +
                0.35 * (l.snowSurfaceY(gx[i].toDouble()) - l.hz)).toFloat()
            gph[i] = (rnd.next() * SnowCedarMath.TAU).toFloat()
            i++
        }
    }

    /** 只读访问器（⛔ 让渲染层直接摸私有数组 —— 越界与写坏都查不到）。 */
    fun layerOf(i: Int): Int = layer[i].toInt()
    fun rzOf(i: Int): Float = rz[i]
    fun phOf(i: Int): Float = ph[i]
    fun glintU(i: Int): Float = gu[i]
    fun glintX(i: Int): Float = gx[i]
    fun glintY(i: Int): Float = gy[i]
    fun glintPh(i: Int): Float = gph[i]

    /**
     * 冰晶闪光的**亮度脉冲**（原型 `drawGlints` 的 `b`）：`sin^16` 的尖峰 ⇒ 任一瞬间只有少数几粒在闪。
     *
     * @return 0 表示该粒此刻不闪（含 `b < 0.30` 的整段剔除，⛔ 把剔除阈值挪到渲染层去）。
     */
    fun glintBright(i: Int, t: Double): Double {
        val s0 = sin(t * (0.9 + 2.1 * gu[i].toDouble()) + gph[i].toDouble())
        if (s0 <= 0.0) return 0.0
        val b = s0.pow(16.0)
        return if (b < 0.30) 0.0 else b
    }

    /** 晶粒菱形的半宽（像素）；半高恒 = 半宽 × 2.8（竖长 = 冰晶各向反射的读感）。 */
    fun glintHalfW(i: Int, b: Double): Double =
        (0.7 + 2.9 * gu[i].toDouble()) * l.s * (0.55 + 0.45 * b)

    /**
     * streak 层的拖尾**长度**（像素）= `snow.streak × 直径`。
     *
     * ⛔ 旧口径"速度 × 秒数"：落到 30 px/s 的慢速上会拉成 10×30 的药丸（所有者判"雪粒在飞"）。
     * 按直径算 ⇒ 拖尾长度与粒大小同阶，读起来才是"斜着飘的大雪"。
     */
    fun streakLen(li: Int): Double = p.snow.streak * (SnowLayers.ALL[li].r * l.s) * 2.0

    /**
     * streak 层一粒的拖尾**另一端**坐标（沿速度反方向）。
     *
     * ⚠️ `+0.01` 的斜向小偏置是原型原样搬来的：完全水平/垂直的零长度线在 canvas 上是一个点，
     * 加 0.01 让 `atan2` 不退化。`sp` 的 `|| 1` 兜住静止帧（无声 + 零风时速度恒 0）。
     */
    fun streakEndX(i: Int, kPx: Double): Double {
        val sp = sqrt(vx[i].toDouble() * vx[i].toDouble() + vy[i].toDouble() * vy[i].toDouble())
        val s = if (sp == 0.0) 1.0 else sp
        return x[i].toDouble() - vx[i].toDouble() / s * kPx + 0.01
    }

    fun streakEndY(i: Int, kPx: Double): Double {
        val sp = sqrt(vx[i].toDouble() * vx[i].toDouble() + vy[i].toDouble() * vy[i].toDouble())
        val s = if (sp == 0.0) 1.0 else sp
        return y[i].toDouble() - vy[i].toDouble() / s * kPx + 0.01
    }

    /** 一粒的像素半径（streak 层忽略 `rz` ⇒ 拖尾等宽）。 */
    fun flakeRadiusPx(i: Int): Double {
        val li = layer[i].toInt()
        return max(0.35, SnowLayers.ALL[li].r * l.s * rz[i].toDouble())
    }

    /** streak 层一粒的线宽（像素）：恒等于**直径**，⛔ 乘 `rz`——拖尾一粗细不均就读成歪的。 */
    fun streakWidthPx(li: Int): Double = SnowLayers.ALL[li].r * l.s * 2.0
}
