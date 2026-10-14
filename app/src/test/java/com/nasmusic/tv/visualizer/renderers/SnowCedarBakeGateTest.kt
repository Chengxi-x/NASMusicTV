package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * E47 K3 闸门：**分帧烘焙队列**的形状 + **逐像素函数**的分块等价性。
 *
 * ## 为什么需要它（E46 `TripodProgressiveBakeGateTest` 的同一条教训）
 * 冷进入黑屏的根因不是"烘得慢"，是**把全部素材压在一帧里同步烘完**。修法是预算制分帧队列，
 * 而队列一分帧就带来三类只有测才抓得到的错：
 * ① **顺序**：`sky` 底色必须第一个（首帧必出画面）、`grade` 必须最后（缺失时画面仍完整）、
 *    `ground` 的 `destination-out` 裁线必须排在该图所有内容之后（切完还在往缺口里填色）；
 * ② **覆盖**：分块必须不重不漏地盖住 `[0, total)`（漏一块 = 树上少一片雪，且**看着像美术问题**）；
 * ③ **粒度**：行块单元的像素数必须守住 [SnowCedarBakes.UNIT_PX_CAP]，否则"超调最多一个单元"
 *    的预算账当场作废（⚠️ 三条带与 grade 的画幅各不相同，队列必须**逐个**拿尺寸）。
 *
 * ## ⛔ 这里没有像素级跨语言对账
 * 原型的验证沙箱里 `getImageData` 读不到 Canvas 内容（E44/E46 同一限制），所以落笔的**形状**
 * 只能靠 [SnowCedarGoldenTest] 里那些"实际消费的顶点"对账，位图像素本身靠真机截图判读。
 * 本文件测的是**不需要 `Bitmap`/`Canvas` 就能算**的那部分：队列形状，和两个纯像素函数。
 */
internal class SnowCedarBakeGateTest {

    private companion object {
        const val T2 = 1e-9
        const val BAND_W = 1600
        const val CLOUD_H = 306
        const val VEIL_FAR_H = 144
        const val VEIL_NEAR_H = 198
        const val GRADE_W = 1600
        const val GRADE_H = 900
        const val SEGS = 1200
        const val SNOW_BANDS = 410
        const val CLUMPS = 133

        fun queue(segs: Int = SEGS, snowBands: Int = SNOW_BANDS, clumps: Int = CLUMPS) =
            SnowBakeQueue(BAND_W, CLOUD_H, VEIL_FAR_H, VEIL_NEAR_H, GRADE_W, GRADE_H, segs, snowBands, clumps)

        /** 每个 kind 的**元素总数**（与 [SnowCedarBakes] 里 `min(N, to)` 的 N 必须是同一份账）。 */
        fun totalOf(kind: Int, segs: Int, snowBands: Int, clumps: Int): Int? = when (kind) {
            SnowBakeKind.SKY_BLOBS -> SnowCedarBakes.SKY_BLOBS
            SnowBakeKind.GROUND_TONES -> SnowCedarBakes.GROUND_TONES
            SnowBakeKind.GROUND_VEINS -> SnowCedarBakes.GROUND_VEINS
            SnowBakeKind.GROUND_MOUNDS -> SnowCedarBakes.GROUND_MOUNDS
            SnowBakeKind.GROUND_UNDUL -> SnowCedarBakes.GROUND_UNDUL
            SnowBakeKind.GROUND_SPALL -> SnowCedarBakes.GROUND_SPALL
            SnowBakeKind.TREE_SEGS -> segs
            SnowBakeKind.TREE_SNOW_BASE -> snowBands
            SnowBakeKind.TREE_SNOW_RIDGE -> snowBands
            SnowBakeKind.TREE_CLUMPS -> clumps
            else -> null
        }

        /** 行块类 kind 的画幅（⛔ 一套 `cw × ch` 包打天下：三条带与 grade 各不相同）。 */
        fun rowSizeOf(kind: Int): Pair<Int, Int>? = when (kind) {
            SnowBakeKind.BAND_CLOUD -> BAND_W to CLOUD_H
            SnowBakeKind.BAND_VEIL_FAR -> BAND_W to VEIL_FAR_H
            SnowBakeKind.BAND_VEIL_NEAR -> BAND_W to VEIL_NEAR_H
            SnowBakeKind.GRADE -> GRADE_W to GRADE_H
            else -> null
        }

        val CHUNK_KINDS = intArrayOf(
            SnowBakeKind.SKY_BLOBS, SnowBakeKind.GROUND_TONES, SnowBakeKind.GROUND_VEINS,
            SnowBakeKind.GROUND_MOUNDS, SnowBakeKind.GROUND_UNDUL, SnowBakeKind.GROUND_SPALL,
            SnowBakeKind.TREE_SEGS, SnowBakeKind.TREE_SNOW_BASE, SnowBakeKind.TREE_SNOW_RIDGE,
            SnowBakeKind.TREE_CLUMPS,
        )

        val ROW_KINDS = intArrayOf(
            SnowBakeKind.BAND_CLOUD, SnowBakeKind.BAND_VEIL_FAR,
            SnowBakeKind.BAND_VEIL_NEAR, SnowBakeKind.GRADE,
        )

        /** 雪原内部「裁线之前必须先画完」的那些单元。 */
        val BEFORE_CUT = intArrayOf(
            SnowBakeKind.GROUND_BASE, SnowBakeKind.GROUND_TONES, SnowBakeKind.GROUND_VEINS,
            SnowBakeKind.GROUND_MOUNDS, SnowBakeKind.GROUND_TREE, SnowBakeKind.GROUND_UNDUL,
            SnowBakeKind.GROUND_FOOT,
        )

        fun lastIndexOfKind(units: List<SnowBakeUnit>, kind: Int): Int {
            var r = -1
            for (i in units.indices) if (units[i].kind == kind) r = i
            return r
        }

        fun firstIndexOfKind(units: List<SnowBakeUnit>, kind: Int): Int {
            for (i in units.indices) if (units[i].kind == kind) return i
            return -1
        }
    }

