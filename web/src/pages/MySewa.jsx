import { useCallback, useEffect, useState } from 'react';
import { api } from '../lib/api.js';
import { useAuth } from '../lib/auth.jsx';
import { Alert, Modal, Spinner } from '../components/ui.jsx';
import {
  formatTime, formatDate, formatShortDate, formatHours, relativeTime,
  timeToInstant, instantToTime, describeAdjustment, isoDate,
} from '../lib/format.js';

export default function MySewaPage() {
  const { user, sewa } = useAuth();

  const [day, setDay] = useState(null);
  const [recent, setRecent] = useState([]);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(false);

  const load = useCallback(async () => {
    try {
      const [dayData, recentData] = await Promise.all([
        api.get('/attendance/me/day'),
        api.get('/attendance/me/recent?count=6'),
      ]);
      setDay(dayData);
      setRecent(recentData.days);
    } catch (err) {
      setError(err.message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const doTap = async (action) => {
    setBusy(true);
    setError('');
    setSuccess('');
    try {
      const result = await api.post('/attendance/me/tap', { body: { action } });
      setSuccess(
        action === 'IN'
          ? `Signed in at ${formatTime(result.record.in_time)}. Have a blessed sewa.`
          : `Signed out at ${formatTime(result.record.out_time)}. Thank you for your sewa.`,
      );
      await load();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  if (!day) return <div className="card"><Spinner label="Loading your sewa…" /></div>;

  const record = day.record;
  const nextAction = day.nextAction;

  return (
    <div className="sewa-shell stack">
      <div className="page-header">
        <h1>Sat Sri Akal, {user.full_name.split(' ')[0]}</h1>
        <p>
          {user.department_name ? `${user.department_name} department` : 'No department assigned'}
          {sewa?.schedule && ` · Sewa on ${sewa.schedule}`}
        </p>
      </div>

      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>
      <Alert kind="success" onDismiss={() => setSuccess('')}>{success}</Alert>

      <div className="sewa-hero">
        <div className="sewa-daylabel">{day.isSewaDay ? 'Sewa day' : 'Today'}</div>
        <div className="sewa-date">{day.dayName}, {formatDate(day.sewaDate)}</div>
        <div className="sewa-schedule">
          {day.isSewaDay
            ? 'A scheduled sewa day.'
            : `Not a scheduled sewa day — this will be recorded as extra sewa.`}
        </div>

        <div className="sewa-times">
          <div className={`sewa-slot ${record?.in_time ? 'filled' : ''}`}>
            <div className="lbl">In</div>
            <div className="val">{record?.in_time ? formatTime(record.in_time) : '—:—'}</div>
            <div className="sub">{slotNote(record?.in_time, record?.in_source, record?.in_adjusted)}</div>
          </div>
          <div className={`sewa-slot ${record?.out_time ? 'filled' : ''}`}>
            <div className="lbl">Out</div>
            <div className="val">{record?.out_time ? formatTime(record.out_time) : '—:—'}</div>
            <div className="sub">{slotNote(record?.out_time, record?.out_source, record?.out_adjusted)}</div>
          </div>
        </div>

        {nextAction ? (
          <button
            type="button"
            className={`sewa-button ${nextAction === 'IN' ? 'to-in' : 'to-out'}`}
            onClick={() => doTap(nextAction)}
            disabled={busy}
          >
            {busy ? 'Recording…' : nextAction === 'IN' ? 'Sign IN for sewa' : 'Sign OUT'}
          </button>
        ) : (
          <div className="sewa-button done">
            Sewa complete — {formatHours(record.hours)} today
          </div>
        )}

        <div className="row" style={{ justifyContent: 'center', marginTop: '0.85rem' }}>
          <button type="button" className="btn-ghost btn-sm" onClick={() => setEditing(true)}>
            {record?.in_time ? '✎ Correct my timings' : '✎ Enter timings by hand'}
          </button>
        </div>
      </div>

      {record?.notes && (
        <div className="card">
          <div className="faint" style={{ whiteSpace: 'pre-wrap' }}>{record.notes}</div>
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2>Recent sewa days</h2>
        </div>

        {recent.length === 0 ? (
          <p className="faint" style={{ margin: 0 }}>Nothing recorded yet.</p>
        ) : (
          recent.map((entry) => (
            <div className="history-item" key={entry.sewaDate}>
              <div className="when">
                <strong>{formatShortDate(entry.sewaDate)}</strong>
                <span>
                  {entry.record?.in_time
                    ? `${formatTime(entry.record.in_time)} – ${entry.record.out_time ? formatTime(entry.record.out_time) : 'no sign-out'}`
                    : 'Did not attend'}
                </span>
              </div>
              {entry.record?.hours != null ? (
                <span className="badge badge-present">{formatHours(entry.record.hours)}</span>
              ) : entry.record?.in_time ? (
                <span className="badge badge-in">Open</span>
              ) : (
                <span className="badge badge-absent">—</span>
              )}
            </div>
          ))
        )}
      </div>

      <p className="faint center">
        Forgot your phone? Sign in at the counter tablet with your Sewadar ID and PIN —
        it records the same attendance.
      </p>

      {editing && (
        <EditTimesModal
          day={day}
          toleranceMinutes={sewa?.toleranceMinutes ?? 120}
          backfillDays={sewa?.backfillDays ?? 14}
          onClose={() => setEditing(false)}
          onDone={(message) => { setEditing(false); setSuccess(message); load(); }}
        />
      )}
    </div>
  );
}

/**
 * The small print under a time. A time that was never tapped has nothing to
 * have drifted from, so it says how it was entered instead of claiming it is
 * "as tapped".
 */
function slotNote(time, source, adjustedMinutes) {
  if (!time) return 'not yet';
  if (source === 'ADMIN') return 'set by an admin';
  if (source === 'MANUAL') return 'entered by hand';
  return describeAdjustment(adjustedMinutes) || 'as tapped';
}

/**
 * Lets a Sewadar set their own in/out times — nudging what they tapped, or
 * filling in a day they missed entirely. The server enforces the tolerance;
 * this screen explains it up front so a refusal is never a surprise.
 */
function EditTimesModal({ day, toleranceMinutes, backfillDays, onClose, onDone }) {
  const [sewaDate, setSewaDate] = useState(day.sewaDate);
  const [inTime, setInTime] = useState(instantToTime(day.record?.in_time));
  const [outTime, setOutTime] = useState(instantToTime(day.record?.out_time));
  const [notes, setNotes] = useState(day.record?.notes || '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  // Changing the date means loading whatever is already recorded there, so the
  // form never silently overwrites another day's times with this day's.
  const changeDate = async (nextDate) => {
    setSewaDate(nextDate);
    setError('');
    try {
      const data = await api.get(`/attendance/me/day?date=${nextDate}`);
      setInTime(instantToTime(data.record?.in_time));
      setOutTime(instantToTime(data.record?.out_time));
      setNotes(data.record?.notes || '');
    } catch (err) {
      setError(err.message);
    }
  };

  const outBeforeIn = inTime && outTime && outTime <= inTime;

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const result = await api.patch('/attendance/me/day', {
        body: {
          sewa_date: sewaDate,
          in_time: inTime ? timeToInstant(sewaDate, inTime) : null,
          out_time: outTime ? timeToInstant(sewaDate, outTime) : null,
          notes: notes.trim() || null,
        },
      });
      onDone(
        result.record.hours != null
          ? `Saved — ${formatHours(result.record.hours)} recorded for ${formatShortDate(sewaDate)}.`
          : `Saved for ${formatShortDate(sewaDate)}.`,
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const earliest = (() => {
    const d = new Date();
    d.setDate(d.getDate() - backfillDays);
    return isoDate(d);
  })();

  return (
    <Modal
      title="Your sewa timings"
      subtitle={`Adjust by up to ${Math.round(toleranceMinutes / 60)} hours from when you tapped. A day you never tapped can be filled in from scratch.`}
      onClose={onClose}
    >
      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="edit-date">Sewa day</label>
          <input
            id="edit-date"
            type="date"
            value={sewaDate}
            min={earliest}
            max={isoDate()}
            onChange={(e) => changeDate(e.target.value)}
          />
          <div className="field-hint">You can go back {backfillDays} days. Older than that, ask an administrator.</div>
        </div>

        <div className="field-row">
          <div className="field">
            <label htmlFor="edit-in">In time</label>
            <input id="edit-in" type="time" value={inTime} onChange={(e) => setInTime(e.target.value)} />
          </div>
          <div className="field">
            <label htmlFor="edit-out">Out time</label>
            <input id="edit-out" type="time" value={outTime} onChange={(e) => setOutTime(e.target.value)} />
          </div>
        </div>

        {outBeforeIn && <Alert kind="warn">The out-time needs to be after the in-time.</Alert>}

        <div className="field">
          <label htmlFor="edit-notes">Note (optional)</label>
          <textarea
            id="edit-notes"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="e.g. Came early to help with setup."
            maxLength={500}
          />
        </div>

        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={busy || outBeforeIn || (!inTime && outTime)}>
            {busy ? 'Saving…' : 'Save timings'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
