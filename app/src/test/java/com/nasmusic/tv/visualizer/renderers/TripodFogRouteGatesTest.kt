package com.nasmusic.tv.visualizer.renderers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * E46「迷雾之战」的**路线 / 建筑随机化 / 人群纪元**闸门（§十二 T12、T13、T22）。
 *
 * 三条闸门全是纯函数判定（⛔ 不回读像素）：读的是 [TripodGait.sceneState] 的解析输出、
 * [TripodFogMath.scanRoute] 与借形公式。每条负样本各配一个旋钮对象（`wK`/`escAmp`/
 * `wValleyA·B`/`routeReject`/`seamRamp`/`CrowdEpoch.LAP_K·Z_SEED_K·VICTIM_K`），
 * ⛔ 不是手喂常量、⛔ 不共享生产路径之外的状态：负样本跑完必须复位，否则后面的闸门读到的是
 * 另一条路线（原型 `multi()` 里 `RA/RP` 的 slice 复位就是为这件事写的）。
 */
internal class TripodFogRouteGatesTest {

    private lateinit var world: TripodFogWorld

    @Before
    fun setup() {
        world = TripodGateFixtures.boot()
    }

    private fun fmt(x: Double, digits: Int = 1): String = String.format(Locale.ROOT, "%.${digits}f", x)

    // ══════════════════════════ T12 路线闸门 ══════════════════════════

    /** 原型 T12 的 `scan()`：逐帧读 `st.mx / st.psi`，并把两道光的**中点**反解回机身系。 */
    private class RouteScan {
        var maxMX = 0.0
        var maxPSI = -1e9
        var minPSI = 1e9

        /** 两道光中点的机身系分解与 `(beamSw, hitFwd)` 的最大残差（m）。 */
        var aimErr = 0.0
    }

    private fun scanRouteFrames(step: Double): RouteScan {
        val p = world.params
        val m = RouteScan()
        var t = 0.0
        while (t <= p.loop) {
            val st = TripodGait.sceneState(world, t)
            val deg = st.psi * 180 / Math.PI
            m.maxMX = maxOf(m.maxMX, abs(st.mx))
            m.maxPSI = maxOf(m.maxPSI, deg)
            m.minPSI = minOf(m.minPSI, deg)
            // ⛔ 判"落点在机身前方 X 米"或"落点与航向夹角"：前者世界系硬编码也成立，
            //    后者会把设计本身的 ±34 m 扫射当成违规（第一版就误报过 53°）。
            val hy = st.psi + st.headYaw
            val midX = (st.beams[0].hx + st.beams[1].hx) / 2
            val midZ = (st.beams[0].hz + st.beams[1].hz) / 2
            val dx = midX - st.mx
            val dz = midZ - st.d
            val ch = cos(hy)
            val sh = sin(hy)
            m.aimErr = maxOf(
                m.aimErr,
                hypot(dx * ch + dz * sh - st.beamSw, dx * sh - dz * ch - st.hitFwd),
            )
            t += step
        }
        return m
    }

    /** 原型 T12 的 `multi(reject)`：200 个种子逐个重抽路线，数不达标条数。 */
    private class MultiRes(val bad: Int, val maxTries: Int, val worst: String)

    private fun multiRoute(reject: Boolean): MultiRes {
        val p = world.params
        val savedReject = p.routeReject
        val savedRa = world.ra.copyOf()
        val savedRp = world.rp.copyOf()
        p.routeReject = reject
        var bad = 0
        var maxTries = 0
        var worst = ""
        for (sd in 1..200) {
            TripodFogMath.applyRoute(world, p.routeSeed0 + sd * 101)
            maxTries = maxOf(maxTries, world.routeTries)
            val q = TripodFogMath.scanRoute(world)
            if (!q.ok) {
                bad++
                if (worst.isEmpty()) {
                    worst = "种子 $sd：侧移 ${fmt(q.maxMX)} m / 航向 ${fmt(q.minPSI)}°…${fmt(q.maxPSI)}°" +
                        " / 可见窗最大 ${fmt(q.wMax, 0)}°"
                }
            }
        }
        p.routeReject = savedReject
        world.ra = savedRa
        world.rp = savedRp
        return MultiRes(bad, maxTries, worst)
    }

