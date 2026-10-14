package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI

/**
 * E47 K2「数学 + 大树」的**跨语言黄金测试** —— 期望值逐字来自
 * `SnowCedarBaseline.kt`，而那份基线只能由
 * `node docs/archive/verification/scripts/e47_snow_golden.js` 从冻结的浏览器原型再生。
 *
 * ## ⛔ 本文件的数字不许手抄、不许"看着调"
 * 改树 / 雪的数学 ⇒ 必须重跑再生脚本并把再生产物一起提交（E44 偏差 D24 的同一条纪律）。
 * 该脚本自带前置闸门：**原型自己的 12 条自检必须全绿**才肯再生 ⇒ 不会出现"基线跟着错误一起写进来"。
 *
 * ## 容差分层（为什么不是全部逐位）
 * - **1e-12**（[T_EXACT]）：只含整数与乘加的路径（mulberry32、hash2、vnoise/fbm）——
 *   V8 与 HotSpot 的 IEEE double 四则运算逐位一致，这一层可以贴到机器精度。
 * - **1e-9**（[T_MATH]）：含 `sin` / `cos` / `pow` 的路径（`segLenPx`、树几何、网格偏移）。
 *   两边都是 IEEE double，但 libm 不保证同一实现；1 ulp 差会被
 *   `n = clamp(round(len/segLenPx), 2, 6)` 这类**临界判断**放大成"少一段枝" ⇒
 *   用 0 容差会红得无法解释（E46 `TripodFogGoldenTest` 同一口径）。
 * - **精确相等**：所有**计数**（段数 / 雪条数 / 雪脊数 / 抽样下标 / 分位下标）与所有**判定结果**
 *   （`viol`、`ridgeBad`、`flatBad`、`truncated`）。整数没有舍入歧义，它们是上面两层能站住的地基。
 *
 * 树**指纹**（[SnowCedarTree.fingerprint]）只在本文件里做 Kotlin 侧的内部确定性自证，
 * ⛔ 不跨语言对账——理由见该函数自己的 KDoc。
 *
 * ## 判据必须咬得住（"跑绿"不等于"测到"）
 * 每档带两个负样本，与原型 T3 逐行同口径：
 * ① 把雪脊长轴**抹平成轴对齐** ⇒ `flatBad` 必须等于基线值（LOW 48 / MED 180 / HIGH 464 条脱枝），
 *    抓的是所有者否掉的"分叉节点上一个大椭圆"（D13）；
 * ② 把承雪面从 `|ux|` 换成 `|uy|` ⇒ 三条计数与平均水平度都必须对得上基线，
 *    抓的是"雪挂到陡枝上"。
 * 另加 ③：枝长单调上界，用一个 `lenDecay=1.06` 的构造树证明该判据不是空转。
 *
 * ## 尚未覆盖（按分工归别的切片）
 * 树的**位图尺寸**在 ⑬ 对账（`*Mesh[1]`/`[2]`，与 ⑧ 同一行基线）；位图**像素**没有基线 ——
 * 原型沙箱里 `getImageData` 读不到东西，所以所有 Canvas 落笔只能靠 [SnowCedarBakeGateTest]
 * 的"分块 ≡ 整张"等价性与真机截图判读，⛔ 不能跨语言逐位对。
 * ⑨ 之后的 K4 组（分层切片 / 前 12 粒初值 / 冰晶点表 / 逐层消隐线）与树同档同画幅对账；
 * ⑩ 的 K6 组（肩雪时序 + 世界状态）与人物点表 / 派生几何 / 顶点法体量在 ⑪ 对账。
 */
internal class SnowCedarGoldenTest {

    /** 一档的基线切片（⛔ 反射按名取：改字段名必须编译不过，而不是运行时静默跳过一条判据）。 */
    private class Gold(
        val segLenPx: DoubleArray,
        val counts: IntArray,
        val box: DoubleArray,
        val snowStats: DoubleArray,
        val snowFlip: DoubleArray,
        val segIdx: IntArray,
        val segs: Array<DoubleArray>,
        val snowIdx: IntArray,
        val snows: Array<DoubleArray>,
        val clumpIdx: IntArray,
        val clumps: Array<DoubleArray>,
        val clumpR: IntArray,
        val mesh: DoubleArray,
        val flakeCounts: IntArray,
        val flakes: Array<DoubleArray>,
        val glints: DoubleArray,
        val glintN: DoubleArray,
        val hideY: IntArray,
        val dust: DoubleArray,
        val dustStat: DoubleArray,
        val world: Array<DoubleArray>,
    )

    @Test
    fun lowTierTreeIsPorted() = checkTier(SnowLevel.LOW, "LOW")

    @Test
    fun medTierTreeIsPorted() = checkTier(SnowLevel.MEDIUM, "MED")

