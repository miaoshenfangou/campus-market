package com.lanou.csh.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置。
 * <p>
 * 鉴权方案:JWT 无状态鉴权,由 {@link JwtInterceptor} 负责 Token 校验。
 * 因此这里需要做两件事:
 * <ol>
 *   <li>关闭 Spring Security 自带的表单登录 / HTTP Basic / CSRF,
 *       否则它会生成随机密码并拦截所有接口,导致前端拿不到 JSON 而是登录页;</li>
 *   <li>把所有请求放行(permitAll),把访问控制的职责统一交给 JWT 拦截器,
 *       避免两套鉴权逻辑互相打架。</li>
 * </ol>
 * Spring Security 在本项目中主要提供两个能力:
 * BCrypt 密码编码器,以及后续可扩展的方法级权限注解(@PreAuthorize)。
 *
 * @author lanou
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 密码编码器:BCrypt 自带随机盐,同一明文每次加密结果不同,
     * 因此数据库不需要额外存 salt 字段,也天然抵御彩虹表攻击。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + JWT,不需要 CSRF token
                .csrf(csrf -> csrf.disable())
                // 无状态:不创建也不使用 HttpSession
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 关闭表单登录与 HTTP Basic 的默认行为
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                // 访问控制交给 JwtInterceptor 统一处理
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }
}
