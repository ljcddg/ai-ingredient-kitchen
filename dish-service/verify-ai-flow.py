"""AI 做菜链路的端到端实测。

流程：提交（含自由输入食材）→ 轮询队列状态 → 拿详情 → 校验落库结果。
跑之前需要：dish-service 在 8092、MySQL / Nacos / Ollama 在跑。
"""
import json
import socket
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8092'
USER_ID = 1001

PASS, FAIL = [], []


def ck(label, ok, detail=''):
    (PASS if ok else FAIL).append(label)
    print('  [%s] %-34s %s' % ('PASS' if ok else 'FAIL', label, detail))


def call(method, path, payload=None, user=USER_ID):
    data = None if payload is None else json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if user is not None:
        req.add_header('X-User-Id', str(user))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode('utf-8'))
        except Exception:
            return e.code, {}


def wait_port(port, timeout=120):
    for i in range(timeout * 2):
        time.sleep(0.5)
        s = socket.socket(); s.settimeout(0.3)
        try:
            s.connect(('127.0.0.1', port)); s.close()
            return '就绪（%.1f 秒）' % ((i + 1) * 0.5)
        except Exception:
            s.close()
    return '超时'


print('8092 :', wait_port(8092))
print()

# ---------------------------------------------------------------- 1. 提交
print('=== 1. 提交生成（番茄 + 鸡蛋 + 自由输入「松露」）===')
st, body = call('POST', '/ai/dish', {
    'ingredientIds': [101, 102],
    'freeIngredients': ['松露'],
    'tastes': ['家常'],
    'servings': 2,
})
print('  HTTP', st, json.dumps(body, ensure_ascii=False)[:300])
ck('提交返回 200', st == 200)
task = body.get('data') or {}
task_id = task.get('taskId')
ck('拿到 taskId', task_id is not None, 'taskId=%s' % task_id)
ck('初始状态是排队中(0)', task.get('status') == 0, 'status=%s' % task.get('status'))
print()

# ---------------------------------------------------------------- 2. 反例：越权取消
print('=== 2. 反例：用别人的身份取消这个任务 ===')
st, body = call('DELETE', '/ai/tasks/%s' % task_id, user=9999)
ck('越权取消被拒', st == 200 and body.get('code') != 200,
   'code=%s msg=%s' % (body.get('code'), body.get('message')))
print()

# ---------------------------------------------------------------- 3. 轮询
print('=== 3. 轮询任务状态（最多 90 秒）===')
final = None
for i in range(45):
    time.sleep(2)
    st, body = call('GET', '/ai/tasks')
    tasks = body.get('data') or []
    me = next((t for t in tasks if t['taskId'] == task_id), None)
    if me is None:
        print('  第 %2d 次：任务不在列表里？' % (i + 1))
        continue
    print('  第 %2d 次：status=%s(%s) position=%s' % (
        i + 1, me['status'], me['statusText'], me['position']))
    if me['status'] in (2, 3):
        final = me
        break

ck('任务进入终态', final is not None)
if final:
    ck('任务成功(2)', final['status'] == 2,
       'status=%s error=%s' % (final['status'], final.get('errorMsg')))
    ck('拿到了菜品 ID', final.get('dishId') is not None, 'dishId=%s' % final.get('dishId'))
    ck('记录了 AI 耗时', (final.get('costMs') or 0) > 0, 'costMs=%s' % final.get('costMs'))
    ck('任务不再可取消', final.get('cancellable') is False)
print()

# ---------------------------------------------------------------- 4. 详情
dish_id = (final or {}).get('dishId')
if dish_id:
    print('=== 4. 菜品详情 ===')
    st, body = call('GET', '/ai/dish/%s' % dish_id)
    d = body.get('data') or {}
    print('  菜名    :', d.get('name'))
    print('  简介    :', d.get('summary'))
    print('  时间    : 备菜 %s / 下锅 %s 分钟' % (d.get('prepTimeMin'), d.get('cookTimeMin')))
    print('  难度/辣度:', d.get('difficulty'), '/', d.get('spicyLevel'))
    print('  食材    :', [(i.get('name'), i.get('amountGram')) for i in (d.get('ingredients') or [])])
    print('  调料    :', d.get('seasonings'))
    print('  厨具    :', d.get('equipment'))
    print('  营养    :', d.get('nutrition'))
    print('  评分    :', d.get('rating'))
    print('  步骤 %d 条:' % len(d.get('steps') or []))
    for s in (d.get('steps') or [])[:3]:
        print('        %s. %s' % (s.get('no'), s.get('text')))
    print('  大厨建议:', d.get('chefTips'))
    print('  替代方案:', d.get('substitutes'))
    print('  评论数  :', len(d.get('comments') or []))

    ck('详情返回 200', st == 200)
    ck('菜名非空', bool(d.get('name')))
    ck('步骤齐全', len(d.get('steps') or []) >= 4)
    ck('食材齐全（含自由输入的松露）',
       len(d.get('ingredients') or []) >= 3,
       '食材=%s' % [i.get('name') for i in (d.get('ingredients') or [])])
    ck('营养带来源说明', bool((d.get('nutrition') or {}).get('source')),
       'source=%s' % (d.get('nutrition') or {}).get('source'))
    ck('大厨建议非空', len(d.get('chefTips') or []) >= 2)
    ck('状态是草稿(0)', d.get('status') == 0, 'status=%s' % d.get('status'))
    print()

    # ------------------------------------------------------------ 5. 反例：别人看不到草稿
    print('=== 5. 反例：换一个用户看这道草稿 ===')
    st, body = call('GET', '/ai/dish/%s' % dish_id, user=9999)
    ck('草稿对别人不可见', body.get('code') != 200,
       'code=%s msg=%s' % (body.get('code'), body.get('message')))
    print()

print('=== 6. 队列快照 ===')
st, body = call('GET', '/ai/queue')
print(' ', json.dumps(body.get('data') or {}, ensure_ascii=False)[:300])
print()

print('=' * 60)
print('汇总：PASS=%d  FAIL=%d' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：')
    for f in FAIL:
        print('   -', f)
