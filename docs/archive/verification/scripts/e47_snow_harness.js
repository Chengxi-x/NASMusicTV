// E47「雪松」原型的**可重跑验证脚本**：在 Node 的 vm 沙箱里桩掉 DOM/canvas，
// 载入 `docs/e47-snow-cedar-preview.html` 的**原文脚本**，跑 T1–T10 自检 + 三档图元/铺屏计量。
//
// ⛔ **方案文档 §五 的 ops/fill 数字只能由本脚本再生**，不许手抄（E44 偏差 D24 的成因）。
//    改完原型（尤其任何 `op(...)` 调用、档位表、图层数量）必须重跑本脚本并把新数一并提交。
//
// 用法：node docs/archive/verification/scripts/e47_snow_harness.js
//       node docs/archive/verification/scripts/e47_snow_harness.js --frames 5400
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..', '..', '..', '..');
const htmlPath = path.join(root, 'docs', 'e47-snow-cedar-preview.html');
const html = fs.readFileSync(htmlPath, 'utf8');

const sm = html.indexOf('<script>');
const se = html.lastIndexOf('</script>');
if (sm < 0 || se <= sm) throw new Error('原型里未找到 <script> 段');
const script = html.slice(sm + '<script>'.length, se);

// ── DOM 桩：控件的值直接从 HTML 标记里解析（⛔ 别在脚本里另写一份默认值，会漂移）
const elements = new Map();
function mkEl(id, attrs) {
  const el = {
    id: id, value: attrs.value !== undefined ? attrs.value : '', checked: !!attrs.checked,
    min: attrs.min, max: attrs.max, step: attrs.step,
    textContent: '', innerHTML: '', dataset: {},
    listeners: {}, onclick: null,
    addEventListener: (t, f) => { (el.listeners[t] = el.listeners[t] || []).push(f); },
    dispatch: (t, ev) => { (el.listeners[t] || []).forEach((f) => f(ev || {})); },
    classList: { toggle: () => {}, add: () => {}, remove: () => {} },
  };
  elements.set(id, el);
  return el;
}
for (const m of html.matchAll(/<(input|select|button|div|canvas|output)\b([^>]*)>/g)) {
  const attrs = {};
  for (const a of m[2].matchAll(/(\w+)="([^"]*)"/g)) attrs[a[1]] = a[2];
  if (!attrs.id) continue;
  // ⚠️ **重名 id 必须当场炸**：沙箱是后写覆盖先写，浏览器 getElementById 返回文档顺序第一个
  //    ⇒ 两套口径（E46 R19 就因此把新滑杆读成了旧控件的值）。
  if (elements.has(attrs.id)) {
    throw new Error('HTML 里 id 重名：' + attrs.id + '（沙箱会静默覆盖，浏览器不会 ⇒ 两套口径）');
  }
  mkEl(attrs.id, attrs);
}
for (const m of html.matchAll(/<select id="(\w+)"[^>]*>([\s\S]*?)<\/select>/g)) {
  const opts = [...m[2].matchAll(/<option\b([^>]*)>/g)].map((o) => ({
    v: (o[1].match(/value="([^"]*)"/) || [, o[1].replace(/\s+/g, ' ').trim()])[1],
    sel: /\bselected\b/.test(o[1]),
  }));
  const pick = opts.find((o) => o.sel) || opts[0];
  if (pick) elements.get(m[1]).value = pick.v;
}

