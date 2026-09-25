"""给榜单造一批"看得下去"的演示数据。

==============================================================================
它到底做什么
==============================================================================
  阶段1  注册 18 个有真人感的美食账号
  阶段2  用不同账号走真实 AI 链路生成 12 道家常菜 -> 发布（此时才上榜单）
  阶段3  用这些账号给所有已发布的菜打分 / 评论 / 收藏，数量与分数按人气档位拉开
  阶段4  打印三个榜单，肉眼验收

==============================================================================
为什么必须走 HTTP 接口而不是直接写库
==============================================================================
评分打完之后要重算 rating_avg / rating_count / rating_wilson，
收藏要按明细重算 count，发布还要同步 ES 索引。这些逻辑写在 Service 里，
绕过它们直接 INSERT，字段之间会立刻对不上（比如 count=20 但 avg 还是 null），
榜单照样难看。所以这里老老实实用接口，慢一点但该触发的都会触发。

同时也顺便验证了这条链路是通的 —— 接口挂了脚本会立刻报错。

==============================================================================
用法
==============================================================================
  python seed-rank-demo.py              # 跑全部阶段
  python seed-rank-demo.py --from 3     # 从阶段3开始（菜已经有了，只补互动）
  python seed-rank-demo.py --dry        # 只打印计划，不调任何接口
"""
import json
import random
import sys
import time
import urllib.error
import urllib.request

GW = 'http://127.0.0.1:7000'
PASSWORD = 'foodie2026'
RNG = random.Random(20260917)

args = sys.argv[1:]
DRY = '--dry' in args
START = 1
if '--from' in args:
    START = int(args[args.index('--from') + 1])

# ---------------------------------------------------------------- 账号
# 昵称写得像真人，评论区才不会一眼假
ACCOUNTS = [
    ('foodie01', '爱做饭的小鹿'), ('foodie02', '深夜食堂常客'),
    ('foodie03', '番茄炒蛋达人'), ('foodie04', '厨房小白一枚'),
    ('foodie05', '川味爱好者'), ('foodie06', '减脂餐日记'),
    ('foodie07', '粤菜老饕'), ('foodie08', '面食控本人'),
    ('foodie09', '汤汤水水'), ('foodie10', '辣味研究所'),
    ('foodie11', '家常菜学徒'), ('foodie12', '一人食'),
    ('foodie13', '懒人快手菜'), ('foodie14', '周末下厨'),
    ('foodie15', '海鲜星人'), ('foodie16', '素食日常'),
    ('foodie17', '干饭第一名'), ('foodie18', '厨房试验田'),
]

# ---------------------------------------------------------------- 要生成的菜
# 用真实家常菜的食材组合去喂 AI，它给出的菜名才不会跑偏
DISHES = [
    {'ings': [101, 102, 103], 'tastes': ['家常', '咸香'], 'servings': 2, 'tier': 'star'},
    {'ings': [143, 116, 169], 'tastes': ['酱香', '家常'], 'servings': 3, 'tier': 'star'},
    {'ings': [145, 116, 117], 'tastes': ['甜鲜', '家常'], 'servings': 2, 'tier': 'star'},
    {'ings': [110, 109, 172, 173], 'tastes': ['麻辣', '下饭'], 'servings': 2, 'tier': 'good'},
    {'ings': [151, 116, 103], 'tastes': ['清淡', '鲜香'], 'servings': 2, 'tier': 'good'},
    {'ings': [104, 105, 114], 'tastes': ['酱香', '浓郁'], 'servings': 3, 'tier': 'star'},
    {'ings': [108, 109, 115], 'tastes': ['家常', '下饭'], 'servings': 2, 'tier': 'mid'},
    {'ings': [125, 115], 'tastes': ['清淡', '爽口'], 'servings': 2, 'tier': 'mid'},
    {'ings': [109, 160, 174], 'tastes': ['麻辣', '下饭'], 'servings': 2, 'tier': 'mid'},
    {'ings': [144, 136, 116], 'tastes': ['清淡', '鲜香'], 'servings': 3, 'tier': 'good'},
    {'ings': [106, 102], 'tastes': ['家常', '快手'], 'servings': 2, 'tier': 'cold'},
    {'ings': [148, 114, 175], 'tastes': ['孜然', '浓郁'], 'servings': 2, 'tier': 'cold'},
]

