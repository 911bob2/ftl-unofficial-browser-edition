// The browser's "Debug console" setting (docs/CONSOLE.md), off by default: on, the game's console opens on ` and \ in
// every mode and the game bar shows its Console button. Off, only a Hyperspace mod's <console enabled="true"/> opens it
// (on Hyperspace's console key). It belongs to this browser, like the debug log switch.
const KEY = 'ftl-debug-console';
export function consoleEnabled() { try { return localStorage.getItem(KEY) === '1'; } catch { return false; } }
export function setConsoleEnabled(on) { try { if (on) localStorage.setItem(KEY, '1'); else localStorage.removeItem(KEY); } catch { /* this visit only */ } }
