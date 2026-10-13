package com.nasmusic.tv.visualizer.renderers

import kotlin.math.ceil

/**
 * E46「迷雾之战 · 雾中三脚巨影」画质档 —— ⛔ **刻意不是** `androidx.tv.material3` 的 `FxLevel`、
 * 也不是 `Color`。
 *
 * 为什么自建（照 `MoonOpBudget` / `SeasideOpBudget` 先例，`docs/e46-tripod-fog-plan.md` §10.2 G4
 * 的同一条硬前提）：[TripodOpBudget] 的三道门（G4 的 ops / 填充 / native 三张表）必须在
 * **纯 JVM 单测**里直接调，而带 Compose / material3 类型的签名在 JVM 单测里会 `not mocked`
 * （`FxLevel` 是 value class、`Color` 走 inline class 包装）。
 * 渲染层做**一次性映射**即可：`FxLevel.OFF → [TripodLevel.LOW]`、
 * `FxLevel.LITE → [TripodLevel.MEDIUM]`、`FxLevel.FULL → [TripodLevel.HIGH]`（K6 落地）。
 *
 * ⚠️ **顺序语义**：[LOW] < [MEDIUM] < [HIGH]，与 §3.1 的三档门控一致
 * （流动雾条数 `3/7/7`、火星短划 `0/0/48`、建筑运动模糊 `0/有/有` 逐档抬）。
 */
internal enum class TripodLevel {
    /** `FxLevel.OFF`：单雾层（A 3 条）、⛔ 无 grade / 腿背光 / 缆须 / 火星 / 负压 / 抓取 / 扫光。 */
    LOW,

    /** `FxLevel.LITE`：全量 − 火星短划（默认档）。 */
    MEDIUM,

    /** `FxLevel.FULL`：全量 + 热射线烤起的火星短划（`EMBER`，+48 图元 / +0.008 屏）。 */
    HIGH
}

// ══════════════════════════════════════════════════════════════════════════════
//  结构常量 —— ⛔ 必须是**顶层** const 且**声明在枚举之前**：枚举的构造参数里引用自己
//  companion 的成员会被编译器拒绝；同文件内的前向引用同样不可靠。使用点被内联 ⇒
//  零运行期依赖、零初始化顺序问题（E43/E44 两个 OpBudget 文件头同一条）。
// ══════════════════════════════════════════════════════════════════════════════

/** 一次全屏 pass 的填充（`w·h` = 1 屏）—— [TripodOpItem.DAMAGE_COALESCER] 用，⛔ 不能记 0。 */
internal const val TRIPOD_POST_FILL = 1.000

// ── 常驻 native 位图的结构常量（`nativePxBudget` 的输入；⛔ 手抄，全部有探针再生）─────────

/** 建筑位图宽（`bakeBuilding` 的 `px = 96`，两带共用）。 */
internal const val TRIPOD_BUILDING_TEX_W = 96

/** 建筑位图高的**钳位上界**（`clamp(round(96·h/w), 48, 320)` 的 320）。 */
internal const val TRIPOD_BUILDING_TEX_H_MAX = 320

/** 建筑位图高的钳位下界（同式的 48）。 */
internal const val TRIPOD_BUILDING_TEX_H_MIN = 48

/** 烘焙栋数：中景 `BAND.mid.n = 34` + 近景 `BAND.near.n = 14`（§A.7）。 */
internal const val TRIPOD_BUILDING_COUNT = 48

/** 建筑位图的**实测**像素合计（`e46_bitmap_inventory.js`：mid 407712 + near 159552）。
 *
 * ⚠️ 它是 `lcg(20261011)` 的**抽样结果**而不是结构量 ⇒ Kotlin 侧 `TripodFogBake` 移植
 * `bakeBuilding` 后必须逐栋复现这份尺寸（`TripodOpBudgetTest` 有对账），
 * ⛔ 不要因为它"看起来该更小/更大"就改这个数。 */
internal const val TRIPOD_BUILDING_PX_MEASURED = 567_264

/** 建筑位图的**结构上界** = 48 × 96 × 320（每栋都按最高钳位记）—— 只给负向自证用。 */
internal const val TRIPOD_BUILDING_PX_MAX = TRIPOD_BUILDING_COUNT * TRIPOD_BUILDING_TEX_W * TRIPOD_BUILDING_TEX_H_MAX

/** 雾条位图：A `256×300` + B `192×300`（`bakeAll` 的字面量，`e46_bitmap_inventory.js` 同）。 */
internal const val TRIPOD_FOG_STRIP_PX = 256 * 300 + 192 * 300

/** 人群姿态位图：4 张 `14×34`（`bakePerson`，R18 四类姿态）。 */
internal const val TRIPOD_PERSON_PX = 4 * 14 * 34

