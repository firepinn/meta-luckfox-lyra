#!/usr/bin/env python3
"""Minimal read-only TFTP server (RFC 1350 + blksize/tsize/timeout options,
RFC 2347-2349), enough for U-Boot's tftpboot. usage: tftpd.py <root> [port]"""
import os, socket, struct, sys, threading

ROOT = os.path.realpath(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 69

def err(sock, addr, code, msg):
    sock.sendto(struct.pack("!HH", 5, code) + msg.encode() + b"\0", addr)

def serve(req, addr):
    parts = req[2:].split(b"\0")
    name, opts = parts[0].decode(), parts[2:-1]
    o = {opts[i].decode().lower(): opts[i + 1].decode() for i in range(0, len(opts) - 1, 2)}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("", 0))
    s.settimeout(2)
    path = os.path.realpath(os.path.join(ROOT, name.lstrip("/")))
    if not path.startswith(ROOT + os.sep) or not os.path.isfile(path):
        err(s, addr, 1, "not found"); return
    data = open(path, "rb").read()
    blk = min(int(o.get("blksize", 512)), 65464)
    oack = {}
    if "blksize" in o: oack["blksize"] = str(blk)
    if "tsize" in o: oack["tsize"] = str(len(data))
    if "timeout" in o: oack["timeout"] = o["timeout"]

    def xfer(pkt, want):
        for _ in range(8):
            s.sendto(pkt, addr)
            try:
                while True:
                    r, a = s.recvfrom(1500)
                    if a != addr: continue
                    op, n = struct.unpack("!HH", r[:4])
                    if op == 4 and n == want: return True
                    if op == 5: return False
            except socket.timeout:
                continue
        return False

    if oack:
        pkt = struct.pack("!H", 6) + b"".join(k.encode() + b"\0" + v.encode() + b"\0" for k, v in oack.items())
        if not xfer(pkt, 0): return
    n = 1
    off = 0
    while True:
        chunk = data[off:off + blk]
        if not xfer(struct.pack("!HH", 3, n & 0xFFFF) + chunk, n & 0xFFFF): 
            print(f"{name}: aborted at block {n}", flush=True); return
        off += blk; n += 1
        if len(chunk) < blk: break
    print(f"{name}: sent {len(data)} bytes to {addr[0]}", flush=True)

ls = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
ls.bind(("", PORT))
print(f"tftpd serving {ROOT} on udp/{PORT}", flush=True)
while True:
    req, addr = ls.recvfrom(1500)
    if struct.unpack("!H", req[:2])[0] == 1:
        threading.Thread(target=serve, args=(req, addr), daemon=True).start()
