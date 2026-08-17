// Theme handling, kept out of App.jsx so the component stays about layout.
//
// Three rules, in order of priority:
//   1. What the user last chose in this app.
//   2. What their operating system prefers.
//   3. Light.
//
// The choice is written to <html data-theme="..."> and every colour in styles.css reads
// from variables scoped to that attribute, so no component ever branches on the theme.

const STORAGE_KEY = 'semanticdocs.theme';

export function readTheme() {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved === 'light' || saved === 'dark') return saved;
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem(STORAGE_KEY, theme);
}

// Applied before React renders, so the first paint is already the right colour.
// Without this you get a white flash on load for dark-theme users.
export function initTheme() {
  const theme = readTheme();
  document.documentElement.setAttribute('data-theme', theme);
  return theme;
}
