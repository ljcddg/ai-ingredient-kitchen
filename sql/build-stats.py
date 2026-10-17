"""离线统计脚本：从 t_dish_ingredient 算出「用量分位数」与「食材替代」。

为什么是离线脚本而不是服务里的定时任务：
  · 统计口径会反复调（分位数、样本阈值、替代规则），重跑脚本比发版快；
  · ai-service 有"不碰业务库"的铁律，这类活只能放在 Java 侧或离线工具里；
  · 数据量小（679 行），一次全量重算几百毫秒，不需要增量。

用法（在项目根目录）：
    python sql/build-stats.py            # 只打印将要执行的 SQL
    python sql/build-stats.py --apply    # 真的写库

依赖：本机 mysql 客户端（不装任何 Python 包）。

文献锚点：Choi[8] 的 KitchenScale 把"预测用量"拆成计量类型分类 → 单位分类 → 数量回归
三个子任务，用微调语言模型做。本项目的约束是**不训练模型**（本地只有 gemma4:e2b，
为一道菜的用量单独训一个回归头不划算），所以取它的统计版：
同一类别、同一人数档下的 P10/P50/P90 就是"这个量正常应该是多少"的非参数估计。
代价是拿不到"计量类型/单位"这两级的精度 —— 分位数只回答数量，不回答"该用勺还是用克"。
"""
from __future__ import annotations

import hashlib
import os
import subprocess
import sys
import tempfile
from collections import Counter, defaultdict
from statistics import median

MYSQL = os.environ.get("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")
DB = os.environ.get("MYSQL_DB", "project0914")
USER = os.environ.get("MYSQL_USER", "root")
PASSWORD = os.environ.get("MYSQL_PASSWORD", "root")
HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")

# 用量样本过滤：一道菜里单样食材超过 2kg 基本是脏数据或"水"这类不计营养的液体
MAX_SANE_GRAM = 2000
# 共现替代的最小支撑：只一起出现过 1 次就推荐，等同于噪声
MIN_CO_OCCUR = 3
# 每个食材最多给几条替代
MAX_SUBSTITUTES = 5
# 用来算分位数的分位点
PERCENTILES = (0.10, 0.50, 0.90)


def _mysql_cmd(*extra: str) -> list[str]:
    """统一的 mysql 客户端命令。

    --default-character-set=utf8mb4 不能省：Windows 下 mysql.exe 默认按控制台
    代码页（GBK）输出中文，Python 用 utf-8 解会直接抛 UnicodeDecodeError，
    表现是 stdout 变成 None —— 排查起来像"查询没返回"。
    """
    return [MYSQL, "-u", USER, f"-p{PASSWORD}", "-h", HOST, "-D", DB,
            "--default-character-set=utf8mb4", *extra]


