package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.fx.AudioSmoother
import kotlin.math.exp
import kotlin.math.sin

/**
 * E47「雪松」的世界状态（原型 §6 `smoothAudio` + §7 `worldState`）—— 漫天大雪的**常驻时间基线**。
 *
 * ## 为什么"一直在下"必须是时间驱动
 * 所有者的第一条需求就是「漫天大雪，一直在下」。所以阵风 `gust`、摆相 `swayPhase` 都只吃 `t`，
 * 音频只是**增益**（`kWind/kGust/kSun`）⇒ 无声帧 = 静态定稿帧（护栏 [SnowPersonGateTest] 的 T8）。
 * 反过来写（让音频驱动 gust 本身）会变成"停歌即静止"，正是 E44/E46 立过的口径红线。
 *
 * ## 单位
 * `windPx` / `fallPx` / `sway` 是**画幅像素**量（乘了 [SnowCedarLayout] 的 `w`/`h`），
 * 直接喂 [SnowField.stepFlakes] 与 `drawBitmapMesh`。
 */
internal class SnowWorldState(
    private val p: SnowCedarP,
    private val l: SnowCedarLayout,
) {
    /** 阵风包络 `∈[-1,1]`（时间基线，⛔ 音频）—— [SnowPersonDust.step] 吃的就是它。 */
    var gust = 0.0
        private set

    var wind = 0.0
        private set
    var windPx = 0.0
        private set
    var fallPx = 0.0
        private set
    var sway = 0.0
        private set
    var sunGain = 1.0
        private set

    /** 音频增益（`AudioSmoother` 后的三条包络 × 系数）。 */
    var kWind = 0.0
        private set
    var kGust = 0.0
        private set
    var kSun = 0.0
        private set

    /** 摆幅倍率：无声时恒 [SwayMulQuiet]（0.45）⇒ ⛔ 静音即静止。 */
    var swayMul = SWAY_MUL_QUIET
        private set

    /**
     * 原型 `worldState(t, dt)`。
     *
     * ⚠️ 与原型一样**不吃 `dt`**（全部是 `t` 的显函数）；`dt` 只属于 [SnowAudio.update] 那条平滑。
     * 少留一个参数就少一条"其实逐帧在积分"的错觉 —— 原型的 `void dt` 是同一意图。
     */
    fun state(t: Double, bass: Double, energy: Double, treble: Double) {
        gust = sin(SnowCedarMath.TAU * t * p.wind.gustHz1) * 0.62 +
            sin(SnowCedarMath.TAU * t * p.wind.gustHz2 + 1.3) * 0.38
        kWind = p.audio.kWind * bass
        kGust = p.audio.kGust * energy
        kSun = p.audio.kSun * treble
        swayMul = SWAY_MUL_QUIET + SWAY_MUL_SPAN * bass
        wind = p.wind.base * (1 + kWind) * (1 + p.wind.gustAmp * (0.5 + kGust) * gust)
        windPx = wind * l.w
        fallPx = p.snow.fall * l.h * (1 + 0.35 * kWind)
        val swayPhase = sin(SnowCedarMath.TAU * t * p.wind.swayHz) * 0.7 +
            sin(SnowCedarMath.TAU * t * p.wind.swayHz * 2.7 + 0.8) * 0.3
        sway = p.wind.swayAmp * l.w * swayMul * swayPhase
        sunGain = 1 + kSun * 0.35
    }

    companion object {
        const val SWAY_MUL_QUIET = 0.45
        const val SWAY_MUL_SPAN = 0.55
    }
}

/**
 * 三条包络的平滑器（原型 `SM` + `smoothAudio`）—— 复用共享 [AudioSmoother] 的
 * `updateDt`（`k = 1 − (1−base)^(dt·60)`），⛔ 另写一份 EMA。
 *
 * ⭐ 取 `attack = release = base = 1 − exp(−smooth)` 时它**逐帧等于**原型的
 * `k = 1 − exp(−smooth·60·dt)`：`updateDt` 给的是 `k = 1 − (1−base)^(dt·60)`，
 * 而 `1−base = exp(−smooth)` ⇒ `k = 1 − exp(−smooth·60·dt)`，恒等式而非近似。
 * 所以本效果**不引入** E44 那种 attack/release 分离
 * （暗角快一档会"每拍眨一下"，这里的风同理）。
 *
 * ⚠️ [AudioSmoother] 是 `Float`，原型是 `Double` ⇒ 包络有 ~1e-7 的量化差。
 * 这条差**只**进 `*World` 黄金对账（判据按 1e-4 收），⛔ 不影响肩雪：
 * `gust` 是纯时间量 ⇒ [SnowPersonDust] 与音频完全无关，能按 1e-12 对。
 */
internal class SnowAudio(p: SnowCedarP) {
    /** `smooth → 60 fps 单帧系数`（原型 `1 − exp(−0.085·60·dt)`，dt=1/60 ⇒ `1 − exp(−0.085)`）。 */
    private val alpha = (1.0 - exp(-p.audio.smooth)).toFloat()

    private val smBass = AudioSmoother(alpha, alpha)
    private val smEnergy = AudioSmoother(alpha, alpha)
    private val smTreble = AudioSmoother(alpha, alpha)

    val bass: Double get() = smBass.value.toDouble()
    val energy: Double get() = smEnergy.value.toDouble()
    val treble: Double get() = smTreble.value.toDouble()

    /** 一帧一次；`dtSec` 直接来自帧循环（⛔ 隐含 60fps —— [AudioSmoother.update] 那条老路）。 */
    fun update(rawBass: Double, rawEnergy: Double, rawTreble: Double, dtSec: Double) {
        smBass.updateDt(rawBass.toFloat(), dtSec.toFloat())
        smEnergy.updateDt(rawEnergy.toFloat(), dtSec.toFloat())
        smTreble.updateDt(rawTreble.toFloat(), dtSec.toFloat())
    }

    fun reset() {
        smBass.reset()
        smEnergy.reset()
        smTreble.reset()
    }
}
