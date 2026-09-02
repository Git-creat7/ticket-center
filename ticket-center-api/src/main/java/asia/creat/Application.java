package asia.creat;

import jakarta.annotation.PostConstruct;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;


@EnableScheduling
@MapperScan("asia.creat.mapper")
@SpringBootApplication
public class Application {

    /**
     * 固定 JVM 默认时区。
     * Redis 缓存中的 LocalDateTime 由 Hutool 序列化为毫秒时间戳，转换依赖 JVM 默认时区；
     * 若不同实例时区不一致（如容器默认 UTC 与本机 Asia/Shanghai），读到同一份缓存会产生 8 小时偏差。
     */
    @PostConstruct
    void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
