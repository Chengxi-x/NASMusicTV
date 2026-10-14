package com.nasmusic.tv.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.NasMusicApp
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = NasMusicApp::class)
class VocalPitchSpeedPreviewCommitTest {

    private val app: NasMusicApp get() = ApplicationProvider.getApplicationContext()

    private fun vm() = VocalSeparationViewModel(app, app.playerManager)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun awaitPitchPersisted(expected: Int) = withTimeout(5_000) {
        while (app.appPreferences.player.pitchSemitones.first() != expected) delay(20)
    }

    private suspend fun awaitSpeedPersisted(expected: Double) = withTimeout(5_000) {
        while (app.appPreferences.player.playbackSpeed.first() != expected) delay(20)
    }

    @Test
    fun `preview 改状态但不落盘`() = runBlocking {
        val viewModel = vm()
        assertEquals("初始持久化应为原调", 0, app.appPreferences.player.pitchSemitones.first())

        viewModel.previewPitch(3)

        assertEquals("preview 必须立即更新状态（按钮标签与弹窗档位跟着变）", 3, viewModel.pitchSemitones.value)
        delay(200)
        assertEquals(
            "preview 绝不能写 DataStore（步进一次写一次盘 = 25 次写）",
            0,
            app.appPreferences.player.pitchSemitones.first()
        )
    }

    @Test
    fun `commit 才把当前预览值落盘`() = runBlocking {
        val viewModel = vm()

        viewModel.previewPitch(-5)
        assertEquals(-5, viewModel.pitchSemitones.value)
        viewModel.commitPitch()

        awaitPitchPersisted(-5)
        assertEquals(-5, app.appPreferences.player.pitchSemitones.first())
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
            app.appPreferences.player.playbackSpeed.first(),
            0.0
        )

        viewModel.commitSpeed()
        awaitSpeedPersisted(0.8)
        assertEquals(0.8, app.appPreferences.player.playbackSpeed.first(), 0.0)
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
}
