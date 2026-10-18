package com.apesource.dishservice.moderation;

import java.util.List;

/**
 * 一次违规词扫描的结果。
 *
 * @param text    替换后的文本（无命中时与原文相同）
 * @param hits    命中的词，去重且保持出现顺序（只有管理员看得到，用户只看星号）
 * @param blocked 是否命中「整条拦截」级别的词 —— 这类内容不进详情页，转待审核
 */
public record ModerationResult(String text, List<String> hits, boolean blocked) {

    public static ModerationResult clean(String text) {
        return new ModerationResult(text, List.of(), false);
    }

    /** 是否有词被替换（用于前端提示「部分内容已屏蔽」） */
    public boolean masked() {
        return !hits.isEmpty();
    }
}
