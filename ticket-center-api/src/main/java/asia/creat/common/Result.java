package asia.creat.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。
 *
 * <p><b>错误约定：业务错误一律 HTTP 200 + body.code</b>。{@code Result.error(404, ...)}
 * 里的 404 只是响应体里的业务码，HTTP 状态码仍是 200，前端 http.ts 的响应拦截器
 * 按 body.code 分流。
 *
 * <p>真实 HTTP 状态码只用在两类"到不了业务逻辑"的场合，这两类同样带 {@code Result} 体：
 * <ul>
 *   <li>拦截器鉴权失败 —— {@code LoginInterceptor} 401、{@code AdminInterceptor} 403，
 *       响应体由 {@code InterceptorErrorWriter} 写出，{@code body.code} 与状态码同值；</li>
 *   <li>静态资源不存在 —— 见 {@code GlobalExceptionHandler#handleNoResourceFound}，
 *       浏览器的 img onerror 需要真实状态码。</li>
 * </ul>
 *
 * <p>不要给业务接口加真实的非 2xx 状态码：前端错误分支只在 2xx 上读 body，
 * 非 2xx 会走 axios 的 AxiosError，msg 被替换成 "Request failed with status code xxx"，
 * 后端精心写的错误文案全部丢失。要改就得同时改 http.ts 的错误分支。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private int code;
    private String msg;
    private Object data;

    public static Result success() {
        Result res = new Result();
        res.code = 200;
        res.msg = "success";
        return res;
    }

    public static Result success(Object data) {
        Result res = new Result();
        res.code = 200;
        res.msg = "success";
        res.data = data;
        return res;
    }

    public static Result error(String msg) {
        Result res = new Result();
        res.code = 500;
        res.msg = msg;
        return res;
    }

    public static Result error(int code, String msg) {
        Result res = new Result();
        res.code = code;
        res.msg = msg;
        return res;
    }
}
