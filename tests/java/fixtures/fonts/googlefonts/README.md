# Google Fonts answers for the `googlefonts` suite

`tests/java/src/GoogleFontsTest.java` checks `GoogleFonts`, the part of the Google Fonts browser (v7.18.0) that reads what Google
Fonts answers. Everything here is what Google Fonts really sent (fetched 2026-10-26), cut down where noted so the suite stays
small. No font file is committed: the suite never downloads one.

## Files

| File | What | Fetched from | Cut down |
|---|---|---|---|
| `catalog.json` | the catalog | `https://fonts.google.com/metadata/fonts` (2.7 MB, 1,950 families, no `)]}'` line) | 22 of the 1,950 families, in the source's order, each with its first 3 `languages`; `axisRegistry` keeps `tag`, `displayName`, `min`, `defaultValue`, `max`, `precision` of all 56 axes |
| `list/<slug>.json` | download lists | `https://fonts.google.com/download/list?family=<Family, spaces as +>` (with its `)]}'` line) | `README.txt` cut to its first line; Material Symbols' `LICENSE.txt` to its first 600 characters. `nonexistent-family-x.json` is the HTTP 400 answer for an unknown family |
| `detail/<slug>.json` | family details | `https://fonts.google.com/metadata/fonts/<Family, spaces as %20>` (with its `)]}'` line) | the fields the app reads and a few others; the description cut to its first two paragraphs, each designer's bio to 120 characters; `coverage`, `stats`, `article`, `languages` left out. `nonexistent-family-x.json` and `material-symbols-outlined.json` are the HTTP 404 answers (an `"er"` array), as sent; `lato-post-405.json` is the HTTP 405 answer to a POST of Lato's address (fetched 2026-10-27), as sent: an `"er"` array with another status |
| `css2/*.css` | css2 answers | the address in `css2/requests.tsv`, sent with the Chrome user agent the app sends (design section 3) | none |
| `css2/requests.tsv` | the request of each css2 answer | `file`, `family`, `mode`, `text`, `address` (tab-separated) | - |

The suite checks that `GoogleFonts.css2Url(family, mode, text)` builds exactly the address in `requests.tsv`, so the answers are
the ones the app's own requests get. The families cover: a variable family with an italic file (Roboto, Inter, Open Sans), static
families (Lato, Ubuntu), a colour font (Bungee Spice, Noto Color Emoji), an italic-only family (Molle), a 700-only family
(UnifrakturCook), 300-only (Buda), weights 1 to 1000 (Sofia Sans), 13 axes with negative values (Roboto Flex), upper-case axes
(Fraunces), a Japanese family (Noto Sans JP), symbols (Libre Barcode 128), the Apache licence (Permanent Marker), the Ubuntu Font
Licence (Ubuntu) and the Reserved Font Name headers of Lato, Molle, Andika, Charis SIL, Gentium Book Plus (two names), Source
Sans 3 (single quotes) and UnifrakturCook (no quotes). Nine more lists are there for their headers only (fetched 2026-10-27): Orbitron
(`Reserved Font Name: "Orbitron"`), Chango (`Reserved Font Names, "Chango"`), Julee (`Reserved Font Name is "Julee"`), Encode Sans
and Homenaje (a straight quote closed by a curly one), Stint Ultra Expanded (the name over a line break), GFS Didot (`<GFS Didot>`),
Numans (`"Numans."`) and Jomolhari (`"Jomolhari" is a Reserved Font Name for this Font Software.`).

## Licences

The data is Google Fonts' public metadata. The licence texts inside the download lists are those of each family: the SIL Open
Font License 1.1 (`OFL.txt`), the Apache License 2.0 (`LICENSE.txt`) and the Ubuntu Font Licence 1.0 (`UFL.txt`), with each
family's copyright line. The descriptions and designer bios are Google Fonts' text, shortened.
