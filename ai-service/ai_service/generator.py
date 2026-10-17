"""生成器 —— AiDishGenerator.generate 的 Python 移植。

整个流程是「约定 + 约束 + 校验 + 重试」四件事叠起来的：
  系统提示词（说清要什么）
      ↓
  format=json（约束解码，保证是合法 JSON，不会裹 ```json 代码块）
      ↓
  反序列化 → 校验器逐字段检查
      ↓ 不合格
  把"哪里不合格"原样追加进提示词，重试一次
      ↓ 仍不合格
  抛 DishGenerationException，Java 侧把任务标记失败

关于营养：AI 给的营养值会展示给用户（标注"仅供参考"），同时这里会用
「食材主数据 × 用量」本地算一份做对比 —— 偏差过大说明这次生成可疑，
把 nutritionVerified 置 false。这是用户看不到的那道保险。
本地算的那份（localNutrition）也随响应返回，Java 落库时直接用它，
不用再算一遍。
"""
from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass, field
from typing import List, Optional

from . import grounding, nutrition, ollama_client, prompt
from .config import Config
from .directory import Directory
from .knowledge import CookingKnowledge, categories_of
from .prompt import build_user_prompt, load_system_prompt
from .schemas import (Constraints, DishDraft, GenerateRequest, IngredientRef)
from .validator import DraftValidator, ValidationResult

logger = logging.getLogger(__name__)


class DishGenerationException(Exception):
    """生成失败。kind 决定 Java 侧 error_msg 的归因与用户可见信息。

    ``tier`` 是比 kind 更细的一层：kind 说"哪一类失败"（超时 / 校验耗尽），
    tier 说"校验耗尽里具体卡在哪"（FORMAT_FAIL / CONSTRAINT_FAIL）。
    论文第 5 章要报的失败分档就落在这个字段上。

    ``retrieval_miss`` 表示这次生成压根没有参考菜谱可注入 ——
    它决定这次失败该不该算进"RAG 效果"里（没有参考的失败是冷启动的代价，
    不能拿来证明"检索没用"）。
    """

    def __init__(self, message: str, kind: str, attempts: int,
                 raw_output: Optional[str], validation: Optional[ValidationResult],
                 meta: "GenerationMeta", tier: Optional[str] = None,
                 retrieval_miss: bool = False):
        super().__init__(message)
        self.message = message
        self.kind = kind
        self.attempts = attempts
        self.raw_output = raw_output
        self.validation = validation
        self.meta = meta
        self.tier = tier
        self.retrieval_miss = retrieval_miss


@dataclass
class GenerationMeta:
    """这次生成用的"配方"。

    为什么必须记下来：换了模型、改了提示词、调了温度之后，老结果的解释力
    就完全不同了。不记这些，"上个月准确率 80%"这种结论根本站不住脚 ——
    因为没人知道那 80% 是哪一版提示词跑出来的。
    """

    model: str
    prompt_version: str
    temperature: float
    max_attempts: int
    reference_dish_ids: List[int] = field(default_factory=list)
    knowledge_rule_ids: List[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "model": self.model,
            "promptVersion": self.prompt_version,
            "temperature": self.temperature,
            "maxAttempts": self.max_attempts,
            # ID 一律字符串传输（项目铁律：Long 序列化成字符串）
            "referenceDishIds": [str(i) for i in self.reference_dish_ids],
            "knowledgeRuleIds": list(self.knowledge_rule_ids),
        }


@dataclass
class GenerateResult:
    draft: DishDraft
    nutrition_verified: bool
    cost_ms: int
    attempts: int
    raw_output: str
    validation: ValidationResult
    meta: GenerationMeta
    local_nutrition: nutrition.Result
    # 依据校验结果：参考为空/参考无用量时为 None（判不了，不能记 0 分）
    attribution: Optional[grounding.Attribution] = None


