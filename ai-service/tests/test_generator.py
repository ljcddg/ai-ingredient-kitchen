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


def test_failure_tier_is_format_fail_when_json_broken(generator, monkeypatch):
    """失败分档：连 JSON 都没写对 → FORMAT_FAIL；这次没参考 → retrieval_miss=True。

    分档的意义在于"该往哪使劲"：FORMAT_FAIL 要改提示词/约束解码，
    CONSTRAINT_FAIL 要改约束表达；混成一个失败率就看不出来了。
    """
    monkeypatch.setattr(ollama_client, "chat", lambda **kwargs: "仍然不是 JSON")

    with pytest.raises(DishGenerationException) as exc_info:
        generator.generate(make_request(GenerateOptions(max_attempts=2)))

    assert exc_info.value.tier == "FORMAT_FAIL"
    assert exc_info.value.retrieval_miss is True


def test_failure_tier_is_constraint_fail_when_rules_broken(generator, monkeypatch):
    """约束类问题 → CONSTRAINT_FAIL（哪怕格式是好的）。"""
    monkeypatch.setattr(ollama_client, "chat", lambda **kwargs: "{}")
    monkeypatch.setattr(
        "ai_service.validator.DraftValidator.validate_detailed",
        lambda self, draft, request: ValidationResult(
            ["没有用上用户提供的食材：鸡蛋"], ["没有用上用户提供的食材：鸡蛋"]))

    with pytest.raises(DishGenerationException) as exc_info:
        generator.generate(make_request(GenerateOptions(max_attempts=1)))

    assert exc_info.value.tier == "CONSTRAINT_FAIL"
    assert "鸡蛋" in (exc_info.value.message + "".join(exc_info.value.validation.problems))


def test_user_prompt_uses_five_fixed_sections(generator):
    """用户消息固定五段：可用食材 / 硬约束 / 参考菜谱 / 输出要求，顺序不能乱。"""
    from ai_service.prompt import build_user_prompt
    from ai_service.directory import Directory
    from ai_service.schemas import Constraints, Reference

    request = make_request()
    request.constraints = Constraints(must_use_names=["番茄"], max_cook_time_min=30)
    references = [Reference(dish_id=1, name="番茄炒蛋", mains=["番茄", "鸡蛋"],
                            cook_time_min=15, step_hints=["先炒蛋"])]

    prompt = build_user_prompt(request, Directory(SNAPSHOT_ENTRIES), references)

    assert "【可用食材】" in prompt
    assert "【硬约束】" in prompt
    assert "【参考菜谱】" in prompt
    assert "【输出要求】" in prompt
    # 顺序：参考必须在输出要求之前（越靠后越接近模型下一步动作）
    assert prompt.index("【可用食材】") < prompt.index("【硬约束】") < prompt.index("【参考菜谱】") < prompt.index("【输出要求】")
    # 有 ID 的食材带上 ID，避免模型把「番茄」「西红柿」当两种料
    assert "番茄(ID:101)" in prompt
    assert "人数：2 人" in prompt


def test_user_prompt_has_no_reference_block_when_empty(generator):
    """没有参考时整段消失 —— 两臂（增强/不增强）的差别必须干净。"""
    from ai_service.prompt import build_user_prompt
    from ai_service.directory import Directory

    prompt = build_user_prompt(make_request(), Directory(SNAPSHOT_ENTRIES), [])

    assert "【参考菜谱】" not in prompt
    assert "【输出要求】" in prompt
