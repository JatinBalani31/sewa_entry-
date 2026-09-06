import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, tokenStore } from '../../lib/api.js';
import { offlineQueue } from '../../lib/offlineQueue.js';
import { Alert } from '../../components/ui.jsx';

/**
 * Turns the tablet this page is open on into a sewa counter.
 *
 * The station token is stored on the device, separate from the admin's own
 * session — so the admin signs out afterwards and the counter keeps working.
 */
export default function KioskSetupPage() {
  const navigate = useNavigate();

  const [departments, setDepartments] = useState([]);
  const [stationName, setStationName] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [paired, setPaired] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [queued, setQueued] = useState(offlineQueue.length);

  useEffect(() => {
    api.get('/departments')
      .then((data) => setDepartments(data.departments))
      .catch((err) => setError(err.message));

    if (tokenStore.kiosk) {
      api.kiosk.get('/kiosk/session')
        .then(setPaired)
        .catch(() => { tokenStore.kiosk = null; });
    }
  }, []);

  const pair = async () => {
    setBusy(true);
    setError('');
    try {
      const data = await api.post('/kiosk/provision', {
        body: {
          station_name: stationName.trim() || undefined,
          department_id: departmentId ? Number(departmentId) : null,
        },
      });
      tokenStore.kiosk = data.token;
      setPaired(await api.kiosk.get('/kiosk/session'));
      setNotice(`This device is now the "${data.station.name}" counter.`);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const unpair = () => {
    if (queued > 0 && !window.confirm(
      `${queued} entr${queued === 1 ? 'y is' : 'ies are'} still waiting to sync from this tablet. Unpairing now discards them. Continue?`,
    )) return;

    tokenStore.kiosk = null;
    offlineQueue.clear();
    setQueued(0);
    setPaired(null);
    setNotice('This device is no longer a sewa counter.');
  };

  return (
    <div className="stack" style={{ maxWidth: 720 }}>
      <div className="page-header">
        <h1>Sewa counter setup</h1>
        <p>
          Pair the tablet you are holding. Sewadars can then sign in on it with their
          ID and PIN — no personal phone needed.
        </p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>
      <Alert kind="success" onDismiss={() => setNotice('')}>{notice}</Alert>

      {paired ? (
        <div className="card">
          <div className="card-header">
            <h2>This device is paired</h2>
            <span className="badge badge-present">Active counter</span>
          </div>

          <p>
            Counter: <strong>{paired.station.name}</strong>
            {paired.station.departmentName && <> · {paired.station.departmentName} only</>}
          </p>

          {queued > 0 && <Alert kind="warn">{queued} entries captured offline are waiting to sync.</Alert>}

          <div className="row" style={{ marginTop: '0.9rem' }}>
            <button type="button" className="btn-primary" onClick={() => navigate('/kiosk')}>
              Open the counter screen →
            </button>
            <button type="button" className="btn-danger row-end" onClick={unpair}>Unpair</button>
          </div>

          <hr style={{ border: 'none', borderTop: '1px solid var(--line)', margin: '1.2rem 0' }} />

          <h3>Before you hand the tablet over</h3>
          <ul className="muted" style={{ fontSize: '0.89rem', paddingLeft: '1.1rem', lineHeight: 1.8, margin: 0 }}>
            <li>Open the counter screen, then put the browser in full-screen or guided access so it cannot be navigated away from.</li>
            <li>Turn the screen timeout off and leave the tablet on charge.</li>
            <li>Sign out of your own admin session — the counter keeps working on its own.</li>
            <li>If the Wi-Fi drops, entries are held on the tablet and sync by themselves once it returns.</li>
          </ul>
        </div>
      ) : (
        <div className="card">
          <div className="card-header"><h2>Pair this device</h2></div>

          <div className="field">
            <label htmlFor="station-name">Counter name</label>
            <input
              id="station-name"
              value={stationName}
              onChange={(e) => setStationName(e.target.value)}
              placeholder="Main gate counter"
              maxLength={60}
            />
            <div className="field-hint">Shown at the top of the counter screen. Optional.</div>
          </div>

          <div className="field">
            <label htmlFor="station-dept">Department</label>
            <select id="station-dept" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              <option value="">All departments</option>
              {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
            <div className="field-hint">
              Pin it to one team and the picker shows only that team — quicker for a
              department with its own counter.
            </div>
          </div>

          <Alert kind="info">
            Pair the tablet that will sit at the counter — the station token is stored
            in <em>this</em> browser, on <em>this</em> device.
          </Alert>

          <button type="button" className="btn-primary btn-block" onClick={pair} disabled={busy}>
            {busy ? 'Pairing…' : 'Pair this device'}
          </button>
        </div>
      )}
    </div>
  );
}
