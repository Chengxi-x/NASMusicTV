package com.nasmusic.tv.visualizer.renderers

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * E47「雪松」的大树生成（原型 §3 `buildTree` + `treeOpt` 的 1:1 移植）。
 *
 * ## 坐标系
 * **单位系**：树干基点 `(0,0)`、干长 1、向上为 `−y`。出图时才乘各向异性缩放（见
 * [SnowCedarBakes]：横 `sx = uy·crownW`、纵 `uy`）。所以本文件**不知道画幅**，
 * 只从 [SnowTreeOpt.segLenPx] 吃一个已经换算好的"目标子段长"。
 *
 * ## 三条移植红线（P28/P29，逐条有黄金测试盯着）
 * 1. ⭐ `clumps` 每条**必须带 `tx/ty`**（该处枝条的实际切向）。无方向的团 ⇒ 分叉点上一个大白椭圆，
 *    所有者 2026-10-14 判「这是不对的，应该按树枝的方向落雪，类似普通树枝一样」（D13）。
 * 2. ⭐ 子枝根部的雪脊，其切向与中心**从子枝实际画出来的第一段** `segs[s0]` 现取，
 *    ⛔ 复制解析发射角 `a` 去预测：枝条是贝塞尔弯曲的，`bendK` 能让第一段与 `a` 差到
 *    ±`atan(2·bendK)` ≈ 31°，按 `a` 摆的雪实测有 29/269 条读成"雪挂歪了"（P29 ②）。
 * 3. ⭐ 节点处那一次 `rnd()` 抽取**必须原样保留**（[maybeNodeRnd]）—— 值丢弃也要抽。
 *    少抽一次就把后面整条枝条序列往后推一位 ⇒ 那是**换了一棵树**，
 *    实测 LOW/MED 铺屏 +0.06/+0.09 全被树形变化污染，账归不到"改了雪"上（P28 ③）。
 *
 * ## ⛔ 装饰量一律走 [SnowCedarMath.hash2]，不占 RNG 流
 * 团雪/根部脊的抖动是**装饰**，占一次 `rnd()` 就等于换一棵树。
 */
internal object SnowCedarTree {

    /**
     * 一根枝段（单位系剪影）。`depth` 供烘焙期的"越细越淡"斜坡用。
     *
     * ⛔ 这三张表都用**主构造器**而不用 `apply { … }`：`apply` 的隐式接收者会遮蔽外层的
     * `x/y/depth` 参数（`this.depth = depth` 变成自赋值 0）。这个坑本次踩过。
     */
    class Seg(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
        val w: Double, val depth: Int,
    )

    /** 一根枝段**上侧**的积雪条。`horiz = |ux|`（承雪判据吃它），`face` 是实际吃进去的那一维。 */
    class SnowBand(
        val x1: Double, val y1: Double, val x2: Double, val y2: Double,
        val sw: Double, val horiz: Double, val face: Double, val depth: Int,
    )

    /**
     * 一条**沿枝方向**的雪脊（团雪）。`tx/ty` = 该处枝条单位切向（红线 ①）。
     * 中心已含"压在枝子上方 `kw·0.5`"的那次抬升。
     */
    class Clump(
        val x: Double, val y: Double, val r: Double,
        val depth: Int, val tx: Double, val ty: Double,
    )

    /** 生成结果：三段表 + 单位系包围盒 + 两条计量。 */
    class Tree(
        val segs: ArrayList<Seg> = ArrayList(),
        val snow: ArrayList<SnowBand> = ArrayList(),
        val clumps: ArrayList<Clump> = ArrayList(),
        var x0: Double = 0.0, var x1: Double = 0.0,
        var y0: Double = 0.0, var y1: Double = 0.0,
        var maxRatio: Double = 0.0,
        var truncated: Boolean = false,
    )

