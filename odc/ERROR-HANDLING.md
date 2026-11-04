# Clear error messages (DrivingSensorsAPI + DriveMe)

Goal: when something fails (registering, configuring upload, starting tracking/recording/heartbeat), the user sees what went
wrong and what to do, never a silent failure or a raw exception.

## A. DrivingSensorsAPI (Mentor spec)

`RegisterDevice` (and `RevokeDevice`): outputs `Success` (Boolean), `ErrorCode` (Text, new), `Message` (Text).
Every failure path sets Success = False, an ErrorCode and a user-readable Message, and ends without raising:

| Situation | ErrorCode | Message |
|---|---|---|
| Not logged in | NOT_LOGGED_IN | You are not logged in. |
| Platform not android/ios | INVALID_PLATFORM | This device type is not supported. |
| DeviceName empty or > 100 chars | INVALID_NAME | Device name must be 1 to 100 characters. |
| DeviceTokenMacKey empty | SERVER_NOT_CONFIGURED | The server is not set up for device registration yet. |
| Device limit reached (only if the action refuses instead of revoking the oldest) | DEVICE_LIMIT | Maximum number of devices reached. |
| Any other exception (AllExceptions handler on the action) | INTERNAL | Registration failed on the server. Try again later. (ref <yyyyMMdd-HHmmss>) |

In the AllExceptions handler: LogMessage with the exception message and the same ref, module "RegisterDevice". Never log
the token, the hash or the key. Success = True sets ErrorCode = "" and Message = "".

## B. DriveMe (ODC Studio, by hand)

1. **Client action `ShowError`** (inputs Context, ErrorCode, ErrorMessage Text):
   - JS node `FriendlyError` with the code from `odc/client-actions/FriendlyError.js` (inputs Context, ErrorCode,
     ErrorMessage; outputs Title, Text, Detail).
   - `Message` node (Type Error): `FriendlyError.Title + ": " + FriendlyError.Text`.
   - Optional: keep Detail in a local variable / "Show details" expander for troubleshooting.

2. **Registering** (the screen/action that calls RegisterDevice + ConfigureUpload):
   - `RegisterDevice`; If not Success -> `ShowError("Registering this device", ErrorCode, Message)` and End.
   - `ConfigureUpload(...)`; If not Success -> `ShowError("Setting up upload", ErrorCode, ErrorMessage)` and End.
   - Success -> Message (Success) "Device registered".
   - Exception handlers on this client action:
     - **CommunicationException** -> `ShowError("Registering this device", "NO_CONNECTION", ExceptionMessage)`.
     - **AllExceptions** -> `ShowError("Registering this device", "", AllExceptions.ExceptionMessage)`.

3. **Every plugin call** (StartTracking, StartRecording, StartHeartbeat, RequestPermissions, UploadNow, ...):
   If not Success -> `ShowError("<what the user tried>", ErrorCode, ErrorMessage)`.

4. **Upload status**: where you show GetUploadStatus, if `state = "auth_failed"` show
   `ShowError("Uploading data", "AUTH_FAILED", LastError)` once, with a "Register again" button next to it.