    // ─────────────────────────── 队列形状：顺序 ───────────────────────────

    /** G1 首帧必出画面：`sky` 底色是 0 号单元；grade 是**末尾连续一段**（⛔ 挤占前景帧预算）。 */
    @Test
    fun skyBaseIsFirstAndGradeIsLast() {
        val q = queue()
        assertEquals("0 号单元必须是 sky 底色", SnowBakeKind.SKY_BASE, q.units[0].kind)
        assertEquals("末位单元必须是 grade", SnowBakeKind.GRADE, q.units.last().kind)
        val n = q.units.count { it.kind == SnowBakeKind.GRADE }
        assertTrue(
            "grade 必须是末尾连续一段，中间夹了别的单元就会先烘暗角再烘树",
            q.units.takeLast(n).all { it.kind == SnowBakeKind.GRADE },
        )
    }

    /** G2 `destination-out` 裁线排在该图全部内容之后；碎雪堆补在裁线**之后**（它补的就是缺口）。 */
    @Test
    fun groundCutSitsBetweenContentAndSpall() {
        val u = queue().units
        val cut = firstIndexOfKind(u, SnowBakeKind.GROUND_CUT)
        assertTrue("队列里没有 ground 裁线单元", cut > 0)
        for (k in BEFORE_CUT) {
            val i = lastIndexOfKind(u, k)
            assertTrue("ground 裁线排在了 ${name(k)}（#$i）之前 ⇒ 切完还在往缺口里填色", i in 0 until cut)
        }
        assertTrue(
            "碎雪堆必须补在裁线之后",
            firstIndexOfKind(u, SnowBakeKind.GROUND_SPALL) > lastIndexOfKind(u, SnowBakeKind.GROUND_CUT),
        )
    }

