# Raw sensor recording: chunk format and sizes (v0.2.0)

For whoever builds the API forwarding (ODC server -> external API). Authoritative source: `SPEC.md`, section "v0.2.0: raw sensor recording".

## What a chunk is

A recording is cut into chunks (default every 60 s, `chunkSeconds` 10 to 600). Each chunk is:

- **metadata** (JSON): `chunkId` (`<recordingId>-<seq, 6 digits>`), `recordingId`, `seq`, `startTs`/`endTs` (epoch ms of first/last sample), `bytes` (gzip size), `counts` per sensor, `format` = `csv-v1`, `encoding` = `gzip+base64`, `platform`, `deviceModel`, `osVersion`, `pluginVersion`, `sensorInfo` (per-sensor name/vendor, calibrated or raw; in ODC delivered as the text attribute `sensorInfoJson`)
- **data**: the CSV below, gzip-compressed, then base64-encoded. In ODC: `DataBase64` of `GetRecordingChunk`, stored as Binary Data (`Base64ToBinary`) in `RecordingChunk.Data`. The binary is therefore **gzip bytes**; to read the CSV: gunzip.

Chunk ids are unique and stable: use `chunkId` as the idempotency key.

## CSV format (csv-v1)

UTF-8, `\n` line endings, header line then one sample per line, chronological per sensor (sensors interleaved in arrival order):

```
t_ms,sensor,x,y,z,v4,v5
1793367101003,acc,0.123,-0.045,9.812,,
1793367101005,gyr,0.0012,-0.0004,0.0001,,
1793367101010,mag,21.5,-3.2,-40.1,,
1793367101050,bar,1013.25,,,,
1793367101000,loc,52.09073741,5.12142212,4.2,13.4,4.8
```

Numbers: up to 6 decimals for acc/gyr/mag/bar (`.` decimal separator), 8 for lat/lon. Empty field = unknown. `t_ms` is epoch milliseconds (UTC), taken from the sensor's own event timestamp (location: the fix time). Samples are what the platform delivered (no resampling), so rates jitter and a `loc` row can have a `t_ms` older than neighbouring rows (as in the example above).

| sensor | source | x | y | z | v4 | v5 |
|---|---|---|---|---|---|---|
| acc | accelerometer incl. gravity, device axes | m/s2 | m/s2 | m/s2 | | |
| gyr | gyroscope | rad/s | rad/s | rad/s | | |
| mag | magnetometer | uT | uT | uT | | |
| bar | barometer | pressure hPa | relative altitude m (iOS only, empty on Android) | | | |
| loc | GPS | latitude | longitude | altitude m | speed m/s | horizontal accuracy m (empty when unknown) |

Platform differences: iOS accelerometer is multiplied by -9.80665 so a phone lying face-up reads z about +9.81 (Android convention). iOS magnetometer is raw (uncalibrated), Android is calibrated. iOS barometer delivers about 1 Hz regardless of the requested rate.

## Sizes

Measured with `odc/tools/estimate-chunk-size.mjs` (synthetic but realistic: acc 100 Hz, gyr 100 Hz, mag 50 Hz, bar 10 Hz, loc 1 Hz = 261 samples/s, gaussian noise, jittered timestamps, gzip level 6):

| | per 60-s chunk | per minute of recording | per hour |
|---|---|---|---|
| samples | 15,660 | 15,660 | about 940,000 |
| CSV raw | 757,421 B (740 KB), 48.4 B/sample | 740 KB | 43.3 MB |
| gzip | 235,186 B (230 KB), ratio 3.2x | 230 KB | 13.5 MB |
| base64 of gzip (crosses JS bridge and HTTP) | 313,584 B (306 KB) | 306 KB | 17.9 MB |

Caveats: random noise compresses worse than real, smooth sensor signals, so real chunks are likely equal or smaller. If the barometer runs at about 1 Hz (iOS) the sample count is about 252/s. Run `node odc/tools/estimate-chunk-size.mjs 60` to reproduce; the second argument form `--write=/tmp/x.csv` writes the synthetic CSV for inspection.

Rule of thumb: about 230 KB gzip (about 310 KB as base64) per minute of recording; a 30-minute drive is about 7 MB gzip.

## Forwarding checklist

1. Select `RecordingChunk` rows not yet sent (add a `SentOn` attribute, see STUDIO-BUILD-GUIDE.md).
2. Send `Data` (gzip bytes) with `Content-Encoding: gzip` or as-is as an octet-stream, plus metadata (`MetaJson`) and `chunkId` as idempotency key.
3. On 2xx, set `SentOn`; delete old sent rows periodically (13.5 MB per hour of recording per device adds up).
