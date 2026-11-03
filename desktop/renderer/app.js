// バーチカル手帳（Windows 版）画面
// window.api（preload で公開）とトップレベルの名前がぶつからないよう、全体を関数で囲む
(() => {
const api = window.api;

/* ---------- 日付（月曜始まり・ローカル時刻） ---------- */
const DOW = ['日', '月', '火', '水', '木', '金', '土'];
const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parseYmd = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
const addDays = (d, n) => { const r = new Date(d); r.setDate(r.getDate() + n); return r; };
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const startOfWeek = (d) => addDays(startOfDay(d), -((d.getDay() + 6) % 7));
const startOfMonth = (d) => new Date(d.getFullYear(), d.getMonth(), 1);
const dayKind = (d) => (d.getDay() === 6 ? 'sat' : d.getDay() === 0 ? 'sun' : 'wd');
const sameDay = (a, b) => ymd(a) === ymd(b);
const hm = (d) => `${d.getHours()}:${pad(d.getMinutes())}`;
const combine = (dateStr, timeStr) => { const d = parseYmd(dateStr); const [h, m] = timeStr.split(':').map(Number); d.setHours(h, m); return d; };

const START_HOUR = 8;
const END_HOUR = 24;
const TASKS_PER_DAY = 3; // タスクリスト（時刻のないタスク）の件数

/*
 * タスク名の先頭の時刻を読み取る（Google Tasks API はタスクの時刻を扱えないため）。
 * 「15:00 教材発注」→ 15:00〜15:30、「15:00-16:30 面談準備」→ 15:00〜16:30 としてスケジュールに表示
 */
const TASK_COLOR = '#8d8778';
function parseTaskTime(text) {
  const t = text.replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));
  const m = t.match(/^\s*(\d{1,2})[:：](\d{2})(?:\s*[-~〜～–ー−]\s*(\d{1,2})[:：](\d{2}))?\s*(.*)$/);
  if (!m) return null;
  const h = Number(m[1]); const mi = Number(m[2]);
  if (h > 23 || mi > 59) return null;
  const start = h * 60 + mi;
  let end = m[3] ? Number(m[3]) * 60 + Number(m[4]) : start + 30;
  if (!(end > start)) end = start + 30;
  return { start, end: Math.min(end, 24 * 60), title: m[5].trim() || '(タスク)' };
}
const untimed = (list) => (list || []).filter((t) => !parseTaskTime(t.text));

/*
 * Google ToDo リストで日時を設定したタスクは、カレンダー側に時刻付きの予定として表示される
 * （Tasks API からは時刻が読めない）。同じ日に同じ名前の時刻付き予定があるタスクは
 * スケジュールにだけ表示し（「☐」を付けてクリックで完了）、タスクリストからは外す。
 */
const normTitle = (s) => s.replace(/\u3000/g, ' ').trim().toLowerCase();
function linkTasks(date, dayEvents) {
  const list = untimed(state.tasks[date]);
  const byTitle = new Map(list.map((t) => [normTitle(t.text), t]));
  const linked = new Set();
  for (const it of dayEvents.timed) {
    if (it.ev.task) continue;
    const t = byTitle.get(normTitle(it.ev.title));
    if (t && !linked.has(t.id)) {
      linked.add(t.id);
      it.ev = { ...it.ev, title: '☐ ' + it.ev.title, task: t, taskDate: date };
    }
  }
  return list.filter((t) => !linked.has(t.id));
}

/** 時刻付きタスクを、スケジュールに並べる予定の形にする */
function taskEvents() {
  const out = [];
  for (const [date, list] of Object.entries(state.tasks)) {
    for (const t of list) {
      const p = parseTaskTime(t.text);
      if (!p) continue;
      const base = parseYmd(date);
      const s = new Date(base); s.setMinutes(p.start);
      const e = new Date(base); e.setMinutes(p.end);
      out.push({ id: 'task:' + t.id, task: t, taskDate: date, title: '☐ ' + p.title, allDay: false,
        start: s.toISOString(), end: e.toISOString(), color: TASK_COLOR });
    }
  }
  return out;
}

const state = {
  view: 'week',
  anchor: startOfDay(new Date()),
  events: [],
  tasks: {}, // { 'YYYY-MM-DD': [{id, text}] }
  app: null, // メインプロセスの状態
  loading: false,
};

const $ = (s) => document.querySelector(s);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };

let toastTimer;
function toast(msg, ms = 3000) {
  const s = $('#status');
  s.textContent = msg; s.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { s.hidden = true; }, ms);
}

/** メインプロセス呼び出し（エラー時はメッセージを表示） */
async function call(promise, errorPrefix) {
  const r = await promise;
  if (r.ok) return r.data;
  if (r.needLogin) {
    state.app = await api.state();
    render();
    throw new Error(r.error);
  }
  toast(errorPrefix ? `${errorPrefix}：${r.error}` : r.error, 5000);
  throw new Error(r.error);
}

