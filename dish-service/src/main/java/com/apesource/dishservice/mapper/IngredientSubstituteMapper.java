package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.IngredientSubstitute;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 食材替代（t_ingredient_substitute，离线统计产物，只读） */
@Mapper
public interface IngredientSubstituteMapper extends BaseMapper<IngredientSubstitute> {
}
