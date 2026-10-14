package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * E47 K3「烘焙」—— 原型 §4 的 8 张一次性位图：
 * `sky / ground / pines / tree / person / personSnow / cloud·veilFar·veilNear 三条带 / grade`。
 *
 * ## 分帧口径沿用 E46（所有者真机验收定稿：预算 24 ms）
 * ⛔ 压在**同一帧**同步烘完：本主题的像素总量比 E46 更大（`sky`/`ground`/`grade` 三张全屏 +
 * 三条带 ≈ 0.7 屏），创维 API 22 上以**秒**计，而这段时间 Compose 一帧都没提交 ⇒ 冷进入黑屏。
 * 排法与 E46 同三条：
 * - [SnowBakeQueue] 只排「烘什么」（纯 Kotlin，⛔ 不碰 `Bitmap`，因此可单测）；
 * - 行块类单元（三条带 / grade）按 [SnowCedarBakes.UNIT_PX_CAP] 切，解析式粒度 ⇒ 超调最多一个单元；
 * - **grade 最后**：暗角/颗粒是覆盖层，缺失时画面依然完整，最不该挤掉前景素材的帧预算。
 *
 * ⚠️ 单元顺序即收敛顺序：`sky` 底色必须是 0 号单元（首帧必出画面），
 * `ground` 的 `destination-out` 裁线必须排在该图**全部内容之后**（否则切完还在往缺口里填色）。
 *
 * ## ⛔ 各向异性缩放下的"沿枝方向"
 * 树位图横向 `sx = uy·crownW`、纵向 `uy`（冠幅与冠高**独立可调**）⇒ 单位系角度 `atan2(ty, tx)`
 * 落到位图上是**错的**（P28 ②：雪脊会斜成与枝条不平行的一蓬团）。本文件里所有沿枝绘制
 * 一律先换成像素系角 `atan2(ty·uy, tx·sx)`，⛔ 直接用单位系角度或先旋转矩阵再取角。
 */
