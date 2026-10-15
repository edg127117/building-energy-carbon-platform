package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import com.platform.audit.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.platform.iot.daikin.control.DaikinBatchContracts.*;
import static com.platform.iot.daikin.control.DaikinControlContracts.*;
import static org.assertj.core.api.Assertions.*;

/** 显式启用的一次性 MySQL 验收；子 JVM 仅使用共享 SQL 厂家替身，不启动应用或真实网关。 */
@EnabledIfEnvironmentVariable(named="DAIKIN_BATCH_MYSQL_ISOLATED", matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DaikinBatchMysqlAcceptanceTest {
    private static JdbcTemplate db;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long USER = 7;
    private static final List<String> CONTROL_TABLES = List.of("biz_daikin_control_grant", "biz_daikin_control_operation",
            "biz_daikin_control_slot", "biz_daikin_control_source", "biz_daikin_control_event",
            "biz_daikin_control_controller_slot", "biz_daikin_control_sample");

    private static JdbcTemplate isolatedDatabase() throws Exception {
        assertThat(System.getenv("DAIKIN_BATCH_MYSQL_ISOLATED")).isEqualTo("true");
        String url = System.getenv("DAIKIN_BATCH_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iot_platform\\?.+");
        var pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(System.getenv("DAIKIN_BATCH_MYSQL_USER"));
        pool.setPassword(System.getenv("DAIKIN_BATCH_MYSQL_PASSWORD"));
        pool.setMaximumPoolSize(8);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(10_000);
        var jdbc = new JdbcTemplate(pool);
        assertThat(jdbc.queryForObject("SELECT @@server_uuid", String.class))
                .isEqualTo(System.getenv("DAIKIN_BATCH_MYSQL_SERVER_UUID"));
        Path expected = Path.of(System.getenv("DAIKIN_BATCH_MYSQL_DATADIR")).toRealPath();
        Path actual = Path.of(jdbc.queryForObject("SELECT @@datadir", String.class)).toRealPath();
        assertThat(actual).isEqualTo(expected);
        Path root = Path.of("target").toRealPath();
        assertThat(actual.startsWith(root)).isTrue();
        assertThat(actual.getParent().getFileName().toString()).startsWith("daikin-batch-mysql-");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("iot_platform");
        return jdbc;
    }

    @AfterAll static void closeDatabase() { if(db!=null) ((HikariDataSource)db.getDataSource()).close(); }

    @BeforeAll static void prepare() throws Exception {
        db = isolatedDatabase();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        Flyway.configure().dataSource(db.getDataSource()).locations("filesystem:src/env/init").target("69").load().migrate();
        db.execute("CREATE TABLE acceptance_daikin_unit(equipment_id VARCHAR(32) PRIMARY KEY, unit_json LONGTEXT NOT NULL, behavior VARCHAR(20) NOT NULL DEFAULT 'NORMAL')");
        db.execute("CREATE TABLE acceptance_daikin_send(seq BIGINT AUTO_INCREMENT PRIMARY KEY, equipment_id VARCHAR(32), controller_key CHAR(64), action VARCHAR(24), value VARCHAR(40), process_id BIGINT, sent_at BIGINT)");
        db.execute("CREATE TABLE acceptance_daikin_worker(worker_id VARCHAR(64) PRIMARY KEY, process_id BIGINT NOT NULL, released BOOLEAN NOT NULL DEFAULT FALSE)");
        db.update("INSERT INTO sys_user(id,username,password,status,del_flag) VALUES (7,'batch-acceptance','not-a-login-secret',1,0)");
        db.update("INSERT INTO building(building_id,building_name,building_type,total_gfa,climate_zone,region_code) VALUES ('BLD001_ACCEPT','隔离验收建筑','办公',100,'温和','330100')");
        db.update("INSERT INTO biz_space(space_id,building_id,space_name,space_type) VALUES ('acceptance-space','BLD001_ACCEPT','隔离验收空间','ROOM')");
        db.update("INSERT INTO biz_system_group(system_group_id,system_group_code,building_id,system_type,system_group_name) VALUES ('acceptance-system','ACCEPTANCE','BLD001_ACCEPT','HVAC','隔离验收系统')");
        db.update("UPDATE biz_device_product SET status='ENABLED' WHERE product_id='PRODUCT_DAIKIN_IN_STATE'");
    }

    @Test @Order(1) void originalMigrationChainAddsV70AndV71WithoutChangingLegacyEvidence() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("migration", 1, 1);
            behavior(ids.getFirst(), "UNKNOWN");
            var options = f.controls.options(USER, ids.getFirst());
            var old = f.controls.submit(USER, ids.getFirst(), new Submit(Action.TEMPERATURE,"26","migration-old",options.revision()),"mysql-acceptance");
            assertThat(old.status()).isEqualTo(Status.UNKNOWN);
            var oldRows = new LinkedHashMap<String,List<Map<String,Object>>>();
            for (String table : CONTROL_TABLES) oldRows.put(table, db.queryForList("SELECT * FROM " + table));
            var history = db.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
            var v70 = Flyway.configure().dataSource(db.getDataSource()).locations("filesystem:src/env/init").target("70").load();
            assertThat(v70.migrate().migrationsExecuted).isEqualTo(1);
            db.update("INSERT INTO biz_daikin_control_batch(batch_id,operator_id,idempotency_key,request_hash,action,requested_value,created_at,trace_id) VALUES ('legacy',7,'legacy-batch',?,'ON_OFF','on',0,'mysql-migration')", "a".repeat(64));
            db.update("INSERT INTO biz_daikin_control_batch_lane(controller_key,item_id,lease_token,lease_until) VALUES (?,'legacy-item','legacy-lease',1)", "b".repeat(64));
            var oldLanes = db.queryForList("SELECT * FROM biz_daikin_control_batch_lane");
            var flyway = Flyway.configure().dataSource(db.getDataSource()).locations("filesystem:src/env/init").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            for (String table : CONTROL_TABLES) assertThat(db.queryForList("SELECT * FROM " + table)).containsExactlyInAnyOrderElementsOf(oldRows.get(table));
            assertThat(db.queryForList("SELECT version,checksum,success FROM flyway_schema_history WHERE version NOT IN ('70','71') ORDER BY installed_rank")).isEqualTo(history);
            assertThat(db.queryForList("SELECT * FROM biz_daikin_control_batch_lane")).isEqualTo(oldLanes);
            assertThat(db.queryForObject("SELECT overlap_allowed FROM biz_daikin_control_batch WHERE batch_id='legacy'", Boolean.class)).isFalse();
            db.update("DELETE FROM biz_daikin_control_batch_lane WHERE item_id='legacy-item'");
            db.update("DELETE FROM biz_daikin_control_batch WHERE batch_id='legacy'");
            assertThat(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('biz_daikin_control_preview','biz_daikin_control_batch','biz_daikin_control_batch_item','biz_daikin_control_batch_lane','biz_daikin_control_batch_gate')", Integer.class)).isEqualTo(5);
            assertThat(count("biz_daikin_control_batch")).isZero();
            assertThat(count("biz_daikin_control_batch_item")).isZero();
            assertThat(count("biz_daikin_control_grant")).isZero();
            System.out.println("MYSQL_BATCH_MIGRATION_OK version=" + db.queryForObject("SELECT VERSION()",String.class));
        }
    }

    @Test @Order(2) void realMysqlPersistsAllScalesAndFiveActionsWithoutSendingDuringSubmission() throws Exception {
        try (var f = new Harness()) {
            for (int size : List.of(1,3,15,100)) {
                var ids = seed("scale"+size, size, Math.max(1,(size+3)/4));
                var preview = f.batches.preview(USER,new PreviewRequest(ids));
                int before = count("acceptance_daikin_send");
                long start = System.nanoTime();
                var batch = f.batches.submit(USER,new SubmitBatch(preview.previewId(),Action.TEMPERATURE,"26","scale-request-"+size,false),"mysql-scale");
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                assertThat(elapsed).isLessThan(2_000);
                assertThat(count("acceptance_daikin_send")).isEqualTo(before);
                assertThat(batch.queuedCount()).isEqualTo(size);
                var complete = drain(f,batch.batchId());
                assertThat(complete.completedCount()).isEqualTo(size);
                assertThat(count("acceptance_daikin_send")-before).isEqualTo(size);
                System.out.printf("MYSQL_BATCH_SCALE devices=%d submitMillis=%d sends=%d%n",size,elapsed,size);
            }
            var values = Map.of(Action.ON_OFF,"on",Action.TEMPERATURE,"26",Action.MODE,"heating",Action.AIR_FLOW,"airFlowOne",Action.FAN_SPEED,"high");
            for (var action : Action.values()) {
                var ids = seed("action"+action.ordinal(),1,1);
                var batch = submit(f,ids,action,values.get(action),"action-"+action);
                assertThat(drain(f,batch.batchId()).completedCount()).isEqualTo(1);
            }
            var ids = seed("already",1,1);
            int before = count("acceptance_daikin_send");
            var noop = drain(f,submit(f,ids,Action.TEMPERATURE,"24","already-target").batchId());
            assertThat(noop.items().getFirst().status()).isEqualTo(ItemStatus.ALREADY_TARGET);
            assertThat(count("acceptance_daikin_send")).isEqualTo(before);
        }
    }

    @Test @Order(3) void databaseAuditFailureRollsBackCreationReplacementCancellationAndReadback() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("rollback",3,3);
            var original = submit(f,ids,Action.TEMPERATURE,"26","rollback-original");
            var preview = f.batches.preview(USER,new PreviewRequest(ids));
            var before = snapshot();
            failAudit();
            try {
                assertThatThrownBy(()->f.batches.submit(USER,new SubmitBatch(preview.previewId(),Action.TEMPERATURE,"27","rollback-new",false),"mysql-rollback"));
                assertThat(snapshot()).isEqualTo(before);
                assertThatThrownBy(()->f.batches.cancel(USER,original.batchId()));
                assertThat(snapshot()).isEqualTo(before);
            } finally { db.execute("DROP TRIGGER acceptance_fail_audit"); }
            var replacement = f.batches.submit(USER,new SubmitBatch(preview.previewId(),Action.TEMPERATURE,"27","rollback-new",false),"mysql-rollback");
            assertThat(f.batches.get(USER,original.batchId()).items()).allMatch(i->i.status()==ItemStatus.SUPERSEDED);
            assertThat(f.batches.cancel(USER,replacement.batchId()).items()).allMatch(i->i.status()==ItemStatus.CANCELLED);

            ids = seed("readback",1,1);
            var batch = submit(f,ids,Action.TEMPERATURE,"26","readback-rollback");
            var claimed = f.store.claim(candidate(batch.batchId()),Clock.systemUTC(),4);
            f.batches.dispatch(claimed);
            forceReadbackDue();
            var operationBefore = db.queryForList("SELECT status,observed_value,last_read_at,unit_status,cloud_stable FROM biz_daikin_control_operation WHERE equipment_id=?",ids.getFirst());
            int samples = count("biz_daikin_control_sample");
            failAudit();
            try {
                f.controls.reconcile();
                assertThat(db.queryForList("SELECT status,observed_value,last_read_at,unit_status,cloud_stable FROM biz_daikin_control_operation WHERE equipment_id=?",ids.getFirst())).isEqualTo(operationBefore);
                assertThat(count("biz_daikin_control_sample")).isEqualTo(samples);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot WHERE operation_id=?",Integer.class,claimedOperation(ids.getFirst()))).isEqualTo(1);
            } finally { db.execute("DROP TRIGGER acceptance_fail_audit"); }
            // The failed readback retains its read lease. Advance only this fixture's lease before the manual retry.
            db.update("UPDATE biz_daikin_control_operation SET lease_until=0 WHERE equipment_id=?",ids.getFirst());
            forceReadbackDue();
            assertThat(drain(f,batch.batchId()).completedCount()).isEqualTo(1);
        }
    }

    @Test @Order(4) void twoIndependentJvmsShareIdempotenceAndFourControllerLimit() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("duplicate",8,1);
            var preview = f.batches.preview(USER,new PreviewRequest(ids));
            var command = new SubmitBatch(preview.previewId(),Action.TEMPERATURE,"26","two-jvm-duplicate",false);
            String payload = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(command));
            compete("submit",payload,"submit",payload,0,0);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch WHERE idempotency_key='two-jvm-duplicate'",Integer.class)).isEqualTo(1);
            String batch = db.queryForObject("SELECT batch_id FROM biz_daikin_control_batch WHERE idempotency_key='two-jvm-duplicate'",String.class);
            int before = count("acceptance_daikin_send");
            compete("claim",batch,"claim",batch,0,0);
            assertThat(count("biz_daikin_control_batch_lane")).isEqualTo(4);
            assertThat(f.batches.get(USER,batch).activeCount()).isEqualTo(4);
            assertThat(count("acceptance_daikin_send")).isEqualTo(before);
            expireClaims(batch);
            f.store.synchronize(Clock.systemUTC());
            assertThat(count("biz_daikin_control_batch_lane")).isZero();
            assertThat(f.batches.get(USER,batch).items().stream().filter(i->i.status()==ItemStatus.NOT_SENT).count()).isEqualTo(4);
            f.batches.cancel(USER,batch);
            assertThat(count("acceptance_daikin_send")).isEqualTo(before);
        }
    }

    @Test @Order(5) void twoJvmDispatchersSendOnceAndSerializeThroughReadback() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("dispatch",16,4);
            var batch = submit(f,ids,Action.TEMPERATURE,"26","two-jvm-dispatch");
            compete("drain",batch.batchId(),"drain",batch.batchId(),0,0);
            assertThat(f.batches.get(USER,batch.batchId()).completedCount()).isEqualTo(16);
            for (String id : ids) assertThat(db.queryForObject("SELECT COUNT(*) FROM acceptance_daikin_send WHERE equipment_id=?",Integer.class,id)).isEqualTo(1);
            var sends = db.queryForList("SELECT * FROM acceptance_daikin_send WHERE equipment_id LIKE 'dispatch-%' ORDER BY seq");
            var previous = new HashMap<String,String>();
            for (var sent : sends) {
                String key = (String)sent.get("controller_key");
                String earlier = previous.put(key,(String)sent.get("equipment_id"));
                if (earlier!=null) {
                    long readAt = db.queryForObject("SELECT last_read_at FROM biz_daikin_control_operation WHERE equipment_id=?",Long.class,earlier);
                    assertThat(((Number)sent.get("sent_at")).longValue()).isGreaterThanOrEqualTo(readAt);
                }
            }
            assertThat(count("biz_daikin_control_batch_lane")).isZero();
        }
    }

    @Test @Order(6) void independentJvmCancellationAndReplacementOnlyAffectQueuedItems() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("cancel",8,1);
            var batch = submit(f,ids,Action.TEMPERATURE,"26","cancel-race");
            compete("cancel",batch.batchId(),"claim",batch.batchId(),0,0);
            var result = f.batches.get(USER,batch.batchId());
            assertThat(result.items()).allMatch(i->Set.of(ItemStatus.CANCELLED,ItemStatus.DISPATCHING).contains(i.status()));
            int active = result.activeCount();
            assertThat(count("biz_daikin_control_batch_lane")).isEqualTo(active);
            expireClaims(batch.batchId()); f.store.synchronize(Clock.systemUTC());
            assertThat(f.batches.get(USER,batch.batchId()).completed()).isTrue();

            ids = seed("replace",3,3);
            var preview = f.batches.preview(USER,new PreviewRequest(ids));
            String first = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(new SubmitBatch(preview.previewId(),Action.TEMPERATURE,"26","replace-jvm-1",false)));
            String second = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(new SubmitBatch(preview.previewId(),Action.TEMPERATURE,"27","replace-jvm-2",false)));
            compete("submit",first,"submit",second,0,0);
            var results = db.queryForList("SELECT batch_id FROM biz_daikin_control_batch WHERE idempotency_key LIKE 'replace-jvm-%'",String.class);
            assertThat(results).hasSize(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_item WHERE equipment_id LIKE 'replace-%' AND status='QUEUED'",Integer.class)).isEqualTo(3);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_batch_item WHERE equipment_id LIKE 'replace-%' AND status='SUPERSEDED'",Integer.class)).isEqualTo(3);
            for (String id : results) f.batches.cancel(USER,id);
        }
    }

    @Test @Order(7) void killedJvmBeforeAndAfterSendingNeverReplays() throws Exception {
        try (var f = new Harness()) {
            for (String mode : List.of("crash-before","crash-after")) {
                var ids = seed(mode,1,1);
                if (mode.equals("crash-after")) behavior(ids.getFirst(),"CRASH");
                var batch = submit(f,ids,Action.TEMPERATURE,"26",mode+"-request");
                int sends = count("acceptance_daikin_send");
                runOne(mode,batch.batchId(),19);
                expireClaims(batch.batchId());
                f.store.synchronize(Clock.systemUTC());
                behavior(ids.getFirst(),"NORMAL");
                forceReadbackDue();
                f.controls.reconcile(); f.store.synchronize(Clock.systemUTC());
                assertThat(f.batches.get(USER,batch.batchId()).items().getFirst().status())
                        .isEqualTo(mode.equals("crash-before")?ItemStatus.NOT_SENT:ItemStatus.UNCONFIRMED);
                for (int i=0;i<3;i++) round(f);
                assertThat(count("acceptance_daikin_send")-sends).isEqualTo(mode.equals("crash-before")?0:1);
            }
        }
    }

    @Test @Order(8) void unknownTimeoutAndReleaseKeepOldQueueStoppedAndHistoryRetained() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("unknown",2,2);
            var batch = submit(f,ids,Action.TEMPERATURE,"26","unknown-request");
            var work = f.store.claim(candidate(batch.batchId()),Clock.systemUTC(),4);
            behavior(work.item().equipmentId(),"UNKNOWN");
            f.batches.dispatch(work);
            String operation = claimedOperation(work.item().equipmentId());
            var recovery = new DaikinControlRecoveryService(f.access,f.controlStore,f.policy,List.of(f.gateway),Clock.systemUTC());
            recovery.continueCurrent(USER,operation,new Close("隔离验收按最新真实替身读值继续"),"mysql-recovery");
            var queued = candidate(batch.batchId());
            assertThat(f.store.claim(queued,Clock.systemUTC(),4)).isNull();
            f.store.synchronize(Clock.systemUTC());
            var result = f.batches.get(USER,batch.batchId());
            assertThat(result.completed()).isTrue();
            assertThat(result.items()).anyMatch(i->i.status()==ItemStatus.UNCONFIRMED && "26".equals(i.observedValue()));
            assertThat(result.items()).anyMatch(i->i.status()==ItemStatus.NOT_SENT);
            behavior(work.item().equipmentId(),"NORMAL");
            Thread.sleep(5);
            var fresh = submit(f,ids,Action.TEMPERATURE,"27","explicit-after-release");
            assertThat(drain(f,fresh.batchId()).completedCount()).isEqualTo(2);
            assertThat(f.batches.get(USER,batch.batchId()).items()).anyMatch(i->i.status()==ItemStatus.UNCONFIRMED);

            ids = seed("timeout",2,2);
            var timeout = submit(f,ids,Action.TEMPERATURE,"26","timeout-request");
            work = f.store.claim(candidate(timeout.batchId()),Clock.systemUTC(),4);
            behavior(work.item().equipmentId(),"NO_APPLY");
            f.batches.dispatch(work);
            db.update("UPDATE biz_daikin_control_operation SET deadline_at=?,next_check_at=0,lease_until=0 WHERE operation_id=?",System.currentTimeMillis()-1,claimedOperation(work.item().equipmentId()));
            f.controls.reconcile(); f.store.synchronize(Clock.systemUTC());
            assertThat(f.batches.get(USER,timeout.batchId()).items()).anyMatch(i->i.status()==ItemStatus.UNCONFIRMED);
            assertThat(f.batches.get(USER,timeout.batchId()).items()).anyMatch(i->i.status()==ItemStatus.NOT_SENT);
            assertThat(f.batches.history(USER,1,20).total()).isGreaterThan(10);
        }
    }

    @Test @Order(9) void twoJvmsAllowOnlyTwoAcceptedSwitchesAndRecoverBothTimeouts() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("overlap", 3, 3);
            for (String id : ids) behavior(id, "NO_APPLY");
            var batch = submit(f, ids, Action.ON_OFF, "on", "overlap-two-jvm");
            var first = f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4); f.batches.dispatch(first);
            int before = count("acceptance_daikin_send");
            compete("dispatch-only", batch.batchId(), "dispatch-only", batch.batchId(), 0, 0);
            assertThat(count("acceptance_daikin_send") - before).isEqualTo(1);
            var current = f.batches.get(USER, batch.batchId());
            assertThat(current.activeCount()).isEqualTo(2); assertThat(current.queuedCount()).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot s JOIN biz_daikin_control_operation o ON o.operation_id=s.operation_id WHERE o.source_id='overlap'", Integer.class)).isEqualTo(2);
            assertThat(f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4)).isNull();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation WHERE source_id='overlap' AND status='ACCEPTED'", Integer.class)).isEqualTo(2);
            db.update("UPDATE biz_daikin_control_operation SET deadline_at=?,next_check_at=0,lease_until=0 WHERE source_id='overlap'", System.currentTimeMillis()-1);
            f.controls.reconcile(); f.store.synchronize(Clock.systemUTC());
            var recovery = new DaikinControlRecoveryService(f.access, f.controlStore, f.policy, List.of(f.gateway), Clock.systemUTC());
            for (var item : f.batches.get(USER, batch.batchId()).items()) if (item.operationId()!=null) {
                assertThat(item.status()).isEqualTo(ItemStatus.UNCONFIRMED);
                recovery.continueCurrent(USER, item.operationId(), new Close("按当前隔离替身读值逐台恢复"), "mysql-overlap");
            }
            for (int i=0;i<3;i++) round(f);
            assertThat(count("acceptance_daikin_send") - before).isEqualTo(1);
            assertThat(f.batches.get(USER, batch.batchId()).items()).anyMatch(i -> i.status()==ItemStatus.NOT_SENT);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot s JOIN biz_daikin_control_operation o ON o.operation_id=s.operation_id WHERE o.source_id='overlap'", Integer.class)).isZero();
            System.out.println("MYSQL_BATCH_OVERLAP_TWO_JVM_AND_RECOVERY_OK");
        }
    }

    @Test @Order(10) void crashedSecondSendDoesNotReplayAndFirstStillCompletes() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("overlap-crash", 3, 3);
            for (String id : ids) behavior(id, "NO_APPLY");
            var batch = submit(f, ids, Action.ON_OFF, "on", "overlap-crash-request");
            var first = f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4); f.batches.dispatch(first);
            var second = candidate(batch.batchId()); behavior(second.item().equipmentId(), "CRASH");
            int before = count("acceptance_daikin_send");
            runOne("crash-after", batch.batchId(), 19);
            expireClaims(batch.batchId()); f.store.synchronize(Clock.systemUTC());
            db.update("UPDATE acceptance_daikin_unit SET unit_json=JSON_SET(unit_json,'$.onOff','on','$.unitStatus','operating') WHERE equipment_id=?", first.item().equipmentId());
            forceReadbackDue(); f.controls.reconcile(); f.store.synchronize(Clock.systemUTC());
            for (int i=0;i<3;i++) round(f);
            assertThat(count("acceptance_daikin_send") - before).isEqualTo(1);
            var result = f.batches.get(USER, batch.batchId());
            assertThat(result.items()).anyMatch(i -> i.equipmentId().equals(first.item().equipmentId()) && i.status()==ItemStatus.MATCHED);
            assertThat(result.items()).anyMatch(i -> i.status()==ItemStatus.UNCONFIRMED);
            assertThat(result.items()).anyMatch(i -> i.status()==ItemStatus.NOT_SENT);
            System.out.println("MYSQL_BATCH_OVERLAP_CRASH_NO_REPLAY_OK");
        }
    }

    @Test @Order(11) void secondSlotAndOperationRollBackTogetherOnAuditFailure() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("overlap-rollback", 2, 2);
            for (String id : ids) behavior(id, "NO_APPLY");
            var batch = submit(f, ids, Action.ON_OFF, "on", "overlap-audit-rollback");
            var first = f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4); f.batches.dispatch(first);
            var second = f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4);
            int before = count("acceptance_daikin_send"); failAudit();
            try { assertThatThrownBy(() -> f.batches.dispatch(second)).isInstanceOf(RuntimeException.class); }
            finally { db.execute("DROP TRIGGER acceptance_fail_audit"); }
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_operation WHERE source_id='overlap-rollback'", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM biz_daikin_control_controller_slot s JOIN biz_daikin_control_operation o ON o.operation_id=s.operation_id WHERE o.source_id='overlap-rollback'", Integer.class)).isEqualTo(1);
            assertThat(count("acceptance_daikin_send")).isEqualTo(before);
            expireClaims(batch.batchId()); f.store.synchronize(Clock.systemUTC());
            db.update("UPDATE acceptance_daikin_unit SET unit_json=JSON_SET(unit_json,'$.onOff','on','$.unitStatus','operating') WHERE equipment_id=?", first.item().equipmentId());
            forceReadbackDue(); f.controls.reconcile(); f.store.synchronize(Clock.systemUTC());
            assertThat(f.batches.get(USER, batch.batchId()).items()).anyMatch(i -> i.status()==ItemStatus.NOT_SENT);
            assertThat(count("acceptance_daikin_send")).isEqualTo(before);
            System.out.println("MYSQL_BATCH_OVERLAP_SECOND_SLOT_ROLLBACK_OK");
        }
    }

    @Test @Order(12) void closingAnUnknownPeerCannotReviveAnUnsentClaim() throws Exception {
        try (var f = new Harness()) {
            var ids = seed("overlap-closed", 2, 2);
            for (String id : ids) behavior(id, "NO_APPLY");
            var batch = submit(f, ids, Action.ON_OFF, "on", "overlap-closed-request");
            var first = f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4); f.batches.dispatch(first);
            var second = f.store.claim(candidate(batch.batchId()), Clock.systemUTC(), 4);
            String operation = claimedOperation(first.item().equipmentId());
            f.controlStore.transition(operation, Set.of(Status.ACCEPTED), Status.UNKNOWN, "SEND_RESULT_UNKNOWN",
                    null, "off", null, null, 0, System.currentTimeMillis());
            var recovery = new DaikinControlRecoveryService(f.access, f.controlStore, f.policy, List.of(f.gateway), Clock.systemUTC());
            recovery.continueCurrent(USER, operation, new Close("按当前状态结束旧未知操作"), "mysql-closed");
            int before = count("acceptance_daikin_send"); f.batches.dispatch(second);
            assertThat(count("acceptance_daikin_send")).isEqualTo(before);
            assertThat(f.store.work(second.item().itemId()).item().status()).isEqualTo(ItemStatus.NOT_SENT);
            assertThat(f.store.work(second.item().itemId()).item().operationId()).isNull();
            System.out.println("MYSQL_BATCH_OVERLAP_RECOVERY_STOPS_UNSENT_CLAIM_OK");
        }
    }

    private static List<String> seed(String prefix,int count,int perController) throws Exception {
        db.update("INSERT INTO biz_daikin_source(source_id,source_name,registered_by) VALUES (?,?,7)",prefix,"隔离模拟来源"+prefix);
        db.update("INSERT INTO biz_daikin_project_mapping(source_id,site_id,building_id,mapping_version,mapped_by,mapped_at) VALUES (?,'site','BLD001_ACCEPT',1,7,CURRENT_TIMESTAMP(3))",prefix);
        var ids = new ArrayList<String>();
        for(int i=0;i<count;i++) {
            String id=prefix+"-"+i,controller="controller-"+(i/perController),vendor="vendor-"+i,unit="unit-"+i;
            db.update("INSERT INTO biz_equipment(equip_id,equip_code,equip_name,type_code,equip_category,system_group_id,building_id,space_id,product_id) VALUES (?,?,?,'IDU','INDOOR_UNIT','acceptance-system','BLD001_ACCEPT','acceptance-space','PRODUCT_DAIKIN_IN_STATE')",id,id,"隔离模拟空调"+i);
            db.update("INSERT INTO biz_device_identity(identity_id,identity_type,identity_value,equip_id,building_id,expected_profile_code,status) VALUES (?,'DAIKIN_UNIT',?,?,'BLD001_ACCEPT','DAIKIN_INDOOR_V2',1)","i-"+id,id,id);
            db.update("INSERT INTO biz_pending_device(pending_id,identity_type,identity_value,profile_code,last_profile_version,first_seen_time,last_seen_time,latest_event_time,latest_time_source,latest_metrics_json,status,bound_identity_id) VALUES (?,'DAIKIN_UNIT',?,'DAIKIN_INDOOR_V2',2,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'SERVER_RECEIVED','{}','BOUND',?)","p-"+id,id,"i-"+id);
            db.update("INSERT INTO biz_daikin_directory(pending_id,identity_hash,source_id,site_id,controller_id,device_kind,unit_id,equipment_id,observed_at,last_catalog_at) VALUES (?,?,?,'site',?,'INDOOR',?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))","p-"+id,DaikinControlAccess.hash(id),prefix,controller,unit,vendor);
            var node = JSON.readTree("""
                    {"unitStatus":"stopped","inCommunicationError":false,"inEquipmentError":false,"inMaintenanceMode":false,
                    "isGroupSlave":false,"masterSlaveFlag":"Master","masterSlaveIds":[],"onOff":"off","mode":"cooling",
                    "temperature":24,"fanSpeed":"low","airflowDirection":"airFlowZero","onOffModeSetList":["on","off"],
                    "modeSetList":["cooling","heating","fan"],"fanSpeedSetList":["low","high","automatic"],
                    "DefaultSetpointRange":{"min":16,"max":32,"step":1},"limitSettempCool":"off","limitSettempHeat":"off"}
                    """);
            ((ObjectNode)node).put("id",vendor).put("unitId",unit);
            db.update("INSERT INTO acceptance_daikin_unit(equipment_id,unit_json) VALUES (?,?)",id,JSON.writeValueAsString(node));
            ids.add(id);
        }
        return ids;
    }

    private static class SqlGateway implements DaikinControlGateway, DaikinControlGrantReader {
        public boolean supports(String source) { return true; }
        public Snapshot read(DaikinControlAccess.Target t) {
            try {
                var unit = JSON.readTree(db.queryForObject("SELECT unit_json FROM acceptance_daikin_unit WHERE equipment_id=?",String.class,t.equipmentId()));
                return new Snapshot(JSON.createObjectNode().put("lcNo",t.controllerId()).put("isConnectionUp",true).put("inForcedStop",false),unit);
            } catch(Exception e) { throw new IllegalStateException("SQL 模拟厂家读取失败",e); }
        }
        public Reply send(DaikinControlAccess.Target t,Action action,String value) {
            String mode = db.queryForObject("SELECT behavior FROM acceptance_daikin_unit WHERE equipment_id=?",String.class,t.equipmentId());
            db.update("INSERT INTO acceptance_daikin_send(equipment_id,controller_key,action,value,process_id,sent_at) VALUES (?,?,?,?,?,?)",t.equipmentId(),t.controllerKey(),action.name(),value,ProcessHandle.current().pid(),System.currentTimeMillis());
            if (!mode.equals("NO_APPLY")) {
                var unit=(ObjectNode)read(t).unit();
                if(action==Action.TEMPERATURE) unit.put(action.observedField,Integer.parseInt(value)); else unit.put(action.observedField,value);
                if(action==Action.ON_OFF) unit.put("unitStatus",value.equals("on")?"operating":"stopped");
                db.update("UPDATE acceptance_daikin_unit SET unit_json=? WHERE equipment_id=?",unit.toString(),t.equipmentId());
            }
            if(mode.equals("CRASH")) Runtime.getRuntime().halt(19);
            if(mode.equals("UNKNOWN")) throw new IllegalStateException("模拟厂家已改变但受理响应丢失");
            return new Reply("10000");
        }
    }

    private static class Harness implements AutoCloseable {
        final DaikinControlPolicy policy = new DaikinControlPolicy();
        final SqlGateway gateway = new SqlGateway();
        final DaikinControlAccess access;
        final DaikinControlStore controlStore;
        final DaikinControlService controls;
        final DaikinBatchStore store;
        final DaikinBatchService batches;
        Harness() {
            var props=new DaikinControlProperties(); props.setEnabled(true); props.setTestOpenControls(true);
            var audit=new AuditGovernanceProperties(); audit.setEnvironmentMode(AuditEnvironmentMode.TEST);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
            var writer=new JdbcSecurityAuditEvidenceWriter(db,new AuditSummarySanitizer());
            access=new DaikinControlAccess(db,null,null,null,props,audit);
            controlStore=new DaikinControlStore(db,tx,JSON,writer,audit);
            controls=new DaikinControlService(props,access,policy,controlStore,List.of(gateway),Clock.systemUTC());
            store=new DaikinBatchStore(db,tx,JSON,controlStore,writer,audit);
            batches=new DaikinBatchService(store,controls,access,policy,props,db,Clock.systemUTC());
        }
        public void close() { batches.stop(); }
    }

    private static Batch submit(Harness f,List<String> ids,Action action,String value,String key) {
        var p=f.batches.preview(USER,new PreviewRequest(ids));
        return f.batches.submit(USER,new SubmitBatch(p.previewId(),action,value,key,false),"mysql-acceptance");
    }
    private static DaikinBatchStore.Work candidate(String batch) {
        try(var f=new Harness()) { return f.store.candidates().stream().filter(w->w.batchId().equals(batch)).findFirst().orElseThrow(); }
    }
    private static void round(Harness f) {
        f.store.synchronize(Clock.systemUTC());
        for(var w:f.store.candidates()) {
            var claimed=f.store.claim(w,Clock.systemUTC(),4);
            if(claimed!=null) f.batches.dispatch(claimed);
        }
        f.controls.reconcile(); f.store.synchronize(Clock.systemUTC());
    }
    private static Batch drain(Harness f,String batch) throws InterruptedException {
        // Real worker clocks retain the normal 30-second read lease and polling schedule; do not bulk-edit them across JVMs.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<deadline) {
            round(f);
            if(f.batches.get(USER,batch).completed()) return f.batches.get(USER,batch);
            Thread.sleep(10);
        }
        throw new AssertionError("批次未在模拟轮次内结束："+batch+";items="+f.batches.get(USER,batch).items()
                +";operations="+db.queryForList("SELECT equipment_id,status,reason_code,lease_until,lease_version,next_check_at,last_read_at FROM biz_daikin_control_operation WHERE equipment_id IN (SELECT equipment_id FROM biz_daikin_control_batch_item WHERE batch_id=?)",batch));
    }
    private static void forceReadbackDue() { db.update("UPDATE biz_daikin_control_operation SET next_check_at=0 WHERE status IN ('ACCEPTED','SENDING','PREPARING') AND lease_until<=?",System.currentTimeMillis()); }
    private static void expireClaims(String batch) { db.update("UPDATE biz_daikin_control_batch_item SET lease_until=0 WHERE batch_id=? AND status='DISPATCHING'",batch); }
    private static String claimedOperation(String equipment) { return db.queryForObject("SELECT operation_id FROM biz_daikin_control_operation WHERE equipment_id=? ORDER BY created_at DESC LIMIT 1",String.class,equipment); }
    private static void behavior(String equipment,String mode) { db.update("UPDATE acceptance_daikin_unit SET behavior=? WHERE equipment_id=?",mode,equipment); }
    private static int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class); }
    private static void failAudit() { db.execute("CREATE TRIGGER acceptance_fail_audit BEFORE INSERT ON sys_security_audit_event FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ISOLATED_AUDIT_FAILURE'"); }
    private static Map<String,List<Map<String,Object>>> snapshot() {
        var rows=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String t:List.of("biz_daikin_control_batch","biz_daikin_control_batch_item","sys_security_audit_event")) rows.put(t,db.queryForList("SELECT * FROM "+t));
        return rows;
    }

    private record Child(Process process,String worker,Path log) { }
    private static Child start(String mode,String payload) throws Exception {
        String worker=UUID.randomUUID().toString();
        Path log=Path.of("target","batch-mysql-worker-"+worker+".log");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Duser.timezone=Asia/Shanghai",
                "-Dfile.encoding=UTF-8","-Dsun.stdout.encoding=UTF-8","-Dsun.stderr.encoding=UTF-8","-cp",classpath,
                DaikinBatchMysqlAcceptanceTest.class.getName(),mode,payload,worker).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return new Child(process,worker,log);
    }
    private static void awaitReady(List<Child> children) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            if(children.stream().allMatch(c->db.queryForObject("SELECT COUNT(*) FROM acceptance_daikin_worker WHERE worker_id=?",Integer.class,c.worker())==1)) {
                assertThat(children.stream().map(c->c.process().pid()).distinct().count()).isEqualTo((long)children.size());
                for(var c:children) db.update("UPDATE acceptance_daikin_worker SET released=TRUE WHERE worker_id=?",c.worker());
                return;
            }
            for(var c:children) if(!c.process().isAlive()) throw new AssertionError("子 JVM 在同步前退出："+Files.readString(c.log()));
            Thread.sleep(30);
        }
        throw new AssertionError("子 JVM 未到达共享 MySQL 屏障");
    }
    private static void finish(Child child,int code) throws Exception {
        assertThat(child.process().waitFor(60,TimeUnit.SECONDS)).as("子 JVM 超时："+child.log()).isTrue();
        assertThat(child.process().exitValue()).as(new String(Files.readAllBytes(child.log()),StandardCharsets.UTF_8)).isEqualTo(code);
    }
    private static void compete(String first,String p1,String second,String p2,int e1,int e2) throws Exception {
        var a=start(first,p1); var b=start(second,p2);
        try { awaitReady(List.of(a,b)); finish(a,e1); finish(b,e2); }
        finally { if(a.process().isAlive()) a.process().destroyForcibly(); if(b.process().isAlive()) b.process().destroyForcibly(); }
        System.out.printf("MYSQL_BATCH_TWO_JVM modes=%s/%s pids=%d/%d%n",first,second,a.process().pid(),b.process().pid());
    }
    private static void runOne(String mode,String payload,int code) throws Exception {
        var child=start(mode,payload);
        try { awaitReady(List.of(child)); finish(child,code); }
        finally { if(child.process().isAlive()) child.process().destroyForcibly(); }
    }

    public static void main(String[] args) throws Exception {
        db=isolatedDatabase();
        String mode=args[0],payload=args[1],worker=args[2];
        db.update("INSERT INTO acceptance_daikin_worker(worker_id,process_id) VALUES (?,?)",worker,ProcessHandle.current().pid());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!Boolean.TRUE.equals(db.queryForObject("SELECT released FROM acceptance_daikin_worker WHERE worker_id=?",Boolean.class,worker))) {
            if(System.nanoTime()>deadline) throw new AssertionError("MySQL 同步屏障超时");
            Thread.sleep(20);
        }
        try(var f=new Harness()) {
            switch(mode) {
                case "submit" -> f.batches.submit(USER,JSON.readValue(Base64.getDecoder().decode(payload),SubmitBatch.class),"two-jvm-acceptance");
                case "cancel" -> f.batches.cancel(USER,payload);
                case "claim" -> {
                    for(var w:f.store.candidates()) if(w.batchId().equals(payload)) f.store.claim(w,Clock.systemUTC(),4);
                }
                case "dispatch-only" -> {
                    for(var w:f.store.candidates()) if(w.batchId().equals(payload)) {
                        var claimed=f.store.claim(w,Clock.systemUTC(),4);
                        if(claimed!=null) f.batches.dispatch(claimed);
                    }
                }
                case "drain" -> drain(f,payload);
                case "crash-before", "crash-after" -> {
                    var w=f.store.claim(candidate(payload),Clock.systemUTC(),4);
                    assertThat(w).isNotNull();
                    if(mode.equals("crash-before")) Runtime.getRuntime().halt(19);
                    f.batches.dispatch(w);
                    throw new AssertionError("模拟厂家应在已发送后终止该 JVM");
                }
                default -> throw new IllegalArgumentException("未知隔离模式");
            }
        } finally { closeDatabase(); }
    }
}
