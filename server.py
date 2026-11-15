"""Tiny local server for Project Menu.

Serves index.html, saves all edits to projects.json, and stores attached
files in the media/ folder next to this file. Only listens on 127.0.0.1,
so nothing is reachable from other machines.

Usage:  python server.py [port] [--no-browser]
"""
import json
import mimetypes
import os
import re
import secrets
import shutil
import sys
import webbrowser
from datetime import date, datetime
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "projects.json")
BACKUP_DIR = os.path.join(HERE, "backups")
MEDIA_DIR = os.path.join(HERE, "media")
TRASH_DIR = os.path.join(MEDIA_DIR, ".trash")
args = [a for a in sys.argv[1:] if not a.startswith("--")]
PORT = int(args[0]) if args else 8765
CHUNK = 1024 * 1024

mimetypes.add_type("audio/webm", ".webm")  # recordings; browsers play it in <video> too
mimetypes.add_type("audio/mp4", ".m4a")


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=HERE, **kw)

    def end_headers(self):
        # Always revalidate so an edited index.html shows up on a normal reload
        if "Cache-Control" not in "".join(h.decode("latin-1") for h in getattr(self, "_headers_buffer", [])):
            self.send_header("Cache-Control", "no-cache")
        super().end_headers()

    def log_message(self, fmt, *args):
        pass  # keep the console quiet

    def _send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Project-Menu", "1")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, code, obj):
        return self._send(code, json.dumps(obj).encode("utf-8"))

    # ---- GET ----
    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/data":
            if not os.path.exists(DATA):
                return self._send(404, b"{}")
            with open(DATA, "rb") as f:
                return self._send(200, f.read())
        if path.startswith("/media/"):
            return self._serve_media(unquote(path[len("/media/"):]))
        return super().do_GET()

    def _serve_media(self, name):
        """Serve a media file with HTTP Range support so videos can seek."""
        fname = os.path.basename(name)
        fpath = os.path.join(MEDIA_DIR, fname)
        if not fname or not os.path.isfile(fpath):
            return self._send(404, b"not found", "text/plain")
        size = os.path.getsize(fpath)
        ctype = mimetypes.guess_type(fname)[0] or "application/octet-stream"
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        status = 200
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):  # suffix range: last N bytes
                    start = max(0, size - int(m.group(2)))
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.end_headers()
                    return
                status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        try:
            with open(fpath, "rb") as f:
                f.seek(start)
                left = length
                while left > 0:
                    buf = f.read(min(CHUNK, left))
                    if not buf:
                        break
                    self.wfile.write(buf)
                    left -= len(buf)
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ---- PUT: save project data ----
    def do_PUT(self):
        if urlparse(self.path).path != "/api/data":
            return self._send(404)
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            data = json.loads(raw)
            assert isinstance(data.get("nodes"), list) and isinstance(data.get("tags"), list)
        except Exception:
            return self._json(400, {"error": "invalid data"})
        # Daily backup of the previous file before overwriting
        if os.path.exists(DATA):
            os.makedirs(BACKUP_DIR, exist_ok=True)
            bk = os.path.join(BACKUP_DIR, f"projects-{date.today().isoformat()}.json")
            if not os.path.exists(bk):
                shutil.copyfile(DATA, bk)
        tmp = DATA + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        os.replace(tmp, DATA)
        return self._json(200, {"ok": True})

    # ---- POST: upload a media file ----
    def do_POST(self):
        u = urlparse(self.path)
        if u.path != "/api/upload":
            return self._send(404)
        name = parse_qs(u.query).get("name", ["file"])[0]
        safe = re.sub(r"[^A-Za-z0-9._-]+", "_", os.path.basename(name)).strip("._")[-80:] or "file"
        fname = f"{datetime.now():%Y%m%d-%H%M%S}-{secrets.token_hex(3)}-{safe}"
        os.makedirs(MEDIA_DIR, exist_ok=True)
        length = int(self.headers.get("Content-Length", 0))
        fpath = os.path.join(MEDIA_DIR, fname)
        try:
            with open(fpath, "wb") as f:
                left = length
                while left > 0:
                    buf = self.rfile.read(min(CHUNK, left))
                    if not buf:
                        break
                    f.write(buf)
                    left -= len(buf)
            if left > 0:
                raise IOError("upload interrupted")
        except Exception as e:
            if os.path.exists(fpath):
                os.remove(fpath)
            return self._json(500, {"error": str(e)})
        return self._json(200, {"url": "media/" + fname, "name": name})

    # ---- DELETE: move a media file to media/.trash ----
    def do_DELETE(self):
        u = urlparse(self.path)
        if u.path != "/api/media":
            return self._send(404)
        fname = os.path.basename(parse_qs(u.query).get("name", [""])[0])
        src = os.path.join(MEDIA_DIR, fname)
        if not fname or not os.path.isfile(src):
            return self._json(404, {"error": "not found"})
        os.makedirs(TRASH_DIR, exist_ok=True)
        shutil.move(src, os.path.join(TRASH_DIR, fname))
        return self._json(200, {"ok": True})


if __name__ == "__main__":
    url = f"http://127.0.0.1:{PORT}/"
    # Without this, Windows lets a second copy bind the same port silently.
    ThreadingHTTPServer.allow_reuse_address = False
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError:
        print(f"Port {PORT} is busy; Project Menu is probably already running.")
        if "--no-browser" not in sys.argv:
            webbrowser.open(url)
        sys.exit(0)
    print(f"Project Menu running at {url}  (Ctrl+C to stop)")
    if "--no-browser" not in sys.argv:
        webbrowser.open(url)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
