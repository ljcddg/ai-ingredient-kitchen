#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
人工评价工具（说明文档 §三 的验收要求："记录约束违反率、重试成功率和人工可操作性评分。
人工评价不能只由生成模型自身完成，也不能用格式通过率替代实际可制作性。"）

这个脚本解决的是"人工评价没法做"里的**准备工作**，不是替代人来打分：

    build  从生成记录表里挑出**成功生成**的菜品，生成一份**盲评材料**：
           - blind-sheet.md      给评价者看的（有菜名/配料/步骤，没有任何模型与策略信息）
           - ratings-template.csv 评价者填分的模板（每个评价者一份，复制成 ratings-<评价者>.csv）
           - answer-key.csv      **不能给评价者看**：序号 → 任务/菜品/策略/校验结果
    score  把填好的 ratings-*.csv 汇总成 human-eval-report.md

为什么要"盲"：这里要比较的是"加入参考菜谱"和"直接生成"两条策略。
如果评价者知道哪份是加了检索增强的，很容易（有意或无意地）偏向它。
所以序号是打乱的、策略信息只留在 answer-key 里。

一个必须一起交代的口径：**提示词版本不同的记录不能放在一起比较**。
改一句系统提示词，整套输出都变了（本项目的 prompt_version = 系统提示词 MD5 前 8 位），
所以脚本会按 prompt_version 分组，跨版本只警告不建议合并。

用法：
    python es/eval/human-eval-kit.py build [--limit 30] [--out es/eval/human-eval] [--seed 20261002]
    python es/eval/human-eval-kit.py score --dir es/eval/human-eval
