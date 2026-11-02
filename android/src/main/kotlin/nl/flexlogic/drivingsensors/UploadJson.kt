package nl.flexlogic.drivingsensors

import org.json.JSONObject

object EventJson {
    /** Writes a stored event into [o] (nulls omitted); shared by getBufferedEvents and the uploader. */
    fun <T : JSONObject> fill(o: T, e: StoredEvent): T {
        val r = e.record
        o.put("id", e.id)
        o.put("ts", r.ts)
        o.put("type", r.type)
        o.put("sessionId", r.sessionId)
        r.lat?.let { o.put("lat", it) }
        r.lon?.let { o.put("lon", it) }
        r.speedMps?.let { o.put("speedMps", it) }
        r.accuracyM?.let { o.put("accuracyM", it) }
        r.peakMps2?.let { o.put("peakMps2", it) }
        return o
    }
}

object ChunkJson {
    /** Everything of a `RecordingChunk` except `data`; shared by getRecordingChunk and the uploader. */
    fun <T : JSONObject> fillHeader(o: T, metaJson: String, device: DeviceInfo): T {
        val meta = JSONObject(metaJson)
        for (k in meta.keys()) o.put(k, meta.get(k))
        o.put("format", "csv-v1")
        o.put("encoding", "gzip+base64")
        o.put("platform", device.platform)
        o.put("deviceModel", device.model)
        o.put("osVersion", device.osVersion)
        o.put("pluginVersion", device.pluginVersion)
        o.put("sensorInfo", device.sensorInfo())
        return o
    }
}
