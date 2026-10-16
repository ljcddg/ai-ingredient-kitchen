"""食材目录（快照版）测试 —— IngredientDirectoryTest 关键行为的 Python 对照。

重点：正式名优先、别名让位。数据里真的存在"「五花肉」既是自己的正式名、
又是「猪肉」的别名"这种冲突，如果混在一遍里登记，结果就取决于行序 ——
同一个名字有时归一到甲、有时归一到乙，缺料判断和营养计算跟着抖。
"""
from ai_service.directory import Directory
from ai_service.schemas import DirectoryEntry


def build(entries):
    return Directory(entries)


class TestDirectory:
    def test_resolves_formal_name_and_alias(self):
        directory = build([
            DirectoryEntry(id=101, name="番茄", alias="西红柿,tomato", category="蔬菜"),
        ])
        assert directory.resolve_id("番茄") == 101
        assert directory.resolve_id("西红柿") == 101
        assert directory.resolve_id(" tomato ") == 101  # 大小写/空白不敏感
        assert directory.resolve_id("辣椒") is None

    def test_formal_name_wins_over_alias(self):
        """正式名一定比别名更具体：别名统一后登记、一律让位。"""
        directory = build([
            DirectoryEntry(id=201, name="猪肉", alias="五花肉", category="肉类"),
            DirectoryEntry(id=202, name="五花肉", category="肉类"),
        ])
        assert directory.resolve_id("五花肉") == 202, "正式名必须赢"
        assert directory.resolve_id("猪肉") == 201

    def test_seasoning_by_category(self):
        directory = build([
            DirectoryEntry(id=108, name="盐", category="调料"),
            DirectoryEntry(id=101, name="番茄", alias="西红柿", category="蔬菜"),
        ])
        assert directory.is_seasoning(108)
        assert directory.is_seasoning_name("盐")
        assert not directory.is_seasoning_name("番茄")
        assert directory.category_of(101) == "蔬菜"

    def test_canonical_names_and_lookups(self):
        directory = build([
            DirectoryEntry(id=101, name="番茄", category="蔬菜", calorie=20),
            DirectoryEntry(id=108, name="盐", category="调料"),
        ])
        assert directory.canonical_names() == {"番茄", "盐"}
        assert directory.name_of(101) == "番茄"
        assert directory.entry_of(101).calorie == 20
        assert directory.entry_by_name("番茄").id == 101
        assert directory.entry_by_name("西红柿") is None, "估算口径不过别名"
        assert directory.size() == 2
