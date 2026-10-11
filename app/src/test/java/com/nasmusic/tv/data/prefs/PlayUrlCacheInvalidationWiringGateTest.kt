package com.nasmusic.tv.data.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终审 B3 —— 直链缓存失效**注册链**的源码文本门。
 *
 * 行为侧（`setMusicSource` / `setMetingApiBaseUrl` 到底有没有调回调）已经由同目录
 * `PlayUrlCacheInvalidateTest` 在 Robolectric 里驱动真 `AppPreferences` 钉住。
 * 本门只补它够不到的两截接线：
 *
 * | # | 内容 |
 * |---|---|
 * | W1 | `NasMusicApp` 里存在 `setOnPlayUrlCacheInvalidated { networkMusicManager.clearPlayUrlCache() }` 这一行，且既有的档位注册 `setOnQualityTierChanged` 一字未动（brief 明令） |
 * | W2 | `AppPreferences.setMusicSource` **函数体内**的 invoke 位于 `dataStore.edit` **之后**，且被 `runCatching` 包住 |
 * | W3 | `AppPreferences.setMetingApiBaseUrl` 体内的 invoke 既在 `dataStore.edit` 之后、也在「非法 URL 直接 return」守卫之后 |
 * | W4 | 负向自证：同一套判据在破坏样本上必须判负；空文本 / 找不到函数体一律判负（门不得空转） |
 *
 * 为什么这三条值得单独钉：`NasMusicApp` 的注册行被删掉 ⇒ 全仓 0 条测试变红、编译过、lint 过，
 * 用户在电视上看到的是「换了自建端点还是五分钟不放歌」，release 下一条日志都没有。
 * W2/W3 的顺序约束更是行为测试测不出来的——回调若写在 `dataStore.edit` **之前**，
 * 行为完全等价（照样触发），但写盘失败时就把缓存白清了一次。
 *
 * 范本：`backend/network/ReplaceChainWiringGateTest.kt`（读文件显式 UTF-8、按花括号配平抽函数体、
 * ⛔ 不用行号锚点、⛔ 不写绝对路径、断言一律 `assertTrue`/`assertFalse`）。
 */
class PlayUrlCacheInvalidationWiringGateTest {

    // ─────────────────── W1：NasMusicApp 的注册行 ───────────────────

    @Test
    fun `W1 NasMusicApp 必须把失效回调注册到清直链缓存`() {
        val src = nasMusicAppSource()
        assertTrue("W1 空转自证：读到的确实是 NasMusicApp", src.contains("class NasMusicApp"))
        assertTrue(
            "W1：`NasMusicApp` 的直链缓存失效接线不合规 ——\n" +
                "换平台/换端点后旧的 DEAD 判定会跟着 networkId 活满 5 分钟，串到另一首歌上（终审 B3）。\n" +
                "回调行为侧已由 PlayUrlCacheInvalidateTest 钉住，缺的正是这一行接线。\n" +
                registrationProblems(src).joinToString("\n"),
            registrationProblems(src).isEmpty()
        )
    }

    // ─────────────────── W2/W3：AppPreferences 的 invoke 位置 ───────────────────

    @Test
    fun `W2 setMusicSource 体内必须在写盘之后调用回调`() {
        val src = appPreferencesSource()
        assertTrue("W2 空转自证：读到的确实是 AppPreferences", src.contains("class AppPreferences"))
        assertTrue(
            "W2：`setMusicSource` 的回调接线不合规（缺失 / 在 dataStore.edit 之前 / 没被 runCatching 包住）——\n" +
                W2_MARKER + "\n" +
                invokeOrderingProblems(src, SET_MUSIC_SOURCE_FN).joinToString("\n"),
            invokeOrderingProblems(src, SET_MUSIC_SOURCE_FN).isEmpty()
        )
    }

    @Test
    fun `W3 被拒绝的非法端点写入不得调用回调`() {
        val src = appPreferencesSource()
        assertTrue(
            "W3：`setMetingApiBaseUrl` 的回调接线不合规——回调跑到「非法 URL 直接 return」的守卫之前了，\n" +
                "被拒绝的写入也会把缓存白清一次。\n" +
                W2_MARKER + "\n" +
                invokeOrderingProblems(src, SET_BASE_URL_FN, requireGuardFirst = true).joinToString("\n"),
            invokeOrderingProblems(src, SET_BASE_URL_FN, requireGuardFirst = true).isEmpty()
        )
    }

    // ─────────────────── W4：负向自证（门不得空转） ───────────────────

