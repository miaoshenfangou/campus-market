package com.lanou.csh.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lanou.csh.entity.ProductImage;
import com.lanou.csh.mapper.ProductImageMapper;
import com.lanou.csh.service.ProductImageService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 商品图片表 服务实现类
 * </p>
 * <p>
 * 图片「存盘 + 落库」的流程统一由 {@code ProductServiceImpl#uploadProductImages} 编排,
 * 本类只提供基础 CRUD 能力(继承自 ServiceImpl),不再重复实现业务方法 ——
 * 原实现里误放了一份 publishProduct,且引用了未注入的 productMapper,已移除。
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Service
public class ProductImageServiceImpl extends ServiceImpl<ProductImageMapper, ProductImage> implements ProductImageService {

}
