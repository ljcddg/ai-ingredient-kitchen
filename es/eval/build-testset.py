# -*- coding: utf-8 -*-
"""
构建 P1-5 的固定测试集（排序对照实验用）。

产出 `es/eval/testset-ranking.json`，包含 140 组查询，分五类：

    common      常见组合      2~3 样常见食材（点选 ID）
    missing     缺失主料      1 样常见食材 —— 大多数菜都会缺主料
    alias       别名          用 t_ingredient.alias 里的别名当自由输入（如「西红柿」）
    unknown     陌生食材      1 样常见 + 1 样主数据里没有的名字
    constraint  约束条件      2 样常见食材 + 忌口 / 时长上限 / 可用设备 / 宽松模式

## 可接受结果怎么标注（这是关键，必须说清楚）

本脚本用**客观可复现的规则**给每组查询标注 `acceptable_dish_ids`：

    一道已发布的菜"可接受" ⟺ 它的**必需主料**（全部非调料食材）都在用户手上

也就是"这道菜现在就能做"。规则完全由数据决定，不掺人工判断，
所以任何人重跑这个脚本都会得到同一份标注 —— 论文里写明这条定义即可。

（说明文档要求"为每组查询标注用户条件和可接受结果"，并没有要求人肉标注；
用可复现的机器规则标注，反而比人工标注更适合做对照实验。）

## 环境与前置

- 本机 MySQL（root/root，库 project0914），脚本通过 `mysql.exe` 读取主数据；
  刻意不依赖任何 Python MySQL 驱动 —— 少一个装不上的依赖。
- 只读，不写库。

用法：
    python es/eval/build-testset.py
"""
import json
import os
import random
import subprocess
import sys
import tempfile
from datetime import datetime

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'testset-ranking.json')

# 固定种子：同一份数据每次生成同一份测试集（可复现是实验的前提）
SEED = 20261001

# 每类查询的组数（说明文档建议 100~200 起步）
PLAN = {'common': 30, 'missing': 30, 'alias': 30, 'unknown': 20, 'constraint': 30, 'from_dish': 40}

# 陌生食材候选（会先剔除主数据里已经存在的名字）
UNKNOWN_CANDIDATES = [
    '香茅', '羽衣甘蓝', '紫苏', '欧芹', '莳萝', '牛至', '罗勒', '藏红花',
    '鱼腥草', '秋葵', '抱子甘蓝', '洋蓟',
]


def query(sql):
    """执行一条 SQL，返回二维数组（制表符分隔）。

    输出**重定向到临时文件**而不是管道：受限环境下子进程管道可能被拦，
    文件重定向没有这个问题，顺带也便于出错时把原始输出打出来。
    """
    with tempfile.NamedTemporaryFile(delete=False, suffix='.tsv') as tmp:
        path = tmp.name
    try:
        with open(path, 'wb') as out:
            subprocess.run(
                [MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                 '-D', DB, '-N', '-B', '-e', sql],
                stdout=out, stderr=subprocess.DEVNULL, check=False)
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            text = f.read()
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass
    return [line.split('\t') for line in text.splitlines() if line.strip()]


