// E47「雪松」K2/K4/K6 的**黄金基线再生器**：从冻结的浏览器原型取权威期望值，
// 生成 `app/src/test/…/SnowCedarBaseline.kt`。
//
// ⛔ 生成的那个 Kotlin 文件是**机器生成物**，禁止手改 / 手抄其中一个数字
//    （E44 偏差 D24 的同一条纪律：基线数字只能由脚本再生）。
//    改树 / 雪 / 人的数学后必须重跑本脚本，并把再生产物一起提交。
//
// 用法（Node 在 PATH）：
//     node docs/archive/verification/scripts/e47_snow_golden.js
//
// 做法（与同目录 `e47_snow_harness.js` 同一套 vm 沙箱思路）：
//   1. 读 `docs/e47-snow-cedar-preview.html` 的**原文脚本**，桩掉 DOM/canvas 后整体载入
//      （`boot()` 因此完整跑过：applyTier → layout → bakeAll → allocFlakes → allocGlints）；
//   2. 探针表达式**在原型作用域内**求值（`vm.runInContext`）⇒ 取的是页面自己的
//      `mulberry32` / `hash2` / `fbm` / `buildTree` / `meshTable` / `personSil` / `FL` / `GL` / `PR`，
//      ⛔ 在本脚本里另写一份实现（那等于用第二份"可能错得更自洽"的代码去对账）；
//   3. 前置条件 = **页面自己的 12 条自检必须全绿**，否则拒绝再生（基线只有在原型当前是对的
//      时候才有意义；把 selfTest 当闸门而不是事后核对，就不会出现"基线跟着一起写进错误"）；
//   4. 三档各跑一轮（`applyTier(t, true)` + `layout(1600, 900)`，画幅写死 = 原型 `<canvas>` 尺寸）。
//
// ⚠️ 沙箱里 canvas 桩把 arc/fill/stroke/getImageData 记成**空实现或全 0** ⇒
//    任何**像素级**断言在本脚本里都是假绿。基线因此只含纯数学标量
//    （几何、RNG、计数、阈值判定结果、personSil 的**顶点法**面积）。
//
// ⚠️ 跨语言对账的**容差分层**（为什么不是全部逐位）：
//   · 只含整数/乘加的路径（mulberry32、hash2、vnoise/fbm、allocFlakes）⇒ Kotlin 侧按 1e-12 对；
//   · 含 `Math.sin` / `Math.pow` 的路径（snowSurfaceY、meshOffsets、树几何、personSil、dust 时序）⇒
//     JS 与 JVM 的三角/幂不保证逐位一致（不同 libm），一棵树几百次调用会把 1 ulp 积累成**分支翻转**
//     （`n = clamp(round(len/segLenPx),2,6)` 一旦换边就少一段枝）。所以这些按 **1e-9** 对，
//     ⛔ 拿 `fpOf` 指纹跨语言对账 —— 那会红得无法解释。指纹只用于 Kotlin 侧的**内部**确定性自证。
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const HTML = path.join(ROOT, 'docs', 'e47-snow-cedar-preview.html');
const OUT = path.join(
  ROOT, 'app', 'src', 'test', 'java', 'com', 'nasmusic', 'tv',
  'visualizer', 'renderers', 'SnowCedarBaseline.kt',
);
const BW = 1600, BH = 900;      // = 原型 <canvas id="cv"> 的尺寸（树分段数按**像素**子段长算 ⇒ 画幅即口径）

// ─────────────────────────── 1 · 载入原型（DOM / canvas 桩）───────────────────────────
const html = fs.readFileSync(HTML, 'utf8');
const sm = html.indexOf('<script>');
const se = html.lastIndexOf('</script>');
if (sm < 0 || se <= sm) throw new Error('原型里未找到 <script> 段');
const script = html.slice(sm + 8, se);

