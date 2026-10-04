#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
备份 Nacos 上的全部配置到 backup/<日期>/ 目录。

用法（项目根目录执行）：
    D:/pycharm/python/python3.12.10/python.exe nacos-config/backup-nacos.py

可选参数：
    --addr http://127.0.0.1:8848   指定 Nacos 地址
    --namespace ""                 指定命名空间 ID（public 传空字符串）
    --dir nacos-config/backup      指定备份根目录

产出结构：
    backup/<日期>/
        manifest.json              配置清单（dataId / group / type / md5 / 大小）
        <GROUP>/
            <dataId>               原始配置内容，UTF-8 无 BOM

为什么 type 是推断出来的：
    Nacos 的 /v1/cs/configs 列表接口不返回 type 字段（返回 null），
    只能按 dataId 后缀推断。本项目推送时用的就是 yaml / properties，
    与实际一致。
"""

import argparse
import io
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime

# Windows 控制台默认 GBK，打印中文会炸，强制 UTF-8 输出
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

# 按后缀推断 Nacos 的 type 字段
TYPE_BY_SUFFIX = {
    ".yaml": "yaml",
    ".yml": "yaml",
    ".properties": "properties",
    ".json": "json",
    ".xml": "xml",
    ".txt": "text",
}


def guess_type(data_id):
    _, ext = os.path.splitext(data_id)
    return TYPE_BY_SUFFIX.get(ext.lower(), "text")


def fetch_config_list(addr, namespace, timeout=10):
    """拉取配置列表。search=blur + 空 dataId/group 表示「查全部」。"""
    query = urllib.parse.urlencode({
        "search": "blur",
        "dataId": "",
        "group": "",
        "appName": "",
        "config_tags": "",
        "pageNo": 1,
        "pageSize": 500,
        "tenant": namespace,
    })
    url = addr.rstrip("/") + "/nacos/v1/cs/configs?" + query
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def safe_dir_name(group):
    """group 可能出现平台非法字符，兜一层替换。"""
    bad = '<>:"/\\|?*'
    name = "".join("_" if c in bad else c for c in group)
    return name or "EMPTY_GROUP"


def main():
    parser = argparse.ArgumentParser(description="备份 Nacos 全部配置")
    parser.add_argument("--addr", default="http://127.0.0.1:8848", help="Nacos 地址")
    parser.add_argument("--namespace", default="", help="命名空间 ID，public 留空")
    parser.add_argument("--dir", default=None, help="备份根目录，默认与本脚本同级的 backup/")
    args = parser.parse_args()

    script_dir = os.path.dirname(os.path.abspath(__file__))
    backup_root = os.path.abspath(args.dir) if args.dir else os.path.join(script_dir, "backup")

    print("连接 Nacos: %s (namespace=%s)" % (args.addr, args.namespace or "public"))
    try:
        result = fetch_config_list(args.addr, args.namespace)
    except Exception as e:
        print("连接失败: %s" % e)
        print("请确认 Nacos 已启动，且地址端口正确。")
        return 1

    items = result.get("pageItems", [])
    total = result.get("totalCount", len(items))
    if len(items) < total:
        print("警告: 接口返回 %d/%d 条，可能超过单页上限，请检查 pageSize。" % (len(items), total))

    stamp = datetime.now().strftime("%Y-%m-%d")
    target = os.path.join(backup_root, stamp)
    if os.path.exists(target):
        # 同一天重复备份，追加时分秒避免覆盖
        target = os.path.join(backup_root, datetime.now().strftime("%Y-%m-%d-%H%M%S"))

    manifest = {
        "backupTime": datetime.now().astimezone().isoformat(timespec="seconds"),
        "nacosAddr": args.addr,
        "namespace": args.namespace,
        "count": len(items),
        "configs": [],
    }

    for item in items:
        data_id = item["dataId"]
        group = item["group"]
        content = item.get("content") or ""
        cfg_type = guess_type(data_id)

        rel_dir = safe_dir_name(group)
        out_dir = os.path.join(target, rel_dir)
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, data_id)

        # 关键点：UTF-8 无 BOM。带 BOM 会让 -Dfile.encoding=UTF-8 的 JVM 解析 YAML 失败
        with open(out_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)

        rel_file = "%s/%s" % (rel_dir, data_id)
        manifest["configs"].append({
            "dataId": data_id,
            "group": group,
            "tenant": item.get("tenant", ""),
            "type": cfg_type,
            "md5": item.get("md5"),
            "file": rel_file,
            "size": len(content.encode("utf-8")),
        })
        print("  [已备份] %-42s @ %-14s %6d 字节" % (data_id, group, len(content.encode("utf-8"))))

    manifest_path = os.path.join(target, "manifest.json")
    with open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print("")
    print("备份完成: 共 %d 条 -> %s" % (len(items), target))
    print("清单文件: %s" % manifest_path)
    print("")
    print("将来恢复（在项目根目录执行，先空跑确认再去掉 --dry）：")
    print("  python nacos-config/restore-nacos.py --backup nacos-config/backup/%s --dry"
          % os.path.basename(target))


if __name__ == "__main__":
    sys.exit(main())
