package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * E46「迷雾之战 · 雾中三脚巨影」K8 §十五 的**门面 / 头身 / 抓取**闸门：
 * §十二 的 **T14（前景不压主体）、T15（破口不贯穿）、T16（头身耦合）、T19（触手抓人）、T20（贴地雾带不压立面）**，
 * 逐条移植自原型 `docs/e46-tripod-fog-preview.html` 的 `selfTest()`（T14 :3078、T15 :3131、
 * T16 :3180、T19 :3535、T20 :3659）。
 *
 * ## 三条共同的纪律
 * - ⛔ 像素回读：JVM 侧 `Bitmap` 回读要么 `not mocked` 要么恒空 ⇒ 永远绿的假闸门（§十四 1）。
 *   本文件全部走**解析判据 + 烘焙记账**：`shadeA()` / `pr()` / `BldSel` / `CrowdAt` /
 *   `BuildingBmp.carves·skirtFrac·skirtA·featherW` / `TripodFogPlan.bandY·shell*·profOps`。
 * - 判据一律读**画面自己写出来的那份**（⛔ 在闸门里重推公式 = E44 D24）：
 *   T14/T20 的建筑 α 用 `buildings()` 同一个式子、T16(d) 读 `plan.build()` 落盘的 `shellCowl/shellBody`、
 *   T19(b) 调 `TripodFogMath.tendrilTip()`（= `grab()` 画的那条梢点）、T20(a) 读 `plan.bandY`
 *   （= `groundBand()` 自己写进去的四个 Y）。
 * - R12：`TripodGait.sceneState()` 返回的 `legs/bldMid/bldNear/crowd` 是**世界上的预分配缓冲**，
 *   下一次调用即被覆写 ⇒ 需要跨帧比较的一律当场摘抄（见 [measureT19] 的 `prevZ`）。
 *
 * ## ⚠️ 负样本必须改**真参数**（闸门纪律 §十四）
 * 本文件的五条负样本各有一个生产侧旋钮，全部是**只为负样本而 `var`** 的字段（口径同
 * `TripodGait.LEG_AZ`）：`TripodFogBake.Carve.*`（9 个界，T15）、`TripodFogBake.Feather.*`（T20-E）、
 * `TripodBandCfg.zMin/xLo/ink`（T14）、`TripodCrowdBandCfg.fin`（T19-D）。
 * ⛔ 在闸门里覆写场景缓冲（`BldSel.ink`）或复刻生成公式来"代数等价"地绕过它们 —— 那样改的
 *    不是画面读的那份，负样本会**不咬自己的标签**（T14 的第一版就是这么假绿的，
 *    顺带暴露了 `genBuildings` 的两带共轴问题，见该闸门 KDoc）。
 * `xLo` 这类**烘焙期**就读的项，改完必须 `genBuildings()` 用同一批种子重生成再测。
 */
internal class TripodFogFacadeGatesTest {

    private lateinit var world: TripodFogWorld
    private lateinit var table: TripodGateBmpTable
    private lateinit var plan: TripodFogPlan
    private lateinit var persons: Array<TripodFogBake.PersonBmp>

    @Before
    fun setup() {
        world = TripodGateFixtures.boot()
        table = TripodGateBmpTable()
        // ⚠️ 48 栋烘焙位图必须**按对象身份**登记：查不到槽位时 blit() 连记账一起跳过
        //    ⇒ 人群/建筑层的图元数会读到 0 = "没采到"被读成"没落笔"（§十四 1 的 Kotlin 形态）。
        for (b in world.bMid) b.bmp?.let { table.put(it) }
        for (b in world.bNear) b.bmp?.let { table.put(it) }
        persons = TripodFogBake.bakePersons()
        plan = TripodFogPlan(world, table)
        // ⛔ 不注入 persons ⇒ crowd()/grab() 整层早退 ⇒ T19(g) 的图元预算恒 0
        plan.persons = persons
    }