// 控件的 value/min/max 从 HTML 标记解析（⛔ 在脚本里另写一份默认值，会漂移）
const elements = new Map();
for (const m of html.matchAll(/<(input|select|button|div|canvas|output)\b([^>]*)>/g)) {
  const attrs = {};
  for (const a of m[2].matchAll(/(\w+)="([^"]*)"/g)) attrs[a[1]] = a[2];
  if (!attrs.id) continue;
  if (elements.has(attrs.id)) throw new Error('HTML 里 id 重名：' + attrs.id);
  elements.set(attrs.id, {
    id: attrs.id, value: attrs.value !== undefined ? attrs.value : '', checked: !!attrs.checked,
    min: attrs.min, max: attrs.max, step: attrs.step, textContent: '', innerHTML: '', dataset: {},
    listeners: {}, onclick: null,
    addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); },
    classList: { toggle: () => {}, add: () => {}, remove: () => {} },
  });
}
for (const m of html.matchAll(/<select id="(\w+)"[^>]*>([\s\S]*?)<\/select>/g)) {
  const opts = [...m[2].matchAll(/<option\b([^>]*)>/g)].map((o) => ({
    v: (o[1].match(/value="([^"]*)"/) || [, o[1].replace(/\s+/g, ' ').trim()])[1],
    sel: /\bselected\b/.test(o[1]),
  }));
  const pick = opts.find((o) => o.sel) || opts[0];
  if (pick) elements.get(m[1]).value = pick.v;
}

function mkCanvas(w, h) {
  const cv = { width: Math.max(1, w | 0), height: Math.max(1, h | 0), toDataURL: () => 'data:,' };
  cv.getContext = () => mkCtx(cv);
  return cv;
}
function mkCtx(cv) {
  const grad = () => ({ addColorStop: () => {} });
  const zero = (w, h) => ({
    width: Math.max(1, w | 0), height: Math.max(1, h | 0),
    data: new Uint8ClampedArray(Math.max(1, w | 0) * Math.max(1, h | 0) * 4),
  });
  return {
    canvas: cv,
    fillStyle: '#000', strokeStyle: '#000', lineWidth: 1, globalAlpha: 1,
    globalCompositeOperation: 'source-over', font: '10px', lineCap: 'butt', lineJoin: 'miter',
    imageSmoothingEnabled: true, filter: 'none', shadowBlur: 0, shadowColor: '#000',
    save: () => {}, restore: () => {}, translate: () => {}, scale: () => {}, rotate: () => {},
    transform: () => {}, setTransform: () => {},
    beginPath: () => {}, closePath: () => {}, moveTo: () => {}, lineTo: () => {},
    arc: () => {}, arcTo: () => {}, ellipse: () => {}, rect: () => {}, quadraticCurveTo: () => {},
    bezierCurveTo: () => {}, fill: () => {}, stroke: () => {}, clip: () => {},
    fillRect: () => {}, strokeRect: () => {}, clearRect: () => {}, drawImage: () => {},
    fillText: () => {}, strokeText: () => {}, setLineDash: () => {},
    createLinearGradient: grad, createRadialGradient: grad,
    createPattern: () => null, measureText: (s) => ({ width: String(s).length * 6 }),
    createImageData: (w, h) => zero(w, h),
    getImageData: (x, y, w, h) => zero(w, h),
    putImageData: () => {},
  };
}
const cvTag = html.match(/<canvas id="cv" width="(\d+)" height="(\d+)"/);
if (!cvTag) throw new Error('未找到 <canvas id="cv"> 及其宽高');
if (+cvTag[1] !== BW || +cvTag[2] !== BH) {
  throw new Error(`原型画布 ${cvTag[1]}×${cvTag[2]} 与本脚本写死的 ${BW}×${BH} 不一致 ⇒ 先对齐再跑`);
}
const mainCv = mkCanvas(+cvTag[1], +cvTag[2]);
const cvEl = elements.get('cv');
cvEl.getContext = mainCv.getContext; cvEl.width = mainCv.width; cvEl.height = mainCv.height;
cvEl.toDataURL = mainCv.toDataURL;

