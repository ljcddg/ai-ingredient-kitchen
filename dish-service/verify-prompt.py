"""验证系统提示词能否约束 gemma4:e2b 的输出格式。

把 prompts/dish-generate-system.txt 原样发给 Ollama，然后逐字段校验输出。
"""
import json
import time
import urllib.error
import urllib.request

SYS_FILE = r'D:\idea\work\project0914\dish-service\src\main\resources\prompts\dish-generate-system.txt'
SYS_PROMPT = open(SYS_FILE, encoding='utf-8').read()

CASES = [
    ('番茄、鸡蛋、葱', '家常', 2),
    ('土豆、牛肉', '红烧', 3),
    ('豆腐、青菜、香菇', '清淡', 1),
]

PASS, FAIL = [], []


def ck(label, ok, detail=''):
    (PASS if ok else FAIL).append(label)
    print('    [%s] %-34s %s' % ('PASS' if ok else 'FAIL', label, detail))


def call(ingredients, taste, servings):
    user = '食材：%s；口味：%s；人数：%d 人' % (ingredients, taste, servings)
    body = {
        'model': 'gemma4:e2b',
        'messages': [
            {'role': 'system', 'content': SYS_PROMPT},
            {'role': 'user', 'content': user},
        ],
        'response_format': {'type': 'json_object'},
        'temperature': 0.7,
        'stream': False,
    }
    req = urllib.request.Request(
        'http://127.0.0.1:11434/v1/chat/completions',
        data=json.dumps(body).encode('utf-8'), method='POST')
    req.add_header('Content-Type', 'application/json')
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=280) as r:
        res = json.loads(r.read().decode('utf-8'))
    return (res['choices'][0]['message']['content'] or '').strip(), time.time() - t0


