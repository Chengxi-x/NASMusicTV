package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * E48「大漠 · 孤烟」门禁 —— 原型 §11.3 K2/K4/K7 的 **T1–T12 / T14 同口径移植** +
 * K2 的「脊线口径唯一性」源码闸门。
 *
 * ## 判据来源
 * 全部判定直调**生产函数**（⛔ 不在测试侧复制算法）：
 * [SmokeFormulas]（摆动 / 半径 / 包络 / 重叠 / 倾角 / 指纹）、
 * [DesertLayout]（`layout` / `crest` / `duneExposures` / `sunCutFraction`）、
 * [DesertSmokeChain]（`reset` / `step`）、[DesertAudio] / [DesertWorldState]（`simSmoke` 全管线）。
 *
 * ## ⛔ T13 为什么不在这
 * T13「计量自洽（ΣPROF≡OPS、Σfill≡FILL）」判的是**原型自带的性能计数器账本**
 * （`PROF` 分类账 vs `OPS`/`FILL` 总账）。方案 P21 已明文：它**只保证两套账自洽**，
 * ⛔ 不保证两侧口径一致 ⇒ 而对生产渲染器而言，那套计数器是**调试 instrumentation**，
 * ⛔ 不该进 `app/src/main`（会变成逐帧真开销）。故 T13 无生产对应物，
 * **不在本类伪造一条**（伪造 = 自己骗自己）。两侧口径一致由 `PerfBudgetContractTest`
 * 的 `DrawScope` 可达行门禁在 Kotlin 侧承担。
 *
 * ## ⛔ T7 的负样本不照抄原型
 * 原型 P23/§八之二ⓔ：原负样本 `noTop = 0.30` 是**写死字面量**，从不调 `smokeAlpha`
 * ⇒ 永远不会红（空壳）。本类改用**同一份谓词**做判别自证：
 * `α(u) ≤ 1e-9` 这条判据在 `u = 0.5 / 0.81` 上必须判红。
 * （末段收只要被删掉，`α(1)` 从 0 变成 `alphaK·0.1·0.82 ≈ 0.119` ⇒ 立刻越界。）
 *
 * ## 三条不可破坏的纪律
 * 1. ⛔ **绝不构造 `DesertSmokeRenderer()`** —— 字段初始化会建 `android.graphics.Path` /
 *    `Paint` / `Bitmap` ⇒ JVM 单测抛 "not mocked"（同 `StarrySkyTest` 的纪律）。
 * 2. ⛔ 源码判据一律**先剥注释**：本文件与生产文件的 KDoc 里**故意**举了
 *    `groupTopCrest(carLayer, x)` / `ridgeAt(` 等反例，不剥注释会假 FAIL。
 * 3. ⛔ 负样本必须**真的能红**，且尽量与正向**喂同一份谓词**（缺一条就可能空转）。
 */
class DesertSmokeGateTest {

    private val w = 1600.0
    private val h = 900.0

    // ─────────────────────── 原型 simSmoke：audio → world → smoke 全管线 ───────────────────────

    /**
     * 原型 `simSmoke(sec, dt, seed, n?)`。⚠️ 帧数取整必须与原型同式
     * （`Math.round(sec / dt)`）——差一帧整条链的指纹就不同。
     */
    private fun simSmoke(
        sec: Double,
        dt: Double,
        seed: Int,
        puffs: Int? = null,
    ): Triple<DesertSmokeChain, DesertAudio, DesertWorldState> {
        val p = DesertP()
        val lay = DesertLayout()
        lay.layout(w, h, p)
        val au = DesertAudio(p)
        val wo = DesertWorldState(p, lay)
        val st = DesertSmokeChain()
        st.reset(puffs ?: p.smoke.puffs, seed, p.smoke.variants)
        au.reset()
        var t = 0.0
        var i = 0
        val n = Math.round(sec / dt).toInt()
        while (i < n) {
            t += dt
            au.update(0.0, 0.0, 0.0, 0.0, 0.0, dt)
            wo.state(t, au.bass, au.mid, au.treble, au.energy, au.section)
            st.step(dt, wo.risePx, lay.smokeH, p.smoke.variants)
            i++
        }
        return Triple(st, au, wo)
    }

    private fun freshLayout(): Pair<DesertP, DesertLayout> {
        val p = DesertP()
        val lay = DesertLayout()
        lay.layout(w, h, p)
        return p to lay
    }

