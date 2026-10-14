package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * E47「雪松」的人物 —— 原型 §4b/§4.5 的**站定手工剪影**（1:1 移植）。
 *
 * ## 所有者裁决（两轮，都不可回退）
 * 1. 「人画的太简单了，如果没法优化真实的话，那就用固定的剪影」⇒ 手工轮廓，⛔ 程序化火柴人。
 * 2. 「还是一个小人在走动，应该不用走动，固定剪影啊」⇒ **完全静止**，⛔ 位移、⛔ 步态相位、
 *    ⛔ 脚印（同轮裁决「彻底去掉脚印」）；逐帧唯一变化的外观是**肩上的积雪**。
 * 3. 「人要跟树在一个水平线上」⇒ 脚线 = 树基线，见 [SnowCedarLayout.personY]（不是参数）。
 *
 * ## 坐标系
 * 归一化点表：`y` 0 = 帽顶 … 1 = 鞋底，`x` 以身高 `hh` 为单位，**+x = 面向方向**（朝树，
 * 由 [STAND] 与构图 `personX < treeX` 决定 ⇒ 出图时 `sgn` 恒 +1）。像素坐标只在
 * [limbPts]/[diskPts] 里由 `cx + x·hh·sgn`、`top + y·hh` 换算一次。
 *
 * ## 为什么画的是"多边形"而不是"描边线"
 * 每段肢体 = **有厚度的锥形多边形**（大衣/雪裤的鼓胀轮廓），再用 round-join 同色描边融接
 * ⇒ 烘完是一整块**实心无接缝**剪影。线宽在真实尺度（hh≈81 px）上会读成火柴。
 *
 * ⚠️ 各部件 alpha **恒 1.00**：0.90/0.96/0.97 混用会在重叠处叠出更深一档的色块，
 * 真实尺度上读成"身上挂着破布" —— 那是第一轮返工观感没变的真因（P23）。
 *
 * ## ⭐ 一份几何，两处消费
 * [poseParts] 同时是**烘焙的顶点源**和 [personSil] 的**测量源**（原型 `PK` 采集口径）。
 * ⛔ 另写一份面积公式：那两份必然漂移，而 T11 的七条结构判据吃的就是这份顶点。
 * 圆/椭圆统一用 **12 边近似**（面积比真椭圆小 4.5%；hh=81 px 时最大偏离 0.14 px ⇒ 肉眼不可见，
 * 但黄金对账两边同口径）。
 */
internal object SnowPerson {

    // ── 样式（决定 K3 用哪支 Paint 落笔）────────────────────────────────
    const val PART_SHADOW = 0     // 接触暗影：浅色系，⛔ 不计入剪影体量
    const val PART_INK = 1        // 深色本体：fill + round-join 同色 stroke
    const val PART_SNOW = 2       // 肩雪/帽雪：浅色 fill only，⛔ 不描边

    /** `rgba(40,44,51,1.00)` —— 剪影本体，alpha 恒 1（见类头 P23 红线）。 */
    const val INK_COLOR = 0xFF282C33.toInt()

    /** `rgba(150,162,178,0.26)` —— 脚下一条整片压住的接触暗影。 */
    const val SHADOW_COLOR = 0x4296A2B2.toInt()

    /** `rgba(248,251,255,0.82)` —— 积雪层（另一张位图，逐帧按 dust 淡入）。 */
    const val SNOW_COLOR = 0xD1F8FBFF.toInt()

    /** 融接描边宽度：`max(0.6, hh·0.022·inkK·swMul)`。 */
    const val STROKE_UNIT = 0.022
    const val STROKE_MIN = 0.6

    // ── 点表（照参考图量，⛔ 凭手感）───────────────────────────────────
    /** 下巴 / 肩线 / 腰 / 衣摆（占身高）⇒ 6.9 头身、**是夹克不是长大衣**。 */
    const val SH = 0.192
    const val HIP = 0.420
    const val HEM = 0.510
    const val CHIN = 0.145

