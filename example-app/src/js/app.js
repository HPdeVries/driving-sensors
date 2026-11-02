import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { Share } from '@capacitor/share';
import { DrivingSensors } from 'driving-sensors';

const STORE_KEY = 'drivingSensors.events.v1';
const ACK_KEY = 'drivingSensors.lastAckedId.v1';
const $ = (id) => document.getElementById(id);

const stored = () => {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) || '[]');
  } catch {
    return [];
  }
};
const saveStored = (events) => localStorage.setItem(STORE_KEY, JSON.stringify(events));
const lastAcked = () => Number(localStorage.getItem(ACK_KEY) || 0);

function log(msg, obj) {
  const line = `${new Date().toLocaleTimeString()} ${msg}${obj !== undefined ? ' ' + JSON.stringify(obj) : ''}`;
  $('log').textContent = line + '\n' + $('log').textContent.slice(0, 4000);
  console.log(line);
}

async function call(label, fn) {
  try {
    const r = await fn();
    log(label + ' ok', r);
    return r;
  } catch (e) {
    log(label + ' ERROR', { code: e.code, message: e.message });
    return undefined;
  }
}

async function showPerms(fn, label) {
  const r = await call(label, fn);
  if (r) $('perm').textContent = JSON.stringify(r, null, 1);
}

function options() {
  const num = (id) => Number($(id).value);
  return {
    harshBrakeMps2: num('harshBrakeMps2'),
    harshAccelMps2: num('harshAccelMps2'),
    locationIntervalMs: num('locationIntervalMs'),
    accelSampleHz: num('accelSampleHz'),
    tripStartSpeedMps: num('tripStartSpeedMps'),
    tripEndIdleMs: num('tripEndIdleMs'),
    minEventGapMs: num('minEventGapMs'),
  };
}

async function showPluginVersion() {
  try {
    const v = await DrivingSensors.getPluginVersion();
    $('pluginVersion').textContent = 'Native plugin version: ' + v.version + ' (' + v.platform + ')';
  } catch (e) {
    $('pluginVersion').textContent = 'Native plugin version: unknown (' + (e.code || e.message) + '; native older than 0.1.2?)';
  }
}

async function refreshStatus() {
  try {
    const s = await DrivingSensors.getStatus();
    $('status').textContent = JSON.stringify(s) + '  @' + new Date().toLocaleTimeString();
    const l = s.lastLocation;
    $('statusDetail').textContent =
      'serviceRunning: ' + s.serviceRunning + ' | lastLocation: ' +
      (l ? fmtTime(l.ts) + ' ' + kmh(l.speedMps) + ' km/h, acc ' + (l.accuracyM != null ? l.accuracyM.toFixed(1) : '?') + ' m' : 'none');
  } catch (e) {
    $('status').textContent = 'ERROR ' + (e.code || '') + ' ' + e.message;
  }
}

async function showReadings(fn, label) {
  $('readingsDetail').textContent = 'working...';
  const r = await call(label, fn);
  if (!r) {
    $('readingsDetail').textContent = 'error, see log';
    return;
  }
  $('readings').textContent = JSON.stringify(r, null, 1);
  const parts = [];
  if (r.location) {
    const l = r.location;
    parts.push(
      `location ${fmtTime(l.ts)}: ${l.lat?.toFixed(5)},${l.lon?.toFixed(5)} ${kmh(l.speedMps)} km/h, acc ${l.accuracyM?.toFixed(1)} m`,
    );
  } else parts.push('no location');
  if (r.motion) {
    const m = r.motion;
    parts.push(`motion: horizontal ${m.horizontalMps2?.toFixed(2)} m/s2, peak ${m.peakHorizontalMps2?.toFixed(2)} m/s2 (${m.samples} samples)`);
  } else parts.push('no motion');
  $('readingsDetail').textContent = parts.join(' | ');
}

async function fetchEvents() {
  const all = stored();
  let afterId = all.length ? all[all.length - 1].id : 0;
  let added = 0;
  for (let i = 0; i < 50; i++) {
    const r = await call('getBufferedEvents(after ' + afterId + ')', () =>
      DrivingSensors.getBufferedEvents({ afterId, limit: 500 }),
    );
    if (!r || !r.events.length) break;
    all.push(...r.events);
    added += r.events.length;
    afterId = r.events[r.events.length - 1].id;
    if (r.events.length < 500) break;
  }
  saveStored(all);
  log('fetched ' + added + ', stored total ' + all.length);
  render();
}

