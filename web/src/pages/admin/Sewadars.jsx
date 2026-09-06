import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api.js';
import { useAuth } from '../../lib/auth.jsx';
import { Alert, ChipRow, EmptyRow, Modal, Spinner } from '../../components/ui.jsx';
import { formatDate } from '../../lib/format.js';

export default function SewadarsPage() {
  const { user: me } = useAuth();

  const [sewadars, setSewadars] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [search, setSearch] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [editing, setEditing] = useState(null);     // sewadar or {} for new
  const [credentialFor, setCredentialFor] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const query = new URLSearchParams({
        ...(search ? { search } : {}),
        ...(departmentId ? { department_id: departmentId } : {}),
      });
      const [sewadarData, departmentData] = await Promise.all([
        api.get(`/sewadars?${query}`),
        api.get('/departments'),
      ]);
      setSewadars(sewadarData.sewadars);
      setDepartments(departmentData.departments);
      setError('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [search, departmentId]);

  useEffect(() => {
    const handle = setTimeout(load, search ? 250 : 0);
    return () => clearTimeout(handle);
  }, [load, search]);

  const act = async (fn, fallback) => {
    try {
      const result = await fn();
      setNotice(result?.message || fallback);
      load();
    } catch (err) {
      setError(err.message);
    }
  };

  const remove = (sewadar) => {
    if (!window.confirm(
      `Remove ${sewadar.full_name} (${sewadar.id})?\n\nIf they have any sewa history they will be made inactive instead of deleted, so their records survive.`,
    )) return;
    act(() => api.del(`/sewadars/${sewadar.id}`), 'Sewadar removed.');
  };

  return (
    <div className="stack">
      <div className="page-header">
        <h1>Sewadars</h1>
        <p>Everyone on the roster, their department, and the credentials they sign in with.</p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>
      <Alert kind="success" onDismiss={() => setNotice('')}>{notice}</Alert>

      <div className="card">
        <div className="field" style={{ marginBottom: '0.7rem' }}>
          <input
            type="search"
            placeholder="Search by name, ID or phone…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        <ChipRow
          value={departmentId}
          onChange={setDepartmentId}
          options={[
            { value: '', label: 'All' },
            ...departments.map((d) => ({ value: String(d.id), label: `${d.name} (${d.sewadar_count})` })),
            { value: 'none', label: 'No department' },
          ]}
        />

        <div className="row" style={{ marginTop: '0.8rem' }}>
          <span className="faint">
            {loading ? 'Loading…' : `${sewadars.length} Sewadar${sewadars.length === 1 ? '' : 's'}`}
          </span>
          <button type="button" className="btn-primary row-end" onClick={() => setEditing({})}>
            + Add Sewadar
          </button>
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Sewadar</th>
              <th>Department</th>
              <th>Phone</th>
              <th>Role</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {loading && sewadars.length === 0 ? (
              <EmptyRow colSpan={6}><Spinner label="Loading Sewadars…" /></EmptyRow>
            ) : sewadars.length === 0 ? (
              <EmptyRow colSpan={6}>No Sewadars match this search.</EmptyRow>
            ) : (
              sewadars.map((sewadar) => (
                <tr key={sewadar.id}>
                  <td data-label="Sewadar">
                    <strong>{sewadar.full_name}</strong>
                    {sewadar.id === me.id && <span className="faint"> (you)</span>}
                    <br />
                    <span className="faint mono">{sewadar.id}</span>
                  </td>
                  <td data-label="Department">{sewadar.department_name || <span className="faint">Unassigned</span>}</td>
                  <td data-label="Phone" className="mono">{sewadar.phone || '—'}</td>
                  <td data-label="Role">
                    <span className={`badge ${sewadar.role === 'ADMIN' ? 'badge-admin' : 'badge-neutral'}`}>
                      {sewadar.role === 'ADMIN' ? 'Admin' : 'Sewadar'}
                    </span>
                  </td>
                  <td data-label="Status">
                    {!sewadar.is_active
                      ? <span className="badge badge-absent">Inactive</span>
                      : sewadar.is_locked
                        ? <span className="badge badge-admin">Locked</span>
                        : <span className="badge badge-present">Active</span>}
                  </td>
                  <td data-label="">
                    <div className="row nowrap">
                      <button type="button" className="btn-ghost btn-sm" onClick={() => setEditing(sewadar)}>Edit</button>
                      <button type="button" className="btn-ghost btn-sm" onClick={() => setCredentialFor(sewadar)}>PIN</button>
                      {sewadar.is_locked && (
                        <button
                          type="button"
                          className="btn-ghost btn-sm"
                          onClick={() => act(() => api.post(`/sewadars/${sewadar.id}/unlock`), 'Unlocked.')}
                        >
                          Unlock
                        </button>
                      )}
                      <button
                        type="button"
                        className="btn-ghost btn-sm"
                        onClick={() => act(
                          () => api.patch(`/sewadars/${sewadar.id}`, { body: { is_active: !sewadar.is_active } }),
                          'Updated.',
                        )}
                      >
                        {sewadar.is_active ? 'Deactivate' : 'Reactivate'}
                      </button>
                      {sewadar.id !== me.id && (
                        <button type="button" className="btn-ghost btn-sm" onClick={() => remove(sewadar)}>Remove</button>
                      )}
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {editing && (
        <SewadarModal
          sewadar={editing.id ? editing : null}
          departments={departments}
          onClose={() => setEditing(null)}
          onDone={(message) => { setEditing(null); setNotice(message); load(); }}
        />
      )}

      {credentialFor && (
        <CredentialModal
          sewadar={credentialFor}
          onClose={() => setCredentialFor(null)}
          onDone={(message) => { setCredentialFor(null); setNotice(message); load(); }}
        />
      )}
    </div>
  );
}

function SewadarModal({ sewadar, departments, onClose, onDone }) {
  const isEdit = Boolean(sewadar);
  const [form, setForm] = useState({
    id: sewadar?.id || '',
    full_name: sewadar?.full_name || '',
    phone: sewadar?.phone || '',
    email: sewadar?.email || '',
    department_id: sewadar?.department_id ? String(sewadar.department_id) : '',
    role: sewadar?.role || 'SEWADAR',
    pin: '',
    password: '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const set = (key) => (event) => setForm((f) => ({ ...f, [key]: event.target.value }));

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      if (isEdit) {
        await api.patch(`/sewadars/${sewadar.id}`, {
          body: {
            full_name: form.full_name,
            phone: form.phone || null,
            email: form.email || null,
            department_id: form.department_id ? Number(form.department_id) : null,
            role: form.role,
          },
        });
        onDone(`${form.full_name} updated.`);
      } else {
        await api.post('/sewadars', {
          body: {
            id: form.id.trim().toUpperCase(),
            full_name: form.full_name,
            phone: form.phone || null,
            email: form.email || null,
            department_id: form.department_id ? Number(form.department_id) : null,
            role: form.role,
            pin: form.pin,
            ...(form.role === 'ADMIN' ? { password: form.password } : {}),
          },
        });
        onDone(`${form.full_name} added. Give them their PIN in person, not over a message.`);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={isEdit ? `Edit ${sewadar.full_name}` : 'Add a Sewadar'}
      subtitle={isEdit
        ? 'Credentials are changed separately, from the PIN button.'
        : 'The PIN is what they type on the counter tablet and to sign in on their phone.'}
      onClose={onClose}
    >
      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <form onSubmit={submit}>
        {!isEdit && (
          <div className="field">
            <label htmlFor="sw-id">Sewadar ID</label>
            <input
              id="sw-id"
              value={form.id}
              onChange={set('id')}
              placeholder="SW1001"
              pattern="[A-Za-z0-9_-]+"
              autoCapitalize="characters"
              required
              autoFocus
            />
            <div className="field-hint">Shown on the counter tablet. Letters, numbers, hyphens and underscores.</div>
          </div>
        )}

        <div className="field">
          <label htmlFor="sw-name">Full name</label>
          <input id="sw-name" value={form.full_name} onChange={set('full_name')} required />
        </div>

        <div className="field-row">
          <div className="field">
            <label htmlFor="sw-phone">Phone (optional)</label>
            <input id="sw-phone" type="tel" inputMode="tel" value={form.phone} onChange={set('phone')} />
          </div>
          <div className="field">
            <label htmlFor="sw-dept">Department</label>
            <select id="sw-dept" value={form.department_id} onChange={set('department_id')}>
              <option value="">Unassigned</option>
              {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </div>
        </div>

        <div className="field">
          <label htmlFor="sw-email">Email (optional)</label>
          <input id="sw-email" type="email" value={form.email} onChange={set('email')} />
        </div>

        <div className="field">
          <label htmlFor="sw-role">Role</label>
          <select id="sw-role" value={form.role} onChange={set('role')}>
            <option value="SEWADAR">Sewadar</option>
            <option value="ADMIN">Administrator</option>
          </select>
          <div className="field-hint">
            An administrator can manage departments, Sewadars and the whole register.
          </div>
        </div>

        {!isEdit && (
          <>
            <div className="field">
              <label htmlFor="sw-pin">Counter PIN</label>
              <input
                id="sw-pin"
                inputMode="numeric"
                value={form.pin}
                onChange={(e) => setForm((f) => ({ ...f, pin: e.target.value.replace(/\D/g, '') }))}
                required
              />
              <div className="field-hint">4–12 digits. Not a repeat (1111) and not a run (1234).</div>
            </div>

            {form.role === 'ADMIN' && (
              <div className="field">
                <label htmlFor="sw-password">Console password</label>
                <input
                  id="sw-password"
                  type="password"
                  value={form.password}
                  onChange={set('password')}
                  required
                />
                <div className="field-hint">
                  At least 8 characters, with letters. This — not the PIN — is what opens the admin console.
                </div>
              </div>
            )}
          </>
        )}

        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={busy}>
            {busy ? 'Saving…' : isEdit ? 'Save changes' : 'Add Sewadar'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

/** Sets a Sewadar's counter PIN, and an administrator's console password. */
function CredentialModal({ sewadar, onClose, onDone }) {
  const [pin, setPin] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const done = [];
      if (pin) {
        await api.post(`/sewadars/${sewadar.id}/set-pin`, { body: { pin } });
        done.push('PIN');
      }
      if (password) {
        await api.post(`/sewadars/${sewadar.id}/set-password`, { body: { password } });
        done.push('console password');
      }
      onDone(`${done.join(' and ')} set for ${sewadar.full_name}. Hand it over in person.`);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={`Credentials — ${sewadar.full_name}`}
      subtitle="Setting either one also clears any active lockout. Leave a field blank to leave it unchanged."
      onClose={onClose}
    >
      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="cred-pin">Counter PIN</label>
          <input
            id="cred-pin"
            inputMode="numeric"
            value={pin}
            onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
            autoFocus
          />
          <div className="field-hint">4–12 digits, not repeated and not sequential.</div>
        </div>

        <div className="field">
          <label htmlFor="cred-password">
            Console password {sewadar.role !== 'ADMIN' && '(only needed for administrators)'}
          </label>
          <input
            id="cred-password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <div className="field-hint">
            {sewadar.has_password
              ? 'This account already has a password; entering one here replaces it.'
              : 'At least 8 characters, with letters.'}
          </div>
        </div>

        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={busy || (!pin && !password)}>
            {busy ? 'Saving…' : 'Set credentials'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
