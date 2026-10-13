'use strict';

/* ------------------------------------------------------------------
   Дневник — локальное минималистичное приложение.
   • Заметки дня — в правой панели, важные можно закреплять (★)
   • Время учёбы (ч/м) + таймер, который само пишет минуты
   • Статус дня (нет → зелёный → жёлтый → красный) красит рамку ячейки
   • Цель по времени в день с полоской прогресса
   • Неделя, серия дней и статистика месяца, лента всех заметок
   Данные хранятся только в localStorage этого браузера.
   Никаких сетевых запросов, внешних скриптов и аналитики нет.
------------------------------------------------------------------ */

const NOTES_KEY = 'diary.entries.v1';
const MARKS_KEY = 'diary.marks.v1';
const SETTINGS_KEY = 'diary.settings.v1';
const TIMER_KEY = 'diary.timer.v1';

const MAX_ENTRIES_PER_DAY = 200;
const MAX_TEXT_LENGTH = 5000;
const MAX_MINUTES_PER_DAY = 24 * 60;
const DEFAULT_GOAL_MINUTES = 120;

const STATUSES = ['', 'green', 'yellow', 'red'];

/* Белый список тем: значение из localStorage принимается только если есть в этом списке */
const THEMES = ['mono', 'night', 'ocean', 'forest', 'sunset', 'sakura', 'lavender', 'paper', 'mist'];

const THEME_NAMES = {
  mono: 'Чёрно-белый',
  night: 'Ночь',
  ocean: 'Океан',
  forest: 'Лес',
  sunset: 'Закат',
  sakura: 'Сакура',
  lavender: 'Лаванда',
  paper: 'Бумага',
  mist: 'Мгла'
};

const MONTHS = [
  'Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'
];

const MONTHS_GENITIVE = [
  'января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'
];

const MONTHS_SHORT = [
  'ЯНВ', 'ФЕВ', 'МАР', 'АПР', 'МАЙ', 'ИЮН',
  'ИЮЛ', 'АВГ', 'СЕН', 'ОКТ', 'НОЯ', 'ДЕК'
];

const WEEKDAYS = [
  'воскресенье', 'понедельник', 'вторник', 'среда',
  'четверг', 'пятница', 'суббота'
];

/* ------------------------- Утилиты ------------------------------ */

function uid() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID();
  }
  return 'e' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
}

function pad(n) {
  return String(n).padStart(2, '0');
}

function clampInt(value, min, max) {
  const n = Math.floor(Number(value));
  if (!Number.isFinite(n)) return min;
  return Math.min(max, Math.max(min, n));
}

function dateKey(year, month, day) {
  return year + '-' + pad(month + 1) + '-' + pad(day);
}

function todayKey() {
  const d = new Date();
  return dateKey(d.getFullYear(), d.getMonth(), d.getDate());
}

function parseKey(key) {
  const p = key.split('-').map(Number);
  return { y: p[0], m: p[1] - 1, d: p[2] };
}

function keyToDate(key) {
  const p = parseKey(key);
  return new Date(p.y, p.m, p.d);
}

function isValidKey(key) {
  if (typeof key !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(key)) return false;
  const parts = key.split('-').map(Number);
  const y = parts[0];
  const m = parts[1];
  const d = parts[2];
  if (m < 1 || m > 12 || d < 1 || d > 31) return false;
  const dt = new Date(y, m - 1, d);
  return dt.getFullYear() === y && dt.getMonth() === m - 1 && dt.getDate() === d;
}

function safeRead(key) {
  try {
    return localStorage.getItem(key);
  } catch (err) {
    console.warn('Нет доступа к localStorage:', err);
    return null;
  }
}

function safeWrite(key, value) {
  try {
    localStorage.setItem(key, value);
    return true;
  } catch (err) {
    console.warn(err);
    return false;
  }
}

function safeRemove(key) {
  try { localStorage.removeItem(key); } catch (err) { console.warn(err); }
}

