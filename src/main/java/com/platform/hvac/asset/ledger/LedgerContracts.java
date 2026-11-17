package com.platform.hvac.asset.ledger;

import com.platform.hvac.asset.api.AssetManagementContracts.EquipmentListItemView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** 台账展示契约；专业、展示测点和状态能力不改变资产身份、计量归属或公式输入。 */
public final class LedgerContracts {
    private LedgerContracts() { }

    public record LedgerPage(int page, int size, long total, List<LedgerItem> items,
                             Map<String, Long> groupCounts, LedgerSummary summary, long generatedAt) { }
    public record LedgerSummary(long assets, long points, long online, long offline,
                                long communicationUnknown, long communicationError, long notMonitored, long delayed) { }
    public record LedgerItem(EquipmentListItemView equipment, String professionalGroup,
                             String classificationSource, long ruleRevision, long classificationRevision, LedgerState operation,
                             LedgerState communication, LedgerDisplayPoint displayPoint) { }
    public record LedgerState(boolean applicable, String value, String reason, String source,
                              Long observedAt, String scope) { }
    public record LedgerDisplayPoint(String status, String reason, String pointId, String pointCode,
                                     String pointName, String unit, Double value, Long eventTime,
                                     Long receivedTime, Integer dataQuality, String usageStatus) { }
    public record TypeRule(String typeCode, String professionalGroup, boolean operationApplicable,
                           String pointComponent, String pointSuffix, String evidence, long revision,
                           Long updatedBy) { }
    public record TypeRuleRequest(@NotBlank @Size(max = 20) String professionalGroup,
                                  @NotNull Boolean operationApplicable,
                                  @Size(max = 20) String pointComponent,
                                  @Size(max = 20) String pointSuffix,
                                  @NotBlank @Size(max = 500) String evidence,
                                  @NotNull @PositiveOrZero Long expectedRevision) { }
    public record ClassificationRequest(@Size(max = 20) String professionalGroup,
                                        @NotBlank @Size(max = 500) String evidence,
                                        @NotNull @PositiveOrZero Long expectedRevision) { }
    public record ClassificationView(String equipmentId, String professionalGroup, String evidence,
                                     long revision) { }
}
