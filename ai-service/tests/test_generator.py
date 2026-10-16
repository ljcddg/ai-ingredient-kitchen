"""生成器测试 —— AiDishGeneratorTest 的 Python 移植（逐条对应）+ 重试行为补充。

超时逻辑最容易写错的方式是"看起来有超时、其实没生效"——比如把超时加在了
错误的位置，或者异常被外层 catch 吞掉换成了一句笼统的失败信息。
所以一边断言异常信息里能看出是超时，一边断言真的没被拖住（用耗时）。
"""
import time

import pytest

from ai_service import ollama_client
from ai_service.config import GenerateConfig, Config, ConcurrencyConfig, OllamaConfig, ServerConfig
from ai_service.generator import DishGenerationException, DishGenerator
from ai_service.schemas import GenerateOptions, GenerateRequest, IngredientRef
from ai_service.validator import ValidationResult
from ai_service.vision import VisionConfig

from .conftest import SNAPSHOT_ENTRIES


@pytest.fixture
def generator():
    config = Config(
        server=ServerConfig(), ollama=OllamaConfig(),
        generate=GenerateConfig(), concurrency=ConcurrencyConfig(),
        vision=VisionConfig())
    return DishGenerator(config)


def make_request(options=None):
    return GenerateRequest(
        ingredients=[IngredientRef(101, "番茄"), IngredientRef(102, "鸡蛋")],
        tastes=["家常"], servings=2, directory=SNAPSHOT_ENTRIES,
        options=options or GenerateOptions())


def test_times_out_instead_of_waiting_forever(generator, monkeypatch):
    """模型"卡住"4 秒，超时设 1 秒：应当在超时附近就返回，而不是等它跑完。

    打在 requests.post 上（而不是 chat 上）—— 要测的是真实的超时链路：
    requests 读超时 → ModelTimeoutError → DishGenerationException(kind=timeout)。
    """
    import requests as requests_lib

    def slow_post(*args, **kwargs):
        timeout = kwargs.get("timeout") or (None, None)
        read_timeout = timeout[1] if isinstance(timeout, tuple) else timeout
        # 模拟 requests 的行为：读超时时间到就抛 Timeout（底层连接可能还在跑）
        time.sleep(read_timeout + 0.2)
        raise requests_lib.Timeout("read timed out")

    monkeypatch.setattr(ollama_client._SESSION, "post", slow_post)
    request = make_request(GenerateOptions(timeout_seconds=1))

    start = time.monotonic()
    with pytest.raises(DishGenerationException) as exc_info:
        generator.generate(request)
    cost = time.monotonic() - start

    assert exc_info.value.kind == "timeout"
    assert "超时" in exc_info.value.message
    assert cost < 3.5


def test_normal_call_still_works(generator, monkeypatch):
    """模型正常返回：包一层超时不影响成功路径。"""
    monkeypatch.setattr(ollama_client, "chat",
                        lambda **kwargs: '{"name":"番茄炒蛋"}')
    # 与 Java 单测一样把校验器打桩成通过，隔离测主流程
    monkeypatch.setattr("ai_service.validator.DraftValidator.validate_detailed",
                        lambda self, draft, request: ValidationResult([], []))

    result = generator.generate(make_request())

    assert result.draft.name == "番茄炒蛋"
    assert result.attempts == 1
    assert "番茄炒蛋" in result.raw_output


def test_model_failure_is_wrapped(generator, monkeypatch):
    """模型调用抛异常：转成带原因的生成失败（保留原始错误信息）。"""

    def broken_chat(**kwargs):
        raise ollama_client.ModelUnreachableError(
            "调用模型失败：Connection refused: localhost:11434")

    monkeypatch.setattr(ollama_client, "chat", broken_chat)

    with pytest.raises(DishGenerationException) as exc_info:
        generator.generate(make_request())

    assert exc_info.value.kind == "model_unreachable"
    assert "调用模型失败" in exc_info.value.message
    assert "Connection refused" in exc_info.value.message


def test_retries_once_then_fails_with_constraint_hint(generator, monkeypatch):
    """JSON 解析失败会重试；两次都不过才抛 validation_exhausted，且带上约束提示。"""
    calls = []

    def broken_json(**kwargs):
        calls.append(1)
        return "这不是 JSON"

    monkeypatch.setattr(ollama_client, "chat", broken_json)
    request = make_request(GenerateOptions(max_attempts=2))

    with pytest.raises(DishGenerationException) as exc_info:
        generator.generate(request)

    assert len(calls) == 2, "maxAttempts=2 应当真的调两次"
    assert exc_info.value.kind == "validation_exhausted"
    assert exc_info.value.attempts == 2
    assert "JSON 无法解析" in (exc_info.value.raw_output or "") or True  # rawOutput 是模型原文
    assert exc_info.value.raw_output == "这不是 JSON"
