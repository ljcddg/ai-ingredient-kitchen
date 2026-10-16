package com.apesource.dishservice.service;

import com.apesource.commonservice.exception.BusinessException;
import com.apesource.dishservice.ai.VisionClient;
import com.apesource.dishservice.ai.VisionProperties;
import com.apesource.dishservice.dto.VisionCandidate;
import com.apesource.dishservice.dto.VisionDetection;
import com.apesource.dishservice.dto.VisionImageResult;
import com.apesource.dishservice.dto.VisionResult;
import com.apesource.dishservice.dto.VisionUnmappedLabel;
import com.apesource.dishservice.entity.VisionDetectLog;
import com.apesource.dishservice.entity.VisionLabel;
import com.apesource.dishservice.mapper.VisionDetectLogMapper;
import com.apesource.dishservice.mapper.VisionLabelMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 识菜编排：多图上传 → 落盘 → YOLO 识别 → 两级映射 → 聚合清单 → 裁剪小图 → 记录日志。
 *
 * <p>职责边界（docs/yolo-vision-design.md §4）：
 * Python 只回答"图里有什么"，本服务决定"这是什么食材"（映射+聚合），
 * 用户决定"用哪些"（前端勾选）。
 *
 * <p>两个绝不抛：识别记录写库失败（与 t_ingredient_candidate 同一原则，
 * 不能让记账功能搞挂主流程）；单张图裁剪失败（cropUrl 留空即可）。
 */
@Slf4j
@Service
public class VisionService {

    /** 允许的图片类型（其余直接拒，别把 exe 当图传进去） */
    private static final List<String> ALLOWED_TYPES =
            List.of("image/jpeg", "image/jpg", "image/png");

    private final VisionClient visionClient;
    private final VisionProperties visionProperties;
    private final VisionLabelMapper visionLabelMapper;
    private final VisionDetectLogMapper visionDetectLogMapper;
    private final IngredientDirectory directory;
    private final ObjectMapper objectMapper;

    public VisionService(VisionClient visionClient,
                         VisionProperties visionProperties,
                         VisionLabelMapper visionLabelMapper,
                         VisionDetectLogMapper visionDetectLogMapper,
                         IngredientDirectory directory,
                         ObjectMapper objectMapper) {
        this.visionClient = visionClient;
        this.visionProperties = visionProperties;
        this.visionLabelMapper = visionLabelMapper;
        this.visionDetectLogMapper = visionDetectLogMapper;
        this.directory = directory;
        this.objectMapper = objectMapper;
    }

    /**
     * 识菜主流程。
     *
     * @param userId 网关注入的 X-User-Id（记录日志 + 后续限流用）
     * @param files  多图（数量/大小/类型在此校验，不合格直接 BusinessException）
     */
    public VisionResult detect(Long userId, List<MultipartFile> files) {
        validate(files);
        String requestId = visionClient.newRequestId();
        long start = System.currentTimeMillis();

        // 1. 落盘（ASCII 文件名：cv2 不吃中文路径，存储层保持 ASCII 是双保险）
        Path dayDir = Path.of(visionProperties.getUploadDir(),
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM")));
        List<StoredImage> stored = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String imageId = "img-" + (i + 1);
            Path saved = save(dayDir, file);
            stored.add(new StoredImage(imageId, saved, file.getContentType()));
        }

        // 2. 调 Python（mediaPath 传绝对路径）
        List<Map<String, String>> payload = new ArrayList<>();
        for (StoredImage s : stored) {
            payload.add(Map.of("imageId", s.imageId, "mediaPath",
                    s.path.toAbsolutePath().normalize().toString()));
        }
        VisionClient.PythonVisionResponse response = visionClient.detect(payload, requestId);
        if (!response.ok()) {
            // 识别失败不落库、不糊错：ai-service 不可用时前端回退手动选食材
            // （docs/yolo-vision-design.md §7.3：识别是加速项，不是必经环节）
            throw new BusinessException(
                    "识别服务暂不可用，请稍后重试或手动选择食材");
        }

        // 3. 映射表 + 目录两级映射
        Map<String, VisionLabel> labelMap = loadLabelMap();
        List<VisionImageResult> imageResults = new ArrayList<>();
        List<List<VisionAggregator.MappedDetection>> perImage = new ArrayList<>();
        List<String> imageIds = new ArrayList<>();
        List<String> logDetectJson = new ArrayList<>();

        for (int i = 0; i < stored.size(); i++) {
            StoredImage s = stored.get(i);
            VisionClient.PythonImage pyImage = response.images() == null ? null
                    : response.images().stream()
                            .filter(p -> s.imageId.equals(p.imageId())).findFirst().orElse(null);
            String imageUrl = "/" + visionProperties.getUploadDir().replace('\\', '/')
                    + "/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"))
                    + "/" + s.path.getFileName();

            if (pyImage == null || pyImage.error() != null) {
                imageResults.add(new VisionImageResult(s.imageId, imageUrl, List.of(),
                        pyImage == null ? "识别响应缺少该图" : pyImage.error()));
                perImage.add(List.of());
                imageIds.add(s.imageId);
                continue;
            }

            List<VisionAggregator.RawDetection> raws = new ArrayList<>();
            for (VisionClient.PythonDetection d : pyImage.detections() == null
                    ? List.<VisionClient.PythonDetection>of() : pyImage.detections()) {
                raws.add(VisionAggregator.toRaw(d));
            }
            List<VisionAggregator.MappedDetection> mapped =
                    VisionAggregator.mapAll(raws, labelMap, directory);

