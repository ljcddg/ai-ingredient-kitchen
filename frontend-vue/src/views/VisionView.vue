<template>
  <div class="page-vision">
    <FxBg />
    <TopBar current="kitchen" />

    <main class="page-wrap vision-wrap">
      <div class="page-head">
        <div>
          <h1 class="page-title">拍照识菜</h1>
          <p class="page-sub">上传几张照片，AI 认出里面的食材 → 你勾选 → 一键生成菜谱</p>
        </div>
        <router-link class="ghost-btn" to="/kitchen">← 返回找菜</router-link>
      </div>

      <!-- 降级提示：识别是加速项，不是必经环节（design §7.3） -->
      <div v-if="notice" class="vision-notice" role="status">
        <span>{{ notice }}</span>
        <router-link class="link-btn" to="/kitchen">改用手动选择食材 →</router-link>
      </div>

      <!-- ==================== 上传区 ==================== -->
      <section class="vision-upload" aria-label="上传图片">
        <div class="drop-zone" :class="{ dragging }"
             role="button" tabindex="0"
             @click="pickFiles" @keydown.enter.prevent="pickFiles"
             @dragover.prevent="dragging = true"
             @dragleave.prevent="dragging = false"
             @drop.prevent="onDrop">
          <input ref="fileInput" type="file" accept="image/jpeg,image/png" multiple hidden
                 @change="onPick" />
          <p class="drop-title">点击选择图片，或把照片拖到这里</p>
          <p class="drop-sub">最多 {{ MAX_IMAGES }} 张 · 单张不超过 {{ MAX_MB }}MB · 支持 JPG / PNG</p>
        </div>

        <div v-if="picked.length" class="preview-grid">
          <figure v-for="(item, i) in picked" :key="item.key" class="preview-item">
            <img :src="item.preview" :alt="'第 ' + (i + 1) + ' 张'" />
            <button class="preview-x" type="button" :aria-label="'移除第 ' + (i + 1) + ' 张'"
                    @click="removePicked(i)">×</button>
          </figure>
        </div>

        <div class="upload-actions">
          <button class="primary-btn" type="button" :disabled="!picked.length || loading"
                  @click="detect">
            {{ loading ? '识别中…' : '开始识别' }}
          </button>
          <span v-if="picked.length" class="upload-count">已选 {{ picked.length }} 张</span>
          <button v-if="picked.length" class="link-btn" type="button" :disabled="loading"
                  @click="clearPicked">清空</button>
        </div>
      </section>

      <!-- ==================== 结果区 ==================== -->
      <section v-if="result" class="vision-result">
        <div class="result-grid">
          <!-- 左：按图分组的检出详情 -->
          <div class="result-images">
            <h2 class="block-title">识别详情 <small>共 {{ result.images.length }} 张 · 耗时 {{ result.costMs }}ms</small></h2>
            <div v-for="(img, i) in result.images" :key="img.imageId" class="result-image-card">
              <img class="result-image" :src="VisionApi.imageUrl(img.imageUrl)"
                   :alt="'第 ' + (i + 1) + ' 张原图'" />
              <p v-if="img.error" class="result-image-err">{{ img.error }}</p>
              <p v-else-if="!img.detections.length" class="result-image-err">这张没认出东西</p>
              <ul v-else class="detect-list">
                <li v-for="(d, j) in img.detections" :key="j" class="detect-item">
                  <img v-if="d.cropUrl" class="detect-crop" :src="VisionApi.imageUrl(d.cropUrl)"
                       alt="" />
                  <span class="detect-label">{{ d.mapped ? d.ingredientName : d.label }}</span>
                  <span class="detect-conf">{{ pct(d.confidence) }}</span>
                  <span v-if="!d.mapped" class="detect-tag">库里没有</span>
                </li>
              </ul>
            </div>
          </div>

          <!-- 右：聚合清单（勾选区） -->
          <aside class="result-panel" aria-label="食材清单">
            <h2 class="block-title">食材清单 <small>勾选要用的</small></h2>

            <p v-if="!result.candidates.length && !result.unmappedLabels.length" class="result-empty">
              这次没认出食材。换个角度、对着食材本身拍一张，或者直接手动选择。
            </p>

            <template v-else>
              <div class="panel-actions">
                <button class="link-btn" type="button" @click="checkAllMapped">全选</button>
                <button class="link-btn" type="button" @click="checkedIds = []">全不选</button>
              </div>

              <label v-for="c in result.candidates" :key="c.ingredientId" class="cand-item">
                <input v-model="checkedIds" type="checkbox" :value="c.ingredientId" />
                <span class="cand-name">{{ c.name }}</span>
                <span class="cand-count">×{{ c.count }}</span>
                <span class="cand-conf" :class="{ high: (c.maxConfidence || 0) >= highConf }">
                  {{ pct(c.maxConfidence) }}
                </span>
              </label>

              <div v-if="result.unmappedLabels.length" class="unmapped-block">
                <h3 class="unmapped-title">认出来但库里没有</h3>
                <p class="unmapped-hint">勾上会以「自由食材」的名字带入生成（不进食材表）</p>
                <label v-for="u in result.unmappedLabels" :key="u.label" class="cand-item unmapped">
                  <input v-model="checkedUnmapped" type="checkbox" :value="u.label" />
                  <span class="cand-name">{{ u.label }}</span>
                  <span class="cand-count">×{{ u.count }}</span>
                  <span class="cand-conf">{{ pct(u.maxConfidence) }}</span>
                </label>
              </div>
            </template>

            <button class="primary-btn block-btn" type="button" :disabled="!totalChecked"
                    @click="goGenerate">
              用这些食材生成菜谱（{{ totalChecked }} 样）→
            </button>
          </aside>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
