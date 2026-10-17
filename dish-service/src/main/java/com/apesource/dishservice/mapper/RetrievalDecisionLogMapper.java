package com.apesource.dishservice.mapper;

import com.apesource.dishservice.entity.RetrievalDecisionLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 检索决策日志（t_retrieval_decision） */
@Mapper
public interface RetrievalDecisionLogMapper extends BaseMapper<RetrievalDecisionLog> {
}