def load_master_data():
    """主数据：食材（含别名/类目）+ 已发布菜品的必需主料"""
    rows = query("SELECT id, name, IFNULL(alias,''), category, is_common, IFNULL(sort_no,0) "
                 "FROM t_ingredient WHERE status = 1 AND deleted = 0 ORDER BY id")
    ingredients = []
    for r in rows:
        if len(r) < 6:
            continue
        ingredients.append({
            'id': int(r[0]),
            'name': r[1],
            'aliases': [a for a in r[2].split(',') if a] if r[2] else [],
            'category': r[3],
            'common': r[4] == '1',
            'sort_no': int(r[5]),
        })

    # 菜品的必需主料 = 该菜全部非调料食材（与 DishSearchService.requiredMains 同一口径）
    seasoning_names = {i['name'] for i in ingredients if i['category'] == '调料'}
    detail = query("SELECT d.id, IFNULL(di.ingredient_id,''), di.ingredient_name "
                   "FROM t_dish d JOIN t_dish_ingredient di ON di.dish_id = d.id "
                   "WHERE d.status = 1 AND d.deleted = 0 ORDER BY d.id")
    dishes = {}
    for r in detail:
        if len(r) < 3:
            continue
        dish_id, ing_id, ing_name = int(r[0]), (int(r[1]) if r[1] else None), r[2]
        if ing_name in seasoning_names:
            continue
        dishes.setdefault(dish_id, []).append({'id': ing_id, 'name': ing_name})

    published = query("SELECT COUNT(*), IFNULL(MAX(publish_time),''), IFNULL(MAX(update_time),'') "
                      "FROM t_dish WHERE status = 1 AND deleted = 0")
    snapshot = {'published_dishes': int(published[0][0]) if published else 0,
                'max_publish_time': published[0][1] if published else '',
                'max_update_time': published[0][2] if published else ''}
    return ingredients, dishes, snapshot


def user_key_set(ingredient_ids, free_names, by_name):
    """用户手上的"钥匙集合"：ID 集合 + 没能归一的自由名字集合"""
    ids = set(ingredient_ids)
    names = set()
    for n in free_names:
        resolved = by_name.get(n)
        if resolved:
            ids.add(resolved)
        else:
            names.add(n)
    return ids, names


def acceptable_dishes(dishes, ids, names):
    """可接受结果 = 必需主料全都在用户手上的已发布菜品"""
    out = []
    for dish_id, required in dishes.items():
        ok = True
        for item in required:
            if item['id'] is not None:
                if item['id'] not in ids:
                    ok = False
                    break
            elif item['name'] not in names:
                ok = False
                break
        if ok and required:
            out.append(dish_id)
    return sorted(out)


def stable_split(group_key):
    """按"食材组合"分组划分验证集/测试集，避免同一输入的变体跨集合泄漏"""
    h = 0
    for ch in group_key:
        h = (h * 131 + ord(ch)) % 1_000_003
    return 'test' if h % 10 < 4 else 'validation'


