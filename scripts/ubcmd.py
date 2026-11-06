#!/usr/bin/env python3
"""Send one command to the U-Boot prompt on a serial port and print the reply.
usage: ubcmd.py <tty> <seconds> <command...>"""
import os, sys, termios, time, select
tty, wait, cmd = sys.argv[1], float(sys.argv[2]), " ".join(sys.argv[3:])
fd = os.open(tty, os.O_RDWR | os.O_NOCTTY)
a = termios.tcgetattr(fd)
a[0] = 0; a[1] = 0; a[2] = termios.CS8 | termios.CREAD | termios.CLOCAL; a[3] = 0
a[4] = a[5] = termios.B1500000; a[6][termios.VMIN] = 0; a[6][termios.VTIME] = 0
termios.tcsetattr(fd, termios.TCSANOW, a)
out = b""
def rd(t, stop=None):
    global out
    end = time.time() + t
    while time.time() < end:
        r, _, _ = select.select([fd], [], [], 0.1)
        if r:
            out += os.read(fd, 65536)
            if stop and out.rstrip().endswith(stop):
                return
rd(0.2)
for ch in cmd:
    os.write(fd, ch.encode()); time.sleep(0.002)
os.write(fd, b"\r")
rd(wait, stop=b"=>")
sys.stdout.write(out.decode("ascii", "replace"))
