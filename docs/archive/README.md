# docs/archive — 已完成文档归档

> **归档日期**：2026-09-20（三轮）
> **归档依据**：见 `AGENTS.md` → Conventions → *Doc lifecycle* 与
> `docs/technical-overview.md` §10.166–§10.167。
>
> 本目录存放**已完成使命**的文档：功能已发布、审查已闭环、方案已实施、方案已废弃。
> 它们**不再维护**，保留在此仅作历史追溯与决策依据。

## ⛔ 三条判定原则（都不是「是否被引用」）

**原则 1 — 文档自身的「状态」标记会过期，以 `CHANGELOG.md` / `technical-overview.md` / 所有者结论为准。**
标记只说明「文档没回填」，不说明「功能没做」。第三轮的 10 篇**全部**自称
「待评审 / 可开发状态 / 方案提案 / Phase 7 未开始」，实际都已落地 —— 这是本仓库最密集的一次反例。

**原则 2 — 「被源码 / `AGENTS.md` 引用」不是归档障碍。**
移动时会在同一次操作里改写**全部**引用（含 `app/src/**` 的 KDoc、`AGENTS.md`、
`CHANGELOG.md`、跨文档链接），路径依然有效，**不会断链**。把「被引用」当否决
会白留一堆已完成的文档 —— 这正是第二轮归档（6 个文档、57 处引用）纠正的错误。

**原则 3 — 方案「永久无法实现 / 主动放弃」同样可归档。**
归档不等于「成功」：目标已失效的方案留着只会持续误导（看着像「还能做」）。
判据是**所有者已明确结论**，例如：
- 阿里云盘 —— 官方已关闭第三方应用访问，**永久不可实现**；
- `network-music-upgrade-plan`（对接 Go Music API）—— 无公开 API 端点、必须自行部署，**主动放弃**。

> 反例边界：只是「暂时没排期」的**在办**方案不适用本条，仍留 `docs/` 根。

---

## 第一轮（41 个）

### 代码审查 / 快照（10）

| 文档 | 判定依据 |
|---|---|
| `code-review-2026-06-26.md` | 审查报告；问题已处理（v2.4.2 修复 9 项，其余已评估） |
| `code-review-2026-06-30.md` | 审查报告；结论已并入后续优化方案 |
| `code-review-2026-09-03.md` + `.docx` | 审查报告；问题已修复并记录于 `technical-overview.md` §10.14x |
| `code-review-2026-09-07.md` | 离线下载功能审查报告；结论已落地 |
| `code-review-2026-09-16.md` + `.html` | 审查报告；问题已修复 |
| `code-review-deferred-analysis-2026-09-04.md` + `.docx` | 复审暂缓项分析；三项均已处理 |
| `codebase-architecture.html` | 架构快照，一次性产物 |

### 已落地的功能方案（22）

| 文档 | 判定依据（CHANGELOG / 源码） |
|---|---|
| `pinyin-search-plan.md` | 拼音搜索已实现（v2.25.7） |
| `subsonic-support-plan.md` | `SubsonicAdapter.kt` / `SubsonicRestClient.kt` 已存在 |
| `daoliyu-feiniu-backend-plan.md` | `DaoliyuAdapter.kt` / `FeiniuAdapter.kt` / `FeiniuUrl.kt` 已存在 |
| `radio-and-jamendo-source-plan.md` | 电台 + Jamendo 音源已在 CHANGELOG 记录 |
| `local-music-feature-plan.md` | 本地音乐已在 CHANGELOG 记录 |
| `optimization-plan-lyrics-cover.md` | 逐字歌词 / 封面轮播已在 CHANGELOG 记录 |
| `library-ui-optimization-plan.md` | 专辑 / 艺术家页面 UI 已在 CHANGELOG 记录 |
| `歌曲离线下载与本地存储管理开发方案.md` + `.docx` | 离线下载已在 CHANGELOG 记录 |
| `K歌开发方案.md` | `KaraokePlaybackScreen.kt` / `KaraokeLyricsView.kt` 已存在 |
| `network-music-tab-plan.md` | 网络音乐顶级 Tab 已在 CHANGELOG 记录 |
| `network-music-feature-proposal.md` | 网络搜索歌曲已在 CHANGELOG 记录 |
| `music-api-solution.md` | 免费音乐 API → Meting 层已落地 |
| `multi-bitrate-playback-download-plan.md` | 网络音乐多码率已在 CHANGELOG 记录 |
| `network-music-failover-plan.md` | 播放失败多级降级已在 CHANGELOG 记录 ⚠️ 文档原写「尚未开发」= 过期标记 |
| `backend-api-version-display-plan.md` | 关于页「API 版本号」展示区已在 CHANGELOG 记录 |
| `remote-control-design.md` | 手机点歌 / 队列编辑已在 CHANGELOG 记录 ⚠️ 文档原写「待开发」= 过期标记 |
| `vocal-removal-approach-c-ai.md` | AI 人声分离（Demucs）已落地 |
| `vocal-separation-pitch-speed-plan.md` | K 歌页面变速控制已在 CHANGELOG 记录 |
| `nas-music-tv-v2.6-feature-proposal-from-mineradio.md` | v2.6.0 已于 2026-07-03 发布 |
| `codebase-optimization-plan.md` | 文档自述 v1.2 已实施 |
| `github-actions-android-ci-cd.md` | CI/CD 已落地（见 `AGENTS.md`） |

### 其他一次性产物（4）

| 文档 | 判定依据 |
|---|---|
| `regression-test.md` | 回归测试清单；被 `CHANGELOG.md` / `technical-overview.md` 引作历史依据 |
| `v2.7.0-feature-test.md` | v2.7.0 功能测试计划，该版本早已发布 |
| `roadmap.md` | 2026-07-01 待办清单，11 项完成 10 项 |
| `百度网盘音乐播放开发方案-评审意见.md` | 评审意见 15 项已全部回填至方案 v2 |

### 对外文章 → `docs/articles/`（5）

`zhihu-introduction.md`、`zhihu-introduction-v2.16.md`、`zhihu-article-v2.18-to-v2.22.md`、
`zhihu-article-2026-09-v2.22-to-v2.32.md` + `.html`

---

## 第二轮（6 个）—— 纠正「被引用即否决」的过度保守

这 6 个都**被源码 KDoc 或 `AGENTS.md` 引用**，第一轮被误判为「不可归档」。
移动时同步改写了 **57 处引用 / 41 个文件**（其中 30+ 是源码），死链 0 新增。

| 文档 | 判定依据 | 被谁引用（已改写） |
|---|---|---|
| `phone-portrait-ui-plan.md` | 竖屏适配已实施（v2.36.0） | `strings.xml`、`UiModeTest.kt` 等 |
| `playlist-import-feature-plan.md` | 歌单导入已落地（§10.161 二维码 + URL 远程上传） | `backend/playlist/*.kt` 等 20+ 处 |
| `feiniu-backend-improvement-plan.md` | 飞牛后端已落地 | `FeiniuAdapter.kt`、`FeiniuUrl.kt`、`AGENTS.md` |
| `mv-karaoke-feature-proposal.md` | MV / K 歌已落地 | `backend/network/mv/*.kt`、`MvPlaybackScreen.kt` 等 |
| `phone-support-plan.md` | 电视 + 手机双端支持已落地 | `AGENTS.md` |
| `催眠频谱效果开发方案.md` | 催眠可视化已落地（CHANGELOG「新增催眠 E25」） | `FormulaLayout.kt` |

---

## 第三轮（13 个）—— 所有者确认：状态标记全部过期

