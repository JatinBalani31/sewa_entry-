import bcrypt from 'bcryptjs';
import { config } from '../config.js';

export const PIN_MIN_LENGTH = 4;
export const PIN_MAX_LENGTH = 12;

export function hashSecret(secret) {
  return bcrypt.hash(String(secret), config.security.bcryptRounds);
}

export function verifySecret(secret, hash) {
  return bcrypt.compare(String(secret), String(hash));
}

// Kept under the old names too — both credentials are hashed the same way.
export const hashPin = hashSecret;
export const verifyPin = verifySecret;

/** PINs are numeric so they can be typed on the kiosk's numeric keypad. */
export function assertValidPin(pin) {
  const value = String(pin ?? '');
  if (!/^\d+$/.test(value)) {
    throw new Error('PIN must contain digits only.');
  }
  if (value.length < PIN_MIN_LENGTH || value.length > PIN_MAX_LENGTH) {
    throw new Error(`PIN must be ${PIN_MIN_LENGTH}-${PIN_MAX_LENGTH} digits long.`);
  }
  if (/^(\d)\1+$/.test(value)) {
    throw new Error('PIN must not be a single repeated digit.');
  }
  const digits = value.split('').map(Number);
  const ascending = digits.every((d, i) => i === 0 || d === digits[i - 1] + 1);
  const descending = digits.every((d, i) => i === 0 || d === digits[i - 1] - 1);
  if (ascending || descending) {
    throw new Error('PIN must not be a run of sequential digits.');
  }
  return value;
}

/**
 * Admin passwords open the whole console, so they are held to more than a PIN:
 * length, and not one of the handful of passwords that get tried first.
 */
const OBVIOUS_PASSWORDS = new Set([
  'password', 'password1', '12345678', '123456789', 'qwerty123',
  'admin123', 'sewaentry', 'letmein1', 'welcome1', 'iloveyou',
]);

export function assertValidPassword(password) {
  const value = String(password ?? '');
  const min = config.security.minPasswordLength;

  if (value.length < min) throw new Error(`Password must be at least ${min} characters.`);
  if (value.length > 200) throw new Error('Password must be 200 characters or fewer.');
  if (OBVIOUS_PASSWORDS.has(value.toLowerCase())) {
    throw new Error('That password is too common. Choose something less guessable.');
  }
  if (/^\d+$/.test(value)) {
    throw new Error('An admin password must not be only digits — add letters too.');
  }
  return value;
}
