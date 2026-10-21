package com.lanou.csh.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类:负责 Token 的签发与解析。
 * <p>
 * 采用 HS256 对称签名,密钥与过期时间从 application.yml 的 {@code jwt.*} 读取。
 * Token 的 payload 中只放 userId 与 username 这类非敏感标识,
 * 不放入密码、手机号等隐私字段(避免 Base64 解码后被直接读取)。
 *
 * @author lanou
 */
@Component
public class JwtUtil {

    /** 签名密钥,长度需 >= 256 bit(32 字节)才能满足 HS256 要求 */
    @Value("${jwt.secret}")
    private String secret;

    /** 过期时间,单位:秒 */
    @Value("${jwt.expiration}")
    private Long expiration;

    /** 签发者 */
    @Value("${jwt.issuer}")
    private String issuer;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Token。
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @return 签好名的 JWT 字符串
     */
    public String generateToken(Integer userId, String username) {
        Map<String, Object> claims = new HashMap<>(2);
        claims.put("userId", userId);
        claims.put("username", username);

        Date now = new Date();
        Date expireAt = new Date(now.getTime() + expiration * 1000);

        return Jwts.builder()
                .setClaims(claims)
                .setIssuer(issuer)
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(expireAt)
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 解析 Token 拿到 Claims。签名错误、格式错误或已过期都会抛出 {@code JwtException}。
     */
    public Claims parseToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .requireIssuer(issuer)
                .build()
                .parseClaimsJws(stripPrefix(token))
                .getBody();
    }

    /**
     * 从 Token 中取 userId,失败返回 null(由调用方决定抛业务异常还是放行)。
     */
    public Integer getUserId(String token) {
        try {
            Object userId = parseToken(token).get("userId");
            return userId == null ? null : Integer.valueOf(userId.toString());
        } catch (Exception e) {
            return null;
        }
    }

    public String getUsername(String token) {
        try {
            Object username = parseToken(token).get("username");
            return username == null ? null : username.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 兼容前端传来的 {@code "Bearer xxx"} 与裸 token 两种写法。
     */
    public String stripPrefix(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        if (t.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return t.substring(7).trim();
        }
        return t;
    }
}
