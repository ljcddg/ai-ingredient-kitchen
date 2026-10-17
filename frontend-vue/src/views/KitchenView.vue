<template>
  <div class="page-kitchen">
    <FxBg />
    <TopBar current="kitchen" />

    <main class="workbench">
      <!-- ==================== 左栏：选择区 ==================== -->
      <section class="panel picker" aria-labelledby="pickerHead">
        <h1 class="sr-only" id="pickerHead">选择食材与用餐偏好</h1>

        <!-- 字段区独立滚动，提交按钮固定在面板底部，永远可见 -->
        <div class="picker-scroll">
          <div class="field">
            <div class="field-head">
              <span class="field-label">食材</span>
              <span class="field-hint">点选，或直接输入</span>
            </div>

            <!-- 搜索：食材表有 88 种，输入即过滤，清空即还原，不需要点按钮 -->
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
                  <!-- 【选中态必须从 state 推导，不能写死】原生版搜索会重建 DOM，
                       写 false 会让「搜一下再清空」把已选状态在界面上全抹掉。
                       Vue 下 aria-pressed 直接绑定 state，这个坑从根上不存在了。 -->
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

            <!-- 自由输入：列表里没有的食材直接敲名字。
                 AI 照样能用上它并写进菜谱，只是它不参与营养校验、也进不了倒排索引。 -->
            <div class="free-ing">
              <label class="sr-only" for="freeIngInput">输入列表里没有的食材</label>
              <div class="free-ing-row">
                <input class="input" id="freeIngInput" type="text" autocomplete="off"
                       placeholder="列表里没有？直接输入，如「松露」" maxlength="20"
                       v-model="freeInput" @input="onFreeInput" @keydown="onFreeKeydown">
                <button class="secondary-btn" id="addFreeIngBtn" type="button"
                        @click="addFreeIngredient(freeInput)">添加</button>
              </div>
              <!-- 联想下拉：只补全主数据里已有的食材，避免「西红柿」和「番茄」被当成两样 -->
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
            </div>
          </div>

          <div class="field">
            <div class="field-head">
              <span class="field-label">口味</span>
              <span class="field-hint">可跳过</span>
            </div>
            <div class="chips" id="tasteChips">
              <button v-for="t in TASTE_TAGS" :key="t" type="button" class="chip"
                      :aria-pressed="String(tastes.includes(t))" @click="toggleTaste(t)">{{ t }}</button>
            </div>
          </div>

          <div class="field">
            <div class="field-head">
              <span class="field-label">人数</span>
            </div>
            <div class="segments" id="servingSegs">
              <button v-for="o in SERVING_OPTIONS" :key="o.value" type="button" class="seg"
                      :aria-pressed="String(servings === o.value)"
                      @click="servings = o.value">{{ o.label }}</button>
            </div>
          </div>

          <!-- 找菜模式（P1-2）：严格 = 只推"现在就能做"的；宽松 = 允许缺几样主料 -->
          <div class="field">
            <div class="field-head">
              <span class="field-label">找菜模式</span>
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

          <!-- 更多条件：忌口 / 时长 / 必用 / 厨具。
               前两个「找菜」和「生成」都生效，后两个只影响生成（搜索不做厨具过滤） -->
          <div class="field">
            <div class="field-head">
              <span class="field-label">更多条件</span>
              <button class="ghost-btn" type="button" id="moreToggle"
                      :aria-expanded="String(moreOpen)" @click="moreOpen = !moreOpen">
                {{ moreOpen ? '收起' : '展开' }}
              </button>
            </div>

            <template v-if="moreOpen">
              <label class="field-hint" for="excludeInput">不吃 / 忌口（找菜与生成都会避开）</label>
              <div class="free-ing-row">
                <input class="input" id="excludeInput" type="text" autocomplete="off"
                       placeholder="如「香菜」，多个用逗号隔开" maxlength="40"
                       v-model="excludeInput" @keydown.enter.prevent="addExcludes(excludeInput)">
                <button class="secondary-btn" type="button"
                        @click="addExcludes(excludeInput)">加入</button>
              </div>
              <div v-if="excludeNames.length" class="picked" id="excludeChips">
                <button v-for="n in excludeNames" :key="'ex' + n" type="button" class="picked-chip"
                        :aria-label="'移除忌口 ' + n" @click="removeExclude(n)">
                  <span>{{ n }}</span>
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8"
                       stroke-linecap="round" aria-hidden="true"><path d="M3 3 9 9M9 3 3 9"/></svg>
                </button>
              </div>

              <label class="field-hint" for="cookTimeInput" style="margin-top:10px">
                最长总耗时（分钟，留空不限）
              </label>
              <input class="input" id="cookTimeInput" type="number" min="5" max="180" step="5"
                     placeholder="留空表示不限" v-model.number="maxCookTimeMin">

              <label class="field-hint" style="margin-top:10px">
                必用食材（点一下标记，只影响 AI 生成）
              </label>
              <div v-if="sortedSelected.length" class="chips" id="mustUseChips">
                <button v-for="id in sortedSelected" :key="'mu' + id" type="button" class="chip"
                        :data-id="id"
                        :aria-pressed="String(effectiveMustUse.includes(id))"
                        @click="toggleMustUse(id)">{{ nameOfIngredient(id) }}</button>
              </div>
              <p v-else class="field-hint">先在上面选几样食材</p>

              <label class="field-hint" for="equipInput" style="margin-top:10px">
                可用厨具（只影响 AI 生成）
              </label>
              <div class="free-ing-row">
                <input class="input" id="equipInput" type="text" autocomplete="off"
                       placeholder="如「炒锅」，多个用逗号隔开" maxlength="40"
                       v-model="equipmentInput" @keydown.enter.prevent="addEquipments(equipmentInput)">
                <button class="secondary-btn" type="button"
                        @click="addEquipments(equipmentInput)">加入</button>
              </div>
              <div v-if="equipment.length" class="picked" id="equipChips">
                <button v-for="n in equipment" :key="'eq' + n" type="button" class="picked-chip"
                        :aria-label="'移除厨具 ' + n" @click="removeEquipment(n)">
                  <span>{{ n }}</span>
                  <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8"
                       stroke-linecap="round" aria-hidden="true"><path d="M3 3 9 9M9 3 3 9"/></svg>
                </button>
              </div>
            </template>
          </div>
        </div>

        <div class="picker-actions">
          <button class="submit" id="searchBtn" type="button"
                  :disabled="pickedCount === 0 || loading" @click="doSearch">
            {{ loading ? '正在找…' : '开始找菜' }}
          </button>
          <button class="picker-secondary" id="generateBtn" type="button"
                  :disabled="pickedCount === 0 || genBusy" @click="submitGenerate">
            {{ genBusy ? '正在提交…' : '跳过查找，直接让 AI 做' }}
          </button>
          <!-- 手写菜谱：不依赖 AI。手边这几样食材已经选在上面了，直接搬进表单预填 -->
          <button class="picker-secondary" id="manualOpenBtn" type="button" @click="openManual">
            手写菜谱（自己写一道）
          </button>
          <!-- 拍照识菜：拍/选几张照片 → YOLO 认出食材 → 勾选 → 回到本页预填。
               不开新窗口：这本来就属于"选手边食材"这一步的另一种输入方式。
               设计文档 docs/yolo-vision-design.md §7 -->
          <router-link class="picker-secondary" id="visionOpenBtn" to="/vision">
            📷 拍照识菜（照片认食材）
          </router-link>
          <p v-if="visionNote" class="picker-note" id="pickerVisionNote">{{ visionNote }}</p>
          <p v-if="freeNote" class="picker-note" id="pickerFreeNote">{{ freeNote }}</p>
          <p class="submit-note" id="submitNote" :style="noteStyle">{{ noteText }}</p>
        </div>
      </section>

      <!-- ==================== 右栏 ==================== -->
      <div class="right-col">
        <!-- 任务区：只在我有任务时出现。放在结果区上方，因为它是"进行中"的 -->
        <section id="taskSection" :hidden="!tasks.length" aria-labelledby="taskHead">
          <div class="card">
            <div class="card-title-row">
              <h2 class="card-title" id="taskHead">我的 AI 做菜任务</h2>
              <span class="task-hint" id="taskHint">{{ taskHint }}</span>
            </div>
            <ul class="task-list" id="taskList">
              <!-- 固定高度面板的占位行：没有进行中的任务时撑住面板，
                   不然卡片只剩标题一条，看着又空又矮 -->
              <li v-if="!openTasks.length" class="task-empty">
                没有进行中的任务。选好食材点「让 AI 做菜」，提交后会在这里排队，做完会亮起来。
              </li>
              <!-- 进行中 + 刚做好还没看过的：都展开。
                   「完成」不等于「用户知道了」—— 先摆出来，
                   点进去看过（@view）之后才收进下面的折叠区 -->
              <TaskRow v-for="t in openTasks" :key="t.taskId" :task="t"
                       :up="canUp(t)" :down="canDown(t)"
                       @action="onTaskAction" @view="markViewed" />

              <!-- 已结束：看过的 / 失败 / 取消 / 中断，收成一行。
                   只要还有已结束的任务就保留这行 —— 否则全是「刚做好」时，
                   清空入口会跟着消失 -->
              <li v-if="hasTerminal" class="task-group-head">
                <button v-if="foldedTasks.length" class="task-group-toggle" id="finishedToggle"
                        type="button" :aria-expanded="String(finishedOpen)"
                        @click="finishedOpen = !finishedOpen">
                  <svg class="task-group-arrow" :class="{ 'is-open': finishedOpen }"
                       viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6"
                       stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M4.5 2.5 8 6l-3.5 3.5"/>
                  </svg>
                  <span>已结束 {{ foldedTasks.length }} 条</span>
                </button>
                <span v-else class="task-group-note">刚做好的还没看</span>
                <button class="task-group-clear" id="clearFinishedBtn" type="button"
                        title="清掉所有已结束的任务记录（菜品不受影响）"
                        :disabled="clearingFinished" @click="clearFinished">
                  {{ clearingFinished ? '清理中…' : '清空' }}
                </button>
              </li>

              <template v-if="finishedOpen">
                <TaskRow v-for="t in foldedTasks" :key="t.taskId" :task="t"
                         @action="onTaskAction" @view="markViewed" />
              </template>
            </ul>
          </div>
        </section>

        <!-- 结果区。aria-live 让结果刷新时屏幕阅读器能播报
             （任务区没有放在 aria-live 里，否则轮询会一直打断播报）

             结构：results 是 flex 列 —— 中间 .results-body 自己滚，
             底部分页器钉住不动。这样翻页按钮永远在同一个位置，
             不用先滚到底才找得到。 -->
        <section class="results" id="results" aria-labelledby="resultTitle" aria-live="polite">
         <div class="results-body">
          <!-- 还没开始 -->
          <template v-if="resultView === 'empty'">
            <div class="result-head"><h2 class="result-title" id="resultTitle">结果</h2></div>
            <div class="empty">
              <p class="empty-title">还没有开始找</p>
              <p class="empty-text">从左边挑几样手边有的食材，点「开始找菜」，我先去库里看看有没有现成的做法，没有再让 AI 现场想。列表里没有的食材可以直接输入。</p>
            </div>
          </template>

          <!-- 骨架 -->
          <template v-else-if="resultView === 'skeleton'">
            <div class="result-head"><h2 class="result-title" id="resultTitle">正在找菜…</h2></div>
            <div class="skeleton">
              <div class="sk-line sk-title"></div>
              <div class="sk-line"></div>
              <div class="sk-line sk-short"></div>
              <div class="sk-stats"><span class="sk-stat"></span><span class="sk-stat"></span><span class="sk-stat"></span></div>
            </div>
          </template>

          <!-- 出错 -->
          <template v-else-if="resultView === 'error'">
            <div class="result-head"><h2 class="result-title" id="resultTitle">没能拿到结果</h2></div>
            <div class="err-box">
              <p class="err-title">请求失败</p>
              <p class="err-text">{{ resultError }}</p>
              <button class="secondary-btn" id="retryBtn" type="button" @click="doSearch()">重试</button>
            </div>
          </template>

          <!-- 结果 -->
          <template v-else-if="result">
            <div class="result-head">
              <h2 class="result-title" id="resultTitle">{{ resultTitle }}</h2>
              <span class="result-meta">{{ pickedNames.join('、') }}</span>
            </div>

            <!-- 状态条：三种情况分开说清楚，别让用户猜 -->
            <div v-if="!result.searchEngineAvailable" class="banner danger">
              <div>
                <p class="banner-title">检索引擎连不上</p>
                <p class="banner-text">现在查不了已有菜品，返回的内容可能不完整。稍后再试一次。</p>
              </div>
            </div>
            <div v-else-if="result.directReturn" class="banner ok">
              <div>
                <p class="banner-title">库里有现成的做法</p>
                <p class="banner-text">你选的食材刚好能凑成下面这道，不用重新生成。</p>
              </div>
            </div>
            <div v-else class="banner warn">
              <div>
                <!-- 三档决策：RAG_GENERATE 说明"库里有相似菜可以参照"，比笼统的"没有现成的"更有信息量 -->
                <p class="banner-title">
                  {{ result.decision === 'RAG_GENERATE' ? '没有现成的，但找到了相似的' : '暂时没有现成的搭配' }}
                </p>
                <p v-if="result.missReason" class="banner-text">{{ result.missReason }}</p>
                <p v-if="result.decisionHint" class="banner-text">{{ result.decisionHint }}</p>
                <!-- ⑩ 放宽建议：每条都对应一个真实筛选项，点了就能少看几条限制 -->
                <ul v-if="result.relaxHints && result.relaxHints.length" class="relax-list">
                  <li v-for="(hint, hi) in result.relaxHints" :key="hi">{{ hint }}</li>
                </ul>
              </div>
            </div>

            <!-- best 是"全局最优"（后端取 ranked[0]，与页码无关），每页都会回传。
                 所以只在第 1 页用它做大卡片，翻到后面几页时它就成了重复项 -->
            <article v-if="result.best && page === 1" class="dish-main">
              <h3 class="dish-name">{{ result.best.name }}</h3>
              <div class="hit-row">
                <span class="hit-label">命中 {{ result.best.hitCount }} / {{ totalRequested }} 种食材</span>
                <!-- 命中刻度：每格对应一样用户选的食材，所以这个视觉是有语义的 -->
                <span class="hit-meter" role="img"
                      :aria-label="'命中 ' + result.best.hitCount + ' / ' + totalRequested + ' 种食材'">
                  <span v-for="i in totalRequested" :key="i" class="hit-seg"
                        :class="{ 'is-hit': i <= result.best.hitCount }"></span>
                </span>
              </div>
              <!-- P1-2 的两个口径要一起看：
                   命中率 = 你的食材被用掉多少；满足率 = 这道菜现在能不能做。
                   只看命中率会推荐出一堆"看着很搭、其实还差好几样"的菜 -->
              <div class="hit-row">
                <span class="hit-label">{{ missingText(result.best) }}</span>
                <span class="tag" :class="{ warn: (result.best.missingCount || 0) > 0 }">
                  主料满足 {{ Math.round((result.best.satisfactionRate || 0) * 100) }}%
                </span>
              </div>
              <p v-if="substituteHint(result.substitutes)" class="subst-hint">
                缺的能换：{{ substituteHint(result.substitutes) }}
              </p>
              <p v-if="result.best.reason" class="cand-sub">{{ result.best.reason }}</p>
              <div class="dish-stats">
                <div v-if="result.best.ratingCount > 0">
                  <span class="stat-value">{{ Number(result.best.ratingAvg || 0).toFixed(1) }}</span>
                  <span class="stat-label">{{ result.best.ratingCount }} 人评价</span>
                </div>
                <div v-if="result.best.collectCount > 0">
                  <span class="stat-value">{{ result.best.collectCount }}</span>
                  <span class="stat-label">人收藏</span>
                </div>
                <div v-if="result.best.cookTimeMin">
                  <span class="stat-value">{{ result.best.cookTimeMin }} 分</span>
                  <span class="stat-label">预计用时</span>
                </div>
                <div v-if="result.best.difficulty">
                  <span class="stat-value">{{ DIFFICULTY_TEXT[result.best.difficulty] || result.best.difficulty }}</span>
                  <span class="stat-label">难度</span>
                </div>
                <div v-if="result.best.energyKcal">
                  <span class="stat-value">{{ Math.round(result.best.energyKcal) }}</span>
                  <span class="stat-label">千卡 / 份</span>
                </div>
              </div>
              <!-- 详情页入口。召回结果里的 dishId 就是 t_dish 的主键 -->
              <div v-if="result.best.dishId" class="dish-main-foot">
                <router-link class="secondary-btn" :to="'/dish/' + result.best.dishId">看看做法和营养</router-link>
              </div>
            </article>

            <template v-if="restCandidates.length">
              <h3 class="subhead">{{ page === 1 ? '其他可以做的' : '更多候选' }}</h3>
              <ul class="cand-list">
                <li v-for="(c, ci) in restCandidates" :key="c.dishId || ('c' + ci)" class="cand">
                  <router-link v-if="c.dishId" class="cand-link" :to="'/dish/' + c.dishId">
                    <div>
                      <div class="cand-name">{{ c.name }}</div>
                      <div class="cand-sub">{{ candSub(c) }}</div>
                    </div>
                    <div class="cand-right">
                      <span class="cand-score">{{ Math.round((c.satisfactionRate || 0) * 100) }}%</span>
                      <span class="cand-flags">
                        {{ missingText(c) }}<template v-if="c.directEligible"> · 可直接用</template>
                      </span>
                    </div>
                  </router-link>
                  <template v-else>
                    <div>
                      <div class="cand-name">{{ c.name }}</div>
                      <div class="cand-sub">{{ candSub(c) }}</div>
                    </div>
                    <div class="cand-right">
                      <span class="cand-score">{{ Math.round((c.satisfactionRate || 0) * 100) }}%</span>
                      <span class="cand-flags">
                        {{ missingText(c) }}<template v-if="c.directEligible"> · 可直接用</template>
                      </span>
                    </div>
                  </template>
                </li>
              </ul>
            </template>

            <!-- 不管直出没直出，都留一个「让 AI 现场做」的出口 ——
                 直出时用户可能只是想换个花样，这也是一次合法的请求 -->
            <div class="alt-action">
              <p>{{ result.directReturn
                ? '想换个做法？可以让 AI 按你选的食材重新写一份。'
                : '库里没有完全匹配的搭配，可以让 AI 按你选的食材现场写一份。' }}</p>
              <button class="secondary-btn" id="genFromResultBtn" type="button"
                      @click="submitGenerate">让 AI 生成一道</button>
            </div>
          </template>
         </div>

          <!-- 分页器：钉在右栏底部，不跟着 .results-body 滚 -->
          <nav v-if="showPager" class="pager" aria-label="结果分页">
            <button class="pager-btn" type="button" id="prevPageBtn"
                    :disabled="page <= 1 || loading" @click="goPage(page - 1)">上一页</button>
            <span class="pager-nums">
              <template v-for="(n, i) in pageNumbers" :key="'pg' + i">
                <span v-if="n === '…'" class="pager-gap">…</span>
                <button v-else class="pager-num" type="button"
                        :class="{ 'is-on': n === page }"
                        :aria-current="n === page ? 'page' : undefined"
                        :disabled="loading" @click="goPage(n)">{{ n }}</button>
              </template>
            </span>
            <button class="pager-btn" type="button" id="nextPageBtn"
                    :disabled="page >= pageCount || loading" @click="goPage(page + 1)">下一页</button>
            <span class="pager-info">共 {{ result.total }} 道 · {{ page }}/{{ pageCount }} 页</span>
          </nav>
        </section>
      </div>
    </main>

    <!-- ==================== 手写菜谱（页内面板，不是新页面） ====================
         为什么不开新页面：用户手边有什么食材，本来就在这一页选着（selectedIds /
         freeIngredients），跳走再跳回来这份草稿就没了。所以做成同一页的覆盖层，
         并把已经选中的食材直接预填成食材表。

         营养这一路和 AI 生成不同：全部按食材库成分表本地算，
         没有"模型估算"这条退路 —— 对不上的食材会标出来、并记进候选食材表。 -->
    <div v-if="manualOpen" class="manual-mask" id="manualPanel" @click.self="closeManual">
      <section class="manual-card" role="dialog" aria-modal="true" aria-labelledby="manualHead">
        <header class="manual-head">
          <div>
            <h2 class="manual-title" id="manualHead">手写菜谱</h2>
            <p class="manual-sub">
              填食材和克数，热量按食材库的成分表算。食材 ≥ 2 样、步骤 ≥ 3 条就能直接进找菜库；
              不够会先存成草稿，只有你自己看得见。
            </p>
          </div>
          <button class="manual-close" id="manualClose" type="button" aria-label="关闭手写菜谱"
                  @click="closeManual">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.8"
                 stroke-linecap="round" aria-hidden="true"><path d="M3 3 9 9M9 3 3 9"/></svg>
          </button>
        </header>

        <div class="manual-body">
          <div class="manual-cols">
            <label class="manual-field">
              <span class="manual-label">菜名</span>
              <input class="input" id="manualName" type="text" maxlength="20" autocomplete="off"
                     placeholder="如「番茄鸡蛋浓汤」" v-model="manualForm.name">
            </label>

            <div class="manual-field">
              <span class="manual-label">几人份</span>
              <div class="segments" id="manualServings">
                <button v-for="o in SERVING_OPTIONS" :key="'ms' + o.value" type="button" class="seg"
                        :id="'manualServing' + o.value"
                        :aria-pressed="String(manualForm.servings === o.value)"
                        @click="manualForm.servings = o.value">{{ o.label }}</button>
              </div>
            </div>
          </div>

          <!-- 食材表：名字 + 克数。克数是营养计算的唯一依据，留空就是"没填好" -->
          <div class="manual-block">
            <div class="manual-block-head">
              <span class="manual-label">食材与用量</span>
              <span class="manual-hint">克数按食材库算热量；调料不用填（不计热量）</span>
            </div>
            <div class="manual-rows" id="manualRows">
              <div v-for="(row, i) in manualForm.ingredients" :key="'mr' + i" class="manual-row">
                <input class="input manual-ing-name" :id="'manualRowName' + i" type="text"
                       list="manualIngList" autocomplete="off" placeholder="食材名，如 番茄"
                       v-model="row.name">
                <input class="input manual-ing-gram" :id="'manualRowGram' + i" type="number"
                       min="1" max="5000" step="5" placeholder="克数" v-model="row.grams">
                <button class="manual-del" type="button" :id="'manualRowDel' + i"
                        :aria-label="'删除第 ' + (i + 1) + ' 行食材'"
                        @click="removeManualRow(i)">删</button>
              </div>
            </div>
            <button class="secondary-btn manual-add" id="manualAddRow" type="button"
                    @click="addManualRow">+ 加一样食材</button>
          </div>

          <div class="manual-block">
            <div class="manual-block-head">
              <span class="manual-label">做法步骤</span>
              <span class="manual-hint">至少 3 条，按顺序写</span>
            </div>
            <div class="manual-rows" id="manualSteps">
              <div v-for="(s, i) in manualForm.steps" :key="'ms' + i" class="manual-row manual-step-row">
                <span class="manual-step-no">{{ i + 1 }}</span>
                <input class="input manual-step-text" :id="'manualStep' + i" type="text"
                       :placeholder="'第 ' + (i + 1) + ' 步做什么'" v-model="manualForm.steps[i]">
                <button class="manual-del" type="button" :id="'manualStepDel' + i"
                        :aria-label="'删除第 ' + (i + 1) + ' 步'"
                        @click="removeManualStep(i)">删</button>
              </div>
            </div>
            <button class="secondary-btn manual-add" id="manualAddStep" type="button"
                    @click="addManualStep">+ 加一步</button>
          </div>

          <div class="manual-cols">
            <label class="manual-field">
              <span class="manual-label">总耗时（分钟，可留空）</span>
              <input class="input" id="manualCookTime" type="number" min="1" max="600"
                     placeholder="如 20" v-model="manualForm.cookTimeMin">
            </label>
            <label class="manual-field">
              <span class="manual-label">难度</span>
              <select class="input" id="manualDifficulty" v-model.number="manualForm.difficulty">
                <option v-for="(text, level) in DIFFICULTY_TEXT" :key="'md' + level"
                        :value="Number(level)">{{ text }}</option>
              </select>
            </label>
          </div>

          <label class="manual-field">
            <span class="manual-label">小贴士（可留空）</span>
            <input class="input" id="manualChefTip" type="text" maxlength="120"
                   placeholder="如「番茄先炒出沙，汤更浓」" v-model="manualForm.chefTips">
          </label>

          <!-- 实时试算：边填边看，别等提交完才发现克数没填、步骤不够 -->
          <div class="manual-preview" id="manualPreview">
            <p class="manual-preview-text" id="manualPreviewText">{{ manualPreviewText }}</p>
            <ul v-if="manualProblems.length" class="manual-problems" id="manualProblems">
              <li v-for="(p, i) in manualProblems" :key="'mp' + i">{{ p }}</li>
            </ul>
            <ul v-if="manualWarnings.length" class="manual-warnings" id="manualWarnings">
              <li v-for="(w, i) in manualWarnings" :key="'mw' + i">{{ w }}</li>
            </ul>
          </div>
        </div>

        <footer class="manual-foot">
          <span class="manual-foot-note" id="manualFootNote">{{ manualFootNote }}</span>
          <button class="secondary-btn" id="manualCancel" type="button" @click="closeManual">取消</button>
          <button class="submit" id="manualSubmit" type="button"
                  :disabled="manualBusy || !manualReady" @click="submitManual">
            {{ manualBusy ? '正在上传…' : '上传菜谱' }}
          </button>
        </footer>
      </section>
    </div>

    <!-- 食材名联想：只用本地食材表（88 种），别名归一交给后端 -->
    <datalist id="manualIngList">
      <option v-for="ing in MANUAL_ING_OPTIONS" :key="'mi' + ing.id" :value="ing.name"></option>
    </datalist>
  </div>
