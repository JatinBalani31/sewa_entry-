import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { z } from 'zod';
import { config } from '../config.js';
import { query } from '../db/index.js';
import { signIn } from '../services/credentials.js';
import { signUserToken } from '../lib/tokens.js';
import { authenticate, loadCurrentUser } from '../middleware/auth.js';
import { asyncRoute } from '../middleware/error.js';
import { validate } from '../middleware/validate.js';
import { hashSecret, verifySecret, assertValidPin, assertValidPassword } from '../lib/pin.js';
import { badRequest, unauthorized } from '../lib/errors.js';
import { scheduleLabel, currentOrPreviousSewaDay, nextSewaDay, toSewaDate } from '../lib/sewaDays.js';

export const authRouter = Router();

const loginLimiter = rateLimit({
  windowMs: 5 * 60 * 1000,
  limit: config.security.loginAttemptsPerWindow,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: 'Too many sign-in attempts from this device. Try again in a few minutes.' },
});

const loginSchema = z.object({
  // One field for all three, because people remember different things about
  // themselves: a Sewadar ID, an email, or a phone number.
  identifier: z.string({ required_error: 'Enter your Sewadar ID, email or phone number.' })
    .trim().min(1, 'Enter your Sewadar ID, email or phone number.'),
  secret: z.string({ required_error: 'Enter your PIN or password.' })
    .min(1, 'Enter your PIN or password.'),
});

authRouter.post(
  '/login',
  loginLimiter,
  validate(loginSchema),
  asyncRoute(async (req, res) => {
    const sewadar = await signIn(req.body.identifier, req.body.secret);
    res.json({ token: signUserToken(sewadar), user: publicSewadar(sewadar) });
  }),
);

authRouter.get(
  '/me',
  authenticate,
  loadCurrentUser,
  asyncRoute(async (req, res) => {
    res.json({
      user: publicSewadar(req.user),
      serverTime: new Date().toISOString(),
      sewa: {
        schedule: scheduleLabel(),
        days: config.sewa.days,
        today: toSewaDate(),
        currentSewaDay: currentOrPreviousSewaDay(),
        nextSewaDay: nextSewaDay(),
        toleranceMinutes: config.sewa.adjustToleranceMinutes,
        backfillDays: config.sewa.backfillDays,
      },
    });
  }),
);

const changeSecretSchema = z.object({
  current: z.string({ required_error: 'Your current PIN or password is required.' }).min(1),
  next: z.string({ required_error: 'A new PIN or password is required.' }).min(1),
});

/**
 * Changes whichever credential the account actually signs in with — an admin
 * changes their password here, a Sewadar their PIN.
 */
authRouter.post(
  '/change-secret',
  authenticate,
  loadCurrentUser,
  validate(changeSecretSchema),
  asyncRoute(async (req, res) => {
    const { rows } = await query(
      'SELECT secret_pin_hash, password_hash FROM sewadars WHERE id = $1',
      [req.user.id],
    );
    const usesPassword = Boolean(rows[0].password_hash);
    const currentHash = usesPassword ? rows[0].password_hash : rows[0].secret_pin_hash;

    if (!(await verifySecret(req.body.current, currentHash))) {
      throw unauthorized(`Your current ${usesPassword ? 'password' : 'PIN'} is incorrect.`);
    }
    if (req.body.next === req.body.current) {
      throw badRequest('The new value must be different from the current one.');
    }

    let next;
    try {
      next = usesPassword ? assertValidPassword(req.body.next) : assertValidPin(req.body.next);
    } catch (err) {
      throw badRequest(err.message);
    }

    const column = usesPassword ? 'password_hash' : 'secret_pin_hash';
    await query(
      `UPDATE sewadars SET ${column} = $2, failed_pin_attempts = 0, pin_locked_until = NULL WHERE id = $1`,
      [req.user.id, await hashSecret(next)],
    );

    res.json({ ok: true, changed: usesPassword ? 'password' : 'pin' });
  }),
);

/** An admin's kiosk PIN, changed without touching their console password. */
const changePinSchema = z.object({
  current: z.string({ required_error: 'Your current PIN or password is required.' }).min(1),
  newPin: z.string({ required_error: 'A new PIN is required.' }).min(1),
});

authRouter.post(
  '/change-pin',
  authenticate,
  loadCurrentUser,
  validate(changePinSchema),
  asyncRoute(async (req, res) => {
    const { rows } = await query(
      'SELECT secret_pin_hash, password_hash FROM sewadars WHERE id = $1',
      [req.user.id],
    );
    const currentHash = rows[0].password_hash || rows[0].secret_pin_hash;
    if (!(await verifySecret(req.body.current, currentHash))) {
      throw unauthorized('Your current PIN or password is incorrect.');
    }

    let pin;
    try {
      pin = assertValidPin(req.body.newPin);
    } catch (err) {
      throw badRequest(err.message);
    }

    await query(
      'UPDATE sewadars SET secret_pin_hash = $2, failed_pin_attempts = 0, pin_locked_until = NULL WHERE id = $1',
      [req.user.id, await hashSecret(pin)],
    );
    res.json({ ok: true, changed: 'pin' });
  }),
);

export function publicSewadar(sewadar) {
  return {
    id: sewadar.id,
    full_name: sewadar.full_name,
    phone: sewadar.phone ?? null,
    email: sewadar.email ?? null,
    role: sewadar.role,
    department_id: sewadar.department_id ?? null,
    department_name: sewadar.department_name ?? null,
    is_active: sewadar.is_active ?? true,
    joined_on: sewadar.joined_on ?? null,
    created_at: sewadar.created_at ?? null,
  };
}
