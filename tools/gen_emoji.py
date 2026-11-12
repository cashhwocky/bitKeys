#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji.txt from Unicode's emoji-test.txt.

Usage: python3 tools/gen_emoji.py path/to/emoji-test.txt > app/src/main/assets/emoji.txt

Output format (UTF-8):
  @<group name>                  starts a category
  <emoji>[\t<skin-tone variant>...]   one base emoji per line, variants tab-separated
Only fully-qualified emoji are kept; the "Component" group is skipped.
"""
import sys

MODS = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}

def key(cps):
    return tuple(c for c in cps if c not in MODS and c != 0xFE0F)

groups = []          # [(name, [base_key, ...])]
bases = {}           # key -> [emoji_str, variants]
group = None
for line in open(sys.argv[1], encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("# group:"):
        name = line.split(":", 1)[1].strip()
        group = None if name == "Component" else (name, [])
        if group:
            groups.append(group)
        continue
    if not line or line.startswith("#") or group is None:
        continue
    data, rest = line.split(";", 1)
    if "fully-qualified" not in rest.split("#", 1)[0]:
        continue
    cps = [int(x, 16) for x in data.split()]
    s = "".join(chr(c) for c in cps)
    k = key(cps)
    if any(c in MODS for c in cps):
        if k in bases:
            bases[k][1].append(s)
        continue
    if k in bases:
        continue
    bases[k] = [s, []]
    group[1].append(k)

out = []
for name, keys in groups:
    out.append("@" + name)
    for k in keys:
        s, variants = bases[k]
        out.append("\t".join([s] + variants))
sys.stdout.write("\n".join(out) + "\n")
