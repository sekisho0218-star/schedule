// シフト表（Google スプレッドシート「シフト表(NEW)」）から講師のコマを読み取る
// リンクを知っている人が閲覧できるシートを、ログインなしで HTML 表示（htmlview）から読む。

const SHEET_URL_RE = /\/spreadsheets\/d\/([a-zA-Z0-9_-]+)/;

/* ---------------- 取得 ---------------- */

function sheetIdFrom(urlOrId) {
  const m = String(urlOrId || '').match(SHEET_URL_RE);
  return m ? m[1] : String(urlOrId || '').trim();
}

async function fetchText(url) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error(`シフト表を読み込めませんでした (${res.status})`);
  return res.text();
}

/** タブ名 → gid の一覧 */
async function listTabs(sheetId) {
  const html = await fetchText(`https://docs.google.com/spreadsheets/d/${sheetId}/htmlview`);
  const tabs = {};
  for (const m of html.matchAll(/name: "((?:[^"\\]|\\.)*)", pageUrl: "[^"]*", gid: "(\d+)"/g)) {
    tabs[JSON.parse(`"${m[1]}"`)] = m[2];
  }
  return tabs;
}

async function fetchGrid(sheetId, gid) {
  const html = await fetchText(`https://docs.google.com/spreadsheets/d/${sheetId}/htmlview/sheet?headers=false&gid=${gid}`);
  return parseTable(html);
}

/* ---------------- HTML の表 → 2次元配列（結合セルは左上だけに値） ---------------- */

function decode(s) {
  return s
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
    .replace(/&amp;/g, '&');
}

function parseTable(html) {
  const start = html.indexOf('<table');
  const body = start >= 0 ? html.slice(start) : html;
  const rows = [];
  let row = null; let cell = null;
  const re = /<(\/?)(tr|td|th|br|table)\b([^>]*)>|<[^>]*>|([^<]+)/gi;
  for (const m of body.matchAll(re)) {
    const [, close, tag0, attrs, text] = m;
    const tag = (tag0 || '').toLowerCase();
    if (text !== undefined) { if (cell) cell.text += decode(text); continue; }
    if (tag === 'table' && close) break;
    if (tag === 'tr' && !close) { row = []; rows.push(row); }
    else if ((tag === 'td' || tag === 'th') && !close) {
      const num = (k) => { const x = (attrs || '').match(new RegExp(`${k}="?(\\d+)`)); return x ? Number(x[1]) : 1; };
      cell = { text: '', cs: num('colspan'), rs: num('rowspan'), th: tag === 'th' };
    } else if ((tag === 'td' || tag === 'th') && close) { if (row && cell) row.push(cell); cell = null; }
    else if (tag === 'br' && cell) cell.text += '\n';
  }
  const grid = []; const taken = new Set();
  rows.forEach((r, ri) => {
    const line = []; let c = 0;
    for (const x of r.filter((x) => !x.th)) { // 行番号（th）は除く
      while (taken.has(`${ri},${c}`)) { line.push(''); c++; }
      line.push(x.text.trim());
      for (let k = 1; k < x.cs; k++) line.push('');
      for (let rr = 1; rr < x.rs; rr++) for (let cc = 0; cc < x.cs; cc++) taken.add(`${ri + rr},${c + cc}`);
      c += x.cs;
    }
    while (taken.has(`${ri},${c}`)) { line.push(''); c++; }
    grid.push(line);
  });
  return grid;
}

/* ---------------- 読み取りルール ---------------- */

const GRADE_RE = /^(小|中|高)\s*([1-6１-６])(.*)$/s;
const TIME_RE = /(\d{1,2})[:：](\d{2})/;
const SUBJECTS = { 数学: '数', 算数: '算', 英語: '英', 理科: '理', 国語: '国', 社会: '社', 数: '数', 算: '算', 英: '英', 理: '理', 国: '国', 社: '社' };
const toHalf = (s) => s.replace(/[０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0));
const noSpace = (s) => s.replace(/[\s　]/g, '');
const minutes = (t) => { const m = toHalf(t).match(TIME_RE); return m ? Number(m[1]) * 60 + Number(m[2]) : null; };
const stripParen = (s) => s.replace(/[（(][^）)]*[）)]/g, '').trim();

/** 科目 → 略称（「英語・理科」→「英・理」）。科目でなければ null */
function subjectAbbr(text) {
  const base = stripParen(text);
  const parts = base.split(/[・/／,、]/).map((p) => p.trim()).filter(Boolean);
  if (!parts.length || !parts.every((p) => SUBJECTS[p])) return null;
  return parts.map((p) => SUBJECTS[p]).join('・');
}

