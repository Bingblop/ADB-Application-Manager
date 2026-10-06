# The in-app Help Guide

About tab, **Help Guide** button. A complete user guide for someone who has never used ADB, Shizuku or root: a table of contents, a search box,
every tab and button explained, step-by-step recipes, safety advice, troubleshooting, a glossary.

The guide is written here as plain HTML fragments (`docs/guide/NN-name.html`) and built into `assets/guide.js`, which the page loads the first
time the button is pressed (so it costs nothing at start-up):

```sh
node docs/guide/build.js          # checks the fragments and writes assets/guide.js
node docs/guide/build.js --check  # only checks (ids, links, empty sections); writes nothing
```

`assets/guide.js` is committed (the APK build does not run Node). Edit the fragments, run the build, commit both.

## Who reads it

Someone who is **a total beginner**: they may not know what an APK, a package name, ADB, "root", "freezing" or "debloating" is, they may be nervous about
breaking their phone, and they are reading on a phone screen. After reading a section they should be able to do the thing, and know what will happen.

* Second person, short sentences, plain words. No joking, no hype.
* The first time a technical word appears in a section, explain it in a few words in brackets, or link to its entry in the glossary (`<a href="#glossary">`).
* Name things exactly as the screen does: the label of a button or tab in **bold** (`<b>Set Up</b>`), a command or a path in `<code>`.
* Say what happens after each step ("A sheet slides up from the bottom", "The row turns blue"), so the reader can tell they are on track.
* Say what the thing needs (a working mode? Android 11+? a computer?) before the steps, not after.
* Say what can go wrong and how to undo it. Never leave a risky action without its safety note and its undo.
* Be honest. Where something is unfinished, or has not been tried on real hardware, or depends on the phone's make, say so in one plain sentence.
* **Do not invent.** Every button label, menu entry, setting name, default and limit you write must be checked against the page (`assets/index.html`) or the
  Java (`src/com/bloatware/bingblop/`). If you cannot confirm it, leave it out. `docs/FULL-GUIDE.md`, `README.md` and `CHANGELOG.md` are useful but may
  be out of date: the code wins.
* No emoji. No backticks anywhere (they are not needed: use `<code>`). Straight quotes and ordinary dashes are fine; use `&amp;` `&lt;` `&gt;` for those characters.
* The app's interface is translated into many languages but this guide is English only; say button names the way the English interface spells them.

## File format

```html
<section id="tab-apps" data-title="Application Manager" data-group="The tabs">
  <p class="hg-lead">One or two sentences: what this is for.</p>
  <p><b>Where to find it:</b> the first tab.</p>
  <h4>Before you start</h4>
  <p>...</p>
  <h4>Step by step</h4>
  <ol class="hg-steps"><li>...</li></ol>
</section>
```

* One `<section>` per entry of the table of contents. `id` is the link target (lower-case, digits, hyphens); `data-title` is the heading shown in the table of
  contents and above the section (do not repeat it inside as an `<h3>`); `data-group` is the group of the table of contents (see below).
* Inside a section use `<h4>` and `<h5>` for sub-headings, `<p>`, `<ul>`, `<ol>`, `<li>`, `<b>`, `<i>`, `<code>`, `<br>`, `<a>`, `<table>`.
* `<ol class="hg-steps">` for numbered steps (drawn with number badges). `<p class="hg-lead">` for the opening sentence.
* Boxes: `<div class="hg-note"><b>Tip.</b> ...</div>` (helpful extra), `<div class="hg-warn"><b>Careful.</b> ...</div>` (can cause a problem; says how to avoid it),
  `<div class="hg-danger"><b>Risky.</b> ...</div>` (can make the phone stop working or lose data; says what to do first and how to recover).
* Tables: `<table class="hg-table"><thead><tr><th>..</th></tr></thead><tbody>...</tbody></table>`, at most 4 columns (the screen is narrow).
* Links to other sections: `<a href="#modes-overview">Working modes</a>`. The id must exist: the build fails on a broken link.
  Link generously to the glossary, the working modes and the safety sections, but do not link the same thing again and again in one paragraph.
* A fragment file holds several sections, written in the order they should appear.

## Groups (in this order) and the sections of each

| Group (`data-group`) | Sections (id: what it covers) |
|---|---|
| **Start here** | `welcome`, `quick-start`, `install-and-update` (which APK, how to update), `basics` (the words you will see), `screen-tour` (header, mode badge, tabs, Back) |
| **Connect the app to your phone** | `modes-overview`, `mode-wireless`, `mode-tcp`, `mode-shizuku`, `mode-root`, `mode-readonly` (Auto and Read-Only, the banner), `app-permissions` |
| **The tabs** (the order of the tab bar) | `tab-apps`, `apps-menu`, `apps-batch`, `apps-profiles`, `apps-backup`, `quick-tiles`, `tab-saved-lists`, `tab-debloater`, `debloater-safety`, `tab-installer`, `installer-splits`, `tab-files`, `files-search`, `files-archives`, `files-storage`, `tab-terminal`, `terminal-agents`, `tab-devices`, `devices-add`, `devices-apps`, `devices-send`, `devices-console`, `devices-logcat`, `devices-files`, `devices-settings`, `devices-display`, `devices-wear`, `tab-settings`, `settings-journal`, `tab-overlays`, `tab-updates`, `tab-store`, `tab-logcat`, `tab-taskmgr`, `tab-about` |
| **Settings and looks** | `prefs` (the gear: every setting), `feature-list` (turning tabs on and off, moving them), `themes` |
| **How do I...?** | `recipe-*`: short task recipes that point into the sections above |
| **Safety and help** | `safety`, `troubleshooting`, `faq`, `glossary`, `privacy`, `credits` |

A tab's own section is named `tab-<key>` where `<key>` is its key in `TAB_DEFS` in the page (`apps`, `saved-lists`, `debloater`, `installer`, `files`,
`terminal`, `devices`, `settings`, `overlays`, `updates`, `store`, `logcat`, `taskmgr`, `about`); a test fails when a tab has no section.
A tab with a lot in it also gets the extra sections named above (they follow the tab's own, under the same group).
