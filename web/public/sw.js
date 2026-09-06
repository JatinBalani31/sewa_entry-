/**
 * Keeps the app shell available offline.
 *
 * This matters most on the reception tablet: if the Wi-Fi drops and someone
 * reloads the kiosk, without this they get a browser error page and the queue
 * of parked punches becomes unreachable. With it, the station comes back up
 * and keeps taking attendance.
 *
 * Attendance data itself is never cached — every /api/ request goes to the
 * network, and the kiosk's own queue (lib/offlineQueue.js) handles the
 * offline case. A cached punch would be a lie about what the server recorded.
 */

const VERSION = 'sewa-entry-v1';
const SHELL = `${VERSION}-shell`;

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL)
      .then((cache) => cache.addAll(['/', '/manifest.webmanifest']))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((key) => !key.startsWith(VERSION)).map((key) => caches.delete(key)),
      ))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;   // map tiles etc: leave alone
  if (url.pathname.startsWith('/api/')) return;      // never cache attendance data

  // Navigations: network first, so a redeploy is picked up, with the cached
  // shell as the fallback when there is no network.
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone();
          caches.open(SHELL).then((cache) => cache.put('/', copy));
          return response;
        })
        .catch(() => caches.match('/').then((cached) => cached || Response.error())),
    );
    return;
  }

  // Built assets carry a content hash in their filename, so a cache hit can
  // never be stale — serve it immediately and fetch only on a miss.
  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        if (response.ok && response.type === 'basic') {
          const copy = response.clone();
          caches.open(SHELL).then((cache) => cache.put(request, copy));
        }
        return response;
      });
    }),
  );
});
