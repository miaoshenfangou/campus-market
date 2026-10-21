package com.lanou.csh.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 轮播图表
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Getter
@Setter
@ToString
public class Banner implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID,自增长
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 横幅标题
     */
    private String title;

    /**
     * 横幅图片路径
     */
    private String image;

    /**
     * 点击横幅跳转的链接地址
     */
    private String url;

    /**
     * 排序权重,数字越大排序越靠前
     */
    private Integer sort;

    /**
     * 状态:0-禁用 1-启用
     */
    private Byte status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
