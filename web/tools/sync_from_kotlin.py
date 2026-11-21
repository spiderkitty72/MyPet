#!/usr/bin/env python3
"""Regenerates web/js/data.js from the Kotlin sources, so the web version uses exactly the
same editions, rules, sprites, icons and pixel font as the Android app.

Run from the repository root:  python3 web/tools/sync_from_kotlin.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/mypet/core"
COMPONENTS = ROOT / "app/src/main/java/com/mypet/app/ui/Components.kt"
OUT = ROOT / "web/js/data.js"

STR = re.compile(r'"((?:[^"\\]|\\.)*)"')


def strings(text):
    return [s.replace('\\"', '"') for s in STR.findall(text)]


def kv(block, key, pattern=r'"((?:[^"\\]|\\.)*)"'):
    m = re.search(rf"\b{key}\s*=\s*{pattern}", block)
    if not m:
        sys.exit(f"missing {key}")
    return m.group(1)


def parse_editions():
    src = (CORE / "Editions.kt").read_text()
    m = re.search(r"private fun egg\(pattern: List<String>\) = PixelArt\.parse\((.*?)\n    \)", src, re.S)
    egg_rows = strings(m.group(1))

    def egg(pattern):
        out = []
        for row in egg_rows:
            out.append(row)
        # The template is built with string concatenation: rebuild it line by line.
        rows = []
        tmpl = re.findall(r'^\s*("(?:[^"]*)"(?:\s*\+\s*pattern\[\d\]\s*\+\s*"[^"]*")?),', m.group(1), re.M)
        for line in tmpl:
            parts = re.findall(r'"([^"]*)"|pattern\[(\d)\]', line)
            rows.append("".join(p[0] if p[1] == "" else pattern[int(p[1])] for p in parts))
        return rows

    editions = []
    blocks = re.split(r"\n    // -{10,} \w+\n", src)[1:]
    for block in blocks:
        desc_m = re.search(r"description = (.*?),\n\s*shellColor", block, re.S)
        rules_m = re.search(r"rules = EditionRules\((.*?)\n        \),", block, re.S)
        rules_src = rules_m.group(1)
        rules = {}
        for key, val in re.findall(r"(\w+) = ([^,\n]+(?:\([^)]*\))?)", rules_src):
            val = val.strip()
            if key == "games":
                continue
            if key in ("bedtime", "wakeTime"):
                a, b = [int(x) for x in val.split("*")] if "*" in val else (int(val), 1)
                rules[key] = a * b
            elif val in ("true", "false"):
                rules[key] = val == "true"
            else:
                rules[key] = float(val) if "." in val else int(val)
        rules["games"] = re.findall(r"GameKind\.(\w+)", re.search(r"games = listOf\((.*?)\)", rules_src).group(1))
        forms = {}
        form_re = re.compile(
            r"(\w+) = Form\(\"([^\"]+)\", \"([^\"]+)\", Stage\.(\w+), \"((?:[^\"\\]|\\.)*)\", "
            r"(egg\(listOf\((.*?)\)\)|PixelArt\.parse\((.*?)\))(?:, lifespanDays = ([\d.]+))?\),",
            re.S,
        )
        for fm in form_re.finditer(block):
            slot, fid, name, stage, blurb, _, egg_pat, art, life = fm.groups()
            sprite = egg(strings(egg_pat)) if egg_pat else strings(art)
            forms[slot] = {
                "id": fid, "name": name, "stage": stage, "blurb": blurb,
                "sprite": sprite, "lifespanDays": float(life) if life else 0,
            }
        color = lambda k: "#" + kv(block, k, r"0x([0-9A-Fa-f]{8})")[2:]
        editions.append({
            "id": kv(block, "id"),
            "name": kv(block, "name"),
            "tagline": kv(block, "tagline"),
            "description": "".join(strings(desc_m.group(1))),
            "shellColor": color("shellColor"),
            "shellAccent": color("shellAccent"),
            "lcdColor": color("lcdColor"),
            "inkColor": color("inkColor"),
            "rules": rules,
            "forms": forms,
        })
    if len(editions) != 4 or any(len(e["forms"]) != 9 for e in editions):
        sys.exit("unexpected edition structure: %s" % [(e["id"], len(e["forms"])) for e in editions])
    return editions


def parse_icons(path, obj):
    src = path.read_text()
    m = re.search(rf"object {obj} \{{(.*?)\n\}}", src, re.S)
    body = m.group(1)
    icons = {}
    for name, art in re.findall(r"val (\w+) = PixelArt\.parse\((.*?)\n    \)", body, re.S):
        icons[name] = strings(art)
    for name, ref in re.findall(r"val (\w+) = com\.mypet\.core\.Icons\.(\w+)", body):
        icons[name] = ("ref", ref)
    for name, ref in re.findall(r"val (\w+) = (\w+)\.flipped\(\)", body):
        icons[name] = [row[::-1] for row in icons[ref]]
    return icons


def parse_font():
    src = (CORE / "Glyphs.kt").read_text()
    font = {}
    for ch, art in re.findall(r"'(.)' to g\((.*?)\)", src):
        font[ch] = strings(art)
    return font


def main():
    editions = parse_editions()
    icons = parse_icons(CORE / "Glyphs.kt", "Icons")
    actions = parse_icons(COMPONENTS, "ActionIcons")
    for k, v in list(actions.items()):
        if isinstance(v, tuple):
            actions[k] = icons[v[1]]
    font = parse_font()
    dump = lambda o: json.dumps(o, indent=1, ensure_ascii=False)
    OUT.write_text(
        "// Generated by web/tools/sync_from_kotlin.py from the Kotlin sources. Do not edit by hand.\n"
        "// All names, characters and pixel art are original to MyPet.\n\n"
        f"export const EDITIONS = {dump(editions)};\n\n"
        f"export const ICONS = {dump(icons)};\n\n"
        f"export const ACTION_ICONS = {dump(actions)};\n\n"
        f"export const FONT = {dump(font)};\n"
    )
    print(f"wrote {OUT.relative_to(ROOT)}: {len(editions)} editions, {len(icons)} icons, {len(font)} glyphs")


if __name__ == "__main__":
    main()
