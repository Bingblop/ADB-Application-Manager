# Translations

The app translates itself: `assets/i18n.js` swaps the text of the page for the text of a **dictionary**, `assets/lang/<code>.js`, where it
appears (text, sentences with bold words or links, tool tips, input hints, `aria-label`, `alt`, the dialogs of the browser), when the language is
chosen and again whenever the page adds or changes something. Nothing runs while the language is English. This folder holds what is needed to keep
the dictionaries complete and right.

| File | What it is |
|---|---|
| `keys.json` | Every string the page can show, as the keys of a dictionary: `x` (a plain string, white space collapsed), `p` (a string with changing parts `{0}`, `{1}`), `b` (a sentence with an element inside, written with numbered tags). Each has a `ctx` that says where it is shown. Made by `extract.js`. |
| `extract.js` | Makes `keys.json` from `assets/index.html` (the script is read with acorn; the page is loaded in Chromium for the sentences with elements inside and for where each text is shown). |
| `BRIEF.md` | The instructions a translator (a person or a model) gets with a file of strings. |
| `dict.js` | `chunks` cuts what a dictionary lacks into files, `check` checks a translated file, `assemble` puts translated files into the dictionary. |
| `check.js` | Checks the dictionaries in `assets/lang/` against `keys.json` and the language list of `i18n.js`. |
| `audit.js`, `hook.js` | Finds data (names of apps and files, paths, output) that a word of a dictionary would translate. |
| `capture_hook.js`, `seen.json` | The texts the UI scripts really showed; `extract.js` uses them for strings that do not look like text. |

## A dictionary

```js
(window.__LANGS = window.__LANGS || {})['es'] = {
x: { "Freeze": "Congelar", "Application Manager": "Gestor de\naplicaciones", "Tap <1>Install</1> to continue": "Toca <1>Instalar</1> para continuar" },
p: [ ["Freeze {0} apps", "Congelar {0} apps"] ]
};
```

* The key is the English text as the page shows it, with white space collapsed. `{0}` marks a part the page fills in (an app name, a count); keep every
  one, once. `<1>…</1>` is an element inside a sentence (bold text, a link), `<2/>` an icon, `<br>` a line break: keep every tag with its number.
* A tab label may hold one `\n` (each line at most 12 characters); no other text may.
* Plurals are two entries (`{0} app`, `{0} apps`); a language with more forms should use a wording that is right for every count.
* Names of apps, packages, files, folders, settings and colors, paths, and what commands and logs print are **data**, not text: they are never
  translated (the list is `I18N.skip(...)` in the page; `audit.js` finds places that are missing from it).

## Adding or finishing a language

1. Add it to `LANGS` in `assets/i18n.js` (`{ code: 'nl', name: 'Nederlands', dir: 'ltr', locale: 'nl' }`; `dir: 'rtl'` for a right-to-left language).
2. `node i18n/dict.js chunks nl` writes `out/i18n/nl/src_01.json`, … : the strings `assets/lang/nl.js` does not have yet, 400 to a file.
3. Translate each file following `BRIEF.md`, into `out_01.json`, … next to it (`{ "<id>": "<translation>" }`, every id), and run
   `node i18n/dict.js check out/i18n/nl/src_01.json out/i18n/nl/out_01.json nl` until it prints `OK`.
4. `node i18n/dict.js assemble nl` merges them into `assets/lang/nl.js` (a key the page does not have any more is dropped).
5. `node i18n/check.js nl` (add `--strict` to make a missing string an error).

## When the page changes

`node i18n/extract.js` (about 20 seconds) makes `keys.json` again; `node i18n/check.js` lists every dictionary's missing and obsolete strings; `chunks`
for each language cuts only what is missing. If a run of the UI scripts showed texts that do not look like text to the extractor, run
`STR_OUT=out/seen.jsonl NODE_OPTIONS="--require ./i18n/capture_hook.js" node run.js --no-compare` first and give the file to
`extract.js --seen out/seen.jsonl`.

New names, paths or output that the page shows must stay as they are: add their selector to `I18N.skip(...)` in `assets/index.html` and run
`node i18n/audit.js`.
