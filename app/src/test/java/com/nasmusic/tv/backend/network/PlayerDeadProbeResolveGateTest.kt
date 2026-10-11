package com.nasmusic.tv.backend.network

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终审 B4 —— `PlayerViewModel` 播放链**请求量**接线的源码文本门。
 *
 * 背景：`PlayerViewModel` 是 `AndroidViewModel`，本仓库跑不了它的行为级单测（既有结论，
 * 见 `ReplaceChainWiringGateTest` 类 KDoc）。B4 修的正是 VM 里的两处调用参数——
 * 改坏它们**编译过、lint 过、其余单测全绿**，用户看到的是「按了遥控器等三分钟才跳歌」。
 * 故沿用仓库既有的文本门手法（范本：同目录 `ReplaceChainWiringGateTest.kt`）。
 *
 * ## 钉住的三条不变量
 *
 * | # | 内容 |
 * |---|---|
 * | G1 | 首轮失败后的 1.5s 重试必须带「未被负面判定钉住」这个前置门（`isProbedDead`），且门在 `delay(1500)` **之前**、只出现一次 |
 * | G2 | 跨源层 `resolvePlayUrlWithCrossSourceFallback` 的实参必须是 `forceRefresh = false`（已知判定要尊重，未知才重探） |
 * | G3 | 其余 `forceRefresh` 站点不许被顺手改掉：首轮解析仍是 `forceRefresh = true`、同源候选仍是 `forceRefresh = true`（Task 5/6 的候选校验靠它拿到新结论） |
 * | G4 | 负向自证：G1/G2/G3 的判据在对应的破坏样本上必须判负；空文本 / 找不到函数体必须直接失败（门不得空转） |
 *
 * ⚠️ 已知取向（有意为之，与 `ReplaceChainWiringGateTest` 的 G3 同类）：G2 要求**显式**写出
 * `forceRefresh = false`。把实参整个删掉（靠形参默认值）行为等价，但门会红——
 * 这个点是 B4 的修复本身，钉死写法比容忍等价写法更重要。
 *
 * ⚠️ 断言一律 `assertTrue` / `assertFalse`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 * ⚠️ 匹配前把空白折叠成单个空格——重新缩进不该弄断门。
 * ⚠️ 读文件必须显式 UTF-8（源码里有中文注释，本机 JVM 默认字符集是 CP936）。
 * ⚠️ 只锚稳定符号名与括号配平，⛔ 不用行号锚点、⛔ 不写绝对路径。
 */
class PlayerDeadProbeResolveGateTest {

    // ─────────────────── G1：1.5s 重试的 DEAD 前置门 ───────────────────

    @Test
    fun `G1 首轮失败的重试必须跳过已被判据钉为 DEAD 的曲目`() {
        val src = playerViewModelSource()
        assertTrue(
            "G1 空转自证：读到的确实是 PlayerViewModel",
            src.contains("class PlayerViewModel")
        )
        assertTrue(
            "G1：`resolveAndPlayByIndex` 的 1.5s 重试没有读 `isProbedDead` ——\n" +
                "一轮点播会把已确认的假 302 原曲完整重探一遍（全部端点 × 全部档位），\n" +
                "正是终审 B4 的「电视上按遥控器等三分钟」。判据见 §6 验收 16③。\n" +
                retryGateProblems(src).joinToString("\n"),
            retryGateProblems(src).isEmpty()
        )
    }

    // ─────────────────── G2：跨源层不再强刷原曲 ───────────────────

    @Test
    fun `G2 跨源替换层必须以 forceRefresh=false 解析原曲`() {
        val src = playerViewModelSource()
        assertTrue(
            "G2：`tryCrossSourceReplace` 仍以 forceRefresh=true 解析原曲（或实参被搬出调用）——\n" +
                "跨源层是原曲的第 3 遍重探，DEAD 判定刚被首轮写下又被它删掉（终审 B4）。\n" +
                crossSourceProblems(src).joinToString("\n"),
            crossSourceProblems(src).isEmpty()
        )
    }

    // ─────────────────── G3：其余站点零回归 ───────────────────

