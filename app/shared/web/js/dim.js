// 🔅 A tint over the whole TV screen (Dim.kt, DimLayer on the TV): a colour and its strength, sent as they are picked
const DIM_COLORS = { dark: '#000000', amber: '#ff8a1e', yellow: '#ffc800', sepia: '#704214', red: '#9a0000', blue: '#10307a' };   // Dim.COLORS
const dimWords = () => ({ dark: T('dim.dark'), amber: T('dim.amber'), yellow: T('dim.yellow'), sepia: T('dim.sepia'), red: T('dim.red'), blue: T('dim.blue') });
let dimSending = 0;

async function dimOpen() {
  if (await needAdbSoon() || !await setupFor('dim')) return;
  const now = await api('/api/dim'); if (!now) return;                  // not quiet: a TV that does not answer is said, the dialog does not just stay shut
  let s = { on: now.on, color: now.color, pct: now.pct };
  let failed = false;                                              // a drag sends many times: one message per run of failures
  const send = () => { clearTimeout(dimSending); dimSending = setTimeout(async () => { failed = !await api('/api/dim', s, failed); }, 120); };   // a slider moved: its last place
  const chips = el('div', { className: 'fchips dimc' });
  const out = el('output');
  const r = el('input', { type: 'range', min: 5, max: 90, step: 5, value: s.pct, ariaLabel: T('dim.strength'), oninput: (e) => set({ pct: +e.target.value, on: true }) });
  const off = el('button', { textContent: '💤 ' + T('dim.off'), onclick: () => set({ on: false }) });
  function draw() {
    const words = dimWords();
    chips.replaceChildren(...Object.keys(DIM_COLORS).map((c) => {
      const dot = el('i'); dot.style.setProperty('--c', DIM_COLORS[c]);
      return el('button', { type: 'button', className: s.on && s.color === c ? 'on' : '', onclick: () => set({ color: c, on: true }) }, dot, el('span', { textContent: words[c] }));
    }));
    out.textContent = s.pct + '%'; r.value = s.pct; off.disabled = !s.on;
  }
  function set(change) { s = { ...s, ...change }; draw(); send(); }
  const d = el('dialog', { className: 'ask menu' }, el('div', { className: 'card' },
    el('div', { className: 'card-head' }, el('h2', { textContent: T('dim.title') }), el('button', { className: 'small iconbtn', textContent: '✕', title: T('common.close'), 'aria-label': T('common.close'), onclick: () => d.close() })),
    chips,
    el('div', { className: 'vol boost' }, r, out),
    el('p', { className: 'note', textContent: T('dim.note') }),
    el('div', { className: 'actions' }, off)));
  d.addEventListener('close', () => d.remove());
  draw();
  document.body.append(d); d.showModal();
}
