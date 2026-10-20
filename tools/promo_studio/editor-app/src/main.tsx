import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.tsx";
import "./index.css";
import { clock } from "./state/clock.ts";
import * as store from "./state/store.ts";

// for tests and the console: window.studio.store.useStore.getState()
(window as unknown as { studio: unknown }).studio = { store, clock };

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