    /**
     * 躯干中轴半宽（颈→肩→胸→腰→摆）。
     * ⚠️ 肩关节**只给 0.070**：躯干多边形在关节处的端面恒垂直于中轴，而中轴在肩段几乎竖直
     * ⇒ 半宽 0.112 会在肩上留一条**水平平板**（上一版读成防弹衣的真因）。真正的斜肩由 [YOKE] 承担。
     */
    val COAT_W = doubleArrayOf(0.032, 0.070, 0.106, 0.090, 0.114)

    /** 肩线（yoke）：一条从领根斜向肩峰的带，左右对称、⛔ 分两片（分片在颈各留一个台阶）。 */
    val YOKE = arrayOf(
        doubleArrayOf(-0.104, 0.206), doubleArrayOf(-0.055, 0.190), doubleArrayOf(0.000, 0.184),
        doubleArrayOf(0.055, 0.190), doubleArrayOf(0.104, 0.206),
    )
    val YOKE_W = doubleArrayOf(0.013, 0.015, 0.012, 0.015, 0.013)

    /** 腿（髋→膝→踝，长裤直筒略收）。 */
    val LEG_W = doubleArrayOf(0.048, 0.042, 0.032)

    /** ⭐ 手臂**垂在大衣外侧**：肘部外沿 0.156 > 同高胸侧 0.116 ⇒ 才读得出"有胳膊"。 */
    val ARM_W = doubleArrayOf(0.030, 0.028, 0.022)

    /** 手杖：真杖径 ≈0.011，细件另吃 `swMul 0.30` 收描边，否则被融接描边加到 2.2 倍 ⇒ 读成木板。 */
    val CANE_W = doubleArrayOf(0.010, 0.007)

    const val BOOT_TOE = 0.055
    const val BOOT_HEEL = 0.030
    const val BOOT_H = 0.026
    const val BOOT_W = 0.040

    /** 唯一姿态：站立拄杖、面向树。 */
    val STAND = SnowPose(
        lean = 0.004,
        scarf = -0.030,
        // 腿的上关节取在腰线（0.430 < 衣摆 0.510）⇒ 藏在夹克里面；⛔ 取在摆下方会在身侧留一个洞
        R = arrayOf(doubleArrayOf(0.042, 0.430), doubleArrayOf(0.058, 0.755), doubleArrayOf(0.066, 0.954)),
        L = arrayOf(doubleArrayOf(-0.042, 0.430), doubleArrayOf(-0.058, 0.755), doubleArrayOf(-0.064, 0.954)),
        // 肩关节 x 取在肩峰正下方（解剖肩 0.117 − 上臂 0.030 = 0.087）⇒ 外轮廓从斜肩一路顺到手臂；
        // 近侧臂**斜下去扶杖**：垂直时手臂内沿整个嵌进大衣 ⇒ 上身读成一口钟。
        aR = arrayOf(doubleArrayOf(0.087, 0.236), doubleArrayOf(0.128, 0.380), doubleArrayOf(0.158, 0.500)),
        aL = arrayOf(doubleArrayOf(-0.087, 0.236), doubleArrayOf(-0.122, 0.372), doubleArrayOf(-0.118, 0.492)),
        // 杖：柄压在近侧手套底下，杖尖与两靴底同一条线，整条落在右腿外侧（⛔ 与腿重叠 ⇒ 三条腿）
        cane = arrayOf(doubleArrayOf(0.168, 0.524), doubleArrayOf(0.206, 0.9985)),
    )

    /** 围巾飘向**身后**（顺风）；⛔ 飘向前方读成"没风"。 */
    const val SCARF_W = 0.026

    /** 头颈中轴朝面向侧的偏移（参考图是"低头望树"）：原型 `0.010 + lean·0.6`，三处共用。 */
    fun headOffset(lean: Double): Double = 0.010 + lean * 0.6

    // ── 派生几何（⛔ 手抄：这些是原型点名的"两处各写一份会漂移"的函数）──
    /** 夹克中轴五关节（颈→肩→胸→腰→摆）：[poseParts] 与 T11 的结构判据**共用**。 */
    fun coatSpine(pose: SnowPose): Array<DoubleArray> {
        val ln = pose.lean
        val ex = headOffset(ln)
        return arrayOf(
            doubleArrayOf(ex, CHIN), doubleArrayOf(0.004 + ln, SH), doubleArrayOf(0.012 + ln * 0.7, 0.345),
            doubleArrayOf(0.006 + ln * 0.4, HIP), doubleArrayOf(0.002 + ln * 0.2, HEM),
        )
    }

