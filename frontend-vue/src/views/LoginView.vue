<template>
  <div class="page-login">
    <!-- ==================== 流动背景 ==================== -->
    <div class="bg-flow" aria-hidden="true">
      <span class="blob blob-1"></span>
      <span class="blob blob-2"></span>
      <span class="blob blob-3"></span>
      <span class="blob blob-4"></span>
      <!-- 噪点叠在色团之上：打散 8bit 屏上的色带 -->
      <span class="fx-grain"></span>
    </div>

    <!-- ==================== 漂浮装饰 ==================== -->
    <div class="floaters" aria-hidden="true">
      <span
        v-for="f in floaters"
        :key="f.id"
        class="floater"
        :class="{ 'is-soft': f.soft }"
        :style="{ ...f.style, color: f.color }"
        v-html="f.html"
      ></span>
    </div>

    <div class="page">
      <!-- ==================== 左侧品牌区 ==================== -->
      <aside class="brand-panel">
        <div class="brand-inner">
          <div class="brand-logo">
            <svg width="34" height="34" viewBox="0 0 34 34" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M6 17.5h22a1.6 1.6 0 0 1 1.6 1.6v.6c0 4.5-3.7 8.1-8.1 8.1h-8.9c-4.5 0-8.1-3.6-8.1-8.1v-.6A1.6 1.6 0 0 1 6 17.5Z"
                    stroke="currentColor" stroke-width="2.1" stroke-linejoin="round"/>
              <path d="M3.6 17.5h2M28.4 17.5h2"
                    stroke="currentColor" stroke-width="2.1" stroke-linecap="round"/>
              <path d="M13 12.6c0-1.8 1.4-2.4 1.4-4.1M17.5 12.6c0-1.8 1.4-2.4 1.4-4.1M22 12.6c0-1.8 1.4-2.4 1.4-4.1"
                    stroke="currentColor" stroke-width="2" stroke-linecap="round" opacity=".78"/>
            </svg>
          </div>
          <h1 class="brand-title">欢迎回来</h1>
          <p class="brand-sub">
            登录你的账号，继续探索<br>
            冰箱里那些食材的无限可能。
          </p>

          <ul class="brand-points">
            <li>
              <span class="dot"></span>
              <div>
                <strong>AI 食谱生成</strong>
                <small>说出你有的食材，灵感立刻就来</small>
              </div>
            </li>
            <li>
              <span class="dot"></span>
              <div>
                <strong>收藏与历史</strong>
                <small>喜欢的菜谱随手存，做过的不再忘</small>
              </div>
            </li>
            <li>
              <span class="dot"></span>
              <div>
                <strong>营养与热量估算</strong>
                <small>每道菜的营养构成，心里有数</small>
              </div>
            </li>
          </ul>
        </div>

        <div class="brand-deco deco-a"></div>
        <div class="brand-deco deco-b"></div>
      </aside>

      <!-- ==================== 右侧表单区 ==================== -->
      <main class="form-panel">
        <div class="form-card">
          <!-- Tab 切换 -->
          <div class="tabs" role="tablist" :data-active="activeTab">
            <button class="tab" :class="{ 'is-active': activeTab === 'login' }"
                    data-tab="login" role="tab" @click="switchTab('login')">登录</button>
            <button class="tab" :class="{ 'is-active': activeTab === 'register' }"
                    data-tab="register" role="tab" @click="switchTab('register')">注册</button>
            <div class="tab-indicator"></div>
          </div>

          <!-- ---------- 登录表单 ---------- -->
          <form class="form" :class="{ 'is-hidden': activeTab !== 'login' }" id="loginForm"
                novalidate @submit.prevent="doLogin">
            <div class="field" :class="{ 'has-error': errors.loginUsername }">
              <label for="loginUsername">用户名</label>
              <div class="input-wrap">
                <svg class="input-icon" viewBox="0 0 20 20" fill="none">
                  <circle cx="10" cy="6.5" r="3.2" stroke="currentColor" stroke-width="1.6"/>
                  <path d="M3.8 16.2c0-3.1 2.8-5 6.2-5s6.2 1.9 6.2 5"
                        stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                </svg>
                <input type="text" id="loginUsername" v-model="loginForm.username"
                       placeholder="请输入用户名" autocomplete="username"
                       @input="errors.loginUsername = ''">
              </div>
              <p class="field-error" data-error-for="loginUsername">{{ errors.loginUsername }}</p>
            </div>

            <div class="field" :class="{ 'has-error': errors.loginPassword }">
              <label for="loginPassword">密码</label>
              <div class="input-wrap">
                <svg class="input-icon" viewBox="0 0 20 20" fill="none">
                  <rect x="4" y="8.6" width="12" height="8.4" rx="2.2"
                        stroke="currentColor" stroke-width="1.6"/>
                  <path d="M6.8 8.6V6.6a3.2 3.2 0 0 1 6.4 0v2"
                        stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                </svg>
                <input :type="showLoginPwd ? 'text' : 'password'" id="loginPassword"
                       v-model="loginForm.password"
                       placeholder="请输入密码" autocomplete="current-password"
                       @input="errors.loginPassword = ''">
                <button type="button" class="eye-btn" :class="{ 'is-on': showLoginPwd }"
                        data-eye="loginPassword" :aria-label="showLoginPwd ? '隐藏密码' : '显示密码'"
                        @click="showLoginPwd = !showLoginPwd">
                  <svg class="eye-open" viewBox="0 0 20 20" fill="none">
                    <path d="M1.8 10S4.9 4.6 10 4.6 18.2 10 18.2 10 15.1 15.4 10 15.4 1.8 10 1.8 10Z"
                          stroke="currentColor" stroke-width="1.5"/>
                    <circle cx="10" cy="10" r="2.4" stroke="currentColor" stroke-width="1.5"/>
                  </svg>
                  <svg class="eye-closed" viewBox="0 0 20 20" fill="none">
                    <path d="M2.5 2.5l15 15M8.4 8.6a2.3 2.3 0 0 0 3.2 3.2"
                          stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    <path d="M5.6 5.9C3.3 7.5 1.8 10 1.8 10S4.9 15.4 10 15.4c1.6 0 3-.5 4.2-1.2M14.9 12.6c1.9-1.5 3.3-2.6 3.3-2.6S15.1 4.6 10 4.6c-.6 0-1.2.1-1.7.2"
                          stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                  </svg>
                </button>
              </div>
              <p class="field-error" data-error-for="loginPassword">{{ errors.loginPassword }}</p>
            </div>

            <div class="form-row">
              <label class="checkbox">
                <input type="checkbox" id="rememberMe" v-model="rememberMe">
                <span class="box"></span>
                <span>记住用户名</span>
              </label>
            </div>

            <button type="submit" class="btn-primary" id="loginSubmit"
                    :disabled="loading.login" :class="{ 'is-loading': loading.login }">
              <span class="btn-text">登录</span>
              <span class="btn-loader"></span>
            </button>
          </form>

          <!-- ---------- 注册表单 ---------- -->
          <form class="form" :class="{ 'is-hidden': activeTab !== 'register' }" id="registerForm"
                novalidate @submit.prevent="doRegister">
            <div class="field" :class="{ 'has-error': errors.regUsername }">
              <label for="regUsername">用户名</label>
              <div class="input-wrap">
                <svg class="input-icon" viewBox="0 0 20 20" fill="none">
                  <circle cx="10" cy="6.5" r="3.2" stroke="currentColor" stroke-width="1.6"/>
                  <path d="M3.8 16.2c0-3.1 2.8-5 6.2-5s6.2 1.9 6.2 5"
                        stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                </svg>
                <input type="text" id="regUsername" v-model="regForm.username"
                       placeholder="4-20 位，字母或数字" autocomplete="username"
                       @input="errors.regUsername = ''">
              </div>
              <p class="field-hint">用于登录，注册后不可修改</p>
              <p class="field-error" data-error-for="regUsername">{{ errors.regUsername }}</p>
            </div>

            <div class="field" :class="{ 'has-error': errors.regNickname }">
              <label for="regNickname">昵称 <span class="optional">（选填）</span></label>
              <div class="input-wrap">
                <svg class="input-icon" viewBox="0 0 20 20" fill="none">
                  <path d="M10 3.2l1.9 4.1 4.4.5-3.3 3 .9 4.4L10 13l-3.9 2.2.9-4.4-3.3-3 4.4-.5L10 3.2Z"
                        stroke="currentColor" stroke-width="1.5" stroke-linejoin="round"/>
                </svg>
                <input type="text" id="regNickname" v-model="regForm.nickname"
                       placeholder="想让别人怎么称呼你" autocomplete="nickname"
                       @input="errors.regNickname = ''">
              </div>
              <p class="field-error" data-error-for="regNickname">{{ errors.regNickname }}</p>
            </div>

            <div class="field" :class="{ 'has-error': errors.regPassword }">
              <label for="regPassword">密码</label>
              <div class="input-wrap">
                <svg class="input-icon" viewBox="0 0 20 20" fill="none">
                  <rect x="4" y="8.6" width="12" height="8.4" rx="2.2"
                        stroke="currentColor" stroke-width="1.6"/>
                  <path d="M6.8 8.6V6.6a3.2 3.2 0 0 1 6.4 0v2"
                        stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                </svg>
                <input :type="showRegPwd ? 'text' : 'password'" id="regPassword"
                       v-model="regForm.password"
                       placeholder="6-32 位" autocomplete="new-password"
                       @input="onRegPasswordInput">
                <button type="button" class="eye-btn" :class="{ 'is-on': showRegPwd }"
                        data-eye="regPassword" :aria-label="showRegPwd ? '隐藏密码' : '显示密码'"
                        @click="showRegPwd = !showRegPwd">
                  <svg class="eye-open" viewBox="0 0 20 20" fill="none">
                    <path d="M1.8 10S4.9 4.6 10 4.6 18.2 10 18.2 10 15.1 15.4 10 15.4 1.8 10 1.8 10Z"
                          stroke="currentColor" stroke-width="1.5"/>
                    <circle cx="10" cy="10" r="2.4" stroke="currentColor" stroke-width="1.5"/>
                  </svg>
                  <svg class="eye-closed" viewBox="0 0 20 20" fill="none">
                    <path d="M2.5 2.5l15 15M8.4 8.6a2.3 2.3 0 0 0 3.2 3.2"
                          stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    <path d="M5.6 5.9C3.3 7.5 1.8 10 1.8 10S4.9 15.4 10 15.4c1.6 0 3-.5 4.2-1.2M14.9 12.6c1.9-1.5 3.3-2.6 3.3-2.6S15.1 4.6 10 4.6c-.6 0-1.2.1-1.7.2"
                          stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                  </svg>
                </button>
              </div>
              <div class="strength" id="pwdStrength" :data-level="pwdStrength">
                <i></i><i></i><i></i><i></i>
              </div>
              <p class="field-hint" id="pwdStrengthText">{{ pwdStrengthText }}</p>
              <p class="field-error" data-error-for="regPassword">{{ errors.regPassword }}</p>
            </div>

            <div class="field" :class="{ 'has-error': errors.regConfirm }">
              <label for="regConfirm">确认密码</label>
              <div class="input-wrap">
                <svg class="input-icon" viewBox="0 0 20 20" fill="none">
                  <rect x="4" y="8.6" width="12" height="8.4" rx="2.2"
                        stroke="currentColor" stroke-width="1.6"/>
                  <path d="M6.8 8.6V6.6a3.2 3.2 0 0 1 6.4 0v2"
                        stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
                </svg>
                <input :type="showRegConfirm ? 'text' : 'password'" id="regConfirm"
                       v-model="regForm.confirm"
                       placeholder="请再输入一次密码" autocomplete="new-password"
                       @input="errors.regConfirm = ''">
                <button type="button" class="eye-btn" :class="{ 'is-on': showRegConfirm }"
                        data-eye="regConfirm" :aria-label="showRegConfirm ? '隐藏密码' : '显示密码'"
                        @click="showRegConfirm = !showRegConfirm">
                  <svg class="eye-open" viewBox="0 0 20 20" fill="none">
                    <path d="M1.8 10S4.9 4.6 10 4.6 18.2 10 18.2 10 15.1 15.4 10 15.4 1.8 10 1.8 10Z"
                          stroke="currentColor" stroke-width="1.5"/>
                    <circle cx="10" cy="10" r="2.4" stroke="currentColor" stroke-width="1.5"/>
                  </svg>
                  <svg class="eye-closed" viewBox="0 0 20 20" fill="none">
                    <path d="M2.5 2.5l15 15M8.4 8.6a2.3 2.3 0 0 0 3.2 3.2"
                          stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    <path d="M5.6 5.9C3.3 7.5 1.8 10 1.8 10S4.9 15.4 10 15.4c1.6 0 3-.5 4.2-1.2M14.9 12.6c1.9-1.5 3.3-2.6 3.3-2.6S15.1 4.6 10 4.6c-.6 0-1.2.1-1.7.2"
                          stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                  </svg>
                </button>
              </div>
              <p class="field-error" data-error-for="regConfirm">{{ errors.regConfirm }}</p>
            </div>

            <button type="submit" class="btn-primary" id="registerSubmit"
                    :disabled="loading.register" :class="{ 'is-loading': loading.register }">
              <span class="btn-text">开启厨房</span>
              <span class="btn-loader"></span>
            </button>
          </form>
        </div>

      </main>
    </div>

    <!-- ==================== 登录成功后的信息面板 ==================== -->
    <div class="modal-mask" :class="{ 'is-hidden': !profileVisible }" id="profileMask"
         @click.self="profileVisible = false">
      <div class="modal">
        <div class="modal-head">
          <div class="avatar" id="profileAvatar">{{ avatarChar }}</div>
          <div>
            <h3 id="profileNickname">{{ profileData.nickname || profileData.username || '—' }}</h3>
            <p id="profileUsername">{{ profileData.username ? '@' + profileData.username : '—' }}</p>
          </div>
        </div>

        <dl class="modal-body">
          <div>
            <dt>用户 ID</dt>
            <dd id="profileUserId">{{ profileData.userId || '—' }}</dd>
          </div>
          <div>
            <dt>Token 类型</dt>
            <dd id="profileTokenType">{{ profileData.tokenType || 'Bearer' }}</dd>
          </div>
          <div class="token-row">
            <dt>Access Token</dt>
            <dd><code id="profileToken">{{ profileData.token || '—' }}</code></dd>
          </div>
        </dl>

        <div class="modal-actions">
          <button type="button" class="btn-ghost" id="copyTokenBtn" @click="copyToken">复制 Token</button>
          <button type="button" class="btn-primary btn-sm" id="testProfileBtn"
                  :disabled="testing" @click="testProfile">
            {{ testing ? '请求中…' : '调一次受保护接口' }}
          </button>
        </div>

        <pre class="api-result" :class="{ 'is-hidden': !apiResult }" id="apiResult"
             v-html="apiResult"></pre>

        <button type="button" class="modal-close" id="logoutBtn" @click="logout">退出登录</button>
      </div>
    </div>

  </div>
