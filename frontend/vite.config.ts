import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The UI calls the backend through a same-origin /api path: Vite proxies it in development,
// nginx proxies it in the Docker image. No CORS configuration is needed on the backend.
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      output: { manualChunks: { charts: ['recharts'], map: ['leaflet', 'react-leaflet'] } },
    },
  },
  server: {
    port: 5173,
    proxy: { '/api': process.env.VITE_API_TARGET ?? 'http://localhost:8080' },
  },
});
