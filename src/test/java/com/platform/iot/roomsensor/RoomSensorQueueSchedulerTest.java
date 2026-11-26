package com.platform.iot.roomsensor;

import com.platform.iot.communication.RoomSensorCommunicationQueue;
import com.platform.iot.roomsensor.RoomSensorQueueScheduler.DrainResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RoomSensorQueueSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-26T02:00:00Z");
    private final ScheduledTasks observationTasks = new ScheduledTasks();
    private final ScheduledTasks communicationTasks = new ScheduledTasks();
    private final RoomSensorWorker observations = mock(RoomSensorWorker.class);
    private final RoomSensorCommunicationQueue communications = mock(RoomSensorCommunicationQueue.class);
    private final RoomSensorQueueScheduler queues = new RoomSensorQueueScheduler(
            observations, communications, observationTasks.scheduler, communicationTasks.scheduler, 250);
    private final TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:queue-scheduler-" + UUID.randomUUID(), "sa", "")));

    @Test
    void wakesOnlyTheCommittedQueueAndIgnoresRollback() {
        transaction.executeWithoutResult(status -> {
            queues.wakeAfterCommit(true, true);
            assertThat(observationTasks.tasks).isEmpty();
            assertThat(communicationTasks.tasks).isEmpty();
            status.setRollbackOnly();
        });
        assertThat(observationTasks.tasks).isEmpty();
        assertThat(communicationTasks.tasks).isEmpty();

        transaction.executeWithoutResult(status -> queues.wakeAfterCommit(false, true));
        assertThat(observationTasks.tasks).isEmpty();
        assertThat(communicationTasks.tasks).singleElement().satisfies(task ->
                assertThat(task.when()).isEqualTo(NOW.plusMillis(250)));
        transaction.executeWithoutResult(status -> queues.wakeAfterCommit(true, false));
        assertThat(observationTasks.tasks).singleElement().satisfies(task -> assertThat(task.when()).isEqualTo(NOW));
    }

    @Test
    void mergesPendingSignalsAndKeepsTheSignalThatArrivesDuringDrain() {
        when(observations.flush()).thenAnswer(call -> {
            transaction.executeWithoutResult(status -> queues.wakeAfterCommit(true, false));
            return DrainResult.IDLE;
        }).thenReturn(DrainResult.IDLE);

        transaction.executeWithoutResult(status -> queues.wakeAfterCommit(true, true));
        transaction.executeWithoutResult(status -> queues.wakeAfterCommit(true, true));
        queues.checkObservations();
        queues.checkCommunications();
        assertThat(observationTasks.tasks).hasSize(1);
        assertThat(communicationTasks.tasks).hasSize(1);

        observationTasks.runNext();
        assertThat(observationTasks.tasks).hasSize(1);
        observationTasks.runNext();
        assertThat(observationTasks.tasks).isEmpty();
        verify(observations, times(2)).flush();
        verifyNoInteractions(communications);
    }

    @Test
    void fullBatchesContinueImmediatelyAndFailuresKeepARetryDelay() {
        when(observations.flush()).thenReturn(DrainResult.MORE_WORK, DrainResult.RETRY_LATER, DrainResult.IDLE);
        when(communications.flush()).thenReturn(DrainResult.MORE_WORK, DrainResult.IDLE);
        queues.checkObservations();
        queues.checkCommunications();

        observationTasks.runNext();
        communicationTasks.runNext();
        assertThat(observationTasks.tasks).singleElement().satisfies(task -> assertThat(task.when()).isEqualTo(NOW));
        assertThat(communicationTasks.tasks).singleElement().satisfies(task -> assertThat(task.when()).isEqualTo(NOW));

        observationTasks.runNext();
        transaction.executeWithoutResult(status -> queues.wakeAfterCommit(true, false));
        queues.checkObservations();
        assertThat(observationTasks.tasks).singleElement().satisfies(task ->
                assertThat(task.when()).isEqualTo(NOW.plus(Duration.ofSeconds(1))));
        observationTasks.runNext();
        communicationTasks.runNext();
        assertThat(observationTasks.tasks).isEmpty();
        assertThat(communicationTasks.tasks).isEmpty();
    }

    @Test
    void failedWakeupLeavesTheWatchdogAbleToSchedule() {
        var schedule = observationTasks.scheduler;
        doThrow(new RejectedExecutionException("isolated rejection")).doAnswer(observationTasks::record)
                .when(schedule).schedule(any(Runnable.class), any(Instant.class));
        transaction.executeWithoutResult(status -> queues.wakeAfterCommit(true, false));
        assertThat(observationTasks.tasks).isEmpty();
        queues.checkObservations();
        assertThat(observationTasks.tasks).hasSize(1);
    }

    private record Task(Runnable runnable, Instant when) { }

    private static final class ScheduledTasks {
        private final ArrayDeque<Task> tasks = new ArrayDeque<>();
        private final TaskScheduler scheduler = mock(TaskScheduler.class);

        private ScheduledTasks() {
            when(scheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
            doAnswer(this::record).when(scheduler).schedule(any(Runnable.class), any(Instant.class));
        }

        private Object record(org.mockito.invocation.InvocationOnMock invocation) {
            tasks.add(new Task(invocation.getArgument(0), invocation.getArgument(1)));
            return null;
        }

        private void runNext() {
            tasks.remove().runnable().run();
        }
    }
}
