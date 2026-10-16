package com.nasmusic.tv.visualizer.renderers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * E48「大漠 · 孤烟」K6（第十一轮改写）—— 驼队 = **时刻表 + 遮挡 + 一次 blit**，共五件事：
 *
 * ① [CamelTex] 点表（唯一再生源 `python docs/archive/verification/scripts/e48_trace_camel.py --write`，
 *    1 环 / 113 顶点 / 归一格 `IW 131 × IH 128`；⛔ 手改表内任何一个数字 = D24 重演）。
 *    绘制成 `Path` 后必须 **even-odd 填充**（对应 `fill('evenodd')`；⛔ 换 winding、
 *    ⛔ 把内外环拆成两次 fill）。
 * ② ⛔ **不把步态移植进 `TEX`**：「腿不动」是所有者选的轴（「程序画的腿实在是不好看」），
 *    「驼队不是静止的」由**行进**与 `bob` 承担；在 `TEX` 里加回 `GAIT` = 违抗裁决（D25）。
 *    ⇒ [CamelTex.GAIT] 只作为 `PROC` 回退档的历史常量留在表里，本端口的绘制路径**不消费它**。
 * ③ `PROC` 回退档**未移植**（待所有者裁）：本类只落出货档 `TEX`。
 * ④ 档无关部分照旧：时刻表 `starts[]` + `camelFootY`（**`groupTopCrest`，⛔ 不是单条脊**）
 *    / `camelVisible`（T9 判据）。
 * ⑤ 画布与锚**照 `PROC` 同式**（`cw=round(1.22·lenK·h)`、`ch=round(1.04h)`、`X=cw·0.46`、
 *    `soleY=ch−1`）+ `TEX_INK_OVER_H = 48/68` ⇒ **blit 尺寸逐位不变**（这就是换档不动预算的机制）。
 */
internal object CamelTex {

    const val IW = 131.0
    const val IH = 128.0

    /** 肩凹列 ⇒ 精灵的水平锚（PROC 的 `X` 同一条定义：两峰关于 `X` 对称）。 */
    const val SAD = 44.0

    /** 颈根列（头颈区右端）。 */
    const val NECK = 84.0

    /** 躯干列区间（`bodyL = k·(TORSO[1]−TORSO[0])/2`）。 */
    val TORSO = doubleArrayOf(7.0, 100.0)

    const val REARX = 25.0
    const val FRONTX = 52.0

    /** 腹线行（口径同 `e48_ref_camel.py`：行宽 >70% 最大行宽的最下一行）—— ⛔ 只剩记录用。 */
    const val BELLY = 79.0

    /** ⚠️ `TEXLEGS` 整档作废后的遗物：腿挂点，代码不再消费（⛔ 别当"腿会动"的证据）。 */
    val LEGS = doubleArrayOf(12.0, 24.0, 65.5, 80.0)
    val LEGW = doubleArrayOf(6.0, 6.0, 7.0, 6.0)

    /**
     * 轮廓环（flat `x,y,x,y,…`）。环填充一律 even-odd（孔洞环与外环同表）。
     *
     * ⚠️⚠️ 由脚本再生（`e48_trace_camel.py --write`）：裁 ink bbox → 面积平均缩到 ink 高 128 px
     * → 同墨口径描边（曼哈顿边界）→ Douglas-Peucker(eps=1) → 自实现 even-odd 回测。
     */
    val RINGS: Array<FloatArray> = arrayOf(
        floatArrayOf(
            1f, 76f, 3f, 72f, 3f, 56f, 8f, 48f, 10f, 40f, 20f, 28f, 20f, 25f, 23f, 20f,
            23f, 17f, 20f, 14f, 20f, 8f, 24f, 7f, 24f, 4f, 26f, 2f, 37f, 3f, 41f, 7f,
            42f, 21f, 45f, 23f, 47f, 22f, 48f, 17f, 45f, 14f, 46f, 6f, 53f, 1f, 64f, 3f,
            68f, 12f, 67f, 18f, 70f, 24f, 73f, 25f, 75f, 32f, 78f, 34f, 78f, 36f, 85f, 45f,
            88f, 45f, 90f, 43f, 91f, 34f, 94f, 29f, 94f, 23f, 97f, 19f, 97f, 16f, 105f, 8f,
            119f, 9f, 121f, 11f, 121f, 17f, 130f, 20f, 130f, 22f, 132f, 23f, 130f, 28f, 123f, 28f,
            121f, 32f, 118f, 33f, 116f, 36f, 116f, 54f, 109f, 71f, 99f, 81f, 92f, 81f, 89f, 85f,
            89f, 89f, 83f, 95f, 82f, 118f, 84f, 122f, 90f, 126f, 90f, 128f, 79f, 128f, 77f, 126f,
            78f, 106f, 74f, 90f, 70f, 93f, 69f, 103f, 66f, 107f, 66f, 121f, 69f, 125f, 72f, 126f,
            72f, 128f, 64f, 129f, 60f, 126f, 63f, 111f, 61f, 96f, 58f, 94f, 55f, 88f, 54f, 80f,
            43f, 77f, 42f, 75f, 38f, 77f, 37f, 81f, 34f, 83f, 34f, 86f, 27f, 94f, 27f, 114f, 31f,
            120f, 35f, 121f, 35f, 124f, 24f, 123f, 24f, 111f, 21f, 103f, 22f, 93f, 20f, 91f, 19f,
            94f, 15f, 97f, 15f, 115f, 17f, 120f, 20f, 123f, 22f, 123f, 23f, 126f, 12f, 126f, 11f,
            107f, 9f, 105f, 11f, 92f, 7f, 84f, 7f, 57f, 5f, 57f, 3f, 63f, 6f, 74f, 3f, 77f,
        ),
    )

