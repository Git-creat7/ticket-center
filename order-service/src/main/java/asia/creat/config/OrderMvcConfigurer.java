package asia.creat.config;

import asia.creat.client.CoreClient;
import asia.creat.utils.InterceptorErrorWriter;
import asia.creat.utils.LoginInterceptor;
import asia.creat.utils.RefreshTokenInterceptor;
import asia.creat.utils.RequireAdmin;
import asia.creat.utils.UserHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class OrderMvcConfigurer implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<CoreClient> coreClient;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);
        registry.addInterceptor(new LoginInterceptor(objectMapper)).order(1);
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!(handler instanceof HandlerMethod method)
                        || method.getMethodAnnotation(RequireAdmin.class) == null) {
                    return true;
                }
                if (!coreClient.getObject().isAdmin(UserHolder.getUser().getId())) {
                    InterceptorErrorWriter.write(response, objectMapper, 403, "需要管理员权限");
                    return false;
                }
                return true;
            }
        }).order(2);
    }
}
