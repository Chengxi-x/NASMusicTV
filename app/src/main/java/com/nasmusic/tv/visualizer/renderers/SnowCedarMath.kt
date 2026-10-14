package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E47「雪松」的确定性数学工具 —— 原型 §0/§2/§4.3 的 1:1 移植。
 *
 * ## 为什么自带一份随机数而⛔ 不共用 [com.nasmusic.tv.visualizer.VisualizerRandom]
 * `VisualizerRandom` 是 **LCG**（`state*1664525+1013904223`，取 `(state shr 8)/2^24`），
 * 原型用的是 **mulberry32**。两者序列完全不同 ⇒ 换随机数就是换一棵树、换一片雪原。
 * 本文件的 [SnowRng.next] 必须与原型逐位一致（[SnowCedarGoldenTest] 的 R1 判据）。
 *
 * ## ⚠️ 数值口径两条
 * 1. **纯数学一律 `Double`**（参数表同）：`n = clamp(round(len/segLenPx), 2, 6)` 这类判断在
 *    临界值上换边就少一段枝 ⇒ 与原型对账的黄金测试当场红；只在落 `Canvas` 的那一行转 `Float`。
 * 2. [jsRound] 是 JS `Math.round` 的语义（`floor(v+0.5)`），⛔ 不要"改成更 Kotlin 的
 *    `roundToInt()`"——后者返回 Int 且带溢出截断语义，而这里的结果要留在 Double 域。
 */
internal object SnowCedarMath {

    const val PI_D = 3.141592653589793
    const val TAU = 2.0 * PI_D
    const val D2R = PI_D / 180.0

    fun clamp(v: Double, a: Double, b: Double): Double = if (v < a) a else if (v > b) b else v

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** JS `Math.round`：`floor(v + 0.5)`（⛔ 不是银行家舍入）。 */
    fun jsRound(v: Double): Double = floor(v + 0.5)

    /**
     * mulberry32 —— 原型的 `mulberry32(seed)`。
     *
     * ⚠️ 每处使用都**新建一个实例**（`buildTree` / `allocFlakes` / `allocGlints` 各自独立流），
     * ⛔ 全效果共用一条流：那会让"改了雪粒初值"顺带换掉整棵树（P28 第二条教训的同类）。
     */
    fun rng(seed: Int): SnowRng = SnowRng(seed)

    class SnowRng(initial: Int) {
        /** JS 闭包里的 `a` 是**被加数**而非输出：第 1 次 `next()` 用 `seed + 0x6D2B79F5`。 */
        private var a: Int = initial