</template>

<script setup>
/* ============================================================
   找菜工作台（Vue 版）
   对应原生版：kitchen.html + js/kitchen.js + js/ingredients.js

   两条链路都在这一页：
     ① 召回：选食材 → 查库里有没有现成的（毫秒级，同步返回）
     ② 生成：交给 AI 现场做（十几秒，异步排队）
   ============================================================ */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import TaskRow from '../components/TaskRow.vue'
import { STORAGE_KEYS } from '../config.js'
import { DishApi } from '../api.js'
import {
  INGREDIENT_GROUPS, TASTE_TAGS, SERVING_OPTIONS, GROUP_TONE,
  nameOfIngredient, findIngredientIdByName, allLocalIngredients
} from '../lib/ingredients.js'
import { DIFFICULTY_TEXT, humanDuration } from '../lib/constants.js'
import { toastSuccess, toastError, toastInfo } from '../store/toast.js'

/** 与后端 es.recall.max-ingredients 保持一致，前端先拦一道 */
const MAX_INGREDIENTS = 10

/** 任务轮询间隔。1.5 秒是「看起来够及时」和「别把后端打爆」之间的折中 */
const TASK_POLL_MS = 1500

/* ---------------- 选择区状态 ---------------- */

const selectedIds = ref([])        // 从列表点选的食材 ID（数字）
const freeIngredients = ref([])    // 自由输入的食材名
const tastes = ref([])
const servings = ref(2)

