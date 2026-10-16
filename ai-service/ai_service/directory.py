"""食材主数据目录 —— 由请求里的 directory 快照构建。

对应 Java 的 IngredientDirectory，但数据来源是请求快照而不是 MySQL：
ai-service 不连业务库（解耦原则之一），主数据由 dish-service 每请求注入。
快照只有几十到几百行，每次请求重建的成本可以忽略，换来的是零缓存一致性问题
——食材转正后下一个请求就认得出来。

登记规则与 Java 版一致：正式名优先、别名让位（「五花肉」既是自己的正式名、
又是「猪肉」的别名，混在一遍里登记结果取决于行序）。
"""
from __future__ import annotations

import re
from typing import Dict, List, Optional, Set

from .schemas import DirectoryEntry

_ALIAS_SPLIT = re.compile(r"[,，]")


def normalize_key(raw: Optional[str]) -> Optional[str]:
    """归一化键：去掉所有空白 + 转小写。与 IngredientDirectory.key 同一套规则。"""
    if raw is None:
        return None
    key = "".join(raw.split()).lower()
    return key or None


class Directory:
    """名字/别名 → 主数据 ID 的内存目录（无状态，随用随建）。"""

    def __init__(self, entries: Optional[List[DirectoryEntry]] = None):
        self._entries: Dict[int, DirectoryEntry] = {}
        self._name_by_id: Dict[int, str] = {}
        self._id_by_key: Dict[str, int] = {}
        self._seasoning_ids: Set[int] = set()
        self._seasoning_keys: Set[str] = set()
        self._category_by_id: Dict[int, str] = {}

        entries = entries or []
        # 第一遍：正式名 + 类目 + 调料集合
        for entry in entries:
            if entry is None or entry.id is None:
                continue
            self._entries[entry.id] = entry
            if entry.name is not None:
                self._name_by_id[entry.id] = entry.name
                self._put_key(entry.name, entry.id)
            if entry.category is not None:
                self._category_by_id[entry.id] = entry.category
            if entry.category == "调料":
                self._seasoning_ids.add(entry.id)
                self._put_seasoning_key(entry.name, entry.id)

        # 第二遍：别名。正式名一定比别名更具体，所以别名统一后登记、一律让位
        for entry in entries:
            if entry is None or entry.id is None or not entry.alias:
                continue
            for alias in _ALIAS_SPLIT.split(entry.alias):
                self._put_key(alias, entry.id)
                if entry.category == "调料":
                    self._put_seasoning_key(alias, entry.id)

    def _put_key(self, raw: Optional[str], ingredient_id: int) -> None:
        key = normalize_key(raw)
        if key is not None:
            self._id_by_key.setdefault(key, ingredient_id)

    def _put_seasoning_key(self, raw: Optional[str], ingredient_id: int) -> None:
        key = normalize_key(raw)
        if key is not None:
            self._seasoning_keys.add(key)

    def resolve_id(self, raw_name: Optional[str]) -> Optional[int]:
        """名字（含别名）→ 主数据 ID；认不出来返回 None。"""
        if raw_name is None:
            return None
        return self._id_by_key.get(normalize_key(raw_name))

    def name_of(self, ingredient_id: Optional[int]) -> Optional[str]:
        return None if ingredient_id is None else self._name_by_id.get(ingredient_id)

    def is_seasoning(self, ingredient_id: Optional[int]) -> bool:
        return ingredient_id is not None and ingredient_id in self._seasoning_ids

    def is_seasoning_name(self, raw_name: Optional[str]) -> bool:
        if raw_name is None:
            return False
        return normalize_key(raw_name) in self._seasoning_keys

    def category_of(self, ingredient_id: Optional[int]) -> Optional[str]:
        return None if ingredient_id is None else self._category_by_id.get(ingredient_id)

    def entry_of(self, ingredient_id: Optional[int]) -> Optional[DirectoryEntry]:
        return None if ingredient_id is None else self._entries.get(ingredient_id)

    def entry_by_name(self, name: Optional[str]) -> Optional[DirectoryEntry]:
        """按正式名精确查找（不过别名）——营养估算的口径与 Java 查库 by name 一致。"""
        if name is None:
            return None
        for entry in self._entries.values():
            if entry.name == name:
                return entry
        return None

    def canonical_names(self) -> Set[str]:
        """全部正式名 —— 拿它当"食材词典"扫步骤正文，找配料表之外的食材。"""
        return set(self._name_by_id.values())

    def size(self) -> int:
        return len(self._entries)
