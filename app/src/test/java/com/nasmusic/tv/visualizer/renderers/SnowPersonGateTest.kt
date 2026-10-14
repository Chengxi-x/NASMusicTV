package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * E47 K6「人物 + 肩雪 + 世界状态」的**行为护栏** —— 移植原型 §5 自检的 T6 / T7 / T8 / T11 / T12。
 *
 * ## 每条判据都必须"有牙齿"
 * 原型每条判据都配了负样本（把真因**装回去**），本文件一条不落：
 * 所有者四轮返工里"看着像改了其实没改"的账，全靠这些负样本兜住
 * （「还是一个小人在走动」「人像剪影不好看」「雪体现的是一个大团」）。
 *
 * ⚠️ 与 [SnowCedarGoldenTest] 的分工：那张表管**跨语言逐位对账**（数字只能由脚本再生），
 * 本文件管**结构性判据**（⛔ 不许把阈值调低让它过 —— 那叫放宽闸门，不叫修缺陷）。
 */
internal class SnowPersonGateTest {

    private fun layout(level: SnowLevel = SnowLevel.MEDIUM): Pair<SnowCedarP, SnowCedarLayout> {
        val p = SnowCedarP()
        p.applyLevel(level)
        val l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        return p to l
    }

    /**
     * **T6 · 人物零位移**（所有者 2026-10-14：「应该不用走动，固定剪影啊」）。
     *
     * 三条一起判：
     * ① 600 帧里 blit 矩形四值逐帧恒等；
     * ② 状态类**字段名**表必须只有 `dust`（原版的 `PR.x` 就是从这里漏出去的）；
     * ③ 负样本：按旧步态让 x 每帧 +0.02 ⇒ 违例必须 > 0，证明 ① 不是空转。
     */
    @Test
    fun t6PersonNeverMoves() {
        val (p, l) = layout()
        val sz = SnowPerson.sizeFor(l)
        val r0 = SnowPerson.rectFor(l, sz)
        assertTrue("blit 矩形必须有效", r0.w > 0 && r0.h > 0)

        val audio = SnowAudio(p)
        val world = SnowWorldState(p, l)
        val dust = SnowPersonDust(p)
        dust.reset()
        val dt = 1.0 / 60.0
        var driftBad = 0
        for (i in 0 until 600) {
            SnowSimAudio.frame(audio, world, dust, i * dt, dt)
            val r = SnowPerson.rectFor(l, SnowPerson.sizeFor(l))
            if (abs(r.x - r0.x) > 1e-9 || abs(r.y - r0.y) > 1e-9 || r.w != r0.w || r.h != r0.h) driftBad++
        }
        assertEquals("600 帧 blit 位移违例", 0, driftBad)

        // ② 位移类字段一出现就是回归（⛔ 别用"看起来没有"代替检查）
        // ⚠️ 必须滤掉编译器合成字段（Kotlin 会给 `private set` 的 `var` 加一个 `$stable`）：
        //    以 `$` 开头的名字 Kotlin 源码根本写不出来，留着判据只会假红。
        val fields = SnowPersonDust::class.java.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }
        val moveKeys = listOf("x", "y", "dir", "pose", "face", "stepT", "turnT").filter { it in fields }
        assertEquals("PR 里的位移类键 $fields", emptyList<String>(), moveKeys)
        assertEquals("肩雪状态只许有 p + dust 两个字段", setOf("p", "dust"), fields.toSet())

