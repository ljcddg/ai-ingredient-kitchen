<template>
  <div class="page-recommend">
    <FxBg />
    <TopBar current="recommend" />

    <!--
      两栏工作台：左选食材、右出推荐。
      和找菜页（.page-kitchen .workbench）用同一套栅格 ——
      两个页面是同一件事的两个阶段（找菜 vs 推菜），布局不该长得像两家人。
    -->
    <main class="workbench">
      <!-- ==================== 左栏：选食材 ==================== -->
      <section class="panel picker" aria-labelledby="pickerHead">
        <h1 class="sr-only" id="pickerHead">选择你手边的食材</h1>

        <div class="picker-scroll">
          <div class="field">
            <div class="field-head">
              <span class="field-label">食材</span>
              <span class="field-hint">点选，或直接输入</span>
            </div>

            <!-- 88 种食材平铺只能一路滚，所以给搜索。输入即过滤，清空即还原 -->
            <div class="ing-search">
              <svg class="ing-search-icon" viewBox="0 0 16 16" fill="none"
                   stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
                <circle cx="7" cy="7" r="4.6"/>
                <path d="M10.4 10.4 14 14"/>
              </svg>
              <label class="sr-only" for="ingSearch">按名称搜索食材</label>
              <input class="ing-search-input" id="ingSearch" type="search" autocomplete="off"
                     placeholder="搜食材，例如「茄」「鱼」"
                     v-model="ingKeyword" @keydown="onIngSearchKeydown">
              <button class="ing-search-clear" id="ingSearchClear" type="button"
                      :hidden="!ingKeyword" aria-label="清空搜索" @click="clearIngSearch">
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
                  <path d="M3 3l6 6M9 3l-6 6"/>
                </svg>
              </button>
            </div>
            <p class="ing-search-miss" id="ingSearchMiss" :hidden="!searchMiss">{{ searchMiss }}</p>

            <div id="ingGroups">
              <div v-for="g in visibleGroups" :key="g.name" class="ing-group">
                <span class="ing-group-name">
                  <i class="group-tone" aria-hidden="true"
                     :style="{ background: GROUP_TONE[g.name] || 'var(--ink-3)' }"></i>{{ g.name }} {{ g.items.length }}
                </span>
                <div class="ing-grid">
                  <button v-for="it in g.items" :key="it.id" type="button" class="ing"
                          :data-id="it.id" :aria-pressed="String(selectedIds.includes(it.id))"
                          @click="toggleIngredient(it.id)">
                    <svg class="ing-tick" viewBox="0 0 12 12" fill="none" stroke="currentColor"
                         stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                      <path d="M2 6.2 4.6 8.8 10 3.2"/>
                    </svg>
                    <span>{{ it.name }}</span>
                  </button>
                </div>
              </div>
            </div>

            <!-- 自由输入：列表里没有的食材也能参与检索（走 ingredientNames 那条召回路径） -->
            <div class="free-ing">
              <label class="sr-only" for="freeIngInput">输入列表里没有的食材</label>
              <div class="free-ing-row">
                <input class="input" id="freeIngInput" type="text" autocomplete="off"
                       placeholder="列表里没有？直接输入，如「松露」" maxlength="20"
                       v-model="freeInput" @input="onFreeInput" @keydown="onFreeKeydown">
                <button class="secondary-btn" id="addFreeIngBtn" type="button"
                        @click="addFreeIngredient(freeInput)">添加</button>
              </div>
              <div class="suggest" id="freeIngSuggest" :hidden="!suggestVisible" role="listbox"
                   aria-label="食材联想">
                <button v-for="(h, i) in suggests" :key="h.id" type="button"
                        class="suggest-item" role="option" :data-name="h.name"
                        :class="{ 'is-active': i === suggestIndex }"
                        @click="addFreeIngredient(h.name)">
                  <span>{{ h.name }}</span>
                  <span class="suggest-group">{{ h.group }}</span>
                </button>
              </div>
            </div>

            <div class="picked" id="pickedBar">
              <button v-for="id in sortedSelected" :key="'id' + id" type="button"
                      class="picked-chip" data-kind="id" :data-value="id" title="点击移除"
                      :aria-label="'移除' + nameOfIngredient(id)" @click="toggleIngredient(id)">
                <span>{{ nameOfIngredient(id) }}</span>
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" aria-hidden="true"><path d="M3 3 9 9M9 3 3 9"/></svg>
              </button>
              <button v-for="name in freeIngredients" :key="'free' + name" type="button"
                      class="picked-chip is-free" data-kind="free" :data-value="name" title="点击移除"
                      :aria-label="'移除' + name" @click="removeFreeIngredient(name)">
                <span>{{ name }}</span>
                <span class="picked-tag">自填</span>
                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" aria-hidden="true"><path d="M3 3 9 9M9 3 3 9"/></svg>
              </button>
              <span v-if="!selectedIds.length && !freeIngredients.length" class="picked-empty">
                还没选食材
              </span>
            </div>
          </div>

          <!-- 找菜模式：严格只推现在就能做的，宽松允许缺几样 -->
          <div class="field">
            <div class="field-head">
              <span class="field-label">缺料容忍</span>
              <span class="field-hint">严格只推现在就能做的</span>
            </div>
            <div class="segments" id="modeSegs">
              <button type="button" class="seg" data-mode="strict"
                      :aria-pressed="String(searchMode === 'strict')"
                      @click="searchMode = 'strict'">严格</button>
              <button type="button" class="seg" data-mode="loose"
                      :aria-pressed="String(searchMode === 'loose')"
                      @click="searchMode = 'loose'">宽松</button>
            </div>
            <div v-if="searchMode === 'loose'" class="chips" style="margin-top:8px">
              <span class="field-hint">最多允许缺</span>
              <button v-for="n in [1, 2, 3]" :key="'mm' + n" type="button" class="chip"
                      :aria-pressed="String(maxMissing === n)"
                      @click="maxMissing = n">{{ n }} 样</button>
            </div>
          </div>
        </div>

        <!-- 提交按钮固定在面板底部：食材列表很长，滚到底才看见按钮等于没有按钮 -->
        <div class="picker-actions">
          <p class="picked-count">
            <template v-if="totalPicked">已选 {{ totalPicked }} 样食材</template>
            <template v-else>不选也行，直接看看有什么新菜</template>
          </p>
          <div class="picker-btns">
            <button class="secondary-btn" id="clearBtn" type="button"
                    :disabled="!totalPicked" @click="clearAll">清空</button>
            <button class="primary-btn" id="recommendBtn" type="button" @click="reload">
              {{ totalPicked ? '按这些食材推荐' : '随便看看' }}
            </button>
          </div>
        </div>
      </section>

      <!-- ==================== 右栏：推荐结果 ==================== -->
      <section class="results" aria-labelledby="resultsHead">
        <div class="page-head">
          <div>
            <h2 class="page-title" id="resultsHead">推荐</h2>
            <p class="page-sub" id="recSub">{{ subtitleText }}</p>
          </div>
        </div>

        <div id="panel" role="region" aria-live="polite">
          <div v-if="view === 'skeleton'" class="skeleton">
            <div class="sk-line sk-title"></div>
            <div class="sk-line"></div>
            <div class="sk-line"></div>
            <div class="sk-line sk-short"></div>
          </div>

          <div v-else-if="view === 'error'" class="err-box">
            <p class="err-title">没能加载推荐</p>
            <p class="err-text">{{ errorMessage }}</p>
            <button class="secondary-btn" type="button" id="retryBtn" @click="load(true)">再试一次</button>
          </div>

          <div v-else-if="view === 'empty'" class="empty">
            <p class="empty-title">暂时没有新菜了</p>
            <p class="empty-text">
              24 小时内推给你的菜都看过了。换个食材组合，或者过一会儿再来看看。
            </p>
          </div>

          <template v-else>
            <ul class="rec-list">
              <li v-for="item in items" :key="item.id" class="rec-row">
                <div class="rank-item rec-main" @click="open(item)">
                  <div class="rank-main">
                    <div class="rank-name">{{ item.name }}</div>
                    <p v-if="item.summary" class="rank-summary">{{ item.summary }}</p>
                    <div class="rank-meta">
                      <!-- 匹配角标：让用户看懂这道菜为什么出现在面前 -->
                      <span v-if="MATCH_TEXT[item.matchType]"
                            class="tag" :class="{ accent: item.matchType === 'full' }">
                        {{ MATCH_TEXT[item.matchType] }}
                      </span>
                      <span class="tag" :class="{ accent: item.rankLevel === 2 }">
                        {{ item.rankLevel === 2 ? '已上榜' : '推荐中' }}
                      </span>
                      <span v-if="item.ratingCount > 0" class="tag">
                        {{ Number(item.ratingAvg || 0).toFixed(1) }} 分 · {{ item.ratingCount }} 人
                      </span>
                      <span v-else class="tag">还没人评价</span>
                      <span v-if="item.cookTimeMin" class="tag">约 {{ item.cookTimeMin }} 分钟</span>
                    </div>
                  </div>
                </div>
                <button
                  class="rec-like"
                  type="button"
                  :class="{ on: liked.has(item.id) }"
                  :aria-pressed="String(liked.has(item.id))"
                  @click="like(item)"
                >{{ item.likeCount || 0 }} 赞</button>
              </li>
            </ul>

            <div class="rec-more">
              <button class="secondary-btn" type="button" id="moreBtn" @click="nextBatch">换一批</button>
            </div>
          </template>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   推荐页（冷启动）
   ------------------------------------------------------------
   和找菜页的区别只在"结果从哪来"：找菜是用户主动检索，
   这里是后端按「全匹配 → 部分匹配 → 随机补齐」三级挑菜推给他。

   三件事：
     1. 选食材 → 传给后端，先把食材选好再出推荐（左选右出）
     2. 点进详情 → 上报查看（查看率是晋级的核心指标，必须点进去才算）
     3. 点赞 → 上报互动

   关于「换一批」而不是分页：24 小时内推过的菜会被后端自动排除，
   所以再取一批拿到的本来就是新菜，不需要 offset。
   ============================================================ */
