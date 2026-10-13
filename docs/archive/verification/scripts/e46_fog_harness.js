// E46「迷雾之战」原型的**可重跑验证脚本**：在 Node 的 vm 沙箱里桩掉 DOM/canvas，
// 载入 `docs/archive/e46-tripod-fog-preview.html` 的**原文脚本**，跑 T1–T30 自检 + 三档图元/铺屏计量。
//
// ⛔ **方案文档 §十 的 ops/fill 数字只能由本脚本再生**，不许手抄（E44 偏差 D24 的成因）。
//    改完原型（尤其任何 `op(...)` 调用、档位分支、图层数量）必须重跑本脚本并把新数一并提交。
//
// 用法：node docs/archive/verification/scripts/e46_fog_harness.js            # 自检 + 计量
//       node docs/archive/verification/scripts/e46_fog_harness.js --frames 900  # 追加 900 帧纯逻辑推进（找 NaN/越界）
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..', '..', '..', '..');
const htmlPath = path.join(root, 'docs', 'archive', 'e46-tripod-fog-preview.html');
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
    // ⭐ R28：range 的量程属性必须带进沙箱 —— 闸门 T26 拿 min/max/step 对账"P 表默认值
    //    在浏览器里到底会被夹断/吸附成什么"。⚠️ 沙箱**不做**夹断与吸附（真做就变成
    //    用沙箱行为替浏览器背书），判据由页面里的 checkSliders 自己算。
    min: attrs.min, max: attrs.max, step: attrs.step,
    textContent: '', innerHTML: '', scrollTop: 0, scrollHeight: 0, dataset: {},
    listeners: {}, onclick: null,
    addEventListener: (t, f) => { (el.listeners[t] = el.listeners[t] || []).push(f); },
    dispatch: (t, ev) => { (el.listeners[t] || []).forEach((f) => f(ev || {})); },
    classList: { toggle: () => {} },
  };
  elements.set(id, el);
  return el;
}
for (const m of html.matchAll(/<(input|select|button|div)\b([^>]*)>/g)) {
  const attrs = {};
  for (const a of m[2].matchAll(/(\w+)="([^"]*)"/g)) attrs[a[1]] = a[2];
  if (!attrs.id) continue;
  // ⚠️ **重名 id 必须当场炸**：`elements.set` 是后写的覆盖先写的，浏览器里
  //    getElementById 却返回**文档顺序的第一个** ⇒ 沙箱与真机读到的是两个控件
  //    （R19 的第一版就把新滑杆命名成了 `sSH`，那个 id 已经属于"手持抖动"，
  //     于是 `P.skirtH` 在闸门里启动成抖动值 1.0，量出来一条假红）。
  if (elements.has(attrs.id)) {
    throw new Error('HTML 里 id 重名：' + attrs.id + '（沙箱会静默覆盖，浏览器不会 ⇒ 两套口径）');
  }
  mkEl(attrs.id, attrs);
}
// <select> 的 value 取 selected 项；无 selected 则取首项
for (const m of html.matchAll(/<select id="(\w+)"[^>]*>([\s\S]*?)<\/select>/g)) {
  const opts = [...m[2].matchAll(/<option\b([^>]*)>/g)].map((o) => ({
    v: (o[1].match(/value="([^"]*)"/) || [, o[1].replace(/\s+/g, ' ').trim()])[1],
    sel: /\bselected\b/.test(o[1]),
  }));
  const pick = opts.find((o) => o.sel) || opts[0];
  if (pick) elements.get(m[1]).value = pick.v;
}
elements.get('cBre').checked = false;

