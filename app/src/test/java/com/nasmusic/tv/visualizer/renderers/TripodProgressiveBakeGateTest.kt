package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E46 首帧分帧烘焙闸门（所有者报障「进入迷雾有段时间黑屏；从别的效果切过来会停在上一个画面」）。
 *
 * ## 根因（本机 JVM 逐阶段普查，见 [TripodBakeCostProbeTest]）
 * `boot()` 把 **全部素材**（48 栋楼位图 + 2 张雾条 + 4 张人群 + 一整张 1080p 暗角颗粒）
 * 压在**进入效果的那一帧**同步烘完：桌面 JVM ≈ 290 ms，创维 API 22 的单核慢一个量级
 * ⇒ 首帧阻塞以**秒**计。而这期间 Compose 的 draw 块没有提交过任何一帧 ⇒
 * 冷进入是黑屏、切换是「上一个效果的最后一帧钉在屏幕上」。
 *
 * ## 修法（照 E43 沙纹理 / E44 月盘的既有先例：预算制分帧烘）
 * 首帧只做几何（`layout` + `syncRouteEpoch`），素材按 [TripodBakeQueue] 排队，
 * 每帧在固定时间预算内推进若干单元；未烘好的位图是全透明占位，发射器现有的
 * 「槽位查不到⇒连记账一起跳过」路径天然兼容。
 */
internal class TripodProgressiveBakeGateTest {

    // ─────────────────────────── 逐像素等价性 ───────────────────────────

    /** G1 grade 分行烘 ≡ 整张烘（分帧重构不得改变任何一个像素）。 */
    @Test
    fun gradeRowsEqualWholeBake() {
        val w = 320
        val h = 180
        val cx = w / 2.0
        val grain = 0.055
        val whole = TripodFogBake.bakeGrade(w, h, cx, grain)
        for (chunk in intArrayOf(1, 7, 64, h)) {
            val px = IntArray(w * h)
            var y = 0
            while (y < h) {
                val y1 = minOf(y + chunk, h)
                // ⭐ 行带**自己的**缓冲（长度 (y1-y)·w）⇒ 生产的真实形状：直接喂 `setPixels`，
                //    ⛔ 不是整张画幅的缓冲（那等于要求渲染器常驻一个 1080p 的 8 MB IntArray）
                val band = IntArray((y1 - y) * w)
                TripodFogBake.bakeGradeRows(band, w, h, cx, grain, y, y1)
                System.arraycopy(band, 0, px, y * w, band.size)
                y = y1
            }
            assertArrayEquals("grade 按 $chunk 行分块烘与整张烘不一致", whole, px)
        }
    }

    /** G2 雾条分行烘 ≡ 整张烘（同一式子、同一个 `fbm`，⛔ 不许有跨行状态）。 */
    @Test
    fun fogStripRowsEqualWholeBake() {
        val w = TripodFogBake.STRIP_A_W
        val h = TripodFogBake.STRIP_A_H
        val whole = TripodFogBake.bakeFogStrip(
            w, h, TripodFogBake.STRIP_A_CELLS, TripodFogBake.STRIP_A_SEED, TripodFogBake.STRIP_OCT, 1.0,
        )
        for (chunk in intArrayOf(1, 13, 96, h)) {
            val px = IntArray(w * h)
            var y = 0
            while (y < h) {
                val y1 = minOf(y + chunk, h)
                val band = IntArray((y1 - y) * w)
                TripodFogBake.bakeFogStripRows(band, w, h, TripodFogBake.STRIP_A_CELLS, TripodFogBake.STRIP_A_SEED, TripodFogBake.STRIP_OCT, 1.0, y, y1)
                System.arraycopy(band, 0, px, y * w, band.size)
                y = y1
            }
            assertArrayEquals("雾条按 $chunk 行分块烘与整张烘不一致", whole.px, px)
        }
    }

    /**
     * G3 几何与烘焙解耦：`genBuildings(bake = false)` 之后逐栋 `bakeBuilding`，
     * 得到的**几何与位图都**必须与一次成型的全量路径逐字节一致
     * （每栋用 `b.seed` 自立的 Lcg ⇒ 烘焙顺序无关；这条就是该性质的护栏）。
     */
    @Test
    fun buildingsBakeIsEquivalentAndOrderIndependent() {
        val a = TripodFogWorld()
        TripodFogMath.layout(a, 1280.0, 720.0)
        TripodFogMath.syncRouteEpoch(a, true)
        TripodFogBake.genBuildings(a, a.params, true)

        val b = TripodFogWorld()
        TripodFogMath.layout(b, 1280.0, 720.0)
        TripodFogMath.syncRouteEpoch(b, true)
        TripodFogBake.genBuildings(b, b.params, false)
        // 几何先就位，位图必须还没烘
        assertNull(b.bMid[0].bmp)
        assertNull(b.bNear[0].bmp)
        // 倒序烘（先近带后中带，⛔ 与全量路径的生成顺序相反）
        for (i in b.bNear.indices) b.bNear[i].bmp = TripodFogBake.bakeBuilding(b.bNear[i], b.params)
        for (i in b.bMid.indices) b.bMid[i].bmp = TripodFogBake.bakeBuilding(b.bMid[i], b.params)

        assertEquals("两带栋数口径变了", a.bMid.size + a.bNear.size, b.bMid.size + b.bNear.size)
        for (i in a.bMid.indices) {
            val x = a.bMid[i]
            val y = b.bMid[i]
            assertEquals("中带几何漂移 @$i", x.x, y.x, 1e-12)
            assertEquals(x.z0, y.z0, 1e-12)
            assertEquals(x.seed, y.seed)
            assertNotNull(y.bmp)
            assertEquals(x.bmp!!.w, y.bmp!!.w)
            assertArrayEquals("中带位图与全量路径不一致 @$i", x.bmp!!.px, y.bmp!!.px)
        }
        for (i in a.bNear.indices) {
            assertArrayEquals(a.bNear[i].bmp!!.px, b.bNear[i].bmp!!.px)
        }
    }

