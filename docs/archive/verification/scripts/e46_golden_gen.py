# -*- coding: utf-8 -*-
"""E46 K3/K4 对账生成器：从冻结的浏览器原型取**权威期望值**，生成 Kotlin 黄金测试。

用法（托管 venv）：
    C:/Users/hxzha/.workbuddy/binaries/python/envs/default/Scripts/python.exe \n        docs/archive/verification/scripts/e46_golden_gen.py

⚠️ 本脚本是 `TripodFogGoldenTest.kt` 期望值的**唯一再生源**（E44 D24 的同款纪律：
   数字只能由脚本再生，⛔ 手抄）。改云场/楼场数学后必须重跑它并把结果一起提交。

做法（与 docs/archive/verification/scripts/e46_fog_harness.js 同一套 vm 沙箱）：
    1. 读 `docs/e46-tripod-fog-preview.html` 的**原文脚本**，在 Node vm 里桩掉 DOM/canvas 后整体载入
       （`boot()` 因此完整跑过：layout → buildCum → setGrabKnots → syncRouteEpoch(true) → genBuildings → bakeAll）；
    2. 把 48 栋位图的 `_skirt` 归零（位图归 K5，几何与数学归 K3/K4 ⇒ 两边都读 0）；
    3. 探针驱动**在原型作用域内**求值（`vm.runInContext`），输出 JSON；
    4. 由同一张探针表生成 Kotlin 黄金测试（期望值来自 JSON，实际值调 K3/K4 的真实函数）。

⚠️ 探针表（PROBES）是唯一真值来源：每条同时写死 JS 表达式与 Kotlin 表达式，
   两边算出的数值必须逐项相等（1e-9）—— 差一项就是移植缺陷，⛔ 靠"看起来对"放过。
"""
import io
import json
import os
import re
import subprocess
import sys
import traceback

# 脚本在 docs/archive/verification/scripts/ 下 ⇒ 上溯四级到仓库根（与同目录其它脚本一致）
ROOT = os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..")
)
HTML = os.path.join(ROOT, "docs", "e46-tripod-fog-preview.html")
HARNESS = os.path.join(ROOT, "logs_temp", "e46_golden_harness.js")
GOLDEN = os.path.join(ROOT, "logs_temp", "e46_golden.json")
KT = os.path.join(
    ROOT, "app", "src", "test", "java", "com", "nasmusic", "tv",
    "visualizer", "renderers", "TripodFogGoldenTest.kt",
)
LOG = os.path.join(ROOT, "logs_temp", "gen_golden.log")
NODE = "C:/Users/hxzha/.workbuddy/binaries/node/versions/22.22.2-6/node.exe"

# ─────────────────────────── 探针表 ───────────────────────────
# (key, js_expr, kotlin_expr) —— js_expr 在原型 IIFE 作用域内求值；kotlin_expr 在测试类内求值。
# ⚠️ 顺序即求值顺序：world 是**有状态**的（applyVariant 会换剧本），
#    "state" 必须排在所有会换剧本的探针之前，fp*/scanCross* 必须排在最后。
PROBES = []


def P(key, js, kt):
    PROBES.append((key, js, kt))


def jsPairs(pairs):
    """Python 元组表 → JS **二维数组**字面量（PROBES 表在模块级求值 ⇒ 必须定义在它之前）。

    ⛔ 不能 `%r`：`(0, 0)` 在 JS 里是**逗号运算符**（整个表达式值 0）⇒
       `[(0, 0), (1, 0)]` 变成 `[0, 0, 0, ...]`，`for (const [x, y] of ...)` 报
       "not iterable"（K5 探针第一版就这么崩的）。
    """
    return "[" + ", ".join("[%d, %d]" % p for p in pairs) + "]"


def darr(name, vals):
    """JS 侧字面量数组（保持与 Kotlin 侧同一个书写顺序）。"""
    return "[" + ", ".join(repr(float(v)) for v in vals) + "]"


# ── buildCum 派生量 ──
P("cum.cumV", "[CUM_V]", "doubleArrayOf(world.cumV)")
P("cum.gscale", "[Gscale]", "doubleArrayOf(world.gscale)")
P("cum.gtot", "[GTOT]", "doubleArrayOf(world.gtot)")
P("cum.taim", "[TAIM]", "doubleArrayOf(world.taim)")
P("cum.gaim", "[GAIM]", "doubleArrayOf(world.gaim)")
P("cum.gw", "[GW]", "doubleArrayOf(world.gw)")
P("cum.chGkx", "Array.from(CHG.kx)", "DoubleArray(world.chG!!.kx.size) { world.chG!!.kx[it] }")
P("cum.chG", "Array.from(CHG.ky)", "DoubleArray(world.chG!!.ky.size) { world.chG!!.ky[it] }")
P("cum.escGkx", "Array.from(ESCG.kx)", "DoubleArray(world.escG!!.kx.size) { world.escG!!.kx[it] }")
P("cum.escG", "Array.from(ESCG.ky)", "DoubleArray(world.escG!!.ky.size) { world.escG!!.ky[it] }")
P("cum.spanMid", "[BAND.mid.span]", "doubleArrayOf(world.bandMid.span)")
P("cum.spanNear", "[BAND.near.span]", "doubleArrayOf(world.bandNear.span)")
P("cum.spanCrowd", "[CBAND.span]", "doubleArrayOf(world.crowdBand.span)")
# ── 世界状态（换戏之前的快照） ──
P(
    "state",
    "[CURVAR, routeEpoch, lapN, RA[0], RA[1], RA[2], RA[3], RP[0], RP[1], RP[2], RP[3], routeTries, varSwitches, _cb]",
    "doubleArrayOf(world.curVar.toDouble(), world.routeEpoch.toDouble(), world.lapN.toDouble(), "
    "world.ra[0], world.ra[1], world.ra[2], world.ra[3], "
    "world.rp[0], world.rp[1], world.rp[2], world.rp[3], "
    "world.routeTries.toDouble(), world.varSwitches.toDouble(), world.crowdBuilds.toDouble())",
)
# ── hermite：直接探**生产表** DH / YH（⛔ 合成表 —— 那踩不到真数据的 Fritsch–Carlson 半径钳制） ──
P("hermite.dhM", "Array.from(DH.m)", "DoubleArray(world.dh.m.size) { world.dh.m[it] }")
P("hermite.yhM", "Array.from(YH.m)", "DoubleArray(world.yh.m.size) { world.yh.m[it] }")
_T = [-0.5, 0.0, 0.3, 8.0, 26.0, 59.6, 68.0, 90.0, 91.0]
P(
    "hermite.dhEval",
    darr("dhEval", _T) + ".map(x => hEval(DH, x))",
    "doubleArrayOf(" + ", ".join(repr(v) for v in _T) + ").map { world.dh.eval(it) }.toDoubleArray()",
)
P(
    "hermite.dhDeriv",
    darr("dhDeriv", _T) + ".map(x => hDeriv(DH, x))",
    "doubleArrayOf(" + ", ".join(repr(v) for v in _T) + ").map { world.dh.deriv(it) }.toDoubleArray()",
)
P(
    "hermite.yhEval",
    darr("yhEval", _T) + ".map(x => hEval(YH, x))",
    "doubleArrayOf(" + ", ".join(repr(v) for v in _T) + ").map { world.yh.eval(it) }.toDoubleArray()",
)
P(
    "hermite.grenEval",
    darr("grenEval", _T) + ".map(x => hEval(GREN, x))",
    "doubleArrayOf(" + ", ".join(repr(v) for v in _T) + ").map { world.gren.eval(it) }.toDoubleArray()",
)
# ── LCG（逐位同构） ──
# ⚠️ 必须是**同一个** Lcg 实例连抽 5 次（⛔ 每次 new 一个：那 5 个数全等于首值）。
for _s in (1, 12345, 20261011):
    P(
        "lcg.%d" % _s,
        "(() => { const f = lcg(%d); const a = []; for (let i = 0; i < 5; i++) a.push(f()); return a; })()" % _s,
        "run { val f = TripodFogMath.Lcg(%d); Array(5) { f.next() }.toDoubleArray() }" % _s,
    )
