<template>
  <!--
    应用根组件。
    Toast 容器是全局单例（原生版是命令式造 DOM，这里改为组件渲染）。

    【类名必须和 CSS 完全对齐（这里踩过）】
    CSS 用的是 .toast-wrap 容器 + .toast.success 这种「同一元素两个类」的写法。
    之前写成 .toast-host / .toast-success，结果样式一条都没命中：
    提示框不固定在右下角、三种类型的图标颜色全一样，而且不报错。
  -->
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>

  <!-- 固定右下角（与原生版位置一致，避免盖住任务区标题） -->
  <div class="toast-wrap" aria-live="polite" role="status">
    <div
      v-for="t in toastState.items"
      :key="t.id"
      class="toast"
      :class="[t.type, { 'is-out': t.out }]"
      @click="dismissToast(t.id)"
    >
      <span class="toast-icon" v-html="ICONS[t.type] || ICONS.info"></span>
      <span>{{ t.message }}</span>
    </div>
  </div>
</template>

<script setup>
import { toastState, dismissToast } from './store/toast.js'

/*
 * 成功用 SVG 而不是 ✓ 字符，两个原因（沿用原生版的判断）：
 *   1. U+2713 落在「杂项符号 / Dingbats」区段（U+2600–U+27BF），
 *      会被「页面不出现 emoji」这类检查判成违规，而它其实只是个勾；
 *   2. 字符在不同字体下大小和粗细都不一样，SVG 可控。
 * 尺寸写在 SVG 属性上，不靠 CSS —— 登录页与内页引的样式不同，写属性最省事。
 */
const ICONS = {
  success: '<svg width="11" height="11" viewBox="0 0 12 12" fill="none"'
    + ' stroke="currentColor" stroke-width="2.4" stroke-linecap="round"'
    + ' stroke-linejoin="round" aria-hidden="true">'
    + '<path d="M2 6.2 4.6 8.8 10 3.2"/></svg>',
  error: '!',
  info: 'i'
}
</script>
