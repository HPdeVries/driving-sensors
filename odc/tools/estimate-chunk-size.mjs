// Estimates the size of a raw sensor recording chunk (csv-v1, see ../RECORDING-FORMAT.md).
// Generates a realistic synthetic CSV (default 60 s) and gzips it with zlib.
// Usage: node estimate-chunk-size.mjs [seconds=60] [--write=path.csv]
import zlib from 'node:zlib';
import fs from 'node:fs';

const seconds = Number(process.argv.find((a) => /^\d+$/.test(a)) || 60);
const writeArg = process.argv.find((a) => a.startsWith('--write='));

// Deterministic PRNG so results are reproducible.
let seed = 12345;
const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
const gauss = () => Math.sqrt(-2 * Math.log(rnd() + 1e-12)) * Math.cos(2 * Math.PI * rnd());
const f = (v, d) => v.toFixed(d);

const RATES = { acc: 100, gyr: 100, mag: 50, bar: 10, loc: 1 };
const t0 = 1793367101000;
const rows = [];
let lat = 52.09073741, lon = 5.12142212, speed = 13.4;

for (const [s, hz] of Object.entries(RATES)) {
  const n = seconds * hz;
  for (let i = 0; i < n; i++) {
    const t = Math.round(t0 + (i * 1000) / hz + gauss() * 0.8); // timestamp jitter
    const sec = i / hz;
    let line;
    if (s === 'acc') {
      // gravity on z, road vibration + slow manoeuvres
      line = `${t},acc,${f(0.4 * Math.sin(sec * 0.7) + gauss() * 0.15, 6)},${f(0.2 * Math.sin(sec * 0.3) + gauss() * 0.12, 6)},${f(9.81 + gauss() * 0.2, 6)},,`;
    } else if (s === 'gyr') {
      line = `${t},gyr,${f(gauss() * 0.01, 6)},${f(gauss() * 0.01, 6)},${f(0.02 * Math.sin(sec * 0.2) + gauss() * 0.01, 6)},,`;
    } else if (s === 'mag') {
      line = `${t},mag,${f(21.5 + gauss() * 0.3, 6)},${f(-3.2 + gauss() * 0.3, 6)},${f(-40.1 + gauss() * 0.3, 6)},,`;
    } else if (s === 'bar') {
      line = `${t},bar,${f(1013.25 + gauss() * 0.02, 6)},,,,`;
    } else {
      speed = Math.max(0, speed + gauss() * 0.3);
      lat += (speed * 1e-5) / 1.1;
      lon += (speed * 1e-5) / 1.8;
      line = `${t},loc,${f(lat, 8)},${f(lon, 8)},${f(4.2 + gauss() * 0.1, 6)},${f(speed, 6)},${f(4.8, 6)}`;
    }
    rows.push({ t, line });
  }
}
rows.sort((a, b) => a.t - b.t); // sensors interleaved in arrival order
const csv = 't_ms,sensor,x,y,z,v4,v5\n' + rows.map((r) => r.line).join('\n') + '\n';
const raw = Buffer.from(csv, 'utf8');
const gz = zlib.gzipSync(raw, { level: 6 });
const b64 = gz.toString('base64');

const samples = rows.length;
const perMin = 60 / seconds;
const kb = (n) => (n / 1024).toFixed(1) + ' KB';
console.log(`Synthetic recording: ${seconds} s, ${samples} samples (${(samples / seconds).toFixed(0)} samples/s)`);
console.log(`CSV raw:        ${raw.length} bytes (${kb(raw.length)}), ${(raw.length / samples).toFixed(1)} bytes/sample`);
console.log(`gzip (level 6): ${gz.length} bytes (${kb(gz.length)}), ratio ${(raw.length / gz.length).toFixed(2)}x`);
console.log(`base64 of gzip: ${b64.length} bytes (${kb(b64.length)}) = what crosses the JS bridge / HTTP per chunk`);
console.log('Per minute:     raw ' + kb(raw.length * perMin) + ', gzip ' + kb(gz.length * perMin) + ', base64 ' + kb(b64.length * perMin));
console.log('Per hour:       raw ' + ((raw.length * perMin * 60) / 1048576).toFixed(1) + ' MB, gzip ' + ((gz.length * perMin * 60) / 1048576).toFixed(1) + ' MB');
if (writeArg) {
  fs.writeFileSync(writeArg.slice(8), csv);
  console.log('Wrote ' + writeArg.slice(8));
}
