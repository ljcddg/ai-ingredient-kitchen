#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把开源中文违规词库导入 t_sensitive_word。

数据来源：https://github.com/Konsheng/Sensitive-lexicon （MIT License）

【为什么要写脚本而不是直接 INSERT 一批词】
  开源词库不能整包倒进来。实测「广告类型.txt」里混着「网络」「招聘」「淘宝」，
  「色情词库.txt」里混着「刺激」「写真」—— 这些词在美食评论里全是正常用法，
  一条条拦下去只会把正常用户刷成星号。所以导入必须经过三道闸：
    · 停用词（业务上确定不能拦的通用词）
    · 长度闸（1 字词误伤率极高；超长词多半是拼出来的垃圾域名）
    · 纯 ASCII 短词闸（LY / 3J 这类缩写几乎全是误伤）
  过闸之后才生成 SQL，且 SQL 幂等（NOT EXISTS），重复执行不会插重复行。

【只导入哪几类】
  广告导流、色情低俗、涉枪涉爆、非法网址四类。词库里的政治类、时政类、
  以及混合了人名与公共事件的几个文件<b>不在本模块处理范围</b>，
  脚本既不读取也不输出其内容 —— 一个美食社区的评论治理用不上它们，
  而且这类词条的误伤率和合规风险都远高于收益。

用法：
  # 1. 先看统计，不写库
  python sql/import-sensitive-lexicon.py --dir "D:\\...\\Vocabulary" --dry-run

  # 2. 确认后真正导入
  python sql/import-sensitive-lexicon.py --dir "D:\\...\\Vocabulary" --apply

  # 3. 只导某一类
  python sql/import-sensitive-lexicon.py --dir ... --files 广告类型.txt --apply
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

# ---------------------------------------------------------------- 文件 → 类别映射
# level: 1=替换为星号（轻度，保留评论）；2=整条拦截待审（重度）
FILE_MAP = {
    "广告类型.txt": ("AD", 1, "广告导流"),
    "色情类型.txt": ("PORN", 2, "色情低俗"),
    "色情词库.txt": ("PORN", 2, "色情低俗"),
    "涉枪涉爆.txt": ("ILLEGAL", 2, "涉枪涉爆"),
    "非法网址.txt": ("ILLEGAL", 2, "非法网址"),
}

DEFAULT_FILES = list(FILE_MAP.keys())

# 不读取的文件（政治/时政/公共事件类，不属于本模块处理范围）
OUT_OF_SCOPE = {
    "政治类型.txt", "反动词库.txt", "GFW补充词库.txt", "零时-Tencent.txt",
    "网易前端过滤敏感词库.txt", "补充词库.txt", "新思想启蒙.txt",
    "COVID-19词库.txt", "暴恐词库.txt", "其他词库.txt",
    "民生词库.txt", "贪腐词库.txt",
}

# ---------------------------------------------------------------- 停用词
# 这些词在真实评论里绝大多数是正常用法。批量导入时一律跳过；
# 管理员若确实想拦，可以在管理页手工新增（人工录入不受本表限制）。
STOPWORDS = {
    # 通用名词
    "网络", "网站", "服务", "平台", "系统", "软件", "程序", "数据", "信息",
    "活动", "价格", "优惠", "便宜", "免费", "本店", "店铺", "商品", "产品",
    # 招聘/电商（广告词库里高频，但正常语境同样高频）
    "招聘", "兼职", "淘宝", "京东", "拼多多", "天猫", "微店", "代购",
    "包邮", "秒杀", "促销", "打折", "下单", "客服", "旺旺",
    # 美食语境高频词
    "好吃", "难吃", "味道", "口感", "食材", "做法", "步骤", "配方", "调料",
    "推荐", "建议", "分享", "试试", "喜欢", "不错", "一般", "失败", "成功",
    # 其它容易误伤的常用词
    "刺激", "写真", "打人", "拆迁", "腐败", "贪污", "救市", "老板", "老板儿",
    "小姐",  # 单独出现时多为称呼；组合词（如「找小姐」）仍会被更长词命中
}

