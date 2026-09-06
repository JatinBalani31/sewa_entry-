import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';

// node --test runs every test file in its own process, so setting the ceiling
// here throttles only this file — the rest of the suite keeps its high limit.
process.env.USE_MEMORY_DB = '1';
process.env.JWT_SECRET = 'test-secret';
process.env.LOGIN_RATE_LIMIT = '3';

const { createApp } = await import('../src/app.js');
const { migrate, closePool } = await import('../src/db/index.js');

let server;
let url;

before(async () => {
  await migrate();
  server = createApp().listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  url = `http://127.0.0.1:${server.address().port}/api/auth/login`;
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
  await closePool();
});

test('the per-IP login limiter refuses a burst of sign-in attempts', async () => {
  const statuses = [];
  for (let i = 0; i < 6; i++) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ identifier: `probe${i}@sewa.local`, secret: '999999' }),
    });
    statuses.push(res.status);
  }

  // The first three are let through (and fail on credentials); the rest are
  // turned away by the limiter before they ever reach the PIN check.
  assert.deepEqual(statuses.slice(0, 3), [401, 401, 401]);
  assert.ok(statuses.slice(3).every((s) => s === 429), `expected 429s, got ${statuses.join(',')}`);
});
