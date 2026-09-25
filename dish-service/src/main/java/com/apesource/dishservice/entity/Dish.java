package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.apesource.dishservice.config.DishStepListTypeHandler;
import com.apesource.dishservice.dto.DishStep;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜品表（{@code t_dish}）。
 *
 * <p><b>草稿态与已发布态共表</b>，用 {@code status} 区分。这意味着：
 * 「我的菜品」和「推荐池」查的是同一张表，只是条件不同。
 * 代价是<b>所有面向推荐池的查询都必须带 {@code status = 1}</b>，这个条件不许漏。
 *
 * <p>四个 JSON 字段（seasonings / equipment / chefTips / substitutes）都是
 * "简单字符串列表"，用 {@link JacksonTypeHandler} 自动转换 ——
 * 它们不参与召回、不参与计算，不值得各开一张表。
 * 注意必须配 {@code autoResultMap = true}，否则查询时 typeHandler 不生效、
 * 读出来是 null（这个坑很安静，不会报错）。
 */
@Data
@TableName(value = "t_dish", autoResultMap = true)
public class Dish {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 菜名归一化键，用于重复检测（同一创建者不重复建同名菜） */
    private String nameKey;

    private String coverUrl;

    /** 来源：1-官方精选，2-AI生成，3-用户自定义 */
    private Integer source;

    /** 创建者（官方精选为 NULL） */
    private Long creatorId;

    /** 状态：0-草稿(仅创建者可见)，1-已发布，2-审核拒绝，3-已下架 */
    private Integer status;

    /** 基准份量（人份），明细用量以此为准 */
    private Integer baseServing;

    /** 口味标签冗余（展示用） */
    private String tasteTag;

    private String summary;

    /** 难度 1-3（与 AI 提示词对齐） */
    private Integer difficulty;

    private Integer prepTimeMin;

    private Integer cookTimeMin;

    /** 辣度 0-3 */
    private Integer spicyLevel;

    /**
     * 做法步骤：[{"no":1,"text":"..."}]
     *
     * <p><b>这里必须用自建的 {@link DishStepListTypeHandler}，不能用 MP 自带的
     * {@code JacksonTypeHandler}。</b>后者因为泛型擦除，读出来的是
     * {@code List<LinkedHashMap>} —— 字段声明看着是 {@code List<DishStep>}、
     * 编译也过得去，但一旦对元素调用 {@code getText()} 就抛 ClassCastException。
     * 更坑的是它会被"接口能正常返回"骗过去：LinkedHashMap 序列化出来的 JSON
     * 和 DishStep 长得一样。详细原因见那个类的注释。
     *
     * <p>同一个实体上另外四个 {@code List<String>} 字段不受影响（字符串元素不会退化），
     * 所以它们继续用 MP 自带的处理器。
     */
    @TableField(typeHandler = DishStepListTypeHandler.class)
    private List<DishStep> stepJson;

    /** 调料名列表，用量由系统按标准配方补 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> seasonings;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> equipment;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> chefTips;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> substitutes;

    // ---- 营养（每人份）：主力是 AI 估算，本地计算做后台校验 ----

    private BigDecimal energyKcal;

    private BigDecimal proteinG;

    private BigDecimal fatG;

    private BigDecimal carbG;

    private BigDecimal fiberG;

    private BigDecimal sodiumMg;

    /** 营养来源：0-没有，1-AI估算(仅供参考)，2-本地精确计算 */
    private Integer nutritionSource;

    /** 是否通过本地校验（与本地算的值偏差 < 40%）：0-否，1-是 */
    private Integer nutritionVerified;

    // ---- 评分与热度（冗余字段）----

    private BigDecimal ratingAvg;

    private Integer ratingCount;

    private BigDecimal ratingWilson;

    private Integer collectCount;

    private Integer viewCount;

    private LocalDateTime publishTime;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
