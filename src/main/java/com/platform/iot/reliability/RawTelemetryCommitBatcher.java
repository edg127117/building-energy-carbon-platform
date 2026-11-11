package com.platform.iot.reliability;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 合并并发原始写入的同阶段提交；每项保留保存点，确认始终晚于整个数据库事务提交。 */
@Component
public class RawTelemetryCommitBatcher implements AutoCloseable {
    public enum Stage { RESERVE, PERSIST }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BlockingQueue<Work> pending=new ArrayBlockingQueue<>(256);
    private final Object lifecycle=new Object();
    private volatile boolean closed;
    private final Thread worker;
    private final Runnable beforeDrain;

    private static final class Work {
        final Stage stage;final String point;final Supplier<?> action;
        final CompletableFuture<Object> completion=new CompletableFuture<>();
        Object value;RuntimeException failure;
        Work(Stage stage,String point,Supplier<?> action){this.stage=stage;this.point=point;this.action=action;}
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RawTelemetryCommitBatcher(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc) {this(jdbc,()->{});}

    RawTelemetryCommitBatcher(JdbcTemplate jdbc,Runnable beforeDrain) {
        this.jdbc=jdbc;this.beforeDrain=beforeDrain;
        var manager=new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        // 提交异常时先尝试回滚，避免连接归还时恢复 autoCommit 意外提交尚未提交的批次。
        manager.setRollbackOnCommitFailure(true);
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        worker=Thread.ofPlatform().daemon().name("raw-telemetry-commit").start(this::run);
    }

    public <T> T execute(String point,Supplier<T> action){return execute(Stage.RESERVE,point,action);}
    int pendingCount(){return pending.size();}

    @SuppressWarnings("unchecked")
    public <T> T execute(Stage stage,String point,Supplier<T> action) {
        if(Thread.currentThread()==worker)throw new IllegalStateException("Raw commit stages cannot nest");
        var work=new Work(Objects.requireNonNull(stage),Objects.requireNonNull(point),Objects.requireNonNull(action));
        synchronized(lifecycle) {
            if(closed||!pending.offer(work))throw unavailable("Raw commit capacity unavailable",null);
        }
        try{return (T)work.completion.get(30,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable("Raw commit wait interrupted",e);}
        catch(TimeoutException e){throw unavailable("Raw commit wait expired; retry using the original identity",e);}
        catch(ExecutionException e){
            if(e.getCause() instanceof RuntimeException failure)throw failure;
            if(e.getCause() instanceof Error failure)throw failure;
            throw unavailable("Raw commit failed",e.getCause());
        }
    }

    private void run() {
        try {
            while(!closed||!pending.isEmpty()) {
                Work first=pending.poll(50,TimeUnit.MILLISECONDS);if(first==null)continue;
                var batch=new ArrayList<Work>();batch.add(first);
                try {beforeDrain.run();TimeUnit.MILLISECONDS.sleep(2);}
                catch(InterruptedException e){first.completion.completeExceptionally(unavailable("Raw commit stopped",e));throw e;}
                catch(RuntimeException|Error failure){first.completion.completeExceptionally(failure);throw failure;}
                while(batch.size()<32) {
                    Work next=pending.peek();if(next==null||next.stage!=first.stage)break;
                    batch.add(Objects.requireNonNull(pending.poll()));
                }
                commit(batch);
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally {
            synchronized(lifecycle){closed=true;}
            Work work;while((work=pending.poll())!=null)work.completion.completeExceptionally(unavailable("Raw commit stopped",null));
        }
    }

    private void commit(List<Work> batch) {
        try {
            transaction.executeWithoutResult(status->{
                // 同一配额仍是跨实例硬上限；一批先取得它，再按固定顺序取得全部点锁，避免交错锁序。
                jdbc.queryForList("SELECT budget_key FROM biz_telemetry_conflict_budget WHERE budget_key='PENDING_RAW' FOR UPDATE",String.class);
                batch.stream().map(w->w.point).distinct().sorted().forEach(point->jdbc.update(
                        "INSERT INTO biz_telemetry_retention_boundary(point_key,before_ms) VALUES (?,0) ON DUPLICATE KEY UPDATE point_key=VALUES(point_key)",point));
                for(var work:batch) {
                    Object savepoint=status.createSavepoint();
                    try{work.value=work.action.get();}
                    catch(RuntimeException failure){status.rollbackToSavepoint(savepoint);work.failure=failure;}
                    finally{status.releaseSavepoint(savepoint);}
                }
                // 保存点之前的预锁不能让失败的首次登记留下空行；同点成功项与非零退役水位保持。
                batch.stream().filter(w->w.failure!=null).map(w->w.point).distinct().forEach(point->jdbc.update(
                        "DELETE FROM biz_telemetry_retention_boundary WHERE point_key=? AND before_ms=0 AND NOT EXISTS (SELECT 1 FROM biz_telemetry_raw_intent WHERE point_key=?)",point,point));
            });
            // execute 返回才代表提交成功；保存点只回滚 MySQL 状态，TD 已写入时仍由冻结意图幂等恢复。
            for(var work:batch) {
                if(work.failure!=null)work.completion.completeExceptionally(work.failure);
                else work.completion.complete(work.value);
            }
        }catch(RuntimeException|Error failure) {
            batch.forEach(work->work.completion.completeExceptionally(failure));
            if(failure instanceof Error error)throw error;
        }
    }

    private static DataAccessResourceFailureException unavailable(String message,Throwable cause) {
        return new DataAccessResourceFailureException(message,cause);
    }

    @Override @PreDestroy public void close() {
        synchronized(lifecycle){closed=true;}
        if(Thread.currentThread()==worker)return;
        try {worker.join(35_000);if(worker.isAlive())worker.interrupt();}
        catch(InterruptedException e){worker.interrupt();Thread.currentThread().interrupt();}
    }
}
