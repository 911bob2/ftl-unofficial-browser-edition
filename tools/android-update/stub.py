"""A stand-in for GitHub's releases/latest API, for the Android update check on the emulator (docs/TESTS.md).

    py -3 tools/android-update/stub.py [--port 8765] [--tag v2.0.2]

The debug build asks it after `updateurl http://10.0.2.2:8765/latest` (debug console, docs/ANDROID.md "Updates").
  GET /latest            the release in GitHub's shape, with an ETag; 304 when If-None-Match matches it
  GET /release/<tag>     the release's page (what Download opens)
  GET /set/<tag>         the tag /latest answers with from now on; /set/noapk/<tag> without an APK; /set/status/<code> an error
Every request is printed with its headers, so what the app sends can be read off.
"""
import argparse
import hashlib
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"tag": "v2.0.2", "apk": True, "status": 200}


def release(host):
    tag = state["tag"]
    assets = [{"name": "FTL.Unofficial.apk", "content_type": "application/vnd.android.package-archive", "size": 7386718,
               "browser_download_url": f"http://{host}/download/{tag}/FTL.Unofficial.apk"}] if state["apk"] else []
    return {"tag_name": tag, "name": f"FTL: Unofficial Browser Edition {tag.lstrip('v')}", "html_url": f"http://{host}/release/{tag}",
            "draft": False, "prerelease": False, "assets": assets}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send(self, status, body=b"", kind="application/json; charset=utf-8", extra=None):
        self.send_response(status)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        if body:
            self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        print(f"{self.command} {self.path}", {k: v for k, v in self.headers.items()}, flush=True)
        host = self.headers.get("Host", "10.0.2.2")
        parts = [p for p in self.path.split("?")[0].split("/") if p]
        if parts[:1] == ["set"]:
            if parts[1:2] == ["status"]:
                state["status"] = int(parts[2])
            else:
                state.update(apk=parts[1:2] != ["noapk"], tag=parts[-1], status=200)
            print("state", state, flush=True)
            return self.send(200, json.dumps(state).encode())
        if parts == ["latest"]:
            if state["status"] != 200:
                return self.send(state["status"], b'{"message":"stub error"}')
            body = json.dumps(release(host)).encode()
            etag = 'W/"' + hashlib.sha256(body).hexdigest()[:32] + '"'
            if self.headers.get("If-None-Match") == etag:
                return self.send(304, extra={"ETag": etag})
            return self.send(200, body, extra={"ETag": etag})
        if parts[:1] == ["release"] and len(parts) == 2:
            page = f"<!doctype html><title>Stub release {parts[1]}</title><body style='font:24px sans-serif;padding:2em'>" \
                   f"<h1>Stub release {parts[1]}</h1><p>FTL.Unofficial.apk would be here.</p></body>"
            return self.send(200, page.encode(), "text/html; charset=utf-8")
        return self.send(404, b'{"message":"Not Found"}')


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--tag", default="v2.0.2")
    a = p.parse_args()
    state["tag"] = a.tag
    print(f"stub on 127.0.0.1:{a.port} (10.0.2.2 in the emulator), tag {a.tag}", flush=True)
    try:
        ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
