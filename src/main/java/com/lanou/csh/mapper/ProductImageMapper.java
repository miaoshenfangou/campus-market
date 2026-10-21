package com.lanou.csh.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanou.csh.entity.ProductImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品图片 Mapper。
 * <p>
 * 字段名统一使用 {@code image_url} / {@code sort_order},与建表脚本保持一致。
 *
 * @author lanou
 */
@Mapper
public interface ProductImageMapper extends BaseMapper<ProductImage> {

    /**
     * 查询某个商品的全部图片,按排序号升序。
     */
    @Select("SELECT image_url FROM product_image WHERE product_id = #{productId} " +
            "ORDER BY sort_order ASC, id ASC")
    List<String> selectImagesByProductId(@Param("productId") Long productId);

    /**
     * 查询某个商品的主图(sort_order 最小的那张),商品无图时返回 null。
     */
    @Select("SELECT image_url FROM product_image WHERE product_id = #{productId} " +
            "ORDER BY sort_order ASC, id ASC LIMIT 1")
    String selectMainImage(@Param("productId") Long productId);

    /**
     * 查询某个商品当前最大的排序号;没有图片时返回 -1。
     * <p>
     * 追加图片时从 {@code max + 1} 开始编号,避免新图把原有主图顶掉。
     */
    @Select("SELECT COALESCE(MAX(sort_order), -1) FROM product_image WHERE product_id = #{productId}")
    Integer selectMaxSortOrder(@Param("productId") Long productId);

    /**
     * 批量查询多个商品的主图,用于列表页避免 N+1 查询。
     * <p>
     * 用「按商品分组取 sort_order 最小值」的子查询定位每个商品的主图。
     */
    @Select("<script>" +
            "SELECT pi.product_id AS productId, pi.image_url AS imageUrl FROM product_image pi " +
            "INNER JOIN (" +
            "  SELECT product_id, MIN(sort_order) AS min_sort FROM product_image " +
            "  WHERE product_id IN " +
            "  <foreach collection='productIds' item='pid' open='(' separator=',' close=')'>#{pid}</foreach>" +
            "  GROUP BY product_id" +
            ") t ON pi.product_id = t.product_id AND pi.sort_order = t.min_sort " +
            "</script>")
    List<ProductImage> selectMainImagesByProductIds(@Param("productIds") List<Long> productIds);
}
