package com.platform.energy.parameters;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 正式参数包目录、预检和导入的稳定 HTTP 契约。 */
public final class EnergyParameterPackageContracts {
    private EnergyParameterPackageContracts() {
    }

    public record SourceMetadataRequest(
            String sourceCode,
            String sourceName,
            String publisher,
            String documentReference,
            String sourceDocumentVersion,
            Integer publicationYear,
            LocalDate publishedOn,
            String applicabilityNote,
            String evidenceReference,
            String energySourceType) {
    }

    public record DependenciesRequest(
            String standardCoalLhvVersionId,
            String naturalGasFormulaVersionId,
            String rawCoalFormulaVersionId,
            String rawCoalEnergyItemCode) {
    }

    public record ElectricityContextRequest(
            String applicabilityLevel,
            String regionCode,
            Integer dataYear,
            Integer accountingYear) {
    }

    /**
     * 数值由目录中的已确认材料固定给出；请求只补充来源、适用期和既有已审核依赖，
     * 因而不能借导入接口覆盖任意参数。
     */
    public record PackageRequest(
            String packageCode,
            List<String> itemCodes,
            SourceMetadataRequest source,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo,
            String standardConditionCode,
            DependenciesRequest dependencies,
            ElectricityContextRequest electricity,
            String idempotencyKey) {
        public PackageRequest {
            itemCodes = itemCodes == null ? List.of() : List.copyOf(itemCodes);
        }
    }

    public record PackageDefinitionView(
            String packageCode,
            String packageName,
            List<PackageItemDefinitionView> items) {
        public PackageDefinitionView {
            items = List.copyOf(items);
        }
    }

    public record PackageItemDefinitionView(
            String itemCode,
            String itemName,
            String targetType,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceValue,
            String sourceUnit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal normalizedValue,
            String normalizedUnit,
            List<String> requiredInputs) {
        public PackageItemDefinitionView {
            requiredInputs = List.copyOf(requiredInputs);
        }
    }

    public record VersionContextView(
            String domain,
            String code,
            String versionId,
            String status,
            String usageNature,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
    }

    public record DenominatorContextView(
            String denominatorType,
            String versionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value,
            String unitCode,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    public record CatalogRequirementView(
            String catalogType,
            String requiredCode,
            String listEndpoint,
            String createEndpoint,
            List<VersionContextView> versions) {
        public CatalogRequirementView {
            versions = List.copyOf(versions);
        }
    }

    public record CatalogView(
            List<PackageDefinitionView> packages,
            List<VersionContextView> existingVersions,
            List<CatalogRequirementView> catalogRequirements,
            List<DenominatorContextView> denominatorContext) {
        public CatalogView {
            packages = List.copyOf(packages);
            existingVersions = List.copyOf(existingVersions);
            catalogRequirements = List.copyOf(catalogRequirements);
            denominatorContext = List.copyOf(denominatorContext);
        }
    }

    public record IssueView(String itemCode, String severity, String code, String message) {
    }

    public record DependencyView(String dependencyType, String versionId, String status, String message) {
    }

    public record ItemPreviewView(
            String itemCode,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal normalizedValue,
            String normalizedUnit,
            List<DependencyView> dependencies,
            List<IssueView> issues) {
        public ItemPreviewView {
            dependencies = List.copyOf(dependencies);
            issues = List.copyOf(issues);
        }
    }

    public record PreviewView(
            String packageCode,
            boolean readyForImport,
            List<ItemPreviewView> items,
            List<IssueView> issues) {
        public PreviewView {
            items = List.copyOf(items);
            issues = List.copyOf(issues);
        }
    }

    public record CreatedVersionView(
            String itemCode,
            String objectType,
            String versionId,
            String status,
            List<String> dependencyVersionIds) {
        public CreatedVersionView {
            dependencyVersionIds = List.copyOf(dependencyVersionIds);
        }
    }

    public record ImportView(
            String packageCode,
            String completionStatus,
            boolean awaitingIndependentReview,
            List<CreatedVersionView> created,
            List<IssueView> notices) {
        public ImportView {
            created = List.copyOf(created);
            notices = List.copyOf(notices);
        }
    }

    public record EnergyParameterPackageApiError(
            int code, String errorCode, String msg, boolean success, String traceId) {
    }
}
