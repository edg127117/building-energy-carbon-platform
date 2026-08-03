package com.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 浏览器跨域访问后端 API 的 MVC 配置。
 *
 * <p>当前允许任意来源模式、常用写操作和 {@code Authorization} 请求头，用于前后端分离部署。
 * {@code allowCredentials(true)} 表示浏览器可携带凭据类请求信息；实际登录态仍由 Bearer JWT
 * 请求头建立，不依赖跨域配置提供安全边界。接口认证和角色校验继续由 Spring Security 执行。</p>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    /**
     * 对全部 MVC 路径应用跨域规则，并把预检结果缓存一小时。
     * 这里不限制来源，因此部署到非受控网络前需要由网关或环境配置收紧允许域名。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")               // 覆盖全部 MVC API 路径。
                .allowedOriginPatterns("*")      // 当前部署允许任意浏览器来源发起跨域请求。
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")             // 包含 Authorization 和 Content-Type。
                .allowCredentials(true)
                .maxAge(3600);                   // 浏览器可复用 OPTIONS 预检结果一小时。
    }
}
