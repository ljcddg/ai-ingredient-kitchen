"""草稿校验器测试 —— DishDraftValidatorTest 的 Python 移植（逐条对应）。

它是"约束输出"三道防线里的第三道，写错一个边界值就会造成两种后果：
放过了烂数据（用户看到乱七八糟的菜谱），或者卡住了好数据（用户永远生成不出来）。
所以每种规则都要有正例和反例。
"""
from ai_service.schemas import (Constraints, DishDraft, DraftIngredient,
                                GenerateRequest, IngredientRef)
from ai_service.validator import DraftValidator

from .conftest import (BEEF, EGG, SCALLION, TOMATO, has, valid_draft,
                       valid_draft_dict, valid_request)


def validate(validator, draft, request=None):
    return validator.validate(draft, request or valid_request())


class TestValidator:
    # ---------------------------------------------------------------- 正例

    def test_valid_draft_passes(self, directory):
        validator = DraftValidator(directory)
        assert validate(validator, valid_draft()) == []

    def test_empty_required_skips_coverage(self, directory):
        validator = DraftValidator(directory)
        problems = validate(validator, valid_draft(), valid_request(refs=[]))
        assert problems == []

    def test_alias_matches_by_ingredient_id(self, directory):
        """用户写别名「西红柿」，模型写「番茄」也算用上了。"""
        validator = DraftValidator(directory)
        refs = [IngredientRef(TOMATO, "西红柿"), IngredientRef(EGG, "鸡蛋"),
                IngredientRef(SCALLION, "葱")]
        problems = validate(validator, valid_draft(), valid_request(refs=refs))
        assert problems == []

    # ---------------------------------------------------------------- 反例：字段规则

    def test_null_draft(self, directory):
        validator = DraftValidator(directory)
        problems = validate(validator, None)
        assert problems
        assert "为空" in problems[0]

    def test_bad_name(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.name = None
        assert any(p.startswith("name 缺失") for p in validate(validator, draft))

        draft2 = valid_draft()
        draft2.name = "这是一道名字特别特别长的菜名超过十二个字了"
        assert any(p.startswith("name 长度") for p in validate(validator, draft2))

    def test_bad_steps(self, directory):
        validator = DraftValidator(directory)
        draft1 = valid_draft()
        draft1.steps = ["只有一步"]
        assert any(p.startswith("steps 有 1 项") for p in validate(validator, draft1))

        draft2 = valid_draft()
        draft2.steps[0] = "太短"
        assert any(p.startswith("steps[1] 长度") for p in validate(validator, draft2))

    def test_bad_numbers(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.difficulty = 5
        draft.spicy_level = 9
        draft.prep_time_min = 0
        problems = validate(validator, draft)
        assert any(p.startswith("difficulty=") for p in problems)
        assert any(p.startswith("spicyLevel=") for p in problems)
        assert any(p.startswith("prepTimeMin=") for p in problems)

    # ---------------------------------------------------------------- 反例：食材

    def test_missing_required_ingredient(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients = [i for i in draft.ingredients if i.name != "葱"]
        problems = validate(validator, draft)
        assert has(problems, "没有用上用户提供的食材") and has(problems, "葱")

    def test_seasoning_counts_as_covered(self, directory):
        """调料出现在 seasonings 里也算命中。"""
        validator = DraftValidator(directory)
        refs = [IngredientRef(TOMATO, "番茄"), IngredientRef(EGG, "鸡蛋"),
                IngredientRef(SCALLION, "葱"), IngredientRef(118, "盐")]
        problems = validate(validator, valid_draft(), valid_request(refs=refs))
        assert problems == []

    def test_absurd_amount(self, directory):
        """用量夸张要报错（实测出现过「料酒 300g」）。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients.append(DraftIngredient(name="料酒", amount_gram=300))
        problems = validate(validator, draft)
        assert has(problems, "料酒") and has(problems, "明显过大")

    def test_amount_limit_scales_with_servings(self, directory):
        """800g 番茄 1 人份算夸张，3 人份就正常。"""
        validator = DraftValidator(directory)
        one = valid_draft()
        one.ingredients[0].amount_gram = 800
        at_one = validate(validator, one, valid_request(servings=1))
        assert has(at_one, "明显过大")

        three = valid_draft()
        three.ingredients[0].amount_gram = 800
        at_three = validate(validator, three, valid_request(servings=3))
        assert not any("番茄" in p and "明显过大" in p for p in at_three)

    def test_total_amount_must_match_servings(self, directory):
        """同一份 430g 的草稿：2 人份合理，6 人份明显不够吃。"""
        validator = DraftValidator(directory)
        two = validate(validator, valid_draft(), valid_request(servings=2))
        assert not has(two, "不匹配")

        six = validate(validator, valid_draft(), valid_request(servings=6))
        assert has(six, "与 6 人份不匹配")

    def test_water_rejected(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients.append(DraftIngredient(name="清水", amount_gram=500))
        assert any("不含营养的液体" in p for p in validate(validator, draft))

    # ---------------------------------------------------------------- 反例：用户约束

    def test_must_use_missing(self, directory):
        validator = DraftValidator(directory)
        constraints = Constraints(must_use_ids={BEEF})
        problems = validate(validator, valid_draft(), valid_request(constraints=constraints))
        assert has(problems, "用户要求必用的食材没有出现在配料表里") and has(problems, "牛肉")

    def test_must_use_not_used_in_steps(self, directory):
        """必用食材只在配料表里挂名、步骤没用到，也要报错。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients.append(DraftIngredient(name="牛肉", amount_gram=150))
        constraints = Constraints(must_use_ids={BEEF})
        problems = validate(validator, draft, valid_request(constraints=constraints))
        assert has(problems, "步骤中没有用到") and has(problems, "牛肉")

    def test_excluded_in_ingredients(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients.append(DraftIngredient(name="牛肉", amount_gram=150))
        constraints = Constraints(exclude_ids={BEEF})
        problems = validate(validator, draft, valid_request(constraints=constraints))
        assert has(problems, "出现了用户禁用的食材") and has(problems, "牛肉")

    def test_excluded_in_steps(self, directory):
        """禁用食材只写在步骤里同样要报错（过敏源写进做法也是事故）。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.steps[1] = "热锅倒花生油，中火烧至六成热，倒入蛋液快速划散后盛出。"
        constraints = Constraints(exclude_names=["花生油"])
        problems = validate(validator, draft, valid_request(constraints=constraints))
        assert has(problems, "出现了用户禁用的食材") and has(problems, "花生油")

    def test_cook_time_over_limit(self, directory):
        validator = DraftValidator(directory)
        constraints = Constraints(max_cook_time_min=10)
        problems = validate(validator, valid_draft(), valid_request(constraints=constraints))
        assert has(problems, "总耗时 14 分钟超过用户要求的 10 分钟")

    def test_unavailable_equipment(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.equipment = ["烤箱"]
        constraints = Constraints(equipment_names=["炒锅", "电饭煲"])
        problems = validate(validator, draft, valid_request(constraints=constraints))
        assert has(problems, "用到了用户没有的厨具") and has(problems, "烤箱")

    def test_equipment_name_tolerance(self, directory):
        """用户有「炒锅」时，「锅」不算缺。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.equipment = ["锅"]
        constraints = Constraints(equipment_names=["炒锅"])
        problems = validate(validator, draft, valid_request(constraints=constraints))
        assert not has(problems, "厨具")

    # ---------------------------------------------------------------- 反例：配料表与步骤一致性

    def test_ingredient_not_used_in_steps(self, directory):
        """配料表里的食材在步骤里没出现要报错。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        # 把所有提到「鸡蛋」的地方都换掉说法（鸡蛋 → 蛋/蛋液/蛋块），
        # 配料表却还列着鸡蛋 —— 这正是"配料表挂名、步骤里没用"的典型
        draft.steps[0] = "番茄去蒂切成滚刀块，另取一碗把蛋打散备用。"
        draft.steps[1] = "热锅倒油，中火烧至六成热，倒入蛋液快速划散后盛出。"
        draft.steps[3] = "倒回炒好的蛋块，加盐翻匀，撒上葱花即可出锅。"
        problems = validate(validator, draft)
        assert has(problems, "步骤中没有出现") and has(problems, "鸡蛋")

    def test_stray_ingredient_in_steps(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.steps[2] = "锅内留底油，放入葱白和香菇爆香，倒入番茄块大火翻炒出汁。"
        problems = validate(validator, draft)
        assert has(problems, "步骤里出现了配料表之外的食材") and has(problems, "香菇")

    def test_substring_match_no_longer_passes(self, directory):
        """旧实现的误判要消失：配料表只写「鸡蛋面」不能算用上了「鸡蛋」。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients = [
            DraftIngredient(name="番茄", amount_gram=300),
            DraftIngredient(name="鸡蛋面", amount_gram=150),
            DraftIngredient(name="葱", amount_gram=10),
        ]
        draft.steps = [
            "番茄去蒂切成滚刀块备用。",
            "锅中加水煮开，下鸡蛋面煮熟后捞出过凉水备用。",
            "锅内留底油，放入葱白爆香，倒入番茄块大火翻炒出汁。",
            "把面条与番茄翻匀，加盐调味即可出锅装盘。",
        ]
        problems = validate(validator, draft)
        assert has(problems, "没有用上用户提供的食材") and has(problems, "鸡蛋")

    # ---------------------------------------------------------------- 反例：营养

    def test_bad_nutrition(self, directory):
        validator = DraftValidator(directory)
        draft1 = valid_draft()
        draft1.nutrition_per_serving = None
        assert any("nutritionPerServing 缺失" in p for p in validate(validator, draft1))

        draft2 = valid_draft()
        draft2.nutrition_per_serving.calories = 5000
        assert any("calories=5000" in p for p in validate(validator, draft2))

        draft3 = valid_draft()
        draft3.nutrition_per_serving.sodium_mg = None
        assert any("sodiumMg 缺失" in p for p in validate(validator, draft3))

    def test_reports_all_problems(self, directory):
        """一次报多个问题（重试时能一次性告诉模型全部要改的地方）。"""
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.difficulty = 8
        draft.chef_tips = ["只有一条"]
        draft.ingredients = []
        problems = validate(validator, draft)
        assert len(problems) >= 3
        assert len(problems) == len(set(problems))

    def test_tolerates_empty_directory(self, broken_directory):
        """目录没加载时也不能崩（按 ID 都查不到名字，但检查要跑完）。"""
        validator = DraftValidator(broken_directory)
        problems = validate(validator, valid_draft())
        assert not has(problems, "没有用上用户提供的食材")

    # ---------------------------------------------------------------- 分档：格式 vs 约束

    def test_validate_detailed_splits_format_and_constraint(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.difficulty = 9                                        # 格式问题
        draft.ingredients = [i for i in draft.ingredients if i.name != "葱"]  # 约束问题

        result = validator.validate_detailed(draft, valid_request())

        assert not result.format_ok()
        assert not result.constraint_ok()
        assert len(result.problems) > len(result.constraint_problems)
        assert has(result.constraint_problems, "没有用上用户提供的食材")
        assert not result.passed()

    def test_format_ok_when_only_constraint_fails(self, directory):
        validator = DraftValidator(directory)
        draft = valid_draft()
        draft.ingredients = [i for i in draft.ingredients if i.name != "葱"]

        result = validator.validate_detailed(draft, valid_request())

        assert result.format_ok()
        assert not result.constraint_ok()

    def test_both_ok_when_passed(self, directory):
        validator = DraftValidator(directory)
        result = validator.validate_detailed(valid_draft(), valid_request())
        assert result.passed()
        assert result.format_ok()
        assert result.constraint_ok()