# ── pw（分段线性步速表） ──
_W = [0.0, 7.3, 14.0, 44.0, 55.0, 68.0, 90.0]
P(
    "pw",
    darr("pw", _W) + ".map(t => pw(VKX, VKY, t))",
    "doubleArrayOf(" + ", ".join(repr(v) for v in _W) + ")"
    ".map { TripodFogMath.pw(TRIPOD_VKX, TRIPOD_VKY, it) }.toDoubleArray()",
)
# ── 距离雾 ──
_D = [5.0, 20.0, 50.0, 100.0, 300.0]
_DL = ", ".join(repr(v) for v in _D)
P("fogOf", darr("fogOf", _D) + ".map(d => fogOf(d, 70))",
  "doubleArrayOf(" + _DL + ").map { TripodFogMath.fogOf(it, 70.0, world.params) }.toDoubleArray()")
P("shadeA", darr("shadeA", _D) + ".map(d => shade(d, 70).a)",
  "doubleArrayOf(" + _DL + ").map { TripodFogMath.shadeA(it, 70.0, world.params) }.toDoubleArray()")
P("shadeFog", darr("shadeFog", _D) + ".map(d => shade(d, 70).fog)",
  "doubleArrayOf(" + _DL + ").map { TripodFogMath.fogOf(it, 70.0, world.params) }.toDoubleArray()")
P("contrast", darr("contrast", _D) + ".map(d => contrastAt(d, 70))",
  "doubleArrayOf(" + _DL + ").map { TripodFogMath.contrastAt(it, 70.0, world.params, world.palette) }.toDoubleArray()")
P(
    "mix3",
    "mix3(CFG.SILH, CFG.FOG, 0.37).map(v => v | 0)",
    "TripodFogMath.mix3(world.palette.silh, world.palette.fog, 0.37).map { it.toDouble() }.toDoubleArray()",
)
# ── 投影 / 相机变换 ──
for _tag, _a, _ka in (
    ("a", "10, 5, 30, 0", "10.0, 5.0, 30.0, 0.0"),
    ("b", "-40, 2, 20, 0.3", "-40.0, 2.0, 20.0, 0.3"),
    ("c", "0, 0, -10, 0", "0.0, 0.0, -10.0, 0.0"),
):
    P(
        "pr." + _tag,
        "(() => { const q = pr(%s); return q ? [q.X, q.Y, q.s, q.along] : [-1, -1, -1, -1]; })()" % _a,
        "run { val q = TripodFogMath.pr(world.cam, world.params, %s); "
        "if (q == null) doubleArrayOf(-1.0, -1.0, -1.0, -1.0) else doubleArrayOf(q.X, q.Y, q.s, q.along) }" % _ka,
    )
for _tag, _a, _ka in (("a", "12, 30, 0.7", "12.0, 30.0, 0.7"), ("b", "-5, 100, -0.2", "-5.0, 100.0, -0.2")):
    P(
        "toCam." + _tag,
        "(() => { const q = toCam(%s); return [q.lat, q.along]; })()" % _a,
        "TripodFogMath.toCam(%s)" % _ka,
    )
    P(
        "fromCam." + _tag,
        "(() => { const q = fromCam(%s); return [q.x, q.z]; })()" % _a,
        "TripodFogMath.fromCam(%s)" % _ka,
    )
# ── 两连杆 IK / bodyFrame ──
P(
    "ik",
    "(() => { const q = ikKnee(2, 30, 50, 8, 0, 62, 9, 43, 43); return [q.x, q.y, q.z]; })()",
    "run { val q = TripodFogMath.ikKnee(2.0, 30.0, 50.0, 8.0, 0.0, 62.0, 9.0, 43.0, 43.0, world.params); "
    "doubleArrayOf(q.x, q.y, q.z) }",
)
P(
    "bodyFrame.a",
    "(() => { const q = bodyFrame(3, 5, 30, 0.7, 50, 12); return [q.x, q.y, q.z]; })()",
    "run { val q = TripodFogMath.bodyFrame(3.0, 5.0, 30.0, 0.7, 50.0, 12.0); doubleArrayOf(q.x, q.y, q.z) }",
)
P(
    "bodyFrame.b",
    "(() => { const q = bodyFrame(-8, 2.5, 1.2, -0.4, 120, -33); return [q.x, q.y, q.z]; })()",
    "run { val q = TripodFogMath.bodyFrame(-8.0, 2.5, 1.2, -0.4, 120.0, -33.0); doubleArrayOf(q.x, q.y, q.z) }",
)
# ── 「带 + 两端窗口」 ──
_Z = [2.0, 30.0, 60.0, 150.0, 400.0]
_ZL = ", ".join(repr(v) for v in _Z)
P("bandWindow", darr("bandWindow", _Z) + ".map(z => bandWindow(z, { zMin: 105, span: 600 }))",
  "doubleArrayOf(" + _ZL + ").map { TripodFogMath.bandWindow(it, 105.0, 600.0) }.toDoubleArray()")
P("crowdWindow", darr("crowdWindow", _Z) + ".map(crowdWindow)",
  "doubleArrayOf(" + _ZL + ").map { TripodFogMath.crowdWindow(world, it) }.toDoubleArray()")
# ── 落地冲击 / 步频冲击 / 人群纪元 ──
_U = [0.30, 1.0 / 3.0, 0.36, 0.45, 0.9, 1.0]
_UL = ", ".join(repr(v) for v in _U)
P("impProgress", darr("impProgress", _U) + ".map(impProgress)",
  "doubleArrayOf(" + _UL + ").map { TripodFogMath.impProgress(it) }.toDoubleArray()")
P("footImp", darr("footImp", _U) + ".map(footImp)",
  "doubleArrayOf(" + _UL + ").map { TripodFogMath.footImp(it) }.toDoubleArray()")
