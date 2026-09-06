import { query } from '../db/index.js';
import { verifySecret } from '../lib/pin.js';
import { config } from '../config.js';
import { unauthorized, locked, forbidden } from '../lib/errors.js';

const { maxPinAttempts, pinLockoutMinutes } = config.security;

// A bcrypt hash of a value nobody knows. Comparing against it makes a missing
// account cost the same time as a wrong secret, so the endpoint cannot be used
// to work out who is registered.
const DECOY_HASH = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';

const PUBLIC_COLUMNS = `
  s.id, s.full_name, s.phone, s.email, s.role, s.department_id, s.is_active,
  s.joined_on, s.created_at, s.failed_pin_attempts, s.pin_locked_until,
  d.name AS department_name`;

/**
 * Sign in to the console.
 *
 * One form, two kinds of credential. An administrator has a password, and once
 * they have one it is the only thing that opens the console — their PIN keeps
 * working at the counter tablet but can no longer be used to sign in, so the
 * short numeric code never becomes a way into the admin screens.
 *
 * `identifier` is an ID, an email, or a phone number; people remember different
 * things about themselves.
 */
export async function signIn(identifier, secret) {
  const value = String(identifier || '').trim().toLowerCase();

  const { rows } = await query(
    `SELECT ${PUBLIC_COLUMNS}, s.secret_pin_hash, s.password_hash
       FROM sewadars s
       LEFT JOIN departments d ON d.id = s.department_id
      WHERE LOWER(s.id) = $1 OR LOWER(s.email) = $1 OR s.phone = $1`,
    [value],
  );
  const sewadar = rows[0];

  if (!sewadar) {
    await verifySecret(secret, DECOY_HASH);
    throw unauthorized('Invalid credentials.');
  }
  if (!sewadar.is_active) throw forbidden('This account has been deactivated.');

  const lockedUntil = sewadar.pin_locked_until ? new Date(sewadar.pin_locked_until) : null;
  if (lockedUntil && lockedUntil > new Date()) {
    throw locked(
      `Too many failed attempts. Try again in ${secondsUntil(lockedUntil)} seconds.`,
      { lockedUntil: lockedUntil.toISOString() },
    );
  }

  const usesPassword = Boolean(sewadar.password_hash);
  const ok = await verifySecret(secret, usesPassword ? sewadar.password_hash : sewadar.secret_pin_hash);

  if (!ok) {
    await registerFailure(sewadar);
    throw unauthorized('Invalid credentials.'); // registerFailure throws when it locks
  }

  await clearFailures(sewadar);
  return strip(sewadar);
}

/** PIN check for the counter tablet, where people are picked from a list. */
export async function verifyKioskPin(sewadarId, pin) {
  const { rows } = await query(
    `SELECT ${PUBLIC_COLUMNS}, s.secret_pin_hash
       FROM sewadars s
       LEFT JOIN departments d ON d.id = s.department_id
      WHERE s.id = $1`,
    [String(sewadarId).trim()],
  );
  const sewadar = rows[0];

  if (!sewadar) {
    await verifySecret(pin, DECOY_HASH);
    throw unauthorized('Invalid credentials.');
  }
  if (!sewadar.is_active) throw forbidden('This account has been deactivated.');

  const lockedUntil = sewadar.pin_locked_until ? new Date(sewadar.pin_locked_until) : null;
  if (lockedUntil && lockedUntil > new Date()) {
    throw locked(
      `Too many wrong PINs. Try again in ${secondsUntil(lockedUntil)} seconds.`,
      { lockedUntil: lockedUntil.toISOString() },
    );
  }

  if (!(await verifySecret(pin, sewadar.secret_pin_hash))) {
    await registerFailure(sewadar);
    throw unauthorized('Invalid credentials.');
  }

  await clearFailures(sewadar);
  return strip(sewadar);
}

/**
 * Count a failed attempt, and lock the account once it reaches the limit.
 * Throws on the attempt that locks so the caller reports the lockout rather
 * than a plain rejection.
 */
async function registerFailure(sewadar) {
  const attempts = (sewadar.failed_pin_attempts ?? 0) + 1;
  const shouldLock = attempts >= maxPinAttempts;
  const until = shouldLock ? new Date(Date.now() + pinLockoutMinutes * 60_000) : null;

  await query(
    'UPDATE sewadars SET failed_pin_attempts = $2, pin_locked_until = $3 WHERE id = $1',
    [sewadar.id, shouldLock ? 0 : attempts, until],
  );

  if (shouldLock) {
    throw locked(
      `Too many failed attempts. This account is locked for ${pinLockoutMinutes} minutes.`,
      { lockedUntil: until.toISOString() },
    );
  }
  throw unauthorized(
    `Invalid credentials. ${maxPinAttempts - attempts} attempt(s) remaining before lockout.`,
  );
}

async function clearFailures(sewadar) {
  if (sewadar.failed_pin_attempts || sewadar.pin_locked_until) {
    await query(
      'UPDATE sewadars SET failed_pin_attempts = 0, pin_locked_until = NULL WHERE id = $1',
      [sewadar.id],
    );
  }
}

function strip(sewadar) {
  const { secret_pin_hash, password_hash, ...rest } = sewadar;
  return rest;
}

function secondsUntil(date) {
  return Math.max(1, Math.ceil((date.getTime() - Date.now()) / 1000));
}
