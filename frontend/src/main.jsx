import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import { applyStoredTheme } from './components/ThemeToggle.jsx';
import './styles.css';

// Set the theme before the first render, otherwise a dark-mode user sees a white flash.
applyStoredTheme();
import { initTheme } from './theme.js';

// Runs before render so there is no flash of the wrong theme.
initTheme();

createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
