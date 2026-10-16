package com.platform.iot.daikin.client;

import com.platform.iot.daikin.control.DaikinControlGateway.SendPermit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** 单次内存派发波次：全部成员准备/退出后一起预留共享预算；进程退出不持久化、不恢复或重发该波次。 */
final class DaikinSendWave {
    private final DaikinHttpBudget budget;
    private final long waitMillis;
    private final List<Member> members=new ArrayList<>();
    private final CompletableFuture<Void> admitted=new CompletableFuture<>();
    private int remaining;

    private DaikinSendWave(DaikinHttpBudget budget,int size,Duration wait) {
        this.budget=budget;waitMillis=wait.toMillis();remaining=size;
        for(int i=0;i<size;i++) members.add(new Member(this));
    }

    static List<SendPermit> prepare(DaikinHttpBudget budget,int size,Duration wait) {
        if(size<1 || size>100) throw new IllegalArgumentException("Invalid send wave size");
        var result=new ArrayList<SendPermit>();
        for(int offset=0;offset<size;) {
            int count=Math.min(budget.waveSize(),size-offset);
            result.addAll(new DaikinSendWave(budget,count,wait).members);offset+=count;
        }
        return List.copyOf(result);
    }

    private void admit() {
        List<Member> ready;
        synchronized(this) { ready=members.stream().filter(m->m.ready).toList(); }
        if(ready.isEmpty()) { admitted.complete(null);return; }
        try {
            var tickets=budget.acquire(ready.size());
            synchronized(this) {
                // 等待者可能已超时/中断；迟到的预留只释放，不允许之后出现迟到 PUT。
                if(admitted.isDone()) tickets.forEach(DaikinHttpBudget.Ticket::close);
                else {
                    for(int i=0;i<ready.size();i++) ready.get(i).ticket=tickets.get(i);
                    admitted.complete(null);
                }
            }
        } catch(InterruptedException failure) {
            Thread.currentThread().interrupt();admitted.completeExceptionally(new DaikinClientException(DaikinClientException.Code.INTERRUPTED,failure));
        } catch(RuntimeException failure) { admitted.completeExceptionally(failure); }
    }

    static final class Member implements SendPermit {
        private final DaikinSendWave wave;
        private boolean arrived;
        private boolean ready;
        private boolean closed;
        private DaikinHttpBudget.Ticket ticket;
        private Member(DaikinSendWave wave) { this.wave=wave; }
        public void awaitReady() { ticket(wave.budget); }
        DaikinHttpBudget.Ticket ticket(DaikinHttpBudget expected) {
            boolean last;
            synchronized(wave) {
                if(expected!=wave.budget || closed || arrived) throw unavailable();
                ready=true;arrived=true;last=--wave.remaining==0;
            }
            if(last) wave.admit();
            try { wave.admitted.get(wave.waitMillis,TimeUnit.MILLISECONDS); }
            catch(InterruptedException failure) {
                Thread.currentThread().interrupt();wave.admitted.completeExceptionally(failure);
                throw new DaikinClientException(DaikinClientException.Code.INTERRUPTED,failure);
            } catch(TimeoutException failure) {
                wave.admitted.completeExceptionally(failure);throw unavailable();
            } catch(ExecutionException failure) {
                if(failure.getCause() instanceof DaikinClientException clientFailure) throw clientFailure;
                throw unavailable();
            }
            synchronized(wave) { if(closed || ticket==null) throw unavailable();return ticket; }
        }
        public void close() {
            boolean last;DaikinHttpBudget.Ticket reserved;
            synchronized(wave) {
                if(closed) return;
                closed=true;last=!arrived && --wave.remaining==0;arrived=true;reserved=ticket;
            }
            if(reserved!=null) reserved.close();
            if(last) wave.admit();
        }
        private static DaikinClientException unavailable() { return new DaikinClientException(DaikinClientException.Code.CONCURRENCY_LIMIT); }
    }
}
