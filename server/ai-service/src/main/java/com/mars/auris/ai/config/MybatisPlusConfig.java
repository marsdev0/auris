package com.mars.auris.ai.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author geyan
 * @date 2026/9/12
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页拦截器:拦截 selectPage,把分页参数改写成物理 LIMIT + 自动发 COUNT
     * 没有它 selectPage 是查全表再内存截断的假分页(P3 §5)
     * @geyan  仔细研究下
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
