package com.platform.hvac.service;

import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.mapper.BizPointNamingRuleMapper;
import com.platform.hvac.mapper.BizSystemGroupMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.hvac.model.entity.BizPointNamingRule;
import com.platform.hvac.service.impl.BizDataPointServiceImpl;
import com.platform.iot.quality.MySqlDataPointConfigProvider;
import com.platform.relation.RelationGovernanceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证测点档案写入边界的单位契约，确保非法计算测点不会进入 MySQL 或配置快照。
 */
@ExtendWith(MockitoExtension.class)
class BizDataPointServiceImplTest {

    private static final String POINT_ID = "POINT_TEST";
    private static final String POINT_CODE = "ENV_T";
    private static final String RULE_ID = "RULE_ENV_T";

    @Mock
    private BizDataPointMapper dataPointMapper;
    @Mock
    private MySqlDataPointConfigProvider configProvider;
    @Mock
    private BizEquipmentMapper equipmentMapper;
    @Mock
    private BizSystemGroupMapper systemGroupMapper;
    @Mock
    private BizPointNamingRuleMapper namingRuleMapper;
    @Mock
    private PointCodeNamingValidator namingValidator;
    @Mock
    private RelationGovernanceGuard relationGuard;

    private BizDataPointServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BizDataPointServiceImpl(
                configProvider,
                equipmentMapper,
                systemGroupMapper,
                namingRuleMapper,
                namingValidator,
                relationGuard);
        ReflectionTestUtils.setField(service, "baseMapper", dataPointMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", " \t "})
    void rejectsAddWhenOnlineCalculationAnalogHasNoUnit(String unit) {
        BizDataPoint point = validPoint();
        point.setUnit(unit);
        stubValidRelationships();

        assertUnitValidationFailure(() -> service.add(point));

        verify(dataPointMapper, never()).insert(any());
        verify(configProvider, never()).refreshAll();
    }

    @Test
    void rejectsUpdateWhenMergedFinalStateHasNoUnit() {
        BizDataPoint existing = validPoint();
        existing.setPointId(POINT_ID);
        existing.setStatus("OFFLINE");
        existing.setUnit(" ");
        when(dataPointMapper.selectById(POINT_ID)).thenReturn(existing);
        stubValidRelationships();

        BizDataPoint update = new BizDataPoint();
        update.setPointId(POINT_ID);
        update.setStatus("ONLINE");

        assertUnitValidationFailure(() -> service.update(update));

        verify(dataPointMapper, never()).updateById(any());
        verify(configProvider, never()).refreshAll();
    }

    @Test
    void allowsAddWhenRequiredUnitIsConfigured() {
        BizDataPoint point = validPoint();
        when(dataPointMapper.insert(point)).thenReturn(1);
        stubValidRelationships();

        var result = service.add(point);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getUnit()).isEqualTo("℃");
        verify(dataPointMapper).insert(point);
        verify(configProvider).refreshAll();
    }

    @Test
    void allowsUpdateWhenRequiredUnitIsConfigured() {
        BizDataPoint existing = validPoint();
        existing.setPointId(POINT_ID);
        when(dataPointMapper.selectById(POINT_ID)).thenReturn(existing);
        when(dataPointMapper.updateById(any(BizDataPoint.class))).thenReturn(1);
        stubValidRelationships();

        BizDataPoint update = new BizDataPoint();
        update.setPointId(POINT_ID);
        update.setPointName("修改后的环境温度");
        update.setUnit("K");

        var result = service.update(update);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getUnit()).isEqualTo("K");
        ArgumentCaptor<BizDataPoint> saved = ArgumentCaptor.forClass(BizDataPoint.class);
        verify(dataPointMapper).updateById(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("ONLINE");
        assertThat(saved.getValue().getIsForCalc()).isEqualTo(1);
        verify(relationGuard, never()).rejectChangedProjection(any(), any(), any());
        verify(configProvider).refreshAll();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unitRuleExemptions")
    void allowsPointOutsideUnitRule(
            String scenario, String status, String dataType, Integer isForCalc) {
        BizDataPoint point = validPoint();
        point.setStatus(status);
        point.setDataType(dataType);
        point.setIsForCalc(isForCalc);
        point.setUnit(" ");
        when(dataPointMapper.insert(point)).thenReturn(1);
        stubValidRelationships();

        var result = service.add(point);

        assertThat(result.getCode()).isEqualTo(200);
        verify(dataPointMapper).insert(point);
        verify(configProvider).refreshAll();
    }

    @Test
    void locksExistingDataTypeBeforeValidatingUpdate() {
        BizDataPoint existing = validPoint();
        existing.setPointId(POINT_ID);
        existing.setDataType("DIGITAL");
        existing.setUnit(" ");
        when(dataPointMapper.selectById(POINT_ID)).thenReturn(existing);
        when(dataPointMapper.updateById(any(BizDataPoint.class))).thenReturn(1);
        stubValidRelationships();

        BizDataPoint update = new BizDataPoint();
        update.setPointId(POINT_ID);
        update.setDataType("ANALOG");
        update.setStatus("ONLINE");
        update.setIsForCalc(1);
        update.setUnit(" ");

        service.update(update);

        ArgumentCaptor<BizDataPoint> saved = ArgumentCaptor.forClass(BizDataPoint.class);
        verify(dataPointMapper).updateById(saved.capture());
        assertThat(saved.getValue().getDataType()).isEqualTo("DIGITAL");
        verify(configProvider).refreshAll();
    }

    private static Stream<Arguments> unitRuleExemptions() {
        return Stream.of(
                Arguments.of("非计算测点", "ONLINE", "ANALOG", 0),
                Arguments.of("离线测点", "OFFLINE", "ANALOG", 1),
                Arguments.of("非模拟量测点", "ONLINE", "DIGITAL", 1));
    }

    private BizDataPoint validPoint() {
        BizDataPoint point = new BizDataPoint();
        point.setPointCode(POINT_CODE);
        point.setPointName("室外环境温度");
        point.setBuildingId("BLD001");
        point.setNamingRuleId(RULE_ID);
        point.setFamilyCode("ENV");
        point.setComponentCode("ENV");
        point.setDataType("ANALOG");
        point.setUnit("℃");
        point.setIsForCalc(1);
        point.setStatus("ONLINE");
        return point;
    }

    private void stubValidRelationships() {
        BizPointNamingRule rule = new BizPointNamingRule();
        rule.setRuleId(RULE_ID);
        rule.setFamilyCode("ENV");
        rule.setComponentCode("ENV");
        rule.setStatus(1);
        when(namingRuleMapper.selectById(RULE_ID)).thenReturn(rule);
        when(namingValidator.matches(rule, POINT_CODE)).thenReturn(true);
    }

    private void assertUnitValidationFailure(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage())
                            .isEqualTo("参与计算的在线模拟量必须配置单位");
                });
    }
}
