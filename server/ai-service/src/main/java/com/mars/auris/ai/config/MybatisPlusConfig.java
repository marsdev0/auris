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
     * Pagination interceptor: intercepts selectPage, rewrites pagination into physical LIMIT + automatic COUNT
     * Without it selectPage is fake pagination — full-table query then in-memory slicing (P3 §5)
     * @geyan  study this carefully
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