const sandbox = {
  document: {
    getElementById: (id) => elements.get(id) || null,
    querySelectorAll: () => [],
    createElement: (tag) => (tag === 'canvas' ? mkCanvas(300, 150) : { style: {}, addEventListener: () => {} }),
    addEventListener: () => {},
  },
  window: { devicePixelRatio: 1, addEventListener: () => {} },
  Math, JSON, Date, console,
  performance: { now: () => Date.now() },
  requestAnimationFrame: () => 1, cancelAnimationFrame: () => {},
  setTimeout, clearTimeout,
  Float32Array, Uint8Array, Uint8ClampedArray, Int32Array, Array, Object, Number, String, Boolean, Error,
};
sandbox.window.document = sandbox.document;
vm.createContext(sandbox);
vm.runInContext(script, sandbox, { filename: 'e47-snow-cedar-preview.html:script' });
if (!sandbox.window.__E47) throw new Error('原型未导出 window.__E47');

/** 在**原型作用域内**求值（页面顶格的 `const` 词法全局可直接取到，⛔ 不必往页面上加导出）。 */
const EV = (expr) => vm.runInContext(expr, sandbox);

// ─────────────────────────── 2 · 前置闸门：页面自己的 12 条自检必须全绿 ───────────────────────────
const tests = EV('selfTest()');
const failed = tests.filter((r) => !r.pass).map((r) => r.id + ' ' + r.name + ' — ' + r.val);
if (failed.length) throw new Error('原型自检未全绿，基线不予再生：\n  ' + failed.join('\n  '));
console.log('前置条件：原型自检 ' + tests.length + ' 条全绿');

// ─────────────────────────── 3 · 探针 ───────────────────────────
// T3 的"贴枝"判据在此**必须**与页面同口径（22° + 垂距 ≤ max(r, 0.004)）。
// ⚠️ 本函数只数一个**整数计数**，Kotlin 侧另有一份；两边靠 `ridgeBad`/`flatBad` 这两个整数
//     钉死 ⇒ 任一侧偷改判据（例如把 22° 放宽）立刻对不上账。
EV(`
var __ridgeBad = function (tree, c, angOverride) {
  const ca = angOverride === undefined ? Math.atan2(c.ty, c.tx) : angOverride;
  for (const s of tree.segs) {
    const dx = s.x2 - s.x1, dy = s.y2 - s.y1, LL = Math.hypot(dx, dy);
    if (LL < 1e-9) continue;
    let da = (Math.atan2(dy, dx) - ca) % Math.PI;
    if (da < 0) da += Math.PI;
    if (da > Math.PI / 2) da -= Math.PI;
    if (Math.abs(da) > 22 * D2R) continue;
    const t = Math.max(0, Math.min(LL, ((c.x - s.x1) * dx + (c.y - s.y1) * dy) / LL));
    if (Math.hypot(c.x - (s.x1 + dx / LL * t), c.y - (s.y1 + dy / LL * t)) <= Math.max(c.r, 0.004)) return false;
  }
  return true;
};
var __mean = function (list) { let s = 0; for (const v of list) s += v; return list.length ? s / list.length : 0; };
var __samples = function (n, k) {                    // 均匀取 k 个下标（含首含尾、去重、确定性）
  const out = []; if (n <= 0) return out;
  for (let i = 0; i < k; i++) out.push(Math.round(n === 1 ? 0 : (i * (n - 1)) / (k - 1)));
  return [...new Set(out)];
};
`);

const TIERS = ['LOW', 'MED', 'HIGH'];
const data = { meta: { bw: BW, bh: BH, seed: EV('P.tree.seed') }, math: {}, tiers: {}, person: {} };

