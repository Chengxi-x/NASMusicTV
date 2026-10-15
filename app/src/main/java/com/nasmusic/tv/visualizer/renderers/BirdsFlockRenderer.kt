package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.FIXED_DT
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.MAX_SUBSTEPS
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.TIER_HIGH
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.TIER_LOW
import com.nasmusic.tv.visualizer.renderers.BirdsFlockMath.TIER_MEDIUM
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * ============================================================================
 *  E49「鸟群 · 掠过荒野」绘制侧（K5）
 *  方案：docs/e49-birds-flock-plan.md §五 / §5.5；原型：
 *  docs/e49-birds-flock-preview.html 的 `render()`（图层序就是契约）。
 *
 *  ## 逐帧结构（§5.5，11 op，与鸟数无关——§5.1 的全部意义）
 *  sky(1) → ground(1) → farTrees(1) → trees(1) → flock L0..L3(4) →
 *  perched(1) → tufts(1) → grade(1)
 *
 *  ## 五条不可动的红线
 *  1. ⛔ 零 `clipPath` / `clip` / `RoundedCornerShape`（API 22 创维 hwui SIGSEGV 全项目禁物）；
 *  2. ⛔ 零 `drawVertices`（API 29 > minSdk 22）；
 *  3. ⛔ 零 `save/rotate/restore`、零 `Matrix`：扑动/侧倾全部在**顶点坐标里**算完（§4.8.1）；
 *  4. ⛔ 逐帧零分配：`Paint`/`Path`/`Rect` 全是字段，每层一条 Path `rewind()` 后一次 fill（§5.7）；
 *  5. ⛔ 每鸟一次 `drawPath` = 600 op = 崩溃触发器的精确形状 ⇒ **合批不是优化，是能不能跑**（§5.1）。
 *
 *  ## postFx 三段式（R6，与 E46/E47 同形）
 *  暗角+颗粒已烘进 grade 位图 ⇒ [vignetteOverride] 恒 0 关断基类那一路；
 *  但 `postFx` 字面量必须留：① `FxCoverageScanTest` 的认定依据，② `needsDamageCoalescer`
 *  的输入，③ 与烘进位图的暗角强度同一个数（0.24，两处口径一致）。
 *  ⛔ 两条一起翻案：`postFx` 回 `NONE` ⇒ MED/HIGH 各多付 1.000 屏脏区合并；删掉
 *  `vignetteOverride` ⇒ 与烘焙位图叠两遍暗角。
 * ==========================================================================
 */
class BirdsFlockRenderer : RendererFx() {

    override val theme = VisualizerTheme.BIRDS_FLOCK

    /**
     * 字面量 `0.24f` 是刻意写的（见类头「postFx 三段式」）；基类那一次全屏暗角永远不画
     * （[vignetteOverride] 恒 0），暗角由 grade 位图承担。⛔ 与 `BirdsFlockBakes.bakeGrade`
     * 的边缘 α 0.24 必须同数；⛔ 不许改成 `PostFx.NONE` 或具名常量（E47 同一条教训）。
     */
    override val postFx = PostFx(vignette = 0.24f)

    /** 恒 `0f`：暗角由烘焙位图承担，基类那一次必须关掉（见 [postFx]）。⛔ 不许改成别的值。 */
    override fun vignetteOverride(fx: FxFrame): Float = 0f

    // ─────────────────────── 状态 ───────────────────────

    private val p = BirdsFlockParams()
    private val flock = BirdsFlockFlock(p)

    private var curTier = -1
    private var laidW = -1
    private var laidH = -1
    private var booted = false

    /** 固定步累加器（§5.8：`FIXED_DT=1/60`，最多 3 子步，超出丢余量——宁可慢放不追赶）。 */
    private var acc = 0f

    // ── 位图（本类全部持有；`boot` 建、`releaseBitmaps` 逐张 recycle）──
    private var bSky: Bitmap? = null
    private var bGround: Bitmap? = null
    private var bFarTrees: Bitmap? = null
    private var bTrees: Bitmap? = null
    private var bTufts: Bitmap? = null
    private var bGrade: Bitmap? = null

