package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.visualizer.fx.FxLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * ============================================================================
 *  E49「鸟群」纯数学层（K2）—— 方案 docs/archive/e49-birds-flock-plan.md §四 / §11.1
 *
 *  ⛔ 零 Compose / Android 依赖（`BirdsFlockMathTest` 的结构前提）。
 *  阈值与常量的唯一来源 = 浏览器原型 `docs/archive/e49-birds-flock-preview.html`
 *  的 harness 实测（§10.2 归档），⛔ 不从方案正文抄数。
 * ==========================================================================
 */
internal object BirdsFlockMath {

    // ── 容量（预分配上限；档位只决定"用多少"，⛔ 不决定"分配多少"）──

    /** 仿真鸟数上限 = HIGH 档 600（§6.1，2026-10-15 G 轮下调后的档位表）。 */
    const val MAX_BIRDS = 600

    /** 树数上限（原型 `rebuildLayout` 生成 3..5 棵）。 */
    const val MAX_TREES = 8

    /** 栖点容量上限 = HIGH 档 perchCap 150（再留余量取 160）。 */
    const val MAX_PERCH = 160

    /** curl 风场项数（原型实证 `NWIND = 4`，§4.8.2）。 */
    const val NWIND = 4

    // ── 档位表（原型 `TIERS`，§6.1）──

    const val TIER_LOW = 0
    const val TIER_MEDIUM = 1
    const val TIER_HIGH = 2

    /** 三档鸟数（§6.1：LOW 200 / MEDIUM 400 / HIGH 600）。 */
    val TIER_BIRDS = intArrayOf(200, 400, 600)

    /** 三档同时在栖上限（原型 `TIERS.perchCap`）。 */
    val TIER_PERCH_CAP = intArrayOf(30, 80, 150)

    /** 三档空间哈希逐格容量（原型 `TIERS.gridCellCap`；⚠️ 只防格子病态膨胀，R7 的双判据归单测）。 */
    val TIER_GRID_CELL_CAP = intArrayOf(14, 16, 18)

    /** `FxLevel → 档位下标`（与 `SnowCedarRenderer.snowLevelOf` 同一条一次性映射；OFF → LOW，雪/鸟是主体，低档砍数量不关效果）。 */
    fun tierOf(level: FxLevel): Int = when (level) {
        FxLevel.OFF -> TIER_LOW
        FxLevel.LITE -> TIER_MEDIUM
        FxLevel.FULL -> TIER_HIGH
    }

    // ── 固定时间步（§5.8）──

    const val FIXED_DT = 1f / 60f
    const val DT_CLAMP = 0.10f
    const val MAX_SUBSTEPS = 3

    // ── 基础纯函数 ──

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    /** 环绕到 [0, period)：双重取模不能省（Kotlin/JS 的 `%` 对负被除数返回负余数，E47 同一条教训）。 */
    fun wrap(v: Float, period: Float): Float {
        val r = v % period
        return if (r < 0f) r + period else r
    }

    /** 环绕到 [0, 1)。 */
    fun wrap01(v: Float): Float {
        val r = v % 1f
        return if (r < 0f) r + 1f else r
    }

    /**
     * `normalize` 的零向量守卫（§5.7 红线）：`|v|² < eps` 时早退返回零向量。
     * ⛔ 一个 NaN 会在一帧内毒化全群（NaN 传播到邻居距离 ⇒ 邻接集变空 ⇒ 净力 NaN ⇒ 整群崩）。
     */
    fun safeNormalize(x: Float, y: Float, out: FloatArray) {
        val m2 = x * x + y * y
        // ⛔ 判据必须是 `!(m2 >= eps)` 而不是 `m2 < eps`：NaN 的任何比较都不成立，
        // `m2 < eps` 会漏掉 NaN 输入（NaN 毒化全群正是本守卫要掐断的路径）。
        if (!(m2 >= 1e-12f)) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val inv = 1f / sqrt(m2)
        out[0] = x * inv
        out[1] = y * inv
    }