    @Test
    fun `G3 首轮解析与同源候选的 forceRefresh 语义不得被顺手改掉`() {
        val vm = playerViewModelSource()
        val mgr = managerSource()
        assertTrue(
            "G3：首轮解析丢了 forceRefresh=true —— 链接过期后永远命中「已过期未到 TTL」的旧缓存（既有修复，勿动）",
            fnRegion(vm, RESOLVE_FN).contains("var playUrl = resolveStreamUrl(song, forceRefresh = true)")
        )
        assertTrue(
            "G3：同源候选丢了 forceRefresh=true —— Task 5 的负面缓存会把原曲钉住，候选整批复用旧判定（Q7③ 层 1 失效）",
            fnRegion(mgr, "suspend fun resolveSameSourceReplacement(")
                .contains("resolvePlayUrlDetailed(candidate, quality, forceRefresh = true)")
        )
    }

    // ─────────────────── G4：负向自证（门不得空转） ───────────────────

    @Test
    fun `G4 破坏样本必须被同一套判据拦下`() {
        val vm = playerViewModelSource()
        // ⚠️ 破坏样本一律在**函数体区域**上做：`if (playUrl.isNullOrBlank())` 在全文件里还有第三处（别的函数），
        // 拿全文当样本会让「第 n 次出现」的计数口径对不上，门会在错误的形状上空转。
        val region = fnRegion(vm, RESOLVE_FN)
        assertTrue("前提自证：resolveAndPlayByIndex 的区域为空", region.isNotBlank())

        // 破坏 1：删掉重试的 DEAD 前置门（回到 B4 的现场）
        val noGate = region.replace(RETRY_GATE, PLAIN_IF)
        assertTrue("自证失效：破坏样本 1 没改动到区域文本（锚串与源码不符）", noGate != region)
        assertTrue("护栏失效：删掉前置门后 G1 仍判正 —— G1 在空转", retryGateProblems(noGate).isNotEmpty())

        // 破坏 1b：门挪到后面那个「全部降级失效」的 if 上（位置错了等于没门）
        assertTrue("前提自证：区域里改造前的门形状应恰有一处（第二个 if）", countOf(region, PLAIN_IF) == 1)
        assertTrue("前提自证：区域里的 DEAD 前置门应恰有一处", countOf(region, RETRY_GATE) == 1)
        val movedGate = replaceNth(noGate, PLAIN_IF, RETRY_GATE, 1)
        assertTrue("自证失效：第二个 if 没被替换成功（replaceNth 坏了）", movedGate != noGate)
        assertTrue("护栏失效：门挪到第二个 if 后 G1 仍判正 —— G1 只查了存在性没查位置",
            retryGateProblems(movedGate).isNotEmpty())

        // 破坏 2：跨源层回到 forceRefresh=true（B4 的原样；该串在本文件唯一，见下方自证）
        assertTrue("前提自证：`forceRefresh = false` 在 PlayerViewModel 里必须唯一",
            countOf(vm, "forceRefresh = false") == 1)
        val strongRefresh = vm.replace("forceRefresh = false", "forceRefresh = true")
        assertTrue("护栏失效：跨源层改回 true 后 G2 仍判正 —— G2 在空转",
            crossSourceProblems(strongRefresh).isNotEmpty())

        // 破坏 2b：实参被挪到调用外面（G2 必须查的是「调用实参里」，不是「函数体里随便哪儿」）。
        // 用合成样本而非真源码串：门必须走到「实参里没有」这条问题，而不是「整段调用都没找到」那条兜底。
        val flagOutsideCall = flatten(
            "private suspend fun tryCrossSourceReplace(song: Song): Song? {\n" +
                " val forceRefresh = false\n" +
                " return nasMusicApp.networkMusicManager.resolvePlayUrlWithCrossSourceFallback(song = song)\n" +
                "}"
        )
        val outsideProblems = crossSourceProblems(flagOutsideCall)
        assertTrue(
            "自证失效：合成样本没走到实参判据（说明 callArgs 或 fnRegion 坏了），实到：$outsideProblems",
            outsideProblems.any { it.contains("实参里没有") } &&
                outsideProblems.none { it.contains("跨源层没接线") }
        )

        // 破坏 3：G3 的两处既有 forceRefresh 被顺手改成 false
        val firstRoundLost = vm.replace(
            "var playUrl = resolveStreamUrl(song, forceRefresh = true)",
            "var playUrl = resolveStreamUrl(song, forceRefresh = false)"
        )
        assertTrue("自证失效：破坏样本 3a 没改动到源码文本", firstRoundLost != vm)
        assertTrue("护栏失效：首轮解析被改负后 G3 仍判正",
            !fnRegion(firstRoundLost, RESOLVE_FN)
                .contains("var playUrl = resolveStreamUrl(song, forceRefresh = true)"))
        val candidateLost = managerSource().replace(
            "resolvePlayUrlDetailed(candidate, quality, forceRefresh = true)",
            "resolvePlayUrlDetailed(candidate, quality, forceRefresh = false)"
        )
        assertTrue("自证失效：破坏样本 3b 没改动到 NetworkMusicManager 文本", candidateLost != managerSource())
        assertTrue("护栏失效：同源候选被改负后 G3 仍判正",
            !fnRegion(candidateLost, "suspend fun resolveSameSourceReplacement(")
                .contains("resolvePlayUrlDetailed(candidate, quality, forceRefresh = true)"))

        // 破坏 4：目标整段不存在时，判据必须是「找不到」而不是「通过」
        assertTrue("护栏失效：空文本被判正 —— G1 判据是恒真的", retryGateProblems("").isNotEmpty())
        assertTrue("护栏失效：空文本下 G2 仍判正 —— G2 判据是恒真的", crossSourceProblems("").isNotEmpty())
        assertTrue("护栏失效：文本里有调用名却配平不了括号时被判正",
            crossSourceProblems("fun tryCrossSourceReplace( x { $CROSS_SOURCE_CALL forceRefresh = false")
                .isNotEmpty())
    }