    /**
     * 原型 T12 的 `seamJump(ramp)`：跨圈那一帧比较**两条不同种子**路线的 `mx`。
     *
     * ⛔ 取 `routeMX` 而不是 [TripodFogWorld.routeRaw]：编舞项在接缝处本来就是 0，拆掉 `seamEnv`
     *    也量不出跳变 = 假判据。ε 必须 > 0（`seamEnv` 两端都恰好为 0，取 ε=0 正负样本一起塌成 0）。
     */
    private fun seamJump(ramp: Double): Double {
        val p = world.params
        val savedRamp = p.seamRamp
        val savedRa = world.ra.copyOf()
        val savedRp = world.rp.copyOf()
        val epsG = world.gtot / p.loop / 60
        p.seamRamp = ramp
        TripodFogMath.applyRoute(world, p.routeSeed0 + 101)
        val a = world.routeRaw(world.gtot - epsG)
        TripodFogMath.applyRoute(world, p.routeSeed0 + 202)
        val b = world.routeRaw(epsG)
        p.seamRamp = savedRamp
        world.ra = savedRa
        world.rp = savedRp
        return abs(a - b)
    }

    /**
     * **T12**：真拐出去 + 转过身体 + 逼近段收回正前方 + 光随身转 + 200 种子全达标 + 换路接缝不跳。
     *
     * 实测（§十二 表 T12 行）：最大侧移 40.0 m、航向 −67°…+35°、可见窗最大航向 51°、
     * 逼近位游走项侧移 0.12 m / 航向 −3.23°、机身系残差 0.000 m、200 种子全达标（最多重抽 3 次）、
     * 接缝跳变 0.002 m。负样本：`wK=0 且 escAmp=0` ⇒ 侧移 0.0 / 航向 0.0；谷壁塌掉 ⇒ 38.5 m；
     * `wK=2.5` ⇒ 60°；落点不随航向转 ⇒ 残差 104.2 m；关拒绝采样 ⇒ 12 条不达标；拆接缝包络 ⇒ 22.3 m。
     */
    @Test
    fun t12_routeHoldsItsDrama() {
        val p = world.params
        val m = scanRouteFrames(0.1)
        assertTrue("T12(a) 侧移 ${fmt(m.maxMX)} m < 15 ⇒ 其实还是直线", m.maxMX >= 15)
        assertTrue("T12(b) 航向没变号（${fmt(m.minPSI)}°…${fmt(m.maxPSI)}°）⇒ 身体从没真正转过",
            m.maxPSI > 8 && m.minPSI < -8)
        assertTrue("T12(e) 两道光中点的机身系分解残差 ${fmt(m.aimErr, 3)} m ≥ 1.5 ⇒ 光没随身转",
            m.aimErr < 1.5)

        // 负样本（a）：**同时**关掉游走项与编舞项才是所有者否掉的旧行为。
        //   ⚠️ R17 起只关 wK 不够：编舞项与 wK 无关，实测 wK=0 仍剩 16.0 m ⇒ 判据是在考一道没出的题。
        val savedK = p.wK
        val savedE = p.escAmp
        val m0 = try {
            p.wK = 0.0
            p.escAmp = 0.0
            scanRouteFrames(0.1)
        } finally {
            p.wK = savedK
            p.escAmp = savedE
        }
        assertTrue("T12(a) 负样本假绿：wK=0 且 escAmp=0 仍量到侧移 ${fmt(m0.maxMX)} m",
            m0.maxMX < 1e-9)
        assertTrue("T12(b) 负样本假绿：旧行为下航向仍 ${fmt(m0.maxPSI)}°", m0.maxPSI < 1e-9)

        // 负样本（d）：游走幅值过头 = 侧身走。
        val srHi = try {
            p.wK = 2.5
            TripodFogMath.scanRoute(world)
        } finally {
            p.wK = savedK
        }
        assertTrue("T12(d) 负样本假绿：wK=2.5 时可见窗航向 ${fmt(srHi.wMax, 0)}° 仍未越 55°",
            srHi.wMax > 55)

        // 负样本（c）：逼近谷壁塌掉 ⇒ 游走项不在贴身段收回。
        //   ⛔ 用 m0 兜 (c) 是假象：wK=0 时 mxAt 当然也归零，那测的是"有没有游走"，不是"收没收回来"。
        val savedVA = p.wValleyA
        val savedVB = p.wValleyB
        val srV = try {
            p.wValleyA = 1e-9
            p.wValleyB = 2e-9
            TripodFogMath.scanRoute(world)
        } finally {
            p.wValleyA = savedVA
            p.wValleyB = savedVB
        }
        assertTrue("T12(c) 负样本假绿：谷壁塌掉后游走项在逼近位仍只侧移 ${fmt(srV.mxAt)} m",
            abs(srV.mxAt) > 15)

        // 正样本（c/d）：取样点 = GAIM（开火段最近位）、取值层 = wanderRaw（不含 escEnv 门与编舞项）。
        val sr = TripodFogMath.scanRoute(world)
        assertTrue("T12(d) 可见游走窗内最大航向 ${fmt(sr.wMax, 0)}° > 55 ⇒ 侧身走", sr.wMax <= 55)
        assertTrue("T12(c) 逼近位游走项侧移 ${fmt(sr.mxAt, 2)} m > 15 ⇒ 贴身段还挂着满幅侧移",
            abs(sr.mxAt) <= 15)
        assertTrue("T12(c) 逼近位游走项航向 ${fmt(sr.psiAt, 2)}° > 8 ⇒ 最后没回到我们这边",
            abs(sr.psiAt) <= 8)

        val mPos = multiRoute(true)
        val mNeg = multiRoute(false)
        assertEquals("T12(f) 拒绝采样下有种子不达标：${mPos.worst}", 0, mPos.bad)
        assertTrue("T12(f) 负样本假绿：关掉拒绝采样重抽 200 种子竟然全达标（maxTries ${mPos.maxTries}）",
            mNeg.bad > 0)

        val jPos = seamJump(p.seamRamp)
        val jNeg = seamJump(1e-6)
        assertTrue("T12(g) 换路接缝帧 mx 跳变 ${fmt(jPos, 3)} m > 0.5 ⇒ 每 2 圈换路时画面横向跳一下",
            jPos <= 0.5)
        assertTrue("T12(g) 负样本假绿：拆掉接缝包络后跳变只有 ${fmt(jNeg)} m", jNeg > 5)

        // 负样本（e）：落点**不随航向转** —— 即第一版那句硬编码 hx = sw、hz = d − hitFwd。
        var negAim = 0.0
        var t = 0.0
        while (t <= p.loop) {
            val st = TripodGait.sceneState(world, t)
            val hy = st.psi + st.headYaw
            val dx = st.beamSw
            val dz = -st.hitFwd
            val ch = cos(hy)
            val sh = sin(hy)
            negAim = maxOf(negAim, hypot(dx * ch + dz * sh - st.beamSw, dx * sh - dz * ch - st.hitFwd))
            t += 0.2
        }
        assertTrue("T12(e) 负样本假绿：世界系「永远朝镜头」的残差只有 ${fmt(negAim)} m", negAim > 10)

        // 复位自检：⛔ 把负样本的路线或旋钮留给别的闸门。
        assertEquals(1.0, p.wK, 0.0)
        assertEquals(1.0, p.escAmp, 0.0)
        assertEquals(0.06, p.wValleyA, 0.0)
        assertEquals(0.28, p.wValleyB, 0.0)
        assertEquals(20.0, p.seamRamp, 0.0)
        assertTrue("T12 复位后 p.routeReject 不是默认 true", p.routeReject)
        assertEquals("复位后当前路线必须还是纪元抽出来的那一条", 40.0, scanRouteFrames(0.1).maxMX, 25.0)
    }

