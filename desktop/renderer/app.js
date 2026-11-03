// バーチカル手帳（Windows 版）画面
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
const TASKS_PER_DAY = 5;

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
  for (const ev of state.events) {
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
function updateScale() {
  const colW = (window.innerWidth - 12 - 38) / 7;
  const ts = Math.min(1.6, Math.max(1, colW / 70));
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
      e.style.height = `max(calc(18px * var(--ts)), calc(${((bottom - top) / totalMin) * 100}% - 3px))`;
      e.style.marginTop = '1px';
      const w = 100 / it.ncols;
      e.style.left = `calc(${w * it.col}% + 3px)`;
      e.style.width = `calc(${w}% - 6px)`;
      e.append(document.createTextNode(it.ev.title));
      e.title = `${hm(new Date(it.ev.start))}–${hm(new Date(it.ev.end))} ${it.ev.title}${it.ev.calendarName ? `（${it.ev.calendarName}）` : ''}`;
      e.addEventListener('click', (ev) => { ev.stopPropagation(); openEventDialog(it.ev); });
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

  // 1時間の高さ：スクロールせずに 8〜24時が収まるように
  requestAnimationFrame(() => {
    const h = scroll.clientHeight;
    if (h > 0) document.documentElement.style.setProperty('--hour-h', `${Math.max(24, h / 16)}px`);
  });
}

function renderTasks(cell, focusEmpty = false) {
  const date = cell.dataset.date;
  const tasks = (state.tasks[date] || []).slice(0, TASKS_PER_DAY);
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
          renderTasks(cell);
          await call(api.completeTask(t.id), 'タスクを完了にできませんでした').catch(() => loadTasks());
        }, 400);
      });
    } else {
      input.placeholder = '+';
      firstEmpty ||= input;
      let committed = false;
      const commit = async (refocus) => {
        const v = input.value.trim();
        if (!v || committed) return;
        committed = true;
        const temp = { id: 'tmp-' + Date.now(), text: v };
        (state.tasks[date] ||= []).push(temp);
        renderTasks(cell, refocus);
        try {
          const id = await call(api.addTask(date, v), 'タスクを追加できませんでした');
          temp.id = id;
          renderTasks(cell);
        } catch {
          state.tasks[date] = state.tasks[date].filter((x) => x !== temp);
          renderTasks(cell);
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
  const maxItems = Math.max(1, Math.floor((cellH - 40 * ts) / (16 * ts)));

  for (let i = 0; i < weeks * 7; i++) {
    const d = addDays(gridStart, i);
    const cell = el('div', `mcell ${dayKind(d)}${d.getMonth() !== first.getMonth() ? ' other' : ''}${sameDay(d, today) ? ' today' : ''}`);
    cell.append(el('div', 'mnum', d.getDate()));
    const { allDay, timed } = eventsOn(d);
    const items = [...allDay.map((ev) => ({ ev, label: ev.title, cls: 'allday' })), ...timed.map(({ ev }) => ({ ev, label: ev.title, cls: '' }))];
    for (const it of items.slice(0, maxItems)) {
      const m = el('div', `mitem ${it.cls}`, it.label); styleEvent(m, it.ev); m.title = it.label; cell.append(m);
    }
    if (items.length > maxItems) cell.append(el('div', 'mmore', `他${items.length - maxItems}件`));
    const n = (state.tasks[ymd(d)] || []).length;
    if (n) cell.append(el('div', 'mtasks', `☐ タスク${n}`));
    cell.addEventListener('click', () => { state.anchor = d; setView('week'); });
    grid.append(cell);
  }
  root.append(grid);
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
  if (!ready) renderSetup(root);
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
  $('#settings-dialog').showModal();
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
    toast('ブラウザで Google にログインしてください…', 8000);
    try {
      state.app = await call(api.signIn(), 'ログインできませんでした');
      toast('ログインしました');
      render(); reload();
    } catch { /* toast 済み */ }
  });
  $('#btn-signout').addEventListener('click', async () => {
    state.app = await api.signOut(); state.events = []; state.tasks = {};
    $('#settings-dialog').close(); render();
  });
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
