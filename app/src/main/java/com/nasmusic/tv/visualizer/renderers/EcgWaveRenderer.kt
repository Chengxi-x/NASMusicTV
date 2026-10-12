package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.fx.FxLevel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * E24 `ECG_WAVE` — 心跳 · 心电图式滚动频谱
 *
 * 视觉：经典 CRT 绿线心电图。一条连续折线从屏幕最右侧生成扫描点，
 * 已绘制的波形冻结并整体向左匀速平移，最左端超出屏幕被裁掉。
 *
 * **只取鼓点**：折线不由宽频时域波形 [AudioFrame.waveform] 驱动（那会把人声、
 * 背景乐器一并画进来）。仅在**鼓点命中**时注入一个完整的心搏复合波，
 * 相邻鼓点之间折线贴中心基线走平。
 *
 * **鼓点判定（双通道，抗漏帧）**：
 *   ① [AudioFrame.beat] —— 官方标志，但只在 1 帧内为 true；而节拍检测跑 50Hz、
 *      渲染数据只 30fps 更新（`EMIT_INTERVAL_MS`），在 30Hz 屏幕上会整拍漏掉；
 *   ② [AudioFrame.pulse] 上升沿 —— pulse 是「快起慢落」包络（约 250ms 回落），
 *      跨帧存活，因此漏掉的单帧标志也能被补回来。
 *   两者取或，再经最短间隔 [MIN_GAP_COLS] 去密。
 *
 * **强弱差异**：幅度取 [AudioFrame.bassRaw]（**未**峰值归一化的低频原始能量）
 * 除以自身慢速均值，得到相对强度；绝不能用 [AudioFrame.bass]——它经 `boost()`
 * 峰值跟随归一化后，在鼓点瞬间**恒为 1.0**，会让每一次鼓点长得一模一样。
 *
 * **相对中心线上下跳动**：基线固定在屏幕垂直中线，心搏波相对基线上下都有振幅
 * （P 波 / R 波向上，Q 波 / S 波下探）。
 *
 * **丢拍（控密度）**：心搏复合波占 **51 列 ≈ 567 ms**（`speed` = 90 列/秒），因此要求
 * 相邻两次渲染间隔 ≥ [MIN_GAP_COLS]（72 列 = 800ms ≈ 75 BPM 上限）；
 * 在这个窗口内检出的鼓点直接丢弃，保证每个心搏完整、彼此不粘连
 * （连续鼓点/鼓花不会堆成一团）。
 *
 * **心搏波形 = 一列一个采样（§A8 第 0 条 · P0）**：[HB] 表索引 = 距鼓点的**整数列数**，
 * O(1) 查表、无插值 —— renderer 每写一列才产生一个可见折线顶点 ⇒
 * 波形时间分辨率就是 1 列，再细的表也画不出来。临床时程（列宽 1000/90 = 11.111 ms）：
 * P 升余弦（列 0..8，峰在列 4）、PR 等电位（列 8..14，**PR 间期 155.6 ms**）、
 * QRS 折线（列 14..22，**宽 88.9 ms**，**R 峰精确落在列 18** ⇒ 采样峰值恒 1.000）、
 * ST 等电位（列 22..26）、T 不对称升余弦（列 26..50，**J→T 峰 200.0 ms、QT 400.0 ms**）。
 * ⛔ **T 必须慢升快降**（升 155.6 ms / 降 111.2 ms = 1.40 : 1）：把 T 做成对称余弦、
 * 或把升降倒过来，是「不像心电图」最常见的来源。
 * ⛔ 表长与 [SPEED_COLS_PER_SEC] 绑定：`HB.size` 必须 < [MIN_GAP_COLS] 列
 * （门禁 `EcgWaveformTest` ①，否则相邻心搏粘连）。
 *
 * ⚠️ **T/QRS 宽度比 = 2.0–4.0，这区间跟踪的是临床值、不是设计意图**：
 * 门禁 `EcgWaveformTest` 用「各自波峰 4% 以上的**连续**列数」量两个宽度
 * （QRS 7 列 / T 21 列 = **3.0**），而临床 T 波 266.7 ms ÷ QRS 88.9 ms ≈ **3.0** ——
 * 下限 2.0 卡「T 必须是宽圆丘、不能退化成第二个窄尖」，上限 4.0 卡「T 不能摊成一段长弧」。
 * ⛔ 门限必须取**各波自身峰值**的 4%：用全表峰值（R = 1.0）当两者门限会把 QRS 的
 * 远场列全算进去、把比例压塌；用 T 峰自己的门限去量 QRS 则相反。
 * ⛔ 只数峰周围的**连续**段：零散落在门限外的列不是波宽，而是别的波渗进来的尾巴。
 *
 * **§A8 观感改造（第 1–5 条）**：
 * - 栅格**纵深**：线色 alpha 由中心 `0.30` 衰减到边缘 `0.10`；除中线外一律**普通叠加**
 *   （原全 `Plus` 会在密集处叠出死白）；竖线按**心电纸**规格 —— 大格 0.2 s
 *   （`gridStepCols` 列，由 `speed` 推导）+ 5 等分小格，260+ 条线**按 4 档 alpha 合批**；
 * - **余辉拖尾**：`ghost` = `history` 的逐帧衰减副本（`GHOST_DECAY` 原地乘，零分配），
 *   作为最底层 Path（`alpha 0.16`）⇒ 波形扫过留下拖影；
 * - **辉光改径向**：原「加宽描边假光晕」改为「主线 + 1 层 3× 宽的 `alpha 0.10`」，
 *   且**仅 `FxLevel != OFF` 时画**；
 * - **CRT 后处理**：`postFx = PostFx(vignette = 0.48f, grain = 0.030f, scanline = 0.14f)`；
 * - **基线漂移（呼吸波）**：等电位段不是数学零，叠加 `sin(wanderPhase) × 0.012`；
 *   ⛔ 相位**只能 dt 累加**（§R1 红线），⛔ 幅度 ≤ 0.015（否则毁掉刚修好的等电位平段）。
 *
 * **峰顶无帽**：不绘制任何节拍点/扫描头圆点。
 *
 * 性能红线：draw 内零分配；history / ghost 为成员，仅在画布尺寸变化时重新分配；
 * 滚动与心搏展开按**列计数**基准匀速（整数列号精确无漂移，不依赖帧率）。
 */
