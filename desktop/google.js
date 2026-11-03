// Google ログイン（デスクトップアプリ用 OAuth：ループバック + PKCE）と Calendar / Tasks API
const http = require('node:http');
const crypto = require('node:crypto');
const { shell } = require('electron');
const config = require('./config');

const SCOPES = [
  'https://www.googleapis.com/auth/calendar.readonly', // カレンダー一覧
  'https://www.googleapis.com/auth/calendar.events', // 予定の読み書き
  'https://www.googleapis.com/auth/tasks', // Google ToDo リスト
];
const CAL = 'https://www.googleapis.com/calendar/v3';
const TASKS = 'https://tasks.googleapis.com/tasks/v1/lists/@default/tasks';

class NeedLoginError extends Error {
  constructor() {
    super('Googleにログインしてください');
    this.code = 'NEED_LOGIN';
  }
}

const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

let access = null; // { token, expiresAt }
let pendingAuthUrl = ''; // ログイン待ちの間、同じ画面を開き直すため
let eventColors = null;

function isSignedIn() {
  return !!config.getRefreshToken();
}

async function tokenRequest(params) {
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(json.error_description || json.error || `token error ${res.status}`);
    err.oauth = json.error;
    throw err;
  }
  return json;
}

/** ブラウザで Google にログインし、リフレッシュトークンを保存する */
async function signIn() {
  const { clientId, clientSecret } = config.load();
  if (!clientId || !clientSecret) throw new Error('設定でクライアントIDとクライアントシークレットを入力してください');

  const verifier = b64url(crypto.randomBytes(32));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  const state = b64url(crypto.randomBytes(16));

  const server = http.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const redirectUri = `http://127.0.0.1:${server.address().port}`;

  const authUrl = 'https://accounts.google.com/o/oauth2/v2/auth?' + new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: SCOPES.join(' '),
    code_challenge: challenge,
    code_challenge_method: 'S256',
    access_type: 'offline',
    prompt: 'consent',
    state,
  });

  const code = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { server.close(); reject(new Error('ログインがタイムアウトしました')); }, 5 * 60_000);
    server.on('request', (req, res) => {
      const u = new URL(req.url, redirectUri);
      if (u.pathname !== '/') { res.writeHead(404); res.end(); return; }
      const ok = u.searchParams.get('state') === state && u.searchParams.get('code');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(`<!doctype html><meta charset="utf-8"><title>バーチカル手帳</title>
        <body style="font-family:sans-serif;padding:40px;text-align:center">
        <h2>${ok ? 'ログインしました' : 'ログインできませんでした'}</h2>
        <p>このタブを閉じて、バーチカル手帳に戻ってください。</p></body>`);
      clearTimeout(timer);
      server.close();
      if (ok) resolve(u.searchParams.get('code'));
      else reject(new Error(u.searchParams.get('error') || 'ログインがキャンセルされました'));
    });
    pendingAuthUrl = authUrl;
    shell.openExternal(authUrl);
  }).finally(() => { pendingAuthUrl = ''; });

  const tok = await tokenRequest({
    client_id: clientId,
    client_secret: clientSecret,
    code,
    code_verifier: verifier,
    redirect_uri: redirectUri,
    grant_type: 'authorization_code',
  });
  if (!tok.refresh_token) throw new Error('リフレッシュトークンを取得できませんでした。もう一度お試しください');
  config.setRefreshToken(tok.refresh_token);
  config.save({ wasSignedIn: true });
  access = { token: tok.access_token, expiresAt: Date.now() + tok.expires_in * 1000 };
}

function signOut() {
  config.save({ wasSignedIn: false });
  const rt = config.getRefreshToken();
  if (rt) fetch('https://oauth2.googleapis.com/revoke?token=' + encodeURIComponent(rt), { method: 'POST' }).catch(() => {});
  config.setRefreshToken('');
  access = null;
}

async function accessToken() {
  if (access && access.expiresAt > Date.now() + 60_000) return access.token;
  const rt = config.getRefreshToken();
  if (!rt) throw new NeedLoginError();
  const { clientId, clientSecret } = config.load();
  try {
    const tok = await tokenRequest({ client_id: clientId, client_secret: clientSecret, refresh_token: rt, grant_type: 'refresh_token' });
    access = { token: tok.access_token, expiresAt: Date.now() + tok.expires_in * 1000 };
    return access.token;
  } catch (e) {
    if (e.oauth === 'invalid_grant') { config.setRefreshToken(''); throw new NeedLoginError(); }
    throw e;
  }
}

async function api(method, url, body) {
  const token = await accessToken();
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) { access = null; throw new NeedLoginError(); }
  if (!res.ok) throw new Error(`Google API エラー (${res.status})`);
  return res.status === 204 ? null : res.json();
}

/* ---------------- カレンダー ---------------- */

async function listCalendars() {
  const data = await api('GET', `${CAL}/users/me/calendarList?minAccessRole=reader&maxResults=250`);
  return (data.items || []).filter((c) => c.selected !== false && !c.hidden);
}