/* ---------- 予定の整理 ---------- */
function eventColor(ev) {
  const hex = (ev.color || '#4a72b5').replace('#', '');
  const n = parseInt(hex.length === 3 ? hex.split('').map((c) => c + c).join('') : hex, 16);
  const mix = (v) => Math.round(v + (255 - v) * 0.5);
  return { ev: `#${hex}`, bg: `rgb(${mix((n >> 16) & 255)},${mix((n >> 8) & 255)},${mix(n & 255)})` };
}
function styleEvent(node, ev) {
  const c = eventColor(ev);
  node.style.setProperty('--ev', c.ev);
  node.style.setProperty('--ev-bg', c.bg);
}

function eventsOn(day) {
  const dayStart = startOfDay(day); const dayEnd = addDays(dayStart, 1); const key = ymd(day);
  const allDay = []; const timed = [];
  for (const ev of [...state.events, ...taskEvents()]) {
    if (ev.allDay) { if (ev.start <= key && key < ev.end) allDay.push(ev); continue; }
    const s = new Date(ev.start); const e = new Date(ev.end);
    if (e <= dayStart || s >= dayEnd) continue;
    timed.push({ ev, s: s < dayStart ? dayStart : s, e: e > dayEnd ? dayEnd : e });
  }
  timed.sort((a, b) => a.s - b.s || b.e - a.e);
  // 重なる予定を横に並べる
  let cluster = null; const clusters = [];
  for (const it of timed) {
    if (!cluster || it.s >= cluster.end) { cluster = { items: [], end: it.e, cols: [] }; clusters.push(cluster); }
    let col = cluster.cols.findIndex((end) => end <= it.s);
    if (col === -1) { col = cluster.cols.length; cluster.cols.push(it.e); } else cluster.cols[col] = it.e;
    it.col = col; cluster.items.push(it);
    if (it.e > cluster.end) cluster.end = it.e;
  }
  clusters.forEach((c) => c.items.forEach((it) => { it.ncols = c.cols.length; }));
  return { allDay, timed };
}

/* ---------- 文字の拡大率：1日分の列の幅に比例（最大1.6倍） ---------- */
// 画面の1/4（幅およそ960px）で 1.0。狭いと少し小さく、広いと少し大きくする
function updateScale() {
  const colW = (window.innerWidth - 10 - 30) / 7;
  const ts = Math.min(1.3, Math.max(0.9, colW / 130));
  document.documentElement.style.setProperty('--ts', ts.toFixed(3));
}