/**
 * 逐帧绘制元素 —— **一个元素一项**，系数 ⛔ **全部是实测值**（不是设计值）。
 *
 * ## 每一行两组数是什么
 * | 组 | 含义 | 喂给 |
 * |---|---|---|
 * | `opsLow/Med/High` | 该元素在该档位的**每帧提交数**（draw call 数） | [TripodOpBudget.estimate] |
 * | `fillLow/Med/High` | 该元素在该档位的**每帧填充量，单位「屏」**（`w·h` = 1 屏，⛔ **含该元素自己的全部提交**） | [TripodOpBudget.overdrawEstimate] |
 *
 * ## ⭐ 口径 = **逐类最坏帧**，⛔ 不是 §十 的 60 帧均值表（2026-10-13 K2 定的分工）
 * §十 那张 23 类表是 `__E46.profile(60)` 的**均值**（典型帧，定位"哪层吃掉铺屏"用）；
 * 本枚举是 `e46_fog_worstframe.js --frames 1800 --variants`（三套剧本 × 三档 × 1800 帧
 * 均匀采样）跑出来的**逐类 max**，理由与 E44 完全同一条：
 * - **条件项照上界落表**（`EMBER` / `WAKE_*` / `GRAB_*` / `FOOT_SHADOW` / `LEG_RIM` /
 *   `BACK_HILITE` / `BUILDING_SCAN` / `GRADE` 都可能某帧为 0）⇒ 门守护的是**最坏帧**；
 * - 铺屏一律 `ceilToMilli` **向上**取整到 3 位（`0.9664 → 0.967`），⛔ 就近取整会把门写成下界
 *   （E44 `GLITTER` 的 MED 就是这么差点做假的）。
 *
 * ⚠️ **合计因此必然高出"单帧最坏"**：本效果的动态类（腿 / 建筑 / 贴地雾带 / 光束柱体）
 * 最坏帧**互不同刻**（腿在贴身掠过段最大、建筑在甩镜模糊段最大）⇒ 逐类 max 之和
 * LOW `4.845` / MED `6.551` / HIGH `6.559` 屏，而**单帧**最坏只有 `2.958 / 5.412 / 5.414`
 * （+21% ~ +64%）。这不是记错，是"每类各自的上界"这个口径的固有保守量，
 * 由 [TripodOpBudget.MEASURED_FILL_LOW] 等三个锚把真实最坏帧单独登记（两条判据分工同 E44）。
 *
 * ## 逐项系数的来源（⛔ 改动前先读这三条）
 * 1. **口径 = 跨剧本最坏帧**：V0/V1/V2 三套剧本（`VARS[0..2]`，§A.9）各跑一遍取最大
 *    —— ⛔ 只在 V0 上量（V1/V2 的缺陷会是隐形的，R29 的同一条纪律）。
 * 2. **档位差是代码事实**，不是"省预算"：LOW 砍掉的是 grade / 腿背光 / 缆须 / 火星 /
 *    负压雾脊+通道 / 抓取两层 / 建筑扫光 / 接触影 / 机背高光（§十 LOW 列），
 *    以及**建筑运动模糊**（`drawBuildings` 的 `blur = tier==='LOW' ? 0 : clamp(|yaw'|·3, 0, 1.5)`
 *    ⇒ near 带宽 ×(1+1.6·blur) ≤ 3.4×、横移 ≤ 45 px）—— 这条同时是
 *    [TripodOpItem.BUILDINGS] 的 LOW 铺屏 `0.471` **低于** MED/HIGH `0.967` 的**全部成因**。
 * 3. ⚠️ **`fill*` 是 16:9 口径**：以 `minDim` 定径的项换算式里含 `h/w`，非 16:9 画幅按比例变。
 *    本表钉的是电视横屏，⛔ 不要把它当"与画幅完全无关"。
 */
