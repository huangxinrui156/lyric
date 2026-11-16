#!/usr/bin/env python3
"""Small, original EPUBs covering descriptors, ZIP64, legacy ZIP names and XML prefixes."""
import io
from pathlib import Path
import zipfile

BASE = Path(__file__).resolve().parents[1] / "app/src/test/resources/epub"
ENTRIES = {
    "mimetype": "application/epub+zip",
    "META-INF/container.xml": '<c:container xmlns:c="urn:oasis:names:tc:opendocument:xmlns:container"><c:rootfiles><c:rootfile full-path="OPS/book.opf"/></c:rootfiles></c:container>',
    "OPS/book.opf": '\ufeff<opf:package xmlns:opf="http://www.idpf.org/2007/opf" xmlns:d="http://purl.org/dc/elements/1.1/"><opf:metadata><d:title>前缀书名</d:title><d:creator>作者</d:creator></opf:metadata><opf:manifest><opf:item id="c" href="章节.xhtml" media-type="application/xhtml+xml"/></opf:manifest><opf:spine><opf:itemref idref="c"/></opf:spine></opf:package>',
    "OPS/章节.xhtml": '\ufeff<?xml version="1.0"?><!DOCTYPE html SYSTEM "https://example.invalid/SECRET"><html><body><h1>章节</h1><p>正文&nbsp;&copy;<b>残缺<p>第二段</body></html>',
}


def entry(name):
    return zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))


class Unseekable(io.BytesIO):
    def seekable(self):
        return False

    def seek(self, *args):
        raise io.UnsupportedOperation()


output = Unseekable()
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
    for name, value in ENTRIES.items():
        archive.writestr(entry(name), value.encode())
(BASE / "stored-descriptor.epub").write_bytes(output.getvalue())

for charset in ("gbk", "cp437"):
    entries = ENTRIES.copy()
    if charset == "cp437":
        entries["OPS/book.opf"] = entries["OPS/book.opf"].replace("章节.xhtml", "café.xhtml")
        entries["OPS/café.xhtml"] = entries.pop("OPS/章节.xhtml")
    output = io.BytesIO()
    names = {}
    with zipfile.ZipFile(output, "w") as archive:
        for name, value in entries.items():
            raw = name.encode(charset)
            placeholder = "X" * len(raw) if any(c > 127 for c in raw) else name
            if placeholder != name:
                names[placeholder.encode()] = raw
            archive.writestr(entry(placeholder), value.encode())
    data = output.getvalue()
    for placeholder, raw in names.items():
        data = data.replace(placeholder, raw)
    (BASE / f"prefixed-{charset}.epub").write_bytes(data)

with zipfile.ZipFile(BASE / "zip64.epub", "w") as archive:
    for name, value in ENTRIES.items():
        with archive.open(entry(name), "w", force_zip64=True) as stream:
            stream.write(value.encode())

# Add genuine ZIP64 end-of-central-directory records even though the fixture is tiny.
import struct
path = BASE / "zip64.epub"
data = path.read_bytes()
offset = data.rfind(b"PK\x05\x06")
_, disk, directory_disk, disk_count, count, size, directory_offset, comment = struct.unpack("<4s4H2LH", data[offset:offset + 22])
zip64_end = struct.pack("<4sQ2H2L4Q", b"PK\x06\x06", 44, 45, 45, 0, 0, count, count, size, directory_offset)
locator = struct.pack("<4sLQL", b"PK\x06\x07", 0, offset, 1)
legacy_end = struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, 0xffff, 0xffff, 0xffffffff, 0xffffffff, 0)
path.write_bytes(data[:offset] + zip64_end + locator + legacy_end)
