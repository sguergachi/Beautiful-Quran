import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/** GitHub Pages serves this repo from /Beautiful-Quran/ (docs/). The app lives at /app/. */
const pagesBase = process.env.VITE_BASE ?? '/'

export default defineConfig({
  plugins: [react()],
  base: pagesBase,
  server: {
    port: 5173,
  },
  optimizeDeps: {
    // sql.js publishes UMD browser files. Pre-bundling creates the ESM default
    // export Vite's dev server needs instead of serving that raw UMD file.
    // Gapless-5 is the same class of UMD package (developer-flag transport).
    include: ['sql.js', '@regosen/gapless-5'],
  },
  build: {
    // Gzipping every chunk only to print its size costs about a second.
    reportCompressedSize: false,
  },
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.test.ts'],
    // The suite is pure functions in Node: one module graph per worker, not
    // one per file, saves most of the start-up cost. A test that needs a
    // clean module registry must reset it itself (`vi.resetModules()`).
    pool: 'threads',
    isolate: false,
  },
})
