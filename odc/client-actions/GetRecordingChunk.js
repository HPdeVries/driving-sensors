/*
 * Client action: GetRecordingChunk
 * Input parameters:
 *   ChunkId (Text)
 * Output parameters:
 *   Success (Boolean)
 *   ErrorCode (Text)
 *   ErrorMessage (Text)
 *   MetaJson (Text) - JSON of the chunk WITHOUT the "data" field (RecordingChunkMeta, see structures.md)
 *   DataBase64 (Text) - base64 of the gzip-compressed CSV (csv-v1), kept separate so the big payload is never JSON-deserialized
 *
 * Reading a chunk does NOT delete it: call AckRecordingChunk after the data is safely stored.
 * The payload is a few hundred KB of base64 per 60-s chunk (see odc/RECORDING-FORMAT.md).
 *
 * JS node body (async JS node: ODC waits for $resolve()).
 */

function getPlugin() {
  const w = window;
  if (w.CapacitorPlugins && w.CapacitorPlugins.DrivingSensors) return w.CapacitorPlugins.DrivingSensors;
  if (w.Capacitor && w.Capacitor.Plugins && w.Capacitor.Plugins.DrivingSensors) return w.Capacitor.Plugins.DrivingSensors;
  // Last resort (UNVERIFIED in MABS 12): proxy for a natively registered plugin.
  try {
    if (w.Capacitor && typeof w.Capacitor.registerPlugin === "function" &&
        typeof w.Capacitor.isNativePlatform === "function" && w.Capacitor.isNativePlatform()) {
      return w.Capacitor.registerPlugin("DrivingSensors");
    }
  } catch (e) { /* ignore */ }
  return null;
}
function fail(code, message) {
  $parameters.Success = false;
  $parameters.ErrorCode = code || "UNKNOWN";
  $parameters.ErrorMessage = message || "";
}

async function run() {
  $parameters.Success = false;
  $parameters.ErrorCode = "";
  $parameters.ErrorMessage = "";
  const plugin = getPlugin();
  if (!plugin) {
    fail("PLUGIN_NOT_AVAILABLE", "DrivingSensors plugin not found. Run in a Capacitor (MABS 12) native build, not in a browser preview or Cordova build.");
    return;
  }
  try {
    const r = await plugin.getRecordingChunk({ chunkId: $parameters.ChunkId });
    const meta = Object.assign({}, r);
    delete meta.data;
    // sensorInfo is a free-form map: pass it on as JSON text (structure attribute sensorInfoJson), not as a nested object.
    meta.sensorInfoJson = JSON.stringify(meta.sensorInfo || {});
    delete meta.sensorInfo;
    $parameters.MetaJson = JSON.stringify(meta);
    $parameters.DataBase64 = (r && r.data) || "";
    $parameters.Success = true;
  } catch (e) {
    fail(e && e.code, e && e.message ? e.message : String(e));
  }
}
run().then(() => $resolve(), () => $resolve());
