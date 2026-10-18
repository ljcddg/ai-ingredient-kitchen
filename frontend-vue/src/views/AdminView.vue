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

      <!-- ==================== 违规词库 ==================== -->
      <section v-show="tab === 'words'" class="card">
        <div class="card-title-row">
          <h2 class="card-title">违规词库</h2>
          <span class="task-hint">命中 level 1 只把词换成星号；level 2 整条进待审</span>
        </div>

        <div class="dish-stats">
          <div><span class="stat-value">{{ wordOverview.total || 0 }}</span><span class="stat-label">词条总数</span></div>
          <div><span class="stat-value">{{ wordOverview.enabled || 0 }}</span><span class="stat-label">已启用</span></div>
          <div><span class="stat-value">{{ wordOverview.loaded || 0 }}</span><span class="stat-label">内存已载入</span></div>
          <div><span class="stat-value">{{ wordOverview.blocking || 0 }}</span><span class="stat-label">拦截级</span></div>
        </div>

        <!-- 试跑：改完词库先拿真实语句验一遍，比看词表靠谱 -->
        <div class="field" style="margin-top:14px">
          <label class="field-hint" for="wordTest">试跑检测（用当前生效的词库扫一段文字）</label>
          <div class="free-ing-row">
            <input class="input" id="wordTest" type="text" maxlength="200"
                   placeholder="例如：这道菜真不错，想学的加微信 abc123"
                   v-model="testText" @keydown.enter="runWordTest">
            <button class="secondary-btn" type="button" :disabled="!testText || busy" @click="runWordTest">
              检测
            </button>
          </div>
        </div>

        <div v-if="testResult" class="word-test-result">
          <p class="cand-sub" style="margin:0">
            命中 <b>{{ testResult.hitCount }}</b> 个词
            <span v-if="testResult.blocked" class="tag danger" style="margin-left:6px">整条拦截待审</span>
            <span v-else-if="testResult.hitCount" class="tag warn" style="margin-left:6px">替换后照常发布</span>
            <span v-else class="tag" style="margin-left:6px">不会拦截</span>
          </p>
          <p class="word-test-line"><span class="word-test-label">替换后</span>{{ testResult.masked }}</p>
          <p v-if="testResult.hits && testResult.hits.length" class="word-test-hits">
            <span v-for="h in testResult.hits" :key="h.word" class="tag"
                  :class="WORD_CATEGORY_TONE[h.category] || ''" style="margin-right:6px">
              {{ h.word }} · {{ WORD_CATEGORY_TEXT[h.category] || h.category }} · L{{ h.level }}
            </span>
          </p>
        </div>

        <!-- 新增 -->
        <div class="word-form">
          <input class="input" type="text" maxlength="64" placeholder="违规词" v-model="newWord.word">
          <select class="input" v-model="newWord.category">
            <option v-for="(label, code) in WORD_CATEGORY_TEXT" :key="code" :value="code">{{ label }}</option>
          </select>
          <select class="input" v-model.number="newWord.level">
            <option :value="1">替换为星号</option>
            <option :value="2">整条拦截</option>
          </select>
          <input class="input" type="text" maxlength="100" placeholder="备注（为什么收录，可空）" v-model="newWord.remark">
          <button class="primary-btn" type="button" :disabled="!newWord.word || busy" @click="addWord">加入词库</button>
        </div>

        <!-- 检索 -->
        <div class="word-form" style="margin-top:10px">
          <input class="input" type="text" maxlength="64" placeholder="按词模糊检索" v-model="wordQuery.keyword"
                 @keydown.enter="loadWords(1)">
          <select class="input" v-model="wordQuery.category">
            <option value="ALL">全部类别</option>
            <option v-for="(label, code) in WORD_CATEGORY_TEXT" :key="code" :value="code">{{ label }}</option>
          </select>
          <select class="input" v-model="wordQuery.level">
            <option value="">全部级别</option>
            <option :value="1">替换为星号</option>
            <option :value="2">整条拦截</option>
          </select>
          <select class="input" v-model="wordQuery.enabled">
            <option value="">全部状态</option>
            <option :value="1">已启用</option>
            <option :value="0">已停用</option>
          </select>
          <button class="secondary-btn" type="button" :disabled="busy" @click="loadWords(1)">检索</button>
        </div>

        <div v-if="!words.length" class="empty">
          <p class="empty-title">这个条件下没有词条</p>
          <p class="empty-text">换个关键词或放宽筛选；还没导入词库的话，先跑一遍 sql/import-sensitive-lexicon.py。</p>
        </div>

        <ul v-else class="cand-list">
          <li v-for="w in words" :key="w.id" class="cand">
            <div style="flex:1">
              <div class="cand-name">
                {{ w.word }}
                <span class="tag" :class="WORD_CATEGORY_TONE[w.category] || ''" style="margin-left:8px">
                  {{ WORD_CATEGORY_TEXT[w.category] || w.category }}
                </span>
                <span class="tag" :class="{ danger: w.level === 2 }" style="margin-left:6px">
                  {{ WORD_LEVEL_TEXT[w.level] }}
                </span>
                <span v-if="w.enabled === 0" class="tag warn" style="margin-left:6px">已停用</span>
              </div>
              <div class="cand-sub">
                命中 {{ w.hitCount || 0 }} 次 · {{ w.source || 'manual' }}
                <template v-if="w.remark"> · {{ w.remark }}</template>
              </div>
            </div>
            <div class="cand-right" style="gap:8px;display:flex">
              <button class="ghost-btn" type="button" :disabled="busy" @click="toggleWord(w)">
                {{ w.enabled === 1 ? '停用' : '启用' }}
              </button>
              <button class="ghost-btn" type="button" :disabled="busy" @click="removeWord(w)">删除</button>
            </div>
          </li>
        </ul>

        <div v-if="wordPage.total > wordPage.size" class="alt-action">
          <p>共 {{ wordPage.total }} 条，第 {{ wordPage.page }} / {{ Math.ceil(wordPage.total / wordPage.size) }} 页</p>
          <button class="secondary-btn" type="button" :disabled="wordPage.page <= 1 || busy"
                  @click="loadWords(wordPage.page - 1)">上一页</button>
          <button class="secondary-btn" type="button"
                  :disabled="wordPage.page * wordPage.size >= wordPage.total || busy"
                  @click="loadWords(wordPage.page + 1)">下一页</button>
        </div>

        <p v-if="lastAction" class="cand-sub">{{ lastAction }}</p>
        <p class="cand-sub">
          停用优于删除：历史评论的 hit_words 里还记着这个词，删掉词条之后「当初为什么屏蔽」就查不到了。
        </p>
      </section>

      <!-- ==================== 举报审核 ==================== -->
      <section v-show="tab === 'reports'" class="card">
        <div class="card-title-row">
          <h2 class="card-title">内容审核</h2>
          <span class="task-hint">举报只把内容推进队列，下架与否由人决定</span>
        </div>

        <div class="rank-seg" role="tablist" aria-label="审核类型">
          <button v-for="m in REVIEW_MODES" :key="m.key" class="rank-seg-btn" type="button"
                  :aria-selected="String(reviewMode === m.key)"
                  @click="reviewMode = m.key; refreshReview()">{{ m.label }}</button>
        </div>

        <!-- ---- 举报 ---- -->
        <template v-if="reviewMode === 'reports'">
          <div class="rank-seg" style="margin-top:10px" role="tablist" aria-label="举报状态">
            <button v-for="f in REPORT_FILTERS" :key="'r' + f.value" class="rank-seg-btn" type="button"
                    :aria-selected="String(reportStatus === f.value)"
                    @click="reportStatus = f.value; loadReports(1)">{{ f.label }}</button>
          </div>

          <div v-if="!reports.length" class="empty">
            <p class="empty-title">这个状态下没有举报</p>
            <p class="empty-text">评论区的「举报」入口提交后会出现在这里；待处理为 0 说明队列清空了。</p>
          </div>

          <ul v-else class="report-list">
            <li v-for="r in reports" :key="r.id" class="report-item">
              <div class="report-head">
                <span class="tag danger">{{ REPORT_REASON_TEXT[r.reasonType] || '其他' }}</span>
                <span class="comment-time">举报人 {{ r.reporterName || ('用户' + r.reporterId) }} · {{ r.createTime }}</span>
                <span v-if="r.reportCount > 1" class="tag warn">该评论共被举报 {{ r.reportCount }} 次</span>
              </div>
              <p class="report-content">{{ r.commentContent }}</p>
              <p v-if="r.reason" class="cand-sub">补充说明：{{ r.reason }}</p>
              <p class="cand-sub">
                评论作者 {{ r.commentAuthor || '—' }} · 菜品「{{ r.dishName || r.dishId }}」 ·
                评论状态 {{ COMMENT_STATUS_TEXT[r.commentStatus] || r.commentStatus }}
                <template v-if="r.hitWords"> · 命中词：{{ r.hitWords }}</template>
              </p>
              <div v-if="r.status === 0" class="alt-action" style="margin-top:8px">
                <input class="input" type="text" maxlength="100" placeholder="处理说明（建议写依据）"
                       v-model="handleRemark[r.id]">
                <button class="primary-btn" type="button" :disabled="busy" @click="handleReport(r, 'remove')">
                  下架评论
                </button>
                <button class="secondary-btn" type="button" :disabled="busy" @click="handleReport(r, 'reject')">
                  驳回举报
                </button>
              </div>
              <p v-else class="cand-sub">
                {{ REPORT_STATUS_TEXT[r.status] }}
                <template v-if="r.handleRemark"> · {{ r.handleRemark }}</template>
                <template v-if="r.handleTime"> · {{ r.handleTime }}</template>
              </p>
            </li>
          </ul>

          <div v-if="reportPage.total > reportPage.size" class="alt-action">
            <p>共 {{ reportPage.total }} 条，第 {{ reportPage.page }} / {{ Math.ceil(reportPage.total / reportPage.size) }} 页</p>
            <button class="secondary-btn" type="button" :disabled="reportPage.page <= 1 || busy"
                    @click="loadReports(reportPage.page - 1)">上一页</button>
            <button class="secondary-btn" type="button"
                    :disabled="reportPage.page * reportPage.size >= reportPage.total || busy"
                    @click="loadReports(reportPage.page + 1)">下一页</button>
          </div>
        </template>

        <!-- ---- 待审评论 ---- -->
        <template v-else>
          <div v-if="!pendingComments.length" class="empty">
            <p class="empty-title">没有待审评论</p>
            <p class="empty-text">
              两类内容会进这里：命中「整条拦截」级违规词的，以及被举报达到 3 次的。
              它们都不会出现在详情页，等你决定放行还是隐藏。
            </p>
          </div>

          <ul v-else class="report-list">
            <li v-for="c in pendingComments" :key="c.id" class="report-item">
              <div class="report-head">
                <span class="tag danger">待审核</span>
                <span class="comment-time">{{ c.nickname || ('用户' + c.userId) }} · {{ c.createTime }}</span>
                <span v-if="c.hitWords" class="tag warn">命中：{{ c.hitWords }}</span>
                <span v-if="c.reportCount" class="tag warn">被举报 {{ c.reportCount }} 次</span>
              </div>
              <p class="report-content">{{ c.content }}</p>
              <p class="cand-sub">菜品「{{ c.dishName || c.dishId }}」</p>
              <div class="alt-action" style="margin-top:8px">
                <input class="input" type="text" maxlength="100" placeholder="处置说明（可空）"
                       v-model="handleRemark[c.id]">
                <button class="primary-btn" type="button" :disabled="busy" @click="reviewComment(c, 'approve')">
                  放行
                </button>
                <button class="secondary-btn" type="button" :disabled="busy" @click="reviewComment(c, 'hide')">
                  隐藏
                </button>
              </div>
            </li>
          </ul>

          <div v-if="pendingPage.total > pendingPage.size" class="alt-action">
            <p>共 {{ pendingPage.total }} 条，第 {{ pendingPage.page }} / {{ Math.ceil(pendingPage.total / pendingPage.size) }} 页</p>
            <button class="secondary-btn" type="button" :disabled="pendingPage.page <= 1 || busy"
                    @click="loadPending(pendingPage.page - 1)">上一页</button>
            <button class="secondary-btn" type="button"
                    :disabled="pendingPage.page * pendingPage.size >= pendingPage.total || busy"
                    @click="loadPending(pendingPage.page + 1)">下一页</button>
          </div>
        </template>

        <p v-if="lastAction" class="cand-sub" style="margin-top:10px">{{ lastAction }}</p>
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
import { ref, reactive, computed, onMounted } from 'vue'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { AdminApi } from '../api.js'
import {
  CANDIDATE_STATUS_TEXT,
  WORD_CATEGORY_TEXT,
  WORD_CATEGORY_TONE,
  WORD_LEVEL_TEXT,
  REPORT_REASON_TEXT,
  REPORT_STATUS_TEXT,
  COMMENT_STATUS_TEXT
} from '../lib/constants.js'
import { toastSuccess } from '../store/toast.js'

