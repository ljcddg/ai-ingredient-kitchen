# -*- coding: utf-8 -*-
"""
补齐人工评价的 B 臂样本（"直接生成"，即不注入参考菜谱）。

## 为什么需要这个脚本

`es/eval/human-eval-kit.py` 要比较两条策略：

    A 臂 = 开了检索增强（`ai.generate.enhance-enabled: true`）→ 记录里 reference_dish_ids 非空
    B 臂 = 直接生成（关掉增强）                             → reference_dish_ids 为空

但**现有记录不能直接拿来对比**：A 臂全在提示词版本 `076b89e0`（加了参考菜谱规则之后），
B 臂全在 `1379896b`（之前）。两臂差的既是策略、也是提示词版本 —— 混淆变量没排掉，
分组统计就不等于对照实验。所以 B 臂必须在**当前提示词版本**下重新生成。

## 它做/不做什么

做：预检（Nacos 开关真的关了吗 / Ollama 在跑吗）→ 提交 N 个生成任务 → 等终态 →
回读生成记录，确认这批真的落成 B 臂（`reference_dish_ids` 为空）且提示词版本一致。

**不做**：不替你改 Nacos、不重启服务 —— 那两步要停正在跑的 dish-service，
脚本擅自做太危险。下面第 1、2 步手工执行。

## 用法（完整三步）

    # 1) 关掉参考增强（先备份 Nacos！）
    python nacos-config/backup-nacos.py
    #    把 dish-service.yaml 里 ai.generate.enhance-enabled 改成 false 推上去
    #    （推送命令见 README「评估与验证」节）

    # 2) 重启 dish-service 让配置生效（Nacos 改了不重启不生效）

    # 3) 跑本脚本补样本
    python es/eval/fill-arm-b.py 5

跑完记得**把开关改回 true 并再重启一次**，脚本最后会再提醒一遍。
"""
import json
import os
import random
import socket
import string
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

GW = 'http://127.0.0.1:7000'
NACOS = 'http://127.0.0.1:8848'
HERE = os.path.dirname(os.path.abspath(__file__))
TESTSET = os.path.join(HERE, 'testset-ranking.json')
MYSQL = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
DB = 'project0914'
TERMINAL = {2, 3, 4, 5}


def call(method, path, body=None, token=None, timeout=120):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(GW + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json;charset=UTF-8')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode('utf-8', errors='replace') or '{}')
    except Exception as e:
        return 0, {'error': str(e)}


def mysql_query(sql):
    """直连库查询；stderr 不能丢 —— 助手函数把错误吞掉是踩过的坑。"""
    with tempfile.NamedTemporaryFile(delete=False, suffix='.tsv') as tmp:
        path = tmp.name
    try:
        with open(path, 'wb') as out:
            r = subprocess.run([MYSQL, '-uroot', '-proot', '--default-character-set=utf8mb4',
                                '-D', DB, '-N', '-B', '-e', sql],
                               stdout=out, stderr=subprocess.PIPE, check=False)
        if r.returncode != 0:
            msg = (r.stderr or b'').decode('utf-8', 'replace')
            if 'Using a password' not in msg:
                print('[mysql 失败] %s' % msg[:300])
        with open(path, encoding='utf-8', errors='replace') as f:
            return [line.split('\t') for line in f.read().splitlines() if line.strip()]
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def nacos_enhance_enabled():
    q = urllib.parse.urlencode({'dataId': 'dish-service.yaml', 'group': 'DEFAULT_GROUP', 'tenant': ''})
    try:
        with urllib.request.urlopen(NACOS + '/nacos/v1/cs/configs?' + q, timeout=10) as resp:
            text = resp.read().decode('utf-8', 'replace')
    except Exception as e:
        return None, '读 Nacos 失败：%s' % e
    for line in text.splitlines():
        s = line.strip()
        if s.startswith('enhance-enabled:'):
            return s.split(':', 1)[1].strip().lower() in ('true', '1', 'yes'), text
    return None, text


def port_open(port):
    s = socket.socket()
    s.settimeout(1.5)
    try:
        return s.connect_ex(('127.0.0.1', port)) == 0
    finally:
        s.close()


def login():
    username = 'armb' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=6))
    password = 'pw' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    call('POST', '/api/account/auth/register',
         {'username': username, 'password': password, 'nickname': 'B 臂样本'})
    st, body = call('POST', '/api/account/auth/login',
                    {'username': username, 'password': password})
    return username, (body.get('data') or {}).get('token')


def submit(token, query):
    st, body = call('POST', '/api/dish/ai/dish', {
        'ingredientIds': query['ingredientIds'],
        'freeIngredients': query['freeIngredients'],
        'tastes': [],
        'servings': 2,
    }, token=token)
    return (body.get('data') or {}).get('taskId'), body


def wait_terminal(token, task_ids, timeout=3600):
    pending = {str(t) for t in task_ids}
    done = {}
    deadline = time.time() + timeout
    while pending and time.time() < deadline:
        st, body = call('GET', '/api/dish/ai/tasks', token=token)
        for t in (body.get('data') or []):
            tid = str(t.get('taskId'))
            if tid in pending and t.get('status') in TERMINAL:
                done[tid] = t
                pending.discard(tid)
                print('  任务 %s → status=%s 耗时=%sms' % (tid, t.get('status'), t.get('costMs')))
        if pending:
            time.sleep(5)
    return done, pending


