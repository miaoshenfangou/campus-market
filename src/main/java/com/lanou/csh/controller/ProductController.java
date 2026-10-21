package com.lanou.csh.controller;

import com.lanou.csh.dto.ProductDetailDTO;
import com.lanou.csh.dto.ProductQuery;
import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.Product;
import com.lanou.csh.entity.ProductImage;
import com.lanou.csh.entity.User;
import com.lanou.csh.service.AuthService;
import com.lanou.csh.service.ProductService;
import com.lanou.csh.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * <p>
 * 商品表 前端控制器
 * </p>
 *
 * @author lanou
 * @since 2026-07-12
 */
@RestController
@RequestMapping("/product")
@Tag(name = "商品模块")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Autowired
    private AuthService authService;

    @PostMapping("/publish")
    @Operation(summary = "发布商品")
    public Result<Integer> publishProduct(@RequestBody Product product,
                                          @RequestHeader("Authorization") String token) {
        Integer userId = authService.getCurrentUser(token).getId();
        product.setUserId(userId.longValue());
        Integer productId = productService.publishProduct(product);
        return Result.success(productId, "发布成功");
    }

    /**
     * 发布商品并同时上传图片(form-data)。
     * <p>
     * 前端发布弹窗直接把商品字段和图片文件放进同一个 FormData,
     * 后端在一个事务里完成「建商品 + 存图」,避免两步式调用产生无图商品。
     * 图片可留空:先发文字、之后再通过 /product/uploadImages/{id} 补图。
     */
    @PostMapping(value = "/publishWithImages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "发布商品并上传图片")
    public Result<Integer> publishWithImages(@ModelAttribute Product product,
                                             @RequestParam(value = "files", required = false) MultipartFile[] files,
                                             @RequestHeader("Authorization") String token) {
        Integer userId = authService.getCurrentUser(token).getId();
        // 用户可控字段一律重置,防止前端伪造 id / 状态 / 推荐权重
        product.setId(null);
        product.setUserId(userId.longValue());
        product.setStatus(null);
        product.setIsRecommend(null);
        product.setRecommendWeight(null);
        product.setViewCount(null);
        Integer productId = productService.publishProductWithImages(product, files);
        return Result.success(productId, "发布成功");
    }

    @PostMapping("/uploadImages/{productId}")
    @Operation(summary = "上传商品图片")
    public Result<List<ProductImage>> uploadProductImages(@PathVariable Long productId,
                                                         @RequestParam("files") MultipartFile[] files,
                                                         @RequestHeader("Authorization") String token) {
        Integer userId = authService.getCurrentUser(token).getId();
        List<ProductImage> images = productService.uploadProductImages(productId, userId, files);
        return Result.success(images, "上传成功");
    }

    /**
     * 商品列表:支持关键词 + 分类的复合筛选与分页。
     * <p>
     * 游客也可以访问,{@code loginUserId} 为空时「是否已收藏」统一返回 false。
     */
    @GetMapping("/list")
    @Operation(summary = "商品列表(复合筛选 + 分页)")
    public Result<PageResult<ProductVO>> listProducts(ProductQuery query,
                                                      @RequestHeader(value = "Authorization", required = false) String token) {
        query.setLoginUserId(authService.getCurrentUserId(token));
        PageResult<ProductVO> page = productService.listProducts(query);
        return Result.success(page);
    }

    @GetMapping("/detail/{id}")
    @Operation(summary = "商品详情")
    public Result<ProductDetailDTO> getProductDetail(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String token) {
        Integer viewerId = authService.getCurrentUserId(token);
        boolean auditor = false;
        if (viewerId != null) {
            User viewer = authService.getCurrentUser(token);
            auditor = viewer.getRole() != null && viewer.getRole() == 1;
        }
        ProductDetailDTO detail = productService.getProductDetail(id, viewerId, auditor);
        return Result.success(detail);
    }

    /**
     * 卖家侧状态变更:下架 / 重新提交审核 / 标记已售。
     * <p>
     * 具体允许哪些流转由服务层校验,前端只传目标状态:
     * 0-重新提交审核,2-下架,3-已售出。
     */
    @PutMapping("/status/{id}")
    @Operation(summary = "下架 / 重新上架(重新审核) / 标记已售")
    public Result<Void> updateStatus(@PathVariable Long id,
                                     @RequestParam Integer status,
                                     @RequestHeader("Authorization") String token) {
        Integer userId = authService.getCurrentUser(token).getId();
        productService.updateStatusBySeller(id, userId, status);
        return Result.success(null, "操作成功");
    }

    /**
     * 删除商品。卖家本人或审核员可以删除。
     * <p>
     * 路径用 /product/remove/{id} 而不是 DELETE /product/{id},
     * 是为了和 /product/list、/product/detail/** 这些读接口在拦截器规则上明确区分。
     */
    @DeleteMapping("/remove/{id}")
    @Operation(summary = "删除商品(卖家本人或审核员)")
    public Result<Void> deleteProduct(@PathVariable Long id,
                                      @RequestHeader("Authorization") String token) {
        User user = authService.getCurrentUser(token);
        boolean auditor = user.getRole() != null && user.getRole() == 1;
        productService.deleteProduct(id, user.getId(), auditor);
        return Result.success(null, "已删除");
    }
}