    // ══════════════════════════ T13 建筑随机化闸门 ══════════════════════════

    /** 原型 `rawBorrow(b, C, cfg, doMod)`：给定相机行程 `C`，本栋当前深度与**这一趟借哪一栋的形状**。 */
    private class Borrow(val id: Int, val z: Double, val span: Double)

    private fun rawBorrow(b: TripodBuilding, c: Double, cfg: TripodBandCfg, doMod: Boolean): Borrow {
        val span = world.gtot / b.k
        val u = (b.z0 - c - cfg.zMin) / span
        val k = floor(u)
        val step = if (doMod) TripodFogMath.mod(k, b.k.toDouble()) else k
        val pool = b.pool
        val id = if (pool.isEmpty()) 0 else {
            pool[TripodFogMath.mod(b.i + step * 11, pool.size.toDouble()).toInt()].i
        }
        return Borrow(id, cfg.zMin + (u - k) * span, span)
    }

    /** 带内不同回收周期 `k` 的种类数（原型 `kinds`）。 */
    private fun kinds(list: Array<TripodBuilding>): Int {
        val s = ArrayList<Int>()
        for (b in list) if (!s.contains(b.k)) s.add(b.k)
        return s.size
    }

    /** 某一帧某一带**入选建筑的形状 id**（原型 `idsAt`，band 0 = 中景 / 1 = 近景）。 */
    private fun idsAt(t: Double, bandIdx: Int): List<Int> {
        val st = TripodGait.sceneState(world, t)
        val arr = if (bandIdx == 0) st.bldMid else st.bldNear
        val n = if (bandIdx == 0) st.bldMidN else st.bldNearN
        val out = ArrayList<Int>(n)
        for (i in 0 until n) out.add(arr[i].id)
        return out
    }

