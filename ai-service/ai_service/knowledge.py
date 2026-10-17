"""烹饪常识知识库（离线检索）。

为什么要有这一层：小模型知道"番茄炒蛋怎么做"，但不知道
"肉要冷水下锅焯""芡汁淀粉水 1:5 出锅前淋"这类**操作规程**。
这些不是检索菜谱能给的东西（菜谱只给结果，不给原理），
但它们恰恰决定生成出来的菜"照着做能不能成"。

做法上刻意保持最轻：
  · 知识是静态 JSON，随仓库走，不联网、不建向量库
  · 召回用标签词面匹配（食材类别 + 口味 + 约束命中的关键词），
    条目只有十几条，词面匹配足够，上向量索引是过度设计
  · 只注入 3 条：小模型上下文有限，塞多了反而挤掉输出空间

与"参考菜谱"的区别：参考菜谱回答"这道菜长什么样"，
知识库回答"这一步为什么这么做"。两者互不替代。
"""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Iterable, List, Optional, Sequence

logger = logging.getLogger(__name__)

KNOWLEDGE_PATH = Path(__file__).resolve().parent.parent / "knowledge" / "cooking-rules.json"

# 一次最多注入几条：3 条 ≈ 200 token，再多会挤占输出预算
MAX_RULES = 3

# "通用"类条目的基础分：任何菜都可能用得上，但不该压过命中类别的条目
GENERIC_SCORE = 0.5


def load_rules(path: Optional[Path] = None) -> List[dict]:
    """读知识库。读不到不算错误 —— 没有知识库照样能生成，只是少了这层提示。"""
    file = path or KNOWLEDGE_PATH
    try:
        with open(file, "r", encoding="utf-8") as f:
            data = json.load(f)
        rules = data.get("rules") if isinstance(data, dict) else data
        return [r for r in (rules or []) if isinstance(r, dict) and r.get("text")]
    except FileNotFoundError:
        logger.warning("烹饪知识库不存在（%s），本次不注入常识", file)
        return []
    except Exception as e:  # 坏文件也不能拖垮生成
        logger.warning("烹饪知识库解析失败（%s），本次不注入常识", e)
        return []


class CookingKnowledge:
    """知识库检索器：无状态，可复用。"""

    def __init__(self, rules: Optional[Sequence[dict]] = None):
        self.rules = list(rules) if rules is not None else load_rules()

    def select(self, categories: Iterable[str], extra_terms: Iterable[str] = (),
               limit: int = MAX_RULES) -> List[dict]:
        """按命中程度挑条目。

        @param categories 本次用到的食材类别（蔬菜/肉类/水产/…）
        @param extra_terms 口味、技法等额外关键词（如「清淡」「炖煮」「少油」）
        """
        cat_set = {c for c in categories if c}
        term_set = {t for t in extra_terms if t}

        scored = []
        for index, rule in enumerate(self.rules):
            tags = set(rule.get("tags") or [])
            score = 0.0
            score += sum(1.0 for tag in tags if tag in cat_set)
            score += sum(1.0 for tag in tags if tag in term_set)
            if "通用" in tags:
                score += GENERIC_SCORE
            if score > 0:
                scored.append((score, int(rule.get("priority") or 0), index, rule))

        # 排序口径：命中分 → 人工优先级 → 知识库顺序。
        #
        # 【为什么需要 priority】"通用"类条目都吃同一个 0.5 底分，只按分数排的话，
        # 永远是文件里排在最前的那三条被选中，后面补的条目（盐后放、蒸制时间、
        # 低盐提鲜）永远轮不到 —— 知识库加了等于没加。priority 让"普适且最常被违反"
        # 的那几条稳定优先，而不是靠文件顺序这种隐式约定。
        #
        # 【为什么最后一档用的是文件顺序】同一请求两次生成必须注入同一批常识，
        # 否则 A/B 对比里"生成质量波动"会混进"知识注入波动"，实验就没法解释。
        scored.sort(key=lambda pair: (-pair[0], -pair[1], pair[2]))
        picked: List[dict] = []
        for _, _, _, rule in scored:
            if len(picked) >= limit:
                break
            picked.append(rule)
        return picked

    def render(self, rules: Sequence[dict]) -> str:
        """渲染成提示词里的【烹饪常识】段；空列表返回空串。"""
        if not rules:
            return ""
        lines = ["【烹饪常识】（这次做菜要注意的操作要点）"]
        for rule in rules:
            lines.append("- " + str(rule["text"]).strip())
        return "\n".join(lines)


def categories_of(directory, ingredients) -> List[str]:
    """从请求的食材里取类别，供召回使用。

    取不到（表外食材、主数据没有）就跳过 —— 知识库召回宁少不滥。
    """
    categories: List[str] = []
    for ref in ingredients or []:
        if ref is None:
            continue
        ingredient_id = getattr(ref, "id", None)
        if ingredient_id is None or ingredient_id <= 0:
            continue
        try:
            category = directory.category_of(ingredient_id)
        except Exception:
            category = None
        if category and category not in categories:
            categories.append(category)
    return categories
