/* ============================================================
   漂浮装饰（Vue 版）：冰淇淋 / 气球 / 纸杯蛋糕 / 甜甜圈 / 樱桃 / 星星
   ------------------------------------------------------------
   与原生版 frontend/js/floaters.js 的算法**逐行一致**：
   同一个固定种子（20260914）、同一份 plan 配比、同一条品牌文字保护带（4%~23%），
   所以两版生成的布局完全相同 —— 这样迁移前后的视觉可以逐像素对照。

   差异只在"产出形式"：原生版直接 createElement + appendChild，
   Vue 版改成返回一个数据数组交给组件渲染（不再命令式操作 DOM）。
   ============================================================ */

/* 固定种子随机：保证每次刷新布局一致，不会闪来闪去 */
function makeRandom(seed) {
  let s = seed >>> 0
  return function () {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
}

/**
 * 每个元素是一个返回 SVG 字符串的函数。
 * 统一约定：viewBox="0 0 48 64"，造型自下而上排布，底部留空给「绳子 / 甜筒尖」。
 */
export const FLOATER_SHAPES = {
  /* ---------- 冰淇淋甜筒 ---------- */
  icecream: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M13.5 33h21L24.6 59.5a.7.7 0 0 1-1.2 0L13.5 33Z"
                  fill="#e0a15c" stroke="rgba(140,88,30,.35)" stroke-width="1.2" stroke-linejoin="round"/>
            <path d="M17.4 33 24 48.6M30.6 33 24 48.6M20.6 33l3.4 8.2M27.4 33 24 41.2"
                  stroke="rgba(140,88,30,.32)" stroke-width="1.1" stroke-linecap="round"/>
            <circle cx="24" cy="30" r="10.5" fill="#8d5a44"/>
            <circle cx="20.4" cy="26.6" r="3" fill="rgba(255,255,255,.22)"/>
            <circle cx="24" cy="20" r="11" fill="#f48ba4"/>
            <circle cx="20" cy="15.6" r="3.4" fill="rgba(255,255,255,.42)"/>
            <circle cx="24" cy="6.6" r="4.4" fill="#e2564a"/>
            <path d="M24 2.4c1.2-2 3.4-2.4 4.8-1.8" stroke="#4d8b4a"
                  stroke-width="1.6" stroke-linecap="round"/>
        </svg>`,

  /* ---------- 蛋筒冰淇淋（单球 + 蛋卷） ---------- */
  cone: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M14 34h20L24.6 59.6a.7.7 0 0 1-1.2 0L14 34Z" fill="#dda05f"
                  stroke="rgba(140,88,30,.35)" stroke-width="1.2" stroke-linejoin="round"/>
            <path d="M18 34l6 15.4M30 34l-6 15.4M21 34l3 7.7M27 34l-3 7.7"
                  stroke="rgba(140,88,30,.3)" stroke-width="1.1" stroke-linecap="round"/>
            <path d="M15.5 32c0-5 3.8-9 8.5-9s8.5 4 8.5 9"
                  fill="#fff5e2" stroke="rgba(196,146,90,.4)" stroke-width="1.2"/>
            <path d="M18.5 24.5c1 2.8 3 4.2 5.5 4.2s4.5-1.4 5.5-4.2"
                  stroke="rgba(196,146,90,.38)" stroke-width="1.2" stroke-linecap="round"/>
            <path d="M19 17.5c0-4 2.3-6.5 5-6.5s5 2.5 5 6.5"
                  fill="#fff8ea" stroke="rgba(196,146,90,.35)" stroke-width="1.2"/>
            <circle cx="24" cy="7.4" r="3.4" fill="#f2933c"/>
        </svg>`,

  /* ---------- 气球 ---------- */
  balloon: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <ellipse cx="24" cy="22" rx="14" ry="16.5" fill="currentColor"/>
            <ellipse cx="18.4" cy="15" rx="4.6" ry="6" fill="rgba(255,255,255,.42)"/>
            <path d="M21.4 38.2h5.2l-1.4 3.4h-2.4l-1.4-3.4Z" fill="currentColor"/>
            <path d="M24 41.6c-3.4 4.4 3.4 7.6 0 12-2.6 3.4 1 5.6 1 5.6"
                  stroke="rgba(120,100,90,.5)" stroke-width="1.3"
                  stroke-linecap="round" fill="none"/>
        </svg>`,

  /* ---------- 纸杯蛋糕 ---------- */
  cupcake: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M13 30h22l-2.6 17.4c-.3 2-2 3.4-4 3.4h-8.8c-2 0-3.7-1.4-4-3.4L13 30Z"
                  fill="#e8825f"/>
            <path d="M18 30l1.4 20.6M24 30v20.8M30 30l-1.4 20.6"
                  stroke="rgba(140,60,30,.26)" stroke-width="1.2"/>
            <path d="M10.6 30.5c0-4.4 4-8 9.4-8h8c5.4 0 9.4 3.6 9.4 8H10.6Z" fill="#f7a8bd"/>
            <path d="M13.4 22.6c0-3.8 3.4-6.8 7.6-6.8h6c4.2 0 7.6 3 7.6 6.8" fill="#fbc4d4"/>
            <path d="M16.6 16.2c0-3.2 2.8-5.8 6.2-5.8h2.4c3.4 0 6.2 2.6 6.2 5.8" fill="#fdd9e3"/>
            <circle cx="24" cy="7.6" r="4" fill="#e2564a"/>
            <path d="M24 3.8c.9-1.8 2.8-2.2 4-1.7" stroke="#4d8b4a"
                  stroke-width="1.5" stroke-linecap="round"/>
        </svg>`,

  /* ---------- 甜甜圈 ---------- */
  donut: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="24" cy="32" r="18" fill="#e8a866"/>
            <circle cx="24" cy="32" r="18" fill="none"
                    stroke="rgba(150,95,40,.25)" stroke-width="1.2"/>
            <path d="M7.4 27c1.8-7.4 8.4-12.6 16.6-12.6S38.8 19.6 40.6 27c-2.4 1.4-5 .4-7-1.2-2 2-5 2.2-7.2.6
                     -2.2 2.2-5.6 2.2-7.8.2-2 2-5 2.2-7.2.6-1.8 1.4-3.6 1-4-.2Z"
                  fill="#f7a8bd"/>
            <circle cx="16" cy="24" r="1.5" fill="#fff" opacity=".85"/>
            <circle cx="24" cy="20.4" r="1.5" fill="#ffe08a"/>
            <circle cx="32" cy="24" r="1.5" fill="#8fd0e8"/>
            <circle cx="20" cy="29.4" r="1.4" fill="#a8e6b8"/>
            <circle cx="28.6" cy="29" r="1.4" fill="#fff"/>
            <circle cx="24" cy="32" r="6" fill="var(--page)"/>
            <circle cx="24" cy="32" r="6" fill="none"
                    stroke="rgba(150,95,40,.22)" stroke-width="1.1"/>
        </svg>`,

  /* ---------- 樱桃 ---------- */
  cherry: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M22 16c6-8 14-9 20-5" stroke="#4d8b4a"
                  stroke-width="2.2" stroke-linecap="round" fill="none"/>
            <path d="M22 16c-1 6-3 10-6 14" stroke="#4d8b4a"
                  stroke-width="2.2" stroke-linecap="round" fill="none"/>
            <circle cx="36" cy="28" r="11" fill="#e2564a"/>
            <circle cx="36" cy="28" r="11" fill="none"
                    stroke="rgba(160,40,30,.25)" stroke-width="1.2"/>
            <circle cx="32" cy="24" r="3.2" fill="rgba(255,255,255,.45)"/>
            <circle cx="16" cy="37" r="10" fill="#c8443a"/>
            <circle cx="12.6" cy="33.4" r="2.8" fill="rgba(255,255,255,.38)"/>
        </svg>`,

  /* ---------- 星星糖 ---------- */
  star: () => `
        <svg viewBox="0 0 48 64" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M24 18l4.2 8.6 9.4 1.3-6.8 6.6 1.6 9.4L24 39.5l-8.4 4.4 1.6-9.4-6.8-6.6 9.4-1.3L24 18Z"
                  fill="currentColor"/>
            <path d="M24 18l4.2 8.6 9.4 1.3-6.8 6.6 1.6 9.4L24 39.5l-8.4 4.4 1.6-9.4-6.8-6.6 9.4-1.3L24 18Z"
                  fill="none" stroke="rgba(120,80,30,.18)" stroke-width="1.2" stroke-linejoin="round"/>
        </svg>`
}

/*
 * 逐条手写的「配比」，比纯随机好看得多：
 * [形状, 大小(px), 透明度, 是否加 blur]
 * 尺寸分三档，对应「近 / 中 / 远」三层景深。
 */
const PLAN = [
  // ---- 近景 ----
  ['icecream', 92, 0.95, false],
  ['balloon', 84, 0.90, false],
  ['cone', 80, 0.92, false],
  ['cupcake', 78, 0.88, false],
  ['icecream', 74, 0.90, false],
  ['balloon', 88, 0.86, false],
  // ---- 中景 ----
  ['donut', 66, 0.78, false],
  ['cherry', 52, 0.82, false],
  ['icecream', 62, 0.76, false],
  ['star', 40, 0.74, false],
  ['balloon', 60, 0.72, false],
  ['cone', 56, 0.70, false],
  ['cupcake', 58, 0.68, false],
  ['donut', 54, 0.66, false],
  ['cherry', 48, 0.70, false],
  ['balloon', 64, 0.74, false],
  ['star', 36, 0.66, false],
  ['icecream', 68, 0.72, false],
  // ---- 远景（带模糊）----
  ['cherry', 34, 0.48, true],
  ['donut', 42, 0.44, true],
  ['balloon', 44, 0.42, true],
  ['star', 32, 0.40, true],
  ['cone', 38, 0.46, true],
  ['cupcake', 40, 0.44, true],
  ['balloon', 30, 0.36, true],
  ['cherry', 30, 0.40, true]
]

/* 配色：都是能跟暖色背景和睦相处的低饱和色（气球和星星用 currentColor 取色） */
const PALETTE = [
  '#ffd166', '#a8d5e2', '#f4a2b8', '#bfe3c0',
  '#f8b76b', '#d9c2f0', '#ffb3a0', '#ffe4a8'
]

/*
 * 「品牌文字保护带」。
 * 落进 4%~23% 这条带的元素自动降级为远景：缩小 + 压淡 + 加模糊，
 * 既留住左侧生气，又不糊住文字。
 */
const TEXT_ZONE = { from: 4, to: 23 }
const ZONE_MAX_SIZE = 40
const ZONE_MAX_OPACITY = 0.42

/**
 * 生成漂浮元素描述数组（纯函数，无副作用）。
 * 动画全在 CSS（--dur / --delay / --sway / --rot / --size / --op）。
 */
export function buildFloaters() {
  const rand = makeRandom(20260914)
  const lanes = PLAN.length
  const order = PLAN.map((_, i) => i)

  // 用固定种子打乱车道顺序，让同类形状不挨着
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(rand() * (i + 1))
    ;[order[i], order[j]] = [order[j], order[i]]
  }

  return PLAN.map(([shape, size, opacity, blurred], index) => {
    const lane = order[index]
    const leftPct = (lane + 0.5) / lanes * 100 + (rand() - 0.5) * (100 / lanes) * 0.9

    let finalSize = size
    let finalOpacity = opacity
    let soft = blurred

    if (leftPct >= TEXT_ZONE.from && leftPct <= TEXT_ZONE.to) {
      finalSize = Math.min(size, ZONE_MAX_SIZE)
      finalOpacity = Math.min(opacity, ZONE_MAX_OPACITY)
      soft = true
    }

    return {
      id: index,
      html: FLOATER_SHAPES[shape](),
      soft,
      color: PALETTE[Math.floor(rand() * PALETTE.length)],
      style: {
        left: leftPct.toFixed(2) + '%',
        '--size': finalSize + 'px',
        '--op': finalOpacity.toFixed(2),
        '--dur': (22 + rand() * 22).toFixed(1) + 's',
        '--delay': (-rand() * 44).toFixed(1) + 's',
        '--sway-dur': (6 + rand() * 6).toFixed(1) + 's',
        '--sway': (14 + rand() * 30).toFixed(1) + 'px',
        '--rot': ((rand() - 0.5) * 30).toFixed(1) + 'deg',
        '--top': (6 + rand() * 84).toFixed(1) + '%'
      }
    }
  })
}
