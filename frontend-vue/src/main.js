/* ============================================================
   应用入口：装配插件与全局样式
   ============================================================ */

import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router/index.js'
import { clearLogin } from './store/auth.js'
import { setUnauthorizedHandler } from './api.js'

// 全局样式（顺序有讲究：令牌与基础层在前，各页样式在后）
// 迁移策略：直接复用原生版的 CSS，避免重画导致视觉回归。
import './styles/fx-bg.css'   // 流光背景层（内页共用）
import './styles/app.css'     // 全局：令牌 / body / 顶栏 / 按钮 / 卡片 / Toast
import './styles/style.css'   // 登录页专属
import './styles/kitchen.css' // 找菜工作台
import './styles/dish.css'    // 菜品详情
import './styles/rank.css'    // 榜单
import './styles/recommend.css' // 推荐（旧页，保留做对照）
import './styles/discover.css'  // 发现（取代推荐页与榜单页的新入口）
import './styles/profile.css' // 我的

const app = createApp(App)

/**
 * token 失效时由 api.js 回调这里。
 * 不用 location.href 硬跳，走路由以保留 SPA 状态（且有守卫兜底）。
 */
setUnauthorizedHandler(() => {
  clearLogin()
  if (router.currentRoute.value.path !== '/') {
    router.replace({ path: '/', query: { expired: '1' } })
  }
})

app.use(router)
app.mount('#app')
