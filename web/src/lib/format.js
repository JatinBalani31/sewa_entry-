/** Shared display helpers, so a time reads the same on every screen. */

export const SOURCE_LABEL = {
  TAP: 'Phone',
  MANUAL: 'Self-entered',
  KIOSK: 'Counter',
  ADMIN: 'Admin',
};

export const STATUS_LABEL = {
  DONE: 'Complete',
  IN: 'Still in',
  ABSENT: 'Absent',
};

export const STATUS_CLASS = {
  DONE: 'badge-present',
  IN: 'badge-in',
  ABSENT: 'badge-absent',
};

/** HH:MM for an instant, in the viewer's own timezone. */
export function formatTime(instant) {
  if (!instant) return '—';
  return new Date(instant).toLocaleTimeString(undefined, {
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

/** "01 Sep 2026" for a YYYY-MM-DD sewa date or an instant. */
export function formatDate(value) {
  if (!value) return '—';
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T12:00:00`) : new Date(value);
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: '2-digit' });
}

/** "Wed 02 Sep" — compact enough for a chip or a column heading. */
export function formatShortDate(value) {
  if (!value) return '—';
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T12:00:00`) : new Date(value);
  return date.toLocaleDateString(undefined, { weekday: 'short', day: '2-digit', month: 'short' });
}

export function relativeTime(instant) {
  if (!instant) return 'never';
  const seconds = Math.round((Date.now() - new Date(instant).getTime()) / 1000);
  if (seconds < 45) return 'just now';
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.round(seconds / 3600)} h ago`;
  const days = Math.round(seconds / 86_400);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

export function formatHours(hours) {
  if (hours == null) return '—';
  const whole = Math.floor(hours);
  const minutes = Math.round((hours - whole) * 60);
  return minutes ? `${whole}h ${minutes}m` : `${whole}h`;
}

/** YYYY-MM-DD for a Date, in local time — the form the API's date filters take. */
export function isoDate(date = new Date()) {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}

export function daysAgo(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return isoDate(d);
}

export function startOfMonth() {
  const d = new Date();
  d.setDate(1);
  return isoDate(d);
}

/**
 * An <input type="time"> value ("09:15") turned into a full ISO instant on the
 * given sewa date, in the viewer's own timezone — which is what the API stores.
 */
export function timeToInstant(sewaDate, timeValue) {
  if (!timeValue) return null;
  const [hours, minutes] = timeValue.split(':').map(Number);
  const [year, month, day] = sewaDate.split('-').map(Number);
  return new Date(year, month - 1, day, hours, minutes, 0, 0).toISOString();
}

/** The reverse: an instant to the "HH:MM" an <input type="time"> wants. */
export function instantToTime(instant) {
  if (!instant) return '';
  const date = new Date(instant);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** "moved 30 min earlier" / "moved 15 min later", or null when untouched. */
export function describeAdjustment(minutes) {
  if (!minutes) return null;
  const abs = Math.abs(minutes);
  const unit = abs >= 60 ? `${(abs / 60).toFixed(abs % 60 ? 1 : 0)} h` : `${abs} min`;
  return `moved ${unit} ${minutes < 0 ? 'earlier' : 'later'}`;
}
