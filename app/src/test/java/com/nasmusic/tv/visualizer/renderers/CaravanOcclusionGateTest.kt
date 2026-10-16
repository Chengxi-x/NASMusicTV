package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * E48「大漠 · 孤烟」K6 门禁 —— 驼队的 **T9 / T10 数值判据** + **D4 遮挡结构闸门**。
 *
 * ## D4 是什么
 * 原型 §5.1 的图层序里，骆驼画在 `drawDune(carLayer)` **之前** ⇒ 腿被**后画**的沙丘画布吃掉
 * —— 遮挡来自**图层顺序**，⛔ 不来自"干脆不画腿"。
 *
 * 因此 `camelFootY` 必须从 **`groupTopCrest(carLayer, x)`**（该组所有脊的最高点）现取：
 * - 分组后画布顶边 = 组内最高脊，取单条脊会整只埋进画布；
 * - ⛔⛔ 这个值**不是** `Params` 的字段、也⛔ 不许搬进参数表 ——
 *   一旦入表，"每帧跟着 layout 重算"就退化成"方案里钉死的一个数"，
 *   换档位 / 换画幅时脚线与画布顶边会**静默漂移**（腿露一截或整只消失）。
 *
 * ## §八之二 ⓒ
 * `carLayer < nG − 1 && srcLayer < nG − 1`：两个分支所在层的**后面必须还有画布**
 * （否则"被吃掉"这条链路根本没有执行者 —— P5「驼队整批消失」曾在 14/14 全绿下重现）。
 *
 * ## 纪律
 * ⛔ 绝不构造 `DesertSmokeRenderer()`；⛔ 源码判据先剥注释（本文件 KDoc 里故意举了反例）。
 */
class CaravanOcclusionGateTest {

    private val w = 1600.0
    private val h = 900.0

    private fun freshLayout(): Pair<DesertP, DesertLayout> {
        val p = DesertP()
        val lay = DesertLayout()
        lay.layout(w, h, p)
        return p to lay
    }

    /** T9：驼峰露出比例必须落在 [0.40, 0.85]，且脚线**必须**压在脊线之下（`off > 0`）。 */
    @Test
    fun t9_camelHumpVisibleRatio() {
        val (p, _) = freshLayout()
        val cp = p.caravan
        var visMin = Double.MAX_VALUE
        var visMax = -Double.MAX_VALUE
        var above = 0
        var i = 0
        while (i < cp.camels) {
            val jit = DesertMath.hash2(i * 5 + 1, 1)
            var b = -1
            while (b <= 1) {
                val bob = b * 0.035
                val off = cp.occl * (0.62 + 0.38 * jit) + bob
                if (off <= 0) above++
                val v = camelVisible(jit, bob, cp)
                visMin = min(visMin, v)
                visMax = max(visMax, v)
                b++
            }
            i++
        }
        assertTrue("每只骆驼的脚线都必须压在脊线之下（算出 off ≤ 0 的有 $above 只）", above == 0)
        assertTrue("露出比必须在 [0.40, 0.85]（实测 [$visMin, $visMax]，occl = ${cp.occl}h）",
            visMin >= 0.40 && visMax <= 0.85)
        // 负样本：occl 砍到 0.10 ⇒ 整只浮在沙丘上
        val save = cp.occl
        cp.occl = 0.10
        try {
            val floating = camelVisible(0.5, 0.0, cp)
            assertTrue("负样本：occl → 0.10 ⇒ 露出 $floating 必须 > 0.85（整只浮起来即变红）", floating > 0.85)
        } finally {
            cp.occl = save
        }
    }

    /** T10：驼队必须"偶尔出现"（1800 s 内 12–20 波、波间空窗 ∈ [idle0, idle1]）。 */
    @Test
    fun t10_caravanIsOccasional() {
        val (p, _) = freshLayout()
        val car = DesertCaravanTable()
        car.build(p.caravan)
        val waves = car.wavesWithin(1800.0)
        var gMin = Double.MAX_VALUE
        var gMax = -Double.MAX_VALUE
        var i = 1
        while (i < car.starts.size) {
            val g = car.starts[i] - car.starts[i - 1] - car.cross
            gMin = min(gMin, g)
            gMax = max(gMax, g)
            i++
        }
        assertTrue("1800 s 内波数必须在 12–20（实测 $waves）", waves >= 12 && waves <= 20)
        assertTrue(
            "波间空窗必须在 [${p.caravan.idle0}, ${p.caravan.idle1}]（实测 [$gMin, $gMax]，横穿 ${car.cross}s）",
            gMin >= p.caravan.idle0 - 1e-6 && gMax <= p.caravan.idle1 + 1e-6,
        )
        // 负样本：空窗压到 2–8 s ⇒ 变成"一直在走"
        val a0 = p.caravan.idle0
        val a1 = p.caravan.idle1
        p.caravan.idle0 = 2
        p.caravan.idle1 = 8
        try {
            val c2 = DesertCaravanTable()
            c2.build(p.caravan)
            val busy = c2.wavesWithin(1800.0)
            var gm = Double.MAX_VALUE
            var k = 1
            while (k < c2.starts.size) {
                gm = min(gm, c2.starts[k] - c2.starts[k - 1] - c2.cross)
                k++
            }
            assertTrue("负样本：空窗压到 2–8 s ⇒ 波数 $busy 必须 > 20", busy > 20)
            assertTrue("负样本：空窗压到 2–8 s ⇒ 最小空窗 $gm s 必须 < idle0", gm < a0)
        } finally {
            p.caravan.idle0 = a0
            p.caravan.idle1 = a1
        }
    }

