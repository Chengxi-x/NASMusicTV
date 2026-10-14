package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * E47 K4「雪场」的**行为闸门**（原型 T4/T5 口径 + 方案 §十二.2 追加的两条）。
 *
 * 与 [SnowCedarGoldenTest] 的分工：那边把"初值点表"逐位钉在浏览器基线上，
 * 本文件管的是**跑起来之后**的四条不变量：
 * ① 守恒 + 不出界（重生条件写反 ⇒ 立刻漏空或堆积）；
 * ② 单帧位移有上限（速度档位乘错、或 `dt` 没夹紧 ⇒ 一粒飞到对侧，读成"闪现"）；
 * ③ 每层**自己**的消隐线（P17/D9：全层共用一条线会把"漫天"读成"上半屏下雪"）；
 * ④ 拖尾按**直径**而非"速度 × 秒"（T5 语义）+ 冰晶点表⛔ 不吃逐帧随机数。
 *
 * ⚠️ 画幅 `1600×900` + MED 档，与黄金基线同口径：消隐线含 `S = H/540`，换画幅就换一批数。
 *
 * ⚠️ 原型 §5 的头注释写着"人物深度 `(personY−hz)/(H−hz) ≈ 0.71`"，**这个数是旧的**：
 * 定稿参数下 `(0.735−0.600)/(1−0.600) = 0.3375`。所以本文件⛔ 不拿那句注释当判据，
 * 改判**可直接观测的几何事实**——远两层的消隐线在脚线**之上**（雪在走到人之前就落到地上），
 * 近两层的消隐线在脚线**之下**（雪会从人前面扫过）。绘制次序本身在 K5 的 `drawContent` 里硬编码。
 */
internal class SnowFieldTest {

    private lateinit var p: SnowCedarP
    private lateinit var l: SnowCedarLayout
    private lateinit var f: SnowField

    @Before
    fun setup() {
        p = SnowCedarP()
        p.applyLevel(SnowLevel.MEDIUM)
        l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        f = SnowField(p, l)
        f.allocFlakes(p.snow.flakes)
        f.allocGlints()
    }

    // ───────────────────── ① 守恒 + 不出界（原型 T4）─────────────────────

    @Test
    fun t4_flakesAreConservedAndStayInBox() {
        val n0 = f.n
        val dt = 1.0 / 60.0
        for (i in 0 until 600) f.stepFlakes(dt, i * dt, 30.0, 60.0)

        assertEquals("600 帧后总粒数必须恒等于初值", n0, f.n)
        assertEquals("越界粒数必须为 0", 0, outOfBox())

        // 负样本：人为挪 1 粒到画面外 ⇒ 检出口径必须**恰为 1**（计数写错就抓不到漏空）
        val save = f.x[0]
        f.x[0] = (l.w * 3).toFloat()
        assertEquals("注入 1 粒必须检出 1 粒", 1, outOfBox())
        f.x[0] = save
    }

    private fun outOfBox(): Int {
        var c = 0
        for (i in 0 until f.n) {
            val x = f.x[i].toDouble()
            val y = f.y[i].toDouble()
            if (x < -0.085 * l.w || x > l.w * 1.085 || y < -0.13 * l.h || y > l.h * 1.085) c++
        }
        return c
    }

    // ───────────────────── ② 单帧位移上限 ─────────────────────