def main():
    random.seed(SEED)
    ingredients, dishes, snapshot = load_master_data()
    if not ingredients or not dishes:
        print('主数据或菜品为空，先确认 MySQL 与数据是否就绪')
        return 1

    by_name = {}
    for i in ingredients:
        by_name[i['name']] = i['id']
        for a in i['aliases']:
            by_name[a] = i['id']

    common = sorted([i for i in ingredients if i['common']], key=lambda x: x['sort_no'])
    aliasable = [i for i in ingredients if i['aliases'] and i['category'] != '调料']
    unknown = [n for n in UNKNOWN_CANDIDATES if n not in by_name]
    # 忌口用的食材：挑一个确实出现在菜里的（否则约束不起作用）
    used_ids = {item['id'] for req in dishes.values() for item in req if item['id']}
    exclude_candidates = [i for i in ingredients if i['id'] in used_ids and i['category'] != '调料']

    queries = []
    idx = 0

    def add(group, ingredient_ids, free_names, mode, max_missing=None,
            exclude_ids=None, max_cook=None, equipment=None, note=''):
        nonlocal idx
        idx += 1
        ids, names = user_key_set(ingredient_ids, free_names, by_name)
        acceptable = acceptable_dishes(dishes, ids, names)
        key = '|'.join([group] + [str(x) for x in sorted(ids)] + sorted(names))
        queries.append({
            'query_id': 'q%03d' % idx,
            'group': group,
            'ingredientIds': sorted(ingredient_ids),
            'freeIngredients': list(free_names),
            'mode': mode,
            'maxMissing': max_missing,
            'excludeIngredientIds': exclude_ids or [],
            'maxCookTimeMin': max_cook,
            'availableEquipment': equipment or [],
            'note': note,
            'acceptable_dish_ids': acceptable,
            'user_ingredient_count': len(ids) + len(names),
            'split': stable_split(key),
        })

    # 1) 常见组合
    for _ in range(PLAN['common']):
        picked = random.sample(common[:max(6, len(common) // 2)], k=random.choice([2, 3]))
        add('common', [i['id'] for i in picked], [], 'loose', 2,
            note='、'.join(i['name'] for i in picked))

    # 2) 缺失主料（只给一样）
    for i in random.sample(common, k=min(PLAN['missing'], len(common))):
        add('missing', [i['id']], [], 'loose', 2, note=i['name'])

    # 3) 别名（自由输入里写别名，考归一化）
    for _ in range(PLAN['alias']):
        picked = random.sample(aliasable, k=min(2, len(aliasable)))
        add('alias', [], [random.choice(i['aliases']) for i in picked], 'loose', 2,
            note='、'.join(i['name'] for i in picked))

    # 4) 陌生食材（一样常见 + 一样库里没有的）
    for n in random.sample(unknown, k=min(PLAN['unknown'], len(unknown))):
        base = random.choice(common)
        add('unknown', [base['id']], [n], 'loose', 3, note='%s + %s' % (base['name'], n))

    # 5) 约束条件（忌口 / 时长 / 设备 / 严格模式轮流上）
    for k in range(PLAN['constraint']):
        picked = random.sample(common, k=min(2, len(common)))
        variant = k % 4
        if variant == 0:
            bad = random.choice(exclude_candidates)
            add('constraint', [i['id'] for i in picked], [], 'loose', 2,
                exclude_ids=[bad['id']], note='忌口 ' + bad['name'])
        elif variant == 1:
            add('constraint', [i['id'] for i in picked], [], 'strict', None,
                note='严格模式：只用现有主料')
        elif variant == 2:
            add('constraint', [i['id'] for i in picked], [], 'loose', 2, max_cook=20,
                note='总耗时 <= 20 分钟')
        else:
            add('constraint', [i['id'] for i in picked], [], 'loose', 2,
                equipment=['炒锅', '锅铲'], note='只有炒锅和锅铲')

    # 6) 由菜品反推的查询：保证有解
    #
    #    为什么需要这一类：前五类查询只给 1~3 样食材，而库里每道菜平均要 2~4 样主料，
    #    实测 132 组里只有 2 组存在"现在就能做"的菜 —— P@5 对绝大多数查询恒为 0，
    #    两个排序策略就没有区分度了。用"某道菜自己的主料集合"当查询，
    #    至少保证这道菜是可做的，P@5 才有意义；而前五类正好用来观察
    #    「平均缺失主料数」这类对无解查询同样敏感的指标。
    solved_pool = sorted([d for d, req in dishes.items() if req and len(req) <= 10])
    for dish_id in random.sample(solved_pool, k=min(PLAN['from_dish'], len(solved_pool))):
        req = dishes[dish_id]
        add('from_dish',
            [item['id'] for item in req if item['id'] is not None],
            [item['name'] for item in req if item['id'] is None],
            'loose', 2, note='由菜品 %d 的主料反推' % dish_id)

    payload = {
        'meta': {
            'built_at': datetime.now().isoformat(timespec='seconds'),
            'seed': SEED,
            'rule_acceptable': '必需主料（全部非调料食材）都在用户手上 = 这道菜现在就能做',
            'total': len(queries),
            'by_group': {g: sum(1 for q in queries if q['group'] == g) for g in PLAN},
            'by_split': {s: sum(1 for q in queries if q['split'] == s)
                         for s in ('validation', 'test')},
            'with_solution': sum(1 for q in queries if q['acceptable_dish_ids']),
            'dish_snapshot': snapshot,
        },
        'queries': queries,
    }
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print('已生成 %s' % OUT)
    print('  查询总数 : %d' % len(queries))
    print('  分组     : %s' % payload['meta']['by_group'])
    print('  划分     : %s' % payload['meta']['by_split'])
    print('  有解查询 : %d / %d（有至少一道"现在就能做"的菜）'
          % (payload['meta']['with_solution'], len(queries)))
    print('  菜库快照 : %s' % snapshot)
    return 0


if __name__ == '__main__':
    sys.exit(main())
