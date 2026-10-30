/*
 * Client action: GetLastReadings
 * Input parameters:
 *   (none)
 * Output parameters:
 *   Success (Boolean)
 *   ErrorCode (Text)
 *   ErrorMessage (Text)
 *   ReadingsJson (Text) - JSON of SensorReadings {hasLocation, hasMotion, location?, motion?} (the JS adds the two flags); each part is omitted when the
 *                         plugin has no such reading yet. Deserialize into SensorReadings and use
 *                         hasLocation/hasMotion (see structures.md).
 *
 * Returns the most recent readings the native side already holds (no waiting, no sensor start).
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
  $parameters.ReadingsJson = "";
  const plugin = getPlugin();
  if (!plugin) {
    fail("PLUGIN_NOT_AVAILABLE", "DrivingSensors plugin not found. Run in a Capacitor (MABS 12) native build, not in a browser preview or Cordova build.");
    return;
  }
  try {
    const r = await plugin.getLastReadings();
    const out = Object.assign({}, r || {});
    out.hasLocation = !!out.location;
    out.hasMotion = !!out.motion;
    $parameters.ReadingsJson = JSON.stringify(out);
    $parameters.Success = true;
  } catch (e) {
    fail(e && e.code, e && e.message ? e.message : String(e));
  }
}
run().then(() => $resolve(), () => $resolve());
