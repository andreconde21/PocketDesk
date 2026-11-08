// PocketDesk desktop client: pairs this browser with a phone, then shows the phone's desktop
// display full window and drives it with this computer's mouse and keyboard.
//
// The phone creates a desktop-class virtual display at exactly this window's size, the apps
// open on it in windows, and the H.264 stream comes over a WebRTC data channel (see player.js).
// Input goes back on the control channel: real mouse events (hover, buttons, wheel), raw keys
// for shortcuts and characters typed with this keyboard's layout.

import { Signaling, PhoneLink } from './rtc.js';
import { Player, detectDecoder } from './player.js';
import { AudioPlayer } from './audio.js';
import { log, warn } from './remotelog.js';

const VERSION = 'pd-1.0';
const $ = (s) => document.querySelector(s);

// ------------------------------------------------------------------ storage

function load(key, fallback) {
  try {
    const v = localStorage.getItem(key);
    return v == null ? fallback : JSON.parse(v);
  } catch {
    return fallback;
  }
}
function save(key, value) {
  try {
    if (value == null) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // storage unavailable: pairing is asked again next time
  }
}

const tvLike = /\b(TV|AFT\w*|CrKey|BRAVIA|SMART-TV|Tizen|Web0S|webOS|GoogleTV)\b/i.test(navigator.userAgent);
const settings = {
  maxw: 1920,
  scale: tvLike ? 1.5 : 1,
  bitrate: 12,
  fps: 60,
  keyboard: tvLike ? 'on' : 'auto',
  sound: true,
  relay: false,
  ...load('pd.settings', {}),
};

const state = {
  sig: null,
  link: null,
  paired: false,
  phoneOnline: false,
  phoneName: 'Phone',
  turn: null,
  sid: 0,
  player: null,
  running: false,
  apps: [],
  icons: {},
  tasks: [],
  reconnectDelay: 1000,
  reconnectTimer: 0,
  lagSince: 0,
};

const audio = new AudioPlayer();

// ------------------------------------------------------------------ ui helpers

function show(which) {
  for (const id of ['pair', 'msg']) $('#' + id).hidden = id !== which;
  $('#screen').hidden = which !== 'screen';
}

function message(title, body, { spinner = true, actions = [] } = {}) {
  $('#msg-title').textContent = title;
  $('#msg-body').innerHTML = body || '';
  $('#msg-spinner').hidden = !spinner;
  const box = $('#msg-actions');
  box.innerHTML = '';
  for (const a of actions) {
    const b = document.createElement('button');
    b.className = a.primary ? 'primary' : 'ghost';
    b.textContent = a.label;
    b.onclick = a.onClick;
    box.appendChild(b);
  }
  show('msg');
}

function setStatus(text, level = '') {
  $('#status-text').textContent = text;
  $('#status').className = 'status ' + level;
}

