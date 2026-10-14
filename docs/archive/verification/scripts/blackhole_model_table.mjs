// E45 黑洞 · 椭圆族几何模型的数表再生器。
//
// 用途：方案文档 §核心几何模型 里那张拱顶高度表**只能**由本脚本产出，
//       ⛔ 不许把数抄进 Kotlin 注释 / 文档正文后就不再对账（E44 偏差 D24 的成因）。
// 跑法：node docs/archive/verification/scripts/blackhole_model_table.mjs [--incl=0,9,40] [--L0=1.15]
//       加 --json 出全精度机器可读表（供 blackhole_g4_crosscheck.mjs 与原型逐行对账；
//       ⛔ 文本表只有 3 位小数，拿它当对账源会造出假的偏差）。
//
// 模型（全部长度以 rs = 1 为单位；阴影是**表观**半径，不是视界）：
//   R_sh = 3*sqrt(3)/2 ≈ 2.598 rs        临界碰撞参数，即看到的黑心半径
//   a(r)      = k*r                       环的半宽（水平），k = 像素/rs
//   b_near    = k*r*sin(i)                近侧（一次像，在阴影**前**）
//   b_far     = k*r*(sin(i) + L)          远侧被抬起（二阶像，在阴影**后**）
//   b_ter     = k*r*(sin(i) + T)          三阶像，落在下侧
//     L = L0*(R_sh/r)^p * cos(i)^m        透镜抬升：内圈抬得多，故内环贴阴影成环
//     T = tau*L
//
// ⚠️ tau 是**几何**抬升比，不是亮度比。下拱要弱，靠 terBright（只管 alpha）。
//    ⛔ 不要用调小 tau 的办法压暗下拱 —— 实测 tau < 0.95 时 i=0 的三阶内环拱顶
//    会沉进阴影被吃掉（0.55 ⇒ 0.580 R_sh，0.85 ⇒ 0.897 R_sh，0.95 ⇒ 1.002 R_sh），
//    于是参考图2 那个**完整**的环拼不出来。文件末尾的自检守这条。
//
// 关键判据（这就是为什么这套参数化能出参考图的拓扑）：
//   i→0 时近侧塌成一条线，二阶 + 三阶上下合拢 ⇒ 一个抱紧阴影的环（参考图 2）；
//   i 增大时二阶拱顶抬高、近侧展开成宽椭圆 ⇒ 星际穿越那种「盘 + 上拱」（参考图 1/3/4）。

const RSH = 3 * Math.sqrt(3) / 2;

function parseArgs() {
  const a = { incl: [0, 9, 40], L0: 1.15, p: 1.6, tau: 0.96, m: 1.0, r: [3, 4, 6, 9, 13], json: false };
  for (const s of process.argv.slice(2)) {
    const [k, v] = s.replace(/^--/, '').split('=');
    if (!(k in a)) throw new Error('未知参数 --' + k);
    if (v === undefined) { a[k] = true; continue; }   // --json 是无值开关
    a[k] = k === 'incl' || k === 'r'
      ? v.split(',').map(x => { const n = Number(x); if (!isFinite(n)) throw new Error(k + ' 含非数: ' + x); return n; })
      : Number(v);
  }
  return a;
}

const a = parseArgs();
const f3 = x => x.toFixed(3);

const rows = [];
let warn = 0;
for (const i of a.incl) {
  const rad = i * Math.PI / 180, s = Math.sin(rad), c = Math.cos(rad);
  for (const r of a.r) {
    const L = a.L0 * Math.pow(RSH / r, a.p) * Math.pow(c, a.m);
    const T = a.tau * L;
    const top2 = r * (s + L) / RSH;
    const top3 = r * (s + T) / RSH;
    if (i === 0 && r === a.r[0]) {
      if (top2 < 1.0) warn++;
      if (top3 < 1.0) warn++;
    }
    rows.push({ incl: i, r, sin: s, L, bNear: s, bFar: s + L, bTer: s + T,
                topFarRsh: top2, topTerRsh: top3 });
  }
}

if (a.json) {
  console.log(JSON.stringify({
    RshRs: RSH, params: { L0: a.L0, p: a.p, tau: a.tau, m: a.m }, incl: a.incl, r: a.r, rows
  }));
} else {
  console.log('# E45 黑洞几何模型数表  (本输出由 blackhole_model_table.mjs 再生，勿手抄)');
  console.log('# R_sh = ' + f3(RSH) + ' rs   L0=' + a.L0 + ' p=' + a.p + ' tau=' + a.tau + ' m=' + a.m);
  console.log('# 判据: i=0 时最内环的二阶**和**三阶拱顶都须 >= 1.00 R_sh,');
  console.log('#       否则正侧视拼不出参考图2 那个完整的抱影环（二阶或三阶被阴影吃掉）。');
  for (const i of a.incl) {
    const rad = i * Math.PI / 180, s = Math.sin(rad), c = Math.cos(rad);
    console.log('\n## incl = ' + i + '°   (sin=' + f3(s) + ', cos=' + f3(c) + ')');
    console.log('r/rs |    L    |  b/a近  |  b/a二  |  b/a三  | 二阶拱顶/R_sh | 三阶拱顶/R_sh');
    for (const row of rows.filter(x => x.incl === i)) {
      console.log(
        String(row.r).padStart(4) + ' | ' + f3(row.L).padStart(6) + ' | ' +
        f3(row.bNear).padStart(6) + ' | ' + f3(row.bFar).padStart(6) + ' | ' +
        f3(row.bTer).padStart(6) + ' | ' + f3(row.topFarRsh).padStart(14) + ' | ' + f3(row.topTerRsh)
      );
    }
  }
}

if (warn) {
  // json 模式下 stdout 只能是那一行 JSON ⇒ 告警走 stderr，否则下游 JSON.parse 直接崩
  const out = a.json ? console.error : console.log;
  if (!a.json) console.log('');
  out('!! ' + warn + ' 条 i=0 最内环拱顶 < 1.00 R_sh ⇒ 抬 L0（二阶）或抬 tau（三阶）。');
  out('!! ⛔ 不要为了让它绿而把判据改小 —— 判据来自参考图2。');
  process.exitCode = 1;
} else if (!a.json) {
  console.log('\n✓ i=0 抱影环判据全过（二阶 + 三阶都露出阴影）');
}
