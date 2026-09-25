<template>
  <div class="page-profile">
    <FxBg />
    <TopBar current="profile" />

    <main class="page-wrap">
      <div class="page-head">
        <div>
          <h1 class="page-title">我的</h1>
          <p class="page-sub">账号信息、收藏的菜、自己生成的菜，都在这儿。</p>
        </div>
      </div>

      <!-- 个人信息卡 -->
      <section class="card profile-card">
        <div class="avatar" id="avatar" aria-hidden="true">{{ firstChar(displayName) }}</div>
        <div class="profile-info">
          <p class="profile-name" id="profileName">{{ displayName }}</p>
          <p class="profile-sub" id="profileSub">{{ profileSub }}</p>
        </div>
        <div class="profile-action">
          <button class="secondary-btn" id="logoutBtnCard" type="button" @click="logout">退出登录</button>
        </div>
      </section>

      <!-- 两个标签用同一块面板，切 tab 时改 aria-labelledby 指回对应的 tab -->
      <!--
        【类名是 profile-tabs / profile-tab，刻意不叫 tabs / tab】
        原生版每个页面只引自己的 CSS，所以「我的」页的 .tab 和登录页的 .tab
        永远不会同时存在。Vue 是 SPA，所有样式全局常驻 —— 两边同名的话，
        后加载的 profile.css 会去改登录页的登录/注册按钮（实测：登录页那两个
        按钮被挤到左边、指示块错位）。改名后两边互不影响。
      -->
      <div class="profile-tabs" role="tablist" aria-label="我的内容">
        <button class="profile-tab" role="tab" id="tabDishes" type="button"
                :aria-selected="state.tab === 'dishes' ? 'true' : 'false'"
                aria-controls="panel" data-tab="dishes"
                @click="switchTab('dishes')">
          我的菜品 <span class="tab-count" id="countDishes">{{ dishes.length }}</span>
        </button>
        <button class="profile-tab" role="tab" id="tabCollects" type="button"
                :aria-selected="state.tab === 'collects' ? 'true' : 'false'"
                aria-controls="panel" data-tab="collects"
                @click="switchTab('collects')">
          我的收藏 <span class="tab-count" id="countCollects">{{ collects.length }}</span>
        </button>
      </div>

      <div id="panel" role="tabpanel" aria-live="polite"
           :aria-labelledby="state.tab === 'dishes' ? 'tabDishes' : 'tabCollects'">

        <!-- 骨架（首次加载该 tab 时） -->
        <div v-if="state.loading" class="skeleton">
          <div class="sk-line sk-title"></div>
          <div class="sk-line"></div>
          <div class="sk-line sk-short"></div>
        </div>

        <!-- 出错 -->
        <div v-else-if="state.error" class="err-box">
          <p class="err-title">没能加载出来</p>
          <p class="err-text">{{ state.error }}</p>
          <button class="secondary-btn" id="reloadBtn" type="button" @click="loadTab(state.tab)">重新加载</button>
        </div>

        <!-- 我的菜品 -->
        <template v-else-if="state.tab === 'dishes'">
          <div v-if="!dishes.length" class="empty">
            <p class="empty-title">还没有自己做的菜</p>
            <p class="empty-text">去「找菜」页面挑几样手边有的食材，让 AI 现场做一道。做好的菜会自动存到这里。</p>
            <div class="empty-actions">
              <router-link class="primary-btn" to="/kitchen">去挑食材</router-link>
            </div>
          </div>

          <template v-else>
            <!-- 草稿和已发布分组展示 —— 它们是两种对外可见性，混排会让用户
                 搞不清「这道菜别人到底能不能看到」 -->
            <template v-if="drafts.length">
              <div class="group-head">草稿 · 只有你能看到（{{ drafts.length }}）</div>
              <ul class="dish-list">
                <li v-for="d in drafts" :key="d.id"><DishCard :dish="d" /></li>
              </ul>
            </template>
            <template v-if="published.length">
              <div class="group-head">已发布（{{ published.length }}）</div>
              <ul class="dish-list">
                <li v-for="d in published" :key="d.id"><DishCard :dish="d" /></li>
              </ul>
            </template>
          </template>
        </template>

        <!-- 我的收藏 -->
        <template v-else>
          <div v-if="!collects.length" class="empty">
            <p class="empty-title">还没有收藏</p>
            <p class="empty-text">在菜品详情页点「收藏」，想做又暂时没空做的菜就会留在这儿，方便回头找。</p>
            <div class="empty-actions">
              <router-link class="primary-btn" to="/kitchen">去挑食材</router-link>
            </div>
          </div>
          <ul v-else class="dish-list">
            <li v-for="d in collects" :key="d.id">
              <DishCard :dish="d" :extra-bits="d.markedTime ? ['收藏于 ' + d.markedTime] : []" />
            </li>
          </ul>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   「我的」页（Vue 版）
   对应原生版：profile.html + js/profile.js

   两块内容：我生成的菜品、我收藏的菜品。
   账号信息先从 localStorage 渲染（立刻可见），再拿服务端的权威值补正。
   ============================================================ */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import DishCard from '../components/DishCard.vue'
