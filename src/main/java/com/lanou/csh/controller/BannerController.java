package com.lanou.csh.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lanou.csh.entity.Banner;
import com.lanou.csh.service.BannerService;
import com.lanou.csh.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 * 轮播图表 前端控制器
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@RestController
@RequestMapping("/banner")
@Tag(name = "轮播图模块")
public class BannerController {

    @Autowired
    private BannerService bannerService;

    /**
     * 首页轮播图:只返回启用状态(1)的,按 sort 倒序(权重大的靠前)。
     */
    @GetMapping("/list")
    @Operation(summary = "轮播图列表")
    public Result<List<Banner>> listBanners() {
        List<Banner> list = bannerService.list(
                new QueryWrapper<Banner>()
                        .eq("status", 1)
                        .orderByDesc("sort")
        );
        return Result.success(list);
    }
}
