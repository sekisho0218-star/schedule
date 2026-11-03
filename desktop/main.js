// バーチカル手帳（Windows デスクトップ版）メインプロセス
const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, screen, shell } = require('electron');
const path = require('node:path');
const config = require('./config');
const google = require('./google');

let win = null;
let tray = null;
let quitting = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  app.whenReady().then(start);
}

const iconPath = path.join(__dirname, 'build', 'icon.png');

function defaultBounds() {
  const area = screen.getPrimaryDisplay().workArea;
  const width = Math.min(1100, Math.round(area.width * 0.6));
  const height = Math.min(820, Math.round(area.height * 0.85));
  return { width, height, x: area.x + area.width - width - 20, y: area.y + 20 };
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
    minWidth: 520,
    minHeight: 420,
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
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => {
    applyWindowSettings();
    win.showInactive();
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
    mode: cfg.mode,
    locked: cfg.locked,
    opacity: cfg.opacity,
    autostart: cfg.autostart,
    view: cfg.view,
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

/** エラーを画面側で扱える形に変換 */
const wrap = (fn) => async (_e, ...args) => {
  try {
    return { ok: true, data: await fn(...args) };
  } catch (err) {
    return { ok: false, error: err.message, needLogin: err.code === 'NEED_LOGIN' };
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
ipcMain.handle('sign-in', wrap(async () => { await google.signIn(); showWindow(); return publicState(); }));
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

app.on('before-quit', () => { quitting = true; });
app.on('window-all-closed', (e) => e.preventDefault());
