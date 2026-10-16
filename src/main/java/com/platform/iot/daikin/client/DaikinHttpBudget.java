package com.platform.iot.daikin.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 来源进程内共享 HTTP 预算：滚动一秒计数允许小批突发，待启动预留也计数，不能借延迟启动超额发送。 */
final class DaikinHttpBudget {
    private final int concurrency;
    private final int requestsPerSecond;
    private final long waitNanos;
    private final ReentrantLock lock=new ReentrantLock(true);
    private final java.util.concurrent.locks.Condition changed=lock.newCondition();
    private final List<Ticket> window=new ArrayList<>();
    private int active;

    DaikinHttpBudget(DaikinClientConfiguration config) {
        concurrency=config.maxConcurrentRequests();requestsPerSecond=config.requestsPerSecond();waitNanos=config.concurrencyWait().toNanos();
    }

    int waveSize() { return Math.min(concurrency,requestsPerSecond); }

    List<Ticket> acquire(int count) throws InterruptedException {
        if(count<1 || count>waveSize()) throw new IllegalArgumentException("HTTP wave exceeds configured budget");
        long deadline=System.nanoTime()+waitNanos;
        if(!lock.tryLock(waitNanos,TimeUnit.NANOSECONDS)) throw exhausted();
        try {
            while(true) {
                long now=System.nanoTime();
                if(deadline-now<=0) throw exhausted();
                window.removeIf(t->t.started && now-t.startedAt>=TimeUnit.SECONDS.toNanos(1));
                if(active+count<=concurrency && window.size()+count<=requestsPerSecond) {
                    var tickets=new ArrayList<Ticket>();
                    for(int i=0;i<count;i++) tickets.add(new Ticket(now));
                    window.addAll(tickets);active+=count;return tickets;
                }
                long remaining=deadline-now;
                if(remaining<=0) throw exhausted();
                long next=window.stream().filter(t->t.started)
                        .mapToLong(t->TimeUnit.SECONDS.toNanos(1)-(now-t.startedAt)).min().orElse(remaining);
                changed.awaitNanos(Math.min(remaining,Math.max(1,next)));
            }
        } finally { lock.unlock(); }
    }

    final class Ticket implements AutoCloseable {
        private final long reservedAt;
        private long startedAt;
        private boolean started;
        private boolean closed;
        Ticket(long now) { reservedAt=now; }
        void start() {
            lock.lock();
            try {
                if(closed || started || System.nanoTime()-reservedAt>=waitNanos) throw exhausted();
                // 用实际调用 transport 的时刻延长窗口；预留后线程被调度延迟也不能提前归还速率名额。
                started=true;startedAt=System.nanoTime();changed.signalAll();
            } finally { lock.unlock(); }
        }
        public void close() {
            lock.lock();
            try {
                if(closed) return;
                closed=true;active--;
                if(!started) window.remove(this);
                changed.signalAll();
            } finally { lock.unlock(); }
        }
    }

    private static DaikinClientException exhausted() { return new DaikinClientException(DaikinClientException.Code.CONCURRENCY_LIMIT); }
}
