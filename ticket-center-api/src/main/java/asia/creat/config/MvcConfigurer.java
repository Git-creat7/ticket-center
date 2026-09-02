package asia.creat.config;

import asia.creat.mapper.UserMapper;
import asia.creat.utils.AdminInterceptor;
import asia.creat.utils.LoginInterceptor;
import asia.creat.utils.RefreshTokenInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class MvcConfigurer implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public MvcConfigurer(StringRedisTemplate stringRedisTemplate, UserMapper userMapper, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器：注册在所有路径上，放行名单见 PublicEndpoints（按“方法 + 路径”匹配）。
        registry.addInterceptor(new LoginInterceptor(objectMapper)).order(1);

        // Token 刷新拦截器：对所有请求尝试解析 Token 并刷新 TTL
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);

        // 管理员拦截器：必须排在登录之后，靠 @RequireAdmin 注解定位接口而非路径
        registry.addInterceptor(new AdminInterceptor(userMapper, objectMapper)).order(2);
    }

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        // 映射本地上传的静态图片文件目录
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}
