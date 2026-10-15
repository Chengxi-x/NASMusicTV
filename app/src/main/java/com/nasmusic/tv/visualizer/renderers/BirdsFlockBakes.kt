package com.nasmusic.tv.visualizer.renderers

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/*
 * ============================================================================
 *  E49「鸟群」烘焙层（K4）—— **无状态画笔**（SnowCedarBakes 同款纪律）：
 *  只往调用方给的 `Canvas` 里写，一个 `Bitmap` 都不持有；位图分配 / 回收全在
 *  `BirdsFlockRenderer`（本效果烘焙全部走原生 `LinearGradient` / `RadialGradient` /
 *  `Path`，⭐ 无逐像素行循环 ⇒ 数 ms 内完成，**不需要** E47 的分帧烘焙队列）。
 *  首帧预算（§6.3 ≤100ms / ≤2 个 24ms 预算片）由"无行循环"这一事实保证。
 * ==========================================================================
 */
internal object BirdsFlockBakes {

    /**
     * 树带 tile 顶端在地平线上方的预留（D2：随设计高度缩放）。
     *
     * 上界推导（设计 px，s=1；记号 T=treeH、B=地面带高 180、j=baseJit、yf=brYf、a=brUpA、lf=brLf）：
     *  枝尖高于地平线 = (T−B·j)/2 + (T+B·j)·yf/2 + cos(a)·T·lf·0.85
     *  取 T=150、B=180、j=0.10、yf=0.90、a=0.40、lf=0.50 ⇒ 66 + 75.6 + 58.7 ≈ 200；
     *  再加末端小杈 T·lf·0.26 ≈ 19.5 ⇒ ≈ 220 ⇒ 取 250 覆盖全部参数组合 + 抗锯齿余量。
     * ⚠️ 原值 `160` **不够**：真机返工 D3 前，最高那棵树的**上段主干与枝杈被 tile 顶边整段裁掉**
     *   （烘焙时 `oy = hY − treePad`，超出部分直接画到 tile 外），表现为"树顶被削平"。
     */
    fun treePad(s: Float): Int = (250f * s).toInt() + 2

    /** 远树排 tile 高度（原型最高 46s px + 余量）。 */
    fun farTreePad(s: Float): Int = (64f * s).toInt() + 2

    /** 树带 tile 在地面带内的下沉（baseJit 最大 0.15 × 带高 + 余量 ⇒ 取 0.20 留够 5% 带高）。 */
    fun treeTileGroundPad(bandH: Float): Int = (bandH * 0.20f).toInt() + 2

    private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ─────────────────────── 天幕（§2.2）───────────────────────

    /**
     * 清冷高调暮色天（§2.2 + §8.3 F 淡金唇线）：天顶冷蓝紫灰 → 中段 → 地平线附近最亮，
     * 紧贴地平线一道**香槟金唇线**（极低饱和，⛔ 非暖红落日——与 E48 的第一读感区分点）。
     * 逐帧 1 次 blit；⛔ 原型红线：`createLinearGradient` 逐帧调用是全仓库禁手，**只在这里烘一次**。
     */
    fun bakeSky(c: Canvas, w: Int, hSky: Int, p: BirdsFlockParams) {
        val hF = p.horizonFrac
        val topL = p.skyTopL * 100f
        val horL = p.skyHorizonL * 100f
        val g = LinearGradient(
            0f, 0f, 0f, hSky.toFloat(),
            intArrayOf(
                aOf(1f, BirdsFlockMath.hslRgb(228f, 26f, topL)),
                aOf(1f, BirdsFlockMath.hslRgb(220f, 16f, topL + 14f)),
                aOf(1f, BirdsFlockMath.hslRgb(210f, 13f, horL - 6f)),
                aOf(1f, BirdsFlockMath.hslRgb(204f, 15f, horL + 3f)),
                aOf(1f, BirdsFlockMath.hslRgb(46f, 20f, horL - 6f)),
                aOf(1f, BirdsFlockMath.hslRgb(40f, 16f, horL - 2f)),
            ),
            floatArrayOf(0f, 0.42f, 0.78f, 0.92f, 0.985f, 1f),
            Shader.TileMode.CLAMP,
        )
        pt.shader = g
        c.drawRect(0f, 0f, w.toFloat(), hSky.toFloat(), pt)
        pt.shader = null
    }

    // ─────────────────────── 地面带（§2.2：恰为 1/5 屏）───────────────────────

