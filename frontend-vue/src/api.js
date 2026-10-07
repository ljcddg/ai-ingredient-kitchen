/* ============================================================
   接口请求封装（Vue 版，语义与原生版 frontend/js/api.js 完全一致）
   ============================================================ */

import { AppConfig, STORAGE_KEYS } from './config.js'

/** 业务异常，带上后端返回的 code，方便特殊处理（比如 401 跳登录） */
export class ApiError extends Error {
  constructor(message, code) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

/**
 * 清掉登录态并回登录页。
 *
 * 加"当前是不是已经在登录页"这个判断，是为了避免在登录页里反复跳转自己 ——
 * 那会把用户正在填的表单刷掉，看起来像页面抽风。
 *
 * 【Vue 版差异】这里不能用 location.href 硬跳（会整页刷新、丢掉 SPA 状态），
 * 改为对外抛一个"需要跳登录"的信号，由路由守卫统一处理。
 */
let onUnauthorized = null
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

function handleUnauthorized() {
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_INFO)
  if (typeof onUnauthorized === 'function') {
    onUnauthorized()
  } else {
    // 兜底：handler 还没注册时（极早期请求）退回原生版的整页跳转
    const here = location.pathname
    const onLoginPage = here === '/' || /\/index\.html$/.test(here)
    if (!onLoginPage) location.href = '/'
  }
}

/**
 * 统一请求方法。
 *   1. HTTP 层错误（网络不通、500）→ 统一异常，调用方 catch 一处
 *   2. 业务层 code != 200 也当失败抛出，message 直接用后端中文提示
 *   3. token 过期（401）统一跳回登录页
 */
export async function request(path, options = {}) {
  const { method = 'GET', body, auth = false, service = 'account' } = options

  const headers = { 'Content-Type': 'application/json' }

  if (auth) {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    if (token) {
      headers['Authorization'] = 'Bearer ' + token
    }
  }

  const fullUrl = AppConfig.serviceUrl(service, path)

  let response
  try {
    response = await fetch(fullUrl, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
  } catch (e) {
    throw new ApiError('无法连接服务器，请确认服务已启动（' + fullUrl + '）', 0)
  }

  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch (e) {
      throw new ApiError('服务器返回了非预期的内容：' + text.slice(0, 120), response.status)
    }
  }

  if (!payload) {
    throw new ApiError('服务器没有返回内容（HTTP ' + response.status + '）', response.status)
  }

  // 401 统一处理：token 过期或无效。
  // 网关拒掉未认证请求时常常把 body 也换成自己的，所以 HTTP 状态码和业务码都要看。
  if (response.status === 401 || payload.code === 401) {
    handleUnauthorized()
    throw new ApiError(payload.message || '登录已过期，请重新登录', 401)
  }

  if (payload.code !== 200) {
    throw new ApiError(payload.message || '请求失败', payload.code)
  }

  return payload.data
}

/* ============================================================
   具体接口
   ============================================================ */

export const AuthApi = {
  /** 注册：后端返回新用户 id */
  register(username, password, nickname) {
    return request('/auth/register', {
      method: 'POST',
      body: { username, password, nickname: nickname || null }
    })
  },

  /** 登录：后端返回 { token, tokenType, userId, username, nickname } */
  login(username, password) {
    return request('/auth/login', {
      method: 'POST',
      body: { username, password }
    })
  },

  /** 查询当前登录用户（受保护接口，验证网关透传是否生效） */
  profile() {
    return request('/user/profile', { auth: true })
  }
}

/**
 * 发现流的查询串。
 *
 * 浏览模式和决策模式共用同一套条件（食材 + 情境筛选），
 * 只有分页那一项不同：浏览用 page/size，决策用 offset。
 * 公共部分抽在这里，免得两个方法各写一遍、改一处漏一处。
 *
 * @param {object} cond 食材与筛选条件
 * @param {string} tail 分页参数（已拼好）
 */
function discoverQuery(cond, tail) {
  const ids = (cond.ingredientIds || []).filter(Boolean)
  const names = (cond.ingredientNames || []).filter(Boolean)
  const q = []
  if (ids.length) q.push('ingredientIds=' + ids.join(','))
  // 名字可能含中文/空格，必须 encode
  if (names.length) q.push('ingredientNames=' + encodeURIComponent(names.join(',')))
  if (cond.maxCookTimeMin) q.push('maxCookTimeMin=' + cond.maxCookTimeMin)
  if (cond.maxDifficulty) q.push('maxDifficulty=' + cond.maxDifficulty)
  if (cond.serving) q.push('serving=' + cond.serving)
  q.push(tail)
  return '?' + q.join('&')
}

