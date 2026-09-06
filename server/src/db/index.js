import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';
import { config } from '../config.js';

const here = path.dirname(fileURLToPath(import.meta.url));
export const SCHEMA_PATH = path.join(here, 'schema.sql');

// Return TIMESTAMP columns as ISO strings rather than local-timezone Date
// objects, so the API speaks one unambiguous time format everywhere.
pg.types.setTypeParser(1114, (v) => (v === null ? null : new Date(v + 'Z').toISOString()));

// A sewa_date is a calendar date, not an instant. Handing it back as the raw
// `YYYY-MM-DD` string keeps it from being shifted a day by the server's own
// timezone on its way through a Date object.
pg.types.setTypeParser(1082, (v) => v);

let pool = null;

async function createMemoryPool() {
  const { newDb } = await import('pg-mem');
  const db = newDb({ autoCreateForeignKeyIndices: true });
  const adapter = db.adapters.createPg();
  return new adapter.Pool();
}

/**
 * node-postgres does not reliably negotiate TLS from a `?sslmode=require` in
 * the connection string alone — behaviour has varied across versions, so an
 * explicit `ssl` option is the only dependable way to get it. Managed
 * Postgres providers (Neon, Supabase, Render's own Postgres, ...) put
 * `sslmode=require` in the URL and terminate TLS with a certificate chain
 * Node does not always fully validate, hence `rejectUnauthorized: false`
 * here — the connection is still encrypted, just not pinned to a CA.
 *
 * A local `docker-compose` Postgres carries no such parameter and has no TLS
 * configured at all, so it is left alone: requesting SSL against a server
 * that does not support it fails outright rather than falling back.
 */
export function poolOptions(connectionString) {
  const wantsSsl = /\bsslmode=require\b|\bssl=true\b/i.test(connectionString);
  return {
    connectionString,
    max: 10,
    idleTimeoutMillis: 30_000,
    connectionTimeoutMillis: 10_000,
    ...(wantsSsl ? { ssl: { rejectUnauthorized: false } } : {}),
  };
}

export async function getPool() {
  if (pool) return pool;

  if (config.useMemoryDb) {
    pool = await createMemoryPool();
    console.warn('[db] Using in-memory PostgreSQL emulation — data resets on restart.');
  } else {
    pool = new pg.Pool(poolOptions(config.databaseUrl));
    pool.on('error', (err) => console.error('[db] idle client error', err));
  }
  return pool;
}

export async function query(text, params = []) {
  const p = await getPool();
  return p.query(text, params);
}

/** Run `fn` inside a transaction, rolling back on any throw. */
export async function transaction(fn) {
  const p = await getPool();
  const client = await p.connect();
  try {
    await client.query('BEGIN');
    const result = await fn(client);
    await client.query('COMMIT');
    return result;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

let migrated = false;

/** Apply schema.sql. Every statement in it is idempotent. */
export async function migrate() {
  if (migrated) return true;

  const sql = fs.readFileSync(SCHEMA_PATH, 'utf8');
  const p = await getPool();

  if (config.useMemoryDb) {
    // pg-mem does not implement `ADD COLUMN IF NOT EXISTS`, and when a
    // `CREATE ... IF NOT EXISTS` hits an object that already exists it skips
    // the statement without reading its AST, then reports that as an
    // unsupported query. Both are benign here, so statements run one at a
    // time and the "it is already there" failures are swallowed.
    for (const statement of splitStatements(sql)) {
      try {
        await p.query(statement.replace(/ADD COLUMN IF NOT EXISTS/gi, 'ADD COLUMN'));
      } catch (err) {
        if (!isAlreadyExists(err)) throw err;
      }
    }
  } else {
    await p.query(sql);
  }
  migrated = true;
  return true;
}

function isAlreadyExists(err) {
  return /already exists|duplicate/i.test(err.message)
    || (err.name === 'NotSupported' && /have not been read by the query planner/i.test(err.message));
}

/** Naive splitter: adequate because schema.sql holds no functions or DO blocks. */
function splitStatements(sql) {
  return sql
    .split('\n')
    .filter((line) => !line.trim().startsWith('--'))
    .join('\n')
    .split(';')
    .map((s) => s.trim())
    .filter(Boolean);
}

export async function closePool() {
  if (pool) {
    await pool.end().catch(() => {});
    pool = null;
    migrated = false;
  }
}