    // ─────────────────────── ① 烟丝链（K4） ───────────────────────

    /** T1：同种子两次模拟必须逐位同；换种子必须不同（RNG 真的吃种子）。 */
    @Test
    fun t1_chainBitExactAcrossRuns() {
        val p = DesertP()
        val f1 = SmokeFormulas.fpOf(simSmoke(12.0, 1.0 / 60.0, p.smoke.seed).first)
        val f2 = SmokeFormulas.fpOf(simSmoke(12.0, 1.0 / 60.0, p.smoke.seed).first)
        val f3 = SmokeFormulas.fpOf(simSmoke(12.0, 1.0 / 60.0, p.smoke.seed + 1).first)
        assertTrue("同 seed 两次 12 s 必须逐位同（$f1 vs $f2）", f1 == f2)
        assertTrue("seed+1 必须给出不同指纹（RNG 吃种子）：$f1 vs $f3", f1 != f3)
    }

    /** T2：行波摆动有界（≤0.026W）且真的到得了理论峰值 1.45。 */
    @Test
    fun t2_swayBoundedAndReachesPeak() {
        val (p, _) = freshLayout()
        var rawMax = 0.0
        var t = 0.0
        while (t <= 180.0) {
            rawMax = max(rawMax, abs(SmokeFormulas.swayRaw(1.0, t, 0.0, p.smoke)))
            t += 1.0 / 15.0
        }
        val cap = 0.026
        val worst = p.smoke.amp * 1.45 * 1.5
        val worstBad = p.smoke.amp * 2 * 1.45 * 1.5
        assertTrue("行波必须到得了理论峰值 1.45（实测 $rawMax）", rawMax >= 1.40)
        assertTrue("音频摆幅饱和 1.5× 的最坏位移 $worst W 必须 ≤ $cap", worst <= cap)
        assertTrue("负样本：amp×2 ⇒ $worstBad W 必须越界（否则闸门没牙）", worstBad > cap)
    }

    /** T3：烟丝数守恒（回绕不新建不销毁），u 恒在 [0,1)。 */
    @Test
    fun t3_chainConservesPuffs() {
        val p = DesertP()
        val s = simSmoke(20.0, 1.0 / 60.0, p.smoke.seed).first
        assertTrue("20 s 后烟丝数必须守恒（恒 ${p.smoke.puffs}，实测 ${s.n}）", s.n == p.smoke.puffs)
        var viol = 0
        var i = 0
        while (i < s.n) {
            val u = s.u[i]
            if (!(u >= 0.0 && u < 1.0) || !u.isFinite()) viol++
            i++
        }
        assertTrue("20 s 后越界 / NaN 的 u 必须为 0（实测 $viol）", viol == 0)
        // 负样本：人为挪出一粒 ⇒ 同一份谓词必须检出恰 1
        val broken = DesertSmokeChain()
        broken.reset(p.smoke.puffs, p.smoke.seed, p.smoke.variants)
        broken.u[3] = 1.73
        var violBad = 0
        i = 0
        while (i < broken.n) {
            val u = broken.u[i]
            if (!(u >= 0.0 && u < 1.0) || !u.isFinite()) violBad++
            i++
        }
        assertTrue("负样本：u[3]=1.73 必须被检出恰 1 次（实测 $violBad）", violBad == 1)
    }

    /** T4：可见段内相邻烟丝必须互相埋住（⛔ 一串糖葫芦）。 */
    @Test
    fun t4_adjacentWispsOverlap() {
        val p = DesertP()
        val (_, lay) = freshLayout()
        val ov = SmokeFormulas.minOverlap(simSmoke(20.0, 1.0 / 60.0, p.smoke.seed).first, p.smoke, h, lay.smokeH)
        val thin = SmokeFormulas.minOverlap(
            simSmoke(20.0, 1.0 / 60.0, p.smoke.seed, max(3, (p.smoke.puffs * 0.55).toInt())).first,
            p.smoke, h, lay.smokeH,
        )
        assertTrue("相邻烟丝必须互相埋住：minOverlap = $ov ≥ 1.8", ov >= 1.8)
        assertTrue("负样本：粒数砍到 0.55× ⇒ $thin 必须 < 1.8（否则糖葫芦回归抓不住）", thin < 1.8)
    }