    /** 三条滚动带的平铺画笔（`Repeated` X / `Clamp` Y；⛔ 绝不 local matrix——E43 真机实测静默退化）。 */
    private var brushFarTrees: Brush? = null
    private var brushTrees: Brush? = null
    private var brushTufts: Brush? = null

    // ── 逐帧复用画笔 / 路径 / 矩形 ──
    private val ptBlit = Paint()
    private val ptFlock = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pathL0 = Path()
    private val pathL1 = Path()
    private val pathL2 = Path()
    private val pathL3 = Path()
    private val pathPerch = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()

    /** 4 个飞行深度层的剪影色（boot 时按 §2.3 混色表算一次；⛔ 同层内所有鸟同 α 同色）。 */
    private val layerColors = IntArray(4)

    // ─────────────────────── 生命周期 ───────────────────────

    /** 进入效果：先释放旧资源（画质变化会重入 `onEnter` 不经 `onExit`，E47 同一条理由）。 */
    override fun onEnterContent(ctx: RenderContext) {
        releaseAll()
        acc = 0f
    }

    override fun onExitContent() {
        releaseAll()
    }

    /** 位图 + 画笔（重排与离效果都要走这里）。⛔ 画笔先置 null 再 recycle（包着位图）。 */
    private fun releaseBitmaps() {
        brushFarTrees = null
        brushTrees = null
        brushTufts = null
        recycle(bSky); bSky = null
        recycle(bGround); bGround = null
        recycle(bFarTrees); bFarTrees = null
        recycle(bTrees); bTrees = null
        recycle(bTufts); bTufts = null
        recycle(bGrade); bGrade = null
    }

    private fun releaseAll() {
        releaseBitmaps()
        curTier = -1
        laidW = -1
        laidH = -1
        booted = false
    }

    private fun recycle(b: Bitmap?) {
        if (b != null && !b.isRecycled) b.recycle()
    }

