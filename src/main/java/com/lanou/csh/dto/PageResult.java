package com.lanou.csh.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 通用分页返回结果。
 * <p>
 * 与 MyBatis-Plus 的 {@code Page} 解耦,只暴露前端真正需要的字段,
 * 避免把分页插件的内部属性(如 orders、optimizeCountSql)序列化给前端。
 *
 * @param <T> 列表元素类型
 * @author lanou
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码,从 1 开始 */
    private long pageNum;

    /** 每页条数 */
    private long pageSize;

    /** 总页数 */
    private long pages;

    public PageResult() {
    }

    public PageResult(List<T> records, long total, long pageNum, long pageSize) {
        this.records = records;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
    }
}
