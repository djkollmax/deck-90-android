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
  ['att', 9.595, 80.891, 6.997, 12.987],
  ['scan', 17.191, 80.891, 6.85, 12.987], ['loc', 24.041, 80.891, 6.643, 12.987],   // the MONO · LOC rocker: SCAN on the left, LOC on the right
  ['prev', 31.784, 80.891, 6.697, 12.987], ['next', 38.481, 80.891, 6.697, 12.987],
  ['m1', 46.377, 80.891, 5.997, 12.987], ['m2', 52.374, 80.891, 6.997, 12.987],
  ['m3', 59.970, 80.891, 6.997, 12.987], ['m4', 66.967, 80.891, 6.897, 12.987],
  ['m5', 74.663, 80.891, 6.597, 12.987], ['m6', 81.259, 80.891, 8.996, 12.987],
  ['slot', 17.091, 15.213, 73.463, 10.019], ['eject', 91.854, 24.490, 4.998, 15.955, 1],
  ['pwr', 2.599, 80.891, 6.397, 12.987],                           // as on the PC's front (src/index.html)
  ['view', 28.886, 42.857, 53.373, 27.458],                         // the display itself: the VU, then the spectrum
];
const el = id => document.getElementById(id);
const app = window.Deck90Android || {                              // a browser: a stand-in, for a look at the face
  state: () => JSON.stringify({ band: 'FM1', slot: 3, freq: '95.1', station: 'Metro', song: 'Un tema de prueba', playing: true, power: true, quality: 'AAC 128',
    memories: ['Aspen', '80s80s', 'Metro', 'Rock & Pop', 'La 100', 'Vorterix', '', '', '', '', '', ''], wx: { t: 24.3, max: 28, min: 17, place: 'Buenos Aires' } }),
  search: (q, cc) => setTimeout(() => faceFound([{ name: 'Radio de prueba ' + (q || cc), url: 'http://x', codec: 'FLAC', bitrate: 0, cc: cc || 'AR', place: 'Buenos Aires' },
    { name: 'Otra radio', url: 'http://y', codec: 'MP3', bitrate: 128, cc: 'UY', place: '' }]), 300),
  tune: j => toast('sintonizo ' + JSON.parse(j).name),
  levels: () => { const t = Date.now() / 300, l = 0.5 + 0.4 * Math.sin(t), r = 0.5 + 0.4 * Math.cos(t * 1.3); return [l, r, l * .35, r * .35].join(','); },
  spectrum: () => { const t = Date.now() / 400; return Array.from({ length: 28 }, (_, k) => (-28 - k * 0.9 + 8 * Math.sin(t + k * .7)).toFixed(1)).join(','); },
  discs: () => JSON.stringify(Array.from({ length: 9 }, (_, i) => ({ d: i + 1, name: ['Abbey Road', 'Kind of Blue', 'Rumours', 'Artaud', 'Clics Modernos', 'Discovery', 'Random Access Memories', 'Thriller', 'Nevermind'][i], songs: 9 + i }))),
  // SERVER, for a look in a browser: a small pretend library
  jfServer: () => JSON.stringify({ server: 'http://192.168.0.199:8096', name: 'Damián', signedIn: !!window.jfDemo, source: window.jfDemoSrc || 'srv', ready: window.jfDemoSrc === 'tel' || !!window.jfDemo }),
  jfSignIn: (a, u) => setTimeout(() => { window.jfDemo = true; faceJf('signed', { name: u, albums: 1402 }); }, 300),
  jfSignOut: () => { window.jfDemo = false; },
  jfPlay: () => {},
  jfList: (req, kind, id, text) => setTimeout(() => {
    const albums = [['Immunity', 'Jon Hopkins', 2013], ['Discovery', 'Daft Punk', 2001], ['Random Access Memories', 'Daft Punk', 2013], ['After Hours', 'The Weeknd', 2020], ['Inside In / Inside Out', 'The Kooks', 2006], ['In Ghost Colours', 'Cut Copy', 2008]]
      .map(([name, a, y], i) => ({ t: 'album', id: 'a' + i, name, sub: a + ' · ' + y }));
    const songs = ['We Disappear', 'Open Eye Signal', 'Breathe This Air', 'Collider', 'Abandon Window', 'Form by Firelight', 'Sun Harmonics', 'Immunity']
      .map((name, i) => ({ t: 'song', id: 's' + i, i, n: i + 1, name, sub: 'Jon Hopkins · Immunity', len: 200 + i * 37, q: 'FLAC 24/44.1' }));
    const items = kind === 'album' || kind === 'queue' ? songs : kind === 'artists' ? ['Cut Copy', 'Daft Punk', 'Jon Hopkins', 'The Kooks', 'The Weeknd'].map((name, i) => ({ t: 'artist', id: 'r' + i, name }))
      : kind === 'search' ? [{ t: 'artist', id: 'r2', name: 'Jon Hopkins' }, albums[0], { ...songs[1], t: 'found' }] : albums;
    faceJfList(req, { kind, id, items, title: kind === 'album' ? 'Immunity' : '' });
  }, 150),
  key: (k, hold) => { if (k === 'loc' && hold) setTimeout(() => faceNear('Mar del Plata', [{ name: 'Radio de la costa', url: 'http://x', codec: 'MP3', bitrate: 128, cc: 'AR', place: 'Mar del Plata' }, { name: 'Onda Atlántica', url: 'http://y', codec: 'AAC', bitrate: 64, cc: 'AR', place: '' }]), 300); else toast('tecla ' + k); },
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

// ═══ the display as on the PC (src/ui/stereo/stereo.js): SDM its colour, six lights and then whole looks (each its
// own glass, letters and meters; three change the whole front), held the one before; HDB four brightnesses; DISP
// what the big letters show. Kept on this phone.
const STEREO_HUES = [0, 38, 150, 196, 248, 300];
const STEREO_DIM = [1, 1.32, .72, .48], STEREO_DIM_NAME = ['NORMAL', 'ALTO', 'MEDIO', 'BAJO'];
const STEREO_LOOKS = [
  { id: 'hd', name: 'HD', say: 'negro, letras amarillas' },
  { id: 'amber', name: 'ÁMBAR', say: 'negro, letras ámbar de los 80' },
  { id: 'ice', name: 'HIELO', say: 'azul noche, letras celestes de VFD' },
  { id: 'night', name: 'VERDE NOCHE', say: 'negro verdoso, letras verdes' },
  { id: 'sport', name: 'ROJO SPORT', say: 'negro, letras rojas' },
  { id: 'white', name: 'BLANCO', say: 'negro, letras blancas' },
  { id: 'lcd', name: 'LCD CLARO', say: 'vidrio claro, letras oscuras', light: true },
  { id: 'miami', name: 'MIAMI', say: 'violeta, letras rosas, vúmetros celestes' },
  { id: 'gold', name: 'ORO', say: 'marrón profundo, letras doradas' },
  { id: 'aqua', name: 'AQUA', say: 'verde agua, letras turquesa' },
  { id: 'cobalt', name: 'COBALTO', say: 'azul profundo, letras blanco azulado' },
  { id: 'lime', name: 'LIMA', say: 'negro, letras verde lima' },
  { id: 'rose', name: 'ROSA', say: 'negro, letras rosa' },
  { id: 'violet', name: 'VIOLETA', say: 'negro, letras violeta' },
  { id: 'fire', name: 'FUEGO', say: 'negro, letras naranja, vúmetros rojos' },
  { id: 'matrix', name: 'MATRIX', say: 'fósforo verde de terminal' },
  { id: 'sunset', name: 'ATARDECER', say: 'violeta oscuro, letras naranja, vúmetros rosa' },
  { id: 'nixie', name: 'NIXIE', say: 'marrón, el naranja de los tubos nixie' },
  { id: 'oled', name: 'OLED', say: 'negro puro, blanco frío, vúmetros azules' },
  { id: 'lcdblue', name: 'LCD AZUL', say: 'LCD con luz azul, letras blancas' },
  { id: 'lcdamber', name: 'LCD ÁMBAR', say: 'LCD con luz ámbar, letras oscuras', light: true },
  { id: 'duo', name: 'BICOLOR', say: 'negro, letras amarillas, carteles celestes, vúmetros verdes' },
  { id: 'carbon', name: 'CARBONO', say: 'negro, letras blancas, carteles rojos' },
  { id: 'neon', name: 'NEÓN', say: 'negro, letras celestes, carteles fucsia' },
  { id: 'emerald', name: 'ESMERALDA', say: 'verde profundo, letras verde claro, vúmetros dorados' },
  { id: 'plasma', name: 'PLASMA', say: 'el naranja rojizo de las pantallas de plasma' },
  { id: 'tokio', name: 'TOKIO', say: 'al estilo Sony de los 2000: frente negro, teclas con luz azul, letras blancas', face: 'tokio' },
  { id: 'studio', name: 'ESTUDIO', say: 'frente negro mate de equipo pro, letras ámbar, vúmetros verdes', face: 'pro' },
  { id: 'titan', name: 'TITANIO', say: 'frente plateado de equipo pro, vidrio negro, letras blancas y naranja', face: 'titan' },
  { id: 'real', name: 'ORIGINAL', say: 'los colores del DP940 de verdad: vidrio violeta, letras amarillas, segmentos apagados verde oliva' },
];
const STEREO_COLORS = STEREO_HUES.length + STEREO_LOOKS.length;
const STEREO_DISP = ['song', 'clock', 'temp', 'clocktemp', 'time'];
const STEREO_DISP_NAME = { song: 'NORMAL', clock: 'HORA', temp: 'TEMPERATURA', clocktemp: 'HORA Y TEMPERATURA', time: 'TIEMPO' };
const look = { color: 1, dimI: 0, disp: 'song' };
try { Object.assign(look, JSON.parse(localStorage.getItem('deck90-face') || '{}')); } catch (_) {}
if (!(look.color >= 1 && look.color <= STEREO_COLORS)) look.color = 1;
if (!(look.dimI >= 0 && look.dimI < STEREO_DIM.length)) look.dimI = 0;
if (!STEREO_DISP.includes(look.disp)) look.disp = 'song';
const stereoLook = () => STEREO_LOOKS[look.color - STEREO_HUES.length - 1] || null;
const lookHue = () => stereoLook() ? 0 : STEREO_HUES[look.color - 1];
function lookApply() {
  const lk = stereoLook(), d = el('stDisplay');
  face.style.setProperty('--st-hue', lookHue() + 'deg'); face.style.setProperty('--st-dim', STEREO_DIM[look.dimI]);
  d.className = d.className.replace(/\s*\blook(-[a-z]+)?\b/g, '') + (lk ? ' look look-' + lk.id + (lk.light ? ' look-light' : '') : '');
  face.dataset.face = lk && lk.face || '';
  const meters = el('mCanvas'), hue = lookHue(), dim = Math.min(1, STEREO_DIM[look.dimI]);
  meters.style.filter = (look.view === 'spec' && hue ? 'hue-rotate(' + hue + 'deg) ' : '') + (dim < 1 ? 'brightness(' + dim + ')' : '');
  try { localStorage.setItem('deck90-face', JSON.stringify(look)); } catch (_) {}
}
lookApply();
if (/temp/.test(look.disp)) setTimeout(() => app.key('weather', false), 1500);   // DISP left on the temperature

function key(k, hold) {
  if (k === 'view') { viewNext(); return; }
  if (k === 'sdm') {
    look.color = (look.color - 1 + (hold ? STEREO_COLORS - 1 : 1)) % STEREO_COLORS + 1; lookApply();
    const lk = stereoLook();
    toast('SDM ' + look.color + '/' + STEREO_COLORS + ' · ' + (lk ? lk.name + ': ' + lk.say : 'COLOR ' + look.color), lk ? 2000 : 1200);
    return;
  }
  if (k === 'hdb') { look.dimI = (look.dimI + 1) % STEREO_DIM.length; lookApply(); toast('HDB · brillo ' + STEREO_DIM_NAME[look.dimI], 1200); return; }
  if (k === 'disp') {
    look.disp = STEREO_DISP[(STEREO_DISP.indexOf(look.disp) + 1) % STEREO_DISP.length]; lookApply();
    if (/temp/.test(look.disp)) app.key('weather', false);           // the temperature where the phone is: the app asks
    toast('DISP · ' + STEREO_DISP_NAME[look.disp] + (look.disp === 'time' ? ' (la radio es en vivo)' : ''), 1500);
    read.last = ''; read(); return;
  }
  if (k === 'scan' && hold && (view.mode || 'radio') === 'radio') { finderOpen(true); return; }   // as on the PC: held, any station in the world
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
      volT = setTimeout(function rep() { key(k); volT = setTimeout(rep, 100); }, 350);
      return;
    }
    if (/^(m[1-6]|sdm|band|scan|pwr|loc|mode)$/.test(k)) holdT = setTimeout(() => { held = true; key(k, true); }, 1000);
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
const BANDS = ['FM1', 'FM2', 'FM3', 'FM4', 'FM5', 'HI-FI', 'AM', 'LOCAL'];   // LOCAL: LOC's, the radios of the place
(function pad() {
  el('padBands').append(...BANDS.map(b => { const x = document.createElement('button'); x.textContent = b; x.dataset.band = b; return x; }));
  el('padMems').append(...Array.from({ length: 12 }, (_, i) => { const x = document.createElement('button'); x.dataset.m = i + 1; x.innerHTML = '<b>' + (i + 1) + '</b><small>—</small>'; return x; }));
  let holdT = 0, held = false;
  const pad = el('pad');
  pad.addEventListener('pointerdown', e => {
    const m = e.target.closest('[data-m]'), k = e.target.closest('[data-k]'); held = false;
    if (m && (view.mode || 'radio') === 'radio') holdT = setTimeout(() => { held = true; app.key('keep' + m.dataset.m, true); setTimeout(read, 80); }, 1000);
    else if (k && k.dataset.k === 'scan') holdT = setTimeout(() => { held = true; key('scan', true); }, 1000);   // as on the face: the world search
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => pad.addEventListener(ev, () => clearTimeout(holdT)));
  pad.addEventListener('click', e => {
    if (held) { held = false; return; }
    const b = e.target.closest('button'); if (!b) return;
    if (b.dataset.v) { viewSet(b.dataset.v); return; }
    if (b.dataset.band) app.key('band:' + b.dataset.band.replace('-', ''), false);
    else if (b.dataset.m) app.key('mem' + b.dataset.m, false);
    else if (b.dataset.k === 'discs') { discsOpen(); return; }
    else if (b.dataset.k === 'jfbrowse') { faceJf('browse'); return; }
    else if (b.dataset.k) { key(b.dataset.k); return; }
    setTimeout(read, 80);
  });
  pad.addEventListener('contextmenu', e => e.preventDefault());
})();
// 0.1.11: the big number keys: twelve for the radio's memories; for a disc, a folder or the songs playing, one per song,
// three rows showing and the rest a swipe below (the one playing comes into view)
let padShown = 0;
function padKeys(count) {
  const box = el('padMems');
  if (box.children.length !== count) {
    const keep = [...box.children];
    while (keep.length > count) keep.pop().remove();
    for (let i = keep.length; i < count; i++) { const x = document.createElement('button'); x.dataset.m = i + 1; x.innerHTML = '<b>' + (i + 1) + '</b><small>—</small>'; box.append(x); }
    padShown = 0;
  }
  box.classList.toggle('many', count > 12);
  if (count > 12) {                                                   // three rows and the top of a fourth: there's more below
    const gap = parseFloat(getComputedStyle(box).rowGap) || 6, h = Math.max(132, box.clientHeight);
    box.style.gridAutoRows = Math.floor((h - 3 * gap) / 3.28) + 'px';
  } else box.style.gridAutoRows = '';
}
const USB_PAD = [['discprev', '◀ CARPETA'], ['discs', 'CARPETAS'], ['discnext', 'CARPETA ▶'], ['mode:radio', 'RADIO']];
const CD_PAD = [['eject', '⏏ EXPULSAR'], ['scan', 'RAND'], ['loc', 'REP'], ['mode:radio', 'RADIO']];
const JF_PAD = [['jfbrowse', 'MÚSICA'], ['scan', 'RAND'], ['loc', 'REP'], ['mode:radio', 'RADIO']];
function padSync(s) {
  const usb = s.mode === 'usb' || s.mode === 'cd' || s.mode === 'server', bands = el('padBands');
  if (bands.dataset.mode !== s.mode) {                               // USB: the folders in place of the bands
    bands.dataset.mode = s.mode; bands.classList.toggle('usb', usb);
    bands.replaceChildren(...(usb ? (s.mode === 'cd' ? CD_PAD : s.mode === 'server' ? JF_PAD : USB_PAD).map(([k, t]) => { const x = document.createElement('button'); x.dataset.k = k; x.textContent = t; return x; })
      : BANDS.map(b => { const x = document.createElement('button'); x.textContent = b; x.dataset.band = b; return x; })));
    const v = document.querySelector('#padViews [data-v=radio]'); if (v) v.textContent = usb ? s.mode.toUpperCase() : 'RADIO';
    const sc = document.querySelector('.pad-row [data-k=scan]'); if (sc) sc.textContent = usb ? 'RAND' : 'SCAN';
  }
  if (usb) {
    const names = s.memories || [], n = (s.usb || s.cd || s.jf || {}).n || 0;
    padKeys(Math.max(12, names.length));                              // 0.1.11: a key for every song, scrolling three rows at a time
    el('padMems').querySelectorAll('button').forEach((b, i) => {
      const x = names[i] || ''; b.querySelector('small').textContent = x || '—'; b.classList.toggle('empty', !x); b.classList.toggle('on', i + 1 === n);
    });
    if (n && n !== padShown) { padShown = n; const b = el('padMems').children[n - 1]; if (b) b.scrollIntoView({ block: 'nearest' }); }
    return;
  }
  padKeys(12);
  el('padBands').querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.band === s.band));
  const names = s.memories || [];
  el('padMems').querySelectorAll('button').forEach((b, i) => {
    const n = names[i] || '';
    b.querySelector('small').textContent = n || '—'; b.classList.toggle('empty', !n); b.classList.toggle('on', i + 1 === s.slot);
  });
}

