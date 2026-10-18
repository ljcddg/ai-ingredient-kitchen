package com.apesource.dishservice.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违规词（{@code t_sensitive_word}）。
 *
 * <p>词库是全量载入内存的（见 {@code SensitiveWordService}），所以这张表只在
 * <b>启动</b>和<b>增删改之后</b>被读。别在评论发布的链路上查它 ——
 * 每发一条评论查一次库，等于把内存缓存的收益又还回去了。
 */
@Data
@TableName("t_sensitive_word")
public class SensitiveWord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 违规词。英文按小写入库，匹配时统一小写比较 */
    private String word;

    /**
     * 类别：{@code AD} 广告导流 / {@code PORN} 色情低俗 / {@code VIOLENCE} 暴恐 /
     * {@code ILLEGAL} 涉枪涉爆等违法 / {@code ABUSE} 辱骂攻击 / {@code OTHER} 其他。
     *
     * <p>用字符串不用数字：类别会被运营扩展，数字枚举每加一类都要改前端映射与注释。
     */
    private String category;

    /** 处置级别：1-替换为星号，2-整条拦截待审 */
    private Integer level;

    /** 是否启用：0-停用，1-启用。停用而不是删除，是为了保留历史命中解释 */
    private Integer enabled;

    /** 来源：{@code manual} 人工录入，{@code lexicon:<文件名>} 批量导入 */
    private String source;

    private String remark;

    /** 累计命中次数，管理页按它排序能看出哪些词最常被触发 */
    private Integer hitCount;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
