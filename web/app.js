"use strict";

const MAX_STOPWATCHES = 7;
const MAX_LAPS = 50;
const MAX_NOTE_LENGTH = 1000;
const LOG_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;
const TRASH_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const DB_NAME = "multi-stopwatch-pwa";
const DB_VERSION = 1;
const STORE_NAME = "app-data";
const LOCAL_STORAGE_KEY = "multi-stopwatch-pwa-backup";
const COLOR_PALETTE = ["#2F7DFF", "#1F9D63", "#E97824", "#7B61FF", "#0096AA", "#DC4070", "#B88900"];

const $ = (selector) => document.querySelector(selector);
const listElement = $("#stopwatch-list");
const toastElement = $("#toast");
let state;
let wallpaperRecord = null;
let wallpaperObjectUrl = null;
let wallpaperDisplayedBlob = null;
let storageMode = "indexeddb";
let persistenceUnavailable = false;
let saveQueue = Promise.resolve();
let toastTimeout;
let editingTimerId = null;
let editingNoteTimerId = null;
let reloadAfterUpdate = false;

const initialSettings = () => ({
  themeColor: "#2F7DFF",
  darkMode: false,
  compactList: false,
  fontScale: 1
});

function makeId() {
  if (crypto.randomUUID) return crypto.randomUUID();
  return `timer-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function makeTimer(name, color) {
  return {
    id: makeId(),
    name,
    note: "",
    color,
    borderColor: color,
    backgroundColor: "#FFFFFF",
    transparency: 0,
    elapsedMs: 0,
    running: false,
    startedAt: 0,
    laps: []
  };
}

function normalizeTimer(input, index) {
  const timer = input && typeof input === "object" ? input : {};
  const color = validColor(timer.color) ? timer.color : COLOR_PALETTE[index % COLOR_PALETTE.length];
  const startedAt = Math.max(0, Number(timer.startedAt) || 0);
  return {
    id: typeof timer.id === "string" && timer.id ? timer.id : makeId(),
    name: typeof timer.name === "string" && timer.name.trim() ? timer.name.trim().slice(0, 40) : `秒表 ${index + 1}`,
    note: typeof timer.note === "string" ? timer.note.slice(0, MAX_NOTE_LENGTH) : "",
    color,
    borderColor: validColor(timer.borderColor) ? timer.borderColor : color,
    backgroundColor: validColor(timer.backgroundColor) ? timer.backgroundColor : "#FFFFFF",
    transparency: clampNumber(timer.transparency, 0, 100, 0),
    elapsedMs: Math.max(0, Number(timer.elapsedMs) || 0),
    running: Boolean(timer.running) && startedAt > 0,
    startedAt,
    laps: Array.isArray(timer.laps) ? timer.laps.slice(-MAX_LAPS).map((lap, lapIndex) => ({
      number: Math.max(1, Math.floor(Number(lap.number) || lapIndex + 1)),
      totalMs: Math.max(0, Number(lap.totalMs) || 0),
      splitMs: Math.max(0, Number(lap.splitMs) || 0)
    })) : []
  };
}

function normalizeState(input) {
  const data = input && typeof input === "object" ? input : {};
  const settings = data.settings && typeof data.settings === "object" ? data.settings : {};
  const timers = Array.isArray(data.timers) ? data.timers.slice(0, MAX_STOPWATCHES).map(normalizeTimer) : [];
  const trash = Array.isArray(data.trash) ? data.trash.map((entry) => ({
    deletedAt: Math.max(0, Number(entry && entry.deletedAt) || Date.now()),
    timer: normalizeTimer(entry && entry.timer, 0)
  })).filter((entry) => Date.now() - entry.deletedAt <= TRASH_RETENTION_MS) : [];
  const logs = Array.isArray(data.logs) ? data.logs.filter((entry) => entry && typeof entry === "object")
    .filter((entry) => Number(entry.time) >= Date.now() - LOG_RETENTION_MS)
    .slice(-10000).map((entry) => ({
      time: Math.max(0, Number(entry.time) || Date.now()),
      timer: String(entry.timer || "").slice(0, 80),
      action: String(entry.action || "").slice(0, 80),
      details: String(entry.details || "").slice(0, 500)
    })) : [];
  return {
    timers,
    trash,
    logs,
    settings: {
      themeColor: validColor(settings.themeColor) ? settings.themeColor : "#2F7DFF",
      darkMode: Boolean(settings.darkMode),
      compactList: Boolean(settings.compactList),
      fontScale: clampNumber(settings.fontScale, 0.8, 1.4, 1)
    }
  };
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(max, Math.max(min, number)) : fallback;
}

function validColor(value) {
  return typeof value === "string" && /^#[0-9a-f]{6}$/i.test(value);
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    if (!("indexedDB" in window)) {
      reject(new Error("IndexedDB unavailable"));
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Could not open local database"));
    request.onblocked = () => reject(new Error("Local database upgrade is blocked"));
  });
}

function readDatabaseRecord(db) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE_NAME, "readonly");
    const request = transaction.objectStore(STORE_NAME).get("current");
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error || new Error("Could not read local database"));
    transaction.onabort = () => reject(transaction.error || new Error("Local database read aborted"));
  });
}

function writeDatabaseRecord(db, record) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE_NAME, "readwrite");
    transaction.objectStore(STORE_NAME).put(record, "current");
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error || new Error("Could not save local database"));
    transaction.onabort = () => reject(transaction.error || new Error("Local database write aborted"));
  });
}

function snapshot() {
  return {
    timers: state.timers.map((timer) => ({ ...timer, laps: timer.laps.map((lap) => ({ ...lap })) })),
    trash: state.trash.map((entry) => ({
      deletedAt: entry.deletedAt,
      timer: { ...entry.timer, laps: entry.timer.laps.map((lap) => ({ ...lap })) }
    })),
    logs: state.logs.map((entry) => ({ ...entry })),
    settings: { ...state.settings }
  };
}

async function loadSavedData() {
  try {
    const db = await openDatabase();
    const record = await readDatabaseRecord(db);
    if (record && record.state) {
      state = normalizeState(record.state);
      wallpaperRecord = record.wallpaper && record.wallpaper.blob instanceof Blob ? record.wallpaper : null;
      db.close();
      return;
    }
    db.close();
  } catch {
    storageMode = "localstorage";
  }

  try {
    const raw = localStorage.getItem(LOCAL_STORAGE_KEY);
    if (raw) {
      const record = JSON.parse(raw);
      state = normalizeState(record.state || record);
      wallpaperRecord = null;
      return;
    }
  } catch {
    persistenceUnavailable = storageMode !== "indexeddb";
  }

  state = normalizeState({
    timers: [makeTimer("秒表 1", COLOR_PALETTE[0])],
    trash: [],
    logs: [],
    settings: initialSettings()
  });
}

function persist() {
  if (!state) return Promise.resolve();
  const stateCopy = snapshot();
  const wallpaperCopy = wallpaperRecord ? { ...wallpaperRecord } : null;
  saveQueue = saveQueue.catch(() => undefined).then(async () => {
    if (storageMode === "indexeddb") {
      try {
        const db = await openDatabase();
        await writeDatabaseRecord(db, { state: stateCopy, wallpaper: wallpaperCopy });
        db.close();
        persistenceUnavailable = false;
        return;
      } catch {
        storageMode = "localstorage";
      }
    }
    try {
      localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify({ state: stateCopy }));
      persistenceUnavailable = false;
      if (wallpaperRecord) toast("本机数据库不可用，壁纸无法保存；秒表数据已保存");
    } catch {
      persistenceUnavailable = true;
      toast("本机存储不可用，请立即导出 JSON 备份");
    }
  });
  return saveQueue;
}

function logAction(timer, action, details = "") {
  const now = Date.now();
  state.logs.push({ time: now, timer: timer || "应用", action, details });
  state.logs = state.logs.filter((entry) => entry.time >= now - LOG_RETENTION_MS).slice(-10000);
}

function elapsedMs(timer, now = Date.now()) {
  if (!timer.running) return timer.elapsedMs;
  return timer.elapsedMs + Math.max(0, now - timer.startedAt);
}

function formatDuration(milliseconds) {
  const hundredths = Math.floor(milliseconds / 10) % 100;
  const seconds = Math.floor(milliseconds / 1000);
  const sec = seconds % 60;
  const minutes = Math.floor(seconds / 60);
  const min = minutes % 60;
  const hours = Math.floor(minutes / 60);
  return `${String(hours).padStart(2, "0")}:${String(min).padStart(2, "0")}:${String(sec).padStart(2, "0")}.${String(hundredths).padStart(2, "0")}`;
}

function colorWithTransparency(hex, transparency) {
  const amount = Math.max(0, Math.min(100, Number(transparency) || 0));
  const alpha = (100 - amount) / 100;
  const red = parseInt(hex.slice(1, 3), 16);
  const green = parseInt(hex.slice(3, 5), 16);
  const blue = parseInt(hex.slice(5, 7), 16);
  return `rgba(${red}, ${green}, ${blue}, ${alpha})`;
}

function addButton(label, action, className = "button secondary") {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className;
  button.dataset.action = action;
  button.textContent = label;
  return button;
}

function render() {
  if (!state) return;
  document.documentElement.dataset.theme = state.settings.darkMode ? "dark" : "light";
  document.documentElement.dataset.compact = String(state.settings.compactList);
  document.documentElement.style.setProperty("--accent", state.settings.themeColor);
  document.documentElement.style.setProperty("--font-scale", `${Math.round(state.settings.fontScale * 100)}%`);
  $("meta[name='theme-color']").content = state.settings.themeColor;
  $("#summary").textContent = `${state.timers.length} / ${MAX_STOPWATCHES} 个秒表 · ${state.timers.filter((timer) => timer.running).length} 个正在运行`;
  $("#add-stopwatch").disabled = state.timers.length >= MAX_STOPWATCHES;
  $("#empty-state").hidden = state.timers.length > 0;
  $("#remove-wallpaper").hidden = !wallpaperRecord;
  renderWallpaper();

  const fragment = document.createDocumentFragment();
  for (const timer of state.timers) {
    const card = document.createElement("article");
    card.className = "timer-card";
    card.dataset.timerId = timer.id;
    card.style.setProperty("--timer-background", colorWithTransparency(timer.backgroundColor, timer.transparency));
    card.style.setProperty("--timer-border", timer.borderColor);
    card.style.setProperty("--timer-accent", timer.color);

    const heading = document.createElement("div");
    heading.className = "timer-heading";
    const name = document.createElement("h2");
    name.className = "timer-name";
    name.textContent = timer.name;
    heading.append(name, addButton(timer.note ? "笔记 ·" : "笔记", "note", "button secondary timer-note-button"));

    const clock = document.createElement("p");
    clock.className = "timer-time";
    clock.dataset.clock = "true";
    clock.textContent = formatDuration(elapsedMs(timer));
    const status = document.createElement("p");
    status.className = "timer-status";
    status.textContent = timer.running ? "运行中" : "已暂停";

    const primaryControls = document.createElement("div");
    primaryControls.className = "timer-controls";
    const toggle = addButton(timer.running ? "暂停" : (elapsedMs(timer) > 0 ? "继续" : "开始"), "toggle", "button primary");
    const lap = addButton("计次", "lap", "button secondary");
    lap.disabled = elapsedMs(timer) <= 0 || timer.laps.length >= MAX_LAPS;
    primaryControls.append(toggle, lap, addButton("重置", "reset", "button secondary"));

    const secondaryControls = document.createElement("div");
    secondaryControls.className = "timer-controls secondary-controls";
    secondaryControls.append(addButton("改名", "rename"), addButton("删除", "delete", "button danger"));

    const laps = document.createElement("details");
    laps.className = "timer-laps";
    laps.hidden = timer.laps.length === 0;
    if (timer.laps.length) {
      const summary = document.createElement("summary");
      summary.className = "timer-laps-button";
      summary.textContent = `圈速记录（${timer.laps.length}）`;
      const list = document.createElement("ol");
      for (const lap of [...timer.laps].reverse()) {
        const entry = document.createElement("li");
        entry.textContent = `第 ${lap.number} 次 · 总计 ${formatDuration(lap.totalMs)} · 本圈 ${formatDuration(lap.splitMs)}`;
        list.append(entry);
      }
      laps.append(summary, list);
    }
    card.append(heading, clock, status, primaryControls, secondaryControls, laps);
    for (const button of card.querySelectorAll("button[data-action]")) {
      button.style.backgroundColor = colorWithTransparency(
        button.dataset.action === "toggle"
          ? (timer.running ? "#D84646" : timer.color)
          : button.dataset.action === "delete"
            ? "#993A41"
            : state.settings.darkMode ? "#353D4C" : "#E1E8F4",
        timer.transparency
      );
      if (button.dataset.action === "toggle" || button.dataset.action === "delete") button.style.color = "#FFFFFF";
    }
    fragment.append(card);
  }
  listElement.replaceChildren(fragment);

  if ($("#appearance-dialog").open) syncAppearanceControls();
  if ($("#card-style-dialog").open) syncCardStyleControls();
  renderTrash();
}

function renderTimes() {
  const now = Date.now();
  for (const card of listElement.querySelectorAll(".timer-card")) {
    const timer = state.timers.find((item) => item.id === card.dataset.timerId);
    const clock = card.querySelector("[data-clock]");
    if (timer && clock) {
      const elapsed = elapsedMs(timer, now);
      clock.textContent = formatDuration(elapsed);
      const lapButton = card.querySelector('[data-action="lap"]');
      if (lapButton) lapButton.disabled = elapsed <= 0 || timer.laps.length >= MAX_LAPS;
    }
  }
}

function applyWallpaper() {
  const layer = $("#wallpaper-layer");
  const currentBlob = wallpaperRecord && wallpaperRecord.blob instanceof Blob ? wallpaperRecord.blob : null;
  if (currentBlob === wallpaperDisplayedBlob) return;
  if (wallpaperObjectUrl) URL.revokeObjectURL(wallpaperObjectUrl);
  wallpaperDisplayedBlob = currentBlob;
  wallpaperObjectUrl = currentBlob ? URL.createObjectURL(currentBlob) : null;
  if (wallpaperObjectUrl) {
    document.documentElement.classList.add("has-wallpaper");
    layer.style.backgroundImage = `url("${wallpaperObjectUrl}")`;
  } else {
    document.documentElement.classList.remove("has-wallpaper");
    layer.style.backgroundImage = "none";
  }
}

function renderWallpaper() {
  applyWallpaper();
}

function toast(message) {
  toastElement.textContent = message;
  toastElement.classList.add("visible");
  clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => toastElement.classList.remove("visible"), 2800);
}

function saveAndRender(message) {
  render();
  persist().then(() => {
    if (message) toast(message);
  });
}

function openDialog(id) {
  const dialog = document.getElementById(id);
  if (dialog && !dialog.open) dialog.showModal();
}

function closeDialog(id) {
  const dialog = document.getElementById(id);
  if (dialog && dialog.open) dialog.close();
}

function getTimer(id) {
  return state.timers.find((timer) => timer.id === id);
}

function firstColor() {
  return COLOR_PALETTE.find((color) => !state.timers.some((timer) => timer.color.toLowerCase() === color.toLowerCase()))
    || COLOR_PALETTE[state.timers.length % COLOR_PALETTE.length];
}

function addStopwatch() {
  if (state.timers.length >= MAX_STOPWATCHES) {
    toast("最多只能创建 7 个秒表");
    return;
  }
  editingTimerId = null;
  $("#name-dialog-title").textContent = "新建秒表";
  $("#timer-name").value = `秒表 ${state.timers.length + 1}`;
  openDialog("name-dialog");
  setTimeout(() => $("#timer-name").focus(), 50);
}

function toggleTimer(timer) {
  const now = Date.now();
  if (timer.running) {
    timer.elapsedMs = elapsedMs(timer, now);
    timer.running = false;
    timer.startedAt = 0;
    logAction(timer.name, "暂停", `计时 ${formatDuration(timer.elapsedMs)}`);
  } else {
    timer.running = true;
    timer.startedAt = now;
    logAction(timer.name, timer.elapsedMs > 0 ? "继续" : "开始", `计时 ${formatDuration(timer.elapsedMs)}`);
  }
  saveAndRender();
}

function addLap(timer) {
  if (timer.laps.length >= MAX_LAPS) {
    toast("每个秒表最多记录 50 圈");
    return;
  }
  const totalMs = elapsedMs(timer);
  if (totalMs <= 0) return;
  const previous = timer.laps.length ? timer.laps[timer.laps.length - 1].totalMs : 0;
  const lap = { number: timer.laps.length + 1, totalMs, splitMs: Math.max(0, totalMs - previous) };
  timer.laps.push(lap);
  logAction(timer.name, "计次", `第 ${lap.number} 次；本圈 ${formatDuration(lap.splitMs)}`);
  saveAndRender();
}

function resetTimer(timer) {
  if (!window.confirm(`重置“${timer.name}”的计时并清除全部圈速记录？`)) return;
  const previousElapsed = elapsedMs(timer);
  const previousLaps = timer.laps.length;
  timer.elapsedMs = 0;
  timer.startedAt = timer.running ? Date.now() : 0;
  timer.laps = [];
  logAction(timer.name, "重置", `之前计时 ${formatDuration(previousElapsed)}，清除 ${previousLaps} 条计次记录`);
  saveAndRender("已重置");
}

function moveTimerToTrash(timer) {
  if (!window.confirm(`将“${timer.name}”移入回收站？可在 7 天内恢复。`)) return;
  if (timer.running) {
    timer.elapsedMs = elapsedMs(timer);
    timer.running = false;
    timer.startedAt = 0;
  }
  state.timers = state.timers.filter((item) => item.id !== timer.id);
  state.trash.unshift({ deletedAt: Date.now(), timer });
  logAction(timer.name, "移入回收站", "保留 7 天");
  saveAndRender("已移入回收站");
}

function renameTimer(timer) {
  editingTimerId = timer.id;
  $("#name-dialog-title").textContent = "修改名称";
  $("#timer-name").value = timer.name;
  openDialog("name-dialog");
  setTimeout(() => $("#timer-name").select(), 50);
}

function editNote(timer) {
  editingNoteTimerId = timer.id;
  $("#note-dialog-title").textContent = `${timer.name} · 笔记`;
  $("#timer-note").value = timer.note;
  openDialog("note-dialog");
}

function renderTrash() {
  const container = $("#trash-list");
  container.replaceChildren();
  if (!state || !state.trash.length) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "回收站是空的";
    container.append(empty);
    $("#clear-trash").disabled = true;
    return;
  }
  $("#clear-trash").disabled = false;
  for (const entry of state.trash) {
    const row = document.createElement("article");
    row.className = "trash-item";
    const name = document.createElement("span");
    name.className = "trash-name";
    name.textContent = entry.timer.name;
    const restore = addButton("恢复", "restore", "button secondary");
    const remove = addButton("永久删除", "purge", "button danger");
    restore.dataset.timerId = entry.timer.id;
    remove.dataset.timerId = entry.timer.id;
    const meta = document.createElement("span");
    meta.className = "trash-meta";
    const remaining = Math.max(0, TRASH_RETENTION_MS - (Date.now() - entry.deletedAt));
    meta.textContent = `剩余 ${Math.ceil(remaining / (24 * 60 * 60 * 1000))} 天`;
    row.append(name, restore, remove, meta);
    container.append(row);
  }
}

function pruneTrash() {
  const cutoff = Date.now() - TRASH_RETENTION_MS;
  const before = state.trash.length;
  state.trash = state.trash.filter((entry) => entry.deletedAt >= cutoff);
  return state.trash.length !== before;
}

function restoreTrashTimer(timerId) {
  if (state.timers.length >= MAX_STOPWATCHES) {
    toast("最多只能创建 7 个秒表，请先删除一个秒表");
    return;
  }
  const index = state.trash.findIndex((entry) => entry.timer.id === timerId);
  if (index < 0) return;
  const [entry] = state.trash.splice(index, 1);
  state.timers.unshift(entry.timer);
  logAction(entry.timer.name, "从回收站恢复", "");
  saveAndRender();
}

function fillTimerSelect(select, selectedId) {
  select.replaceChildren();
  for (const timer of state.timers) {
    const option = document.createElement("option");
    option.value = timer.id;
    option.textContent = timer.name;
    select.append(option);
  }
  if (selectedId && getTimer(selectedId)) select.value = selectedId;
}

function openCardStyleDialog() {
  if (!state.timers.length) {
    toast("请先创建秒表");
    return;
  }
  fillTimerSelect($("#style-timer"), state.timers[0].id);
  syncCardStyleControls();
  closeDialog("interface-dialog");
  openDialog("card-style-dialog");
}

function syncCardStyleControls() {
  const timer = getTimer($("#style-timer").value) || state.timers[0];
  if (!timer) return;
  $("#card-background").value = timer.backgroundColor;
  $("#card-border").value = timer.borderColor;
  $("#card-background-value").value = timer.backgroundColor.toUpperCase();
  $("#card-border-value").value = timer.borderColor.toUpperCase();
  $("#card-transparency").value = timer.transparency;
  $("#transparency-value").value = `${timer.transparency}%`;
  updateStylePreview();
}

function updateStylePreview() {
  const preview = $("#card-preview");
  const background = $("#card-background").value;
  const border = $("#card-border").value;
  preview.style.setProperty("--preview-background", colorWithTransparency(background, $("#card-transparency").value));
  preview.style.setProperty("--preview-border", border);
  $("#card-background-value").value = background.toUpperCase();
  $("#card-border-value").value = border.toUpperCase();
  $("#transparency-value").value = `${$("#card-transparency").value}%`;
}

function syncAppearanceControls() {
  $("#theme-color").value = state.settings.themeColor;
  $("#theme-color-value").value = state.settings.themeColor.toUpperCase();
  $("#dark-mode").checked = state.settings.darkMode;
  $("#compact-list").checked = state.settings.compactList;
  $("#font-scale").value = Math.round(state.settings.fontScale * 100);
  $("#font-scale-value").value = `${Math.round(state.settings.fontScale * 100)}%`;
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.style.position = "fixed";
  link.style.left = "-10000px";
  link.style.top = "0";
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

function csvField(value) {
  return `"${String(value ?? "").replace(/"/g, '""')}"`;
}