// the app's state on the display, as the PC's stereo writes it (stereoText, radio mode), and under it
const shortName = n => String(n || '').replace(/\b(fm|radio|am)\b/gi, '').replace(/\s+/g, ' ').trim().slice(0, 12) || 'RADIO';
const two = n => String(n).padStart(2, '0');
function wxTemp(s) { return s.wx && typeof s.wx.t === 'number' ? Math.round(s.wx.t) + '°C' : ''; }
// DISP: the time, the temperature, both taking turns (4 s each), or LIVE (a radio has no time left); the volume first
function dispText(s) {
  if (s.flash) return s.flash;
  let d = look.disp;
  if (d === 'clocktemp') d = wxTemp(s) && Math.floor(Date.now() / 4000) % 2 ? 'temp' : 'clock';
  if (d === 'clock') { const t = new Date(); return two(t.getHours()) + ':' + two(t.getMinutes()); }
  if (d === 'temp') return wxTemp(s) || 'NO WEATHER';
  if (d === 'time') return 'LIVE';
  return '';
}
function context(s) {
  if (look.disp === 'temp' && wxTemp(s))
    return (s.wx.place || 'TEMPERATURE') + (typeof s.wx.max === 'number' ? ' · ↑' + Math.round(s.wx.max) + '° ↓' + Math.round(s.wx.min) + '°' : '');
  return s.station ? (s.freq ? s.band + ' ' + s.freq + ' · ' : '') + s.station : s.band;
}
// the display told only what changed. setPlayback(false) zeroes its meters, so it is called once (it is already in
// radio mode after); setRadio redraws everything (the big digits, the labels, the footer), so a new song or station
// line only rewrites that line, and a new corner (quality, SLEEP) only the footer: the meters never stumble
function dispRadio(radio) {
  if (disp.state.mode !== 'radio') disp.setPlayback(false);
  const was = disp.state.radio, text = v => String(v ?? '').replace(/[\u0000-\u001f]/g, ' ').slice(0, 512);
  const preset = Math.max(1, Math.min(99, Math.trunc(Number(radio.preset) || 1)));
  const same = was.band === text(radio.band) && was.frequency === text(radio.frequency) && was.preset === preset &&
    was.live === !!radio.live && was.mono === !!radio.mono && !!was.local === !!radio.local;
  const canLine = disp._artist && typeof disp._renderFooter === 'function';   // the component's own parts (dp940-display.js)
  if (!same || !canLine) { disp.setRadio(radio); return; }
  const name = text(radio.name), quality = text(radio.quality).slice(0, 12);
  if (was.name !== name) { was.name = name; disp._artist.textContent = (was.live ? name : '').toLocaleUpperCase('es').slice(0, 42); }
  if (was.quality !== quality) { was.quality = quality; disp._renderFooter(); }
}
// USB (MODE): a pendrive's song on the display as the PC's stereo shows a CD (title big; artist, album and the
// folder under it; the song's number, the time left and the quality below)
function usbTrack(s, u, extra) {
  let title, artist;
  if (!u.picked) { title = 'NO USB'; artist = 'MODE HELD: PICK THE PENDRIVE OR A FOLDER'; }
  else if (u.reading) { title = 'READING'; artist = (u.root || 'USB').toUpperCase(); }
  else if (!u.discs) { title = s.error ? 'NO USB' : 'NO MUSIC'; artist = 'EJECT READS ' + (u.root || 'THE FOLDER').toUpperCase() + ' AGAIN'; }
  else if (s.error) { title = 'NO USB'; artist = 'PLUG THE PENDRIVE IN AND PRESS SEL'; }
  else {
    title = extra || u.title || 'USB';
    artist = [u.artist, u.album || u.discName].filter(Boolean).join(' · ') + (s.playing ? '' : ' · PAUSE') + (u.rand ? ' · RAND' : '') + (u.rep === 1 ? ' · REP' : u.rep === 2 ? ' · REP 1' : '');
  }
  return { title, artist, number: u.discs && !s.error ? u.n : 0, length: u.len || 0, quality: s.quality || '', disc: !!u.discs, source: 'USB', playing: true };
}
// the CD (a USB drive): as the PC's stereo shows the DC-90's disc
const CD_SAY = { none: ['NO CD DRIVE', 'CONNECT A CD DRIVE BY USB (OTG)'], perm: ['ALLOW THE DRIVE', 'ACCEPT ANDROID\'S QUESTION'], nodisc: ['NO DISC', 'INSERT A DISC'],
  reading: ['READING', 'THE DISC'], empty: ['NO AUDIO', 'THIS DISC HAS NO MUSIC'], error: ['CD ERROR', 'UNPLUG THE DRIVE AND PLUG IT IN AGAIN'] };
