// History for the Health tab, shared by the three remotes: how long the TV was used and by which apps, day by day, and how
// memory, CPU and storage moved over time. The TV Setup app on the TV records it (see Stats.kt); this only draws it.
//
//   renderStats(root, data, reload)
//     data    { usage: { "2026-10-13": { "pkg": seconds } }, samples: [[t, on, cpu, memUsed, memTotal, diskUsed, diskTotal, pkg,
//               wifiDbm, "wifi"|"eth"|"", wifiMbps], ...], net: [[t, 0 lost|1 back, kind, screen on], ...], labels: { pkg: name }, collector: bool, days }
//     reload  (days) => void, called when the range buttons are used
(() => {
  const css = `
  .st-range { display:flex; gap:8px; margin:0 0 12px; flex-wrap:wrap; } .st-range button { min-height:40px; padding:0 14px; border-radius:12px; flex:none; flex-direction:row; }
  .st-range button.on { background:var(--accent); color:var(--accent-ink, #fff); }
  .st-sum { display:flex; gap:18px; flex-wrap:wrap; margin:0 0 12px; } .st-sum b { display:block; font-size:20px; } .st-sum span { color:var(--muted); font-size:12px; }
  .st-days { display:flex; align-items:flex-end; gap:4px; height:120px; } .st-day { flex:1; min-width:0; display:flex; flex-direction:column; align-items:center; justify-content:flex-end; height:100%; cursor:pointer; }
  .st-day i { display:block; width:100%; max-width:34px; min-height:2px; border-radius:6px 6px 2px 2px; background:var(--accent); opacity:.55; } .st-day.on i { opacity:1; }
  .st-day small { font-size:10px; color:var(--muted); margin-top:4px; white-space:nowrap; } .st-day.on small { color:var(--ink); font-weight:700; }
  .st-app { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:2px 10px; padding:7px 0; border-top:1px solid var(--line); font-size:14px; } .st-app:first-child { border-top:0; }
  .st-app span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; } .st-app b { font-variant-numeric:tabular-nums; } .st-app i { grid-column:1 / -1; height:6px; border-radius:3px; background:var(--accent); opacity:.7; }
  .st-chart { position:relative; margin-top:6px; } .st-chart svg { display:block; width:100%; height:150px; touch-action:pan-y; } .st-leg { display:flex; gap:6px 14px; flex-wrap:wrap; font-size:12px; color:var(--muted); margin:10px 0 0; } .st-leg + .st-leg { margin-top:6px; }
  .st-key { all:unset; cursor:pointer; border-radius:4px; transition:opacity .12s; } .st-key:focus-visible { outline:2px solid var(--accent); outline-offset:2px; } .st-key.off { opacity:.4; text-decoration:line-through; }
  .st-leg i { display:inline-block; width:10px; height:10px; border-radius:3px; margin-right:5px; vertical-align:-1px; } .st-tip { font-size:13px; min-height:54px; margin-top:12px; padding-top:10px; border-top:1px solid var(--line); overflow-x:auto; } .st-tip table { border-collapse:collapse; white-space:nowrap; }
  .st-tip th { font-size:11px; font-weight:600; color:var(--muted); text-align:left; padding:0 10px 2px 0; } .st-tip td { padding:0 10px 0 0; white-space:pre; vertical-align:top; font-variant-numeric:tabular-nums; } .st-axis { display:flex; justify-content:space-between; font-size:11px; color:var(--muted); }
  .st-strip { display:block; width:100%; height:14px; margin-top:4px; border-radius:4px; } .st-strip rect { transition:opacity .12s; } .st-chart svg, .st-strip { cursor:crosshair; }
  .st-note { color:var(--muted); font-size:13px; margin:0; } .st-leg ~ .st-note { margin-top:10px; } .st-range ~ section.card { margin-top:12px; }`;
  const RAM = '#f59e0b', CPU = '#5b8cff', DISK = '#22c55e', WIFI = '#14b8a6', CUT = '#ef4444';
  const APPS = ['#e879f9', '#38bdf8', '#fb7185', '#a3e635', '#fde047', '#c084fc', '#fb923c', '#2dd4bf'];   // the apps in front, most used first
  const el = (tag, props = {}, ...kids) => { const e = Object.assign(document.createElement(tag), props); kids.forEach((k) => k != null && e.append(k)); return e; };
  const pad = (n) => String(n).padStart(2, '0');
  const dayKey = (d) => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());

  // every time the network went away while the TV was in use; the ones that began while it slept are its own power saving, not a fault.
  // A "lost" with no "back" after it is closed by the samples (the first one with a link, else the last one), never stretched to the
  // chart's end: a "back" the log lost would otherwise paint the whole chart red. null as the end = still down now.
  const statsCuts = (net, samples, from, to) => {
    const cuts = [], rows = samples || [];
    const endOf = (t) => {
      const back = rows.find((r) => r[0] > t && (r[9] === 'wifi' || r[9] === 'eth'));
      if (back) return back[0];
      const last = rows.length ? rows[rows.length - 1][0] : t;
      return to - last < 900 && last >= t ? null : Math.max(last, t + 60);   // fresh samples = down right now; else it ended when they stopped
    };
    let open = null;
    (net || []).forEach((e) => {
      if (!e[1]) { if (!open) open = e; return; }
      if (open) { if (open[3] && e[0] >= from && open[0] <= to) cuts.push([open[0], e[0]]); open = null; }
    });
    if (open && open[3] && open[0] <= to) { const b = endOf(open[0]); if (b === null || b >= from) cuts.push([open[0], b]); }
    return cuts;
  };
  window.statsCuts = statsCuts;
  window.renderStats = (root, data, reload) => {
    if (!document.getElementById('st-css')) document.head.append(el('style', { id: 'st-css', textContent: css }));
    const dur = (s) => { const m = Math.round(s / 60); return m < 60 ? T('health.minutes_n', { n: m }) : T('stats.dur_hm', { h: Math.floor(m / 60), m: pad(m % 60) }); };
    const name = (p) => (data.labels && data.labels[p]) || p.split('.').pop();
    const days = [], n = data.days || 7, today = new Date();
    for (let i = n - 1; i >= 0; i--) { const d = new Date(today); d.setDate(d.getDate() - i); days.push(dayKey(d)); }
    const total = (k) => Object.values((data.usage || {})[k] || {}).reduce((a, b) => a + b, 0);
    let picked = null;                                              // a day, or null for the whole range

    const range = el('div', { className: 'st-range' }, ...[7, 30].map((d) => el('button', { type: 'button', className: d === n ? 'on' : '', textContent: T('stats.days_n', { n: d }), onclick: () => reload(d) })));
    const sum = el('div', { className: 'st-sum' }), bars = el('div', { className: 'st-days' }), apps = el('div'), appsTitle = el('h2'), chartBox = el('div'), chartTitle = el('h2');
    let off = new Set();                                            // the lines turned off in the legend, kept for the next visit
    try { off = new Set(JSON.parse(localStorage.getItem('st-off') || '[]')); } catch {}
    root.replaceChildren(range);
    if (!data.collector) {
      root.append(el('section', { className: 'card' }, el('p', { className: 'st-note', textContent: T('stats.no_data') })));
      return;
    }
    root.append(el('section', { className: 'card' }, el('h2', { textContent: T('stats.tv_time') }), sum, bars),
      el('section', { className: 'card' }, appsTitle, apps), el('section', { className: 'card' }, chartTitle, chartBox));
    const hm = (t) => { const d = new Date(t * 1000); return pad(d.getHours()) + ':' + pad(d.getMinutes()); };
    const level = (dbm) => dbm >= -55 ? T('health.signal_excellent') : dbm >= -67 ? T('health.signal_good') : dbm >= -75 ? T('health.signal_fair') : T('health.signal_weak');

    // The cursor on a chart: follows the mouse (or a finger), puts a line on the nearest sample and says what it was.
    const scrub = (box, rows, x, say) => {
      const svg = box.querySelector('svg'), cur = svg.querySelector('.st-cur');
      const show = (e) => {
        const b = svg.getBoundingClientRect(), at = (e.clientX - b.left) * 600 / b.width;   // in the viewBox's units (600 wide)
        const r = rows.reduce((a, c) => Math.abs(x(c[0]) - at) < Math.abs(x(a[0]) - at) ? c : a);
        cur.setAttribute('x1', x(r[0])); cur.setAttribute('x2', x(r[0])); cur.setAttribute('visibility', 'visible');
        say(r);
      };
      svg.addEventListener('pointerdown', show); svg.addEventListener('pointermove', show);
    };
    const cells = (box, pairs) => {                                 // what the cursor is on: one row of headings over one row of figures
      pairs = pairs.filter(Boolean);
      box.replaceChildren(el('table', {}, el('tr', {}, ...pairs.map(([h]) => el('th', { textContent: h }))), el('tr', {}, ...pairs.map(([, v]) => el('td', { textContent: v })))));
    };
    const curLine = (H) => '<line class="st-cur" y1="0" y2="' + H + '" stroke="currentColor" stroke-opacity=".5" visibility="hidden" vector-effect="non-scaling-stroke"/>';

    // Which app was in front, and when the TV slept: runs of the same state, under a chart as a coloured strip with a faint line
    // at each switch and the sleep greyed; the cursor lights the run it is in. Shared by the RAM/CPU and the Wi-Fi charts.
    const appTrack = (rows, x, W, H) => {
      const runs = [];
      rows.forEach((r, i) => {
        const p = r[1] ? r[7] || '' : null, next = rows[i + 1], end = next && next[0] - r[0] <= 900 ? next[0] : r[0] + 300;   // null: asleep
        const last = runs[runs.length - 1];
        if (last && last.p === p && r[0] - last.b <= 900) last.b = end; else runs.push({ p, a: r[0], b: end });
      });
      const time = {};
      runs.forEach((u) => { if (u.p) time[u.p] = (time[u.p] || 0) + u.b - u.a; });
      const ranked = Object.keys(time).sort((a, b) => time[b] - time[a]);
      const color = (p) => ranked.indexOf(p) < APPS.length ? APPS[ranked.indexOf(p)] : '#64748b';
      const wide = (u) => Math.max(1, x(u.b) - x(u.a)).toFixed(1);
      const asleep = runs.filter((u) => u.p === null);
      const under = asleep.map((u) => '<rect x="' + x(u.a).toFixed(1) + '" width="' + wide(u) + '" y="0" height="' + H + '" fill="currentColor" fill-opacity=".05"/>').join('')
        + '<rect class="st-run" y="0" height="' + H + '" fill="currentColor" fill-opacity=".08" visibility="hidden"/>'
        + runs.slice(1).filter((u, i) => u.p && runs[i].p && u.a - runs[i].b < 900)
          .map((u) => '<line x1="' + x(u.a).toFixed(1) + '" x2="' + x(u.a).toFixed(1) + '" y1="0" y2="' + H + '" stroke="currentColor" stroke-opacity=".22" stroke-dasharray="3 3" vector-effect="non-scaling-stroke"/>').join('');
      const strip = (chart) => {                                    // pointing at the strip is pointing at the chart above it
        const s = el('div', { innerHTML: '<svg class="st-strip" viewBox="0 0 ' + W + ' 14" preserveAspectRatio="none">' + runs.map((u, i) => u.p !== ''
          ? '<rect data-i="' + i + '" x="' + x(u.a).toFixed(1) + '" width="' + wide(u) + '" y="' + (u.p ? 0 : 5) + '" height="' + (u.p ? 14 : 4) + '" fill="' + (u.p ? color(u.p) : 'currentColor') + '"' + (u.p ? '' : ' fill-opacity=".25"')
            + '><title>' + (u.p ? name(u.p) : T('stats.tv_asleep')) + ' · ' + hm(u.a) + '–' + hm(u.b) + '</title></rect>' : '').join('') + '</svg>' }).firstChild;
        ['pointermove', 'pointerdown'].forEach((k) => s.addEventListener(k, (e) => chart.querySelector('svg').dispatchEvent(new PointerEvent(k, { clientX: e.clientX, clientY: e.clientY }))));
        return s;
      };
      const mark = (chart, s) => (r) => {                           // the run at that moment lit, the others dimmed; says its span
        const i = runs.findIndex((u) => r[0] >= u.a && r[0] < u.b), u = runs[i], band = chart.querySelector('.st-run'), lit = u && u.p !== '';
        s.querySelectorAll('rect').forEach((q) => q.setAttribute('opacity', lit && +q.dataset.i !== i ? '.3' : '1'));
        if (lit) { band.setAttribute('x', x(u.a)); band.setAttribute('width', wide(u)); band.setAttribute('visibility', 'visible'); } else band.setAttribute('visibility', 'hidden');
        return lit ? '\n' + hm(u.a) + '–' + hm(u.b) : '';   // its own line in the table under the chart
      };
      const legend = ranked.length || asleep.length ? el('div', { className: 'st-leg' }, ...ranked.slice(0, 5).map((p) => el('span', { innerHTML: '<i style="background:' + color(p) + '"></i>' }, name(p))),
        asleep.length ? el('span', { innerHTML: '<i style="background:currentColor;opacity:.25"></i>' }, T('stats.tv_asleep')) : null) : null;
      return { under, strip, mark, legend };
    };

    const mins = (s) => s < 60 ? T('stats.seconds_n', { n: s }) : T('health.minutes_n', { n: Math.round(s / 60) });

    const draw = () => {
      const all = days.map(total), max = Math.max(1, ...all), used = all.filter((s) => s > 60).length;
      const whole = all.reduce((a, b) => a + b, 0);
      sum.replaceChildren(el('div', {}, el('b', { textContent: dur(picked ? total(picked) : whole) }), el('span', { textContent: picked ? picked.slice(8) + '/' + picked.slice(5, 7) : T('stats.days_n', { n }) })),
        el('div', {}, el('b', { textContent: dur(used ? whole / used : 0) }), el('span', { textContent: T('stats.avg_per_day') })));
      bars.replaceChildren(...days.map((k, i) => el('div', { className: 'st-day' + (k === picked ? ' on' : ''), title: k + ' · ' + dur(all[i]), onclick: () => { picked = picked === k ? null : k; draw(); } },
        el('i', { style: 'height:' + Math.round(all[i] * 100 / max) + '%' }), el('small', { textContent: n > 10 && i % 5 ? '' : k.slice(8) + '/' + k.slice(5, 7) }))));

      const per = {};
      (picked ? [picked] : days).forEach((k) => Object.entries((data.usage || {})[k] || {}).forEach(([p, s]) => { per[p] = (per[p] || 0) + s; }));
      const list = Object.entries(per).filter(([, s]) => s >= 60).sort((a, b) => b[1] - a[1]).slice(0, 12), top = list.length ? list[0][1] : 1;
      appsTitle.textContent = T('stats.most_used') + ' · ' + (picked ? picked.slice(8) + '/' + picked.slice(5, 7) : T('stats.days_n', { n }));
      apps.replaceChildren(...(list.length ? list.map(([p, s]) => el('div', { className: 'st-app' }, el('span', { textContent: name(p) }), el('b', { textContent: dur(s) }), el('i', { style: 'width:' + Math.max(2, Math.round(s * 100 / top)) + '%' })))
        : [el('p', { className: 'st-note', textContent: T('stats.no_apps') })]));

      // memory, CPU and storage over the picked day, or the last 24 hours
      let from, to;
      if (picked) { const d = new Date(picked + 'T00:00:00'); from = d.getTime() / 1000; to = from + 86400; } else { to = Date.now() / 1000; from = to - 86400; }
      const rows = (data.samples || []).filter((r) => r[0] >= from && r[0] <= to);
      chartTitle.textContent = T('stats.chart_title', { when: picked ? picked.slice(8) + '/' + picked.slice(5, 7) : T('stats.last_24h') });
      if (rows.length < 2) { chartBox.replaceChildren(el('p', { className: 'st-note', textContent: T('stats.not_enough') })); return; }
      const W = 600, H = 150, x = (t) => (t - from) * W / (to - from), y = (pct) => H - 4 - pct * (H - 8) / 100;
      const line = (key, pick, color) => {                                // broken where the TV was off or asleep for a while
        let d = '', last = 0;
        rows.forEach((r) => { const v = pick(r); if (v == null) return; d += (r[0] - last > 900 ? 'M' : 'L') + x(r[0]).toFixed(1) + ' ' + y(v).toFixed(1); last = r[0]; });
        return '<path data-s="' + key + '" d="' + d + '" fill="none" stroke="' + color + '" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"' + (off.has(key) ? ' display="none"' : '') + '/>';
      };
      const grid = [0, 25, 50, 75, 100].map((p) => '<line x1="0" x2="' + W + '" y1="' + y(p) + '" y2="' + y(p) + '" stroke="currentColor" stroke-opacity=".12"/>').join('');
      const track = appTrack(rows, x, W, H), cuts = statsCuts(data.net, data.samples, from, to);
      const svg = el('div', { className: 'st-chart', innerHTML: '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="none">' + grid + track.under
        + '<g data-s="cut"' + (off.has('cut') ? ' display="none"' : '') + '>' + cuts.map(([a, b]) => { const x0 = x(Math.max(a, from)), x1 = x(Math.min(b || to, to));
          return '<rect x="' + x0.toFixed(1) + '" width="' + Math.max(2, x1 - x0).toFixed(1) + '" y="0" height="' + H + '" fill="' + CUT + '" fill-opacity=".22"><title>' + (b ? T('stats.cut_span', { from: hm(a), to: hm(b), len: mins(b - a) }) : T('stats.cut_open', { from: hm(a) })) + '</title></rect>'; }).join('') + '</g>'
        + line('wifi', (r) => r[9] === 'wifi' && r[8] != null ? Math.max(0, Math.min(100, (r[8] + 100) * 2)) : null, WIFI) + line('disk', (r) => r[6] ? r[5] * 100 / r[6] : null, DISK)
        + line('ram', (r) => r[4] ? r[3] * 100 / r[4] : null, RAM) + line('cpu', (r) => r[1] ? r[2] : null, CPU)
        + curLine(H) + '</svg>' });
      const strip = track.strip(svg), mark = track.mark(svg, strip);
      const tip = el('div', { className: 'st-tip' });
      const cutAt = (t) => { const c = cuts.find(([a, b]) => t >= a - 150 && t <= (b || to) + 150); return c ? '\n' + hm(c[0]) + (c[1] ? '–' + hm(c[1]) : '') : ''; };   // within half a sample
      const say = (r, now) => {                                     // now: the latest figures, until the chart is touched
        const span = now ? '' : mark(r);
        cells(tip, [[T('stats.time'), hm(r[0])], ['RAM', (r[3] / 1024).toFixed(1) + ' GB\n/ ' + (r[4] / 1024).toFixed(1) + ' GB'], ['CPU', r[1] && r[2] != null ? r[2] + '%' : '–'],
          [T('stats.storage'), (r[5] / 1024).toFixed(1) + ' GB'], ['TV', r[1] ? (r[7] ? name(r[7]) + span : T('stats.on')) : T('stats.asleep') + span],
          cutAt(r[0]) ? [T('stats.network'), T('stats.no_network') + cutAt(r[0])] : r[9] === 'wifi' && r[8] != null ? ['Wi-Fi', r[8] + ' dBm\n' + level(r[8]) + (r[10] ? ' · ' + r[10] + ' Mbps' : '')] : r[9] === 'eth' ? [T('stats.network'), T('stats.cable')] : r.length > 9 && r[9] === '' ? [T('stats.network'), T('stats.no_network')] : null]);
      };
      scrub(svg, rows, x, (r) => say(r));
      say(rows[rows.length - 1], true);
      const ram = rows.map((r) => r[3]), cpu = rows.filter((r) => r[1] && r[2] != null).map((r) => r[2]);
      const wifi = rows.filter((r) => r[9] === 'wifi' && r[8] != null), dbm = wifi.map((r) => r[8]), mbps = wifi.map((r) => r[10]).filter((v) => v);
      const avg = dbm.length ? Math.round(dbm.reduce((a, b) => a + b, 0) / dbm.length) : 0;
      const key = (k, color, text, more) => el('button', { type: 'button', className: 'st-key' + (off.has(k) ? ' off' : ''), title: (more ? more + '\n' : '') + T('stats.toggle_tip'),
        innerHTML: '<i style="background:' + color + '"></i>', onclick: (e) => {          // a line on or off, the others kept as they are
          if (off.has(k)) off.delete(k); else off.add(k);
          e.currentTarget.classList.toggle('off', off.has(k));
          svg.querySelectorAll('[data-s="' + k + '"]').forEach((q) => q.setAttribute('display', off.has(k) ? 'none' : 'inline'));
          try { localStorage.setItem('st-off', JSON.stringify([...off])); } catch {}
        } }, text);
      const notes = [];
      if (rows.some((r) => r.length > 9) && rows.filter((r) => r.length > 9).every((r) => r[9] === 'eth')) notes.push(el('p', { className: 'st-note', textContent: T('stats.eth_note') }));
      if (dbm.length && avg < -70) notes.push(el('p', { className: 'st-note', textContent: T('stats.weak_note') }));
      chartBox.replaceChildren(svg, strip, el('div', { className: 'st-axis' }, el('span', { textContent: hm(from) }), el('span', { textContent: hm((from + to) / 2) }), el('span', { textContent: hm(to) })),
        el('div', { className: 'st-leg' }, key('ram', RAM, 'RAM ' + (Math.min(...ram) / 1024).toFixed(1) + '–' + (Math.max(...ram) / 1024).toFixed(1) + ' GB'),
          key('cpu', CPU, 'CPU ' + (cpu.length ? Math.min(...cpu) + '–' + Math.max(...cpu) + '%' : '–')),
          key('disk', DISK, T('stats.storage_of', { used: (rows[rows.length - 1][5] / 1024).toFixed(1), total: (rows[rows.length - 1][6] / 1024).toFixed(1) })),
          dbm.length ? key('wifi', WIFI, 'Wi-Fi ' + avg + ' dBm · ' + level(avg), [T('stats.wifi_weakest', { min: Math.min(...dbm) }), mbps.length ? T('stats.wifi_link', { lo: Math.min(...mbps), hi: Math.max(...mbps) }) : ''].filter(Boolean).join(' · ')
            + '\n' + T('stats.wifi_scale')) : null,
          cuts.length ? key('cut', CUT, T('stats.cuts_n', { n: cuts.length }), T('stats.while_in_use')) : null),
        track.legend, ...notes, tip);
    };
    draw();
  };
})();
