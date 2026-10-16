package com.apesource.dishservice.service;

import com.apesource.dishservice.ai.VisionClient;
import com.apesource.dishservice.ai.VisionProperties;
import com.apesource.dishservice.dto.VisionResult;
import com.apesource.dishservice.dto.VisionUnmappedLabel;
import com.apesource.dishservice.entity.VisionLabel;
import com.apesource.dishservice.mapper.VisionDetectLogMapper;
import com.apesource.dishservice.mapper.VisionLabelMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VisionService 的 IO 流程测试：落盘 → 调 Python（打桩）→ 映射 → 裁剪 → 组装结果 → 写记录。
 *
 * <p>为什么单独测这一层：{@link VisionAggregatorTest} 覆盖的是纯逻辑，
 * 而落盘/裁剪/imageUrl 拼接这段最容易犯低级错误（Path 拼接、ImageIO 用法、
 * 目录不存在、百分号/反斜杠混用），却又不在纯逻辑测试的射程内。
 *
 * <p>打桩策略：只桩 {@link VisionClient} 与两个 mapper（不碰网络、不碰数据库），
 * 落盘与裁剪**走真实实现**（写到 target/ 下的临时目录，随 clean 消失，不污染仓库）。
 */
class VisionServiceIoTest {

    private static final String UPLOAD_DIR = "target/test-vision-uploads";

    private VisionClient visionClient;
    private VisionLabelMapper labelMapper;
    private VisionDetectLogMapper logMapper;
    private IngredientDirectory directory;
    private VisionService service;

    @BeforeEach
    void setUp() throws Exception {
        // 用例之间必须隔离：落盘目录是共享的，不清空的话"文件数"断言会累加上一个用例的产物
        Path uploadRoot = Path.of(UPLOAD_DIR);
        if (Files.exists(uploadRoot)) {
            try (Stream<Path> walk = Files.walk(uploadRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }

        visionClient = mock(VisionClient.class);
        labelMapper = mock(VisionLabelMapper.class);
        logMapper = mock(VisionDetectLogMapper.class);
        directory = mock(IngredientDirectory.class);

        when(visionClient.newRequestId()).thenReturn("vision-test-1");
        // 默认桩放前面、具体桩放后面：Mockito 是"后定义的匹配优先"
        when(directory.nameOf(anyLong())).thenReturn(null);
        when(directory.nameOf(113L)).thenReturn("胡萝卜");
        // 认不出的必须显式返 null：Mockito 对 Long 返回 0L，会让"表外标签"变成假 ID
        when(directory.resolveId(anyString())).thenReturn(null);

        VisionProperties properties = new VisionProperties();
        properties.setUploadDir(UPLOAD_DIR);
        properties.setMaxImages(9);
        properties.setMaxFileSizeMb(10);

        service = new VisionService(visionClient, properties, labelMapper, logMapper,
                directory, new ObjectMapper());
    }

    @Test
    @DisplayName("落盘 + 裁剪 + imageUrl：一次识别产出原图、裁剪小图与候选清单")
    void writesOriginalAndCrops() throws Exception {
        // 表里配了 carrot -> 113（胡萝卜），模拟 COCO 权重那两条初始映射
        VisionLabel carrot = new VisionLabel();
        carrot.setLabel("carrot");
        carrot.setLabelType("ingredient");
        carrot.setRefId(113L);
        carrot.setMinConfidence(BigDecimal.valueOf(0.4));
        when(labelMapper.selectList(any())).thenReturn(List.of(carrot));

        when(visionClient.detect(any(), anyString())).thenReturn(new VisionClient.PythonVisionResponse(
                true, null, null, 12, "yolov8n.pt", "cuda:0",
                List.of(new VisionClient.PythonImage("img-1", List.of(
                        // 检出一个能映射的（胡萝卜）与一个映射不上的（bowl）
                        new VisionClient.PythonDetection("carrot", 0.91, List.of(10, 10, 40, 40)),
                        new VisionClient.PythonDetection("bowl", 0.83, List.of(0, 0, 60, 60))
                ), null))));

        VisionResult result = service.detect(7L, List.<MultipartFile>of(jpeg("dish.jpg")));

        // 1) 原图落盘（文件名是 UUID，纯 ASCII）
        Path visionDir = Path.of(UPLOAD_DIR, java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM")));
        try (Stream<Path> files = Files.list(visionDir)) {
            assertThat(files.filter(Files::isRegularFile).toList()).hasSize(1);
        }

        // 2) imageUrl 是相对业务路径（前端用 serviceUrl('dish', url) 拼）
        assertThat(result.images()).hasSize(1);
        assertThat(result.images().get(0).imageUrl())
                .startsWith("/" + UPLOAD_DIR + "/")
                .endsWith(".jpg");

        // 3) 裁剪小图只给"能映射上"的检出（bowl 不裁）
        assertThat(result.images().get(0).detections()).hasSize(2);
        String cropUrl = result.images().get(0).detections().get(0).cropUrl();
        assertThat(cropUrl).isEqualTo("/" + UPLOAD_DIR + "/crops/img-1_0.jpg");
        assertThat(Path.of(UPLOAD_DIR, "crops", "img-1_0.jpg")).exists();
        assertThat(Path.of(UPLOAD_DIR, "crops", "img-1_1.jpg")).doesNotExist();

        // 4) 候选只有映射成功的胡萝卜；bowl 进 unmapped
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).name()).isEqualTo("胡萝卜");
        assertThat(result.candidates().get(0).checked()).isTrue();   // 0.91 ≥ 0.8
        assertThat(result.unmappedLabels().stream().map(VisionUnmappedLabel::label).toList())
                .containsExactly("bowl");
    }

    @Test
    @DisplayName("单张坏图只让那张带 error，不拖垮同批其他图")
    void oneBadImageDoesNotFailBatch() throws Exception {
        when(labelMapper.selectList(any())).thenReturn(List.of());
        when(visionClient.detect(any(), anyString())).thenReturn(new VisionClient.PythonVisionResponse(
                true, null, null, 9, "yolov8n.pt", "cpu",
                List.of(new VisionClient.PythonImage("img-1", List.of(), null),
                        new VisionClient.PythonImage("img-2", null, "图片读取失败"))));

        VisionResult result = service.detect(7L,
                List.<MultipartFile>of(jpeg("a.jpg"), jpeg("b.jpg")));

        assertThat(result.images()).hasSize(2);
        assertThat(result.images().get(0).error()).isNull();
        assertThat(result.images().get(1).error()).contains("读取失败");
    }

    @Test
    @DisplayName("Python 侧 ok=false 时抛业务异常（前端据此回退手动选食材）")
    void pythonFailureThrowsBusinessException() throws Exception {
        when(visionClient.detect(any(), anyString())).thenReturn(
                new VisionClient.PythonVisionResponse(false, "model_not_loaded", "权重不存在", 0, null, null, null));
        when(labelMapper.selectList(any())).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.detect(7L, List.<MultipartFile>of(jpeg("a.jpg"))))
                .hasMessageContaining("识别服务暂不可用");
    }

    // ---------- 工具 ----------

    /** 造一张真的 JPEG（不是随便几个字节，否则 ImageIO.read 拿不到尺寸，裁剪会失败） */
    private static MockMultipartFile jpeg(String name) throws Exception {
        BufferedImage image = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 120, 90);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new MockMultipartFile("files", name, "image/jpeg", out.toByteArray());
    }
}
