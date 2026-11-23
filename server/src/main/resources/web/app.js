// Deurbel web-app (iPhone + Android). Live beeld via go2rtc, de rest via de deurbel-server.
const $ = (s) => document.querySelector(s);
const api = async (path, opts = {}) => {
  const r = await fetch(path, { ...opts, headers: opts.body && !(opts.body instanceof Blob) ? { 'Content-Type': 'application/json' } : {} });
  const isJson = (r.headers.get('Content-Type') || '').includes('json');
  const data = isJson ? await r.json() : null;
  if (!r.ok) throw new Error((data && data.error) || `Fout ${r.status}`);
  return data;
};
const post = (p, b) => api(p, { method: 'POST', body: JSON.stringify(b || {}) });
const toast = (msg) => { const t = $('#toast'); t.textContent = msg; t.hidden = false; clearTimeout(toast.t); toast.t = setTimeout(() => t.hidden = true, 3500); };
const fmtTime = (s) => new Date(s * 1000).toLocaleTimeString('nl-NL');
const fmtDateTime = (s) => new Date(s * 1000).toLocaleString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
const fmtDay = (d) => new Date(+d.slice(0, 4), +d.slice(4, 6) - 1, +d.slice(6, 8)).toLocaleDateString('nl-NL', { weekday: 'short', day: 'numeric', month: 'short' });
let status = null;

// ---------- tabs ----------
let current = 'live';
function show(tab) {
  current = tab;
  document.querySelectorAll('nav button').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  document.querySelectorAll('.tab').forEach((s) => (s.hidden = s.id !== `tab-${tab}`));
  if (tab === 'live') startLive(); else stopLive();
  if (tab === 'clips') loadDays();
  if (tab === 'replies') loadReplies();
  if (tab === 'settings') renderSettings();
}
document.querySelectorAll('nav button').forEach((b) => (b.onclick = () => show(b.dataset.tab)));

// ---------- status / toggles ----------
async function refreshStatus() {
  try {
    status = await api('/api/status');
    $('#conn').className = 'dot ' + (status.connected ? 'ok' : 'bad');
    $('#notify').checked = status.notifications;
    $('#chime').disabled = status.chime === null;
    $('#chime').checked = status.chime === true;
    $('#rings').innerHTML = status.rings.length ? status.rings.slice(0, 5).map((r) => `<li>${fmtDateTime(r.time)}</li>`).join('') : '<li>Nog niets</li>';
    if (current === 'settings') renderSettings();
  } catch (e) { $('#conn').className = 'dot bad'; }
}
$('#notify').onchange = async (e) => { try { await post('/api/settings', { notifications: e.target.checked }); toast(e.target.checked ? 'Meldingen aan' : 'Meldingen uit'); } catch (err) { toast(err.message); } };
$('#chime').onchange = async (e) => { try { await post('/api/chime', { on: e.target.checked }); toast(e.target.checked ? 'Gong aan' : 'Gong uit'); } catch (err) { toast('Gong: ' + err.message); refreshStatus(); } };

// ---------- live (go2rtc) ----------
const go2rtcBase = location.protocol === 'https:' ? `https://${location.hostname}:8443/` : `http://${location.hostname}:1984/`;
let liveEl = null, talking = false, soundOn = false, playerLoaded = null;
function loadPlayer() {
  playerLoaded ||= import('/go2rtc/video-stream.js').catch((e) => { playerLoaded = null; throw e; });
  return playerLoaded;
}
async function startLive() {
  const box = $('#player');
  try { await loadPlayer(); } catch (e) { box.innerHTML = '<div class="hint">Live beeld niet beschikbaar (go2rtc draait niet?)</div>'; return; }
  if (liveEl) return;
  box.innerHTML = '';
  liveEl = document.createElement('video-stream');
  liveEl.mode = talking ? 'webrtc' : 'webrtc,mse,hls,mjpeg';
  liveEl.media = talking ? 'video,audio,microphone' : 'video,audio';
  liveEl.background = false;
  box.appendChild(liveEl);
  liveEl.src = new URL('api/ws?src=deurbel', go2rtcBase).toString();
  setTimeout(applySound, 1500);
}
function stopLive() { if (liveEl) { liveEl.remove(); liveEl = null; } }
function applySound() {
  const v = liveEl && liveEl.video;
  if (v) { v.muted = !soundOn; if (soundOn) v.play().catch(() => {}); }
  $('#btn-sound').textContent = soundOn ? '🔊' : '🔇';
}
$('#btn-sound').onclick = () => { soundOn = !soundOn; applySound(); };
$('#btn-reload').onclick = () => { stopLive(); startLive(); };
$('#btn-talk').onclick = async () => {
  if (!talking) {
    try { (await navigator.mediaDevices.getUserMedia({ audio: true })).getTracks().forEach((t) => t.stop()); }
    catch (e) { toast('Geen toegang tot de microfoon'); return; }
  }
  talking = !talking;
  $('#btn-talk').classList.toggle('on', talking);
  $('#talk-hint').textContent = talking ? 'Je praat nu via de deurbel. Tik nogmaals om te stoppen.' : 'Tik op de microfoon om te praten, nogmaals om te stoppen.';
  stopLive(); await startLive();
};

