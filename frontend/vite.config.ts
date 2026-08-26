import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// proxy 直接指向 Python engine(18000)——MVP 不经 Java gateway,
// 后续接 gateway 时把 target 换成 8080 即可(对齐 CyberVerse 的 /api /ws 拆分)
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    proxy: {
      '/v1/asr': {
        target: 'http://localhost:18000',
        changeOrigin: true,
        ws: true, // /v1/asr/stream 是 WebSocket
      },
    },
  },
})
