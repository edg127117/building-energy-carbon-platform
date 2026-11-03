package com.platform.carbon;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 必须显式指定一次性空MySQL实例；普通Maven测试不访问任何外部数据库。 */
@EnabledIfEnvironmentVariable(named="CARBON_PLAN_MYSQL_IT_URL",matches=".+")
class CarbonPlanMysqlIntegrationTest {
    @Test void migrationAndConcurrentVersionAllocationCasReplacementAndAuditRollback() throws Exception {
        assertThat(System.getenv("CARBON_PLAN_MYSQL_IT_ISOLATED")).isEqualTo("true");
        String url=System.getenv("CARBON_PLAN_MYSQL_IT_URL");assertThat(url).startsWith("jdbc:mysql://127.0.0.1:").contains("/iot_platform");
        var ds=new DriverManagerDataSource(url,"root","");var jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('iot_platform','iot_platform_archive')",Integer.class)).isZero();
        Flyway.configure().dataSource(ds).locations("filesystem:src/env/init").target("93").load().migrate();
        var migration=Flyway.configure().dataSource(ds).locations("filesystem:src/env/init").target("96").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(3);assertThat(migration.migrate().migrationsExecuted).isZero();
        var repository=new CarbonPlanRepository(jdbc);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->allocate(gate,tx,repository,"a"));var b=pool.submit(()->allocate(gate,tx,repository,"b"));gate.countDown();
            a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        } finally { pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
        assertThat(repository.versions("BLD001",2026)).extracting(CarbonPlanRepository.Row::number).containsExactly(2,1);
        var row=repository.versions("BLD001",2026).getFirst();
        assertThat(repository.save(row,999,"{}","stale")).isZero();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{ repository.lock("BLD001",2026);assertThat(repository.save(row,0,"{\"rollback\":true}","rollback")).isOne();throw new IllegalStateException("audit-failure"); })).hasMessageContaining("audit-failure");
        assertThat(repository.find("BLD001",row.id()).revision()).isZero();assertThat(repository.find("BLD001",row.id()).json()).isEqualTo("{}");
        assertThat(repository.transition(row,0,"DRAFT","PENDING_REVIEW",null,"submit")).isOne();
        assertThat(repository.transition(row,1,"PENDING_REVIEW","APPROVED",2L,"review")).isOne();
        tx.executeWithoutResult(s->{repository.lock("BLD001",2026);assertThat(repository.activate(row,2,3,"activate")).isOne();});
        assertThat(repository.active("BLD001",2026).id()).isEqualTo(row.id());
        var old=repository.versions("BLD001",2026).getLast();
        repository.transition(old,0,"DRAFT","APPROVED",2L,"fixture");
        tx.executeWithoutResult(s->{repository.lock("BLD001",2026);assertThat(repository.activate(old,1,3,"replace")).isOne();});
        assertThat(repository.find("BLD001",row.id()).status()).isEqualTo("RETIRED");assertThat(repository.active("BLD001",2026).id()).isEqualTo(old.id());
        concurrentRevisionHasExactlyOneWinner(tx,repository);
        concurrentServiceCreationRejectsOldSnapshotAfterScopeLock(tx,repository);
        effectiveFormalSelectionIgnoresRecentNoiseAndSupersededOrCandidateResults(jdbc);
    }
    private void concurrentServiceCreationRejectsOldSnapshotAfterScopeLock(TransactionTemplate tx,CarbonPlanRepository repository) throws Exception {
        var authorization=org.mockito.Mockito.mock(CarbonAuthorization.class);
        var properties=new com.platform.audit.AuditGovernanceProperties();properties.setEnvironmentMode(com.platform.audit.AuditEnvironmentMode.TEST);
        var service=new CarbonPlanService(repository,org.mockito.Mockito.mock(CarbonCalculationRepository.class),authorization,new CarbonPlanCore(),new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),org.mockito.Mockito.mock(com.platform.audit.AuditEvidenceWriter.class),properties);
        var snapshots=new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(call->{snapshots.countDown();if(!snapshots.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("snapshot barrier");return null;}).when(authorization).requireRuleMaintainer(1,java.util.List.of("PLATFORM_ADMIN"),"BLD001");
        var workers=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> create=()->{
                try {tx.execute(s->service.create(1,java.util.List.of("PLATFORM_ADMIN"),"BLD001",new CarbonPlanContracts.Create(2028,null,"isolated concurrency test")));return 200;}
                catch(com.platform.framework.exception.BusinessException conflict) {return conflict.getCode();}
            };
            var a=workers.submit(create);var b=workers.submit(create);
            assertThat(java.util.List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(repository.versions("BLD001",2028)).hasSize(1);
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private void concurrentRevisionHasExactlyOneWinner(TransactionTemplate tx,CarbonPlanRepository repository) throws Exception {
        tx.executeWithoutResult(s->{repository.lock("BLD001",2027);repository.insert("concurrent-revision","BLD001",2027,"{}",null,1,"concurrent test");});
        var row=repository.find("BLD001","concurrent-revision");var gate=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> save=()->{ if(!gate.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");return tx.execute(s->{repository.lock("BLD001",2027);return repository.save(row,0,"{\"updated\":true}","concurrent CAS");}); };
            var first=workers.submit(save);var second=workers.submit(save);gate.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)+second.get(15,TimeUnit.SECONDS)).isOne();
            assertThat(repository.find("BLD001",row.id()).revision()).isOne();
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private void effectiveFormalSelectionIgnoresRecentNoiseAndSupersededOrCandidateResults(JdbcTemplate jdbc) {
        for(int n=0;n<110;n++) batch(jdbc,"noise-"+n,"2024-02-01 00:00:00","2024-03-01 00:00:00","DIRECT");
        batch(jdbc,"plan-old","2023-12-31 16:00:00","2024-01-31 16:00:00","DIRECT");
        batch(jdbc,"plan-current","2023-12-31 16:00:00","2024-01-31 16:00:00","PUBLISHED");
        batch(jdbc,"plan-candidate","2023-12-31 16:00:00","2024-01-31 16:00:00","PUBLISHED");
        jdbc.update("INSERT INTO biz_carbon_result_relation(relation_id,building_id,old_calculation_batch_id,new_calculation_batch_id,relation_status) VALUES ('plan-replaced','BLD001','plan-old','plan-current','SUPERSEDED'),('plan-pending','BLD001','plan-current','plan-candidate','CANDIDATE')");
        // 旧批次和未批准候选即使完成时间更新，也不能覆盖有效替代批次。
        jdbc.update("UPDATE biz_carbon_calculation_batch SET completed_at='2026-01-01 00:00:00' WHERE calculation_batch_id IN ('plan-old','plan-candidate')");
        var repo=new CarbonCalculationRepository(jdbc);
        var start=java.time.Instant.parse("2023-12-31T16:00:00Z");var end=java.time.Instant.parse("2024-01-31T16:00:00Z");
        assertThat(repo.findCurrentFormal("BLD001",CarbonModels.PeriodType.MONTH,start,end,"Asia/Shanghai").batchId()).isEqualTo("plan-current");
        assertThat(repo.findCurrentFormal("BLD001",CarbonModels.PeriodType.MONTH,start,end,"UTC")).isNull();
    }
    private void batch(JdbcTemplate jdbc,String id,String start,String end,String publication) {
        // 与生产持久化使用相同的Instant绑定，避免字符串夹具暗中依赖连接时区为UTC。
        jdbc.update("""
                INSERT INTO biz_carbon_calculation_batch(calculation_batch_id,building_id,period_type,period_start,period_end,timezone_id,result_nature,publication_status,status,idempotency_key,request_hash,rounding_policy_version_id,started_at,deadline_at,completed_at,created_by)
                VALUES (?,'BLD001','MONTH',?,?,'Asia/Shanghai','FORMAL',?,'COMPLETED_COMPLETE',?,'test-hash','CRP_DECIMAL128_V1','2024-03-01 00:00:00','2024-03-01 01:00:00','2024-03-01 00:00:01',1)
                """,id,java.sql.Timestamp.from(java.time.Instant.parse(start.replace(' ','T')+"Z")),
                java.sql.Timestamp.from(java.time.Instant.parse(end.replace(' ','T')+"Z")),publication,id);
    }
    private void allocate(CountDownLatch gate,TransactionTemplate tx,CarbonPlanRepository repository,String id) {
        try { if(!gate.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");tx.executeWithoutResult(s->{repository.lock("BLD001",2026);repository.insert(id,"BLD001",2026,"{}",null,1,"isolated mysql fixture");}); }
        catch(InterruptedException exception) { Thread.currentThread().interrupt();throw new IllegalStateException(exception); }
    }
}
