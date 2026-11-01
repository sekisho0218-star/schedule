// ローカル保存（localStorage）
const K_EVENTS = 'vt.events';
const K_GCACHE = 'vt.googleCache';
const K_TASKS = 'vt.tasks';
const K_SETTINGS = 'vt.settings';

const read = (k, fallback) => {
  try {
    const v = localStorage.getItem(k);
    return v ? JSON.parse(v) : fallback;
  } catch {
    return fallback;
  }
};
const write = (k, v) => localStorage.setItem(k, JSON.stringify(v));

export const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

/* ---------- 設定 ---------- */
export function getSettings() {
  return { clientId: '', calendarId: 'primary', view: 'week', ...read(K_SETTINGS, {}) };
}
export function saveSettings(patch) {
  write(K_SETTINGS, { ...getSettings(), ...patch });
}

/* ---------- ローカル予定 ----------
 * { id, title, description, allDay, start: ISO, end: ISO, source: 'local' }
 * 終日予定は start/end を 'YYYY-MM-DD'（end は翌日＝排他的）で保持
 */
export function getLocalEvents() {
  return read(K_EVENTS, []);
}
export function putLocalEvent(ev) {
  const list = getLocalEvents().filter((e) => e.id !== ev.id);
  list.push({ ...ev, source: 'local' });
  write(K_EVENTS, list);
}
export function deleteLocalEvent(id) {
  write(K_EVENTS, getLocalEvents().filter((e) => e.id !== id));
}

/* ---------- Google予定のキャッシュ（オフライン表示用） ---------- */
export function getGoogleCache() {
  return read(K_GCACHE, []);
}
export function setGoogleCache(list) {
  write(K_GCACHE, list);
}

/* ---------- タスク（日付ごと・最大5件） ---------- */
export const TASKS_PER_DAY = 5;

export function getTasks(dateStr) {
  return read(K_TASKS, {})[dateStr] || [];
}
function saveTasks(dateStr, list) {
  const all = read(K_TASKS, {});
  if (list.length) all[dateStr] = list;
  else delete all[dateStr];
  write(K_TASKS, all);
}
export function addTask(dateStr, text) {
  const list = getTasks(dateStr);
  if (list.length >= TASKS_PER_DAY) return false;
  list.push({ id: uid(), text });
  saveTasks(dateStr, list);
  return true;
}
export function updateTask(dateStr, id, text) {
  saveTasks(dateStr, getTasks(dateStr).map((t) => (t.id === id ? { ...t, text } : t)));
}
/** チェックされたタスクは削除する */
export function completeTask(dateStr, id) {
  saveTasks(dateStr, getTasks(dateStr).filter((t) => t.id !== id));
}
