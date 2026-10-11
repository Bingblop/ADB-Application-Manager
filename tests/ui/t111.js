// The Help Guide (About tab): the button next to GitHub, the sheet with its table of contents (groups with counts, numbered topics), jumping to a topic, the Back to where I was
// pill after a link inside a topic, Back to the contents, the Contents button, the search (a ranked list of the topics that hold every word, with a snippet and the words marked; a tap opens one), closing with the
// X and with Back and opening again where the reader was; and, when assets/guide.js is built, the real guide: it loads on demand, has a topic for every tab, and its links work.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE, REPO } = require('./lib/pw');
(async () => {
  const b = await chromium.launch();
  let failed = 0;
  const check = (name, ok, extra) => { if (!ok) { failed++; console.log('FAIL ' + name + (extra ? ' ' + extra : '')); } else console.log('ok   ' + name); };
  const baseBridge = () => ({ vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
    loadPackages() { return '[]'; }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; } });

  // ---- the viewer, with a small guide of its own ----
  {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript((bridge) => {
      window.AndroidBridge = eval('(' + bridge + ')')();
      const filler = n => Array.from({ length: n }, (_, i) => '<p>Filler paragraph ' + (i + 1) + ' of words so that the topic is long enough to scroll through on a phone screen, with plain words only.</p>').join('');
      window.HELP_GUIDE = { version: '9.9.9', groups: ['Start here', 'The tabs'], sections: [
        { id: 'welcome', title: 'Welcome', group: 'Start here', html: '<p class="hg-lead">The lead. A zebra can freeze.</p>' + filler(10) + '<p>See <a href="#tab-apps">the Apps tab</a> for freezing.</p>' },
        { id: 'basics', title: 'Words you will see', group: 'Start here', html: '<p>Freeze means pause an app.</p><table class="hg-table"><thead><tr><th>Word</th><th>Meaning</th></tr></thead><tbody><tr><td>Freeze</td><td>Stops it</td></tr></tbody></table>' + filler(8) },
        { id: 'tab-apps', title: 'Application Manager', group: 'The tabs', html: '<ol class="hg-steps"><li>One</li><li>Two</li></ol><div class="hg-note"><b>Tip.</b> Freeze with care, like a zebra.</div>' + filler(14) + '<p><a href="#welcome">Back to the start</a></p>' },
        { id: 'tab-files', title: 'File Manager', group: 'The tabs', html: '<p>Files and folders, and zebra stripes.</p>' + filler(12) },
        { id: 'tab-about', title: 'About', group: 'The tabs', html: '<p>About the app, zebra stripes.</p>' + filler(6) },
      ] };
    }, baseBridge.toString());
    await page.goto(PAGE); await page.waitForTimeout(500);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    const wait = ms => page.waitForTimeout(ms);
    const view = () => ev(() => ({ toc: getComputedStyle(document.getElementById('hgToc')).display !== 'none', topic: getComputedStyle(document.getElementById('hgTopic')).display !== 'none' ? (document.querySelector('#hgTopic h3') || {}).innerText : null,
      results: getComputedStyle(document.getElementById('hgResults')).display !== 'none', contentsBtn: getComputedStyle(document.getElementById('hgContentsBtn')).display !== 'none', back: document.getElementById('hgBack').classList.contains('show'), top: Math.round(document.querySelector('#helpGuideModal .modal-sheet').scrollTop) }));

    await ev(() => switchView('about')); await wait(200);
    const btns = await ev(() => Array.from(document.querySelectorAll('#view-about .about-hero .mode-btn-row button')).map(b => b.innerText));
    check('the About tab has Issues and Contact first, then GitHub, Help Guide, What\'s new, Permissions and Check for update', btns.join('|') === 'Issues|Contact the developer|GitHub|Help Guide|What\'s new|Permissions|Check for update', btns.join('|'));
    await page.click('#aboutHelpBtn'); await wait(700);
    const open = await ev(() => ({ shown: document.getElementById('helpGuideModal').classList.contains('show'), groups: Array.from(document.querySelectorAll('#hgToc summary')).map(s => s.innerText.replace(/\s+/g, ' ').trim()),
      links: Array.from(document.querySelectorAll('#hgToc a')).map(a => a.innerText.replace(/\s+/g, ' ')), drawn: document.querySelectorAll('.hg-sec').length, intro: document.querySelector('.hg-intro').innerText }));
    check('it opens with the table of contents: groups with counts, numbered topics, and no topic drawn yet', open.shown && open.groups.join('|') === 'Start here 2 topics|The tabs 3 topics' && open.links.join('|') === '1Welcome|2Words you will see|3Application Manager|4File Manager|5About' && open.drawn === 0, JSON.stringify(open));
    check('the intro says how to use it, that it is English, and the version it was written for', /Contents/.test(open.intro) && /English/.test(open.intro) && /9\.9\.9/.test(open.intro));
    let v = await view();
    check('on the contents the Contents button is not needed', v.toc && !v.topic && !v.contentsBtn, JSON.stringify(v));
    await page.screenshot({ path: 'help_toc.png' });

    // open a topic from the contents
    await ev(() => document.querySelector('#hgToc a[data-hg="tab-files"]').click()); await wait(300);
    v = await view();
    const t1 = await ev(() => ({ group: document.querySelector('#hgTopic .hg-sec-group').innerText, prev: (document.querySelector('.hg-nav-prev') || {}).innerText, next: (document.querySelector('.hg-nav-next') || {}).innerText, back: document.getElementById('hgBack').classList.contains('show') }));
    check('a topic of the contents opens on its own, from the top, with Previous and Next', !v.toc && v.topic === 'File Manager' && v.contentsBtn && v.top === 0 && /topic 4 of 5/i.test(t1.group) && /Previous\s+Application Manager/i.test(t1.prev) && /Next\s+About/i.test(t1.next) && !t1.back, JSON.stringify([v, t1]));
    check('tables are wrapped to scroll sideways and steps and boxes are in the markup (a topic with them)', await ev(() => { openHelpGuide(); hgShow('basics'); const t = !!document.querySelector('#hgTopic .hg-tablewrap > table.hg-table'); hgShow('tab-apps'); return t && document.querySelectorAll('#hgTopic ol.hg-steps li').length === 2 && !!document.querySelector('#hgTopic .hg-note'); }));
    await ev(() => hgShow('tab-files')); await wait(100);
    await page.screenshot({ path: 'help_topic.png' });
    // Next and Previous
    await ev(() => document.querySelector('.hg-nav-next').click()); await wait(200);
    v = await view();
    check('Next opens the next topic, from the top, with no way back offered (it is not a jump)', v.topic === 'About' && v.top === 0 && !v.back, JSON.stringify(v));
    await ev(() => document.querySelector('.hg-nav-prev').click()); await wait(200);
    check('Previous goes back one', (await view()).topic === 'File Manager');
    // a link inside a topic
    await ev(() => hgShow('welcome')); await wait(150);
    await ev(() => { document.querySelector('#hgTopic a[href="#tab-apps"]').scrollIntoView({ block: 'center' }); }); await wait(250);
    const before = await ev(() => document.querySelector('#helpGuideModal .modal-sheet').scrollTop);
    await ev(() => document.querySelector('#hgTopic a[href="#tab-apps"]').click()); await wait(300);
    v = await view();
    check('a link inside a topic opens that topic and offers Back to where I was', v.topic === 'Application Manager' && v.back && v.top === 0 && before > 100, JSON.stringify([v, before]));
    await page.click('#hgBack'); await wait(300);
    v = await view();
    check('Back to where I was returns to that topic at the place of the link and hides itself', v.topic === 'Welcome' && Math.abs(v.top - before) < 8 && !v.back, JSON.stringify([v, before]));
    // the contents again
    await ev(() => document.querySelector('#hgTopic .hg-top').click()); await wait(300);
    v = await view();
    check('Back to the contents under a topic shows the contents again', v.toc && !v.topic && !v.contentsBtn, JSON.stringify(v));
    check('the topic that was open is lit in the contents', await ev(() => !!document.querySelector('#hgToc a.hg-flash[data-hg="welcome"]')));
    await ev(() => hgShow('basics')); await wait(100);
    await page.click('#hgContentsBtn'); await wait(300);
    check('the Contents button goes to the contents from a topic', (await view()).toc);

    // search
    await page.fill('#hgSearch', 'zebra'); await wait(500);
    const s1 = await ev(() => ({ cards: Array.from(document.querySelectorAll('#hgResults .hg-hit-card')).map(c => c.dataset.hg), toc: getComputedStyle(document.getElementById('hgToc')).display, topic: getComputedStyle(document.getElementById('hgTopic')).display,
      marks: document.querySelectorAll('#hgResults mark.hg-hit').length, status: document.getElementById('hgStatus').innerText, first: document.querySelector('#hgResults .hg-hit-card').innerText.replace(/\s+/g, ' ') }));
    check('a search lists the topics that hold the word, each with a snippet and the word marked; the contents are put away', s1.cards.join() === 'welcome,tab-apps,tab-files,tab-about' && s1.toc === 'none' && s1.topic === 'none' && s1.marks === 4 && /4 topics match/.test(s1.status) && /Start here Welcome .*zebra/i.test(s1.first), JSON.stringify(s1));
    await page.fill('#hgSearch', 'freeze zebra'); await wait(500);
    const s2 = await ev(() => Array.from(document.querySelectorAll('#hgResults .hg-hit-card')).map(c => c.dataset.hg).sort().join());
    check('every word has to be in the topic', s2 === 'tab-apps,welcome', s2);
    await page.fill('#hgSearch', 'qqqqq'); await wait(500);
    check('a word that is nowhere says so', await ev(() => getComputedStyle(document.getElementById('hgNone')).display !== 'none' && getComputedStyle(document.getElementById('hgResults')).display === 'none' && document.getElementById('hgStatus').innerText === ''));
    await page.fill('#hgSearch', 'Words'); await wait(400);
    check('a word in a title ranks that topic first', await ev(() => document.querySelector('#hgResults .hg-hit-card').dataset.hg) === 'basics');
    await page.fill('#hgSearch', ''); await wait(500);
    v = await view();
    check('an empty box brings back the contents, with no results left', v.toc && !v.results && !v.topic);
    await page.fill('#hgSearch', 'zebra'); await wait(400);
    await page.click('#hgResults .hg-hit-card[data-hg="tab-files"]'); await wait(400);
    v = await view();
    check('a tap on a result opens that topic and clears the search', v.topic === 'File Manager' && !v.results && await ev(() => document.getElementById('hgSearch').value === ''), JSON.stringify(v));
    // search from inside a topic, then clear: the topic comes back
    await ev(() => { hgShow('welcome'); document.querySelector('#helpGuideModal .modal-sheet').scrollTo({ top: 150 }); }); await wait(200);
    const kept = (await view()).top;
    await page.fill('#hgSearch', 'zebra'); await wait(400); await page.fill('#hgSearch', ''); await wait(400);
    v = await view();
    check('clearing a search made inside a topic returns to that topic where it was', kept > 100 && v.topic === 'Welcome' && Math.abs(v.top - kept) < 8, JSON.stringify([v, kept]));

    // close and open again
    await ev(() => hgShow('tab-files'));
    await ev(() => document.querySelector('#helpGuideModal .modal-sheet').scrollTo({ top: 200 })); await wait(200);
    const at = await ev(() => document.querySelector('#helpGuideModal .modal-sheet').scrollTop);
    await page.click('#helpGuideModal .hg-x'); await wait(400);
    check('the X closes the guide', await ev(() => !document.getElementById('helpGuideModal').classList.contains('show')));
    await page.click('#aboutHelpBtn'); await wait(500);
    v = await view();
    check('opening it again continues with the same topic where the reader was', v.topic === 'File Manager' && Math.abs(v.top - at) < 4, JSON.stringify([v, at]));
    // Back steps back: search, topic, contents, close
    await page.fill('#hgSearch', 'zebra'); await wait(400);
    await ev(() => backNavigate()); await wait(300);
    v = await view();
    check('Back first leaves the search (to the topic that was open)', v.topic === 'File Manager' && !v.results && await ev(() => document.getElementById('helpGuideModal').classList.contains('show')), JSON.stringify(v));
    await ev(() => backNavigate()); await wait(300);
    v = await view();
    check('Back then leaves the topic for the contents', v.toc && !v.topic && await ev(() => document.getElementById('helpGuideModal').classList.contains('show')), JSON.stringify(v));
    check('Back from the contents closes the guide', await ev(() => { backNavigate(); return !document.getElementById('helpGuideModal').classList.contains('show'); }));
    await ev(() => openHelpGuide('tab-about')); await wait(500);
    check('openHelpGuide(id), the link for the rest of the app, opens straight at a topic', (await view()).topic === 'About');
    check('the guide is not translated or reordered by the language engine', await ev(() => document.getElementById('hgBody').getAttribute('translate') === 'no' && document.getElementById('hgBody').getAttribute('dir') === 'ltr'));
    await ev(() => closeHelpGuide());
    await ev(() => { openWorkingModesModal(); });
    await wait(200);
    await ev(() => document.querySelector('#modesModal .modal-helper a').click()); await wait(600);
    check('the Working Modes sheet links to the guide (the modes topic) and closes itself', await ev(() => !document.getElementById('modesModal').classList.contains('show') && document.getElementById('helpGuideModal').classList.contains('show')));
    check('the page had no errors (viewer)', errors.length === 0, JSON.stringify(errors));
    await page.close();
  }

  // ---- the real guide, when it has been built ----
  const guideFile = path.join(REPO, 'assets', 'guide.js');
  if (fs.existsSync(guideFile)) {
    const page = await b.newPage({ viewport: { width: 400, height: 860 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.addInitScript((bridge) => { window.AndroidBridge = eval('(' + bridge + ')')(); }, baseBridge.toString());
    await page.goto(PAGE); await page.waitForTimeout(500);
    const ev = (fn, arg) => page.evaluate(fn, arg);
    check('nothing of the guide is loaded before the button is pressed', await ev(() => !window.HELP_GUIDE && !document.querySelector('script[src="guide.js"]')));
    await ev(() => { switchView('about'); openHelpGuide(); }); await page.waitForTimeout(2500);
    const g = await ev(() => ({ n: window.HELP_GUIDE ? window.HELP_GUIDE.sections.length : 0, secs: document.querySelectorAll('.hg-sec').length, tocLinks: document.querySelectorAll('#hgToc a').length, groups: window.HELP_GUIDE ? window.HELP_GUIDE.groups : [],
      tabs: TAB_DEFS.map(t => t.key), ids: window.HELP_GUIDE ? window.HELP_GUIDE.sections.map(s => s.id) : [], words: window.HELP_GUIDE ? window.HELP_GUIDE.sections.reduce((n, s) => n + s.html.replace(/<[^>]+>/g, ' ').split(/\s+/).length, 0) : 0 }));
    check('pressing the button loads the guide and lists every topic in the contents', g.n > 40 && g.tocLinks === g.n, JSON.stringify({ n: g.n, toc: g.tocLinks }));
    const noTab = g.tabs.filter(k => !g.ids.includes('tab-' + k));
    check('every tab of the app has its own topic', noTab.length === 0, 'missing: ' + noTab.join());
    check('the groups are in the order of the plan', g.groups.join('|') === 'Start here|Connect the app to your phone|The tabs|Settings and looks|How do I...?|Safety and help', g.groups.join('|'));
    check('the guide is long enough to be complete (words)', g.words > 25000, String(g.words));
    const bad = await ev(() => { const ids = new Set(window.HELP_GUIDE.sections.map(s => s.id)); const out = []; window.HELP_GUIDE.sections.forEach(sec => { hgShow(sec.id); const t = document.querySelector('#hgTopic h3'); if (!t || t.innerText !== sec.title) out.push('no ' + sec.id); document.querySelectorAll('#hgTopic a[href^="#"]').forEach(a => { const id = a.getAttribute('href').slice(1); if (!ids.has(id) && !a.dataset.hg) out.push(sec.id + ' -> ' + id); }); }); return out; });
    check('every topic opens, and every link inside it goes to a topic that exists', bad.length === 0, bad.slice(0, 5).join(', '));
    const speed = await ev(() => { const t0 = performance.now(); hgShow('prefs'); hgShow('tab-apps'); hgShow('glossary'); return Math.round((performance.now() - t0) / 3); });
    check('a topic opens at once (about a tenth of a second or less, even the longest)', speed < 150, speed + ' ms');
    check('the page had no errors (real guide)', errors.length === 0, JSON.stringify(errors));
    await page.close();
  } else console.log('note: assets/guide.js is not built yet; the real-guide checks are skipped');

  console.log(failed ? failed + ' FAILED' : 'all checks passed');
  await b.close();
  process.exit(failed ? 1 : 0);
})();
