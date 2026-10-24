#!/usr/bin/env python3
"""Checks that a GlassCast endpoint (phone app or mock_server.py) speaks the protocol.

    python3 tools/check_stream.py 192.168.0.12:8080

Fetches /, /status and /snapshot.jpg, reads ~3 s of /stream to measure the
frame rate, then opens more than the allowed number of viewers to check the
limit and that dropping one viewer leaves the others streaming. If /status
reports audio on, it also reads ~2 s of /audio and checks its rate.
Standard library only.
"""

import json
import socket
import sys
import time
import urllib.error
import urllib.request

BOUNDARY = b"--glasscast"
MAX_CLIENTS = 4


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=5) as res:
        return res.status, res.headers.get("Content-Type", ""), res.read()


class StreamReader:
    """Minimal multipart/x-mixed-replace reader over a raw socket."""

    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.sendall(f"GET /stream HTTP/1.1\r\nHost: {host}\r\n\r\n".encode())
        self.buf = b""
        head = self._read_until(b"\r\n\r\n")
        self.status_line = head.split(b"\r\n", 1)[0].decode()
        self.headers = head.decode(errors="replace").lower()

    def _fill(self):
        chunk = self.sock.recv(65536)
        if not chunk:
            raise ConnectionError("stream closed by server")
        self.buf += chunk

    def _read_until(self, marker):
        while marker not in self.buf:
            self._fill()
        head, self.buf = self.buf.split(marker, 1)
        return head

    def _read_exact(self, n):
        while len(self.buf) < n:
            self._fill()
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def next_frame(self):
        part_head = self._read_until(b"\r\n\r\n")
        if BOUNDARY not in part_head:
            raise ValueError(f"missing boundary in part header: {part_head[:80]!r}")
        length = None
        for line in part_head.split(b"\r\n"):
            if line.lower().startswith(b"content-length:"):
                length = int(line.split(b":", 1)[1])
        if length is None:
            raise ValueError("part without Content-Length")
        data = self._read_exact(length)
        self._read_exact(2)  # trailing CRLF
        if not data.startswith(b"\xff\xd8"):
            raise ValueError("part is not a JPEG")
        return data

    def close(self):
        self.sock.close()


def check_audio(host, port, available, check):
    sock = socket.create_connection((host, port), timeout=5)
    try:
        sock.sendall(f"GET /audio HTTP/1.1\r\nHost: {host}\r\n\r\n".encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(65536)
            if not chunk:
                break
            buf += chunk
        head, _, body = buf.partition(b"\r\n\r\n")
        status_line = head.split(b"\r\n", 1)[0].decode()
        if not available:
            check("GET /audio is 503 while sound is off", "503" in status_line, status_line)
            return
        check("GET /audio is a WAV stream", "200" in status_line and b"audio/wav" in head.lower(), status_line)
        while len(body) < 44:
            body += sock.recv(65536)
        rate = int.from_bytes(body[24:28], "little")
        check("audio header is 16-bit mono WAV", body[:4] == b"RIFF" and body[8:12] == b"WAVE"
              and body[22:24] == b"\x01\x00" and body[34:36] == b"\x10\x00", f"{rate} Hz")
        received, start = len(body) - 44, time.monotonic()
        while time.monotonic() - start < 2:
            received += len(sock.recv(65536))
        seconds = received / 2 / rate / (time.monotonic() - start)
        check("audio arrives in real time", 0.7 < seconds < 1.6, f"{seconds:.2f} s of audio per second")
    finally:
        sock.close()


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    target = sys.argv[1].removeprefix("http://").rstrip("/")
    host, _, port = target.partition(":")
    port = int(port or 8080)
    base = f"http://{host}:{port}"
    failures = 0

    def check(name, ok, detail=""):
        nonlocal failures
        failures += not ok
        print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""))

    status, ctype, body = get(base, "/")
    check("GET / serves the viewer", status == 200 and "text/html" in ctype and b"GlassCast" in body)

    status, ctype, body = get(base, "/status")
    info = json.loads(body)
    check("GET /status returns JSON", status == 200 and {"state", "fps", "clients"} <= info.keys(), json.dumps(info))

    try:
        status, ctype, body = get(base, "/snapshot.jpg")
        check("GET /snapshot.jpg returns a JPEG", ctype == "image/jpeg" and body[:2] == b"\xff\xd8", f"{len(body)} bytes")
    except urllib.error.HTTPError as e:
        check("GET /snapshot.jpg returns a JPEG", False, f"HTTP {e.code} - is the glasses stream running?")

    check_audio(host, port, info.get("audio", False), check)

    try:
        get(base, "/nope")
        check("unknown path is 404", False)
    except urllib.error.HTTPError as e:
        check("unknown path is 404", e.code == 404)

    reader = StreamReader(host, port)
    check("GET /stream is multipart", "200" in reader.status_line and "multipart/x-mixed-replace" in reader.headers)
    frames, start = 0, time.monotonic()
    while time.monotonic() - start < 3:
        reader.next_frame()
        frames += 1
    fps = frames / (time.monotonic() - start)
    check("stream delivers frames", frames > 5, f"{fps:.1f} fps")

    _, _, body = get(base, "/status")
    check("/status counts the viewer", json.loads(body)["clients"] >= 1)

    extra = [StreamReader(host, port) for _ in range(MAX_CLIENTS - 1)]
    overflow = StreamReader(host, port)
    check(f"viewer #{MAX_CLIENTS + 1} is rejected", "503" in overflow.status_line, overflow.status_line)
    overflow.close()

    extra[0].close()
    time.sleep(0.5)
    try:
        for _ in range(5):
            reader.next_frame()
            extra[1].next_frame()
        check("other viewers survive one disconnecting", True)
    except Exception as e:  # noqa: BLE001
        check("other viewers survive one disconnecting", False, str(e))
    for r in [reader, *extra[1:]]:
        r.close()

    time.sleep(0.5)
    _, _, body = get(base, "/status")
    check("viewer count drops back to 0", json.loads(body)["clients"] == 0, body.decode())

    print("\nAll checks passed." if not failures else f"\n{failures} check(s) failed.")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