class EcgWaveRenderer : RendererFx() {

    override val theme = VisualizerTheme.ECG_WAVE

    // §A8 第 4 条 CRT 后处理（暗角 0.48 ≥ §13.5-D9 下限 0.42）
    override val postFx = PostFx(vignette = 0.48f, grain = 0.030f, scanline = 0.14f)

    // ── CRT 绿配色（固定绿色，不取封面色）──────────────────────
    private val lineColor = Color(0xFF33FF7A)   // 主绿线
    private val gridColor = Color(0xFF0E5A2E)   // 暗绿栅格

    // ── 历史缓冲（环形，零 arraycopy）──────────────────────────
    private var history: FloatArray? = null
    /** §A8 第 2 条：余辉拖尾（`history` 的逐帧衰减副本，draw 内原地乘 ⇒ 零分配） */
    private var ghost: FloatArray? = null
    private var cols = 0
    private var rightPtr = -1

    /** 已写列数（单调递增）：滚动与心搏展开按**列计数**基准，整数精确无漂移 */
    private var colIdx = 0
    /** 累计未满一列的位移（避免 roundToInt 造成的系统性漂移） */
    private var colAccum = 0f
    /** 当前心搏的起点（列号）；[NO_BEAT] = 尚无心搏 */
    private var beatCol = NO_BEAT
    /** 上一次真正渲染心搏的列号，用于丢拍；[LAST_FIRE_NONE] 保证第一拍必过 */
    private var lastFireCol = LAST_FIRE_NONE
    /** 本次心搏幅度（0.45..1），命中瞬间快照，整段心搏共用 */
    private var spikeAmp = 1f
    /** 低频原始能量的慢速均值（强弱归一化基准） */
    private var bassAvg = 0f
    /** 上一帧 pulse，用于检测上升沿 */
    private var lastPulse = 0f
    /** §A8 第 5 条：呼吸波相位累加器。⛔ 禁止 `nowMs × 系数`（§R1 红线） */
    private var wanderPhase = 0f

    /** 滚动速度（列/秒）。cols≈屏宽/2，90 列/秒 ≈ 全屏 10–11s 扫过 */
    private val speed = SPEED_COLS_PER_SEC

    /**
     * §A8 第 1 条：竖线大格间距（列）= 0.2 s。
     * ⛔ **由 `speed` 推导**（而非硬编码 18）⇒ 改 `speed` 时栅格自动同步，不会失配。
     */
    private val gridStepCols = (speed * 0.2f).toInt()