// ── 3a · 只含整数/乘加的随机数与噪声路径（Kotlin 侧按 1e-12 对）
const stream = (seedExpr, n) => EV(`(()=>{const r=mulberry32(${seedExpr});const o=[];for(let i=0;i<${n};i++)o.push(r());return o;})()`);
data.math.rngSeed0 = stream('P.tree.seed', 16);
data.math.rngFlakes = stream('P.tree.seed ^ 0x1234', 16);
data.math.rngGlints = stream('P.tree.seed ^ 0x77a1', 16);
// 步进流的种子是 (seed ^ 0x9e37) + frameIndex ⇒ 取 frame 0 与 frame 7 两条（⛔ 只取 frame 0：
// 那抓不到"帧号根本没进种子"这种缺陷 —— 所有帧会抽到同一条序列）。
data.math.rngStep0 = stream('(P.tree.seed ^ 0x9e37) + 0', 8);
data.math.rngStep7 = stream('(P.tree.seed ^ 0x9e37) + 7', 8);
const HI = [-3, -1, 0, 1, 7, 41, 311, -977, 1023, 65535, 20261014, -20261014];
const HJ = [0, 1, -2, 5, 99, 1777, -331, 4096, 131072, 7, 13, -13];
data.math.hash2I = HI;
data.math.hash2J = HJ;
data.math.hash2 = EV(`(()=>{const I=${JSON.stringify(HI)},J=${JSON.stringify(HJ)};const o=[];for(let i=0;i<I.length;i++)o.push(hash2(I[i],J[i]));return o;})()`);
const NA = [[0, 0], [0.5, 0.5], [1.7, -2.3], [5.5, 3.7], [13.25, 7.03], [0.004, 999.5]];
data.math.fbmArgs = NA;
data.math.fbm = EV(`(()=>{const A=${JSON.stringify(NA)};return A.map(a=>fbm(a[0],a[1],3,0.5,2));})()`);
data.math.vnoise = EV(`(()=>{const A=${JSON.stringify(NA)};return A.map(a=>vnoise(a[0],a[1]));})()`);

