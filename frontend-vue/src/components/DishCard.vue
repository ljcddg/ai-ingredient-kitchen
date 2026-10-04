<template>
  <!--
    菜品卡片（「我的」页用，菜品列表项）。
    对应原生版 profile.js 的 dishCardInner()。
    抽成组件是因为「我的菜品」和「我的收藏」两处结构完全一样，
    只有 extraBits（如「收藏于 xx」）不同。
  -->
  <router-link class="dish-card" :to="'/dish/' + dish.id">
    <div class="dish-card-main">
      <div class="dish-card-name">
        <span class="dish-card-title">{{ dish.name || '未命名' }}</span>
        <!-- 草稿标在最前面：不标的话用户分不清哪些别人也能看到 -->
        <span v-if="dish.status === 0" class="tag warn">草稿</span>
        <!-- 冷启动状态：已发布的菜要么还在推荐池里接受验证，要么已经晋级上榜。
             作者最关心这个 —— 他需要知道自己的新菜有没有被推出去 -->
        <span v-if="dish.status === 1" class="tag" :class="{ accent: dish.rankLevel === 2 }">
          {{ dish.rankLevel === 2 ? '已上榜' : '推荐中' }}
        </span>
        <span v-if="SOURCE_TEXT[dish.source]" class="tag">{{ SOURCE_TEXT[dish.source] }}</span>
        <!-- 营养来源必须标出来（说明文档 P2-2）：AI 估算的只能当参考，
             按成分表算的才是可核对的。两者看起来一样、来源完全不同，不标就是误导 -->
        <span v-if="NUTRITION_SOURCE_TEXT[dish.nutritionSource]" class="tag"
              :class="{ warn: dish.nutritionSource === 1 }">
          {{ NUTRITION_SOURCE_TEXT[dish.nutritionSource] }}
        </span>
      </div>
      <div class="dish-card-sub">{{ subText }}</div>
    </div>
    <div class="dish-card-right">
      <div class="dish-card-stat">
        <b>{{ ratingValue }}</b><span>{{ ratingLabel }}</span>
      </div>
      <div class="dish-card-stat">
        <b>{{ dish.collectCount || 0 }}</b><span>收藏</span>
      </div>
    </div>
  </router-link>
</template>

<script setup>
import { computed } from 'vue'
import { DIFFICULTY_TEXT, SOURCE_TEXT, NUTRITION_SOURCE_TEXT } from '../lib/constants.js'
import { authState } from '../store/auth.js'

const props = defineProps({
  dish: { type: Object, required: true },
  /** 追加到副标题末尾的片段，如 ['收藏于 2026-09-15'] */
  extraBits: { type: Array, default: () => [] }
})

/**
 * 是不是我自己的菜。
 *
 * 冷启动数据（推流了多少次、被看了多少次）只对作者有意义 ——
 * 别人看到这些数字既看不懂，也显得像在窥探。所以只在自己的菜上展示。
 */
const isMine = computed(() =>
  props.dish.creatorId != null &&
  String(props.dish.creatorId) === String(authState.userId || ''))

const subText = computed(() => {
  const d = props.dish
  const bits = []
  const minutes = (d.prepTimeMin || 0) + (d.cookTimeMin || 0)
  if (minutes) bits.push(minutes + ' 分钟')
  if (d.difficulty) bits.push(DIFFICULTY_TEXT[d.difficulty] || '')
  if (d.energyKcal) bits.push(d.energyKcal + ' 千卡/份')
  if (isMine.value && d.status === 1) {
    bits.push('推流 ' + (d.pushCount || 0) + ' 次')
    bits.push('查看 ' + (d.viewCount || 0) + ' 次')
  }
  props.extraBits.forEach(b => bits.push(b))
  return bits.filter(Boolean).join(' · ')
})

// 没人评价时显示「—」而不是「0.0」—— 后者像"很难吃"，实际是"还没人评"
const ratingValue = computed(() =>
  props.dish.ratingCount > 0 ? Number(props.dish.ratingAvg).toFixed(1) : '—')

const ratingLabel = computed(() =>
  props.dish.ratingCount > 0 ? props.dish.ratingCount + ' 人评' : '还没人评')
</script>
