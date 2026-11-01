import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.tsx";
import "./index.css";
import { clock } from "./state/clock.ts";
import * as store from "./state/store.ts";

// Keyboard-opened drawers and menus skip their entrance motion (index.css).
const modality = (mode: "key" | "pointer") => () => { document.documentElement.dataset.input = mode; };
addEventListener("keydown", modality("key"), true);
addEventListener("pointerdown", modality("pointer"), true);

// for tests and the console: window.studio.store.useStore.getState()
(window as unknown as { studio: unknown }).studio = { store, clock };

// Register PWA service worker
if ("serviceWorker" in navigator && (window.location.protocol === "http:" || window.location.protocol === "https:")) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch((err) => {
      console.warn("ServiceWorker registration failed:", err);
    });
  });
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