/* ---------------- 识菜回填（/vision 页勾选后带回来的食材） ---------------- */
const route = useRoute()
const router = useRouter()
const visionNote = ref('')         // 回填结果的一句话说明（放在选择区按钮下方）

/* ---------------- 找菜模式与「更多条件」（P1-2 的两种模式 + P1-3 的生成约束） ----------------
   同一套条件同时作用于「找菜」和「让 AI 做」：
   找菜时它们是硬过滤（忌口的东西根本不该被推荐出来），
   生成时它们进提示词、并逐条被校验器检查。
   做成一套输入，是为了避免"搜的时候忌口了、生成的时候忘了"这种前后不一致。 */
const searchMode = ref('loose')     // strict 只用现有主料 / loose 允许缺几样
const maxMissing = ref(2)           // 宽松模式下最多允许缺几样主料
const mustUseIds = ref([])          // 必用食材（只影响生成）
const excludeNames = ref([])        // 忌口食材名（搜与生成都生效）
const excludeInput = ref('')
const maxCookTimeMin = ref(null)    // 最长总耗时（搜与生成都生效）
const equipment = ref([])           // 可用厨具（只影响生成）
const equipmentInput = ref('')
const moreOpen = ref(false)

/**
 * 必用食材只能从已选食材里挑。
 * 让人手动输入一个手边没有的食材再要求"必须用上"，是自相矛盾的请求
 * （后端也会在提交阶段把这类无解条件拒掉）。
 */
