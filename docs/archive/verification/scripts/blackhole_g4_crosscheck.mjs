// E45 黑洞 · G4 对账：原型 HTML 的几何函数 vs. 数表再生器的模型，逐行全精度比对。
//
// 为什么要脚本而不是"看一眼"：方案 §2.2 那张数表和 §五 的参数表都写的是**同一套公式**，
// 但一个是 mjs、一个是浏览器里的 JS。两条代码路径一旦漂移（改了 L0 忘了改 tau、
// 或原型里把 cos(i)^m 写成 cos(i)·m），文档和画面会同时"看着都对"。
// 本脚本把 HTML 的 <script> 原文塞进 vm 沙箱跑，取它**真实调用的那个函数**出来比数，
// ⛔ 不比抄下来的常量（E44 偏差 D24 就是这么产生的）。
//
// 跑法：node docs/archive/verification/scripts/blackhole_g4_crosscheck.mjs
// 退出码：0 = 逐行相等且比对器自证有效；1 = 任一偏差。

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HTML = path.join(HERE, '..', '..', '..', 'blackhole-preview.html');
const TABLE = path.join(HERE, 'blackhole_model_table.mjs');

/* ---------------------------------------------------------- 1. 数表侧 */

const model = JSON.parse(execFileSync(process.execPath, [TABLE, '--json'], { encoding: 'utf8' }));

/* ---------------------------------------------------------- 2. 原型侧（沙箱） */

const src = fs.readFileSync(HTML, 'utf8');
const m = src.match(/<script>([\s\S]*?)<\/script>/);
if (!m) throw new Error('没找到 <script> 块：' + HTML);

const mkEl = (tag) => {
  const e = {
    tagName: tag, children: [], style: {}, dataset: {},
    className: '', textContent: '', innerHTML: '', value: '', checked: false,
    appendChild(c) { this.children.push(c); return c; },
    addEventListener() {}, setPointerCapture() {}, focus() {},
  };
  e.classList = { add() {}, remove() {}, toggle() {}, contains: () => false };
  if (tag === 'canvas') e.getContext = () => mkCtx();
  return e;
};

// ctx 只需要**不被调用**（ringGeom / ringTable 不画图）。若哪天探针依赖了真绘制，
// 这里会抛 TypeError，而不是静默返回假数。
const mkCtx = () => new Proxy({}, {
  get(_, k) {
    if (k === 'createConicGradient' || k === 'createRadialGradient' || k === 'createLinearGradient') {
      return () => ({ addColorStop() {} });
    }
    return () => {};
  },
  set() { return true; },
});

const byId = {};
const sandbox = {
  console, Math, JSON, Number, String, Boolean, Array, Object, Date, isFinite, parseFloat,
  setTimeout: () => 0, clearTimeout: () => {},
  document: {
    getElementById: (id) => (byId[id] ||= mkEl(id === 'cv' ? 'canvas' : 'div')),
    createElement: mkEl,
    addEventListener() {},
    body: mkEl('body'),
  },
  window: {},
  addEventListener() {},
  location: { hash: '' },
  history: { replaceState() {} },
  navigator: {},
  performance: { now: () => 0 },
  requestAnimationFrame: () => 0,
  // 沙箱没有视口：给 0，resize() 自己会夹到 64；像素列不参与对账（数表没有 px）
  innerWidth: 0, innerHeight: 0,
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;

vm.createContext(sandbox);
vm.runInContext(m[1], sandbox, { filename: 'blackhole-preview.html <script>' });

const bh = sandbox.window.__bh;
if (!bh) throw new Error('沙箱里没有 window.__bh —— 脚本没跑完或探针改名了');
const html = bh.params().p;

/* ---------------------------------------------------------- 3. 参数漂移检查 */

const PARAM_MAP = [['L0', 'L0'], ['p', 'lexp'], ['tau', 'tau'], ['m', 'mpow']];
const paramFails = [];
for (const [mKey, hKey] of PARAM_MAP) {
  if (Math.abs(model.params[mKey] - html[hKey]) > 1e-12) {
    paramFails.push(`${mKey}: 数表 ${model.params[mKey]} ≠ 原型 ${html[hKey]}`);
  }
}

/* ---------------------------------------------------------- 4. 逐行全精度比对 */

const TOL = 1e-12;
const rows = [];
const fails = [];
for (const row of model.rows) {
  const g = bh.ringGeom(row.incl, row.r);
  const got = { bNear: g.bNear, bFar: g.bFar, bTer: g.bTer };
  const want = { bNear: row.bNear, bFar: row.bFar, bTer: row.bTer };
  let bad = null;
  for (const k of Object.keys(want)) {
    if (Math.abs(got[k] - want[k]) > TOL) { bad = k; break; }
  }
  if (bad) fails.push(`incl=${row.incl}° r=${row.r} ${bad}: 原型 ${got[bad]} ≠ 数表 ${want[bad]}`);
  rows.push({ incl: row.incl, r: row.r, bFar: got.bFar, bTer: got.bTer, ok: !bad });
}
if (Math.abs(model.RshRs - bh.RshRs) > TOL) fails.push(`R_sh: 原型 ${bh.RshRs} ≠ 数表 ${model.RshRs}`);

/* ---------------------------------------------------------- 5. 比对器自证 */
// 把原型的 tau 改小再比一次：**必须**报出不一致，否则说明上面那个循环是空转的。
bh.setParams({ p: { tau: model.params.tau * 0.9 } });
const negOk = bh.ringGeom(model.rows[0].incl, model.rows[0].r).bTer !== model.rows[0].bTer;

/* ---------------------------------------------------------- 6. 报告 */

console.log('# E45 G4 对账（原型 blackhole-preview.html 的 ringGeom vs. blackhole_model_table.mjs）');
console.log('# T_sh = ' + model.RshRs + ' rs   参数 ' + JSON.stringify(model.params));
console.log('行 | incl |  r/rs  |   b/a二    |   b/a三    | 结果');
for (let i = 0; i < rows.length; i++) {
  const x = rows[i];
  console.log(String(i + 1).padStart(3) + ' | ' + String(x.incl).padStart(4) + '° | ' +
    String(x.r).padStart(6) + ' | ' + x.bFar.toFixed(9) + ' | ' + x.bTer.toFixed(9) +
    ' | ' + (x.ok ? '✓' : '✗'));
}
console.log('');
for (const f of paramFails) console.log('✗ 参数漂移 ' + f);
for (const f of fails) console.log('✗ ' + f);
if (!negOk) console.log('✗ 自证失败：改了 tau 原型数值却没变 ⇒ 比对器是空转的');

if (paramFails.length || fails.length || !negOk) {
  console.log('⇒ G4 不通过（' + (paramFails.length + fails.length) + ' 处偏差）');
  process.exitCode = 1;
} else {
  console.log('⇒ G4 通过：' + rows.length + ' 行逐位相等（1e-12）+ 参数无漂移 + 自证有效');
}
