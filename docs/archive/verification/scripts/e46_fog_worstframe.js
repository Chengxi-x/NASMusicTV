// E46「迷雾之战」**最坏帧**计量（§十 的 60 帧均值表是"典型帧"，`TripodOpBudget` 需要上界）。
//
// ⭐ 为什么单独一个脚本：`__E46.stats(n)` / `profile(n)` 返回的都是**均值**（`/N`），
//    而 E44 `MoonOpBudget` 的口径是「表 = 最坏帧上界」（偏差 D26/D28/D34 全坏在
//    "拿均值当上界"上）。本脚本把同一批渲染跑成 **逐类 max**，是 `TripodOpItem`
//    枚举表的**唯一数字来源**（§十 的均值表降级为典型帧记录，同 E44 的
//    `MEASURED_*` 与 `MoonOpItem` 两张表的分工）。
//
// ⛔ 不在本脚本里另写一份 DOM 桩 / 默认值：bootstrap 直接切自
//    `e46_fog_harness.js` 的「// ── 自检」标记之前那段（沙箱 + `__E46` 导出），
//    harness 改了桩这里自动跟着改，不会漂移。
// ⭐ 逐类计量走 **`E.profAt(t)`**（2026-10-13 加在原型 `__E46` 上的单帧逐类快照，
//    存档/还原 PROF/OPS/FILL，写法同 `crowdOpsAt`）——⛔ 不要试图在沙箱里直接摸
//    `PROF`：原型整段脚本被 IIFE 包裹（`})();`），`P`/`PROF`/`render`/`sceneState`
//    全在函数作用域里，沙箱全局只有一个 `window.__E46`。
//
// 用法：node docs/archive/verification/scripts/e46_fog_worstframe.js [--frames 1800] [--variants]
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..', '..', '..', '..');
const harnessPath = path.join(root, 'docs', 'archive', 'verification', 'scripts', 'e46_fog_harness.js');
const src = fs.readFileSync(harnessPath, 'utf8');
const cut = src.indexOf('// ── 自检');
if (cut < 0) throw new Error('harness 结构变化：找不到「// ── 自检」标记（bootstrap 切分点）');
const bootstrap = src.slice(0, cut);

const framesArg = process.argv.indexOf('--frames');
const N = framesArg > 0 ? parseInt(process.argv[framesArg + 1], 10) : 1800;
const withVariants = process.argv.indexOf('--variants') > 0;
const LOOP = 90;

// ⚠️ `vm.runInThisContext` 的作用域里没有 `require`/`__dirname`/`process`（不是模块作用域）
//    ⇒ 自建一个上下文把这些灌进去，bootstrap 先在这里跑（它自己 createContext(sandbox)）。
//    bootstrap 末尾追加一行把 `sandbox` 挂到 `__HOLDER` 上 —— 逐类计量必须跑在原型自己的
//    上下文里（`profAt` 在里面），而 bootstrap 里的 `sandbox` 是它自己脚本作用域的 const。
const holder = {};
const ctxObj = {
  require: require, module: module, exports: exports,
  __dirname: __dirname, __filename: __filename,
  process: process, console: console, Buffer: Buffer,
  setTimeout: setTimeout, clearTimeout: clearTimeout,
  __HOLDER: holder,
};
vm.createContext(ctxObj);
vm.runInContext(bootstrap + '\n__HOLDER.sandbox = sandbox;\n', ctxObj, { filename: 'e46_fog_harness.js:bootstrap' });
const sandboxCtx = holder.sandbox;

