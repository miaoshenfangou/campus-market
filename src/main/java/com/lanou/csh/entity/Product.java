package com.lanou.csh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 商品表
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Getter
@Setter
@ToString
public class Product implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long categoryId;

    private String title;

    private String description;

    private BigDecimal price;

    private BigDecimal originalPrice;

    /**
     * 0-待审核 1-已上架 2-已下架 3-已售出 4-审核驳回
     */
    private Byte status;

    /**
     * 0-不推荐 1-推荐
     */
    private Byte isRecommend;

    /**
     * 推荐权重
     */
    private Integer recommendWeight;

    /**
     * 浏览数
     */
    private Integer viewCount;

    /**
     * 审核备注:驳回时由审核员填写,通过时留空
     */
    private String auditRemark;

    /**
     * 审核时间
     */
    private LocalDateTime auditTime;

    /**
     * 审核员用户 ID
     */
    private Long auditorId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