# 纯 ASCII 短词闸：长度 <= 该值且全为字母数字的，一律跳过
ASCII_SHORT_MAX = 2

# 词长闸（针对导入，不针对手工录入）
MIN_LEN = 2
DEFAULT_MAX_LEN = 64

# 与后端 SensitiveWordService.normalizeWord 保持一致
NOISE_PATTERN = re.compile(r"[\s*_\-·.。~～+｜|/\\]+")

# ID 前缀：避开雪花 ID 的取值区间，一眼能看出是导入数据
ID_BASE = 1900000000000100000


def compact(word: str) -> str:
    """去干扰符 + 小写，与后端入库口径一致。"""
    return NOISE_PATTERN.sub("", word).strip().lower()


def is_ascii_short(word: str) -> bool:
    if len(word) > ASCII_SHORT_MAX:
        return False
    return all(ord(ch) < 128 for ch in word)


def load_file(path: str, category: str, level: int, max_len: int) -> tuple[list[str], dict]:
    """读一个词表文件，返回 (可用词, 统计)。"""
    stat = {"raw": 0, "dup": 0, "stopword": 0, "too_short": 0, "too_long": 0,
            "ascii_short": 0, "ok": 0}
    seen: set[str] = set()
    words: list[str] = []

    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            raw = line.strip()
            if not raw or raw.startswith("#"):
                continue
            stat["raw"] += 1
            word = compact(raw)
            if not word:
                continue
            if word in seen:
                stat["dup"] += 1
                continue
            seen.add(word)

            if word in STOPWORDS:
                stat["stopword"] += 1
                continue
            if len(word) < MIN_LEN:
                stat["too_short"] += 1
                continue
            if len(word) > max_len:
                stat["too_long"] += 1
                continue
            if is_ascii_short(word):
                stat["ascii_short"] += 1
                continue

            words.append(word)
            stat["ok"] += 1

    return words, stat