let toastTimer = 0;
function toast(text, ms = 4000) {
  const t = $('#toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), ms);
}

function tick() {
  const d = new Date();
  $('#clock').textContent = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
setInterval(tick, 10000);
tick();

// ------------------------------------------------------------------ pairing

let code = '';
function renderCode() {
  const box = $('#code');
  box.innerHTML = '';
  for (let i = 0; i < 6; i++) {
    const s = document.createElement('span');
    s.textContent = code[i] || '';
    if (i === code.length) s.className = 'on';
    box.appendChild(s);
  }
}
function typeDigit(k) {
  $('#pair-error').textContent = '';
  if (k === '⌫') code = code.slice(0, -1);
  else if (k === 'OK') submitCode();
  else if (/^\d$/.test(k) && code.length < 6) code += k;
  renderCode();
  if (code.length === 6 && k !== 'OK') submitCode();
}
function buildKeypad() {
  for (const k of ['1', '2', '3', '4', '5', '6', '7', '8', '9', '⌫', '0', 'OK']) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = k;
    b.onclick = () => typeDigit(k);
    $('#keypad').appendChild(b);
  }
}
function submitCode() {
  if (code.length !== 6) return;
  if (!state.sig?.open) {
    $('#pair-error').textContent = 'No connection to the server. Retrying…';
    return;
  }
  $('#pair-error').textContent = 'Checking…';
  state.sig.send({ t: 'pair', code });
}

// ------------------------------------------------------------------ signaling

function onSigOpen(open) {
  log('signaling ' + (open ? 'open' : 'closed'));
  if (open) state.sig.send({ t: 'hello', role: 'car', carToken: load('pd.token', null) });
  else if (!state.link) setStatus('Server offline', 'warn');
}

function onSig(msg) {
  switch (msg.t) {
    case 'needPair':
      state.paired = false;
      save('pd.token', null);
      code = '';
      renderCode();
      setStatus('Not paired', 'warn');
      show('pair');
      break;
    case 'token':
      save('pd.token', msg.carToken);
      break;
    case 'pairFailed':
      code = '';
      renderCode();
      $('#pair-error').textContent = (msg.msg || 'Pairing failed') + '. Check the code on the phone and try again.';
      break;
    case 'paired':
      state.paired = true;
      state.turn = msg.turn || null;
      state.phoneName = msg.name || 'Phone';
      state.phoneOnline = !!msg.online;
      onPhonePresence();
      break;
    case 'phone':
      state.phoneOnline = !!msg.online;
      onPhonePresence();
      break;
    case 'unpaired':
      save('pd.token', null);
      location.reload();
      break;
    case 'signal':
      state.link?.onSignal(msg.data);
      break;
    default:
      break;
  }
}

function onPhonePresence() {
  if (!state.paired) return;
  if (state.phoneOnline) {
    if (!state.link) connectPhone();
  } else if (!state.link) {
    setStatus(`${state.phoneName} offline`, 'warn');
    message(
      `Waiting for ${state.phoneName}`,
      'Open <b>PocketDesk</b> on the phone and tap <b>Start</b>. The phone needs an internet connection; ' +
        'on the same Wi-Fi as this screen the picture is sharpest.',
    );
  }
}

// ------------------------------------------------------------------ phone link

function connectPhone() {
  clearTimeout(state.reconnectTimer);
  if (state.link) return;
  setStatus('Connecting', 'warn');
  if (!state.running) message(`Connecting to ${state.phoneName}…`, 'Setting up a direct link.');
  state.link = new PhoneLink(state.sig, {
    onOpen: onLinkOpen,
    onClose: onLinkClose,
    onCtl,
    onAudio: (buf) => audio.feed(buf),
    turn: settings.relay && state.turn ? state.turn : null,
  });
}

function onLinkOpen() {
  state.reconnectDelay = 1000;
  setStatus(state.phoneName, 'ok');
  const link = state.link;
  link.sendCtl({
    t: 'hello',
    version: VERSION,
    ua: navigator.userAgent,
    screen: { w: screen.width, h: screen.height, dpr: devicePixelRatio, vw: innerWidth, vh: innerHeight },
  });
  link.sendCtl({ t: 'keyboard', mode: settings.keyboard });
  link.sendCtl({ t: 'allApps?' });
  soundOn = null;
  applySound();
  startDesktop();
  setTimeout(async () => {
    const info = await state.link?.pathInfo();
    if (info) {
      log('link path', info);
      $('#status').title = info.relayed ? 'Through the server' : info.direct ? 'Direct link' : 'Over the internet';
    }
  }, 1500);
}

function onLinkClose(reason) {
  const wasRunning = state.running;
  stopDesktop(false);
  state.link = null;
  if (!state.paired) return;
  warn('link down: ' + reason);
  if (reason === 'timeout' || reason === 'connection failed') {
    setStatus('No link', 'bad');
    const actions = [];
    if (!settings.relay && state.turn) {
      actions.push({
        label: 'Connect through the server',
        primary: true,
        onClick: () => {
          settings.relay = true;
          save('pd.settings', settings);
          connectPhone();
        },
      });
    }
    message(
      'Can\'t reach the phone',
      'Put the phone and this screen on the same Wi-Fi network. Some guest and office networks keep devices apart; ' +
        'a phone hotspot that this screen joins works too. Retrying…',
      { actions },
    );
  } else if (wasRunning) {
    setStatus('Reconnecting', 'warn');
    message('Reconnecting…', '');
  }
  if (state.phoneOnline) {
    state.reconnectTimer = setTimeout(connectPhone, state.reconnectDelay);
    state.reconnectDelay = Math.min(state.reconnectDelay * 2, 10000);
  } else {
    onPhonePresence();
  }
}

function onCtl(msg) {
  switch (msg.t) {
    case 'allApps':
      state.apps = msg.apps || [];
      renderMenu();
      break;
    case 'icon':
      state.icons[msg.pkg] = 'data:image/png;base64,' + msg.icon;
      for (const img of document.querySelectorAll(`img[data-pkg="${CSS.escape(msg.pkg)}"]`)) img.src = state.icons[msg.pkg];
      for (const ph of document.querySelectorAll(`.ph[data-pkg="${CSS.escape(msg.pkg)}"]`)) ph.replaceWith(iconImg(msg.pkg));
      break;
    case 'tasks':
      if (msg.sid === state.sid) {
        state.tasks = msg.tasks || [];
        renderTasks();
      }
      break;
    case 'started':
      if (msg.sid === state.sid) {
        showHintOnce();
      }
      break;
    case 'ended':
      if (msg.sid === state.sid) {
        stopDesktop(false);
        message(
          'The desktop stopped',
          escapeHtml(msg.reason || 'The phone closed it.'),
          { spinner: false, actions: [{ label: 'Start again', primary: true, onClick: () => startDesktop() }] },
        );
      }
      break;
    case 'error':
      toast(msg.msg, 6000);
      break;
    case 'stats':
      if (msg.sid === state.sid) onPhoneStats(msg);
      break;
    case 'log':
      log('phone: ' + msg.msg);
      break;
    default:
      break;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
}

// ------------------------------------------------------------------ the desktop stream

/** The player draws the stream; input is handled here (mouse, not touch-as-finger). */
class DeskPlayer extends Player {
  bindInput() {}
}

/** Display size for this window: device pixels, capped, multiples of 16; dpi keeps text a steady physical size. */
function wantedSize() {
  const stage = $('#stage');
  const cssW = Math.max(320, stage.clientWidth);
  const cssH = Math.max(240, stage.clientHeight);
  let w = cssW * devicePixelRatio;
  let h = cssH * devicePixelRatio;
  const f = Math.min(1, settings.maxw / Math.max(w, h));
  w = Math.floor((w * f) / 16) * 16;
  h = Math.floor((h * f) / 16) * 16;
  const dpi = Math.round(Math.min(640, Math.max(80, 160 * settings.scale * (w / cssW))));
  return { w, h, dpi };
}

function startDesktop() {
  if (!state.link) return;
  stopDesktop(true);
  const sid = Date.now() % 1e9;
  state.sid = sid;
  const canvas = $('#screen');
  state.player = new DeskPlayer(canvas, {
    onInput: () => {},
    onKeyframeRequest: () => state.link?.sendCtl({ t: 'reset', sid }),
    onFirstFrame: () => {
      show('screen');
      canvas.focus();
      setStatus(state.phoneName, 'ok');
    },
    onStats: (s) => {
      state.stats = s;
      updateStatsText();
    },
  });
  state.link.openVideoChannel(sid, (buf) => state.player?.feed(buf));
  const { w, h, dpi } = wantedSize();
  state.size = { w, h, dpi };
  state.link.sendCtl({ t: 'start', sid, pkg: '@desktop', w, h, dpi, fps: settings.fps, bitrate: settings.bitrate * 1e6 });
  state.running = true;
  state.lagSince = 0;
  message(`Opening the desktop…`, 'The phone is creating a screen for this window.');
  log(`desktop start ${w}x${h}/${dpi}`);
}

function stopDesktop(notifyPhone) {
  if (!state.sid) return;
  const sid = state.sid;
  if (notifyPhone) state.link?.sendCtl({ t: 'stop', sid });
  state.link?.closeVideoChannel(sid);
  state.player?.close();
  state.player = null;
  state.sid = 0;
  state.running = false;
  state.tasks = [];
  renderTasks();
  if (pending.timer) cancelAnimationFrame(pending.timer);
  pending.timer = 0;
}

function reconfigure() {
  if (!state.running || !state.link) return;
  const { w, h, dpi } = wantedSize();
  const s = state.size || {};
  if (s.w === w && s.h === h && s.dpi === dpi && s.fps === settings.fps && s.bitrate === settings.bitrate) return;
  state.size = { w, h, dpi, fps: settings.fps, bitrate: settings.bitrate };
  state.link.sendCtl({ t: 'reconfigure', sid: state.sid, w, h, dpi, fps: settings.fps, bitrate: settings.bitrate * 1e6 });
  log(`desktop reconfigure ${w}x${h}/${dpi}`);
}

let resizeTimer = 0;
new ResizeObserver(() => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(reconfigure, 350);
}).observe($('#stage'));

/** The phone's encoder falling behind for a while: drop to a lighter stream once. */
function onPhoneStats(msg) {
  state.phoneLag = msg.lag;
  updateStatsText();
  if (msg.lag > 1200) {
    if (!state.lagSince) state.lagSince = Date.now();
    else if (Date.now() - state.lagSince > 6000 && (settings.maxw > 1280 || settings.fps > 30)) {
      settings.maxw = 1280;
      settings.fps = 30;
      save('pd.settings', settings);
      syncSettingsForm();
      reconfigure();
      toast('The phone was falling behind: switched to a lighter stream (Settings → Sharpness).', 7000);
      state.lagSince = 0;
    }
  } else {
    state.lagSince = 0;
  }
}

function updateStatsText() {
  const s = state.stats;
  if (!s) return;
  const lag = state.phoneLag != null ? ` · phone ${state.phoneLag} ms` : '';
  const text = `${s.size} · ${s.fps} fps · ${(s.kbps / 1000).toFixed(1)} Mbps${lag}`;
  $('#set-stats').textContent = text;
  $('#status').dataset.stats = text;
}

// ------------------------------------------------------------------ mouse

const canvas = $('#screen');
/** Pending pointer move, sent once per animation frame. */
const pending = { move: null, wheel: null, timer: 0 };

function toVideo(e) {
  const p = state.player;
  const r = canvas.getBoundingClientRect();
  const x = Math.round(((e.clientX - r.left) / r.width) * p.videoW);
  const y = Math.round(((e.clientY - r.top) / r.height) * p.videoH);
  return { x: Math.max(0, Math.min(p.videoW - 1, x)), y: Math.max(0, Math.min(p.videoH - 1, y)), w: p.videoW, h: p.videoH };
}

function mods(e) {
  return (e.shiftKey ? 1 : 0) | (e.ctrlKey ? 2 : 0) | (e.altKey ? 4 : 0) | (e.metaKey ? 8 : 0);
}

function flush() {
  pending.timer = 0;
  if (pending.move) {
    state.link?.sendCtl(pending.move);
    pending.move = null;
  }
  if (pending.wheel) {
    const w = pending.wheel;
    pending.wheel = null;
    if (Math.abs(w.dx) > 0.001 || Math.abs(w.dy) > 0.001) state.link?.sendCtl(w);
  }
}
function schedule() {
  if (!pending.timer) pending.timer = requestAnimationFrame(flush);
}

function sendMouse(a, e) {
  if (!state.player?.videoW || !state.sid) return;
  const v = toVideo(e);
  const msg = { t: 'mouse', sid: state.sid, a, b: e.buttons & 31, ...v, m: mods(e) };
  if (a === 'move') {
    pending.move = msg;
    schedule();
  } else {
    flush();
    state.link?.sendCtl(msg);
  }
}

// touch screens and pens on this side: fingers, as on a tablet
const touchIds = new Map();
function touchId(e) {
  let id = touchIds.get(e.pointerId);
  if (id === undefined) {
    const used = new Set(touchIds.values());
    id = 0;
    while (used.has(id)) id++;
    touchIds.set(e.pointerId, id);
  }
  return id;
}
function sendTouch(action, e) {
  if (!state.player?.videoW || !state.sid) return;
  state.link?.sendCtl({ t: 'touch', sid: state.sid, a: action, id: touchId(e), ...toVideo(e) });
}

canvas.addEventListener('pointerdown', (e) => {
  e.preventDefault();
  canvas.focus();
  closeMenu();
  try {
    canvas.setPointerCapture(e.pointerId);
  } catch {
    // ignore
  }
  if (e.pointerType === 'mouse') sendMouse('down', e);
  else sendTouch(0, e);
});
canvas.addEventListener('pointermove', (e) => {
  if (e.pointerType === 'mouse') sendMouse('move', e);
  else if (touchIds.has(e.pointerId)) sendTouch(2, e);
});
const pointerUp = (e) => {
  if (e.pointerType === 'mouse') sendMouse('up', e);
  else if (touchIds.has(e.pointerId)) {
    sendTouch(1, e);
    touchIds.delete(e.pointerId);
  }
};
canvas.addEventListener('pointerup', pointerUp);
canvas.addEventListener('pointercancel', pointerUp);
canvas.addEventListener('pointerleave', (e) => {
  if (e.pointerType === 'mouse' && !e.buttons) sendMouse('leave', e);
});
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
canvas.addEventListener('auxclick', (e) => e.preventDefault());
canvas.addEventListener(
  'wheel',
  (e) => {
    e.preventDefault();
    if (!state.player?.videoW || !state.sid) return;
    // one notch = 100 px (pixel mode, Chrome) or 3 lines
    const k = e.deltaMode === 1 ? 1 / 3 : e.deltaMode === 2 ? 1 : 1 / 100;
    const v = toVideo(e);
    const w = pending.wheel || { t: 'wheel', sid: state.sid, ...v, dx: 0, dy: 0, m: mods(e) };
    w.dx += e.deltaX * k;
    w.dy += -e.deltaY * k;
    Object.assign(w, v);
    pending.wheel = w;
    schedule();
  },
  { passive: false },
);

// ------------------------------------------------------------------ keyboard

let metaAlone = false;

function capturingKeys() {
  return state.running && !$('#screen').hidden && $('#menu').hidden && !$('#settings').open;
}

document.addEventListener('keydown', (e) => {
  if (!$('#pair').hidden) {
    if (/^\d$/.test(e.key)) typeDigit(e.key);
    else if (e.key === 'Backspace') typeDigit('⌫');
    else if (e.key === 'Enter') submitCode();
    return;
  }
  if (!$('#menu').hidden) return menuKey(e);
  if (!capturingKeys()) return;
  if (e.key === 'F11') return; // the browser's own full screen
  // the Windows / Command key alone opens the app menu; with another key it is a modifier
  if (e.key === 'Meta' || e.key === 'OS') {
    metaAlone = true;
    e.preventDefault();
    return;
  }
  metaAlone = false;
  // Ctrl+V pastes this computer's clipboard (the paste event below)
  if ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyV') return;
  e.preventDefault();
  state.link?.sendCtl({ t: 'kb', sid: state.sid, down: true, code: e.code, key: e.key, m: mods(e), r: e.repeat ? 1 : 0 });
});

document.addEventListener('keyup', (e) => {
  if (!capturingKeys() && !(e.key === 'Meta' && metaAlone)) return;
  if (e.key === 'Meta' || e.key === 'OS') {
    if (metaAlone) toggleMenu();
    metaAlone = false;
    e.preventDefault();
    return;
  }
  if ((e.ctrlKey || e.metaKey) && e.code === 'KeyV') return;
  e.preventDefault();
  state.link?.sendCtl({ t: 'kb', sid: state.sid, down: false, code: e.code, key: e.key, m: mods(e), r: 0 });
});

document.addEventListener('paste', (e) => {
  if (!capturingKeys()) return;
  const text = e.clipboardData?.getData('text/plain');
  if (!text) return;
  e.preventDefault();
  state.link?.sendCtl({ t: 'text', sid: state.sid, text: text.slice(0, 20000) });
});

// a lost focus (Alt+Tab away) must not leave a button held on the phone
window.addEventListener('blur', () => {
  metaAlone = false;
});

// ------------------------------------------------------------------ taskbar

function iconImg(pkg) {
  if (state.icons[pkg]) {
    const img = document.createElement('img');
    img.src = state.icons[pkg];
    img.alt = '';
    img.dataset.pkg = pkg;
    return img;
  }
  const ph = document.createElement('div');
  ph.className = 'ph';
  ph.dataset.pkg = pkg;
  return ph;
}

function renderTasks() {
  const box = $('#tasks');
  box.innerHTML = '';
  const topId = state.tasks.find((t) => t.visible)?.id;
  for (const t of state.tasks) {
    const b = document.createElement('div');
    b.className = 'task' + (t.id === topId ? ' top' : '');
    b.title = t.label || t.pkg;
    b.setAttribute('role', 'button');
    b.tabIndex = 0;
    const img = iconImg(t.pkg);
    if (img.tagName === 'DIV') img.style.cssText = 'width:24px;height:24px;border-radius:6px';
    b.appendChild(img);
    const s = document.createElement('span');
    s.textContent = t.label || t.pkg;
    b.appendChild(s);
    const x = document.createElement('button');
    x.className = 'x';
    x.title = 'Close';
    x.textContent = '×';
    x.onclick = (e) => {
      e.stopPropagation();
      state.link?.sendCtl({ t: 'task', sid: state.sid, id: t.id, a: 'close' });
    };
    b.appendChild(x);
    b.onclick = () => {
      state.link?.sendCtl({ t: 'task', sid: state.sid, id: t.id, a: 'focus' });
      canvas.focus();
    };
    b.onauxclick = (e) => {
      if (e.button === 1) state.link?.sendCtl({ t: 'task', sid: state.sid, id: t.id, a: 'close' });
    };
    box.appendChild(b);
  }
}

function key(name) {
  if (!state.sid) return;
  state.link?.sendCtl({ t: 'key', sid: state.sid, k: name });
  canvas.focus();
}
$('#btn-back').onclick = () => key('back');
$('#btn-home').onclick = () => key('home');

$('#btn-full').onclick = toggleFullscreen;
async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else {
      await document.documentElement.requestFullscreen({ navigationUI: 'hide' });
      // in full screen the browser can hand us Esc, Alt+Tab-like keys and Windows-key shortcuts
      try {
        await navigator.keyboard?.lock?.();
      } catch {
        // not supported
      }
    }
  } catch (e) {
    warn('fullscreen: ' + e);
  }
}

