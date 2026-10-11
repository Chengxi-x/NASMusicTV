# 网络艺人全量歌曲开发方案（突破统一搜索 30 首上限）

- **状态**：✅ **已裁决、可开发** —— owner 于 **2026-10-10** 对 Q1–Q9 逐条拍板（裁决记录与对方案的直接影响见 **§9**），Q3 要求的界面/改动量评估见 **§10**，任务分解与逐文件代码见 **§11**。尚未写任何 Kotlin
- **日期**：2026-10-10
- **目标模块**：`backend/network/netease/`（新增网易免鉴权接口层 `NeteaseOpenApi`）、`backend/network/`（`NetworkSongId`/`NetworkArtistTypes`/`NetworkArtistExpansion`/`PlayabilityProbe`/`CandidatePicker`/`ReplaceChain` 六个新件 + `MetingApiService` 搜索改网易优先与可播判据做实 + `NetworkMusicService` 接口扩展 + `NetworkMusicManager` 路由/负面缓存/同源重搜）、`backend/SearchAggregator.kt`（一行超时）、`ui/viewmodel/`（**不新增 VM**，改 `SearchViewModel` 与 `PlayerViewModel`）、`ui/screens/library/SearchTab.kt` + `ui/components/song/UnifiedSongRow.kt`（显示侧）。逐文件清单见 §11 的 File map
- **数字来源**：`docs/archive/verification/scripts/netease_artist_probe.py`（可复跑仪表）。本文数字**只允许**抄自以下四轮已存档日志（UTF-8 + LF，随文档入库；`logs_temp/` 里那些是临时件，会被清理）。目录 `docs/archive/verification/netease_artist_probe/`：
  - **22:13 全量轮** `--multi 20 --cross --platforms` → **74 断言 / exit 0 / FAIL 0 / WARN 8** → `probe_v2_full.log`：§2 A/A2/B/C/D/F/G/H/I 与 §4 的可播率
  - **23:28 字段轮**（默认组，keyword=陈奕迅）→ **41 断言 / exit 0 / FAIL 0 / WARN 5** → `probe_v3_fields.log`：§2 C 组「`type=1` 字段覆盖」（Q1 打标的可行性判定）
  - **23:43 延迟轮** `--latency 8 --pacing 1.2` → **46 断言 / exit 0 / FAIL 0 / WARN 5** → `probe_v4_latency.log`：§2 L 组 → §10.3 的 5s 预算分配；**并复现了 23:28 轮的字段覆盖结论**（`privilege.st` 0/100、`album.picUrl` 0/100 两轮一致）
  - ⚠️ **唯一例外**：§2 E 组（判据验证）来自同日 21:32 的 `--play 12` 轮、22:13 轮未复跑，引用时注意
  - ⛔ **改任何结论必须重跑脚本再改文档**，不许手抄、不许凭记忆
- **版本**：**v1.7** — v1.5：按 owner 三连问补测 F·G·H 组，"8.7% 可播率"作废替换为实测 15.0%，`privilege.st` 降级为"仅作分布参考"，新增 §2 I 组。v1.6：新增 §3.8 播放降级链 + R10 + P4 + Q9。**v1.7：owner Q1–Q9 裁决入账（§9）**、新增 §2 C 组字段覆盖与 L 组延迟分布、答复 Q3（§10 界面影响与改动量）、按裁决把本文改写为**可开发粒度**（§11 九个 Task、逐步骤真实代码与测试，Task 9 是全量回归 + 文档同步）、附录 A 换成完整可复跑命令表、附录 C.3 记 v1.7 的八条自我更正。修订说明见 §附录 C
- **前置阅读**：`docs/archive/multi-bitrate-playback-download-plan.md`（音质档位与直链缓存语义）、`docs/archive/network-music-upgrade-plan.md`

---

## 0. 摘要：三个问题三个答案

| # | 问题 | 答案 | 证据 |
|---|---|---|---|
| 1 | 统一搜艺术家名为什么最多 30 首？ | **不是本 app 的限制**。Meting 请求从不带分页参数，而三个预设公共端点都把「网易云搜索第 1 页 = 30 条」写死在 PHP driver 里，`page` / `limit` / `offset` 全部被忽略（同一批 30 个 id） | §2 A 组 |
| 2 | 怎么拿到这个艺人的全部歌曲？ | **能拿到**。直连网易两个无鉴权开放接口：`type=100` 艺人搜索拿 `artistId`，`GET /api/v1/artist/songs` 拿全量（实测周杰伦 **566 首 / 6 页 / 零重复**）；播放、歌词、封面**完全复用现有 Meting 链路，零新代码** | §2 C+D 组、§3 |
| 3 | 多了怎么展示，少了怎么办？ | **复用项目已有分页机制**（`SongsPagingState` + `SongsTab(onLoadMore)` + 触底提前 20 项），`pageSize=100`；`total ≤ 100` 时 `hasMore=false`，load-more 分支天然不渲染，即"少则不分页"，不需要额外分支 | §2 D 组、§3.4–3.6 |

**但必须先看清第 4 个数字**：随机抽样实测，全量列表里**只有 15.0%（3/20）的曲目在公共 Meting 端点上真的能播**，且**三个预设端点结论完全一致（0/20 首出现分歧）**。这不是实现细节，而是本方案最大的产品风险，单列 §4 与 Q1。

### 0.1 owner 三连问的答案（v1.5 新增，证据 F/G/H 组）

| # | 问题 | 答案 |
|---|---|---|
| 4 | 可播率是否覆盖了设置里的**所有端点**？播放时是否会降级测试所有端点？ | **测量覆盖了三端点，结论一致**（redcha / qijieya / mikus 各 3/20 = 15.0%，逐首交叉 0 处分歧）。**代码确实会遍历所有端点**（`MetingApiService.kt:257-282` 端点循环 × `QualityTiers.fallbackChainOf` 档位链），**但对真正需要它的曲目从不生效**——假 302 在 `:271-276` 就 `return` 成功了。见 §2 F 组、§2 假信号 1、R4；**整条"不可播→重搜换源"降级链的现状核对与改造方案见 §3.8 + R10** |
| 5 | 不可播的歌能否从同端点的**其他来源**（QQ/酷狗等）播放？ | **基本不能**，两道闸门：① **端点侧**——三预设里只有 qijieya 认 `server=kugou/migu`，redcha/mikus 直接拒（§2 G1）；② **app 侧**——`platform` 是全局设置而 `Song` 不带平台字段（`Song.kt:37-41`），同一数字 id 换平台会被当另一首歌解析（实测串歌 0，即只会失败不会放错）。跨平台同名重搜的实测救回率 **1/5**（§2 G2） |
| 6 | 网易公开接口能拿全量，QQ 等有没有同类接口？ | **酷狗有**且质量很好（免鉴权歌手搜索 + 真分页，`total=1845`、跨页零重复），但它的 hash 经公共端点解析 **0/4 可播** → **列表可用、直链不可用**。**QQ 需要登录态/票据**（`req_0.code=500003`、songs=0），kuwo 与 migu 原生接口免鉴权不可用。所以"艺人全量"这条端到端路径目前**只有网易走得通**，酷狗只能作 P3 的"列条目"扩展（§2 H 组、§5 P3） |

> 📌 问 4/5/6 的完整结论汇总成一张速查表：**§2 I 组**（纵向 = Meting 的 6 个 `server` 来源 × 横向 = 免登录自有 API / 公共端点 / 自建端点，格子 = 是否支持播放）。

---

## 1. 现状取证：30 首的完整链路

```
曲库搜索框 (LibraryScreen)
  └─ SearchViewModel.searchSongsOnServer()            SearchViewModel.kt:125-178
       └─ SearchAggregator.search(filterMode=PRECISE) SearchAggregator.kt:122
            ├─ NAS   backendAdapter.searchSongs()      :150-181（15s 超时）
            ├─ 网络  networkMusicManager.search()      :183-201（5s 超时）  ← 30 首来自这条分支
            ├─ 百度  baiduService.search()             :203-238（8s）
            ├─ Jamendo jamendoService.search()         :240-258（5s）
            └─ 本地  localMusicRepository.search()     :261-288（2s）
                 └─ NetworkMusicManager.search()       NetworkMusicManager.kt:113-135（按源 fallback）
                      └─ MetingApiService.search()      MetingApiService.kt:136-162（按端点 fallback）
                           └─ searchWithEndpoint()      :191-213
                                URL = "$endpoint?server=$server&type=search&id=$kw"   :194  ← 无 page / 无 limit
```

**关键事实（逐行核对）**：

| 位置 | 事实 | 对本方案的意义 |
|---|---|---|
| `MetingApiService.kt:194` | 搜索 URL 只有 `server/type/id` 三个参数 | app 侧**没有**任何可拧的"每页条数"旋钮 |
| `NetworkMusicService.kt:31` | 接口签名有 `limit: Int = 0` | 这是**空头承诺**——`MetingApiService` 收下 `limit` 后从不使用（`JamendoService.kt:55` 才真的用）。不要误以为改这个参数就能突破 30 |
| `SearchViewModel.kt:55-78` | 既有绕行手段「换一批」：在关键词后追加 24 个变异后缀（`翻唱/live/现场/伴奏/钢琴/remix…`），跨批次按（歌手,歌名）去重，注释即写明"**突破单次搜索 30 首上限**" | 说明这个坑早被发现，但解法是"骗搜索词"而非"拿全量"——能出新歌，永远拿不到"这个艺人的全部" |
| `MediaLibraryTree.kt:104` `MAX_SEARCH_RESULTS=50` | **不在**统一搜索路径上，它属于 `PlaybackService` 的 Android Auto 媒体浏览树 | ⚠️ 别把它当统一搜索的天花板；但**若日后要把艺人歌曲喂给 Auto 浏览树，50 会二次截断**（见 §7 R6） |

---

## 2. 实测数据（脚本再生。**主体** = 2026-10-10 22:13 全量轮：A→A2→B→C→D→F→G1→G2→G3→H，exit 0 / FAIL 0 / WARN 8 / 共 74 断言；**C.1 字段覆盖** = 23:28 轮（41 断言 / FAIL 0 / WARN 5）；**L 组延迟** = 23:43 轮（46 断言 / FAIL 0 / WARN 5）；**E 组除外**，见该组标注；**I 组是 G/H/F 的汇总速查表，不含新测量**）

### A 组 —— 30 首上限归因

| 端点 | 单页条数 | `page=2` 集合重叠 | `limit=100&offset=30` 集合重叠 |
|---|---|---|---|
| meting.mikus.ink | **30** | **1.00**（30/30 同一批） | **1.00**（同一批） |
| meting.api.redcha.cn | **30** | **1.00**（30/30 同一批） | **1.00**（同一批） |
| api.qijieya.cn/meting | **30** | **1.00** | **0.30**（⚠️ 唯一一条 WARN，见下） |

> 结论：**参数被端点无视**，客户端拿到的永远是同一批 30 首。判据用集合重叠率而非顺序相等——上游热榜会重排同一批曲目，用顺序相等会把"重排"误判成"换页"（脚本旧版犯过，已在 §附录 B 记录）。
>
> ⚠️ **qijieya 的 0.30 不能读成"它支持分页"**。该端点当日 netease 上游多次返回 `[]` 或 5xx，`inter=30/100` 说明 30 首原样在内、多出的 70 首是抖动/上游换批所致；它的 `page=2` 仍是 1.00。若日后要为某个端点做分页，必须先用稳定间隔复测，**不能拿这一条 WARN 当可行性依据**。

### A2 组 —— 跨端点结果集重叠（"遍历多端点取并集"能不能绕过 30）

| 对比 | Jaccard | 交集 / 并集 |
|---|---|---|
| mikus vs redcha | **0.88** | 28 / 32 |
| mikus vs qijieya | **0.94** | 29 / 31 |
| redcha vs qijieya | **0.88** | 28 / 32 |

> **并集只有 31~32 首**（三端点各自 30 首，并集几乎没扩大）。这直接否掉一条看起来很自然的"绕过 30"思路：**多端点轮询取并集不是解法**——它们走的是同一个网易云搜索上游的第 1 页，换端点只提高命中率，不扩样本。⚠️ 这里我一度说过"redcha 与 qijieya 重叠 0.00、并集 60 首"，那是错的，正确数字以本表为准（§附录 C）。

### B 组 —— Meting 的 `type=artist`（零新端点的次优路径）

| 指标 | 实测 |
|---|---|
| 单页条数 | **50**（比 `search` 多 20，仍是死页） |
| `page=2` 集合重叠 | **1.00** → 无分页 |
| 正自检（同请求重取） | overlap **1.00** ✓ 仪表稳定 |
| 负自检（`search` vs `artist`） | jaccard **0.60** ✓ 判据不是恒真 |

> `type=artist` 需要 **artistId**，而 Meting 的 `search` 响应**从不暴露 artistId**（`parseSongs` 只取 title/author/pic/url/album，`MetingApiService.kt:408-448`）。所以它单独用不起来——只能算 30→50 的小改良。

### C 组 —— 艺人 ID 发现（网易原生 `POST /api/search/get`）

| 指标 | 实测 |
|---|---|
| `type=1&limit=100` 返回 | **100 条**（原生接口支持大页） |
| `type=1&offset=100` | 与首页 jaccard **0.00** → **真分页** |
| 响应含 `artists[].id` | 100 条里 **54** 个不同 artistId |
| `type=100`（艺人搜索） | `artistCount=83`，首选 `id=6452 musicSize=568 albumSize=41` |

**C.1 `type=1`（歌曲搜索）响应的字段齐备度**（23:28 字段轮，keyword=陈奕迅，n=100；23:43 延迟轮复现同结果）

| 字段 | 覆盖 | 对方案的意义 |
|---|---|---|
| `duration` | **100/100** | 列表时长可直接显示 |
| `album.name` / `album.id` | **100/100 / 100/100** | 专辑名与专辑 ID 可用（Q6 平铺不受影响） |
| `artists[].name` | **100/100** | 艺人名可直接显示，多人名需拼接 |
| `fee` | **100/100** | 存在但**无判别力**（§2 E 组已证） |
| **`privilege.st`** | **0/100** ⛔ | **该查询形态不返回可用的 privilege** → Q1 的"可播置顶 + 打标"**无法建在 `type=1` 上** |
| **`album.picUrl`** | **0/100** ⛔ | 无封面直链 → 列表封面需另取（见 §10.2 的封面决策） |
| 负自检：不存在字段 `__no_such_field__.x` | **0/100** ✓ | 证明覆盖率统计不是"恒真"，上面两个 0 是真 0 |

> ⚠️ **这是 v1.7 新增的关键约束**：`type=1` 与 `/api/v1/artist/songs` 是**两种不同的响应形态**——后者 §2 D 组实测 `privilege` 566/566、`album.picUrl` 566/566，前者这两项 **0/100**。Q1（C+B：可播置顶 + 打标不隐藏）**只能落在艺人分支上**，普通关键词搜索分支没有打标依据。设计见 §10.2 与 §11 Task 2（解析侧）/ Task 8（UI 侧）。

> 该端点**本 app 已在用**——`LyricsNetworkProvider.kt:261-268` 取歌词就 POST `/api/search/get`。即"直连网易"不是新增架构先例，只是把既有做法从歌词模块挪一个用途。

### D 组 —— 全量列表：`GET /api/v1/artist/songs?id=6452&total=true&limit=100&offset=N`

| 指标 | 实测 |
|---|---|
| `total` | **566** |
| 逐页 | `[0,100] [100,100] [200,100] [300,100] [400,100] [500,66]` → 6 页取完 |
| 跨页重复 | **0**（raw 566 = unique 566） |
| `more` 标志 | `[T,T,T,T,T,F]` 末页转 false |
| `album.picUrl` 覆盖 | **566/566**，且是**直链**（可直接给 Coil，不依赖 Meting 的 302 端点） |
| `duration` 覆盖 | **566/566**（毫秒） |
| `privilege.st` 分布 | `{-200: 517, 0: 49}` |
| `fee` 分布 | `{0: 522, 1: 35, 8: 9}` |
| ⚠️ `limit=200` 异常 | **三轮存档日志三种值**：`total@200` = **241 / 194 / 1070**（22:13 / 23:28 / 23:43 轮），返回条数 = **200 / 194 / 200**，且三轮 `first100_is_subset` **全为 False** → `limit>100` 时连 `total` 与条数都不可信，**必须锁 `limit ≤ 100`**（该断言每轮记 WARN，判据即"不等价即钳制"）。v1.4 曾记过 `total=332`，那轮日志未存档，本文不再引用 |

### E 组 —— 判据验证（分层抽样 6+6，判据=跟完整条重定向链后的 `Content-Type`）⚠️ 数字来自 21:32 `--play 12` 轮，22:13 全量轮未复跑

| `privilege.st` | 样本 | 判定 | 终态 |
|---|---|---|---|
| `-200` | 6 | **全部 DEAD** | 落到 `https://music.163.com/404`，`text/html`，107 KB |
| `0` | 6 | **全部 PLAYABLE** | `audio/mpeg`，206 分段直链 |

- **一致性 12/12** → 判据本身有效（跟链后 `Content-Type` 能干净二分可播/不可播）
- **`fee` 无判别力**：可播组 fee ∈ {0,1,8}、不可播组 fee={0}，有交集 → ⛔ **绝不能用 `fee` 做过滤**
- ⛔ **本组不给可播率**。样本是**人为配平的 6+6**，"50%"没有任何意义；v1.4 曾据此再用 `st` 分布外推"49/566 ≈ 8.7%"，那是**用字段推断冒充测量**，已被 F 组的随机抽样推翻（可播率一律以 F 组为准）。
- ⚠️ **`privilege.st` 由此从"可靠预测字段"降级为"仅作分布参考"**：E 组只证明了小样本上的方向性相关（12/12），F 组的随机 20 首里有 3 首可播，而按 `st==0` 只应有 49/566≈8.7% → 期望 1.7 首。两者在小样本下并不构成显著冲突，但**"用 `st` 一个字段决定过滤/排序"这件事，证据强度不足以支撑 Q1 的 (a) 路线**。

### F 组 —— 随机抽样可播率矩阵（回答"可播率是否覆盖所有端点"）

**20 首随机抽样**（`random.sample`，⛔ 不再用分层），对三个预设端点逐一跟完整重定向链判定，每首每端点**重试 2 次去抖**：

| 端点 | 可播 | 可播率 |
|---|---|---|
| meting.api.redcha.cn | 3/20 | **15.0%** |
| api.qijieya.cn/meting | 3/20 | **15.0%** |
| meting.mikus.ink | 3/20 | **15.0%** |

- **逐首交叉：0/20 首在不同端点上结论不同** → 三端点判定**完全一致**。这印证了 A2 的推断：**公共端点走同一上游，换端点不能改变可播性**。可播率的天花板是**网易侧授权**，不是代理端点。
- **17/20 首在所有可用端点上都不可播**。
- 20 首是小样本，点估计误差大，**不宜把 15.0% 引用成精确数字**；产品决策按"随机抽到的歌，绝大多数播不了（≤ 约 1/6）"来读。

### G 组 —— 换 `server` 平台能否救回（回答"能不能从其他来源播"）

**G1：端点 × 平台支持矩阵**（`type=search`，先测矩阵再看 G2，否则结论会被"端点根本不认这个平台"污染）

| 端点 | netease | tencent | kugou | kuwo | migu | xiami |
|---|---|---|---|---|---|---|
| mikus | 30 | ✗ 非数组 | ✗ | ✗ | ✗ | ✗ |
| redcha | 30 | ✗ | ✗ | ✗ | ✗ | ✗ |
| qijieya | 30 | `[]` (n=0) | **30** | `[]` | **30** | `[]` |

> **三个预设端点里只有 qijieya 认非网易平台**，且只认 kugou 与 migu。mikus/redcha 对这些 `server` 值**返回的不是数组**（脚本判据：非数组即不认该平台；诊断轮里 redcha 的形态是 HTTP 400 + `{"status":400,"message":"server 参数不合法"}`）。**"换来源"在端点层就先被拦掉 2/3。**

**G2：拿 5 首网易侧 DEAD 的曲目去 kugou / migu 同名重搜并测可播**

| 平台 | 同名命中 | 其中可播 |
|---|---|---|
| kugou | **5/5** | **0** |
| migu | 2/5 | **1** |

> 救回率 **1/5**，且这 1 首还得端点恰好支持（只有 qijieya 支持）。**跨平台救援存在，但幅度太小，不足以作为可播率的解法**——它只改变"要不要给用户一个手动按钮"的取舍（Q1 附带项）。

**G3：同一数字 id 换 `server` 解析是否串成另一首歌** → **串歌数 = 0**。含义有两面：① 好消息是切平台不会播出错歌；② 因此**换平台对旧 id 的唯一效果就是失败**，`Song` 不加平台字段也不会造成串歌事故——但也正因 `Song` 不带平台（`Song.kt:37-41`），**当前结构无法把"这首来自 kugou 的 id"回溯保存**。

### H 组 —— 其他平台的原生免鉴权接口（回答"QQ 等有没有同类可搜接口"）

| 平台 | 接口 | 实测 | 结论 |
|---|---|---|---|
| **kugou** | `mobilecdn.kugou.com/api/v3/search/singer` | http 200，`singerid=3520` | ✅ **艺人发现可用** |
| **kugou** | `/api/v3/singer/song`（3 页） | `page1=30 page2=30`，**jaccard 0.00**（真分页），`total=[1845,1845,1845]` 稳定，raw 90 = uniq 90 | ✅ **全量列表可用**，规模比网易更大（1845 首） |
| **kugou** | hash 经 Meting 端点解析直链 | **0/4 可播** | ⚠️ **列表可用、直链不可用** |
| tencent | `musicu.fcg` 免鉴权 | http 200 但 `req_0.code=500003`，`songs=0` | ❌ **需登录态/票据** |
| kuwo | `search.kuwo.cn/r.s` | http 200，`TOTAL=None` | ❌ 免鉴权形态不可用 |
| migu | 原生 JSON | http 200 但**不可解析** | ❌ |
| meting | `type=artist` × netease/kugou/migu | 全部 `n=-1` | ❌ 用艺人名当 id 探不到（`type=artist` 只认真数字 id，见 §2 B） |

> **网易-shaped 的设计（艺人发现 + 全量真分页）在酷狗上同样成立**，这是 P3 的真实依据；但酷狗的曲目 hash 经公共端点解析不出可播直链（0/4），所以酷狗全量列表**只有配合"用户自建端点"才有播放价值**。QQ 侧被登录态挡死，短期无解。

### I 组 —— 速查表：纵向 = Meting 的 `server` 来源，横向 = 三条取歌通道，格子 = **是否支持播放**

判据统一为 E/F 组那条：**跟完整条重定向链后，最终响应 `Content-Type` 含 `audio`** 才算"支持播放"（302 本身不算，见假信号 1）。⛔ 本表**不引入任何新测量**，每格都能回溯到 §2 G1/G2/H/F 与存档 log。

图例：✅ 实测可用 · 🟡 实测可用但比例极低 · ❌ 实测不可用 · ❔ **本轮未探测**（不当作结论，只当缺口）。第 ② 列的"支持播放"一律指**经公共端点**，因为 app 现有的播放路径只有这一条（`MetingApiService.kt:261`）。

| `server` 来源 | ① 平台自有**免登录** API（能否搜 / 能否拿艺人全量） | ② 经**公共 Meting 端点**取直链 | ③ 经**用户自建端点** |
|---|---|---|---|
| **netease**（网易云） | ✅ 搜索 `POST /api/search/get`（type=1 n=100、type=100 艺人 83 位）<br>✅ 艺人全量 `GET /api/v1/artist/songs`（total=566，6 页零重复）<br>❔ 这两个接口**只返回列表字段，不含直链**；网易原生播放器 URL 接口本轮**未探测** | 🟡 **可播，但受授权压制**：随机 20 首 → **3/20 = 15.0%**，三端点判定 0 分歧（17/20 全端点 DEAD）。链路形态：端点 302 → `music.163.com/song/media/outer/url?id=X.mp3` → 可播者给 `audio/mpeg`，不可播者再 302 到 `/404` HTML → **所以 15% 就是"免登录 outer 直链"这条路的天花板** | ❓ **未测**（无自建端点样本；F 组三端点同上游，故自建是唯一未验证变量） |
| **kugou**（酷狗） | ✅ 搜索 `search/singer`（singerid=3520）<br>✅ 艺人全量 `singer/song`（**total=1845**、jaccard 0.00 真分页、跨页零重复）<br>⚠️ 同样只给 hash 不给直链 | ❌ **实测 0 可播**（同名重搜命中 5/5、可播 **0**；独立样本 hash 解析 **0/4**）<br>⚠️ 且三端点里**只有 qijieya 认** `server=kugou`，mikus/redcha 返回非数组 | ❓ 未测（同上；这是 kugou 唯一可能的出路） |
| **migu**（咪咕） | ❌ 自有 JSON **不可解析**（http 200） | 🟡 **极窄**：同名重搜命中 **2/5**、可播 **1**（= 全表唯一一次跨平台救回）<br>⚠️ 也只有 qijieya 认 `server=migu` | ❓ 未测 |
| **tencent / qq**（QQ 音乐） | ❌ `musicu.fcg` 免登录返回 **`req_0.code=500003`、songs=0** → **需登录态/票据** | ❌ **取不到样本**：mikus/redcha 非数组；qijieya 认该值但返回 **n=0**（空数组） | ⚠️ **推断为受阻**（未实测）：免登录连"拿到候选曲目"这一步都不成立，端点便无从解析 |
| **kuwo**（酷我） | ❌ `search.kuwo.cn/r.s` 免登录形态 **`TOTAL=None`**，不可用 | ❌ 同上：mikus/redcha 非数组，qijieya 返回 **n=0** | ❓ 未测（前置的免登录搜索已判死，优先级低） |
| **xiami**（虾米） | ❔ **未探测**（H 组未覆盖该平台的自有接口） | ❌ 三端点全部不可用：mikus/redcha 非数组、qijieya **n=0** | ❓ 未测 |

**读法（三行结论）**

1. **"免登录能搜"与"能播"是两件独立的事**，而且第二列普遍比第一列难。网易与酷狗都做到了"艺人全量列表"，但**列表本身不产出可播直链**——网易可播靠端点那条 302（15%），酷狗那条 302 实测 **0/4 + 0/5**。所以"某平台有公开 API"绝不等于"那个平台的歌能播"。
2. **第 ③ 列（自建端点）全表是 ❓**，这不是遗漏而是本轮测量边界：公共端点判定 0/20 分歧，说明可播率的变量在**授权侧**，而自建端点带用户自己的账号，正是唯一没被测过的那一环。P2 的"建议自建端点"提示（§4）押的就是这个未测格。
3. **纵向看，网易是唯一"列表 + 播放"两端都成立的来源**；kugou 只有列表，migu 只有 1/5 的偶然救回，qq/kuwo/xiami 连免登录搜索这一关都过不了 → §5 P3 的排期依据就是这张表。

### L 组 —— 接口延迟分布（23:43 `--latency 8` 轮；决定 Q2「网易优先 → 端点兜底」在 5s 预算里怎么切）

每个桶 8 次采样，`pct()` 用最近秩（小样本不插值装精度）：

| 桶 | n | p50 | p95 | max | 非 2xx |
|---|---|---|---|---|---|
| `netease_type1_search`（`POST /api/search/get`） | 8 | **241ms** | **368ms** | 368ms | 0 |
| `netease_artist_songs`（`GET /api/v1/artist/songs`，limit=100） | 8 | **265ms** | **279ms** | 279ms | 0 |
| `meting_search@mikus` | 8 | 574ms | 701ms | 701ms | 0 |
| `meting_search@qijieya` | 8 | 561ms | 1124ms | 1124ms | 0 |
| `meting_search@redcha` | 8 | **1213ms** | **3027ms** | 3027ms | 0 |

**三条直接读出的结论**：

1. **网易原生比公共端点快一个数量级的尾巴**（p95 0.37s vs 最慢端点 3.03s）→ Q2 的"默认先走网易免登录接口"不只是"能拿全量"，它同时**降低了统一搜索网络分支的超时率**。
2. **`redcha` 单个端点的 p95（3027ms）就已吃掉 5s 预算的 60%**。而现状 `MetingApiService.search()` 的端点 fallback 是**串行**遍历三个端点（`MetingApiService.kt:136-162`）。把三个桶的 p95 相加得上界 **0.70 + 1.12 + 3.03 ≈ 4.85s**（⚠️ 这是"各自尾巴同时出现"的上界，实际联合分位数会更低，但方向不变：**光端点链自己就贴着 `SearchAggregator.kt:75` 的 `NETWORK_TIMEOUT = 5_000L`**），再加网易优先那 0.37s 就越界。超时后 `withHardTimeout` 返回 null → 网络分支**整批变空列表**（`SearchAggregator.kt:192-195`），用户看到的是"网络音乐没结果"而不是"慢"。→ 预算必须显式切分，见 §10.3。
3. 该轮 `meting_search` 三端点**全部 8/8 拿到 30 条、零非 2xx** —— 与 §2 假信号 2「限流是时段性」一致，本轮无限流。

> ⛔ **这组数字只支撑预算分配，不支撑"网易优先能提高可播率"**——可播率仍以 §2 F 组 15.0% 为准，两者是不同维度。

### 三个必须写进认知的假信号

1. **Meting 返回 302 ≠ 能播**（⚠️ v1.5：已从"风险"升级为**代码事实缺陷**，另见 R4）。`resolvePlayUrlDetailed`（`MetingApiService.kt:250-285`）拿到 302 的 `Location` 就 `return` 成功，而那个 Location 常是 `music.163.com/song/media/outer/url?id=X.mp3`，再由网易 302 到 `/404` 的 HTML（107 KB `text/html`）。ExoPlayer 会拿到 200 + `text/html` 才失败。**端点 fallback 对这种"假成功"完全不生效**（`isSuccess` 已为真，不会重试下一个端点）；`NetworkMusicManager.resolvePlayUrlWithCrossSourceFallback`（:390-391）的触发前提也是**解析结果为空串**，假 302 不是空串 → 同源重搜/跨源替换同样不触发；`PlayerViewModel.kt:420-437` 只补一次 1.5s 重试然后直接跳下一首。本方案调研过程中，我一开始也拿"25/25 都 302"当过可播证据，是错的——脚本 E 组的负自检就是为钉死这个错误而存在的。
   - ⚠️ **但修 R4 不会提高可播率**。F 组的交叉矩阵显示三端点判定 0/20 分歧——同一首不可播的歌在每个端点上给出的都是同一个假 302，端点降级**就算正确触发也没有第二个答案可拿**。修它的价值是"失败判定变准"（该跳的立刻跳、该重搜的能重搜、不把 HTML 交给 ExoPlayer 之后才报错），**不是**"能多播几首"。这个区分必须写进 Q4，否则会被当成收益去论证。
2. **公共端点会限流。** 早前测量轮中 mikus 持续 `429 Too Many Requests`（响应体 `retryAfter` 约 969s~3500s），触发阈值为连续二三十次请求；**2026-10-10 22:13 全量轮里 mikus 已全程可用**（A/G1 各 n=30，F 组 20 首跑完无限流）——限流是**时段性的，不可假设稳定存在，也不可假设已解除**。qijieya 的 netease 上游当日多次返回 `[]` 或 5xx（A 组那条 WARN 即此）。redcha 相对稳。**这直接约束了"逐首解析直链"类功能的并发与节奏**，脚本侧对策是把 429 单独分类（绝不当成端点失败）并按 `--pacing` 节流。
3. **"端点抖动"会被读成"接口能力"**（v1.5 新增）。A 组 qijieya 的 `limit/offset` 重叠 0.30、G2 里 migu 只命中 2/5，都属于这类：**同一个查询换一分钟结果就不同**。凡判据依赖"两次请求的集合差异"，必须①同一请求重取的**正自检**先过（本脚本 B 组有）、②多次重试去抖（F 组每首重试 2 次）、③否则只写 WARN 不写结论。

