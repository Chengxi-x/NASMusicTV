// E49「鸟群」原型的**真截图驱动**：headless Edge + CDP 打开 docs/e49-birds-flock-preview.html，
// 真实 rAF 跑指定秒数后截整帧给所有者判读观感，并回读 selfTest/perchDebug 门禁原文。
//
// ⚠️ 数值对账（自检 + 五门禁 + 确定性对账）不走这里，走 e49_flock_harness.js（纯 vm 沙箱）。
//    E49 没有 E48 那样的 __E48_capture 定帧函数（Boids 是时间积分系统，无绝对时刻可跳），
//    本脚本只能「真实时间等 N 秒再截」，⛔ 不要拿它当数字判据来源。
//
// 用法：
//   node docs/archive/verification/scripts/e49_flock_driver.mjs shot MEDIUM med_15s 15       # 跑 15s 截一帧
//   node .../e49_flock_driver.mjs shot HIGH high_15s 15 grade=0 perchOn=1                    # 带覆盖项（布尔只认 0/1）
//   node .../e49_flock_driver.mjs tiers 15                                                   # 三档各 15s 各一帧 + sha 去重
//   node .../e49_flock_driver.mjs probe "window.__E49.perchDebug()"                          # 裸取表达式
//   node .../e49_flock_driver.mjs gates MEDIUM 20                                            # 真浏览器门禁判读（跑 20s 后读 selfTest）
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9349;                                   // ⛔ 方案指定；别和 seaside(9333)/moonlit(9334)/fog(9336)/snow(9337)/desert(9338) 撞
const PAGE = resolve(ROOT, 'docs', 'e49-birds-flock-preview.html');
const URL_STR = 'file:///' + PAGE.replace(/\\/g, '/');
const OUT = resolve(ROOT, 'output', 'e49_frames');
const SHOT_W = 1600, SHOT_H = 900;                   // 原型画布原生 1600×900，视口即画布
const navUrl = () => URL_STR + '?v=' + Date.now();   // ⭐ 防文档缓存：改完原型重跑不许截到旧版

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
    `--window-size=${SHOT_W},${SHOT_H}`, '--force-device-scale-factor=1', '--hide-scrollbars',
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

/** 覆盖项：只认 `键=数`（E49.P 下一层，无更深路径需求）。布尔键必须列进 BOOLS，只认 0/1，
 *  否则 `grade=0` 会把 true/false 键塞进数值通道，读起来像"设了个 0"而不是关掉了调色层。 */
const BOOLS = new Set(['grade', 'perchOn', 'bankOn', 'audioOn']);
const OV_RE = /^[A-Za-z]\w*=-?[\d.]+$/;
function parseOv(list) {
  return (list || []).map((s) => {
    if (!OV_RE.test(s)) throw new Error(`覆盖项写法不被接受：${s}（须为 键=数）`);
    const i = s.indexOf('=');
    const k = s.slice(0, i), v = parseFloat(s.slice(i + 1));
    if (!BOOLS.has(k)) return [k, v];
    if (!(v === 0 || v === 1)) throw new Error(`布尔覆盖 ${k} 只接受 0 或 1`);
    return [k, v ? 'true' : 'false'];
  });
}

async function navigateAndBoot(cdp) {
  await cdp.send('Page.enable');
  // 页内未捕获异常回传：原型顶层抛错会让 __E49 保持 undefined，只报「页面没起来」会把真因伪装成"慢"
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: 'window.__BOOTERR=[];window.onerror=function(m,u,l,c){window.__BOOTERR.push(m+" @"+(u||"").split("/").pop()+":"+l+":"+c);return false;};'
  });
  await cdp.send('Page.navigate', { url: navUrl() });
  for (let i = 0; i < 120; i++) {
    const ok = await ev(cdp, `!!(window.__E49 && window.__E49.P)`).catch(() => false);
    if (ok) break;
    await sleep(500);
  }
  const booted = await ev(cdp, `!!(window.__E49 && window.__E49.P)`);
  if (!booted) {
    const errs = await ev(cdp, `JSON.stringify(window.__BOOTERR||[])`).catch(() => '[]');
    throw new Error('页面没起来（__E49 未定义）页内异常=' + errs);
  }
  const pre = await ev(cdp, `JSON.stringify(window.__BOOTERR||[])`);
  if (pre !== '[]') console.log('  ⚠️ 页内未捕获异常（帧可能仍出，但必须处理）：' + pre);
}

