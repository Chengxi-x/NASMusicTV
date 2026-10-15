# E49 鸟群 - 原型
# 文件路径：docs/archive/e49-birds-flock-preview.html
# 作者：自动生成 - 2026-10-14
# 状态：已归档（2026-10-15，第十一轮）—— 历史脚手架，**不要再运行**
#
# ⛔⛔ 本脚本是一次性脚手架：它只写出了原型最初的 HTML 骨架（约 3.5 KB）。
#      真正的原型已在其上迭代到 58 KB（含 Boids 仿真、三条视差带、11 项自检门禁），
#      并已随 v2.38.12 全量移植为 Kotlin（`BirdsFlockRenderer` 等 4 个文件）。
#      **运行本脚本会用 3.5 KB 的骨架覆盖掉那份 58 KB 的原型** ⇒ 破坏性、不可逆。
#      保留它只为追溯「原型是怎么起头的」。
#
# E49 现状（恢复/扩展本效果时先读这两份，不要读本脚本）：
#   · 方案：docs/archive/e49-birds-flock-plan.md（§九 含 D1–D3 偏差记录）
#   · 原型：docs/archive/e49-birds-flock-preview.html（§十 全部数字的唯一再生源，
#           由 docs/archive/verification/scripts/e49_flock_harness.js 驱动）
#   · 实现：app/src/main/java/com/nasmusic/tv/visualizer/renderers/BirdsFlock*.kt
#   · 权威落码记录：docs/technical-overview.md §10.235–§10.236

# 1. 基础结构
content = '''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<meta name="color-scheme" content="dark" />
<title>E49 鸟群 - 原型</title>
<style>
*, *::before, *::after { margin:0; padding:0; box-sizing:border-box; user-select:none; -webkit-user-select:none; }
html, body { margin:0; padding:10px; height:100%; overflow:hidden; background:#170d10; color:#f4ece6; font-family:sans-serif; }
canvas { position:fixed; inset:0; width:100%; height:100%; display:block; background:#0a192f; }
#controls-panel {
  position:fixed; right:10px; top:10px; width:300px; max-height:90vh;
  background:rgba(26,32,44,.95); border:2px solid #4a5568; border-radius:8px;
  padding:15px; box-shadow:0 4px 20px rgba(0,0,0,.5); overflow-y:auto; z-index:1000;
}
.panel h2 { margin:0 0 15px; color:#64ffda; text-align:center; font-size:18px; }
.slider-group { margin-bottom:12px; }
.slider-group label { display:block; font-size:10px; color:rgba(244,236,230,.6); margin-bottom:2px; }
.slider { width:100%; height:6px; -webkit-appearance:none; background:rgba(244,236,230,.15); border-radius:3px; }
.slider::-webkit-slider-thumb { -webkit-appearance:none; width:10px; height:16px; background:#64ffda; border-radius:2px; cursor:pointer; }
.slider-value { display:inline-block; font-size:10px; color:#64ffda; width:50px; }
.btn-row { display:flex; gap:6px; margin-top:6px; }
.btn { flex:1; padding:6px 4px; border:1px solid rgba(244,236,230,.2); background:rgba(26,32,44,.4); color:#e0e0e0; font-size:9px; border-radius:4px; cursor:pointer; transition:all .15s; }
.btn:hover { background:rgba(100,255,218,.15); border-color:#64ffda; }
.btn.active { background:#64ffda; color:#0a192f; }
.hud { position:fixed; left:16px; top:16px; z-index:10; font-family:"SF Mono",monospace; font-size:11px; line-height:1.5;
 color:rgba(244,236,230,.7); text-shadow:0 0 4px rgba(0,0,0,.5); background:rgba(10,15,25,.4); padding:8px 12px;
 border-radius:6px; border:1px solid rgba(244,236,230,.1); }
.hud .label { color:#64ffda; }
.hud-bar { height:2px; background:rgba(244,236,230,.1); margin:2px 0; border-radius:1px; overflow:hidden; }
.hud-bar-fill { height:100%; background:#64ffda; width:0%; transition:width .3s ease; }
</style>
</head>
<body>
<canvas id="cv" width="1600" height="900"></canvas>
<div id="gate"><h1>鸟群</h1><p>点击解锁参数面板</p></div>
<div class="hud" id="hud">
  <div><span class="label">帧速:</span> <span id="fps">0</span>ms / <span id="ms">0</span>ms</div>
  <div><span class="label">鸟数:</span> <span id="birds">0</span></div>
  <div><span class="label">图元:</span> <span id="ops">0</span>/<span id="opmax">30</span></div>
  <div><span class="label">铺屏:</span> <span id="fill">0.0</span></div>
  <div class="hud-bar"><div class="hud-bar-fill" id="fpsbar"></div></div>
  <div><span class="label">层:</span> far:<span id="l0">0</span> mid:<span id="l1">0</span> near:<span id="l2">0</span> hero:<span id="l3">0</span></div>
  <div><span class="label">侧倾中位数:</span> <span id="roll">0</span>°</div>
  <div><span class="label">停树中:</span> <span id="perched">0</span></div>
  <div><span class="label">判定:</span> <span id="checks"></span></div>
</div>
</body>
</html>
'''
with open('docs/archive/e49-birds-flock-preview.html', 'w', encoding='utf-8') as f:
    f.write(content)
print("HTML structure written")