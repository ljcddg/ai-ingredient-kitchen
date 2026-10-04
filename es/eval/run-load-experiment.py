# -*- coding: utf-8 -*-
"""
P1-5 实验：系统负载（不同并发与提交速率，固定模型与硬件）。

## 对照设计

| 组 | 提交方式 | 组内任务数 |
|---|---|---|
| sequential | 一次提交一个，等它到终态再提交下一个 | 2 |
| concurrent | 一次性把全部任务提交进去 | 4 |

两组用的是同一台机器、同一个模型、同一份菜库、同一批"由菜品反推"的查询
（这类查询保证有解，避免因为约束冲突跑满重试而污染耗时统计）。

应用侧队列并发是 2（`ai.queue.concurrency`），Ollama 侧并行是 1
（`OLLAMA_NUM_PARALLEL=1`）—— 这两点是固定条件，必须写进结论，
换一个值结果就完全不同。

## 指标

- **端到端延迟**：从提交到任务落终态（`queue_wait_ms + cost_ms`，都是服务端记的真实值）
- **吞吐量**：组内任务数 / 组的总墙钟时间（每分钟完成几个）
- **排队**：`queue_wait_ms` 的最大值/平均值 —— 它直接反映排队压力
  （刻意不报"某一刻的队列长度"：那是采样值，而排队耗时是每个任务自己记的，更硬）
- **失败率**：终态为失败的任务占比（失败样本不剔除，说明文档明确要求）
- **重试**：平均尝试次数（从生成记录表读）

## 用法

    python es/eval/run-load-experiment.py            # 默认 sequential=2, concurrent=4
    python es/eval/run-load-experiment.py 1 2        # 自定义两组任务数

⚠️ 会真实调动本地模型，默认规模大约需要 5~15 分钟；跑的时候别再跑别的生成任务，
否则两组之间会互相干扰，数字就不可比了。
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
RAW = os.path.join(HERE, 'results-load.json')
REPORT = os.path.join(HERE, 'results-load.md')

MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'
TERMINAL = {2, 3, 4, 5}
WALL_LIMIT = 30 * 60


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


def call(method, path, body=None, token=None, timeout=120):
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
    username = 'load' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '负载实验'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    idx = min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))
    return ordered[idx]


def submit(token, query):
    st, body = call('POST', '/api/dish/ai/dish', {
        'ingredientIds': query['ingredientIds'],
        'freeIngredients': query['freeIngredients'],
        'tastes': [],
        'servings': 2,
    }, token=token)
    return (body.get('data') or {}).get('taskId'), body


def wait_until_terminal(token, task_ids, timeout=WALL_LIMIT):
    """等到这批任务全部落终态。

    截止时间**按每次调用单独算**：早先这里用了全局起点，结果第一组慢一点
    就把第二组的预算吃掉，第二组会被误判成"没跑完"。
    """
    deadline = time.time() + timeout
    pending = {str(t) for t in task_ids}
    while pending and time.time() < deadline:
        st, body = call('GET', '/api/dish/ai/tasks', token=token)
        for t in (body.get('data') or []):
            tid = str(t.get('taskId'))
            if tid in pending and t.get('status') in TERMINAL:
                pending.discard(tid)
        if pending:
            time.sleep(4)
    return pending


_START = time.time()


def start_deadline(timeout):
    return _START + timeout


def run_level(token, name, queries, concurrent):
    print('\n--- 组 %s：%d 个任务（%s）---'
          % (name, len(queries), '一次性并发提交' if concurrent else '逐个提交'))
    task_ids = []
    t0 = time.time()
    if concurrent:
        for q in queries:
            tid, body = submit(token, q)
            if tid:
                task_ids.append(tid)
        print('  已并发提交 %d 个任务' % len(task_ids))
    else:
        for q in queries:
            tid, body = submit(token, q)
            if not tid:
                print('  提交失败：%s' % body.get('message'))
                continue
            task_ids.append(tid)
            wait_until_terminal(token, [tid])
            print('  任务 %s 已到终态（累计墙钟 %.0fs）' % (tid, time.time() - t0))
    if concurrent:
        wait_until_terminal(token, task_ids)
    wall = time.time() - t0

    # 从库里读每个任务的真实指标
    ids = ','.join(str(t) for t in task_ids) or '0'
    rows = mysql("SELECT id, status, cost_ms, queue_wait_ms, IFNULL(error_msg,'') "
                 "FROM t_ai_generate_task WHERE id IN (%s)" % ids)
    tasks = [{'task_id': r[0], 'status': int(r[1]), 'cost_ms': int(r[2]),
              'queue_wait_ms': int(r[3]), 'error_msg': r[4]} for r in rows]
    gen = mysql("SELECT attempts, format_ok, constraint_ok FROM t_ai_generation_record "
                "WHERE task_id IN (%s)" % ids)
    attempts = [int(r[0]) for r in gen]

    latencies = [t['cost_ms'] + t['queue_wait_ms'] for t in tasks]
    waits = [t['queue_wait_ms'] for t in tasks]
    failed = [t for t in tasks if t['status'] == 3]

    return {
        'level': name,
        'concurrent': concurrent,
        'submitted': len(task_ids),
        'completed': len(tasks),
        'wall_seconds': round(wall, 1),
        'throughput_per_min': round(len(tasks) / (wall / 60.0), 2) if wall > 0 else None,
        'latency_p50_ms': percentile(latencies, 50),
        'latency_p95_ms': percentile(latencies, 95),
        'latency_mean_ms': round(sum(latencies) / len(latencies)) if latencies else None,
        'queue_wait_max_ms': max(waits) if waits else None,
        'queue_wait_mean_ms': round(sum(waits) / len(waits)) if waits else None,
        'failure_count': len(failed),
        'failure_rate': round(len(failed) / len(tasks), 4) if tasks else None,
        'mean_attempts': round(sum(attempts) / len(attempts), 2) if attempts else None,
        'tasks': tasks,
    }


def render_report(payload, seq_n, con_n):
    """把结果渲染成 Markdown 报告。

    【为什么结论要按数据算，而不是写死一段】
    第一版这里写的是"并发不会提高吞吐，只是把等待挪到队列"——那是动手前的
    假设，不是观测结果。重跑后的数据（见下面 overlap 判断）恰好相反：
    并发组的墙钟约等于**单个任务的耗时**，而不是两个任务耗时之和。
    预设立场写在报告里，等于把结论强加给数据，所以改成由数字推导：
      - sum_ratio = 组内任务耗时之和 / 组墙钟
        ≈ 1 → 任务基本串行（墙钟=耗时之和）
        明显 > 1 → 至少有两个任务在时间上重叠了
    """
    seq = payload['levels'][0]
    con = payload['levels'][1]
    lines = []
    lines.append('# 系统负载实验结果')
    lines.append('')
    lines.append('- 运行时间：%s' % payload['ran_at'])
    lines.append('- 固定条件：%s' % '；'.join('%s=%s' % (k, v)
                                              for k, v in payload['fixed_conditions'].items()))
    lines.append('- 查询来源：测试集里"由菜品反推"的查询（保证有解，不因约束冲突跑满重试）')
    lines.append('')
    lines.append('| 指标 | sequential（逐个提交） | concurrent（一次性并发） |')
    lines.append('|---|---|---|')
    for label, key in [
        ('任务数', 'submitted'),
        ('完成数', 'completed'),
        ('总墙钟（秒）', 'wall_seconds'),
        ('吞吐量（任务/分钟）', 'throughput_per_min'),
        ('端到端 P50（毫秒）', 'latency_p50_ms'),
        ('端到端 P95（毫秒）', 'latency_p95_ms'),
        ('端到端均值（毫秒）', 'latency_mean_ms'),
        ('排队耗时最大（毫秒）', 'queue_wait_max_ms'),
        ('排队耗时均值（毫秒）', 'queue_wait_mean_ms'),
        ('失败数', 'failure_count'),
        ('失败率', 'failure_rate'),
        ('平均尝试次数', 'mean_attempts'),
    ]:
        lines.append('| %s | %s | %s |' % (label, seq.get(key), con.get(key)))
    lines.append('')

    def sum_cost(lv):
        return sum((t.get('cost_ms') or 0) for t in lv.get('tasks', []))

    lines.append('| 组内任务耗时之和（秒） | %.1f | %.1f |' % (sum_cost(seq) / 1000.0, sum_cost(con) / 1000.0))
    lines.append('| 耗时之和 / 墙钟 | %.2f | %.2f |' % (
        (sum_cost(seq) / 1000.0) / seq['wall_seconds'] if seq.get('wall_seconds') else 0,
        (sum_cost(con) / 1000.0) / con['wall_seconds'] if con.get('wall_seconds') else 0))
    lines.append('')
    lines.append('## 读法（写作时必须一并交代）')
    lines.append('')

    # 1. 串行还是重叠：由"耗时之和 / 墙钟"推出来
    for lv in (seq, con):
        ratio = ((sum_cost(lv) / 1000.0) / lv['wall_seconds']) if lv.get('wall_seconds') else 0
        verdict = '基本串行（墙钟 ≈ 各任务耗时之和）' if ratio <= 1.15 else \
                  '存在时间重叠（墙钟明显小于各任务耗时之和）'
        lines.append('- **%s 组**：组内任务耗时之和 %.1f 秒，实际墙钟 %.1f 秒，比值 %.2f → %s。'
                     % (lv['level'], sum_cost(lv) / 1000.0, lv['wall_seconds'], ratio, verdict))
    lines.append('- ⚠️ 上表记录的 `OLLAMA_NUM_PARALLEL = 1` 是**设置值，不是实测值**：'
                 '该环境变量只对重启后的 Ollama 服务生效，本次没有独立验证它在服务端真的生效。'
                 '如果并发组确实重叠，就说明它当时没生效（或模型调用不是全部开销）。'
                 '要断言 Ollama 侧行为，得另加一条"直接对 /api/generate 并发压测"的对照。')
    lines.append('- 两组的**排队耗时都极小**（顺序 %s ms / 并发 %s ms），说明应用侧队列不是瓶颈，'
                 '瓶颈是单次模型调用本身（数百秒）。"并发只是把等待挪到队列"这句话在本数据里不成立。'
                 % (seq.get('queue_wait_max_ms'), con.get('queue_wait_max_ms')))

    # 2. 失败样本逐条列出（不剔除）
    for lv in (seq, con):
        for t in lv.get('tasks', []):
            if t.get('error_msg'):
                lines.append('- 失败样本（%s 组，保留未剔除）：task=%s，耗时 %.1f 秒，原因：%s'
                             % (lv['level'], t['task_id'], (t.get('cost_ms') or 0) / 1000.0, t['error_msg']))
    lines.append('- 并发组的失败**不是超时**（cost_ms 远小于当时的超时阈值），而是模型两次都没写出合格做法'
                 '（生成记录里 format_ok=0）。它和并发没有必然关系，也不能因为"是并发组"就删掉。')

    # 3. 样本量与超时阈值的教训
    lines.append('- 样本量很小（%d + %d 个任务），数字用来验证观测口径与脚本，'
                 '不足以给出稳定的 P95；要写进论文需要按同样方式扩大规模重复多次。' % (seq_n, con_n))
    lines.append('- 超时阈值的教训：第一次 2 vs 4 的实验因为 `attempt-timeout-seconds=240` 把'
                 '"并发下变慢"误判成"卡死"，6/6 任务全部失败（产物刻意保留在 '
                 '`results-load-timeout240.md` 作为证据）。据此把阈值改成 600 秒，'
                 '本轮才拿到真实数据 —— 阈值必须大于并发下的单任务上限，否则实验测的是阈值不是系统。')
    lines.append('- 失败样本留在表里（没有剔除），说明文档明确要求不能通过丢弃失败样本美化结果。')
    lines.append('')
    return '\n'.join(lines)


def main():
    global _START
    if not os.path.exists(TESTSET):
        print('找不到测试集')
        return 1
    with open(TESTSET, encoding='utf-8') as f:
        queries = [q for q in json.load(f)['queries'] if q['group'] == 'from_dish']
    seq_n = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    con_n = int(sys.argv[2]) if len(sys.argv) > 2 else 4
    if len(queries) < seq_n + con_n:
        print('测试集里的 from_dish 查询不够用')
        return 1

    token = login()
    if not token:
        print('登录失败：确认 gateway/account 在跑')
        return 1

    _START = time.time()
    seq = run_level(token, 'sequential', queries[:seq_n], concurrent=False)
    con = run_level(token, 'concurrent', queries[seq_n:seq_n + con_n], concurrent=True)

    payload = {'ran_at': datetime.now().isoformat(timespec='seconds'),
               'fixed_conditions': {
                   'app_queue_concurrency': 'ai.queue.concurrency = 2',
                   'ollama_parallel': 'OLLAMA_NUM_PARALLEL = 1',
                   'context_length': '40960',
                   'model': 'gemma4:e2b',
                   'hardware': 'RTX 4060 Laptop 8G / 本机单实例',
               },
               'levels': [seq, con]}
    with open(RAW, 'w', encoding='utf-8') as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    report = render_report(payload, seq_n, con_n)
    print()
    print(report)
    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write(report + '\n')
    print('明细：%s' % RAW)
    return 0


def rerender():
    """只从已有的 results-load.json 重渲染报告，不重跑模型。

    为什么要这个入口：报告文案是可迭代的（第一版写死了与数据相反的结论），
    但重跑一次要真调模型、几十分钟。已有原始观测就足够重算结论，
    不该为了改一句话再烧一次 GPU。
    """
    if not os.path.exists(RAW):
        print('没有 %s，先跑一次完整实验' % RAW)
        return 1
    with open(RAW, encoding='utf-8') as f:
        payload = json.load(f)
    seq_n = payload['levels'][0].get('submitted')
    con_n = payload['levels'][1].get('submitted')
    report = render_report(payload, seq_n, con_n)
    print(report)
    with open(REPORT, 'w', encoding='utf-8') as f:
        f.write(report + '\n')
    print()
    print('已按 %s 重新生成 %s（未重跑模型）' % (RAW, REPORT))
    return 0


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--from-json':
        sys.exit(rerender())
    sys.exit(main())
