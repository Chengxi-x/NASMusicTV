package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-7 内存镜像一致性测试（DoD：写 DataStore 后镜像 ≤1s 内更新）。
 *
 * ⚠️ 2026-10-16 重构（CI `ProviderMirrorTest > write lyrics kugou url…` 超时挂了一次后）：
 * 本类历史上已两次靠**扩窗口**治这个失败（1s→3s→10s，见 `3f76033` / `2e82eb7`），
 * 两次都没治到根 —— 因为原写法**分不出**「镜像真的慢」与「镜像收集器已经静默死掉」：
 * 收集器挂在 `Dispatchers.IO` 上，那是一个被整包 2202 个 Robolectric 用例共享的池，
 * 而 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` **没有 CoroutineExceptionHandler**
 * ⇒ 任一条收集器抛异常都会被 kotlinx 的默认处理器吞掉（只打 stderr），
 * 该键的镜像从此永不更新，`withTimeout` 到点炸出来的却是一个**没有 cause 的裸
 * TimeoutCancellationException** —— 真实原因被完全掩盖，于是后人只能继续猜「是机器慢」。
 *
 * 本次两处修**根因**（⛔ 不是第三次扩窗）：
 * 1. **给镜像一条独占线程**（`Executors.newSingleThreadExecutor`）：收集器的**启动**
 *    不再需要去抢被整包 2202 个 Robolectric 用例共享的 IO 池。
 *    ⚠️ 诚实交代：`collect` 的**恢复点**仍在 DataStore 自己的内部 scope 上（生产语义如此，
 *    ⛔ 不该为测试去改生产代码）⇒ 这一条只是**降低**调度压力，不承诺完全消除抖动。
 * 2. **给 scope 挂 [CoroutineExceptionHandler] 并把首个失败记下来** —— 这一条才是
 *    **保证可诊断**的关键：超时报错时若发现收集器已死，直接把真实异常挂成 cause；
 *    并新增负向自证用例证明这条报错路径真的通。于是下次再挂，报错会直接说
 *    「收集器已死，真实原因是 X」或「当前值=… 期望=…」，而不是一个没法查的裸超时。
 *
 * 窗口仍是 10s，但它是**安全网**、⛔ 不再是可调参数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderMirrorTest {

    private lateinit var prefs: AppPreferences
    private lateinit var scope: CoroutineScope
    private lateinit var mirrorExecutor: ExecutorService

    /** 镜像收集器的首个未捕获异常（跨线程写、测试线程读 ⇒ 必须 @Volatile） */
    @Volatile
    private var mirrorFailure: Throwable? = null

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
        mirrorFailure = null
        mirrorExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "provider-mirror-test").apply { isDaemon = true }
        }
        scope = CoroutineScope(
            SupervisorJob() +
                mirrorExecutor.asCoroutineDispatcher() +
                CoroutineExceptionHandler { _, e -> if (mirrorFailure == null) mirrorFailure = e }
        )
    }

    @After
    fun tearDown() {
        // ⚠️ @Before 半途失败时 JUnit 仍会调 @After：lateinit 未初始化就取消会抛二次异常、
        //    把真正的 setup 失败掩掉 ⇒ 两处都要守卫
        if (::scope.isInitialized) scope.cancel()
        if (::mirrorExecutor.isInitialized) mirrorExecutor.shutdownNow()
    }

    /**
     * 等 [actual] 收敛到 [expected]。
     *
     * ⛔ 不要靠调用方各自 `withTimeout` + `while` 复制这段逻辑（复制必然漂移）；
     * 统一的失败归因也集中在这里：**超时 ≠ 收集器死亡**，两者必须报成不同的样子。
     *
     * ⚠️ **取证留痕**（2026-10-17）：每次等待都会 `println` 一行实测耗时与轮询次数。
     * 本机 `ProviderMirrorTest` 7/7 全绿、每键收敛 ~30ms，而 CI 同一条 commit 连红两次
     * （一次 200ms 探针没等到、一次 10s 都没等到）——**光看行号无法区分「发射根本没来」
     * 与「线程被饿死/调度不到」**。这行输出会进 Gradle 的 test-results XML
     * （`<system-out>`，210/210 个类都有），CI 的取证步骤把它整份打出来 ⇒ **绿也能拿到耗时分布**。
     */
    private fun awaitMirror(
        key: String,
        expected: String,
        actual: () -> String,
        timeoutMs: Long = MIRROR_TIMEOUT_MS,
    ) {
        val startNs = System.nanoTime()
        var polls = 0
        try {
            runBlocking {
                withTimeout(timeoutMs) {
                    while (actual() != expected) {
                        polls++
                        delay(POLL_MS)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            val dead = mirrorFailure
            val current = actual()
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            println(
                "mirror-timing key=$key RESULT=timeout elapsed=${elapsedMs}ms polls=$polls " +
                    "budget=${timeoutMs}ms current='$current' expected='$expected' collectorDead=${dead != null}",
            )
            // 超时那一刻的**线程现场**：这条才能一锤定音区分两种成因——
            // 镜像线程 BLOCKED/WAITING（发射根本没来）还是 RUNNABLE 卡在别处（线程被饿死）。
            println("mirror-threads key=$key\n${threadSnapshot()}")
            val message = if (dead != null) {
                "$key 镜像收集器已死（真实原因见 cause）：当前='$current' 期望='$expected'"
            } else {
                "$key 镜像 ${timeoutMs}ms 内未收敛：当前='$current' 期望='$expected'"
            }
            throw AssertionError("$message [elapsed=${elapsedMs}ms polls=$polls]", dead ?: e)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        println("mirror-timing key=$key RESULT=converged elapsed=${elapsedMs}ms polls=$polls")
        // 镜像值对上了，也要确认**没有收集器悄悄死掉**（值可能是死前最后一次发射留下的）
        assertEquals("$key 镜像收集器不应有未捕获异常", null, mirrorFailure)
    }

    /**
     * 超时时抓一份线程现场：本用例的镜像线程（`provider-mirror-test`，独占单线程）+
     * DataStore / kotlinx 调度线程。栈只取前 4 帧，够定位「卡在哪个调用」又不刷屏。
     */
    private fun threadSnapshot(): String =
        Thread.getAllStackTraces().entries
            .filter { (t, _) ->
                val n = t.name
                n.contains("provider-mirror") || n.contains("datastore", ignoreCase = true) ||
                    n.startsWith("DefaultDispatcher") || n.startsWith("main")
            }
            .sortedBy { it.key.name }
            .take(14)
            .joinToString("\n") { (t, st) ->
                "  ${t.name} state=${t.state} :: " +
                    st.take(4).joinToString(" <- ") { f -> "${f.className}.${f.methodName}" }
            }

    @Test
    fun `write meting url then mirror updates within 1s`() {
        prefs.startProviderMirrors(scope)
        runBlocking { prefs.setMetingApiBaseUrl("http://mirror-test.example.com/api") }
        awaitMirror(
            "metingApiBaseUrl",
            "http://mirror-test.example.com/api",
            actual = { prefs.getMetingApiBaseUrlSync() },
        )
    }

    @Test
    fun `write mv url then mirror updates within 1s`() {
        prefs.startProviderMirrors(scope)
        runBlocking { prefs.setMvApiBaseUrl("http://mirror-mv.example.com/api") }
        awaitMirror(
            "mvApiBaseUrl",
            "http://mirror-mv.example.com/api",
            actual = { prefs.getMvApiBaseUrlSync() },
        )
    }

    @Test
    fun `write lyrics kugou url then mirror updates within 1s`() {
        prefs.startProviderMirrors(scope)
        runBlocking { prefs.setLyricsKugouBaseUrl("http://mirror-kugou.example.com") }
        awaitMirror(
            "lyricsKugouBaseUrl",
            "http://mirror-kugou.example.com",
            actual = { prefs.getLyricsKugouBaseUrlSync() },
        )
    }

    @Test
    fun `write weather api key then mirror updates within 1s`() {
        prefs.startProviderMirrors(scope)
        runBlocking { prefs.setWeatherApiKey("mirror-key-123") }
        awaitMirror("weatherApiKey", "mirror-key-123", actual = { prefs.getWeatherApiKeySync() })
    }

    /**
     * ⭐ 负向自证（项目惯例：护栏必须证明自己**会**失败，否则与空转无异）。
     *
     * 等一个**永不到达**的值，用短窗口（200ms）触发归因路径，证明：
     * ① 不可达值最终以 `AssertionError` 失败（而不是裸 `TimeoutCancellationException`）；
     * ② 失败信息里带**当前值与期望值** —— 这正是 2026-10-16 CI 那次挂掉时最缺的东西。
     */
    @Test
    fun `unreachable mirror value fails with attributable assertion error`() {
        prefs.startProviderMirrors(scope)
        runBlocking { prefs.setMetingApiBaseUrl("http://mirror-test.example.com/api") }
        val error = runCatching {
            awaitMirror("metingApiBaseUrl", "http://never-arrives.example.com", { prefs.getMetingApiBaseUrlSync() }, timeoutMs = 200)
        }.exceptionOrNull()
        assertTrue("等一个永不到达的值必须以 AssertionError 失败（否则超时归因路径没被证明可用）", error is AssertionError)
        val message = error?.message.orEmpty()
        assertTrue("失败信息必须带期望值，实际=$message", message.contains("http://never-arrives.example.com"))
        assertTrue("失败信息必须带当前值，实际=$message", message.contains("http://mirror-test.example.com/api"))
    }

    @Test
    fun `language mirror double write`() = runBlocking {
        prefs.setLanguage("zh")
        // 镜像立即生效（SharedPreferences 同步写）
        assertEquals("zh", prefs.getLanguageSync())
        // DataStore 事实源一致
        assertEquals("zh", prefs.language.first())
    }

    @Test
    fun `language mirror migration from datastore`() = runBlocking {
        // 模拟老版本：DataStore 已有值，镜像为空
        prefs.setLanguage("en")
        // 手动清空镜像（直接构造的实例 mirrorPrefs 与被测实例相同文件名）
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("language_mirror", Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertEquals("system", prefs.getLanguageSync())  // 迁移前：镜像空回退默认
        prefs.migrateLanguageMirrorIfNeeded()
        assertEquals("en", prefs.getLanguageSync())    // 迁移后：镜像从 DataStore 补写
    }

    private companion object {
        /** 轮询间隔：20ms 足够细，又不至于把 CPU 烧在本用例上 */
        const val POLL_MS = 20L

        /**
         * 安全网，⛔ 不是可调参数。真实修复是「独占线程 + 失败归因」，
         * 再靠往上调它治抖动就是把慢测试合法化（前两次扩窗的教训）。
         */
        const val MIRROR_TIMEOUT_MS = 10_000L
    }
}
