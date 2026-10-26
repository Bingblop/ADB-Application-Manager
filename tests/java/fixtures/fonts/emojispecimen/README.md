# The emoji samples (`emojispecimen` suite)

`tests/java/src/EmojiSpecimenTest.java` checks `EmojiSpecimen`, which reads the 16 sample emoji out of a colour-bitmap (CBDT)
emoji font a byte range at a time. The font it reads in the app is the iOS emoji file (`EmojiCatalog`'s `apple` item: 43,877,492
bytes, SHA-256 `e7b5f998...b81043d`, from `github.com/samuelngs/apple-emoji-ttf/releases/download/v26.2.1/AppleColorEmoji.ttf`).
That file is Apple's and is **not** in the repository, not even in part: the suite builds its own CBDT fonts in Java (cmap
formats 12 and 4, CBLC with three strikes and index subtables of formats 1 to 5, CBDT images of formats 17, 18 and 19, PNG-signed
images that say which strike and glyph they are). This folder holds no font, only this note.

The other sources the suite reads are in the repository: `tools/make-emoji-specimens.sh` (its `SAMPLE`), `assets/index.html`
(`FNT_EMO_SAMPLE`) and the four sample fonts `assets/emoji/{noto,twemoji,blobmoji,openmoji}.ttf` (see `assets/emoji/NOTICE.txt`
for their sources and licences).

## The real file, checked at development time (2026-10-26)

`EmojiSpecimen.read` was run on the real iOS emoji file twice, outside the suite: through HTTP range requests with
`HttpSafe.open` (the GitHub address answers 302 to `release-assets.githubusercontent.com`, whose address the following ranges
reuse; every answer was 206 with `Content-Range: bytes a-b/43877492`, checked by `EmojiSpecimen.checkPart`), and through a
`RandomAccessFile` on a full download whose SHA-256 matched the pinned one. Both gave the same 16 PNGs, each 160x160 (decoded with
`javax.imageio`), byte for byte the images fontTools reads from the file's CBDT for those code points.

What the file looks like where the sample is read (numbers only):

| | |
|---|---|
| tables read | header 12 bytes, directory 208 bytes (13 tables), `cmap` 2,845 bytes, `CBLC` 17,732 bytes (version 3.0) |
| strike | one, 137 ppem, glyphs 4 to 4422, 17 index subtables, all index format 1 with image format 17 |
| glyph records | 16 reads of 4,022 to 35,158 bytes |
| in all | 20 reads, 266,376 bytes (the page says "Reads about 0.3 MB of the iOS emoji file.") |
