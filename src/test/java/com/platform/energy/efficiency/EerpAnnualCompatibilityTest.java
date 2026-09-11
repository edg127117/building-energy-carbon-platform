package com.platform.energy.efficiency;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.platform.energy.efficiency.EerpContracts.*;
import static com.platform.energy.efficiency.EerpServiceTest.*;
import static org.assertj.core.api.Assertions.*;

class EerpAnnualCompatibilityTest {
    @Test
    void readsLegacyAnnualStageWithoutFillingOrRewritingItsRoundingFields() throws Exception {
        var f=new EerpServiceTest(); f.setup();
        var task=f.service.createAnnual(1,ROLES,new AnnualRequest("legacy",B,S,2025,"UTC","tz1",List.of(),null));
        var stage=f.repo.task(task.taskId()).stage();
        var execution=f.codec.read(stage,EerpPeriodCalculator.Execution.class);
        ObjectNode legacy=(ObjectNode)f.mapper.readTree(execution.resultJson());
        legacy.put("eerp",5); legacy.remove(List.of("displayEerp","roundingVersion","displayScale","roundingMode"));
        // 构造旧版持久化格式；查询必须原样读取，不补写新版展示规则或改动证据哈希。
        String legacyStage=f.codec.json(new EerpPeriodCalculator.Execution(f.mapper.writeValueAsString(legacy),execution.evidenceJson(),execution.numericSnapshots()));
        String legacyHash=EerpSupport.hash(legacyStage);
        f.jdbc.update("UPDATE energy_eerp_task SET stage_json=?,evidence_hash=? WHERE task_id=?",legacyStage,legacyHash,task.taskId());
        f.service=f.newService();
        var read=(AnnualResult)f.service.task(1,ROLES,task.taskId()).result();
        assertThat(read.eerp()).isEqualByComparingTo("5");
        assertThat(read.displayEerp()).isNull(); assertThat(read.roundingVersion()).isNull();
        assertThat(read.displayScale()).isNull(); assertThat(read.roundingMode()).isNull();
        f.service.trace(1,ROLES,task.taskId()); f.service.execute(1,ROLES,task.taskId());
        assertThat(f.repo.task(task.taskId()).stage()).isEqualTo(legacyStage);
        assertThat(f.repo.task(task.taskId()).evidenceHash()).isEqualTo(legacyHash);
    }
}
