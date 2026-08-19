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
  test: {
    globals: true,
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
