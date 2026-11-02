/*
 * JS node: RecBuildSeries (synchronous)
 * Inputs:  MaxPoints (Integer) - max points per chart series, 0 = 1500
 * Outputs:
 *   AccelPointsJson (Text) - JSON list of OutSystems Charts DataPoint {Label, Value, DataSeriesName, Tooltip}
 *                            series "x", "y", "z" (bucket mean, m/s2) and "|a|-g" (bucket max, m/s2)
 *   SpeedPointsJson (Text) - JSON list of DataPoint, series "km/h" (GPS speed)
 *   TrackJson (Text)       - JSON list of {Latitude, Longitude} (max 1000 points)
 *   GoogleMapsUrl (Text)   - route link (origin, up to 8 waypoints, destination), "" without GPS
 *   SummaryJson (Text)     - {startTs, endTs, durationSec, counts:{acc,gyr,mag,bar,loc}, maxDynAccel, maxSpeedKmh}
 * Labels are seconds since the first sample. Raw accelerometer includes gravity; "|a|-g" is the
 * dynamic part regardless of phone orientation.
 */
var G = 9.80665;
var maxPts = $parameters.MaxPoints > 0 ? $parameters.MaxPoints : 1500;
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

var accPts = [], maxDyn = 0;
if (acc.length) {
  var bucket = Math.max(10, Math.ceil((dur + 1) / maxPts));
  var cur = -1, n = 0, sx = 0, sy = 0, sz = 0, mx = 0, bt = 0;
  var flush = function () {
    if (!n) return;
    var l = label(bt);
    accPts.push({ Label: l, Value: r2(sx / n), DataSeriesName: "x", Tooltip: "" });
    accPts.push({ Label: l, Value: r2(sy / n), DataSeriesName: "y", Tooltip: "" });
    accPts.push({ Label: l, Value: r2(sz / n), DataSeriesName: "z", Tooltip: "" });
    accPts.push({ Label: l, Value: r2(mx), DataSeriesName: "|a|-g", Tooltip: "" });
  };
  for (var k = 0; k < acc.length; k++) {
    var a = acc[k], b = Math.floor((a[0] - tMin) / bucket);
    if (b !== cur) { flush(); cur = b; n = 0; sx = sy = sz = mx = 0; bt = tMin + b * bucket; }
    var dyn = Math.abs(Math.sqrt(a[1] * a[1] + a[2] * a[2] + a[3] * a[3]) - G);
    n++; sx += a[1]; sy += a[2]; sz += a[3];
    if (dyn > mx) mx = dyn;
    if (dyn > maxDyn) maxDyn = dyn;
  }
  flush();
}

var spdPts = [], maxKmh = 0, step = Math.max(1, Math.ceil(loc.length / maxPts));
for (var j = 0; j < loc.length; j += step) {
  var v = loc[j][3];
  if (!isFinite(v)) continue;
  var kmh = v * 3.6;
  if (kmh > maxKmh) maxKmh = kmh;
  spdPts.push({ Label: label(loc[j][0]), Value: r2(kmh), DataSeriesName: "km/h", Tooltip: "" });
}
for (var q = 0; q < loc.length; q++) if (isFinite(loc[q][3]) && loc[q][3] * 3.6 > maxKmh) maxKmh = loc[q][3] * 3.6;

var track = [], tStep = Math.max(1, Math.ceil(loc.length / 1000));
for (var m = 0; m < loc.length; m += tStep) track.push({ Latitude: loc[m][1], Longitude: loc[m][2] });
if (loc.length && (loc.length - 1) % tStep !== 0) track.push({ Latitude: loc[loc.length - 1][1], Longitude: loc[loc.length - 1][2] });

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
$parameters.SpeedPointsJson = JSON.stringify(spdPts);
$parameters.TrackJson = JSON.stringify(track);
$parameters.GoogleMapsUrl = gm;
$parameters.SummaryJson = JSON.stringify({
  startTs: isFinite(tMin) ? tMin : 0, endTs: isFinite(tMax) ? tMax : 0, durationSec: Math.round(dur / 100) / 10,
  counts: counts, maxDynAccel: r2(maxDyn), maxSpeedKmh: r2(maxKmh)
});
