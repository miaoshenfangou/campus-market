package com.lanou.csh.config;

import com.lanou.csh.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 校验拦截器。
 * <p>
 * 无状态鉴权方案:不依赖 Session,每次请求都从 Authorization 头取 Token 校验。
 * 校验通过后把 userId / username 放进 request attribute,
 * 供后续 Controller 通过 {@code @RequestAttribute} 直接取用,避免重复解析。
 * <p>
 * 只做「是否携带合法 Token」的粗粒度判断;具体到某个接口要求登录的精确控制
 * 由 {@link WebMvcConfig} 的路径注册规则完成。
 *
 * @author lanou
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行预检请求,否则跨域场景下浏览器拿不到 OPTIONS 的响应
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");
        Integer userId = jwtUtil.getUserId(token);
        if (userId == null) {
            writeUnauthorized(response);
            return false;
        }

        request.setAttribute("currentUserId", userId);
        request.setAttribute("currentUsername", jwtUtil.getUsername(token));
        return true;
    }

    /**
     * 未通过校验时返回统一的 JSON 结构(而非重定向到登录页),
     * 前端据此判断需要跳转登录。
     */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":401,\"message\":\"未登录或登录状态已失效,请重新登录\",\"data\":null}"
        );
    }
}
