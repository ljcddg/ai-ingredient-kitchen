"""营养本地计算 —— NutritionCalculator + AiDishGenerator 营养校验的 Python 移植。

口径：食材成分表 × 用量 ÷ 人数。主数据快照里每样食材都有每 100 克的
热量/蛋白/脂肪/碳水/纤维/钠，乘上这道菜实际用了多少克，就是这一锅的总量，
再按人数均分。

为什么不能无条件相信本地结果：用户自由输入的食材（主数据里还没有的，
比如"香茅"）对不上成分表。如果只对上 1 样就算总数，会算出严重偏低的值，
比 AI 估算还不可信。所以用克重覆盖率把关：能对上成分表的克重占比低于
MIN_COVERAGE 就不采用，宁可继续标注"AI 估算"。

已知口径边界（写作时要交代）：
- 调料不计入：草稿里调料只有名字没有用量，没法算。所以本地值系统性地
  略低于真实值（一勺油约 90 千卡），这也是它只用于横向对比、不宣称"实测值"的原因；
- 主数据是每 100 克参考值，与具体品种、部位有关，本身有误差；
- 因此 nutrition_source=2 只表示"这份数值是按成分表算出来的"，不代表实验室测定值。
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import List, Optional

from .directory import Directory
from .schemas import DishDraft

logger = logging.getLogger(__name__)

# 本地计算可用的最低克重覆盖率
MIN_COVERAGE = 0.8


@dataclass
class Item:
    """一样食材：名字 + 克数。"""

    name: str
    grams: Optional[float] = None


@dataclass
class Result:
    """本地算出来的每人份营养。"""

    calories: float = 0.0
    protein_gram: float = 0.0
    fat_gram: float = 0.0
    carb_gram: float = 0.0
    fiber_gram: float = 0.0
    sodium_mg: float = 0.0
    coverage: float = 0.0
    resolved_items: int = 0
    total_items: int = 0
    unresolved: List[str] = field(default_factory=list)

    def trustable(self) -> bool:
        """覆盖率够高才敢用：否则不如老实标成"AI 估算"。"""
        return self.resolved_items > 0 and self.coverage >= MIN_COVERAGE

    def to_dict(self) -> dict:
        return {
            "trustable": self.trustable(),
            "calories": self.calories,
            "proteinGram": self.protein_gram,
            "fatGram": self.fat_gram,
            "carbGram": self.carb_gram,
            "fiberGram": self.fiber_gram,
            "sodiumMg": self.sodium_mg,
            "coverage": self.coverage,
            "resolvedItems": self.resolved_items,
            "totalItems": self.total_items,
            "unresolved": self.unresolved,
        }


EMPTY = Result()


def _nz(value: Optional[float]) -> float:
    return 0.0 if value is None else float(value)


def calculate(items: Optional[List[Item]], servings: int,
              directory: Directory) -> Result:
    """计算每人份营养。

    @param items    这道菜用到的食材与克数（调料不必传：没有用量算不了）
    @param servings 人数（至少按 1 算）
    """
    if not items:
        return EMPTY
    people = max(1, servings)

    valid: List[Item] = []
    for item in items:
        if item is None or item.name is None or not item.name.strip():
            continue
        if item.grams is None or item.grams <= 0:
            continue
        valid.append(item)
    if not valid:
        return EMPTY

    kcal = protein = fat = carb = fiber = sodium = 0.0
    total_gram = 0.0
    resolved_gram = 0.0
    resolved = 0
    unresolved: List[str] = []

    for item in valid:
        gram = float(item.grams)
        total_gram += gram
        # 归一化走目录（正式名 + 别名），与召回用的是同一套口径 ——
        # 不然会出现"召回认得西红柿、营养算不出来"这种自相矛盾。
        # 目录报错按"没对上"处理：绝不用残缺数据凑一个数出来
        try:
            ingredient_id = directory.resolve_id(item.name.strip())
        except Exception:
            ingredient_id = None
        entry = directory.entry_of(ingredient_id)
        if entry is None:
            unresolved.append(item.name.strip())
            continue
        resolved += 1
        resolved_gram += gram
        # 每 100 克 → 实际克数 → 每人份
        factor = gram / 100.0 / people
        kcal += _nz(entry.calorie) * factor
        protein += _nz(entry.protein) * factor
        fat += _nz(entry.fat) * factor
        carb += _nz(entry.carb) * factor
        fiber += _nz(entry.fiber) * factor
        sodium += _nz(entry.sodium) * factor

    coverage = resolved_gram / total_gram if total_gram > 0 else 0.0
    result = Result(kcal, protein, fat, carb, fiber, sodium, coverage,
                    resolved, len(valid), unresolved)
    if unresolved:
        logger.info("本地营养：%d/%d 样食材未对上主数据，覆盖率 %.0f%%，未对上：%s",
                    len(unresolved), len(valid), coverage * 100, "、".join(unresolved))
    return result


def local_items(draft: DishDraft) -> List[Item]:
    """把草稿里的食材整理成"名字 + 克数"。

    不含调料：草稿里的调料只有名字没有用量，算不了。这是本地计算的口径边界。
    """
    items: List[Item] = []
    for item in draft.ingredients or []:
        if item is None or item.name is None or not item.name.strip():
            continue
        items.append(Item(item.name.strip(),
                          float(item.amount_gram) if item.amount_gram is not None else None))
    return items


def estimate_calories_per_serving(draft: DishDraft, servings: int,
                                  directory: Directory) -> Optional[float]:
    """本地估算每人份热量（AI 估算的对照值）。

    @return None 表示有食材不在主数据里（或转正食材营养还是 0），估算不可信
    """
    items = draft.ingredients
    if not items:
        return None
    names = [i.name for i in items if i.name is not None and i.name.strip()]
    if not names:
        return None

    total = 0.0
    for item in items:
        if item is None or item.name is None or item.amount_gram is None:
            continue
        # 按正式名精确查找（不过别名）—— 与 Java 查库 by name 的口径一致
        entry = directory.entry_by_name(item.name.strip())
        # calorie <= 0 也要放弃：自动收录的新食材营养字段留的是 0，
        # 那表示"还不知道"而不是"零热量"—— 拿它参与计算会把整道菜的热量压低，
        # 营养校验必然报"偏差过大"，冤枉正常结果
        if entry is None or entry.calorie is None or entry.calorie <= 0:
            # 只要有一味食材查不到，整份估算就不可信，直接放弃
            return None
        gram = float(item.amount_gram)
        total += entry.calorie * gram / 100.0

    people = max(1, servings)
    return total / people


def verify_nutrition(draft: DishDraft, servings: int, tolerance: float,
                     directory: Directory) -> bool:
    """用「食材主数据 × 用量」本地算一份，与 AI 的值对比。

    @return true 表示偏差在容差内；本地根本没数据可算时返回 false，表示"没法确认"
    """
    try:
        nutrition = draft.nutrition_per_serving
        if nutrition is None or nutrition.calories is None or nutrition.calories <= 0:
            logger.info("营养校验跳过：AI 没有给出有效的每人份热量")
            return False
        local_per_serving = estimate_calories_per_serving(draft, servings, directory)
        if local_per_serving is None or local_per_serving <= 0:
            # 有食材不在主数据里（用户自由输入的那些），算不出来 ——
            # 不是错，只是没法确认
            logger.info("营养校验跳过：有食材不在主数据里，无法本地估算")
            return False
        diff = abs(nutrition.calories - local_per_serving) / local_per_serving
        passed = diff <= tolerance
        logger.info("营养校验：AI %d kcal / 本地 %.0f kcal，偏差 %.0f%%（容差 %.0f%%），%s",
                    nutrition.calories, local_per_serving, diff * 100, tolerance * 100,
                    "通过" if passed else "可疑")
        return passed
    except Exception:
        # 校验执行失败不影响生成结果本身
        logger.warning("营养校验执行异常，按未通过处理", exc_info=True)
        return False


def describe(result: Optional[Result]) -> str:
    """调试用：把结果说成一行。"""
    if result is None:
        return "无"
    unresolved = ("，未对上：" + "、".join(result.unresolved)) if result.unresolved else ""
    return (f"每人份 {result.calories:.0f} kcal"
            f"（覆盖率 {result.coverage * 100:.0f}%，"
            f"对上 {result.resolved_items}/{result.total_items} 样{unresolved}）")
