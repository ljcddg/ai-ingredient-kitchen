"""vision 模块测试。

分层：
- 纯逻辑用例（错误路径）不需要模型，任何环境可跑；
- smoke 用例需要 ultralytics + 权重文件，用 importorskip 保护 —— 没装依赖时跳过而非报错。
"""
from __future__ import annotations

import pytest

from ai_service.vision import (DEFAULT_WEIGHTS, VisionConfig, VisionModelError,
                               YoloDetector)


@pytest.fixture
def detector():
    return YoloDetector(VisionConfig(weights="__no_such_weights__.pt"))


# ---------- 错误路径（不加载模型） ----------

def test_empty_images_rejected(detector):
    with pytest.raises(VisionModelError) as e:
        detector.detect([])
    assert e.value.kind == "bad_request"


def test_too_many_images_rejected(detector):
    images = [{"imageId": f"img-{i}", "mediaPath": "x.jpg"} for i in range(10)]
    with pytest.raises(VisionModelError) as e:
        detector.detect(images)
    assert e.value.kind == "bad_request"
    assert "9" in e.value.message


def test_read_image_missing_file(detector):
    with pytest.raises(VisionModelError) as e:
        detector._read_image("__definitely_missing__.jpg")
    assert e.value.kind == "bad_request"


def test_read_image_rejects_garbage(detector, tmp_path):
    fake = tmp_path / "not_an_image.jpg"
    fake.write_bytes(b"this is not an image")
    with pytest.raises(VisionModelError) as e:
        detector._read_image(str(fake))
    assert e.value.kind == "bad_request"


def test_default_weights_is_relative():
    """写裸名会让 ultralytics 联网下载 —— 默认值必须是相对路径。"""
    assert "\\" not in DEFAULT_WEIGHTS and "/" in DEFAULT_WEIGHTS
    assert DEFAULT_WEIGHTS.startswith("yolo_model/weights/")


def test_device_auto_resolves(detector):
    device = detector._resolve_device()
    assert device in ("cpu", "cuda:0", "cuda")


# ---------- smoke（需要 ultralytics + 权重） ----------

def test_detect_smoke():
    pytest.importorskip("ultralytics", reason="ultralytics 未安装，跳过 smoke")

    from pathlib import Path

    from PIL import Image

    weights = Path(__file__).resolve().parent.parent / DEFAULT_WEIGHTS
    if not weights.is_file():
        pytest.skip(f"权重不存在：{weights}")

    # 画一张红彤彤的圆（近似苹果），用 PIL 落盘再识别：
    # 不断言具体 label（COCO 对小图未必检出），只验证"加载→推理→结果结构"链路是通的
    from PIL import Image, ImageDraw

    image = Image.new("RGB", (640, 480), (30, 30, 30))
    ImageDraw.Draw(image).ellipse([170, 90, 470, 390], fill=(200, 30, 30))
    import tempfile

    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as f:
        image.save(f, format="JPEG")
        path = f.name

    detector = YoloDetector(VisionConfig(device="cpu", conf=0.1))
    result = detector.detect([{"imageId": "img-1", "mediaPath": path}])

    assert result["model"] == "yolov8n.pt"
    assert len(result["images"]) == 1
    entry = result["images"][0]
    assert entry["imageId"] == "img-1"
    assert "error" not in entry, entry.get("error")
    assert isinstance(entry["detections"], list)
    for d in entry["detections"]:
        assert set(d) == {"label", "confidence", "box"}
        assert 0 < d["confidence"] <= 1
        assert len(d["box"]) == 4
