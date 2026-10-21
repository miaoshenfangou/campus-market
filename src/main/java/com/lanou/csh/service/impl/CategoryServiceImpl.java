package com.lanou.csh.service.impl;

import com.lanou.csh.entity.Category;
import com.lanou.csh.mapper.CategoryMapper;
import com.lanou.csh.service.CategoryService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 商品分类表 服务实现类
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

}
