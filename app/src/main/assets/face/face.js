'use strict';
// ═══════════════════════════════════════
//  THE CAR STEREO'S FRONT on the phone: the photo of the DP940 with its keys (as on Deck 90 for the PC, its
//  hotspots in % of the face), its display drawn by the same component (dp940-display.js), fed by the app
//  (Deck90Android.state(), levels()); the keys go to the app (Deck90Android.key()). Held 1 s: the memories are 7–12,
//  BAND is A.MEM; VOL repeats while held. In a browser (no app) it shows itself with a demo state.
// ═══════════════════════════════════════
const ST_KEYS = [   // [key, left %, top %, width %, height %, round]: Deck 90's src/remote/stereo.js
  ['volup', 2.399, 27.458, 5.197, 12.987], ['voldown', 2.399, 64.007, 5.197, 13.544],
  ['sel', 3.598, 46.011, 7.996, 16.141, 1], ['mode', 10.195, 27.829, 6.397, 15.213, 1],
  ['disp', 14.193, 45.269, 5.897, 15.213, 1], ['band', 10.195, 63.636, 6.397, 15.028, 1],
  ['hdb', 20.090, 31.911, 4.698, 16.327, 1], ['sdm', 20.090, 57.143, 4.798, 16.698, 1],
  ['att', 9.595, 80.891, 6.997, 12.987], ['scan', 17.191, 80.891, 13.493, 12.987],
  ['prev', 31.784, 80.891, 6.697, 12.987], ['next', 38.481, 80.891, 6.697, 12.987],
  ['m1', 46.377, 80.891, 5.997, 12.987], ['m2', 52.374, 80.891, 6.997, 12.987],
  ['m3', 59.970, 80.891, 6.997, 12.987], ['m4', 66.967, 80.891, 6.897, 12.987],
  ['m5', 74.663, 80.891, 6.597, 12.987], ['m6', 81.259, 80.891, 8.996, 12.987],
  ['slot', 17.091, 15.213, 73.463, 10.019], ['eject', 91.854, 24.490, 4.998, 15.955, 1],
  ['view', 28.886, 42.857, 53.373, 27.458],                         // the display itself: the VU, then the spectrum
];
const el = id => document.getElementById(id);
const app = window.Deck90Android || {                              // a browser: a stand-in, for a look at the face
  state: () => JSON.stringify({ band: 'FM1', slot: 3, freq: '95.1', name: 'Metro · Un tema de prueba', station: 'Metro', song: 'Un tema de prueba', playing: true, quality: 'AAC 128',
    memories: ['Aspen', '80s80s', 'Metro', 'Rock & Pop', 'La 100', 'Vorterix', '', '', '', '', '', ''] }),
  levels: () => { const t = Date.now() / 300, l = 0.5 + 0.4 * Math.sin(t), r = 0.5 + 0.4 * Math.cos(t * 1.3); return [l, r, l * .35, r * .35].join(','); },
  spectrum: () => { const t = Date.now() / 400; return Array.from({ length: 28 }, (_, k) => (-28 - k * 0.9 + 8 * Math.sin(t + k * .7)).toFixed(1)).join(','); },
  key: k => toast('tecla ' + k),
};
const face = el('stFace');
let disp = null, view = {};

function toast(text, ms = 1800) {
  const t = el('toast'); t.textContent = text; t.classList.add('show');
  clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), ms);
}
window.faceToast = toast;

(function build() {
  for (const [k, l, t, w, h, round] of ST_KEYS) {
    const b = document.createElement('button');
    b.className = 'st-hot' + (round ? ' round' : ''); b.dataset.st = k;
    b.style.cssText = 'left:' + l + '%;top:' + t + '%;width:' + w + '%;height:' + h + '%';
    b.setAttribute('aria-label', k);
    face.append(b);
  }
  if (typeof Deck90Display === 'function') {
    disp = new Deck90Display(el('stDisplay'), { attackMs: 35, releaseMs: 140 });
    const svg = disp.svg;                                         // only its glass, over the photo's own LCD
    svg.setAttribute('viewBox', '23 24 1141 156'); svg.setAttribute('preserveAspectRatio', 'none');
    svg.removeAttribute('width'); svg.removeAttribute('height');
  }
})();

// the display's colour: SDM steps through hues (kept on this phone), HDB through three brightnesses
const look = { hue: 0, dim: 1 };
try { Object.assign(look, JSON.parse(localStorage.getItem('deck90-face') || '{}')); } catch (_) {}
function lookApply() {
  face.style.setProperty('--st-hue', look.hue + 'deg'); face.style.setProperty('--st-dim', look.dim);
  try { localStorage.setItem('deck90-face', JSON.stringify(look)); } catch (_) {}
}
lookApply();

