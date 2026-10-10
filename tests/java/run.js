#!/usr/bin/env node
// Runs the Java checks of the parts of the app that need no Android classes (settings and overlay rules, file rules, install hints, the
// archive engine, the Rish shell, the APK signer, the manifest decoder, the store parsers ...). The classes under test are compiled straight
// from src/ together with the test in tests/java/src, with the JDK on this machine.
//
//   node java/run.js                 every suite
//   node java/run.js ziptool rish    only these
//   node java/run.js --list          name, what it covers and what it needs
//   node java/run.js --jobs 2        suites side by side (default 1: the Rish suites count processes, so keep them apart)
//
// A suite passes when it exits with 0 and prints no line starting with "FAIL". A suite whose prerequisite is missing is SKIPPED (and
// says what to install); that is not a failure, unless you pass --strict.
//
// Prerequisites by suite (see --list): a JDK (javac + java), Node for the parity suite, org.json (ORG_JSON_JAR, see below) for the store
// parsers, python3 + zip for the archive suites, mksh + toybox for Rish, an APK (TEST_APK, default bin/ADB_Application_Manager_Pro.apk
// from ./build.sh) + apksigner (APKSIGNER, or on PATH, or in ANDROID_HOME/build-tools) + keytool for the signer.
// org.json: Android's copy is only stubs, so pass a real one: ORG_JSON_JAR=/path/json-20240303.jar
//   (curl -L -o tests/.cache/org-json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar), or ANDROID_ALL_JAR=/path/android-all-*.jar
//   (Robolectric's all-in-one Android jar, which has org.json and the real android.util.JsonReader the F-Droid parser uses).
'use strict';
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, spawnSync } = require('child_process');

const HERE = __dirname;
const TESTS = path.resolve(HERE, '..');
const REPO = path.resolve(TESTS, '..');
const JSRC = path.join(REPO, 'src');                       // source path root: com/bloatware/bingblop/*.java
const TSRC = path.join(HERE, 'src');
const OUT = path.join(TESTS, 'out', 'java');
const CACHE = path.join(TESTS, '.cache');