const effectiveMustUse = computed(() =>
  mustUseIds.value.filter(id => selectedIds.value.includes(id)))

function toggleMustUse(id) {
  const i = mustUseIds.value.indexOf(id)
  if (i >= 0) mustUseIds.value.splice(i, 1)
  else mustUseIds.value.push(id)
}

/** 加忌口。逗号、顿号、空格都认 —— 方便一次粘贴/输入多个 */
function addExcludes(raw) {
  splitNames(raw).forEach(name => {
    if (!excludeNames.value.includes(name)) excludeNames.value.push(name)
  })
  excludeInput.value = ''
}

function removeExclude(name) {
  excludeNames.value = excludeNames.value.filter(n => n !== name)
}

function addEquipments(raw) {
  splitNames(raw).forEach(name => {
    if (!equipment.value.includes(name)) equipment.value.push(name)
  })
  equipmentInput.value = ''
}

function removeEquipment(name) {
  equipment.value = equipment.value.filter(n => n !== name)
}

function splitNames(raw) {
  return String(raw || '').split(/[,，、\s]+/).map(s => s.trim()).filter(Boolean)
}

/**
 * 缺料文案。
 *
 * 一个"还缺 2 样"的数字远不如"还差 松露、牛肉"有用 ——
 * 前者只能告诉你"不太行"，后者能让你决定"那就买这两样"或者"换一道"。
 */