    /** 复用 Path（零分配） */
    private val path = Path()
    private val ghostPath = Path()
    /** §A8 第 1 条：竖线按距中心距离分 4 档 alpha 合批（260+ 条线 ⇒ 只 4 次 drawPath） */
    private val gridPaths = Array(GRID_ALPHA_LEVELS) { Path() }

    // 宽度恒定的描边 ⇒ 构造期预分配（原实现每帧 new 3 个 Stroke）
    private val mainStroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    private val glowStroke = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    override fun onEnterContent(ctx: RenderContext) {
        rightPtr = -1
        colIdx = 0
        colAccum = 0f
        beatCol = NO_BEAT
        lastFireCol = LAST_FIRE_NONE
        spikeAmp = 1f
        bassAvg = 0f
        lastPulse = 0f
        wanderPhase = 0f
        // history / ghost 在首帧按 canvasSize 分配（onEnter 时 size 可能仍为 0）
    }

    private fun ensureCapacity(w: Float) {
        val need = maxOf(256, (w / 2f).roundToInt())
        if (history == null || history!!.size != need) {
            history = FloatArray(need)
            ghost = FloatArray(need)
            cols = need
            rightPtr = -1
        }
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        ensureCapacity(w)
        val hist = history!!
        val gh = ghost!!

        // ── 时间步进（小数累积，长期不漂移）；dt 由基类钳 0.1s ──
        colAccum += speed * fx.dt
        var n = colAccum.toInt()
        if (n < 0) n = 0
        colAccum -= n

        // ── §A8 第 5 条：呼吸波相位（dt 累加）──
        wanderPhase += fx.dt * WANDER_RATE

        // ── 低频原始能量慢速均值（强弱归一化基准）──────────────
        val raw = frame.bassRaw
        if (bassAvg <= 0f) bassAvg = raw else bassAvg += (raw - bassAvg) * BASS_AVG_ALPHA

        // ── 鼓点判定：官方标志 + pulse 上升沿（补 30fps 漏掉的单帧标志）──
        val pulse = frame.pulse
        val pulseRising = pulse - lastPulse > PULSE_RISE_EPS
        lastPulse = pulse
        if ((frame.beat || pulseRising) && (colIdx - lastFireCol) >= MIN_GAP_COLS) {
            // 相对强度：bassRaw / 近端均值 → 典型 ≈1，鼓点越重越大
            val rel = if (bassAvg > 1e-5f) raw / bassAvg else 1f
            // 映射到 0.45..1，保留强弱差异（避免"每次鼓点一样高"）
            spikeAmp = (0.45f + (rel - 1f) * 0.35f).coerceIn(0.45f, 1f)
            beatCol = colIdx
            lastFireCol = colIdx
        }

        // ── §A8 第 2 条：余辉整段原地衰减（先衰减再写，保证最新列满强度）──
        var d = 0
        while (d < cols) {
            gh[d] *= GHOST_DECAY
            d++
        }

        // ── 写入新列：心搏波按整数列龄查表展开，无鼓点则走基线 ──
        // ⚠️ 顺序不可反：`rightPtr` 语义 =「最后写入的下标」、初值 -1
        //    ⇒ 必须先 `(rightPtr + 1) % cols` 再写，否则首帧写 hist[-1] 越界。
        val wander = sin(wanderPhase) * WANDER_AMP
        var k = 0
        while (k < n) {
            val v = heartbeatAt(colIdx - beatCol) * spikeAmp + wander
            rightPtr = (rightPtr + 1) % cols
            hist[rightPtr] = v
            gh[rightPtr] = v
            colIdx++
            k++
        }

        val midY = h * 0.5f     // 基线：屏幕垂直中线
        val amp = h * AMP_FRACTION

        // ── §A8 第 1 条 CRT 栅格（纵深 + 心电纸大格）──────────
        drawGrid(w, h, midY)

        // ── 主折线（最旧 → 最新，左 → 右）────────────────────
        path.reset()
        ghostPath.reset()
        var i = 0
        while (i < cols) {
            val idx = (rightPtr + 1 + i) % cols
            val x = (i.toFloat() / (cols - 1).coerceAtLeast(1)) * w
            val y = midY - hist[idx] * amp
            val gy = midY - gh[idx] * amp
            if (i == 0) {
                path.moveTo(x, y)
                ghostPath.moveTo(x, gy)
            } else {
                path.lineTo(x, y)
                ghostPath.lineTo(x, gy)
            }
            i++
        }
        // §A8 第 2 条余辉层（最底层，宽 3f）
        drawPath(ghostPath, lineColor, style = mainStroke,
            blendMode = BlendMode.Plus, alpha = 0.16f)
        // §A8 第 3 条辉光层：1 层 3× 宽、仅 fx 开启时画
        if (fx.level != FxLevel.OFF) {
            drawPath(path, lineColor, style = glowStroke,
                blendMode = BlendMode.Plus, alpha = 0.10f)
        }
        // 主绿线
        drawPath(path, lineColor, style = mainStroke,
            blendMode = BlendMode.Plus, alpha = 0.95f)
    }

