# DrivingSensorsAPI: delete devices from the management screens

Deleting removes the device and ALL its data (recording chunks, telemetry events, heartbeats, recording shares).
A device can have a lot of chunk rows, so deletion runs in the background in batches instead of in one request.

1. Device: new attribute `DeleteRequestedOn` (DateTime, default null). Lists/screens hide devices where it is set
   (or show them greyed out with tag "Deleting...").
2. Server action `DeleteDeviceAdmin(DeviceId)` (TelemetryAdmin, raise NotAuthorized otherwise): set `RevokedOn` (if null) and
   `DeleteRequestedOn = CurrDateTime()` (the token stops working immediately), then `Wake` the cleanup timer.
3. Timer `CleanupOldData` (or a new timer `PurgeDeletedDevices`, woken by step 2 and also scheduled daily): for each Device with
   DeleteRequestedOn set, delete in batches of max 5000 rows per loop: RecordingShare, RecordingChunk, ServerTelemetryEvent,
   DeviceHeartbeat (all where DeviceId = that device), then the Device row itself. Stop near the timer time limit; the next run continues.
4. UI:
   - Devices list: a "Delete" action per row; DeviceDetail: a "Delete device" button (danger style).
   - Both open a confirmation: "Delete device <name> and all its recordings, events and heartbeats? This cannot be undone." -> DeleteDeviceAdmin,
     then back to the Devices list with message "Device is being deleted".
   - Only visible to TelemetryAdmin.