    /** G3 有前后依赖的三对：天幕质在增亮带之前、松林柔化在绘制之后、树裙在树的全部段之后。 */
    @Test
    fun dependentUnitsComeAfterTheirPrerequisites() {
        val u = queue().units
        assertTrue(
            "絮状云质必须整批烘在 skyHorizon 之前（增亮带盖在它上面）",
            lastIndexOfKind(u, SnowBakeKind.SKY_BLOBS) < firstIndexOfKind(u, SnowBakeKind.SKY_HORIZON),
        )
        assertEquals(
            "柔化必须紧接绘制（中间插别的单元就会读到半张松林）",
            firstIndexOfKind(u, SnowBakeKind.PINES_DRAW) + 1,
            firstIndexOfKind(u, SnowBakeKind.PINES_SOFTEN),
        )
        for (k in intArrayOf(
            SnowBakeKind.TREE_SEGS, SnowBakeKind.TREE_SNOW_BASE,
            SnowBakeKind.TREE_SNOW_RIDGE, SnowBakeKind.TREE_CLUMPS,
        )) {
            assertTrue("树裙排在了 ${name(k)} 之前", lastIndexOfKind(u, k) < firstIndexOfKind(u, SnowBakeKind.TREE_SKIRT))
        }
    }

    // ─────────────────────────── 队列形状：覆盖与粒度 ───────────────────────────

    /** G4 每个分块 kind 必须**不重不漏**盖住 `[0, total)`：`a` 从 0 起、`b` 首尾相接、末块到 total。 */
    @Test
    fun chunksCoverTheirRangeWithoutGaps() {
        val q = queue()
        for (kind in CHUNK_KINDS) {
            val total = totalOf(kind, SEGS, SNOW_BANDS, CLUMPS)!!
            val us = q.units.filter { it.kind == kind }
            assertEquals("${name(kind)} 没排满 ⇒ 漏一块就是少一片雪", true, us.isNotEmpty())
            assertEquals("${name(kind)} 首块起点", 0, us.first().a)
            assertEquals("${name(kind)} 末块终点", total, us.last().b)
            for (i in us.indices) {
                val x = us[i]
                assertTrue("${name(kind)}[$i] 空块或倒挂 [${x.a},${x.b})", x.b > x.a)
                if (i > 0) assertEquals("${name(kind)} 第 $i 块与前一块断裂", us[i - 1].b, x.a)
            }
        }
    }

    /** G5 行块单元的像素数必须 ≤ [SnowCedarBakes.UNIT_PX_CAP]，且按**各自**画幅盖住 `[0,h)`。 */
    @Test
    fun rowUnitsArePixelBoundedAndCoverTheirOwnHeight() {
        val q = queue()
        for (kind in ROW_KINDS) {
            val (w, h) = rowSizeOf(kind)!!
            val us = q.units.filter { it.kind == kind }
            assertEquals("${name(kind)} 首块起点", 0, us.first().a)
            assertEquals("${name(kind)} 末块终点（⛔ 用别的带的画幅就会在这里红）", h, us.last().b)
            for (i in us.indices) {
                val x = us[i]
                val px = (x.b - x.a) * w
                assertTrue("${name(kind)}[$i] 超预算：$px px > ${SnowCedarBakes.UNIT_PX_CAP}", px <= SnowCedarBakes.UNIT_PX_CAP)
                if (i > 0) assertEquals("${name(kind)} 第 $i 块与前一块断裂", us[i - 1].b, x.a)
            }
        }
    }

    /** G6 空组**一个单元也不排**（旧的dummy 单元形状：`total=0` 还塞一块 ⇒ 白占一次预算判定）。 */
    @Test
    fun emptyGroupsAreNotQueued() {
        val q = queue(segs = 0, snowBands = 0, clumps = 0)
        for (kind in intArrayOf(
            SnowBakeKind.TREE_SEGS, SnowBakeKind.TREE_SNOW_BASE,
            SnowBakeKind.TREE_SNOW_RIDGE, SnowBakeKind.TREE_CLUMPS,
        )) {
            assertEquals("${name(kind)} 为空时不该有任何单元", 0, q.units.count { it.kind == kind })
        }
        assertTrue("非树单元被连带删掉了", q.units.any { it.kind == SnowBakeKind.PERSON })
    }

    /** G7 消费语义：`next()` 按序吐单元、吐完返回 null、`done` 与之一致。 */
    @Test
    fun cursorConsumesInOrderAndStops() {
        val q = queue()
        var i = 0
        while (true) {
            val u = q.next() ?: break
            assertSame("第 $i 个单元顺序不符", q.units[i], u)
            i++
            assertEquals("done 必须在最后一个单元之后才置位", i == q.units.size, q.done)
        }
        assertEquals(q.units.size, i)
        assertNull("耗尽后必须恒返回 null", q.next())
        assertTrue(q.done)
    }

