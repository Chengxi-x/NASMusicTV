# K 歌页升降调 / 变速弹窗：TV 遥控器焦点失效 + 无实时试听（修复方案）

状态：**已开发完成并真机验收通过**（走方案 A + A4-①；返工见 §7.1，复验见 §7.2）
提出：所有者 issue 反馈（2026-10-14）
决策：所有者 2026-10-14 拍板，见 §6
版本段：`v2.38.10`（`versionCode` 178 → 179，`app/build.gradle.kts:47-48`；`CHANGELOG.md` 已开 `[v2.38.10]` 段）
影响面：**仅 TV 端 K 歌页**（`KaraokePlaybackScreen`）；MTV 页明确不改（D3）
关联规范：`docs/conventions-adaptive-ui.md` §5（对话框尺寸）、§10（内容色契约）、§11（焦点视觉分流）

归档（2026-10-14）：✅ 已随 **v2.38.10 / `versionCode 179`** 落地并打附注 tag（指向 `c850d35`），
真机 7 步验收全部通过 ⇒ 按归档规则（已落地 + 命运已定）迁入 `docs/archive/`，移动方式 = **`git mv`**，正文一字未删。
⚠️ **归档 ≠ 权威转移**：本篇仍是本次修复的**唯一方案依据**（§2 根因表 / §3 方案 A / §6 三条决策 /
§7 真机实录 / §7.1 返工 / §7.2 复验 / §7.3 单测隔离），`app/src/**` 的 KDoc 与
`docs/technical-overview.md` §10.232 都指向它。
⏳ 归档时仍未闭合的唯一事项（见 §7.2 末）：**高质量分离（Demucs）模式下 preview 是否即时可闻**，无人验证。

---

## 1. 现象原话与定位澄清

所有者反馈：

> tv 版 MTV 模式，选择调原调、调原速，确定后，遥控器便无法再次选中这 2 个按键；
> 其次，调原调必须确定后才能生效，无法及时预览调整后的音调。

⚠️ **该功能不在 MTV 页（`MvPlaybackScreen`）**。全仓库只有 K 歌页有升降调 / 变速：

| 证据 | 位置 |
|---|---|
| 唯一持有 `showPitchPicker` / `showSpeedPicker` 的文件 | `KaraokePlaybackScreen.kt:155-156` |
| 唯一使用 `karaoke_pitch_label` / `karaoke_speed_label` 的文件 | `KaraokePlaybackScreen.kt:430, 443` |
| `KaraokeStepPickerDialog` 定义与调用 | `KaraokePlaybackScreen.kt:635-829` / `:497, :517` |
| MTV 页底栏只有「歌词 / 换源 / 搜B站」 | `MvPlaybackScreen.kt:430-447`（grep `pitch\|speed\|Picker` 零命中） |

所以修复范围 = **K 歌页**；MTV 页不动。用户说的"调原声 / 原伴唱"是同一行底栏里的
`VocalToggleButton`（`KaraokePlaybackScreen.kt:455-458`），它**不经弹窗**、点击即生效，
之所以"选不中"是焦点问题的**后遗症**（见 §2 F-A），不是它自己的缺陷。

两个症状拆成两条独立问题：
- **问题 1（焦点）**：确定后方向键 / OK 键对底栏按键不再起作用。
- **问题 2（试听）**：`<` / `>` 步进时不发声，必须按「确定」才生效。

---

## 2. 根因清单（按置信度排序，全部有代码证据）

### F-A ⛔ 弹窗是内联覆盖层、不是独立窗口，关闭后焦点无归属 —— 问题 1 的主因

`KaraokeStepPickerDialog` 不是 `androidx.compose.ui.window.Dialog`，而是
`if (showPitchPicker) { Box(Modifier.fillMaxSize()…) }`（`:495-531` + `:658-668`），
且与页面根 `Box`（`:158-169`，唯一挂了 `.focusRequester(screenFocusRequester).focusable()`
的节点）是**兄弟关系**（根 Box 在 `:482` 已闭合，弹窗从 `:494` 起在函数体作用域）。

