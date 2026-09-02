package asia.creat.utils;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 免鉴权端点白名单：必须同时匹配 HTTP 方法和路径。
 *
 * 原先这份名单是 MvcConfigurer 里的 excludePathPatterns，只按路径匹配。
 * 那样放行 /event/* 等于放行该路径上的所有方法 —— 当时恰好只有 GET 落在上面，
 * 但以后谁加一个 DELETE /event/{id}，它会默认免鉴权，而且不会有任何报错提示。
 * 把方法写进名单，新增的写接口默认需要登录，要公开必须显式加一条。
 */
public final class PublicEndpoints {

    private record Rule(HttpMethod method, PathPattern pattern) {
    }

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private static final List<Rule> RULES = List.of(
            // 登录链路：拿验证码、验证码登录、密码登录
            rule(HttpMethod.POST, "/user/code"),
            rule(HttpMethod.POST, "/user/login"),
            rule(HttpMethod.POST, "/user/login/password"),

            // 活动浏览
            rule(HttpMethod.GET, "/event-category/**"),
            rule(HttpMethod.GET, "/event/hot"),
            rule(HttpMethod.GET, "/event/of/category"),
            rule(HttpMethod.GET, "/event/nearby"),
            rule(HttpMethod.GET, "/event/{id}"),
            rule(HttpMethod.GET, "/event/uv/{id}"),
            rule(HttpMethod.GET, "/ticket/of/event/**"),

            // 想看人数上报：允许游客计入，按来源 IP 去重（见 EventServiceImpl#addUv）
            rule(HttpMethod.PUT, "/event/uv/{id}"),

            // 评价浏览
            rule(HttpMethod.GET, "/event-review/hot"),
            rule(HttpMethod.GET, "/event-review/{id}"),
            rule(HttpMethod.GET, "/event-review/{id}/comments"),
            rule(HttpMethod.GET, "/event-review/likes/{id}"),

            // 上传的图片本身是公开可读的
            rule(HttpMethod.GET, "/uploads/**")
    );

    private PublicEndpoints() {
    }

    private static Rule rule(HttpMethod method, String pattern) {
        return new Rule(method, PARSER.parse(pattern));
    }

    /**
     * @param method 请求方法；HEAD 按 GET 处理，Spring 会为 @GetMapping 自动支持 HEAD
     */
    public static boolean isPublic(String method, org.springframework.http.server.PathContainer path) {
        HttpMethod requested = HttpMethod.valueOf(method);
        if (HttpMethod.HEAD.equals(requested)) {
            requested = HttpMethod.GET;
        }
        for (Rule r : RULES) {
            if (r.method().equals(requested) && r.pattern().matches(path)) {
                return true;
            }
        }
        return false;
    }
}