// ── 3b · 逐档：树几何 / 网格 / 雪场 / 肩雪时序
for (const tier of TIERS) {
  EV(`applyTier('${tier}', true); layout(${BW}, ${BH}); bakeAll(); allocFlakes(P.snow.flakes); allocGlints(); resetPerson();`);
  EV('var __t = buildTree(treeOpt());');

  const one = {};
  one.segLenPx = EV('treeOpt().segLenPx');
  one.counts = EV('[__t.segs.length, __t.snow.length, __t.clumps.length]');
  one.box = EV('[__t.bbox.x0, __t.bbox.y0, __t.bbox.x1, __t.bbox.y1, __t.maxRatio, __t.truncated ? 1 : 0]');
  one.snowStats = EV(`(()=>{let viol=0;const hs=[];for(const s of __t.snow){
      if(s.horiz < P.tree.snowHMin - 1e-12) viol++; hs.push(s.horiz);}
    let rb=0; for(const c of __t.clumps) if(__ridgeBad(__t,c)) rb++;
    let fb=0; for(const c of __t.clumps) if(__ridgeBad(__t,c,0)) fb++;
    return [viol, __mean(hs), rb, fb, __t.clumps.length];})()`);
  // T3 的**承雪面负样本**（⛔ 让 Kotlin 侧自己判 ≤0.70：阈值两边各写一份会漂，
  // 而"换了判据就是换了一棵树"这件事只有**同一条流**的数字能钉死）。
  // 把承雪面从 |ux| 换成 |uy| ⇒ 雪改挂在陡枝上 ⇒ 平均水平度必须塌下去。
  one.snowFlip = EV(`(()=>{const f=buildTree(treeOpt({snowSign:-1}));
      return [f.segs.length, f.snow.length, f.clumps.length, __mean(f.snow.map(s=>s.horiz))];})()`);
  one.segIdx = EV('__samples(__t.segs.length, 24)');
  one.snowIdx = EV('__samples(__t.snow.length, 16)');
  one.clumpIdx = EV('__samples(__t.clumps.length, 16)');
  one.segs = EV(`(()=>{const o=[];for(const i of ${JSON.stringify(one.segIdx)}){const s=__t.segs[i];o.push([s.x1,s.y1,s.x2,s.y2,s.w,s.depth]);}return o;})()`);
  one.snows = EV(`(()=>{const o=[];for(const i of ${JSON.stringify(one.snowIdx)}){const s=__t.snow[i];o.push([s.x1,s.y1,s.x2,s.y2,s.sw,s.horiz]);}return o;})()`);
  one.clumps = EV(`(()=>{const o=[];for(const i of ${JSON.stringify(one.clumpIdx)}){const c=__t.clumps[i];o.push([c.x,c.y,c.r,c.depth,c.tx,c.ty]);}return o;})()`);
  // 雪脊半径分布五项（min/p10/p50/p90/max，× 1e9 后取整 ⇒ 变成**整数**探针，跨 libm 无歧义）
  one.clumpR = EV(`(()=>{const a=__t.clumps.map(c=>c.r).sort((x,y)=>x-y);
      const q=(p)=>a.length?Math.round(a[Math.min(a.length-1,Math.round(p*(a.length-1)))]*1e9):0;
      return [a.length,q(0),q(0.1),q(0.5),q(0.9),q(1)];})()`);
  // 风摆网格（含 pow ⇒ 1e-9 档）+ 树位图尺寸（§三 的铺屏口径）
  one.mesh = EV(`(()=>{const a=meshTable(12.5, P.wind.rows, 1);return [P.wind.rows, B.treeMeta.w, B.treeMeta.h].concat(a);})()`);
  // 雪场初值：分层条数 + 前 12 粒（allocFlakes 只吃 mulberry32 ⇒ 可逐位对）
  one.flakeCounts = EV('[FL.n].concat(FL.count).concat(FL.off)');
  one.flakes = EV('(()=>{const o=[];for(let i=0;i<12;i++)o.push([FL.x[i],FL.y[i],FL.ph[i],FL.rz[i],FL.layer[i]]);return o;})()');
  one.glintN = EV('GL.n');
  one.glints = EV('(()=>{const o=[];for(let i=0;i<8;i++)o.push([GL.u[i],GL.x[i],GL.y[i],GL.ph[i]]);return o;})()');
  // 逐层消隐线 gY（§十二 K4 第 1 条附加判据）+ 各层 land（绘制先后的唯一口径）
  one.hideY = EV(`(()=>{const gy=[],land=[];for(let li=0;li<LAYERS.length;li++){const Lf=LAYERS[li];
      land.push(Lf.land);
      gy.push(L.hz + (H-L.hz)*Lf.land + 0.5*(snowSurfaceY(W*0.5)-L.hz) + Lf.r*S*0.5);}
    return land.concat(gy);})()`);
  // 肩雪时序：每 150 帧取一次，跑到 60 s（同时验证"既会长厚也会被阵风抖落"）
  one.dust = EV(`(()=>{const o=[];const dt=1/60;for(let i=0;i<=3600;i++){
      if(i%150===0)o.push(PR.dust);
      const t=i*dt; audioSim(t); smoothAudio(dt); worldState(t,dt); stepPerson(dt);}return o;})()`);
  one.dustStat = EV(`(()=>{let lo=1,hi=0,dec=0,prev=0;resetPerson();const dt=1/60;
      for(let i=0;i<10800;i++){const t=i*dt;audioSim(t);smoothAudio(dt);worldState(t,dt);stepPerson(dt);
        if(PR.dust<lo)lo=PR.dust; if(PR.dust>hi)hi=PR.dust;
        if(PR.dust<prev-1e-12)dec++; prev=PR.dust;}
      return [lo,hi,dec];})()`);
  // 世界状态六项（同一批 t 上采样；⛔ 音频只走平滑后的三条包络）
  one.world = EV(`(()=>{const o=[];const dt=1/60;resetPerson();
      for(let i=0;i<=1800;i++){const t=i*dt;audioSim(t);smoothAudio(dt);worldState(t,dt);
        if(i%180===0)o.push([W_.gust,W_.wind,W_.windPx,W_.fallPx,W_.sway,W_.sunGain,W_.swayMul]);}return o;})()`);
  data.tiers[tier] = one;
}

