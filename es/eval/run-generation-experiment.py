# -*- coding: utf-8 -*-
"""
P1-5 生成质量实验：走真实模型的生成任务，指标从生成记录表里统计。

## 对照设计（说明文档第五节）

    直接生成 / 约束校验重试    ← 当前实现（约束校验 + 有限重试）
    加入参考内容生成           ← P2-1 的检索增强，作为选做组

## 指标与口径

- **格式通过率** = `format_ok=1` 的记录 / 有校验结果的记录
  （分母排除"连模型都没调通"那种：它没有校验结果，按未通过算会虚高失败率）
- **约束通过率** = `constraint_ok=1` / 有校验结果的记录；
  **约束违反率** = 1 − 约束通过率
- **生成失败率** = 终态为失败的记录 / 全部记录 —— 单独报告，
  不能用"丢弃失败样本"的方式把违反率算低（说明文档明确要求）
- **一次通过率** = `attempts=1` / 全部成功记录
- **平均尝试次数**、**平均耗时**、**P50/P95 耗时**（仅统计成功且耗时>0的）
- **发布率** = `review_status=1` / 成功记录（生成出来之后有没有进找菜库）

## 重要：实验条件必须固定（说明文档要求）

模型版本、提示词版本、温度、重试上限、菜库快照、硬件与预热状态都要固定并写进结论。
脚本会把记录里读到的 `model` / `prompt_version` 汇总打印出来 —— 它们不一致时，
这批结果就不能当成一个实验批次。

## 用法

    python es/eval/run-generation-experiment.py [每组重复次数] [最多查询数]
    # 默认：每组重复 1 次、取 3 组查询（小样本跑通流程）

说明文档建议同一输入重复 3 次、保存全部结果。那意味着
`3 组 × 3 次 × 单次 100~170 秒 ≈ 15~25 分钟`，正式跑的时候把参数调上去即可
（生成期间机器负载很高，建议先把 account/gateway 之外的服务停掉）。
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
REPORT = os.path.join(HERE, 'results-generation.md')
RAW = os.path.join(HERE, 'results-generation.json')

MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'

REPEATS = int(sys.argv[1]) if len(sys.argv) > 1 else 1
MAX_QUERIES = int(sys.argv[2]) if len(sys.argv) > 2 else 3
TERMINAL = {2, 3, 4, 5}


def call(method, path, body=None, token=None, timeout=120):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8', errors='replace') or '{}')
    except Exception as e:
        return 0, {'error': str(e)}


def mysql_query(sql):
    with tempfile.NamedTemporaryFile(delete=False, suffix='.tsv') as tmp:
        path = tmp.name
    try:
        with open(path, 'wb') as out:
            subprocess.run([MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                            '-D', DB, '-N', '-B', '-e', sql],
                           stdout=out, stderr=subprocess.DEVNULL, check=False)
        with open(path, encoding='utf-8', errors='replace') as f:
            return [line.split('\t') for line in f.read().splitlines() if line.strip()]
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def login():
    username = 'gen' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '生成实验'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def submit(token, query):
    st, body = call('POST', '/api/dish/ai/dish', {
        'ingredientIds': query['ingredientIds'],
        'freeIngredients': query['freeIngredients'],
        'tastes': [],
        'servings': 2,
    }, token=token)
    return (body.get('data') or {}).get('taskId'), body


def wait_terminal(token, task_ids, timeout=1800):
    """轮询直到这批任务全部落到终态"""
    pending = {str(t) for t in task_ids}
    done = {}
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        st, body = call('GET', '/api/dish/ai/tasks', token=token)
        for t in (body.get('data') or []):
            tid = str(t.get('taskId'))
            if tid in pending and t.get('status') in TERMINAL:
                done[tid] = t
                pending.discard(tid)
        if pending:
            time.sleep(5)
    return done, pending


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))
    return ordered[idx]


def main():
    if not os.path.exists(TESTSET):
        print('找不到测试集')
        return 1
    with open(TESTSET, encoding='utf-8') as f:
        queries = json.load(f)['queries']
    # 用"由菜品反推"的查询：它们保证有解，最接近真实使用
    picked = [q for q in queries if q['group'] == 'from_dish'][:MAX_QUERIES]
    if not picked:
        picked = queries[:MAX_QUERIES]

    token = login()
    if not token:
        print('登录失败：确认 gateway/account 服务在跑')
        return 1

    print('生成质量实验：%d 组查询 × 每组 %d 次 = %d 个任务'
          % (len(picked), REPEATS, len(picked) * REPEATS))
    task_ids = []
    for r in range(REPEATS):
        for q in picked:
            tid, body = submit(token, q)
            if body.get('code') == 200 and tid:
                task_ids.append(tid)
                print('  已提交 %s（第 %d 次）：taskId=%s' % (q['query_id'], r + 1, tid))
            else:
                print('  提交失败 %s：%s' % (q['query_id'], body.get('message')))

    print('等待 %d 个任务落到终态（生成很慢，请耐心）…' % len(task_ids))
    done, pending = wait_terminal(token, task_ids)
    if pending:
        print('  仍在进行中：%s' % ','.join(sorted(pending)))

    # ---------- 从生成记录表统计 ----------
    if not task_ids:
        print('没有成功提交的任务')
        return 1
    ids = ','.join(str(t) for t in task_ids)
    rows = mysql_query(
        "SELECT task_id, status, IFNULL(model,''), IFNULL(prompt_version,''), attempts, cost_ms,"
        " format_ok, constraint_ok, IFNULL(review_status,-1), IFNULL(temperature,0), "
        "LEFT(IFNULL(validation_errors,''),200) FROM t_ai_generation_record "
        "WHERE task_id IN (%s) ORDER BY task_id" % ids)

    records = []
    for r in rows:
        if len(r) < 11:
            continue
        records.append({
            'task_id': r[0], 'status': int(r[1]), 'model': r[2], 'prompt_version': r[3],
            'attempts': int(r[4]), 'cost_ms': int(r[5]), 'format_ok': int(r[6]),
            'constraint_ok': int(r[7]), 'review_status': int(r[8]), 'temperature': r[9],
            'validation_errors': r[10],
        })

    total = len(records)
    checked = [x for x in records if x['validation_errors'] != '' or x['format_ok'] or x['constraint_ok']]
    success = [x for x in records if x['status'] == 2]
    costs = [x['cost_ms'] for x in success if x['cost_ms'] > 0]
    models = sorted({x['model'] for x in records if x['model']})
    prompts = sorted({x['prompt_version'] for x in records if x['prompt_version']})

    def rate(num, den):
        return round(num / den, 4) if den else None

    stats = {
        'tasks_submitted': len(task_ids),
        'records': total,
        'success': len(success),
        'failed': len([x for x in records if x['status'] == 3]),
        'cancelled': len([x for x in records if x['status'] == 4]),
        'format_pass_rate': rate(sum(1 for x in checked if x['format_ok']), len(checked)),
        'constraint_pass_rate': rate(sum(1 for x in checked if x['constraint_ok']), len(checked)),
        'constraint_violation_rate': rate(sum(1 for x in checked if not x['constraint_ok']), len(checked)),
        'failure_rate': rate(len([x for x in records if x['status'] == 3]), total),
        'first_try_rate': rate(sum(1 for x in success if x['attempts'] == 1), len(success)),
        'mean_attempts': round(sum(x['attempts'] for x in records) / total, 3) if total else None,
        'mean_cost_ms': round(sum(costs) / len(costs)) if costs else None,
        'p50_cost_ms': percentile(costs, 50),
        'p95_cost_ms': percentile(costs, 95),
        'publish_rate': rate(sum(1 for x in success if x['review_status'] == 1), len(success)),
        'models': models,
        'prompt_versions': prompts,
    }

    payload = {'ran_at': datetime.now().isoformat(timespec='seconds'),
               'repeats': REPEATS, 'queries': [q['query_id'] for q in picked],
               'stats': stats, 'records': records}
    with open(RAW, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    lines = []
    lines.append('# 生成质量实验结果')
    lines.append('')
    lines.append('- 运行时间：%s' % payload['ran_at'])
    lines.append('- 规模：%d 组查询 × %d 次重复 = %d 个任务'
                 % (len(picked), REPEATS, len(picked) * REPEATS))
    lines.append('- 模型：%s' % (', '.join(models) or '—'))
    lines.append('- 提示词版本：%s' % (', '.join(prompts) or '—'))
    lines.append('- ⚠️ 固定实验条件：换模型/改提示词之后，这批结果不能与新批次混在一起比较')
    lines.append('')
    lines.append('| 指标 | 数值 |')
    lines.append('|---|---|')
    for label, key in [
        ('任务数 / 记录数', None),
        ('成功 / 失败 / 取消', None),
        ('格式通过率', 'format_pass_rate'),
        ('约束通过率', 'constraint_pass_rate'),
        ('约束违反率', 'constraint_violation_rate'),
        ('生成失败率（单独报告）', 'failure_rate'),
        ('一次通过率', 'first_try_rate'),
        ('平均尝试次数', 'mean_attempts'),
        ('平均耗时(ms)', 'mean_cost_ms'),
        ('P50 耗时(ms)', 'p50_cost_ms'),
        ('P95 耗时(ms)', 'p95_cost_ms'),
        ('发布率', 'publish_rate'),
    ]:
        if key is None:
            continue
        lines.append('| %s | %s |' % (label, stats.get(key)))
    lines.append('| 任务数 / 记录数 | %d / %d |' % (stats['tasks_submitted'], stats['records']))
    lines.append('| 成功 / 失败 / 取消 | %d / %d / %d |'
                 % (stats['success'], stats['failed'], stats['cancelled']))
    lines.append('')
    lines.append('样本量很小，指标只作流程验证；正式实验请把「重复次数」调到 3 并扩大查询数。')
    lines.append('')
    report = '\n'.join(lines)
    print()
    print(report)
    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write(report + '\n')
    print('明细：%s' % RAW)
    return 0


if __name__ == '__main__':
    sys.exit(main())
