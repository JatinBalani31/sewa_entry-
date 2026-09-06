import { pathToFileURL } from 'node:url';
import { query, migrate, closePool } from './index.js';
import { hashSecret } from '../lib/pin.js';
import { config } from '../config.js';
import { recentSewaDays, dayName } from '../lib/sewaDays.js';

/**
 * Demo data: the departments a sangat actually runs, a roster spread across
 * them, and the last several Wednesdays and Sundays of attendance — including
 * the awkward cases the reports exist to surface (someone who forgot to sign
 * out, times nudged after the fact, an extra sewa day).
 *
 * Safe to re-run: existing rows are left alone.
 */

const DEPARTMENTS = [
  { name: 'Accounts', code: 'ACC', description: 'Donations, receipts and daily cash reconciliation.' },
  { name: 'BAV', code: 'BAV', description: 'Bal Aur Vidya — children and education sewa.' },
  { name: 'Traffic', code: 'TRF', description: 'Parking marshalling and crowd flow at the gates.' },
  { name: 'Luggage', code: 'LUG', description: 'Cloakroom, bag checking and safe custody.' },
  { name: 'Mobile', code: 'MOB', description: 'Mobile phone deposit counter.' },
  { name: 'Safai', code: 'SAF', description: 'Cleanliness of the hall, langar and premises.' },
  { name: 'Transport', code: 'TRN', description: 'Bus coordination and shuttle duty.' },
  { name: 'Langar', code: 'LNG', description: 'Kitchen, serving and washing sewa.' },
];

const SEWADARS = [
  { id: 'ADM001', full_name: 'Priya Nair', phone: '9820011001', email: 'admin@sewa.local', role: 'ADMIN', pin: '4729', password: config.seedAdminPassword, department: null },
  { id: 'ADM002', full_name: 'Rakesh Iyer', phone: '9820011002', email: 'rakesh@sewa.local', role: 'ADMIN', pin: '5183', password: 'sewa@admin2', department: 'Accounts' },

  { id: 'SW1001', full_name: 'Harpreet Singh', phone: '9820022001', department: 'Accounts', hod: true },
  { id: 'SW1002', full_name: 'Manjit Kaur', phone: '9820022002', department: 'Accounts' },
  { id: 'SW1003', full_name: 'Vikram Sethi', phone: '9820022003', department: 'Accounts' },

  { id: 'SW2001', full_name: 'Anita Desai', phone: '9820022004', department: 'BAV', hod: true },
  { id: 'SW2002', full_name: 'Rohan Mehta', phone: '9820022005', department: 'BAV' },
  { id: 'SW2003', full_name: 'Kavita Joshi', phone: '9820022006', department: 'BAV' },

  { id: 'SW3001', full_name: 'Gurmeet Singh', phone: '9820022007', department: 'Traffic', hod: true },
  { id: 'SW3002', full_name: 'Amit Kulkarni', phone: '9820022008', department: 'Traffic' },
  { id: 'SW3003', full_name: 'Balbir Chawla', phone: '9820022009', department: 'Traffic' },
  { id: 'SW3004', full_name: 'Sunil Rane', phone: '9820022010', department: 'Traffic' },

  { id: 'SW4001', full_name: 'Deepak Shah', phone: '9820022011', department: 'Luggage', hod: true },
  { id: 'SW4002', full_name: 'Nisha Patel', phone: '9820022012', department: 'Luggage' },

  { id: 'SW5001', full_name: 'Farah Khan', phone: '9820022013', department: 'Mobile', hod: true },
  { id: 'SW5002', full_name: 'Imran Shaikh', phone: '9820022014', department: 'Mobile' },

  { id: 'SW6001', full_name: 'Lata Kamble', phone: '9820022015', department: 'Safai', hod: true },
  { id: 'SW6002', full_name: 'Ramesh Gaikwad', phone: '9820022016', department: 'Safai' },
  { id: 'SW6003', full_name: 'Shalini More', phone: '9820022017', department: 'Safai' },

  { id: 'SW7001', full_name: 'Jaspal Bedi', phone: '9820022018', department: 'Transport', hod: true },
  { id: 'SW7002', full_name: 'Naveen Rao', phone: '9820022019', department: 'Transport' },

  { id: 'SW8001', full_name: 'Simran Gill', phone: '9820022020', department: 'Langar', hod: true },
  { id: 'SW8002', full_name: 'Pooja Verma', phone: '9820022021', department: 'Langar' },
  { id: 'SW8003', full_name: 'Arun Nambiar', phone: '9820022022', department: 'Langar' },
];

// A sewa day runs from mid-morning to late afternoon; individuals drift around
// those anchors so the demo register does not look machine-generated.
const at = (sewaDate, hour, minute) => new Date(`${sewaDate}T${pad(hour)}:${pad(minute)}:00.000+05:30`);
const pad = (n) => String(n).padStart(2, '0');
const jitter = (spread) => Math.floor(Math.random() * spread) - Math.floor(spread / 2);

