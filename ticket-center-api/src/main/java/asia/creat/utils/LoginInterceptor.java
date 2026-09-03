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
        // 白名单同时匹配请求方法和路径。
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
