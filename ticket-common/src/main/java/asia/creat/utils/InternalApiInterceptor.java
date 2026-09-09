package asia.creat.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.Assert;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class InternalApiInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Ticket-Internal-Token";
    public static final String VERIFIED_ATTRIBUTE = InternalApiInterceptor.class.getName() + ".verified";

    private final byte[] token;
    private final ObjectMapper objectMapper;

    public InternalApiInterceptor(String token, ObjectMapper objectMapper) {
        Assert.hasText(token, "请配置 TICKET_INTERNAL_TOKEN");
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String supplied = request.getHeader(TOKEN_HEADER);
        if (supplied == null || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8))) {
            InterceptorErrorWriter.write(response, objectMapper, 403, "禁止访问内部接口");
            return false;
        }
        request.setAttribute(VERIFIED_ATTRIBUTE, true);
        return true;
    }
}