    private fun jaccard(a: List<Int>, b: List<Int>): Double {
        var inter = 0
        for (v in a) if (b.contains(v)) inter++
        val uni = a.size + b.size - inter
        return if (uni > 0) inter.toDouble() / uni else 1.0
    }

    /** 整圈里真正出现过的不同形状数（原型 `seen`）。 */
    private fun seenBand(bandIdx: Int): Int {
        val loop = world.params.loop
        val s = ArrayList<Int>()
        var t = 0.0
        while (t < loop) {
            for (id in idsAt(t, bandIdx)) if (!s.contains(id)) s.add(id)
            t += 0.25
        }
        return s.size
    }

    /** 原型 `churn`：`now` = 隔 15 s 可见阵容的 Jaccard，`prefix` = 固定前缀写法的重叠（恒 1）。 */
    private class Churn(val now: Double, val prefix: Double)

    private fun churn(bandIdx: Int, list: Array<TripodBuilding>, n: Int): Churn {
        val loop = world.params.loop
        val oldS = ArrayList<Int>()
        for (i in 0 until minOf(n, list.size)) oldS.add(list[i].i)
        var acc = 0.0
        var k = 0
        var t = 0.0
        while (t < loop) {
            acc += jaccard(idsAt(t, bandIdx), idsAt(TripodFogMath.mod(t + 15, loop), bandIdx))
            k++
            t += 0.5
        }
        return Churn(if (k > 0) acc / k else 1.0, jaccard(oldS, oldS))
    }

