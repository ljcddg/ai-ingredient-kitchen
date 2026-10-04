package com.apesource.commonservice.constant;

/**
 * 认证相关常量。
 * <p>
 * 放在 common-service 是为了让「签发方」和「校验方」共用同一份约定。
 * <p>
 * 这是本项目的硬约束：<b>同一个字符串如果同时出现在两个服务里，就必须提到公共模块</b>。
 * 否则一旦哪天把 {@code userId} 改成 {@code uid}，签发方改了、校验方没改，
 * 表现就是「登录成功但所有需要登录的接口都 401」，排查起来极其痛苦。
 */
public final class AuthConstants {

    private AuthConstants() {
    }

    /** JWT 的密钥配置项前缀，签发方与校验方读的是同一个键 */
    public static final String JWT_SECRET_PROPERTY = "jwt.secret";

    /** token 有效期配置项，带上默认值兜底，避免 Nacos 拉不到配置时启动失败 */
    public static final String JWT_EXPIRE_PROPERTY = "jwt.expire-millis:7200000";

    /** Payload 中存放用户 ID 的自定义 key */
    public static final String CLAIM_USER_ID = "userId";

    /** Payload 中存放用户名的自定义 key */
    public static final String CLAIM_USERNAME = "username";

    /** HTTP 请求头名字，网关注入下游时用 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** 网关透传给下游服务的「用户 ID」请求头 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 网关透传给下游服务的「用户名」请求头 */
    public static final String HEADER_USERNAME = "X-Username";

    /**
     * 角色：普通用户。
     * <p>
     * 与 {@code t_user.role} 的取值一一对应。之所以也放在公共模块，
     * 理由和 claim key 完全一样：写角色的地方（account-service 注册时给默认值）
     * 与读角色的地方（dish-service 鉴权、前端按角色显示管理入口）
     * 必须共用同一套常量，否则哪天把管理员改成 2，改一边漏一边，
     * 表现就是「明明设成管理员了，管理接口还是 403」。
     */
    public static final int ROLE_USER = 0;

    /** 角色：管理员。索引重建、食材转正、内容下架等运维接口要求这个角色 */
    public static final int ROLE_ADMIN = 1;

    /** token 前缀，HTTP 规范要求写成 {@code Authorization: Bearer <token>} */
    public static final String TOKEN_PREFIX = "Bearer ";
}