import { AuthApi, DishApi } from '../api.js'
import { authState, clearLogin } from '../store/auth.js'
import { STORAGE_KEYS } from '../config.js'
import { toastSuccess } from '../store/toast.js'

const router = useRouter()

const state = reactive({
  tab: 'dishes',
  loading: false,
  error: '',
  /** 防止切 tab 时重复请求 */
  loaded: { dishes: false, collects: false }
})

const dishes = ref([])
const collects = ref([])
/** 服务端补正过的账号信息（可选） */
const serverSub = ref('')

const displayName = computed(() => authState.nickname || authState.username || '已登录用户')

/**
 * 头像是昵称首字。中文取第一个字，英文取首字母大写。
 * 不用默认头像图片：省一个资源请求，而且首字比通用人像更有辨识度。
 */
function firstChar(name) {
  const s = String(name || '').trim()
  return s ? s.charAt(0).toUpperCase() : '·'
}

/** 账号副标题：优先用服务端补正过的值，其次用本地的 */
const profileSub = computed(() => {
  if (serverSub.value) return serverSub.value
  const bits = []
  if (authState.username) bits.push('@' + authState.username)
  if (authState.userId) bits.push('ID ' + authState.userId)
  return bits.join(' · ') || '本机登录信息不完整，重新登录一次即可补全'
})

/**
 * 用服务端的值补正页面上的账号信息。
 *
 * 失败不提示也不阻断：localStorage 里的信息已经够页面显示了，
 * 为了一个"更权威的 ID"弹个红色错误提示，对用户是纯噪音。
 */
async function refreshProfileFromServer() {
  try {
    const data = await AuthApi.profile()
    const bits = []
    if (data.username) bits.push('@' + data.username)
    if (data.userId) bits.push('ID ' + data.userId)
    if (bits.length) serverSub.value = bits.join(' · ')

    // 顺便把 userId 回填到本地（保持字符串形态，别让雪花 ID 丢精度）
    if (data.userId && !authState.userId) {
      authState.userId = String(data.userId)
      localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify({
        userId: authState.userId,
        username: authState.username,
        nickname: authState.nickname
      }))
    }
  } catch (e) {
    /* 静默失败，见上面的注释 */
  }
}

const drafts = computed(() => dishes.value.filter(d => d.status === 0))
const published = computed(() => dishes.value.filter(d => d.status !== 0))

function switchTab(tab) {
  if (state.tab === tab && state.loaded[tab]) return
  state.tab = tab
  state.error = ''

  // 没加载过就先显示骨架，加载过直接渲染（切回来不闪）
  if (!state.loaded[tab]) {
    state.loading = true
    loadTab(tab)
  }
}

async function loadTab(tab) {
  state.loading = true
  state.error = ''
  try {
    if (tab === 'dishes') {
      dishes.value = (await DishApi.myDishes()) || []
    } else {
      collects.value = (await DishApi.myCollects()) || []
    }
    state.loaded[tab] = true
  } catch (e) {
    // 只有用户还停在这个 tab 时才报错 —— 否则会把已经切走的面板又写回错误
    if (state.tab === tab) state.error = e.message || '未知错误'
  } finally {
    if (state.tab === tab) state.loading = false
  }
}

function logout() {
  clearLogin()
  toastSuccess('已退出登录')
  router.replace('/')
}

onMounted(() => {
  refreshProfileFromServer()
  loadTab('dishes')
})
</script>
