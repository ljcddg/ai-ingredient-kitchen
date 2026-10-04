# -*- coding: utf-8 -*-
"""
P1-6 降级部分的端到端验证：检索引擎不可用时，生成入口要限流。

说明文档第四节：「ES 故障时向用户说明检索暂不可用，允许有限的数据库检索或
受配额控制的生成入口，避免检索故障把全部请求转移到本地模型」。

分两个阶段跑（中间由外部启停 ES 容器）：

    docker stop es
    python dish-service/verify-p1-degraded.py outage      # 检索挂着：允许少量生成，超出配额即拒
    docker start es
    python dish-service/verify-p1-degraded.py recovered   # 检索恢复：不再限流

## 怎么在不烧模型的前提下验证配额

配额按"该用户最近一小时的生成任务数"算。为了不真的跑满 5 次生成（每次几十秒到几分钟），
`outage` 阶段先真实提交 1 次（验证"降级不等于关闭"），再用 SQL 补 4 条历史任务把计数顶到配额，
然后验证第 6 次被拒。补的那 4 条是本脚本自己造的、带固定 ID 的占位记录，
在 `recovered` 阶段会删干净。
"""
import json
import os
import random
import string
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'
STATE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '.degraded-verify.json')

# 占位任务的固定 ID 段（一眼能看出是测试造的）。
# 注意不能超过 signed BIGINT 上限 9223372036854775807（≈9.22e18）——
# 用 9.99e18 会直接报 "Out of range value for column 'id'"。
FAKE_IDS = [999000000000000001, 999000000000000002, 999000000000000003, 999000000000000004]

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
    print('  %s %-50s got=%s%s' % ('PASS' if ok else 'FAIL', label, got,
                                   '' if ok else '  expect=%s' % (expect,)))


def check_true(label, cond, detail=''):
    check(label + ('（%s）' % detail if detail else ''), bool(cond), True)


def mysql(sql):
    """执行 SQL 并把错误**打出来**。

    早先这里把 stderr 丢进 DEVNULL，结果一次 "Out of range value for column 'id'"
    被静默吞掉，测试失败的表象是"数据没插进去"，白查了一轮。
    """
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
                detail = [line for line in f.read().splitlines() if 'password' not in line]
            print('  [SQL 执行失败] %s' % ' '.join(detail)[:300])
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
            raw = resp.read().decode('utf-8')
            return resp.status, (json.loads(raw) if raw.strip().startswith('{') else {'raw': raw})
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', errors='replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw}
    except Exception as e:
        return 0, {'error': str(e)}


def register_user():
    username = 'deg' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '降级验证'})
    token = login(username, password)
    user_id = mysql("SELECT id FROM t_user WHERE username = '%s'" % username)
    return token, (int(user_id[0][0]) if user_id else None), username, password


def login(username, password):
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def submit(token, ingredient_ids):
    st, body = call('POST', '/api/dish/ai/dish', {
        'ingredientIds': ingredient_ids, 'tastes': ['家常'], 'servings': 2}, token=token)
    return body, (body.get('data') or {}).get('taskId')


def cancel(token, task_id):
    if task_id:
        call('POST', '/api/dish/ai/tasks/%s/cancel' % task_id, token=token)


def task_count(user_id):
    rows = mysql("SELECT COUNT(*) FROM t_ai_generate_task WHERE user_id = %d "
                 "AND create_time >= NOW() - INTERVAL 1 HOUR" % user_id)
    return int(rows[0][0]) if rows else 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    phase = sys.argv[1]

    if phase == 'outage':
        section('阶段 1/2：检索引擎不可用时（降级）')
        token, user_id, username, password = register_user()
        check_true('注册并登录成功', token and user_id)
        if not token:
            return 1

        # ① 降级不等于关闭：第一次提交应当被接受
        body, task_id = submit(token, [101, 102])
        check('降级期间第 1 次提交被接受（允许有限生成）', body.get('code'), 200)
        cancel(token, task_id)
        check('该用户本小时任务数（真实提交后）', task_count(user_id), 1)

        # ② 用 SQL 把计数顶到配额（默认 5），避免真的跑满 5 次生成
        values = ','.join(
            "(%d, %d, MD5(%d), '{}', 2, 0, 0)" % (fid, user_id, fid) for fid in FAKE_IDS)
        mysql("INSERT INTO t_ai_generate_task "
              "(id, user_id, request_signature, request_json, status, cost_ms, queue_wait_ms) "
              "VALUES %s" % values)
        check('预置占位任务后，本小时任务数达到配额', task_count(user_id), 5)

        # ③ 第 6 次提交应当被拒（换一组食材，避免命中单飞复用旧任务）
        body, task_id = submit(token, [103, 104])
        message = body.get('message') or ''
        print('  拒绝原因：%s' % message)
        check_true('超出配额后提交被拒', body.get('code') != 200, body.get('code'))
        check_true('拒绝原因说明是限流', '限流' in message, message[:60])
        check_true('拒绝原因里有配额与已用次数', ('每小时 5 次' in message) and ('已用 5 次' in message))
        check('被拒的请求没有新建任务', task_count(user_id), 5)

        with open(STATE, 'w', encoding='utf-8') as f:
            json.dump({'user_id': user_id, 'username': username, 'password': password}, f)

    elif phase == 'recovered':
        section('阶段 2/2：检索引擎恢复后（不再限流）')
        if not os.path.exists(STATE):
            print('找不到上一阶段的状态文件，先跑 outage 阶段')
            return 1
        with open(STATE, encoding='utf-8') as f:
            state = json.load(f)
        user_id = state['user_id']

        # 关键：必须用**同一个用户**再提交。换个新账号是证明不了什么的 ——
        # 新账号本来就没有历史计数，检索挂着也不会被限流。
        token = login(state['username'], state['password'])
        check_true('用同一用户重新登录', token)
        check('该用户本小时历史任务数（仍达配额）', task_count(user_id), 5)

        body, task_id = submit(token, [105, 106])
        message = body.get('message') or ''
        print('  提交结果：code=%s message=%s' % (body.get('code'), message))
        check('检索恢复后同一用户提交被接受（不再限流）', body.get('code'), 200)
        cancel(token, task_id)

        mysql("DELETE FROM t_ai_generate_task WHERE id IN (%s)"
              % ','.join(str(i) for i in FAKE_IDS))
        left = mysql("SELECT COUNT(*) FROM t_ai_generate_task WHERE id IN (%s)"
                     % ','.join(str(i) for i in FAKE_IDS))
        check('清理占位任务', left[0][0], '0')
        os.remove(STATE)
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