const TABS = [
  { key: 'candidates', label: '食材候选' },
  { key: 'words', label: '违规词库' },
  { key: 'reports', label: '举报审核' },
  { key: 'sync', label: '索引同步' },
  { key: 'nutrition', label: '营养重算' },
  { key: 'ops', label: '下架处置' }
]

const REVIEW_MODES = [
  { key: 'reports', label: '举报' },
  { key: 'pending', label: '待审评论' }
]

const REPORT_FILTERS = [
  { value: 0, label: '待处理' },
  { value: 1, label: '已受理' },
  { value: 2, label: '已驳回' }
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

/* ---------------- 违规词库 ---------------- */
const wordOverview = ref({})
const words = ref([])
const wordPage = ref({ page: 1, size: 20, total: 0 })
const wordQuery = reactive({ keyword: '', category: 'ALL', level: '', enabled: '' })
const newWord = reactive({ word: '', category: 'AD', level: 1, remark: '' })
const testText = ref('')
const testResult = ref(null)

/* ---------------- 举报审核 ---------------- */
const reviewMode = ref('reports')
const reportStatus = ref(0)
const reports = ref([])
const reportPage = ref({ page: 1, size: 20, total: 0 })
const pendingComments = ref([])
const pendingPage = ref({ page: 1, size: 20, total: 0 })
/** 处理说明按「举报ID / 评论ID」分开存，否则同一页多条会互相串味 */
const handleRemark = reactive({})

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

/* ---------------- 违规词库 ---------------- */

async function loadWordOverview() {
  wordOverview.value = (await AdminApi.sensitiveWordOverview()) || {}
}

async function loadWords(page) {
  errorMessage.value = ''
  try {
    const result = await AdminApi.sensitiveWords({
      keyword: wordQuery.keyword,
      category: wordQuery.category,
      level: wordQuery.level,
      enabled: wordQuery.enabled,
      page: page || 1,
      size: 20
    })
    words.value = (result && result.records) || []
    wordPage.value = {
      page: (result && result.page) || 1,
      size: (result && result.size) || 20,
      total: (result && result.total) || 0
    }
  } catch (e) {
    handleError(e)
  }
}

async function runWordTest() {
  if (!testText.value) return
  busy.value = true
  errorMessage.value = ''
  try {
    testResult.value = await AdminApi.testSensitiveWords(testText.value)
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function addWord() {
  if (!newWord.word || !newWord.word.trim()) {
    errorMessage.value = '先填一个词'
    return
  }
  busy.value = true
  errorMessage.value = ''
  try {
    await AdminApi.createSensitiveWord({
      word: newWord.word.trim(),
      category: newWord.category,
      level: newWord.level,
      remark: newWord.remark || null
    })
    toastSuccess('已加入词库，下一次评论立刻生效')
    lastAction.value = '已加入「' + newWord.word.trim() + '」，内存词库已重建'
    newWord.word = ''
    newWord.remark = ''
    await loadWordOverview()
    await loadWords(1)
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function toggleWord(word) {
  busy.value = true
  errorMessage.value = ''
  try {
    const next = word.enabled !== 1
    await AdminApi.setSensitiveWordEnabled(word.id, next)
    toastSuccess(next ? '已启用' : '已停用')
    await loadWordOverview()
    await loadWords(wordPage.value.page)
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function removeWord(word) {
  busy.value = true
  errorMessage.value = ''
  try {
    await AdminApi.deleteSensitiveWord(word.id)
    toastSuccess('已删除')
    lastAction.value = '已删除「' + word.word + '」。注意：历史评论里的命中记录不会再显示这个词'
    await loadWordOverview()
    await loadWords(wordPage.value.page)
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

/* ---------------- 举报审核 ---------------- */

async function loadReports(page) {
  errorMessage.value = ''
  try {
    const result = await AdminApi.commentReports(reportStatus.value, page || 1, 20)
    reports.value = (result && result.records) || []
    reportPage.value = {
      page: (result && result.page) || 1,
      size: (result && result.size) || 20,
      total: (result && result.total) || 0
    }
  } catch (e) {
    handleError(e)
  }
}

async function loadPending(page) {
  errorMessage.value = ''
  try {
    const result = await AdminApi.pendingComments(page || 1, 20)
    pendingComments.value = (result && result.records) || []
    pendingPage.value = {
      page: (result && result.page) || 1,
      size: (result && result.size) || 20,
      total: (result && result.total) || 0
    }
  } catch (e) {
    handleError(e)
  }
}

async function refreshReview() {
  lastAction.value = ''
  if (reviewMode.value === 'reports') {
    await loadReports(reportPage.value.page)
  } else {
    await loadPending(pendingPage.value.page)
  }
}

async function handleReport(row, action) {
  busy.value = true
  errorMessage.value = ''
  try {
    const result = await AdminApi.handleReport(row.id, action, handleRemark[row.id])
    if (action === 'remove') {
      toastSuccess('已下架该评论')
      lastAction.value = '已下架评论；同一条评论的其他待处理举报一并关闭 '
        + ((result && result.closedOthers) || 0) + ' 条'
    } else {
      toastSuccess('已驳回举报')
      lastAction.value = '已驳回这条举报，评论保持原样'
    }
    handleRemark[row.id] = ''
    await loadReports(reportPage.value.page)
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

async function reviewComment(row, action) {
  busy.value = true
  errorMessage.value = ''
  try {
    const result = await AdminApi.reviewComment(row.id, action, handleRemark[row.id])
    toastSuccess(action === 'approve' ? '已放行' : '已隐藏')
    lastAction.value = action === 'approve'
      ? '已放行；连带驳回待处理举报 ' + ((result && result.closedReports) || 0) + ' 条'
      : '已隐藏，详情页不再展示'
    handleRemark[row.id] = ''
    await loadPending(pendingPage.value.page)
  } catch (e) {
    handleError(e)
  } finally {
    busy.value = false
  }
}

/**
 * 按当前 tab 拉对应的数据。
 *
 * 原来这里不分三七二十一把候选、同步都拉一遍 —— 管理台有 6 个 tab 之后，
 * 每切一次都要发三个请求，而且其中两个的数据当前页面根本不显示。
 * 按需加载的代价只是每个分支各写一行。
 */
async function refresh() {
  loading.value = true
  errorMessage.value = ''
  try {
    if (tab.value === 'candidates') {
      await loadCandidates()
      await loadReviewed()
    } else if (tab.value === 'sync' || tab.value === 'nutrition') {
      await loadSync()
    } else if (tab.value === 'words') {
      await loadWordOverview()
      await loadWords(wordPage.value.page)
    } else if (tab.value === 'reports') {
      await refreshReview()
    }
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

/* 分段控件：样式原本只写在 .page-rank 下，管理台这套按钮一直是裸的。
   顺手按同一套视觉补上，并允许换行 —— 六个 tab 在窄屏必须能折成两排。 */
.page-admin .rank-seg {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 3px;
  margin-bottom: 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface-2);
}

.page-admin .rank-seg-btn {
  flex: 1 1 auto;
  min-width: 74px;
  padding: 9px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-2);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: background .16s ease, color .16s ease;
}

.page-admin .rank-seg-btn:hover {
  color: var(--ink);
}

.page-admin .rank-seg-btn[aria-selected="true"] {
  background: var(--surface);
  color: var(--ink);
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(34, 32, 30, .08);
}

/* 违规词：新增 / 检索共用一行式表单，窄屏自动换行 */
.word-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.word-form .input {
  flex: 1 1 140px;
  min-width: 120px;
}

.word-form .primary-btn,
.word-form .secondary-btn {
  flex: 0 0 auto;
}

/* 试跑结果：用等宽字体展示「替换后」，星号的位置才对得齐 */
.word-test-result {
  margin-top: 10px;
  padding: 10px 14px;
  background: #f7f9fc;
  border: 1px solid #d6dce5;
  border-radius: 8px;
}

.word-test-line {
  margin: 6px 0 0;
  font-family: Consolas, monospace;
  font-size: 15px;
  color: #1a2230;
  word-break: break-all;
}

.word-test-label {
  display: inline-block;
  margin-right: 8px;
  padding: 0 6px;
  border-radius: 4px;
  background: #e8eff8;
  color: #1e4fa8;
  font-family: inherit;
  font-size: 13px;
}

.word-test-hits {
  margin: 6px 0 0;
}

/* 举报 / 待审：一张卡片一条，卡片内部按「标签行 → 正文 → 元信息 → 操作」排 */
.report-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.report-item {
  padding: 12px 14px;
  border: 1px solid #e2e7ef;
  border-radius: 8px;
  background: #fff;
}

.report-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

/* 评论原文可能很长，限高滚动而不是把整页撑开 */
.report-content {
  margin: 0;
  padding: 8px 10px;
  background: #f7f9fc;
  border-left: 3px solid #d9534f;
  border-radius: 4px;
  font-size: 15px;
  line-height: 1.55;
  color: #1a2230;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow-y: auto;
}

.report-item .alt-action {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.report-item .alt-action .input {
  flex: 1 1 200px;
}
</style>
