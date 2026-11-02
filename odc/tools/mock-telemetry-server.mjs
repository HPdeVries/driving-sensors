// Mock of the ODC "Telemetry" exposed REST API for LOCAL plugin development only (v0.3.0 native upload).
//
//   node odc/tools/mock-telemetry-server.mjs [--port=8787] [--device-id=dev-1] [--token=secret] [--fail=none|401|409|413|500]
//
// Implements POST /chunks, /events, /heartbeat with the contract of SPEC.md "v0.3.0":
//   - auth: "Authorization: Bearer <token>" + "X-Device-Id" (constant-time compare), else 401; body.deviceId must equal the header, else 400
//   - duplicates: chunk with a known chunkId -> 409; events deduplicated on (sessionId, nativeId, ts)
//   - validation: chunk must be format csv-v1 / encoding gzip+base64, data must base64-decode, gunzip and have the csv-v1 header, else 422
//   - fail mode forces a status (after auth is skipped) for every POST: 401, 409, 413, 500. Change at runtime with
//     POST /__mode?fail=500 (no auth) and read the state with GET /__state.
//
// It speaks plain HTTP. The plugin itself REQUIRES https (configureUpload rejects http), so for a real device expose
// this server through an https tunnel, e.g. `cloudflared tunnel --url http://localhost:8787` or `ngrok http 8787`,
// and use https://<tunnel-host> as EndpointBaseUrl (the mock ignores any path prefix before /chunks, /events, /heartbeat).
// Do NOT use this in production: tokens are plain CLI arguments.
import http from 'node:http';
import zlib from 'node:zlib';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const CSV_HEADER = 't_ms,sensor,x,y,z,v4,v5';
const SENSORS = new Set(['acc', 'gyr', 'mag', 'bar', 'loc']);

function parseArgs(argv) {
  const o = {};
  for (const a of argv) {
    const m = /^--([^=]+)=(.*)$/.exec(a);
    if (m) o[m[1]] = m[2];
  }
  return o;
}

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

/** Decode and validate a chunk's data. Returns {ok, error?, lines?, sensors?}. */
export function validateChunkData(chunk) {
  if (chunk.format !== 'csv-v1') return { ok: false, error: 'format must be csv-v1' };
  if (chunk.encoding !== 'gzip+base64') return { ok: false, error: 'encoding must be gzip+base64' };
  if (typeof chunk.data !== 'string' || chunk.data.length === 0) return { ok: false, error: 'data missing' };
  let csv;
  try {
    csv = zlib.gunzipSync(Buffer.from(chunk.data, 'base64')).toString('utf8');
  } catch (e) {
    return { ok: false, error: 'data is not gzip+base64: ' + e.message };
  }
  const lines = csv.split('\n').filter((l) => l.length > 0);
  if (lines[0] !== CSV_HEADER) return { ok: false, error: 'csv header must be ' + CSV_HEADER };
  const sensors = {};
  for (const l of lines.slice(1)) {
    const p = l.split(',');
    if (p.length !== 7 || !SENSORS.has(p[1]) || !/^\d+$/.test(p[0])) return { ok: false, error: 'bad csv row: ' + l.slice(0, 60) };
    sensors[p[1]] = (sensors[p[1]] || 0) + 1;
  }
  return { ok: true, lines: lines.length - 1, sensors };
}

