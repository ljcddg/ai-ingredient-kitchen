"""草稿校验器 —— DishDraftValidator 的 Python 移植。

"约束输出"三道防线里的第三道：
  1. 系统提示词（告诉它要什么）
  2. Ollama 的 format=json（约束解码，保证是合法 JSON）
  3. 本类（保证字段齐全、类型对、取值合理，且真的满足了用户条件）

校验失败的每条信息都会**原样拼进重试提示词**，所以每条都必须说清
"哪个字段、现在是什么、期望是什么" —— 写"格式错误"四个字对模型毫无帮助。

规则与 prompts/dish-generate-system.txt 里的字段说明一一对应，
改提示词时必须同步改这里，否则会出现"提示词允许但校验拒绝"的诡异现象。

四类检查（与 Java 版逐条对应）：
  1. 用户约束：必用食材有没有用上、禁用食材有没有出现（含步骤里的）、
     总耗时是否超标、是否用到了用户没有的厨具；
  2. 配料表与步骤的一致性：配料表里的食材要在步骤里有用途，
     步骤里也不能凭空多出配料表之外的食材；
  3. 用量按人数与类别：调料和蔬菜不该共用一个上限，
     1 人份和 6 人份也不该共用一个上限 —— 同时用它反查"人数是否一致"；
     上限本身按三级依据取（单品分位 → 类别分位 → 固定档），见 _gram_limit；
  4. 食材匹配走主数据 ID / 别名：原来用字符串包含判断，
     会把「鸡蛋面」里的「鸡蛋」当成命中了「鸡蛋」，也会因为用户写「西红柿」
     而模型写「番茄」就误判成没用到。
"""
from __future__ import annotations

import logging
import math
from collections import Counter
from dataclasses import dataclass, field
from typing import List, Optional, Sequence, Set, Tuple

from .amounts import (SOURCE_FIXED, SOURCE_INGREDIENT, STAT_FLOOR_PER_PERSON,
                      AmountIndex, serving_bucket)
from .directory import Directory, normalize_key
from .schemas import (AmountStat, Constraints, DishDraft, GenerateRequest,
                      QuantityStat)

logger = logging.getLogger(__name__)

# 每人份合理用量上限（克），按主数据类目分档 —— 一个固定值管不了所有场景。
# 这是**第三级**依据：统计表没建、或这一样食材/这一类目样本太少时才用它。
PER_PERSON_GRAM_BY_CATEGORY = {
    "蔬菜": 300, "果香": 300, "肉类": 250, "水产": 250, "主食": 250,
    "豆制品": 200, "蛋奶": 150, "乳品": 150, "干货": 100, "调料": 15, "其他": 250,
}
DEFAULT_PER_PERSON_GRAM = 250

# 余量系数：模型给的量偏大一点不该判错，超过这个倍数才报
AMOUNT_SLACK = 1.5

# 调料不随人数线性放大：3 人份也不会放 3 倍的盐
SEASONING_SERVING_GROWTH = 0.3

# 一份菜"人均主料总量"的合理区间，用来反查人数是否一致
MIN_MAIN_GRAM_PER_SERVING = 80
MAX_MAIN_GRAM_PER_SERVING = 450

# 单字食材名不参与"步骤里凭空多出食材"的扫描。
# 「盐」「糖」「醋」在中文里太容易作为构词成分出现（糖醋、盐水、醋溜），
# 报出来的全是误判；它们由"必用/禁用食材"那两条精确检查兜底。
MIN_NAME_LEN_FOR_STEP_SCAN = 2

# 明确不该出现在 ingredients 里的东西（提示词已要求不列调料）
SEASONING_KEYS = ("盐", "糖", "酱油", "生抽", "老抽", "料酒", "醋", "蚝油")


@dataclass
class ValidationResult:
    """校验结果。

    problems          全部问题（格式在前、约束在后）—— 会原样拼进重试提示词
    constraint_problems 其中的约束类问题（problems 的子集）

    为什么要分档：格式不合格 = 模型连结构化输出都没写对，该改的是提示词/约束解码；
    约束不合格 = 结构没问题但没听用户的话（漏了必用食材、踩了忌口），
    该改的是约束表达或换更强的模型。两者混成一个"失败率"，就看不出该往哪使劲。
    """

    problems: List[str] = field(default_factory=list)
    constraint_problems: List[str] = field(default_factory=list)

    def format_ok(self) -> bool:
        return len(self.problems) <= len(self.constraint_problems)

    def constraint_ok(self) -> bool:
        return not self.constraint_problems

    def passed(self) -> bool:
        return not self.problems


