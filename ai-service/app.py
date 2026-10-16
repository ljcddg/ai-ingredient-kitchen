"""Flask 应用工厂。

开发：python app.py（或 flask run -p 8090）
常驻：gunicorn -w 2 --threads 8 -b 127.0.0.1:8090 app:app
  （--threads 8：模型调用是长耗时请求，worker 线程被信号量挡住时不能影响 /health）

注意用绝对导入（ai_service.xxx）而不是相对导入：本文件既会被
`python app.py` 直接跑，也会被 gunicorn 以 `app:app` 方式导入。
"""
from __future__ import annotations

import logging

from flask import Flask

from ai_service.config import load_config
from ai_service.generator import DishGenerator
from ai_service.routes import bp, health as health_view
from ai_service.vision import YoloDetector


def create_app(config_path=None) -> Flask:
    # 必须最先配置日志：load_config 和 DishGenerator 初始化都会打日志，
    # 放到后面的话根 logger 还没有 handler，INFO 级别会被丢掉
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s")

    app = Flask(__name__)
    app.config["AI_SERVICE"] = load_config(config_path)
    app.extensions["dish_generator"] = DishGenerator(app.config["AI_SERVICE"])
    # YOLO 检测器：构造很轻（模型懒加载，首次识别请求才加载权重），
    # 没装 ultralytics 时生成接口照样可用
    app.extensions["yolo_detector"] = YoloDetector(app.config["AI_SERVICE"].vision)
    app.register_blueprint(bp)
    # 探活走根路径是惯例；/api/v1/health 也保留（契约里写的是这个）
    app.add_url_rule("/health", endpoint="health_root", view_func=health_view)
    return app


app = create_app()

if __name__ == "__main__":
    config = app.config["AI_SERVICE"]
    app.run(host=config.server.host, port=config.server.port)
