# Backend for native upload and heartbeat (v0.3.0): ODC Studio recipe

Goal: data from the plugin (trip events, recording chunks, heartbeats) reaches your ODC backend **without the WebView running**, and every row is tied to a **logged-in user** and a **device**. Contract: `SPEC.md`, section "v0.3.0: native upload to your own endpoint" and "Heartbeat (v0.3.0)". Everything below is built by hand in ODC Studio (ODC Mentor cannot edit mobile apps or libraries, but it can help with the server-only parts if you keep them in a separate **server app**).

Legend: **unverified** = could not be confirmed in ODC documentation; test it in your tenant first.

## 0. Architecture in one picture

```
Login (OutSystems user) --> app calls RegisterDevice (server action, needs logged-in user)
        server: creates Device row, token = random 256 bit, stores only SHA-256(token), returns DeviceId + Token ONCE
app --> ConfigureUpload(endpoint, DeviceId, Token)   // token goes into Keystore/Keychain, app keeps only DeviceId
plugin (native, background) --HTTPS--> exposed REST API "Telemetry": /chunks /events /heartbeat
        OnAuthentication: Authorization: Bearer <token> + X-Device-Id -> Device row -> hash compare -> user = Device.UserId
Timer (later): unsent rows --> external API, SentOn
Logout: ClearUpload (device) --> RevokeDevice (server)
```

Why a per-device secret and not the user session: the plugin uploads in the background, long after the OutSystems session cookie/token may have expired, and the WebView is not involved. A device token can be revoked independently (lost phone, logout) and never grants more than "write telemetry for this device".

## 1. Research findings (ODC documentation)

