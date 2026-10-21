package com.lanou.csh.controller;

import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.User;
import com.lanou.csh.service.AuthService;
import com.lanou.csh.service.UserService;
import com.lanou.csh.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 用户表 前端控制器
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @GetMapping("/products")
    @Operation(summary = "我发布的商品")
    public Result<PageResult<ProductVO>> getMyProducts(
            @RequestHeader("Authorization") String token,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Integer userId = authService.getCurrentUser(token).getId();
        PageResult<ProductVO> products = userService.getMyProducts(userId, pageNum, pageSize);
        return Result.success(products);
    }

    @GetMapping("/favorites")
    @Operation(summary = "我收藏的商品")
    public Result<PageResult<ProductVO>> getMyFavorites(
            @RequestHeader("Authorization") String token,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Integer userId = authService.getCurrentUser(token).getId();
        PageResult<ProductVO> favorites = userService.getMyFavorites(userId, pageNum, pageSize);
        return Result.success(favorites);
    }

    @GetMapping("/info")
    @Operation(summary = "获取当前登录用户信息")
    public Result<User> getCurrentUserInfo(@RequestHeader("Authorization") String token) {
        User user = authService.getCurrentUser(token);
        // 不要把密码哈希返回给前端
        user.setPassword(null);
        return Result.success(user);
    }

    @PutMapping("/info")
    @Operation(summary = "修改个人资料")
    public Result<User> updateUserInfo(@RequestHeader("Authorization") String token,
                                       @RequestBody User user) {
        User current = authService.getCurrentUser(token);
        // 只允许修改资料类字段,防止越权改 id / 密码
        user.setId(current.getId());
        user.setPassword(null);
        user.setUsername(null);
        User updated = userService.updateUserInfo(user);
        updated.setPassword(null);
        return Result.success(updated, "资料更新成功");
    }
}
