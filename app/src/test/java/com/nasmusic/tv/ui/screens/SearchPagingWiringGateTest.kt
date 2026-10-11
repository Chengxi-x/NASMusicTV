package com.nasmusic.tv.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 8 评审 M7 —— 搜索页艺人续页**四跳接线**的源码文本门（F7）。
 *
 * 背景：本分支给搜索页接了一条四跳链（[LibraryBranch] 收集 → 传参 → [LibraryScreen] 形参 →
 * 透传 → SearchTab 消费）。UI Composable 的行为级测试本仓库跑不了，而**任一跳漏传（退回默认值）**
 * 的后果是：编译过、lint 过、全仓测试全绿，用户看到「续页永远不触发 / 角标永远不出现」——
 * 与 Task 7 在 `PlayerViewModel` 上找到的同类缺陷一模一样，故沿用
 * `backend/network/ReplaceChainWiringGateTest.kt` 的文本门手法与三件现成工具
 * （`mainSourceRoot()` / `mainSourceFile()` / `flatten()`，照抄不自创）。
 *
 * ## 钉住的五条不变量
 *
 * | # | 跳 | 内容 |
 * |---|---|---|
 * | W1 | ① 收集 | `LibraryBranch` 里 `searchVM.searchUnplayableIds.collectAsState()` 与 `searchVM.artistPaging.collectAsState()` 都在 |
 * | W2 | ② 传参 | `LibraryScreen(` 调用的实参区内三个实参都在，且**不是**默认值形态（`= emptySet(),` / `= SongsPagingState(),` / `= {}`） |
 * | W3 | ④ 透传 | `SearchTab(` 调用的实参区内三条透传都在，判据同 W2 |
 * | W4 | ⑤ 消费 | `SearchTab.kt` 里续页触发判据 `artistPaging.hasMore && !artistPaging.isLoading` 与 `unplayableIds` 的消费点都在（收而不用于门等价于断链） |
 * | W5 | 扫描自证 | `mainSourceRoot()` 实际扫到的 `.kt` 文件数 > 100，防「文件没找到却全绿」 |
 *
 * ⚠️ 区抽取按**圆括号配平**（跳过行/块注释、普通字符串、原始字符串、字符字面量），
 * ⛔ 不用行号锚点；匹配前把空白折叠成单个空格——重新缩进不该弄断门。
 * ⚠️ W2/W3 的判据是纯函数（`w2Problems`/`w3Problems`），门内自带破坏样本负自检
 * （同 `ReplaceChainWiringGateTest` 的 G4）：判据必须真的能判负，不许空转。
 * ⚠️ 断言一律 `assertTrue` / `assertFalse`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 * ⚠️ 读文件必须显式 UTF-8（源码里有中文注释，本机 JVM 默认字符集是 CP936）。
 */
class SearchPagingWiringGateTest {

    private val libraryBranchPath = "ui/components/branches/LibraryBranch.kt"
    private val libraryScreenPath = "ui/screens/LibraryScreen.kt"
    private val searchTabPath = "ui/screens/library/SearchTab.kt"

    // ─────────────────── W1：① 收集点 ───────────────────

    @Test
    fun `W1 LibraryBranch 必须从 searchVM 收集打标集合与艺人续页状态`() {
        val src = flatten(mainSourceFile(libraryBranchPath))
        assertTrue("W1 空转自证：读到的确实是 LibraryBranch", src.contains("fun LibraryBranch("))
        assertTrue(
            "W1（① 收集）：`LibraryBranch` 没有收集 `searchVM.searchUnplayableIds` ——\n" +
                "下游 `unplayableIds` 永远拿到默认 emptySet()，「可能无版权」角标在搜索页永远不出现。",
            src.contains("viewModel.searchVM.searchUnplayableIds.collectAsState()")
        )
        assertTrue(
            "W1（① 收集）：`LibraryBranch` 没有收集 `searchVM.artistPaging` ——\n" +
                "下游 `artistPaging` 永远是默认 SongsPagingState()，续页判据与计数行一起失效。",
            src.contains("viewModel.searchVM.artistPaging.collectAsState()")
        )
    }

    // ─────────────────── W2：② 传参 ───────────────────