for idx, (ing, taste, servings) in enumerate(CASES, 1):
    print('=' * 66)
    print('用例 %d：食材=%s / 口味=%s / 人数=%d' % (idx, ing, taste, servings))
    try:
        raw, cost = call(ing, taste, servings)
    except Exception as e:
        print('  调用失败:', repr(e))
        FAIL.append('用例%d 调用' % idx)
        continue

    print('  耗时 %.1f 秒，原始输出 %d 字符' % (cost, len(raw)))
    print('  第一个字符 = %r  最后一个字符 = %r  （要求 { 和 }）'
          % (raw[:1], raw[-1:]))
    ck('输出无 markdown 代码块', '```' not in raw)

    try:
        obj = json.loads(raw)
    except Exception as e:
        print('  JSON 解析失败:', e)
        print('  --- 原始输出 ---')
        print(raw[:600])
        FAIL.append('用例%d JSON' % idx)
        continue
    ck('JSON 可解析', True)

    name = obj.get('name')
    summary = obj.get('summary') or ''
    prep_min = obj.get('prepTimeMin')
    cost_min = obj.get('cookTimeMin')
    diff = obj.get('difficulty')
    spicy = obj.get('spicyLevel')
    steps = obj.get('steps') or []
    ings = obj.get('ingredients') or []
    equip = obj.get('equipment') or []
    nutri = obj.get('nutritionPerServing') or {}
    diet = obj.get('dietTags') or []
    subs = obj.get('substitutes') or []
    tips = obj.get('chefTips') or []

    print()
    print('  菜名       :', name)
    print('  简介(%d字)  :' % len(summary), summary)
    print('  备菜/下锅   :', prep_min, '/', cost_min, '分钟')
    print('  难度/辣度   :', diff, '/', spicy)
    print('  厨具       :', equip)
    print('  每人份营养  :', nutri)
    print('  饮食标签   :', diet)
    print('  步骤 %d 条:' % len(steps))
    for s in steps:
        print('      -(%d字) %s' % (len(s), s))
    print('  食材       :', [(i.get('name'), i.get('amountGram')) for i in ings])
    print('  替代方案 %d 条:' % len(subs))
    for s in subs:
        print('      -(%d字) %s' % (len(s), s))
    print('  大厨建议 %d 条:' % len(tips))
    for t in tips:
        print('      -(%d字) %s' % (len(t), t))
    print()

    ck('name 非空且<=12字', bool(name) and len(name) <= 12, 'len=%s' % (len(name) if name else 0))
    ck('summary 20~40字', 20 <= len(summary) <= 40, 'len=%d' % len(summary))
    ck('prepTimeMin 整数 2~60',
       isinstance(prep_min, int) and 2 <= prep_min <= 60, 'val=%r' % prep_min)
    ck('cookTimeMin 整数 5~120',
       isinstance(cost_min, int) and 5 <= cost_min <= 120, 'val=%r' % cost_min)
    ck('difficulty in 1/2/3', diff in (1, 2, 3), 'val=%r' % diff)
    ck('spicyLevel 整数 0~3', isinstance(spicy, int) and 0 <= spicy <= 3, 'val=%r' % spicy)
    ck('steps 4~8 条', 4 <= len(steps) <= 8, 'len=%d' % len(steps))
    ck('steps 每条 15~50字',
       all(15 <= len(s) <= 50 for s in steps),
       '各条字数=%s' % [len(s) for s in steps])
    ck('chefTips 2~3 条', 2 <= len(tips) <= 3, 'len=%d' % len(tips))
    ck('equipment 1~3 项', 1 <= len(equip) <= 3, 'val=%s' % equip)
    # 调料方案 A：AI 只负责说「需要哪几样调料」，用量由系统按标准配方补全
    season = obj.get('seasonings') or []
    ck('seasonings 1~4 项且为字符串',
       1 <= len(season) <= 4 and all(isinstance(x, str) for x in season),
       'val=%s' % season)
    ck('dietTags 1~3 个', 1 <= len(diet) <= 3, 'val=%s' % diet)
    ck('substitutes <=2 条', len(subs) <= 2, 'len=%d' % len(subs))
    ck('ingredients amountGram 均为正整数',
       all(isinstance(i.get('amountGram'), int) and i.get('amountGram') > 0 for i in ings))

    # 营养字段：这一次是【要求 AI 输出】的，所以断言它必须齐全、类型正确、落在合理区间
    NEED_KEYS = {'calories', 'proteinGram', 'fatGram', 'carbGram', 'fiberGram', 'sodiumMg'}
    ck('营养字段齐全', NEED_KEYS.issubset(set(nutri.keys())),
       '缺=%s' % (sorted(NEED_KEYS - set(nutri.keys())) or '无'))
    ck('营养值均为整数',
       all(isinstance(nutri.get(k), int) for k in NEED_KEYS),
       'val=%s' % {k: nutri.get(k) for k in sorted(NEED_KEYS)})
    cal = nutri.get('calories')
    ck('calories 落在 100~800',
       isinstance(cal, int) and 100 <= cal <= 800, 'val=%r' % cal)
    na = nutri.get('sodiumMg')
    ck('sodiumMg 落在 200~1500',
       isinstance(na, int) and 200 <= na <= 1500, 'val=%r' % na)

    # 用量合理性 —— 实测发现 AI 会给出「料酒 300g」这种夸张值，
    # 而营养是「食材主数据 × 用量」算出来的，用量失真会直接让营养数据变成错的
    limits = [('盐', 10), ('糖', 30), ('酱油', 30), ('生抽', 30), ('老抽', 30),
              ('料酒', 30), ('醋', 30), ('蚝油', 30)]
    problems = []
    for i in ings:
        nm = str(i.get('name') or '')
        g = i.get('amountGram')
        if not isinstance(g, int):
            continue
        if any(w in nm for w in ('水', '高汤', '清汤')):
            problems.append('无营养液体不应入列: %s %sg' % (nm, g))
            continue
        cap = 50 if nm.endswith('油') else 800
        for key, lim in limits:
            if key in nm:
                cap = min(cap, lim)
        if g > cap:
            problems.append('%s %sg > 上限 %sg' % (nm, g, cap))
    ck('用量合理（调料不夸张）', not problems, '问题=%s' % (problems or '无'))

    # 关键：用户给的全部食材必须都出现
    got_names = ' '.join(str(i.get('name') or '') for i in ings)
    missing = [x for x in ing.split('、') if x not in got_names]
    ck('覆盖全部输入食材', not missing, '缺失=%s' % (missing or '无'))

    # 提示词明确要求「不要把调料写进 ingredients」，看模型是否遵守
    SEASONINGS = ('盐', '糖', '酱油', '生抽', '老抽', '料酒', '醋', '蚝油', '食用油')
    season_in_ings = [str(i.get('name')) for i in ings
                      if any(k in str(i.get('name')) for k in SEASONINGS)]
    ck('未把调料列进 ingredients', not season_in_ings,
       'found=%s' % (season_in_ings or '无'))
    print()

print('=' * 66)
print('汇总：PASS=%d  FAIL=%d' % (len(PASS), len(FAIL)))
if FAIL:
    print('失败项：')
    for f in FAIL:
        print('   -', f)
