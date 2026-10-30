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

let timer;
function startTimer() {
  stopTimer();
  refreshStatus();
  timer = setInterval(refreshStatus, 5000);
}
function stopTimer() {
  if (timer) clearInterval(timer);
  timer = undefined;
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
