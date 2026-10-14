# deskworlds 视觉效果移植可行性分析（候选 E45）

> **状态**：❌ **已放弃（所有者 2026-10-10 裁决「放弃此项工作，文档归档」）** —— E45 候选**不再推进**，
> 本文自本日起仅作历史追溯与决策依据，**不再维护**。
> 原文：可行性分析，**未开发**。所有者 2026-10-10 提出「这些展示效果能否移植到本项目作为频谱效果」，
> 本文给出**分设备、分路线**的裁决与依据。⛔ 本文不含实现承诺，第 9 节的门禁清单推进前需逐条勾选。
> ⚠️ **归档时第 10 节 D1–D6 六条待决策项全部未获裁决**（放弃本身就是对 D1 的否定回答）——
> 逐条留痕见 `docs/archive/README.md` 第七轮。恢复推进的唯一入口是那份登记 + 本文 §0.1 术语口径。
> 被测对象：`D:\hxzhang\MyGithubSoftware\NasAudio\deskworlds-main`（chaseleantj/deskworlds v1.1.0，MIT）。
> **本文版本 v1.3**（变更经过见第 12 节；用词定义见 §0.1，该节是唯一术语口径）。

---

## 0. 先回答两个问题（大白话）

> **读这一节不需要任何前置知识。** 全文用词以 §0.1 为准；若某处措辞与该节定义冲突，
> **以真实代码为准**，并把冲突记进第 10 节待决策项（D#），⛔ 不要顺着某一种理解偷偷改写。
> 第 12 节版本历史只记变更经过，**不作术语定义**。

### 0.1 术语口径（全文只认这几个意思）

规则：**一个词有两种理解时，不因此造第二个东西** —— 要么统一到本文里的这一个含义，
要么每次出现都写清用的是哪一种。下表就是本文唯一认下的含义。

| 词 | 本文只认这一个意思 | ⛔ 本文不指 |
|---|---|---|
| **频谱效果** | 画面会跟着音乐变化的可视化主题（数据来自 `AudioFrame`），**不要求画成一根根直条** | 传统「频谱条 / 电平表」那种字面条形 |
| **频谱显示件** | 屏幕上**看得见的那个画频谱的图形形态**（条 / 环 / 点 / 弧 / 折线） | 代码模块、类、库、组件包 |
| **对标** | 一个**盘点动作**：动手前把双方已有的同类形态查清楚，免得重复造或误以为能白拿 | 一个新东西、新模块、待建清单、评分表 |
| **世界 / 场景** | deskworlds 里一个独立动画（plasmascape 等 6 个），全文统一称**「世界」** | — |
| **移植** | 把它的 JS 场景**原样**搬进本项目 WebView 里跑（第 5 节路线 A） | 改用 Kotlin/GLES 重写（那叫「重写」，路线 B/C） |
| **画质档** | 两套**不同源**的档位，本文每次出现都标明是哪一套：① 本项目 HIGH/MEDIUM/LOW；② deskworlds eco/balanced/detail/native | 二者互相对等 |
| **行为层** | 世界里**纯计算**的部分（不含 GPU 绘制），可在 Node 里直跑 | 整个世界的性能 |
| **闸门 / 墙** | 一条**独立的阻断条件**（第 3 节三道） | 性能问题（慢不等于阻断） |
| **接线 / 映射表** | 一份「我方音乐信号 → 世界参数」的**纯函数模块**（§2.4） | 物理连线、JS↔Kotlin 通道本身 |

### 0.2 问题一：这些展示效果能不能移植进来当频谱效果？

大白话，四句：

1. **它现在不是频谱效果**。这六个世界（等离子球、篝火、锦鲤池、斗鱼、河床、珊瑚礁）是纯环境动画，
   整份代码里**没有一行跟声音有关**——它们只看时间、鼠标和点击，不听音乐。
2. **想让它跟音乐动，得给它"接线"**：把我们已有的音乐信号（响度、低音、中高音、鼓点、64 条频谱）
   在合适的位置送进去。这一步不难，E41 的地球已经有一条一模一样的通道可照抄。
3. **能不能跑起来完全取决于设备**。新手机、新盒子（内置浏览器够新）✅ 能；
   我们当基准的那台老创维电视 ❌ 不能——三道独立的墙，任何一道都无解，
   而且最深的那道墙跟图形新不新**没关系**，是应用自己的合成方式，且所有者已裁决不修（第 3 节）。
4. **代价**：能跑的设备上只多 **0.41 MB**（18 个文件），跟 E41 一样只在最高画质档出现。

一句话：**在新设备上是「搬运 + 一张映射表」，在基线老电视上是「这条路不通」。**

### 0.3 问题二：「频谱显示件对标」到底是干什么的？

大白话：**搬家前先打开两边橱柜，看清已经有什么盘子。**

因为当时有两个具体的担心，不查清就没法估工作量：

- **它那边**有没有现成的频谱条（或环、点阵）可以直接搬来用？
- **我们这边**有没有现成的频谱条可以直接套在新世界上？

只要有一边有，活就轻；查完是**两边都没有**（§2.3 逐处直读）。这个结果直接把方案性质改了（§2.4）：
要补的不是一件"看得见画频谱的零件"，而是一条"接线"——把音乐映射成世界行为的表
（本项目已有两份现成范本：海边 `SeasideAudioMap` 把 64 条频谱映成**浪高与破碎密度**，
明月 `MoonAudio` 把低音映成**月晕半径与海面亮度** —— ⚠️ 后者**故意不读 `frame.spectrum`**，
只用线性通道，理由见 §2.2 那条 gamma 红线）。

它同时挡住了两种具体的浪费：

1. **误以为能白拿**：设计建在不存在的现成件上，写到一半才发现要自己造。
2. **凭空造重复件**：既然"缺频谱条"，就顺手新画一套满高频谱条叠在世界前面——
   而本项目既有主题的做法全是**把频谱变成场景行为**，从没画过满高直条（§2.3 逐处直读），这套条**没人要过**。
   真要字面意义的条，那是产品取向决策，已登记为 **D6**，不在本文擅自定。

⚠️ 这一步还有一个防错作用：本文初稿曾凭转述写进三个**并不存在**的"现成件"，
正是靠这一步逐处开文件直读才抓出来（已更正并记入第 12 节 v1.1）。

### 0.4 裁决表（细节依据在后文）

**一句话**：**能移植，但它现在不是频谱效果，而且缺得不只是"没接音频"** —— 六个世界里**一行音频代码都没有**，
也**一个频谱显示件都没有**（条/环/点阵全需自绘，见 §2.3）；能否落地**完全取决于设备**，
基线电视上这条路被三道独立闸门堵死。

