package com.lanou.csh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lanou.csh.dto.ProductQuery;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 商品表 Mapper 接口
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 商品列表复合查询(关键词 + 分类 + 是否已收藏),SQL 见 ProductMapper.xml。
     * <p>
     * 第一个参数是分页对象,由 MyBatis-Plus 分页插件自动改写 SQL 追加 LIMIT,
     * 因此 XML 里不需要(也不应该)手写 LIMIT。
     *
     * @param page  分页参数,调用时传入 {@code new Page<>(pageNum, pageSize)}
     * @param query 查询条件,{@code loginUserId} 为空表示游客
     */
    IPage<ProductVO> selectProductList(IPage<ProductVO> page, @Param("query") ProductQuery query);
}
