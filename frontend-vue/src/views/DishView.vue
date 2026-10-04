<template>
  <div class="page-dish">
    <FxBg />
    <TopBar current="kitchen" />

    <main class="page-wrap">
      <router-link class="back-link" to="/kitchen">
        <svg viewBox="0 0 14 14" fill="none" stroke="currentColor" stroke-width="1.6"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M8.5 3 4.5 7l4 4"/>
        </svg>
        <span>返回找菜</span>
      </router-link>

      <div id="content" aria-live="polite">
        <!-- 骨架 -->
        <div v-if="view === 'skeleton'" class="skeleton">
          <div class="sk-line sk-title"></div>
          <div class="sk-line"></div>
          <div class="sk-line sk-short"></div>
          <div class="sk-stats"><span class="sk-stat"></span><span class="sk-stat"></span><span class="sk-stat"></span></div>
        </div>

        <!-- 出错 -->
        <div v-else-if="view === 'error'" class="err-box">
          <p class="err-title">看不到这道菜</p>
          <p class="err-text">{{ errorMessage }}</p>
          <router-link class="secondary-btn" to="/kitchen">回到找菜页</router-link>
        </div>

        <!-- 正文 -->
        <template v-else-if="dish">
          <!-- ==================== 头部 ==================== -->
          <div class="detail-head">
            <div class="detail-head-main">
              <h1 class="detail-title">{{ dish.name }}</h1>
              <p v-if="dish.summary" class="detail-summary">{{ dish.summary }}</p>
              <div class="detail-tags">
                <!-- 草稿/已发布要明说，否则用户不知道"别人能不能看到" -->
                <span v-if="dish.status === 0" class="tag warn">草稿 · 仅自己可见</span>
                <span v-else-if="dish.status === 1" class="tag ok">已发布 · 大家都能搜到</span>
                <span v-if="SOURCE_TEXT[dish.source]" class="tag">{{ SOURCE_TEXT[dish.source] }}</span>
                <span v-if="dish.cookTimeMin" class="tag">约 {{ dish.cookTimeMin }} 分钟</span>
                <span v-if="dish.difficulty" class="tag">
                  {{ DIFFICULTY_TEXT[dish.difficulty] || (dish.difficulty + ' 级') }}
                </span>
                <span v-if="dish.spicyLevel !== null && dish.spicyLevel !== undefined" class="tag">
                  {{ SPICY_TEXT[dish.spicyLevel] || ('辣度 ' + dish.spicyLevel) }}
                </span>
                <span v-for="t in (dish.tastes || [])" :key="t" class="tag">{{ t }}</span>
              </div>
            </div>
            <div class="detail-actions">
              <button class="secondary-btn" id="collectBtn" type="button"
                      :class="{ 'is-on': collected }" :aria-pressed="String(collected)"
                      :disabled="collectBusy" @click="toggleCollect">
                {{ collected ? '已收藏' : '收藏' }}
              </button>

              <!-- 只有创建者看得到发布/撤回：别人的菜点了必然报错，摆着只会让人困惑 -->
              <button v-if="isOwner && dish.status === 0" class="primary-btn" id="publishBtn"
                      type="button" :disabled="publishBusy" @click="doPublish">发布到找菜库</button>
              <button v-else-if="isOwner && dish.status === 1" class="secondary-btn" id="unpublishBtn"
                      type="button" :disabled="publishBusy" @click="doUnpublish">从找菜库撤下</button>
            </div>
          </div>

          <div class="detail-grid">
            <!-- ---------------- 左列 ---------------- -->
            <div class="detail-col">
              <!-- 要准备什么 -->
              <section class="card">
                <div class="card-title-row">
                  <h2 class="card-title">要准备什么</h2>
                  <span v-if="dish.baseServing" class="serving-note" style="margin:0">
                    按 {{ dish.baseServing }} 人份
                  </span>
                </div>

                <ul v-if="(dish.ingredients || []).length" class="ing-list">
                  <li v-for="(i, idx) in dish.ingredients" :key="idx"
                      class="ing-row" :class="{ 'is-main': i.main, 'is-free': isFreeIng(i) }">
                    <span class="ing-row-name">
                      {{ i.name || '（未命名）' }}
                      <span v-if="i.main" class="tag accent">主料</span>
                    </span>
                    <span class="ing-row-amount">{{ i.amountGram ? i.amountGram + ' g' : '适量' }}</span>
                  </li>
                </ul>
                <p v-else class="rating-none">这份菜谱没有记录食材用量。</p>

                <template v-if="(dish.seasonings || []).length">
                  <h3 class="card-title" style="margin:20px 0 10px">调料</h3>
                  <ul class="pill-row">
                    <li v-for="(s, idx) in dish.seasonings" :key="idx" class="pill">{{ s }}</li>
                  </ul>
                </template>

                <template v-if="(dish.equipment || []).length">
                  <h3 class="card-title" style="margin:20px 0 10px">要用到的厨具</h3>
                  <ul class="pill-row">
                    <li v-for="(s, idx) in dish.equipment" :key="idx" class="pill">{{ s }}</li>
                  </ul>
                </template>

                <template v-if="(dish.substitutes || []).length">
                  <h3 class="card-title" style="margin:20px 0 10px">手边没有可以这样换</h3>
                  <ul class="sub-list">
                    <li v-for="(s, idx) in dish.substitutes" :key="idx">{{ s }}</li>
                  </ul>
                </template>
              </section>

              <!-- 怎么做 -->
              <section v-if="(dish.steps || []).length" class="card">
                <h2 class="card-title">怎么做</h2>
                <ol class="step-list">
                  <li v-for="(s, idx) in dish.steps" :key="idx" class="step">
                    <span class="step-no" aria-hidden="true">{{ stepNo(s, idx) }}</span>
                    <p class="step-text">
                      <span class="sr-only">第 {{ stepNo(s, idx) }} 步：</span>{{ s.text || '' }}
                    </p>
                  </li>
                </ol>
              </section>

              <!-- 大厨建议 -->
              <section v-if="(dish.chefTips || []).length" class="card">
                <h2 class="card-title">大厨建议</h2>
                <ul class="tip-list">
                  <li v-for="(t, idx) in dish.chefTips" :key="idx" class="tip">{{ t }}</li>
                </ul>
              </section>
            </div>

            <!-- ---------------- 右列 ---------------- -->
            <div class="detail-col">
              <!-- 营养：必须如实标注数据来源 -->
              <section v-if="nutritionCells.length" class="card">
                <div class="card-title-row">
                  <h2 class="card-title">营养（每人份）</h2>
                  <span v-if="aiEstimated" class="tag warn nutri-source">
                    {{ nutritionSourceText || 'AI 估算，仅供参考' }}
                  </span>
                  <span v-else-if="localUnverified" class="tag warn nutri-source">
                    {{ nutritionSourceText }} · 未通过核对
                  </span>
                  <span v-else class="tag ok nutri-source">
                    {{ nutritionSourceText || '按食材精确计算' }}
                  </span>
                </div>
                <div class="nutri-grid">
                  <div v-for="c in nutritionCells" :key="c[2]" class="nutri">
                    <span class="nutri-val">{{ c[0] }}<span class="nutri-unit">{{ c[1] }}</span></span>
                    <span class="nutri-label">{{ c[2] }}</span>
                  </div>
                </div>
                <p v-if="aiEstimated" class="nutri-caveat">
                  这些数字由 AI 根据食材和用量估算，同一道菜每次生成会有浮动，
                  用来判断「大概吃进去多少」可以，别拿它做严格的饮食控制。
                </p>
                <p v-else-if="localUnverified" class="nutri-caveat">
                  数字是按食材成分表算的，但这次没通过一致性核对（可能与配料表对不齐），
                  当作近似值看就好。
                </p>
                <p v-else class="nutri-caveat">
                  按「每 100 克成分 × 实际用量 ÷ 人数」算出来的，不含调料的热量，
                  所以比实际吃到的略微偏低。
                </p>
              </section>

              <!-- 评分 -->
              <section class="card" id="ratingCard">
                <div class="card-title-row">
                  <h2 class="card-title">大家怎么评</h2>
                  <span v-if="rating.collectCount" class="serving-note" style="margin:0">
                    {{ rating.collectCount }} 人收藏
                  </span>
                </div>

                <div v-if="rating.count > 0" class="rating-box">
                  <span class="rating-score">{{ Number(rating.average).toFixed(1) }}</span>
                  <span class="rating-meta">满分 5 分 · {{ rating.count }} 人评价</span>
                </div>
                <p v-else class="rating-none">还没有人评价，你可以是第一个。</p>

                <div class="stars" id="stars" role="group" aria-label="给这道菜打分"
                     @mouseleave="hoverScore = 0">
                  <button v-for="i in 5" :key="i" class="star" type="button"
                          :class="{ 'is-on': myScore && i <= myScore, 'is-hover': i <= hoverScore }"
                          :data-score="i" :aria-label="'打 ' + i + ' 星'"
                          :aria-pressed="myScore === i ? 'true' : 'false'"
                          @mouseover="hoverScore = i"
                          @click="submitRating(i)"
                          v-html="STAR_SVG"></button>
                </div>
                <p class="stars-note">
                  {{ myScore ? '你打了 ' + myScore + ' 星，再点其他星星可以改。' : '点星星打分。一人一票，可以改。' }}
                </p>
              </section>
            </div>
          </div>

          <!-- ==================== 评论 ==================== -->
          <section class="card">
            <div class="card-title-row">
              <h2 class="card-title">评论</h2>
              <span class="serving-note" style="margin:0" id="commentCount">
                {{ comments.length ? comments.length + ' 条' : '' }}
              </span>
            </div>

            <form class="comment-form" id="commentForm" @submit.prevent="submitComment">
              <label class="sr-only" for="commentInput">写下你的评价</label>
              <textarea class="input" id="commentInput" v-model="commentText"
                        :maxlength="MAX_COMMENT_LENGTH"
                        placeholder="做完感觉怎么样？说说口味、火候、踩过的坑"
                        @keydown="onCommentKeydown"></textarea>
              <div class="comment-actions">
                <span class="char-count" id="charCount" :class="{ 'is-over': commentText.length > MAX_COMMENT_LENGTH }">
                  {{ commentText.length }} / {{ MAX_COMMENT_LENGTH }}
                </span>
                <button class="primary-btn" id="commentSubmitBtn" type="submit" :disabled="commentBusy">发表</button>
              </div>
            </form>

            <ul class="comment-list" id="commentList">
              <li v-if="!comments.length" class="rating-none" id="commentEmpty">
                还没有评论。第一个说点什么的人，往往最有用。
              </li>
              <li v-for="(c, idx) in comments" :key="idx" class="comment">
                <div class="comment-head">
                  <span class="comment-who">{{ c.nickname || '匿名用户' }}</span>
                  <span class="comment-time">{{ c.createTime || '' }}</span>
                </div>
                <!-- 星级用和评分控件同一套 SVG，不用 ★ 字符（字体差异大，且落在 emoji 检测区间） -->
                <div v-if="c.rating" class="comment-stars" role="img" :aria-label="c.rating + ' 星'">
                  <span v-for="n in c.rating" :key="n" v-html="STAR_SVG"></span>
                </div>
                <p class="comment-body">{{ c.content }}</p>
              </li>
            </ul>
          </section>

          <div style="height:8px"></div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   菜品详情页（Vue 版）
   对应原生版：dish.html + js/dish.js

   一个接口一次把营养、做法、调料、建议、评论、评分全给回来 ——
   详情页是「点进去就要看全」的场景，拆成多个请求反而更慢更难处理加载态。
   ============================================================ */
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { DishApi } from '../api.js'
import { authState } from '../store/auth.js'
import { DIFFICULTY_TEXT, SPICY_TEXT, SOURCE_TEXT, NUTRITION_SOURCE_TEXT } from '../lib/constants.js'
import { toastSuccess, toastError, toastInfo } from '../store/toast.js'

