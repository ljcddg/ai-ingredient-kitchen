import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// ============================================================
// 前端（Vue 版）构建配置
//
// 【关键约定】开发服务器把 /api/** 反向代理到网关 7000，
// 让"页面与接口同源"——这与原生版 frontend/serve.py 的思路完全一致。
// 同源不只是省掉 CORS，更是"给别人访问"能成立的前提：
// 若接口地址写死 127.0.0.1，访客的浏览器会去请求他自己那台电脑的端口。
// ============================================================
export default defineConfig(({ command }) => ({
  plugins: [vue()],
  // 编译期常量：dev server 自带 /api 代理，构建产物没有
  define: {
    __VUE_DEV_PROXY__: JSON.stringify(command === 'serve')
  },
  server: {
    port: 5500,
    // 显式绑 IPv4：Vite 默认只监听 localhost（可能解析成 IPv6 ::1），
    // 而验证脚本与网关都走 127.0.0.1，不绑就会"服务起来了却连不上"。
    host: '127.0.0.1',
    // 端口被原生版占用时不自动换端口，直接报错提示，避免两版混淆
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:7000',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    // 雪花 ID 是 19 位字符串，构建产物里绝不能被当成数字处理
    assetsDir: 'assets'
  }
}))