function key(k, hold) {
  if (k === 'view') { viewNext(); return; }
  if (k === 'sdm') { look.hue = (look.hue + (hold ? 300 : 60)) % 360; lookApply(); return; }
  if (k === 'hdb') { look.dim = look.dim >= 1 ? .55 : look.dim >= .55 ? .8 : 1; lookApply(); return; }
  app.key(k, !!hold);
  setTimeout(read, 60);
}
(function keys() {
  let holdT = 0, held = false, volT = 0;
  face.addEventListener('pointerdown', e => {
    const b = e.target.closest('.st-hot'); if (!b) return;
    e.preventDefault(); b.classList.add('down'); held = false;
    const k = b.dataset.st;
    if (/^vol(up|down)$/.test(k)) {
      held = true; key(k);
      volT = setTimeout(function rep() { key(k); volT = setTimeout(rep, 180); }, 450);
      return;
    }
    if (/^(m[1-6]|sdm|band)$/.test(k)) holdT = setTimeout(() => { held = true; key(k, true); }, 1000);
  });
  const up = () => { clearTimeout(holdT); clearTimeout(volT); face.querySelectorAll('.st-hot.down').forEach(b => b.classList.remove('down')); };
  ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => face.addEventListener(ev, up));
  face.addEventListener('click', e => {
    const b = e.target.closest('.st-hot'); if (!b) return;
    if (held) { held = false; return; }
    key(b.dataset.st);
  });
  face.addEventListener('contextmenu', e => e.preventDefault());
})();

// standing up: the bands and the memories as big keys. A memory held 1 s keeps the station playing there (as
// A.MEM and a number on the face); a band touched shows its memories and plays its first
const BANDS = ['FM1', 'FM2', 'FM3', 'FM4', 'FM5', 'HI-FI', 'AM'];
(function pad() {
  el('padBands').append(...BANDS.map(b => { const x = document.createElement('button'); x.textContent = b; x.dataset.band = b; return x; }));
  el('padMems').append(...Array.from({ length: 12 }, (_, i) => { const x = document.createElement('button'); x.dataset.m = i + 1; x.innerHTML = '<b>' + (i + 1) + '</b><small>—</small>'; return x; }));
  let holdT = 0, held = false;
  const pad = el('pad');
  pad.addEventListener('pointerdown', e => {
    const m = e.target.closest('[data-m]'); held = false;
    if (m) holdT = setTimeout(() => { held = true; app.key('keep' + m.dataset.m, true); setTimeout(read, 80); }, 1000);
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => pad.addEventListener(ev, () => clearTimeout(holdT)));
  pad.addEventListener('click', e => {
    if (held) { held = false; return; }
    const b = e.target.closest('button'); if (!b) return;
    if (b.dataset.v) { viewSet(b.dataset.v); return; }
    if (b.dataset.band) app.key('band:' + b.dataset.band.replace('-', ''), false);
    else if (b.dataset.m) app.key('mem' + b.dataset.m, false);
    else if (b.dataset.k) app.key(b.dataset.k, false);
    setTimeout(read, 80);
  });
  pad.addEventListener('contextmenu', e => e.preventDefault());
})();
function padSync(s) {
  el('padBands').querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.band === s.band));
  const names = s.memories || [];
  el('padMems').querySelectorAll('button').forEach((b, i) => {
    const n = names[i] || '';
    b.querySelector('small').textContent = n || '—'; b.classList.toggle('empty', !n); b.classList.toggle('on', i + 1 === s.slot);
  });
}

// the app's state on the display and under it
function read() {
  let s;
  try { s = JSON.parse(app.state()); } catch (_) { return; }
  const key = JSON.stringify(s);
  if (key === read.last) return;
  read.last = key; view = s;
  if (disp) {
    disp.setPlayback(false);
    disp.setRadio({ band: s.memo ? 'A.MEM' : s.band, frequency: s.freq || '', preset: s.slot || 1, live: !!s.playing, name: s.name || '', mono: !!s.mono, quality: s.quality || '' });
  }
  padSync(s);
  el('nowBand').textContent = (s.memo ? 'A.MEM · ' : '') + s.band + ' · ' + s.slot + (s.freq ? ' · ' + s.freq : '') + (s.scan ? ' · SCAN' : '');
  el('nowName').textContent = s.station || 'DECK 90';
  el('nowSong').textContent = s.song || '';
  const q = el('nowQ'); q.textContent = (/FLAC/.test(s.quality || '') ? 'HI-FI · ' : '') + (s.quality || ''); q.classList.toggle('hifi', /FLAC/.test(s.quality || ''));
}
setInterval(read, 250); read();