这 13 个（12 篇 md + 1 个 docx 对照件）中的前 11 个是我第二轮**判定为「未落地」而保留**的，
所有者随后逐条确认**全部已完成或已放弃**；后 2 个是我第二轮判为「活文档」而保留的，
所有者确认**开发工作早已完成**。判定依据从「CHANGELOG 交叉验证」升级为
**所有者结论**（CHANGELOG 只覆盖一部分，方案变更类确实查不到）。

| 文档 | 文档自称状态 | 实际结论 | 所有者依据 |
|---|---|---|---|
| `aliyundrive-support-plan.md` | 「规划中，遭遇硬阻塞，需决策」 | ❌ **永久无法实现** | 阿里云盘已确认不支持其他应用访问 |
| `百度网盘音乐播放开发方案.md` | 「开发进行中（Phase 7 未开始）」 | ✅ 已完成 | 已完成 |
| `android-auto-plan.md` | 「阶段 1 已实施；阶段 2/3/4 未实施」 | ✅ 已开发完成 | 已完成，无实机无法测试 |
| `music-visualizer-dev-plan.md`（+ `.docx`） | 「v6.0（**可开发状态**）」 | ✅ 已开发完成 | 效果库已开发完成 |
| `unified-source-architecture.md` | （无状态标记） | ✅ 已开发完成 | 已开发完成 |
| `audition-lyrics-solution.md` | （无状态标记） | ✅ 已完成（**方案变更**） | 后续方案已完成 |
| `metadata-search-service-solution.md` | （无状态标记） | ✅ 已完成（**方案变更**） | 后续方案已完成 |
| `network-music-upgrade-plan.md` | 「方案提案」 | ❌ **主动放弃** | Go Music API 无公开端点，须自部署 |
| `feature-dev-plan-2026-09.md` | 「待所有者评审」 | ✅ 已开发完成 | 已开发完成 |
| `phone-media-display-plan.md` | 「方案设计（**待评审**）」 | ✅ 已开发完成 | 已开发完成 |
| `codebase-refactoring-plan-2026-09.md` | 「v1.6，含**待所有者决策项**」 | ✅ 已重构完成 | 早已重构完成 |
| `vocal-removal-approach-b-dsp.md` | 「已实施（v3.2 已按实测调优）」 | ✅ 已开发完成 | 已开发完成 |

> ⚠️ **`codebase-refactoring-plan-2026-09.md` 归档时仍含 2 项未闭环的所有者决策项**，
> 归档表示「不再作为在办方案维护」，**不等于这 2 项已决定不做**：
> - **R-5**：PlayerManager 四阶段拆分（PlayerCore / PlayerQueue / PlayerMediaSession /
>   PlayerAudioFocus）—— v1.6 已**降级为待所有者决策项**，「未经所有者重新确认不得启动」；
>   现有定案是「HQ 编排保留 PlayerManager **防接口爆炸**」。
> - **F-8**：下载无保活（P2）——「待所有者决策后再定」。
>
> 若日后要启动任一项，请从归档件恢复评估（播放/队列/媒体会话/音频焦点全路径手测成本）。

> ⚠️ **`vocal-removal-approach-b-dsp.md` 的特殊性**：§10.152 曾把它列为「算法复原依据」
> （`VocalRemovalProcessor.kt` 删除时「先把算法归档再删」）。归档**不削弱该角色** ——
> 它仍是完整算法文档（Mid/Side 流程图、最终参数、`queueInput` 伪代码），
> 只是位置从 `docs/` 根移到 `docs/archive/`，引用已同步改写，复原路径不变。

> ⭐ **方法论修正（本轮最大收获）**：CHANGELOG 交叉验证对「已上线功能」有效，但对
> **方案变更**（`audition-lyrics` / `metadata-search`：功能以另一种形态落地，原关键词自然零命中）
> 与**主动放弃**（`network-music-upgrade-plan`）**必然漏判**。
> 这两类只能问所有者 —— **不要因为「CHANGELOG 零命中」就断言「未落地」**。
> 第二轮判「留」的 10 个**全被推翻**，正是这条漏判造成的。

同步改写 **68 处引用 / 29 个文件**（含 `ApiProbe.kt`、`BaiduNetdiskConfig.kt`、
`PlayStatsAggregator.kt`、`PlayStatsRepository.kt`、`RadioSongScorer.kt`、
`MediaLibraryTree.kt`、`SleepTimerController.kt`、`BeatDetectorTest.kt`、
`ViewModelEvents.kt`、`HqSeparationOrchestrator.kt`、`SpectralMaskProcessor.kt`、
`AGENTS.md`、`CHANGELOG.md`、`technical-overview.md`）。死链 **0 新增**。

> ⚠️ **工具盲区（本轮实测）**：改写只覆盖带 `docs/` 前缀的引用，
> **裸文件名**（如 `vocal-removal-approach-b-dsp.md`）**不会被匹配**，
> 必须 `grep <basename>` 手工清扫（本轮手工修了 3 处：`AGENTS.md`、本 README、
> `technical-overview.md` 的活文档清单，以及 `mv-karaoke-feature-proposal.md` 的关联链接）。

---

## 第四轮（2026-10-02，1 个）—— 所有者指示：不再维护

| 文档 | 判定依据 |
|---|---|
| `starry-sky-visualizer-plan.md` | ✅ **已落地**：E42「星空星轨」已实现并上机（创维 Android 5.1.1 / API 22），门禁 `testDebugUnitTest` **1582 例 / 0 失败**、`lintDebug` **0 Error**；记录见 `technical-overview.md` §10.206。⚠️ **所有者 2026-10-02 明确指示「归档吧，不用再更新了」** —— 该文档在实现期被高频回写（v1.0→v1.5 共 5 版，含 16 行偏差记录），继续维护的成本已超过其作为「决策依据」的价值，故停止更新。实现细节以**源码 + §10.206** 为准，本文档仅作历史追溯 |

**归档时仍未关闭的决策项**（⚠️ 归档不等于已解决，特此留痕）：

1. ⛔ **真机验收未完成**。§十 的 V4–V12 只做到 V4（v1.4 实测 **29.7 fps**）；**v1.5 的「spray 弧场」层从未上机验证** —— 其「帧率与 v1.4 持平（约 29.7 fps）」是**预期而非实测**。v5 的分布密度、亮弧数量观感均未经真机确认。
2. ⛔ **风险 R1（API 22 填充率）仅被「规避」而非「量化」**。v1.4 删掉逐帧全屏缓冲后暴露面大降，但 `dumpsys gfxinfo framestats` 的正式实测始终没做。
3. ⛔ **两处「~1.3 倍台阶 / hash 精度」隐患未在真机判读**：
   - v1.4 的加色叠加在亮线段起点约 1.3× 的台阶（`SEG_GAMMA` / `RING_ALPHA` 一行可调）；
   - v1.5 的 `hash01` 只有约 3e3 个可区分 Float 值，1920 个 `(band,k,n)` 三元组中会有碰撞。fix-3 判断无害（四路参数流相互独立），**但若真机读作「规律重复」，正解是换 32 位整数 hash，而非调参**。
4. ✅ `docs/starry-sky-preview.html`（浏览器原型）**已删除**（2026-10-02，所有者决定）。它是累积缓冲机制的历史原型，Kotlin 侧 v1.4 起已改为「底环 + 亮线段」，**从来不是契约来源**；其驱动过的设计决策（`t^0.72` 半径映射、流星必须用圆弧、枯树替代人物剪影、地平线占底部 1/4、虚化平台被否决等）已完整记入 `docs/archive/starry-sky-visualizer-plan.md` 与 `technical-overview.md` §10.207，原型本体不再保留。

