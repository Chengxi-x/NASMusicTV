package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * E46「迷雾之战 · 雾中三脚巨影」原型自检闸门 **T26 / T27 / T28** 的 Kotlin JVM 移植
 * （K1–K8 计划的 K8，见 `docs/archive/e46-tripod-fog-plan.md` §十五）。
 *
 * §十四 1 的口径：**纯函数闸门** —— 判解析记录 / 参数表 / 剧本表，⛔ Robolectric、⛔ 任何像素回读
 * （harness 的 `fill/stroke/drawImage` 是空实现，像素恒全 0 ⇒ 任何回读像素的判据永远绿）。
 *
 * 数字全部逐条移植自原型 `docs/archive/e46-tripod-fog-preview.html` 的对应 IIFE 与
 * `docs/archive/e46-tripod-fog-plan.md` §十二 的实测表，⛔ 手抄进生产码；每条断言消息带上量到的数值。
 *
 * 覆盖：
 *  - **T26** 滑杆初值闸门（原型 `checkSliders()`，html:4497–4512 + 5115–5130）。
 *    Kotlin 侧没有滑杆 UI，移植口径 = 把原型 `SLIDERS` 表（60 条 `bindSlider` + markup 的
 *    `[min,max]/step`）当**夹具**抄进测试，对同一批 [TripodParams] 默认值判「落在量程内」且
 *    「落在 step 格点上」，并逐条对账 Kotlin 默认值 == 原型默认值（R28 的"两个默认值"就是它抓的）。
 *  - **T27** 剧本闸门 V1 正面逼近 / V2 侧向横穿（html 约 4513–4740）。
 *  - **T28** 剧本轮换与结构闸门（html 约 4740–4925）。
 */
internal class TripodFogScriptGatesTest {

    // ══════════════════════════════════════════════════════════════════════════
    //  T26 夹具：原型 SLIDERS 表（60 条 bindSlider 的 id/key + markup 的 min/max/step + 原型默认值）
    //  ⚠️ 这是**测试夹具**，⛔ 抄进生产码；来源 = html 的 `bindSlider(...)` 与 `<input id="s…" ...>` 成对提取。
    //  ⚠️ `protoDefault` 取 markup 的 `value=`，而 markup 的 `value=` 在原型里只是"JS 没执行时的显示值"
    //     —— 真实默认恒等于 `P` 表（R28 之后 `bindSlider` 用 `el.value = sliderVal(key)` 反向回填）。
    //     所以这里同时用它当"原型 P 默认值"的镜像，与 Kotlin 侧对账。
    // ══════════════════════════════════════════════════════════════════════════

    private class SliderSpec(
        /** 原型的滑杆 id（`sD0` …），报错时定位用。 */
        val id: String,
        /** 绑定的 `P` 字段名（原型 `bindSlider` 第二参，`audio.x` 是嵌套表）。 */
        val key: String,
        val min: Double,
        val max: Double,
        val step: Double,
        /** 原型 `P` 表的默认值（= markup `value=`）。 */
        val protoDefault: Double,
        /** Kotlin [TripodParams] 上的对应字段（字段名逐字对应，仅首字母大小写不同）。 */
        val get: (TripodParams) -> Double,
    )

    private val sliders: List<SliderSpec> = listOf(
        SliderSpec("sD0", "D0", 25.0, 160.0, 1.0, 70.0) { it.d0 },
        SliderSpec("sFN", "fogNear", 0.0, 25.0, 0.5, 8.0) { it.fogNear },
        SliderSpec("sFA", "fogA", 0.0, 0.6, 0.01, 0.24) { it.fogA },
        SliderSpec("sFB", "fogB", 0.0, 0.6, 0.01, 0.28) { it.fogB },
        SliderSpec("sGB", "groundBand", 0.0, 0.8, 0.01, 0.40) { it.groundBand },
        SliderSpec("sSKH", "skirtH", 0.0, 20.0, 0.5, 8.0) { it.skirtH },
        SliderSpec("sSKA", "skirtA", 0.0, 0.9, 0.01, 0.45) { it.skirtA },
        SliderSpec("sIMP", "impactA", 0.0, 0.7, 0.01, 0.50) { it.impactA },
        SliderSpec("sHZ", "horizonK", 0.35, 0.8, 0.005, 0.58) { it.horizonK },
        SliderSpec("sFZ", "fz", 20.0, 160.0, 1.0, 60.0) { it.fz },
        SliderSpec("sPPM", "ppm", 10.0, 40.0, 0.5, 22.0) { it.ppm },
        SliderSpec("sYA", "yawAmp", 0.0, 1.6, 0.05, 1.0) { it.yawAmp },
        SliderSpec("sTM", "tiltMax", 0.0, 0.35, 0.01, 0.20) { it.tiltMax },
        SliderSpec("sBY", "bodyY", 14.0, 40.0, 0.5, 30.0) { it.bodyY },
        SliderSpec("sRF", "RF", 6.0, 24.0, 0.25, 15.0) { it.rf },
        SliderSpec("sL1", "L1", 4.0, 28.0, 0.5, 10.0) { it.l1 },
        SliderSpec("sL2", "L2", 14.0, 44.0, 0.5, 34.0) { it.l2 },
        SliderSpec("sKU", "kneeUp", 0.0, 24.0, 0.5, 13.0) { it.kneeUp },
        SliderSpec("sKO", "kneeOut", 0.0, 16.0, 0.25, 7.0) { it.kneeOut },
        SliderSpec("sBRX", "bodyRx", 3.0, 12.0, 0.1, 6.4) { it.bodyRx },
        SliderSpec("sBRY", "bodyRy", 1.0, 6.0, 0.1, 2.6) { it.bodyRy },
        SliderSpec("sBRB", "breathA", 0.0, 1.2, 0.01, 0.45) { it.breathA },
        SliderSpec("sWAK", "wakeA", 0.0, 1.0, 0.01, 0.55) { it.wakeA },
        SliderSpec("sHF", "headFwd", 3.0, 12.0, 0.25, 3.5) { it.headFwd },
        SliderSpec("sLR", "legR", 0.15, 1.5, 0.05, 0.6) { it.legR },
        SliderSpec("sST", "STRIDE", 4.0, 18.0, 0.25, 10.0) { it.stride },
        SliderSpec("sLF", "lift", 0.0, 8.0, 0.1, 4.6) { it.lift },
        SliderSpec("sKB", "kneeBow", 0.0, 0.4, 0.01, 0.14) { it.kneeBow },
        SliderSpec("sOS", "outSwing", 0.0, 6.0, 0.1, 2.8) { it.outSwing },
        SliderSpec("sBO", "bobA", 0.0, 3.0, 0.05, 0.0) { it.bobA },
        SliderSpec("sRO", "rollA", 0.0, 10.0, 0.1, 4.0) { it.rollA },
        SliderSpec("sSW", "swayA", 0.0, 4.0, 0.1, 1.9) { it.swayA },
        SliderSpec("sPI", "pitchA", 0.0, 3.0, 0.05, 1.05) { it.pitchA },
        SliderSpec("sHY", "headYawA", 0.0, 20.0, 0.1, 0.0) { it.headYawA },
        SliderSpec("sSH", "shakeA", 0.0, 3.0, 0.05, 1.0) { it.shakeA },
        SliderSpec("sJO", "joltA", 0.0, 20.0, 0.5, 9.0) { it.joltA },
        SliderSpec("sHK", "headLagK", 0.0, 1.0, 0.01, 0.13) { it.headLagK },
        SliderSpec("sGR", "grain", 0.0, 0.12, 0.005, 0.055) { it.grain },
        SliderSpec("sEM", "ember", 0.0, 0.8, 0.01, 0.30) { it.ember },
        SliderSpec("sWK", "wK", 0.0, 2.5, 0.05, 1.0) { it.wK },
        SliderSpec("sRS", "routeSeed0", 1.0, 999.0, 1.0, 1.0) { it.routeSeed0.toDouble() },
        SliderSpec("sRL", "routeLaps", 1.0, 6.0, 1.0, 2.0) { it.routeLaps.toDouble() },
        SliderSpec("sRV", "routeVar", -1.0, 2.0, 1.0, -1.0) { it.routeVar.toDouble() },
        SliderSpec("sEA", "escAmp", 0.0, 1.5, 0.05, 1.0) { it.escAmp },
        SliderSpec("sBA", "beamAmp", 0.0, 90.0, 1.0, 34.0) { it.beamAmp },
        SliderSpec("sBP", "beamPeriod", 4.0, 30.0, 0.5, 10.0) { it.beamPeriod },
        SliderSpec("sBI", "beamI", 0.0, 1.2, 0.01, 0.62) { it.beamI },
        SliderSpec("sEN", "emberN", 8.0, 48.0, 2.0, 24.0) { it.emberN.toDouble() },
        SliderSpec("sNB", "nMid", 0.0, 34.0, 1.0, 22.0) { it.nMid.toDouble() },
        SliderSpec("sNK", "nNear", 0.0, 14.0, 1.0, 8.0) { it.nNear.toDouble() },
        SliderSpec("sCN", "crowdN", 0.0, 24.0, 1.0, 8.0) { it.crowdN.toDouble() },
        SliderSpec("sGT", "grabT", 40.0, 70.0, 0.5, 52.0) { it.grabT },
        SliderSpec("sGD", "grabDz", -60.0, 0.0, 1.0, -25.0) { it.grabDz },
        SliderSpec("sTR", "tendReach", 10.0, 70.0, 1.0, 46.0) { it.tendReach },
        SliderSpec("sCI", "crowdInk", 0.0, 2.0, 0.05, 1.0) { it.crowdInk },
        SliderSpec("sCS", "crowdSoak", 0.0, 0.95, 0.01, 0.72) { it.crowdSoak },
        SliderSpec("sCS0", "crowdSeed0", 1.0, 999.0, 1.0, 1.0) { it.crowdSeed0.toDouble() },
        SliderSpec("sBas", "audio.bass", 0.0, 1.0, 0.01, 0.5) { it.audioBass },
        SliderSpec("sTre", "audio.treble", 0.0, 1.0, 0.01, 0.5) { it.audioTreble },
        SliderSpec("sEnr", "audio.energy", 0.0, 1.0, 0.01, 0.5) { it.audioEnergy },
    )

