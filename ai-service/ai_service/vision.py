"""YOLO 图片识别（Phase 3）：多图批量检测 → label + box + confidence。

设计约束（docs/yolo-vision-design.md §5）：
- **懒加载**：没装 ultralytics 的服务照样能起（生成接口不受影响），首次识别请求才加载权重；
- **批量推理**：ultralytics 的 predict 原生支持 list 输入，9 张图一次 batch，GPU 上最高效；
- **权重一律写相对路径** `yolo_model/weights/xxx.pt` —— 写裸名 `yolov8n.pt` 的话
  ultralytics 会尝试联网下载（开发机代理下必翻车）；
- **读图用 np.fromfile + cv2.imdecode**：cv2.imread 不吃 Windows 中文路径，且失败时
  静默返回 None，症状是"图里什么都没有"，极难排查；
- **label → 食材 ID 的映射在 Java 侧**（t_vision_label 表 + IngredientDirectory 别名归一），
  本模块只回答"图里有什么"（英文类别名 + 框 + 置信度），不认识任何业务主数据。

线程安全：Flask --threads 8 下模型单例共用，predict 整体加锁。yolov8n 单图 GPU 4.4ms
（实测），锁不构成瓶颈；CPU 模式串行也在秒级，MVP 定位可接受。
"""
from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List

logger = logging.getLogger(__name__)

# 权重相对 ai-service 根目录（app.py 所在）的路径；__file__ 锚定，不依赖工作目录
DEFAULT_WEIGHTS = "yolo_model/weights/yolov8n.pt"


class VisionModelError(Exception):
    """模型/图片处理失败。kind 供路由做错误分类。"""

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass
class VisionConfig:
    weights: str = DEFAULT_WEIGHTS
    device: str = "auto"      # auto = 有卡用卡没卡用 CPU；也可写死 cpu / cuda:0
    conf: float = 0.25        # ultralytics 初筛阈值；真正的"丢弃/默认勾选"阈值在 Java 侧
    imgsz: int = 640
    max_images: int = 9


@dataclass
class Detection:
    label: str
    confidence: float
    box: List[int]            # [x, y, w, h] 像素，供 Java 侧裁剪小图

    def to_dict(self) -> dict:
        return {"label": self.label,
                "confidence": round(self.confidence, 4),
                "box": [int(v) for v in self.box]}


class YoloDetector:
    """YOLOv8 检测器。单例挂在 app.extensions["yolo_detector"]，模型懒加载。"""

    def __init__(self, config: VisionConfig):
        self._config = config
        self._model = None
        self._device = "cpu"
        self._lock = threading.Lock()

    # ---------- 内部 ----------

    def _resolve_weights(self) -> Path:
        path = Path(self._config.weights)
        if not path.is_absolute():
            path = Path(__file__).resolve().parent.parent / path
        if not path.is_file():
            raise VisionModelError(
                "model_not_loaded",
                f"YOLO 权重文件不存在：{path}（确认 ai-service/yolo_model/weights/ 下有文件）")
        return path

    def _resolve_device(self) -> str:
        if self._config.device != "auto":
            return self._config.device
        try:
            import torch
            return "cuda:0" if torch.cuda.is_available() else "cpu"
        except Exception:
            return "cpu"

    def _ensure_model(self):
        """懒加载。双重检查锁：并发首请求只加载一次。"""
        if self._model is not None:
            return
        with self._lock:
            if self._model is not None:
                return
            try:
                from ultralytics import YOLO
            except ImportError as e:
                raise VisionModelError(
                    "model_not_loaded",
                    f"ultralytics 未安装（{e}）；补装命令见 requirements.txt 末尾注释")
            weights = self._resolve_weights()
            self._device = self._resolve_device()
            logger.info("加载 YOLO 权重：%s（device=%s）", weights, self._device)
            model = YOLO(str(weights))
            model.to(self._device)
            self._model = model

    @staticmethod
    def _read_image(media_path: str):
        """读图。cv2.imread 不吃中文路径且失败静默返 None —— 必须 np.fromfile + imdecode。"""
        import cv2
        import numpy as np

        try:
            data = np.fromfile(media_path, dtype=np.uint8)
        except Exception as e:
            raise VisionModelError("bad_request", f"图片读取失败：{media_path}（{e}）")
        if data.size == 0:
            raise VisionModelError("bad_request", f"图片为空或不存在：{media_path}")
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if image is None:
            raise VisionModelError("bad_request", f"图片解码失败（格式不支持？）：{media_path}")
        return image

    # ---------- 对外 ----------

    def detect(self, images: List[Dict]) -> Dict:
        """批量识别。

        images: [{"imageId": "...", "mediaPath": "..."}, ...]
        返回：{"model": ..., "device": ..., "images": [{"imageId", "detections"|"error"}]}

        单张图读取失败只让那张图带 error 字段，不影响同批其他图 ——
        一张坏图不该拖垮整批（用户可能混进了截图/动图）。
        """
        if not images:
            raise VisionModelError("bad_request", "images 不能为空")
        if len(images) > self._config.max_images:
            raise VisionModelError(
                "bad_request",
                f"单次最多 {self._config.max_images} 张图片，收到 {len(images)} 张")

        self._ensure_model()

        loaded: List[Dict] = []   # 读图成功的 {"imageId", "image"}
        slots: List[int] = []     # 与 loaded 对齐：每项在 results 里的下标
        results: List[Dict] = [{"imageId": str(item.get("imageId") or "")}
                               for item in images]
        for i, item in enumerate(images):
            media_path = str(item.get("mediaPath") or "")
            if not media_path:
                results[i]["error"] = "mediaPath 为空"
                continue
            try:
                loaded.append({"imageId": results[i]["imageId"],
                               "image": self._read_image(media_path)})
                slots.append(i)
            except VisionModelError as e:
                results[i]["error"] = e.message

        if loaded:
            with self._lock:
                predictions = self._model.predict(
                    source=[item["image"] for item in loaded],
                    conf=self._config.conf,
                    imgsz=self._config.imgsz,
                    verbose=False,
                    device=self._device,
                )
            names = self._model.names
            for slot, item, prediction in zip(slots, loaded, predictions):
                detections = []
                for box in prediction.boxes:
                    cls_id = int(box.cls.item())
                    xywh = box.xywh.tolist()[0] if hasattr(box.xywh, "tolist") else box.xywh
                    detections.append(Detection(
                        label=str(names.get(cls_id, cls_id)),
                        confidence=float(box.conf.item()),
                        box=[round(v) for v in xywh],
                    ).to_dict())
                results[slot] = {"imageId": item["imageId"], "detections": detections}

        return {
            "model": Path(self._config.weights).name,
            "device": self._device,
            "images": results,
        }