    private fun DrawScope.drawGrid(w: Float, h: Float, midY: Float) {
        // 中线：唯一保留 Plus 的一条
        drawLine(gridColor, Offset(0f, midY), Offset(w, midY),
            strokeWidth = 1.5f, alpha = GRID_A_CENTER, blendMode = BlendMode.Plus)
        // 上下偏移线：按距中心距离衰减（§A8 第 1 条）+ 普通叠加
        val rows = 4
        for (r in 1..rows) {
            val off = midY * r / (rows + 1)
            val a = GRID_A_EDGE + (GRID_A_CENTER - GRID_A_EDGE) * (1f - off / midY)
            drawLine(gridColor, Offset(0f, midY - off), Offset(w, midY - off),
                strokeWidth = 0.8f, alpha = a)
            drawLine(gridColor, Offset(0f, midY + off), Offset(w, midY + off),
                strokeWidth = 0.8f, alpha = a)
        }
        // 竖线：大格 = `gridStepCols` 列（0.2 s），再 5 等分小格；
        // 260+ 条线 ⇒ ⛔ 不逐线 drawLine，按距中心距离分 4 档 alpha 合批（4 次 drawPath）
        val pxPerCol = w / cols.coerceAtLeast(1)
        val small = gridStepCols * pxPerCol / GRID_SUBDIV
        if (small < 1f) return
        for (p in gridPaths) p.reset()
        val half = w * 0.5f
        var x = half % small          // 让中线正好落在格点上
        while (x <= w) {
            val level = ((1f - abs(x - half) / half) * GRID_ALPHA_LEVELS)
                .toInt().coerceIn(0, GRID_ALPHA_LEVELS - 1)
            gridPaths[level].moveTo(x, 0f)
            gridPaths[level].lineTo(x, h)
            x += small
        }
        for (level in 0 until GRID_ALPHA_LEVELS) {
            drawPath(gridPaths[level], gridColor, alpha = GRID_ALPHAS[level])
        }
    }

