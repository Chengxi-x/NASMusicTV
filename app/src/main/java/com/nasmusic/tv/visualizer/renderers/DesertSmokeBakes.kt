package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * E48「大漠 · 孤烟」K3 —— 原型 §6 的 **14 类**一次性位图：
 * `sky` / `sun`（第六轮新增，⛔ 必须 ARGB_8888）/ `nG` 张沙丘画布 /
 * `wisp×6` / `wispWarm×6` / **`camel`（TEX 档 = 1 张，⛔ 写死 6 会白烘 5 张）** / `veil` / `grade`。
 *
 * ## 分帧口径沿用 E46/E47（所有者真机验收定稿：预算 24 ms）
 * ⛔ 压在**同一帧**同步烘完：全屏逐像素的 `grade`（第十一轮实测 41.7 ms）加上
 * `nG` 张沙丘画布在创维 API 22 上以**百毫秒**计 ⇒ 冷进入黑屏。
 * 排法三条与 E47 逐字同款：
 * - [DesertBakeQueue] 只排「烘什么」（纯 Kotlin，⛔ 不碰 `Bitmap`，因此可单测）；
 * - 行块类单元（`veil` / `grade`）按 [UNIT_PX_CAP] 切，解析式粒度 ⇒ 超调最多一个单元；
 * - **`grade` 最后**：暗角/颗粒是覆盖层，缺失时画面依然完整，最不该挤掉前景素材的帧预算。
 *
 * ⭐ 三条 K3 专属口径：
 * 1. **画布倒序烘焙（`nG−1 → 0`）**：逐帧 blit 仍是 `0 → nG−1`（远→近），而**烘焙**反过来
 *    ⇒ 首帧就是「天幕 + 日盘 + 最近 1–2 组」，前景先到位，⛔ 不是"最远的两组先出现"。
 * 2. ⭐ **真瓶颈是全屏逐像素的 `grade`**（须按行分块），⛔ 不是"单组装不进一个 24 ms 片"：
 *    同一份归档里沙丘是 `bakeGroup 35.5 ms / perGroup 5.1 ms（n=7）`、日盘 0.1 ms。
 *    ⇒ ⛔ 按 0.1 ms 给日盘设预算、也别把一组沙丘再切碎（分组本身就是最小单位）。
 * 3. `sky` 的散射半径**跟盘走**（`max(L.sunR·1.95, W·scatter)`）+ 大范围低强度天幕散射层
 *    （0.62W、α≤.30）—— ⛔ 移植时把散射改回"画幅相对"就是 P29 复发。
 */
