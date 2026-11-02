/*
 * Client action: ConfigureUpload
 * Input parameters:
 *   EndpointBaseUrl (Text) - https base URL of your exposed REST API, e.g. https://<env domain>/DriveMe/rest/Telemetry
 *   DeviceId (Text) - id issued by RegisterDevice
 *   Token (Text) - secret device token issued by RegisterDevice (stored natively in Keystore/Keychain; never keep it in Local Storage)
 *   UploadRecordings (Boolean) - upload recording chunks to POST {base}/chunks
 *   UploadEvents (Boolean) - upload trip events to POST {base}/events
 *   WifiOnly (Boolean) - only upload on unmetered networks
 *   EventBatchSize (Integer) - events per request; 0 = plugin default (500)
 * Output parameters:
 *   Success (Boolean), ErrorCode (Text), ErrorMessage (Text)
 *
 * The plugin requires https. If native upload is on, do NOT also call GetBufferedEvents/AckEvents or the chunk
 * actions for the same data (see SPEC.md "Interplay").
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
    const opts = {
      endpointBaseUrl: $parameters.EndpointBaseUrl,
      deviceId: $parameters.DeviceId,
      token: $parameters.Token,
      uploadRecordings: !!$parameters.UploadRecordings,
      uploadEvents: !!$parameters.UploadEvents,
      wifiOnly: !!$parameters.WifiOnly
    };
    const n = Number($parameters.EventBatchSize);
    if (n > 0) opts.eventBatchSize = Math.floor(n); // 0 / empty = plugin default
    await plugin.configureUpload(opts);
    $parameters.Success = true;
  } catch (e) {
    fail(e && e.code, e && e.message ? e.message : String(e));
  }
}
run().then(() => $resolve(), () => $resolve());
