#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""E48 色调对账：把**参考图**与**原型渲染帧**放在同一口径下量，⛔ 靠肉眼说"太深了"。

用法：
    # 只出报告（诊断用）
    python docs/archive/verification/scripts/e48_ref_palette.py <参考图> [<参考图2> ...] --frame output/e48_frames/full_dune.png
    python docs/archive/verification/scripts/e48_ref_palette.py <参考图> --sun 0.40,0.33   # 日盘中心归一化坐标（可选）

    # 判绿（验收用）：⛔ 阈值手抄，全部从**参考图自己的实测数**推出来
    python docs/archive/verification/scripts/e48_ref_palette.py --gate <参考图> <帧1> [<帧2> ...] [--sun cx,cy,rFrac] [--sun-ref cx,cy,rFrac]
      · `--sun` 给**帧**的日盘（圆心归一化坐标 + 半径占**屏高**的比例）；E48 原型侧 = `L.sunX/W,
        L.sunY/H, P.comp.sunR`，由 `e48_visual_driver.mjs` 随截图一起打印，⛔ 手抄。
        `--sun-ref` 给参考图自己的（两者画幅不同 ⇒ 归一化位置必然不同，⛔ 用同一组数去量两张图）；
        **不给则用脚本里钉死的 `SUN_REF`**（参考图 1 实测，见该常量注释）。
      · 不给 `--sun-ref` 且参考图不是钉死的那张 ⇒ 必须显式传，⛔ 让脚本拿帧的圆去量照片。
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
# 盘内**竖向直径**采样位（×R，负=盘心以上）。⛔ 采到 +0.30R 为止：日盘下缘按需求必须被远脊切断
# （实测切 25.9% ⇒ 脊线在 +0.48R），再往下量到的是**脊的硬边**，那是另一件事、判据 ⑧ 抓不到重点。
# 步距 0.15R 上恰好含 dy=0（= 上冠线性渐变 α→0 的位置，实测台阶就在那儿）。
COL_DY = (-0.90, -0.75, -0.60, -0.45, -0.30, -0.15, 0.0, 0.15, 0.30)
COL_EQ = COL_DY.index(0.0)        # 判据 ⑧ 的起点：盘心那一档

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
# ⭐⭐ **盘缘柔度**（所有者交办「太阳边缘要柔化」）：见 gate() ⑦。
#   口径 = 沿半径细扫后，**10%–90% 落差点之间的半径跨度**（单位 = R）。无量纲、对硬度**单调**。
#   ⚠️ 旧口径「±2% 带内落差占比」**不单调**（把盘改硬，落差整体搬进带内，带内占比反而掉）⇒ 作废。
#   ⛔ 单边 `≤`（`EDGE_SHARE_MAX = 0.20` 那版）：纠正圆心后参考图自身 = **0.088 R**（现 `--gate` 实跑值），
#   旧上限乘二 ⇒ 永真、闸门失效。⑦ 现为**双边贴合带**（柔过头与硬过头都判红）。
EDGE_W_LO = 0.55            # × 参考图宽度：窄于此 = ⛔ 贴上去的硬边圆片
EDGE_W_HI = 2.00            # × 参考图宽度：宽于此 = ⛔ 糊成一团（柔化的允许上限 = 参考图的 2 倍）
# ⭐⭐ **参考图 1 自己的日盘圆**（归一化圆心 + 半径占图高比例）—— 由 `locate_sun()` 定位，
#   ⛔ 手敲、⛔ 网格搜索去"凑"某组剖面数（那等于把剖面当自变量、圆当因变量，圆一错全盘皆错）。
#   重跑取数：`python e48_ref_palette.py --locate logs_temp/e48_ref_orig.png`
#     → 0.3914,0.3690,0.1230（峰值 L .949，连通域 5815 px，阈值 .32，未贴边）
#   ⚠️ **为什么必须有这一条**：这一版之前钉死的是 `(0.402, 0.364, 0.124)`，x **偏了 0.14R**。
#   后果不是"稍微不准"：`RADII` 采的是**上半环 13 点均值**，圆一偏右，右侧 4~5 点就落到**天幕**上
#   ⇒ 参考图自己的 L(0.9R) 从真值 **.784** 被量成 **.562**、±2% 半径带台阶从 **.345** 被量成 **.031**
#   ⇒ 「盘缘必须暗于上冠」「盘缘过渡宽度」两条判据的**目标值全是假的**，据此做的两轮修法方向相反
#   （给日盘加暗缘 + 把落差摊到 0.3R 宽）。⇒ 现在 `--gate` 会**自己定位**参考图的圆，
#   并把这组钉死值降级为一条**一致性自检**（偏差 >SUN_REF_TOL 即判红，⛔ 让常量悄悄过期）。
SUN_REF = (0.3914, 0.3690, 0.1230)
SUN_REF_TOL = 0.005


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
    edge = None
    if sun:
        cx, cy = sun[0], sun[1]
        # R 默认按"占屏高 30%"（老用法）；给第三个数则按**屏高比例** ⇒ 与原型参数同口径
        R = h * (sun[2] if len(sun) > 2 else 0.30)

        # ⚠️ 只采**上半环**（角度 180°…360° ⇒ y 在盘心之上）：日盘下缘按需求必须被远脊切掉 8–45%，
        #    整环均值会把"遮挡"量成"盘缘不亮"（实测：同一张图整环 .177 / 上半环 .336，
        #    而参考图因为切得浅、整环反而 .350 ⇒ 判据变成在比"谁挡得多"，⛔ 比的是盘缘亮度）。
        def ring(fr):
            rad = fr * R
            acc, n = [0.0, 0.0, 0.0], 0
            lums = []
            for a in range(180, 361, 15):
                x = int(cx * w + rad * math.cos(math.radians(a)))
                y = int(cy * h + rad * math.sin(math.radians(a)))
                if 0 <= x < w and 0 <= y < h:
                    p = im.getpixel((x, y))
                    acc[0] += p[0]; acc[1] += p[1]; acc[2] += p[2]; n += 1
                    lums.append(rel_lum(p[0], p[1], p[2]))
            if not n:
                return None
            v = tuple(t / n for t in acc)
            return {'rgb': v, 'L': rel_lum(*v), 'lums': lums}

        prof = [ring(fr) for fr in RADII]
        # ⭐⭐ **盘缘过渡宽度**（所有者交办「太阳边缘要柔化」）：`RADII` 只采 0.9/1.2 ⇒ 中间怎么过渡
        #    完全看不见 —— 实测把 1.00R 从 L .284（硬 clip）改成 .351（贴合参考图 .343）之后，
        #    **原有 16 条判据的每一个数字都一个字没变**。⇒ 旧闸门对"硬边圆片"是**瞎的**。
        #    ⚠️ 口径**换过**：旧口径「±2% 半径带内落差占总落差之比」**对硬度不单调** ——
        #    实测把掩膜从 1.10R 收到 1.00R（明显更硬）反而从 24.7% 掉到 **17.7%**（判成"偏糊"），
        #    因为整条落差搬到了 0.98R **之内**，带内只剩尾巴。⇒ 改为**直接量过渡带宽度**：
        #    沿半径细扫（0.80R→1.30R，步 0.01R），取"从盘侧 90% 落差点到天幕侧 10% 落差点"
        #    的**半径跨度**（单位 = R）。单调、无量纲、可直接读成"边缘糊了多宽"。
        fine = []
        for k in range(0, 51):
            fr = 0.80 + 0.01 * k
            v = ring(fr)
            fine.append((fr, None if not v else v['L']))
        width = None
        pts = [(f, L) for f, L in fine if L is not None]
        if len(pts) >= 20:
            A, B = pts[0][1], pts[-1][1]          # A=盘侧（内），B=天幕侧（外）
            D = A - B
            if D > 0.05:                           # 落差不足 0.05 = 盘外全被遮/无盘 ⇒ ⛔ 不量
                def cross(frac):                   # frac=1 ⇒ 刚起坡（内侧）；frac=0 ⇒ 落尽（外侧）
                    tgt = B + frac * D
                    for i in range(1, len(pts)):
                        (f0, L0), (f1, L1) = pts[i - 1], pts[i]
                        if (L0 - tgt) * (L1 - tgt) <= 0 and L1 != L0:
                            return f0 + (tgt - L0) * (f1 - f0) / (L1 - L0)
                    return None
                hi, lo = cross(0.90), cross(0.10)   # hi=内侧起点, lo=外侧终点
                if hi is not None and lo is not None and lo >= hi:
                    width = lo - hi
        ei, eo = ring(0.98), ring(1.02)
        edge = None
        if ei and eo:
            edge = {'in': ei['L'], 'out': eo['L'], 'step': ei['L'] - eo['L'], 'width': width}
        # ⭐⭐ **盘内竖向台阶**：上面全部判据采的都是**同心环均值** ⇒ 环内的**角度结构**看不见。
        #    实证：柔化改造后截图上有一条**横贯盘心的亮黄带下缘**（上冠线性渐变的 α→0 断崖），
        #    17 条判据全绿。⇒ 沿**竖向直径**再采一列，判"相邻步的最大落差"。
        #    ⚠️ 每档取 dx∈{−.10,0,+.10}R 三点均值：单像素列在照片上是颗粒噪声，会把参考图自己判红。
        col = []
        for f in COL_DY:
            acc, n = [], 0
            for dx in (-0.10, 0.0, 0.10):
                x = int(cx * w + dx * R)
                y = int(cy * h + f * R)
                if 0 <= x < w and 0 <= y < h:
                    acc.append(rel_lum(*im.getpixel((x, y)))); n += 1
            if n:
                col.append(sum(acc) / n)
        step = 0.0
        for k in range(1, len(col)):
            step = max(step, abs(col[k] - col[k - 1]))
        col = {'lums': col, 'maxStep': step} if len(col) >= 4 else None
    return {'path': path, 'w': w, 'h': h, 'mean': mean, 'hist': hist, 'n': len(lums),
            'p5': sorted(lums)[int(len(lums) * 0.05)], 'p95': sorted(lums)[int(len(lums) * 0.95)],
            'dark': dark, 'bands': bands, 'doms': doms, 'prof': prof, 'edge': edge, 'col': col}


