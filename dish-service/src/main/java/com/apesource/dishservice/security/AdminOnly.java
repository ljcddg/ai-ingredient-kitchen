package com.apesource.dishservice.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「只有管理员能调」的接口。
 *
 * <p>为什么用注解 + 拦截器，而不是在每个方法里写一行鉴权：
 * <ul>
 *   <li>新增管理接口时不容易漏 —— 漏写校验是一个方法的事，
 *       但如果校验写在方法体里，漏了不会有任何提示；</li>
 *   <li>「哪些接口是管理接口」这件事本身可被检索：
 *       全项目 grep 这个注解就能列全，写论文的权限矩阵也直接照它整理。</li>
 * </ul>
 *
 * <p>可以打在方法上，也可以打在类上（整个 Controller 都是管理接口时）。
 * 判定逻辑见 {@link AdminAuthInterceptor}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminOnly {

    /** 说明这个接口为什么需要管理员权限（写给自己和后来人看，不参与判定） */
    String value() default "";
}
