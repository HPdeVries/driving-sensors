/*
 * Client action: StartRecording
 * Input parameters:
 *   OptionsJson (Text) - JSON of RecordingOptions, all fields optional, e.g. {"chunkSeconds":60}; empty = defaults
 * Output parameters:
 *   Success (Boolean)
 *   ErrorCode (Text)
 *   ErrorMessage (Text)
 *   RecordingId (Text) - id of the (new or already running) recording
 *
 * Idempotent: when already recording, the plugin returns the running recordingId and ignores the options.
 *
 * Caveat: JSON Serialize of an ODC record emits every attribute with ODC defaults (0 / empty); the plugin would
 * clamp 0 values. Prefer "" (defaults) or hand-built JSON containing only the keys you want to override.
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
    let options;
    const txt = $parameters.OptionsJson;
    if (txt && String(txt).trim() !== "") {
      try { options = JSON.parse(txt); } catch (pe) { fail("INVALID_ARGUMENT", "OptionsJson is not valid JSON: " + pe.message); return; }
    }
    const r = await plugin.startRecording(options);
    $parameters.RecordingId = (r && r.recordingId) || "";
    $parameters.Success = true;
  } catch (e) {
    fail(e && e.code, e && e.message ? e.message : String(e));
  }
}
run().then(() => $resolve(), () => $resolve());