def _nz(values: Optional[list]) -> list:
    return values if values is not None else []


def _check_text(problems: List[str], field_name: str, value: Optional[str],
                min_len: int, max_len: int) -> None:
    if value is None or not value.strip():
        problems.append(f"{field_name} 缺失")
        return
    length = len(value.strip())
    if length < min_len or length > max_len:
        problems.append(f"{field_name} 长度 {length} 不在 {min_len}~{max_len} 之间")


def _check_range(problems: List[str], field_name: str, value: Optional[int],
                 min_v: int, max_v: int) -> None:
    if value is None:
        problems.append(f"{field_name} 缺失")
        return
    if value < min_v or value > max_v:
        problems.append(f"{field_name}={value} 不在 {min_v}~{max_v} 之间")


def _non_negative(problems: List[str], field_name: str, value: Optional[int]) -> None:
    if value is None:
        problems.append(f"{field_name} 缺失")
        return
    if value < 0:
        problems.append(f"{field_name}={value} 不该是负数")


def _check_list_size(problems: List[str], field_name: str, values: Optional[list],
                     min_size: int, max_size: int) -> None:
    if values is None or len(values) == 0:
        problems.append(f"{field_name} 缺失或为空")
        return
    if len(values) < min_size or len(values) > max_size:
        problems.append(f"{field_name} 有 {len(values)} 项，不在 {min_size}~{max_size} 之间")


def _valid_id(ingredient_id: Optional[int]) -> Optional[int]:
    """主数据 ID 是雪花 ID，永远是正数，非正数一律当作"没解析出来"。"""
    return ingredient_id if ingredient_id is not None and ingredient_id > 0 else None


