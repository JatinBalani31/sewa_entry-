import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, tokenStore } from '../lib/api.js';
import { offlineQueue } from '../lib/offlineQueue.js';
import SignaturePad from '../components/SignaturePad.jsx';
import { Alert, Spinner } from '../components/ui.jsx';
import { formatTime, formatDate } from '../lib/format.js';

const STEP = { PICK: 'pick', PIN: 'pin', SIGN: 'sign', RESULT: 'result' };
const PIN_MAX = 12;
// How long the confirmation stays up before the counter resets for the next
// person in the queue.
const RESULT_DWELL_MS = 4000;

export default function KioskPage() {
  const [session, setSession] = useState(null);
  const [booting, setBooting] = useState(true);
  const [bootError, setBootError] = useState('');

  useEffect(() => {
    if (!tokenStore.kiosk) {
      setBooting(false);
      return;
    }
    api.kiosk.get('/kiosk/session')
      .then(setSession)
      .catch((err) => {
        // An expired or revoked station token must not leave the tablet stuck.
        if (err.status === 401 || err.status === 403) tokenStore.kiosk = null;
        setBootError(err.message);
      })
      .finally(() => setBooting(false));
  }, []);

  if (booting) return <div className="loading-screen"><Spinner label="Starting the counter…" /></div>;
  if (!session) return <NotProvisioned error={bootError} />;

  return <Counter session={session} />;
}

function NotProvisioned({ error }) {
  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-logo">
          <div className="mark">🖥️</div>
          <h1>Sewa counter</h1>
          <p>This tablet has not been set up yet.</p>
        </div>

        <Alert kind={error ? 'error' : 'info'}>
          {error || 'An administrator needs to pair this tablet before it can take attendance.'}
        </Alert>

        <ol className="muted" style={{ fontSize: '0.88rem', paddingLeft: '1.1rem', lineHeight: 1.8 }}>
          <li>Sign in on this tablet as an administrator.</li>
          <li>Open <strong>Counter</strong> from the menu.</li>
          <li>Name the counter and tap <strong>Pair this device</strong>.</li>
        </ol>

        <Link
          to="/login"
          className="btn-primary btn-block center"
          style={{ display: 'block', textDecoration: 'none', padding: '0.7rem' }}
        >
          Sign in to set up
        </Link>
      </div>
    </div>
  );
}