| 问题 | 裁决 | 决定性依据 |
|---|---|---|
| 是「频谱效果」吗？ | ❌ **不是**。纯环境动画，驱动只有时间/指针/点击/遮挡 | 全仓库 grep `AudioContext\|AnalyserNode\|getUserMedia\|decodeAudioData` → **0 命中**（第 2 节） |
| 它能直接当频谱效果用吗？ | ❌ **连频谱显示件都没有**：六个世界里没有任何条/环/点阵形态 | §2.3 逐处直读；但 ⭐ 本项目自己也**从不画满高频谱条**（最"直"的只有 `BeatFirework` 14% 屏高底纹），所以缺的要补是**映射表**不是条带（§2.4） |
| 手机 / 现代 WebView 设备能否移植？ | ✅ 能，且**成本可控**（首个场景 10 个 JS 文件、APK 约 **+0.41 MB**） | WebView 只要 ≥ Chrome 93 且有 WebGL2；本项目 E41 已跑通同类链路 |
| 基线创维电视（Android 5.1.1 / API 22 / WebView = Chrome 39）能否移植？ | ❌ **不能**，三重独立阻断，任一都无解 | 第 3 节闸门①②③；闸门③与 WebGL 无关且**所有者已裁决不修** |
| 推荐路线 | 路线 A（WebView 原样移植）+ **仅最高画质档提供**，与 E41 同一门控 | 第 5、9 节 |
| 首个移植对象 | `plasmascape`（等离子球）：零外部资产、体量最小、天然吃振幅 | 第 4 节矩阵 |
| 永不移植 | `reefscape`（15.34 MB 资产 + 3 pass + 4 平行光 + 10+10 采样 AO） | 第 4、6 节 |

⚠️ **第 7 节有一条明确的「未测」缺口**：GPU 侧成本（bloom 金字塔 / 4× MSAA / 每帧重渲阴影）本机**没有测到**
—— 浏览器面板不可见导致场景在 `document.hidden` 处自挂起。已测的只有行为层 CPU 成本。
⛔ **不许从第 7 节那两个毫秒数外推帧率**，那正是本项目历史上「探针典型值当最坏帧用」的同类错误（偏差 D31/D34）。

---

## 1. 被移植对象事实卡（逐条核过源码）

| 项 | 实测值 | 出处 |
|---|---|---|
| 许可 | **MIT**（Copyright 2026 Chase Lean）＋ Three.js 本体 MIT | `LICENSE`、`vendor/THREE-LICENSE.txt` —— 与本项目 GPL v3 **不冲突**，可直接引入 |
| 形态 | 6 个 3D 世界 + macOS 壁纸宿主（Swift）+ 浏览器 gallery | `wallpaper/Wallpaper.swift` 26,533 B（本项目**不需要**，纯宿主） |
| 渲染库 | **three.js r180**，拆成 `three.core.js` 1,403,455 B + `three.module.js` 603,113 B | `vendor/three.core.js` `REVISION = '180'` |
| three 扩展 | ⛔ **零 addons**：不用 OrbitControls / EffectComposer / GLTFLoader / Line2，后处理是**自己 25 行手搓** | `scenes/shared/postprocessing.js:1-25` |
| 模块化 | 真 ES module + **import map**（12 份入口文档各一份，只映射 `three`） | `scenes/*/index.html:11-20`、`scenes/*/wallpaper.html:11-18` |
| 外部资产 | 只有 reefscape 有（5 个文件 / 15.34 MB）；**其余 5 个世界 100% 程序化生成** | `scenes/reefscape/assets/`，其余场景 grep `fetch` 为空 |
| 音频 | ⛔ **零**（见第 2 节） | 全树 grep |
| 场景骨架 | 共享层 7 文件 + 每场景 `main.js`/`render.js`/`shaders.js`/行为文件 | 第 4 节逐场景行数 |
| Node 可测性 | ✅ 行为层与渲染**干净可分**，5 套纯逻辑测试在 Node 直跑通过 | 实测：`plasma ok` / `bonfire fire: ok` / `koiscape behaviour: ok` / `betta food ok` / render-policy 全绿 |

⭐ 最后一行是本次最有价值的结构性发现：它**正好对上本项目的既有约定** ——
浏览器原型用 `vm` 沙箱桩 DOM/canvas、驱动真实 rAF 来出基线（`moonlit_cloud_golden.js` 那套）。
deskworlds 的 `frame-loop.js` 把 `requestFrame / delay / clock` 当参数收（`:8-13`），是**为可测性设计过的**。

---

## 2. 前提澄清：它没有音频，我们有什么可以喂给它

### 2.1 deskworlds 每帧真实消费的输入（全 6 场景一致）

| 通道 | 说明 |
|---|---|
| `dt` / `time` | 驱动 shader 里的 `uTime`/`waterTime`/`reefTime` |
| 指针位置 **+ 速度** | 2D 归一化坐标 + 速度向量，带 `lerp` 衰减（`riverscape/main.js:226-249,313`） |
| 点击 | 投饵 `food.drop()` / 搅火 `stir()` / 触屏放电 `setFinger()` |
| 宿主注入 | `window.sceneRate(fps)`、`scenePause`、`scenePower`、`sceneFeed` —— **已存在的外部数字入口** |
| 遮挡 / 省电 | `visibilitychange`、`prefers-reduced-motion`、`navigator.getBattery` |

⚠️ **不要拿 `window.sceneRate()` 塞振幅**。它的语义是"宿主要求的帧率上限"，会被 `frame-loop.js:24-30`
拿去算 `setTimeout` 提前量 —— 喂进 0..1 的音量就等于把场景节流到 1 fps。这是最容易顺手犯的错。
✅ 正确做法是**新增一个契约函数**（如 `window.DeskWorld.setAudio(payload)`），完全照 E41 已有的形态走：
Kotlin 侧 100 ms tick + EMA 平滑 + `evaluateJavascript`，JS 侧自行插值到渲染帧率
（`WorldGlobeRenderer.kt:56` 的 `setAudio({energy,bass,mid,treble,beat})` 就是现成模板，`:499` 发送点）。

### 2.2 我们这侧能给出的信号（实测自源码，非文档转述）

链路是**自研 FFT，不依赖 `android.media.audiofx.Visualizer`**（该通道连同降级仲裁已于 v2.37.6 移除）：

```
ExoPlayer DefaultAudioSink → PcmTapProcessor → PcmRingBuffer(8192≈186ms)
  → PcmSpectrumTap（独立 HandlerThread，40 ms = 25 Hz，FFT_SIZE 1024，Hann 窗）
  → Radix2Fft.magnitude → 512 bin
  → SpectrumAnalyzer：感知扭曲 → 64 条柱 + bass/mid/treble/energy + beat/pulse/bpm
  → AudioFrame（双缓冲，主线程 withFrameNanos 读）
```

映射建议（这是移植方案里**唯一真正的设计工作**，其余都是搬运）：