// ═══ the meters: the sound the player sends to the speakers (Levels.kt). The stereo's own around its loudness, as
// the PC's stereo does; VU (RMS with a needle's swing, 0 VU a little over the station's own level, PEAK near the
// top of the scale) and ESPECTRO (28 bands, 40 Hz – 16 kHz, +3 dB per octave so music sits level, peaks held a moment)
const VIEWS = ['radio', 'vu', 'spec'], VIEW_NAMES = { radio: 'RADIO', vu: 'VU', spec: 'ESPECTRO' };
const cv = el('mCanvas'), cx = cv.getContext('2d');
let mode = 'radio';
function viewSet(v, quiet) {
  mode = VIEWS.includes(v) ? v : 'radio'; look.view = mode; lookApply();
  document.body.dataset.view = mode;
  el('padViews').querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.v === mode));
  if (!quiet && matchMedia('(min-aspect-ratio: 3/2)').matches)
    toast(mode === 'radio' ? 'RADIO' : VIEW_NAMES[mode] + ' · tocá la pantalla para ' + (mode === 'vu' ? 'el espectro' : 'volver a la radio'));
}
function viewNext() { viewSet(VIEWS[(VIEWS.indexOf(mode) + 1) % VIEWS.length]); }
cv.addEventListener('click', viewNext);
viewSet(look.view, true);

const db = x => x > 0 ? 20 * Math.log10(x) : -90;
let avg = -24, at = 0;
const vu = { l: -20, r: -20, ref: -22, peakL: 0, peakR: 0 };
const SB = 28, sp = { bar: new Float32Array(SB), peak: new Float32Array(SB), hold: new Float32Array(SB), top: -24 };
const fc = k => 40 * Math.pow(400, (k + .5) / SB);                 // a band's middle: 40 Hz × 400^…, up to 16 kHz
(function frame(now) {
  requestAnimationFrame(frame);
  const dt = at ? Math.min(200, now - at) : 16; at = now;
  const on = !!view.playing;
  const [pl = 0, pr = 0, rl = 0, rr = 0] = on ? String(app.levels()).split(',').map(Number) : [];
  if (disp) {
    const dl = db(pl), dr = db(pr), loud = Math.max(dl, dr);
    if (loud > -60) avg += (loud - avg) * (1 - Math.exp(-dt / 2500));
    const f = d => d < -60 ? 0 : Math.max(0, Math.min(1, (d - (avg - 8)) / 13));
    disp.setLevels(f(dl), f(dr));
  }
  if (mode === 'radio') return;
  const d = devicePixelRatio || 1, w = Math.round(cv.clientWidth * d), h = Math.round(cv.clientHeight * d);
  if (!w || !h) return;
  if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
  cx.clearRect(0, 0, w, h);
  cv.style.filter = (mode === 'spec' && look.hue ? 'hue-rotate(' + look.hue + 'deg) ' : '') + (look.dim < 1 ? 'brightness(' + look.dim + ')' : '');
  if (mode === 'vu') vuFrame(rl, rr, pl, pr, dt, w, h);
  else specFrame(on ? String(app.spectrum ? app.spectrum() : '') : '', dt, w, h);
})(0);

