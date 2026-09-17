"""Local-only occupancy feed for testing Gym Buddy without IoT hardware."""

import argparse
import json
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_COUNT = 4_294_967_295


class DemoState:
    def __init__(self, count):
        self.lock = threading.Lock()
        self.count = count
        self.sequence = 0
        self.timestamp = int(time.time() * 1000)
        self.event = "none"
        self.session_id = f"demo-{uuid.uuid4()}"

    def heartbeat(self):
        with self.lock:
            self.sequence += 1
            self.timestamp = int(time.time() * 1000)

    def change(self, delta):
        with self.lock:
            updated = self.count + delta
            if updated < 0 or updated > MAX_COUNT:
                return False, self._snapshot()
            self.count = updated
            self.sequence += 1
            self.timestamp = int(time.time() * 1000)
            self.event = "entry" if delta > 0 else "exit"
            return True, self._snapshot()

    def snapshot(self):
        with self.lock:
            return self._snapshot()

    def _snapshot(self):
        return {
            "schemaVersion": 1,
            "deviceId": "counter-01",
            "roomId": "room-01",
            "sessionId": self.session_id,
            "sequence": self.sequence,
            "count": self.count,
            "event": self.event,
            "timestamp": self.timestamp,
            "status": "valid",
            "confidence": None,
        }


def handler_for(state):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, status, payload):
            body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/v1/state":
                self.reply(200, state.snapshot())
            elif self.path == "/health":
                self.reply(200, {"status": "ok"})
            else:
                self.reply(404, {"error": "not_found"})

        def do_POST(self):
            if self.path not in ("/dev/increment", "/dev/decrement"):
                self.reply(404, {"error": "not_found"})
                return
            delta = 1 if self.path == "/dev/increment" else -1
            changed, snapshot = state.change(delta)
            if changed:
                print(f"Gym count: {snapshot['count']}", flush=True)
                self.reply(200, snapshot)
            else:
                self.reply(409, {"error": "count_limit", "state": snapshot})

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--count", type=int, default=0)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 <= args.count <= MAX_COUNT:
        parser.error("port must be 1..65535 and count must be 0..4294967295")

    state = DemoState(args.count)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(state))

    def heartbeat():
        while True:
            time.sleep(1)
            state.heartbeat()

    threading.Thread(target=heartbeat, daemon=True).start()
    print(f"Demo gym IoT feed: http://127.0.0.1:{args.port}/v1/state", flush=True)
    print("Use POST /dev/increment or POST /dev/decrement to change the count.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
