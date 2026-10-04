# -*- coding: utf-8 -*-
"""
P0-1 权限 与 P1-1/P1-2 召回语义 的端到端验证。

覆盖三件事：

  1. 权限（说明文档第四节）：索引重建 / 食材审核这类运维接口，
     普通用户必须被挡在 403 —— 这条本身就是论文里"接口权限测试"的记录。
  2. 新召回语义（说明文档第二节）：
       · 返回结果带上主料满足率、缺料数与缺失主料名；
       · 严格模式（只用现有食材）下，缺料数必须恒为 0；
       · 宽松模式允许缺料，且结果不会比严格模式更少。
  3. 归一化（说明文档第二节）：自由输入「西红柿」应与点选「番茄」等价。

前置：gateway 7000 / account 8081 / dish 8082 / Nacos 8848 / ES 9200 / MySQL 3306 / Redis 6379 都在跑。
管理员 token 从环境变量 ADMIN_TOKEN 读入 —— 脚本不硬编码管理员账号，
需要你先把某个账号的 t_user.role 置 1：
    UPDATE t_user SET role = 1 WHERE username = 'xxx';
然后登录拿 token。

用法：
    python dish-service/verify-p1-search.py
"""
import json
import os
import random
import string
import sys
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
# 本机有系统代理，不绕过的话连 127.0.0.1 也会被代理拦成 502
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
TOMATO = 101          # 主数据 ID：番茄（别名里有"西红柿"）
EGG = 102             # 主数据 ID：鸡蛋

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
    print('  %s %-44s got=%s%s' % ('PASS' if ok else 'FAIL', label, got,
                                   '' if ok else '  expect=%s' % (expect,)))


def check_true(label, cond, detail=''):
    check(label + ('（%s）' % detail if detail else ''), bool(cond), True)


def call(method, path, body=None, token=None):
    """返回 (http状态码, 响应体 dict)

    超时给到 180 秒是有原因的：全量重建索引会逐条把菜同步进 ES，
    实测 30 道菜要 ~30 秒（每条约 1 秒）。超时设小了会误判成"接口挂了" ——
    服务端明明成功了，客户端却先放弃，这种现象最容易误导排查。
    """
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
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


def register_and_login():
    username = 'p1user' + rand()
    password = 'pw' + rand(8)
    st, body = call('POST', '/api/account/auth/register',
                    {'username': username, 'password': password, 'nickname': 'P1验证'})
    if body.get('code') != 200:
        print('  注册失败：%s' % body)
        return None
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    data = body.get('data') or {}
    print('  普通用户 %s 登录成功，role=%s' % (username, data.get('role')))
    return data.get('token')


def search(body, token):
    st, resp = call('POST', '/api/dish/search', body, token=token)
    return resp.get('data') or {}


# ======================================================================
section('0. 准备：注册一个普通用户用于权限测试')
user_token = register_and_login()
check_true('普通用户 token 获取成功', user_token)
admin_token = os.environ.get('ADMIN_TOKEN')
if not admin_token:
    print('  ！未设置 ADMIN_TOKEN，管理员相关断言会被跳过')
if not user_token:
    print('无法继续，退出')
    sys.exit(1)

# ======================================================================
section('1. 权限：运维接口必须把普通用户挡在 403')
st, body = call('POST', '/api/dish/ai/es/reindex', token=user_token)
check('普通用户调索引重建 -> HTTP 403', st, 403)
check('普通用户调索引重建 -> 业务码 403', body.get('code'), 403)
print('  提示：%s' % (body.get('message') or ''))

st, body = call('GET', '/api/dish/ai/ingredients/candidates?limit=5', token=user_token)
check('普通用户看待审食材清单 -> 403', body.get('code'), 403)

st, body = call('POST', '/api/dish/ai/ingredients/candidates/approve',
                {'ids': [1], 'reason': '越权尝试'}, token=user_token)
check('普通用户调审核通过 -> 403', body.get('code'), 403)

st, body = call('POST', '/api/dish/ai/es/reindex')
check('不带 token 调运维接口 -> 401（网关拦下）', st, 401)

if admin_token:
    st, body = call('POST', '/api/dish/ai/es/reindex', token=admin_token)
    check('管理员调索引重建 -> 业务码 200', body.get('code'), 200)
    d = body.get('data') or {}
    print('  重建结果：%s' % d)
    check_true('重建后已发布菜品 >= 1', (d.get('published') or 0) >= 1)

    st, body = call('GET', '/api/dish/ai/ingredients/candidates?limit=5', token=admin_token)
    check('管理员看待审食材清单 -> 200', body.get('code'), 200)
    print('  待审候选 %d 条' % len(body.get('data') or []))

# ======================================================================
section('2. 召回：严格模式不得返回缺主料的菜')
strict = search({'ingredientIds': [TOMATO, EGG], 'mode': 'strict', 'page': 1, 'size': 20},
                user_token)
