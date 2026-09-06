import { query } from '../db/index.js';
import { config } from '../config.js';
import { badRequest, conflict, notFound } from '../lib/errors.js';
import {
  toSewaDate, isSewaDay, isValidSewaDate, dayName, daysBetween, currentOrPreviousSewaDay,
} from '../lib/sewaDays.js';

const SELECT = `
  SELECT a.id, a.sewadar_id, a.department_id, a.sewa_date,
         a.in_time, a.out_time, a.in_source, a.out_source,
         a.in_tapped_at, a.out_tapped_at, a.signature_blob,
         a.notes, a.recorded_by, a.created_at, a.updated_at,
         s.full_name AS sewadar_name, s.phone AS sewadar_phone,
         d.name AS department_name,
         r.full_name AS recorded_by_name
    FROM attendance a
    JOIN sewadars s ON s.id = a.sewadar_id
    LEFT JOIN departments d ON d.id = a.department_id
    LEFT JOIN sewadars r ON r.id = a.recorded_by`;

export async function getRecordById(id) {
  const { rows } = await query(`${SELECT} WHERE a.id = $1`, [id]);
  return rows[0] || null;
}

export async function getRecord(sewadarId, sewaDate) {
  const { rows } = await query(
    `${SELECT} WHERE a.sewadar_id = $1 AND a.sewa_date = $2`,
    [sewadarId, sewaDate],
  );
  return rows[0] || null;
}

/**
 * A DATE column comes back as a plain string from node-postgres (we install a
 * parser for that) but as a Date object from the in-memory engine. Normalising
 * here means everything downstream — sorting, grouping, the register's column
 * keys — can rely on a `YYYY-MM-DD` string.
 */
function asDateString(value) {
  if (!value) return value;
  if (typeof value === 'string') return value.slice(0, 10);
  return new Date(value).toISOString().slice(0, 10);
}

/** Decorates a stored row with everything the UI needs but does not store. */
export function describe(record) {
  if (!record) return null;
  record.sewa_date = asDateString(record.sewa_date);
  const hours = sewaHours(record);
  return {
    ...record,
    is_sewa_day: isSewaDay(record.sewa_date),
    day_name: dayName(record.sewa_date),
    hours: hours == null ? null : Number(hours.toFixed(2)),
    status: !record.in_time ? 'ABSENT' : !record.out_time ? 'IN' : 'DONE',
    in_adjusted: wasAdjusted(record.in_time, record.in_tapped_at),
    out_adjusted: wasAdjusted(record.out_time, record.out_tapped_at),
  };
}

export function sewaHours(record) {
  if (!record?.in_time || !record?.out_time) return null;
  return (new Date(record.out_time) - new Date(record.in_time)) / 3_600_000;
}