    /**
     * `buildTree(opt)` 的入参。字段与原型 `P.tree` 一一对应，⛔ 少一个就少一条可核对的账。
     *
     * ⚠️ 这里**没有** `clump`（那是烘焙期才乘的系数，不参与几何），也**没有** `segPx`——
     * 单位系换算在 [treeOpt] 里一次做完，⛔ 两处各算一遍会漂（原型 §3 的注释）。
     */
    data class Opt(
        val seed: Int,
        val depth: Int,
        val lenDecay: Double,
        val spreadDeg: Double,
        val jitter: Double,
        val bendK: Double,
        val droop: Double,
        val segLenPx: Double,
        val trunkW: Double,
        val wTaper: Double,
        val maxSegs: Int,
        val childP3: Double,
        val snowHMin: Double,
        val snowK: Double,
        val snowMin: Double,
        val snowSign: Int,
    )

    /** 原型 `treeOpt(over)`：`P.tree` + `segLenPx = segPx ÷ max(1, L.treeH || 540)`。 */
    fun treeOpt(p: SnowCedarP, l: SnowCedarLayout): Opt {
        val t = p.tree
        // `L.treeH || 540`：layout 还没跑过时 treeH=0 ⇒ JS 落到 540（⛔ 除 0 得 Infinity，
        // 于是 n=round(len/∞)=0 再被 clamp 成 2 ⇒ 整棵树每枝只有 2 段）。
        val th = l.treeH
        val segLenPx = t.segPx / max(1.0, if (th == 0.0) 540.0 else th)
        return Opt(
            seed = t.seed, depth = t.depth, lenDecay = t.lenDecay, spreadDeg = t.spreadDeg,
            jitter = t.jitter, bendK = t.bendK, droop = t.droop, segLenPx = segLenPx,
            trunkW = t.trunkW, wTaper = t.wTaper, maxSegs = t.maxSegs, childP3 = t.childP3,
            snowHMin = t.snowHMin, snowK = t.snowK, snowMin = t.snowMin, snowSign = t.snowSign,
        )
    }

    /**
     * 递归生长。签名与原型 `grow(x, y, ang, len, wid, depth)` 逐位对应，
     * 调用序 = RNG 消费序 ⇒ ⛔ 重排任何一次抽取。
     */
    private class Growable(val opt: Opt, val out: Tree) {
        private val rnd = SnowCedarMath.rng(opt.seed)

