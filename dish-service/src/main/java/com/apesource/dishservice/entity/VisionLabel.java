package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * YOLO 类别 → 业务主数据的映射配置（{@code t_vision_label}）。
 *
 * <p>为什么需要这张表：模型输出的是训练时的类别名。COCO 预训练权重输出英文名
 * （apple），库内是中文食材（苹果），对不上；自训 89 类食材模型输出中文名，
 * 走 IngredientDirectory 别名归一即可。一张显式映射表把两种来源都罩住：
 * <ol>
 *   <li>先查本表（label 精确命中）；</li>
 *   <li>未命中再走 {@code IngredientDirectory.resolveId()}；</li>
 *   <li>都不中进 unmappedLabels 明示返回。</li>
 * </ol>
 *
 * <p>新增主数据归 Java 管（ai-service 不连业务库、不认识任何主数据）。
 * 设计文档：docs/yolo-vision-design.md §6.3。
 */
@Data
@TableName("t_vision_label")
public class VisionLabel {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** YOLO 模型输出的类别名（COCO 英文名或训练后中文名），唯一 */
    private String label;

    /** 类型：ingredient=食材（MVP）/ dish=菜品（预留） */
    private String labelType;

    /** 引用 t_ingredient.id（ingredient 型）；dish 型将来引用 t_dish.id */
    private Long refId;

    /** 低于此置信度的检出直接丢弃（按食材分别配，比全局 conf 更细） */
    private BigDecimal minConfidence;

    /**
     * 默认是否勾选。**预留字段**：当前实现用全局阈值
     * （{@code ai.vision.default-checked-confidence}）决定默认勾选；
     * 将来要对单个 label 细控（某类识别率低、希望用户手动确认）时启用本字段。
     */
    private Integer defaultChecked;

    /** 状态：0-停用，1-启用 */
    private Integer status;

    /** 备注（如"COCO 容器类，不映射食材"） */
    private String remark;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
