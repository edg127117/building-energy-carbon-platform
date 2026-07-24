package com.platform;


import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台主启动类
 * @EnableAsync 和 @EnableScheduling 统一迁移到 AsyncConfig
 */
@MapperScan("com.platform.**.mapper")
@SpringBootApplication
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  能效碳效智慧管控平台启动成功   ლ(´ڡ`ლ)ﾞ");
        System.out.println("====== 底层基于 JDK 21 虚拟线程构建 ======");
    }
}