"""用量上限的三级依据测试（单品分位 → 类别分位 → 固定档）。

这块的每条用例都在回答同一个问题：**"该放多少"到底是从哪儿来的**。
只断言"过了/没过"是不够的 —— 固定上限也能让某个用例通过，
但那样测出来的就不是统计，是巧合。所以每条都断言到具体克数与报错里的依据。
"""
from ai_service.schemas import AmountStat, GenerateRequest, QuantityStat
from ai_service.validator import (DraftValidator, STAT_FLOOR_PER_PERSON,
                                  serving_bucket)

from .conftest import (SNAPSHOT_ENTRIES, TOMATO, EGG, SCALLION,  # noqa: F401
                       directory, has, valid_draft_dict)
from ai_service.schemas import DishDraft


def amount_stat(ingredient_id, bucket, p50, p90):
    return AmountStat(ingredient_id=ingredient_id, bucket=bucket,
                      p50=p50, p90=p90, sample_count=5)


def quantity_stat(category, bucket, p50, p90):
    return QuantityStat(category=category, bucket=bucket, p50=p50, p90=p90,
                        sample_count=20)


def draft_with(ingredients):
    raw = valid_draft_dict()
    raw["ingredients"] = ingredients
    return DishDraft.from_dict(raw)


def request_of(servings=2):
    return GenerateRequest(servings=servings)


def problems_for(draft, validator, servings=2):
    return validator.validate_detailed(draft, request_of(servings)).problems


def test_bucket_matches_stats_script():
    """人数档必须与 sql/build-stats.py 的 bucket_of 一致，否则统计永远查不到。"""
    assert serving_bucket(1) == "1-2" and serving_bucket(2) == "1-2"
    assert serving_bucket(3) == "3-4" and serving_bucket(4) == "3-4"
    assert serving_bucket(5) == "5+" and serving_bucket(10) == "5+"


def test_single_ingredient_stat_is_stricter_than_category(directory):
    """番茄在库里每份约 150g（p90）：2 人份 500g 就该被拦。

    这一条正是固定上限拦不住的：蔬菜档 300g/人 × 2 人 × 1.5 = 900g，
    500g 会直接放行 —— 而"两个人吃一斤番茄"是不合理的。
    """
    validator = DraftValidator(directory, [amount_stat(TOMATO, "1-2", 130, 150)])
    problems = problems_for(
        draft_with([{"name": "番茄", "amountGram": 500},
                    {"name": "鸡蛋", "amountGram": 120}]),
        validator)

    assert has(problems, "用量 500g")
    # 上限 = max(150, 地板80) × 2 人 × 1.5 = 450
    assert has(problems, "应不超过 450g")
    assert has(problems, "p90")  # 报错里必须给出依据，重试提示词才有用


def test_amount_within_stat_passes(directory):
    """450g 以内（上限本身）不能误杀。"""
    validator = DraftValidator(directory, [amount_stat(TOMATO, "1-2", 130, 150)])
    problems = problems_for(
        draft_with([{"name": "番茄", "amountGram": 450},
                    {"name": "鸡蛋", "amountGram": 120}]),
        validator)
    assert not has(problems, "番茄")


def test_category_stat_used_when_no_single_ingredient_sample(directory):
    """单品样本不足时退到类别级：蛋奶类每份 150g → 2 人份上限 450g。"""
    validator = DraftValidator(directory, [], [quantity_stat("蛋奶", "1-2", 100, 150)])
    problems = problems_for(
        draft_with([{"name": "番茄", "amountGram": 300},
                    {"name": "鸡蛋", "amountGram": 500}]),
        validator)
    assert has(problems, "用量 500g")
    assert has(problems, "蛋奶类食材每份约 150g")


def test_ingredient_stat_beats_category_stat(directory):
    """两级都命中时用单品级 —— 否则"番茄"会被"蔬菜"的平均量带偏。"""
    validator = DraftValidator(
        directory,
        [amount_stat(TOMATO, "1-2", 130, 150)],
        [quantity_stat("蔬菜", "1-2", 200, 250)])
    problems = problems_for(
        draft_with([{"name": "番茄", "amountGram": 500},
                    {"name": "鸡蛋", "amountGram": 120}]),
        validator)
    assert has(problems, "应不超过 450g")  # 单品级：150×2×1.5
    assert not has(problems, "蔬菜类")      # 不是类别级：250×2×1.5=750


def test_stat_floor_protects_garnish(directory):
    """点缀型食材（葱 p90=8g）有地板保护：100g 正常、260g 才算过量。

    没这个地板的话上限只有 24g，"香菜拌牛肉"这种菜会被判成用量幻觉，
    白跑一轮 12~26 秒的重试。
    """
    validator = DraftValidator(directory, [amount_stat(SCALLION, "1-2", 5, 8)])

    ok = problems_for(
        draft_with([{"name": "番茄", "amountGram": 300},
                    {"name": "葱", "amountGram": 100}]), validator)
    assert not has(ok, "葱")

    too_much = problems_for(
        draft_with([{"name": "番茄", "amountGram": 300},
                    {"name": "葱", "amountGram": 260}]), validator)
    assert has(too_much, "用量 260g")
    assert has(too_much, f"应不超过 {int(STAT_FLOOR_PER_PERSON * 2 * 1.5)}g")


def test_no_stats_falls_back_to_fixed_table(directory):
    """统计没注入（脚本没跑/表空）时行为与改造前完全一致：300×2×1.5=900。"""
    validator = DraftValidator(directory)  # 不传任何统计
    ok = problems_for(
        draft_with([{"name": "番茄", "amountGram": 800},
                    {"name": "鸡蛋", "amountGram": 120}]),
        validator)
    assert not has(ok, "番茄")

    too_much = problems_for(
        draft_with([{"name": "番茄", "amountGram": 950},
                    {"name": "鸡蛋", "amountGram": 120}]),
        validator)
    assert has(too_much, "该类食材每人份的合理上限是 300g")


def test_seasoning_ignores_stats(directory):
    """调料不随人数线性放大，统计表也排除了调料 —— 一律走固定档。

    盐 15g/人 × 2 人 × 1.5 = 45g。就算统计里给盐塞了条 p90=200 的脏数据，
    也不该把上限抬上去（那正是"料酒 300g"当初漏过去的原因）。
    """
    validator = DraftValidator(directory, [amount_stat(108, "1-2", 200, 200)])
    problems = problems_for(
        draft_with([{"name": "番茄", "amountGram": 300},
                    {"name": "盐", "amountGram": 60}]),
        validator)
    assert has(problems, "用量 60g")
    assert has(problems, "调料每人份约 15g")
