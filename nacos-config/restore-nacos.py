#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 backup/<日期>/ 里的配置推回 Nacos（升级后一键恢复用）。

用法（项目根目录执行）：
    # 先空跑看看会推哪些，不真写入
    D:/pycharm/python/python3.12.10/python.exe nacos-config/restore-nacos.py --backup backup/2026-10-01 --dry

    # 真写入
    D:/pycharm/python/python3.12.10/python.exe nacos-config/restore-nacos.py --backup backup/2026-10-01

可选参数：
    --addr http://127.0.0.1:8848   目标 Nacos 地址（可以是新版本的容器）
    --only  dataId关键字            只恢复匹配的配置
    --dry                          空跑，不实际写入

注意：推送用的是 form-urlencoded，不是 multipart。
Nacos v1 的发布接口返回 "true" 表示成功。
"""

import argparse
import io
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

PUBLISH_PATH = "/nacos/v1/cs/configs"


def publish(addr, tenant, group, data_id, cfg_type, content, timeout=10):
    """推送单条配置，返回 (是否成功, 服务端返回文本)"""
    payload = {
        "tenant": tenant,
        "dataId": data_id,
        "group": group,
        "type": cfg_type,
        "content": content,
    }
    body = urllib.parse.urlencode(payload).encode("utf-8")
    req = urllib.request.Request(
        addr.rstrip("/") + PUBLISH_PATH,
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        text = resp.read().decode("utf-8").strip()
    return text.lower() == "true", text


def health_check(addr, timeout=5):
    """确认目标 Nacos 活着。"""
    try:
        with urllib.request.urlopen(addr.rstrip("/") + "/nacos/", timeout=timeout) as resp:
            return resp.status == 200
    except Exception:
        return False


def main():
    parser = argparse.ArgumentParser(description="把备份的配置推回 Nacos")
    parser.add_argument("--addr", default="http://127.0.0.1:8848", help="目标 Nacos 地址")
    parser.add_argument("--backup", required=True, help="备份目录路径，如 backup/2026-10-01")
    parser.add_argument("--only", default=None, help="只恢复 dataId 含该关键字的配置")
    parser.add_argument("--dry", action="store_true", help="空跑，不实际写入")
    args = parser.parse_args()

    # 路径容错：无论你在哪个目录执行，都尽量找到备份。
    # 依次尝试 ① 相对当前目录 ② 相对 nacos-config 目录 ③ 相对项目根目录
    raw = args.backup
    here = os.path.dirname(os.path.abspath(__file__))            # nacos-config/
    root = os.path.dirname(here)                                  # 项目根
    candidates = [
        os.path.abspath(raw),
        os.path.normpath(os.path.join(here, raw)),
        os.path.normpath(os.path.join(root, raw)),
        os.path.normpath(os.path.join(here, "backup", raw)),
    ]

    backup_dir = None
    manifest_path = None
    for cand in candidates:
        probe = os.path.join(cand, "manifest.json")
        if os.path.exists(probe):
            backup_dir, manifest_path = cand, probe
            break

    if not manifest_path:
        print("找不到清单文件，已尝试以下路径：")
        for cand in candidates:
            print("  - %s" % os.path.join(cand, "manifest.json"))
        print("")
        print("提示: 也可以直接给绝对路径，例如")
        print("  --backup D:/idea/work/project0914/nacos-config/backup/2026-10-01")
        return 1

    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    print("备份来源: %s" % backup_dir)
    print("备份时间: %s" % manifest.get("backupTime"))
    print("目标地址: %s" % args.addr)
    print("命名空间: %s" % (manifest.get("namespace") or "public"))
    if args.dry:
        print("模式:     空跑（不会写入）")
    print("")

    if not args.dry and not health_check(args.addr):
        print("连不上目标 Nacos，请确认已启动且端口映射正确。")
        print("注意 Nacos 2.x 除了 8848 还要映射 9848 / 9849（gRPC）。")
        return 1

    namespace = args.addr and manifest.get("namespace", "")
    ok_count = fail_count = skip_count = 0
    failed = []

    for cfg in manifest.get("configs", []):
        data_id = cfg["dataId"]
        group = cfg["group"]
        if args.only and args.only not in data_id:
            skip_count += 1
            continue

        src = os.path.join(backup_dir, cfg["file"])
        if not os.path.exists(src):
            print("  [跳过] %s @ %s —— 备份文件缺失: %s" % (data_id, group, cfg["file"]))
            fail_count += 1
            failed.append(data_id)
            continue

        with open(src, "r", encoding="utf-8") as f:
            content = f.read()

        cfg_type = cfg.get("type") or "text"
        if args.dry:
            print("  [空跑] %-42s @ %-14s type=%-11s %6d 字符"
                  % (data_id, group, cfg_type, len(content)))
            ok_count += 1
            continue

        try:
            ok, resp = publish(args.addr, cfg.get("tenant", namespace), group,
                               data_id, cfg_type, content)
        except Exception as e:
            ok, resp = False, str(e)

        if ok:
            print("  [已恢复] %-42s @ %s" % (data_id, group))
            ok_count += 1
        else:
            print("  [失败]   %-42s @ %s  服务端返回: %s" % (data_id, group, resp))
            fail_count += 1
            failed.append(data_id)

    print("")
    print("完毕: 成功 %d / 失败 %d / 跳过 %d" % (ok_count, fail_count, skip_count))
    if failed:
        print("以下配置没成功，需要手动处理: %s" % ", ".join(failed))
        return 2
    if args.dry:
        print("这是空跑。确认无误后去掉 --dry 再执行一次。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
