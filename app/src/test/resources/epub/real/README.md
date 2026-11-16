# Real EPUB compatibility fixtures

Downloaded 2026-10-30, unmodified, using `curl -L --fail -A "Mozilla/5.0 Lyric EPUB compatibility test"`. Total about 2.3 MiB. These are test resources, not packaged into the release APK.

Project Gutenberg volumes contain their own license and notices; retain those files. They are public domain in the United States; copyright status elsewhere varies. Standard Ebooks includes its public-domain dedication/source notices inside the EPUB. These files are retained as complete books to exercise actual OPF, navigation, CSS, illustrations, and Chinese text.

The requested Gutenberg 1342 EPUB3 images download was also downloaded and ZIP-validated locally but is about 24 MiB, so it is omitted from git. Its complete download was SHA-256 `fd519471687295a8b2492d9e413bc24bee21ade9808d8af29899a16e8599064e`. The `1342.epub3.noimages` endpoint returned HTTP 404. Standard Ebooks supplies the EPUB3 fixture in the committed subset.

| File | Source | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| gutenberg-11-epub2.epub | [Download](https://www.gutenberg.org/ebooks/11.epub.noimages) | 136756 | `08a2de3a58226311915bd87056e59ed4ee49a284dcb5e722fef415bca4032a4b` |
| gutenberg-84-images.epub | [Download](https://www.gutenberg.org/ebooks/84.epub.images) | 439444 | `eb696684c1314092bfe973be1241ab1f44cde101ac19bc36a574893de24016c2` |
| gutenberg-23950-zh.epub | [Download](https://www.gutenberg.org/ebooks/23950.epub.noimages) | 974475 | `4d49f3d6ea5ad6a50ad8cf4d3efb05cea79881c9d26b0c11ccdba9dc60a82b5d` |
| standard-austen.epub | [Download](https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/jane-austen_pride-and-prejudice.epub?source=download) | 831959 | `85814417c1a74d2ca7f3b077b47454d0cfc728392d40d846d1c97d44f24c5279` |