    /**
     * T5：烟丝自身各向异性（`elong ≥ 1.6`，⛔ 用圆画烟）+ 柱切线倾角有界（≤6°）。
     *
     * ⚠️ 阈值 = 「直」的量化；⛔ 为了让判据变绿而放宽（那是把闸门拆了）。
     */
    @Test
    fun t5_anisotropyAndTiltBounded() {
        val (p, lay) = freshLayout()
        val tiltMax = 6.0
        val tl = SmokeFormulas.maxTiltDeg(p.smoke, w, lay.smokeH, 1.5)
        val tlDouble = SmokeFormulas.maxTiltDeg(p.smoke, w, lay.smokeH, 3.0)
        assertTrue("烟丝必须纵 stretch：elong = ${p.smoke.elong} ≥ 1.6", p.smoke.elong >= 1.6)
        assertTrue("最坏倾角 $tl° ≤ $tiltMax°（音频摆幅饱和 1.5×）", tl <= tiltMax)
        assertTrue("负样本：amp×2 ⇒ $tlDouble° 必须越界", tlDouble > tiltMax)
        // 负样本：elong 退回 1.0（用圆画烟）⇒ 喂同一条谓词必须判红
        val save = p.smoke.elong
        p.smoke.elong = 1.0
        try {
            assertTrue("负样本：elong = 1.0 必须被同谓词判红", !(p.smoke.elong >= 1.6))
        } finally {
            p.smoke.elong = save
        }
    }

    /** T6：相邻两粒永不贴同一张 + 每格随圈数换贴纸（否则读成"重复贴图"）。 */
    @Test
    fun t6_variantDiversity() {
        val p = DesertP()
        val s = simSmoke(60.0, 1.0 / 60.0, p.smoke.seed).first
        var adj = 0
        var stuck = 0
        val used = HashSet<Int>()
        var i = 0
        while (i < s.n) {
            if (i + 1 < s.n && s.vr[i] == s.vr[i + 1]) adj++
            used.add(s.vr[i])
            if (s.lap[i] == 0) stuck++
            i++
        }
        assertTrue("相邻两粒永不贴同一张（实测同贴 $adj）", adj == 0)
        assertTrue("至少用上 3 种变体（实测 ${used.size}/${p.smoke.variants}，未回绕格 $stuck）", used.size >= 3)
        // 负样本：全部钉死同一张 ⇒ 同谓词检出
        val allSame = run {
            val c = DesertSmokeChain()
            c.reset(p.smoke.puffs, p.smoke.seed, p.smoke.variants)
            var a = 0
            var k = 0
            while (k + 1 < c.n) {
                c.vr[k] = 0
                if (c.vr[k] == c.vr[k + 1]) a++
                k++
            }
            a
        }
        assertTrue("负样本：全部钉死同一张 ⇒ 相邻同贴 $allSame 必须 > 0", allSame > 0)
    }

    /**
     * T7：浓度包络 —— 两端归零、单峰、峰位在根部。
     *
     * ⛔ **不照抄原型的硬编码负样本**（P23：`noTop = 0.30` 从不调 `smokeAlpha` ⇒ 永不远红）。
     * 这里用**同一份谓词**做判别自证：`α(u) ≤ 1e-9` 在 `u = 0.5 / 0.81` 上必须判红
     * ⇒ 「末段收」真的存在（删掉它 `α(1)` 会跳到 ≈0.119，立刻越界）。
     */
    @Test
    fun t7_concentrationEnvelope() {
        val p = DesertP()
        var peak = 0.0
        var pk = 0.0
        var prevSlope = 0
        var turns = 0
        var u = 0.0
        while (u <= 1.0001) {
            val a = SmokeFormulas.smokeAlpha(u, p.smoke)
            if (a > peak) {
                peak = a
                pk = u
            }
            if (u > 0.006) {
                val prev = SmokeFormulas.smokeAlpha(u - 0.005, p.smoke)
                val sl = if (a > prev) 1 else if (a < prev) -1 else 0
                if (sl != 0 && prevSlope != 0 && sl != prevSlope) turns++
                if (sl != 0) prevSlope = sl
            }
            u += 0.005
        }
        val a0 = SmokeFormulas.smokeAlpha(0.0, p.smoke)
        val a1 = SmokeFormulas.smokeAlpha(1.0, p.smoke)
        assertTrue("根部必须归零：α(0) = $a0", a0 <= 1e-9)
        assertTrue("末段必须收尽：α(1) = $a1", a1 <= 1e-9)
        assertTrue("峰位必须在根部附近：u = $pk ∈ [0.04, 0.16]", pk >= 0.04 && pk <= 0.16)
        assertTrue("包络最多一个转折（实测 $turns）", turns <= 1)
        // 牙齿自证：同谓词在中段 / 平台末必须判红
        assertTrue(
            "牙齿：α(0.5) = ${SmokeFormulas.smokeAlpha(0.5, p.smoke)} 必须 > 1e-9（否则谓词空转）",
            SmokeFormulas.smokeAlpha(0.5, p.smoke) > 1e-9,
        )
        assertTrue("牙齿：α(0.81) 仍在平台段 ⇒ 必须 > 1e-9", SmokeFormulas.smokeAlpha(0.81, p.smoke) > 1e-9)
    }

