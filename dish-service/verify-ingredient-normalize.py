# -*- coding: utf-8 -*-
"""
食材归一化补漏（P1-1 收尾）的端到端验证：真实生成一次，看落库明细拿没拿到主数据 ID。

背景：落库时 `DishDraftWriter.resolveIngredientId()` 只在**用户勾选的列表**里按名字找 ID，
于是 AI 自己补的食材、以及用户在输入框自由敲的食材，哪怕主数据里明明有，
也只能以 ingredient_id = NULL 存下来 —— 名字还能被搜到，但
① 进不了按 ID 的精确召回；② 营养计算查不到成分表，整道菜被标成「AI 估算」；
③ 还要被误记进候选表（等管理员转正）。修法是回退到 `IngredientDirectory.resolveId()`。

本脚本做三件事：

1. **基线**：用 SQL 独立判定"名字其实命中主数据、ID 却是空"的行有多少（这是缺口造成的历史欠账）；
2. 提交一次真实生成（只给自由输入的食材，不给列表食材），等它跑完；
3. **复查新菜**：新菜的明细里"本该有 ID 却是空"的必须为 0 —— 这条判定完全由 SQL 做，
   不调用应用的归一化代码，避免"自己验自己"。

用法（需要 gateway/account/dish 在跑 + MySQL + Ollama）：
    python dish-service/verify-ingredient-normalize.py
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
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'

# 判定「这个名字其实主数据里有」：正式名相同，或在别名列表里（别名用逗号分隔）
SHOULD_HAVE_ID = (
    "di.ingredient_id IS NULL AND d.deleted = 0 AND ("
    "EXISTS (SELECT 1 FROM t_ingredient i WHERE i.deleted = 0 AND i.name = di.ingredient_name)"
    " OR EXISTS (SELECT 1 FROM t_ingredient i WHERE i.deleted = 0 AND i.alias IS NOT NULL"
    " AND FIND_IN_SET(di.ingredient_name, REPLACE(i.alias, '，', ','))))"
)

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
    """查库。失败必须把 mysql 的原话打出来 —— 静默吞掉 stderr 会让"查不到"
    和"SQL 报错"长得一模一样（这个坑之前踩过一次）。"""
    with tempfile.NamedTemporaryFile(delete=False, suffix='.tsv') as tmp:
        path = tmp.name
    try:
        with open(path, 'wb') as out:
            proc = subprocess.run([MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                                   '-D', DB, '-N', '-B', '-e', sql],
                                  stdout=out, stderr=subprocess.PIPE, check=False)
        if proc.returncode != 0:
            print('  [注意] mysql 退出码 %d：%s'
                  % (proc.returncode, proc.stderr.decode('utf-8', 'replace').strip()))
        with open(path, encoding='utf-8', errors='replace') as f:
            return [line.split('\t') for line in f.read().splitlines() if line.strip()]
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def call(method, path, body=None, token=None, timeout=300):
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


def register_login():
    username = 'norm' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '归一化验证'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def overview(where=''):
    rows = mysql("SELECT COUNT(*) FROM t_dish_ingredient di JOIN t_dish d ON d.id = di.dish_id "
                 "WHERE " + SHOULD_HAVE_ID + where)
    return int(rows[0][0]) if rows else -1


def main():
    section('1. 基线：名字命中主数据、ID 却是空的行（归一化缺口的历史欠账）')
    total = int(mysql("SELECT COUNT(*) FROM t_dish_ingredient")[0][0])
    nulls = int(mysql("SELECT COUNT(*) FROM t_dish_ingredient WHERE ingredient_id IS NULL")[0][0])
    debt = overview()
    print('  明细共 %s 行，其中 ingredient_id 为空 %s 行；'
          '其中「主数据里其实有」的 %s 行' % (total, nulls, debt))
    examples = mysql("SELECT di.ingredient_name, COUNT(*) FROM t_dish_ingredient di "
                     "JOIN t_dish d ON d.id = di.dish_id WHERE " + SHOULD_HAVE_ID +
                     " GROUP BY di.ingredient_name ORDER BY 2 DESC, 1")
    for name, c in examples[:10]:
        print('    %-12s %s 行' % (name, c))
    print('  （这些行是修复前落库的，不会自动追补；下面的新菜必须为 0）')

    token = register_login()
    if not token:
        print('登录失败：确认 gateway/account 在跑')
        return 1

    section('2. 提交一次真实生成（只给自由输入食材，不给列表食材）')
    body = {
        'ingredientIds': [],
        'freeIngredients': ['西红柿', '鸡蛋'],
        'tastes': ['家常'],
        'servings': 2,
        'mustUseIngredientIds': [],
        'excludeIngredients': [],
        'maxCookTimeMin': 40,
        'availableEquipment': ['炒锅'],
    }
    st, resp = call('POST', '/api/dish/ai/dish', body, token=token, timeout=60)
    check('提交接口 -> 业务码 200', resp.get('code'), 200)
    task = resp.get('data') or {}
    task_id = task.get('taskId')
    check_true('拿到任务 ID', task_id is not None, task_id)

    deadline = time.time() + 900
    status = None
    tasks = []
    while time.time() < deadline:
        time.sleep(15)
        st, resp = call('GET', '/api/dish/ai/tasks', token=token, timeout=60)
        tasks = resp.get('data') or []
        mine = [t for t in tasks if str(t.get('taskId')) == str(task_id)]
        if mine:
            status = mine[0].get('status')
            print('    任务状态=%s（已等 %ds）' % (status, int(900 - (deadline - time.time()))))
            if status in (2, 3, 4, 5):  # 2 成功 / 3 失败 / 4 取消 / 5 中断
                break
    # 终态：2 成功 / 3 失败 / 4 取消 / 5 中断。这里要判"是不是终态"，
    # 不是"等不等于某个值" —— 用 check() 会拿 True 去比 status，永远 FAIL。
    check_true('任务跑到了终态', status in (2, 3, 4, 5), status)

    section('3. 复查新菜：本该有主数据 ID 的行必须为 0')
    mine = [t for t in tasks if str(t.get('taskId')) == str(task_id)]
    dish_id = (mine[0].get('dishId') if mine else None)
    if dish_id is None:
        rows = mysql("SELECT dish_id FROM t_ai_generation_record WHERE task_id = %s "
                     "ORDER BY id DESC LIMIT 1" % task_id)
        dish_id = rows[0][0] if rows else None
    check_true('拿到菜品 ID', dish_id is not None, dish_id)
    if dish_id is None:
        return 1

    dish = mysql("SELECT name, status, nutrition_source, nutrition_verified FROM t_dish "
                 "WHERE id = %s" % dish_id)
    print('  新菜：%s' % (dish[0] if dish else '（没查到）'))

    rows = mysql("SELECT ingredient_name, IFNULL(ingredient_id,'NULL') FROM t_dish_ingredient "
                 "WHERE dish_id = %s ORDER BY sort_no" % dish_id)
    with_id = [r for r in rows if r[1] != 'NULL']
    without = [r for r in rows if r[1] == 'NULL']
    print('  明细 %d 行，有主数据 ID 的 %d 行，表外的 %d 行' % (len(rows), len(with_id), len(without)))
    for name, iid in rows:
        print('    %-14s %s' % (name, iid))

    debt_new = overview(' AND d.id = %s' % dish_id)
    check('新菜里「名字命中主数据却没 ID」的行数', debt_new, 0)
    check_true('这次生成确实落地了明细行', len(rows) > 0, len(rows))

    # 佐证：AI 补的、用户没勾选的食材也挂上了 ID（自由输入只有西红柿、鸡蛋）
    extra = [r for r in rows if r[0] not in ('西红柿', '鸡蛋')]
    extra_mapped = [r for r in extra if r[1] != 'NULL']
    print('  用户没勾选、由 AI 补进来的食材 %d 种，其中拿到主数据 ID 的 %d 种'
          % (len(extra), len(extra_mapped)))
    check_true('AI 补进来的食材只要主数据里有，就拿到了 ID',
               len(extra) == 0 or len(extra_mapped) > 0, '%d/%d' % (len(extra_mapped), len(extra)))

    section('小结')
    print('  归一化判定完全由 SQL 独立做出（不调用应用代码）')
    print('  PASS %d / FAIL %d' % (PASSED, FAILED))
    if FAIL_NAMES:
        print('  失败项：%s' % FAIL_NAMES)
    return 0 if FAILED == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
