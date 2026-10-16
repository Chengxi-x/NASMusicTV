#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""E48 参考骆驼剪影的**解剖量**（第八轮「骆驼拉的太长了，要减短一点」+ 第九轮「现在的驼峰还是太大」的取数源）。

⛔ 注释里的解剖数只能由本脚本 stdout 再生，不许手抄（E44 D24 的教训）。
判读口径**逐条对齐**页面里的 `window.__E48.anatomy(f)`（`docs/archive/e48-desert-smoke-preview.html`），
两边同名的量才可直接相减：

    lenPx / highPx / lenHigh   剪影外接框宽 / 高 / 长高比（含尾与吻）
    humpRisePx                 峰顶高出**肩凹**（两峰之间的背线低点）的像素
    bodyDepthPx                肩凹 → 腹线
    humpOverDepth              峰高 / 体深        ← `caravan.humpK` 想表达的量
    legPx / legOverHigh        腹线 → 蹄 / 全高    ← 我们由 `h·(0.385−off)` 间接控制
    humpW / wOverRise          **峰宽 / 峰高**（第九轮新增的第二根轴，见下）
    humpW / highPx             峰宽 / 全高

⭐ **第九轮为什么必须加「峰宽」这根轴**：所有者第三次说"驼峰还是太大"时，`峰/体深` 只差 14%，
   而那根轴上我们**已经在台阶顶**——"太大"根本不在它上面。峰宽/峰高实测差 **45%**，才是真错的那根。
   ⇒ 口径 = **半峰高处沿天际线交叉**：从峰心向两侧走 `top[x]`，第一次 `top[x] ≥ 峰顶 + rise/2` 的列
   即峰脚。⛔ 用"该行的 ink 游程"当峰宽 —— 参考图左峰半峰高那一行，游程左端其实是**颈背相接处**，
   会把颈坡并进度量。页面 `anatomy()` 的 `halfW()` 实现**同一条**定义（两边同名的量才可直接相减）。

用法：python docs/archive/verification/scripts/e48_ref_camel.py <剪影png> [--luma N]
     归档：… > docs/archive/verification/results/e48_ref_camel_final.txt 2>&1

`--luma N`：把"什么算墨"从**透明底的黑色剪影**（默认 `a>128 且 均值<160`）换成
     **渲染帧上的暗于 N 的像素**（真机/截图里的骆驼是棕底上的深棕，⛔ 没有 alpha 通道）。
     ⇒ 同一个脚本、同一套判读口径，既能量参考图也能量我们画出来的那一坨，
     两边的数字才可直接相减（⛔ 为截图另写一份脚本 = 两套口径）。
     ⚠️ 量渲染帧时请用 `e48_visual_driver.mjs zoom … <scale>` 的**最近邻放大裁块**：
     原生 18 px 高的骆驼会让下面的局部低点窗口（`±bbox/20`，下限 3 px）跨过整个峰。
     ⛔ **但绝对 luma 阈值量不出"帧上可见的骆驼"**（第八轮 P37）：近景骆驼的腿与它身后的暗沙带
     同亮度 ⇒ 掩膜只框得住躯干，`highPx` 被截在躯干下缘，长/高、峰/体深全部虚高。
     要用这条口径必须先按**骆驼自己的颜色**做掩膜；在那之前，比例只认 `anatomy()`（精灵像素）。
