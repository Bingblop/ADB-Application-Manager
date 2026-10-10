// v7.8 Coding Agents, what a review found: "Always allow" belongs to one shell and one user (a grant in the sandbox does not let commands run
// in Working mode), a read through a link that leads out of the working folder is asked about, a file read while something else prints in the
// shell is refused rather than taken in corrupted, edit markers count only as whole lines, a file that is not UTF-8 text is not edited, a
// changed file keeps its permissions, an answer cut off after it began is an error and not a finished answer, and Cursor's key_ keys are
// hidden from what an agent sees.
const fs = require('fs');
const path = require('path');
const { chromium, PAGE } = require('./lib/pw');
const tx = require('./lib/tx_mock');
(async () => {
  const b = await chromium.launch();
  const page = await b.newPage({ viewport: { width: 400, height: 860 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const env = await tx.install(page, { real: true });
  await page.addInitScript(() => { window.__ai.vault = { claude: { key: true, hint: 'sk-ant-…aaaa', base: '', savedAt: 1790000000000, readable: true } }; });
  await page.goto(PAGE); await page.waitForTimeout(300);
  const sleep = ms => page.waitForTimeout(ms);
  const clean = s => String(s).split(env.base).join('<tmp>');
  const until = async (fn, ms, arg) => { const t0 = Date.now(); while (Date.now() - t0 < (ms || 5000)) { if (await page.evaluate(fn, arg)) return true; await sleep(25); } return false; };
  const idle = () => until(() => !txChat.busy && !txRuns.priv && !txRuns.app);
  const reply = (text, extra) => page.evaluate(([s, extra]) => window.__ai.queue.push(Object.assign({ match: sp => /\/v1\/messages$/.test(sp.url) }, extra || {}, { sse: s })), [tx.claudeSse(text), extra || null]);
  const lastReq = () => page.evaluate(() => { const r = window.__ai.reqs.filter(q => /\/v1\/messages$/.test(q.url)).pop(); return r && r.json; });
  const lastResult = async () => { const r = await lastReq(); return clean(r.messages[r.messages.length - 1].content); };
  const say = async t => { await page.fill('#txInput', t); await page.press('#txInput', 'Enter'); };
  const scr = async sh => clean(await page.locator('#txScreen-' + sh).innerText());
  const cards = sh => page.locator('#txScreen-' + sh + ' .tx-card');
  const asking = sh => until(s => !!document.querySelector('#txScreen-' + s + ' .tx-card .tx-btnrow'), 5000, sh);
  const press = (sh, label) => page.locator('#txScreen-' + sh + ' .tx-card button', { hasText: label }).last().click();
  const pick = async sh => { await page.evaluate(s => { const e = document.getElementById('txShell'); e.value = s; txPickShell(s); }, sh); await until(s => txSess[s].st === 'ready', 5000, sh); };
  const mode = rel => (fs.statSync(path.join(env.dirs.priv, rel)).mode & 0o777).toString(8);
  // a turn is over when the model was asked n more times and the agent is not busy (busy is set a moment after Enter)
  const nReq = () => page.evaluate(() => window.__ai.reqs.filter(q => /\/v1\/messages$/.test(q.url)).length);
  const turn = (k, n) => until(([k, n]) => window.__ai.reqs.filter(q => /\/v1\/messages$/.test(q.url)).length >= k + n && !txChat.busy && !txRuns.priv && !txRuns.app, 8000, [k, n]);

  await page.evaluate(() => window.__ai.queue.push({ match: sp => sp.method === 'GET' && /\/v1\/models/.test(sp.url), keep: true, body: JSON.stringify({ data: [{ id: 'claude-sonnet-5-5', display_name: 'Claude Sonnet 5.5' }] }) }));
  await page.evaluate(() => switchView('terminal')); await until(() => txSess.priv.st === 'ready');
  await page.selectOption('#txAgent', 'claude'); await sleep(100);
  console.log('0. Claude with a saved key, chatting:', await page.evaluate(() => txState.agent + ' ' + txState.mode));

  // ---------------------------------------------------------------- 1. Always allow is for one shell and one user
  await pick('app');
  await reply('First.\n<run>echo in-the-sandbox</run>');
  await reply('Second.\n<run>echo again-in-the-sandbox</run>');
  await reply('Done in the sandbox.');
  let k = await nReq();
  await say('try something'); await asking('app');
  await press('app', 'Always allow in this chat'); await turn(k, 3);
  const app1 = await scr('app');
  console.log('1. Always allow in This app (uid 10234): the next command there runs without asking:', (await cards('app').count()) === 1, app1.includes('again-in-the-sandbox\n'));
  console.log('   the grant is kept for that shell and user only:', JSON.stringify(await page.evaluate(() => txChat.allowRun)));
  await pick('priv');
  await reply('Now in Working mode.\n<run>id -u</run>');
  k = await nReq();
  await say('go on'); await asking('priv');
  console.log('   in Working mode (uid 2000) the same chat asks again:', (await cards('priv').count()) === 1, JSON.stringify(clean(await cards('priv').last().locator('.tx-card-head').innerText())));
  await press('priv', 'Skip'); await turn(k, 1);
  console.log('   skipped: nothing ran there:', !(await scr('priv')).includes('$ id -u'));

  // ---------------------------------------------------------------- 2. a link that leads out of the working folder
  fs.mkdirSync(path.join(env.base, 'outside'), { recursive: true });
  fs.writeFileSync(path.join(env.base, 'outside', 'secret.txt'), 'TOP SECRET\n');
  fs.symlinkSync(path.join(env.base, 'outside'), path.join(env.dirs.priv, 'storage'));
  env.write('priv', 'inside.txt', 'hello from inside\n');
  await reply('Reading the first.\n<read path="inside.txt"/>');
  await reply('And the other.\n<read path="storage/secret.txt"/>');
  k = await nReq();
  await say('read both'); await asking('priv');
  const r2 = await lastResult();
  console.log('2. a file in the folder is read without asking:', r2.includes('hello from inside'), (await cards('priv').count()) === 2);
  console.log('   one behind a link (storage -> a folder outside) is asked about, as Termux\'s ~/storage would be:', JSON.stringify(clean(await cards('priv').last().innerText()).split('\n').slice(0, 2)));
  await press('priv', 'Skip'); await turn(k, 2);
  console.log('   skipped: its text never reaches the model:', !(JSON.stringify(await lastReq())).includes('TOP SECRET'));

  // ---------------------------------------------------------------- 3. a file read while something else prints in the same shell
  env.write('priv', 'notes.txt', 'line one\nline two\n');
  await page.evaluate(() => {
    window.__realExec = txExec;
    window.txExec = async (sh, cmd, kind) => {
      const r = await window.__realExec(sh, cmd, kind);
      if (window.__inject && /base64 < "\$f"/.test(cmd)) r.output = String(r.output).replace(/(@@B64\w+ \d+\n)/, '$1' + window.__inject + '\n');
      return r;
    };
  });
  const edit1 = '<edit path="notes.txt">\n<<<<<<< SEARCH\nline two\n=======\nline 2\n>>>>>>> REPLACE\n</edit>';
  await page.evaluate(() => { window.__inject = 'hello'; });         // a job started with & prints "hello" in the middle of the file's bytes
  await reply('Editing.\n' + edit1);
  await reply('It could not be read.');
  k = await nReq();
  await say('change line two'); await turn(k, 2);
  const firstOfTurn = await page.evaluate(k => { const r = window.__ai.reqs.filter(q => /\/v1\/messages$/.test(q.url))[k].json; return r.messages[r.messages.length - 1].content; }, k);
  console.log('   (the skipped read went back as a denial with the next message:', /<result action="read" status="denied">/.test(firstOfTurn) + ')');
  console.log('3. output of a background job in the middle of the bytes: the read is refused, nothing is changed:', JSON.stringify(await lastResult()), env.read('priv', 'notes.txt') === 'line one\nline two\n');
  await page.evaluate(() => { window.__inject = '@@NONE and a log line with spaces'; });   // the old marker, without the nonce, and a line that is not base64
  await reply('Editing again.\n' + edit1);
  await reply('Changed.');
  k = await nReq();
  await say('try again'); await asking('priv');
  await press('priv', 'Apply'); await turn(k, 2);
  console.log('   a line that cannot be part of the bytes (and an old-style marker) is left out, the edit goes through:', env.read('priv', 'notes.txt') === 'line one\nline 2\n');
  await page.evaluate(() => { window.__inject = ''; window.txExec = window.__realExec; });

  // ---------------------------------------------------------------- 4. edit markers are whole lines
  env.write('priv', 'notes.md', 'Title\n=======\nb = 2  # =======\nend\n');
  await reply('Fixing.\n<edit path="notes.md">\n<<<<<<< SEARCH\nb = 2  # =======\n=======\nb = 3  # =======\n>>>>>>> REPLACE\n<<<<<<< SEARCH\nend\n=======\n>>>>>>> REPLACE\n</edit>');
  await reply('Fixed.');
  k = await nReq();
  await say('set b to 3 and drop the last line'); await asking('priv');
  await press('priv', 'Apply'); await turn(k, 2);
  console.log('4. lines that only contain ======= inside them, and a whole line taken out:', JSON.stringify(env.read('priv', 'notes.md')));

  // ---------------------------------------------------------------- 5. not UTF-8: no edit; a script keeps its permissions
  fs.writeFileSync(path.join(env.dirs.priv, 'latin1.sh'), Buffer.from('#!/bin/sh\necho caf\xe9\n', 'latin1'));
  await reply('Editing it.\n<edit path="latin1.sh">\n<<<<<<< SEARCH\n#!/bin/sh\n=======\n#!/bin/bash\n>>>>>>> REPLACE\n</edit>');
  await reply('It is not text I can edit.');
  k = await nReq();
  await say('use bash in latin1.sh'); await turn(k, 2);
  console.log('5. a Latin-1 file is not edited (it would change bytes nobody touched):', /not UTF-8/.test(await lastResult()), fs.readFileSync(path.join(env.dirs.priv, 'latin1.sh')).equals(Buffer.from('#!/bin/sh\necho caf\xe9\n', 'latin1')));
  env.write('priv', 'run.sh', '#!/bin/sh\necho one\n');
  fs.chmodSync(path.join(env.dirs.priv, 'run.sh'), 0o755);
  await reply('Changing it.\n<edit path="run.sh">\n<<<<<<< SEARCH\necho one\n=======\necho two\n>>>>>>> REPLACE\n</edit>');
  await reply('Done.');
  k = await nReq();
  await say('make run.sh say two'); await asking('priv');
  await press('priv', 'Apply'); await turn(k, 2);
  console.log('   a script edited keeps its permissions:', mode('run.sh'), JSON.stringify(env.read('priv', 'run.sh')));
  await page.evaluate(() => txUndo()); await until(() => !txRuns.priv); await sleep(200); await until(() => !txRuns.priv);
  console.log('   and after /undo too:', mode('run.sh'), JSON.stringify(env.read('priv', 'run.sh')));

  // ---------------------------------------------------------------- 6. an answer cut off after it began
  await reply('Here is the start of a long answer that', { error: 'The server took too long to answer' });
  k = await nReq();
  await say('explain it all'); await turn(k, 1);
  const t6 = (await scr('priv')).trim().split('\n');
  console.log('6. a stream cut after it began is an error, not a finished answer:', JSON.stringify(t6.filter(l => l.trim()).slice(-3)));
  console.log('   the half answer is not kept as the agent\'s word:', await page.evaluate(() => !txChat.msgs.some(m => m.role !== 'user' && /start of a long answer/.test(m.text))));

  // ---------------------------------------------------------------- 7. key_ keys are hidden from the agent too
  console.log('7. a Cursor key_ key in output is hidden, a word like key_id is not:', await page.evaluate(() => txRedact('CURSOR_API_KEY=key_' + 'ab12'.repeat(12) + ' key_id api_key_name')));

  console.log('page errors:', errors.length ? errors : 'none');
  env.close();
  await b.close();
})();
