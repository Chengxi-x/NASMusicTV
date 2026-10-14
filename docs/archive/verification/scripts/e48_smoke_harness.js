// E48「大漠·孤烟」原型的**可重跑验证脚本**：在 Node 的 vm 沙箱里桩掉 DOM/canvas，
// 载入 `docs/e48-desert-smoke-preview.html`的**原文脚本**，跑 T1–T14 自检 + B1–B3 预算上限
// + 三档图元/铺屏计量 + 绘制调用对账。
//
// ⛔ **方案文档 §十 的 ops/fill 数字只能由本脚本再生**，不许手抄（E44 偏差 D24 的成因）。
//    改完原型（尤其任何 `op(...)` 调用、档位表、图层数量）必须重跑本脚本并把新数一并提交。
//
// ⚠️ 沙箱里 `arc/ellipse/fill/stroke/drawImage` **只是计数器**，画不出像素 ⇒
//    页面 selfTest 的每条判据必须是**纯几何/纯数值**的；任何"看起来绿"的像素断言在这里恒绿。
//    真实观感判读一律走 `e48_visual_driver.mjs`（无头 Edge + CDP）的最近邻裁块截图。
//    ⇒「烟柱对比度」「骆驼腿部像素数」这类**读感级闸门放不进本脚本**，只能落在 driver 里。
//
// ⭐ **B1–B3 是 harness 侧的判据**（页面 selfTest() 只出 T1–T14，预算上限不在页面里，
//    所以在此断言）。它们守的是 §十 的**安全论证**本身：HIGH 档铺屏若从 3.87 屏回归到
//    5 屏而脚本照样 exit 0，那句「低于 E44 已验收的 4.21 屏 ⇒ 不需要 E47 那种超预算豁免」
//    就悄悄失效了。⛔ 上限**不要**为了让视觉改动过关而放宽 —— 红了就红，那正是它存在的意义。
//
// 用法：node docs/archive/verification/scripts/e48_smoke_harness.js
//       node docs/archive/verification/scripts/e48_smoke_harness.js --frames 5400
//       node docs/archive/verification/scripts/e48_smoke_harness.js --bake 3   # 烘焙计时重复次数（默认 5）
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..', '..', '..', '..');
const htmlPath = path.join(root, 'docs', 'e48-desert-smoke-preview.html');
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
let STROKES = 0, FILLS = 0, BLITS = 0;      // 供"计数口径"复核（页面自己数，这里只做交叉验证）

/** ⭐ 颜色串校验（真浏览器才有的一类错，桩件必须自己变成会抛的）：
 *   · `addColorStop(0, 'rgba(#c57a4e,0)')` ⇒ Chrome **抛 SyntaxError**，整页 boot 死掉
 *     （`__E48` 赋值在 boot 之后 ⇒ 驱动只会报"页面没起来"，看着像慢）。E48 三轮把 hex 的
 *     `tone.top` 拼进了 `rgba()`，**harness 14/14 全绿、浏览器完全起不来**。
 *   · `ctx.fillStyle = '不可解析'` ⇒ Chrome **静默忽略**，沿用上一个颜色 ⇒ 图层悄悄换色，更难发现。
 *  这里对两者一律抛错。顺带抓住 NaN/Infinity：`(.toFixed(3))` 出来的 'NaN' 不匹配 NUM。 */