| 我方信号 | 落到 deskworlds 的哪里 | 观感 |
|---|---|---|
| `energy` | 电弧总亮度 / 火焰高度 / 鱼群巡航速度 | 整体随响度呼吸 |
| `bass` | 分支概率 `KINK`、火花 `stir()` 强度 | 鼓点"劈"出更多电弧 |
| `mid` / `treble` | 辉光色相偏移（⚠️ 只作**有界微偏**） | 亮部冷↔暖 |
| `beat` | 一次性事件：投饵 / 搅火 / 投掷涟漪 | 踩点的脉冲 |
| `spectrum[64]` | ⚠️ **不是接线、是改语义**：`plasmascape/src/shaders.js:9-14` 的 `vec3 spectrum(float s)` 里 `s` 是**沿电弧的长度位置**（0=电极 → 1=玻璃球），不是频率轴。借它做「频率→色相」要新写一条以 bin 索引为自变量的色尺 | 全频谱→全色相，是最接近"频谱效果"的落点，但**本项目并无现成满高条带可抄**（§2.3 已排除），要不要画见 §2.4 |
| `waveform[128]` | ⛔ **不能用** —— 该数组在 `SpectrumAnalyzer.kt:65`（声明）+ `:144,283,310`（`waveBuf.fill(0f)`）恒为 0，全仓库无渲染器读取 | 接上去就是一条死直线 |

⚠️ 我方 64 条柱是 **gamma `^0.75` 后的显示通道**（`:262`，`displayBuf[bar] = disp.pow(DISPLAY_GAMMA)`），
给 GLSL 做色相/高度映射时**别指望它是线性幅度**；需要线性就得同时传 `linearBuf` 那一路。
这属于细节，但正是 E44 那轮"探针典型值 ≠ 生产最坏帧"教训的位置。

⚠️ 还有一条更容易踩的：**渲染器实际画的柱数不等于 64**。它读的是 `ctx.quality.barCount`
（`AppSettings.kt:377-379`：**HIGH 64 / MEDIUM 64 / LOW 32**），而 `frame.spectrum` 恒 64
（`SpectrumContract.kt:12`）—— 现有渲染器都自己做了重采样（`AdvancedRenderers.kt:615` 那种
`(i * spectrum.size / n)` 口径）。⛔ 移植件若按 64 硬排布，LOW 档就会画错，与全 App 行为不一致。

### 2.3 频谱**显示件对标**：本项目现有形态（逐处直读源码），deskworlds 一件都没有

先看本项目 22 个渲染器**真实**把频谱画成什么。⭐ 关键发现：**没有任何一个是"传统直上直下满高频谱条"**，
全部是抽象映射 —— 这条事实直接改变了移植方案的性质（见本节末）。

| 映射形态 | 现有代表（逐处直读） | 具体式子 |
|---|---|---|
| **径向向外条（环）** | `CircularRingRenderer`（`BasicRenderers.kt:90-96`）、`MilkdropRenderer`（`UltraRenderers.kt:189-195`） | `len = VisualizerMath.barHeight(v, maxLen, 3f)` → `polar(cx, cy, rStart + len, a)`；一根 bin = 一根径向条。圆柱感靠沿切向错开的暗侧/亮侧两笔（`BasicRenderers.kt:104-127`） |
| **底边频谱底纹**（全仓库**唯一**的线性垂直条） | `BeatFireworkRenderer`（`ParticleRenderers.kt:205-217`） | `addRect(x0, h - bh, x0 + slot*0.6f, h)`，`bh = v*h*0.14f` —— ⚠️ **只占屏高 14%**，定位是**底纹**不是主角；合批成单 Path ⇒ 1 次 `drawPath` |
| **半径映射** | `LiquidGridRenderer`（`AdvancedRenderers.kt:207-211`）、`MoleculeRenderer`（`:384` `bandRange(i, atomCount, spectrum.size)` + `bandMean`） | `r = (2.6f + v*4f).coerceAtLeast(1.2f)` —— 频谱驱动网格顶点光斑半径 |
| **速度映射** | `MatrixRainRenderer`（`AdvancedRenderers.kt:615-617`） | 重采样 `(i * spectrum.size / n)` → `speed = colSpeed[i] * (0.5f + v*2.5f)`，列下落速度 |
| **位置 + 尺寸映射** | `ConstellationRenderer`（`AdvancedRenderers.kt:962-967`） | `y = h*0.12f + (1-v)*h*0.76f`、`size = 2.2f + v*5f`；⚠️ 取 bin 用 `(head*7 + it*13) % size`，是**伪随机取样**，不是顺序映射 |
| **弧长/扫线** | `StarrySkyRenderer.drawSegments`（`StarrySkyRenderer.kt:592-605`） | ⛔ **恒读 `frame.spectrum.size`**（LOW 32 同样成立）+ `RING_BANDS` 上限 + LOW 档 `LOW_ARC_STRIDE` **隔柱取样** + `envelopeStep` 包络，映成 `sweepForAmp(cur)` 弧长。⚠️ `:592` 注释写明**有门禁按 (frame, fx) 切这个函数体来断言"恒读 spectrum.size"** |
| **目标池 / 整体微缩** | `RadarGridRenderer`（`BatchThreeRenderers.kt:131,146`） | `stepTargets(..., frame.energy, frame.spectrum, ...)`；低音让网格半径整体微缩 `maxR * (1 - bassSmooth*0.06f)` |
| **映成自然现象** | `SeasideAudioMap`（`SeasideAudioMap.kt:441` `update(dt, spectrum, bassRaw, beat)`） | 频谱 → 浪高与破碎密度；⚠️ 而 `MoonlitRenderer` 的律动层 **⛔ 明确不读 `frame.spectrum`**（`MoonAudio.kt:19`：gamma 后的频谱不驱律动，所有者裁决） |

⛔ **不存在的形态**（都是本次直读排除的，别再以为有现成件）：

- **左右镜像双条**：全仓库无此形态。
- **满高频谱条**：最接近的只有上面那条 14% 屏高底纹。
- **波形折线 / 点状波形**：⛔ 唯一画 `line` 型曲线的是卡拉 OK 音高对比（`KaraokePlaybackScreen` /
  `PlayerScreen`），**与 `AudioFrame.waveform` 无关**（那条数组恒 0，见 §2.2）。

⭐ **可复用的真件**（存在且可跨渲染器调用，已逐个开文件核过）：
`visualizer/VisualizerMath.kt` —— `barHeight(v, maxH, minH) = minH + v*(maxH-minH)`（`:40-41`）、
`polar(cx, cy, radius, angleRad)`（`:56`）、`hueGradient`（`:137`）；以及 `visualizer/PeakHoldTracker.kt`
（峰值保持，`CircularRingRenderer` 的峰值线 `BasicRenderers.kt:139` 就是它的消费方）。

### 2.4 这条对标改变了移植方案的性质

"deskworlds 没有频谱条"在**本项目语境下不算缺陷** —— 因为本项目自己的取向就是
**把频谱映射成场景行为**（Seaside 的浪、StarrySky 的弧、Constellation 的星点位置），
而不是画直条。⛔ 所以移植方案真正要补的不是"显示件"，而是：