    /**
     * **T13**：每栋自带周期 + 各趟换形 + 阵容流变 + 换形埋在雾里 + 接缝不差。
     *
     * 实测（§十二 表 T13 行）：周期种类 mid 3 / near 3、一圈回收 73 次（Σk 91）、48/48 各趟互不相同、
     * 整圈形状 34/34 + 14/14、隔 15 s 重叠 0.722 / 0.528、换形最大 α=0.0111、接缝差异 0 栋。
     * 负样本：旧带级跨度 ⇒ 近景一圈重演 2 趟、同形重复 14 次；固定前缀 ⇒ 阵容恒 22+8 种、重叠 1.000；
     * 借形不取 mod ⇒ 48 栋在 α=1.000 处换形。
     */
    @Test
    fun t13_skylineNeverReplaysAsOneBlock() {
        val p = world.params
        val kMid = kinds(world.bMid)
        val kNear = kinds(world.bNear)
        assertTrue("T13(a) 带内周期种类 mid=$kMid / near=$kNear < 3 ⇒ 天际线作为一整块重放",
            kMid >= 3 && kNear >= 3)

        val oldMid = minOf(p.nMid, world.bMid.size)
        val oldNear = minOf(p.nNear, world.bNear.size)
        val seenMid = seenBand(0)
        val seenNear = seenBand(1)
        assertTrue("T13(b) 中景整圈只见 $seenMid/${world.bMid.size} 栋 ≤ 前缀数 $oldMid ⇒ 阵容是固定前缀",
            seenMid > oldMid)
        assertTrue("T13(b) 近景整圈只见 $seenNear/${world.bNear.size} 栋 ≤ 前缀数 $oldNear ⇒ 阵容是固定前缀",
            seenNear > oldNear)

        val cMid = churn(0, world.bMid, oldMid)
        val cNear = churn(1, world.bNear, oldNear)
        assertTrue("T13(d) 隔 15 s 中景阵容重叠 ${fmt(cMid.now, 3)} ≥ 0.8 ⇒ 天际线整块重放",
            cMid.now < 0.8)
        assertTrue("T13(d) 隔 15 s 近景阵容重叠 ${fmt(cNear.now, 3)} ≥ 0.8 ⇒ 天际线整块重放",
            cNear.now < 0.8)
        assertTrue("T13(d) 负样本假绿：固定前缀的重叠 ${fmt(cMid.prefix, 3)} 竟不恒为 1",
            cMid.prefix > 0.999)

        // (a)(c) 沿 C 走完**恰好一圈**（半开区间，⛔ 含接缝那一帧 —— 它归下面的 seam 检查管）。
        var maxSwapA = 0.0
        var swapEv = 0
        var sumK = 0
        var sameShapePass = 0
        var allDistinct = 0
        var dupOld = 0
        for ((list, cfg) in listOf(world.bMid to world.bandMid, world.bNear to world.bandNear)) {
            for (b in list) {
                sumK += b.k
                val span = world.gtot / b.k
                val seq = ArrayList<Int>()
                var prev: Int? = null
                // ⚠️ 多走 0.25 个跨度：一圈恰好 b.k 趟，但边界那趟可能整段落在采样窗外，
                //    少露头一次就会把"真的换了形"误报成"重复"。判的是**去重后的形状数**。
                var j = 0
                while (j < 64 * b.k + 16) {
                    val w = rawBorrow(b, -j * span / 64, cfg, true)
                    val pr = prev
                    if (pr == null) seq.add(w.id) else if (w.id != pr) {
                        swapEv++
                        seq.add(w.id)
                        maxSwapA = maxOf(maxSwapA, TripodFogMath.bandWindow(w.z, cfg.zMin, w.span))
                    }
                    prev = w.id
                    j++
                }
                val uniq = ArrayList<Int>()
                for (id in seq) if (!uniq.contains(id)) uniq.add(id)
                sameShapePass += b.k - uniq.size
                if (uniq.size == b.k) allDistinct++
                // 负样本（a）：旧带级跨度 ⇒ 每槽位一圈重演 GTOT/cfg.span 趟，且借形恒为自身。
                val old = (world.gtot / cfg.span).roundToInt()
                if (old >= 2) dupOld += old * (old - 1) / 2
            }
        }
        val total = world.bMid.size + world.bNear.size
        assertEquals("T13(a) 各趟互不相同的栋数 $allDistinct ≠ 总栋数 $total", total, allDistinct)
        assertEquals("T13(a) 同形重复 $sameShapePass 次 > 0 ⇒ 同一栋一圈里两趟长得一样", 0, sameShapePass)
        assertTrue("T13(a) 负样本假绿：旧带级跨度只重演 $dupOld 次（应 ≥ 近景栋数 ${world.bNear.size}）",
            dupOld >= world.bNear.size)
        assertTrue("T13 回收事件计数 $swapEv（Σk $sumK）与 Σk 同量级才对", swapEv in 1..sumK)
        assertTrue("T13(c) 换形最大可见度 α=${fmt(maxSwapA, 4)} ≥ 0.05 ⇒ 换形发生在看得见的地方",
            maxSwapA < 0.05)

        // 负样本（c）：同一圈、同一批 C，只是借形不取 mod ⇒ 接缝处换形，量它落在多大 α 上。
        val c0 = TripodGait.sceneState(world, 0.0).c
        val c1 = TripodGait.sceneState(world, p.loop).c
        var negSwapA = 0.0
        var seamDiff = 0
        var seamDiffMod = 0
        for ((list, cfg) in listOf(world.bMid to world.bandMid, world.bNear to world.bandNear)) {
            for (b in list) {
                val a = rawBorrow(b, c0, cfg, false)
                val d = rawBorrow(b, c1, cfg, false)
                if (a.id != d.id) {
                    seamDiff++
                    negSwapA = maxOf(negSwapA, TripodFogMath.bandWindow(a.z, cfg.zMin, a.span))
                }
                if (rawBorrow(b, c0, cfg, true).id != rawBorrow(b, c1, cfg, true).id) seamDiffMod++
            }
        }
        assertEquals("T13(c) 正样本接缝借形差异 $seamDiffMod 栋 ≠ 0 ⇒ mod 没抵住回收次数", 0, seamDiffMod)
        assertTrue("T13(c) 负样本假绿：不取 mod 只有 $seamDiff 栋在接缝换形", seamDiff > 0)
        assertTrue("T13(c) 负样本假绿：不取 mod 的换形可见度 α=${fmt(negSwapA, 3)} ≤ 0.5", negSwapA > 0.5)
    }

