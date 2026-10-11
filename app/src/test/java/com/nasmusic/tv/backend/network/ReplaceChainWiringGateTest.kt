package com.nasmusic.tv.backend.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 7 I-1 —— `PlayerViewModel` 替换链接线的**源码文本门**。
 *
 * 背景：`PlayerViewModel` 是 `AndroidViewModel`，本仓库跑不了它的行为级单测；链的**决策**
 * 由 `ReplaceChainTest` 钉住，但「VM 到底有没有去问决策件」原本只有编译 + lint + 人工走查覆盖。
 * 评审给出的变异证据：删掉 `ReplaceChain.plan(...)` 调用、或把 `when` 的两个分支互换，
 * 1982 例仍全绿。同 `SearchViewModel` 的处境，故沿用仓库既有的文本门手法
 * （范本：`backend/network/ArtistSongsPagingTest.kt:302-329` 的 VM 侧门 + `ci/LocalMusicGateTest.kt` 的 L6 负向自证）。
 *
 * ## 钉住的六条不变量
 *
 * | # | 内容 |
 * |---|---|
 * | G1 | VM 调 `ReplaceChain.plan(song.id, lastReplacedSongId)`，并把产物 id 写回 `lastReplacedSongId` —— 防死循环闸门真的被喂「当前曲 + 上一次替换产物」 |
 * | G2 | 层→IO 的映射不互换：`SAME_SOURCE -> trySameSourceReplace(` 且 `CROSS_SOURCE -> tryCrossSourceReplace(`，且 SAME_SOURCE 分支在前 |
 * | G3 | `R.string.same_source_replace_playing` 落在 `usedLayer == ReplaceLayer.SAME_SOURCE` 分支**内部**（文案不许悄悄搬到跨源分支） |
 * | G4 | 负向自证：G1/G2/G3 的判据在对应的破坏样本上必须判负；找不到文件 / 匹配区域为空必须直接失败（门不得空转） |
 * | G5 | 新 key 在 `values/` 与 `values-en/` **同时存在**，且两侧都是 `%1$s` + `%2$s` 两参 |
 * | G6 | 旧字段名（`lastCrossSource` + `ReplacedId` 两段拼接构造）在 `app/src` 下 0 命中（改名是 Task 8 会读的 Produces 契约） |
 *
 * ⚠️ 断言一律 `assertTrue` / `assertFalse`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 * ⚠️ 匹配前把空白折叠成单个空格 —— 重新缩进不该弄断门。
 * ⚠️ 读文件必须显式 UTF-8（源码里有中文注释，本机 JVM 默认字符集是 CP936）。
 */
class ReplaceChainWiringGateTest {

    // ─────────────────── G1：防死循环闸门真的被接线 ───────────────────

    @Test
    fun `G1 PlayerViewModel 必须把当前曲与上次替换产物交给 ReplaceChain 的 plan`() {
        val src = viewModelSource()
        assertTrue(
            "G1 空转自证：读到的确实是 PlayerViewModel",
            src.contains("class PlayerViewModel")
        )
        assertTrue(
            "G1：VM 没有把 `song.id` 与 `lastReplacedSongId` 传给 `ReplaceChain.plan(...)` ——\n" +
                "防死循环闸门被旁路（改回手拼 listOf(...) 就是这条），替换产物再失败会无限回环。\n" +
                "决策见 ReplaceChain.plan 的 KDoc（§6 验收 15）。",
            planGuardCalled(src)
        )
        assertTrue(
            "G1：VM 没有把命中层的 `replacement.id` 记回 `lastReplacedSongId` ——\n" +
                "第二个实参就永远是 null，plan 的闸门形同虚设。",
            src.contains("lastReplacedSongId = replacement.id")
        )
        assertTrue(
            "G1：plan 的实参不得退化成与上次产物无关的一对（闸门必须读当前曲 id）",
            !src.contains("ReplaceChain.plan(song.id, null)") &&
                !src.contains("ReplaceChain.plan(lastReplacedSongId")
        )
    }

    // ─────────────────── G2：层 → IO 的映射 ───────────────────

    @Test
    fun `G2 两个替换层各自接自己的处理函数且同源在前`() {
        val src = viewModelSource()
        assertTrue(
            "G2：层→处理函数的映射被互换/架空（Q7③ 的层序在 VM 侧被悄悄反转）：\n" +
                mappingProblems(src).joinToString("\n"),
            mappingProblems(src).isEmpty()
        )
    }

    // ─────────────────── G3：同源文案锚在 SAME_SOURCE 分支内 ───────────────────

