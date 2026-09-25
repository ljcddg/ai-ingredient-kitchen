package com.apesource.dishservice.dto;

import lombok.Data;

/**
 * 发表评论的请求体。
 *
 * <p>{@code rating} 是可选的，带上只是让这条评论旁边显示几颗星；
 * <b>计分不认它</b>，计分只认 {@code t_dish_rating}。这个边界如果含糊了，
 * 就会出现"评论区显示的分数和菜品总分对不上"这种没人说得清的问题。
 */
@Data
public class CommentRequest {

    private String content;

    /** 可选的展示用分数 1-5 */
    private Integer rating;
}
