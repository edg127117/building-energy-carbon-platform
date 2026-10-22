package com.platform.iot.dataquality;

import com.platform.iot.dataquality.model.*;
import com.platform.iot.dataquality.model.entity.BizDataQualityFillTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

/** H2 与真实 Spring 事务验证补全证据先提交，不连接 TDengine 或运行环境。 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmedInterpolationTransactionTest {
    @Autowired FillTaskRepository repository;
    @Autowired FillTaskEvidenceCodec codec;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc;
    @Test void committedEvidenceSurvivesDownstreamTransactionRollbackAndKeepsTaskIdentity() {
        var candidate=new BizDataQualityFillTask();candidate.setIdempotencyKey("D01_Q1:"+UUID.randomUUID());
        candidate.setBuildingId("BLD001");candidate.setPointId("POINT001");
        candidate.setStartMinute(LocalDateTime.ofInstant(Instant.ofEpochMilli(60000),ZoneId.of("Asia/Shanghai")));
        candidate.setEndMinute(LocalDateTime.ofInstant(Instant.ofEpochMilli(120000),ZoneId.of("Asia/Shanghai")));
        candidate.setMinuteCount(1);candidate.setDataQuality(1);candidate.setSourceType(FillSourceType.INTERPOLATION);
        candidate.setAlgorithmVersion(InterpolationFillService.ALGORITHM_VERSION);
        candidate.setEvidenceJson(codec.encode(FillSourceType.INTERPOLATION,new FillTaskEvidence.Interpolation(0,100,120000,300,InterpolationFillService.ALGORITHM_VERSION)));
        var id=new AtomicReference<String>();var tx=new TransactionTemplate(transactionManager);
        try {
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                id.set(repository.getOrCreateDurably(candidate).getTaskId());throw new IllegalStateException("READY failed after TD write");
            })).hasMessageContaining("READY failed");
            assertThat(repository.findById(id.get())).isPresent();
            assertThat(repository.getOrCreateDurably(candidate).getTaskId()).isEqualTo(id.get());
        } finally {if(id.get()!=null)jdbc.update("DELETE FROM biz_data_quality_fill_task WHERE task_id=?",id.get());}
    }
}