internal enum class TripodOpItem(
    /** 中文元素名（单测报告用；与原型 `op(...)` 的 tag 逐字对齐，便于对照 harness 输出）。 */
    val label: String,
    val opsLow: Int,
    val opsMed: Int,
    val opsHigh: Int,
    val fillLow: Double,
    val fillMed: Double,
    val fillHigh: Double
) {
    /**
     * 流动雾条（§五 图层 2/8：A 层 3 条横向平铺 + B 层 4 条，LOW 只留 A 且强度补到 1.35×）。
     *
     * ⭐ 提交数是**结构量**（条数），铺屏的**真值略高于**整屏数（探针 6 位小数打印为
     * `1.000000` / `2.000000`，即真值 ∈ (1.000, 1.0000005)）⇒ [TripodOpBudget.ceilToMilli]
     * 按"表是上界"的纪律抬到 `1.001` / `2.001`。
     * ⛔ 不要因为"看着像记错"就把它改回 `1.000` / `2.000` —— 那正是就近取整把门写成**下界**
     * 的那一类错（E44 `GLITTER` MED 同源：`0.087361 → 0.088`，就近会得到 `0.087`）。
     * ⚠️ LOW 的减法必须砍这里而不是砍粒子：单雾层就占 1.000 屏，砍粒子（0.002 屏）是减了个寂寞。
     */
    FOG_STRIP("流动雾条", 3, 7, 7, 1.001, 2.001, 2.001),

    /**
     * 底色渐变（§五 图层 1：一次全屏竖向渐变 `drawRect`）。
     * 填充 = 几何恒等式 `1.000`（通铺全宽全高）。
     */
    SKY_BASE("底色渐变", 1, 1, 1, 1.000, 1.000, 1.000),

    /**
     * 暗角 + 胶片颗粒（§五 图层 11：`bakeGrade` 把两者**合成同一张全屏位图**后一次 blit）。
     *
     * ⭐ LOW 恒 0 是代码事实（`if (!low) drawGrade()`），不是让步。
     * ⛔ **不要**把它拆成基类的 `drawVignette` + `drawGrain`（各一次全屏 `drawRect`）——
     * 那会让 MED/HIGH 多付 `1.000 ~ 2.000` 屏（§十六 R8），也正是 `TripodFogRenderer.postFx`
     * 配了暗角却一帧不画的原因（见该文件类 KDoc）。
     */
    GRADE("暗角+颗粒", 0, 1, 1, 0.000, 1.000, 1.000),

    /**
     * 建筑剪影位图（§七：中景 34 栋 + 近景 14 栋烘焙，`bandWindow` 回收，一次 `drawImageRect`/栋）。
     *
     * ⭐ 提交数最坏 **19** 栋（实测 max；§A.7 的 `nMid 22 / nNear 8` 是**每帧上限**的滑杆名义值，
     * ⛔ 拿它当上界 —— 名义值 30 会把这个门写松 50%）。铺屏最坏 `0.967` 屏出现在
     * **甩镜段**（t≈86.75 s，V0）：`blur = clamp(|yaw'|·3, 0, 1.5)` 把 near 带楼宽撑到
     * ×(1+1.6·1.5) = **3.4×** 并横移 ≤ 45 px ⇒ 同 19 栋在 LOW（`blur = 0`）只铺 `0.471`。
     * ⚠️ 这一行的 LOW/MED 差**全部**来自运动模糊，⛔ 在 Kotlin 侧"顺手统一"——
     * 统一等于把 R19 的甩镜观感删掉，或让 LOW 白白多付 0.5 屏。
     */
    BUILDINGS("建筑剪影位图", 19, 19, 19, 0.471, 0.967, 0.967),

    /**
     * 腿（§3.5 两连杆 IK + 弓形渐变，三腿各一次渐变填充）。
     *
     * ⭐ 三档同提交数 3（腿永远全画），铺屏最坏 `0.534` 屏出现在**贴身掠过段**
     * （t≈55.7 s，V0，dist 11.88 m）—— 那时一条腿横跨小半个画面。
     * ⚠️ 与 [BUILDINGS] **反相**：腿最大时机器在 12 m 内（楼在身后看不见），
     * 楼最大时机器在雾尽头（腿只有 0.000 屏）⇒ 这两行的和最坏帧**永不同刻出现**
     * （本表合计高出单帧最坏的主要来源，见类 KDoc 第 3 节）。
     */
    LEGS("腿", 3, 3, 3, 0.534, 0.534, 0.534),

    /**
     * 贴地雾带（§五 图层 4，R19 后带顶锚在"远带内沿的楼基线"）：一次纵向渐变 `drawRect`。
     *
     * ⭐ 最坏 `0.395` 屏 > 均值 `0.323`：带体铺到画面底（含 10 px 抖动余边），
     * 相机仰角 `tilt` 最大时多出一截。三档同值（LOW ⛔ 不砍它 —— 砍了底部就"站在硬地面上"）。
     */
    GROUND_BAND("贴地雾带", 1, 1, 1, 0.395, 0.395, 0.395),

    /**
     * 光束柱体（§六：两道热光，各一次渐变填充；`beamFoot/beamPeakAt` 管起亮爬升）。
     *
     * ⭐ 最坏 `0.350` 屏 ≫ 均值 `0.033`：熄灯段 `fireEnv = 0` 时一个像素都不铺（R17 双闸），
     * 而开火段落在**近处**的柱子横跨小半屏。三档同值（光束不是档位项）。
     */
    BEAM_COLUMN("光束柱体", 2, 2, 2, 0.350, 0.350, 0.350),

    /**
     * 建筑被扫光（§七：被光束扫中的那栋楼加一层同矩形渐变）。
     * **MED/HIGH-only**（`if (P.tier === 'LOW') continue;`），最多 2 栋（两道 beam）。
     */
    BUILDING_SCAN("建筑被扫光", 0, 2, 2, 0.000, 0.090, 0.090),

    /**
     * 腿背光轮廓光（§4 甲壳组：逆光描边）。
     * **MED/HIGH-only**（LOW 砍掉，§十 LOW 列）。
     */
    LEG_RIM("腿背光轮廓光", 0, 3, 3, 0.000, 0.065, 0.065),

    /** 机身甲壳（`lumpPath` 三段谐波呼吸的主填充）。三档同值。 */
    SHELL("机身甲壳", 1, 1, 1, 0.035, 0.035, 0.035),

    /** 地面落点亮斑（§六 `POOL`：落点处一次竖向压扁的径向渐变 blit，两道 beam 各一）。 */
    BEAM_POOL("地面落点亮斑", 2, 2, 2, 0.021, 0.021, 0.021),

    /**
     * 掠过负压雾脊（R25：贴身掠过时把贴地雾推开一道压缩脊，逐脚锚定）。
     * **MED/HIGH-only**（`WAKE.tierK` 裁 LOW）。最坏 6 处（三脚 × `dupK 1` 的上界量）。
     */
    WAKE_RIDGE("掠过负压雾脊", 0, 6, 6, 0.000, 0.018, 0.018),

    /**
     * 落地冲击环（R22：`win < 1/3` ⇒ 同帧最多一条腿在窗内 ⇒ **最多 1 图元**）。
     * 三档同值（重量感的最小单元，太小不值得裁）。
     */
    IMPACT_RING("落地冲击环", 1, 1, 1, 0.015, 0.015, 0.015),

    /** 腺囊（"光的出处"，`drawBody` 里的一次小填充）。三档同值。 */
    GLAND("腺囊", 1, 1, 1, 0.014, 0.014, 0.014),

    /** 机背高光。**MED/HIGH-only**。 */
    BACK_HILITE("机背高光", 0, 1, 1, 0.000, 0.012, 0.012),

    /** 掠过负压通道（R25：脊心一条低压通道）。**MED/HIGH-only**。 */
    WAKE_CHANNEL("掠过负压通道", 0, 3, 3, 0.000, 0.008, 0.008),

    /**
     * 火星短划（R30：热射线烤起的拉长线段，`per ∈ [9,10,15,18]` 全整除 `LOOP`）。
     *
     * ⭐ **HIGH-only**（R30-b 所有者裁决「B 留」= 保持现状，⛔ 在 Kotlin 侧顺手也给 MED 点上）。
     * 最坏 **48** 条 = `P.emberN` 滑杆量程上限 48（默认 24）—— ⚠️ 表按**量程上界**落而不是
     * 默认值 24：滑杆能拖到 48 是既成事实，按默认值落表等于宣布"拖到 48 会越限"却没人知道。
     * 铺屏只 `0.008` 屏 ⇒ 它是 HIGH 相对 MED **唯一的图元增量**（+48 个，占 HIGH 表值 40%），
     * 真机掉帧时第一刀砍它（§十六 R8）。
     */
    EMBER("火星短划", 0, 0, 48, 0.000, 0.000, 0.008),

    /** 顶罩（`cowlY + cowlRy` 口径的机顶，一次填充）。三档同值。 */
    COWL("顶罩", 1, 1, 1, 0.007, 0.007, 0.007),

    /** 脚底接触影（与 R22 冲击环同一投影点、同一压扁比 ⇒ 读成同一个物理事件）。**MED/HIGH-only**。 */
    FOOT_SHADOW("脚底接触影", 0, 3, 3, 0.000, 0.006, 0.006),

    /** 悬垂缆须（R18 抓取器官的本体：腹下一束，`tendN` 条描边）。**MED/HIGH-only**。 */
    TENDRIL("悬垂缆须", 0, 3, 3, 0.000, 0.005, 0.005),

    /** 抓取缆须（悬垂缆须的**伸长**，同一支笔；`reach ≤ 0.02` 时整段不画）。**MED/HIGH-only**。 */
    GRAB_TENDRIL("抓取缆须", 0, 2, 2, 0.000, 0.004, 0.004),

    /**
     * 人群剪影位图（R18：一人一张 14×34 烘焙位图、一次 `drawImage`）。
     *
     * ⭐ **三档同值 8 人**：LOW ⛔ 不砍人群（§十 明文）—— 满屏浓雾 + 脚边的人影是本效果的
     * 叙事本体，而 0.002 屏的铺屏代价砍它毫无收益（要砍的是 `drawGrab`，它有 `tier==='LOW'` 早退）。
     */
    CROWD("人群剪影位图", 8, 8, 8, 0.002, 0.002, 0.002),

    /** 抓取梢钩（缆须够到人时梢端两笔小钩，⛔ 不算新器官）。**MED/HIGH-only**。 */
    GRAB_HOOK("抓取梢钩", 0, 2, 2, 0.000, 0.002, 0.002),

    /**
     * `postFx` 后处理 —— **本效果三档全 0**（`TripodFogRenderer` 的 `vignetteOverride` 恒 `0f`
     * ⇒ 基类 `applyPostFx` 里 `if (strength > 0f) drawVignette(...)` 一次都不进；
     * `grain`/`scanline` 本来就是 `0f`）⇒ 暗角与颗粒全部由 [GRADE] 那一行记账。
     *
     * ⚠️ 与 E44 的差别：E44 的 `POST_FX` 行是 `0/1/1` 图元、`0/1.000/1.000` 屏（它真的走基类暗角）。
     * 本效果**保留这一行**（而不是删掉）是为了让"后处理确实零开销"成为可断言的**事实**，
     * ⛔ 不要因为"实测没有"就删行 —— 删了就没有任何东西能发现"有人把 vignetteOverride 改回 0.42f"。
     */
    POST_FX("postFx", 0, 0, 0, 0.000, 0.000, 0.000),

    /**
     * 基类洼地合成器（`RendererFx.draw` 第 ④ 步，`needsDamageCoalescer`）—— **LOW 独有**。
     *
     * `needsDamageCoalescer(level, postFx) = level == FxLevel.OFF || !postFx.hasFullScreenPass()`；
     * 本效果 `postFx = PostFx(vignette = 0.42f)` ⇒ `hasFullScreenPass()` 为真 ⇒
     * MED/HIGH **不补**（它们自己有全屏 pass，补那一次纯属浪费 1.000 屏），
     * 只有 LOW（`FxLevel.OFF`）躲不掉：基类补一次不可见全屏 `drawRect`（α = 1/255）。
     *
     * ⚠️ **探针读不到它**（它在基类里，不在原型的 `fillBy` 里）⇒ LOW 的合计必须**手工 +1.000**，
     * 这一行就是那 +1.000 的落账处。与 E44 同名行同值同因。
     */
    DAMAGE_COALESCER("洼地合成", 1, 0, 0, TRIPOD_POST_FILL, 0.0, 0.0);

}