// ── canvas 桩
let STROKES = 0, FILLS = 0, BLITS = 0;      // 供"计数口径"复核（T5 自己数，这里只做交叉验证）
function mkCanvas(w, h) {
  const cv = { width: w, height: h, _isCanvas: true, toDataURL: () => 'data:,' };
  cv.getContext = function () { return mkCtx(cv); };
  return cv;
}
function mkCtx(cv) {
  let px = null;
  const buf = () => (px = px || new Uint8ClampedArray(cv.width * cv.height * 4));
  const grad = () => ({ addColorStop: () => {} });
  const ctx = {
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
    createImageData: (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) }),
    putImageData: (im, x, y) => {
      const b = buf(), W = cv.width, H = cv.height;
      for (let j = 0; j < im.height; j++) {
        const yy = y + j; if (yy < 0 || yy >= H) continue;
        for (let i = 0; i < im.width; i++) {
          const xx = x + i; if (xx < 0 || xx >= W) continue;
          const s = (j * im.width + i) * 4, dd = (yy * W + xx) * 4;
          b[dd] = im.data[s]; b[dd + 1] = im.data[s + 1]; b[dd + 2] = im.data[s + 2]; b[dd + 3] = im.data[s + 3];
        }
      }
    },
    getImageData: (x, y, w, h) => {
      const b = buf(), out = new Uint8ClampedArray(w * h * 4);
      for (let j = 0; j < h; j++) {
        const yy = Math.min(cv.height - 1, Math.max(0, y + j));
        for (let i = 0; i < w; i++) {
          const xx = Math.min(cv.width - 1, Math.max(0, x + i));
          const s = (j * w + i) * 4, dd = (yy * cv.width + xx) * 4;
          out[s] = b[dd]; out[s + 1] = b[dd + 1]; out[s + 2] = b[dd + 2]; out[s + 3] = b[dd + 3];
        }
      }
      return { width: w, height: h, data: out };
    },
  };
  return ctx;
}
const canvasTag = html.match(/<canvas id="cv" width="(\d+)" height="(\d+)"/);
if (!canvasTag) throw new Error('未找到 <canvas id="cv"> 及其宽高');
const cv = mkCanvas(+canvasTag[1], +canvasTag[2]);
const cvEl = elements.get('cv');
cvEl.getContext = cv.getContext; cvEl.width = cv.width; cvEl.height = cv.height; cvEl.toDataURL = cv.toDataURL;

const rafQueue = [];
const document = {
  getElementById: (id) => elements.get(id) || null,
  querySelectorAll: () => [],
  createElement: (tag) => (tag === 'canvas' ? mkCanvas(300, 150) : mkEl('_x' + Math.random(), {})),
  addEventListener: () => {},
};
const window = { devicePixelRatio: 1, addEventListener: () => {} };
const sandbox = {
  document, window, Math, JSON, Date, console,
  performance: { now: () => Date.now() },
  requestAnimationFrame: (cb) => { rafQueue.push(cb); return rafQueue.length; },
  cancelAnimationFrame: () => {},
  setTimeout, clearTimeout,
  Float32Array, Uint8Array, Uint8ClampedArray, Int32Array, Array, Object, Number, String, Boolean,
};
sandbox.window.document = document;
vm.createContext(sandbox);

const t0 = Date.now();
vm.runInContext(script, sandbox, { filename: 'e47-snow-cedar-preview.html:script' });
const E = sandbox.window.__E47;
if (!E) throw new Error('原型未导出 window.__E47');
console.log('载入 + boot()：' + ((Date.now() - t0) / 1000).toFixed(1) + ' s（rAF 队列 ' + rafQueue.length + '，不自跑）');

// ── 自检
const results = E.selfTest();
let allOk = true;
console.log('\n── 自检 ' + results.length + ' 条（条数由页面 selfTest() 自己数，⛔ 在此手抄）──');
for (const r of results) {
  allOk = allOk && !!r.pass;
  console.log((r.pass ? '  ✅ ' : '  ❌ ') + r.id + ' ' + r.name);
  console.log('        ' + r.val);
  console.log('        ' + r.neg);
}

// ── 计量（§五 的唯一数字来源）
const stats = E.stats(60);
console.log('\n── 图元 / 铺屏计量（960×540、60 帧均匀采样，__E47.stats(60) 原文输出）──');
console.log('档位   原型 ops   Kotlin ops   铺屏(屏)   树段/雪段/雪脊   网格行   雪粒');
for (const k of ['LOW', 'MED', 'HIGH']) {
  const s = stats[k];
  console.log(k.padEnd(7) + String(s.ops).padStart(8) + String(s.kotlinOps).padStart(13)
    + String(s.fill.toFixed(2)).padStart(12)
    + String(s.segs + '/' + s.snowSegs + '/' + s.clumps).padStart(18)
    + String(s.rows).padStart(10) + String(s.flakes).padStart(8));
}