/* ---------- 週間バーチカル ---------- */
function renderWeek(root) {
  const weekStart = startOfWeek(state.anchor);
  const days = [...Array(7)].map((_, i) => addDays(weekStart, i));
  const today = new Date();
  const end = days[6];
  $('#title').textContent = weekStart.getMonth() === end.getMonth()
    ? `${weekStart.getFullYear()}年${weekStart.getMonth() + 1}月${weekStart.getDate()}–${end.getDate()}日`
    : `${weekStart.getFullYear()}年${weekStart.getMonth() + 1}/${weekStart.getDate()}–${end.getMonth() + 1}/${end.getDate()}`;

  const grid = el('div', 'week');
  grid.append(el('div', 'corner'));
  for (const d of days) {
    const h = el('div', `day-head ${dayKind(d)}${sameDay(d, today) ? ' today' : ''}`);
    h.append(el('div', 'dow', DOW[d.getDay()]), el('div', 'num', d.getDate()));
    h.title = '終日の予定を追加';
    h.addEventListener('click', () => openEventDialog({ date: ymd(d), allDay: true }));
    grid.append(h);
  }

  grid.append(el('div', 'axis-label', '終日'));
  const per = days.map(eventsOn);
  // タスクリストに出すタスク（スケジュールに表示済みのものを除く）
  state.listTasks = {};
  days.forEach((d, i) => { state.listTasks[ymd(d)] = linkTasks(ymd(d), per[i]); });
  days.forEach((d, i) => {
    const cell = el('div', 'allday');
    for (const ev of per[i].allDay) {
      const it = el('div', 'allday-item ev-style', ev.title);
      styleEvent(it, ev); it.title = ev.title;
      it.addEventListener('click', () => openEventDialog(ev));
      cell.append(it);
    }
    grid.append(cell);
  });

  // 8:00〜24:00（ウィンドウの高さに合わせて1時間の高さを決める）
  const scroll = el('div', 'scroll');
  const tl = el('div', 'timeline');
  const axis = el('div', 'time-axis');
  for (let h = START_HOUR; h < END_HOUR; h++) axis.append(el('div', 'hour', h));
  tl.append(axis);
  const totalMin = (END_HOUR - START_HOUR) * 60;
  days.forEach((d, i) => {
    const col = el('div', `day-col ${dayKind(d)}`);
    for (let k = 1; k < (END_HOUR - START_HOUR) * 2; k++) {
      const line = el('div', `hline${k % 2 ? ' half' : ''}`);
      line.style.top = `${(k / ((END_HOUR - START_HOUR) * 2)) * 100}%`;
      col.append(line);
    }
    const rangeStart = new Date(d.getFullYear(), d.getMonth(), d.getDate(), START_HOUR);
    for (const it of per[i].timed) {
      let top = (it.s - rangeStart) / 60000; let bottom = (it.e - rangeStart) / 60000;
      if (bottom <= 0 || top >= totalMin) continue;
      top = Math.max(0, top); bottom = Math.min(totalMin, bottom);
      const e = el('div', 'event ev-style');
      styleEvent(e, it.ev);
      e.style.top = `${(top / totalMin) * 100}%`;
      e.style.height = `max(calc(16px * var(--ts)), calc(${((bottom - top) / totalMin) * 100}% - 2px))`;
      e.style.marginTop = '1px';
      const w = 100 / it.ncols;
      e.style.left = `calc(${w * it.col}% + 3px)`;
      e.style.width = `calc(${w}% - 6px)`;
      const name = el('div', 'name', it.ev.title);
      e.dataset.mins = String(bottom - top);
      e.append(name);
      e.title = `${hm(new Date(it.ev.start))}–${hm(new Date(it.ev.end))} ${it.ev.title}${it.ev.calendarName ? `（${it.ev.calendarName}）` : ''}${it.ev.task ? '（クリックで完了・編集）' : ''}`;
      e.addEventListener('click', (ev) => {
        ev.stopPropagation();
        if (it.ev.task) openTaskDialog(it.ev.taskDate, it.ev.task); else openEventDialog(it.ev);
      });
      col.append(e);
    }
    if (sameDay(d, today)) {
      const mins = (today - rangeStart) / 60000;
      if (mins >= 0 && mins <= totalMin) {
        const line = el('div', 'now-line'); line.style.top = `${(mins / totalMin) * 100}%`; col.append(line);
      }
    }
    col.addEventListener('click', (evt) => {
      const rect = col.getBoundingClientRect();
      const m = Math.floor((((evt.clientY - rect.top) / rect.height) * totalMin) / 30) * 30;
      const s = START_HOUR * 60 + m; const e = Math.min(s + 60, 24 * 60 - 1);
      const t = (x) => `${pad(Math.floor(x / 60))}:${pad(x % 60)}`;
      openEventDialog({ date: ymd(d), start: t(s), end: t(e) });
    });
    tl.append(col);
  });
  scroll.append(tl);
  grid.append(scroll);

  // タスク（各日5件）
  grid.append(el('div', 'axis-label tasks-label', 'TASK'));
  for (const d of days) {
    const cell = el('div', 'tasks'); cell.dataset.date = ymd(d);
    renderTasks(cell);
    grid.append(cell);
  }
  root.append(grid);

  // 1時間の高さと予定名の行数を合わせる（表示直後と、ウィンドウの大きさが変わるたびに）
  fitTimeline(scroll);
  if (fitObserver) fitObserver.disconnect();
  fitObserver = new ResizeObserver(() => fitTimeline(scroll));
  fitObserver.observe(scroll);
}

let fitObserver = null;

/** 8〜24時がスクロールせずに収まる1時間の高さにし、予定名は枠に入る行数だけ表示（入りきらない分は「…」） */
function fitTimeline(scroll) {
  const h = scroll.clientHeight;
  if (h <= 0) return;
  const hourH = Math.max(18, h / 16);
  document.documentElement.style.setProperty('--hour-h', `${hourH}px`);
  scroll.querySelectorAll('.event').forEach((ev) => {
    const name = ev.firstChild;
    const lineH = parseFloat(getComputedStyle(name).lineHeight) || 13;
    const lines = Math.max(1, Math.floor((ev.clientHeight - 1) / lineH));
    name.style.webkitLineClamp = String(lines);
    name.style.maxHeight = `${lines * lineH}px`; // 行数指定が効かない場合も行の途中で切れないように
  });
}

