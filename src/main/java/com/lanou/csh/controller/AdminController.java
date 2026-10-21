package com.lanou.csh.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lanou.csh.dto.AuditRequest;
import com.lanou.csh.dto.PageResult;
import com.lanou.csh.dto.ProductVO;
import com.lanou.csh.entity.Product;
import com.lanou.csh.entity.User;
import com.lanou.csh.exception.BusinessException;
import com.lanou.csh.service.AuthService;
import com.lanou.csh.service.ProductService;
import com.lanou.csh.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 审核模块:只有 {@code user.role = 1} 的审核员可以访问。
 * <p>
 * 权限校验放在每个方法开头(而不是拦截器里),这样权限不足时能返回
 * 业务提示「需要审核员权限」,而不是统一被拦成 401 让用户以为没登录。
 *
 * @author lanou
 */
@RestController
@RequestMapping("/admin")
@Tag(name = "审核模块")
public class AdminController {

    /** 待审核状态码,与 ProductServiceImpl 中的常量保持一致 */
    private static final byte STATUS_PENDING = 0;

    @Autowired
    private ProductService productService;

    @Autowired
    private AuthService authService;

    @GetMapping("/products")
    @Operation(summary = "按状态查询商品(审核台)")
    public Result<PageResult<ProductVO>> listProducts(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestHeader("Authorization") String token) {
        requireAuditor(token);
        return Result.success(productService.listByStatus(status, pageNum, pageSize));
    }

    @PostMapping("/products/{id}/audit")
    @Operation(summary = "审核商品:通过上架 / 驳回")
    public Result<Void> auditProduct(@PathVariable Long id,
                                     @RequestBody AuditRequest request,
                                     @RequestHeader("Authorization") String token) {
        User auditor = requireAuditor(token);
        productService.auditProduct(id, auditor.getId(), request.getApproved(), request.getRemark());
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        return Result.success(null, approved ? "已通过,商品已上架" : "已驳回,已通知卖家");
    }

    /**
     * 审核台角标用的统计:当前有多少件待审核。
     */
    @GetMapping("/stats")
    @Operation(summary = "审核台统计(待审核数量)")
    public Result<Map<String, Object>> stats(@RequestHeader("Authorization") String token) {
        requireAuditor(token);
        Map<String, Object> data = new HashMap<>(2);
        data.put("pending", productService.count(new QueryWrapper<Product>().eq("status", STATUS_PENDING)));
        return Result.success(data);
    }

    /**
     * 校验当前用户是否审核员。
     *
     * @return 审核员用户,供调用方取 id 写入审核记录
     */
    private User requireAuditor(String token) {
        User user = authService.getCurrentUser(token);
        if (user.getRole() == null || user.getRole() != 1) {
            throw new BusinessException("需要审核员权限,当前账号不是审核员");
        }
        return user;
    }
}