    /**
     * 肩线**上沿** = [YOKE] 各关节法向上侧的连线（与 [limbPts] 同一套法向公式现算，⛔ 手抄 y 值）。
     *
     * ⭐ 两个用途：① T11 的"可见颈长 / 解剖肩宽"从这里读；② 肩雪压在这条线上。
     * 上一版把肩雪写死在 `sh − 0.046`，而 `sh` 后来改到 0.210 ⇒ 那正好是下颌前面 ⇒ 白围脖糊脸（P25）。
     * 由本函数导出后，改 `YOKE` / `YOKE_W` / `SH` 都**不可能**把雪飘到脸上。
     */
    fun shoulderTop(pose: SnowPose): Array<DoubleArray> {
        val s = YOKE
        val ln = pose.lean
        val out = Array(s.size) { doubleArrayOf(0.0, 0.0) }
        for (i in s.indices) {
            val a = s[max(0, i - 1)]
            val b = s[minOf(s.size - 1, i + 1)]
            val p = s[i]
            var dx = b[0] - a[0]
            var dy = b[1] - a[1]
            val dl = if (SnowCedarMath.hypot(dx, dy) > 0.0) SnowCedarMath.hypot(dx, dy) else 1.0
            dx /= dl
            dy /= dl
            val w = YOKE_W[i]
            val ux = p[0] - dy * w + ln
            val uy = p[1] + dx * w
            val dxn = p[0] + dy * w + ln
            val dyn = p[1] - dx * w
            out[i] = if (uy <= dyn) doubleArrayOf(ux, uy) else doubleArrayOf(dxn, dyn)
        }
        return out
    }

    /** 解剖肩半宽（`|yoke x| + 半宽`的最大值）。⛔ 吃 [COAT_W]`[1]` —— 那只是躯干平板的半宽。 */
    fun shoulderHalf(): Double {
        var m = 0.0
        for (i in YOKE.indices) m = max(m, abs(YOKE[i][0]) + YOKE_W[i])
        return m
    }

    /** 靴底下沿（归一化 y）：T12 与 [sizeFor] 都调本函数，⛔ 各写一份。 */
    fun soleFrac(ankleY: Double): Double = ankleY + BOOT_H * 0.55 + BOOT_W * 0.78

    // ── 顶点发射器 ─────────────────────────────────────────────────────
    /**
     * 一段肢体 = 锥形多边形（左沿 0→n−1，再右沿 n−1→0 闭合）。
     * 返回**扁平** `[x0,y0,x1,y1,…]`，共 `2·2n` 个顶点分量。
     */
    fun limbPts(
        pts: Array<DoubleArray>, ws: DoubleArray,
        cx: Double, top: Double, hh: Double, sgn: Double, k: Double,
    ): DoubleArray {
        val n = pts.size
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        for (i in 0 until n) {
            px[i] = cx + pts[i][0] * hh * sgn
            py[i] = top + pts[i][1] * hh
        }
        val nx = DoubleArray(n)
        val ny = DoubleArray(n)
        val ww = DoubleArray(n)
        for (i in 0 until n) {
            val a = max(0, i - 1)
            val b = minOf(n - 1, i + 1)
            var dx = px[b] - px[a]
            var dy = py[b] - py[a]
            val dl = SnowCedarMath.hypot(dx, dy)
            if (dl > 0.0) {
                dx /= dl
                dy /= dl
            } else {
                dx = 0.0
                dy = 0.0
            }
            val w = ws[i] * hh * k
            // dl=0 时 `hypot||1` 的原型口径：dx/dy 归 0 ⇒ 端面退化成点，但仍占一个顶点
            nx[i] = -dy * w
            ny[i] = dx * w
        }
        val out = DoubleArray(4 * n)
        for (i in 0 until n) {
            out[2 * i] = px[i] + nx[i]
            out[2 * i + 1] = py[i] + ny[i]
        }
        for (j in n - 1 downTo 0) {
            val o = 2 * (2 * n - 1 - j)
            out[o] = px[j] - nx[j]
            out[o + 1] = py[j] - ny[j]
        }
        return out
    }