        fun grow(x: Double, y: Double, ang: Double, len: Double, wid: Double, depth: Int) {
            if (depth <= 0 || len < 1e-4) return
            if (out.segs.size >= opt.maxSegs) { out.truncated = true; return }

            // 弯曲：垂直方向偏移的控制点（确定性，吃 RNG）
            val bend = (rnd.next() * 2 - 1) * opt.bendK * len
            val x2 = x + cos(ang) * len
            val y2 = y + sin(ang) * len
            val nx = cos(ang + SnowCedarMath.PI_D / 2)
            val ny = sin(ang + SnowCedarMath.PI_D / 2)
            val cxp = (x + x2) / 2 + nx * bend
            val cyp = (y + y2) / 2 + ny * bend
            val n = max(2, min(6, SnowCedarMath.jsRound(len / opt.segLenPx).toInt()))

            var px = x
            var py = y
            var ptx = 0.0
            var pty = 0.0
            var i = 1
            while (i <= n) {
                val t = i.toDouble() / n
                val it = 1 - t
                val qx = it * it * x + 2 * it * t * cxp + t * t * x2
                val qy = it * it * y + 2 * it * t * cyp + t * t * y2
                val dtx = 2 * it * (cxp - x) + 2 * t * (x2 - cxp)
                val dty = 2 * it * (cyp - y) + 2 * t * (y2 - cyp)
                val dl = SnowCedarMath.hypot(dtx, dty).let { if (it == 0.0) 1.0 else it }
                val ux = dtx / dl
                val uy = dty / dl
                val w = wid * (1 - 0.45 * t)
                out.segs.add(Seg(px, py, qx, qy, w, depth))

                // 水平度 = |ux|（1 = 完全水平 ⇒ 承雪；0 = 竖直 ⇒ 不承雪）
                val horiz = abs(ux)
                val face = if (opt.snowSign > 0) horiz else abs(uy)
                if (face >= opt.snowHMin) {
                    // 承雪厚度：水平枝 ⇒ 雪幔**比枝条本身还粗**（真雪的体积感），竖直枝 ⇒ 只剩一条薄痕。
                    // ⚠️ 下限必须是绝对值而非枝条比例（见 SnowTreeP.snowMin 的注释）。
                    val sw = max(opt.snowMin, w * opt.snowK * (0.35 + 0.85 * face))
                    // 埋没度：枝越细 ⇒ 雪越该**包住**它（偏移趋 0），粗枝才留"上侧承雪"的偏置。
                    val bury = SnowCedarMath.clamp((0.0090 - w) / 0.0060, 0.0, 1.0)
                    // 积雪画在枝段**上侧**：偏移方向恒为屏幕向上（−y），量值随水平度。
                    val off = sw * (0.62 - 0.45 * bury)
                    out.snow.add(SnowBand(px, py - off, qx, qy - off, sw, horiz, face, depth))
                }
                // ⭐ 枝端团雪**必须带枝条切向**（红线 ①）。⚠️ 条件成立才抽第二次 ⇒ 短路口径照抄。
                if (i == n && rnd.next() < 0.55) {
                    out.clumps.add(Clump(qx, qy - wid * 0.5, wid * (0.9 + rnd.next() * 0.8), depth, ux, uy))
                }
                px = qx; py = qy; ptx = ux; pty = uy
                i++
            }

            // ⛔ 旧口径在这里按**母枝宽**塞一坨 `r = wid·1.4` 的无方向团（P28）。雪删了，
            //    但旧口径吃过的那一次 `rnd()` **必须原样保留**（红线 ③）—— 删掉它就换了一棵树。
            if (depth >= opt.depth - 1) rnd.next()
            if (depth <= 1) return

            // 子枝：2 条必然 + 概率第 3 条；积雪重力 ⇒ 越靠下越下垂
            val kids = if (rnd.next() < opt.childP3) 3 else 2
            val half = (opt.spreadDeg * SnowCedarMath.D2R) / 2
            var k = 0
            while (k < kids) {
                val f = if (kids == 1) 0.5 else k.toDouble() / (kids - 1)
                val side = f - 0.5                            // −0.5 … +0.5
                var a = ang + side * 2 * half + (rnd.next() * 2 - 1) * opt.jitter
                a += opt.droop * (0.6 + (1 - depth.toDouble() / opt.depth)) * (if (ptx >= 0) ptx else -ptx) * 0.5
                // 原型这一行的 `+ (|side|<0.01 ? 0 : 0)` 是**恒 0 项**（定稿过程留下的占位），
                // 保留是为了让"中间那条子枝不额外下垂"这件事可读；⛔ 不要当冗余删掉算式其余部分。
                a = a + (if (abs(side) < 0.01) 0.0 else 0.0) - opt.droop * abs(side) * 0.9
                val kl = len * opt.lenDecay * (0.86 + rnd.next() * 0.3)
                val kw = wid * opt.wTaper * (0.88 + rnd.next() * 0.24)
                if (kl / len > out.maxRatio) out.maxRatio = kl / len

                // ⭐ 分叉处的体积 = **每条子枝沿自己方向**的一条起始雪脊（挂在子枝根部、沿子枝轴拉长）。
                //    ⚠️ 抖动走 hash2（⛔ rnd()）：装饰不占枝条 RNG 的序列。
                val hc = SnowCedarMath.hash2(
                    (px * 4177 + k * 613 + depth * 97).toInt(),
                    (py * 331).toInt(),
                )
                val s0 = out.segs.size
                grow(px, py, a, kl, kw, depth - 1)
                // ⭐ 长轴取子枝**实际画出来的第一段**的弦向（红线 ②）；中心落在第一段中点
                //    ⇒ 雪脊与枝条**同段同长**，⛔ 悬在节点外。
                //    截断的子枝（`maxSegs` 用完）一段都没画 ⇒ `segs.size == s0`，此时不产出雪脊。
                if (hc < 0.62 && out.segs.size > s0) {
                    val first = out.segs[s0]
                    val fdx = first.x2 - first.x1
                    val fdy = first.y2 - first.y1
                    val fl = SnowCedarMath.hypot(fdx, fdy).let { if (it == 0.0) 1.0 else it }
                    out.clumps.add(
                        Clump(
                            first.x1 + fdx * 0.5,
                            first.y1 + fdy * 0.5 - kw * 0.5,
                            kw * (0.75 + (hc / 0.62) * 0.5),
                            depth - 1,
                            fdx / fl, fdy / fl,
                        ),
                    )
                }
                k++
            }
        }
    }

