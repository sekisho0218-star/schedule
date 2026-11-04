// シフト表と Google カレンダーの照合・反映
//  - 授業：自動で追加・変更・削除（初回だけは確認してから）
//  - 授業以外（「中3竹内様」など）と、シフト表にない予定：確認して反映
const { loadShift, COURSE_RE } = require('./shift');
const google = require('./google');

const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const hm = (m) => `${Math.floor(m / 60)}:${pad(m % 60)}`;
const dayLabel = (d) => `${d.getMonth() + 1}/${d.getDate()}(${'日月火水木金土'[d.getDay()]})`;
const at = (date, min) => { const d = new Date(date); d.setHours(0, 0, 0, 0); d.setMinutes(min); return d; };
const minOfDay = (iso) => { const d = new Date(iso); return d.getHours() * 60 + d.getMinutes(); };
const noSpace = (s) => (s || '').replace(/[\s　]/g, '');
/** 授業名を生徒ごとに分ける（「中2奥中(数)」→ { base: '中2奥中', subj: '数' }） */
function students(title) {
  return noSpace(title).split('/').filter(Boolean).map((p) => {
    const m = p.match(/^(.*?)[（(]([^）)]*)[）)]$/);
    return m ? { base: m[1], subj: m[2] } : { base: p, subj: '' };
  });
}
/** 同じ授業か（並び順・空白の違いは無視。科目はどちらかが空なら一致とみなす） */
function sameLesson(a, b) {
  const A = students(a); const B = students(b);
  if (A.length !== B.length) return false;
  return A.every((x) => B.some((y) => y.base === x.base && (!x.subj || !y.subj || x.subj === y.subj)));
}
/** シフト表で科目が空欄の生徒は、カレンダーの予定の科目を引き継ぐ */
function mergeTitle(want, have) {
  const H = students(have);
  return students(want).map((x) => {
    const subj = x.subj || H.find((y) => y.base === x.base)?.subj || '';
    return subj ? `${x.base}(${subj})` : x.base;
  }).join('/');
}
const lessonKey = (t) => students(t).map((x) => x.base).sort().join('/');

const LESSON_TITLE = /^(小|中|高)\s*[1-6１-６]/;
const MARK_ADDED = 'シフト表から自動登録';
/**
 * カレンダーの予定が「授業」か。シフト表の読み取り（shift.js）と同じ基準で、予定名だけで決める
 * （以前はここと読み取りの基準が違い、「ダスキン見積もり」が毎回追加されて重複していた）
 */
function isLessonEvent(ev) {
  if (ev.allDay) return false;
  return (LESSON_TITLE.test(ev.title) && !/様/.test(ev.title)) || COURSE_RE.test(ev.title);
}
const addedBySync = (ev) => (ev.description || '').includes(MARK_ADDED);

function toEvent(item) {
  if (item.allDay) {
    const next = new Date(item.date); next.setDate(next.getDate() + 1);
    return { title: item.title, allDay: true, start: ymd(item.date), end: ymd(next) };
  }
  return { title: item.title, allDay: false, start: at(item.date, item.start).toISOString(), end: at(item.date, item.end).toISOString() };
}