/**
 * ⭐ G4 三张门（提交 / 填充 / native 堆），把 §十 的预算变成**构建期失败**。
 *
 * ## 为什么是纯函数而不是源码扫描（§10.2）
 * 渲染器字段初始化就会建 `Path`/`Paint`/`Bitmap`，⛔ 在纯 JVM 里根本无法 `new`；
 * 源码抓取既测不出真实提交数、又会被注释与格式骗过 ⇒ 唯一可行形态是
 * **把计数做成纯函数**：渲染层按同一张表实现，本门禁按同一张表算，两边对不上就是回归。
 *
 * ## 硬约束
 * 1. ⛔ **零 Android import、零 Compose import**（否则 JVM 单测 `not mocked`）。
 * 2. ⛔ **零 `Random`**、每帧路径**零分配**：唯一的表是枚举，函数体里只有 `while` 与标量累加。
 * 3. ⛔ 本表**不设 `waveCount` 之类的入参**（E43 有）：本效果没有"逐浪"元素，逐档差异全部已在
 *    [TripodOpItem] 的三列里。
 */
internal object TripodOpBudget {

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·① 提交预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * **G4 ops** LOW 档每帧提交上限。
     *
     * ⭐ 与 E43/E44 **同值**（`MoonOpBudget.OPS_MAX_LOW`）—— 这是**设备级**的提交数预算
     * （创维 API 22 的 HWUI 每帧 draw call 上限），不是单效果观感参数 ⇒ ⛔ 不要为本效果单独调。
     * 本效果表值 44 ⇒ 余量 2.05×。
     */
    const val OPS_MAX_LOW = 90

