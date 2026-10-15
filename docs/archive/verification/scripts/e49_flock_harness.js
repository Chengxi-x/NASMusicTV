#!/usr/bin/env node
/* ============================================================================
   E49「鸟群 · 掠过荒野」数值再生 harness —— §十 全部数字的唯一来源
   纯 Node vm 沙箱驱动 docs/e49-birds-flock-preview.html 的真实页面代码：
   无图形设备、无 Math.random（页面用 mulberry32 种子 RNG），同帧序必产同数字。

   用法：
     node docs/archive/verification/scripts/e49_flock_harness.js                 # 默认 warm 300 + 计量 1800 帧（MEDIUM）
     node .../e49_flock_harness.js --frames 3600 --warm 600                      # 自定帧数
     node .../e49_flock_harness.js --tier LOW                                    # 单档（MEDIUM/LOW/HIGH）
     node .../e49_flock_harness.js --all-tiers                                   # 三档各跑一轮（各一次确定性对账）
     node .../e49_flock_harness.js --out logs_temp/e49_golden.json               # 黄金快照落盘

   判定门（任一不过即 exit 1）：
     G1 selfTest 全线无 FAIL（WARN 只报不挡）
     G2 perchDebug.flyBelowHorizon === 0        （飞行鸟不得触及地平线下 2px）
     G3 perchDebug.deep.length === 0            （无 py>0.85 的地下鸟）
     G4 每只栖鸟 bird.x 与 wantX 差 < 0.6px 且 aboveHorizon（栖鸟贴枝、高于地平线）
     G5 确定性：同帧序跑两遍，黄金快照逐字节相等
   ⛔ 本脚本是数字来源：文档里的每个数都从这里抄，不许手抄屏幕。
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = path.dirname(process.argv[1]);
const ROOT = path.resolve(HERE, '..', '..', '..', '..');
const PAGE = path.join(ROOT, 'docs', 'e49-birds-flock-preview.html');
const FRAME_MS = 1000 / 60;                    // 页面 loop(ts) 靠 ts 差推进，喂固定 60fps 帧序即确定性

/* ---- 参数 ---- */
const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf('--' + name);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : dflt;
};
const hasFlag = (name) => args.includes('--' + name);
const WARM_FRAMES = parseInt(opt('warm', '300'), 10);
const RUN_FRAMES = parseInt(opt('frames', '1800'), 10);
const ONE_TIER = opt('tier', 'MEDIUM');
const ALL_TIERS = hasFlag('all-tiers');
const OUT_FILE = opt('out', null);
const TIERS_TO_RUN = ALL_TIERS ? ['LOW', 'MEDIUM', 'HIGH'] : [ONE_TIER];

/* ---- 读页面、抽出 <script> ---- */
const html = fs.readFileSync(PAGE, 'utf8');
const s0 = html.indexOf('<script>');
const s1 = html.lastIndexOf('</script>');
if (s0 < 0 || s1 < 0) throw new Error('未找到 <script> 块');
const script = html.slice(s0 + '<script>'.length, s1);