    @Test
    fun highTierTreeIsPorted() = checkTier(SnowLevel.HIGH, "HIGH")

    private fun checkTier(level: SnowLevel, pre: String) {
        val p = SnowCedarP()
        p.applyLevel(level)
        val l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        val opt = SnowCedarTree.treeOpt(p, l)
        val t = SnowCedarTree.build(opt)
        val g = gold(level)
        val tag = "$pre "

        // ① 单位系子段长（= segPx ÷ 树高，含除法的舍入 ⇒ 1e-9 档）
        assertD(g.segLenPx, doubleArrayOf(opt.segLenPx), T_MATH, tag + "segLenPx")

        // ② 三段表的**条数**必须逐个相等：条数一不同，后面的抽样就全错位
        assertEquals(tag + "segs/snow/clumps", g.counts.toList(), listOf(t.segs.size, t.snow.size, t.clumps.size))

        // ③ 包围盒与最长子枝比（位图尺寸与"有没有削冠"的账都从这两个数起）
        assertD(g.box.copyOfRange(0, 5), doubleArrayOf(t.x0, t.y0, t.x1, t.y1, t.maxRatio), T_MATH, tag + "bbox")
        assertEquals(tag + "truncated", g.box[5], if (t.truncated) 1.0 else 0.0, 0.0)

        // ④ 积雪门槛 + 方向契约（原型 T3 同一条判据，四个数一次对）
        var viol = 0
        val hs = ArrayList<Double>(t.snow.size)
        for (s in t.snow) {
            if (s.horiz < p.tree.snowHMin - 1e-12) viol++
            hs.add(s.horiz)
        }
        var ridgeBad = 0
        var flatBad = 0
        for (c in t.clumps) {
            if (unattached(t, c, null)) ridgeBad++
            if (unattached(t, c, 0.0)) flatBad++
        }
        assertD(
            g.snowStats,
            doubleArrayOf(viol.toDouble(), mean(hs), ridgeBad.toDouble(), flatBad.toDouble(), t.clumps.size.toDouble()),
            T_MATH, tag + "snowStats[viol,meanH,ridgeBad,flatBad,clumps]",
        )
        assertEquals(tag + "脱枝必须 0", 0.0, g.snowStats[2], 0.0)
        // 负样本自己也要成立：抹平方向后必须有一大批挂不住（否则 ④ 的 ridgeBad=0 是空转）
        assertTrue(tag + "抹平后脱枝数必须 >0（判据不许空转）", flatBad > 0)

        // ⑤ 负样本：承雪面换成 |uy| ⇒ 段数不变（拓扑没动）、雪条数与平均水平度必须塌
        val flipped = SnowCedarTree.build(opt.copy(snowSign = -1))
        val hsF = ArrayList<Double>(flipped.snow.size)
        for (s in flipped.snow) hsF.add(s.horiz)
        assertD(
            g.snowFlip,
            doubleArrayOf(
                flipped.segs.size.toDouble(),
                flipped.snow.size.toDouble(),
                flipped.clumps.size.toDouble(),
                if (flipped.snow.isEmpty()) 1.0 else mean(hsF),
            ),
            T_MATH, tag + "snowSign=-1[segs,snow,clumps,meanH]",
        )

        // ⑥ 均匀抽样：下标表本身是探针（钉住 JS `Math.round` 的 .5 口径），再逐字段对坐标
        assertEquals(tag + "segs 抽样下标", g.segIdx.toList(), samples(t.segs.size, 24).toList())
        assertDD(
            g.segs,
            Array(g.segIdx.size) { i ->
                val s = t.segs[g.segIdx[i]]
                doubleArrayOf(s.x1, s.y1, s.x2, s.y2, s.w, s.depth.toDouble())
            },
            T_MATH, tag + "segs",
        )
        assertEquals(tag + "snow 抽样下标", g.snowIdx.toList(), samples(t.snow.size, 16).toList())
        assertDD(
            g.snows,
            Array(g.snowIdx.size) { i ->
                val s = t.snow[g.snowIdx[i]]
                doubleArrayOf(s.x1, s.y1, s.x2, s.y2, s.sw, s.horiz)
            },
            T_MATH, tag + "snow",
        )
        assertEquals(tag + "clumps 抽样下标", g.clumpIdx.toList(), samples(t.clumps.size, 16).toList())
        assertDD(
            g.clumps,
            Array(g.clumpIdx.size) { i ->
                val c = t.clumps[g.clumpIdx[i]]
                doubleArrayOf(c.x, c.y, c.r, c.depth.toDouble(), c.tx, c.ty)
            },
            T_MATH, tag + "clumps",
        )

        // ⑦ 雪脊半径分布五项（×1e9 取整 ⇒ **整数**探针，跨 libm 无歧义）
        assertEquals(tag + "clumpR", g.clumpR.toList(), clumpRQuantiles(t).toList())

        // ⑧ 风摆网格：行数 = 档位 rows，逐行偏移含 pow ⇒ 1e-9
        assertEquals(tag + "mesh rows", g.mesh[0], p.wind.rows.toDouble(), 0.0)
        val rows = p.wind.rows
        val out = DoubleArray(rows)
        // 位图高吃**基线给的** `mesh[2]`：尺寸公式归 K3（在那里与 `mesh[1]/[2]` 一起对账），
        // ⛔ 不在本文件里另写一份尺寸算法——两处各写一遍正是原型 §3 警告的漂移源。
        SnowCedarMath.meshOffsets(12.5, rows, g.mesh[2], out)
        assertD(
            DoubleArray(rows) { g.mesh[3 + it] },
            out,
            T_MATH, tag + "meshOffsets(sway=12.5)",
        )
        assertEquals(tag + "mesh 长度", rows + 3, g.mesh.size)

        // ⑨ K4 雪场：分层切片 + 前 12 粒初值 + 冰晶点表 + 逐层消隐线（同一次 layout 之后）
        val f = SnowField(p, l)
        f.allocFlakes(p.snow.flakes)
        assertEquals(
            tag + "flakeCounts[n,各层条数…,各层起点…]",
            g.flakeCounts.toList(),
            (listOf(f.n) + f.flakeCount.toList() + f.flakeOff.toList()),
        )
        // 初值全部**存进 Float32Array**（JS 同）⇒ 两边都是同一份 float32 量化，可以贴 1e-12
        assertDD(
            g.flakes,
            Array(g.flakes.size) { i ->
                doubleArrayOf(
                    f.x[i].toDouble(), f.y[i].toDouble(), f.phOf(i).toDouble(),
                    f.rzOf(i).toDouble(), f.layerOf(i).toDouble(),
                )
            },
            T_EXACT, tag + "flakes[x,y,ph,rz,layer]",
        )
        f.allocGlints()
        assertEquals(tag + "glintN", g.glintN[0], f.glintN.toDouble(), 0.0)
        assertD(
            g.glints,
            DoubleArray(g.glints.size) { i ->
                val k = i / 4
                when (i % 4) {
                    0 -> f.glintU(k).toDouble()
                    1 -> f.glintX(k).toDouble()
                    2 -> f.glintY(k).toDouble()
                    else -> f.glintPh(k).toDouble()
                }
            },
            T_EXACT, tag + "glints[u,x,y,ph]",
        )
        // 逐层消隐线（吃 x = W/2）：基线里 land 与 gy 都 ×1e6 取整 ⇒ **整数**探针
        val hide = IntArray(SnowLayers.ALL.size * 2)
        for (li in SnowLayers.ALL.indices) {
            hide[li] = SnowCedarMath.jsRound(SnowLayers.ALL[li].land * 1e6).toInt()
            hide[SnowLayers.ALL.size + li] =
                SnowCedarMath.jsRound(f.hideY(li, SnowCedarBaseline.BaseW * 0.5) * 1e6).toInt()
        }
        assertEquals(tag + "hideY[各层 land…, 各层 gY…]", g.hideY.toList(), hide.toList())

        // ⑩ K6 时序：肩雪（每 150 帧一样，跑到 60 s）+ 世界状态（每 180 帧一样，跑到 30 s）
        //    ⚠️ 采样**次序**必须与再生脚本一致：先记当前值再步进（`o.push(PR.dust)` 在 step 之前），
        //    记反一位整条曲线就错位——这类"探针挂在赋值前还是后"的坑在 E43 的 harness 上真踩过。
        val audio = SnowAudio(p)
        val world = SnowWorldState(p, l)
        val dust = SnowPersonDust(p)
        dust.reset()
        val dt = 1.0 / 60.0
        val dustNow = ArrayList<Double>(25)
        for (i in 0..3600) {
            if (i % 150 == 0) dustNow.add(dust.dust)
            SnowSimAudio.frame(audio, world, dust, i * dt, dt)
        }
        assertD(g.dust, dustNow.toDoubleArray(), T_DUST, tag + "dust(每 150 帧)")

        var lo = 1.0
        var hi = 0.0
        var dec = 0
        var prev = 0.0
        dust.reset()
        for (i in 0 until 10800) {
            SnowSimAudio.frame(audio, world, dust, i * dt, dt)
            if (dust.dust < lo) lo = dust.dust
            if (dust.dust > hi) hi = dust.dust
            if (dust.dust < prev - 1e-12) dec++
            prev = dust.dust
        }
        assertD(g.dustStat, doubleArrayOf(lo, hi, dec.toDouble()), T_DUST, tag + "dustStat[lo,hi,下降帧]")

        dust.reset()
        val worldNow = ArrayList<DoubleArray>(11)
        for (i in 0..1800) {
            val t = i * dt
            SnowSimAudio.sim(t)
            audio.update(SnowSimAudio.bass, SnowSimAudio.energy, SnowSimAudio.treble, dt)
            world.state(t, audio.bass, audio.energy, audio.treble)
            if (i % 180 == 0) {
                worldNow.add(
                    doubleArrayOf(
                        world.gust, world.wind, world.windPx, world.fallPx,
                        world.sway, world.sunGain, world.swayMul,
                    ),
                )
            }
        }
        // 第 0 列 `gust` 是**纯时间量**（⛔ 音频）⇒ 可以贴 1e-12；其余六列吃 Float 包络 ⇒ 按 T_AUDIO。
        assertD(
            DoubleArray(g.world.size) { g.world[it][0] },
            DoubleArray(worldNow.size) { worldNow[it][0] },
            T_DUST, tag + "world.gust",
        )
        assertDD(
            Array(g.world.size) { i -> g.world[i].copyOfRange(1, 7) },
            Array(worldNow.size) { i -> worldNow[i].copyOfRange(1, 7) },
            T_AUDIO, tag + "world[wind,windPx,fallPx,sway,sunGain,swayMul]",
        )
    }