def main():
    want = int(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].isdigit() else 5
    force = '--force' in sys.argv

    print('== 预检 ==')
    enabled, detail = nacos_enhance_enabled()
    if enabled is None:
        print('[注意] 没能从 Nacos 读出 ai.generate.enhance-enabled（%s）' % str(detail)[:120])
        print('       读不到就没法确认开关状态，请手工确认后再用 --force 继续。')
        if not force:
            return 1
    elif enabled:
        print('[停] Nacos 里 ai.generate.enhance-enabled 还是 true：')
        print('     现在生成出来的会是 A 臂（带参考菜谱），补不了 B 臂。')
        print('     先把开关改成 false 并重启 dish-service（Nacos 改了不重启不生效）。')
        if not force:
            return 1
        print('     你用 --force 跳过了这道检查，后果自负。')
    else:
        print('  enhance-enabled = false ✔')

    if not port_open(11434):
        print('[停] Ollama(11434) 没在跑，生成任务会全部失败（调用模型失败：I/O error）。')
        return 1
    print('  Ollama 在线 ✔')
    if not port_open(8082):
        print('[停] dish-service(8082) 没在跑。')
        return 1
    if not port_open(7000):
        print('[停] gateway(7000) 没在跑。')
        return 1
    print('  dish-service / gateway 在线 ✔')

    with open(TESTSET, encoding='utf-8') as f:
        queries = json.load(f)['queries']
    # 用"由菜品反推"的查询：它们保证有解，最接近真实使用
    picked = [q for q in queries if q['group'] == 'from_dish'][:want]
    if len(picked) < want:
        picked += [q for q in queries if q['group'] not in ('from_dish',)][:want - len(picked)]
    if not picked:
        print('没有可用的查询（要补的样本数是 0？），退出。')
        return 1

    username, token = login()
    if not token:
        print('登录失败：确认 gateway/account 在跑')
        return 1
    print('\n== 提交 %d 个生成任务（账号 %s）==' % (len(picked), username))

    task_ids = []
    for i, q in enumerate(picked, 1):
        tid, body = submit(token, q)
        if body.get('code') == 200 and tid:
            task_ids.append(tid)
            print('  [%d/%d] 已提交 %s → taskId=%s' % (i, len(picked), q['query_id'], tid))
        else:
            print('  [%d/%d] 提交失败 %s：%s' % (i, len(picked), q['query_id'], body.get('message')))
            time.sleep(3)

    if not task_ids:
        print('一个都没提交成功，先查网关/账号服务。')
        return 1

    print('\n== 等待终态（生成很慢，单次 2~8 分钟，别急着 Ctrl+C）==')
    done, pending = wait_terminal(token, task_ids)
    if pending:
        print('  仍在进行中：%s' % ','.join(sorted(pending)))

    # ---------- 回读：确认这批真的是 B 臂 + 提示词版本一致 ----------
    ids = ','.join(str(t) for t in task_ids)
    rows = mysql_query(
        "SELECT task_id, status, IFNULL(prompt_version,''), IFNULL(reference_dish_ids,''), "
        "IFNULL(format_ok,-1), IFNULL(constraint_ok,-1), IFNULL(cost_ms,0), IFNULL(model,'') "
        "FROM t_ai_generation_record WHERE task_id IN (%s) ORDER BY task_id" % ids)

    print('\n== 这批记录（写入 t_ai_generation_record）==')
    print('%-22s %-4s %-10s %-8s %-8s %-8s %s' % ('task_id', 'st', 'prompt', 'format', 'constr', 'cost_ms', '模型'))
    versions, with_ref, ok = set(), 0, 0
    for r in rows:
        if len(r) < 8:
            continue
        tid, status, pv, ref, fmt, con, cost, model = (r + [''] * 8)[:8]
        versions.add(pv)
        if ref.strip() not in ('', 'NULL'):
            with_ref += 1
        if fmt.strip() == '1' and con.strip() == '1' and status.strip() == '2':
            ok += 1
        print('%-22s %-4s %-10s %-8s %-8s %-8s %s' % (tid, status, pv, fmt, con, cost, model))

    print('\n== 判定 ==')
    print('  记录数 %d，其中 status=2 且格式/约束都合格 %d 条' % (len(rows), ok))
    if with_ref:
        print('  [注意] 有 %d 条 reference_dish_ids 非空 —— 说明增强其实没关掉，这批里有 A 臂！' % with_ref)
    else:
        print('  全部 reference_dish_ids 为空 ✔ 这批是 B 臂')
    if len(versions) > 1:
        print('  [注意] 提示词版本不一致：%s —— 混版本不能当同一批次比较' % versions)
    else:
        print('  提示词版本一致：%s ✔' % (', '.join(versions) or '(空)'))

    print('\n== 下一步 ==')
    print('  1) 把 Nacos 的 ai.generate.enhance-enabled 改回 true 并重启 dish-service（否则后续都生成不出 A 臂）')
    print('  2) python es/eval/human-eval-kit.py build        # 出盲评材料')
    print('  3) 找人评分（≥2 人，每人复制一份 ratings-<名字>.csv）')
    print('  4) python es/eval/human-eval-kit.py score        # 出 human-eval-report.md')
    return 0


if __name__ == '__main__':
    sys.exit(main())
