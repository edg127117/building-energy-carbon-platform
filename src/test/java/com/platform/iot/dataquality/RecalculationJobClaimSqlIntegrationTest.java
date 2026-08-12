package com.platform.iot.dataquality;

import com.platform.iot.dataquality.mapper.BizDataQualityRecalcJobMapper;
import com.platform.iot.dataquality.model.RecalculationJobPhase;
import com.platform.iot.dataquality.model.RecalculationJobStatus;
import com.platform.iot.dataquality.model.RecalculationJobType;
import com.platform.iot.dataquality.model.entity.BizDataQualityRecalcJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecalculationJobClaimSqlIntegrationTest {

    @Autowired
    private BizDataQualityRecalcJobMapper mapper;

    @Test
    void waitingJobHasOneAtomicOwnerAndOnlyThatClaimCanBeReleased() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 12, 16, 0);
        LocalDateTime staleBefore = now.minusMinutes(2);
        BizDataQualityRecalcJob job = waitingJob(now.minusMinutes(5));
        assertThat(mapper.insert(job)).isEqualTo(1);

        assertThat(mapper.selectClaimable(staleBefore, 10))
                .extracting(BizDataQualityRecalcJob::getJobId)
                .contains(job.getJobId());
        assertThat(mapper.claimAtomic(job.getJobId(), staleBefore, now))
                .isEqualTo(1);
        assertThat(mapper.claimAtomic(job.getJobId(), staleBefore, now))
                .isZero();
        assertThat(mapper.releaseClaimAtomic(
                job.getJobId(), job.getCursorMinute(), now.minusSeconds(1)))
                .isZero();
        assertThat(mapper.releaseClaimAtomic(
                job.getJobId(), job.getCursorMinute(), now))
                .isEqualTo(1);

        assertThat(mapper.selectById(job.getJobId()).getStatus())
                .isEqualTo(RecalculationJobStatus.WAITING);
    }

    private BizDataQualityRecalcJob waitingJob(LocalDateTime createdAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        BizDataQualityRecalcJob job = new BizDataQualityRecalcJob();
        job.setJobId("SQL" + suffix);
        job.setIdempotencyKey("SQL-CLAIM-" + suffix);
        job.setJobType(RecalculationJobType.RANGE_RECALCULATE);
        job.setBuildingId("BLD001");
        job.setPointIdsJson("[\"POINT008\"]");
        job.setFromMinute(createdAt);
        job.setToMinute(createdAt.plusMinutes(2));
        job.setReason("条件领取 SQL 回归");
        job.setOperatorId(1L);
        job.setStatus(RecalculationJobStatus.WAITING);
        job.setPhase(RecalculationJobPhase.RECALCULATING);
        job.setCursorMinute(createdAt);
        job.setQ0Count(0);
        job.setQ1Count(0);
        job.setQ2Count(0);
        job.setMissingCount(0);
        job.setVoidedCount(0);
        job.setReplacedCount(0);
        job.setCreateTime(createdAt);
        job.setUpdateTime(createdAt);
        return job;
    }
}
