import json
import sys

text = sys.argv[1]
parts = []
for i, ch in enumerate(text):
    t = i / max(1, len(text) - 1)
    r = int(255 - 90 * t)
    g = int(120 + 100 * t) ^ (i * 7 % 16)
    b = int(170 - 60 * t) ^ (i * 5 % 16)
    parts.append({"text": ch, "color": "#%02X%02X%02X" % (r, g, b)})
print(json.dumps([""] + parts, ensure_ascii=False))
