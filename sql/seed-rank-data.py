#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成「更像真实用户」的榜单模拟数据。

产出的 SQL 会做四件事：
  1. 新增一批家常菜（含食材明细、步骤、营养），并置为已发布，把榜单填到 100 道以上
  2. 为每道已发布的菜生成评分（t_dish_rating）与评论（t_dish_comment）
  3. 生成收藏（t_dish_collect）
  4. 回填 t_dish 上的冗余字段（rating_avg / rating_count / rating_wilson / collect_count / view_count）

为什么必须回填冗余字段：
  榜单读的是 t_dish 的冗余列，不是每次聚合算。只插评分表不改冗余列，
  榜单数字一点都不会动。

真实感是怎么做出来的（这是老数据"假"的根本原因）：
  - 评论文本带占位符，按当前菜的菜名 / 主料 / 做法动态渲染，
    所以每道菜下面读到的是"针对这道菜说的话"，不是同一批模板循环
  - 分数是长尾分布（5 分最多、1 分极少），不是清一色满分
  - 时间散落在菜发布之后的随机时刻，且按人的作息加权（晚饭前后最多）
  - 点赞数、cooked_flag、评论里的 rating 都有梯度

用法：
    D:/pycharm/python/python3.12.10/python.exe sql/seed-rank-data.py            # 只预览统计
    D:/pycharm/python/python3.12.10/python.exe sql/seed-rank-data.py --write     # 生成 SQL 文件
