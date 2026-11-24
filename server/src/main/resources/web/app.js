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
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>',
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

// ---------- status ----------
let status = null;
async function refreshStatus() {
  try {
    status = await api('/api/status');
  } catch (e) {
    status = null;
    showStageMsg('Geen verbinding met thuis. Staat Tailscale aan op je telefoon?');
    return;
  }
  renderRecent(); renderDnd(); renderRingBanner();
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
      <div class="grow"><b>Iemand aan de deur</b><span>${ago(r.time)}</span></div>
      <span class="meta">${hhmm(r.time)}</span>
    </button>`).join('');
  $$('#recent .row').forEach((b) => (b.onclick = () => { go('clips'); }));
}

function renderRingBanner() {
  const r = status && status.rings && status.rings[0];
  const fresh = r && Date.now() / 1000 - r.time < 180;
  $('#ring-banner').hidden = !fresh;
  if (fresh) $('#ring-when').textContent = `${hhmm(r.time)} · ${ago(r.time).toLowerCase()}`;
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
  if (liveEl) return;
  $('#stage-msg').hidden = true; setPill('Verbinden…', false);
  try { await loadPlayer(); } catch (e) { showStageMsg('Live beeld is niet beschikbaar. Draait de thuisserver?'); return; }
  liveEl = document.createElement('video-stream');
  liveEl.mode = talking ? 'webrtc' : 'webrtc,mse,hls,mjpeg';
  liveEl.media = talking ? 'video,audio,microphone' : 'video,audio';
  liveEl.background = false;
  $('#player').replaceChildren(liveEl);
  liveEl.src = new URL('api/ws?src=deurbel', go2rtcBase).toString();
  clearInterval(liveWatch);
  const started = Date.now();
  liveWatch = setInterval(() => {                 // show "Live" once frames arrive, error after 20 s
    const v = liveEl && liveEl.video;
    if (v && v.readyState >= 2 && !v.paused) { setPill('Live', true); applySound(); }
    else if (Date.now() - started > 20000 && !(v && v.readyState >= 2)) { stopLive(); showStageMsg('De deurbel reageert niet. Probeer het opnieuw.'); }
  }, 700);
}
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
        await post(`/api/replies/${r.id}/play`);
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
    try { days = await api('/api/days'); } catch (e) { list.innerHTML = `<div class="card"><div class="empty">${e.message}</div></div>`; return; }
    list.innerHTML = '';
    if (!days.length) { list.innerHTML = '<div class="card"><div class="empty">Nog geen opnames. Zit er een micro-SD-kaart in de deurbel?</div></div>'; }
  }
  clipsLoaded = true;
  const batch = days.slice(dayIdx, dayIdx + 3); dayIdx += batch.length;
  for (const d of batch) {
    try { clipCache[d] = await api(`/api/clips?date=${d}`); } catch (e) { clipCache[d] = []; }
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
  return `/api/clip?start=${c.start}&end=${c.end}` + (dl ? `&download=1&name=${name}` : '');
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
      const file = new File([blob], `Deurbel_${hhmm(c.time).replace(':', '')}.mp4`, { type: blob.type || 'video/mp4' });
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
  setTimeout(() => { if (!undone) api(`/api/clip?start=${c.start}`, { method: 'DELETE' }).catch((e) => toast(e.message)); }, 5200);
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
$('#set-test').onclick = async () => { buzz(); try { const r = await post('/api/push/test'); toast(`Testmelding verstuurd naar ${r.devices} telefoon${r.devices === 1 ? '' : 's'}`); } catch (e) { toast(e.message); } };

// ---------- settings ----------
async function renderSettings() {
  updatePushNotice();
  if (!fw) loadFirmwareSummary();
  if (!status) return;
  $('#set-notify').checked = status.notifications;
  $('#set-chime').checked = status.chime === true;
  $('#set-chime').disabled = status.chime === null;
  $('#chime-sub').textContent = status.chime === null ? 'Niet beschikbaar' : (status.dndUntil ? 'Stil door Niet storen' : 'Belgeluid in huis');
  $('#devices-sub').textContent = `${status.devices} telefoon${status.devices === 1 ? '' : 's'} gekoppeld`;
  const types = $('#types');
  if (!status.seenTypes.length) {
    types.innerHTML = '<div class="empty">Bel één keer aan, dan verschijnen hier de soorten gebeurtenissen.</div>';
    $('#types-foot').textContent = '';
  } else {
    types.innerHTML = '';
    status.seenTypes.forEach((t, i) => {
      const l = document.createElement('label'); l.className = 'row';
      l.innerHTML = `<span class="ico-wrap grey">${icon('bell')}</span><div class="grow"><b>Gebeurtenis type ${t}</b></div>
        <input type="checkbox" class="toggle" ${status.ringTypes.includes(t) ? 'checked' : ''}>`;
      $('input', l).onchange = async (e) => {
        const set = new Set(status.ringTypes); e.target.checked ? set.add(t) : set.delete(t);
        status = await post('/api/settings', { ringTypes: [...set] });
      };
      types.appendChild(l);
    });
    $('#types-foot').textContent = 'Niets aan = melding bij elke gebeurtenis (ook beweging). Zet alleen het type van de bel aan voor minder meldingen.';
  }
  $('#about').textContent = status.connected ? 'Verbonden met de deurbel' : status.status;
}
$('#set-notify').onchange = async (e) => { buzz(); try { status = await post('/api/settings', { notifications: e.target.checked }); } catch (err) { toast(err.message); } };
$('#set-chime').onchange = async (e) => {
  buzz();
  try { await post('/api/chime', { on: e.target.checked }); toast(e.target.checked ? 'Gong staat aan' : 'Gong staat uit'); }
  catch (err) { e.target.checked = !e.target.checked; toast('Gong: ' + err.message); }
};

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
let fw = null, fwPoll = null;
async function loadFirmwareSummary() {
  try {
    fw = await api('/api/firmware');
    $('#fw-sub').textContent = `${fw.model ? fw.model + ' · ' : ''}versie ${fw.current.split(' ')[0] || '?'}`;
    $('#fw-auto').checked = fw.autoUpdate === true; $('#fw-auto').disabled = fw.autoUpdate === null;
  } catch (e) { $('#fw-sub').textContent = 'Niet bereikbaar'; }
}
function fwHero(cls, ico, title, sub) {
  return `<div class="fw-hero"><span class="big ${cls}">${icon(ico)}</span><b>${title}</b><span>${sub}</span></div>`;
}
async function openFirmware() {
  buzz(); openSheet('#sheet-fw');
  const body = $('#fw-body');
  body.innerHTML = fwHero('', 'refresh', 'Zoeken naar updates…', 'De deurbel vraagt het na bij TP-Link') + '<div class="progress indet"><i></i></div>';
  let info;
  try { info = await api('/api/firmware?check=1'); }
  catch (e) { body.innerHTML = fwHero('', 'alert', 'Zoeken lukte niet', e.message) + '<button class="btn wide" id="fw-retry">Opnieuw</button>'; $('#fw-retry').onclick = openFirmware; return; }
  const cur = info.current.split(' ')[0];
  if (!info.available) {
    body.innerHTML = fwHero('ok', 'shield', 'Je deurbel is up-to-date', `Versie ${cur}`) +
      '<button class="btn ghost wide" id="fw-close">Sluiten</button>';
    $('#fw-close').onclick = closeSheet; $('#fw-badge').hidden = true; return;
  }
  $('#fw-badge').hidden = false;
  const latest = info.latest.split(' ')[0];
  body.innerHTML = fwHero('new', 'download', `Nieuwe versie ${latest}`, `Nu geïnstalleerd: ${cur}`) +
    (info.releaseNote ? `<div class="fw-notes"></div>` : '') +
    `<div class="fw-warn">${icon('alert')}<span>De deurbel downloadt de update zelf bij TP-Link en start daarna opnieuw op. Hij is dan een paar minuten niet bereikbaar. Haal de stroom er niet af.</span></div>
     <button class="btn wide" id="fw-install">Nu installeren</button>`;
  if (info.releaseNote) $('.fw-notes', body).textContent = info.releaseNote;
  $('#fw-install').onclick = () => installFirmware(cur, latest);
}
async function installFirmware(cur, latest) {
  buzz(30);
  const body = $('#fw-body');
  try { await post('/api/firmware/install'); }
  catch (e) { toast('Installeren mislukt: ' + e.message); return; }
  body.innerHTML = fwHero('new', 'download', `Bijwerken naar ${latest}…`, 'Laat de deurbel aan de stroom') +
    '<div class="progress indet" id="fw-prog"><i></i></div><p class="foot center" id="fw-state" style="margin:10px 0 0">Downloaden…</p>';
  const started = Date.now();
  clearInterval(fwPoll);
  fwPoll = setInterval(async () => {
    let s = null;
    try { s = await api('/api/firmware'); } catch (e) { $('#fw-state').textContent = 'De deurbel start opnieuw op…'; }
    if (s) {
      const now = s.current.split(' ')[0];
      if (now && now !== cur) {
        clearInterval(fwPoll);
        body.innerHTML = fwHero('ok', 'shield', 'Bijgewerkt!', `De deurbel draait nu versie ${now}`) + '<button class="btn wide" id="fw-done">Klaar</button>';
        $('#fw-done').onclick = closeSheet; $('#fw-badge').hidden = true; buzz(40); loadFirmwareSummary(); return;
      }
      const st = s.status || {}; const pct = parseInt(st.progress ?? st.percent ?? '', 10);
      if (!isNaN(pct) && pct > 0) { $('#fw-prog').classList.remove('indet'); $('#fw-prog i').style.width = pct + '%'; $('#fw-state').textContent = `${pct}%`; }
    }
    if (Date.now() - started > 15 * 60 * 1000) { clearInterval(fwPoll); $('#fw-state').textContent = 'Dit duurt lang. Kijk later nog eens bij Instellingen.'; }
  }, 5000);
}
$('#fw-row').onclick = openFirmware;
$('#fw-auto').onchange = async (e) => {
  buzz();
  try { await post('/api/firmware/auto', { on: e.target.checked }); toast(e.target.checked ? 'Automatisch bijwerken staat aan' : 'Automatisch bijwerken staat uit'); }
  catch (err) { e.target.checked = !e.target.checked; toast(err.message); }
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
  if (standalone) return;
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
