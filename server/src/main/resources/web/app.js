// Deurbel web-app — live beeld via go2rtc, de rest via de deurbel-server.
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

// ---------- icons ----------
const ICONS = {
  bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
  video: '<path d="m22 8-6 4 6 4V8Z"/><rect x="2" y="6" width="14" height="12" rx="2"/>',
  film: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M7 3v18M17 3v18M3 7.5h4M3 12h18M3 16.5h4M17 7.5h4M17 16.5h4"/>',
  sliders: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M19 10v2a7 7 0 0 1-14 0v-2M12 19v3"/>',
  volume: '<path d="M11 5 6 9H2v6h4l5 4V5Z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"/>',
  volumeX: '<path d="M11 5 6 9H2v6h4l5 4V5Z"/><path d="m22 9-6 6M16 9l6 6"/>',
  message: '<path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>',
  play: '<path d="m6 3 14 9-14 9V3z"/>',
  share: '<path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13"/>',
  trash: '<path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  moon: '<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  wifiOff: '<path d="M2 2l20 20M8.5 16.5a5 5 0 0 1 7 0M2 8.8a15 15 0 0 1 4.2-2.8M10.7 5.1A15 15 0 0 1 22 8.8M5 12.9a10 10 0 0 1 5.2-2.7M16.8 11.1A10 10 0 0 1 19 12.9M12 20h.01"/>',
  chevronRight: '<path d="m9 18 6-6-6-6"/>',
  chevronLeft: '<path d="m15 18-6-6 6-6"/>',
  maximize: '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8"/>',
  send: '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
  music: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
  speaker: '<rect x="4" y="2" width="16" height="20" rx="2"/><circle cx="12" cy="14" r="4"/><path d="M12 6h.01"/>',
  shareIos: '<path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13"/>',
  plusSquare: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M12 8v8M8 12h8"/>',
  person: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  loader: '<path d="M21 12a9 9 0 1 1-6.2-8.6"/>',
  battery: '<rect x="2" y="7" width="17" height="10" rx="2.5"/><path d="M22 11v2"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  camera: '<path d="M14.5 4h-5L7 7H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3l-2.5-3Z"/><circle cx="12" cy="13" r="3"/>',
  edit: '<path d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>',
  zone: '<path d="M4 18 8 6h9l3 12Z"/><circle cx="4" cy="18" r="1.5"/><circle cx="8" cy="6" r="1.5"/><circle cx="17" cy="6" r="1.5"/><circle cx="20" cy="18" r="1.5"/>',
  leaf: '<path d="M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.5 19 2c1 2 2 4.2 2 8 0 5.5-4.8 10-10 10Z"/><path d="M2 21c0-3 1.9-5.4 5.1-6"/>',
  alert: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
};
const icon = (n, cls = '') => `<svg class="${cls}" viewBox="0 0 24 24">${ICONS[n] || ''}</svg>`;
function paintIcons(root = document) {
  $$('svg[data-i]', root).forEach((s) => { s.setAttribute('viewBox', '0 0 24 24'); s.innerHTML = ICONS[s.dataset.i] || ''; });
}
paintIcons();
const setIcon = (svg, n) => { svg.dataset.i = n; svg.innerHTML = ICONS[n]; };

// ---------- helpers ----------
const api = async (path, opts = {}) => {
  const headers = opts.body && !(opts.body instanceof Blob) ? { 'Content-Type': 'application/json' } : {};
  const r = await fetch(path, { ...opts, headers });
  const data = (r.headers.get('Content-Type') || '').includes('json') ? await r.json() : null;
  if (!r.ok) throw new Error((data && data.error) || `Fout ${r.status}`);
  return data;
};
const post = (p, b) => api(p, { method: 'POST', body: JSON.stringify(b || {}) });
const buzz = (ms = 12) => navigator.vibrate && navigator.vibrate(ms);
const pad = (n) => String(n).padStart(2, '0');
const hhmm = (s) => { const d = new Date(s * 1000); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
const dayName = (date) => {
  const d = new Date(date); d.setHours(0, 0, 0, 0);
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const diff = Math.round((today - d) / 864e5);
  if (diff === 0) return 'Vandaag';
  if (diff === 1) return 'Gisteren';
  return d.toLocaleDateString('nl-NL', { weekday: 'long', day: 'numeric', month: 'long' });
};
const ago = (s) => {
  const m = Math.round((Date.now() / 1000 - s) / 60);
  if (m < 1) return 'Zojuist'; if (m < 60) return `${m} min geleden`;
  if (m < 180) return `${Math.floor(m / 60)} uur geleden`;
  return dayName(s * 1000);
};
const dur = (s) => (s >= 60 ? `${Math.floor(s / 60)}:${pad(s % 60)} min` : `${s} sec`);

let toastTimer;
function toast(text, action, onAction) {
  const t = $('#toast'); $('#toast-text').textContent = text;
  const b = $('#toast-action'); b.hidden = !action; b.textContent = action || '';
  b.onclick = () => { t.hidden = true; onAction && onAction(); };
  t.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), action ? 5000 : 3000);
}

// ---------- sheets ----------
let openSheetEl = null;
function openSheet(id) { closeSheet(); openSheetEl = $(id); $('#scrim').hidden = false; openSheetEl.hidden = false; }
function closeSheet() { if (openSheetEl) openSheetEl.hidden = true; openSheetEl = null; $('#scrim').hidden = true; if (rec) stopRec(); }
$('#scrim').onclick = closeSheet;
$$('.sheet').forEach((sh) => {               // swipe down to close
  let y0 = null;
  sh.addEventListener('touchstart', (e) => { if (sh.scrollTop <= 0) y0 = e.touches[0].clientY; }, { passive: true });
  sh.addEventListener('touchmove', (e) => { if (y0 !== null) { const dy = e.touches[0].clientY - y0; if (dy > 0) sh.style.transform = `translateY(${dy}px)`; } }, { passive: true });
  sh.addEventListener('touchend', (e) => {
    if (y0 === null) return; const dy = e.changedTouches[0].clientY - y0; y0 = null; sh.style.transform = '';
    if (dy > 90) closeSheet();
  });
});

// ---------- navigation ----------
let view = 'live';
function go(v) {
  view = v;
  $$('.tabbar button').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  $$('.view').forEach((s) => (s.hidden = s.id !== `view-${v}`));
  window.scrollTo(0, 0);
  if (v === 'live') startLive(); else stopLive();
  if (v === 'clips' && !clipsLoaded) loadClips(true);
  if (v === 'settings') renderSettings();
}
$$('.tabbar button').forEach((b) => (b.onclick = () => { buzz(); go(b.dataset.v); }));

// ---------- status / devices ----------
let status = null;
let curDev = null;
try { curDev = new URLSearchParams(location.search).get('dev') || localStorage.getItem('dev'); } catch (e) {}
const devs = () => (status && status.devices) || [];
const devById = (id) => devs().find((d) => d.id === id);
const cur = () => devById(curDev) || devs()[0];
const dq = (sep = '?') => (cur() ? `${sep}dev=${cur().id}` : '');
const devIcon = (d) => (d && d.kind === 'camera' ? 'camera' : 'bell');

