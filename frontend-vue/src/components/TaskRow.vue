<template>
  <li class="task" :data-task-id="task.taskId">
    <div class="task-main">
      <div class="task-name">
        <span class="tag" :class="badge.cls">{{ badge.text }}</span>
        <span class="task-title">{{ task.dishName || task.summary || '未命名' }}</span>
      </div>
      <div class="task-sub">{{ sub }}</div>
    </div>

    <div class="task-right">
      <!-- 排队中：显示全局位置，并允许在自己的任务之间调序 -->
      <template v-if="task.status === 0">
        <span class="task-pos">第 {{ task.position }} 位</span>
        <span class="task-order">
          <button v-if="up" class="icon-btn" data-act="up" type="button"
                  title="往前排" aria-label="往前排" @click="$emit('action', 'up', task.taskId)">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M6 10V2M2.6 5.4 6 2l3.4 3.4"/>
            </svg>
          </button>
          <button v-if="down" class="icon-btn" data-act="down" type="button"
                  title="往后排" aria-label="往后排" @click="$emit('action', 'down', task.taskId)">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6"
                 stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M6 2v8M2.6 6.6 6 10l3.4-3.4"/>
            </svg>
          </button>
        </span>
        <button class="ghost-btn" data-act="cancel" type="button"
                @click="$emit('action', 'cancel', task.taskId)">取消</button>
      </template>

      <template v-else-if="task.status === 1">
        <span class="task-pos is-running">正在做，请稍等</span>
        <button class="ghost-btn" data-act="cancel" type="button"
                @click="$emit('action', 'cancel', task.taskId)">取消</button>
      </template>

      <!-- 成功：既能进详情，也能把这条记录从列表里清掉。
           已有主按钮，移除用图标款，避免同重量按钮抢焦点 -->
      <template v-else-if="task.status === 2 && task.dishId">
        <router-link class="secondary-btn" :to="'/dish/' + task.dishId"
                     @click="$emit('view', task.taskId)">查看详情</router-link>
        <button class="icon-btn" data-act="remove" type="button"
                title="移除这条记录" aria-label="移除这条记录"
                @click="$emit('action', 'remove', task.taskId)">
          <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8"
               stroke-linecap="round" aria-hidden="true"><path d="M3 3 9 9M9 3 3 9"/></svg>
        </button>
      </template>

      <!-- 已结束但没生成出菜：失败 / 已取消 / 已中断。
           不给按钮的话，它们会永远堆在列表里，用户只能干瞪眼 -->
      <template v-else>
        <button class="ghost-btn" data-act="remove" type="button"
                @click="$emit('action', 'remove', task.taskId)">移除</button>
      </template>
    </div>
  </li>
</template>

<script setup>
/* ============================================================
   任务行（我的 AI 做菜任务）

   抽成组件的原因：任务列表现在分「进行中（常驻展开）」和
   「已结束（折叠）」两段，两段渲染的是同一种行。
   抽出来只写一份，将来加状态也只改这一个文件。
   ============================================================ */
import { computed } from 'vue'
import { humanDuration } from '../lib/constants.js'

const props = defineProps({
  task: { type: Object, required: true },
  /** 能否往前 / 往后调序 —— 由父组件按「我的排队位置」算好传进来 */
  up: { type: Boolean, default: false },
  down: { type: Boolean, default: false }
})

/**
 * action —— 取消 / 移除 / 调序
 * view   —— 点开了详情页。父组件据此把这条从「刚做好」挪进折叠区，
 *           所以「查看过」这件事必须在点击的瞬间告诉父组件。
 */
defineEmits(['action', 'view'])

/** 状态码 → 标签样式与文案（与后端 AiTaskStatus 一一对应） */
const TASK_BADGE = {
  0: { cls: 'warn', text: '排队中' },
  1: { cls: 'accent', text: '生成中' },
  2: { cls: 'ok', text: '已完成' },
  3: { cls: '', text: '失败' },
  4: { cls: '', text: '已取消' },
  5: { cls: '', text: '已中断' }
}

const badge = computed(() =>
  TASK_BADGE[props.task.status] || { cls: '', text: props.task.statusText || '未知' })

/** 副标题：终态说耗时，进行中说提交时间 */
const sub = computed(() => {
  const t = props.task
  if (t.status === 2) {
    return (t.costMs ? 'AI 用了 ' + humanDuration(t.costMs) : '已完成')
      + (t.queueWaitMs > 500 ? '，排队等了 ' + humanDuration(t.queueWaitMs) : '')
  }
  if (t.status === 3) return t.errorMsg || '生成失败'
  if (t.status === 4 || t.status === 5) return t.statusText || ''
  return t.createTime ? '提交于 ' + t.createTime : ''
})
</script>
