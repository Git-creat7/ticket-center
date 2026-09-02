package asia.creat.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;


@RequiredArgsConstructor
public class LoginInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 白名单判断挪进拦截器：MvcConfigurer 的 excludePathPatterns 表达不了 HTTP 方法，
        // 按路径放行 /event/* 等于放行该路径上的所有方法。
        // 现在拦截器注册在所有路径上，放行与否由 PublicEndpoints 按“方法 + 路径”决定。
        PathContainer path = ServletRequestPathUtils.getParsedRequestPath(request).pathWithinApplication();
        if (PublicEndpoints.isPublic(request.getMethod(), path)) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            InterceptorErrorWriter.write(response, objectMapper, 401, "未登录或登录已过期");
            return false;
        }
        return true;
    }
}