    @Test
    fun `G3 同源替换提示串必须出自 SAME_SOURCE 分支`() {
        val src = viewModelSource()
        assertTrue(
            "G3：SAME_SOURCE 分支实际取的是 `${sameSourceToastKey(src)}`，" +
                "必须是 same_source_replace_playing ——\n" +
                "同源替换时 networkSource 仍是 meting（红线 2），拿跨源文案填第二个占位符等于告诉用户一件没发生的事。",
            sameSourceToastKey(src) == "same_source_replace_playing"
        )
    }

    // ─────────────────── G4：负向自证（门不得空转） ───────────────────

    /**
     * 与 `ci/LocalMusicGateTest.kt` 的 L6 同款：把评审点名的三种破坏形态喂给**同一套判据**，
     * 必须逐个判负。否则门可能只是在匹配一些根本不存在的东西。
     */
    @Test
    fun `G4 破坏样本必须被同一套判据拦下`() {
        val src = viewModelSource()

        // 破坏 1：删掉 plan 调用，手拼层序字面量（丢掉防死循环闸门）
        val noGuard = src.replace(
            "ReplaceChain.plan(song.id, lastReplacedSongId)",
            "listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)"
        )
        assertTrue("护栏失效：删掉 plan 调用后 G1 仍判正 —— G1 在空转", !planGuardCalled(noGuard))

        // 破坏 2：把两个分支的处理函数互换（Q7③ 层序被反转）
        val swapped = src
            .replace("ReplaceLayer.SAME_SOURCE -> trySameSourceReplace(", "ReplaceLayer.SAME_SOURCE -> @@SAME@@(")
            .replace("ReplaceLayer.CROSS_SOURCE -> tryCrossSourceReplace(", "ReplaceLayer.CROSS_SOURCE -> trySameSourceReplace(")
            .replace("ReplaceLayer.SAME_SOURCE -> @@SAME@@", "ReplaceLayer.SAME_SOURCE -> tryCrossSourceReplace(")
        assertTrue("护栏失效：分支互换后 G2 仍判正 —— G2 在空转", mappingProblems(swapped).isNotEmpty())

        // 破坏 3：跨源分支的实参被挪走（同源命中时不再发新串）
        val lostKey = src.replace("R.string.same_source_replace_playing", "R.string.cross_source_replace_playing")
        assertTrue(
            "护栏失效：新 key 被换回 cross_source 后 G3 仍判正 —— G3 在空转",
            sameSourceToastKey(lostKey) != "same_source_replace_playing"
        )

        // 破坏 4：目标串整段不存在时，判据必须是「找不到」而不是「通过」
        assertTrue("护栏失效：空文本被判正 —— 判据是恒真的", mappingProblems("").isNotEmpty())
        assertFalse("护栏失效：空文本仍算调用了 plan", planGuardCalled(""))
    }

    /** 匹配区域非空自证：门依赖的三处结构锚点必须真的在文件里。 */
    @Test
    fun `G4 结构锚点必须真实存在（否则门只是在读空气）`() {
        val src = viewModelSource()
        assertTrue("找不到 tryReplaceByReSearch 的定义 ⇒ 读到的是错的函数/错的文件",
            src.contains("private suspend fun tryReplaceByReSearch("))
        assertTrue("找不到 ReplaceChain.run 的调用 ⇒ 接线整段不见了",
            src.contains("ReplaceChain.run(layers) { layer ->"))
        assertTrue("SAME_SOURCE 文案分支区域为空 ⇒ G3 无据可断",
            sameSourceToastRegion(src).isNotBlank())
    }

    // ─────────────────── G5：中英双份 + 占位符元数 ───────────────────

    @Test
    fun `G5 新串必须中英双份且都是两个占位符`() {
        val key = "same_source_replace_playing"
        for (locale in listOf("values", "values-en")) {
            val file = File(resRoot(), "$locale/strings.xml")
            assertTrue("找不到 $locale/strings.xml：${file.absolutePath}", file.isFile)
            val text = file.readText(Charsets.UTF_8)
            val entry = Regex("""name="$key"[^>]*>([^<]*)<""").find(text)?.groupValues?.get(1).orEmpty()
            assertTrue("$locale/strings.xml 里缺 $key（缺 values-en 会在英文环境崩溃）", entry.isNotBlank())
            assertTrue("$locale 的 $key 缺 %1\$s（文案是两参形式）", entry.contains("%1\$s"))
            assertTrue("$locale 的 $key 缺 %2\$s（文案是两参形式）", entry.contains("%2\$s"))
        }
    }

    // ─────────────────── G6：旧字段名 0 命中 ───────────────────