/** 名字（「竹内　咲希」→「竹内」、「山本パガロビン」→「山本」） */
function surname(name) {
  const sp = name.trim().split(/[\s　]+/);
  if (sp.length > 1) return sp[0];
  const m = name.match(/^[一-鿿々]+/);
  return m && m[0].length < name.length ? m[0] : name.trim();
}

/** 年の推定：今日に一番近い年 */
function resolveDate(md, today) {
  const m = md.match(/^(\d{1,2})\/(\d{1,2})$/);
  if (!m) return null;
  let best = null;
  for (const y of [today.getFullYear() - 1, today.getFullYear(), today.getFullYear() + 1]) {
    const d = new Date(y, Number(m[1]) - 1, Number(m[2]));
    if (!best || Math.abs(d - today) < Math.abs(best - today)) best = d;
  }
  return best;
}

/**
 * 1コマ分（3行×2列）のセルを読み取る。
 * 戻り値：{ students:[{grade,name,subject}], group, freeText, others:[{time,title}], override }
 */
function readSlot(cells, roster) {
  // cells[i][j]: i=行(0..2), j=列(0..1)
  const out = { students: [], group: null, freeText: [], others: [], override: null };
  const kind = (t) => {
    if (!t) return 'empty';
    if (/様/.test(t)) return 'guest';
    if (GRADE_RE.test(toHalf(t)) && toHalf(t).match(GRADE_RE)[3].replace(/[（(][^）)]*[）)]/g, '').trim() === '') return 'grade';
    if (subjectAbbr(t)) return 'subject';
    if (/^[\d:：\s～~\-]+$/.test(toHalf(t)) && TIME_RE.test(toHalf(t))) return 'time';
    if (roster.has(noSpace(t)) || /[\s　]/.test(t.trim())) return 'name';
    return 'text';
  };
  const K = cells.map((r) => r.map(kind));

  // 時刻指定（「中2(15:10)」「数学(16:05～)」）
  for (const r of cells) for (const t of r) {
    const p = t && t.match(/[（(]\s*(\d{1,2}[:：]\d{2})/);
    if (p && out.override == null) out.override = minutes(p[1]);
  }

  // 授業以外（「11:00」＋「中3竹内様」）
  for (let i = 0; i < 3; i++) {
    for (let j = 0; j < 2; j++) {
      if (K[i][j] !== 'guest') continue;
      const other = cells[i][1 - j];
      out.others.push({ time: minutes(other || '') , title: cells[i][j].trim() });
    }
  }

  // 生徒（名前のセルごとに、同じ列の上の学年・下の科目、なければ隣の列の同じ行の科目）
  for (let j = 0; j < 2; j++) {
    for (let i = 0; i < 3; i++) {
      if (K[i][j] !== 'name') continue;
      const name = cells[i][j].trim();
      let grade = null; let subject = null;
      for (let k = i - 1; k >= 0; k--) if (K[k][j] === 'grade') { grade = toHalf(cells[k][j]).match(GRADE_RE).slice(1, 3).join(''); break; }
      if (!grade) grade = roster.get(noSpace(name)) || null;
      for (let k = i + 1; k < 3; k++) if (K[k][j] === 'subject') { subject = subjectAbbr(cells[k][j]); break; }
      if (!subject && K[i][1 - j] === 'subject') subject = subjectAbbr(cells[i][1 - j]);
      out.students.push({ grade, name, subject });
    }
  }

  // 集団授業（「中1」＋「数学」で名前なし）
  if (!out.students.length) {
    for (let j = 0; j < 2 && !out.group; j++) {
      const g = K.findIndex((r) => r[j] === 'grade');
      const s = K.findIndex((r, i) => i > g && r[j] === 'subject');
      if (g >= 0 && s >= 0) {
        out.group = toHalf(cells[g][j]).match(GRADE_RE).slice(1, 3).join('') + stripParen(cells[s][j]);
      }
    }
  }
  // それ以外の書き込み（「指定校コース」など）
  if (!out.students.length && !out.group) {
    for (let i = 0; i < 3; i++) for (let j = 0; j < 2; j++) if (K[i][j] === 'text') out.freeText.push(cells[i][j].trim());
  }
  return out;
}