const SUITES = [
  { name: 'settingsdb', title: 'Android settings (Global / Secure / System): names, values, quoting, read-back verdicts, run through a real sh with a fake settings command',
    tests: ['SettingsDbTest'], main: 'com.bloatware.bingblop.SettingsDbTest', utf8: true },
  { name: 'overlayrules', title: 'Overlays and Material You: cmd overlay output, names, the theme value and its merge, every verdict and the Samsung switch, against a fake phone',
    tests: ['OverlayRulesTest'], main: 'com.bloatware.bingblop.OverlayRulesTest', utf8: true },
  { name: 'parity', title: 'The page and the Java rules give the same answer to the same names, values and colors (compares ~1,300 inputs)',
    kind: 'parity', needs: ['node'] },
  { name: 'filerules', title: 'File manager path rules: canonical form, protected folders', tests: ['FileRulesTest'], main: 'com.bloatware.bingblop.FileRulesTest' },
  { name: 'apktrash', title: 'Deleting a found package file with Undo (which paths, where it waits) and the storage search\'s progress, against a real folder tree', tests: ['ApkTrashTest'], main: 'com.bloatware.bingblop.ApkTrashTest', needs: ['json'] },
  { name: 'fileops', title: 'File manager copy, move and delete with replace / skip / keep both, free names (name (1).ext), cancel, links, atomic write, and the same rules as a shell script', tests: ['FileOpsTest'], main: 'com.bloatware.bingblop.FileOpsTest' },
  { name: 'filesearch', title: 'File search: the query (name, wildcards, ending, size, date, kind, content:, archive:), the bounded walk, hidden and nested options, zip entries, links, limits and Cancel', tests: ['FileSearchTest'], main: 'com.bloatware.bingblop.FileSearchTest' },
  { name: 'rarreader', title: 'RAR archives: RAR 1.5-4.x through junrar and RAR 5 / 7 (own parser, LZ unpacker, filters, solid, AES-256 data and headers, CRC32 / BLAKE2sp): list, one-pass walk, open, passwords, damaged and hostile input; real archives from tests/java/fixtures/rar when present', tests: ['RarReaderTest'], main: 'com.bloatware.bingblop.RarReaderTest' },
  { name: 'zipwriter', title: 'New zip archives: stored / deflated, folders, unicode, ZIP64 end record, AES-256 / AES-128 / ZipCrypto passwords read back through ZipTool, Info-ZIP and pyzipper, wrong password, Cancel', tests: ['ZipWriterTest'], main: 'com.bloatware.bingblop.ZipWriterTest', utf8: true },
  { name: 'zipcrypt', title: 'Zip passwords: traditional ZipCrypto and WinZip AES (128 / 192 / 256), raw entry streams checked against Info-ZIP zip / unzip and pyzipper, wrong password, tampering, empty, > 5 MB, unicode', tests: ['ZipCryptTest'], main: 'com.bloatware.bingblop.ZipCryptTest' },
  { name: 'archiveio', title: 'Archive formats (7z, tar, tar.gz / bz2 / xz / zst / lz4, single gz / bz2 / xz / zst / lz4): detect, list, one-pass walk, open, create, rewrite with edits, 7z AES with encrypted names, cancel, atomic .part; checked against 7-Zip, py7zr, tar, gzip, bzip2, xz, zstd, lz4',
    tests: ['ArchiveIoTest'], main: 'com.bloatware.bingblop.ArchiveIoTest', utf8: true },
  { name: 'progressmeter', title: 'What a long job shows: percent, smoothed speed, time left, the stall check and the wording', tests: ['ProgressMeterTest'], main: 'com.bloatware.bingblop.ProgressMeterTest' },
  { name: 'jobticker', title: 'The once-a-second report of a long job on its own thread: follows the meter, notices a stall while the worker is stuck, stops, survives a failing listener', tests: ['JobTickerTest'], main: 'com.bloatware.bingblop.JobTickerTest' },
  { name: 'reposources', title: 'F-Droid and IzzyOnDroid as download sources: the version list, latest and requested (with a build number), the file address, a package the site does not list, hostile input, against a fake repository', tests: ['RepoSourcesTest'], main: 'com.bloatware.bingblop.RepoSourcesTest', needs: ['json'] },
  { name: 'authmanager', title: 'The authorization code: its shape (25 characters, 125 bits), kept, replaced, repaired when damaged, and how a typed one is compared', tests: ['AuthManagerTest'], main: 'com.bloatware.bingblop.AuthManagerTest' },
  { name: 'logrecorder', title: 'Recording the log to a file: only new lines are written, errors and empty snapshots skipped, the size limit, stop, the list, reading a long file from its end, refused names, UTF-8', tests: ['LogRecorderTest'], main: 'com.bloatware.bingblop.LogRecorderTest', utf8: true },
  { name: 'installguards', title: 'Pre-install checks fail closed: a published checksum that is not a SHA-256 stops the install, and so does an APK whose signing certificate cannot be read', tests: ['InstallGuardsTest'], main: 'com.bloatware.bingblop.InstallGuardsTest' },
  { name: 'assistantrestore', title: 'The saved assistant settings for the launch-through-assistant method: only plain component names are written back, and the record round-trips', tests: ['AssistantRestoreTest'], main: 'com.bloatware.bingblop.AssistantRestoreTest' },
  { name: 'shelloutcome', title: 'The verdict of a privileged command run with an exit-status marker: the status when the marker is there; an Error: line or a timeout note without it is a failure, not an "ok"', tests: ['ShellOutcomeTest'], main: 'com.bloatware.bingblop.ShellOutcomeTest' },
  { name: 'adbserverspec', title: 'Where the bundled adb server listens: a private unix socket in the app\'s folder (closed to other apps), the old loopback port only as the fallback', tests: ['AdbServerSpecTest'], main: 'com.bloatware.bingblop.AdbServerSpecTest' },
  { name: 'sdmstatframe', title: 'SD Maid stat records carry the scan\'s own random tag, so a file name with a newline cannot forge the record of another file', tests: ['SdmStatFrameTest'], main: 'com.bloatware.bingblop.SdmStatFrameTest', needs: ['json'] },
  { name: 'zipbounds', title: 'A small zip cannot fill the storage (extraction stops at the entry\'s declared size) or list millions of files (entry cap)', tests: ['ZipBoundsTest'], main: 'com.bloatware.bingblop.ZipBoundsTest' },
  { name: 'apkflags', title: 'Found package files: identical copies (hash) and older versions of a package, which one is kept', tests: ['ApkFlagsTest'], main: 'com.bloatware.bingblop.ApkFlagsTest' },
  { name: 'procstats', title: '`ps` output: toybox\'s `-o PID,PPID,USER,RSS,%CPU,NAME` (by header column, not fixed offsets) and a degraded busybox-style `ps -A` fallback, truncated rows, package name vs. kernel/native process, top by CPU / RSS', tests: ['ProcStatsTest'], main: 'com.bloatware.bingblop.ProcStatsTest' },
  { name: 'gpustats', title: 'GPU load/frequency sysfs text parsing: Adreno gpubusy/gpu_busy_percentage, Mali utilization, the devfreq frequency-ratio fallback, combine() priority and the UI label', tests: ['GpuStatsTest'], main: 'com.bloatware.bingblop.GpuStatsTest' },
  { name: 'cpustats', title: '/proc/stat CPU-time parsing: the aggregate and per-core lines, tolerance for missing cores and extra kernel fields, and the busy-percentage math (counter resets, a core-count mismatch)', tests: ['CpuStatsTest'], main: 'com.bloatware.bingblop.CpuStatsTest' },
  { name: 'memstats', title: '/proc/meminfo parsing: the kept keys in any order, a kernel too old for MemAvailable, unknown extra keys, and the usedKb/swapUsedKb formulas', tests: ['MemStatsTest'], main: 'com.bloatware.bingblop.MemStatsTest' },
  { name: 'ptyshell', title: 'The real terminal: the pty helper (native/pty) and PtyShell: a tty of the asked size, resize, Ctrl-C, UTF-8, status codes, hang-up, flow control, and the scripts that start it on the phone and in Termux', tests: ['PtyShellTest'], main: 'com.bloatware.bingblop.PtyShellTest', needs: ['pty'], utf8: true },
  { name: 'browserdownload', title: 'What the in-app browser saves: file names (Content-Disposition, address, type), the browser\'s cookies / agent / referer, redirects, refusals in words, a web page is not a file, no partial files left, cancel, resuming (Range, If-Range, validators, 416, servers without ranges, remembered address)', tests: ['BrowserDownloadTest'], main: 'com.bloatware.bingblop.BrowserDownloadTest' },
  { name: 'downloadnotice', title: 'The Helper downloads notification: how many, how far, how fast, how long is left, and how it ended', tests: ['DownloadNoticeTest'], main: 'com.bloatware.bingblop.DownloadNoticeTest' },
  { name: 'helperdownloads', title: 'The Helper download list: start, pause, resume (only the rest is asked for), retry, cancel, remove, at most three at a time, the journal after a restart, what a bad journal may not do', tests: ['HelperDownloadsTest'], main: 'com.bloatware.bingblop.HelperDownloadsTest', needs: ['json'] },
  { name: 'netstats', title: '/proc/net/dev parsing: the two header lines skipped by shape, long interface names, wrong-field-count junk lines, find()/totals with and without loopback, and rate() across a counter reset', tests: ['NetStatsTest'], main: 'com.bloatware.bingblop.NetStatsTest' },
  { name: 'fontscan', title: 'Font search for the app font: which files are fonts, what a font calls itself (the name table in every encoding and shape), the bounded walk and its progress', tests: ['FontScanTest'], main: 'FontScanTest', needs: ['json'] },
  { name: 'splitinfo', title: 'What a split APK\'s manifest says about it (split name, the feature module it configures, feature flag)', tests: ['SplitInfoTest'], main: 'com.bloatware.bingblop.SplitInfoTest' },
  { name: 'enginezip', title: 'The Morphe engine zip holds Kotlin\'s built-in metadata and the other files the patcher reads at run time (a bundle fails on a phone without them)', tests: ['EngineZipTest'], main: 'EngineZipTest' },
  { name: 'abipick', title: 'Which release file and which adb suit a phone: the file named for its ABI, else the universal one, else the first', tests: ['AbiPickTest'], main: 'AbiPickTest' },
  { name: 'devicelink', title: 'Sending packages to another device: serials, install verdicts, the splits a device needs (CPU, density, language), APKS / APKM / XAPK with OBB, against a fake adb', tests: ['DeviceLinkTest'], main: 'DeviceLinkTest', needs: ['json'] },
  { name: 'appextras', title: 'App menu Features / Configurations / Signatures / Libraries tabs: the words for OpenGL ES, touch, keyboard and navigation numbers, the libraries a manifest names, a signing certificate against keytool, the v1 / v2 / v3 / v3.1 schemes of an APK, its native libraries', tests: ['AppExtrasTest'], main: 'AppExtrasTest', needs: ['json'] },
  { name: 'installhints', title: 'What an install failure means (adb / pm install answers)', tests: ['InstallHintsTest'], main: 'com.bloatware.bingblop.InstallHintsTest' },
  { name: 'trackerupdate', title: 'Update the tracker list: the Exodus feed is checked and cut down before it can replace the list in use (too few trackers, not JSON, HTTP error, no connection)', tests: ['TrackerUpdateTest'], main: 'com.bloatware.bingblop.TrackerUpdateTest', needs: ['json'] },
  { name: 'trackers', title: 'Tracker libraries in an app (Exodus list): a hand-built dex, classes defined or only referenced, stored and deflated entries, split apks, damaged files', tests: ['TrackersTest'], main: 'com.bloatware.bingblop.TrackersTest', needs: ['json', 'fixtures'] },
  { name: 'batchverify', title: 'After a batch, the real state decides: uninstall, reinstall, freeze and unfreeze are read back from the package lists (a run whose commands all said "failed" while every app was gone)', tests: ['BatchVerifyTest'], main: 'com.bloatware.bingblop.BatchVerifyTest', needs: ['json'] },
  { name: 'uninstallhints', title: 'What an uninstall failure means (pm uninstall answers: needs root, device policy, user restriction)', tests: ['UninstallHintsTest'], main: 'com.bloatware.bingblop.UninstallHintsTest' },
  { name: 'devuninstall', title: 'Removing an app on another device (Connected Devices): pm uninstall --user 0, the Binder helper pushed, run and taken off again when a system app needs it, what may go into the shell, against a fake adb', tests: ['DeviceUninstallTest'], main: 'com.bloatware.bingblop.DeviceUninstallTest', needs: ['json'] },
  { name: 'pure', title: 'Package-file scan output and XAPK data paths', tests: ['PureTest'], main: 'PureTest', needs: ['json'] },
  { name: 'storedetail', title: 'App Stores: the detail of an app (pictures of a README, the side file of a repository, GitHub and Codeberg projects)', tests: ['StoreDetailTest'], main: 'com.bloatware.bingblop.StoreDetailTest', needs: ['androidall'] },
  { name: 'fdroid', title: 'F-Droid index v1 / v2 parsing (real Seeker and WG Tunnel indexes)', tests: ['FdroidTest'], main: 'com.bloatware.bingblop.FdroidTest', needs: ['androidall', 'fixtures'] },
  { name: 'komi', title: 'GitHub catalog (Komi) feeds: mapping, de-duplication, dates', tests: ['KomiTest'], main: 'KomiTest', needs: ['json', 'fixtures'] },
  { name: 'ziptool', title: 'Archive engine: read, edit and rewrite zips (bad, truncated, encrypted, huge, zip64, streamed), alignment, diff', tests: ['ZipToolTest'], main: 'ZipToolTest', needs: ['zipfix1', 'apk?'] },
  { name: 'zipreview', title: 'Archive engine, review findings: odd names, duplicates, prepended data, extra fields, AES, symlinks', tests: ['ZipReviewTest'], main: 'ZipReviewTest', needs: ['zipfix2'] },
  { name: 'rish', title: 'Rish shell: persistent shell, cd / export, output, STOP, restart (against this machine\'s sh)', tests: ['RishTest'], main: 'RishTest' },
  { name: 'termuxlink', title: 'Terminal sessions in Termux: the bridge script, the token check (impostors refused), a live bash over the loopback connection (bash syntax, cd / export, big output, UTF-8, STOP through helper commands, exit), and every way Termux can refuse or stay silent - with a local bash standing in for Termux', tests: ['TermuxLinkTest'], main: 'com.bloatware.bingblop.TermuxLinkTest', needs: ['bash'] },
  { name: 'agentrules', title: 'Coding agents\' keys: which address each provider\'s key may go to (look-alike hosts, ports, schemes, user info), own-server addresses, hints, redaction, reserved headers, AES-GCM sealing bound to the provider', tests: ['AgentRulesTest'], main: 'com.bloatware.bingblop.AgentRulesTest', utf8: true },
  { name: 'morpheevents', title: 'Morphe Patcher: the engine\'s protocol lines (LOG, STEP, APP, PATCH, RESULT) and the tail that reads them from a growing file (half lines, UTF-8, restart, bursts)', tests: ['MorpheEventsTest'], main: 'com.bloatware.bingblop.MorpheEventsTest', needs: ['json'] },
  { name: 'morphelibrary', title: 'Morphe Patcher: the Patched APKs folder (filing a run, list, delete, export with free names, the log, path safety) and the .apks bundle of a split app', tests: ['MorpheLibraryTest'], main: 'com.bloatware.bingblop.MorpheLibraryTest', needs: ['json'] },
  { name: 'morphenet', title: 'Morphe Patcher: the small HTTP client against local servers: redirects inside a host and to another host (a key never follows), https only, the whole download or nothing', tests: ['MorpheNetTest'], main: 'com.bloatware.bingblop.MorpheNetTest' },
  { name: 'sdmsieve', title: 'SD Maid SE port, shared sieve: segment helpers, every criterion (Anc/Start/End/Contain with partial and case modes, name criteria, And/Or), the crawler conjunction, distinct roots, the upstream edge cases', tests: ['SdmSieveTest'], main: 'com.bloatware.bingblop.SdmSieveTest', needs: ['json'] },
  { name: 'sdmsystemcleaner', title: 'SD Maid SE port, SystemCleaner: the 22 filters on a real folder tree with fake data areas, settings and the root gate, exclusions and the nested rule, cancel, progress and result texts, delete (selection, distinct roots, re-verify, snapshot after the delete) and the safety rules', tests: ['SdmSystemCleanerTest'], main: 'com.bloatware.bingblop.SdmSystemCleanerTest', needs: ['json'] },
  { name: 'sdmappcleaner', title: 'SD Maid SE port, AppCleaner: the 21 filters and the JSON sieves, apps and their exclusions, system apps, inaccessible caches, delete with a fake shell (trim-caches, force-stop) and a fake automation, the result lines', tests: ['SdmAppCleanerTest'], main: 'com.bloatware.bingblop.SdmAppCleanerTest', needs: ['json'] },
  { name: 'sdmacsplan', title: 'SD Maid SE port, accessibility cache clearing: the step engine against a fake settings screen (AOSP, One UI, MIUI / HyperOS plans), labels, scrolling, timeouts, screen off, cancel, the failure budget', tests: ['SdmAcsPlanTest'], main: 'com.bloatware.bingblop.SdmAcsPlanTest', needs: ['json'] },
  { name: 'sdmcorpse', title: 'SD Maid SE port, CorpseFinder: owner attribution per area, marker database, whitelist and blacklist rule, risk, exclusions, cancel, delete of whole remnants and chosen content paths', tests: ['SdmCorpseFinderTest'], main: 'com.bloatware.bingblop.SdmCorpseFinderTest', needs: ['json'] },
  { name: 'sdmdedup', title: 'SD Maid SE port, Deduplicator: size buckets, prefix check, SHA-256, minimum size, links, arbiter, keep-one and delete-all, safety re-checks, cancel', tests: ['SdmDedupTest'], main: 'com.bloatware.bingblop.SdmDedupTest', needs: ['json'] },
  { name: 'sdmengine', title: 'SD Maid SE port, task engine and bridge against fake tools: two tasks at a time with the rest in the queue, cancel, paged results, receipts, History, exclusions with Undo, one-click, settings, the bridge ops in JSON', tests: ['SdmEngineTest'], main: 'com.bloatware.bingblop.SdmEngineTest', needs: ['json'] },
  { name: 'sdmexclusions', title: 'SD Maid SE port, exclusions: apps, paths and segments with tool tags, the stock defaults (remove and restore), matching, the nested rule, import and export', tests: ['SdmExclusionsTest'], main: 'com.bloatware.bingblop.SdmExclusionsTest', needs: ['json'] },
  { name: 'sdmhistory', title: 'SD Maid SE port, History of what was deleted: reports and paths, retention, totals, reset', tests: ['SdmHistoryTest'], main: 'com.bloatware.bingblop.SdmHistoryTest', needs: ['json'] },
  { name: 'sdmareas', title: 'SD Maid SE port, data areas and the shell file system: which areas are read with Java, through the working mode or not at all (and why), stat / list / walk / hash / delete through a real sh', tests: ['SdmAreasTest'], main: 'com.bloatware.bingblop.SdmAreasTest', needs: ['json'], utf8: true },
  { name: 'morphejobs', title: 'Morphe Patcher: the job file of the engine (bundles, options, strip libraries, keystore, errors in words), which files the page may name, the words for a patcher that died', tests: ['MorpheJobsTest'], main: 'com.bloatware.bingblop.MorpheJobsTest', needs: ['json'] },
  { name: 'morpheengine', title: 'The real Morphe engine on this computer: the job file the app writes, the engine listing the patches of the official bundle, patching a real APK with universal patches, the events read back and the result signed (needs engine/build-engine.sh run once, the official bundle and an APK)', tests: ['MorpheEngineTest'], main: 'com.bloatware.bingblop.MorpheEngineTest', needs: ['json', 'engine', 'mpp', 'apk', 'apksigner'] },
  { name: 'aihttp', title: 'Coding agents\' HTTPS calls against a local server: whole and error answers, streaming piece by piece, Cancel mid-stream, no redirects, UTF-8, kept headers, network failures in words', tests: ['AiHttpTest'], main: 'com.bloatware.bingblop.AiHttpTest', utf8: true },
  { name: 'morphestore', title: 'Morphe patch sources against a local server: typed addresses and deep links, versions, bundle manifests, add from patches-bundle.json / releases / a file, updates that keep the old bundle when a download dies, the saved list, a damaged sources.json, hostile ids, the community cache',
    tests: ['MorpheStoreTest'], main: 'com.bloatware.bingblop.MorpheStoreTest', needs: ['json'] },
  { name: 'morphehelper', title: 'Morphe Helper (port of Helper for Morphe): the ten sources and their flags, every resolver against a local server with page fixtures (version lists, requested / latest, missing package, changed page, ABI and format, Cloudflare), Fast Mode, download with checksums, real-manifest check, atomic file and cancel, and APK / apks / xapk / apkm inspection',
    tests: ['MorpheHelperTest'], main: 'com.bloatware.bingblop.MorpheHelperTest', needs: ['json', 'fixtures', 'stubs', 'apk?'], utf8: true },
  { name: 'httpsafe', title: 'Redirects followed by hand: no https-to-http downgrade, no outside address leading to loopback, keys stay with their host, at most 8 hops (local server)', tests: ['HttpSafeTest'], main: 'com.bloatware.bingblop.HttpSafeTest' },
  { name: 'virustotalhost', title: 'The older VirusTotal client sends the API key only to https addresses on the API host (upload address from the server is checked), and follows no redirect', tests: ['VirusTotalHostTest'], main: 'com.bloatware.bingblop.VirusTotalHostTest', needs: ['json'] },
  { name: 'morphevt', title: 'Morphe Helper VirusTotal scan against a local server: lookup found / not found / wrong key / quota, cached report, upload and polling, upload_url above 32 MB, split bundles, the 4 per minute / 500 per day limiter with a fake clock and its saved counters, the key never in a message, Cancel',
    tests: ['MorpheVirusTotalTest'], main: 'com.bloatware.bingblop.MorpheVirusTotalTest', needs: ['json'], utf8: true },
  { name: 'rishreview', title: 'Rish shell, review findings, against a real mksh with toybox applets (Android\'s shell)', tests: ['RishReviewTest'], main: 'RishReviewTest', needs: ['rish'] },
  { name: 'signer', title: 'In-app APK signer: v2 signatures checked with apksigner, RSA and EC keys, re-sign, edited and big APKs, tampering', tests: ['SignerTest'], main: 'SignerTest', needs: ['apk', 'apksigner', 'keys'] },
  { name: 'signmismatch', title: 'APK signer refuses a key that does not match its certificate', tests: ['SignMismatchTest'], main: 'SignMismatchTest', needs: ['apk', 'keys'] },
  { name: 'axml', title: 'Manifest decoder reads every compiled XML file of a real APK', tests: ['AxmlTest'], main: 'AxmlTest', needs: ['apk', 'stubs'] },
];

