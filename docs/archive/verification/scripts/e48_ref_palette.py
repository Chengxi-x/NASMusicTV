#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""E48 色调对账：把**参考图**与**原型渲染帧**放在同一口径下量，⛔ 靠肉眼说"太深了"。

用法：
    # 只出报告（诊断用）
    python docs/archive/verification/scripts/e48_ref_palette.py <参考图> [<参考图2> ...] --frame output/e48_frames/full_dune.png
    python docs/archive/verification/scripts/e48_ref_palette.py <参考图> --sun 0.40,0.33   # 日盘中心归一化坐标（可选）

    # 判绿（验收用）：⛔ 阈值手抄，全部从**参考图自己的实测数**推出来
    python docs/archive/verification/scripts/e48_ref_palette.py --gate <参考图> <帧1> [<帧2> ...] [--sun cx,cy,rFrac] [--sun-ref cx,cy,rFrac]
      · `--sun` 给**帧**的日盘（圆心归一化坐标 + 半径占**屏高**的比例，E48 定稿 = 0.112）；
        `--sun-ref` 给参考图自己的（两者画幅不同 ⇒ 归一化位置必然不同，⛔ 用同一组数去量两张图）。
      · 不给 `--sun-ref` 则退回 `--sun`（同口径），剖面判据会因参考图日心偏移而偏，仅供诊断。
      · 退出码 0 = 全绿；非 0 = 有判红（并打印是哪一条、差多少）。

输出四块（每张图）：
  1. 亮度阶梯：平均相对亮度 + 6 档亮度直方图占比 ⇒ "深/浅"的唯一口径
  2. 竖向 6 带均值：天空→地平线→地面的色相走向
  3. 主色 8 位（median-cut）+ 覆盖率
  4. 日盘剖面（若给 --sun）：从盘心向外 7 个采样的 RGB，判"黄白心→橙边→散光"的层次

⚠️ 只做**统计**，不做像素比对：参考图是照片、原型是程序化绘制，逐像素差没有意义；
   可比的只有"亮度阶梯"和"色相走向"两件事。
