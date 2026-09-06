import ExcelJS from 'exceljs';
import { config } from '../config.js';
import { query } from '../db/index.js';
import { listRecords, sewaHours } from './attendance.js';
import { sewaDaysBetween, dayName, isSewaDay } from '../lib/sewaDays.js';

const TZ = config.report.timezone;

export function localTime(instant) {
  if (!instant) return '';
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: TZ, hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(new Date(instant));
}

/** Fractional day since local midnight — how Excel stores a time of day. */
function dayFraction(instant) {
  if (!instant) return null;
  const [h, m] = localTime(instant).split(':').map(Number);
  return (h * 3600 + m * 60) / 86_400;
}

/**
 * Group the flat record list by department, then by sewa day.
 * Departments with no attendance in the period are still returned, so a
 * department that did not turn up is visible rather than merely absent.
 */
export async function buildDepartmentReport({ startDate, endDate, departmentId, sewadarId } = {}) {
  const records = await listRecords({
    from: startDate, to: endDate, departmentId, sewadarId, limit: 100_000,
  });

  const { rows: departments } = await query(
    `SELECT d.id, d.name, d.code, h.full_name AS hod_name
       FROM departments d
       LEFT JOIN sewadars h ON h.id = d.hod_sewadar_id
      WHERE d.is_active = TRUE
      ORDER BY d.name`,
  );

  const { rows: roster } = await query(
    `SELECT id, full_name, phone, department_id FROM sewadars
      WHERE is_active = TRUE ORDER BY full_name`,
  );

  const dates = startDate && endDate ? sewaDaysBetween(startDate, endDate) : [];

  const byDepartment = new Map();
  const bucketFor = (id, name) => {
    const key = id ?? 0;
    if (!byDepartment.has(key)) {
      byDepartment.set(key, {
        id: key,
        name: name || 'No department',
        hodName: departments.find((d) => d.id === id)?.hod_name || null,
        records: [],
        sewadars: roster.filter((s) => (s.department_id ?? 0) === key),
      });
    }
    return byDepartment.get(key);
  };

  for (const department of departments) bucketFor(department.id, department.name);
  for (const record of records) bucketFor(record.department_id, record.department_name).records.push(record);

  const summary = [...byDepartment.values()].map((department) => {
    const present = department.records.filter((r) => r.in_time);
    const hours = present.reduce((total, r) => total + (sewaHours(r) || 0), 0);
    const missingOut = present.filter((r) => !r.out_time).length;

    return {
      departmentId: department.id || null,
      departmentName: department.name,
      hodName: department.hodName,
      sewadarCount: department.sewadars.length,
      attendances: present.length,
      totalHours: Number(hours.toFixed(2)),
      averageHours: present.length ? Number((hours / present.length).toFixed(2)) : 0,
      missingOutTime: missingOut,
      // Across the period, what share of the possible attendances happened.
      possible: department.sewadars.length * dates.length,
    };
  });

  return { records, departments: [...byDepartment.values()], summary, dates };
}

/** Flags for a manager to look at by hand. */
export function buildExceptions(records) {
  const exceptions = [];

  for (const record of records) {
    if (record.in_time && !record.out_time) {
      exceptions.push({
        sewaDate: record.sewa_date,
        sewadarId: record.sewadar_id,
        sewadarName: record.sewadar_name,
        departmentName: record.department_name || 'No department',
        type: 'No exit time',
        detail: `Signed in at ${localTime(record.in_time)} but never signed out.`,
      });
    }

    const hours = sewaHours(record);
    if (hours != null && hours > config.report.maxSewaHours) {
      exceptions.push({
        sewaDate: record.sewa_date,
        sewadarId: record.sewadar_id,
        sewadarName: record.sewadar_name,
        departmentName: record.department_name || 'No department',
        type: 'Unusually long sewa',
        detail: `${hours.toFixed(2)} h recorded, above the ${config.report.maxSewaHours} h threshold.`,
      });
    }

    if (!isSewaDay(record.sewa_date) && record.in_time) {
      exceptions.push({
        sewaDate: record.sewa_date,
        sewadarId: record.sewadar_id,
        sewadarName: record.sewadar_name,
        departmentName: record.department_name || 'No department',
        type: 'Extra sewa day',
        detail: `Recorded on a ${dayName(record.sewa_date)}, outside the usual schedule.`,
      });
    }

    const adjustment = Math.abs(record.in_adjusted || 0) + Math.abs(record.out_adjusted || 0);
    if (adjustment >= 60) {
      exceptions.push({
        sewaDate: record.sewa_date,
        sewadarId: record.sewadar_id,
        sewadarName: record.sewadar_name,
        departmentName: record.department_name || 'No department',
        type: 'Times adjusted',
        detail: `Entry moved ${record.in_adjusted || 0} min, exit moved ${record.out_adjusted || 0} min from the tapped time.`,
      });
    }
  }

  return exceptions.sort(
    (a, b) => a.sewaDate.localeCompare(b.sewaDate) || a.sewadarName.localeCompare(b.sewadarName),
  );
}

