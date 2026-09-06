import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The console calls /api/* on the same origin; in development Vite hands
    // those through to the API so no CORS or base-URL juggling is needed.
    proxy: {
      '/api': { target: 'http://localhost:4001', changeOrigin: true },
    },
  },
  build: { outDir: 'dist', sourcemap: true },
});
