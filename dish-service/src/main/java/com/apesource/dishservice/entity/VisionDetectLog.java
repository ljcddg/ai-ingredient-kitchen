package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 识菜识别记录（{@code t_vision_detect_log}）。
 *
 * <p>价值不只是审计：**用户勾选结果与识别结果不一致的记录 = 后续模型训练的难例数据**
 * （用户纠正"这不是土豆是洋葱"）。斌宝 2026-10-16 确认建表。
 * 写库失败绝不抛异常影响识别主流程（与 t_ingredient_candidate 同一原则）。
 */
@Data
@TableName("t_vision_detect_log")
public class VisionDetectLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 一次识别一个 requestId（与 Python 侧、响应体一致，方便对账） */
    private String requestId;

    /** 发起用户（网关注入的 X-User-Id） */
    private Long userId;

    /** 图片张数 */
    private Integer imageCount;

    /** 检出明细 JSON：label + confidence + box + 映射结果（Python 原始输出 + Java 映射后） */
    private String detectJson;

    /** 用户勾选后的食材 JSON（确认生成时回写；仅勾选未确认时为空） */
    private String selectedJson;

    /** 是否最终带入生成：0-否，1-是 */
    private Integer usedForGenerate;

    /** 总耗时（毫秒，含 Python 推理 + Java 编排） */
    private Integer costMs;

    /** 失败原因（正常为 null） */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
