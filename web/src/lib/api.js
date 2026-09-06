/**
 * Thin fetch wrapper for the Sewa Entry API.
 *
 * Two token slots, not one: a person's session and a kiosk station's token
 * live side by side, because a tablet stays provisioned as a station while an
 * admin signs in and out of the console on the same device.
 */

const USER_TOKEN_KEY = 'sewa.token';
const KIOSK_TOKEN_KEY = 'sewa.kioskToken';

export const tokenStore = {
  get user() {
    return safeRead(USER_TOKEN_KEY);
  },
  set user(value) {
    safeWrite(USER_TOKEN_KEY, value);
  },
  get kiosk() {
    return safeRead(KIOSK_TOKEN_KEY);
  },
  set kiosk(value) {
    safeWrite(KIOSK_TOKEN_KEY, value);
  },
};

function safeRead(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null; // private mode, or storage blocked
  }
}

function safeWrite(key, value) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {
    /* nothing to do — the session simply will not persist across reloads */
  }
}

export class ApiError extends Error {
  constructor(status, message, details) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.details = details;
  }
}

async function request(method, path, { body, token, signal, raw } = {}) {
  let response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers: {
        ...(body ? { 'content-type': 'application/json' } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal,
    });
  } catch (err) {
    if (err.name === 'AbortError') throw err;
    throw new ApiError(0, 'Cannot reach the server. Check the network connection.');
  }

  if (raw) {
    if (!response.ok) throw new ApiError(response.status, await readError(response));
    return response;
  }

  const text = await response.text();
  let payload = {};
  try {
    payload = text ? JSON.parse(text) : {};
  } catch {
    payload = { error: text };
  }

  if (!response.ok) {
    throw new ApiError(response.status, payload.error || `Request failed (${response.status}).`, payload.details);
  }
  return payload;
}

async function readError(response) {
  try {
    const payload = await response.json();
    return payload.error || `Request failed (${response.status}).`;
  } catch {
    return `Request failed (${response.status}).`;
  }
}

const withUser = (method) => (path, options = {}) =>
  request(method, path, { ...options, token: options.token ?? tokenStore.user });

const withKiosk = (method) => (path, options = {}) =>
  request(method, path, { ...options, token: tokenStore.kiosk });

export const api = {
  get: withUser('GET'),
  post: withUser('POST'),
  patch: withUser('PATCH'),
  del: withUser('DELETE'),

  kiosk: {
    get: withKiosk('GET'),
    post: withKiosk('POST'),
  },

  /** Streams the .xlsx to a download without leaving the page. */
  async downloadExcel(params) {
    const query = new URLSearchParams(
      Object.entries(params).filter(([, v]) => v !== '' && v != null),
    ).toString();

    const response = await request('GET', `/reports/excel?${query}`, { raw: true });
    const blob = await response.blob();

    const filename = /filename="([^"]+)"/.exec(response.headers.get('content-disposition') || '')?.[1]
      || 'sewa_attendance.xlsx';

    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    // Revoking immediately can cancel the download in some browsers.
    setTimeout(() => URL.revokeObjectURL(url), 30_000);
    return filename;
  },
};