// ---------- quick replies ----------
let replies = [];
async function loadReplies() {
  try { replies = await api('/api/replies'); } catch (e) { toast(e.message); }
  renderQuick(); renderReplies();
}
function renderQuick() {
  $('#quick').innerHTML = '';
  replies.forEach((r) => {
    const b = document.createElement('button');
    b.className = 'chip'; b.textContent = r.text;
    b.onclick = async () => {
      if (b.classList.contains('busy')) return;
      b.classList.add('busy');
      try { await post(`/api/replies/${r.id}/play`); toast(`Afgespeeld: "${r.text}"`); } catch (e) { toast('Mislukt: ' + e.message); }
      b.classList.remove('busy');
    };
    $('#quick').appendChild(b);
  });
}
let recorder = null, recId = null;
function renderReplies() {
  const box = $('#replies'); box.innerHTML = '';
  replies.forEach((r) => {
    const c = document.createElement('div'); c.className = 'card';
    c.innerHTML = `<div class="text"></div><div class="small" style="margin:0 0 8px">${r.useVoice && r.hasVoice ? 'Eigen stem' : 'Computerstem (Nederlands)'}</div>
      <div class="acts"><button data-a="listen">▶ Luister</button><button data-a="door">🔔 Bij de deur</button>
      <button data-a="rec" class="${recId === r.id ? 'rec' : ''}">${recId === r.id ? '■ Stop' : (r.hasVoice ? '🎙 Opnieuw' : '🎙 Eigen stem')}</button>
      ${r.hasVoice ? `<button data-a="voice">${r.useVoice ? 'Computerstem' : 'Eigen stem'}</button>` : ''}
      <button data-a="edit">✏️</button><button data-a="del">🗑</button></div>`;
    c.querySelector('.text').textContent = r.text;
    c.querySelectorAll('button').forEach((b) => (b.onclick = () => replyAction(r, b.dataset.a)));
    box.appendChild(c);
  });
}
async function replyAction(r, a) {
  try {
    if (a === 'listen') { const au = new Audio(`/api/replies/${r.id}/preview.wav?t=${Date.now()}`); await au.play(); }
    if (a === 'door') { toast('Afspelen bij de deur…'); await post(`/api/replies/${r.id}/play`); toast('Afgespeeld'); }
    if (a === 'edit') { const t = prompt('Tekst', r.text); if (t && t.trim()) { replies = await api(`/api/replies/${r.id}`, { method: 'PUT', body: JSON.stringify({ text: t }) }); } }
    if (a === 'del') { if (confirm(`"${r.text}" verwijderen?`)) replies = await api(`/api/replies/${r.id}`, { method: 'DELETE' }); }
    if (a === 'voice') { replies = await api(`/api/replies/${r.id}`, { method: 'PUT', body: JSON.stringify({ useVoice: !r.useVoice }) }); }
    if (a === 'rec') {
      if (recId === r.id) { recorder.stop(); return; }
      if (recId) return;
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const chunks = [];
      recorder = new MediaRecorder(stream);
      recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
      recorder.onstop = async () => {
        stream.getTracks().forEach((t) => t.stop());
        recId = null;
        try {
          replies = await api(`/api/replies/${r.id}/voice`, { method: 'POST', body: new Blob(chunks, { type: recorder.mimeType }) });
          toast('Eigen stem opgeslagen');
        } catch (e) { toast('Opslaan mislukt: ' + e.message); }
        renderQuick(); renderReplies();
      };
      recorder.start(); recId = r.id;
      toast('Opnemen… spreek nu in en tik op Stop');
      setTimeout(() => { if (recId === r.id) recorder.stop(); }, 20000);
    }
  } catch (e) { toast(e.message); }
  renderQuick(); renderReplies();
}
$('#add-reply').onclick = async () => {
  const t = $('#new-reply').value.trim(); if (!t) return;
  try { replies = await post('/api/replies', { text: t }); $('#new-reply').value = ''; renderQuick(); renderReplies(); } catch (e) { toast(e.message); }
};

