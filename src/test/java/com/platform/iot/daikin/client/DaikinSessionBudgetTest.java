package com.platform.iot.daikin.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 速率是请求起始间隔，不依赖上一请求返回；普通测试仅使用内存 transport。 */
class DaikinSessionBudgetTest {
    @Test void lowRateCannotWaitPastLocalBudgetAndLaterTransmitAStalePut() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var config=new DaikinClientConfiguration(true,true,URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("test","test","salt","0123456789abcdef"),
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofMillis(100),Duration.ZERO,10000,4,1);
        DaikinHttpTransport transport=(request,max)->{
            calls.incrementAndGet();
            return new DaikinHttpTransport.Response(200,"{\"code\":\"10000\",\"data\":{\"token\":\"t\",\"refreshKey\":\"r\",\"expireAt\":\"2099-01-01T00:00:00Z\"}}".getBytes(StandardCharsets.UTF_8));
        };
        var json=new ObjectMapper();var session=new DaikinSession(config,new DaikinControlWireCodec(json),transport,json,Clock.systemUTC());
        assertThatThrownBy(()->session.controlOnce(DaikinEndpoint.CONTROL_ON_OFF,"v0",Map.of("onOff","on")))
                .isInstanceOfSatisfying(DaikinClientException.class,e->assertThat(e.code()).isEqualTo(DaikinClientException.Code.CONCURRENCY_LIMIT));
        assertThat(calls).hasValue(1); // Only authentication reached transport; the PUT never did.
    }

    @Test void configuredRatePacesStartsWhileResponsesStillOverlap() throws Exception {
        var starts=new CopyOnWriteArrayList<Long>();var release=new CountDownLatch(1);var arrived=new CountDownLatch(3);
        var config=new DaikinClientConfiguration(true,true,URI.create("https://vendor.example.test"),
                new DaikinClientConfiguration.Credentials("test","test","salt","0123456789abcdef"),
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofSeconds(10),Duration.ZERO,10000,3,4);
        DaikinHttpTransport transport=(request,max)->{
            String response;
            if(request.uri().getPath().equals("/token")) response="{\"code\":\"10000\",\"data\":{\"token\":\"t\",\"refreshKey\":\"r\",\"expireAt\":\"2099-01-01T00:00:00Z\"}}";
            else {
                starts.add(System.nanoTime());arrived.countDown();
                if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("请求被响应串行化");
                response="{\"code\":\"10000\"}";
            }
            return new DaikinHttpTransport.Response(200,response.getBytes(StandardCharsets.UTF_8));
        };
        var session=new DaikinSession(config,new DaikinControlWireCodec(new ObjectMapper()),transport,new ObjectMapper(),Clock.systemUTC());
        try(var pool=Executors.newFixedThreadPool(3)) {
            var futures=new ArrayList<Future<?>>();
            try {
                for(int i=0;i<3;i++) { String id="v"+i;futures.add(pool.submit(()->session.controlOnce(DaikinEndpoint.CONTROL_ON_OFF,id,Map.of("onOff","on")))); }
                assertThat(arrived.await(4,TimeUnit.SECONDS)).isTrue();
                var sorted=starts.stream().sorted().toList();
                assertThat(sorted).hasSize(3);
                for(int i=1;i<3;i++) assertThat(sorted.get(i)-sorted.get(i-1)).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(230));
            } finally { release.countDown(); }
            for(var future:futures) future.get(5,TimeUnit.SECONDS);
        }
    }
}
