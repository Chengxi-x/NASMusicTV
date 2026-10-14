package com.nasmusic.tv.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.data.prefs.AppPreferences
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K 歌升降调 / 变速的 preview 与 commit 分工（决策 D2，见
 * `docs/karaoke-picker-tv-focus-fix-plan.md` §3-A3）。
 *
 * 所有者反馈「调原调必须确定后才能生效，无法及时预览」——修复是步进弹窗每换一档就调
 * [VocalSeparationViewModel.previewPitch] / [previewSpeed]（即时改播放器 + 页面状态、
 * **不写 DataStore**），只有「确定」才 commit。落盘次数必须与按键次数解耦，
 * 否则 25 档步进 = 25 次磁盘写。
 *
 * ⚠️ 协程调度：commit 走 `viewModelScope`（`Dispatchers.Main`），Robolectric 的主线程 Looper
 * 在 `runBlocking` 期间不会自己转 ⇒ 必须 `Dispatchers.setMain(UnconfinedTestDispatcher())`，
 * 否则在途协程永远醒不过来（表现为超时而不是断言失败）。等待一律轮询
 * （与 [ArtistExpansionGenerationGateTest] / `PlayModeToggleEventTest` 同法）。
 *
 * ⚠️ 存储实例：那两条同 `application = NasMusicApp::class` 的用例轮询的都是**内存态**，
 * 只有本案等**真实磁盘写**，所以必须给 VM 注入一个**独占临时目录**的 [AppPreferences]
 * （口径与 `AppPreferences` 自己的 `internal constructor` 注释一致：单测不共用进程单例）。
 * 两个坑都是实测踩出来的、[setUp] 里有完整说明：共用单例 ⇒ 写落到被 Robolectric 清掉的
 * 旧 dataDir（表现为 5s 超时，异常被 fire-and-forget 吞掉，全量跑随 worker 顺序时绿时红）；
 * 每条用例直接 `AppPreferences(app)` ⇒ 与同一条用例 onCreate 刚建好的单例**开在同一个文件**，
 * 撞 datastore 的 `There are multiple DataStores active for the same file`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NasMusicApp::class)
class VocalPitchSpeedPreviewCommitTest {

    private val app: NasMusicApp get() = ApplicationProvider.getApplicationContext()

    /** 本用例独占的存储实例（独占 DataStore 文件，见 [setUp]） */
    private lateinit var prefs: AppPreferences

    /** 本用例独占的 filesDir；DataStore 的实例数检查是按**文件路径**算的 */
    private lateinit var prefsDir: File

    private fun vm() = VocalSeparationViewModel(app, app.playerManager, prefs)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // 每条用例一个**独立的临时目录**，用它包一层 ContextWrapper 喂给 internal 构造器
        // （AppPreferences.kt:117 直接读 `context.filesDir`，不经 applicationContext，
        //  所以包装有效）。两个坑都实测踩过，都必须绕开：
        // ① 用 `NasMusicApp.appPreferences` 进程单例 —— 它的文件属于**第一个**建它的 sandbox
        //    的 dataDir，而 Robolectric 每个用例会把 dataDir 清掉重写 ⇒ 写永远落不到当前用例，
        //    commit 的 fire-and-forget 又吞掉异常，只剩 5s 超时（全量跑随 worker 顺序时绿时红）。
        // ② 直接 `AppPreferences(app)` 每条用例建新实例 —— 同一条用例内 onCreate 已经用
        //    **同一个 dataDir** 建起了进程单例的 DataStore，两个实例开在同一个文件上，
        //    撞上 datastore 的 `There are multiple DataStores active for the same file`。
        // 独占目录同时解决两者：路径唯一 ⇒ 无多实例冲突；目录由本用例创建、不被 sandbox 清理 ⇒ 写必落盘。
        prefsDir = File(System.getProperty("java.io.tmpdir"), "nasmusictv-prefs-${System.nanoTime()}")
        prefsDir.mkdirs()
        val isolatedContext = object : android.content.ContextWrapper(app) {
            override fun getFilesDir(): File = prefsDir
        }
        prefs = AppPreferences(isolatedContext)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
        prefsDir.deleteRecursively()
    }

    private suspend fun awaitPitchPersisted(expected: Int) = withTimeout(5_000) {
        while (prefs.player.pitchSemitones.first() != expected) delay(20)
    }

    private suspend fun awaitSpeedPersisted(expected: Double) = withTimeout(5_000) {
        while (prefs.player.playbackSpeed.first() != expected) delay(20)
    }

    @Test
    fun `preview 改状态但不落盘`() = runBlocking {
        val viewModel = vm()
        assertEquals("初始持久化应为原调", 0, prefs.player.pitchSemitones.first())

        viewModel.previewPitch(3)

        assertEquals("preview 必须立即更新状态（按钮标签与弹窗档位跟着变）", 3, viewModel.pitchSemitones.value)
        delay(200)
        assertEquals(
            "preview 绝不能写 DataStore（步进一次写一次盘 = 25 次写）",
            0,
            prefs.player.pitchSemitones.first()
        )
    }

    @Test
    fun `commit 才把当前预览值落盘`() = runBlocking {
        val viewModel = vm()

        viewModel.previewPitch(-5)
        assertEquals(-5, viewModel.pitchSemitones.value)
        viewModel.commitPitch()

        awaitPitchPersisted(-5)
        assertEquals(-5, prefs.player.pitchSemitones.first())
    }

    @Test
    fun `速度 preview 与 commit 同样分离`() = runBlocking {
        val viewModel = vm()

        viewModel.previewSpeed(0.8)
        assertEquals(0.8, viewModel.playbackSpeed.value, 0.0)
        delay(200)
        assertEquals(
            "preview 不写盘：初始持久化仍是原速",
            1.0,
            prefs.player.playbackSpeed.first(),
            0.0
        )

        viewModel.commitSpeed()
        awaitSpeedPersisted(0.8)
        assertEquals(0.8, prefs.player.playbackSpeed.first(), 0.0)
    }

    @Test
    fun `preview 越界值一律夹到合法区间`() {
        val viewModel = vm()

        viewModel.previewPitch(20)
        assertEquals("+20 必须夹到 +12", 12, viewModel.pitchSemitones.value)
        viewModel.previewPitch(-99)
        assertEquals("-99 必须夹到 -12", -12, viewModel.pitchSemitones.value)

        viewModel.previewSpeed(5.0)
        assertEquals("5.0 倍必须夹到 2.0", 2.0, viewModel.playbackSpeed.value, 0.0)
        viewModel.previewSpeed(0.1)
        assertEquals("0.1 倍必须夹到 0.5", 0.5, viewModel.playbackSpeed.value, 0.0)
    }

    @Test
    fun `本案的存储实例必须真的可写可读`() = runBlocking {
        // 上面两条 commit 用例的前提。这一条**直接 await 挂起写**（不经 viewModelScope 的
        // fire-and-forget），实例不可写时它会带着真实异常（IOException / DataStore 多实例检查）
        // 红掉 —— 而不是只留一句 `Timed out waiting for 5000 ms` 让下一个人从头猜。
        prefs.player.setPitchSemitones(-7)
        assertEquals(-7, prefs.player.pitchSemitones.first())

        prefs.player.setPlaybackSpeed(1.25)
        assertEquals(1.25, prefs.player.playbackSpeed.first(), 0.0)
    }
}
