package com.platform.carbon;

import com.platform.carbon.CarbonModels.*;
import com.platform.carbon.api.CarbonContracts.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.platform.carbon.CarbonErrors.*;

@Service
@RequiredArgsConstructor
/**
 * 将已核对目录中的单项录入既有审核链；同目录项与使用性质重试只返回原版本。
 */
public class ElectricityFactorImportService {
    private final ElectricityFactorCatalog catalog;
    private final CarbonAuthorization authorization;
    private final CarbonRuleService rules;
    private final CarbonRuleRepository repository;

    public List<ElectricityFactorCatalog.Entry> catalog() {
        return catalog.entries();
    }

    @Transactional(rollbackFor = Exception.class)
    public FactorVersion importEntry(long userId, Collection<String> roles,
                                     String entryCode, String usageNature) {
        authorization.requireRuleMaintainer(userId, roles, null);
        UsageNature nature;
        try {
            nature = UsageNature.valueOf(usageNature);
        } catch (RuntimeException exception) {
            throw error(400, VALIDATION_FAILED, "必须明确选择研发参考或正式用途");
        }
        var entry = catalog.entry(entryCode)
                .orElseThrow(() -> error(404, NOT_FOUND, "电力平均因子目录项不存在"));
        entryCode = entry.entryCode();
        String imported = repository.importedElectricityFactor(entryCode, nature);
        if (imported != null) return rules.requireFactor(imported);

        // 来源身份锁串行化同年度录入。首次创建冲突由事务回滚，调用方重试不会留下半份组合。
        String sourceCode = entry.sourceCode() + (nature == UsageNature.FORMAL ? "_F" : "_D");
        String sourceId = repository.findSourceIdForUpdate(sourceCode);
        FactorSourceVersion source = sourceId == null ? null : repository.latestSource(sourceId);
        imported = repository.importedElectricityFactor(entryCode, nature);
        if (imported != null) return rules.requireFactor(imported);
        String note = "位置法电力平均CO2因子；数据年度=" + entry.dataYear()
                + "；核算年度=" + entry.accountingYear() + "；单位kgCO2/kWh";
        if (source == null) {
            source = rules.createSource(userId, roles, new CreateFactorSourceRequest(
                    sourceCode, entry.sourceName(), entry.publisher(), entry.documentReference(),
                    entry.publishedOn().getYear(), entry.publishedOn(), note,
                    entry.evidenceReference(), nature.name()));
        } else if (!source.sourceName().equals(entry.sourceName())
                || !source.publisher().equals(entry.publisher())
                || !source.documentReference().equals(entry.documentReference())
                || !Objects.equals(source.publishedOn(), entry.publishedOn())
                || !Objects.equals(source.publicationYear(), entry.publishedOn().getYear())
                || !source.evidenceReference().equals(entry.evidenceReference())
                || !source.applicabilityNote().equals(note) || source.usageNature() != nature) {
            throw error(409, VERSION_CONFLICT, "已有目录来源证据不一致，请核查来源身份");
        }
        LocalDateTime from = LocalDate.of(entry.accountingYear(), 1, 1).atStartOfDay();
        FactorVersion factor = rules.createFactor(userId, roles, new CreateFactorVersionRequest(
                entry.entryCode() + (nature == UsageNature.FORMAL ? "_F" : "_D"),
                "SCOPE_2", "ELECTRICITY", "PURCHASED_ELECTRICITY_LOCATION",
                "GAS_MASS", "CO2", "CO2_ONLY_ELECTRICITY", source.sourceVersionId(),
                entry.applicabilityLevel(), null, entry.regionCode(), "KWH", null, nature.name(),
                from, from.plusYears(1), "CFV_ELECTRICITY_CO2_V1", "CRP_DECIMAL128_V1",
                List.of(new FactorComponentRequest("DIRECT_EMISSION_FACTOR", entry.value(),
                        "KG_CO2/KWH", source.sourceVersionId(), entry.evidenceReference())),
                entry.dataYear(), entry.accountingYear()));
        try {
            repository.recordElectricityImport(entryCode, nature, factor.factorVersionId(),
                    userId, LocalDateTime.now());
        } catch (DuplicateKeyException exception) {
            throw error(409, VERSION_CONFLICT, "目录项已被并发录入，请重试读取原版本");
        }
        return factor;
    }
}