    /**
     * D4 结构闸门：`camelFootY` 只能由 `groupTopCrest(carLayer, x)` 现取。
     *
     * 做法：把 `cp.h` 归零 ⇒ 偏移项整体消失 ⇒ `footY` 必须**恒等于**组顶；
     * 同时必须**不等于**单条脊 `crest(carRidge, x)`（实测差 47 px ⇒ 这条闸门不是空转的）。
     */
    @Test
    fun footYComesFromGroupTopCrest() {
        val (p, lay) = freshLayout()
        val cp = p.caravan
        val ng = lay.dunes[0].nG
        // ① §八之二 ⓒ：两个分支所在层的后面必须还有画布
        assertTrue("carLayer = ${lay.carLayer} 必须 < nG−1 = ${ng - 1}（否则没有画布能挡住驼腿）",
            lay.carLayer < ng - 1)
        assertTrue("srcLayer = ${lay.srcLayer} 必须 < nG−1 = ${ng - 1}（否则没有画布能挡住烟根）",
            lay.srcLayer < ng - 1)
        // ② 先找一个"组顶 ≠ 单条脊"的 x（找不到 ⇒ 本闸门空转）
        var x = 0.0
        var probe = -1.0
        while (x <= w) {
            if (lay.groupTopCrest(lay.carLayer, x) < lay.crest(lay.carRidge, x) - 1e-6) {
                probe = x
                break
            }
            x += 7.0
        }
        assertTrue("必须能找到'组顶严格高于单条脊'的 x（否则本闸门空转）", probe >= 0.0)
        // ③ cp.h = 0 ⇒ footY 恒等于组顶，且 ≠ 单条脊
        val saveH = cp.h
        cp.h = 0.0
        try {
            val foot = camelFootY(probe, 0.5, 0.0, lay, cp)
            val grp = lay.groupTopCrest(lay.carLayer, probe)
            val single = lay.crest(lay.carRidge, probe)
            assertTrue("cp.h = 0 时 footY 必须恒等于 groupTopCrest(carLayer, x) = $grp（实测 $foot）",
                abs(foot - grp) < 1e-9)
            assertTrue("⛔ footY 不许等于单条脊 crest(carRidge, x) = $single（实测 $foot，差 ${single - foot} px）",
                abs(foot - single) > 1e-6)
        } finally {
            cp.h = saveH
        }
    }

    /** D4 的源码侧：`footY` ⛔ 不许入 `Params`（错误的那条路必须不存在）。 */
    @Test
    fun footYIsNotAParam() {
        val raw = src("renderers/DesertSmokeCrest.kt", strip = false)
        val stripped = src("renderers/DesertSmokeCrest.kt", strip = true)
        // 成对自证：原文 KDoc 里就举了 camelFootY 这个反例
        assertTrue("自证：DesertSmokeCrest.kt 原文 KDoc 里就写着 camelFootY（不剥注释会假 FAIL）",
            raw.contains("camelFootY"))
        assertTrue(
            "自证：剥注释后 FootY 必须一次都不剩（实测 ${countOf("FootY", stripped)} 次）",
            countOf("FootY", stripped) == 0,
        )
        // 参数表（DesertP / DesertCaravanP）里不许出现脚线字段
        for (field in listOf("footY", "footY0", "soleY", "ridgeY")) {
            assertTrue("⛔ 参数表不许出现脚线字段 $field（实测 ${countOf(field, stripped)} 次）",
                countOf(field, stripped) == 0)
        }
        // 真实调用点必须在 Caravan.kt 里，且只此一次
        val caravanSrc = src("renderers/Caravan.kt", strip = true)
        val calls = countOf("lay.groupTopCrest(lay.carLayer, x)", caravanSrc)
        assertTrue("camelFootY 必须（且只）在 Caravan.kt 里取一次 groupTopCrest(carLayer, x)（实测 $calls 次）",
            calls == 1)
    }

    // ─────────────────────── 源码工具（照抄 PerfBudgetContractTest 的 mainSourceRoot 手法） ───────────────────────

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    private fun src(rel: String, strip: Boolean): String {
        val f = File(mainSourceRoot(), rel)
        assertTrue("找不到源码 $rel（${f.absolutePath}）", f.isFile)
        val raw = f.readText()
        return if (strip) stripComments(raw) else raw
    }

    /** 剥掉块注释（可嵌套）与行注释；**保持行数不变**（与 PerfBudgetContractTest 同一实现）。 */
    private fun stripComments(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        var inString = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < s.length && depth > 0) {
                    if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '*') {
                        depth++
                        i += 2
                        continue
                    }
                    if (s[i] == '*' && i + 1 < s.length && s[i + 1] == '/') {
                        depth--
                        i += 2
                        continue
                    }
                    if (s[i] == '\n') sb.append('\n')
                    i++
                }
                continue
            }
            if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                while (i < s.length && s[i] != '\n') i++
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun countOf(needle: String, s: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }
}