---

## 3. 设计

### 3.1 数据层：新增 `NeteaseOpenApi`

`app/src/main/java/com/nasmusic/tv/backend/network/netease/NeteaseOpenApi.kt`，纯 OkHttp + Gson（与 `MetingApiService` 同风格，复用 `BackendRegistry.sharedDispatcher/sharedConnectionPool`，见 `MetingApiService.kt:65-72` 的 L2 修复，**不要**再自建线程池）：

| 方法 | 请求 | 返回 |
|---|---|---|
| `findArtist(name)` | `POST music.163.com/api/search/get`，form `s/type=100/offset=0/total=true/limit=100` | `NetworkArtistInfo(id: String, name, songCount)?`，取 `artists[0]`（`limit` 与 `searchSongs` 同值即可，实现里复用 `MAX_LIMIT`） |
| `searchSongs(keyword, offset, limit)`（Q2 新增分支） | 同上，`type=1`、`limit=100` | `List<Song>`；⚠️ 该形态**无 `privilege`、无 `album.picUrl`**（§2 C.1）→ 不打标、不给封面直链 |
| `artistSongs(artistId, offset, limit=100)` | `GET music.163.com/api/v1/artist/songs?id&offset&limit&total=true`，头 `Referer: https://music.163.com/` | `ArtistSongsPage(songs: List<Song>, total: Int, hasMore: Boolean, unplayableIds: Set<String>)`，`hasMore` 取响应 `more` 字段；`privilege.st` 566/566 可用 |

⭐ **为什么不需要额外的"艺人引用"类型**（v1.7 取证，来自 §2 C 组 `[PASS] type=1 响应含 artists[].id distinct_artist_ids=54`）：`type=1` 的歌曲结果**本身就带 `artists[].id`**，而 `toSong()` 会把它写进 `Song.artistId`（`Song.kt:19` 既有字段）。于是"关键词是不是艺人"的强命中判定可以**零额外请求**地从搜索首屏的返回歌曲里读出来（§10.2）。`findArtist`（`type=100`）只在**网络段来自 Meting 端点兜底**（那条链路的 `parseSongs` 不填 `artistId`）时才发这**一次**请求。R3 的额外成本因此是"+0 或 +1"，而不是每次 +2。

`limit` **入参即钳制到 1..100**（§2 D 的 `limit=200` 异常），不依赖调用方自觉。
基址**复用既有设置项** `AppPreferences.lyrics.getLyricsNeteaseBaseUrlSync()`（默认 `https://music.163.com`，常量在 `LyricsNetworkProvider.kt:41` `DEFAULT_NETEASE_BASE_URL`，`NasMusicApp.kt:195` 已在用同一个 provider）——不新增设置项，用户改歌词网易基址时本功能一起跟随。
超时：`connect/read = 2_500ms`（实测 p95 0.37s，§2 L 组 → **6.8× 余量**；见 §10.3 的预算切分）。

### 3.2 统一 `Song` 映射（两条契约红线）

```
Song(
  id             = "ntwk_meting_$neteaseSongId",   // ← 红线 1
  title          = s["name"],
  artist         = s["artists"].joinToString(" / ") { it["name"] },
  artistId       = artistId.toString(),             // Song 已有该字段（Song.kt:23）
  album          = s["album"]["name"],
  albumId        = s["album"]["id"]?.toString(),
  coverUrl       = s["album"]["picUrl"],             // 直链，非 302 端点
  durationMs     = s["duration"],                    // 毫秒，566/566 有值
  isNetworkSong  = true,
  networkSource  = "meting",                          // ← 红线 2
  networkId      = neteaseSongId.toString()
)
```

⚠️ 上面是**艺人分支**（`/api/v1/artist/songs`）的形态。**普通搜索分支（`type=1`）只有前几行可用**：`album.picUrl` 与 `privilege` 覆盖为 **0/100**（§2 C.1）→ 该分支 `coverUrl = null`（`UnifiedSongRow` 走既有无封面占位），且**不进 `unplayableIds`**。⛔ 不许为"看起来有封面"去猜一个未实测的 `type=pic` 端点 URL——本文没有该端点对全量集 id 的实测记录。两条分支共用同一个 `toNeteaseSong()` 构造器，以保证**同一首歌在两个分支里 id 逐字符一致**（红线 1）。

- ⛔ **红线 1｜`id` 必须与 `MetingApiService.parseSongs` 完全同格式**（`MetingApiService.kt:434-443`：`"ntwk_${sourceId}_$netId"`）。播放队列、收藏、下载主键（`ntwk_<source>_<id>`）、断点续传索引全部建在这个字符串上；两条链路给同一首歌生成不同 id，等于同一首歌在收藏/下载里变成两个实体。落地必须由护栏测试锁死（§6）。
- ⛔ **红线 2｜`networkSource` 必须是 `"meting"`，不是 `"netease"`。** `NetworkMusicManager` 按 `services[song.networkSource]` **精确路由、不 fallback**（`NetworkMusicManager.kt:183-186`），而服务注册 key 是 `MetingApiService.sourceId = "meting"`（:55）。写成 `"netease"` 的后果是每次点播都命中 `未注册源` 分支直接失败——而且**编译期与 lint 都不会报**。

### 3.3 接口扩展（照既有默认实现风格）

`NetworkMusicService` 加两个带默认实现的方法，与 `searchByDirectory`（:44）/ `getPlaylist`（:114）同级：

```kotlin
/** 艺人发现：关键词强命中某艺人时返回其信息；不支持艺人概念的源恒返回 null */
suspend fun findArtist(name: String): NetworkArtistInfo? = null

/** 艺人作品分页。返回 null = 取页失败/不支持；空页且 hasMore=false 是合法的"翻到底" */
suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int = 100): ArtistSongsPage? = null
```

两个返回类型都**新增在 `backend/network/` 包内**（`NetworkArtistInfo(id: String, name: String, songCount: Int)`、`ArtistSongsPage(songs, total, hasMore, unplayableIds)`），不复用 `data/model/Artist.kt`——那个模型的 `id` 属于 NAS 后端命名空间，与网易的 `6452` 混用会让"同一 id 在两个来源指向不同的人"成为可能（与 R8 同源教训）。返回 `ArtistSongsPage` 而非 `List<Song>` 是因为 §3.4 的 `hasMore` 必须由接口的 `more` 字段驱动，`List` 装不下这个信息（`unplayableIds` 同理，Q1 的打标集也装不进 `List`）。

`MetingApiService` 覆写并委托 `NeteaseOpenApi`；当 `serverProvider() != "netease"` 时直接返回 `null` / `emptyList()`（本期只覆盖 netease，见 §5 P3）。`NetworkMusicManager` 加同名转发，按 `defaultSource` 路由，**不 fallback**（与 `resolvePlayUrl/resolveLyrics` 同语义；fallback 只属于 `search`）。

默认实现的价值：Jamendo / 百度网盘 / 本地源零改动、不会因"不支持艺人"而报错——这正是 `resolvePlayUrl(song, quality)` 默认忽略 quality 的同一套哲学（:66）。

### 3.4 分页状态：复用 `SongsPagingState`

`data/model/SongsPagingState.kt:6-12` 现成字段够用（`songs / totalCount / isLoading / hasMore / currentPage`）。**唯一语义修正**：艺人列表的 `hasMore` 应取 **接口的 `more` 字段**或 `loaded < total`，而不是照抄 `MainViewModel.kt:1143` 的 `batch.size == pageSize`——后者在"末页恰好满 100"时会多跑一次空请求，而网易直接给了 `more=false`，没必要猜。

### 3.5 状态层：`NetworkArtistExpansion`（由 `SearchViewModel` 组合，不新建 Screen/VM）

Q2 裁决"直接用现在的统一搜索入口" → **不新增 `Screen`、不新增独立 `*DetailScreen`**，艺人的全量分页是"这一次网络搜索"的一种形态，状态属于搜索域。但 `SearchViewModel` 已 359 行且 R-1 拆分刚做过，⛔ 不要再往它里面堆字段——把新增状态装进一个纯 Kotlin 类 `NetworkArtistExpansion`（无 Android 依赖，直接 JVM 单测）：

- `expand(keyword, networkPart)`：先用 `type=1` 结果**自带的** `Song.artistId` + `Song.artist` 做**强命中判定**（复用 `ArtistSplitter.normalizeKey` / `containsArtistWithKey`，⛔ **不新建匹配器**，见 §10.2）；首屏 100 首里没有一位艺人名与关键词归一化相等时，才发**一次** `findArtist`（`type=100`）兜底 → 命中则记住 `artistId / total` 并拉第一页
- `loadMore()`：`offset = 已加载条数`，`hasMore` 取接口 `more`（与 §11 Task 4 的签名一致；VM 侧由 `SearchViewModel.loadMoreArtistSongs()` 包一层）
- `playableFirst(songs, unplayableIds)`（internal 纯函数）：**仅艺人分支**做 `st==0` 置顶（Q1 的 c），普通搜索分支原序
- `unplayableIds: Set<String>`：**仅艺人分支**填充（`privilege.st != 0`），供 UI 打标（Q1 的 b）
- 同名词结果缓存 `artistId`（翻页不重跑 `findArtist`）；`reset()` 换词即重置（`SearchViewModel.clearSearch()` 必须调它，对应 R7"切艺人即重置"）

⚠️ `unplayableIds` 的两个约束：① **只能用于灰显/角标，⛔ 不可用作过滤依据**（`st` 已降级为"仅作分布参考"，§2 E；把它当过滤条件就是 Q1 落选的 (a) 路线）；② 普通搜索分支与 Meting 兜底分支**不填这个 Set**（§2 C.1 字段覆盖 0/100）——"没角标"不等于"能播"。

⚠️ **不要**把 `unplayableIds` 塞进 `Song`：`Song` 是跨模块序列化载体（Gson + `data.model` keep 规则，`resolvedQuality` 注释还专门声明"不落盘"），为一个 UI 徽标扩字段会牵动收藏/下载/歌词的既有语义。用旁路 Set 传。

### 3.6 UI 与"少则不分页"（按 Q2 裁决 = **就地扩展统一搜索 Tab，不新建页面**）

- ⚠️ **v1.7 修正一处引用错误**：`SongsTab` 的真实路径是 `ui/screens/library/browse/SongList.kt`（本文此前写作 `browse/SongList.kt`，且把"回顶 BACK"记在它头上——**回顶不在 `SongsTab` 里**，它由 `MainActivity.kt:70` 的 `listBackHandler`（Level 1.5）+ 各列表自行注册 `LocalListBackHandler` 实现）。
- **`SearchTab` 已经注册了 Level 1.5 回顶**（`ui/screens/library/SearchTab.kt:78-97`，`listState.scrollToItem(0)` + 焦点回第一行）→ 本期**不需要**新建页面就能满足验收项 5 的"BACK 逐级回顶"，五级 BACK 不受影响。
- **唯一缺失的是触底续页钩子**：`SearchTab.kt:139-163` 是裸 `LazyVerticalGrid`，没有 `onLoadMore`。补法照抄 `library/browse/SongList.kt:83-94`（前置 `:81-82` 是教训注释）——⚠️ 必须带 `remember(songs, paging, isSearching)` 的 key：那两行注释记录了 2026-09-25 审查修复 #16，无 key 时 `derivedStateOf` 会冻结首次组合的参数快照，分页条件永远不更新。成本 **≈14 行**（12 行派生状态 + `LaunchedEffect`，加 2 个入参）。
- 少则不分页：**天然成立**。艺人分支 `total ≤ 100` → 首页即 `more=false` → `shouldLoadMore` 恒 false → 不渲染 load-more 项。不必写 `if (total > 100)` 的额外分支。
- **Q5 裁决：「换一批」保留** —— 归属逻辑不变（`SearchViewModel.kt:56-60` 的 24 个变异后缀、`:217-244` 的 `shuffleNetworkSearch()`、`:325+` 的 `pickBestFreshBatch`），本期**不删除、不改造、不接入新分支**。
  ⚠️ **v1.7 取证更正（重要）**：v1.6 本文把它写成"搜索页的既有按钮，命中艺人分支时隐藏"。逐行核对后**该按钮今天不存在**——全仓库 `shuffleNetworkSearch` 只有定义（`SearchViewModel.kt:217`）加一条注释（`:268`），**UI 侧零调用点**。屏幕上可见的两处「换一批」都不走网络搜索路径：`DiscoverTab.kt:195/205` → `LibraryScreen.kt:671` → `LibraryBranch.kt:211-213` → `MainViewModel.refreshBrowseSongs()`（多维度浏览），以及 `HomeScreen.kt:267`（智能电台，`MainViewModel.kt:2606`）。
  → 落地口径：**既不给搜索页新增按钮，也不许改动那两处按钮的链路**；本期只保证 `MetingApiService.search()` 改成"网易优先"后，`shuffleNetworkSearch()` 这条 ViewModel 路径**语义不变**（它调 `searchNetworkSongsBlocking` → `NetworkMusicManager.search`，喂的是 `_networkSearchResults` 流，与统一搜索的 `_searchResults` 是两条流，互不影响）。回归判据见 §6 第 7 条与 §11 Task 4 的回归断言。
- **Q6 裁决：平铺，不按专辑分组** —— 与本文倾向一致；`album` 字段仍显示在行内（`UnifiedSongRow` 已有），不引入分组头。566 首含大量 Live/feat 版本，分组会很碎这条理由成立。

### 3.7 播放链：零新代码

`type=url&id=<neteaseSongId>` / `type=lrc` / `type=pic` 都只认数字 id（URL 只拼 `server/type/id[/br]`，见 `MetingApiService.kt:261`），**不要求这首歌曾出现在搜索结果里**。这不再只是推断：§2 F 组的 20 首是从 566 首**全量集随机抽的**（几乎必然不在那 30 首里），其中 3 首照样解析到了 `audio/*` 直链 → 全量集独有的 id 走既有链路可正常解析。因此 `PlayerManager`、`resolvePlayUrlDetailed` 的档位降级链（`QualityTiers.fallbackChainOf`）、5 分钟直链缓存（`NetworkMusicManager.kt:40` `PLAY_URL_CACHE_TTL_MS`，容量 500 :42）全部原样复用。

⚠️ "零新代码"是双向的：复用链路也**复用了它的假 302 短路缺陷**（R4）。艺人页新增的曲目里绝大多数不可播，它们会全部走这条"返回成功、交给 ExoPlayer 才发现是 HTML"的路径，失败表现与搜索页的 30 首一致。这是 Q1 选 (b)/(c) 时必须一并评估的既有行为，不是本方案新引入的。

---

### 3.8 播放降级链：「不可播 → 用歌名+艺术家重搜换源」——机制**部分存在**，四处错位让它打不着网易的不可播曲目

owner 提的这条链（全量列表 → Meting 取直链 → 不可播则按歌名+艺术家重搜取可播者）**不是新设计**，`resolvePlayUrlWithCrossSourceFallback` 就是它：

| 设想环节 | 现状 | 位置 |
|---|---|---|
| 取直链，可播即播 | ✅ 已实现（端点 × 档位双层降级 + 直链缓存） | `MetingApiService.kt:250-285` |
| 不可播 → 用 `title + artist` 组关键词重搜 | ⚠️ **只在"其他源"里重搜**（artist 为空时退化为仅 title）；**同源重搜没有实现**（见错位 4） | `NetworkMusicManager.kt:396-401` |
| 遍历**其他源**、逐候选做可播校验、排除原曲本身 | ✅ 已实现 | `:404-406`（跳过原源）、`:420-429`（候选循环 + `candidate.id == song.id continue`） |
| 替换队列 + 提示 + 只播一次 | ✅ 已实现：`copy(streamUrl = …)` 保证"替换即可播"、toast、`playerQueue` 重建 | `:431`、`PlayerViewModel.kt:487-493` |
| 防死循环 / 过期回写 | ✅ 已实现：`lastCrossSourceReplacedId` 命中即放弃二次替换；`resolveGeneration` 代数校验 | `PlayerViewModel.kt:473-479, 481, 505, 510` |
| 唯一调用点 | **只在点播解析失败后**：连续两次 `forceRefresh=true` 解析仍拿到**空串**才走（`:424-425` 重试 → `:427` 判空 → `:430` 替换 → 全失效 `:436` 跳下一首） | `PlayerViewModel.kt:424-437` |

#### 四处错位（决定"能不能生效"）

1. ⛔ **触发条件错位**——`NetworkMusicManager.kt:391` 的前提是 `originalUrl.isNullOrBlank()`。而网易不可播曲目走的是**假 302**：`MetingApiService.kt:271-276` 一拿到非空 `Location` 就 `return` 成功。→ 对 §2 F 组那 17/20 的 DEAD 曲目，**这条降级链一次都不会被触发**。所以它不是"效果不好"，是"完全不参与"。
2. ⚠️ **重搜范围错位**——它跨的是**注册源**，不是 `server` 平台。`NasMusicApp.kt:323-327` 只可能再注册 `baidu`（百度网盘，需已配置且开关开启）与 `jamendo`（CC 独立音乐，需配了 client_id），且两者都是**条件注册**——**多数配置下"排除原源"后候选集是空的**，降级链直接 `return null`。而它跨到 `jamendo` 找"周杰伦"在语义上是**找同一首歌的另一位演唱者**，不是原曲。
   → owner 设想的"从 QQ/酷狗等其他来源换"**没有被覆盖**：`MetingApiService.searchWithEndpoint` 的 `server` 取全局 `serverProvider()`（`:192-194`），**没有每次调用的覆盖参数**。
3. ⚠️ **候选可播判据同样弱**——校验用的是 `!candidateUrl.isNullOrBlank()`（`:429`），与原源同一个假 302 漏洞：候选若也返回假 302，会被判成"可播"并写进队列，最终由 ExoPlayer 拿到 `text/html` 才失败（§2 假信号 1）。方法注释里的"避免搜到但依然播不了"（`:373-374`）**在当前判据下不成立**。
4. ⛔ **KDoc 承诺的"层级1 同源重搜"在代码里不存在**（v1.7 逐行核对新增）。`PlayerViewModel.kt:462-467` 的注释写着两条链——"层级1（同源重搜）：用 song 的 title+artist 调 `NetworkMusicManager.search()`"、"层级2（跨源替换）"，但 `tryReplaceByReSearch`（`:471-498`）的函数体**只有 `tryCrossSourceReplace` 一条路径**；全文件 grep `networkMusicManager.search` **零命中**（只有 `resolvePlayUrl` 的 4 处：`:166/:179/:280`），而 `resolvePlayUrlWithCrossSourceFallback` 又显式 `if (svc.sourceId == src) continue` 跳过原源（`NetworkMusicManager.kt:405`）。→ **"用歌名+艺术家在同一平台上重搜另一版本"这一步今天完全没做**。Q7 裁定"做成自动处理"，缺的不是触发条件，而是这一整层。

#### 改造步骤（顺序不可反；按 Q4/Q7 裁决展开，落到 §11 的 Task 5/6/7）

| 步 | 内容 | 本期 | 依赖 |
|---|---|---|---|
| **第 1 步｜判据做实**（= Q4 裁决"按推荐处理"→ 做） | 解析后**跟一跳**取最终 `Content-Type`，含 `audio` 才算成功；否则视为该端点×该档失败，继续 `:257-282` 的降级链（此时"遍历全部端点"才真正有意义）。负面结果一并写进直链缓存（键仍 `networkSource:networkId:quality`），避免同一首反复探测 | ✅ §11 Task 5 | 无前置，是后面两步的地基 |
| **第 2 步｜补上同源重搜**（错位 4，KDoc 已承诺但代码没有） | 在原源上用 `title + artist`（含变体后缀，复用 `SearchViewModel.kt:56-60` 那张后缀表的语义、不共用它的状态）搜另一版本，候选逐个走第 1 步的强判据 | ✅ §11 Task 6 | 第 1 步 |
| **第 3 步｜跨 `server` 重搜** | `NetworkMusicService.search` 与 `MetingApiService.searchWithEndpoint` 增加 per-call `server` 覆盖（默认仍取 `serverProvider()`，零行为变化）；把不可播曲目按 `title+artist` 在 **kugou / migu** 上重搜 | ⏸ **本期不做**（Q8 裁决"还是用网易，先不做 kugou"；张力记录见 §9 T1） | 第 1 步；端点须支持该平台（G1：只有 qijieya） |

**⚠️ 收益上限必须先讲清楚**（否则会被当成解法）：同平台重搜 **≈0**（F 组逐首交叉 0/20 分歧 + A2 并集仅 32 首 → 重搜回来的还是同上游那批 id 与同样的 DEAD 判定）；跨平台重搜实测救回 **1/5**（G2：kugou 命中 5/5 可播 **0**，migu 命中 2/5 可播 **1**）。**约 20%，且依赖特定端点。**

**必须遵守的边界**（源自 R3 限流与 R8 契约）：

- 候选数上限 **≤3**、端点**只用当前配置的那一个**（不做多端点 × 多平台 × 多候选的笛卡尔积；F 组一轮 20 首 × 3 端点就已经踩到 429 时段）。
- ⛔ **不接入「播放全部」/下载/后台批量**——只在**用户主动点播且已失败**或**手动按钮**时触发（§4 的 30 首上限 `SearchViewModel.kt:78` 不许为它放大）。
- 替换产物**只作用于当前播放队列**，⛔ 不写回收藏/下载（`Song` 无平台字段，写回会让 `ntwk_meting_<id>` 指向另一平台的同 id 曲目——R8）。
- 形态：**Q7 裁决为 ③「自动处理」** —— 无需用户关注，替换产物**直接写回当前播放队列对应位置**（`PlayerViewModel.kt:482-493` 的现有回写路径即该语义，本期沿用）。⚠️ 本文 v1.6 曾建议"做成单曲手动按钮"，已被该裁决推翻；随之而来的两个代价必须认下来：① 每次点播最坏多花"候选数 × 搜索+解析"的秒数（预算见 §10.3），② "现在放的是哪个平台的版本"变得不可解释 → 所以 toast 必须带上替代曲名与来源（现有文案 `cross_source_replace_playing` 已含这两个槽位）。
- 可测性：⚠️ **既有的 `NetworkPlaybackResolver`（`backend/download/NetworkPlaybackResolver.kt`）管的是"本地已下载 vs 在线解析"的优先级，不是可播判据**，别把探测逻辑塞进去。本期新增两个可注入的纯逻辑件：`PlayabilityProbe`（输入最终 URL + 一次 HEAD/GET 探测函数，输出 `PLAYABLE / DEAD / UNKNOWN`）与 `CandidatePicker`（输入候选列表与逐个探测结果，输出替代曲或 null）。单测全部用 lambda 注入假探测，不打网络。跨源部分用一个 fake `NetworkMusicService` 断言三件事——**排除原曲、候选上限生效、替换不落库**。

---

## 4. 核心产品取舍：可播率 ≤ 15%

§2 F 组的**随机抽样实测**决定了本方案**不是一个纯增益功能**：

- 拿得到的：566 首（其中 30 首搜索可见 → 覆盖度提升 ~19 倍）
- 真能播的：**随机 20 首里 3 首 = 15.0%，且三个端点判定完全一致**（0/20 分歧）；`st` 分布外推值 8.7% 只能当同数量级的参考。**结论与 v1.4 相同方向、但数字来源换了：这是抽样测出来的，不是字段推出来的。**
- **换端点无效已被证实**（F 组交叉矩阵 + A2 并集只有 32 首）→ 不要把"多端点 fallback 已经会兜"当成本方案的安全垫；代码确实遍历所有端点（`MetingApiService.kt:257-282`），但①假 302 短路它（R4）、②即便触发，各端点答案相同、③现成的跨源降级链对这批曲目**根本不触发**（R10 / §3.8）。
- **换平台救回幅度极小**：G1 只有 1/3 端点认 kugou/migu，G2 实测同名重搜救回 **1/5**，且 `Song` 不带平台字段（`Song.kt:37-41`）→ 想按曲存跨平台候选，必须先做一次**数据模型决策**（R8）。
- 用户体感风险：列表从"30 首、多数能播"变成"566 首、划 20 屏只有零星几首出声"。**这可能比现状更糟**，尤其电视遥控器逐行点播的成本极高。

三条应对路线（**Q1 已裁决 = (c)+(b)**，(a) 落选）：

| 路线 | 做法 | 代价 |
|---|---|---|
| **(a) 默认过滤** | 只展示 `privilege.st==0` 的 49 首，设置里可"显示全部" | 列表瞬间从 566 → 49，**分页几乎不需要**（问题 3 自动退化为"少则不分页"）；但"全部歌曲"名不副实，⚠️ 且 **v1.5 后证据不支持**：`st` 与可播的相关性只有 12/12 小样本支撑，F 组的随机结果与 `st` 外推值并不完全吻合（§2 E），拿它做**隐藏数据**级别的决策太薄 |
| **(b) 全展示 + 打标** | 566 首全展示，`st!=0` 行右侧灰显「可能无版权」角标 | 需给 `UnifiedSongRow` 加一个 badge 入参；点播失败仍走现有跳过链。**文案必须写"可能"**，`st` 不是判据 |
| **(c) 排序优先** | `st==0` 置顶、其余沉底，全展示 | 改动最小，语义最诚实；但沉底部分依旧不可播 |

**✅ Q1 已裁决：采纳推荐的 (c)+(b) 组合**（可播置顶 + 不可播打标但不隐藏）。落点受 §2 C.1 约束——**打标/排序的判据字段 `privilege.st` 只在艺人分支（`/api/v1/artist/songs`，566/566）存在，普通关键词搜索分支（`type=1`）覆盖为 0/100**，所以：

| 分支 | 打标（b） | 置顶（c） | 依据 |
|---|---|---|---|
| 艺人强命中 → `artist/songs` | ✅ `st != 0` 行右侧灰显「可能无版权」 | ✅ `st == 0` 置顶 | §2 D 组 566/566 |
| 普通关键词 → `type=1` | ❌ 无字段可标 → **不显示角标**（≠"全部可播"） | ❌ 保持接口原序 | §2 C.1（0/100） |
| 网易接口下线 → Meting 端点兜底 | ❌ 同上 | ❌ 同上 | 端点 search 响应无 privilege（`MetingApiService.kt:408-448` 只取 5 个字段） |

> ⛔ 不许给"无字段"的分支伪造一个默认角标——那等于把"不知道"说成"能播"或"不能播"。UI 侧统一以**缺省即不显示**处理。

理由是 `st` 是**未登录态**的判读，换用户自建端点（带自己账号 cookie）后可播集会显著不同——隐藏数据的风险大于标记；而 F 组证明"公共端点上换端点/换平台都救不回来"，**唯一还能把可播率抬起来的手段就是用户自建端点**（见本节末尾那条注），所以 (b)/(c) 必须配合"低可播提示 + 建议自建端点"一起上。

> 顺带修正一个认知：公共端点不可播 ≠ 功能失败。**用户自建 Meting 端点是既有能力**（`PRESET_ENDPOINTS` + 自定义 baseUrl，`MetingApiService.kt:116-120`，`baseUrlProvider` 每次请求实时读），自建端点用主人自己的账号，可播率通常远高于公共端点。方案应把"建议自建端点"写进艺人页的空态/低可播提示。

---

## 5. 分期（按 Q9 裁决：**不独立发版，本期一次交付**）

| 块 | 内容 | 前置 | 风险 |
|---|---|---|---|
| **B1 数据层** | `NeteaseOpenApi`（`findArtist` / `searchSongs` / `artistSongs`）+ `NetworkMusicService` 扩展 + `MetingApiService` 覆写 + 单测 + 两条 id/路由红线护栏 | 无 | **零**（无 UI 入口，不改既有搜索路径） |
| **B2 搜索改造**（Q2） | `MetingApiService.search()` 改"网易优先 → 端点兜底" + 预算切分（§10.3）+ 艺人强命中展开 + `SearchViewModel`/`SearchTab` 续页钩子 + 打标/置顶（Q1）+ 平铺（Q6） | B1 | 中（动的是**统一搜索的网络分支**，回归面含「换一批」与来源点亮） |
| **B3 播放降级**（Q4 + Q7 ③） | 第 1 步判据做实（`PlayabilityProbe`）+ 第 2 步同源重搜（`CandidatePicker`）+ 自动替换写回队列 | B1（判据独立于 B2，可与 B2 并行） | 中。⚠️ 判据改动波及**全部网络歌曲与下载**的解析路径（`MetingApiService.resolvePlayUrlDetailed`），回归必须覆盖百度网盘 / jamendo / `extractUrlFromJson` 那条 200-JSON 分支 |
| ~~B4 跨 `server` 重搜~~ | ~~kugou / migu 救援~~ | — | ❌ **本期不做**（Q8 裁决"还是用网易，先不做 kugou"）。⚠️ 由此 Q7 的自动降级在**同平台内**收益 ≈0（F 组 0/20 分歧）——这条张力记录在 §9 T1，不许在验收里把它当"能救回很多首"来测 |
| ~~P3 其他平台艺人全量~~ | kugou 列表 / qq / kuwo / migu | — | ❌ **本期撤出**（Q8）。H 组的实测结论保留，将来重启直接按 §附录 A 的 `--platforms` 复跑 |

> v1.6 的"P4 第 1 步可脱离本方案单独发版"这一选项已被 **Q9 否决**（"不用独立发版"）→ B3 与 B1/B2 同批提交、同批验收。原文的"P1/P2 可独立发版"表述随之作废。
>
> B4/P3 重启前必须先补一轮同规格探测：把 `netease_artist_probe.py` 的 C/D 组换成目标平台的艺人接口、E/F 组沿用"跟完整链看最终 `Content-Type`"判据（v1.5 已把这套判据与 `--platforms` 固化进脚本）。

---

## 6. 验收标准（本期一次交付：B1 + B2 + B3）

**B1 数据层（可全自动）**

1. `./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process` 全绿
2. 新增单测（Robolectric + JUnit4，`app/src/test/`，纯逻辑不打网络，用固定 JSON fixture）：
   - `NeteaseArtistSongsParseTest`：**单页**解析——字段映射与 `artists[]` 多人名拼接、`limit` 钳制（传 500 必须变 100）、`more=false` 终止；⚠️ **多页拼接（566 条累积）不在这里测**，它属于游标逻辑，由 `ArtistSongsPagingTest` 覆盖（`clampLimit` / `parseArtistSongs` 是纯函数，翻页拼接是 `NetworkArtistExpansion` 的状态机）
   - **`NetworkArtistSongIdContractTest`（红线护栏）**：同一 `netId` 下，`NeteaseOpenApi` 生成的 `Song.id` 必须**逐字符等于** `MetingApiService.parseSongs` 生成的 `Song.id`，且 `networkSource == "meting"`。断言失败即代表会撕裂收藏/下载主键
   - `NeteaseType1FieldTest`：**负向断言**——`type=1` fixture 里缺 `privilege`/`album.picUrl` 时必须产出 `coverUrl == null` 且**不进** `unplayableIds`（§2 C.1 的 0/100 落在代码上的形状）
   - `ArtistSongsPagingTest`：`hasMore` 由 `more` 字段驱动（末页恰满 100 时不得再多发一次请求）
3. `lintDebug` 无新增 error（本机口径：`--no-daemon`，见 AGENTS.md）
4. 测量脚本重跑 exit 0，且 **A 组三端点至少一个可采信**（全 429/5xx 时 exit 2，属环境态，不算失败也不算通过）

**B2 搜索改造（需真机）**

