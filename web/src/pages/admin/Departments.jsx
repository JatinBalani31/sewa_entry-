import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api.js';
import { Alert, EmptyRow, Modal, Spinner } from '../../components/ui.jsx';

export default function DepartmentsPage() {
  const [departments, setDepartments] = useState([]);
  const [sewadars, setSewadars] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [editing, setEditing] = useState(null);   // a department, or {} for a new one

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [departmentData, sewadarData] = await Promise.all([
        api.get('/departments'),
        api.get('/sewadars'),
      ]);
      setDepartments(departmentData.departments);
      setSewadars(sewadarData.sewadars);
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const close = async (department) => {
    const warning = department.sewadar_count > 0
      ? `${department.name} still has ${department.sewadar_count} Sewadar(s).\n\nClosing it will leave them without a department until you reassign them. Past attendance keeps this department's name in reports.\n\nContinue?`
      : `Close ${department.name}?\n\nPast attendance keeps this department's name in reports.`;
    if (!window.confirm(warning)) return;

    try {
      const result = await api.del(`/departments/${department.id}?force=true`);
      setNotice(result.message);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  return (
    <div className="stack">
      <div className="page-header">
        <h1>Departments</h1>
        <p>Every sewa team, its head, and how many Sewadars are on it.</p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>
      <Alert kind="success" onDismiss={() => setNotice('')}>{notice}</Alert>

      <div className="card">
        <div className="card-header">
          <h2>{loading ? <Spinner label="Loading…" /> : `${departments.length} departments`}</h2>
          <button type="button" className="btn-primary" onClick={() => setEditing({})}>+ Add department</button>
        </div>

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Code</th>
                <th>HOD</th>
                <th>Sewadars</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {departments.length === 0 && !loading ? (
                <EmptyRow colSpan={5}>
                  No departments yet. Add Accounts, Traffic, Langar and the rest to get started.
                </EmptyRow>
              ) : (
                departments.map((department) => (
                  <tr key={department.id}>
                    <td data-label="Name">
                      <strong>{department.name}</strong>
                      {department.description && <div className="faint">{department.description}</div>}
                    </td>
                    <td data-label="Code" className="mono">{department.code || '—'}</td>
                    <td data-label="HOD">
                      {department.hod_name
                        ? <>{department.hod_name}<br /><span className="faint mono">{department.hod_sewadar_id}</span></>
                        : <span className="faint">Not assigned</span>}
                    </td>
                    <td data-label="Sewadars" className="mono">{department.sewadar_count}</td>
                    <td data-label="">
                      <div className="row nowrap">
                        <button type="button" className="btn-ghost btn-sm" onClick={() => setEditing(department)}>Edit</button>
                        <button type="button" className="btn-ghost btn-sm" onClick={() => close(department)}>Close</button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <DepartmentModal
          department={editing.id ? editing : null}
          sewadars={sewadars}
          onClose={() => setEditing(null)}
          onDone={(message) => { setEditing(null); setNotice(message); load(); }}
        />
      )}
    </div>
  );
}

function DepartmentModal({ department, sewadars, onClose, onDone }) {
  const isEdit = Boolean(department);
  const [form, setForm] = useState({
    name: department?.name || '',
    code: department?.code || '',
    description: department?.description || '',
    hod_sewadar_id: department?.hod_sewadar_id || '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const set = (key) => (event) => setForm((f) => ({ ...f, [key]: event.target.value }));

  // The HOD is chosen from this department's own members where possible, but
  // any Sewadar is allowed — a head is sometimes appointed before the move.
  const members = sewadars.filter((s) => s.department_id === department?.id);
  const others = sewadars.filter((s) => s.department_id !== department?.id);

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const body = {
        name: form.name,
        code: form.code || null,
        description: form.description || null,
        hod_sewadar_id: form.hod_sewadar_id || null,
      };
      if (isEdit) {
        await api.patch(`/departments/${department.id}`, { body });
        onDone(`${form.name} updated.`);
      } else {
        await api.post('/departments', { body });
        onDone(`${form.name} added.`);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={isEdit ? `Edit ${department.name}` : 'Add a department'}
      subtitle="Departments group Sewadars and are how the Excel export is broken up."
      onClose={onClose}
    >
      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="dept-name">Name</label>
          <input id="dept-name" value={form.name} onChange={set('name')} placeholder="Traffic" required autoFocus />
        </div>

        <div className="field">
          <label htmlFor="dept-code">Short code (optional)</label>
          <input id="dept-code" value={form.code} onChange={set('code')} placeholder="TRF" maxLength={20} />
        </div>

        <div className="field">
          <label htmlFor="dept-hod">Head of department (optional)</label>
          <select id="dept-hod" value={form.hod_sewadar_id} onChange={set('hod_sewadar_id')}>
            <option value="">Not assigned</option>
            {members.length > 0 && (
              <optgroup label="In this department">
                {members.map((s) => <option key={s.id} value={s.id}>{s.full_name} ({s.id})</option>)}
              </optgroup>
            )}
            <optgroup label={members.length ? 'Other Sewadars' : 'Sewadars'}>
              {others.map((s) => <option key={s.id} value={s.id}>{s.full_name} ({s.id})</option>)}
            </optgroup>
          </select>
          <div className="field-hint">Recorded for reference — the HOD has no extra permissions yet.</div>
        </div>

        <div className="field">
          <label htmlFor="dept-desc">What this team does (optional)</label>
          <textarea
            id="dept-desc"
            value={form.description}
            onChange={set('description')}
            placeholder="Parking marshalling and crowd flow at the gates."
            maxLength={1000}
          />
        </div>

        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={busy || !form.name.trim()}>
            {busy ? 'Saving…' : isEdit ? 'Save changes' : 'Add department'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