function loadJSON(key) {
  const raw = safeRead(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (err) {
    console.warn('Данные повреждены (' + key + '):', err);
    return null;
  }
}

function formatMinutes(total) {
  if (!total) return '0ч 00м';
  return Math.floor(total / 60) + 'ч ' + pad(total % 60) + 'м';
}

function formatClock(totalSec) {
  const s = Math.max(0, Math.floor(totalSec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  return h ? h + ':' + pad(m) + ':' + pad(sec) : pad(m) + ':' + pad(sec);
}

/* ------------------------- Валидация данных -------------------- */

function sanitizeNotes(source) {
  if (!source || typeof source !== 'object' || Array.isArray(source)) return {};

  const out = {};
  for (const key of Object.keys(source)) {
    if (!isValidKey(key)) continue;
    const list = source[key];
    if (!Array.isArray(list)) continue;

    const clean = [];
    for (const item of list) {
      if (!item || typeof item !== 'object') continue;
      const text = typeof item.text === 'string' ? item.text.slice(0, MAX_TEXT_LENGTH) : '';
      if (!text.trim()) continue;
      clean.push({
        id: typeof item.id === 'string' && item.id.length <= 64 ? item.id : uid(),
        text: text,
        pinned: item.pinned === true,
        createdAt: Number.isFinite(item.createdAt) ? item.createdAt : Date.now(),
        updatedAt: Number.isFinite(item.updatedAt) ? item.updatedAt : null
      });
      if (clean.length >= MAX_ENTRIES_PER_DAY) break;
    }
    if (clean.length) out[key] = clean;
  }
  return out;
}

function sanitizeMarks(source) {
  if (!source || typeof source !== 'object' || Array.isArray(source)) return {};

  const out = {};
  for (const key of Object.keys(source)) {
    if (!isValidKey(key)) continue;
    const item = source[key];
    if (!item || typeof item !== 'object') continue;

    const minutes = clampInt(item.minutes, 0, MAX_MINUTES_PER_DAY);
    const status = STATUSES.indexOf(item.status) >= 0 ? item.status : '';
    if (!minutes && !status) continue;

    out[key] = { minutes: minutes, status: status };
  }
  return out;
}

function sanitizeSettings(source) {
  let goal = DEFAULT_GOAL_MINUTES;
  let theme = 'mono';
  if (source && source !== null && typeof source === 'object') {
    if (Number.isFinite(Number(source.goalMinutes))) {
      goal = clampInt(source.goalMinutes, 0, MAX_MINUTES_PER_DAY);
    }
    if (THEMES.indexOf(source.theme) >= 0) {
      theme = source.theme;
    }
  }
  return { goalMinutes: goal, theme: theme };
}

function sanitizeTimer(source) {
  if (!source || typeof source !== 'object') return null;
  if (!isValidKey(source.dayKey)) return null;
  return {
    dayKey: source.dayKey,
    running: source.running === true,
    startedAt: Number.isFinite(source.startedAt) && source.startedAt > 0 ? source.startedAt : 0,
    baseSec: clampInt(source.baseSec, 0, MAX_MINUTES_PER_DAY * 60),
    committedMin: clampInt(source.committedMin, 0, MAX_MINUTES_PER_DAY)
  };
}

/* ------------------------- Состояние ---------------------------- */

let notes = sanitizeNotes(loadJSON(NOTES_KEY));
let marks = sanitizeMarks(loadJSON(MARKS_KEY));
let settings = sanitizeSettings(loadJSON(SETTINGS_KEY));
let timer = sanitizeTimer(loadJSON(TIMER_KEY));

let viewYear = new Date().getFullYear();
let viewMonth = new Date().getMonth();
let selectedDate = null;
let editingId = null;
let confirmAction = null;

function saveNotes() {
  if (safeWrite(NOTES_KEY, JSON.stringify(notes))) return true;
  toast('Не удалось сохранить: хранилище недоступно');
  return false;
}

function saveMarks() {
  if (safeWrite(MARKS_KEY, JSON.stringify(marks))) return true;
  toast('Не удалось сохранить: хранилище недоступно');
  return false;
}

function saveSettings() {
  return safeWrite(SETTINGS_KEY, JSON.stringify(settings));
}

function saveTimer() {
  if (timer) return safeWrite(TIMER_KEY, JSON.stringify(timer));
  safeRemove(TIMER_KEY);
  return true;
}

/* ------------------------- Отмена действий ---------------------- */

const UNDO_LIMIT = 40;
let undoStack = [];

function snapshot() {
  return {
    notes: JSON.parse(JSON.stringify(notes)),
    marks: JSON.parse(JSON.stringify(marks)),
    timer: timer ? JSON.parse(JSON.stringify(timer)) : null
  };
}

/* Вызывается ДО пользовательского изменения (таймер сюда не пишет) */
function pushUndo() {
  try {
    undoStack.push(snapshot());
    if (undoStack.length > UNDO_LIMIT) undoStack.shift();
  } catch (err) {
    console.warn(err);
  }
  updateUndoBtn();
}

function updateUndoBtn() {
  els.undoBtn.disabled = undoStack.length === 0;
}

function undoAction() {
  const state = undoStack.pop();
  if (!state) {
    toast('Нечего отменять');
    updateUndoBtn();
    return;
  }
  notes = sanitizeNotes(state.notes);
  marks = sanitizeMarks(state.marks);
  timer = sanitizeTimer(state.timer);
  saveNotes();
  saveMarks();
  saveTimer();
  cancelEdit();
  render();
  if (selectedDate) renderPanel();
  updateTimerUI();
  updateUndoBtn();
  toast('Действие отменено');
}

/* ------------------------- DOM ---------------------------------- */

const $ = (id) => document.getElementById(id);

const els = {
  days: $('days'),
  monthLabel: $('monthLabel'),
  yearLabel: $('yearLabel'),
  week: $('week'),
  weekTitle: $('weekTitle'),
  streakLabel: $('streakLabel'),
  stats: $('stats'),
  panelEmpty: $('panelEmpty'),
  panelContent: $('panelContent'),
  dayTitle: $('dayTitle'),
  panelCount: $('panelCount'),
  hoursInput: $('hoursInput'),
  minutesInput: $('minutesInput'),
  resetStudy: $('resetStudy'),
  timerBtn: $('timerBtn'),
  timerTime: $('timerTime'),
  timerNote: $('timerNote'),
  goalInput: $('goalInput'),
  goalMinutesInput: $('goalMinutesInput'),
  timerMinus: $('timerMinus'),
  timerPlus: $('timerPlus'),
  timerReset: $('timerReset'),
  goalBar: $('goalBar'),
  goalLabel: $('goalLabel'),
  progress: null, // определяется ниже
  statuses: $('statuses'),
  entryForm: $('entryForm'),
  entryText: $('entryText'),
  entriesList: $('entriesList'),
  cancelEdit: $('cancelEdit'),
  editHint: $('editHint'),
  monthDialog: $('monthDialog'),
  monthGrid: $('monthGrid'),
  mYearLabel: $('mYearLabel'),
  yearDialog: $('yearDialog'),
  yearGrid: $('yearGrid'),
  yDecadeLabel: $('yDecadeLabel'),
  feedDialog: $('feedDialog'),
  feedFilter: $('feedFilter'),
  feedList: $('feedList'),
  feedBtn: $('feedBtn'),
  themeBtn: $('themeBtn'),
  themeDialog: $('themeDialog'),
  themeGrid: $('themeGrid'),
  jumpDate: $('jumpDate'),
  yearBtn: $('yearBtn'),
  overviewDialog: $('overviewDialog'),
  ovTitle: $('ovTitle'),
  ovTotals: $('ovTotals'),
  ovGrid: $('ovGrid'),
  ovWeek: $('ovWeek'),
  helpDialog: $('helpDialog'),
  helpBtn: $('helpBtn'),
  undoBtn: $('undoBtn'),
  privacyBtn: $('privacyBtn'),
  mdBtn: $('mdBtn'),
  confirmDialog: $('confirmDialog'),
  confirmTitle: $('confirmTitle'),
  confirmText: $('confirmText'),
  confirmOk: $('confirmOk'),
  searchBar: $('searchBar'),
  searchInput: $('searchInput'),
  searchResults: $('searchResults'),
  searchCount: $('searchCount'),
  searchBtn: $('searchBtn'),
  toast: $('toast')
};

els.progress = els.goalBar ? els.goalBar.parentElement : null;

/* ------------------------- Расчётные показатели ------------------ */

function markFor(key) {
  return marks[key] || { minutes: 0, status: '' };
}

function isDayMarked(key) {
  const m = marks[key];
  return !!(m && (m.minutes > 0 || m.status));
}

function goalInfo(key) {
  const goal = settings.goalMinutes;
  const minutes = markFor(key).minutes;
  if (!goal) return { visible: false, pct: 0, done: false, minutes: minutes, goal: 0 };
  return {
    visible: true,
    pct: Math.min(100, Math.round((minutes / goal) * 100)),
    done: minutes >= goal,
    minutes: minutes,
    goal: goal
  };
}

/* Серия непрерывных отмеченных дней (сегодня или со вчера) */
function streakCount() {
  let cursor = new Date();
  if (!isDayMarked(todayKey())) cursor.setDate(cursor.getDate() - 1);
  let n = 0;
  for (;;) {
    const key = dateKey(cursor.getFullYear(), cursor.getMonth(), cursor.getDate());
    if (!isDayMarked(key)) break;
    n++;
    cursor.setDate(cursor.getDate() - 1);
    if (n > 3650) break;
  }
  return n;
}

function monthStats(year, month) {
  const days = new Date(year, month + 1, 0).getDate();
  const stats = { total: 0, worked: 0, marked: 0, notes: 0, green: 0, yellow: 0, red: 0 };
  for (let d = 1; d <= days; d++) {
    const key = dateKey(year, month, d);
    const m = marks[key];
    if (m) {
      if (m.minutes > 0) {
        stats.total += m.minutes;
        stats.worked++;
      }
      if (isDayMarked(key)) stats.marked++;
      if (m.status === 'green') stats.green++;
      else if (m.status === 'yellow') stats.yellow++;
      else if (m.status === 'red') stats.red++;
    }
    stats.notes += (notes[key] || []).length;
  }
  stats.avg = stats.worked ? Math.round(stats.total / stats.worked) : 0;
  return stats;
}

/* ------------------------- Календарь ---------------------------- */

function timeLabel(minutes) {
  if (!minutes) return { text: '__ч __м', filled: false };
  return { text: Math.floor(minutes / 60) + 'ч ' + pad(minutes % 60) + 'м', filled: true };
}

function statusTitle(status) {
  if (status === 'green') return 'зелёный';
  if (status === 'yellow') return 'жёлтый';
  if (status === 'red') return 'красный';
  return 'не отмечен';
}

function applyStatusClasses(el, status) {
  el.classList.remove('st-green', 'st-yellow', 'st-red');
  if (status) el.classList.add('st-' + status);
}

function render() {
  els.monthLabel.textContent = MONTHS[viewMonth];
  els.yearLabel.textContent = String(viewYear);
  renderCalendar();
  renderWeek();
  renderStats();
}

function renderCalendar() {
  const first = new Date(viewYear, viewMonth, 1);
  const offset = (first.getDay() + 6) % 7;           // пн = 0 … вс = 6
  const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
  const totalCells = Math.ceil((offset + daysInMonth) / 7) * 7;
  const tKey = todayKey();
  const frag = document.createDocumentFragment();

  for (let i = 0; i < totalCells; i++) {
    const dayNum = i - offset + 1;

    if (dayNum < 1 || dayNum > daysInMonth) {
      const empty = document.createElement('div');
      empty.className = 'day is-empty';
      empty.setAttribute('aria-hidden', 'true');
      frag.appendChild(empty);
      continue;
    }

    const key = dateKey(viewYear, viewMonth, dayNum);
    const mark = markFor(key);
    const noteList = notes[key] || [];
    const pinnedCount = noteList.filter((n) => n.pinned).length;
    const time = timeLabel(mark.minutes);
    const goal = goalInfo(key);

    const cell = document.createElement('div');
    cell.className = 'day';
    cell.dataset.key = key;
    cell.tabIndex = 0;
    cell.setAttribute('role', 'button');
    applyStatusClasses(cell, mark.status);

    const weekday = new Date(viewYear, viewMonth, dayNum).getDay();
    cell.setAttribute('aria-label',
      dayNum + ' ' + MONTHS_GENITIVE[viewMonth] + ' ' + viewYear + ', ' + WEEKDAYS[weekday] +
      (mark.minutes ? ', учёба: ' + time.text : ', учёба не отмечена') +
      ', заметок: ' + noteList.length + ', статус: ' + statusTitle(mark.status) +
      (goal.visible ? ', цель: ' + goal.pct + '%' : ''));

    const num = document.createElement('span');
    num.className = 'day-num';

    const numText = document.createElement('span');
    numText.textContent = pad(dayNum);
    num.appendChild(numText);

    if (pinnedCount) {
      const star = document.createElement('span');
      star.className = 'pin-star';
      star.textContent = '★';
      star.title = 'Есть закреплённые заметки';
      num.appendChild(star);
    }

    const foot = document.createElement('span');
    foot.className = 'day-foot';

    const timeSpan = document.createElement('span');
    timeSpan.className = 'day-time' + (time.filled ? ' has-time' : '');
    timeSpan.textContent = time.text;

    const markBtn = document.createElement('button');
    markBtn.type = 'button';
    markBtn.className = 'mark' + (mark.status ? ' is-set' : '');
    markBtn.dataset.key = key;
    markBtn.dataset.status = mark.status;
    markBtn.title = 'Статус дня: ' + statusTitle(mark.status);
    markBtn.setAttribute('aria-label', statusTitle(mark.status) + ' — изменить');
    if (mark.status === 'green') markBtn.textContent = '✓';

    foot.append(timeSpan, markBtn);

    const progress = document.createElement('span');
    progress.className = 'day-progress';
    const fill = document.createElement('i');
    progress.appendChild(fill);
    if (goal.visible) {
      fill.style.width = goal.pct + '%';
      if (goal.done) progress.classList.add('is-done');
    } else {
      progress.hidden = true;
    }

    cell.append(num, foot, progress);

    if (key === tKey) cell.classList.add('is-today');
    if (key === selectedDate) cell.classList.add('is-selected');
    if (noteList.length) cell.classList.add('has-note');

    frag.appendChild(cell);
  }

  els.days.replaceChildren(frag);
}

function refreshCell(key) {
  if (!key) return;
  const cell = els.days.querySelector('.day[data-key="' + key + '"]');
  if (cell) {
    const mark = markFor(key);
    const time = timeLabel(mark.minutes);
    const timeSpan = cell.querySelector('.day-time');
    timeSpan.textContent = time.text;
    timeSpan.classList.toggle('has-time', time.filled);

    const markBtn = cell.querySelector('.mark');
    markBtn.className = 'mark' + (mark.status ? ' is-set' : '');
    markBtn.dataset.status = mark.status;
    markBtn.textContent = mark.status === 'green' ? '✓' : '';
    markBtn.title = 'Статус дня: ' + statusTitle(mark.status);

    applyStatusClasses(cell, mark.status);

    const noteList = notes[key] || [];
    cell.classList.toggle('has-note', noteList.length > 0);

    let star = cell.querySelector('.pin-star');
    const hasPinned = noteList.some((n) => n.pinned);
    if (hasPinned && !star) {
      star = document.createElement('span');
      star.className = 'pin-star';
      star.textContent = '★';
      cell.querySelector('.day-num').appendChild(star);
    } else if (!hasPinned && star) {
      star.remove();
    }

    const goal = goalInfo(key);
    const progress = cell.querySelector('.day-progress');
    if (goal.visible) {
      progress.hidden = false;
      progress.querySelector('i').style.width = goal.pct + '%';
      progress.classList.toggle('is-done', goal.done);
    } else {
      progress.hidden = true;
    }
  }
  renderWeek();
  renderStats();
}

/* ------------------------- Неделя и серия ----------------------- */

function renderWeek() {
  const anchor = selectedDate ? keyToDate(selectedDate) : new Date();
  const dow = (anchor.getDay() + 6) % 7;
  const monday = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate() - dow);
  const tKey = todayKey();
  const frag = document.createDocumentFragment();

  for (let i = 0; i < 7; i++) {
    const d = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + i);
    const key = dateKey(d.getFullYear(), d.getMonth(), d.getDate());
    const mark = markFor(key);
    const time = timeLabel(mark.minutes);

    const item = document.createElement('div');
    item.className = 'week-day';
    item.dataset.key = key;
    item.tabIndex = 0;
    item.setAttribute('role', 'button');
    item.setAttribute('aria-label', d.getDate() + ' ' + MONTHS_GENITIVE[d.getMonth()] +
      ', учёба: ' + time.text + ', статус: ' + statusTitle(mark.status));
    applyStatusClasses(item, mark.status);

    const top = document.createElement('span');
    top.className = 'wd-top';

    const num = document.createElement('span');
    num.className = 'wd-num';
    num.textContent = pad(d.getDate());

    const dot = document.createElement('span');
    dot.className = 'wd-dot';

    top.append(num, dot);

    const timeSpan = document.createElement('span');
    timeSpan.className = 'wd-time' + (time.filled ? ' has-time' : '');
    timeSpan.textContent = time.filled ? time.text : '__ч __м';

    item.append(top, timeSpan);

    if (key === tKey) item.classList.add('is-today');
    if (key === selectedDate) item.classList.add('is-selected');

    frag.appendChild(item);
  }

  els.week.replaceChildren(frag);

  const sunday = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + 6);
  const endLabel = monday.getMonth() === sunday.getMonth()
    ? monday.getDate() + '–' + sunday.getDate() + ' ' + MONTHS_SHORT[sunday.getMonth()]
    : monday.getDate() + ' ' + MONTHS_SHORT[monday.getMonth()] + ' – ' +
      sunday.getDate() + ' ' + MONTHS_SHORT[sunday.getMonth()];
  els.weekTitle.textContent = 'НЕДЕЛЯ ' + endLabel;

  const streak = streakCount();
  els.streakLabel.textContent = streak ? 'СЕРИЯ ' + streak + ' дн.' : 'СЕРИЯ 0 дн.';
  els.streakLabel.classList.toggle('is-on', streak > 0);
}