function renderDevSwitch() {
  const list = devs();
  ['#dev-switch-live', '#dev-switch-clips'].forEach((sel) => {
    const el = $(sel);
    el.hidden = list.length < 2;
    if (list.length < 2) return;
    el.innerHTML = list.map((d) => `<button data-id="${d.id}" class="${d.id === cur().id ? 'on' : ''}"></button>`).join('');
    $$('button', el).forEach((b, i) => { b.textContent = list[i].name; b.onclick = () => selectDev(b.dataset.id); });
  });
}
function selectDev(id) {
  if (cur() && cur().id === id) return;
  buzz(); curDev = id;
  try { localStorage.setItem('dev', id); } catch (e) {}
  renderDevSwitch(); renderPower();
  stopLive(); if (view === 'live') startLive();
  clipsLoaded = false; if (view === 'clips') loadClips(true);
}
async function refreshStatus() {
  try {
    status = await api('/api/status');
  } catch (e) {
    status = null;
    showStageMsg('Geen verbinding met thuis. Staat Tailscale aan op je telefoon?');
    return;
  }
  if (status.setup) { showSetup(); return; }
  if (!$('#setup').hidden) { $('#setup').hidden = true; stopLive(); go('live'); }
  if (!devById(curDev) && devs()[0]) curDev = devs()[0].id;
  renderDevSwitch(); renderRecent(); renderDnd(); renderRingBanner(); renderPower();
  if (view === 'settings') renderSettings();
  updatePushNotice();
}

function renderRecent() {
  const box = $('#recent');
  const rings = (status && status.rings) || [];
  if (!rings.length) { box.innerHTML = '<div class="empty">Nog niemand aan de deur geweest</div>'; return; }
  box.innerHTML = rings.slice(0, 4).map((r) => `
    <button class="row" data-t="${r.time}">
      <span class="ico-wrap amber">${icon('bell')}</span>
      <div class="grow"><b>${devs().length > 1 && devById(r.dev) ? devById(r.dev).name : 'Iemand aan de deur'}</b><span>${ago(r.time)}</span></div>
      <span class="meta">${hhmm(r.time)}</span>
    </button>`).join('');
  $$('#recent .row').forEach((b, i) => (b.onclick = () => { const r = rings[i]; if (r.dev && devById(r.dev)) { curDev = r.dev; renderDevSwitch(); } clipsLoaded = false; go('clips'); }));
}

function renderRingBanner() {
  const r = status && status.rings && status.rings[0];
  const fresh = r && Date.now() / 1000 - r.time < 180;
  $('#ring-banner').hidden = !fresh;
  if (fresh) $('#ring-when').textContent = `${hhmm(r.time)} · ${ago(r.time).toLowerCase()}`;
}

function batteryIcon(pct, charging) {
  const w = Math.max(1, Math.round(12 * (pct ?? 100) / 100));
  return `<svg viewBox="0 0 24 24"><rect x="2" y="7" width="17" height="10" rx="2.5"/><path d="M22 11v2"/>` +
    (charging ? '<path d="m11 8.5-2.5 4h3l-2 3" stroke-width="1.8"/>' : `<rect x="4.5" y="9.5" width="${w}" height="5" rx="1" fill="currentColor" stroke="none"/>`) + '</svg>';
}
function powerText(p) {
  if (!p) return 'Nog niet bekend';
  const hasBatt = p.percent !== null;
  if (!hasBatt) return 'Netvoeding';
  if (p.full) return `${p.percent}% · vol`;
  if (p.charging) return `${p.percent}% · laadt op`;
  if (p.external === true) return `${p.percent}% · voeding aangesloten`;
  if (p.external === false) return p.low ? `${p.percent}% · bijna leeg – laad op of controleer de voeding` : `${p.percent}% · op batterij`;
  return `${p.percent}%`;
}
function renderPower() {
  const d = cur(); const p = d && d.power;
  const pill = $('#batt-pill');
  const hasBatt = p && p.percent !== null;
  pill.hidden = !hasBatt;
  if (!hasBatt) return;
  pill.classList.toggle('low', !!p.low);
  pill.innerHTML = `${batteryIcon(p.percent, p.charging)}<span>${p.percent}%</span>`;
}

function renderDnd() {
  const until = status && status.dndUntil;
  const txt = until ? `Aan tot ${hhmm(until)}${new Date(until * 1000).getDate() !== new Date().getDate() ? ' morgen' : ''}` : 'Uit';
  $('#dnd-sub').textContent = txt; $('#dnd-sub2').textContent = txt;
}

// ---------- live (go2rtc) ----------
const go2rtcBase = location.protocol === 'https:' ? `https://${location.hostname}:8443/` : `http://${location.hostname}:1984/`;
let liveEl = null, talking = false, soundOn = false, playerMod = null, liveWatch = null;
const loadPlayer = () => (playerMod ||= import('/go2rtc/video-stream.js').catch((e) => { playerMod = null; throw e; }));

function setPill(text, live) { const p = $('#live-pill'); p.classList.toggle('live', !!live); p.lastElementChild.textContent = text; }
function showStageMsg(text) { $('#stage-msg').hidden = false; $('#stage-text').textContent = text; setPill('Offline', false); }

async function startLive() {
  if (liveEl || !cur()) return;
  $('#stage-msg').hidden = true; $('#live-paused').hidden = true; setPill('Verbinden…', false);
  try { await loadPlayer(); } catch (e) { showStageMsg('Live beeld is niet beschikbaar. Draait de thuisserver?'); return; }
  liveEl = document.createElement('video-stream');
  liveEl.mode = talking ? 'webrtc' : 'webrtc,mse,hls,mjpeg';
  liveEl.media = talking ? 'video,audio,microphone' : 'video,audio';
  liveEl.background = false;
  $('#player').replaceChildren(liveEl);
  const dev = cur();
  liveEl.src = new URL(`api/ws?src=${dev.stream}`, go2rtcBase).toString();
  clearInterval(liveWatch);
  const started = Date.now();
  // battery cameras wake up slower, and live view drains them: pause automatically
  const wakeMs = dev.battery ? 35000 : 20000, maxMs = dev.battery ? 90000 : 180000;
  liveWatch = setInterval(() => {
    const v = liveEl && liveEl.video;
    if (v && v.readyState >= 2 && !v.paused) { setPill('Live', true); applySound(); }
    else if (Date.now() - started > wakeMs && !(v && v.readyState >= 2)) { stopLive(); showStageMsg(`${dev.name} reageert niet. Probeer het opnieuw.`); }
    if (!talking && Date.now() - started > maxMs) { stopLive(); setPill('Gepauzeerd', false); $('#live-paused').hidden = false; }
  }, 700);
}
$('#btn-resume').onclick = () => { buzz(); startLive(); };
function stopLive() { clearInterval(liveWatch); if (liveEl) { liveEl.remove(); liveEl = null; } }
function applySound() {
  const v = liveEl && liveEl.video;
  if (v) { v.muted = !soundOn; if (soundOn) v.play().catch(() => {}); }
}
$('#btn-retry').onclick = () => { stopLive(); startLive(); };

