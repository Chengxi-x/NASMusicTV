package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
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
import com.nasmusic.tv.visualizer.fx.AudioSmoother
import com.nasmusic.tv.visualizer.fx.FxLevel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * E48「大漠 · 孤烟」绘制侧（K5 / K7）—— 原型 §10 `renderFrame` 的逐字移植。
 *
 * ## 图层序契约（原型 §5.1，⛔ 不得重排）
 * ```
 * blit(sky) → drawSun() →
 *   for gi in 0 until nG {
 *       if (gi == carLayer) drawCaravan      ┐
 *       if (gi == srcLayer) { drawEmber; drawSmoke }  ├ 两个条件分支必须在 drawDune(gi) **之前**
 *       drawDune(gi)                          ┘
 *   } → drawVeils() → drawGrade()
 * ```
 * ⭐ 遮挡**只**由图层顺序产生：`drawDune(carLayer)` 吃掉驼腿、`drawDune(srcLayer)` 吃掉烟根，
 * ⛔ 没有专门的裁切/遮罩代码。这是 D4 的遮挡语义闸门（错序 ⇒ 骆驼浮在沙上或整批消失 = P5 的两种死法）。
 * ⚠️ 循环的是**画布数 nG**（= `dune.n`）而⛔脊线数 R —— 逐帧成本只与画布数有关，这正是分组的全部意义。
 * ⭐ 再加一条 §八之二 ⓒ：`carLayer < nG−1 && srcLayer < nG−1`（两个分支都必须是"中间的画布"，
 * 否则会被最后一层整个盖掉 = 画了看不见）。
 *
 * ## 三条不可动的结构纪律（与 E47 逐字同款）
 * 1. **本类拥有全部 `Bitmap`**；烘焙层（[DesertSmokeBakes]）是**无状态画笔** ——
 *    它只往调用方给的 `Canvas`/`IntArray` 里写，一个位图都不持有、⛔ 也没有 `bakeAll()`。
 * 2. **时间只有一个自变量 `tSec`**（由 `fx.dt` 累加，⛔ 不是 `ctx.nowMs`、⛔ 不是墙钟），
 *    音频只是**增益**（`bass/mid/treble/energy/section` 五条线性通道，⛔ 永不碰 `frame.spectrum`）。
 *    无声 ⇒ 静态定稿帧（T11）。
 * 3. **逐帧零分配**：`Paint`/`Path`/`Matrix`/`Rect(F)`/`Shader`/数组全是字段；
 *    `puff()` 用**一次带旋转的 `drawBitmap(Matrix)`**（⛔ 逐粒 `save/restore` —— 原型
 *    `ctx.save/translate/rotate/scale/restore` 在 Compose 侧是 26×5 次画布栈操作）。
 */
class DesertSmokeRenderer : RendererFx() {

    override val theme = VisualizerTheme.DESERT_SMOKE

    /**
     * ⭐ **字面量 `0.10f` 是刻意写的，而基类那一次全屏暗角永远不画**。
     *
     * 暗角与颗粒已由 [DesertSmokeBakes.gradeRows] **烘在同一张 grade 位图**里
     * （逐像素只依赖 `(i,j)` ⇒ 天然可分帧 ⇒ 逐帧只 1 次 blit / 1.000 屏）。基类后处理
     * 再叠一次就是**第二遍暗角 + 第二遍颗粒**（四角压到死黑 + 两层噪点互咬）。
     *
     * ⚠️ 但**不能**因此把 `postFx` 写成 `PostFx.NONE`：`NONE` 会让
     * `RendererFx.needsDamageCoalescer` 在 **LITE/FULL 也为真** ⇒ `OverlayFx.drawDamageCoalescer`
     * 每帧补一次整画布 `drawRect`（肉眼不可见，铺屏整整 1.000 屏），而本效果每帧头一笔
     * 就是全屏 `blitFull(bSky)` ⇒ 脏区早已并成一整块，这次补画零收益。
     * ⇒ 与 E46/E47/E49 **同款三段式**：字面量留着（门禁与 `needsDamageCoalescer` 的账）+
     * [vignetteOverride] 恒 `0f` 关断绘制。⛔ 两条必须一起翻案。
     */
    override val postFx = PostFx(vignette = 0.10f)

    /** 恒 `0f`：暗角由烘焙位图承担，基类那一次必须关掉（见 [postFx]）。⛔ 不许改成别的值。 */
    override fun vignetteOverride(fx: FxFrame): Float = 0f

    // ─────────────────────── 参数与长驻状态 ───────────────────────

    private val p = DesertP()
    private val lay = DesertLayout()
    private val bakes = DesertSmokeBakes(p, lay)

    private val world = DesertWorldState(p, lay)
    private val audio = DesertAudio(p)

    /** 孤烟烟丝链（`reset` 是它唯一的换数组入口）。 */
    private val smoke = DesertSmokeChain()

    /** 驼队时刻表（固定种子派生 ⇒ 同一次进入画面逐位一致）。 */
    private val car = DesertCaravanTable()

    /** 动画时基（秒）：只由 `fx.dt` 累加。 */
    private var tSec = 0.0

    private var curLevel: DesertLevel? = null
    private var laidW = -1
    private var laidH = -1
    private var booted = false

    /**
     * 「本帧只画兜底天幕、下一帧才做 [boot]」的一次性闸门（见 [drawSkyFallback]）。
     *
     * ⛔ 不是缓存、⛔ 不跨渲染器实例复用：由 [releaseAll] 复位。
     */
    private var bootPending = false

    /**
     * 0 号单元（`SKY`）是否已经烘进 [bSky]。
     *
     * ⭐ 用途：帧尾在 [renderFrame] **之前**判一次 —— 没烘就先用 [drawSkyFallback] 铺底，
     * 保证效果层**永远不会是"全透明"**（那会露出 `VisualizerStage` 的深色背景层，
     * 在用户眼里就是「黑屏」）。
     */
    private var skyBaked = false

    /**
     * 兜底天幕的**着色器**（缓存键 = 渐变终点 [skyFallbackEndY]）与它专用的画笔。
     *
     * ⚠️ 着色器缓存 ⇒ 这条路径**逐帧零分配**（[DesertSmokeBakes.skyGradient] 本身有分配，
     *    只在 `bot` 变化时才重建）。⛔ 不复用 [ptBlit]：`blitFull` 也在用同一支，
     *    混用会让「谁负责把 `shader` 置回 `null`」变成一条隐性契约。
     */
    private var skyFallbackShader: LinearGradient? = null
    private var skyFallbackEndY = -1f
    private val ptFallback = Paint()

    // ─────────────────────── 位图（本类全部持有） ───────────────────────

    private var bSky: Bitmap? = null
    private var cSky: Canvas? = null
    private var bSun: Bitmap? = null
    private var cSun: Canvas? = null
    private var bDune: Array<Bitmap?>? = null
    private var cDune: Array<Canvas?>? = null