后果链：

1. 弹窗与页面**共用同一个 `FocusOwner`**（同一个 `AndroidComposeView`）。
2. 点「确定」→ `onConfirm(steps[tempIndex]); onDismiss()`（`:727` / `:807`）→ 整个弹窗子树离开组合。
3. 被移除的正是当前焦点节点，而它的**可聚焦祖先不存在**（兄弟不在父链上）→
   Compose 无回退落点，焦点变成"无归属"。（对照：项目其它 12 处对话框都用真 `Dialog {}`
   独立窗口，主窗口的焦点状态在弹窗期间不受影响，关闭即自然归还 —— 见
   `QualityPickerDialog.kt:34`、`NowPlayingScreen.kt:1181`、`SettingsScreen.kt:1012` 等。）
4. 无任何"归还焦点"代码：`LaunchedEffect(Unit){ screenFocusRequester.requestFocus();
   playPauseFocusRequester?.requestFocus() }`（`:486-492`）key 是 `Unit`，**只在进页时跑一次**；
   弹窗的 `focusRequester` 是 `remember { FocusRequester() }`（`:648`），弹窗销毁即失效。
   全仓库 grep `saveFocus|restoreFocus|focusRestorer|FocusRestoration` → **零命中**。

⇒ 焦点丢失后：按方向键没有任何消费方；按 OK（`DPAD_CENTER`）**穿透到 Activity**，
`MainActivity.onKeyDown`（`:569-589`）→ `MediaKeyHandler`（`:43-55`，`currentScreen == NowPlaying`
时 `playPause()` 并返回 true，K 歌页就挂在 NowPlaying 分支下：`NowPlayingScreen.kt:200-235`）。

📌 **可证伪的真机判据**：复现"确定后按键失效"时，**按一下 OK 键——如果变成播放/暂停切换，
就证明焦点已不在 Compose 树内**（F-A 成立）。这一条必须写进验收步骤（§5）。

### F-B ⛔ 弹窗自己塞了两个"隐形可聚焦节点"，页面根 Box 又是第三个

`Modifier.clickable` 的节点本身可聚焦（`FocusableSurface.kt:146-153` 已把这条写进注释），而弹窗里：

- 全屏遮罩 `Box.clickable(indication = null, onClick = onDismiss)` —— `:662-666`
- 内容 `Column.clickable(indication = null, onClick = {})`（注释写"阻止点击穿透"）—— `:674-678`
- 页面根 `Box.focusable()` —— `:163`

这三个都**没有焦点环、没有缩放、没有任何 `FocusableSurface` 视觉**（焦点可见性全在
`FocusableSurface`/`focusRing()` 里，见 conventions §11「焦点环必须画在 background 之后」）。
焦点一旦被方向键搜索交给它们其中之一，屏幕上**看不到任何高亮**，用户读到的就是
"遥控器整个失灵 / 按键停在原地"。

（几何上全屏 Box 作为方向键候选的概率较低 —— 它的矩形四边都不满足
`candidate.top >= focused.bottom` 之类的方向判据 —— 但 `requestFocus` 失败后的回退、
以及弹窗遮罩与内容的相对位置仍会把焦点交出去。真机验证要区分 F-A 与 F-B，见 §5。）

### F-C ⛔ 弹窗内左右键不步进值，而是把焦点从值节点移走

`<`（`:705-723`）/ 值（`:726-750`）/ `>`（`:753-771`）是三个**并列**的 `FocusableSurface`：

- 步进逻辑只挂在箭头的 `onClick` 上（`:707`、`:755`）；
- 值节点**没有 `onKeyEvent`**，它的 `onClick` 是"确定并关闭"（`:727`）。

⇒ 焦点在值上按 `←` / `→`，走的是 Compose 的方向键焦点搜索（移到箭头 / 越出弹窗），
**不是切档**。所有者"点了调原速以后按上下键还是停在调原速、没法切换到其他选项"的观感，
最贴合的解释就是这一条 + F-B（焦点被看不见的节点吃掉）。