    /**
     * 每帧位移必须有上限，越过去的**只允许**是重生或横向回绕两类合法瞬移。
     *
     * 上限从速度公式现推（⛔ 抄一个常数）：`|vx| ≤ windPx·fMax·1.34`（sway 两项幅值
     * 0.22 + 0.12）、`|vy| ≤ fallPx·fMax·1.15`（`0.85 + 0.30`），`fMax` 是最快层（前景 bokeh 1.42）。
     */
    @Test
    fun t4b_perFrameDisplacementIsBounded() {
        val dt = 1.0 / 60.0
        val windPx = 30.0
        val fallPx = 60.0
        var fMax = 0.0
        for (lf in SnowLayers.ALL) fMax = max(fMax, lf.f)
        val cap = dt * SnowCedarMath.hypot(windPx * fMax * 1.34, fallPx * fMax * 1.15)

        val px = DoubleArray(f.n)
        val py = DoubleArray(f.n)
        var teleports = 0
        var maxOk = 0.0
        var checked = 0
        for (frame in 0 until 600) {
            for (i in 0 until f.n) {
                px[i] = f.x[i].toDouble()
                py[i] = f.y[i].toDouble()
            }
            f.stepFlakes(dt, frame * dt, windPx, fallPx)
            for (i in 0 until f.n) {
                val dx = f.x[i].toDouble() - px[i]
                val dy = f.y[i].toDouble() - py[i]
                val d = SnowCedarMath.hypot(dx, dy)
                // 重生 = 落点被抬回画面上方（`y ∈ [−0.10H, −0.05H]`）；回绕 = 横移一整幅宽
                val respawned = f.y[i] < -0.05 * l.h + 1e-3
                val wrapped = abs(dx) > l.w
                if (respawned || wrapped) {
                    teleports++
                } else {
                    assertTrue("第 $frame 帧第 $i 粒单帧位移 $d > 上限 $cap（重生条件写反？）", d <= cap)
                    maxOk = max(maxOk, d)
                    checked++
                }
            }
        }
        assertTrue("600 帧里必须发生过重生（否则根本没测到重生分支）", teleports > 0)
        assertTrue("正常位移样本必须非空转（checked=$checked）", checked > 0)
        assertTrue("实测最大位移 $maxOk 必须 >0（否则 stepFlakes 没动）", maxOk > 0.0)
        // 判据不许形同虚设：上限必须明显小于画幅高度，否则"越界"和"瞬移"都抓不到
        assertTrue("上限 $cap 必须远小于画幅高 ${l.h}", cap < l.h * 0.05)
    }

    // ───────────────────── ③ 逐层消隐线（P17/D9 回归闸门）─────────────────────

    @Test
    fun landIsStrictlyIncreasingAndDrivesOneHideLinePerLayer() {
        val lands = SnowLayers.ALL.map { it.land }
        for (i in 1 until lands.size) {
            assertTrue("land 必须严格递增：$lands", lands[i] > lands[i - 1])
        }

        // 每层的消隐线 = `hz + land·(H−hz)` + 半个雪原起伏 + 半个粒半径（⛔ 不是 H）
        val mid = l.w * 0.5
        val surface = l.snowSurfaceY(mid)
        for (li in SnowLayers.ALL.indices) {
            val lf = SnowLayers.ALL[li]
            val expect = minOf(
                l.h * 1.045,
                l.hz + (l.h - l.hz) * lf.land + 0.5 * (surface - l.hz) + lf.r * l.s * 0.5,
            )
            assertEquals("L$li 消隐线", expect, f.hideY(li, mid), 1e-9)
        }

        // 负样本口径：若实现把五层压成**一条**共用线，层间距会塌成 0
        val spread = f.hideY(4, mid) - f.hideY(0, mid)
        assertTrue("最远/最近层的消隐线间距 $spread 必须 ≥ 0.8·(H−hz)=" + (l.h - l.hz), spread >= 0.8 * (l.h - l.hz))

        // 可直接观测的几何事实：远两层在脚线**之上**收住，近两层在脚线**之下**扫过人物
        val feet = l.personY
        assertTrue("L0 消隐线 ${f.hideY(0, mid)} 必须高于脚线 $feet", f.hideY(0, mid) < feet)
        assertTrue("L1 消隐线 ${f.hideY(1, mid)} 必须高于脚线 $feet", f.hideY(1, mid) < feet)
        assertTrue("L3 消隐线 ${f.hideY(3, mid)} 必须低于脚线 $feet", f.hideY(3, mid) > feet)
        assertTrue("L4 消隐线 ${f.hideY(4, mid)} 必须低于脚线 $feet", f.hideY(4, mid) > feet)
    }