</template>

<script setup>
/* ============================================================
   登录 / 注册页（Vue 版）
   对应原生版：index.html + js/auth.js + js/floaters.js + js/ui.js
   ============================================================ */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AuthApi } from '../api.js'
import { STORAGE_KEYS } from '../config.js'
import { saveLogin, authState } from '../store/auth.js'
import { buildFloaters } from '../lib/floaters.js'
import { Validate, evaluatePasswordStrength, STRENGTH_TEXTS, copyText, highlightJson } from '../lib/utils.js'
import { toastSuccess, toastError } from '../store/toast.js'

const route = useRoute()
const router = useRouter()

/* ---------------- 顶部装饰 ---------------- */
const floaters = buildFloaters()

/* ---------------- Tab ---------------- */
// hash 指定优先，否则回到登录（与原生版一致）
const initialTab = (location.hash || '').replace('#', '') === 'register' ? 'register' : 'login'
const activeTab = ref(initialTab)

function switchTab(target) {
  activeTab.value = target
  // 切 tab 时清掉错误，避免残留上一个表单的红字
  Object.keys(errors).forEach(k => { errors[k] = '' })
  // 同步到地址栏，刷新后仍停在当前 Tab
  history.replaceState(null, '', '#' + target)
}

/* ---------------- 表单数据与错误 ---------------- */
const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', nickname: '', password: '', confirm: '' })

