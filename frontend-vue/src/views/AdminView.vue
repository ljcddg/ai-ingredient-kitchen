<template>
  <div class="page-admin">
    <FxBg />
    <TopBar current="admin" />

    <main class="page-wrap">
      <div class="page-head">
        <div>
          <h1 class="page-title">管理台</h1>
          <p class="page-sub">{{ subtitle }}</p>
        </div>
        <button class="secondary-btn" type="button" :disabled="loading" @click="refresh">
          {{ loading ? '刷新中…' : '刷新' }}
        </button>
      </div>

      <div class="rank-seg" role="tablist" aria-label="管理功能">
        <button v-for="t in TABS" :key="t.key" class="rank-seg-btn" type="button" role="tab"
                :data-tab="t.key" :aria-selected="String(tab === t.key)"
                @click="switchTab(t.key)">{{ t.label }}</button>
      </div>

      <div v-if="errorMessage" class="err-box">
        <p class="err-title">操作没成功</p>
        <p class="err-text">{{ errorMessage }}</p>
      </div>

      <!-- ==================== 食材候选审核（P0-2） ==================== -->
      <section v-show="tab === 'candidates'" class="card">
        <div class="card-title-row">
          <h2 class="card-title">待审食材候选</h2>
          <span class="task-hint">够次数的会排前面；通过后它才成为正式食材</span>
        </div>

        <div v-if="!candidates.length" class="empty">
          <p class="empty-title">没有待审的候选</p>
          <p class="empty-text">用户在输入框里写了主数据里没有的食材，系统会记下来；同一个名字被用够次数就会出现在这里等你决定。</p>
        </div>

        <ul v-else class="cand-list">
          <li v-for="c in candidates" :key="c.id" class="cand">
            <label class="cand-link" style="cursor:pointer">
              <input type="checkbox" :value="String(c.id)" v-model="selected">
              <div>
                <div class="cand-name">{{ c.name }}</div>
                <div class="cand-sub">
                  被用了 {{ c.useCount }} 次 · 首次出现 {{ shortTime(c.firstSeen) }}
                </div>
              </div>
              <div class="cand-right">
                <span class="tag">待审核</span>
              </div>
            </label>
          </li>
        </ul>

        <template v-if="candidates.length">
          <div class="field" style="margin-top:12px">
            <label class="field-hint" for="approveReason">审核说明（通过可选；驳回必填）</label>
            <input class="input" id="approveReason" type="text" maxlength="100"
                   placeholder="例如：确认是常见食材 / 与「番茄」重复" v-model="reasonInput">
          </div>
          <div class="alt-action">
            <p>已选 {{ selected.length }} 条。</p>
            <button class="primary-btn" type="button" :disabled="!selected.length || busy"
                    @click="approve">通过并转正</button>
            <button class="secondary-btn" type="button" :disabled="!selected.length || busy"
                    @click="reject">驳回</button>
          </div>
        </template>

        <div class="card-title-row" style="margin-top:18px">
          <h2 class="card-title">已审核（最近 {{ reviewed.length }} 条）</h2>
          <button class="ghost-btn" type="button" :aria-expanded="String(historyOpen)"
                  @click="historyOpen = !historyOpen">{{ historyOpen ? '收起' : '展开' }}</button>
        </div>
        <ul v-show="historyOpen" class="cand-list">
          <li v-for="c in reviewed" :key="'r' + c.id" class="cand">
            <div>
              <div class="cand-name">{{ c.name }}</div>
              <div class="cand-sub">
                {{ CANDIDATE_STATUS_TEXT[c.status] }} ·
                {{ c.reviewReason || '无说明' }} ·
                {{ shortTime(c.reviewTime) }}
              </div>
            </div>
            <div class="cand-right">
              <span class="tag" :class="{ warn: c.status === 2 }">
                {{ CANDIDATE_STATUS_TEXT[c.status] }}
              </span>
            </div>
          </li>
        </ul>
      </section>

      <!-- ==================== 索引同步（P1-6） ==================== -->
      <section v-show="tab === 'sync'" class="card">
        <div class="card-title-row">
          <h2 class="card-title">索引同步状态</h2>
          <span class="task-hint">pending 多说明索引在追；givenUp 不为 0 就必须人工介入</span>
        </div>

        <div class="dish-stats">
          <div v-for="(v, k) in syncStatus" :key="k">
            <span class="stat-value">{{ v }}</span>
            <span class="stat-label">{{ SYNC_LABEL[k] || k }}</span>
          </div>
        </div>

        <div class="alt-action">
          <p>手动补偿会把「失败待重试 / 已放弃」的待办重置为立刻可执行，并马上跑一轮。</p>
          <button class="primary-btn" type="button" :disabled="busy" @click="retrySync">手动补偿</button>
          <button class="secondary-btn" type="button" :disabled="busy" @click="reindex">
            全量重建索引
          </button>
        </div>
        <p v-if="lastAction" class="cand-sub">{{ lastAction }}</p>

        <div class="rank-seg" style="margin-top:14px" role="tablist" aria-label="待办筛选">
          <button v-for="f in SYNC_FILTERS" :key="'f' + f.value" class="rank-seg-btn" type="button"
                  :aria-selected="String(syncFilter === f.value)"
                  @click="syncFilter = f.value; loadSync()">{{ f.label }}</button>
        </div>

        <ul v-if="syncTasks.length" class="cand-list">
          <li v-for="t in syncTasks" :key="'t' + t.id" class="cand">
            <div>
              <div class="cand-name">菜品 {{ t.dishId }}</div>
              <div class="cand-sub">
                {{ SYNC_STATUS_TEXT[t.status] }} · 已尝试 {{ t.attempts }} 次 ·
                下次 {{ shortTime(t.nextRetryAt) }}
                <template v-if="t.lastError"> · {{ truncate(t.lastError, 60) }}</template>
              </div>
            </div>
            <div class="cand-right">
              <span class="tag" :class="{ warn: t.status === 2, danger: t.status === 3 }">
                {{ SYNC_STATUS_TEXT[t.status] }}
              </span>
            </div>
          </li>
        </ul>
        <p v-else class="cand-sub">这个筛选下没有待办。</p>
      </section>

      <!-- ==================== 营养重算（P2-2） ==================== -->
      <section v-show="tab === 'nutrition'" class="card">
        <div class="card-title-row">
          <h2 class="card-title">营养本地重算</h2>
          <span class="task-hint">把「AI 估算」换成「按食材成分表 × 用量」算出来的值</span>
        </div>
        <p class="cand-sub">
          说明文档要求不把 AI 估算当成实测值，所以两者的差距必须先量出来。
          重算只改营养字段，改完会自动登记索引同步。
        </p>
        <div class="alt-action">
          <p>整库重算大约需要十几秒到一分钟（取决于菜的数量）。</p>
          <button class="primary-btn" type="button" :disabled="busy" @click="recomputeNutrition">
            {{ busy ? '重算中…' : '开始重算' }}
          </button>
        </div>

        <template v-if="nutritionReport">
          <div class="dish-stats" style="margin-top:14px">
            <div><span class="stat-value">{{ nutritionReport.scanned }}</span><span class="stat-label">扫描</span></div>
            <div><span class="stat-value">{{ nutritionReport.updated }}</span><span class="stat-label">改为本地计算</span></div>
            <div><span class="stat-value">{{ nutritionReport.skipped }}</span><span class="stat-label">成分表对不齐</span></div>
            <div><span class="stat-value">{{ nutritionReport.meanDeviationPct }}%</span><span class="stat-label">平均偏差</span></div>
            <div><span class="stat-value">{{ nutritionReport.maxDeviationPct }}%</span><span class="stat-label">最大偏差</span></div>
            <div><span class="stat-value">{{ nutritionReport.localSourceTotal }}</span><span class="stat-label">本地来源总数</span></div>
          </div>
          <p class="cand-sub">
            偏差 = |AI 估算 − 本地计算| ÷ 本地计算。数值大说明模型在营养上不可信，
            这正是要把来源标出来的原因。
          </p>
        </template>
      </section>

      <!-- ==================== 强制下架 ==================== -->
      <section v-show="tab === 'ops'" class="card">
        <div class="card-title-row">
          <h2 class="card-title">强制下架</h2>
          <span class="task-hint">处理违规或明显错误的内容，不需要是该菜的主人</span>
        </div>
        <div class="field">
          <label class="field-hint" for="unpublishId">菜品 ID</label>
          <div class="free-ing-row">
            <input class="input" id="unpublishId" type="text" inputmode="numeric"
                   placeholder="19 位菜品 ID（从详情页地址栏复制）" v-model="unpublishId">
            <button class="secondary-btn" type="button" :disabled="!unpublishId || busy"
                    @click="forceUnpublish">下架</button>
          </div>
        </div>
        <p v-if="lastAction" class="cand-sub">{{ lastAction }}</p>
        <p class="cand-sub">
          下架只改状态（回到草稿）并把它从索引里摘掉，菜本身不删 —— 误操作可以再发布回来。
        </p>
      </section>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   管理台（说明文档 P0-2 / P1-6 / P2-2）

   四块功能放在一页，用分段控件切换：
     ① 食材候选审核 —— 够次数的陌生食材在这里决定通不通过
     ② 索引同步     —— 还欠多少条、要不要手动补偿、要不要全量重建
     ③ 营养重算     —— 把 AI 估算换成按成分表算，并给出偏差统计
     ④ 强制下架     —— 违规内容的兜底处置

   【权限说明】后端对 /ai/es/**、/ai/admin/**、/ai/ingredients/** 一律 fail-closed，
   不是管理员直接 403。所以这一页拿不到数据时，第一反应应该是"账号没角色"，
   而不是"接口坏了"—— 下面的错误提示把这种情况单独说了出来。
   ============================================================ */