5. 电视端：搜"周杰伦"→ 结果**就是**这个艺人的全量列表（统一搜索入口，无新页面）→ 划到底自动续页、计数递增、BACK 先回顶再回播放页（五级 BACK 不受影响，`SearchTab.kt:78-97` 的既有注册必须照常生效）
6. **网易优先 / 端点兜底两条路径都要走一遍**：① 默认状态下结果来自网易（可用 logcat `MetingDiag` 的分支日志判定）；② 把 `music.163.com` 在 hosts 里指到不可达地址（或开飞行模式仅留 NAS 局域网）→ **必须自动落到 Meting 端点搜索并出结果**，⛔ 不能整批空
7. **两处既有「换一批」零回归 + 网络搜索换一批语义不变**（Q5）：① 发现页 `DiscoverTab` 的「换一批 ↻」与首页智能电台的「换一批」在新路径下仍能出新歌（它们不经网络搜索，只需确认未被误改）；② `SearchViewModel.shuffleNetworkSearch()` 这条 ViewModel 路径**不新增 UI 按钮**（v1.7 取证：该函数今天零调用点，见 §3.6），也**不许因"网易优先"改造而改变返回形状**——`NetworkMusicManager.search()` 的调用方拿到的仍是 `List<Song>`。⛔ 不许把"隐藏按钮"当验收项，按钮本来就不存在
8. **无角标即无信息**（Q1 的负向验收）：普通关键词分支与端点兜底分支**不得**出现「可能无版权」角标；艺人分支里 `st != 0` 的行有角标、`st == 0` 的行置顶且无角标
9. 点播一首**F 组实测判为 PLAYABLE 的曲目**出声正常；点播一首判为 DEAD 的曲目走既有失败跳过链，**不卡死、不弹死循环**（⚠️ v1.6 改口径：不再用 `privilege.st` 选样本——它已降级为"仅作分布参考"，用它当验收判据等于用一个未证实的标志做判定）
10. 同一首歌经"搜索（普通关键词）"与"搜索（艺人分支）"两个入口加入收藏/下载后，**只有一条记录**（红线 1 的真机验证；原"艺人页 vs 搜索页"两个入口在 Q2 裁决后合并为同一页面的两条分支）
11. 未连接任何 NAS、未自建端点时功能可用（`BackendAdapter` nullable 的既有前提，weather radio 已验证过该路径）

**B3 播放降级（判据 + 同源重搜 + 自动替换；全自动部分不打网络）**

12. 单测（fixture 不打网络）：给定"302 → `/404` `text/html`"与"302 → `audio/mpeg`"两条链，`PlayabilityProbe` 必须分别判为 **DEAD / PLAYABLE**——这条是 R4 的护栏，缺它则改动会被"看起来能播"回归掩盖
13. 单测：DEAD 判定写入直链缓存后，**同 key 在 TTL 内不再重复探测**；切档/切端点仍会重探（对齐 `NasMusicApp.kt:318` 的"切档清缓存"既有语义）
14. 单测：用 fake `NetworkMusicService` 断言 `CandidatePicker` 四件事——**排除原曲 id、候选数上限 ≤3 生效、只探到第一个 PLAYABLE 即停、替换产物不写回收藏/下载**
15. 单测：Q7 ③ 的自动替换在 `lastReplacedSongId` 命中时**不再二次替换**（防死循环。⚠️ 该字段改造前叫 `lastCrossSourceReplacedId`，Task 7 因防环范围要同时覆盖同源与跨源两层而更名，`PlayerViewModel.kt:99/466/473/487` 四处引用一并改；既有语义"替换产物仍失败就放弃"不许被改动破坏）
16. 真机：点播一首 F 组判为 DEAD 的曲目 → 自动降级链触发 → 观察 ①不再出现"已切歌未播放"的静默卡死、②toast 指明替代曲名与来源、③整轮请求量不超过"1 次搜索 + 3 次解析"、④**连续触发不引发 429**（对照 R3 的节奏约束）
17. ⚠️ **验收不许把"救回率"当指标**：本期只做同源重搜（跨 `server` 被 Q8 撤出），其收益**未实测**（§9 T1）。验收只判"链路正确 + 不劣化"，不判"多播几首"

> ⚠️ 第 12~13 条会改动**全部网络歌曲与下载**共用的解析判据（`MetingApiService.resolvePlayUrlDetailed`），回归面不止搜索页：验收须额外覆盖百度网盘、jamendo、以及"端点直返 200 JSON 里的 url"那条分支（`MetingApiService.kt:267` 的 `extractUrlFromJson`），它们在改造后**必须保持"非空即成功"**，因为不产生 302 链——判据只加在跟链那一侧。

---

## 7. 风险登记

| # | 风险 | 证据 | 处置 |
|---|---|---|---|
| R1 | 网易无鉴权接口**随时下线** | 旧的 `/api/search/get/web` 今天已返回 `code 400`（本轮实测），是同类先例 | 新功能全部走**新增代码路径**，不改既有 `search()`；接口失败 → 艺人入口整体静默不可用，搜索照旧。入口开关放设置页（对齐 `localMusicEnabled` 的 suspend provider 风格） |
| R2 | 可播率低导致体验劣化（实测 **15.0%，3/20**，三端点判定一致） | §2 F 组（⛔ 别再引 v1.4 的 8.7%） | Q1（§4 三路线）；**换端点与换平台都已被证实救不回**（F 交叉矩阵 + G1/G2） |
| R3 | 公共端点限流（429 / retryAfter 数百~3500s，时段性） | §2 假信号 2 | 艺人接口只打 `music.163.com`（不经 Meting）；**播放解析保持并发 1**，命中已有 5 分钟直链缓存；"播放全部"沿用 30 首上限（`SearchViewModel.kt:78`）不放大 |
| R4 | `resolvePlayUrlDetailed` 对"假 302"不 fallback —— **已核实为代码事实**：`:257-282` 双层循环遍历全部端点×档位，但 `:271-276` 一拿到非空 `Location` 即 `return` 成功；`NetworkMusicManager.kt:391` 的跨源降级前提同样是"解析结果为空串" | §2 假信号 1、代码逐行核对 | ⚠️ 本期**不改**该方法语义（改动面波及全部网络播放与下载，风险不对称）。只在 UI 侧用 `st` 预判打标。**Q4 决策时必须带上这个前提：修它只让失败判定变准，不提高可播率**（F 组 0/20 分歧 → 端点降级即使触发也没有第二个答案） |
| R5 | id/`networkSource` 契约被写错 | 编译与 lint 均不报，点播时才炸 | 红线 1/2 + `NetworkArtistSongIdContractTest`（§6.2） |
| R6 | 喂给 Android Auto 浏览树时被 `MAX_SEARCH_RESULTS=50` 二次截断 | `MediaLibraryTree.kt:104` | 本期不把艺人列表接入 Auto；若要接，须同步评审该常量 |
| R7 | 内存：566 首 × 多艺人累积 | — | 分页状态只存当前艺人；切艺人即重置，不入全局缓存 |
| **R8** | **`Song` 不携带来源平台**，跨平台救援无法按曲回溯（G2 实测救回 1/5，但救回的候选存不下来；`platform` 是全局设置，切平台后旧 netease id 只会解析失败——G3 实测**串歌 0**，所以是"失败"不是"放错歌"） | `Song.kt:37-41`、§2 G2/G3 | 本期**不做**跨平台救援。若日后要做，是一个**数据模型决策**（`Song` 加平台字段 → 牵动 Gson 序列化、`NetworkFavoriteItem` 收藏 JSON、缓存 key `networkSource:networkId:quality`），必须单开方案，⛔ 不能在本期顺手加字段 |
| **R9** | 多端点取并集**不能**突破 30（三端点结果集 Jaccard 0.88~0.94，并集仅 31~32 首） | §2 A2 | 不要把"遍历端点求并集"写进备选方案；它只提高命中率，不扩样本 |
| **R10** | **现成的跨源降级链对网易不可播曲目完全不参与**（四处错位：`:391` 只认空串→假 302 不触发；跨的是注册源而非 `server` 平台，且 baidu/jamendo 是条件注册、候选集常为空；候选校验 `:429` 用同一个弱判据；**KDoc 承诺的同源重搜层级根本没实现**——`PlayerViewModel.kt:462-467` 写了"层级1 同源重搜"，函数体里却零调用 `NetworkMusicManager.search`） | `NetworkMusicManager.kt:373-374, 391, 404-406, 429` + `NasMusicApp.kt:322-328` + `MetingApiService.kt:192-194, 271-276` + `PlayerViewModel.kt:462-498` | §3.8 改造步骤（本期 Task 5/6）。⛔ 在没做"判据做实"之前，**不要**在任何文档/评审里把"已有跨源降级兜底"当成本方案的安全垫——它对 17/20 那批曲目是零作用。⚠️ 也别把 `PlayerViewModel` 的那段 KDoc 当现状读 |

---

## 8. 决策项（**已裁决；其中 Q3 是"先交评估"型裁决，评估答复见 §10**）

> ✅ 本节保留为**选项存档**（当时列出的备选与推荐理由，供追溯"为什么不是另一条路"）。**逐条裁决结果与对方案的直接影响见 §9**；⛔ 不要再按本节字面把任何一项当"待拍板"引用。

| # | 问题 | 选项 | 备注 |
|---|---|---|---|
| **Q1** | 不可播曲目怎么处理？ | (a) 默认过滤 / (b) 全展示+打标 / (c) 可播置顶+打标 | **推荐 (c)+(b)**，理由见 §4。⚠️ v1.5：**证据不再支持 (a)**——`privilege.st` 已降级为"仅作分布参考"，用它隐藏数据太薄（§2 E） |
| **Q2** | 入口形态？ | ① 搜索结果行菜单「查看该歌手全部歌曲」 ② 网络音乐页按钮 ③ 独立 `NetworkArtistDetailScreen` | ③ 复用现成 `SongsTab` 成本最低；② 就地扩展要先给 `SearchTab` 补 `onLoadMore`（§3.6） |
| **Q3** | 允许直连 `music.163.com` 吗？ | 允许 / 只允许经用户配置的 Meting 端点 | 现状歌词模块**已经**直连（`LyricsNetworkProvider.kt:261`），"不新增架构先例"成立；但 Meting 侧"所有网络歌曲一律经端点"的一致性会被打破 |
| **Q4** | 要不要修 R4（解析后探测 `Content-Type` 再判成功）？ | 本期不动 / **单开 P4 第 1 步** | ⚠️ v1.6 升级：**这一项不再只是"艺人功能的附带风险"，它同时是 §3.8 降级链的前置**。不修，则①现成的跨源降级对不可播曲目永不触发（R10）、②任何"重搜换源"的候选校验都会把假 302 当成可播。决策前提不变：**修它是"准确性"投资不是"可播率"投资**（F 组 0/20 分歧 → 端点降级即使正确触发也拿不到不同答案）；收益是"该跳的立刻跳、不把 HTML 交给 ExoPlayer 才报错、跨源降级真正生效" |
| **Q5** | 「换一批」（`SearchViewModel.kt:55-78`）保留吗？ | 保留 / 艺人场景改用全量 / 移除 | 它对非艺人关键词（如"粤语 精选"）仍有价值，倾向**保留** |
| **Q6** | 艺人页是否显示专辑/单曲分组？ | 平铺 / 按 `album` 分组 | `/api/v1/artist/songs` 响应带 `album{id,name,picUrl}`，分组可行；但 566 首里含大量 Live/feat 版本，分组会很碎 |
| **Q7**（v1.5 新增，v1.6 具体化） | 不可播曲目的"重搜换源"降级做成什么形态？ | ① 不做 / ② **单曲手动按钮** / ③ 自动链路 | 机制已存在（§3.8），要动的是**触发、范围、判据**三处。实测收益 **1/5**（G2），且同平台重搜 ≈0 → **推荐 ②，且必须先做 Q4**；③ 会让每次点播最坏多花"候选数 × 搜索+解析"的秒数，并把"放的是哪个平台的版本"变成不可解释的状态。约束照 §3.8：候选 ≤3、单端点、不进播放全部/下载、替换不落库 |
| **Q8**（v1.5 新增） | P3 是否改为"先做 kugou 列表"？ | 保持 P3 待定 / 排期 kugou 列 | H 组已证实 kugou 侧接口与网易同构（`total=1845` 真分页），但**直链 0/4** → 纯列表价值需评估（收藏/下载在不可播前提下意义有限） |
| **Q9**（v1.6 新增） | P4 第 1 步（判据做实）能不能**脱离本方案单独发版**？ | 可以 / 与 P1 绑定 | 倾向**可以**：它改善的是**现有搜索页 30 首**的点播体验，与艺人全量功能无耦合，且不需要 Q3（不新增直连域名）。若同意，应把它从本方案里摘出去单独立项，避免被 Q1–Q3 的争论阻塞 |

---

## 9. 裁决记录（owner，2026-10-10）

> 本节是**唯一权威**的裁决落点记录。引号内为 owner 原话（逐字），"直接影响"= 本文据此改写的小节，"落点"= §11 的 Task 编号。

| # | owner 裁决原话 | 对方案的直接影响 | 落点 |
|---|---|---|---|
| **Q1** | 「按推荐 C+B」 | (c) 可播置顶 + (b) 全展示打标、**不隐藏**。⚠️ 依据 §2 C.1，两个动作**只能落在艺人分支**（`privilege.st` 566/566），普通搜索分支与端点兜底分支**无字段可依 → 不打标、不置顶**，且"无角标"绝不等于"能播" | §3.5 `unplayableIds` / §10.2 矩阵 / Task 2·8 / 验收 8 |
| **Q2** | 「直接使用现在的统一搜索入口，调用网络搜索时默认先使用网易无鉴权接口，如果网易无鉴权接口直接降级到 meting 端口搜索」 | ① **撤销** v1.5 推荐的"独立 `NetworkArtistDetailScreen`"，改就地扩展 `SearchTab`；② "网易优先 → 端点兜底"实现为 **`MetingApiService.search()` 内部的取数策略**（`:136-162`），⛔ 不是新增第 6 个源、不改 `NetworkMusicManager` 的源顺序（`:113-135`）；③ 使 §10.3 的超时预算成为硬约束（网易优先 + 端点串行链贴不住现有 5s） | §3.6 / §10.1 / §10.3 / Task 4 |
| **Q3** | 「需要先评估一下，如果增加这样一个歌曲来源，界面如何调整，代码改动有多大等等」 | **条件裁决**：先交评估（§10），owner 复确认"允许直连 `music.163.com`"后才放行 **Task 4**（唯一把直连接入线上路径的任务）。Task 1–3 是数据层、无 UI 入口、不碰既有搜索路径，**不受该确认阻塞** | §10 全部 |
| **Q4** | 「按推荐处理」 | 判据做实（R4）**本期做**：解析后跟一跳看最终 `Content-Type`，含 `audio` 才判成功；DEAD 判定写负面缓存。⛔ 边界不变：`extractUrlFromJson` 那条"端点直返 200 JSON"分支（`:267`）**保持非空即成功**，判据只加在跟链侧 | §3.8 第 1 步 / Task 5 / 验收 12·13 |
| **Q5** | 「保留，本来就不是搜索页面的功能，其他页面功能不受影响」 | 与代码事实**恰好相符**（v1.7 取证：`shuffleNetworkSearch` 零 UI 调用点，屏幕上的两处「换一批」是发现页与首页电台，都不走网络搜索）。落地为：**不删 ViewModel 逻辑、不新增按钮、不动那两处链路**；v1.6 写的"命中艺人分支时隐藏按钮"随之作废（按钮本就不存在） | §3.6 / 验收 7 / Task 4 回归断言 |
| **Q6** | 「按推荐处理」 | 平铺、不按专辑分组。`album` 仍显示在行内（`UnifiedSongRow` 已有），不加分组头 | §3.6 / Task 8（仅"不加"） |
| **Q7** | 「选 3，做成自动处理，无需用户关注，结果直接替换播放列表中内容」 | 自动链路（无手动按钮）。owner 说的"播放列表"= **当前播放队列**（`PlayerViewModel.kt:482-493` 的既有回写路径），⛔ 不写回收藏/下载/歌单。v1.6 推荐的"单曲手动按钮"作废。代价两条照 §3.8：① 点播最坏多花"1 次重搜 + ≤3 次探测"，② 来源不可解释 → toast 必须带替代曲名与来源（`cross_source_replace_playing`，`strings.xml:881` 双槽位已具备） | §3.8 / Task 6·7 / 验收 14·15·16 |
| **Q8** | 「还是用网易，先不做 kugou」 | B4（跨 `server` 重搜）与 P3（其他平台艺人全量）**本期撤出**，§3.8 第 3 步标 ⏸。H 组实测结论保留在 §2，重启按附录 A 的 `--platforms` 复跑 | §5 / **T1** |
| **Q9** | 「不用独立发版」 | B1+B2+B3 **同批提交、同批验收**，v1.6 的"P4 第 1 步可脱离本方案单独发版"作废；文档同步只写一条 `technical-overview.md` §10.N | §5 / Task 9 |

### 9.1 裁决之间的张力（不许在实现时假装看不见）

| # | 张力 | 证据 | 本期处置 |
|---|---|---|---|
| **T1** | **Q7 选 ③（自动降级）的有效性依赖跨 `server`，而 Q8 恰好把跨 `server` 撤掉了** | 跨平台实测救回 **1/5**（§2 G2：kugou 命中 5/5 可播 0，migu 命中 2/5 可播 1）；同平台重搜 ≈0（§2 F 组 0/20 分歧、A2 三端点并集仅 31~32 首）→ 同源重搜的收益**从未被实测过** | B3 只交付"链路正确 + 判据做实 + 不劣化"。⛔ 验收第 17 条明令**不得把救回率当指标**。若 owner 日后要让 ③ 真正救回歌曲，唯一已知杠杆是重启 B4，而 B4 的前置是 `Song` 携带平台字段——那是**数据模型决策**（R8），必须单开方案 |
| **T2** | **Q1 选 (b)"全展示打标"会把一个未证实的标志大面积打在页面上** | `privilege.st` 分布 `{-200: 517, 0: 49}`（§2 D，566 首里 **91.3% 会被打标**），而实测可播率 **15.0%（3/20）**（§2 F）——两者数量级相近但不等价，`st` 已被降级为"仅作分布参考"（§2 E，12/12 只证明判据可二分） | 文案必须是**不确定语气**（「可能无版权」），⛔ 禁写"不可播/播不了"；只用于灰显与角标，⛔ 不可作过滤依据；`unplayableIds` 是旁路 Set，⛔ 不进 `Song`（§3.5） |
| **T3** | **Q2 的"网易优先"要求放宽超时，而放宽会改变搜索分支的失败形态** | 网易 p95 0.368s + 端点串行三桶 p95 上界 ≈4.85s（§2 L）已贴着 `NETWORK_TIMEOUT = 5_000L`（`SearchAggregator.kt:75`）；超时后 `withHardTimeout` 返回 null → 网络分支**整批变空列表**（`:186-195`），用户看到"网络音乐没结果"而不是"慢" | 提到 **8_000L**，并给网易侧 2_500ms 子闸门（§10.3）。⚠️ 这**不拉长统一搜索的最坏等待**：五个分支 `async` 后逐个 `await`（`:291-298`），总上界取最大值，而 `NAS_TIMEOUT = 15_000L`（`:74`）本就更大 |

---

## 10. Q3 答复：界面影响与改动量评估

> 本节就是 Q3 要的评估。结论先给：**不新增"歌曲来源"这个概念，只改网络源内部的取数策略**，因此界面几乎不动（0 新页面 / 0 新源枚举 / 0 新设置项），代价集中在数据层与播放降级层。

### 10.1 界面影响（为什么这么小）

| 维度 | 结论 | 依据 |
|---|---|---|
| 新 `Screen` / 新导航分支 | **0** —— 就地扩展统一搜索 Tab | Q2「直接使用现在的统一搜索入口」；`Screen` 枚举（`data/model/Screen.kt`）不动 |
| 新 ViewModel | **0** —— 新状态装进纯 Kotlin 类 `NetworkArtistExpansion`，由 `SearchViewModel` 组合 | §3.5；`SearchViewModel.kt` 现 359 行，R-1 拆分刚做过 |
| 新 `MusicSourceType` 枚举值 | **0** —— 网易优先发生在 `MetingApiService.search()` **内部**，聚合层看到的仍是同一个 `NETWORK_MUSIC` 分支 | `SearchAggregator.kt:183-201`；红线 2（`networkSource` 恒为 `"meting"`，`NetworkMusicManager.kt:183-186` 精确路由不 fallback） |
| 新设置项 | **0** —— 基址复用歌词那个 `music.163.com` 设置 | `AppPreferences.kt:1214` `getLyricsNeteaseBaseUrlSync()`，`NasMusicApp.kt:195` 已在用同一 provider |
| 来源点亮栏 | **不动** | `SearchTab.kt` 的 `SearchSourceBar`（:190-230）按源点亮，网易不是源 |
| 五级 BACK | **不动** | `SearchTab.kt:78-97` 已注册 Level 1.5 回顶 |
| 播放全部 / 加入队列上限 | **不动**（仍 30 首） | `SearchViewModel.kt:78` `maxNetworkBatchPlayCount` |
| 用户可见变化 | **3 处**：① 关键词命中艺人时结果变成该艺人完整作品列表、计数随翻页递增；② 划到底自动续页（无按钮、无"加载更多"点击）；③ 艺人分支的行上多一个「可能无版权」灰角标 | §3.6 / §10.2 |

> 一句话：**"增加一个歌曲来源"被做成了"同一个来源换一种取数方式"**，所以界面成本近乎为零；反过来，红线 1/2（id 与 `networkSource`）之所以是红线，正是这个低成本方案的前提——它们一旦写错，融合搜索、去重、收藏、下载全部会分裂。

### 10.2 强命中判定 + 打标/封面决策矩阵

**判定规则**（复用既有工具，⛔ 不新建匹配器）：

1. `ArtistSplitter.normalizeKey(keyword)`（NFKC + 去不可见字符 + 折叠空白 + 小写，`util/ArtistSplitter.kt:47-63`）与 `type=1` 结果自带的 `Song.artist` 比较，**归一化全等**才算命中（走 `containsArtistWithKey(rawArtistField, artistKey)`，`:120-124`——它按 `／ & 、 feat.` 等分隔符拆开后逐个比）。
2. 多命中时取"在首屏 100 首里出现次数最多"的那个 `artistId`（≈作品数直觉，避免把 feat. 客串的歌手当主艺人）。
3. 首屏无命中才发**一次** `findArtist`（`type=100`），且仍要求返回的艺人名归一化全等。
4. ⛔ **不做前缀/模糊匹配**："周杰"不该展开成"周杰伦"的名录——那会把每一次普通关键词搜索都变成艺人目录，是产品级事故。合唱判定走 `ArtistSplitter.containsArtist(rawField, keyword)`（:104-108），它已处理 `／ & 、 feat.` 等分隔符。

**分支矩阵**（这张表决定 Task 2 与 Task 8 的全部条件分支）：

| 分支 | 取数接口 | `privilege.st` | `album.picUrl` | 角标（T2 文案） | `st==0` 置顶 | 封面 |
|---|---|---|---|---|---|---|
| **艺人强命中** | `GET /api/v1/artist/songs` | **566/566** | **566/566** | ✅ `st != 0` 打「可能无版权」 | ✅ 置顶 | ✅ `picUrl` 直链 |
| **网易普通搜索** | `POST /api/search/get` `type=1` | **0/100** ⛔ | **0/100** ⛔ | ❌ 无角标 | ❌ 保持上游原序 | `coverUrl = null` → 走既有无封面占位 |
| **Meting 端点兜底** | `{endpoint}?type=search` | 无该字段 | 有（`pic` 是 302 端点，Coil 自动跟） | ❌ 无角标 | ❌ | `pic` 端点（`MetingApiService.kt:417/:439` 既有语义） |

- ⚠️ "无角标" ≠ "能播"（§2 C.1 的 0/100 落在代码上的形状）→ 验收第 8 条是**负向**断言，`NeteaseType1FieldTest` 是它的单测护栏。
- ⛔ 不许为"看起来有封面"去猜未实测的 `type=pic` URL 拼法（§3.2）。

### 10.3 超时预算分配（Q2 的直接后果）

| 环节 | 实测 p95（§2 L 组，23:43 轮） | 本期预算 | 依据与余量 |
|---|---|---|---|
| 网易 `type=1` 搜索 | 368 ms | OkHttp `connect/read = 2_500 ms` | 6.8× 余量 |
| 网易 `artist/songs` 首页 | 279 ms | 同上 `2_500 ms` | 8.9× 余量 |
| 网易 `findArtist`（`type=100`） | **未单独采样**（与 `type=1` 同接口同形态） | 复用 `2_500 ms`，且只在无命中时发 **1 次** | ⚠️ 估的，不是测的；若实测异常按附录 A 补 L 组 |
| Meting 端点串行链（现状） | 三桶 p95 相加**上界** ≈ 4.85 s（0.70 + 1.12 + 3.03） | 不改（`buildEndpointFallbackOrder`，`MetingApiService.kt:173-184`） | redcha 单端点 p95 **3027 ms** 就已吃掉 5s 预算的 60% |
| **`SearchAggregator.NETWORK_TIMEOUT`** | — | **`5_000L` → `8_000L`**（`SearchAggregator.kt:75`） | 网易 2.5s 子闸门 + 端点链 4.85s + 0.65s 余量 |
| 播放降级单次探测（Task 5/6） | — | 每次探测 `connect/read = 3_000 ms`、**只读响应头即关连接**、候选 ≤3 | ⚠️ 现 `client` 的 readTimeout 是 15s（`MetingApiService.kt:69-70`）——三候选若沿用会到 45s 量级，探测**绝不能**复用那个 client |

- ⚠️ 8s **不拖累整页**：分支 `async` 后逐个 `await`（`SearchAggregator.kt:291-298`），总上界 = max，而 `NAS_TIMEOUT = 15_000L`（`:74`）更大 → 见 T3。
- ⛔ 这组数字只支撑**预算分配**，不支撑"网易优先能提高可播率"（§2 L 组末尾的同一条声明）。

### 10.4 逐文件改动量估计

**新增（主代码 ≈590 行）**

| 文件 | 动作 | 估计行 | 归属 Task |
|---|---|---|---|
| `backend/network/netease/NeteaseOpenApi.kt` | 新增 | ≈210 | 2 |
| `backend/network/NetworkSongId.kt` | 新增 | ≈20 | 1 |
| `backend/network/NetworkArtistTypes.kt`（`NetworkArtistInfo` + `ArtistSongsPage`） | 新增 | ≈35 | 2（`NeteaseOpenApi` 的返回类型必须同批存在） |
| `backend/network/NetworkArtistExpansion.kt` | 新增 | ≈120 | 4 |
| `backend/network/PlayabilityProbe.kt` | 新增 | ≈90 | 5 |
| `backend/network/CandidatePicker.kt` | 新增 | ≈70 | 6 |
| `backend/network/ReplaceChain.kt`（可注入决策件，仿 `NetworkPlaybackResolver` 的存在理由） | 新增 | ≈45 | 7 |

**修改（≈+315 / −35）**

| 文件 | 现行数 | 改动 | 估计行 | 风险 |
|---|---|---|---|---|
| `backend/network/MetingApiService.kt` | 498 | `search()` 加网易优先分支、`parseSongs` 走 `NetworkSongId`、`resolvePlayUrlDetailed` 接探测、覆写两个新方法 | +60/−12 | 中（返回形状影响融合去重） |
| `backend/network/NetworkMusicService.kt` | 115 | 两个默认实现方法 | +20 | 低（默认实现保证 Jamendo/百度零改动） |
| `backend/network/NetworkMusicManager.kt` | 442 | 转发 `findArtist`/`getArtistSongs`、负面缓存、同源重搜 | +85/−5 | 中 |
| `backend/SearchAggregator.kt` | 450 | `:75` 一行常量 | +1/−1 | 低 |
| `ui/viewmodel/SearchViewModel.kt` | 359 | 组合 expansion、把网络段替换进 `_searchResults` | +55 | 中 |
| `ui/screens/library/SearchTab.kt` | 260 | 3 个入参 + 续页钩子（照 `browse/SongList.kt:83-94`） | +30 | 低 |
| `ui/components/song/UnifiedSongRow.kt` | 751 | 1 个入参 + 角标分支（`:413-426` 那个 `when`） | +18 | 低 |
| `ui/screens/LibraryScreen.kt` / `ui/components/branches/LibraryBranch.kt` | 876 / 237 | 参数透传 / 状态收集 | +3 / +8 | 低 |
| `ui/viewmodel/PlayerViewModel.kt` | 602 | 层级1 真正接上 + 决策交给 `ReplaceChain`；KDoc 与实现对齐（⚠️ `:464-465` 的 KDoc 声明"层级1 同源重搜"，但 `:471-497` 的方法体**只有跨源一条**，同源分支从来不存在——这是"文档先行、代码没跟上"的活样本） | +30/−12 | 中 |
| `NasMusicApp.kt` | — | `:303-308` 构造处注入 `NeteaseOpenApi`（Task 4）、给 `NetworkMusicManager` 注入 `probe = HttpPlayabilityProbe()`（Task 5） | +8 | 低 |
| `res/values/strings.xml` + `values-en/strings.xml` | 各 1220 条（实测 `grep -c "<string "` 两侧都是 1220） | **只新增 2 条**，各侧重复一遍：Task 7 的 `same_source_replace_playing`（同源重搜替换文案）、Task 8 的 `song_row_unplayable_badge`「可能无版权」。其余全部**复用既有 key**——跨源降级文案用 `cross_source_replace_playing`（`values/strings.xml:881` / `values-en/strings.xml:1034`，双槽位 `%1$s` 曲名 + `%2$s` 来源已满足 Q7 的"必须可解释"），翻页计数用 `library_songs_count_with_total`，续页加载态用 `common_loading`。⚠️ 同源必须**另起一条**而不能复用跨源那条：原文案会把"用 meting 源替换 meting 源"渲染成一句自相矛盾的话 | +4 | ⚠️ **两文件必须同步加**，缺 `values-en` 会编译通过、渲染时崩（`VisualizerThemeStringsGateTest` 的既有教训） |

**测试（≈950 行，11 个新文件 / 50 例）**：`NetworkArtistSongIdContractTest`(3)、`NeteaseArtistSongsParseTest`(2)、`NeteaseType1FieldTest`(1)、`NetworkArtistRoutingTest`(3)、`NeteasePrioritySearchTest`(3)、`ArtistSongsPagingTest`(8)、`PlayabilityProbeTest`(5)、`MetingProbeWiringTest`(5)、`NetworkDeadProbeCacheTest`(6)、`CandidatePickerTest`(9)、`ReplaceChainTest`(5)。全部 Robolectric + JUnit4（`ReplaceChainTest` 是纯 JVM，无需 Robolectric），解析/决策类用 fixture 与 lambda 注入**不打网络**；用 `MockWebServer` 的只有 `NeteasePrioritySearchTest`、`PlayabilityProbeTest`、`MetingProbeWiringTest` 三个（`app/build.gradle.kts:271` 已有依赖，`MetingResolveTest.kt:5-7` 是现成范本）。⚠️ 凡涉及 Meting 端点链的用例都**不得断言"全链失败 ⇒ url == null"**——`buildEndpointFallbackOrder` 会追加 3 个真实公网预设端点，那样写必然 flaky（`MetingResolveTest.kt:191-202` 的同源纪律）。

> **总量 ≈1850 行，其中约一半是测试。** 风险集中在三处：`MetingApiService.search()` 的返回形状、`resolvePlayUrlDetailed` 的判据（波及全部网络播放与下载）、`PlayerViewModel` 的替换链。其余都是加法式改动。