// ── 逐类别分解（谁吃掉了铺屏，砍层先按这张表定位）
const prof = E.profile(60);
console.log('\n── 逐图元类别分解（60 帧均值）──');
const keys = Object.keys(prof.HIGH).sort((a, b) => prof.HIGH[b].fill - prof.HIGH[a].fill);
console.log('类别'.padEnd(18) + 'HIGH ops  fill      MED ops  fill      LOW ops  fill');
for (const k of keys) {
  const c = (t) => String(prof[t][k] ? prof[t][k].ops.toFixed(2) : '—').padStart(8)
    + String(prof[t][k] ? prof[t][k].fill.toFixed(3) : '—').padStart(9);
  console.log(k.padEnd(18) + c('HIGH') + '   ' + c('MED') + '   ' + c('LOW'));
}

// ── 合批交叉验证：沙箱里真实发生的 stroke / drawImage 次数（⛔ 只信页面自己的计数）
{
  STROKES = 0; FILLS = 0; BLITS = 0;
  E.applyTier('HIGH', true);
  E.resizeTo(960, 540);
  E.seek(0);
  const before = { s: STROKES, f: FILLS, b: BLITS };
  for (let i = 0; i < 30; i++) E.step(1 / 60);
  console.log('\n── 沙箱实发绘制调用（30 帧，HIGH@960×540）──');
  console.log('  stroke=' + (STROKES - before.s) + '  fill/fillRect=' + (FILLS - before.f)
    + '  drawImage=' + (BLITS - before.b)
    + '  ⇒ 每帧 ' + ((STROKES - before.s + FILLS - before.f + BLITS - before.b) / 30).toFixed(1)
    + ' 次（原型侧计数 ' + stats.HIGH.ops + ' op/帧，差额=烘焙层一次性调用）');
}

// ── 数值健康：长跑找 NaN / 越界（--frames N，默认 5400 帧 = 90 s）
const framesArg = process.argv.indexOf('--frames');
const N = framesArg > 0 ? parseInt(process.argv[framesArg + 1], 10) : 5400;
let bad = 0, sample = null, maxDx = 0;
for (let i = 0; i < N; i++) {
  E.step(1 / 60);
  const sc = E.scene();
  const W2 = E.world, P2 = E.flakes, PR = E.person;
  const nums = [W2.wind, W2.windPx, W2.fallPx, W2.sway, W2.sunGain, W2.kWind, W2.kGust, W2.kSun,
                PR.dust, E.personRect().x, sc.ops, sc.fill];
  if (nums.some((v) => !Number.isFinite(v))) { bad++; if (!sample) sample = { i, nums }; continue; }
  const fx = P2.x, fy = P2.y;
  for (let k = 0; k < fx.length; k += 37) {
    if (!Number.isFinite(fx[k]) || !Number.isFinite(fy[k])) { bad++; if (!sample) sample = { i, k }; break; }
  }
  // 每帧位移上限（TV 上"雪粒瞬移"就是这一条破了）
  const dxF = Math.abs(W2.windPx) / 60 + Math.abs(fx[0] || 0) * 0;
  maxDx = Math.max(maxDx, dxF);
}
console.log('\n── 数值健康（' + N + ' 帧推进）──');
console.log('  NaN/Inf 帧数 = ' + bad + (sample ? ' 首个：' + JSON.stringify(sample) : ''));
console.log('  单帧最大雪粒水平位移 = ' + maxDx.toFixed(1) + ' px（960 宽下 '
  + (maxDx / 960 * 100).toFixed(2) + '% 屏宽/帧）');
console.log('  末态：' + JSON.stringify(E.scene()));

console.log('\n结论：' + (allOk && bad === 0 ? '全部通过' : '有未通过项'));
process.exitCode = allOk && bad === 0 ? 0 : 1;