function renderTasks(cell, focusEmpty = false) {
  const date = cell.dataset.date;
  const tasks = (state.listTasks?.[date] || untimed(state.tasks[date])).slice(0, TASKS_PER_DAY);
  cell.replaceChildren();
  let firstEmpty = null;
  for (let i = 0; i < TASKS_PER_DAY; i++) {
    const t = tasks[i];
    const row = el('div', `task${t ? '' : ' empty'}`);
    const cb = document.createElement('input'); cb.type = 'checkbox'; cb.title = '完了';
    const input = document.createElement('input'); input.className = 'task-text'; input.maxLength = 200;
    if (t) {
      input.value = t.text; input.title = t.text; input.readOnly = true;
      input.addEventListener('click', () => openTaskDialog(date, t));
      cb.addEventListener('change', () => {
        if (!cb.checked) return;
        row.classList.add('done');
        setTimeout(async () => {
          state.tasks[date] = (state.tasks[date] || []).filter((x) => x.id !== t.id);
          if (state.listTasks?.[date]) state.listTasks[date] = state.listTasks[date].filter((x) => x.id !== t.id);
          renderTasks(cell);
          await call(api.completeTask(t.id), 'タスクを完了にできませんでした').catch(() => loadTasks());
        }, 400);
      });
    } else {
      input.placeholder = '+';
      input.title = 'タスクを追加（「15:00 〇〇」のように時刻を付けるとスケジュールに表示）';
      firstEmpty ||= input;
      let committed = false;
      const commit = async (refocus) => {
        const v = input.value.trim();
        if (!v || committed) return;
        committed = true;
        const temp = { id: 'tmp-' + Date.now(), text: v };
        (state.tasks[date] ||= []).push(temp);
        if (state.listTasks?.[date] && !parseTaskTime(v)) state.listTasks[date].push(temp);
        // 時刻付き（「15:00 〇〇」）はスケジュールに出るので画面全体を描き直す
        const timed = !!parseTaskTime(v);
        const redraw = (focus) => (timed ? render() : renderTasks(cell, focus));
        redraw(refocus);
        try {
          const id = await call(api.addTask(date, v), 'タスクを追加できませんでした');
          temp.id = id;
          redraw(false);
        } catch {
          state.tasks[date] = state.tasks[date].filter((x) => x !== temp);
          if (state.listTasks?.[date]) state.listTasks[date] = state.listTasks[date].filter((x) => x !== temp);
          redraw(false);
        }
      };
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); commit(true); } });
      input.addEventListener('blur', () => commit(false));
    }
    row.append(cb, input);
    cell.append(row);
  }
  if (focusEmpty && firstEmpty) firstEmpty.focus();
}

/* ---------- 月間 ---------- */
function renderMonth(root) {
  const first = startOfMonth(state.anchor);
  const gridStart = startOfWeek(first);
  const last = new Date(first.getFullYear(), first.getMonth() + 1, 0);
  const weeks = Math.ceil(((last - gridStart) / 86400000 + 1) / 7);
  const today = new Date();
  $('#title').textContent = `${first.getFullYear()}年${first.getMonth() + 1}月`;

  const grid = el('div', 'month');
  grid.style.gridTemplateRows = `auto repeat(${weeks}, minmax(0, 1fr))`;
  for (let i = 0; i < 7; i++) { const d = addDays(gridStart, i); grid.append(el('div', `mhead ${dayKind(d)}`, DOW[d.getDay()])); }
  const ts = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--ts')) || 1;
  const cellH = (root.clientHeight - 30) / weeks;
  const maxItems = Math.max(1, Math.floor((cellH - 34 * ts) / (15 * ts)));

  for (let i = 0; i < weeks * 7; i++) {
    const d = addDays(gridStart, i);
    const cell = el('div', `mcell ${dayKind(d)}${d.getMonth() !== first.getMonth() ? ' other' : ''}${sameDay(d, today) ? ' today' : ''}`);
    cell.append(el('div', 'mnum', d.getDate()));
    const dayEv = eventsOn(d);
    const listForDay = linkTasks(ymd(d), dayEv);
    const { allDay, timed } = dayEv;
    const items = [...allDay.map((ev) => ({ ev, label: ev.title, cls: 'allday' })), ...timed.map(({ ev }) => ({ ev, label: ev.title, cls: '' }))];
    for (const it of items.slice(0, maxItems)) {
      const m = el('div', `mitem ${it.cls}`, it.label); styleEvent(m, it.ev); m.title = it.label;
      if (it.ev.task) m.addEventListener('click', (e) => { e.stopPropagation(); openTaskDialog(it.ev.taskDate, it.ev.task); });
      cell.append(m);
    }
    if (items.length > maxItems) cell.append(el('div', 'mmore', `他${items.length - maxItems}件`));
    const n = listForDay.length;
    if (n) cell.append(el('div', 'mtasks', `☐ タスク${n}`));
    cell.addEventListener('click', () => { state.anchor = d; setView('week'); });
    grid.append(cell);
  }
  root.append(grid);
}

