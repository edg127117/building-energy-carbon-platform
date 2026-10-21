package com.platform.carbon;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** 仅在显式确认的一次性空 MySQL 实例执行；普通测试不连接外部数据库。 */
@EnabledIfEnvironmentVariable(named = "CARBON_PROJECT_MYSQL_IT_URL", matches = ".+")
class CarbonProjectMysqlIntegrationTest {
    @Test void upgradesV76ToV77AndVerifiesIdempotencyRollbackAndConcurrentAllocation() throws Exception {
        assertThat(System.getenv("CARBON_PROJECT_MYSQL_IT_ISOLATED")).isEqualTo("true");
        String url = System.getenv("CARBON_PROJECT_MYSQL_IT_URL");
        assertThat(url).contains("/iot_platform");
        var dataSource = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("CARBON_PROJECT_MYSQL_IT_USER", "root"),
                System.getenv().getOrDefault("CARBON_PROJECT_MYSQL_IT_PASSWORD", ""));
        var jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("iot_platform");
        // 旧迁移固定切换到这两个库，不能只检查 URL 所选的另一个空库。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('iot_platform','iot_platform_archive')", Integer.class))
                .as("专项入口只接受一次性空实例内的项目库及归档库，不修改已有业务库").isZero();
        Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").target("76").load().migrate();
        int equipment = jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Integer.class);
        int points = jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class);
        int factors = jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_factor", Integer.class);
        var flyway = Flyway.configure().dataSource(dataSource).locations("filesystem:src/env/init").target("77").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_equipment", Integer.class)).isEqualTo(equipment);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point", Integer.class)).isEqualTo(points);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_factor", Integer.class)).isEqualTo(factors);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM biz_carbon_project_version", Integer.class)).isZero();

        var repository = new CarbonProjectRepository(jdbc);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> allocate(start, transactions, repository, "mysql-one"));
            var second = workers.submit(() -> allocate(start, transactions, repository, "mysql-two"));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(repository.versions("BLD001")).extracting(CarbonProjectRepository.Row::number).containsExactly(2, 1);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        var row = repository.versions("BLD001").getFirst();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            repository.lock("BLD001");
            assertThat(repository.save(row, row.revision(), "{\"marker\":\"rollback\"}", "rollback")).isEqualTo(1);
            throw new IllegalStateException("injected-rollback");
        })).hasMessageContaining("injected-rollback");
        assertThat(repository.versions("BLD001").getFirst().revision()).isEqualTo(row.revision());
        assertThat(repository.versions("BLD001").getFirst().json()).isEqualTo(row.json());
        assertThat(repository.save(row, 999, "{}", "conflict")).isZero();
    }

    private static void allocate(CountDownLatch start, TransactionTemplate transactions,
                                 CarbonProjectRepository repository, String id) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("worker-start-timeout");
            transactions.executeWithoutResult(status -> {
                repository.lock("BLD001");
                repository.insert(id, "BLD001", "{\"classificationVersionId\":null,\"energies\":[],\"exclusions\":[]}", "隔离锁验证", 1);
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