---

## 11. 实施计划（按 §9 裁决展开；逐 Task 可直接开发）

> **For agentic workers:** 逐 Task 实现，每个 Task 结束时该 Task 的测试必须**先红后绿**。步骤里的复选框（`- [ ]`）就是执行清单。

**Goal:** 让统一网络搜索在关键词命中艺人时返回该艺人的**全量作品**（网易 `music.163.com` 免鉴权接口，翻页续取），可播项置顶、疑似无版权项打标但不隐藏；同时把播放直链的"可播判据"做实，并让"不可播 → 同源重搜 → 自动替换队列"真正生效。

**Architecture:** 三层，全部加法：
1. **数据层**（新）`NeteaseOpenApi` 打网易原生接口，产物经**唯一主键构造点** `NetworkSongId` 落成统一 `Song`，`networkSource` 恒为 `"meting"` → 对上层完全伪装成 Meting 结果。
2. **策略层**（新纯 Kotlin 件，全部可用 lambda 单测）`NetworkArtistExpansion`（是否展开 / 翻页 / 置顶集 / 打标集）、`PlayabilityProbe`（跟链判可播）、`CandidatePicker`（同源重搜救回候选）、`ReplaceChain`（替换决策与防死循环）。
3. **接入层**（改）`MetingApiService.search()` 的"网易优先 → 端点兜底"、`SearchAggregator` 的一个常量、`SearchViewModel` 的组合、`SearchTab`/`UnifiedSongRow` 的显示、`PlayerViewModel` 的自动替换。

**Tech Stack:** Kotlin 2.3.10、OkHttp（复用 `BackendRegistry.sharedDispatcher/sharedConnectionPool`）、Gson、Compose TV（`ExperimentalTvMaterial3Api`）、Robolectric 4.11.1 + JUnit4 + MockWebServer 4.12.0 + kotlinx-coroutines-test。

**Global constraints（每个 Task 都隐含遵守）**
- ⛔ 红线 1：`Song.id` 只能由 `NetworkSongId.forNetworkSong(sourceId, netId)` 生成，格式必须是 `ntwk_meting_<netId>`。
- ⛔ 红线 2：`networkSource` 必须是 `"meting"`（`NetworkMusicManager.kt:183-186` 精确路由不 fallback；写成 `"netease"` 编译与 lint 都不报、点播才炸）。
- ⛔ `privilege.st` **只用于打标/置顶，绝不用于过滤**（T2）。
- ⛔ 构建/测试一律 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`；遇 `fileHashes.lock` 先 `./gradlew.bat --stop`，**不要 `rm` 锁文件**。
- ⛔ 不得用 PowerShell 对 `.kt` 做文本往返替换（CP936 会毁掉 UTF-8 源文件）。
- ⛔ 候选 ≤3、探测并发 1、探测 client 单独 3s 超时；不接入"播放全部"/下载/后台批量；替换产物不落库（不写收藏/下载）。
- ⚠️ Task 4 是**唯一把 `music.163.com` 接入线上路径**的任务，需 Q3 的复确认；Task 1–3、5–9 不新增直连域名（Task 5 的探测跟的是 Meting 端点给的 `Location`）。

**File map**

| 路径 | Task | 动作 |
|---|---|---|
| `app/src/main/java/com/nasmusic/tv/backend/network/NetworkSongId.kt` | 1 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt` | 1,3,4,5 | 改 |
| `app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistSongIdContractTest.kt` | 1,2 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/NetworkArtistTypes.kt` | 2 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/netease/NeteaseOpenApi.kt` | 2 | 新增 |
| `app/src/test/java/com/nasmusic/tv/backend/network/netease/{NeteaseArtistSongsParseTest,NeteaseType1FieldTest}.kt` | 2 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt` | 3 | 改 |
| `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt` | 3,5,6 | 改 |
| `app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistRoutingTest.kt` | 3 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/NetworkArtistExpansion.kt` | 4 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt` | 4 | 改（1 行） |
| `app/src/main/java/com/nasmusic/tv/ui/viewmodel/SearchViewModel.kt` | 4 | 改 |
| `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt` | 4,5 | 改（构造注入 `NeteaseOpenApi` / `HttpPlayabilityProbe`） |
| `app/src/test/java/com/nasmusic/tv/backend/network/{NeteasePrioritySearchTest,ArtistSongsPagingTest}.kt` | 4 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/PlayabilityProbe.kt` | 5 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/ResolveResult.kt` | 5 | 改（+`probedDead` 字段与 `failure(requested, probedDead)`） |
| `app/src/test/java/com/nasmusic/tv/backend/network/{PlayabilityProbeTest,MetingProbeWiringTest,NetworkDeadProbeCacheTest}.kt` | 5 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/CandidatePicker.kt` | 6 | 新增 |
| `app/src/test/java/com/nasmusic/tv/backend/network/CandidatePickerTest.kt` | 6 | 新增 |
| `app/src/main/java/com/nasmusic/tv/backend/network/ReplaceChain.kt` | 7 | 新增 |
| `app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlayerViewModel.kt` | 7 | 改 |
| `app/src/test/java/com/nasmusic/tv/backend/network/ReplaceChainTest.kt` | 7 | 新增 |
| `app/src/main/res/{values,values-en}/strings.xml` | 7,8 | 各 +1 条（Task 7 `same_source_replace_playing`、Task 8 `song_row_unplayable_badge`；计数与加载态复用既有 key） |
| `app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongRow.kt` | 8 | 改 |
| `app/src/main/java/com/nasmusic/tv/ui/screens/library/SearchTab.kt` | 8 | 改 |
| `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt` | 8 | 改（透传） |
| `app/src/main/java/com/nasmusic/tv/ui/components/branches/LibraryBranch.kt` | 8 | 改（收集状态） |
| `docs/technical-overview.md` / `CHANGELOG.md` | 9 | 改（§10.225 / `[Unreleased]` 两行） |
| `docs/archive/verification/netease_artist_probe/probe_v5_regression.log` | 9 | 新增（回归轮 exit-0 存档） |

**公共测试脚手架**（Task 2/4/6 复用，放在各自文件内，不抽公共基类——Robolectric 类各自 `@Config`，抽基类反而增加耦合）：

```kotlin
// 固定 fixture 一律内联为字符串常量，⛔ 不打网络、不读 assets
private val artistSongsPage1 = """
{"api_code":200,"data":{
 "songs":[{"id":1001,"name":"晴天","duration":269000,"artists":[{"id":6452,"name":"周杰伦"}],
   "album":{"id":99,"name":"叶惠美","picUrl":"http://p1.music.126.net/a.jpg"},
   "fee":0,"privilege":{"st":0}},
  {"id":1002,"name":"安静","duration":300000,"artists":[{"id":6452,"name":"周杰伦"}],
   "album":{"id":99,"name":"叶惠美","picUrl":"http://p1.music.126.net/a.jpg"},
   "fee":0,"privilege":{"st":-200}}],
 "total":2,"more":false}}
"""
```

---

### Task 1：`NetworkSongId` —— 主键唯一构造点 + 红线护栏

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkSongId.kt`
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt:434-443`
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistSongIdContractTest.kt`

**Interfaces:**
- Produces: `NetworkSongId.forNetworkSong(sourceId: String, netId: String): String`（Task 2 的 `NeteaseOpenApi` 必须调用它）

- [ ] **Step 1：写失败测试**（本 Task 先钉 Meting 侧；Task 2 再补网易侧的交叉相等）

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkArtistSongIdContractTest {

    /** Meting 端点真实形状的一条搜索结果（title/author/url 里带 id） */
    private val metingSearchBody = """
      [{"title":"晴天","author":"周杰伦",
        "url":"https://music.163.com/song?id=1001",
        "pic":"https://p.example/pic?1","lrc":"https://lrc.example/1"}]
    """.trimIndent()

    @Test
    fun `meting 搜索结果的 id 必须等于唯一构造点的产物`() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(metingSearchBody).setHeader("Content-Type", "application/json"))
        try {
            val svc = MetingApiService(baseUrlProvider = { server.url("/api").toString() })
            val songs: List<Song> = svc.search("周杰伦", limit = 30)
            assertEquals(1, songs.size)
            val song = songs.first()
            // 红线 1：格式逐字符
            assertEquals("ntwk_meting_1001", song.id)
            // 红线 2：路由键
            assertEquals("meting", song.networkSource)
            assertEquals("1001", song.networkId)
            assertEquals(NetworkSongId.forNetworkSong("meting", "1001"), song.id)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `构造点不会被误写成平台名`() {
        assertEquals("ntwk_meting_1001", NetworkSongId.forNetworkSong("meting", "1001"))
        // 负面自检：若有人把 sourceId 写成 netease，本断言会抓住格式漂移（红线 2 的姊妹条）
        org.junit.Assert.assertNotEquals("ntwk_meting_1001", NetworkSongId.forNetworkSong("netease", "1001"))
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NetworkArtistSongIdContractTest"
```
预期：**编译失败** `Unresolved reference: NetworkSongId`。

- [ ] **Step 3：新增构造点**

```kotlin
package com.nasmusic.tv.backend.network

/**
 * 网络歌曲主键的**唯一**构造点（艺人全量方案 红线 1）。
 *
 * `MetingApiService.parseSongs` 与 `NeteaseOpenApi` 都必须经这里生成 `Song.id`。
 * 两条链路给同一首歌生成不同 id，等于同一首歌在收藏 / 下载 / 断点续传索引里变成两个实体
 * （这些主键全部建在这个字符串上）。
 */
object NetworkSongId {
    /** 形如 `ntwk_meting_2652820720`；⛔ sourceId 只能是注册源 key，不能是平台名（红线 2） */
    fun forNetworkSong(sourceId: String, netId: String): String = "ntwk_${sourceId}_$netId"
}
```

- [ ] **Step 4：`MetingApiService.parseSongs` 改走构造点**（`:434-443` 的 `Song(` 首行）

```kotlin
                    Song(
                        id = NetworkSongId.forNetworkSong(sourceId, netId),
                        title = fixedTitle,
```
（其余字段一行不动。`netId` 在 `:429` 已由 `extractIdFromUrl` 求得且非空。）

- [ ] **Step 5：跑测试确认通过** —— 同 Step 2，预期 `BUILD SUCCESSFUL`、2 例全绿。

- [ ] **Step 6：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/NetworkSongId.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistSongIdContractTest.kt
git commit -m "feat(network): 抽出网络歌曲主键唯一构造点并加红线护栏测试"
```

---

### Task 2：`NeteaseOpenApi` —— 三个网易原生接口 + 纯解析器

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkArtistTypes.kt`（`NeteaseOpenApi` 的返回类型必须先于它存在，否则 Task 2 编译不过 → 类型文件归本 Task，Task 3 只加接口默认实现与转发）
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/netease/NeteaseOpenApi.kt`
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/netease/NeteaseArtistSongsParseTest.kt`
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/netease/NeteaseType1FieldTest.kt`
- Modify: `app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistSongIdContractTest.kt`（补网易侧）

**Interfaces:**
- Consumes: `NetworkSongId.forNetworkSong`（Task 1）
- Produces:
  - `data class NetworkArtistInfo(val id: String, val name: String, val songCount: Int)`
  - `data class ArtistSongsPage(val songs: List<Song>, val total: Int, val hasMore: Boolean, val unplayableIds: Set<String>)`
  - `class NeteaseOpenApi(baseUrlProvider: () -> String, client: OkHttpClient = NeteaseOpenApi.defaultClient())`
  - `suspend fun findArtist(name: String): NetworkArtistInfo?`（Task 4 兜底路径）
  - `suspend fun searchSongs(keyword: String, offset: Int = 0, limit: Int = 100): List<Song>`
  - `suspend fun artistSongs(artistId: Long, offset: Int, limit: Int = 100): ArtistSongsPage`
  - `internal fun parseArtistSongs(body: String): ArtistSongsPage` / `internal fun parseType1Songs(body: String): List<Song>` / `internal fun parseArtists(body: String): List<NetworkArtistInfo>` / `internal fun toSong(s: JsonObject, artistIdOverride: String? = null): Song?`——**均为实例方法**，测试里先 `val api = NeteaseOpenApi(baseUrlProvider = { "https://music.163.com" })` 再 `api.parseArtistSongs(...)`；`internal fun clampLimit(limit: Int): Int` 在 companion 上，测试里写 `NeteaseOpenApi.clampLimit(500)`（同模块单测可直接访问 `internal`，先例见 Step 3 末尾说明）

- [ ] **Step 1：写失败测试（解析侧，全部 fixture，不打网络）**

```kotlin
package com.nasmusic.tv.backend.network.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeteaseArtistSongsParseTest {

    // 解析器是**实例方法**（toSong 依赖 METING_SOURCE 常量与后续可能的实例配置），
    // 单测构造一个永不发起请求的实例直打纯函数——baseUrlProvider 只是占位。
    private val api = NeteaseOpenApi(baseUrlProvider = { "https://music.163.com" })

    private fun pageBody(items: String, total: Int, more: Boolean) =
        """{"api_code":200,"data":{"songs":[$items],"total":$total,"more":$more}}"""

    private val twoSongs = """
      {"id":1001,"name":"晴天","duration":269000,
       "artists":[{"id":6452,"name":"周杰伦"},{"id":7,"name":"彭濼"}],
       "album":{"id":99,"name":"叶惠美","picUrl":"http://p/a.jpg"},"fee":0,
       "privilege":{"st":0}},
      {"id":1002,"name":"安静","duration":300000,
       "artists":[{"id":6452,"name":"周杰伦"}],
       "album":{"id":99,"name":"叶惠美","picUrl":"http://p/a.jpg"},"fee":0,
       "privilege":{"st":-200}}
    """.trimIndent()

    @Test
    fun `字段映射与分页标志`() {
        val p = api.parseArtistSongs(pageBody(twoSongs, 566, true))
        assertEquals(566, p.total)
        assertTrue(p.hasMore)                       // hasMore 取接口的 more，不猜
        assertEquals(2, p.songs.size)
        val first = p.songs.first()
        assertEquals("晴天", first.title)
        assertEquals("周杰伦 / 彭濼", first.artist)   // artists[] 多人名拼接
        assertEquals(269_000L, first.durationMs)     // 网易 duration 已是毫秒
        assertEquals("叶惠美", first.album)
        assertEquals("99", first.albumId)
        assertEquals("http://p/a.jpg", first.coverUrl)
        assertEquals("6452", first.artistId)
        // 打标集只含 st != 0 的那首
        assertEquals(setOf("ntwk_meting_1002"), p.unplayableIds)
    }

    @Test
    fun `limit 入参即钳制且末页 more=false 时 hasMore 为 false`() {
        val last = api.parseArtistSongs(pageBody("""{"id":1,"name":"x","duration":1000,
            "artists":[{"id":6452,"name":"A"}],"album":{"id":2,"name":"b"},"privilege":{"st":0}}""", 100, false))
        assertTrue(!last.hasMore)
        // 钳制函数是纯函数，直接测（网络请求侧由 NeteasePrioritySearchTest 覆盖）
        assertEquals(100, NeteaseOpenApi.clampLimit(500))
        assertEquals(100, NeteaseOpenApi.clampLimit(100))
        assertEquals(1, NeteaseOpenApi.clampLimit(0))
        assertEquals(1, NeteaseOpenApi.clampLimit(-5))
    }
}
```

```kotlin
package com.nasmusic.tv.backend.network.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §2 C.1 的 0/100 落在代码上的形状（验收项 8 的单测护栏） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeteaseType1FieldTest {

    private val api = NeteaseOpenApi(baseUrlProvider = { "https://music.163.com" })

    /** type=1 的真实形态：**无 privilege、无 album.picUrl**，但 artists[].id / duration / album.name 齐备 */
    private val type1Body = """
      {"api_code":200,"result":{"songs":[
        {"id":1001,"name":"晴天","duration":269000,
         "artists":[{"id":6452,"name":"周杰伦"}],
         "album":{"id":99,"name":"叶惠美"},"fee":0}],
       "artistIds":[6452]}}
    """.trimIndent()

    @Test
    fun `type1 分支必须产出 null 封面且带 artistId`() {
        val songs = api.parseType1Songs(type1Body)
        val s = songs.single()
        assertNull("type=1 没有 album.picUrl → coverUrl 必须为 null，走既有无封面占位", s.coverUrl)
        assertEquals("叶惠美", s.album)          // 有的字段照旧要用
        assertEquals(269_000L, s.durationMs)
        // §2 C：type=1 自带 artists[].id → 强命中判定零额外请求的依据（Task 4 用它）
        assertEquals("6452", s.artistId)
        assertEquals("周杰伦", s.artist)
        assertEquals("ntwk_meting_1001", s.id)   // 与艺人分支同格式（红线 1）
    }
}
```

- [ ] **Step 2：跑两个测试确认失败**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NeteaseArtistSongsParseTest" --tests "*NeteaseType1FieldTest"
```
预期：编译失败（`Unresolved reference: parseArtistSongs` 等）。

- [ ] **Step 3：新增 `NetworkArtistTypes.kt` 与 `NeteaseOpenApi.kt`**

先建返回类型（Step 1 的测试编译期就要用到）：

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song

/**
 * 网络源的"艺人"概念载体（艺人全量方案 §3.3）。
 *
 * ⛔ 不复用 `data/model/Artist.kt`：那个模型的 `id` 属于 NAS 后端命名空间，
 * 与网易的 `6452` 混用会让"同一 id 在两个来源指向不同的人"成为可能（R8 同源教训）。
 */
data class NetworkArtistInfo(
    val id: String,
    val name: String,
    val songCount: Int
)

/**
 * 艺人作品的一页。
 *
 * @param total 接口声明的总数（§2 D：`total=true` 时与逐页累加一致，566=566）
 * @param hasMore **取自接口的 `more` 字段**，⛔ 不许用 `size == pageSize` 猜（§3.4）
 * @param unplayableIds 疑似无版权曲目 id 集合；**仅用于打标/置顶，⛔ 不可用于过滤**（T2）。
 *        不支持该概念的源 / `type=1` 形态返回空集（§2 C.1 覆盖 0/100）。
 */
data class ArtistSongsPage(
    val songs: List<Song>,
    val total: Int,
    val hasMore: Boolean,
    val unplayableIds: Set<String>
)
```

再建客户端：
```kotlin
package com.nasmusic.tv.backend.network.netease

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nasmusic.tv.backend.network.ArtistSongsPage
import com.nasmusic.tv.backend.network.NetworkArtistInfo
import com.nasmusic.tv.backend.network.NetworkSongId
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐**免鉴权原生接口**客户端（艺人全量方案 §3.1）。
 *
 * 与 Meting 端点无关：直接打 `music.163.com`，因此
 * - 不受公共端点 429 限流影响（§2 假信号 2）
 * - 但随时可能下线（R1）→ 所有调用点都必须能接受"失败即回落 Meting"
 *
 * ⚠️ 解析全部走 `internal` 纯函数，单测用 fixture 覆盖，不打网络。
 */
