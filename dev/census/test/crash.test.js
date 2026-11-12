import { test } from "node:test";
import assert from "node:assert/strict";
import { validateCrash, crashSig, sigText, frame, sameSecret, text, MAX_CRASH } from "../src/logic.js";

const ID = "3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b";
const JAVA = ["java.lang.IllegalStateException: boom", "\tat java.util.ArrayList.get(ArrayList.java:437)", "\tat dev.tvremote.core.Foo.bar(Foo.kt:42)",
  "\tat dev.tvremote.core.Baz.run(Baz.kt:7)", "\tat android.os.Handler.handleCallback(Handler.java:942)", "\tat dev.tvremote.phone.Main.go(Main.kt:9)"].join("\n");
const body = (o = {}) => JSON.stringify({ id: ID, app: "phone", v: "0.3.1", where: "kotlin", dev: { brand: "Google", model: "Pixel 8", android: "14", sdk: 34 },
  kind: "java.lang.IllegalStateException", msg: "boom", stack: JAVA, ...o });

test("validateCrash: good body, unknown fields dropped, sizes cut", () => {
  const r = validateCrash(body({ extra: 1, msg: "m".repeat(999), stack: "s".repeat(20000) }));
  assert.equal(r.app, "phone"); assert.equal(r.loc, "kotlin"); assert.equal(r.extra, undefined);
  assert.equal(r.msg.length, 300); assert.equal(r.stack.length, 8192); assert.equal(r.sdk, 34);
});
test("validateCrash: the computer app sends os / arch / java and no brand", () => {
  const r = validateCrash(body({ app: "pc", dev: { os: "Mac OS X 15", arch: "aarch64", java: "21.0.4" } }));
  assert.equal(r.os, "Mac OS X 15"); assert.equal(r.arch, "aarch64"); assert.equal(r.java, "21.0.4"); assert.equal(r.brand, "");
});
test("validateCrash: rejects malformed, a bad place, an oversize body", () => {
  for (const t of ["", "{", "[]", body({ id: "x" }), body({ app: "karaoke" }), body({ where: "js" }), body({ kind: "" }), body({ stack: "x".repeat(MAX_CRASH) })])
    assert.equal(validateCrash(t), null, t.slice(0, 40));
});
test("validateCrash: emulators are rejected like a ping", () => {
  assert.equal(validateCrash(body({ dev: { brand: "google", model: "sdk_google_atv64_amati_arm64", device: "emu64a", hw: "ranchu" } })), null);
  assert.equal(validateCrash(body({ dev: { brand: "Google", model: "Android SDK built for arm64" } })), null);
});
test("text keeps line breaks, drops other control characters", () => {
  assert.equal(text("a\r\nb\u0000c\td"), "a\nb c\td");
  assert.equal(text(5, 9), "");
});
test("frame: line numbers, hosts and queries go; framework frames are not meaningful", () => {
  assert.equal(frame("\tat dev.tvremote.core.Foo.bar(Foo.kt:42)"), "dev.tvremote.core.Foo.bar(Foo.kt)");
  assert.equal(frame("    at show (http://192.168.1.5:8765/js/errbox.js:12:5)"), "show (/js/errbox.js)");
  assert.equal(frame("    at http://tv.local/js/a.js:3:9"), "/js/a.js");
  assert.equal(frame("show@http://x:8765/js/errbox.js?v=3:12:5"), "show (/js/errbox.js)");
  assert.equal(frame("\tat java.util.ArrayList.get(ArrayList.java:437)"), "");
  assert.equal(frame("\tat android.os.Handler.handleCallback(Handler.java:942)"), "");
  assert.equal(frame("Caused by: x.Y"), ""); assert.equal(frame("java.lang.X: boom"), "");
});
test("crashSig: the same place gives the same signature whatever the line, host or message", async () => {
  const a = await crashSig("phone", "K", JAVA);
  assert.match(a, /^[0-9a-f]{24}$/);
  assert.equal(a, await crashSig("phone", "K", JAVA.replace(/Foo\.kt:42/, "Foo.kt:99")));
  assert.notEqual(a, await crashSig("pc", "K", JAVA));
  assert.notEqual(a, await crashSig("phone", "Other", JAVA));
  assert.notEqual(a, await crashSig("phone", "K", JAVA.replace("Foo.bar", "Foo.baz")));
  const page = (host) => `Error: x\n    at a (http://${host}/js/a.js:1:2)\n    at b (http://${host}/js/b.js:3:4)`;
  assert.equal(await crashSig("pc", "Error", page("127.0.0.1:8765")), await crashSig("pc", "Error", page("10.0.0.4:8769")));
});
test("sigText: only the top 5 meaningful frames count", () => {
  const frames = Array.from({ length: 8 }, (_, i) => `\tat a.B${i}.m(B${i}.kt:${i})`).join("\n");
  assert.equal(sigText("pc", "K", frames).split("\n").length, 2 + 5);
  assert.equal(sigText("pc", "K", "\tat java.lang.Thread.run(Thread.java:1)").split("\n").length, 3);   // none meaningful: all frames kept
});
test("sameSecret", async () => {
  assert.equal(await sameSecret("abc", "abc"), true);
  assert.equal(await sameSecret("abd", "abc"), false);
  assert.equal(await sameSecret("", "abc"), false);
  assert.equal(await sameSecret("abc", undefined), false);
});

test("validateCrash: TV Home, Channels and Games report too", () => {
  for (const app of ["home", "channels", "games", "tvapps"]) assert.equal(validateCrash(body({ app })).app, app);
})

test("validateCrash: KidGuard's crashes are taken (shared table), its pings are not", async () => {
  assert.equal(validateCrash(body({ app: "kidguard" })).app, "kidguard");
  assert.equal(validateCrash(body({ app: "nope" })), null);
  const { validate } = await import("../src/logic.js");
  assert.equal(validate(JSON.stringify({ id: "0f8fad5b-d9cb-469f-a165-70867728950e", app: "kidguard" })), null);
});
