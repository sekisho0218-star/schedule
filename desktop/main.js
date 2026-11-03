// バーチカル手帳（Windows デスクトップ版）メインプロセス
const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, screen, shell, Notification } = require('electron');
const path = require('node:path');
const config = require('./config');
const google = require('./google');
const shiftSync = require('./shiftsync');

let win = null;
let tray = null;
let quitting = false;
let signingIn = null; // 実行中のログイン（Promise）
let autoPrompted = false; // 期限切れで自動的にログイン画面を開いたか

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  app.whenReady().then(start);
}

const iconPath = path.join(__dirname, 'build', 'icon.png');

function defaultBounds() {
  const area = screen.getPrimaryDisplay().workArea;
  // 初期サイズは画面の1/4
  const width = Math.round(area.width / 2);
  const height = Math.round(area.height / 2);
  return { width, height, x: area.x + area.width - width, y: area.y };
}

/** 保存した位置が今の画面の外なら初期位置に戻す */
function validBounds(b) {
  if (!b) return defaultBounds();
  const visible = screen.getAllDisplays().some((d) => {
    const a = d.workArea;
    return b.x < a.x + a.width - 50 && b.x + b.width > a.x + 50 && b.y >= a.y - 10 && b.y < a.y + a.height - 50;
  });
  return visible ? b : defaultBounds();
}

function start() {
  app.setAppUserModelId('jp.sekisho.vertical.desktop');
  const cfg = config.load();
  win = new BrowserWindow({
    ...validBounds(cfg.bounds),
    minWidth: 480,
    minHeight: 360,
    frame: false,
    show: false,
    skipTaskbar: true,
    backgroundColor: '#f1eee6',
    icon: iconPath,
    title: 'バーチカル手帳',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      // 他のウィンドウの後ろにあっても画面の更新（予定名の行数調整など）を止めない
      backgroundThrottling: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => {
    applyWindowSettings();
    win.showInactive();
    if (publicState().expired) setTimeout(promptReLogin, 1500);
  });

  // 位置・大きさを保存
  let saveTimer = null;
  const saveBounds = () => {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => config.save({ bounds: win.getBounds() }), 500);
  };
  win.on('move', saveBounds);
  win.on('resize', saveBounds);

  // デスクトップに貼り付けモードでは「デスクトップの表示（Win+D）」などで最小化されても表示し直す
  win.on('minimize', () => {
    if (config.load().mode === 'desktop' && !quitting) setTimeout(() => win.restore(), 150);
  });
  win.on('close', (e) => {
    if (!quitting) { e.preventDefault(); win.hide(); }
  });
  win.on('focus', () => win.webContents.send('refresh'));

  // リンクは既定のブラウザで開く
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });

  createTray();

  // シフト表との照合：起動20秒後と、30分ごと
  setTimeout(() => runShiftSync(false), 20_000);
  setInterval(() => runShiftSync(false), 30 * 60_000);
}

function applyWindowSettings() {
  const cfg = config.load();
  if (cfg.mode === 'top') {
    win.setAlwaysOnTop(true, 'floating');
  } else {
    win.setAlwaysOnTop(false);
  }
  win.setSkipTaskbar(true);
  win.setOpacity(Math.min(1, Math.max(0.4, cfg.opacity || 1)));
  win.setMovable(!cfg.locked);
  win.setResizable(!cfg.locked);
  app.setLoginItemSettings({ openAtLogin: !!cfg.autostart });
  updateTrayMenu();
  win.webContents.send('state', publicState());
}