    /** 结构锚点必须真实存在（否则门只是在读空气）。 */
    @Test
    fun `G4 结构锚点必须真实存在`() {
        val vm = playerViewModelSource()
        val mgr = managerSource()
        assertTrue("找不到 resolveAndPlayByIndex 的定义 ⇒ 读到的是错的函数/错的文件",
            vm.contains("fun resolveAndPlayByIndex("))
        assertTrue("找不到 tryCrossSourceReplace 的定义 ⇒ 读到的是错的函数/错的文件",
            vm.contains("private suspend fun tryCrossSourceReplace("))
        assertTrue("resolveAndPlayByIndex 的函数体区域为空（括号不配平或被改写）⇒ G1 无据可断",
            fnRegion(vm, RESOLVE_FN).isNotBlank())
        assertTrue("tryCrossSourceReplace 的函数体区域为空 ⇒ G2 无据可断",
            fnRegion(vm, CROSS_FN).isNotBlank())
        assertTrue("函数体里没有那次重试（delay(1500) 不见了）⇒ G1 无据可断",
            fnRegion(vm, RESOLVE_FN).contains("delay(1500)"))
        assertTrue("找不到 resolveSameSourceReplacement ⇒ G3 无据可断",
            mgr.contains("suspend fun resolveSameSourceReplacement("))
        assertTrue("NetworkMusicManager 的函数体区域为空 ⇒ G3 无据可断",
            fnRegion(mgr, "suspend fun resolveSameSourceReplacement(").isNotBlank())
        assertTrue("isProbedDead 没在 NetworkMusicManager 里定义 ⇒ VM 侧的调用无源可查",
            mgr.contains("fun isProbedDead(song: Song): Boolean"))
    }

    // ─────────────────── 判据（纯函数，便于负向自证复用） ───────────────────