const STAR_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true">'
  + '<path d="M12 3.2 14.7 8.8 20.8 9.7 16.4 14 17.4 20.1 12 17.2 6.6 20.1 7.6 14 3.2 9.7 9.3 8.8Z"/>'
  + '</svg>'

/** 与后端 t_dish_comment.content 的列长度一致，前端先拦一道 */
const MAX_COMMENT_LENGTH = 500

const route = useRoute()
const commentInput = ref(null)   // 仅用于发表后聚焦（可选）

const view = ref('skeleton')     // skeleton | error | detail
const errorMessage = ref('')
const dish = ref(null)

const collected = ref(false)
const myScore = ref(null)
const hoverScore = ref(0)
/** 防止连点重复提交 */
const collectBusy = ref(false)
const publishBusy = ref(false)
const ratingBusy = ref(false)
const commentBusy = ref(false)

const comments = ref([])
const commentText = ref('')

/** 菜品 ID 一律保持字符串（见 readDishId 注释） */
const dishId = computed(() => {
  const raw = String(route.params.id == null ? '' : route.params.id)
  return /^\d{1,19}$/.test(raw) ? raw : null
})

/**
 * 【必须保持字符串，不要转 Number】主键是 19 位雪花 ID（约 2×10^18），
 * 而 JS 的 Number 只能精确表示 2^53 以内的整数 —— 转一下低位就丢了，
 * 拿丢掉精度的数字去请求，后端只会回一句「菜品不存在」。
 * 上面 dishId 只做格式校验（/^\d{1,19}$/），不做数值转换。
 */