// 逐类最坏帧：跑在原型自己的 sandbox 上下文里，唯一入口是 window.__E46
const measure = `
(function () {
  const LOOP = ${LOOP}, N = ${N};
  const variants = ${withVariants ? '[0, 1, 2]' : '[0]'};
  const tiers = ['LOW', 'MED', 'HIGH'];
  const E = window.__E46;
  const out = [];
  for (const v of variants) {
    E.withVariant(v, function () {
      for (const tier of tiers) {
        E.P.tier = tier;
        const maxp = {};
        const over = {};
        const top = [];
        let maxOps = 0, maxFill = 0, tOps = 0, tFill = 0, sumOps = 0, sumFill = 0;
        let worstProf = null;
        for (let i = 0; i < N; i++) {
          const t = (i * LOOP / N) % LOOP;
          const c = E.profAt(t);
          if (c.ops > maxOps) { maxOps = c.ops; tOps = t; }
          if (c.fill > maxFill) { maxFill = c.fill; tFill = t; worstProf = c.prof; }
          sumOps += c.ops; sumFill += c.fill;
          // 分布形态：尖峰（1~2 帧）还是平台（几十帧）？这决定上限该贴最坏帧还是贴均值
          for (const th of [2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 4.66, 5.0, 5.2, 5.4, 5.5, 6.0]) {
            over[th] = (over[th] || 0) + (c.fill > th ? 1 : 0);
          }
          top.push({ t: t, ops: c.ops, fill: c.fill });
          for (const k in c.prof) {
            const q = c.prof[k], m = maxp[k] || (maxp[k] = { ops: 0, fill: 0, t: 0 });
            if (q.ops > m.ops) { m.ops = q.ops; m.t = t; }
            if (q.fill > m.fill) m.fill = q.fill;
          }
        }
        out.push({ v: v, tier: tier, maxOps: maxOps, maxFill: maxFill,
                   tOps: tOps.toFixed(2), tFill: tFill.toFixed(2),
                   meanOps: sumOps / N, meanFill: sumFill / N, maxp: maxp,
                   over: over, top: top, worstProf: worstProf });
      }
    });
  }
  return out;
})();
`;
const out = vm.runInContext(measure, sandboxCtx, { filename: 'e46_fog_worstframe.js:measure' });

console.log('── 最坏帧计量（' + N + ' 帧均匀采样 × 档位' + (withVariants ? ' × 三套剧本' : '（仅 V0）') + '）──');
console.log('剧本  档位     最坏图元  最坏铺屏   均值图元  均值铺屏   图元@时刻  铺屏@时刻');
for (const r of out) {
  console.log('V' + r.v + '    ' + r.tier.padEnd(9)
    + String(r.maxOps).padStart(8) + String(r.maxFill.toFixed(3)).padStart(11)
    + String(r.meanOps.toFixed(1)).padStart(10) + String(r.meanFill.toFixed(2)).padStart(10)
    + String(r.tOps).padStart(11) + String(r.tFill).padStart(10));
}