function showWindow() {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function publicState() {
  const cfg = config.load();
  return {
    hasClient: !!(cfg.clientId && cfg.clientSecret),
    clientId: cfg.clientId,
    signedIn: google.isSignedIn(),
    expired: !google.isSignedIn() && !!cfg.wasSignedIn && !!(cfg.clientId && cfg.clientSecret),
    signingIn: !!signingIn,
    mode: cfg.mode,
    locked: cfg.locked,
    opacity: cfg.opacity,
    autostart: cfg.autostart,
    view: cfg.view,
    version: app.getVersion(),
    shift: {
      enabled: cfg.shift.enabled, url: cfg.shift.url, teacher: cfg.shift.teacher, calendarId: cfg.shift.calendarId,
      confirmedOnce: cfg.shift.confirmedOnce, lastRun: cfg.shift.lastRun, lastMessage: cfg.shift.lastMessage,
    },
  };
}

function setSettings(patch) {
  config.save(patch);
  applyWindowSettings();
  return publicState();
}

/* ---------------- タスクトレイ ---------------- */

function createTray() {
  const img = nativeImage.createFromPath(iconPath).resize({ width: 16, height: 16 });
  tray = new Tray(img);
  tray.setToolTip('バーチカル手帳');
  tray.on('click', () => (win.isVisible() ? win.hide() : showWindow()));
  updateTrayMenu();
}

function updateTrayMenu() {
  if (!tray) return;
  const cfg = config.load();
  const opacityItem = (v) => ({
    label: `${Math.round(v * 100)}%`, type: 'radio', checked: Math.abs((cfg.opacity || 1) - v) < 0.01,
    click: () => setSettings({ opacity: v }),
  });
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '表示 / 非表示', click: () => (win.isVisible() ? win.hide() : showWindow()) },
    { type: 'separator' },
    { label: 'デスクトップに貼り付け', type: 'radio', checked: cfg.mode === 'desktop', click: () => setSettings({ mode: 'desktop' }) },
    { label: '常に手前に表示', type: 'radio', checked: cfg.mode === 'top', click: () => setSettings({ mode: 'top' }) },
    { type: 'separator' },
    { label: '位置とサイズを固定', type: 'checkbox', checked: !!cfg.locked, click: (i) => setSettings({ locked: i.checked }) },
    { label: '透明度', submenu: [1, 0.9, 0.8, 0.7, 0.6].map(opacityItem) },
    { label: 'Windows の起動時に開く', type: 'checkbox', checked: !!cfg.autostart, click: (i) => setSettings({ autostart: i.checked }) },
    { label: '位置を初期化', click: () => win.setBounds(defaultBounds()) },
    { type: 'separator' },
    { label: '終了', click: () => { quitting = true; app.quit(); } },
  ]));
}

/* ---------------- 画面（renderer）とのやりとり ---------------- */

/** Google ログイン（同時に1つだけ）。終わったら画面に知らせて再読み込みさせる */
function runSignIn() {
  if (signingIn) {
    google.reopenSignIn();
    return signingIn;
  }
  signingIn = google.signIn()
    .then(() => { autoPrompted = false; showWindow(); })
    .finally(() => {
      signingIn = null;
      if (win) {
        win.webContents.send('state', publicState());
        if (google.isSignedIn()) win.webContents.send('refresh');
      }
    });
  win?.webContents.send('state', publicState());
  return signingIn;
}

/**
 * ログインの期限切れ（Google Cloud の「テスト」モードでは7日）を検知したら、
 * 自動でブラウザに Google のログイン画面を開く（期限切れ1回につき1度だけ）
 */
function promptReLogin() {
  const cfg = config.load();
  if (autoPrompted || signingIn || !cfg.wasSignedIn || !cfg.clientId || !cfg.clientSecret) return;
  autoPrompted = true;
  runSignIn().catch(() => { /* キャンセル時は画面の「ログイン」ボタンから再試行 */ });
}

/** エラーを画面側で扱える形に変換 */
const wrap = (fn) => async (_e, ...args) => {
  try {
    return { ok: true, data: await fn(...args) };
  } catch (err) {
    const needLogin = err.code === 'NEED_LOGIN';
    if (needLogin) promptReLogin();
    return { ok: false, error: err.message, needLogin };
  }
};

