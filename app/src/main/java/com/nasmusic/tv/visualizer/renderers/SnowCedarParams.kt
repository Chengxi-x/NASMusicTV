package com.nasmusic.tv.visualizer.renderers

/**
 * E47「雪松」的参数表 —— `docs/e47-snow-cedar-preview.html` 里 `P` + `TIERS` 的 1:1 移植。
 *
 * ## 为什么单独一个文件、为什么全是 `Double`
 * 原型是浏览器里调出来的，**每一个数都是定稿值**；Kotlin 侧任何"顺手改成 Float"都会让
 * `buildTree` 的 `Math.round(len / segLenPx)`、`clamp` 边界这类判断在临界值上换边 ⇒ 整棵树的
 * 分段数变化 ⇒ 与原型对账的黄金测试（[SnowCedarGoldenTest]）当场红。所以：
 * **参数与纯数学一律 `Double`**，只在落到 `Canvas`/`Paint` 的那一行转 `Float`。
 *
 * ## ⛔ 档位覆盖表必须逐键照抄 `TIERS`
 * [applyLevel] 只写原型那张表里**出现过的键**（11 条）。"顺手"多覆盖一条（例如把
 * `tree.segPx` 也按档位改）会改变子段数 ⇒ 树形变化 ⇒ 铺屏账全被污染（P28 的同一条教训：
 * ⛔ 改了别的维度就没法把结果归因给本次改动）。
 */
internal enum class SnowLevel { LOW, MEDIUM, HIGH }

/**
 * 构图锚点（`P.comp`）。全部是**画幅 fraction**，除 `crownW`（冠幅 ÷ 树高，无量纲）。
 *
 * ⛔ 这里**没有** `personY` —— 所有者 2026-10-14 裁决「人要跟树在一个水平线上」，
 * 脚线由 [SnowCedarLayout] 里 `personY = treeBaseY` 直接给出。留一个字段就是留一条会漂移的路
 * （原型自检 T12 判的第二条正是"这个字段不存在"）。
 */
internal class SnowComp {
    var horizon = 0.600        // 地平线 ×H
    var treeX = 0.745          // 树干基 ×W（中右侧）
    var treeBaseY = 0.735      // 树干基 ×H（比地平线近 ⇒ 站在雪原上）
    var crownTop = 0.115       // 冠顶 ×H
    var crownW = 1.16          // 冠幅 ÷ 树高
    var sunX = 0.265
    var sunY = 0.205

    /** ⚠️ 定稿后**没有滑杆**的两个数（原型 §十三）：要改只能改代码，不是运行时参数。 */
    var sunR = 0.200
    var sunStrength = 0.210

    /** 站定在树冠下、树干左侧，朝右面向树。同线 ⇒ 同人同深度 ⇒ 1.75 m 人 ÷ 12 m 树 ≈ 0.090H。 */
    var personH = 0.090
    var personX = 0.702
}

/** 大树生成参数（`P.tree`）—— [SnowCedarTree.buildTree] 的入参本体。 */
internal class SnowTreeP {
    /** ⭐ 全局固定种子（所有者裁决）：换歌/重进都同一棵树，⛔ 逐帧重掷。 */
    var seed = 20261014
    var depth = 7
    var lenDecay = 0.780
    var spreadDeg = 60.0
    var jitter = 0.34
    var bendK = 0.14
    var droop = 0.150

    /** 子段目标**像素**长（÷ 树高换成单位系，见 [SnowCedarTree.treeOpt]）。 */
    var segPx = 26.0
    var trunkW = 0.068
    var wTaper = 0.72
    var maxSegs = 660
    var childP3 = 0.22

    /** 承雪门槛：枝段水平度 `|ux|` ≥ 它才画雪。 */
    var snowHMin = 0.420

    /** 承雪厚度系数（⚠️ 水平枝的雪幔要**比枝条本身还粗**，旧口径读成"枝上一根白线"）。 */
    var snowK = 1.10

    /** ⭐ 厚度**绝对下限**：`sw ∝ w` 时粗细枝的"雪/枝"比恒等 ⇒ 细枝永远埋不进雪，上冠长出一蓬黑铁丝。 */
    var snowMin = 0.0042
    var clump = 1.05

    /** ⛔ 运行时恒为 +1。`-1` 只服务于原型自检 T3 的负样本（把承雪面从 `|ux|` 换成 `|uy|`）。 */
    var snowSign = 1
}

/** 风（`P.wind`）—— ⛔ 时间基线常驻，音频只做增益调制。 */
internal class SnowWindP {
    var base = 0.026
    var gustAmp = 0.72
    var gustHz1 = 1.0 / 17.0
    var gustHz2 = 1.0 / 41.0
    var swayAmp = 0.0072
    var swayHz = 1.0 / 9.0

    /** 大树网格行数（Kotlin 侧 = `drawBitmapMesh` 的行数；同行各列偏移恒等）。 */
    var rows = 8
}

/** 漫天大雪（`P.snow`）。 */
internal class SnowSnowP {
    var flakes = 540
    var fall = 0.560