    /** MED 档每帧提交上限（与 E43/E44 同值）。本效果表值 73 ⇒ 余量 2.74×。 */
    const val OPS_MAX_MEDIUM = 200

    /** HIGH 档每帧提交上限（与 E43/E44 同值）。本效果表值 121 ⇒ 余量 2.64×。 */
    const val OPS_MAX_HIGH = 320

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·② 填充预算（单位「屏」，`w·h` = 1 屏）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * **G4 fill** LOW 上限 = **5.00 屏**（表值 `4.845` + 余量 `0.155`）。
     *
     * ⚠️ **本效果不能沿用 E44 的 `3.45`**：LOW 表值 4.845 里有三笔 E44 LOW 没有的结构性成本 ——
     * ① 洼地合成 `+1.000`（`FxLevel.OFF` 躲不掉，同 E44）；② 建筑 `0.471`（LOW 也画满 19 栋，
     * 只是没有运动模糊）；③ 腿 `0.534`（贴身掠过段一条腿横跨小半个画面）。
     * ⛔ 不要为了"把上限压回 3.45"去砍这三项 —— 它们分别对应 P-3 崩溃规避、天际线本体与重量感。
     */
    const val OVERDRAW_MAX_LOW = 5.00f

    /**
     * **G4 fill** MED 上限 = **6.75 屏**（表值 `6.551` + 余量 `0.199`）。
     *
     * ⚠️ 同样不能沿用 E44 的 `4.00`：本效果 MED 的**单帧**最坏就有 `5.412` 屏
     * （t=86.75 s 甩镜段：雾条 2.000 + 底色 1.000 + grade 1.000 + 建筑 0.967 + 贴地雾带 0.395），
     * 已高于 E44 已验收 HIGH 的 4.21 ⇒ 这条越限是**既成事实**，处置见 §十六 R8
     * （降级杠杆顺序：暗角颗粒 → 雾条块数 → 贴地雾带；⛔ 第一刀砍雾密度、⛔ 砍人群）。
     */
    const val OVERDRAW_MAX_MEDIUM = 6.75f

