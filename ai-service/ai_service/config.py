"""配置加载：yaml + 环境变量，全部带默认值（配置文件缺失也能起）。"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

import yaml

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config.yaml"


@dataclass
class ServerConfig:
    host: str = "127.0.0.1"
    port: int = 8090


@dataclass
class OllamaConfig:
    base_url: str = "http://127.0.0.1:11434"
    model: str = "gemma4:e2b"


@dataclass
class GenerateConfig:
    temperature: float = 0.7
    max_attempts: int = 2
    attempt_timeout_seconds: int = 600
    nutrition_tolerance: float = 0.4
    seasoning_kcal_compensation: float = 120


@dataclass
class ConcurrencyConfig:
    max_parallel_model_calls: int = 2


@dataclass
class Config:
    server: ServerConfig
    ollama: OllamaConfig
    generate: GenerateConfig
    concurrency: ConcurrencyConfig


def _section(raw: dict, key: str) -> dict:
    value = raw.get(key)
    return value if isinstance(value, dict) else {}


def load_config(path: Optional[str] = None) -> Config:
    """读 yaml 配置；文件不存在或字段缺失都用默认值兜底。"""
    config_path = Path(path) if path else Path(
        os.environ.get("AI_SERVICE_CONFIG", str(DEFAULT_CONFIG_PATH)))
    raw: dict = {}
    if config_path.is_file():
        with open(config_path, "r", encoding="utf-8") as f:
            loaded = yaml.safe_load(f)
        if isinstance(loaded, dict):
            raw = loaded

    server_raw = _section(raw, "server")
    ollama_raw = _section(raw, "ollama")
    generate_raw = _section(raw, "generate")
    concurrency_raw = _section(raw, "concurrency")

    return Config(
        server=ServerConfig(
            host=str(server_raw.get("host", ServerConfig.host)),
            port=int(server_raw.get("port", ServerConfig.port)),
        ),
        ollama=OllamaConfig(
            base_url=str(ollama_raw.get("base_url", OllamaConfig.base_url)),
            model=str(ollama_raw.get("model", OllamaConfig.model)),
        ),
        generate=GenerateConfig(
            temperature=float(generate_raw.get("temperature", GenerateConfig.temperature)),
            max_attempts=int(generate_raw.get("max_attempts", GenerateConfig.max_attempts)),
            attempt_timeout_seconds=int(
                generate_raw.get("attempt_timeout_seconds", GenerateConfig.attempt_timeout_seconds)),
            nutrition_tolerance=float(
                generate_raw.get("nutrition_tolerance", GenerateConfig.nutrition_tolerance)),
            seasoning_kcal_compensation=float(
                generate_raw.get("seasoning_kcal_compensation",
                                 GenerateConfig.seasoning_kcal_compensation)),
        ),
        concurrency=ConcurrencyConfig(
            max_parallel_model_calls=int(
                concurrency_raw.get("max_parallel_model_calls",
                                    ConcurrencyConfig.max_parallel_model_calls)),
        ),
    )