    // ─────────────────────────── ⑪ K6 人物（MED 档、同一画幅）───────────────────────────

    /**
     * 人物的**点表 + 派生几何 + 顶点法体量**逐位对账。
     *
     * ⛔ 这里一条像素判据都没有：原型沙箱里 `arc/ellipse` 是空实现，`getImageData` 恒读到 0，
     * 所以基线吃的是 [SnowPerson.personSil] 采集下来的**实际消费顶点**（与烘焙同一份几何）。
     * `PersonSilThin` 是再生脚本按 `k=0.3` 拍的负样本口径 —— 它在这里只是**记账**，
     * 真正咬人的七条结构判据 + 七条负样本在 [SnowPersonGateTest] 的 T11。
     */
    @Test
    fun personSilhouetteIsPortedBitForBit() {
        val p = SnowCedarP()
        p.applyLevel(SnowLevel.MEDIUM)
        val l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        val B = SnowCedarBaseline
        val st = SnowPerson.STAND

        // 点表本身：抄错一位就在这张表上暴露（⛔ 这些是照参考图量出来的，不是手感）
        assertD(B.PersonScalars, doubleArrayOf(SnowPerson.SH, SnowPerson.HIP, SnowPerson.HEM, SnowPerson.CHIN), T_EXACT, "scalars[sh,hip,hemB,chin]")
        assertD(B.PersonCoatW, SnowPerson.COAT_W, T_EXACT, "coatW")
        assertD(B.PersonYokeW, SnowPerson.YOKE_W, T_EXACT, "yokeW")
        assertD(B.PersonLegW, SnowPerson.LEG_W, T_EXACT, "legW")
        assertD(B.PersonArmW, SnowPerson.ARM_W, T_EXACT, "armW")
        assertD(B.PersonCaneW, SnowPerson.CANE_W, T_EXACT, "caneW")
        assertD(B.PersonBoot, doubleArrayOf(SnowPerson.BOOT_TOE, SnowPerson.BOOT_HEEL, SnowPerson.BOOT_H, SnowPerson.BOOT_W), T_EXACT, "boot[toe,heel,h,w]")
        assertD(B.PersonPoseScalars, doubleArrayOf(st.scarf, st.lean), T_EXACT, "pose[scarf,lean]")
        assertDD(B.PersonYoke, SnowPerson.YOKE, T_EXACT, "yoke")
        assertDD(B.PersonR, st.R, T_EXACT, "R")
        assertDD(B.PersonL, st.L, T_EXACT, "L")
        assertDD(B.PersonAR, st.aR, T_EXACT, "aR")
        assertDD(B.PersonAL, st.aL, T_EXACT, "aL")
        assertDD(B.PersonCane, st.cane, T_EXACT, "cane")

        // 派生几何：原型点名的"两处各写一份会漂移"的那几个函数，只许有一份
        assertDD(B.PersonCoatSpine, SnowPerson.coatSpine(st), T_MATH, "coatSpine")
        assertDD(B.PersonShoulderTop, SnowPerson.shoulderTop(st), T_MATH, "shoulderTop")
        assertD(B.PersonDerived, doubleArrayOf(SnowPerson.shoulderHalf(), SnowPerson.soleFrac(st.R[2][1])), T_MATH, "derived[shoulderHalf,soleFrac]")

        // 顶点法测量：mass / ratio / wFrac / hFrac / polys + 18 块包围盒
        val sil = SnowPerson.personSil(1.0)
        assertD(B.PersonSilHead, doubleArrayOf(sil.mass, sil.ratio, sil.wFrac, sil.hFrac, sil.polys.toDouble()), T_MATH, "silHead")
        assertEquals("silBars 行数", B.PersonSilBars.size, sil.bars.size)
        assertDD(
            B.PersonSilBars,
            Array(sil.bars.size) { i -> val b = sil.bars[i]; doubleArrayOf(b.x0, b.x1, b.y0, b.y1, b.w, b.h) },
            T_MATH, "silBars",
        )
        val thin = SnowPerson.personSil(0.3)
        assertD(B.PersonSilThin, doubleArrayOf(thin.mass, thin.ratio, thin.wFrac, thin.hFrac, thin.polys.toDouble()), T_MATH, "silThin(k=0.3)")

        // 烘焙尺寸与 blit 矩形（1600×900 ⇒ hh=81、ww=63、soleY=8+81·0.9995）
        val sz = SnowPerson.sizeFor(l)
        assertD(B.PersonSize, doubleArrayOf(sz.w.toDouble(), sz.h.toDouble(), sz.top.toDouble(), sz.hh.toDouble(), sz.soleY), T_MATH, "size[w,h,top,hh,soleY]")
        val r = SnowPerson.rectFor(l, sz)
        assertD(B.PersonRect, doubleArrayOf(r.x, r.y, r.w.toDouble(), r.h.toDouble()), T_MATH, "rect[x,y,w,h]")
    }

