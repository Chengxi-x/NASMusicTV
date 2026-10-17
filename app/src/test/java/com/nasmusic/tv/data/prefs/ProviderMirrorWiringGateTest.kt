package com.nasmusic.tv.data.prefs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R-7 provider 键**内存镜像**的确定性接线门（源码文本，零时序、零 Robolectric）。
 *
 * 背景：镜像链原来的唯一覆盖是 `ProviderMirrorTest` 里 4 条「写 DataStore ⇒ 轮询等 `@Volatile`
 * 收敛」的时序用例。那套等待在 CI 上踩了两次红（一次 200ms 探针、一次 10s），而 CI 的磁盘/CPU/
 * 影子框架与客户电视完全不同，**耗时数字对生产没有可比性**；可它同时又是全仓唯一驱动这条链的地方，
 * 直接删掉会让「改设置不生效」这一类故障变成零覆盖。所以拆成两件事：
 * **计时**交给真机（不在 CI 断言），**接线与形状**由本门钉住——本门判据全是源码文本，
 * 不会因为 runner 慢一秒就翻脸。
 *
 * | # | 钉住的东西 | 漏了会是什么症状 |
 * |---|---|---|
 * | G1 | `NasMusicApp.onCreate` 真的调了 `startProviderMirrors(applicationScope)` | 这行被删/挪进分支 ⇒ **所有**同步 getter 永远返回默认值，而编译过、lint 过、全仓其余测试全绿 |
 * | G2 | 每个 `cached*` 镜像字段：读侧有且只有一个 `get*Sync()`、写侧在 `startProviderMirrors` 体内被赋值、声明带 `@Volatile` | 新加一个同步 getter 却忘了加 collector（或反过来）⇒ 用户改了设置不生效，且没有任何自动化信号；丢 `@Volatile` ⇒ 写线程与读线程之间不保证可见性 |
 * | G3 | 镜像链路里的解密调用必须被 `try`/`runCatching` 包住 | 天气 Key 解密一抛 ⇒ 那条收集器当场死掉 ⇒ 该键**永久**失联（其余键照常，最难复现的那种） |
 * | G4 | 负向自证：同一套判据在破坏样本上必须判负；空文本 / 找不到函数体一律判负 | 门不得空转 |
 *
 * ⚠️ 语言镜像（`getLanguageSync`）不在这张网里：它走的是 **SharedPreferences 双写**（冷启动
 * `attachBaseContext` 无法挂起），不是常驻收集器，由 `LanguageMirrorTest` 的 2 条确定性用例守。
 * 本门对它只做一件事：确认它仍然读 `mirrorPrefs`，并把名字记在例外名单里——
 * 新增的同步 getter 若既无镜像字段又不在名单内，G2 会直接判负并说明两种改法。
 *
 * 范本：`data/prefs/PlayUrlCacheInvalidationWiringGateTest.kt`（读文件显式 UTF-8、按花括号配平抽函数体、
 * ⛔ 不用行号锚点、⛔ 不写绝对路径、断言一律 `assertTrue`/`assertFalse`）。
 */
class ProviderMirrorWiringGateTest {

    // ─────────────────── G1：启动接线 ───────────────────

    @Test
    fun `G1 NasMusicApp 必须启动 provider 键镜像收集`() {
        val app = flatten(mainSourceFile("NasMusicApp.kt"))
        assertTrue("G1 空转自证：读到的确实是 NasMusicApp", app.contains("class NasMusicApp"))
        assertTrue(
            "G1：`NasMusicApp.onCreate` 没有把 applicationScope 交给 `startProviderMirrors` ——\n" +
                "这一行缺席时全部同步 getter 只会返回编译期默认值，症状是「设置改了不生效」，\n" +
                "而编译过、lint 过、其余测试全绿（release 日志里一条线索都没有）。\n" +
                registrationProblems(app).joinToString("\n"),
            registrationProblems(app).isEmpty()
        )
    }

    // ─────────────────── G2：形状与覆盖 ───────────────────

    @Test
    fun `G2 每个镜像字段都要有读侧 写侧与 volatile`() {
        val prefs = flatten(mainSourceFile("data/prefs/AppPreferences.kt"))
        assertTrue("G2 空转自证：读到的确实是 AppPreferences", prefs.contains("class AppPreferences"))
        assertTrue(
            "G2：镜像形状不合规 —— 内存镜像必须「有人写、有人读、跨线程可见」三者齐备。\n" +
                coverageProblems(prefs).joinToString("\n"),
            coverageProblems(prefs).isEmpty()
        )
    }