// ---------- clips ----------
let selectedDay = null, openClip = null;
async function loadDays() {
  $('#days').innerHTML = '<span class="small">Laden…</span>';
  try {
    const days = await api('/api/days');
    if (!selectedDay || !days.includes(selectedDay)) selectedDay = days[0];
    $('#days').innerHTML = '';
    days.forEach((d) => {
      const b = document.createElement('button'); b.className = 'chip' + (d === selectedDay ? ' sel' : ''); b.textContent = fmtDay(d);
      b.onclick = () => { selectedDay = d; loadDays(); };
      $('#days').appendChild(b);
    });
    if (!days.length) $('#days').innerHTML = '<span class="small">Geen opnames. Zit er een micro-SD-kaart in de deurbel?</span>';
    if (selectedDay) loadClips();
  } catch (e) { $('#days').innerHTML = `<span class="small">${e.message}</span>`; }
}
async function loadClips() {
  $('#clips').innerHTML = '<li class="small">Laden…</li>';
  try {
    const clips = await api(`/api/clips?date=${selectedDay}`);
    $('#clips').innerHTML = '';
    clips.forEach((c) => {
      const li = document.createElement('li');
      li.innerHTML = `<span>▶ ${fmtTime(c.time)}</span><span class="small">${c.duration >= 60 ? Math.floor(c.duration / 60) + ':' + String(c.duration % 60).padStart(2, '0') : c.duration + 's'}${c.cached ? ' · ✓' : ''}</span>`;
      li.onclick = () => playClip(c);
      $('#clips').appendChild(li);
    });
    if (!clips.length) $('#clips').innerHTML = '<li class="small">Geen opnames op deze dag</li>';
  } catch (e) { $('#clips').innerHTML = `<li class="small">${e.message}</li>`; }
}
function clipUrl(c, dl) {
  const name = new Date(c.time * 1000).toISOString().slice(0, 19).replace(/[-:T]/g, '');
  return `/api/clip?start=${c.start}&end=${c.end}` + (dl ? `&download=1&name=${name}` : '');
}
function playClip(c) {
  openClip = c;
  $('#clip-view').hidden = false;
  const v = $('#clip-video');
  toast('Opname ophalen van de deurbel… (kan even duren)');
  v.src = clipUrl(c); v.play().catch(() => {});
  $('#clip-download').href = clipUrl(c, true);
  window.scrollTo(0, 0);
}
$('#clip-close').onclick = () => { $('#clip-video').pause(); $('#clip-view').hidden = true; openClip = null; };
$('#clip-delete').onclick = async () => {
  if (!openClip || !confirm('Opname verwijderen uit de app? (Losse opnames op de SD-kaart wissen kan niet; de deurbel overschrijft oude opnames zelf.)')) return;
  try { await api(`/api/clip?start=${openClip.start}`, { method: 'DELETE' }); $('#clip-close').onclick(); loadClips(); toast('Verwijderd'); } catch (e) { toast(e.message); }
};
$('#clips-reload').onclick = loadDays;

// ---------- push notifications ----------
const isIos = /iphone|ipad|ipod/i.test(navigator.userAgent);
const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone;
function b64ToBytes(s) { const p = '='.repeat((4 - (s.length % 4)) % 4); const b = atob((s + p).replace(/-/g, '+').replace(/_/g, '/')); return Uint8Array.from(b, (c) => c.charCodeAt(0)); }
async function pushState() {
  if (!('serviceWorker' in navigator) || !('PushManager' in window)) return isIos && !standalone ? 'Zet de app eerst op je beginscherm (zie hieronder).' : 'Deze browser ondersteunt geen meldingen.';
  const reg = await navigator.serviceWorker.ready;
  const sub = await reg.pushManager.getSubscription();
  return sub && Notification.permission === 'granted' ? 'Meldingen staan aan op dit toestel ✓' : 'Meldingen staan nog uit op dit toestel.';
}
async function enablePush() {
  try {
    const reg = await navigator.serviceWorker.ready;
    const perm = await Notification.requestPermission();
    if (perm !== 'granted') { toast('Meldingen niet toegestaan'); return; }
    const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(status.vapidKey) });
    await post('/api/push/subscribe', sub.toJSON());
    toast('Meldingen staan aan');
  } catch (e) { toast('Meldingen aanzetten mislukt: ' + e.message); }
  renderSettings();
}
$('#push-on').onclick = enablePush;
$('#push-test').onclick = async () => { try { const r = await post('/api/push/test'); toast(`Testmelding verstuurd naar ${r.devices} toestel(len)`); } catch (e) { toast(e.message); } };
async function renderSettings() {
  $('#push-state').textContent = await pushState();
  $('#ios-hint').hidden = !(isIos && !standalone);
  if (!status) return;
  $('#status').textContent = `${status.status} · ${status.devices} toestel(len) met meldingen`;
  const box = $('#types'); box.innerHTML = '';
  if (!status.seenTypes.length) box.innerHTML = '<p class="small">Nog geen gebeurtenissen gezien. Bel een keer aan om het type te leren.</p>';
  status.seenTypes.forEach((t) => {
    const l = document.createElement('label'); l.className = 'type';
    l.innerHTML = `<input type="checkbox" ${status.ringTypes.includes(t) ? 'checked' : ''}> Type ${t}`;
    l.querySelector('input').onchange = async (e) => {
      const set = new Set(status.ringTypes); e.target.checked ? set.add(t) : set.delete(t);
      status = await post('/api/settings', { ringTypes: [...set] });
    };
    box.appendChild(l);
  });
}

// ---------- start ----------
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
document.addEventListener('visibilitychange', () => { if (document.hidden) stopLive(); else { refreshStatus(); if (current === 'live') startLive(); } });
refreshStatus(); setInterval(refreshStatus, 15000);
loadReplies();
show('live');
