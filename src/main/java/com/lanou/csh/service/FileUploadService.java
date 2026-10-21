package com.lanou.csh.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface FileUploadService {
    String uploadAvatar(MultipartFile file) throws IOException;

    String uploadProductImage(MultipartFile file);

    /**
     * 按访问路径删除磁盘上的图片文件。
     * <p>
     * 只处理本服务生成的文件名(UUID.ext),且路径前缀必须是
     * {@code /product-images/} 或 {@code /avatar/},
     * 因此即便传入 {@code ../../etc/passwd} 也不会越权删除。
     *
     * @param url 上传时返回的访问路径,如 {@code /product-images/xxx.jpg}
     * @return 真的删掉了文件返回 true;路径非法、文件不存在或删除失败返回 false
     */
    boolean deleteByUrl(String url);
}