function cdTrack(s, u, extra) {
  const say = CD_SAY[u.status];
  let title, artist;
  if (say) [title, artist] = say;
  else if (s.error) { title = 'NO DISC'; artist = 'INSERT A DISC AND PRESS SEL'; }
  else {
    title = extra || u.title || 'TRACK ' + String(u.n).padStart(2, '0');
    artist = (u.files ? [u.artist || 'MP3', 'DISC ' + u.n + '/' + u.total] : [u.artist, u.album].filter(Boolean)).join(' · ') || 'CD · ' + u.total + ' TRACKS';
    artist += (s.playing ? '' : ' · PAUSE') + (u.rand ? ' · RAND' : '') + (u.rep === 1 ? ' · REP' : u.rep === 2 ? ' · REP 1' : '');
  }
  return { title, artist, number: say || s.error ? 0 : u.n, length: u.len || 0, quality: s.quality || '', disc: !say, source: 'CD', playing: true };
}
function cdNow(s, u) {
  const say = CD_SAY[u.status];
  el('nowBand').textContent = 'CD' + (say ? '' : ' · ' + (u.files ? 'ARCHIVO ' : 'PISTA ') + u.n + '/' + u.total) + (u.rand ? ' · RAND' : '') + (u.rep ? ' · REP' : '') + (s.sleep > 0 ? ' · SLEEP ' + s.sleep + "'" : '');
  el('nowName').textContent = say ? ({ none: 'Sin lectora', perm: 'Permiso para la lectora', nodisc: 'Sin disco', reading: 'Leyendo el disco…', empty: 'El disco no tiene música', error: 'No pude leer la lectora' })[u.status] : u.title || 'CD';
  el('nowSong').textContent = say ? ({ none: 'Conectá una lectora de CD por USB (OTG)', perm: 'Aceptá la pregunta de Android', nodisc: 'Poné un disco', reading: '', empty: 'Probá con otro disco', error: 'Desenchufala y volvé a enchufarla' })[u.status]
    : [u.artist, u.album, u.year].filter(Boolean).join(' · ');
  const q = el('nowQ'); q.textContent = s.quality ? (/PCM|FLAC/.test(s.quality) ? 'HI-FI · ' : '') + s.quality : ''; q.classList.toggle('hifi', /PCM|FLAC/.test(s.quality || ''));
}
// SERVER (MODE): a Jellyfin song as the PC's stereo shows the DM-90's (its display speaks English, as the units do)
function jfTrack(s, u, extra) {
  let title, artist, ok = false;
  if (!u.signedIn && u.tel) { title = 'PHONE MUSIC'; artist = 'ALLOW ANDROID TO READ IT · SEL'; }
  else if (!u.signedIn) { title = 'SIGN IN'; artist = 'SERVER, USER AND PASSWORD · EJECT'; }
  else if (s.error === 'NO SERVER') { title = 'NO SERVER'; artist = 'IS IT ON? AWAY FROM HOME: TAILSCALE'; }
  else if (!u.total) { title = u.reading ? 'READING' : u.tel ? 'PHONE' : 'SERVER'; artist = (u.albums ? u.albums + ' ALBUMS · ' : '') + 'EJECT: THE LISTS · MODE HELD: SOURCE'; }
  else {
    ok = true; title = extra || u.title || 'SERVER';
    artist = [u.artist, u.album].filter(Boolean).join(' · ') + (s.playing ? '' : ' · PAUSE') + (u.rand ? ' · RAND' : '') + (u.rep === 1 ? ' · REP' : u.rep === 2 ? ' · REP 1' : '');
  }
  return { title, artist, number: ok ? (u.track || u.n) : 0, length: u.len || 0, quality: s.quality || '', disc: ok, source: u.source || 'SRV', playing: true };
}
function jfNow(s, u) {
  el('nowBand').textContent = (u.tel ? 'TEL' : 'SERVER') + (u.total ? ' · TEMA ' + u.n + '/' + u.total : u.name ? ' · ' + u.name.toUpperCase() : '') + (u.rand ? ' · RAND' : '') + (u.rep ? ' · REP' : '') + (s.sleep > 0 ? ' · SLEEP ' + s.sleep + "'" : '');
  el('nowName').textContent = !u.signedIn && u.tel ? 'La música del teléfono' : !u.signedIn ? 'Tu música de Jellyfin' : s.error === 'NO SERVER' ? 'No contesta el servidor' : u.title || 'Elegí qué escuchar';
  el('nowSong').textContent = !u.signedIn && u.tel ? 'SEL: Android te pide permiso para leerla' : !u.signedIn ? 'MÚSICA → SERVIDOR: servidor, usuario y contraseña' : s.error === 'NO SERVER' ? '¿Está prendido? Fuera de casa: Tailscale' : u.total ? [u.artist, u.album].filter(Boolean).join(' · ') : 'MÚSICA (abajo) o EJECT: las listas';
  const hi = /FLAC|ALAC|WAV/.test(s.quality || ''), q = el('nowQ'); q.textContent = (hi ? 'HI-FI · ' : '') + (s.quality || ''); q.classList.toggle('hifi', hi);
}
function usbNow(s, u) {
  el('nowBand').textContent = 'USB' + (u.discs ? ' · ' + u.disc + '/' + u.discs + ' · TEMA ' + u.n + '/' + u.total : '') + (u.rand ? ' · RAND' : '') + (u.rep ? ' · REP' : '') + (s.sleep > 0 ? ' · SLEEP ' + s.sleep + "'" : '');
  el('nowName').textContent = u.title || (u.picked ? u.root || 'USB' : 'USB');
  el('nowSong').textContent = s.error ? 'Enchufá el pendrive y tocá ▶' : [u.artist, u.album || u.discName].filter(Boolean).join(' · ');
  const q = el('nowQ'); q.textContent = (/FLAC|ALAC|WAV/.test(s.quality || '') ? 'HI-FI · ' : '') + (s.quality || ''); q.classList.toggle('hifi', /FLAC|ALAC|WAV/.test(s.quality || ''));
}
function read() {
  let s;
  try { s = JSON.parse(app.state()); } catch (_) { return; }
  view = s;
  // PWR (or SLEEP's end): GOOD BYE a moment, then the front goes dark; HELLO when it comes on
  const greet = s.greet || '', live = !!s.playing && !greet, extra = dispText(s), off = s.power === false && !greet;
  const trouble = s.error === 'RECONNECT' ? 'RECONNECTING…' : s.error ? 'NO SIGNAL' : s.buffering ? 'TUNING…' : '';
  const line = trouble || (!live ? '' : s.song && Math.floor(Date.now() / 4000) % 3 !== 2 ? s.song : s.station || '');
  // SLEEP: the minutes left take turns with the quality, in its corner of the display
  const corner = s.sleep > 0 && Math.floor(Date.now() / 4000) % 2 ? 'SLEEP ' + s.sleep : live ? s.quality || '' : '';
  const u = s.mode === 'usb' && s.usb || s.mode === 'cd' && s.cd || s.mode === 'server' && s.jf, usbOn = !!u && !greet;
  const radio = greet ? { band: '', frequency: greet, preset: s.slot || 1, live: false, name: '', mono: false, local: false, quality: '' }
    : extra
    ? { band: '', frequency: extra, preset: s.slot || 1, live, name: context(s), mono: !!s.mono, local: s.band === 'LOCAL' && !s.tuned, quality: corner }
    : { band: s.memo ? 'A.MEM' : s.tuned ? '' : s.band, frequency: s.freq || shortName(s.station).toUpperCase(), preset: s.slot || 1, live, name: line, mono: !!s.mono, local: s.band === 'LOCAL' && !s.tuned, quality: corner };
  const track = usbOn ? (s.mode === 'cd' ? cdTrack(s, u, extra) : s.mode === 'server' ? jfTrack(s, u, extra) : usbTrack(s, u, extra)) : null;
  const key = JSON.stringify([track || radio, off, s.memories, s.scan, s.slot, s.band, s.mode]);
  face.classList.toggle('off', off);
  el('stereoView').classList.toggle('aux-on', live);
  if (usbOn && disp && !off) disp.setElapsed(u.pos || 0);
  if (key === read.last) return;
  read.last = key;
  if (disp) { if (track) disp.setTrack(track); else dispRadio(radio); }
  padSync(s);
  if (u) { if (s.mode === 'cd') cdNow(s, u); else if (s.mode === 'server') jfNow(s, u); else usbNow(s, u); return; }
  el('nowBand').textContent = (s.memo ? 'A.MEM · ' : '') + (s.tuned ? 'MUNDO' + (s.cc ? ' · ' + s.cc : '') : s.band + (s.town ? ' · ' + s.town.toUpperCase() : '') + ' · ' + s.slot + (s.freq ? ' · ' + s.freq : '')) + (s.scan ? ' · SCAN' : '') + (s.sleep > 0 ? ' · SLEEP ' + s.sleep + "'" : '');
  el('nowName').textContent = s.station || 'DECK 90';
  el('nowSong').textContent = trouble || s.song || '';
  const q = el('nowQ'); q.textContent = (/FLAC/.test(s.quality || '') ? 'HI-FI · ' : '') + (s.quality || ''); q.classList.toggle('hifi', /FLAC/.test(s.quality || ''));
  if (finderShown()) finderKeepSync();
}
setInterval(read, 250); read();

