/*
 * JS node: RecBuildSeries (synchronous)
 * Inputs:  MaxPoints (Integer) - max points per chart series, 0 = 1500
 *          GapMs (Integer)     - accelerometer gap that counts as "not recording", ms, 0 = 2000
 * Outputs:
 *   AccelPointsJson (Text) - JSON list of OutSystems Charts DataPoint-like items {Label, Value, DataSeriesName, Tooltip}
 *                            series "Horizontal" (bucket max, m/s2) and "Vertical" (bucket max of |vertical|, m/s2)
 *   AxesPointsJson (Text)  - same shape, raw accelerometer bucket means "x", "y", "z" (m/s2, incl. gravity)
 *   SpeedPointsJson (Text) - same shape, series "km/h" (GPS speed)
 *   TrackJson (Text)       - JSON list of {Latitude, Longitude} (max 1000 points)
 *   SpeedTrackJson (Text)  - JSON list of {Color, Points:[{Latitude, Longitude}]}: the track split where the GPS speed
 *                            band changes (<30 blue, 30-50 green, 50-80 yellow, 80-100 orange, >=100 km/h red, no speed grey);
 *                            consecutive segments share their joining point
 *   GapsJson (Text)      - JSON list of {startTs, endTs, durationSec}: periods without accelerometer samples
 *   GoogleMapsUrl (Text)   - route link (origin, up to 8 waypoints, destination), "" without GPS
 *   SummaryJson (Text)     - {startTs, endTs, durationSec, counts:{acc,gyr,mag,bar,loc}, maxHorizontal,
 *                             maxVertical, maxSpeedKmh, gapCount, gapTotalSec, coveragePct}
 * Labels are seconds since the first sample.
 * Horizontal/vertical: gravity is estimated with a ~3 s low-pass filter on the raw accelerometer;
 * the remaining (linear) acceleration is split into the component along gravity (vertical: bumps)
 * and perpendicular to it (horizontal: braking, accelerating, cornering), independent of how the
 * phone is mounted. Same idea as the plugin's harsh-event detection.
 */
var maxPts = $parameters.MaxPoints > 0 ? $parameters.MaxPoints : 1500;
var gapMs = $parameters.GapMs > 0 ? $parameters.GapMs : 2000;
var TAU = 3000;
var rec = window.__dsRec || { parts: [] };
var acc = [], loc = [], counts = { acc: 0, gyr: 0, mag: 0, bar: 0, loc: 0 };
var tMin = Infinity, tMax = -Infinity;
for (var p = 0; p < rec.parts.length; p++) {
  var lines = rec.parts[p].split("\n");
  for (var i = 0; i < lines.length; i++) {
    var c = lines[i].split(",");
    if (c.length < 3) continue;
    var t = +c[0], s = c[1];
    if (!isFinite(t) || counts[s] === undefined) continue;
    counts[s]++;
    if (t < tMin) tMin = t;
    if (t > tMax) tMax = t;
    if (s === "acc") acc.push([t, +c[2], +c[3], +c[4]]);
    else if (s === "loc") loc.push([t, +c[2], +c[3], c[5] === "" ? NaN : +c[5]]);
  }
}
acc.sort(function (a, b) { return a[0] - b[0]; });
loc.sort(function (a, b) { return a[0] - b[0]; });
var dur = isFinite(tMin) ? tMax - tMin : 0;
var label = function (t) { return ((t - tMin) / 1000).toFixed(1); };
var r2 = function (v) { return Math.round(v * 100) / 100; };

var accPts = [], axesPts = [], gaps = [], maxH = 0, maxV = 0;
if (acc.length) {
  var bucket = Math.max(10, Math.ceil((dur + 1) / maxPts));
  var gx = acc[0][1], gy = acc[0][2], gz = acc[0][3], prevT = acc[0][0];
  var cur = -1, n = 0, sx = 0, sy = 0, sz = 0, bh = 0, bv = 0, bt = 0;
  var flush = function () {
    if (!n) return;
    var l = label(bt);
    accPts.push({ Label: l, Value: r2(bh), DataSeriesName: "Horizontal", Tooltip: "" });
    accPts.push({ Label: l, Value: r2(bv), DataSeriesName: "Vertical", Tooltip: "" });
    axesPts.push({ Label: l, Value: r2(sx / n), DataSeriesName: "x", Tooltip: "" });
    axesPts.push({ Label: l, Value: r2(sy / n), DataSeriesName: "y", Tooltip: "" });
    axesPts.push({ Label: l, Value: r2(sz / n), DataSeriesName: "z", Tooltip: "" });
  };
  for (var k = 0; k < acc.length; k++) {
    var a = acc[k], dt = a[0] - prevT;
    if (dt > gapMs) {
      gaps.push({ startTs: prevT, endTs: a[0], durationSec: Math.round(dt / 100) / 10 });
      gx = a[1]; gy = a[2]; gz = a[3];
    } else if (dt > 0) {
      var al = dt / (TAU + dt);
      gx += al * (a[1] - gx); gy += al * (a[2] - gy); gz += al * (a[3] - gz);
    }
    prevT = a[0];
    var gn = Math.sqrt(gx * gx + gy * gy + gz * gz) || 1;
    var ux = gx / gn, uy = gy / gn, uz = gz / gn;
    var lx = a[1] - gx, ly = a[2] - gy, lz = a[3] - gz;
    var v = lx * ux + ly * uy + lz * uz;
    var hx = lx - v * ux, hy = ly - v * uy, hz = lz - v * uz;
    var h = Math.sqrt(hx * hx + hy * hy + hz * hz), av = Math.abs(v);
    var b = Math.floor((a[0] - tMin) / bucket);
    if (b !== cur) { flush(); cur = b; n = 0; sx = sy = sz = bh = bv = 0; bt = tMin + b * bucket; }
    n++; sx += a[1]; sy += a[2]; sz += a[3];
    if (h > bh) bh = h;
    if (av > bv) bv = av;
    if (h > maxH) maxH = h;
    if (av > maxV) maxV = av;
  }
  flush();
}
var gapTotal = 0;
for (var gi = 0; gi < gaps.length; gi++) gapTotal += gaps[gi].durationSec;

