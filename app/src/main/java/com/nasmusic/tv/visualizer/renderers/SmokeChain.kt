package com.nasmusic.tv.visualizer.renderers

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.pow

/**
 * E48「大漠 · 孤烟」K4 —— 原型 §3「孤烟 = 烟丝链」的 1:1 移植。
 *
 * ## 四条不可动的结构纪律
 *
 * 1. **回绕守恒**：`u ≥ 1 ⇒ 减 1 并 lap+1`，⛔ 新建/销毁粒子（T3 判这条）。
 *    全体**等速**推进 —— 抖动只住在槽位偏移 `of` 里，⛔ 逐粒速度差
 *    （速度差会随圈数累积成断档，把 T4 的"相邻必须互相埋住"从构造保证变成运气）。
 * 2. **行波**：相位随高度连续滞后（`−u·swayLag`），`ph` 只作**微量去相关**（×phJit）。
 *    ⛔ 逐粒随机相位：相邻两粒朝不同方向摆 ⇒ 柱子读成闪电/Z 字，与需求 ①「孤烟直」冲突。
 * 3. `puffR` / `smokeAlpha` 是 **u 的纯函数**（⛔ 不含 t ⇒ 逐帧可复现、⛔ 不是逐帧随机），
 *    且必须住在这里、⛔ 不住在绘制侧：`minOverlap`（T4）与 harness 都读它们，
 *    放逐帧里 = 收窄了半径而 T4 看不见 ⇒ "收窄到断珠"能悄悄溜过去（P13 同族）。
 * 4. 变体分配 `i + 2·lap`：相邻两粒的差 = `1 + 2Δlap`，模 6 **不可能为 0**
 *    ⇒ "相邻两粒永不贴同一张"是构造保证（T6）。
 */
internal class DesertSmokeChain {

    var n = 0
        private set

    /** 沿柱归一化高度 `∈[0,1)`。 */
    var u = DoubleArray(0)
        private set

    /** 去相关相位（弧度）。 */
    var ph = DoubleArray(0)
        private set

    /** 槽位偏移（±17% 槽宽）。 */
    var of = DoubleArray(0)
        private set

    /** 圈数（回绕计数；也是变体轮换与重新抖相位的自变量）。 */
    var lap = IntArray(0)
        private set

    /** 烟丝贴图变体号 `∈[0, variants)`。 */
    var vr = IntArray(0)
        private set

    /** 原型 `makeSmoke(n, seed)`。 */
    fun reset(puffs: Int, seed: Int, variants: Int) {
        val cnt = max(0, puffs)
        n = cnt
        if (cnt == 0) {
            u = DoubleArray(0); ph = DoubleArray(0); of = DoubleArray(0)
            lap = IntArray(0); vr = IntArray(0)
            return
        }
        u = DoubleArray(cnt)
        ph = DoubleArray(cnt)
        of = DoubleArray(cnt)
        lap = IntArray(cnt)
        vr = IntArray(cnt)
        val rnd = DesertMath.rng(seed xor 0x2f9a1)
        var i = 0
        while (i < cnt) {
            // ⭐ 抖动是**槽位偏移**（±17% 槽宽），⛔ 逐粒速度差。
            of[i] = (rnd.next() - 0.5) * 0.34
            u[i] = (i + 0.5 + of[i]) / max(1, cnt).toDouble()
            ph[i] = rnd.next() * DesertMath.TAU
            lap[i] = 0
            vr[i] = SmokeFormulas.variantOf(i, 0, variants)
            i++
        }
    }

    /**
     * 原型 `stepSmoke(st, dt, t)` —— **一次等速推进 + 回绕重抽**。
     *
     * @param risePx 本帧沿柱位移（像素；`W_.risePx`）
     * @param smokeH 柱高（像素；`L.smokeH`）
     */
    fun step(dt: Double, risePx: Double, smokeH: Double, variants: Int) {
        val vp = risePx * dt / max(1.0, smokeH)
        var i = 0
        while (i < n) {
            u[i] += vp
            if (u[i] >= 1.0) {
                u[i] -= 1.0
                lap[i] += 1
                // 回绕时按 hash2(i, lap) 重新抖相位/变体 ⇒ 同一格每圈长得不一样。
                ph[i] = DesertMath.hash2(i * 7 + 1, lap[i] * 13 + 5) * DesertMath.TAU
                vr[i] = SmokeFormulas.variantOf(i, lap[i], variants)
            }
            i++
        }
    }
}

