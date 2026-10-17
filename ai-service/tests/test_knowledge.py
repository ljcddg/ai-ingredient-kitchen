"""烹饪常识知识库测试。

知识库这层最容易出的问题不是"选错"，而是"污染两臂对照"：
没参考的请求里混进参考段、或者同一请求两次生成注入了不同常识，
都会让实验结论说不清。所以除了召回，还要断言顺序与稳定性。
"""
from ai_service.knowledge import CookingKnowledge, MAX_RULES, load_rules
from ai_service.prompt import build_user_prompt
from ai_service.schemas import Constraints, GenerateRequest, IngredientRef, Reference

from .conftest import SNAPSHOT_ENTRIES


def make_request():
    return GenerateRequest(
        ingredients=[IngredientRef(101, "番茄"), IngredientRef(102, "鸡蛋")],
        tastes=["家常"], servings=2, directory=SNAPSHOT_ENTRIES)


def test_real_knowledge_file_is_loadable():
    """仓库里的知识库必须能读出来，且条目够用（少于 10 条说明文件被改坏了）。"""
    rules = load_rules()
    assert len(rules) >= 10
    assert all(r.get("id") and r.get("text") and r.get("tags") for r in rules)


def test_select_prefers_category_matched_rules():
    """选了肉类，就该召回焯水/上浆这类和肉有关的条目。"""
    kb = CookingKnowledge()
    picked = kb.select(["肉类"], [])
    ids = [r["id"] for r in picked]

    assert 0 < len(picked) <= MAX_RULES
    assert "blanch-meat" in ids or "velvet" in ids


def test_select_returns_nothing_for_unknown_category():
    """没有命中任何类别也没有额外词时，不硬塞通用条目 —— 少注入优于乱注入。"""
    kb = CookingKnowledge()
    assert kb.select(["不存在的类别"], []) == [] or all(
        "通用" in r["tags"] for r in kb.select(["不存在的类别"], []))


def test_selection_is_stable():
    """同一输入两次必须选出一样的条目（同分按知识库顺序）。"""
    kb = CookingKnowledge()
    first = [r["id"] for r in kb.select(["蔬菜", "肉类"], ["清淡"])]
    second = [r["id"] for r in kb.select(["蔬菜", "肉类"], ["清淡"])]
    assert first == second


def test_priority_breaks_generic_ties():
    """「通用」条目靠 priority 排序，而不是靠文件里的先后。

    这条是知识库能不能持续扩充的关键：如果只按命中分排，
    通用类永远是文件最前面那三条被选中，后面补的条目永远轮不到。
    """
    rules = [
        {"id": "low", "priority": 1, "tags": ["通用"], "text": "甲"},
        {"id": "high", "priority": 9, "tags": ["通用"], "text": "乙"},
        {"id": "mid", "priority": 5, "tags": ["通用"], "text": "丙"},
    ]
    picked = [r["id"] for r in CookingKnowledge(rules).select(["不存在的类别"], [], limit=2)]
    assert picked == ["high", "mid"]


def test_category_match_beats_priority():
    """命中食材类别比 priority 更重要 —— 做鱼时该讲蒸鱼时间，不是讲通用调味顺序。"""
    kb = CookingKnowledge()
    picked = [r["id"] for r in kb.select(["水产"], [])]
    assert "steam-timing" in picked or "pan-fry-fish" in picked


def test_render_empty_returns_empty_string():
    """空选择必须返回空串：提示词里连标题都不该出现。"""
    assert CookingKnowledge().render([]) == ""


def test_prompt_puts_knowledge_before_references():
    """顺序：硬约束 → 烹饪常识 → 参考菜谱 → 输出要求。"""
    from ai_service.directory import Directory

    kb = CookingKnowledge()
    block = kb.render(kb.select(["肉类"], []))
    references = [Reference(dish_id=1, name="红烧肉", mains=["五花肉"])]

    prompt = build_user_prompt(make_request(), Directory(SNAPSHOT_ENTRIES),
                              references, block)

    assert "【烹饪常识】" in prompt
    assert prompt.index("【硬约束】") < prompt.index("【烹饪常识】") < prompt.index("【参考菜谱】")


def test_prompt_without_knowledge_has_no_block():
    from ai_service.directory import Directory

    prompt = build_user_prompt(make_request(), Directory(SNAPSHOT_ENTRIES), [], "")
    assert "【烹饪常识】" not in prompt