| Topic | Finding | Source |
|---|---|---|
| Request pipeline | An exposed method runs: `OnRequest()`, `OnAuthentication()` ("allows you to add basic authentication or custom authentication to requests"), parameter deserialization/validation, method, serialization, `OnResponse()` (always executed, also on errors). | https://success.outsystems.com/documentation/outsystems_developer_cloud/integration_with_external_systems/exposing_rest_apis/ |
| Read a header | In `OnAuthentication`, read the `Authorization` header with `GetRequestHeader` from the **HTTPRequestHandler** extension (the ODC page names it for stripping the `Bearer` prefix). An ODC forum answer calls the same thing `HTTP.Request_GetHeader`. Add it via **Manage dependencies** (extension "HTTP" / HTTPRequestHandler). Exact action name in your Studio version: **unverified**, search "GetRequestHeader" and "Request_GetHeader" in Manage dependencies. | https://success.outsystems.com/documentation/outsystems_developer_cloud/integration_with_external_systems/exposing_rest_apis/token_based_authentication_for_exposed_rest_apis/ ; https://www.outsystems.com/forums/discussion/97606/is-there-an-odc-equivalent-to-the-os11-httprequesthandler-getrequestcontent |
| Return 401 | The token-auth page says: if validation fails "stop processing the request and return an appropriate HTTP status code"; 401 = token missing, expired or invalid; to send a custom code use the HTTP library action `Response_SetStatusCode` (then end the callback flow). 403 = valid token but not permitted. | token_based_authentication page above |
| Set a status code | Manage dependencies > add the HTTP extension's `Response_SetStatusCode`; call it right before the End node and set `StatusCode`. Name in the page: `HTTP.Response_SetStatusCode`. | https://success.outsystems.com/documentation/outsystems_developer_cloud/integration_with_external_systems/exposing_rest_apis/change_the_http_status_code_of_a_rest_api/ |
| Custom error | Raise a **User Exception** (Raise Error) in the method or a callback (OnAuthentication, OnRequest). Default status for a custom error is **500**; if you set a status code (Response_SetStatusCode before raising, per the page "set the response's HTTP Status Code") the custom value is used. Unhandled exception: 500 with DB rollback. Invalid request (body fails validation): status matching the issue, flow does not run. | https://success.outsystems.com/documentation/outsystems_developer_cloud/integration_with_external_systems/exposing_rest_apis/throw_a_custom_error_in_an_exposed_rest_api/ |
| URL format | The docs example is `GET https://<server>/PhoneAPI/rest/v1/GetContact?Id={Id}`; the page does not say whether `PhoneAPI` is the app or API name and `v1` may be the API version. Method URL paths can be customised ("Customize REST URLs"). The format `https://<env domain>/<AppName>/rest/<APIName>/<method>` is the classic OutSystems form; for ODC treat as **unverified**: after publishing, open the API's documentation/Swagger page in your app and copy the real URL; the plugin's `endpointBaseUrl` is everything before `/chunks`. | https://success.outsystems.com/documentation/outsystems_developer_cloud/integration_with_external_systems/exposing_rest_apis/expose_a_rest_api/ ; .../customize_rest_urls/ |
| POST body | Methods with a Record/Structure input (POST/PUT) require `Content-Type: application/json`; the plugin sends `application/json; charset=utf-8`. The "Receive In = Body" setting for an input structure is not described on the pages above (**unverified** text, but it is the standard Studio property: select the input parameter of the method, property **Receive In** = **Body**). | expose_a_rest_api page |
| Request size limit | **No official limit found (unverified).** Forum answers (2021) only say there is no configurable maxReceivedMessageSize for REST. A 60-s chunk is ~310 KB as base64 JSON (RECORDING-FORMAT.md), events batch of 500 about 100 KB. Stay at the plugin defaults; if a gateway answers 413 the plugin marks the chunk rejected and keeps it on the device (lower `chunkSeconds`). | https://www.outsystems.com/forums/discussion/62467/rest-api-json-response-body-limit-outsystems/ |
| Logging | No ODC setting found to switch off body/header logging of exposed REST APIs (**unverified**). A forum answer (2025) says ODC offers no direct way to adjust REST logging levels, standard logs/traces show under Monitoring. A Forge note says headers of exposed requests cannot be logged by app code. Mitigation below: the token only travels in the `Authorization` header, never in the body or URL, and never in your own log messages. | https://www.outsystems.com/forums/discussion/99429/how-to-setting-logging-level-of-a-rest-api-in-odc/ ; https://www.outsystems.com/forge/component-overview/15953/rest-troubleshooting-utils-odc |
| Crypto | **Correction:** ODC has a built-in **Security** library (Manage dependencies, source "Security"): `ComputeTextHash`, `ComputeBinaryHash`, `ComputeMAC`, `CryptoRandomInt`, `AES_NewKey`/`AES_Encrypt`/`AES_Decrypt`, `RSA_*`; prefer it. Original note: no built-in random-bytes / SHA-256 function confirmed in ODC. Forge has CryptoAPI (asset 437, SHA-256 digest, secure RNG) but its listing says "Compatible with OutSystems 11"; ODC compatibility **unverified**. Recommended: the tiny External Logic library in `odc/external-logic/DeviceTokenCrypto/` (section 3). | https://www.outsystems.com/forge/component-overview/437/cryptoapi |
| External Logic | C# class library with `OutSystems.ExternalLibraries.SDK` (nuget 1.7.0, `net8.0` asset). The current OutSystems tooling guidance says target **.NET 10** (.NET 8 support is ending); ZIP of `dotnet publish -r linux-x64`, limits 95 s / 1 GB / 5.5 MB payload. | https://www.nuget.org/packages/OutSystems.ExternalLibraries.SDK/1.7.0 |
| Base URL at runtime | No official ODC page found. Candidates from forums (all **unverified** in ODC): built-in `GetOwnerURL` / `GetServerURL`, `GetRequestDomain` (HTTPRequestHandler). See section 5 for the safe approach. | https://www.outsystems.com/forums/discussion/97406/how-do-i-get-base-url/ |

## 2. Entities (server, in the backend app; not public)

