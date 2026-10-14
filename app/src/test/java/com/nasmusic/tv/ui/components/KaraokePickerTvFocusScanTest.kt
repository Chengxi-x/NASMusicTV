package com.nasmusic.tv.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K 歌步进弹窗的 TV 焦点门禁（源码扫描）。
 *
 * 背景见 `docs/archive/karaoke-picker-tv-focus-fix-plan.md`：所有者真机反馈
 * 「升降调/变速确定后遥控器再也选不中这两个按键」「调原调必须确定才生效，没法即时试听」。
 * 决策 D1（左右换档 + 箭头降级）与 D2（preview 即时、commit 才落盘）的落地形态全靠
 * Compose 焦点系统行为，**Robolectric 测不到 D-Pad**（全树无 `createComposeRule` 依赖），
 * 所以这里按本工程既有门禁的做法（`ScreenUiModeCoverageTest` /
 * `FocusableSurfaceColorContractTest` 同路径）锁住源码结构：
 * 一旦有人把隐形焦点节点、条件挂载的 focusRequester 或裸吞方向键改回去，本用例直接红。
 */
class KaraokePickerTvFocusScanTest {

    private val source: String by lazy { karaokeSource() }

    @Test
    fun `弹窗遮罩与内容卡不得成为可聚焦节点`() {
        // 遮罩 Box + 内容 Column + 左右箭头，共 4 处掐掉焦点资格
        val vetoCount = Regex("canFocus = false").findAll(source).count()
        assertTrue(
            "遮罩/内容卡/两个箭头都必须 `focusProperties { canFocus = false }`（F-B），实际只有 $vetoCount 处",
            vetoCount >= 4
        )
    }

    @Test
    fun `不得再有无条件吞掉上下键的预览监听`() {
        // F-D：吞键不让焦点回环，配上无高亮节点就是"卡死"观感；越界改由 focusGroup 的 exit 处理
        for (banned in listOf("Key.DirectionUp", "Key.DirectionDown")) {
            assertEquals(
                "K 歌页不该再吞 $banned（应改用 focusGroup + exit = FocusRequester.Cancel），" +
                    "实际出现 ${Regex(Regex.escape(banned)).findAll(source).count()} 次",
                0,
                Regex(Regex.escape(banned)).findAll(source).count()
            )
        }
    }

    @Test
    fun `弹窗内部必须自成焦点域且越界一律取消`() {
        assertTrue("缺少 `.focusGroup()`：弹窗内部没有焦点域", ".focusGroup()" in source)
        assertTrue(
            "缺少 `exit = { FocusRequester.Cancel }`：方向键可以把焦点送出弹窗",
            "exit = { FocusRequester.Cancel }" in source
        )
    }

    @Test
    fun `弹窗内三个焦点节点必须逐方向钉死目标`() {
        // 真机（v2.38.10 / versionCode 179，所有者 2026-10-14）：上面那组 focusGroup + exit
        // **只兜住了 DOWN，UP 仍把焦点送出弹窗**（落到遮罩背后的页面节点）⇒
        // 上下左右必须由每个节点自己显式指向弹窗内节点，越界走**回环**。
        // 这里锁"钉位还在、且三个节点都钉全"，防止后来者把它退化回单一 focusGroup。
        assertTrue("档位值节点 UP 必须回环到底行", "up = confirmFocus" in source)
        assertTrue("档位值节点 DOWN 必须进底行", "down = resetFocus" in source)
        assertTrue("底行 UP 必须回到档位值", "up = focusRequester" in source)
        assertTrue("底行 DOWN 越界必须回到档位值", "down = focusRequester" in source)
        assertTrue("重置节点必须挂 `.focusRequester(resetFocus)`", ".focusRequester(resetFocus)" in source)
        assertTrue("确定节点必须挂 `.focusRequester(confirmFocus)`", ".focusRequester(confirmFocus)" in source)

        // 三个节点各自的四个方向都不得留空（留空 = 交给几何搜索 = 会外逃）
        for ((dir, expected) in listOf("up" to 3, "down" to 3, "left" to 2, "right" to 2)) {
            val actual = Regex("(?<![A-Za-z_])$dir = [A-Za-z]").findAll(source).count()
            assertEquals(
                "`$dir` 钉位数量应为 $expected（档位值 + 重置 + 确定），实际 $actual —— " +
                    "少一处就意味着那个方向仍会把焦点送出弹窗",
                expected,
                actual
            )
        }
    }