    /** T8：暖边必须落在朝日一侧，且只给下半段（⛔ 背光侧也暖 = 假光源）。 */
    @Test
    fun t8_warmRimOnSunSideOnlyLower() {
        val (p, lay) = freshLayout()
        val t8 = 12.3
        val s = simSmoke(12.0, 1.0 / 60.0, p.smoke.seed).first
        var cx = 0.0
        var rx = 0.0
        var cn = 0
        var rn = 0
        var rmax = 0.0
        var rsum = 0.0
        var i = 0
        while (i < s.n) {
            val uu = s.u[i]
            if (uu <= 0.02) {
                i++
                continue
            }
            val x = lay.smokeX + SmokeFormulas.swayPx(uu, t8, s.ph[i], 0.50, p.smoke, w)
            val r = SmokeFormulas.puffR(uu, p.smoke, h)
            cx += x
            cn++
            if (uu < p.smoke.rimMax) {
                val side = if (lay.sunX - x > 0) 1.0 else -1.0
                rx += x + side * r * p.smoke.rimOff
                rn++
                rmax = max(rmax, uu)
                rsum += r
            }
            i++
        }
        val meanC = cx / max(1, cn)
        val meanR = rx / max(1, rn)
        val sgn = if (meanC - lay.sunX > 0) 1.0 else if (meanC - lay.sunX < 0) -1.0 else 1.0
        val delta = (meanR - meanC) * sgn
        val bound = -0.15 * (rsum / max(1, rn)) * p.smoke.rimOff
        assertTrue("暖边必须落在朝日一侧：Δ·sign = $delta ≤ $bound", delta <= bound)
        assertTrue("暖边只给下半段：最高 u = $rmax ≤ rimMax = ${p.smoke.rimMax}", rmax <= p.smoke.rimMax + 1e-9)
        assertTrue("负样本：镜像符号写反 ⇒ ${-delta} 必须与 $delta 异号", -delta > delta)
    }