    @Test
    fun `G6 改名后旧字段名在 app_src 下不得再出现`() {
        // ⚠️ 名字用两段拼接构造：写成整串会让本测试文件自己成为命中，门永久红（评审的 grep 口径同此）。
        val oldName = "lastCrossSource" + "ReplacedId"
        val root = appSrcRoot()
        var scanned = 0
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension in TEXT_EXTENSIONS }
            .map { f -> scanned++; f to runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
            .filter { (_, text) -> text != null && text.contains(oldName) }
            .map { it.first.relativeTo(root).path }
            .toList()
        assertTrue(
            "app/src 下扫到 0 个文本文件 ⇒ G6 在空转（root=${root.absolutePath}）",
            scanned > 100
        )
        assertTrue("app/src 下仍出现旧字段名 $oldName：$hits", hits.isEmpty())
        // 负向自证：把旧名塞回一段样本，判据必须命中（否则 0 命中可能是恒真）
        assertTrue(
            "护栏失效：样本含旧名却未命中 —— G6 的匹配是空操作",
            "private var lastCrossSource${"ReplacedId"}: String? = null".contains(oldName)
        )
    }

    // ─────────────────── 判据（纯函数，便于负向自证复用） ───────────────────

    /** G1：VM 是否真的向 `plan` 询问层序（而不是自己手拼）。 */
    private fun planGuardCalled(text: String): Boolean =
        text.contains("ReplaceChain.plan(song.id, lastReplacedSongId)")

    /** G2 判据的问题清单（空 = 合规）。 */
    private fun mappingProblems(text: String): List<String> {
        val same = "ReplaceLayer.SAME_SOURCE -> trySameSourceReplace("
        val cross = "ReplaceLayer.CROSS_SOURCE -> tryCrossSourceReplace("
        val problems = mutableListOf<String>()
        if (!text.contains(same)) problems += "缺 `SAME_SOURCE -> trySameSourceReplace(`"
        if (!text.contains(cross)) problems += "缺 `CROSS_SOURCE -> tryCrossSourceReplace(`"
        if (text.contains("ReplaceLayer.SAME_SOURCE -> tryCrossSourceReplace(")) problems += "SAME_SOURCE 被接到了跨源处理函数"
        if (text.contains("ReplaceLayer.CROSS_SOURCE -> trySameSourceReplace(")) problems += "CROSS_SOURCE 被接到了同源处理函数"
        val i = text.indexOf(same)
        val j = text.indexOf(cross)
        if (i >= 0 && j >= 0 && i > j) problems += "两个分支的先后被调换（同源必须在跨源之前）"
        return problems
    }

    /** G3：`usedLayer == ReplaceLayer.SAME_SOURCE` 分支体的文本；找不到即失败（不许空转）。 */
    private fun sameSourceToastRegion(text: String): String {
        val marker = "if (usedLayer == ReplaceLayer.SAME_SOURCE) {"
        val start = text.indexOf(marker)
        assertTrue(
            "G3：VM 里找不到 `if (usedLayer == ReplaceLayer.SAME_SOURCE) {` 的文案分支 ——\n" +
                "分支结构被改写，G3 无区域可断（门拒绝空转）。",
            start >= 0
        )
        val rest = text.substring(start + marker.length)
        val tail = rest.indexOf("} else {").let { if (it >= 0) rest.substring(0, it) else rest }
        assertTrue("G3：SAME_SOURCE 文案分支区域为空", tail.isNotBlank())
        return tail
    }

    /** G3：SAME_SOURCE 分支实际引用的 string key 名。 */
    private fun sameSourceToastKey(text: String): String {
        val region = sameSourceToastRegion(text)
        val idx = region.indexOf("R.string.")
        assertTrue(
            "G3：SAME_SOURCE 分支里没有任何 `R.string.` 引用 —— 文案不再走资源（硬编码中文会撞 lint，也会绕掉中英双份）",
            idx >= 0
        )
        return region.substring(idx + "R.string.".length).takeWhile { it.isLetterOrDigit() || it == '_' }
    }

    // ─────────────────── 辅助：定位源码 / 读文本 ───────────────────

    private fun viewModelSource(): String = flatten(mainSourceFile("ui/viewmodel/PlayerViewModel.kt"))

    /** 空白折叠成单个空格：重新缩进、换行不该弄断门。 */
    private fun flatten(text: String): String = text.replace(WHITESPACE, " ")

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        assertTrue("源码文件读出来是空的：${file.absolutePath}", text.isNotBlank())
        return text
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),        // user.dir = app/
            File("app/src/main/java/com/nasmusic/tv"),    // user.dir = 仓库根
            File("../app/src/main/java/com/nasmusic/tv")
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }

    private fun appSrcRoot(): File {
        val candidates = listOf(File("src"), File("app/src"), File("../app/src"))
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 app/src（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }

    private fun resRoot(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"), File("../app/src/main/res"))
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 res 目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            root != null
        )
        return root!!
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        val TEXT_EXTENSIONS = setOf("kt", "java", "xml", "kts", "gradle", "md", "json", "pro", "txt")
    }
}
