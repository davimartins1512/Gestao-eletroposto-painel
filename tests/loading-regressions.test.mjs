import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const panel = readFileSync(new URL('../painel.html', import.meta.url), 'utf8');
const charging = readFileSync(new URL('../carregar.html', import.meta.url), 'utf8');
const quiet = { log() {}, error() {} };
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };

function loader(from) {
  const start = panel.indexOf('const TABLE_PAGE_SIZE =');
  const end = panel.indexOf('async function ins(', start);
  let renders = 0;
  const alerts = [];
  const db = { drivers: [{ id: 'original' }], charges: [{ id: 'original' }] };
  const ctx = vm.createContext({
    console: quiet, supa: { from }, db, setSync() {}, render() { renders++; },
    loadOcppCustomers: async () => [], updatePricePerKwhDisplay: async () => {}, loadWhatsappStatus: async () => {}, alert: message => alerts.push(message)
  });
  vm.runInContext(panel.slice(start, end) + '\nthis.load = loadAll;', ctx);
  return { ctx, db, alerts, renders: () => renders };
}

function query(range) {
  return { select() { return this; }, order(key) { assert.equal(key, 'id'); return this; }, range };
}

test('independent tables load concurrently and datasets beyond 1000 rows are complete', async () => {
  const pending = deferred();
  const calls = [];
  const data = Array.from({ length: 1205 }, (_, i) => ({ id: String(i).padStart(4, '0'), amount: 1 }));
  const l = loader(table => query(async (start, end) => {
    calls.push({ table, start, end });
    await pending.promise;
    return { data: table === 'charges' ? data.slice(start, end + 1) : [] };
  }));
  const loading = l.ctx.load();
  assert.equal(calls.length, 10, 'all ten table reads must begin before the first completes');
  assert.equal(l.db.charges[0].id, 'original', 'the previous snapshot remains until reads complete');
  pending.resolve();
  await loading;
  assert.equal(l.db.charges.length, 1205);
  assert.equal(l.db.charges.reduce((sum, row) => sum + row.amount, 0), 1205);
  assert.equal(new Set(l.db.charges.map(row => row.id)).size, 1205);
  assert.equal(l.renders(), 1);
});

test('a failed table read preserves the previous financial snapshot and allows retry', async () => {
  let fails = true;
  const l = loader(table => query(async () => table === 'charges' && fails
    ? { error: { message: 'temporary failure' } } : { data: [{ id: 'new' }] }));
  await l.ctx.load();
  assert.equal(l.db.drivers[0].id, 'original');
  assert.equal(l.db.charges[0].id, 'original');
  assert.equal(l.renders(), 0);
  assert.equal(l.alerts.length, 1);
  fails = false;
  await l.ctx.load();
  assert.equal(l.db.charges[0].id, 'new');
  assert.equal(l.renders(), 1);
});

test('a save during loading queues a fresh snapshot without starting concurrent loaders', async () => {
  const pending = deferred();
  let calls = 0;
  let version = 1;
  const l = loader(() => query(async () => {
    calls++;
    const readVersion = version;
    await pending.promise;
    return { data: [{ id: String(readVersion) }] };
  }));
  const first = l.ctx.load();
  version = 2;
  const second = l.ctx.load();
  assert.equal(first, second);
  assert.equal(calls, 10);
  pending.resolve();
  await second;
  assert.equal(calls, 20);
  assert.equal(l.db.charges[0].id, '2');
});

function polling(api) {
  const start = charging.indexOf('function startPolling() {');
  const end = charging.indexOf('// RENDER CARREGANDO', start);
  let renders = 0;
  const ctx = vm.createContext({
    console: quiet, api, POLL_INTERVAL_MS: 5000, encodeURIComponent,
    setInterval: () => 1, clearInterval() {}, showScreen() {}, clearActiveAuthorization() {}, showError() {},
    renderCharging() { renders++; }, renderFinished() { renders++; }
  });
  vm.runInContext('let authorizationId = "a1", pollTimer = null, sessionRefreshRunning = false, sessionPollGeneration = 0, lastSessionData = null;\n' +
    charging.slice(start, end) + '\nthis.refresh = refreshSession; this.stop = stopPolling; this.change = id => {authorizationId = id;};', ctx);
  return { ctx, renders: () => renders };
}

test('slow charging polls do not overlap and obsolete responses cannot change the screen', async () => {
  const pending = deferred();
  let calls = 0;
  const p = polling(async () => { calls++; return pending.promise; });
  const first = p.ctx.refresh();
  await p.ctx.refresh();
  assert.equal(calls, 1);
  p.ctx.stop();
  p.ctx.change('a2');
  pending.resolve({ response: { ok: true }, body: { success: true, phase: 'charging', session: {} } });
  await first;
  assert.equal(p.renders(), 0);
  await p.ctx.refresh();
  assert.equal(calls, 2, 'the guard must be released for the next authorization');
  assert.equal(p.renders(), 1);
});

test('every inline script remains valid JavaScript', () => {
  for (const [name, html] of [['painel', panel], ['carregar', charging]]) {
    for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) {
      if (match[1].trim()) assert.doesNotThrow(() => new vm.Script(match[1]), name);
    }
  }
});