# 人气档位：控制"评价人数 / 目标均分 / 收藏数"三件事，
# 这三样拉开距离，榜单才不会清一色同样的数据
TIERS = {
    'star': {'raters': (14, 18), 'target': (4.6, 4.9), 'collects': (12, 17), 'comments': (5, 8)},
    'good': {'raters': (9, 13), 'target': (4.2, 4.6), 'collects': (6, 10), 'comments': (3, 6)},
    'mid': {'raters': (5, 8), 'target': (3.7, 4.2), 'collects': (2, 5), 'comments': (1, 3)},
    'cold': {'raters': (2, 4), 'target': (3.0, 3.7), 'collects': (0, 2), 'comments': (0, 2)},
}

# 评论语料按星级分档：高分夸得具体，低分抱怨得具体，中间的不痛不痒 ——
# 这样评论区和分数对得上，看着才像真的
COMMENTS = {
    5: ['照着做了一次，家里人都说比外面买的还好吃',
        '第一次做就成功了，步骤写得很清楚，新手友好',
        '这个做法很稳，我已经回购第三次了',
        '味道出乎意料的好，朋友来家里点名要这道',
        '调料用量给得很准，不用自己再摸索'],
    4: ['味道不错，下次可以少放一点点盐',
        '简单快手，下班回家半小时就搞定',
        '整体很好，就是最后收汁可以再久一点',
        '家里小孩挺爱吃的，会收藏继续做',
        '菜谱靠谱，按这个顺序下去不会手忙脚乱'],
    3: ['中规中矩，属于能吃但没惊艳的那种',
        '还行吧，下次打算多放点调料试试',
        '做出来了但感觉差点意思，可能火候没到',
        '可以试试，不过我个人更喜欢重口味版本'],
    2: ['步骤有点模糊，新手容易做翻车',
        '不太成功，可能是我自己没掌握好火候',
        '味道偏淡，得多加点料才吃得下去'],
}


def call(method, path, body=None, token=None, quiet=False):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8')
        try:
            return json.loads(raw)
        except Exception:
            return {'code': e.code, 'raw': raw[:200]}
    except Exception as e:
        if not quiet:
            print('    [网络异常] %s %s -> %s' % (method, path, e))
        return {'code': -1, 'msg': str(e)}


def gen_scores(target, n):
    """围绕目标均分生成 n 个 1~5 的整数，别全给同一个数。"""
    out = []
    base = int(target)
    frac = target - base
    for _ in range(n):
        r = RNG.random()
        if r < frac * 0.78:
            s = base + 1
        elif r < 0.94:
            s = base
        elif r < 0.985:
            s = base - 1
        else:
            s = base + 2
        out.append(max(1, min(5, s)))
    return out


def pick_comments(scores, want):
    """挑 want 条评论，内容按各自分数挑对应口吻，且尽量不重复。"""
    pool = []
    for s in sorted(scores, reverse=True):
        bucket = COMMENTS.get(s) or COMMENTS.get(max(k for k in COMMENTS if k <= s))
        pool.append(RNG.choice(bucket))
    uniq = []
    for c in pool:
        if c not in uniq:
            uniq.append(c)
    return uniq[:want]