    internal companion object {
        /**
         * 滚动速度（列/秒）。**单一真源**：`HB` 表长、`gridStepCols`、`MIN_GAP_COLS`
         * 全部由它推导/校验（门禁 `EcgWaveformTest` 用它把「表长 ↔ speed ↔ 最短间隔」
         * 三者绑在一起 —— 改 `speed` 而忘重建表会被抓）。
         */
        const val SPEED_COLS_PER_SEC = 90f

        /** 哨兵：列龄恒 > 表长 ⇒ 查表自然返回 0（开播瞬间不漏假信号）。
         *  ⛔ 不用 Int.MIN_VALUE（`colIdx - Int.MIN_VALUE` 会溢出）。 */
        const val NO_BEAT = -1_000_000

        /**
         * 相邻两次心搏最短间隔（列）= 72 × 1000/90 = 800ms ≈ 75 BPM 上限。
         * ⚠️ 声明顺序：必须排在 [LAST_FIRE_NONE] **之前** —— Kotlin 的常量初始化器
         * **不允许同作用域内前向引用**（写反了报
         * 「Variable 'MIN_GAP_COLS' must be initialized」）。
         */
        const val MIN_GAP_COLS = 72

        /** 哨兵：保证第一拍必定通过间隔判定。 */
        const val LAST_FIRE_NONE = -(MIN_GAP_COLS + 1)

        /** 主峰高度占半屏高的比例（原 0.40 → 0.26，压制整体高度） */
        const val AMP_FRACTION = 0.26f

        /** pulse 上升沿阈值：> 该值视为一次鼓点（补漏帧） */
        const val PULSE_RISE_EPS = 0.18f

        /** 低频均值 EMA 系数（每帧），≈0.8s 时间常数 */
        const val BASS_AVG_ALPHA = 0.025f

        /** §A8 第 2 条：余辉每帧衰减系数（≈10 帧可见拖影） */
        const val GHOST_DECAY = 0.90f

        /** §A8 第 5 条：呼吸波幅度。⛔ 必须 ≤ 0.015，否则毁掉等电位平段 */
        const val WANDER_AMP = 0.012f

        /** §A8 第 5 条：呼吸波角速度（rad/s）≈ 7 s 周期 */
        const val WANDER_RATE = 0.9f

        /** §A8 第 1 条：大格 5 等分小格 */
        const val GRID_SUBDIV = 5

        /** §A8 第 1 条：栅格 alpha 档数（中心亮 → 边缘暗） */
        const val GRID_ALPHA_LEVELS = 4

        /** 4 档 alpha（下标 0 = 最靠边缘 / 3 = 最靠中心） */
        val GRID_ALPHAS = floatArrayOf(0.10f, 0.17f, 0.23f, 0.30f)

        /** 栅格中心 / 边缘 alpha（§A8 第 1 条） */
        const val GRID_A_CENTER = 0.30f
        const val GRID_A_EDGE = 0.10f

        /**
         * 心搏波（P-QRS-T）逐列采样值：共 51 列 = 566.7 ms @ 90 列/秒。
         * 索引 = 距鼓点的**列数**；[18] = R 峰（精确落在显示列上 ⇒ 峰值恒 1.000）、
         * [16] = Q 谷、[21] = S 谷、[40] = T 峰。
         *
         * ⛔ **本表是再生产物，⛔ 禁止手改**（§E44「数字只能由脚本再生」）。唯一真源 =
         * `docs/archive/verification/scripts/ecg_waveform_gen.py`：每个临床幅度/时程的取值
         * 与出处（Cooksey / AHA / Bayés de Luna / Hurtado / ecg.utah）都写在那里。
         * 改临床参数 ⇒ 改脚本 ⇒ **重跑**并把本表与 `EcgWaveformTest` 一起提交；
         * 脚本自带 `--check` 形态学自检（表长、R 整列满幅、S 最深、T/QRS 宽度比、
         * PR/ST 等电位、T 升:降 > 1.2、T 起点曲率翻转、Q 谷 < 2 列）。
         *
         * 临床地标（列宽 1000/90 = 11.111 ms，**地标一律以「列」定义、ms 是派生量** ——
         * 采样点就是列本身，按 ms 定义再取整列会让 R 峰采样偏离峰顶）：
         * - P 起 0 / P 峰 4 / P 终 8
         * - QRS 起 14 = **PR 间期 155.6 ms**（正常 120–200）
         * - Q 谷 16（宽 22.2 ms，<40 ms 且锐利）、R 峰 18（**R 峰时 44.4 ms = QRS 的 47%**，AHA ≤45%）
         * - S 谷 21、J 点 22 = **QRS 宽 88.9 ms**（正常 80–100）
         * - T 起 26（ST 段 44.4 ms）、T 峰 40 = **J→T 峰 200.0 ms**、T 终 50 = **QT 400.0 ms**
         *
         * 幅度（R 归一 = 1.0）：P +0.10、Q −0.15、R +1.00、S −0.17、T +0.25。
         * ⛔ **T 不对称升余弦：升 155.6 ms / 降 111.2 ms = 1.40 : 1**（ecg.utah：
         * 「slow ascending ramp and faster descending ramp」）⇒ 列 26→40 升、40→50 降。
         * ⛔ 表长与 [SPEED_COLS_PER_SEC] 绑定：`HB.size < MIN_GAP_COLS`
         * （门禁 `EcgWaveformTest` ①）。
         */
        val HB = floatArrayOf(
            +0.000000f, +0.014645f, +0.050000f, +0.085355f, +0.100000f, +0.085355f, +0.050000f, +0.014645f, +0.000000f, +0.000000f,
            +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, -0.075000f, -0.150000f, +0.425000f, +1.000000f, +0.610000f,
            +0.220000f, -0.170000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.000000f, +0.003134f, +0.012379f, +0.027271f,
            +0.047064f, +0.070765f, +0.097185f, +0.125000f, +0.152815f, +0.179235f, +0.202936f, +0.222729f, +0.237621f, +0.246866f,
            +0.250000f, +0.243882f, +0.226127f, +0.198473f, +0.163627f, +0.125000f, +0.086373f, +0.051527f, +0.023873f, +0.006118f,
            +0.000000f,
        )

        /** 列龄（列）→ 幅值。O(1) 整数索引，无插值、无浮点、无分配。 */
        internal fun heartbeatAt(ageCols: Int): Float =
            if (ageCols >= 0 && ageCols < HB.size) HB[ageCols] else 0f
    }
}