// ═══ the meters: the sound the player sends to the speakers (Levels.kt). The stereo's own around its loudness, as
// the PC's stereo does; VU (RMS with a needle's swing, 0 VU a little over the station's own level, PEAK near the
// top of the scale) and ESPECTRO (28 bands, 40 Hz – 16 kHz, +3 dB per octave so music sits level, peaks held a moment)
const VIEWS = ['radio', 'vu', 'spec', 'stereo'], VIEW_NAMES = { radio: 'RADIO', vu: 'VU', spec: 'ESPECTRO', stereo: 'ESTÉREO' };
const cv = el('mCanvas'), cx = cv.getContext('2d', { alpha: false });
// a phone lying down: only the face, the meters full screen. A tablet lying down is tall enough for the keys
const wide = matchMedia('(min-aspect-ratio: 3/2) and (max-height: 560px)');
// a tablet lying down also has ESTÉREO: the face alone, as big as it fits (a phone lying down already shows it so)
const roomy = matchMedia('(min-aspect-ratio: 1/1) and (min-height: 561px)');
const views = () => roomy.matches && !wide.matches ? VIEWS : VIEWS.slice(0, 3);
const staticLayer = document.createElement('canvas');
const staticContext = staticLayer.getContext('2d', { alpha: false });
let artwork = null, dirty = true;
const VU_SKINS = [
  { name: 'ÁMBAR', paper: ['#fff2ca', '#edc477', '#a66f32'], ink: '#2a1d12', red: '#a42b1e', needle: '#2c140b' },
  { name: 'CREMA', paper: ['#fff9e7', '#e8dac0', '#a99980'], ink: '#28201a', red: '#ae281d', needle: '#361713' },
  { name: 'HI-FI 90', paper: ['#213847', '#12212d', '#050a11'], ink: '#c9e9f6', red: '#ff8053', needle: '#fff1bc' }
];
const SPEC_SKINS = [
  { name: 'FL 90', colors: ['#42ef94', '#ffe27b', '#ff8453'], peak: '#ffe8a3' },
  { name: 'CIAN', colors: ['#28dccf', '#a7f7ff', '#e2fcff'], peak: '#d8ffff' },
  { name: 'ÁMBAR', colors: ['#eb9a35', '#ffcf70', '#ffe7ac'], peak: '#fff2b8' }
];
function skinIndex(value, skins) {
  return Number.isInteger(value) && skins[value] ? value : 0;
}
function meterAction() {
  if (wide.matches) { viewNext(); return; }
  const [property, skins] = mode === 'vu' ? ['vuSkin', VU_SKINS] : ['specSkin', SPEC_SKINS];
  look[property] = (skinIndex(look[property], skins) + 1) % skins.length;
  lookApply();
  dirty = true;
  toast(skins[look[property]].name);
}
const sizeObserver = new ResizeObserver(() => { dirty = true; });
sizeObserver.observe(cv);
let mode = 'radio';
function viewSet(v, quiet) {
  mode = views().includes(v) ? v : 'radio'; look.view = mode; lookApply();
  document.body.dataset.view = mode;
  dirty = true;
  el('padViews').querySelectorAll('button').forEach(button => {
    const selected = button.dataset.v === mode;
    button.classList.toggle('on', selected);
    button.setAttribute('aria-pressed', String(selected));
  });
  meterLabel();
  if (!quiet && mode === 'stereo') toast('ESTÉREO · tocá el display para volver');
  if (!quiet && wide.matches)
    toast(mode === 'radio' ? 'RADIO' : VIEW_NAMES[mode] + ' · tocá la pantalla para ' + (mode === 'vu' ? 'el espectro' : 'volver a la radio'));
}
function viewNext() { const list = views(); viewSet(list[(list.indexOf(mode) + 1) % list.length]); }
[wide, roomy].forEach(q => q.addEventListener('change', () => { if (!views().includes(mode)) viewSet('radio', true); }));
function meterLabel() {
  cv.setAttribute('aria-label', wide.matches ? 'Pasar a la vista siguiente' : 'Cambiar diseño del medidor');
}
wide.addEventListener('change', meterLabel);
cv.addEventListener('click', meterAction);
cv.addEventListener('keydown', event => {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  event.preventDefault();
  meterAction();
});
viewSet(look.view, true);

