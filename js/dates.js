// 日付ユーティリティ（すべてローカルタイム、週は月曜始まり）
export const DOW = ['日', '月', '火', '水', '木', '金', '土'];

export const pad = (n) => String(n).padStart(2, '0');

/** Date -> 'YYYY-MM-DD' */
export function ymd(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 'YYYY-MM-DD' -> Date(00:00 local) */
export function parseYmd(s) {
  const [y, m, d] = s.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export function addDays(d, n) {
  const r = new Date(d);
  r.setDate(r.getDate() + n);
  return r;
}

export function startOfDay(d) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

/** その日を含む週の月曜日 */
export function startOfWeek(d) {
  const day = startOfDay(d);
  const offset = (day.getDay() + 6) % 7; // 月=0 ... 日=6
  return addDays(day, -offset);
}

export function startOfMonth(d) {
  return new Date(d.getFullYear(), d.getMonth(), 1);
}

/** 曜日区分: 'wd' | 'sat' | 'sun' */
export function dayKind(d) {
  const w = d.getDay();
  return w === 6 ? 'sat' : w === 0 ? 'sun' : 'wd';
}

export function isSameDay(a, b) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

export function hm(d) {
  return `${d.getHours()}:${pad(d.getMinutes())}`;
}

/** 'HH:MM' -> 分 */
export function toMinutes(s) {
  const [h, m] = s.split(':').map(Number);
  return h * 60 + m;
}

/** 'YYYY-MM-DD' + 'HH:MM' -> Date */
export function combine(dateStr, timeStr) {
  const d = parseYmd(dateStr);
  const mins = toMinutes(timeStr);
  d.setMinutes(mins);
  return d;
}
