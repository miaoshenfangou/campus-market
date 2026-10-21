package com.lanou.csh.controller;

import com.lanou.csh.dto.LoginRequest;
import com.lanou.csh.entity.User;
import com.lanou.csh.service.UserService;
import com.lanou.csh.util.JwtUtil;
import com.lanou.csh.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证模块:注册与登录,是获取 Token 的唯一入口(对外开放,不受拦截器保护)。
 *
 * @author lanou
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "认证模块")
public class AuthController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public Result<User> register(@RequestBody User user) {
        User registered = userService.register(user);
        return Result.success(registered, "注册成功");
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录,返回 JWT Token")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        // 1. 校验用户名密码(密码用 BCrypt 比对)
        User user = userService.login(request.getUsername(), request.getPassword());

        // 2. 签发 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        // 3. 一起返回,前端把 token 存起来,后续请求放在 Authorization 头里
        Map<String, Object> data = new HashMap<>(3);
        data.put("token", token);
        data.put("tokenType", "Bearer");
        data.put("user", user);
        return Result.success(data, "登录成功");
    }
}