async function ackFetched() {
  const all = stored();
  if (!all.length) return log('nothing to ack');
  const upToId = all[all.length - 1].id;
  try {
    await DrivingSensors.ackEvents({ upToId });
    log('ackEvents(' + upToId + ') ok');
    localStorage.setItem(ACK_KEY, String(upToId));
    refreshStatus();
  } catch (e) {
    log('ackEvents ERROR', { code: e.code, message: e.message });
  }
}

async function exportJson() {
  const payload = JSON.stringify(
    { exportedAt: new Date().toISOString(), platform: Capacitor.getPlatform(), lastAckedId: lastAcked(), events: stored() },
    null,
    1,
  );
  const name = 'driving-sensors-' + new Date().toISOString().replace(/[:.]/g, '-') + '.json';
  try {
    if (Capacitor.isNativePlatform()) {
      const f = await Filesystem.writeFile({ path: name, data: payload, directory: Directory.Cache, encoding: Encoding.UTF8 });
      await Share.share({ title: name, url: f.uri });
    } else {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([payload], { type: 'application/json' }));
      a.download = name;
      a.click();
    }
    log('exported ' + name);
  } catch (e) {
    log('export ERROR', { message: e.message });
  }
}

const fmtTime = (ts) => new Date(ts).toLocaleTimeString();
const kmh = (v) => (v == null ? '' : (v * 3.6).toFixed(1));

function render() {
  const all = stored();
  const counts = {};
  all.forEach((e) => (counts[e.type] = (counts[e.type] || 0) + 1));
  $('counts').innerHTML = ['LOCATION', 'HARSH_BRAKE', 'HARSH_ACCEL', 'TRIP_START', 'TRIP_END']
    .map((t) => `<span>${t}: <b>${counts[t] || 0}</b></span>`)
    .join('');

  const harsh = all.filter((e) => e.type.startsWith('HARSH_')).slice(-3).reverse();
  $('harshBox').innerHTML = harsh
    .map(
      (e) =>
        `<div style="font-size:16px;font-weight:bold;color:#e33">${e.type} ${fmtTime(e.ts)} peak ${e.peakMps2?.toFixed(2)} m/s2 @ ${kmh(e.speedMps)} km/h</div>`,
    )
    .join('');

  $('events').innerHTML = all
    .slice(-50)
    .reverse()
    .map(
      (e) =>
        `<tr class="${e.type.startsWith('HARSH_') ? 'harsh' : ''}"><td>${e.id}</td><td>${fmtTime(e.ts)}</td><td>${e.type}</td><td>${kmh(e.speedMps)}</td><td>${e.peakMps2 != null ? e.peakMps2.toFixed(2) : ''}</td><td>${e.lat != null ? e.lat.toFixed(5) + ',' + e.lon.toFixed(5) : ''}</td></tr>`,
    )
    .join('');
}

$('btnCheck').onclick = () => showPerms(() => DrivingSensors.checkPermissions(), 'checkPermissions');
$('btnLoc').onclick = () =>
  showPerms(() => DrivingSensors.requestPermissions({ permissions: ['location'] }), 'requestPermissions(location)');
$('btnBg').onclick = () =>
  showPerms(() => DrivingSensors.requestPermissions({ permissions: ['backgroundLocation'] }), 'requestPermissions(backgroundLocation)');
$('btnMotion').onclick = () =>
  showPerms(() => DrivingSensors.requestPermissions({ permissions: ['motion', 'notifications'] }), 'requestPermissions(motion,notifications)');
$('btnStart').onclick = async () => {
  await call('startTracking', () => DrivingSensors.startTracking(options()));
  refreshStatus();
};
$('btnStop').onclick = async () => {
  await call('stopTracking', () => DrivingSensors.stopTracking());
  refreshStatus();
};
$('btnStatus').onclick = refreshStatus;
$('btnLast').onclick = () => showReadings(() => DrivingSensors.getLastReadings(), 'getLastReadings');
$('btnCurrent').onclick = () => showReadings(() => DrivingSensors.getCurrentReadings(), 'getCurrentReadings');
$('btnFetch').onclick = fetchEvents;
$('btnAck').onclick = ackFetched;
$('btnExport').onclick = exportJson;
$('btnClear').onclick = () => {
  if (confirm('Delete all locally stored events? (Native buffer is not touched.)')) {
    saveStored([]);
    render();
  }
};