/* ------------------------- Статистика месяца -------------------- */

function renderStats() {
  const s = monthStats(viewYear, viewMonth);
  const frag = document.createDocumentFragment();

  const make = (label, value) => {
    const span = document.createElement('span');
    const l = document.createElement('span');
    l.textContent = label + ' ';
    const b = document.createElement('b');
    b.textContent = value;
    span.append(l, b);
    return span;
  };

  frag.appendChild(make('ЧАСЫ', formatMinutes(s.total)));
  frag.appendChild(make('РАБОТ', String(s.worked)));
  frag.appendChild(make('СРЕДНЕЕ', formatMinutes(s.avg)));
  frag.appendChild(make('ЗАМЕТОК', String(s.notes)));

  const dots = document.createElement('span');
  dots.className = 'stat-dots';
  [['green', s.green], ['yellow', s.yellow], ['red', s.red]].forEach(([cls, val]) => {
    const i = document.createElement('i');
    i.className = cls;
    const b = document.createElement('b');
    b.textContent = String(val);
    dots.append(i, b);
  });
  frag.appendChild(dots);

  els.stats.replaceChildren(frag);
}

/* ------------------------- Панель дня --------------------------- */

function selectDay(key) {
  if (!isValidKey(key)) return;
  selectedDate = key;
  cancelEdit();
  renderPanel();
  renderCalendar();
  renderWeek();

  const cell = els.days.querySelector('.day[data-key="' + key + '"]');
  if (cell) cell.focus({ preventScroll: true });
}

function renderPanel() {
  if (!selectedDate) {
    els.panelEmpty.hidden = false;
    els.panelContent.hidden = true;
    return;
  }

  els.panelEmpty.hidden = true;
  els.panelContent.hidden = false;

  const { y, m, d } = parseKey(selectedDate);
  els.dayTitle.textContent = d + ' ' + MONTHS_GENITIVE[m] + ' ' + y;

  syncStudyInputs();
  updateGoalUI();
  updateTimerUI();

  els.statuses.querySelectorAll('.status-dot').forEach((dot) => {
    dot.classList.toggle('is-active', dot.dataset.status === markFor(selectedDate).status);
  });

  renderEntries();
}

