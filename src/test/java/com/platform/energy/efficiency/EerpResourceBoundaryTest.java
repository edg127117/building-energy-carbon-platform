package com.platform.energy.efficiency;

import com.platform.framework.exception.BusinessException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import static com.platform.energy.efficiency.EerpContracts.*;
import static com.platform.energy.efficiency.EerpServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EerpResourceBoundaryTest {
    @Test
    void acceptsAnnualHardLimitAndRejectsOneMoreBeforeCreatingTask() {
        var f=new EerpServiceTest(); f.setup(); f.limits.setMaximumAnnualSegments(5000);
        var ids=IntStream.range(0,5000).mapToObj(i -> "period"+i).toList();
        var accepted=f.service.createAnnual(1,ROLES,new AnnualRequest("limit",B,S,2025,"UTC","tz1",ids,null));
        assertThat(accepted.failureCode()).as("5000条通过容量门禁，随后正确报告不存在的输入").isEqualTo("EERP_NOT_FOUND");
        var overflow=IntStream.range(0,5001).mapToObj(i -> "period"+i).toList();
        assertThatThrownBy(() -> f.service.createAnnual(1,ROLES,new AnnualRequest("overflow",B,S,2025,"UTC","tz1",overflow,null)))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getErrorCode()).isEqualTo("EERP_CAPACITY_EXCEEDED"));
        assertThat(f.repo.byKey(B,S,"overflow")).isNull();
    }

    @Test
    void evidenceBudgetAcceptsExactBytesAndRejectsOneExtraAtDefaultAndHardMaximum() {
        var f=new EerpServiceTest(); f.setup(); var config=f.activate(f.configuration(false)); f.baselineHour();
        var original=f.period(config,START,START.plusSeconds(3600),"seed");
        String result=f.codec.json(original.result());
        f.calculator=mock(EerpPeriodCalculator.class); f.service=f.newService();
        int overhead=f.codec.json(new EerpPeriodCalculator.Execution(result,"",List.of())).getBytes(StandardCharsets.UTF_8).length;
        for(int maximum:List.of(8388608,16777216)) for(int extra:List.of(0,1)) {
            f.limits.setMaximumEvidenceBytes(maximum);
            var execution=new EerpPeriodCalculator.Execution(result,"x".repeat(maximum-overhead+extra),List.of());
            when(f.calculator.calculate(anyLong(),anyCollection(),anyString(),any(),any(),any())).thenReturn(execution);
            var task=f.period(config,START,START.plusSeconds(3600),"bytes-"+maximum+"-"+extra);
            if(extra==0) {
                assertThat(task.status()).isEqualTo("SUCCEEDED");
                assertThat(f.repo.task(task.taskId()).stage().getBytes(StandardCharsets.UTF_8)).hasSize(maximum);
            } else {
                assertThat(task.failureCode()).isEqualTo("EERP_CAPACITY_EXCEEDED");
                assertThat(f.repo.task(task.taskId()).stage()).isNull(); assertThat(task.result()).isNull();
            }
        }
    }

    @Test
    void timedOutDependencyCannotPublishSuccessAndExplicitRetryCanRecover() {
        var f=new EerpServiceTest(); f.setup(); var config=f.activate(f.configuration(false)); f.baselineHour();
        var real=f.calculator;
        f.limits.setExecutionTimeoutSeconds(5); f.calculator=mock(EerpPeriodCalculator.class); f.service=f.newService();
        when(f.calculator.calculate(anyLong(),anyCollection(),anyString(),any(),any(),any())).thenAnswer(i -> {
            Thread.sleep(5100); ((Runnable)i.getArgument(5)).run(); throw new AssertionError("budget must reject");
        });
        var failed=f.period(config,START,START.plusSeconds(3600),"timeout");
        assertThat(failed.failureCode()).isEqualTo("EERP_EXECUTION_TIMEOUT"); assertThat(failed.result()).isNull();
        verifyNoInteractions(f.values);
        f.calculator=real; f.service=f.newService();
        assertThat(f.service.execute(1,ROLES,failed.taskId()).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void configurationHardLimitsAreValidatedTogether() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var limits=new EerpLimits(); limits.setMaximumPeriodSeconds(86400); limits.setMaximumPoints(32);
            limits.setMaximumAnnualSegments(5000); limits.setMaximumConcurrentTasks(4);
            limits.setExecutionTimeoutSeconds(60); limits.setMaximumEvidenceBytes(16777216);
            assertThat(factory.getValidator().validate(limits)).isEmpty();
            limits.setMaximumPeriodSeconds(86401); limits.setMaximumPoints(33); limits.setMaximumAnnualSegments(5001);
            limits.setMaximumConcurrentTasks(5); limits.setExecutionTimeoutSeconds(61); limits.setMaximumEvidenceBytes(16777217);
            assertThat(factory.getValidator().validate(limits)).hasSize(6);
        }
    }
}
