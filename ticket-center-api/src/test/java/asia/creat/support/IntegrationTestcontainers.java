package asia.creat.support;

import asia.creat.client.OrderClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/** Shared infrastructure for Spring integration tests. */
@ActiveProfiles("testcontainers")
public abstract class IntegrationTestcontainers {

    @MockitoBean
    private TaskScheduler taskScheduler;

    @MockitoBean
    protected OrderClient orderClient;

    private static final String MYSQL_PASSWORD = "testcontainers";

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ticket_center")
            .withUsername("ticket_core")
            .withPassword(MYSQL_PASSWORD)
            // 建表 SQL 只在 deploy/ 维护一份，测试也从那里取。
            .withCopyFileToContainer(MountableFile.forHostPath("../deploy/mysql/01-ticket.sql"),
                    "/docker-entrypoint-initdb.d/init.sql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    static {
        Startables.deepStart(MYSQL, REDIS).join();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");


    }
}
