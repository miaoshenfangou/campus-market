package com.lanou.csh.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lanou.csh.entity.Favorite;

/**
 * <p>
 * 收藏表 服务类
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
public interface FavoriteService extends IService<Favorite> {

    /**
     * 收藏商品(幂等:已收藏时直接返回 true,不重复插入)。
     *
     * @return 操作后是否处于「已收藏」状态
     */
    boolean addFavorite(Integer userId, Long productId);

    /**
     * 取消收藏(幂等:未收藏时也返回 true)。
     */
    boolean removeFavorite(Integer userId, Long productId);
}
