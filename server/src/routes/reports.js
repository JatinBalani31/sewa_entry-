import { Router } from 'express';
import { z } from 'zod';
import { authenticate, loadCurrentUser, requireRole } from '../middleware/auth.js';
import { asyncRoute } from '../middleware/error.js';
import { validate } from '../middleware/validate.js';
import { badRequest } from '../lib/errors.js';
import { buildDepartmentReport, buildExceptions, buildWorkbook } from '../services/report.js';

export const reportsRouter = Router();

reportsRouter.use(authenticate, loadCurrentUser, requireRole('ADMIN'));

const rangeSchema = z.object({
  startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'startDate must be YYYY-MM-DD.').optional(),
  endDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'endDate must be YYYY-MM-DD.').optional(),
  departmentId: z.coerce.number().int().positive().optional(),
  sewadarId: z.string().trim().optional(),
});

function assertRange(q) {
  if (q.startDate && q.endDate && q.startDate > q.endDate) {
    throw badRequest('startDate must not be after endDate.');
  }
}

/** The department-wise workbook. */
reportsRouter.get(
  '/excel',
  validate(rangeSchema, 'query'),
  asyncRoute(async (req, res) => {
    const q = req.validatedQuery;
    assertRange(q);

    const workbook = await buildWorkbook(q);
    const name = `sewa_attendance_${q.startDate || 'all'}_to_${q.endDate || 'all'}.xlsx`;

    res.setHeader('Content-Type', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    res.setHeader('Content-Disposition', `attachment; filename="${name}"`);
    await workbook.xlsx.write(res);
    res.end();
  }),
);

/** The same figures as JSON, so the console can preview before downloading. */
reportsRouter.get(
  '/summary',
  validate(rangeSchema, 'query'),
  asyncRoute(async (req, res) => {
    const q = req.validatedQuery;
    assertRange(q);

    const { records, summary, dates } = await buildDepartmentReport(q);
    const exceptions = buildExceptions(records);

    res.json({
      period: { startDate: q.startDate || null, endDate: q.endDate || null, sewaDays: dates },
      totals: {
        records: records.length,
        attendances: records.filter((r) => r.in_time).length,
        hours: Number(records.reduce((t, r) => t + (r.hours || 0), 0).toFixed(2)),
        exceptions: exceptions.length,
      },
      departments: summary,
      exceptions,
    });
  }),
);
