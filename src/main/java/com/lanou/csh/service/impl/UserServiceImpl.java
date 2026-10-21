package com.lanou.csh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.Category;
import com.lanou.csh.entity.Favorite;
import com.lanou.csh.entity.Product;
import com.lanou.csh.entity.User;
import com.lanou.csh.exception.BusinessException;
import com.lanou.csh.mapper.CategoryMapper;
import com.lanou.csh.mapper.FavoriteMapper;
import com.lanou.csh.mapper.ProductImageMapper;
import com.lanou.csh.mapper.ProductMapper;
import com.lanou.csh.mapper.UserMapper;
import com.lanou.csh.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /** 由 SecurityConfig 统一提供,不在这里 new,便于替换算法与统一配置 */
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductImageMapper productImageMapper;

    @Autowired
    private FavoriteMapper favoriteMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Override
    public User updateUserInfo(User user) {
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        return userMapper.selectById(user.getId());
    }

    @Override
    public PageResult<ProductVO> getMyProducts(Integer userId, Integer pageNum, Integer pageSize) {
        Page<Product> page = new Page<>(pageNum, pageSize);
        productMapper.selectPage(page, new QueryWrapper<Product>()
                .eq("user_id", userId)
                .orderByDesc("create_time"));
        List<ProductVO> vos = convertToProductVOs(page.getRecords());
        return new PageResult<>(vos, page.getTotal(), pageNum, pageSize);
    }

    @Override
    public PageResult<ProductVO> getMyFavorites(Integer userId, Integer pageNum, Integer pageSize) {
        Page<Favorite> page = new Page<>(pageNum, pageSize);
        favoriteMapper.selectPage(page, new QueryWrapper<Favorite>()
                .eq("user_id", userId)
                .orderByDesc("create_time"));

        List<Long> productIds = page.getRecords().stream()
                .map(Favorite::getProductId)
                .collect(Collectors.toList());

        if (productIds.isEmpty()) {
            return new PageResult<>(Collections.emptyList(), page.getTotal(), pageNum, pageSize);
        }

        List<Product> products = productMapper.selectBatchIds(productIds);
        return new PageResult<>(convertToProductVOs(products), page.getTotal(), pageNum, pageSize);
    }

    @Override
    public User login(String username, String rawPassword) {
        if (username == null || username.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException("用户名和密码不能为空");
        }
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        // 用户不存在与密码错误返回同一句提示,避免被用来枚举已注册用户名
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        user.setPassword(null);
        return user;
    }

    @Override
    public User register(User user) {
        String username = user.getUsername();
        if (username == null || username.isBlank()) {
            throw new BusinessException("用户名不能为空");
        }
        if (user.getPassword() == null || user.getPassword().length() < 6) {
            throw new BusinessException("密码长度不能少于 6 位");
        }
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (count != null && count > 0) {
            throw new BusinessException("该用户名已被注册");
        }

        user.setId(null);
        // 角色只能由数据库决定:注册接口一律建成普通用户,
        // 否则请求体里塞一个 role=1 就能自己注册成审核员
        user.setRole(0);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);

        user.setPassword(null);
        return user;
    }

    /**
     * 把 Product 列表转换为 ProductVO 列表,并补齐主图。
     * <p>
     * 主图采用「一次批量查询 + 内存分组」的方式,避免在循环里逐条查库
     * (原实现是 N 次单条 SQL,商品多时会产生 N+1 问题)。
     */
    private List<ProductVO> convertToProductVOs(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toList());
        // 一次查出所有商品的主图,再按 productId 分组
        var mainImageMap = productImageMapper.selectMainImagesByProductIds(productIds).stream()
                .filter(img -> img.getProductId() != null)
                .collect(Collectors.toMap(
                        img -> img.getProductId().toString(),
                        img -> img.getImageUrl() == null ? "" : img.getImageUrl(),
                        (a, b) -> a
                ));

        // 分类名同样一次批量查出:Product 实体上没有 categoryName,不补的话前端只能显示「未分类」
        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> categoryNameMap = categoryIds.isEmpty()
                ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(categoryIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));

        return products.stream().map(product -> {
            ProductVO vo = new ProductVO();
            BeanUtils.copyProperties(product, vo);
            vo.setMainImage(mainImageMap.get(product.getId().toString()));
            vo.setCategoryName(product.getCategoryId() == null
                    ? null : categoryNameMap.get(product.getCategoryId()));
            // 实体 status 是 Byte、VO 是 Integer,BeanUtils 不会自动转换,
            // 不显式赋值的话「我的发布」里所有商品都会被当成在售状态
            vo.setStatus(product.getStatus() == null ? null : product.getStatus().intValue());
            return vo;
        }).collect(Collectors.toList());
    }
}
