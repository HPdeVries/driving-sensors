// Synthetic drive (tilted phone, 3 s brake, 2 s acceleration, bump, 10 s gap) for RecBuildSeries.
// Usage: node drive-test.mjs <recording-viewer dir> [TAU override]
import fs from 'node:fs'; import zlib from 'node:zlib'; import vm from 'node:vm';
const [dir, tauArg] = [process.argv[2], process.argv[3]];
let seed = 7; const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd());
// phone tilted 30 deg about x: gravity on y and z; car forward axis = device x
const G = 9.80665, th = Math.PI / 6, g = [0, G * Math.sin(th), G * Math.cos(th)];
const up = [0, Math.sin(th), Math.cos(th)];
const t0 = 1793367100000, rows = [];
let speed = 15;
for (let i = 0; i < 120 * 100; i++) {
  const t = t0 + i * 10, sec = i / 100;
  if (sec >= 70 && sec < 80) continue;               // 10 s gap (process killed)
  let fwd = 0, vert = 0;
  if (sec >= 20 && sec < 23) fwd = -3;               // braking 3 m/s2 for 3 s
  if (sec >= 40 && sec < 42) fwd = 2;                // accelerating 2 m/s2 for 2 s
  if (sec >= 50 && sec < 50.2) vert = 4;             // bump 4 m/s2
  const a = [fwd + gauss() * 0.1, g[1] + vert * up[1] + gauss() * 0.1, g[2] + vert * up[2] + gauss() * 0.1];
  rows.push(`${t},acc,${a.map((v) => v.toFixed(6)).join(',')},,`);
  if (i % 100 === 0) { speed = Math.max(0, speed + fwd); rows.push(`${t},loc,${(52.09 + sec * 1e-4).toFixed(8)},${(5.12 + sec * 1e-4).toFixed(8)},4,${speed.toFixed(2)},5`); }
}
const csv = 't_ms,sensor,x,y,z,v4,v5\n' + rows.join('\n') + '\n';
let src = fs.readFileSync(`${dir}/RecBuildSeries.js`, 'utf8');
if (tauArg) src = src.replace(/var TAU = \d+;/, `var TAU = ${tauArg};`);
const window = {}; const ctx = vm.createContext({ window, Blob, Response, DecompressionStream, Uint8Array, atob, JSON, Math, Infinity, isFinite, encodeURIComponent, String });
const run = (code, params) => new Promise((res) => { ctx.$parameters = params; ctx.$resolve = () => res(params); vm.runInContext(code, ctx); if (!/\$resolve/.test(code)) res(params); });
await run(fs.readFileSync(`${dir}/RecReset.js`, 'utf8'), {});
await run(fs.readFileSync(`${dir}/RecAppendChunk.js`, 'utf8'), { Base64: zlib.gzipSync(Buffer.from(csv)).toString('base64') });
const r = await run(src, { MaxPoints: 0, GapMs: 0 });
const pts = JSON.parse(r.AccelPointsJson).filter((p) => p.DataSeriesName === 'Horizontal');
const at = (from, to) => Math.max(...pts.filter((p) => +p.Label >= from && +p.Label < to).map((p) => p.Value));
console.log(`TAU=${tauArg || 'file'} summary`, r.SummaryJson);
console.log('  horizontal max: brake 20-21s', at(20, 21), '| brake 22-23s', at(22, 23), '| accel 40-42s', at(40, 42), '| steady 30-35s', at(30, 35), '| bump 50s', at(50, 50.3));
console.log('  gaps', r.GapsJson, '| axes series', [...new Set(JSON.parse(r.AxesPointsJson).map((p) => p.DataSeriesName))].join(','));
const segs = JSON.parse(r.SpeedTrackJson);
console.log('  speed segments', segs.map((s) => `${s.Color}x${s.Points.length}`).join(' '));
for (let i = 1; i < segs.length; i++) { const a = segs[i - 1].Points.at(-1), b = segs[i].Points[0]; if (a.Latitude !== b.Latitude || a.Longitude !== b.Longitude) throw new Error('segments not joined'); }
