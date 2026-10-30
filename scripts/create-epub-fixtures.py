"""Generate original, reproducible EPUB 2/3 publications (no third-party content)."""
from pathlib import Path
from urllib.parse import quote
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED

OUT = Path(__file__).resolve().parents[1] / 'app/src/test/resources/epub'
OUT.mkdir(parents=True, exist_ok=True)

def write_entry(z, name, content, compress_type=ZIP_DEFLATED):
    info = ZipInfo(name, (2026, 10, 30, 0, 0, 0))
    info.compress_type = compress_type
    z.writestr(info, content)

for version, package in [(2, 'book.opf'), (2, 'OEBPS/content.opf'), (3, 'EPUB/package/book.opf'), (3, 'Books/100% + rain/package.opf')]:
    base = str(Path(package).parent)
    base = '' if base == '.' else base + '/'
    chapter = 'Text/雨 + 100%.xhtml'
    href = quote(chapter, safe='/')
    nav_file = 'Navigation/nav.xhtml' if version == 3 else 'Navigation/toc.ncx'
    uid = f'urn:lyric:fixture:{version}:' + ('root' if not base else 'escaped' if '%' in base else 'nested')
    modified = '<meta property="dcterms:modified">2026-10-30T00:00:00Z</meta>' if version == 3 else ''
    spine_toc = ' toc="toc"' if version == 2 else ''
    manifest_nav = f'<item id="toc" href="{nav_file}" media-type="' + ('application/xhtml+xml" properties="nav"' if version == 3 else 'application/x-dtbncx+xml"') + '/>'
    files = {
        'META-INF/container.xml': f'''<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="{quote(package, safe='/')}" media-type="application/oebps-package+xml"/></rootfiles></container>''',
        package: f'''<?xml version="1.0"?><package version="{version}.0" unique-identifier="uid" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">{uid}</dc:identifier><dc:title>Rain EPUB {version} {package}</dc:title><dc:creator>Lyric tests</dc:creator><dc:language>zh</dc:language>{modified}</metadata><manifest><item id="second" href="./Text/../Text/second.xhtml" media-type="application/xhtml+xml"/><item id="first" href="{href}" media-type="application/xhtml+xml"/>{manifest_nav}</manifest><spine{spine_toc}><itemref idref="first"/><itemref idref="second"/></spine></package>''',
        base + chapter: '''<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Rain</title></head><body><h1>雨中来信</h1><p>第一段，书页上的雨。</p><p id="雨+叶">目标段落，叶子落下。</p>''' + '<p>字句之间，清晨的风吹过竹林。</p>' * 60 + '</body></html>',
        base + 'Text/second.xhtml': '''<html xmlns="http://www.w3.org/1999/xhtml"><head><title>晴天</title></head><body><h1>晴天</h1><p>第二章正文。</p></body></html>''',
        base + nav_file: (f'''<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="../{href}#{quote('雨+叶')}">一片叶子</a></li><li><a href="../Text/second.xhtml">晴天目录</a></li></ol></nav></body></html>''' if version == 3 else f'''<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="{uid}"/></head><docTitle><text>Rain</text></docTitle><navMap><navPoint id="a" playOrder="1"><navLabel><text>一片叶子</text></navLabel><content src="../{href}#{quote('雨+叶')}"/></navPoint><navPoint id="b" playOrder="2"><navLabel><text>晴天目录</text></navLabel><content src="../Text/second.xhtml"/></navPoint></navMap></ncx>'''),
    }
    suffix = 'root' if not base else ('literal-percent' if '%' in base else 'nested')
    with ZipFile(OUT / f'epub{version}-{suffix}.epub', 'w') as z:
        write_entry(z, 'mimetype', 'application/epub+zip', compress_type=ZIP_STORED)
        for path, content in files.items():
            write_entry(z, path, content.encode('utf-8'), compress_type=ZIP_DEFLATED)
# Conventional ASCII publication isolates Android XML/runtime behavior from URI decoding.
with ZipFile(OUT / 'epub3-basic.epub', 'w') as z:
    write_entry(z, 'mimetype', 'application/epub+zip', compress_type=ZIP_STORED)
    for path, content in {
        'META-INF/container.xml': '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>',
        'OEBPS/book.opf': '<package version="3.0" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Basic</dc:title><dc:creator>Lyric tests</dc:creator></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/></spine></package>',
        'OEBPS/a.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Rain</title></head><body><h1>Rain</h1><p id="leaf">Reading in the rain.</p></body></html>',
        'OEBPS/nav.xhtml': '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="a.xhtml#leaf">Leaf</a></li></ol></nav></body></html>',
    }.items():
        write_entry(z, path, content, compress_type=ZIP_DEFLATED)
