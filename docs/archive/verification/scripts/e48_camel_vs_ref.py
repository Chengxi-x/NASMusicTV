#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把**我们的**驼队精灵（浏览器导出的烘焙格 PNG）与所有者的参考剪影**等高并排**。
用途：给所有者看"改完之后长什么样"的同一取景口径（第八/九轮同一张版式，可前后对比）。
⛔ 这不是判据 —— 判据是 e48_ref_camel.py / anatomy() 的两轴数字，本图只是判读辅助。
"""
import sys, base64
from PIL import Image

sys.stdout.reconfigure(encoding='utf-8')
sprite_b64, ref_path, out_path, H_TARGET = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])

sp = Image.open(sprite_b64).convert('RGBA')
rf = Image.open(ref_path).convert('RGBA')

# 裁到各自的墨外接框（精灵有 alpha；参考剪影按 alpha>128 且均值<160，与 e48_ref_camel.py 同口径）
def bbox_alpha(im, ink=False):
    px = im.load()
    w, h = im.size
    x0, y0, x1, y1 = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            hit = a > 128 and (not ink or (r + g + b) / 3.0 < 160)
            if hit:
                if x < x0: x0 = x
                if x > x1: x1 = x
                if y < y0: y0 = y
                if y > y1: y1 = y
    return im.crop((x0, y0, x1 + 1, y1 + 1))

sp_c = bbox_alpha(sp)
rf_c = bbox_alpha(rf, ink=True)
sp_s = sp_c.resize((round(sp_c.width * H_TARGET / sp_c.height), H_TARGET), Image.NEAREST)
rf_s = rf_c.resize((round(rf_c.width * H_TARGET / rf_c.height), H_TARGET), Image.LANCZOS)

PAD, GAP = 24, 32
W = PAD * 2 + rf_s.width + GAP + sp_s.width
H = PAD * 2 + H_TARGET
canvas = Image.new('RGB', (W, H), (246, 243, 238))
bg = Image.new('RGBA', (W, H), (0, 0, 0, 0))
canvas.paste(rf_s, (PAD, PAD), rf_s)
canvas.paste(sp_s, (PAD + rf_s.width + GAP, PAD), sp_s)
canvas.save(out_path)
print(f'{out_path}  参考 {rf_c.size}→{rf_s.size}  我们 {sp_c.size}→{sp_s.size}  等高={H_TARGET}px')
print(f'  参考外接框长/高={rf_c.width/rf_c.height:.3f}  我们={sp_c.width/sp_c.height:.3f}')