// ---- Recording (v0.2.0) ----
const REC_TARGET = { acc: 100, gyr: 100, mag: 50, bar: 10, loc: 1 };
const SENSORS = Object.keys(REC_TARGET);
const mb = (b) => ((b || 0) / 1048576).toFixed(2) + ' MB';
const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

async function refreshRecording() {
  try {
    const s = await DrivingSensors.getRecordingStatus();
    $('recStatus').textContent =
      (s.recording ? 'RECORDING ' + (s.recordingId || '') : 'not recording') +
      ' | chunksPending: ' + s.chunksPending + ' | bytesPending: ' + mb(s.bytesPending) +
      (s.stopReason ? ' | stopReason: ' + s.stopReason : '') +
      '  @' + new Date().toLocaleTimeString();
    $('recRates').innerHTML = SENSORS.map((k) => {
      const hz = s.measuredHz ? s.measuredHz[k] : undefined;
      const bad = s.recording && hz != null && hz < REC_TARGET[k] * 0.8;
      return `<tr class="${bad ? 'harsh' : ''}"><td>${k}</td><td>${hz != null ? hz.toFixed(1) : '-'}</td><td>${REC_TARGET[k]}</td><td>${s.available ? s.available[k] : '?'}</td></tr>`;
    }).join('');
  } catch (e) {
    $('recStatus').textContent = 'ERROR ' + (e.code || '') + ' ' + e.message;
  }
}

async function listChunks() {
  const r = await call('listRecordingChunks', () => DrivingSensors.listRecordingChunks({ limit: 100 }));
  if (!r) return;
  $('recChunks').innerHTML = r.chunks
    .map((c) => {
      const n = c.counts || {};
      return `<tr><td>${c.seq}</td><td>${fmtTime(c.startTs)}-${fmtTime(c.endTs)}</td><td>${(c.bytes / 1024).toFixed(0)}</td><td>${SENSORS.map((k) => n[k] ?? 0).join('/')}</td>` +
        `<td><button data-dl="${esc(c.chunkId)}">Download</button> <button data-ack="${esc(c.chunkId)}">Ack</button></td></tr>`;
    })
    .join('');
}

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function gunzipToText(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  return await new Response(stream).text();
}

async function downloadChunk(chunkId) {
  $('recCsv').textContent = 'fetching ' + chunkId + '...';
  const c = await call('getRecordingChunk', () => DrivingSensors.getRecordingChunk({ chunkId }));
  if (!c) {
    $('recCsv').textContent = 'error, see log';
    return;
  }
  try {
    const gz = b64ToBytes(c.data);
    const csv = await gunzipToText(gz);
    const lines = csv.split('\n');
    if (lines[lines.length - 1] === '') lines.pop();
    const counts = {};
    for (let i = 1; i < lines.length; i++) {
      const s = lines[i].split(',', 2)[1];
      counts[s] = (counts[s] || 0) + 1;
    }
    const meta = { ...c, data: undefined };
    $('recCsv').textContent =
      `gzip ${gz.length} B (reported ${c.bytes}), csv ${csv.length} chars, ${lines.length - 1} samples\n` +
      'per-sensor counts (csv): ' + JSON.stringify(counts) + '\n' +
      'per-sensor counts (meta): ' + JSON.stringify(c.counts) + '\n' +
      'meta: ' + JSON.stringify(meta) + '\n\n' +
      lines.slice(0, 20).join('\n');
  } catch (e) {
    $('recCsv').textContent = 'decode ERROR ' + e.message;
    log('chunk decode ERROR', { message: e.message });
  }
}

$('btnRecStart').onclick = async () => {
  await call('startRecording', () => DrivingSensors.startRecording());
  refreshRecording();
};
$('btnRecStop').onclick = async () => {
  await call('stopRecording', () => DrivingSensors.stopRecording());
  refreshRecording();
  listChunks();
};
$('btnRecList').onclick = listChunks;
$('recChunks').onclick = async (ev) => {
  const t = ev.target;
  if (!(t instanceof HTMLElement)) return;
  if (t.dataset.dl) downloadChunk(t.dataset.dl);
  else if (t.dataset.ack) {
    await call('ackRecordingChunk', () => DrivingSensors.ackRecordingChunk({ chunkId: t.dataset.ack }));
    listChunks();
    refreshRecording();
  }
};