/** 表示中のカレンダーすべての予定（繰り返しは展開済み） */
async function listEvents(fromIso, toIso) {
  if (!eventColors) {
    eventColors = await api('GET', `${CAL}/colors`).then((d) => d.event || {}).catch(() => ({}));
  }
  const cals = await listCalendars();
  const results = await Promise.all(cals.map(async (cal) => {
    const out = [];
    let pageToken = '';
    do {
      const q = new URLSearchParams({ timeMin: fromIso, timeMax: toIso, singleEvents: 'true', orderBy: 'startTime', maxResults: '2500' });
      if (pageToken) q.set('pageToken', pageToken);
      const data = await api('GET', `${CAL}/calendars/${encodeURIComponent(cal.id)}/events?${q}`).catch(() => ({ items: [] }));
      for (const e of data.items || []) {
        if (e.status === 'cancelled') continue;
        const allDay = !!e.start?.date;
        out.push({
          id: `${cal.id}|${e.id}`,
          calendarId: cal.id,
          eventId: e.id,
          calendarName: cal.summaryOverride || cal.summary,
          title: e.summary || '(タイトルなし)',
          description: e.description || '',
          allDay,
          start: allDay ? e.start.date : e.start.dateTime,
          end: allDay ? e.end.date : e.end.dateTime,
          color: eventColors[e.colorId]?.background || cal.backgroundColor || '#4a72b5',
          writable: cal.accessRole === 'owner' || cal.accessRole === 'writer',
          recurring: !!e.recurringEventId,
          shift: e.extendedProperties?.private?.vtShift || null, // シフト表から登録した予定
          primary: !!cal.primary,
        });
      }
      pageToken = data.nextPageToken || '';
    } while (pageToken);
    return out;
  }));
  return results.flat();
}

function toGoogleEvent(ev) {
  const body = { summary: ev.title, description: ev.description || '' };
  if (ev.allDay) {
    body.start = { date: ev.start };
    body.end = { date: ev.end };
  } else {
    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    body.start = { dateTime: ev.start, timeZone };
    body.end = { dateTime: ev.end, timeZone };
  }
  return body;
}

const createEvent = (ev) => api('POST', `${CAL}/calendars/primary/events`, toGoogleEvent(ev));

/** シフト表から登録する予定（あとで見分けられるよう印を付ける） */
function shiftBody(ev, kind) {
  return {
    ...toGoogleEvent(ev),
    description: 'シフト表から自動登録（バーチカル手帳）',
    extendedProperties: { private: { vtShift: kind } },
  };
}
const createShiftEvent = (calendarId, ev, kind) =>
  api('POST', `${CAL}/calendars/${encodeURIComponent(calendarId)}/events`, shiftBody(ev, kind));
const updateShiftEvent = (calendarId, eventId, ev, kind) =>
  api('PATCH', `${CAL}/calendars/${encodeURIComponent(calendarId)}/events/${encodeURIComponent(eventId)}`, shiftBody(ev, kind));

/** 書き込めるカレンダーの一覧（設定画面用） */
async function writableCalendars() {
  return (await listCalendars())
    .filter((c) => c.accessRole === 'owner' || c.accessRole === 'writer')
    .map((c) => ({ id: c.id, name: c.summaryOverride || c.summary, primary: !!c.primary }));
}
const updateEvent = (ev) =>
  api('PATCH', `${CAL}/calendars/${encodeURIComponent(ev.calendarId)}/events/${encodeURIComponent(ev.eventId)}`, toGoogleEvent(ev));
const deleteEvent = (ev) =>
  api('DELETE', `${CAL}/calendars/${encodeURIComponent(ev.calendarId)}/events/${encodeURIComponent(ev.eventId)}`);

/* ---------------- タスク（Google ToDo リスト「マイタスク」） ---------------- */

/** [from, toExclusive) が期限の未完了タスクを日付ごとに返す */
async function listTasks(fromYmd, toYmdExclusive) {
  const last = new Date(toYmdExclusive + 'T00:00:00Z');
  last.setUTCDate(last.getUTCDate() - 1);
  const items = [];
  let pageToken = '';
  do {
    const q = new URLSearchParams({
      showCompleted: 'false',
      showHidden: 'false',
      maxResults: '100',
      dueMin: `${fromYmd}T00:00:00.000Z`,
      dueMax: `${last.toISOString().slice(0, 10)}T23:59:59.999Z`,
    });
    if (pageToken) q.set('pageToken', pageToken);
    const data = await api('GET', `${TASKS}?${q}`);
    items.push(...(data.items || []));
    pageToken = data.nextPageToken || '';
  } while (pageToken);

  const byDate = {};
  // 位置の値が大きいほど先に作られたタスク（Google は新しいタスクを先頭に追加する）
  items
    .filter((t) => t.due && t.status !== 'completed' && !t.deleted)
    .sort((a, b) => (a.position < b.position ? 1 : -1))
    .forEach((t) => {
      const d = t.due.slice(0, 10);
      (byDate[d] ||= []).push({ id: t.id, text: t.title || '' });
    });
  return byDate;
}

const addTask = (date, text) => api('POST', TASKS, { title: text, due: `${date}T00:00:00.000Z` }).then((t) => t.id);
const updateTask = (id, text) => api('PATCH', `${TASKS}/${encodeURIComponent(id)}`, { title: text });
const completeTask = (id) => api('PATCH', `${TASKS}/${encodeURIComponent(id)}`, { status: 'completed' });

/** ログイン待ちならブラウザのログイン画面を開き直す */
function reopenSignIn() {
  if (pendingAuthUrl) shell.openExternal(pendingAuthUrl);
  return !!pendingAuthUrl;
}

module.exports = {
  isSignedIn, signIn, signOut, reopenSignIn,
  listEvents, createEvent, updateEvent, deleteEvent,
  createShiftEvent, updateShiftEvent, writableCalendars,
  listTasks, addTask, updateTask, completeTask,
};
