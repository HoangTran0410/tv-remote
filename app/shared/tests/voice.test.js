// node --test app/shared/tests/   (Node 18+, nothing to install)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { fold, norm, voiceCommand, wantsApp, channelLink } = require('../web/js/voice.js');

// the words are data (docs/voice/phrases.json, packed in app/shared/web/voice/); the case table is read by VoiceTest.kt too
const ROOT = path.join(__dirname, '..', '..', '..');
const PACKED = path.join(ROOT, 'app', 'shared', 'web', 'voice', 'phrases.json');
const LIST = JSON.parse(fs.readFileSync(PACKED, 'utf8'));
const TABLE = JSON.parse(fs.readFileSync(path.join(__dirname, 'voice_cases.json'), 'utf8'));
const APPS = Object.entries(TABLE.apps).map(([pkg, name]) => ({ pkg, name }));
const SCENES = Object.entries(TABLE.scenes).map(([id, name]) => ({ id, name }));
const cmd = (t, apps = [
  { pkg: 'com.google.android.youtube.tv', name: 'YouTube' },
  { pkg: 'com.netflix.ninja', name: 'Netflix' },
  { pkg: 'com.fptplay.tv', name: 'FPT Play' },
  { pkg: 'org.videolan.vlc', name: 'VLC' },
]) => voiceCommand(t, apps, SCENES, LIST);
const show = (c) => !c ? 'null' : c.kind === 'key' ? `key:${c.key}:${c.times}` : c.kind === 'app' ? `app:${c.pkg}` : c.kind === 'close' ? `close:${c.pkg}`
  : c.kind === 'channel' ? `channel:${c.name}` : c.kind === 'timer' ? `timer:${c.min}` : c.kind === 'search' ? `search:${c.pkg}:${c.query}` : `scene:${c.scene.id}`;

test('the sentence table (shared with VoiceTest.kt)', () => {
  assert.ok(TABLE.cases.length >= 40);
  for (const [text, want] of TABLE.cases) assert.equal(show(voiceCommand(text, APPS, SCENES, LIST)), want, text);
});

test('the packed list is the site\'s list, signed by the project\'s key', () => {
  const site = path.join(ROOT, 'docs', 'voice', 'phrases.json');
  assert.ok(fs.readFileSync(site).equals(fs.readFileSync(PACKED)), 'run dev/sign/sign_voice.py');
  assert.ok(fs.readFileSync(site + '.sig').equals(fs.readFileSync(PACKED + '.sig')), 'run dev/sign/sign_voice.py');
  const key = crypto.createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), Buffer.from('de2714846a352ea4e0e5b5dcf2272fee9b10bd2da6ebdea8cff858bac1c3f6ea', 'hex')]), format: 'der', type: 'spki' });
  const sig = Buffer.from(fs.readFileSync(PACKED + '.sig', 'utf8').trim(), 'base64');
  const body = fs.readFileSync(PACKED);
  assert.ok(crypto.verify(null, body, key, sig), 'signature does not match');
  assert.ok(!crypto.verify(null, Buffer.concat([body, Buffer.from(' ')]), key, sig), 'a changed list must fail');
});

test('"tivi" and "ti vi" read as tv; fillers at the ends go', () => {
  assert.equal(norm('Mở Ti Vi game'), 'mo tv game');
  assert.equal(wantsApp('tắt youtube', LIST), true);
  assert.equal(wantsApp('tăng âm lượng', LIST), false);
});

test('fold drops accents, case and punctuation', () => {
  assert.equal(fold('  Mở YouTube! '), 'mo youtube');
  assert.equal(fold('Đặt hẹn giờ'), 'dat hen gio');
});

