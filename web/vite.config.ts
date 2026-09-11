import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  base: './',
  build: {
    sourcemap: 'hidden',
    rollupOptions: {
      output: {
        // 公开入口和跨模块复用的暖通编排同块输出，避免重导出产生循环分块执行顺序风险。
        manualChunks(id) {
          if (id.split('\\').join('/').includes('/src/modules/dashboard/')) return 'hvac-dashboard'
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
