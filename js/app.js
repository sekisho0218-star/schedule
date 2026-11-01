import {
  DOW, ymd, parseYmd, addDays, startOfDay, startOfWeek, startOfMonth, dayKind, isSameDay, hm, pad, combine,
} from './dates.js';
import * as store from './store.js';
import * as gcal from './gcal.js';

const START_HOUR = 8;
const END_HOUR = 24;

const params = new URLSearchParams(location.search);
const isWidget = params.has('widget');

const state = {
  view: isWidget ? 'week' : (params.get('view') || store.getSettings().view || 'week'),
  anchor: startOfDay(new Date()),
  google: store.getGoogleCache(),
  syncing: false,
};

const $ = (sel) => document.querySelector(sel);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};

/* =========================================================
 * 予定の取得・整形
 * ======================================================= */
function allEvents() {
  return [...store.getLocalEvents(), ...state.google];
}

/** 1日分の予定を { allDay, timed } に分ける（日をまたぐ予定はその日の範囲に切り詰め） */
function eventsOn(day) {
  const dayStart = startOfDay(day);
  const dayEnd = addDays(dayStart, 1);
  const key = ymd(day);
  const allDay = [];
  const timed = [];
  for (const ev of allEvents()) {
    if (ev.allDay) {
      if (ev.start <= key && key < ev.end) allDay.push(ev);
      continue;
    }
    const s = new Date(ev.start);
    const e = new Date(ev.end);
    if (e <= dayStart || s >= dayEnd) continue;
    timed.push({ ev, s: s < dayStart ? dayStart : s, e: e > dayEnd ? dayEnd : e });
  }
  allDay.sort((a, b) => a.title.localeCompare(b.title));
  timed.sort((a, b) => a.s - b.s || b.e - a.e);
  return { allDay, timed };
}

/** 重なる予定を横に並べるための列割り当て */
function layoutColumns(items) {
  const clusters = [];
  let cur = null;
  for (const it of items) {
    if (!cur || it.s >= cur.end) {
      cur = { items: [], end: it.e, cols: [] };
      clusters.push(cur);
    }
    let col = cur.cols.findIndex((lastEnd) => lastEnd <= it.s);
    if (col === -1) { col = cur.cols.length; cur.cols.push(it.e); } else cur.cols[col] = it.e;
    it.col = col;
    cur.items.push(it);
    if (it.e > cur.end) cur.end = it.e;
  }
  for (const c of clusters) for (const it of c.items) it.ncols = c.cols.length;
  return items;
}

/* =========================================================
 * 週間バーチカル
 * ======================================================= */