    // ══════════════════════════ T22 人群纪元闸门 ══════════════════════════

    /** 一帧坑位的**快照**（R12 的 crowdBuf 是复用的 ⇒ ⛔ 跨帧持有引用）。 */
    private class Crow(val i: Int, val w: Int, val x: Double, val z: Double, val a: Double, val kind: Int)

    private fun crowdSnap(t: Double, skipVictim: Boolean): List<Crow> {
        val st = TripodGait.sceneState(world, t)
        val out = ArrayList<Crow>(st.crowdN)
        for (i in 0 until st.crowdN) {
            val c = st.crowd[i]
            if (skipVictim && c.victim) continue
            out.add(Crow(c.i, c.w, c.x, c.z, c.a, c.kind))
        }
        return out
    }

    private class LapScan(val ch: Int, val hiA: Double, val mid: Int, val slots: Int, val maxFlip: Int)

    /** 原型 `scanLap`：**整趟都在 lapN=0 上**（播放器的接缝帧是 t=CD 而非 t=LOOP，⛔ 在这里拨圈数）。 */
    private fun scanLap(cd: Double, nc: Int): LapScan {
        val cb = world.crowdBand
        val prevW = HashMap<Int, Int>()
        val flip = HashMap<Int, Int>()
        var ch = 0
        var hiA = 0.0
        var mid = 0
        for (i in 0 until nc) {
            for (c in crowdSnap(i * cd, true)) {
                val pw = prevW[c.i]
                if (pw != null && c.w != pw) {
                    ch++
                    flip[c.i] = (flip[c.i] ?: 0) + 1
                    if (c.a > hiA) hiA = c.a
                    val inWin = c.z <= cb.zMin + cb.fin || c.z >= cb.zMin + cb.span - cb.fout
                    if (!inWin) mid++
                }
                prevW[c.i] = c.w
            }
        }
        var maxFlip = 0
        for (v in flip.values) if (v > maxFlip) maxFlip = v
        return LapScan(ch, hiA, mid, flip.size, maxFlip)
    }

    /** 原型 `seamOnly`：第 k 圈末帧 → 第 k+1 圈首帧是**同一相位**，轮次必须逐坑位不变。 */
    private class Seam(val n: Int, val a: Double)

    private fun seamOnly(lapA: Int, lapB: Int, cd: Double): Seam {
        val loop = world.params.loop
        val prevLap = world.lapN
        try {
            world.lapN = lapA
            val a = crowdSnap(loop - cd, true)
            world.lapN = lapB
            val b = crowdSnap(cd, true)
            val ma = HashMap<Int, Crow>()
            for (c in a) ma[c.i] = c
            var n = 0
            var hiA = 0.0
            for (c in b) {
                val q = ma[c.i] ?: continue
                if (q.w == c.w) continue
                n++
                hiA = maxOf(hiA, maxOf(q.a, c.a))
            }
            return Seam(n, hiA)
        } finally {
            world.lapN = prevLap
        }
    }

    /** 原型 `identAcross`：同一相位把圈数拨成 0/1/2，逐坑位比侧向（x 的符号）与姿态。 */
    private class Ident(val n: Int, val ch: Int, val side: Int, val kind: Int)

    private fun identAcross(t: Double, laps: IntArray): Ident {
        val prevLap = world.lapN
        val sn = ArrayList<List<Crow>>(laps.size)
        try {
            for (l in laps) {
                world.lapN = l
                sn.add(crowdSnap(t, true))
            }
        } finally {
            world.lapN = prevLap
        }
        val n = sn[0].size
        var ch = 0
        var side = 0
        var kind = 0
        for (i in 0 until n) {
            var sd = false
            var kd = false
            for (j in 1 until sn.size) {
                if (sn[j][i].x * sn[0][i].x < 0) sd = true
                if (sn[j][i].kind != sn[0][i].kind) kd = true
            }
            if (sd || kd) ch++
            if (sd) side++
            if (kd) kind++
        }
        return Ident(n, ch, side, kind)
    }