    // ─────────────────────── ⑬ K3 树位图尺寸（`*Mesh[1]` / `[2]`）───────────────────────

    /**
     * 树位图的 **宽 × 高**（⑧ 同一行基线里的 `mesh[1]`/`mesh[2]`）与它必须守住的两个不变量。
     *
     * ⭐ 这条能进纯 JVM 黄金测试：[SnowCedarBakes.treeMeta] 只做 `Double`/`Int` 算术，
     * ⛔ 不碰 `Bitmap`。尺寸一错，`drawBitmapMesh` 的映射与方案文档 §三 的内存账全部作废，
     * 而画面**看着还是对的**（只是冠高不再精确等于 `L.treeH`）⇒ 只能靠账来钉。
     *
     * 两条不变量：① 树干基必须落回 `comp.treeX / treeBaseY`（位图坐标 ⇄ 屏幕坐标的闭合）；
     * ② 位图高 = `treeH + 2·pad`（±1 px 舍入），即"冠高精确落到 `L.treeH`"这条缩放定义的结果。
     */
    @Test
    fun treeBitmapDimsArePorted() {
        for (level in SnowLevel.entries) {
            val pre = when (level) {
                SnowLevel.LOW -> "LOW"
                SnowLevel.MEDIUM -> "MED"
                SnowLevel.HIGH -> "HIGH"
            }
            val tag = "$pre "
            val p = SnowCedarP()
            p.applyLevel(level)
            val l = SnowCedarLayout()
            l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
            val t = SnowCedarTree.build(SnowCedarTree.treeOpt(p, l))
            val m = SnowCedarBakes(p, l).treeMeta(t)
            val g = gold(level).mesh

            assertEquals(tag + "位图宽 = mesh[1]", g[1], m.w.toDouble(), 0.0)
            assertEquals(tag + "位图高 = mesh[2]", g[2], m.h.toDouble(), 0.0)
            assertEquals(tag + "树基落回 treeX", l.treeX, m.screenX0 + m.baseX, T_MATH)
            assertEquals(tag + "树基落回 treeBaseY", l.treeBaseY, m.screenY0 + m.baseY, T_MATH)
            assertEquals(tag + "位图高 = treeH + 2·pad", l.treeH + 2 * m.pad, m.h.toDouble(), 1.0)
        }
    }