    /** 圆/椭圆部件（头、连指手套、溅雪）：12 边近似，与 [limbPts] 同一套采集口径。 */
    fun diskPts(x: Double, y: Double, rx: Double, ry: Double, rot: Double): DoubleArray {
        val cr = cos(rot)
        val sr = sin(rot)
        val out = DoubleArray(24)
        for (i in 0 until 12) {
            val a = (i / 12.0) * SnowCedarMath.TAU + rot
            val c = cos(a)
            val s = sin(a)
            out[2 * i] = x + (c * rx) * cr - (s * ry) * sr
            out[2 * i + 1] = y + (c * rx) * sr + (s * ry) * cr
        }
        return out
    }

    /** 描边宽度（`ink` 的 `lineWidth`）：⚠️ 吃 `inkK` 而**不是**几何宽度倍率（见 [SnowPart.inkK]）。 */
    fun strokeWidth(hh: Double, inkK: Double, swMul: Double): Double =
        max(STROKE_MIN, hh * STROKE_UNIT * inkK * swMul)

    /**
     * 深色剪影本体的**全部部件**（含第一条接触暗影），顺序 = 烘焙落笔顺序 = 原型 `drawPersonPose`。
     *
     * 暗影 `style = ` [PART_SHADOW]（⛔ 不计入体量），其余 18 块 `PART_INK` —— 块数由
     * [personSil] 的 `polys` 钉住。`k` 是 T11 负样本的宽度倍率（生产恒 1）。
     */
    fun poseParts(hh: Double, cx: Double, top: Double, sgn: Double, pose: SnowPose, k: Double): ArrayList<SnowPart> {
        val ln = pose.lean
        val ex = headOffset(ln)
        val out = ArrayList<SnowPart>(19)
        // ① 接触暗影：**一整条**压在两脚与杖尖之下（⛔ 三个落点各画一片 ⇒ 中间露出没压住的灰边，
        //    截图判读到的"脚下垫了块灰毯"就是这条）
        out.add(
            SnowPart(
                diskPts(cx + sgn * hh * (0.030 + ln), top + hh * 0.990, hh * 0.205, hh * 0.019, 0.0),
                PART_SHADOW,
            ),
        )
        // ② 远侧肢体（先画 ⇒ 被身体压住；靠"略窄"分前后，⛔ 不靠半透明 = P23）
        out.add(ink(limbPts(pose.L, LEG_W, cx, top, hh, sgn, k * 0.94), k))
        addBoot(out, pose.L[2][0], pose.L[2][1], cx, top, hh, sgn, k * 0.94)
        out.add(ink(limbPts(pose.aL, ARM_W, cx, top, hh, sgn, k * 0.88), k))
        // 远侧手套：⛔ 不画的话那条胳膊末端是一个平头端面 ⇒ 读成截肢（剪影里手只靠"末端比腕粗"成立）
        out.add(ink(diskPts(cx - sgn * 0.126 * hh, top + hh * 0.502, hh * 0.019, hh * 0.023, -0.2 * sgn), k))
        // ③ 夹克：五关节 + 三段宽差 ⇒ 有脖子（真因①）、不再是桶（真因③）
        out.add(ink(limbPts(coatSpine(pose), COAT_W, cx, top, hh, sgn, k), k))
        // ③b 肩线：中轴横向走 ⇒ 端面竖切 ⇒ 上沿自然斜下来（⛔ 靠躯干半宽做肩必然是平板）
        val yoke = Array(YOKE.size) { doubleArrayOf(YOKE[it][0] + ln, YOKE[it][1]) }
        out.add(ink(limbPts(yoke, YOKE_W, cx, top, hh, sgn, k), k))
        // ④ 围巾：只留一条顺风飘向身后的短尾（⛔ 长垂带/绕颈粗圈在 65 px 上会糊进下颌与大衣）
        out.add(
            ink(
                limbPts(
                    arrayOf(
                        doubleArrayOf(ex - 0.006, SH - 0.036),
                        doubleArrayOf(pose.scarf * 1.6 + ln, SH - 0.010),
                        doubleArrayOf(pose.scarf * 2.9 + ln, SH + 0.040),
                        doubleArrayOf(pose.scarf * 3.6 + ln, SH + 0.095),
                    ),
                    doubleArrayOf(SCARF_W, 0.019, 0.013, 0.008),
                    cx, top, hh, sgn, k,
                ),
                k,
            ),
        )
        // ⑤ 头：四块圆盘叠出"颅 + 下颌 + 帽檐 + 帽顶"（檐 = 颅宽；帽顶收成 0.042 且平顶）
        out.add(ink(diskPts(cx + sgn * hh * ex, top + hh * 0.074, hh * 0.048, hh * 0.052, 0.0), k))
        out.add(ink(diskPts(cx + sgn * hh * (ex + 0.003), top + hh * 0.124, hh * 0.034, hh * 0.022, 0.0), k))
        out.add(ink(diskPts(cx + sgn * hh * ex, top + hh * 0.038, hh * 0.050, hh * 0.015, 0.0), k))
        out.add(ink(diskPts(cx + sgn * hh * ex, top + hh * 0.020, hh * 0.042, hh * 0.020, 0.0), k))
        // ⑥ 近侧肢体、连指手套、手杖（全身唯一的硬斜线 ⇒ "为什么站着不动"的物理交代）
        out.add(ink(limbPts(pose.R, LEG_W, cx, top, hh, sgn, k), k))
        addBoot(out, pose.R[2][0], pose.R[2][1], cx, top, hh, sgn, k)
        out.add(ink(limbPts(pose.aR, ARM_W, cx, top, hh, sgn, k), k))
        out.add(ink(diskPts(cx + sgn * 0.164 * hh, top + hh * 0.520, hh * 0.026, hh * 0.030, 0.2 * sgn), k))
        out.add(SnowPart(limbPts(pose.cane, CANE_W, cx, top, hh, sgn, k), PART_INK, k, 0.30))
        return out
    }

