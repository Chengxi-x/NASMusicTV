package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * E46 闸门 **T7–T11**（§十二 表的对应行，逐条移植自原型 `selfTest()`）。
 *
 * ⚠️ 全是**解析判据**（投影点、几何余量、颜色名单），⛔ 回读像素 —— JVM 里 `Bitmap` 回读要么
 * `not mocked` 要么恒空，那种写法会得到一条永远绿的假闸门（§十四 1）。
 * ⚠️ 每条负样本的旋钮都写在它自己的改动清单里并 `try/finally` 还原，⛔ 跨条共用、⛔ 只改判据不改参数。
 */
internal class TripodFogVisibilityGatesTest {

    // ─────────────────────────── T7 游走段主体可见性 ───────────────────────────

    @Test
    fun t7_wanderLegTripodAlwaysInFrame() {
        val world = TripodGateFixtures.boot()
        val p = world.params
        val cam = world.cam
        val hz0 = TripodGateFixtures.hzBase(world)

        fun inFrame(st: TripodScene): Boolean {
            // ⭐ 仰角在**这一处**落地：与 plan.build() (`cam.hz = h·horizonK + tilt`) 同一口径
            cam.hz = hz0 + st.tilt
            val bc = TripodFogMath.bodyFrame(0.0, 0.5, st.bodyY, st.psi, st.d, st.mx)
            val c = TripodFogMath.pr(cam, p, bc.x, bc.y, bc.z, st.yaw)
            val tp = TripodFogMath.pr(
                cam, p, bc.x, bc.y + (TripodFogMath.topY(p) - p.bodyY), bc.z, st.yaw,
            )
            return c != null && tp != null && abs(c.X - cam.cx) <= cam.w * 0.5 &&
                c.Y >= -cam.h * 0.15 && c.Y <= cam.h * 1.15 && tp.Y >= 0.0
        }

        // ⭐ 判据区 = 游走段（escEnv ≥ 0.5 的掠过/揭示/绕后整体移出 ⇒ 归 T18 判）
        fun judged(st: TripodScene) =
            TripodGateFixtures.visibleEnough(world, st) >= 0.05 && world.escEnv(st.g) < 0.5

        var n = 0
        var bad = 0
        TripodGateFixtures.frames(world, 0.1) { _, st ->
            if (judged(st)) {
                n++
                if (!inFrame(st)) bad++
            }
        }

        val savedK = p.wK
        var nNeg = 0
        var negBad = 0
        try {
            p.wK = 4.0                        // 游走幅值 ×4 ⇒ 机器横出轨
            TripodGateFixtures.frames(world, 0.1) { _, st ->
                if (judged(st)) {
                    nNeg++
                    if (!inFrame(st)) negBad++
                }
            }
        } finally {
            p.wK = savedK
            cam.hz = hz0
        }

        assertEquals("游走段应可见 ≥100 帧（反空判）", true, n >= 100)
        assertEquals("出画帧数必须为 0（实测 $n 帧里 $bad 帧出画）", 0, bad)
        assertTrue("负样本没咬红（wK→4 只出画 $negBad 帧，判 >30）", negBad > 30)
        assertTrue("负样本判据区不能空转", nNeg >= 100)
    }

    // ─────────────────────────── T8 建筑带尺度 ───────────────────────────

    @Test
    fun t8_buildingBandFitsScale() {
        val world = TripodGateFixtures.boot()
        val cam = world.cam
        val p = world.params
        val hz0 = TripodGateFixtures.hzBase(world)

        // 原型 `cap(cfg, lim)`：带里**最高那栋**在 `zMin` 处的投影高度 − 限值
        fun cap(hHi: Double, zMin: Double, lim: Double): Double =
            hHi * cam.ppm * (p.fz / (p.fz + zMin)) - lim

        val midOver = cap(world.bandMid.hHi, world.bandMid.zMin, hz0)
        val nearOver = cap(world.bandNear.hHi, world.bandNear.zMin, cam.h * 1.15)
        // 负样本 = 第一版的拍脑袋值（mid 74 / near 88），两条都必须变红
        val negM = cap(74.0, world.bandMid.zMin, hz0)
        val negN = cap(88.0, world.bandNear.zMin, cam.h * 1.15)

        assertTrue("中景整栋出画 ${"%.1f".format(midOver)} px（判 ≤0）", midOver <= 0.0)
        assertTrue("近景出画超 15%：${"%.1f".format(nearOver)} px（判 ≤0）", nearOver <= 0.0)
        assertTrue("负样本 A（mid 74 m）没咬红：${"%.1f".format(negM)} px（判 >0）", negM > 0.0)
        assertTrue("负样本 B（near 88 m）没咬红：${"%.1f".format(negN)} px（判 >0）", negN > 0.0)
    }

    // ─────────────────────────── T9 两道光分离 ───────────────────────────

