import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Backend плагина в режиме разработки: ../backend, gradlew bootRun.
const backend = 'http://127.0.0.1:18081';

export default defineConfig({
  // Относительные пути к ассетам: страница работает и в корне, и под префиксом вроде /plugins/attendance/.
  base: './',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
