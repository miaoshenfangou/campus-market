package com.lanou.csh.service;

import com.lanou.csh.entity.User;

/**
 * 鉴权服务:从请求头携带的 Token 中还原当前登录用户。
 * <p>
 * 控制器层统一通过本接口获取登录用户,避免每个接口都重复写
 * 「解析 Token → 查库 → 判空」的样板代码。
 *
 * @author lanou
 */
public interface AuthService {

    /**
     * 解析 Token 并返回当前登录用户。
     *
     * @param token 请求头 Authorization 的值,支持 "Bearer xxx" 与裸 token
     * @return 当前登录用户
     * @throws com.lanou.csh.exception.BusinessException Token 缺失、无效或已过期;或用户不存在
     */
    User getCurrentUser(String token);

    /**
     * 解析 Token 返回当前用户 ID,不做「用户是否存在」的校验。
     *
     * @return 用户 ID;Token 无效时返回 null
     */
    Integer getCurrentUserId(String token);
}
