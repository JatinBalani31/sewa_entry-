import 'dotenv/config';

function bool(value, fallback) {
  if (value === undefined) return fallback;
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
}

function int(value, fallback) {
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) ? n : fallback;
}

/** "0,3" -> [0, 3]. Sunday is 0, matching JavaScript's getDay(). */
function days(value, fallback) {
  if (!value) return fallback;
  const parsed = String(value)
    .split(',')
    .map((d) => Number.parseInt(d.trim(), 10))
    .filter((d) => Number.isInteger(d) && d >= 0 && d <= 6);
  return parsed.length ? [...new Set(parsed)].sort() : fallback;
}

const isProd = process.env.NODE_ENV === 'production';

export const config = {
  env: process.env.NODE_ENV || 'development',
  isProd,
  port: int(process.env.PORT, 4001),

  databaseUrl: process.env.DATABASE_URL || '',
  // When no DATABASE_URL is configured the server boots against an in-process
  // PostgreSQL emulation (pg-mem) so the app is runnable before Docker is up.
  // Data is lost on restart — never enable this in production.
  useMemoryDb: bool(process.env.USE_MEMORY_DB, !process.env.DATABASE_URL),

  jwt: {
    secret: process.env.JWT_SECRET || 'dev-only-insecure-secret-change-me',
    expiresIn: process.env.JWT_EXPIRES_IN || '12h',
    // A counter tablet stays signed in across many sewa days without a re-login.
    kioskExpiresIn: process.env.KIOSK_TOKEN_EXPIRES_IN || '30d',
  },

  security: {
    bcryptRounds: int(process.env.BCRYPT_ROUNDS, 10),
    maxPinAttempts: int(process.env.MAX_PIN_ATTEMPTS, 3),
    pinLockoutMinutes: int(process.env.PIN_LOCKOUT_MINUTES, 5),
    minPasswordLength: int(process.env.MIN_PASSWORD_LENGTH, 8),
    corsOrigins: (process.env.CORS_ORIGINS || 'http://localhost:5173')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),

    // Per-IP ceilings, sitting in front of the per-account PIN lockout: one
    // stops a single account being ground down, these stop one host spraying
    // many accounts. A kiosk is a whole queue of people behind one IP, so its
    // allowance is much higher than a personal phone's.
    loginAttemptsPerWindow: int(process.env.LOGIN_RATE_LIMIT, 20),
    punchesPerMinute: int(process.env.PUNCH_RATE_LIMIT, 20),
    kioskRequestsPerMinute: int(process.env.KIOSK_RATE_LIMIT, 60),
  },

  sewa: {
    // Sunday = 0 … Saturday = 6. Sewa runs on Wednesday and Sunday.
    days: days(process.env.SEWA_DAYS, [0, 3]),

    // How far a Sewadar may move their own in/out time from the moment the
    // button was actually pressed. Generous on purpose: people forget to tap,
    // and this is a volunteer roster, not a factory clock. An admin correcting
    // a record is not bound by it.
    adjustToleranceMinutes: int(process.env.TIME_ADJUST_TOLERANCE_MINUTES, 120),

    // Attendance outside a sewa day is allowed — it is simply marked as extra
    // sewa in the register rather than refused.
    allowNonSewaDays: bool(process.env.ALLOW_NON_SEWA_DAYS, true),

    // How far back a Sewadar may fill in a day they missed entirely.
    backfillDays: int(process.env.BACKFILL_DAYS, 14),
  },

  report: {
    // Times are stored as instants; reports render them in the sangat's zone.
    timezone: process.env.REPORT_TIMEZONE || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Kolkata',
    // A sewa longer than this is flagged as a probable forgotten sign-out.
    maxSewaHours: int(process.env.MAX_SEWA_HOURS, 14),
  },

  seedAdminPassword: process.env.SEED_ADMIN_PASSWORD || 'sewa@admin1',

  // The one administrator created automatically the first time the app boots
  // against a real, empty database — otherwise a fresh production deployment
  // would have a schema and nobody able to sign in. Only fires when the
  // sewadars table has zero rows; see server/src/db/bootstrap.js.
  bootstrapAdmin: {
    id: process.env.BOOTSTRAP_ADMIN_ID || 'ADMIN1',
    name: process.env.BOOTSTRAP_ADMIN_NAME || 'Administrator',
    email: process.env.BOOTSTRAP_ADMIN_EMAIL || null,
    password: process.env.BOOTSTRAP_ADMIN_PASSWORD || process.env.SEED_ADMIN_PASSWORD || 'sewa@admin1',
  },
};

if (isProd && config.jwt.secret === 'dev-only-insecure-secret-change-me') {
  throw new Error('JWT_SECRET must be set to a strong random value in production.');
}
if (isProd && config.useMemoryDb) {
  throw new Error('USE_MEMORY_DB cannot be enabled in production. Set DATABASE_URL.');
}
