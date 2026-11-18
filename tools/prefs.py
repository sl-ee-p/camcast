#!/usr/bin/env python3
"""Read or edit CamCast's DataStore file (settings.preferences_pb) for device tests on a locked phone.
usage: prefs.py FILE [key=value ...]   values: true/false, integers, anything else is a string."""
import sys

def varint(b, i):
    n = s = 0
    while True:
        c = b[i]; i += 1; n |= (c & 0x7F) << s; s += 7
        if c < 0x80: return n, i

def enc_varint(n):
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        c = n & 0x7F; n >>= 7
        if n: out.append(c | 0x80)
        else: out.append(c); return bytes(out)

def ld(field, payload): return enc_varint(field << 3 | 2) + enc_varint(len(payload)) + payload

def parse(b):
    prefs, i = {}, 0
    while i < len(b):
        _, i = varint(b, i); n, i = varint(b, i); entry = b[i:i + n]; i += n
        j, key, val = 0, None, None
        while j < len(entry):
            tag, j = varint(entry, j); m, j = varint(entry, j)
            if tag >> 3 == 1: key = entry[j:j + m].decode()
            else: val = entry[j:j + m]
            j += m
        vt, k = varint(val, 0)
        if vt & 7 == 0: v, _ = varint(val, k); v = bool(v) if vt >> 3 == 1 else v
        else: m, k = varint(val, k); v = val[k:k + m].decode()
        prefs[key] = v
    return prefs

def encode(prefs):
    out = b""
    for k, v in prefs.items():
        if isinstance(v, bool): val = enc_varint(1 << 3) + enc_varint(int(v))
        elif isinstance(v, int): val = enc_varint(3 << 3) + enc_varint(v)
        else: val = ld(5, v.encode())
        out += ld(1, ld(1, k.encode()) + ld(2, val))
    return out

path = sys.argv[1]
prefs = parse(open(path, "rb").read())
for arg in sys.argv[2:]:
    k, v = arg.split("=", 1)
    prefs[k] = {"true": True, "false": False}.get(v, int(v) if v.lstrip("-").isdigit() else v)
if sys.argv[2:]: open(path, "wb").write(encode(prefs))
print({k: ("***" if k == "password" else v) for k, v in prefs.items()})
