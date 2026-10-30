// node --test app/shared/tests/   Folders on TV Home's grid as the page edits them (js/home_folders.js): each operation gives what
// tests/home_folders_cases.json says, the same table FoldersTest reads for Folders.kt; and the grid order the page draws leaves out
// the apps that sit in folders and has the folder tile in their place.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const web = path.join(__dirname, '../web/js');
const sandbox = { module: undefined };
vm.createContext(sandbox);
const load = (f) => vm.runInContext(fs.readFileSync(path.join(web, f), 'utf8').replace(/^(let|const) /gm, 'var '), sandbox);
load('target.js'); load('home_folders.js'); load('home_order.js');
const file = JSON.parse(fs.readFileSync(path.join(__dirname, 'home_folders_cases.json'), 'utf8'));
const plain = (x) => JSON.parse(JSON.stringify(x));

for (const c of file.cases) test(c.name, () => {
  const got = sandbox.HomeFolders[c.op](plain(file[c.from]), ...c.args);
  assert.deepEqual(got === null ? null : plain(got), c.change);
});

test('the grid order has the folder tile in the place of its apps', () => {
  const apps = [{ pkg: 'com.a.one', label: 'One' }, { pkg: 'com.b.two', label: 'Two' }, { pkg: 'com.c.three', label: 'Three' }, { pkg: 'com.e.four', label: 'Four' }];
  const c = { dock: [], hidden: [], tiles: ['folder:f1'], order: ['com.a.one', 'folder:f1'], folders: { f1: { name: 'Video', apps: ['com.b.two', 'com.e.four'] } } };
  assert.deepEqual(plain(sandbox.homeGridOrder(c, apps)), ['com.a.one', 'folder:f1', 'com.c.three']);
});

test('ids and names', () => {
  assert.ok(sandbox.homeIsFolder('folder:ab12'));
  assert.ok(!sandbox.homeIsFolder('folder:AB'));
  assert.ok(!sandbox.homeIsFolder('tile:a.b/c'));
  assert.equal(sandbox.HomeFolders.newId({ folders: {} }).length, 6);
});
