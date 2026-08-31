package com.platform.relation.importing;

import com.platform.relation.RelationGovernanceRepository.BoundaryRow;
import com.platform.relation.RelationGovernanceRepository.NodeRow;
import com.platform.relation.api.RelationContracts.MeteringImportIssue;

import java.util.List;

/** Excel 解析、领域映射和写入之间使用的包内不可变数据。 */
final class MeteringImportModels {
    private MeteringImportModels() {
    }

    record ParsedWorkbook(String templateVersion, int totalRows, List<ParsedRow> rows,
                          List<MeteringImportIssue> issues) {}

    record ParsedRow(
            int rowNumber, String templateVersion, String boundaryCode,
            String boundaryName, String energyType, String meterPointCode,
            String meterRole, String parentMeterCode, String meterDirection,
            String targetType, String targetCode, String allocationStatus,
            String reasonCode, String reasonText, String evidenceReference,
            String description) {}

    record ValidatedRow(
            ParsedRow source, BoundaryRow existingBoundary,
            NodeRow meterPoint, NodeRow parentMeterPoint, NodeRow target,
            String structureConfirmation) {}

    record ValidationResult(
            ParsedWorkbook workbook, List<ValidatedRow> rows,
            List<MeteringImportIssue> issues) {
        boolean valid() {
            return issues.stream().noneMatch(issue -> "ERROR".equals(issue.level()));
        }
    }
}