    /** ⭐ 驼队精灵的**烘焙超采样倍率**（第九轮，为闭合 D21 的行量化台阶）。⛔ 拿它当"清晰度开关"。 */
    const val SS = 2

    /**
     * 步态帧数（`PROC` 回退档的量）。
     *
     * ⚠️ **TEX 档只有一帧**：贴图只有一个 mid-stride 姿势，⛔ 没有第二帧。
     * 本端口的 `TEX` 绘制路径⛔ 不按 `fr` 索引（那等于把步态加回来 = 违抗 D25）。
     */
    const val GAIT = 6

    /** 贴图 ink 高 ÷ 烘焙格 `h`：钉在 PROC 第十轮实测 48/68（`e48_anatomy_final.txt`）。 */
    const val INK_OVER_H = 48.0 / 68.0

    /** 剪影墨色（原型 `rgb(74,40,28)`；⛔ 各部件 alpha 必须恒 1.00 —— E47 P23 的教训）。 */
    const val INK = 0xFF4A281C.toInt()
}

/**
 * 驼队的**时刻表**（原型 `CAR` + `buildCaravan()`）：
 * 时间表由固定种子派生 ⇒ 同一次进入画面逐位一致；⛔ 逐帧随机。
 */
internal class DesertCaravanTable {

    var starts: DoubleArray = DoubleArray(0)
        private set
    var dir: IntArray = IntArray(0)
        private set

    /** 走完 `span` 个 W（含两侧余量）的秒数。 */
    var cross = 0.0
        private set

    /** 横穿跨度（×W，含两侧余量）。 */
    var span = 1.24
        private set

    /** 原型 `buildCaravan()`。 */
    fun build(p: DesertCaravanP) {
        cross = 1.24 / maxOf(1e-4, p.pace)
        val rnd = DesertMath.rng(p.seed xor 0x5117)
        val s = ArrayList<Double>()
        val d = ArrayList<Int>()
        var t = rnd.next() * 40
        var i = 0
        while (t < 4200) {
            s.add(t)
            d.add(if (i % 2 == 0) 1 else -1)
            t += cross + p.idle0 + rnd.next() * (p.idle1 - p.idle0)
            i++
        }
        starts = s.toDoubleArray()
        dir = d.toIntArray()
        span = 1.24
    }

    /** 1800 s 内的波数（T10 判据读它）。 */
    fun wavesWithin(sec: Double): Int {
        var w = 0
        var i = 0
        while (i < starts.size) {
            if (starts[i] <= sec) w++
            i++
        }
        return w
    }
}

/** `t` 时刻的驼队状态（原型 `caravanAt` 的返回体）。 */
internal class DesertCaravanState(
    val active: Boolean,
    val head: Double,
    val prog: Double,
    val wave: Int,
    val dir: Int,
    /** 下一波还要等多少秒（`active = false` 时才有意义）。 */
    val next: Double,
)

/**
 * 原型 `caravanAt(t)` —— ⛔ 线性扫（自检/逐帧都走这条，必须 O(log n)）。
 */
