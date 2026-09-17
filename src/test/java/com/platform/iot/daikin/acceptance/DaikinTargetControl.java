package com.platform.iot.daikin.acceptance;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/** 测试源码中的本机控制面；生产产物没有该类。 */
@RestController
@RequestMapping("/__test/daikin")
@Profile("daikin-target")
@ConditionalOnProperty(name = {"daikin.target.enabled", "daikin.target.isolated"}, havingValue = "true")
final class DaikinTargetControl {
    private final DaikinTargetFixture fixture;
    private final DaikinTargetVendor vendor;

    DaikinTargetControl(DaikinTargetFixture fixture, DaikinTargetVendor vendor) {
        this.fixture = fixture;
        this.vendor = vendor;
    }

    @GetMapping("/state")
    Map<String, Object> state(HttpServletRequest request) {
        requireLoopback(request);
        return withExpectedInput(fixture.state());
    }

    @PostMapping("/reset")
    Map<String, Object> reset(HttpServletRequest request) {
        requireLoopback(request);
        vendor.equipmentFault(false);
        vendor.runtimeFailure(false);
        vendor.temperature(new BigDecimal("24.5"));
        return withExpectedInput(fixture.resetObservations());
    }

    @PostMapping("/sync")
    Map<String, Object> sync(HttpServletRequest request) {
        requireLoopback(request);
        return withExpectedInput(fixture.syncDirectory());
    }

    /** 页面先走生产 API 入队；本机驱动只推进已存在任务，不替页面提交业务请求。 */
    @PostMapping("/dispatch")
    Map<String, Object> dispatch(HttpServletRequest request) {
        requireLoopback(request);
        return withExpectedInput(fixture.dispatchDirectory());
    }

    @PostMapping("/approve")
    Map<String, Object> approve(HttpServletRequest request) {
        requireLoopback(request);
        return withExpectedInput(fixture.approveAndActivate());
    }

    @PostMapping("/collect")
    Map<String, Object> collect(HttpServletRequest request) {
        requireLoopback(request);
        return withExpectedInput(fixture.collect());
    }

    @PostMapping("/fault")
    Map<String, Object> fault(HttpServletRequest request) {
        requireLoopback(request);
        vendor.equipmentFault(true);
        return withExpectedInput(fixture.collect());
    }

    @PostMapping("/recover")
    Map<String, Object> recover(HttpServletRequest request) {
        requireLoopback(request);
        vendor.equipmentFault(false);
        return withExpectedInput(fixture.collect());
    }

    @PostMapping("/runtime")
    Map<String, Object> runtime(HttpServletRequest request) {
        requireLoopback(request);
        return withExpectedInput(fixture.collectRuntime());
    }

    @PostMapping("/temperature/{value}")
    Map<String, Object> temperature(HttpServletRequest request, @PathVariable BigDecimal value) {
        requireLoopback(request);
        vendor.temperature(value);
        return withExpectedInput(fixture.state());
    }

    private Map<String, Object> withExpectedInput(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>(source);
        result.put("expectedRoomTemp", vendor.temperature());
        result.put("equipmentFaultInjected", vendor.equipmentFault());
        return result;
    }

    private static void requireLoopback(HttpServletRequest request) {
        try {
            InetAddress address = InetAddress.getByName(request.getRemoteAddr());
            if (!address.isLoopbackAddress()) throw new IllegalStateException("DAIKIN_TARGET_LOOPBACK_REQUIRED");
        } catch (java.net.UnknownHostException invalid) {
            throw new IllegalStateException("DAIKIN_TARGET_LOOPBACK_REQUIRED");
        }
    }
}