P(
    "jolt",
    "(() => { const legU = [0.35, 0.68, 1.02], legImp = [0.8, 0.0, 0.5], dist = 30; "
    "const joltK = 1 - smoothstep(JOLT.dIn, JOLT.dOut, dist); const joltEnv = P.joltA * joltK; "
    "let jolt = 0; for (let k = 0; k < JOLT.legK; k++) { const w = impProgress(legU[k]); "
    "if (w >= 0 && w < 1) jolt += legImp[k] * Math.cos(TAU * JOLT.cyc * w); } "
    "const joltY = jolt * joltEnv; return [joltY * JOLT.xK, joltY, joltK]; })()",
    "TripodFogMath.joltOf(doubleArrayOf(0.35, 0.68, 1.02), doubleArrayOf(0.8, 0.0, 0.5), 30.0, world.params)",
)
P(
    "crowdEpoch",
    "[-50, 130, 700].map(raw => Math.floor(raw / CBAND.span) + CEPOCH.lapK * lapN)",
    "doubleArrayOf(-50.0, 130.0, 700.0)"
    ".map { TripodFogMath.crowdEpochOf(it, world.crowdBand.span, world.lapN).toDouble() }.toDoubleArray()",
)
# ── variantOf ──
P(
    "variantOf",
    "[0, 1, 2, 3, 4, 5, 6, 7].map(variantOf)",
    "doubleArrayOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0)"
    ".map { TripodFogMath.variantOf(it.toInt(), world.params).toDouble() }.toDoubleArray()",
)
# ── 路线（escEnv / choreoMX / routeMX / routePSI / seamEnv / wanderEnv） ──
_G = [0.0, 37.5, 120.0, 300.0, 480.0, 599.9]
_GL = ", ".join(repr(v) for v in _G)
P("escEnv", darr("escEnv", _G) + ".map(escEnv)",
  "doubleArrayOf(" + _GL + ").map { world.escEnv(it) }.toDoubleArray()")
P("choreoMX", darr("choreoMX", _G) + ".map(choreoMX)",
  "doubleArrayOf(" + _GL + ").map { world.choreoMX(it) }.toDoubleArray()")
P("routeMX", darr("routeMX", _G) + ".map(routeMX)",
  "doubleArrayOf(" + _GL + ").map { world.routeMX(it) }.toDoubleArray()")
P("routePSI", darr("routePSI", _G) + ".map(routePSI)",
  "doubleArrayOf(" + _GL + ").map { world.routePSI(it) }.toDoubleArray()")
P("seamEnv", darr("seamEnv", _G) + ".map(seamEnv)",
  "doubleArrayOf(" + _GL + ").map { world.seamEnv(it) }.toDoubleArray()")
P("wanderEnv", darr("wanderEnv", _G) + ".map(wanderEnv)",
  "doubleArrayOf(" + _GL + ").map { world.wanderEnv(it) }.toDoubleArray()")
P("fireEnv", darr("fireEnv", _T) + ".map(t => fireEnv(t))",
  "doubleArrayOf(" + ", ".join(repr(v) for v in _T) + ").map { TripodFogMath.fireEnv(world.params, it) }.toDoubleArray()")
# ── 人群坑位表 + 身份（V0） ──
P(
    "ctab",
    "(() => { const TB = crowdTable(); const a = []; for (const p of TB) a.push(p.i, p.victim ? 1 : 0, p.z0, p.x, p.kind); return a; })()",
    "ctabArr()",
)
P(
    "cident",
    "(() => { const a = []; for (let i = 0; i < 4; i++) for (let w = 0; w < 3; w++) { const id = crowdIdent(i, w); a.push(id.x, id.kind); } return a; })()",
    "cidentArr()",
)
# ── 缆须（t=52 抓取瞬间，V0 grabT=52） ──
P(
    "tendril",
    "(() => { const st = sceneState(52); const r1 = tendrilRoot(st, -0.62), t1 = tendrilTip(st, -0.62); "
    "const r2 = tendrilRoot(st, 0.62), t2 = tendrilTip(st, 0.62); "
    "return [r1.x, r1.y, r1.z, t1.x, t1.y, t1.z, t1.D, t1.L, r2.x, r2.y, r2.z, t2.x, t2.y, t2.z, t2.D, t2.L]; })()",
    "tendrilArr(52.0)",
)
# ── aimState（V0 三帧） ──
for _t in (12.5, 59.6, 71.3):
    P(
        "aim.%s" % _t,
        "aimDump(aimState(%s, audioEnv(%s)))" % (_t, _t),
        "aimArr(TripodFogMath.aimState(world, %s, TripodFogMath.audioEnv(world.params, %s)))" % (_t, _t),
    )
# ── sceneState（V0 六帧） ──
for _t in (0.0, 12.5, 33.7, 59.6, 71.3, 88.9):
    P(
        "scene.%s" % _t,
        "sceneDump(sceneState(%s))" % _t,
        "sceneArr(TripodGait.sceneState(world, %s))" % _t,
    )
# ── tripodPose ──
for _t in (12.5, 59.6):
    P(
        "pose.%s" % _t,
        "poseDump(tripodPose(sceneState(%s)))" % _t,
        "run { val p = TripodGait.tripodPose(world, TripodGait.sceneState(world, %s)); "
        "val o = ArrayList<Double>(); for (q in p.hips) { o.add(q.x); o.add(q.y); o.add(q.z) }; "
        "for (q in p.knees) { o.add(q.x); o.add(q.y); o.add(q.z) }; "
        "for (q in p.feet) { o.add(q.x); o.add(q.y); o.add(q.z) }; o.toDoubleArray() }" % _t,
    )
# ── scanCross 逐套剧本 ──
for _vi in (0, 1, 2):
    P(
        "scanCross.v%d" % _vi,
        "(() => { applyVariant(%d, true); return [TCROSS.t1, TCROSS.t2, TCROSS.n, TCROSS.cross ? 1 : 0]; })()" % _vi,
        "run { TripodFogMath.applyVariant(world, %d, true); "
        "doubleArrayOf(world.tcross.t1, world.tcross.t2, world.tcross.n.toDouble(), if (world.tcross.cross) 1.0 else 0.0) }" % _vi,
    )
# ── fingerprint 逐套剧本（接缝对账；每条自带 applyVariant） ──
for _vi in (0, 1, 2):
    for _tag, _t in (("t0", 0.0), ("t90", 90.0)):
        P(
            "fp%d.%s" % (_vi, _tag),
            "(() => { applyVariant(%d, true); return fingerprint(sceneState(%s)); })()" % (_vi, _t),
            "run { TripodFogMath.applyVariant(world, %d, true); "
            "val n = TripodGait.fingerprint(TripodGait.sceneState(world, %s), FPBUF, world.gtot); FPBUF.copyOf(n) }"
            % (_vi, _t),
        )