> 引用改写：2 处（均在 `technical-overview.md` §10.206），路径 `docs/starry-sky-visualizer-plan.md` → `docs/archive/starry-sky-visualizer-plan.md`。死链 **0 新增**（裸文件名 grep 已扫，无遗漏）。
>
> ⚠️ 该文件归档时**尚未纳入 git**（全程 untracked），故用普通文件移动而非 `git mv`——**无历史可保留**，首次提交即以归档路径入库。

---

## 第五轮（2026-10-08，1 个）—— 所有者裁决：不再推进

| 文档 | 判定依据 |
|---|---|
| `visualizer-texture-upgrade-plan.md` | ⚠️ **本篇不是「已落地」，而是所有者判断「不再推进」**（原话「这个文档归档吧，我判断是没啥可做的了」，随后在「整篇归档 / 拆分遗留」两案中选定**整篇归档**）。**已落地的部分**：批次 A/B（S0–S4）质感改造全部落码并过门禁、E29 太阳系 §C1 六条已落地（`13767a1` / §10.219）、低画质门控方案 C 与列条合并已真机复验。**未落地的部分见下面四条遗留** ⇒ 按本仓规矩，「只是暂时没排期的在办方案」**不满足**归档判据，本轮是**所有者裁决覆盖该判据的例外**，务必不要读成「做完了」。移动方式 = `git mv`（历史保留、内容一字未删）；恢复推进的入口是文内 **§12.3 任务清单**与 **§十一 上机验收清单**；实现现状一律以**源码 + `docs/technical-overview.md` §10.203–§10.219** 为准，⛔ 不要拿本文的规格文本当契约 |

**归档时仍未关闭的遗留（⛔ 归档 ≠ 已解决，四条逐条留痕）**：

1. ⛔ **批次 C 的 6 套质感改造至今 0 套开工**：T5.2 E33 齿轮 / T5.3 E37 分子 / T5.4 E38 怀旧 /
   T5.5 E39 照片墙 / T5.6 E40 DNA / T5.7 E41 世界。⛔ **更要紧的是基类迁移面也是 0 套** ——
   欠账的 **E29 / E33 / E37 / E40** 四套至今仍直接实现 `VisualizerRenderer`（不是 `RendererFx`），
   各自持有 `lastMs` 自算 `dt`、自己的 `rng` / 内联 LCG、自己的后处理释放。
   ⚠️ 这四套**不是**裁决排除（有意排除的是 E38 / E39 / E41 三套），别混进豁免理由。
2. ⛔ **真机观感验收几乎整张未跑：27 条判据未勾**。§11.1 的 7 条通用判据只有 `U1`/`U4` 过
   （余 `U2`/`U3`/`U5`/`U6`/`U7`）；§11.2 的 22 条逐效果判据**全部未勾** —— 唯一曾勾过的 `V1`
   属于**已删除的 E03** ⇒ 现役 21 套里 13 套的质感改动 + E29 的增强**从未逐套上机看过**
   （唯一例外 E16 数字雨，因排查崩溃被反复上机）。
   ⚠️ 附带风险：E05 / E13 / E15 / E17 **没有专用单测**（只在 `covered` 名单里）⇒
   观感改动被后来的提交抹掉时**不会有任何测试失败**。
   恢复验收的方法与基线（改造前 = `ca23876`，逐像素不变的最后一次提交；创维 API 22）在 §11.3。
3. ⛔ **§15.7 待修 7 条**（#1 / #3 / #20 / #21 / #23 / #24 只需改注释或已复核仍在源码）；
   **#22 需所有者拍板** —— `ParticlePool.updateAttract()` **零调用方**：删函数 vs 保留复用能力。
4. ⛔ **阶段 6 离屏层接线未做**：T6.1 `drawBloom` **代码已写完但零消费方**（刻意保持未勾）、
   T6.2 / T6.3 屏幕级 bloom 的接线与实测未做；另**裁决项 4 的 600ms 交叉淡入至今未落码**
   （`VisualizerStage.kt:205` 的第 3 实参仍硬编码 `false`）。

> 引用改写 **13 处 / 6 个文件**（`technical-overview.md` ×2、`visualizer-effects-list.md` ×2、
> `archive/music-visualizer-dev-plan.md` ×1、`archive/seaside-visualizer-plan.md` ×3、
> `archive/starry-sky-visualizer-plan.md` ×4、`archive/verification/scripts/verify_linerefs.py` ×1）
> + 本文自引 2 处；其中 **4 处是裸文件名**，靠 `grep <basename>` 手工补的 `docs/` 前缀（第四次印证本 README 的「工具盲区」条）。死链 **0 新增**。
>
> ⚠️ **行号锚点已失效**（这是本篇独有的，其余归档件没有）：`seaside-visualizer-plan.md:9,147` 与
> `starry-sky-visualizer-plan.md:11,160,211,216` 引用该方案的 6 处 `:NNNN` 锚点，因该文档自身的整节删除
> 而**正负双向漂移**，无法用一个偏移量换算。正确行号已逐条列在该文档的 **v1.50 行 ⑥**。
> ⇒ ⛔ **今后引用已归档文档一律用章节号（§ / T 编号），不要用行号**；两份引用方文档同为归档历史件，按规矩不回写。

---

## 未归档（仍留 `docs/` 根，2 个）—— 全部是活文档

> 计数口径：⛔ **本节原先写「`docs/` 根只剩 2 篇 Markdown」——那已经过期**（`visualizer-effects-list.md`
> 与 `visualizer-texture-upgrade-plan.md` 是之后新增的）。2026-10-07 实测 `docs/` 根 = **4 篇 Markdown**；
> **2026-10-08 第五轮把 `visualizer-texture-upgrade-plan.md` 归档后 = 3 篇**：
> **活文档 2 篇**（下表）+ **在册清单 1 篇**（`visualizer-effects-list.md` —— 它是 `VisualizerTheme`
> 的现役效果名册，随效果增删持续维护 ⇒ 属活文档性质，但不在下表的「约定类活文档」里）。
> ⇒ ⛔ 原文「**在办方案 2 篇**（`visualizer-texture-upgrade-plan.md` 仍在办，故**不满足**归档判据）」
> 那句**已作废**：该方案已于 2026-10-08 经所有者裁决整篇归档，遗留四项见**第五轮**。
> ⚠️ **2026-10-10 第六轮之后**：`docs/` 根 = **3 篇 Markdown / 0 份原型 html**（`moonlit-visualizer-plan.md`
> 与 `moonlit-preview.html` 移入本目录），下表**未变** —— `moonlit-*` 两份从来不是活文档。
> ⚠️ **同日第七轮**：`deskworlds-port-feasibility.md`（候选 E45，**所有者裁决放弃**）移入本目录，
> 根目录一度因它落到 4 篇，**现已回到 3 篇**，下表依旧**未变**。
> `docs/slow-roads-verify/` 是验证素材目录，不计入文档数。
> `docs/snapshot/`（16 张截图）不是文档，不计入。
> `docs/*.miora` / `docs/*_assets/`（gitignored 的 WorkBuddy 设计画布素材）已于
> 2026-09-20 经所有者确认删除（对应功能早已上线，素材无引用）。

| 文档 | 为什么是「活」的 |
|---|---|
| `technical-overview.md` | 全项目主索引，§10.N 持续追加（当前最大 §10.167） |
| `conventions-adaptive-ui.md` | 自适应 UI 约定，随新组件持续维护 |

> 其余文档一律视为「已完成 / 已放弃」→ `docs/archive/`。
> 若日后需要恢复某个已归档方案（如 R-5 四阶段拆分、F-8 下载保活），
> 从 `docs/archive/` 取出评估即可 —— 归档是**纯移动**，内容一字未删。

---

## 附：验证证据迁移（2026-09-20，来自 gitignored 的 `logs_temp/`）

