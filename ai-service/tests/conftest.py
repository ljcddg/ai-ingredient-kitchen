"""测试基建。

两份东西：
1. 快照目录 entries —— 与 Java 单测里 Mockito 桩的主数据等价
   （番茄101/鸡蛋102/葱103/牛肉104/奶酪105/松露106/香菇107/盐108/食用油109/料酒110），
   直接用生产同款 Directory（快照构建），顺把目录本身也测了；
2. valid_draft / valid_request —— 与 Java 侧 DishDraftValidatorTest.validDraft() 逐字段一致。

Java 单测里"目录没加载"（toleratesEmptyDirectory）那种桩状态用 BrokenDirectory 模仿。
"""
from __future__ import annotations

import pytest

from ai_service.directory import Directory
from ai_service.schemas import (Constraints, DishDraft, DirectoryEntry,
                                GenerateRequest, IngredientRef)

TOMATO, EGG, SCALLION, BEEF = 101, 102, 103, 104


def _entry(entry_id, name, category, alias=None, calorie=100.0):
    return DirectoryEntry(id=entry_id, name=name, alias=alias, category=category,
                          calorie=calorie, protein=calorie / 10, fat=calorie / 20,
                          carb=calorie / 5, fiber=1, sodium=calorie)


SNAPSHOT_ENTRIES = [
    _entry(TOMATO, "番茄", "蔬菜", alias="西红柿", calorie=20),
    _entry(EGG, "鸡蛋", "蛋奶", calorie=144),
    _entry(SCALLION, "葱", "蔬菜"),
    _entry(BEEF, "牛肉", "肉类"),
    _entry(105, "奶酪", "乳品"),
    _entry(106, "松露", "其他"),
    _entry(107, "香菇", "其他"),
    _entry(108, "盐", "调料"),
    _entry(109, "食用油", "调料"),
    _entry(110, "料酒", "调料"),
]


class BrokenDirectory(Directory):
    """模仿 Java 单测里"目录没加载"的桩：按 ID 都查不到，但检查要跑完。"""

    def __init__(self):
        super().__init__([])

    def resolve_id(self, raw_name):
        return None

    def name_of(self, ingredient_id):
        return None

    def category_of(self, ingredient_id):
        return None

    def canonical_names(self):
        return set()


@pytest.fixture
def directory():
    return Directory(SNAPSHOT_ENTRIES)


@pytest.fixture
def broken_directory():
    return BrokenDirectory()


def valid_draft_dict():
    """一份完全合规的草稿，各用例在它基础上改一个字段来制造问题。"""
    return {
        "name": "番茄炒蛋",
        "summary": "酸甜多汁的番茄裹着滑嫩蛋块，家常下饭，几分钟就能上桌。",
        "prepTimeMin": 6,
        "cookTimeMin": 8,
        "difficulty": 1,
        "spicyLevel": 0,
        "steps": [
            "番茄去蒂切成滚刀块，鸡蛋打入碗中加少许盐打散备用。",
            "热锅倒油，中火烧至六成热，倒入蛋液快速划散后盛出。",
            "锅内留底油，放入葱白爆香，倒入番茄块大火翻炒出汁。",
            "倒回炒好的鸡蛋，加盐翻匀，撒上葱花即可出锅。",
        ],
        "ingredients": [
            {"name": "番茄", "amountGram": 300},
            {"name": "鸡蛋", "amountGram": 120},
            {"name": "葱", "amountGram": 10},
        ],
        "seasonings": ["盐", "食用油"],
        "equipment": ["炒锅", "锅铲"],
        "dietTags": ["家常", "下饭"],
        "substitutes": ["没有葱可以用洋葱代替。"],
        "chefTips": [
            "鸡蛋要热锅热油下锅才蓬松，油温不够会出水变老。",
            "番茄先烫一下去皮再炒，更容易出沙，汤汁更浓。",
        ],
        "nutritionPerServing": {
            "calories": 210, "proteinGram": 13, "fatGram": 14,
            "carbGram": 9, "fiberGram": 2, "sodiumMg": 520,
        },
    }


def valid_draft():
    return DishDraft.from_dict(valid_draft_dict())


def valid_request(servings=2, constraints=None, refs=None):
    if refs is None:
        refs = [IngredientRef(TOMATO, "番茄"), IngredientRef(EGG, "鸡蛋"),
                IngredientRef(SCALLION, "葱")]
    return GenerateRequest(ingredients=refs, tastes=["家常"], servings=servings,
                           constraints=constraints or Constraints.none())


def has(problems, fragment):
    return any(fragment in p for p in problems)