# ═══════════════ K5 烘焙层对账（雾条/grade 逐像素 bit-exact；建筑/人群只对账记账值） ═══════════════
# ⚠️ 顺序：全部排在 fp* 之后 —— JS 侧 bakeBuilding(b) **重新烘焙**（读 P.skirtH/skirtA 的现值），
#   Kotlin 侧同理在**副本**上烘（⛔ 就地烘 world.bMid：那会把 skirtFrac 写回建筑，
#   而 scene.* 探针要求它恒 0 —— 位图归 K5、几何归 K4 的口径就在这一行上）。
# ⚠️ 两张表都必须**严格在界内**（x < w, y < h）：getImageData 只返回 w·h·4 字节，
#    越界读在 JS 里是 undefined ⇒ JSON 变 null ⇒ 生成器 float(None) 崩；而 x ≥ w 但
#    扁平索引仍落在数组内的会**读到另一行的像素**（探针名说的就不是它测的东西了）。
#    B 条只有 192 宽 ⇒ 单独一张表（⛔ 与 A 共用：200/240/255 不是越界崩就是串行）。
_FOG_SAMPLE_A = [(0, 0), (1, 0), (7, 3), (63, 17), (128, 88), (200, 42), (255, 0), (0, 299),
                 (255, 299), (37, 199), (191, 250), (13, 130), (240, 7), (99, 66), (5, 288), (171, 121)]
_FOG_SAMPLE_B = [(0, 0), (1, 0), (7, 3), (63, 17), (128, 88), (100, 42), (191, 0), (0, 299),
                 (191, 299), (37, 199), (95, 250), (13, 130), (120, 7), (99, 66), (5, 288), (171, 121)]
for _tag, _w, _h, _cells, _seed, _smp in (("fogA", 256, 300, 3, 7717, _FOG_SAMPLE_A),
                                          ("fogB", 192, 300, 4, 3391, _FOG_SAMPLE_B)):
    P(
        _tag,
        "(() => { const cv = bakeFogStrip(%d, %d, %d, %d, 5, 1);"
        " const im = cv.getContext('2d').getImageData(0, 0, %d, %d).data;"
        " const a = [cv._seam, cv._seamBad, cv._step, cv._stepBad];"
        " for (const [x, y] of %s) { const i = (y * %d + x) * 4; a.push(im[i], im[i + 1], im[i + 2], im[i + 3]); }"
        " return a; })()" % (_w, _h, _cells, _seed, _w, _h, jsPairs(_smp), _w),
        _tag + "Arr()",
    )
# ⚠️ 采样点必须**严格在界内**（x < W, y < H）：getImageData(0,0,W,H) 只返回 W·H·4 字节，
#    (960,540) 会读到 undefined ⇒ JSON 里变 null ⇒ 生成器 float(None) 崩。末像素是 (959,539)。
_GRAD_SAMPLE = [(0, 0), (479, 269), (959, 539), (17, 530), (480, 0), (300, 300), (700, 100), (5, 5)]
P(
    "grade",
    "(() => { const cv = bakeGrade(); const im = cv.getContext('2d').getImageData(0, 0, W, H).data;"
    " const a = []; for (const [x, y] of %s) { const i = (y * W + x) * 4; a.push(im[i], im[i + 3]); } return a; })()" % (jsPairs(_GRAD_SAMPLE),),
    "gradeArr()",
)
P(
    "bld.acc",
    "(() => { const a = []; for (const b of bMid.concat(bNear)) {"
    " const cv = bakeBuilding(b);"
    " a.push(cv._skirt.frac, cv._skirt.a, cv._feather.w, cv._feather.top, cv._inks.length, cv._carves.length);"
    " for (const c of cv._carves) a.push(c.x, c.y, c.w, c.h); } return a; })()",
    "bldAccArr()",
)
P(
    "person.bh",
    "[0, 1, 2, 3].map(k => bakePerson(k)._bh)",
    "doubleArrayOf(" + ", ".join(
        "TripodFogBake.bakePerson(%d).bh" % k for k in range(4)) + ")",
)

# ─────────────────────────── node 驱动（在原型作用域内求值） ───────────────────────────
DRIVER = r"""
// ═══════════════ 探针驱动（跑在原型 IIFE 作用域里，⛔ 不含 UI / 位图） ═══════════════
// 位图归 K5：把 48 栋的 _skirt 归零，让 K3/K4 侧读到与 Kotlin 默认值一致的 0。
for (const b of bMid) b.bmp._skirt = { frac: 0, a: 0 };
for (const b of bNear) b.bmp._skirt = { frac: 0, a: 0 };
const OUT = {};
const put = (k, v) => { OUT[k] = v; };
const aimDump = (a) => [a.t, a.d, a.dist, a.G, a.s, a.mx, a.psi, a.bodyY, a.roll, a.bodyLat,
  a.leanFwd, a.headYaw, a.dPsiLag, a.gland.x, a.gland.y, a.gland.z, a.hitFwd, a.beamSw, a.fire,
  a.beamI, a.beamSwV, a.beamRaw,
  a.beams[0].ex, a.beams[0].ey, a.beams[0].ez, a.beams[0].hx, a.beams[0].hz, a.beams[0].depression,
  a.beams[1].ex, a.beams[1].ey, a.beams[1].ez, a.beams[1].hx, a.beams[1].hz, a.beams[1].depression];
const sceneDump = (st) => {
  const o = aimDump(st);
  o.push(st.yaw, st.C, st.vWalk, st.camSpeed, st.tilt, st.D0);
  for (const l of st.legs) o.push(l.th, l.swing ? 1 : 0, l.u, l.q, l.lift, l.lat, l.fx, l.fy, l.fz, l.imp);
  for (const x of st.bld) o.push(x.id, x.x, x.z, x.w, x.h, x.dep, x.band, x.a, x.ink, x.sk.frac, x.sk.a);
  o.push(st.grab.reach, st.grab.lift);
  for (const c of st.crowd) o.push(c.i, c.victim ? 1 : 0, c.w, c.x, c.y, c.z, c.dist, c.kind, c.a);
  o.push(st.shakeX, st.shakeY, st.joltX, st.joltY, st.joltK, st.fogPhA, st.fogPhB, st.breathPh, st.poolR);
  return o;
};
const poseDump = (p) => {
  const o = [];
  for (const q of p.hips) o.push(q.x, q.y, q.z);
  for (const q of p.knees) o.push(q.x, q.y, q.z);
  for (const q of p.feet) o.push(q.x, q.y, q.z);
  return o;
};
__PROBES__
console.log('###JSON###' + JSON.stringify(OUT));
"""