function exportLogsCsv() {
  logAction("应用", "导出日志", "最近 30 天");
  const cutoff = Date.now() - LOG_RETENTION_MS;
  const rows = [["时间", "秒表", "操作", "详情"]];
  for (const entry of state.logs.filter((log) => log.time >= cutoff)) {
    rows.push([new Date(entry.time).toLocaleString("zh-CN"), entry.timer, entry.action, entry.details]);
  }
  const csv = "\uFEFF" + rows.map((row) => row.map(csvField).join(",")).join("\r\n");
  downloadBlob(new Blob([csv], { type: "text/csv;charset=utf-8" }), `秒表运行日志_${fileDate()}.csv`);
  persist();
  closeDialog("data-dialog");
}

function exportTimersCsv() {
  logAction("应用", "导出秒表", `${state.timers.length} 个秒表 CSV`);
  const rows = [["秒表", "笔记", "计时", "圈次", "累计用时", "本圈用时"]];
  for (const timer of state.timers) {
    const elapsed = elapsedMs(timer);
    if (!timer.laps.length) {
      rows.push([timer.name, timer.note, formatDuration(elapsed), "", "", ""]);
      continue;
    }
    for (const lap of timer.laps) {
      rows.push([timer.name, timer.note, formatDuration(elapsed), lap.number, formatDuration(lap.totalMs), formatDuration(lap.splitMs)]);
    }
  }
  const csv = "\uFEFF" + rows.map((row) => row.map(csvField).join(",")).join("\r\n");
  downloadBlob(new Blob([csv], { type: "text/csv;charset=utf-8" }), `秒表圈速_${fileDate()}.csv`);
  persist();
  closeDialog("data-dialog");
}