// --- Workbook styling -------------------------------------------------------

const HEADER_FILL = 'FF1F3A5F';
const PRESENT_FILL = 'FFD8F3DC';
const ABSENT_FILL = 'FFF1F3F6';
const WARN_FILL = 'FFFDF3D8';
const ALERT_FILL = 'FFF8D7DA';

function styleHeader(sheet, { rowIndex = 1, filter = true } = {}) {
  const header = sheet.getRow(rowIndex);
  header.font = { bold: true, color: { argb: 'FFFFFFFF' }, size: 11 };
  header.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: HEADER_FILL } };
  header.alignment = { vertical: 'middle', horizontal: 'left', wrapText: true };
  header.height = 24;

  sheet.views = [{ state: 'frozen', ySplit: rowIndex, xSplit: 1 }];
  if (filter) {
    sheet.autoFilter = { from: { row: rowIndex, column: 1 }, to: { row: rowIndex, column: sheet.columnCount } };
  }
}

function border(cell) {
  cell.border = {
    top: { style: 'hair', color: { argb: 'FFD0D5DD' } },
    left: { style: 'hair', color: { argb: 'FFD0D5DD' } },
    bottom: { style: 'hair', color: { argb: 'FFD0D5DD' } },
    right: { style: 'hair', color: { argb: 'FFD0D5DD' } },
  };
}

/** Excel forbids : \ / ? * [ ] in a sheet name, and caps it at 31 characters. */
function safeSheetName(name, used) {
  let base = String(name).replace(/[:\\/?*[\]]/g, '-').slice(0, 28).trim() || 'Sheet';
  let candidate = base;
  let n = 2;
  while (used.has(candidate.toLowerCase())) candidate = `${base} ${n++}`;
  used.add(candidate.toLowerCase());
  return candidate;
}

/**
 * The export the sangat actually asked for: every department's record of
 * in-times and out-times across the sewa days in the period.
 *
 *   1  Summary          — one row per department
 *   2  Register         — Sewadars down the side, sewa days across the top
 *   3  All Entries      — the flat list, one row per Sewadar per sewa day
 *   4+ One per department
 *   n  Exceptions, Report Info
 */