$('#act-sound').onclick = () => {
  buzz(); soundOn = !soundOn; applySound();
  $('#act-sound').setAttribute('aria-pressed', soundOn);
  setIcon($('#act-sound svg'), soundOn ? 'volume' : 'volumeX');
};

$('#act-talk').onclick = async () => {
  buzz(20);
  if (!talking) {
    try { (await navigator.mediaDevices.getUserMedia({ audio: true })).getTracks().forEach((t) => t.stop()); }
    catch (e) { toast('Sta de microfoon toe om te kunnen praten'); return; }
  }
  talking = !talking;
  $('#act-talk').setAttribute('aria-pressed', talking);
  $('#act-talk .lbl').textContent = talking ? 'Stop' : 'Praten';
  $('#talking-badge').hidden = !talking;
  if (talking && !soundOn) $('#act-sound').click();      // you want to hear the answer
  stopLive(); await startLive();
};

$('#btn-full').onclick = () => {
  const st = $('#stage'); const on = !st.classList.contains('fs');
  st.classList.toggle('fs', on);
  setIcon($('#btn-full svg'), on ? 'chevronLeft' : 'maximize');
};

// ---------- quick replies ----------
let replies = [];
async function loadReplies() { try { replies = await api('/api/replies'); } catch (e) { replies = []; } renderReplyList(); }

$('#act-reply').onclick = () => {
  buzz();
  const box = $('#reply-quick');
  box.innerHTML = '';
  replies.forEach((r) => {
    const b = document.createElement('button');
    b.innerHTML = `<span></span><span class="state">${icon(r.useVoice && r.hasVoice ? 'person' : 'speaker')}</span>`;
    b.firstElementChild.textContent = r.text;
    b.onclick = async () => {
      if (b.classList.contains('busy')) return;
      buzz(); b.classList.add('busy'); $('.state', b).innerHTML = '<div class="spinner" style="width:20px;height:20px;border-width:2px"></div>';
      try {
        await post(`/api/replies/${r.id}/play${dq()}`);
        b.classList.replace('busy', 'done'); $('.state', b).innerHTML = icon('check');
        setTimeout(closeSheet, 700);
      } catch (e) { b.classList.remove('busy'); $('.state', b).innerHTML = icon('speaker'); toast('Afspelen mislukt: ' + e.message); }
    };
    box.appendChild(b);
  });
  openSheet('#sheet-reply');
};

// ---------- do not disturb ----------
const openDnd = () => {
  $$('#sheet-dnd [data-dnd]').forEach((b) => b.classList.remove('current'));
  $('#sheet-dnd [data-dnd="off"]').hidden = !(status && status.dndUntil);
  openSheet('#sheet-dnd');
};
$('#row-dnd').onclick = openDnd; $('#set-dnd').onclick = openDnd;
$$('#sheet-dnd [data-dnd]').forEach((b) => (b.onclick = async () => {
  buzz(); const v = b.dataset.dnd;
  let body;
  if (v === 'off') body = { off: true };
  else if (v === 'morning') { const d = new Date(); if (d.getHours() >= 7) d.setDate(d.getDate() + 1); d.setHours(7, 0, 0, 0); body = { until: Math.floor(d / 1000) }; }
  else body = { minutes: +v };
  try { status = await post('/api/dnd', body); renderDnd(); closeSheet(); toast(v === 'off' ? 'Niet storen staat uit' : 'Niet storen staat aan — de gong is stil'); }
  catch (e) { toast(e.message); }
}));

// ---------- clips ----------
let days = [], dayIdx = 0, clipsLoaded = false, clipFilter = 'all', clipCache = {};
async function loadClips(reset) {
  const list = $('#clip-list');
  if (reset) {
    dayIdx = 0; clipCache = {}; list.innerHTML = '<div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>';
    try { days = await api(`/api/days${dq()}`); } catch (e) { list.innerHTML = `<div class="card"><div class="empty">${e.message}</div></div>`; return; }
    list.innerHTML = '';
    if (!days.length) { list.innerHTML = '<div class="card"><div class="empty">Nog geen opnames. Zit er een micro-SD-kaart in het apparaat?</div></div>'; }
  }
  clipsLoaded = true;
  const batch = days.slice(dayIdx, dayIdx + 3); dayIdx += batch.length;
  for (const d of batch) {
    try { clipCache[d] = await api(`/api/clips?date=${d}${dq('&')}`); } catch (e) { clipCache[d] = []; }
    renderDay(d);
  }
  $('#clip-more').hidden = dayIdx >= days.length;
}
function renderDay(d) {
  const clips = (clipCache[d] || []).filter((c) => clipFilter === 'all' || c.rang);
  let sec = $(`#day-${d}`);
  if (!sec) { sec = document.createElement('div'); sec.id = `day-${d}`; $('#clip-list').appendChild(sec); }
  if (!clips.length) { sec.innerHTML = ''; return; }
  const date = new Date(+d.slice(0, 4), +d.slice(4, 6) - 1, +d.slice(6, 8));
  sec.innerHTML = `<div class="day-title">${dayName(date)}</div><div class="card"></div>`;
  const card = $('.card', sec);
  clips.forEach((c) => {
    const b = document.createElement('button');
    b.className = 'row clip' + (c.rang ? ' rang' : '');
    b.innerHTML = `<span class="thumb">${icon(c.rang ? 'bell' : 'play')}</span>
      <div class="grow"><b>${hhmm(c.time)}</b><span>${dur(c.duration)}</span></div>
      ${c.rang ? '<span class="badge">Aangebeld</span>' : ''}${icon('chevronRight', 'chev')}`;
    b.onclick = () => openViewer(c, d);
    card.appendChild(b);
  });
}
$('#clip-more').onclick = () => loadClips(false);
$$('#clip-filter button').forEach((b) => (b.onclick = () => {
  buzz(); clipFilter = b.dataset.f;
  $$('#clip-filter button').forEach((x) => x.classList.toggle('on', x === b));
  Object.keys(clipCache).forEach(renderDay);
}));