/* ---------- ログインの期限切れ ---------- */
function renderExpired(root) {
  $('#title').textContent = 'バーチカル手帳';
  const box = el('div', 'setup');
  const waiting = state.app.signingIn;
  box.innerHTML = `<h2>Google へのログインが必要です</h2>
    <p>ログインの有効期限（7日）が切れました。${waiting
      ? '<b>ブラウザに Google のログイン画面を開きました。</b>アカウントを選んで「続行」を押すと、自動で表示が戻ります。'
      : 'ボタンを押すと、ブラウザに Google のログイン画面が開きます。'}</p>
    <p class="hint">「Google はこのアプリを確認していません」と表示されたら「続行」を選んでください。</p>`;
  const b = el('button', null, waiting ? 'ログイン画面をもう一度開く' : 'Google にログイン');
  b.addEventListener('click', signIn);
  box.append(b);
  root.append(box);
}

async function signIn() {
  toast('ブラウザで Google にログインしてください…', 8000);
  try {
    state.app = await call(api.signIn(), 'ログインできませんでした');
    toast('ログインしました');
    render(); reload();
  } catch { /* toast 済み */ }
}

/* ---------- 初期設定の案内 ---------- */
function renderSetup(root) {
  $('#title').textContent = 'バーチカル手帳';
  const box = el('div', 'setup');
  box.innerHTML = `<h2>はじめに</h2>
    <p>Google カレンダーの予定と Google ToDo リストのタスクを表示するには、Google へのログインが必要です。</p>
    <ol><li>Google Cloud で「デスクトップ アプリ」の OAuth クライアントを作る（README 参照）</li>
    <li>⚙ 設定でクライアントID・シークレットを入力して「保存してGoogleにログイン」</li></ol>`;
  const b = el('button', null, '設定を開く');
  b.addEventListener('click', openSettings);
  box.append(b);
  root.append(box);
}

/* ---------- 画面全体 ---------- */
function render() {
  updateScale();
  const root = $('#view');
  root.replaceChildren();
  const ready = state.app?.signedIn;
  if (!ready && state.app?.expired) renderExpired(root);
  else if (!ready) renderSetup(root);
  else if (state.view === 'month') renderMonth(root);
  else renderWeek(root);
  $('#tab-week').classList.toggle('active', state.view === 'week');
  $('#tab-month').classList.toggle('active', state.view === 'month');
  const top = state.app?.mode === 'top';
  $('#btn-mode').textContent = top ? '📌' : '🖥';
  $('#btn-mode').title = top ? '常に手前に表示中（クリックでデスクトップに貼り付け）' : 'デスクトップに貼り付け中（クリックで常に手前に表示）';
  $('#btn-mode').classList.toggle('on', top);
  $('#btn-lock').textContent = state.app?.locked ? '🔒' : '🔓';
  $('#btn-lock').classList.toggle('on', !!state.app?.locked);
  document.body.classList.toggle('locked', !!state.app?.locked);
}

function range() {
  const from = startOfWeek(startOfMonth(state.anchor));
  return { from, to: addDays(from, 42) };
}

async function loadTasks() {
  const { from, to } = range();
  try {
    state.tasks = await call(api.listTasks(ymd(from), ymd(to)), 'タスクを読み込めませんでした');
  } catch { /* 表示済みのまま */ }
}

async function reload() {
  if (!state.app?.signedIn || state.loading) return;
  state.loading = true;
  $('#btn-sync').classList.add('syncing');
  const { from, to } = range();
  try {
    const [events] = await Promise.all([
      call(api.listEvents(from.toISOString(), to.toISOString()), '予定を読み込めませんでした').catch(() => state.events),
      loadTasks(),
    ]);
    state.events = events;
  } finally {
    state.loading = false;
    $('#btn-sync').classList.remove('syncing');
  }
  // 入力中のタスクを消さないよう、入力中は描き直さない
  if (!document.activeElement?.matches('.task-text') && !document.querySelector('dialog[open]')) render();
}

function setView(v) {
  state.view = v;
  api.settings({ view: v });
  render();
  reload();
}

function move(dir) {
  state.anchor = state.view === 'month'
    ? new Date(state.anchor.getFullYear(), state.anchor.getMonth() + dir, 1)
    : addDays(state.anchor, 7 * dir);
  render();
  reload();
}

