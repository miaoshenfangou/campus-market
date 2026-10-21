package com.lanou.csh.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 登录请求体。
 * <p>
 * 单独定义 DTO 而不是直接收 {@code User} 实体,避免前端把 id、avatar 等
 * 不该由登录接口写入的字段一起传进来。
 *
 * @author lanou
 */
@Data
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;

    private String password;
}
