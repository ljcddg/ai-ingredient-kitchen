# -*- coding: utf-8 -*-
"""
P2-2 营养本地计算的端到端验证：真实数据 + 独立复算。

做三件事：

1. 调管理员接口把全库营养重算一遍，看它报了什么（更新多少、跳过多少、与 AI 估算的偏差）；
2. **独立复算**：脚本自己按「每 100 克 × 克数 ÷ 人数」重算几道菜，
   与库里存的值逐项比对 —— 不复用应用的代码路径，避免"自己验自己"；
3. 核对来源标注：算得出来的必须是 2（本地计算），算不出来的必须还是 1（AI 估算），
   不允许出现"值是估算的、标签写本地"这种情况。

用法（需要 gateway/account/dish 在跑 + MySQL）：
    python dish-service/verify-p2-nutrition.py
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


def login_admin():
    username = 'nutri' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': '营养验证'})
    mysql("UPDATE t_user SET role = 1 WHERE username = '%s'" % username)
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return (body.get('data') or {}).get('token')


def main():
    token = login_admin()
    if not token:
        print('管理员登录失败：确认 gateway/account 在跑')
        return 1

    section('1. 调管理员接口：全库营养重算')
    st, body = call('POST', '/api/dish/ai/admin/nutrition/recompute', token=token)
    check('重算接口 -> 业务码 200', body.get('code'), 200)
    report = body.get('data') or {}
    print('  报告：%s' % report)
    check_true('至少更新了一批菜品', (report.get('updated') or 0) > 0, report.get('updated'))

    section('2. 来源标注：算得出来必须是 2，算不出来必须还是 1')
    rows = mysql("SELECT IFNULL(nutrition_source,'NULL'), COUNT(*) FROM t_dish "
                 "WHERE deleted = 0 GROUP BY nutrition_source ORDER BY nutrition_source")
    dist = {r[0]: int(r[1]) for r in rows}
    print('  来源分布（1=AI 估算，2=本地计算）：%s' % dist)
    check_true('库里出现了「本地计算」来源的菜品', (dist.get('2') or 0) > 0, dist.get('2'))
    check('重算报告的 localSourceTotal 与库内一致', str(report.get('localSourceTotal')),
          str(dist.get('2') or 0))

    section('3. 独立复算：脚本自己算一遍，和库里存的值比')
    # 挑几道食材多、且已标为本地计算的菜
    dishes = mysql("SELECT id, name, base_serving, energy_kcal, protein_g, fat_g, carb_g, "
                   "fiber_g, sodium_mg FROM t_dish WHERE deleted = 0 AND nutrition_source = 2 "
                   "ORDER BY id DESC LIMIT 5")
    check_true('取到若干道本地计算的菜', len(dishes) > 0, len(dishes))

    checked = 0
    for row in dishes:
        dish_id, name = int(row[0]), row[1]
        servings = int(row[2]) if row[2] not in ('', 'NULL') else 1
        stored = [float(x) if x not in ('', 'NULL') else 0.0 for x in row[3:9]]

        items = mysql("SELECT ingredient_name, amount_gram FROM t_dish_ingredient "
                      "WHERE dish_id = %d AND amount_gram IS NOT NULL AND amount_gram > 0" % dish_id)
        if not items:
            continue

        # 独立实现一遍：每 100 克 × 克数 ÷ 人数
        totals = [0.0] * 6
        resolved = 0
        total_gram = 0.0
        resolved_gram = 0.0
        for item in items:
            ing_name = item[0]
            gram = float(item[1])
            total_gram += gram
            nut = mysql("SELECT calorie, protein, fat, carb, fiber, sodium FROM t_ingredient "
                        "WHERE deleted = 0 AND (name = '%s' OR FIND_IN_SET('%s', alias) > 0) "
                        "LIMIT 1" % (ing_name, ing_name))
            if not nut:
                continue
            resolved += 1
            resolved_gram += gram
            for i in range(6):
                totals[i] += float(nut[0][i]) * gram / 100.0
        if resolved_gram / total_gram < 0.8:
            continue

        expected = [round(v / max(1, servings), 1) for v in totals]
        ok = all(abs(expected[i] - stored[i]) <= 0.15 for i in range(6))
        print('  %s：库内 %s' % (name, stored))
        print('  %-14s 独立复算 %s' % ('', expected))
        check_true('「%s」六项营养与独立复算一致' % name, ok)
        checked += 1
        if checked >= 3:
            break

    check_true('至少独立复算核对了 3 道菜', checked >= 3, checked)

    section('4. 一致性：本地计算的菜不应再标成"未核验"')
    bad = mysql("SELECT COUNT(*) FROM t_dish WHERE deleted = 0 AND nutrition_source = 2 "
                "AND IFNULL(nutrition_verified,0) <> 1")
    check('本地计算但未核验的菜品数', bad[0][0], '0')

    print()
    print('=' * 72)
    print('P2-2 验证：PASS %d / FAIL %d' % (PASSED, FAILED))
    for name in FAIL_NAMES:
        print('  - 失败项：%s' % name)
    print('=' * 72)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
