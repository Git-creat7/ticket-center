package asia.creat.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 兜底，不是补漏：当前所有分页入口都过 @Validated PageQuery(@Max(100))，
        // 打不穿。这里防的是以后有人绕开 PageQuery 直接 new Page<>(1, size) 并把
        // size 接到请求参数上——那种写法没有校验层，一条 size=1000000 就能把整表
        // 拉进堆。maxLimit 在 SQL 生成阶段截断，与调用方怎么构造 Page 无关。
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