import { ref, computed, onMounted } from 'vue'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { AdminApi } from '../api.js'
import { CANDIDATE_STATUS_TEXT } from '../lib/constants.js'
import { toastSuccess } from '../store/toast.js'

const TABS = [
  { key: 'candidates', label: '食材候选' },
  { key: 'sync', label: '索引同步' },
  { key: 'nutrition', label: '营养重算' },
  { key: 'ops', label: '下架处置' }
]

const SYNC_LABEL = {
  pending: '待同步',
  done: '已完成',
  failed: '失败待重试',
  givenUp: '已放弃'
}

const SYNC_STATUS_TEXT = { 0: '待同步', 1: '已完成', 2: '失败待重试', 3: '已放弃' }

const SYNC_FILTERS = [
  { value: '', label: '全部' },
  { value: 0, label: '待同步' },
  { value: 2, label: '失败待重试' },
  { value: 3, label: '已放弃' }
]

const tab = ref('candidates')
const loading = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const lastAction = ref('')

const candidates = ref([])
const reviewed = ref([])
const selected = ref([])          // 存字符串：雪花 ID 用 Number 会丢精度
const reasonInput = ref('')
const historyOpen = ref(true)

const syncStatus = ref({})
const syncTasks = ref([])
const syncFilter = ref('')

