package asia.creat.utils;

import asia.creat.dto.UserDTO;
import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || handlerMethod.getMethodAnnotation(RequireAdmin.class) == null) {
            return true;
        }

        // 角色从数据库读取，提权或降权立即生效。
        UserDTO current = UserHolder.getUser();
        if (current == null || current.getId() == null) {
            InterceptorErrorWriter.write(response, objectMapper, 401, "未登录或登录已过期");
            return false;
        }

        User user = userMapper.selectById(current.getId());
        if (user == null || user.getRole() == null || user.getRole() != 1) {
            log.warn("拒绝非管理员访问管理接口, userId={}, uri={} {}",
                    current.getId(), request.getMethod(), request.getRequestURI());
            InterceptorErrorWriter.write(response, objectMapper, 403, "需要管理员权限");
            return false;
        }
        return true;
    }
}
