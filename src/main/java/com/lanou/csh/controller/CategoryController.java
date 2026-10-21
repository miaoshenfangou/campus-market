package com.lanou.csh.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lanou.csh.entity.Category;
import com.lanou.csh.service.CategoryService;
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
 * 商品分类表 前端控制器
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@RestController
@RequestMapping("/category")
@Tag(name = "分类模块")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 分类列表:按 sort 升序返回,供前端筛选栏使用。
     */
    @GetMapping("/list")
    @Operation(summary = "分类列表")
    public Result<List<Category>> listCategories() {
        List<Category> list = categoryService.list(
                new QueryWrapper<Category>().orderByAsc("sort")
        );
        return Result.success(list);
    }
}
