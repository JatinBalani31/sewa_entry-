import { badRequest } from '../lib/errors.js';

/** Validates req[source] against a zod schema and replaces it with the parsed value. */
export function validate(schema, source = 'body') {
  return (req, _res, next) => {
    const result = schema.safeParse(req[source]);
    if (!result.success) {
      const details = result.error.issues.map((i) => ({
        field: i.path.join('.') || '(root)',
        message: i.message,
      }));
      return next(badRequest(details[0]?.message || 'Invalid request.', details));
    }
    req[source === 'query' ? 'validatedQuery' : source] = result.data;
    next();
  };
}