    /** 第 g 张画布的**世界坐标**上沿（原型 `B['dune'+gi].y0`）。 */
    private var duneY0 = DoubleArray(0)

    private var bWisp: Array<Bitmap?>? = null
    private var cWisp: Array<Canvas?>? = null

    /** 12 张烟丝共用的**暂存**位图（`bakeWisp` 的四次叠印源；边读边改 ⇒ 必须独立一份）。 */
    private var bWispTmp: Bitmap? = null
    private var cWispTmp: Canvas? = null

    private var bCamel: Bitmap? = null
    private var cCamel: Canvas? = null
    private var camelSprite: CamelSprite? = null
    private var bVeil: Bitmap? = null
    private var bGrade: Bitmap? = null

    /** 分帧烘焙队列（`null` = 尚未 [boot] 或已交出）。 */
    private var bakeQueue: DesertBakeQueue? = null

    /**
     * 行块暂存：**每个行块 kind 一份**（浮沙带 / grade），容量到 [DesertSmokeBakes.UNIT_PX_CAP]
     * ⇒ 任何**一个**行单元都装得下（⛔ 逐单元 `IntArray(...)` = 每帧分配）。
     * 下标 = `DesertBakeKind.VEIL` / `DesertBakeKind.GRADE`。
     */
    private val rowPx = arrayOfNulls<IntArray>(2)

    // ─────────────────────── 逐帧复用的画笔与着色器 ───────────────────────

    private val ptBlit = Paint()
    private val ptPuff = Paint()
    private val ptCamel = Paint()
    private val ptHalo = Paint()
    private val pathCamel = Path()
    private val matrix = Matrix()

    private val srcRect = Rect()
    private val dstRect = RectF()

    /**
     * 浮沙带的**平铺画笔**（`ShaderBrush(ImageShader(..., Repeated, Clamp))`，逐帧只换
     * `topLeft` 与 `alpha`）。
     *
     * ⛔⛔ **绝不靠 local matrix 做横向偏移**（E43 真机实测：`Shader.setLocalMatrix` 在硬件画布上
     * **静默退化成 identity**）⇒ 偏移只由 [drawVeils] 的**画布平移**承担。
     * ⚠️ 位图 `recycle` 之前必须先把画笔置 `null`（[releaseBitmaps] 的顺序即此约定）。
     */
    private var brushVeil: Brush? = null

    /** 三层日晕（外散 `halo2·R`、日晕 `halo1·R`、贴身柔光 `halo0·R`）。 */
    private var shHalo0: RadialGradient? = null
    private var shHalo1: RadialGradient? = null
    private var shHalo2: RadialGradient? = null

    /** 烟根暖光（一次 `drawRect`；逐帧强度走 [ptPuff] 的 α 乘性）。 */
    private var shEmber: RadialGradient? = null
    private var emberX = 0.0
    private var emberY = 0.0
    private var emberR = 0.0

    // ─────────────────────── 生命周期 ───────────────────────

    /**
     * 进入效果：**首行释放旧资源**，再复位时间轴与音频。
     *
     * ⛔ 首行这个 `releaseAll()` 不是洁癖：**画质变化会重入 `onEnter`**（不经过 `onExit`）。
     * ⚠️ 位图不在这里分配：`onEnter` 时 `ctx.canvasSize` 还是 `Size.Zero` ⇒ 走 [boot]。
     */
    override fun onEnterContent(ctx: RenderContext) {
        releaseAll()
        tSec = 0.0
        audio.reset()
    }

    /** 离效果：逐张 `recycle`（API 22–25 的 `ARGB_8888` 像素在 native 堆、不受 GC 自愈）。 */
    override fun onExitContent() {
        releaseAll()
        tSec = 0.0
    }

    /** 位图 + 画笔 + 队列（**重排与离效果都要走这里**）。⛔ 画笔先置 `null`。 */
    private fun releaseBitmaps() {
        brushVeil = null
        recycle(bSky); bSky = null
        recycle(bSun); bSun = null; cSun = null
        bDune?.forEach { recycle(it) }; bDune = null; cDune = null
        bWisp?.forEach { recycle(it) }; bWisp = null; cWisp = null
        recycle(bWispTmp); bWispTmp = null; cWispTmp = null
        recycle(bCamel); bCamel = null; cCamel = null
        recycle(bVeil); bVeil = null
        recycle(bGrade); bGrade = null
        bakeQueue = null
        for (i in rowPx.indices) rowPx[i] = null
        shHalo0 = null; shHalo1 = null; shHalo2 = null
        shEmber = null
        camelSprite = null
    }

