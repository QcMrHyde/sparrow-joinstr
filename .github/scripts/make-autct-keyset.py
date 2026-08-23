#!/usr/bin/env python3
"""Build a small aut-ct keyset for testing, plus the WIF that can prove against it.

A real keyset is a snapshot of the taproot UTXO set, produced with bitcoin-cli dumptxoutset,
utxo_to_sqlite and aut-ct's filter_utxos.py. This makes an equivalent one from made up keys so a
local autct server can be exercised without a synced node.

Two details decide whether autct accepts it, and neither is written down:

  1. The file is one line of space separated x-only hex, because filter_utxos.py writes
     " ".join(scriptpubkey[4:]), stripping the 5120 prefix off each taproot output script.
  2. The entries are taproot OUTPUT keys, not internal keys. The server applies BIP-341
     tap_tweak to the proving key before looking it up (see lib.rs, tap_tweak(&secp, None)),
     so an untweaked key gives error -8, "provided key is not in the keyset".

Usage:
    python3 make-autct-keyset.py            # writes keyset.aks and mywif.txt
    autct -M serve -k mycontext:keyset.aks -n regtest -p 23333
"""

from coincurve import PrivateKey, PublicKey
import hashlib

N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141

def wif(b):
    payload = b'\xef' + b + b'\x01'
    chk = hashlib.sha256(hashlib.sha256(payload).digest()).digest()[:4]
    alpha = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
    n = int.from_bytes(payload + chk, 'big'); out = ''
    while n:
        n, r = divmod(n, 58); out = alpha[r] + out
    return out

def tagged(tag, msg):
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + msg).digest()

def taproot_output_xonly(k_int):
    """BIP-341 key path tweak with no script tree, as bitcoin's tap_tweak(None) does."""
    p = PrivateKey.from_int(k_int)
    if p.public_key.format()[0] == 3:          # BIP-340: use the even-y internal key
        k_int = N - k_int
        p = PrivateKey.from_int(k_int)
    xonly = p.public_key.format()[1:]
    t = int.from_bytes(tagged("TapTweak", xonly), "big") % N
    Q = PublicKey.combine_keys([p.public_key, PrivateKey.from_int(t).public_key])
    return p, Q.format()[1:].hex()

keys = []
for i in range(60):
    _, q = taproot_output_xonly(7000000 + i)
    keys.append(q)

p, q = taproot_output_xonly(9000003)
keys.append(q)
open("keyset.aks", "wb").write((" ".join(keys)).encode())
open("mywif.txt", "w").write(wif(p.secret))
print("taproot output key in keyset:", q)
print("wrote", len(keys), "output keys")