/** 照合だけ行い、変更の一覧を返す（まだ反映しない） */
async function compare(settings, today = new Date()) {
  const shift = await loadShift({ url: settings.url, teacher: settings.teacher }, today);
  const from = new Date(today); from.setHours(0, 0, 0, 0);
  const inRange = (d) => d >= from;
  const lessons = shift.lessons.filter((l) => inRange(l.date));
  const others = shift.others.filter((o) => inRange(o.date));
  if (!lessons.length && !others.length) return { lessonOps: [], proposals: [], notes: shift.notes, range: null };

  const last = new Date(Math.max(...[...lessons, ...others].map((x) => x.date.getTime())));
  const to = new Date(last); to.setDate(to.getDate() + 1); to.setHours(0, 0, 0, 0);
  let events = await google.listEvents(from.toISOString(), to.toISOString());

  // 反映先カレンダー（'primary' はメインのカレンダー）
  const calId = settings.calendarId && settings.calendarId !== 'primary'
    ? settings.calendarId
    : (events.find((e) => e.primary)?.calendarId || 'primary');
  const onDate = (ev, date) => (ev.allDay ? ev.start === ymd(date) : ymd(new Date(ev.start)) === ymd(date));

  /* ---- 重複の掃除：照合で追加した予定が同じ日・同じ時間・同じ名前で重なっていたら、1件だけ残して削除 ---- */
  const lessonOps = [];
  const groups = new Map();
  for (const e of events) {
    if (e.calendarId !== calId || !e.writable) continue;
    const k = `${e.allDay ? e.start : new Date(e.start).getTime()}|${e.allDay ? e.end : new Date(e.end).getTime()}|${lessonKey(e.title)}`;
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(e);
  }
  const dupIds = new Set();
  for (const g of groups.values()) {
    if (g.length < 2) continue;
    // 自分で入れた予定（印なし）があればそれを残す。なければ1件目を残す
    const keep = g.find((e) => !addedBySync(e)) || g[0];
    for (const e of g) {
      if (e === keep || !addedBySync(e)) continue;
      dupIds.add(e.eventId);
      const date = e.allDay ? new Date(e.start + 'T00:00:00') : new Date(e.start);
      const when = e.allDay ? '終日' : `${hm(minOfDay(e.start))}–${hm(minOfDay(e.end))}`;
      lessonOps.push({ op: 'delete', calendarId: e.calendarId, eventId: e.eventId, desc: `${dayLabel(date)} ${when} ${e.title}（重複を削除）` });
    }
  }
  events = events.filter((e) => !dupIds.has(e.eventId));

  /* ---- 授業 ---- */
  const dates = [...new Set([...lessons.map((l) => ymd(l.date)), ...events.filter((e) => !e.allDay).map((e) => ymd(new Date(e.start)))])];
  for (const key of dates) {
    const date = new Date(key + 'T00:00:00');
    const want = lessons.filter((l) => ymd(l.date) === key);
    let have = events.filter((e) => e.calendarId === calId && isLessonEvent(e) && onDate(e, date) && e.writable);
    const rest = [];
    // 1) 時刻も生徒も同じ → そのまま
    for (const w of want) {
      const i = have.findIndex((h) => sameLesson(h.title, w.title) && minOfDay(h.start) === w.start && minOfDay(h.end) === w.end);
      if (i >= 0) have.splice(i, 1); else rest.push(w);
    }
    // 2) 残りは「開始が同じ」「重なる時間が長い」順に組み合わせて変更（組み合わせのないものは追加）
    const pairs = [];
    rest.forEach((w, wi) => have.forEach((h, hi) => {
      const hs = minOfDay(h.start); const he = minOfDay(h.end);
      const overlap = Math.min(he, w.end) - Math.max(hs, w.start);
      if (overlap > 0) pairs.push({ wi, hi, score: (hs === w.start ? 10000 : 0) + (sameLesson(h.title, w.title) ? 5000 : 0) + overlap });
    }));
    pairs.sort((a, b) => b.score - a.score);
    const usedW = new Set(); const usedH = new Set();
    for (const p of pairs) {
      if (usedW.has(p.wi) || usedH.has(p.hi)) continue;
      usedW.add(p.wi); usedH.add(p.hi);
      const w = rest[p.wi]; const h = have[p.hi];
      const item = { ...w, title: mergeTitle(w.title, h.title) };
      const changed = item.title !== h.title || minOfDay(h.start) !== w.start || minOfDay(h.end) !== w.end;
      if (!changed) continue;
      lessonOps.push({ op: 'update', calendarId: calId, eventId: h.eventId, item, note: h.description || '',
        desc: `${dayLabel(date)} ${hm(minOfDay(h.start))}–${hm(minOfDay(h.end))} ${h.title} → ${hm(item.start)}–${hm(item.end)} ${item.title}` });
    }
    rest.forEach((w, wi) => {
      if (usedW.has(wi)) return;
      // どのカレンダーにでも、同じ日・同じ時間・同じ授業の予定がすでにあれば追加しない（重複防止）
      const already = events.some((e) => !e.allDay && onDate(e, date) && minOfDay(e.start) === w.start && minOfDay(e.end) === w.end && sameLesson(e.title, w.title));
      if (!already) lessonOps.push({ op: 'add', calendarId: calId, item: w, desc: `${dayLabel(date)} ${hm(w.start)}–${hm(w.end)} ${w.title}` });
    });
    have = have.filter((_, hi) => !usedH.has(hi));
    for (const h of have) {
      lessonOps.push({ op: 'delete', calendarId: h.calendarId, eventId: h.eventId,
        desc: `${dayLabel(date)} ${hm(minOfDay(h.start))}–${hm(minOfDay(h.end))} ${h.title}` });
    }
  }

  /* ---- 授業以外：シフト表にあってカレンダーにないもの ---- */
  const proposals = [];
  for (const o of others) {
    const exists = events.some((e) => onDate(e, o.date) && noSpace(e.title) === noSpace(o.title));
    if (exists) continue;
    const when = o.allDay ? '終日' : `${hm(o.start)}–${hm(o.end)}`;
    proposals.push({ key: `add|${ymd(o.date)}|${o.allDay ? 'allday' : o.start}|${noSpace(o.title)}`, type: 'add', checked: true,
      calendarId: calId, item: o, desc: `${dayLabel(o.date)} ${when} ${o.title}` });
  }

  /* ---- シフト表にない予定（反映先カレンダーの、授業以外の予定） ---- */
  for (const e of events) {
    if (e.calendarId !== calId || isLessonEvent(e) || !e.writable) continue;
    const date = e.allDay ? new Date(e.start + 'T00:00:00') : new Date(e.start);
    const inShift = others.some((o) => onDate(e, o.date) && noSpace(e.title) === noSpace(o.title));
    if (inShift) continue;
    const when = e.allDay ? '終日' : `${hm(minOfDay(e.start))}–${hm(minOfDay(e.end))}`;
    proposals.push({ key: `rm|${e.eventId}|${e.start}`, type: 'remove', checked: false,
      calendarId: e.calendarId, eventId: e.eventId, desc: `${dayLabel(date)} ${when} ${e.title}` });
  }

  return { lessonOps, proposals, notes: shift.notes.filter((n) => inRange(n.date)).map((n) => `${dayLabel(n.date)} ${n.text}`), calendarId: calId };
}

async function applyLessonOp(op) {
  if (op.op === 'add') return google.createShiftEvent(op.calendarId, toEvent(op.item), 'lesson');
  if (op.op === 'update') return google.updateShiftEvent(op.calendarId, op.eventId, toEvent(op.item), 'lesson', op.note);
  if (op.op === 'delete') return google.deleteEvent({ calendarId: op.calendarId, eventId: op.eventId });
  return null;
}

async function applyProposal(p) {
  if (p.type === 'add') return google.createShiftEvent(p.calendarId, toEvent(p.item), 'other');
  if (p.type === 'remove') return google.deleteEvent({ calendarId: p.calendarId, eventId: p.eventId });
  return null;
}

module.exports = { compare, applyLessonOp, applyProposal, isLessonEvent, lessonKey, sameLesson, mergeTitle };
