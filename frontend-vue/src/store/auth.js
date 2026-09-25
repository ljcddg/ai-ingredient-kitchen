/* ============================================================
   登录态与用户信息（Vue 版 reactive store，替代原生版的散落 localStorage 读写）
   ============================================================ */

import { reactive, computed } from 'vue'
import { STORAGE_KEYS } from '../config.js'

/**
 * 【关键约定：所有 ID 一律保持字符串】
 *
 * 后端雪花 ID 是 19 位数字，超过 JS Number 的安全整数范围（2^53）。
 * 一旦被 Number() 转换，低位会丢，症状是：
 *   「接口单独测正常，页面就是打不开」——因为 ID 已经变了。
 *
 * 所以这里读写都走 String()，且本地存储里的 ID 原样保留字符串形态。
 */
export const authState = reactive({
  token: localStorage.getItem(STORAGE_KEYS.TOKEN) || '',
  userId: '',
  username: '',
  nickname: ''
})

// 启动时从 localStorage 恢复用户信息（避免刷新后顶栏空一下）
;(function restoreUserInfo() {
  const raw = localStorage.getItem(STORAGE_KEYS.USER_INFO)
  if (!raw) return
  try {
    const info = JSON.parse(raw)
    // 双保险：无论存进来时是什么类型，都强制成字符串
    authState.userId = info.userId != null ? String(info.userId) : ''
    authState.username = info.username || ''
    authState.nickname = info.nickname || ''
  } catch (e) {
    localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  }
})()

export const isLoggedIn = computed(() => !!authState.token)

/** 登录成功后落盘 */
export function saveLogin(data) {
  authState.token = data.token || ''
  authState.userId = data.userId != null ? String(data.userId) : ''
  authState.username = data.username || ''
  authState.nickname = data.nickname || ''

  localStorage.setItem(STORAGE_KEYS.TOKEN, authState.token)
  localStorage.setItem(STORAGE_KEYS.USER_INFO, JSON.stringify({
    userId: authState.userId,
    username: authState.username,
    nickname: authState.nickname
  }))
}

/** 退出登录 */
export function clearLogin() {
  authState.token = ''
  authState.userId = ''
  authState.username = ''
  authState.nickname = ''
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
}

/** 顶栏显示名：优先昵称，退回用户名 */
export const displayName = computed(() => authState.nickname || authState.username || '厨友')

/**
 * 判断某条内容是不是当前用户创建的。
 *
 * 【必须用字符串比较】不能用 Number() 转换 —— 雪花 ID 会丢精度，
 * 本人会被判成"不是本人"，于是看不到自己的发布/撤回按钮。
 */
export function isOwner(creatorId) {
  if (creatorId == null || !authState.userId) return false
  return String(creatorId) === String(authState.userId)
}