    /** T11：无声零漂移但**基线摆动必须活着**；有声时映射必须真的动。 */
    @Test
    fun t11_silentZeroDriftButAlive() {
        val p = DesertP()
        val (_, lay) = freshLayout()
        val r = simSmoke(15.0, 1.0 / 60.0, p.smoke.seed)
        val au = r.second
        val wo = r.third
        val drift = maxOf(abs(au.bass), abs(au.energy), abs(au.treble), abs(au.mid))
        val mulSilent = wo.swayMul
        assertTrue("无声 15 s 后四通道必须零漂移（实测 $drift）", drift <= 1e-12)
        assertTrue("无声 swayMul 必须停在时间基线 0.50（实测 $mulSilent）", abs(mulSilent - 0.50) < 1e-12)
        assertTrue(
            "无声 risePx 必须是 rise×H×0.55 的纯时间基线（实测 ${wo.risePx}）",
            abs(wo.risePx - p.smoke.rise * h * 0.55) < 1e-9,
        )
        // 基线摆幅：无声也必须活着（⛔ 画面定格）
        var swMin = Double.MAX_VALUE
        var swMax = -Double.MAX_VALUE
        var t = 0.0
        var k = 0
        while (k < 900) {
            t += 1.0 / 60.0
            val v = SmokeFormulas.swayPx(1.0, t, 0.0, mulSilent, p.smoke, w)
            swMin = min(swMin, v)
            swMax = max(swMax, v)
            k++
        }
        val alive = swMax - swMin
        val aliveFloor = 0.5 * p.smoke.amp * w
        assertTrue("无声基线摆幅必须 > $aliveFloor px（实测 $alive）", alive > aliveFloor)
        // 有声：能量打到 1.0 ⇒ 映射必须真的离开基线
        val wo2 = DesertWorldState(p, lay)
        val auL = DesertAudio(p)
        auL.reset()
        t = 0.0
        k = 0
        while (k < 90) {
            t += 1.0 / 60.0
            auL.update(1.0, 1.0, 1.0, 1.0, 1.0, 1.0 / 60.0)
            wo2.state(t, auL.bass, auL.mid, auL.treble, auL.energy, auL.section)
            k++
        }
        val kMax = abs(wo2.swayMul - mulSilent)
        assertTrue("有声 90 帧 swayMul 必须真的离开基线（实测 Δ = $kMax > 0.05）", kMax > 0.05)
        // 负样本：swayMul 退化成纯音频项 ⇒ 无声 mul = 0 ⇒ 喂同谓词必须判红
        var dMin = Double.MAX_VALUE
        var dMax = -Double.MAX_VALUE
        t = 0.0
        k = 0
        while (k < 900) {
            t += 1.0 / 60.0
            val v = SmokeFormulas.swayPx(1.0, t, 0.0, 0.0, p.smoke, w)
            dMin = min(dMin, v)
            dMax = max(dMax, v)
            k++
        }
        val dead = dMax - dMin
        assertTrue("负样本：swayMul 写成纯音频项 ⇒ 无声摆幅 $dead 必须 < $aliveFloor（否则抓不住定格）", dead < aliveFloor)
    }

    /** T12：远脊必须切掉日盘 8%–45% 直径（⛔ 悬空圆盘）。 */
    @Test
    fun t12_farRidgeCutsSunDisc() {
        val (p, lay) = freshLayout()
        val cut = lay.sunCutFraction()
        assertTrue("远脊必须切掉日盘 8%–45% 直径（实测 ${cut * 100}%）", cut >= 0.08 && cut <= 0.45)
        // 负样本：sunY 上抬 0.10 ⇒ 悬空圆盘。⚠️ 改完参数**必须重跑 layout**（原型 P14⑤）
        val save = p.comp.sunY
        p.comp.sunY = save - 0.10
        lay.layout(w, h, p)
        val floating = lay.sunCutFraction()
        p.comp.sunY = save
        lay.layout(w, h, p)
        assertTrue("负样本：sunY 上抬 0.10 ⇒ 切掉 ${floating * 100}% 必须 < 8%（悬空圆盘）", floating < 0.08)
    }

    /** T14：沙丘 ≥5 层且**每一层**都必须在某处露出 ≥0.012H（⛔ 三道线 / 白烧位图）。 */
    @Test
    fun t14_multiLayerDunes() {
        val (_, lay) = freshLayout()
        val minExp = h * 0.012
        val exp = lay.duneExposures()
        var hidden = 0
        var weakest = Double.MAX_VALUE
        var i = 0
        while (i < lay.dunes.size - 1) {
            if (exp[i] < weakest) weakest = exp[i]
            if (exp[i] < minExp) hidden++
            i++
        }
        assertTrue("沙丘层数必须 ≥5（实测 ${lay.dunes.size}）", lay.dunes.size >= 5)
        assertTrue("每一层都必须在某处露出 ≥ $minExp px（最弱 $weakest px，被盖掉 $hidden 层）", hidden == 0)
        // 负样本：把相邻两层钉成同一条脊（同 y 同形）⇒ 后一层被整条盖掉
        val a = lay.dunes[1]
        val b = lay.dunes[2]
        val ky = b.y
        val ka = b.amp
        val ka2 = b.amp2
        val kf1 = b.f1
        val kf2 = b.f2
        val ks = b.s
        val kph = b.ph
        b.y = a.y
        b.amp = a.amp
        b.amp2 = a.amp2
        b.f1 = a.f1
        b.f2 = a.f2
        b.s = a.s
        b.ph = a.ph
        val merged = lay.duneExposures()[1]
        b.y = ky
        b.amp = ka
        b.amp2 = ka2
        b.f1 = kf1
        b.f2 = kf2
        b.s = ks
        b.ph = kph
        assertTrue("负样本：两层钉成同一条脊 ⇒ 露出 $merged px 必须 < $minExp", merged < minExp)
    }

