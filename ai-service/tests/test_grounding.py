"""依据校验（grounding）测试。

这块判定的价值全在"比例"上（论文要报 faithfulness），
所以每条用例都同时断言**档位**与**计数**，只断言一个都不够 ——
档位对了但计数错，比例还是错的。
"""
from ai_service.grounding import (DEVIATED, GROUNDED, UNGROUNDED, assess,
                                  LOW_CONFIDENCE_UNGROUNDED)
from ai_service.schemas import DishDraft, DraftIngredient, Reference, ReferenceAmount

from .conftest import SNAPSHOT_ENTRIES, directory  # noqa: F401  （fixture 需要导入）


def draft(*pairs):
    return DishDraft(name="测试菜", ingredients=[DraftIngredient(name=n, amount_gram=g)
                                                 for n, g in pairs])


def reference(name, amounts):
    return Reference(dish_id=1, name=name,
                     amounts=[ReferenceAmount(n, g) for n, g in amounts])


def test_no_references_is_unjudgeable():
    """没有参考时 faithfulness 判不了 —— 计数全 0、judgeable 为假，绝不能记成 0 分。

    注意 assess 本身不再返回 None：answer_relevance 不需要参考也能算，
    而它正是"注入参考 vs 没注入"对照实验里对照组的数据。
    """
    for refs in ([], None):
        a = assess(draft(("番茄", 200)), refs)
        assert a is not None
        assert a.judgeable() is False
        assert a.total() == 0 and a.items == []
        assert a.ctx_precision is None and a.ctx_recall is None


def test_reference_without_amounts_is_unjudgeable():
    """参考里一条用量都没有（老数据/检索没带用量）时同样判不了。

    这里最容易写错：不进判定循环的话，每样食材都会"找不到参照"被记成无据，
    faithfulness 直接变 0 —— 拿"参考没带用量"的锅否定生成。
    """
    a = assess(draft(("番茄", 200)), [Reference(dish_id=1, name="番茄炒蛋")])
    assert a.judgeable() is False
    assert a.ungrounded == 0 and a.items == []


def test_grounded_when_amount_close():
    a = assess(draft(("番茄", 200), ("鸡蛋", 100)),
               [reference("番茄炒蛋", [("番茄", 180), ("鸡蛋", 120)])])
    assert a.grounded == 2 and a.deviated == 0 and a.ungrounded == 0
    assert all(i.tier == GROUNDED for i in a.items)
    assert a.faithfulness() == 1.0


def test_deviated_when_amount_far_off():
    """料酒给到参考的 5 倍：有依据、但明显偏离。"""
    a = assess(draft(("番茄", 200), ("鸡蛋", 500)),
               [reference("番茄炒蛋", [("番茄", 200), ("鸡蛋", 100)])])
    assert a.grounded == 1 and a.deviated == 1
    egg = [i for i in a.items if i.name == "鸡蛋"][0]
    assert egg.tier == DEVIATED


def test_ungrounded_when_ingredient_not_in_reference():
    a = assess(draft(("番茄", 200), ("松露", 10)),
               [reference("番茄炒蛋", [("番茄", 200)])])
    assert a.ungrounded == 1
    assert [i for i in a.items if i.name == "松露"][0].tier == UNGROUNDED


def test_low_confidence_threshold():
    """无据比例超过 30% 才标低可信：2/5 不算，3/5 算。"""
    refs = [reference("参考", [("番茄", 200)])]

    two_of_five = assess(draft(("番茄", 200), ("牛肉", 100), ("奶酪", 50),
                               ("土豆", 100), ("洋葱", 50)), refs)
    assert two_of_five.ungrounded == 4  # 只有番茄在参考里
    assert two_of_five.low_confidence() is True

    ok = assess(draft(("番茄", 200), ("番茄", 200)), refs)
    assert ok.low_confidence() is False
    assert LOW_CONFIDENCE_UNGROUNDED == 0.3


def test_alias_is_normalized(directory):
    """模型写「西红柿」、参考里写「番茄」，必须算有据，不能算无据。"""
    a = assess(draft(("西红柿", 200)),
               [reference("番茄炒蛋", [("番茄", 200)])], directory)
    assert a.ungrounded == 0
    assert a.grounded == 1