import { ref, computed, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { DishApi } from '../api.js'
import {
  INGREDIENT_GROUPS, GROUP_TONE, nameOfIngredient,
  allLocalIngredients, findIngredientIdByName
} from '../lib/ingredients.js'

const PAGE_SIZE = 10

/** 与后端 es.recall.max-ingredients 保持一致，前端先拦一道 */
const MAX_INGREDIENTS = 20

/** 后端回传的 matchType 对应的中文角标 */
const MATCH_TEXT = {
  full: '完全匹配',
  partial: '部分匹配',
  random: '为你推荐'
}

const router = useRouter()

// ---------- 食材选择状态 ----------
const selectedIds = ref([])          // 主数据食材（走 ingredientIds 召回）
const freeIngredients = ref([])      // 表外食材（走 ingredientNames 召回）
const ingKeyword = ref('')
const freeInput = ref('')
const searchMode = ref('strict')     // strict | loose
const maxMissing = ref(2)

// ---------- 结果状态 ----------
const items = ref([])
const page = ref(1)
const view = ref('skeleton')         // skeleton | error | empty | list
const errorMessage = ref('')
const subtitle = ref('')
/** 本次会话里点过赞的菜，只用于按钮置灰，刷新页面后会重置 */
const liked = reactive(new Set())
/** 上一次「按什么食材推荐」的口径，用来在副标题里说明这批菜是按谁找的 */
const lastQuery = ref([])

/* ---------- 食材选择：搜索 / 联想 / 选中 ---------- */

const visibleGroups = computed(() => {
  const kw = ingKeyword.value.trim().toLowerCase()
  if (!kw) return INGREDIENT_GROUPS
  return INGREDIENT_GROUPS
    .map(g => ({ ...g, items: g.items.filter(i => i.name.toLowerCase().includes(kw)) }))
    .filter(g => g.items.length)
})

const searchMiss = computed(() => {
  const kw = ingKeyword.value.trim()
  if (!kw || visibleGroups.value.length) return ''
  return `食材表里没有「${kw}」—— 可以在下面直接输入，AI 照样能用上它`
})

/** 已选按分组顺序展示，避免点选顺序不同导致摘要栏跳来跳去 */
const sortedSelected = computed(() => {
  const order = INGREDIENT_GROUPS.flatMap(g => g.items.map(i => i.id))
  return [...selectedIds.value].sort((a, b) => order.indexOf(a) - order.indexOf(b))
})

const totalPicked = computed(() => selectedIds.value.length + freeIngredients.value.length)

function toggleIngredient(id) {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) {
    selectedIds.value.splice(i, 1)
  } else {
    if (totalPicked.value >= MAX_INGREDIENTS) return
    selectedIds.value.push(id)
  }
}

