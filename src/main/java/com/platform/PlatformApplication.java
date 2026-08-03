package com.platform;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 能效管控平台的 Spring Boot 组合根。
 *
 * <p>这里只负责启动组件扫描和统一发现各业务模块的 MyBatis Mapper；异步、定时任务以及
 * MySQL、TDengine、MQTT 等外部资源均由各自配置类装配，本类不承担业务初始化。</p>
 */
@MapperScan("com.platform.**.mapper")
@SpringBootApplication
public class PlatformApplication {

    /** 启动 Spring 容器；启动阶段的外部资源行为由对应配置开关决定。 */
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  能效碳效智慧管控平台启动成功   ლ(´ڡ`ლ)ﾞ");
        System.out.println("====== 底层基于 JDK 21 虚拟线程构建 ======");
    }
}
