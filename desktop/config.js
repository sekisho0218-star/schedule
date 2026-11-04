// 設定の保存（%APPDATA%\VerticalTecho\config.json）。Google のリフレッシュトークンは OS の暗号化機能で保護する
const { app, safeStorage } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const file = () => path.join(app.getPath('userData'), 'config.json');

const defaults = {
  clientId: '',
  clientSecret: '',
  refreshToken: '', // 暗号化済み（base64）
  wasSignedIn: false, // 一度ログインしたことがある（期限切れのとき自動でログイン画面を開くため）
  mode: 'desktop', // 'desktop' = デスクトップに貼り付け / 'top' = 常に手前
  locked: false,
  opacity: 1,
  autostart: false,
  view: 'week',
  startToday: false, // 週間表示：true なら今日を一番左（false は月曜始まり）
  bounds: null,
  // シフト表との連携
  shift: {
    enabled: false,
    auto: false, // このPCで30分ごとに自動照合（通常はスマホで自動照合するのでオフ）
    url: '',
    teacher: '赤松',
    calendarId: 'primary',
    confirmedOnce: false, // 初回は授業の変更も確認してから反映
    dismissed: [], // 「反映しない」を選んだ項目（次回から聞かない）
    lastRun: null,
    lastMessage: '',
  },
};

let cache = null;

function load() {
  if (cache) return cache;
  try {
    const saved = JSON.parse(fs.readFileSync(file(), 'utf8'));
    cache = { ...defaults, ...saved, shift: { ...defaults.shift, ...(saved.shift || {}) } };
  } catch {
    cache = { ...defaults, shift: { ...defaults.shift } };
  }
  return cache;
}

function save(patch) {
  cache = { ...load(), ...patch };
  fs.mkdirSync(path.dirname(file()), { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(cache, null, 2));
  return cache;
}

function setRefreshToken(token) {
  if (!token) return save({ refreshToken: '' });
  const enc = safeStorage.isEncryptionAvailable()
    ? 'enc:' + safeStorage.encryptString(token).toString('base64')
    : 'raw:' + Buffer.from(token).toString('base64');
  return save({ refreshToken: enc });
}

function getRefreshToken() {
  const v = load().refreshToken;
  if (!v) return '';
  try {
    if (v.startsWith('enc:')) return safeStorage.decryptString(Buffer.from(v.slice(4), 'base64'));
    if (v.startsWith('raw:')) return Buffer.from(v.slice(4), 'base64').toString();
  } catch { /* 復号できない場合は再ログイン */ }
  return '';
}

module.exports = { load, save, setRefreshToken, getRefreshToken };