export async function seed({ quiet = false } = {}) {
  const log = quiet ? () => {} : (...args) => console.log(...args);

  await migrate();

  // --- Departments ----------------------------------------------------------
  const departmentIds = new Map();
  for (const department of DEPARTMENTS) {
    const existing = await query('SELECT id FROM departments WHERE name = $1', [department.name]);
    if (existing.rows[0]) {
      departmentIds.set(department.name, existing.rows[0].id);
      continue;
    }
    const { rows } = await query(
      'INSERT INTO departments (name, code, description) VALUES ($1,$2,$3) RETURNING id',
      [department.name, department.code, department.description],
    );
    departmentIds.set(department.name, rows[0].id);
    log(`  + department ${department.name}`);
  }

  // --- Sewadars -------------------------------------------------------------
  for (const sewadar of SEWADARS) {
    const existing = await query('SELECT id FROM sewadars WHERE id = $1', [sewadar.id]);
    if (existing.rows[0]) continue;

    const pin = sewadar.pin || String(1000 + Math.floor(Math.random() * 8000));
    await query(
      `INSERT INTO sewadars (id, full_name, phone, email, department_id, role,
                             secret_pin_hash, password_hash, is_active)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,TRUE)`,
      [
        sewadar.id, sewadar.full_name, sewadar.phone, sewadar.email || null,
        sewadar.department ? departmentIds.get(sewadar.department) : null,
        sewadar.role || 'SEWADAR',
        await hashSecret(pin),
        sewadar.password ? await hashSecret(sewadar.password) : null,
      ],
    );
    log(`  + sewadar ${sewadar.id} ${sewadar.full_name}${sewadar.role === 'ADMIN' ? ' (ADMIN)' : ''} — PIN ${pin}`);
  }

  // --- HODs -----------------------------------------------------------------
  for (const sewadar of SEWADARS.filter((s) => s.hod)) {
    await query(
      'UPDATE departments SET hod_sewadar_id = $2 WHERE name = $1 AND hod_sewadar_id IS NULL',
      [sewadar.department, sewadar.id],
    );
  }

  // --- Attendance -----------------------------------------------------------
  const { rows: counted } = await query('SELECT COUNT(*)::int AS n FROM attendance');
  if (counted[0].n > 0) {
    log(`  = attendance already holds ${counted[0].n} row(s); skipping history.`);
    return;
  }

  const { rows: roster } = await query(
    `SELECT s.id, s.department_id, (d.hod_sewadar_id = s.id) AS is_hod
       FROM sewadars s
       LEFT JOIN departments d ON d.id = s.department_id
      WHERE s.role = 'SEWADAR'
      ORDER BY s.id`,
  );
  const dates = recentSewaDays(8);
  let written = 0;

  for (const sewaDate of dates) {
    const isToday = sewaDate === dates[dates.length - 1];

    for (const sewadar of roster) {
      // Not everyone comes every time — roughly one in six sits a day out.
      // Department heads are the exception: they are there every sewa day,
      // which also keeps the demo from opening on an HOD with an empty record.
      if (!sewadar.is_hod && Math.random() < 0.17) continue;

      const inTime = at(sewaDate, 9, 30 + jitter(40));
      // On the most recent day some people are still doing sewa, so the board
      // has someone to show as signed in.
      const stillIn = isToday && Math.random() < 0.35;
      const forgotToSignOut = !isToday && Math.random() < 0.06;
      const outTime = stillIn || forgotToSignOut ? null : at(sewaDate, 16, 30 + jitter(60));

      await query(
        `INSERT INTO attendance (sewadar_id, department_id, sewa_date, in_time, out_time,
                                 in_source, out_source, in_tapped_at, out_tapped_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$4,$5)`,
        [
          sewadar.id, sewadar.department_id, sewaDate, inTime, outTime,
          Math.random() < 0.6 ? 'TAP' : 'KIOSK',
          outTime ? (Math.random() < 0.6 ? 'TAP' : 'KIOSK') : null,
        ],
      );
      written++;
    }
  }

  // One record whose times were visibly nudged after the fact, and one entry on
  // a day that is not on the schedule — both should surface as exceptions.
  const adjustable = roster[0];
  if (adjustable) {
    const date = dates[dates.length - 3];
    await query(
      `UPDATE attendance SET in_time = $3, notes = $4
        WHERE sewadar_id = $1 AND sewa_date = $2`,
      [
        adjustable.id, date, at(date, 8, 15),
        'Arrived early for setup; corrected on the phone afterwards.',
      ],
    );
  }

  const extraSewadar = roster[1];
  if (extraSewadar) {
    // The day after the most recent sewa day — off-schedule on purpose.
    const base = new Date(`${dates[dates.length - 1]}T00:00:00.000Z`);
    base.setUTCDate(base.getUTCDate() + 1);
    const extraDate = base.toISOString().slice(0, 10);

    await query(
      `INSERT INTO attendance (sewadar_id, department_id, sewa_date, in_time, out_time,
                               in_source, out_source, in_tapped_at, out_tapped_at, notes)
       VALUES ($1,$2,$3,$4,$5,'KIOSK','KIOSK',$4,$5,$6)`,
      [
        extraSewadar.id, extraSewadar.department_id, extraDate,
        at(extraDate, 10, 0), at(extraDate, 13, 0),
        `Extra sewa on ${dayName(extraDate)} for stock-taking.`,
      ],
    );
    written++;
  }

  log(`  + ${written} attendance record(s) across ${dates.length} sewa days`);
}

// Run directly: `npm run seed`. pathToFileURL is what makes this comparison
// work on Windows, where a hand-built `file://` + path lands one slash short
// of the `file:///C:/...` form that import.meta.url actually uses.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  seed()
    .then(() => {
      console.log('\nSeed complete.');
      console.log(`  Admin console : ADM001  /  ${config.seedAdminPassword}`);
      console.log('                  (email admin@sewa.local works too)');
      console.log('  Sewadar login : SW1001  /  their 4-digit PIN, shown above');
      console.log('  Kiosk PINs    : the same PINs, typed on the counter tablet\n');
      return closePool();
    })
    .catch(async (err) => {
      console.error('Seed failed:', err);
      await closePool();
      process.exit(1);
    });
}