    /**
     * 原型 `buildTree(opt)`：从 `(0,0)` 朝正上（`−π/2`）以干长 1、干宽 `trunkW` 起根。
     *
     * ⚠️ 包围盒只统计 `segs` 的**端点**（⛔ 不含线宽、⛔ 不含雪条抬升）—— 这是位图尺寸的口径，
     * 改了它 = 改铺屏账（§三 的实测尺寸表因此必须随本函数一起再生）。
     */
    fun build(opt: Opt): Tree {
        val out = Tree()
        val g = Growable(opt, out)
        g.grow(0.0, 0.0, -SnowCedarMath.PI_D / 2, 1.0, opt.trunkW, opt.depth)
        var x0 = 0.0; var x1 = 0.0; var y0 = 0.0; var y1 = 0.0
        for (s in out.segs) {
            x0 = min(x0, min(s.x1, s.x2)); x1 = max(x1, max(s.x1, s.x2))
            y0 = min(y0, min(s.y1, s.y2)); y1 = max(y1, max(s.y1, s.y2))
        }
        out.x0 = x0; out.x1 = x1; out.y0 = y0; out.y1 = y1
        return out
    }

    /**
     * FNV-1a 指纹（原型 §11 `fpOf`）—— **同一种子两次生成必须逐位相等**，换种子必须不等。
     *
     * ⚠️ 只用于 Kotlin **内部**的确定性自证（原型 T1 口径）。⛔ 拿它跨语言对账：
     * JS 与 JVM 的 `Math.sin/cos` 不保证逐位一致（不同 libm），一棵树的几百次三角调用
     * 会把 1 ulp 差积累成分支翻转 ⇒ 假红。跨语言对账走 [SnowCedarGoldenTest] 的**逐标量 1e-9**。
     *
     * `mix(v) = imul(h xor (round(v·1e6)|0), 16777619)`：`jsRound` 的 `.5` 口径必须照抄。
     */
    fun fingerprint(items: List<DoubleArray>): Long {
        var h = -2128831035            // 0x811C9DC5（JS 的 2166136261 按 Int 解释）
        for (a in items) {
            for (v in a) {
                val q = SnowCedarMath.jsRound(v * 1e6).toInt()
                h = h xor q
                h *= 16777619
            }
        }
        return h.toLong() and 0xFFFFFFFFL
    }

    /** 把三段表拍成 `fpOf` 的入参序（`x1,y1,x2,y2,w|sw`，与原型逐字段同序）。 */
    fun fpArgs(t: Tree): List<DoubleArray> {
        val all = ArrayList<DoubleArray>(t.segs.size + t.snow.size)
        for (s in t.segs) all.add(doubleArrayOf(s.x1, s.y1, s.x2, s.y2, s.w))
        for (s in t.snow) all.add(doubleArrayOf(s.x1, s.y1, s.x2, s.y2, s.sw))
        return all
    }
}
