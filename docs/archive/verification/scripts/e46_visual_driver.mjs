// E46「迷雾之战」原型的**真截图驱动**：headless Edge + CDP 打开 docs/archive/e46-tripod-fog-preview.html，
// 用 __E46.seek(t) / __E46.capture(w,h) 出**定稿帧**给所有者判读观感。
//
// ⚠️ 数值对账（自检 + 图元/铺屏计量）不走这里，走 e46_fog_harness.js（纯 vm 沙箱，无图形设备）。
//    本脚本只负责「画面长什么样」，⛔ 不要拿它当判据来源。
//
// 用法：
//   node docs/archive/verification/scripts/e46_visual_driver.mjs phases        # 十个阶段各一帧（MED，含掠过/揭示/绕后）
//   node docs/archive/verification/scripts/e46_visual_driver.mjs tiers         # 掠过位三档对比
//   node docs/archive/verification/scripts/e46_visual_driver.mjs t 50 HIGH p2  # 指定 t/档位/文件名
//   node .../e46_visual_driver.mjs t 54.75 MED ab_on P.breathA=0.45 WAKE.cap=0.9   # 叠覆盖出 A/B 对
//   node .../e46_visual_driver.mjs probe "(()=>JSON.stringify(window.__E46.BREATH_REC.length))()"
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9336;                                   // ⛔ 别和 seaside(9333)/moonlit(9334) 撞
const PAGE = resolve(ROOT, 'docs', 'archive', 'e46-tripod-fog-preview.html');
const URL_STR = 'file:///' + PAGE.replace(/\\/g, '/');
// ⚠️ 必须带 ?v= 并显式关缓存：复用常驻浏览器时同一 URL 会命中文档缓存，
//    改完原型重跑却截到**上一版**的图（实测发生过：两轮 PNG 字节数完全相同）。
const OUT = resolve(ROOT, 'output', 'e46_frames');
const SHOT_W = 1280, SHOT_H = 720;
/** ⚠️ 每次导航都带 ?v=：复用常驻浏览器时同一 URL 会命中文档缓存，改完原型重跑却截到上一版的图
 *  （实测发生过：两轮 PNG 字节数完全相同）。URL_STR 本身必须是常量，只在导航处拼参数。 */
const navUrl = () => URL_STR + '?v=' + Date.now();

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 端口已有可调试的 Edge 就直接复用（一次冷启动要 1~2 min，复用后每帧 <1 s）。
 *  返回 null 表示"不是我们起的"，⛔ 结束时别 kill 别人的浏览器。 */
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
        const { resolve, reject } = c.pending.get(m.id);
        c.pending.delete(m.id);
        m.error ? reject(new Error(m.error.message)) : resolve(m.result);
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

async function shot(cdp, t, tier, name, ov) {
  mkdirSync(OUT, { recursive: true });
  // `P.routeVar=N` 不只是赋值：页面只在**纪元边界**上 syncRouteEpoch 才换戏，直接写 P 会让
  // 截图仍停在启动那一套（V0）。所以它必须走页面自己的 applyVariant（⛔ 在本脚本重灌四张表）。
  const rv = (ov || []).filter((k) => k[0] === 'P.routeVar').map((k) => k[1]);
  const setOv = (ov || []).filter((kv) => kv[0] !== 'P.routeVar').map((kv) => `E.${kv[0]} = ${kv[1]};`).join(' ')
    + (rv.length ? ` E.P.routeVar = ${rv[0]}; E.applyVariant(${rv[0]}, true);` : '');
  // ⚠️ 雾/对比度一律按 **st.dist（真实距离）** 算，⛔ 不再用 st.d：
  //    R17 之后 d 是**镜头系有符号量**（d<0 = 机器人在身后），掠过与绕后两段 d→0 会让
  //    这一行印出「雾=0 对比度=100%」，读起来像机身贴着鼻子清晰可见，其实是横在侧方 40 m。
  const info = await ev(cdp, `(() => {
    const E = window.__E46; E.P.tier = '${tier}'; ${setOv}
    const st = E.seek(${t});
    E.render(st);                                  // seek 已 render，这里只为确保档位生效
    const url = E.capture(${SHOT_W}, ${SHOT_H});
    return { d: st.d, dist: st.dist, fog: E.fogOf(st.dist, st.D0), contrast: E.contrastAt(st.dist, st.D0),
             mx: st.mx, yaw: st.yaw * 180 / Math.PI,
             bld: st.bld.length, b64: url.split(',')[1] };
  })()`);
  writeFileSync(`${OUT}/${name}.png`, Buffer.from(info.b64, 'base64'));
  console.log(`  ${name}.png  t=${t.toFixed(2)}s 档位=${tier}  dist=${info.dist.toFixed(1)}m（d=${info.d.toFixed(1)}`
    + (info.d < 0 ? ' ⭐身后' : '') + ` mx=${info.mx.toFixed(1)}） 雾=${info.fog.toFixed(3)}`
    + ` 对比度=${(info.contrast * 100).toFixed(1)}%  偏航=${info.yaw.toFixed(0)}°  建筑=${info.bld}栋`);
  return `${OUT}/${name}.png`;
}

