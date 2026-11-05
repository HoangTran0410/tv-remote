#!/usr/bin/env node
// A TCP proxy that makes a link slow: a one-way delay on each direction and an optional bandwidth cap, to test what 5G or
// Tailscale does to ADB, the picture and file pushes without leaving the desk. No dependencies.
//
//   node dev/test/slowlink.js --to 127.0.0.1:5615 [--listen 5700] [--delay 25] [--rate 0]
//
//   --to host:port   where the traffic goes (the TV emulator's ADB port, console port + 1)
//   --listen port    where clients connect (default 5700, on 127.0.0.1)
//   --delay ms       one way, each direction (default 25: 50 ms round trip; 5G over Tailscale measured 25-53 ms ping)
//   --rate KB/s      cap per direction, 0 = none (default 0); 2500 is about 20 Mbit/s
//
// Bytes keep their order and get out `delay` ms after they went in (a queue per direction, not a timer per chunk).
const net = require('net');
const a = process.argv.slice(2);
const opt = (k, d) => { const i = a.indexOf('--' + k); return i < 0 ? d : a[i + 1]; };
const to = opt('to', null);
if (!to) { console.error('usage: node dev/test/slowlink.js --to host:port [--listen 5700] [--delay 25] [--rate 0]'); process.exit(2); }
const [host, port] = [to.slice(0, to.lastIndexOf(':')), +to.slice(to.lastIndexOf(':') + 1)];
const delay = +opt('delay', 25), rate = +opt('rate', 0) * 1024;     // bytes per second

// One direction: chunks wait in `q` until their time (arrival + delay, and not before the bandwidth cap lets them out).
function pipe(from, dest, label) {
  const q = []; let timer = null, free = 0, ended = false, bytes = 0;
  const pump = () => {
    timer = null;
    const now = Date.now();
    while (q.length && q[0].at <= now) {
      const c = q.shift(); bytes += c.buf.length;
      if (!dest.destroyed) dest.write(c.buf);
    }
    if (q.length) timer = setTimeout(pump, Math.max(1, q[0].at - Date.now()));
    else if (ended && !dest.destroyed) dest.end();
  };
  from.on('data', buf => {
    const now = Date.now();
    let at = now + delay;
    if (rate) { free = Math.max(free, now) + buf.length / rate * 1000; at = Math.max(at, free + delay); }
    q.push({ at, buf });
    if (!timer) timer = setTimeout(pump, Math.max(1, q[0].at - Date.now()));
  });
  from.on('end', () => { ended = true; if (!timer) pump(); });
  from.on('error', () => dest.destroy());
  return () => bytes;
}

net.createServer(c => {
  const s = net.connect(port, host);
  c.setNoDelay(true); s.setNoDelay(true);
  pipe(c, s, 'up'); pipe(s, c, 'down');
  c.on('close', () => s.destroy()); s.on('close', () => c.destroy());
  s.on('error', () => c.destroy());
}).listen(+opt('listen', 5700), '127.0.0.1', () =>
  console.log(`slowlink: 127.0.0.1:${opt('listen', 5700)} -> ${to}, ${delay} ms each way` + (rate ? `, ${rate / 1024} KB/s` : '')));