function missingText(item) {
  if (!item) return ''
  const n = item.missingCount || 0
  if (n <= 0) return '主料齐了'
  const names = (item.missingIngredients || []).join('、')
  return names ? ('还差 ' + names) : ('还差 ' + n + ' 样主料')
}

/**
 * 替代建议渲染成一行字：「豆腐→豆干/千张，香菜→葱花」。
 *
 * 后端只在"还缺的主料"上给替代（`substitutes`：缺的那样 → 可顶上的几样），
 * 所以这里不需要判断上下文，有就展示。最多两样、每样最多两个候选 ——
 * 这行是"顺手提示"，不是采购清单，太长会把主卡片挤变形。
 */
function substituteHint(map) {
  if (!map) return ''
  return Object.entries(map)
    .filter(([, subs]) => Array.isArray(subs) && subs.length)
    .slice(0, 2)
    .map(([missing, subs]) => missing + '→' + subs.slice(0, 2).join('/'))
    .join('，')
}

const loading = ref(false)         // 召回请求进行中
const genBusy = ref(false)         // 生成提交进行中

const ingKeyword = ref('')
const freeInput = ref('')
const suggests = ref([])
const suggestIndex = ref(-1)
const suggestVisible = ref(false)

/** 临时警示文案（1.8 秒后自动恢复成常规提示） */
const noteFlash = ref('')
let flashTimer = null

const pickedCount = computed(() => selectedIds.value.length + freeIngredients.value.length)
/** 点选的按 ID 升序，保证已选列表顺序稳定 */
const sortedSelected = computed(() => [...selectedIds.value].sort((a, b) => a - b))
const pickedNames = computed(() =>
  sortedSelected.value.map(nameOfIngredient).concat(freeIngredients.value))

const noteText = computed(() => {
  if (noteFlash.value) return noteFlash.value
  return pickedCount.value === 0 ? '还没有选择食材' : '已选 ' + pickedCount.value + ' 种食材'
})
const noteStyle = computed(() => (noteFlash.value ? { color: 'var(--danger)' } : {}))

/**
 * 表外食材的状态说明（是信息，不是警告）。
 *
 * 它们靠名字参与召回 —— 能搜到（见 EsDslBuilder 的双路查询），
 * 但不如收录进食材表之后精确：同一个东西的不同叫法会被当成两样，
 * 而且不参与营养核算。所以这里只把状态讲清楚，不做任何阻拦 ——
 * 「手边有什么就能用什么」是这个功能的根本承诺。
 */
const freeNote = computed(() => {
  if (!freeIngredients.value.length) return ''
  const names = freeIngredients.value.slice(0, 3).join('、')
  const more = freeIngredients.value.length > 3 ? ' 等 ' + freeIngredients.value.length + ' 种' : ''
  return '「' + names + '」' + more
    + '还没收录进食材表：AI 能用、也能被搜到，但不如表里的精确；用得多会被自动收录。'
})

/** 临时把提示行改成警示内容，1.8 秒后恢复 */
function flashNote(text) {
  noteFlash.value = text
  clearTimeout(flashTimer)
  flashTimer = setTimeout(() => { noteFlash.value = '' }, 1800)
}

/* ---------------- 食材搜索（客户端过滤） ---------------- */

/**
 * 用 input 事件即时过滤，不做防抖 —— 88 条数据的字符串匹配是微秒级，
 * 防抖反而让输入手感变"黏"。真到几千条再考虑防抖或虚拟滚动。
 */
const visibleGroups = computed(() => {
  const key = ingKeyword.value.trim().toLowerCase()
  if (!key) return INGREDIENT_GROUPS
  return INGREDIENT_GROUPS
    .map(g => ({ name: g.name, items: g.items.filter(i => i.name.toLowerCase().includes(key)) }))
    .filter(g => g.items.length > 0)
})

const searchMiss = computed(() => {
  const key = ingKeyword.value.trim()
  if (!key) return ''
  const hit = visibleGroups.value.reduce((n, g) => n + g.items.length, 0)
  // 一条都没命中时给一句话，留一片空白会让人以为列表没加载出来
  return hit === 0 ? '食材表里没有「' + key + '」，用下面的输入框直接添加' : ''
})

function clearIngSearch() {
  ingKeyword.value = ''
}

function onIngSearchKeydown(e) {
  // Esc 清空，和常见搜索框的行为一致
  if (e.key === 'Escape' && ingKeyword.value) {
    e.preventDefault()
    ingKeyword.value = ''
  }
}

function toggleIngredient(id) {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) {
    selectedIds.value.splice(i, 1)
    return
  }
  if (pickedCount.value >= MAX_INGREDIENTS) {
    flashNote('一次最多选 ' + MAX_INGREDIENTS + ' 种食材')
    return
  }
  selectedIds.value.push(id)
  /*
   * 【Vue 相对原生版的一个实打实的改进】原生版这里要手写 setIngredientPressed()
   * 去回写按钮的 aria-pressed —— 因为搜索会重建整个食材 DOM，状态得单独同步。
   * Vue 下 aria-pressed 是 :aria-pressed="state.includes(id)"，改 state 即改界面，
   * 这类"状态与视图不同步"的 bug 从根上不存在了。
   */
}

/* ---------------- 自由输入食材 ---------------- */

function onFreeInput() {
  const key = freeInput.value.trim().toLowerCase()
  if (!key) {
    hideSuggest()
    return
  }
  // 已经被选走的不再提示，避免用户重复加
  const picked = new Set(selectedIds.value.map(nameOfIngredient))
  const hits = allLocalIngredients()
    .filter(i => i.name.toLowerCase().includes(key) && !picked.has(i.name))
    .slice(0, 6)
  if (!hits.length) {
    hideSuggest()
    return
  }
  suggests.value = hits
  suggestIndex.value = -1
  suggestVisible.value = true
}

function hideSuggest() {
  suggestVisible.value = false
  suggests.value = []
  suggestIndex.value = -1
}

function moveSuggest(delta) {
  const n = suggests.value.length
  if (!n) return
  suggestIndex.value = (suggestIndex.value + delta + n) % n
}

function onFreeKeydown(e) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    moveSuggest(1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    moveSuggest(-1)
  } else if (e.key === 'Escape') {
    hideSuggest()
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const cur = suggests.value[suggestIndex.value]
    addFreeIngredient(cur ? cur.name : freeInput.value)
  }
}

/**
 * 添加一样自由输入的食材。
 *
 * 关键分支：**能在本地食材表里对上名字的，就走点选那条路**，而不是当成自由食材。
 * 否则「番茄」会以两种身份同时存在（一个 ID、一个字符串），
 * 后端归一后虽然能去重，但前端显示的已选列表里会出现两遍。
 */
function addFreeIngredient(raw) {
  const name = String(raw == null ? '' : raw).trim()
  if (!name) return

  if (name.length > 20) {
    flashNote('食材名最长 20 个字')
    return
  }

  const matchedId = findIngredientIdByName(name)
  if (matchedId !== null) {
    if (selectedIds.value.includes(matchedId)) {
      flashNote('「' + name + '」已经选过了')
      return
    }
    if (pickedCount.value >= MAX_INGREDIENTS) {
      flashNote('一次最多选 ' + MAX_INGREDIENTS + ' 种食材')
      return
    }
    selectedIds.value.push(matchedId)
    toastInfo('「' + name + '」在食材表里，已帮你选上')
  } else {
    if (freeIngredients.value.includes(name)) {
      flashNote('「' + name + '」已经加过了')
      return
    }
    if (pickedCount.value >= MAX_INGREDIENTS) {
      flashNote('一次最多选 ' + MAX_INGREDIENTS + ' 种食材')
      return
    }
    freeIngredients.value.push(name)
    // 当场说明它的处境：能用、能搜到（靠名字），但不如收录之后精确。
    // 不说明的话，用户会以为它和从列表点选的食材完全一样。
    toastInfo('「' + name + '」食材表里还没有，AI 也能用，用得多会自动收录')
  }

  freeInput.value = ''
  hideSuggest()
}

