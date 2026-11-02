/*
 * JS node: RecAppendChunk (asynchronous)
 * Inputs:  Base64 (Text) - RecordingChunk.Data as base64 (gzip of a csv-v1 chunk)
 * Outputs: Success (Boolean), ErrorMessage (Text), Lines (Integer) - data lines in this chunk
 * Decompresses in the browser (DecompressionStream) and keeps the CSV rows in window.__dsRec,
 * so the large text never goes through ODC variables. Call once per chunk, in Seq order.
 */
(async function () {
  $parameters.Success = false;
  $parameters.ErrorMessage = "";
  $parameters.Lines = 0;
  try {
    var rec = window.__dsRec || (window.__dsRec = { parts: [], chunks: 0 });
    var b64 = $parameters.Base64 || "";
    var bin = Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); });
    var stream = new Blob([bin]).stream().pipeThrough(new DecompressionStream("gzip"));
    var text = await new Response(stream).text();
    var nl = text.indexOf("\n");
    var body = nl >= 0 ? text.slice(nl + 1) : "";
    if (body && body.charAt(body.length - 1) !== "\n") body += "\n";
    rec.parts.push(body);
    rec.chunks++;
    $parameters.Lines = body ? body.split("\n").length - 1 : 0;
    $parameters.Success = true;
  } catch (e) {
    $parameters.ErrorMessage = String((e && e.message) || e);
  }
  $resolve();
})();
