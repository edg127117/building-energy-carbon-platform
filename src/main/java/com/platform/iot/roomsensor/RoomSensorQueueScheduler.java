package com.platform.iot.roomsensor;

import com.platform.iot.communication.RoomSensorCommunicationQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.function.Supplier;

/** 用提交后的通知启动房间队列投递，以定时检查恢复漏通知、重启和到期重试。 */
@Component
@ConditionalOnProperty(name = "room-sensor.enabled", havingValue = "true")
public class RoomSensorQueueScheduler {
    private static final Logger log = LoggerFactory.getLogger(RoomSensorQueueScheduler.class);
    // 入队唤醒取代旧的定时轮询。兜底周期独立于仍随同版本发布包保留的旧配置。
    private static final long IDLE_CHECK_MILLIS = 1000;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    private final Worker observations;
    private final Worker communications;

    public RoomSensorQueueScheduler(RoomSensorWorker observationQueue, RoomSensorCommunicationQueue communicationQueue,
            @Qualifier("roomSensorTaskScheduler") TaskScheduler observationScheduler,
            @Qualifier("taskScheduler") TaskScheduler communicationScheduler,
            @Value("${room-sensor.communication-flush-delay-ms:250}") long communicationBatchDelayMillis) {
        observations = new Worker(observationScheduler, observationQueue::flush, Duration.ZERO);
        communications = new Worker(communicationScheduler, communicationQueue::flush,
                Duration.ofMillis(communicationBatchDelayMillis));
    }

    void wakeAfterCommit(boolean observationEnqueued, boolean communicationEnqueued) {
        if (!observationEnqueued && !communicationEnqueued) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (observationEnqueued) observations.wake();
                if (communicationEnqueued) communications.wake();
            }
        });
    }

    @Scheduled(fixedDelay = IDLE_CHECK_MILLIS, scheduler = "roomSensorTaskScheduler")
    void checkObservations() {
        observations.checkIfIdle();
    }

    @Scheduled(fixedDelay = IDLE_CHECK_MILLIS, scheduler = "taskScheduler")
    void checkCommunications() {
        communications.checkIfIdle();
    }

    public enum DrainResult { IDLE, MORE_WORK, RETRY_LATER }

    private static final class Worker {
        private final TaskScheduler scheduler;
        private final Supplier<DrainResult> drain;
        private final Duration batchDelay;
        private boolean scheduledOrRunning;
        private boolean signalled;

        private Worker(TaskScheduler scheduler, Supplier<DrainResult> drain, Duration batchDelay) {
            this.scheduler = scheduler;
            this.drain = drain;
            this.batchDelay = batchDelay;
        }

        synchronized void wake() {
            signalled = true;
            if (!scheduledOrRunning) schedule(batchDelay);
        }

        synchronized void checkIfIdle() {
            if (!scheduledOrRunning) schedule(Duration.ZERO);
        }

        private void schedule(Duration delay) {
            scheduledOrRunning = true;
            try {
                scheduler.schedule(this::run, scheduler.getClock().instant().plus(delay));
            } catch (RuntimeException unavailable) {
                // 通知失败发生在提交以后，定时检查继续处理已保存的数据。
                scheduledOrRunning = false;
                log.warn("房间传感器队列暂未调度: {}", unavailable.getClass().getSimpleName());
            }
        }

        private void run() {
            synchronized (this) {
                // 处理开始前的通知已合并。处理期间的新通知留给下一轮，以免漏掉刚提交的待办。
                signalled = false;
            }
            DrainResult result = DrainResult.RETRY_LATER;
            try {
                result = drain.get();
            } finally {
                complete(result);
            }
        }

        private synchronized void complete(DrainResult result) {
            switch (result) {
                case MORE_WORK -> schedule(Duration.ZERO);
                case RETRY_LATER -> schedule(RETRY_DELAY);
                case IDLE -> {
                    if (signalled) schedule(batchDelay);
                    else scheduledOrRunning = false;
                }
            }
        }
    }
}