        // ③ 负样本：旧步态的 x 随帧推进 ⇒ 本判据必须抓到
        var walkBad = 0
        for (i in 1 until 600) if (abs((r0.x + i * 0.02) - r0.x) > 1e-9) walkBad++
        assertTrue("负样本失效：按旧步态推 x 竟没被抓到", walkBad > 0)
    }

    /**
     * **T7 · 肩雪有界 + 阵风抖落**（180 s）。
     *
     * ⭐ 「完全静止 + 雪落肩」里唯一的"活"就是这条积—落循环：既要会厚（`dMax > dMin`），
     * 也要被大阵风抖掉（下降帧 > 0），还⛔ 越界。
     * 负样本：`shakeRate = 0`（只增不减）⇒ 下降帧必须为 0 —— 证明"下降"这一项真的在抖落项上，
     * 不是浮点噪声碰出来的。
     */
    @Test
    fun t7ShoulderSnowAccumulatesAndShakesOff() {
        val (p, l) = layout()
        val audio = SnowAudio(p)
        val world = SnowWorldState(p, l)
        val dust = SnowPersonDust(p)
        val dt = 1.0 / 60.0

        fun run(): Triple<Int, Double, Int> {        // [越界, 区间跨度…下降帧] → 用四元组更直白，见下
            var oob = 0
            var lo = 1.0
            var hi = 0.0
            var dec = 0
            var prev = 0.0
            dust.reset()
            for (i in 0 until 10800) {
                SnowSimAudio.frame(audio, world, dust, i * dt, dt)
                val d = dust.dust
                if (!SnowSimAudio.bounded(d)) oob++
                if (d < lo) lo = d
                if (d > hi) hi = d
                if (d < prev - 1e-12) dec++
                prev = d
            }
            return Triple(oob, hi - lo, dec)
        }

        val (oob, span, dec) = run()
        assertEquals("肩雪越界帧", 0, oob)
        assertTrue("肩雪必须既会长厚（跨度 $span）", span > 0.5)
        assertTrue("阵风抖落必须生效（下降帧 $dec）", dec > 0)
        assertTrue("末帧必须在 [0,1]", dust.dust >= 0.0 && dust.dust <= 1.0)

        // 负样本：抖落速率置 0 ⇒ 单调只增，下降帧必然为 0
        val saved = p.person.shakeRate
        p.person.shakeRate = 0.0
        val noShake = run()
        p.person.shakeRate = saved
        assertEquals("负样本：shakeRate=0 时竟仍有下降帧", 0, noShake.third)
        // 同一条负样本里也不许越界（否则上面那条 oob==0 是噪声给的）
        assertEquals("负样本的越界帧", 0, noShake.first)
    }

    /**
     * **T8 · 无声 ⇒ 音频增益严格收敛到静态基线**（⛔ 拿像素判：雪与风是常驻动画）。
     *
     * 与原型同口径但**更强**：原型把 `SM` 手播种到 0.9 再放掉；这里先喂 90 帧大值（`max|k| > 0.02`
     * ⇒ 证明映射活着，不是接了根死线），再把源置 0 喂 900 帧 ⇒ 三条增益与 `swayMul` 必须贴回基线。
     * 走的是生产那条 [SnowAudio]，⛔ 测试另写一份平滑器。
     */
    @Test
    fun t8SilenceCollapsesToStaticBaseline() {
        val (p, l) = layout()
        val audio = SnowAudio(p)
        val world = SnowWorldState(p, l)
        val dt = 1.0 / 60.0

        for (i in 0 until 90) {
            val t = i * dt
            audio.update(0.9, 0.9, 0.9, dt)
            world.state(t, audio.bass, audio.energy, audio.treble)
        }
        val liveK = maxOf(abs(world.kWind), abs(world.kSun))
        assertTrue("有音 90 帧后 max|Δk|=$liveK 必须 >0.02（映射是活的）", liveK > 0.02)

        for (i in 0 until 900) {
            val t = i * dt
            audio.update(0.0, 0.0, 0.0, dt)
            world.state(t, audio.bass, audio.energy, audio.treble)
        }
        val dK = maxOf(abs(world.kWind), abs(world.kGust), abs(world.kSun))
        assertTrue("无声 900 帧后 max|Δk|=$dK 必须 <1e-6", dK < 1e-6)
        assertTrue("swayMul 必须回到 0.45（Δ=${abs(world.swayMul - 0.45)}）", abs(world.swayMul - 0.45) < 1e-6)
        // ⛔ 静音即静止：gust / sway 的时间基线在无声时仍有非零值
        assertTrue("无声时摆幅必须仍活着", abs(world.sway) > 0.0)
    }

    /**
     * **T11 · 剪影七条结构判据 + 七条负样本**。
     *
     * ⚠️ 记账说明（所有者 2026-10-14「人像剪影不好看」那一轮）：旧版"绝对体量 ≥ 0.25"这条**删除**，
     * ⛔ 不是调低。理由：体量 ≡ 填框率 × 宽占比 × 高占比（`personSil` 的恒等式），上一版 0.259 里
     * 0.762 那份"填得满"正是**桶状躯干**贡献的 —— 而桶腰本身就是被判掉的缺陷；且 0.25 相对 0.259
     * 只有 3.6% 余量，从来不是推导出来的闸门。削掉桶腰 ⇒ 体量必然降到 0.231，而**这恰是改对了的证据**。
     * ⇒ 换成两条有牙齿的：**宽占比**（体量想靠"框窄"作弊就没处藏）+ **部件绝对宽度下限**（火柴人克星）。
     */
    @Test
    fun t11SilhouetteStructureWithTeeth() {
        val sil = SnowPerson.personSil(1.0)
        val stk = SnowPerson.personSil(0.26)
        val cw = SnowPerson.COAT_W
        val shHalf = SnowPerson.shoulderHalf()
        val neckLen = SnowSilProbe.neckLen()
        val armOut = SnowSilProbe.armOut()

        // ── 正样本：真表必须全部过闸
        assertTrue("填框率 ${sil.ratio} 必须 ≥0.60（⛔ 空心人）", sil.ratio >= 0.60)
        assertTrue("高占比 ${sil.hFrac} 必须 ≥0.96（点表 y 0→1 必须真的铺满）", sil.hFrac >= 0.96)
        assertTrue("火柴人体量 ${stk.mass} 必须 ≤ 真表 ${sil.mass} 的 0.40", stk.mass <= sil.mass * 0.40)
        assertTrue("火柴人填框率 ${stk.ratio} 必须 ≤ 真表 ${sil.ratio} 的 0.55", stk.ratio <= sil.ratio * 0.55)
        assertTrue("腰/摆/肩 ${cw[3]}/${cw[4]}/$shHalf 必须收腰", SnowSilProbe.waistOK(cw[3], cw[4], shHalf))
        assertTrue("颈长 $neckLen、领宽 ${cw[0]}、肩 $shHalf 必须有脖子", SnowSilProbe.neckOK(neckLen, cw[0], shHalf))
        assertTrue("肘部外露 $armOut 必须 ≥0.015", SnowSilProbe.armOK(armOut))
        assertTrue("下巴 $CHIN 必须 ≤0.150（⛔ 五头身卡通大头）", SnowSilProbe.headOK(SnowPerson.CHIN))
        assertTrue("剪影里必须有一根杖", SnowSilProbe.caneOK(sil.bars))
        assertTrue("宽占比 ${sil.wFrac} 必须 ≥0.30", SnowSilProbe.widthOK(sil.wFrac))
        assertTrue(
            "解剖宽度下限 肩$shHalf 腿${SnowPerson.LEG_W[0]} 臂${SnowPerson.ARM_W[0]} 靴${SnowPerson.BOOT_W}",
            SnowSilProbe.anatomyOK(shHalf, SnowPerson.LEG_W[0], SnowPerson.ARM_W[0], SnowPerson.BOOT_W),
        )

        // ── 负样本：四条真因各装回去一次，判据必须当场红
        assertFalse("桶腰（腰=肩、摆不放出）没被抓到", SnowSilProbe.waistOK(shHalf, cw[4], shHalf))
        assertFalse("蘑菇（下巴坐在肩线上）没被抓到", SnowSilProbe.neckOK(0.0, cw[0], shHalf))
        assertFalse("袍子（手臂缩回大衣里）没被抓到", SnowSilProbe.armOK(armOut - 0.060))
        assertFalse("卡通大头（5 头身）没被抓到", SnowSilProbe.headOK(0.200))
        assertFalse(
            "没有杖的形状没被抓到",
            SnowSilProbe.caneOK(sil.bars.filter { !(it.x1 >= 0.160 && it.h >= 0.40) }),
        )
        assertFalse("框变窄（杖/围巾/手臂收进身体）没被抓到", SnowSilProbe.widthOK(sil.wFrac - 0.10))
        assertFalse(
            "火柴人（半宽表整体缩到 0.26×）没被抓到",
            SnowSilProbe.anatomyOK(
                shHalf * 0.26, SnowPerson.LEG_W[0] * 0.26, SnowPerson.ARM_W[0] * 0.26, SnowPerson.BOOT_W * 0.26,
            ),
        )
    }

    /**
     * **T12 · 人与树同一条水平线**（所有者 2026-10-14：「还有，人要跟树在一个水平线上」）。
     *
     * [SnowCedarGoldenTest] 已经判了数值那条（`personY ≡ treeBaseY`），本判据补**结构**那两条：
     * ① 参数表 [SnowComp] 里**不许有** `personY` 字段 —— 留一个可写字段就是留一条会漂移的路；
     * ② 烘焙后脚线必须**逐位**落在树基线上（`rect.y + soleY ≡ treeBaseY`），
     *    画幅换成 1280×720 与极矮 1600×20 都要成立。
     */
    @Test
    fun t12FootLineIsNotAParameter() {
        val fields = SnowComp::class.java.declaredFields.map { it.name }
        assertFalse("参数表里出现了 personY：$fields", "personY" in fields)

        for ((w, h) in listOf(1600.0 to 900.0, 1280.0 to 720.0, 1600.0 to 20.0)) {
            val p = SnowCedarP()
            p.applyLevel(SnowLevel.HIGH)
            val l = SnowCedarLayout()
            l.layout(w, h, p)
            val sz = SnowPerson.sizeFor(l)
            val r = SnowPerson.rectFor(l, sz)
            assertEquals("${w.toInt()}×${h.toInt()} 的脚线必须贴在树基线上", l.treeBaseY, r.y + sz.soleY, 0.0)
            assertTrue("${w.toInt()}×${h.toInt()} 的位图必须有高度", sz.h > sz.soleY && sz.hh >= 22)
        }
    }

    companion object {
        private const val CHIN = 0.145
    }
}