    // ─────────────────────── 帧头 ───────────────────────

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw < 2 || ih < 2) return

        // ① 档位 / 画幅：任一变化 ⇒ 全量重烘 + 重置群（原型 `applyTier` 同款三步）
        var dirty = !booted
        val tier = BirdsFlockMath.tierOf(fx.level)
        if (tier != curTier) {
            curTier = tier
            dirty = true
        }
        if (iw != laidW || ih != laidH) {
            laidW = iw
            laidH = ih
            dirty = true
        }
        if (dirty) boot(iw, ih)

        // ② 音频只驱动「周边环境」（§七）：bass → 风场幅值、energy → 视差速度；
        //    ⛔ 不碰三规则权重 / maxSpeed / 邻居数（那会变成"随音乐抽搐"且不可复现）。
        //    无声（bass=energy=0）⇒ 两个乘子 = 1 ⇒ 风与视差都有纯时间基线，画面不死。
        flock.windScale = 1f + p.kBass * frame.bass
        flock.parallaxScale = 1f + p.kEnergy * frame.energy

        // ③ 固定时间步仿真（§5.8）
        acc += fx.dt
        var step = 0
        while (acc >= FIXED_DT && step < MAX_SUBSTEPS) {
            flock.step(FIXED_DT)
            acc -= FIXED_DT
            step++
        }
        if (step == MAX_SUBSTEPS) acc = 0f   // 丢余量：宁可慢放不追赶
        // ④ 扑动相位在渲染阶段推进（§5.8：扑动不参与动力学决策，单调内部相位，⛔ 不用 frame.timeMs）
        flock.stepVisual(fx.dt.coerceAtMost(0.1f))

        // ⑤ 落笔（原生 Canvas）
        val c = drawContext.canvas.nativeCanvas
        renderFrame(c, w, ih.toFloat())
    }

    // ─────────────────────── 逐帧结构（§5.5 图层序 = 契约，⛔ 换序）───────────────────────

    private fun DrawScope.renderFrame(c: Canvas, w: Float, h: Float) {
        val hY = h * p.horizonFrac
        val s = h / 900f   // D2：设计高度缩放因子（与 flock.scale() 同源）

        // ① 天幕（全屏 blit——本效果首尾两张全屏 op 之一，命中"无全屏操作"反面的安全侧）
        bSky?.let { bmp ->
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0f, 0f, w, bmp.height.toFloat())
            c.drawBitmap(bmp, srcRect, dstRect, ptBlit)
        }

        // ② 地面带（1 blit：0.20H，恰为 1/5 屏）
        bGround?.let { bmp ->
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0f, hY, w, hY + bmp.height)
            c.drawBitmap(bmp, srcRect, dstRect, ptBlit)
        }

        // ③ 远景树排（1 平铺带；更慢视差 → 天际线纵深）
        bFarTrees?.let { drawBand(brushFarTrees, it, hY - BirdsFlockBakes.farTreePad(s), flock.farTreeOff, w) }

        // ④ 近景枯树（1 平铺带；停栖点挂枝尖，栖鸟同帧跟随 treeOff）
        bTrees?.let { drawBand(brushTrees, it, hY - BirdsFlockBakes.treePad(s), flock.treeOff, w) }

        // ⑤-⑧ 鸟群 4 深度层（由远及近；每层一条 Path 一次 fill，⛔ op 数与鸟数无关）
        drawFlockLayer(c, w, h, 0, pathL0, s)
        drawFlockLayer(c, w, h, 1, pathL1, s)
        drawFlockLayer(c, w, h, 2, pathL2, s)
        drawFlockLayer(c, w, h, 3, pathL3, s)

        // ⑨ 停栖鸟（画在树之后 ⇒ 读得出"在树上"；与飞行层同族几何、同深度剪影色）
        drawPerched(c, w, h, s)

        // ⑩ 近景草丛（1 平铺带；最快视差 → 贴镜头参照速度）
        bTufts?.let { drawBand(brushTufts, it, hY, flock.tuftOff, w) }

        // ⑪ 调色（暗角 + 分色烘进一张全屏位图）
        bGrade?.let { bmp ->
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0f, 0f, w, bmp.height.toFloat())
            c.drawBitmap(bmp, srcRect, dstRect, ptBlit)
        }
    }

    /**
     * 一条滚动带 = **一次 Compose `drawRect`**（E47 `drawBand` 同款结构，但**着色器定位方式不同**）。
     *
     * ⛔⛔ **必须平移画布，不能只挪 `topLeft`**（2026-10-15 真机返工 D3；E47 同款代码的隐患同源）：
     * Compose 的 `drawRect(brush, topLeft, size)` **不会**把着色器挪到 `topLeft` ——
     * `CanvasDrawScope.drawRect-AsUm42w` 只把 `topLeft/size` 算成一个矩形后直接
     * `Canvas.drawRect(l, t, r, b, paint)`，而 `ShaderBrush.applyTo` 建着色器时
     * **不带任何 local matrix**（字节码逐条核对过）⇒ 着色器锚死在**画布原点**。
     * 后果（`TileMode.Repeated` X / `Clamp` Y）：
     *  · `topLeft.y > 0` ⇒ 着色器按**画布 y** 采样，采到 tile 之外 ⇒ `Clamp` 把 tile **最后一行铺满整条带**；
     *  · `topLeft.x = −off` ⇒ 只改了矩形的范围，**图案一点不动**（带子根本不滚）。
     * 真机症状（1920×1080 / 2712×1220 两档实测）：远树排 = 地平线上一排**等高竖棍**、
     * 近景枯树**整条消失**、近景草丛**整条消失**、栖鸟悬在半空（树没画出来）。
     * ⇒ 正确做法：`translate(−off, y)` 把**画布连同着色器**一起挪到带上沿，画完立刻反向平移还原。
     * ⛔ 不用 `save`/`restore`（本文件红线 3）；⛔ 不用 `setLocalMatrix`（E43 真机实测退化成 identity）。
     */
    private fun DrawScope.drawBand(brush: Brush?, bmp: Bitmap, y: Float, off: Float, w: Float) {
        val br = brush ?: return
        if (bmp.width <= 0 || bmp.height <= 0) return
        val c = drawContext.canvas.nativeCanvas
        c.translate(-off, y)
        drawRect(
            brush = br,
            topLeft = Offset.Zero,
            size = Size(w + bmp.width, bmp.height.toFloat()),
        )
        c.translate(off, -y)
    }

    // ─────────────────────── 鸟群合批（§5.2 / §4.8.1）───────────────────────

    /** 层尺寸锚点：穿层时按 dIdx 连续插值（⛔ 硬跳变读作"闪大"，原型 §8.3 F）。 */
    private fun baseL(d: Float): Float {
        val seg = min(2.999f, max(0f, d * 3f))
        val i0 = seg.toInt()
        return SIZE_STOPS[i0] + (SIZE_STOPS[i0 + 1] - SIZE_STOPS[i0]) * (seg - i0)
    }

    /**
     * 一层鸟：身体纺锤 + 主翼三角（tip 随 `sin(flapPh)` 上下摆，bank 压缩纵向摆幅）
     * + 远翼暗示三角（0.55 摆幅、略后移）——3 三角形 / 9 顶点全在顶点坐标里算完
     * （⛔ 不用 save/rotate、⛔ 不用 Matrix）。每层恰好 1 次 `drawPath`。
     */
    private fun drawFlockLayer(c: Canvas, w: Float, h: Float, li: Int, path: Path, s: Float) {
        var any = false
        for (i in 0 until flock.n) {
            if (flock.isPerched[i].toInt() != 0) continue
            if (layerOf(i) == li) {
                any = true
                break
            }
        }
        if (!any) return
        ptFlock.color = layerColors[li]
        path.rewind()
        for (i in 0 until flock.n) {
            if (flock.isPerched[i].toInt() != 0) continue
            if (layerOf(i) != li) continue
            val xi = flock.px[i] * w
            val yi = flock.py[i] * h
            val m = sqrt(flock.vx[i] * flock.vx[i] + flock.vy[i] * flock.vy[i]) + 1e-9f
            val axx = flock.vx[i] / m
            val ayy = flock.vy[i] / m
            // 同层大小抖动：确定性 hash（⛔ 不碰 RNG → 不扰动仿真随机序列）
            val iJit = 0.86f + 0.28f * (((i * (-1640531527)) ushr 8) % 1000) / 1000f
            // D2：尺寸随设计高度缩放（SIZE_STOPS 是 1600×900 的定值）
            val L = baseL(flock.dIdx[i]) * s * (flock.speedK[i] * 0.3f + 0.85f) * iJit
            // 主翼：tip 随 sin(flapPh) 摆动；bank 压扁纵向摆幅；上摆急促（s²）下摆平缓（§8.3 F）
            val s = sin(flock.flapPh[i])
            val bankC = 0.35f + 0.65f * abs(cos(flock.bank[i]))
            val sUp = max(0f, s) * max(0f, s) * 1.35f
            val sDn = min(0f, s)
            val rise = (sUp + sDn) * L * 0.36f * bankC
            // 翼尖随扑动划小弧：上摆略后收、下扑略前伸（±axx·0.07L·s）
            val tipX = xi + axx * L * (0.04f - 0.07f * s)
            val tipY = yi - rise
            val wrx = xi - axx * L * 0.38f
            val wry = yi - ayy * L * 0.38f
            val whx = xi + axx * L * 0.34f
            val why = yi + ayy * L * 0.34f
            path.moveTo(wrx, wry)
            path.lineTo(tipX, tipY)
            path.lineTo(whx, why)
            path.close()
            // 远翼暗示：摆幅 0.55、向尾后错位 → 双翼 V 形读感
            path.moveTo(wrx - axx * L * 0.06f, wry - ayy * L * 0.06f)
            path.lineTo(tipX - axx * L * 0.12f, yi - rise * 0.55f)
            path.lineTo(whx - axx * L * 0.06f, why - ayy * L * 0.06f)
            path.close()
            // 身体纺锤：头-尾-腹
            val hx = xi + axx * L * 0.46f
            val hy = yi + ayy * L * 0.46f
            val tx2 = xi - axx * L * 0.5f
            val ty2 = yi - ayy * L * 0.5f
            path.moveTo(hx, hy)
            path.lineTo(tx2, ty2)
            path.lineTo(xi, yi + L * 0.10f)
            path.close()
        }
        c.drawPath(path, ptFlock)
    }

    /** 层归属：来自每鸟深度态 dIdx（[0,1) 缓慢漂移穿层；⛔ 不按 py 分带——单一整群会塌层）。 */
    private fun layerOf(i: Int): Int = (flock.dIdx[i] * 4f).toInt().coerceAtMost(3)

    /**
     * 停栖鸟（原型 `drawPerched`）：与飞行鸟**同族** V 形几何（身体纺锤 + 下垂尾羽 + 收翼双翼），
     * 同一支 `ptFlock`，色取最深剪影 `rgba(8,5,3,1)`（§5.9：⛔ 不比飞行最近层更深）。
     * 位置由仿真侧跟随 `treeOff`（同一滚动量）⇒ 树滚鸟不悬空。
     */
    private fun drawPerched(c: Canvas, w: Float, h: Float, s: Float) {
        var any = false
        for (i in 0 until flock.n) {
            if (flock.isPerched[i].toInt() != 0) {
                any = true
                break
            }
        }
        if (!any) return
        ptFlock.color = PERCHED_COLOR
        pathPerch.rewind()
        for (i in 0 until flock.n) {
            if (flock.isPerched[i].toInt() == 0) continue
            val x = flock.px[i] * w
            val y = flock.py[i] * h
            val L = 8.2f * s   // D2：随设计高度缩放；§8.3 F 略微放大，停栖剪影更醒目
            // 身体纺锤（上）：头-肩-肩
            pathPerch.moveTo(x, y - L * 0.55f)
            pathPerch.lineTo(x + L * 0.065f, y - L * 0.05f)
            pathPerch.lineTo(x - L * 0.065f, y - L * 0.05f)
            pathPerch.close()
            // 尾羽（下）：肩-肩-尖尾 → 下垂尾羽读感
            pathPerch.moveTo(x + L * 0.065f, y - L * 0.05f)
            pathPerch.lineTo(x - L * 0.065f, y - L * 0.05f)
            pathPerch.lineTo(x, y + L * 0.42f)
            pathPerch.close()
            // 双翼：收在体侧、微微上翘 → 与飞行 V 形同族
            pathPerch.moveTo(x - L * 0.06f, y - L * 0.04f)
            pathPerch.lineTo(x - L * 0.56f, y - L * 0.46f)
            pathPerch.lineTo(x - L * 0.32f, y + L * 0.10f)
            pathPerch.close()
            pathPerch.moveTo(x + L * 0.06f, y - L * 0.04f)
            pathPerch.lineTo(x + L * 0.56f, y - L * 0.46f)
            pathPerch.lineTo(x + L * 0.32f, y + L * 0.10f)
            pathPerch.close()
        }
        c.drawPath(pathPerch, ptFlock)
    }

    // ─────────────────────── 建素材（首帧 / 换画幅 / 换档位）───────────────────────

    /**
     * `boot(w, h)` —— 位图与画笔只在这里建（`onEnter` 时画幅还是 `Size.Zero`，E46/E47 同款）。
     * 烘焙全部走原生渐变 / Path（**无逐像素行循环**）⇒ 数 ms 内完成，首帧预算（§6.3）成立，
     * ⛔ 不需要 E47 的分帧烘焙队列。
     */
    private fun boot(w: Int, h: Int) {
        booted = true
        releaseBitmaps()

        // ① 仿真布局 + 群重置（原型 applyTier：rebuildLayout → resetFlock）
        flock.layout(w, h, curTier.coerceAtLeast(TIER_LOW))

        // ② 层色表（§2.3：mix(skyMid, ink) 的四档；⛔ 同层内同 α 同色）
        for (li in 0 until 4) {
            val mix = INK_MIX[li]
            val a = LAYER_ALPHA[li]
            val cr = (44f * (1f - mix) + 8f * mix + 0.5f).toInt()
            val cg = (48f * (1f - mix) + 4f * mix + 0.5f).toInt()
            val cb = (58f * (1f - mix) + 2f * mix + 0.5f).toInt()
            layerColors[li] = (a shl 24) or (cr shl 16) or (cg shl 8) or cb
        }

        // ③ 位图（全 ARGB_8888；tile 高度随设计高度缩放，D2）
        val s = h / 900f
        val hYi = (h * p.horizonFrac).toInt()
        val bandHi = h - hYi
        bSky = newBmp(w, hYi + 1)
        bGround = newBmp(w, bandHi)
        bFarTrees = newBmp(w, BirdsFlockBakes.farTreePad(s))
        bTrees = newBmp(w, BirdsFlockBakes.treePad(s) + BirdsFlockBakes.treeTileGroundPad(bandH = h - hYi.toFloat()))
        bTufts = newBmp(w, bandHi)
        bGrade = newBmp(w, h)

        // ④ 烘焙（无状态画笔：只往本类给的 Canvas 写）
        val hF = h.toFloat()
        bSky?.let { BirdsFlockBakes.bakeSky(Canvas(it), w, it.height, p) }
        bGround?.let { BirdsFlockBakes.bakeGround(Canvas(it), w, it.height) }
        bFarTrees?.let { BirdsFlockBakes.bakeFarTrees(Canvas(it), w, s) }
        bTrees?.let {
            BirdsFlockBakes.bakeTrees(
                Canvas(it), w, hYi.toFloat(), hF - hYi, flock, s,
            )
        }
        bTufts?.let { BirdsFlockBakes.bakeTufts(Canvas(it), w, it.height, s) }
        bGrade?.let { BirdsFlockBakes.bakeGrade(Canvas(it), w, h) }

        // ⑤ 三条滚动带的平铺画笔（X 向 Repeated 无缝循环 / Y 向 Clamp）
        brushFarTrees = bFarTrees?.let { bandBrushOf(it) }
        brushTrees = bTrees?.let { bandBrushOf(it) }
        brushTufts = bTufts?.let { bandBrushOf(it) }
    }

    /** 一条带的平铺画笔：X 向 `Repeated`（无缝循环）、Y 向 `Clamp`。 */
    private fun bandBrushOf(bmp: Bitmap): Brush =
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Clamp))

    private fun newBmp(w: Int, h: Int): Bitmap? =
        if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    companion object {
        /** 层尺寸锚点（原型 `sizeStops=[10,14,19,26]`）。 */
        private val SIZE_STOPS = floatArrayOf(10f, 14f, 19f, 26f, 26f)

        /** 大气透视混色表（原型 `inkMix=[0.30,0.48,0.70,1.0]`：越远越偏天空色）。 */
        private val INK_MIX = floatArrayOf(0.30f, 0.48f, 0.70f, 1.0f)

        /** 层 α（原型 `[0.45,0.60,0.80,1.0]`）。 */
        private val LAYER_ALPHA = intArrayOf(115, 153, 204, 255)

        /** 停栖剪影色 `rgba(8,5,3,1)`（⛔ 不比飞行最近层 `rgba(8,4,2,1)` 更深，§2.3）。 */
        private const val PERCHED_COLOR = 0xFF080503.toInt()

        /** `FxLevel → 档位`（对外暴露给单测，与 [BirdsFlockMath.tierOf] 同源）。 */
        internal fun tierOf(level: FxLevel): Int = when (level) {
            FxLevel.OFF -> TIER_LOW
            FxLevel.LITE -> TIER_MEDIUM
            FxLevel.FULL -> TIER_HIGH
        }
    }
}
