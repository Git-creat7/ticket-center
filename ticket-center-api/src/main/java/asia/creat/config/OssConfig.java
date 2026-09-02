package asia.creat.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.Credentials;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.DefaultCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(prefix = "ticket.oss", name = "enabled", havingValue = "true")
public class OssConfig {

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(
            @Value("${ticket.oss.endpoint}") String endpoint,
            @Value("${ticket.oss.access-key-id:}") String accessKeyId,
            @Value("${ticket.oss.access-key-secret:}") String accessKeySecret,
            @Value("${ticket.oss.security-token:}") String securityToken,
            @Value("${ticket.oss.cli-profile:}") String cliProfile,
            ObjectMapper objectMapper) {
        Credentials credentials = resolveCredentials(
                accessKeyId, accessKeySecret, securityToken, cliProfile, objectMapper
        );
        return new OSSClientBuilder().build(endpoint, new DefaultCredentialProvider(credentials));
    }

    private Credentials resolveCredentials(
            String accessKeyId,
            String accessKeySecret,
            String securityToken,
            String cliProfile,
            ObjectMapper objectMapper) {
        boolean hasAccessKeyId = StringUtils.hasText(accessKeyId);
        boolean hasAccessKeySecret = StringUtils.hasText(accessKeySecret);
        if (hasAccessKeyId != hasAccessKeySecret) {
            throw new IllegalStateException("OSS AccessKey ID and secret must be configured together");
        }
        if (hasAccessKeyId) {
            return createCredentials(accessKeyId, accessKeySecret, securityToken);
        }

        // Local development can reuse the active Alibaba Cloud CLI profile.
        return loadCliCredentials(cliProfile, objectMapper);
    }

    private Credentials loadCliCredentials(String configuredProfile, ObjectMapper objectMapper) {
        Path configFile = Path.of(System.getProperty("user.home"), ".aliyun", "config.json");
        if (!Files.isRegularFile(configFile)) {
            throw new IllegalStateException(
                    "OSS credentials are missing. Configure environment variables or Alibaba Cloud CLI"
            );
        }

        try (InputStream inputStream = Files.newInputStream(configFile)) {
            JsonNode root = objectMapper.readTree(inputStream);
            String profileName = StringUtils.hasText(configuredProfile)
                    ? configuredProfile
                    : root.path("current").asText("default");

            for (JsonNode profile : root.path("profiles")) {
                if (!profileName.equals(profile.path("name").asText())) {
                    continue;
                }
                if (!"AK".equalsIgnoreCase(profile.path("mode").asText())) {
                    throw new IllegalStateException("Only an AK Alibaba Cloud CLI profile is supported locally");
                }
                return createCredentials(
                        profile.path("access_key_id").asText(),
                        profile.path("access_key_secret").asText(),
                        profile.path("sts_token").asText()
                );
            }
            throw new IllegalStateException("Alibaba Cloud CLI profile not found: " + profileName);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Alibaba Cloud CLI credentials", e);
        }
    }

    private Credentials createCredentials(String accessKeyId, String accessKeySecret, String securityToken) {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new IllegalStateException("OSS credentials are empty");
        }
        if (StringUtils.hasText(securityToken)) {
            return new DefaultCredentials(accessKeyId, accessKeySecret, securityToken);
        }
        return new DefaultCredentials(accessKeyId, accessKeySecret);
    }
}
