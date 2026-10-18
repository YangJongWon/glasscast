#!/usr/bin/env python3
"""Desktop stand-in for the GlassCast phone app.

Serves web/viewer.html and the same /stream, /snapshot.jpg and /status
endpoints the iOS and Android apps expose, looping over a folder of JPEG
frames. Use it to work on the viewer, or to set up OBS/VLC, without glasses.

    ffmpeg -i clip.mp4 -vf "fps=24,scale=504:896" frames/%04d.jpg
    python3 tools/mock_server.py frames/ --port 8080

Standard library only.
"""

import argparse
import json
import socketserver
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BOUNDARY = "glasscast"
MAX_CLIENTS = 4
ROOT = Path(__file__).resolve().parent.parent


class FrameHub:
    """Holds only the newest frame; slow clients skip frames instead of queueing."""

    def __init__(self):
        self.cond = threading.Condition()
        self.frame = None
        self.seq = 0
        self.clients = 0
        self.fps = 0.0
        self.size = (0, 0)

    def publish(self, frame):
        with self.cond:
            self.frame = frame
            self.seq += 1
            self.cond.notify_all()

    def wait_newer(self, seq, timeout=2.0):
        with self.cond:
            self.cond.wait_for(lambda: self.seq != seq, timeout=timeout)
            return self.seq, self.frame


def jpeg_size(data):
    """Reads width/height from the first JPEG SOF marker."""
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        length = int.from_bytes(data[i + 2 : i + 4], "big")
        if marker in (0xC0, 0xC1, 0xC2):
            h = int.from_bytes(data[i + 5 : i + 7], "big")
            w = int.from_bytes(data[i + 7 : i + 9], "big")
            return w, h
        i += 2 + length
    return 0, 0


def produce(hub, frames, fps):
    interval = 1.0 / fps
    hub.size = jpeg_size(frames[0])
    hub.fps = float(fps)
    i = 0
    next_at = time.monotonic()
    while True:
        hub.publish(frames[i % len(frames)])
        i += 1
        next_at += interval
        time.sleep(max(0.0, next_at - time.monotonic()))


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def server_bind(self):
        # HTTPServer.server_bind does a reverse-DNS lookup that can stall for
        # many seconds on some networks; the name is never used here.
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]


def make_handler(hub, viewer):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def send_bytes(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path in ("/", "/index.html"):
                self.send_bytes(200, "text/html; charset=utf-8", viewer)
            elif path == "/status":
                body = json.dumps(
                    {
                        "app": "GlassCast",
                        "platform": "mock",
                        "state": "streaming",
                        "fps": hub.fps,
                        "width": hub.size[0],
                        "height": hub.size[1],
                        "clients": hub.clients,
                    }
                ).encode()
                self.send_bytes(200, "application/json", body)
            elif path == "/snapshot.jpg":
                if hub.frame is None:
                    self.send_bytes(503, "text/plain", b"No frame yet\n")
                else:
                    self.send_bytes(200, "image/jpeg", hub.frame)
            elif path == "/stream":
                self.stream()
            else:
                self.send_bytes(404, "text/plain", b"Not found\n")

        def stream(self):
            with hub.cond:
                if hub.clients >= MAX_CLIENTS:
                    full = True
                else:
                    full = False
                    hub.clients += 1
            if full:
                self.send_bytes(503, "text/plain", b"Too many viewers\n")
                return
            try:
                self.send_response(200)
                self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Connection", "close")
                self.end_headers()
                seq = -1
                while True:
                    seq, frame = hub.wait_newer(seq)
                    if frame is None:
                        continue
                    head = (
                        f"--{BOUNDARY}\r\nContent-Type: image/jpeg\r\n"
                        f"Content-Length: {len(frame)}\r\n\r\n"
                    ).encode()
                    self.wfile.write(head + frame + b"\r\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with hub.cond:
                    hub.clients -= 1

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("frames", type=Path, help="folder of .jpg frames, played in name order")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--fps", type=int, default=24)
    args = parser.parse_args()

    files = sorted(p for p in args.frames.iterdir() if p.suffix.lower() in (".jpg", ".jpeg"))
    if not files:
        parser.error(f"no .jpg files in {args.frames}")
    frames = [p.read_bytes() for p in files]
    viewer = (ROOT / "web" / "viewer.html").read_bytes()

    hub = FrameHub()
    threading.Thread(target=produce, args=(hub, frames, args.fps), daemon=True).start()
    server = Server(("0.0.0.0", args.port), make_handler(hub, viewer))
    print(f"GlassCast mock: {len(frames)} frames at {args.fps} fps on http://localhost:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
