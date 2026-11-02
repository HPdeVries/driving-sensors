# DrivingSensorsAPI: retention, recording viewer (CSV, charts, map)

Monitoring screens are for role TelemetryAdmin (existing). The JavaScript below is tested; put it in the JS nodes VERBATIM. Recording data never goes into large ODC variables: JS keeps it in `window.__dsRec`.

## A. Retention (extend timer CleanupOldData, daily)
Delete in batches of max 5000 per loop, same pattern as the existing heartbeat cleanup, stop near the timer limit:
- RecordingChunk with ReceivedOn older than 14 days (ignore SentOn for now).
- DeviceHeartbeat older than 14 days (already there).
- ServerTelemetryEvent with ReceivedOn older than 90 days.
Add an index on RecordingChunk.ReceivedOn and ServerTelemetryEvent.ReceivedOn if missing.

## B. Server actions (check TelemetryAdmin role, raise NotAuthorized otherwise)
- GetRecordings(DeviceId) -> list of RecordingInfo {RecordingId Text, Chunks Integer, StartTs Long Integer, EndTs Long Integer, TotalBytes Long Integer, FirstReceivedOn DateTime}: RecordingChunk grouped by RecordingId for that device, newest first.
- GetRecordingChunks(RecordingId) -> list of ChunkRef {ChunkId Text, Seq Integer, StartTs Long Integer, EndTs Long Integer, Bytes Integer}, ordered by Seq ascending.
- GetChunkData(ChunkId) -> Base64 Text = BinaryToBase64(RecordingChunk.Data).

## C. DeviceDetail changes
- New table "Recordings" (GetRecordings): RecordingId, chunks, start/end (epoch ms -> date time), duration, total MB; row click opens RecordingDetail(RecordingId, DeviceId).
- In the telemetry events table: a "Map" link per event with lat/lon <> 0 that opens https://www.google.com/maps/search/?api=1&query=<lat>,<lon> in a new browser tab.

## D. New screen RecordingDetail (inputs RecordingId Text, DeviceId Long Integer), role TelemetryAdmin
Flow (OnReady or a "Load" client action started from OnReady):
1. JS node RecReset.
2. ChunkRefs = GetRecordingChunks(RecordingId). For each (in Seq order): Base64 = GetChunkData(ChunkId); JS node RecAppendChunk(Base64); update a progress text "Loaded i of N chunks" (refresh the screen between chunks). If Success is False show the ErrorMessage and continue.
3. JS node RecBuildSeries(MaxPoints = 1500). JSON Deserialize AccelPointsJson and SpeedPointsJson into List of the OutSystems Charts DataPoint structure (add the OutSystems Charts dependency); deserialize SummaryJson into a structure RecordingSummary {startTs Long Integer, endTs Long Integer, durationSec Decimal, counts (acc, gyr, mag, bar, loc Integer), maxDynAccel Decimal, maxSpeedKmh Decimal}; keep TrackJson and GoogleMapsUrl in local variables.
4. UI:
   - Summary card: start/end time, duration, sample counts per sensor, max |a|-g (m/s2), max speed (km/h).
   - Button "Download CSV" -> JS node RecDownloadCsv(FileName = "recording-" + RecordingId + ".csv").
   - Line chart "Acceleration (m/s2)" from the accel DataPoints (series x, y, z, |a|-g), x-axis = seconds since start.
   - Line chart "GPS speed (km/h)" from the speed DataPoints.
   - Link "Open route in Google Maps" -> GoogleMapsUrl, new tab, hidden when empty. Note under it: "Google Maps plans a route through start, end and 8 points; it is not the exact GPS track."
   - Empty state when there are no chunks.

## E. In-app map (RecordingDetail)
Show the GPS track (TrackJson: list of {Latitude, Longitude}) on an in-app map using the OutSystems Maps library, preferably a provider that needs no API key (Leaflet/OpenStreetMap). Draw the track as a polyline with start and end markers, auto-fit to the track. Hidden when TrackJson is "[]". If no keyless provider is available in this tenant, skip E and report it.

## JS node code

### RecReset

```js
/*
 * JS node: RecReset (DrivingSensorsAPI, RecordingDetail screen)
 * Inputs: none. Outputs: none. Synchronous.
 * Clears the in-browser buffer that RecAppendChunk fills. Call before loading a recording.
 */
window.__dsRec = { parts: [], chunks: 0 };
```

### RecAppendChunk

```js
/*
 * JS node: RecAppendChunk (asynchronous)
 * Inputs:  Base64 (Text) - RecordingChunk.Data as base64 (gzip of a csv-v1 chunk)
 * Outputs: Success (Boolean), ErrorMessage (Text), Lines (Integer) - data lines in this chunk
 * Decompresses in the browser (DecompressionStream) and keeps the CSV rows in window.__dsRec,
 * so the large text never goes through ODC variables. Call once per chunk, in Seq order.
 */
(async function () {
  $parameters.Success = false;
  $parameters.ErrorMessage = "";
  $parameters.Lines = 0;
  try {
    var rec = window.__dsRec || (window.__dsRec = { parts: [], chunks: 0 });
    var b64 = $parameters.Base64 || "";
    var bin = Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); });
    var stream = new Blob([bin]).stream().pipeThrough(new DecompressionStream("gzip"));
    var text = await new Response(stream).text();
    var nl = text.indexOf("\n");
    var body = nl >= 0 ? text.slice(nl + 1) : "";
    if (body && body.charAt(body.length - 1) !== "\n") body += "\n";
    rec.parts.push(body);
    rec.chunks++;
    $parameters.Lines = body ? body.split("\n").length - 1 : 0;
    $parameters.Success = true;
  } catch (e) {
    $parameters.ErrorMessage = String((e && e.message) || e);
  }
  $resolve();
})();
```

### RecDownloadCsv

```js
/*
 * JS node: RecDownloadCsv (synchronous)
 * Inputs:  FileName (Text) - e.g. "recording-" + RecordingId + ".csv"
 * Outputs: Bytes (Integer) - size of the downloaded file, 0 when nothing is loaded
 * Downloads all appended chunks as one csv-v1 file (single header line).
 */
var rec = window.__dsRec || { parts: [] };
var csv = "t_ms,sensor,x,y,z,v4,v5\n" + rec.parts.join("");
$parameters.Bytes = 0;
if (rec.parts.length) {
  var blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  var url = URL.createObjectURL(blob);
  var a = document.createElement("a");
  a.href = url;
  a.download = $parameters.FileName || "recording.csv";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
  $parameters.Bytes = blob.size;
}
```

### RecBuildSeries

```js
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
```