function syncStudyInputs() {
  if (!selectedDate) return;
  const mark = markFor(selectedDate);
  els.hoursInput.value = String(Math.floor(mark.minutes / 60));
  els.minutesInput.value = String(mark.minutes % 60);
}

function renderEntries() {
  const list = selectedDate ? (notes[selectedDate] || []) : [];
  els.entriesList.replaceChildren();

  const pinned = list.filter((n) => n.pinned).length;
  els.panelCount.textContent = list.length
    ? (pinned ? '★' + pinned + ' · ' : '') + list.length + ' шт.'
    : '';

  if (!list.length) {
    const li = document.createElement('li');
    li.className = 'empty-note';
    li.textContent = 'Заметок пока нет';
    els.entriesList.appendChild(li);
    return;
  }

  const sorted = list.slice().sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    return a.createdAt - b.createdAt;
  });

  for (const item of sorted) {
    const li = document.createElement('li');
    li.className = 'entry' + (item.pinned ? ' is-pinned' : '');

    if (item.pinned) {
      const head = document.createElement('div');
      head.className = 'entry-head';
      const star = document.createElement('span');
      star.className = 'star';
      star.textContent = '★';
      const label = document.createElement('span');
      label.textContent = 'ВАЖНОЕ';
      head.append(star, label);
      li.appendChild(head);
    }

    const p = document.createElement('p');
    p.className = 'entry-text';
    p.textContent = item.text; // textContent — HTML из текста не интерпретируется

    const meta = document.createElement('div');
    meta.className = 'entry-meta';

    const time = document.createElement('span');
    time.className = 'entry-time';
    time.textContent = formatTime(item.updatedAt || item.createdAt);

    const buttons = document.createElement('div');
    buttons.className = 'entry-buttons';

    const pinBtn = document.createElement('button');
    pinBtn.type = 'button';
    pinBtn.className = 'btn-ghost' + (item.pinned ? ' is-on' : '');
    pinBtn.textContent = '★';
    pinBtn.title = item.pinned ? 'Открепить' : 'Закрепить важное';
    pinBtn.addEventListener('click', () => togglePin(item.id));

    const editBtn = document.createElement('button');
    editBtn.type = 'button';
    editBtn.className = 'btn-ghost';
    editBtn.textContent = 'Изменить';
    editBtn.addEventListener('click', () => startEdit(item.id));

    const delBtn = document.createElement('button');
    delBtn.type = 'button';
    delBtn.className = 'btn-ghost btn-danger';
    delBtn.textContent = 'Удалить';
    delBtn.addEventListener('click', () => {
      askConfirm('Удалить заметку?', 'Заметка будет удалена безвозвратно.',
        () => removeNote(item.id));
    });

    buttons.append(pinBtn, editBtn, delBtn);
    meta.append(time, buttons);
    li.append(p, meta);
    els.entriesList.appendChild(li);
  }
}

function formatTime(ts) {
  const d = new Date(ts);
  return pad(d.getHours()) + ':' + pad(d.getMinutes());
}

/* ------------------------- Заметки ------------------------------ */

function startEdit(id) {
  const list = notes[selectedDate] || [];
  const item = list.find((e) => e.id === id);
  if (!item) return;
  editingId = id;
  els.entryText.value = item.text;
  els.editHint.textContent = 'Редактирование';
  els.cancelEdit.hidden = false;
  els.entryText.focus();
  els.entryText.setSelectionRange(item.text.length, item.text.length);
}

function cancelEdit() {
  editingId = null;
  els.entryText.value = '';
  els.editHint.textContent = '';
  els.cancelEdit.hidden = true;
}

function addOrUpdateNote(text) {
  const clean = text.replace(/\r\n/g, '\n').trim().slice(0, MAX_TEXT_LENGTH);
  if (!clean || !selectedDate) return;
  pushUndo();

  if (editingId) {
    const list = notes[selectedDate] || [];
    const item = list.find((e) => e.id === editingId);
    if (item) {
      item.text = clean;
      item.updatedAt = Date.now();
    }
  } else {
    const list = notes[selectedDate] || [];
    if (list.length >= MAX_ENTRIES_PER_DAY) {
      toast('Достигнут лимит заметок на день');
      return;
    }
    list.push({
      id: uid(), text: clean, pinned: false,
      createdAt: Date.now(), updatedAt: null
    });
    notes[selectedDate] = list;
  }

  if (saveNotes()) toast(editingId ? 'Заметка обновлена' : 'Заметка сохранена');
  cancelEdit();
  renderEntries();
  refreshCell(selectedDate);
}

function removeNote(id) {
  if (!selectedDate || !notes[selectedDate]) return;
  pushUndo();
  notes[selectedDate] = notes[selectedDate].filter((e) => e.id !== id);
  if (!notes[selectedDate].length) delete notes[selectedDate];
  if (editingId === id) cancelEdit();
  saveNotes();
  renderEntries();
  refreshCell(selectedDate);
}

function togglePin(id) {
  if (!selectedDate) return;
  const item = (notes[selectedDate] || []).find((e) => e.id === id);
  if (!item) return;
  pushUndo();
  item.pinned = !item.pinned;
  saveNotes();
  renderEntries();
  refreshCell(selectedDate);
  toast(item.pinned ? 'Заметка закреплена' : 'Заметка откреплена');
}

/* ------------------------- Учёба, цель, статусы ----------------- */

function writeMark(key, patch) {
  const current = markFor(key);
  const next = {
    minutes: clampInt(patch.minutes !== undefined ? patch.minutes : current.minutes, 0, MAX_MINUTES_PER_DAY),
    status: patch.status !== undefined ? patch.status : current.status
  };
  if (!next.minutes && !next.status) {
    delete marks[key];
  } else {
    marks[key] = next;
  }
  saveMarks();
  refreshCell(key);
}

function applyStudyInputs() {
  if (!selectedDate) return;
  const h = clampInt(els.hoursInput.value, 0, 23);
  const m = clampInt(els.minutesInput.value, 0, 59);
  els.hoursInput.value = String(h);
  els.minutesInput.value = String(m);
  const newMinutes = h * 60 + m;
  if (newMinutes === markFor(selectedDate).minutes) return;
  pushUndo();
  writeMark(selectedDate, { minutes: newMinutes });
  updateGoalUI();
}

function cycleStatus(key) {
  const current = markFor(key).status;
  const idx = STATUSES.indexOf(current);
  const next = STATUSES[(idx + 1) % STATUSES.length];
  if (next === current) return;
  pushUndo();
  writeMark(key, { status: next });
  if (key === selectedDate) {
    els.statuses.querySelectorAll('.status-dot').forEach((dot) => {
      dot.classList.toggle('is-active', dot.dataset.status === next);
    });
  }
}

function updateGoalUI() {
  if (!selectedDate) return;
  const info = goalInfo(selectedDate);
  els.goalInput.value = String(Math.floor(settings.goalMinutes / 60));
  els.goalMinutesInput.value = String(settings.goalMinutes % 60);

  if (!info.visible) {
    els.progress.classList.remove('is-done');
    els.goalBar.style.width = '0%';
    els.goalLabel.textContent = 'Цель не задана';
    return;
  }

  els.goalBar.style.width = info.pct + '%';
  els.progress.classList.toggle('is-done', info.done);
  els.goalLabel.textContent = info.done
    ? 'ЦЕЛЬ ДОСТИГНУТА · ' + formatMinutes(info.minutes) + ' из ' + formatMinutes(info.goal)
    : formatMinutes(info.minutes) + ' из ' + formatMinutes(info.goal) + ' · ' + info.pct + '%';
}

function applyGoalInput() {
  let h = Number(els.goalInput.value);
  let m = Number(els.goalMinutesInput.value);
  if (!Number.isFinite(h)) h = 0;
  if (!Number.isFinite(m)) m = 0;
  h = Math.min(24, Math.max(0, Math.floor(h)));
  m = Math.min(59, Math.max(0, Math.floor(m)));

  let total = h * 60 + m;
  if (total > MAX_MINUTES_PER_DAY) total = MAX_MINUTES_PER_DAY;

  settings.goalMinutes = total;
  saveSettings();

  els.goalInput.value = String(Math.floor(total / 60));
  els.goalMinutesInput.value = String(total % 60);

  if (selectedDate) updateGoalUI();
  renderCalendar();
}

