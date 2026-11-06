#!/usr/bin/env python3
"""Compile a panel-mipi-dbi init script into the firmware blob the kernel's
drivers/gpu/drm/tiny/panel-mipi-dbi.c loads.

Input format (same as notro/panel-mipi-dbi's mipi-dbi-cmd):
    command 0x11            # MIPI DCS command, no parameters
    sleep 120               # delay in ms (encoded as NOP with one parameter)
    command 0x3a 0x55       # command with parameters
Blank lines and '#' comments are ignored.
"""
import sys

MAGIC = b"MIPI DBI" + bytes(7)
VERSION = 1


def compile_script(text):
    out = bytearray(MAGIC + bytes([VERSION]))
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        word, *args = line.split()
        if word == "sleep":
            if len(args) != 1:
                raise SystemExit(f"line {lineno}: sleep takes one value in ms")
            ms = int(args[0], 0)
            if not 0 < ms <= 255:
                raise SystemExit(f"line {lineno}: sleep must be 1..255 ms")
            out += bytes([0x00, 1, ms])
        elif word == "command":
            vals = [int(a, 0) for a in args]
            if not vals or any(not 0 <= v <= 255 for v in vals):
                raise SystemExit(f"line {lineno}: bad command bytes")
            if vals[0] == 0x00:
                raise SystemExit(f"line {lineno}: 0x00 is reserved for sleep")
            params = vals[1:]
            if len(params) > 255:
                raise SystemExit(f"line {lineno}: too many parameters")
            out += bytes([vals[0], len(params)] + params)
        else:
            raise SystemExit(f"line {lineno}: unknown keyword {word!r}")
    if len(out) < len(MAGIC) + 1 + 2:
        raise SystemExit("script has no commands")
    return bytes(out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: mipi-dbi-cmd.py <script.txt> <out.bin>")
    with open(sys.argv[1]) as f:
        blob = compile_script(f.read())
    with open(sys.argv[2], "wb") as f:
        f.write(blob)