function renderWeek(root) {
  const weekStart = startOfWeek(state.anchor);
  const days = [...Array(7)].map((_, i) => addDays(weekStart, i));
  const today = new Date();
  const weekEnd = days[6];
  $('#title').textContent = weekStart.getMonth() === weekEnd.getMonth()
    ? `${weekStart.getFullYear()}年${weekStart.getMonth() + 1}月 ${weekStart.getDate()}–${weekEnd.getDate()}日`
    : `${weekStart.getFullYear()}年${weekStart.getMonth() + 1}/${weekStart.getDate()}–${weekEnd.getMonth() + 1}/${weekEnd.getDate()}`;

  const grid = el('div', 'week');

  // 1行目：曜日ヘッダー
  grid.append(el('div', 'corner'));
  for (const d of days) {
    const h = el('div', `day-head ${dayKind(d)}${isSameDay(d, today) ? ' today' : ''}`);
    h.append(el('div', 'dow', DOW[d.getDay()]), el('div', 'num', d.getDate()));
    h.title = `${ymd(d)} に予定を追加`;
    h.addEventListener('click', () => openEventDialog({ date: ymd(d), allDay: true }));
    grid.append(h);
  }

  // 2行目：終日予定
  grid.append(el('div', 'allday-label', '終日'));
  for (const d of days) {
    const cell = el('div', 'allday');
    for (const ev of eventsOn(d).allDay) {
      const it = el('div', `allday-item ${ev.source}`, ev.title);
      it.title = ev.title;
      it.addEventListener('click', () => openEventDialog(ev));
      cell.append(it);
    }
    grid.append(cell);
  }

  // 3行目：時間軸 + 各日のバーチカル
  const axis = el('div', 'time-axis');
  for (let h = START_HOUR; h <= END_HOUR; h++) {
    axis.append(el('div', `hour${h === END_HOUR ? ' last' : ''}`, `${h}`));
  }
  grid.append(axis);

  const totalMin = (END_HOUR - START_HOUR) * 60;
  for (const d of days) {
    const col = el('div', `day-col ${dayKind(d)}${isSameDay(d, today) ? ' today' : ''}`);
    col.dataset.date = ymd(d);
    const rangeStart = new Date(d.getFullYear(), d.getMonth(), d.getDate(), START_HOUR);
    for (const it of layoutColumns(eventsOn(d).timed)) {
      let top = (it.s - rangeStart) / 60000;
      let bottom = (it.e - rangeStart) / 60000;
      if (bottom <= 0 || top >= totalMin) continue; // 表示範囲外（8時前のみ等）
      top = Math.max(0, top);
      bottom = Math.min(totalMin, bottom);
      const e = el('div', `event ${it.ev.source}`);
      e.style.top = `${(top / totalMin) * 100}%`;
      e.style.height = `max(14px, ${((bottom - top) / totalMin) * 100}%)`;
      const w = 100 / it.ncols;
      e.style.left = `calc(${w * it.col}% + 1px)`;
      e.style.width = `calc(${w}% - 2px)`;
      const realStart = new Date(it.ev.start);
      e.append(el('div', 't', hm(realStart)), el('div', 'n', it.ev.title));
      e.title = `${hm(realStart)}–${hm(new Date(it.ev.end))} ${it.ev.title}`;
      e.addEventListener('click', (evt) => { evt.stopPropagation(); openEventDialog(it.ev); });
      col.append(e);
    }
    if (isSameDay(d, today)) {
      const mins = (today - rangeStart) / 60000;
      if (mins >= 0 && mins <= totalMin) {
        const line = el('div', 'now-line');
        line.style.top = `${(mins / totalMin) * 100}%`;
        col.append(line);
      }
    }
    col.addEventListener('click', (evt) => {
      const rect = col.getBoundingClientRect();
      const mins = Math.floor(((evt.clientY - rect.top) / rect.height) * totalMin / 30) * 30;
      const start = START_HOUR * 60 + mins;
      const end = Math.min(start + 60, 24 * 60 - 1);
      openEventDialog({ date: ymd(d), start: minToHm(start), end: minToHm(end) });
    });
    grid.append(col);
  }

  // 4行目：タスクリスト（各日5件）
  grid.append(el('div', 'tasks-label', 'TASK'));
  for (const d of days) {
    const cell = el('div', 'tasks');
    cell.dataset.date = ymd(d);
    renderTasks(cell);
    grid.append(cell);
  }

  root.append(grid);
}