    @Test
    fun `W2 LibraryScreen 调用必须显式传三个续页实参而不是默认值`() {
        val raw = mainSourceFile(libraryBranchPath)
        val region = callRegion(raw, Regex("LibraryScreen\\(\\s*albums\\s*="))
        assertTrue(
            "W2 结构自证：找不到 `LibraryScreen( albums =` 的调用、或圆括号不配平 ⇒ 门拒绝在空区域上判正",
            region.isNotBlank()
        )
        val flat = flatten(region)
        val problems = w2Problems(flat)
        assertTrue("W2（② 传参）：LibraryBranch → LibraryScreen 的接线被退回默认值或整行删除：\n$problems", problems.isEmpty())

        // 负自检（G4 同款）：同一套判据必须真的能判负
        val mutated = flat
            .replace("searchUnplayableIds = searchUnplayableIds,", "searchUnplayableIds = emptySet(),")
        assertTrue("W2 护栏失效：把实参换成默认值形态后判据仍判正 —— 判据是恒真的", w2Problems(mutated).isNotEmpty())
        val dropped = flat.replace("artistPaging = artistPaging,", "")
        assertTrue("W2 护栏失效：把实参整行删掉后判据仍判正", w2Problems(dropped).isNotEmpty())
        assertTrue("W2 护栏失效：空文本被判正", w2Problems("").isNotEmpty())
    }

    // ─────────────────── W3：④ 透传 ───────────────────

    @Test
    fun `W3 SearchTab 调用必须把三条状态透传下去而不是默认值`() {
        val raw = mainSourceFile(libraryScreenPath)
        val region = callRegion(raw, Regex("SearchTab\\(\\s*searchKeyword\\s*="))
        assertTrue(
            "W3 结构自证：找不到 `SearchTab( searchKeyword =` 的调用（形参区是 `searchKeyword: String`，" +
                "正则只认调用形态）、或圆括号不配平 ⇒ 门拒绝在空区域上判正",
            region.isNotBlank()
        )
        val flat = flatten(region)
        val problems = w3Problems(flat)
        assertTrue("W3（④ 透传）：LibraryScreen → SearchTab 的透传被退回默认值或整行删除：\n$problems", problems.isEmpty())

        // 负自检：变异 A 的门内替身——删掉透传行，判据必须判负
        val dropped = flat.replace("unplayableIds = searchUnplayableIds,", "")
        assertTrue("W3 护栏失效：删掉透传行后判据仍判正 —— 判据是恒真的", w3Problems(dropped).isNotEmpty())
        val defaulted = flat.replace("artistPaging = artistPaging,", "artistPaging = SongsPagingState(),")
        assertTrue("W3 护栏失效：透传换成默认值形态后判据仍判正", w3Problems(defaulted).isNotEmpty())
        assertTrue("W3 护栏失效：空文本被判正", w3Problems("").isNotEmpty())
    }

    // ─────────────────── W4：⑤ 消费点 ───────────────────

    @Test
    fun `W4 SearchTab 必须真的消费续页状态与打标集合而不是只收不用`() {
        val src = flatten(mainSourceFile(searchTabPath))
        assertTrue("W4 空转自证：读到的确实是 SearchTab", src.contains("fun SearchTab("))
        assertTrue(
            "W4（⑤ 消费）：续页触发判据不在了（`artistPaging.hasMore && !artistPaging.isLoading`）——\n" +
                "触底续页永远不发起，艺人全量目录只剩第一页。",
            src.contains("artistPaging.hasMore && !artistPaging.isLoading")
        )
        assertTrue(
            "W4（⑤ 消费）：`artistPaging` 的计数消费不在了（`artistPaging.songs.size`）——\n" +
                "「已加载 x/总数」角标失效，用户看不到续页进度。",
            src.contains("artistPaging.songs.size")
        )
        assertTrue(
            "W4（⑤ 消费）：`unplayableIds` 只收不用（消费点 `song.id in unplayableIds` 不在了）——\n" +
                "「可能无版权」打标整条链路白接通。",
            src.contains("song.id in unplayableIds")
        )
    }

    // ─────────────────── W5：扫描自证 ───────────────────

