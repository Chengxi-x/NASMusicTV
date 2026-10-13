// E46 方案文档行号对账：把文档里所有「`符号` (行号)」引用与 html 当前真实定义行逐一比对。
// 用法：node docs/archive/verification/scripts/e46_doc_linecheck.js [plan.md] [preview.html]
// 口径：行号 = 该标识符在 html 里**首次被声明**的那一行（function/const/let/var，含一行多声明）。
// ⛔ 靠眼睛扫表 —— 附录 B 有三十多行、每行最多七个号，改一次原型就整体推移。
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const ARGS = process.argv.slice(2).filter((s) => !s.startsWith('--'));
const DOC = resolve(ARGS[0] || 'docs/e46-tripod-fog-plan.md');
const HTML = resolve(ARGS[1] || 'docs/e46-tripod-fog-preview.html');

/* ── 1. 建符号 → 声明行 的索引 ─────────────────────────────────────── */
const lines = readFileSync(HTML, 'utf8').split(/\r?\n/);
const decl = new Map();            // name -> 1-based line
for (let i = 0; i < lines.length; i++) {
  const raw = lines[i];
  // 缩进的成员方法（`  foo(a,b) {`）不算声明：只认行首（可含 `export`）的声明式
  const m = raw.match(/^\s*(?:export\s+)?(function|const|let|var)\s+(.*)$/);
  const prop = raw.match(/^\s*([A-Za-z_$][\w$]*)\s*:\s*(?:function\b|\(|async\b)/);
  if (prop) { set(prop[1], i + 1); continue; }   // 导出对象里的方法（`capture: function (w,h)`）
  if (!m) continue;
  let rest = m[2];
  if (m[1] === 'function') {
    const n = rest.match(/^([A-Za-z_$][\w$]*)/);
    if (n) set(n[1], i + 1);
    continue;
  }
  // const/let/var：一行可跟多个声明（`let W=960,H=540,cx=480`），逐个取名字。
  // 只切「顶层逗号」—— 对象字面量 / 数组 / 模板串里的逗号不能当分隔符。
  let depth = 0, quote = '', buf = '';
  const parts = [];
  for (const ch of rest) {
    if (quote) { buf += ch; if (ch === quote) quote = ''; continue; }
    if (ch === '"' || ch === "'" || ch === '`') { quote = ch; buf += ch; continue; }
    if ('([{'.includes(ch)) depth++;
    if (')]}'.includes(ch)) depth--;
    if (ch === ',' && depth === 0) { parts.push(buf); buf = ''; continue; }
    buf += ch;
  }
  parts.push(buf);
  for (const p of parts) {
    const n = p.match(/^\s*([A-Za-z_$][\w$]*)/);
    if (n) set(n[1], i + 1);
  }
}
function set(name, ln) { if (!decl.has(name)) decl.set(name, ln); }   // 首次声明为准

/* ── 2. 抽文档里的引用 ─────────────────────────────────────────────── */
// 形态：`a` / `b` / `c` (11/22/33) 、`a/b/c` (11/22/33) 、`a/b/c` (322–327) 、`a/b/c` (838)
// ⚠️ 只扫表格行（`|` 开头）：正文里的「§七 (3)」「`TCROSS` (708)」这类是章节号或巧合，不是行号引用。
// ⚠️ 只认 ≥100 的号：html 有五千多行，真引用最小的是 161；三位数以下一律当章节/条目编号放过。
const doc = readFileSync(DOC, 'utf8').split(/\r?\n/);
const RE_NUMS = /^[（(]\s*([\d/\-–,、 ]+)[)）]\s*$/;
const refs = [];                   // {line, names[], nums[], raw}
for (let i = 0; i < doc.length; i++) {
  const text = doc[i];
  if (!text.trimStart().startsWith('|')) continue;
  let m;
  const groups = [];
  const RE_G = /`([^`]+)`(?:\s*[\/、+,]\s*`([^`]+)`)*|[（(]\s*[\d\/\-–,、]+\s*[)）]/g;
  while ((m = RE_G.exec(text))) {
    const tok = m[0].trim();
    if (/^[（(]/.test(tok)) {
      if (!RE_NUMS.test(tok)) continue;
      const nums = tok.match(/\d+/g).map(Number);
      if (!nums.every((n) => n >= 100)) { groups.length = 0; continue; }
      const names = [];
      for (const g of groups) names.push(...g.split(/[\/、,]/).map((s) => s.trim()).filter(Boolean));
      if (names.length) refs.push({ line: i + 1, names, nums, raw: tok });
      groups.length = 0;
    } else {
      const inside = m[0].match(/`([^`]+)`/g).map((s) => s.slice(1, -1));
      // 名单里混进非标识符（如 `P` 里带 ⛔ 的初值）就丢掉这一组，避免误配
      for (const s of inside) if (/^[\w$]+(\/[\w$]+)*$/.test(s)) groups.push(s);
      else groups.length = 0;
    }
  }
}

/* ── 3. 对账（--fix 时按 html 实际行号原地重写引用） ────────────────── */
const FIX = process.argv.includes('--fix');
// `Tripod*` 是 Kotlin 侧归属列的类名，⛔ 当 html 符号找
for (const r of refs) r.names = r.names.filter((n) => !/^Tripod/.test(n));
let bad = 0, ok = 0, unknown = 0, fixed = 0;
const out = [];
for (const r of refs) {
  const miss = r.names.filter((n) => !decl.has(n));
  if (miss.length) { unknown++; out.push(`  ?  文档 ${String(r.line).padStart(4)} 行：`
    + miss.join('/') + ' 在 html 里找不到声明（改名了？还是引用写错？）'); continue; }
  const actual = r.names.map((n) => decl.get(n));
  const isRange = /[-–]/.test(r.raw);
  let pass;
  if (r.nums.length === 1 && r.names.length > 1) {
    pass = actual.every((a) => a === r.nums[0]);
  } else if (isRange && r.nums.length === 2) {
    const [lo, hi] = r.nums;
    pass = actual.every((a) => a >= lo && a <= hi)
      && actual.every((a, j) => j === 0 || a >= actual[j - 1]);
  } else {
    pass = r.names.length === r.nums.length && actual.every((a, j) => a === r.nums[j]);
  }
  if (pass) { ok++; continue; }
  if (FIX) {
    // 一号多名 → 仍写一号（同一行的多声明）；区间 → 写成 lo–hi；其余 → 与名单同长的斜杠列
    const wide = /（/.test(r.raw);
    let nums = r.nums.length === 1 && r.names.length > 1 && actual.every((a) => a === actual[0])
      ? [actual[0]] : (isRange && r.nums.length === 2 ? [actual[0], actual[actual.length - 1]] : actual);
    const tok = (wide ? '（' : '(') + nums.join(isRange && r.nums.length === 2 ? '–' : '/') + (wide ? '）' : ')');
    doc[r.line - 1] = doc[r.line - 1].replace(r.raw, tok);
    fixed++;
    out.push(`  ✏️ 文档 ${String(r.line).padStart(4)} 行：` + r.raw + ' ⇒ ' + tok);
    continue;
  }
  bad++;
  out.push(`  ❌ 文档 ${String(r.line).padStart(4)} 行：` + r.names.join(' / ')
    + ' 写作 ' + r.raw + ' ⇒ 实际 ' + actual.join('/'));
}
console.log(`── E46 文档行号对账：${refs.length} 条引用（✅ ${ok} / ❌ ${bad} / ? ${unknown}${FIX ? ` / ✏️ ${fixed}` : ''}）──`);
for (const s of out) console.log(s);
if (FIX && fixed) {
  writeFileSync(DOC, doc.join('\n'), 'utf8');
  console.log(`⇒ 已按 html 实际行号重写 ${fixed} 条；再跑一次本脚本（不带 --fix）应为 0 条 ❌。`);
} else if (bad || unknown) {
  console.log('⇒ 按上面的「实际」列改文档，⛔ 手推偏移量（不同段落推移不同）；或加 --fix 让本脚本重写。');
}
process.exit(unknown || (!FIX && bad) ? 1 : 0);
