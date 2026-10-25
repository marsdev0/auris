import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 业务接口统一走 gateway(8080):路由/限流在网关层,前端不感知后端拓扑
// (asr 流式例外:WS 直连 engine,见 ROADMAP §0——ASR 真流式不走网关)
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    proxy: {
      '/v1/asr': {
        target: 'http://localhost:8080', // 走 gateway(P12-D6 纪律:前端不直连任何后端)
        changeOrigin: true,
        ws: true, // /v1/asr/stream 是 WebSocket(WS 经 gateway 转发)
      },
      '/v1/byok': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/v1/transcribe': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/v1/auth': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/v1/notification': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
