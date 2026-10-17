<template>
  <div class="page-discover">
    <FxBg />
    <TopBar current="discover" />

    <main class="page-wrap">
      <div class="page-head">
        <div>
          <h1 class="page-title">今天吃什么</h1>
          <p class="page-sub">{{ subtitleText }}</p>
        </div>
      </div>

      <div class="discover-grid">
        <!-- ==================== 左：手边食材 ==================== -->
        <section class="picker" aria-label="选择手边食材">
          <div class="picker-head">
            <h2 class="picker-title">手边有什么</h2>
            <button v-if="totalPicked" class="link-btn" type="button" @click="clearAll">清空</button>
          </div>

          <!-- 已选摘要 -->
          <div v-if="totalPicked" class="picked-bar">
            <span v-for="id in sortedSelected" :key="'s' + id"
                  class="chip" @click="toggleIngredient(id)">
              {{ nameOfIngredient(id) }}<i class="chip-x">×</i>
            </span>
            <span v-for="n in freeIngredients" :key="'f' + n"
                  class="chip chip-free" @click="removeFreeIngredient(n)">
              {{ n }}<i class="chip-x">×</i>
            </span>
          </div>
          <p v-else class="picker-hint">不选也可以，那就按大家都在做的给你挑。</p>

          <!-- 搜索 -->
          <div class="ing-search">
            <input v-model="ingKeyword" class="ing-input" type="search"
                   placeholder="搜食材" @keydown="onIngSearchKeydown" />
            <button v-if="ingKeyword" class="link-btn" type="button" @click="clearIngSearch">清除</button>
          </div>
          <p v-if="searchMiss" class="search-miss">{{ searchMiss }}</p>

          <!-- 分组网格 -->
          <div class="ing-groups">
            <div v-for="g in visibleGroups" :key="g.name" class="ing-group">
              <div class="ing-group-name" :class="GROUP_TONE[g.name]">{{ g.name }}</div>
              <div class="ing-grid">
                <button v-for="it in g.items" :key="it.id"
                        class="ing-item"
                        :class="{ on: selectedIds.includes(it.id) }"
                        type="button" @click="toggleIngredient(it.id)">
                  {{ it.name }}
                </button>
              </div>
            </div>
          </div>

          <!-- 表外食材 -->
          <div class="free-input">
            <label class="free-label" for="freeIng">食材表里没有的，直接敲</label>
            <div class="free-row">
              <input id="freeIng" v-model="freeInput" class="ing-input" type="text"
                     placeholder="比如 松露" @input="onFreeInput" @keydown="onFreeKeydown" />
              <button class="secondary-btn" type="button" @click="addFreeIngredient(freeInput)">加上</button>
            </div>
            <ul v-if="suggestVisible" class="suggest">
              <li v-for="(s, i) in suggests" :key="s.name"
                  :class="{ on: i === suggestIndex }"
                  @click="addFreeIngredient(s.name)">{{ s.name }}</li>
            </ul>
          </div>
        </section>

        <!-- ==================== 右：结果 ==================== -->
        <section class="results" aria-label="推荐结果">
          <!-- 模式切换 -->
          <div class="mode-seg" role="tablist" aria-label="呈现方式">
            <button class="mode-btn" type="button" role="tab"
                    :class="{ on: mode === 'browse' }"
                    :aria-selected="mode === 'browse' ? 'true' : 'false'"
                    @click="switchMode('browse')">随便看看</button>
            <button class="mode-btn" type="button" role="tab"
                    :class="{ on: mode === 'decide' }"
                    :aria-selected="mode === 'decide' ? 'true' : 'false'"
                    @click="switchMode('decide')">帮我决定</button>
          </div>

          <!-- 筛选器 -->
          <div class="filters">
            <label class="filter">
              <span>用时</span>
              <select v-model="maxCookTimeMin" @change="reload">
                <option :value="null">不限</option>
                <option :value="15">15 分钟内</option>
                <option :value="30">30 分钟内</option>
                <option :value="60">60 分钟内</option>
              </select>
            </label>
            <label class="filter">
              <span>难度</span>
              <select v-model="maxDifficulty" @change="reload">
                <option :value="null">不限</option>
                <option :value="2">简单</option>
                <option :value="3">一般</option>
                <option :value="5">都能做</option>
              </select>
            </label>
            <label class="filter">
              <span>人数</span>
              <select v-model="serving" @change="reload">
                <option :value="null">不限</option>
                <option :value="1">1 人</option>
                <option :value="2">2 人</option>
                <option :value="3">3 人</option>
                <option :value="4">4 人以上</option>
              </select>
            </label>
            <button v-if="hasFilter" class="link-btn" type="button" @click="resetFilters">重置</button>
          </div>

          <!-- ---------- 浏览模式 ---------- -->
          <div v-if="mode === 'browse'" class="results-body">
            <div v-if="view === 'skeleton'" class="skeleton">
              <div class="sk-line sk-title"></div>
              <div class="sk-line"></div>
              <div class="sk-line"></div>
              <div class="sk-line sk-short"></div>
            </div>

            <div v-else-if="view === 'error'" class="err-box">
              <p class="err-title">没能加载</p>
              <p class="err-text">{{ errorMessage }}</p>
              <button class="secondary-btn" type="button" @click="reload">再试一次</button>
            </div>

            <div v-else-if="view === 'empty'" class="empty">
              <p class="empty-title">暂时没有合适的菜</p>
              <p class="empty-text">试着放宽用时或难度，或者少选几样食材。</p>
            </div>

            <ul v-else class="dish-list">
              <li v-for="item in items" :key="item.id">
                <div class="dish-card">
                  <div class="card-top">
                    <router-link class="dish-name" :to="'/dish/' + item.id" @click="markSeen(item)">
                      {{ item.name }}
                    </router-link>
                    <span v-if="item.matchBadge" class="badge" :class="'badge-' + item.matchTier">
                      {{ item.matchBadge }}
                    </span>
                  </div>

                  <p v-if="item.reason" class="dish-reason">{{ item.reason }}</p>

                  <p v-if="item.missingIngredients && item.missingIngredients.length" class="dish-miss">
                    还缺 {{ item.missingIngredients.join('、') }}
                    <span v-if="item.missingSeasonings && item.missingSeasonings.length" class="miss-soft">
                      （{{ item.missingSeasonings.join('、') }} 是调料，可省）
                    </span>
                  </p>

                  <div class="dish-meta">
                    <span v-if="item.cookTimeMin" class="tag">约 {{ item.cookTimeMin }} 分钟</span>
                    <span v-if="item.difficulty" class="tag">
                      {{ DIFFICULTY_TEXT[item.difficulty] || (item.difficulty + ' 级') }}
                    </span>
                    <span v-if="item.ratingCount" class="tag">
                      {{ Number(item.ratingAvg || 0).toFixed(1) }} 分 · {{ item.ratingCount }} 人
                    </span>
                    <span v-if="item.cookCount" class="tag">{{ item.cookCount }} 人做过</span>
                  </div>
                </div>
              </li>
            </ul>

            <div v-if="view === 'list' && hasMore" class="list-more">
              <button class="secondary-btn" type="button" :disabled="loading" @click="nextPage">
                {{ loading ? '加载中…' : '再看一批' }}
              </button>
            </div>
          </div>

          <!-- ---------- 决策模式 ---------- -->
          <div v-else class="decide-panel">
            <div v-if="view === 'skeleton'" class="skeleton">
              <div class="sk-line sk-title"></div>
              <div class="sk-line"></div>
              <div class="sk-line sk-short"></div>
            </div>

            <div v-else-if="view === 'error'" class="err-box">
              <p class="err-title">没能加载</p>
              <p class="err-text">{{ errorMessage }}</p>
              <button class="secondary-btn" type="button" @click="reload">再试一次</button>
            </div>

            <div v-else-if="view === 'empty'" class="empty">
              <p class="empty-title">没有可以推荐的菜了</p>
              <p class="empty-text">放宽一点条件，或者换几样食材试试。</p>
            </div>

            <template v-else>
              <div class="decide-card">
                <div class="decide-name">{{ current.name }}</div>
                <p v-if="current.reason" class="decide-reason">{{ current.reason }}</p>

                <p v-if="current.missingIngredients && current.missingIngredients.length" class="decide-miss">
                  还缺 {{ current.missingIngredients.join('、') }}
                  <span v-if="current.missingSeasonings && current.missingSeasonings.length" class="miss-soft">
                    （{{ current.missingSeasonings.join('、') }} 是调料，可省）
                  </span>
                </p>
                <p v-else-if="totalPicked" class="decide-miss decide-miss-ok">你手边的食材都齐了</p>

                <div class="decide-meta">
                  <span v-if="current.cookTimeMin" class="tag">约 {{ current.cookTimeMin }} 分钟</span>
                  <span v-if="current.difficulty" class="tag">
                    {{ DIFFICULTY_TEXT[current.difficulty] || (current.difficulty + ' 级') }}
                  </span>
                  <span v-if="current.ratingCount" class="tag">
                    {{ Number(current.ratingAvg || 0).toFixed(1) }} 分 · {{ current.ratingCount }} 人
                  </span>
                  <span v-if="current.cookCount" class="tag">{{ current.cookCount }} 人做过</span>
                </div>

                <p class="decide-count">第 {{ decideIndex + 1 }} / {{ pool.length }} 道</p>
              </div>

              <div class="decide-actions">
                <router-link class="primary-btn" :to="'/dish/' + current.id" @click="markSeen(current)">
                  就这个
                </router-link>
                <button class="secondary-btn" type="button" @click="nextDish">换一个</button>
                <button class="ghost-btn" type="button" @click="nextDish">不想吃</button>
              </div>
            </template>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   发现页
   ------------------------------------------------------------
   取代原来的推荐页与榜单页，合并成一个入口。

   和旧设计的根本区别：
     旧：四个榜单各自排序，用户切 tab 去找自己想要的
     新：一个入口，先问"你手边有什么"，再按"你能不能做"排，
         并且可以切成"帮我决定"（一次只给一道，直接替用户做选择）

   两个模式共用后端同一套排序结果，只是"一次取几条"不同，
   所以用户切到"帮我决定"看到的第一道，和列表里排第一的必然是同一道。
   ============================================================ */