    // ─────────────────── G3：解密守卫 ───────────────────

    @Test
    fun `G3 镜像链路里的解密必须被守卫包住`() {
        val prefs = flatten(mainSourceFile("data/prefs/AppPreferences.kt"))
        assertTrue(
            "G3：解密没被包住 ⇒ 密文坏掉时那条收集器直接抛死，天气 Key 从此永久停在空值" +
                "（其余镜像照常，是排查成本最高的一类失联）。\n" +
                decryptGuardProblems(prefs).joinToString("\n"),
            decryptGuardProblems(prefs).isEmpty()
        )
    }

    // ─────────────────── G4：负向自证（门不得空转） ───────────────────

    @Test
    fun `G4 破坏样本必须被同一套判据拦下`() {
        val app = flatten(mainSourceFile("NasMusicApp.kt"))
        val prefs = flatten(mainSourceFile("data/prefs/AppPreferences.kt"))

        // 破坏 1：整行启动调用被删（今天最可能、也最静默的漏接）
        val callDeleted = app.replace(MIRROR_CALL, "")
        assertTrue("自证失效：锚串与 NasMusicApp 源码不符（改动后门会在旧文本上静默空转）",
            app.contains(MIRROR_CALL) && callDeleted != app)
        assertTrue("护栏失效：删掉启动行后 G1 仍判正", registrationProblems(callDeleted).isNotEmpty())

        // 破坏 1b：改成传一个别的 scope（名字还在，但镜像挂在了会被取消的 scope 上）
        val wrongScope = app.replace(MIRROR_CALL, "appPreferences.startProviderMirrors(viewModelScope)")
        assertTrue("自证失效：破坏样本 1b 没改动到文本", wrongScope != app)
        assertTrue("护栏失效：非 applicationScope 仍被判正", registrationProblems(wrongScope).isNotEmpty())

        // 破坏 1c：空文本必须判负
        assertTrue("护栏失效：空文本下 G1 仍判正", registrationProblems("").isNotEmpty())

        // 破坏 2：某条 collector 的赋值被摘掉 ⇒ 该键读侧永远拿默认值
        val lostCollector = prefs.replace(
            "distinctUntilChanged().collect { cachedMetingApiBaseUrl = it }",
            "distinctUntilChanged().collect { }"
        )
        assertTrue("自证失效：破坏样本 2 没改动到文本", lostCollector != prefs)
        val lostProblems = coverageProblems(lostCollector)
        assertTrue("护栏失效：摘掉 meting collector 后没点名该字段，实到：$lostProblems",
            lostProblems.any { it.contains("cachedMetingApiBaseUrl") })
        assertTrue("护栏失效：摘掉 collector 后 G2 仍判正", lostProblems.isNotEmpty())

        // 破坏 3：@Volatile 被摘掉（跨线程可见性没了）
        val lostVolatile = prefs.replace("@Volatile private var cachedJamendoClientId", "private var cachedJamendoClientId")
        assertTrue("自证失效：破坏样本 3 没改动到文本", lostVolatile != prefs)
        assertTrue("护栏失效：缺 @Volatile 仍被判正",
            coverageProblems(lostVolatile).any { it.contains("cachedJamendoClientId") })

        // 破坏 4：新增一个读 cached 字段的同步 getter，却忘了加 collector —— 未来最可能踩的一种
        val newGetterOnly = flatten(
            "class AppPreferences {\n" +
                " @Volatile private var cachedSubsonicUrl: String = \"\"\n" +
                " fun getSubsonicUrlSync(): String = cachedSubsonicUrl\n" +
                " fun startProviderMirrors(scope: CoroutineScope) { scope.launch { } }\n" +
                "}"
        )
        val newProblems = coverageProblems(newGetterOnly)
        assertTrue("护栏失效：新键漏接 collector 却被判正，实到：$newProblems",
            newProblems.any { it.contains("cachedSubsonicUrl") })

        // 破坏 5：解密守卫被摘掉
        val bareDecrypt = prefs.replace(
            "try { com.nasmusic.tv.util.CryptoUtils.decrypt(enc) } catch (e: Exception) { \"\" }",
            "com.nasmusic.tv.util.CryptoUtils.decrypt(enc)"
        )
        assertTrue("自证失效：破坏样本 5 没改动到文本（解密写法变了，请同步更新锚串与判据）",
            bareDecrypt != prefs)
        assertTrue("护栏失效：裸解密仍被判正", decryptGuardProblems(bareDecrypt).isNotEmpty())
        assertTrue("护栏失效：空文本下 G3 仍判正", decryptGuardProblems("").isNotEmpty())

        // 破坏 6：镜像函数体整个消失 ⇒ 任何判据都不许判正
        val noBody = prefs.replace("fun startProviderMirrors(", "fun startProviderMirrorsX(")
        assertTrue("护栏失效：找不到函数体却被判正", coverageProblems(noBody).isNotEmpty())
        assertTrue("护栏失效：找不到函数体时 fnRegion 不算空", fnRegion(noBody, MIRROR_FN).isEmpty())
    }