# DOM / canvas 桩（与 docs/archive/verification/scripts/e46_fog_harness.js 同一套，⛔ 另写一套）
STUBS = r"""
const elements = new Map();
function mkEl(id, attrs) {
  const el = { id: id, value: '', checked: false, min: attrs.min, max: attrs.max, step: attrs.step,
    textContent: '', innerHTML: '', scrollTop: 0, scrollHeight: 0, dataset: {},
    listeners: {}, onclick: null,
    addEventListener: (t, f) => { (el.listeners[t] = el.listeners[t] || []).push(f); } };
  elements.set(id, el);
  return el;
}
function mkCanvas(w0, h0) {
  const cv = { _w: w0, _h: h0, _img: new Uint8ClampedArray(w0 * h0 * 4), toDataURL: () => 'data:,' };
  // ⭐ width/height 必须是**访问器**：原型 bakeFogStrip/bakeGrade/bakeBuilding/bakePerson 全是
  //    `document.createElement('canvas')`（桩里给 300×150）后立刻 `cv.width = w; cv.height = h`。
  //    写成普通属性 ⇒ _img 停在 300×150，putImageData 的越界写被 TypedArray **静默丢弃**，
  //    getImageData 读回一片 0（K5 的雾条/grade 黄金对账会全盘对不上，且看不出是桩的错）。
  Object.defineProperty(cv, 'width', { get() { return cv._w; },
    set(v) { cv._w = v; cv._img = new Uint8ClampedArray(v * cv._h * 4); } });
  Object.defineProperty(cv, 'height', { get() { return cv._h; },
    set(v) { cv._h = v; cv._img = new Uint8ClampedArray(cv._w * v * 4); } });
  cv.getContext = function () { return mkCtx(cv); };
  return cv;
}
function mkCtx(cv) {
  const grad = () => ({ addColorStop: () => {} });
  return {
    canvas: cv, fillStyle: '#000', strokeStyle: '#000', lineWidth: 1, globalAlpha: 1,
    globalCompositeOperation: 'source-over', filter: 'none', font: '10px',
    save: () => {}, restore: () => {}, translate: () => {}, scale: () => {}, rotate: () => {},
    beginPath: () => {}, closePath: () => {}, moveTo: () => {}, lineTo: () => {},
    arc: () => {}, ellipse: () => {}, rect: () => {}, quadraticCurveTo: () => {}, bezierCurveTo: () => {},
    fill: () => {}, stroke: () => {}, clip: () => {}, clearRect: () => {},
    fillRect: () => {}, strokeRect: () => {}, drawImage: () => {},
    createLinearGradient: grad, createRadialGradient: grad, createConicGradient: grad,
    createPattern: () => null, measureText: (s) => ({ width: String(s).length * 6 }),
    createImageData: (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) }),
    putImageData: (im, x, y) => {
      const W = cv.width, H = cv.height, d = im.data;
      for (let j = 0; j < im.height; j++) {
        const yy = y + j; if (yy < 0 || yy >= H) continue;
        for (let i = 0; i < im.width; i++) {
          const xx = x + i; if (xx < 0 || xx >= W) continue;
          const s = (j * im.width + i) * 4, t = (yy * W + xx) * 4;
          cv._img[t] = d[s]; cv._img[t + 1] = d[s + 1]; cv._img[t + 2] = d[s + 2]; cv._img[t + 3] = d[s + 3];
        }
      }
    },
    getImageData: (x, y, w, h) => {
      const W = cv.width, H = cv.height, out = new Uint8ClampedArray(w * h * 4);
      for (let j = 0; j < h; j++) {
        const yy = y + j;
        for (let i = 0; i < w; i++) {
          const xx = x + i, s = (j * w + i) * 4;
          if (xx < 0 || xx >= W || yy < 0 || yy >= H) { out[s] = 0; out[s + 1] = 0; out[s + 2] = 0; out[s + 3] = 0; continue; }
          const t = (yy * W + xx) * 4;
          out[s] = cv._img[t]; out[s + 1] = cv._img[t + 1]; out[s + 2] = cv._img[t + 2]; out[s + 3] = cv._img[t + 3];
        }
      }
      return { width: w, height: h, data: out };
    },
  };
}
const canvasTag = __CANVAS_TAG__;
const cv = mkCanvas(canvasTag[1], canvasTag[2]);
const cvEl = mkEl('cv', {});
cvEl.getContext = cv.getContext; cvEl.width = cv.width; cvEl.height = cv.height;
const rafQueue = [];
// ⚠️ getElementById **按需自动建档**：initUI 给 ~60 个滑杆 + 一堆按钮逐个 bindSlider，
//    桩里只预建 cv 一个 ⇒ 其余全拿到 null，boot 在第 6 行就炸（`el.value = ...`）。
//    原型注释（:5113）明说沙箱的 el 只是普通对象、**不夹断** min/max/step
//    ⇒ 自动建档（min/max/step = undefined）与设计意图一致，⛔ 另搬一份 markup 量程进来。
const document = {
  getElementById: (id) => {
    let el = elements.get(id);
    if (!el) el = mkEl(id, {});
    return el;
  },
  querySelectorAll: () => [],
  createElement: (tag) => (tag === 'canvas' ? mkCanvas(300, 150) : mkEl('_' + Math.random(), {})),
  addEventListener: () => {},
};
const window = { devicePixelRatio: 1, addEventListener: () => {} };
const sandbox = {
  document: document, window: window, Math: Math, JSON: JSON, Date: Date,
  console: console, performance: { now: () => Date.now() },
  requestAnimationFrame: (cb) => { rafQueue.push(cb); return rafQueue.length; },
  cancelAnimationFrame: () => {}, setTimeout: setTimeout, clearTimeout: clearTimeout,
};
sandbox.window.document = document;
"""


def kd(v):
    """JSON 数 → Kotlin Double 字面量（⛔ 丢精度）。"""
    if isinstance(v, bool):
        raise AssertionError("bool 不该进 DoubleArray")
    if isinstance(v, int):
        return "%d.0" % v
    s = repr(float(v))
    if "e" in s:
        m, e = s.split("e")
        if "." not in m:
            m += ".0"
        s = m + "e" + e
    elif "." not in s:
        s += ".0"
    return s


def main():
    log = []

    def say(s):
        log.append(s)

    html = io.open(HTML, encoding="utf-8").read()
    sm = html.index("<script>")
    se = html.rindex("</script>")
    script = html[sm + len("<script>"):se]
    ct = re.search(r'<canvas id="cv" width="(\d+)" height="(\d+)"', html)
    if not ct:
        raise SystemExit("未找到 <canvas id=cv>")
    probes = "\n".join("put(%r, %s);" % (k, js) for k, js, _ in PROBES)
    driver = DRIVER.replace("__PROBES__", probes)
    # ⭐ 驱动必须**拼进原型 IIFE 里面**（在 `boot();` 之后、`})();` 之前）：
    #    脚本是 `(function(){ 'use strict'; ... })()`，里面的一切 let/const 都是 IIFE 作用域，
    #    ⛔ 另起一次 runInContext 读它们（ReferenceError: bMid is not defined）。
    idx = script.rindex("})();")
    merged = script[:idx] + driver + "\n" + script[idx:]
    src = (
        "const fs = require('fs');\nconst vm = require('vm');\n"
        + STUBS.replace("__CANVAS_TAG__", "[0, %s, %s]" % (ct.group(1), ct.group(2)))
        + "\nvm.createContext(sandbox);\n"
        + "vm.runInContext(%s, sandbox, { filename: 'e46-tripod-fog-preview.html:script' });\n" % json.dumps(merged)
    )
    io.open(HARNESS, "w", encoding="utf-8").write(src)
    say("harness 已写 %s（%d 字节）" % (HARNESS, len(src)))

    r = subprocess.run([NODE, HARNESS], capture_output=True, text=True, encoding="utf-8")
    out = r.stdout or ""
    if "###JSON###" not in out:
        say("harness 运行失败：")
        say(out[-3000:])
        say((r.stderr or "")[-3000:])
        raise SystemExit("harness 运行失败")
    data = json.loads(out[out.rindex("###JSON###") + len("###JSON###"):].strip())
    io.open(GOLDEN, "w", encoding="utf-8").write(json.dumps(data, ensure_ascii=False, indent=1))
    say("探针 %d 条，JSON 已写 %s" % (len(data), GOLDEN))
    emit_kotlin(data)
    total = sum(len(v) for v in data.values())
    say("已写 %s（%d 组探针 / %d 个字段）" % (KT, len(data), total))
    io.open(LOG, "w", encoding="utf-8").write("\n".join(log) + "\n")


