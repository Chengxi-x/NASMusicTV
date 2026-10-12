# 可视化效果列表

> **版本**：v2.38.8（versionCode 177）
> **更新日期**：2026-10-12
> **来源**：`app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` — `VisualizerTheme` 枚举

共 **22** 个效果，分三档。档位只决定「在哪种画质下可选」：

- **BASIC** —— 三档画质全可用。
- **ADV** —— 逐项门控：看该效果**是否真的读取** `ctx.quality.maxParticles`（枚举第 4 参 `needsParticleBudget`），
  不按 tier 一刀切 ⇒ 11 套里 10 套三档全可用，仅 `E14 节拍烟花` 在 LOW 档被挡。
- **ULTRA** —— `supports()` 要求 `allowFramebuffer`，该字段只有 `HIGH` 为 `true` ⇒ MEDIUM / LOW 一律不提供。

增删历史、实现细节、真机验收记录见 `docs/technical-overview.md` §10。

## BASIC 档（9 套）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E05 | CIRCULAR_RING | 圆形频谱环 |
| E24 | ECG_WAVE | 心跳 |
| E25 | HYPNOTIC_FUNCTION | 催眠 |
| E30 | RADAR_GRID | 雷达 |
| E33 | CONCENTRIC_GEARS | 齿轮 |
| E35 | LIGHT_BEAMS | 光轴 |
| E37 | MOLECULE | 分子 |
| E38 | VINTAGE_TV | 怀旧 |
| E43 | SEASIDE | 海边 |

## ADV 档（11 套）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E13 | LIQUID_GRID | 液态网格 |
| E14 | BEAT_FIREWORK | 节拍烟花 |
| E15 | LIQUID_RIPPLE | 液态涟漪 |
| E16 | MATRIX_RAIN | 数字雨 |
| E17 | CONSTELLATION | 星座 |
| E23 | LYRICS_DOT_MATRIX | 歌词点阵 |
| E29 | ORBITAL_RINGS | 太阳系 |
| E39 | PHOTO_WALL | 照片墙 |
| E40 | DNA | DNA 双螺旋 |
| E42 | STAR_TRAILS | 星空星轨 |
| E44 | MOONLIT | 明月 |

## ULTRA 档（2 套）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E18 | MILKDROP_FEEDBACK | 反馈残像 |
| E41 | WORLD | 世界 |

> `E41 世界` 是 WebView + WebGL 的 3D 地球（完全离线）。归 ULTRA 的理由与 E18 不同：2026-10-06 真机故障
> ——**电视端 WebView 只画首帧**，MEDIUM / LOW 用户会看到一块静止的地球，所有者裁决改为仅最高画质档提供（§10.215）。

## 门控的三个特殊项（容易漏）

1. **E39 照片墙是唯一有「列表级」门控的效果**：`VisualizerTheme.selectable(photoWallAvailable)` 在三个照片来源
   开关全关时把 `PHOTO_WALL` 从列表里过滤掉（指示器不显示、左右键也切不到）。其余 21 套恒在列表里（受画质档约束）。
2. **E14 节拍烟花**：ADV 档里唯一 `needsParticleBudget = true` 且非 ULTRA 的效果 ⇒ `LOW.maxParticles = 0` 时被
   `supports()` 挡下。
3. **E18 / E41**：见上「ULTRA」口径。⛔ 没有任何「按设备能力隐藏效果」的过滤逻辑（`availableThemes()` 只看画质档 +
   照片来源）⇒ E41 在不支持 WebView 上屏的设备上仍会出现于 HIGH 档。

## 已移除效果（v2.37.6 移除 11 个；v2.38.2 再移除 9 个）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E01 | IMMERSIVE_BLOOM | 沉浸辉光 |
| E06 | RADIAL_BURST | 径向星芒 |
| E08 | PARTICLE_STORM | 粒子风暴 |
| E09 | PARTICLE_GALAXY | 粒子银河 |
| E10 | MIRROR_KALEIDO | 万花筒 |
| E21 | PRISM_HOLO | 棱镜彩虹 |
| E22 | AURORA | 极光 |
| E26 | VECTOR_WAVES | 声弦 |
| E27 | PULSING_POLYGONS | 几何环 |
| E28 | BAUHAUS_SHAPES | 构成 |
| E36 | FERMAT_SPIRAL | 螺旋 |
| E03 | TUNNEL_FLY | 隧道穿越 |
| E07 | FREQUENCY_MOUNTAIN | 频率山峦 |
| E11 | GALAXY_SPIRAL | 星系螺旋 |
| E12 | SPECTRO_WATERFALL | 频谱瀑布 |
| E19 | PARTICLE_TEXT | 粒子文字 |
| E20 | PLASMA_FLOW | 等离子流场 |
| E31 | ORIGAMI_POLY | 折纸 |
| E32 | STAIRCASE_WAVE | 阶梯 |
| E34 | FRACTAL_TREE | 分形 |

> 老用户存过的已移除主题键经 `AppSettings.kt` 的 `LEGACY_MAP` 自动回落到默认 `CIRCULAR_RING`（圆形频谱环）。
> ⚠️ 清单靠人工维护：`VisualizerThemeTest` 不核对已删名册是否全部进表 ⇒ **删效果时必须手工补 `LEGACY_MAP`**。
> 已移除序号位保留空号，`ordinalLabel` 唯一性不受影响。
