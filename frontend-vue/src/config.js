/* ============================================================
   全局配置（Vue 版，与原生版 frontend/js/config.js 语义一一对应）

   【迁移原则：这块逻辑必须原样保留，一行都不能"优化"】

   它决定了"能不能给别人看"：若接口地址写死 127.0.0.1，
   访客打开公网地址后，他的浏览器会去请求**他自己那台电脑**的端口，
   报"连接被拒绝"——看起来像后端挂了，实际请求根本没出他那台机器。
   ============================================================ */

const GATEWAY_PORT = 7000

// 纯静态服务器端口。原生版 `python -m http.server 5500` 属于这类。
// Vue 开发服务器默认也用 5500，但它带 /api 代理，所以下面规则 1 要排除 dev 情形。
const STATIC_PORT = '5500'

/**
 * 推断接口基地址。
 *
 * 规则（与原生版一致）：
 *   1. file:// 或没有源 → 只能假设后端在本机，走网关 7000
 *   2. 页面来自纯静态服务器（5500）→ 走本机网关 7000
 *   3. 其他任何来源（公网隧道 / 反向代理 / 未来域名部署）→ **同源**
 *
 * 【Vue 版差异说明】
 * Vite dev server 跑在 5500，但它自带 /api 代理，所以这里对 5500 的推断
 * 分两种：有代理时同源，没代理时打网关。用 __VUE_DEV_PROXY__ 编译期常量区分，
 * 由 vite.config.js 注入；生产构建下恒为 false，走原生版同款逻辑。
 */
function detectDefaultApiBase() {
  const here = location

  if (here.protocol === 'file:' || !here.origin) {
    return 'http://127.0.0.1:' + GATEWAY_PORT
  }

  if (here.port === STATIC_PORT) {
    // Vite dev server 自带 /api 代理 → 同源即可（浏览器连预检都不会发）
    if (__VUE_DEV_PROXY__) {
      return here.origin
    }
    return 'http://127.0.0.1:' + GATEWAY_PORT
  }

  return here.origin
}

const DEFAULT_API_BASE = detectDefaultApiBase()

export const STORAGE_KEYS = {
  API_BASE: 'kitchen.apiBase',
  REMEMBERED_USER: 'kitchen.rememberedUser',
  TOKEN: 'kitchen.token',
  USER_INFO: 'kitchen.userInfo',
  // 已经点进去看过的任务 id —— 只决定任务行「展开还是收起」，不是业务数据
  VIEWED_TASKS: 'kitchen.viewedTasks'
}

const API_PREFIX_GATEWAY = '/api/account'
const API_PREFIX_DIRECT = ''

/** 直连模式下各业务服务的端口（走网关时用不到，网关按 /api/<服务> 路由） */
const DIRECT_PORTS = { account: '8081', dish: '8082' }

export const AppConfig = {
  get apiBase() {
    return localStorage.getItem(STORAGE_KEYS.API_BASE) || DEFAULT_API_BASE
  },

  set apiBase(value) {
    localStorage.setItem(STORAGE_KEYS.API_BASE, String(value).replace(/\/+$/, ''))
  },

  /**
   * 当前是否走网关（决定接口路径要不要带 /api 前缀）。
   * 命中任一条即为「走网关」：
   *   1. base 里显式带了 /api
   *   2. base 就是当前源 —— 入口服务/网关托管页面，接口走同源 /api/...
   *   3. base 指向本机网关端口 7000
   */
  get isViaGateway() {
    const base = this.apiBase
    if (base.includes('/api')) return true
    if (base === location.origin) return true
    return base.includes(':' + GATEWAY_PORT)
  },

  get prefix() {
    return this.isViaGateway ? API_PREFIX_GATEWAY : API_PREFIX_DIRECT
  },

  /** 拼出完整接口地址（account 服务专用，等价于 serviceUrl('account', path)） */
  url(path) {
    return this.apiBase + this.prefix + path
  },

  /**
   * 按服务名拼接口地址。
   *   走网关 -> http://127.0.0.1:7000/api/dish/search
   *   直连   -> http://127.0.0.1:8082/search
   */
  serviceUrl(service, path) {
    const base = this.apiBase

    if (this.isViaGateway) {
      return base + '/api/' + service + path
    }

    const port = DIRECT_PORTS[service]
    const u = new URL(base)

    if (!port || u.port === port) {
      return base + path
    }
    return u.protocol + '//' + u.hostname + ':' + port + path
  }
}
