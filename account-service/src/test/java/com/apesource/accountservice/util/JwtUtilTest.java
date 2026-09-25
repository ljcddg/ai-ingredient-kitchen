package com.apesource.accountservice.util;

import com.apesource.commonservice.constant.AuthConstants;
import com.apesource.commonservice.util.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtUtil 单元测试。
 * <p>
 * 不启动 Spring 容器，直接 new 出 JwtUtil 验证核心逻辑，
 * 保证「生成 → 解析 → 篡改检测 → 过期检测」这条链路真的成立。
 */
class JwtUtilTest {

    /**
     * 测试专用密钥（解码后 44 字节，满足 HS256 的 32 字节下限）。
     * <p>
     * 刻意与运行配置的密钥区分开：测试不该依赖任何真实凭据，
     * 也避免真实密钥随源码进入版本库。
     */
    private static final String SECRET =
            "dW5pdF90ZXN0X29ubHlfc2VjcmV0X2tleV9mb3Jfand0X3V0aWxfdGVzdF8zMmI=";

    /** 2 小时有效期 */
    private static final long EXPIRE_MILLIS = 7_200_000L;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRE_MILLIS);

    @Test
    void 密钥解码后长度满足HS256要求() {
        byte[] keyBytes = Base64.getDecoder().decode(SECRET);
        assertTrue(keyBytes.length >= 32,
                "HS256 要求密钥至少 32 字节，当前 " + keyBytes.length + " 字节");
    }

    @Test
    void 生成的token能被正确解析出用户信息() {
        String token = jwtUtil.generateToken(1001L, "bingbao");

        assertNotNull(token);
        // JWT 固定三段，用两个点号分隔
        assertEquals(3, token.split("\\.").length);

        Claims claims = jwtUtil.parseToken(token);
        assertNotNull(claims);
        assertEquals("1001", claims.getSubject());
        assertEquals(1001L, jwtUtil.getUserId(token));
        assertEquals("bingbao", claims.get(AuthConstants.CLAIM_USERNAME, String.class));
    }

    @Test
    void 篡改payload后验签必须失败() {
        String token = jwtUtil.generateToken(1001L, "bingbao");
        String[] parts = token.split("\\.");

        // 把 payload 换成「下一个用户」的内容，模拟攻击者提权
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"9999\",\"userId\":9999,\"username\":\"hacker\"}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        // 签名对不上，必须返回 null
        assertNull(jwtUtil.parseToken(forged), "篡改后的 token 不应通过校验");
        assertFalse(jwtUtil.isValid(forged));
    }

    @Test
    void 已过期的token必须被识别为无效() {
        // 有效期设为 -1000ms，生成的 token 生成即过期
        JwtUtil expiredJwtUtil = new JwtUtil(SECRET, -1000L);
        String expiredToken = expiredJwtUtil.generateToken(1001L, "bingbao");

        assertNull(expiredJwtUtil.parseToken(expiredToken), "过期 token 不应通过校验");
    }

    @Test
    void 去掉Bearer前缀后能正确解析() {
        String token = jwtUtil.generateToken(1001L, "bingbao");

        assertEquals(token, JwtUtil.resolveToken("Bearer " + token));
        // 没有 Bearer 前缀或为空时返回 null
        assertNull(JwtUtil.resolveToken(token));
        assertNull(JwtUtil.resolveToken(null));
        assertNull(JwtUtil.resolveToken(""));
    }
}