"""

import argparse
import csv
import json
import os
import random
import statistics
import subprocess
import sys

# 本机控制台是 GBK：遇到编码不了的字符不要让脚本崩掉（原先 ⚠️ 就崩在 print 上）
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(errors='replace')
    except Exception:
        pass

MYSQL_EXE = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'

# 两条策略臂。reference_dish_ids 非空 = 生成时注入了参考菜谱
ARM_REF = 'A-参考增强'
ARM_PLAIN = 'B-直接生成'

RATING_FIELDS = [
    ('operability', '可操作性(1-5)', '按这份菜谱真的能做出这道菜吗（步骤是否可执行、火候/时间是否够清楚）'),
    ('clarity', '步骤清晰度(1-5)', '不看别的资料，能照着一步步做完吗'),
    ('amount', '用量合理性(1-5)', '配料用量是否符合家常经验（含调料克重）'),
    ('constraint', '是否符合用户条件(是/否)', '必用食材用上了、忌口没出现、时长与厨具对得上'),
    ('would_cook', '是否会真的做(是/否)', '你是这个食材条件的用户，会照着做吗'),
    ('comment', '备注', '自由填写：哪里最不像人写的、哪里最离谱'),
]

# CSV 里的列名是上面的人类可读标签，代码里用英文 id 取列 —— 别把两者混用
COL = {fid: label for fid, label, _ in RATING_FIELDS}


def mysql(sql):
    """直连库跑一条 SQL，返回每行按 \\t 拆开的列表。

    失败必须把 stderr 原文打出来 —— 辅助函数把错误吞掉是踩过的坑。
    """
    r = subprocess.run([MYSQL_EXE, '-uroot', '-proot', DB, '-N', '-B', '-e', sql],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        msg = (r.stderr or '').strip()
        # mysql 客户端因命令行密码会往 stderr 打一句 warning，它不是错误
        if 'Warning] Using a password' not in msg:
            print('[mysql 失败] %s' % msg[:400])
            return []
    return [ln.split('\t') for ln in (r.stdout or '').splitlines() if ln.strip()]


def sql_quote(s):
    return "'" + str(s).replace('\\', '\\\\').replace("'", "''") + "'"


def null_str(v):
    """mysql -N -B 把 NULL 打成字面量 'NULL'，不是空串 —— 不处理会把"无参考"错判成"有参考"。"""
    if v is None:
        return ''
    v = str(v).strip()
    return '' if v in ('', 'NULL', '\\N') else v


def fetch_items(limit):
    """取"成功生成且菜品还在"的记录（status=2 成功；菜品未删除）。"""
    rows = mysql(
        "SELECT r.task_id, r.dish_id, r.reference_dish_ids, r.model, r.prompt_version, "
        "       r.attempts, r.cost_ms, r.format_ok, r.constraint_ok, r.create_time, "
        "       d.name, d.base_serving, d.cook_time_min, d.difficulty, d.taste_tag, "
        "       d.equipment, d.seasonings, d.chef_tips, d.step_json, d.nutrition_source "
        "FROM t_ai_generation_record r JOIN t_dish d ON d.id = r.dish_id "
        "WHERE r.status = 2 AND r.dish_id IS NOT NULL AND d.deleted = 0 "
        "ORDER BY r.create_time DESC LIMIT %d;" % int(limit))
    items = []
    for r in rows:
        if len(r) < 20:
            continue
        items.append({
            'task_id': r[0],
            'dish_id': r[1],
            'arm': ARM_REF if null_str(r[2]) else ARM_PLAIN,
            'reference_dish_ids': null_str(r[2]),
            'model': null_str(r[3]),
            'prompt_version': null_str(r[4]),
            'attempts': null_str(r[5]),
            'cost_ms': null_str(r[6]),
            'format_ok': null_str(r[7]),
            'constraint_ok': null_str(r[8]),
            'create_time': null_str(r[9]),
            'name': null_str(r[10]),
            'servings': null_str(r[11]),
            'cook_time_min': null_str(r[12]),
            'difficulty': null_str(r[13]),
            'taste_tag': null_str(r[14]),
            'equipment': null_str(r[15]),
            'seasonings': null_str(r[16]),
            'chef_tips': null_str(r[17]),
            'step_json': null_str(r[18]),
            'nutrition_source': null_str(r[19]),
        })

    if not items:
        return items

    ids = ','.join(i['dish_id'] for i in items)
    ing = {}
    for r in mysql("SELECT dish_id, ingredient_name, amount, unit, amount_gram, main_flag "
                   "FROM t_dish_ingredient WHERE dish_id IN (%s) ORDER BY dish_id, sort_no;" % ids):
        if len(r) < 6:
            continue
        ing.setdefault(r[0], []).append({
            'name': r[1], 'amount': r[2], 'unit': r[3], 'gram': r[4], 'main': r[5] == '1',
        })
    for it in items:
        it['ingredients'] = ing.get(it['dish_id'], [])
    return items


def jload(text):
    """把 MySQL 里取出来的 JSON 文本解析成 Python 对象（NULL / 空串 → 空列表）。"""
    if not text or text == 'NULL':
        return []
    try:
        v = json.loads(text)
    except Exception:
        return []
    return v if isinstance(v, (list, dict)) else []


def step_lines(step_json):
    out = []
    for s in jload(step_json):
        if isinstance(s, dict):
            out.append(str(s.get('text') or s.get('desc') or '').strip())
        else:
            out.append(str(s).strip())
    return [x for x in out if x]


def render_sheet(items, key_rows, seed, out_dir):
    lines = []
    lines.append('# 生成结果人工评价（盲评材料）')
    lines.append('')
    lines.append('> 本表由 `es/eval/human-eval-kit.py build` 生成，**序号已打乱**，')
    lines.append('> 每道菜的"是哪条策略生成的"只记录在不给评价者看的 `answer-key.csv` 里。')
    lines.append('> 随机种子 `%s`（同一种子可复现同一份材料）。' % seed)
    lines.append('')
    lines.append('## 怎么评')
    lines.append('')
    lines.append('1. 复制一份 `ratings-template.csv` 成 `ratings-<你的名字>.csv`（**每个评价者一份**，不要共用）。')
    lines.append('2. 对着下面的菜谱逐项打分；`item_id` 一列从模板里已经填好，不要改。')
    lines.append('3. 打分只看"照着这张菜谱能不能做出来"，**不要猜哪份是 AI 加的参考**，也不要讨论。')
    lines.append('4. 全部评完把 CSV 放回同一目录，运行：')
    lines.append('')
    lines.append('   ```')
    lines.append('   python es/eval/human-eval-kit.py score --dir %s' % out_dir.replace('\\', '/'))
    lines.append('   ```')
    lines.append('')
    lines.append('5. **至少 2 个人独立评**（脚本会算评价者之间的一致度）；评完再看 `answer-key.csv`。')
    lines.append('')
    lines.append('---')
    lines.append('')
    for k, it in zip(key_rows, items):
        lines.append('## %s · %s' % (k['item_id'], it['name']))
        lines.append('')
        meta = []
        if it['servings']:
            meta.append('份数：%s 人份' % it['servings'])
        if it['cook_time_min']:
            meta.append('耗时：%s 分钟' % it['cook_time_min'])
        if it['difficulty']:
            meta.append('难度：%s' % it['difficulty'])
        if it['taste_tag']:
            meta.append('口味：%s' % it['taste_tag'])
        if meta:
            lines.append('- ' + ' ｜ '.join(meta))
        eq = jload(it['equipment'])
        if eq:
            lines.append('- 厨具：%s' % '、'.join(str(x) for x in eq))
        lines.append('')
        lines.append('**配料**')
        lines.append('')
        for g in it['ingredients']:
            amount = '%s%s' % (g['amount'], g['unit'] or '')
            if g['gram'] and g['gram'] != 'NULL':
                amount += '（约 %sg）' % g['gram']
            lines.append('- %s %s%s' % (g['name'], amount, '（主料）' if g['main'] else ''))
        seas = jload(it['seasonings'])
        if seas:
            lines.append('- 调料：%s' % '、'.join(str(x) for x in seas))
        lines.append('')
        lines.append('**步骤**')
        lines.append('')
        for i, s in enumerate(step_lines(it['step_json']), 1):
            lines.append('%d. %s' % (i, s))
        tips = jload(it['chef_tips'])
        if tips:
            lines.append('')
            lines.append('**大厨建议**')
            lines.append('')
            for t in tips:
                lines.append('- %s' % (t if isinstance(t, str) else json.dumps(t, ensure_ascii=False)))
        lines.append('')
        lines.append('---')
        lines.append('')
    return '\n'.join(lines)


def write_template(path, item_ids):
    # utf-8-sig：Excel 直接双击打开中文 CSV 时不加 BOM 会乱码
    with open(path, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.writer(f)
        w.writerow(['item_id', 'rater'] + [c for _, c, _ in RATING_FIELDS])
        for iid in item_ids:
            w.writerow([iid, ''] + [''] * len(RATING_FIELDS))


def write_answer_key(path, key_rows, seed):
    with open(path, 'w', newline='', encoding='utf-8-sig') as f:
        w = csv.writer(f)
        w.writerow(['item_id', '策略', 'task_id', 'dish_id', 'model', 'prompt_version',
                    'attempts', 'cost_ms', 'format_ok', 'constraint_ok', 'reference_dish_ids',
                    'create_time', '随机种子'])
        for k in key_rows:
            w.writerow([k['item_id'], k['arm'], k['task_id'], k['dish_id'], k['model'],
                        k['prompt_version'], k['attempts'], k['cost_ms'], k['format_ok'],
                        k['constraint_ok'], k['reference_dish_ids'], k['create_time'], seed])


def cmd_build(args):
    items = fetch_items(args.limit)
    if not items:
        print('生成记录里没有"成功生成且菜品还在"的记录，先跑几次生成再来。')
        return 1

    versions = {}
    for it in items:
        versions.setdefault(it['prompt_version'] or '(未知)', 0)
        versions[it['prompt_version'] or '(未知)'] += 1
    if len(versions) > 1:
        print('[注意] 这批记录跨了 %d 个提示词版本：%s' % (len(versions), versions))
        print('   提示词改一句，输出整体就变了 —— 跨版本比较**无效**，')
        print('   要么只用其中一个版本，要么分成两组分别评。')

    rng = random.Random(args.seed)
    order = list(range(len(items)))
    rng.shuffle(order)
    items = [items[i] for i in order]
    key_rows = [{'item_id': 'S%02d' % (n + 1), **{k: it[k] for k in
                 ('arm', 'task_id', 'dish_id', 'model', 'prompt_version', 'attempts',
                  'cost_ms', 'format_ok', 'constraint_ok', 'reference_dish_ids', 'create_time')}}
                for n, it in enumerate(items)]

    os.makedirs(args.out, exist_ok=True)
    sheet = os.path.join(args.out, 'blind-sheet.md')
    tmpl = os.path.join(args.out, 'ratings-template.csv')
    key = os.path.join(args.out, 'answer-key.csv')
    with open(sheet, 'w', encoding='utf-8') as f:
        f.write(render_sheet(items, key_rows, args.seed, args.out))
    write_template(tmpl, [k['item_id'] for k in key_rows])
    write_answer_key(key, key_rows, args.seed)

    arms = {}
    for k in key_rows:
        arms[k['arm']] = arms.get(k['arm'], 0) + 1
    print('已生成 %d 条盲评材料：' % len(key_rows))
    print('  %s' % sheet)
    print('  %s（评价者每人复制一份改名 ratings-<名字>.csv）' % tmpl)
    print('  %s（**别给评价者看**）' % key)
    print('两条策略臂的条数：%s' % arms)
    if min(arms.values()) < 5 if arms else True:
        print('[注意] 某一臂条数不足 5，只能当定性观察，做不了统计结论。')
    return 0


def read_ratings(d):
    """读出目录下所有 ratings-*.csv（评价者填好的），返回 {item_id: {rater: row}}"""
    data = {}
    files = [f for f in sorted(os.listdir(d)) if f.startswith('ratings-') and f.endswith('.csv')
             and f != 'ratings-template.csv']
    raters = []
    for fn in files:
        with open(os.path.join(d, fn), newline='', encoding='utf-8-sig') as f:
            for row in csv.DictReader(f):
                iid = (row.get('item_id') or '').strip()
                if not iid:
                    continue
                rater = (row.get('rater') or fn.replace('ratings-', '').replace('.csv', '')).strip()
                if rater not in raters:
                    raters.append(rater)
                data.setdefault(iid, {})[rater] = row
    return data, raters, files


def to_int(v):
    try:
        n = int(float(str(v).strip()))
        return n if 1 <= n <= 5 else None
    except Exception:
        return None


def weighted_kappa(pairs_a, pairs_b, maxv=5, weights='linear'):
    """有序量表的加权 Cohen's kappa（两名评价者）。

    只用 1~5 这两列的打分；返回 (kappa, 一致率)。样本不足返回 (None, None)。
    """
    n = len(pairs_a)
    if n == 0:
        return None, None
    W = [[0.0] * (maxv + 1) for _ in range(maxv + 1)]
    for i in range(1, maxv + 1):
        for j in range(1, maxv + 1):
            d = abs(i - j)
            W[i][j] = d if weights == 'linear' else (d * d)
    obs = [[0] * (maxv + 1) for _ in range(maxv + 1)]
    for a, b in zip(pairs_a, pairs_b):
        obs[a][b] += 1
    row = [sum(obs[i]) for i in range(maxv + 1)]
    col = [sum(obs[i][j] for i in range(maxv + 1)) for j in range(maxv + 1)]
    num = sum(W[i][j] * obs[i][j] for i in range(1, maxv + 1) for j in range(1, maxv + 1)) / n
    den = sum(W[i][j] * row[i] * col[j] for i in range(1, maxv + 1) for j in range(1, maxv + 1)) / (n * n)
    agree = sum(obs[i][i] for i in range(1, maxv + 1)) / n
    if den == 0:
        return None, agree
    return 1 - num / den, agree


def cmd_score(args):
    data, raters, files = read_ratings(args.dir)
    if not data:
        print('没找到填好的 ratings-*.csv（目录 %s）。' % args.dir)
        print('先把 ratings-template.csv 复制成 ratings-<名字>.csv 再打分。')
        return 1

    key_path = os.path.join(args.dir, 'answer-key.csv')
    if not os.path.exists(key_path):
        print('缺少 answer-key.csv，无法知道每条材料属于哪条策略。')
        return 1
    arms = {}
    with open(key_path, newline='', encoding='utf-8-sig') as f:
        for row in csv.DictReader(f):
            arms[row['item_id'].strip()] = row

    print('评价者 %d 人：%s' % (len(raters), '、'.join(raters)))
    print('覆盖材料 %d 条，策略未知的材料 %d 条' % (len(data), sum(1 for i in data if i not in arms)))

    report = []
    report.append('# 生成结果人工评价报告')
    report.append('')
    report.append('- 评价者：%s（共 %d 人）' % ('、'.join(raters), len(raters)))
    report.append('- 材料：%d 条，来自 `answer-key.csv` 的策略标注' % len(data))
    report.append('- 表内数值为**评分者均分**；1-5 分项越高越好')
    report.append('')

    # ---- 按策略臂汇总 ----
    by_arm = {}
    for iid, row in data.items():
        arm = (arms.get(iid) or {}).get('策略', '未知')
        by_arm.setdefault(arm, {'operability': [], 'clarity': [], 'amount': [],
                                'constraint_yes': 0, 'constraint_n': 0,
                                'would_yes': 0, 'would_n': 0})
        for rater, r in row.items():
            for f in ('operability', 'clarity', 'amount'):
                v = to_int(r.get(COL[f]))
                if v is not None:
                    by_arm[arm][f].append(v)
            c = (r.get(COL['constraint']) or '').strip().lower()
            if c in ('是', 'y', 'yes', '1', 'true'):
                by_arm[arm]['constraint_yes'] += 1
                by_arm[arm]['constraint_n'] += 1
            elif c in ('否', 'n', 'no', '0', 'false'):
                by_arm[arm]['constraint_n'] += 1
            w = (r.get(COL['would_cook']) or '').strip().lower()
            if w in ('是', 'y', 'yes', '1', 'true'):
                by_arm[arm]['would_yes'] += 1
                by_arm[arm]['would_n'] += 1
            elif w in ('否', 'n', 'no', '0', 'false'):
                by_arm[arm]['would_n'] += 1

    report.append('## 一、按策略臂')
    report.append('')
    report.append('| 策略 | 材料数 | 可操作性(均分) | 步骤清晰度 | 用量合理性 | 符合用户条件 | 会真的做 |')
    report.append('|---|---|---|---|---|---|---|')
    for arm, s in sorted(by_arm.items()):
        cnt = sum(1 for i in data if (arms.get(i) or {}).get('策略') == arm)
        def mean(x):
            return '%.2f' % statistics.mean(x) if x else '—'
        def rate(y, n):
            return '%d/%d（%.0f%%）' % (y, n, 100.0 * y / n) if n else '—'
        report.append('| %s | %d | %s | %s | %s | %s | %s |' % (
            arm, cnt, mean(s['operability']), mean(s['clarity']), mean(s['amount']),
            rate(s['constraint_yes'], s['constraint_n']),
            rate(s['would_yes'], s['would_n'])))
    report.append('')

    # ---- 同一条材料两臂对比（只有两臂都有样本时才有意义）----
    per_item = {}
    for iid, row in data.items():
        vals = [to_int(r.get(COL['operability'])) for r in row.values()]
        vals = [v for v in vals if v is not None]
        if vals:
            per_item[iid] = statistics.mean(vals)
    arms_present = sorted(by_arm.keys())
    if len(arms_present) == 2:
        a_vals = [v for iid, v in per_item.items() if (arms.get(iid) or {}).get('策略') == arms_present[0]]
        b_vals = [v for iid, v in per_item.items() if (arms.get(iid) or {}).get('策略') == arms_present[1]]
        report.append('## 二、两条策略的可操作性对比')
        report.append('')
        report.append('| 指标 | %s | %s |' % (arms_present[0], arms_present[1]))
        report.append('|---|---|---|')
        def m(x):
            return '%.2f（n=%d）' % (statistics.mean(x), len(x)) if x else '—'
        report.append('| 可操作性均分 | %s | %s |' % (m(a_vals), m(b_vals)))
        report.append('| 中位数 | %s | %s |' % (
            ('%.1f' % statistics.median(a_vals)) if a_vals else '—',
            ('%.1f' % statistics.median(b_vals)) if b_vals else '—'))
        report.append('')
        small = min(len(a_vals), len(b_vals)) < 5
        report.append('> %s' % ('样本量 < 5（每臂 %d / %d），**只能当定性观察**，不要写成"提升了 X%%"。'
                                % (len(a_vals), len(b_vals)) if small else
                                '样本量各 %d / %d，仍建议报告评分者一致度而不是只报均值。'
                                % (len(a_vals), len(b_vals))))
        report.append('')
    else:
        report.append('## 二、两条策略的可操作性对比')
        report.append('')
        report.append('只有一臂有材料（%s），无法对比 —— 需要分别生成"参考增强"和"直接生成"两组。'
                      % '、'.join(arms_present))
        report.append('')

    # ---- 评价者一致度 ----
    report.append('## 三、评价者之间的一致性')
    report.append('')
    if len(raters) < 2:
        report.append('只有 1 名评价者，**算不出一致度**。说明文档要求至少 2 人独立评价，')
        report.append('一个人评等于用主观分替代了客观分，说服力有限。')
    else:
        report.append('| 评价者对 | 可操作性 加权 kappa | 一致率 | 步骤清晰度 加权 kappa |')
        report.append('|---|---|---|---|')
        for i in range(len(raters)):
            for j in range(i + 1, len(raters)):
                ra, rb = raters[i], raters[j]
                cells = []
                for field in ('operability', 'clarity'):
                    pa, pb = [], []
                    for iid, row in data.items():
                        if ra in row and rb in row:
                            va, vb = to_int(row[ra].get(COL[field])), to_int(row[rb].get(COL[field]))
                            if va and vb:
                                pa.append(va)
                                pb.append(vb)
                    k, agree = weighted_kappa(pa, pb)
                    cells.append('%.2f' % k if k is not None else '—')
                    if field == 'operability':
                        cells.append('%.0f%%' % (100 * agree) if agree is not None else '—')
                report.append('| %s vs %s | %s | %s | %s |' % (ra, rb, cells[0], cells[1], cells[2]))
        report.append('')
        report.append('> 读法：加权 kappa > 0.6 算"基本一致"，0.4~0.6 是"中等"，')
        report.append('> 低于 0.4 要先怀疑**评分标准本身**没讲清楚（比如"可操作性"到底看成什么），')
        report.append('> 而不是直接说评价者不认真。')
    report.append('')
    report.append('---')
    report.append('')
    report.append('## 四、这份报告不能说明什么')
    report.append('')
    report.append('- 分数来自真人主观判断，**样本量小的时候不能当统计结论**；')
    report.append('- 评价者是熟人/同学，存在同源偏差，论文里要如实写明评价者构成；')
    report.append('- 它**替代不了**客观指标（格式/约束通过率那些在 `results-generation.json` 里），')
    report.append('  两者只能互相印证：客观全过而人工分很低，说明校验器漏了真正要管的东西。')
    report.append('')

    out = os.path.join(args.dir, 'human-eval-report.md')
    with open(out, 'w', encoding='utf-8') as f:
        f.write('\n'.join(report))
    print('报告已写出：%s' % out)
    print('\n'.join(report[:8]))
    return 0


def main():
    ap = argparse.ArgumentParser(description='生成结果人工评价工具（盲评材料 + 汇总报告）')
    sub = ap.add_subparsers(dest='cmd', required=True)

    b = sub.add_parser('build', help='从生成记录生成盲评材料')
    b.add_argument('--limit', type=int, default=30, help='最多取多少条成功生成（默认 30）')
    b.add_argument('--out', default=os.path.join('es', 'eval', 'human-eval'), help='输出目录')
    b.add_argument('--seed', type=int, default=20261002, help='打乱序号用的随机种子（可复现）')
    b.set_defaults(func=cmd_build)

    s = sub.add_parser('score', help='汇总填好的评分表')
    s.add_argument('--dir', default=os.path.join('es', 'eval', 'human-eval'), help='评分表所在目录')
    s.set_defaults(func=cmd_score)

    args = ap.parse_args()
    return args.func(args)


if __name__ == '__main__':
    sys.exit(main())
