import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
      // 上传接口返回的是 /uploads/... 裸路径，不带 /api 前缀。
      // 不代理的话这些请求会被 dev server 的 SPA fallback 接走，
      // 返回 200 + text/html（index.html），浏览器解不出图片，表现为图裂。
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
