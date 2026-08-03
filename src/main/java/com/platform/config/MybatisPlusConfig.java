package com.platform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 的 MySQL 查询插件装配。
 *
 * <p>当前只注册 MySQL 分页拦截器，供人员、建筑等 Mapper 分页查询生成正确方言 SQL。
 * TDengine 查询使用独立 JdbcTemplate/Repository，不经过该 MySQL 分页插件。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /** 创建带 MySQL 分页方言的统一拦截器，避免各业务 Mapper 手工拼接 limit/offset。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
