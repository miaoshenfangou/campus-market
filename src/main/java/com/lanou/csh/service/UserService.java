package com.lanou.csh.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.User;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
public interface UserService extends IService<User> {

    /**
     * 更新用户资料。
     *
     * @param user 只包含需要修改字段的用户对象,主键必填
     * @return 更新后的用户
     */
    User updateUserInfo(User user);

    /**
     * 分页查询我发布的商品。
     *
     * @param userId   当前登录用户 ID
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     */
    PageResult<ProductVO> getMyProducts(Integer userId, Integer pageNum, Integer pageSize);

    /**
     * 分页查询我收藏的商品。
     *
     * @param userId   当前登录用户 ID
     * @param pageNum  页码,从 1 开始
     * @param pageSize 每页条数
     */
    PageResult<ProductVO> getMyFavorites(Integer userId, Integer pageNum, Integer pageSize);

    /**
     * 登录校验:用户名 + 明文密码。
     *
     * @return 校验通过返回用户(密码字段已置空);失败抛业务异常
     */
    User login(String username, String rawPassword);

    /**
     * 注册新用户:用户名查重 + BCrypt 加密入库。
     */
    User register(User user);
}
