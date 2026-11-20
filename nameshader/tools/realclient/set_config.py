import sys

path = sys.argv[1]
changes = {}
for arg in sys.argv[2:]:
    key, value = arg.split("=", 1)
    section, name = key.split(".", 1)
    changes[(section, name)] = value
lines = open(path, encoding="utf-8").read().split("\n")
section = None
done = set()
for i, line in enumerate(lines):
    if line and not line.startswith(" ") and line.endswith(":"):
        section = line[:-1]
        continue
    stripped = line.strip()
    if not line.startswith("  ") or line.startswith("   ") or ":" not in stripped:
        continue
    name = stripped.split(":", 1)[0]
    if (section, name) in changes:
        lines[i] = "  " + name + ": " + changes[(section, name)]
        done.add((section, name))
missing = sorted(set(changes) - done)
if missing:
    raise SystemExit("keys not found: " + ", ".join(s + "." + n for s, n in missing))
open(path, "w", encoding="utf-8").write("\n".join(lines))