function removeFreeIngredient(name) {
  const i = freeIngredients.value.indexOf(name)
  if (i >= 0) freeIngredients.value.splice(i, 1)
}

function toggleTaste(tag) {
  const i = tastes.value.indexOf(tag)
  if (i >= 0) tastes.value.splice(i, 1)
  else tastes.value.push(tag)
}

/* ---------------- 召回 ---------------- */

const result = ref(null)
const resultView = ref('empty')   // empty | skeleton | error | result
const resultError = ref('')

/* ---------------- 分页 ----------------
   后端 /search 本身就是分页的（回传 total / page / size + 当前页 candidates），
   best 是 ranked[0] —— 全局最优、与页码无关，所以第 1 页之后不再重复展示它。
   分页代替了"滚到底加载更多"：右栏锁死高度，只有中间列表滚，翻页按钮钉在底部。 */
const PAGE_SIZE = 10
const page = ref(1)

const pageCount = computed(() => {
  const t = (result.value && result.value.total) || 0
  return Math.max(1, Math.ceil(t / PAGE_SIZE))
})

/** 候选不足一页时不显示分页器 —— 一个只有「1」的分页器只是噪音 */
const showPager = computed(() =>
  resultView.value === 'result' && !!result.value && result.value.total > PAGE_SIZE)

/**
 * 页码序列：页数少时全列，多时收成 1 … n-1 n n+1 … N。
 * 始终保留首尾两页 —— 用户想跳到"最后一页看看最差的是什么"是很自然的需求。
 */
const pageNumbers = computed(() => {
  const total = pageCount.value
  const cur = page.value
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const out = [1]
  const from = Math.max(2, cur - 1)
  const to = Math.min(total - 1, cur + 1)
  if (from > 2) out.push('…')
  for (let n = from; n <= to; n++) out.push(n)
  if (to < total - 1) out.push('…')
  out.push(total)
  return out
})

const totalRequested = computed(() => (result.value && result.value.requestedIngredientCount) || 1)

const resultTitle = computed(() => {
  const d = result.value
  if (!d) return ''
  const hasAny = !!d.best || (d.candidates && d.candidates.length > 0)
  if (d.directReturn) return '找到 ' + d.total + ' 道可以做的菜'
  if (hasAny) return '最接近的是这 ' + d.total + ' 道'
  return '这次没有找到'
})

const restCandidates = computed(() => {
  const d = result.value
  if (!d) return []
  const all = d.candidates || []
  // best 只在第 1 页以大卡片出现，所以第 1 页要把它从候选列表里剔掉避免重复；
  // 后面几页它本来也不会出现在结果里（它是 ranked[0]，属于第 1 页）
  return page.value === 1 && d.best ? all.filter(c => c.dishId !== d.best.dishId) : all
})

/** 评价数用小括号收进分数里，让整行只出现一个间隔符 */
function candSub(c) {
  const bits = []
  // 必须 toFixed(1)：ratingAvg 是 double，直接拼字符串会漏出
  // 4.199999999999999 这种浮点尾巴（推荐页早就这么处理了，这里漏了）
  if (c.ratingCount > 0) bits.push(Number(c.ratingAvg || 0).toFixed(1) + ' 分（' + c.ratingCount + ' 人）')
  if (c.cookTimeMin) bits.push(c.cookTimeMin + ' 分钟')
  if (c.missingCount > 0) bits.push(missingText(c))
  return bits.join(' · ')
}

/**
 * 执行检索。
 *
 * @param targetPage 目标页码。不传 = 从头搜（点了「开始找菜」或改了条件），
 *                   传数字 = 翻页（条件不变，只换 page）。
 *                   注意模板里 `@click="doSearch"` 会把事件对象传进来，
 *                   所以这里用 typeof 兜住，不能直接当数字用。
 */
async function doSearch(targetPage) {
  if (loading.value || pickedCount.value === 0) return
  const want = typeof targetPage === 'number' ? targetPage : 1
  loading.value = true
  resultView.value = 'skeleton'
  try {
    const data = await DishApi.search({
      ingredientIds: [...selectedIds.value],
      tasteTags: tastes.value.length ? [...tastes.value] : null,
      servings: servings.value,
      // 自由输入的食材也一起送过去，让召回层知道用户手边有什么。
      // 召回层只认 ID，所以这些名字只影响"缺了什么"的提示，不会误召回。
      freeIngredients: [...freeIngredients.value],
      // P1-2 的两种模式：strict = 一点缺料都不接受；loose = 允许缺 maxMissing 样
      mode: searchMode.value,
      maxMissing: searchMode.value === 'strict' ? null : maxMissing.value,
      // 忌口与时长是硬过滤：不满足的菜不该出现在结果里，
      // 否则用户看到一堆"看着不错但做不了"的菜，还得自己逐条排除
      excludeIngredients: [...excludeNames.value],
      maxCookTimeMin: maxCookTimeMin.value || null,
      page: want,
      size: PAGE_SIZE
    })
    result.value = data
    // 页码以后端回传的为准（后端会把非法页码夹回合法范围）
    page.value = data.page || want
    resultView.value = 'result'
  } catch (e) {
    resultError.value = e.message || '未知错误'
    resultView.value = 'error'
  } finally {
    loading.value = false
  }
}

/** 翻页：条件不变，只换页码。滚回结果区顶部，否则翻完页还停在旧位置看不出变化 */
function goPage(n) {
  if (n < 1 || n > pageCount.value || n === page.value || loading.value) return
  doSearch(n)
  const box = document.querySelector('.page-kitchen .results-body')
  if (box) box.scrollTop = 0
}

/* ---------------- 提交 AI 生成 ---------------- */

function buildPayload() {
  return {
    ingredientIds: [...selectedIds.value],
    freeIngredients: [...freeIngredients.value],
    tastes: tastes.value.length ? [...tastes.value] : [],
    servings: servings.value,
    // P1-3 的生成约束：必用 / 忌口 / 时长上限 / 可用厨具。
    // 空数组与不传等价（后端 toConstraints() 会把空值归一成 none()）
    mustUseIngredientIds: [...effectiveMustUse.value],
    excludeIngredients: [...excludeNames.value],
    maxCookTimeMin: maxCookTimeMin.value || null,
    availableEquipment: [...equipment.value]
  }
}

async function submitGenerate() {
  if (pickedCount.value === 0 || genBusy.value) return
  genBusy.value = true
  try {
    const task = await DishApi.generate(buildPayload())

    // 可能命中后端的单飞：同样的食材短时间重复提交会复用已有任务，
    // 这时它的 position 可能是旧值。所以统一重新拉一次列表，别直接插。
    await loadTasks()

    // 文案别写「做好会通知你」—— 我们没有推送，只有页面轮询，
    // 用户切走标签页就什么都收不到。承诺做不到的事比不承诺更糟。
    toastSuccess(task && task.position > 0
      ? '已排到第 ' + task.position + ' 位'
      : '已交给 AI，正在处理')
  } catch (e) {
    toastError(e.message)
  } finally {
    genBusy.value = false
  }
}

/* ---------------- 任务区 ---------------- */

const tasks = ref([])

