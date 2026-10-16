package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E48「大漠 · 孤烟」K5 门禁 —— `renderFrame` 的**图层序契约**（原型 §5.1 逐字移植）。
 *
 * ## 契约本体
 * ```
 * blit(sky) → drawSun()
 *   → for gi in 0..nG−1 {
 *         if (gi == carLayer) drawCaravan        ← 驼队在**本组沙丘之前**
 *         if (gi == srcLayer) { drawEmber; drawSmoke }   ← 烟在本组沙丘之前
 *         drawDune(gi)
 *     }
 *   → drawVeils() → drawGrade()
 * ```
 *
 * 两个条件分支是 **D4 遮挡语义的闸门**：骆驼 / 烟的根部被**后画**的沙丘画布吃掉
 * ⇒ 遮挡来自**图层顺序**。⛔ 换序、⛔ 把分支挪到 `drawDune` 之后 = 整只骆驼 / 整条烟根浮在沙面上。
 *
 * ## 判据形态
 * ① 逐字存在（九个标记一个不能少）；② 严格递增序；③ 两个分支必须**在循环体内**
 * （不是循环外、也不是 `drawDune` 之后）；④ 负向自证：把同一份谓词喂给**交换过顺序**的源码必须判红。
 *
 * ⛔ 判据跑在**已剥注释**的源码上：`renderFrame` 的 KDoc 里写着整个契约原文，
 *    不剥注释会把文档当成代码（假绿）。
 */
class DesertSmokeLayerOrderGateTest {

    /** 契约的九个标记，按出现顺序即为合法序。 */
    private val order = listOf(
        "bSky?.let { blitFull(c, it, w) }",
        "drawSun(c)",
        "while (gi < ng)",
        "if (gi == lay.carLayer) drawCaravan(c)",
        "if (gi == lay.srcLayer) {",
        "drawEmber(c)",
        "drawSmoke(c)",
        "drawDune(c, gi)",
        "drawVeils(w)",
        "bGrade?.let { blitFull(c, it, w) }",
    )

    /** 同一份谓词：给定源码与标记序，判是否严格递增。 */
    private fun isOrdered(src: String, markers: List<String>): Boolean {
        var prev = -1
        for (m in markers) {
            val i = src.indexOf(m)
            if (i < 0 || i <= prev) return false
            prev = i
        }
        return true
    }

    @Test
    fun renderFrameRespectsLayerOrderContract() {
        val src = strippedRenderer()
        // ① 九个标记逐字存在
        for (m in order) {
            assertTrue("renderFrame 必须含契约标记 `$m`", src.contains(m))
        }
        // ② 严格递增序（同一份谓词）
        assertTrue("图层序必须严格遵循契约顺序", isOrdered(src, order))
        // ③ 两个条件分支必须在循环体内（⛔ 循环外 / drawDune 之后）
        val loopOpen = src.indexOf("while (gi < ng)")
        assertTrue("必须有组循环 while (gi < ng)", loopOpen >= 0)
        var depth = 0
        var loopClose = -1
        var i = src.indexOf('{', loopOpen)
        while (i < src.length) {
            val c = src[i]
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) {
                    loopClose = i
                    break
                }
            }
            i++
        }
        assertTrue("循环体括号必须闭合", loopClose > 0)
        for (m in listOf(
            "if (gi == lay.carLayer) drawCaravan(c)",
            "if (gi == lay.srcLayer) {",
            "drawEmber(c)",
            "drawSmoke(c)",
            "drawDune(c, gi)",
        )) {
            val at = src.indexOf(m)
            assertTrue("`$m` 必须在组循环**体内**（D4 遮挡依赖'后画的沙丘吃掉前景物'）",
                at > loopOpen && at < loopClose)
        }
        // 驼队分支必须在烟分支之前（先驼后烟，同一组内两者互不遮挡）
        assertTrue(
            "驼队分支必须在烟分支之前",
            src.indexOf("if (gi == lay.carLayer) drawCaravan(c)") < src.indexOf("if (gi == lay.srcLayer) {"),
        )
        // ④ 负向自证：同一份谓词喂给交换过顺序的源码必须判红
        val swapped = src.replace(
            "if (gi == lay.carLayer) drawCaravan(c)\n            if (gi == lay.srcLayer) {\n                drawEmber(c)\n                drawSmoke(c)\n            }\n            drawDune(c, gi)",
            "drawDune(c, gi)\n            if (gi == lay.carLayer) drawCaravan(c)\n            if (gi == lay.srcLayer) {\n                drawEmber(c)\n                drawSmoke(c)\n            }",
        )
        assertTrue("负样本必须真的被替换过（否则这条自证是空转的）", swapped != src)
        assertTrue("负样本：分支挪到 drawDune 之后 ⇒ 同一份谓词必须判红", !isOrdered(swapped, order))
        val veilsFirst = src.replace("drawVeils(w)", "bGrade?.let { blitFull(c, it, w) }\n        drawVeils(w)")
            .replace("bGrade?.let { blitFull(c, it, w) }\n        bGrade?.let { blitFull(c, it, w) }", "bGrade?.let { blitFull(c, it, w) }")
        assertTrue(
            "负样本：调色提前到浮沙带之前 ⇒ 同一份谓词必须判红",
            !isOrdered(veilsFirst, order),
        )
    }

    private fun strippedRenderer(): String = stripComments(
        File(mainSourceRoot(), "renderers/DesertSmokeRenderer.kt").readText(),
    )

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
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
}
