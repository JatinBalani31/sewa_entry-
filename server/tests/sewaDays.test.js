import test from 'node:test';
import assert from 'node:assert/strict';
import {
  dayOfWeek, isSewaDay, dayName, isValidSewaDate, sewaDaysBetween,
  recentSewaDays, currentOrPreviousSewaDay, nextSewaDay, daysBetween, scheduleLabel,
} from '../src/lib/sewaDays.js';
import { assertValidPin, assertValidPassword } from '../src/lib/pin.js';

// 2026-09-01 is a Tuesday. The surrounding days are used throughout:
//   Sun 2026-08-30, Mon 31, Tue 09-01, Wed 09-02, Thu 03, Sat 05, Sun 06
test('day of week is read from the date itself, not the server timezone', () => {
  assert.equal(dayOfWeek('2026-08-30'), 0); // Sunday
  assert.equal(dayOfWeek('2026-09-01'), 2); // Tuesday
  assert.equal(dayOfWeek('2026-09-02'), 3); // Wednesday
  assert.equal(dayName('2026-09-02'), 'Wednesday');
});

test('sewa days are Wednesday and Sunday', () => {
  assert.ok(isSewaDay('2026-08-30'), 'Sunday is a sewa day');
  assert.ok(isSewaDay('2026-09-02'), 'Wednesday is a sewa day');
  assert.ok(!isSewaDay('2026-09-01'), 'Tuesday is not');
  assert.ok(!isSewaDay('2026-09-05'), 'Saturday is not');
});

test('the schedule describes itself in words', () => {
  assert.equal(scheduleLabel(), 'Sunday and Wednesday');
});

test('date validation rejects nonsense and impossible dates', () => {
  assert.ok(isValidSewaDate('2026-09-02'));
  assert.ok(!isValidSewaDate('2026-9-2'), 'must be zero-padded');
  assert.ok(!isValidSewaDate('2026-02-30'), 'February has no 30th');
  assert.ok(!isValidSewaDate('not-a-date'));
  assert.ok(!isValidSewaDate(''));
  assert.ok(!isValidSewaDate(undefined));
});

test('sewaDaysBetween returns only the sewa days, oldest first', () => {
  // A full week from Sunday 30 Aug to Saturday 5 Sep.
  const dates = sewaDaysBetween('2026-08-30', '2026-09-05');
  assert.deepEqual(dates, ['2026-08-30', '2026-09-02']);
});

test('sewaDaysBetween spans months and includes both endpoints', () => {
  const dates = sewaDaysBetween('2026-08-26', '2026-09-02');
  assert.equal(dates[0], '2026-08-26', 'the start date is included when it is a sewa day');
  assert.equal(dates[dates.length - 1], '2026-09-02', 'and so is the end date');
  assert.ok(dates.every(isSewaDay));
});

test('an inverted range yields nothing rather than looping', () => {
  assert.deepEqual(sewaDaysBetween('2026-09-05', '2026-08-30'), []);
});

test('the current sewa day is today when today is one, else the previous one', () => {
  assert.equal(currentOrPreviousSewaDay('2026-09-02'), '2026-09-02', 'Wednesday returns itself');
  assert.equal(currentOrPreviousSewaDay('2026-09-01'), '2026-08-30', 'Tuesday falls back to Sunday');
  assert.equal(currentOrPreviousSewaDay('2026-09-05'), '2026-09-02', 'Saturday falls back to Wednesday');
});

test('the next sewa day is always in the future, never today', () => {
  assert.equal(nextSewaDay('2026-09-02'), '2026-09-06', 'Wednesday looks ahead to Sunday');
  assert.equal(nextSewaDay('2026-09-01'), '2026-09-02', 'Tuesday looks ahead to Wednesday');
});

test('recentSewaDays returns the requested count, oldest first, all sewa days', () => {
  const dates = recentSewaDays(4, '2026-09-02');
  assert.equal(dates.length, 4);
  assert.deepEqual(dates, ['2026-08-23', '2026-08-26', '2026-08-30', '2026-09-02']);
  assert.ok(dates.every(isSewaDay));
});

test('daysBetween counts calendar days across a month boundary', () => {
  assert.equal(daysBetween('2026-08-30', '2026-09-02'), 3);
  assert.equal(daysBetween('2026-09-02', '2026-09-02'), 0);
});

test('PIN policy accepts a reasonable PIN and rejects weak ones', () => {
  assert.equal(assertValidPin('4729'), '4729');
  for (const [pin, why] of [
    ['123', 'too short'],
    ['abcd', 'not numeric'],
    ['1111', 'repeated digit'],
    ['1234', 'ascending run'],
    ['4321', 'descending run'],
    ['', 'empty'],
  ]) {
    assert.throws(() => assertValidPin(pin), undefined, `expected "${pin}" rejected (${why})`);
  }
});

test('admin passwords must be long enough and not obvious', () => {
  assert.equal(assertValidPassword('sewa@admin1'), 'sewa@admin1');
  for (const [password, why] of [
    ['short1', 'under the minimum length'],
    ['password', 'on the obvious list'],
    ['12345678', 'digits only'],
    ['987654321', 'digits only, longer'],
  ]) {
    assert.throws(() => assertValidPassword(password), undefined, `expected "${password}" rejected (${why})`);
  }
});
