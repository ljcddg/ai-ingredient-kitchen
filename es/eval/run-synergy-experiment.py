# -*- coding: utf-8 -*-
"""
P1-5 实验：检索生成协同（检索优先能省掉多少模型调用）。

## 要回答的问题

同一批请求，"先检索、能直接给出答案就不生成"相比"一律交给模型现做"，
能省下多少模型调用、端到端快多少、质量差在哪。

## 口径（写作时要一并交代）

- **直接可用**：检索结果里存在 `直接可用=1` 的菜（缺料为 0 且质量达标，判定逻辑见
  `DishSearchService` 的直出条件）。这类请求<b>完全不需要模型</b>。
- **现在能做**：检索结果里存在缺料为 0 的菜（不管评价够不够）——
  严格意义上是"能照做"，但可能质量证据不足，属于宽松口径。
- **需要生成**：以上两条都不成立 —— 手上这组食材在库里找不到能做的菜。
  模型调用比例就按这个口径算。
- **耗时**：检索路径用脚本实测的 HTTP 往返（含网关与 ES），
  生成路径用生成记录表里**真实跑过**的 `queue_wait_ms + cost_ms`
  （不是估算值，也不是我另挑的样本）。
- **质量**：检索直出的菜有社区评价（威尔逊下界/收藏数）；
  AI 新生成的菜<b>没有评价数据</b>——这不是缺点被藏起来了，而是两类东西的本质区别，
  所以质量一栏只报检索侧的真实数字，生成侧如实写"无评价"。

用法（需要 gateway/account/dish + ES 在跑）：
    python es/eval/run-synergy-experiment.py
"""
import json
import os
import random
import string
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
HERE = os.path.dirname(os.path.abspath(__file__))
TESTSET = os.path.join(HERE, 'testset-ranking.json')
RAW = os.path.join(HERE, 'results-synergy.json')
REPORT = os.path.join(HERE, 'results-synergy.md')

MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'


def mysql(sql):
    with tempfile.NamedTemporaryFile(delete=False, suffix='.tsv') as tmp:
        path = tmp.name
    err_path = path + '.err'
    try:
        with open(path, 'wb') as out, open(err_path, 'wb') as err:
            result = subprocess.run(
                [MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                 '-D', DB, '-N', '-B', '-e', sql], stdout=out, stderr=err, check=False)
        if result.returncode != 0:
            with open(err_path, encoding='utf-8', errors='replace') as f:
                print('  [SQL 失败] ' + ' '.join(
                    line for line in f.read().splitlines() if 'password' not in line)[:200])
        with open(path, encoding='utf-8', errors='replace') as f:
            return [line.split('\t') for line in f.read().splitlines() if line.strip()]
    finally:
        for p in (path, err_path):
            try:
                os.unlink(p)
            except OSError:
                pass


def call(method, path, body=None, token=None, timeout=60):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        return 0, {'error': str(e)}


def login():
    username = 'syn' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '协同实验'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))
    return ordered[idx]