def query(sql: str) -> list[list[str]]:
    """执行只读查询，返回按 tab 切开的行（mysql -N 批处理模式）。"""
    result = subprocess.run(
        _mysql_cmd("-N", "-B", "-e", sql),
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0:
        raise SystemExit(f"查询失败：{result.stderr.strip()[:400]}")
    return [line.split("\t") for line in result.stdout.splitlines() if line.strip()]


def stable_id(key: str) -> int:
    """确定性 ID：同一 key 每次跑出同一个值，重复执行不会产生垃圾行。"""
    return int(hashlib.md5(key.encode("utf-8")).hexdigest()[:15], 16)


def bucket_of(servings: int) -> str:
    if servings <= 2:
        return "1-2"
    if servings <= 4:
        return "3-4"
    return "5+"


def percentile(sorted_values: list[float], p: float) -> float:
    """最近秩法取分位：样本少的时候比线性插值稳，不会插出一个样本里没有的值。"""
    if not sorted_values:
        return 0.0
    rank = max(1, min(len(sorted_values), int(round(p * len(sorted_values) + 0.5))))
    return sorted_values[rank - 1]


def build_quantity_stats() -> list[str]:
    """按 类别 × 人数档 统计单样食材的每人份用量分位数。"""
    rows = query("""
        SELECT i.category, d.base_serving, di.amount_gram, di.ingredient_name
        FROM t_dish_ingredient di
        JOIN t_dish d ON d.id = di.dish_id AND d.status = 1 AND d.deleted = 0
        JOIN t_ingredient i ON i.id = di.ingredient_id AND i.deleted = 0
        WHERE i.category <> '调料' AND di.amount_gram > 0
    """)

    samples: dict[tuple[str, str], list[float]] = defaultdict(list)
    skipped = 0
    for category, serving, gram, _name in rows:
        gram_value = float(gram)
        servings = max(1, int(float(serving or 2)))
        if gram_value > MAX_SANE_GRAM:
            skipped += 1
            continue
        samples[(category, bucket_of(servings))].append(gram_value / servings)

    statements = []
    for (category, bucket), values in sorted(samples.items()):
        values.sort()
        p10, p50, p90 = (percentile(values, p) for p in PERCENTILES)
        key = f"{category}|{bucket}"
        statements.append(
            "REPLACE INTO t_ingredient_quantity_stats "
            "(id, category, serving_bucket, p10, p50, p90, sample_count) VALUES "
            f"({stable_id(key)}, '{category}', '{bucket}', {p10:.2f}, {p50:.2f}, {p90:.2f}, {len(values)});"
        )
    print(f"[用量] {len(samples)} 组（跳过 {skipped} 条超 {MAX_SANE_GRAM}g 的异常样本）")
    return statements


def build_substitutes() -> list[str]:
    """两路来源：常一起做（共现）+ 同类可换（同 category）。

    <p><b>为什么 meta 取全表而不是只取"有菜谱用到的"：</b>
    "同类可换"本来就不依赖菜谱 —— 苦瓜和丝瓜是不是一类东西，看食材主数据就知道。
    早期版本只从 t_dish_ingredient 取，导致新加的食材（库里还没有菜用它们）
    一条替代建议都生成不出来，用户选中时只能看到空白。共现那一路仍然只来自菜谱。
    """
    rows = query("""
        SELECT di.dish_id, di.ingredient_id, i.name, i.category, i.calorie
        FROM t_dish_ingredient di
        JOIN t_dish d ON d.id = di.dish_id AND d.status = 1 AND d.deleted = 0
        JOIN t_ingredient i ON i.id = di.ingredient_id AND i.deleted = 0
        WHERE i.category <> '调料'
    """)

    by_dish: dict[str, list[tuple[str, str, str, float]]] = defaultdict(list)
    for dish_id, ingredient_id, name, category, calorie in rows:
        by_dish[dish_id].append((ingredient_id, name, category, float(calorie or 0)))

    # 全量食材（不含调料）—— 同类别替代的候选池
    meta: dict[str, tuple[str, str, float]] = {}
    for ingredient_id, name, category, calorie in query("""
            SELECT id, name, category, calorie FROM t_ingredient
            WHERE deleted = 0 AND status = 1 AND category <> '调料'
            """):
        meta[ingredient_id] = (name, category, float(calorie or 0))

    # ---- 共现：同一道菜里一起出现，说明它们"能搭"，但不等于"能替" ----
    # 所以分数压得低（0.3~0.7），只作为"没有同类候选时的兜底"
    pair_counts: Counter[tuple[str, str]] = Counter()
    for items in by_dish.values():
        ids = sorted({i[0] for i in items})
        for i, src in enumerate(ids):
            for dst in ids[i + 1:]:
                pair_counts[(src, dst)] += 1
                pair_counts[(dst, src)] += 1

    statements: list[str] = []
    written: set[tuple[str, str]] = set()

    # ---- 同类：叶菜换叶菜、根茎换根茎，这是"替"的本义 ----
    # 蛋奶与乳品单独合成一组：鸡蛋没有"同类别"邻居，但牛奶/芝士在营养角色上接近
    def group_of(category: str) -> str:
        return "蛋乳" if category in ("蛋奶", "乳品") else category

    by_category: dict[str, list[tuple[str, float]]] = defaultdict(list)
    for ingredient_id, (_name, category, calorie) in meta.items():
        by_category[group_of(category)].append((ingredient_id, calorie))

    for category, items in by_category.items():
        for ingredient_id, calorie in items:
            neighbours = sorted(
                ((abs(calorie - other_cal), other) for other, other_cal in items if other != ingredient_id),
            )[:MAX_SUBSTITUTES]
            for distance, other in neighbours:
                # 热量越接近越像；同类但热量差 3 倍以上（如冬瓜 vs 红薯）不推荐
                if calorie > 0 and distance / calorie > 2:
                    continue
                score = max(0.5, 0.9 - distance / max(1.0, calorie) * 0.2)
                _add(statements, written, ingredient_id, other, round(score, 4),
                     "same_category", f"同属{category}，热量接近，可互换", 0)

    # ---- 共现兜底：只在同类组内、且同类别候选不够时才用 ----
    # 跨类别共现（番茄↔鸡蛋）是"常一起做"，不是"可以互相替代"。
    # 早期版本没卡这条，结果给「鸡蛋」推出了「松露」—— 数据上共现过，语义上荒谬。
    for (src, dst), count in pair_counts.most_common():
        if count < MIN_CO_OCCUR or (src, dst) in written:
            continue
        if group_of(meta[src][1]) != group_of(meta[dst][1]):
            continue
        if sum(1 for s, _ in written if s == src) >= MAX_SUBSTITUTES:
            continue
        score = min(0.65, 0.3 + count * 0.05)
        dst_name = meta.get(dst, ("", "", 0))[0]
        _add(statements, written, src, dst, round(score, 4), "co_occur",
             f"常和{dst_name}一起做，风味接近", count)

    print(f"[替代] 写入 {len(statements)} 条（每个食材最多 {MAX_SUBSTITUTES} 条）")
    return statements


def _add(statements: list[str], written: set[tuple[str, str]], src: str, dst: str,
         score: float, source: str, reason: str, samples: int) -> None:
    key = (src, dst)
    if key in written or src == dst:
        return
    written.add(key)
    statements.append(
        "REPLACE INTO t_ingredient_substitute "
        "(id, ingredient_id, substitute_id, score, source, reason, sample_count) VALUES "
        f"({stable_id(f'{src}->{dst}')}, {src}, {dst}, {score}, '{source}', "
        f"'{reason}', {samples});"
    )


def main() -> None:
    # 派生表整表重算：REPLACE 只会覆盖同名唯一键，规则收紧后不再生成的旧行
    # （比如上一版推出来的「鸡蛋→松露」）会一直留在表里。先清后写才是干净快照。
    statements = ["DELETE FROM t_ingredient_quantity_stats;",
                  "DELETE FROM t_ingredient_substitute;",
                  ] + build_quantity_stats() + build_substitutes()
    if "--apply" not in sys.argv:
        print("\n".join(statements[:5]))
        print(f"... 共 {len(statements)} 条；加 --apply 真正写库")
        return

    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False, encoding="utf-8") as f:
        f.write("\n".join(statements))
        path = f.name
    with open(path, "r", encoding="utf-8") as sql_file:
        result = subprocess.run(
            _mysql_cmd(), stdin=sql_file, capture_output=True,
            text=True, encoding="utf-8", errors="replace",
        )
    if result.returncode != 0:
        raise SystemExit(f"写库失败：{result.stderr.strip()[:400]}")
    print(f"已写入 {len(statements)} 条统计")


if __name__ == "__main__":
    main()
