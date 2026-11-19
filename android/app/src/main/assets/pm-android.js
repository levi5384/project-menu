// Project Menu Android shim. Loaded before the page's own scripts.
// Routes the page's relative api/* fetch calls to the native PMAndroid bridge
// so index.html runs unchanged and believes it is talking to server.py.
(function () {
  "use strict";
  var B = window.PMAndroid;
  if (!B) return;

  var CHUNK = 512 * 1024;
  var realFetch = window.fetch.bind(window);

  function respond(env) {
    // env: {status, body, type}
    return new Response(env.body == null ? "" : env.body, {
      status: env.status,
      headers: {
        "Content-Type": env.type || "application/json",
        "Cache-Control": "no-store",
        "X-Project-Menu": "1"
      }
    });
  }

  function call(fn) {
    try {
      return respond(JSON.parse(fn()));
    } catch (e) {
      return respond({ status: 500, body: JSON.stringify({ error: String(e && e.message || e) }) });
    }
  }

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () {
        var s = String(r.result);
        var i = s.indexOf(",");
        resolve(i >= 0 ? s.slice(i + 1) : "");
      };
      r.onerror = function () { reject(r.error || new Error("read failed")); };
      r.readAsDataURL(blob);
    });
  }

  function toBlob(body) {
    if (body == null) return new Blob([]);
    if (body instanceof Blob) return body;
    return new Blob([body]);
  }

  async function bodyText(body) {
    if (body == null) return "";
    if (typeof body === "string") return body;
    return await toBlob(body).text();
  }

  async function upload(name, body) {
    var blob = toBlob(body);
    var id = B.uploadBegin(name);
    if (!id) throw new Error("could not start upload");
    try {
      for (var off = 0; off < blob.size; off += CHUNK) {
        var b64 = await blobToBase64(blob.slice(off, Math.min(off + CHUNK, blob.size)));
        if (!B.uploadChunk(id, b64)) throw new Error("write failed");
      }
    } catch (e) {
      try { B.uploadAbort(id); } catch (e2) {}
      return respond({ status: 500, body: JSON.stringify({ error: String(e && e.message || e) }) });
    }
    return call(function () { return B.uploadEnd(id); });
  }

  window.fetch = async function (input, init) {
    init = init || {};
    var urlStr, method, body = init.body;
    if (typeof Request !== "undefined" && input instanceof Request) {
      urlStr = input.url;
      method = init.method || input.method;
      if (body == null && method !== "GET" && method !== "HEAD") body = await input.clone().blob();
    } else {
      urlStr = String(input);
      method = init.method || "GET";
    }
    method = method.toUpperCase();
    var u;
    try { u = new URL(urlStr, location.href); } catch (e) { return realFetch(input, init); }
    if (u.origin !== location.origin || u.pathname.indexOf("/api/") !== 0) return realFetch(input, init);

    var path = u.pathname, q = u.searchParams;
    if (path === "/api/data" && method === "GET") return call(function () { return B.getData(); });
    if (path === "/api/data" && method === "PUT") {
      var text = await bodyText(body);
      return call(function () { return B.putData(text); });
    }
    if (path === "/api/upload" && method === "POST") return upload(q.get("name") || "file", body);
    if (path === "/api/media" && method === "DELETE") {
      var n = q.get("name") || "";
      return call(function () { return B.deleteMedia(n); });
    }
    return respond({ status: 404, body: "{}" });
  };

  // Native file saving (Export). Resolves true when written, false if cancelled/failed.
  var saveSeq = 0, pending = {};
  window.__pmSaveDone = function (id, ok) {
    var cb = pending[id];
    delete pending[id];
    if (cb) cb(!!ok);
  };
  window.pmNative = {
    saveTextFile: function (filename, mime, text) {
      var id = String(++saveSeq);
      return new Promise(function (resolve) {
        pending[id] = resolve;
        try { B.saveTextFile(id, String(filename || "export.txt"), String(mime || "text/plain"), String(text == null ? "" : text)); }
        catch (e) { delete pending[id]; resolve(false); }
      });
    }
  };
})();
