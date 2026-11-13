#!/usr/bin/env python3
"""Builds app/src/main/assets/words.txt: the autocorrect / prediction dictionary.

Usage:
  python3 tools/gen_words.py en_50k.txt /usr/share/hunspell/en_US.dic /usr/share/hunspell/en_US.aff \
      > app/src/main/assets/words.txt

- en_50k.txt: word frequency list (FrequencyWords by Hermit Dave, CC BY-SA 4.0)
- en_US.dic/.aff: Hunspell US English dictionary, used to keep only correctly spelled words
  and to restore apostrophes and capitals ("dont" -> "don't", "im" -> "I'm", "english" -> "English").
Output: one word per line, most common first.
"""
import re
import sys

LIMIT = 40000

def load_affixes(aff_path):
    rules = {}  # flag -> (kind, cross, [(strip, add, regex)])
    lines = open(aff_path, encoding="utf-8", errors="ignore").read().splitlines()
    i = 0
    while i < len(lines):
        parts = lines[i].split()
        if len(parts) == 4 and parts[0] in ("SFX", "PFX") and parts[2] in ("Y", "N"):
            kind, flag, cross, n = parts[0], parts[1], parts[2], int(parts[3])
            entries = []
            for j in range(1, n + 1):
                p = lines[i + j].split()
                strip = "" if p[2] == "0" else p[2]
                add = "" if p[3] == "0" else p[3].split("/")[0]
                cond = p[4] if len(p) > 4 else "."
                rx = re.compile((cond + "$") if kind == "SFX" else ("^" + cond))
                entries.append((strip, add, rx))
            rules[flag] = (kind, cross == "Y", entries)
            i += n + 1
        else:
            i += 1
    return rules

def expand(stem, flags, rules):
    out = {stem}
    sfx_forms = [stem]
    for f in flags:
        r = rules.get(f)
        if not r or r[0] != "SFX":
            continue
        for strip, add, rx in r[2]:
            if rx.search(stem) and (not strip or stem.endswith(strip)):
                w = (stem[: len(stem) - len(strip)] if strip else stem) + add
                out.add(w)
                if r[1]:
                    sfx_forms.append(w)
    for f in flags:
        r = rules.get(f)
        if not r or r[0] != "PFX":
            continue
        for strip, add, rx in r[2]:
            for base in (sfx_forms if r[1] else [stem]):
                if rx.search(base) and (not strip or base.startswith(strip)):
                    out.add(add + base[len(strip):])
    return out

def main(freq_path, dic_path, aff_path):
    rules = load_affixes(aff_path)
    valid = set()
    for line in open(dic_path, encoding="utf-8", errors="ignore").read().splitlines()[1:]:
        if not line.strip():
            continue
        stem, _, flags = line.partition("/")
        valid |= expand(stem.strip(), flags.strip(), rules)

    lower_ok = {w for w in valid if w == w.lower()}
    cap_form = {}
    for w in valid:
        if w != w.lower() and w[0].isupper() and w[1:] == w[1:].lower():
            cap_form.setdefault(w.lower(), w)
    # "dont" -> "don't" when the apostrophe-less spelling isn't itself a word
    contraction = {}
    for w in valid:
        if "'" in w and not w.endswith("'s"):
            key = w.replace("'", "").lower()
            if key not in lower_ok:
                contraction.setdefault(key, w)

    # The subtitle corpus splits contractions ("don" + "t"), so they rank far too low and
    # ones whose bare spelling is also a word ("cant", "wont") are lost. Put them near the top.
    common_contractions = [
        "I'm", "don't", "can't", "it's", "I'll", "won't", "didn't", "that's", "I've", "you're",
        "what's", "doesn't", "isn't", "there's", "I'd", "he's", "she's", "let's", "wasn't",
        "couldn't", "wouldn't", "shouldn't", "haven't", "aren't", "they're", "we're", "you'll",
        "you've", "we'll", "we've", "they'll", "they've", "who's", "here's", "where's", "how's",
        "hasn't", "weren't", "ain't", "you'd", "he'll", "she'll", "it'll", "y'all",
    ]
    out, seen = [], set()
    for line in open(freq_path, encoding="utf-8"):
        word = line.split(" ")[0].strip()
        if not re.fullmatch(r"[a-z]+", word):
            continue
        if len(word) == 1 and word not in ("a", "i"):
            continue
        if word == "i":
            form = "I"
        elif word in contraction:
            form = contraction[word]
        elif word in lower_ok:
            form = word
        elif word in cap_form:
            form = cap_form[word]
        else:
            continue
        if form in seen:
            continue
        seen.add(form)
        out.append(form)
        if len(out) >= LIMIT:
            break
    for c in common_contractions:
        if c in seen:
            out.remove(c)
    out = out[:60] + common_contractions + out[60:]
    sys.stdout.write("\n".join(out[:LIMIT]) + "\n")

if __name__ == "__main__":
    main(*sys.argv[1:4])
