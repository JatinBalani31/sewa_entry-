import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../lib/api.js';
import { useAuth } from '../../lib/auth.jsx';
import { Alert, EmptyRow, LoadingScreen, SourceBadge, Stat, StatusBadge } from '../../components/ui.jsx';
import { formatDate, formatTime, formatHours, relativeTime, isoDate } from '../../lib/format.js';

const REFRESH_MS = 60_000;

export default function DashboardPage() {
  const { sewa } = useAuth();

  const [date, setDate] = useState(isoDate());
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [refreshedAt, setRefreshedAt] = useState(null);

  const load = useCallback(async () => {
    try {
      const roster = await api.get(`/attendance/roster?date=${date}`);
      setData(roster);
      setRefreshedAt(new Date());
      setError('');
    } catch (err) {
      setError(err.message);
    }
  }, [date]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  if (!data) return <LoadingScreen label="Loading the day…" />;

  const present = data.roster.filter((r) => r.in_time);
  const absent = data.roster.filter((r) => !r.in_time);
  const hours = present.reduce((total, r) => total + (r.hours || 0), 0);

  // Group by department so an admin sees which teams turned out.
  const byDepartment = new Map();
  for (const row of data.roster) {
    const name = row.department_name || 'No department';
    if (!byDepartment.has(name)) byDepartment.set(name, { name, total: 0, present: 0, stillIn: 0 });
    const bucket = byDepartment.get(name);
    bucket.total++;
    if (row.in_time) bucket.present++;
    if (row.in_time && !row.out_time) bucket.stillIn++;
  }
  const departments = [...byDepartment.values()].sort((a, b) => b.present - a.present);

  return (
    <div className="stack">
      <div className="page-header">
        <h1>{data.dayName}, {formatDate(data.sewaDate)}</h1>
        <p>
          {data.isSewaDay ? 'A scheduled sewa day' : 'Not a scheduled sewa day'}
          {sewa?.schedule && ` · sewa runs ${sewa.schedule}`}
          {refreshedAt && ` · updated ${relativeTime(refreshedAt.toISOString())}`}
        </p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <div className="card">
        <div className="filters">
          <div>
            <label htmlFor="dash-date">Showing</label>
            <input id="dash-date" type="date" value={date} max={isoDate()} onChange={(e) => setDate(e.target.value)} />
          </div>
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <Link
              to="/admin/register"
              className="btn-primary btn-block center"
              style={{ display: 'block', textDecoration: 'none', padding: '0.6rem' }}
            >
              Open the register
            </Link>
          </div>
        </div>
      </div>

      <div className="stats">
        <Stat label="Present" value={present.length} tone="green" note={`of ${data.total} Sewadars`} />
        <Stat label="Still in" value={data.stillIn} tone={data.stillIn ? 'amber' : undefined} note="not signed out" />
        <Stat label="Absent" value={absent.length} />
        <Stat label="Sewa hours" value={formatHours(hours)} />
      </div>

      <div className="card">
        <div className="card-header"><h2>By department</h2></div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Department</th>
                <th>Present</th>
                <th>Still in</th>
                <th>Turnout</th>
              </tr>
            </thead>
            <tbody>
              {departments.length === 0 ? (
                <EmptyRow colSpan={4}>No departments yet.</EmptyRow>
              ) : (
                departments.map((department) => (
                  <tr key={department.name}>
                    <td data-label="Department"><strong>{department.name}</strong></td>
                    <td data-label="Present" className="mono">{department.present} / {department.total}</td>
                    <td data-label="Still in" className="mono">{department.stillIn || '—'}</td>
                    <td data-label="Turnout">
                      <span className={`badge ${department.present === 0 ? 'badge-absent' : department.present === department.total ? 'badge-present' : 'badge-in'}`}>
                        {department.total ? Math.round((department.present / department.total) * 100) : 0}%
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Present ({present.length})</h2>
          <Link to="/admin/reports" className="faint">Reports →</Link>
        </div>
        <RosterTable rows={present} emptyText="Nobody has signed in for this day yet." />
      </div>

      {absent.length > 0 && (
        <div className="card">
          <div className="card-header"><h2>Not marked ({absent.length})</h2></div>
          <RosterTable rows={absent} emptyText="Everyone is accounted for." showTimes={false} />
        </div>
      )}
    </div>
  );
}

function RosterTable({ rows, emptyText, showTimes = true }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Sewadar</th>
            <th>Department</th>
            {showTimes && <th>In</th>}
            {showTimes && <th>Out</th>}
            {showTimes && <th>Hours</th>}
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <EmptyRow colSpan={showTimes ? 6 : 3}>{emptyText}</EmptyRow>
          ) : (
            rows.map((row) => (
              <tr key={row.sewadar_id}>
                <td data-label="Sewadar">
                  <strong>{row.sewadar_name}</strong>
                  <br />
                  <span className="faint mono">{row.sewadar_id}</span>
                </td>
                <td data-label="Department">{row.department_name || '—'}</td>
                {showTimes && <td data-label="In" className="mono">{row.in_time ? formatTime(row.in_time) : '—'}</td>}
                {showTimes && <td data-label="Out" className="mono">{row.out_time ? formatTime(row.out_time) : '—'}</td>}
                {showTimes && <td data-label="Hours" className="mono">{formatHours(row.hours)}</td>}
                <td data-label="Status">
                  <StatusBadge status={row.status} />
                  {showTimes && row.in_source && (
                    <div style={{ marginTop: '0.25rem' }}><SourceBadge source={row.in_source} /></div>
                  )}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
