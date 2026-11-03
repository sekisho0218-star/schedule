// 画面（renderer）から使える機能だけを公開する
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  state: () => ipcRenderer.invoke('state'),
  settings: (patch) => ipcRenderer.invoke('settings', patch),
  saveClient: (c) => ipcRenderer.invoke('save-client', c),
  signIn: () => ipcRenderer.invoke('sign-in'),
  signOut: () => ipcRenderer.invoke('sign-out'),
  listEvents: (from, to) => ipcRenderer.invoke('list-events', from, to),
  createEvent: (ev) => ipcRenderer.invoke('create-event', ev),
  updateEvent: (ev) => ipcRenderer.invoke('update-event', ev),
  deleteEvent: (ev) => ipcRenderer.invoke('delete-event', ev),
  listTasks: (from, to) => ipcRenderer.invoke('list-tasks', from, to),
  addTask: (date, text) => ipcRenderer.invoke('add-task', date, text),
  updateTask: (id, text) => ipcRenderer.invoke('update-task', id, text),
  completeTask: (id) => ipcRenderer.invoke('complete-task', id),
  hide: () => ipcRenderer.invoke('hide'),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
  shiftSettings: (patch) => ipcRenderer.invoke('shift-settings', patch),
  shiftRun: () => ipcRenderer.invoke('shift-run'),
  shiftApply: (choice) => ipcRenderer.invoke('shift-apply', choice),
  shiftCalendars: () => ipcRenderer.invoke('shift-calendars'),
  shiftResetDismissed: () => ipcRenderer.invoke('shift-reset-dismissed'),
  onShiftResult: (cb) => ipcRenderer.on('shift-result', (_e, r) => cb(r)),
  onRefresh: (cb) => ipcRenderer.on('refresh', () => cb()),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
});
