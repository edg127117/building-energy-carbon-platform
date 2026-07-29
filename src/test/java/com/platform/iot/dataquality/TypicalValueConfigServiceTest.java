package com.platform.iot.dataquality;

import com.platform.framework.exception.BusinessException;
import com.platform.hvac.mapper.BizDataPointMapper;
import com.platform.hvac.model.entity.BizDataPoint;
import com.platform.iot.dataquality.mapper.BizPointTypicalValueConfigMapper;
import com.platform.iot.dataquality.model.TypicalValueStatus;
import com.platform.iot.dataquality.model.entity.BizPointTypicalValueConfig;
import com.platform.security.FormalRole;
import com.platform.system.service.BuildingScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TypicalValueConfigServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long ADMIN_ID = 9L;
    private static final List<String> ENERGY_MANAGER = List.of(FormalRole.ENERGY_MANAGER.name());
    private static final List<String> PLATFORM_ADMIN = List.of(FormalRole.PLATFORM_ADMIN.name());
    private static final LocalDateTime VALID_FROM = LocalDateTime.of(2026, 7, 29, 10, 0);
    private static final LocalDateTime VALID_TO = LocalDateTime.of(2026, 7, 30, 10, 0);

    @Mock private BizPointTypicalValueConfigMapper configMapper;
    @Mock private BizDataPointMapper pointMapper;
    @Mock private BuildingScopeService buildingScopeService;
    @Mock private TypicalValueConfigProvider provider;

    private TypicalValueConfigService service;

    @BeforeEach
    void setUp() {
        service = new TypicalValueConfigService(configMapper, pointMapper, buildingScopeService, provider);
    }

    @Test
    void shouldLockParentPointBeforeAllocatingNextVersion() {
        BizDataPoint point = eligiblePoint();
        when(pointMapper.selectByIdForUpdate("P1")).thenReturn(point);
        when(configMapper.selectMaxVersion("P1")).thenReturn(3);

        BizPointTypicalValueConfig created = service.create(
                USER_ID, ENERGY_MANAGER, "P1", new BigDecimal("9.5"),
                "近三年稳定工况", "传感器缺失时即时出数", VALID_FROM, VALID_TO);

        InOrder order = inOrder(pointMapper, configMapper);
        order.verify(pointMapper).selectByIdForUpdate("P1");
        order.verify(configMapper).selectMaxVersion("P1");
        order.verify(configMapper).insert(any(BizPointTypicalValueConfig.class));
        verify(buildingScopeService).checkAccess(USER_ID, ENERGY_MANAGER, "B1");
        assertThat(created.getStatus()).isEqualTo(TypicalValueStatus.DRAFT);
        assertThat(created.getVersion()).isEqualTo(4);
        assertThat(created.getUnit()).isEqualTo("kW");
        assertThat(created.getCreatedBy()).isEqualTo(USER_ID);
    }

    @Test
    void shouldAllowPlatformAdminToMaintainTypicalValueConfig() {
        BizDataPoint point = eligiblePoint();
        when(pointMapper.selectByIdForUpdate("P1")).thenReturn(point);
        when(configMapper.selectMaxVersion("P1")).thenReturn(0);

        BizPointTypicalValueConfig created = service.create(
                ADMIN_ID, PLATFORM_ADMIN, "P1", new BigDecimal("9.5"),
                "平台管理员补录", "应急维护", VALID_FROM, VALID_TO);

        verify(buildingScopeService).checkAccess(ADMIN_ID, PLATFORM_ADMIN, "B1");
        assertThat(created.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(created.getStatus()).isEqualTo(TypicalValueStatus.DRAFT);
    }

    @Test
    void shouldRejectTypicalValueOutsidePointRange() {
        when(pointMapper.selectByIdForUpdate("P1")).thenReturn(eligiblePoint());

        assertThatThrownBy(() -> service.create(
                USER_ID, ENERGY_MANAGER, "P1", new BigDecimal("101"),
                "统计样本", "补全", VALID_FROM, VALID_TO))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(400);

        verify(configMapper, never()).insert(any());
    }

    @Test
    void shouldOnlyAllowDraftToBeUpdatedAndSubmitted() {
        BizPointTypicalValueConfig approved = config(TypicalValueStatus.APPROVED, USER_ID);
        when(configMapper.selectByIdForUpdate("C1")).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.update(
                USER_ID, ENERGY_MANAGER, "C1", BigDecimal.TEN,
                "新统计", "新原因", VALID_FROM, VALID_TO))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(409);
        assertThatThrownBy(() -> service.submit(USER_ID, ENERGY_MANAGER, "C1"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(409);
    }

    @Test
    void shouldApproveOnlyAfterLockingConfigThenParentPoint() {
        BizPointTypicalValueConfig pending = config(TypicalValueStatus.PENDING, USER_ID);
        when(configMapper.selectByIdForUpdate("C1")).thenReturn(Optional.of(pending));
        when(pointMapper.selectByIdForUpdate("P1")).thenReturn(eligiblePoint());
        when(configMapper.existsApprovedOverlap("P1", VALID_FROM, VALID_TO, "C1"))
                .thenReturn(false);

        service.approve(ADMIN_ID, PLATFORM_ADMIN, "C1", "证据完整");

        InOrder order = inOrder(configMapper, pointMapper);
        order.verify(configMapper).selectByIdForUpdate("C1");
        order.verify(pointMapper).selectByIdForUpdate("P1");
        order.verify(configMapper).existsApprovedOverlap("P1", VALID_FROM, VALID_TO, "C1");
        order.verify(configMapper).updateById(pending);
        assertThat(pending.getStatus()).isEqualTo(TypicalValueStatus.APPROVED);
        assertThat(pending.getReviewerId()).isEqualTo(ADMIN_ID);
    }

    @Test
    void shouldRejectSelfApprovalAndOverlappingApproval() {
        BizPointTypicalValueConfig self = config(TypicalValueStatus.PENDING, ADMIN_ID);
        when(configMapper.selectByIdForUpdate("C1")).thenReturn(Optional.of(self));

        assertThatThrownBy(() -> service.approve(ADMIN_ID, PLATFORM_ADMIN, "C1", "同意"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(409);

        BizPointTypicalValueConfig pending = config(TypicalValueStatus.PENDING, USER_ID);
        when(configMapper.selectByIdForUpdate("C1")).thenReturn(Optional.of(pending));
        when(pointMapper.selectByIdForUpdate("P1")).thenReturn(eligiblePoint());
        when(configMapper.existsApprovedOverlap("P1", VALID_FROM, VALID_TO, "C1"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, PLATFORM_ADMIN, "C1", "同意"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("典型值有效期与已批准配置重叠")
                .extracting("code").isEqualTo(409);
    }

    @Test
    void shouldLimitReviewAndDisableOperationsToPlatformAdmin() {
        BizPointTypicalValueConfig pending = config(TypicalValueStatus.PENDING, USER_ID);
        assertThatThrownBy(() -> service.reject(USER_ID, ENERGY_MANAGER, "C1", "不采用"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(403);
        assertThatThrownBy(() -> service.disable(USER_ID, ENERGY_MANAGER, "C1", "配置错误"))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(403);
    }

    @Test
    void shouldRequireReasonsAndKeepDisabledRecordForHistoricalEvidence() {
        BizPointTypicalValueConfig approved = config(TypicalValueStatus.APPROVED, USER_ID);
        when(configMapper.selectByIdForUpdate("C1")).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.disable(ADMIN_ID, PLATFORM_ADMIN, "C1", " "))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(400);

        service.disable(ADMIN_ID, PLATFORM_ADMIN, "C1", "配置已过期");

        assertThat(approved.getStatus()).isEqualTo(TypicalValueStatus.DISABLED);
        assertThat(approved.getDisabledReason()).isEqualTo("配置已过期");
        verify(configMapper).updateById(eq(approved));
        verify(configMapper, never()).deleteById("C1");
    }

    private static BizDataPoint eligiblePoint() {
        BizDataPoint point = new BizDataPoint();
        point.setPointId("P1");
        point.setBuildingId("B1");
        point.setStatus("ONLINE");
        point.setDataType("ANALOG");
        point.setIsForCalc(1);
        point.setUnit("kW");
        point.setValueMin(BigDecimal.ZERO);
        point.setValueMax(new BigDecimal("100"));
        point.setDefaultValue(new BigDecimal("88"));
        return point;
    }

    private static BizPointTypicalValueConfig config(TypicalValueStatus status, Long creator) {
        BizPointTypicalValueConfig config = new BizPointTypicalValueConfig();
        config.setConfigId("C1");
        config.setPointId("P1");
        config.setBuildingId("B1");
        config.setTypicalValue(BigDecimal.TEN);
        config.setUnit("kW");
        config.setSourceDescription("统计样本");
        config.setReason("传感器缺失");
        config.setValidFrom(VALID_FROM);
        config.setValidTo(VALID_TO);
        config.setStatus(status);
        config.setVersion(1);
        config.setCreatedBy(creator);
        return config;
    }
}