ipcMain.handle('state', () => publicState());
ipcMain.handle('settings', (_e, patch) => {
  const allowed = {};
  for (const k of ['mode', 'locked', 'opacity', 'autostart', 'view']) if (k in patch) allowed[k] = patch[k];
  return setSettings(allowed);
});
ipcMain.handle('save-client', (_e, { clientId, clientSecret }) => {
  const cur = config.load();
  const changed = cur.clientId !== clientId.trim();
  config.save({ clientId: clientId.trim(), ...(clientSecret ? { clientSecret: clientSecret.trim() } : {}) });
  if (changed) google.signOut();
  return publicState();
});
ipcMain.handle('sign-in', wrap(async () => { await runSignIn(); return publicState(); }));
ipcMain.handle('sign-out', () => { google.signOut(); return publicState(); });
ipcMain.handle('list-events', wrap((from, to) => google.listEvents(from, to)));
ipcMain.handle('create-event', wrap((ev) => google.createEvent(ev)));
ipcMain.handle('update-event', wrap((ev) => google.updateEvent(ev)));
ipcMain.handle('delete-event', wrap((ev) => google.deleteEvent(ev)));
ipcMain.handle('list-tasks', wrap((from, to) => google.listTasks(from, to)));
ipcMain.handle('add-task', wrap((date, text) => google.addTask(date, text)));
ipcMain.handle('update-task', wrap((id, text) => google.updateTask(id, text)));
ipcMain.handle('complete-task', wrap((id) => google.completeTask(id)));
ipcMain.handle('hide', () => win.hide());
ipcMain.handle('open-external', (_e, url) => {
  if (/^https:\/\//.test(url)) shell.openExternal(url);
});

/* ---------------- シフト表との照合 ---------------- */

let shiftRunning = false;
let shiftPending = null; // 確認待ちの内容 { lessonOps, proposals }

function saveShift(patch) {
  config.save({ shift: { ...config.load().shift, ...patch } });
}

/**
 * シフト表と Google カレンダーを照合する。
 * 授業の違いは自動で反映（初回だけは確認）。授業以外とシフト表にない予定は、画面で確認してから反映。
 */
async function runShiftSync(manual) {
  const st = config.load().shift;
  if (!st.enabled || !st.url || !google.isSignedIn() || shiftRunning) {
    if (manual && !google.isSignedIn()) return { ok: false, error: 'Google にログインしてください' };
    if (manual && (!st.enabled || !st.url)) return { ok: false, error: '設定でシフト表の URL を入力し、連携をオンにしてください' };
    return { ok: false, error: '照合中です' };
  }
  shiftRunning = true;
  try {
    const r = await shiftSync.compare(st);
    const dismissed = new Set(st.dismissed);
    const proposals = r.proposals.filter((p) => !dismissed.has(p.key));
    const applied = [];
    const failed = [];
    let lessonOps = [];
    if (st.confirmedOnce) {
      for (const op of r.lessonOps) {
        try { await shiftSync.applyLessonOp(op); applied.push(op.desc); } catch (e) { failed.push(`${op.desc}（${e.message}）`); }
      }
    } else {
      lessonOps = r.lessonOps; // 初回は授業の変更も確認してから
    }
    shiftPending = { lessonOps, proposals };
    const parts = [];
    if (applied.length) parts.push(`授業を${applied.length}件反映`);
    if (lessonOps.length || proposals.length) parts.push(`確認待ち${lessonOps.length + proposals.length}件`);
    if (!parts.length) parts.push('違いはありません');
    saveShift({ lastRun: new Date().toISOString(), lastMessage: parts.join('・') });
    const result = { ok: true, applied, failed, lessonOps: lessonOps.map(({ op, desc }) => ({ op, desc })),
      proposals: proposals.map(({ key, type, desc, checked }) => ({ key, type, desc, checked })), notes: r.notes, manual };
    win?.webContents.send('shift-result', result);
    if (applied.length) win?.webContents.send('refresh');
    if (!manual && (lessonOps.length || proposals.length) && Notification.isSupported()) {
      const n = new Notification({ title: 'バーチカル手帳', body: 'シフト表に、反映するか確認が必要な予定があります。', icon: iconPath });
      n.on('click', () => { showWindow(); win.webContents.send('shift-result', result); });
      n.show();
    }
    return result;
  } catch (e) {
    saveShift({ lastRun: new Date().toISOString(), lastMessage: `エラー：${e.message}` });
    if (e.code === 'NEED_LOGIN') promptReLogin();
    return { ok: false, error: e.message };
  } finally {
    shiftRunning = false;
  }
}

/** 確認画面の結果を反映。チェックしなかった項目は次回から聞かない */
async function applyShiftChoices({ applyLessons, keys }) {
  if (!shiftPending) return { ok: true, applied: [] };
  const { lessonOps, proposals } = shiftPending;
  shiftPending = null;
  const chosen = new Set(keys);
  const applied = []; const failed = [];
  if (applyLessons && lessonOps.length) {
    for (const op of lessonOps) {
      try { await shiftSync.applyLessonOp(op); applied.push(op.desc); } catch (e) { failed.push(`${op.desc}（${e.message}）`); }
    }
    saveShift({ confirmedOnce: true });
  }
  for (const p of proposals) {
    if (!chosen.has(p.key)) continue;
    try { await shiftSync.applyProposal(p); applied.push(p.desc); } catch (e) { failed.push(`${p.desc}（${e.message}）`); }
  }
  const st = config.load().shift;
  const dismissed = [...new Set([...st.dismissed, ...proposals.filter((p) => !chosen.has(p.key)).map((p) => p.key)])].slice(-1000);
  saveShift({ dismissed });
  win?.webContents.send('refresh');
  return { ok: true, applied, failed };
}

ipcMain.handle('shift-settings', (_e, patch) => {
  const allowed = {};
  for (const k of ['enabled', 'url', 'teacher', 'calendarId']) if (k in patch) allowed[k] = patch[k];
  // 照合の条件が変わったら、初回確認からやり直す
  if ('url' in allowed || 'teacher' in allowed || 'calendarId' in allowed) allowed.confirmedOnce = false;
  saveShift(allowed);
  return publicState();
});
ipcMain.handle('shift-run', () => runShiftSync(true));
ipcMain.handle('shift-apply', (_e, choice) => applyShiftChoices(choice));
ipcMain.handle('shift-calendars', wrap(() => google.writableCalendars()));
ipcMain.handle('shift-reset-dismissed', () => { saveShift({ dismissed: [] }); return true; });

app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', (e) => e.preventDefault());