**Device**

| Attribute | Type | Notes |
|---|---|---|
| Id | Text (36) **or** Identifier auto | If Text: set it yourself to a GUID (needs a GUID action; **unverified** which exists; easiest is a Long Integer auto-number identifier and send it as text to the device, but then it is guessable: that is harmless because the token is the secret). Recommended: auto-number `Id` + the hash; the plugin treats `deviceId` as an opaque string. |
| UserId | User Identifier | `GetUserId()` at registration |
| DeviceName | Text (100) | e.g. "Hans iPhone" |
| Platform | Text (10) | `android` / `ios` |
| TokenHash | Text (64) | lowercase hex SHA-256, **unique index** |
| CreatedOn, LastSeenOn, RevokedOn | Date Time | RevokedOn = NullDate while active |

Indexes: unique on `TokenHash`; non-unique on `UserId`.

**RecordingChunk** exactly as in `STUDIO-BUILD-GUIDE.md` v0.2.0 (ChunkId unique, RecordingId, Seq, StartTs, EndTs, Bytes, CountsJson, MetaJson, Data (Binary), ReceivedOn, SentOn) **plus** `DeviceId` (Device Identifier) and `UserId` (User Identifier). Add `SensorInfoJson` (Text 2000) if you want to keep it (see section 6).

**TelemetryEvent** (server entity; do not confuse with the plugin structure `TelemetryEvent` in the library; name it `ServerTelemetryEvent` if Studio complains about a name clash)

| Attribute | Type |
|---|---|
| Id | Long Integer, auto-number |
| DeviceId | Device Identifier |
| UserId | User Identifier |
| NativeId | Long Integer (the plugin's `id`, unique per install only) |
| SessionId | Text (50) |
| Type | Text (30) |
| Ts | Long Integer (epoch ms) |
| Lat, Lon, SpeedMps, AccuracyM, PeakMps2 | Decimal |
| ReceivedOn | Date Time |
| SentOn | Date Time (NullDate = not forwarded) |

**Unique index** on `DeviceId + SessionId + NativeId + Ts` (Entity > Indexes > Add Index, Is Unique). A re-sent batch after a lost response then cannot create duplicates (the pre-check in section 7 is only an optimisation).

**DeviceHeartbeat**

| Attribute | Type |
|---|---|
| Id | Long Integer, auto-number |
| DeviceId, UserId | identifiers |
| Seq | Long Integer |
| DeviceTs | Long Integer (epoch ms, `ts` from the payload) |
| ReceivedOn | Date Time |
| IntervalSec, ProcessUptimeSec | Integer |
| AppState | Text (12) |
| BatteryLevel | Integer |
| Charging, Tracking, InTrip, Recording | Boolean |
| Network | Text (10) |
| PendingEvents, PendingChunks | Integer |
| UploadState | Text (20) |
| Platform, PluginVersion | Text |

Index: non-unique on `DeviceId + DeviceTs`. Optional unique on `DeviceId + Seq + DeviceTs` (seq restarts at 1 on each StartHeartbeat, so Seq alone is not unique).

**Aggregate budget**: heartbeats at 30 s give 2,880 rows per device per day (about 1 M rows per device per year). See the cleanup timer in section 9.

## 3. Crypto: token generation and hashing

Use the External Logic library from `odc/external-logic/DeviceTokenCrypto/` (README there has build/upload steps). It exposes server actions:

- `GenerateToken(32)` -> 43-char base64url string from `RandomNumberGenerator` (256 bit).
- `Sha256Hex(text)` -> 64-char lowercase hex.
- `FixedTimeEquals(a, b)` -> Boolean, constant time.

Alternatives (all **unverified** for ODC): Forge CryptoAPI (hash function with SHA256 default, uses `RandomNumberGenerator`); do **not** build a token from `GenerateGuid()` + hashing tricks: GUIDs are not documented as cryptographically secure. A SHA-256 without salt is fine here because the token has 256 bits of entropy (it is not a password).

Add the library as dependency (**Manage dependencies** > Server Actions > DeviceTokenCrypto) in the backend app.

## 4. Server actions

### RegisterDevice

Inputs `DeviceName` Text, `Platform` Text. Outputs `DeviceId` Text, `Token` Text, `Success` Boolean, `Message` Text. Make it a **server action called from the client** in the app (not public to anonymous): the screen/client action calling it must require a role (e.g. `Registered`/`Everyone` authenticated), and the action must itself check:

1. `If not CheckRegisteredRole()` (or your role check) or `GetUserId() = NullIdentifier()`: Success = False, Message = "not logged in", End.
2. Validate: `Platform` in {"android","ios"}, `DeviceName` length 1..100.
3. Limit devices per user (e.g. 5 active): `GetDevicesByUserId`, count where RevokedOn = NullDate; if >= 5 revoke the oldest or refuse.
4. `Token = GenerateToken(32)`; `Hash = Sha256Hex(Token)`.
5. `CreateDevice`: UserId = `GetUserId()`, DeviceName, Platform, TokenHash = Hash, CreatedOn = `CurrDateTime()`, LastSeenOn = NullDate, RevokedOn = NullDate.
6. Outputs: `DeviceId = IntegerToText(Device.Id)`, `Token = Token`. **Never write Token to a log, entity, or Message.** Mark the output parameter `Token` as **Is Mandatory**? Not needed; but do not store it in an Aggregate output or Client Variable.

Calling it twice yields two devices; that is why the client keeps the DeviceId locally (section 5). Rotation = RegisterDevice again + RevokeDevice of the old id.

### RevokeDevice(DeviceId)

1. Logged in check; `Device = GetDevice(DeviceId)`; if not found or `Device.UserId <> GetUserId()`: Success = False (do not reveal which).
2. `UpdateDevice` with RevokedOn = `CurrDateTime()`. The next upload from that device gets 401, the plugin switches to `auth_failed` and stops.

Logout flow in the app (client): 1) `ClearUpload` (deletes token on the device; also call `StopHeartbeat`), 2) `RevokeDevice(DeviceId)` while the session is still valid (if offline, retry later; the pending revoke can be stored in Local Storage), 3) clear the local DeviceId, 4) `Logout`.