/** 当前用户是不是这道菜的创建者（字符串比较，绝不 Number()） */
const isOwner = computed(() => {
  if (!dish.value) return false
  return !!authState.userId && String(authState.userId) === String(dish.value.creatorId)
})

const rating = computed(() => (dish.value && dish.value.rating) || { average: 0, count: 0, collectCount: 0 })

/**
 * 营养来源代码（1 = AI 估算，2 = 按成分表本地计算）。
 *
 * 之前这里用 `!verified` 反推"是不是 AI 估的"，那是错的：verified 说的是
 * "有没有通过一致性核对"，不是"数字哪来的"。本地算出来但核对没过的数据
 * 会被误标成 AI 估算 —— 与列表页（按 nutritionSource 渲染）自相矛盾。
 * 现在改成跟列表页同一口径：后端给代码，前端查同一张映射表。
 */
const nutritionSourceCode = computed(() => {
  const n = dish.value && dish.value.nutrition
  if (!n) return null
  return n.nutritionSource === null || n.nutritionSource === undefined ? null : n.nutritionSource
})

/** 没有代码的老数据：退回 verified 猜测，至少不会把两种来源说成同一种 */
const nutritionSourceText = computed(() => {
  const code = nutritionSourceCode.value
  if (code && NUTRITION_SOURCE_TEXT[code]) return NUTRITION_SOURCE_TEXT[code]
  const n = dish.value && dish.value.nutrition
  if (!n) return ''
  return n.verified ? NUTRITION_SOURCE_TEXT[2] : NUTRITION_SOURCE_TEXT[1]
})

