package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.DishIngredient;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜品-食材明细 Mapper。
 */
@Mapper
public interface DishIngredientMapper extends BaseMapper<DishIngredient> {
}
