// minSdk is 21: APIs newer than that crash on old boxes at run time, and only :shared:lintDebug (NewApi) sees them. This is the cheap
// guard in the pre-push path for the calls that slipped in: ConcurrentHashMap.newKeySet() (use Collections.newSetFromMap) and
// AtomicInteger.updateAndGet { } (use @Synchronized or a compareAndSet loop), both API 24, and their API-24 kin.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

function* kt(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) yield* kt(p);
    else if (/\.(kt|java)$/.test(e.name)) yield p;
  }
}

const API24 = /\.(newKeySet|updateAndGet|getAndUpdate|accumulateAndGet|getAndAccumulate|computeIfAbsent|computeIfPresent|removeIf|getOrDefault)\s*[({]/;

test('no API-24 calls in the Android-shared Kotlin core', () => {
  const root = path.join(__dirname, '..', 'src', 'main');
  const bad = [];
  for (const f of kt(root)) {
    fs.readFileSync(f, 'utf8').split('\n').forEach((l, i) => {
      if (API24.test(l) && !l.trim().startsWith('//')) bad.push(`${path.relative(root, f)}:${i + 1}`);
    });
  }
  assert.deepStrictEqual(bad, [], 'API 24 calls (minSdk 21): newKeySet -> Collections.newSetFromMap(ConcurrentHashMap<T, Boolean>()), updateAndGet & co -> @Synchronized, getOrDefault -> get() ?: x');
});
