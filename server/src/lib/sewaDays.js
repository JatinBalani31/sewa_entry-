import { config } from '../config.js';

const DAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

/**
 * A sewa date is a calendar date, not an instant. Everything here works on
 * `YYYY-MM-DD` strings interpreted in the sangat's reporting timezone, so a
 * Sewadar signing in at 9 pm never lands on the wrong day because the server
 * happens to run in UTC.
 */

/** `YYYY-MM-DD` for an instant, in the reporting timezone. */
export function toSewaDate(instant = new Date()) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: config.report.timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(instant));
}

/** Day of week (0 = Sunday) for a `YYYY-MM-DD` string. */
export function dayOfWeek(sewaDate) {
  // Parsed as UTC midnight so the weekday is a property of the date itself and
  // cannot be shifted by the server's own timezone.
  return new Date(`${sewaDate}T00:00:00.000Z`).getUTCDay();
}

export function isSewaDay(sewaDate) {
  return config.sewa.days.includes(dayOfWeek(sewaDate));
}

export function dayName(sewaDate) {
  return DAY_NAMES[dayOfWeek(sewaDate)];
}

export function isValidSewaDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? '')) return false;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return !Number.isNaN(parsed.getTime()) && toIsoDate(parsed) === value;
}

function toIsoDate(date) {
  return date.toISOString().slice(0, 10);
}

function shift(sewaDate, days) {
  const date = new Date(`${sewaDate}T00:00:00.000Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return toIsoDate(date);
}

/** The sewa day on or before `from` — what the app should open to. */
export function currentOrPreviousSewaDay(from = toSewaDate()) {
  for (let i = 0; i < 14; i++) {
    const candidate = shift(from, -i);
    if (isSewaDay(candidate)) return candidate;
  }
  return from;
}

export function nextSewaDay(from = toSewaDate()) {
  for (let i = 1; i <= 14; i++) {
    const candidate = shift(from, i);
    if (isSewaDay(candidate)) return candidate;
  }
  return from;
}

/** Every sewa day in `[from, to]`, oldest first. Used to build the register. */
export function sewaDaysBetween(from, to) {
  const dates = [];
  let cursor = from;
  // A guard rather than a `while (true)`: a decade of twice-weekly sewa is far
  // more than any report asks for, and a bad range cannot hang the server.
  for (let i = 0; i < 4000 && cursor <= to; i++) {
    if (isSewaDay(cursor)) dates.push(cursor);
    cursor = shift(cursor, 1);
  }
  return dates;
}

/** The most recent `count` sewa days, oldest first. */
export function recentSewaDays(count = 8, from = toSewaDate()) {
  const dates = [];
  let cursor = from;
  for (let i = 0; i < 400 && dates.length < count; i++) {
    if (isSewaDay(cursor)) dates.unshift(cursor);
    cursor = shift(cursor, -1);
  }
  return dates;
}

export function daysBetween(a, b) {
  return Math.round(
    (new Date(`${b}T00:00:00.000Z`) - new Date(`${a}T00:00:00.000Z`)) / 86_400_000,
  );
}

/** Human label for the configured schedule, e.g. "Wednesday and Sunday". */
export function scheduleLabel() {
  const names = [...config.sewa.days].sort().map((d) => DAY_NAMES[d]);
  if (names.length <= 1) return names[0] || 'no scheduled days';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}