import { ref, computed, reactive, onMounted } from 'vue'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { DishApi } from '../api.js'
import { DIFFICULTY_TEXT } from '../lib/constants.js'
import {
  INGREDIENT_GROUPS, GROUP_TONE, nameOfIngredient,
  allLocalIngredients, findIngredientIdByName
} from '../lib/ingredients.js'

const PAGE_SIZE = 10

/** 与后端保持一致，前端先拦一道 */
const MAX_INGREDIENTS = 20

// ---------- 模式 ----------
const mode = ref('browse')            // browse | decide

// ---------- 食材选择 ----------
const selectedIds = ref([])
const freeIngredients = ref([])
const ingKeyword = ref('')
const freeInput = ref('')
const suggests = ref([])
const suggestIndex = ref(0)

// ---------- 筛选条件 ----------
const maxCookTimeMin = ref(null)
const maxDifficulty = ref(null)
const serving = ref(null)

// ---------- 结果 ----------
const items = ref([])
const view = ref('skeleton')          // skeleton | error | empty | list
const errorMessage = ref('')
const loading = ref(false)
const page = ref(1)
const total = ref(0)

// ---------- 决策模式 ----------
const pool = ref([])                  // 一次备好的一批
const decideIndex = ref(0)            // 当前是这批里的第几道