            // 4. 裁剪小图（mapped 的才裁——用户只会点它们）。失败不抛，cropUrl 留空
            List<VisionDetection> detections = new ArrayList<>();
            for (int j = 0; j < mapped.size(); j++) {
                VisionAggregator.MappedDetection d = mapped.get(j);
                String cropUrl = null;
                if (d.mapped()) {
                    cropUrl = crop(s.path, d.raw().box(), s.imageId + "_" + j);
                }
                detections.add(new VisionDetection(d.raw().label(), d.raw().confidence(),
                        cropUrl, d.mapped(), d.mapped() ? String.valueOf(d.ingredientId()) : null,
                        d.ingredientName()));
            }
            imageResults.add(new VisionImageResult(s.imageId, imageUrl, detections, null));
            perImage.add(mapped);
            imageIds.add(s.imageId);
        }

        // 5. 聚合清单
        VisionAggregator.Aggregation aggregation = VisionAggregator.aggregate(
                perImage, imageIds, directory, visionProperties.getDefaultCheckedConfidence());

        // 6. 识别记录（写库失败绝不抛，与候选食材记录同一原则）
        writeLog(userId, requestId, stored.size(), imageResults, aggregation,
                (int) (System.currentTimeMillis() - start));

        return new VisionResult(requestId, (int) (System.currentTimeMillis() - start),
                imageResults, aggregation.candidates(), aggregation.unmapped());
    }

    // ---------- 内部 ----------

    private void validate(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException("请至少上传 1 张图片");
        }
        if (files.size() > visionProperties.getMaxImages()) {
            throw new BusinessException(
                    "单次最多上传 " + visionProperties.getMaxImages() + " 张图片");
        }
        long limit = (long) visionProperties.getMaxFileSizeMb() * 1024 * 1024;
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                throw new BusinessException("有空文件，请重新选择");
            }
            if (file.getSize() > limit) {
                throw new BusinessException(
                        "单张图片不能超过 " + visionProperties.getMaxFileSizeMb() + "MB");
            }
            String type = file.getContentType();
            if (type == null || !ALLOWED_TYPES.contains(type.toLowerCase())) {
                throw new BusinessException(
                        "只支持 JPG / PNG 图片");
            }
        }
    }

    private Path save(Path dayDir, MultipartFile file) {
        try {
            Files.createDirectories(dayDir);
            Path target = dayDir.resolve(UUID.randomUUID() + ".jpg");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
            return target;
        } catch (IOException e) {
            log.error("图片落盘失败：{}", file.getOriginalFilename(), e);
            throw new BusinessException("图片保存失败，请重试");
        }
    }

    /** 裁剪检测框为小图，返回 /uploads/... 访问路径；失败返回 null（不抛） */
    private String crop(Path source, List<Integer> box, String cropName) {
        if (box == null || box.size() != 4) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                return null;
            }
            int x = clamp(box.get(0), 0, image.getWidth() - 1);
            int y = clamp(box.get(1), 0, image.getHeight() - 1);
            int w = clamp(box.get(2), 1, image.getWidth() - x);
            int h = clamp(box.get(3), 1, image.getHeight() - y);
            BufferedImage sub = image.getSubimage(x, y, w, h);
            Path cropDir = Path.of(visionProperties.getUploadDir(), visionProperties.getCropSubDir());
            Files.createDirectories(cropDir);
            Path target = cropDir.resolve(cropName + ".jpg");
            ImageIO.write(sub, "jpg", target.toFile());
            return "/" + visionProperties.getUploadDir().replace('\\', '/') + "/"
                    + visionProperties.getCropSubDir() + "/" + target.getFileName();
        } catch (Exception e) {
            // 裁剪失败只是少一张小图，不影响识别主流程
            log.warn("裁剪小图失败（{}）：{}", cropName, e.getMessage());
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Map<String, VisionLabel> loadLabelMap() {
        List<VisionLabel> rows = visionLabelMapper.selectList(new QueryWrapper<VisionLabel>()
                .eq("status", 1));
        Map<String, VisionLabel> map = new LinkedHashMap<>();
        for (VisionLabel row : rows) {
            map.put(row.getLabel(), row);
        }
        return map;
    }

    private void writeLog(Long userId, String requestId, int imageCount,
                          List<VisionImageResult> imageResults,
                          VisionAggregator.Aggregation aggregation, int costMs) {
        try {
            VisionDetectLog log = new VisionDetectLog();
            log.setRequestId(requestId);
            log.setUserId(userId);
            log.setImageCount(imageCount);
            log.setDetectJson(objectMapper.writeValueAsString(imageResults));
            List<VisionCandidate> candidates = aggregation.candidates();
            List<VisionUnmappedLabel> unmapped = aggregation.unmapped();
            log.setSelectedJson(objectMapper.writeValueAsString(
                    Map.of("candidates", candidates, "unmapped", unmapped)));
            log.setUsedForGenerate(0);
            log.setCostMs(costMs);
            visionDetectLogMapper.insert(log);
        } catch (Exception e) {
            // 记账失败绝不抛 —— 与 t_ingredient_candidate 记录点同一原则
            log.warn("识别记录写库失败（requestId={}）：{}", requestId, e.getMessage());
        }
    }

    /** 落盘后的图片 */
    private record StoredImage(String imageId, Path path, String contentType) {
    }
}
