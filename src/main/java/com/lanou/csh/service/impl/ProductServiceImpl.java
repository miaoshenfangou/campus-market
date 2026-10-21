package com.lanou.csh.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductDetailDTO;
import com.lanou.csh.dto.ProductQuery;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.Category;
import com.lanou.csh.entity.Favorite;
import com.lanou.csh.entity.Product;
import com.lanou.csh.entity.ProductImage;
import com.lanou.csh.entity.User;
import com.lanou.csh.exception.BusinessException;
import com.lanou.csh.mapper.CategoryMapper;
import com.lanou.csh.mapper.FavoriteMapper;
import com.lanou.csh.mapper.ProductImageMapper;
import com.lanou.csh.mapper.ProductMapper;
import com.lanou.csh.mapper.UserMapper;
import com.lanou.csh.service.FileUploadService;
import com.lanou.csh.service.ProductService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

    /** 商品状态:0-待审核 */
    private static final byte STATUS_PENDING = 0;
    /** 商品状态:1-已上架 */
    private static final byte STATUS_ON_SALE = 1;
    /** 商品状态:2-已下架(卖家主动) */
    private static final byte STATUS_OFF = 2;
    /** 商品状态:3-已售出 */
    private static final byte STATUS_SOLD = 3;
    /** 商品状态:4-审核驳回 */
    private static final byte STATUS_REJECTED = 4;

    /** 单个商品最多允许的图片数量(小内存机器也够用,避免磁盘被刷) */
    private static final int MAX_IMAGES_PER_PRODUCT = 9;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductImageMapper productImageMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FavoriteMapper favoriteMapper;

    @Autowired
    private FileUploadService fileUploadService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer publishProduct(Product product) {
        if (product.getTitle() == null || product.getTitle().isBlank()) {
            throw new BusinessException("商品标题不能为空");
        }
        if (product.getPrice() == null) {
            throw new BusinessException("商品价格不能为空");
        }
        // 新发布的商品一律进入「待审核」,审核员通过后才出现在首页列表。
        // 客户端传来的 status 在这里被覆盖,防止绕过审核直接上架。
        product.setStatus(STATUS_PENDING);
        product.setAuditRemark(null);
        product.setAuditTime(null);
        product.setAuditorId(null);
        product.setViewCount(0);
        product.setCreateTime(LocalDateTime.now());
        product.setUpdateTime(LocalDateTime.now());
        productMapper.insert(product);
        return product.getId().intValue();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer publishProductWithImages(Product product, MultipartFile[] files) {
        // 同类内部调用不经过 Spring 代理,但本方法自带事务,整体仍在同一事务内:
        // 图片保存失败会连同商品一起回滚,不会留下「有商品、没图」的脏数据。
        Integer productId = publishProduct(product);
        if (files != null && files.length > 0) {
            saveImages(productId.longValue(), files, 0);
        }
        return productId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<ProductImage> uploadProductImages(Long productId, Integer userId, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BusinessException("请至少选择一张图片");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在");
        }
        // 归属校验:只能给自己的商品传图,避免给别人的商品挂违规图片
        if (userId != null && !userId.equals(product.getUserId() == null ? null : product.getUserId().intValue())) {
            throw new BusinessException("只能给自己的商品上传图片");
        }
        // 追加图片时接在已有图片之后,不能覆盖原主图(sort_order 最小者为列表主图)
        Integer maxSort = productImageMapper.selectMaxSortOrder(productId);
        return saveImages(productId, files, maxSort == null ? 0 : maxSort + 1);
    }

    /**
     * 图片「存盘 + 落库」的公共流程。
     *
     * @param startSort 起始排序号,0 表示这是该商品的第一批图片(第一张即主图)
     */
    private List<ProductImage> saveImages(Long productId, MultipartFile[] files, int startSort) {
        List<String> existing = productImageMapper.selectImagesByProductId(productId);
        if (existing.size() + files.length > MAX_IMAGES_PER_PRODUCT) {
            throw new BusinessException("每个商品最多上传 " + MAX_IMAGES_PER_PRODUCT + " 张图片");
        }

        List<ProductImage> images = new ArrayList<>(files.length);
        int sort = startSort;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            // 存盘并拿到相对访问路径,如 /product-images/xxx.jpg
            String imageUrl = fileUploadService.uploadProductImage(file);

            ProductImage image = new ProductImage();
            image.setProductId(productId);
            image.setImageUrl(imageUrl);
            image.setSortOrder(sort++);
            productImageMapper.insert(image);
            images.add(image);
        }
        return images;
    }

    @Override
    public PageResult<ProductVO> listProducts(ProductQuery query) {
        // 参数兜底:防止前端传 0 / 负数导致 LIMIT 异常
        int pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1 : query.getPageNum();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 10
                : Math.min(query.getPageSize(), 100);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);

        // 走 XML 里的联表查询,一次带出分类名、卖家、主图与收藏状态,避免列表页 N+1
        Page<ProductVO> page = new Page<>(pageNum, pageSize);
        IPage<ProductVO> result = productMapper.selectProductList(page, query);

        // 直接返回 VO:mainImage / categoryName 这些字段在 Product 实体上并不存在,
        // 之前在这里把 VO 转回 Product,会把主图和分类名全部丢掉,
        // 表现为列表页永远只显示 emoji 占位、分类显示「未分类」。
        return new PageResult<>(result.getRecords(), result.getTotal(), pageNum, pageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductDetailDTO getProductDetail(Long id) {
        return getProductDetail(id, null, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductDetailDTO getProductDetail(Long id, Integer viewerId, boolean auditor) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BusinessException("商品不存在或已被下架");
        }

        // 未上架的商品只对卖家本人和审核员开放,其他人按「不存在」处理
        byte status = product.getStatus() == null ? STATUS_PENDING : product.getStatus();
        boolean owner = viewerId != null && product.getUserId() != null
                && product.getUserId().equals(viewerId.longValue());
        if (status != STATUS_ON_SALE && status != STATUS_SOLD && !owner && !auditor) {
            throw new BusinessException("该商品尚未上架");
        }

        // 浏览量 +1:重新拼一个只带 id 与 viewCount 的对象去 update,
        // 避免把整个实体(含 null 字段)覆盖写回数据库
        Product viewUpdate = new Product();
        viewUpdate.setId(id);
        viewUpdate.setViewCount((product.getViewCount() == null ? 0 : product.getViewCount()) + 1);
        productMapper.updateById(viewUpdate);

        ProductDetailDTO dto = new ProductDetailDTO();
        dto.setId(product.getId());
        dto.setTitle(product.getTitle());
        dto.setDescription(product.getDescription());
        dto.setPrice(product.getPrice());
        dto.setOriginalPrice(product.getOriginalPrice());
        dto.setStatus(product.getStatus() == null ? null : product.getStatus().intValue());
        dto.setViewCount(viewUpdate.getViewCount());
        dto.setCreateTime(product.getCreateTime());
        dto.setUserId(product.getUserId());
        dto.setAuditRemark(product.getAuditRemark());

        // 图片列表
        dto.setImages(productImageMapper.selectImagesByProductId(id));

        // 分类名
        if (product.getCategoryId() != null) {
            Category category = categoryMapper.selectById(product.getCategoryId());
            if (category != null) {
                dto.setCategoryName(category.getName());
            }
        }

        // 卖家信息:只暴露用户名、头像与联系方式,不带出密码等敏感字段
        if (product.getUserId() != null) {
            User seller = userMapper.selectById(product.getUserId());
            if (seller != null) {
                ProductDetailDTO.SellerDTO sellerDTO = new ProductDetailDTO.SellerDTO();
                sellerDTO.setId(seller.getId());
                sellerDTO.setUsername(seller.getUsername());
                sellerDTO.setAvatar(seller.getAvatar());
                sellerDTO.setPhone(seller.getPhone());
                dto.setSeller(sellerDTO);
            }
        }
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatusBySeller(Long productId, Integer userId, Integer targetStatus) {
        if (targetStatus == null) {
            throw new BusinessException("缺少目标状态");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在或已删除");
        }
        if (userId == null || product.getUserId() == null || !product.getUserId().equals(userId.longValue())) {
            throw new BusinessException("只能操作自己发布的商品");
        }

        byte current = product.getStatus() == null ? STATUS_PENDING : product.getStatus();
        byte target = targetStatus.byteValue();

        boolean allowed;
        if (target == STATUS_OFF) {
            // 在售、待审核、被驳回的商品都可以主动下架
            allowed = current == STATUS_ON_SALE || current == STATUS_PENDING || current == STATUS_REJECTED;
        } else if (target == STATUS_PENDING) {
            // 「重新上架」= 重新提交审核,只有下架或被驳回的商品需要走这一步
            allowed = current == STATUS_OFF || current == STATUS_REJECTED;
        } else if (target == STATUS_SOLD) {
            allowed = current == STATUS_ON_SALE;
        } else {
            allowed = false;
        }
        if (!allowed) {
            throw new BusinessException("当前状态不允许该操作");
        }

        UpdateWrapper<Product> update = new UpdateWrapper<>();
        update.eq("id", productId)
                .set("status", target)
                .set("update_time", LocalDateTime.now());
        if (target == STATUS_PENDING) {
            // 重新提交时清掉上一次的驳回原因,避免卖家看到过期提示
            update.set("audit_remark", null)
                    .set("audit_time", null)
                    .set("auditor_id", null);
        }
        productMapper.update(null, update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteProduct(Long productId, Integer userId, boolean auditor) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在或已删除");
        }
        boolean owner = userId != null && product.getUserId() != null
                && product.getUserId().equals(userId.longValue());
        if (!owner && !auditor) {
            throw new BusinessException("只能删除自己发布的商品");
        }

        // 先把图片路径读出来,删完记录后再删磁盘文件
        List<String> urls = productImageMapper.selectImagesByProductId(productId);

        productImageMapper.delete(new QueryWrapper<ProductImage>().eq("product_id", productId));
        favoriteMapper.delete(new QueryWrapper<Favorite>().eq("product_id", productId));
        productMapper.deleteById(productId);

        if (urls != null) {
            urls.forEach(fileUploadService::deleteByUrl);
        }
    }

    @Override
    public PageResult<ProductVO> listByStatus(Integer status, Integer pageNum, Integer pageSize) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);

        QueryWrapper<Product> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        // 待审核按提交时间正序(先来先审),其它状态按时间倒序看最新
        if (status != null && status == STATUS_PENDING) {
            wrapper.orderByAsc("create_time");
        } else {
            wrapper.orderByDesc("create_time");
        }

        Page<Product> result = new Page<>(page, size);
        productMapper.selectPage(result, wrapper);
        return new PageResult<>(toVOs(result.getRecords()), result.getTotal(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditProduct(Long productId, Integer auditorId, Boolean approved, String remark) {
        if (approved == null) {
            throw new BusinessException("请选择通过或驳回");
        }
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("商品不存在或已删除");
        }
        byte current = product.getStatus() == null ? STATUS_PENDING : product.getStatus();
        if (current != STATUS_PENDING) {
            throw new BusinessException("该商品不在待审核状态,可能已被处理");
        }

        UpdateWrapper<Product> update = new UpdateWrapper<>();
        update.eq("id", productId)
                .set("status", approved ? STATUS_ON_SALE : STATUS_REJECTED)
                .set("audit_time", LocalDateTime.now())
                .set("auditor_id", auditorId)
                .set("update_time", LocalDateTime.now());

        if (approved) {
            update.set("audit_remark", null);
        } else {
            String reason = remark == null || remark.isBlank() ? "内容不符合发布规范" : remark.trim();
            update.set("audit_remark", reason.length() > 200 ? reason.substring(0, 200) : reason);
        }
        productMapper.update(null, update);
    }

    /**
     * 把商品实体批量转成列表用的 VO。
     * <p>
     * 主图与分类名各用一次批量查询补齐,避免逐条查库(N+1)。
     * 注意 {@code status} 必须手动赋值:实体是 {@code Byte},VO 是 {@code Integer},
     * {@link BeanUtils#copyProperties} 遇到类型不兼容会静默跳过。
     */
    private List<ProductVO> toVOs(List<Product> products) {
        if (products == null || products.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> ids = products.stream()
                .map(Product::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, String> imageMap = new HashMap<>();
        if (!ids.isEmpty()) {
            for (ProductImage image : productImageMapper.selectMainImagesByProductIds(ids)) {
                if (image.getProductId() != null) {
                    imageMap.putIfAbsent(image.getProductId(), image.getImageUrl());
                }
            }
        }

        List<Long> categoryIds = products.stream()
                .map(Product::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> nameMap = new HashMap<>();
        if (!categoryIds.isEmpty()) {
            for (Category category : categoryMapper.selectBatchIds(categoryIds)) {
                nameMap.put(category.getId(), category.getName());
            }
        }

        // 审核台需要看是谁发的,顺手把用户名也批量带上
        List<Long> userIds = products.stream()
                .map(Product::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> userMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : userMapper.selectBatchIds(userIds)) {
                userMap.put(user.getId().longValue(), user.getUsername());
            }
        }

        List<ProductVO> vos = new ArrayList<>(products.size());
        for (Product product : products) {
            ProductVO vo = new ProductVO();
            BeanUtils.copyProperties(product, vo);
            vo.setStatus(product.getStatus() == null ? null : product.getStatus().intValue());
            vo.setMainImage(imageMap.get(product.getId()));
            vo.setCategoryName(product.getCategoryId() == null ? null : nameMap.get(product.getCategoryId()));
            vo.setUsername(product.getUserId() == null ? null : userMap.get(product.getUserId()));
            vos.add(vo);
        }
        return vos;
    }
}