function fileDate() {
  const date = new Date();
  return `${date.getFullYear()}${String(date.getMonth() + 1).padStart(2, "0")}${String(date.getDate()).padStart(2, "0")}`;
}

function blobToDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error || new Error("Could not read image"));
    reader.readAsDataURL(blob);
  });
}

function dataUrlToBlob(dataUrl) {
  if (typeof dataUrl !== "string" || !/^data:image\/[a-zA-Z0-9.+-]+;base64,/.test(dataUrl)) {
    throw new Error("备份中的壁纸格式无效");
  }
  const [header, encoded] = dataUrl.split(",", 2);
  const mime = header.slice(5, header.indexOf(";"));
  const binary = atob(encoded);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
  return new Blob([bytes], { type: mime });
}

async function exportBackup() {
  logAction("应用", "导出 JSON 备份", "");
  const backup = {
    format: "multi-stopwatch-pwa-backup",
    version: 1,
    exportedAt: new Date().toISOString(),
    state: snapshot()
  };
  if (wallpaperRecord && wallpaperRecord.blob instanceof Blob) {
    try {
      backup.wallpaper = {
        name: wallpaperRecord.name || "wallpaper",
        dataUrl: await blobToDataUrl(wallpaperRecord.blob)
      };
    } catch {
      toast("壁纸备份失败；秒表数据仍会导出");
    }
  }
  const json = JSON.stringify(backup, null, 2);
  downloadBlob(new Blob([json], { type: "application/json;charset=utf-8" }), `多秒表备份_${fileDate()}.json`);
  persist();
  closeDialog("data-dialog");
}