/** 只有确实来自 AI 估算（或来源不明且未核对）时才提示"数字会浮动" */
const aiEstimated = computed(() => {
  const code = nutritionSourceCode.value
  if (code) return code === 1
  const n = dish.value && dish.value.nutrition
  return !!n && !n.verified
})

/** 本地计算但没通过核对 —— 少见，但要如实说，不能冒充"精确计算" */
const localUnverified = computed(() => {
  const n = dish.value && dish.value.nutrition
  return nutritionSourceCode.value === 2 && !!n && !n.verified
})

/** 营养格：后端给什么显示什么，前端不自己判断可信度 */
const nutritionCells = computed(() => {
  const n = dish.value && dish.value.nutrition
  if (!n || !n.calories) return []
  return [
    [n.calories, '千卡', '热量'],
    [n.proteinGram, 'g', '蛋白质'],
    [n.fatGram, 'g', '脂肪'],
    [n.carbGram, 'g', '碳水'],
    [n.fiberGram, 'g', '膳食纤维'],
    [n.sodiumMg, 'mg', '钠']
  ].filter(c => c[0] !== null && c[0] !== undefined)
})

/** ingredientId 为空 = 不在主数据里（自填或 AI 加的），不参与营养核算，要标出来 */
function isFreeIng(i) {
  return i.ingredientId === null || i.ingredientId === undefined
}

/** 后端给的 no 可能缺失（历史数据），用下标兜底 */
function stepNo(s, idx) {
  return s.no || (idx + 1)
}

/* ---------------- 加载 ---------------- */