    private fun name(kind: Int): String = when (kind) {
        SnowBakeKind.SKY_BASE -> "skyBase"
        SnowBakeKind.SKY_BLOBS -> "skyBlobs"
        SnowBakeKind.SKY_HORIZON -> "skyHorizon"
        SnowBakeKind.GROUND_BASE -> "groundBase"
        SnowBakeKind.GROUND_TONES -> "groundTones"
        SnowBakeKind.GROUND_VEINS -> "groundVeins"
        SnowBakeKind.GROUND_MOUNDS -> "groundMounds"
        SnowBakeKind.GROUND_TREE -> "groundTreeMound"
        SnowBakeKind.GROUND_UNDUL -> "groundUndulations"
        SnowBakeKind.GROUND_FOOT -> "groundFoot"
        SnowBakeKind.GROUND_CUT -> "groundCut"
        SnowBakeKind.GROUND_SPALL -> "groundSpall"
        SnowBakeKind.TREE_SEGS -> "treeSegs"
        SnowBakeKind.TREE_SNOW_BASE -> "treeSnowBase"
        SnowBakeKind.TREE_SNOW_RIDGE -> "treeSnowRidge"
        SnowBakeKind.TREE_CLUMPS -> "treeClumps"
        SnowBakeKind.TREE_SKIRT -> "treeSkirt"
        SnowBakeKind.PINES_DRAW -> "pinesDraw"
        SnowBakeKind.PINES_SOFTEN -> "pinesSoften"
        SnowBakeKind.PERSON -> "person"
        SnowBakeKind.BAND_CLOUD -> "bandCloud"
        SnowBakeKind.BAND_VEIL_FAR -> "bandVeilFar"
        SnowBakeKind.BAND_VEIL_NEAR -> "bandVeilNear"
        SnowBakeKind.GRADE -> "grade"
        else -> "kind$kind"
    }

    // ─────────────────────── 逐像素函数：分块 ≡ 整张 ───────────────────────

    /**
     * G8 带状位图分行烘 ≡ 整张烘。
     *
     * ⭐ 这条是**分帧的合法性证明**：`bandRows` 每个像素只吃 `(i,j)`，没有任何跨行状态 ⇒
     * 先显示上半屏不会与最终结果差一个像素。反过来，一旦有人加进"上一行的残值"
     * （例如逐行推进的 RNG 或累积的 y），这里立刻红。
     */
    @Test
    fun bandRowsChunkingEqualsWholeBake() {
        val p = SnowCedarP().apply { applyLevel(SnowLevel.MEDIUM) }
        val l = SnowCedarLayout().apply { layout(1600.0, 900.0, p) }
        val b = SnowCedarBakes(p, l)
        val w = 137       // ⚠️ 故意取奇数：偶数宽会把"行偏移算错一半"这类 bug 掩成对齐
        val h = 83
        val whole = IntArray(w * h)
        b.bandRows(whole, w, h, 4, 0.30, 2.7, 0, h)
        for (chunk in intArrayOf(1, 7, 64, h)) {
            val px = IntArray(w * h)
            var y = 0
            while (y < h) {
                val y1 = min(y + chunk, h)
                val band = IntArray((y1 - y) * w)      // 行带**自己的**缓冲 ⇒ 生产的真实形状
                b.bandRows(band, w, h, 4, 0.30, 2.7, y, y1)
                System.arraycopy(band, 0, px, y * w, band.size)
                y = y1
            }
            assertEquals("bandRows 按 $chunk 行分块与整张烘不一致", whole.toList(), px.toList())
        }
    }

