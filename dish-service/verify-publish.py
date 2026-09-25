# -*- coding: utf-8 -*-
"""
发布流程 + ES 同步 + 榜单 的端到端验证。

要证明的核心只有一件事：
    **用户生成的菜，发布之后能被另一个用户搜到。**

这是「AI 做菜」这条链路真正闭环的标志。在此之前，生成结果只躺在创建者的
「我的菜品」里，召回接口一条都搜不到 —— 功能看起来是坏的。

覆盖的断言分四组：
  1. 自动发布：生成成功 → 自动进找菜库（不用用户手动点）
  2. 增量同步：发布后 ES 里立刻有；撤回后立刻没
  3. 跨用户可见：另一个人能用相同食材搜到它（这条最关键）
  4. 榜单：热门 / 高分 / 最新三个榜都能查，且新发布的菜出现在最新榜
  5. 反例：别人的菜发布不了、撤回不了

前置：gateway 7000 / account 8081 / dish 8082 / ES 9200 / Ollama 都在跑。

用法：
    python dish-service/verify-publish.py
"""
import json
import random
import string
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
# 本机有系统代理，不绕过的话连 127.0.0.1 也会被代理拦成 502
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'

PASSED = 0
FAILED = 0
FAIL_NAMES = []


def section(title):
    print()
    print('=' * 68)
    print(title)
    print('=' * 68)


def check(label, got, expect):
    global PASSED, FAILED
    ok = (got == expect)
    if ok:
        PASSED += 1
    else:
        FAILED += 1
        FAIL_NAMES.append(label)
    print('  %s %-36s got=%s%s' % ('PASS' if ok else 'FAIL', label, got,
                                   '' if ok else '  expect=%s' % (expect,)))


def call(method, path, payload=None, token=None, timeout=60, retries=3):
    """调网关。启动期连接被拒时重试几次，避免把'还没起来'误判成失败。"""
    last = None
    for _ in range(retries):
        req = urllib.request.Request(
            GW + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            method=method)
        req.add_header('Content-Type', 'application/json')
        if token:
            req.add_header('Authorization', 'Bearer ' + token)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode('utf-8'))
            except Exception:
                return e.code, {}
        except Exception as e:
            last = e
            time.sleep(1.5)
    return None, {'message': '网关不可达: %r' % last}


def register_login(prefix):
    u = prefix + ''.join(random.choices(string.digits, k=6))
    call('POST', '/api/account/auth/register',
         {'username': u, 'password': 'Test123456', 'nickname': prefix})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': u, 'password': 'Test123456'})
    return u, (body.get('data') or {}).get('token')


section('0. 准备两个账号')
user_a, token_a = register_login('pubA')
user_b, token_b = register_login('pubB')
check('账号 A 拿到 token', bool(token_a), True)
check('账号 B 拿到 token', bool(token_b), True)
print('  A=%s  B=%s' % (user_a, user_b))
if not token_a or not token_b:
    print('\n登录失败，后续无法进行')
    sys.exit(1)

# ======================================================================
# 等 dish-service 就绪。
#
# 【这里踩过两次，判据本身很值得说清楚】
#
# 1. 不能用"端口能连"来判断。Spring Boot 的 Tomcat 开始监听时，
#    Bean 还没初始化完、Nacos 也还没注册，请求打进去照样失败。
#
# 2. 更不能**不带令牌**去探。不带 Authorization 时，网关的 JwtAuthGlobalFilter
#    会先返回 401 —— 请求根本不会转发给 dish-service。
#    所以"能拿到 401"只能证明网关活着，完全证明不了后端服务活着。
#    我第一版就是这么写的，于是脚本每次都"认为已经就绪"然后立刻失败，
#    而失败现象（taskId=None）和真正的提交故障长得一模一样，白查了半天。
#
# 唯一可靠的判据：**带一个有效令牌**去请求 dish-service 的接口，
# 直到它不再返回 5xx（503 = 网关在、但后端还没注册上来）。
print('等待 dish-service 就绪 ... ', end='', flush=True)
ready = False
for _ in range(90):
    st, _b = call('GET', '/api/dish/ai/rank?type=hot&size=1', token=token_a, retries=1)
    if st is not None and st < 500:
        ready = True
        break
    time.sleep(1)
print('ok' if ready else '超时（仍在返回 5xx）')

