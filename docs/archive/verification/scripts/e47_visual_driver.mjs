// E47「雪松」原型的**真截图驱动**：headless Edge + CDP 打开 docs/e47-snow-cedar-preview.html，
// 用 __E47_capture(w,h,t) 出**定稿帧**给所有者判读观感。
//
// ⚠️ 数值对账（自检 + 图元/铺屏计量）不走这里，走 e47_snow_harness.js（纯 vm 沙箱，无图形设备）。
//    本脚本只负责「画面长什么样」，⛔ 不要拿它当判据来源。
//
// 用法：
//   node docs/archive/verification/scripts/e47_visual_driver.mjs phases     # 六个判读位各一帧（MED）
//   node docs/archive/verification/scripts/e47_visual_driver.mjs tiers      # 同一时刻三档对比
//   node .../e47_visual_driver.mjs poses 420                                # 人物剪影表：站姿 × 肩雪三档
//   node .../e47_visual_driver.mjs t 30 HIGH myshot P.tree.snowK=1.0        # 指定 t/档位/文件名 + 覆盖
//   node .../e47_visual_driver.mjs zoom 12 MED person 7                     # 整帧 full_* + 最近邻裁块 zoom_*
//   node .../e47_visual_driver.mjs probe "JSON.stringify(window.__E47.scene())"
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9337;                                   // ⛔ 别和 seaside(9333)/moonlit(9334)/fog(9336) 撞
const PAGE = resolve(ROOT, 'docs', 'e47-snow-cedar-preview.html');
const URL_STR = 'file:///' + PAGE.replace(/\\/g, '/');
const OUT = resolve(ROOT, 'output', 'e47_frames');
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

/** 判读位一律由页面自己算（beatScan 纯推进不渲染）：风/摆的峰值时刻由 gustHz1/2、swayHz 合成，
 *  ⛔ 在脚本里写死秒数 —— 参数表一改判读帧就取错地方。 */
const findBeats = (cdp) => ev(cdp, `window.__E47.beatScan(240)`);

async function shot(cdp, t, tier, name, ov) {
  mkdirSync(OUT, { recursive: true });
  // 覆盖项只认 `对象.字段=数`（argv 里已按正则筛过），⛔ 接受任意表达式
  const setOv = (ov || []).map((kv) => `E.${kv[0]} = ${kv[1]};`).join(' ');
  const info = await ev(cdp, `(() => {
    const E = window.__E47;
    E.applyTier('${tier}', true); ${setOv}
    const url = window.__E47_capture(${SHOT_W}, ${SHOT_H}, ${t});
    const sc = E.scene();
    return { ops: sc.ops, fill: +sc.fill.toFixed(3), flakes: sc.flakes, dust: +sc.dust.toFixed(3),
             wind: +sc.wind.toFixed(4), swayPx: +sc.sway.toFixed(2), personX: +(E.personRect().x / ${SHOT_W}).toFixed(3),
             b64: url.split(',')[1] };
  })()`);
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(info.b64, 'base64'));
  console.log(`  ${name}.png  t=${t.toFixed(2)}s 档位=${tier}  ops=${info.ops} 铺屏=${info.fill}`
    + ` 雪粒=${info.flakes} 肩雪=${info.dust} 风=${info.wind} 摆=${info.swayPx}px 站位x=${info.personX}W`);
  return `${OUT}/${name}.png`;
}

/** 判读**裁块**：整帧落定后再按目标抠一块最近邻放大 ⇒ 看真机 1:1 像素（⛔ 看整帧缩略图判细节，
 *  Read 会把 1280 宽压到几百像素，人物就剩 40 px，任何笔形细节都判不出来）。 */
async function zoom(cdp, t, tier, name, target, scale) {
  mkdirSync(OUT, { recursive: true });
  const info = await ev(cdp, `(() => {
    const E = window.__E47;
    E.applyTier('${tier}', true);
    window.__E47_capture(${SHOT_W}, ${SHOT_H}, ${t});
    const L = E.L, W = ${SHOT_W}, H = ${SHOT_H}, R = E.personRect();
    let cx, cy, w;
    if ('${target}' === 'person') { cx = R.x + R.w / 2; cy = L.personY - L.personH * 0.52; w = L.personH * 2.3; }
    else if ('${target}' === 'tree') { cx = L.treeX; cy = (L.treeTopY + L.treeBaseY) / 2; w = L.treeH * 1.05; }
    else if ('${target}' === 'sun') { cx = L.sunX; cy = L.sunY; w = L.sunR * 6; }
    else { cx = L.treeX - L.treeH * 0.3; cy = L.personY; w = H * 0.46; }
    w = Math.max(48, Math.min(W, H, Math.round(w)));
    const x = Math.min(W - w, Math.max(0, Math.round(cx - w / 2)));
    const y = Math.min(H - w, Math.max(0, Math.round(cy - w / 2)));
    const url = E.crop(x, y, w, w, ${scale});
    return { rect: x + ',' + y + ' ' + w + 'px', px: +(R.x / W).toFixed(3), dust: +E.scene().dust.toFixed(3), b64: url.split(',')[1] };
  })()`);
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(info.b64, 'base64'));
  console.log(`  ${name}.png  t=${t.toFixed(2)}s ${tier} 目标=${target} 裁块=${info.rect} 放大=${scale}× 站位x=${info.px}W 肩雪=${info.dust}`);
}