1. **一张 audio → 场景参数的映射表**（§2.2 那张，但必须像 Seaside/Moonlit 那样**写成纯函数模块 + 配门禁**，
   不能散在渲染器里）。
2. **口径纪律**：柱数恒读 `frame.spectrum.size` / `ctx.quality.barCount`（LOW 32），⛔ 不硬编码 64
   （StarrySky 已有同类门禁可作模板）。
3. **显示通道的 gamma 意识**：`frame.spectrum` 是 `^0.75` 后的显示值；Moonlit 干脆拒用它驱律动
   （`MoonAudio.kt:19`）—— 移植件若要"看得见的律动"，得明确选线性通道还是显示通道。

如果所有者确实想要**字面意义的频谱条**叠在世界前面，那属于**新造显示件**（本项目从未有过满高条），
自绘路径三选一：① 同页另一张 Canvas 2D overlay（✅ 推荐，与 3D 解耦）② three 实例化 quad + uniform 数组
（观感最好但要改 shader）③ 原生 Compose 叠在 `AndroidView` 之上（⚠️ E41 的宿主墙就出在 Compose 合成层）。

---

## 3. 可行性闸门：基线电视上的三道独立阻断

三道闸门**互相独立**，破一道仍被下一道挡。逐条给证据。

### 闸门① three r180 在构造上就拒绝 WebGL1

`vendor/three.module.js:15205`：

```js
const contextName = 'webgl2';
_gl = getContext( contextName, contextAttributes );
```

全文件 grep `'webgl'`（WebGL1 的 context 名）→ **0 命中**，失败即 `:15213` 抛
`Error('Error creating WebGL context with your selected attributes.')`。
`isWebGL2` 只剩 `:2376` 一处 **硬编码 `true`** 的兼容占位 —— 这就是 WebGL1 后端已被整段移除的证据。

而我们的基线电视**只有 WebGL1**：真机探针记录 `WebGL GLSL ES 1.0` / **`isWebGL2 = false`** /
PowerVR Rogue G6110 / `MAX_TEXTURE_SIZE = 8192`
（`docs/archive/e41-tv-blackscreen-fix-plan.md:88,161`）。⇒ **r180 在这台电视上连 context 都建不出来。**

"那降到 r160 不就行了？" —— 本项目 E41 确实证明过 **r160 的 WebGL1 路径在 Chrome 39 上能编译着色器并正确渲染**
（`e41 plan:88` 可行性闸门一）。但降到 r160 要一并处理四处耦合，**不是改版本号**：

| 耦合点 | 事实 | 后果 |
|---|---|---|
| `samples: 4`（4× MSAA 离屏目标） | `plasmascape/render.js:130`、`bonfirescape/render.js:146`、`bettascape/render.js:177`、`riverscape/render-policy.js:46`（注释明令**禁止改**） | MSAA 离屏是 WebGL2 特性；WebGL1 下静默变 0，**边缘抖动/观感回退** |
| `DepthTexture(..., UnsignedIntType)` / `FloatType` 深度 | `shared/postprocessing.js:6`、`bettascape/render.js:148` | WebGL1 需扩展，PowerVR G6110 未证可用 |
| `flat varying vec4 vLight` | `plasmascape/src/shaders.js`（8 处，**全项目仅此场景**） | `flat` 是 **GLSL ES 3.00** 限定符；场景**又从不设** `glslVersion: GLSL3`（全树 grep `glslVersion` → 0 命中）⇒ 这些着色器只在桌面 ANGLE 的宽松前端下编译过 |
| three 版本 API 面 | r180 写的 `outputColorSpace` / `SRGBColorSpace` / RT 构造参数 | r152+ 即有，r160 大体兼容，但 `#include <colorspace_fragment>` 一类 chunk 名有改动 |

⇒ **闸门①判语**：换 three 版本**理论上**能过，代价是把「原样移植」变成「换库重验」，且**过不了闸门③**，
所以不值得为电视端做。

### 闸门② 引擎下限是 Chrome 93 量级，Chrome 39 连解析都过不了

- `import map` 需 **Chrome 89+**；`Object.hasOwn`（`scenes/shared/render-policy.js:22`，⚠️ 在每次取画质档的**热路径**上）需 **93+**。
- three r180 本体是**重 ES6**：实测 `three.core.js` 含 `class ` ×**321**、`const ` ×2801、`let ` ×721、反引号 ×2590。
- 而 Chrome 39 的实测 ES6 矩阵（**逐项 eval 得来，非推测**，`app/src/globe-upstream/downlevel_libs.mjs:13-18`）：
  `class` / 箭头函数 / **`let`** / 模板串 / 解构 / 默认参数 / rest / `?.` / `??` **全是 SyntaxError**。

本项目**确实有**一套 ES5 折衷工具链（tsc `target:ES5, module:None` + 手写 polyfill），E41 靠它救活了 three r160。
但对 deskworlds 它**不适用**，两个硬原因：
1. ⛔ `module: ts.ModuleKind.None` **不做任何模块解析** —— 它只吃**已经打成 UMD 的单一经典脚本**。
   而 r161+ 的 three **不再发布 UMD 包**，12 份入口全靠 import map 解裸说明符 `three` ⇒ 必须先引入 **rollup/esbuild**，
   这是本项目工具链里**不存在**的新依赖。
2. ⛔ **Babel 已被实测否决**（`downlevel_libs.mjs:37-49`：`TypeError: e.Box3 is not a constructor`，
   5 组配置全崩，缺陷在 class 降级语义本身）。⇒ 只能 tsc，而 tsc 需要前置打包（第 1 点）。

**顺带一条好消息**：`new Proxy` 在 three r180 本体里 grep → **0 命中**，所以 E41 那条
"three-globe 2.45.2 把 TSL 打进包里、模块初始化期 `new Proxy`、ES5 原理上做不到"的阻断点，
对**不含 three-globe 的原生 three 移植****不适用**。

### 闸门③ ⛔ 宿主合成墙（最深，且与 WebGL 无关）

即使①②全破，画面**仍然上不了屏**：这台电视上，被 Compose `AndroidView` 承载的 WebView
**只合成第一帧，之后永不更新**。判定证据链完整（`docs/technical-overview.md` §10.215）：

| 证据 | 读数 | 排除项 |
|---|---|---|
| `frames` 时间序列 | 3 → 3 → 12 → 161 → 2653 | rAF 没死 |
| `renderMs` | 首帧 10426 ms，之后 **3–9 ms** | 不是性能问题 |
| 画布回读 | `["14,16,25", ...]` 真实像素 | 场景**确实画出来了** |
| `screencap` 量化 | 地球区域**精确 (0,0,0)** | 画了但**没上屏** |
| **放普通 2D canvas 红方块** | ⛔ **同样不显示** | **与 WebGL 完全无关** |

