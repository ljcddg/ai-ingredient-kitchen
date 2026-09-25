package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.Ingredient;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 食材主数据 Mapper。
 */
@Mapper
public interface IngredientMapper extends BaseMapper<Ingredient> {
}