class DraftValidator:
    """校验器：目录与用量分位随请求构建，构造后只读，可以被多个线程共享。

    两个可选输入（amount_stats / quantity_stats）来自 dish-service 的每请求快照。
    它们为空时行为与改造前完全一致（全部走固定上限）——"缺了也能跑"这条约束是刻意的：
    统计是优化，不该成为生成的前提条件。"""

    def __init__(self, directory: Directory,
                 amount_stats: Optional[Sequence[AmountStat]] = None,
                 quantity_stats: Optional[Sequence[QuantityStat]] = None):
        self.directory = directory
        # 用量分位索引：与提示词里的【常见用量】共用同一份口径（见 amounts.py）
        self.amounts = AmountIndex(amount_stats, quantity_stats)

    # ------------------------------------------------------------------ 入口

    def validate(self, draft: Optional[DishDraft],
                 request: GenerateRequest) -> List[str]:
        return self.validate_detailed(draft, request).problems

    def validate_detailed(self, draft: Optional[DishDraft],
                          request: GenerateRequest) -> ValidationResult:
        if draft is None:
            return ValidationResult(["输出为空，没有解析出任何字段"], [])

        format_problems: List[str] = []
        constraint_problems: List[str] = []

        servings = max(1, request.servings if request is not None else 2)
        constraints = request.constraints if request is not None else None
        if constraints is None:
            constraints = Constraints.none()

        # ---------- 1. 字段格式 ----------
        _check_text(format_problems, "name", draft.name, 1, 12)
        _check_text(format_problems, "summary", draft.summary, 20, 40)
        _check_range(format_problems, "prepTimeMin", draft.prep_time_min, 2, 60)
        _check_range(format_problems, "cookTimeMin", draft.cook_time_min, 5, 120)
        _check_range(format_problems, "difficulty", draft.difficulty, 1, 3)
        _check_range(format_problems, "spicyLevel", draft.spicy_level, 0, 3)

        _check_list_size(format_problems, "steps", draft.steps, 4, 8)
        if draft.steps is not None:
            for i, step in enumerate(draft.steps):
                _check_text(format_problems, f"steps[{i + 1}]", step, 15, 50)
        _check_list_size(format_problems, "seasonings", draft.seasonings, 1, 4)
        _check_list_size(format_problems, "equipment", draft.equipment, 1, 3)
        _check_list_size(format_problems, "chefTips", draft.chef_tips, 2, 3)
        _check_list_size(format_problems, "dietTags", draft.diet_tags, 1, 3)

        # ---------- 2. 配料表与用量（按人数与类别） ----------
        self._check_ingredients(format_problems, draft, servings)

        # ---------- 3. 营养 ----------
        self._check_nutrition(format_problems, draft.nutrition_per_serving)

        # ---------- 4. 用户食材与约束 ----------
        self._check_user_ingredients_used(constraint_problems, draft, request)
        self._check_must_use(constraint_problems, draft, constraints)
        self._check_excluded(constraint_problems, draft, constraints)
        self._check_cook_time(constraint_problems, draft, constraints)
        self._check_equipment(constraint_problems, draft, constraints)

        # ---------- 5. 配料表与步骤的一致性 ----------
        self._check_ingredients_used_in_steps(constraint_problems, draft, request, constraints)
        self._check_no_stray_ingredients_in_steps(constraint_problems, draft)

        all_problems = format_problems + constraint_problems
        logger.info("校验完成：格式问题 %d 项，约束问题 %d 项",
                    len(format_problems), len(constraint_problems))
        return ValidationResult(all_problems, constraint_problems)

    # ------------------------------------------------------------------ 配料表与用量

    def _check_ingredients(self, problems: List[str], draft: DishDraft,
                           servings: int) -> None:
        items = draft.ingredients
        if items is None or len(items) == 0:
            problems.append("ingredients 缺失或为空")
            return

        main_gram_total = 0
        sources: Counter = Counter()
        for i, item in enumerate(items):
            where = f"ingredients[{i + 1}]"
            if item is None or item.name is None or not item.name.strip():
                problems.append(f"{where}.name 缺失")
                continue
            name = item.name.strip()
            gram = item.amount_gram
            if gram is None or gram <= 0:
                problems.append(f"{where}({name}).amountGram 不是正整数")
                continue

            # 用量钳制 —— 模型编数字是常态，这里是拦它的地方
            if "水" in name or "高汤" in name or "清汤" in name:
                problems.append(f"ingredients 里不该出现不含营养的液体：{name}")
                continue

            seasoning = self._is_seasoning(name)
            limit, source, basis = self._gram_limit(name, seasoning, servings)
            sources[source] += 1
            if gram > limit:
                # 报错必须说清"现在是多少、依据是什么、上限是多少" ——
                # 这条信息会原样进重试提示词，只说"用量过大"模型改不动的
                problems.append(
                    f"{where}({name}) 用量 {gram}g 明显过大：{basis}"
                    f"，{servings} 人份应不超过 {limit}g")
            if not seasoning:
                main_gram_total += gram

        if sources:
            # 命中分布是判断"统计到底有没有生效"的唯一现场证据：
            # 全落在 fixed 上说明统计没注入（脚本没跑/表空），优化等于没做，
            # 而这种失效是静默的 —— 校验照样通过，只是回到了老口径。
            logger.info("用量上限依据分布：单品级 %d 项 / 类别级 %d 项 / 固定档 %d 项",
                        sources.get("ingredient", 0), sources.get("category", 0),
                        sources.get("fixed", 0))

        # 人数一致性：主料总量落在"人均 80~450g"之间。
        # 草稿里并没有 servings 字段 —— 让模型自己回填一个数字等于让它自证，
        # 不如用它给出的用量反推：写"2 人份"却只给 150 克主料、或者给 3 公斤，
        # 都是自相矛盾。
        if main_gram_total > 0:
            min_total = MIN_MAIN_GRAM_PER_SERVING * servings
            max_total = MAX_MAIN_GRAM_PER_SERVING * servings
            if main_gram_total < min_total or main_gram_total > max_total:
                problems.append(
                    f"主料总量 {main_gram_total}g 与 {servings} 人份不匹配"
                    f"（人均约 {MIN_MAIN_GRAM_PER_SERVING}~{MAX_MAIN_GRAM_PER_SERVING}g，"
                    f"合计应在 {min_total}~{max_total}g 之间）")

    def _gram_limit(self, name: str, seasoning: bool, servings: int
                    ) -> Tuple[int, str, str]:
        """按"食材 × 人数"算这道菜里该食材的用量上限。

        三级依据，逐级回退 —— 每一级都比下一级更"贴合这一样食材"：

          ① 单品级：库里真实菜谱里「番茄 × 1-2 人份」的 p90。最准，
             能把"番茄 800g / 2 人份"这种量明确判掉（类别级上限 900g 拦不住它）。
          ② 类别级：同类别同人数档的 p90。单品样本 <3 条时用
             （库里只有 113 道菜，一半食材压根凑不够样本）。
          ③ 固定档：PER_PERSON_GRAM_BY_CATEGORY。统计表没建/查询失败时的兜底，
             保证这条链路在任何环境下都不会因为数据缺失而失效。

        上限取 p90 而不是 max：一条录错的菜（料酒 500g）会永久污染 max；
        p90 天然抗单点异常，再乘 AMOUNT_SLACK=1.5，等于"比 90% 的同类做法再宽一半"。

        调料一律走固定档：统计表本身排除了调料（它们不随人数线性放大），
        而且盐/油的合理量由"口味"决定，不由"几个人吃"决定。

        @return (上限克数, 依据来源 ingredient/category/fixed, 给人看的依据说明)
        """
        if seasoning:
            per_person = PER_PERSON_GRAM_BY_CATEGORY["调料"]
            # 调料不随人数线性放大：3 人份也不会放 3 倍的盐
            scale = 1 + SEASONING_SERVING_GROWTH * max(0, servings - 1)
            limit = math.ceil(per_person * scale * AMOUNT_SLACK)
            return limit, "fixed", f"调料每人份约 {per_person}g"

        bucket = serving_bucket(servings)
        ingredient_id = self.directory.resolve_id(name)
        category = self.directory.category_of(ingredient_id)
        basis_stat = self.amounts.lookup(ingredient_id, category, bucket)

        if not basis_stat.has_stat():
            per_person = self._per_person_gram(name)
            limit = math.ceil(per_person * max(1, servings) * AMOUNT_SLACK)
            return limit, SOURCE_FIXED, f"该类食材每人份的合理上限是 {per_person}g"

        p90 = basis_stat.p90
        per_person = max(p90, STAT_FLOOR_PER_PERSON)
        limit = math.ceil(per_person * max(1, servings) * AMOUNT_SLACK)
        if basis_stat.source == SOURCE_INGREDIENT:
            basis = f"库里同类菜谱这样食材每份约 {p90:.0f}g（p90）"
        else:
            basis = f"库里{category or '同类'}类食材每份约 {p90:.0f}g（p90）"
        return limit, basis_stat.source, basis

    def _per_person_gram(self, name: str) -> int:
        category = self.directory.category_of(self.directory.resolve_id(name))
        by_category = PER_PERSON_GRAM_BY_CATEGORY.get(category) if category else None
        return by_category if by_category is not None else DEFAULT_PER_PERSON_GRAM

    def _is_seasoning(self, name: str) -> bool:
        """是不是调料。

        先用主数据的 category='调料'（权威），查不到再退回名字启发式
        （表外食材、或模型自造的词）—— 两个都查不到就按主料处理，宁可严一点。
        """
        if self.directory.is_seasoning_name(name):
            return True
        if name.endswith("油") or name.endswith("酱") or name.endswith("汁"):
            return True
        return any(key in name for key in SEASONING_KEYS)

    # ------------------------------------------------------------------ 食材匹配（ID / 别名）

    def _is_same_ingredient(self, candidate: str, target_id: Optional[int],
                            target_name: Optional[str]) -> bool:
        """判断草稿里的某个名字是不是目标食材。

        三条路，任一命中即可：
        1. 归一化后名字相同（「番茄」=「番茄」）；
        2. 两边都能解析成同一个主数据 ID（用户写「西红柿」、模型写「番茄」）；
        3. 目标只给了名字时，现场解析它的 ID 再比。
        这就是把原来的 text.contains(name) 换掉的原因：
        那个写法既会误判（「鸡蛋面」包含「鸡蛋」），也会漏判（别名对不上）。
        """
        candidate_key = normalize_key(candidate)
        target_key = normalize_key(target_name)
        if candidate_key is None:
            return False
        if target_key is not None and candidate_key == target_key:
            return True
        if target_id is not None:
            want_id: Optional[int] = target_id
        else:
            want_id = self.directory.resolve_id(target_key) if target_key else None
        candidate_id = self.directory.resolve_id(candidate)
        return want_id is not None and want_id > 0 and want_id == candidate_id

    def _all_edible_names(self, draft: DishDraft) -> List[str]:
        """草稿里"可食用的名字"：配料表 + 调料表。"""
        names = self._draft_ingredient_names(draft)
        for seasoning in _nz(draft.seasonings):
            if seasoning is not None and seasoning.strip():
                names.append(seasoning.strip())
        return names

    def _draft_ingredient_names(self, draft: DishDraft) -> List[str]:
        names: List[str] = []
        for item in _nz(draft.ingredients):
            if item is not None and item.name is not None and item.name.strip():
                names.append(item.name.strip())
        return names

    def _mentions(self, draft: DishDraft, ingredient_id: Optional[int],
                  name: Optional[str]) -> bool:
        """草稿里是否用到了这个食材（配料表或调料表里出现了它）。"""
        for candidate in self._all_edible_names(draft):
            if self._is_same_ingredient(candidate, ingredient_id, name):
                return True
        return False

    # ------------------------------------------------------------------ 用户食材与约束

    def _check_user_ingredients_used(self, problems: List[str], draft: DishDraft,
                                     request: GenerateRequest) -> None:
        """用户给的食材一个都不能少 —— 这是这个功能的根本承诺。"""
        if request is None or not request.ingredients:
            return
        missing: List[str] = []
        for ref in request.ingredients:
            if ref is None or ref.name is None or not ref.name.strip():
                continue
            if not self._mentions(draft, ref.id, ref.name.strip()):
                missing.append(ref.name.strip())
        if missing:
            problems.append("没有用上用户提供的食材：" + "、".join(missing))

    def _check_must_use(self, problems: List[str], draft: DishDraft, constraints) -> None:
        missing: List[str] = []
        for ingredient_id in constraints.must_use_ids:
            name = self.directory.name_of(ingredient_id)
            if not self._mentions(draft, ingredient_id, name):
                missing.append(name if name is not None else f"食材#{ingredient_id}")
        for name in constraints.must_use_names:
            if not self._mentions(draft, self.directory.resolve_id(name), name):
                missing.append(name)
        if missing:
            problems.append("用户要求必用的食材没有出现在配料表里：" + "、".join(missing))

    def _check_excluded(self, problems: List[str], draft: DishDraft, constraints) -> None:
        """禁用食材：配料表、调料表、以及步骤正文里都不许出现。

        步骤也要查，是因为"过敏源写进做法"同样是事故：配料表里没有花生，
        步骤里却写"用花生油炒香"，对过敏的人来说没有区别。
        """
        hit: List[str] = []
        steps = "\n".join(_nz(draft.steps))

        for ingredient_id in constraints.exclude_ids:
            name = self.directory.name_of(ingredient_id)
            label = name if name is not None else f"食材#{ingredient_id}"
            if self._mentions(draft, ingredient_id, name) or (name is not None and name in steps):
                hit.append(label)
        for name in constraints.exclude_names:
            if self._mentions(draft, self.directory.resolve_id(name), name) or name in steps:
                hit.append(name)
        if hit:
            problems.append("出现了用户禁用的食材：" + "、".join(dict.fromkeys(hit)))

    def _check_cook_time(self, problems: List[str], draft: DishDraft, constraints) -> None:
        if constraints.max_cook_time_min is None:
            return
        prep = draft.prep_time_min if draft.prep_time_min is not None else 0
        cook = draft.cook_time_min if draft.cook_time_min is not None else 0
        total = prep + cook
        if total > constraints.max_cook_time_min:
            problems.append(
                f"总耗时 {total} 分钟超过用户要求的 {constraints.max_cook_time_min} 分钟"
                f"（备菜 {prep} + 下锅 {cook}）")

    def _check_equipment(self, problems: List[str], draft: DishDraft, constraints) -> None:
        available = constraints.equipment_names
        if not available:
            return
        unavailable: List[str] = []
        for equipment in _nz(draft.equipment):
            if equipment is None or not equipment.strip():
                continue
            required = equipment.strip()
            if not any(self._same_equipment(a, required) for a in available):
                unavailable.append(required)
        if unavailable:
            problems.append(
                "用到了用户没有的厨具：" + "、".join(unavailable)
                + "（用户只有：" + "、".join(available) + "）")

    @staticmethod
    def _same_equipment(available: str, required: str) -> bool:
        """厨具名做宽松匹配。

        「锅」和「炒锅」、「蒸锅」和「蒸屉」这类包含关系不该触发重试 ——
        为用词差异反复重试，浪费的是十几秒的生成时间，换不来任何质量提升。
        真正要拦的是"用户只有炒锅、菜谱却要烤箱"这种硬缺口。
        """
        return available == required or required in available or available in required

    # ------------------------------------------------------------------ 配料表与步骤一致性

    def _check_ingredients_used_in_steps(self, problems: List[str], draft: DishDraft,
                                         request: GenerateRequest, constraints) -> None:
        """配料表里的食材，要在步骤里真的有用途。

        模型的典型偷懒是"配料表列齐了，步骤里只用其中两样" ——
        用户照着做会发现某样食材根本没有下锅时机。
        """
        text = "\n".join(_nz(draft.steps))
        if not text.strip():
            return

        must_use_unused: List[str] = []
        for ingredient_id in constraints.must_use_ids:
            name = self.directory.name_of(ingredient_id)
            if name is not None and not self._mentioned_in_text(text, name):
                must_use_unused.append(name)
        for name in constraints.must_use_names:
            if not self._mentioned_in_text(text, name):
                must_use_unused.append(name)
        if must_use_unused:
            problems.append(
                "用户要求必用的食材只出现在配料表里、步骤中没有用到："
                + "、".join(dict.fromkeys(must_use_unused)))

        if request is None or not request.ingredients:
            return
        unused: List[str] = []
        for ref in request.ingredients:
            if ref is None or ref.name is None or not ref.name.strip():
                continue
            name = ref.name.strip()
            if name in must_use_unused or not self._mentions(draft, ref.id, name):
                continue
            if not self._mentioned_in_text(text, name):
                unused.append(name)
        if unused:
            problems.append("配料表里的这些食材在步骤中没有出现：" + "、".join(unused))

    def _mentioned_in_text(self, text: str, name: str) -> bool:
        """步骤里是否真的提到了这个食材（允许用别名或它的正式名）。

        用户写「西红柿」、步骤里说「番茄」，算提到了。
        """
        if name in text:
            return True
        ingredient_id = self.directory.resolve_id(name)
        canonical = self.directory.name_of(ingredient_id) if ingredient_id else None
        return canonical is not None and canonical in text

    def _check_no_stray_ingredients_in_steps(self, problems: List[str],
                                             draft: DishDraft) -> None:
        """步骤里不能凭空多出配料表之外的食材。

        做法是用主数据当"食材词典"去扫步骤正文：词典里的名字出现在步骤里、
        却不在配料表/调料表里（也没被别名对上），就说明模型偷偷加了料。
        单字名（盐/糖/醋）不参与，理由见 MIN_NAME_LEN_FOR_STEP_SCAN。
        """
        text = "\n".join(_nz(draft.steps))
        if not text.strip():
            return

        listed_keys: Set[str] = set()
        listed_ids: Set[int] = set()
        for name in self._all_edible_names(draft):
            listed_keys.add(normalize_key(name))
            ingredient_id = _valid_id(self.directory.resolve_id(name))
            if ingredient_id is not None:
                listed_ids.add(ingredient_id)

        stray: List[str] = []
        for known in self.directory.canonical_names():
            if known is None or len(known) < MIN_NAME_LEN_FOR_STEP_SCAN:
                continue
            if known not in text:
                continue
            if normalize_key(known) in listed_keys:
                continue
            ingredient_id = _valid_id(self.directory.resolve_id(known))
            if ingredient_id is not None and ingredient_id in listed_ids:
                continue
            stray.append(known)
        if stray:
            problems.append("步骤里出现了配料表之外的食材：" + "、".join(stray))

    # ------------------------------------------------------------------ 营养

    def _check_nutrition(self, problems: List[str], nutrition) -> None:
        if nutrition is None:
            problems.append("nutritionPerServing 缺失")
            return
        _check_range(problems, "nutritionPerServing.calories", nutrition.calories, 0, 800)
        _non_negative(problems, "nutritionPerServing.proteinGram", nutrition.protein_gram)
        _non_negative(problems, "nutritionPerServing.fatGram", nutrition.fat_gram)
        _non_negative(problems, "nutritionPerServing.carbGram", nutrition.carb_gram)
        _non_negative(problems, "nutritionPerServing.fiberGram", nutrition.fiber_gram)
        _check_range(problems, "nutritionPerServing.sodiumMg", nutrition.sodium_mg, 200, 1500)