已排除的修法：`preserveDrawingBuffer:true` 无效、`setLayerType(LAYER_TYPE_SOFTWARE)` 直接让 WebGL context 创建失败（更糟）、
`onResume()+resumeTimers()` 无效。唯一未试的路是「把 WebView 移出 Compose、挂到 Activity 根」，
其代价记在 `e41 plan:271`：**⏸ 未做（会盖住歌词与指示器，需改全 App 共用的舞台代码）**。

⇒ **所有者 2026-10-07 已对同一堵墙裁决「不修」**（E41 改为仅最高画质档提供，旧电视黑屏接受）。
本移植**不应重开该裁决**；deskworlds 路线在基线电视上的可行性 = **否**。

---

## 4. 六个世界的移植优先级矩阵

| 场景 | 场景代码行数 | 外部资产 | 离屏目标 | pass | 灯光/阴影 | 音频映射直觉 | 建议 |
|---|---|---|---|---|---|---|---|
| **plasmascape** 等离子球 | 1,447（main 149 / plasma 408 / render 246 / shaders 590） | **0** | 5 处 | 1 + 6 级 bloom | 无灯无阴影（自发光） | ⭐⭐⭐ 电弧数量/亮度=振幅，`spectrum()` 色尺现成 | **P0 首选**：零资产、无阴影、体量最小 |
| **bonfirescape** 篝火 | 1,950 | 0 | 9 处 | 1 + 6 级 bloom | **2 点光，其中 1 个 512² 立方阴影 = 每帧 6 次深度渲染** | ⭐⭐⭐ `stir()`/`fan()` 天生是 beat 事件 | **P1**：注意立方阴影 |
| **koiscape** 锦鲤池 | 2,708 | 0 | **11 处** | 1 + 涟漪 ping-pong 1024×440 | 1 阴影 | ⭐⭐ beat→投饵涟漪，速度→tempo | **P2**：ping-pong RT 是风险 |
| **bettascape** 斗鱼 | 3,064 | 0 | 8 处 | 1 + DoF + 透明分层 | 1 阴影 | ⭐⭐ 指针→频谱条映射最直白 | **P2**：DoF 开销 |
| **riverscape** 河床 | **7,292**（fish 1705 / anatomy 1209 / broadleaf 1138 / environment 926） | 0 | 2 | 2 | 3 平行光 + 2048²/4096² 阴影，`shadowHz: Infinity` **每帧重渲** | ⭐⭐ | **P3**：代码量最大，收益不匹配 |
| **reefscape** 珊瑚礁 | 2,671 | ⛔ **15,344,682 B** | 3 | 3 + AO 10 采样 + 体积 10 采样 | **4 平行光** + 1536² PCFSoft | ⭐ | **❌ 不移植** |

reefscape 排除理由（三条任一足够）：① 资产 **15,344,682 B** ≈ 当前 release APK（`NASMusicTV-release-v2-38-6.apk` **25,520,898 B**，2026-10-10 实测在盘）的 **60%**；
② 自定义 `.bin` 几何需 `fetch` + 手解（magic `REEF`=0x524546，`terrain.js:13-24`），资产要走 `shouldInterceptRequest`；
③ 最重的渲染配置（4500 悬浮粒子 + 3 pass + 10+10 采样）落在我们要覆盖的中低端设备上必挂。

---

## 5. 三条路线对比

### 路线 A —— WebView 原样移植（推荐）

把场景目录搬进 `assets/`，复用 E41 已趟通的宿主：`https://appassets.androidplatform.net/...` 虚拟域 +
`shouldInterceptRequest` 从 assets 流式供文件 + `evaluateJavascript` 推送 + 焦点归还
（`WorldGlobeRenderer.kt:176-189,191-195,213-230,538-544`）。

| 维度 | 评估 |
|---|---|
| 观感保真 | ✅ **100%**，跑的就是原代码 |
| 工作量 | ✅ 小：plasmascape = 10 个 JS 文件 + 1 份 html + 1 个 Kotlin 渲染器（`isViewBased = true`，直接套 `WorldGlobeRenderer` 骨架） |
| 覆盖设备 | ⚠️ **仅** WebView ≥ 93 且有 WebGL2 者 = 手机 + 较新盒子；**基线电视不可用**（第 3 节） |
| 与现有护栏 | ✅ `isViewBased` 是**已开好的口子**（`VisualizerStage.kt:335-351` 用 `AndroidView` 托管，`draw()` 跳过）；`FxCoverageScanTest` 门槛 ≥22 → 23，需把新类登记进 `covered`/`exempt` 其一 |
| 主要风险 | ⚠️ import map + ES module 在 Android WebView 上的表现**本项目从未验过**（E41 走的是**经典 `<script src>` 四连**，注释写明"顺序不可调换"）⇒ **M0 必须先验这一条** |
| 体积 | +约 **0.41 MB**：移植件共 **18 个文件**，原始 **2,100,954 B** → 逐文件 gzip -9 合计 **429,327 B**（`gzip.compress` 直测；APK 内 zip deflate 与此同量级） |

⚠️ 体积那一栏有个**必须先决策的坑**：three 会出现**两份**（`assets/globe/three.es5.js` r160 给 E41，
新的 r180 给 E45）。两者不可互相替换 —— r160 是 ES5/WebGL1 兜底版，r180 是 deskworlds 依赖的 ES 模块版。
见第 10 节 D2。

### 路线 B —— 原生 GLES 重写（把 three 换掉）

`GLSurfaceView` + EGL + 手写投影/矩阵，着色器**近乎直接复用**（GLSL 源本身是 **ES 1.00 方言，全树无 `#version 300 es`**）。

| 维度 | 评估 |
|---|---|
| 覆盖设备 | ✅ **唯一能上基线电视的路** —— 完全绕开闸门①②③；电视 GPU 是真硬件（G6110，非软渲染） |
| 现有基础 | ⛔ **零**：全仓库 grep `GLSurfaceView\|GLES20\|GLThread` → **无命中**。本项目 21 个渲染器里 15 个走 Compose `DrawScope`、6 个裸 Canvas，**没有任何 GL 代码可参照** |
| 观感保真 | ⚠️ 中：要手搓 three 提供的东西 —— 相机/矩阵、`MeshStandardMaterial` 的 PBR 光照、`#include <tonemapping_fragment>` 等 **three chunk 依赖**（`composite.js:10,40-41`、`particles.js:30`、`terrain.js:131-132` 都在拼 chunk） |
| 工作量 | ⛔ 极大：等于新建一个 GL 子系统 + 每场景重写；且 Compose 侧**没有自定义 shader 能力**（AGSL 要 API 33+） |
| 风险 | ⛔ `flat varying` / MSAA / float RT 在 GLES2 上需逐个降级；新 native 依赖还会撞上 16KB 页对齐那条既有约束（onnxruntime 的现状） |

⇒ 判定：**不作为本次候选**。它才是"让老电视也能炫"的唯一解，但那是另一个量级的立项。

### 路线 C —— 只搬行为层，原生重绘（保真度换兼容性）

