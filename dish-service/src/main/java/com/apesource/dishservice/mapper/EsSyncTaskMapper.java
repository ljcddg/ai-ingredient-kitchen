package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.EsSyncTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * ES 同步待办 Mapper。
 */
@Mapper
public interface EsSyncTaskMapper extends BaseMapper<EsSyncTask> {
}
