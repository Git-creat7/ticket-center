package asia.creat.auth;

import asia.creat.config.InternalApiConfig;
import asia.creat.utils.InternalApiInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InternalApiConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void missingOrBlankCredentialPreventsStartup() {
        contextRunner.run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues("ticket.internal-token= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void feignUsesConfiguredCredential() {
        contextRunner.withPropertyValues("ticket.internal-token=test-service-token").run(context -> {
            assertThat(context).hasNotFailed();
            RequestTemplate template = new RequestTemplate();
            context.getBean(RequestInterceptor.class).apply(template);
            assertThat(template.headers().get(InternalApiInterceptor.TOKEN_HEADER))
                    .containsExactly("test-service-token");
        });
    }
}