### F-D ⚠️ 上下键在两条 Row 上被无条件吞掉（作者原本有意为之，但和上面叠加成"完全卡死"）

```kotlin
// :699-702  顶行：消费 UP
modifier = Modifier.onPreviewKeyEvent { event ->
    event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown
}
// :781-784  底行：消费 DOWN
```

设计意图（`:631` 注释）是"D-Pad 左右切选项、OK 确认，纵向导航有意关掉"。
但用户的心理模型是**竖着的一列档位列表**（升降调 25 档、变速 16 档），
于是"上下键应该换档"的预期被硬吞掉。这两处 lambda 在修复后应重新定义语义（见 §3）。

### F-E ⛔ 无实时试听：步进只改本地 `tempIndex`，生效只在确认时

```kotlin
var tempIndex by remember { mutableStateOf(selectedIndex) }   // :647
onConfirm = { onSetPitch(it) }                                // :508 → :727 / :807
```

而生效路径本身是**零成本、即时**的：
`VocalSeparationViewModel.setPitchSemitones`（`:213-221`）→ `PlayerManager.setPitch`
（`:307-312`，只是一次 `player.playbackParameters = PlaybackParameters(speed, pitchFactor)` 赋值）。

⇒ 实时试听技术上不需要任何新机制，缺的只是"每步都调用"。**但**：`setPitchSemitones`
每次调用都会 `prefs.player.setPitchSemitones(...)` 写 DataStore（`:217-219`），
16/25 档步进 = 每按一次键落盘一次，必须拆成 preview / commit 两个 API（§3-A3）。

### F-F ❓ 待真机确认，暂不作为结论：API 22 上升降调可能根本不生效

`PlayerManager.setPitch` 依赖 `PlaybackParameters.pitch`，其底层是
`AudioTrack.setPitch`（**API 23+**）。本项目 minSdk 22，创维 5.1.1 开发机即 API 22。
若该机上变调被静默忽略，用户会把"根本没效果"读成"必须确定才生效 / 效果不对"。
验收时必须在创维机上单独测一次「+3 半音是否真的变了音高」，与 F-E 分开判定。

---

## 3. 修复方案

### 方案 A（推荐，最小侵入，保持内联覆盖层）

**A1. 关闭时把焦点显式还给打开它的按钮**

- 在 `KaraokePlaybackScreen` 里为两个设置按钮各持一个 `FocusRequester`
  （`pitchButtonFocus` / `speedButtonFocus`），给 `KaraokeSettingButton`（`:586-626`）
  加一个可选 `focusRequester` 参数透传给内部 `FocusableSurface`（该参数已存在，见 `:120`）。
- 新增 `LaunchedEffect(showPitchPicker, showSpeedPicker)`：两者都为 false 时，
  对"最后一个打开过的"按钮 `requestFocus()`（`try/catch`，与 `:654-656` 同款兜底）。
  ⛔ 不要在 `onDismiss` 回调里同步 `requestFocus()` —— 那一刻弹窗节点还在组合中，
  同帧的焦点清理会把它覆盖掉。
- `KaraokeSettingButton` 点击时记下"打开者"到一个 `var lastOpenedBy`，供上面判断。

**A2. 消灭隐形焦点黑洞**

- 遮罩 `Box`（`:662-666`）与内容 `Column`（`:674-678`）各加
  `Modifier.focusProperties { canFocus = false }`，保留触摸点遮罩关闭的行为；
  或直接把遮罩点击换成 `Modifier.pointerInput { detectTapGestures { onDismiss() } }`
  （`pointerInput` 不产生可聚焦节点）。**内容 Column 那条 `onClick = {}` 防穿透可以整条删掉**
  —— 遮罩是父、内容是子，子节点自己不吃点击时才会落到遮罩，而卡片内部全是自己的 clickable。
- 页面根 `Box.focusable()`（`:163`）**保留**（它是 `screenFocusRequester` 的锚点、
  进页建焦点域依赖它），但补 `focusProperties { canFocusIn = ... }` 之类限制风险大，本轮不动。

