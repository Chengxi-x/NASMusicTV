package com.nasmusic.tv.visualizer.renderers

import java.io.File
import org.junit.Test

/**
 * E46 首帧成本探针（⛔ **不断言耗时** —— 墙钟断言在本机/CI 上都站不住，见 2026-10-13 移除
 * `PerfBudgetContractTest` 墙钟判据的同一条理由）。本类只做一件事：把各烘焙阶段的
 * 桌面 JVM 耗时写到 `app/build/tmp/e46_bake_cost.txt`，供
 * `docs/technical-overview.md` 的分帧预算推导**复跑取数**（⛔ 不许手抄）。
 *
 * ⚠️ 桌面 JVM 的绝对值 ≠ 创维 API 22 的绝对值（本机慢一个量级），这里要的是**阶段占比**：
 * 它决定了 [TripodBakeQueue] 的推进顺序（雾 → 人群 → 楼 → grade）。
 */
internal class TripodBakeCostProbeTest {

    @Test
    fun probe() {
        val sb = StringBuilder()
        val world = TripodFogWorld()
        val p = world.params
        sb.append("tier=").append(world.tier).append(" grain=").append(p.grain).append('\n')

        var t = System.nanoTime()
        TripodFogMath.layout(world, 1920.0, 1080.0)
        sb.append("layout=").append(ms(System.nanoTime() - t)).append(" ms\n")

        t = System.nanoTime()
        TripodFogMath.syncRouteEpoch(world, true)
        sb.append("syncRouteEpoch=").append(ms(System.nanoTime() - t)).append(" ms\n")

        t = System.nanoTime()
        TripodFogBake.genBuildings(world, p, true)
        var px = 0L
        for (b in world.bMid + world.bNear) px += b.bmp!!.w.toLong() * b.bmp!!.h
        sb.append("genBuildings(48栋含烘焙)=").append(ms(System.nanoTime() - t))
            .append(" ms  像素=").append(px).append('\n')

        t = System.nanoTime()
        TripodFogBake.genBuildings(world, p, false)
        sb.append("genBuildings(纯几何)=").append(ms(System.nanoTime() - t)).append(" ms\n")

        t = System.nanoTime()
        TripodFogBake.bakeFogStrips()
        sb.append("bakeFogStrips=").append(ms(System.nanoTime() - t))
            .append(" ms  像素=").append(
                TripodFogBake.STRIP_A_W * TripodFogBake.STRIP_A_H +
                    TripodFogBake.STRIP_B_W * TripodFogBake.STRIP_B_H,
            ).append('\n')

        t = System.nanoTime()
        TripodFogBake.bakePersons()
        sb.append("bakePersons=").append(ms(System.nanoTime() - t)).append(" ms\n")

        t = System.nanoTime()
        TripodFogBake.bakeGrade(1920, 1080, 960.0, p.grain)
        sb.append("bakeGrade(1920x1080)=").append(ms(System.nanoTime() - t))
            .append(" ms  像素=2073600\n")

        // 预热后的稳态（首跑含 JIT，占比会失真）
        sb.append("--- 预热后 ---\n")
        t = System.nanoTime()
        TripodFogBake.bakeGrade(1920, 1080, 960.0, p.grain)
        sb.append("bakeGrade=").append(ms(System.nanoTime() - t)).append(" ms\n")
        t = System.nanoTime()
        TripodFogBake.bakeFogStrips()
        sb.append("bakeFogStrips=").append(ms(System.nanoTime() - t)).append(" ms\n")
        t = System.nanoTime()
        val w2 = TripodFogWorld()
        TripodFogMath.layout(w2, 1920.0, 1080.0)
        TripodFogMath.syncRouteEpoch(w2, true)
        TripodFogBake.genBuildings(w2, w2.params, true)
        sb.append("layout+epoch+genBuildings=").append(ms(System.nanoTime() - t)).append(" ms\n")

        // 队列粒度：一个单元在预算内占多少
        val q = TripodBakeQueue(
            TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H,
            TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H,
            w2.bandMid.n, w2.bandNear.n, 1920, 1080,
        )
        sb.append("队列单元数=").append(q.units.size).append('\n')

        val out = File("build/tmp")
        out.mkdirs()
        File(out, "e46_bake_cost.txt").writeText(sb.toString())
    }

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
}
