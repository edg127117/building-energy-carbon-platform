package com.platform.carbon;

/** Reuse all source-integrity/deadline regressions with the opt-in enabled. */
class CurrentReviewedDayVersionBudgetBatchedTest extends CurrentReviewedDayVersionBudgetTest {
    @Override @org.junit.jupiter.api.BeforeEach void controlledStatementClock() {
        super.controlledStatementClock();
        org.springframework.test.util.ReflectionTestUtils.setField(repository,"currentDayBatchedReadEnabled",true);
    }
}
