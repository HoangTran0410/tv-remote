#!/usr/bin/env node
// Keeps dev/flows/*.md true to the code (dev/flows/README.md says why):
//   node dev/flows/check.js                  every flow: the paths, symbols and tests it names exist; its Cases list equals the code's
//   node dev/flows/check.js --commits A..B   also: each commit that changed a flow's Code changed the flow, or says "Flow: unchanged (<why>)"
// app/shared/tests/flows.test.js runs the first, the pre-push hook both.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.join(__dirname, '../..');
const DIR = path.join(ROOT, 'dev/flows');

const ticks = (s) => [...s.matchAll(/`([^`\n]+)`/g)].map((m) => m[1]);
const isPath = (t) => /^(app|dev|docs)\/[\w./-]+\.\w+(:\w+)?$/.test(t);

/** The code files (under **Code:**) and the tests file (under **Tests:**) of one flow. */
function parts(text) {
  const block = (name) => { const m = text.match(new RegExp('\\*\\*' + name + ':\\*\\*([\\s\\S]*?)(?:\\n\\n|$)')); return m ? ticks(m[1]).filter(isPath) : []; };
  return { code: block('Code'), tests: block('Tests') };
}

/** What is wrong with one flow's text, as lines ([] when nothing). */
function checkFlow(text, root = ROOT) {
  const errs = [];
  const read = (p) => { try { return fs.readFileSync(path.join(root, p), 'utf8'); } catch (e) { return null; } };
  const { code, tests } = parts(text);
  if (!code.length) errs.push('no **Code:** files');
  const codeText = code.map(read).filter((s) => s !== null).join('\n');
  for (const t of new Set(ticks(text))) {
    if (isPath(t)) {                                                       // a file, and maybe a symbol in it
      const [p, sym] = t.split(':');
      const s = read(p);
      if (s === null) errs.push('no file ' + p);
      else if (sym && !new RegExp('\\b' + sym + '\\b').test(s)) errs.push(p + ' has no ' + sym);
      continue;
    }
    const m = t.match(/^([A-Z]\w*)\.(\w+)$/);                              // Class.member: declared in the Code files or in <Class>.kt
    const decl = new RegExp('\\b(fun|val|var|class|object)\\s+' + m?.[2] + '\\b');
    if (m && !decl.test(codeText) && !kt(root, m[1]).some((f) => decl.test(fs.readFileSync(f, 'utf8')))) errs.push('no declaration of ' + t);
  }
  // Cases (`file:LIST`): a, b, c  ==  listOf("a", "b", "c") in that file
  const c = text.match(/^Cases \(`([^`:]+):(\w+)`\): (.+)$/m);
  if (c) {
    const s = read(c[1]) || '';
    const l = s.match(new RegExp('val ' + c[2] + '\\s*=\\s*listOf\\(([^)]*)\\)'));
    const inCode = l ? [...l[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]) : null;
    const inFlow = c[3].split(',').map((x) => x.trim()).filter(Boolean);
    if (!inCode) errs.push(c[1] + ' has no listOf ' + c[2]);
    else if (inCode.join() !== inFlow.join()) errs.push('Cases differ from ' + c[2] + ': flow [' + inFlow + '], code [' + inCode + ']');
  }
  // the Test column of every table: each name a test in the Tests file
  const testText = tests.map(read).filter((s) => s !== null).join('\n');
  const tables = [];
  for (const line of text.split('\n')) {
    if (!line.startsWith('|')) { tables.push([]); continue; }
    tables[tables.length - 1].push(line.split('|').slice(1, -1).map((x) => x.trim()));
  }
  for (const rows of tables.filter((t) => t.length)) {
    const col = rows[0].indexOf('Test');
    if (col < 0) continue;
    for (const r of rows.slice(2)) for (const name of ticks(r[col] || ''))
      if (!new RegExp('fun ' + name + '\\(').test(testText)) errs.push('no test ' + name + ' in ' + tests.join(', '));
  }
  return errs;
}

/** The files named <name>.kt under app/ (build output left out). */
function kt(root, name) {
  const out = [];
  const walk = (d) => { for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.isDirectory()) { if (e.name !== 'build' && !e.name.startsWith('.')) walk(path.join(d, e.name)); }
    else if (e.name === name + '.kt') out.push(path.join(d, e.name));
  } };
  walk(path.join(root, 'app'));
  return out;
}

function flows(root = ROOT) {
  const dir = path.join(root, 'dev/flows');
  return fs.readdirSync(dir).filter((f) => f.endsWith('.md') && f !== 'README.md').map((f) => ({ file: 'dev/flows/' + f, text: fs.readFileSync(path.join(dir, f), 'utf8') }));
}

function check(root = ROOT) {
  return flows(root).flatMap((f) => checkFlow(f.text, root).map((e) => f.file + ': ' + e));
}

/** Commits in [range] that changed a flow's Code but not the flow, with no "Flow: unchanged" line. */
function commits(range, root = ROOT) {
  const git = (...a) => execFileSync('git', a, { cwd: root, encoding: 'utf8' });
  const list = flows(root).map((f) => {
    let names;                                                      // the flow's file under every name it had: a renamed flow still counts
    return { file: f.file, code: parts(f.text).code,
      names: () => names || (names = [...new Set([f.file, ...git('log', '--follow', '--name-only', '--format=', '--', f.file).split('\n').filter(Boolean)])]) };
  });
  const errs = [];
  for (const sha of git('rev-list', '--no-merges', range).split('\n').filter(Boolean)) {
    const msg = git('log', '-1', '--format=%B', sha);
    if (/^Flow: unchanged \(.+\)/m.test(msg)) continue;
    const changed = git('diff-tree', '--no-commit-id', '--name-only', '-r', sha).split('\n');
    for (const f of list) {
      const hit = f.code.filter((p) => changed.includes(p));
      if (hit.length && !f.names().some((n) => changed.includes(n)))
        errs.push(sha.slice(0, 8) + ' changed ' + hit.join(', ') + ' but not ' + f.file + ' (update it, or add "Flow: unchanged (<why>)" to the message)');
    }
  }
  return errs;
}

if (require.main === module) {
  const i = process.argv.indexOf('--commits');
  const errs = check().concat(i > 0 && process.argv[i + 1] ? commits(process.argv[i + 1]) : []);
  for (const e of errs) console.error(e);
  process.exit(errs.length ? 1 : 0);
}
module.exports = { check, checkFlow, commits };
