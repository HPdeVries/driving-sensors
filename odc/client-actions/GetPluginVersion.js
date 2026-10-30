/*
 * Client action: GetPluginVersion
 * Input parameters:
 *   (none)
 * Output parameters:
 *   Success (Boolean)
 *   ErrorCode (Text)
 *   ErrorMessage (Text)
 *   Version (Text)  - version of the NATIVE plugin code compiled into the app (e.g. "0.1.2"), not of the JS bundle
 *   Platform (Text) - "android", "ios" or "web"
 *
 * Why: ODC updates JS over the air, so the JS side can be newer than the native binary built by MABS.
 * This call asks the native side, so it reports what is really compiled in.
 *
 * NOTE: on a native binary older than 0.1.2 this call FAILS with a "not implemented" error
 * (Success = false, e.g. ErrorCode UNIMPLEMENTED) and Version is set to "< 0.1.2".
 * PLUGIN_NOT_AVAILABLE means the plugin is not present at all.
 *
 * JS node body (async JS node: ODC waits for $resolve()).
 */
function getPlugin() {
  const w = window;
  if (w.CapacitorPlugins && w.CapacitorPlugins.DrivingSensors) return w.CapacitorPlugins.DrivingSensors;
  if (w.Capacitor && w.Capacitor.Plugins && w.Capacitor.Plugins.DrivingSensors) return w.Capacitor.Plugins.DrivingSensors;
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
  $parameters.Version = "";
  $parameters.Platform = "";
  const plugin = getPlugin();
  if (!plugin) {
    fail("PLUGIN_NOT_AVAILABLE", "DrivingSensors plugin not found. Run in a Capacitor (MABS 12) native build.");
    return;
  }
  try {
    const r = await plugin.getPluginVersion();
    $parameters.Version = (r && r.version) || "";
    $parameters.Platform = (r && r.platform) || "";
    $parameters.Success = true;
  } catch (e) {
    // A native binary older than 0.1.2 has no getPluginVersion
    if (e && (e.code === "UNIMPLEMENTED" || /not implemented/i.test(e.message || ""))) {
      $parameters.Version = "< 0.1.2";
    }
    fail(e && e.code, e && e.message ? e.message : String(e));
  }
}
run().then(() => $resolve(), () => $resolve());
