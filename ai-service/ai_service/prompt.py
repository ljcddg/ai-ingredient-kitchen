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


def build_user_prompt(request: GenerateRequest, directory: Directory) -> str:
    """拼用户消息。格式与 dish-service/verify-prompt.py 里实测过的保持一致。"""
    names = "、".join(ref.name.strip() for ref in request.ingredients
                     if ref.name is not None and ref.name.strip())
    taste = "、".join(request.tastes) if request.tastes else "不限"

    parts = [f"食材：{names}；口味：{taste}；人数：{request.servings} 人"]
    parts.append(append_constraints(request.constraints, directory))
    return "".join(parts)


def append_constraints(constraints: Constraints, directory: Directory) -> str:
    """把用户约束原样写进提示词。

    约束必须"既进提示词、又进校验器"：只在提示词里说，模型可以不理；
    只在校验器里卡，模型第一次几乎必然踩雷、白跑一轮重试。
    """
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
    """把参考菜谱渲染成提示词里的一段；没有参考时返回空串。

    空串意味着提示词里连"参考"这两个字都不会出现，两臂（增强/不增强）的差别才干净。
    只给名称、主料、口味、耗时和前几步要点，不给完整步骤与用量：
    整段塞进去小模型会把它当答案照抄，参考是"灵感"而不是"标准答案"。
    """
    if not references:
        return ""

    lines = ["\n\n## 库内相似菜谱（仅供参考，不是答案）\n"]
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
        if ref.step_hints:
            line += "｜做法要点：" + " / ".join(ref.step_hints)
        lines.append(line + "\n")

    lines.append("以上只是本地库里的同类菜，用来参考搭配与手法：**必须自己写一道新菜**，"
                 "菜名不得与上面任何一道相同；用户的条件与手上的食材优先于参考。\n")
    return "".join(lines)
