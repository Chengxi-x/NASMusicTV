// deskworlds 行为层 CPU 成本实测（可行性分析用，2026-10-10）
//
// 测的是 deskworlds 各场景**与渲染无关的模拟步进**（step/build），用来给
// 「把它的行为层照搬成原生 Compose 渲染器」这条路定数量级。
// GPU 侧成本（bloom 金字塔 / MSAA / 阴影）**不在本脚本范围内**，本机无 WebGL 前台窗口，
// 那部分必须真机或前台浏览器另测，不许从这里的数字外推。
//
// 运行：node docs/archive/verification/scripts/deskworlds_sim_cost.mjs
// 依赖：只有被测量项目自身的源文件，无 npm 包；被测目录不存在时**直接 fail，不许 skip**。
import { performance } from 'node:perf_hooks';
import { existsSync } from 'node:fs';

const SRC = process.env.DESKWORLDS_SRC
  ?? 'D:/hxzhang/MyGithubSoftware/NasAudio/deskworlds-main';
const FRAMES = Number(process.env.FRAMES ?? 600);

if (!existsSync(SRC)) {
  console.error(`FAIL: 找不到被测目录 ${SRC}（用 DESKWORLDS_SRC 指定）`);
  process.exit(1);
}

const p = (rel) => new URL(`file:///${SRC}/${rel}`).href;

/** 跑 n 帧，返回 {msPerFrame, segPerFrame, selfCheck} */
async function bench(label, factory, stepFn, probe) {
  const sim = factory();
  let t0 = performance.now();
  for (let i = 0; i < FRAMES; i++) stepFn(sim, i);
  const ms = performance.now() - t0 - t0 * 0; // 无嵌套计时
  const per = ms / FRAMES;

  // 自检 1：模拟必须真的产出过数据，否则「很快」只是「什么都没做」
  const probeValue = probe(sim);
  if (!probeValue || (typeof probeValue === 'number' && !(probeValue > 0))) {
    console.error(`FAIL: ${label} 步进后探针读数=${probeValue}，模拟没有真的跑`);
    process.exit(1);
  }

  // 自检 2：空转基线必须显著低于被测值，否则计时精度不够
  const idle0 = performance.now();
  for (let i = 0; i < FRAMES; i++) { /* nothing */ }
  const idle = (performance.now() - idle0) / FRAMES;
  if (per < idle * 20) {
    console.error(`FAIL: ${label} 每帧 ${per.toFixed(4)}ms 与空转 ${idle.toFixed(4)}ms 同量级，测不出东西`);
    process.exit(1);
  }

  console.log(
    `${label.padEnd(22)} 每帧 ${per.toFixed(3)} ms   ` +
    `(空转基线 ${idle.toFixed(4)} ms)   探针 ${JSON.stringify(probe(sim))}`
  );
  return per;
}

const results = {};

// ---- plasmascape：40 条电弧 / 上限 9000 段，FIXED_STEP = 1/60 ----
{
  const { createPlasma, FIXED_STEP } = await import(p('scenes/plasmascape/src/plasma.js'));
  const { randomGenerator } = await import(p('scenes/shared/random.js'));
  results.plasma = await bench(
    'plasma step',
    () => createPlasma({ random: randomGenerator(7) }),
    (sim, i) => {
      sim.setFinger({ x: Math.sin(i / 30) * 0.4, y: 0.1, z: 0.6, near: 0.5, present: true });
      sim.step(FIXED_STEP);
      if (i % 2) sim.build();          // 与 main.js:109 同节奏：每两帧重建一次几何
    },
    (sim) => sim.state.segmentCount
  );
}

// ---- bonfirescape：3000 颗 CPU 积分火花，FIXED_STEP = 1/120 ----
{
  const { createFire, FIXED_STEP, SPARKS } = await import(p('scenes/bonfirescape/src/fire.js'));
  const { randomGenerator } = await import(p('scenes/shared/random.js'));
  results.fire = await bench(
    'fire step (3000 sparks)',
    () => createFire({ random: randomGenerator(7) }),
    (sim, i) => {
      if (i % 30 === 0) sim.stir(0.8);  // 模拟「按拍搅动」
      sim.step(FIXED_STEP);
    },
    (sim) => SPARKS > 0 ? sim.state.sparks ?? sim.sparks.length : 0
  );
}

// ---- koiscape 鱼群行为（不碰 three）----
{
  const pond = await import(p('scenes/koiscape/src/pond.js')).catch(() => null);
  if (pond) {
    const names = Object.keys(pond).filter(k => typeof pond[k] === 'function');
    console.log(`koiscape/pond.js 导出（可 Node 直调）: ${names.join(', ')}`);
  }
}

// 汇总：给出「一帧预算」换算
const budget = (per, fps) => {
  const p95ish = per * 1.5;
  return `${fps}fps 预算 ${(1000 / fps).toFixed(1)}ms 内占 ${(p95ish / (1000 / fps) * 100).toFixed(1)}%`;
};
console.log('\n换算（⚠️ 桌面 x64 V8，电视 ARM SoC 至少再乘 3~5 才可比）:');
for (const [k, v] of Object.entries(results)) console.log(`  ${k}: ${budget(v, 30)}`);