// ── 3c · 人物：`PERSON` 常量表 + 派生几何 + `personSil` 的顶点法测量
//     （⛔ 像素法：沙箱里 arc/ellipse 是空实现，必然读到 0；`personSil` 采集的是**实际消费的顶点**，
//      与 T11 同一份口径 ⇒ 本脚本不另写面积公式，否则两份几何会漂移。）
EV(`applyTier('MED', true); layout(${BW}, ${BH}); bakeAll();`);
const pairs = (expr) => EV(`(()=>{const a=${expr};return a.map(p=>[p[0],p[1]]);})()`);
const silOf = (k) => {
  const s = EV(`personSil(${k})`);
  return {
    head: [s.mass, s.ratio, s.wFrac, s.hFrac, s.polys],
    bars: s.bars.map((b) => [b.x0, b.x1, b.y0, b.y1, b.w, b.h]),
  };
};
const sil = silOf(1);
const silThin = silOf(0.3);            // T11 负样本口径：k=0.3 ⇒ 体量必须塌下去
data.person = {
  // 归一化常量表（原型 §4.5）：Kotlin 侧照抄，抄错一位就在这张表上暴露
  scalars: EV('[PERSON.sh, PERSON.hip, PERSON.hemB, PERSON.chin]'),
  coatW: EV('PERSON.coatW'),
  yokeW: EV('PERSON.yokeW'),
  legW: EV('PERSON.legW'),
  armW: EV('PERSON.armW'),
  caneW: EV('PERSON.caneW'),
  boot: EV('[PERSON.boot.toe, PERSON.boot.heel, PERSON.boot.h, PERSON.boot.w]'),
  poseScalars: EV('[PERSON.stand.scarf, PERSON.stand.lean]'),
  yoke: pairs('PERSON.yoke'),
  R: pairs('PERSON.stand.R'),
  L: pairs('PERSON.stand.L'),
  aR: pairs('PERSON.stand.aR'),
  aL: pairs('PERSON.stand.aL'),
  cane: pairs('PERSON.stand.cane'),
  // 派生几何（⛔ 手算：这四个是原型注释里点名的"两处各写一份会漂移"的那几个函数）
  coatSpine: pairs('coatSpine(PERSON.stand)'),
  shoulderTop: pairs('shoulderTop(PERSON.stand)'),
  shoulderHalf: EV('shoulderHalf()'),
  soleFrac: EV('soleFrac(PERSON.stand.R[2][1])'),
  silHead: sil.head,
  silBars: sil.bars,
  silThinHead: silThin.head,
  // 位图尺寸与 blit 矩形（烘完才知道 ⇒ 归 K3 对账，但口径在这里一次锁死）
  size: EV('[B.personSize.w, B.personSize.h, B.personSize.top, B.personSize.hh, B.personSize.soleY]'),
  rect: EV('(()=>{const r=personRect();return [r.x,r.y,r.w,r.h];})()'),
};