    /** [releaseBitmaps] + 派生状态与缓冲。 */
    private fun releaseAll() {
        releaseBitmaps()
        duneY0 = DoubleArray(0)
        curLevel = null
        laidW = -1
        laidH = -1
        booted = false
        bootPending = false
        skyBaked = false
        skyFallbackShader = null
        skyFallbackEndY = -1f
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
        //    resetSmoke → buildCaravan → resetAudio）。
        //
        // ⛔⛔ **`bootPending` 必须并入判据**（2026-10-16 真机第二轮修复）：两段式的**次帧**
        //    （真正执行 `boot()` 的那一帧）里画幅与档位都已经"看起来没变"——`laidW/laidH`
        //    在**首帧**就被更新成新值了 ⇒ 只看 `!booted || 档位变 || 画幅变` 会让
        //    `boot()` **永远不被执行**、`bootPending` 永久停在 `true`：
        //    · 首帧之后画面停在"兜底天幕 + 旧画幅的位图"上（旧位图尺寸与新画幅不符）；
        //    · 下一次画幅变化时又只画一帧兜底 —— **重建被静默吞掉**。
        var dirty = !booted || bootPending
        val level = desertLevelOf(fx.level)
        if (level != curLevel) {
            curLevel = level
            p.applyLevel(level)
            dirty = true
        }
        // ⭐ 画幅是否变化 —— 决定 `boot()` 能否**复用天幕位图**（见 [boot] 的 KDoc）。
        val sizeChanged = iw != laidW || ih != laidH
        if (sizeChanged) {
            laidW = iw
            laidH = ih
            dirty = true
        }
        // ⭐ 画幅**只在首帧 `drawContent`** 才拿得到 ⇒ 位图与队列一律在这里建（E46/E47 同款）。
        //
        // ⭐⭐ **两段式（2026-10-16 真机报障修复）**：`dirty` 的那一帧**只画一帧兜底天幕**，
        //    把 `boot()` 推到下一帧。因为 `boot()` 是**重活**：14 类位图（手机竖屏
        //    1080×2400 下 ≈ 33 MB）分配，紧接着 `bakeStep()` 的 0 号单元 `SKY` 还要做
        //    **3 次全屏 `drawRect`**（1 次 `LinearGradient` + 2 次 `RadialGradient` 散射）。
        //    ⚠️ `Canvas(Bitmap)` 是**软件画布** ⇒ 这 3 次是 **CPU 逐像素**（≈ 7.8 Mpx）；
        //    量级可对照 §10.211 —— E43 那 1.12 Mpx 的逐像素烘焙实测是**秒**级。
        //    这段时间里 Compose 的 draw 一次都没提交，而 `VisualizerStage` 的
        //    `Box.background(Color.Black)` 已经铺满屏幕 ⇒ 用户看到的就是**黑屏一下**。
        //    ⭐ 兜底天幕（[drawSkyFallback]）是**逐帧 GPU 渐变**：成本与画幅无关，
        //    且与烘焙路径**共用同一份 stop 表**（[DesertSmokeBakes.SKY_COLORS]）⇒ 立刻可见、不闪色差。
        //    ⚠️ 同一个闸门顺带覆盖「画幅变化（手机旋转 / 系统栏显隐）」与「档位变化」两条
        //    重建路径 —— 它们原本都会把**已经烘好的整幅画面**先清空、再从头重烘。
        if (dirty) {
            if (!bootPending) {
                bootPending = true
                // ⭐ 首次进入：**还没有任何位图**可画 ⇒ 只能先来一帧兜底天幕（GPU 渐变）。
                if (!booted) {
                    drawSkyFallback(w, h)
                    return
                }
                // ⭐⭐ 画幅 / 档位变化（`booted == true`）：**本帧照常往下走**、画**旧位图**。
                //    `blitFull` 的 `dstRect` 宽取的是**当前画幅宽** ⇒ 旧位图会被拉伸铺满
                //    （形变，但覆盖全屏），把重建推到次帧 —— 于是"重建"这件事
                //    **不会让任何一帧失去画面**。
                //    ⛔ 不要在这里 `return`：那只画一条天幕，丢掉沙丘 / 烟 / 驼队，
                //    用户看到的正是"画面被清空一下"（2026-10-16 真机第一轮返工的原因）。
            } else {
                bootPending = false
                // ⭐ `reuseSky = 画幅没变`：档位变化时天幕内容与档位**无关** ⇒ 直接复用，
                //    ⛔ 不要清空重烘（那正是「黑屏一下」的来源，见 [boot] 的 KDoc）。
                boot(iw, ih, reuseSky = !sizeChanged)
            }
        }
        // ② 分帧烘焙：预算判定在**每个单元之后** ⇒ 0 号单元（`SKY`）必然执行 ⇒ 首帧就有天幕。
        bakeStep()

        // ③ 时间轴 + 音频（原型 `stepAll`：clockT → smooth → world → smoke）。
        //    ⚠️ `fx.dt` 本身已是秒（`FrameClock`），⛔ 不再除 1000（E44 偏差 D31）。
        val dtSec = fx.dt.toDouble()
        tSec += dtSec
        audio.update(
            frame.bass.toDouble(), frame.mid.toDouble(), frame.treble.toDouble(),
            frame.energy.toDouble(), frame.sectionEnergy.toDouble(), dtSec,
        )
        world.state(
            tSec, audio.bass, audio.mid, audio.treble, audio.energy, audio.section,
        )
        smoke.step(dtSec, world.risePx, lay.smokeH, p.smoke.variants)

        val c = drawContext.canvas.nativeCanvas
        // ⭐⭐ 天幕**保底**：0 号单元还没烘完（或这一轮 `boot()` 还没轮到它）时，先铺一帧
        //    兜底天幕 ⇒ 效果层**永远不会是"全透明"**（那会露出 `VisualizerStage` 的深色
        //    背景层，在用户眼里就是「黑屏」）。⛔ 顺序必须在 [renderFrame] **之前**。
        if (!skyBaked) drawSkyFallback(w, h)
        renderFrame(c, w)
    }

    // ─────────────────────── 图层实现（原型 §10 的调用序即契约） ───────────────────────

    /**
     * 兜底天幕 —— `dirty` 那一帧的**唯一**绘制（两段式帧头见 [drawContent]）。
     *
     * ⭐ 与 [DesertSmokeBakes.sky] 的**底渐变同源**：着色器直接由 [DesertSmokeBakes.skyGradient]
     *    产出（同一份 stop 表、同一个 `bot` 口径）⇒ 下一帧真天幕上来时只差「散射 + 条纹补齐」
     *    这一点点，⛔ 不会有色带位置或色相的跳变。
     *
     * ⚠️ 只画底渐变、⛔ **不在这里补散射与条纹** —— 那两次全屏 `RadialGradient` 正是要避开的
     *    CPU 重活（[DesertSmokeBakes.sky] 的 KDoc 已说明它们必须进烘焙）。
     * ⚠️ 这里要调一次 [DesertLayout.layout]：渐变终点 `bot` 取第 0 组沙丘的**世界上沿**，
     *    与烘焙路径同式（⛔ 不能图省事写成 `h * 常数`，那会让色带位置对不上）。
     *    `layout` 是纯算术，实测 0.03 ms（§10.231 成本表）⇒ 与它要避免的开销差三个数量级。
     * ⚠️ 走**原生 `Canvas`**（与 [renderFrame] / [drawVeils] 同一条出口）而不是
     *    `DrawScope.drawRect(brush = …)`：`Brush.verticalGradient` 只吃 `List<Color>`
     *    （**均匀**分布），而天幕的 stop 位置是非均匀的 `0/.25/.60/.85/1` ⇒ 用 `Brush`
     *    会把色带位置整体挪掉。这里直接把同一支 `LinearGradient` 交给画布，绕开那次重采样。
     *    着色器按 `bot` 缓存 ⇒ 这条路径**逐帧零分配**。
     */
    private fun DrawScope.drawSkyFallback(w: Float, h: Float) {
        lay.layout(w.toDouble(), h.toDouble(), p)
        val far = lay.dunes.getOrNull(0) ?: return
        val bot = max(1.0, far.mx).toFloat()
        if (skyFallbackShader == null || skyFallbackEndY != bot) {
            skyFallbackShader = bakes.skyGradient(bot)
            skyFallbackEndY = bot
        }
        val sh = skyFallbackShader ?: return
        val nc = drawContext.canvas.nativeCanvas
        ptFallback.shader = sh
        nc.drawRect(0f, 0f, w, h, ptFallback)
        ptFallback.shader = null
    }

