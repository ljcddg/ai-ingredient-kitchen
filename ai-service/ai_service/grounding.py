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
口径按 RAGAS 的三分法（faithfulness / answer relevance / context precision），
本项目只落地了第一项，另两项留待有标注数据再做。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence

from .schemas import DishDraft, Reference

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

    def total(self) -> int:
        return self.grounded + self.deviated + self.ungrounded

    def faithfulness(self) -> float:
        """有据比例：GROUNDED / 全部。这就是 RAGAS 口径里的 Answer Faithfulness。"""
        total = self.total()
        return 0.0 if total == 0 else self.grounded / total

    def low_confidence(self) -> bool:
        total = self.total()
        if total == 0:
            return False
        return self.ungrounded / total > LOW_CONFIDENCE_UNGROUNDED

    def summary(self) -> str:
        return (f"有据 {self.grounded} / 偏离 {self.deviated} / 无据 {self.ungrounded}"
                f"（faithfulness {self.faithfulness():.2f}）")

    def to_dict(self) -> dict:
        return {
            "grounded": self.grounded,
            "deviated": self.deviated,
            "ungrounded": self.ungrounded,
            "faithfulness": round(self.faithfulness(), 4),
            "lowConfidence": self.low_confidence(),
            "items": [i.to_dict() for i in self.items],
        }


def assess(draft: Optional[DishDraft], references: Optional[Sequence[Reference]],
           directory=None) -> Optional[Attribution]:
    """对一次生成结果做依据校验。

    返回 None 表示"判不了"：没有任何参考、或参考里一条用量都没有。
    这种情况不能记成 0 分 —— 那是拿冷启动的代价去否定 RAG，结论会失真。
    """
    if draft is None or not references:
        return None

    # 参考侧的用量表：同名食材可能出现在多条参考里，取**中位数**附近更稳，
    # 但这里菜谱条数少（≤5），直接取各条的最小二乘没有意义，
    # 用"最接近的参考值"做比较即可 —— 判定的是数量级，不是精确值。
    reference_grams: Dict[str, float] = {}
    for ref in references:
        for amount in (ref.amounts or []):
            name = _canonical(amount.name, directory)
            if not name or amount.amount_gram is None or amount.amount_gram <= 0:
                continue
            # 多条参考给了同一个食材：取较大的那个（宽容一些，避免误判为偏离）
            if name not in reference_grams or amount.amount_gram > reference_grams[name]:
                reference_grams[name] = float(amount.amount_gram)

    if not reference_grams:
        return None

    attribution = Attribution()
    for item in (draft.ingredients or []):
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
