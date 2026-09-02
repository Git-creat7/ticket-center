package asia.creat.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记只允许管理员调用的接口，由 {@link AdminInterceptor} 拦截校验。
 *
 * 用注解而不是在 MvcConfigurer 里配路径：路径匹配区分不了 HTTP 方法，
 * /event 上 GET 是公开的、POST/PUT 只能管理员，按路径配会把两者一起拦住。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