    /** 靴底恒贴地（⛔ 不再有 lift；有 lift 就是滑步的另一种写法）。 */
    private fun addBoot(
        out: ArrayList<SnowPart>, ax: Double, ay: Double,
        cx: Double, top: Double, hh: Double, sgn: Double, k: Double,
    ) {
        val y = ay
        out.add(
            ink(
                limbPts(
                    arrayOf(
                        doubleArrayOf(ax - BOOT_HEEL * 0.6, y + 0.004),
                        doubleArrayOf(ax + BOOT_TOE * 0.55, y + 0.010),
                        doubleArrayOf(ax + BOOT_TOE, y + BOOT_H * 0.55),
                    ),
                    doubleArrayOf(BOOT_W, BOOT_W * 0.90, BOOT_W * 0.78),
                    cx, top, hh, sgn, k,
                ),
                k,
            ),
        )
        out.add(
            ink(
                limbPts(
                    arrayOf(doubleArrayOf(ax, y + BOOT_H * 0.1), doubleArrayOf(ax + BOOT_TOE * 0.92, y + BOOT_H * 0.78)),
                    doubleArrayOf(BOOT_W * 0.72, BOOT_W * 0.55),
                    cx, top, hh, sgn, k,
                ),
                k,
            ),
        )
    }

    private fun ink(pts: DoubleArray, inkK: Double): SnowPart = SnowPart(pts, PART_INK, inkK, 1.0)

