package com.nasmusic.tv.visualizer.renderers

import kotlin.math.sin

/**
 * **测试专用**的合成律动 —— 原型 §6 `audioSim` 的 1:1 移植（`P.audioSim = true` 那条分支）。
 *
 * ## 为什么它只存在于 test 源集
 * 生产侧的音频来自真实 `AudioFrame`（K7 接线）；把"假节拍"带进 main 源集就是留了一条
 * "接了根死线还自测全绿"的路。但它必须与原型逐位一致，否则基线里 `*Dust` / `*World`
 * 两组时序探针（都是在 `audioSim` 驱动的时间轴上采的样）对不上。
 *
 * ⚠️ 只取 `worldState` 真正消费的三条：`bass / energy / treble`（原型的 `mid / beat / pulse`
 * 在 E47 里没有任何消费方 ⇒ ⛔ 为了"照抄"而留两个死字段）。
 */
internal object SnowSimAudio {
    var bass = 0.0
        private set
    var energy = 0.0
        private set
    var treble = 0.0
        private set

    /** 一帧一次：`t` 秒 → 三条包络（`clamp(...,0,1)` 与原型同口径）。 */
    fun sim(t: Double) {
        bass = SnowCedarMath.clamp(0.34 + 0.26 * sin(SnowCedarMath.TAU * t / 1.9) + 0.12 * sin(SnowCedarMath.TAU * t / 0.47), 0.0, 1.0)
        treble = SnowCedarMath.clamp(0.26 + 0.20 * sin(SnowCedarMath.TAU * t / 3.6 + 2.2) + 0.10 * sin(SnowCedarMath.TAU * t / 0.83), 0.0, 1.0)
        energy = SnowCedarMath.clamp(0.32 + 0.20 * sin(SnowCedarMath.TAU * t / 6.2 + 0.4), 0.0, 1.0)
    }

    /**
     * 原型的逐帧次序（`audioSim → smoothAudio → worldState → stepPerson`），
     * 黄金测试与行为测试都走这一条，⛔ 两处各排一遍顺序 —— 顺序一变，采样点就错位。
     */
    fun frame(audio: SnowAudio, world: SnowWorldState, dust: SnowPersonDust, t: Double, dt: Double) {
        sim(t)
        audio.update(bass, energy, treble, dt)
        world.state(t, audio.bass, audio.energy, audio.treble)
        dust.step(dt, world.gust)
    }

    /** 与原型同一个 `isFinite` 角色：肩雪必须是**有限**的 [0,1]。 */
    fun bounded(v: Double): Boolean = !v.isNaN() && !v.isInfinite() && v >= 0.0 && v <= 1.0
}

/**
 * 原型 T11 的两条**结构**量（读 `PERSON` 表本身，⛔ 从渲染结果反推）。
 * 与判据一起放在测试侧：生产代码不需要它们。
 */
internal object SnowSilProbe {
    /** 折线 (pts, ws) 在高度 `y` 处的**外沿** x（中心 + 半宽，线性插值）。 */
    fun edgeAt(pts: Array<DoubleArray>, ws: DoubleArray, y: Double): Double {
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val lo = minOf(a[1], b[1])
            val hi = maxOf(a[1], b[1])
            if (y >= lo && y <= hi) {
                val f = (y - a[1]) / (if ((b[1] - a[1]) != 0.0) b[1] - a[1] else 1.0)
                return (a[0] + (b[0] - a[0]) * f) + (ws[i] + (ws[i + 1] - ws[i]) * f)
            }
        }
        return Double.NEGATIVE_INFINITY
    }

    /** 肘部处"手臂外沿 − 衣身外沿"：≥0.015 才读得出胳膊挂在大衣**外面**。 */
    fun armOut(): Double {
        val elbowY = SnowPerson.STAND.aR[1][1]
        return edgeAt(SnowPerson.STAND.aR, SnowPerson.ARM_W, elbowY) -
            edgeAt(SnowPerson.coatSpine(SnowPerson.STAND), SnowPerson.COAT_W, elbowY)
    }

    /** 可见颈长 = 肩线上沿中关节（领根）的 y − 下巴 y。 */
    fun neckLen(): Double = SnowPerson.shoulderTop(SnowPerson.STAND)[2][1] - SnowPerson.CHIN

    /** 判据用到的四个小闸门（与原型 `waistOK / neckOK / armOK / headOK` 逐字同式）。 */
    fun waistOK(waist: Double, hem: Double, shoulder: Double): Boolean =
        waist <= shoulder * 0.85 && hem > waist

    fun neckOK(nlen: Double, collar: Double, shoulder: Double): Boolean =
        nlen >= 0.018 && collar <= shoulder * 0.30

    fun armOK(o: Double): Boolean = o >= 0.015

    fun headOK(chin: Double): Boolean = chin <= 0.150

    /** 一根伸到身体右侧外侧的长条 ⇒ 只有杖满足。 */
    fun caneOK(bars: List<SnowSilBar>): Boolean = bars.any { it.x1 >= 0.160 && it.h >= 0.40 }

    /** 宽占比下限由**点表**推（⛔ 抄测量值）：声明轮廓宽 0.333 的 90% = 0.30。 */
    fun widthOK(w: Double): Boolean = w >= 0.30

    /** 部件**绝对**半宽下限（单位 hh）：解剖肩 / 大腿 / 上臂 / 靴 ⇒ 火柴人的真正克星。 */
    fun anatomyOK(sh: Double, th: Double, arm: Double, boot: Double): Boolean =
        sh >= 0.095 && th >= 0.038 && arm >= 0.022 && boot >= 0.028
}