export const DishApi = {
  // ================================================================ 召回

  /**
   * 按食材组合召回已有菜品。
   * 返回 DishRecallResult：directReturn / missReason / best / candidates / searchEngineAvailable
   */
  search(payload) {
    return request('/search', { method: 'POST', body: payload, auth: true, service: 'dish' })
  },

  // ================================================================ AI 生成

  /**
   * 提交一次 AI 生成请求（**立即返回**：只入队并返回 taskId + 排队位置，
   * 真正生成跑在服务端后台，前端靠轮询 myTasks() 看进度）。
   */
  generate(payload) {
    return request('/ai/dish', { method: 'POST', body: payload, auth: true, service: 'dish' })
  },

  /** 我的任务列表（含实时排队位置）。轮询这个接口刷新任务区 */
  myTasks() {
    return request('/ai/tasks', { auth: true, service: 'dish' })
  },

  /**
   * 清空所有**已结束**的任务记录（已完成 / 失败 / 取消 / 中断）。
   *
   * 进行中的任务不在清理范围内 —— 想清掉得先取消，否则后台的 AI 调用
   * 还在跑，会变成一条谁也解释不清的幽灵任务。
   *
   * @returns {number} 实际清掉的条数
   */
  clearFinishedTasks() {
    return request('/ai/tasks/finished', { method: 'DELETE', auth: true, service: 'dish' })
  },

  /**
   * 调整排队顺序。
   * @param {number} orderAmongMine 想排到「我的任务」里的第几位，从 1 开始。
   */
  reorderTask(taskId, orderAmongMine) {
    return request('/ai/tasks/' + taskId + '/order', {
      method: 'PUT',
      body: { orderAmongMine },
      auth: true,
      service: 'dish'
    })
  },

  /** 取消任务。只对**进行中**的任务有意义（排队中真移除、生成中逻辑取消） */
  cancelTask(taskId) {
    return request('/ai/tasks/' + taskId + '/cancel', {
      method: 'POST',
      auth: true,
      service: 'dish'
    })
  },

  /**
   * 移除一条**已结束**的任务记录。
   * 和取消刻意分成两个方法：对进行中的任务调它会被后端拒绝（"请先取消再移除"）。
   */
  deleteTask(taskId) {
    return request('/ai/tasks/' + taskId, {
      method: 'DELETE',
      auth: true,
      service: 'dish'
    })
  },

  // ================================================================ 菜品详情与互动

  /** 菜品详情：营养 / 制作过程 / 大厨建议 / 评论 / 评分，一次给全 */
  detail(dishId) {
    return request('/ai/dish/' + dishId, { auth: true, service: 'dish' })
  },

  // ================================================================ 发布（草稿 ↔ 找菜库）

  /** 预览发布校验，不改任何数据（让页面提前显示「还差什么」） */
  publishCheck(dishId) {
    return request('/ai/dish/' + dishId + '/publish-check', { auth: true, service: 'dish' })
  },

  /** 发布到找菜库。校验不通过时后端会在 message 里写明差什么 */
  publishDish(dishId) {
    return request('/ai/dish/' + dishId + '/publish', { method: 'POST', auth: true, service: 'dish' })
  },

  /** 从找菜库撤回（回到草稿，菜本身不删） */
  unpublishDish(dishId) {
    return request('/ai/dish/' + dishId + '/unpublish', { method: 'POST', auth: true, service: 'dish' })
  },

  // ================================================================ 榜单

  /** 菜品榜单。type: hot（热门）/ top（高分）/ latest（最新） */
  rank(type, page, size) {
    return request(
      '/ai/rank?type=' + encodeURIComponent(type || 'hot') +
      '&page=' + (page || 1) + '&size=' + (size || 20),
      { auth: true, service: 'dish' }
    )
  },

  // ================================================================ 推荐池（冷启动）

  /**
   * 推荐流。
   *
   * @param {number[]} ingredientIds 主数据食材 ID（走 ingredientIds 召回，精度高）
   * @param {number} page 页码
   * @param {number} size 每页条数
   * @param {string[]} [ingredientNames] 表外食材名（走 ingredientNames 召回）。
   *        列表里没有的食材只有名字没有 ID，后端按 keyword 匹配识别。
   *
   * 后端按「全匹配 → 部分匹配 → 随机补齐」三级取菜，
   * 并且在返回的同时给这些菜的 push_count +1（不需要前端再上报一次曝光）。
   * 24 小时内推过的菜会被自动排除，所以翻页不会重复看到同一道。
   */
  recommendFeed(ingredientIds, page, size, ingredientNames) {
    const ids = (ingredientIds || []).filter(Boolean)
    const names = (ingredientNames || []).filter(Boolean)
    let url = '/ai/recommend?page=' + (page || 1) + '&size=' + (size || 10)
    if (ids.length) url += '&ingredientIds=' + ids.join(',')
    // 名字可能含中文/空格，必须逐个 encode
    if (names.length) url += '&ingredientNames=' + encodeURIComponent(names.join(','))
    return request(url, { auth: true, service: 'dish' })
  },

  /**
   * 【已废弃】上报查看。
   *
   * 2026-10-07 起浏览计数改由详情接口统一负责（DishViewCounter），
   * 后端这个端点已不再累加。留着只是为了不让老前端报错，新代码不要调用 ——
   * 否则推荐流点进详情会被算两次。
   *
   * @deprecated 详情接口已自动计数，无需再调
   */
  reportView(dishId) {
    return request('/ai/recommend/' + dishId + '/view', { method: 'POST', auth: true, service: 'dish' })
  },

  /** 上报互动。type: like（点赞）/ collect（收藏）/ cook（试做打卡） */
  reportInteract(dishId, type) {
    return request('/ai/recommend/' + dishId + '/interact?type=' + encodeURIComponent(type), {
      method: 'POST', auth: true, service: 'dish'
    })
  },

  // ================================================================ 发现流

  /**
   * 浏览模式：一屏列表。
   *
   * 每条都带匹配档位（齐活/差一点/还差 N 样）和缺料清单 ——
   * 旧榜单只给名次，用户看完不知道自己做得了做不了。
   *
   * @param {object} cond  { ingredientIds, ingredientNames, maxCookTimeMin, maxDifficulty, serving }
   */
  discoverBrowse(cond, page, size) {
    return request('/ai/discover/browse' + discoverQuery(cond,
      'page=' + (page || 1) + '&size=' + (size || 10)), { auth: true, service: 'dish' })
  },

  /**
   * 决策模式：一次备一批，"换一个"从这批里依次取。
   *
   * 为什么不是每次只取一道：每次重新请求会因为排序的微小抖动重复出同一道菜，
   * 用户会以为"换一个"坏了。一次取一批、前端本地推进，顺序就锁死了。
   *
   * @param {number} offset 从第几道开始取；这批用完后再取下一批时传 20、40……
   */
  discoverDecide(cond, offset) {
    return request('/ai/discover/decide' + discoverQuery(cond,
      'offset=' + (offset || 0)), { auth: true, service: 'dish' })
  },

  /** 收藏（幂等，重复点不会让收藏数虚增） */
  collect(dishId) {
    return request('/ai/dish/' + dishId + '/collect', { method: 'POST', auth: true, service: 'dish' })
  },

  /** 取消收藏（幂等） */
  uncollect(dishId) {
    return request('/ai/dish/' + dishId + '/collect', { method: 'DELETE', auth: true, service: 'dish' })
  },

  /** 打分（一人一票，重复打是改分）。cooked 表示是否真的做过 */
  rate(dishId, score, cooked) {
    return request('/ai/dish/' + dishId + '/rating', {
      method: 'POST',
      body: { score, cooked: !!cooked },
      auth: true,
      service: 'dish'
    })
  },

  /** 发表评论。rating 只用于展示，计分只认 rate() */
  comment(dishId, content, rating) {
    return request('/ai/dish/' + dishId + '/comment', {
      method: 'POST',
      body: { content, rating: rating || null },
      auth: true,
      service: 'dish'
    })
  },

  // ================================================================ 我的

  /** 我创建的菜品（含草稿） */
  myDishes() {
    return request('/ai/my/dishes', { auth: true, service: 'dish' })
  },

  /** 我收藏的菜品 */
  myCollects() {
    return request('/ai/my/collects', { auth: true, service: 'dish' })
  },

  // ================================================================ 手写菜谱

  /**
   * 手写菜谱「试算」：只把表单算一遍（营养 + 能不能发出去），**不落库**。
   *
   * 面板上边填边调它，用户就能提前看到"每人份多少 kcal、还差什么"，
   * 不用等提交完才发现克数没填、步骤不够 3 条。
   * 返回 ManualDishPreview：nutrition / items / problems / warnings / ready。
   */
  manualPreview(payload) {
    return request('/ai/dish/manual/preview', { method: 'POST', body: payload, auth: true, service: 'dish' })
  },

  /**
   * 手写菜谱「上传」：落库成 source=3（用户自定义）、nutrition_source=2（本地精确计算）的菜。
   *
   * 后端会先存草稿再试发布，返回 ManualDishResult：
   *   { dishId, published, problems, nutrition }
   * published=false 时 problems 里是"还差什么"（重名只有查库才知道，面板提前提示不了）。
   */
  createManual(payload) {
    return request('/ai/dish/manual', { method: 'POST', body: payload, auth: true, service: 'dish' })
  }
}