const nutritionReport = ref(null)
const unpublishId = ref('')

const subtitle = computed(() => {
  const t = TABS.find(x => x.key === tab.value)
  return t ? ('当前：' + t.label) : '管理员专属'
})

function shortTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(5, 16)
}

function truncate(text, max) {
  const s = String(text || '')
  return s.length > max ? s.slice(0, max) + '…' : s
}

/** 统一的错误出口：403 单独说清楚，否则管理员会以为是接口坏了 */
function handleError(e) {
  const code = e && e.code
  if (code === 403) {
    errorMessage.value = '需要管理员权限：当前账号没有管理角色。'
      + '（后端对管理接口是 fail-closed 的，这不是接口故障）'
  } else {
    errorMessage.value = (e && e.message) || '未知错误'
  }
}

async function loadCandidates() {
  candidates.value = (await AdminApi.ingredientCandidates(20)) || []
  selected.value = []
}

async function loadReviewed() {
  reviewed.value = (await AdminApi.reviewedCandidates(30)) || []
}

async function loadSync() {
  syncStatus.value = (await AdminApi.esSyncStatus()) || {}
  const status = syncFilter.value === '' ? null : syncFilter.value
  syncTasks.value = (await AdminApi.esSyncTasks(status, 30)) || []
}

async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    await loadCandidates()
    await loadReviewed()
    await loadSync()
  } catch (e) {
    handleError(e)
  } finally {
    loading.value = false
  }
}

function switchTab(key) {
  tab.value = key
  errorMessage.value = ''
  lastAction.value = ''
  refresh()
}

async function approve() {
  busy.value = true
  errorMessage.value = ''
  try {
    const report = await AdminApi.approveIngredients(selected.value.map(String), reasonInput.value)
    toastSuccess('已通过 ' + (report && report.approved != null ? report.approved : selected.value.length) + ' 条')
    lastAction.value = '通过后回填了 ' + (report ? report.refilledRows : 0)
      + ' 条历史明细，登记索引同步 ' + (report ? report.queuedDishes : 0) + ' 道菜'
    reasonInput.value = ''
    await loadCandidates()
    await loadReviewed()
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function reject() {
  if (!reasonInput.value || !reasonInput.value.trim()) {
    // 后端也会拦，但没必要让用户白跑一趟接口
    errorMessage.value = '驳回必须写理由：没理由的驳回，下次还会被同一个人提上来'
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    const report = await AdminApi.rejectIngredients(selected.value.map(String), reasonInput.value)
    toastSuccess('已驳回 ' + (report && report.rejected != null ? report.rejected : selected.value.length) + ' 条')
    reasonInput.value = ''
    await loadCandidates()
    await loadReviewed()
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function retrySync() {
  busy.value = true
  errorMessage.value = ''
  try {
    const data = await AdminApi.esSyncRetry()
    lastAction.value = '重置 ' + (data ? data.reset : 0) + ' 条待办，并触发了一轮同步'
    await loadSync()
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function reindex() {
  busy.value = true
  errorMessage.value = ''
  try {
    const report = await AdminApi.reindex()
    lastAction.value = '全量重建：写入 ' + (report ? report.written : '?')
      + ' 条，失败 ' + (report ? report.failed : '?')
      + '，清理 ' + (report ? report.pruned : '?')
      + '，耗时 ' + (report ? Math.round((report.costMs || 0) / 1000) : '?') + ' 秒'
    await loadSync()
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function recomputeNutrition() {
  busy.value = true
  errorMessage.value = ''
  try {
    nutritionReport.value = await AdminApi.recomputeNutrition()
    toastSuccess('营养重算完成')
    await loadSync()
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function forceUnpublish() {
  busy.value = true
  errorMessage.value = ''
  try {
    const message = await AdminApi.forceUnpublish(String(unpublishId.value).trim())
    lastAction.value = message || '已下架'
    toastSuccess('已下架')
    unpublishId.value = ''
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>

<style scoped>
/* 只影响本页的小样式；其余沿用全局样式表，避免出现两套观感 */
.page-admin .dish-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
}
</style>