/* ------------------------- Таймер ------------------------------- */

function timerTotalSec() {
  if (!timer) return 0;
  return timer.baseSec + (timer.running ? Math.max(0, (Date.now() - timer.startedAt) / 1000) : 0);
}

/* Переносит целые минуты таймера в отметку дня */
function commitTimer() {
  if (!timer) return false;
  const totalMin = Math.floor(timerTotalSec() / 60);
  if (totalMin <= timer.committedMin) return false;

  const delta = totalMin - timer.committedMin;
  const current = markFor(timer.dayKey);
  writeMark(timer.dayKey, {
    minutes: clampInt(current.minutes + delta, 0, MAX_MINUTES_PER_DAY)
  });
  timer.committedMin = totalMin;
  saveTimer();

  if (timer.dayKey === selectedDate) syncStudyInputs();
  updateGoalUI();
  return true;
}

function startTimerFor(key) {
  if (timer && timer.running && timer.dayKey !== key) commitTimer();
  timer = {
    dayKey: key,
    running: true,
    startedAt: Date.now(),
    baseSec: 0,
    committedMin: 0
  };
  saveTimer();
  updateTimerUI();
}

function resumeTimer() {
  if (!timer) return;
  timer.running = true;
  timer.startedAt = Date.now();
  saveTimer();
  updateTimerUI();
}

function pauseTimer() {
  if (!timer) return;
  if (timer.running) {
    timer.baseSec = timerTotalSec();
    timer.running = false;
    timer.startedAt = 0;
    commitTimer();
  }
  saveTimer();
  updateTimerUI();
}

function stopTimer() {
  if (!timer) return;
  commitTimer();
  timer = null;
  saveTimer();
  updateTimerUI();
}

function toggleTimer() {
  if (!selectedDate) return;
  if (timer && timer.dayKey === selectedDate) {
    if (timer.running) pauseTimer();
    else resumeTimer();
  } else {
    if (timer && timer.running) commitTimer();
    startTimerFor(selectedDate);
  }
}

/* ±5 секунд к таймеру; целые минуты в итоге дня синхронизируются */
function adjustTimer(deltaSec) {
  if (!timer) {
    toast('Сначала запустите таймер');
    return;
  }
  pushUndo();
  commitTimer();                       // досчитываем целые минуты до правки

  let total = timerTotalSec() + deltaSec;
  if (total < 0) total = 0;
  if (total > MAX_MINUTES_PER_DAY * 60) total = MAX_MINUTES_PER_DAY * 60;

  timer.baseSec = total;
  timer.startedAt = timer.running ? Date.now() : 0;

  const newCommitted = Math.floor(total / 60);
  const diff = newCommitted - timer.committedMin;
  if (diff !== 0) {
    const current = markFor(timer.dayKey);
    writeMark(timer.dayKey, {
      minutes: clampInt(current.minutes + diff, 0, MAX_MINUTES_PER_DAY)
    });
  }
  timer.committedMin = newCommitted;
  saveTimer();

  if (timer.dayKey === selectedDate) syncStudyInputs();
  updateGoalUI();
  updateTimerUI();
  toast(deltaSec > 0 ? '+5 секунд' : '−5 секунд');
}

/* Обнуляет только счётчик таймера, итоговые часы дня сохраняются */
function resetTimerOnly() {
  if (!timer) {
    toast('Таймер не запущен');
    return;
  }
  pushUndo();
  commitTimer();                       // хвост уходит в итог дня

  timer.baseSec = 0;
  timer.committedMin = 0;
  timer.startedAt = timer.running ? Date.now() : 0;
  saveTimer();

  if (timer.dayKey === selectedDate) syncStudyInputs();
  updateGoalUI();
  updateTimerUI();
  toast('Таймер обнулён, время в цели сохранено');
}

function updateTimerUI() {
  els.timerMinus.disabled = !timer;
  els.timerPlus.disabled = !timer;
  els.timerReset.disabled = !timer;

  if (!timer) {
    els.timerBtn.textContent = '▶ Старт';
    els.timerTime.textContent = '00:00';
    els.timerTime.classList.remove('is-running');
    els.timerNote.textContent = '';
    return;
  }

  const forThisDay = timer.dayKey === selectedDate;
  const totalSec = timerTotalSec();

  els.timerTime.textContent = formatClock(totalSec);
  els.timerTime.classList.toggle('is-running', timer.running);

  if (forThisDay) {
    els.timerBtn.textContent = timer.running ? '⏸ Пауза' : '▶ Продолжить';
    els.timerNote.textContent = timer.running ? 'таймер идёт' : 'на паузе';
  } else {
    els.timerBtn.textContent = '▶ Старт';
    const p = parseKey(timer.dayKey);
    els.timerNote.textContent = 'таймер: ' + p.d + ' ' + MONTHS_SHORT[p.m];
  }
}

function tick() {
  if (!timer || !timer.running) return;
  commitTimer();
  updateTimerUI();
}

/* ------------------------- Лента заметок ------------------------ */

function openFeed() {
  els.feedFilter.value = '';
  renderFeed('');
  if (!els.feedDialog.open) els.feedDialog.showModal();
  els.feedFilter.focus();
}

function renderFeed(filter) {
  const q = String(filter || '').trim().toLowerCase();
  els.feedList.replaceChildren();

  const keys = Object.keys(notes)
    .filter((k) => !q || notes[k].some((n) => n.text.toLowerCase().includes(q)))
    .sort((a, b) => (a < b ? 1 : -1));

  if (!keys.length) {
    const div = document.createElement('div');
    div.className = 'search-empty';
    div.textContent = q ? 'Ничего не найдено' : 'Заметок пока нет';
    els.feedList.appendChild(div);
    return;
  }

  for (const key of keys) {
    const p = parseKey(key);
    const head = document.createElement('div');
    head.className = 'feed-day-head';

    const title = document.createElement('span');
    title.textContent = p.d + ' ' + MONTHS_GENITIVE[p.m] + ' ' + p.y;

    const m = markFor(key);
    const marksSpan = document.createElement('span');
    marksSpan.className = 'f-marks';
    marksSpan.textContent = (m.minutes ? formatMinutes(m.minutes) : '—') +
      (m.status ? ' · ' + statusTitle(m.status).toUpperCase() : '');

    head.append(title, marksSpan);
    els.feedList.appendChild(head);

    const list = notes[key].slice().sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      return b.createdAt - a.createdAt;
    });

    for (const item of list) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'feed-item' + (item.pinned ? ' is-pinned' : '');

      const text = document.createElement('span');
      text.className = 'fi-text';
      text.textContent = item.text;

      const meta = document.createElement('span');
      meta.className = 'fi-meta';
      meta.textContent = (item.pinned ? '★ ВАЖНОЕ · ' : '') +
        (p.d + ' ' + MONTHS_SHORT[p.m] + ' · ' + formatTime(item.updatedAt || item.createdAt));

      btn.append(text, meta);
      btn.addEventListener('click', () => {
        viewYear = p.y;
        viewMonth = p.m;
        render();
        els.feedDialog.close();
        selectDay(key);
      });
      els.feedList.appendChild(btn);
    }
  }
}

/* ------------------------- Темы оформления ---------------------- */

/* Применяет тему к <body> и сохраняет выбор (только из белого списка) */
function applyTheme(name) {
  const theme = THEMES.indexOf(name) >= 0 ? name : 'mono';
  document.body.dataset.theme = theme;
  if (settings.theme !== theme) {
    settings.theme = theme;
    saveSettings();
  }
}

function renderThemes() {
  const frag = document.createDocumentFragment();

  for (const id of THEMES) {
    const card = document.createElement('button');
    card.type = 'button';
    card.className = 'theme-card' + (settings.theme === id ? ' is-active' : '');
    card.dataset.themeId = id;
    card.dataset.theme = id;   /* превью показывает палитру своей темы */
    card.setAttribute('aria-pressed', settings.theme === id ? 'true' : 'false');

    const preview = document.createElement('span');
    preview.className = 'tc-preview';
    for (let i = 0; i < 3; i++) preview.appendChild(document.createElement('i'));

    const row = document.createElement('span');
    row.className = 'tc-row';
    ['g', 'y', 'r'].forEach((cls) => {
      const dot = document.createElement('i');
      dot.className = 'tc-dot ' + cls;
      row.appendChild(dot);
    });
    const accent = document.createElement('i');
    accent.className = 'tc-accent';
    row.appendChild(accent);
    preview.appendChild(row);

    const label = document.createElement('span');
    label.className = 'tc-name';
    label.textContent = THEME_NAMES[id] || id;

    card.append(preview, label);
    frag.appendChild(card);
  }

  els.themeGrid.replaceChildren(frag);
}