/** 应用档位 + 覆盖项，真实 rAF 跑 waitSec 秒，回读门禁 + 截整帧。 */
async function shot(cdp, tier, name, waitSec, ov) {
  mkdirSync(OUT, { recursive: true });
  const setOv = (ov || []).map((kv) => `E.P.${kv[0]} = ${kv[1]};`).join(' ');
  await ev(cdp, `(() => { const E = window.__E49; E.applyTier('${tier}'); ${setOv} })()`);
  await sleep(waitSec * 1000);
  const info = await ev(cdp, `(() => {
    const E = window.__E49;
    const st = E.selfTest();
    const pd = E.perchDebug();
    return { lines: st.lines, pass: st.pass, total: st.total,
      perch: { perched: pd.perched, flyBelowHorizon: pd.flyBelowHorizon, deep: pd.deep.length },
      shot: document.getElementById('cv').toDataURL('image/png').split(',')[1] };
  })()`);
  const buf = Buffer.from(info.shot, 'base64');
  writeFileSync(`${OUT}/${name}.png`, buf);
  const sha = createHash('sha256').update(buf).digest('hex').slice(0, 16);
  SHOTS.push({ name, tier, sha });
  console.log(`  ${name}.png  档位=${tier} 等=${waitSec}s  selfTest ${info.pass}/${info.total}`
    + ` 栖=${info.perch.perched} 地下线穿越=${info.perch.flyBelowHorizon} 地下鸟=${info.perch.deep}  sha=${sha}`);
  for (const l of info.lines) console.log('    ' + l);
  return info;
}
/** 本次运行截过的所有帧（⛔ 不跨进程累积）。 */
const SHOTS = [];

function reportDistinct(tag) {
  if (SHOTS.length < 2) return;
  const seen = new Map();
  let dup = false;
  for (const s of SHOTS) {
    if (seen.has(s.sha)) {
      dup = true;
      console.log(`  ⛔⛔ 重复帧：${s.name} 与 ${seen.get(s.sha).name} sha 完全相同 ⇒ 鸟群没在动（两帧画面一致不可能）`);
    } else seen.set(s.sha, s);
  }
  if (!dup) console.log(`  ✅ ${tag}：${SHOTS.length} 帧sha 互不相同（画面在动）`);
}

async function main() {
  const cmd = process.argv[2] || 'gates';
  const reused = await reuse();
  const proc = reused ? null : await launch();
  const tgt = await pageTarget();
  const cdp = await CDP.connect(tgt.webSocketDebuggerUrl);
  try {
    await navigateAndBoot(cdp);
    if (cmd === 'shot') {
      const tier = process.argv[3] || 'MEDIUM';
      const name = process.argv[4] || (tier.toLowerCase() + '_' + (process.argv[5] || '10s'));
      const waitSec = parseFloat(process.argv[5] || '10');
      await shot(cdp, tier, name, waitSec, parseOv(process.argv.slice(6)));
      reportDistinct('shot');
    } else if (cmd === 'tiers') {
      const waitSec = parseFloat(process.argv[3] || '15');
      for (const t of ['LOW', 'MEDIUM', 'HIGH']) await shot(cdp, t, t.toLowerCase() + `_${waitSec}s`, waitSec);
      reportDistinct('tiers');
    } else if (cmd === 'gates') {
      const tier = process.argv[3] || 'MEDIUM';
      const waitSec = parseFloat(process.argv[4] || '20');
      console.log(`── 真浏览器门禁判读：${tier} 档真实 rAF 跑 ${waitSec}s`);
      const info = await shot(cdp, tier, 'gates_' + tier.toLowerCase(), waitSec);
      const noFail = !info.lines.some((l) => l.includes('FAIL'));
      const noLeak = info.perch.flyBelowHorizon === 0 && info.perch.deep === 0;
      console.log(`  ── 判定：${noFail && noLeak ? '✅ selfTest 无 FAIL 且无地下泄漏' : '❌ 有门禁未过'}`);
      if (!(noFail && noLeak)) process.exitCode = 1;
    } else if (cmd === 'probe') {
      const r = await ev(cdp, process.argv[3]);
      console.log(typeof r === 'string' ? r : JSON.stringify(r, null, 2));
    } else {
      throw new Error('未知命令：' + cmd + '（可用：shot / tiers / gates / probe）');
    }
  } finally {
    cdp.ws.close?.();
    if (proc) proc.kill();
  }
}
main().catch((e) => { console.error('⛔', e.message); process.exit(1); });