class NeteaseOpenApi(
    /** 基址复用歌词设置项（默认 https://music.163.com），用户改歌词基址时本功能一起跟随 */
    private val baseUrlProvider: () -> String,
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "NeteaseOpenApi"
        private const val DIAG = "MetingDiag"
        /** ⛔ 网易侧实测：limit>100 时 total 会随 limit 抖动（§2 D 组 241/194/1070），必须锁 100 */
        private const val MAX_LIMIT = 100
        private const val CONNECT_READ_TIMEOUT_MS = 2_500L   // §10.3 子预算（实测 p95 0.368s）

        /** 与 MetingApiService.sourceId 一致（红线 2）；⛔ 绝不能写 "netease" */
        private const val METING_SOURCE = "meting"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

        val EMPTY_PAGE = ArtistSongsPage(emptyList(), 0, false, emptySet())

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(CONNECT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CONNECT_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        /** 入参即钳制，不依赖调用方自觉（§3.1） */
        internal fun clampLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)
    }

    private val gson = Gson()

    private val baseUrl: String
        get() = baseUrlProvider().trim().trimEnd('/')

    /** `POST /api/search/get` `type=100`：仅当网络段来自 Meting 端点（拿不到 `Song.artistId`）时兜底调用一次 */
    suspend fun findArtist(name: String): NetworkArtistInfo? {
        val artists = postSearchGet(type = "100", s = name, offset = 0, limit = MAX_LIMIT)
            ?.let { parseArtists(it) } ?: return null
        return artists.firstOrNull()
    }

    /** `POST /api/search/get` `type=1`：Q2 的"网易优先"搜索分支 */
    suspend fun searchSongs(keyword: String, offset: Int = 0, limit: Int = MAX_LIMIT): List<Song> {
        val body = postSearchGet(type = "1", s = keyword, offset = offset, limit = clampLimit(limit))
            ?: return emptyList()
        return parseType1Songs(body)
    }

    /** `GET /api/v1/artist/songs`：艺人全量分页（§2 D 组：566 首 / more 末页转 false） */
    suspend fun artistSongs(artistId: Long, offset: Int, limit: Int = MAX_LIMIT): ArtistSongsPage {
        val url = "${baseUrl}/api/v1/artist/songs?id=$artistId" +
            "&offset=$offset&limit=${clampLimit(limit)}&total=true&order=hot"
        val request = Request.Builder()
            .url(url)
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", USER_AGENT)
            .build()
        val body = execute(request) ?: return ArtistSongsPage(emptyList(), 0, hasMore = false, emptySet())
        return parseArtistSongs(body)
    }

    private suspend fun postSearchGet(type: String, s: String, offset: Int, limit: Int): String? {
        val form = FormBody.Builder()
            .add("s", s)
            .add("type", type)
            .add("offset", offset.toString())
            .add("total", "true")
            .add("limit", clampLimit(limit).toString())
            .build()
        val request = Request.Builder()
            .url("${baseUrl}/api/search/get")
            .post(form)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.w(DIAG, "netease http=${resp.code} url=${request.url}")
                    return@use null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            // R1：接口下线/超时是**预期内**的常态，调用方按"回落 Meting"处理，不当异常上抛
            AppLog.w(DIAG, "netease request failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ---- 纯解析（internal，单测直打） ----

    /**
     * 统一 `Song` 构造器（§3.2）。**两条分支共用**，以保证同一首歌在两处 id 逐字符一致（红线 1）。
     *
     * 字段缺失即 null / 默认值——这正是 §2 C.1 的 0/100 需要的形状：`type=1` 无 `privilege`、
     * 无 `album.picUrl`，此处不猜测、不用别的端点补。
     */
    internal fun toSong(s: JsonObject, artistIdOverride: String? = null): Song? {
        val netId = s.get("id")?.toString()?.removeSuffix(".0") ?: return null
        val title = s.get("name")?.asString ?: return null
        val artists = s.get("artists")?.asJsonArray
        val artist = artists?.mapNotNull { it.asJsonObject.get("name")?.asString }
            ?.joinToString(" / ").orEmpty()
        val albumObj = s.get("album")?.asJsonObject
        return Song(
            id = NetworkSongId.forNetworkSong(METING_SOURCE, netId),   // 红线 1 + 红线 2
            title = title,
            artist = artist,
            artistId = artistIdOverride ?: artists?.firstOrNull()
                ?.asJsonObject?.get("id")?.toString()?.removeSuffix(".0"),
            album = albumObj?.get("name")?.asString.orEmpty(),
            albumId = albumObj?.get("id")?.toString()?.removeSuffix(".0"),
            coverUrl = albumObj?.get("picUrl")?.asString,              // type=1 → null（§2 C.1）
            durationMs = s.get("duration")?.asLong ?: 0L,              // 网易已是毫秒
            isNetworkSong = true,
            networkSource = METING_SOURCE,                             // ⛔ 不是 "netease"
            networkId = netId
        )
    }

    internal fun parseArtistSongs(body: String): ArtistSongsPage {
        val data = runCatching { gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("data") }
            .getOrElse { AppLog.e(TAG, "parseArtistSongs failed", it); return EMPTY_PAGE }
        val items = data?.getAsJsonArray("songs") ?: return EMPTY_PAGE
        val songs = items.mapNotNull { runCatching { toSong(it.asJsonObject) }.getOrNull() }
        // ⛔ hasMore 只认接口的 more：§2 D 实测末页恰满时 more 已转 false，
        //   照抄 MainViewModel.kt:1143 的 size==pageSize 会多发一次空请求
        val hasMore = data.get("more")?.asBoolean ?: false
        val total = data.get("total")?.asInt ?: songs.size
        return ArtistSongsPage(songs, total, hasMore, unplayableIdsOf(items))
    }

    internal fun parseType1Songs(body: String): List<Song> {
        val result = runCatching { gson.fromJson(body, JsonObject::class.java)?.getAsJsonObject("result") }
            .getOrNull() ?: return emptyList()
        val items = result?.getAsJsonArray("songs") ?: return emptyList()
        // type=1 无 privilege（§2 C.1 0/100）→ 这里**不**生成打标集；
        // 但 artists[].id 会经 toSong 落进 Song.artistId（§2 C：distinct_artist_ids=54），
        // Task 4 的强命中判定因此零额外请求。
        return items.mapNotNull { runCatching { toSong(it.asJsonObject) }.getOrNull() }
    }

    internal fun parseArtists(body: String): List<NetworkArtistInfo> {
        val arr = runCatching {
            gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("result")?.getAsJsonArray("artists")
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { a ->
            val o = a.asJsonObject
            val id = o.get("id")?.asLong ?: return@mapNotNull null
            NetworkArtistInfo(
                id = id.toString(),
                name = o.get("name")?.asString ?: return@mapNotNull null,
                songCount = o.get("musicSize")?.asInt ?: 0
            )
        }
    }

    /**
     * `privilege.st != 0` 的曲目 id 集合。
     *
     * ⚠️ **只用于打标/置顶，⛔ 不可用于过滤**（T2：`st` 已被降级为"仅作分布参考"，
     * 而 `type=1` 形态根本没有这个字段 → 缺字段时返回空集，绝不猜）。
     */
    internal fun unplayableIdsOf(items: JsonArray): Set<String> {
        val out = mutableSetOf<String>()
        items.forEach {
            val o = it.asJsonObject
            val st = o.getAsJsonObject("privilege")?.get("st")?.asInt ?: return@forEach
            val id = o.get("id")?.toString()?.removeSuffix(".0") ?: return@forEach
            if (st != 0) out.add(NetworkSongId.forNetworkSong(METING_SOURCE, id))
        }
        return out
    }
}
```

> ⚠️ **写这段时必须注意的三个真实坑**（前两个都已在本仓库踩过）：
> ① Gson 把 JSON 数字读成 `Double`，`element.toString()` 对 `1001` 给出 `"1001.0"` —— 上面用 `removeSuffix(".0")` 兜住，**不要**改成 `asLong` 后忽略 `id` 可能是科学计数法的场景（网易 id 最长 19 位，`asLong` 安全，但 `artists[].id` 有时是字符串形态）。
> ② `internal` 对同模块单测可见（Kotlin Gradle 插件把 `testDebug` 设成 `main` 的 friend path）——**已核实先例**：`BaiduPanApiTest.kt:105` 直接调 `api.parseBaiduFile(...)`，被调方 `BaiduPanApi.kt:402` 就是 `internal fun`。
> ③ **一个类只能有一个 `companion object`**（本方案初稿在这里同时写了 `companion object` 和 `private companion object`，直接编译不过）。常量按上面的分法放：只给类内部用的（`METING_SOURCE`/`USER_AGENT`）也放 companion，标 `private`。

- [ ] **Step 4：补契约测试的网易侧**（`NetworkArtistSongIdContractTest` 追加一例）

```kotlin
    @Test
    fun `同一 netId 下网易侧与 Meting 侧生成的 id 必须逐字符相等`() {
        // 红线 1 的正体：两条链路共用 NetworkSongId，任何一侧改写格式都会在这里被抓到
        val netease = com.nasmusic.tv.backend.network.netease.NeteaseOpenApi(
            baseUrlProvider = { "https://music.163.com" }
        )
        val item = com.google.gson.JsonObject().apply {
            addProperty("id", 1001L)
            addProperty("name", "晴天")
            addProperty("duration", 269000L)
            add("artists", com.google.gson.JsonArray().apply {
                add(com.google.gson.JsonObject().apply {
                    addProperty("id", 6452L); addProperty("name", "周杰伦")
                })
            })
        }
        val s = netease.toSong(item)!!
        assertEquals(NetworkSongId.forNetworkSong("meting", "1001"), s.id)
        assertEquals("ntwk_meting_1001", s.id)
        assertEquals("meting", s.networkSource)   // 红线 2
    }
```

- [ ] **Step 5：跑三个测试确认通过**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NeteaseArtistSongsParseTest" --tests "*NeteaseType1FieldTest" \
  --tests "*NetworkArtistSongIdContractTest"
```
预期：全绿（4 + 1 + 3 例）。**若 worker 秒死（exit `0x1000000A`）先 `./gradlew.bat --stop` 再重试。**

- [ ] **Step 6：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/netease/NeteaseOpenApi.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/netease/ \
        app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistSongIdContractTest.kt
git commit -m "feat(network): 新增网易免鉴权接口客户端与解析单测"
```

---

### Task 3：接口扩展与转发 —— Jamendo / 百度网盘零改动

**Files:**
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt`（在 `:44 searchByDirectory` 与 `:114 getPlaylist` 同级加两个默认实现）
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt`（构造参数 + 两个覆写）
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt`（两个转发）
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistRoutingTest.kt`

**Interfaces:**
- Consumes: `NeteaseOpenApi`、`NetworkArtistInfo`、`ArtistSongsPage`（均 Task 2）
- Produces:
  - `NetworkMusicService.findArtist(name): NetworkArtistInfo? = null` / `getArtistSongs(artistId, offset, limit=100): ArtistSongsPage? = null`
  - `NetworkMusicManager.findArtist(name): NetworkArtistInfo?` / `NetworkMusicManager.getArtistSongs(artistId, offset, limit): ArtistSongsPage?`
  - `MetingApiService(..., netease: NeteaseOpenApi? = null)`

- [ ] **Step 1：写失败测试（路由与默认实现）**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkArtistRoutingTest {

    /** 不支持艺人的源（Jamendo / 百度网盘的形状）：只实现既有三个方法 */
    private class PlainService : NetworkMusicService {
        override val sourceId = "plain"
        override suspend fun search(keyword: String, limit: Int) = emptyList<Song>()
        override suspend fun resolvePlayUrl(song: Song) = "http://x/1.mp3"
        override suspend fun resolveLyrics(song: Song) = null
        override suspend fun getPlaylist(playlistId: String) = emptyList<Song>()
    }

    private class ArtistService : NetworkMusicService {
        override val sourceId = "meting"
        override suspend fun search(keyword: String, limit: Int) = emptyList<Song>()
        override suspend fun resolvePlayUrl(song: Song) = "http://x/1.mp3"
        override suspend fun resolveLyrics(song: Song) = null
        override suspend fun getPlaylist(playlistId: String) = emptyList<Song>()
        override suspend fun findArtist(name: String) = NetworkArtistInfo("6452", name, 566)
        override suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int) =
            ArtistSongsPage(
                songs = listOf(Song(id = "ntwk_meting_$offset", title = "t$offset",
                    networkSource = "meting", networkId = offset.toString(), isNetworkSong = true)),
                total = 566, hasMore = offset < 500, unplayableIds = setOf("ntwk_meting_$offset")
            )
    }

    @Test
    fun `按默认源路由且不 fallback`() = runBlocking {
        val mgr = NetworkMusicManager(
            services = mapOf("meting" to ArtistService(), "plain" to PlainService()),
            defaultSourceProvider = { "meting" }
        )
        assertEquals("6452", mgr.findArtist("周杰伦")?.id)
        val page = mgr.getArtistSongs("6452", offset = 0)!!
        assertEquals(566, page.total)
        assertTrue(page.hasMore)
        assertEquals(setOf("ntwk_meting_0"), page.unplayableIds)
    }

    @Test
    fun `不支持艺人的源返回 null 而不抛异常`() = runBlocking {
        val mgr = NetworkMusicManager(
            services = mapOf("plain" to PlainService()),
            defaultSourceProvider = { "plain" }
        )
        assertNull(mgr.findArtist("周杰伦"))
        assertNull(mgr.getArtistSongs("1", 0))
    }

    @Test
    fun `默认源未注册时返回 null（与 resolvePlayUrl 的未注册源分支同语义）`() = runBlocking {
        val mgr = NetworkMusicManager(
            services = mapOf("meting" to ArtistService()),
            defaultSourceProvider = { "ghost" }
        )
        assertNull(mgr.findArtist("周杰伦"))
    }
}
```

- [ ] **Step 2：跑测试确认失败** —— `--tests "*NetworkArtistRoutingTest"`，预期编译失败（`findArtist` 未定义）。

- [ ] **Step 3：确认 `NetworkArtistTypes.kt` 已存在**（由 Task 2 创建；本 Task 不重复建，否则两个 Task 抢同一个文件）。若缺失，回到 Task 2 Step 3 补。

- [ ] **Step 4：`NetworkMusicService` 加两个默认实现**（紧跟 `:44` 的 `searchByDirectory` 之后，与 `:114` 的 `getPlaylist` 同级）

```kotlin
    /**
     * 艺人发现：关键词强命中某位艺人时返回其信息。
     *
     * 默认实现返回 null —— Jamendo / 百度网盘 / 本地源**没有"艺人目录"概念**，
     * 让它们因"不支持艺人"而报错是错误语义（与 [resolvePlayUrl] 默认忽略 quality 同一套哲学）。
     */
    suspend fun findArtist(name: String): NetworkArtistInfo? = null

    /**
     * 艺人作品分页。
     *
     * @return null = 该源不支持或取页失败；`hasMore=false` 的空页是合法的"已翻到底"
     */
    suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int = 100): ArtistSongsPage? = null
```

- [ ] **Step 5：`MetingApiService` 委托**（构造参数 `netease` 默认 null → 既有 `NasMusicApp.kt:303-308` 与所有单测零改动）

```kotlin
    /**
     * 网易免鉴权接口（Task 4 起由 NasMusicApp 注入）。
     * null = 未启用网易优先，行为与改造前逐字符一致（R1 的"新功能全走新增代码路径"）。
     */
    private val netease: com.nasmusic.tv.backend.network.netease.NeteaseOpenApi? = null,
```
（放在 `qualityTierProvider` 之后，作为最后一个带默认值的参数。）

```kotlin
    override suspend fun findArtist(name: String): NetworkArtistInfo? {
        // 本期只覆盖 netease 平台（§5 P3 已按 Q8 撤出其他平台）；DEFAULT_SERVER 是类内既有常量（:102）
        if (serverProvider() != DEFAULT_SERVER) return null
        return netease?.findArtist(name)
    }

    override suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int): ArtistSongsPage? {
        if (serverProvider() != DEFAULT_SERVER) return null
        val id = artistId.toLongOrNull() ?: run {
            AppLog.w(DIAG, "getArtistSongs: artistId 不是数字 '$artistId'")
            return null
        }
        return netease?.artistSongs(id, offset, limit)
    }
```

- [ ] **Step 6：`NetworkMusicManager` 两个转发**（放在 `:290 getPlaylist` 附近，注释风格一致）

```kotlin
    /**
     * 艺人发现：按**默认源**路由，不 fallback。
     *
     * 与 resolvePlayUrl/resolveLyrics 同语义（fallback 只属于 [search]）：
     * 艺人 id 是来源特定的，拿 plain 源的搜索结果去补一个网易 artistId 会串人。
     */
    suspend fun findArtist(name: String): NetworkArtistInfo? {
        if (name.isBlank()) return null
        val svc = services[defaultSource] ?: return null
        return try {
            svc.findArtist(name)
        } catch (e: Exception) {
            AppLog.w(TAG, "findArtist error: ${e.message}", e)
            null
        }
    }

    /** 艺人作品分页：按默认源路由，不 fallback */
    suspend fun getArtistSongs(artistId: String, offset: Int, limit: Int = 100): ArtistSongsPage? {
        val svc = services[defaultSource] ?: return null
        return try {
            svc.getArtistSongs(artistId, offset, limit)
        } catch (e: Exception) {
            AppLog.w(TAG, "getArtistSongs error: ${e.message}", e)
            null
        }
    }
```

- [ ] **Step 7：跑测试 + 全量单测**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NetworkArtistRoutingTest"
```
预期：3 例全绿。**再跑整轮** `testDebugUnitTest`（不带 `--tests`）确认 Jamendo / 百度侧无回归（默认实现的意义就在于此）。

- [ ] **Step 8：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/NetworkArtistTypes.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/NetworkArtistRoutingTest.kt
git commit -m "feat(network): 网络音乐服务接口增加艺人发现与作品分页默认实现"
```

---

### Task 4：网易优先 + 艺人展开接入统一搜索 ⚠️ **需 Q3 复确认后才动手**

> 本 Task 是**唯一把 `music.163.com` 接入线上路径**的任务（§9 Q3「需要先评估一下」→ 评估已交 §10，放行条件是 owner 点头）。Task 1–3 不新增直连域名，可以在等待复确认期间先完成。

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkArtistExpansion.kt`
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt:136-162`（`search()` 头部插网易优先分支，端点链一字未改）
- Modify: `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:303-308`（构造处注入 `NeteaseOpenApi`）
- Modify: `app/src/main/java/com/nasmusic/tv/ui/viewmodel/SearchViewModel.kt:42-46, 147-178, 183-186`（组合 expansion）
- Modify: `app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt:75`（`5_000L` → `8_000L`）
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/NeteasePrioritySearchTest.kt`
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/ArtistSongsPagingTest.kt`

**Interfaces:**
- Consumes: `NeteaseOpenApi.searchSongs/findArtist/artistSongs`（Task 2）、`NetworkMusicManager.findArtist/getArtistSongs`（Task 3）、`SongsPagingState`（`data/model/SongsPagingState.kt:6-12`，既有）、`ArtistSplitter.normalizeKey/containsArtistWithKey`（`util/ArtistSplitter.kt:47-63, 120-124`，既有）
- Produces:
  - `class NetworkArtistExpansion(getArtistSongs: suspend (String, Int, Int) -> ArtistSongsPage?, findArtist: suspend (String) -> NetworkArtistInfo?, pageSize: Int = 100)`
  - `suspend fun expand(keyword: String, networkPart: List<Song>): List<Song>` / `suspend fun loadMore(): List<Song>` / `fun reset()`
  - `val unplayableIds: Set<String>` / `val paging: SongsPagingState` / `fun isActive(): Boolean`
  - `companion`：`internal fun hitArtistId(keyword, songs): String?`、`internal fun playableFirst(songs, unplayableIds): List<Song>`、`internal fun mergeWithExpansion(otherPart, expanded): List<Song>`
  - `SearchViewModel.searchUnplayableIds: StateFlow<Set<String>>` / `SearchViewModel.artistPaging: StateFlow<SongsPagingState>` / `SearchViewModel.loadMoreArtistSongs()`（Task 8 的 UI 靠这三个）
  - `MetingApiService.search()` 的返回形状**不变**：仍是 `List<Song>`（§6 第 7 条）

- [ ] **Step 1：写失败测试**

`NeteasePrioritySearchTest.kt`（MockWebServer 同域双路径：`/api/search/get` = 网易原生，`/api` = Meting 端点。范本是 `MetingResolveTest.kt:5-7` 的 import 与 `Dispatcher` 用法）：

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.backend.network.netease.NeteaseOpenApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeteasePrioritySearchTest {

    private lateinit var server: MockWebServer
    private val nativeCalls = AtomicInteger()
    private val endpointCalls = AtomicInteger()
    /** 模拟 R1：网易原生接口下线 / 5xx */
    private var nativeDown = false

    private val type1Body = """{"api_code":200,"result":{"songs":[
      {"id":1001,"name":"晴天","duration":269000,
       "artists":[{"id":6452,"name":"周杰伦"}],
       "album":{"id":99,"name":"叶惠美"}}]}}"""

    private val endpointBody = """[{"title":"安静","author":"周杰伦",
      "url":"http://x/api?server=netease&type=url&id=2002",
      "pic":"http://x/api?server=netease&type=pic&id=2002"}]"""

    @Before
    fun setUp() {
        nativeCalls.set(0); endpointCalls.set(0); nativeDown = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/search/get") -> {
                        nativeCalls.incrementAndGet()
                        if (nativeDown) MockResponse().setResponseCode(500)
                        else MockResponse().setBody(type1Body)
                    }
                    path.startsWith("/api") -> {
                        endpointCalls.incrementAndGet()
                        MockResponse().setBody(endpointBody)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(): MetingApiService {
        val base = server.url("/api").toString().trimEnd('/')
        return MetingApiService(
            baseUrlProvider = { base },                       // 当前端点 = mock，端点链第一跳就命中
            serverProvider = { "netease" },
            netease = NeteaseOpenApi(baseUrlProvider = { base })   // 原生接口也指到同一个 mock
        )
    }

    @Test
    fun `网易原生可用时优先取原生结果且完全不打端点`() = runBlocking {
        val songs = service().search("周杰伦")
        assertEquals(1, songs.size)
        assertEquals("晴天", songs.first().title)
        assertEquals(1, nativeCalls.get())
        assertEquals("⛔ 网易命中后不得再打端点（这就是 Q2 的'优先'语义）", 0, endpointCalls.get())
    }

    @Test
    fun `网易原生失败必须自动回落端点且不得整批空`() = runBlocking {
        nativeDown = true
        val songs = service().search("周杰伦")
        assertEquals("验收 6-②：网易挂了也要出结果", 1, songs.size)
        assertEquals("安静", songs.first().title)
        assertTrue(endpointCalls.get() >= 1)
    }

    @Test
    fun `两条分支的结果形状逐字段同构（Q5 回归护栏）`() = runBlocking {
        val native = service().search("周杰伦")
        nativeDown = true
        val endpoint = service().search("周杰伦")
        for (list in listOf(native, endpoint)) {
            val s = list.single()
            assertTrue("网络段必须是 isNetworkSong", s.isNetworkSong)
            assertEquals("红线 2：两处都恒为 meting", "meting", s.networkSource)
            assertTrue("红线 1：两处同前缀", s.id.startsWith("ntwk_meting_"))
        }
    }
}
```

`ArtistSongsPagingTest.kt`（**纯 JVM 类，不加 Robolectric**——`NetworkArtistExpansion` 无 Android 依赖，省掉一轮 sandbox 启动）：

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistSongsPagingTest {

    private fun song(id: String, artist: String, artistId: String? = "6452") = Song(
        id = "ntwk_meting_$id", title = "t$id", artist = artist, artistId = artistId,
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    private fun page(ids: List<String>, dead: List<String>, more: Boolean, total: Int) = ArtistSongsPage(
        songs = ids.map { song(it, "周杰伦") },
        total = total,
        hasMore = more,
        unplayableIds = dead.map { "ntwk_meting_$it" }.toSet()
    )

    /** 记录调用次序的假后端：fetches 存 (artistId, offset) */
    private class Fake(val pages: MutableMap<Int, ArtistSongsPage?>, val fetches: MutableList<Pair<String, Int>>) {
        var findArtistCalls = 0
        fun expansion() = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; pages[offset] },
            findArtist = { _ -> findArtistCalls++; NetworkArtistInfo("6452", "周杰伦", 566) }
        )
    }

    @Test
    fun `强命中读首屏自带的 artistId 且零额外 findArtist`() = runBlocking {
        val f = Fake(
            mutableMapOf(0 to page((1..100).map { "$it" }, dead = listOf("7"), more = true, total = 566)),
            mutableListOf()
        )
        val exp = f.expansion()
        val out = exp.expand("周杰伦", listOf(song("900", "周杰伦"), song("901", "周杰伦")))
        assertEquals(0, f.findArtistCalls)
        assertEquals(NetworkArtistExpansion.HitSource.NATIVE_FIELD, exp.lastHitSource)
        assertEquals(100, out.size)
        assertEquals(listOf("6452" to 0), f.fetches)
        assertEquals(setOf("ntwk_meting_7"), exp.unplayableIds)
    }

    @Test
    fun `末页恰满 100 且 more=false 时不得再多发一次请求`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page((1..100).map { "$it" }, emptyList(), more = false, total = 100) },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        val afterFirst = fetches.size
        exp.loadMore()
        assertEquals("§2 D：more 已转 false，照 size==pageSize 猜就会多发一次空请求", afterFirst, fetches.size)
        assertFalse(exp.paging.hasMore)
    }

    @Test
    fun `续页按已加载条数取 offset 且累积打标集`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ ->
                fetches += id to offset
                page(((offset + 1)..(offset + 100)).map { "$it" }, dead = listOf("${offset + 1}"), more = offset < 400, total = 566)
            },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        exp.loadMore()
        exp.loadMore()
        assertEquals(listOf("6452" to 0, "6452" to 100, "6452" to 200), fetches)
        assertEquals(300, exp.paging.songs.size)
        assertEquals(3, exp.unplayableIds.size)
    }

    @Test
    fun `可播置顶保持稳定序且只作用于艺人分支`() {
        val dead = setOf("ntwk_meting_2", "ntwk_meting_3")
        val songs = listOf(song("1", "周杰伦"), song("2", "周杰伦"), song("3", "周杰伦"), song("4", "周杰伦"))
        val out = NetworkArtistExpansion.playableFirst(songs, dead)
        assertEquals(listOf("ntwk_meting_1", "ntwk_meting_4", "ntwk_meting_2", "ntwk_meting_3"), out.map { it.id })
        // 打标集为空（普通搜索分支 / 端点兜底分支的形状）→ 顺序必须原样不动
        val plain = listOf(song("5", "周杰伦"), song("6", "周杰伦"))
        assertEquals(plain.map { it.id }, NetworkArtistExpansion.playableFirst(plain, emptySet()).map { it.id })
    }

    @Test
    fun `弱匹配不算命中——周杰不得展开周杰伦的名录`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page(listOf("1"), emptyList(), more = false, total = 1) },
            findArtist = { null }      // type=100 兜底也没命中
        )
        val networkPart = listOf(song("900", "周杰伦"))
        val out = exp.expand("周杰", networkPart)
        assertEquals("§10.2 规则 4：前缀匹配是产品级事故", networkPart, out)
        assertTrue(fetches.isEmpty())
        assertFalse(exp.isActive())
        assertTrue(exp.unplayableIds.isEmpty())
    }

    @Test
    fun `feat 合唱行不算主艺人的反例要成立`() {
        // 关键词是主唱本人时，含 feat 的行仍应命中（ArtistSplitter 已拆 feat.）
        val withFeat = listOf(Song(id = "ntwk_meting_1", title = "x", artist = "周杰伦 / 彭濼",
            artistId = "6452", isNetworkSong = true, networkSource = "meting", networkId = "1"))
        assertEquals("6452", NetworkArtistExpansion.hitArtistId("彭濼", withFeat))
        // 但"周杰伦 / 彭濼"整体作为一个 artist 字段时，关键词"周杰伦"同样命中
        assertEquals("6452", NetworkArtistExpansion.hitArtistId("周杰伦", withFeat))
    }

    @Test
    fun `合并结果 id 必须唯一——重复 key 会让 LazyVerticalGrid 直接崩`() {
        val expanded = listOf(song("1", "周杰伦"), song("2", "周杰伦"))
        val other = listOf(song("1", "周杰伦"), song("9", "蔡依林"))     // 非网络段与展开段撞同一 netId
        val merged = NetworkArtistExpansion.mergeWithExpansion(other, expanded)
        assertEquals(3, merged.size)
        assertEquals(merged.size, merged.map { it.id }.distinct().size)
        // distinctBy 保 first-wins：非网络段在前（mergeWithExpansion 的入参次序），撞车的展开段被丢弃
        assertEquals(listOf("ntwk_meting_1", "ntwk_meting_9", "ntwk_meting_2"), merged.map { it.id })
    }

    @Test
    fun `reset 后不再续取（R7 切艺人即重置）`() = runBlocking {
        val fetches = mutableListOf<Pair<String, Int>>()
        val exp = NetworkArtistExpansion(
            getArtistSongs = { id, offset, _ -> fetches += id to offset; page(listOf("1"), emptyList(), more = true, total = 566) },
            findArtist = { null }
        )
        exp.expand("周杰伦", listOf(song("900", "周杰伦")))
        exp.reset()
        assertFalse(exp.isActive())
        assertEquals(0, exp.loadMore().size)
        assertTrue(exp.unplayableIds.isEmpty())
    }
}
```

> 📌 **「expansion 自己不知道自己被调了几次 `findArtist`」是实现上一个真实的设计缺口**，所以 Step 3 的类必须暴露 `var lastHitSource: HitSource`（`NONE` / `NATIVE_FIELD` / `FIND_ARTIST`）——它同时服务两件事：这条"零额外请求"断言（免鉴权原生字段命中 = 不花钱）与 §10.5 的诊断日志。计数交给 `Fake.findArtistCalls`，状态真相交给 `lastHitSource`，两者一起断言。

- [ ] **Step 2：跑两个测试确认失败**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NeteasePrioritySearchTest" --tests "*ArtistSongsPagingTest"
```
预期：编译失败（`NetworkArtistExpansion` 未定义、`MetingApiService` 无 `netease` 形参的调用点已存在——Task 3 已加，所以只会缺 `search()` 的网易分支与 expansion）。

- [ ] **Step 3：新增 `NetworkArtistExpansion.kt`**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongsPagingState
import com.nasmusic.tv.util.ArtistSplitter

/**
 * 艺人全量展开的**状态件**（艺人全量方案 §3.5）。
 *
 * 装进独立类而不是往 `SearchViewModel` 堆字段：VM 已 359 行、刚做完 R-1 拆分，
 * 且 AndroidViewModel 在本仓库**不可单测**——本类无 Android 依赖，纯 JVM 测试直接打。
 *
 * ⛔ 不做 IO：所有取数走构造期注入的两个 suspend lambda，
 * 由 `SearchViewModel` 接到 `NetworkMusicManager`（存在理由同 `NetworkPlaybackResolver`）。
 */
class NetworkArtistExpansion(
    private val getArtistSongs: suspend (artistId: String, offset: Int, limit: Int) -> ArtistSongsPage?,
    private val findArtist: suspend (name: String) -> NetworkArtistInfo?,
    private val pageSize: Int = DEFAULT_PAGE_SIZE
) {
    /** 命中来源，仅供诊断日志与单测断言（决定"这次展开有没有多花一次 type=100 请求"） */
    enum class HitSource { NONE, NATIVE_FIELD, FIND_ARTIST }

    var lastHitSource: HitSource = HitSource.NONE
        private set

    private var artistId: String? = null
    private var total = 0
    private var more = false
    private val unplayable = linkedSetOf<String>()
    private val fetched = mutableListOf<Song>()

    val unplayableIds: Set<String> get() = unplayable.toSet()

    fun isActive(): Boolean = artistId != null

    /** 交 UI 的分页状态（`hasMore` **只**来自接口 `more`，§3.4） */
    val paging: SongsPagingState
        get() = SongsPagingState(
            songs = fetched.toList(),
            totalCount = total,
            isLoading = false,
            hasMore = more,
            currentPage = if (pageSize <= 0) 0 else fetched.size / pageSize
        )

    /** 换词 / 换艺人即重置（R7：分页状态只存当前艺人，不入全局缓存） */
    fun reset() {
        artistId = null
        total = 0
        more = false
        unplayable.clear()
        fetched.clear()
        lastHitSource = HitSource.NONE
    }

    /**
     * 关键词命中艺人则取第一页全量，否则**原样返回**网络段。
     *
     * @param networkPart 融合结果里 `isNetworkSong` 的那一段（`type=1` 或 Meting 端点兜底）
     */
    suspend fun expand(keyword: String, networkPart: List<Song>): List<Song> {
        reset()
        val id = hitArtistId(keyword, networkPart)
        if (id != null) {
            lastHitSource = HitSource.NATIVE_FIELD
        } else {
            // §10.2 规则 3：只有首屏无命中才发这**一次**兜底请求（端点兜底分支没有 artistId 时走这里）
            val found = findArtist(keyword)?.takeIf {
                ArtistSplitter.normalizeKey(it.name) == ArtistSplitter.normalizeKey(keyword)
            }
            if (found == null) return networkPart
            artistId = found.id
            lastHitSource = HitSource.FIND_ARTIST
        }
        val artist = artistId ?: id
        val page = getArtistSongs(artist, 0, pageSize) ?: run {
            reset()
            return networkPart
        }
        accumulate(page)
        return playableFirst(fetched.toList(), unplayable)
    }

    /** 触底续页。未命中 / 已到底 → 原样返回现有行，⛔ 不发请求（§6 验收 5 + §2 D） */
    suspend fun loadMore(): List<Song> {
        val artist = artistId ?: return emptyList()
        if (!more) return playableFirst(fetched.toList(), unplayable)
        val page = getArtistSongs(artist, fetched.size, pageSize) ?: run {
            more = false
            return playableFirst(fetched.toList(), unplayable)
        }
        accumulate(page)
        return playableFirst(fetched.toList(), unplayable)
    }

    private fun accumulate(page: ArtistSongsPage) {
        fetched += page.songs
        unplayable += page.unplayableIds
        total = page.total
        more = page.hasMore
        if (artistId == null) artistId = page.songs.firstOrNull()?.artistId
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * 强命中判定（§10.2）：归一化全等 + 取"首屏出现次数最多"的 artistId。
         *
         * ⛔ 不做前缀/模糊匹配；⛔ 不新建匹配器（`ArtistSplitter` 已处理 `／ & 、 feat.`）。
         */
        internal fun hitArtistId(keyword: String, songs: List<Song>): String? {
            val key = ArtistSplitter.normalizeKey(keyword)
            if (key.isBlank()) return null
            val ids = songs.mapNotNull { s ->
                val id = s.artistId
                if (id != null && ArtistSplitter.containsArtistWithKey(s.artist, key)) id else null
            }
            if (ids.isEmpty()) return null
            return ids.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        }

        /**
         * 可播置顶（Q1 的 c）：`st == 0` 在前、打标行在后，**两组内部各自保序**。
         *
         * ⚠️ 只对**艺人分支**调用；打标集为空时等价于原序返回（普通搜索分支/端点兜底分支保持上游顺序）。
         * ⛔ 这里绝不 `filter` —— T2 明令 `privilege.st` 只能打标不能过滤。
         */
        internal fun playableFirst(songs: List<Song>, unplayableIds: Set<String>): List<Song> {
            if (unplayableIds.isEmpty()) return songs
            val (ok, dead) = songs.partition { it.id !in unplayableIds }
            return ok + dead
        }

        /**
         * 非网络段（NAS / 百度 / Jamendo / 本地）+ 展开后的网络段，按 id 去重。
         *
         * ⚠️ 去重不是洁癖：`SearchTab` 的网格用 `key = { searchResults[it].id }`
         * （`ui/screens/library/SearchTab.kt:146`），**同 id 两次出现会直接抛
         * "Key was already used"**。展开段与 `type=1` 首屏共享 id 命名空间（红线 1），
         * 撞车是**必然**而不是偶然。
         */
        internal fun mergeWithExpansion(otherPart: List<Song>, expanded: List<Song>): List<Song> =
            (otherPart + expanded).distinctBy { it.id }
    }
}
```

- [ ] **Step 4：`MetingApiService.search()` 插网易优先**（`Kotlin` 片段是 `:136-162` 的**头部插入**，端点串行链 `:143-161` 一字未改）

```kotlin
    override suspend fun search(keyword: String, limit: Int): List<Song> = withContext(Dispatchers.IO) {
        AppLog.i(DIAG, "=== search start === keyword='$keyword'")
        if (keyword.isBlank()) {
            AppLog.i(DIAG, "search: keyword blank, return empty")
            return@withContext emptyList()
        }

        // Q2「网易优先」：客户端已注入且当前平台是 netease 时，先打免鉴权原生接口（§10.3 子闸门 2.5s）。
        // ⚠️ netease == null（既有单测、以及任何未注入的构造点）时行为与改造前**逐字符一致**——
        //   R1/R10 的"新功能全走新增代码路径"在这一行落地。
        if (netease != null && serverProvider() == DEFAULT_SERVER) {
            val nativeSongs = try {
                netease.searchSongs(keyword)
            } catch (e: Exception) {
                // 网易随时下线（R1）是预期内常态：这里只降级，绝不上抛、绝不返回空集
                AppLog.w(DIAG, "search: netease-native threw ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
            if (nativeSongs.isNotEmpty()) {
                AppLog.i(DIAG, "search: netease-native hit=${nativeSongs.size}, endpoint chain skipped")
                AppLog.i(DIAG, "=== search end ===")
                return@withContext nativeSongs
            }
            AppLog.w(DIAG, "search: netease-native empty/offline, falling back to endpoint chain")
        }

        // 构造端点尝试顺序：当前选中的端点优先，然后是其他预设端点
        val currentBase = baseUrl
        ...（以下 `:143-161` 原样保留）
```

> `DEFAULT_SERVER` 是类内既有私有常量（`MetingApiService.kt:102`，值 `"netease"`）——**不要**再写字面量，两处 `serverProvider()` 判断（Task 3 的两个覆写 + 这里）必须同源。
>
> ⚠️ `MetingApiService` 的 `client` readTimeout 是 15s（`:69-70`），而 `NeteaseOpenApi` 自带 2.5s（Task 2）——**"网易优先"这一跳的耗时上限由后者决定**，不要为了"统一"把 `NeteaseOpenApi` 的超时改回 15s，那会直接击穿 §10.3 的预算。

- [ ] **Step 5：`NasMusicApp` 注入**（`:303-308` 的构造处，紧随 `qualityTierProvider`）

```kotlin
        val services = mapOf(
            "meting" to MetingApiService(
                baseUrlProvider = { appPreferences.network.getMetingApiBaseUrlSync() },
                serverProvider = { appPreferences.network.getMusicSourceSync() },
                // F2-6：音质档位（AUTO=0 不传 br；显式档位走降级链）
                qualityTierProvider = { appPreferences.getQualityTierSync() },
                // 艺人全量方案 Q2：网易免鉴权优先。基址复用歌词那个设置项（不新增设置项）
                netease = NeteaseOpenApi(
                    baseUrlProvider = { appPreferences.lyrics.getLyricsNeteaseBaseUrlSync() }
                )
            )
        )
```

- [ ] **Step 6：`SearchViewModel` 组合 expansion**（不新增 Screen/VM，Q2 的落点）

新增成员（放在 `:42-46` 的 `_searchResults` 声明组之后）：

```kotlin
    // --- 艺人全量展开（艺人全量方案 §3.5；状态在 NetworkArtistExpansion，VM 只做接线） ---
    private val artistExpansion = NetworkArtistExpansion(
        getArtistSongs = { id, offset, limit ->
            nasMusicApp.networkMusicManager.getArtistSongs(id, offset, limit)
        },
        findArtist = { name -> nasMusicApp.networkMusicManager.findArtist(name) }
    )
    private val _searchUnplayableIds = MutableStateFlow<Set<String>>(emptySet())
    val searchUnplayableIds: StateFlow<Set<String>> = _searchUnplayableIds.asStateFlow()
    private val _artistPaging = MutableStateFlow(com.nasmusic.tv.data.model.SongsPagingState())
    val artistPaging: StateFlow<com.nasmusic.tv.data.model.SongsPagingState> = _artistPaging.asStateFlow()
    /** 融合结果里非网络段的那一部分：续页时与展开段重新合并（网络段整体替换） */
    private var lastNonNetworkSongs: List<Song> = emptyList()
```

`searchSongsOnServer` 里 `:162-163` 两行替换为：

```kotlin
                val songs = result.allResults.map { it.song }
                // 网络段（含网易原生/Meting 兜底）整体交给 expansion，非网络段原样保留
                val (networkPart, otherPart) = songs.partition { it.isNetworkSong }
                lastNonNetworkSongs = otherPart
                val expanded = artistExpansion.expand(query, networkPart)
                val merged = NetworkArtistExpansion.mergeWithExpansion(otherPart, expanded)
                _searchResults.value = UiState.Success(merged)
                _searchUnplayableIds.value = artistExpansion.unplayableIds
                _artistPaging.value = artistExpansion.paging
                AppLog.i(
                    "MetingDiag",
                    "searchSongsOnServer: '$query' merged=${merged.size} (network ${networkPart.size}→${expanded.size}) hit=${artistExpansion.lastHitSource}"
                )
```

新增公开方法与 `clearSearch()` 的重置：

```kotlin
    /** 触底续页：仅当本词命中艺人时才有意义（UI 侧由 shouldLoadMore 保证不会空转） */
    fun loadMoreArtistSongs() {
        if (!artistExpansion.isActive()) return
        if (!artistExpansion.paging.hasMore || artistExpansion.paging.isLoading) return
        viewModelScope.launch {
            _artistPaging.value = artistExpansion.paging.copy(isLoading = true)
            val merged = NetworkArtistExpansion.mergeWithExpansion(
                lastNonNetworkSongs, artistExpansion.loadMore()
            )
            _searchResults.value = UiState.Success(merged)
            _searchUnplayableIds.value = artistExpansion.unplayableIds
            _artistPaging.value = artistExpansion.paging
        }
    }
```

```kotlin
    fun clearSearch() {
        lastSearchedKeyword = null
        artistExpansion.reset()
        lastNonNetworkSongs = emptyList()
        _searchUnplayableIds.value = emptySet()
        _artistPaging.value = com.nasmusic.tv.data.model.SongsPagingState()
        _searchResults.value = UiState.Success(emptyList())
    }
```

> ⚠️ **`artistPaging` 的 `isLoading` 是 `copy(isLoading = true)` 造出来的临时值**，`NetworkArtistExpansion` 自己没有 `isLoading` 字段——所以 `_artistPaging` 在请求返回前必须至少有一次 `isLoading = false` 的落值（上面代码里就是 `loadMore()` 之后那次赋值）。若把 `isLoading` 塞回 expansion 内部，§8 的"少则不分页"结论（`hasMore=false → shouldLoadMore` 恒 false）不受影响，但 Task 8 的 `remember(songs, paging, isSearching)` key 会因每次 `copy` 产生新实例而**每帧重算**，别这样写。

- [ ] **Step 7：`SearchAggregator` 预算放宽一行**（`backend/SearchAggregator.kt:75`）

```kotlin
        // 艺人全量方案 §10.3：网易优先 2.5s 子闸门 + Meting 端点链 p95 上界 ≈4.85s（§2 L 组）
        // → 原 5s 会在"网易先跑满再走端点"的串行链上直接超时。
        // ⚠️ 放宽到 8s **不拖累整页**：分支 async 后逐个 await（`:291-298`），总上界取 max，
        //   而 NAS_TIMEOUT = 15_000L（`:74`）仍更大——见 §9.1 T3。
        private const val NETWORK_TIMEOUT = 8_000L
```

- [ ] **Step 8：跑测试 + 全量回归**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NeteasePrioritySearchTest" --tests "*ArtistSongsPagingTest"
```
预期：全绿（3 + 8 例）。**再跑整轮 `testDebugUnitTest`**，重点看 `MetingResolveTest`（`netease` 默认 null，网易分支不参与 → 必须零改动通过）与 `SearchAggregator` 相关用例（超时常量不改变行为，只改边界）。

- [ ] **Step 9：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/NetworkArtistExpansion.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt \
        app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt \
        app/src/main/java/com/nasmusic/tv/ui/viewmodel/SearchViewModel.kt \
        app/src/main/java/com/nasmusic/tv/NasMusicApp.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/NeteasePrioritySearchTest.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/ArtistSongsPagingTest.kt
git commit -m "feat(network): 统一搜索接入网易免鉴权优先与艺人全量展开"
```

---

### Task 5：`PlayabilityProbe` —— 判据做实（§3.8 第 1 步 / Q4）+ 负面缓存

> **这是后面两个 Task 的地基**：Task 6 的候选校验、Task 7 的替换触发都依赖"DEAD 是个真信号"。R4 的假 302 今天在 `MetingApiService.kt:271-276` 被当成成功（拿到非空 `Location` 就 `return`），于是 §3.8 表格里那条现成的降级链**一次都不会被触发**（错位 1）。

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/PlayabilityProbe.kt`
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/ResolveResult.kt:14-32`（加 1 个带默认值的字段 + `failure` 加 1 个带默认值的形参）
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt:52`（构造参数）+ `:250-285`（`resolvePlayUrlDetailed` 全方法替换）
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt`（`:42` 常量、`:67` 缓存字段、`:97-101` clear、`:190-222` 读写）
- Modify: `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt:303-308`（注入真实探测）
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/PlayabilityProbeTest.kt`
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/MetingProbeWiringTest.kt`
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/NetworkDeadProbeCacheTest.kt`

**Interfaces:**
- Consumes: `BackendRegistry.sharedDispatcher` / `sharedConnectionPool`（`internal`，同模块可见；先例已核实 `MetingApiService.kt:67-68`）
- Produces:
  - `enum class Playability { PLAYABLE, DEAD, UNKNOWN }`
  - `interface PlayabilityProbe { fun probe(url: String): Playability }`（**接口**，不是类——§3.8 要求"单测全部用 lambda 注入假探测，⛔ 不打网络"）
  - `class HttpPlayabilityProbe(client: OkHttpClient = defaultClient()) : PlayabilityProbe`
  - `HttpPlayabilityProbe.classify(code: Int, contentType: String?): Playability`（companion 上的 `internal` 纯函数）
  - `ResolveResult(url: String?, actualQuality: Int, probedDead: Boolean = false)`
  - `MetingApiService(..., probe: PlayabilityProbe? = null)`（接在 Task 4 的 `netease` 之后，仍是最后一个带默认值的参数）

- [ ] **Step 1：写失败测试（判据本体，验收 12）**

```kotlin
package com.nasmusic.tv.backend.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 可播判据测试（艺人全量方案 §3.8 第 1 步，§6 验收 12）。
 *
 * 这条测试是 **R4 的护栏**：改造前"302 → /404.html"会被当成解析成功，
 * 缺它则整轮判据改动可能被"看起来能播"的回归悄悄掩盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayabilityProbeTest {

    private lateinit var server: MockWebServer

    // 判据的本质是"跟一跳拿最终响应的 Content-Type"，所以这个 client **必须** followRedirects
    private val probe = HttpPlayabilityProbe(
        OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    )

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    /** §6 验收 12 上半：302 → 404 text/html ⇒ DEAD */
    @Test
    fun `假 302 落到 html 页面必须判 DEAD`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/404.html").toString())
        )
        server.enqueue(
            MockResponse().setResponseCode(404)
                .addHeader("Content-Type", "text/html")
                .setBody("<html><body>Not Found</body></html>")
        )
        assertEquals(
            Playability.DEAD,
            probe.probe(server.url("/api?server=netease&type=url&id=1").toString())
        )
    }

    /** §6 验收 12 下半：302 → audio/mpeg ⇒ PLAYABLE */
    @Test
    fun `真 302 落到音频必须判 PLAYABLE`() {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .addHeader("Location", server.url("/real.mp3").toString())
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg")
                .setBody("ID3")
        )
        assertEquals(Playability.PLAYABLE, probe.probe(server.url("/api?type=url&id=2").toString()))
    }

    /** 探测只取 1 字节，⛔ 不许把整首歌下下来 */
    @Test
    fun `探测请求带 Range 只取首字节`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg").setBody("x")
        )
        assertEquals(Playability.PLAYABLE, probe.probe(server.url("/a.mp3").toString()))
        val recorded = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("bytes=0-0", recorded?.getHeader("Range"))
    }

    @Test
    fun `探测失败只能是 UNKNOWN——⛔ 不许把网络抖动判成不可播`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(Playability.UNKNOWN, probe.probe(server.url("/api").toString()))
    }

    /** classify 的完整档位表（无 IO，R4 护栏的全覆盖面） */
    @Test
    fun `classify 只认证据、不确定一律留给上游`() {
        fun c(code: Int, ct: String?) = HttpPlayabilityProbe.classify(code, ct)
        // 明确的"这里没有歌"
        assertEquals(Playability.DEAD, c(404, null))
        assertEquals(Playability.DEAD, c(403, "text/html"))
        assertEquals(Playability.DEAD, c(410, "text/html"))
        assertEquals(Playability.DEAD, c(451, "text/html"))       // 地域版权屏蔽
        assertEquals(Playability.DEAD, c(200, "text/html; charset=utf-8"))
        assertEquals(Playability.DEAD, c(200, "application/json"))
        // 媒体
        assertEquals(Playability.PLAYABLE, c(200, "audio/mpeg"))
        assertEquals(Playability.PLAYABLE, c(206, "Audio.MPEG"))   // Range 命中 + 大小写
        assertEquals(Playability.PLAYABLE, c(200, "video/mp4"))
        // 不确定 ⇒ UNKNOWN（绝不能当成 DEAD，见实现注释）
        assertEquals(Playability.UNKNOWN, c(200, null))
        assertEquals(Playability.UNKNOWN, c(200, "  "))
        assertEquals(Playability.UNKNOWN, c(200, "application/octet-stream"))
        assertEquals(Playability.UNKNOWN, c(500, "text/html"))     // 服务端故障是环境态，不是"这首歌没了"
        assertEquals(Playability.UNKNOWN, c(302, "audio/mpeg"))    // 跟链没走完
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*PlayabilityProbeTest"
```
预期：编译失败（`Playability` / `HttpPlayabilityProbe` 未定义）。

- [ ] **Step 3：新增 `PlayabilityProbe.kt`**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一次直链可播性判定的三态结果（艺人全量方案 §3.8 第 1 步）。 */
enum class Playability {
    /** 最终响应是音频/视频——真的能出声 */
    PLAYABLE,

    /** 最终响应明确不是媒体（404/403/410/451、html/json/text）——即 R4 的假 302 */
    DEAD,

    /** 探测本身失败、超时或类型未知。⛔ 绝不按 DEAD 处理 */
    UNKNOWN
}

/**
 * 可播判据。
 *
 * 之所以是**接口**而不是类：§3.8 要求"单测全部用 lambda 注入假探测、不打网络"，
 * 而真实探测必须跟一次公网 CDN。生产代码只认 [HttpPlayabilityProbe]。
 */
interface PlayabilityProbe {
    fun probe(url: String): Playability
}

/**
 * 真实探测：跟随重定向一跳，用**最终响应的 Content-Type** 判定。
 *
 * 请求带 `Range: bytes=0-0`——只需要响应头，⛔ 不允许把整首歌拉下来（R3 限流 + 电视带宽）。
 * ⚠️ 探测**只用当前配置的端点**、并发由调用方串行保证（Task 6 的候选循环一次只探一个）。
 */
class HttpPlayabilityProbe(
    private val client: OkHttpClient = defaultClient()
) : PlayabilityProbe {

    override fun probe(url: String): Playability = try {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(request).execute().use { response ->
            classify(response.code, response.header("Content-Type"))
        }
    } catch (e: Exception) {
        // 超时/DNS/TLS 失败 → UNKNOWN：让上游维持"非空即成功"的旧行为，
        // 一次网络抖动绝不能把一首好歌打成"不可播"并缓存 5 分钟
        AppLog.w(DIAG, "probe: ${e.javaClass.simpleName} ${e.message} → UNKNOWN")
        Playability.UNKNOWN
    }

    companion object {
        private const val DIAG = "MetingDiag"
        private const val PROBE_TIMEOUT_MS = 3_000L

        internal fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(com.nasmusic.tv.backend.BackendRegistry.sharedDispatcher)
            .connectionPool(com.nasmusic.tv.backend.BackendRegistry.sharedConnectionPool)
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 判据的本体：必须**跟随**重定向才能量到 CDN，而不是端点自己
            .followRedirects(true)
            .build()

        /**
         * 纯判据（无 IO，单测直打）。
         *
         * 保守方向是**单向**的：不确定一律 UNKNOWN，由调用方按"可播"放行。
         * 理由——UNKNOWN 误判成 DEAD 会让本可播的歌被跳过（用户体感"莫名切歌"，且会被负面缓存钉住 5 分钟）；
         * DEAD 误判成可播则只是 ExoPlayer 自己报错、走既有跳过链。前者更糟。
         */
        internal fun classify(code: Int, contentType: String?): Playability = when {
            code == 404 || code == 403 || code == 410 || code == 451 -> Playability.DEAD
            code !in 200..299 -> Playability.UNKNOWN
            contentType.isNullOrBlank() -> Playability.UNKNOWN
            else -> {
                val ct = contentType.lowercase()
                when {
                    ct.startsWith("audio") || ct.startsWith("video") -> Playability.PLAYABLE
                    ct.contains("html") || ct.contains("json") || ct.startsWith("text/") -> Playability.DEAD
                    else -> Playability.UNKNOWN
                }
            }
        }
    }
}
```

- [ ] **Step 4：`ResolveResult` 加降级信号**

`:14-17` 的类体与 `:29-32` 的 companion 改为（**字段带默认值**，所以 `QualityProbeTest` / `DowngradePersistTest` / `LocalPlaybackPriorityTest` 里 30+ 处 `ResolveResult(...)` 构造点零改动）：

```kotlin
data class ResolveResult(
    val url: String?,
    val actualQuality: Int,
    /**
     * 失败是否由**探测确认的 DEAD** 造成（艺人全量方案 §3.8 第 1 步）。
     *
     * ⚠️ 只有 true 才允许写负面缓存："端点没给链接"（网络抖动、id 过期）与
     *   "给了但跟链确认是假 302" 是两件事，前者钉 5 分钟会把偶发失败放大成静默跳曲。
     */
    val probedDead: Boolean = false
) {
    /** 是否解析成功 */
    val isSuccess: Boolean get() = !url.isNullOrBlank()

    fun isDowngradedFrom(requested: Int): Boolean =
        isSuccess && requested != QualityTiers.AUTO && actualQuality != requested

    companion object {
        /** 解析失败（无源可降）；`probedDead=true` 表示失败原因是探测判定的假 302 */
        fun failure(requested: Int, probedDead: Boolean = false): ResolveResult =
            ResolveResult(null, requested, probedDead)
    }
}
```

- [ ] **Step 5：`MetingApiService` 接线**（`:250-285` 整个方法替换；`:236-249` 的 KDoc 只在末尾补一行）

构造参数（接在 Task 4 的 `netease` 之后）：

```kotlin
    /**
     * 可播判据（§3.8 第 1 步，Q4 裁决）。null = 不探测，维持既有"拿到非空 Location 即成功"。
     * 生产由 NasMusicApp 注入 [HttpPlayabilityProbe]；单测注入假探测（⛔ 不打网络）。
     */
    private val probe: PlayabilityProbe? = null
```

方法体：

```kotlin
    override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult =
        withContext(Dispatchers.IO) {
            val netId = song.networkId ?: return@withContext ResolveResult(song.streamUrl, quality)
            val server = serverProvider()
            val endpoints = buildEndpointFallbackOrder(baseUrl)
            // 档位与降级链（999 → 320 → 192 → 128；AUTO 直接不传 br 走端点默认）
            val brChain = QualityTiers.fallbackChainOf(quality)
            var probedDead = false
            for (endpoint in endpoints) {
                for (br in brChain) {
                    try {
                        val brParam = if (br != null) "&br=$br" else ""
                        val url = "$endpoint?server=$server&type=url&id=${URLEncoder.encode(netId, "UTF-8")}$brParam"
                        val request = Request.Builder().url(url).build()
                        var playUrl: String? = null
                        var fromRedirect = false
                        noRedirectClient.newCall(request).execute().use { response ->
                            val location = response.header("Location")
                            when (response.code) {
                                302 -> {
                                    playUrl = location
                                    fromRedirect = !location.isNullOrBlank()
                                }
                                200 -> playUrl = response.body?.string()?.let { extractUrlFromJson(it) }
                                else -> null
                            }
                        }
                        // 必须复制进一个未被 lambda 捕获的局部 val：playUrl 在 use{} 里被赋值，
                        // Kotlin 对被闭包捕获的 var **不做智能转换**，直接用会编译不过
                        val resolved = playUrl
                        if (!resolved.isNullOrBlank()) {
                            // br == null（AUTO 档）时 actualQuality 记为请求档位本身，不产生降级提示
                            val actual = br ?: quality
                            // §3.8 第 1 步（Q4）：判据**只加在跟链那一侧**（302 → Location → 跟一跳）。
                            // 200-JSON 分支不产生重定向链，维持既有"非空即成功"（§6 验收 16 的回归口径，
                            // 百度网盘 / jamendo 走的是接口默认实现，本方法根本不参与）。
                            // ⛔ 只有 DEAD 才 continue；UNKNOWN 与 PLAYABLE 一律放行。
                            if (fromRedirect && probe?.probe(resolved) == Playability.DEAD) {
                                probedDead = true
                                AppLog.w(TAG, "resolvePlayUrl: probe DEAD via '$endpoint' br=$br for netId=$netId")
                                continue   // 只跳内层 for(br)：该端点×该档失败，继续降级链
                            }
                            AppLog.d(TAG, "resolvePlayUrl: resolved via '$endpoint' br=$br (actual=$actual) for netId=$netId")
                            return@withContext ResolveResult(resolved, actual)
                        }
                        AppLog.w(TAG, "resolvePlayUrl: empty from '$endpoint' br=$br for netId=$netId")
                    } catch (e: Exception) {
                        AppLog.w(TAG, "resolvePlayUrl: endpoint '$endpoint' br=$br failed: ${e.message}")
                    }
                }
            }
            AppLog.w(TAG, "resolvePlayUrl: all endpoints failed for netId=$netId (requested=$quality) probedDead=$probedDead")
            ResolveResult.failure(quality, probedDead)
        }
```

> ⚠️ **`probedDead` 是"曾经探到过 DEAD"而不是"最后一次失败的原因是 DEAD"**。全链失败时只要有一跳被确认假 302，就值得进负面缓存（下一首点播同一首时不必再花一次跟链）；而"所有端点都只是没给链接"（环境态）不给缓存——这正是 `probedDead` 与"失败"的区别。
>
> ⚠️ `continue` 只跳**内层** `for (br)`，与既有降级链语义一致（"该端点×该档失败 → 试下一档"）；端点切换仍由外层循环完成。**不要**改成 `break` 或标签 continue，那会让"遍历全部端点"的既有意义（§4 第三条）改变。

- [ ] **Step 6：写并跑接线测试 `MetingProbeWiringTest`**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 判据接线测试（§6 验收 12/16 的代码侧形态）。
 *
 * ⚠️ 断言纪律沿用 `MetingResolveTest.kt:191-202` 的既有教训：
 * **绝不断言"全链失败 ⇒ url == null"**——`buildEndpointFallbackOrder` 会在首选端点之后
 * 追加 3 个真实公网预设端点，测试环境可能真的拿到直链，那样断言必然 flaky。
 * 因此这里只在**首选端点就能定案**的路径上断言最终 url（PLAYABLE / UNKNOWN / 200-JSON），
 * DEAD 那条只断言"探测被问过、且假 302 直链没被当成结果返回"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MetingProbeWiringTest {

    private lateinit var server: MockWebServer

    /** 假探测：按 URL 查表，未登记的档位默认 UNKNOWN；记录被问过哪些 URL */
    private class FakeProbe(var verdicts: Map<String, Playability> = emptyMap()) : PlayabilityProbe {
        val asked = mutableListOf<String>()
        override fun probe(url: String): Playability {
            asked += url
            return verdicts[url] ?: Playability.UNKNOWN
        }
    }

    private val deadUrl get() = server.url("/fake-redirect.html").toString()
    private val okUrl get() = server.url("/real.mp3").toString()

    /** true ⇒ 端点回 200 JSON；false ⇒ 端点回 302 + Location */
    private var asJson = false
    private var location: String? = null

    @Before
    fun setup() {
        asJson = false
        location = null
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = location ?: return MockResponse().setResponseCode(200).setBody("{}")
                return if (asJson) MockResponse().setResponseCode(200).setBody("""{"url":"$target"}""")
                else MockResponse().setResponseCode(302).addHeader("Location", target)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service(probe: PlayabilityProbe?) = MetingApiService(
        baseUrlProvider = { server.url("/api").toString().trimEnd('/') },
        serverProvider = { "netease" },
        probe = probe
    )

    private val song = Song(
        id = "ntwk_meting_1001", title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "meting", networkId = "1001"
    )

    @Test
    fun `302 直链探测 DEAD ⇒ 不作为结果返回`() = runBlocking {
        location = deadUrl
        val probe = FakeProbe(mapOf(deadUrl to Playability.DEAD))
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals("假 302 必须被问过探测", listOf(deadUrl), probe.asked)
        assertTrue("DEAD 直链绝不能当成功返回（R4 的本体）", r.url != deadUrl)
    }

    @Test
    fun `302 直链探测 PLAYABLE ⇒ 原样返回`() = runBlocking {
        location = okUrl
        val probe = FakeProbe(mapOf(okUrl to Playability.PLAYABLE))
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals(okUrl, r.url)
        assertEquals(listOf(okUrl), probe.asked)
    }

    @Test
    fun `探测 UNKNOWN ⇒ 维持旧的'非空即成功'，⛔ 不得劣化`() = runBlocking {
        location = okUrl
        val probe = FakeProbe()          // 全部 UNKNOWN
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals("探测拿不准时必须放行（否则超时抖动会误杀好歌）", okUrl, r.url)
    }

    @Test
    fun `200-JSON 分支不产生重定向链 ⇒ 一次都不探测`() = runBlocking {
        asJson = true                    // §6 验收 16：这条分支必须保持原判据
        location = okUrl
        val probe = FakeProbe(mapOf(okUrl to Playability.DEAD))
        val r = service(probe).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals(okUrl, r.url)
        assertTrue("200 JSON 直返不该跟链，不该被探测", probe.asked.isEmpty())
    }

    @Test
    fun `probe 为 null ⇒ 与改造前逐字节一致`() = runBlocking {
        location = deadUrl
        val r = service(null).resolvePlayUrlDetailed(song, QualityTiers.AUTO)
        assertEquals("未注入探测时旧行为不变（MetingResolveTest 的 15 例全靠这条护栏）", deadUrl, r.url)
    }
}
```

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*MetingProbeWiringTest" --tests "*MetingResolveTest"
```
预期：新 5 例全绿，**`MetingResolveTest` 原 15 例零改动全绿**（`probe` 默认 null）。

- [ ] **Step 7：`NetworkMusicManager` 负面缓存**（验收 13）

四处改动，全部贴既有直链缓存的写法：

```kotlin
    companion object {
        ...
        /** 播放链接缓存容量上限（超出按时间淘汰最旧，防止超长会话内存只增不减） */
        private const val PLAY_URL_CACHE_MAX = 500
        // 艺人全量方案 §3.8 第 1 步：确认过的"假 302"负面判定，与直链缓存同 TTL（避免同一首反复探测）
        private const val DEAD_PROBE_TTL_MS = PLAY_URL_CACHE_TTL_MS
    }
```

```kotlin
    /** 播放直链内存缓存：cacheKey → CachedPlayUrl（线程安全，resolvePlayUrl 在 IO 线程并发访问） */
    private val playUrlCache = ConcurrentHashMap<String, CachedPlayUrl>()

    /**
     * 负面探测缓存：cacheKey → 判定时间戳（§3.8 第 1 步）。
     * key 与 [playUrlKey] 同构（含档位）→ **切档位必然重探**，对齐验收 13。
     */
    private val deadProbeCache = ConcurrentHashMap<String, Long>()
```

```kotlin
    /** 清空全部播放直链缓存（含负面探测缓存）。档位变化时由 AppPreferences.setQualityTier() 调用（修 G5）。 */
    fun clearPlayUrlCache() {
        val size = playUrlCache.size
        val deadSize = deadProbeCache.size
        playUrlCache.clear()
        deadProbeCache.clear()
        if (size > 0 || deadSize > 0) AppLog.d(TAG, "clearPlayUrlCache: cleared $size url + $deadSize dead entries")
    }
```

`resolvePlayUrlDetailed` 内 `:190-222` 段（过期清理 → 查缓存 → 写缓存）改为：

```kotlin
        // 清理过期缓存条目
        val now = System.currentTimeMillis()
        playUrlCache.entries.removeAll { (_, v) -> now - v.timestamp >= PLAY_URL_CACHE_TTL_MS }
        deadProbeCache.entries.removeAll { (_, ts) -> now - ts >= DEAD_PROBE_TTL_MS }
        // 容量上限：超出后按时间淘汰最旧条目（近似 LRU，避免缓存无界增长）
        if (playUrlCache.size > PLAY_URL_CACHE_MAX) {
            playUrlCache.entries
                .sortedBy { it.value.timestamp }
                .take(playUrlCache.size - PLAY_URL_CACHE_MAX)
                .forEach { playUrlCache.remove(it.key) }
        }
        if (deadProbeCache.size > PLAY_URL_CACHE_MAX) {
            deadProbeCache.entries
                .sortedBy { it.value }
                .take(deadProbeCache.size - PLAY_URL_CACHE_MAX)
                .forEach { deadProbeCache.remove(it.key) }
        }

        // 检查缓存（forceRefresh 时跳过——重试路径绝不命中旧缓存）
        if (!forceRefresh) {
            val cached = playUrlCache[cacheKey]
            if (cached != null) {
                AppLog.d(TAG, "resolvePlayUrl: cache hit for key=$cacheKey")
                return ResolveResult(cached.url, cached.actualQuality)
            }
            // §3.8 第 1 步：已确认的假 302 在 TTL 内不再重复跟链探测（一次点播 = 最多一次探测）
            if (deadProbeCache.containsKey(cacheKey)) {
                AppLog.d(TAG, "resolvePlayUrl: dead-probe cache hit for key=$cacheKey")
                return ResolveResult.failure(quality, probedDead = true)
            }
        } else {
            // 强制刷新：清除旧的直链缓存**与负面判定**，确保下面走完整降级链
            //（重试路径必须能重探——Task 6/7 的候选校验就是靠这条拿到新结论）
            playUrlCache.remove(cacheKey)
            deadProbeCache.remove(cacheKey)
        }

        return try {
            val result = svc.resolvePlayUrlDetailed(song, quality)
            if (result.isSuccess) {
                // 写入缓存（含实际档位，降级结果也缓存，避免重复降级探测）
                playUrlCache[cacheKey] = CachedPlayUrl(result.url!!, now, result.actualQuality)
                AppLog.d(TAG, "resolvePlayUrl: cached new url for key=$cacheKey actual=${result.actualQuality}")
            } else {
                // 解析失败：移除缓存条目，避免下次又命中过期项
                playUrlCache.remove(cacheKey)
                // ⚠️ 只有**探测确认的 DEAD** 才进负面缓存；普通失败（端点没给链接）不钉 5 分钟
                if (result.probedDead) {
                    deadProbeCache[cacheKey] = now
                    AppLog.d(TAG, "resolvePlayUrl: cached DEAD verdict for key=$cacheKey")
                }
            }
            result
        } catch (e: Exception) {
            AppLog.w(TAG, "resolvePlayUrl error: ${e.message}", e)
            playUrlCache.remove(cacheKey)
            ResolveResult.failure(quality)
        }
```

- [ ] **Step 8：写并跑缓存测试 `NetworkDeadProbeCacheTest`**

Fake 服务的写法照 `PlayUrlCacheKeyTest.kt:29-51`（**手写 Fake，不用 Mockito**——该文件 `:21-22` 的注释记录了原因：`NetworkMusicService` 含 suspend fun，Mockito 处理不了 Kotlin 协程参数）。

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 负面探测缓存测试（§6 验收 13）。
 *
 * 三件事必须成立：DEAD 在 TTL 内不重探、**forceRefresh 与切档位必须重探**、
 * 普通失败（probedDead=false）⛔ 不进缓存。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkDeadProbeCacheTest {

    private class FakeService : NetworkMusicService {
        override val sourceId = "meting"
        var verdict: ResolveResult = ResolveResult.failure(320, probedDead = true)
        val calls = mutableListOf<Int>()
        override suspend fun search(keyword: String, limit: Int): List<Song> = emptyList()
        override suspend fun resolvePlayUrl(song: Song): String? = null
        override suspend fun resolveLyrics(song: Song): String? = null
        override suspend fun getPlaylist(playlistId: String): List<Song> = emptyList()
        override suspend fun resolvePlayUrlDetailed(song: Song, quality: Int): ResolveResult {
            calls += quality
            return verdict
        }
    }

    private lateinit var svc: FakeService
    private lateinit var manager: NetworkMusicManager

    private val song = Song(
        id = "ntwk_meting_1001", title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "meting", networkId = "1001"
    )

    @Before
    fun setup() {
        svc = FakeService()
        manager = NetworkMusicManager(
            services = mapOf("meting" to svc),
            defaultSourceProvider = { "meting" },
            qualityTierProvider = { 320 }
        )
    }

    @Test
    fun `DEAD 判定在 TTL 内不再重复探测`() = runBlocking {
        val first = manager.resolvePlayUrlDetailed(song, 320)
        val second = manager.resolvePlayUrlDetailed(song, 320)
        assertEquals("服务只应被打一次", listOf(320), svc.calls)
        assertFalse(second.isSuccess)
        assertTrue("缓存命中也必须带回 DEAD 语义", second.probedDead)
        assertTrue(first.probedDead)
    }

    @Test
    fun `forceRefresh 必须重探（重试与候选校验路径的命门）`() = runBlocking {
        manager.resolvePlayUrlDetailed(song, 320)
        manager.resolvePlayUrlDetailed(song, 320, forceRefresh = true)
        assertEquals(listOf(320, 320), svc.calls)
    }

    @Test
    fun `切档位即换 key，必须重探`() = runBlocking {
        manager.resolvePlayUrlDetailed(song, 320)
        manager.resolvePlayUrlDetailed(song, 128)
        assertEquals(listOf(320, 128), svc.calls)
    }

    @Test
    fun `clearPlayUrlCache 连负面缓存一起清（对齐 NasMusicApp 的切档清缓存）`() = runBlocking {
        manager.resolvePlayUrlDetailed(song, 320)
        manager.clearPlayUrlCache()
        manager.resolvePlayUrlDetailed(song, 320)
        assertEquals(listOf(320, 320), svc.calls)
    }

    @Test
    fun `普通失败不得进负面缓存——偶发故障不能钉 5 分钟`() = runBlocking {
        svc.verdict = ResolveResult.failure(320, probedDead = false)
        manager.resolvePlayUrlDetailed(song, 320)
        val r = manager.resolvePlayUrlDetailed(song, 320)
        assertEquals("没被探测确认就该重试", listOf(320, 320), svc.calls)
        assertFalse(r.probedDead)
    }

    @Test
    fun `成功结果仍走直链缓存（既有语义零回归）`() = runBlocking {
        svc.verdict = ResolveResult("https://cdn/320.mp3", 320)
        manager.resolvePlayUrlDetailed(song, 320)
        val second = manager.resolvePlayUrlDetailed(song, 320)
        assertEquals(listOf(320), svc.calls)
        assertEquals("https://cdn/320.mp3", second.url)
    }
}
```

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  --tests "*NetworkDeadProbeCacheTest" --tests "*PlayUrlCacheKeyTest"
```
预期：新 6 例全绿 + `PlayUrlCacheKeyTest` 原例全绿（同 key 规则没动）。

- [ ] **Step 9：`NasMusicApp` 注入真实探测**（`:303-308`，Task 4 的 `netease` 之后）

```kotlin
                // 艺人全量方案 §3.8 第 1 步（Q4）：判据做实——跟一跳拿最终 Content-Type
                probe = HttpPlayabilityProbe()
```

- [ ] **Step 10：跑全量单测**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```
预期：全绿。重点回看**下载侧**（`QualityProbeTest` / `DowngradePersistTest` / `LocalPlaybackPriorityTest` / `DownloadQualityPathTest`）——它们构造的是 `ResolveResult(...)` 的两参形态，默认值保证零改动；以及 `NetworkMusicManagerTest`（负面缓存不改变它对成功路径的断言）。

- [ ] **Step 11：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/PlayabilityProbe.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/ResolveResult.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt \
        app/src/main/java/com/nasmusic/tv/NasMusicApp.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/PlayabilityProbeTest.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/MetingProbeWiringTest.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/NetworkDeadProbeCacheTest.kt
git commit -m "feat(network): 播放直链增加跟链可播判据与负面探测缓存"
```

---

### Task 6：`CandidatePicker` —— 补上"同源重搜"这一整层（§3.8 第 2 步 / 错位 4）

> **这一步缺的不是触发条件，是整层代码**：`PlayerViewModel.kt:462-467` 的 KDoc 白纸黑字写着"层级1（同源重搜）"，但 `tryReplaceByReSearch`（`:471-498`）的函数体只有 `tryCrossSourceReplace` 一条路径；而 `resolvePlayUrlWithCrossSourceFallback` 又显式 `if (svc.sourceId == src) continue` 跳过原源（`NetworkMusicManager.kt:405`）。所以"用歌名+艺术家在**同一平台**上换一版本"今天完全没做。

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/CandidatePicker.kt`
- Modify: `app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt`（新增 `resolveSameSourceReplacement`，放在 `resolvePlayUrlWithCrossSourceFallback` 之后）
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/CandidatePickerTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `ResolveResult.isSuccess`（探测判 DEAD 的直链在这里已经不会回来了——**这正是 Task 5 是前置的原因**）；`ArtistSplitter.normalizeKey`（`util/ArtistSplitter.kt:47-63`）
- Produces:
  - `data class CandidatePicker.Replacement(val song: Song, val playUrl: String)`
  - `class CandidatePicker(search: suspend (String) -> List<Song>, resolve: suspend (Song) -> ResolveResult, maxCandidates: Int = 3)`
  - `suspend fun pick(title: String, artist: String, excludeId: String): Replacement?`
  - `internal fun titleMatches(candidateTitle: String, originalTitle: String): Boolean`（companion 上的纯函数）
  - `suspend fun NetworkMusicManager.resolveSameSourceReplacement(song: Song): CandidatePicker.Replacement?`

- [ ] **Step 1：写失败测试**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同源重搜候选测试（§6 验收 14）。
 *
 * fake 只暴露 search / resolve 两个注入点，**断言的正是"除了这两个什么都没碰"**——
 * 替换产物不落库（收藏/下载）这件事在结构上由"没有第三个口"保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CandidatePickerTest {

    private fun song(id: String, title: String, artist: String = "周杰伦") = Song(
        id = "ntwk_meting_$id", title = title, artist = artist,
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    /** 记录调用轨迹的假搜索/假解析 */
    private class Harness(
        val pool: List<Song>,
        val playable: Set<String>            // 这些 id 判 PLAYABLE，其余判 DEAD
    ) {
        val queries = mutableListOf<String>()
        val resolved = mutableListOf<String>()
        fun picker() = CandidatePicker(
            search = { q -> queries += q; pool },
            resolve = { s ->
                resolved += s.id
                if (s.id in playable) ResolveResult("https://cdn/${s.networkId}.mp3", QualityTiers.AUTO)
                else ResolveResult.failure(QualityTiers.AUTO, probedDead = true)
            }
        )
    }

    @Test
    fun `一次搜索一个关键词——title 与 artist 都带上`() = runBlocking {
        val origin = song("1", "晴天")
        val h = Harness(listOf(song("2", "晴天 (Live)")), playable = setOf("ntwk_meting_2"))
        val r = h.picker().pick(origin.title, origin.artist, excludeId = origin.id)
        assertNotNull(r)
        assertEquals(listOf("晴天 周杰伦"), h.queries)     // 只 1 次搜索（§6 验收 16 ③）
        assertEquals("ntwk_meting_2", r?.song?.id)
    }

    @Test
    fun `排除原曲 id`() = runBlocking {
        val origin = song("1", "晴天")
        val h = Harness(listOf(song("1", "晴天"), song("2", "晴天")), playable = setOf("ntwk_meting_2"))
        val r = h.picker().pick(origin.title, origin.artist, excludeId = origin.id)
        assertEquals(listOf("ntwk_meting_2"), h.resolved)   // 原曲一次都不该被解析
        assertEquals("ntwk_meting_2", r?.song?.id)
    }

    @Test
    fun `候选数上限 3 生效——不许演变成全池扫描`() = runBlocking {
        val pool = (1..10).map { song("${it + 1}", "晴天") }
        val h = Harness(pool, playable = emptySet())
        assertNull(h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1"))
        assertEquals("最多 3 次解析", listOf("ntwk_meting_2", "ntwk_meting_3", "ntwk_meting_4"), h.resolved)
    }

    @Test
    fun `只探到第一个可播即停，不再验证后面的候选`() = runBlocking {
        val pool = (1..6).map { song("${it + 1}", "晴天") }
        val h = Harness(pool, playable = setOf("ntwk_meting_4"))
        val r = h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1")
        assertEquals("ntwk_meting_4", r?.song?.id)
        assertEquals(listOf("ntwk_meting_2", "ntwk_meting_3", "ntwk_meting_4"), h.resolved)
    }

    @Test
    fun `替换产物自带已解析直链，⛔ 不写回收藏与下载`() = runBlocking {
        val h = Harness(listOf(song("2", "晴天")), playable = setOf("ntwk_meting_2"))
        val r = h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1")!!
        // ① 替换即可播：直链已经解析好并挂在 song.streamUrl 上
        assertEquals("https://cdn/2.mp3", r.song.streamUrl)
        assertEquals("https://cdn/2.mp3", r.playUrl)
        // ② 副作用为零：全程只有 1 次搜索 + 1 次解析，没有第三个调用口
        assertEquals(listOf("晴天 周杰伦"), h.queries)
        assertEquals(listOf("ntwk_meting_2"), h.resolved)
        // ③ 红线不撕裂：来源与 id 命名空间仍是 meting（Task 1 的构造点负责）
        assertEquals("meting", r.song.networkSource)
        assertEquals("ntwk_meting_2", r.song.id)
    }

    @Test
    fun `同名不同歌必须挡住——重搜不能把用户导到别的曲子`() = runBlocking {
        val h = Harness(listOf(song("2", "安静")), playable = setOf("ntwk_meting_2"))
        assertNull(h.picker().pick("晴天", "周杰伦", excludeId = "ntwk_meting_1"))
        assertTrue("标题不符的候选一次都不该被解析", h.resolved.isEmpty())
    }

    @Test
    fun `artist 为空时退化为纯 title，且不残留多余空格`() = runBlocking {
        val h = Harness(listOf(song("2", "晴天")), playable = setOf("ntwk_meting_2"))
        h.picker().pick("晴天", "", excludeId = "ntwk_meting_1")
        assertEquals(listOf("晴天"), h.queries)
    }

    @Test
    fun `搜索抛异常等于没有替代，⛔ 绝不上抛`() = runBlocking {
        val picker = CandidatePicker(
            search = { throw java.io.IOException("endpoint down") },
            resolve = { ResolveResult.failure(QualityTiers.AUTO) }
        )
        assertNull(picker.pick("晴天", "周杰伦", excludeId = "ntwk_meting_1"))
    }

    // ── titleMatches 的边界（纯函数，无 fixture） ─────────────
    @Test
    fun `titleMatches 只认全等与前缀，不做模糊`() {
        fun m(c: String, o: String) = CandidatePicker.titleMatches(c, o)
        assertTrue(m("晴天", "晴天"))
        assertTrue(m("晴天 (Live)", "晴天"))          // 版本差异正是同源重搜要找的东西
        assertTrue(m("晴天MV", "晴天"))
        assertFalse(m("安静", "晴天"))
        assertFalse(m("晴天", "晴天雨"))               // 反方向不算
        assertFalse(m("晴", "晴天"))                   // 短标题不当前缀用
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process --tests "*CandidatePickerTest"
```
预期：编译失败（`CandidatePicker` 未定义）。

- [ ] **Step 3：新增 `CandidatePicker.kt`**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.ArtistSplitter

/**
 * 同源重搜的候选挑选（艺人全量方案 §3.8 第 2 步，补错位 4）。
 *
 * 纯逻辑件：搜索与解析都在构造期注入，单测用 lambda 打假（§3.8 的"单测不打网络"要求）。
 * 与 `resolvePlayUrlWithCrossSourceFallback` 的分工：那一个是**跨注册源**（找同名另一演唱者），
 * 本件是**同源内**换版本（找同一首歌的另一个可播版本）——Q8 撤出跨 `server` 后，
 * 这一层是本期唯一新增的重搜路径。
 */
class CandidatePicker(
    private val search: suspend (String) -> List<Song>,
    private val resolve: suspend (Song) -> ResolveResult,
    private val maxCandidates: Int = MAX_CANDIDATES
) {

    /** 替代曲（已带直链，替换即可播） */
    data class Replacement(val song: Song, val playUrl: String)

    /**
     * @return 第一个可播的替代曲；无可播替代 / 搜索失败返回 null（由调用方决定跳下一首）
     */
    suspend fun pick(title: String, artist: String, excludeId: String): Replacement? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.isBlank()) {
            AppLog.w(DIAG, "CandidatePicker: title 与 artist 均为空，跳过同源重搜")
            return null
        }
        // 一次搜索、一个关键词（§6 验收 16 ③）。⚠️ 这里**故意不做变体后缀**：
        // §3.8 设想的"复用 SearchViewModel 那张 24 后缀表"会把 1 次搜索放大成 N 次，
        // 在公共端点上直接撞 R3 限流，而本期收益未实测（§9 T1）。
        val pool = try {
            search(query)
        } catch (e: Exception) {
            AppLog.w(DIAG, "CandidatePicker: search '$query' threw ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        val candidates = pool
            .filter { it.id != excludeId && titleMatches(it.title, title) }
            .distinctBy { it.id }
            .take(maxCandidates)
        for (c in candidates) {
            val r = try {
                resolve(c)
            } catch (e: Exception) {
                AppLog.w(DIAG, "CandidatePicker: resolve ${c.title} threw ${e.message}")
                null
            }
            val url = r?.url
            if (r != null && r.isSuccess && !url.isNullOrBlank()) {
                // 替换即可播：直链挂在副本上，⛔ 不写回收藏/下载（R8）
                return Replacement(c.copy(streamUrl = url), url)
            }
        }
        return null
    }

    companion object {
        private const val DIAG = "MetingDiag"

        /** 候选上限（§3.8 边界：⛔ 不做端点 × 平台 × 候选的笛卡尔积） */
        const val MAX_CANDIDATES = 3

        /**
         * 标题判定：**归一化全等** 或 **候选以原标题为前缀**（版本后缀是同源重搜的主要目标形态）。
         *
         * ⛔ 不做包含/编辑距离之类的模糊——"晴天"重搜回"安静"并自动替用户播放，
         * 在 Q7③（自动处理、无需用户关注）下是不可接受的事故。
         * 归一化复用 `ArtistSplitter.normalizeKey`（它只是 NFKC + 空白折叠 + lowercase，与"艺人"无关）。
         */
        internal fun titleMatches(candidateTitle: String, originalTitle: String): Boolean {
            val c = ArtistSplitter.normalizeKey(candidateTitle)
            val o = ArtistSplitter.normalizeKey(originalTitle)
            if (c.isEmpty() || o.isEmpty()) return false
            return c == o || (o.length >= 2 && c.startsWith(o))
        }
    }
}
```

> ⚠️ **`titleMatches` 的方向性**：`m("晴天", "晴天雨")` 必须为 false——前缀关系只在"候选是原曲的某个版本"时成立，反过来意味着原曲只是候选的一部分。测试里那条反例就是这个边界的存在证明。

- [ ] **Step 4：`NetworkMusicManager` 新增同源入口**（放在 `resolvePlayUrlWithCrossSourceFallback` 的结尾之后）

```kotlin
    /**
     * 同源重搜替换（艺人全量方案 §3.8 第 2 步）。
     *
     * 与 [resolvePlayUrlWithCrossSourceFallback] 的区别是方向：这里**只在原注册源内**取候选
     * （`services[networkSource]`，不走 [orderedServices] 的 fallback），语义是
     * "同一首歌的另一个版本"，而不是"另一位演唱者的同名歌"。
     *
     * 请求量：1 次搜索 + ≤3 次解析（§6 验收 16 ③），解析全部 `forceRefresh=true`
     * ——Task 5 的负面缓存会把原曲钉成 DEAD，候选若不强制刷新就可能整批复用旧判定。
     *
     * @return 替代曲与已解析直链；无可播替代 / 未注册源返回 null
     */
    suspend fun resolveSameSourceReplacement(song: Song): CandidatePicker.Replacement? {
        if (!song.isNetworkSong) return null
        val src = song.networkSource ?: return null
        val svc = services[src] ?: return null
        val quality = effectiveQuality(song)
        return CandidatePicker(
            search = { query -> svc.search(query) },
            resolve = { candidate -> resolvePlayUrlDetailed(candidate, quality, forceRefresh = true) }
        ).pick(title = song.title, artist = song.artist, excludeId = song.id)
    }
```

- [ ] **Step 5：跑测试 + 全量单测**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process --tests "*CandidatePickerTest"
```
预期：9 例全绿。再跑整轮 `testDebugUnitTest`——`resolvePlayUrlWithCrossSourceFallback` 一行未改，跨源路径必须零回归。

- [ ] **Step 6：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/CandidatePicker.kt \
        app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt \
        app/src/test/java/com/nasmusic/tv/backend/network/CandidatePickerTest.kt
git commit -m "feat(network): 新增同源重搜候选挑选与播放替换入口"
```

---

### Task 7：`ReplaceChain` + `PlayerViewModel` 接线 —— Q7③ 的自动替换

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/backend/network/ReplaceChain.kt`
- Modify: `app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlayerViewModel.kt:99`（字段改名）、`:461-498`（KDoc 与 `tryReplaceByReSearch` 全替换）、`:500-512` 之后新增 `trySameSourceReplace`
- Modify: `app/src/main/res/values/strings.xml:881` 之后 +1 条、`app/src/main/res/values-en/strings.xml:1034` 之后 +1 条
- Test: `app/src/test/java/com/nasmusic/tv/backend/network/ReplaceChainTest.kt`

**Interfaces:**
- Consumes: Task 6 的 `NetworkMusicManager.resolveSameSourceReplacement(song)`、既有 `resolvePlayUrlWithCrossSourceFallback(song, forceRefresh)`
- Produces:
  - `enum class ReplaceLayer { SAME_SOURCE, CROSS_SOURCE }`
  - `ReplaceChain.plan(songId: String, lastReplacedId: String?): List<ReplaceLayer>`
  - `ReplaceChain.run(layers: List<ReplaceLayer>, attempt: suspend (ReplaceLayer) -> Song?): Pair<ReplaceLayer, Song>?`（`suspend`）
  - `PlayerViewModel` 内部字段改名：`lastCrossSourceReplacedId` → `lastReplacedSongId`（语义从"跨源产物"扩成"任一层替换产物"；全仓库只有 4 处引用：`:99/:466/:473/:487`）

- [ ] **Step 1：写失败测试**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 替换链决策测试（§6 验收 15 + §3.8 第 2/3 步的顺序约束）。
 *
 * 纯 JVM 测试，不需要 Robolectric（无 Android 依赖）。
 */
class ReplaceChainTest {

    private fun song(id: String) = Song(
        id = "ntwk_meting_$id", title = "晴天", artist = "周杰伦",
        isNetworkSong = true, networkSource = "meting", networkId = id
    )

    @Test
    fun `普通失败按序给出两层——同源在前`() {
        assertEquals(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE),
            ReplaceChain.plan("ntwk_meting_1", lastReplacedId = null)
        )
        assertEquals(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE),
            ReplaceChain.plan("ntwk_meting_1", lastReplacedId = "ntwk_meting_9")
        )
    }

    /** §6 验收 15：替换产物仍失败 ⇒ 不再二次替换（`PlayerViewModel.kt:473-479` 的既有语义） */
    @Test
    fun `已是替换产物 ⇒ 层序为空，一层都不跑`() {
        assertEquals(emptyList<ReplaceLayer>(), ReplaceChain.plan("ntwk_meting_2", lastReplacedId = "ntwk_meting_2"))
    }

    @Test
    fun `第一个成功的层即停——后面的层一次都不请求`() = runBlocking {
        val tried = mutableListOf<ReplaceLayer>()
        val hit = ReplaceChain.run(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)
        ) { layer ->
            tried += layer
            if (layer == ReplaceLayer.SAME_SOURCE) song("2") else null
        }
        assertEquals(ReplaceLayer.SAME_SOURCE, hit?.first)
        assertEquals("跨源一层根本不该被跑", listOf(ReplaceLayer.SAME_SOURCE), tried)
    }

    @Test
    fun `同源落空才轮到跨源`() = runBlocking {
        val tried = mutableListOf<ReplaceLayer>()
        val hit = ReplaceChain.run(
            listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)
        ) { layer ->
            tried += layer
            if (layer == ReplaceLayer.CROSS_SOURCE) song("3") else null
        }
        assertEquals(ReplaceLayer.CROSS_SOURCE, hit?.first)
        assertEquals(listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE), tried)
    }

    @Test
    fun `两层都落空返回 null（调用方据此跳下一首）`() = runBlocking {
        assertNull(ReplaceChain.run(ReplaceChain.plan("1", null)) { null })
        assertNull(ReplaceChain.run(ReplaceChain.plan("1", "1")) { error("不该被调用") })
    }
}
```

- [ ] **Step 2：跑测试确认失败**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process --tests "*ReplaceChainTest"
```
预期：编译失败（`ReplaceChain` / `ReplaceLayer` 未定义）。

