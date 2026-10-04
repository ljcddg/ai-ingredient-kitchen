<template>
  <div class="page-rank">
    <FxBg />
    <TopBar current="rank" />

    <main class="page-wrap">
      <div class="page-head">
        <div>
          <h1 class="page-title">榜单</h1>
          <p class="page-sub" id="rankSub">{{ subtitleText }}</p>
        </div>
      </div>

      <!-- 用分段控件而不是页签：四个选项是"同一份列表的四种排序"，
           用页签会显得像四个不同的页面 -->
      <div class="rank-seg" id="rankSeg" role="tablist" aria-label="榜单类型">
        <button
          v-for="t in RANK_TYPES"
          :key="t.key"
          class="rank-seg-btn"
          type="button"
          role="tab"
          :data-type="t.key"
          :aria-selected="t.key === state.type ? 'true' : 'false'"
          @click="switchType(t.key)"
        >{{ t.label }}</button>
      </div>

      <div id="panel" role="tabpanel" aria-live="polite">
        <!-- 骨架 -->
        <div v-if="view === 'skeleton'" class="skeleton">
          <div class="sk-line sk-title"></div>
          <div class="sk-line"></div>
          <div class="sk-line"></div>
          <div class="sk-line sk-short"></div>
        </div>

        <!-- 出错 -->
        <div v-else-if="view === 'error'" class="err-box">
          <p class="err-title">没能加载榜单</p>
          <p class="err-text">{{ errorMessage }}</p>
          <button class="secondary-btn" type="button" id="retryBtn" @click="load(true)">再试一次</button>
        </div>

        <!-- 空态 -->
        <div v-else-if="view === 'empty'" class="empty">
          <p class="empty-title">这个榜还空着</p>
          <p class="empty-text">只有被发布到找菜库的菜才会出现在这里。去做一道再发布出来，你就占住第一个位置了。</p>
          <div class="empty-actions">
            <router-link class="primary-btn" to="/kitchen">去做一道</router-link>
          </div>
        </div>

        <!-- 列表 -->
        <template v-else>
          <ul class="rank-list">
            <li v-for="(item, index) in items" :key="item.id">
              <router-link class="rank-item" :to="'/dish/' + item.id">
                <span class="rank-no" :class="{ 'is-top': noOf(index) <= 3 }">{{ noOf(index) }}</span>
                <div class="rank-main">
                  <div class="rank-name">{{ item.name }}</div>
                  <p v-if="item.summary" class="rank-summary">{{ item.summary }}</p>
                  <div class="rank-meta">
                    <span v-if="SOURCE_TEXT[item.source]"
                          class="tag" :class="{ accent: item.source === 2 }">{{ SOURCE_TEXT[item.source] }}</span>
                    <span v-if="item.cookTimeMin" class="tag">约 {{ item.cookTimeMin }} 分钟</span>
                    <span v-if="item.difficulty" class="tag">
                      {{ DIFFICULTY_TEXT[item.difficulty] || (item.difficulty + ' 级') }}
                    </span>
                    <span v-if="item.collectCount" class="tag">{{ item.collectCount }} 人收藏</span>
                  </div>
                </div>
                <!-- 没人评价时不要显示"0.0 分"——那看起来像"很难吃" -->
                <div class="rank-score">
                  <!-- 冷启动榜是综合得分排的序，就显示综合得分，
                       显示平均分反而对不上名次，会让人以为排序错了 -->
                  <template v-if="state.type === 'cold'">
                    <div class="rank-score-num">{{ Number(item.dishScore || 0).toFixed(0) }}</div>
                    <div class="rank-score-label">综合得分</div>
                  </template>
                  <template v-else-if="item.ratingCount > 0">
                    <div class="rank-score-num">{{ Number(item.ratingAvg || 0).toFixed(1) }}</div>
                    <div class="rank-score-label">{{ item.ratingCount }} 人评价</div>
                  </template>
                  <template v-else>
                    <div class="rank-score-label">还没人评价</div>
                  </template>
                </div>
              </router-link>
            </li>
          </ul>

          <!-- 还有下一页就给个按钮。不做无限滚动 ——
               榜单是"看一眼就走"的场景，一直往下刷反而让人停不下来还啥也没记住 -->
          <div v-if="hasMore" class="rank-more">
            <button class="secondary-btn" type="button" id="moreBtn" @click="loadMore">
              再看 {{ Math.min(PAGE_SIZE, state.total - shown) }} 道
            </button>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   榜单页（Vue 版）
   对应原生版：rank.html + js/rank.js

   三个榜共用同一个接口（GET /ai/rank?type=...），前端只负责切换参数。
   榜单内容由后端算好，这里不做任何排序或筛选 ——
   排序口径（威尔逊下界、热度权重）是业务规则，必须只有一份实现。
   ============================================================ */
import { ref, reactive, computed, onMounted } from 'vue'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { DishApi } from '../api.js'
import { DIFFICULTY_TEXT, SOURCE_TEXT } from '../lib/constants.js'

const RANK_TYPES = [
  { key: 'hot', label: '热门榜' },
  { key: 'top', label: '高分榜' },
  { key: 'latest', label: '最新榜' },
  // 冷启动榜：按综合得分（评分 60% + 互动 40%）排，新菜从这里冒头
  { key: 'cold', label: '冷启动榜' }
]

/**
 * 每页条数。两个约束都要对齐后端 DishRankService：
 *   ① 单页上限 50 —— 这里别写更大的数，写了也会被后端夹回去
 *   ② 榜单总容量 100 —— 后端会把返回的 total 封顶到 100，
 *      所以这里最多就是 10 页，翻到底自动没有"再看"按钮
 */
const PAGE_SIZE = 10

const state = reactive({ type: 'hot', page: 1, total: 0 })
const items = ref([])
const view = ref('skeleton')       // skeleton | error | empty | list
const errorMessage = ref('')
const subtitle = ref('')

const shown = computed(() => (state.page - 1) * PAGE_SIZE + items.value.length)
const hasMore = computed(() => view.value === 'list' && shown.value < state.total)

const subtitleText = computed(() => {
  if (view.value === 'list' || view.value === 'empty') {
    return (subtitle.value || '榜单') + '，共 ' + state.total + ' 道'
  }
  return '看看大家最近都在做什么菜。'
})

/** 名次要在分页里连续，所以不能用 index 直接当名次 */
function noOf(index) {
  return (state.page - 1) * PAGE_SIZE + index + 1
}

async function load(showSkeleton) {
  if (showSkeleton) view.value = 'skeleton'
  try {
    const data = await DishApi.rank(state.type, state.page, PAGE_SIZE)
    state.total = data.total || 0
    subtitle.value = data.subtitle || '榜单'
    items.value = data.items || []
    view.value = items.value.length ? 'list' : 'empty'
  } catch (e) {
    errorMessage.value = e.message || '未知错误'
    view.value = 'error'
  }
}

function switchType(type) {
  if (type === state.type) return
  state.type = type
  state.page = 1
  load(true)
}

function loadMore() {
  state.page += 1
  load(false)
}

onMounted(() => load(true))
</script>
