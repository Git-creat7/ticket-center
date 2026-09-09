package asia.creat.config;

import asia.creat.utils.InternalApiInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InternalApiConfig implements WebMvcConfigurer {

    private final String token;
    private final InternalApiInterceptor interceptor;

    public InternalApiConfig(@Value("${ticket.internal-token:}") String token, ObjectMapper objectMapper) {
        this.token = token;
        this.interceptor = new InternalApiInterceptor(token, objectMapper);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/internal/**").order(-1);
    }

    @Bean
    public RequestInterceptor internalFeignInterceptor() {
        return template -> template.header(InternalApiInterceptor.TOKEN_HEADER, token);
    }
}
