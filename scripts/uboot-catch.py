#!/usr/bin/env python3
"""Spam the console until U-Boot's '=>' prompt appears, then run one command.
usage: uboot-catch.py <tty> <timeout_s> <command...>"""
import os, sys, termios, time, select
tty, limit, cmd = sys.argv[1], float(sys.argv[2]), " ".join(sys.argv[3:])
fd = os.open(tty, os.O_RDWR | os.O_NOCTTY)
a = termios.tcgetattr(fd)
a[0] = 0; a[1] = 0; a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL; a[3] = 0
a[4] = a[5] = termios.B1500000; a[6][termios.VMIN] = 0; a[6][termios.VTIME] = 0
termios.tcsetattr(fd, termios.TCSANOW, a)
buf = b""; end = time.time() + limit
while time.time() < end:
    os.write(fd, b" ")
    r, _, _ = select.select([fd], [], [], 0.05)
    if r:
        buf += os.read(fd, 65536)
        if b"stop autoboot" in buf or buf.rstrip().endswith(b"=>"):
            break
else:
    sys.exit("no U-Boot prompt within %.0fs" % limit)
for _ in range(10):
    os.write(fd, b"\x03"); time.sleep(0.05)
os.write(fd, b"\r"); time.sleep(0.5)
for ch in cmd:
    os.write(fd, ch.encode()); time.sleep(0.002)
os.write(fd, b"\r")
out = b""; end = time.time() + 4
while time.time() < end:
    r, _, _ = select.select([fd], [], [], 0.1)
    if r:
        out += os.read(fd, 65536)
print(out.decode("ascii", "replace")[-400:])