/** 进行中：排队中 + 生成中。这两个状态常驻展开 */
const activeTasks = computed(() =>
  tasks.value.filter(t => t.status === 0 || t.status === 1))

/* ---------------- 「看过没有」决定收不收起来 ---------------- */

/** 已看过的任务 id，存 localStorage。只留最近 200 个，避免无限增长 */
const VIEWED_KEEP = 200

function loadViewedIds() {
  try {
    const raw = JSON.parse(localStorage.getItem(STORAGE_KEYS.VIEWED_TASKS) || '[]')
    return Array.isArray(raw) ? raw.map(String) : []
  } catch (e) {
    // 存坏了就当没看过：最多是全部展开，不会出错
    return []
  }
}

const viewedIds = ref(loadViewedIds())
const viewedSet = computed(() => new Set(viewedIds.value))

/**
 * 刚做好、还没点进去看过的菜 —— 保持展开。
 *
 * 【为什么不直接按"已完成"就收起来】用户提交完就切走了，回来看任务列表时
 * 只看到一行「已结束」，等于白做了 —— 他根本不知道做好了、更不知道好不好。
 * 所以"完成"只是我们这边跑完了，用户点头才算这件事闭环。
 */
const pendingTasks = computed(() =>
  tasks.value.filter(t => t.status === 2 && !viewedSet.value.has(String(t.taskId))))

/** 展开区 = 进行中 + 刚做好待看（两类都是"需要用户当下看一眼"的） */
const openTasks = computed(() => [...activeTasks.value, ...pendingTasks.value])

/** 折叠区 = 已结束里「看过了」或「本来就没生成出菜」的那些 */
const foldedTasks = computed(() =>
  tasks.value.filter(t => {
    if (t.status === 0 || t.status === 1) return false
    if (t.status === 2) return viewedSet.value.has(String(t.taskId))
    // 失败 / 取消 / 中断：原因就写在任务行上，不用等"看过"才有价值
    return true
  }))

/** 只要还有任何已结束的任务，就保留清空入口 */
const hasTerminal = computed(() =>
  tasks.value.some(t => t.status !== 0 && t.status !== 1))

/** 用户点开了某条任务的详情页 —— 记下来，它下次就收进折叠区了 */
function markViewed(taskId) {
  const id = String(taskId)
  if (viewedSet.value.has(id)) return
  const next = [...viewedIds.value, id].slice(-VIEWED_KEEP)
  viewedIds.value = next
  localStorage.setItem(STORAGE_KEYS.VIEWED_TASKS, JSON.stringify(next))
}

/** 已结束那段的展开状态。默认收起 —— 用户关心的是「现在在做什么」 */
const finishedOpen = ref(false)

const clearingFinished = ref(false)

/**
 * 清空所有已结束的任务记录。
 *
 * 【为什么不做本地剪枝】和任务动作一样，清完重新拉一次列表：
 * 本地剪枝得自己判断「哪些算已结束」，而后端的终态定义才是唯一事实来源。
 */
async function clearFinished() {
  if (clearingFinished.value) return
  clearingFinished.value = true
  try {
    const removed = await DishApi.clearFinishedTasks()
    await loadTasks()
    toastSuccess(removed ? '已清掉 ' + removed + ' 条记录' : '没有可清理的记录')
  } catch (e) {
    toastError(e.message)
  } finally {
    clearingFinished.value = false
  }
}

/** 排队中的任务按当前位置升序 —— 这就是「我的任务」在队列里的相对顺序 */
const waitingTasks = computed(() =>
  tasks.value.filter(t => t.status === 0).slice().sort((a, b) => a.position - b.position))

const waitingMap = computed(() => {
  const m = new Map()
  waitingTasks.value.forEach((t, i) => m.set(t.taskId, i))
  return m
})

function canUp(t) {
  const i = waitingMap.value.get(t.taskId)
  return i > 0
}

function canDown(t) {
  const i = waitingMap.value.get(t.taskId)
  return i >= 0 && i < waitingTasks.value.length - 1
}

const taskHint = computed(() => {
  const active = activeTasks.value.length
  if (active) {
    return active + ' 个进行中' + (active > 1 ? '，AI 一次做一道，其余排队' : '')
  }
  const pending = pendingTasks.value.length
  if (pending) {
    return pending + ' 道刚做好，等你看看'
  }
  return '没有进行中的任务'
})

let pollTimer = null

const hasActiveTask = () => tasks.value.some(t => t.status === 0 || t.status === 1)

async function loadTasks() {
  try {
    tasks.value = (await DishApi.myTasks()) || []
    syncPolling()
  } catch (e) {
    // 轮询失败不弹 Toast —— 每 1.5 秒弹一次会把页面刷爆。停掉轮询，等下次操作再试。
    stopPolling()
  }
  /*
   * 【Vue 相对原生版的一个改进】原生版这里要维持一个 taskSig 指纹，数据没变就
   * 不重绘 —— 否则用户正在点的按钮会在 mousedown 与 mouseup 之间被换掉，点击落空。
   * Vue 下 v-for 有 :key="t.taskId"，patch 是就地复用节点的，按钮不会被重建，
   * 所以那个指纹机制可以整体去掉。
   */
}