const NUM_RE = /^[-+]?(?:\d+\.?\d*|\.\d+)(?:%|deg|grad|rad|turn)?$/;
function badColor(s, where) {
  if (typeof s !== 'string') return;                    // 渐变对象 / pattern：不在这里查
  const t = s.trim();
  if (/^#[0-9a-fA-F]{3,8}$/.test(t) || /^[a-zA-Z]+$/.test(t)) return;
  const m = /^(rgb|rgba|hsl|hsla|hwb)\((.*)\)$/i.exec(t);
  const parts = m ? m[2].split(/[\s,/]+/).filter(Boolean) : [];
  if (!m || parts.length < 3 || parts.length > 4 || parts.some((p) => !NUM_RE.test(p))) {
    throw new Error(where + ' 颜色串不可解析：' + JSON.stringify(s));
  }
}

function mkCanvas(w, h) {
  const cv = { width: w, height: h, _isCanvas: true, toDataURL: () => 'data:,' };
  cv.getContext = function () { return mkCtx(cv); };
  return cv;
}
function mkCtx(cv) {
  let px = null;
  const buf = () => (px = px || new Uint8ClampedArray(cv.width * cv.height * 4));
  const grad = () => ({ addColorStop: (o, c) => badColor(c, 'addColorStop') });
  let _fill = '#000', _stroke = '#000';
  const ctx = {
    canvas: cv,
    get fillStyle() { return _fill; },
    set fillStyle(v) { badColor(v, 'fillStyle'); _fill = v; },
    get strokeStyle() { return _stroke; },
    set strokeStyle(v) { badColor(v, 'strokeStyle'); _stroke = v; },
    lineWidth: 1, globalAlpha: 1,
    globalCompositeOperation: 'source-over', font: '10px', lineCap: 'butt', lineJoin: 'miter',
    imageSmoothingEnabled: true,
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
// ⛔ 不注入 Path2D：Kotlin 侧没有对应物，原型里用了也会在真机露馅 ⇒ 沙箱直接让它 undefined
const sandbox = {
  document, window, Math, JSON, Date, console,
  performance: { now: () => Date.now() },
  requestAnimationFrame: (cb) => { rafQueue.push(cb); return rafQueue.length; },
  cancelAnimationFrame: () => {},
  setTimeout, clearTimeout,
  Float32Array, Float64Array, Int32Array, Uint8Array, Uint8ClampedArray, Array, Object, Number, String, Boolean,
};
sandbox.window.document = document;
vm.createContext(sandbox);

const t0 = Date.now();
vm.runInContext(script, sandbox, { filename: 'e48-desert-smoke-preview.html:script' });
const E = sandbox.window.__E48;
if (!E) throw new Error('原型未导出 window.__E48');
console.log('载入 + boot()：' + ((Date.now() - t0) / 1000).toFixed(1) + ' s（rAF 队列 ' + rafQueue.length + '，不自跑）');

// ── 烘焙层齐备性（沙箱里画不出像素，但**图层存在性**是可验的：缺层 = 真机空白）
{
  // ⚠️ 沙丘画布名是**按画布索引**生成的（`dune0..dune{nG-1}`，nG 随档位变 5/7/9）
  //    ⇒ ⛔ 在此写死一份名单：写死的那份和页面实际烘的层数一旦分叉，本检查就变成恒绿的空壳。
  //    ⚠️ E48 三轮分组后 `DUNES` 是**脊线**表（R = nG × sub），逐帧 blit 的是画布 ⇒ 必须按 nG 数。
  const need = ['sky', 'veil', 'grade', 'wisp', 'wispWarm', 'camel'];
  const nG = E.DUNES.length ? E.DUNES[0].nG : 0;
  for (let i = 0; i < nG; i++) need.push('dune' + i);
  const miss = need.filter((k) => !E.B[k]);
  console.log('烘焙层：' + need.length + ' 项（沙丘 ' + E.DUNES.length + ' 道脊 / ' + nG + ' 张画布），缺失 = '
    + (miss.length ? miss.join(',') : '无')
    + '  wisp=' + (E.B.wisp ? E.B.wisp.length : 0) + ' 张  wispWarm=' + (E.B.wispWarm ? E.B.wispWarm.length : 0)
    + ' 张  camel=' + (E.B.camel ? E.B.camel.length : 0) + ' 帧');
  if (miss.length) throw new Error('烘焙层缺失：' + miss.join(','));
}

// ── 自检
const results = E.selfTest();
let allOk = true;
console.log('\n── 自检 ' + results.length + ' 条（条数由页面 selfTest() 自己数，⛔ 在此手抄）──');
// ⚠️ **两行必须带标签**：每条判据都有 `val`（实测）与 `neg`（负样本 = 故意弄坏后的值）。
//    T14 就是踩过这个坑的：实测"最弱露出 27.2 px"，负样本 1.4 px，两行长得一模一样
//    ⇒ 引用的人把 1.4 px 当成实测写进了文档。⛔ 别再把这两行合成一行、或去掉前缀。
for (const r of results) {
  allOk = allOk && !!r.pass;
  console.log((r.pass ? '  ✅ ' : '  ❌ ') + r.id + ' ' + r.name);
  console.log('        实测：' + r.val);
  console.log('        负样本：' + r.neg);
}

// ── 计量（§十 的唯一数字来源）
const stats = E.stats(60);
console.log('\n── 图元 / 铺屏计量（960×540、60 帧均匀采样，__E48.stats(60) 原文输出）──');
console.log('档位  原型ops  Kotlinops  铺屏(屏)  烟丝粒  芯丝op  驼op  沙丘脊/画布  波纹  日晕  纱  切日%');
for (const k of ['LOW', 'MED', 'HIGH']) {
  const s = stats[k];
  console.log(k.padEnd(6) + String(s.ops).padStart(8) + String(s.kotlinOps).padStart(10)
    + String(s.fill.toFixed(2)).padStart(10) + String(s.smoke).padStart(9) + String(s.puffs).padStart(8)
    + String(s.camels).padStart(6) + (s.duneLayers + '/' + s.duneCanvases).padStart(12) + String(s.ripples).padStart(6)
    + String(s.halo).padStart(6) + String(s.veils).padStart(4) + String(s.cutPct).padStart(8));
}

// ── 烘焙墙钟：**median of N**，⛔ 不是真机数字
// ⚠️ 为什么必须取中位数：`stats()` 内嵌的 `bakeAll(1)` 是**单次采样**，而它受冷启动
//    （JIT / 文件缓存 / 同机并发）支配，实测进程间极差可达 5 倍 ⇒ 单次采样当不得数引用。
//    重复 N 次取中位数 = 只留下稳定的量级；真机墙钟在 Kotlin 侧测（本沙箱没有设备含义）。
{
  const bi = process.argv.indexOf('--bake');
  const BN = bi > 0 ? Math.max(1, parseInt(process.argv[bi + 1], 10) || 5) : 5;
  const snap = {}, tier0 = E.P.tier, sw = 960, sh = 540;
  for (const g in E.P) if (E.P[g] && typeof E.P[g] === 'object') snap[g] = Object.assign({}, E.P[g]);
  const med = (a) => { const b = a.slice().sort((x, y) => x - y); return b[Math.floor(b.length / 2)]; };
  const bake = {};
  console.log('\n── 烘焙墙钟（median of ' + BN + '，`__E48.bakeAll(1)` 原地重复，960×540）──');
  for (const k of ['LOW', 'MED', 'HIGH']) {
    E.applyTier(k, true);
    E.resizeTo(sw, sh);                                    // 一次不计时，当 warm-up
    const t = [];
    for (let i = 0; i < BN; i++) t.push(E.bakeAll(1));
    bake[k] = { med: med(t), min: Math.min(...t), max: Math.max(...t), n: t.length };
    console.log('  ' + k.padEnd(5) + 'median=' + String(bake[k].med).padStart(7) + ' ms   区间 ['
      + bake[k].min + ', ' + bake[k].max + '] ms   极差/中位数 = '
      + (bake[k].max / Math.max(1, bake[k].med)).toFixed(2) + '×' + '   逐次 [' + t.join(', ') + ']');
  }
  console.log('  ⚠️ **这是量级论证，不是真机数字**：进程内重复只消掉了 JIT/缓存噪声，'
    + '换一台机器/换一个并发环境仍可差数倍；真机首帧预算（E46 的 24 ms/帧红线）在 Kotlin 侧测。');
  console.log('  ⛔ 本数字**不做断言** —— 它没有设备含义，钉死阈值只会制造一台机器的假红。');
  for (const g in snap) for (const key in snap[g]) E.P[g][key] = snap[g][key];
  E.P.tier = tier0; E.resizeTo(sw, sh);
  console.log('  附：stats() 内嵌的单次采样（未中位数化，仅留痕）= LOW ' + stats.LOW.bakeMs
    + ' / MED ' + stats.MED.bakeMs + ' / HIGH ' + stats.HIGH.bakeMs + ' ms');
}

// ── B1–B3 预算上限断言（harness 侧判据，页面 selfTest() 不含这三项）
// ⭐ 阈值与依据：
//   · 铺屏 HIGH ≤ **4.21** 屏、ops HIGH ≤ **217**：E44「明月」**真机已验收**的 HIGH 档值，
//     本主题的整条安全论证（§十 判读 1「不需要 E47 那种超预算+21% 待裁决的豁免」）就架在这两个数上。
//     ⛔ 上限不得放宽。当前 HIGH 实测 3.87 屏 ⇒ 余量只有 0.34 屏（8%），H2「放大日盘」这类改动
//     会直接吃掉它 —— 红了就红，不要为了让它过关而抬上限。
//   · 铺屏 MED ≤ 3.90 / LOW ≤ 3.60：实测 3.62 / 3.33 之上留 ~8%，与 HIGH 的 3.87→4.21 同一余量哲学，
//     取整到 0.1。**LOW/MED 也要卡**：`comp.sunR` 之类的构图滑杆是逐档生效的，只卡 HIGH 会让
//     低档先悄悄涨穿。
//   · ops MED/LOW 上限由 E44 的 **51.54 op/屏**（217 ÷ 4.21）从各自铺屏上限反推
//     ⇒ LOW/MED 的 ops 闸门与铺屏闸门**永远同口径**，HIGH 那档正好还原成 217。
const E44_OPS = 217, E44_SCREENS = 4.21;
const OPS_PER_SCREEN = E44_OPS / E44_SCREENS;
const SCREEN_CAP = { HIGH: 4.21, MED: 3.90, LOW: 3.60 };
const OPS_CAP = {
  HIGH: E44_OPS,
  MED: Math.floor(SCREEN_CAP.MED * OPS_PER_SCREEN),
  LOW: Math.floor(SCREEN_CAP.LOW * OPS_PER_SCREEN),
};
console.log('\n参考基准（E44「明月」真机已验收 HIGH 档）：217 ops / 4.21 屏 ⇒ '
  + OPS_PER_SCREEN.toFixed(2) + ' op/屏（本档预算的换算率）');
console.log('\n── 预算上限断言 ' + '3 条 ──');
const budget = [];
for (const t of ['HIGH', 'MED', 'LOW']) {
  const s = stats[t];
  const fOk = s.fill <= SCREEN_CAP[t], oOk = s.ops <= OPS_CAP[t];
  const slackF = (SCREEN_CAP[t] - s.fill), slackO = (OPS_CAP[t] - s.ops);
  budget.push({
    id: 'B' + (t === 'HIGH' ? 1 : t === 'MED' ? 2 : 3),
    name: t + ' 档铺屏 ≤ ' + SCREEN_CAP[t].toFixed(2) + ' 屏 且 原型ops ≤ ' + OPS_CAP[t],
    pass: fOk && oOk,
    val: '铺屏 ' + s.fill.toFixed(2) + ' 屏（余量 ' + (slackF >= 0 ? '+' : '') + slackF.toFixed(2) + '，'
      + (s.fill > 0 ? (slackF / s.fill * 100).toFixed(1) : '—') + '%）· ops ' + s.ops
      + '（余量 ' + (slackO >= 0 ? '+' : '') + slackO + '）· Kotlin 折叠后 ' + s.kotlinOps,
    neg: '上限 ' + SCREEN_CAP[t].toFixed(2) + ' 屏 / ' + OPS_CAP[t] + ' op'
      + (t === 'HIGH' ? '（E44 真机已验收值，§十 判读 1 的依据）' : '（由 E44 的 ' + OPS_PER_SCREEN.toFixed(2) + ' op/屏 反推）')
      + ' ⇒ 突破即变红：超出的部分直接读作「本主题需要 E47 那种超预算豁免」',
  });
}
let budgetOk = true;
for (const r of budget) {
  budgetOk = budgetOk && !!r.pass;
  console.log((r.pass ? '  ✅ ' : '  ❌ ') + r.id + ' ' + r.name);
  console.log('        实测：' + r.val);
  console.log('        负样本：' + r.neg);
}
allOk = allOk && budgetOk;

// ── 逐类别分解（谁吃掉了铺屏，降档先按这张表定位）
const prof = E.profile(60);
console.log('\n── 逐图元类别分解（60 帧均值）──');
const keys = Object.keys(prof.HIGH).sort((a, b) => prof.HIGH[b].fill - prof.HIGH[a].fill);
console.log('类别'.padEnd(18) + 'HIGH ops  fill      MED ops  fill      LOW ops  fill');
for (const k of keys) {
  const c = (t) => String(prof[t][k] ? prof[t][k].ops.toFixed(2) : '—').padStart(8)
    + String(prof[t][k] ? prof[t][k].fill.toFixed(3) : '—').padStart(9);
  console.log(k.padEnd(18) + c('HIGH') + '   ' + c('MED') + '   ' + c('LOW'));
}
// ── 降档阶梯（D9）
// ⭐ **哪些量是杠杆、每档降到多少 = 现场从 `__E48.TIERS` 读，⛔ 不在此手抄**：
//    之前的版本把「日晕 3→1 → 沙丘层 9→7→5」写成一句常量文本，那正是 D9 明令禁止的
//    「另处重述成不同次序/不同数字」—— 方案 D9 正文写的是「日晕 2→1 → 沙丘层 3→2」
//    （那是 E44 的档位表），两者已经漂开。下面把 `LEVERS` 降级成**杠杆标识**，
//    数字一律现读；并加了三条护栏，任何一处漂移都会当场炸。
// ⚠️ **诚实声明**：**次序本身在页面里没有数据源**。它是语义裁决 ——「氛围层先砍，
//    烟是需求 ①② 的载体所以最后动」（D9 原话，且明确说它与 §五 的成本分布**相反**）。
//    所以次序只能列在这里；要真正做到单一数据源，得在 HTML 里加一个 `DEGRADE_LADDER`
//    数组由本脚本读取 —— 那需要改页面（designer lane），未获批准，此处不擅自动手。
const LEVERS = [
  { id: 'grade', label: '调色层',   paths: ['fx.grain'],    kill: '或 P.gradeOn=false（bGrade 按钮）' },
  { id: 'sand',  label: '贴地浮沙', paths: ['fx.veils'] },
  { id: 'halo',  label: '日晕',     paths: ['fx.haloLayers'] },
  { id: 'dune',  label: '沙丘层',   paths: ['dune.n', 'dune.sub'] },
];
const TIER3 = ['HIGH', 'MED', 'LOW'];
let ladderOk = true;
const ladderErr = [];
// 载体键（smoke.* / caravan.*）—— D9「⛔ 先动烟」的对象，靠它当场验证阶梯没伸进去
const carrier = new Set();
for (const t of TIER3) for (const k of Object.keys(E.TIERS[t])) {
  if (/^(smoke|caravan)\./.test(k)) carrier.add(k);
}
{
  // 护栏 1：杠杆路径必须三档都在 TIERS 里（改档位表却忘了改这里 → 立刻炸）
  for (const lv of LEVERS) {
    for (const p of lv.paths) {
      for (const t of TIER3) {
        if (!(p in E.TIERS[t])) { ladderOk = false; ladderErr.push('杠杆 ' + lv.label + ' 的 ' + p + ' 不在 TIERS.' + t); }
      }
    }
  }
  // 护栏 2：D9 的「⛔ 先动烟」给上牙齿 —— 阶梯里出现任何 smoke.* / caravan.* 路径就炸
  for (const lv of LEVERS) for (const p of lv.paths) {
    if (carrier.has(p)) { ladderOk = false; ladderErr.push('⛔ 降档阶梯动了载体 ' + p + '（违反 D9「先动烟」）'); }
  }
  // 护栏 3：每个杠杆必须真有落差（否则它是"降档阶梯"里的空条目）
  for (const lv of LEVERS) {
    const drops = lv.paths.some((p) => E.TIERS.HIGH[p] > E.TIERS.LOW[p]);
    if (!drops) { ladderOk = false; ladderErr.push('杠杆 ' + lv.label + ' 在 HIGH→LOW 之间毫无落差 ⇒ 不是杠杆'); }
  }
}
const ladderText = LEVERS.map((lv) => {
  const steps = lv.paths
    .filter((p) => !(E.TIERS.HIGH[p] === E.TIERS.MED[p] && E.TIERS.MED[p] === E.TIERS.LOW[p]))
    .map((p) => E.TIERS.HIGH[p] + '→' + E.TIERS.MED[p] + '→' + E.TIERS.LOW[p]);
  return lv.label + '（' + (steps.length ? steps.join(' · ') : '三档同值，仅开关') + '）'
    + (lv.kill ? '，' + lv.kill : '');
}).join(' → ');
console.log('\n── 降档杠杆顺序（D9 定死次序，⛔ 先动烟）──');
console.log('  ' + ladderText);
console.log('  次序 = ' + LEVERS.map((l) => l.id).join(' → ')
  + '；每档落差现读自 __E48.TIERS（' + TIER3.join('/') + '）。'
  + '⚠️ 次序是语义裁决，页面无数据源 ⇒ 见脚本内注释（要单一源需改 HTML，未获批准）。');
console.log('  ' + (ladderOk ? '✅ 护栏 3/3 通过：杠杆路径齐全 / 未动烟与驼队载体 / 每档真有落差'
  + '（载体键 ' + [...carrier].sort().join(' ') + ' 均不在阶梯里）'
  : '❌ 护栏失败：' + ladderErr.join('；')));
allOk = allOk && ladderOk;

// ── 合批交叉验证：沙箱里真实发生的 stroke / fill / drawImage 次数（⛔ 只信页面自己的计数）
// ⭐ **对账口径（这一段之前的解释是算错的，务必别再用旧说法）**：
//   两个数窗口**不同**，旧的「差额 = 烘焙层一次性调用」是**错的** —— 烘焙发生在下面的 30 帧
//   采样循环**之外**（`before` 快照已把它扣掉），它**不可能**产生任何差额。真实算术是：
//     ① **窗口差**：`stats(60)` 的采样窗起于 `midWaveT()` ≈ 43.5 s（驼队横穿中点 = **最坏帧**），
//        而这里的 30 帧从 `seek(0)` 起、只覆盖 t ∈ [0, 0.5] s ⇒ 此时驼队尚未进场
//        （`beatScan` 给的露头时刻是 ~14.8 s）⇒ 正好少掉「驼队」那几 op。
//     ② **漏记的 fill**：`drawSun()` 对**同一个 arc** 先填径向渐变、再填竖向调色坡，
//        页面只记了 1 次 `op('落日日盘', …)`，但 canvas 侧发出了 **2 次 `fill()`**
//        ⇒ 每次渲染多 1 次画布调用，op 侧看不见。
//   ⇒ 正确关系：`stats.HIGH.ops − 驼队op(t≈43.5s) ≈ 同窗口 op(t≈0) + 1`，两侧口径一致，
//     差额被这两项完全解释，**没有隐藏绘制**。
// ⚠️ 下面的断言用**同窗口**（t≈0 同时采两侧）算残差，才能把 ① 消掉、只留 ② —— 否则等于
//    用一个错误的解释去"验证"一个错误的数。
{
  STROKES = 0; FILLS = 0; BLITS = 0;
  E.applyTier('HIGH', true);
  E.resizeTo(960, 540);
  E.seek(0);
  const before = { s: STROKES, f: FILLS, b: BLITS };
  let opsSum = 0;
  for (let i = 0; i < 30; i++) { E.step(1 / 60); opsSum += E.scene().ops; }
  // ⚠️ **必须在这里就把三个计数器取快照**：`profile()` 自己还要渲染 180 帧，若等到打印时再读
  //    `STROKES - before.s`，读到的就是 30 帧 + 180 帧的合计（分母仍是 30 ⇒ 每帧数翻 7 倍）。
  const n30 = { s: STROKES - before.s, f: FILLS - before.f, b: BLITS - before.b };
  const calls = n30.s + n30.f + n30.b;
  const callsPer = calls / 30, opsPer = opsSum / 30;
  const residual = callsPer - opsPer;                      // 应恰为 +1（drawSun 的第二次 fill）
  const prof = E.profile(60);
  const camelMid = prof.HIGH['驼队'] ? prof.HIGH['驼队'].ops : 0;
  const crossGap = stats.HIGH.ops - camelMid - residual - opsPer;
  console.log('\n── 沙箱实发绘制调用（30 帧，HIGH@960×540，窗口 t∈[0, 0.5]s）──');
  console.log('  stroke=' + n30.s + '  fill/fillRect=' + n30.f
    + '  drawImage=' + n30.b
    + '  ⇒ 每帧 ' + callsPer.toFixed(1) + ' 次'
    + '（同一窗口页面侧计数 ' + opsPer.toFixed(1) + ' op/帧）');
  console.log('  对账：同窗口残差 = ' + callsPer.toFixed(2) + ' − ' + opsPer.toFixed(2) + ' = '
    + residual.toFixed(2) + ' 次/帧 ＝ drawSun 对同一 arc 的第二次 fill（页面发了 fill() 但没记 op()）'
    + '  ⇒ 这 1 次是**已知漏记**，不是隐藏绘制。');
  console.log('  跨窗口对账（解释 ' + stats.HIGH.ops + ' vs ' + callsPer.toFixed(1) + ' 的差）：');
  console.log('    ' + stats.HIGH.ops + '（stats(60)，窗起 t=' + E.midWaveT().toFixed(2) + 's，驼队在场）'
    + ' − ' + camelMid.toFixed(2) + '（驼队 op，t≈0 时不在场）'
    + ' − ' + residual.toFixed(2) + '（漏记 fill）= ' + (stats.HIGH.ops - camelMid - residual).toFixed(2)
    + ' ≈ 同窗口 ' + opsPer.toFixed(2) + '（残 ' + (crossGap >= 0 ? '+' : '') + crossGap.toFixed(2)
    + ' = ' + (Math.abs(crossGap) / opsPer * 100).toFixed(1)
    + '% ＝ 烟三类 α 门限逐帧波动，统计噪声，非口径问题）');
  console.log('  ⛔ 旧输出写的「差额=烘焙层一次性调用」是**错的**：烘焙在 30 帧窗口之外（已由 before 快照扣除），'
    + '产生不了差额。');
  // ⛔ 本次**不改动 T13 的计量口径**（T13 = ΣPROF≡OPS），只加这条独立的一致性检查：
  //    页面不能记比它画的更多的 op（那是虚记预算），也不能多画 2 次以上未记的调用（那才是隐藏绘制）。
  //    残差 1 = drawSun 的竖向调色坡；若日后有人再加一个未记的 fill，这里会立刻变红。
  const reconOk = residual >= -1e-6 && residual <= 1 + 1e-6;
  console.log((reconOk ? '  ✅ ' : '  ❌ ') + 'B4 沙箱实发调用 − 同窗口 op ∈ [0, 1]（唯一允许的漏记 = drawSun 第二次 fill）'
    + '  实测残差 ' + residual.toFixed(3) + ' 次/帧'
    + (reconOk ? '' : ' ⇒ 有未记的隐藏绘制，或页面记了没画的 op（虚记预算）'));
  allOk = allOk && reconOk;
}

// ── 判读时刻表（⛔ 驱动脚本里写死秒数：摆/余烬/驼队时刻由 Hz 与种子表合成，改了就得重算）
const bs = E.beatScan(90);
console.log('\n── 判读时刻（beatScan(90)，喂给 e48_visual_driver.mjs）──');
console.log('  最大摆幅 t=' + bs.tSway + ' s（' + bs.swayMaxPx + ' px）   余烬峰 t=' + bs.tEmber
  + ' s（' + bs.emberMax + '）   上升最快 t=' + bs.tRise + ' s（' + bs.riseMaxPx + ' px/s）');
console.log('  驼队：露头 t=' + bs.tWaveIn + ' s  横穿中点 t=' + bs.tWaveMid + ' s  退场 t=' + bs.tWaveOut
  + ' s  空窗 t=' + bs.tGap + ' s  单波时长=' + bs.cross + ' s  1800s 内波次=' + bs.wavesPer1800);

// ── 数值健康：长跑找 NaN / 越界（--frames N，默认 5400 帧 = 90 s）
const framesArg = process.argv.indexOf('--frames');
const N = framesArg > 0 ? parseInt(process.argv[framesArg + 1], 10) : 5400;
let bad = 0, sample = null, maxDx = 0, uViol = 0, maxLap = 0, minU = 1e9, maxU = -1e9;
E.applyTier('HIGH', true);
E.resizeTo(960, 540);
E.seek(0);
for (let i = 0; i < N; i++) {
  E.step(1 / 60);
  const sc = E.scene();
  const w = E.world, sk = E.smoke, au = E.audio;
  const nums = [w.gust, w.swayMul, w.risePx, w.ember, w.sunGain, w.sandK, w.haze, w.driftPx,
    w.kRise, w.kSway, w.kSun, w.kSand, au.bass, au.mid, au.treble, au.energy, au.section,
    sc.ops, sc.fill, sc.puffs];
  if (nums.some((v) => !Number.isFinite(v))) { bad++; if (!sample) sample = { i, nums }; continue; }
  for (let k = 0; k < sk.n; k++) {
    const uu = sk.u[k], ph = sk.ph[k], vv = sk.vr[k], lp = sk.lap[k];
    if (!Number.isFinite(uu) || !Number.isFinite(ph) || !Number.isFinite(lp)) { bad++; if (!sample) sample = { i, k }; break; }
    if (!(uu >= 0 && uu < 1)) uViol++;
    minU = Math.min(minU, uu); maxU = Math.max(maxU, uu);
    maxLap = Math.max(maxLap, lp);
  }
  // 单帧烟粒沿柱位移上限（TV 上"烟瞬移"就是这一条破了）
  const rise = Math.abs(w.risePx) / 60;
  maxDx = Math.max(maxDx, rise);
}
console.log('\n── 数值健康（' + N + ' 帧推进，HIGH@960×540）──');
console.log('  NaN/Inf 帧数 = ' + bad + (sample ? ' 首个：' + JSON.stringify(sample) : ''));
console.log('  u 越界计数 = ' + uViol + '  u∈[' + minU.toFixed(4) + ', ' + maxU.toFixed(4)
  + ']  最大圈数 = ' + maxLap);
console.log('  单帧最大烟粒沿柱位移 = ' + maxDx.toFixed(2) + ' px（540 高下 '
  + (maxDx / 540 * 100).toFixed(2) + '% 屏高/帧）');
console.log('  末态：' + JSON.stringify(E.scene()));

console.log('\n── 本脚本**管不到**的（⛔ 不是遗漏，是物理上做不到）──');
console.log('  沙箱里 arc/fill/drawImage 只是计数器，`getImageData` 恒返回全 0 ⇒ **任何像素判据在这里恒绿**。');
console.log('  「烟柱与天幕的对比度」「骆驼腿部实际像素数」「烟丝是否糊」这类**读感级闸门**只能落在');
console.log('  e48_visual_driver.mjs（真浏览器 + CDP 截图 + 裁块读像素），归 designer lane。');

const healthOk = bad === 0 && uViol === 0;
console.log('\n结论：' + (allOk && healthOk ? '全部通过' : '有未通过项')
  + '  [自检T×' + results.length + ' + 预算B1–B3 + 对账B4 + 降档护栏 + 数值健康 ' + N + ' 帧]');
process.exitCode = allOk && healthOk ? 0 : 1;
