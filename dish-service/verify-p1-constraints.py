# -*- coding: utf-8 -*-
"""
P1-3 生成约束的端到端验证：用户提的条件（必用/禁用食材、时长、设备）
到底有没有真的约束住 AI 生成的内容。

两个用例，一正一反：

  正例：约束可满足（忌口香菜、总耗时 ≤ 25 分钟、只用炒锅和锅铲）
        → 任务应当「成功」。成功本身就是证据：校验器拦不住的内容不可能成功，
          所以成功即意味着生成结果满足了全部约束。
  反例：约束自相矛盾（既要求必用牛肉、又忌口牛肉）
        → 任务必须「失败」，而且失败信息要能看出跟用户条件有关。
          这条是确定性断言，不依赖模型的临场发挥。

前置：gateway 7000 / account 8081 / dish 8082 / Nacos 8848 / ES 9200 /
      Ollama 11434 / MySQL 3306 / Redis 6379 都在跑（生成要走真实模型，一次约 20~40 秒）。

用法：
    python dish-service/verify-p1-constraints.py
"""
import json
import random
import string
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
TOMATO = 101
EGG = 102
BEEF = 104

STATUS_TEXT = {0: '排队中', 1: '生成中', 2: '成功', 3: '失败', 4: '已取消', 5: '已中断'}

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
    print('  %s %-46s got=%s%s' % ('PASS' if ok else 'FAIL', label, got,
                                   '' if ok else '  expect=%s' % (expect,)))


def check_true(label, cond, detail=''):
    check(label + ('（%s）' % detail if detail else ''), bool(cond), True)


def call(method, path, body=None, token=None, timeout=180):
    """生成任务要等模型，超时给足 180 秒"""
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
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw}
    except Exception as e:
        return 0, {'error': str(e)}


def rand(n=6):
    return ''.join(random.choices(string.ascii_lowercase + string.digits, k=n))


def login_new_user():
    username = 'p13user' + rand()
    password = 'pw' + rand(8)
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': 'P13'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def submit(token, payload):
    st, body = call('POST', '/api/dish/ai/dish', payload, token=token)
    data = body.get('data') or {}
    return st, body, data


def wait_terminal(token, task_id, timeout=420):
    """轮询「我的任务」直到该任务落到终态。

    超时给到 420 秒：单次生成实测几十秒到几分钟，而无解的约束会跑满重试预算
    （默认 2 次）。窗口开小了会把"还在生成"误判成"没失败"。
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        st, body = call('GET', '/api/dish/ai/tasks', token=token)
        for t in (body.get('data') or []):
            if str(t.get('taskId')) == str(task_id):
                last = t
                if t.get('status') in (2, 3, 4, 5):
                    return t
        time.sleep(3)
    return last


# ======================================================================
section('0. 准备：注册一个用户')
token = login_new_user()
check_true('用户 token 获取成功', token)
if not token:
    sys.exit(1)

# ======================================================================
section('1. 正例：可满足的约束应当生成成功')
st, body, task = submit(token, {
    'ingredientIds': [TOMATO, EGG],
    'tastes': ['家常'],
    'servings': 2,
    'mustUseIngredientIds': [TOMATO],
    'excludeIngredients': ['香菜'],
    'maxCookTimeMin': 25,
    'availableEquipment': ['炒锅', '锅铲'],
})
check('提交生成任务 -> 业务码 200', body.get('code'), 200)
print('  任务 %s 已入队，状态=%s' % (task.get('taskId'), STATUS_TEXT.get(task.get('status'))))

if body.get('code') == 200:
    done = wait_terminal(token, task.get('taskId'))
    status = (done or {}).get('status')
    print('  终态：%s（%s），菜名=%s，AI 耗时=%sms，排队=%sms' % (
        status, STATUS_TEXT.get(status, '?'), (done or {}).get('dishName'),
        (done or {}).get('costMs'), (done or {}).get('queueWaitMs')))
    if (done or {}).get('errorMsg'):
        print('  失败原因：%s' % done.get('errorMsg'))
    check('可满足的约束 -> 任务成功（成功即证明约束全部通过校验）', status, 2)

# ======================================================================
section('2. 反例：自相矛盾的约束必须失败，且说明与用户条件有关')
st, body, task = submit(token, {
    'ingredientIds': [TOMATO, BEEF],
    'tastes': [],
    'servings': 2,
    'mustUseIngredientIds': [BEEF],     # 必须用牛肉
    'excludeIngredientIds': [BEEF],     # 又忌口牛肉 —— 无解
})
if body.get('code') == 200:
    done = wait_terminal(token, task.get('taskId'))
    status = (done or {}).get('status')
    error = (done or {}).get('errorMsg') or ''
    print('  终态：%s（%s）' % (status, STATUS_TEXT.get(status, '?')))
    print('  失败原因：%s' % error)
    check('矛盾约束 -> 任务失败', status, 3)
    check_true('失败信息里点明了与用户条件有关的冲突',
               ('禁用' in error) or ('必用' in error), error[:60])
else:
    # 提交阶段就被拒才是**期望**结果：这类约束无解，让它进队列只会白跑几分钟
    msg = body.get('message') or ''
    print('  提交被直接拒绝：%s' % msg)
    check('矛盾约束 -> 被明确拒绝（不进队列）', body.get('code') != 200, True)
    # 注意：主数据里 ID 104 的正式名是「牛腩」（牛肉是 141），所以这里两种写法都认 ——
    # 顺带说明"按 ID 判定 + 按名字给出提示"这套是通的
    check_true('拒绝原因点明了冲突的食材', ('牛腩' in msg) or ('牛肉' in msg), msg[:60])

# ======================================================================
section('3. 约束进了提示词与签名（同一请求二次提交应当命中单飞）')
st1, body1, t1 = submit(token, {
    'ingredientIds': [TOMATO, EGG],
    'tastes': ['清淡'],
    'servings': 2,
    'excludeIngredients': ['香菜'],
})
st2, body2, t2 = submit(token, {
    'ingredientIds': [TOMATO, EGG],
    'tastes': ['清淡'],
    'servings': 2,
    'excludeIngredients': ['香菜'],
})
check('两次相同请求 -> 同一个任务（单飞）',
      str(t1.get('taskId')), str(t2.get('taskId')))

st3, body3, t3 = submit(token, {
    'ingredientIds': [TOMATO, EGG],
    'tastes': ['清淡'],
    'servings': 2,
    'excludeIngredients': ['香菜', '牛肉'],   # 约束不同
})
check_true('约束不同 -> 不是同一个任务（约束确实进了签名）',
           str(t3.get('taskId')) != str(t1.get('taskId')),
           '%s vs %s' % (t3.get('taskId'), t1.get('taskId')))

# 收尾：把演示用的任务都取消掉，别让它们占着队列
for tid in {str(t1.get('taskId')), str(t3.get('taskId'))}:
    call('POST', '/api/dish/ai/tasks/%s/cancel' % tid, token=token)

# ======================================================================
print()
print('=' * 72)
print('结果：PASS %d / FAIL %d' % (PASSED, FAILED))
if FAIL_NAMES:
    print('失败项：')
    for name in FAIL_NAMES:
        print('  - %s' % name)
print('=' * 72)
sys.exit(1 if FAILED else 0)
