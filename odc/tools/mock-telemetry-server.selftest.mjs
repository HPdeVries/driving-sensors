// Self-test for mock-telemetry-server.mjs. Usage: node odc/tools/mock-telemetry-server.selftest.mjs
import assert from 'node:assert/strict';
import zlib from 'node:zlib';
import { createMockServer } from './mock-telemetry-server.mjs';

const { server, state } = createMockServer({ deviceId: 'dev-1', token: 'tok', log: false });
await new Promise((r) => server.listen(0, r));
const base = `http://localhost:${server.address().port}/DriveMe/rest/Telemetry`;

const csv = ['t_ms,sensor,x,y,z,v4,v5', '1793367101003,acc,0.1,-0.04,9.8,,', '1793367101000,loc,52.09,5.12,4.2,13.4,4.8'].join('\n') + '\n';
const data = zlib.gzipSync(csv).toString('base64');
const chunk = (o = {}) => ({ chunkId: 'r1-000001', recordingId: 'r1', seq: 1, startTs: 1, endTs: 2, bytes: 10, format: 'csv-v1', encoding: 'gzip+base64', platform: 'android', deviceId: 'dev-1', data, ...o });
const post = async (path, body, { token = 'tok', device = 'dev-1' } = {}) => {
  const r = await fetch(base + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: token ? `Bearer ${token}` : '', 'X-Device-Id': device },
    body: JSON.stringify(body),
  });
  return r.status;
};
const mode = (fail) => fetch(base.replace('/DriveMe/rest/Telemetry', '') + '/__mode?fail=' + fail, { method: 'POST' });

let n = 0;
const t = async (name, fn) => { await fn(); n++; console.log('ok -', name); };

try {
  await t('chunk accepted (csv-v1 decodes)', async () => { assert.equal(await post('/chunks', chunk()), 200); assert.equal(state.chunks.get('r1-000001').rows, 2); });
  await t('duplicate chunk -> 409', async () => assert.equal(await post('/chunks', chunk()), 409));
  await t('wrong token -> 401', async () => assert.equal(await post('/chunks', chunk({ chunkId: 'x' }), { token: 'bad' }), 401));
  await t('wrong device header -> 401', async () => assert.equal(await post('/chunks', chunk({ chunkId: 'x', deviceId: 'other' }), { device: 'other' }), 401));
  await t('body deviceId mismatch -> 400', async () => assert.equal(await post('/chunks', chunk({ chunkId: 'y', deviceId: 'other' })), 400));
  await t('bad gzip -> 422', async () => assert.equal(await post('/chunks', chunk({ chunkId: 'z', data: Buffer.from('nope').toString('base64') })), 422));
  await t('bad csv header -> 422', async () => assert.equal(await post('/chunks', chunk({ chunkId: 'h', data: zlib.gzipSync('a,b\n1,2\n').toString('base64') })), 422));
  await t('wrong format -> 422', async () => assert.equal(await post('/chunks', chunk({ chunkId: 'f', format: 'csv-v2' })), 422));
  const ev = (id) => ({ id, ts: 1000 + id, type: 'LOCATION', sessionId: 's1' });
  await t('events accepted', async () => { assert.equal(await post('/events', { deviceId: 'dev-1', platform: 'ios', pluginVersion: '0.3.0', events: [ev(1), ev(2)] }), 200); assert.equal(state.events.size, 2); });
  await t('events deduplicated', async () => { await post('/events', { deviceId: 'dev-1', events: [ev(2), ev(3)] }); assert.equal(state.events.size, 3); });
  await t('heartbeat accepted', async () => { assert.equal(await post('/heartbeat', { deviceId: 'dev-1', ts: Date.now(), seq: 1, processUptimeSec: 5, appState: 'background', batteryLevel: 80, network: 'wifi', uploadState: 'idle' }), 200); assert.equal(state.heartbeats.length, 1); });
  await t('heartbeat without seq -> 422', async () => assert.equal(await post('/heartbeat', { deviceId: 'dev-1', ts: 1 }), 422));
  for (const code of [401, 409, 413, 500]) {
    await t(`fail mode ${code}`, async () => { await mode(code); assert.equal(await post('/events', { deviceId: 'dev-1', events: [] }), code); await mode('none'); });
  }
  await t('unknown path -> 404', async () => assert.equal(await post('/nope', {}), 404));
  console.log(`\nall ${n} checks passed`);
} catch (e) {
  console.error('FAILED:', e.message);
  process.exitCode = 1;
} finally {
  server.close();
}