    // ─────────────────────────── 队列语义 ───────────────────────────

    /** G4 队列顺序 = 雾 → 人群 → 楼 → grade，且**每帧一个单元**足以在有限步内收敛。 */
    @Test
    fun queueOrderAndConvergence() {
        val q = TripodBakeQueue(
            TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H,
            TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H,
            34, 14, 1920, 1080,
        )
        var buildings = 0
        var strips = 0
        var persons = 0
        var gradeUnits = 0
        var lastGradeRow = -1
        var gradeRows = 0
        var guard = 0
        while (true) {
            val u = q.next() ?: break
            assertTrue("队列越界（guard=$guard）", ++guard < 5000)
            when (u.kind) {
                TripodBakeKind.STRIP_A, TripodBakeKind.STRIP_B -> strips++
                TripodBakeKind.PERSONS -> persons++
                TripodBakeKind.BUILDING -> {
                    buildings++
                    assertTrue("建筑单元下标越界：${u.a}", u.a in 0 until 48)
                }

                TripodBakeKind.GRADE -> {
                    gradeUnits++
                    assertTrue("grade 行块必须自上而下连续：${u.a} vs $lastGradeRow", u.a == lastGradeRow + 1)
                    assertTrue("grade 行块为空", u.b > u.a)
                    lastGradeRow = u.b - 1
                    gradeRows += u.b - u.a
                }
            }
        }
        assertTrue("雾条没有排队", strips >= 2)
        assertEquals("人群应当只排一个单元", 1, persons)
        assertEquals("48 栋楼应当逐栋排队", 48, buildings)
        assertTrue("grade 必须分块排队（整张同步烘就是本闸门的病）", gradeUnits >= 2)
        assertEquals("grade 行块必须恰好铺满画幅", 1080, gradeRows)
        assertTrue("队列必须终止", q.done)

        // 顺序：任何楼栋都排在 grade 之前，雾条排在楼栋之前
        var prevRank = -1
        for (u in q.units) {
            val rank = when (u.kind) {
                TripodBakeKind.STRIP_A, TripodBakeKind.STRIP_B -> 0
                TripodBakeKind.PERSONS -> 1
                TripodBakeKind.BUILDING -> 2
                else -> 3
            }
            assertTrue("队列顺序被打乱（kind=${u.kind}）", rank >= prevRank)
            prevRank = rank
        }
    }

    /** G5 单个单元的像素量有上界 ⇒「一帧多烘一个单元」的超调才有界。 */
    @Test
    fun queueUnitsArePixelBounded() {
        val q = TripodBakeQueue(
            TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H,
            TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H,
            34, 14, 1920, 1080,
        )
        assertTrue("队列为空，本闸门在空转", q.units.isNotEmpty())
        for (u in q.units) {
            when (u.kind) {
                TripodBakeKind.GRADE -> assertTrue(
                    "grade 单元 ${(u.b - u.a) * 1920} px 超上界",
                    (u.b - u.a) * 1920 <= TripodFogBake.BAKE_UNIT_PX_CAP,
                )

                TripodBakeKind.STRIP_A -> assertTrue(
                    "雾条 A 单元超上界",
                    (u.b - u.a) * TripodFogBake.STRIP_A_W <= TripodFogBake.BAKE_UNIT_PX_CAP,
                )

                TripodBakeKind.STRIP_B -> assertTrue(
                    "雾条 B 单元超上界",
                    (u.b - u.a) * TripodFogBake.STRIP_B_W <= TripodFogBake.BAKE_UNIT_PX_CAP,
                )

                TripodBakeKind.BUILDING -> { /* 一栋是原子的：96×≤320 = ≤30720 px */ }
                else -> { /* 人群 4×14×34，忽略 */ }
            }
        }
    }

    /** G6 画幅变化只重排 grade（楼已经烘好的⛔ 不许白烘一遍）。 */
    @Test
    fun gradeOnlyRequeueOnResize() {
        val q = TripodBakeQueue.gradeOnly(1280, 720)
        assertTrue("重排队列里还有非 grade 单元", q.units.all { it.kind == TripodBakeKind.GRADE })
        var rows = 0
        for (u in q.units) rows += u.b - u.a
        assertEquals(720, rows)
    }