"""
import sys
from PIL import Image

# ⚠️ Windows 控制台默认 CP936：一旦把 stdout 重定向进文件（归档的常规用法），
#    `⇒` 这类字符就 `UnicodeEncodeError` 半途中断 ⇒ 归档文件是**截断的**。⇒ 无条件锁 UTF-8。
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')

LUMA = None                       # --luma N ⇒ 走"帧上暗像素"口径
INK = lambda r, g, b, a: (a > 128 and (r + g + b) / 3.0 < 160.0) if LUMA is None \
    else (r * 0.299 + g * 0.587 + b * 0.114) < LUMA



def main(path):
    im = Image.open(path).convert('RGBA')
    w, h = im.size
    px = im.load()
    top = [None] * w      # 每列第一个 ink 行
    bot = [None] * w      # 每列最后一个 ink 行
    for x in range(w):
        for y in range(h):
            if INK(*px[x, y]):
                top[x] = y
                break
        for y in range(h - 1, -1, -1):
            if INK(*px[x, y]):
                bot[x] = y
                break
    cols = [x for x in range(w) if top[x] is not None]
    x0, x1 = min(cols), max(cols)
    y0 = min(top[x] for x in cols)
    y1 = max(bot[x] for x in cols)

    # 腹线 = 行宽 >70% 最大行宽的**最下**一行。⚠️ 阈值不能取 50%：四条腿加起来的行宽仍有
    #   躯干平台的一半（参考图 200 vs 393），50% 会把腹线读到腿中间去。
    rown = []
    for y in range(h):
        n = sum(1 for x in range(x0, x1 + 1) if INK(*px[x, y]))
        rown.append(n)
    nmax = max(rown)
    belly = max(y for y in range(h) if rown[y] > 0.7 * nmax)

    # 两峰 = 背线 `top[x]` 的两个**局部低点**，⛔ 按外接框中点劈半。
    #   ⚠️ 这是与 `anatomy()` 唯一的口径差：我们的剪影以躯干中心对齐精灵中心，劈半正好切在肩凹；
    #   参考图的**头颈伸到 x=486**（bbox 中点 255 落在后峰上），劈半会把两个峰都划进"左半"。
    #   所以这里显式找局部低点，并要求两峰心相距 ≥ bbox 宽的 1/5（否则挑中的是同一个峰的毛刺）。
    k = max(3, (x1 - x0) // 20)
    def tget(x):
        return top[x] if 0 <= x < w and top[x] is not None else 1e9
    mins = [(top[x], x) for x in cols
            if all(top[x] <= tget(x - d) and top[x] <= tget(x + d) for d in range(1, k + 1))]
    mins.sort()
    sep = (x1 - x0) // 5
    rear = mins[0]
    front = next((t for t in mins if abs(t[1] - rear[1]) >= sep), rear)
    humpTop = min(rear[0], front[0])
    a, b = sorted([rear[1], front[1]])
    saddle = max(top[x] for x in range(a, b + 1) if top[x] is not None)

    print(path + '  (' + str(w) + '×' + str(h) + ')')
    print('  外接框 x=' + str(x0) + '–' + str(x1) + '  y=' + str(y0) + '–' + str(y1))
    print('  lenPx=%d highPx=%d  长/高=%.3f' % (x1 - x0 + 1, y1 - y0 + 1, (x1 - x0 + 1) / (y1 - y0 + 1)))
    p, q = sorted([rear, front], key=lambda t: t[1])
    print('  左峰顶=行%d(x=%d)  右峰顶=行%d(x=%d)  肩凹=行%d  腹线=行%d  蹄=行%d'
          % (p[0], p[1], q[0], q[1], saddle, belly, y1))
    print('  humpRisePx=%d  bodyDepthPx=%d  **峰/体深=%.3f**' % (saddle - humpTop, belly - saddle,
                                                                 (saddle - humpTop) / (belly - saddle)))
    print('  legPx=%d  腿/全高=%.3f' % (y1 - belly, (y1 - belly) / (y1 - y0 + 1)))
    # ⭐ 峰的**第二根轴**：半峰高处沿**天际线**量出的峰宽（第九轮「驼峰还是太大」逼出来的口径）。
    #   ⛔ 用"该行的 ink 游程"当峰宽 —— 半峰高那一行会把**颈坡/臀坡**并进度量
    #   （参考图左峰 y=69 的游程是 (92,76)，左端 92 px 其实是颈背相接处，不是峰脚）。
    #   ⇒ 从峰心向两侧走 `top[x]`，第一次 `top[x] ≥ 半峰高` 的列即峰脚；两侧相加 = 峰宽。
    #   这条口径在原型侧由 `anatomy()` 的 `humpW*Px` 同名实现（⛔ 两边一套定义，否则数字不可减）。
    print('  ── 峰宽（天际线半峰高交叉，与 anatomy() 同口径）──')
    for nm, (pk, px_) in (('左峰', p), ('右峰', q)):
        r = saddle - pk
        L = pk + r / 2.0
        lx = px_
        while lx - 1 >= x0 and top[lx - 1] is not None and top[lx - 1] < L:
            lx -= 1
        rx = px_
        while rx + 1 <= x1 and top[rx + 1] is not None and top[rx + 1] < L:
            rx += 1
        wl, wr = px_ - lx, rx - px_
        print('  %s rise=%d  半峰高=行%.1f  左肩=%d 右肩=%d ⇒ 峰宽=%d  **宽/高=%.3f**  宽/全高=%.3f'
              % (nm, r, L, wl, wr, wl + wr, (wl + wr) / r, (wl + wr) / (y1 - y0 + 1)))
    # ⭐ **头/峰高低关系**（第九轮补的第三根轴）：原型侧 `anatomy()` 早就有 `headRow/headBelowFrontPx`，
    #   但参考图那一边**从来没量过** ⇒ 我们"头顶低于前峰 1 px"其实没有对照物。
    #   口径与 `anatomy()` 一致：头颈区 = **右峰之后**天际线的最低点到外接框右端，`headRow` = 该区天际线最高点。
    #   （原型侧窗口右端是画布边、这里外接框右端 = 吻尖，同一条"颈后全部"的定义。）
    print('  ── 头/峰高低（与 anatomy() 的 headRow / headBelowFrontPx 同口径）──')
    nk = max(range(q[1], x1 + 1), key=lambda x: top[x] if top[x] is not None else -1)
    hrow = min(top[x] for x in range(nk, x1 + 1) if top[x] is not None)
    print('  颈背相接=外接框内列%d  头顶=行%d  右峰顶=行%d ⇒ **头顶低于前峰 %d px** = 峰高的 %.2f 倍'
          % (nk - x0, hrow, q[0], hrow - q[0], (hrow - q[0]) / float(saddle - humpTop)))
    # 躯干长（不含尾/吻/颈）：腹线那一行 ink 的跨度
    xs = [x for x in range(w) if INK(*px[x, belly])]
    print('  躯干跨（腹线行）x=%d–%d = %d px ⇒ 躯干长/全高=%.3f'
          % (min(xs), max(xs), max(xs) - min(xs) + 1, (max(xs) - min(xs) + 1) / (y1 - y0 + 1)))

    # 腿宽 = 腹线↔蹄**中间那一行**的各段 ink 游程（四条腿各自的粗细）
    ym = (belly + y1) // 2
    runs, cur = [], 0
    for x in range(x0, x1 + 1):
        if INK(*px[x, ym]):
            cur += 1
        elif cur:
            runs.append(cur)
            cur = 0
    if cur:
        runs.append(cur)
    runs = [r for r in runs if r >= 3]
    print('  腿行 y=%d 游程 %s ⇒ 腿宽/全高 = %s（均值 %.3f）'
          % (ym, runs, ['%.3f' % (r / (y1 - y0 + 1)) for r in runs],
             (sum(runs) / len(runs) / (y1 - y0 + 1)) if runs else float('nan')))


if __name__ == '__main__':
    argv = sys.argv[1:]
    if '--luma' in argv:
        i = argv.index('--luma')
        LUMA = float(argv[i + 1])
        del argv[i:i + 2]
    if not argv:
        sys.exit('用法：e48_ref_camel.py <剪影png> [--luma N]')
    print('墨口径：' + ('帧上暗像素 luma<%g' % LUMA if LUMA is not None else '剪影 alpha>128 且均值<160'))
    main(argv[0])