    /**
     * ⛔ **HIGH 档没有绝对填充上限** —— 照 E44 的所有者裁决口径
     * （「将上限值调整合适，保证 HIGH 能体现所有效果，实际上 high 不应该有上限」）。
     *
     * ⚠️ **「不设上限」≠「没有判据」**。HIGH 侧改跑两条**真实**断言：
     * 1. **单调方向**：`fill(HIGH) ≥ fill(MEDIUM) ≥ fill(LOW)`；
     * 2. **棘轮**：`fill(HIGH) ≤ [FILL_REF_HIGH] × [FILL_RATCHET]`。
     *
     * ⛔ 不要用一个大数字冒充"无上限"：写 `Float.MAX_VALUE` 会让 [overdrawMax] 在 HIGH
     * 变成常量真的无穷、单测里的"超限"分支永不可达。这里用 `POSITIVE_INFINITY` 并让
     * `TripodOpBudgetTest` ⛔ **断言 HIGH 走的是棘轮而不是绝对门**。
     */
    const val OVERDRAW_MAX_HIGH: Float = Float.POSITIVE_INFINITY

    /**
     * HIGH 档棘轮的参考值 = **6.00**。
     *
     * ⭐ 与 E44 的**关键差别**（E44 的 `FILL_REF_HIGH = 4.306` = 半遮态实测最坏）：
     * 本效果的表值高出实测单帧最坏 **21%**（逐类最坏帧互不同刻，见 [TripodOpItem] 类 KDoc）
     * ⇒ 若照抄"REF = 实测最坏"，**表值会越过自己的棘轮**
     * （`6.559 > 5.414 × 1.10 = 5.955`）—— 门会自己红，而红的是口径不是代码。
     * 所以 REF 改按**表值**反解：`6.559 ÷ 1.10 = 5.963` ⇒ 向上取整到 `6.00`，
     * 棘轮 `6.60` 高出表值 `0.041`。实测单帧最坏 `5.414` 由 [MEASURED_FILL_HIGH] 单独登记。
     *
     * ⚠️ 它**不是**画质天花板：要超出只需把这一行常量抬上去，但 ⛔ **必须写理由**（改动即评审）。
     * 它的唯一职责是拦**静默增长** —— 记账失控恰恰是"没人打算改观感、但每行多算了一点"的时刻。
     */
    const val FILL_REF_HIGH = 6.00f

    /** 棘轮容差（`×1.10`）：照 E44 同值，⛔ 再宽就等于没有门。 */
    const val FILL_RATCHET = 1.10f

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·③ native 堆预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 常驻 native 堆的 ARGB_8888 像素总数上限（与 E43/E44 同值）。
     *
     * ⭐ 本效果在这一门上的**结构与 E44 根本不同**：E44 是「源图 + 圆盘 + 跨效果共享的全屏
     * STARFIELD」，本效果是「两张雾条 + 48 栋建筑 + 4 张人群 + **一张全屏 grade**」。
     * grade（`bakeGrade` 按 `cv.width × cv.height` 烘）是唯一随画幅线性涨的一项，
     * 1080p 一项就 `2,073,600` ⇒ 见 [nativePxBudget] 的逐档表。
     */
    const val NATIVE_PX_MAX = 3_000_000

    /**
     * 门的**适用区间**：16:9 画幅长边 ≤ 1920。
     *
     * ⚠️ 这不是妥协，是**实测边界**：1080p 实测合计 `2,777,168`（余 `222,832` = 7.4%），
     * 而 **1440p 必然突破** —— grade 一项 `3,686,400 > 3,000,000`。
     * ⭐ 破点与 E44 **同一个长边**（都是 2560：E44 破在全屏 STARFIELD，本效果破在全屏 grade），
     * 但**成因不同** ⇒ ⛔ 不要照抄 E44 那条"`Id.STARFIELD` 没有降采样"的注释。
     */
    const val NATIVE_PX_OK_MAX_LONG_EDGE = 1920