// ── VU: two needles on lit amber faces, side by side when there's room, one over the other standing up
const VU_MARKS = [-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3];
const vuPos = v => Math.max(-0.03, Math.min(1.06, (Math.pow(10, v / 20) - 0.1) / (Math.pow(10, 3 / 20) - 0.1)));
function vuFrame(rl, rr, pl, pr, dt, W, H) {
  const dl = db(rl), dr = db(rr), loud = Math.max(dl, dr);
  if (loud > -60) vu.ref += (loud - vu.ref) * (1 - Math.exp(-dt / 4000));
  const k = 1 - Math.exp(-dt / 95);                                  // about the 300 ms a VU needle takes
  vu.l += (Math.max(-26, dl - vu.ref - 2) - vu.l) * k; vu.r += (Math.max(-26, dr - vu.ref - 2) - vu.r) * k;
  vu.peakL = pl >= .97 ? 350 : Math.max(0, vu.peakL - dt); vu.peakR = pr >= .97 ? 350 : Math.max(0, vu.peakR - dt);
  const side = W / H >= 1.9, g = Math.round(Math.min(W, H) * .04);
  if (side) { const mw = (W - g) / 2; vuMeter(0, 0, mw, H, vu.l, 'L', vu.peakL > 0); vuMeter(mw + g, 0, mw, H, vu.r, 'R', vu.peakR > 0); }
  else { const mh = (H - g) / 2; vuMeter(0, 0, W, mh, vu.l, 'L', vu.peakL > 0); vuMeter(0, mh + g, W, mh, vu.r, 'R', vu.peakR > 0); }
}
function vuMeter(x, y, w, h, val, ch, peak) {
  const fw = Math.min(w, h * 1.9), fh = Math.min(h, fw / 1.9);       // a face of 1.9:1, centred
  x += (w - fw) / 2; y += (h - fh) / 2; w = fw; h = fh;
  const r = h * .07;
  cx.save();
  rrect(x, y, w, h, r); cx.fillStyle = '#121212'; cx.fill();
  const ix = x + h * .045, iy = y + h * .045, iw = w - h * .09, ih = h - h * .09;
  rrect(ix, iy, iw, ih, r * .7); cx.save(); cx.clip();
  const bg = cx.createRadialGradient(ix + iw / 2, iy + ih * .95, ih * .1, ix + iw / 2, iy + ih * .7, iw * .75);
  bg.addColorStop(0, '#fff1c8'); bg.addColorStop(.6, '#f2cf86'); bg.addColorStop(1, '#c9953f');
  cx.fillStyle = bg; cx.fillRect(ix, iy, iw, ih);
  const px = ix + iw / 2, py = iy + ih * 1.18, R = ih * 1.02, A = 47 * Math.PI / 180;
  const ang = p => -A + 2 * A * p, at = (rad, p) => [px + rad * Math.sin(ang(p)), py - rad * Math.cos(ang(p))];
  cx.lineCap = 'butt';
  cx.beginPath(); cx.arc(px, py, R * .8, -Math.PI / 2 - A, -Math.PI / 2 + ang(vuPos(0))); cx.strokeStyle = '#1c1a16'; cx.lineWidth = ih * .012; cx.stroke();
  cx.beginPath(); cx.arc(px, py, R * .8 + ih * .02, -Math.PI / 2 + ang(vuPos(0)), -Math.PI / 2 + A); cx.strokeStyle = '#c3281e'; cx.lineWidth = ih * .05; cx.stroke();
  cx.font = '700 ' + Math.round(ih * .085) + 'px Arial, Helvetica, sans-serif'; cx.textAlign = 'center'; cx.textBaseline = 'middle';
  for (const m of VU_MARKS) {
    const p = vuPos(m), [ax, ay] = at(R * .8, p), [bx, by] = at(R * (m === 0 || m === -20 || m === 3 ? .9 : .87), p), [tx, ty] = at(R * .97, p);
    cx.beginPath(); cx.moveTo(ax, ay); cx.lineTo(bx, by); cx.strokeStyle = m > 0 ? '#c3281e' : '#1c1a16'; cx.lineWidth = ih * .014; cx.stroke();
    if (m !== -1 || iw > ih * 1.6) { cx.fillStyle = m > 0 ? '#c3281e' : '#1c1a16'; cx.fillText(m > 0 ? '+' + m : String(Math.abs(m)), tx, ty); }
  }
  cx.font = '700 ' + Math.round(ih * .16) + 'px Georgia, "Times New Roman", serif'; cx.fillStyle = '#1c1a16'; cx.fillText('VU', px, iy + ih * .6);
  cx.font = '700 ' + Math.round(ih * .07) + 'px Arial, Helvetica, sans-serif'; cx.textAlign = 'left'; cx.fillText(ch, ix + ih * .07, iy + ih * .1);
  cx.textAlign = 'right'; cx.fillStyle = '#5b4a2a'; cx.fillText('DECK 90', ix + iw - ih * .07, iy + ih * .1);
  const [nx, ny] = at(R * .93, vuPos(val));                          // the needle, from its pivot under the face
  cx.beginPath(); cx.moveTo(px, py); cx.lineTo(nx, ny); cx.strokeStyle = '#111'; cx.lineWidth = Math.max(1.5, ih * .012); cx.stroke();
  const plate = iy + ih * .8;                                        // the black plate hiding the pivot, and PEAK
  cx.fillStyle = '#16140f'; cx.fillRect(ix, plate, iw, ih - (plate - iy));
  const lx = ix + iw - ih * .2, ly = plate + (ih - (plate - iy)) / 2;
  cx.beginPath(); cx.arc(lx, ly, ih * .035, 0, 2 * Math.PI); cx.fillStyle = peak ? '#ff3b2e' : '#3a1210'; cx.fill();
  if (peak) { cx.shadowColor = '#ff3b2e'; cx.shadowBlur = ih * .08; cx.fill(); cx.shadowBlur = 0; }
  cx.font = '700 ' + Math.round(ih * .055) + 'px Arial, Helvetica, sans-serif'; cx.textAlign = 'right'; cx.fillStyle = '#8a8572'; cx.fillText('PEAK', lx - ih * .06, ly);
  cx.restore(); cx.restore();
}
function rrect(x, y, w, h, r) { cx.beginPath(); cx.roundRect ? cx.roundRect(x, y, w, h, r) : cx.rect(x, y, w, h); }