# ======================================================================
section('1. A 生成一道菜（土豆 + 胡萝卜）')
# 刻意选这两样：ES 里现有的 9 条测试数据不含它们，
# 所以后面召回命中什么、没命中什么，结论都很干净。
st, body = call('POST', '/api/dish/ai/dish',
                {'ingredientIds': [105, 113], 'freeIngredients': [],
                 'tastes': ['家常'], 'servings': 2},
                token=token_a)
task_id = (body.get('data') or {}).get('taskId')
check('提交生成任务', bool(task_id), True)
print('  taskId=%s' % task_id)
if not task_id:
    sys.exit(1)

dish_id = None
deadline = time.time() + 240
while time.time() < deadline:
    time.sleep(1.5)
    st, body = call('GET', '/api/dish/ai/tasks', token=token_a)
    mine = next((t for t in (body.get('data') or []) if t.get('taskId') == task_id), None)
    if mine and mine.get('status') in (2, 3, 4, 5):
        dish_id = mine.get('dishId')
        status_text = mine.get('statusText')
        break
else:
    print('  生成超时')
    sys.exit(1)

check('生成成功', mine.get('status'), 2)
if mine.get('status') != 2:
    print('  生成失败：', mine.get('errorMsg'))
    sys.exit(1)
check('拿到 dishId', bool(dish_id), True)
print('  dishId=%s' % dish_id)

# ======================================================================
section('2. 自动发布：生成完就能被搜到（用户不用手动点）')
time.sleep(1.5)   # 等 ES 的 refresh_interval（索引里设的 1s）

st, body = call('GET', '/api/dish/ai/dish/%s' % dish_id, token=token_a)
detail = body.get('data') or {}
check('详情接口返回', st, 200)
check('状态已是「已发布」', detail.get('status'), 1)
check('有发布时间', bool(detail.get('publishTime')), True)
dish_name = detail.get('name')
print('  菜名：%s' % dish_name)

st, body = call('GET', '/api/dish/ai/dish/%s/publish-check' % dish_id, token=token_a)
check('发布校验接口可用', st, 200)
check('校验结果为通过', (body.get('data') or {}).get('pass'), True)

# ======================================================================
section('3. 跨用户可见：B 用相同食材能否搜到 A 的菜（最关键）')
time.sleep(1.0)
st, body = call('POST', '/api/dish/search',
                {'ingredientIds': [105, 113], 'tasteTags': None,
                 'servings': 2, 'page': 1, 'size': 20},
                token=token_b)
data = body.get('data') or {}
candidates = data.get('candidates') or []
best = data.get('best') or {}
hit_ids = [str(c.get('dishId')) for c in candidates]
if best.get('dishId'):
    hit_ids.append(str(best.get('dishId')))

check('召回接口可用', st, 200)
check('检索引擎正常', data.get('searchEngineAvailable'), True)
check('B 能搜到 A 的菜', str(dish_id) in hit_ids, True)
print('  B 搜到的菜：%s' % (hit_ids or '空'))

# ======================================================================
section('4. 撤回：从找菜库摘掉，索引里也不该再有')
st, body = call('POST', '/api/dish/ai/dish/%s/unpublish' % dish_id, token=token_a)
check('撤回成功', body.get('code'), 200)
check('  撤回有提示文案', bool(body.get('message')), True)

st, body = call('GET', '/api/dish/ai/dish/%s' % dish_id, token=token_a)
check('撤回后状态回到草稿', (body.get('data') or {}).get('status'), 0)

time.sleep(1.5)
st, body = call('POST', '/api/dish/search',
                {'ingredientIds': [105, 113], 'tasteTags': None,
                 'servings': 2, 'page': 1, 'size': 20},
                token=token_b)
candidates = (body.get('data') or {}).get('candidates') or []
best = (body.get('data') or {}).get('best') or {}
hit_ids = [str(c.get('dishId')) for c in candidates]
if best.get('dishId'):
    hit_ids.append(str(best.get('dishId')))
check('撤回后搜不到了', str(dish_id) not in hit_ids, True)
print('  撤后搜到的菜：%s' % (hit_ids or '空'))

# ======================================================================
section('5. 重新发布：还能再搜回来（撤回不是单向的）')
st, body = call('POST', '/api/dish/ai/dish/%s/publish' % dish_id, token=token_a)
check('手动发布成功', body.get('code'), 200)
time.sleep(1.5)
st, body = call('POST', '/api/dish/search',
                {'ingredientIds': [105, 113], 'tasteTags': None,
                 'servings': 2, 'page': 1, 'size': 20},
                token=token_b)
candidates = (body.get('data') or {}).get('candidates') or []
best = (body.get('data') or {}).get('best') or {}
hit_ids = [str(c.get('dishId')) for c in candidates]
if best.get('dishId'):
    hit_ids.append(str(best.get('dishId')))
