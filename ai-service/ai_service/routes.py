"""HTTP 接口：/api/v1/generate、/api/v1/nutrition、/health。

契约见 docs/ai-service-split.md §6。字段名与 dish-service 的 DTO 一字不差
（camelCase），Java 侧用同一套字段名做 Jackson 映射。
"""
from __future__ import annotations

import logging
from typing import List

from flask import Blueprint, current_app, jsonify, request

from . import nutrition, ollama_client
from .generator import DishGenerationException, DishGenerator
from .schemas import GenerateOptions, GenerateRequest

logger = logging.getLogger(__name__)

bp = Blueprint("api", __name__, url_prefix="/api/v1")


def _generator() -> DishGenerator:
    return current_app.extensions["dish_generator"]


def _config():
    return current_app.config["AI_SERVICE"]


def _validation_to_dict(validation) -> dict:
    if validation is None:
        return {"problems": [], "constraintProblems": []}
    return {"problems": validation.problems,
            "constraintProblems": validation.constraint_problems}


@bp.post("/generate")
def generate():
    """LLM 菜谱生成（同步）：Java 传食材/约束/主数据快照/参考菜谱，返回草稿。"""
    body = request.get_json(silent=True)
    try:
        req = GenerateRequest.from_dict(body or {}, _default_options())
    except ValueError as e:
        logger.warning("生成请求参数不合法：%s", e)
        return jsonify({"ok": False, "errorKind": "bad_request", "error": str(e)}), 400

    try:
        result = _generator().generate(req)
    except DishGenerationException as e:
        logger.warning("生成失败（%s，尝试 %d 次）：%s", e.kind, e.attempts, e.message)
        return jsonify({
            "ok": False,
            "errorKind": e.kind,
            "error": e.message,
            "attempts": e.attempts,
            "rawOutput": e.raw_output,
            "validation": _validation_to_dict(e.validation),
            "meta": e.meta.to_dict(),
        })
    except Exception:
        logger.exception("生成器未捕获异常")
        return jsonify({
            "ok": False,
            "errorKind": "internal",
            "error": "AI 服务异常",
            "attempts": 0,
            "rawOutput": None,
            "validation": {"problems": [], "constraintProblems": []},
            "meta": None,
        }), 500

    return jsonify({
        "ok": True,
        "draft": result.draft.to_dict(),
        "nutritionVerified": result.nutrition_verified,
        "localNutrition": result.local_nutrition.to_dict(),
        "attempts": result.attempts,
        "costMs": result.cost_ms,
        "rawOutput": result.raw_output,
        "validation": _validation_to_dict(result.validation),
        "meta": result.meta.to_dict(),
    })


@bp.post("/nutrition")
def nutrition_batch():
    """批量本地营养计算（给 dish-service 的 NutritionRecomputeService 全库重算用）。"""
    body = request.get_json(silent=True) or {}
    from .directory import Directory
    from .schemas import DirectoryEntry

    directory_raw = body.get("directory")
    entries = [e for e in (DirectoryEntry.from_dict(i if isinstance(i, dict) else {})
                           for i in directory_raw if isinstance(directory_raw, list))
               if e is not None]
    directory = Directory(entries)

    results = []
    for item in body.get("items") or []:
        if not isinstance(item, dict):
            continue
        ingredients = [
            nutrition.Item(str(i.get("name")),
                           float(i["amountGram"]) if i.get("amountGram") is not None else None)
            for i in (item.get("ingredients") or []) if isinstance(i, dict)
        ]
        servings = item.get("servings")
        result = nutrition.calculate(ingredients,
                                     int(servings) if servings else 1, directory)
        entry = {"dishId": item.get("dishId")}
        entry.update(result.to_dict())
        results.append(entry)

    logger.info("批量营养计算完成：主数据 %d 条，菜谱 %d 道", len(entries), len(results))
    return jsonify({"ok": True, "results": results})


@bp.get("/health")
def health():
    """健康检查：服务本身 + Ollama 通不通。

    根路径 /health 也挂了一份（见 app.py 的 add_url_rule）—— 探活走根是惯例，
    蓝图前缀是给业务接口用的。
    """
    generator = _generator()
    ollama_ok = ollama_client.ping(_config().ollama.base_url)
    return jsonify({
        "status": "up",
        "model": _config().ollama.model,
        "promptVersion": generator.prompt_version,
        "ollamaReachable": ollama_ok,
    })


def _default_options() -> GenerateOptions:
    config = _config()
    return GenerateOptions(
        model=config.ollama.model,
        temperature=config.generate.temperature,
        max_attempts=config.generate.max_attempts,
        timeout_seconds=config.generate.attempt_timeout_seconds,
    )
