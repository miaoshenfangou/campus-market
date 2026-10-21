package com.lanou.csh.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品列表项视图对象。
 * <p>
 * 列表页只需要卡片展示所需的字段,不需要把商品描述等大字段带出来,
 * 因此单独用 VO 承载「商品 + 分类名 + 卖家 + 主图 + 是否已收藏」的组合结果。
 *
 * @author lanou
 */
@Data
public class ProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private BigDecimal price;

    private BigDecimal originalPrice;

    /** 0-待审核 1-已上架 2-已下架 3-已售出 */
    private Integer status;

    private Integer viewCount;

    private LocalDateTime createTime;

    /* ---------- 关联字段(由 SQL JOIN 或业务层补充) ---------- */

    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    private Long userId;

    /** 卖家用户名 */
    private String username;

    /** 卖家头像 */
    private String avatar;

    /** 商品主图 URL */
    private String mainImage;

    /** 当前登录用户是否已收藏该商品 */
    private Boolean isFavorite;

    /** 审核备注:驳回原因,「我发布的」里直接展示 */
    private String auditRemark;
}