// ------------------------------------------------------------------ app menu

let menuSel = 0;
function filteredApps() {
  const q = $('#menu-search').value.trim().toLowerCase();
  return q ? state.apps.filter((a) => a.label.toLowerCase().includes(q) || a.pkg.includes(q)) : state.apps;
}
function renderMenu() {
  const grid = $('#menu-grid');
  grid.innerHTML = '';
  const apps = filteredApps();
  if (menuSel >= apps.length) menuSel = 0;
  apps.forEach((a, i) => {
    const b = document.createElement('button');
    b.className = 'app' + (i === menuSel && $('#menu-search').value ? ' sel' : '');
    b.title = a.label;
    b.appendChild(iconImg(a.pkg));
    const s = document.createElement('span');
    s.textContent = a.label;
    b.appendChild(s);
    b.onclick = () => openApp(a.pkg);
    grid.appendChild(b);
  });
  if (!apps.length) grid.innerHTML = `<p class="muted small">${state.apps.length ? 'No app matches.' : 'Loading apps…'}</p>`;
}
function openApp(pkg) {
  closeMenu();
  if (!state.sid) return;
  state.link?.sendCtl({ t: 'open', sid: state.sid, pkg });
  canvas.focus();
}
function toggleMenu() {
  if ($('#menu').hidden) openMenu();
  else closeMenu();
}
function openMenu() {
  $('#menu').hidden = false;
  $('#btn-apps').classList.add('open');
  $('#menu-search').value = '';
  menuSel = 0;
  renderMenu();
  $('#menu-search').focus();
}
function closeMenu() {
  if ($('#menu').hidden) return;
  $('#menu').hidden = true;
  $('#btn-apps').classList.remove('open');
}
function menuKey(e) {
  if (e.key === 'Escape' || e.key === 'Meta') {
    e.preventDefault();
    closeMenu();
    canvas.focus();
  } else if (e.key === 'Enter') {
    const a = filteredApps()[menuSel];
    if (a) openApp(a.pkg);
  } else if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
    e.preventDefault();
    menuSel = Math.min(filteredApps().length - 1, menuSel + (e.key === 'ArrowDown' ? 4 : 1));
    renderMenu();
  } else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
    e.preventDefault();
    menuSel = Math.max(0, menuSel - (e.key === 'ArrowUp' ? 4 : 1));
    renderMenu();
  }
}
$('#btn-apps').onclick = toggleMenu;
$('#menu-search').addEventListener('input', () => {
  menuSel = 0;
  renderMenu();
});
document.addEventListener('pointerdown', (e) => {
  if (!$('#menu').hidden && !e.target.closest('#menu') && !e.target.closest('#btn-apps')) closeMenu();
});