function showIosInstallGuide() {
  const ua = navigator.userAgent || "";
  const ios = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const safari = /Safari/.test(ua) && !/CriOS|FxiOS|EdgiOS|OPiOS/.test(ua);
  const standalone = navigator.standalone === true || window.matchMedia("(display-mode: standalone)").matches;
  if (!ios || !safari || standalone) return;
  try {
    if (localStorage.getItem("multi-stopwatch-ios-guide-dismissed") === "1") return;
  } catch {
    // Keep the guide available when storage is disabled.
  }
  $("#ios-install-guide").hidden = false;
}

function registerServiceWorker() {
  if (!("serviceWorker" in navigator) || !window.isSecureContext) return;
  const hadController = Boolean(navigator.serviceWorker.controller);
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (reloadAfterUpdate) window.location.reload();
  });

  navigator.serviceWorker.register(new URL("./sw.js", window.location.href), {
    scope: new URL("./", window.location.href).pathname
  }).then((registration) => {
    const showUpdate = () => { $("#update-notice").hidden = false; };
    if (registration.waiting) showUpdate();
    registration.addEventListener("updatefound", () => {
      const worker = registration.installing;
      if (!worker) return;
      worker.addEventListener("statechange", () => {
        if (worker.state === "installed" && hadController) showUpdate();
      });
    });
    registration.update().catch(() => undefined);
    $("#apply-update").addEventListener("click", () => {
      if (!registration.waiting) return;
      reloadAfterUpdate = true;
      registration.waiting.postMessage("SKIP_WAITING");
    }, { once: true });
  }).catch(() => {
    // The page remains usable when service workers are unavailable.
  });
}