`logs_temp/` 是项目约定的临时目录（`.gitignore:87`），但其中有一批文件**被已入库文档引用**
（`docs/technical-overview.md` §10.14x–§10.16x、`CHANGELOG.md`、`AGENTS.md` 等）。
一旦清理该目录，这些引用会**悬空**，其中全量审查报告的「未完成项」更是**唯一记录**。
故把其中的**证据类文件**迁入版本控制（`logs_temp/` 保留为空目录，约定不变）。

### 迁到 `docs/archive/`（与既有 `code-review-*.md` 同级）

| 文件 | 被谁引用 |
|---|---|
| `code-review-full-report-2026-09-13.md` | §10.147 / §10.148 / §10.152–§10.156 的「来源」 |
| `code-review-karaoke-onnx-2026-09-14.md` | §10.146 / §10.158 的「来源」 |

### 迁到 `docs/archive/verification/`

| 内容 | 被谁引用 |
|---|---|
| `verify4.log` | `AGENTS.md` / §10.165（lint 内部异常栈为既有现象） |
| `render_auto_icons.py`、`render_car_icon.py` | `CHANGELOG.md`（Android Auto 图标形状推导） |
| `parse_lint.py` | 解析 lint HTML 报告（可复用工具） |
| `_depsrc/`（media3-session 1.2.1 源码，51 文件） | §10.165 的证据链 |
| `verify_resampler/`、`verify_feiniu_url/`、`verify_auth_headers/`、`verify_ort/` | `CHANGELOG.md` / §10.146 / §10.155 的验证 harness（源码与说明，不含编译产物） |
| `scripts/seaside_wave_harness.js` | **E43 海边原型的帧驱动 harness**（2026-10-03 归档）。`vm` 沙箱桩掉 DOM/canvas/`Path2D`，打桩 `requestAnimationFrame` 驱动**真实页面代码**，注入探针读浪队列闭包状态。产出交接跳变 / 退水深长 / 浪数 / 每帧位移上限等全部量化阈值，`docs/archive/seaside-visualizer-plan.md` §7 把它列为 Kotlin 端单测的断言口径来源 |
| `scripts/seaside_hole_continuity_check.js` | 泡沫破洞场连续性（可见洞逐帧位移 0.74px = 0.31× 洞半径，8.4× 优于旧实现） |
| `scripts/seaside_caustic_geom_check.py` | 焦散胞壁网 vs 旧孤立短划的 1:1 栅格化对比（PIL） |
| `scripts/seaside_visual_driver.mjs` | 真浏览器 CDP 驱动（`snap`/`series`/`waterline`/`cost`），rAF 打桩、确定性推进；截图落 `output/seaside_frames/` |
| `scripts/html_syntax_check.js` | 从 HTML 里抽出内联 `<script>` 做 `new Function` 语法检查（不落盘临时文件） |

**未迁入**（可重新生成的构建产物与一次性日志，已移出 `logs_temp/`）：
`*.class`、`*.kotlin_module`、`aapt2-out/*.zip`、`iconprobe/`（37 M）、`adb_verify/`（22 M）、
`r8probe/`（9 M）、`_depsrc_common/`（1.8 M）及全部构建/测试日志。

**同步改写引用 30 处 / 5 个文件**（`AGENTS.md`、`CHANGELOG.md`、`technical-overview.md`、
`android-auto-plan.md`、`feiniu-backend-improvement-plan.md`），
并修正 7 处「该文件不入库 / 在 gitignore 临时目录」的过时表述。
另有 2 处测试 KDoc 原误指向 `logs_temp/` 的脚本副本（正本在项目根），一并改正。

---

## 汇总

