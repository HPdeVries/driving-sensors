/*
 * Client action: ListRecordingChunks
 * Input parameters:
 *   Limit (Integer) - max chunks, 0 = plugin default (100). Integer, not Long Integer.
 * Output parameters:
 *   Success (Boolean)
 *   ErrorCode (Text)
 *   ErrorMessage (Text)
 *   ChunksJson (Text) - JSON ARRAY of RecordingChunkInfo (deserialize as List of RecordingChunkInfo)
 *   Count (Integer)
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
    const limit = $parameters.Limit || 0;
    const r = await plugin.listRecordingChunks(limit > 0 ? { limit: limit } : undefined);
    const chunks = (r && r.chunks) || [];
    $parameters.ChunksJson = JSON.stringify(chunks);
    $parameters.Count = chunks.length;
    $parameters.Success = true;
  } catch (e) {
    fail(e && e.code, e && e.message ? e.message : String(e));
  }
}
run().then(() => $resolve(), () => $resolve());
