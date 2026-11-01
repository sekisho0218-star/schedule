// Googleカレンダー連携（Google Identity Services + Calendar API v3）
import { getSettings } from './store.js';
import { ymd, parseYmd, addDays } from './dates.js';

const SCOPE = 'https://www.googleapis.com/auth/calendar.events';
const API = 'https://www.googleapis.com/calendar/v3';
const K_TOKEN = 'vt.gtoken';

let tokenClient = null;
let tokenClientId = null;
let pending = null; // { resolve, reject }

function loadToken() {
  try {
    const t = JSON.parse(localStorage.getItem(K_TOKEN) || 'null');
    if (t && t.expiresAt > Date.now() + 60_000) return t;
  } catch { /* noop */ }
  return null;
}

export function hasConnected() {
  return localStorage.getItem('vt.gconnected') === '1';
}

export function isReady() {
  return !!getSettings().clientId;
}

function waitForGis(timeout = 10000) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    (function check() {
      if (window.google?.accounts?.oauth2) return resolve();
      if (Date.now() - t0 > timeout) return reject(new Error('Googleログインスクリプトを読み込めませんでした'));
      setTimeout(check, 100);
    })();
  });
}

async function ensureClient() {
  const { clientId } = getSettings();
  if (!clientId) throw new Error('設定でGoogle OAuth クライアントIDを入力してください');
  await waitForGis();
  if (tokenClient && tokenClientId === clientId) return;
  tokenClientId = clientId;
  tokenClient = google.accounts.oauth2.initTokenClient({
    client_id: clientId,
    scope: SCOPE,
    callback: (resp) => {
      const p = pending; pending = null;
      if (!p) return;
      if (resp.error) return p.reject(new Error(resp.error_description || resp.error));
      const tok = { accessToken: resp.access_token, expiresAt: Date.now() + Number(resp.expires_in) * 1000 };
      localStorage.setItem(K_TOKEN, JSON.stringify(tok));
      localStorage.setItem('vt.gconnected', '1');
      p.resolve(tok);
    },
    error_callback: (err) => {
      const p = pending; pending = null;
      p?.reject(new Error(err?.message || 'Googleログインがキャンセルされました'));
    },
  });
}

/** アクセストークン取得（必要ならログインポップアップ） */
export async function getToken({ interactive = true } = {}) {
  const cached = loadToken();
  if (cached) return cached.accessToken;
  if (!interactive) return null;
  await ensureClient();
  const tok = await new Promise((resolve, reject) => {
    pending = { resolve, reject };
    tokenClient.requestAccessToken({ prompt: hasConnected() ? '' : 'consent' });
  });
  return tok.accessToken;
}

export function signOut() {
  const t = loadToken();
  if (t && window.google?.accounts?.oauth2) google.accounts.oauth2.revoke(t.accessToken, () => {});
  localStorage.removeItem(K_TOKEN);
  localStorage.removeItem('vt.gconnected');
}

async function api(path, { method = 'GET', body, interactive = true } = {}) {
  let token = await getToken({ interactive });
  if (!token) throw new Error('Googleにログインしていません');
  const doFetch = () => fetch(API + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let res = await doFetch();
  if (res.status === 401) {
    localStorage.removeItem(K_TOKEN);
    token = await getToken({ interactive });
    if (!token) throw new Error('Googleの認証が切れました。同期ボタンを押してください');
    res = await doFetch();
  }
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Google API エラー (${res.status}): ${text.slice(0, 200)}`);
  }
  return res.status === 204 ? null : res.json();
}

const calPath = () => `/calendars/${encodeURIComponent(getSettings().calendarId || 'primary')}/events`;

/** Google のイベント -> アプリ内形式 */
function fromGoogle(g) {
  const allDay = !!g.start?.date;
  return {
    id: 'g:' + g.id,
    googleId: g.id,
    title: g.summary || '(タイトルなし)',
    description: g.description || '',
    allDay,
    start: allDay ? g.start.date : g.start.dateTime,
    end: allDay ? g.end.date : g.end.dateTime,
    source: 'google',
  };
}

/** アプリ内形式 -> Google のイベント */
function toGoogle(ev) {
  const body = { summary: ev.title, description: ev.description || '' };
  if (ev.allDay) {
    body.start = { date: ev.start };
    body.end = { date: ev.end };
  } else {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    body.start = { dateTime: new Date(ev.start).toISOString(), timeZone: tz };
    body.end = { dateTime: new Date(ev.end).toISOString(), timeZone: tz };
  }
  return body;
}

/** 期間内のイベント一覧 */
export async function listEvents(from, to, { interactive = true } = {}) {
  const out = [];
  let pageToken = '';
  do {
    const q = new URLSearchParams({
      timeMin: from.toISOString(),
      timeMax: to.toISOString(),
      singleEvents: 'true',
      orderBy: 'startTime',
      maxResults: '2500',
    });
    if (pageToken) q.set('pageToken', pageToken);
    const data = await api(`${calPath()}?${q}`, { interactive });
    for (const g of data.items || []) if (g.status !== 'cancelled') out.push(fromGoogle(g));
    pageToken = data.nextPageToken || '';
  } while (pageToken);
  return out;
}

export async function createEvent(ev) {
  return fromGoogle(await api(calPath(), { method: 'POST', body: toGoogle(ev) }));
}

export async function updateEvent(ev) {
  return fromGoogle(await api(`${calPath()}/${encodeURIComponent(ev.googleId)}`, { method: 'PATCH', body: toGoogle(ev) }));
}

export async function deleteEvent(ev) {
  await api(`${calPath()}/${encodeURIComponent(ev.googleId)}`, { method: 'DELETE' });
}

// 終日予定の日付計算ヘルパー（再エクスポート）
export const allDayEnd = (dateStr) => ymd(addDays(parseYmd(dateStr), 1));