"""

import argparse
import io
import json
import os
import random
import subprocess
import sys
from datetime import datetime, timedelta

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "project0914"
SEED = 20261002

# 新数据用的 ID 号段（1 开头 19 位），与库里现有 2 开头的雪花 ID 不冲突
ID_BASE = 1900000000000000000
_id_seq = ID_BASE


def next_id():
    global _id_seq
    _id_seq += 1
    return _id_seq


# ================================================================== 菜品
# (菜名, 做法, [(主料, 克重)], [调料], 难度1-3, 备料分钟, 烹饪分钟, 辣度0-3, 口味标签)
DISHES = [
    # ---- 炒 ----
    ("蒜蓉西兰花", "stir_fry", [("西兰花", 300), ("大蒜", 20)], ["盐", "蚝油"], 1, 8, 8, 0, "清淡,蒜香"),
    ("手撕包菜", "stir_fry", [("白菜", 400), ("干辣椒", 5), ("大蒜", 15)], ["生抽", "醋", "盐"], 1, 8, 8, 1, "酸辣,下饭"),
    ("干煸豆角", "stir_fry", [("豆角", 350), ("猪肉", 80), ("大蒜", 15)], ["生抽", "盐", "干辣椒"], 2, 10, 15, 1, "干香,下饭"),
    ("蚝油生菜", "stir_fry", [("生菜", 350), ("大蒜", 15)], ["蚝油", "生抽", "盐"], 1, 5, 5, 0, "清淡,快手"),
    ("醋溜白菜", "stir_fry", [("白菜", 450), ("干辣椒", 5)], ["醋", "生抽", "盐", "白糖"], 1, 8, 8, 1, "酸甜,开胃"),
    ("香菇菠菜", "stir_fry", [("菠菜", 300), ("香菇", 100)], ["蚝油", "盐", "生抽"], 1, 8, 8, 0, "清淡,鲜香"),
    ("鱼香茄子", "stir_fry", [("茄子", 400), ("猪肉", 80), ("大蒜", 15), ("生姜", 10)], ["豆瓣酱", "生抽", "醋", "白糖"], 2, 10, 15, 1, "鱼香,浓郁"),
    ("地三鲜", "stir_fry", [("茄子", 250), ("土豆", 250), ("青椒", 100)], ["生抽", "蚝油", "盐", "白糖"], 2, 12, 18, 0, "酱香,下饭"),
    ("韭菜炒蛋", "stir_fry", [("韭菜", 250), ("鸡蛋", 150)], ["盐", "生抽"], 1, 6, 6, 0, "家常,鲜香"),
    ("青椒炒蛋", "stir_fry", [("青椒", 200), ("鸡蛋", 150)], ["盐", "生抽"], 1, 6, 8, 0, "家常,快手"),
    ("虾仁滑蛋", "stir_fry", [("虾仁", 200), ("鸡蛋", 180)], ["盐", "料酒", "芝麻油"], 2, 10, 8, 0, "鲜嫩,滑蛋"),
    ("芹菜炒肉", "stir_fry", [("芹菜", 250), ("猪肉", 150)], ["生抽", "盐", "料酒"], 1, 10, 10, 0, "家常,下饭"),
    ("青椒肉丝", "stir_fry", [("青椒", 200), ("猪肉", 200)], ["生抽", "料酒", "盐"], 1, 10, 10, 0, "家常,下饭"),
    ("鱼香肉丝", "stir_fry", [("猪肉", 250), ("木耳", 50), ("胡萝卜", 80)], ["豆瓣酱", "醋", "白糖", "生抽"], 2, 15, 15, 1, "鱼香,下饭"),
    ("回锅肉", "stir_fry", [("五花肉", 300), ("青椒", 100), ("洋葱", 80)], ["豆瓣酱", "生抽", "料酒"], 2, 10, 15, 2, "香辣,下饭"),
    ("宫保鸡丁", "stir_fry", [("鸡胸肉", 250), ("花生", 60), ("干辣椒", 10)], ["生抽", "醋", "白糖", "料酒"], 2, 15, 15, 2, "麻辣,酸甜"),
    ("黑椒牛柳", "stir_fry", [("牛肉", 250), ("洋葱", 100), ("青椒", 80)], ["黑胡椒", "生抽", "蚝油"], 2, 15, 12, 0, "黑椒,浓郁"),
    ("土豆丝炒肉", "stir_fry", [("土豆", 300), ("猪肉", 100), ("青椒", 50)], ["醋", "生抽", "盐"], 1, 10, 10, 0, "酸爽,家常"),
    ("山药炒木耳", "stir_fry", [("山药", 250), ("木耳", 50), ("胡萝卜", 60)], ["盐", "蚝油"], 1, 10, 10, 0, "清爽,养生"),
    ("西葫芦炒蛋", "stir_fry", [("西葫芦", 300), ("鸡蛋", 150)], ["盐", "生抽"], 1, 8, 8, 0, "清淡,快手"),
    ("芹菜炒香干", "stir_fry", [("芹菜", 250), ("豆干", 150)], ["盐", "生抽", "芝麻油"], 1, 8, 10, 0, "清爽,豆香"),
    ("家常豆腐", "stir_fry", [("豆腐", 400), ("猪肉", 80), ("青椒", 60)], ["豆瓣酱", "生抽", "蚝油"], 2, 10, 15, 1, "酱香,下饭"),
    ("麻婆豆腐", "stir_fry", [("豆腐", 400), ("猪肉", 100)], ["豆瓣酱", "花椒", "干辣椒", "生抽"], 2, 10, 12, 3, "麻辣,下饭"),
    ("孜然羊肉", "stir_fry", [("羊肉", 300), ("洋葱", 100)], ["孜然", "干辣椒", "盐", "生抽"], 2, 12, 12, 2, "孜然,香辣"),
    ("黄焖鸡", "stir_fry", [("鸡胸肉", 350), ("土豆", 200), ("青椒", 80)], ["生抽", "老抽", "料酒", "白糖"], 2, 15, 25, 0, "酱香,浓郁"),
    ("辣子鸡", "stir_fry", [("鸡翅", 400), ("干辣椒", 30), ("花椒", 5)], ["生抽", "料酒", "盐", "芝麻"], 3, 20, 20, 3, "麻辣,干香"),
    ("清炒莴笋丝", "stir_fry", [("莴笋", 350), ("大蒜", 10)], ["盐", "蚝油"], 1, 8, 8, 0, "清爽,清淡"),
    ("金针菇炒蛋", "stir_fry", [("金针菇", 200), ("鸡蛋", 150)], ["盐", "生抽", "蚝油"], 1, 8, 8, 0, "鲜香,快手"),
    # ---- 炖 / 烧 ----
    ("红烧肉", "braise", [("五花肉", 500), ("生姜", 20), ("葱", 30)], ["生抽", "老抽", "白糖", "料酒"], 2, 15, 60, 0, "酱香,肥而不腻"),
    ("土豆炖牛腩", "braise", [("牛腩", 500), ("土豆", 300), ("胡萝卜", 150)], ["生抽", "老抽", "料酒", "五香粉"], 2, 20, 90, 0, "浓郁,软烂"),
    ("番茄牛腩", "braise", [("牛腩", 450), ("番茄", 300), ("洋葱", 100)], ["生抽", "料酒", "盐", "白糖"], 2, 20, 80, 0, "酸甜,浓郁"),
    ("糖醋排骨", "braise", [("排骨", 500), ("生姜", 15)], ["醋", "白糖", "生抽", "料酒"], 2, 15, 40, 0, "糖醋,酸甜"),
    ("红烧鸡翅", "braise", [("鸡翅", 500), ("生姜", 15)], ["生抽", "老抽", "料酒", "白糖"], 1, 10, 25, 0, "酱香,下饭"),
    ("水煮牛肉", "braise", [("牛肉", 300), ("生菜", 150), ("豆干", 100)], ["豆瓣酱", "干辣椒", "花椒", "料酒"], 3, 20, 25, 3, "麻辣,嫩滑"),
    ("红烧带鱼", "braise", [("带鱼", 500), ("生姜", 20), ("大蒜", 15)], ["生抽", "老抽", "料酒", "醋"], 2, 15, 25, 0, "酱香,鲜香"),
    ("油焖大虾", "braise", [("虾仁", 400), ("生姜", 15), ("葱", 20)], ["生抽", "料酒", "白糖", "醋"], 2, 10, 15, 0, "鲜甜,酱香"),
    ("土豆炖羊肉", "braise", [("羊肉", 450), ("土豆", 200), ("生姜", 20)], ["料酒", "生抽", "孜然", "盐"], 2, 20, 70, 0, "浓香,暖身"),
    ("香菇滑鸡", "braise", [("鸡胸肉", 300), ("香菇", 150), ("生姜", 15)], ["生抽", "蚝油", "料酒", "芝麻油"], 2, 15, 25, 0, "鲜嫩,酱香"),
    ("红烧茄子", "braise", [("茄子", 450), ("大蒜", 15), ("青椒", 60)], ["生抽", "蚝油", "白糖", "醋"], 1, 10, 15, 0, "酱香,软糯"),
    # ---- 蒸 ----
    ("清蒸鲈鱼", "steam", [("鲈鱼", 600), ("生姜", 20), ("葱", 30)], ["生抽", "料酒", "芝麻油"], 2, 15, 12, 0, "清淡,鲜嫩"),
    ("蒸水蛋", "steam", [("鸡蛋", 300)], ["生抽", "芝麻油", "盐"], 1, 8, 12, 0, "滑嫩,快手"),
    ("蒜蓉粉丝蒸虾", "steam", [("虾仁", 350), ("粉丝", 100), ("大蒜", 25)], ["生抽", "蚝油", "料酒"], 2, 15, 12, 0, "蒜香,鲜甜"),
    ("粉蒸肉", "steam", [("五花肉", 400), ("红薯", 200)], ["生抽", "老抽", "五香粉", "料酒"], 2, 25, 60, 0, "软糯,香浓"),
    ("蛤蜊蒸蛋", "steam", [("蛤蜊", 300), ("鸡蛋", 200), ("生姜", 10)], ["生抽", "料酒", "芝麻油"], 2, 15, 15, 0, "鲜嫩,清淡"),
    # ---- 汤 / 粥 ----
    ("番茄蛋花汤", "soup", [("番茄", 200), ("鸡蛋", 100)], ["盐", "芝麻油"], 1, 8, 10, 0, "清淡,快手"),
    ("紫菜蛋花汤", "soup", [("紫菜", 20), ("鸡蛋", 100), ("虾仁", 50)], ["盐", "芝麻油", "生抽"], 1, 5, 8, 0, "清淡,鲜香"),
    ("玉米排骨汤", "soup", [("排骨", 400), ("玉米", 300), ("胡萝卜", 150)], ["盐", "料酒", "生姜"], 1, 15, 60, 0, "清甜,滋补"),
    ("冬瓜排骨汤", "soup", [("排骨", 350), ("冬瓜", 400), ("生姜", 15)], ["盐", "料酒"], 1, 12, 50, 0, "清爽,解腻"),
    ("南瓜燕麦粥", "soup", [("南瓜", 300), ("燕麦", 80)], ["白糖"], 1, 8, 25, 0, "清甜,养胃"),
    ("山药排骨汤", "soup", [("排骨", 350), ("山药", 250), ("胡萝卜", 100)], ["盐", "料酒", "生姜"], 1, 15, 55, 0, "清甜,滋补"),
    ("海带豆腐汤", "soup", [("海带", 100), ("豆腐", 250)], ["盐", "生抽", "芝麻油"], 1, 10, 20, 0, "清淡,鲜香"),
    ("蛤蜊豆腐汤", "soup", [("蛤蜊", 300), ("豆腐", 250), ("生姜", 10)], ["盐", "料酒", "芝麻油"], 1, 10, 18, 0, "鲜美,清淡"),
    ("瘦肉粥", "soup", [("米饭", 200), ("猪肉", 100), ("生姜", 10)], ["盐", "生抽", "芝麻油"], 1, 10, 30, 0, "绵密,暖胃"),
    # ---- 主食 ----
    ("蛋炒饭", "staple", [("米饭", 400), ("鸡蛋", 150), ("胡萝卜", 60)], ["盐", "生抽", "葱"], 1, 8, 10, 0, "家常,快手"),
    ("扬州炒饭", "staple", [("米饭", 400), ("鸡蛋", 150), ("火腿", 80), ("豌豆", 60)], ["盐", "生抽", "蚝油"], 2, 12, 12, 0, "粒粒分明,鲜香"),
    ("番茄鸡蛋面", "staple", [("面条", 250), ("番茄", 200), ("鸡蛋", 100)], ["盐", "生抽", "白糖"], 1, 8, 12, 0, "酸甜,家常"),
    ("葱油拌面", "staple", [("面条", 250), ("葱", 80)], ["生抽", "老抽", "白糖", "芝麻油"], 1, 10, 10, 0, "葱香,快手"),
    ("炸酱面", "staple", [("面条", 250), ("猪肉", 200), ("黄瓜", 80)], ["豆瓣酱", "生抽", "老抽", "料酒"], 2, 15, 20, 0, "酱香,浓郁"),
    ("红烧牛肉面", "staple", [("面条", 250), ("牛肉", 250), ("菠菜", 100)], ["生抽", "老抽", "五香粉", "盐"], 2, 20, 60, 0, "浓香,暖身"),
    ("家常炒面", "staple", [("面条", 300), ("猪肉", 120), ("白菜", 150), ("胡萝卜", 60)], ["生抽", "老抽", "蚝油"], 1, 12, 12, 0, "酱香,家常"),
    ("虾仁炒饭", "staple", [("米饭", 400), ("虾仁", 150), ("鸡蛋", 100), ("豌豆", 50)], ["盐", "生抽", "黑胡椒"], 2, 12, 12, 0, "鲜香,粒粒分明"),
    ("韭菜鸡蛋饺子", "staple", [("饺子皮", 300), ("韭菜", 250), ("鸡蛋", 150)], ["盐", "生抽", "芝麻油"], 2, 25, 15, 0, "家常,鲜香"),
    ("猪肉白菜饺子", "staple", [("饺子皮", 300), ("猪肉", 250), ("白菜", 200)], ["盐", "生抽", "芝麻油", "料酒"], 2, 25, 15, 0, "家常,多汁"),
    # ---- 煎 ----
    ("香煎带鱼", "fry", [("带鱼", 450), ("生姜", 15)], ["盐", "料酒", "生抽", "黑胡椒"], 2, 15, 15, 0, "外酥里嫩,鲜香"),
    ("香煎三文鱼", "fry", [("三文鱼", 300)], ["盐", "黑胡椒", "柠檬", "蜂蜜"], 1, 10, 10, 0, "鲜嫩,西式"),
    ("葱油饼", "fry", [("面粉", 300), ("葱", 80)], ["盐", "芝麻油"], 2, 30, 20, 0, "酥脆,葱香"),
    # ---- 凉拌 ----
    ("凉拌黄瓜", "cold", [("黄瓜", 300), ("大蒜", 15)], ["醋", "生抽", "芝麻油", "盐"], 1, 8, 5, 1, "爽脆,开胃"),
    ("拍黄瓜", "cold", [("黄瓜", 350), ("大蒜", 15)], ["醋", "生抽", "盐", "辣椒酱"], 1, 6, 5, 1, "爽口,快手"),
    ("凉拌木耳", "cold", [("木耳", 80), ("香菜", 30), ("大蒜", 10)], ["醋", "生抽", "芝麻油", "盐"], 1, 12, 8, 1, "爽脆,解腻"),
    ("凉拌菠菜", "cold", [("菠菜", 300), ("花生", 50), ("大蒜", 10)], ["生抽", "醋", "芝麻油", "盐"], 1, 10, 8, 0, "清爽,营养"),
    # ---- 烤 ----
    ("烤鸡翅", "bake", [("鸡翅", 500)], ["生抽", "料酒", "蜂蜜", "孜然"], 1, 20, 25, 1, "焦香,多汁"),
    ("烤五花肉", "bake", [("五花肉", 400)], ["生抽", "料酒", "孜然", "五香粉"], 1, 25, 30, 1, "焦香,下酒"),
]

# 每道菜一句简介，用做法 + 主料拼，避免"无描述"的空洞感
SUMMARY_TPL = {
    "stir_fry": "{main}大火快炒，{taste}，是一道很稳的家常下饭菜。",
    "braise": "{main}慢火炖到入味，汤汁浓郁，适合周末慢慢做一锅。",
    "steam": "蒸的做法最大程度留住{dish}的鲜味，清爽不油腻。",
    "soup": "{main}慢煮成汤，清润暖胃，饭前一小碗刚刚好。",
    "staple": "{main}做主角的快手主食，一碗就能解决一顿饭。",
    "fry": "{main}煎到两面金黄，外酥里嫩，配饭配酒都合适。",
    "cold": "{dish}爽脆开胃，不用开火也能端上桌。",
    "bake": "{main}进烤箱不用守着，外焦里嫩，是懒人友好做法。",
}

CUT_BY_CATEGORY = {
    "蔬菜": ["切块", "切片", "切段"],
    "肉类": ["切块", "切片", "切丝"],
    "水产": ["切块", "切段", "改花刀"],
    "豆制品": ["切块", "切片"],
    "主食": ["备好", "切小"],
    "蛋奶": ["打散"],
    "干货": ["泡发", "切丝"],
}


def build_steps(method, mains, seasonings, cook_time, rng):
    """按做法生成步骤。主料名嵌进步骤里，读起来才像"为这道菜写的"。"""
    m0 = mains[0][0] if mains else "食材"
    m1 = mains[1][0] if len(mains) > 1 else None
    s0 = seasonings[0] if seasonings else "盐"
    s1 = seasonings[1] if len(seasonings) > 1 else "生抽"

    if method == "stir_fry":
        steps = [
            "%s洗净切好，%s" % (m0, ("%s切配齐备用。" % m1) if m1 else "沥干水分备用。"),
            "锅烧热倒油，下蒜末和姜片爆出香味。",
            "倒入%s大火翻炒至断生。" % m0,
            "%s沿锅边淋入%s和%s，翻炒均匀。" % (("加入%s，" % m1) if m1 else "", s0, s1),
            "炒到入味即可出锅，别炒过头影响口感。",
        ]
    elif method == "braise":
        steps = [
            "%s切块，冷水下锅焯去血沫，捞出冲净。" % m0,
            "锅内放少许油，下%s炒出香味。" % s0,
            "倒入%s翻炒上色，加%s炒匀。" % (m0, s1),
            "加入没过食材的热水，大火烧开后转小火炖 %d 分钟。" % cook_time,
            "开大火收汁，汤汁浓稠时出锅装盘。",
        ]
    elif method == "steam":
        steps = [
            "%s处理干净，用%s和姜片腌 10 分钟去腥。" % (m0, "料酒"),
            "盘底垫几片姜，摆上%s。" % m0,
            "水烧开后上锅，大火蒸 %d 分钟。" % cook_time,
            "出锅倒掉盘里的腥水，铺上葱丝。",
            "淋一勺热油激香，再沿边淋入%s即可。" % s0,
        ]
    elif method == "soup":
        steps = [
            "%s洗净切小，%s" % (m0, ("%s切好备用。" % m1) if m1 else "姜片备好。"),
            "锅中加水烧开，%s下锅焯一遍后捞出。" % m0,
            "另起锅加足量清水，放入%s和姜片，大火烧开转中小火。" % (("与%s一起" % m1) if m1 else m0),
            "慢煮 %d 分钟，煮到食材软烂、汤色变浓。" % cook_time,
            "加%s调味，出锅前撒葱花。" % s0,
        ]
    elif method == "staple":
        steps = [
            "%s备好，%s切小丁。" % (m0, m1 or "配菜"),
            "锅烧热倒油，先把配料炒香。",
            "加入%s翻炒均匀。" % (m1 or "配菜"),
            "倒入%s，用铲子压散翻炒。" % m0,
            "加%s和%s调味，大火翻炒到入味出锅。" % (s0, s1),
        ]
    elif method == "fry":
        steps = [
            "%s洗净擦干水分，用%s腌 15 分钟。" % (m0, s1),
            "平底锅倒油烧至六成热。",
            "放入%s，中小火煎到一面定型。" % m0,
            "翻面继续煎 %d 分钟，煎至两面金黄。" % max(3, cook_time // 3),
            "出锅吸掉多余油，撒上%s即可。" % (m1 or "葱花"),
        ]
    elif method == "cold":
        steps = [
            "%s洗净，按纹理切好。" % m0,
            "锅中水烧开，%s焯 30 秒立刻捞出。" % m0,
            "过一遍凉开水，沥干后装盘。",
            "%s和%s调成料汁。" % (s0, s1),
            "淋上料汁拌匀，静置 5 分钟入味即可。",
        ]
    else:  # bake
        steps = [
            "%s洗净，用%s和%s腌 30 分钟。" % (m0, s0, s1),
            "烤箱预热至 200 度。",
            "烤盘铺锡纸，把%s摆开不要叠。" % m0,
            "入烤箱烤 %d 分钟，中途翻一次面。" % cook_time,
            "出炉稍晾，撒上孜然或芝麻即可。",
        ]
    return [{"no": i + 1, "text": t} for i, t in enumerate(steps)]


# 营养密度（每 100g 可食部，近似值，够真实即可）
NUTRI_BY_CATEGORY = {
    "蔬菜": (26, 1.6, 0.3, 5.0, 2.0, 8),
    "肉类": (200, 20.0, 13.0, 0.5, 0.0, 60),
    "水产": (105, 18.0, 3.0, 0.5, 0.0, 90),
    "蛋奶": (145, 13.0, 9.5, 1.5, 0.0, 130),
    "豆制品": (95, 8.5, 5.0, 3.5, 1.5, 12),
    "主食": (345, 8.0, 1.2, 74.0, 2.5, 5),
    "干货": (210, 21.0, 1.5, 32.0, 6.0, 300),
    "乳品": (65, 3.2, 3.5, 5.0, 0.0, 45),
    "果香": (560, 22.0, 45.0, 18.0, 7.0, 6),
    "调料": (120, 2.0, 1.0, 22.0, 1.0, 3000),
    "其他": (45, 4.0, 0.6, 6.0, 1.5, 10),
}


def estimate_nutrition(ingredients, serving):
    """按食材成分 × 克重 ÷ 人份 估算。调料不计入口径（与线上一致）。"""
    tot = [0.0] * 6
    for name, gram, category in ingredients:
        if category == "调料":
            continue
        d = NUTRI_BY_CATEGORY.get(category, NUTRI_BY_CATEGORY["其他"])
        for i in range(6):
            tot[i] += d[i] * gram / 100.0
    s = max(1, serving)
    return [round(v / s, 1) for v in tot]


def wilson_lower_bound(p, n, z=1.96):
    """与 DishInteractionService#wilsonLowerBound 完全一致的口径。
    公式不一致会导致"库里能直出、ES 里不能"。"""
    if n <= 0:
        return 0.0
    z2 = z * z
    centre = p + z2 / (2 * n)
    margin = z * ((p * (1 - p) + z2 / (4 * n)) / n) ** 0.5
    return max(0.0, min(1.0, (centre - margin) / (1 + z2 / n)))


# ================================================================== 评论语料
# 每条 = (文本模板, 适用标签集合)。"any" 表示所有菜都能用。
# 占位符：{dish}=菜名  {main}=主料  {taste}=口味标签首词
#
# 为什么用占位符而不是纯固定文本：固定文本循环几遍就露馅了，
# 把菜名和主料揉进句子，每条评论读起来才像"针对这道菜说的"。
COMMENT_TPL = {
    5: [
        ("这道{dish}我一周做了三次，家里那位现在点名要吃。", {"any"}),
        ("{main}的用量给得刚好，不用自己再摸索，第一次做就很稳。", {"any"}),
        ("周末做了一锅给爸妈尝，他们说明显比楼下馆子的强。", {"slow"}),
        ("步骤写得清楚，我这种平时不进厨房的人也一次成功了。", {"newbie"}),
        ("收汁那一步别省，做出来味道完全不一样，香得很。", {"slow"}),
        ("孩子平时挑食，这次居然添了半碗饭。", {"mild"}),
        ("按方子来的，一点没改，味道已经很好，没有踩雷。", {"any"}),
        ("备料到出锅不到半小时，下班回家救急用。", {"quick"}),
        ("第二次做了，把{taste}的味道调得更足一点，更合家人口味。", {"any"}),
        ("朋友来家里吃饭点名要这道，做完很有面子。", {"hard"}),
        ("汤汁拌饭一绝，我一个人能干掉两碗。", {"slow"}),
        ("以前在外面吃过，自己照着做居然还原了八九成。", {"any"}),
        ("火候说得很明白，跟着时间走不会老。", {"any"}),
        ("食材都是冰箱里常备的，不用专门跑超市。", {"any"}),
        ("做完拍照发朋友圈，好几个人问我要菜谱。", {"any"}),
        ("{main}处理得很嫩，一点都不柴，这点最惊喜。", {"meat"}),
        ("调料清单短，厨房小白也不会手忙脚乱。", {"newbie"}),
        ("家里老人牙口不好，做软一点正好合适。", {"slow"}),
        ("比我想象中简单，重点步骤都点出来了。", {"newbie"}),
        ("已经加入我的常做清单，隔几天就要做一次。", {"any"}),
        ("味道很正，{taste}的香气一进门就闻到了。", {"any"}),
        ("腌那十分钟真的有用，别偷懒跳过。", {"any"}),
        ("一人份的量刚好，不会做多做少。", {"any"}),
        ("出锅前那勺芝麻油是灵魂，香气立刻上来了。", {"any"}),
        ("连着做了两次，第二次明显比第一次顺手。", {"any"}),
        ("平时不爱吃这个的人都能接受，说明真的不难吃。", {"veg"}),
        ("配米饭配面条都行，怎么搭都不出错。", {"any"}),
        ("做完一大锅，第二天热一下味道也没散。", {"slow"}),
        ("这道菜让我对下厨有信心了，谢谢方子。", {"newbie"}),
        ("关键一步说得很细，照着做没糊锅。", {"any"}),
        ("家里人说比外卖强多了，还干净。", {"any"}),
        ("我把辣度减了一半给孩子吃，照样很好吃。", {"spicy"}),
        ("分量标得准，两口人吃正好不剩。", {"any"}),
        ("从备料到装盘全程计时，和写的时间基本对得上。", {"quick"}),
        ("炒的时间点给得准，照着数秒都不会老。", {"stir_fry"}),
        ("第一次做就成功了，很有成就感。", {"newbie"}),
        ("味道层次挺清楚，不是一味的重口。", {"any"}),
        ("冷藏后再热一遍，味道居然更进去了。", {"slow"}),
        ("我妈尝了一口说这个可以上桌待客了。", {"hard"}),
        ("真的省事，洗的锅碗都比其他菜少。", {"quick"}),
        ("这个做法把{main}的鲜味全留住了。", {"any"}),
        ("按这个量放调料，咸淡刚刚好，不用再补救。", {"any"}),
        ("做完给室友分了一份，第二天问我要链接。", {"any"}),
        ("蒸的时间控制得刚刚好，{main}一点没老。", {"steam"}),
        ("外皮焦香里面还多汁，烤箱菜能做到这样很满意。", {"bake"}),
        ("清爽不油腻，夏天吃这个最舒服。", {"cold"}),
        ("一碗下肚整个人都暖了，阴雨天就想吃这个。", {"soup"}),
        ("主食也能做得这么香，我连吃两碗。", {"staple"}),
    ],
    4: [
        ("整体挺好，下次我会少放一点盐，偏咸了些。", {"any"}),
        ("味道不错，就是{main}可以再多一点，吃着不过瘾。", {"any"}),
        ("成功了，就是最后收汁有点久，稍微干了一点。", {"slow"}),
        ("挺好吃的，下次打算把糖减半，家里人不爱甜。", {"any"}),
        ("步骤很清楚，新手也能做，只是备料比我预想的花时间。", {"any"}),
        ("做出来挺香，唯一的问题是油可以再少放一点。", {"any"}),
        ("不错，下次提前腌久一点应该更入味。", {"any"}),
        ("味道可以，就是火候不太好掌握，我第一次稍微过了。", {"any"}),
        ("家人评价不错，我自己觉得可以再辣一点。", {"mild"}),
        ("还原度挺高，要是能标注下中途要不要翻面就更好了。", {"any"}),
        ("蛮好吃的，就是分量偏少，两个人吃略紧。", {"any"}),
        ("做法没问题，我换了个锅，时间要稍微加几分钟。", {"any"}),
        ("味道稳，就是汤汁有点多，下次多收一会儿。", {"slow"}),
        ("挺满意的，唯一遗憾是{main}我没有买到新鲜的。", {"any"}),
        ("可以，下次我会先把{main}焯水再炒，口感应该更好。", {"any"}),
        ("做出来的卖相一般，但味道是真的好。", {"any"}),
        ("家里人都吃完了，说明没问题，下次多做点。", {"any"}),
        ("好吃，就是准备的东西有点多，适合周末做。", {"hard"}),
        ("第一次做稍微咸了点，第二次减量正好。", {"any"}),
        ("挺香，就是最后一步的油温我没控制好。", {"any"}),
        ("整体满意，如果能给个代替食材的建议会更好。", {"any"}),
        ("味道真不错，就是耗时比写的时间长一些。", {"slow"}),
        ("做出来了，卖相比菜谱图差一点，味道没差。", {"any"}),
        ("挺下饭的，就是有点油，下次我沥一下。", {"any"}),
        ("不错，孩子爱吃，就是辣度对他来说要再降。", {"spicy"}),
        ("成功了，感谢。下次试试加别的配菜。", {"any"}),
        ("好做，就是{main}切的时候要费点工夫。", {"any"}),
        ("味道好，就是我家锅小，得分两次炒。", {"stir_fry"}),
        ("挺好，就是调料里有一样我家没有，用别的代替了。", {"any"}),
        ("做出来挺满意，唯一是我把时间看错，多炖了十分钟。", {"slow"}),
        ("香是挺香，就是收拾起来麻烦点。", {"any"}),
        ("味道不错，下次少放点水，汤稀了。", {"soup"}),
        ("还可以，主要是我刀工不行，切得不均匀。", {"any"}),
        ("挺好吃的，就是感觉少了一点点酸味。", {"any"}),
        ("做了两次才摸到火候，第一次略老。", {"any"}),
        ("满意，就是这个量我家三口人吃有点不够。", {"any"}),
        ("不错，就是最后装盘不太好看，不影响吃。", {"any"}),
        ("味道可以，下次我想试试加香菜提味。", {"any"}),
        ("整体不错，唯一是步骤里水量说得有点模糊。", {"any"}),
        ("蒸出来水有点多，下次我少加一点水。", {"steam"}),
        ("烤的时间建议再给个区间，我家烤箱脾气不一样。", {"bake"}),
        ("拌匀之后最好再静置久一点，会更入味。", {"cold"}),
        ("面条有点坨了，下次我先过一遍凉水。", {"staple"}),
    ],
    3: [
        ("味道一般，没什么惊艳的，但也算能吃。", {"any"}),
        ("做出来了，家里人反应平平，可能不合我们口味。", {"any"}),
        ("中规中矩，照着做没失败，但也不会再做第二次。", {"any"}),
        ("感觉少了点什么，我自己加了点调料才好吃。", {"any"}),
        ("不难吃，但也没到会推荐给别人的程度。", {"any"}),
        ("做法没问题，就是味道偏淡，得自己加重口。", {"any"}),
        ("可能是我手艺问题，做出来比预期差一点。", {"any"}),
        ("食材搭配有点奇怪，我个人不太习惯。", {"any"}),
        ("还行，就是步骤写得有点简略，新手容易卡住。", {"hard"}),
        ("做出来偏咸，下次要减量，这次只能凑合吃。", {"any"}),
        ("说不上好吃，胜在快手，赶时间可以试。", {"quick"}),
        ("味道普通，感觉外卖随便点一个都比这个强。", {"any"}),
        ("没有想象中好，主要是{main}的味道没出来。", {"any"}),
        ("一般般，可能这道菜本身就不是我的菜。", {"any"}),
        ("不功不过，做完没什么成就感。", {"any"}),
        ("分量和味道都一般，胜在食材好买。", {"any"}),
    ],
    2: [
        ("做出来偏咸，我按方子放的盐，可能不适合我家口味。", {"any"}),
        ("步骤里的时间不太准，我按着做有点糊了。", {"any"}),
        ("{main}的处理没讲清楚，我做出来有点腥。", {"any"}),
        ("味道比较寡淡，需要自己大改，不太推荐照搬。", {"any"}),
        ("可能是我操作问题，最后成品和描述差挺远。", {"any"}),
        ("调料比例不太对，做完偏甜，家里人都没怎么动。", {"any"}),
        ("感觉这道菜不太适合新手，我翻车了。", {"hard"}),
        ("做出来口感偏老，下次不会按这个时间来。", {"any"}),
        ("食材用量不太合理，我按量做出来太多了。", {"any"}),
        ("有点失望，味道和想象中差不少。", {"any"}),
    ],
    1: [
        ("失败了，不知道哪一步出了问题，最后没敢吃。", {"any"}),
        ("完全做不出来，步骤跳得太快，新手别试。", {"hard"}),
        ("味道很奇怪，家里人都不吃，最后倒掉了。", {"any"}),
        ("按方子做完全不成形，可能我火候差太远。", {"any"}),
        ("这个搭配真的吃不惯，做完浪费食材了。", {"any"}),
        ("咸得没法入口，建议把用量再核一下。", {"any"}),
    ],
}

# 分数分布：真实世界的评分是长尾的，清一色 5 分一眼假
SCORE_WEIGHTS = [(5, 50), (4, 30), (3, 12), (2, 6), (1, 2)]

# 评论里的"前缀/后缀"变体，进一步打散重复感
PREFIX_ANY = ["", "", "", "", "第二次做，", "上周做的，", "今天又做了一次，", "照着方子，", "第一次尝试，"]

# 人的作息：晚饭前后最活跃，凌晨几乎没人发评论
HOUR_WEIGHTS = [1, 1, 1, 1, 1, 2, 4, 5, 5, 4, 4, 6,
                8, 7, 5, 4, 5, 7, 12, 14, 13, 10, 6, 3]

EQUIPMENT_BY_METHOD = {
    "stir_fry": ["炒锅"], "braise": ["炒锅", "砂锅"], "steam": ["蒸锅"],
    "soup": ["汤锅"], "staple": ["炒锅", "煮锅"], "fry": ["平底锅"],
    "cold": ["拌盆"], "bake": ["烤箱"],
}

CHEF_TIPS_BY_METHOD = {
    "stir_fry": ["锅要烧到微微冒烟再下菜，才有镬气。", "调料提前兑成碗汁，下锅不慌。"],
    "braise": ["焯水后用温水冲，冷水会让肉收缩变柴。", "炖的时候水要一次加够。"],
    "steam": ["水开后再上锅，蒸的时间从那一刻开始算。", "盘里的腥水一定要倒掉。"],
    "soup": ["中途别加冷水，汤的鲜味会断。", "出锅前再调味，盐放早了肉容易老。"],
    "staple": ["米饭用隔夜的，水分少更好炒散。", "面条煮到八分熟就捞，后面还要炒。"],
    "fry": ["食材表面擦干再下锅，不容易溅油。", "一面定型再翻，别急着动它。"],
    "cold": ["焯水后过一遍凉开水，口感更脆。", "料汁吃之前再淋，放久了会出水。"],
    "bake": ["烤盘垫锡纸，事后好清洗。", "腌够时间再进烤箱，味道差很多。"],
}


def esc(s):
    return s.replace("\\", "\\\\").replace("'", "''")


def jstr(obj):
    """JSON 字段。MySQL 里存文本，转义交给 esc()。"""
    return json.dumps(obj, ensure_ascii=False)


def query(sql):
    r = subprocess.run([MYSQL, "-uroot", "-proot", "--default-character-set=utf8mb4",
                        DB, "-N", "-e", sql], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace"))
    return r.stdout.decode("utf-8").strip()


def pick_hour(rng):
    return rng.choices(range(24), weights=HOUR_WEIGHTS, k=1)[0]


def random_moment(start, end, rng):
    """在 [start, end] 里取一个时刻，小时按作息加权。"""
    span = (end - start).total_seconds()
    day = start + timedelta(seconds=rng.random() * span)
    return day.replace(hour=pick_hour(rng),
                       minute=rng.randint(0, 59),
                       second=rng.randint(0, 59))


def dish_tags(method, mains_categories, spicy, difficulty, cook_time):
    tags = {method}
    if cook_time <= 12:
        tags.add("quick")
    if cook_time >= 40:
        tags.add("slow")
    if spicy >= 2:
        tags.add("spicy")
    if spicy == 0:
        tags.add("mild")
    if any(c in ("肉类", "水产") for c in mains_categories):
        tags.add("meat")
    if all(c in ("蔬菜", "豆制品", "干货") for c in mains_categories):
        tags.add("veg")
    if difficulty == 1:
        tags.add("newbie")
    if difficulty == 3:
        tags.add("hard")
    return tags


def pick_comment_tpl(score, tags, rng, used=None):
    """挑一条和当前菜特征对得上的评论模板。挑不到就退回通用池。

    used 是本道菜已经用过的模板，用来避免"同一道菜下面好几条一模一样的评论" ——
    那是最容易露馅的地方。
    """
    pool = COMMENT_TPL[score]
    matched = [t for t, tg in pool if "any" in tg or (tg & tags)]
    if len(matched) < 3:
        matched = [t for t, tg in pool if "any" in tg]
    if used:
        fresh = [t for t in matched if t not in used]
        if fresh:
            return rng.choice(fresh)
    return rng.choice(matched)


def make_comment_text(score, tags, dish_name, main_name, taste, rng, used):
    """拼一条评论，尽量不撞同一道菜下已有的文本。"""
    for _ in range(8):
        text = rng.choice(PREFIX_ANY) + render_comment(
            pick_comment_tpl(score, tags, rng, used), dish_name, main_name, taste)
        if text not in used:
            used.add(text)
            return text
    # 池子用尽的兜底：加个序号式前缀也要保证不重复
    text = render_comment(pick_comment_tpl(score, tags, rng), dish_name, main_name, taste)
    used.add(text)
    return text


def render_comment(tpl, dish_name, main_name, taste):
    return tpl.replace("{dish}", dish_name) \
              .replace("{main}", main_name) \
              .replace("{taste}", taste)


def chunked_insert(table, columns, rows, out, batch=200):
    """批量 INSERT。行数多时单条 INSERT 会把文件撑到几倍大。"""
    if not rows:
        return
    col_sql = ", ".join(columns)
    for i in range(0, len(rows), batch):
        part = rows[i:i + batch]
        vals = ",\n  ".join("(" + ", ".join(r) + ")" for r in part)
        out.append("INSERT INTO %s (%s) VALUES\n  %s;\n" % (table, col_sql, vals))


def fmt_dt(dt):
    return "'%s'" % dt.strftime("%Y-%m-%d %H:%M:%S")


def main():
    ap = argparse.ArgumentParser(description="生成榜单模拟数据")
    ap.add_argument("--write", action="store_true", help="写出 SQL 文件")
    ap.add_argument("--days", type=int, default=180, help="数据散布在最近多少天内")
    args = ap.parse_args()

    rng = random.Random(SEED)
    now = datetime.now()
    earliest = now - timedelta(days=args.days)

    print("读取现有数据 ...")
    users = [int(x) for x in query("SELECT id FROM t_user WHERE deleted=0 AND status=1").split()]
    if not users:
        print("t_user 里没有可用用户，先注册几个再说。")
        return 1

    ing_map = {}
    for line in query("SELECT id,name,category FROM t_ingredient").splitlines():
        if not line.strip():
            continue
        iid, name, cat = line.split("\t")
        ing_map[name] = (int(iid), cat)
    print("  用户 %d 个，食材 %d 种" % (len(users), len(ing_map)))

    existing_names = set(query("SELECT name FROM t_dish WHERE deleted=0").splitlines())
    pub_rows = query(
        "SELECT id,name,rating_avg,rating_count,collect_count "
        "FROM t_dish WHERE deleted=0 AND status=1").splitlines()
    published = []
    for line in pub_rows:
        if not line.strip():
            continue
        p = line.split("\t")
        published.append({
            "id": int(p[0]), "name": p[1],
            "avg": float(p[2] or 0), "count": int(p[3] or 0), "collect": int(p[4] or 0),
        })

    existing_pairs = set()
    for line in query("SELECT dish_id,user_id FROM t_dish_rating WHERE deleted=0").splitlines():
        if not line.strip():
            continue
        d, u = line.split("\t")
        existing_pairs.add((int(d), int(u)))

    # 收藏表也有 uk_dish_user，同样要避开已有的组合，否则导入会撞唯一键
    existing_collect = set()
    for line in query("SELECT dish_id,user_id FROM t_dish_collect WHERE deleted=0").splitlines():
        if not line.strip():
            continue
        d, u = line.split("\t")
        existing_collect.add((int(d), int(u)))
    print("  已发布 %d 道，已有评分 %d 条，已有收藏 %d 条"
          % (len(published), len(existing_pairs), len(existing_collect)))

    # ---------------------------------------------------------------- 新菜品
    dish_rows, ing_rows, new_dishes = [], [], []
    skipped = []
    for (name, method, mains, seas, diff, prep, cook, spicy, taste) in DISHES:
        if name in existing_names:
            skipped.append(name)
            continue

        dish_id = next_id()
        serving = rng.choice([2, 2, 2, 3, 4])
        publish = random_moment(earliest, now - timedelta(days=3), rng)

        resolved = []
        for n, gram in mains:
            if n not in ing_map:
                print("  警告: 食材不在表里 -> %s" % n)
                continue
            iid, cat = ing_map[n]
            resolved.append((n, gram, cat))

        steps = build_steps(method, mains, seas, cook, rng)
        nut = estimate_nutrition(resolved, serving)
        main_name = resolved[0][0] if resolved else name
        taste_first = taste.split(",")[0]
        summary = SUMMARY_TPL[method].replace("{dish}", name) \
                                     .replace("{main}", main_name) \
                                     .replace("{taste}", taste_first)

        seasoning_grams = {"盐": 2, "白糖": 5, "生抽": 10, "老抽": 5, "醋": 8,
                           "蚝油": 10, "料酒": 10, "花椒": 2, "干辣椒": 5,
                           "豆瓣酱": 15, "孜然": 3, "黑胡椒": 2, "五香粉": 2,
                           "辣椒酱": 10, "蜂蜜": 8, "芝麻油": 5}

        sort_no = 0
        for idx, (n, gram, cat) in enumerate(resolved):
            sort_no += 1
            ing_rows.append([
                str(next_id()), str(dish_id), str(ing_map[n][0]),
                "'%s'" % esc(n), "%.2f" % gram, "'g'", "%.2f" % gram,
                "1" if idx == 0 else "0", str(sort_no), fmt_dt(publish),
            ])
        for n in seas:
            if n not in ing_map:
                continue
            sort_no += 1
            g = seasoning_grams.get(n, 8)
            ing_rows.append([
                str(next_id()), str(dish_id), str(ing_map[n][0]),
                "'%s'" % esc(n), "%.2f" % g, "'g'", "%.2f" % g,
                "0", str(sort_no), fmt_dt(publish),
            ])

        dish_rows.append([
            str(dish_id), "'%s'" % esc(name), "'%s'" % esc(name), "NULL",
            "1", "NULL", "1", str(serving), "'%s'" % esc(taste_first),
            "'%s'" % esc(summary), str(diff), str(prep), str(cook), str(spicy),
            "'%s'" % esc(jstr(steps)),
            "'%s'" % esc(jstr(seas)),
            "'%s'" % esc(jstr(EQUIPMENT_BY_METHOD[method])),
            "'%s'" % esc(jstr(CHEF_TIPS_BY_METHOD[method])),
            "'%s'" % esc(jstr(["%s可以用同季的替代品，用量保持不变。" % main_name])),
            str(nut[0]), str(nut[1]), str(nut[2]), str(nut[3]), str(nut[4]), str(nut[5]),
            "2", "1",
            "0.00", "0", "0.0000", "0", "0",
            fmt_dt(publish), "0", fmt_dt(publish), fmt_dt(publish),
        ])

        new_dishes.append({
            "id": dish_id, "name": name, "method": method,
            "main": main_name, "taste": taste_first,
            "cats": [c for _, _, c in resolved],
            "spicy": spicy, "diff": diff, "cook": cook,
            "publish": publish, "avg": 0.0, "count": 0, "collect": 0,
        })

    # ---------------------------------------------------------------- 评分 / 评论 / 收藏
    rating_rows, comment_rows, collect_rows, update_rows = [], [], [], []
    targets = new_dishes + published

    for d in targets:
        is_new = "method" in d
        tags = dish_tags(d.get("method", "stir_fry"), d.get("cats", ["蔬菜"]),
                         d.get("spicy", 0), d.get("diff", 1), d.get("cook", 15)) \
            if is_new else {"any", d.get("method", "stir_fry")}
        main_name = d.get("main", d["name"])
        taste = d.get("taste", "家常")
        start = d.get("publish", earliest)
        if start >= now:
            start = earliest

        # 人气：多数菜一般热，少数菜很热 —— 榜单才有层次
        pop = rng.uniform(0.15, 1.0)
        want = int(6 + pop * 42)

        avail = [u for u in users if (d["id"], u) not in existing_pairs]
        rng.shuffle(avail)
        picked = avail[:want]
        if not picked:
            continue

        scores = rng.choices([s for s, _ in SCORE_WEIGHTS],
                             weights=[w for _, w in SCORE_WEIGHTS], k=len(picked))

        old_count, old_avg, old_collect = d["count"], d["avg"], d["collect"]
        score_sum = old_avg * old_count

        # 本道菜已经用过的评论文本，保证一道菜下面不会读到重复的话
        used_texts = set()

        for uid, sc in zip(picked, scores):
            score_sum += sc
            moment = random_moment(start, now, rng)
            cooked = 1 if (sc >= 4 and rng.random() < 0.82) else (1 if rng.random() < 0.25 else 0)
            # 评分表自带的短评：只有一部分人写
            r_comment = "NULL"
            if rng.random() < 0.34:
                r_comment = "'%s'" % esc(make_comment_text(
                    sc, tags, d["name"], main_name, taste, rng, used_texts))

            rating_rows.append([
                str(next_id()), str(d["id"]), str(uid), str(sc),
                str(max(1, min(5, sc + rng.choice([0, 0, 0, -1, 1])))),
                str(cooked), r_comment, "1.00", "0",
                fmt_dt(moment), fmt_dt(moment),
            ])

            # 评论表：不是每个评分都配一条评论
            if rng.random() < 0.62:
                text = make_comment_text(sc, tags, d["name"], main_name, taste, rng, used_texts)
                like = 0
                if sc == 5:
                    like = int(rng.expovariate(1 / 9))
                elif sc == 4:
                    like = int(rng.expovariate(1 / 4))
                elif sc == 3:
                    like = rng.randint(0, 3)
                cm = moment + timedelta(minutes=rng.randint(1, 40))
                if cm > now:
                    cm = now
                comment_rows.append([
                    str(next_id()), str(d["id"]), str(uid),
                    "'%s'" % esc(text), str(sc), str(min(like, 180)),
                    "1", "0", fmt_dt(cm), fmt_dt(cm),
                ])

        # 收藏：和评价数正相关，但不等于评价数
        collect_n = int(len(picked) * rng.uniform(0.35, 0.95))
        avail_collect = [u for u in users if (d["id"], u) not in existing_collect]
        for uid in rng.sample(avail_collect, min(collect_n, len(avail_collect))):
            cm = random_moment(start, now, rng)
            collect_rows.append([
                str(next_id()), str(d["id"]), str(uid), "0", fmt_dt(cm),
            ])

        total = old_count + len(picked)
        avg = score_sum / total
        wilson = wilson_lower_bound(avg / 5.0, total)
        collect_total = old_collect + collect_n
        views = (total * rng.randint(7, 26)) + rng.randint(20, 400)

        update_rows.append(
            "UPDATE t_dish SET rating_avg=%.2f, rating_count=%d, rating_wilson=%.4f, "
            "collect_count=%d, view_count=%d, update_time=NOW() WHERE id=%d;"
            % (avg, total, wilson, collect_total, views, d["id"]))

        d["final_avg"], d["final_count"] = avg, total
        d["final_wilson"] = wilson
        d["final_collect"] = collect_total

    # ---------------------------------------------------------------- 输出
    print("")
    print("新增菜品 %d 道（跳过已存在的 %d 个同名菜）" % (len(new_dishes), len(skipped)))
    print("新增评分 %d 条 / 评论 %d 条 / 收藏 %d 条" %
          (len(rating_rows), len(comment_rows), len(collect_rows)))
    print("回填冗余字段 %d 道菜" % len(update_rows))
    print("已发布总数：%d -> %d 道" % (len(published), len(published) + len(new_dishes)))

    top = sorted([d for d in targets if d.get("final_count", 0) >= 1],
                 key=lambda x: -x.get("final_wilson", 0))[:10]
    print("")
    print("生成后高分榜前 10（按威尔逊下界）：")
    for i, d in enumerate(top, 1):
        print("  %2d. %-12s  %.2f 分  %3d 人评价  威尔逊 %.4f"
              % (i, d["name"], d["final_avg"], d["final_count"], d["final_wilson"]))

    if not args.write:
        print("")
        print("这是预览。加 --write 才会生成 sql/seed-rank-data.sql")
        return 0

    out = ["-- 榜单模拟数据（seed-rank-data.py 生成，%s）\n"
           "-- 导入：mysql -uroot -proot --default-character-set=utf8mb4 project0914 < sql/seed-rank-data.sql\n"
           % datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
           "SET NAMES utf8mb4;\n",
           "START TRANSACTION;\n"]

    chunked_insert("t_dish", [
        "id", "name", "name_key", "cover_url", "source", "creator_id", "status",
        "base_serving", "taste_tag", "summary", "difficulty", "prep_time_min",
        "cook_time_min", "spicy_level", "step_json", "seasonings", "equipment",
        "chef_tips", "substitutes", "energy_kcal", "protein_g", "fat_g", "carb_g",
        "fiber_g", "sodium_mg", "nutrition_source", "nutrition_verified",
        "rating_avg", "rating_count", "rating_wilson", "collect_count", "view_count",
        "publish_time", "deleted", "create_time", "update_time"], dish_rows, out)

    chunked_insert("t_dish_ingredient", [
        "id", "dish_id", "ingredient_id", "ingredient_name", "amount", "unit",
        "amount_gram", "main_flag", "sort_no", "create_time"], ing_rows, out)

    chunked_insert("t_dish_rating", [
        "id", "dish_id", "user_id", "score", "taste_score", "cooked_flag",
        "comment", "weight", "deleted", "create_time", "update_time"], rating_rows, out)

    chunked_insert("t_dish_comment", [
        "id", "dish_id", "user_id", "content", "rating", "like_count",
        "status", "deleted", "create_time", "update_time"], comment_rows, out)

    chunked_insert("t_dish_collect", [
        "id", "dish_id", "user_id", "deleted", "create_time"], collect_rows, out)

    out.append("\n-- 回填冗余字段（榜单读的是这些列，不回填榜单数字不会变）\n")
    out.extend(u + "\n" for u in update_rows)
    out.append("\nCOMMIT;\n")

    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "seed-rank-data.sql")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(out))
    print("")
    print("已写出 %s（%.1f KB）" % (path, os.path.getsize(path) / 1024))
    print("导入命令：")
    print('  "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -proot '
          '--default-character-set=utf8mb4 project0914 < sql/seed-rank-data.sql')
    return 0


if __name__ == "__main__":
    sys.exit(main())
