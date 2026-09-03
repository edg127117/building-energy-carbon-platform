import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 独立构建入口隔离继承界面的全局样式和展示插件；同一工程内保留原入口及其验证。
export default defineConfig({
  base: './',
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  build: {
    outDir: 'dist/platform',
    sourcemap: 'hidden',
    rollupOptions: { input: fileURLToPath(new URL('./platform.html', import.meta.url)) },
  },
})
