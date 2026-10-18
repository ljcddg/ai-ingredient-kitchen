package com.apesource.dishservice.dto;

import lombok.Data;

import java.util.List;

/**
 * 极简分页结果。
 *
 * <p>没有直接用 MyBatis-Plus 的 {@code IPage}：那个对象带一堆
 * 前端用不到的字段（pages / orders / optimizeCountSql / searchCount…），
 * 序列化出去会让接口文档变脏，也会让「这个字段到底有没有用」变成考古题。
 *
 * @param <T> 行类型
 */
@Data
public class PageResult<T> {

    private List<T> records;

    /** 满足条件的总条数（不是当前页条数） */
    private long total;

    private int page;

    private int size;

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPage(page);
        result.setSize(size);
        return result;
    }
}