/** Minutes a recorded time was moved from the moment the button was pressed. */
function wasAdjusted(recorded, tapped) {
  if (!recorded || !tapped) return 0;
  return Math.round((new Date(recorded) - new Date(tapped)) / 60_000);
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

export function assertUsableSewaDate(sewaDate, { allowFuture = false } = {}) {
  if (!isValidSewaDate(sewaDate)) throw badRequest('Sewa date must be a real date in YYYY-MM-DD form.');

  const today = toSewaDate();
  if (!allowFuture && sewaDate > today) {
    throw badRequest('That sewa day has not happened yet.');
  }
  if (!config.sewa.allowNonSewaDays && !isSewaDay(sewaDate)) {
    throw badRequest(`${sewaDate} is a ${dayName(sewaDate)} — sewa is not scheduled that day.`);
  }
  return sewaDate;
}

/**
 * A Sewadar may set their own times freely, but only near the moment they
 * actually tapped, and only for a recent day. Both limits are lifted for an
 * admin, who is correcting the register rather than reporting their own sewa.
 */
export function assertWithinTolerance(newTime, tappedAt, label) {
  if (!tappedAt) return; // nothing was tapped, so there is nothing to drift from

  const tolerance = config.sewa.adjustToleranceMinutes;
  const drift = Math.abs(new Date(newTime) - new Date(tappedAt)) / 60_000;

  if (drift > tolerance) {
    throw badRequest(
      `The ${label} time can be moved by up to ${formatMinutes(tolerance)} from when you tapped. `
      + `That is ${formatMinutes(Math.round(drift))} away — ask an administrator to correct it.`,
    );
  }
}

function formatMinutes(minutes) {
  if (minutes < 60) return `${minutes} minutes`;
  const hours = minutes / 60;
  return `${Number.isInteger(hours) ? hours : hours.toFixed(1)} hour${hours === 1 ? '' : 's'}`;
}

function assertOrder(inTime, outTime) {
  if (inTime && outTime && new Date(outTime) <= new Date(inTime)) {
    throw badRequest('The out-time must be after the in-time.');
  }
  if (inTime && outTime) {
    const hours = (new Date(outTime) - new Date(inTime)) / 3_600_000;
    if (hours > 24) throw badRequest('A single sewa day cannot be longer than 24 hours.');
  }
}

// ---------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------

async function departmentOf(sewadarId) {
  const { rows } = await query('SELECT department_id FROM sewadars WHERE id = $1', [sewadarId]);
  if (!rows[0]) throw notFound(`No Sewadar with ID ${sewadarId}.`);
  return rows[0].department_id;
}

/** Creates the day's row if it is not there yet, and returns it. */
async function ensureRecord(sewadarId, sewaDate) {
  const existing = await getRecord(sewadarId, sewaDate);
  if (existing) return existing;

  const { rows } = await query(
    `INSERT INTO attendance (sewadar_id, department_id, sewa_date) VALUES ($1, $2, $3) RETURNING id`,
    [sewadarId, await departmentOf(sewadarId), sewaDate],
  );
  return getRecordById(rows[0].id);
}

/**
 * Record an arrival or a departure at the current server time.
 *
 * `source` says where the tap came from — the Sewadar's own phone or the
 * counter tablet — and is kept so the register can show how each entry arrived.
 */
export async function tap({ sewadarId, sewaDate, action, source = 'TAP', signature = null }) {
  const date = sewaDate || toSewaDate();
  assertUsableSewaDate(date);

  const record = await ensureRecord(sewadarId, date);
  const now = new Date();

  if (action === 'IN') {
    if (record.in_time) {
      throw conflict(`Sewa entry for ${date} is already recorded.`, { record: describe(record) });
    }
    await query(
      `UPDATE attendance
          SET in_time = $2, in_tapped_at = $2, in_source = $3,
              signature_blob = COALESCE($4, signature_blob),
              updated_at = CURRENT_TIMESTAMP
        WHERE id = $1`,
      [record.id, now, source, signature],
    );
  } else {
    if (!record.in_time) {
      throw conflict('No entry time recorded yet — sign in before signing out.', { record: describe(record) });
    }
    if (record.out_time) {
      throw conflict(`Sewa exit for ${date} is already recorded.`, { record: describe(record) });
    }
    if (now <= new Date(record.in_time)) {
      throw conflict('The out-time must be after the in-time.');
    }
    await query(
      `UPDATE attendance
          SET out_time = $2, out_tapped_at = $2, out_source = $3,
              signature_blob = COALESCE($4, signature_blob),
              updated_at = CURRENT_TIMESTAMP
        WHERE id = $1`,
      [record.id, now, source, signature],
    );
  }

  return describe(await getRecordById(record.id));
}

/**
 * A Sewadar setting or correcting their own times.
 *
 * Free within the tolerance around what they tapped; a day they never tapped
 * at all can be filled in outright, within the backfill window.
 */
export async function selfAdjust({ sewadarId, sewaDate, inTime, outTime, notes }) {
  assertUsableSewaDate(sewaDate);

  const age = daysBetween(sewaDate, toSewaDate());
  if (age > config.sewa.backfillDays) {
    throw badRequest(
      `${sewaDate} is more than ${config.sewa.backfillDays} days ago. Ask an administrator to add it.`,
    );
  }

  const record = await ensureRecord(sewadarId, sewaDate);
  const nextIn = inTime === undefined ? record.in_time : inTime;
  const nextOut = outTime === undefined ? record.out_time : outTime;

  if (!nextIn && nextOut) throw badRequest('Set an in-time before an out-time.');
  assertOrder(nextIn, nextOut);

  if (inTime !== undefined && inTime) assertWithinTolerance(inTime, record.in_tapped_at, 'entry');
  if (outTime !== undefined && outTime) assertWithinTolerance(outTime, record.out_tapped_at, 'exit');

  await query(
    `UPDATE attendance
        SET in_time = $2,
            out_time = $3,
            in_source = CASE WHEN $2 IS NULL THEN NULL ELSE COALESCE(in_source, 'MANUAL') END,
            out_source = CASE WHEN $3 IS NULL THEN NULL ELSE COALESCE(out_source, 'MANUAL') END,
            notes = COALESCE($4, notes),
            updated_at = CURRENT_TIMESTAMP
      WHERE id = $1`,
    [record.id, nextIn, nextOut, notes ?? null],
  );

  return describe(await getRecordById(record.id));
}

/**
 * An admin writing or correcting any Sewadar's record. Not bound by the
 * tolerance or the backfill window, but always attributed and note-worthy.
 */
export async function adminSet({ sewadarId, sewaDate, inTime, outTime, notes, adminId }) {
  assertUsableSewaDate(sewaDate);
  assertOrder(inTime, outTime);
  if (!inTime && outTime) throw badRequest('Set an in-time before an out-time.');

  const record = await ensureRecord(sewadarId, sewaDate);

  await query(
    `UPDATE attendance
        SET in_time = $2, out_time = $3,
            in_source = CASE WHEN $2 IS NULL THEN NULL ELSE 'ADMIN' END,
            out_source = CASE WHEN $3 IS NULL THEN NULL ELSE 'ADMIN' END,
            notes = $4, recorded_by = $5, updated_at = CURRENT_TIMESTAMP
      WHERE id = $1`,
    [record.id, inTime ?? null, outTime ?? null, notes ?? null, adminId],
  );

  return describe(await getRecordById(record.id));
}

export async function deleteRecord(id) {
  const { rows } = await query('DELETE FROM attendance WHERE id = $1 RETURNING id', [id]);
  return Boolean(rows[0]);
}

// ---------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------

export async function listRecords({ from, to, sewadarId, departmentId, sewaDate, limit = 2000 } = {}) {
  const where = [];
  const params = [];

  if (sewaDate) { params.push(sewaDate); where.push(`a.sewa_date = $${params.length}`); }
  if (from) { params.push(from); where.push(`a.sewa_date >= $${params.length}`); }
  if (to) { params.push(to); where.push(`a.sewa_date <= $${params.length}`); }
  if (sewadarId) { params.push(sewadarId); where.push(`a.sewadar_id = $${params.length}`); }
  if (departmentId) { params.push(Number(departmentId)); where.push(`a.department_id = $${params.length}`); }

  const clause = where.length ? `WHERE ${where.join(' AND ')}` : '';
  params.push(limit);

  const { rows } = await query(
    `${SELECT} ${clause} ORDER BY a.sewa_date DESC, s.full_name ASC LIMIT $${params.length}`,
    params,
  );
  return rows.map(describe);
}

/**
 * The whole roster for one sewa day, present or not — this is the sheet an
 * admin actually works from, so a Sewadar with no record yet still appears,
 * marked absent.
 */
export async function rosterForDay(sewaDate, departmentId = null) {
  const params = [sewaDate];
  let filter = '';
  if (departmentId) {
    params.push(Number(departmentId));
    filter = ` AND s.department_id = $${params.length}`;
  }

  const { rows } = await query(
    `SELECT s.id AS sewadar_id, s.full_name AS sewadar_name, s.phone AS sewadar_phone,
            s.department_id, d.name AS department_name,
            a.id, a.sewa_date, a.in_time, a.out_time, a.in_source, a.out_source,
            a.in_tapped_at, a.out_tapped_at, a.notes, a.recorded_by, a.signature_blob
       FROM sewadars s
       LEFT JOIN departments d ON d.id = s.department_id
       LEFT JOIN attendance a ON a.sewadar_id = s.id AND a.sewa_date = $1
      WHERE s.is_active = TRUE${filter}
      ORDER BY d.name, s.full_name`,
    params,
  );

  return rows.map((row) => ({
    ...describe({ ...row, sewa_date: row.sewa_date || sewaDate }),
    id: row.id ?? null,
    sewa_date: sewaDate,
  }));
}

/** Everything the phone console needs for the day it opens to. */
export async function sewadarDay(sewadarId, sewaDate) {
  // Today, always — never silently retargeted to the last sewa day. Someone
  // doing extra sewa on a Tuesday means Tuesday, and pointing their tap at
  // Sunday would collide with the record they already have there.
  const date = sewaDate || toSewaDate();
  const record = await getRecord(sewadarId, date);

  return {
    sewaDate: date,
    dayName: dayName(date),
    isSewaDay: isSewaDay(date),
    record: describe(record),
    nextAction: !record?.in_time ? 'IN' : !record?.out_time ? 'OUT' : null,
    toleranceMinutes: config.sewa.adjustToleranceMinutes,
    serverTime: new Date().toISOString(),
  };
}
