package asia.creat.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean
    public RedissonClient redissonClient(RedisProperties properties) {
        Config config = new Config();
        RedisProperties.Sentinel sentinel = properties.getSentinel();

        if (sentinel != null
                && StringUtils.hasText(sentinel.getMaster())
                && sentinel.getNodes() != null
                && !sentinel.getNodes().isEmpty()) {
            SentinelServersConfig server = config.useSentinelServers()
                    .setMasterName(sentinel.getMaster())
                    .addSentinelAddress(sentinel.getNodes().stream()
                            .map(RedisConfig::redisAddress)
                            .toArray(String[]::new))
                    .setReadMode(ReadMode.MASTER)
                    .setMasterConnectionPoolSize(32)
                    .setMasterConnectionMinimumIdleSize(8)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(300);
            applyCredentials(server, properties, sentinel);
        } else {
            SingleServerConfig server = config.useSingleServer()
                    .setAddress(redisAddress(properties.getHost() + ":" + properties.getPort()))
                    .setConnectionPoolSize(32)
                    .setConnectionMinimumIdleSize(8)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(300);
            if (StringUtils.hasText(properties.getPassword())) {
                server.setPassword(properties.getPassword());
            }
        }
        return Redisson.create(config);
    }

    private static void applyCredentials(
            SentinelServersConfig server,
            RedisProperties properties,
            RedisProperties.Sentinel sentinel) {
        if (StringUtils.hasText(properties.getUsername())) {
            server.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            server.setPassword(properties.getPassword());
        }
        if (StringUtils.hasText(sentinel.getUsername())) {
            server.setSentinelUsername(sentinel.getUsername());
        }
        String sentinelPassword = StringUtils.hasText(sentinel.getPassword())
                ? sentinel.getPassword()
                : properties.getPassword();
        if (StringUtils.hasText(sentinelPassword)) {
            server.setSentinelPassword(sentinelPassword);
        }
    }

    private static String redisAddress(String address) {
        return address.startsWith("redis://") || address.startsWith("rediss://")
                ? address
                : "redis://" + address;
    }
}