| 项 | 第一轮 | 第二轮 | 第三轮 | 合计 |
|---|---|---|---|---|
| 移入 `docs/archive/` | 36 | 6 | 13 | **56** |
| 移入 `docs/articles/` | 5 | 0 | 0 | **5** |
| 同步改写引用 | 52 处 / 20 文件 | 57 处 / 41 文件 | 68 处 / 29 文件 | **179 处**（涉及 65+ 文件，各轮有重叠） |
| `docs/` 根目录 | 62 → 21 | 21 → 15 | 15 → **2** | **−60**（仅剩 2 篇活文档） |
| `seaside-visualizer-plan.md` | E43 海边实现方案 v1.3。**已 shipped**（`SeasideRenderer.kt` / `SeasideWaves.kt` / `SeasideOpBudget.kt`，v2.38.2；视觉由所有者真机逐项确认后定稿）。**归档时仍带未决项**：§12.5「仍未解决 / 未验证」的 **U1**（单列尖峰 10.77 px，成因未定位）、**U8**（G13 与 §4.7① 在 4K 上冲突，**待裁定**）、**U9**（两处规格歧义，已按「不猜」记录待确认），以及 §4.7 表「逐帧水线最大位移 10.77 px ⛔ 未解决」。接替记录：`docs/archive/seaside-preview.html` 是只读规格基准；全部已验证结论已落 `technical-overview.md` §10.209–§10.211 |
| `photo-spectrum-effect-plan.md` | 照片墙转场与频谱方案。**已 shipped**（`PHOTO_WALL` = E39，在册于 `VisualizerTheme`）。**归档时待确认项为 0**（文档 §统计自述「待确认 0 项」）；但 §实现期记录着两条**用户上机报告的缺陷**：「竖版图片总是无法占满屏幕」「很多时候进入动画效果还未完成…」，**是否已修复未在文档中回填** —— 需要时回查源码或重上机。⛔ 已知遗留：转场打断（手动切歌/切图时从当前 blend 反向插值）明确留给阶段 10 |
| `seaside-preview.html` | E43 海边的**浏览器原型**（2663 行单文件）。**所有者 2026-10-07 裁决：「归档吧，不用了」** ⇒ 它不再是视觉基准。⭐ **这不是「方案未完成」，而是「职责转移」**：原型已完成使命（v2.38.2 真机逐项对齐后 `SeasideRenderer.kt` 定稿），此后**真机实现是唯一权威**。⚠️ 后果已显形：`SeasideRenderer.kt` 与 `SeasideOpBudget.kt` 里有 **100+ 处 `seaside-preview.html:NNNN` 形式的行号引用**，它们此后只能指向**一份冻结的快照**——⛔ **不要再拿原型去「对齐」真机**，行号会骗人。**归档时仍带 3 项未决**：① `verification/scripts/seaside_doc_consistency_check.py` 的 `MUST` 断言 `lerp(spawnFar, shoreYs[i], tAdv)` 在原型里**已不存在**（实测 `常量不一致数: 1`）——脚本比原型旧，⛔ 别信它的绿灯；② `seaside_wave_harness.js` 的 5b/5c 段判定已失效（仍在检查被 `wiS` 取代的 `wi`，见 `seaside-visualizer-plan.md` U4）；③ 5 个脚本仍可跑（路径已同步），但它们验的是**原型**，不再覆盖真机 |
| `permission-and-signing-plan.md` | 权限与签名统一方案 v1.5。**已 shipped**（S0–S8 全批次实施，v2.38.3；`ReleaseSigningGateTest` / `LocalMusicGateTest` 三道门禁 + CI fail-fast 全部落地）。**归档时待裁决项为 0**（§状态自述「可开工，无待裁决项」，`technical-overview.md` §10.214 已回填实施结果）。⚠️ **它的引用密度是全 `docs/` 最高的**：`AGENTS.md`、`.github/workflows/build.yml`、`app/build.gradle.kts`、3 个门禁测试的**断言失败消息**都在引它 ⇒ 全部已改写为 `docs/archive/` 路径（12 处里占 6 处） |
| `e41-tv-blackscreen-fix-plan.md` | E41「世界」电视端黑屏的**诊断与裁决记录**。**❌ 修复已永久放弃**（所有者 2026-10-07 裁决：方案 A / B 均否决，「改回原来很好效果的代码……就是黑屏吧」）。定位到的真根因是**宿主**而非渲染层：该机 WebView 被 Compose `AndroidView` 承载时**只画首帧、永不更新**（2D canvas 同样不上屏），故任何页面内改动都救不了。**最终落地**：`WORLD` 由 `Tier.ADV` 提到 `Tier.ULTRA`，仅最高画质档提供（v2.38.4）。**归档时仍带未决项**：§八 的**真机验收 U1/U2** 待所有者执行——① 电视上确认 MEDIUM/LOW 档不再出现 E41；② 手机上确认观感与回退前一致。全部已验证结论已落 `technical-overview.md` §10.215 |
| `solar-system-upgrade-plan.md` | E29 太阳系程序化质感增强**功能清单 + 当时的编译错误清单**。**已 shipped**（2026-10-08：功能 ①–⑥ 全部落地，22 处错误清零，`OrbitalProceduralEnhanceTest` 11 例门禁；版本号按所有者指示**未提升**）。⚠️ **归档时必须提醒的两点**：① 本文第二节那张错误表**已过期**，头部与二级标题均已标注「历史快照」，⛔ 不要再照它排查；② 实现**偏离原规格 6 处**（预烘 16 档角度→连续角度、原色夜色→压暗、sweepGradient→径向渐变、1.04→1.18、smoothstep 边缘柔化→半径调制、楔形缝→整圈同心内圈），理由逐条记在 `technical-overview.md` §10.219 的表里 —— 原规格里 ①②④⑤ 四项**照写就会画错**。**归档时仍带未决项**：§四 的每档新增提交（LOW +2 / MED +18 / HIGH +38）是**静态估算**，真机帧率待所有者上机（§九 R18）|
| `moonlit-visualizer-plan.md` | E44「明月」开发方案 **v2.0**。**已 shipped**（T0–T11 全部闭合：`MoonlitRenderer.kt` + `MoonPhase` / `MoonClouds` / `MoonWater` / `MoonDiskBake` / `MoonSeascape` / `MoonAudio` / `MoonOpBudget`，v2.38.6 / versionCode 175；**2026-10-10 所有者在创维 API 22 真机验收通过**，裁决「其他没有问题，这个版本收尾完结」；月相按裁决改**强制满月** = 偏差 **D35**，§十六 Q1–Q9 全部关闭）。**⚠️ 归档时仍带未决项（所有者知悉，非阻塞）**：① §十三 的 **U2 首帧延迟**与 **U3 native 堆增量**从未采集，**U1 只有 HIGH 档**（23.2~29.5 fps / 19 点，LOW / MED 未测）⇒ 若要用它们做降档决策必须按 §十三 抬头三条口径重测，⛔ 不许手抄；② **解除 D35**（恢复真实月相）时 §6.5 与 V1/V2/V3/V5 四条判读必须重跑（固定满月下不可判读）；③ 4 个验证脚本里只有 `moonlit_cloud_golden.js` 有 CI 化的再生契约。接替记录：全部已验证结论已落 `technical-overview.md` §10.224；⚠️ 本文**仍是 E44 最完整的交付规格**，归档只意味着「不再作为在办文档维护」 |
| `moonlit-preview.html` | E44 明月的**浏览器原型**（1726 行单文件，所有者 2026-10-08 宣布定稿）。⭐ **与 E43 `seaside-preview.html` 不同：它仍是活的再生源，不是纯历史快照** —— `docs/archive/verification/scripts/moonlit_cloud_golden.js` 在 `vm` 沙箱里从它**原文抽出**云场函数（播种 / `layoutPuffs` / `stepClouds` / `occlusion`）生成 G3 基线 `MoonlitCloudBaseline.kt`，因此 ⛔ **不得删除、不得手工改其云场数学**（改一段必须重跑生成器并同一提交带走再生结果）。移动后已复跑：基线**逐位相同**。⚠️ 源码侧 **100+ 处 `moonlit-preview.html:NNNN` 裸文件名 + 行号引用**（`MoonClouds` / `MoonWater` / `MoonDiskBake` / `MoonPhase` / `MoonSeascape` / `MoonlitRenderer` + 对应测试）—— 归档是纯移动、内容一字未动 ⇒ 行号**仍然为真**；但按 D35 后的现状读法与 E43 一致：**真机实现是唯一权威**，不要再拿原型去「对齐」真机。⚠️ 已知定稿盲区（文档内已记录）：七轮判读**全部在 `phaseLock:'full'` 下调参** ⇒ 非满月观感从未被判读，这一条正是 D35 的可查背景 |

> **2026-10-05 追加归档 2 份**（所有者裁决）：`seaside-visualizer-plan.md`、`photo-spectrum-effect-plan.md` → `docs/archive/`。同步改写引用 **5 处 / 4 文件**（`SeasideWaves.kt`、`PhotoWallSettingsSection.kt`、`docs/archive/README.md`、`docs/archive/starry-sky-visualizer-plan.md`）。⚠️ 其中 **1 处是真正会跑坏的脚本**：`verification/scripts/seaside_doc_consistency_check.py` 的硬编码绝对路径用的是**反斜杠**，因此 `docs/` 正斜杠扫描扫不到——归档时必须手动扫**裸文件名**。⇒ 此论在本轮已被实证两次。

> **2026-10-07 追加归档 1 份**：`e41-tv-blackscreen-fix-plan.md` → `docs/archive/`。
> 同步改写引用 **2 处 / 2 文件**（`AppSettings.kt` 的 `WORLD` KDoc、`technical-overview.md` §10.215）。

> **2026-10-08 追加归档 1 份（第五轮）**：`visualizer-texture-upgrade-plan.md` → `docs/archive/`
> （可视化质感升级方案，批次 A/B 已落码、批次 C 与真机验收未跑 ⇒ **所有者裁决「不再推进」而归档，
> 非「已完成」**，遗留四条见上方**第五轮**）。移动方式 = `git mv`（本篇**已在版本库**，与同日
> `solar-system-upgrade-plan.md` 的 untracked 情形不同）；引用改写 **13 处 / 6 文件 + 自引 2 处**；
> `docs/` 根 4 → **3 篇 Markdown**。⚠️ 唯一副作用：另有 2 份归档件对它的 **6 处 `:NNNN` 行号锚点已失效**
> （该文档历史上做过整节删除），换算表在其 **v1.50 行 ⑥**。

> **2026-10-08 追加归档 1 份**：`solar-system-upgrade-plan.md` → `docs/archive/`（E29 太阳系程序化增强，已 shipped）。
> 该文档**归档前从未入库**（工作区新增），故用普通 `mv` 而非 `git mv` —— **无历史可保留**，
> 引用改写 **1 处 / 1 文件**（`OrbitalProceduralEnhanceTest.kt` 的 KDoc 指向 `docs/archive/` 路径）。
> ⚠️ 本篇是**同一次会话内先建后归档**，故上表「`docs/` 根目录」一列未变（净增删为 0），
> 只更新了「移入」与「同步改写引用」的合计。

