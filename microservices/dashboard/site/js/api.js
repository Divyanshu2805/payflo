// The dashboard's only way to the backend: the same public API any merchant calls, through the gateway.
// A merchant session is a JWT (and its refresh token); the operator console sends the admin key instead.
// Both live in sessionStorage, so closing the tab ends them, and neither is ever sent anywhere but the API.

const SESSION_KEY = 'payflo.session';
const ADMIN_KEY = 'payflo.admin';

export class ApiError extends Error {
  constructor(status, body, retryAfter) {
    super((body && body.errorDescription) || `HTTP ${status}`);
    this.status = status;
    this.code = (body && body.errorCode) || `HTTP_${status}`;
    this.description = body && body.errorDescription;
    this.fieldErrors = body && body.fieldErrors;
    this.retryAfter = retryAfter;
  }
}

function read(key) {
  try {
    return JSON.parse(sessionStorage.getItem(key));
  } catch {
    return null;
  }
}

function write(key, value) {
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value));
    else sessionStorage.removeItem(key);
  } catch {
    // Storage refused (a private window): the session then lasts until the page is reloaded.
  }
}

let session = read(SESSION_KEY);
let adminKey = read(ADMIN_KEY);
let onSessionEnd = () => {};

export function onSessionEnded(callback) {
  onSessionEnd = callback;
}

export function currentSession() {
  return session;
}

export function isAdmin() {
  return Boolean(adminKey);
}

// The token's payload says who it is for. It is read for display only: the gateway is what verifies it.
function claims(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(payload));
  } catch {
    return {};
  }
}

function keep(login, email) {
  const payload = claims(login.accessToken);
  session = { accessToken: login.accessToken, refreshToken: login.refreshToken, email, role: payload.role, merchantId: payload.merchant_id };
  write(SESSION_KEY, session);
}

// ---- the calls the console at the bottom of the page lists

const calls = [];
const listeners = new Set();

export function apiCalls() {
  return calls;
}

export function onApiCall(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function record(call) {
  calls.unshift(call);
  if (calls.length > 80) calls.pop();
  listeners.forEach((listener) => listener(call));
}

// ---- requests

async function send(method, path, body, { idempotencyKey, admin, token } = {}) {
  const headers = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (admin) headers['X-Admin-Key'] = adminKey || '';
  else if (token) headers.Authorization = `Bearer ${token}`;
  if (idempotencyKey) headers['X-Idempotency-Key'] = idempotencyKey;

  const started = performance.now();
  let response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    record({ method, path, status: 0, ms: Math.round(performance.now() - started), code: 'NETWORK', at: new Date() });
    throw new ApiError(0, { errorCode: 'NETWORK', errorDescription: 'The dashboard server did not answer' });
  }
  const text = await response.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = { errorDescription: text.slice(0, 200) };
  }
  record({
    method, path, status: response.status, ms: Math.round(performance.now() - started), at: new Date(),
    code: response.ok ? null : parsed && parsed.errorCode, idempotencyKey,
    credential: admin ? 'admin key' : token ? 'JWT' : 'none',
    remaining: response.headers.get('X-RateLimit-Remaining'),
  });
  if (!response.ok) throw new ApiError(response.status, parsed, response.headers.get('Retry-After'));
  return parsed;
}

let refreshing = null;

// A refresh token works once, so two requests that fail together must share one refresh.
function refresh() {
  if (!refreshing) {
    refreshing = send('POST', '/v1/auth/refresh', { refreshToken: session.refreshToken })
      .then((login) => keep(login, session.email))
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}

/** A call as the logged-in merchant. An expired access token is refreshed once, then the call is repeated. */
export async function api(method, path, body, options = {}) {
  if (!session) throw new ApiError(401, { errorCode: 'UNAUTHORIZED', errorDescription: 'Not logged in' });
  try {
    return await send(method, path, body, { ...options, token: session.accessToken });
  } catch (error) {
    if (error.status !== 401 || error.code !== 'UNAUTHORIZED' || !session || !session.refreshToken) throw error;
    try {
      await refresh();
    } catch {
      endSession();
      throw error;
    }
    return send(method, path, body, { ...options, token: session.accessToken });
  }
}

/** A call as the platform operator, with the admin key. */
export function adminApi(method, path, body) {
  return send(method, path, body, { admin: true });
}

export function query(params) {
  const search = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== '' && value !== null && value !== undefined) search.set(name, value);
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function newIdempotencyKey() {
  return crypto.randomUUID();
}

// ---- sessions

export async function login(email, password) {
  const result = await send('POST', '/v1/auth/login', { email, password });
  keep(result, email);
  return session;
}

export function signup(details) {
  return send('POST', '/v1/auth/signup', details);
}

export async function logout() {
  if (session) {
    try {
      await send('POST', '/v1/auth/logout', { refreshToken: session.refreshToken }, { token: session.accessToken });
    } catch {
      // The token may already be gone; the local session ends either way.
    }
  }
  endSession();
}

/** The admin key is checked by asking for one merchant: a wrong key is a 401 from the gateway. */
export async function adminLogin(key) {
  adminKey = key;
  try {
    await adminApi('GET', '/v1/admin/merchants?size=1');
  } catch (error) {
    adminKey = null;
    throw error;
  }
  write(ADMIN_KEY, adminKey);
}

export function adminLogout() {
  adminKey = null;
  write(ADMIN_KEY, null);
  onSessionEnd();
}

function endSession() {
  session = null;
  write(SESSION_KEY, null);
  onSessionEnd();
}