/* ---------- 予定ダイアログ ---------- */
let editing = null;
function openEventDialog(evOrDraft) {
  const f = $('#event-form');
  const existing = !!evOrDraft.id;
  editing = existing ? evOrDraft : null;
  f.reset();
  $('#event-dialog-title').textContent = existing ? '予定を編集' : '予定を追加';
  $('#btn-event-delete').hidden = !existing || !evOrDraft.writable;
  if (existing) {
    const ev = evOrDraft;
    f.title.value = ev.title; f.description.value = ev.description || ''; f.allDay.checked = ev.allDay;
    if (ev.allDay) {
      f.date.value = ev.start; f.endDate.value = ymd(addDays(parseYmd(ev.end), -1)); f.start.value = '09:00'; f.end.value = '10:00';
    } else {
      const s = new Date(ev.start); const e = new Date(ev.end);
      f.date.value = ymd(s); f.endDate.value = ymd(e);
      f.start.value = `${pad(s.getHours())}:${pad(s.getMinutes())}`; f.end.value = `${pad(e.getHours())}:${pad(e.getMinutes())}`;
    }
    $('#event-source').textContent = `カレンダー：${ev.calendarName || ''}${ev.writable ? '' : '（閲覧のみ）'}`;
  } else {
    f.allDay.checked = !!evOrDraft.allDay; f.date.value = evOrDraft.date; f.endDate.value = evOrDraft.date;
    f.start.value = evOrDraft.start || '09:00'; f.end.value = evOrDraft.end || '10:00';
    $('#event-source').textContent = 'メインの Google カレンダーに保存されます';
  }
  toggleTimes();
  $('#event-dialog').showModal();
  if (!existing) setTimeout(() => f.title.focus(), 30);
}
function toggleTimes() {
  const f = $('#event-form');
  f.start.disabled = f.end.disabled = f.allDay.checked;
  f.start.closest('.row2').style.opacity = f.allDay.checked ? 0.4 : 1;
}
function readEventForm() {
  const f = $('#event-form');
  const allDay = f.allDay.checked; const date = f.date.value;
  let endDate = f.endDate.value || date; if (endDate < date) endDate = date;
  const base = { title: f.title.value.trim(), description: f.description.value, allDay };
  if (allDay) return { ...base, start: date, end: ymd(addDays(parseYmd(endDate), 1)) };
  const s = combine(date, f.start.value || '09:00');
  let e = combine(endDate, f.end.value || '10:00');
  if (e <= s) e = new Date(s.getTime() + 3600_000);
  return { ...base, start: s.toISOString(), end: e.toISOString() };
}
async function saveEvent() {
  const data = readEventForm();
  if (!data.title) return;
  try {
    if (editing) await call(api.updateEvent({ ...editing, ...data }), '予定を保存できませんでした');
    else await call(api.createEvent(data), '予定を保存できませんでした');
  } catch { /* toast 済み */ }
  reload();
}
async function deleteEvent() {
  if (!editing || !confirm(`「${editing.title}」を削除しますか？`)) return;
  $('#event-dialog').close();
  try { await call(api.deleteEvent(editing), '予定を削除できませんでした'); } catch { /* toast 済み */ }
  reload();
}

/* ---------- タスク編集ダイアログ ---------- */
let editingTask = null;
function openTaskDialog(date, task) {
  editingTask = { date, task };
  $('#task-form').text.value = task.text;
  $('#task-dialog').showModal();
}

/* ---------- 設定 ---------- */
function openSettings() {
  const f = $('#settings-form'); const s = state.app;
  f.clientId.value = s.clientId || ''; f.clientSecret.value = '';
  f.querySelectorAll('input[name=mode]').forEach((r) => { r.checked = r.value === s.mode; });
  f.locked.checked = !!s.locked; f.autostart.checked = !!s.autostart;
  f.opacity.value = s.opacity || 1; $('#opacity-value').textContent = `${Math.round((s.opacity || 1) * 100)}%`;
  $('#google-state').textContent = s.signedIn ? '状態：ログイン済み（予定・タスクを同期中）' : '状態：未ログイン';
  $('#btn-signout').hidden = !s.signedIn;
  $('#app-version').textContent = s.version ? `バージョン ${s.version}` : '';
  // シフト表との連携
  const sh = s.shift || {};
  f.shiftEnabled.checked = !!sh.enabled;
  f.shiftAuto.checked = !!sh.auto;
  f.shiftUrl.value = sh.url || '';
  f.shiftTeacher.value = sh.teacher || '赤松';
  updateShiftStatus();
  const sel = f.shiftCalendar;
  sel.replaceChildren(new Option('メインのカレンダー', 'primary'));
  sel.value = 'primary';
  if (s.signedIn) {
    api.shiftCalendars().then((r) => {
      if (!r.ok) return;
      for (const c of r.data) if (!c.primary) sel.append(new Option(c.name, c.id));
      sel.value = sh.calendarId || 'primary';
    });
  }
  $('#settings-dialog').showModal();
}

/* ---------- シフト表との違い ---------- */
function updateShiftStatus() {
  const sh = state.app?.shift || {};
  const last = sh.lastRun ? new Date(sh.lastRun) : null;
  $('#shift-status').textContent = !sh.enabled ? '状態：オフ'
    : last ? `前回の照合：${last.getMonth() + 1}/${last.getDate()} ${hm(last)}　${sh.lastMessage || ''}`
    : '状態：オン（まだ照合していません）';
}

const OP_LABEL = { add: '追加', update: '変更', delete: '削除', remove: '削除' };
let shiftDialogResult = null;

