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
  }
}