def emit_kotlin(data):
    L = []
    w = L.append
    w("package com.nasmusic.tv.visualizer.renderers")
    w("")
    w("import org.junit.Assert.assertEquals")
    w("import org.junit.Before")
    w("import org.junit.Test")
    w("")
    w("/**")
    w(" * E46 K3/K4/K5 **对账黄金测试** —— 从冻结的浏览器原型取权威期望值，逐字段核对 Kotlin 移植。")
    w(" *")
    w(" * ⭐ 生成方式（⛔ 手改本文件）：`python docs/archive/verification/scripts/e46_golden_gen.py`")
    w(" *   1. 在 Node vm 里桩掉 DOM/canvas，载入 `docs/e46-tripod-fog-preview.html` 的**原文脚本**")
    w(" *      （`boot()` 因此完整跑过：layout → buildCum → setGrabKnots → syncRouteEpoch(true) → genBuildings → bakeAll）；")
    w(" *   2. 把 48 栋位图的 `_skirt` 归零（位图归 K5，几何与数学归 K3/K4 ⇒ 两边都读 0）；")
    w(" *   3. 探针驱动在**原型作用域内**求值，输出 `logs_temp/e46_golden.json`；")
    w(" *   4. 本文件的期望值逐字来自那份 JSON，实际值调 K3/K4/K5 的真实函数。")
    w(" *")
    w(" * ⚠️ 容差 1e-9（绝对）：两边都是 IEEE double 且运算顺序一致，但 `sin/cos/exp/pow` 在")
    w(" *    V8 与 HotSpot 上不保证逐位相同（实现不同的 fdlibm）⇒ ⛔ 用 0.0 容差。")
    w(" * ⚠️ `scene.*` / `fp*` 读的是 world 上的**预分配缓冲**（R12）⇒ 每条探针各自算、立刻比，")
    w(" *    ⛔ 先攒一批 scene 再比（读到的是最后一帧）。")
    w(" * ⚠️ `bld` 的 `skirtFrac/skirtA` 恒 0：setup 烘完当场归零（JS 侧同样把 `_skirt` 归零）；而**几何走生产 `TripodFogBake.genBuildings`**——两带共用一支 LCG 的保真度靠本文件守。")
    w(" * ⚠️ K5 的 `fogA/fogB`（雾条逐像素 + T6 四条判据）、`grade`（暗角+颗粒逐像素）是**逐位**")
    w(" *    对账（含 `Uint8ClampedArray` 的 ties-to-even 量化）；`bld.acc` 只对账**记账值**")
    w(" *    （雾裙/羽化的实际落笔值 + carves 矩形）—— 多边形 AA 与 canvas 栅格化器有意不同。")
    w(" */")
    w("internal class TripodFogGoldenTest {")
    w("")
    w("    private lateinit var world: TripodFogWorld")
    w("")
    w("    @Before")
    w("    fun setup() {")
    w("        world = TripodFogWorld()")
    w("        TripodFogMath.layout(world, 960.0, 540.0)")
    w("        TripodGait.buildCum(world)")
    w("        TripodFogMath.setGrabKnots(world)")
    w("        TripodFogMath.syncRouteEpoch(world, true)")
    w("        prodBuildings(world)")
    w("        // ⭐ initUI 的 after 回调复刻（滑杆本身是 P→el→P 空往返，⛔ 漏掉 after）：")
    w("        //   sHZ→layout / sST→buildCum / sSKH+sSKA→genBuildings / sRS+sRL+sRV→syncRouteEpoch(true)")
    w("        //   / sGT→setGrabKnots。前四个是重算（sSKH+sSKA 那一路要**重烘 48 栋**，⚠️ 不是空转，")
    w("        //   但归零回填值后与 boot 等价），后三个各走一次 applyVariant(force=true)")
    w("        //   ⇒ varSwitches 从 1 变 4 —— `state` 探针读它，⛔ 只复刻 boot 会对不上。")
    w("        TripodFogMath.layout(world, 960.0, 540.0)")
    w("        TripodGait.buildCum(world)")
    w("        prodBuildings(world)")
    w("        prodBuildings(world)")
    w("        TripodFogMath.syncRouteEpoch(world, true)")
    w("        TripodFogMath.syncRouteEpoch(world, true)")
    w("        TripodFogMath.syncRouteEpoch(world, true)")
    w("        TripodFogMath.setGrabKnots(world)")
    w("    }")
    w("")
    w("    // ─────────────────── 探针的扁平化（与 JS 侧 sceneDump/aimDump 同序） ───────────────────")
    w("")
    w("    private fun aimArr(a: AimState): DoubleArray = doubleArrayOf(")
    w("        a.t, a.d, a.dist, a.g, a.s, a.mx, a.psi, a.bodyY, a.roll, a.bodyLat,")
    w("        a.leanFwd, a.headYaw, a.dPsiLag, a.gland.x, a.gland.y, a.gland.z, a.hitFwd, a.beamSw, a.fire,")
    w("        a.beamI, a.beamSwV, a.beamRaw,")
    w("        a.beams[0].ex, a.beams[0].ey, a.beams[0].ez, a.beams[0].hx, a.beams[0].hz, a.beams[0].depression,")
    w("        a.beams[1].ex, a.beams[1].ey, a.beams[1].ez, a.beams[1].hx, a.beams[1].hz, a.beams[1].depression,")
    w("    )")
    w("")
    w("    // TripodScene 把 aimState 的字段**整份平铺**进来（R27）⇒ 同序再导一份（⛔ 逐字段重列）。")
    w("    private fun aimArr(st: TripodScene): DoubleArray = doubleArrayOf(")
    w("        st.t, st.d, st.dist, st.g, st.s, st.mx, st.psi, st.bodyY, st.roll, st.bodyLat,")
    w("        st.leanFwd, st.headYaw, st.dPsiLag, st.gland.x, st.gland.y, st.gland.z, st.hitFwd, st.beamSw, st.fire,")
    w("        st.beamI, st.beamSwV, st.beamRaw,")
    w("        st.beams[0].ex, st.beams[0].ey, st.beams[0].ez, st.beams[0].hx, st.beams[0].hz, st.beams[0].depression,")
    w("        st.beams[1].ex, st.beams[1].ey, st.beams[1].ez, st.beams[1].hx, st.beams[1].hz, st.beams[1].depression,")
    w("    )")
    w("")
    w("    private fun sceneArr(st: TripodScene): DoubleArray {")
    w("        val o = ArrayList<Double>()")
    w("        o.addAll(aimArr(st).toList())")
    w("        o.add(st.yaw); o.add(st.c); o.add(st.vWalk); o.add(st.camSpeed); o.add(st.tilt); o.add(st.d0)")
    w("        for (l in st.legs) {")
    w("            o.add(l.th); o.add(if (l.swing) 1.0 else 0.0); o.add(l.u); o.add(l.q); o.add(l.lift)")
    w("            o.add(l.lat); o.add(l.fx); o.add(l.fy); o.add(l.fz); o.add(l.imp)")
    w("        }")
    w("        for (i in 0 until st.bldMidN) {")
    w("            val x = st.bldMid[i]")
    w("            o.add(x.id.toDouble()); o.add(x.x); o.add(x.z); o.add(x.w); o.add(x.h); o.add(x.dep)")
    w("            o.add(x.band.toDouble()); o.add(x.a); o.add(x.ink); o.add(x.skirtFrac); o.add(x.skirtA)")
    w("        }")
    w("        for (i in 0 until st.bldNearN) {")
    w("            val x = st.bldNear[i]")
    w("            o.add(x.id.toDouble()); o.add(x.x); o.add(x.z); o.add(x.w); o.add(x.h); o.add(x.dep)")
    w("            o.add(x.band.toDouble()); o.add(x.a); o.add(x.ink); o.add(x.skirtFrac); o.add(x.skirtA)")
    w("        }")
    w("        o.add(st.grab.reach); o.add(st.grab.lift)")
    w("        for (i in 0 until st.crowdN) {")
    w("            val c = st.crowd[i]")
    w("            o.add(c.i.toDouble()); o.add(if (c.victim) 1.0 else 0.0); o.add(c.w.toDouble())")
    w("            o.add(c.x); o.add(c.y); o.add(c.z); o.add(c.dist); o.add(c.kind.toDouble()); o.add(c.a)")
    w("        }")
    w("        o.add(st.shakeX); o.add(st.shakeY); o.add(st.joltX); o.add(st.joltY); o.add(st.joltK)")
    w("        o.add(st.fogPhA); o.add(st.fogPhB); o.add(st.breathPh); o.add(st.poolR)")
    w("        return o.toDoubleArray()")
    w("    }")
    w("")
    w("    private fun ctabArr(): DoubleArray {")
    w("        val tb = TripodGait.crowdTable(world)")
    w("        val o = ArrayList<Double>()")
    w("        for (p in tb) {")
    w("            o.add(p.i.toDouble()); o.add(if (p.victim) 1.0 else 0.0)")
    w("            o.add(p.z0); o.add(p.x); o.add(p.kind.toDouble())")
    w("        }")
    w("        return o.toDoubleArray()")
    w("    }")
    w("")
    w("    private fun cidentArr(): DoubleArray {")
    w("        val o = ArrayList<Double>()")
    w("        val out = TripodGait.CrowdIdent()")
    w("        for (i in 0 until 4) for (wq in 0 until 3) {")
    w("            TripodGait.crowdIdent(world, i, wq, out)")
    w("            o.add(out.x); o.add(out.kind.toDouble())")
    w("        }")
    w("        return o.toDoubleArray()")
    w("    }")
    w("")
    w("    private fun tendrilArr(t: Double): DoubleArray {")
    w("        val st = TripodGait.sceneState(world, t)")
    w("        val v = st.crowd[0]")
    w("        val ctx = TripodFogMath.TendrilCtx(")
    w("            bodyLat = st.bodyLat, leanFwd = st.leanFwd, bodyY = st.bodyY,")
    w("            psi = st.psi, d = st.d, mx = st.mx,")
    w("            bodyRx = world.params.bodyRx, personH = world.params.personH,")
    w("            tendReach = world.params.tendReach,")
    w("            victimX = v.x, victimY = v.y, victimZ = v.z,")
    w("            reach = st.grab.reach,")
    w("        )")
    w("        val r1 = TripodFogMath.tendrilRoot(ctx, -0.62); val t1 = TripodFogMath.tendrilTip(ctx, -0.62)")
    w("        val r2 = TripodFogMath.tendrilRoot(ctx, 0.62); val t2 = TripodFogMath.tendrilTip(ctx, 0.62)")
    w("        return doubleArrayOf(")
    w("            r1.x, r1.y, r1.z, t1.x, t1.y, t1.z, t1.D, t1.L,")
    w("            r2.x, r2.y, r2.z, t2.x, t2.y, t2.z, t2.D, t2.L,")
    w("        )")
    w("    }")
    w("")
    w("    /**")
    w("     * 原型的**整条楼场生成**：走生产入口 `TripodFogBake.genBuildings`（连 `bakeBuilding` 一起），")
    w("     * ⛔ 不许在这里另写一份几何抽样（那会再立一个事实源，E44 D24）。")
    w("     *")
    w("     * ⭐ 用生产函数而不是复刻抽样，81 条探针才会**真的吃到** `mk()` 的 r() 顺序：两条带必须共用同一支")
    w("     *    `Lcg(20261011)`（原型在 `genBuildings` 函数体里建一次，`mk` 是它的闭包）。曾经每条带各建一支 ⇒")
    w("     *    **近景带从第一个数就换一批种子**，整条前景天际线换一批楼，而本文件当时用自己的副本生成 ⇒ 一路")
    w("     *    绿灯，最后是 T14 的负样本才把它咬出来（D19 的近景带位置前提）。")
    w("     *")
    w("     * ⚠️ `bakeBuilding` 会把 `skirtFrac/skirtA/featherW/featherTop` **回填进建筑**，而 `scene.*` 探针要求")
    w("     *    它们恒 0（位图归 K5 的口径，JS 侧同样把 48 栋的 `_skirt` 归零）⇒ 烘完当场归零。")
    w("     */")
    w("    private fun prodBuildings(world: TripodFogWorld) {")
    w("        TripodFogBake.genBuildings(world, world.params)")
    w("        for (b in world.bMid + world.bNear) {")
    w("            b.skirtFrac = 0.0")
    w("            b.skirtA = 0.0")
    w("            b.featherW = 0.0")
    w("            b.featherTop = 0.0")
    w("        }")
    w("    }")
    w("")
    w("    // ─────────────── K5 烘焙层（原型 bakeFogStrip / bakeGrade / bakeBuilding） ───────────────")
    w("    // ⚠️ 与 JS 探针**严格同序**（_FOG_SAMPLE / _GRAD_SAMPLE 两张表在伴生对象里）。")
    w("    // ⛔ 就地烘 world.bMid/bNear：bakeBuilding 会把 skirtFrac/skirtA/featherW/featherTop")
    w("    //    **回填进建筑**，而 scene.* 探针要求它们恒 0（位图归 K5、几何归 K4 的口径）⇒")
    w("    //    每栋先复制再烘，回填写到副本上。")
    w("")
    w("    private fun fogAArr(): DoubleArray = fogStripArr(256, 300, 3, 7717, FOG_SAMPLE_A)")
    w("")
    w("    private fun fogBArr(): DoubleArray = fogStripArr(192, 300, 4, 3391, FOG_SAMPLE_B)")
    w("")
    w("    /** 一条雾条的 `[seam, seamBad, step, stepBad]` + 采样点的 RGBA（打包 ARGB 拆回）。 */")
    w("    private fun fogStripArr(w: Int, h: Int, cells: Int, seed: Int, smp: IntArray): DoubleArray {")
    w("        val b = TripodFogBake.bakeFogStrip(w, h, cells, seed, 5, 1.0)")
    w("        val o = ArrayList<Double>(4 + smp.size * 2)")
    w("        o.add(b.seam); o.add(b.seamBad); o.add(b.step); o.add(b.stepBad)")
    w("        var s = 0")
    w("        while (s < smp.size) {")
    w("            val p = b.px[smp[s + 1] * b.w + smp[s]]")
    w("            o.add(((p ushr 16) and 0xFF).toDouble())")
    w("            o.add(((p ushr 8) and 0xFF).toDouble())")
    w("            o.add((p and 0xFF).toDouble())")
    w("            o.add(((p ushr 24) and 0xFF).toDouble())")
    w("            s += 2")
    w("        }")
    w("        return o.toDoubleArray()")
    w("    }")
    w("")
    w("    /** grade 位图 8 个采样点的 (R, A)（原型 bakeGrade 读全局 W=960/H=540/cx=480；⛔ 棱线 D31）。 */")
    w("    private fun gradeArr(): DoubleArray {")
    w("        val px = TripodFogBake.bakeGrade(960, 540, 480.0, world.params.grain)")
    w("        val o = ArrayList<Double>(16)")
    w("        var s = 0")
    w("        while (s < GRAD_SAMPLE.size) {")
    w("            val p = px[GRAD_SAMPLE[s + 1] * 960 + GRAD_SAMPLE[s]]")
    w("            o.add(((p ushr 16) and 0xFF).toDouble())")
    w("            o.add(((p ushr 24) and 0xFF).toDouble())")
    w("            s += 2")
    w("        }")
    w("        return o.toDoubleArray()")
    w("    }")
    w("")
    w("    /**")
    w("     * 48 栋的烘焙记账（⛔ 不含像素：多边形 AA 与 canvas 栅格化器**有意**不同，见 K5 文件头）：")
    w("     * 每栋 `[skirtFrac, skirtA, featherW, featherTop, inks.size, carves.size, carves×4]`。")
    w("     */")
    w("    private fun bldAccArr(): DoubleArray {")
    w("        val o = ArrayList<Double>()")
    w("        for (b in world.bMid) bldAcc1(o, b)")
    w("        for (b in world.bNear) bldAcc1(o, b)")
    w("        return o.toDoubleArray()")
    w("    }")
    w("")
    w("    private fun bldAcc1(o: ArrayList<Double>, src: TripodBuilding) {")
    w("        val b = TripodBuilding()")
    w("        b.i = src.i; b.pool = src.pool; b.x = src.x; b.z0 = src.z0")
    w("        b.w = src.w; b.h = src.h; b.dep = src.dep")
    w("        b.seed = src.seed; b.k = src.k")
    w("        val cv = TripodFogBake.bakeBuilding(b, world.params)")
    w("        o.add(cv.skirtFrac); o.add(cv.skirtA); o.add(cv.featherW); o.add(cv.featherTop)")
    w("        o.add(cv.inks.size.toDouble()); o.add(cv.carves.size.toDouble())")
    w("        for (c in cv.carves) { o.add(c.x); o.add(c.y); o.add(c.w); o.add(c.h) }")
    w("    }")
    w("")
    w("    private companion object {")
    w("        /** fingerprint 的上界：58 + 4×(48+48)（单带容量 48）。 */")
    w("        val FPBUF = DoubleArray(58 + 4 * 96)")
    w("")
    w("        /** K5 雾条 A 采样表（与 JS `_FOG_SAMPLE_A` 同序同值，⛔ 手改）。 */")
    w("        val FOG_SAMPLE_A = intArrayOf(")
    w("            0, 0, 1, 0, 7, 3, 63, 17, 128, 88, 200, 42, 255, 0, 0, 299,")
    w("            255, 299, 37, 199, 191, 250, 13, 130, 240, 7, 99, 66, 5, 288, 171, 121,")
    w("        )")
    w("")
    w("        /** K5 雾条 B 采样表（与 JS `_FOG_SAMPLE_B` 同序同值；B 只有 192 宽，⛔ 与 A 共用）。 */")
    w("        val FOG_SAMPLE_B = intArrayOf(")
    w("            0, 0, 1, 0, 7, 3, 63, 17, 128, 88, 100, 42, 191, 0, 0, 299,")
    w("            191, 299, 37, 199, 95, 250, 13, 130, 120, 7, 99, 66, 5, 288, 171, 121,")
    w("        )")
    w("")
    w("        /** K5 grade 采样表（与 JS `_GRAD_SAMPLE` 同序同值，⛔ 手改）。 */")
    w("        val GRAD_SAMPLE = intArrayOf(0, 0, 479, 269, 959, 539, 17, 530, 480, 0, 300, 300, 700, 100, 5, 5)")
    w("    }")
    w("")
    # 黄金数据
    w("    // ─────────────────── 黄金数据（原型真值，⛔ 手改） ───────────────────")
    w("    private val GOLDEN: Map<String, DoubleArray> = mapOf(")
    for key, vals in data.items():
        w('        "%s" to doubleArrayOf(' % key)
        for i in range(0, len(vals), 8):
            w("            " + ", ".join(kd(v) for v in vals[i:i + 8]) + ",")
        w("        ),")
    w("    )")
    w("")
    # 求值派发
    w("    /** 按 key 调 K3/K4/K5 的真实函数（⛔ 与黄金数据各写各的 —— 那比对就没意义了）。 */")
    w("    private fun actual(key: String): DoubleArray = when (key) {")
    for key, _, kt in PROBES:
        w('        "%s" -> %s' % (key, kt))
    w("        else -> error(" + '"未实现的探针：$key"' + ")")
    w("    }")
    w("")
    w("    @Test")
    w("    fun golden() {")
    w("        var n = 0")
    w("        for ((key, expected) in GOLDEN) {")
    w("            val act = actual(key)")
    w("            assertEquals(" + '"$key 字段数"' + ", expected.size, act.size)")
    w("            for (i in expected.indices) {")
    w("                assertEquals(" + '"$key[$i]"' + ", expected[i], act[i], 1e-9)")
    w("            }")
    w("            n += expected.size")
    w("        }")
    w("        println(" + '"E46 黄金对账：${GOLDEN.size} 组探针 / $n 个字段全部命中（容差 1e-9）"' + ")")
    w("    }")
    w("}")
    io.open(KT, "w", encoding="utf-8").write("\n".join(L) + "\n")


if __name__ == "__main__":
    try:
        main()
    except SystemExit as e:
        io.open(LOG, "a", encoding="utf-8").write("\n退出：%s\n" % e)
        raise
    except Exception:
        io.open(LOG, "a", encoding="utf-8").write("\n" + traceback.format_exc())
        raise