> **2026-10-07 追加归档 2 份**（所有者裁决）：`seaside-preview.html`、`permission-and-signing-plan.md` → `docs/archive/`。
> 同步改写引用 **12 处 / 7 文件**（`technical-overview.md`、`docs/archive/README.md`、`SeasideWaves.kt`、`SeasideWavesTest.kt`、`app/build.gradle.kts`、`ReleaseSigningGateTest.kt`、`LocalMusicGateTest.kt`）+ **4 处脚本路径**。
> ⚠️ **`seaside-preview.html` 归档会跑坏 4 个脚本，且 3 个是上一轮同一个坑的第三次复现**：
> `seaside_wave_harness.js` / `seaside_hole_continuity_check.js` 用 `__dirname` 上溯**三级**取页面
> （三级 ⇒ `docs/`，须改**两级**）；`seaside_visual_driver.mjs` 用 `resolve(ROOT,'docs',…)`；
> `seaside_doc_consistency_check.py` 是**硬编码绝对路径**⇒ **正斜杠 `docs/` 扫描永远扫不到它**，
> 本轮已改成按 `__file__` 解析（顺带修掉「换机即失效」这个旧债）。
> ⇒ **`docs/` 前缀扫描 + 裸文件名扫描都不够，必须单独问「有没有脚本按路径找这份文件」。**
> ⚠️ `SeasideRenderer.kt` / `SeasideOpBudget.kt` 里的 `seaside-preview.html:NNNN` **行号引用本轮未改**
> —— 那两个文件当时正被蟹迹 lane 占用；它们是**裸文件名 + 行号**（非路径），归档不改内容 ⇒ 引用**仍然为真**，
> 但请按上面那行的告诫读：**原型已冻结，不再是真机基准**。

> **2026-10-10 追加归档 2 份（第六轮，所有者指示「这两个文件归档吧」）**：`moonlit-visualizer-plan.md`、
> `moonlit-preview.html` → `docs/archive/`（E44「明月」，T0–T11 闭合 + 同日真机验收通过 ⇒ 满足「已 shipped」）。
> 移动方式 = `git mv`（两份均已入库，`f697c93`），内容一字未删。
> 引用改写 **33 处 / 21 文件**：`AGENTS.md`（云场基线那条）、`technical-overview.md`（§10.224 依据行 + 遗留 ② 改为「已归档」）、
> `visualizer-effects-list.md`、**13 个 Kotlin 文件的 KDoc**（7 主源 `AppSettings` / `MoonClouds` / `MoonOpBudget` /
> `MoonPhase` / `MoonWater` / `MoonlitRenderer` + 6 测试）、两份文档互引 9 + 1 处、**3 个脚本的路径**。
> ⚠️ **上一轮 seaside 的坑本轮按规矩单独排查过**：三个脚本都**按路径分段**定位原型
> （`moonlit_cloud_golden.js:17` `path.join(root,'docs',…)`、`moonlit_ref_match_check.py:29` `os.path.join(ROOT,'docs',…)`、
> `moonlit_visual_driver.mjs:30` `resolve(ROOT,'docs',…)`），`docs/` 前缀扫描**一律扫不到** ⇒ 三处已改为 `('docs','archive',…)`。
> ✅ **验收是量出来的不是推断**：改完复跑 `node docs/archive/verification/scripts/moonlit_cloud_golden.js`，
> 再生的 `MoonlitCloudBaseline.kt` 与入库版**逐位相同**（97 行；参考帧 `occl = 0.20890827081355456`、`tSec = 12.499999999999972`、
> `occl` 分布五项不变）⇒ 「原型仍是 G3 基线唯一再生源」这条契约在新路径下成立。
> ⚠️ `logs_temp/` 下的 `check_tables.py` / `doc_table_check.py` / `moonlit-preview.bak.html` 仍指旧路径 ——
> gitignored 一次性脚本，**未改**。

> 上表只计「三轮归档」。另有 **2 份审查报告**自 `logs_temp/` 迁入 `docs/archive/`
> （见上文「附：验证证据迁移」）。⚠️ **原此处写「现共 62 篇文档」，实测对不上**（本轮移入前顶层 md 只有 61 篇）——
> 计数口径本就含糊（是否含 html / 是否含本索引），今后**以实测为准**：
> 第七轮之后 `docs/archive/` 顶层 = **62 篇 `.md` + 7 份 `.html`**（`ls docs/archive/*.md | grep -v README` → 62；含本索引另计）。

---

## 第七轮（2026-10-10，1 个）—— 所有者裁决：放弃

| 文档 | 判定依据 |
|---|---|
| `deskworlds-port-feasibility.md` | ❌ **本篇不是「已落地」，也不是「已排期未开工」，而是所有者直接放弃**（原话「放弃此项工作，文档归档」，未附条件）。候选编号 **E45** 从未立项；可行性结论写在文内（⛔ 基线创维电视被三道独立闸门堵死：three r180 硬只取 `webgl2` / 引擎地板 Chrome 93 / **宿主合成墙**——第三道与 WebGL 无关且 2026-10-07 已裁决不修；✅ 现代 WebView 设备可行，代价 +0.41 MB / 18 个文件）。移动方式 = **普通 `mv`**：该文档与配套脚本**全程 untracked**，`git mv` 无历史可保留，首次提交即以归档路径入库（与第四轮同例）；**正文 §0–§11 一字未删**，只改状态头 + 加 v1.3 版本行 |

**归档时仍未关闭的决策项（⛔ 归档 ≠ 已解决，D1–D6 逐条留痕；恢复推进必须先答这六条）**：

1. ⛔ **D1 目标设备集合**：是否接受「只在手机/新盒子上显示、基线电视按 `Tier.ULTRA` 门控直接不出现」？
   **放弃本身就是对它的否定回答**——若日后恢复，这条仍是第一道门。
2. ⛔ **D2 three 双份**：E41 用 r160 ES5 兜底版、移植需 r180 ES 模块版，是并存（+0.41 MB）还是把 E41 一起升 r180
   （要重做 E41 全部真机验证）？⛔ 不可「合并成一份省体积」。
3. ⛔ **D3 观感期望**：是否接受「六个世界是**活的场景**而非传统频谱条」这一定位（影响验收口径：静音时必须不能看得出在跟音乐）。
4. ⛔ **D4 M0 探针由谁跑**：需要一台 WebView ≥ Chrome 93 的 Android 设备 + **前台可见**的浏览器面板
   —— 本机面板 `visibilityState=hidden`，而 deskworlds 全 6 场景监听 `document.hidden` 自挂起 ⇒ **无设备则 GPU 侧数据长期悬空**。
5. ⛔ **D5 许可署名落地**：MIT 要求随分发保留版权与许可声明（`LICENSE` 1,067 B + `vendor/THREE-LICENSE.txt` 1,081 B），
   放 `assets/` 还是仅记入 `LICENSES.md`？
6. ⛔ **D6 频谱呈现取向**：沿用既有做法把频谱**映射成场景行为**（本项目 22 套从无满高频谱条），
   还是**新造字面频谱条**叠在世界前面？⚠️ 后者属新显示件，本项目从未有过，需另行论证。

**本篇独有的两条「不要重蹈」**（恢复时先读这两条，能省掉一整轮返工）：

- ⛔ **调研子代理曾虚构三个不存在的“现成件”**（`SpectrumScales.kt` / `PeakFollower.kt` / `AdvancedRenderers.kt:211` 的「左右镜像双条」），
  初稿凭转述写入后被逐处开文件直读推翻 ⇒ 任何引用进本文前必须**重开文件核对**（已记入版本历史 v1.1）。
  真件是 `visualizer/VisualizerMath.kt`（`barHeight:40`、`polar:56`）与 `PeakHoldTracker.kt`。
- ⛔ **§7.2 的 GPU 侧成本至今是「未测」**，不许从 §7.1 那两个毫秒数（plasma 0.691 / fire 0.402 ms，桌面 x64 行为层）外推帧率
  —— 那正是偏差 D31/D34「探针典型值当最坏帧用」的同款错误。

