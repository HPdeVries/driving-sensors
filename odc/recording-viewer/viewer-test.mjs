import fs from 'node:fs'; import zlib from 'node:zlib'; import vm from 'node:vm';
// Usage: node estimate-chunk-size.mjs 60 --write=/tmp/x/chunk1.csv; node viewer-test.mjs <recording-viewer dir> /tmp/x
const dir = process.argv[2], S = process.argv[3];
const c1 = fs.readFileSync(`${S}/chunk1.csv`, 'utf8');
// chunk 2 = same data shifted 60 s later
const c2 = c1.split('\n').map((l, i) => i === 0 || !l ? l : l.replace(/^(\d+)/, (m) => String(+m + 60000))).join('\n');
const b64 = (csv) => zlib.gzipSync(Buffer.from(csv)).toString('base64');
let downloaded = null;
const window = {};
const document = { body: { appendChild() {}, removeChild() {} }, createElement: () => ({ click() { downloaded = this; } }) };
const ctx = vm.createContext({ window, document, Blob, Response, DecompressionStream, Uint8Array, atob, URL: { createObjectURL: (b) => { ctx.lastBlob = b; return 'blob:x'; }, revokeObjectURL() {} }, setTimeout, JSON, Math, Infinity, isFinite, encodeURIComponent, String });
const run = (name, params) => new Promise((resolve) => {
  ctx.$parameters = params; ctx.$resolve = () => resolve(params);
  vm.runInContext(fs.readFileSync(`${dir}/${name}.js`, 'utf8'), ctx);
  if (!/\$resolve/.test(fs.readFileSync(`${dir}/${name}.js`, 'utf8'))) resolve(params);
});
await run('RecReset', {});
console.log('append1', JSON.stringify((({Base64,...r})=>r)(await run('RecAppendChunk', { Base64: b64(c1) }))));
console.log('append2', JSON.stringify((({Base64,...r})=>r)(await run('RecAppendChunk', { Base64: b64(c2) }))));
console.log('bad    ', JSON.stringify((({Base64,...r})=>r)(await run('RecAppendChunk', { Base64: 'bm90IGd6aXA=' }))));
const d = await run('RecDownloadCsv', { FileName: 'rec.csv' });
const csvOut = await ctx.lastBlob.text();
console.log('download bytes', d.Bytes, 'file', downloaded.download, 'headers', (csvOut.match(/^t_ms/gm) || []).length, 'lines', csvOut.split('\n').length - 2);
const s = await run('RecBuildSeries', { MaxPoints: 0 });
const acc = JSON.parse(s.AccelPointsJson), spd = JSON.parse(s.SpeedPointsJson), tr = JSON.parse(s.TrackJson);
const series = {}; acc.forEach((p) => series[p.DataSeriesName] = (series[p.DataSeriesName] || 0) + 1);
console.log('accel series', JSON.stringify(series), 'sample', JSON.stringify(acc.slice(0, 4)));
console.log('speed pts', spd.length, 'sample', JSON.stringify(spd[0]), '| track pts', tr.length, JSON.stringify(tr[0]));
console.log('summary', s.SummaryJson);
console.log('maps', s.GoogleMapsUrl.slice(0, 160) + '...', 'waypoints', (decodeURIComponent(s.GoogleMapsUrl).match(/\|/g) || []).length + 1);
await run('RecReset', {}); const e = await run('RecBuildSeries', { MaxPoints: 0 });
console.log('empty ->', e.AccelPointsJson, e.SpeedPointsJson, e.TrackJson, JSON.stringify(e.GoogleMapsUrl), e.SummaryJson);
