---
name: e48-desert-smoke-preview-status
description: E48「大漠·孤烟」原型交付状态与未决项
metadata:
  type: project
---

# E48「大漠·孤烟」原型交付状态

**交付物**：`docs/e48-desert-smoke-preview.html`（纯 Canvas 2D，零 Kotlin）+ `e48_smoke_harness.js` + `e48_visual_driver.mjs`。

**验收结果**（2026-10-14）：
- harness **14/14 全绿**，NaN/Inf=0，HIGH 94.4 ops / 3.94 屏（低于 E44 已验收的 4.21）。
- 调色 gate **15/15 全绿**（含日盘 r/R=0.9 亮带 L=0.814 对齐参考图）。
- 沙丘形态已从"薄片叠层"转为**凸顶圆丘**：近景 `roll` 把 ridged 噪声混向平滑 fbm，面级明暗主项从 `|slope|`（描边属性）改为相对高度 `hf^1.15 − 0.34`（体积属性），脊刃亮边退化成单条薄亮刃、去掉配对的脊下阴带。
- 日盘新增 0.92 stop `#ffb350` 亮带，r/R=0.9 实测 L=0.814（参考图 .814 ± .045）。
- 烘焙成本 HIGH 档 **1386 ms**（较上一轮 1017 ms 上升 36%，因 rim pass 每列分配 5-stop 渐变）。

**所有者裁决状态**：
- ✅ 太阳重建完成（gate 15/15）。
- ✅ 多层沙丘重叠（n=7, sub=2 → 14 条脊线；roll 曲线近景 78% 圆丘）。
- ✅ 色调提亮（全局亮度阶梯 + 软饱和 + 掠射保底）。
- ✅ 删烽燧、删水面（资产表 12→9 项）。
- ⛔ **参考图2 从未送达**——当前色调用参考图1实测 ladder 驱动，若用户重新附上参考图2需重跑 palette gate。

**未决项**：
1. 方案文档同步：§一 needs、§3 asset table（12→9）、§五 budget（bakeMs=1386ms）、§八 P-entries（十踩 roll/hf-shape）、§十 regenerated numbers、§十一 wiring（frame-sliced baking）。
2. 真机验收：创维 TV API-22 上沙丘体块是否可读（Canvas 2D 在 HWUI 上的折线采样精度）、驼队 22px 高度是否可辨、烟摆动 10° 倾角在低对比面板上是否可见。
3. 命名与档位归属（D7）：建议 `DESERT_SMOKE` / ordinalLabel "48" / Tier.ADV，待所有者定稿。

**已知风险**：
- bakeMs=1386ms 超 E46 首帧预算（~1000ms），Kotlin 侧必须走分帧烘焙（E46 模式：首帧必出画面，剩余层逐帧补）。
- 近景圆丘 `roll=0.78 * t^0.85` 是经验值，若真机上仍读成"灯芯绒"需继续调尖度 `sharp` 或频率 `f1`。