// ------------------------------------------------------------------ sound

let soundOn = null;
function applySound() {
  $('#btn-sound').classList.toggle('off', !settings.sound);
  if (!state.link || soundOn === settings.sound) return;
  soundOn = settings.sound;
  audio.setEnabled(settings.sound);
  state.link.sendCtl({ t: 'audio', on: settings.sound });
}
$('#btn-sound').onclick = () => {
  settings.sound = !settings.sound;
  save('pd.settings', settings);
  syncSettingsForm();
  applySound();
};

// ------------------------------------------------------------------ settings

function syncSettingsForm() {
  $('#set-maxw').value = String(settings.maxw);
  $('#set-scale').value = String(settings.scale);
  $('#set-bitrate').value = String(settings.bitrate);
  $('#set-fps').value = String(settings.fps);
  $('#set-keyboard').value = settings.keyboard;
  $('#set-sound').checked = settings.sound;
  $('#set-relay').checked = settings.relay;
  $('#btn-forget').hidden = !state.paired;
}
function readSettingsForm() {
  const before = { ...settings };
  settings.maxw = Number($('#set-maxw').value);
  settings.scale = Number($('#set-scale').value);
  settings.bitrate = Number($('#set-bitrate').value);
  settings.fps = Number($('#set-fps').value);
  settings.keyboard = $('#set-keyboard').value;
  settings.sound = $('#set-sound').checked;
  settings.relay = $('#set-relay').checked;
  save('pd.settings', settings);
  if (before.keyboard !== settings.keyboard) state.link?.sendCtl({ t: 'keyboard', mode: settings.keyboard });
  applySound();
  reconfigure();
}
$('#btn-settings').onclick = () => {
  syncSettingsForm();
  $('#settings').showModal();
};
for (const id of ['#set-maxw', '#set-scale', '#set-bitrate', '#set-fps', '#set-keyboard', '#set-sound', '#set-relay']) {
  $(id).addEventListener('change', readSettingsForm);
}
$('#settings').addEventListener('close', () => canvas.focus());
$('#btn-forget').onclick = () => {
  if (!confirm('Unpair this screen? You will need a new code from the phone.')) return;
  state.sig?.send({ t: 'forget' });
  save('pd.token', null);
  setTimeout(() => location.reload(), 300);
};

// ------------------------------------------------------------------ first-run hint

function showHintOnce() {
  if (load('pd.hinted', false)) return;
  save('pd.hinted', true);
  const h = $('#hint');
  h.innerHTML =
    '<b>Apps</b>: the grid button or the Windows key · <b>Back</b>: Esc · <b>Paste</b>: Ctrl+V pastes this computer\'s clipboard · ' +
    'drag a window\'s top bar to move it, its edges to resize';
  h.hidden = false;
  setTimeout(() => (h.hidden = true), 12000);
}

// ------------------------------------------------------------------ start

async function main() {
  buildKeypad();
  renderCode();
  syncSettingsForm();
  const dec = await detectDecoder();
  if (!dec.ok) {
    message('This browser can\'t show the desktop', escapeHtml(dec.reason) + '. Use a current Chrome, Edge or Safari.', { spinner: false });
    setStatus('Unsupported', 'bad');
    return;
  }
  message('Connecting…', '');
  state.sig = new Signaling(onSig, onSigOpen);
  // keep the stream when the tab comes back after sleeping
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && state.sid) state.link?.sendCtl({ t: 'reset', sid: state.sid });
  });
}

main();
