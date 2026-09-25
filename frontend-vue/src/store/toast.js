/* ============================================================
   通用 UI 工具：Toast（Vue 版，替代原生版 js/ui.js 的命令式调用）

   原生版是 document.createElement 造 DOM 再 appendChild；
   Vue 版改成维护一个响应式数组，由 App.vue 里的容器渲染。
   好处：不再直接操作 DOM，且能自动跟随组件生命周期。

   【类名必须和 CSS 对齐（这里踩过）】
   CSS 里是 .toast-wrap 容器 + .toast.success / .toast.error 这种
   「同一元素挂两个类」的写法，**不是** .toast-host / .toast-success。
   写错的话样式一条都不生效 —— 症状是提示框不在右下角、图标全部同色，
   而且不会报任何错。改类名前先对一遍 CSS 选择器。
   ============================================================ */

import { reactive } from 'vue'

export const toastState = reactive({
  items: []
})

let seed = 0

/**
 * 弹一条提示。
 * @param {string} message 文案
 * @param {'success'|'error'|'info'} type 类型（决定配色与图标）
 * @param {number} duration 毫秒，0 表示不自动关闭
 */
export function toast(message, type = 'info', duration = 2600) {
  const id = ++seed
  toastState.items.push({ id, message, type, out: false })

  if (duration > 0) {
    setTimeout(() => dismissToast(id), duration)
  }
  return id
}

/**
 * 关闭一条提示。
 * 先置 out=true 播退场动画（CSS 的 .toast.is-out），260ms 后再从数组移除 ——
 * 直接 splice 会让它"啪"地消失，和原生版的观感不一致。
 */
export function dismissToast(id) {
  const t = toastState.items.find(x => x.id === id)
  if (!t || t.out) return
  t.out = true
  setTimeout(() => {
    const i = toastState.items.findIndex(x => x.id === id)
    if (i !== -1) toastState.items.splice(i, 1)
  }, 260)
}

export const toastSuccess = (m, d) => toast(m, 'success', d)
/** 错误多留一会儿，够看清（与原生版一致：3400ms） */
export const toastError = (m, d) => toast(m, 'error', d || 3400)
export const toastInfo = (m, d) => toast(m, 'info', d)
