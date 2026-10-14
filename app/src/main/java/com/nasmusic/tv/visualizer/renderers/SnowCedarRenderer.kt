package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import kotlin.math.max

/*
 * ============================================================================
 *  E47「雪松」绘制侧（K5 / K7）
 *  原型：`docs/archive/e47-snow-cedar-preview.html` §10 的 `renderFrame` 与全部 `draw*`
 *  （`:1233-1414`）。本文件**只做重放**：几何与烘焙全部在
 *  `SnowCedarParams` / `SnowCedarMath` / `SnowCedarTree` / `SnowCedarBakes` /
 *  `SnowField` / `SnowPerson` / `SnowWorld` 里，逐帧只做「取状态 → 落笔」。
 * ==========================================================================
 *
 * ## 三条不可动的结构纪律
 *
 * 1. **本类拥有全部 `Bitmap`**。烘焙层（[SnowCedarBakes]）是**无状态画笔**——它只往
 *    调用方给的 `Canvas`/`IntArray` 里写，一个位图都不持有、⛔ 也没有 `bakeAll()`。
 *    于是分配/回收全在本类：[boot] 建、[releaseBitmaps]（`onEnter` 首行与 `onExitContent` 共用）
 *    逐张 `recycle`。
 * 2. **时间只有一个自变量 `tSec`**（由 `fx.dt` 累加，⛔ 不是 `ctx.nowMs`、⛔ 不是墙钟），
 *    音频只是**增益**（`bass/energy/treble/energy` 四条律动通道，⛔ 永不碰 `frame.spectrum`）。
 *    无声 ⇒ 静态定稿帧（T8）。
 * 3. **逐帧零分配**：`Paint`/`Path`/`Rect(F)`/`Shader`/数组全是字段；
 *    `drawContent` 里唯一的分配点被 `PerfBudgetContractTest` 的源码扫描盯着。
 *
 * ## 为什么暗角走位图、而 `postFx` 仍要留一个字面量
 * 暗角**与**颗粒已经由 [SnowCedarBakes.gradeRows] **烘在同一张 grade 位图**里
 * （逐像素只依赖 `(i,j)` ⇒ 天然可分帧 ⇒ 逐帧只 1 次 blit / 1.000 屏）。基类后处理
 * 再叠一次就是**第二遍暗角 + 第二遍颗粒**（四角压到死黑 + 两层噪点互咬）。
 *
 * ⚠️ 但**不能**因此把 `postFx` 写成 `PostFx.NONE`：`NONE` 会让
 * `RendererFx.needsDamageCoalescer` 在 **LITE/FULL 也为真**，于是每帧白补一次整画布
 * `drawRect`（`OverlayFx.drawDamageCoalescer`），**肉眼不可见但铺屏整整 1.000 屏**，
 * 且本类每帧头一笔就是全屏 `blitFull(bSky)` ⇒ 脏区早已并成一整块，这次补画**零收益**。
 * 这正是 E46「迷雾之战」先踩过的那条路（见 `TripodFogRenderer.postFx` 的三重身份 KDoc）。
 *
 * ⇒ 形制 = **字面量留着 + `vignetteOverride` 恒 `0f` 关掉基类那一路**（与 E46 逐字同款）。
 * 详见 [postFx] 字段上的 KDoc；⛔ 两条必须一起翻案。
 */
class SnowCedarRenderer : RendererFx() {

    override val theme = VisualizerTheme.SNOW_CEDAR

    /**
     * ⭐ **字面量 `0.34f` 是刻意写的，而基类那一次全屏暗角永远不画**（2026-10-14 真机帧率返工）。
     *
     * 暗角与颗粒**已烘进 `grade` 位图**（类头「为什么暗角走位图」），所以 `applyPostFx` 一次都不该发生。
     * 但把 `postFx` 写成 `PostFx.NONE` 有个**探针看不到的代价**：
     * `RendererFx.needsDamageCoalescer(level, postFx) = level == OFF || !postFx.hasFullScreenPass()`
     * ⇒ `NONE` 时 `hasFullScreenPass()` 恒假 ⇒ 该判定在 **LITE/FULL 也为真** ⇒ `OverlayFx.drawDamageCoalescer()`
     * 每帧补一次**整画布** `drawRect`（`size = size`，alpha = 1/255）。⚠️ 那次绘制肉眼不可见，
     * **铺屏却是整整 1.000 屏**，而 E47 本来每帧头一笔就是 `blitFull(bSky)`（已是全屏矩形）
     * ⇒ 脏区早被并成一整块，这次补画**什么也没换来**。
     *
     * ⇒ 与 E46（`TripodFogRenderer`）**同款形制**：字面量留着（`FxCoverageScanTest.postFxRe` 与
     * `needsDamageCoalescer` 两个下游都读它），真正的绘制用 [vignetteOverride] 关掉。
     * ⛔ 两条一起翻案：`postFx` 回 `NONE` ⇒ MED/HIGH 各多付 1.000 屏（6.11 → 7.11）；
     * 或删掉 `vignetteOverride` ⇒ 暗角与烘焙位图**叠两遍**。
     * ⚠️ `FxLevel.OFF`（LOW）时 `needsDamageCoalescer` 因 `level == OFF` 恒真，那一屏在基类层面**省不掉**。
     */
    override val postFx = PostFx(vignette = 0.34f)

    /** 恒 `0f`：暗角由烘焙位图承担，基类那一次必须关掉（见 [postFx]）。⛔ 不许改成别的值。 */
    override fun vignetteOverride(fx: FxFrame): Float = 0f

    // ─────────────────────── 参数与长驻状态 ───────────────────────

    private val p = SnowCedarP()
    private val lay = SnowCedarLayout()
    private val bakes = SnowCedarBakes(p, lay)

    private val world = SnowWorldState(p, lay)
    private val audio = SnowAudio(p)
    private val dust = SnowPersonDust(p)

    /** 五层雪 + 冰晶闪光点表。`allocFlakes/allocGlints` 是它唯一的换数组入口。 */
    private val field = SnowField(p, lay)