function Counter({ session }) {
  const [step, setStep] = useState(STEP.PICK);
  const [sewadars, setSewadars] = useState([]);
  const [search, setSearch] = useState('');
  const [selected, setSelected] = useState(null);
  const [selectedDay, setSelectedDay] = useState(null);
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [clock, setClock] = useState(new Date());
  const [online, setOnline] = useState(navigator.onLine);
  const [queued, setQueued] = useState(offlineQueue.length);
  const [syncNote, setSyncNote] = useState('');

  const signatureRef = useRef(null);
  const resetTimerRef = useRef(null);

  useEffect(() => {
    const tick = setInterval(() => setClock(new Date()), 1000);
    return () => clearInterval(tick);
  }, []);

  const loadSewadars = useCallback(async (term) => {
    try {
      const data = await api.kiosk.get(`/kiosk/sewadars?search=${encodeURIComponent(term || '')}`);
      setSewadars(data.sewadars);
    } catch (err) {
      // Offline: the last-seen roster stays on screen so the queue keeps moving.
      if (err.status !== 0) setError(err.message);
    }
  }, []);

  useEffect(() => {
    const handle = setTimeout(() => loadSewadars(search), search ? 220 : 0);
    return () => clearTimeout(handle);
  }, [search, loadSewadars]);

  const flushQueue = useCallback(async () => {
    if (!offlineQueue.length) return;
    const { synced, rejected, remaining } = await offlineQueue.flush(
      (body) => api.kiosk.post('/kiosk/punch', { body }),
    );
    setQueued(remaining);
    if (synced || rejected.length) {
      setSyncNote(
        `${synced} queued entr${synced === 1 ? 'y' : 'ies'} synced.`
        + (rejected.length
          ? ` ${rejected.length} rejected: ${rejected.map((r) => `${r.sewadarId} — ${r.reason}`).join('; ')}`
          : ''),
      );
    }
  }, []);

  useEffect(() => {
    const goOnline = () => { setOnline(true); flushQueue(); };
    const goOffline = () => setOnline(false);

    window.addEventListener('online', goOnline);
    window.addEventListener('offline', goOffline);
    if (navigator.onLine) flushQueue();

    return () => {
      window.removeEventListener('online', goOnline);
      window.removeEventListener('offline', goOffline);
    };
  }, [flushQueue]);

  useEffect(() => () => clearTimeout(resetTimerRef.current), []);

  const reset = useCallback(() => {
    clearTimeout(resetTimerRef.current);
    setStep(STEP.PICK);
    setSelected(null);
    setSelectedDay(null);
    setPin('');
    setResult(null);
    setError('');
    setSearch('');
  }, []);

  const choose = async (sewadar) => {
    setSelected(sewadar);
    setPin('');
    setError('');
    setStep(STEP.PIN);
    try {
      setSelectedDay(await api.kiosk.get(`/kiosk/status/${encodeURIComponent(sewadar.id)}`));
    } catch {
      setSelectedDay(null); // offline: the server decides the direction on sync
    }
  };

  const park = (signature) => {
    offlineQueue.add({
      sewadarId: selected.id,
      pin,
      action: selectedDay?.nextAction,
      signature,
      sewaDate: session.sewaDate,
    });
    setQueued(offlineQueue.length);
    setResult({ ok: true, offline: true, name: selected.full_name, action: selectedDay?.nextAction });
    setStep(STEP.RESULT);
    resetTimerRef.current = setTimeout(reset, RESULT_DWELL_MS);
  };

  const submit = async ({ withSignature }) => {
    if (!selected || pin.length < 4) return;

    const signature = withSignature ? signatureRef.current?.toDataURL() : null;
    setBusy(true);
    setError('');

    if (!navigator.onLine) {
      park(signature);
      setBusy(false);
      return;
    }

    try {
      const data = await api.kiosk.post('/kiosk/punch', {
        body: {
          sewadar_id: selected.id,
          pin,
          action: selectedDay?.nextAction,
          signature: signature || undefined,
        },
      });
      setResult({
        ok: true,
        name: data.sewadar.full_name,
        action: data.action,
        at: data.action === 'IN' ? data.record.in_time : data.record.out_time,
        hours: data.record.hours,
      });
      setStep(STEP.RESULT);
      resetTimerRef.current = setTimeout(reset, RESULT_DWELL_MS);
    } catch (err) {
      if (err.status === 0) {
        park(signature); // the connection dropped between the check and the send
      } else {
        setError(err.message);
        setPin('');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="kiosk-shell">
      <div className="kiosk-bar">
        <span className="station">{session.station.name}</span>
        {session.station.departmentName && (
          <span className="badge badge-neutral">{session.station.departmentName}</span>
        )}
        {!session.isSewaDay && <span className="pill">EXTRA SEWA DAY</span>}
        {!online && <span className="pill">OFFLINE — entries are being queued</span>}
        {queued > 0 && <span className="pill">{queued} waiting to sync</span>}
        <span className="clock">{formatTime(clock)}</span>
      </div>

      <div className="kiosk-body">
        <div className="kiosk-panel">
          {syncNote && <Alert kind="info" onDismiss={() => setSyncNote('')}>{syncNote}</Alert>}
          {error && <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>}

          {step === STEP.PICK && (
            <PickStep
              sewadars={sewadars}
              search={search}
              onSearch={setSearch}
              onChoose={choose}
              session={session}
            />
          )}

          {step === STEP.PIN && (
            <PinStep
              sewadar={selected}
              day={selectedDay}
              pin={pin}
              busy={busy}
              onPin={setPin}
              onBack={reset}
              onSubmit={() => submit({ withSignature: false })}
              onSign={() => setStep(STEP.SIGN)}
            />
          )}

          {step === STEP.SIGN && (
            <SignStep
              sewadar={selected}
              day={selectedDay}
              busy={busy}
              signatureRef={signatureRef}
              onBack={() => setStep(STEP.PIN)}
              onSubmit={() => submit({ withSignature: true })}
            />
          )}

          {step === STEP.RESULT && <ResultStep result={result} onDone={reset} />}
        </div>
      </div>
    </div>
  );
}

function PickStep({ sewadars, search, onSearch, onChoose, session }) {
  return (
    <>
      <div className="kiosk-step-title">Who is signing in?</div>
      <div className="kiosk-step-sub">
        {session.dayName}, {formatDate(session.sewaDate)} — find your name, then enter your PIN.
      </div>

      <div className="field">
        <input
          type="search"
          placeholder="Search by name or Sewadar ID…"
          value={search}
          onChange={(e) => onSearch(e.target.value)}
          autoFocus
        />
      </div>

      {sewadars.length === 0 ? (
        <p className="faint center" style={{ padding: '2rem 0' }}>
          {search ? `Nobody matches “${search}”.` : 'No Sewadars available at this counter.'}
        </p>
      ) : (
        <div className="sewadar-grid">
          {sewadars.map((sewadar) => (
            <button type="button" key={sewadar.id} className="sewadar-tile" onClick={() => onChoose(sewadar)}>
              <strong>{sewadar.full_name}</strong>
              <span>{sewadar.id}{sewadar.department_name ? ` · ${sewadar.department_name}` : ''}</span>
            </button>
          ))}
        </div>
      )}
    </>
  );
}

function PinStep({ sewadar, day, pin, busy, onPin, onBack, onSubmit, onSign }) {
  const press = (key) => {
    if (key === 'del') onPin(pin.slice(0, -1));
    else if (key === 'clr') onPin('');
    else if (pin.length < PIN_MAX) onPin(pin + key);
  };

  const action = day?.nextAction;
  const done = day && !action;

  return (
    <>
      <div className="kiosk-step-title">{sewadar.full_name}</div>
      <div className="kiosk-step-sub">
        {done
          ? 'You have already signed in and out today.'
          : action
            ? `About to sign ${action}. Enter your PIN to confirm.`
            : 'Enter your PIN to confirm.'}
        {day?.record?.in_time && ` Signed in at ${formatTime(day.record.in_time)}.`}
      </div>

      <div className="pin-dots" aria-label={`${pin.length} digits entered`}>
        {Array.from({ length: Math.max(pin.length, 4) }).map((_, index) => (
          <span key={index} className={`pin-dot ${index < pin.length ? 'filled' : ''}`} />
        ))}
      </div>

      <div className="pinpad">
        {['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((key) => (
          <button type="button" key={key} onClick={() => press(key)} disabled={busy || done}>{key}</button>
        ))}
        <button type="button" className="wide" onClick={() => press('clr')} disabled={busy || done}>Clear</button>
        <button type="button" onClick={() => press('0')} disabled={busy || done}>0</button>
        <button type="button" className="wide" onClick={() => press('del')} disabled={busy || done}>⌫</button>
      </div>

      <div className="row" style={{ marginTop: '1.15rem' }}>
        <button type="button" className="btn-secondary" onClick={onBack} disabled={busy}>← Back</button>
        <button type="button" className="btn-secondary row-end" onClick={onSign} disabled={busy || done || pin.length < 4}>
          Add signature
        </button>
        <button type="button" className="btn-primary" onClick={onSubmit} disabled={busy || done || pin.length < 4}>
          {busy ? 'Recording…' : `Confirm${action ? ` sign ${action}` : ''}`}
        </button>
      </div>
    </>
  );
}

function SignStep({ sewadar, day, busy, signatureRef, onBack, onSubmit }) {
  const [signed, setSigned] = useState(false);

  return (
    <>
      <div className="kiosk-step-title">Sign to confirm</div>
      <div className="kiosk-step-sub">
        {sewadar.full_name} — sign {day?.nextAction || 'entry'}. Your signature is stored with the record.
      </div>

      <SignaturePad ref={signatureRef} onChange={setSigned} />

      <div className="row" style={{ marginTop: '1.15rem' }}>
        <button type="button" className="btn-secondary" onClick={onBack} disabled={busy}>← Back</button>
        <button type="button" className="btn-primary row-end" onClick={onSubmit} disabled={busy || !signed}>
          {busy ? 'Recording…' : 'Confirm with signature'}
        </button>
      </div>
    </>
  );
}

function ResultStep({ result, onDone }) {
  return (
    <div className="kiosk-result">
      <div className={`tick ${result.ok ? 'ok' : 'err'}`}>{result.ok ? '✓' : '✕'}</div>
      <h2>{result.name}</h2>

      {result.offline ? (
        <>
          <p className="muted">
            Sign {result.action} saved on this tablet. It will sync by itself when the network returns.
          </p>
          <span className="badge badge-in">Queued offline</span>
        </>
      ) : (
        <>
          <p className="muted">
            Signed <strong>{result.action}</strong> at {formatTime(result.at)}.
            {result.action === 'OUT' && result.hours != null && ` ${result.hours} hours of sewa today.`}
          </p>
          <span className="badge badge-present">Recorded</span>
        </>
      )}

      <div style={{ marginTop: '1.4rem' }}>
        <button type="button" className="btn-secondary" onClick={onDone}>Next person</button>
      </div>
    </div>
  );
}