export function createMockServer(opts = {}) {
  const cfg = {
    deviceId: opts.deviceId || 'dev-1',
    token: opts.token || 'secret-token',
    fail: opts.fail || 'none',
    log: opts.log !== false,
  };
  const state = { chunks: new Map(), events: new Map(), heartbeats: [], requests: 0 };
  const log = (...a) => cfg.log && console.log(new Date().toISOString(), ...a);

  function send(res, status, body) {
    const txt = JSON.stringify(body ?? {});
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(txt) });
    res.end(txt);
  }

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/__mode' && req.method === 'POST') {
      cfg.fail = url.searchParams.get('fail') || 'none';
      log('fail mode ->', cfg.fail);
      return send(res, 200, { fail: cfg.fail });
    }
    if (url.pathname === '/__state' && req.method === 'GET') {
      return send(res, 200, { fail: cfg.fail, chunks: [...state.chunks.keys()], events: state.events.size, heartbeats: state.heartbeats.length, requests: state.requests });
    }
    const route = ['chunks', 'events', 'heartbeat'].find((r) => url.pathname.endsWith('/' + r));
    if (req.method !== 'POST' || !route) return send(res, 404, { error: 'not found' });

    const parts = [];
    let size = 0;
    req.on('data', (c) => { size += c.length; parts.push(c); });
    req.on('end', () => {
      state.requests++;
      const tag = `${req.method} /${route} ${size}B`;
      const auth = req.headers['authorization'] || '';
      const hdrDevice = req.headers['x-device-id'] || '';
      const bearer = auth.startsWith('Bearer ') ? auth.slice(7) : '';
      // never log the token, only whether it matched
      const authOk = !!bearer && hdrDevice === cfg.deviceId && safeEqual(bearer, cfg.token);

      if (cfg.fail === '401') { log(tag, '-> 401 (forced)'); return send(res, 401, { error: 'unauthorized' }); }
      if (cfg.fail === '409') { log(tag, '-> 409 (forced)'); return send(res, 409, { error: 'duplicate' }); }
      if (cfg.fail === '413') { log(tag, '-> 413 (forced)'); return send(res, 413, { error: 'too large' }); }
      if (cfg.fail === '500') { log(tag, '-> 500 (forced)'); return send(res, 500, { error: 'boom' }); }
      if (!authOk) { log(tag, '-> 401 (auth)'); return send(res, 401, { error: 'unauthorized' }); }

      let body;
      try { body = JSON.parse(Buffer.concat(parts).toString('utf8')); } catch { log(tag, '-> 400 (json)'); return send(res, 400, { error: 'invalid json' }); }
      if (body.deviceId !== hdrDevice) { log(tag, '-> 400 (deviceId mismatch)'); return send(res, 400, { error: 'deviceId does not match X-Device-Id' }); }

      if (route === 'chunks') {
        if (!body.chunkId) return send(res, 400, { error: 'chunkId missing' });
        if (state.chunks.has(body.chunkId)) { log(tag, body.chunkId, '-> 409 (duplicate)'); return send(res, 409, { error: 'duplicate' }); }
        const v = validateChunkData(body);
        if (!v.ok) { log(tag, body.chunkId, '-> 422', v.error); return send(res, 422, { error: v.error }); }
        state.chunks.set(body.chunkId, { meta: { ...body, data: undefined }, rows: v.lines, sensors: v.sensors });
        log(tag, body.chunkId, `-> 200 rows=${v.lines}`, JSON.stringify(v.sensors), 'platform=' + body.platform);
        return send(res, 200, { ok: true });
      }
      if (route === 'events') {
        if (!Array.isArray(body.events)) return send(res, 422, { error: 'events must be an array' });
        let added = 0;
        for (const e of body.events) {
          const key = [body.deviceId, e.sessionId, e.id, e.ts].join('|');
          if (!state.events.has(key)) { state.events.set(key, e); added++; }
        }
        log(tag, `-> 200 received=${body.events.length} new=${added}`);
        return send(res, 200, { ok: true, added });
      }
      // heartbeat
      if (typeof body.seq !== 'number' || typeof body.ts !== 'number') return send(res, 422, { error: 'seq/ts missing' });
      state.heartbeats.push(body);
      log(tag, `-> 200 seq=${body.seq} up=${body.processUptimeSec}s app=${body.appState} bat=${body.batteryLevel} net=${body.network} upload=${body.uploadState}`);
      return send(res, 200, { ok: true });
    });
  });

  return { server, cfg, state };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const a = parseArgs(process.argv.slice(2));
  const { server, cfg } = createMockServer({ deviceId: a['device-id'], token: a.token, fail: a.fail });
  const port = Number(a.port || 8787);
  server.listen(port, () => {
    console.log(`mock telemetry server on http://localhost:${port}  deviceId=${cfg.deviceId} fail=${cfg.fail}`);
    console.log('The plugin requires https: use a tunnel (cloudflared / ngrok) for real devices. Token is not printed.');
  });
}