function openThemes() {
  renderThemes();
  if (!els.themeDialog.open) els.themeDialog.showModal();
}

/* ------------------------- Обзор года -------------------------- */

function openOverview() {
  renderOverview(viewYear);
  if (!els.overviewDialog.open) els.overviewDialog.showModal();
}

function renderOverview(year) {
  els.ovTitle.textContent = 'ОБЗОР ' + year;

  const totals = { minutes: 0, marked: 0, notes: 0, green: 0, yellow: 0, red: 0, best: null };
  const weekdaySum = [0, 0, 0, 0, 0, 0, 0];   // пн … вс
  const weekdayCount = [0, 0, 0, 0, 0, 0, 0];

  const grid = document.createDocumentFragment();

  for (let m = 0; m < 12; m++) {
    const row = document.createElement('div');
    row.className = 'ov-row';

    const label = document.createElement('span');
    label.className = 'ov-month';
    label.textContent = MONTHS_SHORT[m];
    row.appendChild(label);

    const cells = document.createElement('div');
    cells.className = 'ov-cells';

    const daysInMonth = new Date(year, m + 1, 0).getDate();
    for (let d = 1; d <= 31; d++) {
      if (d > daysInMonth) {
        const blank = document.createElement('span');
        blank.className = 'ov-sq is-blank';
        cells.appendChild(blank);
        continue;
      }

      const key = dateKey(year, m, d);
      const mark = markFor(key);
      const noteCount = (notes[key] || []).length;

      const sq = document.createElement('button');
      sq.type = 'button';
      sq.className = 'ov-sq';
      sq.dataset.key = key;

      const dt = new Date(year, m, d);
      const wd = (dt.getDay() + 6) % 7;
      weekdayCount[wd]++;

      if (mark.minutes) {
        totals.minutes += mark.minutes;
        weekdaySum[wd] += mark.minutes;
        const ratio = settings.goalMinutes
          ? mark.minutes / settings.goalMinutes
          : mark.minutes / 60;
        if (ratio >= 1) sq.classList.add('lv3');
        else if (ratio >= 0.5) sq.classList.add('lv2');
        else sq.classList.add('lv1');
        if (!totals.best || mark.minutes > totals.best.minutes) {
          totals.best = { key: key, minutes: mark.minutes };
        }
      }
      if (mark.status) {
        sq.classList.remove('lv1', 'lv2', 'lv3');
        sq.classList.add('st-' + mark.status);
        totals[mark.status]++;
      }
      if (isDayMarked(key)) totals.marked++;
      totals.notes += noteCount;

      sq.title = d + ' ' + MONTHS_GENITIVE[m] + ' ' + year +
        ' · ' + (mark.minutes ? formatMinutes(mark.minutes) : 'нет учёбы') +
        (mark.status ? ' · ' + statusTitle(mark.status) : '') +
        (noteCount ? ' · заметок: ' + noteCount : '');

      sq.addEventListener('click', () => {
        viewYear = year;
        viewMonth = m;
        render();
        els.overviewDialog.close();
        selectDay(key);
      });

      cells.appendChild(sq);
    }

    row.appendChild(cells);
    grid.appendChild(row);
  }

  els.ovGrid.replaceChildren(grid);

  const frag = document.createDocumentFragment();
  const addStat = (label, value) => {
    const span = document.createElement('span');
    const l = document.createElement('span');
    l.textContent = label + ' ';
    const b = document.createElement('b');
    b.textContent = value;
    span.append(l, b);
    frag.appendChild(span);
  };
  addStat('ЧАСОВ', formatMinutes(totals.minutes));
  addStat('ДНЕЙ С ОТМЕТКОЙ', String(totals.marked));
  addStat('ЗАМЕТОК', String(totals.notes));
  if (totals.best) {
    const bp = parseKey(totals.best.key);
    addStat('ЛУЧШИЙ ДЕНЬ', bp.d + ' ' + MONTHS_SHORT[bp.m] + ' · ' + formatMinutes(totals.best.minutes));
  }
  els.ovTotals.replaceChildren(frag);

  // Среднее по дням недели
  const maxAvg = Math.max(1, ...weekdaySum.map((s, i) => (weekdayCount[i] ? s / weekdayCount[i] : 0)));
  const weekFrag = document.createDocumentFragment();
  for (let i = 0; i < 7; i++) {
    const avg = weekdayCount[i] ? weekdaySum[i] / weekdayCount[i] : 0;
    const col = document.createElement('div');
    col.className = 'ov-wd';

    const bar = document.createElement('span');
    bar.className = 'bar';
    const fill = document.createElement('i');
    fill.style.height = Math.max(avg ? 6 : 0, Math.round((avg / maxAvg) * 100)) + '%';
    bar.appendChild(fill);

    const name = document.createElement('span');
    name.textContent = ['пн', 'вт', 'ср', 'чт', 'пт', 'сб', 'вс'][i];

    const val = document.createElement('b');
    val.textContent = avg ? formatMinutes(Math.round(avg)) : '—';

    col.append(bar, name, val);
    weekFrag.appendChild(col);
  }
  els.ovWeek.replaceChildren(weekFrag);
}

/* ------------------------- Переход к дате ----------------------- */

function jumpToDate(value) {
  if (!value) return;
  const parts = value.split('-').map(Number);
  if (parts.length !== 3 || !isValidKey(value)) return;
  viewYear = parts[0];
  viewMonth = parts[1] - 1;
  render();
  selectDay(value);
}

/* ------------------------- Экспорт текстом ---------------------- */

/* В Android-обёртке файл уходит через мост в «Загрузки», иначе — обычное скачивание */
function saveViaAndroid(name, content) {
  try {
    const bridge = window.DiaryAndroid;
    if (bridge && typeof bridge.saveFile === 'function') {
      bridge.saveFile(name, content);
      return true;
    }
  } catch (err) {
    console.warn(err);
  }
  return false;
}

function downloadBlob(content, mime, name) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function fileStamp() {
  const d = new Date();
  return d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate());
}

function exportMarkdown() {
  const keys = Object.keys(notes).sort((a, b) => (a < b ? 1 : -1));
  if (!keys.length) {
    toast('Заметок пока нет');
    return;
  }

  const lines = ['# Дневник', '', 'Экспорт: ' + new Date().toLocaleString('ru-RU'), ''];
  for (const key of keys) {
    const p = parseKey(key);
    const mark = markFor(key);
    const bits = [p.d + ' ' + MONTHS_GENITIVE[p.m] + ' ' + p.y];
    if (mark.minutes) bits.push('учёба ' + formatMinutes(mark.minutes));
    if (mark.status) bits.push('статус: ' + statusTitle(mark.status));

    lines.push('## ' + bits.join(' · '), '');

    const list = notes[key].slice().sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      return a.createdAt - b.createdAt;
    });
    for (const item of list) {
      const body = item.text.split('\n').map((line) => '  ' + line).join('\n');
      lines.push((item.pinned ? '- ★ ' : '- ') + body);
    }
    lines.push('');
  }

  const name = 'diary-' + fileStamp() + '.md';
  if (saveViaAndroid(name, lines.join('\n'))) {
    toast('Файл сохранён в «Загрузки»');
    return;
  }
  downloadBlob(lines.join('\n'), 'text/markdown;charset=utf-8', name);
  toast('Текстовый файл сохранён');
}

/* ------------------------- Режим скрытия ------------------------ */

let isPrivate = false;

function togglePrivacy() {
  isPrivate = !isPrivate;
  document.body.classList.toggle('is-private', isPrivate);
  els.privacyBtn.textContent = isPrivate ? 'Показать' : 'Скрыть';
  els.privacyBtn.classList.toggle('is-on', isPrivate);
  toast(isPrivate ? 'Заметки скрыты' : 'Заметки показаны');
}