    /**
     * 浅色**积雪层**（另一张位图，逐帧按 `dust` 淡入）。三片：帽顶、沿肩线一条不断开的带、手套背。
     *
     * ⭐ 本版真因 ⑤：上一版每片雪都**整块落在深色形体内部** ⇒ 白椭圆四周还有一圈黑 ⇒ 读成
     * "衣服上贴了块白斑"。积雪只能落在**朝上的水平面**上 ⇒ 每片中心都要**压在轮廓线上**
     * （一半露在天光里、一半盖在黑形上 ⇒ 才读得出"堆在上面"）。
     * ⛔ 左右各一片肩雪：中间必露出大衣上缘 ⇒ 读成白领/企鹅。
     * 领窝处收成细线（0.005）：那里是脖子，堆成粗条就把颈窝糊掉 ⇒ 又变回蘑菇。
     */
    fun snowParts(hh: Double, cx: Double, top: Double, sgn: Double, pose: SnowPose): ArrayList<SnowPart> {
        val ex = headOffset(pose.lean)
        val st = shoulderTop(pose)
        val band = Array(st.size) { doubleArrayOf(st[it][0], st[it][1] - 0.005) }
        return arrayListOf(
            SnowPart(diskPts(cx + sgn * hh * ex, top + hh * 0.010, hh * 0.040, hh * 0.014, 0.0), PART_SNOW),
            SnowPart(limbPts(band, doubleArrayOf(0.011, 0.014, 0.005, 0.014, 0.011), cx, top, hh, sgn, 1.0), PART_SNOW),
            SnowPart(diskPts(cx + sgn * 0.164 * hh, top + hh * 0.490, hh * 0.020, hh * 0.008, 0.0), PART_SNOW),
        )
    }

    // ── 纯几何测量（原型 `personSil`，T11 判据吃它）─────────────────────
    /**
     * ⛔ 不用像素法（`getImageData` 数 alpha）：原型的验证沙箱里 arc/ellipse 是空实现，恒读到 0。
     * 这里采集 [poseParts] **实际消费的顶点** ⇒ 与烘焙同一份几何。
     *
     * 重叠块会重复计面积，但判据吃的是**同一张点表的相对量**：`k` 缩小 ⇒ 体量近似线性下降。
     */
    fun personSil(k: Double): SnowSil {
        val hh = 160.0
        val cx0 = SnowCedarMath.jsRound(hh * 0.78) / 2.0
        val top0 = 3.0
        val parts = poseParts(hh, cx0, top0, 1.0, STAND, k)
        var area = 0.0
        var x0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        var polys = 0
        val bars = ArrayList<SnowSilBar>()
        for (part in parts) {
            if (part.style != PART_INK) continue
            polys++
            val q = part.pts
            val n = q.size / 2
            var s = 0.0
            var bx0 = Double.POSITIVE_INFINITY
            var bx1 = Double.NEGATIVE_INFINITY
            var by0 = Double.POSITIVE_INFINITY
            var by1 = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val ax = q[2 * i]
                val ay = q[2 * i + 1]
                val j = (i + 1) % n
                s += ax * q[2 * j + 1] - q[2 * j] * ay
                if (ax < bx0) bx0 = ax
                if (ax > bx1) bx1 = ax
                if (ay < by0) by0 = ay
                if (ay > by1) by1 = ay
                if (ax < x0) x0 = ax
                if (ax > x1) x1 = ax
                if (ay < y0) y0 = ay
                if (ay > y1) y1 = ay
            }
            area += kotlin.math.abs(s) / 2
            // 逐块包围盒（÷hh）：T11 用它判"剪影里有没有一根伸到身体外侧的长条"= 手杖在不在
            bars.add(
                SnowSilBar(
                    (bx0 - cx0) / hh, (bx1 - cx0) / hh, (by0 - top0) / hh, (by1 - top0) / hh,
                    (bx1 - bx0) / hh, (by1 - by0) / hh,
                ),
            )
        }
        val box = max(1e-6, (x1 - x0) * (y1 - y0))
        return SnowSil(area / (hh * hh), area / box, (x1 - x0) / hh, (y1 - y0) / hh, polys, bars)
    }

    // ── 烘焙尺寸与 blit 矩形 ───────────────────────────────────────────
    /**
     * 原型 `bakePerson()` 的**尺寸部分**：`hh = max(22, round(personH))`，位图 `ww × (hh+16)`，
     * 上沿留 8 px —— 帽顶/肩雪的中心压在轮廓线上 ⇒ **必然**露出轮廓外，不留就切掉。
     */
    fun sizeFor(l: SnowCedarLayout): SnowPersonSize {
        val hh = max(22.0, SnowCedarMath.jsRound(l.personH)).toInt()
        val ww = SnowCedarMath.jsRound(hh * 0.78).toInt()
        val top = 8
        return SnowPersonSize(ww, hh + 16, top, hh, top + hh * soleFrac(STAND.R[2][1]))
    }

    /**
     * blit 矩形：**只由布局决定**（⛔ 不吃时间、不吃位移）⇒ [SnowPersonGateTest] 的 T6 就判这个。
     * 旧版这里是行走位置 `PR.x`，是所有者两次反馈"还在走动"的直接来源。
     */
    fun rectFor(l: SnowCedarLayout, sz: SnowPersonSize): SnowBlit =
        SnowBlit(l.personX - sz.w / 2.0, l.personY - sz.soleY, sz.w, sz.h)
}