function clearIngSearch() {
  ingKeyword.value = ''
}

/** Esc 清空搜索框，和原生版一致 */
function onIngSearchKeydown(e) {
  if (e.key === 'Escape') clearIngSearch()
}

/* ---------- 表外食材（自由输入 + 联想） ---------- */

const suggests = ref([])
const suggestIndex = ref(0)
const suggestVisible = computed(() => suggests.value.length > 0)

function onFreeInput() {
  const kw = freeInput.value.trim().toLowerCase()
  if (!kw) {
    suggests.value = []
    return
  }
  suggests.value = allLocalIngredients()
    .filter(i => i.name.toLowerCase().includes(kw))
    .slice(0, 6)
  suggestIndex.value = 0
}

/** 自由输入框回车：有联想就选第一个，否则当成表外食材加入 */
function onFreeKeydown(e) {
  if (e.key === 'Enter') {
    e.preventDefault()
    if (suggestVisible.value) addFreeIngredient(suggests.value[suggestIndex.value].name)
    else addFreeIngredient(freeInput.value)
  } else if (e.key === 'Escape') {
    freeInput.value = ''
    suggests.value = []
  }
}

/**
 * 加入一样食材。名字能在本地表里对上就转成 ID（走主数据召回，
 * 精度更高），对不上才当表外食材 —— 否则「西红柿」和「番茄」
 * 会被当成两样，检索结果平白少一半。
 */