// ─────────────────────────── 4 · 生成 Kotlin ───────────────────────────
function fmt(v) {
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new Error('探针产出非有限数：' + v);
  if (Number.isInteger(v)) return String(v) + '.0';
  let s = String(v);
  if (!/[.en]/.test(s)) s += '.0';
  return s.replace('e+', 'e');
}
const darr = (name, arr) => `    @JvmField val ${name}: DoubleArray = doubleArrayOf(${arr.map(fmt).join(', ')})\n`;
const iarr = (name, arr) => `    @JvmField val ${name}: IntArray = intArrayOf(${arr.map((v) => Math.round(v)).join(', ')})\n`;
function dd2(name, rows) {
  // ⚠️ 行间必须有逗号：`arrayOf(a\n b)` 在 Kotlin 里是**把 b 当成 a 的调用参数**，
  // 直译成 JS 数组字面量就会漏掉它，编译期炸一片 `Expecting ')'`。
  const body = rows.map((r) => `        doubleArrayOf(${r.map(fmt).join(', ')})`).join(',\n');
  return `    @JvmField val ${name}: Array<DoubleArray> = arrayOf(\n${body}\n    )\n`;
}

const DEPTH = { LOW: 5, MED: 7, HIGH: 8 };
let kt = '';
kt += 'package com.nasmusic.tv.visualizer.renderers\n\n';
kt += '// ⛔⛔⛔ 本文件是**机器生成物**：禁止手改、禁止手抄其中一个数字 ⛔⛔⛔\n';
kt += '// 唯一再生源：node docs/archive/verification/scripts/e47_snow_golden.js\n';
kt += '// 改树 / 雪 / 人的数学后必须重跑该脚本，并把再生产物一起提交（E44 偏差 D24 的同一条纪律）。\n';
kt += '//\n';
kt += `// 画幅 ${BW}×${BH} = 原型 <canvas id="cv"> 的尺寸。树的分段数按**像素**子段长算\n`;
kt += '// ⇒ 画幅一变树形就变，本基线只在这一张画幅上成立；单测必须先 layout 到它再对账。\n';
kt += '//\n';
kt += '// 容差分层见再生脚本头部：只含整数/乘加的探针按 1e-12，含 sin/pow 的按 1e-9。\n\n';
kt += '/** E47「雪松」黄金基线（由 `e47_snow_golden.js` 从浏览器原型再生，⛔ 手抄）。 */\n';
kt += 'internal object SnowCedarBaseline {\n\n';
kt += '    /** 与再生脚本一致的画幅口径（单测必须先 `layout(BaseW, BaseH)`）。 */\n';
kt += `    const val BaseW = ${BW}.0\n    const val BaseH = ${BH}.0\n\n`;
kt += '    // ── 数学工具 ───────────────────────────────────────────────────────\n';
kt += darr('RngSeed0', data.math.rngSeed0);
kt += darr('RngFlakes', data.math.rngFlakes);
kt += darr('RngGlints', data.math.rngGlints);
kt += darr('RngStep0', data.math.rngStep0);
kt += darr('RngStep7', data.math.rngStep7);
kt += iarr('Hash2I', data.math.hash2I);
kt += iarr('Hash2J', data.math.hash2J);
kt += darr('Hash2', data.math.hash2);
kt += dd2('NoiseArgs', data.math.fbmArgs);
kt += darr('Fbm', data.math.fbm);
kt += darr('Vnoise', data.math.vnoise);
for (const tier of TIERS) {
  const o = data.tiers[tier];
  kt += `\n    // ── ${tier} 档（depth=${DEPTH[tier]}）──────────────────────────────────────\n`;
  kt += darr(tier + 'SegLenPx', [o.segLenPx]);
  kt += iarr(tier + 'Counts', o.counts);                 // [segs, snow, clumps]
  kt += darr(tier + 'Box', o.box);                      // x0,y0,x1,y1,maxRatio,truncated
  kt += darr(tier + 'SnowStats', o.snowStats);          // viol, meanH, ridgeBad, flatBad, clumps
  kt += darr(tier + 'SnowFlip', o.snowFlip);            // snowSign=-1：segs, snow, clumps, 平均水平度
  kt += iarr(tier + 'SegIdx', o.segIdx);
  kt += dd2(tier + 'Segs', o.segs);
  kt += iarr(tier + 'SnowIdx', o.snowIdx);
  kt += dd2(tier + 'Snows', o.snows);
  kt += iarr(tier + 'ClumpIdx', o.clumpIdx);
  kt += dd2(tier + 'Clumps', o.clumps);
  kt += iarr(tier + 'ClumpR', o.clumpR);                // [n, min,p10,p50,p90,max] ×1e9 取整
  kt += darr(tier + 'Mesh', o.mesh);                    // [rows, treeW, treeH, 每行偏移…]
  kt += iarr(tier + 'FlakeCounts', o.flakeCounts);      // [n, 各层条数…, 各层起点…]
  kt += dd2(tier + 'Flakes', o.flakes);                 // x,y,ph,rz,layer
  kt += darr(tier + 'Glints', o.glints.flat());         // u,x,y,ph × 8
  kt += iarr(tier + 'HideY', o.hideY.map((v) => Math.round(v * 1e6)));   // [各层 land…, 各层 gY×1e6…]
  kt += darr(tier + 'Dust', o.dust);                    // 每 150 帧一次，0…60 s
  kt += darr(tier + 'DustStat', o.dustStat);            // lo, hi, 下降帧数
  kt += dd2(tier + 'World', o.world);                   // gust,wind,windPx,fallPx,sway,sunGain,swayMul
  kt += darr(tier + 'GlintN', [o.glintN]);
}
kt += '\n    // ── 人物（MED 档、同一画幅）────────────────────────────────────────\n';
kt += darr('PersonScalars', data.person.scalars);                    // sh,hip,hemB,chin
kt += darr('PersonCoatW', data.person.coatW);
kt += darr('PersonYokeW', data.person.yokeW);
kt += darr('PersonLegW', data.person.legW);
kt += darr('PersonArmW', data.person.armW);
kt += darr('PersonCaneW', data.person.caneW);
kt += darr('PersonBoot', data.person.boot);                          // toe,heel,h,w
kt += darr('PersonPoseScalars', data.person.poseScalars);            // scarf,lean
kt += dd2('PersonYoke', data.person.yoke);
kt += dd2('PersonR', data.person.R);
kt += dd2('PersonL', data.person.L);
kt += dd2('PersonAR', data.person.aR);
kt += dd2('PersonAL', data.person.aL);
kt += dd2('PersonCane', data.person.cane);
kt += dd2('PersonCoatSpine', data.person.coatSpine);
kt += dd2('PersonShoulderTop', data.person.shoulderTop);
kt += darr('PersonDerived', [data.person.shoulderHalf, data.person.soleFrac]);
kt += darr('PersonSilHead', data.person.silHead);                    // mass,ratio,wFrac,hFrac,polys
kt += dd2('PersonSilBars', data.person.silBars);                     // x0,x1,y0,y1,w,h（÷hh）
kt += darr('PersonSilThin', data.person.silThinHead);
kt += darr('PersonSize', data.person.size);                          // w,h,top,hh,soleY
kt += darr('PersonRect', data.person.rect);                          // x,y,w,h
kt += '}\n';

fs.writeFileSync(OUT, kt, 'utf8');
const kb = (Buffer.byteLength(kt, 'utf8') / 1024).toFixed(1);
console.log('已生成 ' + path.relative(ROOT, OUT).replace(/\\/g, '/') + '（' + kb + ' KiB）');
for (const t of TIERS) {
  const o = data.tiers[t];
  console.log('  ' + t.padEnd(5) + ' segs/snow/clumps=' + o.counts.join('/') +
    '  meanH=' + o.snowStats[1].toFixed(4) +
    '  脱枝=' + o.snowStats[2] + '(必须0) 抹平后脱枝=' + o.snowStats[3] + '/' + o.snowStats[4] +
    '  树位图=' + o.mesh[1] + '×' + o.mesh[2]);
}
console.log('  人物 polys=' + data.person.silHead[4] + ' mass=' + data.person.silHead[0].toFixed(4) +
  ' thinMass=' + data.person.silThinHead[0].toFixed(4));