strict_items = strict.get('candidates') or []
print('  严格模式命中 %d 条（total=%s），直出=%s' % (len(strict_items), strict.get('total'),
                                                  strict.get('directReturn')))
if strict.get('missReason'):
    print('  未直出原因：%s' % strict['missReason'])

check_true('严格模式结果里没有缺料项',
           all((it.get('missingCount') or 0) == 0 for it in strict_items),
           '最大缺料 %s' % max([it.get('missingCount') or 0 for it in strict_items] or [0]))

if strict_items:
    first = strict_items[0]
    for field in ('satisfactionRate', 'utilizationRate', 'missingCount',
                  'matchedIngredients', 'missingIngredients', 'reason'):
        check_true('返回项包含字段 %s' % field, field in first)
    check_true('严格模式满足率为 1', first.get('satisfactionRate') == 1.0,
               'satisfactionRate=%s' % first.get('satisfactionRate'))
    print('  首条：「%s」满足率=%.2f 利用率=%.2f 主料=%s' % (
        first.get('name'), first.get('satisfactionRate') or 0,
        first.get('utilizationRate') or 0, first.get('matchedIngredients')))
    print('  推荐理由：%s' % first.get('reason'))

# ======================================================================
section('3. 召回：宽松模式允许补料，且结果不会更少')
loose = search({'ingredientIds': [TOMATO, EGG], 'mode': 'loose', 'maxMissing': 2,
                'page': 1, 'size': 20}, user_token)
loose_items = loose.get('candidates') or []
print('  宽松模式命中 %d 条（严格模式 %d 条）' % (len(loose_items), len(strict_items)))

check_true('宽松模式结果不少于严格模式', len(loose_items) >= len(strict_items),
           '%d >= %d' % (len(loose_items), len(strict_items)))
check_true('宽松模式里出现过缺料项（否则说明没放宽）',
           any((it.get('missingCount') or 0) > 0 for it in loose_items) or not loose_items)
check_true('严格模式的结果是宽松模式的子集',
           {it.get('dishId') for it in strict_items} <= {it.get('dishId') for it in loose_items})

incomplete = [it for it in loose_items if (it.get('missingCount') or 0) > 0]
if incomplete:
    it = incomplete[0]
    print('  缺料示例：「%s」缺 %d 样：%s' % (it.get('name'), it.get('missingCount'),
                                            it.get('missingIngredients')))
    check_true('缺料菜不会直出', not loose.get('directReturn') or
               (loose.get('best') or {}).get('missingCount', 0) == 0)

# ======================================================================
section('4. 归一化：自由输入「西红柿」应与点选「番茄」等价')
# 用宽松模式对比：严格模式下这个组合很可能一条都不剩，
# 那样"两边都是空集"也能通过，等于没验证 —— 断言要落在非空集合上才有意义。
alias = search({'freeIngredients': ['西红柿'], 'mode': 'loose', 'maxMissing': 3,
                'page': 1, 'size': 20}, user_token)
by_id = search({'ingredientIds': [TOMATO], 'mode': 'loose', 'maxMissing': 3,
                'page': 1, 'size': 20}, user_token)
alias_items = alias.get('candidates') or []
id_items = by_id.get('candidates') or []

check('别名输入的食材计数为 1（未重复计数）',
      alias.get('requestedIngredientCount'), 1)
check('点选番茄的食材计数为 1', by_id.get('requestedIngredientCount'), 1)
check_true('对比用样例非空（否则下面的集合比较没有意义）', len(alias_items) > 0,
           '%d 条' % len(alias_items))
check_true('两种输入的候选集合一致',
           sorted(it.get('dishId') for it in alias_items)
           == sorted(it.get('dishId') for it in id_items),
           '别名 %d 条 / ID %d 条' % (len(alias_items), len(id_items)))

# ======================================================================
section('5. 硬约束：禁用食材必须整条排除')
before = search({'ingredientIds': [TOMATO, EGG], 'mode': 'loose', 'maxMissing': 2,
                 'page': 1, 'size': 20}, user_token)
after = search({'ingredientIds': [TOMATO, EGG], 'mode': 'loose', 'maxMissing': 2,
                'excludeIngredients': ['番茄'], 'page': 1, 'size': 20}, user_token)
before_items = before.get('candidates') or []
after_items = after.get('candidates') or []
print('  禁用番茄前 %d 条 → 后 %d 条' % (len(before_items), len(after_items)))

check_true('禁用后结果不增加', len(after_items) <= len(before_items))
leaked = [it for it in after_items
          if '番茄' in (it.get('matchedIngredients') or [])
          or '番茄' in (it.get('missingIngredients') or [])]
check_true('禁用后的结果里不再出现番茄', not leaked,
           '泄漏 %d 条' % len(leaked))

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
