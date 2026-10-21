package com.lanou.csh.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductDetailDTO;
import com.lanou.csh.dto.ProductQuery;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.Product;
import com.lanou.csh.entity.ProductImage;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * <p>
 * 商品表 服务类
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
public interface ProductService extends IService<Product> {

    /**
     * 发布商品:落库并返回新商品 ID。
     */
    Integer publishProduct(Product product);

    /**
     * 发布商品并同时保存图片:一次事务内完成「建商品 + 存图 + 写图片表」。
     * <p>
     * 相比「先发布再单独上传」的两步式调用,这里任意一步失败都会整体回滚,
     * 不会留下「有商品、没图片」的脏数据。
     *
     * @param product 商品信息
     * @param files   图片文件,可为空(先发文字、后补图)
     * @return 新商品 ID
     */
    Integer publishProductWithImages(Product product, MultipartFile[] files);

    /**
     * 给已存在的商品追加图片。
     *
     * @param productId 商品 ID
     * @param userId    当前登录用户,用于校验只能给自己的商品传图
     * @param files     图片文件
     */
    List<ProductImage> uploadProductImages(Long productId, Integer userId, MultipartFile[] files);

    /**
     * 商品列表:关键词 + 分类复合筛选,带分页。
     * <p>
     * 返回 {@link ProductVO} 而不是 {@code Product}:主图、分类名、卖家名这些
     * 字段在商品实体上并不存在,只有 VO 才带得出来。
     */
    PageResult<ProductVO> listProducts(ProductQuery query);

    /**
     * 商品详情:含分类名、卖家信息、图片列表,并累加浏览量。
     */
    ProductDetailDTO getProductDetail(Long id);

    /**
     * 带访问者身份的详情查询。
     * <p>
     * 未上架(待审核 / 已下架 / 已售出 / 驳回)的商品只对卖家本人和审核员可见,
     * 其他人一律按「商品不存在」处理,避免通过猜 ID 看到未审核内容。
     *
     * @param id       商品 ID
     * @param viewerId 当前登录用户 ID,游客传 null
     * @param auditor  当前用户是否审核员
     */
    ProductDetailDTO getProductDetail(Long id, Integer viewerId, boolean auditor);

    /**
     * 卖家侧状态变更:下架 / 重新提交审核 / 标记已售。
     *
     * @param productId    商品 ID
     * @param userId       当前登录用户(必须是卖家本人)
     * @param targetStatus 目标状态:2-下架,0-重新提交审核,3-已售出
     */
    void updateStatusBySeller(Long productId, Integer userId, Integer targetStatus);

    /**
     * 删除商品:卖家本人或审核员可操作。
     * <p>
     * 会一并清理商品图片记录、收藏记录以及磁盘上的图片文件,避免留下孤儿数据。
     *
     * @param productId 商品 ID
     * @param userId    当前登录用户 ID
     * @param auditor   当前用户是否审核员
     */
    void deleteProduct(Long productId, Integer userId, boolean auditor);

    /**
     * 按状态分页查询,给审核台使用。
     *
     * @param status 0-待审核 1-已上架 2-已下架 3-已售出 4-已驳回;传 null 表示全部
     */
    PageResult<ProductVO> listByStatus(Integer status, Integer pageNum, Integer pageSize);

    /**
     * 审核商品:通过则上架,驳回则退回卖家并记录原因。
     *
     * @param productId 商品 ID
     * @param auditorId 审核员用户 ID
     * @param approved  true-通过 false-驳回
     * @param remark    审核备注(驳回必填)
     */
    void auditProduct(Long productId, Integer auditorId, Boolean approved, String remark);
}
