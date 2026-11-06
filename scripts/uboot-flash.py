#!/usr/bin/env python3
"""Flash a chunked image from U-Boot over TFTP: erase the span, then per chunk
tftpboot -> mmc write -> mmc read back -> crc32 compare.
usage: uboot-flash.py <tty> <plan.txt> <end_sector>"""
import os, subprocess, sys, re
tty, plan, end = sys.argv[1], sys.argv[2], int(sys.argv[3], 0)
UB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ubcmd.py")
def ub(cmd, t):
    return subprocess.run(["python3", "-I", UB, tty, str(t), cmd], capture_output=True, text=True).stdout
out = ub("mmc dev 0; mmc erase 0 %#x" % end, 300)
print("erase:", " ".join(l for l in out.splitlines() if "erase" in l.lower() or "blocks" in l.lower())[-160:], flush=True)
bad = 0
for line in open(plan):
    name, start, cnt, crc = line.split()
    nbytes = "%#x" % (int(cnt, 0) * 512)
    o = ub("tftpboot 0x02080000 %s; mmc write 0x02080000 %s %s; mmc read 0x0a080000 %s %s; crc32 0x0a080000 %s" % (name, start, cnt, start, cnt, nbytes), 120)
    m = re.findall(r"==> ([0-9a-f]{8})", o)
    got = m[-1] if m else "????????"
    ok = got == crc
    bad += not ok
    print("%s %s sectors at %s: %s" % (name, cnt, start, "OK" if ok else "BAD readback=%s want=%s" % (got, crc)), flush=True)
print("DONE, %d bad chunks" % bad, flush=True)