    /**
     * 有界分离守卫因子 `(1 − d/R)^n`，**原型实证 n = 2**（§4.2）：
     * `d = R` 处为 0、`d → 0` 处 → 1 ⇒ 与 `1/d` 相乘后处处有界（boids 爆炸的头号来源被掐断）。
     */
    fun sepGuard(d: Float, r: Float): Float {
        if (r <= 0f) return 0f
        val rd = d / r
        if (rd >= 1f) return 0f
        val g = 1f - rd
        return g * g
    }

    // ── 侧倾（§4.9）──

    /** 倾角目标 `φ_target = atan2(a_lat, g0·spdN)`，钳 ±45°（T9 的上限）。 */
    fun bankTarget(aLat: Float, g0: Float, spdN: Float): Float =
        atan2(aLat, g0 * spdN).coerceIn(-PI.toFloat() / 4f, PI.toFloat() / 4f)

    /**
     * 侧倾指数趋近：`|φ_target| > |φ|`（滚入）用 `τ = 0.07`，否则（滚出）用 `τ = 0.14`
     * ⇒ **滚入比滚出快 = 2:1**（§3.3 StarDisplay 的侧倾波结论）。
     * ⛔ 不实现"侧倾导致掉高度"（剪影下会读成鱼群，§3.3/O4 默认裁决）。
     */
    fun bankStep(bank: Float, target: Float, dt: Float): Float {
        val tau = if (abs(target) > abs(bank)) 0.07f else 0.14f
        return bank + (target - bank) * (1f - exp(-dt / tau))
    }

    /** JS 语义的相位差折返到 (−π, π]（扑动弱同步 Kuramoto 用，§4.10）。 */
    fun angleWrapPi(dph: Float): Float {
        val twoPi = 2f * PI.toFloat()
        var r = (dph + PI.toFloat()) % twoPi
        if (r < 0f) r += twoPi
        return r - PI.toFloat()
    }

    /**
     * HSL → RGB（原型用 `hsl(h,s%,l%)` 字符串；Kotlin 侧烘焙色值统一走这里）。
     * 返回 `0xRRGGBB`（不带 α；α 由调用方按 `argbOf` 拼）。
     */
    fun hslRgb(hDeg: Float, sPct: Float, lPct: Float): Int {
        val h = (hDeg % 360f + 360f) % 360f / 360f
        val s = clamp(sPct, 0f, 100f) / 100f
        val l = clamp(lPct, 0f, 100f) / 100f
        if (s <= 0f) {
            val g = (l * 255f + 0.5f).toInt()
            return (g shl 16) or (g shl 8) or g
        }
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        val r = hueToRgb(p, q, h + 1f / 3f)
        val g = hueToRgb(p, q, h)
        val b = hueToRgb(p, q, h - 1f / 3f)
        return ((r * 255f + 0.5f).toInt() shl 16) or
            ((g * 255f + 0.5f).toInt() shl 8) or
            (b * 255f + 0.5f).toInt()
    }

    private fun hueToRgb(p: Float, q: Float, tIn: Float): Float {
        var t = tIn
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** `0xRRGGBB` + α(0..1) → ARGB。 */
    fun argbOf(a: Float, rgb: Int): Int =
        (clamp(a, 0f, 1f) * 255f + 0.5f).toInt() shl 24 or (rgb and 0x00FFFFFF)
}

/**
 * 种子 RNG（mulberry32 的逐字移植；原型 ⛔ 禁止 `Math.random`，同一次运行逐位可复现）。
 * Kotlin `Int` 乘法自然回绕 ⇒ 与 JS `Math.imul` 语义一致。
 */
internal class BfRng(seed: Int) {
    private var s: Int = seed

    fun next(): Float {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        val u = (t xor (t ushr 14)).toLong() and 0xFFFFFFFFL
        return u * 2.3283064365386963e-10f
    }
}