    /** 原型 `zfp`：换种子只该换"谁站在这儿"，⛔ 换"这个人跑到哪一格"。 */
    private fun zFingerprint(seed: Int): DoubleArray {
        val p = world.params
        val saved = p.crowdSeed0
        p.crowdSeed0 = seed
        try {
            val o = ArrayList<Double>()
            for (i in 0 until 30) {
                for (c in crowdSnap(i * p.loop / 30, false)) o.add(c.z)
            }
            return o.toDoubleArray()
        } finally {
            p.crowdSeed0 = saved
        }
    }

    private class ZF(val same: Boolean, val d: Double)

    private fun maxZDiff(seeds: IntArray): ZF {
        val f = seeds.map { zFingerprint(it) }
        val first = f[0]
        var same = true
        for (x in f) {
            if (x.size != first.size) {
                same = false
                break
            }
            for (i in x.indices) if (x[i] != first[i]) {
                same = false
                break
            }
            if (!same) break
        }
        // 差值取**全部种子两两之间**的最大（只比前两颗会把 1.7 m 报成"最大差"，⛔ 低估违例）。
        var m = 0.0
        for (a in f.indices) for (b in a + 1 until f.size) {
            val fa = f[a]
            val fb = f[b]
            val n = minOf(fa.size, fb.size)
            for (i in 0 until n) m = maxOf(m, abs(fa[i] - fb[i]))
        }
        return ZF(same, m)
    }

    /** 原型 `vfp`：受害人锚点跨 4 圈 × 2 个种子必须**逐字节同一份**（它是抓取几何的一部分）。 */
    private fun vFingerprint(seed: Int): String {
        val p = world.params
        val saved = p.crowdSeed0
        val prevLap = world.lapN
        p.crowdSeed0 = seed
        val sb = StringBuilder()
        try {
            for (l in 0..3) {
                world.lapN = l
                val c = crowdSnap(48.5, false)[0]
                sb.append(fmt(c.x, 4)).append('/').append(fmt(c.z, 4)).append('/').append(c.kind).append('|')
            }
        } finally {
            world.lapN = prevLap
            p.crowdSeed0 = saved
        }
        return sb.toString()
    }

    private fun epochWhy(
        lap: LapScan,
        seam: Seam,
        idc: Ident,
        zf: ZF,
        vicA: String,
        vicB: String,
        nc: Int,
    ): List<String> {
        val bad = ArrayList<String>()
        if (lap.ch == 0) bad.add("a 一圈 $nc 帧里轮次一次都没跳过（换人逻辑没对象，本闸门空转）")
        if (seam.n > 0) bad.add(
            "a 跨圈接缝有 ${seam.n} 个坑位换了轮次、最亮 α=${fmt(seam.a, 4)} ⇒ 人在画面中段当场换脸",
        )
        if (!(lap.hiA < 0.02)) bad.add("a 圈内换人那一刻最亮 α=${fmt(lap.hiA, 4)} ≥ 0.02 ⇒ 换人发生在看得见的地方")
        if (lap.mid > 0) bad.add("a 有 ${lap.mid} 次换人落在淡入/淡出窗之外（z 不在带端）")
        // 原型 `Math.ceil(idc.n * 0.6)` 的整数写法（⛔ 浮点乘再 ceil —— 判据本身不该有舍入边界）。
        if (!(idc.ch >= (idc.n * 3 + 4) / 5 && idc.ch > 0)) {
            bad.add("b 三圈之间只有 ${idc.ch}/${idc.n} 个坑位换过人 ⇒ 纪元没进种子，第二圈还是同一批人")
        }
        if (!(idc.side > 0 && idc.kind > 0)) {
            bad.add("b 换过的坑位里 side 变 ${idc.side}、kind 变 ${idc.kind} ⇒ 只换了一半")
        }
        if (!zf.same) bad.add("c 换种子后 z 指纹不一致（最大差 ${fmt(zf.d, 2)} m）⇒ 纪元动到了纵向位置")
        if (vicA != vicB) bad.add("d 受害人锚点随种子漂移 ⇒ 抓取几何（grabDz/grabDx 与缆须长度）对不上")
        return bad
    }

