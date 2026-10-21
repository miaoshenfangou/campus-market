package com.lanou.csh.controller;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lanou.csh.entity.Favorite;
import com.lanou.csh.service.AuthService;
import com.lanou.csh.service.FavoriteService;
import com.lanou.csh.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/favorites")
@Tag(name = "收藏模块")
public class FavoriteController {
    @Autowired
    private FavoriteService favoriteService;
    @Autowired
    private AuthService authService;

    // 收藏商品
    @PostMapping("/{productId}")
    @Operation(summary = "收藏商品")
    public Result<String> addFav(@RequestHeader("Authorization") String token,
                                 @PathVariable Long productId){
        Integer userId = authService.getCurrentUser(token).getId();
        favoriteService.addFavorite(userId, productId);
        return Result.success("收藏成功");
    }

    // 取消收藏
    @DeleteMapping("/{productId}")
    @Operation(summary = "取消收藏")
    public Result<String> delFav(@RequestHeader("Authorization") String token,
                                 @PathVariable Long productId){
        Integer userId = authService.getCurrentUser(token).getId();
        favoriteService.removeFavorite(userId, productId);
        return Result.success("取消收藏成功");
    }

    // 判断当前用户是否收藏该商品（详情页展示收藏状态）
    @GetMapping("/{productId}")
    @Operation(summary = "判断是否已收藏")
    public Result<Boolean> checkFav(@RequestHeader("Authorization") String token,
                                    @PathVariable Long productId){
        Integer userId = authService.getCurrentUser(token).getId();
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Favorite::getUserId, userId).eq(Favorite::getProductId, productId);
        // 用 count() 判断存在性,比 exists() 更通用(count 在 ServiceImpl 上一定有)
        long count = favoriteService.count(wrapper);
        return Result.success(count > 0);
    }
}