> 引用改写 **0 处**：`grep -rn deskworlds` 全仓仅命中本文与 `docs/archive/verification/scripts/deskworlds_sim_cost.mjs` 自身，
> **没有任何外部文档 / 源码 / `AGENTS.md` / CHANGELOG 引用它**（脚本在 `docs/archive/` 下、路径未变，文内引用照旧有效）。
> ⚠️ 未进 `CHANGELOG.md`：本篇是**纯文档、未写一行实现代码**，按本仓规矩（文档改动不入 CHANGELOG）不登记。
> `docs/` 根因此回到 **3 篇 Markdown**（两张活文档 + `visualizer-effects-list.md` 现役名册）。

## 第八轮（2026-10-12，1 个）—— 已落地并通过 owner 真机验收

| 文档 | 判定依据 |
|---|---|
| `network-artist-full-discography-plan.md` | ✅ **已落地**：随 **v2.38.7 / `versionCode 176`** 出包（`NASMusicTV-release-v2-38-7.apk` 25,541,663 B），owner 于 **2026-10-12** 报「经过测试，没问题了」⇒ §12 八项验收 + Task 8 的 6 项真机判读 + 四项已知风险**一揽子通过**。⚠️ **验收覆盖的是含 `25c570d`（10-11 21:48 的网易信封解析修复）的代码状态，不是 18:44 那只已入库的 APK**——线上「角标 + 可播置顶」曾整条静默失效，要发布该修复须**重新出包**（`versionCode` 是否升 177 由 owner 定）。代码侧：分支 `feat/network-artist-discography`（对 `main` 39 提交 / 57 文件 / +10083 −90）终审十项 F1–F10、回炉 I-1/I-2、scoped 回审全部闭合。权威落码记录 = `docs/technical-overview.md` **§10.225**；版本条目 = `CHANGELOG.md` 的 `## [v2.38.7]` 段。移动方式 = **`git mv`**（历史保留），正文 §0–§12 与附录**一字未删**，只改状态头 + 版本行（v2.4 → v2.5） |

⚠️ **归档 ≠ 职责结束**：本文仍是本功能的**唯一方案依据**——`app/src/**` 里 20 处 `艺人全量方案 §x.y` 的 KDoc 引用、`docs/archive/verification/scripts/netease_artist_probe.py` 的头注（本文所有数字的唯一再生源）、以及 §10.225 都指向它。⛔ 不要因为「已归档」把它当历史草案处置；恢复或扩展本功能时**必须先读它**（Q1–Q9 的裁决边界都在 §9，测量偏差在附录 B）。

**归档时仍未闭合的事项**（⛔ 逐条留痕，归档 ≠ 已解决）：

1. ⏳ **M-4：续页取到 null 时零日志**（终审 parked）。体感是「艺人页拉到一半不动」；修法要把第二个信号位再穿一层返回值，而 `NetworkArtistExpansion` 的设计约束是「⛔ 不做 IO、不打日志」，调用侧在 `MainViewModel` / `NetworkMusicManager` ⇒ 属独立一批。**owner 若收到「翻页不动」的反馈，先查这条。**
   ⚠️ **2026-10-12 升级为「已造成实害」**：同族的「`EMPTY_PAGE` 静默交回原段、全程零日志」正是 `25c570d` 那次失效在单测与真机上**双双不可被发现**的原因（见 §10.225 补记）⇒ 后续动这一面时按原修法（再穿一层返回值），⛔ 不要回到「入口复位共享状态」。
2. ⏳ **`403 → DEAD` 的判据风险**（Task 5 遗留 M-2）：CDN 令牌短暂过期会被钉进负面缓存 5 min。真机整体通过 ⇒ 现状可接受，但**未做逐项判读**；候选修法（补 `User-Agent: Mozilla/5.0`、把 403 移出 DEAD 集）**仍未落码**。
3. ⏳ **角标取舍待 owner 表态**（Task 8 的 M3/M4）：`possiblyUnplayable` 排在 `else -> SourceBadge` **之前** ⇒ 被打标的行失去来源标签；且角标只有灰色，TV 过扫描下偏弱。一揽子验收未细分此项，**若日后出现「看不出这首歌来自哪个源」的反馈即为它**。
4. ⛔ **`limit ≤ 100` 是接口约束、不是实现细节**：探针 v5 实测 `limit=200` ≠ 两页 100（`first100_is_subset=False`）⇒ 任何放大分页规模的改动都会**静默丢数据**。
5. ⏳ **Q8 决定不做的 kugou / P3**：`total=1845` 真分页可用但**直链 0/4**；而**自建 Meting 端点是全表唯一未测格**（§2 表第 ③ 列），§4 的「建议自建端点」押的就是这个空格。
6. ⏳ **套件级既有 flake**：`PhotoWallControllerTest > empty pool…` 全量跑必红（隔离跑绿；把本分支新增门类挪出的 A/B 跑同样红），CI `test` job 会因它红 ⇒ ⛔ 别算进本功能的账，根因在 `visualizer/photo` 域（§10.225 遗留②）。

> `docs/` 根在本轮之后**入库的 Markdown = 3 篇**（`technical-overview.md`、`conventions-adaptive-ui.md`、`visualizer-effects-list.md`）；另有另一在办会话新增、**尚未入库**的三份方案与两份原型 html，计数以实测为准，不在此定论。
>
> 引用改写 **3 个文件 / 5 处**：`docs/technical-overview.md` §10.225（3 处：唯一方案依据、出包段尾巴、遗留③ 结案）、本文 §附录 C 的自引用 1 处、`docs/archive/verification/scripts/netease_artist_probe.py` 头注 1 处。⛔ 死链自检 `grep -rIn "docs/network-artist-full-discography-plan" --exclude-dir=.git --exclude-dir=.superpowers --exclude-dir=build --exclude-dir=output --exclude-dir=logs_temp .` → 只应剩**本行这一条自指**（命令串本身），其余文件 **0 命中**。源码里的 `艺人全量方案 §x.y` 是**角色引用**而非路径，随文档同迁、无需改写。
> ⚠️ 未进 `CHANGELOG.md`：本轮是**纯文档移动 + 验收结案**，按本仓规矩（文档改动不入 CHANGELOG）不登记；代码事实已在 `## [v2.38.7]` 段内。

## 第九轮（2026-10-14，2 个）—— 已落地并打 tag `v2.38.9`，两处真机返工已验收

| 文档 | 判定依据 |
|---|---|
| `e46-tripod-fog-plan.md` | ✅ **已落地**：§十五 K1–K8 已按本文全量移植为 Kotlin（5 个主文件 6748 行 / 8 个测试类 49 例 + 81 探针 5963 字段逐位对账），首版随 **v2.38.8** 出包；两处真机返工（K9 ① 巨影填色回屏幕空间、② 进入首帧改预算制分帧烘焙）由 owner 真机判「好，就这样吧，测试效果能接受」（2026-10-14），随 **v2.38.9 / `versionCode 178`** 推送并打附注 tag（指向 `7689c3e`，与 TV 上验收通过的那份 APK 代码同源）。移动方式 = **`git mv`**，正文 §一～§十六 + 附录 A/B **一字未删**，只在顶部加归档状态头 |
| `e46-tripod-fog-preview.html` | 同一功能的 **K0 浏览器原型**（纯 Canvas 2D，自带 31 条自检 T1–T31 全含真负样本）。⚠️ **它不是"历史草案"：它是 `docs/archive/verification/scripts/e46_golden_gen.py` 的再生源** —— `TripodFogGoldenTest` 的 81 探针 / 5963 字段期望值由本文件在 `vm` 沙箱里跑出。改过被对账的数学 ⇒ 重跑该脚本并提交再生结果；⛔ 手抄一个数进测试 = 偏差 E44 D24 的重演。移动方式 = **`git mv`** |

