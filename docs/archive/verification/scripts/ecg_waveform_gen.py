#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E24 心跳 —— HB 波形表的**唯一再生源**（§E44「数字只能由脚本再生，⛔ 不许手抄」）。

⛔ 改了本文件里的任何临床参数，都必须重跑本脚本，并把 `EcgWaveRenderer.HB`
  与 `EcgWaveformTest` 里引用到的派生数字**一起**提交。
  把数抄进 Kotlin 注释或测试就是偏差 D24 的重演。

## 用法

    python ecg_waveform_gen.py                    # 打印 Kotlin HB 字面量
    python ecg_waveform_gen.py --check            # 表格自身的形态学自检
    python ecg_waveform_gen.py --json out.json    # 供渲染对比工具读取

## 采样网格

波形**一列一个采样、无插值**（`EcgWaveRenderer.heartbeatAt` 是 O(1) 整数索引）。
列宽由 `SPEED_COLS_PER_SEC` 决定 ⇒ 改速度必须重跑本脚本并重建表。

    列宽 = 1000 / 90 ms = 11.111 ms
    复合波 P 起(col0) → T 终(col50) = 51 列 = 566.7 ms

⛔ **地标一律以「列」定义，ms 是派生量。** 因为采样点就是列本身（t = i × 列宽），
  这样定义才能保证 R 峰**精确落在某一列**且取值恰为 1.000 —— 若按 ms 定义再取整列，
  采样点会偏离峰顶（实测把 1.000 削成 0.980，见 §5.1 第 1 条「不要点采样陡峭上冲支」）。

## 临床依据（R 归一化为 1.0，Ⅱ导联 / 左胸导联形态）

| 地标   | 列 | ms      | 临床值核对 |
|--------|----|---------|-----------|
| P 起   | 0  | 0       | 基准 |
| P 峰   | 4  | 44.4    | P 宽 88.9ms，峰在 50%（Cooksey：宽 80–100ms） |
| P 终   | 8  | 88.9    | — |
| QRS 起 | 14 | 155.6   | **PR 间期 155.6ms**（正常 120–200，均值 160） |
| Q 谷   | 16 | 177.8   | Q 宽 22.2ms（**<40ms 且锐利**，Bayés de Luna / Utah） |
| R 峰   | 18 | 200.0   | **R 峰时 44.4ms = QRS 的 47%**（AHA：左胸导联 ≤45ms） |
| S 谷   | 21 | 233.3   | R→S 33.3ms |
| J 点   | 22 | 244.4   | **QRS 宽 88.9ms**（正常 80–100） |
| T 起   | 26 | 288.9   | ST 段 44.4ms |
| T 峰   | 40 | 444.4   | **J→T峰 200.0ms** |
| T 终   | 50 | 555.6   | **QT = 555.6 − 155.6 = 400.0ms**（ecg.utah：60bpm ≤400ms） |

**幅度**（Winsor 1956 / AHA：Ⅱ导联 R≈7.1mm、S≈1.2mm、P≈1.0mm；T 为 R 的 1/4~1/3）

| 波 | ×R | 正常范围 |
|----|-----|---------|
| P  | +0.10 | 0.07–0.15 |
| Q  | −0.15 | ≤0.25（窄而尖） |
| R  | +1.00 | 基准 |
| S  | −0.17 | −0.10 ~ −0.25 |
| T  | +0.25 | +0.20 ~ +0.30 |

**T 波不对称**（Hurtado 2013 的 59:38 形状比；ecg.utah：「slow ascending ramp and
faster descending ramp」）：升 155.6ms / 降 111.2ms = **1.40 : 1**，由临床地标
（T 起 26 → T 峰 40 → T 终 50）直接给出。

⛔ **不要把 T 做成对称余弦** —— 那是「不像心电图」最常见的来源。

## 形状函数