    /** 近层拖尾长度 ÷ **雪粒直径**（⛔ 不是秒数 —— 旧口径按"速度 × 秒"会拉成 10×30 的药丸）。 */
    var streak = 0.42

    /** 逐粒半径抖动幅度（dot/bokeh 层靠它拿到"同层大小不一"；streak 层不用 ⇒ 拖尾等宽）。 */
    var rVar = 0.42
}

/**
 * 肩上积雪（`P.person`）—— 逐帧唯一会变化的人物外观，⛔ 没有任何位移/步态参数。
 *
 * ⚠️ 三个数是**配出来的**，不是推出来的：`gust` 是 17 s / 41 s 两个正弦的和，约四成时间都在
 * 0.6 以上。阈值取 0.55 + 速率 2.6 时抖落项 `sh²·rate` 全程压过累积项 `1/dustSecs`
 * ⇒ 肩雪恒 0，"雪落肩"根本看不见（原型 t=40 实测 dust=0）。现取 0.72 / 1.4 ⇒ 平时积得住、大阵风才抖。
 */
internal class SnowPersonP {
    var dustSecs = 14.0
    var shakeAt = 0.72
    var shakeRate = 1.4
}

/** 音频映射（`P.audio`）—— 纯氛围克制（E45/E46 同款），⛔ 不吃 `spectrum`。 */
internal class SnowAudioP {
    var kWind = 0.80      // × bass   → 平均风速
    var kGust = 0.90      // × energy → 阵风深度
    var kSun = 0.60       // × treble → 日晕提亮
    var smooth = 0.085    // AudioSmoother 的 α（`k = 1 − exp(−α·60·dt)`）
}

/** 一次性烘焙 / 图层参数（`P.fx`）。 */
internal class SnowFxP {
    var vignette = 0.34

    /** ⚠️ 档位 0（LOW）关掉颗粒，但**暗角与颗粒烘在同一张 grade 位图里**（§十一(d)），
     *  所以本字段只决定位图像素，逐帧铺屏恒为 1.00 屏。 */
    var grain = 0.055
    var cloudBands = 2
    var veils = 3
    var pines = 18
    var hazeRows = 1

    /** 天幕噪点强度（`fx.skyTex`）：烘焙期自叠一层低对比噪声，⛔ 逐帧颗粒贴图。 */
    var skyTex = 0.030

    /** 冰晶闪光的候选点总数（每帧只有少数在闪，见 [SnowCedarSnow.drawGlints]）。 */
    var glint = 56
}

/**
 * E47 的参数总表。`boot()` 期一次构造，逐帧只读。
 *
 * ⚠️ 默认值 = 原型 `P` 的**初值**（不是任何一档）：原型进页面后由 `applyTier(P.tier)` 覆盖成
 * MED，Kotlin 侧则由首帧的 `fx.level` 覆盖（[applyLevel]）。保留 `P` 初值只为对账时能复现
 * "还没切档"的那个状态。
 */
internal class SnowCedarP {
    val comp = SnowComp()
    val tree = SnowTreeP()
    val wind = SnowWindP()
    val snow = SnowSnowP()
    val person = SnowPersonP()
    val audio = SnowAudioP()
    val fx = SnowFxP()

    /**
     * 原型 `TIERS[t]` 的 1:1 移植 —— **11 个键，一条不多一条不少**。
     *
     * ⚠️ 原型注释写着「maxSegs 一律取该 depth 的完整树不截断」，**实测不成立**：
     * 1600×900 上 MED/HIGH 两档的段数会撞上 1200/2800 上限（基线 `truncated` = 1），
     * 而且撞不撞与画幅有关（段数随 `segLenPx` 变 ⇒ 大屏更容易撞）。
     * 所有者已按**这个形状**验收了效果 ⇒ ⛔ 不要"顺手把 maxSegs 调大以消除截断"，
     * 那是换一棵树、并让 §三 的位图尺寸与铺屏账全部作废。截断状态由
     * [SnowCedarGoldenTest] 按档逐位钉住。
     */
    fun applyLevel(level: SnowLevel) {
        when (level) {
            SnowLevel.LOW -> {
                tree.depth = 5; tree.maxSegs = 460; wind.rows = 4
                snow.flakes = 380; snow.rVar = 0.34
                fx.cloudBands = 1; fx.veils = 2; fx.grain = 0.0
                fx.pines = 11; fx.vignette = 0.30; fx.glint = 34
            }
            SnowLevel.MEDIUM -> {
                tree.depth = 7; tree.maxSegs = 1200; wind.rows = 8
                snow.flakes = 820; snow.rVar = 0.42
                fx.cloudBands = 2; fx.veils = 3; fx.grain = 0.055
                fx.pines = 18; fx.vignette = 0.34; fx.glint = 56
            }
            SnowLevel.HIGH -> {
                tree.depth = 8; tree.maxSegs = 2800; wind.rows = 12
                snow.flakes = 1260; snow.rVar = 0.46
                fx.cloudBands = 2; fx.veils = 3; fx.grain = 0.075
                fx.pines = 26; fx.vignette = 0.34; fx.glint = 78
            }
        }
    }
}