    /**
     * 原型 `renderFrame(t)` 的**逐字移植**。图层序就是契约，⛔ 换序。
     *
     * ⚠️ 浮沙带走 **Compose `drawRect`**（[drawVeils]）而其余走**原生 `Canvas`**
     * —— 两条路径各走各的，⛔ 不要把带子改成原生 `drawRect`（那会需要 local matrix）。
     */
    private fun DrawScope.renderFrame(c: Canvas, w: Float) {
        val ng = lay.dunes[0].nG

        // ① 天幕（全屏 1 blit）
        bSky?.let { blitFull(c, it, w) }

        // ② 落日：三层日晕 + 一次日盘 blit
        drawSun(c)

        // ③ 沙丘画布 + 两个条件分支（遮挡只由图层顺序产生）
        var gi = 0
        while (gi < ng) {
            if (gi == lay.carLayer) drawCaravan(c)
            if (gi == lay.srcLayer) {
                drawEmber(c)
                drawSmoke(c)
            }
            drawDune(c, gi)
            gi++
        }

        // ④ 贴地浮沙带（`p.fx.veils` 图元；Kotlin 侧 ImageShader(Repeat) ⇒ 1 op/带）
        drawVeils(w)

        // ⑤ 调色（暗角 + 颗粒，**烘在一起** ⇒ 1 blit / 1.000 屏）
        bGrade?.let { blitFull(c, it, w) }
    }