const errors = reactive({
  loginUsername: '', loginPassword: '',
  regUsername: '', regNickname: '', regPassword: '', regConfirm: ''
})

const loading = reactive({ login: false, register: false })

/* 密码显隐（对应原生版的 .eye-btn） */
const showLoginPwd = ref(false)
const showRegPwd = ref(false)
const showRegConfirm = ref(false)

/* 记住用户名（只存用户名，绝不存密码） */
const rememberMe = ref(true)

/* 密码强度 */
const pwdStrength = ref(0)
const pwdStrengthText = computed(() => {
  if (!regForm.password) return STRENGTH_TEXTS[0]
  return STRENGTH_TEXTS[pwdStrength.value]
})

function onRegPasswordInput() {
  errors.regPassword = ''
  pwdStrength.value = evaluatePasswordStrength(regForm.password)
}

/* ---------------- 登录 ---------------- */
async function doLogin() {
  const username = loginForm.username.trim()
  const password = loginForm.password

  errors.loginUsername = Validate.username(username) || ''
  errors.loginPassword = Validate.password(password) || ''
  if (errors.loginUsername || errors.loginPassword) return

  loading.login = true
  try {
    const data = await AuthApi.login(username, password)

    // 保存登录态（saveLogin 内部会把 userId 强制转成字符串）
    saveLogin(data)

    // 「记住用户名」只存用户名，绝不存密码
    if (rememberMe.value) {
      localStorage.setItem(STORAGE_KEYS.REMEMBERED_USER, username)
    } else {
      localStorage.removeItem(STORAGE_KEYS.REMEMBERED_USER)
    }

    toastSuccess('登录成功，正在进入厨房')
    // 支持 ?redirect= 回跳（原生版是直接 href 跳 kitchen.html）
    const redirect = route.query.redirect
    router.replace(typeof redirect === 'string' && redirect ? redirect : '/kitchen')
  } catch (err) {
    toastError(err.message || '登录失败')
    errors.loginPassword = err.message || '登录失败'
  } finally {
    loading.login = false
  }
}

