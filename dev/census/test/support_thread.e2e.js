// Support replies end to end, against a local worker (not part of `node --test`: it needs one running):
//   npx wrangler d1 execute tvr-census --local --persist-to D --file=schema.sql
//   npx wrangler dev --local --persist-to D --port 8798 --var CRASH_TOKEN:testtoken
//   node dev/census/test/support_thread.e2e.js [http://127.0.0.1:8798]
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

const URL = process.argv[2] || "http://127.0.0.1:8798";
const OWNER = { authorization: "Bearer testtoken" };
const post = async (p, b, h = {}, ct = "application/json") => {
  const r = await fetch(URL + p, { method: "POST", headers: { "content-type": ct, ...h }, body: typeof b === "string" ? b : JSON.stringify(b) });
  return { status: r.status, cors: r.headers.get("access-control-allow-origin"), j: r.status === 204 ? null : await r.json() };
};
const get = async (p) => { const r = await fetch(URL + p, { headers: OWNER }); return { status: r.status, j: await r.json() }; };

const id = randomUUID();
const send = () => post("/v1/support", { id, app: "setup", v: "0.50.0", model: "Test TV", report: "x" });
const a = (await send()).j, b = (await send()).j;                     // the same TV sent twice
assert.ok(a.code && b.code && a.code !== b.code);

// the owner replies to the OLDER code: the TV, asking with its newest, still sees it
assert.equal((await post("/v1/support/reply", { code: a.code, text: "Hi <b>there</b>\nline 2" }, OWNER)).j.id, 1);
let t = await post("/v1/support/thread", { code: b.code, key: b.key }, {}, "text/plain");   // text/plain: the page's, no preflight
assert.equal(t.status, 200); assert.equal(t.cors, "*");
assert.deepEqual(t.j.msgs.map((m) => [m.id, m.who, m.text]), [[1, "us", "Hi <b>there</b>\nline 2"]]);   // kept as text: the page shows it as text
assert.equal((await post("/v1/support/thread", { code: b.code, key: b.key, after: 1 })).j.msgs.length, 0);

// the person answers from the page; the owner sees it, and in "said since"
assert.equal((await post("/v1/support/say", { code: a.code, key: a.key, text: "  thanks \u0000 " }, {}, "text/plain")).j.id, 2);
const o = (await get("/v1/support?code=" + b.code)).j;
assert.deepEqual(o.msgs.map((m) => [m.who, m.text]), [["us", "Hi <b>there</b>\nline 2"], ["tv", "thanks"]]);
assert.equal(o.codes.length, 2); assert.equal(o.h, undefined);
const said = (await get("/v1/support/said?since=" + (Date.now() - 60_000))).j.said.filter((m) => m.text === "thanks");
assert.equal(said.length, 1); assert.equal(said[0].code, b.code);    // the newest code of that TV: where a reply goes

// wrong key, unknown code, bad shapes, empty text, the owner's routes without the token
assert.equal((await post("/v1/support/thread", { code: b.code, key: a.key })).status, 403);
assert.equal((await post("/v1/support/thread", { code: "00000000", key: "x" })).status, 403);
assert.equal((await post("/v1/support/thread", "not json")).status, 403);
assert.equal((await post("/v1/support/say", { code: b.code, key: b.key, text: " \n " })).status, 400);
assert.equal((await post("/v1/support/reply", { code: b.code, text: "x" })).status, 403);
assert.equal((await post("/v1/support/reply", { code: "00000000", text: "x" }, OWNER)).status, 404);
assert.equal((await get("/v1/support/said")).status, 200);

// long text is cut, not refused; a day's says run out
const long = await post("/v1/support/say", { code: b.code, key: b.key, text: "y".repeat(5000) });
assert.equal(long.status, 200);
assert.equal((await post("/v1/support/thread", { code: b.code, key: b.key, after: 2 })).j.msgs[0].text.length, 2000);
let last;
for (let i = 0; i < 25; i++) last = await post("/v1/support/say", { code: b.code, key: b.key, text: "n" + i });
assert.equal(last.status, 429);

// two writes at once both land, with different ids
const two = await Promise.all([post("/v1/support/reply", { code: b.code, text: "p" }, OWNER), post("/v1/support/reply", { code: b.code, text: "q" }, OWNER)]);
assert.ok(two.every((r) => r.status === 200)); assert.notEqual(two[0].j.id, two[1].j.id);

// forgotten: the thread goes with it, and the TV is told (403) so it stops asking
await fetch(URL + "/v1/support?code=" + a.code, { method: "DELETE", headers: OWNER });
assert.equal((await post("/v1/support/thread", { code: b.code, key: b.key })).status, 200);   // b still holds the thread
await fetch(URL + "/v1/support?code=" + b.code, { method: "DELETE", headers: OWNER });
assert.equal((await post("/v1/support/thread", { code: b.code, key: b.key })).status, 403);
console.log("support thread e2e: ok");