"""
import sys
import os
import math
from PIL import Image

BANDS = 6
NDOM = 8
# 日盘剖面的采样半径（×R）。⛔ 取 1.00R：那正好压在 arc 的边界上，量到的是**抗锯齿**而不是盘缘
# （实测同一张图 1.00R → L .268、0.90R → L .44，差出去一整档，四条盘判据全部失真）。
RADII = (0.0, 0.3, 0.6, 0.9, 1.2, 1.6, 2.0)

# ── 判绿阈值：只有这两类是**绝对**的，其余全部相对参考图现算 ──────────────────
# ① 暗部占比上限 6%：三轮裁决 ③「整个色调太深」的量化落点。
#    参考图 1 实测 2.8%；被否掉的上一版实测 23.2% ⇒ 6% 把两者分开且留了一倍余量。
DARK_MAX = 6.0
# ② 天顶带亮度下限 0.13：「太深」最先塌的就是天顶（旧版天顶 #3d1c2a ⇒ L .026）。
ZENITH_MIN = 0.13
# 逐带 / 均值的容差：照片有颗粒与景深，原型是程序化平滑 ⇒ 不追求相等，只求同一阶梯。
BAND_TOL = 0.045
MEAN_TOL = 0.025
# 日盘剖面的五条判据（见 gate()）：⛔ "单调下降"这类**看着像物理其实选错口径**的判据 ——
# 参考图的盘是"下金上白 + 盘缘陡降 + 盘外立刻平坦"，单调下降第一条就把它自己判红。
# 近平容差：参考图自己 1.3↔2.0 的差再放一倍（那一段的斜率是照片暗角，不是散光）。
FLAT_TOL = 0.022
# ⭐ 散光的**色相**容差（通道差，非亮度）：亮度全绿也可能把散光画成"糊了层奶黄雾"。
#   参考图盘外 1.2R 实测 (226,77,47) ⇒ R−G = 149（把天**烧红**）；奶黄式散光只到 ~107。
RED_TOL = 30.0


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def rel_lum(r, g, b):
    # Rec.709 线性加权前先做 sRGB 反伽马（照片与 Canvas 输出都是 sRGB）
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def hx(t):
    return '#%02x%02x%02x' % (int(t[0]), int(t[1]), int(t[2]))


def load(path):
    return Image.open(path).convert('RGB')


def measure(path, sun=None):
    """→ dict：一张图的全部可判据（报告与 --gate 共用同一份，⛔ 两套口径）。"""
    im = load(path)
    w, h = im.size
    small = im.resize((120, max(1, int(120 * h / w))), Image.BILINEAR)
    sw, sh = small.size
    px = list(small.getdata())

    lums = [rel_lum(*p) for p in px]
    mean = sum(lums) / len(lums)
    hist = [0] * 6
    for v in lums:
        hist[min(5, int(v * 6))] += 1
    dark = 100.0 * sum(1 for v in lums if v < 0.06) / len(lums)

    bands = []
    for b in range(BANDS):
        y0, y1 = int(sh * b / BANDS), int(sh * (b + 1) / BANDS)
        band = [small.getpixel((x, y)) for y in range(y0, y1) for x in range(sw)]
        r = sum(p[0] for p in band) / len(band)
        g = sum(p[1] for p in band) / len(band)
        bb = sum(p[2] for p in band) / len(band)
        bands.append({'rgb': (r, g, bb), 'L': sum(rel_lum(*p) for p in band) / len(band)})

    q = im.resize((160, max(1, int(160 * h / w)))).quantize(colors=NDOM, method=Image.MEDIANCUT)
    pal = q.getpalette()
    doms = []
    tot = sum(c for c, _ in (sorted(q.getcolors() or [], reverse=True))) or 1
    for c, idx in sorted(q.getcolors() or [], reverse=True):
        r, g, b = pal[idx * 3:idx * 3 + 3]
        doms.append({'rgb': (r, g, b), 'pct': 100.0 * c / tot, 'L': rel_lum(r, g, b)})

    prof = None
    if sun:
        cx, cy = sun[0], sun[1]
        # R 默认按"占屏高 30%"（老用法）；给第三个数则按**屏高比例** ⇒ 与原型参数同口径
        R = h * (sun[2] if len(sun) > 2 else 0.30)
        prof = []
        # ⚠️ 只采**上半环**（角度 180°…360° ⇒ y 在盘心之上）：日盘下缘按需求必须被远脊切掉 8–45%，
        #    整环均值会把"遮挡"量成"盘缘不亮"（实测：同一张图整环 .177 / 上半环 .336，
        #    而参考图因为切得浅、整环反而 .350 ⇒ 判据变成在比"谁挡得多"，⛔ 比的是盘缘亮度）。
        for k, fr in enumerate(RADII):
            rad = fr * R
            acc, n = [0.0, 0.0, 0.0], 0
            for a in range(180, 361, 15):
                x = int(cx * w + rad * math.cos(math.radians(a)))
                y = int(cy * h + rad * math.sin(math.radians(a)))
                if 0 <= x < w and 0 <= y < h:
                    p = im.getpixel((x, y))
                    acc[0] += p[0]; acc[1] += p[1]; acc[2] += p[2]; n += 1
            if not n:
                prof.append(None)
            else:
                v = tuple(t / n for t in acc)
                prof.append({'rgb': v, 'L': rel_lum(*v)})
    return {'path': path, 'w': w, 'h': h, 'mean': mean, 'hist': hist, 'n': len(lums),
            'p5': sorted(lums)[int(len(lums) * 0.05)], 'p95': sorted(lums)[int(len(lums) * 0.95)],
            'dark': dark, 'bands': bands, 'doms': doms, 'prof': prof}


def report(m):
    print('\n=== %s  (%d×%d)' % (os.path.basename(m['path']), m['w'], m['h']))
    print('  平均相对亮度 = %.4f    最暗 5%% 分位 = %.4f    最亮 5%% 分位 = %.4f'
          % (m['mean'], m['p5'], m['p95']))
    print('  亮度直方图（6 档）: ' + '  '.join('L%d=%.1f%%' % (i, 100.0 * m['hist'][i] / m['n']) for i in range(6)))
    print('  暗部占比（亮度 <0.06）= %.1f%%' % m['dark'])
    print('  竖向 %d 带（上=天顶，下=前景）：' % BANDS)
    for b, bnd in enumerate(m['bands']):
        print('    带%d  y=%.2f–%.2f  avg=%s  L=%.4f'
              % (b, b / float(BANDS), (b + 1) / float(BANDS), hx(bnd['rgb']), bnd['L']))
    print('  主色 %d 位：' % NDOM)
    for d in m['doms']:
        print('    %s  %5.1f%%  L=%.4f' % (hx(d['rgb']), d['pct'], d['L']))
    if m['prof']:
        print('  日盘剖面（自盘心向外，**上半环**均值，r/R 见 RADII）：')
        for fr, v in zip(RADII, m['prof']):
            if v:
                print('    r/R=%.1f  %s  L=%.4f' % (fr, hx(v['rgb']), v['L']))


def gate(ref, m):
    """逐条判据 → [(名字, 通过?, 实测, 期望)]。⛔ 返回布尔列表就算完：判红必须能说出差多少。"""
    out = []
    out.append(('暗部占比 ≤%.1f%%' % DARK_MAX, m['dark'] <= DARK_MAX,
                '%.1f%%' % m['dark'], '≤ %.1f%%（参考图实测 %.1f%%）' % (DARK_MAX, ref['dark'])))
    z = m['bands'][0]['L']
    out.append(('天顶带 L ≥%.2f' % ZENITH_MIN, z >= ZENITH_MIN,
                'L=%.4f' % z, '≥ %.2f（参考图实测 %.4f）' % (ZENITH_MIN, ref['bands'][0]['L'])))
    d = m['mean'] - ref['mean']
    out.append(('平均亮度贴合参考图 ±%.3f' % MEAN_TOL, abs(d) <= MEAN_TOL,
                'L=%.4f（Δ=%+.4f）' % (m['mean'], d), '参考图 %.4f ± %.3f' % (ref['mean'], MEAN_TOL)))
    for b in range(BANDS):
        a, r = m['bands'][b]['L'], ref['bands'][b]['L']
        out.append(('带%d L 贴合 ±%.3f' % (b, BAND_TOL), abs(a - r) <= BAND_TOL,
                    'L=%.4f（Δ=%+.4f）' % (a, a - r), '参考图 %.4f ± %.3f' % (r, BAND_TOL)))
    if m['prof'] and ref['prof'] and all(m['prof']) and all(ref['prof']):
        # 任一半径出界（日盘贴边）⇒ 整组剖面判据**跳过**并显式说明，⛔ 拿缺样子的序列算
        pr = [v['L'] for v in m['prof']]
        rp = [v['L'] for v in ref['prof']]
        # ① 盘内平坦（⛔"灯泡"）：盘心到 0.9R 的落差不得超过参考图自己的 +余量。
        #    参考图 pr[0]−pr[3] = .604−.549 = **−.055**（盘缘几乎和盘心一样亮）；被否掉的上一版是 +.75。
        flat_ref = rp[0] - rp[3]
        flat = pr[0] - pr[3]
        out.append(('盘内平坦（⛔ 灯泡式中心过亮）', flat <= max(0.14, flat_ref + 0.10),
                    'L0−L0.9=%+.3f（%.3f → %.3f）' % (flat, pr[0], pr[3]),
                    '≤ %.3f（参考图 %+.3f）' % (max(0.14, flat_ref + 0.10), flat_ref)))
        # ② 盘缘**陡降**（= "不是简单的大圆球"的正身）：0.9R → 1.2R 必须掉一大档。
        #    参考图 .549 → .211 = **−.338**；一个软边大圆球只有 −.05。⛔ 用大半径渐变去"柔化"盘缘。
        cliff_ref = rp[3] - rp[4]
        cliff = pr[3] - pr[4]
        out.append(('盘缘陡降（⛔ 软边大圆球）', cliff >= min(0.20, cliff_ref * 0.7),
                    'L0.9−L1.2=%+.3f' % cliff, '≥ %.3f（参考图 %+.3f 的七成）'
                    % (min(0.20, cliff_ref * 0.7), cliff_ref)))
        # ③ **上冠**：上半环 0.3–0.6R 必须**比盘心更亮**（低角度大气 reddening 的反面）。
        #    参考图 .788 / .816 vs 盘心 .604 ⇒ 峰值在盘内偏上。⛔ 均匀的橙黄圆盘。
        cap_ref = max(rp[1], rp[2]) - rp[0]
        cap = max(pr[1], pr[2]) - pr[0]
        out.append(('上冠亮于盘心（下金上白）', cap >= min(0.06, cap_ref * 0.6),
                    'max(L0.3,L0.6)−L0=%+.3f' % cap, '≥ %.3f（参考图 %+.3f）'
                    % (min(0.06, cap_ref * 0.6), cap_ref)))
        d1 = pr[3] - rp[3]
        out.append(('盘缘 r/R=0.9 亮度贴合', abs(d1) <= BAND_TOL,
                    'L=%.4f（Δ=%+.4f）' % (pr[3], d1),
                    '参考图 %.4f ± %.3f ⇒ 判"散光够不够"' % (rp[3], BAND_TOL)))
        flat_out = abs(pr[4] - pr[6])
        rflat = abs(rp[4] - rp[6])
        out.append(('盘外近平（散光⛔光环）', flat_out <= max(FLAT_TOL, rflat * 2),
                    '|L1.2−L2.0|=%.4f' % flat_out,
                    '≤ %.4f（参考图自身 %.4f 的两倍）' % (max(FLAT_TOL, rflat * 2), rflat)))
        # ⑥ **散光的色相**：亮度全绿也抓不到"把天烧红" vs "糊了层奶黄雾"——两者的 L 可以一样。
        rr = ref['prof'][4]['rgb']
        red_ref = rr[0] - rr[1]
        red = m['prof'][4]['rgb'][0] - m['prof'][4]['rgb'][1]
        out.append(('盘外 1.2R 红度（散光⛔奶黄）', abs(red - red_ref) <= RED_TOL,
                    'R−G=%.0f（Δ=%+.0f）' % (red, red - red_ref),
                    '参考图 %.0f ± %.0f' % (red_ref, RED_TOL)))
    return out


def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass                                        # 老版本 Python 没有 reconfigure，报告照出
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(2)
    frame = None
    sun = None
    sun_ref = None
    paths = []
    gate_mode = False
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--frame':
            frame = args[i + 1]; i += 2; continue
        elif a == '--sun':
            sun = tuple(float(x) for x in args[i + 1].split(','))[:3]; i += 2; continue
        elif a == '--sun-ref':
            sun_ref = tuple(float(x) for x in args[i + 1].split(','))[:3]; i += 2; continue
        elif a == '--gate':
            gate_mode = True; i += 1; continue
        paths.append(a); i += 1

    if not gate_mode:
        for p in paths:
            report(measure(p, sun))
        if frame:
            report(measure(frame, sun))
            print('\n── 判读口径 ──')
            print('  「色调太深」= 参考图的 平均相对亮度 与 暗部占比 明显高于/低于原型帧；两数一起看，⛔ 只看均值。')
            print('  验收请改跑 --gate：判据从参考图自己的实测数推出，⛔ 肉眼裁决、⛔ 手抄阈值。')
        return 0

    if len(paths) < 2:
        print('--gate 需要 <参考图> <帧1> [<帧2> ...]')
        return 2
    ref = measure(paths[0], sun_ref or sun)
    report(ref)
    print('\n── 判绿（参考图 = %s）──' % os.path.basename(paths[0]))
    bad = 0
    for p in paths[1:]:
        m = measure(p, sun)
        report(m)
        rows = gate(ref, m)
        fails = [r for r in rows if not r[1]]
        print('\n  ▶ %s : %s（%d/%d 条通过）'
              % (os.path.basename(p), '✅ 全绿' if not fails else '❌ 判红', len(rows) - len(fails), len(rows)))
        for name, ok, got, want in rows:
            print('      %s %-28s 实测 %-34s 期望 %s' % ('✔' if ok else '✘', name, got, want))
        bad += len(fails)
    print('\n结论：%s' % ('全部通过' if not bad else '%d 条判红' % bad))
    return 0 if not bad else 1


if __name__ == '__main__':
    sys.exit(main())