    /** 结构锚点必须真实存在（否则门只是在读空气）。 */
    @Test
    fun `G4 结构锚点必须真实存在`() {
        val prefs = flatten(mainSourceFile("data/prefs/AppPreferences.kt"))
        val app = flatten(mainSourceFile("NasMusicApp.kt"))
        assertTrue("找不到 `$MIRROR_FN` 的定义 ⇒ 读到的是错的文件", prefs.contains(MIRROR_FN))
        assertTrue("镜像函数体为空或不配平 ⇒ G2/G3 无据可断", fnRegion(prefs, MIRROR_FN).isNotBlank())
        assertTrue("一个镜像字段都没枚举到 ⇒ 字段命名约定已变，门必须显式失败",
            mirrorFields(prefs).isNotEmpty())
        assertTrue("一个同步 getter 都没枚举到 ⇒ getter 命名约定已变", syncGetters(prefs).isNotEmpty())
        assertTrue("NasMusicApp 里找不到 `$MIRROR_CALL` ⇒ 读错了文件", app.contains(MIRROR_CALL))
        assertTrue("例外名单里的 getter 在本文件不存在（名单该瘦身了）",
            NON_MIRROR_GETTERS.all { prefs.contains("fun $it()") })
    }

    // ─────────────────── 判据（纯函数，便于负向自证复用） ───────────────────

    /** G1 判据（空 = 合规）：必须存在「applicationScope 启动镜像」这一行接线。 */
    private fun registrationProblems(appText: String): List<String> {
        val problems = mutableListOf<String>()
        if (!appText.contains(MIRROR_CALL)) {
            problems += "缺 `$MIRROR_CALL`（镜像收集从未启动？）"
        }
        return problems
    }

    /**
     * G2 判据（空 = 合规）：以 `cached*` 字段为单位，逐个核对三件事——
     * ① 读侧：恰好一个 `get*Sync()` 的函数体引用它（0 个=死镜像，>1 个=判据窗口串味，都要问）；
     * ② 写侧：`startProviderMirrors` 体内存在对它的赋值；
     * ③ 可见性：声明带 `@Volatile`（写线程是 DataStore 的内部 scope，读线程是 UI/主线程）。
     *
     * 同时反向核对：每个 `get*Sync()` 都必须被解释（认领某个镜像字段，或在 [NON_MIRROR_GETTERS]
     * 例外名单里且函数体确实读 `mirrorPrefs`）——这条才是「新增键忘了接镜像」的捕手。
     */
    private fun coverageProblems(prefsText: String): List<String> {
        val body = fnRegion(prefsText, MIRROR_FN)
        if (body.isEmpty()) {
            return listOf("找不到 `$MIRROR_FN` 的函数体（或括号不配平）—— 门拒绝在空区域上判正")
        }
        val problems = mutableListOf<String>()
        val fields = mirrorFields(prefsText)
        val getters = syncGetters(prefsText)
        if (fields.isEmpty()) problems += "一个 `cached*` 镜像字段都没枚举到（命名约定变了？）"
        if (getters.isEmpty()) problems += "一个 `get*Sync()` 都没枚举到（命名约定变了？）"

        fields.forEach { field ->
            val readers = getters.filter { getterWindow(prefsText, it).contains(field) }
            if (readers.isEmpty()) {
                problems += "镜像字段 `$field` 没有任何 `get*Sync()` 读它（写了没人读=死镜像，或读侧被改回挂起读）"
            } else if (readers.size > 1) {
                problems += "镜像字段 `$field` 被 ${readers.size} 个同步 getter 引用：${readers.joinToString()}（判据要求 1:1，多个说明窗口串到了别的成员）"
            }
            if (!Regex("${Regex.escape(field)}\\s*=").containsMatchIn(body)) {
                problems += "镜像字段 `$field` 在 `$MIRROR_FN` 体内没有被任何 collect 赋值 ⇒ 对应 getter 会永远返回默认值（改设置不生效）"
            }
            val decl = prefsText.indexOf("private var $field")
            if (decl < 0) {
                problems += "找不到 `private var $field` 的声明（枚举与正文不一致）"
            } else if (!prefsText.substring(maxOf(0, decl - VOLATILE_LOOKBACK), decl).contains("@Volatile")) {
                problems += "镜像字段 `$field` 的声明缺 `@Volatile`（写线程与读线程之间不保证可见性）"
            }
        }

        getters.forEach { getter ->
            val window = getterWindow(prefsText, getter)
            val claimed = fields.filter { window.contains(it) }
            if (claimed.isNotEmpty()) return@forEach
            if (getter in NON_MIRROR_GETTERS) {
                if (!window.contains("mirrorPrefs")) {
                    problems += "`$getter` 在例外名单里但函数体已经不读 `mirrorPrefs` ⇒ 名单与实现脱节，请重新归类"
                }
            } else {
                problems += "新增的同步 getter `$getter` 既没有认领任何 `cached*` 镜像字段，也不在例外名单里 ——\n" +
                    "  要么给它加 collector + `@Volatile private var cached*`（推荐，与其余键同构），\n" +
                    "  要么确属 SharedPreferences 双写那类机制，再把名字加进 NON_MIRROR_GETTERS 并保证函数体读 mirrorPrefs"
            }
        }
        return problems
    }

