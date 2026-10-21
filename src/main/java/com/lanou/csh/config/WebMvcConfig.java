package com.lanou.csh.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置:注册 JWT 拦截器与静态资源映射。
 *
 * @author lanou
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private JwtInterceptor jwtInterceptor;

    /** 上传文件的磁盘根目录,与 application.yml 中 file.upload.path 保持一致 */
    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 需要登录才能访问的接口路径。
     * <p>
     * 只拦截写操作与「我的」相关接口;商品浏览、分类、轮播图等读接口对游客开放。
     */
    private static final String[] AUTH_REQUIRED = {
            "/favorites/**",
            "/product/publish",
            "/product/publishWithImages",
            "/product/uploadImages/**",
            "/user/**",
            "/product/status/**",
            "/product/remove/**",
            "/admin/**"
    };

    /** 无需登录的接口,允许游客访问 */
    private static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/auth/register",
            "/product/list",
            "/product/detail/**",
            "/category/**",
            "/banner/**",
            "/doc.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/error"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns(AUTH_REQUIRED)
                .excludePathPatterns(PUBLIC_PATHS);
    }

    /**
     * 把上传目录映射为静态资源,使 {@code /product-images/xxx.jpg} 可直接被浏览器访问。
     * <p>
     * application.yml 里已配置 {@code spring.web.resources.static-locations},
     * 这里显式注册是为了保证两种配置方式都能生效,避免因配置优先级导致图片 404。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + uploadPath;
        registry.addResourceHandler("/product-images/**").addResourceLocations(location + "product-images/");
        registry.addResourceHandler("/avatar/**").addResourceLocations(location + "avatar/");
    }
}