async function importBackupFile(file) {
  if (file.size > 25 * 1024 * 1024) throw new Error("备份文件超过 25 MB 限制");
  const backup = JSON.parse(await file.text());
  if (!backup || backup.format !== "multi-stopwatch-pwa-backup" || !backup.state) {
    throw new Error("这不是有效的多秒表 JSON 备份");
  }
  if (Array.isArray(backup.state.timers) && backup.state.timers.length > MAX_STOPWATCHES) {
    throw new Error("备份包含超过 7 个秒表，无法导入");
  }
  if (!window.confirm("导入备份会替换当前秒表、设置、日志和回收站数据，继续吗？")) return;
  state = normalizeState(backup.state);
  wallpaperRecord = backup.wallpaper && backup.wallpaper.dataUrl
    ? { name: String(backup.wallpaper.name || "wallpaper"), blob: dataUrlToBlob(backup.wallpaper.dataUrl) }
    : null;
  logAction("应用", "导入 JSON 备份", "");
  render();
  await persist();
  closeDialog("data-dialog");
  toast("备份已导入");
}

function wireEvents() {
  $("#add-stopwatch").addEventListener("click", addStopwatch);
  $("#interface-button").addEventListener("click", () => openDialog("interface-dialog"));
  $("#appearance-button").addEventListener("click", () => {
    syncAppearanceControls();
    openDialog("appearance-dialog");
  });
  $("#data-button").addEventListener("click", () => openDialog("data-dialog"));
  $("#trash-button").addEventListener("click", () => openDialog("trash-dialog"));
  $("#wallpaper-button").addEventListener("click", () => {
    closeDialog("interface-dialog");
    $("#wallpaper-file").click();
  });
  $("#remove-wallpaper").addEventListener("click", () => {
    wallpaperRecord = null;
    logAction("界面", "移除壁纸", "");
    render();
    persist();
    closeDialog("interface-dialog");
    toast("壁纸已移除");
  });
  $("#card-style-button").addEventListener("click", openCardStyleDialog);
  $("#wallpaper-file").addEventListener("change", (event) => {
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      toast("请选择图片文件");
      return;
    }
    if (file.size > 8 * 1024 * 1024) {
      toast("壁纸文件不能超过 8 MB");
      return;
    }
    wallpaperRecord = { name: file.name, blob: file };
    logAction("界面", "设置壁纸", file.name);
    render();
    persist().then(() => toast("壁纸已应用"));
  });

  listElement.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const card = button.closest("[data-timer-id]");
    const timer = card && getTimer(card.dataset.timerId);
    if (!timer) return;
    switch (button.dataset.action) {
      case "toggle": toggleTimer(timer); break;
      case "lap": addLap(timer); break;
      case "reset": resetTimer(timer); break;
      case "rename": renameTimer(timer); break;
      case "delete": moveTimerToTrash(timer); break;
      case "note": editNote(timer); break;
      default: break;
    }
  });

  $("#name-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const value = $("#timer-name").value.trim();
    if (!value) {
      toast("名称不能为空");
      return;
    }
    if (editingTimerId) {
      const timer = getTimer(editingTimerId);
      if (timer && timer.name !== value) {
        const previous = timer.name;
        timer.name = value;
        logAction(timer.name, "改名", `${previous} → ${value}`);
      }
    } else {
      if (state.timers.length >= MAX_STOPWATCHES) {
        toast("最多只能创建 7 个秒表");
        return;
      }
      const timer = makeTimer(value, firstColor());
      state.timers.unshift(timer);
      logAction(timer.name, "创建", "");
    }
    editingTimerId = null;
    closeDialog("name-dialog");
    saveAndRender();
  });

  $("#note-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const timer = getTimer(editingNoteTimerId);
    if (timer) {
      timer.note = $("#timer-note").value.slice(0, MAX_NOTE_LENGTH);
      logAction(timer.name, "编辑笔记", `字数 ${timer.note.length}`);
      saveAndRender();
    }
    closeDialog("note-dialog");
  });

  $("#appearance-form").addEventListener("submit", (event) => {
    event.preventDefault();
    state.settings.themeColor = $("#theme-color").value;
    state.settings.darkMode = $("#dark-mode").checked;
    state.settings.compactList = $("#compact-list").checked;
    state.settings.fontScale = Number($("#font-scale").value) / 100;
    logAction("界面", "更改外观", "");
    saveAndRender("外观已保存");
    closeDialog("appearance-dialog");
  });

  $("#theme-color").addEventListener("input", () => {
    $("#theme-color-value").value = $("#theme-color").value.toUpperCase();
  });
  $("#font-scale").addEventListener("input", () => {
    $("#font-scale-value").value = `${$("#font-scale").value}%`;
  });

  $("#style-timer").addEventListener("change", syncCardStyleControls);
  $("#card-background").addEventListener("input", updateStylePreview);
  $("#card-border").addEventListener("input", updateStylePreview);
  $("#card-transparency").addEventListener("input", updateStylePreview);
  $("#card-style-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const timer = getTimer($("#style-timer").value);
    if (!timer) return;
    timer.backgroundColor = $("#card-background").value;
    timer.borderColor = $("#card-border").value;
    timer.transparency = Number($("#card-transparency").value);
    logAction(timer.name, "更改卡片外观", `透明度 ${timer.transparency}%`);
    saveAndRender("秒表样式已应用");
    closeDialog("card-style-dialog");
  });

  $("#dismiss-install-guide").addEventListener("click", () => {
    $("#ios-install-guide").hidden = true;
    try {
      localStorage.setItem("multi-stopwatch-ios-guide-dismissed", "1");
    } catch {
      // Dismissal remains effective for this page session.
    }
  });
  $("#export-backup").addEventListener("click", exportBackup);
  $("#export-timers").addEventListener("click", exportTimersCsv);
  $("#export-logs").addEventListener("click", exportLogsCsv);
  $("#import-backup").addEventListener("click", () => $("#backup-file").click());
  $("#backup-file").addEventListener("change", async (event) => {
    const file = event.target.files && event.target.files[0];
    event.target.value = "";
    if (!file) return;
    try {
      await importBackupFile(file);
    } catch (error) {
      toast(error.message || "备份导入失败");
    }
  });
  $("#trash-list").addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const timerId = button.dataset.timerId;
    if (button.dataset.action === "restore") {
      restoreTrashTimer(timerId);
    } else if (button.dataset.action === "purge" && window.confirm("永久删除这个秒表？此操作无法撤销。")) {
      const entry = state.trash.find((item) => item.timer.id === timerId);
      state.trash = state.trash.filter((item) => item.timer.id !== timerId);
      if (entry) logAction(entry.timer.name, "永久删除", "");
      saveAndRender();
    }
  });
  $("#clear-trash").addEventListener("click", () => {
    if (!state.trash.length || !window.confirm("清空回收站并永久删除全部内容？")) return;
    state.trash = [];
    logAction("回收站", "清空", "");
    saveAndRender();
  });
  $("#apply-update").addEventListener("click", () => {
    $("#update-notice").hidden = true;
  });

  document.querySelectorAll("[data-close]").forEach((button) => {
    button.addEventListener("click", () => closeDialog(button.dataset.close));
  });
  document.querySelectorAll("dialog").forEach((dialog) => {
    dialog.addEventListener("click", (event) => {
      if (event.target === dialog) dialog.close();
    });
  });
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) renderTimes();
  });
}

async function startApp() {
  await loadSavedData();
  pruneTrash();
  logAction("应用", "启动", "");
  wireEvents();
  render();
  if (persistenceUnavailable) toast("无法访问本机存储，请导出 JSON 备份");
  else persist();
  showIosInstallGuide();
  registerServiceWorker();
  setInterval(renderTimes, 50);
  setInterval(() => {
    if (pruneTrash()) {
      logAction("回收站", "自动清理", "已移除超过 7 天的秒表");
      saveAndRender();
    }
  }, 60 * 1000);
}

startApp().catch(() => {
  state = normalizeState({ timers: [makeTimer("秒表 1", COLOR_PALETTE[0])], settings: initialSettings() });
  wireEvents();
  render();
  toast("数据读取失败，当前数据不会自动保存；请尽快导出备份");
});