function showShiftResult(r) {
  if (!r) return;
  if (!r.ok) { if (r.error) toast(`シフト表：${r.error}`, 6000); return; }
  const pending = r.lessonOps.length + r.proposals.length;
  if (r.applied.length) toast(`シフト表の授業を${r.applied.length}件反映しました`, 5000);
  if (r.failed?.length) toast(`反映できなかった予定が${r.failed.length}件あります`, 6000);
  if (!pending) {
    if (r.manual && !r.applied.length) toast('シフト表とカレンダーに違いはありません');
    return;
  }
  shiftDialogResult = r;
  const sec = (title, inner) => `<div class="shift-sec"><h3>${title}</h3><div class="shift-list">${inner}</div></div>`;
  const esc = (t) => t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  $('#shift-applied').innerHTML = r.applied.length
    ? sec(`自動で反映した授業（${r.applied.length}件）`, r.applied.map((d) => `<div>${esc(d)}</div>`).join('')) : '';
  $('#shift-lessons').innerHTML = r.lessonOps.length
    ? sec('授業の変更（初回のみ確認。以後は自動で反映）',
      `<label><input type="checkbox" id="shift-apply-lessons" checked> 次の${r.lessonOps.length}件を反映し、今後は授業を自動で反映する</label>`
      + r.lessonOps.map((o) => `<div><span class="tag ${o.op}">${OP_LABEL[o.op]}</span>${esc(o.desc)}</div>`).join('')) : '';
  const adds = r.proposals.filter((p) => p.type === 'add');
  const rms = r.proposals.filter((p) => p.type === 'remove');
  const item = (p) => `<label><input type="checkbox" data-key="${esc(p.key)}" ${p.checked ? 'checked' : ''}><span><span class="tag ${p.type}">${OP_LABEL[p.type]}</span>${esc(p.desc)}</span></label>`;
  $('#shift-proposals').innerHTML = (adds.length ? sec('授業以外は反映しますか？（シフト表にあってカレンダーにない予定）', adds.map(item).join('')) : '')
    + (rms.length ? sec('シフト表にない予定です。削除しますか？', rms.map(item).join('')) : '');
  $('#shift-notes').innerHTML = r.notes?.length
    ? `<p class="hint">読み取れなかった書き込み：${esc(r.notes.slice(0, 5).join('、'))}${r.notes.length > 5 ? ' ほか' : ''}</p>` : '';
  if (!$('#shift-dialog').open) $('#shift-dialog').showModal();
}

async function submitShift(apply) {
  const r = shiftDialogResult; shiftDialogResult = null;
  $('#shift-dialog').close();
  if (!r) return;
  const keys = apply ? [...document.querySelectorAll('#shift-proposals input[data-key]:checked')].map((i) => i.dataset.key) : [];
  const applyLessons = apply && !!document.querySelector('#shift-apply-lessons')?.checked;
  const res = await api.shiftApply({ applyLessons, keys });
  if (res.applied?.length) toast(`${res.applied.length}件をカレンダーに反映しました`);
  if (res.failed?.length) toast(`反映できなかった予定が${res.failed.length}件あります`, 6000);
  state.app = await api.state();
  reload();
}

