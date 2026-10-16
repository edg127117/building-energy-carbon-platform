package com.platform.iot.daikin.client;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 波次退出、超时与共享预算争用模拟；禁止跳过成员后迟到发送或泄漏 HTTP 名额。 */
class DaikinSendWaveTest {
    private DaikinHttpBudget budget(int concurrent,int rate,long waitMillis) {
        return new DaikinHttpBudget(new DaikinClientConfiguration(true,true,URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("test","test","salt","0123456789abcdef"),
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofMillis(waitMillis),Duration.ZERO,10000,concurrent,rate));
    }
    private DaikinHttpBudget.Ticket ready(com.platform.iot.daikin.control.DaikinControlGateway.SendPermit member,DaikinHttpBudget budget) {
        return ((DaikinSendWave.Member)member).ticket(budget);
    }

    @Test void failedPreflightOrAlreadyTargetMemberExitsWithoutHoldingSuccessfulPeer() throws Exception {
        var budget=budget(4,4,2000);var wave=DaikinSendWave.prepare(budget,3,Duration.ofSeconds(3));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var ready=pool.submit(()->ready(wave.getFirst(),budget));
            Thread.sleep(30);assertThat(ready.isDone()).isFalse();
            wave.get(1).close();wave.get(2).close();
            var ticket=ready.get(2,TimeUnit.SECONDS);ticket.start();wave.getFirst().close();
            assertThatThrownBy(()->ready(wave.get(1),budget)).isInstanceOf(DaikinClientException.class);
            assertThat(budget.acquire(3)).hasSize(3).allSatisfy(DaikinHttpBudget.Ticket::close);
        } finally { wave.forEach(com.platform.iot.daikin.control.DaikinControlGateway.SendPermit::close); }
    }

    @Test void waveWaitsForEnoughSharedRateBudgetThenAdmitsAllReadyMembers() throws Exception {
        var budget=budget(4,4,2500);
        var preceding=budget.acquire(3);preceding.forEach(DaikinHttpBudget.Ticket::start);preceding.forEach(DaikinHttpBudget.Ticket::close);
        var wave=DaikinSendWave.prepare(budget,2,Duration.ofSeconds(3));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->ready(wave.getFirst(),budget));var second=pool.submit(()->ready(wave.getLast(),budget));
            Thread.sleep(50);assertThat(first.isDone()).isFalse();assertThat(second.isDone()).isFalse();
            var a=first.get(2,TimeUnit.SECONDS);var b=second.get(2,TimeUnit.SECONDS);
            a.start();b.start();
        } finally { wave.forEach(com.platform.iot.daikin.control.DaikinControlGateway.SendPermit::close); }
    }

    @Test void timedOutWaveNeverReleasesLatePermissionOrLeaksReservedCapacity() throws Exception {
        var budget=budget(4,4,1000);var wave=DaikinSendWave.prepare(budget,2,Duration.ofMillis(30));
        assertThatThrownBy(()->ready(wave.getFirst(),budget)).isInstanceOf(DaikinClientException.class);
        wave.getFirst().close();
        assertThatThrownBy(()->ready(wave.getLast(),budget)).isInstanceOf(DaikinClientException.class);wave.getLast().close();
        assertThat(budget.acquire(4)).hasSize(4).allSatisfy(DaikinHttpBudget.Ticket::close);
    }

    @Test void singleRequestBudgetSplitsWaveWithoutWaitingForLaterMembers() throws Exception {
        var budget=budget(1,4,2000);var wave=DaikinSendWave.prepare(budget,3,Duration.ofSeconds(3));
        for(var member:wave) { ready(member,budget).start();member.close(); }
        assertThat(budget.acquire(1)).hasSize(1).allSatisfy(DaikinHttpBudget.Ticket::close);
    }
}