let viewing = null;
function clipUrl(c, dl) {
  const d = new Date(c.time * 1000);
  const name = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}`;
  return `/api/clip?start=${c.start}&end=${c.end}${dq('&')}` + (dl ? `&download=1&name=${name}` : '');
}
function openViewer(c, day) {
  buzz(); viewing = { c, day };
  $('#viewer').hidden = false;
  $('#viewer-title').textContent = `${dayName(c.time * 1000)} · ${hhmm(c.time)}`;
  $('#viewer-sub').textContent = (c.rang ? 'Aangebeld · ' : '') + dur(c.duration);
  const v = $('#viewer-video');
  $('#viewer-loading').hidden = false;
  v.oncanplay = () => { $('#viewer-loading').hidden = true; };
  v.onerror = () => { $('#viewer-loading').innerHTML = '<p>Deze opname kon niet worden geladen.</p>'; };
  v.src = clipUrl(c); v.play().catch(() => {});
}
function closeViewer() { const v = $('#viewer-video'); v.pause(); v.removeAttribute('src'); v.load(); $('#viewer').hidden = true;
  $('#viewer-loading').innerHTML = '<div class="spinner"></div><p>Opname ophalen van de deurbel…</p>'; viewing = null; }
$('#viewer-close').onclick = closeViewer;

$('#viewer-save').onclick = async () => {
  buzz(); const { c } = viewing;
  try {
    if (navigator.canShare) {                               // iPhone: "Bewaar video" in the share sheet
      toast('Video klaarmaken…');
      const blob = await (await fetch(clipUrl(c))).blob();
      const file = new File([blob], `${(cur() && cur().name) || 'Deurbel'}_${hhmm(c.time).replace(':', '')}.mp4`, { type: blob.type || 'video/mp4' });
      if (navigator.canShare({ files: [file] })) { await navigator.share({ files: [file] }); return; }
    }
  } catch (e) { if (e.name === 'AbortError') return; }
  const a = document.createElement('a'); a.href = clipUrl(c, true); a.download = ''; document.body.appendChild(a); a.click(); a.remove();
  toast('Download gestart');
};

$('#viewer-delete').onclick = () => {
  buzz(); const { c, day } = viewing; closeViewer();
  const before = clipCache[day];
  clipCache[day] = before.filter((x) => x.start !== c.start); renderDay(day);
  let undone = false;
  toast('Opname verwijderd', 'Ongedaan maken', () => { undone = true; clipCache[day] = before; renderDay(day); });
  setTimeout(() => { if (!undone) api(`/api/clip?start=${c.start}${dq('&')}`, { method: 'DELETE' }).catch((e) => toast(e.message)); }, 5200);
};

// ---------- push notifications ----------
const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const b64ToBytes = (s) => Uint8Array.from(atob((s + '='.repeat((4 - (s.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
async function pushEnabled() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window) || Notification.permission !== 'granted') return false;
  const reg = await navigator.serviceWorker.ready;
  return !!(await reg.pushManager.getSubscription());
}
async function updatePushNotice() {
  const on = await pushEnabled().catch(() => false);
  $('#push-notice').hidden = on || !('Notification' in window) || Notification.permission === 'denied';
  $('#push-sub').textContent = on ? 'Aan' : (isIos && !standalone ? 'Eerst op beginscherm zetten' : 'Uit — tik om aan te zetten');
}
async function enablePush() {
  if (isIos && !standalone) { showOnboard(true); return; }
  if (!('PushManager' in window)) { toast('Deze browser ondersteunt geen meldingen'); return; }
  try {
    const reg = await navigator.serviceWorker.ready;
    if ((await Notification.requestPermission()) !== 'granted') { toast('Meldingen zijn niet toegestaan'); return; }
    if (!status) await refreshStatus();
    const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(status.vapidKey) });
    await post('/api/push/subscribe', sub.toJSON());
    toast('Meldingen staan aan 🎉'); buzz(30);
  } catch (e) { toast('Aanzetten mislukt: ' + e.message); }
  updatePushNotice(); refreshStatus();
}
$('#push-notice').onclick = enablePush;
$('#set-push').onclick = enablePush;
$('#set-test').onclick = async () => { buzz(); try { const r = await post('/api/push/test'); toast(`Testmelding verstuurd naar ${r.phones} telefoon${r.phones === 1 ? '' : 's'}`); } catch (e) { toast(e.message); } };

// ---------- settings ----------
async function renderSettings() {
  updatePushNotice();
  if (!status || status.setup) return;
  $('#set-notify').checked = status.notifications;
  $('#devices-sub').textContent = `${status.phones} telefoon${status.phones === 1 ? '' : 's'} gekoppeld`;
  const box = $('#dev-list'); box.innerHTML = '';
  devs().forEach((d) => {
    const b = document.createElement('button'); b.className = 'row';
    b.innerHTML = `<span class="ico-wrap ${d.connected ? (d.kind === 'camera' ? 'blue' : 'amber') : 'grey'}">${icon(devIcon(d))}</span>
      <div class="grow"><b></b><span></span></div>${icon('chevronRight', 'chev')}`;
    $('b', b).textContent = d.name;
    $('.grow span', b).textContent = [d.model, d.connected ? 'verbonden' : 'niet bereikbaar', d.power && d.power.percent !== null ? `${d.power.percent}%` : ''].filter(Boolean).join(' · ');
    b.onclick = () => openDevice(d.id);
    box.appendChild(b);
  });
  const au = status.autoUpdate || { on: true, time: '01:00' };
  $('#au-on').checked = au.on; $('#au-time').value = au.time; $('#au-time-row').hidden = !au.on;
  $('#about').textContent = status.connected ? 'Alles verbonden' : (devs().find((d) => !d.connected) ? `${devs().find((d) => !d.connected).name}: ${devs().find((d) => !d.connected).status}` : status.status);
}
$('#set-notify').onchange = async (e) => { buzz(); try { status = await post('/api/settings', { notifications: e.target.checked }); } catch (err) { toast(err.message); } };
async function saveAutoUpdate() {
  try {
    status = await post('/api/autoupdate', { on: $('#au-on').checked, time: $('#au-time').value || '01:00' });
    toast($('#au-on').checked ? `Updates worden automatisch geïnstalleerd om ${$('#au-time').value}` : 'Automatisch bijwerken staat uit');
  } catch (e) { toast(e.message); }
  renderSettings();
}
$('#au-on').onchange = () => { buzz(); saveAutoUpdate(); };
$('#au-time').onchange = () => saveAutoUpdate();

// ---------- one device: settings sheet ----------
let sheetDev = null;
const labelIcon = { ring: 'bell', person: 'person', motion: 'video', package: 'download', vehicle: 'send', pet: 'person', other: 'message' };
function openDevice(id) { sheetDev = id; renderDevice(); openSheet('#sheet-dev'); }
function renderDevice() {
  const d = devById(sheetDev); if (!d) { closeSheet(); return; }
  const body = $('#dev-body');
  const p = d.power;
  body.innerHTML = `
    <div class="dev-head"><span class="ico-wrap ${d.kind === 'camera' ? 'blue' : 'amber'} big">${icon(devIcon(d))}</span>
      <div class="grow"><h2></h2><span class="sub">${d.model || ''} · ${d.host} · ${d.connected ? 'verbonden' : 'niet bereikbaar'}</span></div>
      <button class="icon-btn" id="dv-rename" aria-label="Naam wijzigen">${icon('edit')}</button></div>
    <div class="card flush">
      ${p ? `<div class="row"><span class="ico-wrap ${p.low ? 'red' : 'green'}">${batteryIcon(p.percent, p.charging)}</span>
        <div class="grow"><b>Voeding</b><span>${powerText(p)}</span></div></div>` : ''}
      ${d.kind === 'doorbell' ? `<label class="row"><span class="ico-wrap violet">${icon('music')}</span>
        <div class="grow"><b>Gong</b><span>${d.chime === null ? 'Niet beschikbaar' : status.dndUntil ? 'Stil door Niet storen' : 'Belgeluid in huis'}</span></div>
        <input type="checkbox" class="toggle" id="dv-chime" ${d.chime ? 'checked' : ''} ${d.chime === null ? 'disabled' : ''}></label>` : ''}
      <label class="row"><span class="ico-wrap green">${icon('leaf')}</span>
        <div class="grow"><b>Zuinig opnemen</b><span>Alleen opnemen als er iets gebeurt (minder stroom en opslag)</span></div>
        <input type="checkbox" class="toggle" id="dv-rec" ${d.eventsOnly ? 'checked' : ''} ${d.eventsOnly === null ? 'disabled' : ''}></label>
      <button class="row" id="dv-zones"><span class="ico-wrap blue">${icon('zone')}</span>
        <div class="grow"><b>Detectiezones</b><span>Alleen in deze zones reageren</span></div>${icon('chevronRight', 'chev')}</button>
      <button class="row" id="dv-fw"><span class="ico-wrap grey">${icon('download')}</span>
        <div class="grow"><b>Software</b><span>Zoeken naar updates</span></div>${icon('chevronRight', 'chev')}</button>
    </div>
    <h3 class="section-title" style="margin-left:4px">Waarvoor een melding?</h3>
    ${d.kind === 'doorbell' ? `<button class="btn wide flush" id="dv-learn">${icon('bell')}Aanbellen herkennen</button>
      <p class="foot" id="dv-learn-hint" style="margin-left:4px">Druk één keer op de bel, dan weet de app welke gebeurtenis het aanbellen is.</p>` : ''}
    <div class="card flush" id="dv-types"></div>
    <p class="foot" id="dv-types-foot" style="margin-left:4px"></p>
    <button class="btn text danger" id="dv-remove">Apparaat verwijderen uit de app</button>`;
  $('h2', body).textContent = d.name;
  // event types
  const types = $('#dv-types');
  if (!d.types.length) types.innerHTML = '<div class="empty">Nog geen gebeurtenissen gezien.</div>';
  d.types.forEach((t) => {
    const row = document.createElement('div'); row.className = 'row';
    const opts = Object.entries(status.labelNames || {}).map(([k, v]) => `<option value="${k}" ${t.label === k ? 'selected' : ''}>${v}</option>`).join('');
    row.innerHTML = `<span class="ico-wrap ${t.label === 'ring' ? 'amber' : 'grey'}">${icon(labelIcon[t.label] || 'bell')}</span>
      <label class="grow type-name"><b></b><span></span>
        <select aria-label="Naam kiezen"><option value="" ${t.label ? '' : 'selected'} disabled>Kies een naam…</option>${opts}</select></label>
      <input type="checkbox" class="toggle" ${t.notify ? 'checked' : ''} aria-label="Melding">`;
    $('b', row).textContent = t.name; $('label span', row).textContent = t.desc;
    $('select', row).onchange = async (e) => { buzz(); try { status = await post(`/api/types/label?dev=${d.id}`, { type: t.type, label: e.target.value }); renderDevice(); } catch (err) { toast(err.message); } };
    $('input', row).onchange = async (e) => {
      buzz(); const set = new Set(d.types.filter((x) => x.notify).map((x) => x.type)); e.target.checked ? set.add(t.type) : set.delete(t.type);
      status = await post(`/api/settings?dev=${d.id}`, { ringTypes: [...set] }); renderDevice();
    };
    types.appendChild(row);
  });
  $('#dv-types-foot').textContent = !d.types.length ? '' : d.types.some((x) => x.notify)
    ? 'Je krijgt alleen een melding bij wat aan staat. Tik op een naam om die te wijzigen.'
    : 'Nu krijg je bij álle gebeurtenissen een melding (ook beweging). Zet alleen aan waarvoor je een melding wilt.';
  // actions
  $('#dv-rename').onclick = async () => {
    const n = prompt('Naam van dit apparaat', d.name); if (!n || !n.trim()) return;
    try { status = await post(`/api/devices/rename?dev=${d.id}`, { name: n.trim() }); renderDevice(); renderDevSwitch(); renderSettings(); } catch (e) { toast(e.message); }
  };
  if ($('#dv-chime')) $('#dv-chime').onchange = async (e) => {
    buzz();
    try { await post(`/api/chime?dev=${d.id}`, { on: e.target.checked }); d.chime = e.target.checked; toast(e.target.checked ? 'Gong staat aan' : 'Gong staat uit'); }
    catch (err) { e.target.checked = !e.target.checked; toast('Gong: ' + err.message); }
  };
  $('#dv-rec').onchange = async (e) => {
    buzz();
    try { status = await post(`/api/recording?dev=${d.id}`, { eventsOnly: e.target.checked }); toast(e.target.checked ? 'Neemt alleen nog op bij gebeurtenissen' : 'Neemt continu op'); }
    catch (err) { e.target.checked = !e.target.checked; toast(err.message); }
  };
  $('#dv-zones').onclick = () => openZones(d);
  $('#dv-fw').onclick = () => openFirmware(d.id);
  if ($('#dv-learn')) $('#dv-learn').onclick = () => learnRing(d);
  $('#dv-remove').onclick = async () => {
    if (!confirm(`${d.name} verwijderen uit de app? Op het apparaat zelf verandert niets.`)) return;
    try { status = await api(`/api/devices?dev=${d.id}`, { method: 'DELETE' }); closeSheet(); renderSettings(); renderDevSwitch(); toast('Verwijderd'); } catch (e) { toast(e.message); }
  };
}

async function learnRing(d) {
  buzz(30);
  const btn = $('#dv-learn'); btn.disabled = true;
  btn.innerHTML = '<div class="spinner" style="width:18px;height:18px;border-width:2px;border-top-color:currentColor"></div>Luisteren… druk nu op de bel';
  $('#dv-learn-hint').textContent = 'Loop naar de deur en druk één keer op de bel. Je hebt 90 seconden.';
  let msg;
  try {
    status = await post(`/api/learn?dev=${d.id}`);
    msg = status.learned ? 'Gelukt! Aanbellen is herkend. Je krijgt nu alleen een melding als er wordt aangebeld.' : 'Er is geen aanbelling gezien. Probeer het nog eens.';
    if (status.learned) { toast('Aanbellen is herkend'); buzz(60); }
  } catch (e) { msg = 'Herkennen lukte niet: ' + e.message; }
  renderDevice(); if ($('#dv-learn-hint')) $('#dv-learn-hint').textContent = msg;
}

// ---------- add a device ----------
let addIp = null;
$('#dev-add').onclick = async () => {
  buzz(); addIp = null; $('#add-err').hidden = true; $('#add-ip').value = ''; $('#add-name').value = '';
  openSheet('#sheet-add');
  const list = $('#add-found'); list.hidden = false;
  list.innerHTML = '<div class="row"><div class="spinner" style="width:22px;height:22px;border-width:2px"></div><div class="grow"><b>Zoeken…</b><span>Dit duurt een paar seconden</span></div></div>';
  let found = [];
  try { found = await api('/api/setup/scan'); } catch (e) {}
  $('#add-intro').textContent = found.length ? 'Gevonden. Kies het apparaat en geef het een naam.' : 'Geen nieuw apparaat gevonden. Vul het adres zelf in (te vinden in de app van je router).';
  list.hidden = !found.length; list.innerHTML = '';
  found.forEach((ip, i) => {
    const b = document.createElement('button'); b.className = 'row';
    b.innerHTML = `<span class="ico-wrap blue">${icon('camera')}</span><div class="grow"><b>Tapo-apparaat</b><span>${ip}</span></div><span class="radio"></span>`;
    b.onclick = () => { addIp = ip; $$('#add-found .row').forEach((r) => r.classList.toggle('sel', r === b)); buzz(); };
    list.appendChild(b); if (i === 0) b.click();
  });
};
$('#add-go').onclick = async () => {
  const ip = $('#add-ip').value.trim() || addIp;
  const err = $('#add-err'); err.hidden = true;
  if (!ip) { err.textContent = 'Kies een apparaat of vul het adres in'; err.hidden = false; return; }
  const btn = $('#add-go'); btn.disabled = true; btn.textContent = 'Toevoegen…';
  try {
    const r = await post('/api/devices', { ip, name: $('#add-name').value.trim() });
    toast(`${r.name} is toegevoegd`); buzz(40); closeSheet(); await refreshStatus(); renderSettings();
  } catch (e) { err.textContent = e.message; err.hidden = false; }
  btn.disabled = false; btn.textContent = 'Toevoegen';
};

// ---------- detection zones ----------
let zones = [], zoneSel = 0, zoneDev = null;
async function openZones(d) {
  buzz(); zoneDev = d; closeSheet();
  $('#zone-editor').hidden = false; $('#zone-sub').textContent = 'Zones ophalen…';
  const img = $('#zone-img');
  img.onerror = () => { img.removeAttribute('src'); $('#zone-box').classList.add('noimg'); };
  $('#zone-box').classList.remove('noimg');
  img.src = new URL(`api/frame.jpeg?src=${d.stream}&t=${Date.now()}`, go2rtcBase).toString();
  try {
    zones = (await api(`/api/zones?dev=${d.id}`)).zones;
    $('#zone-sub').textContent = zones.length ? 'Sleep de hoeken om de zone aan te passen' : 'Nog geen zones: het hele beeld telt. Tik op "Zone erbij".';
  } catch (e) { zones = []; $('#zone-sub').textContent = e.message; }
  zoneSel = 0; drawZones();
}
function drawZones() {
  const svg = $('#zone-svg'), box = $('#zone-box');
  $$('.zone-handle', box).forEach((h) => h.remove());
  svg.innerHTML = zones.map((z, i) => {
    const sel = i === zoneSel ? ' sel' : '';
    if (z.length === 2) {
      const [a, b] = z; const x = Math.min(a[0], b[0]), y = Math.min(a[1], b[1]);
      return `<rect class="zone${sel}" data-i="${i}" x="${x}" y="${y}" width="${Math.abs(b[0] - a[0])}" height="${Math.abs(b[1] - a[1])}"/>`;
    }
    return `<polygon class="zone${sel}" data-i="${i}" points="${z.map((p) => p.join(',')).join(' ')}"/>`;
  }).join('');
  $$('.zone', svg).forEach((el) => (el.onpointerdown = () => { zoneSel = +el.dataset.i; drawZones(); }));
  const z = zones[zoneSel]; if (!z) return;
  z.forEach((pt, n) => {
    const h = document.createElement('div'); h.className = 'zone-handle';
    h.style.left = `${pt[0] * 100}%`; h.style.top = `${pt[1] * 100}%`;
    h.onpointerdown = (e) => {
      e.preventDefault(); h.setPointerCapture(e.pointerId);
      h.onpointermove = (ev) => {
        const r = box.getBoundingClientRect();
        pt[0] = Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width));
        pt[1] = Math.min(1, Math.max(0, (ev.clientY - r.top) / r.height));
        h.style.left = `${pt[0] * 100}%`; h.style.top = `${pt[1] * 100}%`;
        const el = $(`.zone[data-i="${zoneSel}"]`, svg);
        if (z.length === 2) { const [a, b] = z; el.setAttribute('x', Math.min(a[0], b[0])); el.setAttribute('y', Math.min(a[1], b[1])); el.setAttribute('width', Math.abs(b[0] - a[0])); el.setAttribute('height', Math.abs(b[1] - a[1])); }
        else el.setAttribute('points', z.map((p) => p.join(',')).join(' '));
      };
      h.onpointerup = () => { h.onpointermove = null; buzz(); };
    };
    box.appendChild(h);
  });
}
$('#zone-add').onclick = () => {
  if (zones.length >= 4) { toast('Maximaal 4 zones'); return; }
  buzz(); const like = zones[0];
  zones.push(like && like.length > 2 ? [[0.25, 0.45], [0.75, 0.45], [0.85, 0.95], [0.15, 0.95]] : [[0.2, 0.35], [0.8, 0.95]]);
  zoneSel = zones.length - 1; drawZones(); $('#zone-sub').textContent = 'Sleep de hoeken om de zone aan te passen';
};
$('#zone-del').onclick = () => { if (!zones.length) return; buzz(); zones.splice(zoneSel, 1); zoneSel = 0; drawZones(); };
$('#zone-save').onclick = async () => {
  buzz(30);
  try { await post(`/api/zones?dev=${zoneDev.id}`, { zones }); toast('Detectiezones opgeslagen'); $('#zone-editor').hidden = true; }
  catch (e) { toast(e.message); }
};
$('#zone-close').onclick = () => { $('#zone-editor').hidden = true; };

function renderReplyList() {
  const box = $('#reply-list');
  box.innerHTML = '';
  replies.forEach((r) => {
    const b = document.createElement('button'); b.className = 'row';
    b.innerHTML = `<span class="ico-wrap ${r.useVoice && r.hasVoice ? 'red' : 'blue'}">${icon(r.useVoice && r.hasVoice ? 'person' : 'message')}</span>
      <div class="grow"><b></b><span>${r.useVoice && r.hasVoice ? 'Eigen stem' : 'Computerstem'}</span></div>${icon('chevronRight', 'chev')}`;
    $('b', b).textContent = r.text;
    b.onclick = () => openEditor(r);
    box.appendChild(b);
  });
}

// ---------- reply editor ----------
let editing = null, rec = null, recBlob = null, recStart = 0, recTimer = null, editVoice = 'tts';
function openEditor(r) {
  editing = r; recBlob = null;
  $('#edit-title').textContent = r ? 'Antwoord wijzigen' : 'Nieuw antwoord';
  $('#edit-text').value = r ? r.text : '';
  $('#edit-delete').hidden = !r;
  setVoice(r && r.useVoice && r.hasVoice ? 'own' : 'tts');
  openSheet('#sheet-edit');
  if (!r) setTimeout(() => $('#edit-text').focus(), 300);
}
function setVoice(v) {
  editVoice = v;
  $$('#edit-voice button').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  $('#rec-area').hidden = v !== 'own';
  const has = recBlob || (editing && editing.hasVoice);
  $('#rec-title').textContent = has ? 'Eigen stem opgenomen ✓' : 'Neem je eigen stem op';
  $('#rec-sub').textContent = has ? 'Tik op de knop om opnieuw op te nemen' : 'Tik op de rode knop en spreek de tekst in';
}
$$('#edit-voice button').forEach((b) => (b.onclick = () => { buzz(); setVoice(b.dataset.v); }));
async function startRec() {
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); } catch (e) { toast('Sta de microfoon toe om op te nemen'); return; }
  const chunks = [];
  rec = new MediaRecorder(stream);
  rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
  rec.onstop = () => {
    stream.getTracks().forEach((t) => t.stop());
    recBlob = new Blob(chunks, { type: rec.mimeType }); rec = null;
    clearInterval(recTimer); $('#rec-btn').classList.remove('on'); setVoice('own');
  };
  rec.start(); recStart = Date.now(); buzz(30);
  $('#rec-btn').classList.add('on');
  $('#rec-title').textContent = 'Opnemen… 0:00';
  $('#rec-sub').textContent = 'Tik nogmaals om te stoppen';
  recTimer = setInterval(() => {
    const s = Math.floor((Date.now() - recStart) / 1000);
    $('#rec-title').textContent = `Opnemen… 0:${pad(s)}`;
    if (s >= 20) stopRec();
  }, 250);
}
function stopRec() { if (rec && rec.state !== 'inactive') rec.stop(); }
$('#rec-btn').onclick = () => (rec ? stopRec() : startRec());

async function saveEditor() {
  const text = $('#edit-text').value.trim();
  if (!text) { toast('Vul een tekst in'); return null; }
  if (!editing) {
    replies = await post('/api/replies', { text });
    editing = replies[replies.length - 1];
  } else if (text !== editing.text) {
    replies = await api(`/api/replies/${editing.id}`, { method: 'PUT', body: JSON.stringify({ text }) });
  }
  if (recBlob) { replies = await api(`/api/replies/${editing.id}/voice`, { method: 'POST', body: recBlob }); recBlob = null; }
  const wantOwn = editVoice === 'own';
  const cur = replies.find((x) => x.id === editing.id);
  if (cur && wantOwn && !cur.hasVoice) { toast('Neem eerst je eigen stem op'); return null; }
  if (cur && cur.useVoice !== wantOwn) replies = await api(`/api/replies/${editing.id}`, { method: 'PUT', body: JSON.stringify({ useVoice: wantOwn }) });
  editing = replies.find((x) => x.id === editing.id);
  renderReplyList();
  return editing;
}
$('#edit-save').onclick = async () => { buzz(); try { if (await saveEditor()) { closeSheet(); toast('Opgeslagen'); } } catch (e) { toast(e.message); } };
$('#edit-listen').onclick = async () => {
  buzz();
  try {
    if (recBlob && editVoice === 'own') { new Audio(URL.createObjectURL(recBlob)).play(); return; }
    const r = await saveEditor(); if (!r) return;
    await new Audio(`/api/replies/${r.id}/preview.wav?t=${Date.now()}`).play();
  } catch (e) { toast(e.message); }
};
$('#edit-delete').onclick = async () => {
  buzz(); const r = editing; closeSheet();
  const before = replies; replies = replies.filter((x) => x.id !== r.id); renderReplyList();
  let undone = false;
  toast('Antwoord verwijderd', 'Ongedaan maken', () => { undone = true; replies = before; renderReplyList(); });
  setTimeout(async () => { if (!undone) { try { replies = await api(`/api/replies/${r.id}`, { method: 'DELETE' }); renderReplyList(); } catch (e) { toast(e.message); } } }, 5200);
};
$('#reply-add').onclick = () => { buzz(); openEditor(null); };

// ---------- firmware ----------
let fwPoll = null, fwDev = null;
function fwHero(cls, ico, title, sub) {
  return `<div class="fw-hero"><span class="big ${cls}">${icon(ico)}</span><b>${title}</b><span>${sub}</span></div>`;
}
async function openFirmware(devId) {
  if (devId) fwDev = devId;
  buzz(); openSheet('#sheet-fw');
  const body = $('#fw-body');
  body.innerHTML = fwHero('', 'refresh', 'Zoeken naar updates…', 'Het apparaat vraagt het na bij TP-Link') + '<div class="progress indet"><i></i></div>';
  let info;
  try { info = await api(`/api/firmware?check=1&dev=${fwDev}`); }
  catch (e) { body.innerHTML = fwHero('', 'alert', 'Zoeken lukte niet', e.message) + '<button class="btn wide" id="fw-retry">Opnieuw</button>'; $('#fw-retry').onclick = () => openFirmware(); return; }
  const cur = info.current.split(' ')[0];
  if (!info.available) {
    body.innerHTML = fwHero('ok', 'shield', 'Up-to-date', `${(devById(fwDev) || {}).name || ''} · versie ${cur}`) +
      '<button class="btn ghost wide" id="fw-close">Sluiten</button>';
    $('#fw-close').onclick = closeSheet; return;
  }
  const latest = info.latest.split(' ')[0];
  body.innerHTML = fwHero('new', 'download', `Nieuwe versie ${latest}`, `Nu geïnstalleerd: ${cur}`) +
    (info.releaseNote ? `<div class="fw-notes"></div>` : '') +
    `<div class="fw-warn">${icon('alert')}<span>Het apparaat downloadt de update zelf bij TP-Link en start daarna opnieuw op. Hij is dan een paar minuten niet bereikbaar. Haal de stroom er niet af.</span></div>
     <button class="btn wide" id="fw-install">Nu installeren</button>`;
  if (info.releaseNote) $('.fw-notes', body).textContent = info.releaseNote;
  $('#fw-install').onclick = () => installFirmware(cur, latest);
}
async function installFirmware(cur, latest) {
  buzz(30);
  const body = $('#fw-body');
  try { await post(`/api/firmware/install?dev=${fwDev}`); }
  catch (e) { toast('Installeren mislukt: ' + e.message); return; }
  body.innerHTML = fwHero('new', 'download', `Bijwerken naar ${latest}…`, 'Laat het apparaat aan de stroom') +
    '<div class="progress indet" id="fw-prog"><i></i></div><p class="foot center" id="fw-state" style="margin:10px 0 0">Downloaden…</p>';
  const started = Date.now();
  clearInterval(fwPoll);
  fwPoll = setInterval(async () => {
    let s = null;
    try { s = await api(`/api/firmware?dev=${fwDev}`); } catch (e) { $('#fw-state').textContent = 'Het apparaat start opnieuw op…'; }
    if (s) {
      const now = s.current.split(' ')[0];
      if (now && now !== cur) {
        clearInterval(fwPoll);
        body.innerHTML = fwHero('ok', 'shield', 'Bijgewerkt!', `Draait nu versie ${now}`) + '<button class="btn wide" id="fw-done">Klaar</button>';
        $('#fw-done').onclick = closeSheet; buzz(40); return;
      }
      const st = s.status || {}; const pct = parseInt(st.progress ?? st.percent ?? '', 10);
      if (!isNaN(pct) && pct > 0) { $('#fw-prog').classList.remove('indet'); $('#fw-prog i').style.width = pct + '%'; $('#fw-state').textContent = `${pct}%`; }
    }
    if (Date.now() - started > 15 * 60 * 1000) { clearInterval(fwPoll); $('#fw-state').textContent = 'Dit duurt lang. Kijk later nog eens bij Instellingen.'; }
  }, 5000);
}
// ---------- first-time setup: link the doorbell ----------
let setupIp = null, setupShown = false;
async function showSetup() {
  if (setupShown) return;
  setupShown = true; stopLive();
  $('#setup').hidden = false;
  const list = $('#setup-found');
  list.innerHTML = '<div class="row"><div class="spinner" style="width:22px;height:22px;border-width:2px"></div><div class="grow"><b>Zoeken…</b><span>Dit duurt een paar seconden</span></div></div>';
  let found = [];
  try { found = await api('/api/setup/scan'); } catch (e) { found = []; }
  if (!found.length) {
    $('#setup-intro').textContent = 'Geen deurbel gevonden. Vul het adres zelf in (te vinden in de app van je router).';
    list.hidden = true; $('#setup-manual-btn').hidden = true; $('#setup-ip').hidden = false;
    return;
  }
  $('#setup-intro').textContent = found.length === 1 ? 'Gevonden! Vul je wachtwoord in om te koppelen.' : 'Kies je deurbel en vul je wachtwoord in.';
  list.innerHTML = '';
  found.forEach((ip, i) => {
    const b = document.createElement('button'); b.className = 'row';
    b.innerHTML = `<span class="ico-wrap amber">${icon('bell')}</span><div class="grow"><b>Tapo-apparaat</b><span>${ip}</span></div><span class="radio"></span>`;
    b.onclick = () => { setupIp = ip; $$('#setup-found .row').forEach((r) => r.classList.toggle('sel', r === b)); buzz(); };
    list.appendChild(b);
    if (i === 0) b.click();
  });
}
$('#setup-manual-btn').onclick = () => { $('#setup-ip').hidden = false; $('#setup-ip').focus(); $('#setup-manual-btn').hidden = true; };
$('#setup-go').onclick = async () => {
  const ip = ($('#setup-ip').hidden ? '' : $('#setup-ip').value.trim()) || setupIp;
  const pw = $('#setup-pw').value;
  const err = $('#setup-err'); err.hidden = true;
  if (!ip) { err.textContent = 'Kies je deurbel of vul het adres in'; err.hidden = false; return; }
  if (!pw) { err.textContent = 'Vul je wachtwoord in'; err.hidden = false; return; }
  const btn = $('#setup-go'); btn.disabled = true; btn.textContent = 'Koppelen…';
  try {
    const r = await post('/api/setup', { ip, password: pw });
    buzz(40); btn.textContent = `Gekoppeld met ${r.model || 'je deurbel'} ✓`;
    setTimeout(() => { setupShown = false; $('#setup').hidden = true; refreshStatus(); go('live'); showOnboard(false); }, 1500);
  } catch (e) {
    err.textContent = e.message; err.hidden = false; btn.disabled = false; btn.textContent = 'Koppelen';
  }
};

// ---------- theme ----------
function applyTheme(t) {
  const root = document.documentElement;
  root.dataset.theme = t === 'light' || t === 'dark' ? t : 'system';
  try { localStorage.setItem('theme', t); } catch (e) {}
  const dark = t === 'dark' || (t !== 'light' && matchMedia('(prefers-color-scheme: dark)').matches);
  $$('meta[name="theme-color"]').forEach((m) => { m.media = 'all'; m.content = dark ? '#0b0d10' : '#f2f3f5'; });
  $$('#theme-seg button').forEach((b) => b.classList.toggle('on', b.dataset.t === t));
}
let themeChoice = 'system';
try { themeChoice = localStorage.getItem('theme') || 'system'; } catch (e) {}
applyTheme(themeChoice);
$$('#theme-seg button').forEach((b) => (b.onclick = () => { buzz(); themeChoice = b.dataset.t; applyTheme(themeChoice); }));
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => applyTheme(themeChoice));

// ---------- onboarding ----------
let installPrompt = null;
addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; });
function showOnboard(force) {
  if (standalone || !$('#setup').hidden) return;
  if (!force && localStorage.getItem('onboarded')) return;
  const steps = isIos
    ? [`Tik onderin Safari op ${icon('shareIos')} <b>Deel</b>`, `Kies ${icon('plusSquare')} <b>Zet op beginscherm</b>`, 'Open <b>Deurbel</b> vanaf je beginscherm']
    : ['Tik in Chrome op het menu <b>⋮</b>', 'Kies <b>App installeren</b> of <b>Toevoegen aan startscherm</b>', 'Open <b>Deurbel</b> vanaf je startscherm'];
  $('#onboard-steps').innerHTML = steps.map((s) => `<li><span>${s}</span></li>`).join('');
  $('#onboard-install').hidden = !installPrompt;
  $('#onboard').hidden = false;
}
$('#onboard-skip').onclick = () => { localStorage.setItem('onboarded', '1'); $('#onboard').hidden = true; };
$('#onboard-install').onclick = async () => { if (installPrompt) { installPrompt.prompt(); await installPrompt.userChoice; installPrompt = null; } $('#onboard-skip').click(); };

// ---------- start ----------
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopLive(); else { refreshStatus(); if (view === 'live') startLive(); }
});
if (new URLSearchParams(location.search).get('live')) history.replaceState(null, '', '/');
refreshStatus(); setInterval(refreshStatus, 10000);
loadReplies();
go('live');
setTimeout(() => showOnboard(false), 1200);