    // ─────────────────────── ② K2：脊线口径唯一性（源码闸门） ───────────────────────

    /**
     * K2 硬验收项：「烘焙、遮挡、自检同一口径」。
     *
     * `crest()` 被 **T14 / T12 / 沙丘烘焙 / layout 极值扫描 / 驼队脚线 / 烟根锚点** 六处共用
     * ⇒ 谁都不许自己再写一份采样公式。闸门形态照 E47 T12：**要求错误的那条路不存在**。
     *
     * ⛔ 判据跑在**已剥注释**的源码上 —— `Caravan.kt` 的 KDoc 里**故意**写着
     * `groupTopCrest(carLayer, x)`，不剥注释会把文档里的例子当成第二处口径（假 FAIL）。
     */
    @Test
    fun k2_ridgeCalibreIsSingleSourced() {
        val crestSrc = src("renderers/DesertSmokeCrest.kt", strip = true)
        val bakesSrc = src("renderers/DesertSmokeBakes.kt", strip = true)
        val caravanSrc = src("renderers/Caravan.kt", strip = true)

        // ① 脊形采样只有一个入口：`ridgeAt(` 只允许 **1 处定义 + ridgeMix 里的 2 次调用**。
        //    ⚠️ 定义行 `fun ridgeAt(x, y, p)` 自身也含 `ridgeAt(` ⇒ 总数是 3 而不是 2
        //    （第一次写成 2，被本闸门当场抓出来）。
        val atCalls = countOf("ridgeAt(", crestSrc)
        assertTrue("ridgeAt( 全文件只允许 3 次（1 定义 + ridgeMix 的 2 次调用），实测 $atCalls", atCalls == 3)
        assertTrue("ridgeMix 自身必须调两次 ridgeAt（主 / 次八度）", countOf("ridgeAt(", funBody("ridgeMix", crestSrc)) == 2)

        // ② 六个消费者各自都读共享口径
        val layoutBody = funBody("layout", crestSrc)
        val consumers = listOf(
            "T14 每层露出" to (funBody("duneExposures", crestSrc) to "crest("),
            "T12 远脊切日盘" to (funBody("sunCutFraction", crestSrc) to "crest(0, sunX)"),
            // 沙丘位图由 group 分派给 rune/swales/ripples 三支笔，取样口径在它们各自体内
            "沙丘烘焙·体块" to (funBody("rune", bakesSrc) to "l.crest(i, x)"),
            "沙丘烘焙·风成沼" to (funBody("swales", bakesSrc) to "l.crest("),
            "沙丘烘焙·波纹" to (funBody("ripples", bakesSrc) to "l.crest("),
            "layout 极值扫描" to (layoutBody to "crest(i, x)"),
            "烟根锚点" to (layoutBody to "groupTopCrest(srcLayer, smokeX)"),
            "驼队脚线" to (funBody("camelFootY", caravanSrc) to "lay.groupTopCrest(lay.carLayer, x)"),
        )
        for ((name, pair) in consumers) {
            val (body, needle) = pair
            assertTrue("$name 必须读共享脊线口径（缺 `$needle`）", body.contains(needle))
        }

        // ③ 错误的那条路必须不存在（E47 T12 形态）
        val footBody = funBody("camelFootY", caravanSrc)
        assertTrue("驼队脚线 ⛔ 不许走单条脊 lay.crest(（分层后被前一组吃掉的语义就没了）", !footBody.contains("lay.crest("))

        // ④ 剥注释自证（原文含 / 剥后不含 —— 成对，缺一条就可能空转）
        val rawCaravan = src("renderers/Caravan.kt", strip = false)
        assertTrue(
            "自证：Caravan.kt 原文 KDoc 里就写着 groupTopCrest(carLayer, x)（文档里的例子）",
            rawCaravan.contains("groupTopCrest(carLayer, x)"),
        )
        assertTrue(
            "自证：剥注释后文档里那个例子必须消失（实测 ${countOf("groupTopCrest(carLayer, x)", caravanSrc)} 次）",
            countOf("groupTopCrest(carLayer, x)", caravanSrc) == 0,
        )
        val footCalls = countOf("lay.groupTopCrest(lay.carLayer, x)", caravanSrc)
        assertTrue("自证：剥注释后真实调用只允许 1 次，实测 $footCalls", footCalls == 1)

        // ⑤ 负向自证：同一份谓词喂坏源码必须判红
        val broken = crestSrc.replace("0.22 * ridgeAt(b, c.s + 7.1, c.sharp * 0.62)", "0.22 * 0.5")
        val okReal = countOf("ridgeAt(", crestSrc) == 3
        val okBroken = countOf("ridgeAt(", broken) == 3
        assertTrue("正向谓词对真源码必须为真", okReal)
        assertTrue("负向谓词对删掉次八度调用的源码必须为假（实测 ${countOf("ridgeAt(", broken)} 次）", !okBroken)
    }

    // ─────────────────────── 源码工具（照抄 PerfBudgetContractTest 的 mainSourceRoot 手法） ───────────────────────

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    private fun src(rel: String, strip: Boolean): String {
        val f = File(mainSourceRoot(), rel)
        assertTrue("找不到源码 $rel（${f.absolutePath}）", f.isFile)
        val raw = f.readText()
        return if (strip) stripComments(raw) else raw
    }

    /**
     * 剥掉块注释（Kotlin 块注释可嵌套）与行注释。⚠️ **必须保持行数与原文件一致**
     * —— 否则按行号的定位会漂移（与 `PerfBudgetContractTest` 同一实现）。
     */
    private fun stripComments(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < s.length && depth > 0) {
                    if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '*') {
                        depth++
                        i += 2
                        continue
                    }
                    if (s[i] == '*' && i + 1 < s.length && s[i + 1] == '/') {
                        depth--
                        i += 2
                        continue
                    }
                    if (s[i] == '\n') sb.append('\n')
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                while (i < s.length && s[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * 从**已剥注释**的源码里取 `fun NAME(` 的函数体。
     *
     * ⛔ **必须先配平参数表右括号，再看先遇 `=` 还是 `{`** —— 单表达式函数
     * （`fun f(...): T = expr`）没有大括号体，直接找下一个 `{` 会**吃到下一个函数**
     * （`camelFootY` 就是这一形，第一次写错时整条谓词指向了错误的目标）。
     */
    private fun funBody(name: String, s: String): String {
        val idx = s.indexOf("fun $name(")
        assertTrue("源码里找不到 fun $name(", idx >= 0)
        var i = s.indexOf('(', idx)
        assertTrue("fun $name( 缺左括号", i >= 0)
        var depth = 0
        var closed = false
        while (i < s.length) {
            val c = s[i]
            if (c == '(') depth++
            else if (c == ')') {
                depth--
                if (depth == 0) {
                    closed = true
                    i++
                    break
                }
            }
            i++
        }
        assertTrue("fun $name( 参数表括号未闭合", closed)
        var eq = -1
        var brace = -1
        var j = i
        while (j < s.length) {
            val c = s[j]
            if (c == '=') {
                eq = j
                break
            }
            if (c == '{') {
                brace = j
                break
            }
            j++
        }
        if (eq >= 0) {
            // 表达式体可能**跨行**（`= \n    expr(...) + ...`）⇒ 逐行向后吃到下一个成员声明为止。
            val stop = listOf(
                "fun ", "internal ", "private ", "public ", "protected ", "}", "{",
                "val ", "var ", "class ", "object ", "companion ", "override ", "const ", "@",
            )
            var pos = eq + 1
            var end = s.length
            while (pos < s.length) {
                val nl = s.indexOf('\n', pos)
                val lineEnd = if (nl < 0) s.length else nl
                var t = pos
                while (t < lineEnd && (s[t] == ' ' || s[t] == '\t' || s[t] == '\r')) t++
                val trimmed = s.substring(t, lineEnd)
                if (trimmed.isNotEmpty() && stop.any { trimmed.take(12).startsWith(it) }) {
                    end = pos
                    break
                }
                if (nl < 0) break
                pos = nl + 1
            }
            return s.substring(eq, end)
        }
        assertTrue("fun $name( 既无 = 也无 {", brace >= 0)
        val sb = StringBuilder()
        var d = 0
        var k = brace
        while (k < s.length) {
            val c = s[k]
            sb.append(c)
            if (c == '{') d++
            else if (c == '}') {
                d--
                if (d == 0) return sb.toString()
            }
            k++
        }
        error("fun $name( 的函数体括号未闭合")
    }

    private fun countOf(needle: String, s: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }
}