    @Test
    fun `W4 破坏样本必须被同一套判据拦下`() {
        val app = nasMusicAppSource()

        // 破坏 1：整行注册被删（今天最可能发生的漏接——编译过、其余测试全绿）
        val regDeleted = app.replace(NEW_REGISTRATION, "")
        assertTrue("自证失效：锚串与 NasMusicApp 源码不符（改动后门会静默在旧文本上空转）",
            app.contains(NEW_REGISTRATION) && regDeleted != app)
        assertTrue("护栏失效：删掉注册行后 W1 仍判正 —— W1 在空转",
            registrationProblems(regDeleted).isNotEmpty())

        // 破坏 1b：注册了但回调体是空的（名字在、什么都没清）
        val emptyBody = app.replace(NEW_REGISTRATION, "appPreferences.setOnPlayUrlCacheInvalidated { }")
        assertTrue("自证失效：破坏样本 1b 没改动到文本", emptyBody != app)
        assertTrue("护栏失效：空回调体仍被判正 —— 回调体检查是空操作",
            registrationProblems(emptyBody).isNotEmpty())

        // 破坏 1c：既有档位注册被动过（brief 明令不许）
        val qualityLost = app.replace(QUALITY_REGISTRATION, "")
        assertTrue("自证失效：破坏样本 1c 没改动到文本", qualityLost != app)
        assertTrue("护栏失效：档位注册被删后 W1 仍判正", registrationProblems(qualityLost).isNotEmpty())

        // 破坏 1d：空文本必须判负
        assertTrue("护栏失效：空文本下 W1 仍判正", registrationProblems("").isNotEmpty())

        // 破坏 2：invoke 挪到 dataStore.edit 之前（行为测试测不出的那一种）
        val beforeEdit = flatten(
            "suspend fun setMusicSource(sourceKey: String) {\n" +
                " runCatching { onPlayUrlCacheInvalidated?.invoke() } .onFailure { AppLog.w(TAG, \"x\") }\n" +
                " dataStore.edit { it[keyMusicSource] = sourceKey }\n" +
                "}"
        )
        val beforeProblems = invokeOrderingProblems(beforeEdit, SET_MUSIC_SOURCE_FN)
        assertTrue("自证失效：合成样本没走到「顺序」判据，实到：$beforeProblems",
            beforeProblems.any { it.contains("之前") } && beforeProblems.none { it.contains("没有") })
        assertTrue("护栏失效：invoke 在写盘之前仍被判正", beforeProblems.isNotEmpty())

        // 破坏 3：回调没有 runCatching 包住（回调一抛就掀掉写盘）
        val unguarded = flatten(
            "suspend fun setMusicSource(sourceKey: String) {\n" +
                " dataStore.edit { it[keyMusicSource] = sourceKey }\n" +
                " onPlayUrlCacheInvalidated?.invoke()\n" +
                "}"
        )
        val unguardedProblems = invokeOrderingProblems(unguarded, SET_MUSIC_SOURCE_FN)
        assertTrue("自证失效：合成样本没走到「runCatching」判据，实到：$unguardedProblems",
            unguardedProblems.any { it.contains("runCatching") })
        assertTrue("护栏失效：裸 invoke 仍被判正", unguardedProblems.isNotEmpty())

        // 破坏 4：回调整段缺席
        val noInvoke = flatten(
            "suspend fun setMusicSource(sourceKey: String) {\n" +
                " dataStore.edit { it[keyMusicSource] = sourceKey }\n" +
                "}"
        )
        assertTrue("护栏失效：setMusicSource 里根本没有回调却被判正",
            invokeOrderingProblems(noInvoke, SET_MUSIC_SOURCE_FN).isNotEmpty())

        // 破坏 5：空文本必须判负，不能判正
        assertTrue("护栏失效：空文本被判正 —— 顺序判据是恒真的",
            invokeOrderingProblems("", SET_MUSIC_SOURCE_FN).isNotEmpty())
        assertTrue("护栏失效：空文本下找不到函数体却算通过",
            fnRegion("", SET_BASE_URL_FN).isEmpty())
    }

    /** 结构锚点必须真实存在（否则门只是在读空气）。 */
    @Test
    fun `W4 结构锚点必须真实存在`() {
        val prefsSrc = appPreferencesSource()
        val appSrc = nasMusicAppSource()
        assertTrue("找不到 setMusicSource 的定义 ⇒ 读到的是错的函数/错的文件",
            prefsSrc.contains(SET_MUSIC_SOURCE_FN))
        assertTrue("找不到 setMetingApiBaseUrl 的定义 ⇒ 读到的是错的函数/错的文件",
            prefsSrc.contains(SET_BASE_URL_FN))
        assertTrue("setMusicSource 的函数体区域为空（括号不配平或被改写）⇒ W2 无据可断",
            fnRegion(prefsSrc, SET_MUSIC_SOURCE_FN).isNotBlank())
        assertTrue("setMetingApiBaseUrl 的函数体区域为空 ⇒ W3 无据可断",
            fnRegion(prefsSrc, SET_BASE_URL_FN).isNotBlank())
        assertTrue("setMetingApiBaseUrl 里找不到非法 URL 的守卫 ⇒ W3 的顺序判据无锚可依",
            fnRegion(prefsSrc, SET_BASE_URL_FN).contains("if (url.isNotBlank() && normalized == null)"))
        assertTrue("回调字段没在 AppPreferences 里声明 ⇒ 注册无源可接",
            prefsSrc.contains("private var onPlayUrlCacheInvalidated: (() -> Unit)? = null"))
        assertTrue("setter 没在 AppPreferences 里定义 ⇒ NasMusicApp 那行编译不过也就算了，门要能指出",
            prefsSrc.contains("fun setOnPlayUrlCacheInvalidated(cb: () -> Unit)"))
        assertTrue("NasMusicApp 里找不到 NetworkMusicManager 的构造 ⇒ 读错了文件",
            appSrc.contains("networkMusicManager = NetworkMusicManager("))
    }

