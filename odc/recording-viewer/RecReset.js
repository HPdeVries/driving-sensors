/*
 * JS node: RecReset (DrivingSensorsAPI, RecordingDetail screen)
 * Inputs: none. Outputs: none. Synchronous.
 * Clears the in-browser buffer that RecAppendChunk fills. Call before loading a recording.
 */
window.__dsRec = { parts: [], chunks: 0 };