// ── canvas 桩：只需支撑原型实际用到的 API（含像素回读，T6 靠它）
function mkCanvas(w, h) {
  const cv = { width: w, height: h, _isCanvas: true, toDataURL: () => 'data:,' };
  cv.getContext = function () { return mkCtx(cv); };
  return cv;
}
function mkCtx(cv) {
  let px = null;                                     // 仅在用到 ImageData 时分配
  const buf = () => (px = px || new Uint8ClampedArray(cv.width * cv.height * 4));
  const grad = () => ({ addColorStop: () => {} });
  return {
    canvas: cv,
    fillStyle: '#000', strokeStyle: '#000', lineWidth: 1, globalAlpha: 1,
    globalCompositeOperation: 'source-over', filter: 'none', font: '10px',
    save: () => {}, restore: () => {}, translate: () => {}, scale: () => {}, rotate: () => {},
    beginPath: () => {}, closePath: () => {}, moveTo: () => {}, lineTo: () => {},
    arc: () => {}, ellipse: () => {}, rect: () => {}, quadraticCurveTo: () => {}, bezierCurveTo: () => {},
    fill: () => {}, stroke: () => {}, clip: () => {}, clearRect: () => {},
    fillRect: () => {}, strokeRect: () => {}, drawImage: () => {},
    createLinearGradient: grad, createRadialGradient: grad, createConicGradient: grad,
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
}
const canvasTag = html.match(/<canvas id="cv" width="(\d+)" height="(\d+)"/);
if (!canvasTag) throw new Error('未找到 <canvas id="cv"> 及其宽高');
const cv = mkCanvas(+canvasTag[1], +canvasTag[2]);
const cvEl = mkEl('cv', { value: '', width: cv.width, height: cv.height });
cvEl.getContext = cv.getContext; cvEl.width = cv.width; cvEl.height = cv.height;
cvEl.toDataURL = cv.toDataURL;
// 原型里 document.getElementById('cv') 拿到的必须就是这同一个对象，capture()/bakeAll() 才会互相可见

const rafQueue = [];
const document = {
  getElementById: (id) => elements.get(id) || null,
  querySelectorAll: () => [],
  createElement: (tag) => (tag === 'canvas' ? mkCanvas(300, 150) : mkEl('_' + Math.random(), {})),
  addEventListener: () => {},
};
const window = { devicePixelRatio: 1, addEventListener: () => {} };
const sandbox = {
  document: document, window: window, Math: Math, JSON: JSON, Date: Date,
  console: console, performance: { now: () => Date.now() },
  requestAnimationFrame: (cb) => { rafQueue.push(cb); return rafQueue.length; },
  cancelAnimationFrame: () => {},
  setTimeout: setTimeout, clearTimeout: clearTimeout,
};
sandbox.window.document = document;
vm.createContext(sandbox);

// boot() 里的 requestAnimationFrame(frame) 只入队不自跑，避免无限循环
vm.runInContext(script, sandbox, { filename: 'e46-tripod-fog-preview.html:script' });
const E = sandbox.window.__E46;
if (!E) throw new Error('原型未导出 window.__E46');

// ── 自检
const results = E.selfTest();
let allOk = true;
console.log('── 自检 ' + results.length + ' 条（T1–T' + results.length + '，条数由页面 selfTest() 自己数，⛔ 在此手抄）──');
for (const r of results) {
  allOk = allOk && !!r.pass;
  console.log((r.pass ? '  ✅ ' : '  ❌ ') + r.id + ' ' + r.name);
  console.log('        ' + r.val);
  console.log('        ' + r.neg);
}

// ── 计量（§十 的唯一数字来源）
const stats = E.stats(60);
console.log('\n── 图元 / 铺屏计量（60 帧均匀采样，__E46.stats(60) 原文输出）──');
console.log('档位     平均图元   平均铺屏(屏)');
for (const k of ['LOW', 'MED', 'HIGH']) {
  console.log(k.padEnd(8) + String(stats[k].ops).padStart(8) + String(stats[k].fill.toFixed(2)).padStart(12));
}

// ── 铺屏分解（8.5 屏 ≫ E44 的 4.21，必须先定位是谁吃掉的再决定砍哪层）
const prof = E.profile(60);
console.log('\n── 逐图元类别分解（60 帧均值）──');
const keys = Object.keys(prof.HIGH).sort((a, b) => prof.HIGH[b].fill - prof.HIGH[a].fill);
console.log('类别'.padEnd(20) + 'HIGH ops  fill     MED ops  fill     LOW ops  fill');
for (const k of keys) {
  const c = (t) => String(prof[t][k] ? prof[t][k].ops.toFixed(2) : '—').padStart(7) + String(prof[t][k] ? prof[t][k].fill.toFixed(3) : '—').padStart(8);
  console.log(k.padEnd(20) + c('HIGH') + '   ' + c('MED') + '   ' + c('LOW'));
}

// ── 推进 90 s 找 NaN / 越界（可选，--frames N）
const framesArg = process.argv.indexOf('--frames');
const N = framesArg > 0 ? parseInt(process.argv[framesArg + 1], 10) : 2161; // 默认 90 s × 24 fps
let bad = 0, sample = null;
for (let i = 0; i < N; i++) {
  const t = (i * 90) / (N - 1);
  const st = E.sceneState(t);
  const nums = [st.d, st.G, st.C, st.vWalk, st.camSpeed, st.yaw].concat(st.beams.flatMap((b) => [b.ex, b.ey, b.ez, b.hx, b.hz, b.depression]));
  const nan = nums.some((v) => !Number.isFinite(v));
  const legBad = st.legs.some((l) => !Number.isFinite(l.q) || !Number.isFinite(l.lift));
  if (nan || legBad) { bad++; if (!sample) sample = { t: t, st: st }; }
}
console.log('\n── 数值健康 ──');
console.log('  ' + N + ' 帧采样，NaN/Inf 帧数 = ' + bad);
if (sample) console.log('  首个异常帧 t=' + sample.t.toFixed(2) + '，d=' + sample.st.d + ' C=' + sample.st.C);

// ── 循环闭环的独立复核（不靠 T4 的 fingerprint，改查参数表整除性）
const div = (p) => (90 / p) % 1 === 0;
const periods = { 'beamPeriod(10)': E.P.beamPeriod, 'driftA(45)': 1 / E.P.driftA, 'driftB(30)': 1 / E.P.driftB,
                  'breath(18)': 1 / E.P.breathHz };
const gate = Object.entries(periods).map(([k, v]) => k + '=' + v + 's ' + (div(v) ? '✅整除90' : '❌不整除'));
console.log('\n── §八 周期整除闸门（独立复核）──');
gate.forEach((g) => console.log('  ' + g));

console.log('\n结论：' + (allOk && bad === 0 && gate.every((g) => g.includes('✅')) ? '全部通过' : '有未通过项'));
process.exitCode = allOk && bad === 0 ? 0 : 1;

// ── 剧本导出量（§3.3 / §八 的权威数字来源，⛔ 手抄）
console.log('\n── 剧本导出量 ──');
console.log('  G(LOOP) = ' + E.gTotal().toFixed(1) + ' m（整数个步幅）⇒ 建筑回收跨度按**每栋自己的** k 取 GTOT÷k：'
  + (function () {
    const b = E.bands();
    const f = (l, tag) => { const ks = Array.from(new Set(l.map((x) => x.k))).sort((p, q) => p - q);
      return tag + ' k∈{' + ks.join(',') + '} → 跨度 ' + ks.map((k) => (E.gTotal() / k).toFixed(1)).join(' / ') + ' m'; };
    return f(b.mid, '中景') + '；' + f(b.near, '近景');
  })());
// 最近位取 **st.dist（真实水平距离）**，⛔ 不能再按最小 d 找：
//   R17 起机器会绕到相机背后，V0 一圈两次穿越 0，"最小 d"抓到的是穿越②（t≈79、d=−70 m）
//   那个**符号**，不是一个位形 —— 打印出来会得到 d=−70 的"对比度 84.2%"和 −826%H 的"整机高度"。
// ⭐ R30：**逐套剧本各印一遍**。戏由纪元轮转（T28），方案文档里三套的数都得有；只印 V0
//   等于给 V1/V2 开空白支票。换戏/还原一律走页面自己的 E.withVariant（⛔ 在脚本里手写
//   applyVariant 再手工还原 —— 漏还原一项，下一套的数字就带着上一套的脏状态）。
// §3.1 尺度核算表：占画面高度比与视口无关（PPM = ppm·H/540 ⇒ 分子分母的 H 相消），
//        所以这里只印比例，px 由 ×720 得到（1280×720 一栏）。⛔ 在文档里手算第二套数。
// ⛔ 顶缘一律取 __E46.topY()（= cowlY + cowlRy，原型自己的口径），
//    不许在这里重写 `bodyY + 0.3 + bodyRy` 这类近似式：加顶罩后两套数就会分叉。
const spanOf = (d) => E.topY() * E.P.ppm / 540 * (E.P.fz / (E.P.fz + d));
const fmtSpan = (d) => '整机 ' + (spanOf(d) * 100).toFixed(0) + '%H（' + (spanOf(d) * 720).toFixed(0) + ' px@720）';
for (let vi = 0; vi < E.VARS.length; vi++) {
  const r = E.withVariant(vi, () => {
    const a = E.sceneState(0), b = E.sceneState(E.P.LOOP);
    let tMin = 0, distMin = Infinity;
    for (let t = 0; t <= E.P.LOOP + 1e-9; t += 0.05) {
      const st = E.sceneState(t); if (st.dist < distMin) { distMin = st.dist; tMin = t; }
    }
    // TCROSS 是**原地改字段**的那一份，applyVariant 里已经 scanCross() ⇒ 读到的就是当套的数
    return { d0: a.d, dl: b.d, n: E.TCROSS.n, t1: E.TCROSS.t1, t2: E.TCROSS.t2,
             distMin: distMin, tMin: tMin };
  });
  const cross = r.n === 0
    ? '一圈**零穿越**（d 恒 ≥ 0 ⇒ 偶数 ✓，机器全程在我们前方）；TCROSS.t1 退回全局最近位'
    : '穿越 d=0 ' + r.n + ' 次：t=' + r.t1.toFixed(1) + ' s（掠过） / t=' + r.t2.toFixed(1)
      + ' s（绕后路过，⛔ 不是最近位）';
  console.log('  ' + E.VARS[vi].name + '：d(0)=' + r.d0.toFixed(1) + ' → d(LOOP)=' + r.dl.toFixed(1)
    + ' m（接缝两端同距离，对比度 ' + (E.contrastAt(Math.max(r.d0, r.dl)) * 100).toFixed(2) + '%）；' + cross
    + '；真实最近 ' + r.distMin.toFixed(1) + ' m @t=' + r.tMin.toFixed(1) + ' s ⇒ 对比度 '
    + (E.contrastAt(r.distMin) * 100).toFixed(1) + '%、' + fmtSpan(r.distMin)
    + '；d=100 m 一栏 ' + (E.contrastAt(100) * 100).toFixed(1) + '%、' + fmtSpan(100));
}
console.log('  （整机屏幕高度一律按**真实距离**算，与 yaw 无关；三档同一条式子）');