**A3. 步进即试听（实时预览）+ BACK 回滚**

- `VocalSeparationViewModel` 拆两套 API：
  - `previewPitch(semitones: Int)` / `previewSpeed(speed: Double)`：只更新
    `_pitchSemitones` / `_playbackSpeed`（让按钮上的值实时跟着变）+ `playerManager.setPitch/setSpeed`，
    **不写 prefs**；
  - `commitPitch()` / `commitSpeed()`：把当前值落盘（沿用现有 `prefs.player.*`）。
  - 现有 `setPitchSemitones` / `setPlaybackSpeed` 语义保持（别处仍在用：`loadPitchSpeedFromPrefs` `:199-210`）。
- 弹窗内箭头 `onClick` 改成：`tempIndex` 变化 → `onPreview(steps[tempIndex])`。
- 「确定」= `onCommit` + 关窗 + 归还焦点；「重置」= `onPreview(原调/原速)` + 直接生效（现状即 `onReset()`，`:787`）。
- **BACK 必须回滚**：打开弹窗时 `remember` 一份 `initialIndex` 快照，`onDismiss` 走 BACK 路径时
  `onPreview(steps[initialIndex])` 复原（否则"没按确定就改了音"更糟）。
  ⚠️ 需要区分"确定关闭"和"BACK/点遮罩关闭"两种 dismiss —— 现 `onDismiss` 是同一个回调
  （`:510` / `:529`），要加一个 `onCancel`，或在弹窗内部用 `confirmed` 标志位。
- ⚠️ 高质量模式（`isHighQualityMode`）下播放的是离线分离产物文件，
  变调/变速是否同样走 `PlaybackParameters` 需实测；若走的是缓存音频路径，
  预览语义要一致（否则"预览没声音"）。

**A4. 方向键语义重定义（治 F-C / F-D）**

✅ **采用 ①（D1 已决策）**，② 已排除：

- **① 值节点自己吃 LEFT/RIGHT 步进**：给值 `FocusableSurface` 包一层
  `Modifier.onKeyEvent { Key.DirectionLeft/Right → 改 tempIndex + onPreview；true }`，
  并把两个箭头**降级为纯装饰**（`focusProperties { canFocus = false }`，只保留触摸点击）。
  这样弹窗内**只剩 1 个焦点节点 + 底行 2 个**，遥控器行为完全可预期：
  `←` `→` 换档、`↑`/`↓` 在「档位」与「重置/确定」之间走、OK 确认。
  ⛔ 顺带必须去掉值节点上那个**条件挂载的 focusRequester**
  （`if (tempIndex == selectedIndex) Modifier.focusRequester(...)`，`:731`）——
  箭头降级后值节点是弹窗唯一的档位焦点，requester 必须**恒定挂载**，
  否则步进时 modifier 从焦点节点上脱离，就是把焦点往外推的同一类隐患。
- ~~**② 改成纵向档位列表**（`LazyColumn` + `focusGroup`）~~ ❌ **本轮排除**（D1）：
  贴合"上下切选项"的心智模型，但要重做弹窗高度/尺寸（conventions §5 `responsiveDialogSize`）
  与 25 档滚动窗口，改动面远大于收益。若 ① 真机仍不满意再单独立项。

- 顶行吞 UP、底行吞 DOWN 这两处（`:699-702`、`:781-784`）在 A2 之后仍然需要 ——
  它们防止焦点越出弹窗；但必须改为"越界时**显式回环**到自己这一行的首/尾"
  （`focusProperties { exit = { ... } }`，项目已有同款写法可抄：
  `SettingsScreen.kt:502-519` 的 `.focusGroup() + focusProperties { exit = ... }`），
  而不是无声吞掉。吞键 + 看不见焦点，才是"卡死"感的来源。

  ⚠️ **真机推翻了这个设想**（见 §7.1）：照抄 `SettingsScreen` 的
  `.focusGroup() + exit = { FocusRequester.Cancel }` 后，`↓` 兜住了、**`↑` 仍越出弹窗**
  ⇒ 落地形态改为**三个节点逐方向显式钉 `up/down/left/right`**，`focusGroup + exit` 只留作后备。

