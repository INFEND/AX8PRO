#!/usr/bin/env python3
"""Restore the exact Tektur variable font provided in the user's archive.
The public upstream version is pinned by commit and Git blob digest. The
archive's sfnt header and two differing metadata tables are embedded below.
The final SHA-256 must equal the supplied archive, otherwise fail the build.
No runtime font downloads, and no substitution of a different font.
"""
import argparse
import base64
import hashlib
import struct
import urllib.request
import zlib
from pathlib import Path

URL = "https://raw.githubusercontent.com/google/fonts/f42a0c3666f18672a0397f3e7557430009d2ade5/ofl/tektur/Tektur%5Bwdth%2Cwght%5D.ttf"
UPSTREAM_BLOB = "d1cf18b3a394808ee2a122ab0794499d8e7743bf"
EXPECTED = "1ff1792ecc4728cf011d31e43a53a5c97e82f5c7cc7b9f23af24b209106e962c"
HEADER = bytes.fromhex("000100000013010000040030474445469db8a255000003500000062847504f5355ee1f250000746400004a3047535542c295cab1000022780000098648564152e677d89300002c0000000eb04f532f329aa249c7000001c800000060535441547bd6f113000002a8000000a6636d617081550e390000197000000908667661720e6edf0b000002280000008067617370000000100000014400000008676c7966695086000000be9400009894677661727516865d000157280001243668656164243278410000019000000036686865610cb00be90000016c00000024686d74781da9b4f000003ab0000011a26c6f63618288a83e0000109c000008d46d617870047800550000014c000000206e616d65d98008ed0000097800000722706f7374e80eb40f00004c540000280f7072657068068c850000013c00000007")
PATCH = {
    b"head": "eNpjYGRgYGBk9PC1/9sUz2/zlYGJ+QVQhOH2Eq07IPrBZg6f/9z/2Th3gcXZGJhAogBw3A0U",
    b"name": "eNqdVMtu00AUPU7d0kcaKhaVQCxGCKEiJSmUh6CVkChQBGp5taISj4VJ3NSljaPYpoQ16pIVH4D4AsQn8AF8BCuWfAALzlxfJ5OqsECWPWfuzD33zL13DMB4BxiB508A+OT/Vuyh7P9SXELF/6F4BAv+F8U+TvsfFY9i0X+meIz2G4rHMevPKZ5w8KR3UPqseAqXS23FZQdPe2e8VcUVXPVmFZ8caPOAijeu2IPBT8UlnMJ3xSNk/abYxwy+Kh7FWXxQPIZJvFd8jPvfKh4fnN2z6nYVT5LnheIpnnJDcRknsKx4mpzXFFdoP6f4OLWVFc8QAQ8Qo4s9BORfR0gU4RZtbTQ5ayPht3mEZZn7AzTwGneoOCVHQFtMa7M/FnzFfE08I2S0bhJHaGGbvptETY7buE1rQtZQokX8tpjZkGsBxwZ3RGSN6N8hY0jbqnpbvIYnjJSJr6HmKr935bsuWrrcucUnPGLd9bwpqlLGSLCIeT5Wk/Xu0Jqgzjdi/LrkrsX1h1ihkg3Rl5BhRfKVCnPMiCn2eYKuqMx37HJsOPk0Gt2qNJKLULzvkdeQvyN7XebVIQZ7iqfibbXFsvciFdrXOMqG4xZqArzhG0lNX2lm9zm3OozkfoVZeSw4ZU7Mf2bosNe+PHWyNvnuYYffFn0SvKO1QbRH7SG7LGV+uo5eI6v2FLb3Ms5SzWPCVWuvyQnzmhvppkS6JkCP861+Hm0/BMpfdFwsfLmWttwNI/2RSv7a0qVG4tiItq75fuudSYyuKswkSl4Xm9NtwaF4FR2Xc+XWlOo6UiMbdbevNd9Z1bOnump492rCWdylqqMrcO5SoSCQE7TkDM2hKhc9ZyN3pMID1jqrn1fI4L5TI7c2Nd6h0Dn/Ud14gc8VLHGXvROx/BcSPKe1jut8LxG9PFTxYVaXpdC09E8dwzN3p/2rdZjhbv+cBgvkXqCO/M6EMrpqHknddrTi9l+RSeZize/cX3u8pZnOeMOK3p7nvMe71+OTcObGOv8Hu0YaRQ=="
}

def table_dir(raw):
    count = struct.unpack_from(">H", raw, 4)[0]
    return {tag: (checksum, off, size) for tag, checksum, off, size in
            (struct.unpack_from(">4sIII", raw, 12+16*i) for i in range(count))}

def reconstruct(raw):
    if hashlib.sha256(raw).hexdigest() == EXPECTED:
        return raw
    blob = hashlib.sha1(b"blob "+str(len(raw)).encode()+b"\0"+raw).hexdigest()
    if blob != UPSTREAM_BLOB:
        raise ValueError("Unexpected Tektur upstream Git blob: "+blob)
    src = table_dir(raw)
    target = table_dir(HEADER)
    out = bytearray(162656)
    out[:len(HEADER)] = HEADER
    for tag, (checksum, off, size) in target.items():
        if tag in PATCH:
            data = zlib.decompress(base64.b64decode(PATCH[tag]))
        else:
            old_sum, old_off, old_size = src[tag]
            if old_sum != checksum or old_size != size:
                raise ValueError(f"Archive/upstream table differs: {tag!r}, {old_size}/{size}, {old_sum:08x}/{checksum:08x}")
            data = raw[old_off:old_off+old_size]
        if len(data) != size:
            raise ValueError(f"Incorrect table length: {tag!r}")
        out[off:off+size] = data
    actual = hashlib.sha256(out).hexdigest()
    if actual != EXPECTED:
        raise ValueError("Exact archive font checksum failed: "+actual)
    return bytes(out)

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--source", type=Path, help="Optional exact archive font or pinned upstream file")
    args = p.parse_args()
    if args.output.is_file() and hashlib.sha256(args.output.read_bytes()).hexdigest() == EXPECTED:
        print("Tektur archive SHA-256 verified:", EXPECTED)
        return
    if args.source:
        raw = args.source.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=45) as response:
            raw = response.read(300001)
        if len(raw)>300000:
            raise ValueError("Oversized upstream font")
    font = reconstruct(raw)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_suffix(".tmp")
    temp.write_bytes(font)
    temp.replace(args.output)
    print("Tektur archive SHA-256 verified:", EXPECTED)

if __name__ == "__main__":
    main()
