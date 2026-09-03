package asia.creat.utils;

import asia.creat.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
public final class InterceptorErrorWriter {

    private InterceptorErrorWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, int status, String msg) {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            // 拦截器不会经过全局异常处理，这里直接写统一响应体。
            objectMapper.writeValue(response.getWriter(), Result.error(status, msg));
        } catch (IOException e) {
            log.warn("写出拦截器错误体失败, status={}", status, e);
        }
    }
}
