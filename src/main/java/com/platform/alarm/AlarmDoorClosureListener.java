package com.platform.alarm;

import com.platform.iot.roomsensor.RoomSensorWorker.ConfirmedDoorClosure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 将已持久化的关门观测交给平台报警服务，恢复对应设备的门开超时事件。 */
@Component
@ConditionalOnProperty(name = "alarm.enabled", havingValue = "true")
class AlarmDoorClosureListener {
    private static final Logger log = LoggerFactory.getLogger(AlarmDoorClosureListener.class);
    private final AlarmService alarms;

    AlarmDoorClosureListener(AlarmService alarms) {
        this.alarms = alarms;
    }

    /** 队列删除已提交后再写报警；失败由常规报警扫描读取已保存的关门事实补偿。 */
    @TransactionalEventListener
    void onClosed(ConfirmedDoorClosure event) {
        try {
            alarms.doorClosed(event.observation());
        } catch (RuntimeException failure) {
            log.warn("门磁关门报警恢复暂不可用，设备={}，异常类型={}",
                    event.observation().equipId(), failure.getClass().getSimpleName());
        }
    }
}