    /** 全屏 blit（`sky` / `grade`）：位图尺寸 = 画幅的**整数**尺寸 ⇒ 1:1，不拉伸。 */
    private fun blitFull(c: Canvas, bmp: Bitmap, w: Float) {
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0f, 0f, w, bmp.height.toFloat())
        c.drawBitmap(bmp, srcRect, dstRect, ptBlit)
    }

    /**
     * 落日（原型 `drawSun`）：三层同心柔光**先**画，日盘贴图**后**画。
     *
     * ⭐ **用 `drawCircle` 而不是 `drawRect`**（E47 2026-10-14 真机返工同款，零视觉代价）：
     * 渐变在 `rad` 处已归零 ⇒ 方形的四角是纯透明像素，不省那 21% 铺屏。
     * ⚠️ 三层各自的内半径（`0.94R / 0.88R / 0.90R`）都被日盘（`softK·R ≥ 1.0R`）盖住
     * ⇒ Kotlin 侧的 stop 表按"内盘也填"建，与原型可见结果一致（见 [buildHaloShaders]）。
     */
    private fun drawSun(c: Canvas) {
        val a = 0.095 * world.sunGain
        if (a <= 0.002) return
        ptHalo.alpha = alpha8(a / HALO_A_FULL)
        val sx = lay.sunX.toFloat()
        val sy = lay.sunY.toFloat()
        val r = lay.sunR
        if (p.fx.haloLayers > 2) {
            shHalo2?.let {
                ptHalo.shader = it
                c.drawCircle(sx, sy, (r * p.sun.halo2).toFloat(), ptHalo)
            }
        }
        if (p.fx.haloLayers > 1) {
            shHalo1?.let {
                ptHalo.shader = it
                c.drawCircle(sx, sy, (r * p.sun.halo1).toFloat(), ptHalo)
            }
        }
        shHalo0?.let {
            ptHalo.shader = it
            c.drawCircle(sx, sy, (r * p.sun.halo0).toFloat(), ptHalo)
        }
        ptHalo.shader = null
        // ⭐ 盘体本身**已移入烘焙**（`bakeSun()` ⇒ `B.sun`）⇒ 逐帧只剩一次 drawImage。
        val bmp = bSun ?: return
        val s = bmp.width
        c.drawBitmap(bmp, (lay.sunX - s / 2.0).toFloat(), (lay.sunY - s / 2.0).toFloat(), ptBlit)
    }

    /** 沙丘画布：源 `(0,0,W,bh)` → 同位 `(0, y0)`（位图不是缩放 blit）。 */
    private fun drawDune(c: Canvas, gi: Int) {
        val arr = bDune ?: return
        val bmp = arr.getOrNull(gi) ?: return
        val y0 = if (gi < duneY0.size) duneY0[gi] else 0.0
        c.drawBitmap(bmp, 0f, y0.toFloat(), ptBlit)
    }

    /**
     * 驼队（原型 `drawCaravan`）：每峰 1 次 blit；脚线压在中景沙丘脊线**之下**
     * ⇒ 后画的沙丘把腿吃掉（T9）。
     *
     * ⚠️ **TEX 档只有一个姿势** ⇒ ⛔ 按 `fr` 索引 6 帧（那等于把步态加回来 = 违抗 D25）；
     * 「不是静止的」由**行进**与 `bob` 承担。
     */
    private fun drawCaravan(c: Canvas) {
        val bmp = bCamel ?: return
        val sp = camelSprite ?: return
        val st = caravanAt(car, tSec, lay.w)
        if (!st.active) return
        val gapPx = p.caravan.gap * lay.w
        var idx = 0
        while (idx < p.caravan.camels) {
            val x = st.head - st.dir * idx * gapPx
            if (x >= -gapPx * 1.5 && x <= lay.w + gapPx * 1.5) {
                val jit = DesertMath.hash2(idx * 5 + 1, st.wave + 1)
                val bob = sin(DesertMath.TAU * tSec * p.caravan.bobHz + idx * 1.9) * 0.035
                val sc = (0.86 + jit * 0.26) / CamelTex.SS      // ⛔ 除以 SS：精灵是 2× 烘焙
                val footY = camelFootY(x, jit, bob, lay, p.caravan)
                val bw = sp.w * sc
                val bh = sp.h * sc
                matrix.reset()
                matrix.postTranslate((-sp.cx * sc).toFloat(), (-sp.sole * sc).toFloat())
                if (st.dir < 0) matrix.postScale(-1f, 1f)
                matrix.postTranslate(x.toFloat(), footY.toFloat())
                ptCamel.alpha = alpha8(0.94)
                c.drawBitmap(bmp, matrix, ptCamel)
            }
            idx++
        }
    }

    /**
     * 烟根的一点暖光（原型 `drawEmber`）：贴在烟源层脊线**之下**，随后该层 blit 会把它盖掉大半，
     * 只在脊线上沿漏出一团暖色 ⇒ 烟"有来源"而画面上找不到台子（⛔ 烽燧，裁决不变）。
     * bass ⇒ 明暗（烟的调制链一行没动）。
     */
    private fun drawEmber(c: Canvas) {
        val sh = shEmber ?: return
        val fl = 0.72 + 0.28 * sin(DesertMath.TAU * tSec / 2.3) * sin(DesertMath.TAU * tSec / 5.7 + 1.1)
        val a = DesertMath.clamp(0.30 * world.ember * fl, 0.0, 0.6)
        if (a <= 0.002) return
        ptPuff.shader = sh
        ptPuff.alpha = alpha8(a)
        val r = emberR.toFloat()
        c.drawRect(emberX.toFloat() - r, emberY.toFloat() - r, emberX.toFloat() + r, emberY.toFloat() + r, ptPuff)
        ptPuff.shader = null
    }

    /**
     * 孤烟（原型 `drawSmoke`）：外晕与芯丝**同张贴图两种缩放**（⛔ 另烘一张外晕）。
     *
     * ⭐ 三条口径：
     * 1. ⛔ 逐帧排序：索引序 ≈ u 序（`makeSmoke` 按 `(i+0.5+of)/n` 铺初值，全体等速 ⇒ 序不变）；
     * 2. 抖动源是 `hash2(索引, lap)`，lap 随回绕递增 ⇒ **确定性可复现**，⛔ 不是逐帧随机；
     * 3. alpha 沿柱高做一条**单调**衰减（根部满 → 顶端 0.72）—— 真实的烟越远越被大气透视吃掉。
     */
    private fun drawSmoke(c: Canvas) {
        val wisps = bWisp ?: return
        if (smoke.n == 0) return
        val variants = p.smoke.variants
        val jitK = p.smoke.jitK
        val swayMul = world.swayMul
        val t = tSec
        val emberK = world.ember
        var i = smoke.n - 1
        while (i >= 0) {
            val u = smoke.u[i]
            if (u <= 0.0) {
                i--
                continue
            }
            val ph = smoke.ph[i]
            val lap = smoke.lap[i]
            val jx = (DesertMath.hash2(i * 11 + 3, lap) - 0.5) * 0.70 * jitK
            val jy = (DesertMath.hash2(i * 17 + 5, lap + 2) - 0.5) * 0.5 * jitK
            val rot = (DesertMath.hash2(i * 23 + 7, lap + 4) - 0.5) * 0.45
            val r = SmokeFormulas.puffR(u, p.smoke, lay.h)
            val x = lay.smokeX + SmokeFormulas.swayPx(u, t, ph, swayMul, p.smoke, lay.w) + jx * r
            val y = lay.smokeBaseY - u * lay.smokeH + jy * r * 0.4
            val tl = SmokeFormulas.puffTilt(u, t, ph, swayMul, p.smoke, lay.w, lay.smokeH) + rot
            val dist = 1.0 - 0.28 * DesertMath.smoothstep(0.35, 1.00, u)
            val al = SmokeFormulas.smokeAlpha(u, p.smoke) * dist
            if (al <= 0.0015) {
                i--
                continue
            }
            val vr = ((smoke.vr[i] % variants) + variants) % variants
            val core = wisps.getOrNull(vr)
            if (core == null) {
                i--
                continue
            }
            val warm = wisps.getOrNull(variants + vr)
            puff(c, core, x, y, r * p.smoke.haloMul, p.smoke.elong * 0.92, tl, al * p.smoke.haloA, false)
            puff(c, core, x, y, r, p.smoke.elong, tl, al, false)
            if (u < p.smoke.rimMax) {
                val side = sideOf(lay.sunX - x)
                puff(
                    c, warm, x + side * r * p.smoke.rimOff, y - r * 0.10,
                    r * 0.80, p.smoke.elong * 0.78, tl, al * p.smoke.rimA * emberK, side > 0,
                )
            }
            i--
        }
    }

    /** 原型 `Math.sign(v) || -1`（`0` 也算"无符号" ⇒ 取 −1）。 */
    private fun sideOf(v: Double): Double = if (v > 0.0) 1.0 else if (v < 0.0) -1.0 else -1.0

    /**
     * 一次带旋转的 blit（原型 `puff()`）。
     *
     * ⭐ 用**复用的 [matrix]** 而不是逐粒 `save/translate/rotate/scale/restore`：
     * `M = T(x,y)·R(tilt)·S(±1,1)·T(−r,−h/2)` —— `post*` 的右结合次序与 Canvas 的
     * `translate → rotate → scale → drawImage` 逐位等价。
     */
    private fun puff(
        c: Canvas,
        bmp: Bitmap?,
        x: Double,
        y: Double,
        r: Double,
        elong: Double,
        tilt: Double,
        alpha: Double,
        mirror: Boolean,
    ) {
        if (bmp == null || r <= 0.0 || alpha <= 0.0015) return
        val h = r * 2.0 * elong
        val w = r * 2.0
        matrix.reset()
        matrix.postTranslate((-r).toFloat(), (-h / 2.0).toFloat())
        if (mirror) matrix.postScale(-1f, 1f)
        matrix.postRotate(tilt.toFloat() * 57.29577951308232f)     // rad → deg
        matrix.postTranslate(x.toFloat(), y.toFloat())
        ptPuff.shader = null
        ptPuff.alpha = alpha8(DesertMath.clamp(alpha, 0.0, 1.0))
        c.drawBitmap(bmp, matrix, ptPuff)
    }

    /**
     * 贴地浮沙带（原型 `drawVeils`）：**一次 Compose `drawRect` + 一次画布平移**（逐带）。
     *
     * ⛔⛔ **必须平移画布，不能只挪 `topLeft`**（2026-10-15 真机返工，E46/E47/E49 同一条根因）：
     * Compose 的 `drawRect(brush, topLeft, size)` **不会**把着色器挪到 `topLeft`
     * （`ShaderBrush.applyTo` 建着色器时不带任何 local matrix）⇒ 着色器锚死在**画布原点**。
     * ⛔ **不用 `Shader.setLocalMatrix` / 任何 local matrix**（E43 真机实测静默退化成 identity）。
     * ⛔ 不用 `clipPath` / `clip(Rect)`：矩形画在 `[−off, −off+w+cw]`，出画幅的部分由画布自己裁掉。
     * ⚠️ 原型的"平铺第二张补右缘"（`COLLAPSE 1`）在 Kotlin 侧由 `ImageShader(Repeat)` 折叠成 1 op。
     *
     * @param speed 横向滚动速度（原型 `drawVeils` 的 `0.9 + i·0.4`）
     */
    private fun DrawScope.drawVeils(w: Float) {
        val bmp = bVeil ?: return
        val br = brushVeil ?: return
        val cw = bmp.width
        val dh = bmp.height
        if (cw <= 0 || dh <= 0) return
        val yTop = lay.dunes[lay.dunes.size - 1].mx - lay.h * 0.02
        var i = 0
        while (i < p.fx.veils) {
            val raw = tSec * world.driftPx * (0.9 + i * 0.4)
            val off = ((raw % cw) + cw) % cw
            val a = DesertMath.clamp(0.30 * world.sandK * (1.0 - i * 0.35), 0.0, 0.8)
            val yF = (yTop + lay.h * 0.03 * i).toFloat()
            val cv = drawContext.canvas.nativeCanvas
            cv.translate((-off).toFloat(), yF)
            drawRect(
                brush = br,
                topLeft = Offset.Zero,
                size = Size(w + cw, dh.toFloat()),
                alpha = a.toFloat(),
            )
            cv.translate(off.toFloat(), -yF)
            i++
        }
    }

    // ─────────────────────── 建素材（首帧 / 换画幅 / 换档位） ───────────────────────

    /**
     * `boot(w,h)` —— **位图与队列只在这里建**（E46/E47 同款：`onEnter` 时画幅还是 `Size.Zero`）。
     * 原型 `resizeTo(w,h)`：`layout → bakeAll → resetSmoke → buildCaravan → resetAudio`。
     *
     * ⭐ 与原型的唯一差别：`bakeAll` 被拆成 [bakeQueue] + [bakeStep]（逐帧预算 24 ms），
     * 位图先建成**全透明**的占位、行块逐帧往里长。
     *
     * ⚠️ 这一段会分配十几张位图（≈30 MB @1080p）⇒ **只在画幅或档位真的变化时跑**。
     *
     * @param reuseSky 画幅**没变**时传 `true` ⇒ 天幕位图**原样复用**（天幕内容与档位无关，
     *   见函数内的长注释）。⛔ 只有画幅变了 / 首次进入才传 `false`（那时才需要重新分配 + 铺底）。
     */
    private fun boot(w: Int, h: Int, reuseSky: Boolean) {
        // ⭐⭐ 先**摘出**旧天幕（`releaseBitmaps()` 会把它 recycle 掉）—— 它要么被**复用**，
        //    要么被拉伸成新天幕的底。
        val prevSky = bSky
        bSky = null
        cSky = null
        booted = true
        releaseBitmaps()                       // ⛔ 先交出旧的一整套
        p.applyLevel(curLevel ?: DesertLevel.MEDIUM)
        lay.layout(w.toDouble(), h.toDouble(), p)

        val ng = lay.dunes[0].nG
        val variants = p.smoke.variants

        // ① 天幕 / 日盘 / 调色（全屏）
        //
        // ⭐⭐ **档位变化时直接复用旧天幕**（2026-10-16 真机第三轮，这是「黑屏一下」的正解）：
        //    `applyLevel` 改的是 `dune.n`（5/7/9）等参数，而 `bakes.sky()` 只读 `lay` 与
        //    `fx.skyTex` —— **`skyTex` 不在 `applyLevel` 里** ⇒ 天幕内容与档位**完全无关**。
        //    ⛔ 不复用的话：档位一变就 `releaseBitmaps()` 清掉天幕、再花几十 ms 重烘
        //    ⇒ 中间那几帧 `blitFull(bSky)` 画的是**全透明** ⇒ 露出舞台深色背景层 = 黑屏。
        //    ⚠️ 这条路径**每次进入都必然走一次**：`VisualizerViewModel._quality` 的初值是
        //    `VisualQuality.Default`（= MEDIUM），真实设置**之后**才从存储读入 ⇒ 只要用户的
        //    画质不是 MEDIUM（本次真机就是 **HIGH**），进入效果后 `fx.level` 必然变一次。
        val reuse = reuseSky && prevSky != null && !prevSky.isRecycled &&
            prevSky.width == w && prevSky.height == h
        if (reuse) {
            bSky = prevSky
            cSky = Canvas(prevSky)
            skyBaked = true                    // 复用的是**已烘好**的天幕 ⇒ 无需兜底
        } else {
            bSky = newBmp(w, h); cSky = bSky?.let { Canvas(it) }
            skyBaked = false
            // ⭐ **画面兜底**：`createBitmap` 出来的天幕是**全透明**的，而真正的天幕要等
            //    本帧后半段的 `bakeStep()` 烘完 0 号单元（`SKY`）才有内容 —— 中间任何一步
            //    （分配失败 / 单元异常 / 设备上烘焙超时）都会让本帧的 `blitFull(bSky)`
            //    变成"什么都没画" ⇒ 露出 `VisualizerStage` 的黑底。这里先把它铺满：
            //      · 画幅变化（旋转 / 系统栏显隐）⇒ 用**旧天幕拉伸**（画面连续、只是形变）；
            //      · 首次进入 ⇒ 铺一色暖红（`SKY_COLORS[1]`，与天幕中段同色系）。
            //    ⚠️ 成本：一次全屏 `drawBitmap` 缩放（native）或一次 `eraseColor`（native memset）
            //    —— 只在 `boot()` 时发生一次，与它避免的黑帧完全不成比例。
            val cs = cSky
            if (cs != null) {
                val ps = prevSky
                if (ps != null && !ps.isRecycled && ps.width > 0 && ps.height > 0) {
                    ptBlit.isFilterBitmap = true
                    cs.drawBitmap(ps, null, Rect(0, 0, w, h), ptBlit)
                    ptBlit.isFilterBitmap = false
                    ps.recycle()
                } else {
                    bSky!!.eraseColor(DesertSmokeBakes.SKY_COLORS[1])
                }
            }
        }
        val ss = bakes.sunSize()
        bSun = if (ss > 0) newBmp(ss, ss) else null
        cSun = bSun?.let { Canvas(it) }
        bGrade = newBmp(w, h)

        // ② 沙丘画布：⛔ 不写死 nG（5/7/9 是档的函数）
        bDune = arrayOfNulls(ng)
        cDune = arrayOfNulls(ng)
        duneY0 = DoubleArray(ng)
        var gi = 0
        while (gi < ng) {
            val box = bakes.groupBox(gi)
            val bh = box[1].toInt()
            val bmp = newBmp(w, bh)
            bDune!![gi] = bmp
            cDune!![gi] = bmp?.let { Canvas(it) }
            duneY0[gi] = box[0]
            gi++
        }

        // ③ 12 张烟丝（6 变体 × 中性/暖边）+ 一份暂存
        val sz = bakes.wispSize()
        bWispTmp = newBmp(sz, sz)
        cWispTmp = bWispTmp?.let { Canvas(it) }
        bWisp = arrayOfNulls(variants * 2)
        cWisp = arrayOfNulls(variants * 2)
        var v = 0
        while (v < variants) {
            var warm = 0
            while (warm < 2) {
                val bmp = newBmp(sz, sz)
                bWisp!![v + warm * variants] = bmp
                cWisp!![v + warm * variants] = bmp?.let { Canvas(it) }
                warm++
            }
            v++
        }

        // ④ 骆驼（⭐ TEX 档 = **1 张**；⛔ 写死 6 会白烘 5 张）。位图先建占位，
        //    真正的点表填充走 [DesertBakeKind.CAMEL] 单元（与其余 13 类同一条分帧路径）。
        val dispH = camelDisplayH(p.caravan, h.toDouble())
        val bakeH = dispH * CamelTex.SS
        val boxC = camelBox(bakeH, p.caravan.lenK)
        bCamel = newBmp(boxC[0], boxC[1])
        cCamel = bCamel?.let { Canvas(it) }
        camelSprite = null

        // ⑤ 浮沙带（按 `0.9W × 0.11H` 烘焙 ⇒ 铺屏数与分辨率无关）
        val vb = bakes.veilBox()
        bVeil = newBmp(vb[0], vb[1])
        brushVeil = bVeil?.let { bandBrushOf(it) }

        // ⑥ 日晕 / 烟根（画幅相关 ⇒ 每次 boot 都重开）
        buildHaloShaders()
        buildEmberShader()

        // ⑦ 驼队时刻表 + 烟丝链复位（原型 `resizeTo` 的后三步）
        car.build(p.caravan)
        smoke.reset(p.smoke.puffs, p.smoke.seed, variants)
        audio.reset()

        // ⑧ 分帧队列（⛔ 两处画幅各不相同 ⇒ 逐个传进去）
        bakeQueue = DesertBakeQueue(ng, vb[0], vb[1], w, h, variants)
    }

    private fun newBmp(w: Int, h: Int): Bitmap? =
        if (w <= 0 || h <= 0) null else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /** 一条带的平铺画笔：X 向 `Repeated`（无缝循环）、Y 向 `Clamp`（上下沿不外溢）。 */
    private fun bandBrushOf(bmp: Bitmap): Brush =
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Clamp))

    /**
     * 三层日晕（原型 `halo(rad, a, r0K)`）。
     *
     * ⚠️ Android 的 `RadialGradient(x, y, r, …)` 的 stop 表**从圆心起算**（⛔ JS 的
     * `createRadialGradient(x0,y0,r0,x1,y1,r1)` 有内半径）⇒ 内盘那段（`r < r0K·R`）
     * 用**两个同 α 的 stop**（`0` 与 `r0K·R / rad`）补出来，之后按原型的
     * `0 → a / 0.55 → 0.72a / 1 → 0` 折算。三层各自的 α 倍率（`0.22 / 0.40 / 1.00`）
     * 烘进 stop，逐帧强度走 [ptHalo] 的 α（乘性等价）。
     */
    private fun buildHaloShaders() {
        val x = lay.sunX.toFloat()
        val y = lay.sunY.toFloat()
        val r = lay.sunR.toFloat()
        val layers = arrayOf(
            Triple(p.sun.halo0, 1.00, 0.94),
            Triple(p.sun.halo1, 0.40, 0.88),
            Triple(p.sun.halo2, 0.22, 0.90),
        )
        val built = arrayOfNulls<RadialGradient>(3)
        var i = 0
        while (i < 3) {
            val (k, mul, r0k) = layers[i]
            val rad = (r * k).toFloat()
            val r0 = r * r0k.toFloat()
            // ⛔ 这里**不要**再套 `.toFloat()` —— `rad` / `r0` 都已是 `Float`（`r:Float ×
            //    `k`/`r0k`:Double` 在 :813 / :814 就已经落定了），`Float / Float` 仍是 `Float`
            //    ⇒ 同类型转换会被 Kotlin 前端判为 `Redundant call of conversion method`。
            //    本行在 `rad == 0f` 时退化常数 ⇒ 恒为有限值，⛔ 无需防 0/0。
            val t0 = if (rad > 0f) (r0 / rad) else 0f
            val t1 = t0 + 0.55f * (1f - t0)
            built[i] = RadialGradient(
                x, y, max(1f, rad),
                intArrayOf(
                    argb(HALO_A_FULL * mul, 255, 104, 52),
                    argb(HALO_A_FULL * mul, 255, 104, 52),
                    argb(HALO_A_FULL * mul * 0.72, 255, 92, 46),
                    argb(0.0, 255, 66, 40),
                ),
                floatArrayOf(0f, t0, t1, 1f),
                Shader.TileMode.CLAMP,
            )
            i++
        }
        shHalo0 = built[0]
        shHalo1 = built[1]
        shHalo2 = built[2]
    }

    /** 烟根暖光（原型 `drawEmber` 的三段径向渐变）：逐帧强度走 `ptPuff.alpha` 乘性。 */
    private fun buildEmberShader() {
        emberX = lay.smokeX
        emberY = lay.srcRidgeY + lay.h * 0.010
        emberR = max(5.0, lay.h * 0.030)
        shEmber = RadialGradient(
            emberX.toFloat(), emberY.toFloat(), emberR.toFloat(),
            intArrayOf(
                argb(1.0, 255, 206, 142),
                argb(0.4, 255, 150, 72),
                argb(0.0, 255, 120, 52),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    // ─────────────────────── 分帧烘焙 ───────────────────────

    /**
     * 每帧在**固定时间预算**（[DesertSmokeBakes.BAKE_BUDGET_MS]）内推进队列，烘完即空转。
     *
     * ⭐ 预算判定在**每个单元之后** ⇒ 0 号单元（`SKY`）在任何设备上都必然执行
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
    private fun runBakeUnit(u: DesertBakeUnit) {
        when (u.kind) {
            DesertBakeKind.SKY -> {
                val cv = cSky ?: return
                bakes.sky(cv)
                skyBaked = true                // ⭐ 帧尾的「天幕保底」据此判断（见 [drawContent]）
            }
            DesertBakeKind.SUN -> cSun?.let { bakes.sun(it) }
            DesertBakeKind.DUNE -> {
                val cv = cDune?.getOrNull(u.a) ?: return
                bakes.group(cv, u.a)
            }
            DesertBakeKind.WISP -> {
                // ⚠️ `a` = 变体号、`b` = 0(中性) / 1(暖边) ⇒ 下标 = `变体 + warm·variants`
                val variants = p.smoke.variants
                val slot = u.a + u.b * variants
                val cv = cWisp?.getOrNull(slot) ?: return
                val tmp = bWispTmp ?: return
                val tmpCv = cWispTmp ?: return
                bakes.wisp(cv, tmp, tmpCv, u.a, u.b != 0)
            }
            DesertBakeKind.CAMEL -> {
                // ⭐ 真正填点表（位图已在 [boot] 建占位；⛔ 不得在此建位图）。
                //    尺寸与 [boot] 里的占位必须**同源同式** ⇒ 一律现算，⛔ 不缓存字段。
                val cv = cCamel ?: return
                val bakeH = camelDisplayH(p.caravan, lay.h) * CamelTex.SS
                val boxC = camelBox(bakeH, p.caravan.lenK)
                camelSprite = bakeTexCamel(cv, ptCamel, pathCamel, bakeH, boxC[0], boxC[1], p.caravan.lenK)
            }
            DesertBakeKind.VEIL -> {
                val bmp = bVeil ?: return
                val rows = u.b - u.a
                if (rows <= 0) return
                val px = rowBuf(0, rows * bmp.width)
                bakes.veilRows(px, bmp.width, bmp.height, u.a, u.b)
                bmp.setPixels(px, 0, bmp.width, 0, u.a, bmp.width, rows)
            }
            DesertBakeKind.GRADE -> {
                val bmp = bGrade ?: return
                val rows = u.b - u.a
                if (rows <= 0) return
                val px = rowBuf(1, rows * bmp.width)
                bakes.gradeRows(px, bmp.width, bmp.height, u.a, u.b)
                bmp.setPixels(px, 0, bmp.width, 0, u.a, bmp.width, rows)
            }
        }
    }

    /** 行块槽位 → 暂存数组下标（⛔ 两个 kind 各一份，避免一次大块把另一块挤掉）。 */
    private fun rowBuf(slot: Int, need: Int): IntArray {
        val cur = rowPx[slot]
        if (cur != null && cur.size >= need) return cur
        val next = IntArray(max(need, DesertSmokeBakes.UNIT_PX_CAP))
        rowPx[slot] = next
        return next
    }

    // ─────────────────────── 小工具 ───────────────────────

    private fun alpha8(a: Double): Int =
        DesertSmokeBakes.u8(DesertMath.clamp(a, 0.0, 1.0) * 255.0)

    private fun argb(a: Double, r: Int, g: Int, b: Int): Int =
        DesertSmokeBakes.argb(a, r, g, b)

    companion object {
        /** 日晕强度的**满档**（原型 `a = 0.095·sunGain`，`sunGain ≤ 1+0.35·0.6 = 1.21`）。 */
        private const val HALO_A_FULL = 0.12

        /** 24 ms 预算换算成纳秒。 */
        private val BAKE_NS = DesertSmokeBakes.BAKE_BUDGET_MS * 1000000L

        /**
         * `FxLevel → [DesertLevel]` 的**一次性映射**（与 `TripodFogRenderer.tripodLevelOf`、
         * `MoonlitRenderer.moonLevelOf`、`SnowCedarRenderer.snowLevelOf` 同一条）。
         *
         * ⚠️ `OFF → LOW`（不是"关掉沙丘"）：低档只是砍到 5 张画布 / 14 粒烟丝 / 无颗粒。
         */
        internal fun desertLevelOf(level: FxLevel): DesertLevel = when (level) {
            FxLevel.OFF -> DesertLevel.LOW
            FxLevel.LITE -> DesertLevel.MEDIUM
            FxLevel.FULL -> DesertLevel.HIGH
        }
    }
}

/**
 * E48 的世界状态（原型 §7 `smoothAudio` + §8 `worldState`）—— 大漠的**常驻时间基线**。
 *
 * ## 为什么"一直在吹"必须是时间驱动
 * 所有者的需求是"大漠孤烟"的氛围：阵风 `gust`、浮沙漂移 `driftPx` 都只吃 `t`，
 * 音频只是**增益**（`kRise/kSway/kSun/kSand`）⇒ 无声帧 = 静态定稿帧（T11 判"无声零漂移"，
 * 但**基线摆动仍在** ⇒ 画面不死）。反过来写（让音频驱动 gust 本身）会变成"停歌即静止"，
 * 正是 E44/E46/E47 立过的口径红线。
 */
internal class DesertWorldState(
    private val p: DesertP,
    private val l: DesertLayout,
) {
    /** 阵风包络 `∈[-1,1]`（时间基线，⛔ 音频）。 */
    var gust = 0.0
        private set

    /** 摆幅倍率：无声时恒 0.50 ⇒ 烟照样摆，⛔ 静音即定格。 */
    var swayMul = 0.50
        private set

    /** 沿柱位移（像素/秒）。 */
    var risePx = 0.0
        private set

    /** 余烬强度（0.70 + 0.50·bass）。 */
    var ember = 0.70
        private set

    var sunGain = 1.0
        private set
    var sandK = 0.40
        private set

    /** 暖雾强度（`fx.haze × (0.80 + 0.50·section)`）。 */
    var haze = 0.30
        private set

    /** 浮沙横向漂移（像素/秒）。 */
    var driftPx = 0.0
        private set

    var kRise = 0.0
        private set
    var kSway = 0.0
        private set
    var kSun = 0.0
        private set
    var kSand = 0.0
        private set

    /**
     * 原型 `worldState(t, dt)`。
     *
     * ⚠️ 与原型一样**不吃 `dt`**（全部是 `t` 的显函数）；`dt` 只属于 [DesertAudio.update]
     * 那条平滑。少留一个参数就少一条"其实逐帧在积分"的错觉。
     */
    fun state(t: Double, bass: Double, mid: Double, treble: Double, energy: Double, section: Double) {
        gust = sin(DesertMath.TAU * t * p.wind.gustHz1) * 0.62 +
            sin(DesertMath.TAU * t * p.wind.gustHz2 + 1.3) * 0.38
        kRise = p.audio.kRise * bass
        kSway = p.audio.kSway * energy
        kSun = p.audio.kSun * treble
        kSand = p.audio.kSand * mid
        swayMul = 0.50 + kSway
        risePx = p.smoke.rise * l.h * (0.55 + kRise)
        ember = 0.70 + 0.50 * bass
        sunGain = 1.0 + 0.35 * kSun
        sandK = 0.40 + kSand
        haze = p.fx.haze * (0.80 + 0.50 * section)
        driftPx = p.wind.base * l.w * (1.0 + p.wind.gustAmp * 0.6 * gust) * (1.0 + 0.5 * bass)
    }
}

/**
 * 五条包络的平滑器（原型 `SM` + `smoothAudio`）—— 复用共享 [AudioSmoother] 的
 * `updateDt`（`k = 1 − (1−base)^(dt·60)`），⛔ 另写一份 EMA。
 *
 * ⭐ 取 `attack = release = 1 − exp(−smooth)` 时它**逐帧等于**原型的
 * `k = 1 − exp(−smooth·60·dt)`：`updateDt` 给的是 `k = 1 − (1−base)^(dt·60)`，
 * 而 `1−base = exp(−smooth)` ⇒ 恒等式而非近似。
 * ⚠️ [AudioSmoother] 是 `Float`、原型是 `Double` ⇒ 包络有 ~1e-7 的量化差（只进黄金对账，
 * `gust` 是纯时间量 ⇒ 与音频完全无关，能按 1e-12 对）。
 */
internal class DesertAudio(p: DesertP) {
    private val alpha = (1.0 - exp(-p.audio.smooth)).toFloat()

    private val smBass = AudioSmoother(alpha, alpha)
    private val smMid = AudioSmoother(alpha, alpha)
    private val smTreble = AudioSmoother(alpha, alpha)
    private val smEnergy = AudioSmoother(alpha, alpha)
    private val smSection = AudioSmoother(alpha, alpha)

    val bass: Double get() = smBass.value.toDouble()
    val mid: Double get() = smMid.value.toDouble()
    val treble: Double get() = smTreble.value.toDouble()
    val energy: Double get() = smEnergy.value.toDouble()
    val section: Double get() = smSection.value.toDouble()

    /** 一帧一次；`dtSec` 直接来自帧循环（⛔ 隐含 60fps —— [AudioSmoother.update] 那条老路）。 */
    fun update(
        rawBass: Double, rawMid: Double, rawTreble: Double,
        rawEnergy: Double, rawSection: Double, dtSec: Double,
    ) {
        smBass.updateDt(rawBass.toFloat(), dtSec.toFloat())
        smMid.updateDt(rawMid.toFloat(), dtSec.toFloat())
        smTreble.updateDt(rawTreble.toFloat(), dtSec.toFloat())
        smEnergy.updateDt(rawEnergy.toFloat(), dtSec.toFloat())
        smSection.updateDt(rawSection.toFloat(), dtSec.toFloat())
    }

    fun reset() {
        smBass.reset(); smMid.reset(); smTreble.reset(); smEnergy.reset(); smSection.reset()
    }
}