def locate_sun(path, thr=0.32):
    """**定位**日盘圆 → (cx, cy, rFrac)（归一化；rFrac = R / 图高）。⛔ 手敲圆心。

    口径：全图最亮点必在盘内（本场景天幕上限 L≈.25、沙丘 ≈.20，盘 ≈.6~.95）⇒ 从它做
    4-邻接洪泛填充（阈值 L>thr）⇒ 取连通域 bbox。
    ⚠️ **半径取水平半宽**、圆心 y = **上缘 + R**：日盘下缘按需求必须被远脊切断（参考图切 25.9%），
       竖向 bbox 是**矮的**，用它推半径会系统性偏小。
    ⚠️ 为什么这条必须进脚本、⛔ 一次性手算：本轮实证钉死的 `SUN_REF` x 偏了 **0.14R**
       （敲 0.402，定位 0.3914）⇒ 上半环 13 点里右侧 4~5 点落到**天幕**上，把参考图自己的
       L(0.9R) 从真值 **.784** 量成 **.562**、把 ±2% 台阶从 **.345** 量成 **.031**。
       ⇒ 三条盘判据的目标值全是假的，"盘缘必须暗于上冠""边缘要摊开"这两条修法**方向反了**。
    """
    im = load(path)
    w, h = im.size
    px = im.load()
    lums = [0.0] * (w * h)
    best, bi = -1.0, -1
    for j in range(h):
        for i in range(w):
            v = rel_lum(*px[i, j][:3])
            lums[j * w + i] = v
            if v > best:
                best, bi = v, j * w + i
    seen = bytearray(w * h)
    seen[bi] = 1
    stack = [bi]
    xmin = xmax = bi % w
    ymin = ymax = bi // w
    n = 0
    while stack:
        k = stack.pop()
        i, j = k % w, k // w
        n += 1
        if i < xmin: xmin = i
        if i > xmax: xmax = i
        if j < ymin: ymin = j
        if j > ymax: ymax = j
        for nk in (k - w, k + w, k - 1, k + 1):
            if seen[nk] or nk < 0 or nk >= w * h:
                continue
            ni = nk % w
            if abs(ni - i) + abs(nk // w - j) != 1:
                continue                              # ⛔ 跨行回绕
            if lums[nk] > thr:
                seen[nk] = 1
                stack.append(nk)
    R = (xmax - xmin) / 2.0
    return {'cx': ((xmin + xmax) / 2.0) / w, 'cy': (ymin + R) / h,
            'rFrac': R / h, 'n': n, 'peak': best, 'thr': thr,
            'touch': (xmin <= 0 or xmax >= w - 1 or ymin <= 0)}


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
        v = m['prof'][3]
        if v and len(v['lums']) >= 4:
            print('  0.90R 逐方位角（180°→360°，步 15°）：%s ⇒ 极差 %.3f'
                  % (' '.join('%.2f' % x for x in v['lums']),
                     max(v['lums']) - min(v['lums'])))
    if m.get('edge'):
        e = m['edge']
        print('  盘缘过渡（±2%% 半径带）：L(0.98R)=%.4f → L(1.02R)=%.4f ⇒ 带内落差 %.4f'
              % (e['in'], e['out'], e['step']))
        print('  盘缘**过渡宽度**（10%%–90%% 落差点之间的半径跨度）：%s'
              % ('—（落差不足或越界）' if e.get('width') is None
                 else '%.3f R' % e['width']))
    if m.get('col'):
        c = m['col']
        print('  盘内竖向列（dy/R=%s）：%s ⇒ 相邻最大台阶 %.4f'
              % ('/'.join('%.2f' % f for f in COL_DY),
                 ' '.join('%.3f' % v for v in c['lums']), c['maxStep']))


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
        # ④ **盘缘与上冠的暗度差**（= "有光晕"的结构本体）：参考图纠正圆心后
        #    0.9R .733 vs 上冠峰 .788 ⇒ **−.048** —— 边缘**只略暗**，盘缘本身是亮的。
        #    ⛔ 旧口径 `rc ≤ min(-0.06, rc_ref*0.6)`：代入参考图自己 rc_ref=−.048 ⇒ 上限 −.060，
        #    **参考图过不了自己的判据**（旧值 −.244 来自敲偏 0.14R 的假圆心，把右侧采样落到了天幕上）。
        #    ⇒ 改为**以参考图 rc 为中心的双边带**：上界挡住"盘缘和球心一样亮 = 发光实心球"，
        #    下界挡住"把盘缘挖成黑圈 = 贴上去的圆片"。⛔ 单边 `≤`（越小越严）会奖励过度挖暗。
        rc_ref = rp[3] - max(rp[1], rp[2])
        rc = pr[3] - max(pr[1], pr[2])
        lo, hi = rc_ref - 0.10, rc_ref + 0.05
        out.append(('盘缘/上冠暗度差贴合', lo <= rc <= hi,
                    'L0.9−max(L0.3,L0.6)=%+.3f' % rc,
                    '∈ [%+.3f, %+.3f]（参考图 %+.3f）' % (lo, hi, rc_ref)))
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
        # ⑦ **盘缘过渡宽度**（所有者交办「太阳边缘要柔化」）：②「盘缘陡降」量的是 0.9R→1.2R 的
        #    **总量**，而总量相同的两种画法——1 px 悬崖 vs 25 px 斜坡——在 ②③④⑥ 上一个字都不差。
        #    实证：把日盘从 `arc(R)` 硬 clip 改成 9-stop 渐变铺到 1.30R 之后，16 条判据**全部数字不变**。
        #    口径 = 10%–90% 落差点之间的**半径跨度**（单位 R），单调：越大越糊。
        #    ⚠️ 旧口径「±2% 带内占比」**对硬度不单调**（更硬的盘把落差整体搬到带**内**，
        #    带内反而只剩尾巴：实测 softK 1.10→1.00 明显变硬，占比却从 24.7% 掉到 17.7%）⇒ 作废。
        #    ⭐ 参考图纠正圆心后 = **0.088 R** ⇒ 它的盘缘其实是**锐**的，
        #    "柔"的读感来自贴缘红晕 + 上冠/侧缘的角度结构（见 ⑩），⛔ 宽半径渐变。
        #    ⚠️ **换 px 前先确认 R 的出处**：宽度是**无量纲 R 分数**，px = 分数 × 该图的 R。
        #    参考图 700×439、rFrac=0.1230 ⇒ R = 439×0.1230 = **54.0 px** ⇒ 0.088 R ≈ **4.8 px**；
        #    交付帧（1280×720、sunR=0.150H ⇒ R=108 px）0.118 R ≈ **12.7 px**。
        #    （旧注释写的"≈2 px @R=27 px"是**手算、从未核对图尺寸**，已作废 —— 跨图比较一律用 R 分数。）
        #    ⇒ **双边带**：下界挡"贴上去的硬边圆片"，上界挡"糊成一团"；柔化只允许到参考图的 2 倍。
        if m.get('edge') and ref.get('edge') \
                and m['edge'].get('width') is not None and ref['edge'].get('width') is not None:
            w_ref = ref['edge']['width']
            w = m['edge']['width']
            lo, hi = w_ref * EDGE_W_LO, w_ref * EDGE_W_HI
            tag = ('偏硬（⛔ 圆片）' if w < lo else '偏糊（⛔ 糊成一团）' if w > hi else '贴合')
            out.append(('盘缘过渡宽度（10–90，双边带）', lo <= w <= hi,
                        '%.3f R · %s' % (w, tag),
                        '∈ [%.3f, %.3f] R（参考图 %.3f R）' % (lo, hi, w_ref)))
    # ⑧⑨ **盘内竖向形状**（同一轮柔化改造里被"同心环均值"漏掉的整类缺陷）：截图上是一条
    #    **横贯盘心的亮黄带下缘**，而 17 条判据全绿 —— 环均值把角度结构平均掉了。
    if m.get('col') and ref.get('col'):
        # ⑧ 赤道以下**必须继续变暗**（参考图列：0.591→0.431→0.314）。我们旧表在 t=.50 把上冠
        #    α 直接归零、红层要到 t=.80 才起 ⇒ 下半盘躺平在 .58：**落差只有参考图的 15%**。
        #    ⇒ 读出来的不是"落日的下半被大气烧暗"，是"一条亮带贴在盘中间"。
        dn_ref = ref['col']['lums'][COL_EQ] - ref['col']['lums'][-1]
        dn = m['col']['lums'][COL_EQ] - m['col']['lums'][-1]
        out.append(('盘心以下继续变暗（⛔ 亮带贴中间）', dn >= min(0.10, dn_ref * 0.6),
                    'L0−L0.30=%+.3f' % dn, '≥ %+.3f（参考图 %+.3f 的六成）'
                    % (min(0.10, dn_ref * 0.6), dn_ref)))
        # ⑨ 相邻采样档的**最大台阶**：真·clip 断崖（≥0.3）必须被抓到。
        #    ⚠️ 上限取得比参考图自己的最陡段宽（参考图 0.163 —— 它的竖向坡本身很陡），
        #    ⛔ 拿"越小越严"当正确：那会把参考图自己判红。
        ms_ref = ref['col']['maxStep']
        out.append(('盘内无台阶（⛔ 渐变被 clip）', m['col']['maxStep'] <= max(0.14, ms_ref * 1.3),
                    '最大相邻台阶=%.4f' % m['col']['maxStep'],
                    '≤ %.4f（参考图自身 %.4f 的 1.3 倍）' % (max(0.14, ms_ref * 1.3), ms_ref)))
        # ⑩ **盘缘的角度结构**：环均值把 13 个方位角平均掉 ⇒ 一条"上亮下暗的竖向坡"和
        #    一圈"均匀的橙边"可以给出**同一个均值**。参考图 0.90R 逐方位角实测
        #    `.37 .64 .79 .89 .92 .93 .93 .94 .92 .93 .82 .62 .34` ⇒ 极差 **0.60**
        #    （水平盘缘暗、顶部冠亮 = 大气透视的本体）。均匀环 = 极差 0 = "贴上去的色卡圆片"。
        #    口径取**极差**而非标准差：照片颗粒对 std 敏感、对"整段缺失"不敏感。
        if m['prof'][3] and ref['prof'][3] and len(m['prof'][3]['lums']) >= 4:
            sp_ref = max(ref['prof'][3]['lums']) - min(ref['prof'][3]['lums'])
            sp = max(m['prof'][3]['lums']) - min(m['prof'][3]['lums'])
            out.append(('盘缘角度结构（⛔ 均匀色卡环）', sp >= min(0.30, sp_ref * 0.6),
                        '0.90R 极差=%.3f（%.2f→%.2f）'
                        % (sp, min(m['prof'][3]['lums']), max(m['prof'][3]['lums'])),
                        '≥ %.3f（参考图 %.3f 的六成）' % (min(0.30, sp_ref * 0.6), sp_ref)))
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
    locate_mode = False
    bad0 = 0
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
        elif a == '--locate':
            locate_mode = True; i += 1; continue
        paths.append(a); i += 1

    if locate_mode:
        for p in paths:
            lc = locate_sun(p)
            print('%s  →  --sun %.4f,%.4f,%.4f' % (os.path.basename(p), lc['cx'], lc['cy'], lc['rFrac']))
            print('   峰值 L=%.4f  连通域 %d px  阈值 %.2f  %s'
                  % (lc['peak'], lc['n'], lc['thr'],
                     '⚠️ 连通域**贴到图边** ⇒ 阈值太低、日盘和天幕连成一片了，数不可信' if lc['touch'] else '未贴边'))
        return 0

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
    # ⭐ 参考图的日盘圆**由脚本定位**，⛔ 用钉死的 `SUN_REF` 去量（钉死值降级成一致性自检的对象）。
    #    成因（本轮实证）：`SUN_REF` 的 x 手敲偏 0.14R ⇒ 上半环右侧 4~5 点落到天幕上 ⇒
    #    参考图自己的 L(0.9R) 被量成 .562（真值 .784）、±2% 台阶被量成 .031（真值 .345）
    #    ⇒ 三条盘判据的目标值全是假的，"盘缘要暗""落差要摊开"这两条修法**方向反了**。
    loc = locate_sun(paths[0])
    refSun = sun_ref or (loc['cx'], loc['cy'], loc['rFrac'])
    ref = measure(paths[0], refSun)
    report(ref)
    d = [abs(refSun[0] - SUN_REF[0]), abs(refSun[1] - SUN_REF[1]), abs(refSun[2] - SUN_REF[2])]
    print('\n── 参考图日盘圆：定位 %.4f,%.4f,%.4f ｜ 钉死 SUN_REF %.4f,%.4f,%.4f ──'
          % (refSun[0], refSun[1], refSun[2], SUN_REF[0], SUN_REF[1], SUN_REF[2]))
    if sun_ref:
        print('  ➖ 显式传了 --sun-ref ⇒ 一致性自检**跳过**（自检只保护"忘了传"这条路径）')
    else:
        ok = max(d) <= SUN_REF_TOL
        print('  %s 钉死常量与定位一致（各分量 ≤%.3f）%s'
              % ('✔' if ok else '✘', SUN_REF_TOL,
                 '' if ok else ' ⇒ 偏差 %s ⇒ **把 SUN_REF 改成上面定位的那组数**（测量赢，⛔ 改判据）'
                                % ('/'.join('%.4f' % v for v in d)))
              )
        if not ok:
            bad0 = 1
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
    print('\n结论：%s' % ('全部通过' if not (bad + bad0)
                          else '%d 条判红%s' % (bad + bad0, '（含日盘圆一致性）' if bad0 else '')))
    return 0 if not (bad + bad0) else 1


if __name__ == '__main__':
    sys.exit(main())
