// Shared setup of the UI scripts.
//
//   const { chromium, PAGE, OUT, REPO, fixture } = require('./lib/pw');
//
// - chromium: Playwright's, found through node_modules (cd tests && npm install) or NODE_PATH. Pass CHROMIUM_PATH to use a browser you
//   already have.
// - A fixed clock, locale and time zone for every page (and for this Node process): the scripts print dates, ages and times, and those
//   must not depend on the day they run, the machine's time zone or its language. The clock still runs, but it starts at TEST_NOW
//   (default 2026-10-03T04:00:00Z) when the script starts, in Node and in every page alike (so a time made in one is right in the other).
// - PAGE is the page under test (assets/index.html of this checkout, or PAGE_URL), OUT the folder for screenshots and other files
//   (tests/out/<script>, or TEST_OUT); relative paths in a script land there too, because the working directory is changed to it.
'use strict';
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const TESTS = path.resolve(__dirname, '..', '..');
const REPO = path.resolve(TESTS, '..');
const PAGE = process.env.PAGE_URL || pathToFileURL(path.join(REPO, 'assets', 'index.html')).href;
const FIXED_NOW = Date.parse(process.env.TEST_NOW || '2026-10-03T04:00:00Z');

let playwright;
try { playwright = require('playwright'); } catch (e) {
  console.error('Playwright is not installed. Run:  cd tests && npm install && npx playwright install chromium\n(or put an existing install on NODE_PATH)');
  process.exit(2);
}

// Date shifted by `offset` ms and then running with the real clock. Used in this process and, as text, in every page.
function installClock(offset) {
  const RealDate = Date;
  function FixedDate(...a) {
    if (!new.target) return new RealDate(RealDate.now() + offset).toString();
    return a.length ? new RealDate(...a) : new RealDate(RealDate.now() + offset);
  }
  FixedDate.prototype = RealDate.prototype;
  FixedDate.now = () => RealDate.now() + offset;
  FixedDate.parse = RealDate.parse.bind(RealDate);
  FixedDate.UTC = RealDate.UTC.bind(RealDate);
  Object.setPrototypeOf(FixedDate, RealDate);
  globalThis.Date = FixedDate;
}
const CLOCK_OFFSET = FIXED_NOW - Date.now();
installClock(CLOCK_OFFSET);
const PAGE_CLOCK = '(' + installClock.toString() + ')(' + CLOCK_OFFSET + ');';

const name = path.basename((require.main && require.main.filename) || 'script', '.js');
const OUT = process.env.TEST_OUT || path.join(TESTS, 'out', name);
fs.mkdirSync(OUT, { recursive: true });
process.chdir(OUT);

const chromium = {
  async launch(opts = {}) {
    const o = Object.assign({}, opts);
    if (process.env.CHROMIUM_PATH && !o.executablePath) o.executablePath = process.env.CHROMIUM_PATH;
    const browser = await playwright.chromium.launch(o);
    const newContext = browser.newContext.bind(browser);
    browser.newContext = async (co = {}) => {
      const ctx = await newContext(Object.assign({ locale: 'en-US', timezoneId: 'UTC' }, co));
      await ctx.addInitScript(PAGE_CLOCK);
      return ctx;
    };
    browser.newPage = async (po = {}) => {          // like Playwright's: a page in a context of its own, closed with it
      const ctx = await browser.newContext(po);
      const page = await ctx.newPage();
      const close = page.close.bind(page);
      page.close = async (...a) => { try { return await close(...a); } finally { await ctx.close().catch(() => {}); } };
      return page;
    };
    return browser;
  },
};

const fixture = file => path.join(TESTS, 'ui', 'fixtures', file);

module.exports = { chromium, PAGE, OUT, REPO, TESTS, fixture };
