# -*- coding: utf-8 -*-
"""
Vue 版前端入口服务（跑构建产物 dist/）

它做了三件事：
  1. 静态文件：把 dist/ 发出去
  2. /api/** 反向代理到网关 7000 —— 于是**页面与接口同源**
  3. SPA 兜底：找不到的路径一律回 index.html
     （vue-router 用的是 history 模式，没有这条兜底，刷新 /rank 会 404）

【为什么不直接用 vite dev / preview】
  - dev server 是按需编译的，首屏要先等编译，且在这台机器上容易被回收；
  - 这个脚本是纯静态 + 反代，启动即用、行为稳定，也更接近"真的部署出去"的样子。
  开发时想热更新再用 npm run dev。

用法：
    python frontend-vue/serve-dist.py            # 默认 5600
    python frontend-vue/serve-dist.py 8088       # 指定端口

【端口选 5600 而不是 5500 是刻意的】
  js 里的 config.js 会按"页面从哪来"推断接口地址：
  来自 5500（约定为纯静态服务器）→ 直打本机网关 7000（跨域）；
  其他任何来源 → 同源。这里走同源，浏览器连预检都不会发。
"""
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.request
from http.server import HTTPServer, SimpleHTTPRequestHandler

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(ROOT, 'dist')
GATEWAY = 'http://127.0.0.1:7000'
DEFAULT_PORT = 5600

# 反代时不转发这些逐跳首部
HOP_BY_HOP = {
    'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
    'te', 'trailers', 'transfer-encoding', 'upgrade', 'host', 'content-length'
}


class VueHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DIST, **kwargs)

    # ---------------- 日志 ----------------
    def log_message(self, fmt, *args):
        # 只打一行简短的，避免刷屏
        sys.stdout.write('  %s\n' % (fmt % args))
        sys.stdout.flush()

    # ---------------- 反代 ----------------
    def _proxy(self, method):
        url = GATEWAY + self.path
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else None

        req = urllib.request.Request(url, data=body, method=method)
        for k, v in self.headers.items():
            if k.lower() not in HOP_BY_HOP:
                req.add_header(k, v)

        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                payload = resp.read()
                self.send_response(resp.status)
                for k, v in resp.headers.items():
                    if k.lower() not in HOP_BY_HOP:
                        self.send_header(k, v)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
        except urllib.error.HTTPError as e:
            payload = e.read()
            self.send_response(e.code)
            for k, v in e.headers.items():
                if k.lower() not in HOP_BY_HOP:
                    self.send_header(k, v)
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as e:
            msg = json.dumps({'code': 502, 'message': '网关不可达：%s' % e},
                             ensure_ascii=False).encode('utf-8')
            self.send_response(502)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)

    def do_GET(self):
        if self.path.startswith('/api/'):
            return self._proxy('GET')
        return super().do_GET()

    def do_POST(self):
        if self.path.startswith('/api/'):
            return self._proxy('POST')
        self.send_error(405)

    def do_PUT(self):
        if self.path.startswith('/api/'):
            return self._proxy('PUT')
        self.send_error(405)

    def do_DELETE(self):
        if self.path.startswith('/api/'):
            return self._proxy('DELETE')
        self.send_error(405)

    # ---------------- SPA 兜底 ----------------
    def send_head(self):
        # 先按真实文件找；找不到就回 index.html（history 模式路由靠这条）
        path = self.translate_path(self.path)
        if not os.path.exists(path):
            self.path = '/index.html'
        return super().send_head()


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PORT

    if not os.path.isdir(DIST):
        print('找不到构建产物：%s' % DIST)
        print('请先构建：cd frontend-vue && npm run build')
        return 2
    if not os.path.isfile(os.path.join(DIST, 'index.html')):
        print('dist/index.html 不存在，请重新构建')
        return 2

    mimetypes.add_type('application/javascript', '.js')
    mimetypes.add_type('text/css', '.css')

    print('=' * 56)
    print('  AI 灵感厨房 · Vue 版（dist）')
    print('=' * 56)
    print('  页面:  http://127.0.0.1:%d' % port)
    print('  接口:  /api/**  ->  %s' % GATEWAY)
    print('  静态:  %s' % DIST)
    print('  按 Ctrl+C 停止')
    print()

    httpd = HTTPServer(('127.0.0.1', port), VueHandler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print('\n已停止')
    return 0


if __name__ == '__main__':
    sys.exit(main())