    /** 大树（单位系）与它的位图映射账；[boot] 里一次生成。 */
    private var tree: SnowCedarTree.Tree? = null
    private var treeMeta: SnowTreeMeta? = null
    private var personSz: SnowPersonSize? = null

    /** 动画时基（秒）：只由 `fx.dt` 累加。 */
    private var tSec = 0.0

    /** 已应用的档位（`null` = 还没应用过 ⇒ 首帧必走一次 `applyLevel`）。 */
    private var curLevel: SnowLevel? = null
    private var laidW = -1
    private var laidH = -1
    private var booted = false

    // ─────────────────────── 位图（本类全部持有） ───────────────────────

    private var bSky: Bitmap? = null
    private var bGround: Bitmap? = null
    private var bPines: Bitmap? = null
    private var bPinesTmp: Bitmap? = null
    private var bTree: Bitmap? = null
    private var bPerson: Bitmap? = null
    private var bPersonSnow: Bitmap? = null
    private var bCloud: Bitmap? = null
    private var bVeilFar: Bitmap? = null
    private var bVeilNear: Bitmap? = null
    private var bGrade: Bitmap? = null

    /** 与上面六张**同生命周期**的 `Canvas`（烘焙期落笔用；⛔ 不逐单元新建）。 */
    private var cSky: Canvas? = null
    private var cGround: Canvas? = null
    private var cPines: Canvas? = null
    private var cTree: Canvas? = null
    private var cPerson: Canvas? = null
    private var cPersonSnow: Canvas? = null

    /** 分帧烘焙队列（`null` = 尚未 [boot] 或已交出）。 */
    private var bakeQueue: SnowBakeQueue? = null

    /**
     * 行块暂存：**每个行块 kind 一份**（三条带 + grade），容量到 [SnowCedarBakes.UNIT_PX_CAP]
     * ⇒ 任何**一个**行单元都装得下（⛔ 逐单元 `IntArray(...)` = 每帧分配）。
     * 下标 = `SnowBakeKind.BAND_CLOUD / BAND_VEIL_FAR / BAND_VEIL_NEAR / GRADE`。
     */
    private val rowPx = arrayOfNulls<IntArray>(4)

    // ─────────────────────── 逐帧复用的画笔与着色器 ───────────────────────