/**
 * 講師のコマを読み取る。
 * lessons: 授業（自動で反映） / others: 授業以外（確認して反映） / notes: 読み取れなかった書き込み
 * 時刻は手帳の予定に合わせる：開始は「16:05→16:00」のように丸め、小学生だけのコマは60分（平日16:05の枠は16:30〜）、それ以外は90分。
 */
function extractTeacher(grid, teacher, roster, today = new Date()) {
  const W = Math.max(...grid.map((r) => r.length));
  const g = grid.map((r) => [...r, ...Array(W - r.length).fill('')]);
  const lessons = []; const others = []; const notes = [];
  for (let top = 0; top < g.length - 1; top++) {
    if (!/^\d{1,2}\/\d{1,2}$/.test(g[top][1]) || !g[top + 1].some((x) => x === teacher || /^\S+$/.test(x))) continue;
    if (!/^\d{1,2}\/\d{1,2}$/.test(g[top][1])) continue;
    for (let d = 0; d < 7; d++) {
      const tc = 15 * d;
      const date = resolveDate(g[top][tc + 1] || '', today);
      if (!date) continue;
      let c = -1;
      for (let k = tc + 1; k < tc + 15; k++) if ((g[top + 1][k] || '').trim() === teacher) { c = k; break; }
      if (c < 0) continue;
      const weekday = date.getDay() >= 1 && date.getDay() <= 5;
      for (let off = 2; off <= 23; off += 3) {
        const L = top + off;
        if (L + 2 >= g.length) break;
        const cells = [0, 1, 2].map((i) => [g[L + i][c] || '', g[L + i][c + 1] || '']);
        if (!cells.flat().some((x) => x.trim())) continue;
        const label = g[L][tc] || '';
        const slot = readSlot(cells, roster);

        for (const o of slot.others) {
          const t = o.time ?? minutes(label);
          if (t == null) { notes.push({ date, text: o.title }); continue; }
          others.push({ date, start: t, end: t + 60, title: o.title });
        }

        let title = null; let elem = false;
        if (slot.students.length) {
          title = slot.students.map((s) => `${s.grade || ''}${surname(s.name)}${s.subject ? `(${s.subject})` : ''}`).join('/');
          elem = slot.students.every((s) => (s.grade || '').startsWith('小'));
        } else if (slot.group) {
          title = slot.group;
          elem = slot.group.startsWith('小');
        } else if (slot.freeText.length && !slot.others.length) {
          title = slot.freeText.join(' ');
        }
        if (!title) {
          if (!slot.others.length) notes.push({ date, text: cells.flat().filter(Boolean).join(' / ') });
          continue;
        }
        // 時刻のない書き込み（日曜の「テスト対策　ワーク進める」など）は、その日の終日の予定（授業以外）として扱う
        if (slot.freeText.length && !minutes(label) && slot.override == null) {
          others.push({ date, allDay: true, title: slot.freeText.join(' ') });
          continue;
        }

        // 開始時刻：「10:00(小) 10:30(中)」のような枠は小学生かどうかで選ぶ
        const times = [...toHalf(label).matchAll(/(\d{1,2})[:：](\d{2})/g)].map((m) => Number(m[1]) * 60 + Number(m[2]));
        let start = slot.override ?? (times.length > 1 && /中/.test(label) && !elem ? times[1] : times[0]);
        if (start == null) { notes.push({ date, text: title }); continue; }
        if (start % 30 === 5) start -= 5; // 16:05 → 16:00
        if (weekday && elem && start === 16 * 60 && slot.override == null) start = 16 * 60 + 30;
        lessons.push({ date, start, end: Math.min(start + (elem ? 60 : 90), 24 * 60), title });
      }
    }
  }
  return { lessons, others, notes };
}

/** シフト表を読み込んで講師のコマを返す */
async function loadShift({ url, teacher, tab = 'シフト表(NEW)', rosterTab = '名簿' }, today = new Date()) {
  const id = sheetIdFrom(url);
  const tabs = await listTabs(id);
  if (!tabs[tab]) throw new Error(`シフト表に「${tab}」のタブが見つかりません`);
  const roster = new Map();
  if (tabs[rosterTab]) {
    for (const r of await fetchGrid(id, tabs[rosterTab])) {
      if (/^(小|中|高)[1-6]$/.test(r[0] || '') && r[1]) roster.set(noSpace(r[1]), r[0]);
    }
  }
  const grid = await fetchGrid(id, tabs[tab]);
  return extractTeacher(grid, teacher, roster, today);
}

module.exports = { loadShift, extractTeacher, parseTable, readSlot, surname, subjectAbbr, sheetIdFrom };