搬**纯逻辑**（`plasma.js` / `fire.js` / `pond.js` / `render-policy.js` / `frame-loop.js`，Node 已证可独立跑），
用 Compose `DrawScope` 重画。

| 维度 | 评估 |
|---|---|
| 覆盖设备 | ✅ 全档（走现成的 `RendererFx` 基类） |
| 观感保真 | ⛔ **拿不到 GIF 里那个观感**：没有光照、没有 bloom、没有 MSAA、没有景深。等离子球在 Canvas 上会退化成"一簇发光曲线" |
| 与既有教训 | ⚠️ 本项目 E41 曾走过同一条「回退原生改造」并**最终全部回退**；且基线电视 HWUI 有硬红线：**API 22 创维真机三次复现 `Region::createTJunctionFreeRegion` SIGSEGV**，禁令不分圆角；LOW 档碎矩形会把 HWUI 推进原生崩溃（`MoonlitRenderer.kt:96-99`、`RendererFx.kt:83-85`） |
| 工作量 | 中（数学可照抄 + 有 `vm` harness 先例），但**产出与预期不符** |

⇒ 判定：**只在"必须覆盖老电视"成为硬需求时才考虑**，且必须先向所有者说明"C 方案看不到 GIF 里那个画面"。

---

## 6. 打包与护栏影响（路线 A 的 as-built 清单）

| 护栏 / 约束 | 现状 | 移植时要动什么 |
|---|---|---|
| `FxCoverageScanTest` 源码扫描 | 断言渲染器类数 **≥ 22**（`:253`），且每个类必须落进 `covered` **XOR** `exempt` | 类数 +1；`WorldGlobeRenderer` 是靠 `isViewBased … = true` 被识别为 view 型并入 `exempt` 的 —— 新渲染器**照此登记**，否则门禁直接红 |
| `GlobeAssetsHygieneTest`（5 例） | 防 `node_modules` / 上游原件被 `mergeDebugAssets` 打进 APK（**真实事故：23.3 MB / 139 条目**） | ⚠️ **同一失败模式对 deskworlds 更危险**（它整个仓库 46 MB，含 15 MB 资产与 `docs/*.mp4`）。新资产目录必须纳入同一护栏，且 ⛔ **`.gitignore` 挡不住打包，真正的护栏是单测** |
| Tier 门控 | `Tier.ULTRA` 只被 `supports()` 一处消费（`ULTRA -> allowFramebuffer`，仅 `VisualQuality.HIGH`） | E45 应**同样标 ULTRA**，理由与 E41 完全一致：「能显示它的设备」与「该用最高档的设备」尽量重合，别让用户在老电视上切到黑屏 |
| 字符串双语门禁 | `VisualizerThemeStringsGateTest`（缺 `values-en` 键**编译得过、渲染时崩**） | 新增 `R.string.visualizer_theme_*` 必须同时补 `values`/`values-en` |
| ProGuard | 已有 `-keep com.nasmusic.tv.visualizer.** { *; }` | 无需改动（R8 会破坏 enum 名→渲染器创建与 DataStore 持久化，现有规则已覆盖） |
| `@JavascriptInterface` | ⛔ **全仓库不存在**，E41 只用 `evaluateJavascript` 单向推 | 保持单向；若新增 JS→Kotlin 回调才需要新 keep 规则 |

---

## 7. 性能：已测的、未测的（数字只出自脚本，⛔ 不手抄）

### 7.1 已测 —— 行为层 CPU 成本

复现脚本：**`docs/archive/verification/scripts/deskworlds_sim_cost.mjs`**（本次新建，已入库）。
它在 Node 里直接 import deskworlds 的**原文**行为层，跑 600 帧步进，并自带两条防退化自检
（探针读数必须 >0，否则"很快"可能只是"什么都没做"；被测值必须显著高于空转基线，否则计时精度不够）。

```
plasma step（40 弧 / 每帧 build 一次几何，实测 segmentCount=2091）   每帧 0.691 ms
fire step（3000 颗 CPU 积分火花，FIXED_STEP=1/120）                  每帧 0.402 ms
```

30 fps 预算（33.3 ms）里分别占 **3.1%** 与 **1.8%**。
⚠️ 这是**桌面 x64 V8**；电视 ARM SoC 至少再乘 **3~5** 才可比。⇒ 结论只能是：
**CPU 模拟不是瓶颈，瓶颈在 GPU**（bloom 金字塔 / MSAA / 阴影重渲）。

### 7.2 ⛔ 未测 —— GPU 侧（本次没能拿到，且不许猜）

尝试过真浏览器冒烟测试：本机静态服务 `node serve.mjs` 起得来（`/scenes/plasmascape/` HTTP 200，
WebGL2 context 可建），但**浏览器面板没有可见表面**：

```
visibilityState = hidden，canvas 停在默认 300×150，截图报
NATIVE_BROWSER_VIEWPORT_UNAVAILABLE (viewport=0x0, visible=false)
```

而 deskworlds **全部 6 个场景都监听 `document.hidden` 并自挂起** —— 隐藏页里一帧都不会渲染，
所以那次运行**既没有 fps 也没有 draw call 数据**，控制台 0 条消息也不能当作"着色器无错"的证据。

⇒ **GPU 成本是悬空项**，须在 M0 用**前台浏览器**或真机 WebView 补测。补测口径建议沿用本项目已有的
可复现测量约定（帧驱动 harness / `screencap` 量化 / 时间序列而非单次读数）。

### 7.3 结构性风险：它**没有自适应降档**

`scenes/shared/render-policy.js:12-19` 四档画质是**静态的、由用户在 `<select>` 里选、存 localStorage**
（`controls.js:47-51`）：

```
eco 20fps/1.05M px/dpr1 · balanced 30/1.8M/1.25 · detail 60/3.0M/1.5 · native 60/16M/∞
```

自动输入只有电池、`document.hidden`、reduced-motion、macOS 宿主的 `sceneRate`。
⛔ **没有任何"帧时间超标就降档"的闭环** —— `frame-loop.js` 只做节流，不做 LOD。
这跟本项目 E44 那轮"必须自己闭环测帧率"的经验正好相反，移植时**要在 Kotlin 侧自建观测**，
不能假定它会自适应。另外 `riverscape/render-policy.js:44-46` 把 `samples: 4` 钉死在两档且注释禁止修改，
等于把最贵的一项排除出了降档手段。

---

## 8. 已排除 / 不要重蹈（本节是写给未来的自己）

1. ⛔ **不要用 `window.sceneRate()` 承载振幅** —— 那是帧率上限，会污染 `setTimeout` 提前量计算（第 2.1 节）。
2. ⛔ **不要指望 `setLayerType(LAYER_TYPE_SOFTWARE)`** —— 在 API 22 上直接导致 `Error creating WebGL context`。
3. ⛔ **不要用 Babel 做 ES5 降级** —— 已被实测否决（`downlevel_libs.mjs:37-49`，5 组配置全崩）。
4. ⛔ **不要把 deskworlds 整个仓库塞进 `assets/`** —— 46 MB，含 `docs/*.mp4` 与 15 MB 珊瑚礁资产；
   `mergeDebugAssets` 会**原样全打**，`.gitignore` 无效。