    /** G9 grade 分行烘 ≡ 整张烘：MED（颗粒在）与 LOW（颗粒关）两条路径都要成立。 */
    @Test
    fun gradeRowsChunkingEqualsWholeBake() {
        for (level in SnowLevel.entries) {
            val p = SnowCedarP().apply { applyLevel(level) }
            val l = SnowCedarLayout().apply { layout(1600.0, 900.0, p) }
            val b = SnowCedarBakes(p, l)
            val w = 200
            val h = 120
            val whole = IntArray(w * h)
            b.gradeRows(whole, w, h, 0, h)
            for (chunk in intArrayOf(1, 13, h)) {
                val px = IntArray(w * h)
                var y = 0
                while (y < h) {
                    val y1 = min(y + chunk, h)
                    val band = IntArray((y1 - y) * w)
                    b.gradeRows(band, w, h, y, y1)
                    System.arraycopy(band, 0, px, y * w, band.size)
                    y = y1
                }
                assertEquals("${level} grade 按 $chunk 行分块与整张烘不一致", whole.toList(), px.toList())
            }
        }
    }

    /**
     * G10 grade 写入的是**未预乘**像素：源色 `(40,46,58)` 原样进 RGB，alpha 只住 A 字节。
     *
     * 权威依据是 `Bitmap.java` 里 `setPixels` 的契约原文——"Each element in the array is a
     * packed int representing a **non-premultiplied** ARGB Color"（本机 SDK 源
     * `sources/android-36.1/android/graphics/Bitmap.java`，`getPixels`/`setPixel` 同一句措辞）。
     *
     * ⚠️ 本函数的**第一版**写的却是逐通道预乘（`R = round(40·A/255)`），口径照搬了原型里
     * JS 侧的语义直觉；真后果是 Skia 落位图时**再乘一次** ⇒ 暗角强度按 `A/255` 平方衰减，
     * 四角几乎不变暗。所以这里的负样本就是"逐通道乘 A"那种写法：只要有人改回去，
     * `assertEquals(40, r)` 会在第一批 `A < 255` 的像素上直接红。
     *
     * ⛔ 全 0 或全 255 的画幅会让判据空转 ⇒ 末条 `mid > 100` 强制画幅里存在中间调子。
     */
    @Test
    fun gradePixelsAreUnpremultiplied() {
        val p = SnowCedarP().apply { applyLevel(SnowLevel.MEDIUM) }
        val l = SnowCedarLayout().apply { layout(1600.0, 900.0, p) }
        val b = SnowCedarBakes(p, l)
        val w = 200
        val h = 120
        val px = IntArray(w * h)
        b.gradeRows(px, w, h, 0, h)
        var mid = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val v = px[j * w + i]
                val a = (v ushr 24) and 255
                assertEquals("($i,$j) R 被预乘过（setPixels 要吃未预乘值）", 40, (v shr 16) and 255)
                assertEquals("($i,$j) G 被预乘过", 46, (v shr 8) and 255)
                assertEquals("($i,$j) B 被预乘过", 58, v and 255)
                if (a > 0 && a < 255) mid++
            }
        }
        assertTrue("画幅里没有半透明像素 ⇒ 未预乘判据在空转（换一个会出中间调子的画幅）", mid > 100)
    }

    /**
     * G10b 三条带同样必须是未预乘，且源色恒为**纯白**：`R=G=B=255`，雪絮的浓淡只由 A 表达。
     *
     * 逐字对应原型 `bakeBand()` 的 `d[k]=d[k+1]=d[k+2]=255; d[k+3]=round(a*255)`。
     * 若把预乘漏进这里，白色与"白乘自己"数值相同 ⇒ RGB 看起来没错，但**真机的合成**会把带子
     * 压成灰斑（背景是亮灰的天空，`src-over` 下 alpha 平方衰减就是变暗）——所以判据必须
     * 直接钉住 255，而不是"RGB 三者相等"这种预乘也过得去的弱条件。
     */
    @Test
    fun bandPixelsAreUnpremultipliedWhite() {
        val p = SnowCedarP().apply { applyLevel(SnowLevel.MEDIUM) }
        val l = SnowCedarLayout().apply { layout(1600.0, 900.0, p) }
        val b = SnowCedarBakes(p, l)
        val w = 137
        val h = 83
        val px = IntArray(w * h)
        b.bandRows(px, w, h, 4, 0.30, 2.7, 0, h)
        var mid = 0
        for (j in 0 until h) {
            for (i in 0 until w) {
                val v = px[j * w + i]
                val a = (v ushr 24) and 255
                assertEquals("($i,$j) 带子源色不是纯白", (0xFF shl 16) or (0xFF shl 8) or 0xFF, v and 0x00FFFFFF)
                if (a > 0 && a < 255) mid++
            }
        }
        assertTrue("$w×$h 的带子里没有半透明像素 ⇒ 纯白判据在空转", mid > 100)
    }

    /**
     * G11 三条带的画幅 + `fbm` 参数表（[SnowCedarBakes.bandSpec]）必须与原型 `bakeAll()` 逐字一致。
     *
     * ⚠️ 期望值由原型表达式在 node 里求得（⛔ 手算）：
     * `node -e "for (const [W,H] of [[1600,900],[1280,720],[200,100]]) console.log(Math.max(256,Math.round(W)), Math.max(64,Math.round(H*0.34)), Math.max(48,Math.round(H*0.16)), Math.max(48,Math.round(H*0.22)), Math.ceil(W*1.04), Math.ceil(H*0.20))"`
     * 第三个画幅专门踩 `max(…)` 下限：`200 < 256`、`round(100·0.34)=34 < 64` ⇒ 不夹一下带子会比屏还矮。
     */
    @Test
    fun bandSpecMatchesPrototypeTable() {
        val rows = arrayOf(
            //        W      H     bandW cloud veilFar veilNear
            intArrayOf(1600, 900, 1600, 306, 144, 198),
            intArrayOf(1280, 720, 1280, 245, 115, 158),
            intArrayOf(200, 100, 256, 64, 48, 48),
        )
        val spec = arrayOf(
            SnowBakeKind.BAND_CLOUD to intArrayOf(4, 30, 27),     // oct, alpha×100, ySeed×10
            SnowBakeKind.BAND_VEIL_FAR to intArrayOf(3, 42, 81),
            SnowBakeKind.BAND_VEIL_NEAR to intArrayOf(3, 55, 43),
        )
        for (r in rows) {
            val p = SnowCedarP().apply { applyLevel(SnowLevel.MEDIUM) }
            val l = SnowCedarLayout().apply { layout(r[0].toDouble(), r[1].toDouble(), p) }
            val b = SnowCedarBakes(p, l)
            for ((i, s) in spec.withIndex()) {
                val k = s.first
                val got = b.bandSpec(k)
                assertTrue("${name(k)} 不在带表里 ⇒ 渲染器拿不到参数", got != null)
                val x = got!!
                assertEquals("${r[0]}×${r[1]} ${name(k)} 宽", r[2], x.w)
                assertEquals("${r[0]}×${r[1]} ${name(k)} 高", r[3 + i], x.h)
                assertEquals("${name(k)} oct", s.second[0], x.oct)
                assertEquals("${name(k)} alpha", s.second[1].toDouble(), SnowCedarMath.jsRound(x.alpha * 100), T2)
                assertEquals("${name(k)} ySeed", s.second[2].toDouble(), SnowCedarMath.jsRound(x.ySeed * 10), T2)
            }
            assertTrue("${r[0]}×${r[1]} 未知 kind 不该有带规格", b.bandSpec(SnowBakeKind.GRADE) == null)
        }
    }

    /** G12 松林位图 = `ceil(W·1.04) × ceil(H·0.20)`：横向留 4% 让边上的树压出屏外，纵向只占五分之一屏。 */
    @Test
    fun pineBitmapSizeMatchesPrototype() {
        val rows = arrayOf(
            intArrayOf(1600, 900, 1664, 180),
            intArrayOf(1280, 720, 1332, 144),
            intArrayOf(200, 100, 208, 20),
        )
        for (r in rows) {
            val p = SnowCedarP().apply { applyLevel(SnowLevel.MEDIUM) }
            val l = SnowCedarLayout().apply { layout(r[0].toDouble(), r[1].toDouble(), p) }
            val (w, h) = SnowCedarBakes(p, l).pineSize()
            assertEquals("${r[0]}×${r[1]} 松林宽", r[2], w)
            assertEquals("${r[0]}×${r[1]} 松林高", r[3], h)
        }
    }
}
