"""RAGAS 另三项指标的测试（answer_relevance / ctx_precision / ctx_recall）。

这三项都是**代理指标**，所以测试的重点不是"分数好看"，而是
"每种失效能不能被区分出来" —— 参考注入错了、用户食材没被覆盖、
成品答非所问，三者必须给出不同的低分，否则做 A/B 时归因不到环节。
"""
from ai_service.grounding import (answer_relevance, assess, retrieval_metrics)
from ai_service.schemas import (Constraints, DishDraft, GenerateRequest,
                                IngredientRef, Reference, ReferenceAmount)

from .conftest import (SNAPSHOT_ENTRIES, TOMATO, EGG, SCALLION,  # noqa: F401
                       directory, valid_draft_dict)

BEEF = 104


def draft_of(**overrides):
    raw = valid_draft_dict()
    raw.update(overrides)
    return DishDraft.from_dict(raw)


def request_of(ingredients=None, tastes=None, constraints=None, servings=2):
    return GenerateRequest(
        ingredients=ingredients if ingredients is not None
        else [IngredientRef(TOMATO, "番茄"), IngredientRef(EGG, "鸡蛋")],
        tastes=tastes or [],
        servings=servings,
        constraints=constraints or Constraints.none())


def reference(mains, missing=None, amounts=None):
    return Reference(dish_id=1, name="参考菜", mains=mains, missing=missing or [],
                     amounts=[ReferenceAmount(n, g) for n, g in (amounts or [])])


# ------------------------------------------------------------------ answer_relevance


def test_relevance_full_score_when_everything_matches(directory):
    draft = draft_of(spicyLevel=1)
    req = request_of(tastes=["微辣"], constraints=Constraints(max_cook_time_min=20))
    # 番茄炒蛋的步骤里两样主料都出现了，spicyLevel=1 命中微辣，5+8=13 ≤ 20
    assert answer_relevance(draft, req, directory) == 1.0


def test_relevance_drops_when_ingredient_missing_from_text(directory):
    """配料表里有鸡蛋、但菜名/步骤里完全不提 —— 食材维只拿一半分。

    (0.5×0.5 + 1.0×0.3 + 1.0×0.2) / 1.0 = 0.75
    """
    draft = draft_of(
        name="番茄汤",
        summary="清爽的番茄汤，酸甜开胃，饭前先来一小碗。",
        steps=["番茄切块备用，锅中加水烧开。",
               "下番茄块小火煮十分钟，煮到番茄软烂出沙。",
               "加盐调味，撒上葱花，关火盛出即可。",
               "装碗后稍晾两分钟再喝，避免烫口。"],
        ingredients=[{"name": "番茄", "amountGram": 300}],
        spicyLevel=1,
    )
    req = request_of(tastes=["微辣"], constraints=Constraints(max_cook_time_min=20))
    assert answer_relevance(draft, req, directory) == 0.75


def test_relevance_taste_ignored_when_not_requested(directory):
    """用户没点口味，就不该因为口味扣分 —— 分母只算"这次真的提了"的维度。"""
    draft = draft_of(spicyLevel=0)
    req = request_of(tastes=[])
    assert answer_relevance(draft, req, directory) == 1.0


def test_relevance_no_signal_taste_is_skipped(directory):
    """「家常」这种标签无法判别，算进去只会把指标稀释成常数。"""
    draft = draft_of()
    assert answer_relevance(draft, request_of(tastes=["家常"]), directory) == 1.0


def test_relevance_penalizes_broken_constraint(directory):
    """超时是硬条件：耗时维 0 分。

    这次没提口味，所以分母只有食材（0.5）与约束（0.2）：
    0.5 / (0.5+0.2) ≈ 0.714 —— 这正是"没提的维度不进分母"的效果。
    """
    draft = draft_of(prepTimeMin=30, cookTimeMin=60)
    req = request_of(constraints=Constraints(max_cook_time_min=20))
    assert abs(answer_relevance(draft, req, directory) - 0.5 / 0.7) < 1e-9


def test_relevance_none_without_anything_to_judge(directory):
    """没有食材、没有口味、没有约束 → 判不了，不能记 0 分。"""
    req = GenerateRequest(ingredients=[], tastes=[], constraints=Constraints.none())
    assert answer_relevance(draft_of(), req, directory) is None


# ------------------------------------------------------------------ 检索侧两项


def test_retrieval_metrics_precision_and_recall(directory):
    """参考主料 3 样、用户缺 1 样 → 精确率 2/3；用户 2 样食材全被覆盖 → 召回 1.0。"""
    refs = [reference(["番茄", "鸡蛋", "葱"], missing=["葱"],
                      amounts=[("番茄", 200)])]
    req = request_of([IngredientRef(TOMATO, "番茄"), IngredientRef(EGG, "鸡蛋")])
    precision, recall = retrieval_metrics(req, refs, directory)
    assert abs(precision - 2 / 3) < 1e-9
    assert recall == 1.0


def test_retrieval_recall_drops_when_user_ingredient_uncovered(directory):
    """用户手里有牛肉，参考里全没提到 → 召回 1/3，提示该走 GENERATE_FRESH。"""
    refs = [reference(["番茄", "鸡蛋", "葱"])]
    req = request_of([IngredientRef(TOMATO, "番茄"), IngredientRef(EGG, "鸡蛋"),
                      IngredientRef(BEEF, "牛肉")])
    _, recall = retrieval_metrics(req, refs, directory)
    assert abs(recall - 2 / 3) < 1e-9


def test_retrieval_metrics_none_without_references(directory):
    precision, recall = retrieval_metrics(request_of(), [], directory)
    assert precision is None and recall is None


def test_assess_fills_all_four_metrics(directory):
    """一次评估要同时给出四项，且用量判定仍然是老口径（0.5~2 倍）。"""
    refs = [reference(["番茄", "鸡蛋", "葱"],
                      amounts=[("番茄", 200), ("鸡蛋", 120), ("葱", 10)])]
    req = request_of(tastes=["家常"],
                     constraints=Constraints(max_cook_time_min=30))
    a = assess(draft_of(), refs, directory, req)

    assert a.judgeable() is True
    assert a.faithfulness() == 1.0
    assert a.answer_relevance == 1.0
    assert a.ctx_precision == 1.0
    assert a.ctx_recall == 1.0
    # 落库用的 dict 里四项都在，null 只能出现在"判不了"的时候
    payload = a.to_dict()
    assert payload["judgeable"] is True
    assert payload["answerRelevance"] == 1.0


def test_assess_without_references_still_reports_relevance(directory):
    """无参考（GENERATE_FRESH）时也要有 answer_relevance —— 那是对照组的数据。"""
    req = request_of(tastes=["家常"])
    a = assess(draft_of(), [], directory, req)
    assert a.judgeable() is False
    assert a.answer_relevance == 1.0
    assert a.ctx_precision is None and a.ctx_recall is None