5. ⛔ **不要为覆盖老电视去重开「WebView 移出 Compose」** —— 那是 E41 已裁决不修的宿主墙（第 3 节闸门③）。
6. ⛔ **不要用 `AudioFrame.waveform`** —— 它恒为全 0。
7. ⚠️ **不要把 CPU 毫秒数当帧率证据**（第 7.1 节）；也不要把单次读数当性能结论 —— 必须拉时间序列。
8. ⚠️ **`plasmascape` 的 8 处 `flat varying` 未经真机验证**：它们在桌面 ANGLE 宽松前端下能编译，
   不等于在目标设备驱动上能编译。
9. ⛔ **不要把 `plasmascape` 的 `spectrum(s)` 当频率色尺接线** —— 它的 `s` 是**沿弧长度位置**（`shaders.js:9-14`），拿来做频率映射是**改语义**，得新写色尺并自绘条带（§2.3）。
10. ⛔ **不要按 64 柱硬排布** —— 渲染器读 `ctx.quality.barCount`，**LOW 档是 32**；照 64 画就和全 App 口径不一致。
11. ⛔ **不要以为本项目有现成的"满高频谱条"可复用** —— §2.3 逐处直读已排除：没有镜像双条、没有满高直条、没有波形折线（`waveform` 恒 0）。最接近的只有 `BeatFirework` 那条 14% 屏高的**底纹**。

---

## 9. 若决定推进：带门禁的最小验证顺序

| 阶段 | 内容 | 通过判据 |
|---|---|---|
| **M0 引擎地板探针**（⛔ 未过不许进 M1） | 手机 + 目标电视盒上用**真 WebView** 加载**未经修改**的 `scenes/plasmascape/`（import map + ES module），跑 30 s | ① ES module/import map 能解；② WebGL2 context 建成；③ 无着色器编译错；④ `frames` **时间序列**持续增长；⑤ `screencap` 采样**非 (0,0,0)** |
| **M1 注入层原型** | 在 M0 的页面里加 `window.DeskWorld.setAudio({energy,bass,mid,treble,beat, bars[64]})`，用**合成信号**（正弦振幅 + 踩点脉冲）驱动 | 静止音频 = 静止画面（**无声帧逐像素等价**，照 E44 G15 的做法）；振幅↑ 电弧数↑ 可肉眼判定 |
| **M1b 映射层（纯函数 + 门禁）** | 把 §2.2 那张表写成**独立纯函数模块**（照 `SeasideAudioMap.kt` / `MoonAudio.kt` 的先例，零 Android import），并定死用显示通道还是线性通道；若另要字面频谱条，走 §2.4 自绘路 ① | 无声帧逐像素等于未接（G15 口径）；柱数**恒读 `frame.spectrum.size` / `ctx.quality.barCount`**，HIGH/MEDIUM 64、**LOW 32** 各验一次 |
| **M2 Kotlin 接线** | `E45` 渲染器（`isViewBased = true`，抄 `WorldGlobeRenderer` 骨架：100 ms tick + EMA + Gson + `shouldInterceptRequest`），`MOONLIT` 之后的枚举位 | `FxCoverageScanTest` 23 类绿；`VisualizerThemeStringsGateTest` 绿；双语字符串齐 |
| **M3 资产与打包护栏** | 新目录纳入 `GlobeAssetsHygieneTest` 同族检查 | 条目数与字节数**逐条对账**，且能证明上游原件不在 assets |
| **M4 真机验收** | 所有者上机判读观感 + 我驱动 adb 采样帧率 | 帧率数据来自**带开关的屏内读数**或可复跑脚本，不来自单次截图 |

---

## 10. 待所有者决策项（推进前必须有答案）

| # | 议题 | 选项 |
|---|---|---|
| **D1** | **目标设备集合**：E45 是否接受"只在手机/新盒子上显示、基线电视按 ULTRA 门控直接不出现"？ | 接受 → 走路线 A；不接受 → 只有路线 B（另一个量级）或路线 C（观感降级） |
| **D2** | **three 双份**：E41 用 r160 ES5、E45 需 r180 ES 模块。是并存（+0.41 MB）、还是把 E41 也升到 r180（要重做 E41 全部真机验证）？ | 建议并存；⛔ 不可"合并成一份省体积" |
| **D3** | **观感期望**：接受"六个世界是**活的场景**而非传统频谱条"这一产品定位吗？（它们更像背景生态，不读频谱也自洽） | 影响是否要求"静音时必须明显看得出在跟音乐"的验收口径 |
| **D4** | **M0 探针由谁跑**：需要一台 WebView ≥ 93 的 Android 设备 + 前台可见浏览器面板（本机面板不可见，第 7.2 节） | 无设备 → GPU 侧数据长期悬空，M1 无法判定 |
| **D5** | **许可署名落地**：MIT 要求随分发保留版权与许可声明。是放 `assets/desk/`（打进 APK）还是仅写进 `LICENSES.md`？ | 建议 assets 内保留原文件（`LICENSE` 1,067 B + `vendor/THREE-LICENSE.txt` 1,081 B，成本可忽略） |
| **D6** | **频谱呈现取向**：(a) 沿用本项目既有做法 —— 频谱**映射成场景行为**（电弧/火焰/鱼速），⛔ 屏幕上没有"频谱条"；还是 (b) **新造字面频谱条**叠在世界前面（本项目从无此件，要走 §2.4 自绘路 ①）？ | 建议 (a)：与 Seaside/StarrySky/Constellation 同族、零新显示件；⚠️ 选 (b) 要先回答"为什么要满高条"，且 ⛔ 不能选"映成浪"再宣称它是频谱（Seaside 已有此取舍） |

---

## 11. 事实溯源表（每个数字的来处）

