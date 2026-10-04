# -*- coding: utf-8 -*-
"""「手写菜谱」重名拦截回归探针（纯接口，不经过浏览器；跑一次约 5 秒）。

它盯的是这条规则：`t_dish` 的唯一键 `uk_name_creator(name_key, creator_id)` ——
同一创作者不能有两道同名菜（草稿也算）。所以第二道同名菜必须在**落库前**被拦下，
并给出人话提示，而不是让 `DuplicateKeyException` 落进兜底处理器变成「服务器开小差了」。

断言：
  1) 第一次提交 -> preview.ready=true，create.published=true，拿到 dishId
  2) 同名再 preview -> ready=false，problems 里有「换个名字」（面板据此提前禁用上传按钮）
  3) 同名再 create  -> HTTP 200，message 是人话（不是「服务器开小差了」），data 为 null
  4) 库里该账号仍只有一道菜，且是 status=1 / source=3（用户自定义）
清理：探针自己建的菜 / 明细 / 同步待办 / ES 文档 / 账号。

用法（前端 5500 与后端 8082 都要活着）：
    D:\\pycharm\\python\\python3.12.10\\python.exe dish-service\\verify-manual-dish.py
"""
import json
import random
import string
import subprocess
import sys
import urllib.error
import urllib.request

GATEWAY = 'http://127.0.0.1:7000'
ES = 'http://127.0.0.1:9200'
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

PASS = FAIL = 0


def check(name, got, expected):
    global PASS, FAIL
    if got == expected:
        PASS += 1
        print('  [PASS] %s' % name)
    else:
        FAIL += 1
        print('  [FAIL] %s 期望=%r 实际=%r' % (name, expected, got))


def check_true(name, cond, hint=''):
    global PASS, FAIL
    if cond:
        PASS += 1
        print('  [PASS] %s' % name)
    else:
        FAIL += 1
        print('  [FAIL] %s  %s' % (name, hint))


def api(method, path, body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8'))


def mysql(sql):
    out = subprocess.run([MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                          'project0914', '-N', '-B', '-e', sql],
                         capture_output=True, text=True, encoding='utf-8', errors='replace')
    if out.returncode != 0:
        print('  [MySQL 失败] %s' % (out.stderr or '').strip()[:300])
        return []
    return [ln for ln in out.stdout.splitlines() if ln.strip()]


def main():
    uname = 'dupfx' + ''.join(random.choices(string.digits, k=6))
    dish_name = '重名回归' + ''.join(random.choices(string.digits, k=6))
    st, rb = api('POST', '/api/account/auth/register',
                 {'username': uname, 'password': 'Test123456', 'nickname': '重名探针'})
    check('账号注册', (rb or {}).get('code'), 200)
    st, lb = api('POST', '/api/account/auth/login', {'username': uname, 'password': 'Test123456'})
    token = ((lb or {}).get('data') or {}).get('token')
    check_true('拿到 token', bool(token), 'resp=%s' % json.dumps(lb, ensure_ascii=False)[:200])
    if not token:
        return 1

    payload = {
        'name': dish_name,
        'servings': 2,
        'ingredients': [{'name': '番茄', 'ingredientId': 101, 'grams': 200},
                        {'name': '鸡蛋', 'ingredientId': 102, 'grams': 100}],
        'steps': ['番茄切块备用', '鸡蛋打散炒成块', '下番茄炒出沙后合炒调味'],
    }

    print('\n--- 1) 第一次 preview / create ---')
    st, pb = api('POST', '/api/dish/ai/dish/manual/preview', payload, token)
    pre = (pb or {}).get('data') or {}
    check('首次 preview ready', pre.get('ready'), True)
    st, cb = api('POST', '/api/dish/ai/dish/manual', payload, token)
    created = (cb or {}).get('data') or {}
    dish_id = created.get('dishId')
    check('首次 create published', created.get('published'), True)
    check_true('首次 create 拿到 dishId', bool(dish_id), 'resp=%s' % json.dumps(cb, ensure_ascii=False)[:300])

    print('\n--- 2) 同名 preview（面板提前提示） ---')
    st, pb2 = api('POST', '/api/dish/ai/dish/manual/preview', payload, token)
    pre2 = (pb2 or {}).get('data') or {}
    probs = pre2.get('problems') or []
    print('  problems=%s' % json.dumps(probs, ensure_ascii=False))
    check('同名 preview ready=False', pre2.get('ready'), False)
    check_true('同名 preview 提示换个名字', any('换个名字' in p for p in probs), 'problems=%r' % probs)

    print('\n--- 3) 同名 create（服务端兜底话术） ---')
    st, cb2 = api('POST', '/api/dish/ai/dish/manual', payload, token)
    msg = (cb2 or {}).get('message') or ''
    print('  http=%s code=%s message=%s' % (st, (cb2 or {}).get('code'), msg))
    check('同名 create HTTP 200', st, 200)
    check_true('不再回「服务器开小差了」', '服务器开小差' not in msg, 'message=%r' % msg)
    check_true('给了能照着做的原因', ('换个名字' in msg) or ('同名' in msg), 'message=%r' % msg)
    check('同名没有落第二条菜', (cb2 or {}).get('data'), None)

    print('\n--- 4) 库里状态 ---')
    n = mysql("SELECT COUNT(*) FROM t_dish WHERE creator_id = "
              "(SELECT id FROM t_user WHERE username = '%s');" % uname)
    check('这个账号库里恰好一道菜', n[0] if n else '?', '1')
    if dish_id:
        row = mysql("SELECT status, source, nutrition_source, CAST(energy_kcal AS CHAR) "
                    "FROM t_dish WHERE id = %s;" % dish_id)
        print('  t_dish: %s' % (row[0] if row else '?'))
        check('那一道是已发布(1) / 自定义(3)', (row[0].split('\t')[:2] if row else []), ['1', '3'])

    print('\n--- 清理 ---')
    if dish_id:
        mysql("DELETE FROM t_dish_ingredient WHERE dish_id = %s;" % dish_id)
        mysql("DELETE FROM t_es_sync_task WHERE dish_id = %s;" % dish_id)
        mysql("DELETE FROM t_dish WHERE id = %s;" % dish_id)
        req = urllib.request.Request('%s/dish_index/_doc/%s' % (ES, dish_id), method='DELETE')
        try:
            urllib.request.urlopen(req, timeout=10).read()
        except Exception as e:
            print('  [注意] ES 删除未成功（ES 没跑也不影响）：%s' % e)
    mysql("DELETE FROM t_user WHERE username = '%s';" % uname)
    left = mysql("SELECT COUNT(*) FROM t_dish WHERE creator_id = "
                 "(SELECT id FROM t_user WHERE username = '%s');" % uname)
    check('探针数据已清干净', left[0] if left else '?', '0')

    print('\n==== PASS %d / FAIL %d ====' % (PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
