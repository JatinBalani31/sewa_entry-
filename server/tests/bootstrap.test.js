import test, { before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';

process.env.USE_MEMORY_DB = '1';
process.env.JWT_SECRET = 'test-secret';
process.env.BOOTSTRAP_ADMIN_ID = 'ADMIN1';
process.env.BOOTSTRAP_ADMIN_PASSWORD = 'first-run-password';

const { migrate, query, closePool } = await import('../src/db/index.js');
const { bootstrapFirstAdmin } = await import('../src/db/bootstrap.js');
const { signIn } = await import('../src/services/credentials.js');

before(async () => {
  await migrate();
});

beforeEach(async () => {
  await query('DELETE FROM attendance');
  await query('DELETE FROM sewadars');
  await query('DELETE FROM departments');
});

after(async () => {
  await closePool();
});

test('an empty database gets exactly one working administrator', async () => {
  const created = await bootstrapFirstAdmin();

  assert.equal(created.id, 'ADMIN1');
  assert.equal(created.password, 'first-run-password');

  const sewadar = await signIn('ADMIN1', 'first-run-password');
  assert.equal(sewadar.role, 'ADMIN');
  assert.equal(sewadar.id, 'ADMIN1');
});

test('a database that already has anyone in it is left alone', async () => {
  await query(
    `INSERT INTO sewadars (id, full_name, secret_pin_hash) VALUES ($1, $2, $3)`,
    ['EXISTING1', 'Someone Already Here', '$2a$10$abcdefghijklmnopqrstuuvwxyzabcdefghijklmnopqrstuvwxy'],
  );

  const created = await bootstrapFirstAdmin();
  assert.equal(created, null, 'must not run once the roster is non-empty, even a non-admin roster');

  const { rows } = await query(`SELECT id FROM sewadars WHERE role = 'ADMIN'`);
  assert.equal(rows.length, 0, 'no admin should have been created');
});

test('running it twice on an empty database only ever creates one admin', async () => {
  await bootstrapFirstAdmin();
  const second = await bootstrapFirstAdmin();

  assert.equal(second, null, 'the second call sees the first admin and does nothing');

  const { rows } = await query('SELECT COUNT(*)::int AS n FROM sewadars');
  assert.equal(rows[0].n, 1);
});

test('a weak bootstrap password fails loudly instead of creating a weak admin', async () => {
  process.env.BOOTSTRAP_ADMIN_PASSWORD = '1234';
  const { config } = await import('../src/config.js');
  config.bootstrapAdmin.password = '1234';

  await assert.rejects(() => bootstrapFirstAdmin(), /at least 8 characters/i);

  const { rows } = await query('SELECT COUNT(*)::int AS n FROM sewadars');
  assert.equal(rows[0].n, 0, 'no half-created admin should be left behind');

  config.bootstrapAdmin.password = 'first-run-password';
});