async function load() {
  if (!dishId.value) {
    view.value = 'error'
    errorMessage.value = '地址里缺少菜品编号，可能是从旧链接点进来的。'
    return
  }
  view.value = 'skeleton'
  try {
    const data = await DishApi.detail(dishId.value)
    dish.value = data
    collected.value = !!data.collected
    myScore.value = data.myScore === undefined ? null : data.myScore
    comments.value = data.comments || []
    view.value = 'detail'
  } catch (e) {
    view.value = 'error'
    errorMessage.value = e.message || '未知错误'
  }
}

/* ---------------- 收藏 ---------------- */

async function toggleCollect() {
  if (collectBusy.value) return
  collectBusy.value = true
  try {
    const result = collected.value
      ? await DishApi.uncollect(dishId.value)
      : await DishApi.collect(dishId.value)
    collected.value = !!result.collected
    toastSuccess(collected.value ? '已加入收藏，可以在「我的」里找到' : '已取消收藏')
  } catch (e) {
    toastError(e.message)
  } finally {
    collectBusy.value = false
  }
}

/* ---------------- 发布 / 撤回 ---------------- */

/**
 * 发布前先调一次 publishCheck 把「还差什么」摆出来。
 * 直接点后端也会返回同样的原因，但那是"失败了才知道"，用户还白等一轮请求。
 */
async function doPublish() {
  if (publishBusy.value) return
  publishBusy.value = true
  try {
    const check = await DishApi.publishCheck(dishId.value)
    if (check && check.pass === false) {
      toastError('还差一点：' + (check.problems || []).join('；'))
      return
    }
    const msg = await DishApi.publishDish(dishId.value)
    toastSuccess(msg || '已发布到找菜库')
    // 重拉详情：发布状态、发布时间都要跟着变
    await load()
  } catch (e) {
    toastError(e.message)
  } finally {
    publishBusy.value = false
  }
}

async function doUnpublish() {
  if (publishBusy.value) return
  publishBusy.value = true
  try {
    const msg = await DishApi.unpublishDish(dishId.value)
    toastInfo(msg || '已从找菜库撤下')
    await load()
  } catch (e) {
    toastError(e.message)
  } finally {
    publishBusy.value = false
  }
}

/* ---------------- 评分 ---------------- */

async function submitRating(score) {
  if (ratingBusy.value) return
  ratingBusy.value = true
  try {
    const summary = await DishApi.rate(dishId.value, score, false)
    myScore.value = score
    /*
     * 【Vue 相对原生版的一个实打实的改进】
     * 原生版这里必须手写「只替换评分卡 DOM」，因为整页 innerHTML 重绘会把
     * 评论框里正在写的内容清掉。Vue 下改 myScore 只会 patch 变化的那几个
     * 按钮属性，textarea 的 v-model 值本来就不受影响 —— 不用再刻意绕。
     */
    if (dish.value && dish.value.rating) {
      dish.value.rating.average = summary.average
      dish.value.rating.count = summary.count
    }
    toastSuccess('已记录你的 ' + score + ' 星评价')
  } catch (e) {
    toastError(e.message)
  } finally {
    ratingBusy.value = false
  }
}

/* ---------------- 评论 ---------------- */

function onCommentKeydown(e) {
  // Ctrl / Cmd + Enter 快速发表，写长评论时手不用离开键盘
  if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
    e.preventDefault()
    submitComment()
  }
}

async function submitComment() {
  if (commentBusy.value) return

  const content = commentText.value.trim()
  if (!content) {
    toastError('先写点什么吧')
    return
  }
  if (content.length > MAX_COMMENT_LENGTH) {
    toastError('评论最多 ' + MAX_COMMENT_LENGTH + ' 字')
    return
  }

  commentBusy.value = true
  try {
    const created = await DishApi.comment(dishId.value, content, myScore.value)
    // 本地插入新评论，不重拉整个详情：省一次请求，也不会打断用户
    comments.value.unshift(created)
    commentText.value = ''
    toastSuccess('评论已发表')
  } catch (e) {
    toastError(e.message)
  } finally {
    commentBusy.value = false
  }
}

/* ---------------- 启动与路由参数变化 ---------------- */

onMounted(load)
// 从「榜单」点另一道菜进来时，组件会被复用，必须监听 id 变化重新加载
watch(() => route.params.id, (nv, ov) => {
  if (nv !== ov) load()
})
</script>
