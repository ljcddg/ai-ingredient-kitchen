"""依据校验（grounding check）。

一句话规则：**生成结果里每一样食材的用量，都应该能在参考菜谱里找到出处。**
找不到出处，就是模型编的；找到了但量差一大截，就是"有依据但偏离"。

三档判定（对齐 docs/thesis/RAG与功能扩展方案.md §3.4）：

| 档位 | 判据 | 含义 |
|---|---|---|
| GROUNDED | 参考里有同食材，用量在 0.5~2 倍之间 | 有依据 |
| DEVIATED | 参考里有同食材，但量差超过 2 倍 | 有依据但被放大/缩小 |
| UNGROUNDED | 参考里根本没有这个食材 | 幻觉嫌疑 |

为什么用"2 倍"而不是更细的阈值：参考菜谱本身是不同人数、不同做法，
用量天然有 1.5 倍上下的浮动；卡太紧会把正常差异判成幻觉，
卡太松（比如 3 倍）又会让"料酒 300g"这种明显错误漏过去。

这个判定刻意**不参与生成成败**：它只在结果出来后给一个可信度结论。
理由是全自动、可复现，但误判率不为零 —— 拿它拦生成会误杀好结果，
拿它做统计（faithfulness 比例）则正好。

文献锚点：Brown[1] 的系统综述在结论里点出"RAG 的评测仍以词面重叠类指标为主，
检索质量与生成质量的联合评测不充分"。faithfulness 就是补这个缺口的那一项 ——
它不看输出像不像参考，只看每一处用量有没有出处，检索与生成在同一把尺子上被度量。

**RAGAS 三项都落了**（原来只有第一项）：
  · faithfulness     —— 生成有没有依据（用量能否在参考里找到出处）
  · answer_relevance —— 回答有没有答所问（词面代理，见 answer_relevance()）
  · context_precision / context_recall —— 检索准不准、全不全（见 retrieval_metrics()）

后三项在真正的 RAGAS 里靠 LLM 反向生成问题/标注句级支撑，本项目没有标注数据、
本地模型也不适合自评（gemma4:e2b 判自己写的菜会一路给高分），所以一律取**可复现的
词面/集合代理**。这是明确的降级，不是等价替代：代理指标能横向比较"注入了参考 vs 没注入"
"改了召回 vs 没改"，但不能拿去和论文里的 RAGAS 绝对值比大小。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from .schemas import DishDraft, GenerateRequest, Reference

GROUNDED = "GROUNDED"
DEVIATED = "DEVIATED"
UNGROUNDED = "UNGROUNDED"

# 用量倍率容忍区间：0.5~2 倍算"有依据"
MIN_RATIO = 0.5
MAX_RATIO = 2.0

# 无据比例超过这个值 → 标记"低可信"（只标注，不拦）
LOW_CONFIDENCE_UNGROUNDED = 0.3


@dataclass
class ItemAssessment:
    """单条食材的判定结果。"""

    name: str
    grams: float
    tier: str
    reference_grams: Optional[float] = None

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "amountGram": self.grams,
            "tier": self.tier,
            "referenceGram": self.reference_grams,
        }


@dataclass
class Attribution:
    """整道菜的归因结果。"""

    items: List[ItemAssessment] = field(default_factory=list)
    grounded: int = 0
    deviated: int = 0
    ungrounded: int = 0
    # 另三项：各自独立判"判不了"（None），不能用一个 0 混过去
    answer_relevance: Optional[float] = None
    ctx_precision: Optional[float] = None
    ctx_recall: Optional[float] = None

    def total(self) -> int:
        return self.grounded + self.deviated + self.ungrounded

    def faithfulness(self) -> float:
        """有据比例：GROUNDED / 全部。这就是 RAGAS 口径里的 Answer Faithfulness。"""
        total = self.total()
        return 0.0 if total == 0 else self.grounded / total

    def judgeable(self) -> bool:
        """有没有可判的用量项。False 表示参考为空/参考没带用量，faithfulness 不成立。"""
        return self.total() > 0

    def low_confidence(self) -> bool:
        total = self.total()
        if total == 0:
            return False
        return self.ungrounded / total > LOW_CONFIDENCE_UNGROUNDED

    def summary(self) -> str:
        parts = [f"有据 {self.grounded} / 偏离 {self.deviated} / 无据 {self.ungrounded}"
                 f"（faithfulness {self.faithfulness():.2f}）"]
        if self.answer_relevance is not None:
            parts.append(f"回答相关性 {self.answer_relevance:.2f}")
        if self.ctx_precision is not None:
            parts.append(f"上下文精确率 {self.ctx_precision:.2f}")
        if self.ctx_recall is not None:
            parts.append(f"上下文召回率 {self.ctx_recall:.2f}")
        return "，".join(parts)

    def to_dict(self) -> dict:
        return {
            "grounded": self.grounded,
            "deviated": self.deviated,
            "ungrounded": self.ungrounded,
            "faithfulness": round(self.faithfulness(), 4),
            "lowConfidence": self.low_confidence(),
            # 三项都是 null 表示判不了；faithfulness 的 null 由 judgeable 表达
            # （Java 侧据此决定是否落库，不会把"判不了"写成 0 分）
            "judgeable": self.judgeable(),
            "answerRelevance": _round(self.answer_relevance),
            "ctxPrecision": _round(self.ctx_precision),
            "ctxRecall": _round(self.ctx_recall),
            "items": [i.to_dict() for i in self.items],
        }


def _round(value: Optional[float]) -> Optional[float]:
    return None if value is None else round(value, 4)


def assess(draft: Optional[DishDraft], references: Optional[Sequence[Reference]],
           directory=None, request: Optional[GenerateRequest] = None) -> Optional[Attribution]:
    """对一次生成结果做 RAGAS 三（四）项评估。

    返回 None 只有一种情况：没有草稿，没东西可判。

    **参考为空时也返回对象**（这是本次改造的一处语义变化）：faithfulness 那部分判不了
    （见 judgeable()），但 answer_relevance 照样能算 —— 而它正是"注入参考 vs 没注入"
    这组对照实验里对照组的唯一数据。无参考就把整个对象丢掉，等于 A/B 只剩实验组。

    为什么用量判定刻意**不参与生成成败**：它全自动、可复现，但误判率不为零 ——
    拿它拦生成会误杀好结果，拿它做统计（faithfulness 比例）则正好。
    """
    if draft is None:
        return None

    attribution = Attribution()

    # 参考侧的用量表：同名食材可能出现在多条参考里，取"较大的那个"做比较。
    # 菜谱条数少（≤5），取中位数没有意义 —— 判定的是数量级，不是精确值。
    reference_grams: Dict[str, float] = {}
    for ref in references or []:
        for amount in (ref.amounts or []):
            name = _canonical(amount.name, directory)
            if not name or amount.amount_gram is None or amount.amount_gram <= 0:
                continue
            # 多条参考给了同一个食材：取较大的那个（宽容一些，避免误判为偏离）
            if name not in reference_grams or amount.amount_gram > reference_grams[name]:
                reference_grams[name] = float(amount.amount_gram)

    # 参考里一条用量都没有（老数据 / 检索没带用量）时**不进判定循环**：
    # 那时候每一样食材都"找不到参照"，全会被记成 UNGROUNDED、faithfulness 变成 0 ——
    # 这是拿"参考没带用量"的锅去否定生成，正是这套指标最不能犯的错。
    # 计数留 0，由 judgeable() 表达"判不了"，落库时保持 NULL。
    candidates = (draft.ingredients or []) if reference_grams else []
    for item in candidates:
        if item is None or not item.name or item.amount_gram is None:
            continue
        key = _canonical(item.name, directory)
        ref_gram = reference_grams.get(key) if key else None

        if ref_gram is None:
            tier = UNGROUNDED
            attribution.ungrounded += 1
        else:
            ratio = float(item.amount_gram) / ref_gram
            if MIN_RATIO <= ratio <= MAX_RATIO:
                tier = GROUNDED
                attribution.grounded += 1
            else:
                tier = DEVIATED
                attribution.deviated += 1

        attribution.items.append(
            ItemAssessment(item.name, float(item.amount_gram), tier, ref_gram))

    attribution.answer_relevance = answer_relevance(draft, request, directory)
    attribution.ctx_precision, attribution.ctx_recall = retrieval_metrics(
        request, references, directory)
    return attribution


def _canonical(name: Optional[str], directory) -> Optional[str]:
    """把食材名归一到"主数据 ID 对应的标准名"，归不了就用原名的规范化形式。

    这一步是必须的：用户写「西红柿」、模型写「番茄」、参考库里写「番茄」，
    不归一就会把同一个食材判成"参考里没有" —— 无据比例会虚高。
    """
    if name is None:
        return None
    text = name.strip()
    if not text:
        return None
    if directory is not None:
        try:
            ingredient_id = directory.resolve_id(text)
            canonical = directory.name_of(ingredient_id) if ingredient_id else None
            if canonical:
                return canonical
        except Exception:
            pass
    return text


# --------------------------------------------------------------------- 后三项指标

# 口味 → 可检测的口语信号（出现在菜名/简介/调料/步骤里都算）。
#
# 这是**词面代理**：RAGAS 原版用 LLM 反向生成"这段回答能回答什么问题"再比对，
# 本项目用"用户点的口味在成品里有没有留下痕迹"来近似。它答不了"好不好吃"，
# 但能答"说了要微辣、成品里有没有辣"这类最基本的"答非所问"。
TASTE_SIGNALS: Dict[str, Tuple[str, ...]] = {
    "清淡": ("清淡", "清蒸", "白灼", "水煮", "少油", "低脂", "原味"),
    "咸香": ("盐", "生抽", "酱油", "蚝油", "酱香", "咸鲜"),
    "炖煮": ("炖", "焖", "煮", "煲", "烧", "煨"),
    "微辣": ("辣", "豆瓣", "辣椒", "花椒", "小米椒"),
    "酸甜": ("醋", "糖", "番茄酱", "柠檬", "酸甜"),
}

# 无判别力的口味标签：家常/下饭这类说的是"场景"，不是味道，
# 任何一道菜都算命中，算进去只会把这一维的分母做大、把指标稀释成常数。
NO_SIGNAL_TASTES = ("家常", "下饭", "快手")

# 各维权重：食材是用户最在意的（他要清冰箱），口味次之，约束再次
RELEVANCE_WEIGHTS = {"ingredient": 0.5, "taste": 0.3, "constraint": 0.2}


def answer_relevance(draft: Optional[DishDraft], request: Optional[GenerateRequest],
                     directory=None) -> Optional[float]:
    """答案相关性：生成结果在多大程度上真的回答了用户的请求。

    三个维度，**只统计"这次请求里确实提了"的那些**（没提口味就不该因为口味扣分），
    再按 RELEVANCE_WEIGHTS 归一化：

      · 食材覆盖：用户给的每样食材，是否出现在菜名/简介/调料/步骤里
        （按主数据归一，用户写「西红柿」、成品写「番茄」算命中）；
      · 口味契合：用户点的口味在成品文本里有没有对应信号，见 TASTE_SIGNALS；
      · 约束契合：有时长/厨具/忌口要求时，逐项检查是否满足。

    **为什么不是"用到的用户食材数/用户食材数"那一个比值**：校验器已经会拦掉漏食材的
    草稿，通过的菜这一项恒等于 1，指标就退化成常数、永远满分 —— 等于没测。
    加上口味与约束两维，指标才在"格式都合格"的样本之间有区分度。

    @return 0~1；三个维度都不适用时返回 None（判不了，不是 0 分）
    """
    if draft is None or request is None:
        return None

    text = _cooking_text(draft)
    scores: List[Tuple[str, float]] = []

    user_names = [ref.name.strip() for ref in (request.ingredients or [])
                  if ref is not None and ref.name and ref.name.strip()]
    if user_names:
        hit = sum(1 for name in user_names if _appears_in(text, name, directory))
        scores.append(("ingredient", hit / len(user_names)))

    tastes = [t for t in (request.tastes or []) if t]
    taste_hits = [_taste_hit(t, draft, text) for t in tastes]
    judged = [h for h in taste_hits if h is not None]
    if judged:
        scores.append(("taste", sum(1 for h in judged if h) / len(judged)))

    constraint_score = _constraint_match(draft, request)
    if constraint_score is not None:
        scores.append(("constraint", constraint_score))

    if not scores:
        return None
    weight_sum = sum(RELEVANCE_WEIGHTS[name] for name, _ in scores)
    if weight_sum <= 0:
        return None
    return sum(RELEVANCE_WEIGHTS[name] * value for name, value in scores) / weight_sum


def retrieval_metrics(request: Optional[GenerateRequest],
                      references: Optional[Sequence[Reference]],
                      directory=None) -> Tuple[Optional[float], Optional[float]]:
    """检索侧的两项指标（不需要草稿，只用到参考与用户食材）。

    把"用户手里的食材"当作信息需求（RAGAS 里 ground truth 的位置），
    "注入的参考菜谱"当作上下文，于是有标准的一对：

      · ctx_precision = 参考主料里用户真有的占比
        —— 低说明检索把一堆缺料的菜塞进了提示词，模型只能硬凑；
      · ctx_recall = 用户的食材被参考覆盖的比例
        —— 低说明用户手里的东西检索没找到对应菜（该判 GENERATE_FRESH 了）。

    两项都基于集合，不看用量，因此与 faithfulness 不重叠：
    faithfulness 问"写出来的量有没有出处"，这两项问"检索本身准不准、全不全"。

    @return (precision, recall)，各自在判不了时为 None（没参考 / 没给食材）
    """
    refs = list(references or [])
    if not refs:
        return None, None

    mains: set = set()
    missing: set = set()
    for ref in refs:
        for name in (ref.mains or []):
            key = _canonical(name, directory)
            if key:
                mains.add(key)
        for name in (ref.missing or []):
            key = _canonical(name, directory)
            if key:
                missing.add(key)

    precision: Optional[float] = None
    if mains:
        # missing 是 mains 的子集（检索器按"用户有哪些"算缺料），
        # 两者都做过归一，可以直接相减
        precision = max(0.0, (len(mains) - len(missing & mains)) / len(mains))

    user_names: List[str] = []
    if request is not None:
        user_names = [ref.name.strip() for ref in (request.ingredients or [])
                      if ref is not None and ref.name and ref.name.strip()]
    recall: Optional[float] = None
    if user_names:
        covered = sum(1 for name in user_names
                      if _canonical(name, directory) in mains)
        recall = covered / len(user_names)

    return precision, recall


def _cooking_text(draft: DishDraft) -> str:
    """"这道菜本身"的文本：菜名、简介、步骤、调料、标签。

    **刻意不含配料表**：配料表里必然列着用户给的食材（校验器拦掉了漏列的），
    把它算进来，"食材覆盖"这一维就恒等于 1、指标永远满分。
    要测的是"配料表里列的东西真的用上了吗"，所以只看正文。

    也不含 chefTips / substitutes：那是给用户的建议（"没有葱可以换洋葱"），
    提到某个食材不代表这道菜用了它 —— 算进来会把"没用上"说成"用上了"。
    """
    parts: List[str] = [draft.name or "", draft.summary or ""]
    parts.extend(draft.steps or [])
    parts.extend(draft.seasonings or [])
    parts.extend(draft.diet_tags or [])
    return "\n".join(p for p in parts if p)


def _draft_text(draft: DishDraft) -> str:
    """"用户能看到的一切"：正文 + 配料表 + 小贴士。

    用在约束检查上（忌口食材出现在哪都算踩线，菜谱小贴士里写"可以用花生油"
    对过敏的人同样危险），所以这里比 _cooking_text 收得更全。
    """
    parts: List[str] = [_cooking_text(draft)]
    parts.extend(f"{i.name}" for i in (draft.ingredients or []) if i is not None)
    parts.extend(draft.chef_tips or [])
    parts.extend(draft.substitutes or [])
    return "\n".join(p for p in parts if p)


def _appears_in(text: str, name: str, directory) -> bool:
    """食材名是否出现在文本里（别名也算：用户说西红柿、成品写番茄）。"""
    if name in text:
        return True
    canonical = _canonical(name, directory)
    if canonical and canonical in text:
        return True
    # 用户可能用的是别名，而成品用了正式名之外的同义词（"番茄" vs "西红柿"）：
    # 主数据目录里两者指向同一个 ID，这里已经通过 _canonical 归一过一次了，
    # 还查不到就只能算没出现 —— 宁严不滥，否则这个指标会虚高
    return False


def _taste_hit(taste: str, draft: DishDraft, text: str) -> Optional[bool]:
    """单个口味标签是否在成品里留下痕迹。None 表示这个标签无法判定。"""
    if taste in NO_SIGNAL_TASTES:
        return None
    if taste == "微辣" and draft.spicy_level is not None:
        if 1 <= draft.spicy_level <= 2:
            return True
    if taste == "清淡" and draft.spicy_level is not None:
        # 只有"不辣 + 没有重口做法"才算清淡；红烧不辣但不是清淡
        if draft.spicy_level == 0 and not any(
                k in text for k in ("油炸", "红烧", "干锅", "麻辣", "酱焖", "糖色")):
            return True
    keywords = TASTE_SIGNALS.get(taste)
    if not keywords:
        return None
    return any(k in text for k in keywords)


def _constraint_match(draft: DishDraft, request: GenerateRequest) -> Optional[float]:
    """约束契合度：用户提的硬条件满足了几成。没提任何约束时返回 None。"""
    constraints = request.constraints
    if constraints is None:
        return None
    checks: List[bool] = []

    if constraints.max_cook_time_min is not None:
        total = (draft.prep_time_min or 0) + (draft.cook_time_min or 0)
        checks.append(total <= constraints.max_cook_time_min)

    if constraints.equipment_names:
        available = constraints.equipment_names
        checks.append(all(any(a == e or e in a or a in e for a in available)
                          for e in (draft.equipment or [])))

    text = _draft_text(draft)
    for name in constraints.exclude_names or []:
        if name:
            checks.append(name not in text)
    if constraints.exclude_ids:
        # 禁用食材按 ID 给出的那部分由校验器兜底（那边有主数据可比），
        # 这里只看名字层面，避免两处各写一套 ID→名字的解析
        pass

    if not checks:
        return None
    return sum(1 for c in checks if c) / len(checks)