- [ ] **Step 3：新增 `ReplaceChain.kt`**

```kotlin
package com.nasmusic.tv.backend.network

import com.nasmusic.tv.data.model.Song

/** 解析失败后可用的替换层（§3.8 的层级 1 与层级 2）。 */
enum class ReplaceLayer { SAME_SOURCE, CROSS_SOURCE }

/**
 * 替换链的**纯决策件**（艺人全量方案 §3.8 / Q7③）。
 *
 * `PlayerViewModel` 是不可单测的 AndroidViewModel，所以"该跑哪几层、什么时候停"
 * 抽到这里用纯 JVM 测试钉住（存在理由同 `NetworkPlaybackResolver`）。
 * ⛔ 这里不做任何 IO，也不碰 generation。
 */
object ReplaceChain {

    /**
     * 本次失败该按序尝试哪几层。
     *
     * ⛔ 同源必须在跨源**之前**：同源换的是同一首歌的另一版本，跨源换的可能是**另一位演唱者的同名歌**
     * （`resolvePlayUrlWithCrossSourceFallback` 只按 `title + artist` 搜，`jamendo` 上的同名异人不是原曲）。
     *
     * 防死循环闸门在这里：`songId == lastReplacedId` 表示本首已是某次替换的产物且仍然失败，
     * 返回空列表 ⇒ 一层都不跑，调用方直接跳下一首（§6 验收 15）。
     * ⚠️ 该闸门现在覆盖**两层**（既有代码只管跨源），这是有意的收紧：
     * 替换产物再触发替换，最坏情况是 A→B→A 的回环，而每一环都要付"1 次搜索 + ≤3 次解析"。
     */
    fun plan(songId: String, lastReplacedId: String?): List<ReplaceLayer> =
        if (lastReplacedId != null && songId == lastReplacedId) emptyList()
        else listOf(ReplaceLayer.SAME_SOURCE, ReplaceLayer.CROSS_SOURCE)

    /**
     * 按序尝试，**第一个成功的层即停**（⛔ 不许把后面的层也跑一遍浪费请求，R3）。
     *
     * @return 命中的层与替代曲；全失败返回 null
     */
    suspend fun run(
        layers: List<ReplaceLayer>,
        attempt: suspend (ReplaceLayer) -> Song?
    ): Pair<ReplaceLayer, Song>? {
        for (layer in layers) {
            val replacement = attempt(layer)
            if (replacement != null) return layer to replacement
        }
        return null
    }
}
```

