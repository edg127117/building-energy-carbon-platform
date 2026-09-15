# 共用协议解析源码

平台的协议预览与 `telemetry-adapter` 编译同一份无 I/O 解析源码，保留原 Java 包名和 V2 输出结构。
两个 Maven 入口通过 `build-helper-maven-plugin` 引入此目录，仍分别使用根目录和适配器原有构建命令；不是新服务，也不需要先安装本地 SNAPSHOT 依赖。

这里只包含解析器、异常及不可变协议/报文模型，不依赖 Spring、JDBC、MQTT 或平台设备归属。
Spring Bean 装配保留在适配器中；预览调用方负责有界请求、权限和临时样例生命周期。
构建时需保留仓库的 `protocol-core` 相邻目录，不能只复制适配器子目录。