波峰一律用**零端斜率的升余弦**，⛔ 不是高斯：高斯在基线处**没有有限支撑**，
采样点落在尾部就会留下永不归零的「小丘」，PR/ST 段就不再是等电位平线了。

波与波之间用**线性**过渡（⛔ 不用 smoothstep）：smoothstep 会把波界的水平切线
「抹圆」，从线性插值的 0 斜率变成 ≈1.87 斜率，波间平段会拱起来。
"""
import json
import math
import sys

try:  # Windows 控制台默认 GBK —— 显式切 UTF-8，否则打印 ⛔/中文直接崩
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ── 采样网格 ────────────────────────────────────────────────────────────────
SPEED_COLS_PER_SEC = 90.0                 # 唯一真源：EcgWaveRenderer.SPEED_COLS_PER_SEC
COL_MS = 1000.0 / SPEED_COLS_PER_SEC      # 11.111 ms
MIN_GAP_COLS = 72                         # EcgWaveRenderer.MIN_GAP_COLS（丢拍窗口）

# ── 临床地标（以列定义；ms = 列 × COL_MS）──────────────────────────────────
P_ONSET_COL   = 0
P_APEX_COL    = 4
P_END_COL     = 8
QRS_ONSET_COL = 14                        # PR 间期起点
Q_ANCHOR_COL  = 14                        # QRS 起始处的等电位锚点（Q 窄而锐 ⇒ 需要下坡进谷）
Q_NADIR_COL   = 16
R_PEAK_COL    = 18                        # ⛔ 必须整列，峰值才恰为 1.000
S_NADIR_COL   = 21
J_COL         = 22                        # J 点 / QRS 终
T_ONSET_COL   = 26
T_APEX_COL    = 40
T_END_COL     = 50

# ── 临床幅度（R = 1.0 归一）────────────────────────────────────────────────
A_P = 0.10
A_Q = -0.15
A_R = 1.00
A_S = -0.17
A_T = 0.25

# ── 形态学自检阈值（与 Kotlin 门禁 EcgWaveformTest 同口径）──────────────────
SUPPORT_REL = 0.04        # 有效支撑 = 超过「自身峰值 4%」的列
T_OVER_QRS_MIN = 2.0      # 临床 T(270ms)/QRS(89ms) ≈ 3.0；下限只卡「T 必须是宽圆丘」
T_OVER_QRS_MAX = 4.0      # 上限防止 T 退化成一段长弧


def ms(col):
    return col * COL_MS


def bump(t, t0, tp, t1, amp):
    """
    零端斜率 + 零顶斜率的不对称升余弦波。
    t0→tp 升（amp/2·(1−cosπ·u)），tp→t1 降；区间外恒 0。
    ⛔ `tp` 传几何中点即退化为对称波。
    """
    if t < t0 or t > t1 or amp == 0:
        return 0.0
    if t <= tp:
        span = tp - t0
        if span <= 0:
            return amp
        return amp * 0.5 * (1.0 - math.cos(math.pi * (t - t0) / span))
    span = t1 - tp
    if span <= 0:
        return amp
    return amp * 0.5 * (1.0 - math.cos(math.pi * (t1 - t) / span))


def lin(t, t0, v0, t1, v1):
    """两锚点之间线性过渡；区间外夹到最近端点。"""
    if t1 == t0:
        return v1
    k = (t - t0) / (t1 - t0)
    if k < 0.0:
        return v0
    if k > 1.0:
        return v1
    return v0 + (v1 - v0) * k


def piecewise(t):
    """分段波形求值。锚点按时间顺序排列。"""
    if t < Q_ANCHOR_COL:
        # P 波（之后到 QRS 起始保持等电位 0）
        return bump(t, P_ONSET_COL, P_APEX_COL, P_END_COL, A_P)
    if t < Q_NADIR_COL:
        return lin(t, Q_ANCHOR_COL, 0.0, Q_NADIR_COL, A_Q)      # Q：等电位下坡进谷
    if t < R_PEAK_COL:
        return lin(t, Q_NADIR_COL, A_Q, R_PEAK_COL, A_R)        # R：谷 → 峰（上升支）
    if t < S_NADIR_COL:
        return lin(t, R_PEAK_COL, A_R, S_NADIR_COL, A_S)        # S：峰 → 谷（下降支）
    if t < J_COL:
        return lin(t, S_NADIR_COL, A_S, J_COL, 0.0)             # 回基线 → J 点
    return bump(t, T_ONSET_COL, T_APEX_COL, T_END_COL, A_T)    # T：不对称宽圆丘


def build_table():
    """按显示网格逐列采样 ⇒ 一列一个点，与 `heartbeatAt(ageCols)` 完全对应。"""
    return [piecewise(i) for i in range(T_END_COL + 1)]


def support(cols, lo, hi, ref_peak):
    """有效支撑：区间内超过自身峰值 SUPPORT_REL 的列数。"""
    thr = abs(ref_peak) * SUPPORT_REL
    return sum(1 for i in range(lo, min(hi + 1, len(cols))) if abs(cols[i]) > thr)


def first_knee(cols, lo, eps=1e-6):
    """
    找「膝」：ST 水平段与 T 上升支的接缝。

    ⚠️ **不能用「二阶差分符号翻转」** —— 升余弦自身的曲率在 T 峰处就翻一次
    （见 [T 起 → T 峰] 段凸、[T 峰 → T 终] 段凹），翻出来的是**拐点**而不是接缝，
    会误报成 T 的中段。接缝的真实特征是「曲率从恒 0 跳到持续同号」，
    所以判据是：**首个 d2 非零且紧邻下一列同号**的列。
    """
    def d2(k):
        return cols[k - 1] - 2 * cols[k] + cols[k + 1]

    for i in range(lo + 1, len(cols) - 2):
        a, b = d2(i), d2(i + 1)
        if abs(a) > eps and abs(b) > eps and (a > 0) == (b > 0):
            return i
    return None


def validate(cols):
    out = []
    n = len(cols)
    out.append(("表长 < MIN_GAP", n, n < MIN_GAP_COLS,
                "%d 列（%.1f ms）< %d" % (n, n * COL_MS, MIN_GAP_COLS)))

    ri = max(range(n), key=lambda i: cols[i])
    peak = cols[ri]
    out.append(("R 峰整列满幅", peak, abs(peak - 1.0) < 1e-9 and ri == R_PEAK_COL,
                "峰值 %.6f @col%d" % (peak, ri)))

    li = min(range(n), key=lambda i: cols[i])
    out.append(("S 谷最深且在 R 后", cols[li], li > ri and abs(cols[li] - A_S) < 1e-9,
                "最小 %.6f @col%d" % (cols[li], li)))

    lo, hi = QRS_ONSET_COL, J_COL
    qrs = support(cols, lo, hi, peak)
    tpk = max(cols[T_ONSET_COL:])
    tw = support(cols, T_ONSET_COL, n, tpk)
    ratio = tw / qrs if qrs else 0.0
    out.append(("T/QRS 有效宽度比", ratio, T_OVER_QRS_MIN <= ratio <= T_OVER_QRS_MAX,
                "T=%d 列 / QRS=%d 列" % (tw, qrs)))

    # PR 段 = P 终后到 QRS 起始前；ST 段 = J 点后到 T 起前
    pr = cols[P_END_COL:QRS_ONSET_COL]
    st = cols[J_COL:T_ONSET_COL]
    flat = max(max(abs(v) for v in pr), max(abs(v) for v in st))
    out.append(("PR/ST 等电位", flat, flat < 1e-3, "PR=%d 列 ST=%d 列 max|v|=%.2e"
                % (len(pr), len(st), flat)))

    # T 不对称：上升段 : 下降段 列数比（临床 155.6 : 111.2 ≈ 1.40）
    up = sum(1 for i in range(T_ONSET_COL, T_APEX_COL) if cols[i] > cols[i - 1])
    dn = sum(1 for i in range(T_APEX_COL, n - 1) if cols[i + 1] < cols[i])
    ar = up / dn if dn else 0.0
    out.append(("T 升支:降支 > 1.2", ar, ar > 1.2, "升 %d 段 / 降 %d 段" % (up, dn)))

    # T 起点必须有「膝」：ST 的水平切线接上升余弦 ⇒ 曲率翻转（没有就是被抹圆了）
    k = first_knee(cols, J_COL)
    out.append(("T 起点曲率翻转", "col%s" % k, k is not None and k <= T_ONSET_COL + 1,
                "膝 @col%s（T 起 col%d）" % (k, T_ONSET_COL)))

    # Q 必须窄而锐：谷底只占 1 列（宽 <2 列 ⇒ <40ms）
    q_half = A_Q * 0.5          # ⛔ 不要写成 `-A_Q * 0.5`：A_Q 本身为负，取负号会把判据翻成正阈值
    qw = sum(1 for i in range(Q_ANCHOR_COL, R_PEAK_COL) if cols[i] < q_half)
    out.append(("Q 谷窄(<2列)", qw, qw <= 2, "%d 列在 Q 谷半深(%.3f)以下" % (qw, q_half)))
    return out


def main():
    cols = build_table()
    mode = sys.argv[1] if len(sys.argv) > 1 else ""

    if mode == "--check":
        print("表长 %d 列，表窗 %.1f ms（列宽 %.3f ms）" % (len(cols), len(cols) * COL_MS, COL_MS))
        print("临床地标(ms): P起%.0f P峰%.0f P终%.0f QRS起%.0f Q谷%.0f R峰%.0f S谷%.0f J%.0f T起%.0f T峰%.0f T终%.0f"
              % tuple(ms(c) for c in (P_ONSET_COL, P_APEX_COL, P_END_COL, QRS_ONSET_COL,
                                       Q_NADIR_COL, R_PEAK_COL, S_NADIR_COL, J_COL,
                                       T_ONSET_COL, T_APEX_COL, T_END_COL)))
        print("PR=%.1fms  QRS=%.1fms  QT=%.1fms  J→T峰=%.1fms  T升:降=%d:%d"
              % (ms(QRS_ONSET_COL), ms(J_COL) - ms(QRS_ONSET_COL),
                 ms(T_END_COL) - ms(QRS_ONSET_COL), ms(T_APEX_COL) - ms(J_COL),
                 T_APEX_COL - T_ONSET_COL, T_END_COL - T_APEX_COL))
        ok = True
        for name, val, passed, note in validate(cols):
            ok = ok and passed
            print("  %-22s %-12s %s   %s" % (name, val, "PASS" if passed else "**FAIL**", note))
        print("总体:", "PASS" if ok else "FAIL")
        return 0 if ok else 1

    if mode == "--json":
        payload = {
            "hb": [round(v, 6) for v in cols],
            "marks": [
                {"n": "P", "i": P_APEX_COL},
                {"n": "Q", "i": Q_NADIR_COL},
                {"n": "R", "i": R_PEAK_COL},
                {"n": "S", "i": S_NADIR_COL},
                {"n": "T", "i": T_APEX_COL},
            ],
        }
        with open(sys.argv[2], "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
        print("wrote", sys.argv[2])
        return 0

    # 默认：打印 Kotlin 字面量（10 个一行，与现有风格一致）
    print("// 由 ecg_waveform_gen.py 再生 ⛔ 不要手改（列宽 = 1000/%.0f ms）" % SPEED_COLS_PER_SEC)
    for start in range(0, len(cols), 10):
        row = ", ".join(("+" if v >= 0 else "") + "%.6ff" % v for v in cols[start:start + 10])
        print("            " + row + ("," if start + 10 < len(cols) else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())