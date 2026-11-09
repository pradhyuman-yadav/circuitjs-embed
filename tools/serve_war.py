#!/usr/bin/env python3
"""Serve a folder locally, by default the compiled app on port 8888.

Caching is disabled because a cached circuitjs1.nocache.js points at compiled
files that the next build replaces, which leaves a blank page.

Usage:
    python tools/serve_war.py                 # http://127.0.0.1:8888/circuitjs.html
    python tools/serve_war.py --dir . --port 8889
"""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class NoCacheHandler(SimpleHTTPRequestHandler):
    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", default=str(ROOT / "war"), help="folder to serve")
    parser.add_argument("--port", type=int, default=8888)
    args = parser.parse_args()

    handler = partial(NoCacheHandler, directory=args.dir)
    with ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
        print(f"Serving {args.dir} at http://127.0.0.1:{args.port}/", flush=True)
        server.serve_forever()