    /**
     * **T22**：换人只在带端与跨圈、跨圈确实换、⛔ 动 `z`、受害人锚点恒定（R21）。
     *
     * 实测（§十二 表 T22 行）：一圈 2160 帧（1/24 s）内轮次跳变 9 次、覆盖 7 个坑位、跳变那刻最亮
     * α=0.0049、跨圈接缝 0 个坑位变、lap0/1/2 在 t=30 s 有 7/7 坑位换过人、5 个种子 z 指纹逐位相同、
     * 受害人锚点跨 4 圈 × 2 种子同一份。负样本：`LAP_K→0` ⇒ 接缝 7 个坑位换轮次 α=0.1551 + 三圈 0/7 换人；
     * `LAP_K→−1` ⇒ 接缝 α=0.1526；`Z_SEED_K→1` ⇒ z 指纹最大差 591.50 m；`VICTIM_K→1` ⇒ 锚点随种子漂移。
     */
    @Test
    fun t22_crowdEpochSwapsOnlyWhereInvisible() {
        val cd = 1.0 / 24
        val nc = (world.params.loop / cd).roundToInt()
        val lap0 = scanLap(cd, nc)
        val seam0 = seamOnly(0, 1, cd)
        val id0 = identAcross(30.0, intArrayOf(0, 1, 2))
        val seeds = intArrayOf(1, 2, 7, 42, 999)
        val zf0 = maxZDiff(seeds)
        val v1 = vFingerprint(1)
        val v7 = vFingerprint(7)
        val pw = epochWhy(lap0, seam0, id0, zf0, v1, v7, nc)
        assertTrue("T22 正样本违例：${pw.joinToString("；")}", pw.isEmpty())
        assertTrue("T22(a) 一圈轮次跳变 ${lap0.ch} 次 ⇒ 从没换过人", lap0.ch > 0)

        // 负样本 A / A′：lapK 的两个错号（0 = 不认圈；−1 = 反号 ⇒ 缝上变 ±2）。
        val savedLapK = CrowdEpoch.LAP_K
        val aw: List<String>
        val bw: List<String>
        try {
            CrowdEpoch.LAP_K = 0
            aw = epochWhy(scanLap(cd, nc), seamOnly(0, 1, cd), identAcross(30.0, intArrayOf(0, 1, 2)), zf0, v1, v7, nc)
            CrowdEpoch.LAP_K = -1
            bw = epochWhy(scanLap(cd, nc), seamOnly(0, 1, cd), id0, zf0, v1, v7, nc)
        } finally {
            CrowdEpoch.LAP_K = savedLapK
        }
        assertTrue("T22 负样本 A 假绿：lapK=0 竟无任何违例", aw.isNotEmpty())
        assertTrue("T22 负样本 A′ 假绿：lapK=−1 竟无任何违例", bw.isNotEmpty())

        // 负样本 C：把种子混进 z0；负样本 D：让受害人跟着纪元换。
        val savedZ = CrowdEpoch.Z_SEED_K
        val savedV = CrowdEpoch.VICTIM_K
        val cw: List<String>
        val dw: List<String>
        try {
            CrowdEpoch.Z_SEED_K = 1
            cw = epochWhy(lap0, seam0, id0, maxZDiff(seeds), v1, v7, nc)
            CrowdEpoch.Z_SEED_K = 0
            CrowdEpoch.VICTIM_K = 1
            dw = epochWhy(lap0, seam0, id0, zf0, vFingerprint(1), vFingerprint(7), nc)
        } finally {
            CrowdEpoch.Z_SEED_K = savedZ
            CrowdEpoch.VICTIM_K = savedV
        }
        assertTrue("T22 负样本 C 假绿：种子混进 z0 后 z 指纹仍一致", cw.isNotEmpty())
        assertTrue("T22 负样本 D 假绿：受害人跟纪元换后锚点仍不随种子漂移", dw.isNotEmpty())

        // 复位自检：⛔ 把负样本的纪元旋钮留给后面的闸门。
        assertEquals(1, CrowdEpoch.LAP_K)
        assertEquals(0, CrowdEpoch.Z_SEED_K)
        assertEquals(0, CrowdEpoch.VICTIM_K)
        assertEquals(0, world.lapN)
    }
}
