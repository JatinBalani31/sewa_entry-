import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';

// The suite always runs against the in-process database so it needs no Docker.
process.env.USE_MEMORY_DB = '1';
process.env.JWT_SECRET = 'test-secret';
process.env.SEED_ADMIN_PASSWORD = 'sewa@admin1';
// The suite signs in far more often than a person would; the limiter has its
// own test file rather than throttling every other one.
process.env.LOGIN_RATE_LIMIT = '2000';
process.env.KIOSK_RATE_LIMIT = '2000';
process.env.PUNCH_RATE_LIMIT = '2000';

const { createApp } = await import('../src/app.js');
const { migrate, closePool } = await import('../src/db/index.js');
const { seed } = await import('../src/db/seed.js');
const { currentOrPreviousSewaDay, toSewaDate } = await import('../src/lib/sewaDays.js');

let base;
let server;
let admin;

// The Sewadar-facing screens always work on today's date, so tests that tap
// and then adjust must use the same day. currentOrPreviousSewaDay is only the
// admin roster's default.
const SEWA_DAY = toSewaDate();

before(async () => {
  await migrate();
  await seed({ quiet: true });
  server = createApp().listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}/api`;
  admin = await login('ADM001', 'sewa@admin1');
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
  await closePool();
});

async function call(method, path, { token, body } = {}) {
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { raw: text }; }
  return { status: res.status, body: json, headers: res.headers };
}

const login = async (identifier, secret) =>
  (await call('POST', '/auth/login', { body: { identifier, secret } })).body.token;

/** A fresh Sewadar, so each test starts from a clean attendance history. */
let counter = 0;
async function makeSewadar(overrides = {}) {
  const id = `TST${String(++counter).padStart(3, '0')}`;
  const res = await call('POST', '/sewadars', {
    token: admin,
    body: { id, full_name: `Test Sewadar ${counter}`, pin: '5837', ...overrides },
  });
  assert.equal(res.status, 201, `could not create ${id}: ${JSON.stringify(res.body)}`);
  return res.body.sewadar;
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

test('health endpoint reports the service is up', async () => {
  const res = await call('GET', '/health');
  assert.equal(res.status, 200);
  assert.equal(res.body.ok, true);
});

test('an admin signs in with their password and gets the sewa schedule', async () => {
  const res = await call('POST', '/auth/login', { body: { identifier: 'ADM001', secret: 'sewa@admin1' } });
  assert.equal(res.status, 200);
  assert.equal(res.body.user.role, 'ADMIN');
  assert.equal(res.body.user.secret_pin_hash, undefined, 'no hash may ever leave the server');
  assert.equal(res.body.user.password_hash, undefined);

  const me = await call('GET', '/auth/me', { token: res.body.token });
  assert.equal(me.body.sewa.schedule, 'Sunday and Wednesday');
  assert.deepEqual(me.body.sewa.days, [0, 3]);
});

test('an admin can sign in by email as well as by ID', async () => {
  assert.ok(await login('admin@sewa.local', 'sewa@admin1'));
});

test("an admin's kiosk PIN does not open the console", async () => {
  // ADM001 has both a password and the PIN 4729. Only the password signs in.
  const res = await call('POST', '/auth/login', { body: { identifier: 'ADM001', secret: '4729' } });
  assert.equal(res.status, 401, 'a 4-digit PIN must never be a way into the admin screens');
});

test('a Sewadar signs in with their PIN, by ID or phone', async () => {
  const sewadar = await makeSewadar({ phone: '9000000001' });
  assert.ok(await login(sewadar.id, '5837'));
  assert.ok(await login('9000000001', '5837'));
});

test('a Sewadar organization can be created, updated, and searched in the web console', async () => {
  const sewadar = await makeSewadar({ organization: 'Community Group' });
  assert.equal(sewadar.organization, 'Community Group');

  const updated = await call('PATCH', `/sewadars/${sewadar.id}`, {
    token: admin,
    body: { organization: 'Updated Community Group' },
  });
  assert.equal(updated.body.sewadar.organization, 'Updated Community Group');

  const search = await call('GET', '/sewadars?search=Updated%20Community', { token: admin });
  assert.ok(search.body.sewadars.some((row) => row.id === sewadar.id));
});

test('an unknown identifier fails the same way as a wrong secret', async () => {
  const unknown = await call('POST', '/auth/login', { body: { identifier: 'NOBODY', secret: '5837' } });
  assert.equal(unknown.status, 401);
  assert.match(unknown.body.error, /Invalid credentials/);
});

test('protected routes reject missing and malformed tokens', async () => {
  assert.equal((await call('GET', '/sewadars')).status, 401);
  assert.equal((await call('GET', '/sewadars', { token: 'not-a-jwt' })).status, 401);
});

test('a Sewadar cannot reach the admin screens', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  assert.equal((await call('GET', '/sewadars', { token })).status, 403);
  assert.equal((await call('GET', '/attendance/roster', { token })).status, 403);
  assert.equal((await call('GET', '/reports/summary', { token })).status, 403);
  assert.equal((await call('POST', '/departments', { token, body: { name: 'Sneaky' } })).status, 403);
});

// ---------------------------------------------------------------------------
// Departments
// ---------------------------------------------------------------------------

test('all departments from the current roster are seeded for the MVP', async () => {
  const res = await call('GET', '/departments', { token: admin });
  assert.equal(res.status, 200);

  const names = res.body.departments.map((d) => d.name);
  assert.equal(names.length, 40);
  for (const expected of ['Account', 'BAV', 'Traffic', 'Luggage', 'Mobile', 'Safai Group A', 'Transport (Traffic 1)']) {
    assert.ok(names.includes(expected), `expected a ${expected} department`);
  }
});

test('each department reports its HOD and head-count', async () => {
  const res = await call('GET', '/departments', { token: admin });
  const traffic = res.body.departments.find((d) => d.name === 'Traffic');

  assert.equal(traffic.hod_name, 'Gurmeet Singh');
  assert.ok(traffic.sewadar_count > 0);
});

test('an admin can add, rename and close a department', async () => {
  const created = await call('POST', '/departments', {
    token: admin,
    body: { name: 'Shoe Stand', code: 'SHO', description: 'Footwear counter sewa.' },
  });
  assert.equal(created.status, 201);
  const id = created.body.department.id;

  const renamed = await call('PATCH', `/departments/${id}`, {
    token: admin,
    body: { name: 'Jodha Ghar' },
  });
  assert.equal(renamed.body.department.name, 'Jodha Ghar');

  const closed = await call('DELETE', `/departments/${id}`, { token: admin });
  assert.equal(closed.status, 200);

  const list = await call('GET', '/departments', { token: admin });
  assert.ok(!list.body.departments.some((d) => d.id === id), 'a closed department drops off the list');
});

test('a duplicate department name is refused', async () => {
  const res = await call('POST', '/departments', { token: admin, body: { name: 'Account' } });
  assert.equal(res.status, 409);
});

test('a department with members cannot be closed by accident', async () => {
  const created = await call('POST', '/departments', { token: admin, body: { name: 'Store Room' } });
  const id = created.body.department.id;
  await makeSewadar({ department_id: id });

  const blocked = await call('DELETE', `/departments/${id}`, { token: admin });
  assert.equal(blocked.status, 409);
  assert.match(blocked.body.error, /still has 1 active Sewadar/);

  const forced = await call('DELETE', `/departments/${id}?force=true`, { token: admin });
  assert.equal(forced.status, 200);
});

test('an HOD must be a real Sewadar', async () => {
  const res = await call('POST', '/departments', {
    token: admin,
    body: { name: 'Ghost Department', hod_sewadar_id: 'NOPE999' },
  });
  assert.equal(res.status, 400);
});

// ---------------------------------------------------------------------------
// Sewadars
// ---------------------------------------------------------------------------

test('a new admin needs a password, not just a PIN', async () => {
  const res = await call('POST', '/sewadars', {
    token: admin,
    body: { id: 'BADADM', full_name: 'No Password', role: 'ADMIN', pin: '5837' },
  });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /needs a password/);
});

test('the weak-PIN policy is enforced when adding a Sewadar', async () => {
  const res = await call('POST', '/sewadars', {
    token: admin,
    body: { id: 'WEAKPIN', full_name: 'Weak Pin', pin: '1234' },
  });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /sequential digits/);
});

test('a Sewadar can be moved between departments', async () => {
  const departments = (await call('GET', '/departments', { token: admin })).body.departments;
  const sewadar = await makeSewadar({ department_id: departments[0].id });

  const moved = await call('PATCH', `/sewadars/${sewadar.id}`, {
    token: admin,
    body: { department_id: departments[1].id },
  });
  assert.equal(moved.body.sewadar.department_id, departments[1].id);
  assert.equal(moved.body.sewadar.department_name, departments[1].name);
});

test('the last administrator cannot be demoted or deactivated', async () => {
  // Two admins are seeded; drop one so only ADM001 is left.
  await call('PATCH', '/sewadars/ADM002', { token: admin, body: { is_active: false } });

  const demote = await call('PATCH', '/sewadars/ADM001', { token: admin, body: { role: 'SEWADAR' } });
  assert.equal(demote.status, 409);
  assert.match(demote.body.error, /last active administrator/);

  await call('PATCH', '/sewadars/ADM002', { token: admin, body: { is_active: true } });
});

test('an admin can set a Sewadar PIN and clear a lockout', async () => {
  const sewadar = await makeSewadar();

  const setPin = await call('POST', `/sewadars/${sewadar.id}/set-pin`, { token: admin, body: { pin: '8351' } });
  assert.equal(setPin.status, 200);
  assert.ok(await login(sewadar.id, '8351'), 'the new PIN works');

  const unlock = await call('POST', `/sewadars/${sewadar.id}/unlock`, { token: admin });
  assert.equal(unlock.status, 200);
});

test('three wrong PINs lock an account, and an admin releases it', async () => {
  const sewadar = await makeSewadar();
  const wrong = { identifier: sewadar.id, secret: '0000' };

  assert.equal((await call('POST', '/auth/login', { body: wrong })).status, 401);
  assert.equal((await call('POST', '/auth/login', { body: wrong })).status, 401);

  const third = await call('POST', '/auth/login', { body: wrong });
  assert.equal(third.status, 423);
  assert.match(third.body.error, /locked for 5 minutes/);

  // Even the right PIN is refused while the cooldown runs.
  const correct = await call('POST', '/auth/login', { body: { identifier: sewadar.id, secret: '5837' } });
  assert.equal(correct.status, 423);

  assert.equal((await call('POST', `/sewadars/${sewadar.id}/unlock`, { token: admin })).status, 200);
  assert.ok(await login(sewadar.id, '5837'), 'unlocking lets them straight back in');
});

test('a Sewadar with attendance history is deactivated rather than deleted', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');
  await call('POST', '/attendance/me/tap', { token, body: { action: 'IN', sewa_date: SEWA_DAY } });

  const res = await call('DELETE', `/sewadars/${sewadar.id}`, { token: admin });
  assert.equal(res.status, 200);
  assert.equal(res.body.deactivated, true, 'their sewa record must survive');

  const still = await call('GET', `/sewadars/${sewadar.id}`, { token: admin });
  assert.equal(still.body.sewadar.is_active, false);
});

// ---------------------------------------------------------------------------
// Attendance
// ---------------------------------------------------------------------------

test('a Sewadar taps in and out, and the day totals up', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  const day = await call('GET', '/attendance/me/day', { token });
  assert.equal(day.status, 200);
  assert.equal(day.body.nextAction, 'IN');
  assert.equal(day.body.record, null);

  const tapIn = await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });
  assert.equal(tapIn.status, 201);
  assert.ok(tapIn.body.record.in_time);
  assert.equal(tapIn.body.record.in_source, 'TAP');
  assert.equal(tapIn.body.record.status, 'IN');

  const tapOut = await call('POST', '/attendance/me/tap', { token, body: { action: 'OUT' } });
  assert.equal(tapOut.status, 201);
  assert.equal(tapOut.body.record.status, 'DONE');
  assert.ok(tapOut.body.record.hours >= 0);
});

test('signing in twice on one day is refused', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });
  const again = await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });

  assert.equal(again.status, 409);
  assert.match(again.body.error, /already recorded/);
});

test('signing out before signing in is refused', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  const res = await call('POST', '/attendance/me/tap', { token, body: { action: 'OUT' } });
  assert.equal(res.status, 409);
  assert.match(res.body.error, /sign in before signing out/);
});

test('a Sewadar may nudge their own times within the tolerance', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');
  await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });

  const thirtyMinutesEarlier = new Date(Date.now() - 30 * 60_000).toISOString();
  const res = await call('PATCH', '/attendance/me/day', {
    token,
    body: { sewa_date: SEWA_DAY, in_time: thirtyMinutesEarlier, notes: 'Came early to set up.' },
  });

  assert.equal(res.status, 200);
  assert.equal(new Date(res.body.record.in_time).toISOString(), thirtyMinutesEarlier);
  assert.ok(res.body.record.in_adjusted < 0, 'the record remembers it was moved earlier');
  assert.equal(res.body.record.notes, 'Came early to set up.');
});

test('a Sewadar may not move a time far beyond the tolerance', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');
  await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });

  const eightHoursEarlier = new Date(Date.now() - 8 * 3600_000).toISOString();
  const res = await call('PATCH', '/attendance/me/day', {
    token,
    body: { sewa_date: SEWA_DAY, in_time: eightHoursEarlier },
  });

  assert.equal(res.status, 400);
  assert.match(res.body.error, /ask an administrator/i);
});

test('a Sewadar can fill in a day they never tapped at all', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  const res = await call('PATCH', '/attendance/me/day', {
    token,
    body: {
      sewa_date: SEWA_DAY,
      in_time: `${SEWA_DAY}T04:00:00.000Z`,
      out_time: `${SEWA_DAY}T11:00:00.000Z`,
      notes: 'Forgot to use the app.',
    },
  });

  assert.equal(res.status, 200);
  assert.equal(res.body.record.hours, 7);
  assert.equal(res.body.record.in_source, 'MANUAL');
});

test('an out-time before the in-time is refused', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  const res = await call('PATCH', '/attendance/me/day', {
    token,
    body: {
      sewa_date: SEWA_DAY,
      in_time: `${SEWA_DAY}T11:00:00.000Z`,
      out_time: `${SEWA_DAY}T04:00:00.000Z`,
    },
  });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /must be after/);
});

test('a future sewa day cannot be recorded', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  const res = await call('POST', '/attendance/me/tap', {
    token,
    body: { action: 'IN', sewa_date: '2099-01-06' },
  });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /has not happened yet/);
});

test('a day far in the past is out of reach for a Sewadar', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');

  const res = await call('PATCH', '/attendance/me/day', {
    token,
    body: { sewa_date: '2020-01-01', in_time: '2020-01-01T04:00:00.000Z' },
  });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /Ask an administrator/);
});

test('an admin is not bound by the tolerance or the backfill window', async () => {
  const sewadar = await makeSewadar();

  const res = await call('PUT', '/attendance/records', {
    token: admin,
    body: {
      sewadar_id: sewadar.id,
      sewa_date: '2026-01-07',
      in_time: '2026-01-07T04:00:00.000Z',
      out_time: '2026-01-07T12:00:00.000Z',
      notes: 'Reconstructed from the paper register.',
    },
  });

  assert.equal(res.status, 200);
  assert.equal(res.body.record.hours, 8);
  assert.equal(res.body.record.in_source, 'ADMIN');
  assert.equal(res.body.record.recorded_by, 'ADM001');
});

test('the roster shows everyone for a day, present and absent alike', async () => {
  const res = await call('GET', `/attendance/roster?date=${SEWA_DAY}`, { token: admin });

  assert.equal(res.status, 200);
  assert.equal(res.body.sewaDate, SEWA_DAY);
  assert.ok(res.body.total > 0);
  assert.equal(res.body.total, res.body.roster.length);

  const absent = res.body.roster.filter((r) => r.status === 'ABSENT');
  assert.ok(absent.length > 0, 'a Sewadar with no record must still appear, marked absent');
  assert.ok(res.body.roster.every((r) => r.sewa_date === SEWA_DAY));
});

test('the roster can be narrowed to one department', async () => {
  const departments = (await call('GET', '/departments', { token: admin })).body.departments;
  const traffic = departments.find((d) => d.name === 'Traffic');

  const res = await call('GET', `/attendance/roster?date=${SEWA_DAY}&department_id=${traffic.id}`, { token: admin });
  assert.ok(res.body.roster.length > 0);
  assert.ok(res.body.roster.every((r) => r.department_id === traffic.id));
});

test('a whole department can be marked in one request', async () => {
  const a = await makeSewadar();
  const b = await makeSewadar();

  const res = await call('POST', '/attendance/records/bulk', {
    token: admin,
    body: {
      sewa_date: SEWA_DAY,
      entries: [
        { sewadar_id: a.id, in_time: `${SEWA_DAY}T04:00:00.000Z`, out_time: `${SEWA_DAY}T11:00:00.000Z` },
        { sewadar_id: b.id, in_time: `${SEWA_DAY}T04:30:00.000Z`, out_time: `${SEWA_DAY}T10:30:00.000Z` },
      ],
    },
  });

  assert.equal(res.status, 200);
  assert.equal(res.body.saved, 2);
  assert.equal(res.body.failed.length, 0);
});

test('one bad row in a bulk save does not discard the good ones', async () => {
  const good = await makeSewadar();

  const res = await call('POST', '/attendance/records/bulk', {
    token: admin,
    body: {
      sewa_date: SEWA_DAY,
      entries: [
        { sewadar_id: good.id, in_time: `${SEWA_DAY}T04:00:00.000Z` },
        { sewadar_id: 'GHOST999', in_time: `${SEWA_DAY}T04:00:00.000Z` },
      ],
    },
  });

  assert.equal(res.body.saved, 1);
  assert.equal(res.body.failed.length, 1);
  assert.equal(res.body.failed[0].sewadar_id, 'GHOST999');
});

test('deleting a record needs a reason', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');
  const tapped = await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });
  const id = tapped.body.record.id;

  assert.equal((await call('DELETE', `/attendance/records/${id}`, { token: admin })).status, 400);

  const deleted = await call('DELETE', `/attendance/records/${id}?reason=duplicate+entry`, { token: admin });
  assert.equal(deleted.status, 200);
});

// ---------------------------------------------------------------------------
// Kiosk
// ---------------------------------------------------------------------------

test('a counter tablet is provisioned and signs a Sewadar in and out', async () => {
  const provision = await call('POST', '/kiosk/provision', {
    token: admin,
    body: { station_name: 'Main gate counter' },
  });
  assert.equal(provision.status, 200);
  const kiosk = provision.body.token;

  const session = await call('GET', '/kiosk/session', { token: kiosk });
  assert.equal(session.body.station.name, 'Main gate counter');

  const sewadar = await makeSewadar();

  const signIn = await call('POST', '/kiosk/punch', {
    token: kiosk,
    body: { sewadar_id: sewadar.id, pin: '5837' },
  });
  assert.equal(signIn.status, 201);
  assert.equal(signIn.body.action, 'IN');
  assert.equal(signIn.body.record.in_source, 'KIOSK');

  // The station works out the direction on its own the second time.
  const signOut = await call('POST', '/kiosk/punch', {
    token: kiosk,
    body: { sewadar_id: sewadar.id, pin: '5837' },
  });
  assert.equal(signOut.body.action, 'OUT');
});

test('a station pinned to a department only lists that department', async () => {
  const departments = (await call('GET', '/departments', { token: admin })).body.departments;
  const safai = departments.find((d) => d.name === 'Safai Sewa Samiti');

  const provision = await call('POST', '/kiosk/provision', {
    token: admin,
    body: { department_id: safai.id },
  });
  const kiosk = provision.body.token;

  const list = await call('GET', '/kiosk/sewadars', { token: kiosk });
  assert.ok(list.body.sewadars.length > 0);
  assert.ok(list.body.sewadars.every((s) => s.department_name === 'Safai Sewa Samiti'));
});

test('the kiosk list carries no phone numbers or emails', async () => {
  const kiosk = (await call('POST', '/kiosk/provision', { token: admin, body: {} })).body.token;
  const res = await call('GET', '/kiosk/sewadars', { token: kiosk });

  assert.ok(res.body.sewadars.length > 0);
  for (const sewadar of res.body.sewadars) {
    assert.deepEqual(Object.keys(sewadar).sort(), ['department_name', 'full_name', 'id']);
  }
});

test('a station token and a person token are not interchangeable', async () => {
  const kiosk = (await call('POST', '/kiosk/provision', { token: admin, body: {} })).body.token;

  assert.equal((await call('GET', '/sewadars', { token: kiosk })).status, 403);
  assert.equal((await call('GET', '/kiosk/sewadars', { token: admin })).status, 403);
});

test('a kiosk signature is stored with the record', async () => {
  const kiosk = (await call('POST', '/kiosk/provision', { token: admin, body: {} })).body.token;
  const sewadar = await makeSewadar();

  const res = await call('POST', '/kiosk/punch', {
    token: kiosk,
    body: {
      sewadar_id: sewadar.id,
      pin: '5837',
      signature: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUg==',
    },
  });
  assert.equal(res.status, 201);
  assert.ok(res.body.record.signature_blob.startsWith('data:image/png;base64,'));
});

test('a signature that is not an image data URL is refused', async () => {
  const kiosk = (await call('POST', '/kiosk/provision', { token: admin, body: {} })).body.token;
  const sewadar = await makeSewadar();

  const res = await call('POST', '/kiosk/punch', {
    token: kiosk,
    body: { sewadar_id: sewadar.id, pin: '5837', signature: '<script>bad</script>' },
  });
  assert.equal(res.status, 400);
});

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

test('the summary reports every department, even one with no attendance', async () => {
  const res = await call('GET', `/reports/summary?startDate=2026-01-01&endDate=${toSewaDate()}`, { token: admin });

  assert.equal(res.status, 200);
  assert.ok(res.body.totals.records > 0);
  assert.ok(res.body.departments.length >= 7);

  const traffic = res.body.departments.find((d) => d.departmentName === 'Traffic');
  assert.ok(traffic.sewadarCount > 0);
  assert.equal(traffic.hodName, 'Gurmeet Singh');
  assert.ok(Number.isFinite(traffic.totalHours));
});

test('the summary flags Sewadars who never signed out', async () => {
  const sewadar = await makeSewadar();
  const token = await login(sewadar.id, '5837');
  await call('POST', '/attendance/me/tap', { token, body: { action: 'IN' } });

  const res = await call('GET', `/reports/summary?startDate=${SEWA_DAY}&endDate=${SEWA_DAY}`, { token: admin });
  const noExit = res.body.exceptions.filter((e) => e.type === 'No exit time');
  assert.ok(noExit.some((e) => e.sewadarId === sewadar.id));
});

test('the excel export downloads as a real xlsx workbook', async () => {
  const res = await fetch(`${base}/reports/excel?startDate=2026-01-01&endDate=${toSewaDate()}`, {
    headers: { authorization: `Bearer ${admin}` },
  });

  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type'), /spreadsheetml\.sheet/);
  assert.match(res.headers.get('content-disposition'), /filename="sewa_attendance_/);

  const buffer = Buffer.from(await res.arrayBuffer());
  assert.ok(buffer.length > 5000, `workbook looks empty (${buffer.length} bytes)`);
  assert.equal(buffer.subarray(0, 2).toString(), 'PK', 'every xlsx is a zip');
});

test('the workbook carries a sheet per department plus the register', async () => {
  const ExcelJS = (await import('exceljs')).default;
  const res = await fetch(`${base}/reports/excel?startDate=2026-01-01&endDate=${toSewaDate()}`, {
    headers: { authorization: `Bearer ${admin}` },
  });

  const wb = new ExcelJS.Workbook();
  await wb.xlsx.load(Buffer.from(await res.arrayBuffer()));
  const names = wb.worksheets.map((s) => s.name);

  for (const expected of ['Summary', 'Register', 'All Entries', 'Exceptions', 'Report Info']) {
    assert.ok(names.includes(expected), `expected a ${expected} sheet, got ${names.join(', ')}`);
  }
  for (const department of ['Account', 'Traffic', 'Safai Sewa Samiti']) {
    assert.ok(names.includes(department), `expected a ${department} sheet`);
  }

  // The register puts Sewadars down the side and sewa days across the top.
  const register = wb.getWorksheet('Register');
  const header = register.getRow(1).values.filter(Boolean).map(String);
  assert.ok(header[0].startsWith('Department'));
  assert.ok(register.rowCount > 1, 'the register has rows');
});

test('an invalid date range is refused rather than silently returning nothing', async () => {
  const res = await call('GET', '/reports/summary?startDate=2026-09-01&endDate=2026-01-01', { token: admin });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /must not be after/);
});
