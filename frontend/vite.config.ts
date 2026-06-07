import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 代理 API 请求到后端（后续迭代启用）
    // proxy: {
    //   '/api': 'http://localhost:8080'
    // }
  }
})