    /** 暖褐地面：近地平线接光亮带 → 底部更深纵深（§8.3 F）；竖向渐变、无横向纹理。 */
    fun bakeGround(c: Canvas, w: Int, bandH: Int) {
        val g = LinearGradient(
            0f, 0f, 0f, bandH.toFloat(),
            intArrayOf(
                aOf(1f, BirdsFlockMath.hslRgb(36f, 20f, 30f)),
                aOf(1f, BirdsFlockMath.hslRgb(33f, 17f, 23f)),
                aOf(1f, BirdsFlockMath.hslRgb(31f, 15f, 18f)),
                aOf(1f, BirdsFlockMath.hslRgb(26f, 12f, 10f)),
            ),
            floatArrayOf(0f, 0.16f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        pt.shader = g
        c.drawRect(0f, 0f, w.toFloat(), bandH.toFloat(), pt)
        pt.shader = null
    }

    // ─────────────────────── 远景树排（1 op）───────────────────────

    /** 地平线上一排细小枯树剪影（比近树更淡更蓝 = 大气透视、更慢视差；原型 seed 777001）。
     *  D2（2026-10-15 真机返工）：尺寸随 s 缩放、干宽 ×1.4 —— 高 DPI 下细棍读感修复。 */
    fun bakeFarTrees(c: Canvas, w: Int, s: Float) {
        val rng = BfRng(777001)
        pt.color = aOf(0.52f, BirdsFlockMath.hslRgb(219f, 11f, 36f))
        val path = Path()
        for (i in 0 until 28) {
            val x0 = rng.next() * w
            val hgt = (16f + rng.next() * 30f) * s
            val wdt = (1.6f + rng.next() * 2.6f) * s * 1.4f
            val lean = (rng.next() - 0.5f) * hgt * 0.25f
            val x = ((x0 % w) + w) % w
            val base = farTreePad(s).toFloat()
            path.moveTo(x - wdt, base)
            path.lineTo(x + lean, base - hgt)
            path.lineTo(x + wdt, base)
            path.close()
            // 干中段两根细枝（追加 rng 消耗；同一确定序列 → 布局整体稳定）
            val by = base - hgt * 0.55f
            val b1 = rng.next() * hgt * 0.30f
            val b2 = rng.next() * hgt * 0.24f
            path.moveTo(x - wdt * 0.8f, by)
            path.lineTo(x - wdt * 0.8f - b1, by - hgt * 0.22f)
            path.lineTo(x - wdt * 0.4f, by)
            path.close()
            path.moveTo(x + wdt * 0.8f, by + hgt * 0.02f)
            path.lineTo(x + wdt * 0.8f + b2, by - hgt * 0.16f)
            path.lineTo(x + wdt * 0.4f, by + hgt * 0.02f)
            path.close()
        }
        c.drawPath(path, pt)
    }

    // ─────────────────────── 近景枯树（1 op）───────────────────────

    /**
     * 枯树剪影（原型 `drawTrees` 移植）：非对称主干 + 3~4 根不规则分枝，与栖点共享
     * [BirdsFlockFlock.treeGeom] ⇒ 停栖鸟必然坐在枝尖上。
     * tile 宽 = 画幅宽（与树分布同宽）⇒ 绘制期按 `topLeft.x = −treeOff` 平铺滚动。
     */
    fun bakeTrees(c: Canvas, w: Int, hY: Float, bandH: Float, flock: BirdsFlockFlock, s: Float) {
        pt.color = aOf(1f, BirdsFlockMath.hslRgb(230f, 12f, 8f))
        val geom = BirdsFlockTreeGeom()
        val path = Path()
        val topPad = treePad(s)
        for (i in 0 until flock.nTree) {
            flock.treeGeom(i, geom)
            val x = flock.treeX[i]
            val baseY = hY + bandH * geom.baseJit
            val topY = hY - flock.treeH[i]
            val hTrunk = baseY - topY
            val w0 = bandH * geom.w0f
            val bend = geom.bendf * w0
            val midX = x + bend
            val midY = baseY - hTrunk * 0.5f
            // 局部 y = 屏幕 y − (hY − treePad)
            val oy = hY - topPad
            // 主干下段（锥形 + 轻微弯曲）
            path.moveTo(x - w0, baseY - oy)
            path.quadTo(x - w0 * 0.45f + bend * 0.4f, baseY - hTrunk * 0.35f - oy, midX - w0 * 0.28f, midY - oy)
            path.lineTo(midX + w0 * 0.28f, midY - oy)
            path.quadTo(x + w0 * 0.50f + bend * 0.3f, baseY - hTrunk * 0.32f - oy, x + w0, baseY - oy)
            path.close()
            // 上段主干延伸到树梢（锥形收尖）
            path.moveTo(midX - w0 * 0.20f, midY - oy)
            path.lineTo(x + bend * 0.3f, topY - oy)
            path.lineTo(midX + w0 * 0.20f, midY - oy)
            path.close()
            // 冠部分枝 3~4 根（与栖点共享 treeGeom ⇒ 鸟坐在枝尖）
            // D2：枝宽系数 0.075 → 0.13、下限 1.2 → 2.0s —— 真机高 DPI 下细到不可见的枝（"树没显示出来"）修复
            for (b in 0 until geom.nBr) {
                val by = midY - (midY - topY) * geom.brYf[b]
                val bLen = flock.treeH[i] * geom.brLf[b]
                val tipX = x + geom.brSide[b] * sin(geom.brUpA[b]) * bLen
                val tipY = by - cos(geom.brUpA[b]) * bLen * 0.85f
                val bw = maxOf(2.0f * s, bLen * 0.13f)
                // 枝条 = 带宽度渐变的四边形（底宽 bw → 尖端收窄），不再是最细处不可见的三角薄片
                path.moveTo(x - bw * 0.5f, by - oy)
                path.lineTo(tipX, tipY - oy)
                path.lineTo(x + bw * 0.5f, by - oy)
                path.close()
                if (geom.brFork[b]) {
                    // 末端小杈：让剪影更"枯"
                    val fw = maxOf(1.6f * s, bw * 0.5f)
                    path.moveTo(tipX, tipY - oy)
                    path.lineTo(tipX + geom.brSide[b] * bLen * 0.26f, tipY - bLen * 0.26f - oy)
                    path.lineTo(tipX + fw * 0.5f, tipY + fw * 0.5f - oy)
                    path.close()
                    // 装饰细枝（hash 派生、非栖点、确定性：不耗 rng）
                    val dk = (bLen * 57f + tipX * 13f + tipY * 7f) % 5f
                    if (dk < 3f) {
                        path.moveTo(tipX, tipY - oy)
                        path.lineTo(tipX + geom.brSide[b] * bLen * 0.16f, tipY - bLen * 0.28f - oy)
                        path.lineTo(tipX + geom.brSide[b] * bLen * 0.30f, tipY - bLen * 0.10f - oy)
                        path.close()
                    }
                }
            }
        }
        c.drawPath(path, pt)
    }

    // ─────────────────────── 近景草丛（1 op）───────────────────────

    /** 稀疏弧簇（原型 seed 31415926）：非均匀散布、不等距（否掉"尺子线"）、不贴地平线、低对比。
     *  D2（2026-10-15 真机返工）：叶宽 1.1 → 2.0s px/侧、叶片加高加多 —— 高 DPI 下"竖棍"读感修复。 */
    fun bakeTufts(c: Canvas, w: Int, bandH: Int, s: Float) {
        val rng = BfRng(31415926)
        pt.color = aOf(0.68f, 0x17110A)   // rgba(23,17,10,0.68)：比地面略深，D2 真机裁块判读加对比
        val path = Path()
        val bandF = bandH.toFloat()
        val halfW = 2.0f * s   // 叶片半宽：原型 1.1px 在高 DPI 下不可见 ⇒ 2.0s
        for (cl in 0 until 16) {
            val cx = ((0.03f + 0.94f * rng.next()) * w)
            // ⛔ 不贴 tile 底（cy + 叶高 ≤ bandF，超出部分被 tile 边缘裁掉）
            val cy = bandF * (0.26f + 0.56f * rng.next())
            val blades = 3 + (rng.next() * 3f).toInt()
            for (b in 0 until blades) {
                val bx = cx + (rng.next() - 0.5f) * 14f * s
                val hgt = bandF * (0.05f + 0.06f * rng.next())
                val lean = (rng.next() - 0.5f) * hgt * 0.9f
                // 叶片：细弯三角（底→尖端→底），弯曲方向随机
                path.moveTo(bx - halfW, cy)
                path.quadTo(bx + lean * 0.4f, cy - hgt * 0.6f, bx + lean, cy - hgt)
                path.quadTo(bx + lean * 0.5f, cy - hgt * 0.55f, bx + halfW, cy)
                path.close()
            }
        }
        c.drawPath(path, pt)
    }

    // ─────────────────────── 调色（1 op，全屏）───────────────────────

    /**
     * 电影分色（§8.3 F：中央极淡暖微光 + 暗角）烘进一张全屏位图 ⇒ 1 blit / 1.000 屏。
     *
     * ⛔ **基类后处理必须关断**（`vignetteOverride` 恒 0）：再叠一次就是双暗角四角死黑
     * （R6 三段式的全部理由）。`postFx` 的字面量 `0.24f` 与这里的边缘 α **必须是同一个数**（§8.2）。
     * ⚠️ 原型是双圆 `createRadialGradient`（内圆暖光/外圆暗角两段）；Android 只有单圆
     * `RadialGradient` ⇒ 收敛为单圆三停点近似（中心 0.02 暖 → 0.55 透明 → 1.0 黑 0.24），已记偏差 D1。
     */
    fun bakeGrade(c: Canvas, w: Int, h: Int) {
        val cx = w * 0.5f
        val cy = h * 0.55f
        val r = h * 0.75f
        val g = RadialGradient(
            cx, cy, r,
            intArrayOf(
                aOf(0.02f, 0xFFC48C),   // 中央极淡暖微光（暮色中心）
                aOf(0f, 0x000000),
                aOf(0.24f, 0x000000),   // 暗角（与 postFx.vignette = 0.24f 同数）
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        pt.shader = g
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), pt)
        pt.shader = null
    }

    private fun aOf(a: Float, rgb: Int): Int = BirdsFlockMath.argbOf(a, rgb)
}
