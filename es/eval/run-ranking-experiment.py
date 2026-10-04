# -*- coding: utf-8 -*-
"""
P1-5 排序对照实验：legacy（原公式） vs satisfaction（主料满足率）。

同一份测试集、同一份菜库快照，每个查询跑两臂，只切换排序策略
（请求里的 strategy 字段，见 DishSearchRequest —— 靠改 Nacos 重启服务切换的话，
两臂之间菜库/索引可能已经变了，结果就不可比）。

## 指标（口径全部写在这里，避免事后解释）

- **Precision@5**：top-5 里有多少条属于"可接受结果"（标注规则见 build-testset.py），
  分母取 `min(5, 实际返回条数)`；一条都没返回时记 0 并计入**不足返回率**。
- **平均缺失主料数@5**：top-5 的 missingCount 平均值 —— 无解查询也能用，
  衡量"最接近能做的那几道菜离你有多远"。
- **不足返回率**：返回条数 < 5 的查询占比（说明文档要求同时报告）。
- **约束违反条数**：返回项里违反硬约束（缺料超上限/超时长/踩忌口）的条数 ——
  按设计应当恒为 0，留着是为了证明硬约束真的在过滤，而不是纸面上写着。
- **最优缺料命中率**：返回项中缺料最少的那条，是不是排在第 1 位。

以**测试集（test）**为主口径，验证集（validation）一并给出；两组都按
"食材组合"分组划分，同一输入的变体不会跨集合。

## 用法（需要 gateway 7000 / account 8081 / dish 8082 都在跑）

    python es/eval/run-ranking-experiment.py
"""
import json
import os
import random
import string
import sys
import urllib.error
import urllib.request
from datetime import datetime

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
HERE = os.path.dirname(os.path.abspath(__file__))
TESTSET = os.path.join(HERE, 'testset-ranking.json')
RESULT = os.path.join(HERE, 'results-ranking.json')

ARMS = ['legacy', 'satisfaction']
TOP_K = 5


def call(method, path, body=None, token=None, timeout=60):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', errors='replace')
        return e.code, {'raw': raw}
    except Exception as e:
        return 0, {'error': str(e)}


def login():
    username = 'eval' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '实验'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def search_body(query, arm):
    return {
        'ingredientIds': query['ingredientIds'],
        'freeIngredients': query['freeIngredients'],
        'mode': query['mode'],
        'maxMissing': query['maxMissing'],
        'excludeIngredientIds': query['excludeIngredientIds'],
        'maxCookTimeMin': query['maxCookTimeMin'],
        'availableEquipment': query['availableEquipment'],
        'strategy': arm,
        'page': 1,
        'size': TOP_K,
    }


def run_query(token, query, arm):
    """跑一个查询，返回这一条的全部原始观测（便于事后复算任何指标）"""
    st, body = call('POST', '/api/dish/search', search_body(query, arm), token=token)
    data = body.get('data') or {}
    items = data.get('candidates') or []
    # dishId 在 JSON 里是字符串（雪花 ID 防前端精度丢失），统一转成字符串比
    acceptable = {str(a) for a in query['acceptable_dish_ids']}

    top = items[:TOP_K]
    returned = len(items)
    hits = sum(1 for it in top if str(it.get('dishId')) in acceptable)
    missing = [it.get('missingCount') or 0 for it in top]

    # 约束违反自检：只做响应里能直接判的两条（缺料超上限、超时长）。
    # 「禁用食材」在响应里只有名字、没有 ID，脚本判不了 —— 那条由
    # 单测（DishSearchSatisfactionTest）和 verify-p1-search.py 的端到端用例覆盖。
    violations = 0
    allowed = 0 if query['mode'] == 'strict' else (query['maxMissing'] or 2)
    for it in items:
        if (it.get('missingCount') or 0) > allowed:
            violations += 1
        elif query['maxCookTimeMin'] and (it.get('cookTimeMin') or 0) > query['maxCookTimeMin']:
            violations += 1

    best_first = 0
    if missing:
        best = min(missing)
        if missing[0] == best:
            best_first = 1

    return {
        'query_id': query['query_id'],
        'group': query['group'],
        'split': query['split'],
        'arm': arm,
        'acceptable_total': len(acceptable),
        'returned': returned,
        'top5_dish_ids': [it.get('dishId') for it in top],
        'top5_missing': missing,
        'top5_satisfaction': [it.get('satisfactionRate') for it in top],
        'hit_count': hits,
        'precision_at_5': (hits / min(TOP_K, returned)) if returned else 0.0,
        'mean_missing_at_5': (sum(missing) / len(missing)) if missing else 0.0,
        'insufficient_return': 1 if returned < TOP_K else 0,
        'constraint_violations': violations,
        'best_missing_first': best_first,
        'direct_return': bool(data.get('directReturn')),
        'es_available': bool(data.get('searchEngineAvailable')),
    }


