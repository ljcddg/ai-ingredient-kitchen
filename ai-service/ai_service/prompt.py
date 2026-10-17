"""系统提示词加载 + 版本号 + 用户消息拼装。

提示词版本 = 内容 MD5 前 8 位：改了提示词之后，老结果属于哪一版必须查得出来，
否则"效果变好了"这种结论会连着两版提示词混在一起，说不清是谁的功劳。
（与 dish-service 的口径逐字一致：库里已有的版本号是 076b89e0。）
"""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import List, Optional

from .directory import Directory
from .schemas import Constraints, GenerateRequest, Reference

PROMPT_PATH = Path(__file__).resolve().parent.parent / "prompts" / "dish-generate-system.txt"


def load_system_prompt(path: Optional[Path] = None):
    """读系统提示词，返回 (内容, 版本号)。启动时读一次缓存，不必每次请求碰磁盘。"""
    prompt_file = path or PROMPT_PATH
    with open(prompt_file, "r", encoding="utf-8") as f:
        text = f.read()
    return text, md5_prefix(text)


def md5_prefix(text: str) -> str:
    """内容哈希前 8 位 —— 短到能塞进日志，又能区分不同版本。"""
    digest = hashlib.md5(text.encode("utf-8")).hexdigest()
    return digest[:8]


def build_user_prompt(request: GenerateRequest, directory: Directory,
                      references: Optional[List[Reference]] = None,
                      knowledge_block: str = "") -> str:
    """拼用户消息 —— 固定分段结构，只填字段，不塞自由文本。

    为什么要把原来的"一句话 + 附加要求"改成固定段：小模型（gemma4:e2b）的
    指令跟随很依赖结构线索，同一批信息按
    「可用食材 / 硬约束 / 烹饪常识 / 参考菜谱 / 输出要求」分段后，
    漏用食材与擅自加料的概率明显下降（RAG 方案 §3.3）。

    段的顺序是有讲究的，越靠后越接近模型的"下一步动作"：
    能给什么 → 不能干什么 → 这一步该怎么做 → 别人怎么配的 → 怎么输出。
    烹饪常识放在参考菜谱**之前**：先讲操作规程，再给搭配样例，
    否则模型容易照着参考的配方抄，把常识当成废话。
    """
    blocks = [_ingredient_block(request, directory)]

    constraints_block = _constraints_block(request, directory)
    if constraints_block:
        blocks.append(constraints_block)

    # 烹饪常识放在参考菜谱之前：先讲"这一步该怎么做"，再给"别人怎么配的"
    if knowledge_block:
        blocks.append(knowledge_block)

    reference_block = render_references(references)
    if reference_block:
        blocks.append(reference_block)

    # 输出要求里提到什么，取决于这次到底有没有参考 ——
    # 没有参考却写着"用量与参考菜谱不同时说明理由"，模型会去找一段不存在的参考。
    blocks.append(OUTPUT_BLOCK_WITH_REF if reference_block else OUTPUT_BLOCK_NO_REF)
    return "\n\n".join(blocks)


def _ingredient_block(request: GenerateRequest, directory: Directory) -> str:
    """可用食材：名字为主、有主数据 ID 的附在后面。

    名字是不能少的 —— 校验器按名字与别名比对；ID 只是让模型知道
    "这两样是同一个东西"，避免它写"西红柿"又写"番茄"当成两种料。
    """
    labels: List[str] = []
    for ref in request.ingredients:
        if ref is None or ref.name is None or not ref.name.strip():
            continue
        name = ref.name.strip()
        labels.append(f"{name}(ID:{ref.id})" if ref.id and ref.id > 0 else name)
    used = "、".join(labels) if labels else "（未提供）"
    return f"【可用食材】{used}"


def _constraints_block(request: GenerateRequest, directory: Directory) -> str:
    """硬约束：一条一行，没提的不出现（空段会让模型误以为有要求）。"""
    constraints = request.constraints
    items: List[str] = []

    if constraints is not None:
        must_use = must_use_labels(constraints, directory)
        if must_use:
            items.append("必用：" + "、".join(must_use))

        # 保序去重：名字在前、ID 翻出来的名字在后（与 Java LinkedHashSet 同序）
        excludes = list(dict.fromkeys(constraints.exclude_names))
        for ingredient_id in constraints.exclude_ids:
            name = directory.name_of(ingredient_id)
            excludes.append(name if name is not None else f"食材#{ingredient_id}")
        excludes = list(dict.fromkeys(excludes))
        if excludes:
            items.append("绝对不能用（过敏或忌口）：" + "、".join(excludes))

        if constraints.max_cook_time_min is not None:
            items.append(f"总耗时不超过 {constraints.max_cook_time_min} 分钟（备菜 + 下锅）")

        if constraints.equipment_names:
            items.append("只能用这些厨具：" + "、".join(constraints.equipment_names))

    items.append(f"人数：{request.servings} 人")
    if request.tastes:
        items.append("口味：" + "、".join(request.tastes))

    return "【硬约束】\n- " + "\n- ".join(items)


