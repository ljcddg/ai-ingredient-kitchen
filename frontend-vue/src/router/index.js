/* ============================================================
   路由表
   ============================================================ */

import { createRouter, createWebHistory } from 'vue-router'
import { authState, isAdmin } from '../store/auth.js'

// 路由用 history 模式，路径与原生版页面一一对应，便于对照迁移：
//   /              -> index.html   （登录/注册）
//   /kitchen       -> kitchen.html （找菜工作台）
//   /dish/:id      -> dish.html?id=xxx（菜品详情）
//   /rank          -> rank.html    （榜单）
//   /recommend     -> 推荐页       （冷启动推荐池，本次新增）
//   /profile       -> profile.html （我的）
//   /admin         -> 管理台（食材候选审核 / 索引同步 / 营养重算），仅管理员
const routes = [
  { path: '/', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/kitchen', name: 'kitchen', component: () => import('../views/KitchenView.vue') },
  // 识菜：拍照/选图 → YOLO 识别 → 食材清单勾选 → 回填厨房生成表单
  // 设计文档：docs/yolo-vision-design.md §7
  { path: '/vision', name: 'vision', component: () => import('../views/VisionView.vue') },
  { path: '/dish/:id', name: 'dish', component: () => import('../views/DishView.vue'), props: true },
  { path: '/rank', name: 'rank', component: () => import('../views/RankView.vue') },
  // 发现页：取代旧的推荐页，把"选食材 → 出推荐"和榜单合并成一个入口
  { path: '/discover', name: 'discover', component: () => import('../views/DiscoverView.vue') },
  // 旧的推荐页地址重定向过来，收藏里存了老链接的人不至于 404
  { path: '/recommend', redirect: '/discover' },
  { path: '/profile', name: 'profile', component: () => import('../views/ProfileView.vue') },
  { path: '/admin', name: 'admin', component: () => import('../views/AdminView.vue'), meta: { admin: true } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

/**
 * 登录态守卫。
 *
 * 与原生版 Nav.init() 里的守卫语义一致：
 *   受保护页面无 token → 回登录页；已登录还想进登录页 → 去工作台。
 */
router.beforeEach((to) => {
  const logged = !!authState.token

  if (!to.meta.public && !logged) {
    return { path: '/', query: { redirect: to.fullPath } }
  }
  if (to.meta.public && logged && to.name === 'login') {
    return { path: '/kitchen' }
  }
  // 管理页：普通用户直接送回工作台。
  // 这层只是省得让人进去看一片 403 —— 真正的权限判定在后端（fail-closed）。
  if (to.meta.admin && !isAdmin.value) {
    return { path: '/kitchen' }
  }
  return true
})
