import { verifyToken, TOKEN_TYPE } from '../lib/tokens.js';
import { unauthorized, forbidden } from '../lib/errors.js';
import { query } from '../db/index.js';

function readBearer(req) {
  const header = req.get('authorization') || '';
  const [scheme, token] = header.split(' ');
  return scheme?.toLowerCase() === 'bearer' && token ? token : null;
}

/** Populates req.auth from a valid bearer token, or throws 401. */
export function authenticate(req, _res, next) {
  const token = readBearer(req);
  if (!token) return next(unauthorized('Missing bearer token.'));
  try {
    req.auth = verifyToken(token);
    next();
  } catch (err) {
    next(unauthorized(
      err.name === 'TokenExpiredError' ? 'Session expired. Sign in again.' : 'Invalid token.',
    ));
  }
}

/** Requires a person's session, not a counter tablet's station token. */
export function requireUser(req, _res, next) {
  if (req.auth?.typ !== TOKEN_TYPE.USER) {
    return next(forbidden('This endpoint requires a signed-in user.'));
  }
  next();
}

/** Requires a station token issued to a counter tablet. */
export function requireKiosk(req, _res, next) {
  if (req.auth?.typ !== TOKEN_TYPE.KIOSK) {
    return next(forbidden('This endpoint requires a kiosk station token.'));
  }
  next();
}

export function requireRole(...roles) {
  return (req, _res, next) => {
    if (req.auth?.typ !== TOKEN_TYPE.USER) {
      return next(forbidden('This endpoint requires a signed-in user.'));
    }
    if (!roles.includes(req.auth.role)) {
      return next(forbidden(`Requires role: ${roles.join(' or ')}.`));
    }
    next();
  };
}

/**
 * Confirms the account behind the token still exists and is active — a token
 * issued before a Sewadar was deactivated must stop working immediately.
 */
export async function loadCurrentUser(req, _res, next) {
  // A kiosk token carries a station name, not a Sewadar ID — without this the
  // lookup below would miss and report the confusing "account no longer exists".
  if (req.auth?.typ !== TOKEN_TYPE.USER) {
    return next(forbidden('This endpoint requires a signed-in user, not a kiosk station token.'));
  }

  try {
    const { rows } = await query(
      `SELECT s.id, s.full_name, s.phone, s.email, s.role, s.department_id,
              s.is_active, s.joined_on, s.created_at,
              d.name AS department_name
         FROM sewadars s
         LEFT JOIN departments d ON d.id = s.department_id
        WHERE s.id = $1`,
      [req.auth.sub],
    );

    const sewadar = rows[0];
    if (!sewadar) return next(unauthorized('Account no longer exists.'));
    if (!sewadar.is_active) return next(forbidden('This account has been deactivated.'));

    req.user = sewadar;
    next();
  } catch (err) {
    next(err);
  }
}
