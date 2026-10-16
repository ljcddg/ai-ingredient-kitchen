"""营养本地计算测试 —— NutritionCalculatorTest 的 Python 移植（逐条对应）。

重点是两件事：
1. 算式要对：每 100 克的值 × 实际克数 ÷ 人数，一个环节错都会让数值离谱；
2. 算不出来时不能硬算：成分表对不齐（陌生食材）时必须判定为"不可信"，
   让上层退回 AI 估算并标注来源，而不是拿残缺数据凑一个看着像样的数。
"""
import pytest

from ai_service import nutrition
from ai_service.directory import Directory
from ai_service.schemas import DirectoryEntry, DishDraft

from .conftest import SNAPSHOT_ENTRIES


@pytest.fixture
def directory():
    # 只留 番茄/鸡蛋 两项营养已知的（香茅故意不建 —— 陌生食材）
    entries = [e for e in SNAPSHOT_ENTRIES if e.id in (101, 102)]
    return Directory(entries)


def item(name, grams):
    return nutrition.Item(name, grams)


class TestNutritionCalculator:
    def test_computes_per_serving(self, directory):
        # 番茄 300g → 60 kcal；鸡蛋 120g → 172.8 kcal；合计 232.8 / 2 人 = 116.4
        result = nutrition.calculate([item("番茄", 300), item("鸡蛋", 120)], 2, directory)

        assert result.calories == pytest.approx(116.4, abs=0.01)
        assert result.protein_gram == pytest.approx((20 * 3 + 144 * 1.2) / 10.0 / 2, abs=0.01)
        assert result.sodium_mg == pytest.approx(116.4, abs=0.01)
        assert result.coverage == pytest.approx(1.0)
        assert result.resolved_items == 2
        assert result.trustable()

    def test_divides_by_servings(self, directory):
        items = [item("番茄", 300)]
        assert nutrition.calculate(items, 1, directory).calories == pytest.approx(60.0, abs=0.01)
        assert nutrition.calculate(items, 2, directory).calories == pytest.approx(30.0, abs=0.01)

    def test_not_trustable_when_coverage_low(self, directory):
        # 200g 认得 + 200g 不认得 → 覆盖率 50%
        result = nutrition.calculate([item("番茄", 200), item("香茅", 200)], 2, directory)

        assert result.coverage == pytest.approx(0.5, abs=0.01)
        assert result.unresolved == ["香茅"]
        assert not result.trustable()

    def test_trustable_at_ninety_percent(self, directory):
        """覆盖率达标的边界：900g 认得 + 100g 不认得（90%）可以用。"""
        result = nutrition.calculate([item("番茄", 900), item("香茅", 100)], 2, directory)

        assert result.coverage == pytest.approx(0.9, abs=0.01)
        assert result.trustable()

    def test_empty_input(self, directory):
        assert not nutrition.calculate([], 2, directory).trustable()
        assert not nutrition.calculate(None, 2, directory).trustable()
        assert not nutrition.calculate(
            [item("  ", 10), item("番茄", 0)], 2, directory).trustable()

    def test_tolerates_directory_failure(self):
        """目录本身报错：按「没对上」处理，不影响生成链路。"""

        class ThrowingDirectory(Directory):
            def resolve_id(self, raw_name):
                raise RuntimeError("目录没加载")

        result = nutrition.calculate([item("番茄", 300)], 2, ThrowingDirectory([]))

        assert not result.trustable()
        assert result.calories == 0

    def test_uses_directory_for_alias_resolution(self):
        """归一化交给目录：别名也应该能被解析到同一份成分表。"""
        entries = [e for e in SNAPSHOT_ENTRIES if e.id == 101]  # 番茄，别名西红柿
        directory = Directory(entries)

        result = nutrition.calculate([item("西红柿", 200)], 2, directory)

        assert result.resolved_items == 1
        assert result.calories == pytest.approx(20.0, abs=0.01)

    def test_zero_calorie_entry_not_used_for_estimate(self):
        """转正食材 calorie=0 = 还不知道：估算必须放弃，而不是算成 0 冤枉正常结果。

        口径与 Java 一致：成分表计算（calculate）认条目，calorie=0 也照算；
        但 AI 估算对照（estimate_calories_per_serving / verify_nutrition）遇到
        calorie<=0 直接整体放弃 —— 拿它参与计算会把整道菜的热量压低，
        营养校验必然报"偏差过大"。
        """
        entries = [DirectoryEntry(id=101, name="番茄", category="蔬菜", calorie=0)]
        directory = Directory(entries)
        draft = DishDraft.from_dict({
            "ingredients": [{"name": "番茄", "amountGram": 300}],
            "nutritionPerServing": {"calories": 210},
        })

        assert nutrition.estimate_calories_per_serving(draft, 2, directory) is None
        assert not nutrition.verify_nutrition(draft, 2, 0.4, directory)