const db = x => x > 0 ? 20 * Math.log10(x) : -90;
let avg = -24, at = 0;
const vu = { l: -20, r: -20, ref: -22, peakL: 0, peakR: 0 };
const SB = 28, BARS = 14;                                          // measured in 28 bands, shown as 14 wide bars (each the louder of two)
const sp = { bar: new Float32Array(BARS), peak: new Float32Array(BARS), hold: new Float32Array(BARS), top: -24 };
const fc = k => 40 * Math.pow(400, (k + .5) / SB);                 // a band's middle: 40 Hz × 400^…, up to 16 kHz
function frame(now) {
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
  const d = Math.min(2, window.devicePixelRatio || 1);           // 2× is sharp on a phone; more only costs frames
  if (dirty || artwork?.d !== d) {
    dirty = false;
    artwork = buildArtwork(d);
  }
  if (!artwork) return;
  const { w, h } = artwork;
  cx.drawImage(staticLayer, 0, 0, w, h);
  if (mode === 'vu') vuFrame(rl, rr, pl, pr, dt, w, h);
  else specFrame(on ? String(app.spectrum ? app.spectrum() : '') : '', dt, w, h);
}
requestAnimationFrame(frame);

// VU calibration and ballistics.
const VU_MARKS = [-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3];
const vuPos = v => Math.max(-0.03, Math.min(1.06, (Math.pow(10, v / 20) - 0.1) / (Math.pow(10, 3 / 20) - 0.1)));
function vuFrame(rl, rr, pl, pr, dt, W, H) {
  const dl = db(rl), dr = db(rr), loud = Math.max(dl, dr);
  if (loud > -60) vu.ref += (loud - vu.ref) * (1 - Math.exp(-dt / 4000));
  const k = 1 - Math.exp(-dt / 95);                                  // about the 300 ms a VU needle takes
  vu.l += (Math.max(-26, dl - vu.ref - 2) - vu.l) * k; vu.r += (Math.max(-26, dr - vu.ref - 2) - vu.r) * k;
  vu.peakL = pl >= .97 ? 350 : Math.max(0, vu.peakL - dt); vu.peakR = pr >= .97 ? 350 : Math.max(0, vu.peakR - dt);
  drawNeedle(artwork.meters[0], vu.l, vu.peakL > 0);
  drawNeedle(artwork.meters[1], vu.r, vu.peakR > 0);
}

const SPEC_LABELS = [[63, '63'], [125, '125'], [250, '250'], [500, '500'], [1000, '1k'], [2000, '2k'], [4000, '4k'], [8000, '8k'], [16000, '16k']];
function specFrame(str, dt, W, H) {
  const vals = str ? str.split(',').map(Number) : [];
  const fine = vals.length === SB ? vals.map((v, k) => v + 3 * Math.log2(fc(k) / 1000)) : null;
  const tilt = fine && Array.from({ length: BARS }, (_, j) => Math.max(fine[2 * j], fine[2 * j + 1]));
  if (tilt) { const mx = Math.max(...tilt); if (mx > -80) sp.top += (Math.min(0, mx + 3) - sp.top) * (1 - Math.exp(-dt / 3000)); }
  const RANGE = 48, fall = dt / 1000 * 1.4;
  for (let k = 0; k < BARS; k++) {
    const t = tilt ? Math.max(0, Math.min(1, (tilt[k] - (sp.top - RANGE)) / RANGE)) : 0;
    sp.bar[k] = t > sp.bar[k] ? t : Math.max(t, sp.bar[k] - fall);
    if (sp.bar[k] >= sp.peak[k]) { sp.peak[k] = sp.bar[k]; sp.hold[k] = 700; }
    else if ((sp.hold[k] -= dt) < 0) sp.peak[k] = Math.max(sp.bar[k], sp.peak[k] - fall * .6);
  }
  drawSpectrum(artwork);
}

/* Artwork is rebuilt only when the view, skin or backing-store size changes. */
function buildArtwork(d) {
  const w = Math.round(cv.clientWidth * d), h = Math.round(cv.clientHeight * d);
  if (!w || !h) return null;
  cv.width = staticLayer.width = w;
  cv.height = staticLayer.height = h;
  const g = staticContext;
  g.setTransform(d, 0, 0, d, 0, 0);
  const width = w / d, height = h / d;
  g.fillStyle = '#090909';
  g.fillRect(0, 0, width, height);
  if (mode === 'spec') return { d, w, h, ...spectrumArtwork(g, width, height) };
  const skin = VU_SKINS[skinIndex(look.vuSkin, VU_SKINS)];
  const side = width / height >= 1.9;                               // two faces side by side when they fit
  const gap = Math.min(12, Math.min(width, height) * .04);
  const mw = side ? (width - gap) / 2 : width;
  const mh = side ? height : (height - gap) / 2;
  const meters = ['L', 'R'].map((ch, i) =>
    vuArtwork(g, side ? i * (mw + gap) : 0, side ? 0 : i * (mh + gap), mw, mh, ch, skin, d));
  return { d, w, h, meters };
}

function panel(g, x, y, w, h) {
  const metal = g.createLinearGradient(x, y, x, y + h);
  metal.addColorStop(0, '#938477');
  metal.addColorStop(.026, '#453a31');
  metal.addColorStop(.55, '#2d2925');
  metal.addColorStop(.985, '#171412');
  metal.addColorStop(1, '#766456');
  g.fillStyle = metal;
  g.fillRect(x, y, w, h);
  g.strokeStyle = '#95816b';
  g.lineWidth = 1;
  g.strokeRect(x + .5, y + .5, w - 1, h - 1);
  g.strokeStyle = '#090806';
  g.strokeRect(x + 2.5, y + 2.5, w - 5, h - 5);
  g.fillStyle = '#080909';
  g.fillRect(x + 5, y + 5, w - 10, h - 10);
}

