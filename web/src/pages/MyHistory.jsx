import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api.js';
import { useAuth } from '../lib/auth.jsx';
import { Alert, EmptyRow, LoadingScreen, SourceBadge, StatusBadge } from '../components/ui.jsx';
import { formatShortDate, formatTime, formatHours, describeAdjustment } from '../lib/format.js';

export default function MyHistoryPage() {
  const { user } = useAuth();
  const [days, setDays] = useState(null);
  const [extra, setExtra] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/attendance/me/recent?count=40')
      .then((data) => {
        setDays(data.days);
        setExtra(data.extraDays);
      })
      .catch((err) => { setError(err.message); setDays([]); });
  }, []);

  const totals = useMemo(() => {
    if (!days) return { attended: 0, hours: 0, missed: 0, open: 0 };
    const rows = [...days.map((d) => d.record), ...extra].filter(Boolean);
    return {
      attended: rows.filter((r) => r.in_time).length,
      hours: rows.reduce((total, r) => total + (r.hours || 0), 0),
      missed: days.filter((d) => !d.record?.in_time).length,
      open: rows.filter((r) => r.in_time && !r.out_time).length,
    };
  }, [days, extra]);

  if (days === null) return <LoadingScreen label="Loading your sewa history…" />;

  return (
    <div className="stack">
      <div className="page-header">
        <h1>My sewa history</h1>
        <p>
          {user.department_name || 'No department'} · the last {days.length} scheduled sewa days
          {extra.length > 0 && `, plus ${extra.length} extra sewa day${extra.length === 1 ? '' : 's'}`}.
        </p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <div className="stats">
        <Tile label="Days attended" value={totals.attended} />
        <Tile label="Total hours" value={formatHours(totals.hours)} />
        <Tile label="Days missed" value={totals.missed} />
        <Tile
          label="No sign-out"
          value={totals.open}
          note={totals.open ? 'Correct these from My sewa' : 'All complete'}
        />
      </div>

      <div className="card">
        <div className="card-header"><h2>Scheduled sewa days</h2></div>
        <HistoryTable rows={days.map((d) => ({ sewaDate: d.sewaDate, record: d.record }))} />
      </div>

      {extra.length > 0 && (
        <div className="card">
          <div className="card-header"><h2>Extra sewa</h2></div>
          <HistoryTable rows={extra.map((r) => ({ sewaDate: r.sewa_date, record: r }))} />
        </div>
      )}
    </div>
  );
}

function HistoryTable({ rows }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Day</th>
            <th>In</th>
            <th>Out</th>
            <th>Hours</th>
            <th>Status</th>
            <th>Recorded</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <EmptyRow colSpan={6}>Nothing recorded yet.</EmptyRow>
          ) : (
            rows.map(({ sewaDate, record }) => {
              const adjustment = record
                ? describeAdjustment(record.in_adjusted) || describeAdjustment(record.out_adjusted)
                : null;
              return (
                <tr key={sewaDate}>
                  <td data-label="Day"><strong>{formatShortDate(sewaDate)}</strong></td>
                  <td data-label="In" className="mono">{record?.in_time ? formatTime(record.in_time) : '—'}</td>
                  <td data-label="Out" className="mono">{record?.out_time ? formatTime(record.out_time) : '—'}</td>
                  <td data-label="Hours" className="mono">{formatHours(record?.hours)}</td>
                  <td data-label="Status"><StatusBadge status={record?.status || 'ABSENT'} /></td>
                  <td data-label="Recorded">
                    <SourceBadge source={record?.in_source} />
                    {adjustment && <div className="faint">{adjustment}</div>}
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}

function Tile({ label, value, note }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
      {note && <div className="stat-note">{note}</div>}
    </div>
  );
}