// ---------- prerequisites ----------
function which(cmd) {
  const dirs = (process.env.PATH || '').split(path.delimiter);
  for (const d of dirs) { const p = path.join(d, cmd); try { fs.accessSync(p, fs.constants.X_OK); if (fs.statSync(p).isFile()) return p; } catch (e) { /* next */ } }
  return null;
}
function run(cmd, args, opts) { return spawnSync(cmd, args, Object.assign({ encoding: 'utf8' }, opts)); }

function newestFile(dir, re) {
  let best = null;
  const walk = d => { let es; try { es = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; } for (const e of es) { const p = path.join(d, e.name); if (e.isDirectory()) walk(p); else if (re.test(e.name) && (!best || p > best)) best = p; } };
  walk(dir);
  return best;
}

const NEEDS = {
  pty: () => ({ props: { ptyexec: path.join(REPO, 'native', 'pty', 'x86_64', 'ptyexec') } }),
  node: () => ({}),
  json: () => {
    const m2 = path.join(os.homedir(), '.m2', 'repository', 'org');
    const cands = [process.env.ORG_JSON_JAR, path.join(CACHE, 'org-json.jar'), newestFile(path.join(m2, 'json'), /^json-.*\.jar$/), process.env.ANDROID_ALL_JAR, newestFile(path.join(m2, 'robolectric', 'android-all'), /^android-all-.*\.jar$/)].filter(Boolean);
    const jar = cands.find(p => fs.existsSync(p));
    return jar ? { cp: [jar] } : { skip: 'needs org.json: set ORG_JSON_JAR, or curl -L -o tests/.cache/org-json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar' };
  },
  androidall: () => {
    const cands = [process.env.ANDROID_ALL_JAR, newestFile(path.join(os.homedir(), '.m2', 'repository', 'org', 'robolectric', 'android-all'), /^android-all-.*\.jar$/)].filter(Boolean);
    const jar = cands.find(p => fs.existsSync(p));
    return jar ? { cp: [jar] } : { skip: 'needs Android\'s real framework classes (android.util.JsonReader): set ANDROID_ALL_JAR to a Robolectric android-all jar' };
  },
  stubs: () => ({ sp: [path.join(HERE, 'stubs')] }),
  fixtures: () => ({ props: { fixtures: path.join(HERE, 'fixtures') } }),
  zipfix1: () => zipFixtures('zipfix1', 'mkzip1.py', 'notzip.bin'),
  zipfix2: () => zipFixtures('zipfix2', 'mkzip2.py', 'big_uncompressed.zip'),
  apk: optional => {
    const cands = [process.env.TEST_APK, path.join(REPO, 'bin', 'ADB_Application_Manager_Pro.apk')].filter(Boolean);
    const apk = cands.find(p => fs.existsSync(p));
    if (apk) return { props: { testapk: apk } };
    return optional ? {} : { skip: 'needs an APK: run ./build.sh, or set TEST_APK=/path/app.apk' };
  },
  apksigner: () => {
    let p = process.env.APKSIGNER && fs.existsSync(process.env.APKSIGNER) ? process.env.APKSIGNER : which('apksigner');
    if (!p) for (const home of [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT].filter(Boolean)) p = p || newestFile(path.join(home, 'build-tools'), /^apksigner$/);
    return p ? { props: { apksigner: p } } : { skip: 'needs apksigner (Android build-tools): set APKSIGNER or ANDROID_HOME' };
  },
  keys: () => {
    if (!which('keytool')) return { skip: 'needs keytool (part of the JDK)' };
    const dir = path.join(OUT, 'keys');
    fs.mkdirSync(dir, { recursive: true });
    const mk = (file, args) => {
      if (fs.existsSync(path.join(dir, file))) return null;
      const r = run('keytool', ['-genkeypair', '-storetype', 'PKCS12', '-keystore', path.join(dir, file), '-storepass', 'password', '-keypass', 'password', '-validity', '3650'].concat(args));
      return r.status === 0 ? null : 'keytool failed: ' + (r.stderr || r.stdout).slice(0, 200);
    };
    const err = mk('rsa.p12', ['-alias', 'rsa', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=test rsa']) || mk('ec.p12', ['-alias', 'ec', '-keyalg', 'EC', '-groupname', 'secp256r1', '-dname', 'CN=test ec']);
    return err ? { skip: err } : { props: { keys: dir } };
  },
  bash: () => (which('bash') ? {} : { skip: 'needs bash' }),
  // The Morphe engine built on this computer (engine/build-engine.sh leaves engine/build/engine-libs) and the official patch bundle (downloaded once into tests/.cache)
  engine: () => {
    const libs = path.join(REPO, 'engine', 'build', 'engine-libs');
    return fs.existsSync(libs) ? { props: { enginelibs: libs } } : { skip: 'needs the Morphe engine: run engine/build-engine.sh (Gradle, JDK, d8)' };
  },
  mpp: () => {
    const f = path.join(CACHE, 'official.mpp');
    if (!fs.existsSync(f)) {
      fs.mkdirSync(CACHE, { recursive: true });
      const j = run('curl', ['-sSL', '--max-time', '60', 'https://raw.githubusercontent.com/MorpheApp/morphe-patches/main/patches-bundle.json']);
      let url = '';
      try { url = JSON.parse(j.stdout).download_url; } catch (e) {}
      if (!url) return { skip: 'needs the official patch bundle (could not read its address from GitHub)' };
      const d = run('curl', ['-sSL', '--max-time', '300', '-o', f + '.part', url]);
      if (d.status !== 0 || !fs.existsSync(f + '.part') || fs.statSync(f + '.part').size < 1000000) return { skip: 'needs the official patch bundle (download failed)' };
      fs.renameSync(f + '.part', f);
    }
    return { props: { mpp: f } };
  },
  rish: () => {
    const mksh = which('mksh'), toybox = which('toybox');
    if (!mksh || !toybox) return { skip: 'needs mksh and toybox (apt install mksh toybox)' };
    const env = path.join(OUT, 'rishenv');
    fs.rmSync(env, { recursive: true, force: true });
    fs.mkdirSync(path.join(env, 'bin'), { recursive: true });
    fs.mkdirSync(path.join(env, 'tb'), { recursive: true });
    fs.symlinkSync(mksh, path.join(env, 'bin', 'mksh'));
    fs.symlinkSync(toybox, path.join(env, 'bin', 'toybox'));
    for (const a of ['cat', 'env', 'getprop', 'grep', 'head', 'id', 'kill', 'nohup', 'pgrep', 'pkill', 'printf', 'ps', 'seq', 'setsid', 'sleep', 'tail', 'tee', 'timeout', 'yes']) fs.symlinkSync(path.join(env, 'bin', 'toybox'), path.join(env, 'tb', a));
    return { props: { rishenv: env } };
  },
};

function zipFixtures(dirName, script, sentinel) {
  if (!which('python3')) return { skip: 'needs python3' };
  if (script === 'mkzip1.py' && !which('zip')) return { skip: 'needs the zip command (an encrypted test archive is made with it)' };
  const dir = path.join(OUT, dirName);
  if (!fs.existsSync(path.join(dir, sentinel))) {
    fs.mkdirSync(dir, { recursive: true });
    const r = run('python3', [path.join(HERE, 'zip', script), dir]);
    if (r.status !== 0) return { skip: 'could not make the test archives: ' + (r.stderr || r.stdout).slice(0, 300) };
  }
  const props = {}; props[dirName] = dir;
  if (dirName === 'zipfix2') props.zipdump = path.join(HERE, 'zip', 'zipdump.py');
  return { props };
}

function resolveNeeds(suite) {
  const out = { props: {}, cp: [], sp: [] };
  for (const n of suite.needs || []) {
    const optional = n.endsWith('?'), id = n.replace('?', '');
    const r = NEEDS[id](optional);
    if (r.skip) return { skip: r.skip };
    Object.assign(out.props, r.props || {});
    out.cp.push(...(r.cp || []));
    out.sp.push(...(r.sp || []));
  }
  return out;
}

// ---------- running ----------
function exec(cmd, args, opts, timeoutS) {
  return new Promise(resolve => {
    const child = spawn(cmd, args, opts);
    let out = '', timedOut = false;
    child.stdout.on('data', d => { out += d; });
    child.stderr.on('data', d => { out += d; });
    const timer = setTimeout(() => { timedOut = true; child.kill('SIGKILL'); }, timeoutS * 1000);
    child.on('error', e => { clearTimeout(timer); resolve({ code: 127, out: out + String(e), timedOut }); });
    child.on('close', code => { clearTimeout(timer); resolve({ code, out, timedOut }); });
  });
}
const clean = text => text.split('\n').filter(l => !l.startsWith('Picked up ')).join('\n');

async function runSuite(suite, timeoutS) {
  const started = Date.now();
  const dir = path.join(OUT, suite.name), classes = path.join(dir, 'classes'), work = path.join(dir, 'run');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(classes, { recursive: true });
  fs.mkdirSync(work, { recursive: true });
  const need = resolveNeeds(suite);
  if (need.skip) return { name: suite.name, status: 'SKIP', why: [need.skip], seconds: 0 };

  const files = suite.kind === 'parity' ? [path.join(TSRC, 'ParityDump2.java')] : suite.tests.map(t => path.join(TSRC, t + '.java'));
  const cpSep = path.delimiter;
  const libs = fs.readdirSync(path.join(REPO, 'libs')).filter(f => f.endsWith('.jar')).map(f => path.join(REPO, 'libs', f));
  need.cp = Array.from(new Set(need.cp.concat(libs)));
  const cp = need.cp.join(cpSep);
  const javac = await exec('javac', ['-encoding', 'UTF-8', '-nowarn', '-sourcepath', [JSRC].concat(need.sp).join(cpSep), '-d', classes].concat(cp ? ['-cp', cp] : [], files), { cwd: work }, timeoutS);
  fs.writeFileSync(path.join(dir, 'compile.txt'), javac.out);
  if (javac.code !== 0) return { name: suite.name, status: 'FAIL', why: ['does not compile', clean(javac.out).split('\n').filter(Boolean).slice(0, 6).join('\n          ')], seconds: (Date.now() - started) / 1000 };

  const props = Object.entries(need.props).map(([k, v]) => '-D' + k + '=' + v);
  const env = Object.assign({}, process.env, suite.utf8 ? { LC_ALL: 'C.UTF-8', LANG: 'C.UTF-8' } : {});
  let res;
  if (suite.kind === 'parity') {
    const penv = Object.assign({}, env, { LC_ALL: 'C.UTF-8', PARITY_HTML: path.join(REPO, 'assets', 'index.html'), PARITY_CLASSES: classes, PARITY_CASES: path.join(HERE, 'parity', 'parity_cases.json') });
    res = await exec(process.execPath, [path.join(HERE, 'parity', 'parity_all.js')], { cwd: work, env: penv }, timeoutS);
  } else {
    const jvm = suite.utf8 ? ['-Dfile.encoding=UTF-8', '-Dsun.jnu.encoding=UTF-8'] : [];
    res = await exec('java', jvm.concat(props, ['-cp', [classes].concat(need.cp).join(cpSep), suite.main]), { cwd: work, env }, timeoutS);
  }
  const text = clean(res.out);
  fs.writeFileSync(path.join(dir, 'output.txt'), text);
  const why = [];
  if (res.timedOut) why.push('timed out');
  else if (res.code !== 0) why.push('exit code ' + res.code);
  const fails = text.split('\n').filter(l => /^FAIL\b/.test(l) || /^DIFF\b/.test(l));
  if (fails.length) why.push(fails.length + ' failing check' + (fails.length > 1 ? 's' : '') + ': ' + fails.slice(0, 3).map(l => l.slice(0, 140)).join(' | '));
  const last = text.trim().split('\n').pop() || '';
  return { name: suite.name, status: why.length ? 'FAIL' : 'PASS', why, summary: last.slice(0, 90), seconds: (Date.now() - started) / 1000 };
}

async function main() {
  const args = process.argv.slice(2);
  let jobs = 1, timeoutS = 900, strict = false, list = false;
  const names = [];
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === '--jobs') jobs = Math.max(1, +args[++i] || 1);
    else if (a === '--timeout') timeoutS = +args[++i] || 900;
    else if (a === '--strict') strict = true;
    else if (a === '--list') list = true;
    else if (a === '-h' || a === '--help') { console.log(fs.readFileSync(__filename, 'utf8').split('\n').filter(l => l.startsWith('//')).map(l => l.slice(3)).join('\n')); return 0; }
    else names.push(a);
  }
  const unknown = names.filter(n => !SUITES.some(s => s.name === n));
  if (unknown.length) { console.error('unknown suite: ' + unknown.join(', ')); return 2; }
  const selected = names.length ? SUITES.filter(s => names.includes(s.name)) : SUITES;
  if (list) { for (const s of selected) console.log(s.name.padEnd(13), s.title + ((s.needs || []).length ? '   [needs: ' + s.needs.join(', ') + ']' : '')); return 0; }
  if (!which('javac') || !which('java')) { console.error('A JDK (javac and java) is needed.'); return 2; }

  fs.mkdirSync(OUT, { recursive: true });
  const started = Date.now();
  const results = [];
  let next = 0;
  const worker = async () => {
    while (next < selected.length) {
      const s = selected[next++];
      const r = await runSuite(s, timeoutS);
      results.push(r);
      console.log(`${String(results.length).padStart(2)}/${selected.length} ${r.name.padEnd(13)} ${(r.status === 'PASS' ? 'PASS' : r.status + '  <<<<').padEnd(10)} ${r.seconds.toFixed(0).padStart(3)}s  ${r.status === 'PASS' ? (r.summary || '') : s.title.slice(0, 80)}`);
      if (r.status !== 'PASS') for (const w of r.why) console.log('          ' + w);
    }
  };
  await Promise.all(Array.from({ length: Math.min(jobs, selected.length) }, worker));

  const bad = results.filter(r => r.status === 'FAIL' || (strict && r.status === 'SKIP'));
  const skipped = results.filter(r => r.status === 'SKIP').length;
  console.log('');
  console.log(bad.length ? `${bad.length} of ${results.length} suites need a look: ${bad.map(r => r.name).join(', ')}`
    : `All ${results.length - skipped} suites passed${skipped ? ', ' + skipped + ' skipped (a prerequisite is missing)' : ''} (${((Date.now() - started) / 1000).toFixed(0)}s).`);
  return bad.length ? 1 : 0;
}

main().then(code => process.exit(code), e => { console.error(e); process.exit(2); });
