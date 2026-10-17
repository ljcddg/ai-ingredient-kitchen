#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
校验「前端食材表」与「后端 t_ingredient」是否严格对齐。

背景（这是本项目最容易踩的坑之一）：
前端点选食材后传给后端的是 **id**，后端 IngredientResolver 拿 id 去 t_ingredient 查，
查不到只打一条 warn 然后**静默跳过**。于是「只改前端、没改库」的后果是：
用户明明选了苦瓜，生成的菜里没有苦瓜，**而且没有任何报错**。

本脚本把三件事查出来：
  1. 前端有、库里没有（或已删除/停用）  → 选了点不到，必须修
  2. 同一个 id 两边名字不一致            → 显示与落库对不上，必须修
  3. 库里有、前端没有                    → 通常是候选食材转正（雪花 id 塞不进 Number），
                                          只提示，不算错

用法：
    python sql/verify-ingredient-sync.py
退出码：0 对齐；1 有必须修的问题
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JS_FILE = ROOT / "frontend-vue" / "src" / "lib" / "ingredients.js"

# 前端只挂 101~188、189~252 这两段人工维护的 id；转正食材是雪花 id，前端不列（见文件末尾说明）
JS_ID_MAX = 999


def query(sql):
    """用 mysql CLI 取数；Windows 下 mysql.exe 输出是 GBK，必须显式指定 utf8mb4 + errors=replace"""
    cmd = [
        "mysql", "-uroot", "-proot", "--default-character-set=utf8mb4",
        "-N", "-B", "-e", "USE project0914; " + sql,
    ]
    result = subprocess.run(cmd, capture_output=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        print("查询失败：", result.stderr, file=sys.stderr)
        sys.exit(2)
    rows = []
    for line in (result.stdout or "").splitlines():
        if not line.strip():
            continue
        rows.append(line.split("\t"))
    return rows


def load_frontend():
    """从 ingredients.js 里扒出 [{id, name}]，不执行 JS —— 只认 `{ id: N, name: 'X' }` 这个固定写法"""
    text = JS_FILE.read_text(encoding="utf-8")
    pairs = re.findall(r"\{\s*id:\s*(\d+)\s*,\s*name:\s*'([^']+)'\s*\}", text)
    return [(int(i), n) for i, n in pairs]


def main():
    fe = load_frontend()
    db = {int(i): (n, int(st), int(dl)) for i, n, st, dl in
          query("SELECT id, name, status, deleted FROM t_ingredient")}

    problems = []

    dup = {i for i, _ in fe if sum(1 for j, _ in fe if j == i) > 1}
    if dup:
        problems.append("前端 id 重复：%s" % sorted(dup))

    fe_ids = set()
    for i, name in fe:
        fe_ids.add(i)
        if i not in db:
            problems.append("前端有、库里没有：id=%s %s（选了会被静默跳过）" % (i, name))
            continue
        db_name, status, deleted = db[i]
        if deleted == 1 or status != 1:
            problems.append("前端有、库里已停用/删除：id=%s %s" % (i, name))
        if db_name != name:
            problems.append("同名不同字：id=%s 前端=%s 库=%s" % (i, name, db_name))

    # 库里有、前端没列：只对人工维护的 id 段（< JS_ID_MAX）报出来，雪花 id 属于转正食材
    for i, (name, status, deleted) in db.items():
        if i < JS_ID_MAX and i not in fe_ids and deleted == 0 and status == 1:
            problems.append("库里有、前端没列：id=%s %s（用户点不到，但匹配仍可命中）" % (i, name))

    promoted = [i for i in db if i >= JS_ID_MAX]
    print("前端食材数：%d" % len(fe))
    print("库内食材数：%d（其中候选转正的雪花 id %d 条，前端不列）" % (len(db), len(promoted)))

    if not problems:
        print("✅ 两边严格对齐")
        return 0

    print("\n❌ 发现 %d 个问题：" % len(problems))
    for p in problems:
        print("  - " + p)
    return 1


if __name__ == "__main__":
    sys.exit(main())