    // ─────────────────────────── R 组 · 数学（1e-12）───────────────────────────

    @Test
    fun r1_rngStreamsMatchPrototypeBitForBit() {
        val seed = SNOW_SEED
        assertD(SnowCedarBaseline.RngSeed0, stream(seed, 16), T_EXACT, "RngSeed0")
        assertD(SnowCedarBaseline.RngFlakes, stream(seed xor 0x1234, 16), T_EXACT, "RngFlakes")
        assertD(SnowCedarBaseline.RngGlints, stream(seed xor 0x77a1, 16), T_EXACT, "RngGlints")
        // 步进流种子 = (seed ^ 0x9e37) + 帧号：取两帧 ⇒ ⛔ 只取 frame 0 抓不到"帧号根本没进种子"
        assertD(SnowCedarBaseline.RngStep0, stream((seed xor 0x9e37) + 0, 8), T_EXACT, "RngStep0")
        assertD(SnowCedarBaseline.RngStep7, stream((seed xor 0x9e37) + 7, 8), T_EXACT, "RngStep7")
    }

    @Test
    fun r2_hash2MatchesPrototypeOnSignedAndOverflowInputs() {
        val bi = SnowCedarBaseline.Hash2I
        val bj = SnowCedarBaseline.Hash2J
        assertD(SnowCedarBaseline.Hash2, DoubleArray(bi.size) { SnowCedarMath.hash2(bi[it], bj[it]) }, T_EXACT, "Hash2")
    }

