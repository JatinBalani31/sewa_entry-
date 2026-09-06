import test, { beforeEach } from 'node:test';
import assert from 'node:assert/strict';

// The queue is browser code, so give it the two globals it touches. Keeping
// the test here means `npm test` covers it without pulling a browser test
// runner into the project for one module.
const store = new Map();
globalThis.localStorage = {
  getItem: (key) => (store.has(key) ? store.get(key) : null),
  setItem: (key, value) => store.set(key, String(value)),
  removeItem: (key) => store.delete(key),
};
globalThis.crypto ??= (await import('node:crypto')).webcrypto;

const { offlineQueue } = await import('../../web/src/lib/offlineQueue.js');

const entry = (sewadarId, overrides = {}) => ({
  sewadarId,
  pin: '5837',
  action: 'IN',
  sewaDate: '2026-08-30',
  ...overrides,
});

/** Mimics the ApiError shape the queue branches on. */
const httpError = (status, message) => Object.assign(new Error(message), { status });

beforeEach(() => {
  store.clear();
});

test('a parked entry survives and reports the queue length', () => {
  assert.equal(offlineQueue.length, 0);
  offlineQueue.add(entry('SW1001'));
  offlineQueue.add(entry('SW2001'));
  assert.equal(offlineQueue.length, 2);

  const [first] = offlineQueue.list();
  assert.equal(first.sewadarId, 'SW1001');
  assert.ok(first.id, 'each entry needs an id so it can be removed individually');
  assert.ok(first.queuedAt, 'the moment it was parked is recorded');
});

test('flushing sends every entry in the order it was taken, then empties', async () => {
  offlineQueue.add(entry('SW1001'));
  offlineQueue.add(entry('SW2001'));
  offlineQueue.add(entry('SW3001'));

  const sent = [];
  const result = await offlineQueue.flush(async (body) => sent.push(body.sewadar_id));

  assert.deepEqual(sent, ['SW1001', 'SW2001', 'SW3001']);
  assert.equal(result.synced, 3);
  assert.equal(result.remaining, 0);
  assert.equal(offlineQueue.length, 0);
});

test('the sewa date rides along, so a queue draining next morning lands on the right day', async () => {
  offlineQueue.add(entry('SW1001', { sewaDate: '2026-08-30', action: 'OUT' }));

  let received;
  await offlineQueue.flush(async (body) => { received = body; });

  assert.equal(received.sewa_date, '2026-08-30');
  assert.equal(received.action, 'OUT');
  assert.equal(received.pin, '5837', 'the server, not the tablet, verifies the PIN');
});

test('a signature is carried through the queue', async () => {
  offlineQueue.add(entry('SW1001', { signature: 'data:image/png;base64,AAAA' }));

  let received;
  await offlineQueue.flush(async (body) => { received = body; });

  assert.equal(received.signature, 'data:image/png;base64,AAAA');
});

test('a network failure keeps the entry for the next attempt', async () => {
  offlineQueue.add(entry('SW1001'));

  const result = await offlineQueue.flush(async () => { throw httpError(0, 'Cannot reach the server.'); });

  assert.equal(result.synced, 0);
  assert.equal(result.remaining, 1);
  assert.equal(offlineQueue.length, 1, 'an entry must never be lost to a transport failure');
});

test('a server error keeps the entry too — it may succeed later', async () => {
  offlineQueue.add(entry('SW1001'));

  await offlineQueue.flush(async () => { throw httpError(503, 'Service unavailable.'); });

  assert.equal(offlineQueue.length, 1);
});

test('a rejection the server will never accept drops the entry instead of wedging the queue', async () => {
  offlineQueue.add(entry('SW1001', { pin: '0000' }));

  const result = await offlineQueue.flush(async () => { throw httpError(401, 'Invalid credentials.'); });

  assert.equal(result.synced, 0);
  assert.equal(result.rejected.length, 1);
  assert.equal(result.rejected[0].reason, 'Invalid credentials.');
  assert.equal(offlineQueue.length, 0, 'retrying a wrong PIN forever would block everyone behind it');
});

test('one bad entry does not stop the good ones behind it', async () => {
  offlineQueue.add(entry('SW1001', { pin: '0000' }));
  offlineQueue.add(entry('SW2001'));
  offlineQueue.add(entry('SW3001'));

  const result = await offlineQueue.flush(async (body) => {
    if (body.pin === '0000') throw httpError(401, 'Invalid credentials.');
  });

  assert.equal(result.synced, 2);
  assert.equal(result.rejected.length, 1);
  assert.equal(offlineQueue.length, 0);
});

test('a transport failure mid-queue keeps only what did not go through', async () => {
  offlineQueue.add(entry('SW1001'));
  offlineQueue.add(entry('SW2001'));

  await offlineQueue.flush(async (body) => {
    if (body.sewadar_id === 'SW2001') throw httpError(0, 'Cannot reach the server.');
  });

  assert.equal(offlineQueue.length, 1);
  assert.equal(offlineQueue.list()[0].sewadarId, 'SW2001');
});

test('flushing an empty queue is a no-op', async () => {
  let called = false;
  const result = await offlineQueue.flush(async () => { called = true; });

  assert.equal(called, false);
  assert.deepEqual(result, { synced: 0, rejected: [], remaining: 0 });
});

test('a corrupt storage value is treated as an empty queue rather than throwing', () => {
  store.set('sewa.counterQueue', 'this is not json');
  assert.deepEqual(offlineQueue.list(), []);
  assert.equal(offlineQueue.length, 0);

  // And it recovers: a new entry still parks correctly.
  offlineQueue.add(entry('SW1001'));
  assert.equal(offlineQueue.length, 1);
});

test('clear empties the queue', () => {
  offlineQueue.add(entry('SW1001'));
  offlineQueue.clear();
  assert.equal(offlineQueue.length, 0);
});
