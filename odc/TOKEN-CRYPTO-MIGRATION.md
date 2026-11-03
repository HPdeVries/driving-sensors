# DrivingSensorsAPI: replace DeviceTokenCrypto (External Logic) with the built-in Security library

Existing devices and data do not need to keep working (agreed 2026-11-03): every device registers again afterwards.

## 1. Dependencies
- Add from source **Security**: `ComputeMAC`, `AES_NewKey` (and `ComputeTextHash` only if ComputeMAC turns out unusable).
- After step 3/4 work, remove the dependency on the External Logic library `DeviceTokenCrypto` (GenerateToken, Sha256Hex, FixedTimeEquals). Nothing else may still reference it.

## 2. Secret setting
- New setting `DeviceTokenMacKey` (Text, **Secret**). Value: a random key, e.g. the output of one `AES_NewKey()` call, set in ODC Portal per environment (Development now). Never log it.
- If the setting is empty at runtime: RegisterDevice fails with Message "server not configured", OnAuthentication -> 401.

## 3. Helper server action `HashDeviceToken(Token Text) -> Hash Text`
- `Hash = ComputeMAC(Token, Site.DeviceTokenMacKey)` with HMAC-SHA256 (pick the SHA-256 option if ComputeMAC has an algorithm input).
- Keep the output exactly as ComputeMAC returns it (hex or base64, whatever it is); everything stores and compares via this one helper.
- Why a MAC instead of a plain hash: a copy of the Device table alone cannot be used to check guessed tokens without the key.

## 4. Changes
**Device.TokenHash**: change length to 200 (format of ComputeMAC unknown); keep the unique index.

**RegisterDevice**, step "generate token":
- `Token = AES_NewKey()` (a random key from a cryptographic generator). Check its output: if it is shorter than 32 characters, use `Token = AES_NewKey() + AES_NewKey()`.
  Remove characters that cause trouble in an HTTP header only if they occur (base64 `+ / =` are fine in a Bearer header; whitespace/newlines are not, so `Trim` and remove line breaks).
- `TokenHash = HashDeviceToken(Token)`. Rest unchanged (Token is returned once, never logged or stored).

**AuthenticateDevice / OnAuthentication** (all endpoints: events, chunks, heartbeat):
- Replace `Sha256Hex(Token)` + `FixedTimeEquals(...)` with `HashDeviceToken(Token) = Device.TokenHash` (a plain `=` is fine:
  without the key an attacker cannot produce a value to time against). Everything else unchanged: 401 + Unauthorized exception on mismatch, revoked, missing headers.

**One-off reset** (old hashes can never match the new MAC anyway, this only cleans up monitoring): in the timer or a TelemetryAdmin
button "Revoke all devices" (with confirmation): set `RevokedOn = CurrDateTime()` on every Device where RevokedOn is null. Keep their data.

## 5. Test (after publish to Development)
1. Without headers / wrong token on `.../heartbeat` -> HTTP 401 (curl, see BACKEND-UPLOAD-GUIDE section 4 "Test it from a terminal").
2. In DriveMe: `RegisterDevice` again, then `ConfigureUpload` with the new DeviceId/Token, then `UploadNow` and `StartHeartbeat`:
   heartbeat and uploads appear in monitoring under the new device. The old device shows as revoked.
3. ODC logs: no token or key values anywhere.

## 6. Afterwards
- Delete the External Logic library `DeviceTokenCrypto` in ODC Portal (Integrate > External logic) once no app uses it (user decision).
- Mobile side: the plugin reports `auth_failed` in `GetUploadStatus` for the old token; that is expected until step 5.2.