/**
 * 孤烟的**纯函数**侧（原型的 `variantOf` / `swayRaw` / `swayPx` / `smokeAlpha` /
 * `puffR` / `puffTilt` / `minOverlap` / `maxTiltDeg` / `fpOf`）。
 *
 * ⭐ 全部是 `internal companion object` 形态的纯函数 ⇒ 单测能在 JVM 里**直接调生产函数**，
 * ⛔ 不在测试里复制一份算法（那必然漂移，E44 偏差 D24 的成因）。
 */
internal object SmokeFormulas {

    /** 变体分配：`i + 2·lap` —— 相邻两粒的差 = `1 + 2Δlap`，模 V **不可能为 0**。 */
    fun variantOf(i: Int, lap: Int, v: Int): Int {
        if (v <= 0) return 0
        return ((i + 2 * lap) % v + v) % v
    }

    /**
     * 摆动基波（两个不可通拍频叠加 ⇒ 峰值会逼近 1.45 但永不变周期）。
     *
     * ⭐ **行波**：相位随高度连续滞后（`−u·swayLag`），`ph` 只作**微量去相关**（×phJit）。
     */
    fun swayRaw(u: Double, t: Double, ph: Double, sp: DesertSmokeP): Double {
        val j = ph * sp.phJit
        val p1 = u.pow(1.7)
        return p1 * (sin(DesertMath.TAU * t * sp.swayHz1 - u * sp.swayLag1 + j) +
            0.45 * sin(DesertMath.TAU * t * sp.swayHz2 - u * sp.swayLag2 + j * 0.61 + 1.7))
    }

    private fun sin(v: Double): Double = DesertMath.sinD(v)

    /** 摆幅像素 = `amp × W × swayMul × swayRaw`（原型 `swayPx`）。 */
    fun swayPx(u: Double, t: Double, ph: Double, mul: Double, sp: DesertSmokeP, w: Double): Double =
        sp.amp * w * mul * swayRaw(u, t, ph, sp)

    /**
     * 浓度包络：**起来 → 平台 → 末段收**（根部 0→满，`u∈[0.05,0.82]` 维持满密度，最后 18% 散尽）。
     *
     * ⭐ B1：原先是 `(1−u)^1.30` 的**单调衰减** —— 实测柱顶相对局部背景只有 +7 L、
     * 柱中段 +24 L ⇒ 「直上」这半个核心读感根本没成立（T7 全绿也照样不成立：
     * T7 管形状，⛔ 不管"看得见多少"）。改成平台段后全柱满密度，只有末段收。
     */
    fun smokeAlpha(u: Double, sp: DesertSmokeP): Double {
        val uc = DesertMath.clamp(u, 0.0, 1.0)
        val fin = DesertMath.clamp(uc / 0.05, 0.0, 1.0).pow(0.7)
        // 平台段：满密度撑到 u=0.82；`0.18·smoothstep` 是一条**极缓的**整体收薄
        // （单峰仍是单峰，因为它在升段之后**单调下降**，⛔ 不制造第二个峰）。
        val hold = (1.0 - DesertMath.smoothstep(0.82, 1.00, uc)) *
            (1.0 - 0.18 * DesertMath.smoothstep(0.05, 0.95, uc))
        return sp.alphaK * 0.1 * fin * hold
    }

    /**
     * puff 半径：随柱高**呼吸**（`u` 的纯函数）。
     *
     * ⭐ B1：等宽 + 硬边是"光柱"最强的两个信号；真实烟柱的轮廓沿高度**收、放、再收**。
     * ⚠️ 幅度 0.11/0.05：T4 的 min 重叠随最窄半径**线性**下降（0.11/0.05 ⇒ 最窄 0.872×，
     * T4 ≈ 2.28，闸门 1.8 有 27% 余量）⇒ 轮廓起伏要跟 T4 的余量一起买。
     */
    fun puffR(u: Double, sp: DesertSmokeP, h: Double): Double {
        val base = sp.r0 * h * (1.0 + sp.grow * DesertMath.clamp(u, 0.0, 1.0).pow(1.25))
        return base * (1.0 + 0.11 * sin(u * 11.3 + 1.7) + 0.05 * sin(u * 23.7 + 0.4))
    }

