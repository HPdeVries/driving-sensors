/*
 * JS node: RecDownloadCsv (synchronous)
 * Inputs:  FileName (Text) - e.g. "recording-" + RecordingId + ".csv"
 * Outputs: Bytes (Integer) - size of the downloaded file, 0 when nothing is loaded
 * Downloads all appended chunks as one csv-v1 file (single header line).
 */
var rec = window.__dsRec || { parts: [] };
var csv = "t_ms,sensor,x,y,z,v4,v5\n" + rec.parts.join("");
$parameters.Bytes = 0;
if (rec.parts.length) {
  var blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
  var url = URL.createObjectURL(blob);
  var a = document.createElement("a");
  a.href = url;
  a.download = $parameters.FileName || "recording.csv";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(function () { URL.revokeObjectURL(url); }, 10000);
  $parameters.Bytes = blob.size;
}
