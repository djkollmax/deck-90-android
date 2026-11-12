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
];
const el = id => document.getElementById(id);
const app = window.Deck90Android || {                              // a browser: a stand-in, for a look at the face
  state: () => JSON.stringify({ band: 'FM1', slot: 3, freq: '95.1', name: 'Metro · Un tema de prueba', station: 'Metro', song: 'Un tema de prueba', playing: true, quality: 'AAC 128' }),
  levels: () => { const t = Date.now() / 300; return (0.5 + 0.4 * Math.sin(t)) + ',' + (0.5 + 0.4 * Math.cos(t * 1.3)); },
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
  el('nowBand').textContent = (s.memo ? 'A.MEM · ' : '') + s.band + ' · ' + s.slot + (s.freq ? ' · ' + s.freq : '') + (s.scan ? ' · SCAN' : '');
  el('nowName').textContent = s.station || 'DECK 90';
  el('nowSong').textContent = s.song || '';
  const q = el('nowQ'); q.textContent = (/FLAC/.test(s.quality || '') ? 'HI-FI · ' : '') + (s.quality || ''); q.classList.toggle('hifi', /FLAC/.test(s.quality || ''));
}
setInterval(read, 250); read();

// the meters: the sound the player sends to the speakers, around its own loudness, as the PC's stereo does
let avg = -24, at = 0;
(function meters(now) {
  requestAnimationFrame(meters);
  if (!disp) return;
  if (!view.playing) { disp.setLevels(0, 0); return; }
  const dt = at ? Math.min(200, now - at) : 16; at = now;
  const [l, r] = String(app.levels()).split(',').map(Number);
  const dl = l > 0 ? 20 * Math.log10(l) : -90, dr = r > 0 ? 20 * Math.log10(r) : -90, loud = Math.max(dl, dr);
  if (loud > -60) avg += (loud - avg) * (1 - Math.exp(-dt / 2500));
  const f = d => d < -60 ? 0 : Math.max(0, Math.min(1, (d - (avg - 8)) / 13));
  disp.setLevels(f(dl), f(dr));
})(0);
