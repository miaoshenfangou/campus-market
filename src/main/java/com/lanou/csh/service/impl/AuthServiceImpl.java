package com.lanou.csh.service.impl;

import com.lanou.csh.entity.User;
import com.lanou.csh.exception.BusinessException;
import com.lanou.csh.mapper.UserMapper;
import com.lanou.csh.service.AuthService;
import com.lanou.csh.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 鉴权服务实现:JWT 解析 + 用户校验。
 *
 * @author lanou
 */
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserMapper userMapper;

    @Override
    public User getCurrentUser(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException("未登录,请先登录");
        }
        Integer userId = jwtUtil.getUserId(token);
        if (userId == null) {
            throw new BusinessException("登录状态已失效,请重新登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return user;
    }

    @Override
    public Integer getCurrentUserId(String token) {
        return token == null ? null : jwtUtil.getUserId(token);
    }
}
