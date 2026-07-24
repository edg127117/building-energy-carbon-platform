package com.platform.iot.temporal;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class HvacRawEventRetentionServiceTest {

    @Test
    void deletesOnlyRawEventsBeforeConfiguredNinetyDayCutoff() {
        HvacRawEventRepository repository = mock(HvacRawEventRepository.class);
        HvacRawEventRetentionService service = new HvacRawEventRetentionService(repository, 90);
        long now = Instant.parse("2026-07-23T00:00:00Z").toEpochMilli();

        service.cleanup(now);

        verify(repository).deleteBefore(now - Duration.ofDays(90).toMillis());
    }

    @Test
    void rejectsInvalidRetentionInsteadOfDeletingEverything() {
        HvacRawEventRepository repository = mock(HvacRawEventRepository.class);

        assertThatThrownBy(() -> new HvacRawEventRetentionService(repository, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
