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
    // 与浏览器构建使用同一 ESM 解析，避免 Node 将 async-validator 的 CJS 默认导出当成构造器。
    server: { deps: { inline: ['element-plus'] } },
  },
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