    /** G3 判据（空 = 合规）：镜像体内出现解密时，其紧邻之前必须有 `try {` 或 `runCatching`。 */
    private fun decryptGuardProblems(prefsText: String): List<String> {
        val body = fnRegion(prefsText, MIRROR_FN)
        if (body.isEmpty()) {
            return listOf("找不到 `$MIRROR_FN` 的函数体 —— 门拒绝在空区域上判正")
        }
        val at = body.indexOf(DECRYPT)
        if (at < 0) return emptyList() // 该键不再解密，无守卫可要求
        val before = body.substring(maxOf(0, at - GUARD_LOOKBACK), at)
        return if (before.contains("try {") || before.contains("runCatching")) emptyList() else
            listOf("`$DECRYPT` 没有被 try/runCatching 包住（解密一抛，这条收集器当场死掉，该键永久失联）")
    }

    /** 镜像字段名：`private var cached*` 声明（⛔ 不要求带 @Volatile，@Volatile 由判据单独检查）。 */
    private fun mirrorFields(text: String): List<String> =
        Regex("private var (cached\\w+)").findAll(text).map { it.groupValues[1] }.toList()

    /** 同步 getter 名：`fun get*Sync()`。 */
    private fun syncGetters(text: String): List<String> =
        Regex("fun (get\\w+Sync)\\(\\)").findAll(text).map { it.groupValues[1] }.toList()

    /**
     * 某个同步 getter 的「函数体窗口」：签名起，到下一个 `fun ` 或 [GETTER_WINDOW_CHARS] 字符为止。
     * 够覆盖单行返回表达式与 `getLanguageSync` 那种短块体；串到下一个成员时窗口也已经在
     * 下一个 `fun` 处截断，所以字段归属仍由 coverage 判据的 1:1 约束兜住。
     */
    private fun getterWindow(text: String, getter: String): String {
        val start = text.indexOf("fun $getter()")
        if (start < 0) return ""
        val nextFun = text.indexOf("fun ", start + 1)
        val end = if (nextFun < 0) text.length else minOf(nextFun, start + GETTER_WINDOW_CHARS)
        return text.substring(start, maxOf(start, minOf(end, text.length)))
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

    // ─────────────────── 辅助：定位源码 / 读文本 ───────────────────

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

        /** NasMusicApp 里预期的启动行（flatten 后的单空格写法，与源码逐字对应） */
        const val MIRROR_CALL = "appPreferences.startProviderMirrors(applicationScope)"

        const val MIRROR_FN = "fun startProviderMirrors("

        /** 唯一一处会抛的镜像逻辑：天气 Key 解密（其余键都是纯 map） */
        const val DECRYPT = "CryptoUtils.decrypt"

        /** 守卫只可能写在解密的紧邻之前；20 字符够容纳 `try { com.nasmusic.tv.util.` 之前的尾巴 */
        const val GUARD_LOOKBACK = 90

        /** 声明与 @Volatile 之间最多隔着注释尾巴 + 单空格，20 字符足够 */
        const val VOLATILE_LOOKBACK = 20

        const val GETTER_WINDOW_CHARS = 320

        /** 不靠常驻收集器、而靠 SharedPreferences 双写的同步 getter（见类头 ⚠️ 说明） */
        val NON_MIRROR_GETTERS = setOf("getLanguageSync")
    }
}
