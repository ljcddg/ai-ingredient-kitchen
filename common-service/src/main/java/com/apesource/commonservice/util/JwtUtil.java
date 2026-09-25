package com.apesource.commonservice.util;

import com.apesource.commonservice.constant.AuthConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 工具类（基于 jjwt 0.12.x），<b>纯静态工具</b>，不依赖 Spring 容器。
 * <p>
 * 之所以从 account-service 提到 common-service：
 * <ul>
 *   <li>签发方（account-service 登录时签 token）和校验方（gateway 验 token）
 *       必须用<b>完全一致的算法、密钥、claim key</b>，稍有偏差就是「登录成功但接口 401」；</li>
 *   <li>gateway 是 WebFlux 体系，引入 Spring 组件要额外注意线程模型，
 *       用静态工具 + 自己在配置类里创建实例最干净。</li>
 * </ul>
 * <p>
 * 与网上大量 0.9.x 老教程的关键差异：
 * <ul>
 *   <li>生成：{@code Jwts.builder().subject(...).signWith(key)}，0.12 取消了 {@code setSubject} 这类 setter 命名</li>
 *   <li>解析：{@code Jwts.parser().verifyWith(key).build().parseSignedClaims(token)}，
 *       0.12 用 {@code Jwts.parser()} 取代了 {@code parserBuilder()}，
 *       且 {@code parseClaimsJws} 改名 {@code parseSignedClaims}</li>
 *   <li>密钥：必须 {@code SecretKey}，HS256 要求至少 256 bit（32 字节）</li>
 * </ul>
 */
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /** 签名密钥 */
    private final SecretKey secretKey;

    /** 过期时间，单位毫秒 */
    private final long expireMillis;

    /**
     * @param secret       Base64 编码的密钥，解码后必须 ≥ 32 字节，否则 HS256 抛 WeakKeyException
     * @param expireMillis token 有效期（毫秒）
     */
    public JwtUtil(String secret, long expireMillis) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("jwt.secret 未配置，无法生成/校验 token");
        }
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expireMillis = expireMillis;
    }

    /**
     * 生成 token。
     *
     * @param userId   用户主键
     * @param username 用户名
     * @return 签名后的 JWT 字符串
     */
    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expireMillis);
        return Jwts.builder()
                // subject 放用户唯一标识，是 JWT 规范里预留给「主体」的字段
                .subject(String.valueOf(userId))
                .claim(AuthConstants.CLAIM_USER_ID, userId)
                .claim(AuthConstants.CLAIM_USERNAME, username)
                .issuedAt(now)
                .expiration(expireAt)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验 token。
     * <p>
     * 签名不合法、被篡改、已过期都会抛异常，这里统一捕获返回 null，
     * 让调用方用「非空判断」处理失败，避免异常层层向上传。
     * <p>
     * 注意：本方法内部对异常只打 warn 日志，<b>不把异常细节抛给前端</b>，
     * 防止攻击者通过错误信息区分「签名错」和「已过期」来试探系统。
     *
     * @param token JWT 字符串
     * @return 解析出的 Claims，失败返回 null
     */
    public Claims parseToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 过期 ExpiredJwtException、篡改 SignatureException 都会走到这
            log.warn("JWT 校验失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 token 中取用户 ID，失败返回 null。
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        // claim 里的数字经 Jackson 反序列化可能是 Integer，转成 Long 更稳妥
        Object raw = claims.get(AuthConstants.CLAIM_USER_ID);
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 从 token 中取用户名，失败返回 null。
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        return claims == null ? null : claims.get(AuthConstants.CLAIM_USERNAME, String.class);
    }

    /**
     * 校验 token 是否有效（签名正确且未过期）。
     */
    public boolean isValid(String token) {
        return parseToken(token) != null;
    }

    /**
     * 剥离请求头里的 {@code Bearer } 前缀。
     * <p>
     * HTTP 规范要求 Authorization 头写成 {@code Bearer <token>}，
     * 解析前必须去掉前缀，否则签名校验一定失败。
     *
     * @param authorizationHeader 原始请求头值
     * @return 纯 token，无法识别时返回 null
     */
    public static String resolveToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        if (authorizationHeader.startsWith(AuthConstants.TOKEN_PREFIX)) {
            return authorizationHeader.substring(AuthConstants.TOKEN_PREFIX.length()).trim();
        }
        return null;
    }
}