const SWING = 47 * Math.PI / 180;
const angle = p => -SWING + 2 * SWING * p;
function dialPoint(dial, radius, position) {
  const a = angle(position);
  return [dial.px + radius * Math.sin(a), dial.py - radius * Math.cos(a)];
}
function vuArtwork(g, x, y, w, h, ch, skin, d) {
  const fw = Math.min(w, h * 1.9), fh = fw / 1.9;
  x += (w - fw) / 2;
  y += (h - fh) / 2;
  panel(g, x, y, fw, fh);
  const inset = Math.max(7, fh * .045);
  const ix = x + inset, iy = y + inset, iw = fw - 2 * inset, ih = fh - 2 * inset;
  const dial = { px: ix + iw / 2, py: iy + ih * 1.18, radius: ih * 1.02, skin, d };
  const plate = iy + ih * .8;
  const lx = ix + iw - ih * .15, ly = (plate + iy + ih) / 2;
  Object.assign(dial, { ix, iy, iw, ih, plate, lx, ly });

  const paper = g.createRadialGradient(dial.px, iy + ih * .64, 0, dial.px, iy + ih * .58, iw * .65);
  skin.paper.forEach((color, i) => paper.addColorStop(i / 2, color));
  g.fillStyle = paper;
  g.fillRect(ix, iy, iw, ih);
  const glass = g.createLinearGradient(0, iy, 0, plate);
  glass.addColorStop(0, '#ffffff45');
  glass.addColorStop(.28, '#ffffff00');
  glass.addColorStop(.92, '#00000000');
  glass.addColorStop(1, '#00000029');
  g.fillStyle = glass;
  g.fillRect(ix, iy, iw, plate - iy);

  const scaleRadius = dial.radius * .8;
  for (const [from, to, color, weight] of [[0, vuPos(0), skin.ink, .008], [vuPos(0), 1, skin.red, .029]]) {
    g.beginPath();
    g.arc(dial.px, dial.py, scaleRadius, angle(from) - Math.PI / 2, angle(to) - Math.PI / 2);
    g.strokeStyle = color;
    g.lineWidth = Math.max(1, ih * weight);
    g.stroke();
  }
  g.font = '700 ' + Math.max(8, Math.round(ih * .072)) + 'px Georgia, serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  for (const mark of VU_MARKS) {
    const position = vuPos(mark);
    const a = dialPoint(dial, scaleRadius, position);
    const b = dialPoint(dial, dial.radius * .878, position);
    const label = dialPoint(dial, dial.radius * .96, position);
    g.strokeStyle = g.fillStyle = mark > 0 ? skin.red : skin.ink;
    g.lineWidth = Math.max(1, ih * .01);
    g.beginPath();
    g.moveTo(...a);
    g.lineTo(...b);
    g.stroke();
    g.fillText(mark > 0 ? '+' + mark : String(Math.abs(mark)), ...label);
  }
  g.fillStyle = skin.ink;
  g.font = '700 ' + Math.round(ih * .15) + 'px Georgia, serif';
  g.fillText('VU', dial.px, iy + ih * .61);
  g.font = '700 ' + Math.max(8, Math.round(ih * .057)) + 'px Consolas, monospace';
  g.textAlign = 'left';
  g.fillText('CANAL ' + ch, ix + 7, iy + ih * .09);
  g.textAlign = 'right';
  g.fillText('DECK 90', ix + iw - 7, iy + ih * .09);
  g.fillStyle = '#100e0c';
  g.fillRect(ix, plate, iw, iy + ih - plate);
  g.strokeStyle = '#080604';
  g.strokeRect(ix + .5, iy + .5, iw - 1, ih - 1);
  g.fillStyle = '#a5a096';
  g.fillText('PEAK', lx - Math.max(6, ih * .055), ly);
  g.fillStyle = '#4d201d';
  g.beginPath();
  g.arc(lx, ly, Math.max(2.5, ih * .023), 0, 2 * Math.PI);
  g.fill();
  return dial;
}

function drawNeedle(dial, value, peak) {
  const { d, ix, iy, iw, plate, px, py, ih, lx, ly, radius, skin } = dial;
  cx.save();
  cx.scale(d, d);
  cx.save();
  cx.beginPath();
  cx.rect(ix + 1, iy + 1, iw - 2, plate - iy - 1);
  cx.clip();
  cx.beginPath();
  cx.moveTo(px, py);
  cx.lineTo(...dialPoint(dial, radius * .93, vuPos(value)));
  cx.strokeStyle = skin.needle;
  cx.lineWidth = Math.max(1.1, ih * .009);
  cx.stroke();
  cx.restore();
  if (peak) {
    cx.fillStyle = '#ff5b42';
    cx.shadowColor = '#ff4d29';
    cx.shadowBlur = 8 * d;
    cx.beginPath();
    cx.arc(lx, ly, Math.max(2.5, ih * .023), 0, 2 * Math.PI);
    cx.fill();
  }
  cx.restore();
}

function spectrumArtwork(g, w, h) {
  const skin = SPEC_SKINS[skinIndex(look.specSkin, SPEC_SKINS)];
  panel(g, 0, 0, w, h);
  const glass = g.createLinearGradient(0, 5, 0, h - 5);
  glass.addColorStop(0, '#131e1b');
  glass.addColorStop(.2, '#070d0c');
  glass.addColorStop(1, '#090e0c');
  g.fillStyle = glass;
  g.fillRect(5, 5, w - 10, h - 10);
  const fs = Math.max(9, Math.min(15, Math.round(Math.min(w, h) / 40)));   // letters grow on a tablet
  const x0 = 12, y0 = 16 + fs * 1.4, y1 = h - 12 - fs * 1.3, plotW = w - 24, bw = plotW / BARS;
  const rows = Math.max(10, Math.min(24, Math.round((y1 - y0) / 9)));
  const sh = (y1 - y0) / rows, gap = Math.max(2, bw * .14);
  const columns = Array.from({ length: BARS }, (_, k) => x0 + k * bw + gap / 2);
  const segments = Array.from({ length: rows }, (_, i) => ({
    y: y1 - (i + 1) * sh + sh * .12,
    zone: i >= rows * .88 ? 2 : i >= rows * .7 ? 1 : 0
  }));
  const ledW = bw - gap, ledH = sh * .76;
  for (const { y, zone } of segments) {
    g.fillStyle = skin.colors[zone] + '16';
    for (const x of columns) g.fillRect(x, y, ledW, ledH);
  }
  g.font = '700 ' + fs + 'px Consolas, monospace';
  g.textBaseline = 'middle';
  g.fillStyle = '#a4beb0';
  g.textAlign = 'left';
  g.fillText('ESPECTRO / ' + skin.name, x0, 8 + fs * .9);
  g.textAlign = 'right';
  g.fillText(BARS + ' BANDAS', w - x0, 8 + fs * .9);
  g.fillStyle = '#9aa991';
  g.textAlign = 'center';
  for (const [f, text] of SPEC_LABELS) {
    const half = g.measureText(text).width / 2;
    const x = x0 + Math.log(f / 40) / Math.log(400) * plotW;
    g.fillText(text, Math.max(x0 + half, Math.min(w - x0 - half, x)), h - 6 - fs * .7);
  }
  return { skin, columns, segments, rows, ledW, ledH };
}

function drawSpectrum({ d, skin, columns, segments, rows, ledW, ledH }) {
  cx.save();
  cx.scale(d, d);
  const lit = new Path2D(), middle = new Path2D(), hot = new Path2D();
  const zones = [lit, middle, hot];
  const peaks = new Path2D(), halo = new Path2D();
  for (let k = 0; k < BARS; k++) {
    const count = Math.round(sp.bar[k] * rows);
    const pk = Math.min(rows - 1, Math.round(sp.peak[k] * rows) - 1);
    const x = columns[k];
    for (let i = 0; i < count; i++) {
      const { y, zone } = segments[i];
      zones[zone].rect(x, y, ledW, ledH);
    }
    if (pk > 0) { const y = segments[pk].y, h = Math.min(ledH, 3); peaks.rect(x, y, ledW, h); halo.rect(x - 1.5, y - 2, ledW + 3, h + 4); }
  }
  for (let i = 0; i < zones.length; i++) {
    cx.fillStyle = skin.colors[i];
    cx.fill(zones[i]);
  }
  cx.fillStyle = skin.peak;                                          // the peaks' glow: a soft halo, not a blur (a
  cx.globalAlpha = .28; cx.fill(halo);                               // shadow blur costs the whole width every frame)
  cx.globalAlpha = 1; cx.fill(peaks);
  cx.restore();
}