const suggestVisible = computed(() => suggests.value.length > 0)
const totalPicked = computed(() => selectedIds.value.length + freeIngredients.value.length)
const hasFilter = computed(() =>
  maxCookTimeMin.value !== null || maxDifficulty.value !== null || serving.value !== null)

/** 决策模式当前展示的菜 */
const current = computed(() => pool.value[decideIndex.value] || null)

const hasMore = computed(() => total.value > page.value * PAGE_SIZE)

const subtitleText = computed(() => {
  if (view.value !== 'list' && view.value !== 'empty') {
    return '选几样手边有的食材，也可以直接让我替你挑一道'
  }
  if (!totalPicked.value) return '按大家都在做的给你挑'
  return '按 ' + pickedNames.value.join('、') + ' 找的'
})

const pickedNames = computed(() =>
  sortedSelected.value.map(nameOfIngredient).concat(freeIngredients.value))

const sortedSelected = computed(() => {
  const order = INGREDIENT_GROUPS.flatMap(g => g.items.map(i => i.id))
  return [...selectedIds.value].sort((a, b) => order.indexOf(a) - order.indexOf(b))
})

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
  return `食材表里没有「${kw}」—— 可以在下面直接输入`
})

/* ---------------- 条件 ---------------- */

function conditions() {
  return {
    ingredientIds: [...selectedIds.value],
    ingredientNames: [...freeIngredients.value],
    maxCookTimeMin: maxCookTimeMin.value,
    maxDifficulty: maxDifficulty.value,
    serving: serving.value
  }
}

/* ---------------- 加载 ---------------- */