internal fun caravanAt(tbl: DesertCaravanTable, t: Double, w: Double): DesertCaravanState {
    val n = tbl.starts.size
    var lo = 0
    var hi = n - 1
    var idx = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (tbl.starts[mid] <= t) {
            idx = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (idx < 0) return DesertCaravanState(false, 0.0, 0.0, -1, 1, 0.0)
    val p = (t - tbl.starts[idx]) / tbl.cross
    if (p >= 1.0) {
        val nx = if (idx + 1 < n) tbl.starts[idx + 1] - t else 1e9
        return DesertCaravanState(false, 0.0, 0.0, idx, tbl.dir[idx], nx)
    }
    val d = tbl.dir[idx]
    val head = if (d > 0) (-0.12 + 1.24 * p) * w else (1.12 - 1.24 * p) * w
    // `next` 仅在 active = false 时有意义（活跃分支恒 0.0）。
    return DesertCaravanState(true, head, p, idx, d, 0.0)
}

/**
 * 骆驼脚线 y（原型 `camelFootY`）：跟着**驼队所在画布的可见顶边**走，
 * 再往下压 `occl·(0.62…1.00)` 个峰高 ⇒ 腿被**后画**的沙丘吃掉
 * （遮挡来自图层顺序，⛔ 来自"干脆不画腿"）。
 *
 * ⚠️ 分组后必须取 `groupTopCrest` 而⛔单条脊线：画布顶边 = 组内各脊的最高点，
 * 取低了骆驼会整个埋进画布里。
 *
 * ⛔⛔ 本函数**不是** `Params` 的字段、也⛔ 不许搬进参数表：D4 的结构闸门
 * （`CaravanOcclusionGateTest` 断言 `footY` 只能由 `groupTopCrest(carLayer, x)` 现取）。
 */
internal fun camelFootY(x: Double, jit: Double, bob: Double, lay: DesertLayout, cp: DesertCaravanP): Double =
    lay.groupTopCrest(lay.carLayer, x) + cp.h * lay.h * (cp.occl * (0.62 + 0.38 * jit) + bob)

/** 峰高被遮后露出的比例（原型 `camelVisible`；1 = 完全可见，0 = 全埋；T9 判据）。 */
internal fun camelVisible(jit: Double, bob: Double, cp: DesertCaravanP): Double {
    val off = cp.occl * (0.62 + 0.38 * jit) + bob
    return DesertMath.clamp(1.06 - off, 0.0, 1.06) / 1.06
}

/** 一只骆驼精灵的**锚点账**（原型 `B.camel[f]` 的那几个字段；位图本体由渲染器持有）。 */
internal class CamelSprite(
    val w: Int,
    val h: Int,
    /** 肩凹列 x（水平锚）。 */
    val cx: Double,
    /** 蹄线 y（垂直锚）。 */
    val sole: Double,
    /** 颈根列 x（`anatomy()` 的窗口锚）。 */
    val nk: Double,
    /** 躯干半长（`anatomy()` 的后峰区窗口）。 */
    val bodyL: Double,
    /** 腹线行 y（⛔ 只剩记录用）。 */
    val bellyY: Double,
    /** 烘焙格高（2× 显示高）。 */
    val hh: Double,
)

/** 精灵画布的 `(cw, ch)`（原型 `cw=round(1.22·lenK·h)`、`ch=round(1.04h)`）。 */
internal fun camelBox(h: Double, lenK: Double): IntArray = intArrayOf(
    DesertMath.jsRound(h * 1.22 * lenK).toInt(),
    DesertMath.jsRound(h * 1.04).toInt(),
)

/**
 * 贴图档骆驼（原型 `bakeTexCamel`）—— 画进调用方给好的 `cv`，⛔ 自己建位图。
 *
 * @param h 烘焙格高（**2× 显示高**，原型 `h * CAMEL_SS`）
 * @param cw / ch [camelBox] 的产物（画布与锚**照 `PROC` 同式** ⇒ 换档 blit 尺寸逐位不变）
 */
internal fun bakeTexCamel(
    cv: Canvas,
    paint: Paint,
    path: Path,
    h: Double,
    cw: Int,
    ch: Int,
    lenK: Double,
): CamelSprite? {
    if (cw < 1 || ch < 1) return null
    val t = CamelTex
    if (t.RINGS.isEmpty()) return null
    val inkH = maxOf(4.0, DesertMath.jsRound(h * t.INK_OVER_H))
    val k = inkH / t.IH
    val x = cw * 0.46
    val soleY = ch - 1.0
    val ox = x - k * t.SAD                     // 肩凹列 ↔ PROC 的 `X`
    val oy = soleY + 1.0 - k * t.IH            // 蹄线 ↔ `soleY`
    val bellyY = oy + k * t.BELLY

    paint.color = t.INK
    paint.style = Paint.Style.FILL
    path.reset()
    path.fillType = Path.FillType.EVEN_ODD      // ⛔ 换 winding / 拆两次 fill
    for (ring in t.RINGS) {
        path.moveTo((ox + k * ring[0]).toFloat(), (oy + k * ring[1]).toFloat())
        var i = 2
        while (i + 1 < ring.size) {
            path.lineTo((ox + k * ring[i]).toFloat(), (oy + k * ring[i + 1]).toFloat())
            i += 2
        }
        path.close()
    }
    cv.drawPath(path, paint)
    return CamelSprite(
        w = cw, h = ch, cx = x, sole = soleY,
        nk = ox + k * t.NECK, bodyL = k * (t.TORSO[1] - t.TORSO[0]) / 2.0,
        bellyY = bellyY, hh = h,
    )
}

/**
 * 驼队精灵的**显示**高（原型 `B.camelH = max(14, round(P.caravan.h·H))`）。
 *
 * ⚠️ 这是**显示尺寸**（不是烘焙尺寸：烘焙 = 它 × [CamelTex.SS]）。
 */
internal fun camelDisplayH(cp: DesertCaravanP, hScreen: Double): Double =
    maxOf(14.0, DesertMath.jsRound(cp.h * hScreen))