/* ============================================================
   拍照识菜（Vue 版）
   对应链路：多图上传 → POST /ai/vision/dishes（同步识别）→ 勾选
            → 回填厨房页生成表单（router query 传递）

   设计文档：docs/yolo-vision-design.md
   两条产品原则（文档 §1）：
     ① 识别是辅助不是权威 —— 候选项必须经用户勾选，勾选同时兜底误识别；
     ② 识别失败/服务不可用 → 回退手动选食材，它是加速项不是必经环节。
   ============================================================ */
import { ref, computed, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import FxBg from '../components/FxBg.vue'
import TopBar from '../components/TopBar.vue'
import { VisionApi } from '../api.js'
import { toastError, toastInfo } from '../store/toast.js'

/** 与后端 ai.vision.max-images / max-file-size-mb 保持一致（后端仍会再校验一道） */
const MAX_IMAGES = 9
const MAX_MB = 10

/** 后端默认勾选线：达到这个置信度的候选默认勾上（VisionProperties.defaultCheckedConfidence） */
const highConf = 0.8

const router = useRouter()

const fileInput = ref(null)
const picked = ref([])          // [{ key, file, preview }]
const dragging = ref(false)
const loading = ref(false)
const notice = ref('')          // 降级/空态提示
const result = ref(null)

const checkedIds = ref([])      // 勾选的食材 ID（字符串，后端 Long 序列化成字符串）
const checkedUnmapped = ref([]) // 勾选的"库内没有"标签（走自由食材通道）

const totalChecked = computed(() => checkedIds.value.length + checkedUnmapped.value.length)

/* ---------------- 选图 ---------------- */

function pickFiles() {
  if (fileInput.value) fileInput.value.click()
}

function onPick(event) {
  addFiles(event.target.files)
  event.target.value = ''   // 允许重复选同一个文件
}

function onDrop(event) {
  dragging.value = false
  addFiles(event.dataTransfer && event.dataTransfer.files)
}

function addFiles(fileList) {
  const files = Array.from(fileList || [])
  for (const file of files) {
    if (picked.value.length >= MAX_IMAGES) {
      toastInfo(`最多 ${MAX_IMAGES} 张，多出来的已忽略`)
      break
    }
    if (!/^image\/(jpeg|jpg|png)$/i.test(file.type)) {
      toastError(`「${file.name}」不是 JPG / PNG，已跳过`)
      continue
    }
    if (file.size > MAX_MB * 1024 * 1024) {
      toastError(`「${file.name}」超过 ${MAX_MB}MB，已跳过`)
      continue
    }
    picked.value.push({
      key: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      file,
      preview: URL.createObjectURL(file)
    })
  }
}

function removePicked(index) {
  const item = picked.value[index]
  if (item) URL.revokeObjectURL(item.preview)
  picked.value.splice(index, 1)
}

function clearPicked() {
  picked.value.forEach(item => URL.revokeObjectURL(item.preview))
  picked.value = []
  result.value = null
  notice.value = ''
  checkedIds.value = []
  checkedUnmapped.value = []
}

// 离开页面回收 objectURL（否则整轮预览的 blob 会一直挂在内存里）
onBeforeUnmount(() => {
  picked.value.forEach(item => URL.revokeObjectURL(item.preview))
})

/* ---------------- 识别 ---------------- */

async function detect() {
  if (!picked.value.length || loading.value) return
  loading.value = true
  notice.value = ''
  try {
    const data = await VisionApi.detect(picked.value.map(item => item.file))
    result.value = data
    // 高置信默认勾选（后端已按阈值算好 checked，前端只负责初始值）
    checkedIds.value = data.candidates.filter(c => c.checked).map(c => c.ingredientId)
    checkedUnmapped.value = []
    if (!data.candidates.length && !data.unmappedLabels.length) {
      notice.value = '这次没认出食材。换个角度、或者对着食材本身再拍一张试试。'
    }
  } catch (e) {
    result.value = null
    notice.value = (e && e.message) || '识别服务暂时不可用，可以先手动选择食材。'
  } finally {
    loading.value = false
  }
}

function checkAllMapped() {
  checkedIds.value = (result.value ? result.value.candidates : []).map(c => c.ingredientId)
}

function pct(value) {
  return `${Math.round((value || 0) * 100)}%`
}

/* ---------------- 回填生成 ---------------- */

/**
 * 回填策略（design §6.4，DTO 零改动）：
 *   映射成功的 -> ingredientIds（有主数据 ID，参与营养校验）
 *   库里没有的 -> freeIngredients（表外名字走自由输入通道）
 * 用 router query 传递，厨房页 onMounted 消费后清掉 query。
 */
function goGenerate() {
  if (!totalChecked.value) return
  router.push({
    path: '/kitchen',
    query: {
      visionIds: checkedIds.value.join(','),
      visionFree: checkedUnmapped.value.join(',')
    }
  })
}
</script>
