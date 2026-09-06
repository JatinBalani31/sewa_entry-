import { useState } from 'react';
import { api } from '../lib/api.js';
import { useAuth } from '../lib/auth.jsx';
import { Alert, Modal } from './ui.jsx';

/** Mirrors the server's PIN policy so the field can object before a round trip. */
function describePinWeakness(pin) {
  if (!/^\d*$/.test(pin)) return 'Digits only.';
  if (pin.length < 4) return 'At least 4 digits.';
  if (pin.length > 12) return 'At most 12 digits.';
  if (/^(\d)\1+$/.test(pin)) return 'Not a single repeated digit — 1111 is guessed first.';

  const digits = pin.split('').map(Number);
  const ascending = digits.every((d, i) => i === 0 || d === digits[i - 1] + 1);
  const descending = digits.every((d, i) => i === 0 || d === digits[i - 1] - 1);
  if (ascending || descending) return 'Not a run of sequential digits — 1234 is guessed second.';

  return null;
}

const OBVIOUS = ['password', 'password1', '12345678', '123456789', 'qwerty123',
  'admin123', 'sewaentry', 'letmein1', 'welcome1', 'iloveyou'];

function describePasswordWeakness(password) {
  if (password.length < 8) return 'At least 8 characters.';
  if (OBVIOUS.includes(password.toLowerCase())) return 'Too common — choose something less guessable.';
  if (/^\d+$/.test(password)) return 'Add letters, not only digits.';
  return null;
}

/**
 * Changes whichever credential this account actually signs in with: an admin's
 * console password, or a Sewadar's PIN. The server decides which — this only
 * changes the wording and the validation to match.
 */
export default function ChangeSecretModal({ onClose, onDone }) {
  const { isAdmin } = useAuth();

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const noun = isAdmin ? 'password' : 'PIN';
  const digitsOnly = (value) => (isAdmin ? value : value.replace(/\D/g, ''));

  const weakness = next ? (isAdmin ? describePasswordWeakness(next) : describePinWeakness(next)) : null;
  const mismatch = confirm.length > 0 && next !== confirm;
  const unchanged = next.length > 0 && next === current;
  const ready = !weakness && !mismatch && !unchanged && current.length > 0 && confirm.length > 0;

  const submit = async (event) => {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await api.post('/auth/change-secret', { body: { current, next } });
      onDone(
        isAdmin
          ? 'Your console password has been changed.'
          : 'Your PIN has been changed. Use the new one at the counter too.',
      );
    } catch (err) {
      setError(err.message);
      setCurrent('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={`Change your ${noun}`}
      subtitle={
        isAdmin
          ? 'This is what opens the admin console. Your counter PIN is separate and is not affected.'
          : 'The same PIN signs you in here and on the counter tablet. The old one stops working immediately.'
      }
      onClose={onClose}
    >
      <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

      <form onSubmit={submit}>
        <div className="field">
          <label htmlFor="cur-secret">Current {noun}</label>
          <input
            id="cur-secret"
            type="password"
            inputMode={isAdmin ? 'text' : 'numeric'}
            autoComplete="current-password"
            value={current}
            onChange={(e) => setCurrent(digitsOnly(e.target.value))}
            autoFocus
            required
          />
          <div className="field-hint">Three wrong attempts lock the account for five minutes.</div>
        </div>

        <div className="field">
          <label htmlFor="new-secret">New {noun}</label>
          <input
            id="new-secret"
            type="password"
            inputMode={isAdmin ? 'text' : 'numeric'}
            autoComplete="new-password"
            value={next}
            onChange={(e) => setNext(digitsOnly(e.target.value))}
            required
          />
          <div className={`field-hint ${weakness || unchanged ? 'field-error' : ''}`}>
            {unchanged
              ? `The new ${noun} must differ from your current one.`
              : weakness || (isAdmin ? '8 characters or more. Looks good.' : '4–12 digits. Looks good.')}
          </div>
        </div>

        <div className="field">
          <label htmlFor="confirm-secret">Confirm new {noun}</label>
          <input
            id="confirm-secret"
            type="password"
            inputMode={isAdmin ? 'text' : 'numeric'}
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(digitsOnly(e.target.value))}
            required
          />
          {mismatch && <div className="field-hint field-error">The two do not match.</div>}
        </div>

        <div className="modal-actions">
          <button type="button" className="btn-secondary" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={busy || !ready}>
            {busy ? 'Changing…' : `Change ${noun}`}
          </button>
        </div>
      </form>
    </Modal>
  );
}