    @Test
    fun t9_twoBeamsNeverMerge() {
        val world = TripodGateFixtures.boot()
        val p = world.params
        val need = 2 * p.beamHalfFar

        // ⚠️ 世界系两点距离，⛔ 只用 |Δx|：机身转向时两落点一部分落在深度方向上，
        //    取 Δx 等于「它一转身两道光就算重合了」——那是判据的错，不是画面的错。
        fun gap(st: TripodScene) =
            hypot(st.beams[0].hx - st.beams[1].hx, st.beams[0].hz - st.beams[1].hz)

        var got = Double.POSITIVE_INFINITY
        TripodGateFixtures.frames(world, 0.2) { _, st -> got = min(got, gap(st)) }

        val savedGap = p.beamGap
        val savedDiv = p.beamDiv
        var negWorst = 0
        try {
            p.beamGap = 0.6                   // 回到第一版：gap 0.6 m + 发散 0.012
            p.beamDiv = 0.012
            TripodGateFixtures.frames(world, 0.2) { _, st ->
                if (TripodGateFixtures.visibleEnough(world, st) >= 0.05 && gap(st) < need) negWorst++
            }
        } finally {
            p.beamGap = savedGap
            p.beamDiv = savedDiv
        }

        assertTrue("最小落点间距 ${"%.1f".format(got)} m ≤ 门限 ${"%.1f".format(need)} m", got > need)
        assertTrue("负样本没咬红（第一版参数下重合帧 $negWorst，判 >30）", negWorst > 30)
    }

    // ─────────────────────────── T10 站姿（R11 五判据） ───────────────────────────

    private class T10Stat(
        val ratio: Double,
        val upMin: Double,
        val upAt: Double,
        val upMax: Double,
        val upMaxSt: Double,
        val angMin: Double,
        val angMax: Double,
        val hi: Double,
        val lo: Double,
        val hiAt: Double,
        val loAt: Double,
    )

