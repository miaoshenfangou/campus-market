package com.lanou.csh.dto;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductDetailDTO {
    private Long id;
    private String title;
    private String description;
    private BigDecimal price;
    private BigDecimal originalPrice;
    private String categoryName;
    private Integer status;
    private Integer viewCount;
    /** 卖家用户 ID:前端据此判断当前用户是不是卖家,决定要不要显示「下架/删除」 */
    private Long userId;
    /** 审核备注(驳回原因) */
    private String auditRemark;
    private LocalDateTime createTime;
    // 商品图片列表
    private List<String> images;
    // 卖家信息内部类
    private SellerDTO seller;

    @Data
    public static class SellerDTO {
        private Integer id;
        private String username;
        private String avatar;
        private String phone;
    }
}