    @Test
    fun r3_noiseMatchesPrototype() {
        val args = SnowCedarBaseline.NoiseArgs
        assertD(
            SnowCedarBaseline.Fbm,
            DoubleArray(args.size) { SnowCedarMath.fbm(args[it][0], args[it][1], 3, 0.5, 2.0) },
            T_EXACT, "Fbm",
        )
        assertD(
            SnowCedarBaseline.Vnoise,
            DoubleArray(args.size) { SnowCedarMath.vnoise(args[it][0], args[it][1]) },
            T_EXACT, "Vnoise",
        )
    }

    // ─────────────────────────── 跨切片的三条独立护栏 ───────────────────────────

    /** 原型 T1：同种子必须**逐位**复现同一棵树，换种子必须换树（⛔ 跨语言，只自证）。 */
    @Test
    fun t1_treeIsBitDeterministicAndSeedSensitive() {
        val p = SnowCedarP()
        p.applyLevel(SnowLevel.MEDIUM)
        val l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        val opt = SnowCedarTree.treeOpt(p, l)
        val a = SnowCedarTree.build(opt)
        val b = SnowCedarTree.build(opt)
        val c = SnowCedarTree.build(opt.copy(seed = opt.seed + 1))

        assertEquals("同种子指纹", SnowCedarTree.fingerprint(SnowCedarTree.fpArgs(a)), SnowCedarTree.fingerprint(SnowCedarTree.fpArgs(b)))
        assertNotEquals("seed+1 必须换树（RNG 没吃种子就是这里）", SnowCedarTree.fingerprint(SnowCedarTree.fpArgs(a)), SnowCedarTree.fingerprint(SnowCedarTree.fpArgs(c)))
        // 指纹相等还不够：逐位比第一段（浮点表示层面的自证）
        assertEquals("同种子第一段 x2 逐位", a.segs[0].x2, b.segs[0].x2, 0.0)
        assertEquals("同种子第一段 y2 逐位", a.segs[0].y2, b.segs[0].y2, 0.0)
        assertTrue("换种子后第一段必须不同", abs(a.segs[0].x2 - c.segs[0].x2) > 0.0)
    }

    /** 原型 T2：子枝长 ≤ 父枝 × `lenDecay` × 抖动上界；并用 1.06 的构造树证明判据咬得住。 */
    @Test
    fun t2_branchLengthIsMonotonicallyDecaying() {
        val p = SnowCedarP()
        p.applyLevel(SnowLevel.MEDIUM)
        val l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        val opt = SnowCedarTree.treeOpt(p, l)
        val cap = p.tree.lenDecay * 1.16 + 1e-9      // 生成期 kl = len·lenDecay·(0.86…1.16)

        val full = SnowCedarTree.build(opt.copy(maxSegs = 4000))
        assertTrue("maxRatio=${full.maxRatio} 必须 ≤ $cap", full.maxRatio <= cap)

        val bad = SnowCedarTree.build(opt.copy(lenDecay = 1.06, maxSegs = 400))
        assertTrue("负样本失效：lenDecay=1.06 时 maxRatio=${bad.maxRatio} 仍未越界", bad.maxRatio > cap)
    }

    /** 原型 T12 的第一条：人的脚线**不是参数**，由树基给出 ⇒ 二者必须逐位相等。 */
    @Test
    fun t12PersonAndTreeShareOneHorizontalLine() {
        val p = SnowCedarP()
        p.applyLevel(SnowLevel.HIGH)
        val l = SnowCedarLayout()
        l.layout(SnowCedarBaseline.BaseW, SnowCedarBaseline.BaseH, p)
        assertEquals("personY 必须恒等于 treeBaseY", l.treeBaseY, l.personY, 0.0)
        assertEquals("treeH = treeBaseY − crownTop（900 画幅）", 558.0, l.treeH, T_MATH)
        assertEquals("S = H/540", 900.0 / 540.0, l.s, T_MATH)
        // 极矮画幅：treeH 必须走 20 的下限而不是趋 0（否则 uy / segLenPx 一起爆炸）
        val tiny = SnowCedarLayout()
        tiny.layout(1600.0, 20.0, p)
        assertEquals("treeH 下限", 20.0, tiny.treeH, 0.0)
        assertTrue("treeBaseY < crownTop 时仍要有树", tiny.treeH > 0.0)
    }