// 逐类最坏帧表（Kotlin TripodOpItem 的唯一来源）：取各档跨剧本的最大值
const tiers = ['LOW', 'MED', 'HIGH'];
const worst = {};
for (const r of out) {
  const w = worst[r.tier] || (worst[r.tier] = { maxOps: 0, maxFill: 0, maxp: {} });
  if (r.maxOps > w.maxOps) w.maxOps = r.maxOps;
  if (r.maxFill > w.maxFill) w.maxFill = r.maxFill;
  for (const k in r.maxp) {
    const m = w.maxp[k] || (w.maxp[k] = { ops: 0, fill: 0, t: 0 });
    if (r.maxp[k].ops > m.ops) { m.ops = r.maxp[k].ops; m.t = r.maxp[k].t; }
    if (r.maxp[k].fill > m.fill) m.fill = r.maxp[k].fill;
  }
}
console.log('\n── 逐类最坏帧（跨剧本最大；Kotlin 表的唯一来源）──');
console.log('类别'.padEnd(22) + 'LOW ops  fill        MED ops  fill        HIGH ops  fill');
const keys = new Set();
for (const t of tiers) for (const k in worst[t].maxp) keys.add(k);
const sorted = [...keys].sort((a, b) => {
  const fa = worst.HIGH.maxp[a] ? worst.HIGH.maxp[a].fill : -1;
  const fb = worst.HIGH.maxp[b] ? worst.HIGH.maxp[b].fill : -1;
  return fb - fa;
});
for (const k of sorted) {
  const c = (t) => {
    const m = worst[t].maxp[k];
    return m ? String(m.ops).padStart(7) + String(m.fill.toFixed(6)).padStart(12) : '      —           —';
  };
  console.log(k.padEnd(22) + c('LOW') + '   ' + c('MED') + '   ' + c('HIGH'));
}
console.log('\n── 逐类最坏帧（3 位**向上**取整，Kotlin 表直接落这一列）──');
console.log('类别'.padEnd(22) + 'LOW ops  fill     MED ops  fill     HIGH ops  fill');
for (const k of sorted) {
  const c = (t) => {
    const m = worst[t].maxp[k];
    return m ? String(m.ops).padStart(7) + String((Math.ceil(m.fill * 1000) / 1000).toFixed(3)).padStart(9) : '      —        —';
  };
  console.log(k.padEnd(22) + c('LOW') + '   ' + c('MED') + '   ' + c('HIGH'));
}
console.log('\n── 合计（逐类最坏帧求和，Kotlin 表口径）──');
for (const t of tiers) {
  let so = 0, sf = 0;
  for (const k in worst[t].maxp) { so += worst[t].maxp[k].ops; sf += worst[t].maxp[k].fill; }
  console.log(t.padEnd(9) + 'ops=' + String(so).padStart(4) + '  fill=' + sf.toFixed(3)
    + '   （单帧最坏 ops=' + worst[t].maxOps + ' / fill=' + worst[t].maxFill.toFixed(3) + '）');
}
console.log('\n── 合计（均值，§十 表口径）──');
for (const t of tiers) {
  const r = out.find((x) => x.tier === t);
  console.log(t.padEnd(9) + 'ops=' + r.meanOps.toFixed(1) + '  fill=' + r.meanFill.toFixed(2));
}

// 分布形态：超阈帧数 / 帧数 —— 尖峰还是平台
console.log('\n── 铺屏分布形态（超阈帧数 / ' + N + '）──');
console.log('档位'.padEnd(9) + [2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 4.66, 5.0, 5.2, 5.4, 5.5, 6.0]
  .map((th) => ('>' + th).padStart(7)).join(''));
for (const r of out) {
  console.log(('V' + r.v + ' ' + r.tier).padEnd(9)
    + [2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 4.66, 5.0, 5.2, 5.4, 5.5, 6.0]
      .map((th) => String(r.over[th] || 0).padStart(7)).join(''));
}

// top 帧：按铺屏取前 12 帧，看最坏段是"一瞬间"还是"一段"
console.log('\n── 铺屏 top 帧（每档前 12）──');
for (const r of out) {
  const s = r.top.slice().sort((a, b) => b.fill - a.fill).slice(0, 12);
  console.log('V' + r.v + ' ' + r.tier + ': ' + s.map((x) => x.t.toFixed(2) + 's/' + x.fill.toFixed(2)).join('  '));
}

// 最坏帧构成：铺屏最大的那一帧里，每一类付了多少（决定"第一刀砍谁"）
console.log('\n── 最坏帧构成（该档铺屏最大那一帧的逐类原样）──');
for (const r of out) {
  console.log('V' + r.v + ' ' + r.tier + ' @t=' + r.tFill + 's  总 ops=' + r.maxOps + ' / fill=' + r.maxFill.toFixed(3));
  const ks = Object.keys(r.worstProf).sort((a, b) => r.worstProf[b].fill - r.worstProf[a].fill);
  for (const k of ks) {
    const q = r.worstProf[k];
    if (q.fill < 0.0005 && q.ops < 2) continue;
    console.log('    ' + k.padEnd(20) + String(q.ops).padStart(4) + ' ops' + String(q.fill.toFixed(3)).padStart(9) + ' 屏');
  }
}