/* ---------- イベント登録 ---------- */
function bind() {
  $('#btn-prev').addEventListener('click', () => move(-1));
  $('#btn-next').addEventListener('click', () => move(1));
  $('#btn-today').addEventListener('click', () => { state.anchor = startOfDay(new Date()); render(); reload(); });
  $('#tab-week').addEventListener('click', () => setView('week'));
  $('#tab-month').addEventListener('click', () => setView('month'));
  $('#btn-sync').addEventListener('click', reload);
  $('#btn-settings').addEventListener('click', openSettings);
  $('#btn-hide').addEventListener('click', () => api.hide());
  $('#btn-mode').addEventListener('click', async () => {
    state.app = await api.settings({ mode: state.app.mode === 'top' ? 'desktop' : 'top' });
    render();
    toast(state.app.mode === 'top' ? '常に手前に表示します' : 'デスクトップに貼り付けます（他のウィンドウの後ろに表示）');
  });
  $('#btn-lock').addEventListener('click', async () => {
    state.app = await api.settings({ locked: !state.app.locked });
    render();
    toast(state.app.locked ? '位置とサイズを固定しました' : '位置とサイズの固定を解除しました');
  });

  const f = $('#event-form');
  f.allDay.addEventListener('change', toggleTimes);
  f.date.addEventListener('change', () => { if (f.endDate.value < f.date.value) f.endDate.value = f.date.value; });
  f.addEventListener('submit', (e) => { e.preventDefault(); $('#event-dialog').close(); saveEvent(); });
  $('#btn-event-cancel').addEventListener('click', () => $('#event-dialog').close());
  $('#btn-event-delete').addEventListener('click', deleteEvent);

  const tf = $('#task-form');
  tf.addEventListener('submit', async (e) => {
    e.preventDefault(); $('#task-dialog').close();
    const { date, task } = editingTask; const v = tf.text.value.trim();
    if (!v || v === task.text) return;
    task.text = v; render();
    try { await call(api.updateTask(task.id, v), 'タスクを変更できませんでした'); } catch { loadTasks().then(render); }
  });
  $('#btn-task-cancel').addEventListener('click', () => $('#task-dialog').close());
  $('#btn-task-done').addEventListener('click', async () => {
    $('#task-dialog').close();
    const { date, task } = editingTask;
    state.tasks[date] = (state.tasks[date] || []).filter((x) => x.id !== task.id); render();
    try { await call(api.completeTask(task.id), 'タスクを完了にできませんでした'); } catch { loadTasks().then(render); }
  });

  const sf = $('#settings-form');
  sf.addEventListener('submit', (e) => { e.preventDefault(); $('#settings-dialog').close(); });
  sf.querySelectorAll('input[name=mode]').forEach((r) => r.addEventListener('change', async () => { state.app = await api.settings({ mode: r.value }); render(); }));
  sf.locked.addEventListener('change', async () => { state.app = await api.settings({ locked: sf.locked.checked }); render(); });
  sf.autostart.addEventListener('change', async () => { state.app = await api.settings({ autostart: sf.autostart.checked }); });
  sf.opacity.addEventListener('input', async () => {
    $('#opacity-value').textContent = `${Math.round(sf.opacity.value * 100)}%`;
    state.app = await api.settings({ opacity: parseFloat(sf.opacity.value) });
  });
  $('#btn-signin').addEventListener('click', async () => {
    if (!sf.clientId.value.trim()) { toast('クライアントIDを入力してください'); return; }
    state.app = await api.saveClient({ clientId: sf.clientId.value, clientSecret: sf.clientSecret.value });
    if (!state.app.hasClient) { toast('クライアントシークレットを入力してください'); return; }
    $('#settings-dialog').close();
    signIn();
  });
  $('#btn-signout').addEventListener('click', async () => {
    state.app = await api.signOut(); state.events = []; state.tasks = {};
    $('#settings-dialog').close(); render();
  });
  // シフト表との連携
  const saveShiftSettings = async () => {
    state.app = await api.shiftSettings({
      enabled: sf.shiftEnabled.checked, auto: sf.shiftAuto.checked, url: sf.shiftUrl.value.trim(),
      teacher: sf.shiftTeacher.value.trim() || '赤松', calendarId: sf.shiftCalendar.value || 'primary',
    });
    updateShiftStatus();
  };
  sf.shiftEnabled.addEventListener('change', saveShiftSettings);
  sf.shiftAuto.addEventListener('change', saveShiftSettings);
  sf.shiftUrl.addEventListener('change', saveShiftSettings);
  sf.shiftTeacher.addEventListener('change', saveShiftSettings);
  sf.shiftCalendar.addEventListener('change', saveShiftSettings);
  $('#btn-shift-run').addEventListener('click', async () => {
    await saveShiftSettings();
    toast('シフト表と照合しています…', 8000);
    const r = await api.shiftRun();
    state.app = await api.state(); updateShiftStatus();
    $('#settings-dialog').close();
    showShiftResult(r);
  });
  $('#btn-shift-reset').addEventListener('click', async () => {
    await api.shiftResetDismissed();
    toast('「反映しない」にした項目を、次回の照合でもう一度確認します');
  });
  $('#shift-form').addEventListener('submit', (e) => { e.preventDefault(); submitShift(true); });
  $('#btn-shift-skip').addEventListener('click', () => submitShift(false));
  api.onShiftResult((r) => { showShiftResult(r); api.state().then((st) => { state.app = st; }); });

  $('#link-readme').addEventListener('click', (e) => {
    e.preventDefault();
    api.openExternal('https://github.com/sekisho0218-star/schedule/blob/ccr-71be2548-2602dq/desktop/README.md');
  });

  document.addEventListener('keydown', (e) => {
    if (e.target.closest('input, textarea, dialog')) return;
    if (e.key === 'ArrowLeft') move(-1);
    if (e.key === 'ArrowRight') move(1);
  });
  let resizeTimer;
  window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 120); });
  api.onRefresh(() => reload());
  api.onState((s) => { state.app = s; render(); });
  // 5分ごとに同期、1分ごとに現在時刻ラインを更新
  setInterval(reload, 5 * 60_000);
  setInterval(() => { if (!document.activeElement?.matches('.task-text') && !document.querySelector('dialog[open]')) render(); }, 60_000);
}

(async function init() {
  state.app = await api.state();
  state.view = state.app.view || 'week';
  bind();
  render();
  reload();
})();
})();
