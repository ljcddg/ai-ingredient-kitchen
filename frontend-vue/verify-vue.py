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

    proc = subprocess.Popen(
        [browser, '--headless=new', '--disable-gpu',
         '--remote-debugging-port=%d' % CDP_PORT,
         # 新版 Chrome 默认拒绝非白名单 origin 的 CDP 连接，必须显式放开
         '--remote-allow-origins=*',
         '--user-data-dir=' + profile, '--no-first-run', '--no-default-browser-check',
         'about:blank'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

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
            check('分段控件三个选项',
                  cdp.js("document.querySelectorAll('.rank-seg-btn').length"), 3)
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
