package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * E48「大漠 · 孤烟」K2 —— 原型 §0/§1/§2 的 1:1 移植：
 * 确定性数学（mulberry32 / hash2 / vnoise / fbm / 调色混色）+ `P` 参数表 + `TIERS` +
 * `buildDunes()` 整张脊线表生成 + `layout()` 的逐层派生量 + `ridgeMix()` / `crest()` /
 * `groupTopCrest()` 这条**六处共用的脊线口径**。
 *
 * ## 三条不可动的结构纪律
 *
 * 1. **`crest()` 只有这一个实现**。原型 §11.3 K2 列出六个消费方 —— T14（每层露出）/
 *    T12（远脊切日盘）/ 沙丘烘焙 `bakeRune` / `layout` 的极值扫描 / 驼队脚线
 *    `camelFootY` / 烟根锚点 `srcRidgeY` —— 全部走本文件的 [DesertLayout.crest]
 *    （⛔ 谁都不许自己再写一份采样公式）。闸门见 `DesertSmokeGateTest` 的
 *    「crest 口径唯一性」源码扫描：它要求**错误的那条路不存在**（E47 T12 同款形态）。
 * 2. **换噪声实现 ⇒ 四处口径全歪**：`ridgeAt` / `ridgeMix` 是 `crest()` 与
 *    `ridgeMean()` 共用的脊形函数；换掉它不只是换形状，`d.rmean`（脊线均值）会随之漂
 *    ⇒ `d.y` 不再是"平均脊线位置"。⛔ 不许按"更平滑"的直觉替换。
 * 3. **参数与纯数学一律 `Double`**（与 E47 同）：`Math.round` / `clamp` 边界上的判断
 *    在临界值换边就会改掉整张沙脊表 ⇒ 与原型对账的黄金判据当场红。
 *    只在落到 `Canvas` 的那一行转 `Float`。
 */
internal object DesertMath {

    const val PI_D = 3.141592653589793
    const val TAU = 2.0 * PI_D
    const val D2R = PI_D / 180.0

    fun clamp(v: Double, a: Double, b: Double): Double = if (v < a) a else if (v > b) b else v

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** JS `Math.round`：`floor(v + 0.5)`（⛔ 不是银行家舍入）。 */
    fun jsRound(v: Double): Double = floor(v + 0.5)

    /** mulberry32（原型 §0）。⚠️ 每处使用都**新建一个实例** ⇒ 各条流互不干扰。 */
    fun rng(seed: Int): DesertRng = DesertRng(seed)

    class DesertRng(initial: Int) {
        /** JS 闭包里的 `a` 是**被加数**而非输出：第 1 次 `next()` 用 `seed + 0x6D2B79F5`。 */
        private var a: Int = initial