### 方案 B（架构对齐，本轮不推荐）

把弹窗改成真正的 `Dialog {}` 独立窗口（与项目另外 12 处对话框一致）。
焦点归还会**自动**成立（主窗口 `FocusOwner` 不受影响），F-A 一次性消失。

代价与坑（必须知道再选）：
- `DialogBackHandler.kt:53-57` 明确写了：**真 `Dialog {}` 里 Activity 的
  `onBackPressedDispatcher` 收不到按键**，必须改用 `androidx.activity.compose.BackHandler`，
  现有 `RegisterDialogBackHandler(onDismiss)`（`:651`）在 Dialog 内**无效**，
  BACK 会直接穿透到 Level 3「退出确认」。
- 尺寸要按 conventions §5 走 `responsiveDialogSize`；K 歌页虽强制横屏（`:1-5` 豁免说明），
  但 `width(420.dp)` 硬编码（`:671`）本轮**不改**（该页永不竖屏，属 §5 判定口径里的豁免项）。
- Dialog 窗口内的 D-Pad 行为需真机重测（与内联覆盖层不是同一套焦点树）。

**结论：先做 A（4 处小改动、可单测、风险局部），把 B 留作「若 A 真机仍不稳」的后备。**

---

## 4. 改动清单（文件级）

| 文件 | 改动 |
|---|---|
| `ui/components/KaraokePlaybackScreen.kt` | `KaraokeSettingButton` 加 `focusRequester` 参数；两个设置按钮各持 requester；新增 dismiss 后归还焦点的 `LaunchedEffect`；遮罩/内容加 `focusProperties { canFocus = false }`（或改 `pointerInput`）；`KaraokeStepPickerDialog` 加 `onPreview` / `onCancel`、值节点吃 `←`/`→`、箭头降级、`exit` 回环替换裸吞键 |
| `ui/viewmodel/VocalSeparationViewModel.kt` | 新增 `previewPitch` / `previewSpeed` / `commitPitch` / `commitSpeed`（preview 不写 prefs） |
| `ui/screens/NowPlayingScreen.kt` | 回调签名跟着改：`onSetPitch`/`onSetSpeed` → `onPreviewPitch`/`onPreviewSpeed`，并新增 `onCommitPitch`/`onCommitSpeed`（`:153-154` 声明、`:218-219` 透传） |
| `ui/components/branches/NowPlayingBranch.kt` | 装配处改接线（`:143-146`）：preview → `vocalVM.previewPitch/previewSpeed`，commit → `vocalVM.commitPitch/commitSpeed` |
| `docs/technical-overview.md` §10 | 落地并真机验收后补 N.x 小节（含 F-A 的焦点链分析与 F-F 的 API 22 结论） |
| `CHANGELOG.md` | ⚠️ 按既有约定：**仅在验收通过后**写一行 what，不写根因（见 [[feedback-changelog-scope-and-frozen-release]]） |

不动的东西（别"顺手修"）：
- `MvPlaybackScreen.kt`（没有这两个弹窗）；
- 底栏 5 秒 `alpha = 0.15f` 淡出机制（`:141-152`，与 MTV 页对齐是刻意的）；
- `PlayerControls.kt:352-372` 的 `VocalToggleButton`（原唱/伴唱不经弹窗，本来就即时生效）。

---

## 5. 验证手段

**单测（本机能跑，`./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process`）**
- `previewPitch` 不产生 DataStore 写入、`commitPitch` 产生一次（注入 fake prefs）。
- 弹窗快照回滚：抽成纯函数（`initialIndex` + `confirmed` → 该发哪个 preview 值）单测。
- 现有门禁不破：`MediaKeyHandlerTest`（`:93-118` 三条 DPAD_CENTER 路由）、
  `FocusIndicatorContractTest`、`ScreenUiModeCoverageTest`（K 歌页有 `:1-5` 豁免标记，别删）。