internal class SnowCedarBakes(
    private val p: SnowCedarP,
    private val l: SnowCedarLayout,
) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val xout = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    // ══════════════════════════════════════════════════════════════════════════
    //  大树
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 树位图的尺寸与映射（原型 `bakeTree()` 的前 8 行 + `B.treeMeta`）。
     *
     * ⭐ 单独成函数、⛔ 不碰 `Bitmap`：`*Mesh[1]/[2]` 是 K3 唯一能被黄金测试钉住的账
     * （[SnowCedarGoldenTest] ⑬），所以这一段必须能在 JVM 里跑。
     */
    fun treeMeta(t: SnowCedarTree.Tree): SnowTreeMeta {
        val pad = ceil(0.06 * l.treeH).toInt() + 8
        val uy = l.treeH / max(1e-6, t.y1 - t.y0)
        val sx = uy * p.comp.crownW
        val bw = ceil((t.x1 - t.x0) * sx).toInt() + pad * 2
        val bh = ceil((t.y1 - t.y0) * uy).toInt() + pad * 2
        val baseX = pad + (0.0 - t.x0) * sx
        val baseY = pad + (0.0 - t.y0) * uy
        return SnowTreeMeta(bw, bh, pad, sx, uy, t.x0, t.y0, baseX, baseY, l.treeX - baseX, l.treeBaseY - baseY)
    }

    /** ① 枝干剪影：**越细越淡**（大气透视）且越靠基部越深（原型 :400 的写反教训）。 */
    fun treeSegs(cv: Canvas, t: SnowCedarTree.Tree, m: SnowTreeMeta, from: Int, to: Int) {
        val th = max(1e-6, t.y1 - t.y0)
        var i = max(0, from)
        val e = min(t.segs.size, to)
        while (i < e) {
            val s = t.segs[i]
            val wpx = max(0.6, s.w * m.uy * 2)
            val fade = SnowCedarMath.clamp(0.55 + s.depth * 0.06, 0.0, 1.0)
            val near = SnowCedarMath.clamp(1 - min(1.0, -s.y1 / (th * 0.55)), 0.0, 1.0)
            val k2 = 1 - 0.20 * near
            val thin = SnowCedarMath.clamp((3.4 - s.depth) / 2.4, 0.0, 1.0)
            line.color = argb(
                SnowCedarMath.clamp((0.90 + 0.08 * near) * (1 - 0.46 * thin), 0.0, 1.0),
                lk(74 * fade + 40, 214.0, k2, thin),
                lk(70 * fade + 40, 219.0, k2, thin),
                lk(64 * fade + 42, 226.0, k2, thin),
            )
            line.strokeWidth = wpx.toFloat()
            cv.drawLine(x(m, s.x1), y(m, s.y1), x(m, s.x2), y(m, s.y2), line)
            i++
        }
    }

    /** ②a 宽而淡的"雪团底"（先叠体积，再压实心雪脊 ⇒ 读成一坨坨积雪而不是白线）。 */
    fun treeSnowBase(cv: Canvas, t: SnowCedarTree.Tree, m: SnowTreeMeta, from: Int, to: Int) {
        line.color = argb(0.26, 246, 249, 253)
        var i = max(0, from)
        val e = min(t.snow.size, to)
        while (i < e) {
            val s = t.snow[i]
            val wpx = max(1.0, s.sw * m.uy * 2)
            line.strokeWidth = (wpx * 1.7).toFloat()
            cv.drawLine(x(m, s.x1), y(m, s.y1) - (wpx * 0.30).toFloat(), x(m, s.x2), y(m, s.y2) - (wpx * 0.30).toFloat(), line)
            i++
        }
    }

    /** ②b 实心雪脊 + 下缘一抹冷灰（让"雪条"有厚度而非白线）。 */
    fun treeSnowRidge(cv: Canvas, t: SnowCedarTree.Tree, m: SnowTreeMeta, from: Int, to: Int) {
        var i = max(0, from)
        val e = min(t.snow.size, to)
        while (i < e) {
            val s = t.snow[i]
            val wpx = max(1.0, s.sw * m.uy * 2)
            line.color = argb(0.88 + 0.10 * s.face, 252, 253, 255)
            line.strokeWidth = wpx.toFloat()
            cv.drawLine(x(m, s.x1), y(m, s.y1), x(m, s.x2), y(m, s.y2), line)
            line.color = argb(0.34, 196, 205, 216)
            line.strokeWidth = max(0.6, wpx * 0.30).toFloat()
            cv.drawLine(
                x(m, s.x1), y(m, s.y1) + (wpx * 0.46).toFloat(),
                x(m, s.x2), y(m, s.y2) + (wpx * 0.46).toFloat(), line,
            )
            i++
        }
    }

    /**
     * ③ 分叉节点的雪 = **沿枝条方向的雪脊**（所有者 2026-10-14 裁决，D13/P28）。
     * 三团沿**枝轴**排开、每团长轴与枝轴同向；⛔ 轴对齐横椭圆（"枝上挂白盘子"）、⛔ 无方向大圆团。
     */
    fun treeClumps(cv: Canvas, t: SnowCedarTree.Tree, m: SnowTreeMeta, from: Int, to: Int) {
        var i = max(0, from)
        val e = min(t.clumps.size, to)
        while (i < e) {
            val cl = t.clumps[i]
            val r = max(0.8, cl.r * m.uy * p.tree.clump)
            val rot = atan2(cl.ty * m.uy, cl.tx * m.sx)
            val ex = cos(rot)
            val ey = sin(rot)
            val cx = x(m, cl.x).toDouble()
            val cy = y(m, cl.y).toDouble()
            val j1 = SnowCedarMath.hash2((cl.x * 977).toInt(), (cl.y * 613).toInt())
            val j2 = SnowCedarMath.hash2((cl.x * 331).toInt(), ((cl.y + 7) * 269).toInt())
            // [沿枝偏移, 上抬量, 缩放]：沿**枝轴**排开（横向并排又变盘子）；长宽比 2.6:1 ⇒ 读得出"趴在上面的一条"
            val l0 = doubleArrayOf(0.0, 0.44, 1.0)
            val l1 = doubleArrayOf(-0.62 - 0.5 * j1, 0.34 + 0.10 * j2, 0.72 + 0.16 * j2)
            val l2 = doubleArrayOf(0.58 + 0.4 * j2, 0.32 + 0.14 * j1, 0.66 + 0.2 * j1)
            val wide = argb(0.30, 240, 245, 251)
            val solid = argb(0.92, 251, 253, 255)
            lobe(cv, cx, cy, r, ex, ey, rot, l0, 1.30, 0.50, 1.0, 1.0, wide)
            lobe(cv, cx, cy, r, ex, ey, rot, l1, 1.30, 0.50, 1.0, 1.0, wide)
            lobe(cv, cx, cy, r, ex, ey, rot, l2, 1.30, 0.50, 1.0, 1.0, wide)
            lobe(cv, cx, cy, r, ex, ey, rot, l0, 0.98, 0.34, 0.86, 1.06, solid)
            lobe(cv, cx, cy, r, ex, ey, rot, l1, 0.98, 0.34, 0.86, 1.06, solid)
            lobe(cv, cx, cy, r, ex, ey, rot, l2, 0.98, 0.34, 0.86, 1.06, solid)
            var k = 0
            while (k < 3) {                       // 边缘小瘤：沿枝轴不对称地鼓出来
                val hj = SnowCedarMath.hash2((cl.x * 131 + k * 7).toInt(), (cl.y * 271 + k * 13).toInt())
                lobeXY(cv, cx, cy, r, ex, ey, rot,
                    (-0.9 + 1.8 * hj) * (0.8 + 0.5 * j1), 0.42 + 0.10 * hj,
                    (0.20 + 0.26 * j1) * (1 + hj), 0.13 + 0.15 * hj, solid)
                k++
            }
            // 冷灰下缘：一条**贴着枝轴**的短线（⛔ 轴对齐圆弧——旋转后弧会跑到侧面去）
            line.color = argb(0.30, 190, 200, 214)
            line.strokeWidth = max(0.6, r * 0.20).toFloat()
            cv.drawLine(
                (cx - ex * r * 1.02).toFloat(), (cy - ey * r * 1.02 - r * 0.10).toFloat(),
                (cx + ex * r * 1.02).toFloat(), (cy + ey * r * 1.02 - r * 0.10).toFloat(), line,
            )
            i++
        }
    }

    /** 原型 `blob()`：中心 = 基点沿枝轴推 `along·r`、再上抬 `up·r`；半轴 = `(rxK,ryK)·r`，长轴与枝轴同向。 */
    private fun lobe(
        cv: Canvas, cx: Double, cy: Double, r: Double, ex: Double, ey: Double, rot: Double,
        lobe3: DoubleArray, rxK: Double, ryK: Double, sAlong: Double, sUp: Double, c: Int,
    ) = lobeXY(cv, cx, cy, r, ex, ey, rot, lobe3[0] * sAlong, lobe3[1] * sUp, rxK * lobe3[2], ryK * lobe3[2], c)

    private fun lobeXY(
        cv: Canvas, cx: Double, cy: Double, r: Double, ex: Double, ey: Double, rot: Double,
        along: Double, up: Double, rxK: Double, ryK: Double, c: Int,
    ) {
        val px = cx + along * r * ex
        val py = cy + along * r * ey - up * r
        val rx = rxK * r
        val ry = ryK * r
        if (rx <= 0.0 || ry <= 0.0) return
        fill.color = c
        cv.save()
        cv.rotate((rot * 180.0 / SnowCedarMath.PI_D).toFloat(), px.toFloat(), py.toFloat())
        cv.drawOval(
            (px - rx).toFloat(), (py - ry).toFloat(), (px + rx).toFloat(), (py + ry).toFloat(), fill,
        )
        cv.restore()
    }

    /** ④ 基部雪裙：先树坑冷阴、后白裙压在树干下缘上 ⇒ 树"从雪里长出来"（⛔ 电线杆感）。 */
    fun treeSkirt(cv: Canvas, m: SnowTreeMeta) {
        val skR = max(8.0, l.treeH * 0.085)
        var i = 0
        while (i < 2) {
            val hj = SnowCedarMath.hash2(i * 19 + 2, 5)
            val hj2 = SnowCedarMath.hash2(i + 7, 23)
            val mx = m.baseX + (hj - 0.5) * skR * 0.8
            val my = m.baseY - skR * (0.04 + 0.10 * hj2)
            val rw = skR * (1.85 + 0.45 * hj2)
            val rh = rw * 0.17
            squashedRadial(cv, mx, my, rw, rh, floatArrayOf(0.12f, 0f, 1f),
                intArrayOf(argb(0.20, 146, 163, 186), argb(0.11, 154, 170, 192), argb(0.0, 160, 176, 196)))
            i++
        }
        i = 0
        while (i < 4) {
            val hj = SnowCedarMath.hash2(i * 37 + 5, 71)
            val hj2 = SnowCedarMath.hash2(i + 3, 13)
            val hj3 = SnowCedarMath.hash2(i, 97)
            val mx = m.baseX + (hj - 0.5) * skR * 1.6
            val my = m.baseY + skR * (0.02 + 0.14 * hj3)
            val rw = skR * (0.85 + 0.60 * hj2)
            val rh = rw * 0.30
            squashedRadial(cv, mx, my, rw, rh, floatArrayOf(0f, 0f, 1f),
                intArrayOf(argb(0.95, 255, 255, 255), argb(0.55, 250, 252, 255), argb(0.0, 246, 250, 255)))
            i++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  天幕
    // ══════════════════════════════════════════════════════════════════════════

    /** 阴天高调渐变底色（⛔ 完美平滑的渐变读成"色卡"，絮状起伏由 [skyBlobs] 补）。 */
    fun skyBase(cv: Canvas) {
        val skyBot = l.hz + l.h * 0.06
        fill.shader = LinearGradient(0f, 0f, 0f, skyBot.toFloat(),
            intArrayOf(rgb(0xB9C0C9), rgb(0xCCD3DA), rgb(0xE6EAEE), rgb(0xEEF1F4)),
            floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, l.w.toFloat(), skyBot.toFloat(), fill)
        fill.shader = null
    }

    /**
     * 烘焙期自叠的絮状云质（46 团，⛔ 逐帧颗粒贴图）。
     *
     * ⚠️ 分块必须**预跳随机数**：`rnd()` 是跨团连续的状态，每团固定抽 5 次
     * （x / v / r / pale / a）⇒ 从 `from` 起烘要先空跑 `5·from` 次，⛔ 直接新建序列（那样每块都是同一批云）。
     */
    fun skyBlobs(cv: Canvas, from: Int, to: Int) {
        val skyBot = l.hz + l.h * 0.06
        val tex = p.fx.skyTex
        if (tex <= 0.0) return
        val rnd = SnowCedarMath.rng(p.tree.seed xor 0x2f9c)
        var skip = from * 5
        while (skip > 0) { rnd.next(); skip-- }
        var i = max(0, from)
        val e = min(SKY_BLOBS, to)
        while (i < e) {
            val x = ((i + rnd.next()) / SKY_BLOBS) * l.w * 1.16 - l.w * 0.08
            val v = rnd.next().pow(1.35)
            val y = (0.04 + 0.92 * v) * skyBot
            val r = (0.05 + rnd.next() * 0.14) * l.w
            val pale = rnd.next() < 0.46
            val a = SnowCedarMath.clamp(tex * (0.30 + rnd.next() * 0.95) * (1 - 0.40 * (y / skyBot)), 0.0, 0.32)
            val cr = if (pale) 252 else 152
            val cg = if (pale) 253 else 162
            val cb = if (pale) 255 else 177
            fill.shader = RadialGradient(x.toFloat(), y.toFloat(), r.toFloat(),
                intArrayOf(argb(a, cr, cg, cb), argb(a * 0.42, cr, cg, cb), argb(0.0, cr, cg, cb)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            cv.drawRect((x - r).toFloat(), (y - r).toFloat(), (x + r).toFloat(), (y + r).toFloat(), fill)
            fill.shader = null
            i++
        }
    }

    /** 贴地平线的增亮带（远处雪面把天光反照回来 ⇒ "越靠地平线越亮"是雪天特有的读感）。 */
    fun skyHorizon(cv: Canvas) {
        val skyBot = l.hz + l.h * 0.06
        val tex = p.fx.skyTex
        if (tex <= 0.0) return
        val y0 = skyBot - l.h * 0.19
        fill.shader = LinearGradient(0f, y0.toFloat(), 0f, skyBot.toFloat(),
            intArrayOf(argb(0.0, 248, 250, 253), argb(SnowCedarMath.clamp(0.20 + tex * 4, 0.0, 0.6), 248, 250, 253)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, y0.toFloat(), l.w.toFloat(), skyBot.toFloat(), fill)
        fill.shader = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  雪原
    // ══════════════════════════════════════════════════════════════════════════

    /** 底色渐变 + 三道缓丘（⛔ 坡缘描边：纯白雪原上会读成横贯全屏的等高线）。 */
    fun groundBase(cv: Canvas) {
        val top = l.hz - l.h * 0.02
        fill.shader = LinearGradient(0f, top.toFloat(), 0f, l.h.toFloat(),
            intArrayOf(rgb(0xE9EDF1), rgb(0xF2F5F8), rgb(0xDFE5EC)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, top.toFloat(), l.w.toFloat(), l.h.toFloat(), fill)
        fill.shader = null
        var k = 0
        while (k < 3) {
            val yBase = l.hz + l.h * (0.05 + k * 0.10)
            path.reset()
            path.moveTo(0f, l.h.toFloat())
            path.lineTo(0f, yBase.toFloat())
            val st = max(6.0, l.w / 220)
            var xp = 0.0
            while (xp <= l.w) {
                val yv = yBase + l.h * 0.020 * SnowCedarMath.fbm(xp / l.w * (3 + k), 11.3 + k * 3.1, 3, 0.5, 2.0) -
                    l.h * 0.010 * sin(xp / l.w * (2.1 + k))
                path.lineTo(xp.toFloat(), yv.toFloat())
                xp += st
            }
            path.lineTo(l.w.toFloat(), l.h.toFloat())
            path.close()
            fill.color = if (k == 2) argb(0.55, 255, 255, 255) else argb(0.42, 248, 251, 255)
            cv.drawPath(path, fill)
            k++
        }
    }

    /** 大尺度雪坡调子：7 条上下缘都带 fbm 起伏的宽调子（直边 = 斑马线）。 */
    fun groundTones(cv: Canvas, from: Int, to: Int) {
        var i = max(0, from)
        val e = min(GROUND_TONES, to)
        while (i < e) {
            val r1 = SnowCedarMath.hash2(i, 137)
            val r2 = SnowCedarMath.hash2(i, 149)
            val yc = l.hz + l.h * (0.05 + 0.62 * ((i + 0.35 + 0.4 * r1) / GROUND_TONES).pow(1.35))
            val hh = l.h * (0.016 + 0.052 * (i / GROUND_TONES.toDouble())) * (0.7 + 0.7 * r2)
            val st = max(8.0, l.w / 160)
            path.reset()
            var xp = 0.0
            var first = true
            while (xp <= l.w + st) {
                val yv = yc - hh * (0.55 + 0.50 * SnowCedarMath.fbm(xp / l.w * 2.6 + i * 3.3, 7.7 + i, 2, 0.5, 2.0))
                if (first) { path.moveTo(xp.toFloat(), yv.toFloat()); first = false } else path.lineTo(xp.toFloat(), yv.toFloat())
                xp += st
            }
            var xq = l.w + st
            while (xq >= -st) {
                val yv = yc + hh * (0.55 + 0.50 * SnowCedarMath.fbm(xq / l.w * 2.6 + i * 5.1, 2.3 + i, 2, 0.5, 2.0))
                path.lineTo(xq.toFloat(), yv.toFloat())
                xq -= st
            }
            path.close()
            fill.color = if (r1 > 0.48) argb(0.070 + 0.050 * r2, 172, 188, 208) else argb(0.160 + 0.120 * r2, 255, 255, 255)
            cv.drawPath(path, fill)
            i++
        }
    }

    /** 风蚀纹：暗槽先铺宽而淡的两层，再压两条亮带（⛔ 等宽等长的白/暗平行线 = 一地蓝色短划痕）。 */
    fun groundVeins(cv: Canvas, from: Int, to: Int) {
        var i = max(0, from)
        val e = min(GROUND_VEINS, to)
        while (i < e) {
            val r1 = SnowCedarMath.hash2(i, 3)
            val r2 = SnowCedarMath.hash2(i, 7)
            val r3 = SnowCedarMath.hash2(i, 29)
            val r4 = SnowCedarMath.hash2(i, 47)
            val dep = r1.pow(1.35)
            val yv = l.hz + l.h * (0.035 + 0.62 * dep)
            val xv = r2 * l.w
            val len = l.w * (0.030 + 0.100 * r3) * (0.45 + 1.25 * dep)
            val ww = max(0.7, (0.7 + 1.9 * r3) * l.s * (0.45 + 1.25 * dep))
            val sag = len * (0.02 + 0.05 * r4) * (if (r1 > 0.5) 1.0 else -1.0)
            val dy = -len * (0.008 + 0.022 * r3)
            vein(cv, xv, yv, len, sag, dy, ww * 1.35, ww * 2.2, argb(0.05 + 0.05 * r2, 176, 188, 203))
            vein(cv, xv, yv, len, sag, dy, ww * 0.80, ww * 1.0, argb(0.06 + 0.06 * r3, 176, 188, 203))
            vein(cv, xv, yv, len, sag, dy, -ww * 0.90, ww * 2.6, argb(0.13 + 0.10 * r2, 255, 255, 255))
            vein(cv, xv, yv, len, sag, dy, 0.0, ww * 1.5, argb(0.30 + 0.16 * r3, 255, 255, 255))
            i++
        }
    }

    private fun vein(cv: Canvas, xv: Double, yv: Double, len: Double, sag: Double, dy: Double, oy: Double, w2: Double, c: Int) {
        line.color = c
        line.strokeWidth = w2.toFloat()
        path.reset()
        path.moveTo(xv.toFloat(), (yv + oy).toFloat())
        path.quadTo((xv + len * 0.5).toFloat(), (yv + oy + sag).toFloat(), (xv + len).toFloat(), (yv + oy + dy).toFloat())
        cv.drawPath(path, line)
    }

    /** 近景雪丘：低对比"亮顶 + 前缘暗阴"的一对**压扁径向渐变**（⛔ 实心椭圆 = 一道划痕）。 */
    fun groundMounds(cv: Canvas, from: Int, to: Int) {
        var i = max(0, from)
        val e = min(GROUND_MOUNDS, to)
        while (i < e) {
            val r1 = SnowCedarMath.hash2(i, 61)
            val r2 = SnowCedarMath.hash2(i, 83)
            val r3 = SnowCedarMath.hash2(i, 101)
            val dep = 0.35 + 0.65 * r1
            val xv = (r2 * 1.2 - 0.1) * l.w
            val yv = l.hz + l.h * (0.16 + 0.80 * dep)
            val rw = l.w * (0.035 + 0.075 * r3) * (0.4 + dep)
            val rh = rw * (0.16 + 0.10 * r1)
            squashedRadial(cv, xv, yv - rh * 0.35, rw, rh, floatArrayOf(0f, 0.58f, 1f),
                intArrayOf(argb(0.30 + 0.20 * r3, 255, 255, 255), argb(0.15 + 0.10 * r3, 255, 255, 255), argb(0.0, 255, 255, 255)))
            val sh = rw * 0.72
            squashedRadial(cv, xv, yv + rh * 0.55, sh, rh * 0.55, floatArrayOf(0f, 1f),
                intArrayOf(argb(0.13 + 0.10 * r1, 168, 184, 205), argb(0.0, 168, 184, 205)))
            i++
        }
    }

    /** 树根雪堆：被风推出来的雪檐（顺风拖出一条舌）+ 靠树侧冷阴 + 檐顶四道小亮脊。 */
    fun groundTreeMound(cv: Canvas) {
        val bx = l.treeX
        val by = l.treeBaseY
        val drw = max(12.0, l.treeH * 0.20)
        val drh = drw * 0.17
        mound(cv, bx - drw * 0.14, by - drh * 0.10, drw, drh, 0.62)
        mound(cv, bx + drw * 0.52, by + drh * 0.16, drw * 0.62, drh * 0.72, 0.40)
        val dsh = drw * 0.86
        squashedRadial(cv, bx - drw * 0.06, by + drh * 0.62, dsh, drh * 0.55, floatArrayOf(0f, 1f),
            intArrayOf(argb(0.16, 178, 192, 210), argb(0.0, 178, 192, 210)))
        var i = 0
        while (i < 4) {
            val r1 = SnowCedarMath.hash2(i, 211)
            val r2 = SnowCedarMath.hash2(i, 223)
            mound(cv, bx + (r1 - 0.42) * drw * 1.7, by - drh * (0.30 + 0.55 * r2), drw * (0.14 + 0.12 * r2), drh * (0.5 + 0.5 * r1), 0.42)
            i++
        }
    }

    /** 雪原大尺度起伏：9 团极低对比的冷阴/暖亮 ⇒ 平原读成"有坡的堆"。 */
    fun groundUndulations(cv: Canvas, from: Int, to: Int) {
        var i = max(0, from)
        val e = min(GROUND_UNDUL, to)
        while (i < e) {
            val r1 = SnowCedarMath.hash2(i, 257)
            val r2 = SnowCedarMath.hash2(i, 263)
            val r3 = SnowCedarMath.hash2(i, 269)
            val dep = 0.15 + 0.85 * r1
            val cx = (r2 * 1.3 - 0.15) * l.w
            val cy = l.hz + l.h * (0.06 + 0.66 * dep)
            val rw = l.w * (0.10 + 0.16 * r3) * (0.5 + dep)
            val rh = rw * (0.10 + 0.06 * r1)
            val c0 = if (r3 > 0.45) argb(0.20, 176, 190, 209) else argb(0.30, 255, 255, 255)
            val c1 = if (r3 > 0.45) argb(0.0, 176, 190, 209) else argb(0.0, 255, 255, 255)
            squashedRadial(cv, cx, cy, rw, rh, floatArrayOf(0f, 1f), intArrayOf(c0, c1))
            i++
        }
    }

    /** 人站定 ⇒ 脚下烘一圈被踩陷的雪窝（暗壁 + 亮心，⛔ 逐帧；烘焙期一次）。 */
    fun groundFoot(cv: Canvas) {
        val hh = l.personH
        val px = l.personX + hh * 0.028
        fillEllipse(cv, px, l.personY + hh * 0.006, hh * 0.215, hh * 0.050, argb(0.24, 158, 171, 189))
        fillEllipse(cv, px, l.personY - hh * 0.012, hh * 0.130, hh * 0.026, argb(0.34, 252, 253, 255))
    }

    /**
     * 地平线裁线：位图是**矩形**，真机上那条横贯全屏的"尺子线"就是位图上缘。
     * 修法 = 烘焙期按雪面波浪线 `destination-out` 切掉直边（必须排在本图全部内容之后）。
     */
    fun groundCut(cv: Canvas) {
        val cutPad = l.h * 0.012
        fill.xfermode = xout
        path.reset()
        path.moveTo(0f, -1f)
        val st = max(6.0, l.w / 240)
        var xp = 0.0
        while (xp <= l.w + st) {
            path.lineTo(xp.toFloat(), (l.snowSurfaceY(xp) - cutPad).toFloat())
            xp += st
        }
        path.lineTo((l.w + st).toFloat(), -1f)
        path.close()
        fill.color = Color.BLACK
        cv.drawPath(path, fill)
        fill.xfermode = null
    }

    /** 沿切线补一排**向上溢出的碎雪堆**，把剩下的微波打断成参差。 */
    fun groundSpall(cv: Canvas, from: Int, to: Int) {
        val cutPad = l.h * 0.012
        var i = max(0, from)
        val e = min(GROUND_SPALL, to)
        while (i < e) {
            val r1 = SnowCedarMath.hash2(i, 311)
            val r2 = SnowCedarMath.hash2(i, 331)
            val r3 = SnowCedarMath.hash2(i, 349)
            val mx = (i + 0.15 + 0.7 * r1) / GROUND_SPALL * l.w
            val rw = l.w * (0.012 + 0.030 * r3)
            val rh = max(3.0, rw * (0.20 + 0.16 * r2))
            mound(cv, mx, l.snowSurfaceY(mx) - cutPad + rh * 0.15, rw, rh, 0.30 + 0.34 * r1)
            i++
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  远景松林
    // ══════════════════════════════════════════════════════════════════════════

    fun pineSize(): Pair<Int, Int> = Pair(ceil(l.w * 1.04).toInt(), ceil(l.h * 0.20).toInt())

    /** 每棵自己的层数 + 每层自己的抖宽 + 顺风不对称（⛔ 固定 5 层对称锯齿 = clip-art）。 */
    fun pinesDraw(cv: Canvas, w: Int, h: Int) {
        val n = p.fx.pines
        val rnd = SnowCedarMath.rng(p.tree.seed xor 0x5f3a)
        line.strokeCap = Paint.Cap.ROUND
        var i = 0
        while (i < n) {
            val u = (i + 0.15 + rnd.next() * 0.7) / n
            val xv = u * w
            val hh = h * (0.34 + rnd.next() * 0.56) * (0.75 + 0.25 * abs(u - 0.5) * 2)
            val bwid = hh * (0.30 + rnd.next() * 0.10)
            val base = (h - 1).toDouble()
            val tone = (136 + rnd.next() * 14).let { SnowCedarMath.jsRound(it).toInt() }
            fill.color = argb(0.32, tone - 10, tone, tone + 12)
            val tiers = 4 + rnd.next().let { (it * 4).toInt() }
            val lean = (rnd.next() - 0.5) * 0.16
            val jit = DoubleArray(tiers) { 0.82 + rnd.next() * 0.36 }
            path.reset()
            path.moveTo(xv.toFloat(), base.toFloat())
            var k = 0
            while (k < tiers) {
                val t2 = tierY(k, tiers, base, hh, bwid, jit)
                val xl = xv - t2[2] * (1 + lean)
                path.quadTo(
                    (xl - t2[2] * 0.16).toFloat(), (t2[0] - hh * 0.055).toFloat(),
                    xl.toFloat(), t2[0].toFloat(),
                )
                path.lineTo((xv - t2[2] * 0.45 * (1 + lean)).toFloat(), t2[1].toFloat())
                k++
            }
            path.lineTo(xv.toFloat(), (base - hh).toFloat())
            k = tiers - 1
            while (k >= 0) {
                val t2 = tierY(k, tiers, base, hh, bwid, jit)
                val xr = xv + t2[2] * (1 - lean)
                path.lineTo((xv + t2[2] * 0.45 * (1 - lean)).toFloat(), t2[1].toFloat())
                path.quadTo(
                    (xr + t2[2] * 0.16).toFloat(), (t2[0] - hh * 0.055).toFloat(),
                    xr.toFloat(), t2[0].toFloat(),
                )
                k--
            }
            path.close()
            cv.drawPath(path, fill)
            // 枝上积雪：塔形左上方几道白（弱到只读作"挂雪"，⛔ 抢戏成白条）
            line.color = argb(0.34, 246, 249, 253)
            line.strokeWidth = max(0.8, hh * 0.028).toFloat()
            k = 0
            while (k < 3) {
                val f = (k + 0.6) / tiers
                val yv = base - hh * (f + 0.4) * 0.95
                val ww = bwid * (1 - f * 0.72) * jit[min(tiers - 1, k)]
                path.reset()
                path.moveTo((xv - ww * 0.9).toFloat(), yv.toFloat())
                path.lineTo((xv - ww * 0.1).toFloat(), (yv - hh * 0.05).toFloat())
                cv.drawPath(path, line)
                k++
            }
            i++
        }
    }

    private fun tierY(k: Int, tiers: Int, base: Double, hh: Double, bwid: Double, jit: DoubleArray): DoubleArray {
        val f = k.toDouble() / tiers
        return doubleArrayOf(
            base - hh * (f + 1.0 / tiers) * 0.95 + hh * 0.030,
            base - hh * (f + 0.30) * 0.95,
            bwid * (1 - f * 0.72) * jit[k],
        )
    }

    /**
     * ⛔ `ctx.filter` 不可用（电视侧无对应能力）⇒ 用**自身三次错位叠印**做廉价柔化，
     * 把卡通硬边糊掉 = 远景 + 飞雪遮挡该有的样子（只在烘焙期跑，不计逐帧 ops）。
     * 之后根部 26% 用 `destination-out` 渐隐（⛔ 让松树"站"在一条硬线上）。
     *
     * ⚠️ 必须先整份拷到 [tmp] 再清空 [bmp] 自己叠：直接在原图上边读边写会把第一次叠印的结果
     * 当成第二次的源 ⇒ 越叠越糊且不可复现（原型 `mkC` 出 `tmp` 就是为了这一步）。
     */
    fun pinesSoften(bmp: Bitmap, tmp: Bitmap, w: Int, h: Int) {
        Canvas(tmp).drawBitmap(bmp, 0f, 0f, null)
        val cv = Canvas(bmp)
        cv.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val alphas = doubleArrayOf(0.62, 0.34, 0.34, 0.26)
        val dxs = doubleArrayOf(0.0, -1.7 * l.s, 1.7 * l.s, 0.0)
        val dys = doubleArrayOf(0.0, 0.6 * l.s, -0.6 * l.s, 1.7 * l.s)
        val pt = Paint(Paint.FILTER_BITMAP_FLAG)
        var i = 0
        while (i < 4) {
            pt.alpha = SnowCedarMath.jsRound(alphas[i] * 255).toInt()
            cv.save()
            cv.translate(dxs[i].toFloat(), dys[i].toFloat())
            cv.drawBitmap(tmp, 0f, 0f, pt)
            cv.restore()
            i++
        }
        fill.shader = LinearGradient(0f, (h * 0.74).toFloat(), 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, argb(0.92, 0, 0, 0)), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        fill.xfermode = xout
        cv.drawRect(0f, (h * 0.74).toFloat(), w.toFloat(), h.toFloat(), fill)
        fill.xfermode = null
        fill.shader = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  人物（本体 + 肩雪两张）
    // ══════════════════════════════════════════════════════════════════════════

    /** 本体 = 1 块接触暗影 + 18 块实心剪影；⚠️ alpha 恒 1.00（混用会叠出更深色块 = 破布感，P23）。 */
    fun personBody(cv: Canvas, sz: SnowPersonSize) {
        val parts = SnowPerson.poseParts(sz.hh.toDouble(), sz.w / 2.0, sz.top.toDouble(), 1.0, SnowPerson.STAND, 1.0)
        for (part in parts) drawPart(cv, part, sz.hh.toDouble(), SnowPerson.INK_COLOR)
    }

    /** 肩雪（另一张位图，逐帧按 `dust` 淡入 ⇒ ⛔ 烘进本体就演不出"越积越厚、阵风一抖又掉"）。 */
    fun personSnow(cv: Canvas, sz: SnowPersonSize) {
        val parts = SnowPerson.snowParts(sz.hh.toDouble(), sz.w / 2.0, sz.top.toDouble(), 1.0, SnowPerson.STAND)
        for (part in parts) drawPart(cv, part, sz.hh.toDouble(), SnowPerson.SNOW_COLOR)
    }

    private fun drawPart(cv: Canvas, part: SnowPart, hh: Double, inkColor: Int) {
        buildPath(part.pts)
        when (part.style) {
            SnowPerson.PART_SHADOW -> {
                fill.color = SnowPerson.SHADOW_COLOR
                fill.style = Paint.Style.FILL
                cv.drawPath(path, fill)
            }
            SnowPerson.PART_SNOW -> {
                fill.color = SnowPerson.SNOW_COLOR
                fill.style = Paint.Style.FILL
                cv.drawPath(path, fill)
            }
            else -> {
                // 实心 + round-join 同色描边 ⇒ 相邻块**融成一条连续轮廓**（⛔ 分别画会留缝）
                fill.color = inkColor
                fill.style = Paint.Style.FILL
                cv.drawPath(path, fill)
                line.color = inkColor
                line.strokeWidth = SnowPerson.strokeWidth(hh, part.inkK, part.swMul).toFloat()
                cv.drawPath(path, line)
            }
        }
        fill.style = Paint.Style.FILL
    }

    private fun buildPath(pts: DoubleArray) {
        path.reset()
        val n = pts.size / 2
        if (n < 2) return
        path.moveTo(pts[0].toFloat(), pts[1].toFloat())
        var i = 1
        while (i < n) {
            path.lineTo(pts[2 * i].toFloat(), pts[2 * i + 1].toFloat())
            i++
        }
        path.close()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  三条带 + grade（逐像素 ⇒ 天然按行分块）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 一行（或一块行带）的带状位图像素：**横向无缝**（`periodic` ⇒ 用角度采样）的白色 fbm。
     *
     * ⭐ 逐像素等于"逐行独立"⇒ 分帧后可以先显示上半（与 E46 的 grade 同一条性质）。
     * 写入的是**未预乘**值（`Bitmap.setPixels` 的契约）：源色恒为白 ⇒ `(A,255,255,255)`，
     * 逐字对应原型 `bakeBand()` 的 `d[k]=d[k+1]=d[k+2]=255; d[k+3]=round(a*255)`。
     */
    fun bandRows(px: IntArray, w: Int, h: Int, oct: Int, alpha: Double, ySeed: Double, j0: Int, j1: Int) {
        val ys = max(0, j0)
        val ye = min(h, j1)
        var j = ys
        while (j < ye) {
            val jf = j / h.toDouble()
            var i = 0
            var o = (j - ys) * w
            while (i < w) {
                val a = (i / w.toDouble()) * SnowCedarMath.TAU
                val v = SnowCedarMath.fbm(
                    cos(a) * 2.2 + 9.1,
                    sin(a) * 2.2 + jf * 3.0 + ySeed, oct, 0.55, 2.0,
                )
                val edge = SnowCedarMath.smoothstep(0.0, 0.22, jf) * (1 - SnowCedarMath.smoothstep(0.72, 1.0, jf))
                val al = SnowCedarMath.clamp((v - 0.42) * 1.9, 0.0, 1.0) * alpha * edge
                val b = u8(SnowCedarMath.jsRound(al * 255))
                px[o + i] = (b shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
                i++
            }
            j++
        }
    }

    /**
     * 暗角 + 颗粒**合并为一张** ⇒ 逐帧只 1 次铺屏。逐像素同样只依赖 `(i,j)` ⇒ 分帧行块性质成立。
     *
     * ⚠️ 原型 `bakeBand()` 还有个 `periodic=false` 分支，三条带**全部**传 `true`（原型 §4
     * `bakeAll()` 的三个调用）⇒ 该分支在原型里就是条死路，Kotlin 侧不照抄。
     *
     * ⚠️ 与 E46 不同：这里暗角半径是**绝对像素**（`diag`），换画幅必须重烘。
     * 写入必须走 `Bitmap.setPixels` 的**未预乘**契约（`Bitmap.java`：「a packed int
     * representing a **non-premultiplied** ARGB Color」）⇒ 源色 `(40,46,58)` **原样**写，
     * ⛔ 逐通道乘 `A`（本函数第一版就是这么写的：Skia 会再乘一次 ⇒ 暗角强度按
     * `A/255` 平方衰减，四角几乎不变暗；三条带同理会从"提亮成白絮"变成"压暗成灰斑"）。
     */
    fun gradeRows(px: IntArray, w: Int, h: Int, j0: Int, j1: Int) {
        val cx = w / 2.0
        val cy = h * 0.52
        val diag = hypot(w / 2.0, h / 2.0)
        val grain = p.fx.grain
        val grainOn = grain > 0.0
        val ys = max(0, j0)
        val ye = min(h, j1)
        var j = ys
        while (j < ye) {
            var i = 0
            var o = (j - ys) * w
            while (i < w) {
                val r = hypot(i - cx, j - cy) / diag
                var a = p.fx.vignette * SnowCedarMath.smoothstep(0.42, 1.06, r)
                if (grainOn) a += SnowCedarMath.hash2(i, j) * grain * 0.55
                val al = u8(SnowCedarMath.clamp(a, 0.0, 1.0) * 255)
                px[o + i] = (al shl 24) or (40 shl 16) or (46 shl 8) or 58
                i++
            }
            j++
        }
    }

    /** `bandRows` 与 `gradeRows` 的画幅（三条带**按屏宽烘焙** ⇒ 铺屏数与分辨率无关）。 */
    fun bandWidth(): Int = max(256, SnowCedarMath.jsRound(l.w).toInt())
    fun cloudHeight(): Int = max(64, SnowCedarMath.jsRound(l.h * 0.34).toInt())
    fun veilFarHeight(): Int = max(48, SnowCedarMath.jsRound(l.h * 0.16).toInt())
    fun veilNearHeight(): Int = max(48, SnowCedarMath.jsRound(l.h * 0.22).toInt())

    /**
     * 三条带的**画幅 + 烘焙参数**（原型 `bakeAll()` 那三个调用逐字搬来）。
     *
     * ⭐ 分帧派发只吃这张表 ⇒ 尺寸与 `oct / alpha / ySeed` 恒为同一份账；⛔ 在渲染器里另写一遍
     * `0.30 / 2.7`：抄错一位会改掉一整条带的对比度，而它在截图上读起来像"风大了/小了"，归因不回来。
     * 判据见 [SnowCedarBakeGateTest]。
     */
    fun bandSpec(kind: Int): SnowBandSpec? = when (kind) {
        SnowBakeKind.BAND_CLOUD -> SnowBandSpec(bandWidth(), cloudHeight(), 4, 0.30, 2.7)
        SnowBakeKind.BAND_VEIL_FAR -> SnowBandSpec(bandWidth(), veilFarHeight(), 3, 0.42, 8.1)
        SnowBakeKind.BAND_VEIL_NEAR -> SnowBandSpec(bandWidth(), veilNearHeight(), 3, 0.55, 4.3)
        else -> null
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  落笔小工具
    // ══════════════════════════════════════════════════════════════════════════

    /** 压扁的径向渐变（原型 `save→translate→scale(1,rh/rw)→arc(rw)` 的同一条形状）。 */
    private fun squashedRadial(cv: Canvas, mx: Double, my: Double, rw: Double, rh: Double, stops: FloatArray, colors: IntArray) {
        if (rw <= 0.0) return
        cv.save()
        cv.translate(mx.toFloat(), my.toFloat())
        cv.scale(1f, (rh / rw).toFloat())
        fill.shader = RadialGradient(0f, 0f, rw.toFloat(), colors, stops, Shader.TileMode.CLAMP)
        cv.drawCircle(0f, 0f, rw.toFloat(), fill)
        fill.shader = null
        cv.restore()
    }

    /** 原型的 `mound()`：白亮顶的三段渐变，透明度 `a` 只作用在第一段。 */
    private fun mound(cv: Canvas, mx: Double, my: Double, rw: Double, rh: Double, a: Double) {
        squashedRadial(cv, mx, my, rw, rh, floatArrayOf(0f, 0.62f, 1f),
            intArrayOf(argb(a, 255, 255, 255), argb(a * 0.62, 252, 253, 255), argb(0.0, 248, 251, 255)))
    }

    private fun fillEllipse(cv: Canvas, cx: Double, cy: Double, rx: Double, ry: Double, c: Int) {
        fill.color = c
        cv.save()
        cv.drawOval(
            (cx - rx).toFloat(), (cy - ry).toFloat(), (cx + rx).toFloat(), (cy + ry).toFloat(), fill,
        )
        cv.restore()
    }

    /** 单位系 → 位图像素（原型 `X`/`Y` 两个闭包）。 */
    private fun x(m: SnowTreeMeta, v: Double): Float = (m.pad + (v - m.bx0) * m.sx).toFloat()

    private fun y(m: SnowTreeMeta, v: Double): Float = (m.pad + (v - m.by0) * m.uy).toFloat()

    /** 原型的 `lk()`：基部色 → 末梢雪色按 `thin·0.86` 混，再乘基部明度 `k2`。 */
    private fun lk(v: Double, w: Double, k2: Double, thin: Double): Int =
        SnowCedarMath.jsRound(SnowCedarMath.lerp(v * k2, w, thin * 0.86)).toInt()

    private fun rgb(hex: Int): Int = Color.rgb((hex shr 16) and 255, (hex shr 8) and 255, hex and 255)

    companion object {
        /**
         * 各烘焙的**元素总数**（原型 §4 里写死的 `n`）：[SnowBakeQueue] 按它切块，对应的
         * `groundXxx / skyBlobs` 按它收尾 ⇒ ⛔ 两处各写一个数（改一处 ⇒ 队列最后一块永远烘不完，
         * 或反过来多出空块）。
         */
        const val SKY_BLOBS = 46
        const val GROUND_TONES = 7
        const val GROUND_VEINS = 130
        const val GROUND_MOUNDS = 16
        const val GROUND_UNDUL = 9
        const val GROUND_SPALL = 26

        /** 单个行块单元的像素上界（E46 同值）：粒度解析 ⇒ 超调最多一个单元。 */
        const val UNIT_PX_CAP = 24000

        /** 逐帧烘焙时间预算（毫秒）—— 与 E46 真机验收定稿的 24 ms 同档。 */
        const val BAKE_BUDGET_MS = 24L

        /** ⚠️ 必须**四舍五入**（原型 `Math.round`）：⛔ `toInt()` 截断会让暗角最外圈整环偏暗一档。 */
        private fun u8(v: Double): Int = max(0, min(255, SnowCedarMath.jsRound(v).toInt()))

        private fun argb(a: Double, r: Int, g: Int, b: Int): Int =
            Color.argb(max(0, min(255, SnowCedarMath.jsRound(a * 255).toInt())), r and 255, g and 255, b and 255)
    }
}

/** 一条带的画幅与 `fbm` 参数（[SnowCedarBakes.bandSpec] 的返回体）。 */
internal class SnowBandSpec(val w: Int, val h: Int, val oct: Int, val alpha: Double, val ySeed: Double)

/** 树位图的映射账（`B.treeMeta`）：⛔ 尺寸数字只能由 [SnowCedarBakes.treeMeta] 现算，黄金测试吃它。 */
internal class SnowTreeMeta(
    val w: Int, val h: Int, val pad: Int,
    val sx: Double, val uy: Double,
    val bx0: Double, val by0: Double,
    val baseX: Double, val baseY: Double,
    val screenX0: Double, val screenY0: Double,
)

/**
 * E47 首帧之后的**分帧烘焙队列**（纯 Kotlin：只排「烘什么」，⛔ 不碰 `Bitmap` ⇒ 可单测）。
 *
 * 顺序 = 收敛顺序：`sky` 底色（0 号单元，首帧必出画面）→ 雪原 → 大树 → 松林 → 人物 →
 * 三条带 → **grade 最后**。同一张位图内部保持原型的落笔次序（`ground` 的裁线在该图末尾）。
 *
 * ⚠️ 三条带与 grade 的画幅**各不相同**（带按屏宽 × 各自的固定高度，grade 是全屏），
 * 所以必须**逐个传进来** —— ⛔ 一套 `cw × ch` 包打天下：那会让三条带的行块数按全屏算，
 * 单元数与 [SnowCedarBakes.UNIT_PX_CAP] 的解析粒度双双失真（预算账就作废了）。
 */
internal class SnowBakeQueue(
    bandW: Int,
    cloudH: Int,
    veilFarH: Int,
    veilNearH: Int,
    gradeW: Int,
    gradeH: Int,
    segs: Int,
    snowBands: Int,
    clumps: Int,
) {
    val units = ArrayList<SnowBakeUnit>()
    private var cursor = 0
    val done: Boolean get() = cursor >= units.size

    init {
        units.add(SnowBakeUnit(SnowBakeKind.SKY_BASE, 0, 0))
        addChunks(SnowBakeKind.SKY_BLOBS, SnowCedarBakes.SKY_BLOBS, 8)
        units.add(SnowBakeUnit(SnowBakeKind.SKY_HORIZON, 0, 0))
        units.add(SnowBakeUnit(SnowBakeKind.GROUND_BASE, 0, 0))
        addChunks(SnowBakeKind.GROUND_TONES, SnowCedarBakes.GROUND_TONES, 4)
        addChunks(SnowBakeKind.GROUND_VEINS, SnowCedarBakes.GROUND_VEINS, 30)
        addChunks(SnowBakeKind.GROUND_MOUNDS, SnowCedarBakes.GROUND_MOUNDS, 8)
        units.add(SnowBakeUnit(SnowBakeKind.GROUND_TREE, 0, 0))
        addChunks(SnowBakeKind.GROUND_UNDUL, SnowCedarBakes.GROUND_UNDUL, 5)
        units.add(SnowBakeUnit(SnowBakeKind.GROUND_FOOT, 0, 0))
        units.add(SnowBakeUnit(SnowBakeKind.GROUND_CUT, 0, 0))
        addChunks(SnowBakeKind.GROUND_SPALL, SnowCedarBakes.GROUND_SPALL, 13)
        addChunks(SnowBakeKind.TREE_SEGS, segs, 400)
        addChunks(SnowBakeKind.TREE_SNOW_BASE, snowBands, 400)
        addChunks(SnowBakeKind.TREE_SNOW_RIDGE, snowBands, 400)
        addChunks(SnowBakeKind.TREE_CLUMPS, clumps, 80)
        units.add(SnowBakeUnit(SnowBakeKind.TREE_SKIRT, 0, 0))
        units.add(SnowBakeUnit(SnowBakeKind.PINES_DRAW, 0, 0))
        units.add(SnowBakeUnit(SnowBakeKind.PINES_SOFTEN, 0, 0))
        units.add(SnowBakeUnit(SnowBakeKind.PERSON, 0, 0))
        addRowUnits(SnowBakeKind.BAND_CLOUD, bandW, cloudH)
        addRowUnits(SnowBakeKind.BAND_VEIL_FAR, bandW, veilFarH)
        addRowUnits(SnowBakeKind.BAND_VEIL_NEAR, bandW, veilNearH)
        addRowUnits(SnowBakeKind.GRADE, gradeW, gradeH)
    }

    fun next(): SnowBakeUnit? = if (cursor >= units.size) null else units[cursor++]

    /** `total <= 0` ⇒ **一个单元也不排**（原型该阶段本就没有内容；塞空单元只会白占一次帧预算判定）。 */
    private fun addChunks(kind: Int, total: Int, step: Int) {
        var a = 0
        while (a < total) {
            val b = min(a + step, total)
            units.add(SnowBakeUnit(kind, a, b))
            a = b
        }
    }

    private fun addRowUnits(kind: Int, w: Int, h: Int) {
        val rows = max(1, SnowCedarBakes.UNIT_PX_CAP / max(1, w))
        var a = 0
        while (a < h) {
            val b = min(a + rows, h)
            units.add(SnowBakeUnit(kind, a, b))
            a = b
        }
    }
}

internal class SnowBakeUnit(val kind: Int, val a: Int, val b: Int)

internal object SnowBakeKind {
    const val SKY_BASE = 0
    const val SKY_BLOBS = 1
    const val SKY_HORIZON = 2
    const val GROUND_BASE = 3
    const val GROUND_TONES = 4
    const val GROUND_VEINS = 5
    const val GROUND_MOUNDS = 6
    const val GROUND_TREE = 7
    const val GROUND_UNDUL = 8
    const val GROUND_FOOT = 9
    const val GROUND_CUT = 10
    const val GROUND_SPALL = 11
    const val TREE_SEGS = 12
    const val TREE_SNOW_BASE = 13
    const val TREE_SNOW_RIDGE = 14
    const val TREE_CLUMPS = 15
    const val TREE_SKIRT = 16
    const val PINES_DRAW = 17
    const val PINES_SOFTEN = 18
    const val PERSON = 19
    const val BAND_CLOUD = 20
    const val BAND_VEIL_FAR = 21
    const val BAND_VEIL_NEAR = 22
    const val GRADE = 23
}