function syncPolling() {
  if (hasActiveTask()) startPolling()
  else stopPolling()
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(loadTasks, TASK_POLL_MS)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function onTaskAction(act, taskId) {
  // 【保持字符串，不要 Number()】任务 ID 也是雪花 ID，转 Number 会丢精度，
  // 拿错的 ID 调接口只会得到「任务不存在」。它只被用来拼 URL 和当 Map 的键。
  if (act === 'cancel') {
    try {
      const msg = await DishApi.cancelTask(taskId)
      toastInfo(msg || '已取消')
      await loadTasks()
    } catch (e) {
      toastError(e.message)
    }
    return
  }

  if (act === 'remove') {
    try {
      const msg = await DishApi.deleteTask(taskId)
      toastInfo(msg || '已移除')
      // 重新拉一次，而不是在本地把它从 tasks 里剪掉：本地剪枝要处理
      // "删完列表空了要隐藏整个任务区"这类分支，多一次请求换掉这些容易漏的地方，划算
      await loadTasks()
    } catch (e) {
      toastError(e.message)
    }
    return
  }

  if (act === 'up' || act === 'down') {
    const idx = waitingMap.value.get(taskId)
    if (idx === undefined || idx < 0) return
    // 目标位置是「我的任务里的第几位」，1-based：
    //   上移：从第 idx+1 位挪到第 idx 位   -> 传 idx
    //   下移：从第 idx+1 位挪到第 idx+2 位 -> 传 idx + 2
    const target = act === 'up' ? idx : idx + 2
    try {
      await DishApi.reorderTask(taskId, target)
      await loadTasks()
    } catch (e) {
      toastError(e.message)
    }
  }
}

/* ---------------- 手写菜谱（用户自定义，source=3） ----------------

   为什么做成同一页的覆盖层而不是新路由：用户手边有什么食材，本来就在这页
   点着（selectedIds / freeIngredients）。跳走再跳回来，这份没提交的草稿就没了；
   而且"我这几样食材能做点什么"和"我自己写一道"本来就是同一件事的两条出路。

   营养全部按食材库成分表本地算（后端 NutritionCalculator）：
   手写菜没有 AI 估算这条退路 —— 对不上成分表的食材会被标出来、并记进候选食材表，
   值照样算给你看，只是标成"未核验"。 */

/** 面板里食材名联想用；本地食材表 88 种，别名归一交给后端 IngredientDirectory */
const MANUAL_ING_OPTIONS = allLocalIngredients()

const manualOpen = ref(false)
const manualBusy = ref(false)
const manualPreview = ref(null)   // 最近一次试算结果（preview 不落库）
let manualTimer = null

function emptyManualRow() {
  return { name: '', grams: '' }
}

/** 空表单：默认 3 行食材 + 3 步 —— 正好是「能发布」的最小骨架，省得用户自己数 */
function emptyManualForm() {
  return {
    name: '',
    servings: servings.value,
    cookTimeMin: '',
    difficulty: 2,
    chefTips: '',
    ingredients: [emptyManualRow(), emptyManualRow(), emptyManualRow()],
    steps: ['', '', '']
  }
}

const manualForm = ref(emptyManualForm())

const manualPreviewText = computed(() => {
  const p = manualPreview.value
  return (p && p.text) || '填完食材和步骤，这里会实时算出每人份热量。'
})
const manualProblems = computed(() => (manualPreview.value && manualPreview.value.problems) || [])
const manualWarnings = computed(() => (manualPreview.value && manualPreview.value.warnings) || [])
/** 只有后端试算说"ready"才让点上传：前端自己判一遍门槛容易和后端判歪 */
const manualReady = computed(() => !!(manualPreview.value && manualPreview.value.ready))
const manualFootNote = computed(() => {
  if (manualBusy.value) return '正在上传…'
  if (!manualPreview.value) return '还没试算'
  if (manualReady.value) return '够门槛了，上传后直接进找菜库'
  return '还差 ' + manualProblems.value.length + ' 项，补上再上传'
})

/** 预填：把已选/自填的食材搬进食材表，克数留空让用户自己填 */
function prefillManualRows() {
  const rows = sortedSelected.value.map(id => ({ name: nameOfIngredient(id), grams: '' }))
  freeIngredients.value.forEach(name => rows.push({ name, grams: '' }))
  while (rows.length < 3) rows.push(emptyManualRow())
  return rows
}

function openManual() {
  manualForm.value = {
    ...emptyManualForm(),
    servings: servings.value,
    ingredients: prefillManualRows()
  }
  manualPreview.value = null
  manualOpen.value = true
  scheduleManualPreview()
}

function closeManual() {
  manualOpen.value = false
  clearTimeout(manualTimer)
}

function addManualRow() {
  manualForm.value.ingredients.push(emptyManualRow())
}

function removeManualRow(i) {
  manualForm.value.ingredients.splice(i, 1)
  if (!manualForm.value.ingredients.length) addManualRow()
}

function addManualStep() {
  manualForm.value.steps.push('')
}

function removeManualStep(i) {
  manualForm.value.steps.splice(i, 1)
  if (!manualForm.value.steps.length) addManualStep()
}

/**
 * 表单 → 后端 ManualDishRequest。
 *
 * 空克数必须转成 null：`type=number` 的空输入 v-model 拿到的是空字符串，
 * 原样发出去后端按 BigDecimal 解析会直接失败（"边填边试算"时这一步天天发生）。
 */
function manualPayload() {
  const f = manualForm.value
  return {
    name: (f.name || '').trim(),
    servings: f.servings,
    cookTimeMin: f.cookTimeMin === '' || f.cookTimeMin == null ? null : Number(f.cookTimeMin),
    difficulty: f.difficulty,
    chefTips: (f.chefTips || '').trim(),
    ingredients: f.ingredients
      .map(r => {
        const name = (r.name || '').trim()
        return {
          name,
          ingredientId: findIngredientIdByName(name),
          grams: r.grams === '' || r.grams == null ? null : Number(r.grams)
        }
      })
      .filter(r => r.name || r.grams != null),
    steps: f.steps.map(s => (s || '').trim()).filter(Boolean)
  }
}

/** 边填边算：300ms 防抖 —— 每敲一个字母打一次后端，只会让面板一直闪 */
function scheduleManualPreview() {
  clearTimeout(manualTimer)
  manualTimer = setTimeout(runManualPreview, 300)
}

async function runManualPreview() {
  const payload = manualPayload()
  // 表单还空着就别问了：后端只会回一堆"没填"，还白花一次请求
  if (!payload.name && !payload.ingredients.length && !payload.steps.length) {
    manualPreview.value = null
    return
  }
  try {
    manualPreview.value = await DishApi.manualPreview(payload)
  } catch (e) {
    manualPreview.value = null
    toastError(e.message)
  }
}

async function submitManual() {
  if (manualBusy.value) return
  const name = (manualForm.value.name || '').trim()
  manualBusy.value = true
  try {
    const res = await DishApi.createManual(manualPayload())
    if (res && res.published) {
      toastSuccess('「' + name + '」已发布到找菜库')
    } else {
      const why = res && res.problems && res.problems.length
        ? '，还差：' + res.problems.join('；')
        : ''
      toastInfo('「' + name + '」已存为草稿' + why, 4000)
    }
    closeManual()
  } catch (e) {
    toastError(e.message)
  } finally {
    manualBusy.value = false
  }
}

// 面板里的任何改动都触发一次试算（deep 监听，不必给每个输入框挂 @input）
watch(manualForm, () => {
  if (manualOpen.value) scheduleManualPreview()
}, { deep: true })

/* ---------------- 启动 ---------------- */

/** 点到别处就收起联想（原生版是挂在 document 上的全局监听） */
function onDocumentClick(e) {
  if (suggestVisible.value && !e.target.closest('.free-ing')) {
    hideSuggest()
  }
}

/** 页面切到后台就停掉轮询，回来再继续 —— 没人看的页面不需要每 1.5 秒问一次 */
function onVisibilityChange() {
  if (document.hidden) {
    stopPolling()
  } else if (hasActiveTask()) {
    loadTasks()
  }
}

/**
 * 识菜回填：/kitchen?visionIds=101,102&visionFree=香蕉,土豆
 *
 * 识菜页（/vision）勾选后把两串结果带回来（docs/yolo-vision-design.md §6.4）：
 *   映射成功的 ID   -> selectedIds（走主数据，能参与营养校验、能进倒排索引）
 *   库里没有的标签  -> freeIngredients（走自由输入通道）
 * 带回后立刻 replace 清掉 query：否则用户刷新或后退会重复带入一遍。
 * 本地食材表里没有的 ID 直接丢弃 —— 避免脏 query 造出后端不认识的选中项。
 */
function applyVisionQuery() {
  const rawIds = String(route.query.visionIds || '')
    .split(',').map(s => s.trim()).filter(Boolean)
  const rawFree = String(route.query.visionFree || '')
    .split(',').map(s => s.trim()).filter(Boolean)
  if (!rawIds.length && !rawFree.length) return

  const localIds = new Set(allLocalIngredients().map(i => i.id))
  const ids = rawIds.map(Number).filter(id => Number.isFinite(id) && localIds.has(id))
  const dropped = rawIds.length - ids.length

  ids.forEach(id => {
    if (!selectedIds.value.includes(id)) selectedIds.value.push(id)
  })
  rawFree.forEach(name => {
    if (!freeIngredients.value.includes(name)) freeIngredients.value.push(name)
  })

  const total = ids.length + rawFree.length
  visionNote.value = total
    ? `已从照片带入 ${total} 样食材` + (dropped ? `（另有 ${dropped} 样不在食材表里，已忽略）` : '')
    : '照片里认出的食材都不在食材表里，请手动选择'

  router.replace({ path: '/kitchen' })
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('visibilitychange', onVisibilityChange)

  // 识菜页带回来的食材（有就预填好，没有就什么都不做）
  applyVisionQuery()

  // 进页面先拉一次任务：可能上次的还在队列里，或者已经做完了
  loadTasks()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopPolling()
  clearTimeout(flashTimer)
  clearTimeout(manualTimer)
})
</script>