/**
 * 请求序号：连点筛选时可能同时有多个请求在飞，
 * 只有最后一次发出的那个结果有资格写进页面 —— 否则会出现
 * "选了 30 分钟，页面却显示上一次不限时的结果"。
 */
let requestSeq = 0

async function loadBrowse(p) {
  const seq = ++requestSeq
  loading.value = true
  if (p === 1) view.value = 'skeleton'
  try {
    const data = await DishApi.discoverBrowse(conditions(), p, PAGE_SIZE)
    if (seq !== requestSeq) return
    // 「再看一批」必须追加，不能替换：原来的写法会把上一批整段冲掉，
    // 用户点第二页等于"从头再来"，前面的菜白看了
    const list = data.items || []
    items.value = p === 1 ? list : mergeUnique(items.value, list)
    total.value = data.total || 0
    page.value = p
    view.value = items.value.length ? 'list' : 'empty'
  } catch (e) {
    if (seq !== requestSeq) return
    errorMessage.value = e.message || '未知错误'
    view.value = 'error'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/** 按 id 去重追加：后端分页边界偶尔会漏出重复项（排序抖动） */
function mergeUnique(base, extra) {
  const ids = new Set(base.map(i => i.id))
  const merged = base.slice()
  for (const item of extra) {
    if (!ids.has(item.id)) {
      ids.add(item.id)
      merged.push(item)
    }
  }
  return merged
}

async function loadDecide(offset) {
  const seq = ++requestSeq
  loading.value = true
  if (offset === 0) view.value = 'skeleton'
  try {
    const list = await DishApi.discoverDecide(conditions(), offset) || []
    if (seq !== requestSeq) return
    pool.value = list
    decideIndex.value = 0
    view.value = list.length ? 'list' : 'empty'
  } catch (e) {
    if (seq !== requestSeq) return
    errorMessage.value = e.message || '未知错误'
    view.value = 'error'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/** 条件变了：两边都从头来 */
function reload() {
  if (mode.value === 'browse') {
    loadBrowse(1)
  } else {
    loadDecide(0)
  }
}

function switchMode(next) {
  if (mode.value === next) return
  const previous = mode.value
  mode.value = next

  // 浏览 → 决策：列表里已经有一批了，直接拿它当池子。
  // 这样"切过去看到的第一道"必然就是列表里排第一的那道（和后端同一套排序），
  // 也省掉一次十几条的打分请求。
  if (next === 'decide' && previous === 'browse' && items.value.length) {
    const fresh = items.value.filter(i => !seen.has(i.id))
    pool.value = fresh.length ? fresh : items.value
    decideIndex.value = 0
    view.value = 'list'
    return
  }
  reload()
}

function nextPage() {
  if (!hasMore.value || loading.value) return
  loadBrowse(page.value + 1)
}

/**
 * 换一个。
 *
 * 从这一批里取下一位，不是重新请求 —— 每次重算会因为排序的微小抖动
 * （比如刚好有人打了分）重复出同一道菜。这批用完了再取下一批。
 *
 * 这一轮已经点进去看过的菜（{@link markSeen}）要跳过：
 * 用户点过又回来点"换一个"，还是同一道会很莫名其妙。
 */
function nextDish() {
  let i = decideIndex.value
  while (i + 1 < pool.value.length && seen.has(pool.value[i + 1].id)) {
    i += 1
  }
  if (i + 1 < pool.value.length) {
    decideIndex.value = i + 1
    return
  }
  // 整批都看过了：按已消费的条数取下一批
  loadDecide(pool.value.length)
}

/** 点进去之前记一下，避免"换一个"转回来还是它（仅本次会话有效） */
function markSeen(item) {
  if (item) seen.add(item.id)
}
const seen = reactive(new Set())

/* ---------------- 食材选择交互 ---------------- */

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

function onIngSearchKeydown(e) {
  if (e.key === 'Escape') clearIngSearch()
}

function onFreeInput() {
  const kw = freeInput.value.trim().toLowerCase()
  suggests.value = kw
    ? allLocalIngredients().filter(i => i.name.toLowerCase().includes(kw)).slice(0, 6)
    : []
  suggestIndex.value = 0
}

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

/** 名字能对上主数据就转 ID，对不上才当表外食材 */
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

function resetFilters() {
  maxCookTimeMin.value = null
  maxDifficulty.value = null
  serving.value = null
  reload()
}

onMounted(() => reload())
</script>