    private val ptBlit = Paint()
    private val ptDust = Paint()
    private val ptSnow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ptGlint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GLINT_COLOR
    }
    private val pathStreak = Path()
    private val pathGlint = Path()

    /**
     * 三条带的**平铺画笔**（`ShaderBrush(ImageShader(..., Repeated, Clamp))`，逐帧只换
     * `topLeft.x` 与 `alpha`）。
     *
     * ⛔⛔ **绝不靠 local matrix 做横向偏移**（E43 真机实测：`Shader.setLocalMatrix` 在硬件画布上
     * **静默退化成 identity**，带子整条不动、看起来"风停了"，而画面没有任何报错）⇒ 本文件里
     * 三条带的偏移**只**由 [drawBand] 的 `topLeft.x = −off` 承担，⛔ 任何 local matrix 都不许出现。
     *
     * ⚠️ `asImageBitmap()` 只是把同一块 native 像素**包一层**（不复制，`ProceduralTexture` 同款），
     * 所以烘焙期用 `Canvas`/`setPixels` 往里写、绘制期用 `ShaderBrush` 读，是同一张位图。
     * ⚠️ 位图 `recycle` 之前必须先把这三个画笔置 `null`（[releaseBitmaps] 的顺序即此约定）。
     */
    private var brushCloud: Brush? = null
    private var brushVeilFar: Brush? = null
    private var brushVeilNear: Brush? = null

    /**
     * 日晕的两层径向渐变（外晕 `2.1R × 0.62`、内核 `1R × 1`）。
     *
     * ⭐ 停点里的 α 只按**满档** `0.6` 建一次，逐帧强度走 `ptSun.alpha = a / 0.6`
     * （SkPaint 的 α 对着色器输出是**乘性**的 ⇒ 线性等价，⛔ 不必逐帧新建 `RadialGradient`）。
     * 中心/半径是**绝对像素**（[SnowCedarLayout.sunX/sunY/sunR]）⇒ 画幅一变整对作废。
     */
    private var shSunOuter: RadialGradient? = null
    private var shSunInner: RadialGradient? = null
    private val ptSun = Paint()

    private val srcRect = Rect()
    private val dstRect = RectF()

    /** dot/bokeh 两桶的点表（各 `2·n` 个 float，整轮复用；⛔ 不按层各开一份）。 */
    private var bufLo = FloatArray(0)
    private var bufHi = FloatArray(0)

    /** 网格顶点：`(meshW+1)(meshH+1)·2`，`meshW = 1`、`meshH = rows−1` ⇒ `4·rows`。 */
    private var meshOff = DoubleArray(0)
    private var meshVerts = FloatArray(0)
    private var meshCells = 0

    // ─────────────────────── 生命周期 ───────────────────────

    /**
     * 进入效果：**首行释放旧资源**，再复位时间轴与两条包络。
     *
     * ⛔ 首行这个 `releaseAll()` 不是洁癖：**画质变化会重入 `onEnter`**（不经过 `onExit`），
     * 不释放就是上一张画幅的十几张位图 + 三块画笔整份常驻 native 堆（本效果 ≈30 MB @1080p）。
     *
     * ⚠️ 位图不在这里分配：`onEnter` 时 `ctx.canvasSize` 还是 `Size.Zero`，画幅只能从首帧的
     * `DrawScope.size` 取 ⇒ 走 E46 的 `boot(w, h)` 模式（见 [boot]）。
     */
    override fun onEnterContent(ctx: RenderContext) {
        releaseAll()
        tSec = 0.0
        audio.reset()
        dust.reset()
    }

    /**
     * 离效果：**逐张 `recycle`**。API 22–25 的 `ARGB_8888` 像素在 native 堆、不受 GC 自愈
     * （§11.3.6 / `RendererFx.onExit` 对 `ProceduralTexture` 的同一理由）。
     *
     * ⚠️ 顺序是契约：先置 `null` 画笔（它包着位图），**再** `recycle`；反过来就是拿回收了的
     * 位图去建着色器。
     */
    override fun onExitContent() {
        releaseAll()
        tSec = 0.0
    }

    /**
     * 位图 + 画笔 + 队列（**重排与离效果都要走这里**）。
     *
     * ⛔ 画笔先置 `null`：它只是包着位图，位图 `recycle` 之后再被绘制就是 native 崩溃。
     */
    private fun releaseBitmaps() {
        brushCloud = null
        brushVeilFar = null
        brushVeilNear = null
        recycle(bSky); bSky = null
        recycle(bGround); bGround = null
        recycle(bPines); bPines = null
        recycle(bPinesTmp); bPinesTmp = null
        recycle(bTree); bTree = null
        recycle(bPerson); bPerson = null
        recycle(bPersonSnow); bPersonSnow = null
        recycle(bCloud); bCloud = null
        recycle(bVeilFar); bVeilFar = null
        recycle(bVeilNear); bVeilNear = null
        recycle(bGrade); bGrade = null
        cSky = null
        cGround = null
        cPines = null
        cTree = null
        cPerson = null
        cPersonSnow = null
        bakeQueue = null
        for (i in rowPx.indices) rowPx[i] = null
    }

    /** [releaseBitmaps] + 派生状态与缓冲（`onEnter` 首行与 `onExitContent` 共用）。 */
    private fun releaseAll() {
        releaseBitmaps()
        bufLo = FloatArray(0)
        bufHi = FloatArray(0)
        meshOff = DoubleArray(0)
        meshVerts = FloatArray(0)
        meshCells = 0
        shSunOuter = null
        shSunInner = null
        tree = null
        treeMeta = null
        personSz = null
        curLevel = null
        laidW = -1
        laidH = -1
        booted = false
    }

    private fun recycle(b: Bitmap?) {
        if (b != null && !b.isRecycled) b.recycle()
    }

    // ─────────────────────── 帧头：档位 / 画幅 / 时间轴 / 音频 ───────────────────────

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        val iw = w.toInt()
        val ih = h.toInt()
        if (iw < 2 || ih < 2) return

        // ① 档位 / 画幅：任一变化 ⇒ 整组重烘（原型 `resizeTo` 同款三步：layout → bakeAll →
        //    allocFlakes/allocGlints → resetPerson）。`applyLevel` 只在档位**真的变了**时跑
        //    （它改的是整张参数表，逐帧重跑没有意义，且会让"变化"这一判据永远为真）。
        var dirty = !booted
        val level = snowLevelOf(fx.level)
        if (level != curLevel) {
            curLevel = level
            p.applyLevel(level)
            dirty = true
        }
        if (iw != laidW || ih != laidH) {
            laidW = iw
            laidH = ih
            lay.layout(w.toDouble(), h.toDouble(), p)
            dirty = true
        }
        // ⭐ 画幅**只在首帧 `drawContent`** 才拿得到（`onEnter` 时 `ctx.canvasSize` 还是
        //    `Size.Zero`）⇒ 位图与队列一律在这里建（E46 `boot(w,h)` 同款），⛔ 不在 `onEnter` 里建。
        if (dirty) boot(iw, ih)
        // ② 分帧烘焙：⭐ `SKY_BASE` 是 0 号单元且预算判定在**每个单元之后** ⇒ 首帧必出天幕，
        //    绝不会黑屏（[SnowCedarBakes] 的类头 KDoc 与 [SnowBakeQueue] 是同一条契约）。
        bakeStep()

        // ③ 时间轴 + 音频（原型 `stepAll` 的顺序：clockT → smooth → world → flakes → person）。
        //    ⚠️ `fx.dt` 本身已是秒（`FrameClock`），⛔ 不再除 1000（E44 偏差 D31）。
        val dtSec = fx.dt.toDouble()
        tSec += dtSec
        audio.update(frame.bass.toDouble(), frame.energy.toDouble(), frame.treble.toDouble(), dtSec)
        world.state(tSec, audio.bass, audio.energy, audio.treble)
        field.stepFlakes(dtSec, tSec, world.windPx, world.fallPx)
        dust.step(dtSec, world.gust)

        val c = drawContext.canvas.nativeCanvas
        renderFrame(c, w)
    }

    /**
     * 原型 `renderFrame` 的**逐字移植**（`:1393-1414`）。图层序就是契约，⛔ 换序。
     *
     * 顺序：sky → sun(2) → clouds → pines → veilFar → ground → glints →
     * snow L0/L1 → tree → snow L2 → person(+肩雪) → veils → snow L3 → grade → snow L4。
     * ⭐ **L4 必须留在 grade 之后**：前景虚化贴镜头，画在调色之前会被暗角压掉（原型注释原话）。
     *
     * ⚠️ 位图层走**原生 `Canvas`**（`c`），三条平铺带走 **Compose `drawRect`**（[drawBand]）——
     * 两条路径各走各的，⛔ 不要把带子改成原生 `drawRect`（那会需要 local matrix，见 [drawBand]）。
     */
    private fun DrawScope.renderFrame(c: Canvas, w: Float) {
        val H = lay.h

        // ① 天幕（全屏 1 blit）
        bSky?.let { blitFull(c, it, w) }

        // ② 模糊日晕：两层径向渐变，**外晕先画**（原型 `:1248-1249`），⛔ 无硬边日盘。
        drawSun(c)

        // ③ 阴云层（每带 1 图元）
        val cloudBmp = bCloud
        if (cloudBmp != null) {
            val br = brushCloud
            if (br != null) {
                val nBand = p.fx.cloudBands
                var i = 0
                while (i < nBand) {
                    drawBand(br, cloudBmp, 0.35 + i * 0.25, H * (0.02 + i * 0.16), w, 0.55 - i * 0.10)
                    i++
                }
            }
        }

        // ④ 远景松林（1 blit）
        bPines?.let {
            val pw = it.width.toDouble()
            val ph = it.height.toDouble()
            val x0 = (w.toDouble() - pw) / 2.0
            val y0 = lay.hz - ph + SnowCedarMath.jsRound(H * 0.012)
            c.drawBitmap(it, x0.toFloat(), y0.toFloat(), ptBlit)
        }

        // ⑤ 远景雾带（1 图元）
        val farBmp = bVeilFar
        if (farBmp != null) {
            brushVeilFar?.let { drawBand(it, farBmp, 0.30, lay.hz - H * 0.075, w, 0.55) }
        }

        // ⑥ 雪原：源 (0, top, w, h−top) → 同位（位图是全屏尺寸，⛔ 不是缩放 blit）
        drawGround(c, w)

        // ⑦ 冰晶闪光（**一条 Path + 一次 fill**；cull 在 `glintBright` 里，⛔ 不重复实现）
        drawGlints(c)

        // ⑧ 极远 / 远层雪（落雪深度比树还远 ⇒ 画在树**后**）
        drawSnowLayer(c, LAYER_FAR_A)
        drawSnowLayer(c, LAYER_FAR_B)

        // ⑨ 大树风摆（**一次 `drawBitmapMesh`**：原型 `rows` 次切片折叠而来）
        drawTree(c)

        // ⑩ 中层雪（land ≈ 0.36 ≈ 树基深度 ⇒ 树前、人后）
        drawSnowLayer(c, LAYER_MID)

        // ⑪ 人（本体 + 按 `dust` 淡入的肩雪，⛔ blit 矩形只由布局决定）
        drawPerson(c)

        // ⑫ 贴地吹雪带（`p.fx.veils` 图元）
        val nearBmp = bVeilNear
        if (nearBmp != null) {
            brushVeilNear?.let { br ->
                var i = 0
                val nVeil = p.fx.veils
                while (i < nVeil) {
                    drawBand(br, nearBmp, 0.55 + i * 0.30,
                        lay.hz + H * (0.02 + i * 0.11), w, 0.34 - i * 0.05)
                    i++
                }
            }
        }

        // ⑬ 近层雪（带拖尾，画在人物**前**）
        drawSnowLayer(c, LAYER_STREAK)

        // ⑭ 调色（暗角 + 颗粒，**烘在一起** ⇒ 1 blit / 1.000 屏）
        bGrade?.let { blitFull(c, it, w) }

        // ⑮ 前景虚化雪（⛔ 必须在 grade 之后）
        drawSnowLayer(c, LAYER_BOKEH)
    }

    // ─────────────────────── 图层实现 ───────────────────────

    /** 全屏 blit（`sky` / `grade`）：位图尺寸 = 画幅的**整数**尺寸 ⇒ 1:1，不拉伸。 */
    private fun blitFull(c: Canvas, bmp: Bitmap, w: Float) {
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0f, 0f, w, bmp.height.toFloat())
        c.drawBitmap(bmp, srcRect, dstRect, ptBlit)
    }

    /** 雪原：只贴 `hz − 0.02H` 以下那段（原型 `drawGround` 的九参 `drawImage`，1:1 不缩放）。 */
    private fun drawGround(c: Canvas, w: Float) {
        val bmp = bGround ?: return
        val top = SnowCedarMath.clamp(lay.hz - lay.h * 0.02, 0.0, lay.h)
        val ti = top.toInt()
        if (ti < 0 || ti >= bmp.height) return
        srcRect.set(0, ti, bmp.width, bmp.height)
        dstRect.set(0f, ti.toFloat(), w, bmp.height.toFloat())
        c.drawBitmap(bmp, srcRect, dstRect, ptBlit)
    }

    /**
     * 模糊日晕：外晕 `2.1R`（`×0.62`）**先**画，内核 `R`（`×1`）后画。
     *
     * ⭐ **用 `drawCircle` 而不是 `drawRect`**（2026-10-14 真机帧率返工，零视觉代价，**−0.382 屏**）。
     * 两个渐变各自按**自己的半径**建在 `boot` 里（`RadialGradient(x, y, rIn/rOut, …)`），
     * `TileMode.CLAMP` ⇒ 圆内采样与矩形版**逐像素相同**；圆外那 4 块角在矩形版里本就是 α=0 的像素
     * （最后一个色标即 `rgba(…,0)`）⇒ 不画它们没有任何差别。
     * 面积账（`sunR = 0.200·H`，1080p ⇒ `rIn 216` / `rOut 453.6`）：
     * 矩形 `432² + 907² = 1010k px²` = **0.487 屏**（与方案 §十 逐位对齐），
     * 圆 `π·216² + π·453.6² = 793k` = 0.105 屏 ⇒ **省 0.382 屏**，且省掉的正是最贵的
     * 逐像素 shader 采样面积。
     */
    private fun drawSun(c: Canvas) {
        val outer = shSunOuter ?: return
        val inner = shSunInner ?: return
        val a = SnowCedarMath.clamp(p.comp.sunStrength * world.sunGain, 0.0, SUN_A_MAX)
        if (a <= 0.002) return
        ptSun.alpha = alpha8(a / SUN_A_MAX)
        val sx = lay.sunX.toFloat()
        val sy = lay.sunY.toFloat()
        val rOut = (lay.sunR * 2.1).toFloat()
        val rIn = lay.sunR.toFloat()
        ptSun.shader = outer
        c.drawCircle(sx, sy, rOut, ptSun)
        ptSun.shader = inner
        c.drawCircle(sx, sy, rIn, ptSun)
    }

    /**
     * 一条平铺带 = **一次 Compose `drawRect`**。
     *
     * ⭐ **横向滚动只靠 `topLeft.x = −off`**：画笔是 `ShaderBrush(ImageShader(Repeated, Clamp))`
     * ⇒ 图案按屏宽无缝平移，把矩形整体左移 `off` 就等于"风把云吹过去"。
     * ⛔⛔ **不用 `Shader.setLocalMatrix` / 任何 local matrix** —— E43 真机实测它在硬件画布上
     * **静默退化成 identity**（带子不动、无报错，画面读起来像"风停了"）；E46 的 `drawLinRect`
     * 因此也只用 `Canvas.concat`。本文件**一处 local matrix 都不许出现**。
     * ⛔ 不用 `clipPath` / `clip(Rect)` 去"裁一条带"：矩形本身画在 `[−off, −off+w+cw]`，
     * 出画幅的部分由画布自己裁掉（`off ∈ [0, cw)` ⇒ 必然盖满 `[0, w]`）。
     *
     * @param speed 横向滚动速度（原型 `drawVeil` 的第 6 参；阴云层是 `0.35 + i·0.25`）
     * @param y     带上沿（像素）
     * @param alpha  该带的整体 α（位图内部 α 再乘它 ⇒ 等价原型的 `globalAlpha`）
     */
    private fun DrawScope.drawBand(
        brush: Brush, bmp: Bitmap, speed: Double, y: Double, w: Float, alpha: Double,
    ) {
        val cw = bmp.width
        val dh = bmp.height
        if (cw <= 0 || dh <= 0) return
        val off = bandOff(speed, cw)
        drawRect(
            brush = brush,
            topLeft = Offset((-off).toFloat(), y.toFloat()),
            size = Size(w + cw, dh.toFloat()),
            alpha = SnowCedarMath.clamp(alpha, 0.0, 1.0).toFloat(),
        )
    }

    /**
     * 横向滚动偏移（原型 `((t * windPx * speed) % cw + cw) % cw`）。
     *
     * ⚠️ **双重取模不能省**：极小概率下 `gust` 把 `windPx` 压成负值（[SnowWorldState.state]
     * 的 `(1 + gustAmp·(0.5+kGust)·gust)` 在 `gust = −1`、`kGust` 较大时可以为负），
     * 而 Kotlin/JS 的 `%` 对负被除数返回**负余数** ⇒ 单取模会把整条带推到左边界的反方向。
     */
    private fun bandOff(speed: Double, cw: Int): Double {
        val raw = tSec * world.windPx * speed
        return ((raw % cw) + cw) % cw
    }

    /** 冰晶闪光：本帧亮着的晶粒拼成**一条** `Path`，⛔ 不逐粒提交。 */
    private fun drawGlints(c: Canvas) {
        val n = field.glintN
        if (n == 0) return
        var k = 0
        pathGlint.reset()
        var i = 0
        while (i < n) {
            val b = field.glintBright(i, tSec)
            if (b > 0.0) {
                val hw = field.glintHalfW(i, b).toFloat()
                val hh = hw * 2.8f
                val x = field.glintX(i)
                val y = field.glintY(i)
                pathGlint.moveTo(x, y - hh)
                pathGlint.lineTo(x + hw, y)
                pathGlint.lineTo(x, y + hh)
                pathGlint.lineTo(x - hw, y)
                pathGlint.close()
                k++
            }
            i++
        }
        if (k > 0) c.drawPath(pathGlint, ptGlint)
    }

    /**
     * 大树风摆：**一次 `drawBitmapMesh`**。
     *
     * ⭐ **网格宽度必须给 1 而不是 0**（这是个 API 事实，不是风格选择）：
     * `Canvas.drawBitmapMesh(bitmap, meshWidth, meshHeight, verts, vertOffset, colors, colorOffset, paint)`
     * **没有 `texs` 参数**（纹理坐标由顶点在网格里的位置隐式推出：`u = i/meshWidth`、
     * `v = j/meshHeight`）⇒ `meshWidth = 0` 会让 `i/meshWidth = 0/0 = NaN`，Skia 的
     * `SkCanvas::drawBitmapMesh` 还 `SkASSERT(meshWidth > 0)`。给 1 ⇒ 每行**两列**顶点
     * （`u = 0` 与 `u = 1`），两列吃**同一个**行偏移，于是每行仍是"整幅宽的一条带、整体平移"。
     *
     * ⚠️ 顶点 y 取 `meta.h / meshH`（**恒等映射**）：若照原型的 `sh = meta.h / rows` 填 y、
     * 而 `v` 按 `meshH` 铺，整棵树会被纵向拉伸 `rows/(rows−1)`。
     */
    private fun drawTree(c: Canvas) {
        val bmp = bTree ?: return
        val meta = treeMeta ?: return
        val rows = meshOff.size
        val cells = meshCells
        if (rows < 2 || cells < 1) return
        SnowCedarMath.meshOffsets(world.sway, rows, meta.h.toDouble(), meshOff)
        val bw = bmp.width.toDouble()
        val step = meta.h.toDouble() / cells
        var j = 0
        while (j < rows) {
            val xOff = meta.screenX0 + meshOff[j]
            val y = (meta.screenY0 + j * step).toFloat()
            meshVerts[4 * j] = xOff.toFloat()
            meshVerts[4 * j + 1] = y
            meshVerts[4 * j + 2] = (xOff + bw).toFloat()
            meshVerts[4 * j + 3] = y
            j++
        }
        c.drawBitmapMesh(bmp, 1, cells, meshVerts, 0, null, 0, ptBlit)
    }

    /** 人：本体 1 blit + `dust > 0.004` 时同矩形再叠一张肩雪。⛔ 逐帧不许动（P22/P26）。 */
    private fun drawPerson(c: Canvas) {
        val body = bPerson ?: return
        val sz = personSz ?: return
        val r = SnowPerson.rectFor(lay, sz)
        val x = r.x.toFloat()
        val y = r.y.toFloat()
        c.drawBitmap(body, x, y, ptBlit)
        val d = dust.dust
        if (d > 0.004) {
            val snow = bPersonSnow ?: return
            ptDust.alpha = alpha8(d)
            c.drawBitmap(snow, x, y, ptDust)
        }
    }

    /**
     * 一层雪（原型 `drawSnowLayer`）。**每层每桶恰好一次提交**。
     *
     * - **streak 层**：`STROKE` + round cap 的短线段集（一条 `Path` 一次 `drawPath`）。
     *   ⛔ `rz` 被**故意忽略**（`streakWidthPx` 恒取直径）——拖尾一粗细不均就读成"歪的"。
     * - **dot / bokeh 层**：⛔ `drawPoints` **不能逐点变半径**，而原型是逐粒 `arc(r·rz)`
     *   （它要的就是"同层大小不一"，等大圆点会读成印刷网点）。取舍：**拆成两个 `drawPoints`
     *   桶**，按 `rzOf(i)` 在 `1 + rVar·0.5` 处分开，低桶 `2·base`、高桶 `2·base·(1+rVar)`。
     *   ⭐ 这是**有意多付**的：dot/bokeh 层因此是 2 图元而不是 1（五层共 6 而不是 3）。
     *   换来的好处是留住便宜的批量 `drawPoints`（⛔ 不退回逐粒 `arc`）**并且**留住逐粒尺寸抖动；
     *   真要压回 1 图元只能牺牲抖动或改成逐粒提交，两者都比这 +1 图元更贵。
     */
    private fun drawSnowLayer(c: Canvas, li: Int) {
        val def = SnowLayers.ALL[li]
        val a0 = field.flakeOff[li]
        val b0 = a0 + field.flakeCount[li]
        if (b0 <= a0) return
        ptSnow.color = argbOf(def.a, def.rgb)
        if (def.shape == SnowShape.STREAK) {
            // 拖尾长度 = `streak × 直径`（⛔ 不是"速度 × 秒数"：那会被拉成 10×30 的药丸）
            val kPx = field.streakLen(li)
            ptSnow.strokeWidth = field.streakWidthPx(li).toFloat()
            pathStreak.reset()
            var i = a0
            while (i < b0) {
                pathStreak.moveTo(field.x[i], field.y[i])
                pathStreak.lineTo(field.streakEndX(i, kPx).toFloat(), field.streakEndY(i, kPx).toFloat())
                i++
            }
            c.drawPath(pathStreak, ptSnow)
            return
        }
        val base = def.r * lay.s
        val split = 1.0 + p.snow.rVar * 0.5
        var nLo = 0
        var nHi = 0
        var i = a0
        while (i < b0) {
            val x = field.x[i]
            val y = field.y[i]
            if (field.rzOf(i) < split) {
                bufLo[2 * nLo] = x
                bufLo[2 * nLo + 1] = y
                nLo++
            } else {
                bufHi[2 * nHi] = x
                bufHi[2 * nHi + 1] = y
                nHi++
            }
            i++
        }
        // round cap ⇒ 每个点落成一个直径 = `strokeWidth` 的实心圆
        // ⚠️ `drawPoints(pts, offset, count, paint)`：**paint 在最后**（count 单位是**值**不是点）
        ptSnow.strokeWidth = (2.0 * base).toFloat().coerceAtLeast(MIN_DOT_PX)
        if (nLo > 0) c.drawPoints(bufLo, 0, nLo * 2, ptSnow)
        ptSnow.strokeWidth = (2.0 * base * (1.0 + p.snow.rVar)).toFloat().coerceAtLeast(MIN_DOT_PX)
        if (nHi > 0) c.drawPoints(bufHi, 0, nHi * 2, ptSnow)
    }

    // ─────────────────────── 建素材（首帧 / 换画幅 / 换档位） ───────────────────────

    /**
     * `boot(w,h)` —— **位图与队列只在这里建**（E46 同款：`onEnter` 时画幅还是 `Size.Zero`）。
     * 原型 `resizeTo(w,h)`：`layout → bakeAll → allocFlakes → allocGlints → resetPerson`。
     *
     * ⭐ 与原型的唯一差别：`bakeAll` 被拆成 [bakeQueue] + [bakeStep]（逐帧预算 24 ms），
     * 位图先建成**全透明**的占位、行块逐帧往里长（E46 的同一条修法：冷进入黑屏的根因不是
     * "烘得慢"，是"把全部素材压在一帧里同步烘完"）。
     *
     * ⚠️ 这一段会分配十几张位图（≈30 MB @1080p）⇒ **只在画幅或档位真的变化时跑**；
     * `layout` 与 `applyLevel` 都已由调用方落好，本函数只消费。
     */
    private fun boot(w: Int, h: Int) {
        booted = true
        releaseBitmaps()   // ⛔ 先交出旧的一整套（换画幅/换档位时的同一批字段）

        // ① 树 + 它的位图映射账（位图尺寸的**唯一**口径在 [SnowCedarBakes.treeMeta]）
        val t = SnowCedarTree.build(SnowCedarTree.treeOpt(p, lay))
        val meta = bakes.treeMeta(t)
        tree = t
        treeMeta = meta
        personSz = SnowPerson.sizeFor(lay)

        // ② 雪场与肩雪：⛔ 人物除 `dust` 外无跨帧状态（[SnowPersonDust] 的 T6 契约）
        field.allocFlakes(p.snow.flakes)
        field.allocGlints()
        dust.reset()
        audio.reset()
        bufLo = FloatArray(2 * max(1, field.n))
        bufHi = FloatArray(2 * max(1, field.n))

        // ③ 网格顶点（`meshW = 1`、`meshH = rows − 1` ⇒ `2·rows·2 = 4·rows` 个 float）
        val rows = max(2, p.wind.rows)
        meshCells = rows - 1
        meshOff = DoubleArray(rows)
        meshVerts = FloatArray(4 * rows)

        // ④ 位图（⛔ 一律 `ARGB_8888`：条带的 `setPixels` 契约与像素级对账都按它算）
        bSky = newBmp(w, h); cSky = bSky?.let { Canvas(it) }
        bGround = newBmp(w, h); cGround = bGround?.let { Canvas(it) }
        bTree = newBmp(meta.w, meta.h); cTree = bTree?.let { Canvas(it) }
        val pw = bakes.pineSize()
        bPines = newBmp(pw.first, pw.second); cPines = bPines?.let { Canvas(it) }
        bPinesTmp = newBmp(pw.first, pw.second)
        val sz = personSz
        if (sz != null) {
            bPerson = newBmp(sz.w, sz.h); cPerson = bPerson?.let { Canvas(it) }
            bPersonSnow = newBmp(sz.w, sz.h); cPersonSnow = bPersonSnow?.let { Canvas(it) }
        }
        val bw = bakes.bandWidth()
        bCloud = newBmp(bw, bakes.cloudHeight())
        bVeilFar = newBmp(bw, bakes.veilFarHeight())
        bVeilNear = newBmp(bw, bakes.veilNearHeight())
        bGrade = newBmp(w, h)

        // ⑤ 三条带的平铺画笔 + 日晕渐变（**画幅相关** ⇒ 每次 boot 都重开）
        //    `asImageBitmap()` 只是给同一块 native 像素包一层（不复制）⇒ 烘焙期往里写、
        //    绘制期由 `ShaderBrush` 读，是同一张位图。
        brushCloud = bCloud?.let { bandBrushOf(it) }
        brushVeilFar = bVeilFar?.let { bandBrushOf(it) }
        brushVeilNear = bVeilNear?.let { bandBrushOf(it) }
        buildSunShaders()

        // ⑥ 分帧队列（三条带与 grade 的画幅**各不相同** ⇒ 逐个传进去，⛔ 不能一套包打天下）
        bakeQueue = SnowBakeQueue(
            bakes.bandWidth(), bakes.cloudHeight(), bakes.veilFarHeight(), bakes.veilNearHeight(),
            w, h, t.segs.size, t.snow.size, t.clumps.size,
        )
    }

    /** 一条带的平铺画笔：X 向 `Repeated`（无缝循环）、Y 向 `Clamp`（上下沿不外溢）。 */
    private fun bandBrushOf(bmp: Bitmap): Brush =
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Clamp))

    private fun newBmp(w: Int, h: Int): Bitmap? =
        if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /**
     * 日晕的两层渐变。停点色值按 `a = [SUN_A_MAX]` 建，逐帧强度走 `ptSun.alpha`
     * （见字段 KDoc 的「乘性等价」说明）。
     */
    private fun buildSunShaders() {
        val a = SUN_A_MAX
        val x = lay.sunX.toFloat()
        val y = lay.sunY.toFloat()
        val rIn = lay.sunR.toFloat()
        val rOut = (lay.sunR * 2.1).toFloat()
        val inStops = floatArrayOf(0f, 0.42f, 1f)
        val inCols = intArrayOf(
            sunColor(a, 255, 252, 242),
            sunColor(a * 0.55, 255, 251, 244),
            sunColor(0.0, 255, 251, 244),
        )
        val outCols = intArrayOf(
            sunColor(a * 0.62, 255, 252, 242),
            sunColor(a * 0.62 * 0.55, 255, 251, 244),
            sunColor(0.0, 255, 251, 244),
        )
        shSunInner = RadialGradient(x, y, rIn, inCols, inStops, Shader.TileMode.CLAMP)
        shSunOuter = RadialGradient(x, y, rOut, outCols, inStops, Shader.TileMode.CLAMP)
    }

    // ─────────────────────── 分帧烘焙 ───────────────────────

    /**
     * 每帧在**固定时间预算**（[SnowCedarBakes.BAKE_BUDGET_MS]）内推进队列，烘完即空转。
     *
     * ⭐ 预算判定在**每个单元之后** ⇒ 0 号单元（`SKY_BASE`）在任何设备上都必然执行
     *    ⇒ 首帧就有天幕，⛔ 不会黑屏。
     * ⚠️ `System.nanoTime()` 是**纳秒**而预算是**毫秒** ⇒ 换算这一步不能省（E43 的原话）。
     */
    private fun bakeStep() {
        val q = bakeQueue ?: return
        if (q.done) return
        val t0 = System.nanoTime()
        while (!q.done) {
            val u = q.next() ?: break
            runBakeUnit(u)
            if (System.nanoTime() - t0 >= BAKE_NS) break
        }
    }

    /** 消费一个烘焙单元。⛔ 顺序即收敛顺序（队列已排好，这里不得改）。 */
    private fun runBakeUnit(u: SnowBakeUnit) {
        when (u.kind) {
            SnowBakeKind.SKY_BASE -> cSky?.let { bakes.skyBase(it) }
            SnowBakeKind.SKY_BLOBS -> cSky?.let { bakes.skyBlobs(it, u.a, u.b) }
            SnowBakeKind.SKY_HORIZON -> cSky?.let { bakes.skyHorizon(it) }

            SnowBakeKind.GROUND_BASE -> cGround?.let { bakes.groundBase(it) }
            SnowBakeKind.GROUND_TONES -> cGround?.let { bakes.groundTones(it, u.a, u.b) }
            SnowBakeKind.GROUND_VEINS -> cGround?.let { bakes.groundVeins(it, u.a, u.b) }
            SnowBakeKind.GROUND_MOUNDS -> cGround?.let { bakes.groundMounds(it, u.a, u.b) }
            SnowBakeKind.GROUND_TREE -> cGround?.let { bakes.groundTreeMound(it) }
            SnowBakeKind.GROUND_UNDUL -> cGround?.let { bakes.groundUndulations(it, u.a, u.b) }
            SnowBakeKind.GROUND_FOOT -> cGround?.let { bakes.groundFoot(it) }
            // ⛔ `GROUND_CUT` 必须排在**该图全部内容之后**（否则切完还在往缺口里填色）
            SnowBakeKind.GROUND_CUT -> cGround?.let { bakes.groundCut(it) }
            SnowBakeKind.GROUND_SPALL -> cGround?.let { bakes.groundSpall(it, u.a, u.b) }

            SnowBakeKind.TREE_SEGS -> {
                val t = tree ?: return
                val m = treeMeta ?: return
                cTree?.let { bakes.treeSegs(it, t, m, u.a, u.b) }
            }
            SnowBakeKind.TREE_SNOW_BASE -> {
                val t = tree ?: return
                val m = treeMeta ?: return
                cTree?.let { bakes.treeSnowBase(it, t, m, u.a, u.b) }
            }
            SnowBakeKind.TREE_SNOW_RIDGE -> {
                val t = tree ?: return
                val m = treeMeta ?: return
                cTree?.let { bakes.treeSnowRidge(it, t, m, u.a, u.b) }
            }
            SnowBakeKind.TREE_CLUMPS -> {
                val t = tree ?: return
                val m = treeMeta ?: return
                cTree?.let { bakes.treeClumps(it, t, m, u.a, u.b) }
            }
            SnowBakeKind.TREE_SKIRT -> {
                val m = treeMeta ?: return
                cTree?.let { bakes.treeSkirt(it, m) }
            }

            SnowBakeKind.PINES_DRAW -> {
                val bmp = bPines ?: return
                cPines?.let { bakes.pinesDraw(it, bmp.width, bmp.height) }
            }
            // ⚠️ `pinesSoften` 边读边改**两张**位图 ⇒ 必须给它一份 scratch（⛔ 不能传同一张）
            SnowBakeKind.PINES_SOFTEN -> {
                val bmp = bPines ?: return
                val tmp = bPinesTmp ?: return
                bakes.pinesSoften(bmp, tmp, bmp.width, bmp.height)
            }

            // ⭐ 一个单元填**两张**位图（本体 + 肩雪，同尺寸）
            SnowBakeKind.PERSON -> {
                val sz = personSz ?: return
                cPerson?.let { bakes.personBody(it, sz) }
                cPersonSnow?.let { bakes.personSnow(it, sz) }
            }

            SnowBakeKind.BAND_CLOUD, SnowBakeKind.BAND_VEIL_FAR, SnowBakeKind.BAND_VEIL_NEAR -> {
                val bmp = bandBmpOf(u.kind) ?: return
                val spec = bakes.bandSpec(u.kind) ?: return
                val rows = u.b - u.a
                if (rows <= 0) return
                val px = rowBuf(u.kind, rows * spec.w)
                bakes.bandRows(px, spec.w, spec.h, spec.oct, spec.alpha, spec.ySeed, u.a, u.b)
                // ⚠️ `setPixels` 吃**非预乘** ARGB；三条带的源色恒为白 ⇒ 原样写
                bmp.setPixels(px, 0, spec.w, 0, u.a, spec.w, rows)
            }

            SnowBakeKind.GRADE -> {
                val bmp = bGrade ?: return
                val rows = u.b - u.a
                if (rows <= 0 || bmp.width < 1) return
                val px = rowBuf(u.kind, rows * bmp.width)
                bakes.gradeRows(px, bmp.width, bmp.height, u.a, u.b)
                bmp.setPixels(px, 0, bmp.width, 0, u.a, bmp.width, rows)
            }
        }
    }

    private fun bandBmpOf(kind: Int): Bitmap? = when (kind) {
        SnowBakeKind.BAND_CLOUD -> bCloud
        SnowBakeKind.BAND_VEIL_FAR -> bVeilFar
        SnowBakeKind.BAND_VEIL_NEAR -> bVeilNear
        else -> null
    }

    /** 行块 kind → 暂存数组下标（⛔ 四个 kind 各一份，避免一次大块把另一块挤掉）。 */
    private fun rowSlot(kind: Int): Int = when (kind) {
        SnowBakeKind.BAND_CLOUD -> 0
        SnowBakeKind.BAND_VEIL_FAR -> 1
        SnowBakeKind.BAND_VEIL_NEAR -> 2
        else -> 3
    }

    private fun rowBuf(kind: Int, need: Int): IntArray {
        val slot = rowSlot(kind)
        val cur = rowPx[slot]
        if (cur != null && cur.size >= need) return cur
        val next = IntArray(max(need, SnowCedarBakes.UNIT_PX_CAP))
        rowPx[slot] = next
        return next
    }

    // ─────────────────────── 小工具 ───────────────────────

    private fun alpha8(a: Double): Int = SnowCedarMath.jsRound(
        SnowCedarMath.clamp(a, 0.0, 1.0) * 255.0,
    ).toInt()

    /** 雪粒色（原型 `rgba(rgb, a)`）。`rgb` 是 `0xRRGGBB`。 */
    private fun argbOf(a: Double, rgb: Int): Int =
        (alpha8(a) shl 24) or (rgb and 0x00FFFFFF)

    private fun sunColor(a: Double, r: Int, g: Int, b: Int): Int =
        (alpha8(a) shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        /** 日晕强度的**满档**（原型 `clamp(sunStrength·sunGain, 0, 0.6)` 的上界）。 */
        private const val SUN_A_MAX = 0.6

        /** 冰晶闪光色 `rgba(255,255,255,0.92)`（原型 `drawGlints` 的 `fillStyle`）。 */
        private const val GLINT_COLOR = 0xEAFFFFFF.toInt()

        /** 雪点直径下限（原型 `Math.max(0.35, base·rz)` 的半径 0.35 ⇒ 直径 0.7）。 */
        private const val MIN_DOT_PX = 0.7f

        /** 24 ms 预算换算成纳秒。 */
        private val BAKE_NS = SnowCedarBakes.BAKE_BUDGET_MS * 1000000L

        // 五层的下标（[SnowLayers.ALL] 的次序即落雪深度序，⛔ 换序就是换绘制先后）
        private const val LAYER_FAR_A = 0
        private const val LAYER_FAR_B = 1
        private const val LAYER_MID = 2
        private const val LAYER_STREAK = 3
        private const val LAYER_BOKEH = 4

        /**
         * `FxLevel → [SnowLevel]` 的**一次性映射**（与 `TripodFogRenderer.tripodLevelOf`、
         * `MoonlitRenderer.moonLevelOf` 同一条）。
         *
         * ⚠️ `OFF → LOW`（不是"关掉雪"）：雪是本效果的主体，低档只是砍到 380 粒 / 1 条云带 /
         * 2 条吹雪带 / 无颗粒（[SnowCedarP.applyLevel]）。
         */
        internal fun snowLevelOf(level: FxLevel): SnowLevel = when (level) {
            FxLevel.OFF -> SnowLevel.LOW
            FxLevel.LITE -> SnowLevel.MEDIUM
            FxLevel.FULL -> SnowLevel.HIGH
        }
    }
}