## 5. Client flow after login

Mobile app `DriveMe`, after a successful login (OnApplicationReady / Home OnReady):

```
1. DeviceId = Local Storage "DeviceRegistration".DeviceId   (store ONLY the DeviceId and the user id it belongs to, NOT the token)
2. If DeviceId empty OR stored UserId <> GetUserId():
     RegisterDevice(DeviceName = <e.g. "iPhone of " + username>, Platform = <android|ios>)
     If not Success -> show Message, End
     save DeviceId (+ UserId) in Local Storage
     ConfigureUpload(EndpointBaseUrl, DeviceId, Token, UploadRecordings=True, UploadEvents=True, WifiOnly=<setting>, EventBatchSize=0)
     (Token variable goes out of scope here; do not copy it anywhere)
3. Else: GetUploadStatus. If StatusJson.configured is False (e.g. app reinstalled/ token lost) -> RegisterDevice again (rotate: RevokeDevice(old DeviceId) afterwards).
4. Optional: StartHeartbeat(IntervalSec = 30)  (needs location permission first)
```

Platform: use `GetPluginVersion`/Capacitor platform via a small client JS (`Capacitor.getPlatform()`), or the ODC `GetDeviceType` / mobile platform built-ins (**unverified** names; reading `window.Capacitor.getPlatform()` in a JS node is safe).

