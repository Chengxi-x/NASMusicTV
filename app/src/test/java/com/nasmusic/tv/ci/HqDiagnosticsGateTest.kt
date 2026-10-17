package com.nasmusic.tv.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 高质量分离「取证链路」门禁。
 *
 * 背景：出问题的电视在用户那边，我们既不能复现也没有 adb。用户能回传的证据只有屏幕上的
 * 文字，于是这条链路上任何一处**截断**或**清空**都会让整轮排查白跑一场 ——
 * 上一版的 `.take(60)` 正好把 ORT 异常里能区分「内存给不出来 / 文件读不了 / 算子不支持」
 * 的那半句切掉了（v2.38.14 之前）。
 *
 * 文本门禁的理由与 [LocalMusicGateTest] 相同：这些点编译期没有类型约束，
 * 一次「顺手重构」就能无声拆掉。
 *
 * | # | 内容 |
 * |---|---|
 * | H1 | 用户可见的错误文案一律不截断（错误赋值行不得出现 `.take(`） |
 * | H2 | DemucsSeparator 的每条失败都走 recordError，诊断详情与短文案分离 |
 * | H3 | 横幅 5 秒自动消失（clearHqError）绝不能顺手清掉设置页要留存的详情 |
 * | H4 | 详情从编排器一路接到设置页渲染，任何一环断链都算失败 |
 * | H5 | 负向自证：把 `.take(60)` 塞回文案行，H1 必须挂 |
 */
class HqDiagnosticsGateTest {

    private val separatorSource by lazy { mainSourceFile("player/DemucsSeparator.kt") }
    private val orchestratorSource by lazy { mainSourceFile("player/HqSeparationOrchestrator.kt") }

    // ─────────────────── H1：不截断 ───────────────────

    @Test
    fun `用户可见错误文案不得截断`() {
        val offenders = listOf(
            "player/DemucsSeparator.kt" to separatorSource,
            "player/HqSeparationOrchestrator.kt" to orchestratorSource,
            "player/ModelDownloadManager.kt" to mainSourceFile("player/ModelDownloadManager.kt"),
        ).flatMap { (name, source) ->
            source.lineSequence().mapIndexedNotNull { index, line ->
                if (isErrorTextAssignment(line) && line.contains(".take(")) "$name:${index + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(
            "这些行把用户可见的错误文案截断了：\n" + offenders.joinToString("\n") + "\n" +
                "出问题的设备在用户那边，横幅与设置页里的原文是唯一取证通道 —— 要收口请用\n" +
                "DeviceDiagnostics.report() 追加信息，不要用 take(n) 删信息。",
            offenders.isEmpty(),
        )
    }

    // ─────────────────── H2：失败记录走 recordError ───────────────────

    @Test
    fun `分离器必须同时产出短文案与诊断详情`() {
        assertTrue(
            "DemucsSeparator 里的 recordError() 不见了 ——\n" +
                "失败点若退回裸赋值，lastErrorDetail 就永远是 null，设置页那一栏会空转。",
            separatorSource.contains("private fun recordError("),
        )
        assertTrue(
            "DemucsSeparator.lastErrorDetail 必须保持对外可读（编排器要把它存进 StateFlow）",
            separatorSource.contains("var lastErrorDetail: String? = null"),
        )
        // 空转自证：确实读到了分离器源码
        assertTrue(separatorSource.contains("class DemucsSeparator"))
    }

    // ─────────────────── H3：详情不随横幅消失 ───────────────────

    @Test
    fun `关闭横幅不得清掉诊断详情`() {
        val body = functionBody(orchestratorSource, "fun clearHqError()")
        assertTrue(
            "clearHqError() 里出现了 _hqErrorDetail ——\n" +
                "那是 5 秒后自动触发的横幅关闭；一起清掉就等于用户还没截图，证据已经没了。\n" +
                "清详情只应发生在「下一次分离成功」时。",
            !body.contains("_hqErrorDetail"),
        )
        assertTrue(
            "编排器缺 _hqErrorDetail 这条 StateFlow，设置页无从留存",
            orchestratorSource.contains("private val _hqErrorDetail = MutableStateFlow<String?>(null)"),
        )
    }

    // ─────────────────── H4：链路到设置页 ───────────────────

    @Test
    fun `诊断详情必须一路接到设置页`() {
        val hops = listOf(
            "player/PlayerManager.kt" to "val hqErrorDetail: StateFlow<String?> get() = hqOrchestrator.hqErrorDetail",
            "ui/viewmodel/VocalSeparationViewModel.kt" to "val hqErrorDetail: StateFlow<String?> = playerManager.hqErrorDetail",
            "ui/components/branches/SettingsBranch.kt" to "hqErrorDetail = hqErrorDetail,",
            "ui/screens/SettingsScreen.kt" to "hqErrorDetail: String? = null,",
            "ui/screens/settings/PlayerSettingsSection.kt" to "state.hqErrorDetail?.let",
            // 横幅判空的锚点必须是短文案：详情长期留存，拿它判空横幅就再也关不掉
            "ui/components/branches/NowPlayingBranch.kt" to "hqError = if (hqError != null) (hqErrorDetail ?: hqError) else null,",
        )
        hops.forEach { (path, anchor) ->
            val source = mainSourceFile(path)
            assertTrue(
                "$path 里找不到 `$anchor` —— 诊断详情在设置页的链路上断了这一环，" +
                    "用户就没法在设置页截图回传。",
                source.contains(anchor),
            )
        }
    }

    // ─────────────────── H5：负向自证 ───────────────────

    @Test
    fun `H1 不是空转`() {
        val sabotaged = separatorSource + "\n        lastError = msg.take(60)\n"
        assertTrue("护栏失效：塞回 .take(60) 后 H1 仍判通过 —— 这条门禁在空转", hasOffenderLine(sabotaged))
    }

    // ─────────────────── 辅助 ───────────────────

    /** 用户可见错误文案的赋值行（短文案 → 横幅；详情 → 设置页） */
    private fun isErrorTextAssignment(line: String): Boolean =
        line.contains("lastError =") ||
            line.contains("lastDownloadError =") ||
            line.contains("_hqError.value =") ||
            line.contains("lastErrorDetail =")

    private fun hasOffenderLine(source: String): Boolean =
        source.lineSequence().any { isErrorTextAssignment(it) && it.contains(".take(") }

    /** 取顶层函数/方法的源码片段（从签名到下一行独占的 `}`） */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`", start >= 0)
        val rest = source.substring(start)
        val end = rest.indexOf("\n    }")
        return if (end < 0) rest else rest.substring(0, end)
    }

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码根（user.dir=${File("").absolutePath}）；已尝试：" +
                candidates.joinToString { it.absolutePath },
            found != null,
        )
        return found!!
    }
}
