import crypto from 'node:crypto';
import { query } from './index.js';
import { hashSecret, assertValidPassword } from '../lib/pin.js';
import { config } from '../config.js';

/**
 * Create the very first administrator, but only when the database is
 * genuinely empty.
 *
 * `npm run seed` fills a fresh database with demo departments and Sewadars,
 * which is right for trying the app out but wrong for a real deployment — an
 * NGO's production database should hold their real roster, not "Harpreet
 * Singh". This instead creates exactly one working admin account so the first
 * deploy has a way in at all, and does nothing once anyone exists.
 *
 * Safe to call on every boot: the emptiness check makes it a no-op after the
 * first successful run.
 */
export async function bootstrapFirstAdmin() {
  const { rows } = await query('SELECT COUNT(*)::int AS n FROM sewadars');
  if (rows[0].n > 0) return null;

  const { id, name, email, password } = config.bootstrapAdmin;
  assertValidPassword(password); // fail loudly rather than boot with a weak admin

  // The PIN is not meant to be used — it exists only because the column is
  // NOT NULL — so a random one is generated and never surfaced. The admin
  // signs in with the password and can set a real PIN from the console if
  // they ever need the counter tablet too.
  const throwawayPin = String(crypto.randomInt(100_000, 1_000_000));

  await query(
    `INSERT INTO sewadars (id, full_name, email, secret_pin_hash, password_hash, role, is_active)
     VALUES ($1, $2, $3, $4, $5, 'ADMIN', TRUE)`,
    [id, name, email, await hashSecret(throwawayPin), await hashSecret(password)],
  );

  return { id, password };
}
