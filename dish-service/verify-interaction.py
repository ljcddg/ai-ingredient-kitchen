# -*- coding: utf-8 -*-
"""
收藏 / 评分 / 评论 / 我的菜品 —— 端到端验证。

重点不是「能不能跑通」，而是那几处**写错了也不会报错**的地方：

  1. 重复点收藏     -> 收藏数不能虚增（靠 upsert 的 affected rows 判断）
  2. 取消后再收藏   -> 不能 500（唯一键 + 逻辑删除的经典冲突）
  3. 改分           -> 评价人数不能 +1（一人一票）
  4. 别名归一       -> 「西红柿」和「番茄」不能变成两样食材
  5. 越权           -> 别人的草稿看不到、别人的任务取消/移除不掉
  6. 任务记录移除   -> 进行中的任务不允许移除（会变成幽灵任务），
                       必须"先取消、再移除"，两条明确的路径

【关于"发布"这一步】
草稿（status=0）只有创建者本人能操作，所以第二个用户没法给它打分或评论 ——
这是**正确行为**，但会挡住"一人一票""多用户评分"这些用例。
所以脚本中间会直接用 mysql 把它改成已发布，模拟"审核通过进推荐池"。
真正的发布要走审核流（见 docs/ai-kitchen.md），这里只是为了让用例跑得下去。

跑法：python dish-service/verify-interaction.py
前置：ES + MySQL + Redis + Nacos + account-service + gateway + dish-service 都在跑
      Ollama 也要在（生成那步会真调模型）
"""
import json
import random
import string
import subprocess
import sys
import time
import urllib.error
import urllib.request

GW = 'http://127.0.0.1:7000'
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.EXE'

PASS = 0
FAIL = 0
FAILURES = []


def check(label, ok, extra=''):
    global PASS, FAIL
    if ok:
        PASS += 1
        print('  PASS  %-34s %s' % (label, extra))
    else:
        FAIL += 1
        FAILURES.append(label)
        print('  FAIL  %-34s %s' % (label, extra))


def call(method, path, payload=None, token=None, timeout=60):
    url = GW + path
    data = json.dumps(payload).encode('utf-8') if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode('utf-8'))
        except Exception:
            return e.code, {'code': e.code, 'message': 'HTTP %d' % e.code}
    except Exception as e:
        return None, {'code': -1, 'message': repr(e)}


def sql(statement):
    """直接改库。只在"把草稿发布"这一处用 —— 产品上那是审核流的活。"""
    r = subprocess.run([MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                        '-D', 'project0914', '-e', statement],
                       capture_output=True, text=True, encoding='utf-8', errors='ignore')
    return r.returncode == 0, (r.stderr or '').replace('Using a password', '').strip()


