package com.lanou.csh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lanou.csh.entity.Favorite;
import com.lanou.csh.mapper.FavoriteMapper;
import com.lanou.csh.service.FavoriteService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 收藏服务实现。
 * <p>
 * 两个方法都做成幂等:重复收藏不会插入第二条,重复取消也不报错。
 * 数据库层面另有 {@code uk_user_product} 联合唯一索引兜底,
 * 即使并发请求同时通过了「是否存在」检查,也只会有一条写入成功。
 *
 * @author lanou
 */
@Service
public class FavoriteServiceImpl extends ServiceImpl<FavoriteMapper, Favorite> implements FavoriteService {

    @Override
    public boolean addFavorite(Integer userId, Long productId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getProductId, productId);
        if (this.count(wrapper) > 0) {
            return true;
        }
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setProductId(productId);
        favorite.setCreateTime(LocalDateTime.now());
        return baseMapper.insert(favorite) > 0;
    }

    @Override
    public boolean removeFavorite(Integer userId, Long productId) {
        LambdaQueryWrapper<Favorite> wrapper = new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getProductId, productId);
        baseMapper.delete(wrapper);
        return true;
    }
}
