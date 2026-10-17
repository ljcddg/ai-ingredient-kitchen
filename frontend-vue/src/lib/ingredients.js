/* ============================================================
   食材 / 口味 / 人数 数据（与原生版 frontend/js/ingredients.js 一致）
   ============================================================ */

/**
 * 食材分组（共 152 种）。
 *
 * 【id 必须和后端 t_ingredient 的 id 一一对应 —— 这是硬约束】
 * 前端点选后传给后端的是 id，后端拿 id 去主数据查；查不到只会打一条 warn
 * 然后**静默跳过**（见 IngredientResolver）。所以「只加前端、不加库」的后果是：
 * 用户选了某样食材，生成的菜里却没有它，而且**没有任何报错**。
 *
 * 【为什么分 5 组而不是按 DB 的 category】DB 的 category 是给营养统计用的细分类，
 * 拆得太碎；这里合并成 5 个人找食材时的直觉分类。
 *
 * 【排序原则】组内把「最可能出现在家常菜里」的排在前面 ——
 * 152 个食材不可能一屏放下，前 6~8 个的曝光度决定了大多数用户会不会去滚动。
 * 189 之后的第三批同样按这个原则排：先夏天常见的苦瓜/丝瓜，再往后是更专用的。
 */
export const INGREDIENT_GROUPS = [
  {
    name: '蔬菜菌菇',
    items: [
      { id: 101, name: '番茄' }, { id: 105, name: '土豆' }, { id: 113, name: '胡萝卜' },
      { id: 108, name: '青椒' }, { id: 114, name: '洋葱' }, { id: 103, name: '葱' },
      { id: 115, name: '大蒜' }, { id: 116, name: '生姜' }, { id: 106, name: '韭菜' },
      { id: 126, name: '香菇' }, { id: 123, name: '白菜' }, { id: 121, name: '茄子' },
      { id: 122, name: '黄瓜' }, { id: 124, name: '菠菜' }, { id: 125, name: '西兰花' },
      { id: 130, name: '芹菜' }, { id: 131, name: '生菜' }, { id: 132, name: '豆角' },
      { id: 135, name: '西葫芦' }, { id: 136, name: '冬瓜' }, { id: 129, name: '南瓜' },
      { id: 133, name: '玉米' }, { id: 128, name: '莲藕' }, { id: 134, name: '山药' },
      { id: 137, name: '莴笋' }, { id: 138, name: '豌豆' }, { id: 139, name: '红薯' },
      { id: 127, name: '金针菇' }, { id: 140, name: '木耳' },
      /* ---- 第三批（189~206）：菜市场每天有、但库里以前没有的 ---- */
      { id: 189, name: '苦瓜' }, { id: 190, name: '丝瓜' }, { id: 195, name: '花菜' },
      { id: 196, name: '卷心菜' }, { id: 202, name: '油菜' }, { id: 197, name: '白萝卜' },
      { id: 200, name: '豆芽' }, { id: 193, name: '空心菜' }, { id: 191, name: '秋葵' },
      { id: 192, name: '芦笋' }, { id: 194, name: '茼蒿' }, { id: 198, name: '竹笋' },
      { id: 199, name: '芋头' }, { id: 201, name: '紫甘蓝' }, { id: 203, name: '平菇' },
      { id: 204, name: '杏鲍菇' }, { id: 205, name: '口蘑' }, { id: 206, name: '银耳' }
    ]
  },
  {
    name: '肉蛋水产',
    items: [
      { id: 102, name: '鸡蛋' }, { id: 109, name: '猪肉' }, { id: 104, name: '牛腩' },
      { id: 142, name: '鸡胸肉' }, { id: 111, name: '虾仁' }, { id: 141, name: '牛肉' },
      { id: 143, name: '五花肉' }, { id: 144, name: '排骨' }, { id: 145, name: '鸡翅' },
      { id: 148, name: '羊肉' }, { id: 149, name: '鸭肉' }, { id: 146, name: '培根' },
      { id: 147, name: '香肠' }, { id: 150, name: '火腿' }, { id: 151, name: '鲈鱼' },
      { id: 153, name: '带鱼' }, { id: 152, name: '三文鱼' }, { id: 154, name: '鱿鱼' },
      { id: 155, name: '蛤蜊' }, { id: 156, name: '螃蟹' },
      /* ---- 第三批（207~221）---- */
      { id: 207, name: '猪里脊' }, { id: 208, name: '鸡腿' }, { id: 210, name: '牛腱' },
      { id: 213, name: '鲫鱼' }, { id: 214, name: '草鱼' }, { id: 209, name: '鸡爪' },
      { id: 212, name: '猪蹄' }, { id: 211, name: '腊肉' }, { id: 215, name: '黑鱼' },
      { id: 216, name: '黄鱼' }, { id: 217, name: '鳕鱼' }, { id: 218, name: '龙利鱼' },
      { id: 219, name: '扇贝' }, { id: 220, name: '牡蛎' }, { id: 221, name: '虾皮' }
    ]
  },
  {
    name: '豆制品与主食',
    items: [
      { id: 110, name: '豆腐' }, { id: 112, name: '米饭' }, { id: 107, name: '面粉' },
      { id: 157, name: '豆干' }, { id: 158, name: '腐竹' }, { id: 159, name: '面条' },
      { id: 160, name: '粉丝' }, { id: 166, name: '米粉' }, { id: 161, name: '年糕' },
      { id: 162, name: '馒头' }, { id: 163, name: '饺子皮' }, { id: 164, name: '燕麦' },
      { id: 165, name: '意面' }, { id: 168, name: '海带' }, { id: 167, name: '紫菜' },
      /* ---- 第三批（222~232）---- */
      { id: 229, name: '大米' }, { id: 232, name: '河粉' }, { id: 222, name: '豆皮' },
      { id: 223, name: '千张' }, { id: 226, name: '红豆' }, { id: 227, name: '小米' },
      { id: 228, name: '糯米' }, { id: 231, name: '玉米面' }, { id: 230, name: '全麦面包' },
      { id: 224, name: '黄豆' }, { id: 225, name: '绿豆' }
    ]
  },
  {
    name: '调味',
    items: [
      { id: 117, name: '生抽' }, { id: 118, name: '盐' }, { id: 120, name: '醋' },
      { id: 119, name: '白糖' }, { id: 169, name: '老抽' }, { id: 170, name: '蚝油' },
      { id: 171, name: '料酒' }, { id: 180, name: '芝麻油' }, { id: 174, name: '豆瓣酱' },
      { id: 178, name: '辣椒酱' }, { id: 173, name: '干辣椒' }, { id: 172, name: '花椒' },
      { id: 175, name: '孜然' }, { id: 176, name: '黑胡椒' }, { id: 177, name: '五香粉' },
      { id: 179, name: '蜂蜜' },
      /* ---- 第三批（233~242）---- */
      { id: 233, name: '淀粉' }, { id: 234, name: '番茄酱' }, { id: 238, name: '白胡椒粉' },
      { id: 242, name: '鸡精' }, { id: 237, name: '咖喱粉' }, { id: 236, name: '黄豆酱' },
      { id: 235, name: '甜面酱' }, { id: 239, name: '八角' }, { id: 240, name: '香叶' },
      { id: 241, name: '桂皮' }
    ]
  },
  {
    name: '乳品果香',
    items: [
      { id: 181, name: '牛奶' }, { id: 184, name: '柠檬' }, { id: 185, name: '香菜' },
      { id: 183, name: '芝士' }, { id: 182, name: '淡奶油' }, { id: 186, name: '花生' },
      { id: 187, name: '芝麻' }, { id: 188, name: '核桃' },
      /* ---- 第三批（243~252）---- */
      { id: 245, name: '苹果' }, { id: 246, name: '香蕉' }, { id: 247, name: '橙子' },
      { id: 243, name: '酸奶' }, { id: 250, name: '红枣' }, { id: 251, name: '枸杞' },
      { id: 248, name: '菠萝' }, { id: 249, name: '芒果' }, { id: 252, name: '腰果' },
      { id: 244, name: '黄油' }
    ]
  }
]