    // ─────────────────────────── 判据与工具 ───────────────────────────

    /**
     * 雪脊长轴是否**贴着某条枝段的轴向**：夹角 ≤ 22° **且**中心到该弦的垂距 ≤ 自身半径。
     * ⛔ 不查"有没有 tx/ty 字段"——写个字段就骗过去了；这里独立地从 `segs` 反解，
     * 于是挂歪、挂空、轴对齐椭圆三种形状一起被抓（与原型 `ridgeUnattached` 逐行同口径）。
     *
     * @param angOverride 非 null 即负样本口径（`0.0` = 把所有长轴抹平成轴对齐）
     */
    private fun unattached(t: SnowCedarTree.Tree, c: SnowCedarTree.Clump, angOverride: Double?): Boolean {
        val ca = angOverride ?: atan2(c.ty, c.tx)
        for (s in t.segs) {
            val dx = s.x2 - s.x1
            val dy = s.y2 - s.y1
            val len = SnowCedarMath.hypot(dx, dy)
            if (len < 1e-9) continue
            // 弦无正反 ⇒ 折到 (−90°, 90°]。⚠️ 只能"超过 90° 就减 180°"，
            // ⛔ 不能写成 `mod π − π/2`：那会把**完全平行**（raw=0）折成 −90°，判据把自己的实现判红。
            var da = (atan2(dy, dx) - ca) % PI
            if (da < 0) da += PI
            if (da > PI / 2) da -= PI
            if (abs(da) > 22 * SnowCedarMath.D2R) continue
            val proj = max(0.0, min(len, ((c.x - s.x1) * dx + (c.y - s.y1) * dy) / len))
            val d = SnowCedarMath.hypot(c.x - (s.x1 + dx / len * proj), c.y - (s.y1 + dy / len * proj))
            if (d <= max(c.r, 0.004)) return false
        }
        return true
    }

    /** 原型 `__samples`：均匀取 k 个下标（含首含尾、去重、确定性），吃 `Math.round` 的 .5 口径。 */
    private fun samples(n: Int, k: Int): IntArray {
        if (n <= 0) return IntArray(0)
        val out = ArrayList<Int>(k)
        for (i in 0 until k) {
            val v = if (n == 1) 0 else SnowCedarMath.jsRound(i.toDouble() * (n - 1) / (k - 1)).toInt()
            if (!out.contains(v)) out.add(v)
        }
        return out.toIntArray()
    }

    /** 原型 `clumpR` 五项：升序排序后取 min/p10/p50/p90/max，×1e9 取整成**整数**探针。 */
    private fun clumpRQuantiles(t: SnowCedarTree.Tree): IntArray {
        val rs = DoubleArray(t.clumps.size) { t.clumps[it].r }
        rs.sort()
        val n = rs.size
        fun q(p: Double): Int =
            if (n == 0) 0
            else SnowCedarMath.jsRound(rs[min(n - 1, SnowCedarMath.jsRound(p * (n - 1)).toInt())] * 1e9).toInt()
        return intArrayOf(n, q(0.0), q(0.1), q(0.5), q(0.9), q(1.0))
    }

    private fun mean(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        var s = 0.0
        for (v in xs) s += v
        return s / xs.size
    }

    /** `mulberry32(seed)()` 连取 n 个（原型 `stream()` 的同一条流）。 */
    private fun stream(seed: Int, n: Int): DoubleArray {
        val r = SnowCedarMath.rng(seed)
        return DoubleArray(n) { r.next() }
    }

    private fun assertD(exp: DoubleArray, act: DoubleArray, tol: Double, msg: String) {
        assertEquals("$msg 长度", exp.size, act.size)
        for (i in exp.indices) assertEquals("$msg[$i]", exp[i], act[i], tol)
    }

    private fun assertDD(exp: Array<DoubleArray>, act: Array<DoubleArray>, tol: Double, msg: String) {
        assertEquals("$msg 行数", exp.size, act.size)
        for (i in exp.indices) assertD(exp[i], act[i], tol, "$msg[$i]")
    }

