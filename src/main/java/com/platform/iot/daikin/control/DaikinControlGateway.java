package com.platform.iot.daikin.control;

import com.fasterxml.jackson.databind.JsonNode;
import static com.platform.iot.daikin.control.DaikinControlContracts.Action;

/** 厂家边界只允许单次下发；测试直接注入内存替身，生产网关须双重显式启用。 */
public interface DaikinControlGateway {
    boolean supports(String sourceId);
    Snapshot read(DaikinControlAccess.Target target);
    Reply send(DaikinControlAccess.Target target, Action action, String value);

    /** 适配器确认控制 PUT 尚未调用 transport；一旦开始传输，任何失败都不能使用此异常解除隔离。 */
    final class NotSentException extends RuntimeException {
        private final String reason;
        public NotSentException(String reason) {
            super("本地控制请求未发送");
            if(!java.util.Set.of("HTTP_BUDGET_UNAVAILABLE","CONTROL_AUTH_UNAVAILABLE").contains(reason)) throw new IllegalArgumentException("Invalid local refusal");
            this.reason=reason;
        }
        public String reason() { return reason; }
    }

    /** 仅协调同批已领取项；未走到发送的成员须 close 退出，不新增厂家批量接口或重试语义。 */
    interface SendPermit extends AutoCloseable {
        void awaitReady();
        @Override void close();
    }
    static SendPermit uncoordinated() {
        return new SendPermit() { public void awaitReady() { } public void close() { } };
    }
    default java.util.List<SendPermit> prepareSendWave(int size) {
        return java.util.stream.IntStream.range(0,size).mapToObj(i->uncoordinated()).toList();
    }
    default Reply send(DaikinControlAccess.Target target, Action action, String value, SendPermit permit) {
        permit.awaitReady();return send(target,action,value);
    }

    record Snapshot(JsonNode controller, JsonNode unit) { }
    record Reply(String code) { }
}
