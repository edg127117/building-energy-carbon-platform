package com.platform.alarm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.iot.temporal.HvacRawEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.platform.alarm.AlarmModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AlarmExtendedEvaluationTest {
    @Test
    void evaluatesNestedConditionsWithThreeValuedLogic() {
        ConditionNode all = new ConditionNode("ALL", List.of(
                leaf("p1", "GT", "10"),
                new ConditionNode("ANY", List.of(leaf("p2", "LT", "5"), leaf("p3", "EQ", "1")), null)), null);

        assertThat(AlarmRuleEvaluator.condition(all, Map.of("p1", bd("11"), "p2", bd("4")))).isTrue();
        assertThat(AlarmRuleEvaluator.condition(all, Map.of("p1", bd("9")))).isFalse();
        assertThat(AlarmRuleEvaluator.condition(all, Map.of("p1", bd("11")))).isNull();
        assertThat(AlarmRuleEvaluator.condition(new ConditionNode("ALL", List.of(leaf("p", "GT", "0")), null),
                Map.of("p", bd("1")))).isTrue();
    }

    @Test
    void qualityFreshnessUsesAppliedTimingPerPointAndKeepsLegacyDefault() {
        Timing fallback = new Timing(900, 30, 10, null);
        assertThat(CollectedAlarmDeviceSource.freshnessLimitMillis(300, 30, fallback, 120_000L)).isEqualTo(330_000L);
        assertThat(CollectedAlarmDeviceSource.freshnessLimitMillis(null, null, fallback, 120_000L)).isEqualTo(930_000L);
        assertThat(CollectedAlarmDeviceSource.freshnessLimitMillis(null, null, null, 120_000L)).isEqualTo(120_000L);
    }

    @Test
    void jumpUsesAbsoluteAndPercentDifferencesAndRejectsZeroBaseline() {
        long now = 1_800_000_120_000L;
        List<com.platform.iot.temporal.model.RawTelemetryEvent> absolute = List.of(
                raw("p", 100, now - 120_000), raw("p", 101, now - 60_000), raw("p", 110, now));
        assertThat(AlarmRuleEvaluator.jump(absolute, now, 120_000, 60_000, 5_000, 70_000, bd("5"), "ABS")).isTrue();
        assertThat(AlarmRuleEvaluator.jump(absolute, now, 120_000, 60_000, 5_000, 70_000, bd("20"), "PERCENT")).isFalse();
        var zero = List.of(raw("p", 0, now - 120_000), raw("p", 1, now));
        assertThat(AlarmRuleEvaluator.jump(zero, now, 120_000, 60_000, 5_000, 70_000, bd("10"), "PERCENT")).isNull();
    }

    @Test
    void stuckNeedsContinuousRunningAndCompleteFreshHistory() {
        long now = 1_800_000_120_000L;
        List<com.platform.iot.temporal.model.RawTelemetryEvent> values = List.of(
                raw("value", 20, now - 120_000), raw("value", 20.1, now - 60_000), raw("value", 20, now));
        List<com.platform.iot.temporal.model.RawTelemetryEvent> running = List.of(
                raw("run", 1, now - 120_000), raw("run", 1, now - 60_000), raw("run", 1, now));
        assertThat(AlarmRuleEvaluator.stuck(values, running, now, 120_000, 60_000, 5_000, 70_000,
                bd("0.5"), "ABS", bd("1"))).isTrue();
        assertThat(AlarmRuleEvaluator.stuck(values, List.of(raw("run", 0, now)), now, 120_000, 60_000, 5_000,
                70_000, bd("0.5"), "ABS", bd("1"))).isNull();
        List<com.platform.iot.temporal.model.RawTelemetryEvent> highOffState = List.of(
                raw("run", 2, now - 120_000), raw("run", 2, now - 60_000), raw("run", 2, now));
        assertThat(AlarmRuleEvaluator.stuck(values, highOffState, now, 120_000, 60_000, 5_000, 70_000,
                bd("0.5"), "ABS", bd("1"))).isFalse();
        var gapped = List.of(raw("value", 20, now - 120_000), raw("value", 20, now));
        assertThat(AlarmRuleEvaluator.jump(gapped, now, 120_000, 60_000, 5_000, 70_000, bd("1"), "ABS")).isNull();
        var badMiddle = List.of(raw("value", 20, now - 120_000), raw("value", 20, now - 60_000, 1, false),
                raw("value", 20, now));
        assertThat(AlarmRuleEvaluator.jump(badMiddle, now, 120_000, 60_000, 5_000, 70_000, bd("1"), "ABS")).isNull();
        var lateMiddle = List.of(raw("value", 20, now - 120_000), raw("value", 20, now - 60_000, 0, true),
                raw("value", 20, now));
        assertThat(AlarmRuleEvaluator.jump(lateMiddle, now, 120_000, 60_000, 5_000, 70_000, bd("1"), "ABS")).isNull();
        var duplicate = List.of(raw("value", 20, now - 120_000), raw("value", 20, now - 60_000),
                raw("value", 20, now - 60_000), raw("value", 20, now));
        assertThat(AlarmRuleEvaluator.jump(duplicate, now, 120_000, 60_000, 5_000, 70_000, bd("1"), "ABS")).isNull();
        var future = List.of(raw("value", 20, now - 120_000), raw("value", 20, now), raw("value", 25, now + 1));
        assertThat(AlarmRuleEvaluator.jump(future, now, 120_000, 60_000, 5_000, 70_000, bd("1"), "ABS")).isNull();
    }

    @Test
    void validatorRejectsTreesDeeperThanThreeLevelsBeforeAssetReads() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AlarmDefinitionValidator validator = new AlarmDefinitionValidator(jdbc);
        ConditionNode leaf = new ConditionNode(null, List.of(), new Condition("p", "GT", bd("0"), null));
        ConditionNode level3 = new ConditionNode("ALL", List.of(leaf, leaf), null);
        ConditionNode level2 = new ConditionNode("ALL", List.of(level3, leaf), null);
        ConditionNode tooDeep = new ConditionNode("ALL", List.of(level2, leaf), null);
        RuleRequest request = request(new Advanced("e", tooDeep, null, null, null, null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate("b", request))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("最多三层");
    }

    @Test
    void validatorAcceptsTimingOnlyQualityRulesAndRequiresMultiPointAlignment() {
        AlarmDefinitionValidator validator = new AlarmDefinitionValidator(mock(JdbcTemplate.class));
        RuleRequest onePoint = new RuleRequest("QUALITY", "质量", "IMPORTANT", "Q1", null, null, true, 1,
                null, 1, 0, 0, "ALL", "QUALITY", List.of(new Condition("p1", "GT", bd("0"), null)),
                new Advanced(null, null, null, new Timing(300, 30, null, null), null, null));
        validator.validate("building", onePoint);

        RuleRequest multiplePoints = new RuleRequest("QUALITY", "质量", "IMPORTANT", "Q1", null, null, true, 1,
                null, 1, 0, 0, "ALL", "QUALITY", List.of(new Condition("p1", "GT", bd("0"), null),
                        new Condition("p2", "GT", bd("0"), null)),
                new Advanced(null, null, null, new Timing(300, 30, null, null), null, null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate("building", multiplePoints))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("对齐容差");
    }

    @Test
    void combinesFaultFactsFromTwoIndependentSourcesAndKeepsBuildingScope() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            org.mockito.Mockito.when(rs.getString(1)).thenReturn("机组一");
            org.mockito.Mockito.when(rs.getString(2)).thenReturn("space-a");
            org.mockito.Mockito.when(rs.getString(3)).thenReturn("CHILLER");
            org.mockito.Mockito.when(rs.getString(4)).thenReturn("一层");
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        AlarmFaultSource first = building -> List.of(new AlarmFaultSource.FaultReading("identity-1", building,
                "equipment-1", "vendor-fault-a", true, 100, "来源A故障"));
        AlarmFaultSource second = building -> List.of(new AlarmFaultSource.FaultReading("identity-2", building,
                "equipment-2", "vendor-fault-b", true, 101, "来源B故障"),
                new AlarmFaultSource.FaultReading("identity-other", "other-building", "other-equipment",
                        "ignored", true, 102, "越界记录"));
        AlarmFaultSource failed = building -> { throw new IllegalStateException("源暂不可用"); };
        CollectedAlarmDeviceSource source = new CollectedAlarmDeviceSource(jdbc, null, mock(HvacRawEventRepository.class),
                null, new ObjectMapper(), List.of(first, second, failed));
        Rule fault = new Rule("fault-rule", "FAULT", "设备故障", "IMPORTANT", "Q1", null, null,
                true, 1, 1, null, null, 1, 0, 0, "ALL", "FAULT", List.of());

        DeviceResult result = source.load("building-a", List.of(fault), 200);

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).extracting(Candidate::description)
                .containsExactlyInAnyOrder("来源A故障", "来源B故障");
        assertThat(result.unavailableRuleIds()).containsExactly("fault-rule");
        assertThat(result.unavailableReason()).contains("故障来源读取失败");
    }

    @Test
    void daikinAdapterPreservesThePersistedActiveExceptionReadPath() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AtomicReference<String> sqlSeen = new AtomicReference<>();
        doAnswer(invocation -> {
            sqlSeen.set(invocation.getArgument(0));
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            org.mockito.Mockito.when(rs.getString(1)).thenReturn("identity");
            org.mockito.Mockito.when(rs.getString(2)).thenReturn("building-a");
            org.mockito.Mockito.when(rs.getString(3)).thenReturn("equipment");
            org.mockito.Mockito.when(rs.getString(4)).thenReturn("VENDOR_EQUIPMENT");
            org.mockito.Mockito.when(rs.getString(5)).thenReturn("active-key");
            org.mockito.Mockito.when(rs.getLong(6)).thenReturn(123L);
            org.mockito.Mockito.when(rs.getString(7)).thenReturn("compressor");
            return List.of(mapper.mapRow(rs, 0));
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        var rows = new DaikinAlarmFaultSource(jdbc).load("building-a");

        assertThat(rows).containsExactly(new AlarmFaultSource.FaultReading("identity", "building-a", "equipment",
                "VENDOR_EQUIPMENT", true, 123, "compressor"));
        assertThat(sqlSeen.get()).contains("biz_daikin_exception_instance", "biz_daikin_monitoring_target", "active_key IS NOT NULL");
    }

    private static RuleRequest request(Advanced advanced) {
        return new RuleRequest("CONDITION", "条件", "IMPORTANT", "Q0", null, null, true, 1,
                null, 1, 0, 0, "ALL", "VALUE", List.of(), advanced);
    }

    private static ConditionNode leaf(String point, String operator, String value) {
        return new ConditionNode(null, List.of(), new Condition(point, operator, bd(value), null));
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }

    private static com.platform.iot.temporal.model.RawTelemetryEvent raw(String point, double value, long at) {
        return raw(point, value, at, 0, false);
    }

    private static com.platform.iot.temporal.model.RawTelemetryEvent raw(String point, double value, long at, int quality, boolean late) {
        return new com.platform.iot.temporal.model.RawTelemetryEvent(point, point, "test", point, "device", "building",
                "group", "equipment", "equipment", "family", "component", "suffix", value, at, at,
                quality, 1, late);
    }
}
