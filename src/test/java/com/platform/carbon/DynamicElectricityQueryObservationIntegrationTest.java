package com.platform.carbon;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static com.platform.carbon.DynamicElectricityRepository.hash;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DynamicElectricityQueryObservationIntegrationTest extends QueryDayCacheFixture {
    @Test void realQueryReportsStoredValidationFactorsAndCurrentRawReadsWithoutChangingTheView() {
        seedDay(2, 2);
        saveHours(2, 2);
        var expected = readOnlyRepeatableRead().execute(status -> service.query(USER, ROLES, BUILDING, "DAY", PERIOD));
        clearInvocations(repository, reader);
        try (var capture = new Capture()) {
            var actual = readOnlyRepeatableRead().execute(status -> service.query(USER, ROLES, BUILDING, "DAY", PERIOD));
            assertThat(actual).isEqualTo(expected);
            assertThat(capture.last()).contains("event=request_end", "success=true",
                    "periodType=DAY", "period=" + PERIOD,
                    "STORED_RESULTS:1/1/0/", "STORED_HEADERS:1/1/0/", "METADATA:1/1/0/", "FACTORS:2/2/0/",
                    "CANDIDATE_HEADERS:1/1/0/", "CURRENT_RAW_BODIES:1/1/0/", "DAY_LOAD:1/1/0/",
                    "STORED_ROWS:2", "FACTOR_ROWS:2", "CANDIDATE_ROWS:4", "CURRENT_RAW_ROWS:4", "DAY_FALLBACKS:0");
            assertThat(capture.messages()).hasSizeLessThanOrEqualTo(DynamicElectricityQueryObservation.MAX_EVENTS)
                    .allSatisfy(message -> assertThat(message).doesNotContain("isolated-query-fixture", "fixed-query-metadata", "point-000", "reading_json"));
        }
        verify(repository, times(2)).factors(eq(BUILDING), any(), any());
        verify(repository).loadCurrentReviewedDay(eq(BUILDING), eq(FROM), eq(DAY_END), any());
        verify(repository, never()).electricityVersion(anyString(), any(), any(), anyString());
        verifyNoInteractions(reader);
    }

    @Test void changedEvidenceHashReferenceFailsBeforeMetadataAndCurrentness() {
        seedDay(2, 2);
        var rows = saveHours(2, 2);
        jdbc.update("UPDATE biz_dynamic_electricity_result SET payload_hash=? WHERE result_id=?", hash("wrong"), rows.getFirst().id());
        try (var capture = new Capture()) {
            assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status -> service.query(USER, ROLES, BUILDING, "DAY", PERIOD)))
                    .isInstanceOf(com.platform.framework.exception.BusinessException.class)
                    .hasMessageContaining("证据引用不一致");
            assertThat(capture.last()).contains("success=false", "STORED_HEADERS:1/0/1/",
                    "METADATA:0/0/0/0", "DAY_LOAD:0/0/0/0");
        }
    }

    @Test void invalidRawPeriodNeverBecomesALogLabelOrReachesTheRepository() {
        clearInvocations(repository);
        try (var capture = new Capture()) {
            assertThatThrownBy(() -> service.query(USER, ROLES, BUILDING, "YEAR", "private-invalid-period"))
                    .isInstanceOf(com.platform.framework.exception.BusinessException.class);
            assertThat(capture.messages()).allSatisfy(message -> assertThat(message)
                    .contains("periodType=UNVALIDATED", "period=UNVALIDATED").doesNotContain("private-invalid-period"));
        }
        verifyNoInteractions(repository);
    }

    @Test void corruptFactorStillFailsAndIsNotConvertedToADegradedView() {
        seedDay(2, 2);
        saveHours(2, 2);
        jdbc.update("UPDATE biz_dynamic_electricity_factor SET payload_hash=?", hash("wrong"));
        try (var capture = new Capture()) {
            assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status -> service.query(USER, ROLES, BUILDING, "DAY", PERIOD)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(capture.last()).contains("success=false", "STORED_HEADERS:1/1/0/", "FACTORS:1/0/1/", "DAY_LOAD:0/0/0/0");
        }
    }

    @Test void failedCurrentSourceSqlRetainsItsFailureAndItsStartedStage() {
        seedDay(2, 2);
        saveHours(2, 2);
        jdbc.execute("DROP TABLE biz_energy_manual_reading");
        try (var capture = new Capture()) {
            assertThatThrownBy(() -> readOnlyRepeatableRead().execute(status -> service.query(USER, ROLES, BUILDING, "DAY", PERIOD)))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(capture.messages()).anySatisfy(message -> assertThat(message)
                    .contains("event=phase_begin", "observedPhase=CANDIDATE_HEADERS", "CANDIDATE_HEADERS:1/0/0/0"));
            assertThat(capture.last()).contains("success=false", "CANDIDATE_HEADERS:1/0/1/", "DAY_LOAD:1/0/1/",
                    "CURRENT_SOURCE_LEGACY:0/0/0/0");
        }
    }

    @Test void optionalCapacityFallbackIsCountedOncePerDayWhileEveryLegacyHourRemainsChecked() {
        seedDay(2, 2);
        saveHours(2, 2);
        doReturn(Optional.empty()).when(repository).loadCurrentReviewedDay(anyString(), any(), any(), any());
        try (var capture = new Capture()) {
            var view = readOnlyRepeatableRead().execute(status -> service.query(USER, ROLES, BUILDING, "DAY", PERIOD));
            assertThat(view.points().subList(0, 2)).allMatch(point -> "COMPLETE".equals(point.status()));
            assertThat(capture.last()).contains("success=true", "CURRENT_SOURCE_LEGACY:2/2/0/", "DAY_FALLBACKS:1");
        }
    }

    private static final class Capture implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(DynamicElectricityQueryObservation.class);
        private final Level previous = logger.getLevel();
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private Capture() {
            appender.start();
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
        List<String> messages() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList(); }
        String last() { return messages().getLast(); }
        @Override public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }
}