// 判读位（人**站定**、无脚印 ⇒ 取的是"肩雪生命周期"的四个时刻 + 风/摆两个氛围时刻）
const PHASE_SHOTS = (b) => [
  [0.5, 'a0_刚进入_肩雪为0'],
  [b.tHalf, 'a1_肩雪半积'],
  [b.tFull, 'a2_肩雪积满'],
  [b.tShake, 'a3_阵风抖落'],
  [b.tSway, 'a4_最大摆幅'],
  [b.tWind, 'a5_阵风峰值'],
];

async function main() {
  const cmd = process.argv[2] || 'phases';
  const reused = await reuse();
  const proc = reused ? null : await launch();
  const tgt = await pageTarget();
  const cdp = await CDP.connect(tgt.webSocketDebuggerUrl);
  try {
    await cdp.send('Page.enable');
    await cdp.send('Page.navigate', { url: navUrl() });
    for (let i = 0; i < 120; i++) {
      const ok = await ev(cdp, `!!(window.__E47 && window.__E47.P)`).catch(() => false);
      if (ok) break;
      await sleep(500);
    }
    const booted = await ev(cdp, `!!(window.__E47 && window.__E47.P)`);
    if (!booted) throw new Error('页面没起来（__E47 未定义）');

    if (cmd === 'phases') {
      const b = await findBeats(cdp);
      console.log('── 判读位（页面 beatScan(240) 现算）：阵风峰 t=' + b.tWind
        + ' s（wind=' + b.windMax + '） 最大摆幅 t=' + b.tSway + ' s（' + b.swayMaxPx + ' px@1600）'
        + ' 肩雪积满 t=' + b.tFull + ' s 半积 t=' + b.tHalf + ' s 可见抖落 t=' + b.tShake + ' s');
      for (const [t, name] of PHASE_SHOTS(b)) await shot(cdp, t, 'MED', name);
      const st = await ev(cdp, 'JSON.stringify(window.__E47.selfTest().map(function (r) { return r.id + (r.pass ? "PASS" : "FAIL"); }))');
      console.log('  浏览器内自检：' + st + '（数值判据以 harness 为准）');
    } else if (cmd === 'tiers') {
      const b = await findBeats(cdp);
      console.log('── 同一时刻三档对比（肩雪积满 t=' + b.tFull + '）──');
      for (const tier of ['LOW', 'MED', 'HIGH']) await shot(cdp, b.tFull, tier, 'cmp_' + tier);
    } else if (cmd === 't') {
      const t = parseFloat(process.argv[3]), tier = (process.argv[4] || 'MED').toUpperCase();
      const ov = process.argv.slice(6).filter((s) => /^[A-Za-z]\w*(\.\w+)?=-?[\d.]+$/.test(s))
        .map((s) => { const i = s.indexOf('='); return [s.slice(0, i), parseFloat(s.slice(i + 1))]; });
      await shot(cdp, t, tier, process.argv[5] || ('t' + t + '_' + tier), ov);
    } else if (cmd === 'poses') {
      // 人物剪影判读表：站姿 × 肩雪三档（0/50/100%），由页面 poseSheet() 用原函数放大重画
      const hh = parseInt(process.argv[3] || '420', 10);
      mkdirSync(OUT, { recursive: true });
      const url = await ev(cdp, `window.__E47.poseSheet(0, ${hh})`);
      const nm = `${OUT}/poses_stand.png`;
      writeFileSync(nm, Buffer.from(url.split(',')[1], 'base64'));
      console.log(`  ${nm}  hh=${hh}px 三格=肩雪 0 / 50% / 100%`);
    } else if (cmd === 'zoom') {
      const t = parseFloat(process.argv[3] ?? '12'), tier = (process.argv[4] || 'MED').toUpperCase();
      const target = (process.argv[5] || 'person').toLowerCase();
      const scale = parseInt(process.argv[6] || '4', 10);
      await shot(cdp, t, tier, 'full_' + target);
      await zoom(cdp, t, tier, 'zoom_' + target, target, scale);
    } else if (cmd === 'probe') {
      const r = await ev(cdp, process.argv[3]);
      console.log(typeof r === 'string' ? r : JSON.stringify(r));
    } else throw new Error('未知命令：phases|tiers|poses|t|zoom|probe');
    console.log('输出目录：' + OUT);
  } finally {
    try { cdp.ws.close(); } catch (e) { /* 已经断了 */ }
    if (proc) proc.kill();
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