function addFreeIngredient(raw) {
  const name = String(raw || '').trim()
  if (!name) return
  const id = findIngredientIdByName(name)
  if (id != null) {
    if (!selectedIds.value.includes(id)) {
      if (totalPicked.value >= MAX_INGREDIENTS) return
      selectedIds.value.push(id)
    }
  } else if (!freeIngredients.value.includes(name)) {
    if (totalPicked.value >= MAX_INGREDIENTS) return
    freeIngredients.value.push(name)
  }
  freeInput.value = ''
  suggests.value = []
}

function removeFreeIngredient(name) {
  const i = freeIngredients.value.indexOf(name)
  if (i >= 0) freeIngredients.value.splice(i, 1)
}

function clearAll() {
  selectedIds.value = []
  freeIngredients.value = []
}

/* ---------- 拉推荐 ---------- */

const subtitleText = computed(() => {
  if (view.value !== 'list' && view.value !== 'empty') {
    return '选几样手边有的食材，按这些条件给你找菜'
  }
  if (!lastQuery.value.length) {
    return items.value.length ? '随机挑了几道新菜给你' : ''
  }
  return '按 ' + lastQuery.value.join('、') + ' 找的'
})

async function load(showSkeleton) {
  if (showSkeleton) view.value = 'skeleton'
  try {
    const ids = [...selectedIds.value]
    const data = await DishApi.recommendFeed(ids, page.value, PAGE_SIZE, freeIngredients.value)
    items.value = data.items || []
    view.value = items.value.length ? 'list' : 'empty'
    lastQuery.value = ids.map(nameOfIngredient).concat(freeIngredients.value)
  } catch (e) {
    errorMessage.value = e.message || '未知错误'
    view.value = 'error'
  }
}

function reload() {
  page.value = 1
  load(true)
}

function nextBatch() {
  page.value += 1
  load(true)
}

/** 点进详情：先上报查看再跳转。上报失败不影响跳转 —— 计数丢一次不要紧 */
async function open(item) {
  try {
    await DishApi.reportView(item.id)
  } catch (e) {
    /* 静默：查看计数丢一次不影响用户操作 */
  }
  router.push('/dish/' + item.id)
}

async function like(item) {
  if (liked.has(item.id)) return
  liked.add(item.id)
  item.likeCount = (item.likeCount || 0) + 1
  try {
    await DishApi.reportInteract(item.id, 'like')
  } catch (e) {
    // 失败了要把界面上的假增量撤回去，否则数字和库里对不上
    liked.delete(item.id)
    item.likeCount = Math.max(0, (item.likeCount || 0) - 1)
  }
}

onMounted(() => load(true))
</script>