    @Test
    fun `档位值节点必须自己吃左右键做步进`() {
        // F-C / 决策 D1：左右换档，箭头降级为装饰
        assertTrue("档位值节点缺 `.onKeyEvent`", ".onKeyEvent" in source)
        assertTrue("缺 `Key.DirectionLeft` 步进", "Key.DirectionLeft" in source)
        assertTrue("缺 `Key.DirectionRight` 步进", "Key.DirectionRight" in source)
    }

    @Test
    fun `档位值节点的 focusRequester 必须恒定挂载`() {
        // 原实现是 `if (tempIndex == selectedIndex) Modifier.focusRequester(...)`，
        // 步进时该 modifier 会从当前焦点节点上脱离 —— 正是要避免的同类隐患
        assertTrue(
            "focusRequester 不得条件挂载（tempIndex 一变就会从焦点节点上摘掉 modifier）",
            "tempIndex == selectedIndex" !in source
        )
        assertTrue("档位值节点必须挂 `.focusRequester(focusRequester)`", ".focusRequester(focusRequester)" in source)
    }

    @Test
    fun `弹窗关闭后必须显式归还焦点给打开它的设置按钮`() {
        // F-A：内联覆盖层与页面根 Box 是兄弟，节点离开组合后焦点无归属 ⇒ 遥控器全部落空
        assertTrue(
            "缺少关闭后归还焦点的 `LaunchedEffect(showPitchPicker, showSpeedPicker)`（F-A）",
            "LaunchedEffect(showPitchPicker, showSpeedPicker)" in source
        )
        assertTrue("缺少 `pickerReturnFocus`（记录打开弹窗的那个按钮）", "pickerReturnFocus" in source)
    }

    @Test
    fun `步进走 preview 确定走 commit`() {
        // 决策 D2：预览即时试听、只有确定才落盘
        assertTrue("弹窗必须接 `onPreview`", "onPreview = { onPreviewPitch(it) }" in source)
        assertTrue("弹窗必须接 `onCommit`", "onCommit = { onCommitPitch() }" in source)
        assertTrue("变速弹窗必须接 `onPreview`", "onPreview = { onPreviewSpeed(it) }" in source)
        assertTrue("变速弹窗必须接 `onCommit`", "onCommit = { onCommitSpeed() }" in source)
        assertTrue("旧的 `onConfirm` 语义（确定才生效、无试听）必须移除", "onConfirm" !in source)
    }

    @Test
    fun `取消路径必须回滚到打开时的值快照而不是档位索引`() {
        // steps 不含当前值时 indexOf = -1，取整到 0 会让"没改任何东西就按 BACK"跳到 -12 半音
        assertTrue("取消路径必须回滚 `initialValue` 快照", "onPreview(initialValue)" in source)
        assertTrue(
            "不得用 steps[initialIndex] 回滚（-1 取整会跳到首档）",
            "onPreview(steps[initialIndex])" !in source
        )
    }

    /**
     * 定位 `KaraokePlaybackScreen.kt`。
     *
     * AGP 单测的 `user.dir` 是模块目录（`…/app`），同时尝试仓库根与上级；
     * 定位失败**直接失败**而不是跳过 —— 否则这道门禁会被静默禁用。
     */
    private fun karaokeSource(): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/components/KaraokePlaybackScreen.kt"),
            File("app/src/main/java/com/nasmusic/tv/ui/components/KaraokePlaybackScreen.kt"),
            File("../app/src/main/java/com/nasmusic/tv/ui/components/KaraokePlaybackScreen.kt"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "找不到 KaraokePlaybackScreen.kt（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!.readText()
    }
}