export async function buildWorkbook({ startDate, endDate, departmentId, sewadarId } = {}) {
  const { records, departments, summary, dates } = await buildDepartmentReport({
    startDate, endDate, departmentId, sewadarId,
  });
  const exceptions = buildExceptions(records);

  const wb = new ExcelJS.Workbook();
  wb.creator = 'Sewa Entry';
  wb.created = new Date();

  const used = new Set();

  // --- 1. Summary -----------------------------------------------------------
  const sum = wb.addWorksheet(safeSheetName('Summary', used));
  sum.columns = [
    { header: 'Department', key: 'name', width: 26 },
    { header: 'HOD', key: 'hod', width: 22 },
    { header: 'Sewadars', key: 'count', width: 11 },
    { header: 'Attendances', key: 'attendances', width: 13 },
    { header: 'Possible', key: 'possible', width: 11 },
    { header: 'Turnout %', key: 'turnout', width: 12 },
    { header: 'Total hours', key: 'hours', width: 13 },
    { header: 'Avg hours', key: 'avg', width: 12 },
    { header: 'Missing exit', key: 'missing', width: 13 },
  ];

  for (const row of summary) {
    const excelRow = sum.addRow({
      name: row.departmentName,
      hod: row.hodName || '—',
      count: row.sewadarCount,
      attendances: row.attendances,
      possible: row.possible || '—',
      turnout: row.possible ? Number(((row.attendances / row.possible) * 100).toFixed(1)) : '—',
      hours: row.totalHours,
      avg: row.averageHours,
      missing: row.missingOutTime,
    });
    excelRow.getCell('hours').numFmt = '0.00';
    excelRow.getCell('avg').numFmt = '0.00';
    if (typeof excelRow.getCell('turnout').value === 'number') excelRow.getCell('turnout').numFmt = '0.0';
    if (row.missingOutTime > 0) {
      excelRow.getCell('missing').fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: ALERT_FILL } };
    }
    excelRow.eachCell(border);
  }

  if (summary.length) {
    const total = sum.addRow({
      name: 'TOTAL',
      count: summary.reduce((t, r) => t + r.sewadarCount, 0),
      attendances: summary.reduce((t, r) => t + r.attendances, 0),
      hours: Number(summary.reduce((t, r) => t + r.totalHours, 0).toFixed(2)),
      missing: summary.reduce((t, r) => t + r.missingOutTime, 0),
    });
    total.font = { bold: true };
    total.getCell('hours').numFmt = '0.00';
  }
  styleHeader(sum);

  // --- 2. Register ----------------------------------------------------------
  // The classic attendance sheet: names down, dates across, in/out in the cell.
  const register = wb.addWorksheet(safeSheetName('Register', used), {
    pageSetup: { orientation: 'landscape', fitToPage: true, fitToWidth: 1, fitToHeight: 0 },
  });

  const registerColumns = [
    { header: 'Department', key: 'dept', width: 24 },
    { header: 'Sewadar ID', key: 'id', width: 13 },
    { header: 'Name', key: 'name', width: 24 },
    ...dates.map((date) => ({
      header: `${date}\n${dayName(date).slice(0, 3)}`,
      key: date,
      width: 14,
    })),
    { header: 'Days present', key: 'present', width: 13 },
    { header: 'Total hours', key: 'hours', width: 12 },
  ];
  register.columns = registerColumns;

  const byKey = new Map(records.map((r) => [`${r.sewadar_id}::${r.sewa_date}`, r]));

  for (const department of departments) {
    for (const sewadar of department.sewadars) {
      const row = { dept: department.name, id: sewadar.id, name: sewadar.full_name };
      let present = 0;
      let hours = 0;

      for (const date of dates) {
        const record = byKey.get(`${sewadar.id}::${date}`);
        if (record?.in_time) {
          present++;
          hours += sewaHours(record) || 0;
          row[date] = `${localTime(record.in_time)}–${localTime(record.out_time) || '?'}`;
        } else {
          row[date] = '—';
        }
      }
      row.present = present;
      row.hours = Number(hours.toFixed(2));

      const excelRow = register.addRow(row);
      excelRow.getCell('hours').numFmt = '0.00';

      for (const date of dates) {
        const cell = excelRow.getCell(date);
        const record = byKey.get(`${sewadar.id}::${date}`);
        cell.alignment = { horizontal: 'center' };
        cell.fill = {
          type: 'pattern',
          pattern: 'solid',
          fgColor: { argb: !record?.in_time ? ABSENT_FILL : record.out_time ? PRESENT_FILL : WARN_FILL },
        };
      }
      excelRow.eachCell(border);
    }
  }
  styleHeader(register);
  register.getRow(1).alignment = { vertical: 'middle', horizontal: 'center', wrapText: true };
  register.getRow(1).height = 32;

  // --- 3. All entries -------------------------------------------------------
  addEntriesSheet(wb.addWorksheet(safeSheetName('All Entries', used)), records);

  // --- 4. One sheet per department -----------------------------------------
  for (const department of departments) {
    if (!department.records.length && !department.sewadars.length) continue;
    const sheet = wb.addWorksheet(safeSheetName(department.name, used));
    addEntriesSheet(sheet, department.records, { department });
  }

  // --- Exceptions -----------------------------------------------------------
  const exc = wb.addWorksheet(safeSheetName('Exceptions', used));
  exc.columns = [
    { header: 'Sewa date', key: 'date', width: 13 },
    { header: 'Department', key: 'dept', width: 22 },
    { header: 'Sewadar', key: 'name', width: 24 },
    { header: 'Issue', key: 'type', width: 22 },
    { header: 'Detail', key: 'detail', width: 62 },
  ];
  for (const exception of exceptions) {
    const row = exc.addRow({
      date: exception.sewaDate,
      dept: exception.departmentName,
      name: exception.sewadarName,
      type: exception.type,
      detail: exception.detail,
    });
    row.getCell('type').fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: ALERT_FILL } };
    row.eachCell(border);
  }
  if (!exceptions.length) exc.addRow({ date: '—', type: 'Nothing to review in this period' });
  styleHeader(exc);

  // --- Report info ----------------------------------------------------------
  const info = wb.addWorksheet(safeSheetName('Report Info', used));
  info.columns = [{ header: 'Field', key: 'k', width: 26 }, { header: 'Value', key: 'v', width: 56 }];
  info.addRows([
    { k: 'Generated at', v: new Date().toISOString() },
    { k: 'Timezone', v: TZ },
    { k: 'Period start', v: startDate || 'all time' },
    { k: 'Period end', v: endDate || 'all time' },
    { k: 'Sewa days in period', v: dates.length },
    { k: 'Department filter', v: departmentId || 'all departments' },
    { k: 'Sewadar filter', v: sewadarId || 'all Sewadars' },
    { k: 'Records', v: records.length },
    { k: 'Exceptions', v: exceptions.length },
  ]);
  styleHeader(info, { filter: false });

  return wb;
}

