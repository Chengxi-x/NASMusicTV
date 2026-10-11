package com.nasmusic.tv.backend.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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
 * | G3 | 在 `tryReplaceByReSearch` 的函数体区域内，每个 `R.string.*_replace_playing` 引用**前面最近的层常量必须与它同名**，且两个 key 都必须出现（见下方 G3 专段） |
 * | G4 | 负向自证：G1/G2/G3 的判据在对应的破坏样本上必须判负；找不到文件 / 匹配区域为空必须直接失败（门不得空转） |
 * | G5 | 新 key 在 `values/` 与 `values-en/` **同时存在**，且两侧都是 `%1$s` + `%2$s` 两参 |
 * | G6 | 旧字段名（`lastCrossSource` + `ReplacedId` 两段拼接构造）在 `app/src` 下 0 命中；扫描管线本身用自建临时目录做正反两向自证（命中/不命中都要对） |
 *
 * ## G3 的锚点口径与它会死于哪种变异（评审 N1）
 *
 * 判据只认**稳定符号名**（`fun tryReplaceByReSearch` 的函数体 + `ReplaceLayer.SAME_SOURCE` /
 * `ReplaceLayer.CROSS_SOURCE` 两个层常量 + 两个 `R.string` key 的出现次序），
 * ⛔ 不再锚 `if (usedLayer == …) {` 这类控制流形状 —— 那样等于把门钉在一种写法上，
 * 文案分发改成穷尽 `when` 就会把它弄断（正是本轮 Production 侧的改动）。
 *
 * 它会死于这些变异（都是**必须**拦下的）：
 * - 两个 key 互换 ⇒ SAME_SOURCE 分支里读到的却是跨源串，它与前面的 SAME_SOURCE 层常量不同名 ⇒ 判负；
 * - 任一 key 被删除或整体搬出本函数 ⇒ 存在性检查判负；
 * - 同源文案 key 被换回 `cross_source_replace_playing` ⇒ 判负；
 * - 函数体找不到 / 括号不配平 ⇒ 区域为空 ⇒ 判负（拒绝空转）。
 *
 * ⚠️ 已知取向：若有人把穷尽 `when` 改回 `if/else`（`else` 分支不带层常量），G3 会判负。
 * 这是**有意的**——仓库约定文案分发用穷尽 `when`（新层必须由编译器逼出分支），
 * 门会在改动时大声失败，而不是悄悄放行。
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

    // ─────────────────── G3：两条文案各归其层 ───────────────────

    @Test
    fun `G3 两条替换提示串各自挂在自己的层分支上`() {
        val src = viewModelSource()
        assertTrue(
            "G3：`tryReplaceByReSearch` 的文案分发把提示串挂错了层（或整段被搬走）——\n" +
                "同源替换时 networkSource 仍是 meting（红线 2），拿跨源文案填第二个占位符等于告诉用户一件没发生的事。\n" +
                "判据口径见类 KDoc「G3 的锚点口径」。\n" +
                toastKeyProblems(src).joinToString("\n"),
            toastKeyProblems(src).isEmpty()
        )
    }

    // ─────────────────── G4：负向自证（门不得空转） ───────────────────

    /**
     * 与 `ci/LocalMusicGateTest.kt` 的 L6 同款：把评审点名的破坏形态（删 plan 调用 / 换分支处理函数 /
     * 文案 key 被换回·互换·删除 / 整段空文本）喂给**同一套判据**，必须逐个判负。
     * 否则门可能只是在匹配一些根本不存在的东西。
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

        // 破坏 3：同源的实参被换回跨源串
        val lostKey = src.replace("R.string.same_source_replace_playing", "R.string.cross_source_replace_playing")
        assertTrue(
            "护栏失效：新 key 被换回 cross_source 后 G3 仍判正 —— G3 在空转",
            toastKeyProblems(lostKey).isNotEmpty()
        )

        // 破坏 3b：两个 key 整名互换（同源拿到跨源文案、跨源拿到两版本文案）
        val swappedToast = src
            .replace("R.string.same_source_replace_playing", "R.string.@@TOAST@@")
            .replace("R.string.cross_source_replace_playing", "R.string.same_source_replace_playing")
            .replace("R.string.@@TOAST@@", "R.string.cross_source_replace_playing")
        assertTrue(
            "护栏失效：两条文案互换后 G3 仍判正 —— G3 在空转",
            toastKeyProblems(swappedToast).isNotEmpty()
        )

        // 破坏 3c：同源串整段消失（删分支实参而不是改文案）
        val droppedKey = src.replace("R.string.same_source_replace_playing", "R.string.queue_empty")
        assertTrue(
            "护栏失效：同源串被删除后 G3 仍判正 —— 存在性检查是空操作",
            toastKeyProblems(droppedKey).isNotEmpty()
        )

        // 破坏 4：目标串整段不存在时，判据必须是「找不到」而不是「通过」
        assertTrue("护栏失效：空文本被判正 —— 判据是恒真的", mappingProblems("").isNotEmpty())
        assertFalse("护栏失效：空文本仍算调用了 plan", planGuardCalled(""))
        assertTrue("护栏失效：空文本下 G3 的文案判据仍判正", toastKeyProblems("").isNotEmpty())
    }

    /** 匹配区域非空自证：门依赖的结构锚点必须真的在文件里（锚点是符号名，不是行号/缩进）。 */
    @Test
    fun `G4 结构锚点必须真实存在（否则门只是在读空气）`() {
        val src = viewModelSource()
        assertTrue("找不到 tryReplaceByReSearch 的定义 ⇒ 读到的是错的函数/错的文件",
            src.contains("private suspend fun tryReplaceByReSearch("))
        assertTrue("找不到 ReplaceChain.run 的调用 ⇒ 接线整段不见了",
            src.contains("ReplaceChain.run(layers) { layer ->"))
        assertTrue("tryReplaceByReSearch 的函数体区域为空（括号不配平或被改写）⇒ G3 无据可断",
            replaceFnRegion(src).isNotBlank())
        assertTrue("函数体里一条替换文案都没有 ⇒ G3 无据可断",
            replaceFnRegion(src).contains("R.string."))
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
        selfProofForScanner(oldName)

        val (hits, scanned) = scanFor(oldName, appSrcRoot())
        assertTrue(
            "app/src 下扫到 $scanned 个文本文件 ⇒ G6 在空转（root=${appSrcRoot().absolutePath}）",
            scanned > 100
        )
        assertTrue("app/src 下仍出现旧字段名 $oldName：$hits", hits.isEmpty())
    }

    /**
     * N2：G6 的自证必须真的走一遍扫描管线（临时目录里放一个含旧名的样本 + 一个不含的样本），
     * 而不是像此前那样只断 `"…".contains(oldName)` —— 那是编译期恒真，永远碰不到 `walkTopDown`。
     */
    private fun selfProofForScanner(oldName: String) {
        val tempRoot = Files.createTempDirectory("replace-chain-gate-g6").toFile()
        try {
            File(tempRoot, "Nested").mkdirs()
            File(tempRoot, "Hit.kt").writeText("private var $oldName: String? = null", Charsets.UTF_8)
            File(tempRoot, "Nested/AlsoHit.java").writeText("String $oldName = null;", Charsets.UTF_8)
            // 近似串（新名 + 只到旧名前半段）必须不命中，证明匹配不是恒真
            File(tempRoot, "Clean.kt").writeText("private var lastReplacedSongId: String? = null // lastCrossSource", Charsets.UTF_8)
            File(tempRoot, "Ignored.bin").writeText("白名单外的扩展名不该被扫：$oldName", Charsets.UTF_8)

            val (hits, scanned) = scanFor(oldName, tempRoot)
            assertTrue("自证失效：临时目录里放了两份样本却没被走到（walkTopDown 或扩展名白名单坏了），scanned=$scanned", scanned >= 3)
            assertTrue(
                "自证失效：命中清单与样本不符（应为两份，实际 $hits）",
                hits.size == 2 && hits.none { it.endsWith("Ignored.bin") }
            )
            assertTrue("自证失效：命中项里没有 .kt 样本 —— $hits", hits.any { it.endsWith("Hit.kt") })
            assertTrue("自证失效：命中项里没有嵌套目录下的 .java 样本 —— $hits", hits.any { it.endsWith("AlsoHit.java") })
            assertTrue("自证失效：不含整名的近似样本也被判命中（匹配是恒真）—— $hits", hits.none { it.endsWith("Clean.kt") })
        } finally {
            tempRoot.deleteRecursively()
        }
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

    /**
     * G3：`tryReplaceByReSearch` 的函数体文本（签名起、到与之配平的 `}` 止）。
     * 只锚函数名与括号配平，⛔ 不锚行号/缩进/控制流写法；找不到或括号不配平返回空串，
     * 由调用方按「无区域可断」判负。
     */
    private fun replaceFnRegion(text: String): String {
        val start = text.indexOf("fun tryReplaceByReSearch(")
        if (start < 0) return ""
        val bodyStart = text.indexOf('{', start)
        if (bodyStart < 0) return ""
        var depth = 0
        var inString = false
        var escaped = false
        var i = bodyStart
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return ""
    }

    /**
     * G3 判据（纯函数，便于负向自证复用）：函数体内每个替换文案 key
     * **前面最近的层常量**必须与它同名，且两个 key 都必须在场。
     * 层常量取 `ReplaceLayer.SAME_SOURCE` / `ReplaceLayer.CROSS_SOURCE` 的全名形式，
     * 因此 `when` 分支、`if (usedLayer == …)`、`plan` 的字面量列表都算数 —— 与形状无关。
     */
    private fun toastKeyProblems(text: String): List<String> {
        val region = replaceFnRegion(text)
        if (region.isEmpty()) {
            return listOf("G3：找不到 `tryReplaceByReSearch` 的函数体（或其括号不配平）—— 门拒绝在空区域上判正")
        }
        val problems = mutableListOf<String>()
        val events = mutableListOf<Pair<Int, String>>()
        fun add(needle: String, tag: String) {
            findAll(region, needle).forEach { events += it to tag }
        }
        add("ReplaceLayer.$SAME_SOURCE_TAG", SAME_SOURCE_TAG)
        add("ReplaceLayer.$CROSS_SOURCE_TAG", CROSS_SOURCE_TAG)
        for (key in listOf(SAME_TOAST_KEY, CROSS_TOAST_KEY)) {
            if (!region.contains("R.string.$key")) {
                problems += "函数体里没有 `R.string.$key`（被删除或搬出了 tryReplaceByReSearch）"
            }
            add("R.string.$key", key)
        }
        events.sortBy { it.first }
        var currentLayer: String? = null
        for ((_, tag) in events) {
            if (tag == SAME_SOURCE_TAG || tag == CROSS_SOURCE_TAG) currentLayer = tag
            else if (currentLayer != layerOfToastKey(tag)) {
                problems += "`R.string.$tag` 前面最近的层常量是 ${currentLayer ?: "（没有层常量）"}，与它不同名"
            }
        }
        return problems
    }

    private fun layerOfToastKey(key: String): String =
        if (key == SAME_TOAST_KEY) SAME_SOURCE_TAG else CROSS_SOURCE_TAG

    /** 返回 [needle] 在 [text] 中所有出现位置的下标（不重叠扫描）。 */
    private fun findAll(text: String, needle: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return out
            out += at
            from = at + needle.length
        }
    }

    /** G6 判据：在 [root] 下按扩展名白名单扫文本，返回（命中文件相对路径, 扫描到的文件数）。 */
    private fun scanFor(needle: String, root: File): Pair<List<String>, Int> {
        var scanned = 0
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension in TEXT_EXTENSIONS }
            .map { f -> scanned++; f to runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
            .filter { (_, text) -> text != null && text.contains(needle) }
            .map { it.first.relativeTo(root).path }
            .toList()
        return hits to scanned
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

        // G3 的稳定符号名：层常量尾段 + 两条文案 key（⛔ 不含任何控制流形状）
        const val SAME_SOURCE_TAG = "SAME_SOURCE"
        const val CROSS_SOURCE_TAG = "CROSS_SOURCE"
        const val SAME_TOAST_KEY = "same_source_replace_playing"
        const val CROSS_TOAST_KEY = "cross_source_replace_playing"
    }
}
