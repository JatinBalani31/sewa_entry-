import { Router } from 'express';
import { z } from 'zod';
import { query } from '../db/index.js';
import { authenticate, loadCurrentUser, requireRole } from '../middleware/auth.js';
import { asyncRoute } from '../middleware/error.js';
import { validate } from '../middleware/validate.js';
import { badRequest, notFound, conflict } from '../lib/errors.js';

export const departmentsRouter = Router();

departmentsRouter.use(authenticate, loadCurrentUser);

const SELECT = `
  SELECT d.id, d.name, d.code, d.description, d.hod_sewadar_id, d.is_active, d.created_at,
         h.full_name AS hod_name, h.phone AS hod_phone
    FROM departments d
    LEFT JOIN sewadars h ON h.id = d.hod_sewadar_id`;

/** Everyone can read the department list — the kiosk and the sign-in screens use it. */
departmentsRouter.get(
  '/',
  asyncRoute(async (req, res) => {
    const includeInactive = req.query.include_inactive === 'true' && req.user.role === 'ADMIN';
    const { rows } = await query(
      `${SELECT} ${includeInactive ? '' : 'WHERE d.is_active = TRUE'} ORDER BY d.name`,
    );

    // Head-count per department, folded in here so the console does not have
    // to make a second round trip for a number it always shows.
    const { rows: counts } = await query(
      `SELECT department_id, COUNT(*)::int AS n FROM sewadars
        WHERE is_active = TRUE GROUP BY department_id`,
    );
    const countFor = new Map(counts.map((c) => [c.department_id, c.n]));

    res.json({
      departments: rows.map((d) => ({ ...d, sewadar_count: countFor.get(d.id) ?? 0 })),
    });
  }),
);

departmentsRouter.get(
  '/:id',
  asyncRoute(async (req, res) => {
    const { rows } = await query(`${SELECT} WHERE d.id = $1`, [Number(req.params.id)]);
    if (!rows[0]) throw notFound(`No department with ID ${req.params.id}.`);

    const { rows: members } = await query(
      `SELECT id, full_name, phone, role, is_active FROM sewadars
        WHERE department_id = $1 ORDER BY full_name`,
      [Number(req.params.id)],
    );
    res.json({ department: rows[0], members });
  }),
);

const departmentSchema = z.object({
  name: z.string({ required_error: 'Department name is required.' })
    .trim().min(1, 'Department name is required.').max(100),
  code: z.string().trim().max(20).nullable().optional(),
  description: z.string().trim().max(1000).nullable().optional(),
  hod_sewadar_id: z.string().trim().max(50).nullable().optional(),
  is_active: z.boolean().optional(),
});

departmentsRouter.post(
  '/',
  requireRole('ADMIN'),
  validate(departmentSchema),
  asyncRoute(async (req, res) => {
    await assertHodExists(req.body.hod_sewadar_id);
    const { rows } = await query(
      `INSERT INTO departments (name, code, description, hod_sewadar_id, is_active)
       VALUES ($1, $2, $3, $4, COALESCE($5, TRUE)) RETURNING id`,
      [
        req.body.name, req.body.code || null, req.body.description || null,
        req.body.hod_sewadar_id || null, req.body.is_active ?? null,
      ],
    );
    const { rows: created } = await query(`${SELECT} WHERE d.id = $1`, [rows[0].id]);
    res.status(201).json({ department: created[0] });
  }),
);

departmentsRouter.patch(
  '/:id',
  requireRole('ADMIN'),
  validate(departmentSchema.partial()),
  asyncRoute(async (req, res) => {
    const id = Number(req.params.id);
    await findDepartment(id);
    if (req.body.hod_sewadar_id !== undefined) await assertHodExists(req.body.hod_sewadar_id);

    const fields = [];
    const params = [id];
    for (const [key, value] of Object.entries(req.body)) {
      params.push(value === '' ? null : value);
      fields.push(`${key} = $${params.length}`);
    }
    if (!fields.length) throw badRequest('No fields to update.');

    await query(`UPDATE departments SET ${fields.join(', ')} WHERE id = $1`, params);
    const { rows } = await query(`${SELECT} WHERE d.id = $1`, [id]);
    res.json({ department: rows[0] });
  }),
);

departmentsRouter.delete(
  '/:id',
  requireRole('ADMIN'),
  asyncRoute(async (req, res) => {
    const id = Number(req.params.id);
    const department = await findDepartment(id);

    // A department with people in it is deactivated rather than deleted, so no
    // Sewadar is quietly orphaned and no past report loses its heading.
    const { rows } = await query(
      'SELECT COUNT(*)::int AS n FROM sewadars WHERE department_id = $1 AND is_active = TRUE',
      [id],
    );
    if (rows[0].n > 0) {
      if (req.query.force !== 'true') {
        throw conflict(
          `${department.name} still has ${rows[0].n} active Sewadar(s). Move them first, or pass force=true to close the department and leave them unassigned.`,
          { memberCount: rows[0].n },
        );
      }
      await query('UPDATE sewadars SET department_id = NULL WHERE department_id = $1', [id]);
    }

    await query('UPDATE departments SET is_active = FALSE WHERE id = $1', [id]);
    res.json({
      ok: true,
      message: `${department.name} closed. Past attendance keeps its department name in reports.`,
    });
  }),
);

async function findDepartment(id) {
  const { rows } = await query('SELECT id, name FROM departments WHERE id = $1', [id]);
  if (!rows[0]) throw notFound(`No department with ID ${id}.`);
  return rows[0];
}

async function assertHodExists(hodId) {
  if (!hodId) return;
  const { rows } = await query('SELECT id FROM sewadars WHERE id = $1', [hodId]);
  if (!rows[0]) throw badRequest(`No Sewadar with ID ${hodId} to make HOD.`);
}
