// e46_phase_kinematics.js —— §3.3 阶段运动学表再生器（⛔ 数字不许手推/手抄）。
// 前 127 行是 e46_fog_harness.js 的桩头副本（vm 沙箱 + DOM/canvas 桩 + 加载页面原文）。
// ⚠️ 若 harness 头部改动，必须重新截：
//   head -127 docs/archive/verification/scripts/e46_fog_harness.js | sed "s|path.resolve(__dirname, \x27..\x27, \x27..\x27, \x27..\x27, \x27..\x27)|path.resolve(process.cwd())|" > docs/archive/verification/scripts/e46_phase_kinematics.js
// 用法：node docs/archive/verification/scripts/e46_phase_kinematics.js
// ⚠️ camSpeed 的符号约定见方案 §3.3：C'<0 = 后退。
// E46「迷雾之战」原型的**可重跑验证脚本**：在 Node 的 vm 沙箱里桩掉 DOM/canvas，
// 载入 `docs/archive/e46-tripod-fog-preview.html` 的**原文脚本**，跑 T1–T19 自检 + 三档图元/铺屏计量。
//
// ⛔ **方案文档 §十 的 ops/fill 数字只能由本脚本再生**，不许手抄（E44 偏差 D24 的成因）。
//    改完原型（尤其任何 `op(...)` 调用、档位分支、图层数量）必须重跑本脚本并把新数一并提交。
//
// 用法：node docs/archive/verification/scripts/e46_fog_harness.js            # 自检 + 计量
//       node docs/archive/verification/scripts/e46_fog_harness.js --frames 900  # 追加 900 帧纯逻辑推进（找 NaN/越界）
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(process.cwd());
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
const PS = E.PHASES || null;
const K = [[0,10],[10,27],[27,41],[41,54],[54,58],[58,62],[62,70],[70,79],[79,90]];
const N = ['P0 雾中初现','P1 逼近','P2 转身游走','P3 回身再逼近','P4 冲刺','P5 贴身掠过','P6 身后揭示','P7 隐没绕后','P8 回正归位'];
console.log('阶段                        t窗        d(起→止)        dist min→max   v_walk 均值  camSpeed 均值/极值');
for (let i=0;i<9;i++){
  const [a,b]=K[i]; let mn=1e9,mx=-1e9,vs=0,cs=0,cmin=1e9,cmax=-1e9,n=0;
  const d0=E.sceneState(a).d, d1=E.sceneState(b).d;
  for(let t=a;t<=b;t+=0.05){const st=E.sceneState(t);
    if(st.dist<mn)mn=st.dist; if(st.dist>mx)mx=st.dist;
    vs+=st.vWalk; cs+=st.camSpeed; if(st.camSpeed<cmin)cmin=st.camSpeed; if(st.camSpeed>cmax)cmax=st.camSpeed; n++;}
  console.log(N[i].padEnd(24)+String(a).padStart(2)+'-'+String(b).padStart(2)+'s   '+
    (d0.toFixed(0)+'→'+d1.toFixed(0)).padStart(14)+'   '+
    (mn.toFixed(1)+'→'+mx.toFixed(1)).padStart(13)+'   '+(vs/n).toFixed(2).padStart(6)+'      '+
    (cs/n).toFixed(2).padStart(6)+' / '+cmin.toFixed(2)+'…'+cmax.toFixed(2));
}
const s0=E.sceneState(0), sL=E.sceneState(90);
console.log('接缝 d(0)='+s0.d+' d(LOOP)='+sL.d+' mx='+s0.mx+'/'+sL.mx+' psi='+s0.psi.toFixed(4)+'/'+sL.psi.toFixed(4));
