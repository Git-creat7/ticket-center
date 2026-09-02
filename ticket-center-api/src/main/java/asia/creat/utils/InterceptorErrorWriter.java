package asia.creat.utils;

import asia.creat.common.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 给拦截器写统一错误体。
 *
 * 拦截器 return false 时请求不会进入 Controller，也不经过 {@code GlobalExceptionHandler}，
 * 所以响应体得手写。原先只 {@code setStatus(401)}，body 是空的：
 * curl / Postman / 自动化测试只能看到一个裸状态码，不知道是没登录还是没权限。
 *
 * 状态码保留真实的 401/403，body 用和全站一致的 {@code {code,msg,data}} 信封——
 * 与 {@code GlobalExceptionHandler#handleNoResourceFound} 同一个路子（真实状态码 + Result 体）。
 */
@Slf4j
public final class InterceptorErrorWriter {

    private InterceptorErrorWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, int status, String msg) {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            // body.code 与 HTTP 状态码取同一个值：这两处本就同源，分开取值只会让调用方犯疑
            objectMapper.writeValue(response.getWriter(), Result.error(status, msg));
        } catch (IOException e) {
            // 客户端提前断开等 IO 失败：状态码已经写出，这里只记不抛，
            // 抛出去会被 Exception 兜底改写成 500，把真正的 401/403 盖掉
            log.warn("写出拦截器错误体失败, status={}", status, e);
        }
    }
}