/* ------------------------- Выбор месяца/года --------------------- */

let pickerYear = viewYear;

function renderMonthGrid() {
  els.mYearLabel.textContent = String(pickerYear);
  const frag = document.createDocumentFragment();
  MONTHS.forEach((name, idx) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = name;
    if (idx === viewMonth && pickerYear === viewYear) b.classList.add('is-current');
    b.addEventListener('click', () => {
      viewMonth = idx;
      viewYear = pickerYear;
      els.monthDialog.close();
      render();
    });
    frag.appendChild(b);
  });
  els.monthGrid.replaceChildren(frag);
}

let pickerDecade = Math.floor(viewYear / 10) * 10;

function renderYearGrid() {
  els.yDecadeLabel.textContent = pickerDecade + '–' + (pickerDecade + 9);
  const frag = document.createDocumentFragment();
  for (let i = 0; i < 10; i++) {
    const year = pickerDecade + i;
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = String(year);
    if (year === viewYear) b.classList.add('is-current');
    b.addEventListener('click', () => {
      viewYear = year;
      els.yearDialog.close();
      render();
    });
    frag.appendChild(b);
  }
  els.yearGrid.replaceChildren(frag);
}

/* ------------------------- Подтверждение ------------------------ */

function askConfirm(title, text, action) {
  els.confirmTitle.textContent = title;
  els.confirmText.textContent = text;
  confirmAction = action;
  els.confirmDialog.showModal();
}

/* ------------------------- Поиск -------------------------------- */

function runSearch(query) {
  const q = query.trim().toLowerCase();
  els.searchResults.replaceChildren();

  if (!q) {
    els.searchResults.hidden = true;
    els.searchCount.textContent = '';
    return;
  }

  const results = [];
  for (const key of Object.keys(notes)) {
    for (const item of notes[key]) {
      if (item.text.toLowerCase().includes(q)) results.push({ key, item });
    }
  }
  results.sort((a, b) => b.item.createdAt - a.item.createdAt);

  els.searchResults.hidden = false;
  els.searchCount.textContent = results.length ? results.length + ' шт.' : '';

  if (!results.length) {
    const div = document.createElement('div');
    div.className = 'search-empty';
    div.textContent = 'Ничего не найдено';
    els.searchResults.appendChild(div);
    return;
  }

  for (const res of results.slice(0, 100)) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'search-item';

    const p = parseKey(res.key);
    const dateSpan = document.createElement('span');
    dateSpan.className = 'si-date';
    dateSpan.textContent = (res.item.pinned ? '★ ' : '') +
      p.d + ' ' + MONTHS_GENITIVE[p.m] + ' ' + p.y;

    const textSpan = document.createElement('span');
    textSpan.className = 'si-text';
    textSpan.textContent = res.item.text;

    btn.append(dateSpan, textSpan);
    btn.addEventListener('click', () => {
      viewYear = p.y;
      viewMonth = p.m;
      render();
      selectDay(res.key);
    });
    els.searchResults.appendChild(btn);
  }
}

/* ------------------------- Экспорт / импорт ---------------------- */

function exportData() {
  const payload = {
    app: 'diary',
    version: 3,
    exportedAt: new Date().toISOString(),
    notes: notes,
    marks: marks,
    settings: settings
  };
  const name = 'diary-' + fileStamp() + '.json';
  const content = JSON.stringify(payload, null, 2);
  if (saveViaAndroid(name, content)) {
    toast('Резервная копия сохранена в «Загрузки»');
    return;
  }
  downloadBlob(content, 'application/json', name);
  toast('Резервная копия сохранена');
}

function importData(file) {
  const reader = new FileReader();
  reader.onload = () => {
    let parsed;
    try {
      parsed = JSON.parse(String(reader.result));
    } catch (err) {
      toast('Файл не является корректным JSON');
      return;
    }
    if (!parsed || typeof parsed !== 'object') {
      toast('В файле нет данных дневника');
      return;
    }

    const rawNotes = parsed.notes || (parsed.entries && typeof parsed.entries === 'object'
      ? parsed.entries
      : parsed);
    const newNotes = sanitizeNotes(rawNotes);
    const newMarks = sanitizeMarks(parsed.marks || {});
    const newSettings = sanitizeSettings(parsed.settings);
    const noteCount = Object.keys(newNotes).length;

    if (!noteCount && !Object.keys(newMarks).length) {
      toast('В файле нет корректных записей дневника');
      return;
    }

    let entryCount = 0;
    for (const k of Object.keys(newNotes)) entryCount += newNotes[k].length;

    askConfirm(
      'Импортировать данные?',
      'Найдено заметок: ' + entryCount + ' за ' + noteCount +
        ' дн., отмеченных дней: ' + Object.keys(newMarks).length +
        '. Текущие данные будут заменены содержимым файла.',
      () => {
        notes = newNotes;
        marks = newMarks;
        settings = newSettings;
        if (saveNotes() && saveMarks() && saveSettings()) {
          cancelEdit();
          render();
          if (selectedDate) renderPanel();
          toast('Данные импортированы');
        }
      }
    );
  };
  reader.readAsText(file);
}

/* ------------------------- Уведомления --------------------------- */

let toastTimer = null;
function toast(message) {
  els.toast.textContent = message;
  els.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove('show'), 2400);
}

/* ------------------------- События ------------------------------- */

$('prevYear').addEventListener('click', () => { viewYear -= 1; render(); });
$('nextYear').addEventListener('click', () => { viewYear += 1; render(); });

$('prevMonth').addEventListener('click', () => {
  viewMonth -= 1;
  if (viewMonth < 0) { viewMonth = 11; viewYear -= 1; }
  render();
});

$('nextMonth').addEventListener('click', () => {
  viewMonth += 1;
  if (viewMonth > 11) { viewMonth = 0; viewYear += 1; }
  render();
});

$('todayBtn').addEventListener('click', () => {
  const d = new Date();
  viewYear = d.getFullYear();
  viewMonth = d.getMonth();
  render();
  selectDay(todayKey());
});

/* Клик по кружку — смена статуса; по остальному — открытие дня */
els.days.addEventListener('click', (event) => {
  const markBtn = event.target.closest('.mark');
  if (markBtn) {
    event.stopPropagation();
    cycleStatus(markBtn.dataset.key);
    return;
  }
  const cell = event.target.closest('.day');
  if (!cell || cell.classList.contains('is-empty')) return;
  selectDay(cell.dataset.key);
});

els.days.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  const cell = event.target.closest('.day');
  if (!cell || cell.classList.contains('is-empty') || event.target !== cell) return;
  event.preventDefault();
  selectDay(cell.dataset.key);
});

/* Неделя */
els.week.addEventListener('click', (event) => {
  const item = event.target.closest('.week-day');
  if (!item) return;
  const p = parseKey(item.dataset.key);
  viewYear = p.y;
  viewMonth = p.m;
  render();
  selectDay(item.dataset.key);
});

els.week.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  const item = event.target.closest('.week-day');
  if (!item) return;
  event.preventDefault();
  const p = parseKey(item.dataset.key);
  viewYear = p.y;
  viewMonth = p.m;
  render();
  selectDay(item.dataset.key);
});

/* Панель: время учёбы */
els.hoursInput.addEventListener('change', applyStudyInputs);
els.minutesInput.addEventListener('change', applyStudyInputs);
els.hoursInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); applyStudyInputs(); } });
els.minutesInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); applyStudyInputs(); } });

els.resetStudy.addEventListener('click', () => {
  if (!selectedDate) return;
  if (!markFor(selectedDate).minutes) {
    toast('Время и так не отмечено');
    return;
  }
  pushUndo();
  if (timer && timer.dayKey === selectedDate) stopTimer();
  els.hoursInput.value = '0';
  els.minutesInput.value = '0';
  writeMark(selectedDate, { minutes: 0 });
  updateGoalUI();
  toast('Время сброшено');
});

/* Панель: таймер и цель */
els.timerBtn.addEventListener('click', toggleTimer);
els.timerMinus.addEventListener('click', () => adjustTimer(-5));
els.timerPlus.addEventListener('click', () => adjustTimer(5));
els.timerReset.addEventListener('click', resetTimerOnly);
els.goalInput.addEventListener('change', applyGoalInput);
els.goalMinutesInput.addEventListener('change', applyGoalInput);
els.goalInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); applyGoalInput(); } });
els.goalMinutesInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); applyGoalInput(); } });

