package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.IngredientCandidate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食材候选表 Mapper。
 *
 * <p>没有自定义 SQL —— 计数与转正都能用 MyBatis-Plus 的 Wrapper 表达，
 * 手写 XML 只会增加维护面。
 */
@Mapper
public interface IngredientCandidateMapper extends BaseMapper<IngredientCandidate> {
}