# ================================================================ 阶段1 账号
def stage_accounts():
    print('\n' + '=' * 66)
    print('阶段1  准备美食账号')
    print('=' * 66)
    users = []
    for uname, nick in ACCOUNTS:
        if DRY:
            users.append((uname, 'fake'))
            continue
        r = call('POST', '/api/account/auth/register',
                 {'username': uname, 'password': PASSWORD, 'nickname': nick})
        if r.get('code') == 200:
            users.append((uname, 'reg'))
        else:
            # 已存在就去登录 —— 脚本可以重复跑
            users.append((uname, 'dup:%s' % (r.get('message') or '')[:20]))
    # 统一登录拿 token
    tokens = []
    for uname, state in users:
        if DRY:
            tokens.append((uname, nick, 'TOKEN'))
            continue
        lg = call('POST', '/api/account/auth/login',
                  {'username': uname, 'password': PASSWORD})
        if lg.get('code') == 200:
            tokens.append((uname, state, lg['data']['token']))
        else:
            print('    [登录失败] %s -> %s' % (uname, lg.get('message')))
    print('  可用账号 %d / %d' % (len(tokens), len(ACCOUNTS)))
    return tokens


# ================================================================ 阶段2 生成
def stage_generate(tokens):
    print('\n' + '=' * 66)
    print('阶段2  AI 生成菜品并发布（这道最慢，本地小模型一道十几秒）')
    print('=' * 66)
    if DRY:
        print('  [dry] 计划生成 %d 道菜，跳过实际调用' % len(DISHES))
        return []
    pending = []
    for i, spec in enumerate(DISHES):
        token = tokens[i % len(tokens)][2]
        r = call('POST', '/api/dish/ai/dish', {
            'ingredientIds': spec['ings'],
            'freeIngredients': [],
            'tastes': spec['tastes'],
            'servings': spec['servings'],
        }, token=token)
        if r.get('code') == 200:
            tid = r['data']['taskId']
            pending.append((tokens[i % len(tokens)][0], tid, spec))
            print('  提交 %d/%d  taskId=%s  by %s'
                  % (i + 1, len(DISHES), tid, tokens[i % len(tokens)][0]))
        else:
            print('  [提交失败] %d -> %s' % (i + 1, r.get('message')))

    # 轮询：按账号各自的任务列表盯进度
    done = {}
    deadline = time.time() + 900
    owner_token = {u: t for u, _, t in tokens}
    while time.time() < deadline:
        alive = [(u, tid, s) for u, tid, s in pending if tid not in done]
        if not alive:
            break
        for uname, tid, spec in alive:
            tasks = call('GET', '/api/dish/ai/tasks',
                         token=owner_token[uname], quiet=True)
            for t in (tasks.get('data') or []):
                tid_s = str(t.get('taskId'))
                st = t.get('status')
                if tid_s == str(tid) and st in (2, 3, 4, 5):
                    done[tid] = (t.get('dishId'), st, spec)
                    mark = {2: '完成', 3: '失败', 4: '取消', 5: '中断'}.get(st, st)
                    print('  task %s -> %s%s' % (tid, mark, ('(%s)' % t.get('errorMsg'))[:60] if st != 2 else ''))
        time.sleep(3)

    published = []
    for tid, (dish_id, st, spec) in done.items():
        if st != 2 or not dish_id:
            continue
        # 生成者本人去发布
        uname = next(u for u, t, s in pending if str(t) == str(tid))
        pr = call('POST', '/api/dish/ai/dish/%s/publish' % dish_id,
                  token=owner_token[uname])
        if pr.get('code') == 200:
            published.append((str(dish_id), spec))
            print('  发布成功 dishId=%s' % dish_id)
        else:
            print('  [发布失败] dishId=%s -> %s' % (dish_id, pr.get('message')))
    print('  新增已发布菜品 %d 道' % len(published))
    return published


