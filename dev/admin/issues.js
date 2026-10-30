#!/usr/bin/env node
// Which GitHub issues need a word from us, so nobody has to remind the agent:
//   - open, and the last word is not the owner's (a new issue, or an answer to us);
//   - labelled next-release ("fixed on main, waiting for a release") and a release came out after our last comment: say it is out,
//     then take the label off (and close the issue when the fix is all it asked for).
// Prints nothing when nothing is due. Run by the session hooks (.claude/settings.json; --every N: at most once per N minutes) and at
// the end of dev/release/release.sh. Needs gh; offline or no gh: silent. --json: [{key, text}] for crashwatch (key changes with each
// new comment, so an answer on an issue already waiting is news again; with --json every comment of someone else from the last
// RECENT_DAYS is a row too: one posted while we were writing our reply was hidden behind it, as "the last word is ours").
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const OWNER = 'HoangTran0410';
const LABEL = 'next-release';
const RECENT_DAYS = 3;
const gh = (...a) => JSON.parse(execFileSync('gh', a, { cwd: path.join(__dirname, '../..'), encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'ignore'] }));

function due(everyComment = false) {
  const issues = gh('issue', 'list', '--state', 'open', '--limit', '100', '--json', 'number,title,author,createdAt,comments,labels');
  const rel = gh('release', 'list', '--limit', '1', '--json', 'tagName,publishedAt')[0];
  const out = [];
  for (const i of issues) {
    const ours = i.comments.filter((c) => c.author.login === OWNER);
    const last = i.comments[i.comments.length - 1];
    const waiting = last ? last.author.login !== OWNER : i.author.login !== OWNER;
    const at = last ? last.createdAt : i.createdAt;
    if (everyComment) {                                              // the same key as the "waits" row of a last comment: told once either way
      const since = new Date(Date.now() - RECENT_DAYS * 86400000).toISOString();
      for (const c of i.comments) if (c.author.login !== OWNER && c.createdAt > since && c !== last)
        out.push(`${i.number}@${c.createdAt}|#${i.number} new comment by ${c.author.login} (answered after it? read it): ${i.title}`);
    }
    if (waiting) out.push(`${i.number}@${at}|#${i.number} waits for a reply (${last ? 'last comment by ' + last.author.login : 'no answer yet'}): ${i.title}`);
    const lastOurs = ours.length ? ours[ours.length - 1].createdAt : i.createdAt;
    if (rel && i.labels.some((l) => l.name === LABEL) && rel.publishedAt > lastOurs)
      out.push(`${i.number}@${rel.tagName}|#${i.number} is ${LABEL} and ${rel.tagName} is out since our last comment: say the fix is out, remove the label: ${i.title}`);
  }
  // pull requests: one from someone else is waiting until we said a word after its last push or comment (#11 sat unseen)
  for (const p of gh('pr', 'list', '--state', 'open', '--limit', '50', '--json', 'number,title,author,createdAt,updatedAt,comments,reviews')) {
    const words = [...p.comments, ...p.reviews.map((r) => ({ author: r.author, createdAt: r.submittedAt }))].sort((a, b) => a.createdAt < b.createdAt ? -1 : 1);
    const last = words[words.length - 1];
    if (p.author.login === OWNER && (!last || last.author.login === OWNER)) continue;
    if (last && last.author.login === OWNER && last.createdAt >= p.updatedAt.slice(0, 19)) continue;
    out.push(`pr${p.number}@${p.updatedAt}|PR #${p.number} by ${p.author.login} waits for a review (${last ? 'last word by ' + last.author.login : 'no answer yet'}): ${p.title}`);
  }
  return out;
}

if (require.main === module) {
  const k = process.argv.indexOf('--every');
  if (k > 0) {                                                       // a hook on every prompt: ask GitHub at most once per N minutes
    const stamp = path.join(os.tmpdir(), 'tvr-issues-' + OWNER);
    try { if (Date.now() - fs.statSync(stamp).mtimeMs < +process.argv[k + 1] * 60000) process.exit(0); } catch (e) { }
    try { fs.writeFileSync(stamp, ''); } catch (e) { }
  }
  let rows;
  try { rows = due(process.argv.includes('--json')).map((l) => ({ key: l.slice(0, l.indexOf('|')), text: l.slice(l.indexOf('|') + 1) })); } catch (e) { process.exit(0); }
  if (process.argv.includes('--json')) { console.log(JSON.stringify(rows)); process.exit(0); }
  const lines = rows.map((r) => r.text);
  if (lines.length) console.log('GitHub issues needing a reply (dev/admin/issues.js; replies are public: write them for the person, per AGENTS.md):\n- ' + lines.join('\n- '));
}
module.exports = { due };