function addEntriesSheet(sheet, records, { department } = {}) {
  sheet.columns = [
    { header: 'Sewa date', key: 'date', width: 13 },
    { header: 'Day', key: 'day', width: 11 },
    { header: 'Sewadar ID', key: 'id', width: 13 },
    { header: 'Name', key: 'name', width: 24 },
    { header: 'Phone', key: 'phone', width: 15 },
    ...(department ? [] : [{ header: 'Department', key: 'dept', width: 22 }]),
    { header: 'In time', key: 'in', width: 11 },
    { header: 'Out time', key: 'out', width: 11 },
    { header: 'Hours', key: 'hours', width: 10 },
    { header: 'Recorded by', key: 'source', width: 16 },
    { header: 'Notes', key: 'notes', width: 40 },
  ];

  const SOURCE_LABEL = { TAP: 'Phone', MANUAL: 'Self-entered', KIOSK: 'Counter tablet', ADMIN: 'Admin' };

  for (const record of [...records].reverse()) {
    const row = sheet.addRow({
      date: record.sewa_date,
      day: record.day_name,
      id: record.sewadar_id,
      name: record.sewadar_name,
      phone: record.sewadar_phone || '',
      ...(department ? {} : { dept: record.department_name || 'No department' }),
      in: dayFraction(record.in_time),
      out: dayFraction(record.out_time),
      hours: record.hours ?? null,
      source: SOURCE_LABEL[record.in_source] || '—',
      notes: record.notes || '',
    });

    row.getCell('in').numFmt = 'hh:mm';
    row.getCell('out').numFmt = 'hh:mm';
    row.getCell('hours').numFmt = '0.00';

    if (record.in_time && !record.out_time) {
      row.getCell('out').fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: ALERT_FILL } };
    }
    if (!isSewaDay(record.sewa_date)) {
      row.getCell('day').fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: WARN_FILL } };
    }
    row.eachCell(border);
  }

  if (!records.length) sheet.addRow({ date: '—', name: 'No attendance recorded in this period' });

  // A department sheet carries its own heading rows, so the header sits lower.
  if (department) {
    sheet.spliceRows(1, 0, [], []);
    sheet.getCell('A1').value = department.name;
    sheet.getCell('A1').font = { bold: true, size: 14 };
    sheet.getCell('A2').value = `HOD: ${department.hodName || 'not assigned'} · ${department.sewadars.length} Sewadar(s)`;
    sheet.getCell('A2').font = { color: { argb: 'FF5B6879' } };
    styleHeader(sheet, { rowIndex: 3 });
  } else {
    styleHeader(sheet);
  }
}
