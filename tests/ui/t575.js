// The privileged-feature gate across the resolved working mode and the Shizuku server's uid. Two tiers the page reads from the Java
// verdict: isRootActive (RootGate.unlocked, sysPrivActive) unlocks the Root Features surface and the component toggle for Root with su
// or Shizuku as the system user (uid 1000) or root (uid 0); ovlCanWriteOverlays (RootGate.uid0, rootUidActive) is the stricter uid-0
// tier for writing a fabricated colour overlay, so Shizuku as uid 1000 stays locked there. The ADB shell user (uid 2000) unlocks
// neither. Also the back-compat fallback for an older app that sends neither field (Root is the mode and rootWorkingModeActive is true).
const { chromium, PAGE } = require('./lib/pw');
(async () => {
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    window.AndroidBridge = {
      vibrate() {}, loadPreferences() { return '{}'; }, loadCustomLists() { return '[]'; }, getSystemInfo() { return '{}'; }, isSystemDarkMode() { return true; }, setSystemBarColor() {},
      loadPackages() { return JSON.stringify([{ pkg: 'com.x', name: 'X', isSystem: false }]); }, getWorkingMode() { return '{}'; }, getIconPacks() { return '[]'; }, loadAppIcons() { return 'started'; },
    };
  });
  await page.goto(PAGE); await page.waitForTimeout(500);

  // Each case sets the page globals (as the working-mode probe would) and reads the three gate helpers in one synchronous step.
  const cases = [
    { label: 'Shizuku uid 1000 (system user)', priv: true, cwm: 'shizuku',
      lmd: { activeMode: 'shizuku', isPrivileged: true, sysPrivActive: true, rootUidActive: false, shizuku: { authorized: true, uid: 1000 } },
      want: { root: true, compLocked: false, ovl: false } },
    { label: 'Shizuku uid 0 (root)', priv: true, cwm: 'shizuku',
      lmd: { activeMode: 'shizuku', isPrivileged: true, sysPrivActive: true, rootUidActive: true, shizuku: { authorized: true, uid: 0 } },
      want: { root: true, compLocked: false, ovl: true } },
    { label: 'Shizuku uid 2000 (ADB shell)', priv: false, cwm: 'shizuku',
      lmd: { activeMode: 'shizuku', isPrivileged: false, sysPrivActive: false, rootUidActive: false, shizuku: { authorized: true, uid: 2000 } },
      want: { root: false, compLocked: true, ovl: false } },
    { label: 'Root with su', priv: true, cwm: 'root',
      lmd: { activeMode: 'root', isPrivileged: true, sysPrivActive: true, rootUidActive: true },
      want: { root: true, compLocked: false, ovl: true } },
    { label: 'back-compat older app, Root (no new fields)', priv: true, cwm: 'root',
      lmd: { activeMode: 'root', isPrivileged: true, rootWorkingModeActive: true },
      want: { root: true, compLocked: false, ovl: true } },
    { label: 'back-compat older app, Shizuku (no new fields)', priv: true, cwm: 'shizuku',
      lmd: { activeMode: 'shizuku', isPrivileged: true },
      want: { root: false, compLocked: true, ovl: false } },
  ];

  for (const c of cases) {
    const got = await page.evaluate(a => {
      isPrivilegedActive = a.priv;
      currentWorkingMode = a.cwm;
      lastModesData = a.lmd;
      return { root: isRootActive(), compLocked: compToggleLocked(), ovl: ovlCanWriteOverlays() };
    }, c);
    console.log(`${c.label}: isRootActive=${got.root} compToggleLocked=${got.compLocked} ovlCanWriteOverlays=${got.ovl}`);
    for (const k of ['root', 'compLocked', 'ovl']) if (got[k] !== c.want[k]) console.log(`FAIL ${c.label} [${k}]: got ${got[k]} want ${c.want[k]}`);
  }

  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