        fun next(): Double {
            a += 0x6d2b79f5
            // Kotlin 的 Int 乘法天然按 32 位回绕 ⇒ 与 `Math.imul` 同；`|0` 同理。
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** 二维整数哈希（⛔ 不占 RNG 流 —— 装饰量占一次就把整条脊线表往后推一位）。 */
    fun hash2(i: Int, j: Int): Double {
        var h = (i * 374761393) + (j * 668265263)
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    /** 值噪声：格点 [hash2] + smoothstep 双线性。 */
    fun vnoise(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        val u = xf * xf * (3.0 - 2.0 * xf)
        val v = yf * yf * (3.0 - 2.0 * yf)
        return lerp(
            lerp(hash2(xi, yi), hash2(xi + 1, yi), u),
            lerp(hash2(xi, yi + 1), hash2(xi + 1, yi + 1), u),
            v,
        )
    }

    /** 分形叠加。⚠️ 末行的 `/ n` 归一**不能省**。 */
    fun fbm(x: Double, y: Double, oct: Int, gain: Double, lac: Double): Double {
        var s = 0.0
        var a = 0.5
        var f = 1.0
        var n = 0.0
        var i = 0
        while (i < oct) {
            n += a
            s += a * vnoise(x * f, y * f)
            a *= gain
            f *= if (lac == 0.0) 2.0 else lac
            i++
        }
        return s / n
    }

    // ─────────────────────── 调色（⛔ 逐帧调用，只在烘焙期与 layout 里） ───────────────────────

    /** `#rrggbb`（以 `0xRRGGBB` 的 Int 表达）→ `[r,g,b]`（原型 `hex2rgb`）。 */
    fun hex2rgb(h: Int): IntArray = intArrayOf((h shr 16) and 255, (h shr 8) and 255, h and 255)

    /** 两个 hex 之间线性插值 ⇒ hex（原型 `mixHex`）。 */
    fun mixHex(h1: Int, h2: Int, t: Double): Int {
        val a = hex2rgb(h1)
        val b = hex2rgb(h2)
        val r = jsRound(lerp(a[0].toDouble(), b[0].toDouble(), t)).toInt()
        val g = jsRound(lerp(a[1].toDouble(), b[1].toDouble(), t)).toInt()
        val bl = jsRound(lerp(a[2].toDouble(), b[2].toDouble(), t)).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    /** 两个 `[r,g,b]` 三元组之间线性插值 ⇒ 同形态三元组（原型 `mixRgb`，阳/阴坡色带用）。 */
    fun mixRgb(c1: IntArray, c2: IntArray, t: Double): IntArray = intArrayOf(
        jsRound(lerp(c1[0].toDouble(), c2[0].toDouble(), t)).toInt(),
        jsRound(lerp(c1[1].toDouble(), c2[1].toDouble(), t)).toInt(),
        jsRound(lerp(c1[2].toDouble(), c2[2].toDouble(), t)).toInt(),
    )

    /** 原型 `Math.hypot`（本效果只作用于小量 ⇒ 直接勾股）。 */
    fun hypot(a: Double, b: Double): Double = sqrt(a * a + b * b)

    fun sinD(v: Double): Double = sin(v)

    fun absD(v: Double): Double = abs(v)
}

/** 画质档位 → 原型 `TIERS` 的三档（`applyLevel` 只写这张表里出现过的键）。 */
internal enum class DesertLevel { LOW, MEDIUM, HIGH }

/** 构图锚点（`P.comp`）—— 全部是**画幅 fraction**（原型：参考图 1 的实测定位）。 */
internal class DesertComp {
    var sunX = 0.391
    var sunY = 0.340

    /** ⭐ H2：R 0.112→0.150（切日比例 25.8%，T12 窗口 8–45% 余量很大）。 */
    var sunR = 0.150

    /** 最远一层沙脊的基准 y ×H（日盘下缘由**这一层**切掉 ⇒ T12 判）。 */
    var duneTop = 0.412
    var duneBot = 0.960

    /** 烟柱所在的 ×W（三轮裁决：⛔ 烽燧 ⇒ 烟由沙脊线之后升起）。 */
    var smokeX = 0.615

    /** 烟源所在层的深度分数（0=最远…1=最近 ⇒ 0.72 落在倒数第二层）。 */
    var smokeSrc = 0.72

    /** 烟柱顶端 ×H（B1：0.090→0.055，把柱加长 ~26 px）。 */
    var smokeTop = 0.055

    /** 驼队脚线所在层的深度分数。 */
    var carLayer = 0.55
}

/** 沙丘（`P.dune`）。 */
internal class DesertDuneP {
    var seed = 20261014
    var n = 7
    var sub = 2
    var stack = 1.35
    var ovl = 1.05
    var gapK = 1.00
    var litK = 1.00
    var shadeK = 1.00
    var ripples = 11
}

/** 落日（`P.sun`）—— 日盘半径与日晕半径是**同一个预算里的两个杠杆**。 */
internal class DesertSunP {
    var halo0 = 1.13
    var halo1 = 1.38
    var halo2 = 1.72
    var scatter = 0.120
    var bloom = 1.00

    /** 掩膜归零半径（×R）= gate ⑦「盘缘过渡宽度」的旋钮；起坡固定在 0.94R，⛔ >1.20。 */
    var softK = 1.06
}

/** 孤烟（`P.smoke`）。 */
internal class DesertSmokeP {
    var seed = 20261014
    var puffs = 20
    var r0 = 0.0150
    var grow = 0.50
    var elong = 2.85
    var amp = 0.0082
    var swayHz1 = 1.0 / 19.0
    var swayHz2 = 1.0 / 7.3
    var swayLag1 = 0.80
    var swayLag2 = 1.45
    var phJit = 0.20
    var rise = 0.0550

    /** B1：2.00→1.45（峰值浓度 0.187→0.145）。⛔ T7 **不判**峰值浓度。 */
    var alphaK = 1.45
    var haloMul = 1.35
    var haloA = 0.30

    /** ⚠️ 原型 `P.smoke.haloRise`：当前 `drawSmoke` 不再消费（第七轮删芯丝线机制后只剩记录）。 */
    var haloRise = 0.62
    var jitK = 1.00
    var rimOff = 0.95
    var rimMax = 0.62
    var rimA = 0.62
    var variants = 6
}

/** 驼队（`P.caravan`）——⛔ 没有 `tex` 字段：K 端只落**出货档 `TEX`**（所有者第十一轮裁决）。 */
internal class DesertCaravanP {
    var seed = 20261014
    var pace = 0.0190

    /** B2：0.0300→0.0380（可见腿由恒 0 转正成 1.6~7.1 px）。 */
    var h = 0.0380
    var gap = 0.034
    var camels = 6

    /** 波间隔（空窗）秒数区间 —— T10 判 [idle0, idle1]。 */
    var idle0 = 40
    var idle1 = 80

    /** B2：0.520→0.345。⛔ T9 露出比区间 `[0.40,0.85]` **未动**。 */
    var occl = 0.345
    var bobHz = 1.0 / 3.4

    /** 体型尺寸（水平）：⚠️ 两档共用 `cw = round(1.22·lenK·h)` ⇒ 换它就是换 blit 尺寸。 */
    var lenK = 0.92

    /** 峰在体型里的比例（垂直）—— ⭐ 只对 `PROC` 回退档有意义（本端口未移植 PROC）。 */
    var humpK = 0.38
}

/** 风（`P.wind`）—— ⛔ 时间基线常驻，音频只做增益调制。 */
internal class DesertWindP {
    var base = 0.012
    var gustAmp = 0.55
    var gustHz1 = 1.0 / 17.0
    var gustHz2 = 1.0 / 41.0
}

/** 浮沙与调色（`P.fx`）。 */
internal class DesertFxP {
    var haloLayers = 3
    var veils = 2
    var haze = 0.30

    /** ⚠️ 档位 0（LOW）关掉颗粒，但**暗角与颗粒烘在同一张 grade 位图里** ⇒ 逐帧铺屏恒 1.00 屏。 */
    var vignette = 0.10
    var grain = 0.030

    /** 天幕 tonal 条纹强度（α ≤ skyTex，⛔ 云）。 */
    var skyTex = 0.022
}

/** 音频映射（`P.audio`）—— 纯氛围克制（⛔ 频谱条 / ⛔ 节拍闪光）。 */
internal class DesertAudioP {
    var kRise = 0.90
    var kSway = 0.85
    var kSun = 0.60
    var kSand = 1.10
    var kHaze = 0.50

    /** AudioSmoother 的 α（原型 `k = 1 − exp(−smooth·60·dt)`）。 */
    var smooth = 0.085
}

/**
 * E48 的参数总表（原型 `P` + `TIERS`）。
 *
 * ⚠️ 默认值 = 原型 `P` 的**初值**；Kotlin 侧由首帧的 `fx.level` 覆盖（[applyLevel]）。
 * ⛔ [applyLevel] 只写原型 `TIERS` 里出现过的 **9 个键**（"顺手"多覆盖一条就会改变
 * 对账口径，P16/P28 同族）。
 */
internal class DesertP {
    val comp = DesertComp()
    val dune = DesertDuneP()
    val sun = DesertSunP()
    val smoke = DesertSmokeP()
    val caravan = DesertCaravanP()
    val wind = DesertWindP()
    val fx = DesertFxP()
    val audio = DesertAudioP()

    fun applyLevel(level: DesertLevel) {
        when (level) {
            DesertLevel.LOW -> {
                smoke.puffs = 14; caravan.camels = 4; dune.ripples = 6
                dune.n = 5; dune.sub = 2
                fx.grain = 0.0; fx.veils = 1; fx.haloLayers = 1; smoke.jitK = 0.85
            }
            DesertLevel.MEDIUM -> {
                smoke.puffs = 20; caravan.camels = 6; dune.ripples = 11
                dune.n = 7; dune.sub = 2
                fx.grain = 0.030; fx.veils = 2; fx.haloLayers = 2; smoke.jitK = 1.00
            }
            DesertLevel.HIGH -> {
                smoke.puffs = 26; caravan.camels = 7; dune.ripples = 16
                dune.n = 9; dune.sub = 2
                fx.grain = 0.042; fx.veils = 2; fx.haloLayers = 3; smoke.jitK = 1.15
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  脊线表（原型 §2：`DUNES` + `buildDunes()`）
// ══════════════════════════════════════════════════════════════════════════

/**
 * 深度分数 t → 该层的调子。锚点全部取**参考图 1 的实测主色**（`e48_ref_palette.py`）。
 *
 * ⭐ 二轮实测对账（ref 竖向 6 带：天顶 L .179 → 沙带 L .080/.083/.099）：
 * 参考图的沙丘**本体是暗棕**（#6d4b34 占 21.8%），亮的 #c57a4e（L .263）只占 8.7%
 * ⇒ 它是**脊线上的一条高光刃**，⛔ 当整层的中间调。阴坡 ⛔ 紫色（旧表的蓝紫底是
 * "太深"的元凶之一）。
 */
internal object DesertToneFar {
    const val TOP = 0xc98052
    const val MID = 0x855538
    const val BOT = 0x704a33
    val LIT = intArrayOf(255, 196, 128)
    val SHADE = intArrayOf(92, 58, 40)
    val HAZE = intArrayOf(255, 186, 120)
}

internal object DesertToneNear {
    const val TOP = 0xa96a41
    const val MID = 0x67452f
    const val BOT = 0x5c3e2a
    val LIT = intArrayOf(255, 166, 92)
    val SHADE = intArrayOf(52, 34, 24)
    val HAZE = intArrayOf(255, 170, 100)
}

/**
 * 一层的调子（原型 `d.tone`）—— `top/mid/bot` 是体块渐变用的 hex，
 * `lit/shade/haze` 是 `rgba(...)` 用的 `[r,g,b]` 三元组。
 */
internal class DesertTone(
    val top: Int, val mid: Int, val bot: Int,
    val lit: IntArray, val shade: IntArray, val haze: IntArray,
) {
    /** `tone.top` 的三元组形态（脊刃亮边用；原型 `d.tone.topRgb`）。 */
    val topRgb: IntArray = DesertMath.hex2rgb(top)
}

/**
 * 一道脊线（原型 `DUNES[i]`）—— `n` 张画布 × 每张 `sub` 道脊 ⇒ 共 R 道，
 * `g` 是它所属的**画布**索引。
 *
 * ⭐ 为什么必须分组成画布（三轮裁决 ② 的正解）：逐帧成本 = 画布数 + 画布总高度，
 * 而**同一张画布内**多叠几道脊既不增加 blit，也不增加总高度（脊与脊的互相遮挡发生在
 * 位图内部）。⛔ 各脊同频 = 读成"一条脊的复制" ⇒ f1/f2/相位逐脊独立。
 */
internal class DesertDune {
    var i = 0

    /** 深度分数：0 = 最远 … 1 = 最近。 */
    var t = 0.0
    var g = 0
    var sub = 1
    var nG = 1
    var f1 = 0.0
    var f2 = 0.0
    var s = 0.0
    var ph = 0.0

    /** 尖度 = ridged 噪声的 `p` 次幂 ⇒ **只决定"脊有多窄"**（3.4/2.1，⛔ 4.2 的针状锯齿）。 */
    var sharp = 0.0

    /** 近景混向平滑 fbm 丘的比例（0.78·t^0.85 ⇒ 远层纯 ridged、近层 78% 圆丘）。 */
    var roll = 0.0

    /** 该层脊形的均值（数值积分，K=96 中点采样）⇒ `crest()` 减掉它，`y` 才是平均脊线位置。 */
    var rmean = 0.0

    lateinit var tone: DesertTone

    /** 基准线 y（像素，`layout` 里算）。 */
    var y = 0.0

    /** 振幅（**分数**！`crest()` 里是 `y − H·amp·(…)`）。 */
    var amp = 0.0

    /** 第二八度振幅 = `amp × 0.30`。 */
    var amp2 = 0.0

    /** 烘焙列数（远层本身平，粗步进读不出来，⛔ 逐列成本均分）。 */
    var cols = 200

    /** 阳/阴坡着色带系数 = **整条山坡**（该列脊线→本层底）。 */
    var bandK = 0.0

    /** 脊线上方的暖雾带高度（远层厚、近层只留 6 px 余量）。 */
    var hazePad = 0.0

    /** ③ 大尺度调子的团数（只给最近三张画布的领脊）。 */
    var swales = 0

    /** ④ 风成波纹开关（同上：只给最近三张画布的领脊；条数读 `P.dune.ripples`）。 */
    var rippleOn = false

    /** 极值（像素；`layout` 里按 `W/400` 步长扫出来）。 */
    var mn = 0.0
    var mx = 0.0
}

/**
 * E48 的布局（原型 `L` + `layout()`）：**画幅像素**尺度，
 * 进效果首帧与画幅变化时重算一次。
 *
 * ⚠️ 本类同时是 `crest()` 的**唯一宿主** —— 烘焙、遮挡、自检三处共用（见文件头纪律 1）。
 */
internal class DesertLayout {

    var w = 0.0
    var h = 0.0

    /** `S = H/540`（原型的 `S`）。 */
    var s = 1.0

    val dunes = ArrayList<DesertDune>()

    var sunX = 0.0
    var sunY = 0.0
    var sunR = 0.0
    var smokeTopY = 0.0

    /** 全沙海**共用**的亮度阶梯上端 = 地平线（`duneTop`，第一道脊的基准线）。 */
    var horizonY = 0.0

    /** 下端 = 画面底再放 2%（最后一层的体块吃满）。 */
    var duneFieldBot = 0.0

    var smokeX = 0.0
    var srcRidge = 0
    var srcLayer = 0
    var carRidge = 0
    var carLayer = 0

    /** 烟源层画布的**顶边**（= 遮挡线的位置）与烟柱基点分开存。 */
    var srcRidgeY = 0.0

    /** B1b：基点**抬到遮挡线之上**一整个 puff 半径（⛔ 不画烽燧）。 */
    var smokeBaseY = 0.0
    var smokeH = 0.0

    /** 有效层数（= `DUNES.length`）；`0` = 还没跑过 [layout]。 */
    val ridgeCount: Int get() = dunes.size

    // ─────────────────────── 脊形（原型 §2 的三个纯函数） ───────────────────────

    /** 脊状噪声（ridged multifractal 的一维版）：`1−|2f−1|` 再取 `p` 次幂 ⇒ **峰尖、谷平**。 */
    fun ridgeAt(x: Double, y: Double, p: Double): Double {
        val f = DesertMath.fbm(x, y, 3, 0.5, 2.0)
        return pow1m(1.0 - DesertMath.absD(2.0 * f - 1.0), p)
    }

    private fun pow1m(v: Double, p: Double): Double {
        if (v <= 0.0) return 0.0
        return v.pow(p)
    }

    /**
     * 第 i 层在归一化横坐标 u 处的脊形（0…1）。两个八度（0.78 + 0.22）：大脊套小脊。
     *
     * ⭐ 第二八度**必须比主八度圆**（`sharp·0.62`、频率 2.1 倍而非 2.7 倍）；
     * ⭐⭐ `roll` 把整条脊形**混向平滑 fbm 丘**：远层纯 ridged = 锯齿剪影，
     * 近层 78% 圆丘 = 一颗一颗凸顶大沙丘。两条分支共用同一套采样坐标。
     */
    fun ridgeMix(c: DesertDune, u: Double): Double {
        val a = u * c.f1 + c.s
        val b = u * c.f1 * 2.1 + c.s * 1.9
        val rg = 0.78 * ridgeAt(a, c.s + 3.3, c.sharp) +
            0.22 * ridgeAt(b, c.s + 7.1, c.sharp * 0.62)
        val hl = 0.78 * DesertMath.fbm(a, c.s + 3.3, 3, 0.5, 2.0) +
            0.22 * DesertMath.fbm(b, c.s + 7.1, 2, 0.5, 2.0)
        return DesertMath.lerp(rg, hl, c.roll)
    }

    /** 数值积分出该层脊形的均值（K=96 **中点**采样，⛔ 端点）。只依赖 f1/s/sharp/roll。 */
    fun ridgeMean(c: DesertDune): Double {
        val k = 96
        var sum = 0.0
        var i = 0
        while (i < k) {
            sum += ridgeMix(c, (i + 0.5) / k)
            i++
        }
        return sum / k
    }

    /**
     * 第 i 层沙脊在像素 x 处的 y（解析、确定性 ⇒ 烘焙、遮挡、自检共用同一口径）。
     *
     * ⚠️ 振幅与极值住在 [DesertDune]（[layout] 里算），本函数只读 ⇒ 任何调用点都拿到同一个口径。
     */
    fun crest(i: Int, x: Double): Double {
        if (i < 0 || i >= dunes.size) return 0.0
        val c = dunes[i]
        val u = x / (if (w > 0.0) w else 1.0)
        return c.y - h * (c.amp * (ridgeMix(c, u) - c.rmean) + c.amp2 * sin(u * c.f2 + c.ph))
    }

    /** 深度分数 → **脊线索引**（最近一道 = R−1）；四舍五入 ⇒ 滑杆读回来的就是真正生效的那道脊。 */
    fun layerOf(fr: Double): Int {
        val r = dunes.size
        if (r <= 0) return 0
        val v = DesertMath.jsRound(fr * (r - 1)).toInt()
        return if (v < 0) 0 else if (v > r - 1) r - 1 else v
    }

    /** 画布 g 的成员脊线索引区间（脊线按远→近连续分组 ⇒ 组内遮挡次序天然正确）。 */
    fun groupRidges(g: Int): IntArray {
        val r = dunes.size
        if (r == 0) return intArrayOf(0, -1)
        val sub = dunes[0].sub
        val a = g * sub
        val b = minOf(r - 1, a + sub - 1)
        return intArrayOf(a, b)
    }

    /**
     * 画布 g 在像素 x 处**看得见的顶边** = 组内各脊的最高点。
     *
     * 烟根与驼腿必须落在它之下，否则"后画的画布吃掉前景物"这条遮挡链会失效
     * （分组后 ⛔ 再用单条脊线当顶边）。
     */
    fun groupTopCrest(g: Int, x: Double): Double {
        val ab = groupRidges(g)
        var mn = 1e9
        var i = ab[0]
        while (i <= ab[1]) {
            val y = crest(i, x)
            if (y < mn) mn = y
            i++
        }
        return if (mn >= 1e9) 0.0 else mn
    }

    // ─────────────────────── 表生成 + 布局（原型 §2） ───────────────────────

    /** 原型 `buildDunes()`：`n × sub` 道脊的整张表生成（⛔ 手写四条）。 */
    fun buildDunes(p: DesertP) {
        dunes.clear()
        val n = maxOf(3, DesertMath.jsRound(p.dune.n.toDouble()).toInt())
        val sub = maxOf(1, DesertMath.jsRound(p.dune.sub.toDouble()).toInt())
        val r = n * sub
        val seed = p.dune.seed
        var i = 0
        while (i < r) {
            val t = if (r == 1) 1.0 else i.toDouble() / (r - 1)
            val d = DesertDune()
            d.i = i
            d.t = t
            d.g = i / sub
            d.sub = sub
            d.nG = n
            d.f1 = DesertMath.lerp(11.0, 1.30, t)
            d.f2 = DesertMath.lerp(17.0, 2.9, t)
            d.s = DesertMath.hash2(i + 3, seed and 0xffff) * 31.0
            d.ph = DesertMath.hash2(i + 11, (seed ushr 3) and 0xffff) * DesertMath.TAU
            d.sharp = DesertMath.lerp(3.4, 2.1, t)
            d.roll = 0.78 * t.pow(0.85)
            d.tone = DesertTone(
                top = DesertMath.mixHex(DesertToneFar.TOP, DesertToneNear.TOP, t),
                mid = DesertMath.mixHex(DesertToneFar.MID, DesertToneNear.MID, t),
                bot = DesertMath.mixHex(DesertToneFar.BOT, DesertToneNear.BOT, t),
                lit = DesertMath.mixRgb(DesertToneFar.LIT, DesertToneNear.LIT, t),
                shade = DesertMath.mixRgb(DesertToneFar.SHADE, DesertToneNear.SHADE, t),
                haze = DesertMath.mixRgb(DesertToneFar.HAZE, DesertToneNear.HAZE, t),
            )
            d.rmean = ridgeMean(d)
            dunes.add(d)
            i++
        }
    }

    /**
     * 原型 `layout(w, h)` —— **改几何的量**（层数/间距/振幅/锚点）全在这里。
     *
     * ⚠️ `amp` 是**分数**（`gap / H × ovl`）⇒ `crest()` 里乘回 `H`；
     * 直接把像素间距塞进来 = 起伏被放大 H 倍（E48 三轮首踩，mn/mx 冲到 ±3000）。
     */
    fun layout(wIn: Double, hIn: Double, p: DesertP) {
        w = wIn
        h = hIn
        s = hIn / 540.0
        sunX = p.comp.sunX * w
        sunY = p.comp.sunY * h
        sunR = p.comp.sunR * h
        smokeTopY = p.comp.smokeTop * h
        buildDunes(p)
        horizonY = p.comp.duneTop * h
        duneFieldBot = h * 1.02
        val r = dunes.size
        val ng = dunes[0].nG
        val e = maxOf(1.0, p.dune.stack)

        // ① 基准线：`pow(t, e)` 分布 ⇒ e>1 时**远密近疏**（透视下沙丘场的样子）。
        var i = 0
        while (i < r) {
            val d = dunes[i]
            d.y = DesertMath.lerp(p.comp.duneTop, p.comp.duneBot, d.t.pow(e) * p.dune.gapK) * h
            i++
        }
        // ② 振幅 = `ovl × 到下一道脊的间距` ⇒ 起伏与间距**同源**。
        i = 0
        while (i < r) {
            val d = dunes[i]
            val gap = if (i < r - 1) dunes[i + 1].y - d.y else d.y - dunes[i - 1].y
            d.amp = maxOf(0.004, (gap / h) * p.dune.ovl)
            d.amp2 = d.amp * 0.30
            d.cols = DesertMath.jsRound(DesertMath.lerp(200.0, 500.0, d.t)).toInt()
            d.bandK = DesertMath.lerp(0.80, 0.95, d.t)
            d.hazePad = h * (0.028 * (1.0 - d.t) + 0.006)
            // ③ 大尺度调子 + 风成波纹只给**最近三张画布的最前那道脊**。
            val lead = d.g >= ng - 3 && i == groupRidges(d.g)[1]
            d.swales = if (lead) DesertMath.jsRound(3.0 + d.t * 4.0).toInt() else 0
            d.rippleOn = lead
            i++
        }
        // ④ 极值（`mn/mx`）：布局期一次扫完 ⇒ 烘焙不再各自扫。
        i = 0
        while (i < r) {
            val d = dunes[i]
            var mn = 1e9
            var mx = -1e9
            val stp = maxOf(2.0, DesertMath.jsRound(w / 400.0))
            var x = 0.0
            while (x <= w) {
                val y = crest(i, x)
                if (y < mn) mn = y
                if (y > mx) mx = y
                x += stp
            }
            d.mn = mn
            d.mx = mx
            i++
        }
        smokeX = p.comp.smokeX * w
        srcRidge = layerOf(p.comp.smokeSrc)
        srcLayer = dunes[srcRidge].g
        carRidge = layerOf(p.comp.carLayer)
        carLayer = dunes[carRidge].g
        srcRidgeY = groupTopCrest(srcLayer, smokeX)
        smokeBaseY = srcRidgeY - maxOf(h * 0.008, p.smoke.r0 * h * 1.25)
        smokeH = maxOf(20.0, smokeBaseY - smokeTopY)
    }

    /**
     * 远脊切日盘的比例（原型 `sunCutFraction`；T12 判据）。
     *
     * `0` = 日盘整个露在天上（悬空圆盘）、`1` = 整个埋进远脊 ⇒ 目标区间 `[0.08, 0.45]`。
     * ⚠️ **只读 [sunY] / [sunR] / [crest] 这条唯一脊线口径**（⛔ 另抄一份采样公式）。
     */
    fun sunCutFraction(): Double {
        val bottom = sunY + sunR
        if (sunR <= 0.0) return 0.0
        return DesertMath.clamp((bottom - crest(0, sunX)) / (2.0 * sunR), 0.0, 1.0)
    }

    /**
     * 每层沙脊"在某处露出的最大高度" = `max_x ( min_{j>i} crest(j,x) − crest(i,x) )`
     * （原型 `duneExposures()`；≤0 ⇒ 该层被后面的层**整条盖掉** ⇒ 画了看不见）。
     * 最后一道没有东西盖它 ⇒ 记为恒可见。T14 的判据读它。
     */
    fun duneExposures(): DoubleArray {
        val n = dunes.size
        val e = DoubleArray(n)
        if (n == 0) return e
        e[n - 1] = 1e9
        val stp = maxOf(3.0, DesertMath.jsRound(w / 300.0))
        var x = 0.0
        while (x <= w) {
            var mn = 1e9
            var i = n - 1
            while (i >= 0) {
                val y = crest(i, x)
                if (i < n - 1) e[i] = maxOf(e[i], mn - y)
                if (y < mn) mn = y
                i--
            }
            x += stp
        }
        return e
    }
}