| 事实 | 出处 | 核验方式 |
|---|---|---|
| three = r180 | `vendor/three.core.js` `REVISION = '180'` | grep 直读 |
| 只取 webgl2 / 无 WebGL1 | `vendor/three.module.js:15205`，grep `'webgl'` → 0 命中 | 直读 + grep |
| `isWebGL2` 硬编码 true | `vendor/three.module.js:2376` | grep |
| 零音频 | 全树 grep `AudioContext\|AnalyserNode\|createMediaElementSource\|getUserMedia\|decodeAudioData` → 0 命中（排除 `vendor/`） | grep |
| import map ×12 | `scenes/*/index.html`、`scenes/*/wallpaper.html` | grep |
| `Object.hasOwn` 仅 1 处但在热路径 | `scenes/shared/render-policy.js:22` | grep |
| three r180 ES6 密度 | `three.core.js`：`class `×321 / `const `×2801 / `let `×721 / 反引号×2590 | 计数脚本 |
| reefscape 资产 15,344,682 B | `os.path.getsize` 逐文件求和（9,392,764 + 297,228 + 2,260,822 + 2,128,841 + 1,255,027） | 直测 |
| gzip 后移植体积 0.41 MB | 移植件 18 个文件：原始 2,100,954 B → 逐文件 `gzip -9` 合计 429,327 B（three.core 单件 277,535 B） | 直测 |
| 画质四档数值 | `scenes/shared/render-policy.js:12-19` | 直读 |
| 行为层 CPU 0.691 / 0.402 ms | `docs/archive/verification/scripts/deskworlds_sim_cost.mjs` 输出 | **本脚本复跑即可再生** |
| 5 套 Node 测试通过 | `node scenes/*/tests/*.mjs` 直跑（plasma/fire/koiscape/betta-food/render-policy） | 实测 |
| Chrome 39 ES6 矩阵 | `app/src/globe-upstream/downlevel_libs.mjs:13-18`（真机逐项 eval 记录） | 直读 |
| 电视 WebGL1 / G6110 / MAX_TEX 8192 | `docs/archive/e41-tv-blackscreen-fix-plan.md:88,161`（真机探针） | 直读 |
| 宿主只合成首帧 | `docs/technical-overview.md` §10.215（证据表 6 行） | 直读 |
| 「移出 Compose」未做及其代价 | `e41-tv-blackscreen-fix-plan.md:271` | 直读 |
| 渲染器契约 / `isViewBased` 口子 | `visualizer/VisualizerRenderer.kt:20-44`、`visualizer/VisualizerStage.kt:335-351,401` | 直读 |
| 64 条柱 / 25 Hz / FFT 1024 / 无 dB 转换 | `player/SpectrumAnalyzer.kt:11-14,166-270`、`player/PcmSpectrumTap.kt:111`（`FFT_SIZE = 1024`）、`:114`（`INTERVAL_MS = 40L`）、`player/Radix2Fft.kt:104-108` | 直读 |
| `waveform` 恒 0 | `player/SpectrumAnalyzer.kt:65`（声明）+ `:144,283,310`（`waveBuf.fill(0f)`）+ 全仓库无 `.waveform[` 读取 | 直读 + grep |
| E41 的 setAudio 契约与 100 ms tick | `visualizer/renderers/WorldGlobeRenderer.kt:56,72,499,538-544` | 直读 |
| 全仓库零 GL 代码 | grep `GLSurfaceView\|GLES20\|GLThread` → 无命中 | grep |
| 22 类门禁 | `app/src/test/java/com/nasmusic/tv/visualizer/fx/FxCoverageScanTest.kt:253` | 直读 |
| 频谱形态逐处（径向条 / 底边底纹 / 半径 / 列速 / 星点位置 / 弧长 / 目标池 / 浪高） | `BasicRenderers.kt:90-96,139`、`UltraRenderers.kt:189-195`、`ParticleRenderers.kt:205-217`、`AdvancedRenderers.kt:207-211,615-617,962-967`、`StarrySkyRenderer.kt:592-605`、`BatchThreeRenderers.kt:131,146`、`SeasideAudioMap.kt:441`、`MoleculeRenderer.kt:384`、`MoonAudio.kt:19` | **逐处开文件直读** |
| ⚠️ **无镜像双条 / 无满高直条 / 无波形折线** | 全仓 `drawLine` 命中仅 `BasicRenderers.kt:120-134`（环的径向笔触）、`BatchFourRenderers.kt:1169,1191,1265,1789`、卡拉 OK 音高线；`drawDots` → 0 命中 | grep |
| ⚠️ **deskworlds 无任何频谱显示件** | 全树（排除 `vendor/`）grep `fillRect\|strokeRect\|clearRect` → **仅 1 命中**：`scenes/reefscape/src/composite.js:56` 的 `clearRect` 清屏调用 | grep |
| 可复用真件 | `visualizer/VisualizerMath.kt:40-41`（`barHeight`）、`:56`（`polar`）、`:137`（`hueGradient`）；`visualizer/PeakHoldTracker.kt`（消费方 `BasicRenderers.kt:139`） | 直读 |
| barCount：HIGH 64 / MEDIUM 64 / **LOW 32** | `data/model/AppSettings.kt:377-379`；`frame.spectrum` 恒 64 见 `visualizer/SpectrumContract.kt:12` | 直读 |
| 当前 release APK 25,520,898 B | 在盘文件 `app/build/outputs/apk/release/NASMusicTV-release-v2-38-6.apk`（2026-10-10），与 `docs/technical-overview.md:14503` 记录一致 | 直测 + 直读 |

---

## 12. 版本历史

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-10 | 初版：三道闸门裁决 + 六场景矩阵 + 三路线对比；新增可复跑脚本 `deskworlds_sim_cost.mjs`；如实登记 GPU 侧为**未测** |
| v1.1 | 2026-10-10 | 补 **§2.3 频谱显示件对标** + **§2.4 方案性质结论**：逐处直读本项目 8 种频谱映射形态，并**排除**三项曾被误记为"现成件"的东西 —— ⛔ 无左右镜像双条、⛔ 无满高频谱条（最"直"的只有 `BeatFirework` 14% 屏高底纹）、⛔ 无波形折线（`waveform` 恒 0）；同时更正本节初稿里**凭转述写入的错误行号与不存在的文件**（`SpectrumScales.kt` / `PeakFollower.kt` 均不存在，真件是 `VisualizerMath.kt` / `PeakHoldTracker.kt`）。新增 M1b 门禁与 **D6** 取向决策项；§2.2 补 `barCount` 三档口径与 gamma 显示通道两条红线 |
| v1.2 | 2026-10-10 | 按所有者要求把 **§0 改为「先回答两个问题（大白话）」**：新增 **§0.1 术语口径**（9 个词的唯一含义 + 「一个词两种理解时不另造第二个东西」的规则）、**§0.2 能不能移植**、**§0.3 对标是干什么的**（两边橱柜盘点 → 结论是改方案性质的那条：缺的是接线不是零件）；原裁决表降为 **§0.4**。**章节编号未改动**（⚠️ 若插入新顶级节会连带 §2.x 等 30 余处交叉引用，且会误伤指向 `technical-overview` 的 `§10.215`），因此术语节以 §0.1 形式置于最前 |
| v1.3 | 2026-10-10 | ❌ **所有者裁决「放弃此项工作，文档归档」** ⇒ 状态改为**已放弃 / 不再维护**，本文移入 `docs/archive/`。⚠️ 归档时 **D1–D6 六条待决策项均未获裁决**（放弃 = 对 D1 的否定回答），逐条留痕见 `docs/archive/README.md` 第七轮；正文 0–11 节**一字未删**。可复跑脚本 `docs/archive/verification/scripts/deskworlds_sim_cost.mjs` 同处保留（§7.1 两个毫秒数的唯一再生源） |
