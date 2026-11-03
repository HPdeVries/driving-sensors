# DrivingSensorsAPI: public share link for one recording

Goal: a TelemetryAdmin can create a share link for one recording; anyone with the link (no login) sees that
recording only. RecordingDetail itself and all existing server actions stay TelemetryAdmin-only. Do not change them.

## 1. Entity RecordingShare
| Attribute | Type | Notes |
|---|---|---|
| Id | Long Integer (auto) | |
| RecordingId | Text (100) | the RecordingId of RecordingChunk; unique index |
| DeviceId | Device Identifier | for display on DeviceDetail |
| Token | Text (50) | GUID from GenerateGuid(); unique index |
| CreatedOn | DateTime | |
| CreatedBy | User Identifier | |
| IsRevoked | Boolean | default False |

## 2. Server actions
Admin (check TelemetryAdmin role, raise NotAuthorized otherwise, same as the existing ones):
- `CreateRecordingShare(RecordingId Text, DeviceId Long Integer) -> Token Text`: if an active (not revoked) share for this RecordingId exists, return its Token; otherwise create one with a new GUID.
- `RevokeRecordingShare(RecordingId Text)`: set IsRevoked = True on the active share (the link stops working at once; a new Create gives a new token).
- `GetRecordingShare(RecordingId Text) -> Token Text` ("" when none active).

Public (no role check; the token is the authorization). Both first resolve the token with a shared private helper
`ResolveShareToken(Token) -> RecordingId Text, IsValid Boolean`: IsValid only when a RecordingShare with exactly this Token
exists, IsRevoked = False, and Token is not empty. When not valid: raise a user exception "Link not valid", and return no data.
- `GetSharedRecordingChunks(Token Text)` -> list of ChunkRef, same as GetRecordingChunks but for the resolved RecordingId.
- `GetSharedChunkData(Token Text, ChunkId Text) -> Base64 Text`: fetch the RecordingChunk by ChunkId AND RecordingId = resolved RecordingId
  (a ChunkId of another recording must return nothing / raise "Link not valid"); return BinaryToBase64(Data).
Never return DeviceId, UserId, user name, email or the Device record from these public actions.

## 3. DeviceDetail, Recordings table
Per row a "Share" action: CreateRecordingShare, then show a popup with the full link
`<app base URL>/PublicRecording?Token=<Token>` (build it in a JS node: `window.location.origin + "/DrivingSensorsAPI/PublicRecording?Token=" + encodeURIComponent(Token)`;
if the app's URL path is different, use the path of the current page up to the app name), a "Copy" button
(JS: `navigator.clipboard.writeText(Link)`), an "Open" link (new tab) and a "Revoke link" button (RevokeRecordingShare, with confirmation).
Show a small "Shared" tag on rows that have an active share.

## 4. New screen PublicRecording (input Token Text), role: Anonymous (public, no login)
Same content and the same JS nodes (RecReset, RecAppendChunk, RecBuildSeries, RecDownloadCsv, code VERBATIM as in RecordingDetail)
and the same UI as RecordingDetail (summary card, coverage/gaps, acceleration chart, raw axes accordion, speed chart, gaps table,
CSV download, Google Maps link, track map), but:
- Chunks come from GetSharedChunkData/GetSharedRecordingChunks with the Token, never from the admin actions.
- No device id, user, navigation menu or links to admin screens. Title "Shared drive recording".
- Empty or invalid token, or the server raises "Link not valid": show only "This link is not valid or was revoked."
- Use a layout without the app menu (e.g. LayoutBlank) so anonymous users see no admin navigation.
Copy the RecordingDetail screen as a starting point and only change the data loading and the points above.
