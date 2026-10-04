# -*- coding: utf-8 -*-
"""
Vue 版验证脚本（登录页 + 路由骨架）

与原生版的 verify-pages.py 是两套独立的脚本 —— Vue 版 DOM 结构由组件渲染，
选择器与渲染时机都不同，所以不能复用，只能另写一套。

用法：
    cd D:\\idea\\work\\project0914
    python frontend-vue/verify-vue.py

前置：
    - frontend-vue dev server 已在 127.0.0.1:5500 运行
    - 后端（gateway 7000 / account 8081 / dish 8082）在跑（登录用例需要）
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
urllib.request.install_opener(urllib.request.build_opener(urllib.request.ProxyHandler({})))

# 前端地址：dev server(5500) 与 dist 静态服务(5600) 都能跑。
# 两者行为一致（都做了 /api 反代、页面与接口同源），所以自动挑在跑的那个。
VUE_URL = 'http://127.0.0.1:5500/'


def _detect_front_port():
    global VUE_URL
    for p in (5500, 5600):
        s = socket.socket()
        s.settimeout(0.4)
        try:
            s.connect(('127.0.0.1', p))
            VUE_URL = 'http://127.0.0.1:%d/' % p
            return p
        except Exception:
            pass
        finally:
            try:
                s.close()
            except Exception:
                pass
    # 两个都没起来：默认按 dev server 5500 等一会儿（可能刚启动）
    VUE_URL = 'http://127.0.0.1:5500/'
    return 5500
GATEWAY = 'http://127.0.0.1:7000'
CDP_PORT = 9333

PASS = 0
FAIL = 0
FAILURES = []


def check(name, got, expected):
    global PASS, FAIL
    ok = got == expected
    if ok:
        PASS += 1
        print('  PASS  %s' % name)
    else:
        FAIL += 1
        FAILURES.append('%s  期望=%r  实际=%r' % (name, expected, got))
        print('  FAIL  %s  期望=%r 实际=%r' % (name, expected, got))


def check_true(name, got, hint=''):
    global PASS, FAIL
    if got:
        PASS += 1
        print('  PASS  %s' % name)
    else:
        FAIL += 1
        FAILURES.append('%s  %s' % (name, hint))
        print('  FAIL  %s  %s' % (name, hint))


def section(title):
    print()
    print('=' * 60)
    print('  %s' % title)
    print('=' * 60)


# ======================================================================
# CDP 客户端
# ======================================================================

class Cdp:
    def __init__(self, ws_url):
        from websocket import create_connection  # type: ignore
        self.ws = create_connection(ws_url, timeout=30)
        self.mid = 0

    def send(self, method, params=None):
        self.mid += 1
        self.ws.send(json.dumps({'id': self.mid, 'method': method, 'params': params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get('id') == self.mid:
                if 'error' in msg:
                    raise RuntimeError(msg['error'])
                return msg.get('result')

    def js(self, expr):
        r = self.send('Runtime.evaluate', {
            'expression': expr, 'returnByValue': True, 'awaitPromise': True
        })
        res = r.get('result', {})
        if r.get('exceptionDetails'):
            desc = r['exceptionDetails'].get('exception', {}).get('description', '')
            return {'__error__': desc}
        return res.get('value')

    def goto(self, url, wait=2.5):
        self.send('Page.navigate', {'url': url})
        time.sleep(wait)

    def reload(self, wait=2.5):
        """强制整页重载。

        【为什么需要它】SPA 里对同一个 URL 调 Page.navigate 不会重新加载文档
        （vue-router 只是切了组件），所以 onMounted 里读 localStorage 的那类
        初始化逻辑不会再跑。测「刷新后回填」必须真重载。
        """
        self.send('Page.reload', {'ignoreCache': True})
        time.sleep(wait)

    def wait_for(self, expr, timeout=45, interval=0.5):
        """轮询直到表达式为真。

        【为什么需要它】Vite dev server 是"按需编译"的：刚改过文件时，
        首次请求要等它转换完才会返回正常页面。直接把这种冷启动误判成
        "页面坏了"，会得到一屏假失败。
        """
        end = time.time() + timeout
        last = None
        while time.time() < end:
            last = self.js(expr)
            if last is True or (last not in (None, False, '') and not isinstance(last, dict)):
                return last
            time.sleep(interval)
        return last

    def close(self):
        try:
            self.ws.close()
        except Exception:
            pass


def find_browser():
    for p in (r'C:\Program Files\Google\Chrome\Application\chrome.exe',
              r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
              os.path.expanduser(r'~\AppData\Local\Google\Chrome\Application\chrome.exe'),
              r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
              r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'):
        if os.path.isfile(p):
            return p
    return None


CDP_PROFILE_MARKER = 'cdp-profile-vue'


def kill_stale_cdp_browsers():
    """杀掉上一次跑验证遗留的 CDP 浏览器，并等调试端口释放。

    【为什么需要】浏览器是单例进程：同一个 `--user-data-dir` 已经有实例在跑时，
    新起的进程只把 URL 转交给老实例、自己立刻退出（`proc.poll()` 不为 None）。
    于是这一轮验证实际是挂在**上一次那个浏览器**（可能还属于别的作业）上跑的 ——
    那个作业被回收时浏览器一起消失，这一轮就会在中间收到
    `ConnectionResetError: [WinError 10054] 远程主机强迫关闭了一个现有的连接`。
    症状是「跑到一半突然连不上浏览器」，看着像页面坏了，其实浏览器根本不是这轮的。

    只杀命令行里带本脚本专用 profile 的进程，**不会碰用户自己开的浏览器**。
    """
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='chrome.exe' or Name='msedge.exe'\" | "
          "Where-Object { $_.CommandLine -like '*%s*' } | "
          "ForEach-Object { $_.ProcessId }" % CDP_PROFILE_MARKER)
    try:
        r = subprocess.run(['powershell', '-NoProfile', '-Command', ps],
                           capture_output=True, text=True, timeout=30)
    except Exception as e:
        print('  [注意] 清理遗留浏览器失败：%s' % e)
        return
    pids = [ln.strip() for ln in (r.stdout or '').splitlines() if ln.strip().isdigit()]
    for pid in pids:
        subprocess.run(['taskkill', '/F', '/PID', pid], capture_output=True, text=True)
    if not pids:
        return
    print('  清理上次遗留的 CDP 浏览器 %d 个: %s' % (len(pids), ','.join(pids)))
    for _ in range(20):
        s = socket.socket()
        s.settimeout(0.4)
        try:
            s.connect(('127.0.0.1', CDP_PORT))
            s.close()
            time.sleep(0.3)
        except Exception:
            break


def ensure_pip():
    """确保 websocket-client 可用（CDP 需要）"""
    try:
        import websocket  # noqa
        return True
    except ImportError:
        print('  正在安装 websocket-client ...')
        r = subprocess.run([sys.executable, '-m', 'pip', 'install', '-q', 'websocket-client'],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print('  安装失败:', (r.stderr or '')[-300:])
            return False
        return True


def wait_port(port, timeout=60, must_be_http=True):
    """等端口可连（并可选地要求 HTTP 有响应）"""
    end = time.time() + timeout
    while time.time() < end:
        s = socket.socket()
        s.settimeout(0.6)
        try:
            s.connect(('127.0.0.1', port))
            s.close()
            if must_be_http:
                try:
                    with urllib.request.urlopen('http://127.0.0.1:%d/' % port, timeout=3) as r:
                        r.read(1)
                    return True
                except urllib.error.HTTPError:
                    return True
                except Exception:
                    pass
            else:
                return True
        except Exception:
            pass
        finally:
            try:
                s.close()
            except Exception:
                pass
        time.sleep(0.6)
    return False


MYSQL_EXE = r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'


def mysql(sql):
    """直连库跑一条 SQL（只用来给测试账号提权/查证，不参与任何业务逻辑）。

    注意：失败必须把 stderr 原文打出来 —— P1-6 的 E2E 踩过"把 stderr 丢进
    DEVNULL 导致 SQL 报错被静默吞掉、断言假通过"的坑。
    """
    r = subprocess.run([MYSQL_EXE, '-uroot', '-proot', 'project0914', '-N', '-B', '-e', sql],
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        print('  [mysql 失败] %s' % (r.stderr or '')[:300])
    return [ln for ln in (r.stdout or '').splitlines() if ln.strip()]


def api(method, path, body=None, token=None, timeout=60):
    """直接打网关（不经浏览器），用来拿 token / 读接口原始数据做对照"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(GATEWAY + path, data=data, method=method)
    if body is not None:
        req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode('utf-8', errors='replace'))
    except urllib.error.HTTPError as e:
        raw = e.read().decode('utf-8', errors='replace')
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {'raw': raw}
    except Exception as e:
        return 0, {'error': str(e)}


