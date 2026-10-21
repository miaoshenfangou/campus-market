package com.lanou.csh.dto;

import lombok.Data;

/**
 * 审核请求体。
 *
 * @author lanou
 */
@Data
public class AuditRequest {

    /**
     * true-通过(商品上架) false-驳回(商品退回卖家,需填原因)
     */
    private Boolean approved;

    /**
     * 审核备注。驳回时作为驳回原因展示给卖家;通过时可留空。
     */
    private String remark;
}
