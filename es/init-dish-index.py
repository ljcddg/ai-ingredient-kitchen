"""建 ES 菜品索引（可选灌演示数据）。

==============================================================================
用法
==============================================================================
  python init-dish-index.py                 只确保索引存在（默认行为，安全）
  python init-dish-index.py --sample        额外灌入 9 条演示数据
  python init-dish-index.py --purge-sample  只清掉那 9 条演示数据
  python init-dish-index.py --recreate      删掉整个索引重建（会清空，需确认）

==============================================================================
为什么默认不灌演示数据（血泪教训，改回去之前先读这段）
==============================================================================
sample-dish-data.ndjson 里的 9 条菜（dishId 1001~1009）是**纯索引数据**，
MySQL 的 t_dish 里并没有对应的行。于是会出现一种极具迷惑性的现象：

    搜索页能召回「土豆炖牛腩 4.7 分 · 200 人评价 · 900 人收藏」，
    点进去却弹出「菜品不存在」。

原因很直白：搜索走 ES、详情走 MySQL，两边对不上号。
ES 里有、库里没有的文档就叫**幽灵菜** —— 它一定点不开，
而且因为详情接口对"不存在"和"无权限"统一提示，日志上还看不出异常。

真要灌演示数据，就必须**库和索引一起灌**（t_dish + t_dish_ingredient
也要有对应记录），否则就是给自己埋雷。目前这个脚本只负责索引侧，
所以默认关掉，并且只在显式 --sample 时才灌。

顺带一提，--recreate 会删掉整个索引，真实数据也会一起没。
所以默认改成"索引已存在就跳过"，要重建必须显式加 --recreate。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:9200'
# 本脚本位于 <项目根>/es/ 下，向上一级推导项目根，避免把绝对路径写死
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INDEX = 'dish_index'

# 演示数据的 dishId 段。真实菜品用的是 19 位雪花 ID，两者不会撞。
SAMPLE_IDS = list(range(1001, 1010))

args = set(a.lower() for a in sys.argv[1:])
do_recreate = '--recreate' in args
do_sample = '--sample' in args
do_purge = '--purge-sample' in args


def req(method, path, body=None, ctype='application/json'):
    data = body.encode('utf-8') if isinstance(body, str) else body
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header('Content-Type', ctype)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            return resp.status, resp.read().decode('utf-8')
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode('utf-8')


def count():
    st, body = req('GET', '/%s/_count' % INDEX)
    return json.loads(body).get('count') if st == 200 else 'ERR'


def purge_sample():
    """删掉索引里那 9 条演示文档（按 dishId 精确匹配，不碰真实数据）。"""
    body = json.dumps({'query': {'terms': {'dishId': SAMPLE_IDS}}})
    st, res = req('POST', '/%s/_delete_by_query?refresh=true&conflicts=proceed' % INDEX, body)
    if st != 200:
        print('  清理失败：HTTP %s %s' % (st, res[:300]))
        return
    print('  已删除演示文档 %s 条' % json.loads(res).get('deleted'))


# ---------------------------------------------------------------- 0) 只清理
if do_purge:
    print('清理演示数据：dishId %s' % SAMPLE_IDS)
    before = count()
    purge_sample()
    print('  索引文档数：%s -> %s' % (before, count()))
    raise SystemExit(0)


# ---------------------------------------------------------------- 1) 索引
if do_recreate:
    # 删索引 = 真实数据一起没。全量重建能救回来（POST /ai/es/reindex），
    # 但在那之前搜索是空的，所以必须显式确认。
    print('即将删除索引 %s（真实数据也会一起清空）' % INDEX)
    if input('确认请输入 yes：').strip().lower() != 'yes':
        raise SystemExit('已取消')
    st, body = req('DELETE', '/%s' % INDEX)
    print('DELETE /%s -> %s' % (INDEX, st))
else:
    st, _ = req('HEAD', '/%s' % INDEX)
    if st == 200:
        print('索引 %s 已存在（当前 %s 条），跳过创建。' % (INDEX, count()))
        print('  想清空重建用 --recreate；只想重灌索引用 POST /ai/es/reindex。')
    else:
        # 2) 建索引
        # mapping 的唯一来源在 dish-service 的 resources 下：
        # 应用启动时会读**同一份**来自动建索引（DishIndexWriter.ensureIndex）。
        # 两处各存一份 mapping 是"改了没生效"的经典来源，所以这里也指向它。
        MAPPING = os.path.join(ROOT, 'dish-service', 'src', 'main', 'resources',
                               'es', 'dish-index.json')
        with open(MAPPING, encoding='utf-8') as f:
            mapping = f.read()
        st, body = req('PUT', '/%s' % INDEX, mapping)
        print('PUT    /%s -> %s %s' % (INDEX, st, body[:200]))
        if st != 200:
            raise SystemExit('建索引失败：' + body)


# ---------------------------------------------------------------- 2) 演示数据
if not do_sample:
    print('\n未灌入演示数据（默认行为）。')
    print('  原因：这 9 条在 MySQL 里没有对应记录，搜索能召回但点进去必然')
    print('       「菜品不存在」。需要它们做召回验证时，用 --sample 显式灌入，')
    print('        验证完记得 --purge-sample 清掉。')
    raise SystemExit(0)

print('\n' + '!' * 70)
print('!! 即将灌入 9 条【纯索引】演示数据，它们在 MySQL 里没有对应记录：')
print('!!   搜索能搜到，点进去一律提示「菜品不存在」。')
print('!! 只应用于 es/verify-recall.py 这类召回验证，跑完请执行：')
print('!!   python init-dish-index.py --purge-sample')
print('!' * 70)

with open(os.path.join(ROOT, 'es', 'sample-dish-data.ndjson'), encoding='utf-8') as f:
    nd = f.read()
if not nd.endswith('\n'):
    nd += '\n'
st, body = req('POST', '/_bulk?refresh=true', nd, ctype='application/x-ndjson')
res = json.loads(body)
print('\nPOST   /_bulk       -> %s  errors = %s' % (st, res.get('errors')))
for it in res.get('items', []):
    a = it.get('index', {})
    print('        id=%-5s status=%s %s' % (a.get('_id'), a.get('status'),
                                            '' if a.get('status') == 201 else a.get('error')))

# 3) 计数 + 确认只有一个业务索引
print('COUNT  /%s  -> %s' % (INDEX, count()))
st, body = req('GET', '/_cat/indices?format=json')
print('INDICES             -> %s' % [i['index'] for i in json.loads(body)])