    // ─────────────────────────── 源码闸门 ───────────────────────────

    /** G7 反空转对照：`boot()` 体必须非空且真的在建队列。 */
    @Test
    fun bootBodyIsReal() {
        val body = functionBody("boot")
        assertTrue("boot() 体被抽空了，闸门会在空转状态下一路绿灯", body.isNotBlank())
        assertTrue("boot() 不再建立分帧烘焙队列", body.contains("TripodBakeQueue("))
    }

    /**
     * G8 首帧⛔ 全量烘焙：`boot()` 里不得出现整张 grade / 整张雾条 / 全量建筑的同步入口，
     * 且每帧必须真的推进队列（`drawContent` 调 [bakeStep]）。
     */
    @Test
    fun firstFrameMustNotBakeEverything() {
        val boot = functionBody("boot")
        for (forbidden in listOf("bakeGrade(", "bakeFogStrips(", "bakePersons(", "bakeFogStrip(")) {
            assertFalse("boot() 又变成同步全量烘焙：$forbidden", boot.contains(forbidden))
        }
        assertFalse("boot() 里建筑应当只出几何", boot.contains("genBuildings(world, p, true)"))
        val draw = functionBody("drawContent")
        assertTrue("drawContent 每帧必须推进分帧烘焙（bakeStep）", draw.contains("bakeStep("))
    }

    /** G9 释放路径必须把队列与行块缓冲一起交出去（native 位图之外，Java 堆的暂存也得还）。 */
    @Test
    fun exitReleasesBakeBuffers() {
        val body = functionBody("onExitContent")
        assertTrue("onExitContent 没有清空分帧队列", body.contains("bakeQueue"))
        assertTrue("onExitContent 没有释放分帧行块缓冲", body.contains("bakePx"))
    }

    /**
     * G10 画幅变化时的**换代**队列：尚未消费的场景单元⛔ 不许丢、⛔ 不许重排到 grade 之后，
     * 而 grade 单元必须按新画幅重铺。
     *
     * 负样本：把 [TripodBakeQueue.requeueGrade] 改成直接返回 `gradeOnly(w, h)` ⇒
     * 「剩下的楼与雾」整段消失，本闸门红（真机上表现为画面永久缺一块且长不回来）。
     */
    @Test
    fun requeueGradeKeepsPendingSceneUnits() {
        val q = TripodBakeQueue(
            TripodFogBake.STRIP_A_W, TripodFogBake.STRIP_A_H,
            TripodFogBake.STRIP_B_W, TripodFogBake.STRIP_B_H,
            34, 14, 1920, 1080,
        )
        // 消费掉前 5 个单元（雾条 A 的头几块），剩下的必须原样带过去
        repeat(5) { assertNotNull("队列不足 5 个单元", q.next()) }
        val pendingScene = q.units.drop(5).filter { it.kind != TripodBakeKind.GRADE }
        assertTrue("前 5 个单元之后还有场景单元（本闸门的前提）", pendingScene.isNotEmpty())

        val n = q.requeueGrade(1280, 720)
        assertEquals("场景单元被丢了", pendingScene.size, n.units.take(pendingScene.size).count { it.kind != TripodBakeKind.GRADE })
        for (i in pendingScene.indices) {
            assertSame("场景单元次序变了 @$i", pendingScene[i], n.units[i])
        }
        val grade = n.units.drop(pendingScene.size)
        assertTrue("grade 单元没重排（还按 1080 行铺）", grade.isNotEmpty())
        assertTrue("换代队列里混进了第二个场景段", grade.all { it.kind == TripodBakeKind.GRADE })
        var rows = 0
        var last = -1
        for (u in grade) {
            assertEquals("grade 行块不连续", last + 1, u.a)
            last = u.b - 1
            rows += u.b - u.a
        }
        assertEquals("grade 必须按新画幅铺满", 720, rows)
        assertFalse("换代队列必须还没消费", n.done)
    }

    // ─────────────────────────── helpers（照 TripodFillMechanismGateTest）───────────────────────────

    private fun rendererSource(): String =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/TripodFogRenderer.kt").readText()

    private fun functionBody(name: String): String {
        val src = stripComments(rendererSource())
        val head = Regex("""fun\s+(?:DrawScope\.)?$name\s*\(""").find(src) ?: error("找不到 $name")
        var i = head.range.last
        while (src[i] != '{') i++
        var depth = 0
        var j = i
        while (j < src.length) {
            when (src[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(i + 1, j)
                }
            }
            j++
        }
        error("$name 大括号不配对")
    }

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")!!)
        repeat(6) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 app/src/main/java")
    }

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var blockDepth = 1
                while (i < src.length && blockDepth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { blockDepth++; i += 2 }
                    else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { blockDepth--; i += 2 }
                    else {
                        if (src[i] == '\n') sb.append('\n')
                        i++
                    }
                }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
