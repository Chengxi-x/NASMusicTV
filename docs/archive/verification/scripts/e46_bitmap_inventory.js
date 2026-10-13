// E46「迷雾之战」**常驻 native 位图清单**（`TripodOpBudget.nativePxBudget` 的唯一数字来源）。
//
// ⭐ 为什么单独一个脚本：E46 的位图结构与 E44 **根本不同** —— E44 是「源图 + 圆盘 + 跨效果
//    共享的全屏 STARFIELD」，本效果是「两张雾条 + 48 栋建筑 + 4 张人群 + **一张全屏 grade**」
//    （暗角+颗粒合成位图，`bakeGrade` 按 `cv.width×cv.height` 烘）。grade 这一项就吃掉
//    1080p 的 2,073,600 px ⇒ 必须逐项量出来，⛔ 照抄 E44 的表。
//
// ⛔ 不在本脚本里另写一份 DOM 桩：bootstrap 直接切自 `e46_fog_harness.js` 的
//    「// ── 自检」标记之前那段（沙箱 + `__E46` 导出），harness 改了桩这里自动跟着改。
//
// 用法：node docs/archive/verification/scripts/e46_bitmap_inventory.js [--size 1920x1080]
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(__dirname, '..', '..', '..', '..');
const harnessPath = path.join(root, 'docs', 'archive', 'verification', 'scripts', 'e46_fog_harness.js');
const src = fs.readFileSync(harnessPath, 'utf8');
const cut = src.indexOf('// ── 自检');
if (cut < 0) throw new Error('harness 结构变化：找不到「// ── 自检」标记（bootstrap 切分点）');
const bootstrap = src.slice(0, cut);

const sizeArg = process.argv.indexOf('--size');
const sizes = sizeArg > 0 ? [process.argv[sizeArg + 1]] : ['960x540', '1280x720', '1920x1080', '2560x1440'];

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

// ⚠️ `layout(w,h)` 会按新画布尺寸重算 PPM/hz，`bakeGrade` 用的是当前 W/H
//    （capture() 同一条路径：改尺寸 → layout → 重烘依赖尺寸的那几张）。
const probe = `
(function () {
  const E = window.__E46;
  const out = { sizes: [] };
  for (const s of ${JSON.stringify(sizes)}) {
    const w = +s.split('x')[0], h = +s.split('x')[1];
    // ⛔ 不走 E.capture()（它会 render 一遍、还要 toDataURL）—— 这里只要位图尺寸。
    //    layout 在 bootstrap 里是模块级函数，只能经 capture 的同类路径触发；
    //    所以改用 __E46.seek(0) 之前先借 capture 的尺寸切换副作用太绕，
    //    直接数**当前**（boot 时的那张 960×540）位图，grade 按目标尺寸线性外推。
    out.sizes.push({ w: w, h: h });
  }
  const b = E.bands();
  const inv = (arr, tag) => {
    let px = 0, maxPy = 0, minPy = 1e9, n = 0;
    const dims = [];
    for (const x of arr) {
      const bw = x.bmp.width, bh = x.bmp.height;
      px += bw * bh; n++;
      if (bh > maxPy) maxPy = bh;
      if (bh < minPy) minPy = bh;
      dims.push(bw + 'x' + bh);
    }
    return { tag: tag, n: n, px: px, maxPy: maxPy, minPy: minPy, dims: dims };
  };
  out.mid = inv(b.mid, '建筑中景');
  out.near = inv(b.near, '建筑近景');
  out.stripA = (function () { return null; })();
  return out;
})();
`;
// 雾条 / 人群 / grade 不在 __E46 暴露面上（它们是模块级 let）⇒ 只能从 drawImage 的实参侧拿：
// 走 E.profAt(0) 之前的 render 会把雾条 blit 出来，但桩 ctx 不记参数。
// ⭐ 所以这三项按**源码结构常量**记（bakeAll 里的字面量），由 Kotlin 侧同常量对账：
//   fogStrips.A = 256x300、fogStrips.B = 192x300、person[4] = 14x34、grade = W x H。
const STRUCT = { stripA: [256, 300], stripB: [192, 300], person: [14, 34], personN: 4, bldW: 96 };
const r = vm.runInContext(probe, sandboxCtx, { filename: 'e46_bitmap_inventory.js:probe' });

console.log('── 常驻 native 位图清单（ARGB_8888，1 px = 4 byte）──');
for (const g of [r.mid, r.near]) {
  console.log(g.tag.padEnd(10) + '栋数 ' + String(g.n).padStart(3)
    + '  合计 ' + String(g.px).padStart(9) + ' px'
    + '  py ∈ [' + g.minPy + ', ' + g.maxPy + ']'
    + '  均 py ' + (g.px / g.n / STRUCT.bldW).toFixed(1));
}
const bldPx = r.mid.px + r.near.px;
const bldPxMax = (r.mid.n + r.near.n) * STRUCT.bldW * 320;
console.log('建筑合计   ' + String(bldPx).padStart(9) + ' px（实测）  /  ' + bldPxMax + ' px（结构上界 = 48 × 96 × 320）');
const fogPx = STRUCT.stripA[0] * STRUCT.stripA[1] + STRUCT.stripB[0] * STRUCT.stripB[1];
const personPx = STRUCT.personN * STRUCT.person[0] * STRUCT.person[1];
console.log('雾条合计   ' + String(fogPx).padStart(9) + ' px（A 256x300 + B 192x300，源码字面量）');
console.log('人群合计   ' + String(personPx).padStart(9) + ' px（4 × 14x34，源码字面量）');
console.log('');
console.log('档位        grade(W×H)      建筑实测     建筑上界      雾条+人群     合计(实测)    合计(上界)   vs 3,000,000');
for (const s of r.sizes) {
  const grade = s.w * s.h;
  const a = grade + bldPx + fogPx + personPx;
  const b = grade + bldPxMax + fogPx + personPx;
  console.log((s.w + 'x' + s.h).padEnd(10)
    + String(grade).padStart(10)
    + String(bldPx).padStart(13)
    + String(bldPxMax).padStart(13)
    + String(fogPx + personPx).padStart(13)
    + String(a).padStart(14)
    + String(b).padStart(14)
    + '   ' + (a > 3000000 ? '❌实测超' : '✅实测内') + ' / ' + (b > 3000000 ? '❌上界超' : '✅上界内'));
}
console.log('');
console.log('⚠️ grade 是**全屏**位图（bakeGrade 按 cv.width×cv.height）⇒ 它是唯一随画幅线性涨的一项；');
console.log('   建筑 48 栋是固定项（py 由 bakeBuilding 的 clamp(round(96·h/w), 48, 320) 决定，实测分布见上）。');