    /** 柱切线倾角：`dx/du ÷ dy/du`（`dy/du = −smokeH`）⇒ 烟丝跟着柱子弯，⛔ 竖直贴。 */
    fun puffTilt(u: Double, t: Double, ph: Double, mul: Double, sp: DesertSmokeP, w: Double, smokeH: Double): Double {
        val du = 0.02
        val a = swayPx(minOf(1.0, u + du), t, ph, mul, sp, w)
        val b = swayPx(maxOf(0.0, u - du), t, ph, mul, sp, w)
        return atan2((a - b) / (2.0 * du), smokeH)
    }

    /**
     * 相邻两粒沿**柱轴**的重叠率 = `(纵向半轴_i + 纵向半轴_{i+1}) ÷ 间距`，
     * 取可见段 `u∈[0.08,0.85]` 的最小值（原型 `minOverlap`；T4 判据）。
     *
     * ⚠️ 半轴必须用 `r × elong`（烟丝是纵 stretch 的丝，⛔ 拿横向半径量横向 = 把细烟误判成断珠）。
     *
     * @param h       **画布高**（原型闭包里的 `H`）—— `puffR` 的 `r0 × h` 吃它，
     *                ⛔ 不是 `w`：把宽传进来 puff 会大 1.78×，重叠率跟着虚高
     *                （实测 4.22 vs 原型 2.28），"糖葫芦" 回归会被这张闸门漏过去。
     * @param smokeH 烟柱可见段高度（`layout.smokeH`），间距 `Δu × smokeH` 的分母。
     */
    fun minOverlap(st: DesertSmokeChain, sp: DesertSmokeP, h: Double, smokeH: Double): Double {
        val idx = IntArray(st.n)
        var cnt = 0
        var i = 0
        while (i < st.n) {
            if (st.u[i] >= 0.08 && st.u[i] <= 0.85) idx[cnt++] = i
            i++
        }
        // 按 u 升序（n ≤ 26 ⇒ 插入排序足够，⛔ 引入集合/比较器分配）
        var a = 1
        while (a < cnt) {
            val key = idx[a]
            val ku = st.u[key]
            var b = a - 1
            while (b >= 0 && st.u[idx[b]] > ku) {
                idx[b + 1] = idx[b]
                b--
            }
            idx[b + 1] = key
            a++
        }
        var mn = 1e9
        var k = 1
        while (k < cnt) {
            val p = idx[k - 1]
            val q = idx[k]
            val gap = (st.u[q] - st.u[p]) * smokeH
            if (gap <= 0.0) {
                mn = 0.0
            } else {
                mn = minOf(mn, sp.elong * (puffR(st.u[p], sp, h) + puffR(st.u[q], sp, h)) / gap)
            }
            k++
        }
        return mn
    }

    /**
     * 理论最大切线倾角（音频取饱和 1.5 倍摆幅的最坏值；原型 `maxTiltDeg`）。
     *
     * ⚠️ 阈值 = 「直」的量化：最坏帧柱切线倾角 ≤ 6° ⇒ 一眼读成"直上"而非"飘带"。
     */
    fun maxTiltDeg(sp: DesertSmokeP, w: Double, smokeH: Double, mul: Double): Double {
        var mx = 0.0
        val du = 0.01
        var t = 0.0
        while (t <= 180.0) {
            var u = 0.05
            while (u <= 1.0001) {
                val d = kotlin.math.abs(
                    swayPx(minOf(1.0, u + du), t, 0.0, mul, sp, w) -
                        swayPx(maxOf(0.0, u - du), t, 0.0, mul, sp, w),
                ) / (2.0 * du)
                mx = maxOf(mx, kotlin.math.abs(atan2(d, smokeH)))
                u += 0.05
            }
            t += 0.25
        }
        return mx / DesertMath.D2R
    }

    /**
     * 烟丝链指纹（原型 `fpOf`）：FNV-1a over `(u, ph, vr, lap)`。
     *
     * ⚠️ `Math.round(v·1e6)|0` 的**取整**必须与原型一致 —— 直接混 Double 的位模式
     * 会让"同种子逐位复现"这条判据变成平台相关的运气。
     */
    fun fpOf(st: DesertSmokeChain): Int {
        var h = -2128831035          // 2166136261 as signed 32-bit
        fun mix(v: Double) {
            val q = DesertMath.jsRound(v * 1e6).toInt()
            h = (h xor q) * 16777619
        }
        var i = 0
        while (i < st.n) {
            mix(st.u[i]); mix(st.ph[i]); mix(st.vr[i].toDouble()); mix(st.lap[i].toDouble())
            i++
        }
        return h
    }
}
