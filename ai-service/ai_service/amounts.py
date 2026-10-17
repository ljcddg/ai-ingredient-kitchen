"""用量分位快照的读取口径 —— 提示词与校验器共用同一份。

为什么要单独一个模块：同一条"该放多少"的数据，有两个使用方 ——
  · 生成**前**：prompt 把常见用量写进提示词，让模型有可抄的数（治幻觉的源头）；
  · 生成**后**：validator 用同一份分位算上限，把离谱的量打回去（治幻觉的出口）。
两边如果各写一套"人数档"或"取哪个分位"，就会出现"提示词说 300g 正常、
校验器却判 300g 超标"的自相矛盾 —— 模型被教了一次又被打一次，重试也改不对。

人数档（1-2 / 3-4 / 5+）还必须与离线脚本 {@code sql/build-stats.py} 的 bucket_of 一致：
那边按这个档位统计，这边按这个档位查。不一致不会报错，只会静默查不到、全部退到固定上限 ——
优化失效但一切"看起来正常"，所以这份口径只能有一处定义。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Dict, Iterable, Optional, Sequence, Tuple

from .schemas import AmountStat, QuantityStat

# 单品样本不足、类别也不足时用固定档；这个常量在 validator 里，这里只声明来源标记
SOURCE_INGREDIENT = "ingredient"
SOURCE_CATEGORY = "category"
SOURCE_FIXED = "fixed"

# 统计分位算出来的上限/建议量，每人份不许低于这个值。
#
# 【为什么需要一个地板】p90 是从真实菜谱里数出来的：香菜、小米辣这类"点缀型"食材，
# 库里每道菜只用 5~10g，p90 也是 10g，乘 1.5 之后上限只有 15g/人 ——
# 而"香菜拌牛肉"放 50g 香菜完全正常。不设地板会把正常菜判成用量幻觉，
# 触发一轮 12~26 秒的无谓重试，最后还可能失败。
# 80g/人 的量级：比任何点缀用料都宽，又远低于主食/肉的正常量，两头都不误伤。
STAT_FLOOR_PER_PERSON = 80


def serving_bucket(servings: int) -> str:
    """人数 → 统计档。与 sql/build-stats.py 的 bucket_of 逐字一致。"""
    if servings <= 2:
        return "1-2"
    if servings <= 4:
        return "3-4"
    return "5+"


@dataclass
class AmountBasis:
    """一次用量查询的结果：分位值 + 它是从哪一级取到的。"""

    p50: Optional[float] = None
    p90: Optional[float] = None
    source: str = SOURCE_FIXED

    def has_stat(self) -> bool:
        return self.p90 is not None and self.p90 > 0


class AmountIndex:
    """两级分位的内存索引（随请求构建，构造后只读）。

    刻意不做缓存：库里只有 34 + 8 行，构建成本微不足道；
    而 dish-service 是每请求注入快照的 —— 脚本一重建，下一个请求就是新口径，
    不需要任何"缓存什么时候失效"的逻辑。
    """

    def __init__(self, amount_stats: Optional[Sequence[AmountStat]] = None,
                 quantity_stats: Optional[Sequence[QuantityStat]] = None):
        # (食材ID, 人数档) → (p50, p90)
        self._by_ingredient: Dict[Tuple[int, str], Tuple[float, float]] = {}
        for stat in amount_stats or []:
            if stat is None or stat.ingredient_id is None or not stat.bucket:
                continue
            if stat.p90 is None or stat.p90 <= 0:
                continue
            self._by_ingredient[(stat.ingredient_id, stat.bucket)] = (
                float(stat.p50 or stat.p90), float(stat.p90))

        # (类目, 人数档) → (p50, p90)
        self._by_category: Dict[Tuple[str, str], Tuple[float, float]] = {}
        for stat in quantity_stats or []:
            if stat is None or not stat.category or not stat.bucket:
                continue
            if stat.p90 is None or stat.p90 <= 0:
                continue
            self._by_category[(stat.category, stat.bucket)] = (
                float(stat.p50 or stat.p90), float(stat.p90))

    def size(self) -> Tuple[int, int]:
        """(单品组数, 类别组数) —— 日志里用它确认统计到底注入没有。"""
        return len(self._by_ingredient), len(self._by_category)

    def lookup(self, ingredient_id: Optional[int], category: Optional[str],
               bucket: str) -> AmountBasis:
        """按 单品 → 类别 的顺序取分位；都没有就返回 source='fixed'。"""
        if ingredient_id is not None:
            hit = self._by_ingredient.get((ingredient_id, bucket))
            if hit is not None:
                return AmountBasis(hit[0], hit[1], SOURCE_INGREDIENT)
        if category:
            hit = self._by_category.get((category, bucket))
            if hit is not None:
                return AmountBasis(hit[0], hit[1], SOURCE_CATEGORY)
        return AmountBasis()

    def any_for(self, ingredient_id: Optional[int], category: Optional[str],
                bucket: str) -> bool:
        return self.lookup(ingredient_id, category, bucket).has_stat()


def usable(items: Iterable) -> bool:
    """快照里到底有没有可用数据（给"要不要加这一段"的判断用）。"""
    return any(item is not None for item in items or [])
