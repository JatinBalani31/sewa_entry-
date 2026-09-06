/**
 * Offline resilience for the counter tablet.
 *
 * When the hall's Wi-Fi drops mid-sewa the counter must keep taking
 * attendance. Entries are parked in localStorage and replayed once the network
 * is back. The Sewadar's PIN is part of the parked payload because the server
 * — never the tablet — is what verifies it; an entry that turns out to carry a
 * wrong PIN is rejected on sync rather than being silently accepted.
 *
 * Each entry keeps the sewa date it belongs to, so a queue that only drains
 * the next morning still lands on the right day.
 */

const QUEUE_KEY = 'sewa.counterQueue';
const MAX_ENTRIES = 500;

function read() {
  try {
    const raw = localStorage.getItem(QUEUE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function write(entries) {
  try {
    localStorage.setItem(QUEUE_KEY, JSON.stringify(entries.slice(-MAX_ENTRIES)));
  } catch {
    /* storage full or blocked — the caller sees it via length */
  }
}

export const offlineQueue = {
  list: read,

  get length() {
    return read().length;
  },

  add(entry) {
    const entries = read();
    entries.push({ ...entry, queuedAt: new Date().toISOString(), id: crypto.randomUUID() });
    write(entries);
    return entries.length;
  },

  remove(id) {
    write(read().filter((entry) => entry.id !== id));
  },

  clear() {
    write([]);
  },

  /**
   * Replay every parked entry in the order it was taken.
   *
   * A rejection the server will never accept (wrong PIN, locked account, a
   * duplicate sign-in) drops the entry — retrying forever would wedge the
   * queue behind one person. A transport failure keeps it for the next try.
   */
  async flush(send) {
    const entries = read();
    if (!entries.length) return { synced: 0, rejected: [], remaining: 0 };

    let synced = 0;
    const rejected = [];
    const keep = [];

    for (const entry of entries) {
      try {
        await send({
          sewadar_id: entry.sewadarId,
          pin: entry.pin,
          action: entry.action,
          signature: entry.signature || undefined,
          sewa_date: entry.sewaDate,
        });
        synced++;
      } catch (err) {
        if (err.status && err.status !== 0 && err.status < 500) {
          rejected.push({ ...entry, reason: err.message });
        } else {
          keep.push(entry);
        }
      }
    }

    write(keep);
    return { synced, rejected, remaining: keep.length };
  },
};
