package com.platform.audit.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.audit.AuditGovernanceErrors;
import com.platform.audit.AuditGovernanceProperties;
import com.platform.audit.query.AuditQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditExportWorkerTest {
    @Test
    void csvEscapesFormulaAndStructureCharacters() {
        assertThat(AuditExportWorker.csv("=HYPERLINK(\"bad\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"bad\"\")\"");
        assertThat(AuditExportWorker.csv("a,b\nc"))
                .isEqualTo("\"a,b\nc\"");
        assertThat(AuditExportWorker.csv(null)).isEmpty();
    }

    @Test
    void rejectedDispatchFailsPersistentJobInsteadOfLeavingPending() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        AuditExportRepository repository = mock(AuditExportRepository.class);
        AuditExportFinalizer finalizer = mock(AuditExportFinalizer.class);
        AuditExportJob job = new AuditExportJob(
                "00000000000000000000000000000001", 1L, "test", "{}", "hash", "PENDING",
                null, null, null, LocalDateTime.now().plusMinutes(1), null, "trace",
                LocalDateTime.now(), null, null, null);
        when(repository.find(job.exportId())).thenReturn(Optional.of(job));
        AuditExportWorker worker = new AuditExportWorker(
                repository, mock(AuditQueryService.class), finalizer,
                new AuditGovernanceProperties(), new ObjectMapper(), executor);

        try {
            worker.dispatch(new AuditExportRequested(job.exportId()));
            verify(finalizer).fail(eq(job), eq(AuditGovernanceErrors.EXPORT_QUEUE_FULL), anyString());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