// ═══ RADIO SEARCH (SCAN held, as on the PC: src/ui/tuner/finder.js): any station in the world, by name and/or
// country, from Radio Browser (the app asks it: search(), the answer in faceFound). A station touched plays (a tuned
// station, not a memory); the numbers below keep the one playing in the band's memories
const RF_COUNTRIES = ['AR', 'UY', 'CL', 'BR', 'PY', 'BO', 'PE', 'CO', 'VE', 'EC', 'MX', 'CU', 'US', 'CA', 'ES', 'PT', 'FR', 'IT',
  'DE', 'AT', 'CH', 'GB', 'IE', 'NL', 'BE', 'DK', 'SE', 'NO', 'FI', 'IS', 'PL', 'CZ', 'HU', 'GR', 'TR', 'RU', 'UA', 'IL', 'ZA',
  'EG', 'MA', 'IN', 'CN', 'JP', 'KR', 'TW', 'HK', 'SG', 'TH', 'ID', 'PH', 'AU', 'NZ', 'JM'];
const rfCountryName = (() => { let dn = null; try { dn = new Intl.DisplayNames(['es'], { type: 'region' }); } catch (_) {} return cc => (dn && dn.of(cc)) || cc; })();
let rfResults = [], rfBusy = false;
(function finderBuild() {
  const sel = el('rfCountry');
  const opts = RF_COUNTRIES.map(cc => [cc, rfCountryName(cc)]).sort((a, b) => a[1].localeCompare(b[1], 'es'));
  sel.append(new Option('Todos los países', ''), ...opts.map(([cc, n]) => new Option(n, cc)));
  el('rfKeep').append(...Array.from({ length: 12 }, (_, i) => { const b = document.createElement('button'); b.type = 'button'; b.dataset.m = i + 1; b.textContent = i + 1; return b; }));
})();
function finderShown() { return el('rfFind').classList.contains('show'); }
function finderOpen(on, near) {
  el('rfFind').classList.toggle('show', on); el('rfFind').classList.remove('list');
  if (!on) return;
  el('rfTitle').textContent = 'BUSCAR RADIO EN EL MUNDO';
  if (near) return finderKeepSync();
  finderKeepSync();
  setTimeout(() => el('rfName').focus(), 50);
}
function finderKeepSync() {
  el('rfBand').textContent = view.keepBand || view.band || 'FM1';
  el('rfKeep').querySelectorAll('button').forEach(b => { b.disabled = !view.playing; });
}
function finderSearch() {
  const q = el('rfName').value.trim(), cc = el('rfCountry').value, msg = el('rfMsg');
  if (!q && !cc) { msg.textContent = 'Escribí un nombre o elegí un país'; return; }
  if (!app.search) { msg.textContent = '⚠ La búsqueda anda en la app'; return; }
  rfBusy = true; msg.textContent = 'Buscando…';
  el('rfName').blur();
  app.search(q, cc);
}
// LOC held: the place's radios, in the search
window.faceNear = (town, r) => {
  finderOpen(true, true);
  el('rfTitle').textContent = 'RADIOS DE ' + (town ? town.toUpperCase() : 'LA ZONA');
  el('rfName').value = ''; el('rfCountry').value = '';
  faceFound(r);
  if (r && !r.length) el('rfMsg').textContent = 'No encontré radios cerca: probá por país';
};
window.faceFound = r => {
  rfBusy = false;
  const msg = el('rfMsg');
  if (!r) { msg.textContent = '⚠ No pude buscar: ¿hay internet?'; return; }
  rfResults = r;
  msg.textContent = r.length ? r.length + (r.length === 1 ? ' radio' : ' radios') + (r.length >= 60 ? ' (las 60 más escuchadas)' : '') : 'No encontré radios con ese nombre';
  finderRender();
};
function finderRender() {
  el('rfList').replaceChildren(...rfResults.map((r, i) => {
    const b = document.createElement('button');
    b.type = 'button'; b.className = 'rf-item' + (view.tuned && view.station === r.name ? ' on' : ''); b.dataset.i = i;
    const name = document.createElement('b'); name.textContent = r.name;
    const where = document.createElement('small'); where.textContent = [r.place, r.cc ? rfCountryName(r.cc) : ''].filter(Boolean).join(', ');
    const q = document.createElement('i'), codec = String(r.codec || '').toUpperCase();
    q.textContent = (codec === 'FLAC' ? 'HI-FI · ' : '') + [codec, r.bitrate || ''].filter(Boolean).join(' ');
    if (codec === 'FLAC') q.className = 'hifi';
    b.append(name, where, q);
    return b;
  }));
}
el('rfGo').addEventListener('click', finderSearch);
el('rfNear').addEventListener('click', () => { el('rfMsg').textContent = 'Buscando las radios de la zona…'; app.key('loc', true); });
el('rfClose').addEventListener('click', () => finderOpen(false));
el('rfFind').addEventListener('click', e => { if (e.target.id === 'rfFind') finderOpen(false); });
el('rfCountry').addEventListener('change', finderSearch);
el('rfName').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); finderSearch(); } });
// USB's folders (the pad's CARPETAS): the search's window as a list; one touched plays from its first song
function discsOpen() {
  let list = [];
  try { list = JSON.parse(app.discs ? app.discs() : '[]'); } catch (_) {}
  el('rfFind').classList.add('show', 'list');
  el('rfTitle').textContent = 'CARPETAS · ' + ((view.usb && view.usb.root) || 'USB').toUpperCase();
  el('rfMsg').textContent = list.length ? list.length + (list.length === 1 ? ' carpeta' : ' carpetas') + ' con música' : 'No hay música: MODE apretado para elegir el pendrive';
  const now = view.usb ? view.usb.disc : 0;
  el('rfList').replaceChildren(...list.map(d => {
    const b = document.createElement('button'); b.type = 'button'; b.className = 'rf-item' + (d.d === now ? ' on' : ''); b.dataset.disc = d.d;
    const name = document.createElement('b'); name.textContent = d.name;
    const n = document.createElement('small'); n.textContent = d.songs + (d.songs === 1 ? ' tema' : ' temas');
    const i = document.createElement('i'); i.textContent = d.d;
    b.append(name, n, i); return b;
  }));
  const on = el('rfList').querySelector('.on'); if (on) on.scrollIntoView({ block: 'center' });
}
el('rfList').addEventListener('click', e => {
  const d = e.target.closest('[data-disc]');
  if (d) { app.key('disc' + d.dataset.disc, false); finderOpen(false); setTimeout(read, 300); return; }
  const b = e.target.closest('.rf-item'); if (!b) return;
  const r = rfResults[Number(b.dataset.i)]; if (!r) return;
  app.tune(JSON.stringify(r));
  setTimeout(() => { read(); finderRender(); }, 300);
});
el('rfKeep').addEventListener('click', e => {
  const b = e.target.closest('button[data-m]'); if (!b || b.disabled) return;
  app.key('keep' + b.dataset.m, true);
  setTimeout(read, 150);
});