def main():
    global PASS, FAIL

    section('0. 前置检查')
    if not ensure_pip():
        print('  websocket-client 不可用，无法继续')
        return 2

    port = _detect_front_port()
    print('  前端服务: %d' % port)
    print('  等待前端就绪 (%d) ...' % port, end='', flush=True)
    if not wait_port(port, timeout=90):
        print(' 超时')
        print('  请先启动其一：')
        print('    cd frontend-vue && npm run dev        (dev 5500)')
        print('    python frontend-vue/serve-dist.py     (dist 5600)')
        return 2
    print(' ok')

    # 后端是否在跑（登录用例需要，不在则跳过那部分）
    backend_up = wait_port(7000, timeout=3)
    print('  后端网关 7000 ->', '在线' if backend_up else '离线（将跳过登录用例）')

    browser = find_browser()
    if not browser:
        print('  未找到 Chrome/Edge')
        return 2

    profile = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '.workbuddy', 'cdp-profile-vue')
    profile = os.path.abspath(profile)
    os.makedirs(profile, exist_ok=True)

    kill_stale_cdp_browsers()

    proc = subprocess.Popen(
        [browser, '--headless=new', '--disable-gpu',
         '--remote-debugging-port=%d' % CDP_PORT,
         # 新版 Chrome 默认拒绝非白名单 origin 的 CDP 连接，必须显式放开
         '--remote-allow-origins=*',
         '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check',
         'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    time.sleep(0.6)
    if proc.poll() is not None:
        print('  [致命] 新起的浏览器立刻退出了（多半是复用了同一个 profile 的老实例）；'
              '先关掉残留的 CDP 浏览器再重跑')
        return 2

    tab = None
    for _ in range(50):
        time.sleep(0.4)
        try:
            with urllib.request.urlopen('http://127.0.0.1:%d/json' % CDP_PORT, timeout=3) as r:
                tabs = json.loads(r.read().decode())
            tab = next((t for t in tabs if t.get('type') == 'page'), None)
            if tab:
                break
        except Exception:
            pass
    if not tab:
        print('  CDP 启动失败')
        proc.kill()
        return 2

    cdp = Cdp(tab['webSocketDebuggerUrl'])
    cdp.send('Page.enable')
    cdp.send('Runtime.enable')

    # 收集控制台报错（Vue 的编译错误会在这里露出来）
    errors = []

    try:
        # ---------------- 1. 页面渲染 ----------------
        section('1. 登录页渲染（Vue 是否真的挂载成功）')

        # 【必须在最前面清 localStorage】
        # CDP 复用同一个 profile，而 Chrome 在被 kill 前不一定把 localStorage 刷盘，
        # 于是上一轮留下的 kitchen.token 会带进本轮 —— 路由守卫看到"已登录"，
        # 就把 / 重定向到 /kitchen，登录页的所有断言会集体假失败。
        # 这个现象非常像"页面坏了"，所以要在断言之前就把状态归零。
        cdp.goto(VUE_URL + '#login')
        cdp.wait_for("!!document.querySelector('#app')", timeout=60)
        cdp.js("localStorage.clear()")
        cdp.reload()
        cdp.wait_for("!!document.querySelector('#loginForm')", timeout=30)

        # 兜底：万一还是被守卫带走了，再硬刷一次
        if cdp.js("location.pathname") != '/':
            cdp.js("localStorage.clear()")
            cdp.goto(VUE_URL + '#login')
            cdp.reload()
            cdp.wait_for("!!document.querySelector('#loginForm')", timeout=30)
        time.sleep(0.5)

        mounted = cdp.js("!!document.querySelector('#app').firstElementChild")
        check('Vue 已挂载到 #app', mounted, True)

        check('标题文案', cdp.js("document.querySelector('.brand-title')?.textContent.trim()"), '欢迎回来')
        check('品牌卖点数', cdp.js("document.querySelectorAll('.brand-points li').length"), 3)
        check('两个 Tab 都在', cdp.js("document.querySelectorAll('.tab').length"), 2)
        check('登录表单存在', cdp.js("!!document.querySelector('#loginForm')"), True)
        check('注册表单存在', cdp.js("!!document.querySelector('#registerForm')"), True)

        # 漂浮装饰（固定种子，数量恒定 26）
        check('漂浮装饰数量', cdp.js("document.querySelectorAll('.floater').length"), 26)
        check('流光背景色团', cdp.js("document.querySelectorAll('.bg-flow .blob').length"), 4)
        check('噪点层存在', cdp.js("!!document.querySelector('.bg-flow .fx-grain')"), True)

        # ---------------- 2. Tab 切换 ----------------
        section('2. Tab 切换')
        check('初始在登录 Tab',
              cdp.js("document.querySelector('.tabs').dataset.active"), 'login')
        check('登录表单可见',
              cdp.js("!document.querySelector('#loginForm').classList.contains('is-hidden')"), True)
        check('注册表单隐藏',
              cdp.js("document.querySelector('#registerForm').classList.contains('is-hidden')"), True)

        cdp.js("document.querySelector('.tab[data-tab=\"register\"]').click()")
        time.sleep(0.4)
        check('切到注册后 data-active',
              cdp.js("document.querySelector('.tabs').dataset.active"), 'register')
        check('注册表单显示',
              cdp.js("!document.querySelector('#registerForm').classList.contains('is-hidden')"), True)
        check('URL hash 同步', cdp.js("location.hash"), '#register')

        cdp.js("document.querySelector('.tab[data-tab=\"login\"]').click()")
        time.sleep(0.4)
        check('切回登录 data-active',
              cdp.js("document.querySelector('.tabs').dataset.active"), 'login')

        # ---------------- 3. 密码显隐 ----------------
        section('3. 密码显示切换')
        cdp.js("document.querySelector('.tab[data-tab=\"register\"]').click()")
        time.sleep(0.3)
        check('初始 type=password', cdp.js("document.querySelector('#regPassword').type"), 'password')
        cdp.js("document.querySelector('.eye-btn[data-eye=\"regPassword\"]').click()")
        time.sleep(0.3)
        check('点击后 type=text', cdp.js("document.querySelector('#regPassword').type"), 'text')
        check('按钮带 is-on',
              cdp.js("document.querySelector('.eye-btn[data-eye=\"regPassword\"]').classList.contains('is-on')"), True)
        cdp.js("document.querySelector('.eye-btn[data-eye=\"regPassword\"]').click()")
        time.sleep(0.3)
        check('再点回 password', cdp.js("document.querySelector('#regPassword').type"), 'password')

        # ---------------- 4. 密码强度 ----------------
        section('4. 密码强度评估')
        def set_pwd(v):
            cdp.js("""
                (() => {
                  const el = document.querySelector('#regPassword');
                  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                  setter.call(el, %s);
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                })()
            """ % json.dumps(v))
            time.sleep(0.35)

        set_pwd('abc')
        check('短密码 -> 等级 0 或 1',
              cdp.js("Number(document.querySelector('#pwdStrength').dataset.level)") in (0, 1), True)

        set_pwd('Abc123!@#xyz')
        check('强密码 -> 等级 4',
              cdp.js("Number(document.querySelector('#pwdStrength').dataset.level)"), 4)
        check('强度文案', cdp.js("document.querySelector('#pwdStrengthText').textContent.trim()"), '很强')

        set_pwd('')
        check('清空后文案复位',
              cdp.js("document.querySelector('#pwdStrengthText').textContent.trim()"), '密码强度')

        # ---------------- 5. 前端校验 ----------------
        section('5. 前端校验（必须与后端 DTO 对齐）')
        # 【必须先清掉「记住用户名」】CDP 复用同一个 profile，上一轮测试往
        # localStorage 写的 kitchen.rememberedUser 会残留在本轮，onMounted 会把
        # 它回填进输入框 —— 那样"空提交"就不是空的了，断言会假失败。
        cdp.js("localStorage.removeItem('kitchen.rememberedUser')")
        cdp.reload()
        time.sleep(0.5)
        cdp.js("document.querySelector('.tab[data-tab=\"login\"]').click()")
        time.sleep(0.3)

        # 空提交
        cdp.js("document.querySelector('#loginForm').requestSubmit()")
        time.sleep(0.5)
        check('空用户名报错',
              cdp.js("document.querySelector('[data-error-for=\"loginUsername\"]').textContent.trim()"), '用户名不能为空')
        check('空密码报错',
              cdp.js("document.querySelector('[data-error-for=\"loginPassword\"]').textContent.trim()"), '密码不能为空')

        # 用户名太短
        def set_login(u, p):
            cdp.js("""
                (() => {
                  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                  const u = document.querySelector('#loginUsername');
                  const pw = document.querySelector('#loginPassword');
                  setter.call(u, %s); u.dispatchEvent(new Event('input', { bubbles: true }));
                  setter.call(pw, %s); pw.dispatchEvent(new Event('input', { bubbles: true }));
                })()
            """ % (json.dumps(u), json.dumps(p)))
            time.sleep(0.3)

        set_login('abc', 'Test123456')
        cdp.js("document.querySelector('#loginForm').requestSubmit()")
        time.sleep(0.5)
        check('用户名过短报错',
              cdp.js("document.querySelector('[data-error-for=\"loginUsername\"]').textContent.trim()"),
              '用户名长度需在 4-20 位之间')

        set_login('okuser', '123')
        cdp.js("document.querySelector('#loginForm').requestSubmit()")
        time.sleep(0.5)
        check('密码过短报错',
              cdp.js("document.querySelector('[data-error-for=\"loginPassword\"]').textContent.trim()"),
              '密码长度需在 6-32 位之间')

        # 输入时实时清错
        cdp.js("""
            (() => {
              const el = document.querySelector('#loginUsername');
              const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
              setter.call(el, 'abcd');
              el.dispatchEvent(new Event('input', { bubbles: true }));
            })()
        """)
        time.sleep(0.35)
        check('输入后错误被清掉',
              cdp.js("document.querySelector('[data-error-for=\"loginUsername\"]').textContent.trim()"), '')

        # ---------------- 6. 记住用户名 ----------------
        section('6. 记住用户名')
        cdp.js("localStorage.clear(); localStorage.setItem('kitchen.rememberedUser', 'remembered_user')")
        cdp.goto(VUE_URL + '#login')
        cdp.reload()   # 必须真重载，onMounted 才会重新读 localStorage
        time.sleep(0.5)
        check('回填记住的用户名',
              cdp.js("document.querySelector('#loginUsername').value"), 'remembered_user')
        check('只存用户名不存密码',
              cdp.js("localStorage.getItem('kitchen.token') === null && localStorage.getItem('kitchen.rememberedUser') !== null"), True)

        # ---------------- 7. 路由守卫 ----------------
        section('7. 路由守卫（未登录不能进内页）')
        cdp.js("localStorage.clear()")
        cdp.goto(VUE_URL + '#login')
        cdp.goto(VUE_URL + '#login')
        time.sleep(0.4)

        cdp.goto(VUE_URL + 'kitchen')
        time.sleep(1.2)
        check('未登录访问 /kitchen 被弹回登录页',
              cdp.js("location.pathname"), '/')
        check('带回跳参数',
              cdp.js("new URLSearchParams(location.search).get('redirect')"), '/kitchen')
        check('确实渲染的是登录页',
              cdp.js("!!document.querySelector('#loginForm')"), True)

        # ---------------- 8. （已移除）接口地址设置 ----------------
        # 原来登录页脚有「接口地址 xxx · 修改」，点开是个设置弹窗。
        # 2026-09-17 起整条链路已从页面上删掉（开发期调试入口，不该给用户看），
        # 所以这一节断言一并移除。接口地址仍由 config.js 按「页面从哪来」自动推断。

        # ---------------- 9. 真实登录（需要后端） ----------------
        if backend_up:
            section('9. 真实登录链路（走网关）')
            import random
            import string
            uname = 'vue' + ''.join(random.choices(string.digits, k=6))

            # 先注册
            body = json.dumps({'username': uname, 'password': 'Test123456', 'nickname': 'Vue测试'}).encode()
            req = urllib.request.Request(GATEWAY + '/api/account/auth/register', data=body, method='POST')
            req.add_header('Content-Type', 'application/json')
            try:
                with urllib.request.urlopen(req, timeout=25) as r:
                    reg = json.loads(r.read().decode())
                check_true('注册成功', reg.get('code') == 200, 'resp=%s' % json.dumps(reg, ensure_ascii=False)[:200])
            except urllib.error.HTTPError as e:
                check_true('注册成功', False, 'HTTP %s' % e.code)

            cdp.js("localStorage.clear()")
            cdp.goto(VUE_URL + '#login')
            cdp.goto(VUE_URL + '#login')
            time.sleep(0.5)

            set_login(uname, 'Test123456')
            cdp.js("document.querySelector('#loginForm').requestSubmit()")
            time.sleep(3.5)

            check('登录后跳转到 /kitchen',
                  cdp.js("location.pathname"), '/kitchen')
            check_true('token 已落盘',
                       bool(cdp.js("localStorage.getItem('kitchen.token')")),
                       'got=%r' % cdp.js("localStorage.getItem('kitchen.token')"))
            check_true('userId 是字符串（雪花 ID 未丢精度）',
                       isinstance(cdp.js("JSON.parse(localStorage.getItem('kitchen.userInfo')||'{}').userId"), str),
                       'got=%r' % cdp.js("typeof JSON.parse(localStorage.getItem('kitchen.userInfo')||'{}').userId"))
            um = cdp.js("JSON.parse(localStorage.getItem('kitchen.userInfo')||'{}').username")
            check('落盘的用户名正确', um, uname)

            # 已登录状态访问登录页应被弹到工作台
            cdp.goto(VUE_URL + '#login')
            time.sleep(1.2)
            check('已登录访问登录页 -> 工作台',
                  cdp.js("location.pathname"), '/kitchen')

            # ---------------- 9b. 榜单页（登录态下） ----------------
            section('9b. 榜单页 /rank')
            cdp.goto(VUE_URL + 'rank')
            time.sleep(2.5)

            check('路由到 /rank', cdp.js("location.pathname"), '/rank')
            check('顶栏存在', cdp.js("!!document.querySelector('.topbar')"), True)
            check('顶栏昵称非空',
                  cdp.js("(document.querySelector('#whoText')?.textContent || '').trim().length > 0"), True)
            check('当前页导航高亮为榜单',
                  cdp.js("document.querySelector('.nav-link[data-page=\"rank\"]')?.getAttribute('aria-current')"),
                  'page')
            check('其他导航项无高亮',
                  cdp.js("document.querySelector('.nav-link[data-page=\"kitchen\"]')?.hasAttribute('aria-current')"),
                  False)
            # 冷启动榜是后加的第四个分段（见 RankView.vue 的 RANK_TYPES）——
            # 这里曾经写死 3，页面加分段后就成了"长期假失败"，会训练人忽略 FAIL
            check('分段控件四个选项（热门/高分/最新/冷启动）',
                  cdp.js("document.querySelectorAll('#rankSeg .rank-seg-btn').length"), 4)
            check('默认选中热门榜',
                  cdp.js("document.querySelector('.rank-seg-btn[data-type=\"hot\"]')?.getAttribute('aria-selected')"),
                  'true')
            check('榜单列表有内容',
                  cdp.js("document.querySelectorAll('.rank-list .rank-item').length") > 0, True)
            check('副标题含总数',
                  '共 ' in str(cdp.js("document.querySelector('#rankSub')?.textContent || ''")),
                  True)

            # 切到高分榜
            cdp.js("document.querySelector('.rank-seg-btn[data-type=\"top\"]').click()")
            time.sleep(2.0)
            check('切到高分榜后 aria-selected',
                  cdp.js("document.querySelector('.rank-seg-btn[data-type=\"top\"]')?.getAttribute('aria-selected')"),
                  'true')
            check('热门榜取消选中',
                  cdp.js("document.querySelector('.rank-seg-btn[data-type=\"hot\"]')?.getAttribute('aria-selected')"),
                  'false')
            check('切换后仍有列表或空态',
                  cdp.js("!!document.querySelector('.rank-list') || !!document.querySelector('.empty')"), True)

            # 冷启动榜：新菜冒头的地方，按综合得分排（不是纯互动量）
            cdp.js("document.querySelector('.rank-seg-btn[data-type=\"cold\"]').click()")
            time.sleep(2.0)
            check('切到冷启动榜后 aria-selected',
                  cdp.js("document.querySelector('.rank-seg-btn[data-type=\"cold\"]')?.getAttribute('aria-selected')"),
                  'true')
            check('冷启动榜有列表或空态',
                  cdp.js("!!document.querySelector('.rank-list') || !!document.querySelector('.empty')"), True)

            # 榜单项能点进详情
            cdp.goto(VUE_URL + 'rank')
            time.sleep(2.0)
            first_href = cdp.js("document.querySelector('.rank-item')?.getAttribute('href')")
            check_true('榜单项链接到详情页', bool(first_href) and '/dish/' in str(first_href),
                       'href=%r' % first_href)

            # ---------------- 9c. 我的页 /profile ----------------
            section('9c. 我的页 /profile')
            cdp.goto(VUE_URL + 'profile')
            time.sleep(2.5)

            check('路由到 /profile', cdp.js("location.pathname"), '/profile')
            check('导航高亮为我的',
                  cdp.js("document.querySelector('.nav-link[data-page=\"profile\"]')?.getAttribute('aria-current')"),
                  'page')
            check('账号名非空',
                  cdp.js("(document.querySelector('#profileName')?.textContent || '').trim().length > 0"), True)
            check('头像首字非空',
                  cdp.js("(document.querySelector('#avatar')?.textContent || '').trim().length > 0"), True)
            check('账号副标题含 ID',
                  'ID ' in str(cdp.js("document.querySelector('#profileSub')?.textContent || ''")), True)
            check('两个 tab 都在', cdp.js("document.querySelectorAll('.profile-tabs .profile-tab').length"), 2)
            check('默认选中「我的菜品」',
                  cdp.js("document.querySelector('.profile-tab[data-tab=\"dishes\"]')?.getAttribute('aria-selected')"),
                  'true')
            check('新用户菜品数为 0',
                  cdp.js("document.querySelector('#countDishes')?.textContent.trim()"), '0')
            check('显示空态或列表',
                  cdp.js("!!document.querySelector('#panel .empty') || !!document.querySelector('#panel .dish-list')"),
                  True)
            check('panel 指向 dishes tab',
                  cdp.js("document.querySelector('#panel')?.getAttribute('aria-labelledby')"), 'tabDishes')

            # 切到收藏
            cdp.js("document.querySelector('.profile-tab[data-tab=\"collects\"]').click()")
            time.sleep(2.0)
            check('切到收藏后 aria-selected',
                  cdp.js("document.querySelector('.profile-tab[data-tab=\"collects\"]')?.getAttribute('aria-selected')"),
                  'true')
            check('菜品 tab 取消选中',
                  cdp.js("document.querySelector('.profile-tab[data-tab=\"dishes\"]')?.getAttribute('aria-selected')"),
                  'false')
            check('panel 指向 collects tab',
                  cdp.js("document.querySelector('#panel')?.getAttribute('aria-labelledby')"), 'tabCollects')
            check('显示空态或收藏列表',
                  cdp.js("!!document.querySelector('#panel .empty') || !!document.querySelector('#panel .dish-list')"),
                  True)
            check('卡片退出登录按钮在',
                  cdp.js("!!document.querySelector('#logoutBtnCard')"), True)

            # ---------------- 9d. 菜品详情页 /dish/:id ----------------
            section('9d. 菜品详情页 /dish/:id')
            cdp.goto(VUE_URL + 'rank')
            cdp.wait_for("!!document.querySelector('.rank-item')", timeout=30)
            href = cdp.js("document.querySelector('.rank-item')?.getAttribute('href')")
            check_true('从榜单拿到一个菜品链接', bool(href), 'href=%r' % href)

            cdp.goto(VUE_URL + str(href).lstrip('/'))
            cdp.wait_for("!!document.querySelector('.detail-title')", timeout=30)
            time.sleep(0.5)

            check('路由到 /dish/...', str(cdp.js("location.pathname")).startswith('/dish/'), True)
            # 拆成两行写：嵌套引号 + 括号太深容易被 Python 读歪
            title_txt = cdp.js("(document.querySelector('.detail-title')?.textContent || '').trim()")
            check_true('菜品标题非空', bool(title_txt), 'got=%r' % title_txt)
            check('有「要准备什么」卡片',
                  cdp.js("[...document.querySelectorAll('.card-title')].some(e => e.textContent.includes('要准备什么'))"),
                  True)
            check('有返回找菜链接',
                  cdp.js("document.querySelector('.back-link')?.getAttribute('href')"), '/kitchen')
            check('收藏按钮初始 aria-pressed',
                  cdp.js("document.querySelector('#collectBtn')?.getAttribute('aria-pressed')"), 'false')
            check('评分五颗星', cdp.js("document.querySelectorAll('.stars .star').length"), 5)
            check('评论框存在', cdp.js("!!document.querySelector('#commentInput')"), True)
            check('字数计数初始', cdp.js("document.querySelector('#charCount')?.textContent.trim()"), '0 / 500')
            # 这道菜不是本人创建的 → 不应出现发布/撤回按钮
            check('非本人看不到发布/撤回按钮',
                  cdp.js("!document.querySelector('#publishBtn') && !document.querySelector('#unpublishBtn')"), True)

            # 收藏
            cdp.js("document.querySelector('#collectBtn').click()")
            time.sleep(2.2)
            check('收藏后按钮变「已收藏」',
                  cdp.js("document.querySelector('#collectBtn')?.textContent.trim()"), '已收藏')
            check('收藏后 aria-pressed=true',
                  cdp.js("document.querySelector('#collectBtn')?.getAttribute('aria-pressed')"), 'true')

            # 打分
            cdp.js("document.querySelector('.stars .star[data-score=\"4\"]').click()")
            time.sleep(2.2)
            check('打分后该星 aria-pressed',
                  cdp.js("document.querySelector('.stars .star[data-score=\"4\"]')?.getAttribute('aria-pressed')"), 'true')
            note_txt = cdp.js("(document.querySelector('.stars-note')?.textContent || '').trim()")
            check_true('打分后提示语更新', '你打了 4 星' in str(note_txt), 'got=%r' % note_txt)

            # 空评论应被拦下（条数不变）
            before_cnt = cdp.js("document.querySelectorAll('#commentList .comment').length")
            cdp.js("document.querySelector('#commentForm').requestSubmit()")
            time.sleep(1.0)
            check('空评论被拦下（条数不变）',
                  cdp.js("document.querySelectorAll('#commentList .comment').length"), before_cnt)

            # 发一条真评论
            cdp.js("""
                (() => {
                  const el = document.querySelector('#commentInput');
                  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                  setter.call(el, 'Vue 版详情页评论测试');
                  el.dispatchEvent(new Event('input', { bubbles: true }));
                })()
            """)
            time.sleep(0.5)
            check_true('字数计数已更新',
                       cdp.js("document.querySelector('#charCount')?.textContent.trim()") != '0 / 500',
                       'got=%r' % cdp.js("document.querySelector('#charCount')?.textContent.trim()"))

            cdp.js("document.querySelector('#commentForm').requestSubmit()")
            time.sleep(2.5)
            check('评论发表成功（列表首条是它）',
                  cdp.js("document.querySelector('#commentList .comment .comment-body')?.textContent.trim()"),
                  'Vue 版详情页评论测试')
            check('评论框已清空', cdp.js("document.querySelector('#commentInput').value"), '')

            # 不存在的菜 → 错误态
            cdp.goto(VUE_URL + 'dish/9999999999999999999')
            cdp.wait_for("!!document.querySelector('.err-box') || !!document.querySelector('.detail-title')", timeout=30)
            time.sleep(0.8)
            check('不存在的菜显示错误态', cdp.js("!!document.querySelector('.err-box')"), True)

            # ---------------- 9e. 找菜工作台 /kitchen ----------------
            section('9e. 找菜工作台 /kitchen')
            cdp.goto(VUE_URL + 'kitchen')
            cdp.wait_for("document.querySelectorAll('.ing').length > 0", timeout=40)
            time.sleep(0.5)

            check('路由到 /kitchen', cdp.js("location.pathname"), '/kitchen')
            check('食材按钮共 88 个', cdp.js("document.querySelectorAll('.ing').length"), 88)
            check('食材分组 5 个', cdp.js("document.querySelectorAll('.ing-group').length"), 5)
            check('口味标签 6 个', cdp.js("document.querySelectorAll('#tasteChips .chip').length"), 6)
            check('人数档位 4 个', cdp.js("document.querySelectorAll('#servingSegs .seg').length"), 4)
            check('默认 2 人选中',
                  cdp.js("document.querySelectorAll('#servingSegs .seg')[1]?.getAttribute('aria-pressed')"), 'true')
            check('初始提示文案',
                  cdp.js("document.querySelector('#submitNote')?.textContent.trim()"), '还没有选择食材')
            check('初始两个按钮都禁用',
                  cdp.js("document.querySelector('#searchBtn').disabled && document.querySelector('#generateBtn').disabled"),
                  True)
            check('任务区默认隐藏', cdp.js("document.querySelector('#taskSection').hidden"), True)
            check('结果区默认空态', cdp.js("!!document.querySelector('#results .empty')"), True)

            # ---- 点选食材 ----
            cdp.js("document.querySelectorAll('.ing')[0].click(); document.querySelectorAll('.ing')[1].click()")
            time.sleep(0.5)
            check('点选后 aria-pressed=true',
                  cdp.js("document.querySelectorAll('.ing')[0].getAttribute('aria-pressed')"), 'true')
            check('已选芯片 2 个', cdp.js("document.querySelectorAll('#pickedBar .picked-chip').length"), 2)
            check('提示变为已选 2 种',
                  cdp.js("document.querySelector('#submitNote')?.textContent.trim()"), '已选 2 种食材')
            check('两个按钮已启用',
                  cdp.js("!document.querySelector('#searchBtn').disabled && !document.querySelector('#generateBtn').disabled"),
                  True)

            def set_input(selector, value):
                cdp.js("""
                    (() => {
                      const el = document.querySelector(%s);
                      const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                      setter.call(el, %s);
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                    })()
                """ % (json.dumps(selector), json.dumps(value)))
                time.sleep(0.5)

            # ---- 搜索过滤：清空后已选状态不能丢（原生版踩过的坑） ----
            set_input('#ingSearch', '茄')
            filtered = cdp.js("document.querySelectorAll('.ing').length")
            check_true('搜索「茄」只剩匹配项', 0 < filtered < 88, 'got=%r' % filtered)
            check_true('过滤后已选项仍为选中',
                       cdp.js("[...document.querySelectorAll('.ing')].filter(b => b.getAttribute('aria-pressed') === 'true').length") >= 1,
                       'got=%r' % cdp.js("[...document.querySelectorAll('.ing')].filter(b => b.getAttribute('aria-pressed') === 'true').length"))

            set_input('#ingSearch', '')
            check('清空搜索后恢复 88 个', cdp.js("document.querySelectorAll('.ing').length"), 88)
            check('★清空后已选芯片还在', cdp.js("document.querySelectorAll('#pickedBar .picked-chip').length"), 2)
            check('★清空后原按钮仍为选中',
                  cdp.js("document.querySelectorAll('.ing')[0].getAttribute('aria-pressed')"), 'true')

            set_input('#ingSearch', '不存在的食材xyz')
            check('无命中时给出提示', cdp.js("document.querySelector('#ingSearchMiss')?.hidden"), False)
            set_input('#ingSearch', '')
            check('清空后提示隐藏', cdp.js("document.querySelector('#ingSearchMiss')?.hidden"), True)

            # ---- 自由输入：不在表里 -> 自填；在表里 -> 并入点选 ----
            set_input('#freeIngInput', '松露')
            cdp.js("document.querySelector('#addFreeIngBtn').click()")
            time.sleep(0.7)
            check('自填食材生成 is-free 芯片',
                  cdp.js("[...document.querySelectorAll('#pickedBar .picked-chip')].filter(c => c.classList.contains('is-free')).length"),
                  1)
            check('自填后提示 3 种',
                  cdp.js("document.querySelector('#submitNote')?.textContent.trim()"), '已选 3 种食材')

            set_input('#freeIngInput', '茄子')
            cdp.js("document.querySelector('#addFreeIngBtn').click()")
            time.sleep(0.8)
            check('表内食材并入点选（自填仍只有 1 个）',
                  cdp.js("[...document.querySelectorAll('#pickedBar .picked-chip')].filter(c => c.classList.contains('is-free')).length"),
                  1)
            check('并入后共 4 种',
                  cdp.js("document.querySelector('#submitNote')?.textContent.trim()"), '已选 4 种食材')

            # ---- 口味 / 人数 ----
            cdp.js("document.querySelectorAll('#tasteChips .chip')[0].click()")
            time.sleep(0.3)
            check('口味可选中',
                  cdp.js("document.querySelectorAll('#tasteChips .chip')[0].getAttribute('aria-pressed')"), 'true')
            cdp.js("document.querySelectorAll('#servingSegs .seg')[3].click()")
            time.sleep(0.3)
            check('人数切到第 4 档',
                  cdp.js("document.querySelectorAll('#servingSegs .seg')[3].getAttribute('aria-pressed')"), 'true')
            check('人数只有一个选中',
                  cdp.js("[...document.querySelectorAll('#servingSegs .seg')].filter(b => b.getAttribute('aria-pressed') === 'true').length"),
                  1)

            # ---- 上限 10 种 ----
            cdp.js("[...document.querySelectorAll('.ing')].forEach(b => { if (b.getAttribute('aria-pressed') === 'false') b.click(); })")
            time.sleep(1.0)
            check('食材选择上限 10', cdp.js("document.querySelectorAll('#pickedBar .picked-chip').length"), 10)

            # ---- 移除已选 ----
            cdp.js("document.querySelector('#pickedBar .picked-chip').click()")
            time.sleep(0.5)
            check('点芯片可移除', cdp.js("document.querySelectorAll('#pickedBar .picked-chip').length"), 9)

            # ---- 真实召回 ----
            cdp.js("document.querySelector('#searchBtn').click()")
            cdp.wait_for("!!document.querySelector('#results .banner') || !!document.querySelector('#results .err-box')",
                         timeout=90)
            time.sleep(0.5)
            check('召回出现状态条', cdp.js("!!document.querySelector('#results .banner')"), True)
            check('结果标题非空',
                  len(cdp.js("(document.querySelector('#resultTitle')?.textContent || '').trim()")) > 0, True)
            check('结果区有「让 AI 生成一道」出口',
                  cdp.js("!!document.querySelector('#genFromResultBtn')"), True)

            # ---- 提交 AI 生成 ----
            cdp.js("document.querySelector('#generateBtn').click()")
            cdp.wait_for("!document.querySelector('#taskSection').hidden", timeout=60)
            time.sleep(1.0)
            check('任务区出现', cdp.js("document.querySelector('#taskSection').hidden"), False)
            check('任务条目 >= 1',
                  cdp.js("document.querySelectorAll('#taskList .task').length") >= 1, True)
            check('任务有状态徽章', cdp.js("!!document.querySelector('#taskList .task .tag')"), True)
            check('任务提示非空',
                  len(cdp.js("(document.querySelector('#taskHint')?.textContent || '').trim()")) > 0, True)

            # ---------------- 9f. 找菜模式 / 更多条件（P1-2 排序 + P1-3 约束） ----------------
            section('9f. 找菜模式与更多条件（断言前端真实发出的载荷）')
            cdp.goto(VUE_URL + 'kitchen')
            cdp.wait_for("document.querySelectorAll('.ing').length > 0", timeout=40)
            time.sleep(0.6)

            # 把 fetch 记下来：这一节断言的是"前端到底送了什么"，
            # 而不是"结果看起来对不对" —— 后者可能被后端的兜底逻辑掩盖。
            cdp.js("""
                (() => {
                  window.__reqs = [];
                  const orig = window.fetch;
                  window.__origFetch = orig;
                  window.fetch = function (u, o) {
                    try {
                      window.__reqs.push({ url: String(u), body: (o && o.body) ? JSON.parse(o.body) : null });
                    } catch (e) {}
                    return orig.apply(this, arguments);
                  };
                  return true;
                })()
            """)

            check('默认是宽松模式',
                  cdp.js("document.querySelector('#modeSegs [data-mode=loose]').getAttribute('aria-pressed')"), 'true')
            check('宽松档位有 1/2/3 三档', cdp.js("document.querySelectorAll('#modeSegs ~ .chips .chip').length"), 3)
            check('默认最多缺 2 样',
                  cdp.js("[...document.querySelectorAll('#modeSegs ~ .chips .chip')].findIndex(b => b.getAttribute('aria-pressed') === 'true')"), 1)

            cdp.js("document.querySelector('#modeSegs [data-mode=strict]').click()")
            time.sleep(0.4)
            check('严格模式高亮',
                  cdp.js("document.querySelector('#modeSegs [data-mode=strict]').getAttribute('aria-pressed')"), 'true')
            check('严格模式不再显示缺料档位', cdp.js("document.querySelectorAll('#modeSegs ~ .chips').length"), 0)
            cdp.js("document.querySelector('#modeSegs [data-mode=loose]').click()")
            time.sleep(0.4)
            check('切回宽松档位回来', cdp.js("document.querySelectorAll('#modeSegs ~ .chips .chip').length"), 3)

            check('更多条件默认收起',
                  cdp.js("document.querySelector('#moreToggle').getAttribute('aria-expanded')"), 'false')
            check('收起时忌口输入不存在', cdp.js("!!document.querySelector('#excludeInput')"), False)
            cdp.js("document.querySelector('#moreToggle').click()")
            time.sleep(0.4)
            check('展开后 aria-expanded=true',
                  cdp.js("document.querySelector('#moreToggle').getAttribute('aria-expanded')"), 'true')
            check('展开后有忌口输入', cdp.js("!!document.querySelector('#excludeInput')"), True)

            # 忌口：一次输入两个（逗号也认）
            set_input('#excludeInput', '香菜, 花生')
            cdp.js("document.querySelector('#excludeInput + button').click()")
            time.sleep(0.5)
            check('忌口拆成 2 个芯片', cdp.js("document.querySelectorAll('#excludeChips .picked-chip').length"), 2)
            cdp.js("document.querySelector('#excludeChips .picked-chip').click()")
            time.sleep(0.4)
            check('移除一个后只剩「花生」',
                  cdp.js("[...document.querySelectorAll('#excludeChips .picked-chip span')].map(s => s.textContent.trim())"),
                  ['花生'])

            set_input('#cookTimeInput', '30')
            check('最长耗时填了 30', cdp.js("document.querySelector('#cookTimeInput').value"), '30')

            # 必用食材只能从已选里挑：先选 2 样，再把第 1 个标成必用
            cdp.js("document.querySelectorAll('.ing')[0].click(); document.querySelectorAll('.ing')[1].click()")
            time.sleep(0.5)
            check('必用候选=已选 2 样', cdp.js("document.querySelectorAll('#mustUseChips .chip').length"), 2)
            cdp.js("document.querySelector('#mustUseChips .chip').click()")
            time.sleep(0.4)
            must_id = cdp.js("document.querySelector('#mustUseChips .chip').getAttribute('data-id')")
            check('标记后该食材 aria-pressed=true',
                  cdp.js("document.querySelector('#mustUseChips .chip').getAttribute('aria-pressed')"), 'true')
            check_true('拿得到必用食材 id', bool(must_id), 'got=%r' % must_id)

            set_input('#equipInput', '炒锅')
            cdp.js("document.querySelector('#equipInput + button').click()")
            time.sleep(0.5)
            check('厨具芯片 1 个',
                  cdp.js("[...document.querySelectorAll('#equipChips .picked-chip span')].map(s => s.textContent.trim())"),
                  ['炒锅'])

            # ---- 找菜：宽松 + 最多缺 3 样 ----
            cdp.js("[...document.querySelectorAll('#modeSegs ~ .chips .chip')][2].click()")
            time.sleep(0.4)
            cdp.js("window.__reqs = []")
            cdp.js("document.querySelector('#searchBtn').click()")
            cdp.wait_for("window.__reqs.filter(r => r.url.indexOf('/api/dish/search') >= 0).length > 0", timeout=60)
            time.sleep(1.5)
            q = cdp.js("window.__reqs.filter(r => r.url.indexOf('/api/dish/search') >= 0).slice(-1)[0].body")
            check_true('搜索请求带载荷', isinstance(q, dict), 'got=%r' % q)
            if isinstance(q, dict):
                check('搜索载荷 mode=loose', q.get('mode'), 'loose')
                check('搜索载荷 maxMissing=3', q.get('maxMissing'), 3)
                check('搜索载荷带忌口', q.get('excludeIngredients'), ['花生'])
                check('搜索载荷带最长耗时', q.get('maxCookTimeMin'), 30)
                check_true('搜索载荷带已选食材 2 样',
                           len(q.get('ingredientIds') or []) == 2, 'got=%r' % q.get('ingredientIds'))

            check_true('召回有状态条（不是错误态）',
                       cdp.js("!!document.querySelector('#results .banner')"),
                       'err=%r' % cdp.js("(document.querySelector('#results .err-box')?.textContent || '').trim()"))
            # 结果区文案只在真有结果时才有意义，空库不算缺陷
            if cdp.js("document.querySelectorAll('#results .dish-main').length"):
                check_true('主菜标了「还差 X」或「主料齐了」',
                           cdp.js("[...document.querySelectorAll('#results .dish-main .hit-label')].some(e => e.textContent.trim().indexOf('还差 ') === 0 || e.textContent.trim() === '主料齐了')"),
                           'got=%r' % cdp.js("[...document.querySelectorAll('#results .dish-main .hit-label')].map(e => e.textContent.trim())"))
                check_true('主菜标了主料满足率',
                           cdp.js("[...document.querySelectorAll('#results .dish-main .tag')].some(e => e.textContent.indexOf('主料满足') >= 0)"),
                           'got=%r' % cdp.js("[...document.querySelectorAll('#results .dish-main .tag')].map(e => e.textContent.trim())"))
                check_true('候选行标了满足率 %',
                           cdp.js("document.querySelectorAll('#results .cand-right .cand-score').length") >= 1, '没有候选行')
            else:
                print('  （本轮召回为空，结果文案断言跳过）')

            # ---- 找菜：严格模式 maxMissing 必须是 null ----
            cdp.js("document.querySelector('#modeSegs [data-mode=strict]').click()")
            time.sleep(0.4)
            cdp.js("window.__reqs = []")
            cdp.js("document.querySelector('#searchBtn').click()")
            cdp.wait_for("window.__reqs.filter(r => r.url.indexOf('/api/dish/search') >= 0).length > 0", timeout=60)
            time.sleep(1.2)
            q2 = cdp.js("window.__reqs.filter(r => r.url.indexOf('/api/dish/search') >= 0).slice(-1)[0].body")
            check_true('严格模式搜索请求带载荷', isinstance(q2, dict), 'got=%r' % q2)
            if isinstance(q2, dict):
                check('严格模式 mode=strict', q2.get('mode'), 'strict')
                check('严格模式 maxMissing=null', q2.get('maxMissing'), None)

            # ---- 生成载荷：拦下这一次 POST，只验前端送了什么（不真调模型）----
            cdp.js("""
                (() => {
                  const orig = window.__origFetch;
                  window.fetch = function (u, o) {
                    const url = String(u);
                    if (url.indexOf('/api/dish/ai/dish') >= 0 && o && String(o.method || '').toUpperCase() === 'POST') {
                      window.__genBody = JSON.parse(o.body);
                      return Promise.resolve(new Response(
                        JSON.stringify({ code: 200, message: 'ok', data: { taskId: '999999999999999999', position: 1 } }),
                        { status: 200, headers: { 'Content-Type': 'application/json' } }));
                    }
                    return orig.apply(this, arguments);
                  };
                  return true;
                })()
            """)
            cdp.js("document.querySelector('#generateBtn').click()")
            cdp.wait_for("!!window.__genBody", timeout=30)
            gen = cdp.js("window.__genBody")
            check_true('生成请求已发出', isinstance(gen, dict), 'got=%r' % gen)
            if isinstance(gen, dict):
                check('生成载荷带必用食材',
                      [str(x) for x in (gen.get('mustUseIngredientIds') or [])], [str(must_id)])
                check('生成载荷带忌口', gen.get('excludeIngredients'), ['花生'])
                check('生成载荷带最长耗时', gen.get('maxCookTimeMin'), 30)
                check('生成载荷带可用厨具', gen.get('availableEquipment'), ['炒锅'])
                check_true('生成载荷带人数（数字）',
                           isinstance(gen.get('servings'), int), 'got=%r' % gen.get('servings'))
                check_true('生成载荷带已选食材 2 样',
                           len(gen.get('ingredientIds') or []) == 2, 'got=%r' % gen.get('ingredientIds'))
            cdp.js("window.fetch = window.__origFetch")
            time.sleep(0.5)

            # ---------------- 9g. 管理台 /admin（P0 授权 + P1-6/P2-2 运维入口） ----------------
            section('9g. 管理台（普通用户被挡 / 管理员可用）')
            cdp.goto(VUE_URL + 'admin')
            time.sleep(1.6)
            check('普通用户访问 /admin 被弹回工作台', cdp.js("location.pathname"), '/kitchen')
            check('顶栏不给普通用户「管理」入口',
                  cdp.js("!!document.querySelector('.nav .nav-link[data-page=admin]')"), False)

            adm = 'vueadm' + ''.join(random.choices(string.digits, k=6))
            reg_body = json.dumps({'username': adm, 'password': 'Test123456', 'nickname': 'Vue管理'}).encode()
            req = urllib.request.Request(GATEWAY + '/api/account/auth/register', data=reg_body, method='POST')
            req.add_header('Content-Type', 'application/json')
            try:
                with urllib.request.urlopen(req, timeout=25) as r:
                    reg = json.loads(r.read().decode())
                check_true('管理员账号注册成功', reg.get('code') == 200,
                           'resp=%s' % json.dumps(reg, ensure_ascii=False)[:200])
            except urllib.error.HTTPError as e:
                check_true('管理员账号注册成功', False, 'HTTP %s' % e.code)

            # 提权走库：接口没有"把自己变成管理员"的入口（也不该有）
            rows = mysql("UPDATE t_user SET role = 1 WHERE username = '%s'; SELECT role FROM t_user WHERE username = '%s';" % (adm, adm))
            check('库里 role 已提权为 1', rows[-1] if rows else None, '1')

            st, lb = api('POST', '/api/account/auth/login', {'username': adm, 'password': 'Test123456'})
            admin_data = (lb or {}).get('data') or {}
            admin_token = admin_data.get('token')
            check('登录接口回传 role=1（前端才能显示管理入口）', admin_data.get('role'), 1)
            check_true('拿到管理员 token', bool(admin_token), 'resp=%s' % json.dumps(lb, ensure_ascii=False)[:200])

            cdp.js("localStorage.clear()")
            cdp.goto(VUE_URL + '#login')
            cdp.goto(VUE_URL + '#login')
            time.sleep(0.5)
            set_login(adm, 'Test123456')
            cdp.js("document.querySelector('#loginForm').requestSubmit()")
            time.sleep(3.5)
            check('管理员登录后落在工作台', cdp.js("location.pathname"), '/kitchen')
            check('顶栏对管理员显示「管理」入口',
                  cdp.js("!!document.querySelector('.nav .nav-link[data-page=admin]')"), True)

            # Vite dev server 是「按需编译」：/admin 的路由分包首次请求要等它转换完才返回，
            # 固定 sleep 会把这种冷启动判成「被弹回工作台」，连累后面 7 条断言一起假失败。
            cdp.js("document.querySelector('.nav .nav-link[data-page=admin]').click()")
            cdp.wait_for("location.pathname === '/admin' && "
                         "!!document.querySelector('[aria-label=\"管理功能\"]')", timeout=30)
            check('管理员进入 /admin 不被弹回', cdp.js("location.pathname"), '/admin')
            check('四个功能分段都在',
                  cdp.js("document.querySelectorAll('[aria-label=\"管理功能\"] .rank-seg-btn').length"), 4)
            check('默认停在待审候选',
                  cdp.js("document.querySelector('[aria-label=\"管理功能\"] [data-tab=candidates]').getAttribute('aria-selected')"),
                  'true')
            check('管理页没有错误框', cdp.js("!!document.querySelector('.err-box')"), False)
            check_true('待审候选有列表或明确空态',
                       cdp.js("!!document.querySelector('.cand-list') || !!document.querySelector('.empty')"),
                       '两者都没有')

            cdp.js("document.querySelector('[aria-label=\"管理功能\"] [data-tab=sync]').click()")
            time.sleep(2.2)
            sync_n = cdp.js("document.querySelectorAll('.dish-stats .stat-value').length")
            check_true('同步统计格 >= 3 项', sync_n >= 3, 'got=%r' % sync_n)
            check('没有错误框（同步接口拿到数据）', cdp.js("!!document.querySelector('.err-box')"), False)
            check_true('有「手动补偿」按钮',
                       cdp.js("[...document.querySelectorAll('button')].some(b => b.textContent.trim() === '手动补偿')"),
                       '按钮没找到')

            cdp.js("document.querySelector('[aria-label=\"管理功能\"] [data-tab=nutrition]').click()")
            time.sleep(1.0)
            check_true('营养重算入口在',
                       cdp.js("[...document.querySelectorAll('button')].some(b => b.textContent.indexOf('重算') >= 0)"),
                       '按钮没找到')

            cdp.js("document.querySelector('[aria-label=\"管理功能\"] [data-tab=ops]').click()")
            time.sleep(1.0)
            check('下架输入框在', cdp.js("!!document.querySelector('#unpublishId')"), True)
            check('空 ID 时下架按钮禁用', cdp.js("document.querySelector('#unpublishId + button').disabled"), True)

            # ---------------- 9h. 营养来源标注（P2-2：详情与列表必须同口径） ----------------
            section('9h. 营养来源标注')
            st, rb = api('GET', '/api/dish/ai/rank?type=hot&page=1&size=50', token=admin_token)
            items = ((rb.get('data') or {}).get('items') or []) if (st == 200 and isinstance(rb, dict)) else []
            check_true('榜单接口可用', len(items) > 0, 'status=%s items=%d' % (st, len(items)))
            local = next((i for i in items if i.get('nutritionSource') == 2), None)
            ai_one = next((i for i in items if i.get('nutritionSource') == 1), None)
            print('  榜单里 source=2 的菜：%s' % (local.get('name') if local else '无'))
            check_true('榜单里存在「本地计算」来源的菜', local is not None, 'items=%d' % len(items))

            if local:
                cdp.goto(VUE_URL + 'dish/' + str(local['id']))
                cdp.wait_for("!!document.querySelector('.nutri-source') || !!document.querySelector('.err-box')", timeout=40)
                time.sleep(0.8)
                check('详情页标注为「营养按成分表计算」',
                      cdp.js("document.querySelector('.nutri-source')?.textContent.trim()"), '营养按成分表计算')
                body_text = str(cdp.js("document.querySelector('.nutri-caveat')?.textContent || ''"))
                check_true('详情页说明了算法口径（每 100 克成分 × 用量 ÷ 人数）',
                           '每 100 克成分' in body_text, 'got=%r' % body_text)
                check_true('本地计算的菜没有被标成 AI 估算',
                           'AI' not in str(cdp.js("document.querySelector('.nutri-source')?.textContent || ''")),
                           'got=%r' % cdp.js("document.querySelector('.nutri-source')?.textContent"))

            # 榜单里可能已经没有 source=1 的菜（整库重算后只剩极少数），改从库里找一道
            if ai_one is None:
                rows = mysql("SELECT id FROM t_dish WHERE nutrition_source = 1 AND status = 1 LIMIT 1;")
                if rows:
                    ai_one = {'id': rows[0].split('\t')[0]}
                    print('  库里 source=1 且已发布的菜：%s' % ai_one['id'])

            if ai_one is not None:
                cdp.goto(VUE_URL + 'dish/' + str(ai_one['id']))
                cdp.wait_for("!!document.querySelector('.nutri-source')", timeout=40)
                time.sleep(0.8)
                check_true('AI 估算来源的菜明确标出「AI 估算」',
                           'AI 估算' in str(cdp.js("document.querySelector('.nutri-source')?.textContent || ''")) or
                           'AI 估算' in str(cdp.js("document.body.innerText")),
                           'got=%r' % cdp.js("document.querySelector('.nutri-source')?.textContent.trim()"))
            else:
                # 库里的 source=1 都是未发布的草稿（详情页取不到，返回「菜品不存在」），
                # 于是这一分支在真实数据上覆盖不到。这里做一次**可恢复的临时标记**：
                # 把一道已发布菜的 nutrition_source 临时改成 1，验证页面确实标成「AI 估算」，
                # 之后在 finally 里按原值还原 —— 不还原就会留下一道口径错误的菜。
                rows = mysql("SELECT id, nutrition_source, nutrition_verified FROM t_dish "
                             "WHERE status = 1 ORDER BY id LIMIT 1;")
                check_true('找到可用于临时标记的已发布菜', len(rows) > 0, 'rows=%r' % rows)
                if rows:
                    did, old_src, old_ver = (rows[0].split('\t') + ['', ''])[:3]
                    mysql("UPDATE t_dish SET nutrition_source = 1, nutrition_verified = 0 WHERE id = %s;" % did)
                    print('  （临时把菜 %s 标成 AI 估算以覆盖该分支，稍后还原 source=%s verified=%s）'
                          % (did, old_src, old_ver))
                    try:
                        cdp.goto(VUE_URL + 'dish/' + did)
                        cdp.wait_for("!!document.querySelector('.nutri-source')", timeout=40)
                        time.sleep(0.8)
                        check_true('AI 估算来源的菜明确标出「AI 估算」',
                                   'AI 估算' in str(cdp.js("document.querySelector('.nutri-source')?.textContent || ''")) or
                                   'AI 估算' in str(cdp.js("document.body.innerText")),
                                   'got=%r' % cdp.js("document.querySelector('.nutri-source')?.textContent.trim()"))
                    finally:
                        mysql("UPDATE t_dish SET nutrition_source = %s, nutrition_verified = %s WHERE id = %s;"
                              % (old_src, old_ver, did))
                        back = mysql("SELECT nutrition_source, nutrition_verified FROM t_dish WHERE id = %s;" % did)
                        print('  （已还原菜 %s 的营养来源：%r）' % (did, back))

            # ---------------- 9i. 手写菜谱（页内面板 → 真落库 → 详情页同口径） ----------------
            section('9i. 手写菜谱（自己写一道，不用 AI）')

            # 菜名带随机后缀：已发布菜之间 nameKey 全局唯一，
            # 固定名字的话第二次跑必然撞名、只能留在草稿，得到一屏假失败
            MANUAL_NAME = '手写验证' + ''.join(random.choices(string.digits, k=6))

            def click_sel(sel):
                """点一下选择器命中的元素；元素不在就返回 False —— 别让 JS 抛错把断言吞掉

                选择器用**单引号**包进 JS：脚本里写的选择器本身就带双引号
                （`.ing[data-id="101"]`），再用双引号包一层就成了
                `querySelector(".ing[data-id="101"]")` → SyntaxError: missing ) after argument list
                """
                return cdp.js("(function(){var el=document.querySelector('%s');"
                              "if(!el)return false;el.click();return true})()" % sel)

            def set_val(sel, val):
                """填值并触发 input 事件：Vue 的 v-model 只听 input，直接改 value 不算数"""
                return cdp.js("(function(){var el=document.querySelector('%s');if(!el)return false;"
                              "el.value=%s;el.dispatchEvent(new Event('input',{bubbles:true}));return true})()"
                              % (sel, json.dumps(val)))

            def es_delete(doc_id):
                """直接删 ES 文档：验证菜不该留在索引里，而全量重建要跑一两分钟"""
                req = urllib.request.Request(
                    'http://127.0.0.1:9200/dish_index/_doc/%s' % doc_id, method='DELETE')
                try:
                    with urllib.request.urlopen(req, timeout=15) as r:
                        return r.status
                except urllib.error.HTTPError as e:
                    return e.code
                except Exception:
                    return 0

            cdp.goto(VUE_URL + 'kitchen')
            cdp.wait_for("!!document.querySelector('#manualOpenBtn')", timeout=45)
            check_true('食材页有「手写菜谱」入口',
                       cdp.js("document.querySelector('#manualOpenBtn').textContent.trim().length > 0"),
                       '按钮没找到')
            check('点选番茄（101）', click_sel('.ing[data-id="101"]'), True)
            check('点选鸡蛋（102）', click_sel('.ing[data-id="102"]'), True)
            check('已选两样', cdp.js("document.querySelectorAll('#pickedBar .picked-chip').length"), 2)
            check('打开手写菜谱', click_sel('#manualOpenBtn'), True)
            time.sleep(0.6)
            check('面板是页内覆盖层（没有跳走）', cdp.js("location.pathname"), '/kitchen')
            check('面板出现', cdp.js("!!document.querySelector('#manualPanel .manual-card')"), True)
            check('已选食材预填进行 1', cdp.js("document.querySelector('#manualRowName0').value"), '番茄')
            check('已选食材预填进行 2', cdp.js("document.querySelector('#manualRowName1').value"), '鸡蛋')
            check('不够门槛时上传按钮禁用', cdp.js("document.querySelector('#manualSubmit').disabled"), True)
            # 面板一打开就会试算一次（预填的食材还没填克数），等这一版回来再说
            # 注意：问题清零后 #manualProblems 这个 <ul> 会整个消失（v-if），
            # 所以一律用 querySelectorAll('#manualProblems li').length 数条数 ——
            # 直接取 .children 在"零问题"时会对 null 取属性，抛 TypeError 变成假失败
            cdp.wait_for("document.querySelectorAll('#manualProblems li').length > 0", timeout=30)
            check_true('还没填完就告诉你差什么（别等上传才报）',
                       cdp.js("document.querySelectorAll('#manualProblems li').length") > 0,
                       'problems=%s' % cdp.js("(document.querySelector('#manualProblems') || {}).innerText"))

            # 断言"前端到底送了什么"，而不是"屏幕上的数字看着对"：
            # 试算文本是 300ms 防抖 + 异步回来的，填完立刻读多半还是上一版表单的结果
            cdp.js("""
                (() => {
                  window.__reqs = [];
                  if (!window.__origFetch) window.__origFetch = window.fetch;
                  const orig = window.__origFetch;
                  window.fetch = function (u, o) {
                    try {
                      window.__reqs.push({ url: String(u), body: (o && o.body) ? JSON.parse(o.body) : null });
                    } catch (e) {}
                    return orig.apply(this, arguments);
                  };
                  return true;
                })()
            """)

            check('填菜名', set_val('#manualName', MANUAL_NAME), True)
            check('填番茄克数', set_val('#manualRowGram0', '200'), True)
            check('填鸡蛋克数', set_val('#manualRowGram1', '100'), True)
            MANUAL_STEPS = ['番茄切块备用', '鸡蛋打散炒成块', '下番茄炒出沙后合炒调味']
            for i, step in enumerate(MANUAL_STEPS):
                check('填第 %d 步' % (i + 1), set_val('#manualStep%d' % i, step), True)

            # 等到"带克数的那一版"载荷真的发出去（防抖会连发好几条，取最后一条）
            cdp.wait_for("window.__reqs.filter(r => r.url.indexOf('/ai/dish/manual/preview') >= 0 && "
                         "r.body && (r.body.ingredients || []).filter(x => x.grams !== null).length >= 2).length > 0",
                         timeout=60)
            pv = cdp.js("window.__reqs.filter(r => r.url.indexOf('/ai/dish/manual/preview') >= 0).slice(-1)[0].body")
            pv = pv if isinstance(pv, dict) else {}
            print('  试算载荷：%s' % json.dumps(pv, ensure_ascii=False))
            check('载荷带菜名', pv.get('name'), MANUAL_NAME)
            check('载荷带人数', pv.get('servings'), 2)
            check('载荷带两样食材（名字 + 克数）',
                  [[i.get('name'), i.get('grams')] for i in (pv.get('ingredients') or [])
                   if i.get('grams') is not None], [['番茄', 200], ['鸡蛋', 100]])
            check('载荷带三条步骤', [s for s in (pv.get('steps') or [])], MANUAL_STEPS)
            check('载荷里的食材 id 由本地食材表反查（101/102）',
                  [i.get('ingredientId') for i in (pv.get('ingredients') or [])], [101, 102])

            # 再看面板自己怎么说：问题清空 + 试算文本换成带克数的那一版
            cdp.wait_for("document.querySelectorAll('#manualProblems li').length === 0", timeout=60)
            preview_text = str(cdp.js("document.querySelector('#manualPreviewText').textContent")).strip()
            print('  实时试算：%s' % preview_text)
            check_true('试算给出每人份热量', 'kcal' in preview_text, 'got=%r' % preview_text)
            check_true('试算说明算法口径（覆盖率）', '覆盖率' in preview_text, 'got=%r' % preview_text)
            check_true('两样食材都对上了成分表', '2/2' in preview_text, 'got=%r' % preview_text)
            check('没有阻塞问题', cdp.js("document.querySelectorAll('#manualProblems li').length"), 0)
            check('面板说够门槛了',
                  str(cdp.js("document.querySelector('#manualFootNote').textContent")).strip(),
                  '够门槛了，上传后直接进找菜库')
            check('够门槛后可以上传', cdp.js("document.querySelector('#manualSubmit').disabled"), False)

            check('点上传', click_sel('#manualSubmit'), True)
            published_toast = cdp.wait_for(
                "(document.body.innerText.indexOf('已发布到找菜库') >= 0) ? 'ok' : false", timeout=60)
            check('提示「已发布到找菜库」', published_toast, 'ok')
            check('上传后面板自动收起', cdp.js("!!document.querySelector('#manualPanel')"), False)
            man_sent = cdp.js("window.__reqs.filter(r => /\\/ai\\/dish\\/manual$/.test(r.url)).slice(-1)[0] || null")
            check('上传请求带的就是面板里那份表单',
                  [[i.get('name'), i.get('grams')] for i in (((man_sent or {}).get('body') or {}).get('ingredients') or [])
                   if i.get('grams') is not None], [['番茄', 200], ['鸡蛋', 100]])

            st, rb = api('GET', '/api/dish/ai/my/dishes', token=admin_token)
            items = ((rb.get('data') or []) if (st == 200 and isinstance(rb, dict)) else [])
            created = next((d for d in items if str(d.get('name', '')) == MANUAL_NAME), None)
            check_true('「我的菜」里查得到刚上传的菜', created is not None, 'items=%d' % len(items))
            manual_id = (created or {}).get('id') or (created or {}).get('dishId')
            print('  新菜 id=%s' % manual_id)
            check_true('拿到新菜 id', bool(manual_id), 'got=%r' % (created,))

            if manual_id:
                rows = mysql("SELECT status, source, nutrition_source, nutrition_verified, energy_kcal "
                             "FROM t_dish WHERE id = %s;" % manual_id)
                parts = (rows[0].split('\t') if rows else [])
                print('  库里 status/source/营养来源/核验/热量 = %r' % (parts,))
                pad = parts + ['', '', '', '', '']
                check('状态=已发布', pad[0], '1')
                check('来源=用户自定义（3）', pad[1], '3')
                check('营养来源=本地计算（2）', pad[2], '2')
                check('营养已核验', pad[3], '1')
                check_true('热量算出来了',
                           pad[4] not in ('', 'NULL') and float(pad[4]) > 0, 'energy=%r' % pad[4])

                ings = mysql("SELECT ingredient_id, amount_gram, main_flag FROM t_dish_ingredient "
                             "WHERE dish_id = %s ORDER BY sort_no;" % manual_id)
                print('  明细：%r' % ings)
                check('面板里的空行没进明细（只两样食材）', len(ings), 2)
                check_true('明细都挂上了主数据 ID',
                           all((r.split('\t')[0] not in ('NULL', '')) for r in ings), 'rows=%r' % ings)
                check('第一样算主料', (ings[0].split('\t') + ['', '', ''])[2] if ings else None, '1')

                cdp.goto(VUE_URL + 'dish/' + str(manual_id))
                cdp.wait_for("!!document.querySelector('.nutri-source') || !!document.querySelector('.err-box')",
                             timeout=45)
                time.sleep(0.8)
                check('详情页标「营养按成分表计算」',
                      cdp.js("document.querySelector('.nutri-source')?.textContent.trim()"), '营养按成分表计算')
                detail_text = str(cdp.js("document.body.innerText"))
                check_true('详情页把来源标成「我的创作」', '我的创作' in detail_text, 'got=%r' % detail_text[:120])
                check_true('详情页有我们写的菜名', MANUAL_NAME in detail_text, '菜名没出现')

            # 重名这条路：唯一键 uk_name_creator(name_key, creator_id) 不让同一人写两道同名菜，
            # 而且它**不区分草稿/已发布、也不区分 deleted** —— 所以"已发布菜不重名"那一层校验挡不住它。
            # 用户会走两条路，两条都得给一句人话：
            #   ① 面板里再填一次同名 -> preview 就把问题列出来、上传按钮直接灰掉（不用等提交）
            #   ② 绕过面板打接口（并发提交 / 逻辑删除残留）-> 服务端自己兜住
            # 以前是提交后才撞唯一键 -> 兜底 500「服务器开小差了」，所以这两条都要断言。
            # 注意：走到这里人还停在菜品详情页（上面刚 cdp.goto 过 dish/<id>），
            # 得先回工作台 —— 手写面板是"就地展开"，换了路由按钮就不在当前 DOM 里
            cdp.goto(VUE_URL + 'kitchen')
            cdp.wait_for("!!document.querySelector('#manualOpenBtn')", timeout=45)
            check('重名再开一次面板', click_sel('#manualOpenBtn'), True)
            time.sleep(0.6)
            set_val('#manualName', MANUAL_NAME)
            set_val('#manualRowName0', '番茄')
            set_val('#manualRowName1', '鸡蛋')
            set_val('#manualRowGram0', '200')
            set_val('#manualRowGram1', '100')
            for i, step in enumerate(MANUAL_STEPS):
                set_val('#manualStep%d' % i, step)
            cdp.wait_for("((document.querySelector('#manualProblems') || {}).innerText || '')"
                         ".indexOf('换个名字') >= 0", timeout=60)
            dup_hint = str(cdp.js("(document.querySelector('#manualProblems') || {}).innerText || ''"))
            print('  面板重名提示：%s' % dup_hint.replace('\n', ' / '))
            check_true('面板提前报重名（不用等提交）', '换个名字' in dup_hint, 'problems=%r' % dup_hint)
            check('重名时上传按钮禁用', cdp.js("document.querySelector('#manualSubmit').disabled"), True)
            click_sel('#manualCancel')
            time.sleep(0.4)

            st, rb = api('POST', '/api/dish/ai/dish/manual', {
                'name': MANUAL_NAME,       # 故意用刚发布的同名
                'servings': 2,
                'ingredients': [{'name': '番茄', 'ingredientId': 101, 'grams': 200},
                                {'name': '鸡蛋', 'ingredientId': 102, 'grams': 100}],
                'steps': ['番茄切块备用', '鸡蛋打散炒成块', '下番茄炒出沙后合炒调味']
            }, token=admin_token)
            dup_msg = str((rb or {}).get('message') or '') if isinstance(rb, dict) else ''
            print('  重名接口返回：http=%s code=%s message=%s' % (st, (rb or {}).get('code'), dup_msg))
            check('重名提交 HTTP 200', st, 200)
            check_true('不再回「服务器开小差了」', '服务器开小差' not in dup_msg, 'message=%r' % dup_msg)
            check_true('重名给出了能照着做的原因',
                       ('换个名字' in dup_msg) or ('同名' in dup_msg), 'message=%r' % dup_msg)
            # 重名压根不该落第二条。中文名不写进 SQL（命令行过 ANSI 代码页，匹不匹得上靠运气）：
            # 只按 ASCII 用户名数行数，再看原来那道有没有被连累
            dup_id = None
            same_name = mysql("SELECT COUNT(*) FROM t_dish d JOIN t_user u ON u.id = d.creator_id "
                              "WHERE u.username = '%s';" % adm)
            check('库里这个账号还是一道菜', (same_name[0] if same_name else '?'), '1')
            if manual_id:
                rows = mysql("SELECT status FROM t_dish WHERE id = %s;" % manual_id)
                check('原来那道没被连累（仍已发布）', (rows[0] if rows else '?'), '1')

            # 收尾：验证菜不留在库里，否则每跑一次找菜库就多一道测试菜
            cleaned = []
            for did in [manual_id, dup_id]:
                if not did:
                    continue
                mysql("DELETE FROM t_dish_ingredient WHERE dish_id = %s;" % did)
                mysql("DELETE FROM t_es_sync_task WHERE dish_id = %s;" % did)
                mysql("DELETE FROM t_dish WHERE id = %s;" % did)
                code = es_delete(did)
                if code not in (200, 404):
                    print('  [注意] ES 文档 %s 删除返回 %s（ES 没跑时是 0）' % (did, code))
                cleaned.append(str(did))
            if cleaned:
                left = mysql("SELECT COUNT(*) FROM t_dish WHERE id IN (%s);" % ','.join(cleaned))
                check('验证菜已从库里清掉', (left[0] if left else '?'), '0')

            # ---------------- 9j. 推荐页（冷启动推荐流） ----------------
            # 这一段是「推荐页整页 500」的回归测试：当时 t_dish_push_log 这张表不在库里，
            # 推荐候选查询里的 24 小时去重子查询直接报 Table doesn't exist，
            # 页面就成了「没能加载推荐 / 服务器开小差了」。所以先查表在不在，再看接口、再看页面。
            section('9j. 推荐页（冷启动推荐流）')
            tbl = mysql("SELECT COUNT(*) FROM information_schema.tables "
                        "WHERE table_schema = 'project0914' AND table_name = 't_dish_push_log';")
            check('推流日志表在库里（缺它就是整页 500）', (tbl[0] if tbl else '?'), '1')

            st, rb = api('GET', '/api/dish/ai/recommend?page=1&size=5', token=admin_token)
            check('推荐接口 HTTP 200', st, 200)
            feed = (rb.get('data') or {}) if isinstance(rb, dict) else {}
            feed_items = feed.get('items') or []
            check_true('推荐接口返回了菜', len(feed_items) > 0, 'count=%r' % feed.get('count'))
            check_true('每道菜都说了为什么被推（matchType）',
                       all(str(d.get('matchType') or '') for d in feed_items),
                       'matchTypes=%r' % [d.get('matchType') for d in feed_items])

            # 24 小时去重的依据必须真的写下来了，否则下次刷还是同一批（推荐流会像坏了）
            plog = mysql("SELECT COUNT(*) FROM t_dish_push_log l JOIN t_user u ON u.id = l.user_id "
                         "WHERE u.username = '%s';" % adm)
            check_true('推流日志记下了这次推荐（24h 去重的依据）',
                       bool(plog) and str(plog[0]).isdigit() and int(plog[0]) >= 1, 'rows=%r' % plog)

            cdp.goto(VUE_URL + 'recommend')
            cdp.wait_for("!!document.querySelector('.page-recommend')", timeout=45)
            page_text = cdp.wait_for(
                "(document.querySelector('.page-recommend .rec-row') || "
                "document.querySelector('.page-recommend .err-box') || "
                "document.querySelector('.page-recommend .empty')) ? document.body.innerText : false",
                timeout=45)
            check('推荐页没有错误框', cdp.js("!!document.querySelector('.page-recommend .err-box')"), False)
            check_true('页面文案里没有「没能加载推荐」', '没能加载推荐' not in str(page_text),
                       'got=%r' % str(page_text)[:160])
            rec_rows = cdp.js("document.querySelectorAll('.page-recommend .rec-row').length")
            check_true('推荐页渲染出了菜', rec_rows and int(rec_rows) > 0, 'rec-row 数量=%r' % rec_rows)
            check_true('每行都带来源角标（让用户看懂为什么是这道菜）',
                       cdp.js("document.querySelectorAll('.page-recommend .rec-row .tag').length") > 0,
                       '没找到角标')

            cdp.js("localStorage.clear()")
        else:
            section('9. 真实登录链路')
            print('  后端未启动，跳过')

        # ---------------- 10. 控制台无报错 ----------------
        section('10. 控制台健康度')
        console_err = cdp.js("window.__vueErrors__ || null")
        check('页面无未捕获错误标记', console_err, None)

    finally:
        cdp.close()
        proc.terminate()
        try:
            proc.wait(timeout=6)
        except Exception:
            proc.kill()

    section('汇总')
    print('  PASS: %d   FAIL: %d' % (PASS, FAIL))
    if FAILURES:
        print()
        print('  失败项：')
        for f in FAILURES:
            print('    - %s' % f)
    print()
    return 0 if FAIL == 0 else 1


if __name__ == '__main__':
    sys.exit(main())