/* ---------------- 注册 ---------------- */
async function doRegister() {
  const username = regForm.username.trim()
  const nickname = regForm.nickname.trim()
  const password = regForm.password
  const confirmValue = regForm.confirm

  errors.regUsername = Validate.username(username) || ''
  errors.regNickname = Validate.nickname(nickname) || ''
  errors.regPassword = Validate.password(password) || ''
  errors.regConfirm = Validate.confirm(password, confirmValue) || ''
  if (errors.regUsername || errors.regNickname || errors.regPassword || errors.regConfirm) return

  loading.register = true
  try {
    await AuthApi.register(username, password, nickname)
    toastSuccess('注册成功，请使用新账号登录')

    // 清空注册表单
    regForm.username = ''
    regForm.nickname = ''
    regForm.password = ''
    regForm.confirm = ''
    pwdStrength.value = 0

    // 按约定：切回登录页 + 自动填充用户名
    // 也要落一份「记住用户名」，否则刷新后自动填充就丢了
    localStorage.setItem(STORAGE_KEYS.REMEMBERED_USER, username)
    rememberMe.value = true

    switchTab('login')
    loginForm.username = username
    loginForm.password = ''
  } catch (err) {
    // 「用户名已存在」这类业务错误直接显示在用户名输入框下
    toastError(err.message || '注册失败')
    errors.regUsername = err.message || '注册失败'
  } finally {
    loading.register = false
  }
}