**EndpointBaseUrl at runtime.** The plugin needs the absolute https URL of the backend, and the mobile app's own web origin is `capacitor://localhost`/`https://localhost`, so `window.location` is wrong. Options, best first:
1. **Server action `GetTelemetryBaseUrl`** (server side, so it returns the environment's real host): `GetOwnerURL()` / `GetServerURL()` / `GetRequestDomain()` style built-ins exist in OutSystems 11 and are suggested for ODC in forum threads (**unverified in ODC**; test which one exists and what it returns, it may include the app path). Append `"/<AppName>/rest/<Api>"` per the URL you copied from the published API's documentation page.
2. **App setting / site property** `TelemetryBaseUrl` per environment (recommended fallback, always works): ODC apps can define settings (Studio: app > Settings, name varies, **unverified**), set differently in DEV and PRD. A hard-coded Text constant in the app is the last resort.
Whichever you pick, validate in the client that it starts with `https://` (the plugin rejects anything else with `INVALID_ARGUMENT`) and has no trailing path error: `<base>/chunks` must be the method URL.

Sources for options: forum https://www.outsystems.com/forums/discussion/97406/how-do-i-get-base-url/ (unverified for ODC).

## 6. Exposed REST API `Telemetry`

Studio: backend app > Logic > Integrations > REST > **Expose REST API**, name `Telemetry`. In its properties set **Authentication = Custom** (creates `OnAuthentication`). Add three methods, each **Method = POST**, with **URL path** customised (see "Customize REST URLs") to exactly `chunks`, `events`, `heartbeat`, so that the base URL + `/chunks` is the endpoint the plugin calls. Final URL of one method must be verified in the API documentation page (see section 1).

Structures (in the backend app; attribute names equal the JSON keys, case matters). **Receive In = Body** on the single input parameter of each method.

### ChunkUpload (input of `chunks`)

| Attribute | Type | Notes |
|---|---|---|
| chunkId, recordingId | Text | |
| seq | Integer | |
| startTs, endTs | Long Integer | epoch ms |
| bytes | Integer | gzip size |
| counts | RecordingSensorCounts | structure from structures.md (nested object `counts`) |
| format | Text | must be `csv-v1` |
| encoding | Text | must be `gzip+base64` |
| platform, deviceModel, osVersion, pluginVersion | Text | |
| deviceId | Text | must equal header |
| data | Text | base64 of gzip, ~310 KB; a Text attribute handles it (ODC text has no practical length limit in structures; entity Text columns do, so convert with `Base64ToBinary` before storing). **Unverified:** behaviour with multi-hundred-KB text values in exposed REST bodies. |
| sensorInfo | (ignored) | The plugin sends `sensorInfo` as a JSON **object** with free keys (`{"acc":"Bosch BMI160 ...","bar":"..."}`). A structure that does not declare it simply ignores the extra property (standard JSON deserialization; **unverified** for ODC but long-standing OutSystems behaviour). If you need it: declare it as a structure `SensorInfo` with the five attributes `acc, gyr, mag, bar, loc` (Text) and serialize it with `JSON Serialize` into `SensorInfoJson`; keys the plugin does not send stay empty. |

### EventsUpload (input of `events`)

`deviceId`, `platform`, `pluginVersion` (Text), `events` = **List of TelemetryEventIn**.

TelemetryEventIn (same shape as the library's `TelemetryEvent`, see structures.md): `id` Long Integer, `ts` Long Integer, `type` Text, `sessionId` Text, `lat`, `lon`, `speedMps`, `accuracyM`, `peakMps2` Decimal. (Attribute `id` becomes your `NativeId`.)

### HeartbeatIn (input of `heartbeat`)

Exactly the table of `HeartbeatPayload` in `structures.md`. Outputs for all methods: a small structure `Ack {ok Boolean}` (or no output; ODC then returns 200 with an empty/default body).

Because the plugin only looks at the status code, outputs are irrelevant; keep them tiny.

### OnAuthentication (the heart of the security)

Local variables: `AuthHeader` Text, `Token` Text, `DeviceIdText` Text, `Hash` Text, `DeviceIdLong` Long Integer/Identifier.

1. `AuthHeader = GetRequestHeader("Authorization")`; `DeviceIdText = GetRequestHeader("X-Device-Id")`.
2. If `Length(AuthHeader) < 15` or `Substr(AuthHeader, 0, 7) <> "Bearer "` or `DeviceIdText = ""` -> **Unauthorized**.
3. `Token = Substr(AuthHeader, 7, Length(AuthHeader) - 7)`. Parse `DeviceIdText` to the identifier (`TextToLongInteger`/`TextToIdentifier`; non-numeric -> Unauthorized; do the conversion safely, e.g. check `Length <= 18` and use a regex-free check with `Index`/digits-only helper, or catch the exception in an exception handler that goes to Unauthorized).
4. `GetDeviceById(DeviceId)`; if empty or `Device.RevokedOn <> NullDate()` -> Unauthorized.
5. `Hash = Sha256Hex(Token)`; `If not FixedTimeEquals(Hash, Device.TokenHash)` -> Unauthorized. (The hash compare is on 64-char hashes; FixedTimeEquals avoids timing leaks.)
6. Update `Device.LastSeenOn = CurrDateTime()`: only if the last value is older than 60 s (saves a write per request).
7. Keep the authenticated identity for the method: store `Device.Id` and `Device.UserId` in **server request variables**... ODC has no global request scope you can rely on (**unverified**); safest is to read the headers again in each method via a shared server action `AuthenticateDevice` (does steps 1-6 and returns `DeviceId`, `UserId`, `IsValid`), and call it in `OnAuthentication` **and** at the top of each method (a cache-free repeat of one indexed read; fine). Methods use only the values returned by `AuthenticateDevice`, never ids from the body.
8. **Unauthorized branch**: `Response_SetStatusCode(401)`, then end the callback... Per the token-auth page, "stop processing the request and return an appropriate HTTP status code". Whether ending the flow after `Response_SetStatusCode` actually skips the method body is **unverified**: to be safe also **Raise Error** with a User Exception `Unauthorized` right after `Response_SetStatusCode(401)` (custom errors honour a set status code per the "Throw a Custom Error" page; without it you would get 500, which the plugin treats as retryable backoff instead of `auth_failed`). Test with the mock-style check below.

**Test it from a terminal** (replace host and values): 
```
curl -i -X POST https://<host>/<App>/rest/Telemetry/heartbeat -H "Authorization: Bearer wrong" -H "X-Device-Id: 1" -H "Content-Type: application/json" -d '{}'
```
must print `401`; with the right token and body `200`.

### Method flows (after AuthenticateDevice succeeded)

Common: `If Body.deviceId <> DeviceIdText (header)` -> `Response_SetStatusCode(400)`, raise user exception `BadRequest`. Return early.

**chunks** (status semantics from SPEC):
1. Validate: `format = "csv-v1"`, `encoding = "gzip+base64"`, `chunkId <> ""`, `data <> ""`, `Length(chunkId) <= 100`. Fail -> **422** (SetStatusCode(422) + raise `Unprocessable`). The plugin marks the chunk **rejected** (kept on the device, no retry) for 400/413/422, so only use these for permanent faults.
2. `GetRecordingChunkByChunkId(chunkId)`: exists -> `Response_SetStatusCode(409)` + raise `Duplicate` (plugin treats 409 as success and deletes the chunk) **or** simply return 200 without writing. Both are success for the plugin; **200 is simpler** (no exception, no rollback), use 409 only if you want the distinction in logs. Do not use 409 for anything else.
3. `Base64ToBinary(data)` (BinaryData extension, see STUDIO-BUILD-GUIDE v0.2.0 B.1); `CreateRecordingChunk` with DeviceId/UserId from the **authenticated device**, ReceivedOn, `SentOn = NullDate()`, `CountsJson = JSON Serialize(counts)`.
4. Race between two identical uploads: the unique index on ChunkId throws on the second insert; handle in an exception handler (`Database Exception` or All Exceptions) by returning 200 (idempotent), not 500.
5. Return 200.

**events**:
1. `events.Length` must be 1..1000, else 422. Per event: `type` non-empty, `ts > 0`.
2. For each event: optional pre-check `GetTelemetryEventByKey(DeviceId, sessionId, id, ts)`; skip if present; else `CreateTelemetryEvent` with DeviceId/UserId from authentication. Better for speed: one aggregate fetching existing keys for this device and `ts` range, then loop. For up to 500 rows a loop of creates is OK (a few hundred ms).
3. A unique-index violation in the loop = another request already stored it; catch and continue. Return 200 (plugin acks up to the highest id sent).
4. On a transient DB failure let the exception go (500): the plugin backs off and retries; partially stored batches are fine because of idempotency.

**heartbeat**: validate `seq >= 1`, `ts > 0`; create `DeviceHeartbeat` (ReceivedOn = `CurrDateTime()`). Never fail for a duplicate (return 200). Plugin heartbeats are **not retried or queued**, so keep the method fast and do no joins.

### Status code cheat sheet (matches SPEC)

| Situation | Code | Plugin reaction |
|---|---|---|
| ok / duplicate | 200 (or 409) | success, chunk deleted / events acked |
| missing/bad/revoked token | 401 | state `auth_failed`, uploads stop until the next ConfigureUpload |
| valid token but not allowed | 403 | same as 401 |
| body deviceId <> header | 400 | chunk rejected (kept, not retried); events: batch halved, then backoff |
| invalid chunk content | 422 | chunk rejected |
| too large (gateway) | 413 | chunk rejected |
| anything else (500, 502, 503, timeouts) | | backoff 30 s doubling to 30 min |

## 7. Forwarding to the external API later

Timer `ForwardTelemetry` (every 1-5 min, **Timeout** adequate, run on one environment only):

1. `ServerTelemetryEvent` where `SentOn = NullDate()` order by Id, max 500 per run; call the consumed REST API (**Consume REST API** in the backend app) with the batch plus `DeviceId`/`UserId` and an idempotency key (e.g. `DeviceId-SessionId-NativeId-Ts`). On 2xx set `SentOn = CurrDateTime()` for those rows (bulk update by a small server action); on error stop and let the next run retry (add `Attempts` and `LastError` attributes if you want a dead-letter after N tries).
2. `RecordingChunk` where `SentOn = NullDate()` order by Id, 5-10 per run; send `Data` (gzip bytes, `Content-Encoding: gzip`) plus `MetaJson` and `ChunkId` as idempotency key. Set `SentOn` on 2xx.
3. Heartbeats are usually **not forwarded**; they are monitoring data for you.
4. Timers have a per-run time limit (**unverified** exact value for ODC timers; keep each run short with the row caps above and use the "Wake Timer" action to continue while rows remain, if available).

## 8. Heartbeat monitoring (simple)

Platform notes for users of the heartbeat (from SPEC): **Android** shows a persistent foreground-service notification ("Heartbeat active" when only the heartbeat runs). **iOS** keeps the app alive with lowest-power location updates (accuracy 3 km, distance filter 3 km, fixes ignored), which shows the **location indicator** in the status bar; users must have granted location (always/when in use per the tracking setup). Heartbeats are not queued: a gap is the signal.

Aggregates/screens for a "Devices" admin screen (role `Admin`):

- **Last heartbeat per device**: Aggregate on `Device` with a calculated attribute / sub-query `Max(DeviceHeartbeat.ReceivedOn)` grouped by DeviceId (use an Aggregate with `Group by DeviceId` and `Max`). Show age = `DiffSeconds(Last, CurrDateTime())`; red when `> 2 * IntervalSec` of the last row (use 60 s if unknown).
- **Gaps** (detail view of one device): fetch the last N rows ordered by `DeviceTs`; walk them in a server action and flag consecutive rows with `DeviceTs(n) - DeviceTs(n-1) > 2 * IntervalSec * 1000` (or `Seq` jump of more than 1 within the same uptime run: seq restarts at 1 after StartHeartbeat, so compare only while `ProcessUptimeSec` increases).
- **OS restarted the process**: consecutive rows where `ProcessUptimeSec(n) < ProcessUptimeSec(n-1)` (same seq run or seq continuing, since seq is persisted over restarts). Count them per day as a stability KPI. Seq going back to 1 means StartHeartbeat was called again (app restart by the user or a login).
- Also useful columns: `AppState`, `Network`, `BatteryLevel`, `UploadState` (`auth_failed`/`backoff` shows why uploads stall), `PendingEvents`/`PendingChunks` (growth = device cannot upload).

## 9. Security notes and housekeeping

- **HTTPS only**: ODC serves https; the plugin refuses anything else and does not follow redirects to non-https. Never put the token in a URL or body.
- **Token handling**: server stores only SHA-256; token shown once in `RegisterDevice`'s output; the client keeps only the DeviceId in Local Storage (Local Storage is plain, not encrypted). On the device the plugin keeps the token in Android Keystore (AES-GCM) / iOS Keychain (AfterFirstUnlockThisDeviceOnly).
- **Logging**: ODC records request/response metadata and traces for exposed APIs; whether request bodies or headers can end up in logs/traces is **unverified** and no setting to disable it was found. Do not log `Authorization`, the token, or bodies in your own `LogMessage`/`Trace`; in `OnAuthentication` never write the header value into an error message. Since the token is only in a header and each exception message you raise is static, it cannot leak via your messages. Review Monitoring > Logs/Traces of the API once after the first test calls and check that no token appears; if it does, tell OutSystems support and rotate the token.
- **Revocation and rotation**: RevokeDevice on logout, a "My devices" screen listing the user's devices with a Revoke button, rotation by re-registering. Consider expiring devices not seen for 90+ days (Timer: set RevokedOn where `LastSeenOn < CurrDateTime() - 90 days`).
- **Rate limiting**: no built-in per-client rate limit for exposed REST APIs confirmed in ODC (**unverified**). Mitigations: authentication happens before parsing the body (the cheap 401 path), plugin backoff on errors, cap `events` at 1000 and reject bigger bodies with 422, a per-device daily quota check in `chunks` (count of chunks received today) returning 429 (the plugin treats unknown codes as backoff), and Cloudflare/WAF in front if you own the domain (ODC default host: **unverified**).
- **Authorization**: the data owner is **always taken from the authenticated Device row** (UserId/DeviceId), never from the body.
- **Brute force**: the token has 256 bits, guessing is infeasible; `Device.Id` guessing only reaches the hash compare, which fails closed.
- **Data protection**: location and driving behaviour are personal data (GDPR): tell users, define purpose and retention.
- **Retention timers** (separate Timers, daily, off-peak):
  - `CleanupHeartbeats`: delete `DeviceHeartbeat` older than 14-30 days, in batches (delete max e.g. 5,000 rows per run; 2,880 rows per device per day at 30 s).
  - `CleanupForwarded`: delete `RecordingChunk` and `ServerTelemetryEvent` with `SentOn` older than N days (chunks are 13.5 MB per hour of recording per device).
  - `ExpireDevices`: revoke devices not seen for 90 days.

## 10. Local development with the mock server

`node odc/tools/mock-telemetry-server.mjs --device-id=1 --token=abc` implements the same contract (auth, 409/422/400, fail modes 401/409/413/500, `/heartbeat`). It is plain HTTP; the plugin demands https, so tunnel it (`cloudflared tunnel --url http://localhost:8787`) and use the tunnel URL as `endpointBaseUrl` in the test app (`example-app`, section "Upload"). Self-test: `node odc/tools/mock-telemetry-server.selftest.mjs`.