    /** 消息里的数按 JS 的观感打印（25 而不是 25.0），便于与 §十二 对读。 */
    private fun n(v: Double): String =
        if (v == Math.floor(v) && v.isFinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    /** 断言消息里带上**量到的数值**（所有者靠它判断是真绿还是空转）。 */
    private fun fmt(x: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", x)

    /**
     * 原型 `checkSliders()` 的两条判据（html:5115–5130）逐字移植：
     *  a 值落在 `[min,max]` 内 —— 浏览器会把 range **夹断**、沙箱里的 `el` 是普通对象不夹断；
     *  b 且落在 `step` 格点上 —— 浏览器会**吸附到最近格点**、沙箱同样不会。
     *  ⚠️ 两条都是"真机与沙箱两个默认值"的成因，⛔ 合成一条（合了就分不清是夹断还是吸附）。
     *  ⚠️ 容差 `1e-6` 与判据次序（先量程、越界即 `continue`）必须与原型一致：Kotlin Double 与
     *     JS number 同为 IEEE-754 double，同一批数应逐位得到同一结论。
     */
    private fun violationsOf(v: Double, min: Double, max: Double, step: Double): List<String> {
        if (!(v.isFinite() && v >= min && v <= max)) return listOf("越出 [${n(min)}, ${n(max)}]")
        val d = (v - min) / step
        if (step.isFinite() && step > 0 && Math.abs(d - Math.round(d)) > 1e-6) {
            return listOf("不在 step=${n(step)} 的格点上")
        }
        return emptyList()
    }

    /** 整表跑一遍（= 原型的 `checkSliders()`），返回 "key=v（原因）" 形式的坏项。 */
    private fun checkSliders(p: TripodParams): List<String> {
        val out = ArrayList<String>()
        for (s in sliders) {
            val bad = violationsOf(s.get(p), s.min, s.max, s.step)
            if (bad.isNotEmpty()) out.add("${s.key}=${n(s.get(p))}（${bad[0]}）")
        }
        return out
    }

    /**
     * **T26**（原型 html:4497–4512）滑杆初值由 `P` 表写入且落在量程与 `step` 格点上。
     *
     * §十二 实测：**60** 个滑杆绑定的 `P` 默认值全部落在各自 `[min,max]` 与 `step` 格点上
     * ⇒ 真机与沙箱是同一个默认值。
     * 负样本：**A** `headFwd=999`（越出 `max=12` ⇒ 浏览器夹断成 12、沙箱不夹）⇒ 抓到 **1** 项；
     * **B** `headFwd=3.4`（在量程内但不在 0.25 格点上 ⇒ 浏览器吸附到 3.5）⇒ 抓到 **1** 项。
     *
     * Kotlin 侧移植口径（无滑杆 UI）：`SLIDERS` 表当夹具（见上），判 [TripodParams] 的**默认值**；
     * 另加一条原型没有但对账同一件事的判据 —— Kotlin 默认值必须**等于**原型 `P` 默认值
     * （R28 的空改事故就是"两份事实源"，这条盯的是它的 Kotlin 侧同构形态）。
     */
    @Test
    fun t26_slider_defaults_in_range_and_on_step_grid() {
        val fresh = TripodParams()                     // ⛔ world.params：那一份会被 applyVariant 改
        // 正样本：整表零坏项（原型 pass 的第一半 bad.length === 0）
        val bad0 = checkSliders(fresh)
        assertTrue("T26 正样本红：${sliders.size} 个绑定字段里有 ${bad0.size} 项会被浏览器改写 ⇒ " + bad0.joinToString("、"),
            bad0.isEmpty())
        assertEquals("T26 反空判：SLIDERS 表条数必须与原型一致（原型 val 报 60 个滑杆）", 60, sliders.size)

        // 事实源对账：Kotlin 默认值 == 原型 P 默认值（⛔ 让 markup 反向覆盖 —— R28 的成因）
        val drift = ArrayList<String>()
        for (s in sliders) {
            val v = s.get(fresh)
            if (v != s.protoDefault) drift.add("${s.key}: kotlin=${n(v)} proto=${n(s.protoDefault)}")
        }
        assertTrue("T26 两份默认值分叉 ${drift.size} 项 ⇒ 真机与原型不是同一个画面：${drift.joinToString("、")}",
            drift.isEmpty())

        // 负样本 A：headFwd 推到量程之外（max=12 ⇒ 浏览器会夹成 12，沙箱不夹）
        val kf = fresh.headFwd
        try {
            fresh.headFwd = 999.0
            val over = checkSliders(fresh)
            assertTrue("T26-A 负样本假绿：headFwd=999 越出 max=12 却一项都没抓到（原型抓到 1 项）", over.isNotEmpty())
            assertEquals("T26-A 负样本波及面：原型只抓到 1 项，量到 ${over.size} 项 ⇒ ${over.joinToString("、")}",
                1, over.size)
            assertTrue("T26-A 抓的必须是「越界」而不是别的：${over[0]}", over[0].contains("越出 [3, 12]"))
        } finally {
            fresh.headFwd = kf
        }

        // 负样本 B：headFwd 在量程内但不在 0.25 格点上（浏览器吸附到 3.5）
        try {
            fresh.headFwd = 3.4
            val offgrid = checkSliders(fresh)
            assertTrue("T26-B 负样本假绿：headFwd=3.4 不在 0.25 格点上却一项都没抓到（原型抓到 1 项）", offgrid.isNotEmpty())
            assertEquals("T26-B 负样本波及面：原型只抓到 1 项，量到 ${offgrid.size} 项 ⇒ ${offgrid.joinToString("、")}",
                1, offgrid.size)
            assertTrue("T26-B 抓的必须是「非格点」而不是越界：${offgrid[0]}", offgrid[0].contains("不在 step=0.25 的格点上"))
        } finally {
            fresh.headFwd = kf
        }

        // 复位自检（原型同构：改完立刻再 checkSliders() 必须回到零坏项）
        val back = checkSliders(fresh)
        assertTrue("T26 复位自检红：headFwd 已还原 $kf，整表却仍有 ${back.size} 项坏值 ⇒ ${back.joinToString("、")}",
            back.isEmpty())
        assertEquals("T26 旋钮复位：headFwd 必须回到格点值 3.5（⚠️ 0.6 那类历史教训——默认值一旦不在格点上，画面从来就不是文档写的那个数）",
            3.5, fresh.headFwd, 1e-12)
        // 纯函数自检：坏值喂进去必须咬得住（⛔ 探针恒读空）
        assertTrue("T26 纯函数自检红：violationsOf 对越界值返回空 ⇒ 整条闸门是恒真式",
            violationsOf(-5.0, 0.0, 1.0, 0.05).isNotEmpty())
        assertTrue("T26 纯函数自检红：violationsOf 对非格点值返回空 ⇒ 整条闸门是恒真式",
            violationsOf(0.07, 0.0, 1.0, 0.05).isNotEmpty())
        assertTrue("T26 纯函数自检红：violationsOf 对格点内合法值报错 ⇒ 判据写坏了",
            violationsOf(0.05, 0.0, 1.0, 0.05).isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  共享口径（原型 html:2374–2442 的 `LAND_HZ / hFracOf / litOf / bodyOf / bodyXOf / onFracOf /
    //  shownIdxOf / anyContentOf / personBhOf / tendrilTip`）
    //  ⚠️ 与 `TripodFogBeamGatesTest` 里那份私有副本**同源同式**（Kotlin 侧没有跨文件共享观测器，
    //     各测试类按 §十四 各自移植同一份；⛔ 在此改写公式 —— 判据分叉就是 R17 翻车的那件事）。
    // ══════════════════════════════════════════════════════════════════════════

    private lateinit var world: TripodFogWorld
    private lateinit var persons: Array<TripodFogBake.PersonBmp>

    @Before
    fun setup() {
        world = TripodGateFixtures.boot()
        persons = TripodFogBake.bakePersons()
    }

    /** 原型 `LAND_HZ(st)`：`hz = H·horizonK + tilt`，与 render()/T7 同一处落地。 */
    private fun landHz(st: TripodScene) {
        world.cam.hz = TripodGateFixtures.hzBase(world) + st.tilt
    }

    /** 原型 `hFracOf`：整机投影（屏）—— >1 = 被裁。 */
    private fun hFracOf(st: TripodScene): Double {
        val p = world.params
        val cam = world.cam
        return TripodFogMath.topY(p) * cam.ppm * (p.fz / (p.fz + st.dist)) / cam.h
    }

    /** 原型 `litOf`：剪影落在背景上的对比度 ≥ 5%。 */
    private fun litOf(st: TripodScene): Boolean = TripodGateFixtures.visibleEnough(world, st) >= 0.05

    /** 原型 `bodyOf`。 */
    private fun bodyOf(st: TripodScene): BodyPt =
        TripodFogMath.bodyFrame(0.0, 0.5, st.bodyY, st.psi, st.d, st.mx)

    /** 原型 `bodyXOf`：机身中心的屏幕横坐标（NaN = 浓雾外或被 `pr` 剔除 ⇒ 调用方按 lit 过滤）。 */
    private fun bodyXOf(st: TripodScene): Double {
        if (!litOf(st)) return Double.NaN
        val bc = bodyOf(st)
        landHz(st)
        val q = TripodFogMath.pr(world.cam, world.params, bc.x, bc.y, bc.z, st.yaw)
        return if (q == null) Double.NaN else q.X
    }

    /** 原型 `onFracOf`：整机竖直投影落在视口内的比例（机顶 ↔ 机身正下方地面点，含 tilt 后的 hz）。 */
    private fun onFracOf(st: TripodScene): Double {
        val p = world.params
        val cam = world.cam
        landHz(st)
        val bc = bodyOf(st)
        val tp = TripodFogMath.pr(cam, p, bc.x, bc.y + (TripodFogMath.topY(p) - p.bodyY), bc.z, st.yaw)
        val ft = TripodFogMath.pr(cam, p, bc.x, 0.0, bc.z, st.yaw)
        if (tp == null || ft == null) return 0.0
        val lo = min(tp.Y, ft.Y)
        val hi = max(tp.Y, ft.Y)
        if (hi - lo <= 0.0) return 0.0
        return (min(hi, cam.h) - max(lo, 0.0)) / (hi - lo)
    }

    /** 原型 `shownIdxOf`：⛔ 只判机身中心（R17 的"巨腿"教训），逐部件找露馅下标。 */
    private fun shownIdxOf(st: TripodScene): Int {
        if (!litOf(st)) return -1
        val p = world.params
        val cam = world.cam
        landHz(st)
        val bc = bodyOf(st)
        val top = TripodFogMath.topY(p) - p.bodyY
        val xs = doubleArrayOf(bc.x, bc.x, st.legs[0].fx, st.legs[1].fx, st.legs[2].fx)
        val ys = doubleArrayOf(bc.y, bc.y + top, 0.0, 0.0, 0.0)
        val zs = doubleArrayOf(bc.z, bc.z, st.legs[0].fz, st.legs[1].fz, st.legs[2].fz)
        for (i in 0 until 5) {
            val c = TripodFogMath.pr(cam, p, xs[i], ys[i], zs[i], st.yaw) ?: continue
            if (abs(c.X - cam.cx) <= cam.w * 0.5 + 40 && c.Y >= -40 && c.Y <= cam.h + 40) return i
        }
        return -1
    }

    /** 原型 `anyContentOf`：盲跑段 = 既没有机器也没有任何一栋楼（建筑判据与绘制同源）。 */
    private fun anyContentOf(st: TripodScene): Boolean {
        if (shownIdxOf(st) >= 0) return true
        if (bandHasContent(st, st.bldMid, st.bldMidN)) return true
        return bandHasContent(st, st.bldNear, st.bldNearN)
    }

    private fun bandHasContent(st: TripodScene, arr: Array<BldSel>, n: Int): Boolean {
        val p = world.params
        val cam = world.cam
        for (i in 0 until n) {
            val b = arr[i]
            if (b.a <= 0.002 || b.z < 2) continue
            val p0 = TripodFogMath.pr(cam, p, b.x, 0.0, b.z, st.yaw) ?: continue
            val p1 = TripodFogMath.pr(cam, p, b.x, b.h, b.z, st.yaw) ?: continue
            val wpx = b.w * cam.ppm * p0.s
            val x = p0.X - wpx / 2
            val hh = p0.Y - p1.Y
            if (x + wpx < -20 || x > cam.w + 20 || hh < 0.5) continue
            val ink = TripodFogMath.clamp(
                TripodFogMath.shadeA(hypot(b.x, b.z), st.d0, p) * b.a * b.ink, 0.0, 1.0,
            )
            if (ink >= 0.004) return true
        }
        return false
    }

    /** 原型 `personBhOf(k)`：该姿态的绘制高度修正（⛔ 按位图像素反推 —— 位图高 34 px 是烘焙分辨率）。 */
    private fun personBhOf(kind: Int): Double = persons[kind.coerceIn(0, TripodSlot.PERSON_N - 1)].bh

    /** 与 [TripodFogPlan] 的 `grab()` 逐参同构的缆须上下文（⛔ 闸门里另推"该伸多长"）。 */
    private fun tendrilCtx(st: TripodScene): TripodFogMath.TendrilCtx {
        val p = world.params
        val v = st.crowd[0]
        return TripodFogMath.TendrilCtx(
            st.bodyLat, st.leanFwd, st.bodyY, st.psi, st.d, st.mx,
            p.bodyRx, p.personH, p.tendReach, v.x, v.y, v.z, st.grab.reach,
        )
    }

    /**
     * 原型 `withVariant(vi, fn)`（html:2448–2459）：临时换戏 + **精确还原**。
     *
     * ⚠️ R30 纪律：旋钮必须写在 `fn` **里面** —— `applyVariant` 会重灌 `fireT0/fireT1/grabT/yawAmp`
     *   与四张表，末尾 `buildCum` 还会重跑 `setWanderCenter()` ⇒ 在外面改的值当场被覆盖。
     * ⚠️ `force = true` 绕过 `applyVariant` 的"同一套就跳过"短路（原型先把 `CURVAR` 按成 −1）。
     * ⛔ 在 `fn` 里改的其他 P 字段由调用方自己还原（本函数只管它动过的那五项，管多了会把负样本悄悄擦掉）。
     */
    private fun <T> withVariant(vi: Int, fn: () -> T): T {
        val p = world.params
        val v0 = world.curVar
        val f0 = p.fireT0
        val f1 = p.fireT1
        val g0 = p.grabT
        val y0 = p.yawAmp
        try {
            TripodFogMath.applyVariant(world, vi, true)
            return fn()
        } finally {
            TripodFogMath.applyVariant(world, v0, true)
            p.fireT0 = f0
            p.fireT1 = f1
            p.grabT = g0
            if (p.yawAmp != y0) TripodFogMath.setYawAmp(world, y0)
            TripodFogMath.setGrabKnots(world)
            world.crowdT = null                       // 原型 `_ct = null`
            world.crowdKey = ""
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  T27 剧本闸门 V1 正面逼近 / V2 侧向横穿
    // ══════════════════════════════════════════════════════════════════════════

    private companion object {
        /** 原型 T27 的 `DT`（掠过/揭示要亚帧级，⛔ 0.1 s 会漏极值）。 */
        const val DT = 0.05
        const val RAD = 180.0 / Math.PI
        const val LIM_MX_BEAT = 6.0
        const val LIM_SPAN = 0.40
        const val LIM_R_LO = 0.15
        const val LIM_R_HI = 0.60
        const val LIM_BETA = 0.25
    }

    /** 原型 T27 的 `scan()` 输出 p（一整圈的公共度量，⛔ 与剧本无关）。 */
    private class T27Scan {
        var distMin = Double.POSITIVE_INFINITY
        var tMin = 0.0
        var fracMax = 0.0
        var xLo = Double.POSITIVE_INFINITY
        var tLo = 0.0
        var dLo = 0.0
        var xHi = Double.NEGATIVE_INFINITY
        var tHi = 0.0
        var dHi = 0.0
        var cBest = 0
        var cA = 0.0
        var cB = 0.0
        var cFrac = 1.0
        var fBest = 0
        var fA = 0.0
        var fB = 0.0
        var rLo = Double.POSITIVE_INFINITY
        var rHi = 0.0
        var rN = 0
        var yawMax = 0.0
        var betaMax = 0.0
        var fireVis = 0
        var fireSweep = 0
        var fireAtMin = 0.0
        var mxAtMin = 0.0
        var mxAtBeat = 0.0
        var baseAtBeat = 0.0
        var envAtBeat = 0.0
        var beatT = 0.0
        var blankMax = 0
        var blankA = 0.0
        var blankB = 0.0
        var grabRuns = 0
        var grabEnd = 0.0
        var gap = -9.0
        var gF = 0
        var vPx = Double.POSITIVE_INFINITY
        var vA = 9.0
    }

    /** 一整圈的公共度量：逐帧调**画面正在用的那同一份**口径函数（⛔ 闸门自己重推 `wanderPart`）。 */
    private fun scanT27(): T27Scan {
        val p = world.params
        val cam = world.cam
        val q = T27Scan()
        var cur = 0
        var bgn = 0.0
        var curF = 0
        var bgnF = 0.0
        var curE = 0
        var bgnE = 0.0
        var run = 0
        var t = 0.0
        while (t <= p.loop + 1e-9) {
            val st = TripodGait.sceneState(world, t)
            if (st.dist < q.distMin) {
                q.distMin = st.dist
                q.tMin = t
            }
            q.fracMax = max(q.fracMax, hFracOf(st))
            val isLit = litOf(st)
            val x = bodyXOf(st)
            val on = onFracOf(st)
            if (isLit && !x.isNaN()) {
                if (x < q.xLo) {
                    q.xLo = x; q.tLo = t; q.dLo = st.dist
                }
                if (x > q.xHi) {
                    q.xHi = x; q.tHi = t; q.dHi = st.dist
                }
                val beta = atan2(st.mx, if (abs(st.d) < 1e-6) 1e-6 else st.d)
                q.yawMax = max(q.yawMax, abs(st.yaw))
                q.betaMax = max(q.betaMax, abs(beta))
                if (abs(beta) >= LIM_BETA) {
                    val r = abs(st.yaw) / abs(beta)
                    q.rN++
                    q.rLo = min(q.rLo, r); q.rHi = max(q.rHi, r)
                }
                val centered = abs(x - cam.cx) <= cam.w * 0.15 && on >= 0.7
                if (centered) {
                    if (cur == 0) bgn = t
                    cur++
                    if (cur > q.cBest) {
                        q.cBest = cur; q.cA = bgn; q.cB = t
                    }
                    q.cFrac = min(q.cFrac, on)
                } else cur = 0
                if (centered && on >= 0.995) {
                    if (curF == 0) bgnF = t
                    curF++
                    if (curF > q.fBest) {
                        q.fBest = curF; q.fA = bgnF; q.fB = t
                    }
                } else curF = 0
            } else {
                cur = 0
                curF = 0
            }
            if (st.fire > 0.004 && isLit) q.fireVis++
            if (anyContentOf(st)) curE = 0
            else {
                if (curE == 0) bgnE = t
                curE++
                if (curE > q.blankMax) {
                    q.blankMax = curE; q.blankA = bgnE; q.blankB = t
                }
            }
            if (st.grab.reach > 0.02) {
                if (run == 0) q.grabRuns++
                run++
                q.grabEnd = t
            } else run = 0
            if (st.grab.reach >= 0.99 && st.grab.lift <= 0.2) {
                q.gF++
                val ctx = tendrilCtx(st)
                for (u in doubleArrayOf(-0.62, 0.62)) {
                    val tp = TripodFogMath.tendrilTip(ctx, u)
                    q.gap = max(q.gap, tp.D - tp.L)
                }
                val v = st.crowd[0]
                landHz(st)
                val vp = TripodFogMath.pr(cam, p, v.x, v.y, v.z, st.yaw)
                if (vp != null) {
                    q.vPx = min(q.vPx, p.personH * personBhOf(v.kind) * cam.ppm * vp.s)
                    q.vA = min(q.vA, v.a)
                }
            }
            t += DT
        }
        // ⭐ R30：「最近位 ±2 s 的开火量」**必须在 tMin 定稿之后回读**（边扫边比会把"从 t=0 起的一整段"
        //   算进来 —— V1 熄灯窗之外 220 m 处正在扫射本来合理，却量到 fire=1.000 ⇒ 假红）。
        var u = max(0.0, q.tMin - 2)
        while (u <= min(p.loop, q.tMin + 2)) {
            q.fireAtMin = max(q.fireAtMin, TripodGait.sceneState(world, u).fire)
            u += DT
        }
        // ⭐ R30：V1 的居中机制**按设计拍量，⛔ 按 dist 最小位量**（换个最近位会把谷失效掩盖掉）。
        q.mxAtMin = abs(TripodGait.sceneState(world, q.tMin).mx)
        val tb = world.tcross.t1
        val gb = world.gAt(tb)
        val sb = TripodGait.sceneState(world, tb)
        q.beatT = tb
        q.mxAtBeat = abs(sb.mx)
        q.baseAtBeat = abs(world.pathBase(gb))
        q.envAtBeat = world.wanderEnv(gb)
        // 横扫窗（两极值之间）内的可见开火时长 —— 只有 tLo < tHi（真横扫）才有意义
        if (q.tHi > q.tLo) {
            var s = q.tLo
            while (s <= q.tHi) {
                val st = TripodGait.sceneState(world, s)
                if (st.fire > 0.004 && litOf(st)) q.fireSweep++
                s += DT
            }
        }
        // 度量写过的全局 hz 必须复位（与 T7 同一纪律）
        world.cam.hz = TripodGateFixtures.hzBase(world)
        return q
    }

    /** 每套剧本共有的四项：结构闭合、贴身段熄灯、可见开火有量、盲跑有界 + 抓取段（原型 `common`）。 */
    private fun commonWhy(q: T27Scan): List<String> {
        val w = ArrayList<String>()
        if (world.tcross.n % 2 != 0) {
            w.add("z 一圈内 d 穿越 0 的次数是奇数（${world.tcross.n}）⇒ 首末符号不同，接缝必开")
        }
        // ⚠️ 这条**只在真的贴身时才要求熄灯**，"贴身"用当圈的投影量判，⛔ 拿 P.fireNear 当条件
        //    （负样本 B 就是把 fireNear 拆成 0 —— 条件随之失效，缺陷反而量不到）。
        if (q.fracMax >= 0.8 && !(q.fireAtMin < 0.004)) {
            w.add("z 整机投影 ${fmt(q.fracMax, 2)} 屏（贴身段）而最近位 ±2 s 最大开火量 " +
                "${fmt(q.fireAtMin, 3)} ≥ 0.004 ⇒ 两道光柱贴着镜头插进画面（fireEnv 的两道闸都没生效）")
        }
        if (!(q.fireVis * DT >= 4)) {
            w.add("z 开火且整机可见只 ${fmt(q.fireVis * DT)} s < 4 s ⇒ 这一圈基本没看见热光（需求点 2 落空）")
        }
        if (!(q.blankMax * DT <= 9)) {
            w.add("z 空雾（机器与建筑都不可见）最长连续 ${fmt(q.blankMax * DT)} s " +
                "@t=${fmt(q.blankA)}→${fmt(q.blankB)} ⇒ 循环里有一段纯白幕")
        }
        if (!(q.grabRuns == 1)) w.add("g 一圈内缆须伸出 ${q.grabRuns} 次（应恰好 1 次 = 一轮一次）")
        if (!(q.grabEnd <= q.tMin - 1)) {
            w.add("g 抓取段收尾于 t=${fmt(q.grabEnd)} s，晚于最近位 t=${fmt(q.tMin)} s ⇒ 和这一圈的看家拍撞车")
        }
        if (!(q.gF * DT >= 0.5)) w.add("g 峰值抓取窗只 ${fmt(q.gF * DT, 2)} s（判据空转）")
        if (!(q.gap <= 0.8)) w.add("g 峰值时缆须梢离人还差 ${fmt(q.gap)} m ⇒ 解剖够不着")
        if (!(q.vPx >= 12)) w.add("g 受害人最矮 ${fmt(q.vPx)} px < 12 px ⇒ 读不出那是个人")
        if (!(q.vA >= 0.10)) w.add("g 受害人最淡 α=${fmt(q.vA, 3)} ⇒ 雾里看不见谁被抓住")
        return w
    }

    /** V1 正面逼近：看家拍是"它正对着我们压到脸上"（原型 `why1`）。 */
    private fun why1(q: T27Scan): List<String> {
        val w = ArrayList<String>()
        if (!(q.distMin <= 30)) w.add("a 最近真实距离 ${fmt(q.distMin)} m > 30 m ⇒ 没有\u201c面对面\u201d")
        if (!(q.fracMax >= 0.85)) w.add("a 最大整机投影 ${fmt(q.fracMax, 2)} 屏 < 0.85 ⇒ 压不出罩住镜头的感觉")
        if (!(q.cBest * DT >= 2)) {
            w.add("b 正对镜头（机身中心 ±15% 宽且在画 ≥70%）最长连续 ${fmt(q.cBest * DT, 2)} s < 2 s ⇒ 正面逼近一帧就飘走")
        }
        if (!(q.fBest * DT >= 1)) {
            w.add("b2 含机顶**整机完整入画**最长 ${fmt(q.fBest * DT, 2)} s < 1 s ⇒ 全程只看见腹部与腿，认不出那是机器")
        }
        if (q.yawMax * RAD > 1) {
            w.add("d 本剧本镜头不该转（YH 全 0），实测 |yaw| 峰值 ${fmt(q.yawMax * RAD)}° ⇒ 有东西在动偏航")
        }
        if (!(q.mxAtBeat <= LIM_MX_BEAT)) {
            w.add("f 设计拍 t=${fmt(world.tcross.t1)} s 的横向偏移 ${fmt(q.mxAtBeat)} m > $LIM_MX_BEAT m " +
                "⇒ 不是\u201c迎面压过来\u201d，是从侧面擦过去（谷没压住随机游走，原形 ${fmt(q.baseAtBeat)} m × " +
                "wanderEnv=${fmt(q.envAtBeat, 3)}；而 dist 最小位自己挪到了横向只有 ${fmt(q.mxAtMin)} m 的另一拍 = 掩盖）")
        }
        return w + commonWhy(q)
    }

    /** V2 侧向横穿：看家拍是"它保持中距把整片城区扫一遍"（原型 `why2`）。 */
    private fun why2(q: T27Scan): List<String> {
        val w = ArrayList<String>()
        val cam = world.cam
        val span = (q.xHi - q.xLo) / cam.w
        if (!(q.xLo <= cam.w * 0.30)) w.add("a 机身中心最左只到 ${fmt(q.xLo / cam.w * 100, 0)}% 宽 > 30% ⇒ 没有横扫到左翼")
        if (!(q.xHi >= cam.w * 0.70)) w.add("a 机身中心最右只到 ${fmt(q.xHi / cam.w * 100, 0)}% 宽 < 70% ⇒ 没有横扫到右翼")
        if (!(span >= LIM_SPAN)) w.add("a 横扫幅度 ${fmt(span * 100, 0)}% 宽 < 40% ⇒ 读成\u201c慢慢漂\u201d而不是横穿")
        if (!(q.dLo <= 120 && q.dHi <= 120)) {
            w.add("a 两极值帧的真距 ${fmt(q.dLo, 0)} / ${fmt(q.dHi, 0)} m > 120 m ⇒ 那只是远处雾里的漂移，不是横穿")
        }
        if (!(q.distMin >= 40)) w.add("b 最近 ${fmt(q.distMin)} m < 40 m ⇒ 这一套也变成贴身戏，与 V0/V1 撞车")
        if (!(q.rN >= 20)) w.add("c |β|≥${fmt(LIM_BETA * RAD, 0)}° 的帧只有 ${q.rN} 帧（判据空转 ⇒ 编舞弧没撑开）")
        if (!(q.rLo >= LIM_R_LO)) {
            w.add("c |yaw|/|β| 最低 ${fmt(q.rLo, 2)} < $LIM_R_LO ⇒ 镜头完全不跟，34 m 的巨影贴着画面边平移，像贴上去的纸板")
        }
        if (!(q.rHi <= LIM_R_HI)) {
            w.add("c |yaw|/|β| 最高 ${fmt(q.rHi, 2)} > $LIM_R_HI ⇒ 镜头跟得太死，机器在画面里钉住不动，横穿退化成\u201c镜头替它稳住\u201d")
        }
        if (!(q.fireSweep * DT >= 8)) {
            w.add("e 横扫窗内可见开火只 ${fmt(q.fireSweep * DT)} s < 8 s ⇒ 这一套戏的高潮（两道光扫过城区）是黑的")
        }
        return w + commonWhy(q)
    }

    /** 原型 `has(w, tag)`：负样本必须咬在**自己的字母**上（⛔ 判"非空"—— 蹭到别的判据 = 从没验过这个缺陷）。 */
    private fun has(w: List<String>, tag: String): Boolean = w.any { it.startsWith("$tag ") }

    private fun joinW(w: List<String>): String =
        if (w.isEmpty()) "⚠️ 假负样本（判据空转 ⇒ 这条缺陷从没被验过）" else w.joinToString("；").take(260)

    private fun describe(v: String, q: T27Scan): String =
        "$v：最近 ${fmt(q.distMin)} m @t=${fmt(q.tMin)}（投影 ${fmt(q.fracMax, 2)} 屏）、正对连续 " +
            "${fmt(q.cBest * DT)} s @t=${fmt(q.cA)}→${fmt(q.cB)}（段内最低在画 ${fmt(q.cFrac * 100, 0)}%）、" +
            "含机顶完整入画 ${fmt(q.fBest * DT)} s、|yaw| 峰值 ${fmt(q.yawMax * RAD)}°、可见开火 " +
            "${fmt(q.fireVis * DT, 0)} s、空雾 ${fmt(q.blankMax * DT)} s、设计拍 t=${fmt(q.beatT)} 横向 " +
            "${fmt(q.mxAtBeat)} m（原形 ${fmt(q.baseAtBeat)} m × wanderEnv=${fmt(q.envAtBeat, 3)}）、" +
            "最近位横向 ${fmt(q.mxAtMin)} m、受害人 ${fmt(q.vPx)} px / α ${fmt(q.vA, 2)}、缆须余量 " +
            "${fmt(q.gap, 2)} m、横扫 ${fmt(q.xLo / world.cam.w * 100, 0)}%→${fmt(q.xHi / world.cam.w * 100, 0)}%" +
            "（|yaw|/|β| ∈ [${fmt(q.rLo, 2)}, ${fmt(q.rHi, 2)}]，${q.rN} 帧）"

    /**
     * **T27**（原型 html:4513–4765）剧本闸门 V1 正面逼近 / V2 侧向横穿：各判各的看家拍 + 各带各的负样本。
     *
     * §十二 实测（⛔ 把 T18 的"掠过 9~16 m + 身后揭示 + 藏穿越②"套到 V1/V2 上是**假红**）：
     *  - **V1**：最近 **24.0 m @t=68.0**（投影 **0.98 屏**）、正对镜头连续 **23.9 s** @t=56.1→80.0
     *    （段内最低在画 **84%**）、含机顶完整入画 **12.4 s**、`|yaw|` 峰值 **0.0°**、可见开火 **48 s**、
     *    空雾 **0.0 s**；⭐ 设计拍 t=68.0 横向 **0.0 m**（谷把随机游走原形 **12.9 m** 压到
     *    `wanderEnv=0.000`）、最近位横向 **0.0 m**、受害人 **19.3 px / α 0.45**、缆须余量 **0.00 m**。
     *  - **V2**：横扫 **23%→75% 宽**（@t=63.9→78.4 s，两极值帧真距 **81/101 m**）、最近 **73.6 m**
     *    （投影 **0.62 屏**）、`|yaw|/|β| ∈ [0.29, 0.31]`（**351** 帧，β 峰值 27.8°）、横扫窗内可见开火
     *    **14.6 s**、受害人 **16.4 px / α 0.33**、缆须余量 **0.00 m**。
     *
     * 负样本（每条咬在自己的字母上）：**V1·A** 谷心错位 1/4 圈 ⇒ f（设计拍横向 **12.2 m > 6 m**，
     * 原形 12.9 m × `wanderEnv=0.949`，而 dist 最小位自己挪到横向 5.7 m 的另一拍 = 掩盖）；
     * **V1·B** 拆 `fireEnv` 两道闸 ⇒ z（贴身段投影 0.98 屏而最近位 ±2 s 最大开火量 **1.000**）；
     * **V1·C** 最近位拉到 90 m ⇒ a（**90.0 m > 30 m** + 最大投影 **0.55 屏 < 0.85**）；
     * **V2·D** 镜头不跟 `yawAmp=0` ⇒ c（比值最低 **0.00 < 0.15**）；
     * **V2·E** 跟死 `yawAmp=2.5` ⇒ c（比值最高 **0.77 > 0.60**，横扫同时被吃掉）；
     * **V2·F** `escAmp=0` ⇒ a（横扫 **36% < 40%**、`|β|≥14°` 只 **0** 帧 = 判据空转）；
     * **V2·G** 熄灯窗盖住横扫 ⇒ e（可见开火 **0.0 s < 8 s**）。
     */
    @Test
    fun t27_variantScriptsHoldTheirOwnSignatureBeats() {
        // ── 正样本：两套各跑各的 ──
        val p1 = withVariant(1) { scanT27() }
        val w1 = why1(p1)
        val p2 = withVariant(2) { scanT27() }
        val w2 = why2(p2)
        assertTrue("T27 V1 正样本未过：${w1.joinToString("；")} ／ ${describe("V1", p1)}", w1.isEmpty())
        assertTrue("T27 V2 正样本未过：${w2.joinToString("；")} ／ ${describe("V2", p2)}", w2.isEmpty())
        // 反空判：正样本必须真的量到东西（⛔ 一圈都在雾外也"通过"）
        assertTrue("T27 反空判：V1 一帧\u201c正对镜头\u201d都没采到（cBest=${p1.cBest}）⇒ 闸门空转", p1.cBest > 0)
        assertTrue("T27 反空判：V2 一帧 |β|≥14° 都没采到（rN=${p2.rN}）⇒ 闸门空转", p2.rN > 0)
        assertTrue("T27 反空判：V2 横扫窗内一次可见开火都没采到（fireSweep=${p2.fireSweep}）", p2.fireSweep > 0)

        // ── V1·A 谷**心**错位（GW 挪开 1/4 圈）⇒ f 红 ──
        //   ⚠️ R30 否掉了"谷深塌掉"（wValleyA/B→0）：那两条滑杆调的是谷的**宽度**，
        //   `smoothstep(GTOT·A, GTOT·B, 0)` 在谷心恒 0 ⇒ 只会让谷变陡，是一条咬不动任何判据的假负样本。
        val n1a = withVariant(1) {
            val s = world.gw
            try {
                world.gw = s + world.gtot * 0.25
                why1(scanT27())
            } finally {
                world.gw = s
            }
        }
        assertTrue("T27 负样本 V1·A 假绿（谷心错位必须咬到 f）：${joinW(n1a)}", has(n1a, "f"))

        // ── V1·B 拆掉 fireEnv 的两道闸（时间窗挪出贴身段 + 关掉近距强制熄灭）⇒ z 红 ──
        //   ⚠️ 只拆时间窗**不会**红 —— fireNear=45 m 还会兜住 24 m；这条验的是"两道闸都要"本身。
        val savedNear = world.params.fireNear
        val n1b = withVariant(1) {
            try {
                world.params.fireT0 = 10.0
                world.params.fireT1 = 30.0
                world.params.fireNear = 0.0
                why1(scanT27())
            } finally {
                // ⚠️ fireNear 不在 withVariant 的还原清单里（原型只管五项）⇒ 这条负样本自己管
                world.params.fireNear = savedNear
            }
        }
        assertTrue("T27 负样本 V1·B 假绿（贴身段开火必须咬到 z）：${joinW(n1b)}", has(n1b, "z"))

        // ── V1·C 最近位拉到 90 m：**整段谷**抬到 90（⛔ 只抬最低那一拍：Hermite 在相邻三拍之间
        //    仍有 ≈26 m 的曲线最低点，判据 a 咬不住，R30 实测）；改 DH_KY 后**原地**重建 + 重扫穿越 ──
        val n1c = withVariant(1) {
            val sv = world.dhKy
            val m = DoubleArray(sv.size) { max(sv[it], 90.0) }
            try {
                world.dhKy = m
                world.dh.set(TRIPOD_DH_KX, m)
                TripodFogMath.scanCrossInto(world)     // d 整体上抬 ⇒ TCROSS 要跟着重扫（applyVariant 的第③步）
                world.crowdT = null
                why1(scanT27())
            } finally {
                world.dhKy = sv
                world.dh.set(TRIPOD_DH_KX, sv)
                TripodFogMath.scanCrossInto(world)
                world.crowdT = null
                world.crowdKey = ""
            }
        }
        assertTrue("T27 负样本 V1·C 假绿（最近位拉到 90 m 必须咬到 a）：${joinW(n1c)}", has(n1c, "a"))

        // ── V2·D 镜头不跟（yawAmp=0）⇒ c 下界红（横扫幅度反而变大，a 照样过 = 它单独成一条的理由）──
        val n2d = withVariant(2) {
            TripodFogMath.setYawAmp(world, 0.0)
            val w = why2(scanT27())
            w
        }
        assertTrue("T27 负样本 V2·D 假绿（镜头不跟必须咬到 c）：${joinW(n2d)}", has(n2d, "c"))

        // ── V2·E 镜头跟得太死（yawAmp=2.5 ⇒ 比值 ≈0.3·2.5=0.75）⇒ c 上界红 ──
        val n2e = withVariant(2) {
            TripodFogMath.setYawAmp(world, 2.5)
            why2(scanT27())
        }
        assertTrue("T27 负样本 V2·E 假绿（镜头跟死必须咬到 c）：${joinW(n2e)}", has(n2e, "c"))

        // ── V2·F 编舞弧整幅关掉（escAmp=0）⇒ 横扫退化成随机游走的小漂 ⇒ a 红（+ c 空转）──
        val savedEsc = world.params.escAmp
        val n2f = withVariant(2) {
            try {
                world.params.escAmp = 0.0
                world.crowdT = null                   // escAmp 进不了人群键，但会改走位 ⇒ 一并作废缓存
                why2(scanT27())
            } finally {
                world.params.escAmp = savedEsc        // 同上：⛔ 让 escAmp=0 漏给后面的闸门
                world.crowdT = null
                world.crowdKey = ""
            }
        }
        assertTrue("T27 负样本 V2·F 假绿（关掉编舞弧必须咬到 a）：${joinW(n2f)}", has(n2f, "a"))

        // ── V2·G 熄灯窗盖住横扫段（[55,84]）⇒ e 红（z 靠 fireNear 兜不住：最近位 72 m）──
        //   ⛔ 不手工还原 fireT0/T1 —— withVariant 的 finally 负责（R30 之前这里还原的是一对旧变量名）。
        val n2g = withVariant(2) {
            world.params.fireT0 = 55.0
            world.params.fireT1 = 84.0
            why2(scanT27())
        }
        assertTrue("T27 负样本 V2·G 假绿（熄灯窗盖住横扫必须咬到 e）：${joinW(n2g)}", has(n2g, "e"))

        // ── 复位自检：⛔ 让一次自检把画面永久留在 V1/V2 上 ──
        val pp = world.params
        assertEquals("T27 结束时 curVar 必须回到进入时的 0", 0, world.curVar)
        assertEquals("fireT0 未还原（负样本 B/G 漏了复位）", 54.5, pp.fireT0, 1e-12)
        assertEquals("fireT1 未还原", 87.0, pp.fireT1, 1e-12)
        assertEquals("grabT 未还原", 52.0, pp.grabT, 1e-12)
        assertEquals("yawAmp 未还原（setYawAmp 是倍率，⛔ 绝对角度口径）", 1.0, pp.yawAmp, 1e-12)
        assertEquals("fireNear 未还原", 45.0, pp.fireNear, 1e-12)
        assertEquals("escAmp 未还原", 1.0, pp.escAmp, 1e-12)
        assertEquals("V0 的间距表被负样本 C 留在别处 ⇒ dhKy 不是 TRIPOD_VARS[0].dh 那份引用",
            220.0, world.dhKy[0], 1e-12)
        assertTrue("T27 复位后 V0 的正样本读不到设计拍（tcross.t1=${fmt(world.tcross.t1, 2)}）",
            world.tcross.t1 > 0.0)
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  T28 剧本轮换与结构闸门
    // ══════════════════════════════════════════════════════════════════════════

    /** 原型 `L1(a,b)`：逐拍绝对差之和（缺项按 0 计，与 JS 的 `b[i] || 0` 同式）。 */
    private fun l1(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += abs(a[i] - (if (i < b.size) b[i] else 0.0))
        return s
    }

    /**
     * 原型 `dup`：三套骨架**两两**必须真的不同（dh / yh / ch 各自 L1 > 10，⛔ 换个名字当两套剧本）。
     *
     * ⚠️ 判据是纯函数，输入是"这三套的表"—— Kotlin 的 [TripodScript] 全是 `val`（原型 `VARS[i].dh = …`
     *   那种就地改写做不到），所以负样本 B 靠**代入另一份表**走同一个判据函数（真表 + 真公式，
     *   ⛔ 手喂一个越界常量）。
     */
    private fun dupWhy(
        names: List<String>,
        dhs: List<DoubleArray>,
        yhs: List<DoubleArray>,
        chs: List<DoubleArray>,
    ): List<String> {
        val dup = ArrayList<String>()
        for (i in names.indices) for (j in i + 1 until names.size) {
            if (names[i] == names[j]) dup.add("V$i/V$j 同名")
            val ld = l1(dhs[i], dhs[j])
            if (ld <= 10) dup.add("V$i/V$j 间距骨架相同（dh L1=${fmt(ld, 0)}）")
            val ly = l1(yhs[i], yhs[j])
            if (ly <= 10) dup.add("V$i/V$j 偏航骨架相同（yh L1=${fmt(ly, 0)}）")
            val lc = l1(chs[i], chs[j])
            if (lc <= 10) dup.add("V$i/V$j 横向编舞相同（ch L1=${fmt(lc, 0)}）")
        }
        return dup
    }

    /** 原型 `struct(vi)`：每套剧本自己的结构前提（接缝藏得住 / 偶数穿越 / 熄灯两端等值 / 首末闭合 / 表长 / 相位铺满）。 */
    private fun structWhy(vi: Int): List<String> {
        val p = world.params
        val w = ArrayList<String>()
        val ky = world.dh.ky
        val dSeam = max(ky[0], ky[ky.size - 1])
        val c = TripodFogMath.contrastAt(dSeam, p.d0, p, world.palette)
        if (!(c < 0.05)) {
            w.add("c V$vi 接缝位 d=${fmt(dSeam, 0)} m 对比度 ${fmt(c * 100)}% ≥ 5% ⇒ 跨圈那两帧整机露出来")
        }
        if (world.tcross.n % 2 != 0) {
            w.add("c V$vi 一圈穿越 d=0 ${world.tcross.n} 次（奇数）⇒ 首末符号不同，必开缝")
        }
        val f0 = TripodFogMath.fireEnv(p, 0.0)
        val fL = TripodFogMath.fireEnv(p, p.loop)
        if (abs(f0 - fL) > 1e-9) {
            w.add("c V$vi fireEnv(0)=${fmt(f0, 3)} ≠ fireEnv(LOOP)=${fmt(fL, 3)} ⇒ 熄灯窗跨过接缝" +
                "（窗口必须严格落在 (0,LOOP) 内且宽度 ≥2·FIRE_RAMP）")
        }
        val yk = world.yhKy
        val dy = TripodFogMath.mod(yk[yk.size - 1] - yk[0], 360.0)
        if (dy != 0.0) {
            w.add("c V$vi 偏航首末 ${fmt(yk[0], 0)}→${fmt(yk[yk.size - 1], 0)}（差 ${fmt(dy, 1)}° 非 360 整周）" +
                "⇒ 镜头跨圈跳变")
        }
        val ck = world.chTy
        if (ck[0] != 0.0 || ck[ck.size - 1] != 0.0) {
            w.add("c V$vi 编舞首末非 0（${fmt(ck[0], 0)}/${fmt(ck[ck.size - 1], 0)}）⇒ 编舞项靠自己闭合，⛔ 它不许乘 seamEnv")
        }
        val lens = listOf(
            Triple("dh", world.dhKy.size, TRIPOD_DH_KX.size),
            Triple("yh", world.yhKy.size, TRIPOD_YH_KX.size),
            Triple("ch", world.chTy.size, TRIPOD_CH_TX.size),
            Triple("esc", world.escTy.size, TRIPOD_ESC_TX.size),
        )
        for ((k, a, g) in lens) if (a != g) {
            w.add("c V$vi $k 表长 $a 与共用网格 $g 对不上 ⇒ hermite 读错位")
        }
        val ph = world.phases
        if (ph[0].t0 != 0.0 || ph[ph.size - 1].t1 != p.loop) {
            w.add("c V$vi 相位表没铺满 [0,${fmt(p.loop, 0)}]（${fmt(ph[0].t0, 1)}→${fmt(ph[ph.size - 1].t1, 1)}）⇒ HUD 分段有空洞")
        }
        for (i in 1 until ph.size) {
            if (ph[i].t0 != ph[i - 1].t1) {
                w.add("c V$vi 相位 ${ph[i - 1].name} 与 ${ph[i].name} 之间" +
                    (if (ph[i].t0 > ph[i - 1].t1) "有 ${fmt(ph[i].t0 - ph[i - 1].t1)} s 空洞"
                     else "重叠（${fmt(ph[i - 1].t1 - ph[i].t0)} s）"))
            }
        }
        return w
    }

    /** 原型 `spin(n, pin)`：走**真实**的 syncRouteEpoch 路径数 8 个纪元的戏份。 */
    private fun spin(n: Int, pin: Int?): List<Int> {
        val p = world.params
        val seq = ArrayList<Int>()
        p.routeVar = pin ?: -1
        for (ep in 0 until n) {
            world.lapN = ep * max(1, p.routeLaps)
            TripodFogMath.syncRouteEpoch(world, true)
            seq.add(world.curVar)
            // ⚠️ 钉死要在**每轮**重写：syncRouteEpoch 会走 applyVariant，只在循环外写一次的话
            //    下一轮就被默认值覆盖，pinned 会假装"只出一套"其实出了整套轮转（原型第一版就漏在这）。
            if (pin != null) p.routeVar = pin
        }
        return seq
    }

    /** 原型 `anchor()`：受害人锚点 `(x, z0)` —— 它是 `gAt(grabT)` / `DH` / `routeMX` 的函数。 */
    private fun anchor(): String {
        val s = TripodGait.crowdTable(world)[0]
        return fmt(s.x, 2) + "/" + fmt(s.z0, 2)
    }

    /**
     * **T28**（原型 html:4766–4930）剧本轮换与结构闸门：覆盖 / 两两不同 / 逐套闭合 / 人群表随戏重建 / ⛔ 状态泄漏。
     *
     * §十二 实测：8 个纪元戏份 **[0,1,2,0,1,2,0,1]**（覆盖 3/3、相邻重复 0）；三套骨架两两 L1 最小
     * **53**（判 >10）；结构前提全绿（接缝位对比度 **4.08%@220 m** ×3、一圈穿越 `d=0` **V0 2 / V1 0 / V2 0**
     * ⇒ ⚠️ 0 与 2 都是偶数，V1/V2 零穿越**合法**）；人群表换戏首呼重建 **1/1/1**、同剧本再呼三次
     * **0/0/0**、计数器自检 **1**；三套受害人锚点 **1.94/−45.40** vs **2.65/−119.14** vs **−6.00/−74.70**；
     * 闸门结束 `CURVAR=0`。
     *
     * 负样本：**A** 钉死 `routeVar=0` ⇒ `[0,0,0,0,0,0,0,0]`；**B** 把 V1 间距表抄成 V0 ⇒ 间距骨架相同；
     * **C1** V1 接缝首末拍到 60 m ⇒ 对比度 **40.1% ≥ 5%**；**C2** `fireT1=LOOP+10` ⇒ `fireEnv` 两端不等；
     * **C3** 偏航末拍 120° ⇒ 首末差 120°；**C4** 相位末段只写到 85 s ⇒ 没铺满 [0,90]；
     * **D** 换戏把接缝表 + `grabT` + 编舞/横向表一起抄成 V0 ⇒ V0/V1 受害人锚点撞车（换戏没换人质）；
     * **E** 强制 `applyVariant(2)` 后 `CURVAR=2 ≠ 0`（抓得到没还原的换戏）。
     */
    @Test
    fun t28_scriptRotationAndPerScriptStructureHold() {
        val p = world.params
        // ── 快照（⚠️ applyRoute 的产物 RA/RP 必须一起管住，否则等于给后面的闸门换了条路线）──
        val svRa = world.ra.copyOf()
        val svRp = world.rp.copyOf()
        val svEpoch = world.routeEpoch
        val svLap = world.lapN
        val svCur = world.curVar
        val svRouteVar = p.routeVar
        val svF0 = p.fireT0
        val svF1 = p.fireT1
        val svG = p.grabT
        val svY = p.yawAmp
        val svBeamPeriod = p.beamPeriod
        fun restore() {
            p.routeVar = svRouteVar
            world.lapN = svLap
            world.routeEpoch = svEpoch
            TripodFogMath.applyVariant(world, svCur, true)
            world.ra = svRa
            world.rp = svRp
            p.fireT0 = svF0
            p.fireT1 = svF1
            p.grabT = svG
            TripodFogMath.setGrabKnots(world)
            TripodFogMath.setYawAmp(world, svY)
            p.beamPeriod = svBeamPeriod
            TripodGait.buildCum(world)
            world.crowdT = null
            world.crowdKey = ""
        }
        try {
            // ── (a) 轮换覆盖 + 相邻不同 ──
            val seq = spin(8, null)
            val kinds = seq.toSortedSet()
            var adj = 0
            for (i in 1 until seq.size) if (seq[i] == seq[i - 1]) adj++
            assertEquals("T28(a) 8 个纪元的戏份 = $seq（原型实测 [0,1,2,0,1,2,0,1]）", listOf(0, 1, 2, 0, 1, 2, 0, 1), seq)
            assertEquals("T28(a) 轮换覆盖只 ${kinds.size}/3 套 ⇒ 有一套戏永远抽不到", 3, kinds.size)
            assertEquals("T28(a) 相邻纪元重复 $adj 次 ⇒ 换了种子还是同一条路", 0, adj)
            // 负样本 A：手动钉死只出一套 ⇒ 证明轮换确实由纪元驱动（⛔ 恒读轮转结果当"覆盖了"）
            val pinned = spin(8, 0)
            val pinnedKinds = pinned.toSortedSet().size
            p.routeVar = -1
            restore()
            assertTrue("T28 负样本 A 假绿：钉死 routeVar=0 竟还出 $pinnedKinds 套戏（seq=$pinned）",
                pinnedKinds == 1 && pinned == listOf(0, 0, 0, 0, 0, 0, 0, 0))

            // ── (b) 骨架两两不同 ──
            val names = TRIPOD_VARS.map { it.name }
            val dhs = TRIPOD_VARS.map { it.dh }
            val yhs = TRIPOD_VARS.map { it.yh }
            val chs = TRIPOD_VARS.map { it.ch }
            val dup0 = dupWhy(names, dhs, yhs, chs)
            assertTrue("T28(b) 三套骨架没两两不同：${dup0.joinToString("；")}", dup0.isEmpty())
            // 反空判：L1 必须是**量出来**的大数（⛔ 判据在两组恒 0 的表上"通过"）
            var minL1 = Double.MAX_VALUE
            for (i in dhs.indices) for (j in i + 1 until dhs.size) {
                minL1 = min(minL1, min(l1(dhs[i], dhs[j]), min(l1(yhs[i], yhs[j]), l1(chs[i], chs[j]))))
            }
            assertTrue("T28(b) 反空判：两两 L1 最小 ${fmt(minL1, 0)} ≤ 10 ⇒ 骨架几乎一样（原型实测最小 53）",
                minL1 > 10)
            // 负样本 B：V1 的间距表抄成 V0（作者忘了改一张表）
            val dupNeg = dupWhy(names, listOf(dhs[0], dhs[0], dhs[2]), yhs, chs)
            assertTrue("T28 负样本 B 假绿：把 V1 间距表抄成 V0 竟没抓到撞车：${joinW(dupNeg)}",
                dupNeg.any { it.contains("V0/V1 间距骨架相同") })

            // ── (c) 每套的结构前提 ──
            val st0 = ArrayList<String>()
            val crossN = IntArray(TRIPOD_VARS.size)
            val seamPct = DoubleArray(TRIPOD_VARS.size)
            for (vi in TRIPOD_VARS.indices) {
                val w = withVariant(vi) {
                    crossN[vi] = world.tcross.n
                    val ky = world.dh.ky
                    seamPct[vi] = TripodFogMath.contrastAt(
                        max(ky[0], ky[ky.size - 1]), p.d0, p, world.palette,
                    ) * 100
                    structWhy(vi)
                }
                st0.addAll(w)
            }
            assertTrue("T28(c) 逐套结构前提未全绿：${st0.joinToString("；")}", st0.isEmpty())
            // §十二 的穿越计数与接缝位对比度（⚠️ 0 与 2 都是偶数 ⇒ V1/V2 零穿越合法）
            assertEquals("T28(c) 一圈穿越 d=0 的次数与 §十二 不符（量到 V0 ${crossN[0]} / V1 ${crossN[1]} / V2 ${crossN[2]}，" +
                "原型实测 2 / 0 / 0）", listOf(2, 0, 0), crossN.toList())
            for (vi in seamPct.indices) {
                assertTrue("T28(c) V$vi 接缝位对比度 ${fmt(seamPct[vi], 2)}% 偏离原型实测 4.08% 太多 ⇒ 雾曲线口径分叉",
                    abs(seamPct[vi] - 4.08) < 0.5)
            }
            // 负样本 C1：V1 接缝首末拍到 60 m（跨圈那两帧整机露出来）
            val stC1 = withVariant(1) {
                val s = world.dhKy
                val m = s.copyOf()
                m[0] = 60.0
                m[m.size - 1] = 60.0
                world.dhKy = m
                world.dh.set(TRIPOD_DH_KX, m)
                val w = structWhy(1)
                world.dhKy = s
                world.dh.set(TRIPOD_DH_KX, s)
                w
            }
            assertTrue("T28 负样本 C1 假绿（接缝位拉到 60 m 必须咬到 c）：${joinW(stC1)}", has(stC1, "c"))
            assertTrue("T28 负样本 C1 必须咬在对比度上：${joinW(stC1)}",
                stC1.any { it.contains("对比度") })
            // 负样本 C2：熄灯窗跨过接缝
            val stC2 = withVariant(0) {
                p.fireT1 = p.loop + 10
                val w = structWhy(0)
                p.fireT1 = svF1
                w
            }
            assertTrue("T28 负样本 C2 假绿（熄灯窗跨过接缝必须咬到 c）：${joinW(stC2)}", has(stC2, "c"))
            assertTrue("T28 负样本 C2 必须咬在 fireEnv 两端不等上：${joinW(stC2)}",
                stC2.any { it.contains("fireEnv") })
            // 负样本 C3：偏航表末拍不归零（跨圈那一帧镜头甩 120°）
            val stC3 = withVariant(0) {
                val s = world.yhKy
                val m = s.copyOf()
                m[m.size - 1] = 120.0
                world.yhKy = m
                TripodFogMath.setYawAmp(world, p.yawAmp)
                val w = structWhy(0)
                world.yhKy = s
                TripodFogMath.setYawAmp(world, p.yawAmp)
                w
            }
            assertTrue("T28 负样本 C3 假绿（偏航末拍 120° 必须咬到 c）：${joinW(stC3)}", has(stC3, "c"))
            assertTrue("T28 负样本 C3 必须咬在偏航首末上：${joinW(stC3)}", stC3.any { it.contains("偏航首末") })
            // 负样本 C4：相位表末段只写到 85 s（HUD 尾段没有高亮）
            val stC4 = withVariant(0) {
                val ph = world.phases
                val last = ph[ph.size - 1]
                world.phases = ph.copyOf().also { it[it.size - 1] = TripodPhase(last.name, last.t0, 85.0) }
                val w = structWhy(0)
                world.phases = ph
                w
            }
            assertTrue("T28 负样本 C4 假绿（相位表没铺满必须咬到 c）：${joinW(stC4)}", has(stC4, "c"))
            assertTrue("T28 负样本 C4 必须咬在相位铺满上：${joinW(stC4)}", stC4.any { it.contains("相位表") })

            // ── (d) 人群表随戏重建 / 同剧本绝不重建 / 计数器自检 ──
            val enter = ArrayList<Int>()
            val rep = ArrayList<Int>()
            for (vi in TRIPOD_VARS.indices) {
                enter.add(withVariant(vi) {
                    val a = world.crowdBuilds
                    TripodGait.crowdTable(world)
                    world.crowdBuilds - a
                })
                rep.add(withVariant(vi) {
                    TripodGait.crowdTable(world)                 // 预热呼（⛔ 不作废本身混进计数）
                    val a = world.crowdBuilds
                    repeat(3) { TripodGait.crowdTable(world) }
                    world.crowdBuilds - a
                })
            }
            assertTrue("T28(d) 换戏首呼重建 = $enter（原型 1/1/1）⇒ 换戏没换人群表 = 换戏没换人质",
                enter == listOf(1, 1, 1))
            assertTrue("T28(d) 同剧本再呼三次重建 = $rep（原型 0/0/0）⇒ 缓存没命中，每帧重算阵容",
                rep == listOf(0, 0, 0))
            // ⚠️ 计数器自检：rep=0 只有"它本来数得到 1"才算数（探针挂错作用域会恒读 0）
            val repSanity = withVariant(0) {
                TripodGait.crowdTable(world)
                val a = world.crowdBuilds
                val savedN = p.crowdN
                p.crowdN = savedN + 1
                TripodGait.crowdTable(world)
                p.crowdN = savedN
                world.crowdBuilds - a
            }
            assertEquals("T28(d) 计数器自检 = $repSanity（应 1）⇒ 重建计数探针恒读 0，上面那条 0/0/0 不算数",
                1, repSanity)
            val anc = ArrayList<String>()
            for (vi in TRIPOD_VARS.indices) anc.add(withVariant(vi, { anchor() }))
            val ancDup = ArrayList<String>()
            for (i in anc.indices) for (j in i + 1 until anc.size) if (anc[i] == anc[j]) ancDup.add("V$i/V$j")
            assertTrue("T28(d) 三套受害人锚点撞车 $ancDup（原型 1.94/−45.40、2.65/−119.14、−6.00/−74.70）" +
                "⇒ 换戏没换人质；量到 $anc", ancDup.isEmpty())
            assertTrue("T28(d) 反空判：锚点表为空 ⇒ crowdTable 没读到受害人", anc.size == 3 && anc.none { it.isEmpty() })
            // 负样本 D：把 V1 的接缝表 + grabT + 编舞/横向表整体抄成 V0（⚠️ 只抄 dh 不够：grabT 一动
            //   Gg=gAt(grabT) 就不同，锚点自然错开 —— 那正是每套自带 grabT 的理由）
            val a0 = withVariant(0, { anchor() })
            val a1copied = withVariant(1) {
                val v0 = TRIPOD_VARS[0]
                try {
                    world.dhKy = v0.dh
                    world.chTy = v0.ch
                    world.escTy = v0.esc
                    world.dh.set(TRIPOD_DH_KX, v0.dh)
                    p.grabT = v0.grabT
                    // ⚠️ 六步重建一步都不能少：TCROSS 必须重扫，否则 `setWanderCenter` 读的是 V1 的
                    //    旧 t1 ⇒ GW 不同 ⇒ `routeMX(Gg)` 不同 ⇒ 锚点"碰巧"没撞车 = 这条负样本假绿。
                    TripodFogMath.scanCrossInto(world)
                    TripodFogMath.setGrabKnots(world)
                    TripodGait.buildCum(world)          // 末尾重跑 setWanderCenter（GW 是新 TCROSS 的函数）
                    world.crowdT = null
                    anchor()
                } finally {
                    world.crowdT = null
                    world.crowdKey = ""
                }
            }
            assertTrue("T28 负样本 D 假绿：V1 的表整体抄成 V0 后锚点仍不撞车（a0=$a0 / copied=$a1copied）",
                a0 == a1copied)

            // ── (e) 闸门状态归位 + 负样本 E（没还原的换戏）──
            val atZero = world.curVar == 0
            TripodFogMath.applyVariant(world, 2, true)
            val leakVar = world.curVar
            val leak = leakVar == 0
            world.curVar = -1                          // ⛔ 绕过 applyVariant 的"同一套就跳过"短路
            TripodFogMath.applyVariant(world, 0, true)
            assertTrue("T28(e) 闸门进入时 curVar=${svCur}，跑完却不在 0 ⇒ 前面有临时换戏没还原", atZero)
            assertTrue("T28 负样本 E 假绿：强制 applyVariant(2) 后 curVar=$leakVar 竟还等于 0 ⇒ 泄漏探针恒绿",
                !leak)
        } finally {
            restore()
        }

        // ── 复位自检：⛔ 把轮换/换戏的状态留给后面的闸门（T12/T2 的数会莫名其妙挪位置）──
        assertEquals("T28 复位后 curVar 必须回到 0", 0, world.curVar)
        assertEquals("T28 复位后 routeVar 必须回到默认 −1（轮转）", -1, p.routeVar)
        assertEquals("T28 复位后 lapN 没还原", svLap, world.lapN)
        assertEquals("T28 复位后 routeEpoch 没还原", svEpoch, world.routeEpoch)
        assertEquals("T28 复位后基波幅值没还原（syncRouteEpoch 会重抽 RA/RP）", svRa[0], world.ra[0], 1e-12)
        assertEquals("T28 复位后 V0 的熄灯窗没还原", 54.5, p.fireT0, 1e-12)
        assertEquals("T28 复位后 V0 的 grabT 没还原", 52.0, p.grabT, 1e-12)
        assertEquals("T28 复位后 yawAmp 倍率没还原", 1.0, p.yawAmp, 1e-12)
        assertEquals("T28 复位后 crowdN 没还原", 8, p.crowdN)
        assertTrue("T28 复位后 V0 的一圈穿越数不是 2（量到 ${world.tcross.n}）⇒ scanCross 没跟着还原",
            world.tcross.n == 2)
    }
}