/** 一段肢体 / 一个圆盘 = 一个待落笔的部件（扁平顶点 + 样式 + 描边参数）。 */
internal class SnowPart(
    val pts: DoubleArray,
    val style: Int,
    /**
     * ⚠️ 描边倍率**可以与几何宽度倍率不同**：远侧肢体几何缩到 `k·0.94`，而 `ink` 仍吃 `k`
     * （原型 `limbPath(..., k*0.94); ink(g, hh, '1.00', k)`）—— 两处都缩会让前后腿差变成两级台阶。
     */
    val inkK: Double = 1.0,
    /** ⛔ 只给"细件"用（手杖 0.30）：融接描边每侧外扩 0.011 hh，杖的半宽只有 0.010 hh。 */
    val swMul: Double = 1.0,
)

/** 站定姿态的点表（归一化；⛔ 不是正弦驱动 ⇒ Kotlin 侧照抄同一张表就能逐点对账）。 */
internal class SnowPose(
    val lean: Double,
    val scarf: Double,
    val R: Array<DoubleArray>,
    val L: Array<DoubleArray>,
    val aR: Array<DoubleArray>,
    val aL: Array<DoubleArray>,
    val cane: Array<DoubleArray>,
)

/** 剪影测量结果（[SnowPerson.personSil]）。`mass` 单位 = `÷hh²`，`ratio` = 填框率。 */
internal class SnowSil(
    val mass: Double, val ratio: Double,
    val wFrac: Double, val hFrac: Double,
    val polys: Int, val bars: ArrayList<SnowSilBar>,
)

/** 单个部件的包围盒（÷ 身高，原点 = 身体中轴 / 帽顶）。 */
internal class SnowSilBar(
    val x0: Double, val x1: Double, val y0: Double, val y1: Double,
    val w: Double, val h: Double,
)

/** 人物位图尺寸（原型 `B.personSize`）：`soleY` = 脚线在位图内的像素 y。 */
internal class SnowPersonSize(
    val w: Int, val h: Int, val top: Int, val hh: Int, val soleY: Double,
)

/** blit 矩形（像素）。 */
internal class SnowBlit(val x: Double, val y: Double, val w: Int, val h: Int)

/**
 * 肩上积雪的**唯一逐帧状态**（原型 `PR`）。
 *
 * ⭐ 整个类只有 `dust` 一个状态量 —— 这就是 T6「人逐帧不许动」的编译期保证：
 * 想加回位移就得新增字段，而 [SnowPersonGateTest] 会当场把字段名点出来。
 *
 * 累积/抖落：无风时单调升到 1，阵风（`gust > shakeAt`）开始抖落 ⇒ 积—落循环，画面仍有时间维度。
 * ⚠️ 三个参数是**配出来的**（见 [SnowPersonP] 的注释），不是推出来的。
 */
internal class SnowPersonDust(private val p: SnowCedarP) {
    var dust = 0.0
        private set

    fun reset() {
        dust = 0.0
    }

    /** ⛔ 这里出现任何 x/phase/pose 推进都是回归。`gust` 来自 [SnowWorldState]（纯时间量）。 */
    fun step(dt: Double, gust: Double) {
        val sh = max(0.0, (gust - p.person.shakeAt) / max(1e-6, 1 - p.person.shakeAt))
        dust = SnowCedarMath.clamp(dust + dt / p.person.dustSecs - sh * sh * dt * p.person.shakeRate, 0.0, 1.0)
    }
}