// ⭐ R17（判读轮 4 意见 4「视角也可以旋转，突然发现机器人在身后，吓一跳」）改的是**时间轴本身**：
//   贴身掠过 + 回头揭示 + 隐没绕后 + 归位，旧阶段表（压迫位 54~56 s 那一版）整体失效，
//   照旧表截图会判读到「该掠过的时候在逼近」。
// 掠过位与绕后位一律从页面现取（E.TCROSS 由 DH 关键帧求根，⛔ 写死 t）；
// 其余各帧按新阶段区间取中点：
//   P0 0–8 远雾 / P1 8–26 逼近 / P2 26–38 转身游走 / P3 38–54 再逼近并开火
//   P4 54–59.6 冲刺（fireEnv 在此段熄灯）/ P5 掠过 / P6 揭示 / P7 绕后空雾 / P8 穿越② / P9 归位
// ⚠️ 开火段在 t < fireT0：fireEnv 的语义是「t 窗内熄灭」（掠过/揭示/绕后三段必须熄灯，
//    12 m 处一道加色光束会把整幅画面洗白），所以判读光束观感的帧取在 48/53 s，⛔ 不是 56 s。
const PHASE_SHOTS = [
  [5, 'p0_雾中初现'], [18, 'p1_逼近'], [34, 'p2_转身游走'], [48, 'p3_逼近开火'],
  [53, 'p4_开火贴近'],
  ['t1', 'p5_贴身掠过'], ['t1+4.5', 'p6_回头揭示'], ['t1+12', 'p7_绕后空雾'],
  ['t2', 'p8_绕后穿越'], [87, 'p9_归位'],
];
/** 在页面里求「真实距离 dist 最小点」与两次 d 过零时刻，⛔ 不在本脚本另算一条 hermite。
 *  ⚠️ 必须是 dist=hypot(mx,d)（真实水平距），⛔ 不是 d：R17 之后 d 的最小值是 −70（身后最远），
 *      拿它当「压迫位」会截到绕后最深的那一帧。 */
const findBeats = (cdp) => cdp.send('Runtime.evaluate', {
  expression: `(() => { const E = window.__E46; let tM = 0, dM = Infinity;
    for (let t = 0; t <= E.P.LOOP; t += 0.1) { const st = E.sceneState(t); if (st.dist < dM) { dM = st.dist; tM = t; } }
    return { dmin: tM, t1: E.TCROSS.t1, t2: E.TCROSS.t2 }; })()`, returnByValue: true
}).then((r) => { if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails)); return r.result.value; });


async function main() {
  const cmd = process.argv[2] || 'phases';
  const reused = await reuse();
  const proc = reused ? null : await launch();
  const tgt = await pageTarget();
  const cdp = await CDP.connect(tgt.webSocketDebuggerUrl);
  try {
    await cdp.send('Page.enable');
    await cdp.send('Page.navigate', { url: navUrl() });
    for (let i = 0; i < 60; i++) {
      const ok = await ev(cdp, `!!(window.__E46 && window.__E46.P)`).catch(() => false);
      if (ok) break;
      await sleep(250);
    }
    const booted = await ev(cdp, `!!(window.__E46 && window.__E46.P)`);
    if (!booted) throw new Error('页面没起来（__E46 未定义）');

    const beats = await findBeats(cdp);
    // 't1+4.5' 这类相对时刻由页面里的 TCROSS 展开（⛔ 写死秒数：DH 关键帧一改掠过位就挪）
    const at = (s) => (typeof s === 'number' ? s
      : beats[String(s).split('+')[0]] + (String(s).includes('+') ? parseFloat(String(s).split('+')[1]) : 0));
    if (cmd === 'phases') {
      console.log(`── 阶段定稿帧（档位 MED，掠过 t1=${beats.t1.toFixed(2)} / 穿越② t2=`
        + beats.t2.toFixed(2) + ` / 最近位 ${beats.dmin.toFixed(1)} 均由页面现算）──`);
      for (const [s, name] of PHASE_SHOTS) await shot(cdp, at(s), 'MED', name);
      const st = await ev(cdp, 'JSON.stringify(window.__E46.selfTest().map(function (r) { return r.id + (r.pass ? "PASS" : "FAIL"); }))');
      console.log('  浏览器内自检：' + st + '（数值判据以 harness 为准）');
    } else if (cmd === 'tiers') {
      console.log('── 同一时刻三档对比（掠过位 t=' + beats.t1.toFixed(2) + '）──');
      for (const tier of ['LOW', 'MED', 'HIGH']) await shot(cdp, beats.t1, tier, 'p5_' + tier);
    } else if (cmd === 't') {
      const t = parseFloat(process.argv[3]), tier = (process.argv[4] || 'MED').toUpperCase();
      // argv[6..] = `对象.字段=数` 覆盖（判读 A/B：同一帧 P.wakeA=0 vs 0.55；也能碰 WAKE/DUST 旋钮表）。
      // 只认「已导出的对象表 + 数值」，⛔ 接受字符串/表达式 —— 避免把任意代码拼进页面。
      const ov = process.argv.slice(6).filter((s) => /^[A-Za-z]\w*(\.\w+)?=-?[\d.]+$/.test(s))
        .map((s) => { const i = s.indexOf('='); return [s.slice(0, i), parseFloat(s.slice(i + 1))]; });
      await shot(cdp, t, tier, process.argv[5] || ('t' + t + '_' + tier), ov);
    } else if (cmd === 'probe') {
      // 只读探针：在页面里跑一段表达式，用来找「机身在画且完整入画」这类判读帧位，⛔ 不作数值判据
      const r = await ev(cdp, process.argv[3]);
      console.log(typeof r === 'string' ? r : JSON.stringify(r));
    } else throw new Error('未知命令：phases|tiers|t|probe');
    console.log('输出目录：' + OUT);
  } finally {
    // ⚠️ 必须关 ws：node 的事件循环会因为这条还开着的 WebSocket 永远不空 ⇒ 脚本**打完图却不退出**
    //   （实测：单帧 1 s 出图，进程却挂到被外层杀掉，`&&` 串多条时后面几条根本轮不到跑）。
    try { cdp.ws.close(); } catch (e) { /* 已经断了 */ }
    if (proc) proc.kill();                     // 复用的浏览器留给下次
  }
}
main().catch((e) => { console.error(e); process.exit(1); });
