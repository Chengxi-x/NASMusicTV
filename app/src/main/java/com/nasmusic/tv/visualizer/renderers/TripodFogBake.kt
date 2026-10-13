package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E46「迷雾之战 · 雾中三脚巨影」**烘焙层**（`docs/archive/e46-tripod-fog-plan.md` §五，
 * 原型 `docs/archive/e46-tripod-fog-preview.html` 的 `h2/vnoise/fbm/bakeFogStrip/bakeBuilding/
 * bakeGrade/bakePerson/bakeAll/genBuildings`）。
 *
 * ## 本文件产出什么
 * 全部是 **`IntArray`（`ARGB_8888` 打包整型，与 `Bitmap.setPixels` 同一格式）**：
 * - 两条雾条 A `256×300` / B `192×300`（水平严格周期 + 垂直 α 斜坡**烘进位图**）；
 * - 48 栋战损建筑剪影位图（`96 × clamp(round(96·h/w), 48, 320)`）：无电（只有一个颜色）、
 *   `destination-out` **有界**挖穿（`CARVE`）、⭐ 下缘雾裙 + 左右/檐口羽化**烘进同一张**；
 * - grade 全屏位图（暗角 + 胶片颗粒合成一张，⛔ 棱线 —— D31）；
 * - 4 张人群姿态位图 `14×34`（`_bh` 随位图走，8 人复用）。
 *
 * ## ⛔ 三条纪律
 * 1. **零 Android 类型**：`Bitmap` / `Canvas` / `Color` 一个都不出现 ⇒ 单测直接喂构造像素，
 *    不需要图形设备（承 E44 [MoonDiskBake] 的同一条纪律）。
 * 2. **⛔ 回读像素当闸门**：JVM 里没有真 canvas，像素恒 0 ⇒ 回读版永远绿。T11/T15/T20
 *    在 Kotlin 侧的形态是**解析记账**：`_inks`（指令流颜色名单）、`_carves`（每道破口的
 *    归一化矩形）、`_skirt` / `_feather`（裙高/强度/羽化宽度的**实际落笔值**）。
 * 3. **⛔ 运行时再叠一层**：雾裙与羽化都是**烘焙期**的 α 分布（原型 R19/D39 的判读：
 *    "运行时叠"会让所有楼在同一屏幕行被切齐）。改 `skirtH`/`skirtA`/`FEATHER` 必须重烘。
 *
 * ## 与原型的两处**有意**差异（都只影响亚像素，⛔ 不影响任何闸门）
 * - 多边形 / 圆头笔画的抗锯齿：原型走浏览器 canvas 的栅格化器，本文件走**4 子行扫描线 +
 *   解析跨度覆盖**（竖向 1/4 px 精度、横向解析精确）。立面位图被 blit 到屏幕上时绘制 α 只有
 *   0.000~0.13（D0=70 的雾律），人群位图还要再缩 2.4 倍 ⇒ 亚像素差完全不可见。
 * - `fillRect` 类 destination-out **不抗锯齿**（与 canvas 的 `fillRect` 一致：轴对齐矩形
 *    按像素中心归属）。
 *
 * ## 量化口径（⭐ 与 `putImageData` 逐位一致）
 * `ImageData.data` 是 `Uint8ClampedArray`：赋值走 **ToUint8Clamp = 截到 [0,255] 后
 * **四舍五入到偶数**（ties-to-even，实测 `0.5→0 / 1.5→2 / 2.5→2 / 254.5→254`）**。
 * ⛔ 不是 Kotlin `roundToInt()` 的 ties-up（`Math.round` = `floor(x+0.5)`）——
 * 雾条/grade 的黄金对账会逐字段踩到 .5 平局。见 [u8]。
 */
internal object TripodFogBake {

    // ══════════════════════════════════════════════════════════════════════════
    //  常量（原型 :1219/:1223 的 CARVE / FEATHER，§七 (3) 的界）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * R13 破口界。⛔ 这些数字是「立面必须读成实心战损板、不是格栅」的几何约束，改前先过 T15。
     * 第一版 `seamW=[0.35,1] + edge=0 + holeW=[0.3,1]` ⇒ 整幅横条透出余烬地平线、
     * 外加一条横贯立面的斜划痕（判读轮 3 左楼那两处伪影）。
     *
     * ⚠️ **`var` 只为 T15 的负样本**（第一版那套界必须能在测试里改回来、用**同一批种子重烘**，
     * 手喂常量 = 一条不咬自己的假负样本）；生产路径恒用下面这份，⛔ 上滑杆、⛔ 在别处改。
     */
    object Carve {
        var seamW0 = 0.18
        var seamW1 = 0.55

        /** 楼板缝高（占位图高）。 */
        var seamH = 0.18

        /** 多数层不挖（楼板还在）。 */
        var seamSkip = 0.55

        /** 两端留边 ⇒ 破口永远不触边。 */
        var edge = 0.04
        var holeW0 = 0.08
        var holeW1 = 0.22
        var holeH0 = 0.04
        var holeH1 = 0.11
    }

    /**
     * R19b 边缘羽化宽度（位图自身比例，⛔ 屏幕 px —— 位图会被拉伸到投影高度）。
     *
     * ⚠️ `var` 只为 **T20 负样本 E**（羽化→0 重烘焙 ⇒ 立面回到硬直角边）；生产恒用这份。
     */
    object Feather {
        var w = 0.07
        var top = 0.05
    }

    /** 雾条 A：`bakeFogStrip(256, 300, 3, 7717, 5, 1)`（原型 :1411）。 */
    const val STRIP_A_W = 256
    const val STRIP_A_H = 300
    const val STRIP_A_CELLS = 3
    const val STRIP_A_SEED = 7717
    const val STRIP_B_W = 192
    const val STRIP_B_H = 300
    const val STRIP_B_CELLS = 4
    const val STRIP_B_SEED = 3391

    /** fbm 八度数（两条雾条都是 5）。 */
    const val STRIP_OCT = 5

    /**
     * 分帧烘焙的**单个单元像素上界**（行块 = `本值 / 宽`）。
     *
     * 取 24000 px ⇒ 全屏 grade（1920 宽）一块 12 行、雾条 A（256 宽）一块 93 行。
     * 上界的意义：渲染器的预算制是「烘到超时为止」⇒ 最坏情况就是**一个单元越界**，
     * 有界的一帧超调才不会把 42~53 ms 的电视帧拖成可感知的卡顿。
     * ⛔ 不要为了「少几块」把它放大：48 栋楼里最大的一张是 96×320 = 30720 px，
     * 那是原子的（一栋的几何与烘焙无法再拆），本值小于它纯属两回事。
     */
    const val BAKE_UNIT_PX_CAP = 24000

    /** 建筑位图宽（原型 `const px = 96`）。 */
    const val BLD_PX = 96

    /** 建筑位图高的钳制区间（原型 `clamp(Math.round(px·h/w), 48, 320)`）。 */
    const val BLD_PY_MIN = 48
    const val BLD_PY_MAX = 320

