// E48「大漠·孤烟」原型的**真截图驱动**：headless Edge + CDP 打开 docs/e48-desert-smoke-preview.html，
// 用 __E48_capture(w,h,t) 出**定稿帧**给所有者判读观感。
//
// ⚠️ 数值对账（自检 + 图元/铺屏计量）不走这里，走 e48_smoke_harness.js（纯 vm 沙箱，无图形设备）。
//    本脚本只负责「画面长什么样」，⛔ 不要拿它当判据来源。
//
// 用法：
//   node docs/archive/verification/scripts/e48_smoke_harness.js   # 先跑这个拿时刻表
//   node docs/archive/verification/scripts/e48_visual_driver.mjs phases     # 七个判读位各一帧（MED）
//   node docs/archive/verification/scripts/e48_visual_driver.mjs tiers      # 同一时刻三档对比
//   node .../e48_visual_driver.mjs phasediff                               # 七个判读位两两平均通道差（是否"同一个画面"）
//   node .../e48_visual_driver.mjs motion 0 150 16                    # 逐帧差异度 + 烟柱摆幅跨度（趋势尺）
//   node .../e48_visual_driver.mjs wisps 300                               # 烟丝判读表（6 变体 × 芯/暖边）
//   node .../e48_visual_driver.mjs t 32.63 HIGH myshot P.smoke.amp=0.015   # 指定 t/档位/文件名 + 覆盖
//   node .../e48_visual_driver.mjs zoom 32.63 MED smoke 8                  # 整帧 full_* + 最近邻裁块 zoom_*
//   node .../e48_visual_driver.mjs zoom 60 MED dune 3 gradeOn=0            # 同上 + 关掉调色层做对照（销疑颗粒 vs 烘焙artifact）
//   node .../e48_visual_driver.mjs probe "JSON.stringify(window.__E48.scene())"
//
// 判读位⛔写死秒数：摆/余烬/驼队时刻由页面 beatScan() 按 Hz 与种子表现算（参数一改就变）。
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9338;                                   // ⛔ 别和 seaside(9333)/moonlit(9334)/fog(9336)/snow(9337) 撞
const PAGE = resolve(ROOT, 'docs', 'e48-desert-smoke-preview.html');
const URL_STR = 'file:///' + PAGE.replace(/\\/g, '/');
const OUT = resolve(ROOT, 'output', 'e48_frames');
const SHOT_W = 1280, SHOT_H = 720;
// ⚠️ 每次导航都带 ?v=：复用常驻浏览器时同一 URL 会命中文档缓存，改完原型重跑却截到上一版的图。
const navUrl = () => URL_STR + '?v=' + Date.now();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function reuse() {
  try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { await pageTarget(); return true; } }
  catch (e) { /* 没开端口 */ }
  return false;
}
async function launch() {
  const udd = OUT + '_profile';
  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${udd}`, `--remote-debugging-port=${PORT}`,
    '--window-size=1600,900', '--force-device-scale-factor=1', '--hide-scrollbars',
    '--mute-audio', 'about:blank',
  ];
  const proc = spawn(EDGE, args, { stdio: 'ignore' });
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) return proc; } catch (e) { /* 端口还没起 */ }
    await sleep(250);
  }
  throw new Error('Edge 没开出调试端口');
}
async function pageTarget() {
  for (let i = 0; i < 60; i++) {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const p = list.find((t) => t.type === 'page');
    if (p) return p;
    await sleep(200);
  }
  throw new Error('没有 page target');
}
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) {
        const { resolve: res, reject: rej } = c.pending.get(m.id);
        c.pending.delete(m.id);
        m.error ? rej(new Error(m.error.message)) : res(m.result);
      }
    };
    return c;
  }
  send(method, params = {}) {
    return new Promise((res, rej) => {
      const id = ++this.id;
      this.pending.set(id, { resolve: res, reject: rej });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
}
const ev = (cdp, expr) => cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true })
  .then((r) => { if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails)); return r.result.value; });

/** 覆盖项：只认 `键` / `对象.键` / `对象.子.键 = 数`（⛔ 不接受任意表达式）。布尔键必须列进 BOOLS，
 *  否则 `gradeOn=0` 会把 true/false 键塞进数值通道，读起来像"设了个 0"而不是关掉了层。
 *  ⚠️ 路径深度原本只放行**一段点**，于是 `P.dune.litK=0`（关掉阳/阴坡铺色做对照判读）被 filter
 *     **静默丢弃** ⇒ 两张图逐像素相同，却会被读成"这一层对画面没有影响"。现在深度放到两段，
 *     并且**含 `=` 却不匹配的参数直接抛错** —— 覆盖项被吞是判读事故，不是可容忍的宽松解析。 */
const BOOLS = new Set(['gradeOn', 'paused', 'audioSim']);
const OV_RE = /^[A-Za-z]\w*(?:\.\w+){0,2}=-?[\d.]+$/;
function parseOv(list) {
  return (list || []).map((s) => {
    if (OV_RE.test(s)) return s;
    if (s.includes('=')) throw new Error(`覆盖项写法不被接受：${s}（须为 键 / 对象.键 / 对象.子.键 = 数）`);
    return null;
  }).filter(Boolean).map((s) => {
    const i = s.indexOf('=');
    const k = s.slice(0, i), v = parseFloat(s.slice(i + 1));
    if (!BOOLS.has(k.split('.').pop())) return [k, v];
    if (!(v === 0 || v === 1)) throw new Error(`布尔覆盖 ${k} 只接受 0 或 1`);
    return [k, v ? 'true' : 'false'];
  });
}

/** 判读位由页面自己算（beatScan 纯推进⛔渲染）：摆/余烬/升速峰由两个不可通拍频 + 驼队种子表合成，
 *  ⛔ 在脚本里写死秒数 —— 参数表一改判读帧就取错地方。 */
const findBeats = (cdp) => ev(cdp, `window.__E48.beatScan(150)`);

async function shot(cdp, t, tier, name, ov) {
  mkdirSync(OUT, { recursive: true });
  // 覆盖项只认 `对象.字段=数`（argv 里已按正则筛过），⛔ 接受任意表达式
  const setOv = (ov || []).map((kv) => `E.${kv[0]} = ${kv[1]};`).join(' ');
  const info = await ev(cdp, `(() => {
    const E = window.__E48;
    E.applyTier('${tier}', true); ${setOv}
    const url = window.__E48_capture(${SHOT_W}, ${SHOT_H}, ${t});
    const sc = E.scene();
    return { ops: sc.ops, fill: +sc.fill.toFixed(3), puffs: sc.puffs, lap: sc.lap,
             sway: +sc.swayMul.toFixed(3), rise: +sc.risePx.toFixed(1), ember: +sc.ember.toFixed(3),
             sun: +sc.sun.toFixed(3), wave: sc.wave, cut: sc.cutPct, b64: url.split(',')[1] };
  })()`);
  const buf = Buffer.from(info.b64, 'base64');
  writeFileSync(`${OUT}/${name}.png`, buf);
  // ⭐ 记下 SHA-256：判读位如果悄悄退化成**同一帧**（改参数时最常见的判读事故 —— 两个判读位
  //    由同一个量驱动，峰值必然落在同一帧），光看图是看不出来的，两张图看起来"都挺对"。
  //    实测就发生过：a2「余烬峰」与 a3「升速最快」SHA-256 完全相同 ⇒ 第二个判读位是假的。
  const sha = createHash('sha256').update(buf).digest('hex').slice(0, 16);
  SHOTS.push({ name, t: +Number(t).toFixed(2), sha });
  console.log(`  ${name}.png  t=${t.toFixed(2)}s 档位=${tier}  ops=${info.ops} 铺屏=${info.fill}`
    + ` 烟丝=${info.puffs}(圈Σ${info.lap}) 摆×=${info.sway} 升=${info.rise}px/s 余烬=${info.ember}`
    + ` 驼队=${info.wave} 切日=${info.cut}%  sha=${sha}`);
  return `${OUT}/${name}.png`;
}
/** 本次运行截过的所有帧（⛔ 不跨进程累积）。 */
const SHOTS = [];

/** ⭐ 判读位**去重 + 差异度**自检：两个判读位指向同一帧时**立刻报红**。
 *  E48 首轮实测：七个判读位的**平均通道差只有 0.09~0.10 / 255** —— 单看任何一张都"没问题"，
 *  但拼起来等于「这张图两分钟不动」。所以这里不判"好不好看"，只判**判读位之间是不是真的不同**。 */
function reportDistinct(tag) {
  if (SHOTS.length < 2) return;
  console.log(`  ── ${tag}：判读位去重（sha256 前 16 位）`);
  const seen = new Map();
  for (const s of SHOTS) {
    if (seen.has(s.sha)) {
      console.log(`  ⛔⛔ 重复帧：${s.name}(t=${s.t}) 与 ${seen.get(s.sha).name}(t=${seen.get(s.sha).t}) `
        + `sha 完全相同 ⇒ 这两个判读位**不是两个时刻**，其中一个在替另一个背书`);
    } else seen.set(s.sha, s);
  }
  if (seen.size === SHOTS.length) console.log(`  ✅ ${SHOTS.length} 个判读位 sha 互不相同`);
}

/** 逐帧差异度：把 N 帧缩到 1/8 后两两求平均绝对差 ⇒ 一个**可比较的数字**，
 *  用来回答「这画面到底动不动」。⛔ 不是判据（克制氛围本来就该低），⛔ 是趋势尺。 */
async function motion(cdp, tier, t0, t1, n) {
  mkdirSync(OUT, { recursive: true });
  const url = await ev(cdp, `(() => {
    const E = window.__E48; E.applyTier('${tier}', true);
    const N = ${n}, T0 = ${t0}, T1 = ${t1};
    const s = document.createElement('canvas'); s.width = ${SHOT_W / 8}; s.height = ${SHOT_H / 8};
    const g = s.getContext('2d', { willReadFrequently: true });
    const out = [];
    for (let i = 0; i < N; i++) {
      const t = T0 + (T1 - T0) * (i / (N - 1));
      window.__E48_capture(${SHOT_W}, ${SHOT_H}, t);
      g.clearRect(0, 0, s.width, s.height);
      g.drawImage(document.getElementById('cv'), 0, 0, s.width, s.height);
      out.push(Array.from(g.getImageData(0, 0, s.width, s.height).data));
    }
    const d = [];                                     // 与前一帧的平均绝对差（8-bit 通道尺度）
    for (let i = 1; i < N; i++) { let a = 0; for (let k = 0; k < out[i].length; k++) a += Math.abs(out[i][k] - out[i - 1][k]); d.push(a / out[i].length); }
    // 烟柱横向位移（重心）。⚠️ **只取柱的上 1/3**：摆动随高度有相位滞后（swayLag1/Lag2），
    //    对整柱取重心会把各高度**互相抵消** ⇒ 量出来只有 1~2 px（实测），那不是"摆幅小"，
    //    是**尺子坏了**。上 1/3 压在平坦天幕上，重心干净。
    //    ⚠️⚠️ 这几行注释**住在模板字符串里** ⇒ 里面**绝不能出现反引号**（会提前闭合模板，
    //    报出来是一句莫名其妙的 "missing ) after argument list" + 行号指到本函数中段）。
    const cx = []; const W = ${SHOT_W}, H = ${SHOT_H}, L = E.L;
    for (let i = 0; i < N; i++) {
      const t = T0 + (T1 - T0) * (i / (N - 1));
      window.__E48_capture(W, H, t);
      const g2 = document.getElementById('cv').getContext('2d', { willReadFrequently: true });
      const w = 120, h = Math.max(30, Math.round(L.smokeH * 0.30));
      const y0 = Math.max(0, Math.round(L.smokeTopY + (L.smokeBaseY - L.smokeTopY) * 0.10));
      const im = g2.getImageData(Math.round(L.smokeX - w / 2), y0, w, h).data;
      let num = 0, den = 0;
      for (let yy = 0; yy < h; yy++) for (let xx = 0; xx < w; xx++) {
        const k = (yy * w + xx) * 4, o = (yy * w + xx) * 4;
        const lum = 0.2126 * im[k] + 0.7152 * im[k + 1] + 0.0722 * im[k + 2];
        const v = lum - 118; if (v > 5) { num += v * (xx - w / 2); den += v; }
      }
      cx.push(den > 0 ? num / den : NaN);
    }
    const ok = cx.filter((x) => !Number.isNaN(x));
    return JSON.stringify({ d: d.map((x) => +x.toFixed(3)), cx: cx.map((x) => +x.toFixed(2)),
      span: +(Math.max(...ok) - Math.min(...ok)).toFixed(2) });
  })()`);
  const r = JSON.parse(url);
  console.log(`  逐帧平均绝对差（8-bit，${SHOT_W / 8}×${SHOT_H / 8}）：`);
  console.log('   ', r.d.join('  '));
  console.log(`  烟柱亮度重心 x（px，相对柱心）：${r.cx.join(' ')}`);
  console.log(`  ⭐ 摆幅跨度 = ${r.span} px @1280  ⇒  TV 上（≈2 m 观看距离）可见阈值约 6~8 px`);
  return r;
}

/** 判读**裁块**：整帧落定后按目标抠一块最近邻放大 ⇒ 看真机 1:1 像素（⛔ 平滑放大，
 *  那会把烟丝糊成"画得软"；Read 也会把 1280 宽压到几百像素，30 px 的烟丝根本判不出来）。 */
async function zoom(cdp, t, tier, name, target, scale, ov) {
  mkdirSync(OUT, { recursive: true });
  // 与 shot() 同口径的覆盖项（只认 `对象.字段=数`）：用于「这条纹理是烘焙artifact还是调色层颗粒」
  // 这类**对照销疑**——同一时刻同一裁块，关掉某一层再截一张，差别自己会说话。
  const setOv = (ov || []).map((kv) => `E.${kv[0]} = ${kv[1]};`).join(' ');
  const info = await ev(cdp, `(() => {
    const E = window.__E48;
    E.applyTier('${tier}', true); ${setOv} E.bakeAll();
    window.__E48_capture(${SHOT_W}, ${SHOT_H}, ${t});
    const L = E.L, P = E.P, W = ${SHOT_W}, H = ${SHOT_H};
    let cx, cy, w;
    const n = E.DUNES.length;
    if ('${target}' === 'smoke') { cx = L.smokeX; cy = L.smokeBaseY - L.smokeH * 0.42; w = L.smokeH * 0.62; }
    else if ('${target}' === 'root') { cx = L.smokeX; cy = L.smokeBaseY; w = H * 0.22; }   // 烟根↔脊线的交接处
    else if ('${target}' === 'sun') { cx = L.sunX; cy = L.sunY; w = L.sunR * 5.2; }
    else if ('${target}' === 'dune') { cx = W * 0.55; cy = E.crest(n - 1, W * 0.55) + H * 0.06; w = H * 0.46; }
    else if ('${target}' === 'stack') { cx = W * 0.28; cy = (E.crest(0, W * 0.28) + L.smokeBaseY) / 2; w = H * 0.5; }  // 多层重叠脊**叠在一起**的判读位
    else if ('${target}' === 'caravan') {
      const st = E.caravanAt(${t});
      cx = st.head; cy = E.groupTopCrest(L.carLayer, st.head) + P.caravan.h * H * 0.4; w = P.caravan.h * H * 7.5;
    } else { cx = L.smokeX; cy = L.smokeBaseY - L.smokeH * 0.42; w = L.smokeH * 0.62; }
    w = Math.max(48, Math.min(W, H, Math.round(w)));
    const x = Math.min(W - w, Math.max(0, Math.round(cx - w / 2)));
    const y = Math.min(H - w, Math.max(0, Math.round(cy - w / 2)));
    const url = E.crop(x, y, w, w, ${scale});
    const st = E.caravanAt(${t});
    return { rect: x + ',' + y + ' ' + w + 'px', puffs: E.scene().puffs,
             colTop: +(L.smokeTopY / H).toFixed(3), head: +(st.head / W).toFixed(3), active: st.active ? 1 : 0,
             b64: url.split(',')[1] };
  })()`);
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(info.b64, 'base64'));
  console.log(`  ${name}.png  t=${t.toFixed(2)}s ${tier} 目标=${target} 裁块=${info.rect} 放大=${scale}×`
    + ` 烟丝=${info.puffs} 柱顶y=${info.colTop}H 驼头x=${info.head}W 在场=${info.active}`);
}

// 判读位：需求 ①「孤烟直」+ ②「轻微摆动」+ 三轮裁决后的三要素（落日 / 多层重叠沙丘 / 驼队）各取一个最不利时刻
// ⛔ 'beacon' / 'water' 两个判读位随烽燧、水面一起删除（三轮裁决 ④⑤）
const PHASE_SHOTS = (b) => [
  [0.5, 'a0_刚进入_烟柱初生'],
  [b.tSway, 'a1_最大摆幅'],
  [b.tEmber, 'a2_余烬峰_暖边最亮'],
  [b.tRise, 'a3_升速最快'],
  [b.tWaveIn, 'a4_驼队刚露头_腿被遮'],
  [b.tWaveMid, 'a5_驼队横穿中点'],
  [b.tGap, 'a6_空窗_只有烟'],
];

async function main() {
  const cmd = process.argv[2] || 'phases';
  const reused = await reuse();
  const proc = reused ? null : await launch();
  const tgt = await pageTarget();
  const cdp = await CDP.connect(tgt.webSocketDebuggerUrl);
  try {
    await cdp.send('Page.enable');
    // ⭐ 页内未捕获异常的回传通道：`boot()` 在 `window.__E48 = {...}` **之前**执行，所以烘焙里
    //    任何一处抛错都会让 __E48 保持 undefined ⇒ 驱动只报「页面没起来」，把真机才有的错（vm 桩件
    //    不校验的 addColorStop 颜色串 / 非有限渐变坐标）伪装成"慢"。导航前注入 onerror 才能拿到原文。
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
      source: 'window.__BOOTERR=[];window.onerror=function(m,u,l,c){window.__BOOTERR.push(m+" @"+(u||"").split("/").pop()+":"+l+":"+c);return false;};'
    });
    await cdp.send('Page.navigate', { url: navUrl() });
    for (let i = 0; i < 120; i++) {
      const ok = await ev(cdp, `!!(window.__E48 && window.__E48.P)`).catch(() => false);
      if (ok) break;
      await sleep(500);
    }
    const booted = await ev(cdp, `!!(window.__E48 && window.__E48.P)`);
    if (!booted) {
      const errs = await ev(cdp, `JSON.stringify(window.__BOOTERR||[])`).catch(() => '[]');
      throw new Error('页面没起来（__E48 未定义）页内异常=' + errs);
    }
    const pre = await ev(cdp, `JSON.stringify(window.__BOOTERR||[])`);
    if (pre !== '[]') console.log('  ⚠️ 页内未捕获异常（帧可能仍出，但必须处理）：' + pre);

    if (cmd === 'phases') {
      const b = await findBeats(cdp);
      console.log('── 判读位（页面 beatScan(150) 现算）：最大摆幅 t=' + b.tSway + ' s（' + b.swayMaxPx
        + ' px@1280） 余烬峰 t=' + b.tEmber + ' s（' + b.emberMax + '） 升速峰 t=' + b.tRise
        + ' s（' + b.riseMaxPx + ' px/s）'
        + (b.tEmberRiseSep === undefined ? '' : ' **两者相隔 ' + b.tEmberRiseSep + ' s**')
        + ' 驼队 露头=' + b.tWaveIn + ' 中点=' + b.tWaveMid
        + ' 退场=' + b.tWaveOut + ' 空窗=' + b.tGap + '（单波 ' + b.cross + ' s）');
      for (const [t, name] of PHASE_SHOTS(b)) await shot(cdp, t, 'MED', name);
      reportDistinct('七个判读位');
      const st = await ev(cdp, 'JSON.stringify(window.__E48.selfTest().map(function (r) { return r.id + (r.pass ? "PASS" : "FAIL"); }))');
      console.log('  浏览器内自检：' + st + '（数值判据以 harness 为准）');
    } else if (cmd === 'phasediff') {
      // ⭐ 七个判读位**两两之间**的平均通道差。sha 去重只能抓"完全同一帧"，抓不到
      //    "差 0.1 / 255 —— 肉眼分不出、但一个判读位正在替另一个背书"。
      const b = await findBeats(cdp);
      const ts = PHASE_SHOTS(b);
      const r = await ev(cdp, `(() => {
        const E = window.__E48; E.applyTier('MED', true);
        const S = 160, T = 90, cvv = document.getElementById('cv');
        const g = cvv.getContext('2d', { willReadFrequently: true });
        const px = [];
        for (const [t] of ${JSON.stringify(ts)}) {
          window.__E48_capture(${SHOT_W}, ${SHOT_H}, t);
          const s = document.createElement('canvas'); s.width = S; s.height = T;
          const sg = s.getContext('2d', { willReadFrequently: true });
          sg.drawImage(cvv, 0, 0, S, T);
          px.push(sg.getImageData(0, 0, S, T).data);
        }
        const nm = ${JSON.stringify(ts.map((x) => x[1]))};
        const out = [];
        for (let i = 0; i < px.length; i++) for (let j = i + 1; j < px.length; j++) {
          let a = 0; for (let k = 0; k < px[i].length; k++) a += Math.abs(px[i][k] - px[j][k]);
          out.push([nm[i], nm[j], +(a / px[i].length).toFixed(3)]);
        }
        out.sort((x, y) => x[2] - y[2]);
        return JSON.stringify(out);
      })()`);
      console.log('── 判读位两两平均通道差（160×90，8-bit；⛔ 不是"好看不好看"，是"是不是同一个画面"）');
      for (const [x, y, d] of JSON.parse(r)) {
        console.log(`   ${String(d).padStart(7)}  ${x}  ↔  ${y}${d < 0.05 ? '   ⛔ 近乎同一画面' : ''}`);
      }
    } else if (cmd === 'motion') {
      // ⭐ 「这画面到底动不动」的尺：不是判据（克制氛围本来就该低），是**趋势尺** ——
      //    用来确认改了 alpha 包络 / 摆幅之后，摆动真的落到了像素上。
      const t0 = parseFloat(process.argv[3] || '0'), t1 = parseFloat(process.argv[4] || '150');
      const n = parseInt(process.argv[5] || '16', 10);
      console.log(`── motion：MED，t=${t0}..${t1}s，${n} 帧 ──`);
      await motion(cdp, 'MED', t0, t1, n);
    } else if (cmd === 'tiers') {
      const b = await findBeats(cdp);
      console.log('── 同一时刻三档对比（最大摆幅 t=' + b.tSway + '）──');
      for (const tier of ['LOW', 'MED', 'HIGH']) await shot(cdp, b.tSway, tier, 'cmp_' + tier);
    } else if (cmd === 'wisps') {
      // 烟丝判读表：6 个变体 × 芯/暖边，按原烘焙函数放大重画 ⇒ 判"像不像烟丝"
      const cell = parseInt(process.argv[3] || '300', 10);
      mkdirSync(OUT, { recursive: true });
      const url = await ev(cdp, `window.__E48.wispSheet(${cell})`);
      const nm = `${OUT}/wisps_sheet.png`;
      writeFileSync(nm, Buffer.from(url.split(',')[1], 'base64'));
      console.log(`  ${nm}  cell=${cell}px 6 变体 ×（中性底色=芯丝 / 冷底色=暖边）`);
    } else if (cmd === 't') {
      const t = parseFloat(process.argv[3]), tier = (process.argv[4] || 'MED').toUpperCase();
      await shot(cdp, t, tier, process.argv[5] || ('t' + t + '_' + tier), parseOv(process.argv.slice(6)));
    } else if (cmd === 'zoom') {
      const t = parseFloat(process.argv[3] ?? '12'), tier = (process.argv[4] || 'MED').toUpperCase();
      const target = (process.argv[5] || 'smoke').toLowerCase();
      const scale = parseInt(process.argv[6] || '4', 10);
      const ov = parseOv(process.argv.slice(7));
      await shot(cdp, t, tier, 'full_' + target, ov);
      await zoom(cdp, t, tier, 'zoom_' + target, target, scale, ov);
    } else if (cmd === 'probe') {
      const r = await ev(cdp, process.argv[3]);
      console.log(typeof r === 'string' ? r : JSON.stringify(r));
    } else throw new Error('命令：phases|phasediff|motion|tiers|wisps|t|zoom|probe');
    console.log('输出目录：' + OUT);
  } finally {
    try { cdp.ws.close(); } catch (e) { /* 已经断了 */ }
    if (proc) proc.kill();
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