check('重新发布后又能搜到', str(dish_id) in hit_ids, True)
print('  重新发布后搜到的菜：%s' % (hit_ids or '空'))

# 幂等：重复发布不该报错
st, body = call('POST', '/api/dish/ai/dish/%s/publish' % dish_id, token=token_a)
check('重复发布不报错（幂等）', body.get('code'), 200)

# ======================================================================
section('6. 榜单：三个榜都能查，新菜出现在最新榜')
for t in ['hot', 'top', 'latest']:
    st, body = call('GET', '/api/dish/ai/rank?type=%s&size=20' % t, token=token_b)
    d = body.get('data') or {}
    print('  %-7s -> 共 %s 条，标题「%s」' % (t, d.get('total'), d.get('title')))
    check('榜单 %s 可用' % t, st, 200)
    check('榜单 %s 有标题' % t, bool(d.get('title')), True)

st, body = call('GET', '/api/dish/ai/rank?type=latest&size=50', token=token_b)
ids = [str(i.get('id')) for i in ((body.get('data') or {}).get('items') or [])]
check('最新榜包含刚发布的菜', str(dish_id) in ids, True)
print('  最新榜共 %d 条' % len(ids))

# 榜单不能含草稿：撤回后它应该从榜单里消失
call('POST', '/api/dish/ai/dish/%s/unpublish' % dish_id, token=token_a)
st, body = call('GET', '/api/dish/ai/rank?type=latest&size=50', token=token_b)
ids = [str(i.get('id')) for i in ((body.get('data') or {}).get('items') or [])]
check('撤回后不在榜单里（无草稿泄漏）', str(dish_id) not in ids, True)
print('  撤回后最新榜共 %d 条' % len(ids))
# 恢复成已发布，留给手动查看
call('POST', '/api/dish/ai/dish/%s/publish' % dish_id, token=token_a)

# 非法的 type 要退回热门榜而不是报错
st, body = call('GET', '/api/dish/ai/rank?type=nonsense', token=token_b)
check('非法 type 退回热门榜', (body.get('data') or {}).get('type'), 'hot')

# ======================================================================
section('7. 反例：别人的菜不能发布 / 不能撤回')
# 再用 B 的身份对 A 的菜动手。预期一律被拒，且提示统一是"菜品不存在"
st, body = call('POST', '/api/dish/ai/dish/%s/unpublish' % dish_id, token=token_b)
check('B 不能撤回 A 的菜', body.get('code') != 200, True)
print('  提示：%s' % (body.get('message') or ''))

st, body = call('GET', '/api/dish/ai/dish/%s/publish-check' % dish_id, token=token_b)
check('B 不能查 A 的发布校验', body.get('code') != 200, True)
print('  提示：%s' % (body.get('message') or ''))

st, body = call('POST', '/api/dish/ai/dish/999999999/publish', token=token_a)
check('发布不存在的菜被拒', body.get('code') != 200, True)
print('  提示：%s' % (body.get('message') or ''))

# ======================================================================
section('8. 索引全量重建（运维接口）')
st, body = call('POST', '/api/dish/ai/es/reindex', token=token_a)
d = body.get('data') or {}
check('全量重建接口可用', st, 200)
check('重建后已发布条数 >= 1', (d.get('published') or 0) >= 1, True)
print('  重建结果：%s' % d)

time.sleep(1.0)
st, body = call('POST', '/api/dish/search',
                {'ingredientIds': [105, 113], 'tasteTags': None,
                 'servings': 2, 'page': 1, 'size': 20},
                token=token_b)
candidates = (body.get('data') or {}).get('candidates') or []
best = (body.get('data') or {}).get('best') or {}
hit_ids = [str(c.get('dishId')) for c in candidates]
if best.get('dishId'):
    hit_ids.append(str(best.get('dishId')))
check('重建后依然能搜到', str(dish_id) in hit_ids, True)
print('  重建后搜到的菜：%s' % (hit_ids or '空'))

# ======================================================================
print()
print('=' * 68)
print('汇总：%d PASS / %d FAIL' % (PASSED, FAILED))
print('=' * 68)
if FAIL_NAMES:
    print('失败项：')
    for n in FAIL_NAMES:
        print('  -', n)
print()
print('测试账号：%s / %s（密码都是 Test123456）' % (user_a, user_b))
print('菜品：%s  dishId=%s' % (dish_name, dish_id))
sys.exit(1 if FAILED else 0)
