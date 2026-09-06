import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api.js';
import { Alert, EmptyRow, Spinner, Stat } from '../../components/ui.jsx';
import { daysAgo, isoDate, startOfMonth, formatHours, formatShortDate } from '../../lib/format.js';

const PRESETS = [
  { label: 'Last 4 weeks', from: () => daysAgo(27), to: () => isoDate() },
  { label: 'This month', from: () => startOfMonth(), to: () => isoDate() },
  { label: 'Last 3 months', from: () => daysAgo(89), to: () => isoDate() },
  { label: 'Last 12 months', from: () => daysAgo(364), to: () => isoDate() },
];

export default function ReportsPage() {
  const [range, setRange] = useState({ startDate: daysAgo(27), endDate: isoDate(), departmentId: '', sewadarId: '' });
  const [departments, setDepartments] = useState([]);
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [tab, setTab] = useState('departments');

  useEffect(() => {
    api.get('/departments')
      .then((d) => setDepartments(d.departments))
      .catch((err) => setError(err.message));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const query = new URLSearchParams(
        Object.entries(range).filter(([, value]) => value !== ''),
      );
      setData(await api.get(`/reports/summary?${query}`));
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [range]);

  useEffect(() => { load(); }, [load]);

  const set = (key) => (event) => setRange((r) => ({ ...r, [key]: event.target.value }));

  const download = async () => {
    setDownloading(true);
    setError('');
    try {
      const filename = await api.downloadExcel(range);
      setNotice(`${filename} downloaded — a sheet per department, plus the register, all entries and exceptions.`);
    } catch (err) {
      setError(err.message);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div className="stack">
      <div className="page-header">
        <h1>Reports</h1>
        <p>Check the figures here, then export the department-wise workbook.</p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>
      <Alert kind="success" onDismiss={() => setNotice('')}>{notice}</Alert>

      <div className="card">
        <div className="chip-row" style={{ marginBottom: '0.8rem' }}>
          {PRESETS.map((preset) => (
            <button
              key={preset.label}
              type="button"
              className="chip"
              onClick={() => setRange((r) => ({ ...r, startDate: preset.from(), endDate: preset.to() }))}
            >
              {preset.label}
            </button>
          ))}
        </div>

        <div className="filters">
          <div>
            <label htmlFor="rep-from">From</label>
            <input id="rep-from" type="date" value={range.startDate} onChange={set('startDate')} />
          </div>
          <div>
            <label htmlFor="rep-to">To</label>
            <input id="rep-to" type="date" value={range.endDate} max={isoDate()} onChange={set('endDate')} />
          </div>
          <div className="full">
            <label htmlFor="rep-dept">Department</label>
            <select id="rep-dept" value={range.departmentId} onChange={set('departmentId')}>
              <option value="">All departments</option>
              {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
          <div className="full">
            <button type="button" className="btn-primary btn-block" onClick={download} disabled={downloading || loading}>
              {downloading ? 'Building the workbook…' : '↓ Export Excel'}
            </button>
          </div>
        </div>
      </div>

      {loading && !data ? (
        <div className="card"><Spinner label="Adding it up…" /></div>
      ) : data ? (
        <>
          <div className="stats">
            <Stat label="Sewa days" value={data.period.sewaDays.length} />
            <Stat label="Attendances" value={data.totals.attendances} tone="green" />
            <Stat label="Total hours" value={formatHours(data.totals.hours)} />
            <Stat
              label="To review"
              value={data.totals.exceptions}
              tone={data.totals.exceptions ? 'red' : undefined}
              note={data.totals.exceptions ? 'See the Exceptions tab' : 'All clean'}
            />
          </div>

          {data.period.sewaDays.length > 0 && (
            <div className="card">
              <div className="card-header"><h3>Sewa days in this period</h3></div>
              <div className="chip-row">
                {data.period.sewaDays.map((day) => (
                  <span className="chip" key={day}>{formatShortDate(day)}</span>
                ))}
              </div>
            </div>
          )}

          <div className="card">
            <div className="tabs">
              <button type="button" className={tab === 'departments' ? 'active' : ''} onClick={() => setTab('departments')}>
                Departments ({data.departments.length})
              </button>
              <button type="button" className={tab === 'exceptions' ? 'active' : ''} onClick={() => setTab('exceptions')}>
                Exceptions ({data.exceptions.length})
              </button>
            </div>

            {tab === 'departments' ? (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Department</th>
                      <th>HOD</th>
                      <th>Sewadars</th>
                      <th>Attendances</th>
                      <th>Turnout</th>
                      <th>Hours</th>
                      <th>No exit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.departments.length === 0 ? (
                      <EmptyRow colSpan={7}>No departments to report on.</EmptyRow>
                    ) : (
                      data.departments.map((row) => (
                        <tr key={row.departmentName}>
                          <td data-label="Department"><strong>{row.departmentName}</strong></td>
                          <td data-label="HOD">{row.hodName || <span className="faint">—</span>}</td>
                          <td data-label="Sewadars" className="mono">{row.sewadarCount}</td>
                          <td data-label="Attendances" className="mono">{row.attendances}</td>
                          <td data-label="Turnout">
                            {row.possible ? (
                              <span className={`badge ${turnoutClass(row.attendances / row.possible)}`}>
                                {Math.round((row.attendances / row.possible) * 100)}%
                              </span>
                            ) : <span className="faint">—</span>}
                          </td>
                          <td data-label="Hours" className="mono">{formatHours(row.totalHours)}</td>
                          <td data-label="No exit" className="mono">
                            {row.missingOutTime
                              ? <span className="badge badge-admin">{row.missingOutTime}</span>
                              : '—'}
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Day</th>
                      <th>Sewadar</th>
                      <th>Department</th>
                      <th>Issue</th>
                      <th>Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.exceptions.length === 0 ? (
                      <EmptyRow colSpan={5}>
                        Nothing to review — every sewa day in this period is complete.
                      </EmptyRow>
                    ) : (
                      data.exceptions.map((exception, index) => (
                        <tr key={`${exception.sewadarId}-${exception.sewaDate}-${index}`}>
                          <td data-label="Day">{formatShortDate(exception.sewaDate)}</td>
                          <td data-label="Sewadar">
                            <strong>{exception.sewadarName}</strong>
                            <br />
                            <span className="faint mono">{exception.sewadarId}</span>
                          </td>
                          <td data-label="Department">{exception.departmentName}</td>
                          <td data-label="Issue"><span className="badge badge-admin">{exception.type}</span></td>
                          <td data-label="Detail" className="muted">{exception.detail}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      ) : null}
    </div>
  );
}

function turnoutClass(ratio) {
  if (ratio >= 0.75) return 'badge-present';
  if (ratio >= 0.4) return 'badge-in';
  return 'badge-absent';
}
