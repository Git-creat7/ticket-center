package asia.creat.service;

import asia.creat.dto.UserDTO;
import asia.creat.utils.UserHolder;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 图片对象存储服务：封装阿里云 OSS 的上传与删除，以及 key 生成和地址校验。
 * Controller 只负责参数绑定与结果包装，存储细节与可替换性收敛在本类。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "ticket.oss", name = "enabled", havingValue = "true")
public class OssStorageService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");

    private final OSS ossClient;
    private final String bucketName;
    private final String publicDomain;
    private final String objectPrefix;

    public OssStorageService(
            OSS ossClient,
            @Value("${ticket.oss.bucket-name}") String bucketName,
            @Value("${ticket.oss.public-domain}") String publicDomain,
            @Value("${ticket.oss.object-prefix}") String objectPrefix) {
        this.ossClient = ossClient;
        this.bucketName = bucketName;
        this.publicDomain = removeTrailingSlash(publicDomain);
        this.objectPrefix = normalizePrefix(objectPrefix);
    }

    /**
     * 上传图片，返回可访问的完整 URL。
     *
     * @throws IllegalArgumentException 参数或格式不合法
     * @throws IllegalStateException    OSS 存储失败
     */
    public String uploadImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }

        try {
            String objectKey = createObjectKey(image.getOriginalFilename());
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(image.getSize());
            if (StringUtils.hasText(image.getContentType())) {
                metadata.setContentType(image.getContentType());
            }

            try (InputStream inputStream = image.getInputStream()) {
                ossClient.putObject(bucketName, objectKey, inputStream, metadata);
            }

            String imageUrl = publicDomain + "/" + objectKey;
            log.debug("图片上传到 OSS 成功，objectKey={}", objectKey);
            return imageUrl;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | OSSException | ClientException e) {
            throw new IllegalStateException("图片上传失败", e);
        }
    }

    /**
     * 删除图片（接受完整 URL 或对象 key）。
     *
     * @throws IllegalArgumentException 地址不属于本项目或格式不合法
     * @throws IllegalStateException    OSS 存储失败
     */
    public void deleteImage(String imageUrl) {
        try {
            String objectKey = extractObjectKey(imageUrl);
            ossClient.deleteObject(bucketName, objectKey);
            log.debug("删除 OSS 图片成功，objectKey={}", objectKey);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (OSSException | ClientException e) {
            throw new IllegalStateException("图片删除失败", e);
        }
    }

    private String createObjectKey(String originalFilename) {
        String suffix = StringUtils.getFilenameExtension(originalFilename);
        if (!StringUtils.hasText(suffix)) {
            throw new IllegalArgumentException("图片缺少扩展名");
        }

        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        if (!IMAGE_EXTENSIONS.contains(normalizedSuffix)) {
            throw new IllegalArgumentException("仅支持 jpg、jpeg、png、gif 和 webp 图片");
        }

        String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
        // 与本地存储一致：文件名带 {userId}- 前缀，删除时 FileStorageService 据此校验归属
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("上传图片需要登录");
        }
        return objectPrefix + datePath + "/" + user.getId() + "-"
                + UUID.randomUUID().toString().replace("-", "") + "." + normalizedSuffix;
    }

    private String extractObjectKey(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new IllegalArgumentException("图片地址不能为空");
        }

        String value = filename.trim().replace('\\', '/');
        String objectKey;
        URI uri = URI.create(value);
        if (uri.isAbsolute()) {
            URI domain = URI.create(publicDomain);
            if (!domain.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !domain.getHost().equalsIgnoreCase(uri.getHost())) {
                throw new IllegalArgumentException("不是本项目的 OSS 图片地址");
            }
            objectKey = uri.getPath();
        } else {
            objectKey = value;
        }

        while (objectKey.startsWith("/")) {
            objectKey = objectKey.substring(1);
        }
        if (!objectKey.startsWith(objectPrefix) || objectKey.length() == objectPrefix.length()) {
            throw new IllegalArgumentException("只能删除本项目上传的图片");
        }
        return objectKey;
    }

    private String normalizePrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return "uploads/";
        }
        String normalized = prefix.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String removeTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