// ---- Upload + heartbeat (v0.3.0) ----
const UP_KEY = 'drivingSensors.upload.v1'; // non-secret fields only; the token is never saved by the app
try {
  const saved = JSON.parse(localStorage.getItem(UP_KEY) || '{}');
  if (saved.endpoint) $('upEndpoint').value = saved.endpoint;
  if (saved.deviceId) $('upDeviceId').value = saved.deviceId;
} catch {
  /* ignore */
}
const ago = (ms) => (ms ? Math.round((Date.now() - ms) / 1000) + ' s ago' : '-');

async function refreshUpload() {
  try {
    const s = await DrivingSensors.getUploadStatus();
    $('upStatus').textContent =
      `${s.configured ? 'configured' : 'NOT configured'} | state ${s.state}` +
      ` | pending: ${s.pendingChunks} chunks, ${s.pendingEvents} events` +
      ` | rejected ${s.rejectedChunks} | uploaded ${s.uploadedChunks} chunks, ${s.uploadedEvents} events` +
      ` | last ok ${ago(s.lastSuccessAt)}` +
      (s.lastError ? ` | last error "${s.lastError}" (HTTP ${s.lastHttpStatus ?? '-'}) ${ago(s.lastErrorAt)}` : '') +
      (s.nextAttemptAt ? ` | next attempt in ${Math.max(0, Math.round((s.nextAttemptAt - Date.now()) / 1000))} s` : '');
    $('upRaw').textContent = JSON.stringify(s, null, 1);
  } catch (e) {
    $('upStatus').textContent = 'ERROR ' + (e.code || '') + ' ' + e.message;
  }
}
async function refreshHeartbeat() {
  try {
    const s = await DrivingSensors.getHeartbeatStatus();
    $('hbStatus').textContent =
      (s.running ? `RUNNING every ${s.intervalSec} s` : 'stopped') +
      ` | sent ${s.sent}, ok ${s.succeeded} | last sent ${ago(s.lastSentAt)}, last ok ${ago(s.lastSuccessAt)}` +
      (s.lastError ? ` | error "${s.lastError}" (HTTP ${s.lastHttpStatus ?? '-'})` : '');
  } catch (e) {
    $('hbStatus').textContent = 'ERROR ' + (e.code || '') + ' ' + e.message;
  }
}

$('btnUpConfigure').onclick = async () => {
  const endpoint = $('upEndpoint').value.trim();
  const deviceId = $('upDeviceId').value.trim();
  const opts = {
    endpointBaseUrl: endpoint,
    deviceId,
    token: $('upToken').value,
    uploadRecordings: $('upRec').checked,
    uploadEvents: $('upEvents').checked,
    wifiOnly: $('upWifi').checked,
  };
  const n = Number($('upBatch').value);
  if (n > 0) opts.eventBatchSize = n;
  await call('configureUpload', () => DrivingSensors.configureUpload(opts));
  try {
    localStorage.setItem(UP_KEY, JSON.stringify({ endpoint, deviceId }));
  } catch {
    /* ignore */
  }
  $('upToken').value = '';
  refreshUpload();
};
$('btnUpClear').onclick = async () => {
  await call('clearUpload', () => DrivingSensors.clearUpload());
  refreshUpload();
};
$('btnUpNow').onclick = async () => {
  await call('uploadNow', () => DrivingSensors.uploadNow());
  refreshUpload();
};
$('btnHbStart').onclick = async () => {
  const n = Number($('hbInterval').value);
  await call('startHeartbeat', () => DrivingSensors.startHeartbeat(n > 0 ? { intervalSec: n } : undefined));
  refreshHeartbeat();
};
$('btnHbStop').onclick = async () => {
  await call('stopHeartbeat', () => DrivingSensors.stopHeartbeat());
  refreshHeartbeat();
};

let timer;
let recTimer;
let upTimer;
function startTimer() {
  stopTimer();
  showPluginVersion();
  refreshStatus();
  timer = setInterval(refreshStatus, 5000);
  refreshRecording();
  recTimer = setInterval(refreshRecording, 2000);
  const refreshUp = () => { refreshUpload(); refreshHeartbeat(); };
  refreshUp();
  upTimer = setInterval(refreshUp, 2000);
}
function stopTimer() {
  if (timer) clearInterval(timer);
  timer = undefined;
  if (recTimer) clearInterval(recTimer);
  recTimer = undefined;
  if (upTimer) clearInterval(upTimer);
  upTimer = undefined;
}

App.addListener('appStateChange', ({ isActive }) => {
  if (isActive) {
    startTimer();
    fetchEvents();
  } else {
    stopTimer();
  }
});

render();
startTimer();
log('ready, platform ' + Capacitor.getPlatform());
