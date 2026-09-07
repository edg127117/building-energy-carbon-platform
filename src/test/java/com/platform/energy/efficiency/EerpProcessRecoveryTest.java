package com.platform.energy.efficiency;

import com.platform.config.TdengineConfig;
import com.platform.config.TdengineProperties;
import com.platform.energy.period.*;
import com.platform.framework.exception.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static com.platform.energy.efficiency.EerpServiceTest.*;
import static org.assertj.core.api.Assertions.*;

/** 只在一次性目标引擎运行：真正的子JVM竞争、强制终止及MySQL死锁，不以线程重建代替进程恢复。 */
@EnabledIfEnvironmentVariable(named="EERP_PROCESS_IT_ENABLED", matches="true")
public class EerpProcessRecoveryTest {
    @Test
    void recoversAfterJvmKillPartialWriteCompetingProcessAndMysqlDeadlock() throws Exception {
        assertThat(System.getenv("EERP_IT_ISOLATED")).isEqualTo("true");
        var mysql=mysql();
        assertThat(mysql.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(mysql.getDataSource()).locations("filesystem:src/env/init").load().migrate();
        var taos=taos(); String database="eerp_process_"+UUID.randomUUID().toString().replace("-","");
        taos.execute("CREATE DATABASE "+database+" KEEP 3650 DURATION 10d WAL_LEVEL 1");
        var properties=properties(database);
        ReflectionTestUtils.invokeMethod(new TdengineConfig(properties),"initializeEnergyPeriodSchema",taos);
        Path directory=Files.createTempDirectory(Path.of("target"),"eerp-process-");
        List<Process> processes=new ArrayList<>();
        try {
            var f=fixture(mysql);
            var config=f.activate(f.configuration(false)); f.baselineHour();
            f.snapshots=new NativePeriodSnapshotService(mysql,value -> {throw new IllegalStateException("synthetic initial write failure");},
                    new EnergyPeriodAuthorization(f.duties,f.scope),f.mapper); f.service=f.newService();
            var pending=f.period(config,START,START.plusSeconds(3600),"process-kill");
            assertThat(pending.status()).isEqualTo("FAILED");
            String frozen=f.repo.task(pending.taskId()).stage();
            Path marker=directory.resolve("partial-write.marker");
            Process first=start(directory,database,pending.taskId(),marker.toString()); processes.add(first);
            await(() -> Files.exists(marker),Duration.ofSeconds(30));
            assertThat(f.repo.task(pending.taskId()).status()).isEqualTo("RUNNING");
            assertThat(f.service.task(1,ROLES,pending.taskId()).result()).isNull();
            assertThat(mysql.queryForObject("SELECT COUNT(*) FROM energy_native_quantity_snapshot WHERE status='VISIBLE'",Integer.class)).isEqualTo(1);
            String oldToken=f.repo.task(pending.taskId()).lease();
            Process competitor=start(directory,database,pending.taskId(),"none"); processes.add(competitor);
            assertThat(competitor.waitFor(30,TimeUnit.SECONDS)).isTrue(); assertThat(competitor.exitValue()).isEqualTo(23);
            assertThat(f.repo.task(pending.taskId()).lease()).isEqualTo(oldToken);
            first.destroyForcibly(); assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
            Instant expiry=f.repo.task(pending.taskId()).leaseUntil();
            await(() -> Instant.now().isAfter(expiry),Duration.ofSeconds(50));
            Process resumed=start(directory,database,pending.taskId(),"none"); processes.add(resumed);
            assertThat(resumed.waitFor(30,TimeUnit.SECONDS)).isTrue(); assertThat(resumed.exitValue()).isZero();
            assertThat(f.repo.task(pending.taskId()).stage()).isEqualTo(frozen);
            assertThat(f.repo.task(pending.taskId()).status()).isEqualTo("SUCCEEDED");
            assertThat(f.repo.finish(pending.taskId(),oldToken,"SUCCEEDED",null)).isZero();
            assertThat(taos.queryForObject("SELECT COUNT(*) FROM "+database+".st_energy_period_result",Long.class)).isEqualTo(2);

            var deadlocked=f.period(config,START,START.plusSeconds(3600),"process-deadlock");
            assertThat(deadlocked.status()).isEqualTo("FAILED");
            // 外部事务持有任务行，执行进程持有执行互斥行；形成真实InnoDB等待环。
            try (var connection=mysql.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try (var statement=connection.createStatement()) {
                    statement.setQueryTimeout(15);
                    for(int i=0;i<100;i++) statement.executeUpdate("INSERT INTO energy_eerp_station_guard VALUES ('deadlock','weight"+i+"')");
                    statement.executeUpdate("UPDATE energy_eerp_task SET revision=revision WHERE task_id='"+deadlocked.taskId()+"'");
                    Process victim=start(directory,database,deadlocked.taskId(),"none"); processes.add(victim);
                    await(() -> mysql.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)>0,Duration.ofSeconds(30));
                    statement.executeQuery("SELECT guard_id FROM energy_eerp_execution_guard WHERE guard_id=1 FOR UPDATE").close();
                    assertThat(victim.waitFor(20,TimeUnit.SECONDS)).isTrue(); assertThat(victim.exitValue()).isEqualTo(24);
                } finally { connection.rollback(); }
            }
            assertThat(f.repo.task(deadlocked.taskId()).status()).isEqualTo("FAILED");
            Process retried=start(directory,database,deadlocked.taskId(),"none"); processes.add(retried);
            assertThat(retried.waitFor(30,TimeUnit.SECONDS)).isTrue(); assertThat(retried.exitValue()).isZero();
            assertThat(f.repo.task(deadlocked.taskId()).status()).isEqualTo("SUCCEEDED");
            assertThat(taos.queryForObject("SELECT COUNT(*) FROM "+database+".st_energy_period_result",Long.class)).isEqualTo(4);
            System.out.println("EERP_PROCESS_ACCEPTANCE competingProcess=REJECTED kill=FORCED partialWrite=RECOVERED frozenStage=UNCHANGED deadlock=MYSQL_1213 explicitRetry=SUCCEEDED");
        } finally {
            for(Process process:processes) if(process.isAlive()) {process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}
            taos.execute("DROP DATABASE "+database);
        }
    }