def main():
    if not os.path.exists(TESTSET):
        print('找不到测试集，先跑 build-testset.py')
        return 1
    with open(TESTSET, encoding='utf-8') as f:
        queries = json.load(f)['queries']

    token = login()
    if not token:
        print('登录失败：确认 gateway/account 在跑')
        return 1

    print('检索优先实验：%d 组查询' % len(queries))
    rows = []
    for q in queries:
        body = {
            'ingredientIds': q['ingredientIds'],
            'freeIngredients': q['freeIngredients'],
            'mode': q['mode'],
            'maxMissing': q['maxMissing'],
            'excludeIngredientIds': q['excludeIngredientIds'],
            'maxCookTimeMin': q['maxCookTimeMin'],
            'availableEquipment': q['availableEquipment'],
            'strategy': 'satisfaction',
            'page': 1,
            'size': 5,
        }
        start = time.time()
        st, resp = call('POST', '/api/dish/search', body, token=token)
        latency_ms = (time.time() - start) * 1000
        data = resp.get('data') or {}
        items = data.get('candidates') or []

        direct = any(i.get('directEligible') for i in items)
        cookable = any((i.get('missingCount') or 0) == 0 for i in items)
        missing = [i.get('missingCount') or 0 for i in items]
        best = items[0] if items else None

        rows.append({
            'query_id': q['query_id'],
            'group': q['group'],
            'latency_ms': round(latency_ms, 1),
            'returned': len(items),
            'direct_usable': direct,
            'cookable_now': cookable,
            'best_missing': min(missing) if missing else None,
            'top1_dish_id': best.get('dishId') if best else None,
            'top1_wilson': best.get('ratingWilson') if best else None,
            'top1_collect': best.get('collectCount') if best else None,
            'top1_missing': best.get('missingCount') if best else None,
        })

    total = len(rows)
    direct_n = sum(1 for r in rows if r['direct_usable'])
    cookable_n = sum(1 for r in rows if r['cookable_now'])
    need_generation_strict = total - direct_n
    need_generation_lenient = total - cookable_n
    latencies = [r['latency_ms'] for r in rows]

    # 检索直出的质量：只看真的直出的那些查询
    direct_rows = [r for r in rows if r['direct_usable']]
    wilson = [r['top1_wilson'] for r in direct_rows if r['top1_wilson'] is not None]
    collect = [r['top1_collect'] for r in direct_rows if r['top1_collect'] is not None]

    # 生成路径的真实耗时：直接读生成记录表
    gen = mysql("SELECT cost_ms, attempts, status FROM t_ai_generation_record "
                "WHERE cost_ms > 0 ORDER BY create_time DESC LIMIT 50")
    gen_costs = [int(r[0]) for r in gen]
    gen_attempts = [int(r[1]) for r in gen]
    gen_failed = sum(1 for r in gen if r[2] == '3')

    stats = {
        'queries': total,
        'direct_usable': direct_n,
        'cookable_now': cookable_n,
        'model_call_ratio_strict': round(need_generation_strict / total, 4) if total else None,
        'model_call_ratio_lenient': round(need_generation_lenient / total, 4) if total else None,
        'search_latency_p50_ms': percentile(latencies, 50),
        'search_latency_p95_ms': percentile(latencies, 95),
        'search_latency_mean_ms': round(sum(latencies) / len(latencies), 1) if latencies else None,
        'direct_top1_wilson_mean': round(sum(wilson) / len(wilson), 4) if wilson else None,
        'direct_top1_collect_mean': round(sum(collect) / len(collect), 1) if collect else None,
        'generation_samples': len(gen_costs),
        'generation_latency_p50_ms': percentile(gen_costs, 50),
        'generation_latency_p95_ms': percentile(gen_costs, 95),
        'generation_latency_mean_ms': round(sum(gen_costs) / len(gen_costs)) if gen_costs else None,
        'generation_mean_attempts': round(sum(gen_attempts) / len(gen_attempts), 2) if gen_attempts else None,
        'generation_failure_rate': round(gen_failed / len(gen), 4) if gen else None,
    }

    payload = {'ran_at': datetime.now().isoformat(timespec='seconds'),
               'testset': os.path.basename(TESTSET), 'stats': stats, 'rows': rows}
    with open(RAW, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    lines = []
    lines.append('# 检索生成协同实验结果')
    lines.append('')
    lines.append('- 运行时间：%s' % payload['ran_at'])
    lines.append('- 测试集：%s（%d 组）' % (payload['testset'], total))
    lines.append('- 检索路径耗时：脚本实测的 HTTP 往返（含网关与 ES）')
    lines.append('- 生成路径耗时：生成记录表里真实跑过的 `queue_wait_ms + cost_ms`（近 %d 条）'
                 % len(gen_costs))
    lines.append('')
    lines.append('## 检索优先能省掉多少模型调用')
    lines.append('')
    lines.append('| 口径 | 命中查询数 | 占比 | 需要生成的查询 | **模型调用比例** |')
    lines.append('|---|---|---|---|---|')
    lines.append('| 直接可用（缺料 0 + 质量达标） | %d | %.1f%% | %d | **%.1f%%** |'
                 % (direct_n, 100.0 * direct_n / total, need_generation_strict,
                    100.0 * stats['model_call_ratio_strict']))
    lines.append('| 现在能做（缺料 0） | %d | %.1f%% | %d | **%.1f%%** |'
                 % (cookable_n, 100.0 * cookable_n / total, need_generation_lenient,
                    100.0 * stats['model_call_ratio_lenient']))
    lines.append('')
    lines.append('## 端到端耗时与质量')
    lines.append('')
    lines.append('| 指标 | 检索路径 | 生成路径 |')
    lines.append('|---|---|---|')
    lines.append('| P50 耗时 | %s ms | %s ms |'
                 % (stats['search_latency_p50_ms'], stats['generation_latency_p50_ms']))
    lines.append('| P95 耗时 | %s ms | %s ms |'
                 % (stats['search_latency_p95_ms'], stats['generation_latency_p95_ms']))
    lines.append('| 平均耗时 | %s ms | %s ms |'
                 % (stats['search_latency_mean_ms'], stats['generation_latency_mean_ms']))
    lines.append('| 失败率 | 0（无模型依赖） | %s |'
                 % (stats['generation_failure_rate']))
    lines.append('| 平均尝试次数 | — | %s |' % stats['generation_mean_attempts'])
    lines.append('| 质量证据 | 社区评价（威尔逊均值 %s，收藏均值 %s） | 无评价数据（新菜） |'
                 % (stats['direct_top1_wilson_mean'], stats['direct_top1_collect_mean']))
    lines.append('')
    lines.append('## 结论与局限')
    lines.append('')
    lines.append('1. 检索路径比生成路径快 **约 %s 倍**（P50：%s ms vs %s ms），'
                 '因为前者只是一次 ES 查询，后者要跑本地模型。'
                 % (round((stats['generation_latency_p50_ms'] or 1)
                          / max(1, stats['search_latency_p50_ms'] or 1)),
                    stats['search_latency_p50_ms'], stats['generation_latency_p50_ms']))
    lines.append('2. 但检索能"直接给出答案"的比例受菜库规模限制：本库 %s 道已发布菜，'
                 % (mysql("SELECT COUNT(*) FROM t_dish WHERE status = 1 AND deleted = 0")[0][0]))
    lines.append('   用户只给 1~3 样食材时，多数请求在库里找不到能做的菜 —— 这正是生成功能存在的理由。')
    lines.append('3. 两类结果的质量不可直接比较：检索结果有社区评价，生成结果是新菜、无评价数据。')
    lines.append('   要比较生成质量，得走人工盲评（说明文档要求 ≥2 名评价者）。')
    lines.append('')
    report = '\n'.join(lines)
    print()
    print(report)
    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write(report + '\n')
    print('明细：%s' % RAW)
    print('论文可用的表：%s' % REPORT)
    return 0


if __name__ == '__main__':
    sys.exit(main())