/**
 * 口味标签。
 *
 * 【取值必须和 ES 文档里的 tasteTags 严格一致】否则筛选一条都命中不了。
 * 所以这里没有随手扩充 —— 加标签必须同时改 es/init-dish-index.py 的样本数据。
 * 这是"前端枚举 与 后端索引取值"必须同源的典型例子。
 */
export const TASTE_TAGS = ['家常', '清淡', '咸香', '炖煮', '微辣', '酸甜']

/** 人数分段。后端接收的是具体人数，这里只是给用户一个省事的档位 */
export const SERVING_OPTIONS = [
  { label: '1 人', value: 1 },
  { label: '2 人', value: 2 },
  { label: '3-4 人', value: 4 },
  { label: '5 人以上', value: 6 }
]

/**
 * 分组色相。88 个按钮全灰会非常单调，但给每个食材配图标不现实。
 * 退一步：按**分类**给一个色点 —— 一屏里能靠颜色快速定位到「我要找蔬菜」。
 * 取值都是低饱和浅色，只做区分，不抢品牌红（--accent）的视觉优先级。
 */
export const GROUP_TONE = {
  '蔬菜菌菇': '#5f9e63',
  '肉蛋水产': '#c8664f',
  '豆制品与主食': '#b98a3c',
  '调味': '#8a6a4f',
  '乳品果香': '#8a6ea8'
}

/* ---------- 便捷查询（原生版这几个函数在 kitchen.js 里） ---------- */

/** 按 id 找名字；找不到就把 id 原样返回 */
export function nameOfIngredient(id) {
  for (const group of INGREDIENT_GROUPS) {
    const hit = group.items.find(i => i.id === id)
    if (hit) return hit.name
  }
  return String(id)
}

/** 按名字在本地食材表里找 ID（只精确匹配，别名归一交给后端） */
export function findIngredientIdByName(name) {
  const key = String(name || '').toLowerCase()
  for (const group of INGREDIENT_GROUPS) {
    const hit = group.items.find(i => i.name.toLowerCase() === key)
    if (hit) return hit.id
  }
  return null
}

/** 本地食材表里的全部条目（带所属分组），供联想使用 */
export function allLocalIngredients() {
  const out = []
  for (const group of INGREDIENT_GROUPS) {
    for (const item of group.items) {
      out.push({ id: item.id, name: item.name, group: group.name })
    }
  }
  return out
}
