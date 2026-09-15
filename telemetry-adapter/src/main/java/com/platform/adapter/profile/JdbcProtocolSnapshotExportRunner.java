package com.platform.adapter.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** 显式启用后执行一次只读 JDBC 迁移导出并关闭进程。 */
@Component
@ConditionalOnProperty(
        prefix = "adapter.profile",
        name = "export-enabled",
        havingValue = "true")
public class JdbcProtocolSnapshotExportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JdbcProtocolSnapshotExportRunner.class);

    private final JdbcProtocolSnapshotExporter exporter;
    private final AdapterProfileProperties properties;
    private final ConfigurableApplicationContext context;

    public JdbcProtocolSnapshotExportRunner(
            JdbcProtocolSnapshotExporter exporter,
            AdapterProfileProperties properties,
            ConfigurableApplicationContext context) {
        this.exporter = exporter;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!"jdbc".equalsIgnoreCase(properties.getMode())) {
            throw new IllegalArgumentException("只读迁移导出必须使用adapter.profile.mode=jdbc");
        }
        JdbcProtocolSnapshotExporter.ExportResult result = exporter.export(
                properties.getExportFile(), properties.getOutputVersion());
        log.info("协议迁移导出完成: activeProfiles={}, archivedEntries={}",
                result.activeProfiles(), result.archivedEntries());
        SpringApplication.exit(context, () -> 0);
    }
}
