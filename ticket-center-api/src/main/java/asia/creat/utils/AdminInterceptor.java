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

/**
 * 校验 {@link RequireAdmin} 标记的接口是否由管理员调用。
 *
 * 角色每次都从 MySQL 读，不放进登录 Token：
 * 放 Token 里意味着提权/降权要等用户重新登录才生效，而降权不生效是安全问题。
 * 管理端接口是低频写操作（建活动、加票档），多一次主键查询无所谓。
 */
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

        // 走到这里 LoginInterceptor(order=1) 已经放行，UserHolder 必然有值；
        // 仍判一次 null，避免以后有人把这个拦截器的 order 调到登录之前。
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