/* ---------------- 信息面板（登录链路的调试入口） ---------------- */
const profileVisible = ref(false)
const profileData = reactive({ nickname: '', username: '', userId: '', tokenType: '', token: '' })
const testing = ref(false)
const apiResult = ref('')

const avatarChar = computed(() => {
  const n = profileData.nickname || profileData.username || '?'
  return n.charAt(0).toUpperCase()
})

function showProfile(user) {
  profileData.nickname = user.nickname || ''
  profileData.username = user.username || ''
  profileData.userId = user.userId != null ? String(user.userId) : ''
  profileData.tokenType = user.tokenType || 'Bearer'
  profileData.token = user.token || ''
  apiResult.value = ''
  profileVisible.value = true
}

async function copyToken() {
  const ok = await copyText(localStorage.getItem(STORAGE_KEYS.TOKEN) || '')
  ok ? toastSuccess('Token 已复制到剪贴板') : toastError('复制失败，请手动选择')
}

/** 调一次受保护接口：直观验证「网关验签 + 身份透传」整条链路 */
async function testProfile() {
  testing.value = true
  try {
    const data = await AuthApi.profile()
    apiResult.value = highlightJson(JSON.stringify(data, null, 2))
    toastSuccess('接口调用成功')
  } catch (err) {
    apiResult.value = highlightJson(JSON.stringify({ error: err.message, code: err.code }, null, 2))
    toastError(err.message || '调用失败')
  } finally {
    testing.value = false
  }
}

function logout() {
  authState.token = ''
  authState.userId = ''
  authState.username = ''
  authState.nickname = ''
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  profileVisible.value = false
  toastSuccess('已退出登录')
}

/* ---------------- 启动 ---------------- */
onMounted(() => {
  // 回填「记住用户名」
  const remembered = localStorage.getItem(STORAGE_KEYS.REMEMBERED_USER)
  if (remembered) {
    loginForm.username = remembered
  }

  // 上次登录过就把信息面板弹出来（原生版行为：调试入口常驻）
  const saved = localStorage.getItem(STORAGE_KEYS.USER_INFO)
  if (saved) {
    try {
      showProfile(JSON.parse(saved))
    } catch (e) {
      localStorage.removeItem(STORAGE_KEYS.USER_INFO)
    }
  }

  // token 过期被踢回来时的提示
  if (route.query.expired === '1') {
    toastError('登录已过期，请重新登录')
  }
})
</script>
