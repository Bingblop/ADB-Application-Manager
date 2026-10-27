// Mock bridge for the Custom Fonts tab scripts (t586-t591): a whole window.AndroidBridge (settings in memory, a working mode chosen by opts.mode) with
// the methods of the font maker design, section 6 (/mnt/project-files/plans/custom-fonts-v7.17.0.md): fontMaker* (pick, preview, build, cancel, library,
// font data, delete, export, install, uninstall, changer status, apply, reset), emoji* (catalog, download, cancel, delete), sysEmoji* (status, apply,
// undo) and softRestart, answering with the shapes the design gives and realistic data: a variable Inter-like font (wght + opsz, nine named instances),
// a static TrueType font, a CFF one, a COLR emoji font of one's own and a bitmap one, the five catalog emoji fonts, two library entries, a changer
// status for Root, the system user (Shizuku UID 1000), Shizuku as root (UID 0), the ADB shell (UID 2000, locked) and a phone that is not a Samsung.
//   await page.addInitScript(fmk.initScript, { mode: 'system', b64: { vf, regular, bold }, ... });
// opts.mode: 'root' (Root with su) | 'system' (Shizuku as UID 1000) | 'uid0' (Shizuku as UID 0) | 'shell' (Shizuku as UID 2000) | 'none' (no working mode)
// opts.samsung (true), opts.oneUi ('8.5'), opts.appliesSelfSigned (false), opts.sdk (36), opts.dark (true), opts.userId (0)
// opts.b64: { vf, regular, bold }: base64 of the fixture fonts the previews load (the script reads them from tests/ui/fixtures)
// opts.picks: { regular, bold, emoji }: what the file chooser hands back for a slot: 'vf' | 'vfWidth' (variable, no Weight axis) | 'static' | 'cff' | 'colr' |
//   'colr1' (COLRv1) | 'colrCff' (COLR with PostScript outlines: refused in the emoji slot, emoji_outlines) | 'cbdt' | an error code ('cancelled', 'woff' ...);
//   a font without Latin letters carries the bridge's no-Latin warning in the Regular and Bold slots, never in the emoji slot (as fmkCopyPick drops it)
// opts.entries: the library at the start (default: two entries); opts.downloaded: catalog ids on the phone at the start (default ['twemoji'])
// opts.hold: build, download and privileged jobs move one event further each time the script calls window.__fmk.step() (and step(n), stepAll())
// opts.buildError: { code, error } the build ends with, opts.buildWarnings: its warnings (both also window.__fmk.buildError / buildWarnings, which a script may change);
// opts.downloadError: { id: { code, error, status } } (also window.__fmk.downloadError); opts.current: the changer's current entry id (default: the applied one)
// opts.sysActive: the catalog id in use as the phone's emoji at the start; opts.missing: method names left out (an older build)
// opts.statusFails: how many changer statuses answer no_answer before one answers; opts.noAppFonts: a Samsung without /data/app_fonts (available:false)
// opts.applyError / opts.resetError / opts.sysApplyError / opts.sysUndoError: { code, error, readBack } the job ends with; opts.sysBlocked: { code, error } (the status's blocked)
// opts.refuse: { method: { code, error } } answered at once instead of the job (the bridge's busy answers and the like)
// opts.holdSave: the save screen stays open until the script calls window.__fmk.save()
// While a file chooser or a save screen is open (until its answer) a second one is refused with busy, as the bridge does. A build with rebuildId keeps the
// entry's id and package and takes the next versionCode (not_found for an id that is not in the library).
// window.__fmk: { calls, requests, picks, step(), stepAll(), pending(), lib, cat, setMode(m), save() }
// v7.18.0: Google Fonts (gfonts*) and the emoji samples (emojiSpecimen*), with the shapes of section 5 of the Google Fonts design
// (/mnt/project-files/plans/google-fonts-v7.18.0.md). opts.gf:
//   catalog: the compact list on the phone at the start (default none: at 0); stale: it is older than 7 days; remote: what a refresh brings (default the
//   catalog; __fmk.gfRemote instead when it is set); refreshError: { code, error } a refresh ends with (also __fmk.gfRefreshError); downloads: families
//   on the phone at the start; reserved: { family: [names] } (default { Lato: ['Lato'] }); previewError: families answered gf_no_preview; previewFail:
//   families answered with a network error; infoError: { code, error } every family's details end with (also __fmk.gfInfoError); downloadError:
//   { family: { code, error } }; useError: { code, error, slot? } gfontsUse answers with (only that slot's when slot is set); saveError: { code, error }
//   the save ends with ('cancelled' too); specimenError: { code, error } the iOS read ends with (also __fmk.specimenError); appleCached: the iOS pictures are kept
//   from an earlier read; hold: the preview answers wait for __fmk.gfAnswer(n) / gfAnswerAll() (pending: gfPending()), the other jobs (refresh,
//   details, download, iOS read) for __fmk.gfStep(n) / gfStepAll(); otherWindow: another window of the app is getting the list, a family's details
//   and the iOS emoji (also __fmk.gfOtherWindow): gfontsRefresh, gfontsInfo and emojiSpecimenFetch answer busy, as the bridge does when the running
//   job is not this window's (a second ask of this window joins its own); __fmk.gfOtherWindowDone(): that window's refresh ended (the list on the phone
//   is the remote one, and only that window was told: no onGfCatalog here). The list is stale, as GoogleFonts.isStale says, when opts.gf.stale is set
//   or its time is more than 7 days before the page's clock (Date.now, which a script may move)
// opts.emoB64: { noto, twemoji, blobmoji, openmoji }: base64 of assets/emoji/<id>.ttf (emojiSpecimenFont answers "" for an id without one)
// __fmk.gfPreviews: every preview asked ({ reqId, family, mode, text }); gfOut / gfOutMax: the previews asked and not answered, now and at most
exports.initScript = function (opts) {
  opts = opts || {};
  const J = JSON.stringify;
  const now = Date.now(), day = 864e5, MB = 1048576;
  const mode = opts.mode || 'root';
  const samsung = opts.samsung !== false;
  const sdk = opts.sdk || 36;
  const b64 = opts.b64 || {};
  const delay = opts.delay === undefined ? 12 : opts.delay;
  const queue = [];
  let seq = 0;
  const st = window.__fmk = { calls: [], requests: [], queue };
  // a job's events: run one by one on the timer, or only when the script steps (opts.hold)
  const run = (events) => {
    if (opts.hold) { events.forEach(e => queue.push(e)); return; }
    events.forEach((e, i) => setTimeout(e, delay * (i + 1)));
  };
  st.step = (n) => { for (let i = 0; i < (n || 1) && queue.length; i++) queue.shift()(); return queue.length; };
  st.stepAll = () => { while (queue.length) queue.shift()(); return 0; };
  st.pending = () => queue.length;
  const job = () => 'job' + (++seq);

  // ---- the working mode (what getWorkingMode says; isRootActive reads sysPrivActive, the uid-0 tier rootUidActive) ----
  const WM = {
    root: { activeMode: 'root', configuredMode: 'root', isPrivileged: true, modeAvailable: true, rootAvailable: true, rootGranted: true, sysPrivActive: true, rootUidActive: true, rootWorkingModeActive: true, adbTcp: {}, adbWireless: {}, shizuku: {} },
    system: { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, modeAvailable: true, rootAvailable: false, sysPrivActive: true, rootUidActive: false, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true, uid: 1000 } },
    uid0: { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, modeAvailable: true, rootAvailable: false, sysPrivActive: true, rootUidActive: true, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true, uid: 0 } },
    shell: { activeMode: 'shizuku', configuredMode: 'shizuku', isPrivileged: true, modeAvailable: true, rootAvailable: false, sysPrivActive: false, rootUidActive: false, adbTcp: {}, adbWireless: {}, shizuku: { installed: true, running: true, authorized: true, uid: 2000 } },
    none: { activeMode: 'readonly', configuredMode: 'auto', isPrivileged: false, modeAvailable: false, rootAvailable: false, sysPrivActive: false, rootUidActive: false, adbTcp: {}, adbWireless: {}, shizuku: {} },
  };
  st.mode = mode;
  const wm = () => J(WM[st.mode] || WM.none);
  const unlocked = () => !!(WM[st.mode] || {}).sysPrivActive;
  const uid0 = () => !!(WM[st.mode] || {}).rootUidActive;
  const lockReason = 'Needs Root, or Shizuku running as the system user (UID 1000) or root (UID 0).';
  const lockSys = 'Needs Root, or Shizuku running as root (UID 0).';
  // the engine's warnings as Java writes them (FontInspect.W_NO_LATIN, W_RESTRICTED; EmojiMerge.W_EMOJI_GPOS)
  const W_NO_LATIN = "This font has no basic Latin letters and digits (A-Z, a-z, 0-9): English text would show in the phone's own font.";
  const W_RESTRICTED = 'This font\'s maker does not allow it to be embedded or changed ("restricted license" in the font). Use it only if its license lets you.';
  const W_EMOJI_GPOS = "The emoji font's positioning table (GPOS) was left out; its emoji do not need it to show.";

  // ---- what a picked file is ----
  const instances = [['Thin', 100], ['ExtraLight', 200], ['Light', 300], ['Regular', 400], ['Medium', 500], ['SemiBold', 600], ['Bold', 700], ['ExtraBold', 800], ['Black', 900]]
    .map(([name, w]) => ({ name, coords: { wght: w, opsz: 14 } }));
  const FILES = {
    vf: { fileName: 'InterVariable.ttf', size: 880 * 1024, preview: 'vf', info: { family: 'Inter Variable', subfamily: 'Regular', fullName: 'Inter Variable Regular', version: 'Version 4.100', weight: 400, width: 5, italic: false,
      outlines: 'TrueType', glyphs: 2548, upem: 2048, codepoints: 2017, latin: true, color: 'none', variable: true,
      axes: [{ tag: 'opsz', name: 'Optical size', min: 14, def: 14, max: 32, hidden: false }, { tag: 'wght', name: 'Weight', min: 100, def: 400, max: 900, hidden: false }],
      instances, guessRegular: { opsz: 14, wght: 400 }, guessBold: { opsz: 14, wght: 700 }, warnings: [] } },
    static: { fileName: 'Lato-Regular.ttf', size: 656 * 1024, preview: 'regular', info: { family: 'Lato', subfamily: 'Regular', fullName: 'Lato Regular', version: 'Version 2.015', weight: 400, width: 5, italic: false,
      outlines: 'TrueType', glyphs: 2164, upem: 2000, codepoints: 1981, latin: true, color: 'none', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {}, warnings: [] } },
    staticBold: { fileName: 'Lato-Bold.ttf', size: 662 * 1024, preview: 'bold', info: { family: 'Lato', subfamily: 'Bold', fullName: 'Lato Bold', version: 'Version 2.015', weight: 700, width: 5, italic: false,
      outlines: 'TrueType', glyphs: 2164, upem: 2000, codepoints: 1981, latin: true, color: 'none', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {}, warnings: [] } },
    cff: { fileName: 'SourceSans3-Regular.otf', size: 312 * 1024, preview: 'regular', info: { family: 'Source Sans 3', subfamily: 'Regular', fullName: 'Source Sans 3 Regular', version: 'Version 3.052', weight: 400, width: 5, italic: false,
      outlines: 'CFF', glyphs: 1937, upem: 1000, codepoints: 1452, latin: true, color: 'none', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {},
      warnings: [W_RESTRICTED] } },
    colr: { fileName: 'TwemojiMozilla.ttf', size: 1450 * 1024, preview: '', info: { family: 'Twemoji Mozilla', subfamily: 'Regular', fullName: 'Twemoji Mozilla', version: 'Version 0.7.0', weight: 400, width: 5, italic: false,
      outlines: 'TrueType', glyphs: 3913, upem: 2048, codepoints: 3431, latin: false, color: 'COLRv0', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {}, warnings: [W_NO_LATIN] } },
    colr1: { fileName: 'Noto-COLRv1.ttf', size: 4920 * 1024, preview: '', info: { family: 'Noto Color Emoji', subfamily: 'Regular', fullName: 'Noto Color Emoji', version: 'Version 2.051', weight: 400, width: 5, italic: false,
      outlines: 'TrueType', glyphs: 5847, upem: 1024, codepoints: 1452, latin: false, color: 'COLRv1', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {}, warnings: [W_NO_LATIN] } },
    colrCff: { fileName: 'EmojiOne-CFF.otf', size: 2100 * 1024, preview: '', info: { family: 'EmojiOne Color', subfamily: 'Regular', fullName: 'EmojiOne Color', version: 'Version 1.4', weight: 400, width: 5, italic: false,
      outlines: 'CFF', glyphs: 2780, upem: 2048, codepoints: 2412, latin: false, color: 'COLRv0', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {}, warnings: [W_NO_LATIN] } },
    // a variable font whose only axis is Width: FontInspect guesses no Bold (guessBold null), its Regular is the default
    vfWidth: { fileName: 'NotoSans-Width.ttf', size: 720 * 1024, preview: 'vf', info: { family: 'Noto Sans Width', subfamily: 'Regular', fullName: 'Noto Sans Width Regular', version: 'Version 2.013', weight: 400, width: 5, italic: false,
      outlines: 'TrueType', glyphs: 4515, upem: 1000, codepoints: 3741, latin: true, color: 'none', variable: true,
      axes: [{ tag: 'wdth', name: 'Width', min: 62.5, def: 100, max: 100, hidden: false }], instances: [{ name: 'Condensed', coords: { wdth: 62.5 } }, { name: 'Regular', coords: { wdth: 100 } }],
      guessRegular: { wdth: 100 }, guessBold: null, warnings: [] } },
  };
  const PICK_ERRORS = {
    cbdt: { code: 'bitmap_emoji', error: 'This emoji font is made of pictures (CBDT), which cannot be put inside a font. Choose a COLR emoji font.' },
    woff: { code: 'woff', error: 'This is a web font (WOFF). Choose a .ttf or .otf file.' },
    collection: { code: 'collection', error: 'This file holds several fonts (a font collection). Choose a single .ttf or .otf file.' },
    not_a_font: { code: 'not_a_font', error: 'This file is not a font.' },
    too_big: { code: 'too_big', error: 'This file is larger than 96 MB.' },
    cancelled: { code: 'cancelled', error: 'No file was chosen.' },
  };
  const picks = Object.assign({ regular: 'vf', bold: 'staticBold', emoji: 'colr' }, opts.picks || {});
  st.picks = picks;                                                  // a script may change what the chooser hands back next
  const tokens = {};

  // ---- the library and the catalog ----
  const lib = st.lib = opts.entries || [
    { id: 'f1a2b3', name: 'Inter Display', pkg: 'com.monotype.android.font.InterDisplay_3fa9c1', versionCode: 2, created: now - 2 * day, hasBold: true, emoji: { id: 'noto', name: 'Noto Color Emoji' },
      sizes: { apk: Math.round(9.8 * MB), regular: Math.round(4.6 * MB), bold: Math.round(4.7 * MB) }, installed: true, installedVersion: 2, applied: true },
    { id: 'c4d5e6', name: 'Lato', pkg: 'com.monotype.android.font.Lato_8b21d0', versionCode: 1, created: now - 5 * day, hasBold: false, emoji: null,
      sizes: { apk: 702 * 1024, regular: 656 * 1024, bold: 0 }, installed: false, installedVersion: 0, applied: false },
  ];
  const device = () => ({ samsung, oneUi: samsung ? (opts.oneUi === undefined ? '8.5' : opts.oneUi) : '', appliesSelfSigned: opts.appliesSelfSigned === true, sdk, userId: opts.userId || 0 });
  const downloaded = new Set(opts.downloaded || ['twemoji']);
  const CAT = [
    { id: 'noto', name: 'Noto Color Emoji', version: 'Unicode 18', format: 'COLRv1', mergeable: true, minApi: 33, size: 5038236, sha256: 'b8e25ea68db82f9e4d0aee921f4420be2be39887bd5c893a2ad98710531f9d0c',
      license: 'OFL-1.1', licenseUrl: 'https://github.com/googlefonts/noto-emoji/blob/v2026-09-24-unicode18_0/2D/fonts/LICENSE', sourceUrl: 'https://github.com/googlefonts/noto-emoji', attribution: 'Noto Color Emoji, Copyright 2022 Google Inc.', note: '' },
    { id: 'twemoji', name: 'Twemoji', version: '17', format: 'COLRv0', mergeable: true, minApi: 26, size: 2003200, sha256: 'd2d721abc0ab04c0f40673e4b521e8b580a6b31d1018b7f2dcb438fdae4c265e',
      license: 'CC-BY-4.0', licenseUrl: 'https://creativecommons.org/licenses/by/4.0/', sourceUrl: 'https://github.com/SableClient/twemoji-font', attribution: 'Graphics (c) Twitter, Inc. and contributors (jdecked/twemoji); COLR build by SableClient (Apache-2.0)', note: '' },
    { id: 'blobmoji', name: 'Blobmoji', version: 'Emoji 17', format: 'COLRv1', mergeable: true, minApi: 33, size: 4444184, sha256: 'be12a85b4ac069ef07541e861f96e99710ad4f5264600aff2dd396a640dbfb7f',
      license: 'OFL-1.1', licenseUrl: 'https://github.com/DavidBerdik/blobmoji2/blob/main/LICENSE', sourceUrl: 'https://github.com/DavidBerdik/blobmoji2', attribution: 'Blobmoji2 by DavidBerdik (a fork of Noto Emoji with the blob art)', note: '' },
    { id: 'openmoji', name: 'OpenMoji', version: '17', format: 'COLRv1', mergeable: true, minApi: 33, size: 2401396, sha256: 'b6c86e3d95f557dae9a7d59a993b5a6f2274c401d6d975747d43612c6fab236d',
      license: 'CC-BY-SA-4.0', licenseUrl: 'https://creativecommons.org/licenses/by-sa/4.0/', sourceUrl: 'https://github.com/hfg-gmuend/openmoji', attribution: 'OpenMoji (c) HfG Schwäbisch Gmünd', note: '' },
    { id: 'apple', name: 'iOS emoji', version: 'iOS 26.2', format: 'CBDT', mergeable: false, minApi: 0, size: 43877492, sha256: 'e7b5f998812d9ab50d32f5626cb1e88edea345d725f8edf44a7ec3333b81043d',
      license: 'Proprietary (Apple Inc.)', licenseUrl: 'https://www.apple.com/legal/intellectual-property/', sourceUrl: 'https://github.com/samuelngs/apple-emoji-ttf', attribution: '(c) Apple Inc.; third-party build by samuelngs', note: '' },
  ];
  const catalog = () => ({ ok: true, sdk, items: CAT.map(it => Object.assign({}, it, { usable: !it.mergeable || sdk >= it.minApi, downloaded: downloaded.has(it.id) })) });
  st.cat = CAT;
  let current = opts.current !== undefined ? opts.current : ((lib.find(e => e.applied) || {}).id || null);
  let sysActive = opts.sysActive || null;
  const dls = {};

  // ---- v7.18.0 Google Fonts: the list, the downloads, the jobs ----
  const G = opts.gf || {};
  const GF_UNKNOWN = 'This family is not in the Google Fonts list. Update the list and try again.';
  const gfq = [], pvq = [];
  const gfRun = (events) => { if (G.hold) { events.forEach(e => gfq.push(e)); return; } events.forEach((e, i) => setTimeout(e, delay * (i + 1))); };
  st.gfStep = (n) => { for (let i = 0; i < (n || 1) && gfq.length; i++) gfq.shift()(); return gfq.length; };
  st.gfStepAll = () => { while (gfq.length) gfq.shift()(); return 0; };
  st.gfAnswer = (n) => { for (let i = 0; i < (n || 1) && pvq.length; i++) pvq.shift().run(); return pvq.length; };
  st.gfAnswerAll = () => { while (pvq.length) pvq.shift().run(); return 0; };
  st.gfPending = () => pvq.map(p => ({ reqId: p.reqId, family: p.family, mode: p.mode, text: p.text }));
  st.gfPreviews = []; st.gfOut = 0; st.gfOutMax = 0;
  let gfCat = JSON.parse(J(G.catalog || { v: 1, at: 0, axes: {}, families: [] }));
  let gfStale = !!G.stale, gfRefreshJob = null, appleJob = null;
  const gfIsStale = () => gfCat.at > 0 && (gfStale || Date.now() < gfCat.at || Date.now() - gfCat.at > 7 * day);
  const gfInfoJobs = {};
  // a refresh's new list on the phone (the remote one)
  const gfGot = () => { gfCat = JSON.parse(J(st.gfRemote || G.remote || G.catalog || { v: 1, at: 0, axes: {}, families: [] })); if (!gfCat.at) gfCat.at = Date.now(); gfStale = false; };
  st.gfOtherWindowDone = () => { gfGot(); st.gfOtherWindow = false; };
  const gfFam = (f) => gfCat.families.find(e => e.f === f) || null;
  const GF_W = { 100: 'Thin', 200: 'ExtraLight', 300: 'Light', 400: 'Regular', 500: 'Medium', 600: 'SemiBold', 700: 'Bold', 800: 'ExtraBold', 900: 'Black' };
  // the files of a family's download, as GoogleFonts.plan keeps them: a variable family's top-level variable files (tags in ASCII order), else its static files
  const gfFilesOf = (e) => {
    const F = e.f.replace(/ /g, ''), its = e.w.some(w => /i$/.test(w));
    if (e.x && e.x.length) {
      const tags = e.x.map(a => a[0]).sort().join(',');
      const v = [{ name: F + '-VariableFont_' + tags + '.ttf', variable: true, italic: false, weight: 400 }];
      if (its) v.push({ name: F + '-Italic-VariableFont_' + tags + '.ttf', variable: true, italic: true, weight: 400 });
      return v.map((x, i) => Object.assign(x, { size: 300 * 1024 + i * 21 * 1024 }));
    }
    return e.w.map((w, i) => {
      const n = parseInt(w, 10), it = /i$/.test(w), nm = GF_W[n] || ('W' + n);
      return { name: F + '-' + (it ? (n === 400 ? 'Italic' : nm + 'Italic') : nm) + '.ttf', variable: false, italic: it, weight: n, size: 120 * 1024 + i * 7 * 1024 };
    });
  };
  const gfReserved = (f) => ((G.reserved || { Lato: ['Lato'] })[f] || []).slice();
  const gfLicense = (f) => {
    const r = gfReserved(f), q = r.map(x => '"' + x + '"').join(' and ');
    if (f === 'Roboto') return { kind: 'apache2', name: 'Apache License 2.0', text: 'Apache License\nVersion 2.0, January 2004\nhttp://www.apache.org/licenses/' };
    if (/^Ubuntu/.test(f)) return { kind: 'ufl', name: 'Ubuntu Font Licence 1.0', text: 'Copyright 2010,2011 Canonical Ltd.\n\nThis Font Software is licensed under the Ubuntu Font Licence, Version 1.0.\nhttps://ubuntu.com/legal/font-licence\n\nUBUNTU FONT LICENCE Version 1.0' };
    return { kind: 'ofl', name: 'SIL Open Font License 1.1', text: 'Copyright 2010-2014 The ' + f + ' Project Authors' + (r.length ? ', with Reserved Font Name' + (r.length > 1 ? 's ' : ' ') + q : '') + '.\n\nThis Font Software is licensed under the SIL Open Font License, Version 1.1.\nThis license is copied below, and is also available with a FAQ at:\nhttps://openfontlicense.org' };
  };
  const gfDesc = (e) => e.f === 'Lato'
    ? 'Lato is a sans serif typeface family started in the summer of 2010 by Warsaw-based designer Łukasz Dziedzic ("Lato" means "Summer" in Polish).\nIn December 2010 the Lato family was published under the Open Font License by his foundry tyPoland, with support from Google.\n\nIn the last ten or so years, during which Łukasz has been designing type, most of his projects were rooted in a particular design task that he needed to solve. With Lato, it was no different. Originally, the family was conceived as a set of corporate fonts for a large client, who in the end decided to go in different stylistic direction, so the family became available for a public release.'
    : e.f + ' is a ' + ({ sans: 'sans serif', serif: 'serif', display: 'display', hand: 'handwriting', mono: 'monospace' }[e.c] || '') + ' family on Google Fonts.';
  const gfRow = (e) => {
    const files = gfFilesOf(e), lic = gfLicense(e.f);
    return { family: e.f, at: Date.now(), size: files.reduce((s, x) => s + x.size, 0), license: lic.kind, licenseName: lic.name, reserved: gfReserved(e.f), files };
  };
  // the downloads, and the list entry each was made from: like the app's meta.json, a download stays usable when a later list no longer has its family
  const gfDl = {}, gfDlOf = {};
  (G.downloads || []).forEach((f, i) => { const e = gfFam(f) || ((G.remote || {}).families || []).find(x => x.f === f); if (e) { gfDl[f] = gfRow(e); gfDl[f].at = now - i * day; gfDlOf[f] = e; } });
  const gfJobs = {};
  // what FontInspect says of a family's file (the fixture fonts stand in for it)
  const gfInfoOf = (e, fi) => {
    if (fi.variable) {
      const axes = (e.x || []).map(a => ({ tag: a[0], name: (gfCat.axes || {})[a[0]] || a[0], min: a[1], def: a[3], max: a[2], hidden: false }));
      const wa = axes.find(a => a.tag === 'wght'), reg = {}, bold = {};
      axes.forEach(a => { reg[a.tag] = a.def; bold[a.tag] = a.def; });
      if (wa) { reg.wght = Math.max(wa.min, Math.min(wa.max, 400)); bold.wght = Math.max(wa.min, Math.min(wa.max, 700)); }
      return { family: e.f, subfamily: fi.italic ? 'Italic' : 'Regular', fullName: e.f + (fi.italic ? ' Italic' : ' Regular'), version: 'Version 3.010', weight: 400, width: 5, italic: fi.italic,
        outlines: 'TrueType', glyphs: 1326, upem: 2048, codepoints: 1012, latin: true, color: e.col ? 'COLRv1' : 'none', variable: true, axes,
        instances: wa ? instances.filter(x => x.coords.wght >= wa.min && x.coords.wght <= wa.max).map(x => ({ name: x.name, coords: Object.assign({}, reg, { wght: x.coords.wght }) })) : [],
        guessRegular: reg, guessBold: wa && wa.max >= 700 ? bold : null, warnings: [] };
    }
    return { family: e.f, subfamily: (GF_W[fi.weight] || 'Regular') + (fi.italic ? ' Italic' : ''), fullName: e.f + ' ' + (GF_W[fi.weight] || 'Regular'), version: 'Version 2.015', weight: fi.weight, width: 5, italic: fi.italic,
      outlines: 'TrueType', glyphs: 2164, upem: 2000, codepoints: 1981, latin: true, color: e.col ? 'COLRv1' : 'none', variable: false, axes: [], instances: [], guessRegular: {}, guessBold: {}, warnings: [] };
  };
  // a preview's faces (a woff2 subset each in the app; the fixture fonts here): card, one face; styles, one per style (18 at most); axes, the variable ones
  const gfFaces = (e, mode) => {
    const st1 = (w) => { const n = parseInt(w, 10), it = /i$/.test(w); return { style: it ? 'italic' : 'normal', weight: String(n), stretch: '', b64: n >= 600 ? (b64.bold || '') : (b64.regular || '') }; };
    if (mode === 'styles') return e.w.slice(0, 18).map(st1);
    if (mode === 'axes' && e.x && e.x.length) {
      const wa = e.x.find(a => a[0] === 'wght'), wd = e.x.find(a => a[0] === 'wdth');
      const f0 = { style: 'normal', weight: wa ? wa[1] + ' ' + wa[2] : '400', stretch: wd ? wd[1] + '% ' + wd[2] + '%' : '', b64: b64.vf || '' };
      return e.w.some(w => /i$/.test(w)) ? [f0, Object.assign({}, f0, { style: 'italic' })] : [f0];
    }
    return [st1(e.w.indexOf('400') >= 0 ? '400' : e.w[0])];
  };
  // the sixteen iOS sample pictures (a 1x1 PNG each here)
  const PNG1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
  const APPLE = [0x1F600, 0x1F602, 0x1F970, 0x1F60E, 0x1F973, 0x1F62D, 0x1F44D, 0x1F44B, 0x2764, 0x1F525, 0x1F389, 0x1F436, 0x1F984, 0x1F355, 0x1F680, 0x1F308].map(cp => ({ cp, b64: PNG1 }));

  const missing = new Set(opts.missing || []);
  const b = window.AndroidBridge = {
    // the page's start
    vibrate() {}, loadPreferences() { return J({ version: 2, preset: 'material3', appearance: opts.dark === false ? 'light' : 'dark', overrides: { dark: {}, light: {} } }); }, savePreferences() {},
    loadCustomLists() { return '[]'; }, saveCustomLists() {},
    getSystemInfo() { return J({ manufacturer: samsung ? 'samsung' : 'Google', model: samsung ? 'SM-S948U' : 'Pixel 9', androidVersion: String(sdk >= 36 ? 16 : sdk >= 35 ? 15 : sdk >= 34 ? 14 : sdk >= 33 ? 13 : 12), sdk }); },
    isSystemDarkMode() { return opts.dark !== false; }, setSystemBarColor() {}, getMaterialYouColors() { return '{}'; },
    loadSetting(k) { return Object.prototype.hasOwnProperty.call(st.kv, k) ? st.kv[k] : ''; }, saveSetting(k, v) { st.kv[k] = v; return true; },
    saveStore() {}, loadStore() { return ''; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
    loadPackages() { return J([{ pkg: 'com.example.alpha', name: 'Alpha', isSystem: false, version: '1.0' }]); },
    getWorkingMode() { return wm(); }, getWorkingModeFresh() { return wm(); },
    getUpdateState() { return J({ running: false, checkedAt: 0, updates: [], checked: 0, errors: 0, selfStatus: 'ok' }); },
    openUrl(u) { st.calls.push('openUrl:' + u); }, copyToClipboard() {},

    // ---- picking and building ----
    fontMakerPick(slot) {
      st.calls.push('fontMakerPick:' + slot);
      if (st.pickOpen) return J({ ok: false, code: 'busy', error: 'The file chooser is already open.' });
      const what = picks[slot];
      st.pickOpen = true;
      setTimeout(() => {
        st.pickOpen = false;
        const f0 = FILES[what];
        let err = PICK_ERRORS[what] || (f0 ? null : { code: String(what), error: 'The app could not read that file.' });
        // the emoji slot (fmkCopyPick): COLR only, with TrueType outlines
        if (!err && slot === 'emoji' && !/^COLR/.test(f0.info.color)) err = { code: 'not_emoji', error: 'This font has no colour emoji (COLR). Choose a COLR emoji font.' };
        if (!err && slot === 'emoji' && f0.info.outlines !== 'TrueType') err = { code: 'emoji_outlines', error: "This emoji font's outlines are PostScript ones (" + f0.info.outlines + '); only an emoji font with TrueType outlines (glyf) can be added. Choose another emoji font.' };
        if (err) { window.onFontMakerPicked(slot, J(Object.assign({ ok: false }, err))); return; }
        const f = f0, token = 'tok-' + slot + '-' + (++seq);
        tokens[token] = f;
        const info = Object.assign({}, f.info, { warnings: slot === 'emoji' ? f.info.warnings.filter(w => w !== W_NO_LATIN) : f.info.warnings.slice() });
        window.onFontMakerPicked(slot, J({ ok: true, token, fileName: f.fileName, size: f.size, info }));
      }, delay);
      return J({ ok: true });
    },
    fontMakerPreview(token) { st.calls.push('fontMakerPreview:' + token); const f = tokens[token]; return f && f.preview ? (b64[f.preview] || '') : ''; },
    fontMakerBuild(json) {
      st.calls.push('fontMakerBuild');
      let r; try { r = JSON.parse(json); } catch (e) { return J({ ok: false, code: 'bad_request', error: 'The request could not be read.' }); }
      st.requests.push(r);
      if (!r.regular || !tokens[r.regular.token]) return J({ ok: false, code: 'no_regular', error: 'Choose the Regular font again.' });
      if (st.building) return J({ ok: false, code: 'busy', error: 'A font is being made. Wait for it to finish, or cancel it.' });
      // FontPackage.visible: a name with a letter, digit, punctuation or symbol that is not a blank filler, or anything in the emoji blocks U+1F000-U+1FBFF
      const shown = String(r.name || '').replace(/[\u115F\u1160\u3164\uFFA0\u2800]/g, '');
      if (!/[\p{L}\p{N}\p{P}\p{S}]/u.test(shown) && ![...shown].some(c => c.codePointAt(0) >= 0x1F000 && c.codePointAt(0) <= 0x1FBFF)) return J({ ok: false, code: 'bad_name', error: 'Give the font a name.' });
      const old = r.rebuildId ? lib.find(e => e.id === r.rebuildId) : null;
      if (r.rebuildId && !old) return J({ ok: false, code: 'not_found', error: 'That font is not in My fonts any more.' });
      const id = job(), reg = tokens[r.regular.token];
      st.building = true;
      st.buildJob = id; st.cancelled = false;
      const stages = [['read', 1, 2], ['read', 2, 2]];
      if (reg.info.variable) stages.push(['instance', 1, r.bold && r.bold.coords ? 2 : 1]);
      if (reg.info.variable && r.bold && r.bold.coords) stages.push(['instance', 2, 2]);
      if (reg.info.outlines !== 'TrueType') stages.push(['convert', 0, 0]);
      if (r.emoji) stages.push(['emoji', 1200, 3913], ['emoji', 3913, 3913]);
      stages.push(['package', 0, 0], ['sign', 0, 0], ['save', 0, 0]);
      const events = stages.map(s => () => { if (!st.cancelled) window.onFontMakerProgress(id, s[0], s[1], s[2]); });
      events.push(() => {
        st.building = false;
        if (st.cancelled) { window.onFontMakerBuilt(id, J({ ok: false, code: 'cancelled', error: 'The build was cancelled.' })); return; }
        if (st.buildError) { window.onFontMakerBuilt(id, J(Object.assign({ ok: false }, st.buildError))); return; }
        const emo = r.emoji ? (r.emoji.catalogId ? { id: r.emoji.catalogId, name: (CAT.find(c => c.id === r.emoji.catalogId) || {}).name || r.emoji.catalogId } : { id: 'own', name: 'Twemoji Mozilla' }) : null;
        const suffix = String(r.name).replace(/[^A-Za-z0-9]/g, '') || 'Font';
        const fresh = { name: r.name, hasBold: !!r.bold, emoji: emo, sizes: { apk: 2 * MB + (emo ? 2 * MB : 0), regular: MB, bold: r.bold ? MB : 0 } };
        let entry;
        // a rebuild keeps the id, the package and the created date, takes the new name and the next versionCode (FontLibrary.entryFor)
        if (old) { Object.assign(old, fresh, { versionCode: Math.max(old.versionCode, old.installedVersion || 0) + 1, updated: Date.now() }); entry = old; }
        else { entry = Object.assign({ id: 'n' + seq + 'e', pkg: 'com.monotype.android.font.' + suffix + '_a1b2c3', versionCode: 1, created: Date.now(), installed: false, installedVersion: 0, applied: false }, fresh); lib.unshift(entry); }
        const warnings = st.buildWarnings || (emo ? [W_EMOJI_GPOS] : []);
        window.onFontMakerBuilt(id, J({ ok: true, entry, warnings }));
      });
      run(events);
      return J({ ok: true, job: id });
    },
    fontMakerCancel(id) { st.calls.push('fontMakerCancel:' + id); if (id === st.buildJob) st.cancelled = true; return J({ ok: true }); },

    // ---- the library ----
    fontMakerLibrary() { st.calls.push('fontMakerLibrary'); lib.forEach(e => { e.applied = e.id === current; }); return J({ ok: true, entries: lib, device: device() }); },
    fontMakerFontData(id, face) { st.calls.push('fontMakerFontData:' + id + ':' + face); return face === 'bold' ? (b64.bold || '') : (b64.regular || ''); },
    fontMakerDelete(id) {
      st.calls.push('fontMakerDelete:' + id);
      if (id === current) return J({ ok: false, code: 'applied', error: 'Reset the font changer first.' });
      const i = lib.findIndex(e => e.id === id);
      if (i < 0) return J({ ok: false, code: 'not_found', error: 'That font is not in the library.' });
      lib.splice(i, 1);
      return J({ ok: true });
    },
    fontMakerExport(id) {
      st.calls.push('fontMakerExport:' + id);
      if (st.saveOpen) return J({ ok: false, code: 'busy', error: 'The save screen is already open.' });
      st.saveOpen = true;
      const done = () => { st.saveOpen = false; window.onFontMakerExported(id, J({ ok: true, name: ((lib.find(e => e.id === id) || {}).name || 'Font') + '.apk' })); };
      if (opts.holdSave) st.save = done; else setTimeout(done, delay);
      return J({ ok: true });
    },
    fontMakerInstall(id) {
      st.calls.push('fontMakerInstall:' + id);
      if (!samsung) return J({ ok: false, code: 'not_samsung', error: 'Font packages work only on Samsung phones.' });
      const e = lib.find(x => x.id === id), j = job(), priv = !!(WM[st.mode] || {}).isPrivileged;
      run([() => { if (e) { e.installed = true; e.installedVersion = e.versionCode; } window.onFontMakerInstalled(id, J(priv ? { ok: true, how: 'pm', note: '' } : { ok: true, how: 'installer', note: '' })); }]);
      return J({ ok: true, job: j });
    },
    fontMakerUninstall(id) {
      st.calls.push('fontMakerUninstall:' + id);
      const e = lib.find(x => x.id === id), j = job(), priv = !!(WM[st.mode] || {}).isPrivileged;
      // without a working mode the app opens the font's App info (how 'settings') and the package stays until the user taps Uninstall there
      run([() => { if (e && priv) { e.installed = false; e.installedVersion = 0; } window.onFontMakerUninstalled(id, J({ ok: true, how: priv ? 'pm' : 'settings', note: '' })); }]);
      return J({ ok: true, job: j });
    },

    // ---- the font changer (RootGate.unlocked) ----
    fontMakerChangerStatus() {
      st.calls.push('fontMakerChangerStatus');
      const j = job();
      const asked = st.mode;                                         // the mode the status is asked in, as fmkGate reads it when the job starts
      run([() => {
        if (st.statusFails > 0) { st.statusFails--; window.onFontMakerChangerStatus(J({ ok: false, code: 'no_answer', error: 'The phone did not answer. Try again.' })); return; }
        const e = lib.find(x => x.id === current), on = !!(WM[asked] || {}).sysPrivActive;
        if (on && opts.noAppFonts) { window.onFontMakerChangerStatus(J({ ok: true, available: false, unlocked: true, lockReason: '', mode: asked === 'system' ? 'system' : 'root', current: null })); return; }
        window.onFontMakerChangerStatus(J({ ok: true, available: samsung, unlocked: samsung && on, lockReason: on ? '' : lockReason,
          mode: !on ? 'none' : asked === 'system' ? 'system' : 'root', current: e ? { suffix: e.pkg.split('.').pop(), id: e.id, name: e.name } : null }));
      }]);
      return J({ ok: true, job: j });
    },
    fontMakerApply(id) {
      st.calls.push('fontMakerApply:' + id);
      if (!unlocked()) return J({ ok: false, code: 'locked', error: lockReason });
      const e = lib.find(x => x.id === id), j = job();
      run([() => {
        if (opts.applyError) { window.onFontMakerApplied(J(Object.assign({ ok: false }, opts.applyError))); return; }
        current = id;
        const dir = '/data/app_fonts/0/' + e.pkg.split('.').pop();
        const files = [{ name: 'DroidSans.ttf', size: e.sizes.regular, sha256: '3f1c9a7e5b2d4c8f0a6e1b9d7c3f5a2e8b4d6c0f9a1e3b5d7c9f2a4e6b8d0c1f', label: 'u:object_r:app_fonts_data_file:s0' }];
        if (e.hasBold) files.push({ name: 'DroidSans-Bold.ttf', size: e.sizes.bold, sha256: '9e2b4d6f8a0c1e3b5d7f9a2c4e6b8d0f1a3c5e7b9d2f4a6c8e0b1d3f5a7c9e2b', label: 'u:object_r:app_fonts_data_file:s0' });
        window.onFontMakerApplied(J({ ok: true, readBack: { sansLoc: dir + '#' + e.pkg.split('.').pop(), files }, refreshed: true }));
      }]);
      return J({ ok: true, job: j });
    },
    fontMakerReset() {
      st.calls.push('fontMakerReset');
      if (!unlocked()) return J({ ok: false, code: 'locked', error: lockReason });
      const j = job();
      run([() => { if (opts.resetError) { window.onFontMakerReset(J(Object.assign({ ok: false }, opts.resetError))); return; } current = null; window.onFontMakerReset(J({ ok: true, readBack: { sansLoc: 'default#default', files: [] }, refreshed: true })); }]);
      return J({ ok: true, job: j });
    },

    // ---- emoji downloads ----
    emojiCatalog() { st.calls.push('emojiCatalog'); return J(catalog()); },
    emojiDownload(id) {
      st.calls.push('emojiDownload:' + id);
      const it = CAT.find(c => c.id === id);
      if (!it) return J({ ok: false, code: 'unknown', error: 'There is no such emoji font.' });
      const j = job();
      dls[id] = { cancelled: false };
      const steps = [0.1, 0.45, 0.8, 1].map(p => () => { if (!dls[id] || dls[id].cancelled) return; window.onEmojiProgress(id, Math.round(it.size * p), it.size); });
      steps.push(() => {
        const d = dls[id]; delete dls[id];
        if (!d || d.cancelled) { window.onEmojiDone(id, J({ ok: false, code: 'cancelled', error: 'The download was cancelled.' })); return; }
        const err = (st.downloadError || {})[id];
        if (err) { window.onEmojiDone(id, J(Object.assign({ ok: false }, err))); return; }
        downloaded.add(id);
        window.onEmojiDone(id, J({ ok: true, size: it.size, sha256: it.sha256 }));
      });
      run(steps);
      return J({ ok: true, job: j });
    },
    emojiCancel(id) { st.calls.push('emojiCancel:' + id); if (dls[id]) dls[id].cancelled = true; return J({ ok: true }); },
    emojiDelete(id) { st.calls.push('emojiDelete:' + id); if (sysActive === id) return J({ ok: false, code: 'in_use', error: 'Undo the system emoji first.' }); downloaded.delete(id); return J({ ok: true }); },

    // ---- system emoji (RootGate.uid0), until the next restart ----
    sysEmojiStatus() {
      st.calls.push('sysEmojiStatus');
      const j = job();
      run([() => window.onSysEmojiStatus(J({ ok: true, unlocked: uid0(), lockReason: uid0() ? '' : lockSys,
        targets: ['/system/fonts/NotoColorEmoji.ttf', '/system/fonts/NotoColorEmojiFlags.ttf'], active: sysActive ? { id: sysActive, name: (CAT.find(c => c.id === sysActive) || {}).name } : null,
        blocked: uid0() && opts.sysBlocked ? opts.sysBlocked : null }))]);
      return J({ ok: true, job: j });
    },
    sysEmojiApply(id) {
      st.calls.push('sysEmojiApply:' + id);
      if (!uid0()) return J({ ok: false, code: 'locked', error: lockSys });
      const j = job();
      run([() => {
        if (opts.sysApplyError) { if (opts.sysApplyError.readBack && opts.sysApplyError.readBack.some(x => x.ok)) sysActive = id; window.onSysEmojiApplied(J(Object.assign({ ok: false }, opts.sysApplyError))); return; }
        sysActive = id; const it = CAT.find(c => c.id === id); window.onSysEmojiApplied(J({ ok: true, readBack: [
        { target: '/system/fonts/NotoColorEmoji.ttf', sha256: it.sha256, ok: true }, { target: '/system/fonts/NotoColorEmojiFlags.ttf', sha256: it.sha256, ok: true }] })); }]);
      return J({ ok: true, job: j });
    },
    sysEmojiUndo() {
      st.calls.push('sysEmojiUndo');
      const j = job();
      run([() => {
        if (opts.sysUndoError) { window.onSysEmojiUndone(J(Object.assign({ ok: false }, opts.sysUndoError))); return; }
        sysActive = null; window.onSysEmojiUndone(J({ ok: true, readBack: [{ target: '/system/fonts/NotoColorEmoji.ttf', sha256: 'c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00', ok: true }], copyRemoved: true }));
      }]);
      return J({ ok: true, job: j });
    },
    softRestart() { st.calls.push('softRestart'); return uid0() ? J({ ok: true }) : J({ ok: false, code: 'locked', error: 'Needs Root.' }); },

    // ---- v7.18.0 Google Fonts ----
    gfontsCatalog() { st.calls.push('gfontsCatalog'); return J(Object.assign({ ok: true, stale: gfIsStale(), count: gfCat.families.length }, gfCat)); },
    gfontsRefresh(force) {
      st.calls.push('gfontsRefresh:' + force);
      if (gfRefreshJob) return J({ ok: true, job: gfRefreshJob });
      if (!force && gfCat.at > 0 && !gfIsStale()) return J({ ok: true, fresh: true });
      if (st.gfOtherWindow) return J({ ok: false, code: 'busy', error: 'Another window of the app is getting the list of fonts. Try again in a moment.' });
      const j = gfRefreshJob = job();
      gfRun([() => {
        gfRefreshJob = null;
        if (st.gfRefreshError) { window.onGfCatalog(J(Object.assign({ ok: false }, st.gfRefreshError))); return; }
        gfGot();
        window.onGfCatalog(J({ ok: true, at: gfCat.at, count: gfCat.families.length }));
      }]);
      return J({ ok: true, job: j });
    },
    gfontsPreview(reqId, family, mode, text) {
      st.calls.push('gfontsPreview:' + family + ':' + mode);
      st.gfPreviews.push({ reqId, family, mode, text });
      if (!/^[A-Za-z0-9_-]{1,24}$/.test(String(reqId)) || ['card', 'styles', 'axes'].indexOf(mode) < 0) return J({ ok: false, code: 'bad_request', error: 'The request could not be read.' });
      const e = gfFam(family);
      if (!e) return J({ ok: false, code: 'gf_unknown', error: GF_UNKNOWN });
      st.gfOut++; st.gfOutMax = Math.max(st.gfOutMax, st.gfOut);
      const answer = () => {
        st.gfOut--;
        if ((G.previewError || []).indexOf(family) >= 0) { window.onGfPreview(reqId, J({ ok: false, code: 'gf_no_preview', error: 'Google Fonts sent no preview for this family.' })); return; }
        if ((G.previewFail || []).indexOf(family) >= 0) { window.onGfPreview(reqId, J({ ok: false, code: 'network', error: 'The download failed: timeout' })); return; }
        window.onGfPreview(reqId, J({ ok: true, family, mode, faces: gfFaces(e, mode) }));
      };
      if (G.hold) pvq.push({ reqId, family, mode, text, run: answer }); else setTimeout(answer, delay);
      return J({ ok: true });
    },
    gfontsInfo(family) {
      st.calls.push('gfontsInfo:' + family);
      const e = gfFam(family);
      if (!e) return J({ ok: false, code: 'gf_unknown', error: GF_UNKNOWN });
      if (gfInfoJobs[family]) return J({ ok: true, job: gfInfoJobs[family] });
      if (st.gfOtherWindow) return J({ ok: false, code: 'busy', error: 'Another window of the app is getting the details of this family. Try again in a moment.' });
      const j = gfInfoJobs[family] = job();
      gfRun([() => {
        delete gfInfoJobs[family];
        if (st.gfInfoError) { window.onGfInfo(family, J(Object.assign({ ok: false }, st.gfInfoError))); return; }
        const lic = gfLicense(family);
        window.onGfInfo(family, J({ ok: true, family, description: gfDesc(e), license: lic.kind, licenseName: lic.name, licenseText: lic.text, reserved: gfReserved(family),
          designers: e.d.slice(), files: gfFilesOf(e).map(x => ({ name: x.name, variable: x.variable, italic: x.italic })) }));
      }]);
      return J({ ok: true, job: j });
    },
    gfontsDownloads() {
      st.calls.push('gfontsDownloads');
      return J({ ok: true, families: Object.keys(gfDl).map(k => gfDl[k]).sort((a, b) => b.at - a.at) });
    },
    gfontsDownload(family) {
      st.calls.push('gfontsDownload:' + family);
      const e = gfFam(family);
      if (!e) return J({ ok: false, code: 'gf_unknown', error: GF_UNKNOWN });
      if (gfJobs[family]) return J({ ok: false, code: 'busy', error: 'This font family is already downloading.' });
      const files = gfFilesOf(e), n = files.length, j = job();
      gfJobs[family] = { cancelled: false };
      const live = () => gfJobs[family] && !gfJobs[family].cancelled;
      const ev = [];
      files.forEach((f, i) => {
        ev.push(() => { if (live()) window.onGfProgress(family, i + 1, n, Math.round(f.size / 2), f.size); });
        ev.push(() => { if (live()) window.onGfProgress(family, i + 1, n, f.size, f.size); });
      });
      ev.push(() => {
        const d = gfJobs[family];
        delete gfJobs[family];
        if (!d || d.cancelled) { window.onGfDone(family, J({ ok: false, code: 'cancelled', error: 'The download was cancelled.' })); return; }
        const err = (st.gfDownloadError || {})[family];
        if (err) { window.onGfDone(family, J(Object.assign({ ok: false }, err))); return; }
        gfDl[family] = gfRow(e); gfDlOf[family] = e;
        window.onGfDone(family, J({ ok: true, entry: gfDl[family] }));
      });
      gfRun(ev);
      return J({ ok: true, job: j });
    },
    gfontsCancel(family) { st.calls.push('gfontsCancel:' + family); if (gfJobs[family]) gfJobs[family].cancelled = true; return J({ ok: true }); },
    gfontsDelete(family) {
      st.calls.push('gfontsDelete:' + family);
      if (gfJobs[family]) return J({ ok: false, code: 'busy', error: 'This font family is downloading. Cancel it first.' });
      if (st.gfSaving === family) return J({ ok: false, code: 'busy', error: 'This font family is being saved. Wait for it to finish.' });
      if (!gfDl[family]) return J({ ok: false, code: 'gf_not_downloaded', error: 'Download this family first.' });
      delete gfDl[family]; delete gfDlOf[family];
      return J({ ok: true });
    },
    gfontsUse(family, fileName, slot) {
      st.calls.push('gfontsUse:' + family + ':' + fileName + ':' + slot);
      if (st.pickOpen) return J({ ok: false, code: 'busy', error: 'The file chooser is already open.' });
      if (slot !== 'regular' && slot !== 'bold') return J({ ok: false, code: 'bad_request', error: 'The request could not be read.' });
      // the download alone (its meta.json), as the app: a family the list no longer has is still used
      const row = gfDl[family], e = gfDlOf[family];
      if (!row || !e) return J({ ok: false, code: 'gf_not_downloaded', error: 'Download this family first.' });
      const fi = row.files.find(x => x.name === fileName);
      if (!fi) return J({ ok: false, code: 'gf_bad_file', error: "That file is not part of this family's download." });
      setTimeout(() => {
        if (G.useError && (!G.useError.slot || G.useError.slot === slot)) { const x = Object.assign({ ok: false }, G.useError); delete x.slot; window.onFontMakerPicked(slot, J(x)); return; }
        const token = 'tok-gf-' + slot + '-' + (++seq), info = gfInfoOf(e, fi);
        tokens[token] = { fileName: fi.name, size: fi.size, preview: fi.variable ? 'vf' : fi.weight >= 600 ? 'bold' : 'regular', info };
        window.onFontMakerPicked(slot, J({ ok: true, token, fileName: fi.name, size: fi.size, info, gf: { family, reserved: gfReserved(family) } }));
      }, delay);
      return J({ ok: true });
    },
    gfontsSave(family) {
      st.calls.push('gfontsSave:' + family);
      if (st.saveOpen) return J({ ok: false, code: 'busy', error: 'The save screen is already open.' });
      if (!gfDl[family]) return J({ ok: false, code: 'gf_not_downloaded', error: 'Download this family first.' });
      st.saveOpen = true; st.gfSaving = family;
      const done = () => {
        st.saveOpen = false; st.gfSaving = '';
        window.onGfSaved(family, J(G.saveError ? Object.assign({ ok: false }, G.saveError) : { ok: true, name: family.replace(/ /g, '') + '.zip' }));
      };
      if (opts.holdSave) st.save = done; else setTimeout(done, delay);
      return J({ ok: true });
    },

    // ---- v7.18.0 the emoji samples ----
    emojiSpecimenFont(id) { st.calls.push('emojiSpecimenFont:' + id); return (opts.emoB64 || {})[id] || ''; },
    emojiSpecimenCached(id) { st.calls.push('emojiSpecimenCached:' + id); return J({ ok: true, images: id === 'apple' && st.appleCached ? APPLE : [] }); },
    emojiSpecimenFetch(id) {
      st.calls.push('emojiSpecimenFetch:' + id);
      if (id !== 'apple') return J({ ok: false, code: 'unknown', error: 'There is no such emoji font.' });
      if (appleJob) return J({ ok: true, job: appleJob });
      if (st.gfOtherWindow) return J({ ok: false, code: 'busy', error: 'Another window of the app is reading the iOS emoji. Try again in a moment.' });
      const j = appleJob = job();
      gfRun([() => {
        appleJob = null;
        if (st.specimenError) { window.onEmojiSpecimen('apple', J(Object.assign({ ok: false }, st.specimenError))); return; }
        st.appleCached = true;
        window.onEmojiSpecimen('apple', J({ ok: true, images: APPLE, from: downloaded.has('apple') ? 'file' : 'web' }));
      }]);
      return J({ ok: true, job: j });
    },
  };
  st.kv = Object.assign({}, opts.kv || {});
  st.statusFails = opts.statusFails || 0;
  st.buildError = opts.buildError || null; st.buildWarnings = opts.buildWarnings || null; st.downloadError = opts.downloadError || {};
  st.gfRemote = null; st.gfRefreshError = G.refreshError || null; st.gfInfoError = G.infoError || null; st.gfDownloadError = G.downloadError || {};
  st.specimenError = G.specimenError || null; st.appleCached = !!G.appleCached; st.gfSaving = ''; st.gfOtherWindow = !!G.otherWindow;
  st.gfDl = gfDl;
  Object.keys(opts.refuse || {}).forEach(m => { b[m] = function () { st.calls.push(m + ':' + [].join.call(arguments, ',')); return J(Object.assign({ ok: false }, opts.refuse[m])); }; });
  missing.forEach(m => { delete b[m]; });
  st.setMode = (m) => { st.mode = m; };
};
