# Real RAR archives for the `rarreader` suite

`tests/java/src/RarReaderTest.java` runs every `*.rar` in this folder through `RarReader` (list, walk with the CRC32 / BLAKE2sp checks of
the archive, open by name, compared with the walk) and, when a `<name>.exp` file from the rarfile project sits next to an archive, checks
that the names in it are in the listing. Passwords are tried from a short list (`password`, `test`, `secret`, `1234`, ...).
The suite passes without any file here (the container, the unpacker, the filters and the encryption are then checked with archives that the
test writes itself), but those archives only prove that reader and writer agree; real ones are what shows that the format is read the way
RAR writes it.

**This folder is empty on purpose in the commit that added the suite**: the session that wrote `RarReader` could not download anything (the
GitHub hosts were refused by the sandbox), and there is no `rar` program to make archives with. Run `./fetch.sh` once on a machine that can
reach GitHub; it needs `curl` and `python3` and puts the files here (about 1-2 MB). File names are those of the upstream repositories as
remembered; a name that does not exist (404) is skipped, so check what arrived (`ls`) and trim the folder to roughly 3 MB.

## Sources and licenses

* **rarfile** by Marko Kreen, `https://github.com/markokr/rarfile`, folder `test/files/`: `rar3-*.rar` (RAR 3 / 4: solid, comments,
  `-hp` encrypted headers, old format, symlinks, unicode names, vols), `rar5-*.rar` (RAR 5: crc / blake2sp hashes, solid, `-hp`, `-p` with
  password check, times, quick open), the `.exp` files with the expected listing. ISC license (the test files are part of the repository).
  Passwords of the encrypted ones: `password`.
* **libarchive** `https://github.com/libarchive/libarchive`, folder `libarchive/test/`: `test_read_format_rar5_*.rar.uu` (uuencoded; decoded by
  `fetch.sh` with python): stored, compressed, solid, delta / x86 / arm filters, symlink, empty file, unicode, blake2. BSD 2-clause license.

What each real archive should show (the reason to have it): a compressed RAR5 file (the LZ decoder), a solid RAR5 archive, a RAR5 archive with a
delta, an E8 and an ARM filter, a RAR5 archive with BLAKE2sp hashes, `-p` and `-hp` RAR5 archives (key derivation, check value), a RAR5 symlink,
a RAR 3 / 4 archive of each of: compressed, solid, `-p`, `-hp`, unicode names.