    @Test
    fun t10_stanceGeometryHolds() {
        val world = TripodGateFixtures.boot()
        val p = world.params

        fun fwdLegs() = TripodGait.LEG_AZ.count { cos(it) > 0.05 }

        // ⚠️ 髋/膝/脚一律取 tripodPose() —— 和渲染器**同一份**几何。自算一套近似的旧写法在
        //    曲折路线下来不及反映「站立脚钉在地面系」，实际跨度会比闸门算出来的大。
        fun scan(): T10Stat {
            var upMin = Double.POSITIVE_INFINITY
            var upAt = 0.0
            var upMax = Double.NEGATIVE_INFINITY
            var upMaxSt = Double.NEGATIVE_INFINITY
            var angMin = Double.POSITIVE_INFINITY
            var angMax = Double.NEGATIVE_INFINITY
            var hi = Double.POSITIVE_INFINITY
            var lo = Double.POSITIVE_INFINITY
            var hiAt = 0.0
            var loAt = 0.0
            TripodGateFixtures.frames(world, 0.1) { t, st ->
                val pose = TripodGait.tripodPose(world, st)
                for (i in 0 until 3) {
                    val h = pose.hips[i]
                    val k = pose.knees[i]
                    val f = pose.feet[i]
                    val up = k.y - h.y
                    if (up < upMin) {
                        upMin = up; upAt = t
                    }
                    if (up > upMax) upMax = up
                    // ⚠️ 膝顶闸门只统计**着地腿**：摆动腿抬在空中折叠是正常步态
                    if (!st.legs[i].swing && up > upMaxSt) upMaxSt = up
                    val dd = hypot(hypot(f.x - h.x, f.y - h.y), f.z - h.z)
                    val sHi = p.l1 + p.l2 - dd
                    val sLo = dd - abs(p.l2 - p.l1)
                    if (sHi < hi) {
                        hi = sHi; hiAt = t
                    }
                    if (sLo < lo) {
                        lo = sLo; loAt = t
                    }
                    val ax = h.x - k.x; val ay = h.y - k.y; val az = h.z - k.z
                    val bx = f.x - k.x; val by = f.y - k.y; val bz = f.z - k.z
                    val dn = hypot(hypot(ax, ay), az) * hypot(hypot(bx, by), bz)
                    val ang = acos(
                        TripodFogMath.clamp((ax * bx + ay * by + az * bz) / (if (dn == 0.0) 1e-9 else dn), -1.0, 1.0)
                    ) * 180.0 / Math.PI
                    if (ang < angMin) angMin = ang
                    if (ang > angMax) angMax = ang
                }
            }
            // ⚠️ ratio 必须在扫描**当时**取：withP 还原后才读 P 会拿到正样本的值
            return T10Stat(p.l2 / p.l1, upMin, upAt, upMax, upMaxSt, angMin, angMax, hi, lo, hiAt, loAt)
        }

        fun ok(s: T10Stat) = s.ratio >= 2.0 && s.upMin > 1.0 && s.hi > 2.0 && s.lo > 2.0

        fun withP(mut: () -> Unit): T10Stat {
            val s1 = p.l1; val s2 = p.l2; val sk = p.kneeUp; val sr = p.rf
            return try {
                mut()
                scan()
            } finally {
                p.l1 = s1; p.l2 = s2; p.kneeUp = sk; p.rf = sr
            }
        }

        val fwd = fwdLegs()
        val cur = scan()
        val negA = withP { p.l1 = 20.0; p.l2 = 20.0; p.kneeUp = 0.0 }
        val negB = withP { p.rf = 40.0 }
        val negC = withP { p.l2 = 46.0 }
        val negApex = withP { p.l1 = 15.0; p.l2 = 36.0 }

        // 负样本 D：方位角回到第一版（反号）⇒ 三条腿全拖在身后
        val savedAz = TripodGait.LEG_AZ.copyOf()
        val negFwd: Int
        try {
            TripodGait.LEG_AZ[0] = Math.PI
            TripodGait.LEG_AZ[1] = TripodFogMath.TAU / 3
            TripodGait.LEG_AZ[2] = 5 * TripodFogMath.TAU / 3
            negFwd = fwdLegs()
        } finally {
            System.arraycopy(savedAz, 0, TripodGait.LEG_AZ, 0, savedAz.size)
        }

        val apexTop = p.bodyY + cur.upMaxSt
        val apexLimit = TripodFogMath.topY(p) + 1.0
        val negApexTop = p.bodyY + negApex.upMaxSt

        assertTrue("前跨腿只有 $fwd 条（判 ≥2）", fwd >= 2)
        assertTrue("正样本五条判据没全绿：ratio ${cur.ratio} upMin ${cur.upMin} hi ${cur.hi} lo ${cur.lo}", ok(cur))
        assertFalse("负样本 A（R11 之前）没咬红", ok(negA))
        assertFalse("负样本 B（RF→40）没咬红", ok(negB))
        assertFalse("负样本 C（L2→46）没咬红", ok(negC))
        assertTrue("负样本 D（旧方位角）前跨腿 $negFwd 条（判 <2）", negFwd < 2)
        assertTrue("着地膝顶 ${"%.1f".format(apexTop)} m 高过机顶+1 m（${"%.1f".format(apexLimit)}）", apexTop <= apexLimit)
        assertTrue("负样本 E（L1→15）膝顶 ${"%.1f".format(negApexTop)} m 没越过机顶", negApexTop > apexLimit)

        // 还原自检：闸门不许留副作用
        assertEquals(2, fwdLegs())
        assertEquals(10.0, p.l1, 1e-12)
        assertEquals(34.0, p.l2, 1e-12)
        assertEquals(13.0, p.kneeUp, 1e-12)
        assertEquals(15.0, p.rf, 1e-12)
    }

    // ─────────────────────────── T11 建筑无电（颜色名单） ───────────────────────────

    @Test
    fun t11_buildingsHaveNoLights() {
        val trace = ArrayList<TripodFogBake.BakeCmd>()
        val world = try {
            TripodFogBake.trace = trace
            TripodGateFixtures.boot()
        } finally {
            TripodFogBake.trace = null
        }
        val all = (world.bMid.toList() + world.bNear.toList()).mapNotNull { it.bmp }
        val inkTotal = all.sumOf { it.inks.size }
        val badBmps = all.count { bmp -> bmp.inks.any { it != TripodFogBake.SILH_INT } }
        val fills = trace.filter { it.op == TripodFogBake.OP_FILL }.map { it.color }

        // 判据本身（原型 `same()`）：只用剪影色。负样本走**同一条**判据。
        fun silhouetteOnly(colors: List<Int>) = colors.all { it == TripodFogBake.SILH_INT }

        val warm = (0xFF shl 24) or (255 shl 16) or (190 shl 8) or 116   // 第一版的窗色 [255,190,116]

        assertTrue("位图张数 ${all.size}（判 ≥40，反空判）", all.size >= 40)
        assertEquals("每张位图应当只用一格颜色（inks $inkTotal vs 张数 ${all.size}）", all.size, inkTotal)
        assertEquals("非剪影色位图 ${badBmps} 张", 0, badBmps)
        assertTrue("指令流里出现过非剪影色（${fills.size} 次上色）", silhouetteOnly(fills))
        assertFalse("负样本没咬红：补一格暖黄窗后判据仍然为真", silhouetteOnly(fills + warm))
        assertTrue(
            "破口必须真的走 destination-out（OP_CLEAR ${trace.count { it.op == TripodFogBake.OP_CLEAR }} 次）",
            trace.any { it.op == TripodFogBake.OP_CLEAR },
        )
    }
}
