"""端到端验证：把热量下限改成 0 后，纯低热量组合（生姜116 + 韭菜106）能否生成成功。

判定标准：
- 任务最终 status 进入终态，且拿到 dishId（说明没被 calories 下限拦死）
- 若失败，打印 error_msg，看是不是还是 calories 的锅
"""
import json, sys, time, urllib.error, urllib.request, random, string

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))
GW = 'http://127.0.0.1:7000'


def call(method, path, payload=None, token=None):
    req = urllib.request.Request(GW + path,
        data=json.dumps(payload).encode() if payload is not None else None, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:
        return None, {'err': repr(e)}


def wait_ready(token, timeout=120):
    print('等待 dish-service 就绪 ...', end='', flush=True)
    end = time.time() + timeout
    while time.time() < end:
        st, _ = call('GET', '/api/dish/ai/rank?type=hot', token=token)
        if st == 200:
            print(' ok'); return True
        time.sleep(2)
    print(' 超时'); return False


u = 'lowcal' + ''.join(random.choices(string.digits, k=5))
st, b = call('POST', '/api/account/auth/register', {'username': u, 'password': 'Test123456'})
print('注册 %s -> %s' % (u, st))
st, b = call('POST', '/api/account/auth/login', {'username': u, 'password': 'Test123456'})
if st != 200 or b.get('code') != 200:
    print('登录失败:', st, json.dumps(b, ensure_ascii=False)[:200]); sys.exit(1)
tok = b['data']['token']

if not wait_ready(tok):
    sys.exit(1)

print('\n提交生成：生姜(116) + 韭菜(106)，2 人份')
st, b = call('POST', '/api/dish/ai/dish',
             {'ingredientIds': [116, 106], 'freeIngredients': [], 'tastes': [], 'servings': 2}, tok)
if st != 200 or b.get('code') != 200:
    print('提交失败:', st, json.dumps(b, ensure_ascii=False)[:300]); sys.exit(1)
task_id = b['data']['taskId']
print('  taskId =', task_id)

final = None
for _ in range(90):  # 最多等 3 分钟
    time.sleep(2)
    st, b = call('GET', '/api/dish/ai/tasks', token=tok)
    for t in (b.get('data') or []):
        if t.get('taskId') == task_id:
            final = t
            if t.get('status') in (4, 5):  # 终态（4=成功发布? 5=失败? 直接打印看）
                break
    if final and final.get('status') in (4, 5):
        break

print('\n=== 任务最终状态 ===')
print(json.dumps(final, ensure_ascii=False, indent=2) if final else '  未取到任务对象')

if final and final.get('dishId'):
    dish_id = final['dishId']
    st, b = call('GET', '/api/dish/ai/dish/' + str(dish_id), token=tok)
    d = (b.get('data') or {})
    nutri = d.get('nutrition') or {}
    print('\n=== 生成的菜 ===')
    print('  菜名:', d.get('name'))
    print('  每份热量 calories =', nutri.get('calories'))
    print('  状态 status =', d.get('status'))
    if nutri.get('calories') is not None and nutri.get('calories') < 100:
        print('\n[PASS] 低热量菜（calories=%s < 100）已成功生成，下限=0 生效'
              % nutri.get('calories'))
    else:
        print('\n[INFO] 菜生成了，但 calories=%s（AI 仍给到了 100 以上，属正常）'
              % nutri.get('calories'))
elif final and final.get('errorMsg'):
    print('\n[FAIL] 任务失败，errorMsg =', final.get('errorMsg'))
else:
    print('\n[FAIL] 任务未进入终态或未拿到 dishId')
