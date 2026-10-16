package com.platform.iot.daikin.client;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 纯进程内预算验证，预留、实际起始、共享滚动限额与释放均不依赖厂家或数据库。 */
class DaikinHttpBudgetTest {
    private DaikinHttpBudget budget(int concurrent,int rate,long waitMillis) {
        return new DaikinHttpBudget(new DaikinClientConfiguration(true,true,URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("test","test","salt","0123456789abcdef"),
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofMillis(waitMillis),Duration.ZERO,10000,concurrent,rate));
    }

    @Test void oneRollingSecondNeverContainsMoreThanFourStartsAcrossCompetingCallers() throws Exception {
        var budget=budget(4,4,5000);var starts=new CopyOnWriteArrayList<Long>();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var work=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++) work.add(pool.submit(()->{
                try(var ticket=budget.acquire(1).getFirst()) {
                    ticket.start();starts.add(System.nanoTime());
                } catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new RuntimeException(failure); }
            }));
            for(var future:work) future.get(7,TimeUnit.SECONDS);
        }
        var sorted=starts.stream().sorted().toList();assertThat(sorted).hasSize(20);
        // A small observation tolerance covers recording after start(), not an extra request allowance.
        for(int i=4;i<sorted.size();i++) assertThat(sorted.get(i)-sorted.get(i-4)).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(998));
    }

    @Test void reservedButDelayedStartsContinueToOccupyRateSlots() throws Exception {
        var budget=budget(4,4,2000);var tickets=budget.acquire(4);
        try {
            Thread.sleep(1050);
            try(var pool=Executors.newSingleThreadExecutor()) {
                var pending=pool.submit(()->budget.acquire(1));
                Thread.sleep(30);assertThat(pending.isDone()).isFalse();
                tickets.forEach(DaikinHttpBudget.Ticket::start);tickets.forEach(DaikinHttpBudget.Ticket::close);
                Thread.sleep(30);assertThat(pending.isDone()).isFalse();
                var next=pending.get(2,TimeUnit.SECONDS);next.forEach(DaikinHttpBudget.Ticket::close);
            }
        } finally { tickets.forEach(DaikinHttpBudget.Ticket::close); }
    }

    @Test void expiredReservationCannotStartAndCancellationReturnsOnlyUnusedSlots() throws Exception {
        var budget=budget(1,1,30);var ticket=budget.acquire(1).getFirst();Thread.sleep(40);
        assertThatThrownBy(ticket::start).isInstanceOf(DaikinClientException.class);ticket.close();ticket.close();
        try(var next=budget.acquire(1).getFirst()) { next.start(); }
        assertThatThrownBy(()->budget.acquire(1)).isInstanceOf(DaikinClientException.class);
    }

    @Test void concurrencyOneAndRateOneRemainIndependentAndDoNotAdmitOversizedWaves() throws Exception {
        var budget=budget(1,4,30);
        assertThatThrownBy(()->budget.acquire(2)).isInstanceOf(IllegalArgumentException.class);
        var first=budget.acquire(1).getFirst();first.start();
        assertThatThrownBy(()->budget.acquire(1)).isInstanceOf(DaikinClientException.class);
        first.close();try(var second=budget.acquire(1).getFirst()) { second.start(); }
    }
}