test('opens apps by name, accents or not', () => {
  assert.deepEqual(cmd('mở YouTube'), { kind: 'app', pkg: 'com.google.android.youtube.tv', name: 'YouTube' });
  assert.equal(cmd('mo netflix').pkg, 'com.netflix.ninja');
  assert.equal(cmd('Xem FPT Play').pkg, 'com.fptplay.tv');
  assert.equal(cmd('open vlc').pkg, 'org.videolan.vlc');
  assert.equal(cmd('mở ứng dụng youtube').pkg, 'com.google.android.youtube.tv');
});

test('an app the TV does not have goes to the Assistant', () => {
  assert.equal(cmd('mở Spotify'), null);               // an alias, but the TV does not have it
  assert.equal(cmd('thời tiết hôm nay'), null);
  assert.equal(cmd(''), null);
});

test('channels', () => {
  for (const t of ['mở kênh vtv1', 'Xem kênh VTV1', 'chuyển kênh vtv1', 'chuyển sang kênh vtv1', 'bật kênh vtv1', 'kênh vtv1', 'open channel vtv1', 'watch channel vtv1', 'channel vtv1', 'tune to vtv1'])
    assert.deepEqual(cmd(t), { kind: 'channel', name: 'vtv1' }, t);
  assert.equal(cmd('mở kênh Thể thao').name, 'the thao');
  assert.equal(cmd('kênh tiếp').key, 'CH_UP');
  assert.equal(cmd('next channel').key, 'CH_UP');
  assert.equal(cmd('kênh trước').key, 'CH_DOWN');
  assert.equal(cmd('previous channel').key, 'CH_DOWN');
  assert.equal(channelLink('the thao'), 'tvchannels://find/the%20thao');
});

test('keys', () => {
  assert.deepEqual(cmd('tắt tiếng'), { kind: 'key', key: 'MUTE', times: 1 });
  assert.equal(cmd('Tạm dừng').key, 'PLAY_PAUSE');
  assert.equal(cmd('về màn hình chính').key, 'HOME');
  assert.equal(cmd('tắt TV').key, 'SLEEP');
  assert.equal(cmd('bật tivi').key, 'WAKE');
  assert.equal(cmd('bật tv').key, 'WAKE');                          // not an app called "tv"
});

test('volume steps, 3 by default, capped at 15', () => {
  assert.deepEqual(cmd('tăng âm lượng'), { kind: 'key', key: 'VOL_UP', times: 3 });
  assert.deepEqual(cmd('giảm âm lượng 5'), { kind: 'key', key: 'VOL_DOWN', times: 5 });
  assert.equal(cmd('volume up 99').times, 15);
});

test('sleep timer in minutes or hours, and cancelling it', () => {
  assert.deepEqual(cmd('hẹn giờ tắt 30 phút'), { kind: 'timer', min: 30 });
  assert.deepEqual(cmd('tắt TV sau 2 tiếng'), { kind: 'timer', min: 120 });
  assert.deepEqual(cmd('sleep in 45 minutes'), { kind: 'timer', min: 45 });
  assert.deepEqual(cmd('hủy hẹn giờ'), { kind: 'timer', min: 0 });
});

test('a scene by its name', () => {
  assert.equal(cmd('đi ngủ').scene.id, 's1');
  assert.equal(cmd('cảnh xem phim').scene.id, 's2');
});

test('"X on Y": a search inside the app, with its link when the list has one', () => {
  const c = voiceCommand('mở vtv3 trên fpt play', APPS, SCENES, LIST);
  assert.equal(c.kind, 'search');
  assert.equal(c.url, 'https://fptplay.vn/tim-kiem/vtv3');
  assert.equal(voiceCommand('Mở Sơn Tùng trên YouTube', APPS, SCENES, LIST).url, 'https://www.youtube.com/results?search_query=S%C6%A1n%20T%C3%B9ng');
  assert.equal(voiceCommand('open cats on netflix', APPS, SCENES, LIST).url, null);       // no link in the list: the app's own SEARCH or just the app
  assert.deepEqual(voiceCommand('mở vtv3 trên fpt play', [], SCENES, LIST).pkg, 'net.fptplay.ottbox');   // no app list: the alias's package
});
