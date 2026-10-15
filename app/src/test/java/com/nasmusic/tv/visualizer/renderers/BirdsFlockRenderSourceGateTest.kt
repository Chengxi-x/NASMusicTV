package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E49「鸟群」渲染源码卫生门禁（方案 §11.2 `BirdsFlockRenderSourceGateTest` 的落地版）。
 *
 * ## 判据（每条都要负向自证）
 *  1. ⛔ 零 `clipPath` / `clip(` / `RoundedCornerShape` —— **API 22 创维 hwui SIGSEGV 的全项目禁物**
 *     （`SeasideTest` ① 与 `MoonlitSkySeaTest` ⑥ 同一条红线；注释里的不算数）；
 *  2. ⛔ 零 `drawVertices`（API 29 > minSdk 22，§5.3）；
 *  3. ⛔ 零 `setLocalMatrix` / `save()` / `restore()` / `Matrix`（§5.2：E43 真机实测
 *     local matrix 静默退化 + 扑动/侧倾必须在顶点坐标里算完）；
 *  4. 渲染器的逐帧入口（`drawContent` / `renderFrame` / `drawBand`）必须是
 *     `fun DrawScope.xxx(` —— 漏掉接收者会整体逃过零分配扫描（E43 陷阱 ①）；
 *  5. `BirdsFlockMath` 零 Android / Compose 依赖（§11.1：纯逻辑收进 internal object）；
 *  6. ⛔ 零 `Math.random` 式无种子随机（§十三：同一次运行逐位可复现）。
 */
class BirdsFlockRenderSourceGateTest {

    private val files = listOf(
        "BirdsFlockMath.kt",
        "BirdsFlockFlock.kt",
        "BirdsFlockBakes.kt",
        "BirdsFlockRenderer.kt",
    )

    // ── 源码定位（照抄 FxCoverageScanTest.mainSourceRoot 手法）──