const minToHm = (m) => `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;

/* ---------- タスク ---------- */
function renderTasks(cell, focusEmpty = false) {
  const date = cell.dataset.date;
  cell.replaceChildren();
  const tasks = store.getTasks(date);
  let firstEmpty = null;
  for (let i = 0; i < store.TASKS_PER_DAY; i++) {
    const t = tasks[i];
    const row = el('div', `task${t ? '' : ' empty'}`);
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.setAttribute('aria-label', '完了');
    const input = document.createElement('input');
    input.className = 'task-text';
    input.enterKeyHint = 'done';
    input.maxLength = 100;
    if (t) {
      input.value = t.text;
      input.title = t.text;
      cb.addEventListener('change', () => {
        if (!cb.checked) return;
        row.classList.add('done');
        setTimeout(() => {
          store.completeTask(date, t.id);
          renderTasks(cell);
        }, 450);
      });
      input.addEventListener('change', () => {
        const v = input.value.trim();
        if (v) store.updateTask(date, t.id, v);
        else { store.completeTask(date, t.id); renderTasks(cell); }
      });
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') input.blur(); });
    } else {
      input.placeholder = isWidget ? '' : '+';
      if (!firstEmpty) firstEmpty = input;
      let committed = false;
      const commit = (refocus) => {
        const v = input.value.trim();
        if (!v || committed) return;
        committed = true; // 再描画で入力欄が消える際の blur による二重登録を防ぐ
        store.addTask(date, v);
        renderTasks(cell, refocus);
      };
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); commit(true); }
      });
      input.addEventListener('blur', () => commit(false));
    }
    row.append(cb, input);
    cell.append(row);
  }
  if (focusEmpty && firstEmpty) firstEmpty.focus();
}

/* =========================================================
 * 月間
 * ======================================================= */
function renderMonth(root) {
  const first = startOfMonth(state.anchor);
  const month = first.getMonth();
  const gridStart = startOfWeek(first);
  const today = new Date();
  $('#title').textContent = `${first.getFullYear()}年${month + 1}月`;

  const grid = el('div', 'month');
  for (let i = 0; i < 7; i++) {
    const d = addDays(gridStart, i);
    grid.append(el('div', `mhead ${dayKind(d)}`, DOW[d.getDay()]));
  }
  // 必要な週数（5 or 6週）
  const lastDay = new Date(first.getFullYear(), month + 1, 0);
  const weeks = Math.ceil((((lastDay - gridStart) / 86400000) + 1) / 7);
  const maxItems = window.innerWidth < 640 ? 3 : 4;

  for (let i = 0; i < weeks * 7; i++) {
    const d = addDays(gridStart, i);
    const cell = el('div', `mcell ${dayKind(d)}${d.getMonth() !== month ? ' other' : ''}${isSameDay(d, today) ? ' today' : ''}`);
    cell.append(el('div', 'mnum', d.getDate()));
    const { allDay, timed } = eventsOn(d);
    const items = [
      ...allDay.map((ev) => ({ ev, label: ev.title, cls: 'allday' })),
      ...timed.map(({ ev, s }) => ({ ev, label: `${hm(s)} ${ev.title}`, cls: '' })),
    ];
    for (const it of items.slice(0, maxItems)) {
      const m = el('div', `mitem ${it.cls} ${it.ev.source}`, it.label);
      m.title = it.label;
      m.addEventListener('click', (e) => { e.stopPropagation(); openEventDialog(it.ev); });
      cell.append(m);
    }
    if (items.length > maxItems) cell.append(el('div', 'mmore', `他${items.length - maxItems}件`));
    const nTasks = store.getTasks(ymd(d)).length;
    if (nTasks) cell.append(el('div', 'mtasks', `☐ タスク${nTasks}`));
    cell.addEventListener('click', () => { state.anchor = d; setView('week'); });
    grid.append(cell);
  }
  root.append(grid);
}

/* =========================================================
 * 画面全体
 * ======================================================= */
function render() {
  const root = $('#view');
  const scroll = root.scrollTop;
  root.replaceChildren();
  if (state.view === 'month') renderMonth(root); else renderWeek(root);
  root.scrollTop = scroll;
  $('#tab-week').classList.toggle('active', state.view === 'week');
  $('#tab-month').classList.toggle('active', state.view === 'month');
  $('#btn-sync').classList.toggle('connected', gcal.hasConnected());
}

function setView(v) {
  state.view = v;
  if (!isWidget) store.saveSettings({ view: v });
  render();
  syncIfConnected();
}

function move(dir) {
  if (state.view === 'month') {
    state.anchor = new Date(state.anchor.getFullYear(), state.anchor.getMonth() + dir, 1);
  } else {
    state.anchor = addDays(state.anchor, 7 * dir);
  }
  render();
  syncIfConnected();
}

let statusTimer = null;
function toast(msg, ms = 2500) {
  const s = $('#status');
  s.textContent = msg;
  s.hidden = false;
  clearTimeout(statusTimer);
  statusTimer = setTimeout(() => { s.hidden = true; }, ms);
}

/* =========================================================
 * Googleカレンダー同期
 * ======================================================= */
/** 表示中の月を含む6週間分を同期範囲とする（週間表示の週も必ず含まれる） */
function syncRange() {
  const from = startOfWeek(startOfMonth(state.anchor));
  return { from, to: addDays(from, 42) };
}

async function sync({ interactive = true } = {}) {
  if (state.syncing) return;
  if (!gcal.isReady()) {
    if (interactive) openSettings();
    return;
  }
  state.syncing = true;
  $('#btn-sync').classList.add('syncing');
  try {
    const { from, to } = syncRange();
    const fetched = await gcal.listEvents(from, to, { interactive });
    // 範囲外のキャッシュは保持し、範囲内は取得結果で置き換え
    const fromKey = ymd(from); const toKey = ymd(to);
    const outside = state.google.filter((ev) => {
      if (ev.allDay) return ev.end <= fromKey || ev.start >= toKey;
      return new Date(ev.end) <= from || new Date(ev.start) >= to;
    });
    state.google = [...outside, ...fetched];
    store.setGoogleCache(state.google);
    render();
    if (interactive) toast(`Googleカレンダーと同期しました（${fetched.length}件）`);
  } catch (err) {
    console.error(err);
    if (interactive) toast(err.message, 5000);
  } finally {
    state.syncing = false;
    $('#btn-sync').classList.remove('syncing');
  }
}

function syncIfConnected() {
  if (gcal.hasConnected() && gcal.isReady()) sync({ interactive: false });
}

/* =========================================================
 * 予定ダイアログ
 * ======================================================= */
let editing = null;

function openEventDialog(evOrDraft) {
  const dlg = $('#event-dialog');
  const f = $('#event-form');
  const isExisting = !!evOrDraft.id;
  editing = isExisting ? evOrDraft : null;
  f.reset();
  $('#event-dialog-title').textContent = isExisting ? '予定を編集' : '予定を追加';
  $('#btn-event-delete').hidden = !isExisting;

  if (isExisting) {
    const ev = evOrDraft;
    f.title.value = ev.title;
    f.description.value = ev.description || '';
    f.allDay.checked = ev.allDay;
    if (ev.allDay) {
      f.date.value = ev.start;
      f.endDate.value = ymd(addDays(parseYmd(ev.end), -1));
      f.start.value = '09:00'; f.end.value = '10:00';
    } else {
      const s = new Date(ev.start); const e = new Date(ev.end);
      f.date.value = ymd(s); f.endDate.value = ymd(e);
      f.start.value = `${pad(s.getHours())}:${pad(s.getMinutes())}`;
      f.end.value = `${pad(e.getHours())}:${pad(e.getMinutes())}`;
    }
    $('#event-source').textContent = ev.source === 'google' ? 'Googleカレンダーの予定' : 'この端末のみの予定';
  } else {
    f.allDay.checked = !!evOrDraft.allDay;
    f.date.value = evOrDraft.date;
    f.endDate.value = evOrDraft.date;
    f.start.value = evOrDraft.start || '09:00';
    f.end.value = evOrDraft.end || '10:00';
    $('#event-source').textContent = gcal.hasConnected() ? 'Googleカレンダーに保存されます' : 'この端末に保存されます（Google未連携）';
  }
  toggleTimeInputs();
  dlg.showModal();
  if (!isExisting) setTimeout(() => f.title.focus(), 50);
}

function toggleTimeInputs() {
  const f = $('#event-form');
  const allDay = f.allDay.checked;
  f.start.disabled = allDay; f.end.disabled = allDay;
  f.start.closest('.row').style.opacity = allDay ? .4 : 1;
}

function readEventForm() {
  const f = $('#event-form');
  const title = f.title.value.trim();
  const allDay = f.allDay.checked;
  const date = f.date.value;
  let endDate = f.endDate.value || date;
  if (endDate < date) endDate = date;
  const base = { title, description: f.description.value, allDay };
  if (allDay) return { ...base, start: date, end: ymd(addDays(parseYmd(endDate), 1)) };
  const s = combine(date, f.start.value || '09:00');
  let e = combine(endDate, f.end.value || '10:00');
  if (e <= s) e = new Date(s.getTime() + 60 * 60000);
  return { ...base, start: s.toISOString(), end: e.toISOString() };
}

async function saveEvent() {
  const data = readEventForm();
  if (!data.title) return;
  try {
    if (editing?.source === 'google') {
      const updated = await gcal.updateEvent({ ...editing, ...data });
      state.google = state.google.map((e) => (e.id === editing.id ? updated : e));
      store.setGoogleCache(state.google);
    } else if (editing) {
      store.putLocalEvent({ ...editing, ...data });
    } else if (gcal.hasConnected() && gcal.isReady()) {
      try {
        const created = await gcal.createEvent(data);
        state.google.push(created);
        store.setGoogleCache(state.google);
      } catch (err) {
        store.putLocalEvent({ id: store.uid(), ...data });
        toast(`Googleに保存できなかったため端末に保存しました：${err.message}`, 5000);
      }
    } else {
      store.putLocalEvent({ id: store.uid(), ...data });
    }
  } catch (err) {
    toast(err.message, 5000);
  }
  render();
}

async function removeEvent() {
  if (!editing || !confirm(`「${editing.title}」を削除しますか？`)) return;
  try {
    if (editing.source === 'google') {
      await gcal.deleteEvent(editing);
      state.google = state.google.filter((e) => e.id !== editing.id);
      store.setGoogleCache(state.google);
    } else {
      store.deleteLocalEvent(editing.id);
    }
  } catch (err) {
    toast(err.message, 5000);
  }
  $('#event-dialog').close();
  render();
}

/* =========================================================
 * 設定ダイアログ
 * ======================================================= */
function openSettings() {
  const s = store.getSettings();
  const f = $('#settings-form');
  f.clientId.value = s.clientId;
  f.calendarId.value = s.calendarId === 'primary' ? '' : s.calendarId;
  $('#google-state').textContent = gcal.hasConnected() ? '状態：Googleカレンダーと連携中' : '状態：未連携';
  $('#btn-google-signout').hidden = !gcal.hasConnected();
  $('#settings-dialog').showModal();
}

/* =========================================================
 * イベント登録
 * ======================================================= */
function bind() {
  $('#btn-prev').addEventListener('click', () => move(-1));
  $('#btn-next').addEventListener('click', () => move(1));
  $('#btn-today').addEventListener('click', () => { state.anchor = startOfDay(new Date()); render(); syncIfConnected(); });
  $('#tab-week').addEventListener('click', () => setView('week'));
  $('#tab-month').addEventListener('click', () => setView('month'));
  $('#btn-sync').addEventListener('click', () => sync({ interactive: true }));
  $('#btn-settings').addEventListener('click', openSettings);

  const f = $('#event-form');
  f.allDay.addEventListener('change', toggleTimeInputs);
  f.date.addEventListener('change', () => { if (f.endDate.value < f.date.value) f.endDate.value = f.date.value; });
  f.addEventListener('submit', (e) => { e.preventDefault(); $('#event-dialog').close(); saveEvent(); });
  $('#btn-event-cancel').addEventListener('click', () => $('#event-dialog').close());
  $('#btn-event-delete').addEventListener('click', removeEvent);

  const sf = $('#settings-form');
  sf.addEventListener('submit', (e) => {
    e.preventDefault();
    store.saveSettings({ clientId: sf.clientId.value.trim(), calendarId: sf.calendarId.value.trim() || 'primary' });
    $('#settings-dialog').close();
    if (sf.clientId.value.trim()) sync({ interactive: true });
  });
  $('#btn-settings-cancel').addEventListener('click', () => $('#settings-dialog').close());
  $('#btn-google-signout').addEventListener('click', () => {
    gcal.signOut();
    state.google = [];
    store.setGoogleCache([]);
    $('#settings-dialog').close();
    render();
    toast('Googleカレンダーとの連携を解除しました');
  });

  // キーボード：← → で前後移動
  document.addEventListener('keydown', (e) => {
    if (e.target.closest('input, textarea, dialog')) return;
    if (e.key === 'ArrowLeft') move(-1);
    if (e.key === 'ArrowRight') move(1);
  });

  // スマホ：左右スワイプで前後移動
  let touch = null;
  const view = $('#view');
  view.addEventListener('touchstart', (e) => {
    if (e.touches.length === 1) touch = { x: e.touches[0].clientX, y: e.touches[0].clientY };
  }, { passive: true });
  view.addEventListener('touchend', (e) => {
    if (!touch) return;
    const dx = e.changedTouches[0].clientX - touch.x;
    const dy = e.changedTouches[0].clientY - touch.y;
    touch = null;
    if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.5) move(dx < 0 ? 1 : -1);
  }, { passive: true });

  // 現在時刻ラインの更新・復帰時の再同期
  setInterval(() => { if (!document.querySelector('dialog[open]') && !document.activeElement?.matches('input')) render(); }, 60_000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') { render(); syncIfConnected(); }
  });
}

/* =========================================================
 * 起動
 * ======================================================= */
if (isWidget) document.body.classList.add('widget');
bind();
render();
syncIfConnected();
if (isWidget) setInterval(syncIfConnected, 5 * 60_000);

if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}
