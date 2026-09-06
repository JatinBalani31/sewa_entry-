import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api.js';
import { Alert, ChipRow, Spinner, StatusBadge, Stat } from '../../components/ui.jsx';
import { formatDate, formatHours, instantToTime, timeToInstant, isoDate } from '../../lib/format.js';

/**
 * The register: one sewa day, one department at a time, every Sewadar in it
 * with editable in and out times.
 *
 * This is the screen an admin actually works from on a tablet, so it is built
 * as a list of rows with time inputs rather than a spreadsheet — and it saves
 * only what changed, in one request.
 */
export default function RegisterPage() {
  const [date, setDate] = useState(isoDate());
  const [departmentId, setDepartmentId] = useState('');
  const [departments, setDepartments] = useState([]);

  const [meta, setMeta] = useState(null);
  const [rows, setRows] = useState([]);
  const [draft, setDraft] = useState({});      // sewadar_id -> { in, out }
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    api.get('/departments')
      .then((data) => setDepartments(data.departments))
      .catch((err) => setError(err.message));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const query = new URLSearchParams({ date, ...(departmentId ? { department_id: departmentId } : {}) });
      const data = await api.get(`/attendance/roster?${query}`);
      setMeta(data);
      setRows(data.roster);
      // Editing starts from what is stored, so an untouched row saves nothing.
      setDraft(Object.fromEntries(data.roster.map((r) => [
        r.sewadar_id,
        { in: instantToTime(r.in_time), out: instantToTime(r.out_time) },
      ])));
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [date, departmentId]);

  useEffect(() => { load(); }, [load]);

  const setTime = (sewadarId, field, value) =>
    setDraft((d) => ({ ...d, [sewadarId]: { ...d[sewadarId], [field]: value } }));

  // Only rows whose times actually differ from what is stored get sent.
  const changed = useMemo(() => rows.filter((row) => {
    const edit = draft[row.sewadar_id];
    if (!edit) return false;
    return edit.in !== instantToTime(row.in_time) || edit.out !== instantToTime(row.out_time);
  }), [rows, draft]);

  const invalid = changed.filter((row) => {
    const edit = draft[row.sewadar_id];
    return (edit.out && !edit.in) || (edit.in && edit.out && edit.out <= edit.in);
  });

  const save = async () => {
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await api.post('/attendance/records/bulk', {
        body: {
          sewa_date: date,
          entries: changed.map((row) => ({
            sewadar_id: row.sewadar_id,
            in_time: draft[row.sewadar_id].in ? timeToInstant(date, draft[row.sewadar_id].in) : null,
            out_time: draft[row.sewadar_id].out ? timeToInstant(date, draft[row.sewadar_id].out) : null,
          })),
        },
      });
      setNotice(result.message);
      if (result.failed.length) {
        setError(result.failed.map((f) => `${f.sewadar_id}: ${f.error}`).join(' · '));
      }
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  /** Stamps a standard sewa shift onto everyone not yet marked. */
  const fillAll = () => {
    setDraft((d) => {
      const next = { ...d };
      for (const row of rows) {
        if (!next[row.sewadar_id].in) next[row.sewadar_id] = { in: '09:30', out: '16:30' };
      }
      return next;
    });
  };

  const clearAll = () => {
    setDraft(Object.fromEntries(rows.map((r) => [r.sewadar_id, { in: '', out: '' }])));
  };

  return (
    <div className="stack">
      <div className="page-header">
        <h1>Attendance register</h1>
        <p>Mark in and out times for a whole department in one go. Blank means absent.</p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>
      <Alert kind="success" onDismiss={() => setNotice('')}>{notice}</Alert>

      <div className="card">
        <div className="filters">
          <div>
            <label htmlFor="reg-date">Sewa day</label>
            <input
              id="reg-date"
              type="date"
              value={date}
              max={isoDate()}
              onChange={(e) => setDate(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor="reg-dept">Department</label>
            <select id="reg-dept" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              <option value="">All departments</option>
              {departments.map((d) => (
                <option key={d.id} value={d.id}>{d.name} ({d.sewadar_count})</option>
              ))}
            </select>
          </div>
        </div>

        {meta && (
          <div className="row" style={{ marginTop: '0.8rem' }}>
            <span className={`badge ${meta.isSewaDay ? 'badge-present' : 'badge-in'}`}>
              {meta.dayName}{meta.isSewaDay ? '' : ' — extra sewa'}
            </span>
            <span className="faint">{formatDate(meta.sewaDate)}</span>
          </div>
        )}
      </div>

      {meta && (
        <div className="stats">
          <Stat label="Present" value={meta.present} tone="green" note={`of ${meta.total} Sewadars`} />
          <Stat label="Still in" value={meta.stillIn} tone={meta.stillIn ? 'amber' : undefined} />
          <Stat label="Absent" value={meta.total - meta.present} />
          <Stat label="Unsaved" value={changed.length} tone={changed.length ? 'blue' : undefined} />
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2>{loading ? <Spinner label="Loading…" /> : `${rows.length} Sewadar${rows.length === 1 ? '' : 's'}`}</h2>
          <div className="row">
            <button type="button" className="btn-secondary btn-sm" onClick={fillAll} disabled={loading}>
              Fill unmarked
            </button>
            <button type="button" className="btn-secondary btn-sm" onClick={clearAll} disabled={loading}>
              Clear all
            </button>
          </div>
        </div>

        {rows.length === 0 && !loading ? (
          <p className="faint center" style={{ padding: '1.5rem 0', margin: 0 }}>
            No Sewadars {departmentId ? 'in this department' : 'yet'}.
          </p>
        ) : (
          rows.map((row) => {
            const edit = draft[row.sewadar_id] || { in: '', out: '' };
            const state = !edit.in ? 'absent' : edit.out ? 'present' : 'partial';
            const rowInvalid = invalid.some((r) => r.sewadar_id === row.sewadar_id);

            return (
              <div className={`register-row ${state}`} key={row.sewadar_id}>
                <div className="who">
                  <strong>{row.sewadar_name}</strong>
                  <span>
                    {row.sewadar_id}
                    {!departmentId && row.department_name ? ` · ${row.department_name}` : ''}
                  </span>
                </div>

                <div className="register-times">
                  <div>
                    <label htmlFor={`in-${row.sewadar_id}`}>In</label>
                    <input
                      id={`in-${row.sewadar_id}`}
                      type="time"
                      value={edit.in}
                      onChange={(e) => setTime(row.sewadar_id, 'in', e.target.value)}
                    />
                  </div>
                  <div>
                    <label htmlFor={`out-${row.sewadar_id}`}>Out</label>
                    <input
                      id={`out-${row.sewadar_id}`}
                      type="time"
                      value={edit.out}
                      onChange={(e) => setTime(row.sewadar_id, 'out', e.target.value)}
                    />
                  </div>
                  <div style={{ paddingBottom: '0.15rem' }}>
                    {rowInvalid
                      ? <span className="badge badge-admin">Check times</span>
                      : <StatusBadge status={state === 'present' ? 'DONE' : state === 'partial' ? 'IN' : 'ABSENT'} />}
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>

      {changed.length > 0 && (
        <div className="card">
          <div className="row">
            <span className="muted">
              {changed.length} change{changed.length === 1 ? '' : 's'} not yet saved
              {invalid.length > 0 && ` · ${invalid.length} need fixing first`}
            </span>
            <button
              type="button"
              className="btn-primary row-end"
              onClick={save}
              disabled={saving || invalid.length > 0}
            >
              {saving ? 'Saving…' : `Save ${changed.length} change${changed.length === 1 ? '' : 's'}`}
            </button>
          </div>
          {invalid.length > 0 && (
            <Alert kind="warn">
              An out-time needs an in-time before it, and must come after it.
            </Alert>
          )}
        </div>
      )}
    </div>
  );
}