/* ---- DOM 桩（HTML 内 id 重复即抛，照 E47 口径） ---- */
const idRe = /id="([^"]+)"/g;
const seen = new Set();
let m;
while ((m = idRe.exec(html))) {
  if (seen.has(m[1])) throw new Error('重复 id: ' + m[1]);
  seen.add(m[1]);
}
function mkEl(id) {
  return {
    id, style: {}, className: '', textContent: '', innerHTML: '', value: '',
    checked: false, min: 0, max: 0, step: 0, type: '', onclick: null,
    oninput: null, onchange: null,
    appendChild: () => {},
    addEventListener: () => {},
    classList: { add: () => {}, remove: () => {}, toggle: () => {} },
  };
}
let FILLS = 0, STROKES = 0, BLITS = 0;
function mkCanvas(w, h) {
  const cv = { width: w, height: h, _isCanvas: true, toDataURL: () => 'data:,' };
  cv.getContext = function () { return mkCtx(cv); };
  return cv;
}
function mkCtx(cv) {
  const grad = () => ({ addColorStop: () => {} });
  return {
    canvas: cv,
    fillStyle: '#000', strokeStyle: '#000', lineWidth: 1, globalAlpha: 1,
    globalCompositeOperation: 'source-over', font: '10px', lineCap: 'butt', lineJoin: 'miter',
    save: () => {}, restore: () => {}, translate: () => {}, scale: () => {}, rotate: () => {},
    beginPath: () => {}, closePath: () => {}, moveTo: () => {}, lineTo: () => {},
    arc: () => {}, ellipse: () => {}, rect: () => {}, quadraticCurveTo: () => {}, bezierCurveTo: () => {},
    fill: () => { FILLS++; }, stroke: () => { STROKES++; }, clip: () => {}, clearRect: () => {},
    fillRect: () => { FILLS++; }, strokeRect: () => {},
    drawImage: () => { BLITS++; },
    createLinearGradient: grad, createRadialGradient: grad,
    createPattern: () => null, measureText: (s) => ({ width: String(s).length * 6 }),
  };
}
const elements = new Map();
for (const id of seen) elements.set(id, mkEl(id));

/* ---- 造一个完整世界（跑两遍用于确定性对账） ---- */
function buildWorld(tierName) {
  const cvTag = html.match(/<canvas id="cv" width="(\d+)" height="(\d+)"/);
  if (!cvTag) throw new Error('未找到 <canvas id="cv"> 及其宽高');
  const cv = mkCanvas(+cvTag[1], +cvTag[2]);
  const cvEl = elements.get('cv');
  cvEl.getContext = cv.getContext; cvEl.width = cv.width; cvEl.height = cv.height; cvEl.toDataURL = cv.toDataURL;

  const rafQueue = [];
  const document = {
    getElementById: (id) => elements.get(id) || null,
    querySelectorAll: () => [],
    createElement: (tag) => (tag === 'canvas' ? mkCanvas(300, 150) : mkEl('_x' + Math.random())),
    addEventListener: () => {},
  };
  const window = { devicePixelRatio: 1, addEventListener: () => {} };
  const sandbox = {
    document, window, Math, JSON, Date, console,
    performance: { now: () => Date.now() },
    requestAnimationFrame: (cb) => { rafQueue.push(cb); return rafQueue.length; },
    cancelAnimationFrame: () => {},
    setTimeout, clearTimeout,
    Float32Array, Float64Array, Uint8Array, Uint8ClampedArray, Uint16Array, Uint32Array,
    Int8Array, Int16Array, Int32Array, Array, Object, Number, String, Boolean,
  };
  sandbox.window.document = document;
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox, { filename: 'e49-birds-flock-preview.html:script' });
  const E = sandbox.window.__E49;
  if (!E) throw new Error('原型未导出 window.__E49');
  if (!rafQueue.length) throw new Error('页面加载后 rAF 队列为空（loop 未注册）');

  // 喂固定帧序：每帧从队列取 loop 再由页面自己 re-register
  let ts = 0;
  const drive = (n) => {
    for (let i = 0; i < n; i++) {
      ts += FRAME_MS;
      const cb = rafQueue.shift();
      if (!cb) throw new Error('rAF 队列空：页面没在每帧末尾 re-register loop');
      cb(ts);
    }
  };

  E.applyTier(tierName);          // 重建布局 + 重置鸟群（含种子 RNG 重播种）
  return { E, drive };
}

