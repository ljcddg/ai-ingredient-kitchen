package com.apesource.accountservice.mapper;

import com.apesource.accountservice.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper。
 * <p>
 * 继承 {@link BaseMapper} 后即拥有单表增删改查能力，无需手写 XML。
 * 只有多表关联、复杂动态条件时才需要自定义方法。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