def sql_literal(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def build_sql(rows: list[tuple[str, str, int, str]], batch_size: int = 400) -> str:
    """生成幂等 INSERT。每批一个 INSERT ... SELECT，靠 NOT EXISTS 跳过已有词。"""
    lines = ["SET NAMES utf8mb4;", ""]
    total = len(rows)
    for start in range(0, total, batch_size):
        chunk = rows[start:start + batch_size]
        selects = []
        for offset, (word, category, level, source) in enumerate(chunk):
            new_id = ID_BASE + start + offset
            selects.append(
                "    SELECT {id} AS id, {word} AS word, {cat} AS category, {level} AS level, "
                "1 AS enabled, {src} AS source, '批量导入' AS remark, NOW() AS ct, NOW() AS ut".format(
                    id=new_id,
                    word=sql_literal(word),
                    cat=sql_literal(category),
                    level=level,
                    src=sql_literal(source),
                )
            )
        lines.append(
            "INSERT INTO t_sensitive_word "
            "(id, word, category, level, enabled, source, remark, create_time, update_time)"
        )
        lines.append("SELECT * FROM (")
        lines.append("\n    UNION ALL\n".join(selects))
        lines.append(") AS batch")
        lines.append("WHERE NOT EXISTS (")
        lines.append("    SELECT 1 FROM t_sensitive_word w WHERE w.word = batch.word AND w.deleted = 0")
        lines.append(");")
        lines.append("")
    lines.append("SELECT category, level, COUNT(*) AS cnt FROM t_sensitive_word")
    lines.append("WHERE deleted = 0 GROUP BY category, level ORDER BY category, level;")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="导入开源敏感词库到 t_sensitive_word")
    parser.add_argument("--dir", required=True, help="Vocabulary 目录")
    parser.add_argument("--files", default="", help="逗号分隔的文件名；留空则导入默认四类")
    parser.add_argument("--max-len", type=int, default=DEFAULT_MAX_LEN, help="词长上限（默认 64）")
    parser.add_argument("--apply", action="store_true", help="真正写库")
    parser.add_argument("--dry-run", action="store_true", help="只看统计（默认行为）")
    parser.add_argument("--output", default="", help="生成的 SQL 路径")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="root")
    parser.add_argument("--database", default="project0914")
    parser.add_argument("--mysql", default="mysql", help="mysql 客户端可执行文件")
    args = parser.parse_args()

    if not os.path.isdir(args.dir):
        print("目录不存在：%s" % args.dir)
        return 2

    files = [f.strip() for f in args.files.split(",") if f.strip()] or DEFAULT_FILES

    rows: list[tuple[str, str, int, str]] = []
    seen: set[str] = set()
    grand = {"raw": 0, "dup": 0, "stopword": 0, "too_short": 0, "too_long": 0,
             "ascii_short": 0, "ok": 0, "cross_dup": 0}

    print("=" * 68)
    print("词库目录：%s" % args.dir)
    print("=" * 68)

    for name in files:
        if name in OUT_OF_SCOPE:
            print("[跳过] %-26s 不在本模块处理范围（政治/时政类）" % name)
            continue
        if name not in FILE_MAP:
            print("[跳过] %-26s 未配置类别映射" % name)
            continue

        path = os.path.join(args.dir, name)
        if not os.path.isfile(path):
            print("[缺失] %-26s 文件不存在" % name)
            continue

        category, level, label = FILE_MAP[name]
        words, stat = load_file(path, category, level, args.max_len)

        added = 0
        for word in words:
            if word in seen:
                grand["cross_dup"] += 1
                continue
            seen.add(word)
            rows.append((word, category, level, "lexicon:" + name))
            added += 1

        for key in ("raw", "dup", "stopword", "too_short", "too_long", "ascii_short", "ok"):
            grand[key] += stat[key]

        print("[读入] %-26s %5d 条 → 可用 %4d（停用词 %d / 过短 %d / 超长 %d / 缩写 %d / 重复 %d）· %s L%d"
              % (name, stat["raw"], stat["ok"], stat["stopword"], stat["too_short"],
                 stat["too_long"], stat["ascii_short"], stat["dup"], label, level))

    print("-" * 68)
    print("合计：读入 %d 行 → 去重后可用 %d 条（跨文件重复 %d）"
          % (grand["raw"], len(rows), grand["cross_dup"]))
    print("      过滤：停用词 %d / 过短 %d / 超长 %d / 英文缩写 %d"
          % (grand["stopword"], grand["too_short"], grand["too_long"], grand["ascii_short"]))

    if not rows:
        print("没有可导入的词，结束。")
        return 0

    print("      样例：" + "、".join(w for w, _, _, _ in rows[:8]))

    sql = build_sql(rows)
    output = args.output or os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                         ".generated", "sensitive-words-import.sql")
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w", encoding="utf-8") as fh:
        fh.write(sql)
    print("      SQL 已生成：%s（%.1f KB）" % (output, os.path.getsize(output) / 1024))

    if not args.apply:
        print("\n[dry-run] 未写库。确认统计无误后加 --apply 执行。")
        return 0

    cmd = [args.mysql, "--host=" + args.host, "--port=" + str(args.port),
           "--user=" + args.user, "--password=" + args.password,
           "--default-character-set=utf8mb4", args.database]
    print("\n[执行] " + " ".join(cmd[:3] + ["...", args.database]) + " < " + output)
    with open(output, "r", encoding="utf-8") as fh:
        proc = subprocess.run(cmd, stdin=fh, capture_output=True)
    if proc.returncode != 0:
        print("导入失败：")
        print(proc.stderr.decode("utf-8", errors="replace"))
        return 1
    print(proc.stdout.decode("utf-8", errors="replace").strip())
    print("\n导入完成。词库变更在后端下一次 reload 时生效（增删改会自动重建，"
          "也可调 POST /ai/admin/sensitive-words/reload 立即刷新）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