/* ---- 黄金快照：只取页面对外 API 的输出，四舍五入位与页面一致 ---- */
function snapshot(E) {
  const st = E.selfTest();
  const pd = E.perchDebug();
  const stats = E.stats(60);
  const fails = st.lines.filter((l) => l.includes('FAIL'));
  const warns = st.lines.filter((l) => l.includes('WARN'));
  const perchRowsBad = pd.rows.filter(
    (r) => !r.aboveHorizon || Math.abs(r.bird.x - r.wantX) >= 0.6
  );
  return {
    tier: (stats.match(/档位 (\w+)/) || [])[1] || null,
    selfTest: { pass: st.pass, total: st.total, lines: st.lines },
    fails, warns,
    perch: {
      offT: pd.offT, horizonY: +pd.horizonY.toFixed(1),
      perched: pd.perched,
      flyMinY: pd.flyMinY, flyMaxY: pd.flyMaxY,
      flyBelowHorizon: pd.flyBelowHorizon,
      deepCount: pd.deep.length,
      deep: pd.deep,
      rows: pd.rows,
      rowsBad: perchRowsBad,
    },
  };
}

/* ---- 单档跑：两遍同帧序 → G5 确定性；快照过 G1–G4 ---- */
function runTier(tierName) {
  console.log(`\n══ ${tierName} 档：warm ${WARM_FRAMES} + 计量 ${RUN_FRAMES} 帧（${((WARM_FRAMES + RUN_FRAMES) / 60).toFixed(0)}s 虚拟时长）══`);
  const w1 = buildWorld(tierName);
  w1.drive(WARM_FRAMES + RUN_FRAMES);
  const snap1 = snapshot(w1.E);

  // G5 确定性：全新世界同帧序重放
  const w2 = buildWorld(tierName);
  w2.drive(WARM_FRAMES + RUN_FRAMES);
  const snap2 = snapshot(w2.E);
  const j1 = JSON.stringify(snap1), j2 = JSON.stringify(snap2);

  const gates = [
    ['G1 selfTest 无 FAIL', snap1.fails.length === 0, snap1.fails.join(' | ') || '0 FAIL'],
    ['G2 flyBelowHorizon=0', snap1.perch.flyBelowHorizon === 0, 'flyBelowHorizon=' + snap1.perch.flyBelowHorizon],
    ['G3 deep(地下鸟)=0', snap1.perch.deepCount === 0, 'deep=' + snap1.perch.deepCount],
    ['G4 栖鸟贴枝+高于地平线', snap1.perch.rowsBad.length === 0,
      snap1.perch.rowsBad.length ? JSON.stringify(snap1.perch.rowsBad[0]) : `全部 ${snap1.perch.perched} 只就位`],
    ['G5 确定性（两遍逐字节相等）', j1 === j2, j1 === j2 ? 'identical (' + j1.length + ' B)' : 'MISMATCH — 见下方两份快照'],
  ];
  let ok = true;
  for (const [name, pass, detail] of gates) {
    ok = ok && pass;
    console.log((pass ? '  ✅ ' : '  ❌ ') + name + '  →  ' + detail);
  }
  console.log('  selfTest ' + snap1.selfTest.pass + '/' + snap1.selfTest.total + '：');
  for (const l of snap1.selfTest.lines) console.log('    ' + l);
  if (snap1.warns.length) console.log('  ⚠ WARN（不挡）: ' + snap1.warns.length + ' 条');
  if (j1 !== j2) {
    console.log('  [run1] ' + j1);
    console.log('  [run2] ' + j2);
  }
  return { tier: tierName, gates: gates.map(([n, p, d]) => ({ gate: n, pass: p, detail: d })), snapshot: snap1, deterministic: j1 === j2 };
}

const results = TIERS_TO_RUN.map(runTier);
let allOk = results.every((r) => r.gates.every((g) => g.pass));
console.log('\n──── 总判定：' + (allOk ? '✅ 全部门禁通过' : '❌ 有门禁未过') + ' ────');

if (OUT_FILE) {
  const outPath = path.isAbsolute(OUT_FILE) ? OUT_FILE : path.join(ROOT, OUT_FILE);
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, JSON.stringify({ generatedBy: 'e49_flock_harness.js', page: 'docs/e49-birds-flock-preview.html', warmFrames: WARM_FRAMES, runFrames: RUN_FRAMES, results }, null, 2));
  console.log('黄金快照已写 ' + outPath);
}
process.exit(allOk ? 0 : 1);