    // ─────────────────── 判据（纯函数，便于负向自证复用） ───────────────────

    /** W1 判据（空 = 合规）：两条注册各就各位，且回调体都是清缓存。 */
    private fun registrationProblems(text: String): List<String> {
        val problems = mutableListOf<String>()
        if (!text.contains(NEW_REGISTRATION)) {
            problems += "缺 `$NEW_REGISTRATION`"
        }
        if (!text.contains(QUALITY_REGISTRATION)) {
            problems += "既有的 `$QUALITY_REGISTRATION` 被改动或删除（修 G5 的语义要原样保留）"
        }
        val n = countOf(text, CLEAR_BODY)
        if (n != 2) problems += "回调体为 `$CLEAR_BODY` 的注册有 $n 处（应为 2 处：档位 + 平台/端点）"
        return problems
    }

    /**
     * W2/W3 判据（空 = 合规）：函数体内必须存在被 `runCatching` 包住的 invoke，
     * 且位置在 `dataStore.edit` 之后；[requireGuardFirst] 时还要求在「守卫 return」之后。
     */
    private fun invokeOrderingProblems(
        text: String,
        signature: String,
        requireGuardFirst: Boolean = false
    ): List<String> {
        val region = fnRegion(text, signature)
        if (region.isEmpty()) {
            return listOf("找不到 `$signature` 的函数体（或括号不配平）—— 门拒绝在空区域上判正")
        }
        val problems = mutableListOf<String>()
        val editAt = region.indexOf("dataStore.edit")
        val invokeAt = region.indexOf(INVOKE)
        val guardedAt = region.indexOf(GUARDED_INVOKE)
        if (editAt < 0) problems += "体内没有 `dataStore.edit`（写盘本体被搬走？）"
        if (invokeAt < 0) problems += "体内没有 `$INVOKE`（回调没接上）"
        if (guardedAt < 0) problems += "invoke 没有被 runCatching 包住（回调一抛就掀掉写盘，对齐 onQualityTierChanged 样板）"
        if (editAt >= 0 && invokeAt >= 0 && invokeAt < editAt) {
            problems += "`$INVOKE` 在 `dataStore.edit` 之前（写盘失败也会清缓存）"
        }
        if (requireGuardFirst) {
            val guardAt = region.indexOf("if (url.isNotBlank() && normalized == null)")
            if (guardAt < 0) problems += "找不到「非法 URL 直接 return」的守卫（被拒绝的写入无从判定）"
            else if (invokeAt >= 0 && invokeAt < guardAt) {
                problems += "invoke 在非法 URL 守卫之前（被拒绝的写入也会清缓存）"
            }
        }
        return problems
    }

    /**
     * 函数体文本（签名起、到与之配平的 `}` 止）。只锚函数名与括号配平，
     * ⛔ 不锚行号/缩进/控制流写法；找不到或不配平返回空串，由调用方按「无区域可断」判负。
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

    // ─────────────────── 辅助：定位源码 / 读文本 ───────────────────

    private fun nasMusicAppSource(): String = flatten(mainSourceFile("NasMusicApp.kt"))

    private fun appPreferencesSource(): String = flatten(mainSourceFile("data/prefs/AppPreferences.kt"))

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

        const val W2_MARKER = "判据口径：invoke 必须在 dataStore.edit 之后且被 runCatching 包住"

        /** NasMusicApp 里预期的两行注册（flatten 后的单空格写法，与源码逐字对应） */
        const val NEW_REGISTRATION =
            "appPreferences.setOnPlayUrlCacheInvalidated { networkMusicManager.clearPlayUrlCache() }"

        const val QUALITY_REGISTRATION =
            "appPreferences.setOnQualityTierChanged { networkMusicManager.clearPlayUrlCache() }"

        /** 两条注册共同的回调体（W1 用它确认「注册不是空 lambda」） */
        const val CLEAR_BODY = "{ networkMusicManager.clearPlayUrlCache() }"

        const val INVOKE = "onPlayUrlCacheInvalidated?.invoke()"
        const val GUARDED_INVOKE = "runCatching { $INVOKE }"

        const val SET_MUSIC_SOURCE_FN = "suspend fun setMusicSource("
        const val SET_BASE_URL_FN = "suspend fun setMetingApiBaseUrl("
    }
}