/* ============================================================
   管理员接口（说明文档 P0-2 / P1-6 / P2-2）

   后端对这些路径做了 fail-closed 授权：不是管理员一律 403，
   所以前端拿不到数据不是"接口坏了"，而是账号没有管理员角色。
   ============================================================ */
export const AdminApi = {
  // ---------------- 食材候选审核 ----------------
  //
  // ⚠️ 管理接口的路径**都带 `/ai` 前缀**：后端这些接口统一挂在 `DishPublishController`
  // 的 `@RequestMapping("/ai")` 下（`/ai/ingredients/...`、`/ai/es/...`、`/ai/admin/...`）。
  // 少写 `/ai` 不会报 404，而是落到没有 handler 的路径上、返回业务码 500「服务器开小差了」，
  // 前端只看到"错误框"，很容易误判成后端故障。
  // （这个 bug 就是前端 E2E 抓到的：后端直连的验证脚本全过，前端管理台却整页报错。）

  /** 待审候选（status=0 的那批） */
  ingredientCandidates(limit) {
    return request('/ai/ingredients/candidates?limit=' + (limit || 20),
      { auth: true, service: 'dish' })
  },

  /** 已审核过的候选（含审核人与审核说明，用来复盘"当初为什么放进来"） */
  reviewedCandidates(limit) {
    return request('/ai/ingredients/candidates/reviewed?limit=' + (limit || 50),
      { auth: true, service: 'dish' })
  },

  /** 通过：转正成正式食材（可批量） */
  approveIngredients(ids, reason) {
    return request('/ai/ingredients/candidates/approve', {
      method: 'POST', body: { ids, reason: reason || null }, auth: true, service: 'dish'
    })
  },

  /** 驳回：必须给理由（后端会校验）—— 没理由的驳回下次还会被同一个人提上来 */
  rejectIngredients(ids, reason) {
    return request('/ai/ingredients/candidates/reject', {
      method: 'POST', body: { ids, reason }, auth: true, service: 'dish'
    })
  },

  // ---------------- 索引同步（P1-6） ----------------

  /** 同步待办总览：pending 多说明索引在追；givenUp 不为 0 就必须人工介入 */
  esSyncStatus() {
    return request('/ai/es/sync/status', { auth: true, service: 'dish' })
  },

  /** 待办明细 */
  esSyncTasks(status, limit) {
    const qs = []
    if (status !== null && status !== undefined && status !== '') qs.push('status=' + status)
    qs.push('limit=' + (limit || 50))
    return request('/ai/es/sync/tasks?' + qs.join('&'), { auth: true, service: 'dish' })
  },

  /** 手动补偿：把失败/放弃的待办重置为立刻可执行，并马上跑一轮 */
  esSyncRetry() {
    return request('/ai/es/sync/retry', { method: 'POST', auth: true, service: 'dish' })
  },

  /** 全量重建索引。实测 30 道菜约 30 秒，按钮期间要禁用 */
  reindex() {
    return request('/ai/es/reindex', { method: 'POST', auth: true, service: 'dish' })
  },

  // ---------------- 营养（P2-2） ----------------

  /** 把库里标着"AI 估算"的菜按成分表重算，并返回新旧偏差统计 */
  recomputeNutrition() {
    return request('/ai/admin/nutrition/recompute', { method: 'POST', auth: true, service: 'dish' })
  },

  // ---------------- 强制作业 ----------------

  /** 强制下架（不需要是该菜的主人），用于处理违规或明显错误的内容 */
  forceUnpublish(dishId) {
    return request('/ai/admin/dish/' + dishId + '/unpublish',
      { method: 'POST', auth: true, service: 'dish' })
  }
}
