"""Ollama 客户端 —— 直连 REST API，比 Spring AI 那套 Options/Prompt/ChatResponse 直觉得多。

POST {base_url}/api/chat，format=json 约束解码（不设的话输出会被 ```json
代码块包住，解析直接失败）。
"""
from __future__ import annotations

import logging
import time
from typing import Optional

import requests

logger = logging.getLogger(__name__)

# Ollama 永远是本机地址，显式关掉环境变量里的代理：
# 开发机常开着 HTTP_PROXY（IDE / 内网工具注入的），requests 默认尊重它们，
# 会把发往 127.0.0.1:11434 的请求也送去代理 —— 表现为"代理连不上上游"，
# 而不是直连 Ollama，排查时会完全找错方向。
_SESSION = requests.Session()
_SESSION.trust_env = False


class OllamaError(Exception):
    """模型调用失败。kind 决定 Java 侧 error_msg 的归因。"""

    kind = "internal"


class ModelUnreachableError(OllamaError):
    """连不上 Ollama（没起、模型没拉、返回非 200）。"""

    kind = "model_unreachable"


class ModelTimeoutError(OllamaError):
    """单次调用超过超时时间。"""

    kind = "timeout"


def brief(text: Optional[str], limit: int = 200) -> str:
    """压成一行、截断 —— 错误信息要能塞进日志和 error_msg。"""
    if text is None:
        return "null"
    one_line = " ".join(str(text).split())
    return one_line if len(one_line) <= limit else one_line[:limit] + "..."


def _elapsed_ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


def chat(base_url: str, model: str, system_prompt: str, user_prompt: str,
         temperature: float, timeout_seconds: int,
         connect_timeout_seconds: int = 10) -> str:
    """调一次模型，返回文本内容。

    语义边界与 Java 版一致：这里的超时只保证"应用侧不被一条请求拖住"，
    底层 HTTP 请求不会因为超时就被取消（和"用户取消"是同一种情况：
    应用侧放手，模型侧可能还在跑、还占着 GPU）。
    """
    url = base_url.rstrip("/") + "/api/chat"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "stream": False,
        "format": "json",
        "options": {"temperature": temperature},
    }
    logger.info("调用模型：model=%s，温度=%s，超时=%d 秒，提示词 %d 字",
                model, temperature, timeout_seconds, len(system_prompt) + len(user_prompt))
    started = time.monotonic()
    try:
        response = _SESSION.post(url, json=payload,
                                 timeout=(connect_timeout_seconds, timeout_seconds))
    except requests.Timeout:
        logger.warning("模型调用超时：model=%s，已等待 %d ms", model, _elapsed_ms(started))
        raise ModelTimeoutError(
            f"模型响应超时（超过 {timeout_seconds} 秒），已放弃本次生成。"
            f"可以重试，或换个更常见的食材组合")
    except requests.RequestException as e:
        logger.warning("模型调用失败：model=%s，连接层错误：%s", model, brief(e))
        # 调不通（Ollama 没起、模型没拉）直接抛出去，让上层把原因写进 error_msg
        # —— 重试也不会变好
        raise ModelUnreachableError(f"调用模型失败：{brief(e)}")

    if response.status_code != 200:
        logger.warning("模型调用失败：Ollama 返回 %d，%s", response.status_code, brief(response.text))
        raise ModelUnreachableError(
            f"调用模型失败：Ollama 返回 {response.status_code} {brief(response.text)}")

    try:
        data = response.json()
    except ValueError as e:
        raise ModelUnreachableError(f"调用模型失败：响应不是合法 JSON（{brief(e)}）")

    content = (data.get("message") or {}).get("content")
    if content is None:
        raise ModelUnreachableError("调用模型失败：响应里没有 message.content")
    logger.info("模型返回：耗时 %d ms，输出 %d 字", _elapsed_ms(started), len(str(content)))
    return str(content).strip()


def ping(base_url: str, timeout_seconds: int = 3) -> bool:
    """健康检查：Ollama 在不在。"""
    try:
        response = _SESSION.get(base_url.rstrip("/") + "/api/version",
                                timeout=timeout_seconds)
        return response.status_code == 200
    except requests.RequestException as e:
        logger.warning("Ollama 健康检查失败：%s，原因：%s", base_url, brief(e))
        return False
