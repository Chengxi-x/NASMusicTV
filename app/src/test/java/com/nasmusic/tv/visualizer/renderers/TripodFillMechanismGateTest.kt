package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E46 填充机制闸门（所有者报障「电视上腿/机身只有描边没有填充」的回归护栏）。
 *
 * ## 真机实测（2026-10-13，创维 E46 / API 22）
 * 探针 2 在帧末把**真实**的三条腿各用一种画法重画一遍，连拍 80 帧逐像素数色：
 * - 屏幕空间路径 + 纯色填充：**每帧都落笔**（1000–9800 px 量级，包围盒 356×884 与腿同尺寸）
 * - 同一条路径经 `Canvas.concat(轴矩阵)` 画在轴空间：**80 帧全部 0 像素**
 *
 * 同版本手机正常；`drawLinRect` 的 4 点凸矩形走同一个 `concat` 机制在电视上**能**画，
 * 所以踩雷的是「多顶点凹/自交路径 + 画布矩阵」这一交叉，而不是矩阵本身。
 * 全片形状口径实测（`LIN_POLY` 只有三种）：`COLUMN` 恒凸 491/491，`SHELL` 恒凹 330/330，
 * `LEG` 976/981 帧自交 —— 缺填的与凹/自交的完全同集合。
 *
 * ⇒ 定案：`drawLinPoly` **必须在屏幕空间落笔**（路径取顶点池原样、渐变轴取指令 `g0..g3` 原样），
 * ⛔ 不得回到"单位空间路径 + `Canvas.concat`"。
 */
internal class TripodFillMechanismGateTest {

    private fun rendererSource(): String =
        File(mainSourceRoot(), "com/nasmusic/tv/visualizer/renderers/TripodFogRenderer.kt").readText()

    /** 取函数体（剥注释后大括号配对），写法类断言只能对**真实代码**判。 */
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

    /** G1 反空转对照：提取器必须真的拿到非空函数体，且体里确实有落笔调用。 */
    @Test
    fun linPolyBodyIsReal() {
        val body = functionBody("drawLinPoly")
        assertTrue("drawLinPoly 体被抽空了，闸门会在空转状态下一路绿灯", body.isNotBlank())
        assertTrue("drawLinPoly 不再 drawPath ⇒ 本闸门其余断言失去意义", body.contains("drawPath("))
    }

    /** G2 ⛔ 轴空间 + 画布矩阵不得回来（真机实测：多顶点凹/自交路径这样画一个像素都不落）。 */
    @Test
    fun linPolyMustNotUseCanvasMatrix() {
        val body = functionBody("drawLinPoly")
        for (forbidden in listOf("concat(", "setLocalMatrix(", "scale(", "translate(", "rotate(")) {
            assertFalse("drawLinPoly 里出现了 $forbidden —— 填充改回画布变换承载，电视端会整笔不落笔", body.contains(forbidden))
        }
        assertFalse("drawLinPoly 又去取单位空间缓存着色器", body.contains("gradOf("))
    }

    /** G3 路径与渐变都在屏幕空间：路径直接来自顶点池，渐变轴就是指令 `g0..g3`。 */
    @Test
    fun linPolyDrawsInScreenSpace() {
        val body = functionBody("drawLinPoly")
        assertTrue("drawLinPoly 必须用屏幕空间路径（buildScreenPath）", body.contains("buildScreenPath("))
        assertTrue("drawLinPoly 必须以指令自己的轴端点建渐变", body.contains("LinearGradient(ax0, ay0, ax1, ay1"))
    }

    /**
     * G4 屏幕空间端点式渐变**只对线性渐变成立**：全圈扫一遍，任何 `LIN_POLY` 指令的渐变模板
     * 都必须是线性的。若有 `RADIAL` 模板被编成 `LIN_POLY`，本函数会静默画错（负样本：
     * 翻 `TripodGrad.RADIAL` 的某一位即可变红）。
     */
    @Test
    fun linPolyGradientsAreAllLinear() {
        val world = TripodGateFixtures.boot()
        TripodFogMath.layout(world, 1280.0, 720.0)
        val table = TripodGateBmpTable()
        for (b in world.bMid) b.bmp?.let { table.put(it) }
        for (b in world.bNear) b.bmp?.let { table.put(it) }
        val plan = TripodFogPlan(world, table)
        plan.persons = TripodFogBake.bakePersons()
        world.tier = TripodLevel.HIGH
        var seen = 0
        var t = 0.0
        val loop = world.params.loop
        while (t <= loop + 1e-9) {
            val n = plan.build(TripodGait.sceneState(world, t))
            var i = 0
            while (i < n) {
                if (plan.cmdKind[i] == TripodCmd.LIN_POLY) {
                    val kind = plan.cmdGrad[i]
                    assertTrue("LIN_POLY 用了径向模板 $kind", kind in 0 until TripodGrad.N && !TripodGrad.RADIAL[kind])
                    seen++
                }
                i++
            }
            t += 0.25
        }
        assertTrue("一圈里一条 LIN_POLY 都没发过，闸门失去意义（seen=$seen）", seen > 0)
    }

    /** G5 凸类指令不受本闸门约束：`drawLinRect` 仍走 `concat`（电视端实测能画）。 */
    @Test
    fun linRectStillUsesConcat() {
        val body = functionBody("drawLinRect")
        assertTrue("drawLinRect 体被抽空了", body.isNotBlank())
        assertTrue("drawLinRect 仍应按已验路径走 concat（它没有被真机判红）", body.contains("concat("))
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

    /** 剥块注释（Kotlin 可嵌套）与行注释，⛔ 必须保持行数一致。 */
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
