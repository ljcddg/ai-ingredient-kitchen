/* ============================================================
   通用工具：表单校验、复制、JSON 着色、密码强度
   （语义与原生版 frontend/js/ui.js + auth.js 的校验部分一致）
   ============================================================ */

/**
 * 表单校验规则。
 * 【必须与后端 DTO 严格对齐】—— 前后端都要拦，少一边都会出现"前端放过、后端报错"，
 * 或"前端拦住、后端其实允许"的错位。
 */
export const Validate = {
  /** 用户名：后端要求 4-20 位 */
  username(value) {
    if (!value) return '用户名不能为空'
    if (value.length < 4 || value.length > 20) return '用户名长度需在 4-20 位之间'
    return null
  },

  /** 密码：后端要求 6-32 位 */
  password(value) {
    if (!value) return '密码不能为空'
    if (value.length < 6 || value.length > 32) return '密码长度需在 6-32 位之间'
    return null
  },

  /** 昵称：后端要求最长 20 位，选填 */
  nickname(value) {
    if (value && value.length > 20) return '昵称最长 20 位'
    return null
  },

  confirm(pwd, confirmValue) {
    if (!confirmValue) return '请再输入一次密码'
    if (pwd !== confirmValue) return '两次输入的密码不一致'
    return null
  }
}

/**
 * 评估密码强度，返回 0-4。
 * 「长度 + 字符种类」打分，不追求科学精确，目的是给用户直观反馈。
 */
export function evaluatePasswordStrength(pwd) {
  if (!pwd) return 0

  let score = 0
  if (pwd.length >= 8) score++
  if (pwd.length >= 12) score++

  const kinds = [
    /[a-z]/.test(pwd),
    /[A-Z]/.test(pwd),
    /\d/.test(pwd),
    /[^A-Za-z0-9]/.test(pwd)
  ].filter(Boolean).length

  if (kinds >= 2) score++
  if (kinds >= 4) score++

  // 太短的话压低头部评分，避免「aA1!」这种 4 位就显示很强
  if (pwd.length < 8) score = Math.min(score, 1)

  return Math.min(score, 4)
}

export const STRENGTH_TEXTS = ['密码强度', '弱', '一般', '较强', '很强']

/** 复制到剪贴板（http 环境下 clipboard 会被禁用，保留 execCommand 兜底） */
export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch (e) {
    /* 落到下面的兜底方案 */
  }

  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    ta.remove()
    return ok
  } catch (e) {
    return false
  }
}

/** JSON 语法着色（用于结果展示） */
export function highlightJson(json) {
  const escaped = String(json)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  return escaped.replace(
    /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g,
    (match) => {
      if (/^"/.test(match)) {
        return /:$/.test(match)
          ? '<span class="k">' + match + '</span>'
          : '<span class="s">' + match + '</span>'
      }
      return match
    }
  )
}

/**
 * 雪花 ID 格式校验。
 * 【必须按字符串校验】—— 19 位数字超出 JS 安全整数范围，绝不能转 Number 再比。
 */
export function isSnowflakeId(v) {
  return /^\d{1,19}$/.test(String(v == null ? '' : v))
}
