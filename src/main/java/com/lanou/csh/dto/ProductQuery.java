package com.lanou.csh.dto;
import lombok.Data;

@Data
public class ProductQuery {
    private Integer pageNum = 1;
    private Integer pageSize = 10;
    private Integer categoryId;
    private String keyword;
    private Integer loginUserId;
}
//yan