- [ ] **Step 4：加一条同源替换文案**（**两个文件都要加**，缺 `values-en` 会在英文环境崩溃）

`values/strings.xml`（紧跟 `:881` 的 `cross_source_replace_playing`）：

```xml
    <string name="same_source_replace_playing">《%1$s》链接失效，已改播另一版本《%2$s》</string>
```

`values-en/strings.xml`（紧跟 `:1034`）：

```xml
    <string name="same_source_replace_playing">"%1$s" link expired, playing another version "%2$s"</string>
```

> ⚠️ 为什么不复用 `cross_source_replace_playing`：同源替换时 `networkSource` 仍是 `"meting"`（红线 2），拿它填 `%2$s` 会显示"已用 meting 源版本替换播放"——原曲也是 meting 源，等于告诉用户一件没发生的事。文案必须与链的真实层级一致。

- [ ] **Step 5：`PlayerViewModel` 接线**（`:461-498` 整段替换；`tryCrossSourceReplace` `:500-512` 原样保留）

字段（`:99`）：

```kotlin
    /** 最近一次替换产物的 id（任一层）。命中即不再二次替换，防替换回环（§6 验收 15） */
    private var lastReplacedSongId: String? = null
```

替换链：

```kotlin
    /**
     * 解析失败后的降级替换：先同源重搜取另一版本，再跨源重搜替换（§3.8 第 2/3 步）。
     *
     * 层级1（同源重搜）：`NetworkMusicManager.resolveSameSourceReplacement()`，
     *  在原注册源内用 title+artist 重搜，候选逐个走**已做实的判据**（Task 5），≤3 个，取第一个可播的。
     * 层级2（跨源替换）：层级1 落空才跑 `resolvePlayUrlWithCrossSourceFallback`。
     * 防死循环：本首已是替换产物（`lastReplacedSongId` 命中）⇒ 两层都不跑，直接返回 false 由调用方跳曲。
     *
     * ⛔ Q7③ 的自动替换只作用于**当前播放队列**，不写回收藏/下载（R8）。
     *
     * @return true 已替换并播放；false 无可播替代（调用方应跳下一首）
     */
    private suspend fun tryReplaceByReSearch(song: Song, targetIndex: Int, generation: Int): Boolean {
        if (!song.isNetworkSong) return false
        val layers = ReplaceChain.plan(song.id, lastReplacedSongId)
        if (layers.isEmpty()) {
            AppLog.w("PlayerViewModel", "tryReplaceByReSearch: ${song.title} 已是替换产物且仍失败，放弃替换")
            return false
        }
        val hit = ReplaceChain.run(layers) { layer ->
            // 代数校验留在调用侧：层内任何一次挂起返回后都可能已被用户切歌
            if (generation != resolveGeneration) null
            else when (layer) {
                ReplaceLayer.SAME_SOURCE -> trySameSourceReplace(song, generation)
                ReplaceLayer.CROSS_SOURCE -> tryCrossSourceReplace(song, targetIndex, generation)
            }
        }
        if (hit == null) {
            AppLog.w("PlayerViewModel", "tryReplaceByReSearch: ${song.title} 两层替换均无可用替代")
            return false
        }
        val (usedLayer, replacement) = hit
        if (generation != resolveGeneration) return false
        val latestQueue = playerState.value.queue
        val updatedQueue = latestQueue.mapIndexed { index, s ->
            if (index == targetIndex) replacement else s
        }
        // 记录本次替换产物 id，防后续对同一首歌无限替换
        lastReplacedSongId = replacement.id
        val message = if (usedLayer == ReplaceLayer.SAME_SOURCE) {
            getApplication<Application>().getString(
                R.string.same_source_replace_playing, song.title, replacement.title
            )
        } else {
            getApplication<Application>().getString(
                R.string.cross_source_replace_playing, song.title, replacement.networkSource ?: ""
            )
        }
        AppLog.w("PlayerViewModel", "tryReplaceByReSearch: $usedLayer 替换为 '${replacement.title}' (${replacement.networkSource})")
        showMessage?.invoke(message)
        playerManager.playQueue(updatedQueue, targetIndex)
        return true
    }

    /**
     * 层级1：同源重搜（§3.8 错位 4 的修复——KDoc 早就写了这一层，代码里一直没有）。
     * 返回带已解析 streamUrl 的替代曲；无可播替代返回 null。
     */
    private suspend fun trySameSourceReplace(song: Song, generation: Int): Song? {
        if (generation != resolveGeneration) return null
        val result = nasMusicApp.networkMusicManager.resolveSameSourceReplacement(song) ?: return null
        if (generation != resolveGeneration) return null
        return result.song
    }
```

> ⚠️ **原 KDoc 与代码不一致是既有的**（`:462-467` 承诺了不存在的层级1）。本次改动同时把注释拉回真实行为——`§3.8 表格里"唯一调用点"` 那段（`:424-437`：两次 `forceRefresh` 解析都空 → `:430` 才调本函数）**没有变**，所以"⛔ 不接入播放全部/下载/后台批量"的边界自动保持成立。
>
> ⚠️ `ReplaceChain.run` 的 lambda 里那次 `generation` 校验**不能省**：Task 6 的同源重搜是"1 次搜索 + ≤3 次解析"，最坏情况要几秒，期间用户完全可能已经切歌。

- [ ] **Step 6：跑测试 + 全量单测 + lint**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process --tests "*ReplaceChainTest"
```
预期：5 例全绿。再跑整轮 `testDebugUnitTest` 与 `lintDebug`（`--tests "*ReplaceChainTest"` 不带 Robolectric，普通 JVM 跑，符合"纯逻辑件"的定位）。

- [ ] **Step 7：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/backend/network/ReplaceChain.kt \
        app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlayerViewModel.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml \
        app/src/test/java/com/nasmusic/tv/backend/network/ReplaceChainTest.kt
git commit -m "feat(player): 播放替换接入同源重搜优先链与替换文案"
```

---

### Task 8：UI —— 打标（Q1 b）、置顶已在 Task 4、续页计数（§3.6）

> Q1 的 (c)「可播置顶」已经在 `NetworkArtistExpansion.playableFirst` 里做完（Task 4），本 Task 只做**显示侧**：角标 + 计数 + 触底续页 + 参数透传。
> ⛔ 分组（Q6）与「换一批」（Q5）在本 Task **什么都不做**——前者平铺、后者既有链路不许碰（§3.6）。

**Files:**
- Modify: `app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongRow.kt`（`:118` KDoc、`:138` 入参、`:157` 透传、`:196` 私有行组件入参、`:414-426` 角标 `when`）
- Modify: `app/src/main/java/com/nasmusic/tv/ui/screens/library/SearchTab.kt`（`:55-74` 入参、`:80-97` 之后加续页钩子、`:129-163` 计数行与 footer）
- Modify: `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`（`:179-180` 入参、`:642-659` 透传）
- Modify: `app/src/main/java/com/nasmusic/tv/ui/components/branches/LibraryBranch.kt`（`:51/:83` 之后收集状态、`:134-135` 之后透传）
- Modify: `app/src/main/res/values/strings.xml` +1、`values-en/strings.xml` +1

**Interfaces:**
- Consumes: Task 4 的 `SearchViewModel.searchUnplayableIds` / `artistPaging` / `loadMoreArtistSongs()`；`data/model/SongsPagingState`
- Produces: `UnifiedSongRow(possiblyUnplayable: Boolean = false)`、`SearchTab(unplayableIds, artistPaging, onLoadMoreArtistSongs)`

- [ ] **Step 1：加角标文案**（两个文件都要，缺 `values-en` 会在英文环境崩）

`values/strings.xml`（`:881` 附近，与两条替换文案同区）：

```xml
    <string name="song_row_unplayable_badge">可能无版权</string>
```

`values-en/strings.xml`（`:1034` 附近）：

```xml
    <string name="song_row_unplayable_badge">Maybe unavailable</string>
```

> ⚠️ 文案**必须带"可能"**。`privilege.st` 只是未登录态的分布参考（§2 E / T2），写成"无版权"是把一个未证实的标志当结论。

- [ ] **Step 2：`UnifiedSongRow` 加一个入参**

KDoc（`:118` 的 `@param urlStatus` 之后）：

```kotlin
 * @param possiblyUnplayable 该曲在艺人全量列表里带 `privilege.st != 0`（**仅打标、不隐藏**，Q1 的 b）。
 *                   默认 false；普通搜索分支与端点兜底分支**没有这个字段**，必须保持 false（§2 C.1）——
 *                   "没角标"不等于"能播"，UI 侧统一以缺省即不显示处理。
```

公开签名（`:138` 的 `urlStatus` 之后、`modifier` 之前）与私有行组件（`:196` 同款位置）各加一行：

```kotlin
    possiblyUnplayable: Boolean = false,
```

透传（`:157` 的 `urlStatus = urlStatus,` 之后）：

```kotlin
            possiblyUnplayable = possiblyUnplayable,
```

角标 `when`（`:414-426`）——**插在 `isStub` 之后、`else -> SourceBadge` 之前**，并在注释里写清优先级：

```kotlin
                        // 来源标签：URL 失效（红）> 待补全（橙）> 可能无版权（灰）> 正常来源。
                        // ⚠️ 角标只打标不过滤（Q1 b）；行仍可点播，失败走 Task 5/6/7 的替换链。
                        when {
                            urlStatus == UrlStatus.UNREACHABLE -> Text(
                                text = stringResource(com.nasmusic.tv.R.string.playlist_url_invalid),
                                color = NasMusicColors.Warning,
                                fontSize = FontSize.small()
                            )
                            isStub -> Text(
                                text = stringResource(com.nasmusic.tv.R.string.playlist_stub_badge),
                                color = NasMusicColors.Primary,
                                fontSize = FontSize.small()
                            )
                            possiblyUnplayable -> Text(
                                text = stringResource(com.nasmusic.tv.R.string.song_row_unplayable_badge),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small()
                            )
                            else -> SourceBadge(song = song)
                        }
```

> ⚠️ `MODE_CARD` / `MODE_COMPACT` **不加**这个入参（它们没有来源标签位）；`SearchTab` 用的是 `MODE_ROW`（`SearchTab.kt:150`），所以打标只可能出现在行模式——不要为了"看着统一"去改卡片模式。

- [ ] **Step 3：`SearchTab` 加入参与续页钩子**

入参（`:60` 的 `queueSongIds` 之后，全部带默认值 → 其它调用点零改动）：

```kotlin
    // 艺人全量方案 Q1(b)：仅艺人分支填充的"可能无版权"旁路集合（⛔ 不塞进 Song）
    unplayableIds: Set<String> = emptySet(),
    // §3.6 触底续页：hasMore 只来自接口的 more 字段，"少则不分页"天然成立
    artistPaging: com.nasmusic.tv.data.model.SongsPagingState =
        com.nasmusic.tv.data.model.SongsPagingState(),
    onLoadMoreArtistSongs: () -> Unit = {},
```

新增 import（文件头 `:15-19` 的 runtime 组里）：

```kotlin
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
```

续页钩子（紧跟 `:97` 的 `DisposableEffect` 回顶块之后、`Column(` 之前）：

```kotlin
    // 检测是否滚动接近底部，触发加载更多
    // ⚠️ remember 必须带 key：照抄 ui/screens/library/browse/SongList.kt:81-94 的教训
    //   （2026-09-25 审查修复 #16——无 key 时 derivedStateOf 捕获首次组合的参数快照，
    //    分页条件永远用冻结的旧列表长度/旧分页标志，续页**一次都不会触发**）。
    val shouldLoadMore by remember(searchResults, artistPaging, isSearching) {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = searchResults.size
            // 提前 20 项加载下一页
            totalItems > 0 && lastVisibleIndex >= totalItems - 20 &&
                    artistPaging.hasMore && !artistPaging.isLoading && !isSearching
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMoreArtistSongs()
    }
```

`UnifiedSongRow(` 调用处（`:153` 的 `mode = SongRowMode.MODE_ROW,` 之后）加一行：

```kotlin
                            possiblyUnplayable = song.id in unplayableIds,
```

- [ ] **Step 4：`SearchTab` 计数行 + 续页 footer**

计数行插在 `ActionBar(...)` 与列表之间（`:135-137` 之间，复用既有文案 `library_songs_count_with_total`，⛔ 不新增）：

```kotlin
                // 命中艺人时显示"已加载/总数"，让用户知道这不是搜索前 30 条而是全量目录
                if (artistPaging.totalCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.library_songs_count_with_total,
                            searchResults.size, artistPaging.totalCount
                        ),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small(),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }
```

footer 在 `items(searchResults.size, key = { searchResults[it].id }) { … }` **之后**（同一 `LazyVerticalGrid` 作用域内）：

```kotlin
                    // 续页加载态：只在"还有下一页且正在取"时占一整行
                    // （⛔ 不加 items 总数以外的 key，footer 与数据行同 id 会撞 `key` 重复）
                    if (artistPaging.hasMore && artistPaging.isLoading) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.common_loading),
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small(),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            )
                        }
                    }
```

- [ ] **Step 5：`LibraryScreen` 透传**

入参（`:180` 的 `isSearching` 之后）：

```kotlin
    // 艺人全量方案：搜索页的打标集合与艺人续页状态（全部带默认值）
    searchUnplayableIds: Set<String> = emptySet(),
    artistPaging: SongsPagingState = SongsPagingState(),
    onLoadMoreArtistSongs: () -> Unit = {},
```

`SearchTab(` 调用处（`:645` 的 `isSearching = isSearching,` 之后）：

```kotlin
                        unplayableIds = searchUnplayableIds,
                        artistPaging = artistPaging,
                        onLoadMoreArtistSongs = onLoadMoreArtistSongs,
```

- [ ] **Step 6：`LibraryBranch` 收集状态**

状态收集（`:83` 的 `enabledSearchSources` 之后）：

```kotlin
                    val searchUnplayableIds by viewModel.searchVM.searchUnplayableIds.collectAsState()
                    val artistPaging by viewModel.searchVM.artistPaging.collectAsState()
```

`LibraryScreen(` 调用处（`:135` 的 `isSearching = isSearching,` 之后）：

```kotlin
                        searchUnplayableIds = searchUnplayableIds,
                        artistPaging = artistPaging,
                        onLoadMoreArtistSongs = { viewModel.searchVM.loadMoreArtistSongs() },
```

