package com.platform.carbon;

/** Reuse all source-integrity/deadline regressions with the opt-in enabled. */
class CurrentReviewedDayVersionReadBatchedTest extends CurrentReviewedDayVersionReadTest {
    @Override @org.junit.jupiter.api.BeforeEach void setup() {
        super.setup();
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"currentDayBatchedReadEnabled",true);
    }
}