- 可选源码扫描门禁（照 `GlobeAssetsHygieneTest` 风格）：断言
  `KaraokePlaybackScreen.kt` 的弹窗遮罩/内容 `clickable` 之后必须紧跟
  `canFocus = false` 或已改 `pointerInput` —— 防后来者再把隐形焦点节点加回去。
  （项目无 `createComposeRule` 依赖，D-Pad 焦点行为**无法在单测里覆盖**，见
  `app/src/test` 全树 grep 零命中。）

**真机（TV `192.168.0.114:5555`，adb 见 AGENTS.md；焦点类缺陷必须真机判）**

修复前先采证（确认根因归属），修复后复跑：

1. 进 K 歌页 → 焦点在播放/暂停（应有青色焦点环）。
2. `→` 走到「升降调」→ OK 打开弹窗 → 高亮应在**当前档位值**上。
3. 弹窗内 `←` / `→`：**应听到音高实时变化**（F-A3 生效）；在创维（API 22）上同时判 F-F ——
   若完全没变化，另开一条 issue，不要混进本次修复。
4. `↓` → 高亮到「重置/确定」行；`↑` → 回到档位行。
5. 「确定」关闭 → **立刻 `←`/`→` 应能看到相邻按钮依次高亮，OK 应能重新打开弹窗**。
   ⛔ 关键判据：此时若按 OK 变成**播放/暂停**，即 F-A 未修好。
6. BACK 关闭（不改档位）→ 音高应**回滚到打开弹窗前**，且焦点同样归位。
7. 反复开关 10 次、并把底栏放到 5 秒淡出（不按键）后再按键 → 焦点不得丢失、
   不得出现"看不见高亮但 OK 有反应"的状态（F-B）。

截图判读按 [[feedback-device-verification-division]]：agent 驱动 adb 与截图判读，
设备侧操作留给所有者。

---

## 6. 决策（所有者 2026-10-14 拍板）

| # | 决策 | 落地含义 |
|---|---|---|
| **D1** | **方向键语义走 A4-①**：左右换档、`<` / `>` 箭头降级为纯装饰（不可聚焦，仅保留触摸点击） | 弹窗内焦点节点收敛为「档位值 + 重置 + 确定」三个；`↑`/`↓` 在两行之间走；A4-② 纵向列表本轮不做 |
| **D2** | **接受"预览即改状态、只有确定才落盘"** | VM 拆 `previewPitch/previewSpeed`（改 StateFlow + PlayerManager，不写 DataStore）与 `commitPitch/commitSpeed`（落盘）；BACK / 点遮罩关闭必须回滚到打开弹窗时的快照值；档位记忆以 commit 为准 |
| **D3** | **MTV 页不改，只改 K 歌页** | `MvPlaybackScreen.kt` 零改动；不动它的底栏与 `alpha` 淡出机制 |

仍未闭环、但不阻塞开发的观察项：
- **F-F**（API 22 创维上 `AudioTrack.setPitch` 是否存在 ⇒ 升降调可能根本不生效）
  留到 §5 真机验收第 3 步判定，若成立则**另立 issue**，不并进本次修复。
- **高质量分离模式**（离线产物文件）下 preview 是否同样即时可闻，同样在真机验收里确认。

---

## 7. 真机验收实录（2026-10-14，v2.38.10 / versionCode 179，`192.168.0.114:5555`，model `9R54_G8S` / API 22）

所有者按 §5 的 7 步逐条走，升降调与变速**两个弹窗都测了**，反馈原话：

> 1. 只有第 4 步出问题：按上键，焦点会跳出弹窗，按下键焦点不会跳出弹窗
> 2. 其他的内容没问题
> 3. 我也同时测试了调速，与升降调的问题一致

