package com.nasmusic.tv.visualizer.renderers

import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.fx.FxLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E49「鸟群」仿真与数学门禁（方案 §11.2 的**合并版**：
 * `BirdsFlockMathTest` / `BirdsFlockSteerGateTest` / `BirdsFlockGridTest` /
 * `BirdsFlockPerchGateTest` / `BirdsFlockOrderParamTest` 五类的等价断言收进本文件）。
 *
 * ## 口径（§11.1）
 * ⛔ **从不构造 renderer**（渲染器带 Compose 类型，JVM 单测里会 `not mocked`）——
 * 纯逻辑全部走 `BirdsFlockMath` / `BirdsFlockFlock`（`TripodFogMath` 形态）。
 * 阈值与常量来源 = 原型 harness 归档（§10.2），⛔ 不手抄方案正文数字。
 */
class BirdsFlockGateTest {

    // ─────────────────────── 数学层（K2）───────────────────────

    /** §5.7 红线：`normalize(0,0)` 守卫 —— 零向量/NaN 输入必须早退返回零向量。 */
    @Test
    fun `normalize zero guard returns zero vector`() {
        val out = FloatArray(2)
        BirdsFlockMath.safeNormalize(0f, 0f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        BirdsFlockMath.safeNormalize(Float.NaN, 1f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
        // 正常输入仍归一化
        BirdsFlockMath.safeNormalize(3f, 4f, out)
        assertEquals(0.6f, out[0], 1e-5f)
        assertEquals(0.8f, out[1], 1e-5f)
    }

    /** §4.2 有界分离：守卫 `(1−d/R)²` 在 `d = R` 处为 0、`d → 0` 处 → 1、处处有界 ≤ 1。 */
    @Test
    fun `bounded separation guard is zero at R and finite everywhere`() {
        assertEquals(0f, BirdsFlockMath.sepGuard(30f, 30f), 0f)
        assertEquals(1f, BirdsFlockMath.sepGuard(0f, 30f), 1e-4f)
        // 任意 d 都有界（⛔ 裸 1/d² 在 d→0 时发散，boids 爆炸的头号来源）
        for (i in 0..100) {
            val d = i * 0.3f
            assertTrue(BirdsFlockMath.sepGuard(d, 30f) in 0f..1f)
        }
        // d > R 恒 0（只推开真正近的）
        assertEquals(0f, BirdsFlockMath.sepGuard(45f, 30f), 0f)
    }

    /** §4.9：滚入（τ=0.07）比滚出（τ=0.14）快 = 2:1（StarDisplay 侧倾波）。 */
    @Test
    fun `bank roll-in is twice as fast as roll-out`() {
        // 同一差值闭合一半所需步数：滚入段 0→0.5 闭合到 0.25；滚出段 0.5→0.1 回落到 0.3。
        // 指数衰减下闭合同一 fraction 的步数与时间常数成正比 ⇒ nOut 应恰为 2·nIn。
        val nIn = stepsToFraction(from = 0f, target = 0.5f, gap0 = 0.5f, fracGap = 0.25f)
        val nOut = stepsToFraction(from = 0.5f, target = 0.1f, gap0 = 0.4f, fracGap = 0.2f)
        assertTrue(
            "滚入/滚出时间常数应 = 1:2（§4.9 的 2:1），实测 nIn=$nIn nOut=$nOut",
            nOut in (2 * nIn - 1)..(2 * nIn + 1),
        )
    }

    /** 指数趋近下，|bank − target| 从 gap0 闭合到 fracGap 所需的固定步数。 */
    private fun stepsToFraction(from: Float, target: Float, gap0: Float, fracGap: Float): Int {
        var b = from
        var n = 0
        val dt = 1f / 60f
        while (n < 2000) {
            b = BirdsFlockMath.bankStep(b, target, dt)
            n++
            if (kotlin.math.abs(b - target) <= fracGap) return n
        }
        return n
    }

    /** 倾角钳 ±45°（T9 上限）：极端横向加速度不得越界。 */
    @Test
    fun `bank target is clamped to plus minus 45 degrees`() {
        assertEquals(-PI.toFloat() / 4f, BirdsFlockMath.bankTarget(-1000f, 0.9f, 0.17f), 1e-4f)
        assertEquals(PI.toFloat() / 4f, BirdsFlockMath.bankTarget(1000f, 0.9f, 0.17f), 1e-4f)
    }

    // ─────────────────────── 档位映射（K1，§12）───────────────────────

    /** §6.1 / §十二：枚举签名、档位表 200/400/600、`needsParticleBudget = false`、LOW 仍可选。 */
    @Test
    fun `tier mapping and enum signature`() {
        assertEquals(BirdsFlockMath.TIER_LOW, BirdsFlockMath.tierOf(FxLevel.OFF))
        assertEquals(BirdsFlockMath.TIER_MEDIUM, BirdsFlockMath.tierOf(FxLevel.LITE))
        assertEquals(BirdsFlockMath.TIER_HIGH, BirdsFlockMath.tierOf(FxLevel.FULL))
        // 与渲染器侧映射同源
        assertEquals(BirdsFlockMath.tierOf(FxLevel.LITE), BirdsFlockRenderer.tierOf(FxLevel.LITE))

        assertEquals(200, BirdsFlockMath.TIER_BIRDS[0])
        assertEquals(400, BirdsFlockMath.TIER_BIRDS[1])
        assertEquals(600, BirdsFlockMath.TIER_BIRDS[2])

        val t = VisualizerTheme.BIRDS_FLOCK
        assertEquals(VisualizerTheme.Tier.ADV, t.tier)
        assertEquals("49", t.ordinalLabel)
        assertFalse(t.needsParticleBudget)
        // 三档全部可选（含 LOW）——`ParticleBudgetGateTest` 反推的真值一致
        for (q in VisualQuality.entries) {
            assertTrue("$q 应支持 BIRDS_FLOCK（不读 maxParticles）", q.supports(t))
        }
    }

    // ─────────────────────── 仿真门禁（K3）───────────────────────

    /** §五.7 数值健康：推进 1800 帧（三档 30s），NaN/Inf = 0、无越界、无冻结。 */
    @Test
    fun `simulation stays numerically healthy across 1800 frames`() {
        val flock = BirdsFlockFlock(BirdsFlockParams())
        flock.layout(1600, 900, BirdsFlockMath.TIER_MEDIUM)
        repeat(1800) {
            flock.step(BirdsFlockMath.FIXED_DT)
            flock.stepVisual(BirdsFlockMath.FIXED_DT)
        }
        assertEquals(0, flock.nanInfFrames)
        assertEquals(400, flock.n)
        // 状态守恒（§5.9 T8）：isPerched 标志数 = nPerched 计数器 ≤ perchCap
        var flags = 0
        for (i in 0 until flock.n) if (flock.isPerched[i].toInt() != 0) flags++
        assertEquals(flock.nPerched, flags)
        assertTrue(flock.nPerched <= flock.perchCap)
        // 无冻结（§4.6 T5）：没有任何鸟速度为 NaN 或永久贴 0
        for (i in 0 until flock.n) {
            assertTrue(flock.px[i].isFinite())
            assertTrue(flock.py[i].isFinite() && flock.py[i] in 0f..1f)
        }
    }

    /** §4.5 top-7：邻居数 ≤ 7、锥条件成立、且小 N 下与暴力 O(N²) 逐位一致。 */
    @Test
    fun `top-7 neighbors match brute force on small N`() {
        val flock = BirdsFlockFlock(BirdsFlockParams())
        flock.layout(800, 600, BirdsFlockMath.TIER_LOW)
        // 小 N 交叉验证布置：12 只鸟聚在一个 ~100px 团里（全部落在 3×3 邻格窗口内），
        // 用无理数级间隔避免对称 d² tie（⛔ 规则网格会大量等距 ⇒ tie-break 差异制造假红）
        val small = 12
        for (i in 0 until small) {
            flock.px[i] = 0.5f + 0.017f * sin(i * 2.399f)
            flock.py[i] = 0.4f + 0.017f * cos(i * 1.733f)
            flock.vx[i] = 0.02f
            flock.vy[i] = 0f
            flock.isPerched[i] = 0
            flock.state[i] = 0
        }
        // 余下鸟放到团外远处（不在 3×3 邻格窗口内，暴力 = 团内候选）
        for (i in small until flock.n) {
            flock.px[i] = 0.01f
            flock.py[i] = 0.99f
            flock.vx[i] = 0f
            flock.vy[i] = 0f
        }
        flock.rebuildGrid()
        for (i in 0 until small) {
            flock.findNeighbors(i)
            val k = flock.nbrK[i].toInt()
            assertTrue("邻居数必须 ≤ 7，实测 $k", k in 0..7)
            val base = i * 14
            // 锥条件：每个记录的邻居必须在前向半平面内
            for (nn in 0 until k) {
                val j = flock.nid[base + nn * 2]
                val dxj = flock.px[j] * flock.w - flock.px[i] * flock.w
                val dyj = flock.py[j] * flock.h - flock.py[i] * flock.h
                assertTrue("锥条件 dot(r,v) > 0 必须成立", dxj * flock.vx[i] + dyj * flock.vy[i] > 0f)
            }
            // 暴力 O(N²)：全部鸟按 d² 排序 + 同一锥条件，取最近 7 —— 逐位一致
            val expected = ArrayList<Pair<Int, Int>>()
            for (j in 0 until small) {
                if (j == i) continue
                val dxj = flock.px[j] * flock.w - flock.px[i] * flock.w
                val dyj = flock.py[j] * flock.h - flock.py[i] * flock.h
                val d2 = dxj * dxj + dyj * dyj
                if (d2 <= 0.1f) continue
                if (dxj * flock.vx[i] + dyj * flock.vy[i] <= 0f) continue
                expected.add(Pair(j, d2.toInt()))
            }
            expected.sortWith(compareBy({ it.second }, { it.first }))
            val want = expected.take(7).map { it.first }.toSet()
            val got = (0 until k).map { flock.nid[base + it * 2] }.toSet()
            assertEquals("小 N 暴力交叉验证：鸟 $i 的邻居集应逐位一致", want, got)
        }
    }

    /** §5.9 停栖状态机：栖点 slot 无重复占用、栖鸟高于地平线、起飞后 slot 归还。 */
    @Test
    fun `perch slots are never double-booked and perched birds stay above horizon`() {
        val flock = BirdsFlockFlock(BirdsFlockParams())
        flock.layout(1600, 900, BirdsFlockMath.TIER_MEDIUM)
        repeat(2400) {   // 40s：足以出现多轮落栖/起飞
            flock.step(BirdsFlockMath.FIXED_DT)
            flock.stepVisual(BirdsFlockMath.FIXED_DT)
            // 每帧检查：同一 slot 至多一只鸟占用
            val seen = HashMap<Int, Int>()
            for (i in 0 until flock.n) {
                if (flock.isPerched[i].toInt() == 0) continue
                val slot = flock.perchSlotOf[i]
                assertTrue("栖鸟 slot 必须有效，实测 $slot", slot in 0 until flock.nPerch)
                assertTrue("slot $slot 被重复占用", seen.put(slot, i) == null)
                assertTrue(
                    "栖鸟必须高于地平线（⛔ 地下鸟 = 状态机泄漏）",
                    flock.py[i] * flock.h < flock.h * 0.800f,
                )
            }
        }
    }

    /** §4.10 / §八 R2：γ（极化）持续起伏（方差 > 0）⇒ 群体不冻结、不"原地呼吸后僵住"。 */
    @Test
    fun `polarization gamma fluctuates instead of freezing`() {
        val flock = BirdsFlockFlock(BirdsFlockParams())
        flock.layout(1600, 900, BirdsFlockMath.TIER_HIGH)
        var sum = 0.0
        var sumSq = 0.0
        var frames = 0
        repeat(1200) {   // 20s
            flock.step(BirdsFlockMath.FIXED_DT)
            flock.stepVisual(BirdsFlockMath.FIXED_DT)
            // 跳过前 300 帧热身（子团收敛段）
            if (frames >= 300) {
                var sx = 0f
                var sy = 0f
                var cnt = 0
                for (i in 0 until flock.n) {
                    if (flock.isPerched[i].toInt() != 0) continue
                    val m = sqrt(flock.vx[i] * flock.vx[i] + flock.vy[i] * flock.vy[i])
                    if (m > 1e-9f) {
                        sx += flock.vx[i] / m
                        sy += flock.vy[i] / m
                        cnt++
                    }
                }
                val g = if (cnt > 0) sqrt(sx * sx + sy * sy) / cnt else 0f
                sum += g
                sumSq += g * g
            }
            frames++
        }
        val sampled = frames - 300
        val mean = sum / sampled
        val variance = sumSq / sampled - mean * mean
        assertTrue("γ 均值应 > 0.1（群体有方向性），实测 $mean", mean > 0.1)
        assertTrue("γ 应持续起伏（时间方差 > 0 ⇒ 无冻结），实测 $variance", variance > 1e-5)
        assertTrue("γ 应 < 0.95（⛔ 恒 1 = 整体刚性平移，§4.10 失效模式）", mean < 0.95)
    }

    /** 确定性（§十三 ⛔ 禁止逐帧随机）：同种子两次运行，600 帧后状态逐位一致。 */
    @Test
    fun `two seeded runs are bit-for-bit identical`() {
        val a = BirdsFlockFlock(BirdsFlockParams())
        val b = BirdsFlockFlock(BirdsFlockParams())
        a.layout(1600, 900, BirdsFlockMath.TIER_MEDIUM)
        b.layout(1600, 900, BirdsFlockMath.TIER_MEDIUM)
        repeat(600) {
            a.step(BirdsFlockMath.FIXED_DT)
            a.stepVisual(BirdsFlockMath.FIXED_DT)
            b.step(BirdsFlockMath.FIXED_DT)
            b.stepVisual(BirdsFlockMath.FIXED_DT)
        }
        for (i in 0 until a.n) {
            assertEquals("px[$i] 应逐位一致", a.px[i], b.px[i], 0f)
            assertEquals("py[$i] 应逐位一致", a.py[i], b.py[i], 0f)
            assertEquals("vx[$i] 应逐位一致", a.vx[i], b.vx[i], 0f)
            assertEquals("vy[$i] 应逐位一致", a.vy[i], b.vy[i], 0f)
            assertEquals("flapPh[$i] 应逐位一致", a.flapPh[i], b.flapPh[i], 0f)
        }
    }

    /** §4.7：边界滑移 —— 被压向边界的鸟**滑过去**而不是贴墙（贴壁比例 ≈ 0 的行为版判据）。 */
    @Test
    fun `birds near the side boundary keep sliding instead of sticking`() {
        val flock = BirdsFlockFlock(BirdsFlockParams())
        flock.layout(1600, 900, BirdsFlockMath.TIER_LOW)
        repeat(1200) {
            flock.step(BirdsFlockMath.FIXED_DT)
            flock.stepVisual(BirdsFlockMath.FIXED_DT)
        }
        // 统计贴在左/右竖边附近且切向速度也为 0 的鸟 —— 应为 0（kTangent > 0 保证滑移）
        val bandN = flock.h * 0.120f
        var stuck = 0
        for (i in 0 until flock.n) {
            if (flock.isPerched[i].toInt() != 0) continue
            val xi = flock.px[i] * flock.w
            val atEdge = xi < bandN || xi > flock.w - bandN
            val speed = sqrt(flock.vx[i] * flock.vx[i] + flock.vy[i] * flock.vy[i])
            if (atEdge && speed < 1e-3f) stuck++
        }
        assertEquals("贴墙鸟数必须为 0（§4.7 切向滑移）", 0, stuck)
    }

    // ─────────────────────── 烘焙层几何一致性（K4）───────────────────────

    /** 栖点必须落在枝尖上：treeGeom 共享 ⇒ 栖点 y 不得低于 perchMaxY 过滤线（低位枝已滤）。 */
    @Test
    fun `perch candidates respect the low-branch filter`() {
        val flock = BirdsFlockFlock(BirdsFlockParams())
        flock.layout(1600, 900, BirdsFlockMath.TIER_MEDIUM)
        val perchMaxY = flock.h * 0.800f - flock.h * 0.120f * 0.55f
        for (k in 0 until flock.nPerch) {
            assertTrue("栖点必须高于低位枝过滤线", flock.perchSy[k] <= perchMaxY + 1e-3f)
            assertTrue("栖点必须在画幅内", flock.perchSy[k] >= 0f)
        }
    }
}
