import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 容器里后端是 compose 的服务名，本机开发仍走 localhost。
const target = process.env.VITE_API_TARGET ?? 'http://localhost:8080'

// server 与 preview 不共享代理配置：dev 读前者，托管构建产物读后者。
// 只配 server 的话，容器里跑 vite preview 时 /api 会 404。
const proxy = {
  '/api': {
    target,
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api/, ''),
  },
  // 上传接口返回的是 /uploads/... 裸路径，不带 /api 前缀。
  // 不代理的话这些请求会被 SPA fallback 接走，
  // 返回 200 + text/html（index.html），浏览器解不出图片，表现为图裂。
  '/uploads': {
    target,
    changeOrigin: true,
  },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy,
  },
  preview: {
    host: '0.0.0.0',
    port: 5173,
    proxy,
  },
})
