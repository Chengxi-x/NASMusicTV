// NasScreenUiMode-exempt: K 歌页被 MainActivity 的
// `isFullScreenPage = showMv || showKaraoke || showVisualizer` 强制
// `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` —— **永远不在竖屏渲染**，因此不需要 UiMode 分支。
// （勿"顺手"加竖屏适配：横屏版式才是它的唯一形态。）
// 标记由 `ScreenUiModeCoverageTest` 消费，见 docs/conventions-adaptive-ui.md §9。

package com.nasmusic.tv.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import com.nasmusic.tv.R
import com.nasmusic.tv.util.QrCodeGenerator
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.rememberAsyncImagePainter
import com.nasmusic.tv.data.model.Lyrics
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.RegisterDialogBackHandler
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicBrushes
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * KARAOKE 全屏播放页面
 *
 * 布局：
 * - 全屏封面背景（ContentScale.Crop + blur + 暗色渐变遮罩）
 * - 中上部：歌曲名 + 歌手名
 * - 下方：歌词区域（置画面下方，字体放大，置于半透明黑色全宽色块中）
 * - 底部：左上角返回按钮 + 右下角控制栏（上一首/播放暂停/下一首/升降调/变速/原唱伴唱切换）
 *
 * 无进度条控制。原唱/伴唱按钮只切换音频，不退出页面。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun KaraokePlaybackScreen(
    currentSong: Song?,
    isPlaying: Boolean,
    lyrics: Lyrics?,
    coverCandidates: List<String>,
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    /** 当前播放进度（毫秒），驱动歌词定位 —— 必须与播放页传入的同一 progressMs 来源 */
    progressMs: Long,
    /** 歌曲总时长（毫秒），用于底部细进度线 —— 与播放页传入的同一 durationMs 来源 */
    durationMs: Long,
    vocalRemovalEnabled: Boolean,
    onToggleVocalRemoval: () -> Unit,
    onExitKaraoke: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    /** 升降调（半音 -12~+12，0 = 原调） */
    pitchSemitones: Int = 0,
    /** 播放速度（0.5~2.0，1.0 = 原速） */
    playbackSpeed: Double = 1.0,
    /** 预览升降调（即时试听、不落盘）—— 步进弹窗换档时调用 */
    onPreviewPitch: (Int) -> Unit = {},
    /** 提交升降调（弹窗「确定」/落盘） */
    onCommitPitch: () -> Unit = {},
    /** 预览变速（即时试听、不落盘） */
    onPreviewSpeed: (Double) -> Unit = {},
    /** 提交变速（弹窗「确定」/落盘） */
    onCommitSpeed: () -> Unit = {},
    /** 重置升降调回调 */
    onResetPitch: () -> Unit = {},
    /** 重置播放速度回调 */
    onResetSpeed: () -> Unit = {},
    /** 是否为高质量分离模式 */
    isHighQualityMode: Boolean = false,
    /** 高质量分离是否正在进行 */
    isSeparating: Boolean = false,
    /** 高质量分离进度（0f~1f）与阶段描述 */
    separationProgress: Pair<Float, String> = 0f to "",
    /** 高质量分离错误信息（非空时显示红色错误提示） */
    hqError: String? = null,
    /** 清除高质量分离错误 */
    onClearHqError: () -> Unit = {},
    /** 高质量分离成功信息（非空时显示绿色成功提示） */
    hqSuccess: String? = null,
    /** 清除高质量分离成功信息 */
    onClearHqSuccess: () -> Unit = {},
    /** 高质量分离模型是否已下载（未下载时禁用高质量切换） */
    modelDownloaded: Boolean = false,
    /** 切换分离模式回调（快速↔高质量） */
    onToggleSeparationMode: () -> Unit = {},
    playPauseFocusRequester: FocusRequester? = null,
    remoteControlUrl: String? = null
) {
    // ── 二维码自动显隐：5 秒无操作 -> 完全隐藏，任意遥控器按键重新显示 ──
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val screenFocusRequester = remember { FocusRequester() }
    fun activateControls() { lastInteraction = System.currentTimeMillis() }
    LaunchedEffect(lastInteraction) {
        controlsVisible = true
        delay(5000)
        if (System.currentTimeMillis() - lastInteraction >= 5000) controlsVisible = false
    }
    // 控制按钮对齐 MTV 页：5 秒无操作虚化到 0.15，操作/焦点时显化（二维码仍完全隐藏）
    val controlsAlpha = if (controlsVisible) 1f else 0.15f

    // ── 升降调 / 变速选择弹窗显隐 ──
    var showPitchPicker by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }
    // ⛔ 弹窗是内联覆盖层（不是独立 Dialog 窗口），与下面的页面根 Box 是**兄弟**关系，
    // 关闭时弹窗子树整棵离开组合、焦点没有可聚焦祖先可回退 ⇒ 焦点变无归属，
    // 遥控器方向键全部落空、OK 键穿透到 Activity 的 MediaKeyHandler（变成播放/暂停）。
    // 所以必须由这两个 requester 在关闭后把焦点显式还给打开弹窗的那个设置按钮。
    val pitchButtonFocus = remember { FocusRequester() }
    val speedButtonFocus = remember { FocusRequester() }
    var pickerReturnFocus by remember { mutableStateOf<FocusRequester?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .focusRequester(screenFocusRequester)
            .focusable()
            .onFocusChanged { if (it.hasFocus) activateControls() }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) activateControls()
                false
            }
    ) {
        // ── 手机遥控二维码（右上角，5 秒无操作完全隐藏）──
        val qrBitmap = remember(remoteControlUrl) {
            remoteControlUrl?.let { QrCodeGenerator.generateQrBitmap(it, 256) }
        }
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.karaoke_scan_remote),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 必须先于同 Box 中后声明的全屏背景/遮罩绘制，否则会被盖住
                    .zIndex(10f)
                    .padding(24.dp)
                    .size(80.dp)
                    .alpha(if (controlsVisible) 1f else 0f)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            )
        }

        // ── 高质量分离中浮层提示 ──
        if (isSeparating && isHighQualityMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(10f)
                    .background(Color(0xDD000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.karaoke_converting_accompaniment),
                        fontSize = FontSize.small(),
                        fontWeight = FontWeight.Bold,
                        color = NasMusicColors.TextPrimary
                    )
                    if (separationProgress.first > 0f) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${(separationProgress.first * 100).toInt()}% · ${separationProgress.second}",
                            fontSize = FontSize.caption(),
                            color = NasMusicColors.TextSecondary
                        )
                    }
                }
            }
        }

        // ── 高质量分离错误提示（5秒自动消失）──
        if (hqError != null && !isSeparating) {
            val errorKey = hqError
            LaunchedEffect(errorKey) {
                delay(5000L)
                onClearHqError()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(10f)
                    .background(Color(0xDD331111), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hqError,
                    fontSize = FontSize.small(),
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFFF8888)
                )
            }
        }

        // ── 高质量分离成功提示（绿色，5秒自动消失）──
        if (hqSuccess != null && !isSeparating) {
            val successKey = hqSuccess
            LaunchedEffect(successKey) {
                delay(5000L)
                onClearHqSuccess()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .zIndex(10f)
                    .background(Color(0xDD113311), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = hqSuccess,
                    fontSize = FontSize.small(),
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF88FF88)
                )
            }
        }

        // ── 全屏封面背景 ──
        val bgUrl = coverCandidates.firstOrNull() ?: currentSong?.coverUrl
        if (bgUrl != null) {
            val painter = rememberAsyncImagePainter(model = bgUrl)
            Image(
                painter = painter,
                contentDescription = "Karaoke Fullscreen Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (coverFilterEnabled && coverFilterBlurRadius > 0f)
                            Modifier.blur(coverFilterBlurRadius.dp)
                        else Modifier
                    )
            )
        }
        // 暗色渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC0C1222),
                            Color(0x990C1222),
                            Color(0xCC0C1222)
                        )
                    )
                )
        )

        // ── 前景内容 ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 上部：歌曲信息
            Column(
                modifier = Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentSong?.title ?: "",
                    fontSize = FontSize.title(),
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentSong?.artist ?: "",
                    fontSize = FontSize.button(),
                    color = NasMusicColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            Spacer(Modifier.weight(1f))

            // 下部：歌词区域 —— 半透明黑色全宽框，上下保留空间；
            // 框的下沿有一条细进度线（青色→蓝色渐变），颜色/渐变与播放页进度条一致，
            // 但更细（2.dp）、无圆点滑块，仅作整曲进度指示，不参与焦点与 seek。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000), RoundedCornerShape(12.dp))
            ) {
                KaraokeLyricsView(
                    lyrics = lyrics,
                    progressMs = progressMs,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp)
                )
                // 进度细线：背景轨道 + 渐变填充，贴半透明框下沿（下沿上方留 8dp 间距）
                val karaokeProgress = if (durationMs > 0L) {
                    (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .height(2.dp)
                ) {
                    // 轨道底色
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(1.dp))
                            .background(NasMusicColors.SurfaceVariant.copy(alpha = 0.6f))
                    )
                    // 渐变进度填充
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(karaokeProgress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(NasMusicBrushes.progressBar)
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }

        // ── 底部栏：左下角返回 + 右下角控制（对齐 MTV：5 秒无操作虚化，操作/焦点时显化）──
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 20.dp)
                .onFocusChanged { if (it.hasFocus) activateControls() }
                .alpha(controlsAlpha),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮（左下角）
            MiniIconButton(
                onClick = { activateControls(); onExitKaraoke() },
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back"
            )

            // 控制栏（右下角）
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiniIconButton(
                    onClick = { activateControls(); onPrevious() },
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous"
                )
                Spacer(Modifier.width(20.dp))
                MiniIconButton(
                    onClick = { activateControls(); onPlayPause() },
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    primary = true,
                    focusRequester = playPauseFocusRequester
                )
                Spacer(Modifier.width(20.dp))
                MiniIconButton(
                    onClick = { activateControls(); onNext() },
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Next"
                )
                Spacer(Modifier.width(16.dp))

                // ── 升降调按钮 ──
                KaraokeSettingButton(
                    label = stringResource(R.string.karaoke_pitch_label),
                    value = when (pitchSemitones) {
                        0 -> stringResource(R.string.karaoke_original_tune)
                        in 1..12 -> "+${pitchSemitones}"
                        else -> "$pitchSemitones"
                    },
                    isModified = pitchSemitones != 0,
                    focusRequester = pitchButtonFocus,
                    onClick = {
                        activateControls()
                        pickerReturnFocus = pitchButtonFocus
                        showPitchPicker = true
                    }
                )
                Spacer(Modifier.width(10.dp))

                // ── 变速按钮 ──
                KaraokeSettingButton(
                    label = stringResource(R.string.karaoke_speed_label),
                    value = when {
                        playbackSpeed == 1.0 -> stringResource(R.string.karaoke_original_speed)
                        playbackSpeed < 1.0 -> String.format("%.1f", playbackSpeed)
                        else -> String.format("%.1f", playbackSpeed)
                    },
                    isModified = playbackSpeed != 1.0,
                    focusRequester = speedButtonFocus,
                    onClick = {
                        activateControls()
                        pickerReturnFocus = speedButtonFocus
                        showSpeedPicker = true
                    }
                )
                Spacer(Modifier.width(16.dp))

                // 原唱/伴唱切换（只切换音频，不退出页面）
                VocalToggleButton(
                    label = if (vocalRemovalEnabled) stringResource(R.string.karaoke_original_vocal) else stringResource(R.string.karaoke_accompaniment),
                    onClick = { activateControls(); onToggleVocalRemoval() }
                )
                Spacer(Modifier.width(10.dp))

                // ── 分离模式切换（快速/高质量）──
                KaraokeSettingButton(
                    label = stringResource(R.string.karaoke_quality_label),
                    value = when {
                        !modelDownloaded -> "🔒"
                        isSeparating -> stringResource(R.string.karaoke_converting)
                        isHighQualityMode -> stringResource(R.string.karaoke_high_quality)
                        else -> stringResource(R.string.karaoke_fast)
                    },
                    isModified = isHighQualityMode,
                    onClick = {
                        activateControls()
                        // 模型未下载或正在转换中时禁止切换
                        if (isSeparating) return@KaraokeSettingButton
                        if (modelDownloaded || isHighQualityMode) {
                            onToggleSeparationMode()
                        }
                    }
                )
            }
        }
    }

    // 进入 KARAOKE 页面时先建立页面焦点域，再把焦点交给播放/暂停按钮。
    // 页面级预览监听因此能收到内部按钮上的所有遥控器按键。
    LaunchedEffect(Unit) {
        try {
            screenFocusRequester.requestFocus()
            playPauseFocusRequester?.requestFocus()
        } catch (_: Exception) {
        }
    }

    // 步进弹窗关闭后**显式**把焦点还给打开它的那个设置按钮（F-A 修复）。
    // ⛔ 不能在弹窗的 onDismiss 回调里同步 requestFocus() —— 那一刻弹窗节点还在组合中，
    // 同帧的焦点清理会把它盖掉；必须以「两个弹窗都不可见」为 key，在节点移除之后再请求。
    // 首次组合时两个 flag 都是 false，但 pickerReturnFocus 仍为 null ⇒ 不会抢走进页焦点。
    LaunchedEffect(showPitchPicker, showSpeedPicker) {
        if (!showPitchPicker && !showSpeedPicker) {
            try {
                pickerReturnFocus?.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    // ── 升降调选择弹窗 ──
    if (showPitchPicker) {
        val originalTuneLabel = stringResource(R.string.karaoke_original_tune)
        KaraokeStepPickerDialog(
            title = stringResource(R.string.karaoke_pitch_title),
            steps = (-12..12).toList(),
            currentValue = pitchSemitones,
            formatLabel = { semitones ->
                when (semitones) {
                    0 -> originalTuneLabel
                    in 1..12 -> "+${semitones}"
                    else -> "$semitones"
                }
            },
            onPreview = { onPreviewPitch(it) },
            onCommit = { onCommitPitch() },
            onReset = { onResetPitch() },
            onDismiss = { showPitchPicker = false }
        )
    }

    // ── 变速选择弹窗 ──
    if (showSpeedPicker) {
        val originalSpeedLabel = stringResource(R.string.karaoke_original_speed)
        KaraokeStepPickerDialog(
            title = stringResource(R.string.karaoke_speed_title),
            steps = listOf(0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0),
            currentValue = playbackSpeed,
            formatLabel = { speed ->
                when (speed) {
                    1.0 -> originalSpeedLabel
                    else -> String.format("%.1f", speed)
                }
            },
            onPreview = { onPreviewSpeed(it) },
            onCommit = { onCommitSpeed() },
            onReset = { onResetSpeed() },
            onDismiss = { showSpeedPicker = false }
        )
    }
}

/**
 * 简易图标按钮（用于 KARAOKE 控制栏）
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MiniIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    val buttonSize = if (primary) 72.dp else 56.dp
    val iconSize = if (primary) 36.dp else 28.dp

    FocusableSurface(
        onClick = onClick,
        modifier = Modifier
            .size(buttonSize)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(50),
        focusedScale = 1.12f,
        animationDurationMs = 200,
        containerColor = if (primary) NasMusicColors.Primary else NasMusicColors.Surface,
        // 主按钮聚焦时整体变亮；普通按钮 Surface → 青色 0.3f
        focusedContainerColor = if (primary) NasMusicColors.PrimaryBright else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = if (primary) NasMusicColors.TextPrimary else NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f,
        focusBorderColor = NasMusicColors.FocusRing
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = NasMusicColors.TextPrimary,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

/**
 * K 歌页设置按钮（升降调 / 变速）
 *
 * 显示 标签 + 当前值，修改后高亮提示，点击弹出步进选择弹窗。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun KaraokeSettingButton(
    label: String,
    value: String,
    isModified: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    FocusableSurface(
        onClick = onClick,
        focusRequester = focusRequester,
        modifier = Modifier.size(width = 84.dp, height = 56.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.08f,
        animationDurationMs = 200,
        containerColor = if (isModified) NasMusicColors.Primary.copy(alpha = 0.25f) else NasMusicColors.Surface,
        focusedContainerColor = if (isModified) NasMusicColors.Primary.copy(alpha = 0.4f) else NasMusicColors.Primary.copy(alpha = 0.3f),
        contentColor = NasMusicColors.TextPrimary,
        focusedContentColor = NasMusicColors.TextPrimary,
        pressedScale = 0.92f,
        focusBorderColor = NasMusicColors.FocusRing
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                fontSize = FontSize.caption(),
                fontWeight = FontWeight.Bold,
                color = if (isModified) NasMusicColors.Primary else LocalFocusableContentColor.current
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = FontSize.small(),
                fontWeight = FontWeight.Bold,
                color = NasMusicColors.TextPrimary,
                maxLines = 1
            )
        }
    }
}

/**
 * K 歌步进选择弹窗（升降调 / 变速通用）
 *
 * TV 遥控器适配（决策 D1）：
 * - `←` / `→` 在**档位值节点上**直接换档并即时试听（左右箭头降级为纯装饰，不可聚焦）
 * - `↑` / `↓` 在「档位值」与「重置 / 确定」之间走
 * - OK 确认 ⇒ 落盘；BACK / 点遮罩 ⇒ 回滚到打开弹窗时的档位（不落盘）
 *
 * ⚠️ 本弹窗是**内联覆盖层**、不是独立 `Dialog` 窗口，与调用方的页面根 Box 是兄弟关系，
 * 所以关闭后的焦点归还由外层 `LaunchedEffect(showPitchPicker, showSpeedPicker)` 负责（F-A）。
 */
@OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
private fun <T> KaraokeStepPickerDialog(
    title: String,
    steps: List<T>,
    currentValue: T,
    formatLabel: (T) -> String,
    onPreview: (T) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    // 打开时的**值**快照：取消路径（BACK / 点遮罩）按它回滚。
    // ⛔ 不能回滚到 steps[initialIndex] —— currentValue 不在 steps 里时
    // indexOf 返回 -1（手机遥控网页/历史数据可能给出 1.05 这类值），
    // 取整到 0 会让"什么都没改就按 BACK"变成直接跳到 -12 半音 / 0.5 倍速。
    val initialValue = remember { currentValue }
    val initialIndex = remember { steps.indexOf(currentValue).coerceAtLeast(0) }
    var tempIndex by remember { mutableStateOf(initialIndex) }
    // true = 关闭时保留当前预览值（确定 / 重置）；false = 取消路径，需要回滚
    var keepValueOnDismiss by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun stepTo(index: Int) {
        val next = index.coerceIn(steps.indices)
        if (next == tempIndex) return
        tempIndex = next
        onPreview(steps[next])
    }

    fun commitAndClose() {
        keepValueOnDismiss = true
        onCommit()
        onDismiss()
    }

    fun cancelAndClose() {
        if (!keepValueOnDismiss) onPreview(initialValue)
        onDismiss()
    }

    // 修复：注册 BACK 键回调，使 BACK 关闭本弹窗而非穿透到应用退出确认。
    // 取消路径必须先回滚预览值再关窗，否则"没按确定却改了音"。
    RegisterDialogBackHandler { cancelAndClose() }

    // 弹窗打开时自动聚焦到档位值节点
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    // ⛔ 遮罩与内容卡的 `clickable` 各自都会创建一个**可聚焦、却没有任何焦点视觉**的节点
    // （同类教训见 FocusableSurface.kt 的粘滞焦点注释）：方向键一旦落到它们身上，
    // 屏幕上看不到高亮、OK 又只做 dismiss，用户读到的就是"遥控器整个失灵"（F-B）。
    // 触摸点遮罩关闭的行为要保留，所以只掐掉焦点资格，不删 clickable。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusProperties { canFocus = false }
            .background(Color(0x80000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { cancelAndClose() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .padding(24.dp)
                .background(NasMusicColors.Background, RoundedCornerShape(16.dp))
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // 阻止点击穿透到遮罩层
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TV 焦点域：弹窗内部自成一组，任何方向想越出这一组都**取消移动**
            // （FocusRequester.Cancel），焦点始终留在弹窗里看得见的地方。
            // 替代了原先"顶行吞 UP / 底行吞 DOWN"两处无条件吞键（F-D）——
            // 吞键本身不让焦点回环，配上无高亮的节点就是"卡死"观感。
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .focusGroup()
                    .focusProperties {
                        exit = { FocusRequester.Cancel }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Text(
                    text = title,
                    fontSize = FontSize.button(),
                    fontWeight = FontWeight.Bold,
                    color = NasMusicColors.TextPrimary
                )
                Spacer(Modifier.height(20.dp))

                // 选项区域：左右箭头（TV 上不可聚焦的装饰，仅触摸可点）+ 当前档位值
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)
                ) {
                    // 左箭头
                    FocusableSurface(
                        onClick = { stepTo(tempIndex - 1) },
                        modifier = Modifier
                            .size(48.dp)
                            .focusProperties { canFocus = false },
                        shape = RoundedCornerShape(50),
                        focusedScale = 1.1f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.9f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = "<", fontSize = FontSize.subtitle(), fontWeight = FontWeight.Bold, color = NasMusicColors.TextPrimary)
                        }
                    }

                    // 当前档位值：TV 上档位行唯一的焦点节点，
                    // `←` / `→` 在这里直接换档并即时试听（原实现把左右键让给了箭头焦点搜索，
                    // 于是按左右只会"焦点跑掉"，这就是所有者反馈的"切不到其他选项"，F-C）
                    FocusableSurface(
                        onClick = { commitAndClose() },
                        modifier = Modifier
                            .width(200.dp)
                            .height(56.dp)
                            .focusRequester(focusRequester)
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> { stepTo(tempIndex - 1); true }
                                    Key.DirectionRight -> { stepTo(tempIndex + 1); true }
                                    else -> false
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Primary.copy(alpha = 0.2f),
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.4f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = formatLabel(steps[tempIndex]),
                                fontSize = FontSize.subtitle(),
                                fontWeight = FontWeight.Bold,
                                color = NasMusicColors.TextPrimary
                            )
                        }
                    }

                    // 右箭头
                    FocusableSurface(
                        onClick = { stepTo(tempIndex + 1) },
                        modifier = Modifier
                            .size(48.dp)
                            .focusProperties { canFocus = false },
                        shape = RoundedCornerShape(50),
                        focusedScale = 1.1f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.9f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = ">", fontSize = FontSize.subtitle(), fontWeight = FontWeight.Bold, color = NasMusicColors.TextPrimary)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 重置 + 确定按钮（越出弹窗的焦点由上面 Column 的 focusGroup + exit 兜住）
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FocusableSurface(
                        // 重置是明确动作：立即生效并落盘（resetPitch/resetSpeed 自带持久化）
                        onClick = { keepValueOnDismiss = true; onReset(); onDismiss() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.05f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Surface,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.3f),
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = stringResource(R.string.karaoke_reset), fontSize = FontSize.small(), fontWeight = FontWeight.Bold, color = LocalFocusableContentColor.current)
                        }
                    }

                    FocusableSurface(
                        onClick = { commitAndClose() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        focusedScale = 1.05f,
                        animationDurationMs = 200,
                        containerColor = NasMusicColors.Primary,
                        focusedContainerColor = NasMusicColors.Primary,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContentColor = NasMusicColors.TextPrimary,
                        pressedScale = 0.95f,
                        focusBorderColor = NasMusicColors.FocusRing
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(text = stringResource(R.string.karaoke_confirm), fontSize = FontSize.small(), fontWeight = FontWeight.Bold, color = LocalFocusableContentColor.current)
                        }
                    }
                }
            }
        }
    }
}