    private fun fmt(x: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", x)

    /** 与 [TripodFogPlan.build] 第一行同式的地平线（`cam.hz` 会被 build 覆写 ⇒ 每次现算）。 */
    private fun hzOf(st: TripodScene): Double = world.cam.h * world.params.horizonK + st.tilt

    // ══════════════════════════════════════ T14 前景不压主体（D19）══════════════════════════════════════

    private inner class T14Scan {
        var ratio = 0.0
        var rT = 0.0
        var rB = 0.0
        var worst = -1.0
        var t = 0.0
        var a = 0.0
        var mach = 0.0
        var d = 0.0
        var over = 0
        var n = 0

        /** 最紧那一帧「最黑的那一栋楼」的构成（诊断用：x / z / 带窗口 / ink）。 */
        var bx = 0.0
        var bz = 0.0
        var bw = 0.0
        var bi = 0.0

        fun where(): String =
            "t=${fmt(t)} s、d=${fmt(d, 0)} m：楼 α=${fmt(a, 3)}" +
                "（x=${fmt(bx, 0)} z=${fmt(bz, 0)} 带窗口=${fmt(bw, 3)} ink=${fmt(bi, 2)}）" +
                " vs 机身 α=${fmt(mach, 3)}，余量 ${fmt(worst, 4)}"
    }

    /**
     * 原型 T14 的 `scan()`（两条正交口径同一次扫描里量完）：
     * (a) 同距离归一 `A / shade(楼距).a = 带窗口·ink` 必须 < 1；
     * (b) `dist ≤ 150 m` 的每一帧，可见近景楼里最黑的那一栋必须**没有机身黑**。
     *
     * 口径与 [TripodFogPlan.buildings] 逐字同源：`A = clamp(shade(楼真距离).a · 带窗口 · 带 ink)`，
     * `A < 0.004` 那条剔除线也要一起跳过（绘制侧 `continue` 的楼，闸门必须也看不见）。
     * 负样本一律走 [withNear] 改**真参数**（⛔ 在闸门里覆写 `BldSel.ink`）。
     */
    private fun scanT14(): T14Scan {
        val p = world.params
        val m = T14Scan()
        TripodGateFixtures.frames(world, 0.25) { t, st ->
            val mach = TripodFogMath.shadeA(st.dist, st.d0, p)
            var mx = 0.0
            var bx = 0.0
            var bz = 0.0
            var bw = 0.0
            var bi = 0.0
            for (i in 0 until st.bldNearN) {
                val b = st.bldNear[i]
                val bd = hypot(b.x, b.z)
                val fogA = TripodFogMath.shadeA(bd, st.d0, p)
                val a = TripodFogMath.clamp(fogA * b.a * b.ink, 0.0, 1.0)
                if (a < 0.004) continue
                val rt = a / fogA
                if (rt > m.ratio) {
                    m.ratio = rt; m.rT = t; m.rB = bd
                }
                if (a > mx) {
                    mx = a; bx = b.x; bz = b.z; bw = b.a; bi = b.ink
                }
            }
            if (st.dist > D_SUBJ_T14) return@frames
            m.n++
            if (mx - mach > m.worst) {
                m.worst = mx - mach; m.t = t; m.a = mx; m.mach = mach; m.d = st.dist
                m.bx = bx; m.bz = bz; m.bw = bw; m.bi = bi
            }
            if (mx >= mach) m.over++
        }
        return m
    }

    /**
     * 原型 :3071–3075 的负样本落法：改 `BAND.near` 的真参数 → `genBuildings()` 用**同一批种子**
     * 重生成 → 重测 → 改回来再重生成（逐字节还原）。
     *
     * ⚠️ `xLo` 是**烘焙期**读的（`mk()` 里 `x = side·lerp(xLo, xHalf, pow06(r))`），⛔ 只改配置
     *    不重生成 = 改了个已定型的表 ⇒ 一条不咬自己标签的假负样本。
     */
    private inline fun <R> withNear(ink: Double, xLo: Double, regen: Boolean, body: () -> R): R {
        val cfg = world.bandNear
        val i0 = cfg.ink
        val x0 = cfg.xLo
        cfg.ink = ink
        cfg.xLo = xLo
        if (regen) TripodFogBake.genBuildings(world, world.params)
        try {
            return body()
        } finally {
            cfg.ink = i0
            cfg.xLo = x0
            if (regen) TripodFogBake.genBuildings(world, world.params)
        }
    }

    /**
     * **T14 前景不压主体（D19）**。
     *
     * 实测（§十二 T14 行）：
     * - (a) 同距离归一最大比 **0.720**（= 带窗口 1.0 × ink 0.72，t=8.3 s、楼距 177 m）< 1，余量 **0.280**；
     * - (b) `d ≤ 150 m` 共 **182** 帧，前景压过主体 **0** 次；最紧一帧 t=83.8 s（d=147 m）
     *   楼 **0.130** vs 机身 **0.137**。
     * - 负样本：近景 ink 0.72→**1.00** ⇒ (a) 归一比 **1.000**（楼与机器一样黑）；
     *   再把 xLo 70→**45** 并重新生成同一批种子（两项一起回退到 D19 之前）⇒ (b) **2/182** 帧压过主体
     *   （最紧一帧楼 **0.219** vs 机身 **0.137**）。
     * - ⚠️ 只把 zMin 42→20 **拉不红**：近景楼的真距离由侧向 xLo 主导（最近那栋 x≈91 m），
     *   z 再小 dist 也在 110 m 以上 ⇒ 负样本必须**连 xLo 一起回退**（原型 :3076 写死了这条）。
     *   ⭐ 这条「最近那栋 x≈91 m」的位置前提同时是 `genBuildings` **两带共用一支 r** 的探测器：
     *    每条带各建一支 Lcg 时近景带会换一批种子（实测读到 x≈157 m 的楼），负样本就咬不红
     *    —— 那次假绿就是这么暴露的，⛔ 把负样本改回"手喂常量"绕过它。
     */
    @Test
    fun t14_foregroundNeverOverpowersSubject() {
        val cfg = world.bandNear
        val pos = scanT14()
        val negI = withNear(1.00, cfg.xLo, regen = false) { scanT14() }
        val negD = withNear(1.00, 45.0, regen = true) { scanT14() }

        assertTrue("T14 帧集只有 ${pos.n} 帧 ≤ 150 m（判 >100，反空判）", pos.n > 100)
        assertTrue("同距离归一比 ${fmt(pos.ratio, 3)} 必须 > 0（近景带一个像素都没画？）", pos.ratio > 0.0)
        assertTrue(
            "同距离归一比 ${fmt(pos.ratio, 3)} ≥ 1（实测应为 0.720 = 带窗口·ink，t=${fmt(pos.rT)} s" +
                "、楼距 ${fmt(pos.rB, 0)} m）⇒ 前景与机器一样黑",
            pos.ratio < 1.0,
        )
        assertEquals(
            "d ≤ 150 m 的 ${pos.n} 帧里前景压过主体 ${pos.over} 次（最紧一帧 ${pos.where()}）",
            0,
            pos.over,
        )
        assertTrue(
            "负样本 A 没咬红：ink→1.00 后归一比只有 ${fmt(negI.ratio, 3)}（判 ≥1.000）",
            negI.ratio >= 1.0,
        )
        assertTrue(
            "负样本 B 没咬红：ink→1.00 + xLo 70→45（重生成同一批种子）后压过主体 ${negD.over}/${negD.n} 帧" +
                "（实测应为 2/182），最紧一帧 ${negD.where()}",
            negD.over > 0,
        )

        // 还原自检：旋钮不留残值，且**重生成回到同一批种子**（最紧一帧必须换回正样本那一帧）
        val back = scanT14()
        assertEquals("还原后归一比必须与正样本一致", fmt(pos.ratio, 6), fmt(back.ratio, 6))
        assertEquals("还原后帧数必须一致", pos.n, back.n)
        assertEquals("还原后最紧一帧必须回到正样本那一帧", fmt(pos.t), fmt(back.t))
        assertEquals(0, back.over)
        assertEquals(0.72, cfg.ink, 1e-12)
        assertEquals(70.0, cfg.xLo, 1e-12)
    }

    // ══════════════════════════════════════ T15 破口不贯穿（R13）══════════════════════════════════════

    /**
     * T15 的度量结果。`why` 由 [judgeCarves]（实例作用域，够得着 [fmt]）填好，
     * 嵌套类自己**不**引用外层实例成员 —— Kotlin 的非 inner 嵌套类拿不到外层 `this`。
     */
    private class T15Judge(
        val n: Int,
        val maxW: Double,
        val maxH: Double,
        val maxArea: Double,
        val badW: Int,
        val badH: Int,
        val badEdge: Int,
        val badArea: Int,
    ) {
        val ok: Boolean get() = badW == 0 && badH == 0 && badEdge == 0 && badArea == 0
        var why: String = ""
    }

    /**
     * 原型 T15 的 `judge(list)`：逐栋逐破口判**归一化矩形**（数据 = `BuildingBmp.carves`，
     * 由 `bakeBuilding` 在挖穿时逐道记录，⛔ 回读像素 —— 与 T11 同一理由）。
     */
    private fun judgeCarves(bmps: List<TripodFogBake.BuildingBmp>): T15Judge {
        var n = 0
        var maxW = 0.0
        var maxH = 0.0
        var maxArea = 0.0
        var badW = 0
        var badH = 0
        var badEdge = 0
        var badArea = 0
        for (bmp in bmps) {
            var area = 0.0
            for (c in bmp.carves) {
                n++
                area += c.w * c.h
                maxW = max(maxW, c.w)
                maxH = max(maxH, c.h)
                if (c.w > LIM_W) badW++
                if (c.h > LIM_H) badH++
                if (c.x < LIM_EDGE || c.x + c.w > 1 - LIM_EDGE) badEdge++
            }
            maxArea = max(maxArea, area)
            if (area > LIM_AREA) badArea++
        }
        val r = T15Judge(n, maxW, maxH, maxArea, badW, badH, badEdge, badArea)
        r.why = listOfNotNull(
            if (badW > 0) "① 宽超限 $badW 道（最大 ${fmt(maxW, 2)} > $fmtLimW）" else null,
            if (badH > 0) "② 高超限 $badH 道（最大 ${fmt(maxH, 2)} > $fmtLimH）" else null,
            if (badEdge > 0) "③ 触边 $badEdge 道" else null,
            if (badArea > 0) "④ 总面积超限 $badArea 栋（最大 ${fmt(maxArea, 3)} > $fmtLimArea）" else null,
        ).joinToString("；")
        return r
    }

    /**
     * **T15 破口不贯穿（R13）**：48 张立面上每一道 destination-out 挖穿都必须有界，
     * 否则透出的余烬地平线会在雾里读成"半透明灰板 + 一排排横向亮条纹"。
     *
     * 实测（§十二 T15 行）：**48** 张立面、**253** 道破口：最大宽 **0.55** ≤ 0.6、最大高 **0.11** ≤ 0.14、
     * 单栋最大挖穿面积 **0.081** ≤ 0.18、触边 **0** 道。
     *
     * ⭐ **负样本改的是 `TripodFogBake.Carve` 这份真烘焙界**（`val`→`var` 的闸门期可变性，
     * 口径与 `TripodGait.LEG_AZ` 同）：改完直接再走一遍公开入口 `bakeBuilding` ⇒ 判据吃的是
     * 生产同一条烘焙路径。⛔ 在闸门里复刻抽取顺序或复刻公式（E44 D24：第二个事实源），
     * 也 ⛔ 只判 `!neg.ok` 而不逐条判 ①②③④ —— 那会让一条界失效时另外三条替它蒙绿。
     *
     * ⚠️ 本条正样本本身**不是恒真**：判据打在烘焙真实记录的矩形上，界一改（或 `py` 的钳制一改）
     * 立刻咬红；另加两条反空判（`n ≥ 张数`、实测 maxW/maxH/maxArea 落在有意义区间）防"没有破口"蒙绿。
     */
    @Test
    fun t15_carvesStayBounded() {
        val all = (world.bMid.toList() + world.bNear.toList()).mapNotNull { it.bmp }
        val pos = judgeCarves(all)

        assertTrue("立面张数 ${all.size}（判 ≥40，反空判）", all.size >= 40)
        assertTrue("破口记录 ${pos.n} 道 < 立面 ${all.size} 张 ⇒ 有的一张都没挖（判 ≥ 张数）", pos.n >= all.size)
        assertTrue(
            "判据界必须容得下烘焙界（Carve.seamW1 ${TripodFogBake.Carve.seamW1} / holeW1" +
                " ${TripodFogBake.Carve.holeW1} / holeH1 ${TripodFogBake.Carve.holeH1} / edge" +
                " ${TripodFogBake.Carve.edge}）：烘焙界一旦越过闸门界，本条必须咬红",
            TripodFogBake.Carve.seamW1 <= LIM_W &&
                TripodFogBake.Carve.holeW1 <= LIM_W &&
                // ⚠️ 缝道的**记账高度** = `seamH / rows`（`rows = max(3, py/15)` ⇒ ≥ 3），
                //    ⛔ 直接拿 `Carve.seamH`(0.18) 比 LIM_H(0.14) —— 那是"意图值"，不是记录值。
                max(TripodFogBake.Carve.holeH1, TripodFogBake.Carve.seamH / 3.0) <= LIM_H &&
                TripodFogBake.Carve.edge >= LIM_EDGE,
        )
        assertTrue(
            "四条判据没全绿：${if (pos.why.isEmpty()) "ok" else pos.why}" +
                "（实测 48 张 / 253 道，应为 maxW 0.55 ≤ 0.60、maxH 0.11 ≤ 0.14、单栋最大面积 0.081 ≤ 0.18、触边 0）",
            pos.ok,
        )
        // 反空判：破口必须是"真的有量"的战损，不是退化的小数
        assertTrue("最大宽只有 ${fmt(pos.maxW, 3)} ⇒ 破口退化，判据在空转", pos.maxW >= 0.20)
        assertTrue("最大高只有 ${fmt(pos.maxH, 3)} ⇒ 破口退化，判据在空转", pos.maxH >= 0.04)
        assertTrue("单栋最大面积只有 ${fmt(pos.maxArea, 3)} ⇒ 密度判据在空转", pos.maxArea >= 0.02)
        assertEquals("触边破口 ${pos.badEdge} 道（应为 0：两端各留 edge）", 0, pos.badEdge)

        // 负样本（原型 :3131 就地改写 `CARVE`）：烘焙界退回第一版 ⇒ **同一批种子**重烘再判
        val c = TripodFogBake.Carve
        val sW0 = c.seamW0
        val sW1 = c.seamW1
        val sE = c.edge
        val sH0 = c.holeW0
        val sH1 = c.holeW1
        val sG0 = c.holeH0
        val sG1 = c.holeH1
        val neg = try {
            c.seamW0 = 0.35; c.seamW1 = 1.0
            c.edge = 0.0
            c.holeW0 = 0.3; c.holeW1 = 1.0
            c.holeH0 = 0.2; c.holeH1 = 0.9
            judgeCarves(
                (world.bMid.toList() + world.bNear.toList()).map {
                    TripodFogBake.bakeBuilding(it, world.params)
                },
            )
        } finally {
            c.seamW0 = sW0; c.seamW1 = sW1; c.edge = sE
            c.holeW0 = sH0; c.holeW1 = sH1; c.holeH0 = sG0; c.holeH1 = sG1
        }
        assertTrue(
            "负样本道数 ${neg.n} < 正样本 ${pos.n} ⇒ 改界后破口反而变少，说明重烘没吃到新界",
            neg.n >= pos.n,
        )
        assertFalse("负样本没咬红：" + neg.why, neg.ok)
        // 四条界各自必须被同一次改界咬到（原型实测 139 / 99 / 83 / 47）
        assertTrue("① 宽界没咬（seamW 0.35~1、holeW 0.3~1 应造出 >$fmtLimW 的道）：" + neg.why, neg.badW > 0)
        assertTrue("② 高界没咬（holeH 0.2~0.9 应造出 >$fmtLimH 的道）：" + neg.why, neg.badH > 0)
        assertTrue("③ 边距界没咬（edge→0 应造出贴边/越界的道）：" + neg.why, neg.badEdge > 0)
        assertTrue("④ 单栋总面积界没咬（应造出 >$fmtLimArea 的栋）：" + neg.why, neg.badArea > 0)

        // 还原自检：旋钮不留残值，且正样本那批位图**没被负样本改写**
        val pos2 = judgeCarves(all)
        assertEquals(0.18, c.seamW0, 1e-12)
        assertEquals(0.55, c.seamW1, 1e-12)
        assertEquals(0.04, c.edge, 1e-12)
        assertEquals(0.22, c.holeW1, 1e-12)
        assertEquals(0.11, c.holeH1, 1e-12)
        assertEquals(pos.n, pos2.n)
        assertEquals(fmt(pos.maxW, 6), fmt(pos2.maxW, 6))
        assertEquals(fmt(pos.maxArea, 6), fmt(pos2.maxArea, 6))
        assertTrue("还原后判据变红：" + pos2.why, pos2.ok)
    }

    // ══════════════════════════════════════ T16 头身耦合（R14）══════════════════════════════════════

    private class T16Res(
        val maxH: Double,
        val tMax: Double,
        val cor: Double,
        val nUns: Int,
        val nSat: Int,
        val tStar: Double,
        val dpStar: Double,
        val gap: Double,
        val hStar: Double,
        val lStar: Double,
        val why: List<String>,
    ) {
        val ok: Boolean get() = why.isEmpty()
    }

    private class T16Seeds(val mx: Double, val at: Int)

    /** 原型 T16 的 `judge(lagK, lagArm, yawA, spinK)`：改四个旋钮重扫一圈，四条判据各抓一种真实失败。 */
    private fun judgeHead(lagK: Double, lagArm: Double, yawA: Double, spinK: Double): T16Res {
        val p = world.params
        val sK = p.headLagK
        val sA = p.headLagArm
        val sY = p.headYawA
        val sS = p.headSpinK
        val d2g = 180.0 / Math.PI
        try {
            p.headLagK = lagK
            p.headLagArm = lagArm
            p.headYawA = yawA
            p.headSpinK = spinK

            var maxH = 0.0
            var tMax = 0.0
            var tStar = 0.0
            var dpStar = 0.0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            var n = 0
            var nSat = 0
            TripodGateFixtures.frames(world, 0.1) { t, st ->
                val x = st.headYaw * d2g
                val y = -st.dPsiLag * d2g
                if (abs(x) > maxH) {
                    maxH = abs(x); tMax = t
                }
                // 机身转弯剧烈程度（°/每米行程）—— 用来找"转弯最猛那一帧"
                val dp = (world.routePSI(st.g + 0.5) - world.routePSI(st.g - 0.5)) * d2g
                if (abs(dp) > abs(dpStar)) {
                    dpStar = dp; tStar = t
                }
                // ⭐ R17：相关只在**未触限幅**的帧上求（限幅帧是构造级保证，混进去等于拿一串常数拟合斜率）
                if (abs(y) > p.headLagCap) {
                    nSat++
                    return@frames
                }
                sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y
                n++
            }
            val den = sqrt((sxx - sx * sx / n) * (syy - sy * sy / n))
            val cor = if (den == 0.0) 0.0 else (sxy - sx * sy / n) / den

            // ⚠️ stS 与 stM 共用同一份缓冲 ⇒ 标量必须先落地再取下一帧（R12）
            val stS = TripodGait.sceneState(world, tStar)
            val hStar = stS.headYaw * d2g
            val lStar = -stS.dPsiLag * d2g
            // ⭐ SHELL 读的是发射器**这一帧真的用出去的**轮廓旋转角 ⇒ 必须 build 到 |headYaw| 最大的那一帧
            plan.build(TripodGait.sceneState(world, tMax))
            val gap = abs(plan.shellCowl - plan.shellBody) * d2g

            val limA = p.rollA + 1
            val why = ArrayList<String>()
            if (!(maxH <= limA)) why.add("a 幅值 ${fmt(maxH, 2)}° > ${fmt(limA, 1)}°")
            if (!(n >= 200)) why.add("b 未触限幅的转弯帧只有 $n 个 < 200（相关系数是几个瞬间的巧合）")
            if (!(cor >= LIM_B_T16)) why.add("b 相关系数 ${fmt(cor, 2)} < $LIM_B_T16")
            if (!(hStar * lStar > 0 && abs(hStar) >= LIM_C_T16)) {
                why.add(
                    "c t*=${fmt(tStar)} s 处 headYaw ${fmt(hStar, 2)}° 与滞后信号 ${fmt(lStar, 2)}°" +
                        "不同号或过小",
                )
            }
            if (!(gap < LIM_D_T16)) why.add("d 顶罩轮廓比甲壳多转 ${fmt(gap, 2)}°")
            return T16Res(maxH, tMax, cor, n, nSat, tStar, dpStar, gap, hStar, lStar, why)
        } finally {
            p.headLagK = sK
            p.headLagArm = sA
            p.headYawA = sY
            p.headSpinK = sS
        }
    }

    /**
     * 原型 T16 的 `seedScan()`：6 个随机路线种子下的 `max|headYaw|`（限幅是**构造级**保证，
     * ⛔ 只测当前种子 = 把判据退化成"碰巧这条路线转弯不猛"）。种子扫描完必须还原 `ra/rp`。
     */
    private fun headSeedScan(count: Int): T16Seeds {
        val p = world.params
        val savedRa = world.ra.copyOf()
        val savedRp = world.rp.copyOf()
        val d2g = 180.0 / Math.PI
        try {
            var mx2 = 0.0
            var at = 0
            for (sd in 1..count) {
                TripodFogMath.applyRoute(world, p.routeSeed0 + sd * 53)
                var v = 0.0
                TripodGateFixtures.frames(world, 0.2) { _, st ->
                    v = max(v, abs(st.headYaw) * d2g)
                }
                if (v > mx2) {
                    mx2 = v; at = sd
                }
            }
            return T16Seeds(mx2, at)
        } finally {
            world.ra = savedRa
            world.rp = savedRp
        }
    }

    /**
     * **T16 头身耦合（R14）**：机头必须滞后于机身转弯、幅值不超过躯干横滚 +1°、顶罩不随头自转。
     *
     * 实测（§十二 T16 行）：`max|headYaw|` **3.25° ≤ 5.0°**（rollA 4°+1）；
     * corr(headYaw, −dψ滞后) **1.000 ≥ 0.5**，在 **860** 帧未触限幅的转弯上求得，
     * 另 **41** 帧被 headLagCap=25° 钉住剔出样本；转弯最猛 t\*=**9.2 s**（dψ/dG −18.11°/m）处
     * headYaw **2.21°** 与滞后信号 **16.99°** 同号；顶罩与甲壳轮廓旋转差 **0.000° < 0.01°**；
     * 6 个随机路线种子最大 **3.25°**。
     * 负样本：A 旧独立振荡（lagK=0、yawA=7）⇒ 幅值 **7.00°**、corr **−0.01**；
     * B 负滞后（头**领**着机身转）⇒ corr **−1.00** 且 c 项 −2.21°；C 全额滞后 lagK=1 ⇒ **25.00°**；
     * D 顶罩自转 spinK=1 ⇒ 多转 **+1.63°**；E 拆限幅 headLagCap=90 ⇒ 种子最大 **9.60°**。
     */
    @Test
    fun t16HeadIsCoupledToTheBody() {
        val p = world.params
        val pos = judgeHead(p.headLagK, p.headLagArm, p.headYawA, p.headSpinK)
        val seedPos = headSeedScan(6)
        val savedCap = p.headLagCap
        val seedNeg = try {
            p.headLagCap = 90.0
            headSeedScan(6)
        } finally {
            p.headLagCap = savedCap
        }
        val negA = judgeHead(0.0, p.headLagArm, 7.0, 0.0)
        val negB = judgeHead(-p.headLagK, p.headLagArm, p.headYawA, 0.0)
        val negC = judgeHead(1.0, p.headLagArm, p.headYawA, 0.0)
        val negD = judgeHead(p.headLagK, p.headLagArm, p.headYawA, 1.0)
        plan.build(TripodGait.sceneState(world, 0.0))   // 复位 SHELL，⛔ 把负样本残值留给后续闸门

        assertEquals(
            "正样本四条判据没全绿（实测应为 max|headYaw| 3.25°、corr 1.000、gap 0.000°）：" +
                pos.why.joinToString("；"),
            emptyList<String>(),
            pos.why,
        )
        assertTrue(
            "6 个随机路线种子里最大 ${fmt(seedPos.mx, 2)}°（种子 ${seedPos.at}）越出 ${fmt(p.rollA + 1, 1)}°",
            seedPos.mx <= p.rollA + 1,
        )
        assertTrue(
            "负样本 E 没咬红：拆掉 headLagCap→90 后种子最大仍只有 ${fmt(seedNeg.mx, 2)}°（判 >${fmt(p.rollA + 1, 1)}）",
            seedNeg.mx > p.rollA + 1,
        )
        for ((name, r) in listOf("A 旧独立振荡" to negA, "B 头领转身" to negB, "C 全额滞后" to negC, "D 顶罩自转" to negD)) {
            val expect = when {
                name.startsWith("A") -> "a/b"
                name.startsWith("B") -> "b/c"
                name.startsWith("C") -> "a"
                else -> "d"
            }
            assertFalse("负样本 $name 没咬红（应报 $expect）：${r.why.joinToString("；")}", r.ok)
        }
        // 还原自检
        assertEquals(0.13, p.headLagK, 1e-12)
        assertEquals(7.0, p.headLagArm, 1e-12)
        assertEquals(0.0, p.headYawA, 1e-12)
        assertEquals(0.0, p.headSpinK, 1e-12)
        assertEquals(25.0, p.headLagCap, 1e-12)
        val after = judgeHead(p.headLagK, p.headLagArm, p.headYawA, p.headSpinK)
        assertEquals("还原后必须重新全绿：" + after.why.joinToString("；"), emptyList<String>(), after.why)
        assertEquals(fmt(pos.maxH, 4), fmt(after.maxH, 4))
    }

    // ══════════════════════════════════════ T19 触手抓人（R18）══════════════════════════════════════

    private class T19P {
        var runs = 0
        var cur = 0
        var runA = 0.0
        var runB = 0.0
        var len = 0.0
        var runEnd = 0.0
        var r0 = 0.0
        var rN = 0.0
        var gap = 0.0
        var aMin = 9.0
        var hpMin = 9e9
        var dxMax = 0.0
        var gF = 0
        var out = 0
        var seamN = 0
        var seamA = 0.0
        var vSeamA = 0.0
        var soakA = 0.0
        var opsMax = 0
    }

    /** 与 [TripodFogPlan] 的 `grab()` 逐参同构的缆须上下文（⛔ 在闸门里另推"该伸多长"）。 */
    private fun tendrilCtx(st: TripodScene): TripodFogMath.TendrilCtx {
        val p = world.params
        val v = st.crowd[0]
        return TripodFogMath.TendrilCtx(
            st.bodyLat, st.leanFwd, st.bodyY, st.psi, st.d, st.mx,
            p.bodyRx, p.personH, p.tendReach, v.x, v.y, v.z, st.grab.reach,
        )
    }

    /** 原型 `crowdOpsAt(t).total`：人群 + 抓取缆须 + 梢钩三类图元之和（画面那一帧真的付了多少）。 */
    private fun crowdGrabOps(): Int =
        plan.profOps[TripodOpItem.CROWD.ordinal] +
            plan.profOps[TripodOpItem.GRAB_TENDRIL.ordinal] +
            plan.profOps[TripodOpItem.GRAB_HOOK.ordinal]

    /**
     * 原型 T19 的 `measure()`：`DT = 1/30`，逐帧读 `st.grab` / `st.crowd` / `tendrilTip`，
     * 抓取段内另跑一次完整发射取图元预算。
     *
     * ⚠️ 投影口径用 `st.yaw`（`crowd()` 画的就是它），⛔ 用 `st.psi`：原型 T19 的 `pr(..., st.psi)`
     * 与它自己声明的"读画面的函数"纪律冲突（`drawCrowd` 用的是 `st.yaw`）⇒ 以 Kotlin 绘制侧为准。
     */
    private fun measureT19(): T19P {
        val p = world.params
        val cam = world.cam
        val dt = 1.0 / 30.0
        val n = (p.loop / dt).roundToInt()
        val m = T19P()
        val prevZ = DoubleArray(32)
        val curZ = DoubleArray(32)
        var prevReady = false
        var i = 0
        while (i <= n) {
            val t = i * dt
            val st = TripodGait.sceneState(world, t)
            val r = st.grab.reach
            val lift = st.grab.lift
            if (i == 0) m.r0 = r
            if (i == n) m.rN = r
            val on = r > 0.02
            if (on && m.cur == 0) {
                m.runs++; m.runA = t
            }
            if (!on && m.cur > 0) {
                m.runB = t
                m.len = max(m.len, m.runB - m.runA)
            }
            m.cur = if (on) m.cur + 1 else 0
            if (on) {
                plan.build(st)
                m.opsMax = max(m.opsMax, crowdGrabOps())
            }
            // 回收跨越：与上一帧同坑位的 z 作差（R12 ⇒ 自己维护 prevZ，⛔ 留住 st.crowd 引用）
            for (k in 0 until st.crowdN) {
                val q = st.crowd[k]
                curZ[k] = q.z
                if (prevReady && q.z - prevZ[q.i] < -world.crowdBand.span * 0.5) {
                    m.seamN++
                    if (q.a > m.seamA) m.seamA = q.a
                }
            }
            val v = st.crowd[0]
            if (i == 0 || i == n) m.vSeamA = max(m.vSeamA, v.a)
            if (lift >= 0.98) m.soakA = max(m.soakA, v.a)
            // ⭐ 抓取峰 = 缆须伸到头 **且人还没离开地面**：三件事必须同一窗里同时成立
            if (r >= 0.99 && lift <= 0.2) {
                m.gF++
                val ctx = tendrilCtx(st)
                for (u in T19_U) {
                    val tp = TripodFogMath.tendrilTip(ctx, u)
                    m.gap = max(m.gap, tp.D - tp.L)
                }
                val q = TripodFogMath.pr(cam, p, v.x, v.y, v.z, st.yaw)
                val bc = TripodFogMath.bodyFrame(0.0, 0.5, st.bodyY, st.psi, st.d, st.mx)
                val bp = TripodFogMath.pr(cam, p, bc.x, bc.y, bc.z, st.yaw)
                if (q == null || bp == null || q.X < 0 || q.X > cam.w || q.Y < 0 || q.Y > cam.h) {
                    m.out++
                } else {
                    val bmp = persons[v.kind.coerceIn(0, TripodSlot.PERSON_N - 1)]
                    m.hpMin = min(m.hpMin, p.personH * bmp.bh * cam.ppm * q.s)
                    m.aMin = min(m.aMin, v.a)
                    m.dxMax = max(m.dxMax, abs(q.X - bp.X))
                }
            }
            System.arraycopy(curZ, 0, prevZ, 0, st.crowdN)
            prevReady = true
            i++
        }
        m.runEnd = m.runB
        return m
    }

    /** 原型 T19 的 `why(p)`：十四条判据，一条一个缺陷（⛔ 一个缺陷两张红牌）。 */
    private fun whyT19(p: T19P, t1: Double): List<String> {
        val w = ArrayList<String>()
        if (p.r0 != p.rN) {
            w.add(
                "a reach(0)=${fmt(p.r0, 3)} ≠ reach(LOOP)=${fmt(p.rN, 3)}" +
                    " ⇒ 抓取动作不随循环闭合，下一圈从半空中接",
            )
        }
        if (p.runs != 1) w.add("a 一圈内 reach>0.02 的连续段有 ${p.runs} 次（应恰好 1 次 = 一轮一次）")
        if (!(p.len >= 1.0)) w.add("a 抓取段只 ${fmt(p.len, 2)} s < 1 s（判据空转 / 抓太快看不见）")
        if (!(p.runEnd <= t1 - 1)) {
            w.add(
                "a 抓取段收尾于 t=${fmt(p.runEnd)} s，晚于掠过位 t1−1=${fmt(t1 - 1)} s" +
                    " ⇒ 和贴身掠过/回头揭示撞车",
            )
        }
        if (!(p.gF * DT_T19 >= 0.5)) {
            w.add("b 峰值抓取窗只有 ${fmt(p.gF * DT_T19, 2)} s（伸到头且人还在地面）⇒ 这一项没东西可判")
        }
        if (!(p.gap <= 0.8)) {
            w.add(
                "b 峰值时缆须梢离人还差 ${fmt(p.gap, 1)} m > 0.8 m" +
                    " ⇒ 解剖够不着（tendReach 与 grabDz 不匹配）",
            )
        }
        if (p.out > 0) w.add("c 峰值抓取窗内有 ${p.out}/${p.gF} 帧受害人不在画面上")
        if (!(p.hpMin >= 12.0)) w.add("c 受害人最矮 ${fmt(p.hpMin)} px < 12 px ⇒ 读不出那是个人")
        if (!(p.aMin >= 0.10)) w.add("c 受害人最淡 α=${fmt(p.aMin, 3)} < 0.10 ⇒ 雾里看不见谁被抓住")
        if (!(p.dxMax <= TripodGateFixtures.W * 0.25)) {
            w.add(
                "c 受害人与机身中心水平相距 ${fmt(p.dxMax, 0)} px > 0.25 屏宽" +
                    " ⇒ 缆须伸向画面别处，抓的不是他",
            )
        }
        // ⚠️ d 只取**两端点本身**：圈尾 lift 已 =1，那一格的 α 归 f 管（一个缺陷一张红牌）
        if (!(p.vSeamA < 0.02)) {
            w.add(
                "d 圈缝端点（t=0 / t=90）处受害人 α=${fmt(p.vSeamA, 3)} ≥ 0.02" +
                    " ⇒ 抓取在接缝处被看见：人被拎在腹下跨进新一圈，或新一圈开场凭空多出一个人",
            )
        }
        if (!(p.seamN >= world.params.crowdN)) {
            w.add("e 一圈内只检测到 ${p.seamN} 次回收跨越（人数 ${world.params.crowdN}）⇒ 该判据空转")
        }
        if (!(p.seamA < 0.02)) {
            w.add(
                "e 回收接缝那一帧最亮的人 α=${fmt(p.seamA, 4)} ≥ 0.02" +
                    " ⇒ 人群在远处瞬间\"摆渡\"回近处（瞬移）",
            )
        }
        if (!(p.soakA < 0.02)) {
            w.add("f lift≥0.98（人已到腹下）时受害人 α=${fmt(p.soakA, 4)} ≥ 0.02 ⇒ 人被挂在外壳上，⛔ 被吸收")
        }
        if (!(p.opsMax <= 14)) {
            w.add(
                "g 人群+抓取层单帧最多付掉 ${p.opsMax} 个图元 > 14" +
                    " ⇒ 创维那类设备上的 disjoint 图元数超预算（默认 8 人应为 8+4）",
            )
        }
        return w
    }

    /**
     * **T19 触手抓人（R18）**：一轮一次 + 缆须真够得着 + 受害人可辨 + 圈缝/回收/吸收三处不残留 + 图元预算。
     *
     * 实测（§十二 T19 行）：抓取段 **t=48.9→56.2 s（7.30 s，一圈 1 次）**，掠过位 t1=59.6 s；
     * `reach(0)=reach(LOOP)=0.000`；峰值窗（reach≥0.99 且 lift≤0.2）**0.87 s**，缆须梢距人最大
     * **0.00 m**；同窗受害人屏高 ≥**21.6 px**、α ≥**0.455**、与机身中心水平最大相距 **25 px**
     * （出画 **0/26** 帧）；圈缝受害人 α **0.0032**；回收跨越 **9** 次、接缝帧最亮 α **0.0016**；
     * lift≥0.98 时 α **0.0041**；人群+抓取层单帧最大图元 **10 ≤ 14**。
     * 负样本：A grabT→30 s（时间轴随 `setGrabKnots` 一起挪）⇒ **26/26 帧出画** + 圈缝 α **0.418**；
     * B tendReach→18 m ⇒ 梢点差 **17.3 m**；C crowdSoak→1（不溶解）⇒ 接缝 α **0.031**、腹下 α **0.848**；
     * D 人群带近端淡入拆掉 ⇒ 接缝 α **0.839**；E crowdN→24 ⇒ **26 图元 > 14**；
     * F reach 末关键帧→0.6 ⇒ `reach(0)=0.000 ≠ reach(LOOP)=0.600` 且跑出 **2** 段。
     *
     * ⭐ 负样本 D 改的是真旋钮 `TripodCrowdBandCfg.fin`（`val`→`var` 的闸门期可变性）：
     * `crowdWindow(z)` 每次调用现读 ⇒ 改完直接重扫一圈即可，⛔ 在闸门里往场景缓冲乘系数
     * （第一版走的是"代数等价"的缓冲覆写，那不是画面读的那份 ⇒ 负样本不咬自己的标签）。
     */
    @Test
    fun t19TendrilGrabReadsAsOneMachine() {
        val p = world.params
        val t1 = world.tcross.t1      // boot 里 syncRouteEpoch(true) → applyVariant → scanCrossInto 已算好

        val pos = measureT19()
        val pw = whyT19(pos, t1)

        val savedGrabT = p.grabT
        val aw = try {
            p.grabT = 30.0
            TripodFogMath.setGrabKnots(world)
            whyT19(measureT19(), t1)
        } finally {
            p.grabT = savedGrabT
            TripodFogMath.setGrabKnots(world)
        }
        val savedReach = p.tendReach
        val bw = try {
            p.tendReach = 18.0
            whyT19(measureT19(), t1)
        } finally {
            p.tendReach = savedReach
        }
        val savedSoak = p.crowdSoak
        val cw = try {
            p.crowdSoak = 1.0
            whyT19(measureT19(), t1)
        } finally {
            p.crowdSoak = savedSoak
        }
        val savedFin = world.crowdBand.fin
        val dw = try {
            world.crowdBand.fin = 0.0            // D：人群带近端淡入拆掉
            whyT19(measureT19(), t1)             // 原型实测接缝 α 0.839
        } finally {
            world.crowdBand.fin = savedFin
        }
        val savedN = p.crowdN
        val ew = try {
            p.crowdN = 24
            whyT19(measureT19(), t1)
        } finally {
            p.crowdN = savedN
        }
        val savedTail = world.gren.ky[world.gren.ky.size - 1]
        val fw = try {
            world.gren.ky[world.gren.ky.size - 1] = 0.6
            whyT19(measureT19(), t1)
        } finally {
            world.gren.ky[world.gren.ky.size - 1] = savedTail
        }

        assertEquals(
            "正样本十四条判据没全绿（实测应为抓取段 48.9→56.2 s、峰值窗 0.87 s、gap 0.00 m、" +
                "hp 21.6 px、α 0.455、dx 25 px、圈缝 0.0032、跨越 9 次、接缝 0.0016、腹下 0.0041、图元 10）：" +
                pw.joinToString("；"),
            emptyList<String>(),
            pw,
        )
        assertTrue("抓取段最大图元 ${pos.opsMax}（判 >0：0 = crowd()/grab() 整层早退，判据在空转）", pos.opsMax > 0)
        assertTrue("峰值抓取窗只有 ${pos.gF} 帧 ⇒ (c) 三项没有样本", pos.gF > 0)
        for ((name, w) in listOf(
            "A 抓取时刻→30 s" to aw,
            "B 缆须极限→18 m" to bw,
            "C 溶解起点→1（不溶解）" to cw,
            "D 人群带近端淡入拆掉" to dw,
            "E 人数→24" to ew,
            "F reach 末关键帧→0.6" to fw,
        )) {
            assertTrue(
                "负样本 $name 没咬红（⚠️ 假负样本）",
                w.isNotEmpty(),
            )
        }

        // 还原自检：六个旋钮都不留残值
        val back = measureT19()
        assertEquals("还原后必须重新全绿：" + whyT19(back, t1).joinToString("；"), emptyList<String>(), whyT19(back, t1))
        assertEquals(fmt(pos.runA, 3), fmt(back.runA, 3))
        assertEquals(pos.runs, back.runs)
        assertEquals(pos.opsMax, back.opsMax)
        assertEquals(52.0, p.grabT, 1e-12)
        assertEquals(46.0, p.tendReach, 1e-12)
        assertEquals(0.72, p.crowdSoak, 1e-12)
        assertEquals(8, p.crowdN)
        assertEquals(0.0, world.gren.ky[world.gren.ky.size - 1], 1e-12)
        assertEquals(35.0, world.crowdBand.fin, 1e-12)
    }

    // ══════════════════════════════════════ T20 贴地雾带 / 雾裙（R19）══════════════════════════════════════

    private class T20Sweep {
        var cur = 0
        var curT = 0.0
        var curFrames = 0
        var old = 0
        var oldT = 0.0
        var oldFrames = 0
        var top = 0.0
        var lo = 0.0
        var hi = 0.0
        var hzAtRows = 0.0
        var vis = 0
        var aMax = 0.0
        var aSum = 0.0
        var aN = 0
    }

    private class T20Skirt {
        var n = 0
        var lo = 1.0
        var hi = 0.0
        var ratio = 9.0
        var fLo = 1.0
        var fHi = 0.0
    }

    /** 每帧入选立面里被 `[yLo, yHi]` 整段切到的栋数（与 `buildings()` 同一条早退口径）。 */
    private fun cutFacades(st: TripodScene, yLo: Double, yHi: Double): Int {
        val p = world.params
        val cam = world.cam
        var n = 0
        for (band in 0 until 2) {
            val count = if (band == 0) st.bldMidN else st.bldNearN
            val list = if (band == 0) st.bldMid else st.bldNear
            for (i in 0 until count) {
                val b = list[i]
                if (b.a <= 0.002 || b.z < 2) continue
                val q0 = TripodFogMath.pr(cam, p, b.x, 0.0, b.z, st.yaw) ?: continue
                val q1 = TripodFogMath.pr(cam, p, b.x, b.h, b.z, st.yaw) ?: continue
                if (q0.Y - q1.Y < 0.5) continue
                val wpx = b.w * cam.ppm * q0.s
                val x = q0.X - wpx / 2
                if (x + wpx < 0 || x > cam.w) continue
                if (q0.Y > yLo && q1.Y < yHi) n++
            }
        }
        return n
    }

    /**
     * 负样本 A 的**旧锚**（R19 之前的写法：`bh = 0.16H`、带顶 `hz − 0.45bh`、渐变高 `2.35bh`、峰在第 35%），
     * 半峰核心的算法与 `groundBand()` 一致。⛔ 这个数**别抄回绘制函数** —— 它只活在这一条负样本里。
     */
    private fun oldBandCore(hz: Double): DoubleArray {
        val bh = world.cam.h * 0.16
        val y0 = hz - bh * 0.45
        val sp = bh * 2.35
        val pk = 0.35
        return doubleArrayOf(y0 + sp * (pk / 2), y0 + sp * (pk + (1 - pk) / 2))
    }

    /**
     * 原型 T20 的 `sweep()`：每帧先 `plan.build(st)`（⭐ 让发射器把**这一帧真的用出去**的 `bandY` 写出来，
     * 与原型 `render(st)` 写 `BANDY` 同一口径），再用新锚 / 旧锚各切一次立面，并顺带量绘制 α 的真实分布。
     */
    private fun sweepT20(): T20Sweep {
        val p = world.params
        val cam = world.cam
        val r = T20Sweep()
        TripodGateFixtures.frames(world, 0.25) { t, st ->
            cam.hz = hzOf(st)
            plan.build(st)
            r.vis += st.bldMidN + st.bldNearN
            r.top = plan.bandY.top
            r.lo = plan.bandY.lo
            r.hi = plan.bandY.hi
            r.hzAtRows = cam.hz
            val c = cutFacades(st, plan.bandY.lo, plan.bandY.hi)
            val oc = oldBandCore(cam.hz)
            val o = cutFacades(st, oc[0], oc[1])
            // ⭐ 与 buildings() 同一个式子（⛔ 另推一份）：这是"玻璃盒子"判据的分母
            for (band in 0 until 2) {
                val count = if (band == 0) st.bldMidN else st.bldNearN
                val list = if (band == 0) st.bldMid else st.bldNear
                for (i in 0 until count) {
                    val b = list[i]
                    if (b.a <= 0.002 || b.z < 2) continue
                    val a = TripodFogMath.clamp(
                        TripodFogMath.shadeA(hypot(b.x, b.z), st.d0, p) * b.a * b.ink, 0.0, 1.0,
                    )
                    r.aMax = max(r.aMax, a)
                    r.aSum += a
                    r.aN++
                }
            }
            if (c > 0) r.curFrames++
            if (c > r.cur) {
                r.cur = c; r.curT = t
            }
            if (o > 0) r.oldFrames++
            if (o > r.old) {
                r.old = o; r.oldT = t
            }
        }
        return r
    }

    /** 原型 T20 的 `skirt()`：裙/羽化只看烘焙期写下的分布与"这一帧有没有被选中"，⛔ 不必跑 build。 */
    private fun skirtScan(): T20Skirt {
        val r = T20Skirt()
        TripodGateFixtures.frames(world, 0.25) { _, st ->
            for (band in 0 until 2) {
                val count = if (band == 0) st.bldMidN else st.bldNearN
                val list = if (band == 0) st.bldMid else st.bldNear
                for (i in 0 until count) {
                    val b = list[i]
                    if (b.skirtFrac <= 0.0) continue     // 烘焙期没落笔的（强度 0 / 厚度 0）记 0
                    r.n++
                    r.lo = min(r.lo, b.skirtFrac)
                    r.hi = max(r.hi, b.skirtFrac)
                    r.ratio = min(r.ratio, 1 / (1 - min(0.999, b.skirtA)))
                    val fw = b.src?.bmp?.featherW ?: 0.0
                    r.fLo = min(r.fLo, fw)
                    r.fHi = max(r.fHi, fw)
                }
            }
        }
        return r
    }

    /** 原型 T20 的 `why(sw, sk)`。 */
    private fun whyT20(sw: T20Sweep, sk: T20Skirt): List<String> {
        val p = world.params
        val bad = ArrayList<String>()
        if (p.groundBand <= 0.001) {
            bad.add(
                "a 贴地雾带强度=${fmt(p.groundBand, 2)} ⇒ 带体根本没画，本闸门无对象" +
                    "（⛔ 把\"没有雾带\"读成\"雾带不压楼\"）",
            )
        }
        if (sw.cur > 0) {
            bad.add(
                "a 亮带半峰核心在 t=${fmt(sw.curT)} s 压在 ${sw.cur} 栋在画立面上" +
                    "（全圈 ${sw.curFrames} 帧有压楼）⇒ 楼读成同一行被切齐",
            )
        }
        if (sk.n == 0) {
            bad.add(
                "b 全圈 ${SKIRT_N} 次采样里**没有一栋**立面带雾裙（厚度 ${fmt(p.skirtH)} m、" +
                    "强度 ${fmt(p.skirtA, 2)}）⇒ 楼底与地面之间没有过渡",
            )
        } else {
            if (!(sk.lo >= 0.12 && sk.hi <= 0.60)) {
                bad.add(
                    "b 裙高占立面 ${fmt(sk.lo, 3)}→${fmt(sk.hi, 3)} 越出 [0.12, 0.60]" +
                        " ⇒ 楼要么裙太薄看不见、要么整栋被抹掉",
                )
            }
            if (sk.ratio < 1.6) {
                bad.add("c 立面顶/底 α 比 ${fmt(sk.ratio, 2)} < 1.6 ⇒ 立面上下一样透，还是\"半透明玻璃盒子\"")
            }
        }
        if (!(sk.fLo >= 0.04 && sk.fHi <= 0.12)) {
            bad.add(
                "e 边缘羽化占立面宽 ${fmt(sk.fLo, 3)}→${fmt(sk.fHi, 3)} 越出 [0.04, 0.12]" +
                    " ⇒ 要么还是硬直角边（读成卡片），要么楼被羽化吃掉大半幅",
            )
        }
        return bad
    }

    /**
     * **T20 贴地雾带不压立面 + 雾裙/边缘羽化烘进立面（R19）**。
     *
     * 实测（§十二 T20 行）：亮带顶 / 半峰核心 = **hz+13.6 / hz+44.8~hz+156.4 px**
     * （锚在 `z = 105 m` 的楼基线，带体起点在地平线以下 13.6 px）；全圈压在立面 **0 栋 / 0 帧**；
     * 裙高占立面 **0.181→0.478**（世界 8 m ÷ 楼高 16~46 m）、顶/底 α 比 **1.82 ≥ 1.6**；
     * 羽化占立面宽 **0.070**；带雾裙的在画立面 **10830 / 10830 栋次**（两次采样帧距同为 1/4 s，
     * ⛔ 把"没采到"读成"没有裙"）；绘制 α 上限 **0.130**、均值 **0.028**（10830 栋次）——
     * 量级由雾律基线 D0=70（随 energy ±24%）决定 ⇒ "玻璃"感的主因是**硬直角边**而不是透明度，
     * 羽化才是对因的手术。
     *
     * 负样本：A 带顶锚回 R19 之前（`hz−0.072H` 起、峰在 `hz+0.060H`）⇒ 同一判定式压楼最多
     * **19 栋 / 263 帧**（最坏 t=87.0 s）；B `skirtA→0.20` ⇒ 顶/底比 **1.25 < 1.6**；
     * C `skirtH→25 m` ⇒ 裙高 **0.567→0.850** 越界；D `skirtH→0` ⇒ **0/361** 采样带裙；
     * E `FEATHER.w→0` 重烘 ⇒ 羽化 **0.000** 越界。
     *
     * ⭐ 五条负样本改的都是**真旋钮**：A 是解析式的旧锚（同一判定式换带体），B/C/D 用
     * `TripodParams.skirtA/skirtH`（`var`），E 用 `TripodFogBake.Feather.w`（`val`→`var` 的闸门期
     * 可变性，口径同 `Carve`）。改完一律走公开入口 `TripodFogBake.genBuildings()` 重烘
     * （与原型同一份 `mk()`，⛔ 复刻），`finally` 里还原再重烘 ⇒ 同一批种子逐字节回来。
     */
    @Test
    fun t20GroundBandDoesNotCutFacades() {
        val p = world.params
        val sw = sweepT20()
        val sk0 = skirtScan()
        val pw = whyT20(sw, sk0)

        val savedA = p.skirtA
        val savedH = p.skirtH
        val bw = try {
            p.skirtA = 0.20
            TripodFogBake.genBuildings(world, p)
            whyT20(sw, skirtScan())
        } finally {
            p.skirtA = savedA
            TripodFogBake.genBuildings(world, p)
        }
        val cw = try {
            p.skirtH = 25.0
            TripodFogBake.genBuildings(world, p)
            whyT20(sw, skirtScan())
        } finally {
            p.skirtH = savedH
            TripodFogBake.genBuildings(world, p)
        }
        val dw = try {
            p.skirtH = 0.0
            TripodFogBake.genBuildings(world, p)
            whyT20(sw, skirtScan())
        } finally {
            p.skirtH = savedH
            TripodFogBake.genBuildings(world, p)
        }
        val savedFw = TripodFogBake.Feather.w
        val ew = try {
            TripodFogBake.Feather.w = 0.0     // 羽化拆掉 ⇒ 立面回到硬直角边（读成卡片）
            TripodFogBake.genBuildings(world, p)
            whyT20(sw, skirtScan())           // 原型实测羽化 0.000 越出 0.04~0.12
        } finally {
            TripodFogBake.Feather.w = savedFw
            TripodFogBake.genBuildings(world, p)
        }

        assertEquals(
            "正样本没全绿（实测应为压楼 0 栋/0 帧、裙 0.181→0.478、顶/底比 1.82、羽化 0.070）：" +
                pw.joinToString("；"),
            emptyList<String>(),
            pw,
        )
        assertTrue("雾带强度 ${fmt(p.groundBand, 2)} ≤ 0.001 ⇒ 带体没画，本闸门无对象", p.groundBand > 0.001)
        assertTrue("在画立面只有 ${sw.aN} 栋次（361 帧 × 30 栋 = 10830，反空判）", sw.aN >= 1000)
        // 解析形式的锚点判据：带顶在**地平线以下**（旧锚把它抬到 hz 之上 = 切立面的根因）
        assertTrue(
            "亮带顶 hz${if (sw.top >= sw.hzAtRows) "+" else "−"}${fmt(sw.top - sw.hzAtRows)} px 必须落在 hz 之下" +
                "（R19 之前是 hz−0.072H = ${fmt(-world.cam.h * 0.072)} px）",
            sw.top >= sw.hzAtRows,
        )
        assertTrue("负样本 A 没咬红：旧锚压楼 ${sw.old} 栋 / ${sw.oldFrames} 帧（判均 >0，最坏 t=${fmt(sw.oldT)} s）", sw.old > 0)
        assertTrue("负样本 A 的帧集没铺开：只有 ${sw.oldFrames} 帧（判 >0）", sw.oldFrames > 0)
        for ((name, w) in listOf(
            "B skirtA→0.20" to bw,
            "C skirtH→25 m" to cw,
            "D skirtH→0" to dw,
            "E 羽化 Feather.w→0" to ew,
        )) {
            assertTrue("负样本 $name 没咬红（⚠️ 假负样本）", w.isNotEmpty())
        }

        // 还原自检：重烘后必须回到正样本的分布
        val sk1 = skirtScan()
        assertEquals("还原后必须重新全绿：" + whyT20(sw, sk1).joinToString("；"), emptyList<String>(), whyT20(sw, sk1))
        assertEquals(fmt(sk0.lo, 6), fmt(sk1.lo, 6))
        assertEquals(fmt(sk0.hi, 6), fmt(sk1.hi, 6))
        assertEquals(fmt(sk0.fLo, 6), fmt(sk1.fLo, 6))
        assertEquals(fmt(sk0.fHi, 6), fmt(sk1.fHi, 6))
        assertEquals(sk0.n, sk1.n)
        assertEquals(0.45, p.skirtA, 1e-12)
        assertEquals(8.0, p.skirtH, 1e-12)
        assertEquals(0.07, TripodFogBake.Feather.w, 1e-12)
    }

    // ══════════════════════════════════════ 常量（§十二 的界）══════════════════════════════════════

    private companion object {
        /** T14 (b) 的帧集上限：`d ≤ 150 m`（§八 剧本表 P1 末的 knot，"三条腿逐渐分辨"）。 */
        const val D_SUBJ_T14 = 150.0

        /** T15 四条界（原型 `LIM`）。 */
        const val LIM_W = 0.6
        const val LIM_H = 0.14
        const val LIM_EDGE = 0.02
        const val LIM_AREA = 0.18
        val fmtLimW = "0.6"
        val fmtLimH = "0.14"
        val fmtLimArea = "0.18"

        /** T16 的 b/c/d 界（a 界 = `rollA + 1`，随参数现算）。 */
        const val LIM_B_T16 = 0.5
        const val LIM_C_T16 = 2.0
        const val LIM_D_T16 = 0.01

        /** T19：`DT = 1/30` ⇒ 2701 帧；缆须两根的根偏移 = `grab()` 里的 `±0.62`。 */
        const val DT_T19 = 1.0 / 30.0
        val T19_U = doubleArrayOf(-0.62, 0.62)

        /** T20：`DT = DS = 1/4` ⇒ 361 次采样（原型 :3660）。 */
        const val SKIRT_N = 361
    }
}