    private fun sourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer/renderers")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 renderers 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    /** 剥掉块注释与行注释（照抄 FxCoverageScanTest.stripComments —— ⛔ 注释里的禁物不算数）。 */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1]); i += 2; continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') {
                inString = true; sb.append(c); i++; continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                var depth = 1
                while (i < src.length && depth > 0) {
                    if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') {
                        depth++; i += 2
                    } else if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') {
                        depth--; i += 2
                    } else {
                        if (src[i] == '\n') sb.append('\n'); i++
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

    private fun strippedOf(name: String): String {
        val f = File(sourceRoot(), name)
        assertTrue("源码文件应存在：$name", f.isFile)
        return stripComments(f.readText())
    }

    // ── 正向断言 ──

    @Test
    fun `零 clip 家族与 drawVertices 与 local matrix 与 canvas 状态栈`() {
        val forbidden = listOf(
            "clipPath", "clipPathBounds", "RoundedCornerShape", "drawVertices",
            "setLocalMatrix", "save()", "restore()", ".rotate(", "Matrix",
        )
        for (name in files) {
            val src = strippedOf(name)
            for (token in forbidden) {
                assertFalse(
                    "$name 的可执行代码里不得出现「$token」（API 22 SIGSEGV 红线 / E43 local matrix 静默退化）",
                    src.contains(token),
                )
            }
        }
    }

    @Test
    fun `逐帧入口必须带 DrawScope 接收者`() {
        val src = strippedOf("BirdsFlockRenderer.kt")
        for (fn in listOf("drawContent", "renderFrame", "drawBand")) {
            assertTrue(
                "逐帧入口 $fn 必须是 `fun DrawScope.$fn(`（漏掉接收者会整体逃过零分配扫描，E43 陷阱 ①）",
                Regex("""fun\s+DrawScope\.$fn\s*\(""").containsMatchIn(src),
            )
        }
    }

    @Test
    fun `数学层零 Android Compose 依赖`() {
        val src = strippedOf("BirdsFlockMath.kt")
        assertFalse("BirdsFlockMath 不得 import android.*（§11.1 纯逻辑）", src.contains("import android"))
        assertFalse(
            "BirdsFlockMath 不得 import androidx.compose（§11.1 纯逻辑）",
            src.contains("import androidx"),
        )
    }

    @Test
    fun `零无种子随机`() {
        val src = strippedOf("BirdsFlockFlock.kt")
        assertFalse(
            "仿真层不得出现 Math.random / kotlin.random.Random（§十三：种子 RNG，逐位可复现）",
            src.contains("Math.random") || src.contains("kotlin.random.Random"),
        )
    }

    @Test
    fun `合批结构：每层恰好一条 Path 字段`() {
        val src = strippedOf("BirdsFlockRenderer.kt")
        // 5 条预热 Path（4 飞行层 + 1 停栖），逐帧 rewind —— ⛔ 不得每帧 Path()
        for (fn in listOf("pathL0", "pathL1", "pathL2", "pathL3", "pathPerch")) {
            assertTrue("应声明预热字段 $fn（§5.2 合批）", src.contains("private val $fn = Path()"))
        }
        // drawFlockLayer / drawPerched 内不得 new Path
        val drawBody = src.substringAfter("private fun drawFlockLayer")
        assertTrue(
            "drawFlockLayer 内不得每帧 new Path（⛔ 逐帧分配红线）",
            !Regex("""new\s+Path\(\)|Path\(\)""").containsMatchIn(drawBody.substringBefore("private fun drawPerched")),
        )
    }

    /**
     * 带子定位**必须平移画布，⛔ 不能只挪 `topLeft`**（2026-10-15 真机返工 **D3**）。
     *
     * 判据：`drawBand` 的可执行代码里
     *  ① 必须有 `translate(` —— Compose 的 `drawRect(brush, …)` **不会**把着色器挪到 `topLeft`
     *     （`CanvasDrawScope.drawRect-AsUm42w` 只算矩形后直接 `Canvas.drawRect`，`ShaderBrush.applyTo`
     *     建着色器时**不带 local matrix** ⇒ 着色器锚在画布原点）⇒ 只挪 `topLeft` 时着色器按画布 y 采样，
     *     落到 tile 之外被 `TileMode.Clamp` 折成"最后一行铺满整条带"，且 `topLeft.x = −off` **完全不滚**；
     *  ② ⛔ 不得出现 `Offset(-off` —— 那正是旧写法（E49 真机症状：远树排 = 一排等高竖棍、
     *     近景枯树与近景草丛整条消失、栖鸟悬空）。
     *
     * ⚠️ **`SnowCedarRenderer.kt`（E47 雪松）是同款代码、同一条根因**，2026-10-15 一并修
     * ⇒ 两个文件一起扫，防任何一边回退。
     */
    @Test
    fun `带子定位必须平移画布而不是挪 topLeft`() {
        for (name in listOf("BirdsFlockRenderer.kt", "SnowCedarRenderer.kt")) {
            val src = strippedOf(name)
            val body = src.substringAfter("private fun DrawScope.drawBand").substringBefore("private fun ")
            assertTrue(
                "$name 的 drawBand 必须 `translate(` 画布（着色器锚在画布原点，只挪 topLeft 不生效 —— 见函数 KDoc）",
                body.contains("translate("),
            )
            assertFalse(
                "$name 的 drawBand ⛔ 不得用 `Offset(-off, y)` 定位带子（着色器不跟着走 ⇒ Clamp 铺最后一行）",
                body.contains("Offset(-off"),
            )
        }
    }

    // ── 负向自证（证明本门禁真的在起作用）──

    @Test
    fun `负向自证 判据函数对含禁物的样本必须报红`() {
        val bad = "class X {\n    fun f() { canvas.clipPath(p) }\n}"
        assertTrue("含 clipPath 的样本必须被判出", bad.contains("clipPath"))
        val good = strippedOf("BirdsFlockRenderer.kt")
        assertFalse("真实渲染器源码必须不含 clipPath", good.contains("clipPath"))
    }

    /** 带子定位那条判据的负向自证：把 `drawBand` 换回旧写法（只挪 `topLeft`）必须被判红。 */
    @Test
    fun `负向自证 带子定位判据对旧写法样本必须报红`() {
        val old = "drawRect(brush = br, topLeft = Offset(-off, y), size = Size(w + cw, dh))"
        assertTrue("旧写法样本必须被判出（含 Offset(-off）", old.contains("Offset(-off"))
        assertFalse("旧写法样本不得含 translate(（否则该判据恒绿、失去意义）", old.contains("translate("))
    }
}