# 输出要求写在最后一段：模型对"最后看到的要求"最敏感。
# 两个版本只差"配料表允许来自哪里"这一句 —— 有参考时可以借用参考里的食材，
# 没参考时必须严格只用【可用食材】，否则等于默许模型自己加料。
_OUTPUT_HEAD = (
    "【输出要求】只输出一个 JSON 对象（第一个字符是 {，最后一个字符是 }）；"
    "不要输出本段或上面任何【】标签文字；"
)

OUTPUT_BLOCK_WITH_REF = (
    _OUTPUT_HEAD
    + "配料表里的每一项必须来自【可用食材】或【参考菜谱】，不要自己加料；"
    "用量若与参考菜谱不同，在 chefTips 里写一句理由。"
)

OUTPUT_BLOCK_NO_REF = (
    _OUTPUT_HEAD
    + "配料表里的每一项必须来自【可用食材】，不要自己加料，也不要引入没提到的食材。"
)


def append_constraints(constraints: Constraints, directory: Directory) -> str:
    """把用户约束写进提示词（保留给旧调用方，格式为由 _constraints_block 统一生成）。"""
    if constraints is None or constraints.no_constraint():
        return ""

    lines = ["\n\n用户附加要求（必须全部满足）："]

    must_use = must_use_labels(constraints, directory)
    if must_use:
        lines.append("\n- 必须用上的食材：" + "、".join(must_use))

    # 保序去重：名字在前、ID 翻出来的名字在后（与 Java LinkedHashSet 同序）
    excludes = list(dict.fromkeys(constraints.exclude_names))
    for ingredient_id in constraints.exclude_ids:
        name = directory.name_of(ingredient_id)
        excludes.append(name if name is not None else f"食材#{ingredient_id}")
    excludes = list(dict.fromkeys(excludes))
    if excludes:
        lines.append("\n- 绝对不能用（过敏或忌口）：" + "、".join(excludes))

    if constraints.max_cook_time_min is not None:
        lines.append(f"\n- 总耗时不超过 {constraints.max_cook_time_min} 分钟（备菜 + 下锅）")

    if constraints.equipment_names:
        lines.append("\n- 只能用这些厨具：" + "、".join(constraints.equipment_names))

    return "".join(lines)


def must_use_labels(constraints: Constraints, directory: Directory) -> List[str]:
    labels = list(constraints.must_use_names)
    for ingredient_id in constraints.must_use_ids:
        name = directory.name_of(ingredient_id)
        labels.append(name if name is not None else f"食材#{ingredient_id}")
    return labels


def render_references(references: Optional[List[Reference]]) -> str:
    """把参考菜谱渲染成【参考菜谱】段；没有参考时返回空串。

    空串意味着提示词里连"参考"这两个字都不会出现，两臂（增强/不增强）的差别才干净。

    给什么、不给什么：
      · 给——名称、口味、耗时、主料、**用量**、前几步做法要点；
      · 不给——完整步骤、完整调料表。
    整段塞进去小模型会把它当答案照抄，参考是"灵感"而不是"标准答案"。

    用量是后来补上的（依据校验需要它）：模型的"用量幻觉"多半来自没有可抄的数，
    给了具体克数后它更倾向于在量级内调整，而不是凭空编一个 300g。
    """
    if not references:
        return ""

    lines = ["【参考菜谱】（库内相似菜，只作灵感，不是答案）"]
    for index, ref in enumerate(references, 1):
        line = f"{index}. {ref.name or ''}"
        if ref.taste_tags:
            line += "｜口味：" + "、".join(ref.taste_tags)
        if ref.cook_time_min is not None:
            line += f"｜耗时约 {ref.cook_time_min} 分钟"
        if ref.mains:
            line += "｜主料：" + "、".join(ref.mains)
        if ref.missing:
            line += "（其中你手上没有：" + "、".join(ref.missing) + "）"
        if ref.amounts:
            # 用量写进参考：模型的"用量幻觉"多半来自没有可抄的数，
            # 给了具体克数后它更倾向于在量级内调整，而不是凭空编一个 300g
            pairs = [f"{a.name} {_fmt_gram(a.amount_gram)}g" for a in ref.amounts
                     if a.name and a.amount_gram]
            if pairs:
                line += "｜用量：" + "、".join(pairs)
        if ref.step_hints:
            line += "｜做法要点：" + " / ".join(ref.step_hints)
        lines.append(line)

    lines.append("必须自己写一道新菜，菜名不得与上面任何一道相同；用户的条件与手上的食材优先于参考。")
    return "\n".join(lines)


def _fmt_gram(value) -> str:
    """克数取整显示：参考菜谱是给人看的，不需要小数点。"""
    try:
        return str(int(round(float(value))))
    except (TypeError, ValueError):
        return str(value)
