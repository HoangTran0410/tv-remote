// Every APK a release carries is named where people read: tv-voice.apk shipped with no word in either README or the
// guide until the user asked (2026-11-03). A new release file = its line in README.md and README.vi.md (a link, or why it has no
// Downloader code), and a flow or guide topic for what it does.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '../../..');
const read = (f) => fs.readFileSync(path.join(ROOT, f), 'utf8');
const apks = [...new Set([...read('dev/release/release.sh').matchAll(/build\/([a-z-]+\.apk)/g)].map((m) => m[1]))].sort();

test('release.sh attaches APKs', () => assert.ok(apks.length >= 7, apks.join()));

for (const f of ['README.md', 'README.vi.md']) {
  test(`${f} names every APK a release carries`, () => {
    const s = read(f);
    assert.deepStrictEqual(apks.filter((a) => !s.includes(a)), [], 'say what each is (a link, or why it has no Downloader code)');
  });
}