    /** 子进程只执行已固定stage；原始事实替身为空，意外重读会导致结果失败。 */
    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("EERP_IT_ISOLATED"))) throw new IllegalStateException("isolated only");
        var f=fixture(mysql());
        var store=new TdengineEnergyPeriodValueStore(taos(),properties(System.getenv("EERP_PROCESS_DATABASE")));
        AtomicInteger writes=new AtomicInteger();
        f.snapshots=new NativePeriodSnapshotService(f.jdbc,value -> {
            if(!"none".equals(args[1])&&writes.incrementAndGet()==2) {
                try {Files.writeString(Path.of(args[1]),"second-write-blocked");Thread.sleep(120000);}
                catch(Exception failure){throw new IllegalStateException(failure);}
            }
            store.write(value);
        },new EnergyPeriodAuthorization(f.duties,f.scope),f.mapper); f.service=f.newService();
        try {
            var result=f.service.execute(1,ROLES,args[0]);
            if(!"SUCCEEDED".equals(result.status())) System.exit(25);
            org.mockito.Mockito.verifyNoInteractions(f.reader);
        } catch(BusinessException conflict) {
            if("EERP_REVISION_CONFLICT".equals(conflict.getErrorCode())||"EERP_CAPACITY_EXCEEDED".equals(conflict.getErrorCode())) System.exit(23);
            throw conflict;
        } catch(DataAccessException failure) {
            Throwable cause=failure;
            while(cause!=null) {
                if(cause instanceof java.sql.SQLException sql&&sql.getErrorCode()==1213) {System.out.println("MYSQL_DEADLOCK_1213");System.exit(24);}
                cause=cause.getCause();
            }
            throw failure;
        }
    }

    private static EerpServiceTest fixture(JdbcTemplate mysql) {
        var f=new EerpServiceTest(); f.setup(); f.jdbc=mysql; f.repo=new EerpRepository(mysql); f.service=f.newService(); return f;
    }
    private static JdbcTemplate mysql() {return new JdbcTemplate(new DriverManagerDataSource(System.getenv("EERP_IT_MYSQL_URL"),System.getenv("EERP_IT_MYSQL_USER"),System.getenv("EERP_IT_MYSQL_PASSWORD")));}
    private static JdbcTemplate taos() {return new JdbcTemplate(new DriverManagerDataSource(System.getenv("EERP_IT_TDENGINE_URL"),System.getenv("EERP_IT_TDENGINE_USER"),System.getenv("EERP_IT_TDENGINE_PASSWORD")));}
    private static TdengineProperties properties(String database) {var p=new TdengineProperties();p.setDatabase(database);return p;}
    private static Process start(Path directory,String database,String task,String marker) throws Exception {
        List<String> arguments=List.of("-Xmx256m","-Duser.timezone=UTC","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),EerpProcessRecoveryTest.class.getName(),task,marker);
        Path argfile=directory.resolve(UUID.randomUUID()+".args");
        Files.write(argfile,arguments.stream().map(value -> "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"").toList());
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+argfile.toAbsolutePath());
        builder.environment().put("EERP_PROCESS_DATABASE",database);
        return builder.redirectErrorStream(true).redirectOutput(directory.resolve(UUID.randomUUID()+".log").toFile()).start();
    }
    private static void await(BooleanSupplier condition,Duration maximum) throws InterruptedException {
        long deadline=System.nanoTime()+maximum.toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline) Thread.sleep(100);
        assertThat(condition.getAsBoolean()).as("bounded condition within %s",maximum).isTrue();
    }
}