def register_and_login(prefix):
    user = prefix + ''.join(random.choices(string.digits, k=6))
    call('POST', '/api/account/auth/register',
         {'username': user, 'password': 'Test123456', 'nickname': '交互验证'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': user, 'password': 'Test123456'})
    return user, (body.get('data') or {}).get('token')


def wait_dish_service(token, attempts=30, gap=2):
    """
    等 dish-service 真的能用。

    为什么需要这个：它启动时 Tomcat 端口会**先**监听起来，Spring 上下文还在装配，
    这个窗口里发请求会拿到空响应（TCP 连得上，但没人处理）。
    光靠"端口通了"判断就绪是不够的，得真发一个请求看能不能拿到正常业务码。
    """
    for _ in range(attempts):
        st, body = call('GET', '/api/dish/ai/my/dishes', token=token, timeout=10)
        if st == 200 and body.get('code') == 200:
            return True
        time.sleep(gap)
    return False


def section(title):
    print()
    print('=' * 68)
    print(title)
    print('=' * 68)


# ====================================================================== 准备
section('0. 准备账号')

user_a, token_a = register_and_login('ixA')
user_b, token_b = register_and_login('ixB')
check('用户 A 登录', bool(token_a), user_a)
check('用户 B 登录', bool(token_b), user_b)

if not token_a or not token_b:
    print('\n登录失败，后续无法进行 —— 检查网关和 account-service')
    sys.exit(1)

if not wait_dish_service(token_a):
    print('\ndish-service 一直不可用 —— 检查它是否真的启动成功（8082）')
    sys.exit(1)

DISH = '/api/dish/ai/dish/{}'
TASK = '/api/dish/ai/tasks'

# ====================================================================== 提交
section('1. 提交生成任务（含自由输入 + 别名）')

# 101 = 番茄（点选）；「西红柿」是同一样东西的别名，用来验证归一；
# 「松露」不在主数据里，用来验证"照常生成但不参与营养核算"
payload = {
    'ingredientIds': [101, 102],
    'freeIngredients': ['西红柿', '松露'],
    'tastes': ['家常'],
    'servings': 2
}
st, body = call('POST', '/api/dish/ai/dish', payload, token=token_a)
task = body.get('data') or {}
task_id = task.get('taskId')

check('提交返回 200', body.get('code') == 200, body.get('message') or '')
check('拿到 taskId', bool(task_id), 'taskId=%s' % task_id)
check('排队位置有值', task.get('position') is not None, 'position=%s' % task.get('position'))

if not task_id:
    print('\n提交失败，后续无法进行')
    sys.exit(1)

# ====================================================================== 轮询
section('2. 轮询任务（验证状态与位置真的在变）')

positions = []
statuses = []
dish_id = None
deadline = time.time() + 240

while time.time() < deadline:
    st, body = call('GET', TASK, token=token_a)
    tasks = body.get('data') or []
    mine = next((t for t in tasks if t.get('taskId') == task_id), None)
    if not mine:
        break

    if not positions or positions[-1] != mine.get('position'):
        positions.append(mine.get('position'))
    if not statuses or statuses[-1] != mine.get('status'):
        statuses.append(mine.get('status'))

    if mine.get('status') in (2, 3, 4, 5):
        dish_id = mine.get('dishId')
        break
    time.sleep(1.5)

check('任务到达终态', bool(statuses) and statuses[-1] in (2, 3, 4, 5),
      '状态轨迹=%s，位置轨迹=%s' % (statuses, positions))
check('状态经过「生成中」', 1 in statuses, '轨迹=%s' % statuses)
check('生成成功并落库', statuses[-1] == 2 and bool(dish_id), 'dishId=%s' % dish_id)

if not dish_id:
    print('\n生成没有成功（状态轨迹 %s），后续依赖它的用例无法进行' % statuses)
    print('提示：一直停在 0 或 1 的话，检查 Ollama 是否在跑（11434）')
    sys.exit(1)

st, body = call('GET', TASK, token=token_a)
mine = next((t for t in (body.get('data') or []) if t.get('taskId') == task_id), {})
check('任务带食材摘要', bool(mine.get('summary')), 'summary=%r' % mine.get('summary'))
check('任务带菜名', bool(mine.get('dishName')), 'dishName=%r' % mine.get('dishName'))
check('耗时已记录', (mine.get('costMs') or 0) > 0, 'costMs=%s' % mine.get('costMs'))

# 主键必须以字符串形式输出：19 位雪花 ID 超过了 JS Number 的精确范围（2^53），
# 当成 JSON number 发出去，浏览器 JSON.parse 之后低位就丢了 ——
# 前端再拿它去请求详情，后端只会回「菜品不存在」，而且打印出来看着还挺像原值。
# 这两条断言守住 common-service 里 JsonAutoConfiguration 的约定，防止被人无意改掉。
check('taskId 输出为字符串', isinstance(mine.get('taskId'), str),
      'type=%s' % type(mine.get('taskId')).__name__)
check('dishId 输出为字符串', isinstance(dish_id, str),
      'type=%s value=%s' % (type(dish_id).__name__, dish_id))

# ====================================================================== 详情
section('3. 菜品详情（生成完已自动发布）')

st, body = call('GET', DISH.format(dish_id), token=token_a)
d = body.get('data') or {}

check('详情返回 200', body.get('code') == 200, body.get('message') or '')
# 生成成功后会自动跑一次发布校验，通过就直接进找菜库 ——
# 所以这里期望的是"已发布"而不是"草稿"。
# 这条断言同时守着自动发布那条路：它一旦失效，用户生成的菜会永远搜不到，
# 而现象只是"召回里没有这道菜"，很难定位到是发布环节断的。
check('生成后已自动发布', d.get('status') == 1, 'status=%s' % d.get('status'))
check('发布时间已写入', bool(d.get('publishTime')), 'publishTime=%s' % d.get('publishTime'))
check('来源标记为 AI 生成', d.get('source') == 2, 'source=%s' % d.get('source'))
check('有制作步骤', len(d.get('steps') or []) >= 3, '%d 步' % len(d.get('steps') or []))
check('有大厨建议', len(d.get('chefTips') or []) >= 1, '%d 条' % len(d.get('chefTips') or []))
check('有营养数据', (d.get('nutrition') or {}).get('calories', 0) > 0,
      '热量=%s' % (d.get('nutrition') or {}).get('calories'))
check('营养标注了来源', bool((d.get('nutrition') or {}).get('source')),
      '%r' % (d.get('nutrition') or {}).get('source'))
check('初始未收藏', d.get('collected') is False, 'collected=%s' % d.get('collected'))
check('初始无我的评分', d.get('myScore') is None, 'myScore=%s' % d.get('myScore'))

names = [i.get('name') for i in (d.get('ingredients') or [])]
check('别名归一后无重复', names.count('番茄') == 1 and '西红柿' not in names, '食材=%s' % names)
check('自填食材保留原文', '松露' in names, '食材=%s' % names)

free_rows = [i for i in (d.get('ingredients') or []) if not i.get('ingredientId')]
free_names = [r.get('name') for r in free_rows]
# 只断言「松露」在里面，不断言"自填行只有它一个" ——
# AI 有时会把调料（比如「食用油」）也写进 ingredients 而不是 seasonings，
# 那是提示词遵守度问题，不是归一逻辑的问题，不该让这条断言替它背锅。
check('自填食材无主数据 ID', '松露' in free_names, '自填行=%s' % free_names)
if len(free_rows) > 1:
    print('       注意：AI 额外把 %s 也放进了 ingredients（提示词要求调料放 seasonings）'
          % [n for n in free_names if n != '松露'])

# ====================================================================== 越权（发布前）
section('4. 反例：草稿不该被别人碰到')

# 先撤回，把它变回草稿再测这一节。
# 以前这一步是"直接改库把菜设成已发布"绕过去的（那时还没有发布接口），
# 现在让接口自己走一遍：测的是真实路径，而不是"我把库改成我想要的样子"。
st, body = call('POST', DISH.format(dish_id) + '/unpublish', token=token_a)
check('先撤回变回草稿', body.get('code') == 200, body.get('message') or '')

st, body = call('GET', DISH.format(dish_id), token=token_b)
check('B 看 A 的草稿被拒', body.get('code') != 200, body.get('message') or '')

st, body = call('POST', DISH.format(dish_id) + '/collect', token=token_b)
check('B 收藏 A 的草稿被拒', body.get('code') != 200, body.get('message') or '')

st, body = call('POST', DISH.format(dish_id) + '/rating', {'score': 5}, token=token_b)
check('B 给 A 的草稿打分被拒', body.get('code') != 200, body.get('message') or '')

# ====================================================================== 收藏
section('5. 收藏（幂等 + 恢复式）')

collect_url = DISH.format(dish_id) + '/collect'

st, body = call('POST', collect_url, token=token_a)
r1 = body.get('data') or {}
check('收藏成功', body.get('code') == 200 and r1.get('collected') is True,
      'collected=%s count=%s' % (r1.get('collected'), r1.get('collectCount')))
check('收藏数为 1', r1.get('collectCount') == 1, 'count=%s' % r1.get('collectCount'))

# 关键：再点一次，计数不能变 2
st, body = call('POST', collect_url, token=token_a)
r2 = body.get('data') or {}
check('重复收藏不虚增', r2.get('collectCount') == 1, 'count=%s' % r2.get('collectCount'))

st, body = call('DELETE', collect_url, token=token_a)
r3 = body.get('data') or {}
check('取消收藏成功', body.get('code') == 200 and r3.get('collected') is False,
      'collected=%s count=%s' % (r3.get('collected'), r3.get('collectCount')))
check('取消后计数归零', r3.get('collectCount') == 0, 'count=%s' % r3.get('collectCount'))

# 关键：记录还在（逻辑删除 + 唯一键），再收藏必须走恢复而不是插入
st, body = call('POST', collect_url, token=token_a)
r4 = body.get('data') or {}
check('取消后能再次收藏（恢复式）', body.get('code') == 200 and r4.get('collectCount') == 1,
      'code=%s count=%s msg=%s' % (body.get('code'), r4.get('collectCount'), body.get('message')))

st, body = call('DELETE', collect_url, token=token_a)
st, body = call('DELETE', collect_url, token=token_a)
check('重复取消不报错', body.get('code') == 200, body.get('message') or '')

# 恢复成「已收藏」，后面验证「我的收藏」列表
call('POST', collect_url, token=token_a)

# ====================================================================== 发布
section('6. 发布（好让第二个用户能参与）')

# 走真实接口发布，不再直接改库。
# 改库测不出「发布接口 + 事务事件 + 同步 ES」这条链路，
# 而后面所有跨用户的收藏 / 评分 / 评论都以"它真的发布出去了"为前提。
st, body = call('POST', DISH.format(dish_id) + '/publish', token=token_a)
check('发布成功', body.get('code') == 200, body.get('message') or '')

st, body = call('GET', DISH.format(dish_id), token=token_a)
check('发布后状态为 1', (body.get('data') or {}).get('status') == 1,
      'status=%s' % (body.get('data') or {}).get('status'))

st, body = call('GET', DISH.format(dish_id), token=token_b)
check('发布后 B 能看了', body.get('code') == 200, body.get('message') or '')

# ====================================================================== 评分
section('7. 评分（一人一票）')

rate_url = DISH.format(dish_id) + '/rating'

st, body = call('POST', rate_url, {'score': 4}, token=token_a)
s1 = body.get('data') or {}
check('A 打分成功', body.get('code') == 200, body.get('message') or '')
check('评分人数为 1', s1.get('count') == 1, 'count=%s' % s1.get('count'))
check('平均分为 4.0', abs((s1.get('average') or 0) - 4.0) < 0.01, 'avg=%s' % s1.get('average'))
check('我的评分已记录', s1.get('myScore') == 4, 'myScore=%s' % s1.get('myScore'))

# 关键：改分不能变成两个人
st, body = call('POST', rate_url, {'score': 5}, token=token_a)
s2 = body.get('data') or {}
check('改分不新增评价人数', s2.get('count') == 1, 'count=%s' % s2.get('count'))
check('平均分跟着改', abs((s2.get('average') or 0) - 5.0) < 0.01, 'avg=%s' % s2.get('average'))

st, body = call('POST', rate_url, {'score': 3, 'cooked': True}, token=token_b)
s3 = body.get('data') or {}
check('B 打分后人数变 2', s3.get('count') == 2, 'count=%s' % s3.get('count'))
check('平均分正确 (5+3)/2=4.0', abs((s3.get('average') or 0) - 4.0) < 0.01, 'avg=%s' % s3.get('average'))

st, body = call('POST', rate_url, {'score': 9}, token=token_a)
check('越界分值被拒', body.get('code') != 200, body.get('message') or '')

# ====================================================================== 评论
section('8. 评论')

comment_url = DISH.format(dish_id) + '/comment'

st, body = call('POST', comment_url, {'content': '火候正好，家里人很爱吃', 'rating': 5},
                token=token_b)
c1 = body.get('data') or {}
check('评论发表成功', body.get('code') == 200, body.get('message') or '')
check('评论带回昵称', bool(c1.get('nickname')), 'nickname=%r' % c1.get('nickname'))
check('评论带回时间', bool(c1.get('createTime')), 'time=%r' % c1.get('createTime'))

st, body = call('POST', comment_url, {'content': '   '}, token=token_a)
check('空白评论被拒', body.get('code') != 200, body.get('message') or '')

st, body = call('POST', comment_url, {'content': 'x' * 600}, token=token_a)
check('超长评论被拒', body.get('code') != 200, body.get('message') or '')

st, body = call('POST', comment_url, {'content': '我按这个方子做了，很成功'}, token=token_a)
check('A 也能评论（可多条）', body.get('code') == 200, body.get('message') or '')

st, body = call('GET', DISH.format(dish_id), token=token_a)
comments = (body.get('data') or {}).get('comments') or []
check('详情里能看到评论', len(comments) >= 2, '%d 条' % len(comments))
check('评论带昵称', all(c.get('nickname') for c in comments),
      '%s' % [c.get('nickname') for c in comments])

# ====================================================================== 我的
section('9. 我的菜品 / 我的收藏')

st, body = call('GET', '/api/dish/ai/my/dishes', token=token_a)
dishes = body.get('data') or []
check('我的菜品列表可用', body.get('code') == 200, body.get('message') or '')
check('列表里有刚生成的菜', any(x.get('id') == dish_id for x in dishes), '%d 条' % len(dishes))
check('列表项带状态与来源', all('status' in x and 'source' in x for x in dishes))

st, body = call('GET', '/api/dish/ai/my/collects', token=token_a)
collects = body.get('data') or []
check('我的收藏列表可用', body.get('code') == 200, body.get('message') or '')
check('收藏列表里有这道菜', any(x.get('id') == dish_id for x in collects), '%d 条' % len(collects))
check('收藏项带收藏时间', all(x.get('markedTime') for x in collects),
      'markedTime=%s' % [x.get('markedTime') for x in collects][:2])

st, body = call('GET', '/api/dish/ai/my/collects', token=token_b)
empty = body.get('data')
check('B 没收藏过则列表为空', not empty, '%s' % empty)

st, body = call('GET', '/api/dish/ai/my/dishes', token=token_b)
check('B 没有自己创建的菜', not (body.get('data') or []), '%s' % (body.get('data') or []))

# ====================================================================== 移除任务
section('10. 移除任务记录（只允许已结束的）')

# 用一个「探测器」任务来验证移除规则 —— 主任务这时已经生成完成，
# 用它测不出「进行中的任务不能移除」这条分支。
st, body = call('POST', '/api/dish/ai/dish',
                {'ingredientIds': [105], 'freeIngredients': [], 'tastes': [], 'servings': 1},
                token=token_a)
probe_id = (body.get('data') or {}).get('taskId')
check('提交探测器任务', bool(probe_id), 'taskId=%s' % probe_id)

# 趁它还在排队/生成，直接尝试移除 —— 必须被拒。
# 这条限制是有意义的：放行的话用户以为任务没了，
# 后台的 AI 调用还在跑、跑完还会往库里落一道菜，变成谁也说不清的幽灵任务。
st, body = call('DELETE', '%s/%s' % (TASK, probe_id), token=token_a)
check('进行中的任务不能移除（反例）', body.get('code') != 200, body.get('message') or '')

st, body = call('POST', '%s/%s/cancel' % (TASK, probe_id), token=token_a)
check('先取消探测器任务', body.get('code') == 200, body.get('message') or '')

st, body = call('DELETE', '%s/%s' % (TASK, probe_id), token=token_a)
check('取消后的任务可以移除', body.get('code') == 200, body.get('message') or '')

st, body = call('GET', TASK, token=token_a)
ids = [t.get('taskId') for t in (body.get('data') or [])]
check('移除后列表里不再出现', probe_id not in ids, '当前共 %d 条任务' % len(ids))

st, body = call('DELETE', '%s/%s' % (TASK, task_id), token=token_b)
check('移除别人的任务被拒', body.get('code') != 200, body.get('message') or '')

# ====================================================================== 反例
section('11. 反例：任务越权（取消）')

st, body = call('POST', '%s/%s/cancel' % (TASK, task_id), token=token_b)
check('取消别人的任务被拒', body.get('code') != 200, body.get('message') or '')

st, body = call('POST', '%s/999999999/cancel' % TASK, token=token_a)
check('取消不存在的任务被拒', body.get('code') != 200, body.get('message') or '')

st, body = call('GET', DISH.format(999999999), token=token_a)
check('查不存在的菜被拒', body.get('code') != 200, body.get('message') or '')

# ====================================================================== 汇总
print()
print('=' * 68)
print('汇总：%d PASS / %d FAIL' % (PASS, FAIL))
if FAILURES:
    print('失败项：')
    for f in FAILURES:
        print('  -', f)
print('=' * 68)
print()
print('本次账号：%s / %s  （密码都是 Test123456）' % (user_a, user_b))
print('生成的菜品 ID：%s' % dish_id)

sys.exit(0 if FAIL == 0 else 1)
