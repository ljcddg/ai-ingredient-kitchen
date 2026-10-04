# -*- coding: utf-8 -*-
"""
P1-6 索引同步的端到端验证：正常路径 + ES 故障恢复 + 撤回后不会被旧任务写回。

分四个阶段跑（中间由外部脚本启停 ES 容器）：

    python dish-service/verify-p1-sync.py base      # ES 正常：撤回一道菜 → 待办应为"已完成"
    docker stop es
    python dish-service/verify-p1-sync.py outage    # ES 挂了：撤回另一道菜 → 待办应为"失败待重试"
    docker start es
    python dish-service/verify-p1-sync.py recover   # ES 恢复：手动补偿 → 两条待办都完成，且都从索引里删掉了
    python dish-service/verify-p1-sync.py restore   # 收尾：把两道菜恢复成发布状态并全量重建，不留脏数据

## 为什么这样设计

- **待办表是事实来源**：断言直接查 `t_es_sync_task`，而不是看日志里有没有 warn。
- **索引状态直接查 ES**（`GET /dish_index/_doc/{id}`）：不经过应用层，
  避免"应用说同步了、其实索引里没有"这种自证清白。
- **撤回 + 恢复这一对**验证的正是说明文档要求的
  "验证撤回后不会因旧任务重新进入索引"：ES 挂掉期间登记的那条待办，
  恢复后执行的结果必须是**删除**（因为菜已经撤回了），而不是把旧文档写回去。
- 用的是本项目自己生成的测试菜品（creator 用户名以 p13user/gen/eval 开头），
  跑完 restore 会恢复原状，不动真实数据。

前置：gateway 7000 / account 8081 / dish 8082 / MySQL / ES 9200 都在跑（ES 的阶段除外）。
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

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
ES = 'http://127.0.0.1:9200'
INDEX = 'dish_index'
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.sync-verify.json')
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'

STATUS_TEXT = {0: '待同步', 1: '已完成', 2: '失败待重试', 3: '已放弃'}

PASSED = 0
FAILED = 0
FAIL_NAMES = []


def section(title):
    print()
    print('=' * 72)
    print(title)
    print('=' * 72)


def check(label, got, expect):
    global PASSED, FAILED
    ok = (got == expect)
    if ok:
        PASSED += 1
    else:
        FAILED += 1
        FAIL_NAMES.append(label)
    print('  %s %-52s got=%s%s' % ('PASS' if ok else 'FAIL', label, got,
                                   '' if ok else '  expect=%s' % (expect,)))


def check_true(label, cond, detail=''):
    check(label + ('（%s）' % detail if detail else ''), bool(cond), True)


def mysql(sql):
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


def call(method, url, body=None, token=None, timeout=180):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode('utf-8')
            return resp.status, (json.loads(raw) if raw.strip().startswith(('{', '[')) else {'raw': raw})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', errors='replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw}
    except Exception as e:
        return 0, {'error': str(e)}


def login_admin():
    """自助建一个管理员：注册 → 提权（直连库）→ 登录"""
    username = 'syncadm' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', GW + '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '同步验证'})
    mysql("UPDATE t_user SET role = 1 WHERE username = '%s'" % username)
    st, body = call('POST', GW + '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def pick_dishes(n):
    """挑几道"我自己测试造出来"的已发布菜品，别动真实数据"""
    rows = mysql(
        "SELECT d.id FROM t_dish d JOIN t_user u ON u.id = d.creator_id "
        "WHERE d.status = 1 AND d.deleted = 0 "
        "AND (u.username LIKE 'p13user%%' OR u.username LIKE 'gen%%' "
        "     OR u.username LIKE 'eval%%' OR u.username LIKE 'syncadm%%') "
        "ORDER BY d.id DESC LIMIT %d" % n)
    return [int(r[0]) for r in rows if r]


def in_index(dish_id):
    """直接查 ES：这道菜在不在索引里（不经过应用层，免得自证清白）"""
    st, _ = call('GET', '%s/%s/_doc/%d' % (ES, INDEX, dish_id), timeout=15)
    return st == 200


def sync_task(dish_id):
    rows = mysql("SELECT status, attempts, last_error, next_retry_at, version "
                 "FROM t_es_sync_task WHERE dish_id = %d" % dish_id)
    if not rows:
        return None
    r = rows[0]
    return {'status': int(r[0]), 'attempts': int(r[1]),
            'last_error': r[2] if len(r) > 2 else '',
            'next_retry_at': r[3] if len(r) > 3 else '',
            'version': r[4] if len(r) > 4 else ''}


def wait_task(dish_id, want_status, timeout=30):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = sync_task(dish_id)
        if last and last['status'] == want_status:
            return last
        time.sleep(1)
    return last


def load_state():
    with open(STATE, encoding='utf-8') as f:
        return json.load(f)


def save_state(state):
    with open(STATE, 'w', encoding='utf-8') as f:
        json.dump(state, f, ensure_ascii=False, indent=2)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    phase = sys.argv[1]
    token = login_admin()
    if not token:
        print('管理员登录失败：确认 gateway/account 在跑')
        return 1

    if phase == 'base':
        section('阶段 1/4：ES 正常时的同步（正常路径）')
        # 能复用上次记录的两道菜就复用：重跑验证时不会每次都新撤回一道，
        # 免得测试跑多了在库里留下一堆未发布的测试菜
        dishes = []
        if os.path.exists(STATE):
            try:
                kept = [load_state()['a'], load_state()['b']]
                published = {int(r[0]) for r in mysql(
                    "SELECT id FROM t_dish WHERE status = 1 AND id IN (%d, %d)"
                    % (kept[0], kept[1]))}
                if len(published) == 2:
                    dishes = kept
                    print('  复用上次的两道测试菜品：%s' % dishes)
            except Exception:
                dishes = []
        if not dishes:
            dishes = pick_dishes(2)
        check('挑到 2 道测试菜品', len(dishes), 2)
        if len(dishes) < 2:
            return 1
        a, b = dishes
        print('  菜品 A=%d  B=%d' % (a, b))
        check_true('A 已在索引里（撤回前）', in_index(a))
        check_true('B 已在索引里（撤回前）', in_index(b))
        check('撤回前 A 没有待办记录', sync_task(a), None)

        st, body = call('POST', GW + '/api/dish/ai/admin/dish/%d/unpublish' % a, token=token)
        check('管理员撤回 A -> 业务码 200', body.get('code'), 200)

        task = wait_task(a, 1, timeout=30)
        check_true('A 的待办已生成并直接完成（ES 正常）', task and task['status'] == 1,
                   task and STATUS_TEXT.get(task['status']))
        check_true('A 已从索引删除', not in_index(a))
        check_true('尝试次数为 1（一次成功，没有重试）', task and task['attempts'] == 1,
                   task and task['attempts'])
        save_state({'a': a, 'b': b})
        print('  已记录菜品 A=%d B=%d 到 %s' % (a, b, os.path.basename(STATE)))

    elif phase == 'outage':
        section('阶段 2/4：ES 故障时的同步（待办必须留下且有退避）')
        state = load_state()
        b = state['b']
        check('待办表里 B 还没有记录', sync_task(b), None)

        st, body = call('POST', GW + '/api/dish/ai/admin/dish/%d/unpublish' % b, token=token)
        check('ES 挂着时撤回 B -> 业务码仍为 200（索引失败不影响业务）', body.get('code'), 200)

        task = wait_task(b, 2, timeout=60)
        check_true('B 的待办状态为「失败待重试」', task and task['status'] == 2,
                   task and STATUS_TEXT.get(task['status']))
        check_true('已记录失败次数', task and task['attempts'] >= 1, task and task['attempts'])
        check_true('记录了失败原因', task and (task['last_error'] or '').strip() != '',
                   (task and task['last_error'] or '')[:60])
        check_true('安排了下次重试时间（退避）', task and task['next_retry_at'] not in ('', 'NULL'),
                   task and task['next_retry_at'])
        check_true('业务数据已撤回（库里状态已是草稿）',
                   mysql("SELECT status FROM t_dish WHERE id = %d" % b)[0][0] == '0')

    elif phase == 'recover':
        section('阶段 3/4：ES 恢复后的手动补偿')
        state = load_state()
        a, b = state['a'], state['b']

        st, body = call('POST', GW + '/api/dish/ai/es/sync/retry', token=token)
        check('手动补偿 -> 业务码 200', body.get('code'), 200)
        data = body.get('data') or {}
        print('  重置条数 = %s，当前待办概览 = %s' % (data.get('reset'), data.get('status')))

        for dish_id, name in ((a, 'A'), (b, 'B')):
            task = wait_task(dish_id, 1, timeout=60)
            check_true('%s 的待办已变为「已完成」' % name, task and task['status'] == 1,
                       task and STATUS_TEXT.get(task['status']))
            check_true('%s 不在索引里（撤回生效，旧任务没有把它写回来）' % name,
                       not in_index(dish_id))

        rows = mysql("SELECT COUNT(*) FROM t_es_sync_task WHERE status IN (2, 3)")
        check('待办表里没有残留的失败/放弃记录', rows[0][0], '0')

    elif phase == 'restore':
        section('阶段 4/4：收尾（把测试菜品恢复成发布状态并重建索引）')
        state = load_state()
        a, b = state['a'], state['b']
        mysql("UPDATE t_dish SET status = 1, publish_time = NOW() WHERE id IN (%d, %d)" % (a, b))
        # 顺手清掉这两道菜的待办，好让 base 阶段可以反复重跑（否则"撤回前没有待办"会一直失败）
        mysql("DELETE FROM t_es_sync_task WHERE dish_id IN (%d, %d)" % (a, b))
        check_true('两道测试菜已恢复为发布状态',
                   all(r[0] == '1' for r in
                       mysql("SELECT status FROM t_dish WHERE id IN (%d, %d) ORDER BY id" % (a, b))))

        st, body = call('POST', GW + '/api/dish/ai/es/reindex', token=token, timeout=300)
        check('全量重建 -> 业务码 200', body.get('code'), 200)
        report = body.get('data') or {}
        print('  重建报告：%s' % report)
        check_true('A 重新出现在索引里', in_index(a))
        check_true('B 重新出现在索引里', in_index(b))
    else:
        print('未知阶段：%s' % phase)
        return 1

    print()
    print('=' * 72)
    print('阶段 %s：PASS %d / FAIL %d' % (phase, PASSED, FAILED))
    for name in FAIL_NAMES:
        print('  - 失败项：%s' % name)
    print('=' * 72)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