class DishGenerator:
    """有状态（提示词缓存 + 并发信号量），线程安全。"""

    def __init__(self, config: Config):
        self.config = config
        self.system_prompt, self.prompt_version = load_system_prompt()
        # 同时发往 Ollama 的调用上限：与 dish-service 队列并发度对齐。
        # Ollama 并行度为 1，多了也只是分时复用同一个模型实例，
        # 还会把单次调用拖到超时。超出的请求在信号量上排队。
        self.model_gate = threading.BoundedSemaphore(
            max(1, config.concurrency.max_parallel_model_calls))
        # 知识库只读一次：十几条规则的词面匹配，比每次读盘便宜得多
        self.knowledge = CookingKnowledge()
        logger.info("生成器已就绪：模型=%s，提示词版本=%s，最大尝试=%d，超时=%ds",
                    config.ollama.model, self.prompt_version,
                    config.generate.max_attempts,
                    config.generate.attempt_timeout_seconds)

    # ------------------------------------------------------------------ 主流程

    def generate(self, request: GenerateRequest) -> GenerateResult:
        start = time.monotonic()
        options = request.options
        directory = Directory(request.directory)
        # 用量分位随请求注入：校验器据此把"该放多少"从固定上限换成数据分位
        # （空列表会让它自动退到固定档，不影响老环境）
        validator = DraftValidator(directory, request.amount_stats, request.quantity_stats)

        references = request.references or []
        reference_ids = [ref.dish_id for ref in references if ref.dish_id is not None]
        if references:
            logger.info("本次生成注入 %d 条参考菜谱：%s", len(references),
                        [ref.name for ref in references])

        # 烹饪常识：按本次食材类别与口味召回，只取最相关的几条
        rules = self.knowledge.select(
            categories_of(directory, request.ingredients), request.tastes or [])
        knowledge_block = self.knowledge.render(rules)
        if rules:
            logger.info("本次注入烹饪常识 %d 条：%s", len(rules), [r.get("id") for r in rules])

        user_prompt = build_user_prompt(request, directory, references, knowledge_block)
        # 只为日志再算一次（同函数同输入，结果确定）：常见用量进了提示词，
        # 事后按 prompt_version 分组做 A/B 时，"这段到底有没有出现"必须查得出来 ——
        # 提示词版本号跟的是**系统**提示词，用户消息里的变化不会体现在版本号上。
        amount_hints = prompt.render_amount_hints(request, directory).count("\n- ")
        if amount_hints:
            logger.info("本次注入常见用量 %d 项（按 %d 人份换算）",
                        amount_hints, request.servings)
        required_names = [ref.name.strip() for ref in request.ingredients
                          if ref.name is not None and ref.name.strip()]

        logger.info("开始生成：requestId=%s，食材 %d 项，口味 %s，人数 %d，参考菜谱 %d 条，"
                    "主数据 %d 条，用量分位 %d+%d 组，最多尝试 %d 次",
                    request.request_id, len(request.ingredients), request.tastes or "无",
                    request.servings, len(references), directory.size(),
                    len(request.amount_stats), len(request.quantity_stats), options.max_attempts)

        last_problems: List[str] = []
        last_raw_output: Optional[str] = None
        last_validation: Optional[ValidationResult] = None
        # 最后一次失败落在哪一档：FORMAT_FAIL（连 JSON 都没写对）/ CONSTRAINT_FAIL（没听用户的话）。
        # 这个区分决定"该改提示词还是该改约束表达"，也是论文里失败分档的原始数据。
        last_tier: Optional[str] = None
        attempts = max(1, options.max_attempts)
        meta = GenerationMeta(options.model, self.prompt_version,
                              options.temperature, attempts, reference_ids,
                              [str(r.get("id")) for r in rules])

        for attempt in range(1, attempts + 1):
            logger.info("第 %d/%d 次：调用模型%s", attempt, attempts,
                        "（带上次的问题重试）" if last_problems else "")
            try:
                json_text = self._call_model(user_prompt, last_problems, options)
            except ollama_client.OllamaError as e:
                # 超时/调不通这类已经是"能给用户看的结论"了，原样保留消息，
                # 只把记录需要的上下文补上（试了几次、上次输出是什么）
                raise DishGenerationException(str(e), e.kind, attempt - 1,
                                              last_raw_output, last_validation, meta)
            last_raw_output = json_text

            try:
                draft = DishDraft.from_dict(json.loads(json_text))
            except Exception as e:
                last_problems = [f"输出的 JSON 无法解析：{ollama_client.brief(e)}"]
                # 解析不了就是纯粹的格式问题，约束根本没机会被判
                last_validation = ValidationResult(last_problems, [])
                last_tier = "FORMAT_FAIL"
                logger.warning("第 %d/%d 次：JSON 解析失败 - %s",
                               attempt, attempts, ollama_client.brief(e))
                continue

            validation = validator.validate_detailed(draft, request)
            if validation.passed():
                local = nutrition.calculate(nutrition.local_items(draft),
                                            request.servings, directory)
                nutrition_verified = nutrition.verify_nutrition(
                    draft, request.servings,
                    self.config.generate.nutrition_tolerance, directory)
                cost_ms = int((time.monotonic() - start) * 1000)
                logger.info("第 %d/%d 次生成通过校验：name=%s，耗时 %d ms，营养校验=%s",
                            attempt, attempts, draft.name, cost_ms, nutrition_verified)
                logger.info("本地营养：%s", nutrition.describe(local))

                # 依据校验 + RAGAS 三项：只在结果出来后给可信度结论，不影响成败
                attribution = grounding.assess(draft, references, directory, request)
                if attribution is not None:
                    logger.info("RAGAS 评估：%s", attribution.summary())
                    if attribution.low_confidence():
                        logger.warning("依据校验判定低可信（无据比例超过 %.0f%%）：%s",
                                       grounding.LOW_CONFIDENCE_UNGROUNDED * 100,
                                       attribution.summary())

                return GenerateResult(draft, nutrition_verified, cost_ms, attempt,
                                      json_text, validation, meta, local, attribution)

            last_problems = validation.problems
            last_validation = validation
            # 有约束类问题就归 CONSTRAINT_FAIL（该改的是约束表达/回注具体项）；
            # 只有格式问题才是 FORMAT_FAIL（该改的是提示词或约束解码）
            last_tier = "CONSTRAINT_FAIL" if validation.constraint_problems else "FORMAT_FAIL"
            logger.warning("第 %d/%d 次生成未通过校验（格式问题 %d 项 / 约束问题 %d 项）：%s",
                           attempt, attempts,
                           len(validation.problems) - len(validation.constraint_problems),
                           len(validation.constraint_problems),
                           "；".join(validation.problems))

        # 详细原因留给日志：字段级的说明（prepTimeMin=0 不在 2~60 之间）是给重试提示词
        # 和开发者看的，直接甩给用户等于没说。但与"用户自己提的条件"相关的那些要挑出来
        # 附上：条件自相矛盾时（既要求用牛肉、又忌口牛肉），不说清楚用户只会一遍遍重试。
        retrieval_miss = not references
        logger.warning("生成失败：%d 次尝试都没通过校验，分档=%s，参考=%d 条，最后一次的问题：%s",
                       attempts, last_tier or "UNKNOWN", len(references), "；".join(last_problems))
        message = ("AI 没能用「" + describe(required_names)
                   + "」想出合适的做法，换个食材组合或口味再试试"
                   + constraint_hint(last_problems))
        raise DishGenerationException(message, "validation_exhausted", attempts,
                                      last_raw_output, last_validation, meta,
                                      tier=last_tier, retrieval_miss=retrieval_miss)

    # ------------------------------------------------------------------ 调模型

    def _call_model(self, user_prompt: str, previous_problems: List[str],
                    options) -> str:
        user_text = user_prompt
        if previous_problems:
            # 把上次的问题原样告诉它。实测小模型对"具体哪里错了"是有反应的，
            # 但对"格式错误"这种笼统说法没反应 —— 所以校验器的报错必须具体。
            lines = ["\n\n上一次的输出存在下列问题，请全部修正后重新输出："]
            lines.extend("\n- " + p for p in previous_problems)
            user_text = user_prompt + "".join(lines)

        waiting_since = time.monotonic()
        with self.model_gate:
            waited_ms = int((time.monotonic() - waiting_since) * 1000)
            if waited_ms >= 100:
                logger.info("排队等待模型槽位 %d ms（并发上限 %d）",
                            waited_ms, self.config.concurrency.max_parallel_model_calls)
            return ollama_client.chat(
                base_url=self.config.ollama.base_url,
                model=options.model,
                system_prompt=self.system_prompt,
                user_prompt=user_text,
                temperature=options.temperature,
                timeout_seconds=options.timeout_seconds,
            )


# ------------------------------------------------------------------ 消息 helpers


def constraint_hint(problems: List[str]) -> str:
    """从校验问题里挑出"和用户条件有关"的那些，附给用户看。"""
    prefixes = ("用户要求必用", "出现了用户禁用", "用到了用户没有", "总耗时")
    hit = [p for p in problems if p.startswith(prefixes)]
    return "" if not hit else "；与你的条件有关：" + "；".join(hit)


def describe(names: List[str]) -> str:
    """把食材列表说成一句人话；太长了就只提前两个，免得报错信息一屏都是菜名。"""
    if not names:
        return "这组食材"
    if len(names) <= 3:
        return "、".join(names)
    return f"{names[0]}、{names[1]} 等 {len(names)} 种食材"