    /** 基线里三档前缀字段的显式映射（⛔ 反射按名取：漏一档必须编译不过）。 */
    private fun gold(level: SnowLevel): Gold = when (level) {
        SnowLevel.LOW -> Gold(
            SnowCedarBaseline.LOWSegLenPx, SnowCedarBaseline.LOWCounts, SnowCedarBaseline.LOWBox,
            SnowCedarBaseline.LOWSnowStats, SnowCedarBaseline.LOWSnowFlip,
            SnowCedarBaseline.LOWSegIdx, SnowCedarBaseline.LOWSegs,
            SnowCedarBaseline.LOWSnowIdx, SnowCedarBaseline.LOWSnows,
            SnowCedarBaseline.LOWClumpIdx, SnowCedarBaseline.LOWClumps,
            SnowCedarBaseline.LOWClumpR, SnowCedarBaseline.LOWMesh,
            SnowCedarBaseline.LOWFlakeCounts, SnowCedarBaseline.LOWFlakes,
            SnowCedarBaseline.LOWGlints, SnowCedarBaseline.LOWGlintN, SnowCedarBaseline.LOWHideY,
            SnowCedarBaseline.LOWDust, SnowCedarBaseline.LOWDustStat, SnowCedarBaseline.LOWWorld,
        )
        SnowLevel.MEDIUM -> Gold(
            SnowCedarBaseline.MEDSegLenPx, SnowCedarBaseline.MEDCounts, SnowCedarBaseline.MEDBox,
            SnowCedarBaseline.MEDSnowStats, SnowCedarBaseline.MEDSnowFlip,
            SnowCedarBaseline.MEDSegIdx, SnowCedarBaseline.MEDSegs,
            SnowCedarBaseline.MEDSnowIdx, SnowCedarBaseline.MEDSnows,
            SnowCedarBaseline.MEDClumpIdx, SnowCedarBaseline.MEDClumps,
            SnowCedarBaseline.MEDClumpR, SnowCedarBaseline.MEDMesh,
            SnowCedarBaseline.MEDFlakeCounts, SnowCedarBaseline.MEDFlakes,
            SnowCedarBaseline.MEDGlints, SnowCedarBaseline.MEDGlintN, SnowCedarBaseline.MEDHideY,
            SnowCedarBaseline.MEDDust, SnowCedarBaseline.MEDDustStat, SnowCedarBaseline.MEDWorld,
        )
        SnowLevel.HIGH -> Gold(
            SnowCedarBaseline.HIGHSegLenPx, SnowCedarBaseline.HIGHCounts, SnowCedarBaseline.HIGHBox,
            SnowCedarBaseline.HIGHSnowStats, SnowCedarBaseline.HIGHSnowFlip,
            SnowCedarBaseline.HIGHSegIdx, SnowCedarBaseline.HIGHSegs,
            SnowCedarBaseline.HIGHSnowIdx, SnowCedarBaseline.HIGHSnows,
            SnowCedarBaseline.HIGHClumpIdx, SnowCedarBaseline.HIGHClumps,
            SnowCedarBaseline.HIGHClumpR, SnowCedarBaseline.HIGHMesh,
            SnowCedarBaseline.HIGHFlakeCounts, SnowCedarBaseline.HIGHFlakes,
            SnowCedarBaseline.HIGHGlints, SnowCedarBaseline.HIGHGlintN, SnowCedarBaseline.HIGHHideY,
            SnowCedarBaseline.HIGHDust, SnowCedarBaseline.HIGHDustStat, SnowCedarBaseline.HIGHWorld,
        )
    }

    companion object {
        /** 全局固定种子（所有者裁决）；基线的 RNG 探针全部从它出发。 */
        private const val SNOW_SEED = 20261014

        /** 只含整数/乘加的路径：贴到机器精度。 */
        private const val T_EXACT = 1e-12

        /** 含 `sin` / `cos` / `pow` 的路径。 */
        private const val T_MATH = 1e-9

        /**
         * 肩雪 / `gust` 这类**只走 `sin` + 乘加**的时序路径，但要跨 3600~10800 次累加。
         *
         * ⚠️ 为什么不是 [T_EXACT]：`gust` 的 1 ulp 差要经过 `sh²`（`(gust−0.72)²/0.0784`）
         * 放大再逐帧累加，60 s 上贴到 1e-12 会红得无法解释；这里给到 1e-9 是**按步数**放的
         * （≈ √3600 · 机器精度量级），仍比 [T_AUDIO] 紧四个数量级 ⇒ 位移类回归照样咬得住。
         */
        private const val T_DUST = 1e-9

        /**
         * 吃**音频包络**的路径（`wind / windPx / fallPx / sway / sunGain / swayMul`）。
         *
         * 共享 [AudioSmoother] 是 `Float`，原型的 `smoothAudio` 是 `Double` ⇒ 包络本身就有
         * ~1e-7 的量化差，再乘进 `windPx≈57` 这种量级。1e-4 是这条链的**下界**，
         * ⛔ 别再往下压到 1e-6 然后"顺手把 smoother 改成 Double"——那是为测试改生产精度。
         * 判据仍有牙齿：T8 用 `> 0.02` 的活体检查证明确实在跟音频联动。
         */
        private const val T_AUDIO = 1e-4
    }
}
