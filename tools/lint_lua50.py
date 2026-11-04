#!/usr/bin/env python3
"""Flags Lua 5.1+ syntax in addons for the 1.12 client, which runs Lua 5.0.

Usage: python3 tools/lint_lua50.py <addon dir>...
"""
import re
import sys
from pathlib import Path

CHECKS = [
    (re.compile(r"#"), "length operator # (use table.getn / string.len)"),
    (re.compile(r"(?<![\w\]\)])%|[\w\)\]]\s*%\s*[\w\(]"), "modulo operator % (use math.mod)"),
    (re.compile(r"\bselect\s*\("), "select() (5.1)"),
    (re.compile(r"\bstring\.(match|gmatch)\b|\bmath\.fmod\b"), "5.1 library function"),
    (re.compile(r"[\w\)\]\"']\s*:\s*(find|sub|gsub|lower|upper|format|len|rep|byte|match|gmatch)\s*\("), "string method call (5.1)"),
    (re.compile(r"\.\.\.(?!\s*\))"), "... outside a parameter list (5.1; use arg)"),
    (re.compile(r"\bgoto\b|//"), "5.2+ syntax"),
]


def strip(source):
    """Blanks out comments and string literals, keeping line structure."""
    out = []
    i, n = 0, len(source)
    while i < n:
        if source.startswith("--[[", i):
            j = source.find("]]", i)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", source[i:j]))
            i = j
        elif source.startswith("--", i):
            j = source.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif source.startswith("[[", i):
            j = source.find("]]", i)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", source[i:j]))
            i = j
        elif source[i] in "\"'":
            q = source[i]
            j = i + 1
            while j < n and source[j] != q:
                j += 2 if source[j] == "\\" else 1
            out.append('"' + " " * (j - i - 1) + '"')
            i = j + 1
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def main():
    problems = 0
    for root in sys.argv[1:]:
        for path in sorted(Path(root).rglob("*.lua")):
            for number, line in enumerate(strip(path.read_text()).splitlines(), 1):
                # `...` is fine as the last parameter of a function definition.
                code = re.sub(r"function\b[^(]*\([^)]*\)", "function()", line)
                for pattern, message in CHECKS:
                    if pattern.search(code):
                        print(f"{path}:{number}: {message}: {line.strip()}")
                        problems += 1
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