        fun next(): Double {
            a += 0x6d2b79f5
            // Kotlin 的 Int 乘法天然按 32 位回绕 ⇒ 与 `Math.imul` 同；`|0` 同理。
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            // ⚠️ `(t ^ (t>>>14)) >>> 0` 是**无符号** 32 位 ⇒ 必须先转 Long 再抹符号位；
            //    直接 `t / 2^32` 会给出负数（原型不会）。
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** 二维整数哈希（⛔ 不占 RNG 流 —— 装饰量占一次就把整棵树往后推一位）。 */
    fun hash2(i: Int, j: Int): Double {
        var h = (i * 374761393) + (j * 668265263)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    /** 值噪声：格点 [hash2] + smoothstep 双线性。 */
    fun vnoise(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = xf * xf * (3.0 - 2.0 * xf)
        val v = yf * yf * (3.0 - 2.0 * yf)
        return lerp(
            lerp(hash2(xi, yi), hash2(xi + 1, yi), u),
            lerp(hash2(xi, yi + 1), hash2(xi + 1, yi + 1), u),
            v,
        )
    }

    /** 分形叠加。⚠️ 末行的 `/ n` 归一**不能省**：省掉雪原表面线会整片偏向 0。 */
    fun fbm(x: Double, y: Double, oct: Int, gain: Double, lac: Double): Double {
        var s = 0.0
        var a = 0.5
        var f = 1.0
        var n = 0.0
        var i = 0
        while (i < oct) {
            n += a
            s += a * vnoise(x * f, y * f)
            a *= gain
            f *= if (lac == 0.0) 2.0 else lac
            i++
        }
        return s / n
    }

    /**
     * `meshTable` —— 大树风摆的**每行 x 偏移**（原型 §4.3；Kotlin 侧展开成
     * `drawBitmapMesh` 的顶点数组）。
     *
     * ⭐ 两条不可动的性质：
     * 1. `u^1.6` ⇒ 基部行（`u=0`）偏移恒 0，树"长在土里"而不是整块平移；
     * 2. **同一行各列偏移恒等** ⇒ 网格横向不可能撕裂（撕裂读成"树皮被撕成条"）。
     *    所以生产代码按行填同一个值即可，⛔ 逐列再算一遍。
     *
     * @param bmpH 树位图像素高
     * @param out  容量 ≥ [rows] 的接收数组（⛔ 逐帧新建 —— 零分配红线）
     */
    fun meshOffsets(sway: Double, rows: Int, bmpH: Double, out: DoubleArray) {
        val yBot = ((rows - 0.5) * bmpH) / rows
        val yTop = (0.5 * bmpH) / rows
        val span = maxOf(1e-6, yBot - yTop)
        var j = 0
        while (j < rows) {
            val yc = ((j + 0.5) * bmpH) / rows
            val u = clamp((yBot - yc) / span, 0.0, 1.0)
            out[j] = sway * if (u <= 0.0) 0.0 else u.pow(1.6)
            j++
        }
    }

    /** JS `Math.hypot(a,b)` 在本效果里只作用于单位系小量 ⇒ 直接勾股（无溢出/下溢风险）。 */
    fun hypot(a: Double, b: Double): Double = sqrt(a * a + b * b)

    fun absD(v: Double): Double = abs(v)

    /** 转发 `kotlin.math.sin`（渲染层统一从这里取，⛔ 各处自己 import）。 */
    fun sinD(v: Double): Double = sin(v)
}

/**
 * E47 的布局（原型 `L` + `layout()`）：**画幅像素**尺度，进效果首帧与画幅变化时重算一次。
 *
 * ⚠️ `personY` **不是参数**，它在这里由 `treeBaseY` 赋值（原型 `layout()` 的
 * `L.personY = L.treeBaseY`）。护栏见 [SnowCedarGoldenTest] 的 T12 同款判据：
 * 只比数值不够——**留一个可写的字段就是留一条会漂移的路**。
 */
internal class SnowCedarLayout {
    var w = 0.0
    var h = 0.0

    /** `S = H/540`：雪粒半径等**按高度归一**的量用的缩放因子（原型的 `S`）。 */
    var s = 1.0

    var hz = 0.0
    var groundY0 = 0.0
    var treeX = 0.0
    var treeBaseY = 0.0
    var treeTopY = 0.0
    var treeH = 0.0
    var personY = 0.0
    var personX = 0.0
    var personH = 0.0
    var sunX = 0.0
    var sunY = 0.0
    var sunR = 0.0

    /**
     * 原型 `layout(w, h)`。
     *
     * ⚠️ `treeH` 的 `max(20, …)` 下限：`crownTop` 与 `treeBaseY` 是两个独立 fraction，画幅很矮时
     * 二者可能几乎重合 ⇒ 树高趋 0，`uy = treeH/bboxH` 与 `segLenPx = segPx/treeH` 一起爆炸。
     */
    fun layout(wIn: Double, hIn: Double, p: SnowCedarP) {
        w = wIn
        h = hIn
        s = hIn / 540.0
        hz = p.comp.horizon * hIn
        groundY0 = p.comp.treeBaseY * hIn
        treeX = p.comp.treeX * wIn
        treeBaseY = p.comp.treeBaseY * hIn
        treeTopY = p.comp.crownTop * hIn
        treeH = maxOf(20.0, treeBaseY - treeTopY)
        personY = treeBaseY          // ⭐ 与树同一条水平线（⛔ 不是独立参数）
        personX = p.comp.personX * wIn
        personH = p.comp.personH * hIn
        sunX = p.comp.sunX * wIn
        sunY = p.comp.sunY * hIn
        sunR = p.comp.sunR * hIn
    }

    /**
     * 雪原表面线（地平线以下的缓丘），x 像素 → y 像素。原型 §2 `snowSurfaceY`。
     *
     * ⭐ 它是**逐层消隐线**的唯一口径：五层雪各自的消隐 y 里带 `0.5·(snowSurfaceY(x) − hz)`
     * （§4.4），⛔ 改用常量地平线会让"雪落在地上"静默退化成"雪贴在镜头前"。
     */
    fun snowSurfaceY(x: Double): Double {
        val u = x / w
        return hz + h * (
            0.012 + 0.030 * SnowCedarMath.fbm(u * 5.5, 3.7, 3, 0.5, 2.0) +
                0.010 * SnowCedarMath.sinD(u * 5.1)
            )
    }
}
