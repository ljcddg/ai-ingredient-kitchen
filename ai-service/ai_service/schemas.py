"""请求 / 响应的结构化定义。

字段名与 dish-service 的 DTO 一字不差（camelCase）——Java 侧用同一套字段名
做 Jackson 映射，改名前先改两边。草稿字段还必须与
prompts/dish-generate-system.txt 里定义的 JSON 一致，否则反序列化会静默变成 null。

ID 一律按字符串传输（项目铁律：Long 序列化成字符串，防 JS 精度丢失），
Python 侧解析时再转回 int。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, List, Optional, Set

_ALIAS_SPLIT = re.compile(r"[,，]")


def as_int(value: Any) -> Optional[int]:
    """宽松整数转换：模型可能输出 300.0，Java Jackson 也会收成 300。"""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value) if value.is_integer() else None
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return None
    return None


def as_float(value: Any) -> Optional[float]:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value.strip())
        except ValueError:
            return None
    return None


def as_str(value: Any) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return str(value)


def as_str_list(value: Any) -> Optional[List[str]]:
    if value is None:
        return None
    if not isinstance(value, list):
        return None
    return [v if isinstance(v, str) else str(v) for v in value]


def _clean_names(raw: Any) -> List[str]:
    """去 null/空白、trim、保序去重 —— 与 GenerateConstraints.cleanNames 一致。"""
    if not isinstance(raw, list):
        return []
    seen: List[str] = []
    for item in raw:
        if item is None:
            continue
        text = str(item).strip()
        if text and text not in seen:
            seen.append(text)
    return seen


def _id_set(raw: Any) -> Set[int]:
    if not isinstance(raw, list):
        return set()
    result: Set[int] = set()
    for item in raw:
        parsed = as_int(item)
        if parsed is not None:
            result.add(parsed)
    return result


# ------------------------------------------------------------------ 草稿（模型输出）


@dataclass
class DraftIngredient:
    name: Optional[str] = None
    amount_gram: Optional[int] = None

    @classmethod
    def from_dict(cls, raw: dict) -> "DraftIngredient":
        return cls(name=as_str(raw.get("name")),
                   amount_gram=as_int(raw.get("amountGram")))

    def to_dict(self) -> dict:
        return {"name": self.name, "amountGram": self.amount_gram}


@dataclass
class NutritionDraft:
    calories: Optional[int] = None
    protein_gram: Optional[int] = None
    fat_gram: Optional[int] = None
    carb_gram: Optional[int] = None
    fiber_gram: Optional[int] = None
    sodium_mg: Optional[int] = None

    @classmethod
    def from_dict(cls, raw: dict) -> "NutritionDraft":
        return cls(
            calories=as_int(raw.get("calories")),
            protein_gram=as_int(raw.get("proteinGram")),
            fat_gram=as_int(raw.get("fatGram")),
            carb_gram=as_int(raw.get("carbGram")),
            fiber_gram=as_int(raw.get("fiberGram")),
            sodium_mg=as_int(raw.get("sodiumMg")),
        )

    def to_dict(self) -> dict:
        return {
            "calories": self.calories,
            "proteinGram": self.protein_gram,
            "fatGram": self.fat_gram,
            "carbGram": self.carb_gram,
            "fiberGram": self.fiber_gram,
            "sodiumMg": self.sodium_mg,
        }


@dataclass
class DishDraft:
    """AI 生成结果的结构映射 —— 字段名必须与系统提示词里定义的 JSON 一字不差。

    用 Optional 而不是默认值：这样"字段缺失"能被校验器识别出来，
    给出"缺少 difficulty"这种明确的错误，而不是拿到一个 0 分不清真假。
    """

    name: Optional[str] = None
    summary: Optional[str] = None
    prep_time_min: Optional[int] = None
    cook_time_min: Optional[int] = None
    difficulty: Optional[int] = None
    spicy_level: Optional[int] = None
    steps: Optional[List[str]] = None
    ingredients: Optional[List[DraftIngredient]] = None
    seasonings: Optional[List[str]] = None
    equipment: Optional[List[str]] = None
    nutrition_per_serving: Optional[NutritionDraft] = None
    diet_tags: Optional[List[str]] = None
    substitutes: Optional[List[str]] = None
    chef_tips: Optional[List[str]] = None

    @classmethod
    def from_dict(cls, raw: dict) -> "DishDraft":
        ingredients_raw = raw.get("ingredients")
        ingredients = None
        if isinstance(ingredients_raw, list):
            ingredients = [DraftIngredient.from_dict(i if isinstance(i, dict) else {})
                           for i in ingredients_raw]
        nutrition_raw = raw.get("nutritionPerServing")
        nutrition = NutritionDraft.from_dict(nutrition_raw) if isinstance(nutrition_raw, dict) else None
        return cls(
            name=as_str(raw.get("name")),
            summary=as_str(raw.get("summary")),
            prep_time_min=as_int(raw.get("prepTimeMin")),
            cook_time_min=as_int(raw.get("cookTimeMin")),
            difficulty=as_int(raw.get("difficulty")),
            spicy_level=as_int(raw.get("spicyLevel")),
            steps=as_str_list(raw.get("steps")),
            ingredients=ingredients,
            seasonings=as_str_list(raw.get("seasonings")),
            equipment=as_str_list(raw.get("equipment")),
            nutrition_per_serving=nutrition,
            diet_tags=as_str_list(raw.get("dietTags")),
            substitutes=as_str_list(raw.get("substitutes")),
            chef_tips=as_str_list(raw.get("chefTips")),
        )

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "summary": self.summary,
            "prepTimeMin": self.prep_time_min,
            "cookTimeMin": self.cook_time_min,
            "difficulty": self.difficulty,
            "spicyLevel": self.spicy_level,
            "steps": self.steps,
            "ingredients": [i.to_dict() for i in self.ingredients]
                          if self.ingredients is not None else None,
            "seasonings": self.seasonings,
            "equipment": self.equipment,
            "nutritionPerServing": self.nutrition_per_serving.to_dict()
                                   if self.nutrition_per_serving else None,
            "dietTags": self.diet_tags,
            "substitutes": self.substitutes,
            "chefTips": self.chef_tips,
        }


# ------------------------------------------------------------------ 生成输入


@dataclass
class IngredientRef:
    """用户选用的一个食材。id 允许为 None：自由输入且尚未归一的食材没有主数据 ID。"""

    id: Optional[int] = None
    name: Optional[str] = None

    @classmethod
    def from_dict(cls, raw: dict) -> "IngredientRef":
        return cls(id=as_int(raw.get("id")), name=as_str(raw.get("name")))


@dataclass
class Constraints:
    """生成约束：必用食材、禁用食材、最长耗时、可用设备。

    字段用 Optional/空集合表达"没提这个约束"，而不是用空字符串或 0：
    「不限时长」和「最多 0 分钟」是两件完全不同的事。
    """

    must_use_ids: Set[int] = field(default_factory=set)
    must_use_names: List[str] = field(default_factory=list)
    exclude_ids: Set[int] = field(default_factory=set)
    exclude_names: List[str] = field(default_factory=list)
    max_cook_time_min: Optional[int] = None
    equipment_names: List[str] = field(default_factory=list)

    @classmethod
    def from_dict(cls, raw: Optional[dict]) -> "Constraints":
        if not isinstance(raw, dict):
            return cls()
        return cls(
            must_use_ids=_id_set(raw.get("mustUseIngredientIds")),
            must_use_names=_clean_names(raw.get("mustUseIngredients")),
            exclude_ids=_id_set(raw.get("excludeIngredientIds")),
            exclude_names=_clean_names(raw.get("excludeIngredients")),
            max_cook_time_min=as_int(raw.get("maxCookTimeMin")),
            equipment_names=_clean_names(raw.get("availableEquipment")),
        )

    @classmethod
    def none(cls) -> "Constraints":
        return cls()

    def no_constraint(self) -> bool:
        """所有约束项都为空 —— 决定要不要在提示词里加"用户约束"那一段。"""
        return (not self.must_use_ids and not self.must_use_names
                and not self.exclude_ids and not self.exclude_names
                and self.max_cook_time_min is None and not self.equipment_names)


@dataclass
class DirectoryEntry:
    """食材主数据快照的一项（dish-service 的 IngredientDirectory 导出）。

    营养字段是每 100g 的值。ai-service 不连业务库，主数据由每请求注入。
    """

    id: int
    name: Optional[str] = None
    alias: Optional[str] = None
    category: Optional[str] = None
    calorie: Optional[float] = None
    protein: Optional[float] = None
    fat: Optional[float] = None
    carb: Optional[float] = None
    fiber: Optional[float] = None
    sodium: Optional[float] = None

    @classmethod
    def from_dict(cls, raw: dict) -> Optional["DirectoryEntry"]:
        entry_id = as_int(raw.get("id"))
        if entry_id is None:
            return None
        return cls(
            id=entry_id,
            name=as_str(raw.get("name")),
            alias=as_str(raw.get("alias")),
            category=as_str(raw.get("category")),
            calorie=as_float(raw.get("calorie")),
            protein=as_float(raw.get("protein")),
            fat=as_float(raw.get("fat")),
            carb=as_float(raw.get("carb")),
            fiber=as_float(raw.get("fiber")),
            sodium=as_float(raw.get("sodium")),
        )


@dataclass
class Reference:
    """一条参考菜谱的可用片段（dish-service 检索好后传过来，Python 只负责渲染）。"""

    dish_id: Optional[int] = None
    name: Optional[str] = None
    mains: List[str] = field(default_factory=list)
    missing: List[str] = field(default_factory=list)
    taste_tags: List[str] = field(default_factory=list)
    cook_time_min: Optional[int] = None
    step_hints: List[str] = field(default_factory=list)

    @classmethod
    def from_dict(cls, raw: dict) -> "Reference":
        return cls(
            dish_id=as_int(raw.get("dishId")),
            name=as_str(raw.get("name")),
            mains=as_str_list(raw.get("mains")) or [],
            missing=as_str_list(raw.get("missing")) or [],
            taste_tags=as_str_list(raw.get("tasteTags")) or [],
            cook_time_min=as_int(raw.get("cookTimeMin")),
            step_hints=as_str_list(raw.get("stepHints")) or [],
        )


@dataclass
class GenerateOptions:
    model: str = "gemma4:e2b"
    temperature: float = 0.7
    max_attempts: int = 2
    timeout_seconds: int = 600


def _parse_options(raw: Any, defaults: GenerateOptions) -> GenerateOptions:
    """请求里的 options 覆盖配置默认值；缺项/解析失败用默认值。"""
    if not isinstance(raw, dict):
        return GenerateOptions(model=defaults.model, temperature=defaults.temperature,
                               max_attempts=defaults.max_attempts,
                               timeout_seconds=defaults.timeout_seconds)
    model = as_str(raw.get("model")) or defaults.model
    temperature = as_float(raw.get("temperature"))
    max_attempts = as_int(raw.get("maxAttempts"))
    timeout_seconds = as_int(raw.get("timeoutSeconds"))
    return GenerateOptions(
        model=model,
        temperature=temperature if temperature is not None else defaults.temperature,
        max_attempts=max_attempts if max_attempts is not None and max_attempts > 0
        else defaults.max_attempts,
        timeout_seconds=timeout_seconds if timeout_seconds is not None and timeout_seconds > 0
        else defaults.timeout_seconds,
    )


@dataclass
class GenerateRequest:
    request_id: Optional[str] = None
    ingredients: List[IngredientRef] = field(default_factory=list)
    tastes: List[str] = field(default_factory=list)
    servings: int = 2
    constraints: Constraints = field(default_factory=Constraints)
    directory: List[DirectoryEntry] = field(default_factory=list)
    references: List[Reference] = field(default_factory=list)
    options: GenerateOptions = field(default_factory=GenerateOptions)

    @classmethod
    def from_dict(cls, body: dict, defaults: GenerateOptions) -> "GenerateRequest":
        if not isinstance(body, dict):
            raise ValueError("请求体必须是 JSON 对象")
        ingredients_raw = body.get("ingredients")
        ingredients = [IngredientRef.from_dict(i if isinstance(i, dict) else {})
                       for i in ingredients_raw] if isinstance(ingredients_raw, list) else []
        directory_raw = body.get("directory")
        directory = [e for e in (DirectoryEntry.from_dict(i if isinstance(i, dict) else {})
                                 for i in directory_raw) if e is not None] \
            if isinstance(directory_raw, list) else []
        references_raw = body.get("references")
        references = [Reference.from_dict(i if isinstance(i, dict) else {})
                      for i in references_raw] if isinstance(references_raw, list) else []
        options_raw = body.get("options")
        options = _parse_options(options_raw, defaults)
        servings = as_int(body.get("servings"))
        return cls(
            request_id=as_str(body.get("requestId")),
            ingredients=ingredients,
            tastes=as_str_list(body.get("tastes")) or [],
            servings=servings if servings is not None and servings > 0 else 2,
            constraints=Constraints.from_dict(body.get("constraints")),
            directory=directory,
            references=references,
            options=options,
        )