    // ───────────────────── ④ 拖尾语义 + 冰晶确定性 ─────────────────────

    /**
     * streak 层的拖尾长度 = `streak × 直径`，**与速度大小无关**。
     *
     * ⚠️ 这条专门盯旧口径"速度 × 秒数"：低速帧会被拉成 10×30 的药丸（所有者判"雪粒在飞"）。
     * 用相差 33 倍的两个风速各跑一帧，拖尾长度都必须**恰好等于同一个 k**。
     */
    @Test
    fun streakLengthFollowsDiameterNotSpeed() {
        val li = 3
        val k = f.streakLen(li)
        assertEquals("k = streak·(r·S)·2", p.snow.streak * SnowLayers.ALL[li].r * l.s * 2.0, k, 1e-12)

        for (windPx in doubleArrayOf(6.0, 200.0)) {
            f.stepFlakes(1.0 / 60.0, 0.25, windPx, 40.0)
            var seen = 0
            var worst = 0.0
            val a = f.flakeOff[li]
            for (i in a until a + f.flakeCount[li]) {
                if (f.vx[i] == 0.0f && f.vy[i] == 0.0f) continue      // 静止帧方向退化，不计
                val ex = f.streakEndX(i, k)
                val ey = f.streakEndY(i, k)
                // 端点里含原型给的 +0.01 斜向偏置（零长度线在 canvas 上会退化成一个点），量长度先剥掉
                val len = SnowCedarMath.hypot(ex - f.x[i] - 0.01, ey - f.y[i] - 0.01)
                worst = max(worst, abs(len - k))
                seen++
            }
            assertTrue("windPx=$windPx 下必须有 streak 粒子被检", seen > 0)
            // 容差 1e-3：坐标存的是 float32（幅值 ~1600 ⇒ 量化误差 ~1e-4），而 k=4.34——
            // 旧口径"速度 × 秒"在这里差的是**整倍**，1e-3 照样咬得住。
            assertEquals("windPx=$windPx 的拖尾长度必须恒等于 k=$k", 0.0, worst, 1e-3)
        }
    }

    @Test
    fun glintTableIsSeedFixedAndOnlyAFewTwinkle() {
        assertEquals("glint 条数 = P.fx.glint", p.fx.glint.toInt(), f.glintN)

        // ⛔ 逐帧随机数：同一种子重建两次必须逐位相同（换歌/重进仍同一片晶粒）
        val g = SnowField(p, l)
        g.allocGlints()
        for (i in 0 until g.glintN) {
            assertEquals("u[$i]", f.glintU(i), g.glintU(i), 0.0f)
            assertEquals("x[$i]", f.glintX(i), g.glintX(i), 0.0f)
            assertEquals("y[$i]", f.glintY(i), g.glintY(i), 0.0f)
            assertEquals("ph[$i]", f.glintPh(i), g.glintPh(i), 0.0f)
        }

        // `sin^16` + `b<0.30` 剔除 ⇒ 任一瞬间只有**少数**几粒在闪
        // （全亮 = 一张固定的白点网，读成"脏屏"；一条也不闪 = 这层等于没画）
        var lit = 0
        var samples = 0
        var t = 0.0
        while (t < 60.0) {
            var on = 0
            for (i in 0 until f.glintN) {
                val b = f.glintBright(i, t)
                if (b > 0.0) {
                    assertTrue("b=$b 必须 ≥ 剔除阈值 0.30", b >= 0.30)
                    assertTrue("b=$b 必须 ≤ 1", b <= 1.0)
                    on++
                }
            }
            lit += on
            samples++
            t += 1.0 / 30.0
        }
        val meanLit = lit.toDouble() / samples
        assertTrue("平均亮闪 $meanLit 必须 >0（判据不许空转）", meanLit > 0.0)
        assertTrue("平均亮闪 $meanLit 必须 < 0.20·n=${0.20 * f.glintN}", meanLit < 0.20 * f.glintN)
    }
}