# ================================================================ 阶段3 互动
def stage_interact(tokens):
    print('\n' + '=' * 66)
    print('阶段3  打分 / 评论 / 收藏')
    print('=' * 66)

    # 返回结构是 RankPage(type, title, subtitle, total, page, size, items)，列表在 items
    # 注意：榜单接口不在网关白名单里，不带 token 会 401
    rank = call('GET', '/api/dish/ai/rank?type=latest&page=1&size=50',
                token=tokens[0][2])
    dishes = (rank.get('data') or {}).get('items') or []
    if not dishes:
        print('  榜单是空的，先确认有没有已发布的菜')
        return
    print('  参与互动的已发布菜品：%d 道' % len(dishes))

    # 给每道菜一个人气档位：按顺序轮转 + 随机，避免扎堆
    order = dishes[:]
    RNG.shuffle(order)
    tier_names = ['star'] * 4 + ['good'] * 5 + ['mid'] * 6 + ['cold'] * 4
    plan = []
    for i, d in enumerate(order):
        plan.append((d, tier_names[i % len(tier_names)]))
    RNG.shuffle(plan)

    for idx, (d, tier) in enumerate(plan):
        did = str(d.get('id') or d.get('dishId'))
        name = d.get('name')
        cfg = TIERS[tier]
        n_rate = RNG.randint(*cfg['raters'])
        target = RNG.uniform(*cfg['target'])
        n_col = RNG.randint(*cfg['collects'])
        n_com = RNG.randint(*cfg['comments'])

        pool = tokens[:]
        RNG.shuffle(pool)
        raters = pool[:n_rate]
        collectors = pool[:n_col]
        commenters = raters[:n_com]

        scores = gen_scores(target, len(raters))
        if DRY:
            print('  [dry] %-16s %-5s 评%d 目标%.1f 藏%d 论%d'
                  % (name, tier, len(raters), target, n_col, n_com))
            continue

        ok_r = ok_c = ok_f = 0
        for (uname, _, tok), sc in zip(raters, scores):
            if call('POST', '/api/dish/ai/dish/%s/rating' % did,
                    {'score': sc, 'cooked': True}, token=tok,
                    quiet=True).get('code') == 200:
                ok_r += 1
        texts = pick_comments(scores, len(commenters))
        for (uname, _, tok), text in zip(commenters, texts):
            if call('POST', '/api/dish/ai/dish/%s/comment' % did,
                    {'content': text}, token=tok, quiet=True).get('code') == 200:
                ok_c += 1
        for uname, _, tok in collectors:
            if call('POST', '/api/dish/ai/dish/%s/collect' % did,
                    token=tok, quiet=True).get('code') == 200:
                ok_f += 1

        real = None
        det = call('GET', '/api/dish/ai/dish/%s' % did, token=tokens[0][2], quiet=True)
        if det.get('code') == 200:
            v = det['data']
            # 详情视图把评分收在 rating 子对象里：{average, count}
            rv = v.get('rating') or {}
            real = '%.2f分/%d评/%d藏' % (rv.get('average') or 0,
                                        rv.get('count') or 0,
                                        v.get('collectCount') or 0)
        print('  %2d. %-16s %-5s 评%d藏%d论%d -> %s'
              % (idx + 1, name, tier, ok_r, ok_f, ok_c, real or '?'))


# ================================================================ 阶段4 报告
def stage_report(tokens):
    print('\n' + '=' * 66)
    print('阶段4  榜单验收')
    print('=' * 66)
    tok = tokens[0][2] if tokens else None
    for t, label in [('hot', '热门榜'), ('top', '高分榜'), ('latest', '最新榜')]:
        r = call('GET', '/api/dish/ai/rank?type=%s&page=1&size=8' % t, token=tok)
        lst = (r.get('data') or {}).get('items') or []
        print('\n  【%s】' % label)
        if not lst:
            print('    (空)')
        for i, d in enumerate(lst, 1):
            # DishCardView 没暴露 wilson 字段，这里打三样用户看得见的就够了
            print('   %d. %-16s %-4s分  %3s人评价  %3s收藏'
                  % (i, d.get('name'),
                     d.get('ratingAvg') if d.get('ratingAvg') else '-',
                     d.get('ratingCount') or 0,
                     d.get('collectCount') or 0))


if __name__ == '__main__':
    print('目标网关：%s' % GW)
    if DRY:
        print('*** dry run，只打印计划 ***')
    toks = []
    if START <= 1:
        toks = stage_accounts()
    if START <= 2:
        if START == 2 and not toks:
            toks = stage_accounts()
        stage_generate(toks)
    if START <= 3:
        if not toks:
            toks = stage_accounts()
        stage_interact(toks)
    if START <= 4:
        if not toks:
            toks = stage_accounts()
        stage_report(toks)
