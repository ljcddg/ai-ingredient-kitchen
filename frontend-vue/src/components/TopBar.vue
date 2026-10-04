<template>
  <!--
    顶栏（三个内页共用）。
    对应原生版：各页面里的 <header class="topbar"> + js/nav.js

    原生版刻意把顶栏 HTML 写在各页面里（不用 JS 生成），避免"闪一下才出现"。
    Vue 版是组件，但结构是同步渲染的，同样不会闪 —— 且比三份手写 HTML 更好维护。
  -->
  <header class="topbar">
    <div class="topbar-left">
      <div class="brand">
        <svg class="brand-mark" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <path d="M3.5 10.5h17"/>
          <path d="M6 10.5v4.5a6 6 0 0 0 12 0v-4.5"/>
          <path d="M12 3.5v3.5"/>
        </svg>
        <span>AI 灵感厨房</span>
      </div>
      <nav class="nav" aria-label="主导航">
        <router-link class="nav-link" to="/kitchen" data-page="kitchen"
                     :aria-current="current === 'kitchen' ? 'page' : undefined">找菜</router-link>
        <router-link class="nav-link" to="/recommend" data-page="recommend"
                     :aria-current="current === 'recommend' ? 'page' : undefined">推荐</router-link>
        <router-link class="nav-link" to="/rank" data-page="rank"
                     :aria-current="current === 'rank' ? 'page' : undefined">榜单</router-link>
        <router-link class="nav-link" to="/profile" data-page="profile"
                     :aria-current="current === 'profile' ? 'page' : undefined">我的</router-link>
        <!-- 管理入口只给管理员看。普通用户看不到它，但就算手敲 /admin，
             路由守卫也会送回来、后端接口更是直接 403 —— 这里只是少一层困惑 -->
        <router-link v-if="isAdmin" class="nav-link" to="/admin" data-page="admin"
                     :aria-current="current === 'admin' ? 'page' : undefined">管理</router-link>
      </nav>
    </div>
    <div class="topbar-right">
      <span class="who" id="whoText">{{ whoText }}</span>
      <button class="ghost-btn" id="logoutBtn" type="button" @click="logout">退出</button>
    </div>
  </header>
</template>

<script setup>
/* ============================================================
   顶栏：昵称、导航高亮、退出登录
   ------------------------------------------------------------
   原生版 Nav.init() 还兼了「登录态守卫」；Vue 版这活交给路由守卫
   （router/index.js 的 beforeEach），职责更清晰，也不会出现
   "页面先渲染一半再被跳走"的闪烁。
   ============================================================ */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { authState, clearLogin, isAdmin } from '../store/auth.js'
import { toastSuccess } from '../store/toast.js'

const props = defineProps({
  /** 当前页标识：'kitchen' | 'dish' | 'recommend' | 'rank' | 'profile' | 'admin' */
  current: { type: String, default: '' }
})

const router = useRouter()

/** 昵称优先，读不到就退到用户名（原生版是「已登录」，这里用真实用户名更友好） */
const whoText = computed(() => authState.nickname || authState.username || '已登录')

function logout() {
  clearLogin()
  toastSuccess('已退出登录')
  router.replace('/')
}
</script>