internal class DesertSmokeBakes(
    private val p: DesertP,
    private val l: DesertLayout,
) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** `wisp` 的四次错位叠印（⛔ 与烘焙共用一支：它必须**不带**着色器）。 */
    private val copy = Paint()

    private val path = Path()
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    // ══════════════════════════════════════════════════════════════════════════
    //  天幕（原型 `bakeSky`）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 天幕：暖红竖向渐变（**按参考图实测亮度阶梯定档**）+ 落日散光（烘进本层 ⇒ 逐帧 0 成本）
     * + 极淡 tonal 条纹（⛔ 云，需求「无云」）。
     *
     * ⭐ 五个锚点 = 参考图 1 **远离日盘那一侧**沿 y 的实测 L 阶梯：
     * `.00→.185｜.10→.173（**天顶不是最亮**）｜.24→.185｜.34→.205｜.39→.231`。
     * ⛔ 上一版的 #f4ac63（L .40）：那把带2 抬到 .259（参考图 .205）＝"整片天太亮"。
     */
    fun sky(cv: Canvas) {
        val w = l.w
        val h = l.h
        if (w < 1.0 || h < 1.0) return
        val far = l.dunes[0]
        val bot = max(1.0, far.mx).toFloat()
        fill.shader = skyGradient(bot)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        fill.shader = null

        // 落日的**散光**：贴着盘缘的一圈浓红，到 1.9R 完全化进天幕。
        val sy = l.sunY
        val bl = p.sun.bloom
        val rad = max(l.sunR * 1.95, w * p.sun.scatter)
        val sx = l.sunX.toFloat()
        val syf = sy.toFloat()
        scatter(cv, sx, syf, rad.toFloat(), floatArrayOf(0f, 0.42f, 0.63f, 0.84f, 1f), intArrayOf(255, 60, 32, 255, 60, 32, 255, 62, 33, 255, 66, 35, 255, 66, 35), floatArrayOf(0.68f, 0.66f, 0.58f, 0.18f, 0f), bl)

        // ⭐⭐ 大范围低强度的天幕散射（所有者建议③，唯一能白拿的预算）：贴地平线收。
        val wide = w * 0.62
        scatter(cv, sx, (sy + h * 0.02).toFloat(), wide.toFloat(), floatArrayOf(0f, 0.30f, 0.62f, 1f), intArrayOf(255, 74, 38, 255, 80, 42, 255, 92, 50, 255, 104, 58), floatArrayOf(0.30f, 0.19f, 0.075f, 0f), bl)

        // 高空极淡横向色调（不是云：alpha ≤ skyTex，只破掉"色卡感"）。
        val rnd = DesertMath.rng(p.dune.seed xor 0x1f3)
        var i = 0
        while (i < 8) {
            val y = h * (0.02 + rnd.next() * 0.34)
            val hh = h * (0.024 + rnd.next() * 0.052)
            val a = p.fx.skyTex * (0.40 + rnd.next() * 0.60)
            fill.shader = LinearGradient(
                0f, y.toFloat(), 0f, (y + hh).toFloat(),
                intArrayOf(argb(0.0, 255, 214, 160), argb(a, 255, 214, 160), argb(0.0, 255, 214, 160)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            cv.drawRect((-w * 0.05).toFloat(), y.toFloat(), (w * 1.05).toFloat(), (y + hh).toFloat(), fill)
            i++
        }
        fill.shader = null
    }

    /**
     * 天幕**底渐变**的着色器 —— [sky]（软件烘焙路径）与 `DesertSmokeRenderer.drawSkyFallback`
     * （逐帧 GPU 兜底路径）**共用这一个构造**。
     *
     * ⛔ 单一来源不是洁癖：两条路径若各写一份 stop 表，`bot` 或色带位置一旦漂移，
     * 兜底帧 → 真天幕之间就会闪一次色差（兜底只活 1 帧，肉眼正好抓住）。
     * ⚠️ 构造有分配 ⇒ **调用方负责缓存**（烘焙路径每次 `sky()` 一次，兜底路径按 `bot` 缓存）。
     */
    fun skyGradient(bot: Float): LinearGradient = LinearGradient(
        0f, 0f, 0f, bot,
        SKY_COLORS, SKY_POS,
        Shader.TileMode.CLAMP,
    )

    /** 原型 `createRadialGradient(...)` + `fillRect(0,0,W,H)`：一条散射层。 */
    private fun scatter(
        cv: Canvas, cx: Float, cy: Float, rad: Float,
        stops: FloatArray, rgb: IntArray, alpha: FloatArray, bloom: Double,
    ) {
        val n = stops.size
        val cols = IntArray(n)
        var i = 0
        while (i < n) {
            cols[i] = argb(DesertMath.clamp(alpha[i] * bloom, 0.0, 0.95), rgb[3 * i], rgb[3 * i + 1], rgb[3 * i + 2])
            i++
        }
        fill.shader = RadialGradient(cx, cy, max(1f, rad), cols, stops, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, l.w.toFloat(), l.h.toFloat(), fill)
        fill.shader = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  日盘（原型 `bakeSun`：两次绘制 + 一次 destination-in 径向 α 掩膜）
    // ══════════════════════════════════════════════════════════════════════════

    /** 日盘贴图的边长（原型 `S = ceil(2·Re) + 2`）。 */
    fun sunSize(): Int {
        val re = l.sunR * p.sun.softK
        if (re <= 0.0) return 0
        return kotlin.math.ceil(2.0 * re).toInt() + 2
    }

    /**
     * ⭐ 为什么必须进烘焙、⛔ 留在逐帧：盘的边缘硬度 = **总落差有多少挤在多宽的半径带里**，
     * 而 Canvas 只有两种渐变，都单独做不到"竖向坡 + 径向淡出" ⇒ 唯一的组合方式是
     * **两次绘制 + 一次 `destination-in` 径向 α 掩膜**（掩膜把竖向坡在 r≥0.90R 之后整体抹成斜坡）。
     *
     * ⚠️ 基盘 stop 表**改为全程不透明**：淡出交给掩膜，⛔ 两处各淡一半（那会在 1.0R 叠成第二个拐点）。
     * ⚠️ 起坡半径 **0.94R**、归零半径 `softK·R`（gate ⑦ 的唯一旋钮）。
     */
    fun sun(cv: Canvas) {
        val r = l.sunR
        val re = r * p.sun.softK
        if (r <= 0.0 || re <= 0.0) return
        val s = sunSize()
        val c = s / 2.0
        val cf = c.toFloat()

        // ① 基盘（径向）：上冠白 → 金 → 橙 → 盘缘砖红，**全程不透明**。
        fill.shader = RadialGradient(
            cf, cf, r.toFloat(),
            intArrayOf(opaque(0xfcc358), opaque(0xfdba50), opaque(0xffb04a), opaque(0xff8f3c), opaque(0xff7530)),
            floatArrayOf(0f, 0.5f, 0.75f, 0.90f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawCircle(cf, cf, re.toFloat(), fill)

        // ② 竖向调色坡（下金上白 → 下红），画满 Re、⛔ 不再 clip 回 R。
        fill.shader = LinearGradient(
            0f, (c - r).toFloat(), 0f, (c + r).toFloat(),
            intArrayOf(
                argb(0.70, 255, 250, 150), argb(0.92, 255, 252, 152), argb(1.0, 255, 253, 152),
                argb(0.58, 255, 246, 136), argb(0.0, 255, 240, 120),
                argb(0.42, 234, 86, 46), argb(0.62, 228, 68, 38),
                argb(0.80, 218, 54, 30), argb(0.92, 204, 44, 26),
            ),
            floatArrayOf(0f, 0.16f, 0.33f, 0.44f, 0.50f, 0.56f, 0.66f, 0.80f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawCircle(cf, cf, re.toFloat(), fill)

        // ③ 径向 α 掩膜 = **这条斜坡的宽度本身**（`softK`）。
        // ⚠️ Android 的 `RadialGradient` **没有内半径**（⛔ JS 的
        // `createRadialGradient(x0,y0,r0,x1,y1,r1)` 才有）⇒ 起坡半径 `0.94R` 折进 stop 表：
        // `stp[0] = 0.94R / Re`，`CLAMP` 把内盘整段填成首个 α（与 [buildHaloShaders] 同一折算）。
        val r0 = r * 0.94
        val t0 = if (re > 0.0) r0 / re else 0.0
        val n = 9
        val cols = IntArray(n)
        val stp = FloatArray(n)
        var k = 0
        while (k < n) {
            val f = k / 8.0
            stp[k] = (t0 + (1.0 - t0) * f).toFloat()
            cols[k] = argb(1.0 - f, 255, 255, 255)
            k++
        }
        fill.xfermode = dstIn
        fill.shader = RadialGradient(cf, cf, re.toFloat(), cols, stp, Shader.TileMode.CLAMP)
        cv.drawCircle(cf, cf, re.toFloat(), fill)
        fill.xfermode = null
        fill.shader = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  沙丘画布（原型 `bakeGroup` + `bakeRune`）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 一张画布 = 一个**深度组**（`sub` 道脊线，远→近依次叠进同一张位图）。
     *
     * ⭐ 这就是裁决 ②「很多沙丘重叠」不加价的实现：组内遮挡发生在**烘焙期**，
     * 逐帧只有 1 次 blit，且画布总高度 = `Σ(下一组首脊 mx − 本组首脊 mn)` 与 sub **无关**。
     *
     * @return `(y0, bh)`：第 g 张画布的下缘 = **第 g+1 组首脊的最低点 + 余量**
     *   ⇒ 相邻画布永不漏透明缝（旧版"左下白条"的同族坑）。
     */
    fun groupBox(gi: Int): DoubleArray {
        val ab = l.groupRidges(gi)
        val nG = l.dunes[0].nG
        var y0 = 1e9
        var pad = 0.0
        var i = ab[0]
        while (i <= ab[1]) {
            val d = l.dunes[i]
            y0 = min(y0, d.mn)
            pad = max(pad, d.hazePad)
            i++
        }
        y0 = max(0.0, y0 - pad)
        var y1 = l.h * 1.02
        if (gi < nG - 1) {
            val na = l.groupRidges(gi + 1)[0]
            y1 = min(l.h * 1.02, l.dunes[na].mx + l.h * 0.018)
        }
        val bh = max(2.0, DesertMath.jsRound(y1 - y0))
        return doubleArrayOf(y0, bh)
    }

    /** 原型 `bakeGroup(gi)`：往调用方给好的 `cv`（尺寸 = `W × bh`）里落笔。 */
    fun group(cv: Canvas, gi: Int) {
        val ab = l.groupRidges(gi)
        val box = groupBox(gi)
        val y0 = box[0]
        val y1 = if (gi < l.dunes[0].nG - 1) {
            min(l.h * 1.02, l.dunes[l.groupRidges(gi + 1)[0]].mx + l.h * 0.018)
        } else {
            l.h * 1.02
        }
        cv.translate(0f, (-y0).toFloat())
        var i = ab[0]
        while (i <= ab[1]) {
            rune(cv, i, y1)
            i++
        }
        cv.translate(0f, y0.toFloat())
    }

    /**
     * 单道脊 `i`（原型 `bakeRune`）：体块 → 阳/阴坡 → 脊刃 → 大尺度调子 → 风成波纹 → 暖雾。
     *
     * ⛔⛔ 五条 E48 三轮踩出来的口径（⛔ 别按"看起来"改回去）：
     * 1. 体块渐变挂在**全沙海共用的一条竖向亮度阶梯**（`horizonY → 1.02H`）上，⛔ 逐层各挂自己那条；
     * 2. ② 的面积明暗用**宽窗**（`winS = 11%W`）与**相对高度** `hf`（⛔ `|斜率|` = 描边）；
     *    饱和必须**软**（`m/(k+m)`），⛔ `clamp(…,0,1)`（平板 + 硬边界 = 竖贴面）；
     * 3. 带宽必须**恰好等于步进**（`step + 1` ⇒ source-over 叠加出竖梳齿）；
     * 4. 脊刃只留一道**薄亮刃**，⛔ 再配一条脊下阴带（把每道脊框成"线"= 薄片观感）；
     * 5. ③/④ 只给**最近三张画布的领脊**（逐脊成本是 R 的倍数，R=18 全给就进不了首帧预算）。
     */
    private fun rune(cv: Canvas, i: Int, y1: Double) {
        val d = l.dunes[i]
        val tone = d.tone
        val w = l.w
        val h = l.h
        val step = max(2.0, DesertMath.jsRound(w / d.cols))

        // 剪影折线（体块路径与后续所有"沿脊铺色"的 pass 共用同一条）
        val pts = FloatArray((((w + 2.0 * step) / step).toInt() + 2) * 2)
        var pc = 0
        var x = -step
        pts[pc++] = x.toFloat(); pts[pc++] = l.crest(i, x).toFloat()
        while (x <= w + step) {
            x += step
            pts[pc++] = x.toFloat(); pts[pc++] = l.crest(i, x).toFloat()
        }
        path.reset()
        path.moveTo(pts[0], pts[1])
        var k = 2
        while (k + 1 < pc) {
            path.lineTo(pts[k], pts[k + 1])
            k += 2
        }
        path.lineTo((w + step).toFloat(), (y1 + h * 0.02).toFloat())
        path.lineTo((-step).toFloat(), (y1 + h * 0.02).toFloat())
        path.close()

        // ① 体块：竖向三段渐变（脊亮 → 中调 → 底部压暗），挂**全沙海共用**的亮度阶梯。
        fill.shader = LinearGradient(
            0f, l.horizonY.toFloat(), 0f, l.duneFieldBot.toFloat(),
            intArrayOf(opaque(tone.top), opaque(tone.mid), opaque(tone.bot)),
            floatArrayOf(0f, 0.18f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawPath(path, fill)
        fill.shader = null

        // 之后所有"沿脊线铺色"的 pass 一律**裁进体块路径**（溢出会糊到更远那一层）。
        cv.save()
        cv.clipPath(path)

        val win = max(step * 2.0, w * 0.02)
        val winS = max(win * 2.0, w * 0.11)
        val span = max(1.0, d.mx - d.mn)
        val litN = tone.lit
        val shaN = tone.shade
        val topRgb = tone.topRgb
        val litK = p.dune.litK
        val shadeK = p.dune.shadeK
        val aer = DesertMath.lerp(0.42, 1.00, d.t)
        val wgBias = DesertMath.lerp(0.34, -0.04, d.t)
        val grazeK = d.t.pow(3.0) * 0.26
        val rimH = max(3.0, h * (0.008 + 0.020 * d.t))

        x = 0.0
        while (x <= w) {
            val sm = (l.crest(i, x - win) + 2.0 * l.crest(i, x) + l.crest(i, x + win)) / 4.0
            val slS = (l.crest(i, x + winS) - l.crest(i, x - winS)) / (2.0 * winS)
            val u = slS * 5.5                                  // 有符号面斜率（±1 ≈ 一个"整面"）
            val mag = abs(u) / (0.30 + abs(u))                  // 软饱和：⛔ clamp
            val cy = l.crest(i, x)
            // ⭐ 面级明暗的主项是"相对高度"，⛔ "|斜率|"（线属性 = 描边）。
            val hf = DesertMath.clamp((d.mx - sm) / span, 0.0, 1.0)
            val shape = hf.pow(1.15) - 0.34
            val wg = (1.0 - u) * 0.5 - wgBias
            val wlt = 1.0 / (1.0 + exp(-4.4 * (wg - 0.5)))      // logistic 软夹位
            val graze = grazeK * wlt                            // 掠射保底（只对最近几层）
            val dir = wlt - 0.5
            val m = shape + dir * (0.42 + 0.40 * hf) + mag * 0.60 * dir + graze
            val an = abs(m)
            val soft = an / (0.34 + an)
            val pos = if (m > 0.0) 1.0 else 0.0
            val a = soft * (0.62 * litK * aer * pos + 0.80 * shadeK * aer * (1.0 - pos))
            val wl = 1.0 / (1.0 + exp(-7.0 * m))
            val cr = DesertMath.jsRound(litN[0] * wl + shaN[0] * (1.0 - wl)).toInt()
            val cg = DesertMath.jsRound(litN[1] * wl + shaN[1] * (1.0 - wl)).toInt()
            val cb = DesertMath.jsRound(litN[2] * wl + shaN[2] * (1.0 - wl)).toInt()
            val flank = max(h * 0.014, (y1 - cy) * d.bandK)      // 该列**整条山坡**
            val flankS = flank * 0.78                           // 阴坡只铺大半条山坡
            fill.shader = LinearGradient(
                0f, cy.toFloat(), 0f, (cy + flank).toFloat(),
                intArrayOf(argb(a, cr, cg, cb), argb(0.0, cr, cg, cb), argb(0.0, cr, cg, cb)),
                floatArrayOf(0f, (flankS / flank).toFloat(), 1f),
                Shader.TileMode.CLAMP,
            )
            cv.drawRect(
                x.toFloat(), (cy - step).toFloat(), (x + step).toFloat(), (cy + flank).toFloat(), fill,
            )
            // ②b **脊线轮廓光**：只留一道**薄亮刃**（⛔ 再配脊下阴带 = 把每道脊框成一条线）。
            val ca = 0.30 * litK * aer * hf.pow(0.90) * (0.55 + 0.45 * wlt)
            if (ca > 0.004) {
                fill.shader = LinearGradient(
                    0f, cy.toFloat(), 0f, (cy + rimH).toFloat(),
                    intArrayOf(
                        argb(ca, topRgb[0], topRgb[1], topRgb[2]),
                        argb(ca * 0.45, topRgb[0], topRgb[1], topRgb[2]),
                        argb(0.0, topRgb[0], topRgb[1], topRgb[2]),
                    ),
                    floatArrayOf(0f, 0.30f, 1f),
                    Shader.TileMode.CLAMP,
                )
                cv.drawRect(
                    x.toFloat(), (cy - step).toFloat(), (x + step).toFloat(), (cy + rimH).toFloat(), fill,
                )
            }
            x += step
        }
        fill.shader = null
        cv.restore()

        // ③ 大尺度调子（只给领脊）：几团超扁的暖/冷渐变把"平"破掉。
        if (d.swales > 0) swales(cv, i, y1)
        // ④ 风成波纹（只给领脊）：**平行于脊线**的整幅色带，由 fbm 掩膜断续。
        if (d.rippleOn) ripples(cv, i, y1)
        // ⑤ 脊线上方的一层暖雾（远层才需要：⛔ 让远处的脊变成剪影刀边）。
        val hz = p.fx.haze * (1.0 - d.t)
        if (d.hazePad > 0.0 && hz > 0.02) {
            val hzc = tone.haze
            fill.shader = LinearGradient(
                0f, (d.mn - d.hazePad).toFloat(), 0f, (d.mn + h * 0.012).toFloat(),
                intArrayOf(
                    argb(0.0, hzc[0], hzc[1], hzc[2]),
                    argb(0.24 * hz, hzc[0], hzc[1], hzc[2]),
                    argb(0.42 * hz, hzc[0], hzc[1], hzc[2]),
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            path.reset()
            path.moveTo(pts[0], pts[1])
            k = 2
            while (k + 1 < pc) {
                path.lineTo(pts[k], pts[k + 1])
                k += 2
            }
            path.lineTo((w + step).toFloat(), (d.mn - d.hazePad).toFloat())
            path.lineTo((-step).toFloat(), (d.mn - d.hazePad).toFloat())
            path.close()
            cv.drawPath(path, fill)
            fill.shader = null
        }
    }

    /** 原型 §6 ③：超扁径向团（`save→translate→scale(1,ry/rx)` 的同一条形状）。 */
    private fun swales(cv: Canvas, i: Int, y1: Double) {
        val d = l.dunes[i]
        val rnd = DesertMath.rng(p.dune.seed xor (i * 7919 + 13))
        var k = 0
        while (k < d.swales) {
            val u = rnd.next()
            val x = l.w * u
            val cy = l.crest(i, x)
            val y = cy + (y1 - cy) * (0.25 + rnd.next() * 0.6)
            val rx = l.w * (0.06 + rnd.next() * 0.12)
            val ry = l.h * (0.012 + rnd.next() * 0.026)
            val col = if (rnd.next() < 0.55) d.tone.lit else d.tone.shade
            val a0 = 0.16 * (0.5 + u)
            cv.save()
            cv.translate(x.toFloat(), y.toFloat())
            cv.scale(1f, (ry / rx).toFloat())
            fill.shader = RadialGradient(
                0f, 0f, rx.toFloat(),
                intArrayOf(argb(a0, col[0], col[1], col[2]), argb(0.0, col[0], col[1], col[2])),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            cv.drawCircle(0f, 0f, rx.toFloat(), fill)
            fill.shader = null
            cv.restore()
            k++
        }
    }

    /** 原型 §6 ④ 的 `fAt`：`f` 沿 x 漂移（M2：⛔ 恒定高度分数 + 平行于脊线 = 等高线）。 */
    private fun fAt(xx: Double, f: Double, ph: Double, w: Double): Double =
        f + 0.055 * sin(xx / w * 9.4 + ph) + 0.030 * sin(xx / w * 21.7 + ph * 2.3)

    /** 原型 §6 ④：风成波纹（⛔ 随机短划 = 划痕；必须整幅色带 + fbm 掩膜断续）。 */
    private fun ripples(cv: Canvas, i: Int, y1: Double) {
        val d = l.dunes[i]
        val w = l.w
        val h = l.h
        val rnd = DesertMath.rng(p.dune.seed xor (0xabcd + i * 977))
        val n = max(1, DesertMath.jsRound(p.dune.ripples.toDouble()).toInt())
        val seg = max(3.0, DesertMath.jsRound(w / 110.0))
        val litN = d.tone.lit
        val shaN = d.tone.shade
        var k = 0
        while (k < n) {
            // 体高分数 ⇒ 带随脊线起伏；⚠️ 两端**各自求 f**（⛔ 用段首那一个值填两端 ⇒ 锯齿）。
            val f = 0.12 + (k + 0.35 + rnd.next() * 0.30) / n * 0.76
            val ph = rnd.next() * DesertMath.TAU
            val pd = 0.30 + rnd.next() * 0.35
            val lw = max(0.9, h * (0.0016 + 0.0034 * f) * (0.55 + 0.45 * d.t))
            line.strokeWidth = lw.toFloat()
            var x = 0.0
            while (x < w) {
                val fa = fAt(x, f, ph, w)
                val xb = min(w, x + seg)
                val fb = fAt(xb, f, ph, w)
                val m = DesertMath.fbm((x / w) * 5.5 + ph, fa * 3.1 + pd, 3, 0.5, 2.0)
                val a = DesertMath.clamp((m - 0.42) / 0.34, 0.0, 1.0)
                val ya = l.crest(i, x)
                val yb = l.crest(i, xb)
                if (a >= 0.22) {
                    line.strokeWidth = (lw * (0.55 + 0.45 * a)).toFloat()
                    line.color = argb(0.20 * a, shaN[0], shaN[1], shaN[2])
                    cv.drawLine(
                        x.toFloat(), (ya + fa * (y1 - ya) + lw * 0.55).toFloat(),
                        xb.toFloat(), (yb + fb * (y1 - yb) + lw * 0.55).toFloat(), line,
                    )
                    line.color = argb(0.13 * a, litN[0], litN[1], litN[2])
                    cv.drawLine(
                        x.toFloat(), (ya + fa * (y1 - ya) - lw * 0.62).toFloat(),
                        xb.toFloat(), (yb + fb * (y1 - yb) - lw * 0.62).toFloat(), line,
                    )
                }
                x += seg
            }
            k++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  烟丝位图（原型 `bakeWisp` / `bakeWisps`）
    // ══════════════════════════════════════════════════════════════════════════

    /** 烟丝位图的边长（原型 `SZ = 128`）。 */
    fun wispSize(): Int = WISP_SZ

    /**
     * 一张烟丝：主丝 = 26 颗软圆盘沿 fbm 摆动竖直曲线串成；宽 `0.35→1.0`；
     * 2 条子丝挂在两侧 ⇒ 破掉"一根香肠"；4 遍错位叠印做**无 filter 柔化**（全在烘焙期）。
     *
     * ⭐⭐ 第五轮否决②「烟柱中间还是柱状的」⇒ **沿丝身的低频浓度团块**：K 段**随机**
     * 宽度/相位/强度的团块（K 取 2~3）。⛔ 不用正弦做这件事（正弦是**周期**的，
     * 读成"波纹/竹节"，⛔ 不是"涡"）。这层住在位图里 ⇒ 逐帧 0 成本，而且
     * ⛔ 不碰 `puffR` / 粒子位置 ⇒ T4（重叠率）与 T5（倾角）**完全不受影响**。
     */
    fun wisp(cv: Canvas, tmp: Bitmap, tmpCv: Canvas, variant: Int, warm: Boolean) {
        val sz = WISP_SZ.toDouble()
        tmpCv.drawColor(0, PorterDuff.Mode.CLEAR)
        val rnd = DesertMath.rng((p.smoke.seed xor 0x77a1) + variant * 92821 + (if (warm) 47 else 11))
        val col = if (warm) intArrayOf(255, 196, 120) else intArrayOf(250, 240, 230)
        val kb = 2 + kotlin.math.floor(rnd.next() * 2.0).toInt()
        val cs = DoubleArray(kb)
        val cwid = DoubleArray(kb)
        val cal = DoubleArray(kb)
        var k = 0
        while (k < kb) {
            cs[k] = rnd.next()
            cwid[k] = 0.14 + rnd.next() * 0.22
            cal[k] = 0.30 + rnd.next() * 0.80
            k++
        }
        filament(tmpCv, rnd, sz, col, cs, cwid, cal, 0.0, 1.00, 1.00)
        filament(tmpCv, rnd, sz, col, cs, cwid, cal, 0.17, 0.62, 0.74)
        filament(tmpCv, rnd, sz, col, cs, cwid, cal, -0.14, 0.55, 0.64)

        copy.shader = null
        copy.xfermode = null
        pass(cv, tmp, 0.0, 0.0, 0.50)
        pass(cv, tmp, -2.0, 1.0, 0.28)
        pass(cv, tmp, 2.0, -1.0, 0.18)
        pass(cv, tmp, 0.0, 3.0, 0.14)
    }

    /** 团块浓度（原型 `clump`）：K 段随机团块取最大值 ⇒ 疏—密—疏、⛔ 周期竹节。 */
    private fun clumpAt(s: Double, cs: DoubleArray, cwid: DoubleArray, cal: DoubleArray): Double {
        var m = 0.0
        var j = 0
        while (j < cs.size) {
            val dd = abs(s - cs[j])
            if (dd < cwid[j]) {
                val q = 1.0 - dd / cwid[j]
                val v = cal[j] * q * q
                if (v > m) m = v
            }
            j++
        }
        return m                                            // ∈[0, 1.10]
    }

    /** 一条丝（原型 `filament`）：26 颗软圆盘沿横摆曲线串成。 */
    private fun filament(
        cv: Canvas,
        rnd: DesertMath.DesertRng,
        sz: Double,
        col: IntArray,
        cs: DoubleArray,
        cwid: DoubleArray,
        cal: DoubleArray,
        ox: Double,
        wmul: Double,
        amul: Double,
    ) {
        val p1 = rnd.next() * DesertMath.TAU
        val p2 = rnd.next() * DesertMath.TAU
        val p3 = rnd.next() * DesertMath.TAU
        val n = 26
        var i = 0
        while (i < n) {
            val s = i.toDouble() / (n - 1).toDouble()       // 0 = 底 … 1 = 顶
            val y = sz * (0.95 - 0.88 * s)
            // ⭐ 低频项**加强**：一记更慢的横摆（1.3）让丝身的**轮廓**自己 wander。
            val wob = sin(s * 1.3 + p1) * 0.16 + sin(s * 3.1 + p2) * 0.12 + sin(s * 7.7 + p3) * 0.05
            val x = sz * (0.5 + ox + wob * (0.22 + 0.78 * s))
            val cp = clumpAt(s, cs, cwid, cal)
            val wq = max(
                1.2,
                sz * 0.30 * wmul *
                    (0.35 + 0.65 * sin(DesertMath.PI_D * s).pow(0.55)) *
                    (0.62 + 0.55 * cp),
            )
            val a = 0.17 * amul * (0.45 + 0.80 * cp)
            disc(cv, x, y, wq, a, col)
            i++
        }
    }

    /** 一颗软圆盘：`radial(0→w)` 三段（`a / 0.53a / 0`），⛔ filter 柔化。 */
    private fun disc(cv: Canvas, x: Double, y: Double, r: Double, a: Double, col: IntArray) {
        fill.shader = RadialGradient(
            x.toFloat(), y.toFloat(), r.toFloat(),
            intArrayOf(
                argb(a, col[0], col[1], col[2]),
                argb(a * 0.53, col[0], col[1], col[2]),
                argb(0.0, col[0], col[1], col[2]),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        cv.drawRect(
            (x - r).toFloat(), (y - r).toFloat(), (x + r).toFloat(), (y + r).toFloat(), fill,
        )
        fill.shader = null
    }

    /** 一次错位叠印（原型 `pass(dx, dy, a)`：`globalAlpha = a` + `drawImage(tmp, dx, dy)`）。 */
    private fun pass(cv: Canvas, tmp: Bitmap, dx: Double, dy: Double, a: Double) {
        copy.alpha = u8(a * 255.0)
        cv.drawBitmap(tmp, dx.toFloat(), dy.toFloat(), copy)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  贴地浮沙带 + 全屏调色（逐像素 ⇒ 天然按行分块）
    // ══════════════════════════════════════════════════════════════════════════

    /** 原型 `bakeBand('veil', …)` 的画幅与 fbm 参数（横向无缝 ⇒ 用角度采样）。 */
    fun veilBox(): IntArray = intArrayOf(
        max(64, DesertMath.jsRound(l.w * 0.9).toInt()),
        max(16, DesertMath.jsRound(l.h * 0.11).toInt()),
    )

    /**
     * 一行（或一块行带）的浮沙带像素：**横向无缝**（`periodic` ⇒ 用角度采样）的 fbm。
     *
     * ⭐ 逐像素等于"逐行独立"⇒ 分帧后可以先显示上半。
     * 写入的是**未预乘**值（`Bitmap.setPixels` 的契约）。
     */
    fun veilRows(px: IntArray, w: Int, h: Int, j0: Int, j1: Int) {
        if (w < 1 || h < 1) return
        val oct = 4
        val alpha = 0.42
        val ySeed = 2.1
        val rgb = VEIL_RGB
        val ys = max(0, j0)
        val ye = min(h, j1)
        var j = ys
        while (j < ye) {
            val jf = j / h.toDouble()
            var i = 0
            // ⛔ `o` 是**本行基址**，循环内⛔**不得**自增 —— 下标已经是 `px[o + i]`，再 `o++`
            //    会让实际下标以 2 倍速增长（最大到 `(rows+1)*w`），撞穿 `rowBuf` 的容量。
            //    2026-10-16 真机 `ArrayIndexOutOfBoundsException: length=24000; index=24000`
            //    （24000 == [UNIT_PX_CAP]）的真凶就是这里多写的一行 `o++`。
            val o = (j - ys) * w
            while (i < w) {
                val a = (i / w.toDouble()) * DesertMath.TAU
                val v = DesertMath.fbm(cos(a) * 2.6 + 9.1, sin(a) * 2.6 + jf * 3.2 + ySeed, oct, 0.55, 2.0)
                val edge = DesertMath.smoothstep(0.0, 0.30, jf) * (1.0 - DesertMath.smoothstep(0.66, 1.0, jf))
                val al = DesertMath.clamp((v - 0.40) * 1.9, 0.0, 1.0) * alpha * edge
                val b = u8(DesertMath.jsRound(al * 255.0))
                px[o + i] = (b shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
                i++
            }
            j++
        }
    }

    /**
     * 暗角 + 颗粒**合并为一张** ⇒ 逐帧只 1 次铺屏（原型 `bakeGrade`）。
     *
     * ⭐ 三轮裁决 ③「整个色调太深了」的主犯就是这里：旧值 `vignette 0.34 · smoothstep(0.40, 1.05)`
     * + 紫黑底 ⇒ 实测**暗部占比 23.2%**（参考图 1 只有 2.8%）。现取 `0.10 · smoothstep(0.62, 1.10)`
     * + 暖棕底，且 ⛔ 暗角色再往蓝紫走。
     * ⚠️ 写成未预乘法（⛔ 逐通道乘 A：Skia 会再乘一次 ⇒ 暗角强度按 `A/255` 平方衰减）。
     */
    fun gradeRows(px: IntArray, w: Int, h: Int, j0: Int, j1: Int) {
        if (w < 1 || h < 1) return
        val cx = w / 2.0
        val cy = h * 0.46
        val diag = DesertMath.hypot(w / 2.0, h / 2.0)
        val grain = p.fx.grain
        val grainOn = grain > 0.0
        val vig = p.fx.vignette
        val ys = max(0, j0)
        val ye = min(h, j1)
        var j = ys
        while (j < ye) {
            var i = 0
            // ⛔ 同上：`o` 是本行基址，循环内⛔ 不得自增（[veilRows] 的姊妹 BUG）。
            val o = (j - ys) * w
            while (i < w) {
                val r = DesertMath.hypot(i - cx, j - cy) / diag
                var a = vig * DesertMath.smoothstep(0.62, 1.10, r)
                if (grainOn) a += DesertMath.hash2(i, j) * grain * 0.55
                val al = u8(DesertMath.clamp(a, 0.0, 1.0) * 255.0)
                px[o + i] = (al shl 24) or (46 shl 16) or (26 shl 8) or 20
                i++
            }
            j++
        }
    }

    companion object {
        /**
         * 天幕**底渐变**的 5 个锚（原型 §6 `bakeSky` 的实测亮度阶梯：天顶**不是**最亮）。
         *
         * ⛔ **单一来源**：软件烘焙路径 [sky] 与逐帧兜底路径
         * `DesertSmokeRenderer.drawSkyFallback` **共用这一份** —— 两处各写一份必然漂移，
         * 而漂移的后果是「兜底帧 → 真天幕」之间闪一次色差（兜底只活 1 帧，肉眼正好抓住）。
         */
        val SKY_HEX = intArrayOf(0xc65436, 0xbf5134, 0xc65436, 0xc66036, 0xd7693a)

        /** [SKY_HEX] 对应的渐变位置。 */
        val SKY_POS = floatArrayOf(0f, 0.25f, 0.60f, 0.85f, 1f)

        /**
         * [SKY_HEX] 预先转成**不透明** ARGB（静态算一次 ⇒ 烘焙路径逐次零分配）。
         *
         * ⚠️ 它在源码里排在 `opaque`（本 companion 末尾）**之前**，这是**有意为之**、且安全：
         *    `opaque` 是**无状态纯函数**，companion 的初始化器是在已经建好的 `Companion`
         *    实例上调它，不读任何字段 ⇒ 与「字段按声明顺序求值」这条规则无关。
         *    ⛔ 但不要照抄这个顺序去写**依赖其他字段**的常量 —— 那种才会读到 `null`/`0`。
         */
        val SKY_COLORS = IntArray(SKY_HEX.size) { opaque(SKY_HEX[it]) }

        /** 烟丝位图边长（原型 `SZ = 128`）。 */
        const val WISP_SZ = 128

        /** 浮沙带源色（原型 `[246,186,122]`）。 */
        val VEIL_RGB = intArrayOf(246, 186, 122)

        /** 单个行块单元的像素上界（E46/E47 同值）：粒度解析 ⇒ 超调最多一个单元。 */
        const val UNIT_PX_CAP = 24000

        /** 逐帧烘焙时间预算（毫秒）—— 与 E46/E47 真机验收定稿的 24 ms 同档。 */
        const val BAKE_BUDGET_MS = 24L

        /** 原型 `Math.round`（⛔ `toInt()` 截断会让暗角最外圈整环偏暗一档）。 */
        fun u8(v: Double): Int = max(0, min(255, DesertMath.jsRound(v).toInt()))

        fun argb(a: Double, r: Int, g: Int, b: Int): Int =
            (u8(a * 255.0) shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)

        /** hex（`0xRRGGBB`）→ 不透明的 ARGB。 */
        fun opaque(hex: Int): Int = 0xFF000000.toInt() or hex
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  分帧烘焙队列（纯 Kotlin：只排「烘什么」，⛔ 不碰 `Bitmap` ⇒ 可单测）
// ══════════════════════════════════════════════════════════════════════════

internal class DesertBakeUnit(val kind: Int, val a: Int, val b: Int)

internal object DesertBakeKind {
    const val SKY = 0
    const val SUN = 1
    const val DUNE = 2
    const val WISP = 3
    const val CAMEL = 4
    const val VEIL = 5
    const val GRADE = 6
}

/**
 * E48 首帧之后的**分帧烘焙队列**。
 *
 * 顺序 = 收敛顺序：`sky`（0 号单元，首帧必出画面）→ `sun` → **沙丘画布 `nG−1 → 0` 倒序**
 * （首帧 = 天幕 + 日盘 + 最近 1–2 组）→ 12 张烟丝 → 骆驼 → 浮沙带 → **`grade` 最后**。
 *
 * ⚠️ `veil` 与 `grade` 的画幅**各不相同**（带按 `0.9W × 0.11H`、grade 是全屏）
 * ⇒ 必须**逐个传进来**，⛔ 一套包打天下（行块数按全屏算 ⇒ 预算账作废）。
 */
internal class DesertBakeQueue(
    nG: Int,
    veilW: Int,
    veilH: Int,
    gradeW: Int,
    gradeH: Int,
    variants: Int,
) {
    val units = ArrayList<DesertBakeUnit>()
    private var cursor = 0
    val done: Boolean get() = cursor >= units.size

    init {
        units.add(DesertBakeUnit(DesertBakeKind.SKY, 0, 0))
        units.add(DesertBakeUnit(DesertBakeKind.SUN, 0, 0))
        var gi = nG - 1
        while (gi >= 0) {                        // ⭐ 倒序：最近组先到位
            units.add(DesertBakeUnit(DesertBakeKind.DUNE, gi, 0))
            gi--
        }
        var v = 0
        while (v < variants) {
            units.add(DesertBakeUnit(DesertBakeKind.WISP, v, 0))
            units.add(DesertBakeUnit(DesertBakeKind.WISP, v, 1))
            v++
        }
        units.add(DesertBakeUnit(DesertBakeKind.CAMEL, 0, 0))
        addRowUnits(DesertBakeKind.VEIL, veilW, veilH)
        addRowUnits(DesertBakeKind.GRADE, gradeW, gradeH)
    }

    fun next(): DesertBakeUnit? = if (cursor >= units.size) null else units[cursor++]

    private fun addRowUnits(kind: Int, w: Int, h: Int) {
        if (w < 1 || h < 1) return
        val rows = max(1, DesertSmokeBakes.UNIT_PX_CAP / max(1, w))
        var a = 0
        while (a < h) {
            val b = min(a + rows, h)
            units.add(DesertBakeUnit(kind, a, b))
            a = b
        }
    }
}
