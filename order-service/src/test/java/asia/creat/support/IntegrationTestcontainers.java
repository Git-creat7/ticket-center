package asia.creat.support;

import asia.creat.client.CoreClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/** Shared infrastructure for Spring integration tests. */
@ActiveProfiles("testcontainers")
public abstract class IntegrationTestcontainers {

    @MockitoBean
    private TaskScheduler taskScheduler;

    @MockitoBean
    protected CoreClient coreClient;

    private static final String MYSQL_PASSWORD = "testcontainers";
    private static final String RABBITMQ_USERNAME = "test";
    private static final String RABBITMQ_PASSWORD = "test";

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("ticket_order")
            .withUsername("ticket_order")
            .withPassword(MYSQL_PASSWORD)
            // 建表 SQL 只在 deploy/ 维护一份，测试也从那里取。
            .withCopyFileToContainer(MountableFile.forHostPath("../deploy/mysql/02-order.sql"),
                    "/docker-entrypoint-initdb.d/init.sql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes");

    protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management")
            .withUser(RABBITMQ_USERNAME, RABBITMQ_PASSWORD)
            .withPermission("/", RABBITMQ_USERNAME, ".*", ".*", ".*");

    static {
        Startables.deepStart(MYSQL, REDIS, RABBITMQ).join();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> RABBITMQ_USERNAME);
        registry.add("spring.rabbitmq.password", () -> RABBITMQ_PASSWORD);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }
}