    /** 人群位图尺寸（原型 `bakePerson`）。 */
    const val PERSON_W = 14
    const val PERSON_H = 34

    /** 圆头笔画的线宽 / 头 discs 半径（原型 `g.lineWidth = 3.0` / `arc(x, y, 3.0)`）。 */
    const val PERSON_LW = 3.0

    /** 剪影色（原型 `CFG.SILH = [18, 19, 23]`）—— 建筑/人群位图**唯一**的颜色（T11）。 */
    val SILH_R = 18
    val SILH_G = 19
    val SILH_B = 23

    /** 雾色（原型 `CFG.FOG = [178, 174, 168]`）—— 雾条的基色。 */
    val FOG_R = 178
    val FOG_G = 174
    val FOG_B = 168

    /** 打包成 `ARGB_8888` 的剪影色（不透明）—— T11 指令流与栅格化共用这一个值。 */
    val SILH_INT: Int = (0xFF shl 24) or (SILH_R shl 16) or (SILH_G shl 8) or SILH_B

    // ══════════════════════════════════════════════════════════════════════════
    //  量化：ToUint8Clamp（ties-to-even）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * `ImageData.data`（`Uint8ClampedArray`）的赋值语义：钳到 [0,255] 后**四舍五入到偶数**。
     *
     * ⚠️ ⛔ 不是 [roundToInt]（= Java `Math.round` = `floor(x+0.5)`，ties **向上**）：
     *    `0.5→0` 而 `roundToInt(0.5)=1`。雾条与 grade 的 α 是 `fbm/hypot` 的浮点乘积，
     *    .5 平局虽罕见但**确实会出现**（`a·255` 的二进制尾数恰好的时候）⇒ 黄金对账逐字段踩。
     */
    fun u8(v: Double): Int {
        if (v.isNaN()) return 0
        if (v < 0.5) return 0
        if (v >= 255.5) return 255
        val f = floor(v)
        val d = v - f
        val fi = f.toInt()
        return when {
            d < 0.5 -> fi
            d > 0.5 -> fi + 1
            else -> if (fi and 1 == 0) fi else fi + 1 // ties-to-even
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  噪声（原型 :1337/:1342/:1348 的 h2 / vnoise / fbm）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 整数格点 hash（原型 `h2`）。
     *
     * ⚠️ **⛔ 复用 [SeasideWaves.vnoise2]**：那本的 hash 常量与 `per` 语义都不同，
     *    复用会让 T6 的接缝判据值与原型对不上（雾条的确定性是黄金对账的一部分）。
     *    `mod(i, per)` 让 x 方向**按 per 严格周期**（T6 的正样本就建在这上面）。
     *
     * ⚠️ `per` 是 **Double**：T6 的负样本要传非整除格频 `cells + 0.37`（周期不整除 ⇒
     *    接缝必然显著非零）。相应地，`mod(i,per)·374761393 + j·668265263 + seed·97463`
     *    必须按 **double 求和再 `|0`**（JS 语义）—— `j` 大到 540 时 `j·668265263` 已过 2^31，
     *    ⛔ 用 Int 累加（虽然整数输入时 Int 回绕恰好等价，但非整数 `per` 时不等价）。
     */
    fun h2(i: Int, j: Int, per: Double, seed: Int): Double {
        val sum = modD(i.toDouble(), per) * 374761393.0 + j * 668265263.0 + seed * 97463.0
        var n = toInt32(sum)
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n.toLong() and 0xFFFFFFFFL).toDouble() / 4294967295.0
    }

    /** JS `|0` 的 ToInt32 语义（截断 + mod 2^32 回绕）—— ⛔ Kotlin `toInt()` 会**饱和**。 */
    private fun toInt32(v: Double): Int {
        val t = v.toLong() // |v| < 2^63 时精确截断（本文件所有输入都远小于）
        return (t and 0xFFFFFFFFL).toInt()
    }

    /** JS `mod`（`((a % n) + n) % n`；两边都是 fmod 语义，符号随被除数）。 */
    private fun modD(a: Double, n: Double): Double {
        val r = a % n
        return if (r < 0.0) r + n else r
    }

    /** 双线性平滑值噪声（原型 `vnoise`）。 */
    fun vnoise(x: Double, y: Double, per: Double, seed: Int): Double {
        val i = floor(x)
        val j = floor(y)
        val u = x - i
        val v = y - j
        val su = u * u * (3 - 2 * u)
        val sv = v * v * (3 - 2 * v)
        val ii = i.toInt()
        val jj = j.toInt()
        val a = lerp(h2(ii, jj, per, seed), h2(ii + 1, jj, per, seed), su)
        val b = lerp(h2(ii, jj + 1, per, seed), h2(ii + 1, jj + 1, per, seed), su)
        return lerp(a, b, sv)
    }

    /**
     * 分形布朗（原型 `fbm`；`c` 每层翻倍、`y` 方向走 0.62 的各向异性）。
     *
     * ⚠️ `cells` 是 Double：负样本传 `cells + 0.37`（见 [h2]）。
     */
    fun fbm(x: Double, y: Double, cells: Double, seed: Int, oct: Int): Double {
        var s = 0.0
        var amp = 0.5
        var nrm = 0.0
        var c = cells
        var o = 0
        while (o < oct) {
            s += amp * vnoise(x * c, y * c * 0.62, c, seed + o * 101)
            nrm += amp
            amp *= 0.55
            c *= 2.0
            o++
        }
        return s / nrm
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private fun clamp(v: Double, a: Double, b: Double): Double = if (v < a) a else if (v > b) b else v

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp((x - e0) / (if (e1 - e0 != 0.0) e1 - e0 else 1e-9), 0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  栅格化暂存（⛔ 每栋新建：bakeBuilding 顺序调用 48 次，复用即零分配）
    // ══════════════════════════════════════════════════════════════════════════

    private class Scratch {
        var cov = DoubleArray(0)
        var acc = DoubleArray(0)
        val xs = DoubleArray(64)
        val ws = IntArray(64)

        fun ensure(w: Int, h: Int) {
            if (cov.size < w * h) cov = DoubleArray(w * h)
            if (acc.size < w) acc = DoubleArray(w)
        }
    }

    private val sc = Scratch()

    /**
     * 多边形覆盖度写进 [Scratch.cov]（0..1）。
     *
     * 4 子行扫描线 + **解析跨度覆盖**：每个子行求多边形边与扫描线的交点，
     * 按 nonzero 缠绕数成跨度，跨度与每个像素列 `[x, x+1]` 的交叠长度即覆盖贡献。
     * ⇒ 横向亚像素解析精确，竖向 1/4 px。⛔ 不用"像素中心命中"二值化（硬锯齿边在
     * 8% 对比度的画面上读成卡片，正是 R19b 要修的东西）。
     */
    private fun polyCover(w: Int, h: Int, poly: DoubleArray) {
        val cov = sc.cov
        val acc = sc.acc
        val xs = sc.xs
        val ws = sc.ws
        java.util.Arrays.fill(cov, 0, w * h, 0.0)
        val n = poly.size / 2
        var y = 0
        while (y < h) {
            java.util.Arrays.fill(acc, 0, w, 0.0)
            var sy = 0
            while (sy < 4) {
                val yy = y + (sy + 0.5) * 0.25
                // 交点（半开规则：每行每边只计一次）
                var m = 0
                var e = 0
                while (e < n) {
                    val j = if (e + 1 == n) 0 else e + 1
                    val x0 = poly[2 * e]
                    val y0 = poly[2 * e + 1]
                    val x1 = poly[2 * j]
                    val y1 = poly[2 * j + 1]
                    if ((y0 <= yy && y1 > yy) || (y1 <= yy && y0 > yy)) {
                        xs[m] = x0 + (yy - y0) / (y1 - y0) * (x1 - x0)
                        ws[m] = if (y1 > y0) 1 else -1
                        m++
                    }
                    e++
                }
                // 插入排序（m ≤ 顶点数 ~20，⛔ Arrays.sort 装箱）
                var a = 1
                while (a < m) {
                    val xv = xs[a]
                    val wv = ws[a]
                    var b = a - 1
                    while (b >= 0 && xs[b] > xv) {
                        xs[b + 1] = xs[b]
                        ws[b + 1] = ws[b]
                        b--
                    }
                    xs[b + 1] = xv
                    ws[b + 1] = wv
                    a++
                }
                var wind = 0
                var i = 0
                while (i < m) {
                    wind += ws[i]
                    if (wind != 0 && i + 1 < m) addSpan(acc, w, xs[i], xs[i + 1], 0.25)
                    i++
                }
                sy++
            }
            var x = 0
            while (x < w) {
                cov[y * w + x] = acc[x]
                x++
            }
            y++
        }
    }

    /** 跨度 [xa, xb) 与各像素列的交叠长度 × k 累加进 [acc]。 */
    private fun addSpan(acc: DoubleArray, w: Int, xa: Double, xb: Double, k: Double) {
        if (xb <= xa) return
        var x = floor(xa).toInt()
        if (x < 0) x = 0
        val xe = min(w, ceil(xb).toInt())
        while (x < xe) {
            val lo = if (xa > x) xa else x.toDouble()
            val hi = if (xb < x + 1.0) xb else x + 1.0
            if (hi > lo) acc[x] += (hi - lo) * k
            x++
        }
    }

    /** source-over 写入（预乘混合；位图初始全 0 ⇒ 首次填充即 `A = cov·α`、RGB = 本色）。 */
    private fun srcOver(px: IntArray, i: Int, cov: Double, alpha: Double, r: Int, g: Int, b: Int) {
        val as_ = cov * alpha
        if (as_ <= 0.0) return
        val ad = ((px[i] ushr 24) and 0xFF) / 255.0
        val outA = as_ + ad * (1 - as_)
        if (outA <= 0.0) {
            px[i] = 0
            return
        }
        val dr = ((px[i] ushr 16) and 0xFF).toDouble()
        val dg = ((px[i] ushr 8) and 0xFF).toDouble()
        val db = (px[i] and 0xFF).toDouble()
        val outR = (r * as_ + dr * ad * (1 - as_)) / outA
        val outG = (g * as_ + dg * ad * (1 - as_)) / outA
        val outB = (b * as_ + db * ad * (1 - as_)) / outA
        px[i] = (u8(outA * 255.0) shl 24) or (u8(outR) shl 16) or (u8(outG) shl 8) or u8(outB)
    }

    /** destination-out 的矩形版（⛔ 不抗锯齿：按像素中心归属，与 canvas `fillRect` 一致）。 */
    private fun destOutRect(px: IntArray, w: Int, h: Int, x0: Double, y0: Double, x1: Double, y1: Double, a: Double) {
        if (a <= 0.0) return
        var x = 0
        while (x < w) {
            val cx = x + 0.5
            if (cx < x0 || cx >= x1) {
                x++
                continue
            }
            var y = 0
            while (y < h) {
                val cy = y + 0.5
                if (cy >= y0 && cy < y1) {
                    val i = y * w + x
                    val ad = ((px[i] ushr 24) and 0xFF) / 255.0
                    val outA = ad * (1 - a)
                    if (outA <= 0.0) px[i] = 0
                    else px[i] = (px[i] and 0x00FFFFFF) or (u8(outA * 255.0) shl 24)
                }
                y++
            }
            x++
        }
    }

    /**
     * destination-out 的**轴向线性渐变**版：`(xa,ya)` 处 α = [aA]、`(xb,yb)` 处 α = [aB]，
     * 只作用于矩形 `[x0,x1]×[y0,y1]`（像素中心取样）。
     *
     * ⚠️ canvas 渐变按**非预乘**的 α 插值，而 destination-out 只吃源 α ⇒ 与实现无关。
     */
    private fun destOutGrad(
        px: IntArray, w: Int, h: Int,
        x0: Double, y0: Double, x1: Double, y1: Double,
        xa: Double, ya: Double, xb: Double, yb: Double,
        aA: Double, aB: Double,
    ) {
        val dx = xb - xa
        val dy = yb - ya
        val len2 = dx * dx + dy * dy
        if (len2 <= 0.0) {
            destOutRect(px, w, h, x0, y0, x1, y1, aA)
            return
        }
        var x = 0
        while (x < w) {
            val cxp = x + 0.5
            if (cxp < x0 || cxp >= x1) {
                x++
                continue
            }
            var y = 0
            while (y < h) {
                val cyp = y + 0.5
                if (cyp >= y0 && cyp < y1) {
                    val t = ((cxp - xa) * dx + (cyp - ya) * dy) / len2
                    val a = lerp(aA, aB, if (t < 0.0) 0.0 else if (t > 1.0) 1.0 else t)
                    if (a > 0.0) {
                        val i = y * w + x
                        val ad = ((px[i] ushr 24) and 0xFF) / 255.0
                        val outA = ad * (1 - a)
                        if (outA <= 0.0) px[i] = 0
                        else px[i] = (px[i] and 0x00FFFFFF) or (u8(outA * 255.0) shl 24)
                    }
                }
                y++
            }
            x++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  T11 的指令流记录（⛔ 像素回读：JVM 里没有真 canvas）
    // ══════════════════════════════════════════════════════════════════════════

    /** source-over 填充（原型 `fillStyle = ink(...)` + `fill()/fillRect()`）。 */
    const val OP_FILL = 0

    /** destination-out 挖穿/擦除（原型 `globalCompositeOperation = 'destination-out'`）。 */
    const val OP_CLEAR = 1

    class BakeCmd(val op: Int, val color: Int)

    /**
     * 指令流轨迹（默认 null = 生产零成本）。T11 由测试打开：烘焙 48 栋后断言
     * **只出现过剪影色**，且 `OP_CLEAR` 的颜色被忽略（destination-out 只吃源 α）。
     *
     * ⚠️ 烘焙是单线程顺序执行的（K6 初始化路径）⇒ ⛔ 并发 bake。
     */
    var trace: MutableList<BakeCmd>? = null

    private fun rec(op: Int, color: Int) {
        trace?.add(BakeCmd(op, color))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  雾条（原型 :1353）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 一条雾条的烘焙结果。
     *
     * @param seam  T6 判据①：生成函数在 x=0 与 x=1 处的最大逐行差（周期场恒 0）
     * @param seamBad 负样本：非整除格频 `cells+0.37` 的同一统计量（必须 ≫ 0）
     * @param step T6 判据②：接缝最大列差 / 内部最大列差（周期场 ≤ 1）
     * @param stepBad 负样本：非整除格频的同一比值（必须 ≫ 1）
     */
    class FogStripBmp(
        val w: Int,
        val h: Int,
        val px: IntArray,
        val seam: Double,
        val seamBad: Double,
        val step: Double,
        val stepBad: Double,
    )

    /**
     * 垂直 α 斜坡**烘进位图** ⇒ K6 一次带 α 的 blit 搞定，⛔ 逐帧再叠遮罩。
     *
     * ⚠️ 包络必须「地平线最浓 → 向近地面衰减」：贴底最强等于给离相机最近的物体加最多雾，
     *    会把巨影洗白；物理上雾量随**视线距离**增长，而屏幕下方对应的是近地面。
     */
    fun bakeFogStrip(w: Int, h: Int, cells: Int, seed: Int, oct: Int, maxA: Double): FogStripBmp {
        val px = IntArray(w * h)
        bakeFogStripRows(px, w, h, cells, seed, oct, maxA, 0, h)
        // ── T6 的两条判据（原型 :1376/:1387 的 cv._seam / cv._step，解析重算同一式子）──
        val f = { x: Double, yv: Double, c: Double ->
            clamp((fbm(x, yv, c, seed, oct) - 0.33) * 1.95, 0.0, 1.0) * maxA
        }
        var seam = 0.0
        var seamBad = 0.0
        var y = 0
        while (y < h) {
            val yv = y / h.toDouble()
            seam = max(seam, abs(f(1.0, yv, cells.toDouble()) - f(0.0, yv, cells.toDouble())))
            seamBad = max(seamBad, abs(f(1.0, yv, cells + 0.37) - f(0.0, yv, cells + 0.37)))
            y++
        }
        val at = { xx: Int, yy: Int -> (px[yy * w + xx] ushr 24) and 0xFF }
        var sMax = 0.0
        var inMax = 0.0
        y = 0
        while (y < h) {
            sMax = max(sMax, abs((at(0, y) - at(w - 1, y)).toDouble()))
            y++
        }
        var x = 1
        while (x < w) {
            y = 0
            while (y < h) {
                inMax = max(inMax, abs((at(x, y) - at(x - 1, y)).toDouble()))
                y++
            }
            x++
        }
        val step = sMax / max(1e-9, inMax)
        // 负样本：平铺时下一块画的是**同一张位图的列 0**，不是连续场的 f(1)
        var sbS = 0.0
        var sbN = 0.0
        y = 0
        while (y < h) {
            if (y % 4 == 0) {                          // 原型 `for (let y = 0; y < h; y += 4)`
                val yv = y / h.toDouble()
                sbS = max(sbS, abs(f(0.0, yv, cells + 0.37) - f((w - 1) / w.toDouble(), yv, cells + 0.37)))
                x = 1
                // ⚠️ 原型 `for (let x = 1; x < w; x += 4)`：从 **1** 起、步长 4
                //    （⛔ 不是 4,8,12… —— 那等于换了抽样点集，sbN 会变）
                while (x < w) {
                    sbN = max(sbN, abs(f(x / w.toDouble(), yv, cells + 0.37) - f((x - 1) / w.toDouble(), yv, cells + 0.37)))
                    x += 4
                }
            }
            y++
        }
        return FogStripBmp(w, h, px, seam, seamBad, step, sbS / max(1e-9, sbN))
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  grade（原型 :1427）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 暗角 + 胶片颗粒**合成一张全屏位图**，⛔ 各画一次（那是 2 屏铺屏）。
     *
     * 合成之所以成立：两者都能写成"盖一层带 α 的颜色" ⇒ `A = a0 + a1`、
     * `color = 255·a1/A`（颗粒为负时 color 取 0）。⛔ 把 color 写成"正白负黑、α 只取 a1"
     * —— 那样暗角会在颗粒负半区凭空消失，四角发花。
     * 颗粒用**白噪声**（h2 逐像素哈希），⛔ fbm（那是雾的频谱，叠上去只会糊不会"实"）。
     *
     * ⚠️ 原型 ⛔ 不再画"前景废墟棱线"（D31：读成一条缝，R9 改由近景建筑掠影承担）。
     *
     * @param cx 画面中心 x（原型 `layout` 的 `cx = w/2`）
     */
    fun bakeGrade(w: Int, h: Int, cx: Double, grain: Double): IntArray {
        val px = IntArray(w * h)
        bakeGradeRows(px, w, h, cx, grain, 0, h)
        return px
    }

    /**
     * 按行区间烘 grade —— 分帧烘焙的最小粒度。
     *
     * ⭐ 与整张烘**逐像素一致**（暗角半径只依赖 `w/h/cx`，颗粒是逐像素哈希 ⇒ 无跨行状态），
     * 该性质由 `TripodProgressiveBakeGateTest` G1 把门 ⇒ 渲染器可以先建全透明位图、
     * 再逐块 `setPixels`，观感只是暗角/颗粒自上而下补齐。
     *
     * ⚠️ `px` 是**这一块行带自己的**缓冲（长度恰好 `(y1-y0)·w`，写法 `px[(y-y0)·w+x]`），
     * ⛔ 不是整张画幅的缓冲 —— 那样渲染器就得为 1080p 常驻一个 8 MB 的 `IntArray`。
     * 行号仍然按**整张**算（`y` 从 `y0` 起、`h` 用真高），只有落笔位置平移。
     */
    fun bakeGradeRows(
        px: IntArray,
        w: Int,
        h: Int,
        cx: Double,
        grain: Double,
        y0: Int,
        y1: Int,
    ) {
        val r0 = min(w, h) * 0.34
        val r1 = max(w, h) * 0.72
        val ys = max(y0, 0)
        var y = ys
        val ye = min(y1, h)
        while (y < ye) {
            var x = 0
            while (x < w) {
                val vg = clamp((hypot(x - cx, y - h * 0.5) - r0) / (r1 - r0), 0.0, 1.0)
                val a0 = vg * vg * 0.42
                val a1 = abs(h2(x, y, 1000000.0, 9999) - 0.5) * 2 * grain
                val a = a0 + a1
                val c = if (a < 1e-6) 0.0 else 255.0 * a1 / a
                px[(y - ys) * w + x] = (u8(a * 255.0) shl 24) or (u8(c) shl 16) or (u8(c) shl 8) or u8(c)
                x++
            }
            y++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  建筑位图（原型 :1224）
    // ══════════════════════════════════════════════════════════════════════════

    /** 一道 destination-out 挖穿的归一化矩形（T15 的记账，⛔ 回读像素）。 */
    class CarveRec(val x: Double, val y: Double, val w: Double, val h: Double)

    /**
     * 一栋建筑的烘焙结果。
     *
     * @param inks 用过的颜色名单（T11：只能有剪影色一个）
     * @param carves 每道破口的归一化矩形（T15）
     * @param skirtFrac 雾裙高度分数（**实际落笔值**，⛔ 意图值；没落笔 = 0）
     * @param skirtA 雾裙强度（同上）
     * @param featherW 左右羽化宽度（位图自身比例；`fw < 1px` 时记 0）
     * @param featherTop 檐口羽化高度（同上）
     */
    class BuildingBmp(
        val w: Int,
        val h: Int,
        val px: IntArray,
        val inks: IntArray,
        val carves: ArrayList<CarveRec>,
        val skirtFrac: Double,
        val skirtA: Double,
        val featherW: Double,
        val featherTop: Double,
    )

    /**
     * 战损城郊剪影，**一栋一张烘焙位图**（⛔ 逐帧小矩形 = 创维 API 22 SIGSEGV 触发器）。
     *
     * ⛔ **灯**（判读轮 2 意见 2：「世界大战了，哪里还有电」）：整张位图只有一个颜色。
     * 破口、楼板缝、塌角一律走 destination-out **真挖穿**，透出的是背后的雾。
     * ⚠️ ⛔ `source-in` 收蒙版（第一版那样做 ⇒ 48 栋全成无窗无顶的实心板）。
     *
     * ⭐ r() 的抽取顺序是保真度的一部分（黄金对账逐项踩）：
     * `roof → j(0.5) → kind → [檐口 7/4/1+4n 个] → 末拍 lerp → 残桅 1/4 → 楼板缝 1/3 → 破洞 1+4·holes`。
     *
     * @param b 建筑实例（几何 + 种子）；`skirtFrac/skirtA/featherW/featherTop` 由本函数**回填**
     */
    fun bakeBuilding(b: TripodBuilding, params: TripodParams): BuildingBmp {
        val pxw = BLD_PX
        val py = (pxw * b.h / b.w).roundToInt().coerceIn(BLD_PY_MIN, BLD_PY_MAX)
        val r = TripodFogMath.Lcg(b.seed)
        val carves = ArrayList<CarveRec>()
        rec(OP_FILL, SILH_INT)

        // ── 立面轮廓（source-over 剪影色）──
        val roof = py * lerp(0.02, 0.12, r.next())
        val poly = ArrayList<Double>(24)
        poly.add(0.0); poly.add(py.toDouble())
        poly.add(0.0); poly.add(roof + jitter(py, r, 0.5))
        val kind = r.next()
        if (kind < 0.34) {                       // 退台
            val sx = pxw * lerp(0.18, 0.34, r.next())
            val ex = pxw * lerp(0.6, 0.82, r.next())
            poly.add(sx); poly.add(py * 0.05 + jitter(py, r, 1.0))
            poly.add(sx); poly.add(roof + jitter(py, r, 1.0))
            poly.add(ex); poly.add(roof + jitter(py, r, 1.0))
            poly.add(ex); poly.add(py * lerp(0.05, 0.12, r.next()) + jitter(py, r, 1.0))
        } else if (kind < 0.67) {                // 塌一角
            val bx = pxw * lerp(0.42, 0.78, r.next())
            poly.add(bx); poly.add(roof + jitter(py, r, 0.6))
            poly.add(bx + pxw * 0.07); poly.add(py * lerp(0.2, 0.42, r.next()))   // 断崖式塌口
            poly.add(pxw.toDouble()); poly.add(py * lerp(0.24, 0.5, r.next()))
        } else {                                 // 锯齿碎檐
            val n = 4 + (r.next() * 4).toInt()
            var i = 1
            while (i <= n) {
                val u = i.toDouble() / n
                poly.add(pxw * (u - 0.5 / n)); poly.add(roof + jitter(py, r, 1.0) * (0.3 + 0.7 * r.next()))
                poly.add(pxw * u); poly.add(roof * (1 - 0.4 * r.next()) + jitter(py, r, 1.0))
                i++
            }
        }
        poly.add(pxw.toDouble()); poly.add(py * lerp(0.04, 0.3, r.next()))
        poly.add(pxw.toDouble()); poly.add(py.toDouble())

        val px = IntArray(pxw * py)
        sc.ensure(pxw, py)
        polyCover(pxw, py, poly.toDoubleArray())
        run {
            val cov = sc.cov
            var i = 0
            while (i < pxw * py) {
                srcOver(px, i, cov[i], 1.0, SILH_R, SILH_G, SILH_B)
                i++
            }
        }

        // ── 残桅：还立着的半根（⛔ 单独图元；旋转矩形 = 四边形）──
        if (r.next() < 0.5) {
            val mx = pxw * lerp(0.15, 0.85, r.next())
            val mh = py * lerp(0.05, 0.16, r.next())
            val th = (r.next() - 0.5) * 0.34
            val c = cos(th)
            val s = sin(th)
            val lx0 = -pxw * 0.008
            val lx1 = pxw * 0.008
            val ly0 = -mh
            val ly1 = 0.0
            val q = DoubleArray(8)
            quad(q, 0, lx0, ly0, mx, roof, c, s)
            quad(q, 1, lx1, ly0, mx, roof, c, s)
            quad(q, 2, lx1, ly1, mx, roof, c, s)
            quad(q, 3, lx0, ly1, mx, roof, c, s)
            rec(OP_FILL, SILH_INT)
            polyCover(pxw, py, q)
            val cov = sc.cov
            var i = 0
            while (i < pxw * py) {
                srcOver(px, i, cov[i], 1.0, SILH_R, SILH_G, SILH_B)
                i++
            }
        }

        // ── 挖穿层：楼板缝 + 破洞（destination-out，⛔ 上色）──
        rec(OP_CLEAR, 0)
        val rows = max(3, (py / 15.0).roundToInt())
        var k = 1
        while (k < rows) {
            if (r.next() < Carve.seamSkip) {
                k++
                continue
            }
            val y = py * k / rows.toDouble()
            val h0 = max(1.0, py / rows.toDouble() * Carve.seamH)
            val wf = lerp(Carve.seamW0, Carve.seamW1, r.next())
            val x0 = pxw * lerp(Carve.edge, 1 - Carve.edge - wf, r.next())
            destOutRect(px, pxw, py, x0, y, x0 + pxw * wf, y + h0, 1.0)
            carves.add(CarveRec(x0 / pxw, y / py, wf, h0 / py))
            k++
        }
        val holes = 1 + (r.next() * 3).toInt()
        var hk = 0
        while (hk < holes) {                     // 炸穿的洞：围绕随机中心的**局部**四边形
            val hw = pxw * lerp(Carve.holeW0, Carve.holeW1, r.next())
            val hh = py * lerp(Carve.holeH0, Carve.holeH1, r.next())
            val hcx = pxw * lerp(0.15, 0.85, r.next())
            val hcy = py * lerp(0.2, 0.9, r.next())
            val q = doubleArrayOf(
                hcx - hw * 0.5, hcy - hh * 0.4,
                hcx + hw * 0.5, hcy - hh * 0.5,
                hcx + hw * 0.35, hcy + hh * 0.5,
                hcx - hw * 0.4, hcy + hh * 0.35,
            )
            sc.ensure(pxw, py)
            polyCover(pxw, py, q)
            val cov = sc.cov
            var i = 0
            while (i < pxw * py) {
                val ad = ((px[i] ushr 24) and 0xFF) / 255.0
                val outA = ad * (1 - cov[i])
                px[i] = if (outA <= 0.0) 0 else (px[i] and 0x00FFFFFF) or (u8(outA * 255.0) shl 24)
                i++
            }
            carves.add(CarveRec((hcx - hw * 0.5) / pxw, (hcy - hh * 0.5) / py, hw / pxw, hh / py))
            hk++
        }

        // ── R19 贴地雾裙：世界系固定厚度换算成**这一栋自己的**位图高度分数 ──
        val skFrac = clamp(params.skirtH / b.h, 0.0, 0.85)
        val skPx = py * skFrac
        val skOn = skPx >= 1 && params.skirtA > 0.001
        if (skOn) {
            destOutGrad(px, pxw, py, 0.0, py - skPx, pxw.toDouble(), py.toDouble(),
                0.0, py.toDouble(), 0.0, py - skPx, params.skirtA, 0.0)
            rec(OP_CLEAR, SILH_INT)
        }
        // 记录的是**实际被擦掉的那一段**：⛔ 记成"意图值"（闸门会绿、画面上什么都没有）
        b.skirtFrac = if (skOn) skFrac else 0.0
        b.skirtA = if (skOn) params.skirtA else 0.0

        // ── R19b 边缘羽化（三次 destination-out 渐变，⛔ 上色 ⇒ 不进 inks）──
        val fw = pxw * Feather.w
        val tw = py * Feather.top
        if (Feather.w > 0 && fw >= 1) {
            destOutGrad(px, pxw, py, 0.0, 0.0, fw, py.toDouble(),
                0.0, 0.0, fw, 0.0, 1.0, 0.0)
            destOutGrad(px, pxw, py, pxw - fw, 0.0, pxw.toDouble(), py.toDouble(),
                pxw - fw, 0.0, pxw.toDouble(), 0.0, 0.0, 1.0)
            rec(OP_CLEAR, 0)
        }
        if (Feather.top > 0 && tw >= 1) {
            destOutGrad(px, pxw, py, 0.0, 0.0, pxw.toDouble(), tw,
                0.0, 0.0, 0.0, tw, 1.0, 0.0)
            rec(OP_CLEAR, 0)
        }
        b.featherW = if (fw >= 1) Feather.w else 0.0
        b.featherTop = if (tw >= 1) Feather.top else 0.0

        return BuildingBmp(
            pxw, py, px, intArrayOf(SILH_INT), carves,
            b.skirtFrac, b.skirtA, b.featherW, b.featherTop,
        )
    }

    /** 原型 `const j = (k) => py * lerp(0, 0.16, r()) * k`（檐口抖动量）。 */
    private fun jitter(py: Int, r: TripodFogMath.Lcg, k: Double): Double =
        py * lerp(0.0, 0.16, r.next()) * k

    /** `Math.pow(v, 0.6)`（原型楼体横向锚点的幂律；⛔ 手写快速幂 —— 累乘误差会进对账）。 */
    private fun pow06(v: Double): Double = v.pow(0.6)

    /** 旋转矩形的第 [idx] 个角写进 [q]（原型 `translate(mx,roof) → rotate(θ) → fillRect`）。 */
    private fun quad(q: DoubleArray, idx: Int, lx: Double, ly: Double, mx: Double, roof: Double, c: Double, s: Double) {
        q[2 * idx] = mx + lx * c - ly * s
        q[2 * idx + 1] = roof + lx * s + ly * c
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  人群姿态位图（原型 :1129）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 一张人群姿态位图 + 它的**高度修正**（原型 `cv._bh`）。
     *
     * ⚠️ `_bh` 必须随位图走：drawCrowd 的 `hp = personH·bmp._bh·PPM·s` 读它，
     *    ⛔ 在绘制侧另存一份（那是 E44 D24「表与几何各说各话」的同一条错）。
     */
    class PersonBmp(val px: IntArray, val bh: Double)

    /**
     * 四种姿态：0 站立仰头 / 1 奔跑 / 2 蹲伏抱头 / 3 被拎起（四肢朝上乱舞）。
     *
     * 栅格化 = 圆头笔画的**胶囊距离场**（线段 ± 1.5 px 的并集）+ 头部圆盘，4×4 子采样。
     * ⚠️ canvas 的 stroke 有抗锯齿，本文件用二值子采样近似 —— 位图会被缩到约 14 px 高
     * （34 → 14），亚像素差不可见（见文件头「两处有意差异」）。
     */
    fun bakePerson(kind: Int): PersonBmp {
        val w = PERSON_W
        val h = PERSON_H
        val px = IntArray(w * h)
        val segs = ArrayList<Double>(32) // x0,y0,x1,y1 四元组
        val bh: Double
        if (kind == 0) {                       // 站立仰头（看见巨影的那一刻）
            seg(segs, 7.0, 9.0, 7.0, 19.0); seg(segs, 7.0, 12.0, 3.0, 15.5); seg(segs, 7.0, 12.0, 11.0, 15.5)
            seg(segs, 7.0, 19.0, 4.5, 32.0); seg(segs, 7.0, 19.0, 9.5, 32.0)
            head(segs, 7.0, 6.0)
            bh = 1.00
        } else if (kind == 1) {                // 奔跑（四肢相位错开，⛔ 对称"士兵步"）
            seg(segs, 8.2, 10.0, 6.2, 19.0); seg(segs, 8.0, 12.5, 12.2, 9.5); seg(segs, 8.0, 12.5, 3.2, 15.0)
            seg(segs, 6.2, 19.0, 11.0, 25.0); seg(segs, 11.0, 25.0, 9.5, 32.0)
            seg(segs, 6.2, 19.0, 2.6, 24.5); seg(segs, 2.6, 24.5, 4.6, 31.0)
            head(segs, 8.6, 7.0)
            bh = 1.00
        } else if (kind == 2) {                // 蹲伏抱头（趴在地面上的那种剪影）
            seg(segs, 8.0, 15.0, 5.4, 22.0); seg(segs, 8.0, 16.5, 12.0, 13.0); seg(segs, 8.0, 16.5, 11.4, 19.5)
            seg(segs, 5.4, 22.0, 10.4, 24.5); seg(segs, 10.4, 24.5, 8.4, 30.5)
            head(segs, 8.8, 12.0)
            bh = 0.70
        } else {                               // 被拎起：四肢朝上方乱舞
            seg(segs, 7.0, 11.0, 7.0, 21.0); seg(segs, 7.0, 13.0, 2.4, 6.5); seg(segs, 7.0, 13.0, 11.6, 7.5)
            seg(segs, 7.0, 21.0, 3.2, 28.0); seg(segs, 7.0, 21.0, 10.8, 27.5)
            head(segs, 7.0, 8.0)
            bh = 1.06
        }
        rec(OP_FILL, SILH_INT)
        val half = PERSON_LW * 0.5
        val n = segs.size / 4
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                var hit = 0
                var sy = 0
                while (sy < 4) {
                    val yy = y + (sy + 0.5) * 0.25
                    var sx = 0
                    while (sx < 4) {
                        val xx = x + (sx + 0.5) * 0.25
                        var s = 0
                        while (s < n) {
                            if (capsuleD(xx, yy, segs[4 * s], segs[4 * s + 1], segs[4 * s + 2], segs[4 * s + 3], half) <= 0.0) {
                                hit++
                                break
                            }
                            s++
                        }
                        sx++
                    }
                    sy++
                }
                if (hit > 0) px[y * w + x] = (u8(hit / 16.0 * 255.0) shl 24) or (SILH_R shl 16) or (SILH_G shl 8) or SILH_B
                x++
            }
            y++
        }
        return PersonBmp(px, bh)
    }

    /** 一段圆头笔画（原型 `seg` = `beginPath/moveTo/lineTo/stroke`）。 */
    private fun seg(segs: ArrayList<Double>, x0: Double, y0: Double, x1: Double, y1: Double) {
        segs.add(x0); segs.add(y0); segs.add(x1); segs.add(y1)
    }

    /** 头部圆盘 = 零长胶囊（原型 `arc(x, y, 3.0)` + fill）。 */
    private fun head(segs: ArrayList<Double>, x: Double, y: Double) {
        segs.add(x); segs.add(y); segs.add(x); segs.add(y)
    }

    /** 点到圆头线段（胶囊）的有符号距离（≤ 0 = 在笔画内）。 */
    private fun capsuleD(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double, half: Double): Double {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        var t = 0.0
        if (len2 > 0.0) {
            t = ((px - x0) * dx + (py - y0) * dy) / len2
            if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        }
        val cx = x0 + dx * t
        val cy = y0 + dy * t
        return hypot(px - cx, py - cy) - half
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  genBuildings（原型 :1005 的**生成**半边；选取在 K4）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 两带建筑实例表（原型 `bMid = mk(BAND.mid); bNear = mk(BAND.near)`）。
     *
     * ⭐ r() 的抽取顺序是保真度的一部分：`side → w → h → x → z0 → dep → seed → k`。
     *    挪一个 = 整条天际线换一批楼，而 `scene.*` 对账的 bld 段全部对不上。
     * ⭐ **两条带共用同一支 `r`**（原型 `genBuildings()` 在函数体里建一次 `lcg(20261011)`，
     *    `mk()` 是它的闭包）。⛔ 每条带各建一支 —— 那会让近景带从第一个数就换一批种子，
     *    整条近景天际线（侧向 x、深度 z0、周期 k、烘焙 seed）全变，而**画面里看不出来**：
     *    近景带仍然"有楼"，只是楼不是那一栋。这条由 T14 的负样本咬出来（D19 的近景带
     *    位置前提「最近那栋 x≈91 m」在错表上读到 x≈157 m ⇒ 前景压不过主体 ⇒ 假绿负样本）。
     * ⚠️ `z0 = r()·GTOT` ⇒ **必须在 buildCum 之后**调（boot 顺序：layout → buildCum →
     *    setGrabKnots → syncRouteEpoch(true) → genBuildings → bakeAll）。
     *
     * @param params 读 `skirtH/skirtA`（烘焙输入 ⇒ 改它们要重烘）
     */
    fun genBuildings(world: TripodFogWorld, params: TripodParams, bake: Boolean = true) {
        val r = TripodFogMath.Lcg(20261011)
        world.bMid = mk(world, world.bandMid, params, r, bake)
        world.bNear = mk(world, world.bandNear, params, r, bake)
    }

    private fun mk(
        world: TripodFogWorld,
        cfg: TripodBandCfg,
        params: TripodParams,
        r: TripodFogMath.Lcg,
        bake: Boolean,
    ): Array<TripodBuilding> {
        val arr = Array(cfg.n) { TripodBuilding() }
        var i = 0
        while (i < cfg.n) {
            val side = if (r.next() < 0.5) -1 else 1
            val w = lerp(cfg.wLo, cfg.wHi, r.next())
            val h = lerp(cfg.hLo, cfg.hHi, r.next())
            val b = arr[i]
            b.i = i
            b.pool = arr
            b.x = side * lerp(cfg.xLo, cfg.xHalf, pow06(r.next()))
            b.z0 = r.next() * world.gtot
            b.w = w
            b.h = h
            b.dep = lerp(14.0, 34.0, r.next())
            b.seed = (r.next() * 1e9).toInt()
            b.k = 1 + floor(r.next() * 3.0).toInt()
            if (bake) b.bmp = bakeBuilding(b, params) // 位图 + skirt/feather 回填（原型 `b.bmp = bakeBuilding(b)`）
            i++
        }
        return arr
    }

    /** 便捷：一次烘齐雾条 + 人群 + grade（原型 `bakeAll` 的建筑半边由 [genBuildings] 负责）。 */
    fun bakeFogStrips(): Pair<FogStripBmp, FogStripBmp> =
        bakeFogStrip(STRIP_A_W, STRIP_A_H, STRIP_A_CELLS, STRIP_A_SEED, STRIP_OCT, 1.0) to
            bakeFogStrip(STRIP_B_W, STRIP_B_H, STRIP_B_CELLS, STRIP_B_SEED, STRIP_OCT, 1.0)

    fun bakePersons(): Array<PersonBmp> = Array(4) { bakePerson(it) }

    /**
     * 按行区间烘一条雾带 —— 分帧烘焙的最小粒度。
     *
     * ⭐ [bakeFogStrip] 的**同一段式子**（`hy = h·0.62` 的垂直包络逐行独立、`fbm` 逐像素无状态）
     * ⇒ 与整张烘逐像素一致，由 `TripodProgressiveBakeGateTest` G2 把门。
     * ⚠️ 打包口径必须与整张路径逐字节相同：原来是「先写 `Uint8` 四通道 `d`、再压回 ARGB」，
     * 这里直接把 α 放到 24 位，两者都是 `(u8(a·255) << 24) | R<<16 | G<<8 | B`。
     *
     * ⚠️ 与 [bakeGradeRows] 同一条缓冲口径：`px` 是**这一块行带**的缓冲（长度 `(y1-y0)·w`），
     * 行号仍按整张算（垂直包络 `hy` 用的是真高 `h`）。
     */
    fun bakeFogStripRows(
        px: IntArray,
        w: Int,
        h: Int,
        cells: Int,
        seed: Int,
        oct: Int,
        maxA: Double,
        y0: Int,
        y1: Int,
    ) {
        val hy = h * 0.62
        val ys = max(y0, 0)
        var y = ys
        val ye = min(y1, h)
        while (y < ye) {
            val ramp = if (y < hy) lerp(0.55, 1.0, y / hy)
            else lerp(1.0, 0.22, smoothstep(hy, h.toDouble(), y.toDouble()))
            val yv = y / h.toDouble()
            var x = 0
            while (x < w) {
                val a = clamp(
                    (fbm(x / w.toDouble(), yv, cells.toDouble(), seed, oct) - 0.33) * 1.95,
                    0.0,
                    1.0,
                ) * ramp * maxA
                px[(y - ys) * w + x] = (u8(a * 255.0) shl 24) or (FOG_R shl 16) or (FOG_G shl 8) or FOG_B
                x++
            }
            y++
        }
    }
}

/** [TripodBakeQueue] 的单元类型（顺序即队列顺序：雾 → 人群 → 楼 → grade）。 */
internal object TripodBakeKind {
    const val STRIP_A = 0
    const val STRIP_B = 1
    const val PERSONS = 2
    const val BUILDING = 3
    const val GRADE = 4
}

/**
 * 一个待烘单元。
 *
 * @param kind [TripodBakeKind]
 * @param a 行块的起始行（雾条 / grade）或楼的扁平下标（[TripodBakeKind.BUILDING]，⛔ 行号）
 * @param b 行块的**开区间**上界；一栋楼为 `a + 1`；人群恒 [TripodSlot.PERSON_N]
 */
internal class TripodBakeUnit(val kind: Int, val a: Int, val b: Int)

/**
 * E46 首帧之后的**分帧烘焙队列**（纯 Kotlin：只排队「烘什么」，⛔ 不碰 `Bitmap`）。
 *
 * ## 为什么存在
 * 进入效果的旧路径把 48 栋楼位图 + 2 张雾条 + 4 张人群 + 一整张全屏暗角颗粒压在**同一帧**
 * 同步烘完（本机逐阶段普查 ≈ 2.77 M px，其中 grade 独占 2.07 M @1080p）。桌面 JVM 约 290 ms
 * 已经是「切换时上一个画面钉在屏幕上」，创维 API 22 慢一个量级 ⇒ 以**秒**计，
 * 而这期间 Compose 的 draw 块一帧都没提交 ⇒ 冷进入就是黑屏。
 *
 * ## 排法（照 E43 沙纹理 / E44 月盘的先例）
 * - **雾最先**：观众进场第一眼要看到的就是雾，两张雾条切成行块排在最前；
 * - 人群一个单元（4×14×34 px，不值得再拆）；
 * - **逐栋**楼（一栋的几何与烘焙是原子的，不能再拆；最坏 96×320 = 30720 px）；
 * - **grade 最后**：全屏暗角/颗粒是**覆盖层**，缺失时画面依然完整，最不该挤掉前景素材的帧预算。
 *
 * ⛔ 单元顺序即收敛顺序，⛔ 不要改成按"最耗时的先烘"。
 *
 * @param midCount [TripodFogWorld.bandMid] 的栋数（先中带）
 * @param nearCount [TripodFogWorld.bandNear] 的栋数（后近带）⇒ 扁平下标 `0 until midCount+nearCount`
 */
internal class TripodBakeQueue(
    stripAW: Int,
    stripAH: Int,
    stripBW: Int,
    stripBH: Int,
    midCount: Int,
    nearCount: Int,
    gradeW: Int,
    gradeH: Int,
) {
    /** 已排好的单元，顺序 = 消费顺序（闸门直接遍历它判顺序与像素上界）。 */
    val units = ArrayList<TripodBakeUnit>()

    private var cursor = 0

    /** 队列是否已排空（⛔ 判"素材是否齐"请用 `done`，⛔ 不要判位图非空）。 */
    val done: Boolean get() = cursor >= units.size

    init {
        // 非零的雾条尺寸 = 「整场景重排」；gradeOnly 传 0 ⇒ 只排 grade。
        val scene = stripAH > 0 && midCount > 0
        if (scene) {
            addRowUnits(TripodBakeKind.STRIP_A, stripAW, stripAH)
            addRowUnits(TripodBakeKind.STRIP_B, stripBW, stripBH)
            units.add(TripodBakeUnit(TripodBakeKind.PERSONS, 0, TripodSlot.PERSON_N))
            var i = 0
            while (i < midCount + nearCount) {
                units.add(TripodBakeUnit(TripodBakeKind.BUILDING, i, i + 1))
                i++
            }
        }
        if (gradeH > 0) addRowUnits(TripodBakeKind.GRADE, gradeW, gradeH)
    }

    /** 取下一个待烘单元；排空返回 null（渲染器据此停止计时）。 */
    fun next(): TripodBakeUnit? = if (cursor >= units.size) null else units[cursor++]

    /**
     * 画幅变化时的**换代队列**：尚未消费的场景单元原样带过去（⛔ 不重烘、⛔ 不丢），
     * grade 部分按新画幅重排。
     *
     * ⛔ 不要图省事直接 `gradeOnly(w, h)` —— 那会把「场景还没烘完」的楼与雾一起从队列里抹掉，
     * 而它们的 `bmp` 永远是 null ⇒ 画面缺一块且**再也长不回来**。
     */
    fun requeueGrade(w: Int, h: Int): TripodBakeQueue {
        val q = TripodBakeQueue(0, 0, 0, 0, 0, 0, w, h)
        val scene = ArrayList<TripodBakeUnit>(units.size - cursor)
        var i = cursor
        while (i < units.size) {
            val u = units[i]
            if (u.kind != TripodBakeKind.GRADE) scene.add(u)
            i++
        }
        scene.addAll(q.units)
        q.units.clear()
        q.units.addAll(scene)
        return q
    }

    /** 按 [TripodFogBake.BAKE_UNIT_PX_CAP] 把一张 h 行的图切成连续、不重叠、铺满的行块。 */
    private fun addRowUnits(kind: Int, w: Int, h: Int) {
        val rows = max(1, TripodFogBake.BAKE_UNIT_PX_CAP / max(1, w))
        var a = 0
        while (a < h) {
            val b = min(a + rows, h)
            units.add(TripodBakeUnit(kind, a, b))
            a = b
        }
    }

    companion object {
        /**
         * 画幅变化 ⇒ **只**重排 grade。
         *
         * 暗角半径是绝对像素（§十一(d)）⇒ 换画幅必须重烘；但楼与雾条与画幅无关，
         * 整场景重排会让观众看到已经就位的素材**凭空消失再逐帧长回来**。
         */
        fun gradeOnly(w: Int, h: Int): TripodBakeQueue = TripodBakeQueue(0, 0, 0, 0, 0, 0, w, h)
    }
}