- [ ] **Step 7：编译 + lint（UI 没有可单测的纯逻辑，判据是这三条）**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug lintDebug \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  *> logs_temp/task8-build.log
```
预期：`BUILD SUCCESSFUL`、lint 无新增 error（既有 256 warning 口径见 AGENTS.md）。
再确认两件事：① `grep -c 'song_row_unplayable_badge' app/src/main/res/values/strings.xml app/src/main/res/values-en/strings.xml` 都是 `1`；② `searchResults` 在 `SearchTab` 里仍只有一个消费点（打标 + 网格），没有新增去重逻辑——**去重属于 Task 4 的 `mergeWithExpansion`，UI 不做二次防御**。

- [ ] **Step 8：提交**

```bash
git add app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongRow.kt \
        app/src/main/java/com/nasmusic/tv/ui/screens/library/SearchTab.kt \
        app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt \
        app/src/main/java/com/nasmusic/tv/ui/components/branches/LibraryBranch.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-en/strings.xml
git commit -m "feat(ui): 搜索页艺人全量结果支持续页计数与可能无版权打标"
```

---

### Task 9：全量回归 + 红线自检 + 文档同步（Q9：**不独立发版**，随下一版）

> ⛔ 只有 Task 1–8 都已提交才执行本 Task。若 Task 4 因 Q3 复确认未过而搁置，Task 1–3、5–8 仍可独立交付（`music.163.com` 不进线上路径），本 Task 的回归口径完全不变，只是 §10.225 末尾必须写明「Task 4 未接入，`NeteaseOpenApi` 只被单测引用」——**别让一个没人调用的类静静躺在 main 源集里而不留痕**。

**Files:**
- Modify: `docs/technical-overview.md`（追加 `### 10.225`）
- Modify: `CHANGELOG.md`（新增 `## [Unreleased]` 段 + 2 行；`v2.38.6` 已打 tag ⇒ 该段冻结，只在它**上方**加）
- Create: `docs/archive/verification/netease_artist_probe/probe_v5_regression.log`（脚本复跑存档，随本批提交）
- Scratch（⛔ 不提交）: `logs_temp/task9-*.log`、`logs_temp/probe_v5_regression.log`

**Interfaces:**
- Consumes: Task 1–8 的全部产出
- Produces: ——（本计划终点，无下游）

- [ ] **Step 1：先释放卡死的守护进程，再跑全量单测**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat --stop
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  *> logs_temp/task9-test.log
```

（`--stop` 是**必须的第一步**而不是报错后的补救：AGENTS.md 记录过卡死守护进程持 `fileHashes.lock` 会让后续构建秒失败；若仍然秒失败，再 `--stop` 一次重跑。**⛔ 绝不 `rm` 锁文件**——`rm` 失败会把文件留在 Windows「删除挂起」态，之后所有构建都起不来。）

用日志汇总实际用例数（不要读控制台滚动输出估数）：

```bash
python -c "import glob,xml.etree.ElementTree as ET;\
t=f=e=s=0;\
[globals().update(t=t+int((r.get('tests') or 0)),f=f+int((r.get('failures') or 0)),\
e=e+int((r.get('errors') or 0)),s=s+int((r.get('skipped') or 0)))\
 for r in (ET.parse(p).getroot() for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'))];\
print(f'tests={t} failures={f} errors={e} skipped={s}')"
```

期望 **`failures=0 errors=0`**，且 `tests=` 落在下表口径上：

| 来源 | 用例数 |
|---|---|
| 改造前基线（AGENTS.md 2026-09-16 实测，`testDebugUnitTest` 全绿约 50s） | 518 |
| Task 1 `NetworkArtistSongIdContractTest` | 2 |
| Task 2 `NeteaseArtistSongsParseTest` 2 + `NeteaseType1FieldTest` 1 + 契约补 1 | 4 |
| Task 3 `NetworkArtistRoutingTest` | 3 |
| Task 4 `NeteasePrioritySearchTest` 3 + `ArtistSongsPagingTest` 8 | 11 |
| Task 5 `PlayabilityProbeTest` 5 + `MetingProbeWiringTest` 5 + `NetworkDeadProbeCacheTest` 6 | 16 |
| Task 6 `CandidatePickerTest` | 9 |
| Task 7 `ReplaceChainTest` | 5 |
| **合计** | **568** |

> ⚠️ 若实际数 < 568 且 `failures=0`，**八成是某个 `@Test` 方法名与既有用例重名被覆盖**，或直接漏写了一例——逐类跑 `--tests "*XxxTest"` 对齐数字，别把差额当舍入放过。
> ⚠️ 若某条用例只在 Task 内单跑绿、全量跑红：先怀疑 Task 5 的共享 OkHttp 连接池与 Robolectric 并发（既有 `MetingResolveTest` 就是靠「只断言可确定性质」躲开这个的，见本文 §11 Task 5 Step 6 的同源纪律），别加 `synchronized` 或降并发。

- [ ] **Step 2：编译 + lint**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug lintDebug \
  --no-daemon -Pkotlin.compiler.execution.strategy=in-process \
  *> logs_temp/task9-build.log
```

期望：`BUILD SUCCESSFUL`；lint **无新增 error**（既有 256 warning 口径见 AGENTS.md，别去清）。输出里若出现 `LintCliClient.analyzeOnly → UAST visitor` 的异常栈，那是既有现象、不影响构建结果，不要当新问题排查。

- [ ] **Step 3：四条红线自检（编译与 lint 都抓不到的那四类）**

```bash
# ① 红线 1：网易侧产物 id 只能出自 NetworkSongId
grep -rn '"ntwk' app/src/main/java/com/nasmusic/tv/backend/network/netease/
# ② 红线 2：网易侧 networkSource 恒为 "meting"
grep -rn 'networkSource' app/src/main/java/com/nasmusic/tv/backend/network/netease/
# ③ 探测只发生在「跟完 302」那一侧
grep -n 'fromRedirect' app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt
# ④ 超时预算落在 8s（Task 4 Step 里改的那一行）
grep -n 'NETWORK_TIMEOUT' app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt
```

| # | 期望 | 不满足说明什么 |
|---|---|---|
| ① | **0 命中**（网易侧一处都不出现字面 `ntwk`） | 有旁路拼 id ⇒ 收藏/下载主键会撕裂（R5） |
| ② | 只出现 `networkSource = "meting"`（或等价常量）；**任何 `"netease"` 都是违规** | `NetworkMusicManager.kt:183-186` 精确路由不 fallback ⇒ 编译过、lint 过、点播才炸 |
| ③ | 恰好 **3 处**：`var fromRedirect = false` 声明、`fromRedirect = !location.isNullOrBlank()` 赋值、`if (fromRedirect && …probe…)` 闸门 | 若 200-JSON 分支也能探测：把「非空即成功」的既有语义改坏了，且 baidu/jamendo 会被误伤（它们不进 `resolvePlayUrlDetailed`） |
| ④ | `private const val NETWORK_TIMEOUT = 8_000L`，且 `NAS_TIMEOUT = 15_000L` 未动 | 分支是 `awaitAll`（取最大值）不是求和，所以 8s 安全；若 `NAS_TIMEOUT` 被改动就是越界 |

再补一条人眼检查（grep 不好写）：`NetworkArtistExpansion.kt` 与 `NeteaseOpenApi.kt` 里 **`st` / `privilege` 只出现在「打标 / 排序」的表达式里，绝不出现在 `filter` 的谓词里**（T2：`privilege.st` 只打标不过滤）。

- [ ] **Step 4：复跑探针脚本，确认本文前提没被上游改动**

只跑**不打播放端点**的默认轮（A→A2→B→C＋字段覆盖→D，汇总行应为 `TOTAL=41`，与 `probe_v3_fields.log` 同形）：

```bash
PYTHONIOENCODING=utf-8 python docs/archive/verification/scripts/netease_artist_probe.py \
  > logs_temp/probe_v5_regression.log 2>&1
echo "exit=$?"
tail -3 logs_temp/probe_v5_regression.log
```

⛔ **不要**在这一步带 `--multi` / `--cross` / `--platforms` / `--play`——它们打真实播放端点、请求量 ≈ `N × 端点数 × 重试`，是上一轮被限流的原因；本轮只是回归，不是重新测量。

退出码判读（脚本自己的约定）：
- `0` ⇒ `FAIL=0`，本文 §2 / §4 / §7 的全部前提仍然成立，继续 Step 5。
- `1` ⇒ **上游行为变了**。⛔ 停在这里，把 `tail -40` 的失败断言贴进提交信息并回到 §2 重审——本文的可播率、字段覆盖、`total` 抖动三条前提都是从这个脚本来的，脚本红了就等于计划的前提红。
- `2` ⇒ 环境态（无网络，或 A 组三端点全部 429/5xx 而其余无失败）。**这不是失败**：换时段重跑；连续两轮 `2` 才需要把「端点可用性」列进 R9 风险登记。

转存前先探测真实编码（AGENTS.md 的 CP936 教训；bash 重定向 normally 已是 UTF-8+LF，PowerShell 产生的才是 UTF-16/CRLF）：

```bash
python -c "import pathlib;d=pathlib.Path('logs_temp/probe_v5_regression.log').read_bytes();\
print('bom' if d[:3]==b'\xef\xbb\xbf' else 'plain','crlf' if b'\r\n' in d else 'lf',len(d))"
cp logs_temp/probe_v5_regression.log docs/archive/verification/netease_artist_probe/probe_v5_regression.log
```

若上一步打印出 `crlf` 或 `bom`，先用 `python -c` 配 `encoding='utf-8'` 规范化后再拷，**不要用 `Set-Content`**。

- [ ] **Step 5：`docs/technical-overview.md` 追加 §10.225**

在 §10.224 那一节**之后**、下一章节标题之前插入。正文照下面这段写，只把 `<…>` 两处换成 Step 1/4 的真实输出（**这是本 Task 唯一允许现场取数的两处，其余都是定稿文案**）：

```markdown
### 10.225 v2.38.7（随下一版发布，不独立发版）— 网络搜索命中艺人时取全量作品 + 可播判据做实 + 不可播自动替换（2026-10-11）

**背景**：统一网络搜索走 Meting 端点，`type=search` 单页恒定 **30 首**、`page`/`limit` 参数被忽略（§2 A 组，重叠率 1.00），热门艺人实际作品数是几百首量级。owner 裁决 Q1(C+B 可播置顶+打标不隐藏)、Q2(网易免鉴权优先、失败降级 meting 端点)、Q3(先评估界面影响，评估答复见方案 §10)、Q4(判据做实)、Q5(保留「换一批」不动)、Q6(平铺不分组)、Q7(自动替换播放列表内容)、Q8(只做网易，不做 kugou)、Q9(不独立发版)。唯一方案依据 `docs/network-artist-full-discography-plan.md`（v1.7）；本节不重复其取证，只沉淀落码事实。

**架构（三层，全加法）**：① 数据层 `netease/NeteaseOpenApi.kt`（免鉴权 `/api/search/get` type=1/100 + `/api/v1/artist/songs` 分页）、`NetworkArtistTypes.kt`、`NetworkSongId.kt`（`ntwk_meting_<netId>` 的**唯一**构造点）；② 策略层纯 Kotlin 件 `NetworkArtistExpansion.kt`（是否展开/翻页游标/置顶集/打标集）、`PlayabilityProbe.kt`（`Playability{PLAYABLE,DEAD,UNKNOWN}` + `HttpPlayabilityProbe`）、`CandidatePicker.kt`（同源重搜 ≤3 候选）、`ReplaceChain.kt`（分层计划 + 防死循环）；③ 接入层 `MetingApiService`（网易优先 + 探测接线）、`NetworkMusicManager`（直链缓存键含档位 + **负面缓存** `deadProbeCache`）、`SearchAggregator`（`NETWORK_TIMEOUT` 5s→8s）、`SearchViewModel`、`SearchTab`/`UnifiedSongRow`、`PlayerViewModel`、`NasMusicApp`（注入 `probe`）。

**四条落码事实值得迁移**：
1. **假 302 是本项目播放判据的真正根因**（R4）：`MetingApiService` 旧逻辑取到第一个非空 `Location` 即 `return`，而网易不可播曲目 302 到 `music.163.com/404` 的 HTML——非空但播不了。修法是在**跟完重定向那一侧**加一次 `Range: bytes=0-0` 探测，按 `Content-Type` 归类；`UNKNOWN`（超时/异常/空 CT/非 2xx）**绝不能降级成 `DEAD`**，否则网络抖动会变成「曲库大面积灰掉」。200-JSON 分支保持「非空即成功」原语义不动，`probe` 参数默认 `null` ⇒ 未注入时与改造前逐字节一致。
2. **`privilege.st` 只打标不过滤**（T2）：实测分布 `{-200:517, 0:49}`，与判定的相关性只由 12 例人为配平样本支撑。文案因此是「**可能**无版权」而不是「无版权」。
3. **`type=1` 搜索响应没有 `privilege`、没有 `album.picUrl`（0/100）**，但**带 `artists[].id`** ⇒ 角标与置顶只在艺人展开分支上成立，普通搜索分支结构性拿不到这些字段（`NeteaseType1FieldTest` 是这条的负向护栏）。
4. **两个 id/来源红线编译与 lint 都不报**：`Song.id` 必须走 `NetworkSongId.forNetworkSong`，`networkSource` 必须恒为 `"meting"`（`NetworkMusicManager` 精确路由不 fallback）。写错的发现时机是**点播**，护栏是 `NetworkArtistSongIdContractTest`（逐字符对账 Meting 侧解析结果）。

**测试**：11 个测试文件 / 568 例（基线 518 + 本批 50），`testDebugUnitTest` **FAIL=0 / ERROR=0 / SKIPPED=<Step 1 skipped>**；lint 无新增 error。回归探针 `<probe exit>`（安全组 A→A2→B→C＋字段覆盖→D，`FAIL=0`，日志 `docs/archive/verification/netease_artist_probe/probe_v5_regression.log`）。

**未做（明确留痕）**：kugou/qq/kuwo/migu 原生接口（Q8；kugou 虽实测支持 1845 首真分页但直链 0/4）；`privilege.st` 过滤；变体后缀重搜（会击穿「1 次搜索 + 3 次解析」预算）；「换一批」链路（Q5，零改动）；跨源替换的候选集仍是**注册源**（baidu/jamendo 条件注册，多数配置为空集）。
```

- [ ] **Step 6：CHANGELOG 加 `## [Unreleased]` 段**

Q9 定了不独立发版，`v2.38.6` 又已打 tag（该段冻结、历史段落不改），所以在 `## [v2.38.6] - 2026-10-10` **上方**插入一段；发版时由 cutting 的人改名，本批不动 `app/build.gradle.kts:47-48` 的 `versionCode = 175` / `versionName = "2.38.6"`。

```markdown
## [Unreleased]

### Added

- **网络搜索命中艺人时返回该艺人的全部作品**：结果可上下翻页续取，可播项排在前面
- **疑似无版权的歌曲不再隐藏**，行尾标注「可能无版权」，点播失败后会自动换用同名的可播版本继续播放
```

（口径照 AGENTS.md：**只写做了什么**——一条一行，不写根因、不写实现细节、不写文件行号；「假 302 / `privilege.st` / 负面缓存」这些全在 §10.225。文档改动本身不进 CHANGELOG，但本批是代码改动，必须有这两行。）

- [ ] **Step 7：提交**

```bash
git add app/src docs/technical-overview.md CHANGELOG.md \
        docs/archive/verification/netease_artist_probe/probe_v5_regression.log
git status --short          # 逐行确认：logs_temp/ 下的东西一条都不该出现
git commit -m "feat(network): 统一搜索接入网易免鉴权优先与艺人全量展开"
```

`git status --short` 里若出现 `?? logs_temp/…` 属正常（`.gitignore` 已挡），但出现 `?? output/…` 或根目录裸文件必须先归位再提交（AGENTS.md「Repo-root hygiene」）。

> ⛔ 本 Task 到「本地提交」为止。**不 push、不打 tag**（Q9 + AGENTS.md 的推送需另行确认）；`./gradlew.bat assembleRelease` 若由 owner 另行要求，注意它需要 `keystore.properties` 与 `cryptoPassphrase` 行，且输出重定向到 `logs_temp/release_build.log`。

---

## 附录 A：复现命令

脚本：`docs/archive/verification/scripts/netease_artist_probe.py`。命令行参数只有 `--keyword --artist --play --endpoint --pacing --multi --cross --platforms --latency` 九个（`grep -n add_argument` 可自证）；**默认轮就是安全轮**（不打任何播放端点），凡是带 `--play/--multi/--cross/--platforms` 的都会打真实播放或真实 CDN。

以下命令统一在仓库根、Git Bash 下执行，先设一次别名：

```bash
PROBE="PYTHONIOENCODING=utf-8 python docs/archive/verification/scripts/netease_artist_probe.py"
```

| 轮次 | 完整命令 | 覆盖组 | 存档 log | 汇总 |
|---|---|---|---|---|
| **默认轮 = 安全轮（本文主体前提；§2 C.1 字段覆盖也在这一轮里，无额外参数）** | `$PROBE --keyword 陈奕迅 > logs_temp/probe_v3_fields.log 2>&1` | A / A2 / B / C（含字段覆盖）/ D | `probe_v3_fields.log` | `FAIL=0 WARN=5 TOTAL=41` |
| **L 组延迟轮** | `$PROBE --latency 8 --pacing 1.2 > logs_temp/probe_v4_latency.log 2>&1` | 默认 + L（各接口 p50/p95/max） | `probe_v4_latency.log` | `FAIL=0 WARN=5 TOTAL=46` |
| **E 组判据轮** | `$PROBE --play 12 --endpoint redcha > logs_temp/probe_e.log 2>&1` | 默认 + E（6+6 人为配平） | — | ⛔ **只验证判据能否二分，不产出可播率** |
| **F 组可播率矩阵** | `$PROBE --multi 20 --pacing 1.5 > logs_temp/probe_f.log 2>&1` | 默认 + F（随机抽样 × 多端点） | — | 本文 §4 可播率数字的**唯一合法来源** |
| **G1/G2/G3** | `$PROBE --multi 20 --cross > logs_temp/probe_g.log 2>&1` | 默认 + F + G（G 依赖 F 的 DEAD 集，**必须与 `--multi` 同跑**） | — | 含 id 串歌负自检 |
| **H 组平台探测** | `$PROBE --platforms > logs_temp/probe_h.log 2>&1` | 默认 + H（kugou/tencent/kuwo/migu 原生免鉴权） | — | — |
| **全量轮（本文 22:13）** | `$PROBE --multi 20 --cross --platforms > logs_temp/probe_v2_full.log 2>&1` | A→A2→B→C→D→F→G1→G2→G3→H | `probe_v2_full.log` | `FAIL=0 WARN=8 TOTAL=74` |

换艺人 / 换关键词（`--artist` 不给时由 C 组自动发现）：

```bash
$PROBE --keyword 陈奕迅 --artist 3485 > logs_temp/probe_eason.log 2>&1
```

**日志转存纪律**（⛔ 本文所有数字只能从这些 exit-0 的存档 log 里抄）：

```bash
# ① 先探测真实编码：bash 重定向 normally 是 plain+lf；PowerShell 产生的常是 UTF-16/CRLF，
#    直接 cp 进 docs/archive/ 会让后续 grep/wc 读成空文件（本项目真实踩过）
python -c "import pathlib;d=pathlib.Path('logs_temp/probe_v2_full.log').read_bytes();\
print('bom' if d[:3]==b'\xef\xbb\xbf' else 'plain','crlf' if b'\r\n' in d else 'lf',len(d))"

# ② 需要规范化时用 python 往返（⛔ 不用 Set-Content / PowerShell 任何文本往返）
python -c "import pathlib;p=pathlib.Path('logs_temp/probe_v2_full.log');\
s=p.read_text(encoding='utf-8');pathlib.Path('docs/archive/verification/netease_artist_probe/probe_v2_full.log')\
.write_text(s.replace('\r\n','\n'), encoding='utf-8')"

# ③ 核对存档与汇总行
grep -c "^\[PASS\]\|^\[WARN\]\|^\[FAIL\]" docs/archive/verification/netease_artist_probe/probe_v2_full.log
tail -3 docs/archive/verification/netease_artist_probe/probe_v2_full.log
```

请求量级提醒：`--multi N` 的实际请求数 ≈ `N × 端点数 × 每档重试`，`--cross` 再加一轮跨平台重搜；`--pacing` 默认 1.5s，**跑全量轮别调小**（上一轮 mikus 出现过时段性 429，见附录 B 与 C 表第 8 行）。

退出码：`0` 全部通过 / `1` 有断言失败（**上游行为变了，本文前提需重审**）/ `2` 环境态（无网络，或 A 组三端点全部 429/5xx 而其余无失败）。

## 附录 B：本轮调研中我自己踩到的四个测量错误（保留，供后人别再踩）

1. **把 302 当可播证据。** 最初抽样 25/25 个 artist-list id 全部拿到 302，据此得出"播放链完全复用"的乐观结论。跟完整条链才发现其中一半落到 `music.163.com/404` 的 HTML。E 组因此把判据定成"最终响应 `Content-Type` 含 audio"，并加了"必须存在 DEAD 样本"的负自检。
2. **把顺序相等当分页判据。** `page=2` 与 `page=1` 顺序不等当时被读成"端点开始支持分页"。改成集合 Jaccard 后复测，redcha 的 `artist page=2` 重叠率实为 **1.00（同一批）**，热榜重排只是顺序变化。脚本现在有正自检（同请求重取必须 1.00）+ 负自检（跨接口必须 <1.00）双向钉死这个判据。
3. **拿字段分布外推可播率，还把它写成实测。** v1.4 的 **8.7%** 是 `privilege.st==0` 的条数占比（49/566）——**一个都没打过播放端点**，属于"用上游的一个权限标志推断结论"。补做随机抽样（F 组）后实测是 **15.0%（3/20）**，且 `st` 与判定的相关性只由 12 例人为配平样本支撑。正确分工：**E 组只验证判据能否二分，F 组才产出率。** 这个 8.7% 一度进了 §0、§4、R2、Q1 四处，v1.5 全部替换。
4. **凭记忆引用未落盘的脚本输出。** 中途我说过"redcha 与 qijieya 的 30 首重叠率 0.00、并集 60 首"（并据此设想过"多端点取并集绕过 30"），也引用过几个来自已删除草稿脚本的百分比。实际存档里是 **Jaccard 0.88、并集 32 首**，方向完全相反。**判据**：⛔ 只有 `docs/archive/verification/netease_artist_probe/probe_v2_full.log`（本轮 exit 0 / FAIL 0 / 74 断言）里的行可以被抄进文档；草稿脚本（`logs_temp/probe_*.py`）与记忆都不算来源。

> ⛔ 改本文任何数字前先重跑脚本。上一轮 E44 明月就吃过"把一次带 bug 的沙箱输出抄进文档，假峰值活了整个定稿流程"的亏（成因是探针作用域读错变量，见 AGENTS.md「E44 明月云场基线」），本轮的四个坑性质完全相同。

## 附录 C：v1.5 修订说明（回应 owner 三连问）

| # | v1.4 的说法 | v1.5 的更正 | 依据 |
|---|---|---|---|
| 1 | 可播率 **8.7%**（49/566，由 `privilege.st` 外推） | **15.0%（3/20 随机抽样），三端点一致、0/20 分歧**；`st` 外推值仅作同数量级参考 | F 组 |
| 2 | 「`privilege.st` 是可播性的可靠预测字段」 | 降级为「**仅作分布参考**」：12/12 只证明判据可二分，不证明该字段可用来**过滤/隐藏数据** | E + F 组对照 |
| 3 | 「端点 fallback 对假成功不生效」（列为风险 R4） | 升级为**已核实的代码事实**：`:257-282` 确实遍历全端点×档位，`:271-276` 首个非空 `Location` 即 `return`；跨源降级前提也是空串（`NetworkMusicManager.kt:391`）。**并补上"修它不提高可播率"这条决策前提** | 代码逐行 + F 组 |
| 4 | R2 的隐含出路是"多端点会兜住" | **兜不住**：三端点同源、结果集 Jaccard 0.88~0.94、并集仅 31~32 首、判定 0/20 分歧 | A2 + F |
| 5 | P3「三家的艺人接口完全没调研」 | **已调研**：kugou ✅ 真分页全量（1845）但直链 0/4；qq ❌ 需登录态（500003）；kuwo/migu ❌ 免鉴权不可用；端点侧只有 qijieya 认 kugou/migu | G1 + H |
| 6 | （新增认知）跨平台救援可行 | 实测救回 **1/5**；且 `Song` 不带平台字段 → 候选无法落库（R8、Q7）；G3 串歌 0，切平台只会失败不会放错歌 | G2 + G3 |
| 7 | （新增）`limit=200` 异常里 `total` 记为 332 | 本轮实测 **241** → `total` 自身随 `limit` 抖动，"必须锁 `limit ≤ 100`" 的结论不变但依据更强 | D 组 WARN |
| 8 | mikus 状态记为「429 限流，本轮跳过」 | 22:13 轮 mikus **全程可用**（A/G1 各 n=30、F 组 20 首跑完）→ 限流是时段性，两侧都不可假设 | A / F / 假信号 2 |

### C.2 v1.6：owner 提出的"不可播→重搜换源"降级链，逐行核对结果（代码事实，非测量）

| 环节 | 直觉预期 | 核对结果 |
|---|---|---|
| 重搜换源机制 | 需要新写 | ✅ **早已存在且相当完整**：`title+artist` 重搜 → 逐候选校验 → 排除原曲 → 替换队列 + toast + 防死循环（`NetworkMusicManager.kt:382-441`、`PlayerViewModel.kt:471-510`） |
| 触发 | 不可播即触发 | ⛔ **不触发**：前提是解析结果为空串（`:391`），而不可播曲目返回的是假 302（非空 `Location`） |
| 范围 | 换 Meting 的其他 `server` 平台 | ⚠️ **换的是注册源**（baidu / jamendo），且两者**条件注册**（`NasMusicApp.kt:322-328`）→ 多数配置下候选集为空；jamendo 上"同曲另一演唱者"也不是原曲 |
| 候选校验 | 能筛掉不可播 | ⚠️ **同一个弱判据**（`!isNullOrBlank()`，`:429`）→ 会把候选的假 302 判成可播；方法注释"避免搜到但依然播不了"（`:373-374`）在当前判据下不成立 |
| 收益 | 可能是可播率的解法 | ❌ 不是。同平台重搜 ≈0（F 组 0/20 分歧、A2 并集 32 首）；跨平台实测救回 **1/5**（G2） |

### C.3 v1.7：Q1–Q9 裁决入账 + 改写为可开发粒度时的逐条自我更正

v1.7 的正文变化分两类。**决策面**：§9 逐条记录 owner 裁决，§10 是 Q3（"先评估界面怎么调、改动有多大"）的答复，§11 按裁决拆成 9 个 Task 并给出逐文件真实代码。**取证面**：下表八条引用错误——它们比决策更值得留档，因为成因全是同一个"**文档写得比代码自信**"，而每一条都会在施工中途把执行人带偏。

| # | v1.6 及更早的说法 | v1.7 的更正 | 核对方式 |
|---|---|---|---|
| 1 | 目标模块写着「`ui/viewmodel/` 新增 `ArtistSongsViewModel`」 | ⛔ **不新增 VM**。Q2 裁决"直接用现在的统一搜索入口" ⇒ 状态属于搜索域，由 `SearchViewModel` 组合一个纯 Kotlin 件 `NetworkArtistExpansion`；新增状态一律不进已 359 行、R-1 刚拆过的 `SearchViewModel` 字段区 | `wc -l SearchViewModel.kt` = 359 + §11 Task 4 的类签名 |
| 2 | 「`PlayerViewModel` 已有**层级1 同源重搜**，本期只是接上网易曲目」 | ⚠️ 层级1 **只存在于 KDoc**：`:464-465` 写着"层级1（同源重搜）：用 title+artist 调 `search()`"，而 `:471-497` 的方法体里只有 `tryCrossSourceReplace` 一条分支，同源分支**从未实现**。所以 Task 7 是"新写 + 把 KDoc 与实现对齐"，不是"接线" | 逐行读 `PlayerViewModel.kt:461-512` |
| 3 | 「搜索页有既有的**换一批**按钮，命中艺人分支时隐藏它」 | ⛔ **该按钮今天不存在**。全仓库 `shuffleNetworkSearch` 只有定义（`SearchViewModel.kt:217`）+ 一条注释（`:268`），**UI 侧零调用点**；屏幕上两处可见的「换一批」都不走网络搜索路径（`DiscoverTab.kt:195/205` → `LibraryBranch.kt:211-213` → `MainViewModel.refreshBrowseSongs()`；`HomeScreen.kt:267` → 智能电台）。Q5"保留"的落地口径因此是"**既不加按钮也不碰那两处链路**" | 全仓库 grep 调用点 |
| 4 | 「把可播探测逻辑放进既有的 `NetworkPlaybackResolver`，它已经是播放裁决点」 | ⛔ **职责不同**：`backend/download/NetworkPlaybackResolver.kt` 管的是"本地已下载 vs 在线解析"的**优先级**，不是"这条 URL 播不放得出来"的**判据**。塞进去会让下载语义与播放判据互相污染 ⇒ 新建 `PlayabilityProbe`（接口 + `HttpPlayabilityProbe`），可测性靠 lambda 注入 | 读该类全部公开方法 |
| 5 | 「`browse/SongList.kt:82-93` 里有列表回顶（BACK）」 | 两处错：① 真实路径是 `ui/screens/library/browse/SongList.kt`；② **回顶不在 `SongsTab` 里**，由 `MainActivity.kt:70` 的 `listBackHandler`（Level 1.5）+ 各列表自注册 `LocalListBackHandler` 承担。`:83-94`（前置 `:81-82` 是教训注释）是**触底续页钩子**，这才是 `SearchTab` 缺的东西 | 逐行读 `SongList.kt:81-94` 与 `SearchTab.kt:78-97` |
| 6 | 「strings 新增 3 条（角标 + 翻页计数 + 加载态）」 | ⛔ **只新增 2 条**：`song_row_unplayable_badge`（Task 8）、`same_source_replace_playing`（Task 7）。计数复用 `library_songs_count_with_total`、加载态复用 `common_loading`、跨源文案复用 `cross_source_replace_playing`（`:881`/`:1034`）。同源必须另起一条——复用跨源文案会把"用 meting 源替换 meting 源"渲染成自相矛盾的话 | `grep -c "<string "` 两侧均 1220；逐 key grep |
| 7 | 「`--latency` 轮跑 20 个样本」「字段轮跑默认参数」 | 与存档 log 对齐：延迟轮是 `--latency 8 --pacing 1.2`（`TOTAL=46`），字段轮是 `--keyword 陈奕迅` 默认组（`TOTAL=41`），全量轮是 `--multi 20 --cross --platforms`（`TOTAL=74`）。附录 A 的命令表按此重写，⛔ 参数与 log 不匹配时数字就不该被引用 | 三份存档 log 的汇总行 + 脚本 `add_argument` 清单 |
| 8 | 「`limit=200` 时 `total` = 332」 | 撤回为"**三轮三种值：241 / 194 / 1070，返回条数 200 / 194 / 200，`first100_is_subset` 三轮全 False**"。332 那一轮日志未存档 ⇒ 按附录 B 第 4 条的判据，**不可引用**。结论（锁 `limit ≤ 100`）不变，但依据换成已存档的 WARN | 三份 log 的 D 组 WARN 行 |

> ⚠️ 表里第 2、3、4 条都是**"存在性"误判**（以为有代码、以为有按钮、以为有职责），而第 1、5、6、7、8 条是**"位置/数值"误判**。前者危险得多：它会让计划写成"改造 X"，而施工时 X 根本不存在，执行人只能现场发明——那正是本方案想消灭的偏差。所以 §11 里每一个"复用既有…"的措辞都要求给出 `file:line`，且 `grep` 能自证。
