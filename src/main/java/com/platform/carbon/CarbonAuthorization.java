package com.platform.carbon;

import com.platform.audit.BackendDuty;
import com.platform.audit.BackendDutyService;
import com.platform.system.service.BuildingScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;

import static com.platform.carbon.CarbonErrors.FORBIDDEN;
import static com.platform.carbon.CarbonErrors.error;

@Component
@RequiredArgsConstructor
/** 在动态职责之外始终执行建筑范围和正式结果审批职责分离。 */
class CarbonAuthorization {
    private final BackendDutyService dutyService;
    private final BuildingScopeService buildingScopeService;

    void requireReader(long userId, Collection<String> roles, String buildingId) {
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    void requireRuleMaintainer(long userId, Collection<String> roles, String buildingId) {
        requireDuty(userId, BackendDuty.CARBON_RULE_MAINTAIN);
        if (buildingId != null) buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    void requireRuleReviewer(long userId, Collection<String> roles, String buildingId) {
        requireDuty(userId, BackendDuty.CARBON_RULE_REVIEW);
        if (buildingId != null) buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    void requireRuleActivator(long userId, Collection<String> roles, String buildingId) {
        requireDuty(userId, BackendDuty.CARBON_RULE_ACTIVATE);
        if (buildingId != null) buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    void requireCalculationRunner(long userId, Collection<String> roles, String buildingId) {
        requireDuty(userId, BackendDuty.CARBON_CALCULATION_RUN);
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    void requireRecalculationTrigger(long userId, Collection<String> roles, String buildingId) {
        requireDuty(userId, BackendDuty.CARBON_RECALCULATION_TRIGGER);
        buildingScopeService.checkAccess(userId, roles, buildingId);
    }

    void requireRecalculationApprover(long userId, Collection<String> roles,
                                      Collection<String> buildingIds) {
        requireDuty(userId, BackendDuty.CARBON_RECALCULATION_APPROVE);
        buildingIds.forEach(buildingId -> buildingScopeService.checkAccess(
                userId, roles, buildingId));
    }

    void requireSeparation(long responsibleUserId, long reviewerId) {
        dutyService.requireSeparation(responsibleUserId, reviewerId);
    }

    private void requireDuty(long userId, BackendDuty duty) {
        if (!dutyService.hasDuty(userId, duty)) {
            throw error(403, FORBIDDEN, "缺少碳管理动态职责");
        }
    }
}