    /** 门**破掉**的那一档 16:9 长边（1440p），给"破点哨兵"断言用。 */
    const val NATIVE_PX_BREAK_LONG_EDGE = 2560

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·④ 实测锚（⛔ 表必须 ≥ 这些数，否则门在守一个不存在的帧）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 实测**单帧最坏**提交数（`e46_fog_worstframe.js --frames 1800 --variants`，
     * 跨 V0/V1/V2 三套剧本取最大）：LOW 36 / MED 56 / HIGH 104。
     *
     * ⚠️ 它们是**单帧**总量（`E.profAt(t)` 的 `ops` 返回值），⛔ 不是逐类 max 之和
     * （那是 43/73/121，见 [TripodOpItem] 表）—— 两个数分工同 E44 的
     * `MEASURED_OPS_*` 与 `estimate()`。
     */
    const val MEASURED_OPS_LOW = 36
    const val MEASURED_OPS_MEDIUM = 56
    const val MEASURED_OPS_HIGH = 104

    /**
     * 实测**单帧最坏**填充（屏）：LOW `2.958` / MED `5.412` / HIGH `5.414`。
     *
     * ⚠️ 三个数分别是：LOW `t=55.70 s`（V0 贴身掠过段）、MED/HIGH `t=86.75 s`（V0 甩镜段）。
     * ⛔ 与 E44 的差别：E44 的 `MEASURED_FILL_LOW` 已把探针读不到的洼地合成 `+1.00` 并进探针值；
     * 本效果的原型探针**没有**基类那一项 ⇒ 这里是**纯原型账**，洼地合成的 `+1.000`
     * 只存在于表里的 [TripodOpItem.DAMAGE_COALESCER] 行。
     */
    const val MEASURED_FILL_LOW = 2.958f
    const val MEASURED_FILL_MEDIUM = 5.412f
    const val MEASURED_FILL_HIGH = 5.414f

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·① 提交预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 该档位的**每帧提交数上界**（条件项按上界计入 ⇒ 返回的是最坏帧口径的表值，不是典型帧）。
     */
    fun estimate(level: TripodLevel): Int {
        var n = 0
        var i = 0
        val items = TripodOpItem.entries
        while (i < items.size) {
            n += items[i].opsOf(level)
            i++
        }
        return n
    }

    /** [estimate] 的对应档位上限（供门禁与报告共用，⛔ 避免两处各写一份数字）。 */
    fun opsMax(level: TripodLevel): Int = when (level) {
        TripodLevel.LOW -> OPS_MAX_LOW
        TripodLevel.MEDIUM -> OPS_MAX_MEDIUM
        TripodLevel.HIGH -> OPS_MAX_HIGH
    }

    /** 逐元素提交数（单测用它逐行核对探针的逐类最坏帧表）。 */
    fun opsOf(item: TripodOpItem, level: TripodLevel): Int = item.opsOf(level)

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·② 填充预算
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 该档位的**每帧填充量**，单位「屏」。
     *
     * ⚠️ 返回值**与 `w`/`h` 的尺度无关**（过绘制率是尺度不变量），`w`/`h` 只做入参校验；
     * ⛔ 但与画幅**比例**有关（见 [TripodOpItem] 的 KDoc 第 3 条，本表按 16:9 定）。
     */
    fun overdrawEstimate(w: Float, h: Float, level: TripodLevel): Float {
        // ⛔ 刻意不带 message：那是每次调用一次的字符串模板分配，而本函数要能挂遥测。
        require(w > 0f && h > 0f)
        var acc = 0.0
        var i = 0
        val items = TripodOpItem.entries
        while (i < items.size) {
            acc += fillOf(items[i], level)
            i++
        }
        return acc.toFloat()
    }

    /**
     * [overdrawEstimate] 的对应档位上限。⛔ HIGH 恒为 `+[Float.POSITIVE_INFINITY]`
     * —— HIGH 的判据在 [fillRatchetMax] 与单调方向断言里，⛔ 不在这里。
     */
    fun overdrawMax(level: TripodLevel): Float = when (level) {
        TripodLevel.LOW -> OVERDRAW_MAX_LOW
        TripodLevel.MEDIUM -> OVERDRAW_MAX_MEDIUM
        TripodLevel.HIGH -> OVERDRAW_MAX_HIGH
    }

    /** HIGH 档棘轮的天花板 = [FILL_REF_HIGH] × [FILL_RATCHET]。 */
    fun fillRatchetMax(): Float = FILL_REF_HIGH * FILL_RATCHET

    /** 逐元素填充量（[overdrawEstimate] 的单项版本，单位「屏」）。 */
    fun fillOf(item: TripodOpItem, level: TripodLevel): Double = item.fillOf(level)

