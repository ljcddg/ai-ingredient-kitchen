"""dish-service 召回接口端到端验证。

三个用例都是精心设计的，目的是让「实现错了」这件事能被断言抓住：

  用例 1  番茄+鸡蛋      验证硬约束命中 3 条 + 直出判定为 true + 精排顺序
  用例 2  番茄+土豆      验证评分阈值能把候选挡在直出之外
  用例 3  番茄+鸡蛋+牛腩 验证「库里确实没有」这条分支

另外用例 1 同时隐含验证了两件事：
  草稿 dishId=1008（status=0）不该出现
  已删除 dishId=1009（deleted=1）不该出现

==============================================================================
它依赖的 9 条数据（dishId 1001~1009）是【纯索引】数据，MySQL 里没有对应行
==============================================================================
所以本脚本自己管数据的生老病死：

    开始 -> 灌进 dish_index
    结束 -> 从 dish_index 删掉（finally，出错也删）

为什么要这么麻烦：留着它们，搜索页就会召回「土豆炖牛腩」这类卡片，
点进去却提示「菜品不存在」—— 搜索走 ES、详情走 MySQL，两边对不上号。
这类"能搜到但点不开"的文档叫幽灵菜，生产环境里一条都不该有。

（跑完仍想手动确认的话：python init-dish-index.py --purge-sample）
"""
import json
import os
import urllib.error
import urllib.request

URL = 'http://127.0.0.1:8082/search'
ES = 'http://127.0.0.1:9200'
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = 'dish_index'
SAMPLE_IDS = list(range(1001, 1010))

CASES = [
    {
        'name': '用例1  食材=番茄+鸡蛋',
        'req': {'ingredientIds': [101, 102]},
        # 1003 缺鸡蛋、1006 缺番茄、1007 缺鸡蛋都不该被召回（硬约束）
        # 1008 是草稿、1009 已删除，也都该被过滤掉
        'expect_ids': [1001, 1002, 1004],
        'expect_direct': True,
        'expect_top': '番茄炒蛋',
    },
    {
        'name': '用例2  食材=番茄+土豆',
        'req': {'ingredientIds': [101, 105]},
        # 只有 1007 同时含这两样；但它 ratingWilson=0.70 < 0.84 → 不该直出
        'expect_ids': [1007],
        'expect_direct': False,
        'expect_top': '番茄土豆汤',
    },
    {
        'name': '用例3  食材=番茄+鸡蛋+牛腩',
        'req': {'ingredientIds': [101, 102, 104]},
        # 没有任何一道菜同时含这三样 → 空结果，交给 AI 生成
        'expect_ids': [],
        'expect_direct': False,
        'expect_top': None,
    },
]


def es_req(method, path, body=None, ctype='application/json'):
    data = body.encode('utf-8') if isinstance(body, str) else body
    r = urllib.request.Request(ES + path, data=data, method=method)
    if data is not None:
        r.add_header('Content-Type', ctype)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def seed_sample():
    """把 9 条演示文档灌进索引（跑完必须 purge，否则会变成幽灵菜）。"""
    with open(os.path.join(ROOT, 'es', 'sample-dish-data.ndjson'), encoding='utf-8') as f:
        nd = f.read()
    if not nd.endswith('\n'):
        nd += '\n'
    res = es_req('POST', '/%s/_bulk?refresh=true' % INDEX, nd,
                 ctype='application/x-ndjson')
    if res.get('errors'):
        print('!! 灌入演示数据出现错误：')
        for it in res.get('items', []):
            a = it.get('index', {})
            if a.get('status') != 201:
                print('   id=%s %s' % (a.get('_id'), a.get('error')))
        return False
    print('已临时灌入 %d 条演示数据（dishId %s...%s）'
          % (len(res.get('items', [])), SAMPLE_IDS[0], SAMPLE_IDS[-1]))
    return True


def purge_sample():
    """清掉演示文档。按 dishId 精确匹配，不碰真实数据。"""
    body = json.dumps({'query': {'terms': {'dishId': SAMPLE_IDS}}})
    res = es_req('POST', '/%s/_delete_by_query?refresh=true&conflicts=proceed' % INDEX, body)
    print('已清理演示数据 %s 条' % res.get('deleted', '?'))


def call(payload):
    data = json.dumps(payload).encode('utf-8')
    req = urllib.request.Request(URL, data=data, method='POST')
    req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return {'code': e.code, 'raw': e.read().decode('utf-8')}


if not seed_sample():
    raise SystemExit('演示数据没灌进去，后面的断言没有意义，终止。')

passed = 0
failed = 0
try:
    for case in CASES:
        print('=' * 68)
        print(case['name'])
        resp = call(case['req'])
        if resp.get('code') != 200:
            print('  [FAIL] 接口返回异常：', resp)
            failed += 1
            continue

        data = resp['data']
        got_ids = sorted(x['dishId'] for x in data['candidates'])
        top_name = data['best']['name'] if data.get('best') else None

        checks = [
            ('召回集合', got_ids, sorted(case['expect_ids'])),
            ('directReturn', data['directReturn'], case['expect_direct']),
            ('best.name', top_name, case['expect_top']),
        ]

        for label, got, exp in checks:
            flag = 'PASS' if got == exp else 'FAIL'
            if got == exp:
                passed += 1
            else:
                failed += 1
            print('  [%s] %-14s got=%r  expect=%r' % (flag, label, got, exp))

        print('  -- 候选明细 --')
        if not data['candidates']:
            print('     (无候选)')
        for c in data['candidates']:
            print('     %-14s hit=%d/%d  覆盖率=%.2f  综合分=%.4f  wilson=%.2f  评价=%d人  %s'
                  % (c['name'], c['hitCount'], data['requestedIngredientCount'],
                     c['similarity'], c['score'], c['ratingWilson'] or 0,
                     c['ratingCount'] or 0,
                     '可直出' if c['directEligible'] else '不可直出'))
        print('  missReason =', data.get('missReason'))
        print('  ES 可用    =', data.get('searchEngineAvailable'))
finally:
    # 出错也要清：留着就是 9 条点不开的幽灵菜
    print('=' * 68)
    purge_sample()

print('=' * 68)
print('断言结果：PASS=%d  FAIL=%d' % (passed, failed))