    /** G1 判据的问题清单（空 = 合规）。门必须在 `delay(1500)` 之前、且只出现一次。 */
    private fun retryGateProblems(text: String): List<String> {
        val region = fnRegion(text, RESOLVE_FN)
        if (region.isEmpty()) {
            return listOf("G1：找不到 `resolveAndPlayByIndex` 的函数体（或其括号不配平）—— 门拒绝在空区域上判正")
        }
        val problems = mutableListOf<String>()
        val hits = findAll(region, RETRY_GATE)
        if (hits.isEmpty()) problems += "重试条件里没有 `$RETRY_GATE`"
        if (hits.size > 1) problems += "DEAD 前置门出现了 ${hits.size} 次（只该挡 1.5s 重试那一次）"
        val delayAt = region.indexOf("delay(1500)")
        if (delayAt < 0) problems += "找不到 1.5s 重试本体 `delay(1500)` —— 区域不是预期的那段"
        else if (hits.isNotEmpty() && hits[0] > delayAt) {
            problems += "DEAD 前置门出现在 `delay(1500)` 之后 —— 它挡在「全部降级失效」的分支上，重试照样跑"
        }
        return problems
    }

    /** G2 判据的问题清单（空 = 合规）：只看 `resolvePlayUrlWithCrossSourceFallback(...)` 的实参。 */
    private fun crossSourceProblems(text: String): List<String> {
        val region = fnRegion(text, CROSS_FN)
        if (region.isEmpty()) {
            return listOf("G2：找不到 `tryCrossSourceReplace` 的函数体（或其括号不配平）—— 门拒绝在空区域上判正")
        }
        val args = callArgs(region, CROSS_SOURCE_CALL)
        if (args == null) return listOf("G2：`tryCrossSourceReplace` 体内没有 `$CROSS_SOURCE_CALL(...)` 调用 —— 跨源层没接线")
        val problems = mutableListOf<String>()
        if (!args.contains("forceRefresh = false")) problems += "跨源调用的实参里没有 `forceRefresh = false`"
        if (args.contains("forceRefresh = true")) problems += "跨源调用的实参仍是 `forceRefresh = true`（终审 B4 现场）"
        return problems
    }

    /**
     * 函数体文本（签名起、到与之配平的 `}` 止）。
     * 只锚函数名与括号配平，⛔ 不锚行号/缩进/控制流写法；找不到或不配平返回空串，由调用方判负。
     */
    private fun fnRegion(text: String, signature: String): String {
        val start = text.indexOf(signature)
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

    /** [callee] 那次调用的实参文本（圆括号配平）；没有该调用返回 null。 */
    private fun callArgs(text: String, callee: String): String? {
        val at = text.indexOf(callee)
        if (at < 0) return null
        val open = text.indexOf('(', at + callee.length - 1)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = open
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
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return text.substring(open + 1, i)
                    }
                }
            }
            i++
        }
        return null
    }

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

    private fun countOf(text: String, needle: String): Int = findAll(text, needle).size

    /** 替换第 [n] 次（0 起）出现的 [needle]；找不到则原样返回，由调用方的自证发现。 */
    private fun replaceNth(text: String, needle: String, replacement: String, n: Int): String {
        val at = findAll(text, needle).getOrNull(n) ?: return text
        return text.substring(0, at) + replacement + text.substring(at + needle.length)
    }

    // ─────────────────── 辅助：定位源码 / 读文本 ───────────────────

    private fun playerViewModelSource(): String = flatten(mainSourceFile("ui/viewmodel/PlayerViewModel.kt"))

    private fun managerSource(): String = flatten(mainSourceFile("backend/network/NetworkMusicManager.kt"))

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

    private companion object {
        val WHITESPACE = Regex("""\s+""")

        /** G1 的重试前置门（flatten 后的单空格写法，与源码逐字对应） */
        const val RETRY_GATE =
            "if (playUrl.isNullOrBlank() && !nasMusicApp.networkMusicManager.isProbedDead(song))"

        /** 改造前的门本体形状（flatten 后带尾随 `{`），G4 用它构造「门挪错位置」的样本 */
        const val PLAIN_IF = "if (playUrl.isNullOrBlank()) {"

        /** G1 的函数锚点（稳定符号名） */
        const val RESOLVE_FN = "fun resolveAndPlayByIndex("

        /** G2 的函数锚点 */
        const val CROSS_FN = "fun tryCrossSourceReplace("

        /** G2 的跨源解析调用（稳定符号名，⛔ 不锚实参形状以外的东西） */
        const val CROSS_SOURCE_CALL = "resolvePlayUrlWithCrossSourceFallback("
    }
}
