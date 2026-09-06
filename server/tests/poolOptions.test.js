import test from 'node:test';
import assert from 'node:assert/strict';

process.env.USE_MEMORY_DB = '1';
process.env.JWT_SECRET = 'test-secret';

const { poolOptions } = await import('../src/db/index.js');

test('a local docker-compose Postgres URL gets no ssl option', () => {
  const options = poolOptions('postgres://sewa:sewa_dev_password@db:5432/sewa_entry');
  assert.equal(options.ssl, undefined, 'a plain local Postgres does not speak TLS at all');
});

test('a managed Postgres URL with sslmode=require gets an explicit ssl option', () => {
  const options = poolOptions('postgres://user:pass@ep-example.neon.tech/sewa_entry?sslmode=require');
  assert.deepEqual(options.ssl, { rejectUnauthorized: false });
});

test('the sslmode check is case-insensitive and works alongside other query params', () => {
  const options = poolOptions('postgres://user:pass@host/db?SSLMODE=REQUIRE&channel_binding=require');
  assert.deepEqual(options.ssl, { rejectUnauthorized: false });
});

test('ssl=true is honoured the same way as sslmode=require', () => {
  const options = poolOptions('postgres://user:pass@host/db?ssl=true');
  assert.deepEqual(options.ssl, { rejectUnauthorized: false });
});

test('the connection string itself is always passed through unchanged', () => {
  const url = 'postgres://user:pass@ep-example.neon.tech/sewa_entry?sslmode=require';
  assert.equal(poolOptions(url).connectionString, url);
});