// ═══ SERVER: the music lists (MÚSICA, or EJECT) and the sign-in. The app answers a list through faceJfList(req, …);
// an older answer that arrives late is dropped. ◀ goes back through what was opened
const jf = { stack: [], req: 0, view: null, timer: 0 };
function jfShow(on) { el('jfBox').classList.toggle('show', on); if (!on) el('jfFind').blur(); }
function jfServer() { try { return JSON.parse(app.jfServer ? app.jfServer() : '{}'); } catch (_) { return {}; } }
function jfHome() {
  jf.stack = []; el('jfFind').value = '';
  const j = view.jf || {}, who = jfServer(), tel = who.source === 'tel';
  jfSrcPaint(who);
  jfView({ kind: 'home', title: tel ? 'MÚSICA · TELÉFONO' : 'MÚSICA' + (who.name ? ' · ' + who.name.toUpperCase() : '') });
  if (!who.ready && who.source) {                                   // 0.1.10: the source isn't ready: what it needs
    return jfRender([tel ? { t: 'nav', k: 'allow', name: 'PERMITIR', sub: 'Android te pregunta si Deck 90 puede leer la música del teléfono' }
      : { t: 'nav', k: 'login', name: 'ENTRAR AL SERVIDOR', sub: 'La dirección de tu Jellyfin, el usuario y la contraseña' }], '');
  }
  jfRender([
    { t: 'go', k: 'shuffle', name: 'AL AZAR', sub: tel ? '100 temas de todo el teléfono' : '100 temas de toda la biblioteca' },
    { t: 'nav', k: 'recent', name: 'RECIENTES', sub: tel ? 'Los discos que copiaste último' : 'Los discos que entraron último' },
    { t: 'nav', k: 'albums', name: 'DISCOS', sub: j.albums ? j.albums + ' discos' : '' },
    { t: 'nav', k: 'artists', name: 'ARTISTAS', sub: j.artists ? j.artists + ' artistas' : '' },
    { t: 'nav', k: 'queue', name: 'LO QUE SUENA', sub: j.total ? j.total + ' temas' : '' },
    tel ? null : { t: 'nav', k: 'login', name: 'SERVIDOR', sub: who.server || '' },
  ].filter(Boolean), j.reading ? 'Leyendo la biblioteca…' : '');
}
// 0.1.10: SOURCE, as tabs over the lists
function jfSrcPaint(who) { for (const b of el('jfSrc').children) b.classList.toggle('on', b.dataset.src === ((who || jfServer()).source || 'srv')); }
el('jfSrc').addEventListener('click', e => {
  const b = e.target.closest('button[data-src]'); if (!b) return;
  if (app.jfSource) app.jfSource(b.dataset.src); else { window.jfDemoSrc = b.dataset.src; if (b.dataset.src !== 'usb') jfHome(); }
});
function jfView(v, push) {
  if (push && jf.view) jf.stack.push(jf.view);
  jf.view = v; el('jfTitle').textContent = v.title; el('jfBack').style.visibility = jf.stack.length ? 'visible' : 'hidden';
  if (v.kind === 'home') return;
  el('jfMsg').textContent = v.kind === 'search' ? 'Buscando…' : 'Cargando…'; el('jfList').replaceChildren();
  const r = ++jf.req;
  if (app.jfList) app.jfList(r, v.kind, v.id || '', v.text || '');
}
window.faceJfList = (req, o) => {
  if (req !== jf.req || !jf.view) return;
  const items = o.items || [];
  if (o.title && jf.view.kind !== 'search') { jf.view.title = o.title.toUpperCase(); el('jfTitle').textContent = jf.view.title; }
  const what = { albums: 'discos', artists: 'artistas', artist: 'discos', album: 'temas', recent: 'discos', queue: 'temas', search: 'resultados' }[o.kind] || '';
  jfRender(items, o.error === 'signed out' ? 'El servidor ya no te reconoce: SERVIDOR para entrar de nuevo'
    : o.error && !items.length ? '⚠ No contesta el servidor: ' + o.error : items.length ? items.length + ' ' + what : o.kind === 'search' ? 'No encontré nada' : 'No hay nada acá');
};
function jfRender(items, msg) {
  el('jfMsg').textContent = msg || '';
  const now = view.jf && view.jf.title;
  el('jfList').replaceChildren(...items.map(it => {
    const b = document.createElement('button'); b.type = 'button';
    b.className = 'rf-item' + (it.t === 'nav' || it.t === 'go' || it.t === 'artist' || it.t === 'album' ? ' nav' : '') + ((it.t === 'song' || it.t === 'found') && it.name === now ? ' on' : '');
    Object.assign(b.dataset, { t: it.t, id: it.id || '', k: it.k || '', i: it.i != null ? it.i : '', name: it.name || '' });
    const name = document.createElement('b'); name.textContent = (it.t === 'song' && it.n ? String(it.n).padStart(2, '0') + '  ' : '') + it.name;
    const sub = document.createElement('small'); sub.textContent = it.sub || '';
    const side = document.createElement('i');
    side.textContent = it.t === 'song' || it.t === 'found' ? (it.q ? it.q + ' · ' : '') + (it.len ? Math.floor(it.len / 60) + ':' + String(it.len % 60).padStart(2, '0') : '') : it.t === 'go' ? '▶' : '›';
    if (/FLAC|ALAC|WAV/.test(it.q || '')) side.classList.add('hifi');
    b.append(name, sub, side); return b;
  }));
  const on = el('jfList').querySelector('.on'); if (on) on.scrollIntoView({ block: 'center' });
}
el('jfList').addEventListener('click', e => {
  const b = e.target.closest('.rf-item'); if (!b) return;
  const d = b.dataset, v = jf.view || {};
  if (d.t === 'go') { app.jfPlay && app.jfPlay(d.k, '', 0); jfShow(false); return; }
  if (d.t === 'nav') {
    if (d.k === 'login') { jfShow(false); return faceJf('login'); }
    if (d.k === 'allow') { if (app.jfSource) app.jfSource('tel'); return; }
    return jfView({ kind: d.k, title: d.name }, true);
  }
  if (d.t === 'artist') return jfView({ kind: 'artist', id: d.id, title: d.name.toUpperCase() }, true);
  if (d.t === 'album') return jfView({ kind: 'album', id: d.id, title: d.name.toUpperCase() }, true);
  if (d.t === 'song' && app.jfPlay) app.jfPlay(v.kind === 'queue' ? 'queue' : 'album', v.id || '', Number(d.i) || 0);
  if (d.t === 'found' && app.jfPlay) app.jfPlay('found', d.id, 0);
  setTimeout(read, 400); jfShow(false);
});
el('jfBack').addEventListener('click', () => { const v = jf.stack.pop(); if (!v) return; if (v.kind === 'home') return jfHome(); jfView(v); });
el('jfClose').addEventListener('click', () => jfShow(false));
// any words, as they're typed: artists and albums from what's kept, songs from the server
el('jfFind').addEventListener('input', () => {
  clearTimeout(jf.timer);
  const text = el('jfFind').value.trim();
  jf.timer = setTimeout(() => {
    if (text.length < 2) { if (jf.view && jf.view.kind === 'search') jfHome(); return; }
    jfView({ kind: 'search', text, title: 'BUSCAR' }, !(jf.view && jf.view.kind === 'search'));
  }, 450);
});
el('jfFind').addEventListener('keydown', e => { if (e.key === 'Enter') el('jfFind').blur(); });

function jfLoginOpen() {
  const who = jfServer();
  el('jfAddr').value = who.server ? who.server.replace(/^http:\/\//, '') : el('jfAddr').value;
  el('jfPw').value = '';
  if (!el('jfUser').value && who.name) el('jfUser').value = who.name;   // 0.1.10: entering again, only the password
  el('jfLoginMsg').textContent = who.signedIn ? 'Conectado como ' + who.name + '. Para cambiar, entrá de nuevo.' : '';
  el('jfOut').style.display = who.signedIn ? '' : 'none';
  el('jfLogin').classList.add('show');
  setTimeout(() => (el('jfAddr').value ? el(el('jfUser').value ? 'jfPw' : 'jfUser') : el('jfAddr')).focus(), 60);
}
el('jfGo').addEventListener('click', () => {
  const addr = el('jfAddr').value.trim(), user = el('jfUser').value.trim(), pw = el('jfPw').value;
  if (!addr || !user) { el('jfLoginMsg').textContent = 'Falta la dirección del servidor o el usuario'; return; }
  if (!app.jfSignIn) { el('jfLoginMsg').textContent = '⚠ Entrar anda en la app'; return; }
  el('jfLoginMsg').textContent = 'Entrando…'; el('jfGo').disabled = true;
  app.jfSignIn(addr, user, pw); el('jfPw').value = '';
});
el('jfPw').addEventListener('keydown', e => { if (e.key === 'Enter') el('jfGo').click(); });
el('jfLoginClose').addEventListener('click', () => el('jfLogin').classList.remove('show'));
// 0.1.10: no server: the phone's own music (SOURCE TEL)
el('jfUseTel').addEventListener('click', () => { el('jfLogin').classList.remove('show'); if (app.jfSource) app.jfSource('tel'); else { window.jfDemoSrc = 'tel'; faceJf('browse'); } });
el('jfOut').addEventListener('click', () => { app.jfSignOut && app.jfSignOut(); el('jfLogin').classList.remove('show'); toast('SERVER · saliste del servidor'); });

// what the app asks of the face: the sign-in, the lists, the sign-in's answer
window.faceJf = (what, data) => {
  if (what === 'login') return jfLoginOpen();
  if (what === 'browse') { const w = jfServer(); if (!w.signedIn && w.source !== 'tel' && app.jfServer) return jfLoginOpen(); jfShow(true); return jfHome(); }
  if (what === 'source') { if (el('jfBox').classList.contains('show') || jfServer().ready) { jfShow(true); jfHome(); } return; }   // 0.1.10: SOURCE changed
  if (what === 'close') { jfShow(false); return; }
  if (what === 'signed') {
    el('jfGo').disabled = false;
    if (data && data.error) { el('jfLoginMsg').textContent = data.error; return; }
    el('jfLogin').classList.remove('show');
    toast('SERVER · hola ' + (data && data.name || '') + (data && data.albums ? ' · ' + data.albums + ' discos' : ''));
    jfShow(true); jfHome();
  }
};
