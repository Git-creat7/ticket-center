package asia.creat.service;

import asia.creat.common.exception.BusinessException;
import asia.creat.dto.UserDTO;
import asia.creat.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final String UPLOAD_DIR = "uploads";

    @Value("${ticket.oss.enabled:false}")
    private boolean ossEnabled;

    @Autowired(required = false)
    private OssStorageService ossStorageService;

    /**
     * 上传图片，优先使用 OSS，未启用 OSS 时保存在本地磁盘 uploads 目录
     */
    public String uploadImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }

        String originalFilename = image.getOriginalFilename();
        String suffix = StringUtils.getFilenameExtension(originalFilename);
        if (!StringUtils.hasText(suffix)) {
            throw new IllegalArgumentException("图片缺少扩展名");
        }

        String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
        if (!IMAGE_EXTENSIONS.contains(normalizedSuffix)) {
            throw new IllegalArgumentException("仅支持 jpg、jpeg、png、gif 和 webp 格式的图片");
        }

        // 如果开启了 OSS 且注入了服务，直接使用 OSS
        if (ossEnabled && ossStorageService != null) {
            return ossStorageService.uploadImage(image);
        }

        // 降级为本地磁盘存储
        try {
            String datePath = LocalDate.now().format(DATE_PATH_FORMATTER);
            String fileName = buildOwnedFileName(normalizedSuffix);
            String relativePath = UPLOAD_DIR + "/" + datePath + "/" + fileName;

            Path targetPath = Paths.get(System.getProperty("user.dir"), relativePath);
            File parentDir = targetPath.getParent().toFile();
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            Files.copy(image.getInputStream(), targetPath);
            log.info("本地图片保存成功，路径: {}", targetPath.toAbsolutePath());
            return "/" + relativePath;
        } catch (IOException e) {
            log.error("本地图片存储异常", e);
            throw new IllegalStateException("图片上传存储失败", e);
        }
    }

    /**
     * 生成带归属标记的文件名：{userId}-{uuid}.{ext}
     *
     * 删除时只认文件名里的这个 userId，所以归属信息必须落在路径本身：
     * 图片在评价发布前就能被撤回，此时它还没进任何数据库记录，没有别的地方可查归属。
     */
    private String buildOwnedFileName(String normalizedSuffix) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new IllegalStateException("上传图片需要登录");
        }
        return user.getId() + "-" + UUID.randomUUID().toString().replace("-", "") + "." + normalizedSuffix;
    }

    /**
     * 校验图片归属：文件名前缀里的 userId 必须是当前调用者。
     *
     * 修复前这里只要求登录：图片 URL 通过评价接口公开可见，
     * 任何登录用户抓一遍别人的评价就能把他的图全删掉。
     */
    private boolean isOwnedByCurrentUser(String imageUrl) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return false;
        }
        String fileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
        int dash = fileName.indexOf('-');
        if (dash <= 0) {
            // 没有归属前缀：早于本次修复上传的历史文件，一律拒绝删除
            return false;
        }
        return fileName.substring(0, dash).equals(String.valueOf(user.getId()));
    }

    /**
     * 删除图片
     */
    public void deleteImage(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            return;
        }

        if (!isOwnedByCurrentUser(imageUrl)) {
            log.warn("拒绝删除非本人上传的图片, imageUrl={}, userId={}",
                    imageUrl, UserHolder.getUser() != null ? UserHolder.getUser().getId() : null);
            throw new BusinessException(403, "无权删除该图片");
        }

        if (ossEnabled && ossStorageService != null && imageUrl.startsWith("http")) {
            try {
                ossStorageService.deleteImage(imageUrl);
            } catch (Exception e) {
                log.warn("OSS 图片删除警告: {}", e.getMessage());
            }
            return;
        }

        // 本地图片删除：路径必须落在 uploads 目录内，否则拒绝
        try {
            String cleanPath = imageUrl.startsWith("/") ? imageUrl.substring(1) : imageUrl;
            Path uploadRoot = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR)
                    .toAbsolutePath().normalize();
            Path path = uploadRoot.resolve(
                    cleanPath.startsWith(UPLOAD_DIR + "/") ? cleanPath.substring(UPLOAD_DIR.length() + 1) : cleanPath
            ).toAbsolutePath().normalize();

            // normalize 之后仍不在 uploads 下，说明存在 ../ 穿越
            if (!path.startsWith(uploadRoot)) {
                log.warn("拒绝删除 uploads 目录外的文件: {}", imageUrl);
                return;
            }

            Files.deleteIfExists(path);
            log.info("本地图片删除成功: {}", path);
        } catch (Exception e) {
            log.warn("本地图片删除警告: {}", e.getMessage());
        }
    }
}