/* Панель: статусы */
els.statuses.addEventListener('click', (event) => {
  const dot = event.target.closest('.status-dot');
  if (!dot || !selectedDate) return;
  if (dot.dataset.status === markFor(selectedDate).status) return;
  pushUndo();
  writeMark(selectedDate, { status: dot.dataset.status });
  els.statuses.querySelectorAll('.status-dot').forEach((d) => {
    d.classList.toggle('is-active', d === dot);
  });
});

/* Панель: заметки */
els.entryForm.addEventListener('submit', (event) => {
  event.preventDefault();
  const text = els.entryText.value;
  if (!text.trim()) return;
  addOrUpdateNote(text);
});

els.cancelEdit.addEventListener('click', cancelEdit);

/* Выборщики */
els.monthLabel.addEventListener('click', () => {
  pickerYear = viewYear;
  renderMonthGrid();
  els.monthDialog.showModal();
});

els.yearLabel.addEventListener('click', () => {
  pickerDecade = Math.floor(viewYear / 10) * 10;
  renderYearGrid();
  els.yearDialog.showModal();
});

$('mPrevYear').addEventListener('click', () => { pickerYear -= 1; renderMonthGrid(); });
$('mNextYear').addEventListener('click', () => { pickerYear += 1; renderMonthGrid(); });
$('yPrevDecade').addEventListener('click', () => { pickerDecade -= 10; renderYearGrid(); });
$('yNextDecade').addEventListener('click', () => { pickerDecade += 10; renderYearGrid(); });

document.querySelectorAll('[data-close]').forEach((btn) => {
  btn.addEventListener('click', () => btn.closest('dialog').close());
});

/* Лента */
els.feedBtn.addEventListener('click', openFeed);
els.feedFilter.addEventListener('input', () => renderFeed(els.feedFilter.value));

/* Новые кнопки */
els.themeBtn.addEventListener('click', openThemes);
els.themeGrid.addEventListener('click', (event) => {
  const card = event.target.closest('.theme-card');
  if (!card) return;
  const id = card.dataset.themeId;
  if (THEMES.indexOf(id) < 0) return;
  applyTheme(id);
  renderThemes();
  els.themeDialog.close();
  toast('Тема: ' + (THEME_NAMES[id] || id));
});
els.jumpDate.addEventListener('change', () => jumpToDate(els.jumpDate.value));
els.yearBtn.addEventListener('click', openOverview);
els.helpBtn.addEventListener('click', () => els.helpDialog.showModal());
els.undoBtn.addEventListener('click', undoAction);
els.privacyBtn.addEventListener('click', togglePrivacy);
els.mdBtn.addEventListener('click', exportMarkdown);

/* Подтверждение */
els.confirmOk.addEventListener('click', () => {
  const action = confirmAction;
  confirmAction = null;
  els.confirmDialog.close();
  if (typeof action === 'function') action();
});
els.confirmDialog.addEventListener('close', () => { confirmAction = null; });

/* Поиск */
els.searchBtn.addEventListener('click', () => {
  const willOpen = els.searchBar.hidden;
  els.searchBar.hidden = !willOpen;
  els.searchBtn.setAttribute('aria-expanded', String(willOpen));
  if (willOpen) {
    els.searchInput.focus();
  } else {
    els.searchInput.value = '';
    runSearch('');
  }
});
els.searchInput.addEventListener('input', () => runSearch(els.searchInput.value));

/* Данные */
$('exportBtn').addEventListener('click', exportData);
$('importBtn').addEventListener('click', () => $('importFile').click());
$('importFile').addEventListener('change', (event) => {
  const file = event.target.files && event.target.files[0];
  if (file) importData(file);
  event.target.value = '';
});

$('clearBtn').addEventListener('click', () => {
  askConfirm(
    'Очистить дневник?',
    'Все заметки, время учёбы и статусы будут удалены безвозвратно. Сначала сделайте экспорт.',
    () => {
      notes = {};
      marks = {};
      timer = null;
      safeRemove(NOTES_KEY);
      safeRemove(MARKS_KEY);
      safeRemove(TIMER_KEY);
      cancelEdit();
      selectedDate = null;
      render();
      renderPanel();
      updateTimerUI();
      toast('Дневник очищен');
    }
  );
});

/* Клавиатура: месяцы, сегодня, лента, обзор года, заметка, статусы, отмена */
document.addEventListener('keydown', (event) => {
  const dialogOpen = els.monthDialog.open || els.yearDialog.open ||
    els.confirmDialog.open || els.feedDialog.open ||
    els.overviewDialog.open || els.helpDialog.open || els.themeDialog.open;

  /* Отмена действия работает и при открытых полях ввода */
  if ((event.ctrlKey || event.metaKey) && (event.key === 'z' || event.key === 'Z' || event.key === 'я' || event.key === 'Я')) {
    if (!dialogOpen) {
      event.preventDefault();
      undoAction();
    }
    return;
  }

  if (dialogOpen) return;
  const tag = document.activeElement && document.activeElement.tagName;
  if (tag === 'INPUT' || tag === 'TEXTAREA') return;

  if (event.key === 'ArrowLeft') {
    $('prevMonth').click();
  } else if (event.key === 'ArrowRight') {
    $('nextMonth').click();
  } else if (event.key === 't' || event.key === 'е') {
    $('todayBtn').click();
  } else if (event.key === 'f' || event.key === 'а') {
    openFeed();
  } else if (event.key === 'y' || event.key === 'н') {
    openOverview();
  } else if (event.key === 'g' || event.key === 'п') {
    openThemes();
  } else if (event.key === 'h' || event.key === 'р') {
    els.helpDialog.showModal();
  } else if (event.key === 'n' || event.key === 'т') {
    if (!selectedDate) selectDay(todayKey());
    if (selectedDate && els.panelContent && !els.panelContent.hidden) {
      els.entryText.focus();
      els.entryText.scrollIntoView({ block: 'center' });
    }
  } else if (event.key >= '1' && event.key <= '4') {
    if (!selectedDate) return;
    const next = STATUSES[Number(event.key) - 1];
    if (next === markFor(selectedDate).status) return;
    pushUndo();
    writeMark(selectedDate, { status: next });
    els.statuses.querySelectorAll('.status-dot').forEach((dot) => {
      dot.classList.toggle('is-active', dot.dataset.status === next);
    });
  }
});

/* Закрытие диалога по клику именно на подложку */
[els.monthDialog, els.yearDialog, els.confirmDialog, els.feedDialog,
 els.overviewDialog, els.helpDialog, els.themeDialog].forEach((dlg) => {
  dlg.addEventListener('click', (event) => {
    if (event.target !== dlg) return;
    const r = dlg.getBoundingClientRect();
    const outside =
      event.clientX < r.left || event.clientX > r.right ||
      event.clientY < r.top || event.clientY > r.bottom;
    if (outside) dlg.close();
  });
});

/* Тик таймера: минуты уходят в отметку дня */
setInterval(tick, 1000);

/* Сохраняем хвост таймера при уходе со страницы */
window.addEventListener('beforeunload', () => {
  if (timer && timer.running) {
    timer.baseSec = timerTotalSec();
    const totalMin = Math.floor(timer.baseSec / 60);
    if (totalMin > timer.committedMin) {
      const delta = totalMin - timer.committedMin;
      const current = markFor(timer.dayKey);
      marks[timer.dayKey] = {
        minutes: clampInt(current.minutes + delta, 0, MAX_MINUTES_PER_DAY),
        status: current.status
      };
      if (!marks[timer.dayKey].minutes && !marks[timer.dayKey].status) delete marks[timer.dayKey];
      safeWrite(MARKS_KEY, JSON.stringify(marks));
      timer.committedMin = totalMin;
    }
    timer.startedAt = Date.now();
    timer.baseSec = 0;
    safeWrite(TIMER_KEY, JSON.stringify(timer));
  }
});

/* ------------------------- Запуск -------------------------------- */

applyTheme(settings.theme);
render();
renderPanel();
updateTimerUI();
updateUndoBtn();