| 步 | 结果 |
|---|---|
| 1 进页焦点在播放/暂停 | ✅ |
| 2 `→` 到「升降调」→ OK，高亮在当前档位值 | ✅ |
| 3 弹窗内 `←`/`→` 即时试听 | ✅ **音高实时变化** ⇒ **F-F 不成立**（API 22 上 `AudioTrack.setPitch` 有效），本项闭环，无需另立 issue |
| 4 `↓` / `↑` 在两行之间走 | ❌ **`↑` 把焦点送出弹窗**（`↓` 正常）；变速弹窗同样 |
| 5 「确定」关闭后 `←`/`→` 与重新 OK | ✅ **F-A 已修好**（关键判据「按 OK 变播放/暂停」未出现） |
| 6 BACK 关闭 ⇒ 回滚 + 焦点归位 | ✅ |
| 7 反复开关 10 次 + 底栏淡出后再按键 | ✅（F-B 的"看不见高亮但 OK 有反应"未复现） |

### 7.1 第 4 步的返工（已落地）

A2 里那套「`.focusGroup() + focusProperties { exit = { FocusRequester.Cancel } }`」**只兜住了 DOWN，
UP 仍会越出弹窗**，落到遮罩背后页面里仍然可聚焦的节点上（返回按钮 / 底栏）。
⇒ 不再依赖焦点域的越界回调，改为**弹窗内三个节点逐方向显式钉死**（越界走**回环**）：

| 节点 | up | down | left | right |
|---|---|---|---|---|
| 档位值 | `confirmFocus`（回环到底行） | `resetFocus` | 由 `onKeyEvent` 吃掉（换档），不参与搜索 | 同左 |
| 重置 | `focusRequester`（档位值） | `focusRequester`（回环） | `confirmFocus` | `confirmFocus` |
| 确定 | `focusRequester` | `focusRequester`（回环） | `resetFocus` | `resetFocus` |

`focusGroup + exit = Cancel` **保留为后备**（覆盖未被钉到的路径），但已知它不是本案的充分手段。
门禁 `KaraokePickerTvFocusScanTest` 增一条：钉位数量必须 `up=3 / down=3 / left=2 / right=2`
且三个 requester 都在 —— 少一处就说明某个方向又交回给几何搜索了。

⚠️ 遗留判断（无人证过）：为什么 `exit` 对 UP 不生效？两种可能都**未被排除**——
① Compose 的越界回调只在"搜索离开焦点域"时触发，而 UP 的搜索根本没走域边界（先命中了域外节点）；
② `FocusRequester.Cancel` 在 1.6.0 的这条路径上被降级成"不移动"，键继续交给上层默认搜索。
本工程的取舍是**不去追这个机理**，直接把四个方向钉死（结果可测、不依赖版本内部行为）。
若日后升级到新的 Compose 版本，这条钉位表**不要**被"更优雅的焦点域方案"替换掉，除非真机重跑 §5 第 4 步。

### 7.2 复验（同日，返工版 APK，`lastUpdateTime` 13:03）

所有者在电视上重跑第 4 步（升降调与变速两个弹窗）后判**通过** ⇒ 焦点不再越出弹窗，
`↑`/`↓`/`←`/`→` 四个方向都在「档位值 / 重置 / 确定」三个节点之间走或回环。
**§5 的 7 步至此全部通过**，本 issue 关闭。

唯一仍开放的是 §6 末尾那条观察项：**高质量分离模式**下 preview 是否同样即时可闻（本轮无人测）。

### 7.3 随本次返工一并落地的单测隔离修复（不改运行时行为）

提交前的全量验证红了两条 —— 是本次新加的 `VocalPitchSpeedPreviewCommitTest`「等落盘」用例
**顺序依赖**（同一份代码两次全量一绿一红），与焦点返工无关。
成因与修法（Robolectric 的 dataDir 与 `AppPreferences` 进程单例冲突）详见
`docs/technical-overview.md` §10.232 五。落地形态：
`VocalSeparationViewModel` 增加**带默认值的** `prefs` 构造参数（默认仍取 `NasMusicApp` 单例，
`MainViewModel` 的两参调用不变）⇒ **与已验收通过的 179 包运行行为完全一致，不必重装**。
全量结果：199 类 / 2130 例 / 0 失败，lint 0 error / 136 warning。