// ── ESPECTRO: segmented bars as on a 90s deck's display, green, then amber, then red at the top
const SPEC_LABELS = [[63, '63'], [125, '125'], [250, '250'], [500, '500'], [1000, '1k'], [2000, '2k'], [4000, '4k'], [8000, '8k'], [16000, '16k']];
function specFrame(str, dt, W, H) {
  const vals = str ? str.split(',').map(Number) : [];
  const tilt = vals.length === SB ? vals.map((v, k) => v + 3 * Math.log2(fc(k) / 1000)) : null;
  if (tilt) { const mx = Math.max(...tilt); if (mx > -80) sp.top += (Math.min(0, mx + 3) - sp.top) * (1 - Math.exp(-dt / 3000)); }
  const RANGE = 48, fall = dt / 1000 * 1.4;
  for (let k = 0; k < SB; k++) {
    const t = tilt ? Math.max(0, Math.min(1, (tilt[k] - (sp.top - RANGE)) / RANGE)) : 0;
    sp.bar[k] = t > sp.bar[k] ? t : Math.max(t, sp.bar[k] - fall);
    if (sp.bar[k] >= sp.peak[k]) { sp.peak[k] = sp.bar[k]; sp.hold[k] = 700; }
    else if ((sp.hold[k] -= dt) < 0) sp.peak[k] = Math.max(sp.bar[k], sp.peak[k] - fall * .6);
  }
  const d = devicePixelRatio || 1, lab = Math.round(14 * d), head = Math.round(16 * d);
  cx.fillStyle = '#050607'; rrect(0, 0, W, H, 8 * d); cx.fill();
  cx.font = '700 ' + Math.round(10 * d) + 'px Consolas, "Courier New", monospace'; cx.textBaseline = 'middle';
  cx.fillStyle = '#4f7d78'; cx.textAlign = 'left'; cx.fillText('ANALIZADOR DE ESPECTRO', 10 * d, head * .7);
  cx.textAlign = 'right'; cx.fillText(SB + ' BANDAS', W - 10 * d, head * .7);
  const x0 = 10 * d, x1 = W - 10 * d, y0 = head + 6 * d, y1 = H - lab - 4 * d;
  const bw = (x1 - x0) / SB, gap = Math.max(1, bw * .22);
  const segs = Math.max(10, Math.min(32, Math.round((y1 - y0) / (7 * d)))), sh = (y1 - y0) / segs, sg = Math.max(1, sh * .28);
  const col = i => i >= segs * .88 ? '#ff5a4e' : i >= segs * .7 ? '#ffd166' : '#5ef08a';
  for (let k = 0; k < SB; k++) {
    const x = x0 + k * bw + gap / 2, lit = Math.round(sp.bar[k] * segs), pk = Math.min(segs - 1, Math.round(sp.peak[k] * segs) - 1);
    for (let i = 0; i < segs; i++) {
      const y = y1 - (i + 1) * sh + sg / 2;
      cx.globalAlpha = i < lit || (i === pk && pk > 0) ? 1 : .07; cx.fillStyle = col(i); cx.fillRect(x, y, bw - gap, sh - sg);
    }
  }
  cx.globalAlpha = 1; cx.fillStyle = '#6f6b5d'; cx.textAlign = 'center';
  const every = bw * SB / (d * 9) < 34 ? 2 : 1;
  SPEC_LABELS.forEach(([f, t], i) => {
    if (i % every) return;
    const k = Math.log(f / 40) / Math.log(400) * SB - .5;
    const tw = cx.measureText(t).width / 2;
    cx.fillText(t, Math.max(x0 + tw, Math.min(x1 - tw, x0 + (k + .5) * bw)), H - lab / 2 - 2 * d);
  });
}