var spdPts = [], maxKmh = 0, step = Math.max(1, Math.ceil(loc.length / maxPts));
for (var j = 0; j < loc.length; j += step) {
  var sp = loc[j][3];
  if (!isFinite(sp)) continue;
  spdPts.push({ Label: label(loc[j][0]), Value: r2(sp * 3.6), DataSeriesName: "km/h", Tooltip: "" });
}
for (var q = 0; q < loc.length; q++) if (isFinite(loc[q][3]) && loc[q][3] * 3.6 > maxKmh) maxKmh = loc[q][3] * 3.6;

var track = [], tStep = Math.max(1, Math.ceil(loc.length / 1000));
for (var m = 0; m < loc.length; m += tStep) track.push({ Latitude: loc[m][1], Longitude: loc[m][2] });
if (loc.length && (loc.length - 1) % tStep !== 0) track.push({ Latitude: loc[loc.length - 1][1], Longitude: loc[loc.length - 1][2] });

var bands = [[30, "#2563eb"], [50, "#16a34a"], [80, "#eab308"], [100, "#f97316"], [Infinity, "#dc2626"]];
var bandOf = function (mps) {
  if (!isFinite(mps)) return -1;
  for (var bi = 0; bi < bands.length; bi++) if (mps * 3.6 < bands[bi][0]) return bi;
  return bands.length - 1;
};
var segs = [], seg = null;
for (var s2 = 0; s2 < loc.length; s2 += tStep) {
  var b2 = bandOf(loc[s2][3]), p2 = { Latitude: loc[s2][1], Longitude: loc[s2][2] };
  if (!seg || seg.band !== b2) {
    if (seg) seg.Points.push(p2);
    seg = { band: b2, Color: b2 < 0 ? "#9ca3af" : bands[b2][1], Points: [p2] };
    segs.push(seg);
  } else seg.Points.push(p2);
}
if (seg && (loc.length - 1) % tStep !== 0) seg.Points.push({ Latitude: loc[loc.length - 1][1], Longitude: loc[loc.length - 1][2] });
for (var s3 = 0; s3 < segs.length; s3++) delete segs[s3].band;

var gm = "", ll = function (pt) { return pt[1].toFixed(6) + "," + pt[2].toFixed(6); };
if (loc.length === 1) {
  gm = "https://www.google.com/maps/search/?api=1&query=" + encodeURIComponent(ll(loc[0]));
} else if (loc.length > 1) {
  var wps = [], nw = Math.min(8, loc.length - 2);
  for (var w = 1; w <= nw; w++) wps.push(ll(loc[Math.round((w * (loc.length - 1)) / (nw + 1))]));
  gm = "https://www.google.com/maps/dir/?api=1&travelmode=driving&origin=" + encodeURIComponent(ll(loc[0])) +
    "&destination=" + encodeURIComponent(ll(loc[loc.length - 1])) +
    (wps.length ? "&waypoints=" + encodeURIComponent(wps.join("|")) : "");
}

$parameters.AccelPointsJson = JSON.stringify(accPts);
$parameters.AxesPointsJson = JSON.stringify(axesPts);
$parameters.SpeedPointsJson = JSON.stringify(spdPts);
$parameters.TrackJson = JSON.stringify(track);
$parameters.SpeedTrackJson = JSON.stringify(segs);
$parameters.GapsJson = JSON.stringify(gaps);
$parameters.GoogleMapsUrl = gm;
$parameters.SummaryJson = JSON.stringify({
  startTs: isFinite(tMin) ? tMin : 0, endTs: isFinite(tMax) ? tMax : 0, durationSec: Math.round(dur / 100) / 10,
  counts: counts, maxHorizontal: r2(maxH), maxVertical: r2(maxV), maxSpeedKmh: r2(maxKmh),
  gapCount: gaps.length, gapTotalSec: Math.round(gapTotal * 10) / 10,
  coveragePct: dur > 0 ? Math.round(Math.max(0, (dur / 1000 - gapTotal) / (dur / 1000)) * 1000) / 10 : 0
});