    // ══════════════════════════════════════════════════════════════════════════
    //  G4·③ native 堆
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 常驻 **native 堆**的 ARGB_8888 位图像素总数（`e46_bitmap_inventory.js` 再生）。
     * 逐项：
     * | 位图 | 尺寸 | 计 |
     * |---|---|---|
     * | 建筑剪影 ×48 | `96 × py`，`py = clamp(round(96·h/w), 48, 320)` | [TRIPOD_BUILDING_PX_MEASURED] = 567,264 |
     * | 流动雾条 A/B | `256×300` / `192×300` | [TRIPOD_FOG_STRIP_PX] = 134,400 |
     * | 人群姿态 ×4 | `14×34` | [TRIPOD_PERSON_PX] = 1,904 |
     * | grade（暗角+颗粒） | **`w × h`（全屏）** | 随画幅 |
     *
     * | 画幅 | grade | 合计（实测栋径） | 判定 |
     * |---|---|---|---|
     * | 960×540 | 518,400 | 1,221,968 | ✅ |
     * | 1280×720 | 921,600 | 1,625,168 | ✅ |
     * | 1920×1080 | 2,073,600 | 2,777,168 | ✅（余 7.4%） |
     * | 2560×1440 | 3,686,400 | 4,389,968 | ❌（grade 一项已超） |
     *
     * ⭐ 建筑那一项**直接调常量**而不是重算 48 栋的 `h/w`：`py` 是 `lcg(20261011)` 的抽样结果，
     * Kotlin 侧复现它属于 `TripodFogBake` 的保真度问题（`TripodOpBudgetTest` 逐栋对账），
     * ⛔ 不在这里复制一份 LCG —— 那是 E44 D10「表与几何各说各话」的同一条错。
     */
    fun nativePxBudget(w: Float, h: Float): Int =
        nativePxBudgetWithBuildingPx(w, h, TRIPOD_BUILDING_PX_MEASURED)

    /**
     * [nativePxBudget] 的**可注入建筑像素**版本 —— ⛔ 这就是负向自证的接缝：
     * 喂 [TRIPOD_BUILDING_PX_MAX]（48 栋全按 `py = 320` 的钳位上界记）⇒
     * `1080p = 3,684,464 > [NATIVE_PX_MAX]` ⇒ **必然红**；
     * 喂 [TRIPOD_BUILDING_PX_MEASURED]（规格口径）⇒ 1080p 达标（余 7.4%）。
     */
    fun nativePxBudgetWithBuildingPx(w: Float, h: Float, buildingPx: Int): Int {
        require(w > 0f && h > 0f)
        require(buildingPx >= 0)
        val grade = w.toDouble() * h.toDouble()
        return (grade + buildingPx + TRIPOD_FOG_STRIP_PX + TRIPOD_PERSON_PX).toInt()
    }

    /** grade 位图的像素（单独暴露给"破点在 1440p"那条哨兵）。 */
    fun gradePx(w: Float, h: Float): Int {
        require(w > 0f && h > 0f)
        return (w.toDouble() * h.toDouble()).toInt()
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  换算辅助
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 建筑位图高的钳位区间（`bakeBuilding`：`clamp(round(96·h/w), 48, 320)`）。
     * 给单测做"实测 py 必须落进区间"的对账（实测 mid `[59, 204]` / near `[71, 211]`）。
     */
    fun buildingTexHeightRange(): IntRange = TRIPOD_BUILDING_TEX_H_MIN..TRIPOD_BUILDING_TEX_H_MAX

    /**
     * 建筑位图**均高**的参照式（只给单测报告用，⛔ 不进预算）：
     * `measuredPx ÷ 48 ÷ 96` = 123.104（实测 mid 均 124.9 / near 均 118.7，按 34:14 加权；
     * ⚠️ 首版 KDoc 写的 123.5 是 567264/4608 的除法错误，已由 `TripodOpBudgetTest` 当场抓住并修正）。
     */
    fun buildingTexMeanHeight(): Double =
        TRIPOD_BUILDING_PX_MEASURED.toDouble() / TRIPOD_BUILDING_COUNT / TRIPOD_BUILDING_TEX_W

    /**
     * 向上取整到 3 位（"屏"的记账精度）。
     *
     * ⭐ [TripodOpItem] 全部 `fill*` 都由它从 6 位小数的探针原值产生：表必须是**上界**，
     * 而就近取整会把落在 `x.xxx5` 之下的换算值**砍到实测以下**，门当场从"守最坏帧"
     * 退化成"守一个不存在的帧"（E44 D26/D28 同一处分量级错）。
     * 单测 `ceilToMilli 把探针原值抬成表值` 逐行对账（含 `1.0004 → 1.001` 这种"看着像记错"的行）。
     */
    fun ceilToMilli(value: Double): Double = ceil(value * 1000.0) / 1000.0
}

/**
 * 档位取值（写成**文件级私有扩展**而不是枚举里的 `when`：
 * 枚举成员引用自身 companion 的成员会被编译器拒绝，E43/E44 两个 OpBudget 同因）。
 */
private fun TripodOpItem.opsOf(level: TripodLevel): Int = when (level) {
    TripodLevel.LOW -> opsLow
    TripodLevel.MEDIUM -> opsMed
    TripodLevel.HIGH -> opsHigh
}

private fun TripodOpItem.fillOf(level: TripodLevel): Double = when (level) {
    TripodLevel.LOW -> fillLow
    TripodLevel.MEDIUM -> fillMed
    TripodLevel.HIGH -> fillHigh
}
