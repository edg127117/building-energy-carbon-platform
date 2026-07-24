package com.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置
 * 作用：允许前端分离部署（或直接双击HTML文件）时，跨域访问后端的 API
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")               // 允许跨域的路径
                .allowedOriginPatterns("*")      // 允许所有来源（测试阶段用 *）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的方法
                .allowedHeaders("*")             // 允许所有请求头
                .allowCredentials(true)          // 允许携带 Cookie/Token
                .maxAge(3600);                   // 预检请求的有效期
    }
}
