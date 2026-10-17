"""【常见用量】段与分位索引的测试。

这段的价值全在"数字对不对、和校验器一致不一致"：
提示词说"不超过 400g"、校验器却按 450g 拦，模型会被教一次又被打一次，
重试三轮也改不对 —— 所以每条用例都把**换算后的克数**断言出来。
"""
from ai_service.amounts import AmountIndex, serving_bucket
from ai_service.prompt import MAX_AMOUNT_HINTS, build_user_prompt, render_amount_hints
from ai_service.schemas import (AmountStat, Constraints, GenerateRequest,
                                IngredientRef, QuantityStat)

from .conftest import SNAPSHOT_ENTRIES, TOMATO, EGG, SCALLION, directory  # noqa: F401


def amount_stat(ingredient_id, bucket, p50, p90):
    return AmountStat(ingredient_id=ingredient_id, bucket=bucket, p50=p50, p90=p90,
                      sample_count=10)


def request_of(names_ids, servings=2, amount_stats=None, quantity_stats=None):
    return GenerateRequest(
        ingredients=[IngredientRef(i, n) for n, i in names_ids],
        servings=servings,
        constraints=Constraints.none(),
        amount_stats=amount_stats or [],
        quantity_stats=quantity_stats or [])


def test_hint_scales_by_servings(directory):
    """p50/p90 是每人份，提示词要给的是这道菜的总量 —— 2 人份 ×2。"""
    req = request_of([("番茄", TOMATO)], servings=2,
                     amount_stats=[amount_stat(TOMATO, "1-2", 150, 200)])
    block = render_amount_hints(req, directory)

    assert "2 人份" in block
    assert "番茄：约 300g（一般不超过 400g）" in block


def test_hint_prefers_ingredient_over_category(directory):
    req = request_of([("番茄", TOMATO)], servings=1,
                     amount_stats=[amount_stat(TOMATO, "1-2", 150, 200)],
                     quantity_stats=[QuantityStat("蔬菜", "1-2", 200, 250, 100)])
    block = render_amount_hints(req, directory)
    assert "约 150g（一般不超过 200g）" in block


def test_hint_falls_back_to_category(directory):
    """鸡蛋没有单品样本时，用蛋奶类的分位（150g/人 → 2 人份 300g）。"""
    req = request_of([("鸡蛋", EGG)], servings=2,
                     quantity_stats=[QuantityStat("蛋奶", "1-2", 100, 150, 20)])
    block = render_amount_hints(req, directory)
    assert "鸡蛋：约 200g（一般不超过 300g）" in block


def test_hint_applies_floor(directory):
    """点缀食材（葱 p90=8g）的"不超过"要抬到地板 80g/人，否则等于劝用户别放葱。"""
    req = request_of([("葱", SCALLION)], servings=2,
                     amount_stats=[amount_stat(SCALLION, "1-2", 5, 8)])
    block = render_amount_hints(req, directory)
    assert "葱：约 10g（一般不超过 160g）" in block


def test_no_stats_means_no_block(directory):
    """统计没注入时这段完全不出现 —— 两臂（有/无统计）的差别才干净。"""
    req = request_of([("番茄", TOMATO)])
    assert render_amount_hints(req, directory) == ""
    prompt = build_user_prompt(req, directory)
    assert "【常见用量】" not in prompt


def test_hint_caps_ingredient_count(directory):
    """食材再多也只列前 N 项：段太长会挤占输出预算。"""
    entries = [("番茄", TOMATO), ("鸡蛋", EGG), ("葱", SCALLION)]
    stats = [amount_stat(TOMATO, "1-2", 150, 200),
             amount_stat(EGG, "1-2", 60, 75),
             amount_stat(SCALLION, "1-2", 5, 8)]
    req = request_of(entries, amount_stats=stats)
    block = render_amount_hints(req, directory)

    assert block.count("\n- ") == min(MAX_AMOUNT_HINTS, len(stats))


def test_amount_index_reports_source(directory):
    """同一份索引要能说清"这条数是从哪一级来的"，日志与报错都靠它。"""
    index = AmountIndex([amount_stat(TOMATO, "1-2", 150, 200)],
                        [QuantityStat("蔬菜", "1-2", 200, 250, 100)])

    hit = index.lookup(TOMATO, "蔬菜", "1-2")
    assert (hit.source, hit.p50, hit.p90) == ("ingredient", 150, 200)

    miss_single = index.lookup(EGG, "蔬菜", "1-2")
    assert (miss_single.source, miss_single.p90) == ("category", 250)

    nothing = index.lookup(EGG, "肉类", "5+")
    assert nothing.source == "fixed" and nothing.has_stat() is False


def test_bucket_is_shared_with_stats_script():
    """档位边界：与 sql/build-stats.py 的 bucket_of 必须逐字一致。"""
    assert [serving_bucket(n) for n in (1, 2, 3, 4, 5, 8)] == \
           ["1-2", "1-2", "3-4", "3-4", "5+", "5+"]