**归档时仍未闭合的事项**（⛔ 逐条留痕，归档 ≠ 已解决；恢复推进先答这三条）：

1. ⏳ **§十四 4 的 ①–⑬ 共 13 条真机验收项未执行**（已判的只有 ⑭「进入首帧必出画面」与 K9 ①②）⇒ E46 整体仍按**「已实现、未验收」**记账。风险最高的三条：**R8 铺屏**（最坏帧 5.412/5.414 屏 vs E44 已验收的 4.306，第一刀砍甩镜段建筑运动模糊，⛔ 不动雾密度与人群）、**⑥ LOW 档实测 fps**（用 `docs/archive/verification/scripts/moonlit_fps_sample.py --tier LOW\|MED\|HIGH` 读屏上角标；API 22 上 `gfxinfo framestats` / `SurfaceFlinger --latency` 均无效）、**⑩ 三套剧本轮换**需盯满 6 圈 ≈ 9 min。
2. ⛔ **§十六 R32「行进路线能否全随机」= 只评估、不排期**，且两条前置未闭合：T12 只验过 seed 1..200；`applyRoute` 80 次抽签全失败时**没有兜底**（保留第 80 个非法解 ⇒ 视觉断裂而非崩溃）。⛔ 不推翻 R29 的「运行时随机生成骨架」裁决。
3. ⚠️ **`[v2.38.8]` 与 `[v2.38.9]` 两段 CHANGELOG 均已随 tag 冻结** ⇒ 上述验收项暴露的任何缺陷只能开 `[v2.38.10]` 修，⛔ 不回写已冻结段（见 [[feedback-changelog-scope-and-frozen-release]] 的仓内同条纪律）。

> ⚠️ **归档 ≠ 权威转移**：本篇仍是 E46 的**唯一方案依据**（所有者 2026-10-11 口径），`app/src/**` 里 16 处 `docs/archive/e46-tripod-fog-*` 的 KDoc 引用、§10.228/§10.230/§10.231 与 5 份验证脚本都指向它。⛔ 不要因为"已归档"就当历史草案处置。
>
> 引用改写 **18 个文件 / 33 处 `docs/` 前缀文本 + 4 处脚本路径常量**：10 个 Kotlin（`AppSettings` + 5 个 renderer 主文件 + 4 个测试类）、`docs/technical-overview.md`、两份被移动文档自身的互引，以及脚本常量 `e46_golden_gen.py:32` / `e46_fog_harness.js:14` / `e46_phase_kinematics.js:20` / `e46_visual_driver.mjs:21`（这三份 js 里的 `filename: 'e46-tripod-fog-preview.html:script'` 是 **vm 栈标签**、非路径，按原样保留）。
> ⛔ 死链自检：`git ls-files -z | xargs -0 grep -In "docs/e46-tripod-fog"` → **只应剩本行这一条自指**（命令串本身），其余文件 **0 命中**（2026-10-14 实测；`logs_temp/` 与 `.workbuddy/` 是 gitignored 的一次性草稿，按规矩不追改，故按 `git ls-files` 扫而不是全树 grep——全树还会撞进 `output/` 里的浏览器 profile）。
> ✅ 再生脚本已在新路径**实跑通过**：`python docs/archive/verification/scripts/e46_golden_gen.py` 重写 `TripodFogGoldenTest.kt` 后 `git diff` 为空 ⇒ 路径迁移未改变任何对账数字。
> ⚠️ 未进 `CHANGELOG.md`：本轮是**纯文档移动 + 归档登记**，按本仓规矩（文档改动不入 CHANGELOG）不登记。
> `docs/` 根在本轮之后**入库的 Markdown = 3 篇**（`git ls-files docs | grep -E '^docs/[^/]+\.md$'` 实测：`technical-overview.md` 活文档、`conventions-adaptive-ui.md`、`visualizer-effects-list.md` 现役名册；**入库的原型 html = 0 份**）；另有另一在办会话新增、**尚未入库**的 `blackhole-*` / `e45-orbital-*` / `e47-snow-cedar-preview.html`，计数以实测为准，不在此定论。

## 第十轮（2026-10-14，1 个）—— 已落地并打附注 tag `v2.38.10`，真机 7 步验收全过

| 文档 | 判定依据 |
|---|---|
| `karaoke-picker-tv-focus-fix-plan.md` | ✅ **已落地**：所有者真机反馈的 K 歌页升降调 / 变速两处问题（「确定后遥控器再也选不中这两个按键」「调原调必须确定才生效、无法即时试听」），走方案 A + A4-①（`←`/`→` 换档即时试听、箭头降级为装饰）+ D2（preview 即时改状态、只有确定才落盘）+ D3（只改 K 歌页，MTV 页不动）。首轮真机 7 步里第 4 步红（`↑` 越出弹窗、`↓` 正常）⇒ 返工为「弹窗内三个节点逐方向显式钉 `up/down/left/right`」，同日复验 **7/7 通过**。随 **v2.38.10 / `versionCode 179`** 提交（`c850d35`）并打附注 tag。移动方式 = **`git mv`**，正文 §一～§7.3 **一字未删**，只在顶部加归档状态头 |

**归档时仍未闭合的事项**（⛔ 逐条留痕，归档 ≠ 已解决）：

1. ⏳ **高质量分离（Demucs）模式下 preview 是否即时可闻**未验证 —— 该路径走模型输出文件而非播放器
   `setPitch` / `setSpeed`，本案真机第 3 步判的是快速模式。若反馈"高质量模式下调音没反应"即为它。
2. ⚠️ **F-F 已被真机证伪**（API 22 创维 `9R54_G8S` 上 `AudioTrack.setPitch` 确实会变调）⇒ 不必另立 issue；
   但该结论只在**这一台**老电视上证过，其他 API 22 机型未覆盖。

> ⚠️ **归档 ≠ 权威转移**：本篇仍是本次修复的唯一方案依据，`app/src/**` 里 2 处 KDoc 引用
> （`KaraokePickerTvFocusScanTest` / `VocalPitchSpeedPreviewCommitTest`）与 §10.232 都指向它。
> ⛔ 尤其不要把 §7.1 那张钉位表"优化"回 `focusGroup + exit` —— 它已被真机证明只兜得住 DOWN；
> 要替换必须先真机重跑 §5 第 4 步。
>
> 引用改写 **3 个文件 / 3 处 `docs/` 前缀文本**：2 个测试类 KDoc + `docs/technical-overview.md` §10.232。
> ⛔ 死链自检：`git ls-files -z | xargs -0 grep -In "docs/karaoke-picker-tv-focus-fix-plan"` →
> **只应剩本行这一条自指**（命令串本身），其余文件 **0 命中**（2026-10-14 实测）。
> ⚠️ 未进 `CHANGELOG.md`：本轮是**纯文档移动 + 归档登记**，按本仓规矩（文档改动不入 CHANGELOG）不登记；
> 代码事实已在 `## [v2.38.10]` 段内（该段随 tag 冻结，后续缺陷只能开 `[v2.38.11]`）。
> `docs/` 根在本轮之后**入库的 Markdown = 3 篇**（`technical-overview.md`、`conventions-adaptive-ui.md`、
> `visualizer-effects-list.md`）；另有另一在办会话新增、**尚未入库**的 `blackhole-*` / `e45-orbital-*` /
> `e47-*` 方案与原型，计数以 `git ls-files docs | grep -E '^docs/[^/]+\.md$'` 实测为准。

## 回滚

归档为**纯移动**（`git mv`，历史保留），未删除任何内容。整体回滚：

```bash
git checkout -- docs CHANGELOG.md AGENTS.md
git clean -fd docs/archive docs/articles   # 若需连新目录一并撤销
```
