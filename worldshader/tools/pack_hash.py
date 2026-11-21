#!/usr/bin/env python3
"""worldshader - check or update the WORLDSHADER_HASH line of the shared core shaders.

    python3 -I worldshader/tools/pack_hash.py          # check, exit 1 if a hash is stale
    python3 -I worldshader/tools/pack_hash.py --write  # update the hash lines in place

The hash is the first 16 hex digits of SHA-256 over the file text without the hash line, the same as
PackInstaller.contentHash in Java. Run --write after every edit of rendertype_outline.vsh/fsh.
"""
import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = os.path.join(ROOT, "pack", "assets", "minecraft", "shaders", "core")
FILES = ["rendertype_outline.vsh", "rendertype_outline.fsh"]
LINE = re.compile(r"(?m)^#define WORLDSHADER_HASH h([0-9a-f]+)\n")


def content_hash(text):
    return hashlib.sha256(LINE.sub("", text).encode("utf-8")).hexdigest()[:16]


def main(argv):
    write = "--write" in argv
    ok = True
    for name in FILES:
        path = os.path.join(CORE, name)
        text = open(path, encoding="utf-8", newline="").read()
        m = LINE.search(text)
        if not m:
            print("ERR no hash line:", name)
            ok = False
            continue
        h = content_hash(text)
        if m.group(1) == h:
            print("OK ", name, h)
            continue
        if write:
            text = text[:m.start(1)] + h + text[m.end(1):]
            open(path, "w", encoding="utf-8", newline="").write(text)
            print("SET", name, h)
        else:
            print("ERR stale hash:", name, m.group(1), "!=", h)
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