def aggregate(rows):
    if not rows:
        return {}
    n = len(rows)
    return {
        'queries': n,
        'precision_at_5': round(sum(r['precision_at_5'] for r in rows) / n, 4),
        'mean_missing_at_5': round(sum(r['mean_missing_at_5'] for r in rows) / n, 3),
        'insufficient_return_rate': round(sum(r['insufficient_return'] for r in rows) / n, 4),
        'empty_return_rate': round(sum(1 for r in rows if r['returned'] == 0) / n, 4),
        'constraint_violations': sum(r['constraint_violations'] for r in rows),
        'best_missing_first_rate': round(sum(r['best_missing_first'] for r in rows) / n, 4),
        'direct_return_rate': round(sum(1 for r in rows if r['direct_return']) / n, 4),
    }


def fmt(value):
    return '—' if value is None else ('%.3f' % value if isinstance(value, float) else str(value))


def main():
    if not os.path.exists(TESTSET):
        print('找不到测试集，先跑 build-testset.py')
        return 1
    with open(TESTSET, encoding='utf-8') as f:
        testset = json.load(f)
    queries = testset['queries']
    meta = testset['meta']

    token = login()
    if not token:
        print('登录失败：确认 gateway/account 服务在跑')
        return 1

    print('测试集：%d 组（有解 %d 组，菜库快照 %s）'
          % (meta['total'], meta['with_solution'], meta['dish_snapshot']))

    raw = []
    for q in queries:
        for arm in ARMS:
            raw.append(run_query(token, q, arm))

    # ---------- 汇总 ----------
    def subset(rows, **filters):
        out = rows
        for key, val in filters.items():
            out = [r for r in out if r[key] == val]
        return out

    summary = {}
    for arm in ARMS:
        arm_rows = subset(raw, arm=arm)
        test_rows = subset(arm_rows, split='test')
        test_solved = [r for r in test_rows if r['acceptable_total'] > 0]
        all_solved = [r for r in arm_rows if r['acceptable_total'] > 0]
        summary[arm] = {
            'all': aggregate(arm_rows),
            'test': aggregate(test_rows),
            'test_solved': aggregate(test_solved),
            'all_solved': aggregate(all_solved),
            'by_group': {
                g: aggregate([r for r in test_rows if r['group'] == g])
                for g in sorted({r['group'] for r in test_rows})
            },
        }

    payload = {
        'ran_at': datetime.now().isoformat(timespec='seconds'),
        'testset_meta': meta,
        'metrics_rule': {
            'precision_at_5': 'top-5 中属于"可接受结果"的条数 / min(5, 返回条数)',
            'mean_missing_at_5': 'top-5 的 missingCount 平均值',
            'insufficient_return_rate': '返回条数 < 5 的查询占比',
            'constraint_violations': '返回项违反硬约束的条数（设计上应恒为 0）',
        },
        'summary': summary,
        'raw': raw,
    }
    with open(RESULT, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    # ---------- 生成论文可用的表（同时落一份 Markdown）----------
    lines = []
    lines.append('# 排序对照实验结果（legacy vs satisfaction）')
    lines.append('')
    lines.append('- 运行时间：%s' % payload['ran_at'])
    lines.append('- 测试集：%s（共 %d 组，其中 %d 组"现在就能做"）'
                 % (os.path.basename(TESTSET), meta['total'], meta['with_solution']))
    lines.append('- 菜库快照：%s' % meta['dish_snapshot'])
    lines.append('- 可接受结果标注规则：%s' % meta['rule_acceptable'])
    lines.append('- 主口径：test 划分；validation 一并给出，防同一输入变体跨集合泄漏')
    lines.append('')
    lines.append('## 总览')
    lines.append('')
    lines.append('| 指标 | legacy（原公式） | satisfaction（新策略） |')
    lines.append('|---|---|---|')
    rows = [
        ('Precision@5（有解查询 · test）',
         summary['legacy']['test_solved'].get('precision_at_5'),
         summary['satisfaction']['test_solved'].get('precision_at_5')),
        ('Precision@5（有解查询 · 全体划分）',
         summary['legacy']['all_solved'].get('precision_at_5'),
         summary['satisfaction']['all_solved'].get('precision_at_5')),
        ('Precision@5（全体查询，无解记 0）',
         summary['legacy']['test'].get('precision_at_5'),
         summary['satisfaction']['test'].get('precision_at_5')),
        ('平均缺失主料数@5',
         summary['legacy']['test'].get('mean_missing_at_5'),
         summary['satisfaction']['test'].get('mean_missing_at_5')),
        ('最优缺料命中率（最少缺料的排第 1）',
         summary['legacy']['test'].get('best_missing_first_rate'),
         summary['satisfaction']['test'].get('best_missing_first_rate')),
        ('不足返回率（< 5 条）',
         summary['legacy']['test'].get('insufficient_return_rate'),
         summary['satisfaction']['test'].get('insufficient_return_rate')),
        ('零返回率（一条都没有）',
         summary['legacy']['test'].get('empty_return_rate'),
         summary['satisfaction']['test'].get('empty_return_rate')),
        ('约束违反条数（设计上应恒为 0）',
         summary['legacy']['test'].get('constraint_violations'),
         summary['satisfaction']['test'].get('constraint_violations')),
        ('直出率',
         summary['legacy']['test'].get('direct_return_rate'),
         summary['satisfaction']['test'].get('direct_return_rate')),
    ]
    for name, a, b in rows:
        lines.append('| %s | %s | %s |' % (name, fmt(a), fmt(b)))

    lines.append('')
    lines.append('## 分组：平均缺失主料数@5（test）')
    lines.append('')
    lines.append('| 分组 | 组数 | legacy | satisfaction |')
    lines.append('|---|---|---|---|')
    for g in sorted(summary['legacy']['by_group']):
        lg = summary['legacy']['by_group'][g]
        sa = summary['satisfaction']['by_group'][g]
        lines.append('| %s | %s | %s | %s |' % (g, lg.get('queries'),
                                               fmt(lg.get('mean_missing_at_5')),
                                               fmt(sa.get('mean_missing_at_5'))))

    lines.append('')
    lines.append('## 口径与局限（写作时必须一并交代）')
    lines.append('')
    lines.append('1. Precision@5 的分母取 `min(5, 实际返回条数)`；返回 0 条记 0 分并计入零返回率。')
    lines.append('2. 本菜库只有 %d 道已发布菜品，导致两类现象：'
                 % meta['dish_snapshot']['published_dishes'])
    lines.append('   - 有解查询（存在"现在就能做"的菜）占比低，P@5 的可比样本偏少；')
    lines.append('   - 不足返回率很高：用户只给 1~3 样食材时，多数菜因缺料超上限被硬约束筛掉。')
    lines.append('3. 「可做」标签是客观规则（必需主料全在手上），不含人工判断；')
    lines.append('   它衡量的是可制作性，不等于口味或做法质量。')
    lines.append('4. 耗时/资源开销不在本表：搜索是纯读路径，生成开销见生成记录表')
    lines.append('   （t_ai_generation_record 的 cost_ms / attempts）。')
    lines.append('')

    report = '\n'.join(lines)
    print(report)
    md_path = os.path.join(HERE, 'results-ranking.md')
    with open(md_path, 'w', encoding='utf-8') as f:
        f.write(report + '\n')

    print('完整结果（含每条查询的原始观测）：%s' % RESULT)
    print('论文可用的表：%s' % md_path)
    return 0


if __name__ == '__main__':
    sys.exit(main())
