package com.lanou.csh.service.impl;

import com.lanou.csh.exception.BusinessException;
import com.lanou.csh.service.FileUploadService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传实现:图片落地到磁盘,返回可被浏览器直接访问的相对路径。
 * <p>
 * 安全约束:
 * <ul>
 *   <li>只接受 {@code image/*},并按扩展名白名单二次校验(Content-Type 可被伪造);</li>
 *   <li>文件名统一用 UUID 重新生成,不沿用上传的原始名,杜绝路径穿越与重名覆盖;</li>
 *   <li>限制单张大小,避免小内存机器被大文件打爆。</li>
 * </ul>
 *
 * @author lanou
 */
@Service
public class FileUploadServiceImpl implements FileUploadService {

    /** 允许的图片扩展名 */
    private static final Set<String> ALLOWED_EXT = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "bmp"));

    /** Content-Type 到扩展名的兜底映射:原始文件名没有后缀时使用 */
    private static final Map<String, String> MIME_TO_EXT = new HashMap<>();

    static {
        MIME_TO_EXT.put("image/jpeg", "jpg");
        MIME_TO_EXT.put("image/jpg", "jpg");
        MIME_TO_EXT.put("image/png", "png");
        MIME_TO_EXT.put("image/gif", "gif");
        MIME_TO_EXT.put("image/webp", "webp");
        MIME_TO_EXT.put("image/bmp", "bmp");
    }

    private static final long AVATAR_MAX_BYTES = 10L * 1024 * 1024;
    private static final long PRODUCT_IMAGE_MAX_BYTES = 5L * 1024 * 1024;

    @Value("${file.upload.avatar-path}")
    private String uploadPath;

    @Value("${file.upload.product-image-path}")
    private String productImagePath;

    @Override
    public String uploadAvatar(MultipartFile file) throws IOException {
        return saveImage(file, uploadPath, "/avatar/", AVATAR_MAX_BYTES);
    }

    @Override
    public String uploadProductImage(MultipartFile file) {
        return saveImage(file, productImagePath, "/product-images/", PRODUCT_IMAGE_MAX_BYTES);
    }

    /** 本服务生成的文件名格式:32 位无连字符 UUID + 扩展名 */
    private static final java.util.regex.Pattern GENERATED_NAME =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{32}\\.[a-zA-Z0-9]{2,5}$");

    @Override
    public boolean deleteByUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        int slash = url.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String prefix = url.substring(0, slash + 1);
        String name = url.substring(slash + 1);

        // 只删本服务生成的文件名,历史上手写的 kindle.jpg 之类不会被误删
        if (!GENERATED_NAME.matcher(name).matches()) {
            return false;
        }

        String dir;
        if ("/product-images/".equals(prefix)) {
            dir = productImagePath;
        } else if ("/avatar/".equals(prefix)) {
            dir = uploadPath;
        } else {
            return false;
        }

        File file = new File(new File(dir).getAbsoluteFile(), name);
        try {
            return file.isFile() && file.delete();
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * 统一的图片保存逻辑:校验 → 生成新文件名 → 落盘 → 返回访问路径。
     *
     * @param dir       磁盘目录(绝对路径)
     * @param urlPrefix 访问前缀,如 {@code /product-images/}
     */
    private String saveImage(MultipartFile file, String dir, String urlPrefix, long maxBytes) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传的图片为空");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BusinessException("只能上传图片文件");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException("图片大小不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }

        String ext = extensionOf(file.getOriginalFilename());
        if (ext.isEmpty()) {
            // 没有后缀(部分手机相册导出的文件会这样)才按 Content-Type 推断
            ext = MIME_TO_EXT.get(contentType.toLowerCase(Locale.ROOT));
        } else if (!ALLOWED_EXT.contains(ext)) {
            // 有后缀但不在白名单(.jsp、.php、.exe 等)直接拒绝,
            // 不能"猜一个后缀"兜底 —— 那等于允许把任意文件塞进图片目录。
            throw new BusinessException("不支持的图片格式,请上传 JPG / PNG / GIF / WEBP");
        }
        if (ext == null) {
            throw new BusinessException("无法识别图片格式,请重新选择文件");
        }

        File destDir = new File(dir).getAbsoluteFile();
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new BusinessException("上传目录创建失败,请检查服务器磁盘权限");
        }

        // UUID 重命名:避免中文名、重名覆盖,也阻断 ../ 之类的路径穿越
        String newFileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        try {
            file.transferTo(new File(destDir, newFileName));
        } catch (IOException e) {
            throw new BusinessException("图片保存失败,请稍后重试");
        }
        return urlPrefix + newFileName;
    }

    /**
     * 从原始文件名中提取小写扩展名,没有则返回空串。
     */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
