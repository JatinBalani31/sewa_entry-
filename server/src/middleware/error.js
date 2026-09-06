import { ApiError } from '../lib/errors.js';
import { config } from '../config.js';

/** Wraps an async route handler so rejections reach the error middleware. */
export const asyncRoute = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);

export function notFoundHandler(req, res) {
  res.status(404).json({ error: `No route for ${req.method} ${req.originalUrl}` });
}

export function errorHandler(err, _req, res, _next) {
  if (err instanceof ApiError) {
    return res.status(err.status).json({ error: err.message, ...(err.details ? { details: err.details } : {}) });
  }

  // Postgres integrity violations map onto meaningful HTTP statuses.
  if (err.code === '23505') return res.status(409).json({ error: 'That value is already taken.', details: err.detail });
  if (err.code === '23503') return res.status(400).json({ error: 'Referenced record does not exist.', details: err.detail });
  if (err.code === '23514') return res.status(400).json({ error: 'Value violates a database constraint.', details: err.detail });

  console.error('[error]', err);
  res.status(500).json({
    error: 'Internal server error.',
    ...(config.isProd ? {} : { message: err.message, stack: err.stack }),
  });
}
