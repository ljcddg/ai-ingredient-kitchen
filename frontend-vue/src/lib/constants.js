/* ============================================================
   业务文案映射（与后端枚举对齐）
   ------------------------------------------------------------
   对应原生版 nav.js 末尾那几个常量。抽成独立模块是为了：
   Vue 的模板里直接 import 使用，不用挂到 window 上。
   ============================================================ */

/** 难度 1-5 的文字映射（与 AI 提示词里的档位一致） */
export const DIFFICULTY_TEXT = { 1: '很简单', 2: '简单', 3: '一般', 4: '有点难', 5: '讲究' }

/** 辣度 0-3 的文字映射 */
export const SPICY_TEXT = { 0: '不辣', 1: '微辣', 2: '中辣', 3: '很辣' }

/** 菜品来源 1-官方 2-AI 3-自定义 */
export const SOURCE_TEXT = { 1: '官方精选', 2: 'AI 生成', 3: '我的创作' }

/**
 * 营养来源（对应 t_dish.nutrition_source，说明文档 P2-2）。
 *
 * 这两个标签的意义完全不同，必须分开显示：
 *   1 = AI 按常识估的，只能当参考
 *   2 = 按食材成分表（每 100 克）× 实际用量算出来的
 * 把估算标成"计算值"就是在把估算当实测用。
 */
export const NUTRITION_SOURCE_TEXT = {
  1: '营养为 AI 估算',
  2: '营养按成分表计算'
}

/** 食材候选的审核状态（对应 IngredientCandidate 常量） */
export const CANDIDATE_STATUS_TEXT = { 0: '待审核', 1: '已转正', 2: '已驳回' }

/** 把毫秒数说成人话：12.3 秒 / 1 分 05 秒 */
export function humanDuration(ms) {
  if (!ms && ms !== 0) return ''
  const sec = Math.round(ms / 1000)
  if (sec < 60) return sec + ' 秒'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return m + ' 分 ' + (s < 10 ? '0' + s : s) + ' 秒'
}