    @Test
    fun `W5 源码根必须真的扫到全部 kt 文件（防找不到文件却全绿）`() {
        val root = mainSourceRoot()
        val scanned = root.walkTopDown().count { it.isFile && it.extension == "kt" }
        assertTrue(
            "W5 扫描自证：源码根 ${root.absolutePath} 下只扫到 $scanned 个 .kt（应 >100）——\n" +
                "user.dir 候选或源码布局变了，W1–W4 都在读空气，门在空转。",
            scanned > 100
        )
        // 三件被钉的源码文件必须逐个真实存在且非空（mainSourceFile 内部已断言，这里点名防「钉错文件」）
        for (path in listOf(libraryBranchPath, libraryScreenPath, searchTabPath)) {
            val file = root.resolve(path)
            assertTrue("W5 扫描自证：门钉的源码文件不存在：${file.absolutePath}", file.isFile)
            assertTrue("W5 扫描自证：门钉的源码文件是空的：${file.absolutePath}", file.length() > 0)
        }
    }

    // ─────────────────── 判据（纯函数，便于负自检复用） ───────────────────

    /** W2 判据的问题清单（空 = 合规）：三实参在场且非默认值形态。输入是**已 flatten** 的实参区。 */
    private fun w2Problems(flatRegion: String): List<String> {
        val problems = mutableListOf<String>()
        checkNamedArg(flatRegion, problems, "searchUnplayableIds", "emptySet(),")
        checkNamedArg(flatRegion, problems, "artistPaging", "SongsPagingState(),")
        checkNamedArg(flatRegion, problems, "onLoadMoreArtistSongs", "{}")
        return problems
    }

    /** W3 判据的问题清单（空 = 合规）：三条透传在场且非默认值形态。 */
    private fun w3Problems(flatRegion: String): List<String> {
        val problems = mutableListOf<String>()
        checkNamedArg(flatRegion, problems, "unplayableIds", "emptySet(),", passThroughValue = "searchUnplayableIds")
        checkNamedArg(flatRegion, problems, "artistPaging", "SongsPagingState(),", passThroughValue = "artistPaging")
        checkNamedArg(flatRegion, problems, "onLoadMoreArtistSongs", "{}", passThroughValue = "onLoadMoreArtistSongs")
        return problems
    }

    /**
     * 单个具名实参的判据：`name = <值>` 必须在场；值不得是默认形态；
     * 给了 [passThroughValue] 则必须是透传形态 `name = <passThroughValue>,`（W3 用），
     * W2 只要求非默认（lambda 体的写法允许演化）。
     */
    private fun checkNamedArg(
        flatRegion: String,
        problems: MutableList<String>,
        name: String,
        defaultShape: String,
        passThroughValue: String? = null,
    ) {
        if (!flatRegion.contains("$name = ")) {
            problems += "实参区里没有 `$name = …`（整行被删或调用被改写）"
            return
        }
        if (flatRegion.contains("$name = $defaultShape")) {
            problems += "`$name` 被退回默认值形态 `$defaultShape`"
        }
        if (passThroughValue != null && !flatRegion.contains("$name = $passThroughValue,")) {
            problems += "`$name` 不是透传形态 `$name = $passThroughValue,`"
        }
    }

    // ─────────────────── 辅助：定位源码 / 读文本 / 抽区（照抄 ReplaceChainWiringGateTest） ───────────────────

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

    /**
     * 从 [header] 正则命中的调用起点抽到配平的闭括号为止（含两端）。
     * 跳过 // 行注释、块注释、"..." 普通串、\"\"\" 原始串、'.' 字符字面量——
     * 中文注释里的全角括号本就无害，ASCII 括号一律不数。
     * 找不到或配不平返回空串，由调用方按「无区域可断」判负。
     */
    private fun callRegion(text: String, header: Regex): String {
        val match = header.find(text) ?: return ""
        val open = text.indexOf('(', match.range.first)
        if (open < 0) return ""
        var depth = 0
        var i = open
        var inLine = false
        var inBlock = false
        var inString = false
        var inRaw = false
        var inChar = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                inLine -> if (c == '\n') inLine = false
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ }
                inRaw -> if (c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"') { inRaw = false; i += 2 }
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                inChar -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '\'' -> inChar = false
                }
                else -> when {
                    c == '/' && next == '/' -> { inLine = true; i++ }
                    c == '/' && next == '*' -> { inBlock = true; i++ }
                    c == '"' && next == '"' && i + 2 < text.length && text[i + 2] == '"' -> { inRaw = true; i += 2 }
                    c == '"' -> inString = true
                    c == '\'' -> inChar = true
                    c == '(' -> depth++
                    c == ')' -> {
                        depth--
                        if (depth == 0) return text.substring(match.range.first, i + 1)
                    }
                }
            }
            i++
        }
        return ""
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
