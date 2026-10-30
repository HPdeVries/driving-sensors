/*
 * Client action: GetCurrentReadings
 * Input parameters:
 *   TimeoutMs (Integer) - max time to wait for a fresh fix, ms; 0 = plugin default
 *   MotionSampleMs (Integer) - motion sampling window, ms; 0 = plugin default
 * Output parameters:
 *   Success (Boolean)
 *   ErrorCode (Text)
 *   ErrorMessage (Text)
 *   ReadingsJson (Text) - JSON of SensorReadings {hasLocation, hasMotion, location?, motion?} (the JS adds the two flags); parts the plugin could not
 *                         obtain are omitted. Deserialize into SensorReadings and use
 *                         hasLocation/hasMotion (see structures.md).
 *
 * Inputs equal to 0 are left out of the options, so the plugin applies its own defaults.
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
    const opts = {};
    if ($parameters.TimeoutMs > 0) opts.timeoutMs = $parameters.TimeoutMs;
    if ($parameters.MotionSampleMs > 0) opts.motionSampleMs = $parameters.MotionSampleMs;
    const r = await plugin.getCurrentReadings(opts);
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
