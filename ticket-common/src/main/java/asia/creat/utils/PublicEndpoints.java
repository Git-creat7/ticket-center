package asia.creat.utils;

import org.springframework.http.HttpMethod;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

public final class PublicEndpoints {

    private record Rule(HttpMethod method, PathPattern pattern) {
    }

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    // 公开接口按 HTTP 方法和路径匹配。
    private static final List<Rule> RULES = List.of(
            rule(HttpMethod.POST, "/user/code"),
            rule(HttpMethod.POST, "/user/login"),
            rule(HttpMethod.POST, "/user/login/password"),

            rule(HttpMethod.GET, "/event-category/**"),
            rule(HttpMethod.GET, "/event/hot"),
            rule(HttpMethod.GET, "/event/of/category"),
            rule(HttpMethod.GET, "/event/nearby"),
            rule(HttpMethod.GET, "/event/{id}"),
            rule(HttpMethod.GET, "/event/uv/{id}"),
            rule(HttpMethod.GET, "/ticket/of/event/**"),

            rule(HttpMethod.PUT, "/event/uv/{id}"),

            rule(HttpMethod.GET, "/event-review/hot"),
            rule(HttpMethod.GET, "/event-review/{id}"),
            rule(HttpMethod.GET, "/event-review/{id}/comments"),
            rule(HttpMethod.GET, "/event-review/likes/{id}"),

            rule(HttpMethod.GET, "/uploads/**")
    );

    private PublicEndpoints() {
    }

    private static Rule rule(HttpMethod method, String pattern) {
        return new Rule(method, PARSER.parse(pattern));
    }

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
