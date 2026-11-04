/*
 * JS node: FriendlyError (synchronous) - DriveMe, client action ShowError
 * Inputs:  Context (Text)      - what the user tried, e.g. "Registering this device"
 *          ErrorCode (Text)    - ErrorCode output of a DrivingSensors client action or of RegisterDevice
 *          ErrorMessage (Text) - technical message (shown as detail only)
 * Outputs: Title (Text), Text (Text), Detail (Text)
 * Maps plugin and backend error codes to a sentence a user can act on.
 */
var texts = {
  // DrivingSensors plugin
  PLUGIN_NOT_AVAILABLE: "This feature only works in the installed DriveMe app, not in the browser preview.",
  PERMISSION_DENIED: "Location permission is missing. Open Settings > Apps > DriveMe > Permissions and allow location \"All the time\".",
  LOCATION_DISABLED: "Location is switched off on this phone. Turn on Location and try again.",
  NOT_SUPPORTED: "This phone or app build does not support this feature. Update the app and try again.",
  INVALID_ARGUMENT: "The app sent invalid settings. Please report this to the administrator.",
  STORAGE_FULL: "The phone is running out of space for recordings. Connect to Wi-Fi so recordings can upload, then try again.",
  PERMISSION_LOST: "Location permission was withdrawn, so recording stopped. Allow location \"All the time\" and start again.",
  // DrivingSensorsAPI RegisterDevice
  NOT_LOGGED_IN: "You are not logged in. Log in and try again.",
  INVALID_PLATFORM: "This device type is not supported.",
  INVALID_NAME: "The device name is empty or too long (max 100 characters).",
  SERVER_NOT_CONFIGURED: "The server is not set up for device registration yet. Please contact the administrator.",
  DEVICE_LIMIT: "You have reached the maximum number of registered devices. Remove an old device and try again.",
  // Communication / upload
  NO_CONNECTION: "No connection to the server. Check your internet connection and try again.",
  AUTH_FAILED: "This device is no longer authorized to send data. Register the device again."
};
var code = ($parameters.ErrorCode || "").toUpperCase();
$parameters.Title = ($parameters.Context || "Action") + " failed";
$parameters.Text = texts[code] || "Something went wrong. Try again; if it keeps failing, contact the administrator.";
$parameters.Detail = (code ? code + ": " : "") + ($parameters.ErrorMessage || "");
