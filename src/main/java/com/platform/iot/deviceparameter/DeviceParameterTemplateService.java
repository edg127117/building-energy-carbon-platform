package com.platform.iot.deviceparameter;

import com.platform.iot.deviceparameter.DeviceParameterCandidateService.CandidateCommand;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.ProductIdentity;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.TemplateHead;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.TemplateRevision;
import com.platform.iot.deviceparameter.DeviceParameterJdbcRepository.TemplateValue;
import com.platform.iot.deviceparameter.DeviceParameterModels.Applicability;
import com.platform.iot.deviceparameter.DeviceParameterModels.Candidate;
import com.platform.iot.deviceparameter.DeviceParameterModels.Definition;
import com.platform.iot.deviceparameter.DeviceParameterModels.EquipmentIdentity;
import com.platform.iot.deviceparameter.DeviceParameterModels.SourceType;
import com.platform.iot.deviceparameter.api.DeviceParameterContracts.TemplateRevisionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.platform.iot.deviceparameter.DeviceParameterSupport.id;
import static com.platform.iot.deviceparameter.DeviceParameterSupport.invalid;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
/** 产品参数模板修订只产生 TEMPLATE 候选，启用和应用都不会直接发布设备参数版本。 */
public class DeviceParameterTemplateService {
    private final DeviceParameterJdbcRepository repository;
    private final DeviceParameterAuthorization authorization;
    private final DeviceParameterCandidateService candidateService;

    @Transactional
    public TemplateRevision createDraft(
            long userId, Collection<String> roles, TemplateRevisionRequest request) {
        authorization.requireGlobalConfigurator(roles);
        ProductIdentity product = repository.findProduct(request.productId())
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "设备产品不存在"));
        if (!"ENABLED".equals(product.status())) {
            throw invalid("只有已启用产品可以创建参数模板修订");
        }
        TemplateHead head = repository.findTemplateHead(product.productId(), true).orElse(null);
        if (head == null) {
            if (request.expectedRevision() != -1) {
                throw invalid("新建产品参数模板的 expectedRevision 必须为 -1");
            }
            repository.insertTemplateHead(id(), product.productId(), userId);
            head = repository.findTemplateHead(product.productId(), true).orElseThrow();
        } else if (head.configRevision() != request.expectedRevision()) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "产品参数模板修订号已过期");
        }
        List<TemplateValue> values = normalizeValues(product, request);
        TemplateRevision revision = new TemplateRevision(id(), head.templateId(), product.productId(),
                repository.nextTemplateRevisionNo(head.templateId()), "DRAFT",
                request.changeReason().trim(), request.evidenceReference().trim(), userId);
        repository.insertTemplateRevision(revision, values);
        repository.audit(id(), null, "USER", userId, "CREATE_TEMPLATE_DRAFT",
                "TEMPLATE_REVISION", revision.revisionId(), null, null,
                "values=" + values.size(), "SUCCESS", null, null, null);
        return revision;
    }

    @Transactional
    public TemplateRevision publish(
            long userId, Collection<String> roles, String revisionId, int expectedRevision) {
        authorization.requireGlobalConfigurator(roles);
        TemplateRevision revision = repository.findTemplateRevision(revisionId)
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "产品参数模板修订不存在"));
        ProductIdentity product = repository.findProduct(revision.productId())
                .orElseThrow(() -> invalid("模板关联产品不存在"));
        if (!"ENABLED".equals(product.status())) {
            throw invalid("模板发布前产品必须保持启用");
        }
        for (TemplateValue value : repository.listTemplateValues(revisionId)) {
            Definition definition = repository.findDefinition(value.definitionId()).orElse(null);
            Applicability applicability = repository.findApplicability(
                    product.equipmentTypeCode(), value.definitionId()).orElse(null);
            if (definition == null || !"ENABLED".equals(definition.status())
                    || applicability == null || !"ENABLED".equals(applicability.status())) {
                throw invalid("模板发布前全部标准定义和适用关系必须保持启用");
            }
        }
        if (repository.publishTemplateRevision(revision.templateId(), revisionId,
                expectedRevision, userId) != 1) {
            throw DeviceParameterErrors.error(409, DeviceParameterErrors.VERSION_CONFLICT,
                    "模板状态或修订号已变化");
        }
        repository.audit(id(), null, "USER", userId, "PUBLISH_TEMPLATE",
                "TEMPLATE_REVISION", revisionId, null, null, revision.productId(),
                "SUCCESS", null, null, null);
        return repository.findTemplateRevision(revisionId).orElseThrow();
    }

    @Transactional
    public List<Candidate> apply(
            long userId, Collection<String> roles, String equipmentId) {
        authorization.requireMaintainer(roles);
        EquipmentIdentity equipment = candidateService.requireEquipment(equipmentId);
        authorization.checkBuilding(userId, roles, equipment.buildingId());
        if (equipment.productId() == null) {
            throw invalid("设备没有绑定产品，不能应用产品参数模板");
        }
        TemplateRevision revision = repository.findActiveTemplateRevision(equipment.productId())
                .orElseThrow(() -> DeviceParameterErrors.error(404,
                        DeviceParameterErrors.NOT_FOUND, "产品没有已启用参数模板"));
        List<Candidate> result = new ArrayList<>();
        for (TemplateValue value : repository.listTemplateValues(revision.revisionId())) {
            Definition definition = repository.findDefinition(value.definitionId()).orElseThrow();
            result.add(candidateService.ingest(new CandidateCommand(equipment, SourceType.TEMPLATE,
                    revision.revisionId(), Integer.toString(revision.revisionNo()),
                    definition.parameterCode(), value.rawValue().toPlainString(), value.rawUnit(),
                    null, definition.parameterCode(), null,
                    revision.revisionId() + '|' + value.definitionId(), userId)));
        }
        return List.copyOf(result);
    }

    private List<TemplateValue> normalizeValues(
            ProductIdentity product, TemplateRevisionRequest request) {
        Set<String> definitions = new HashSet<>();
        return request.values().stream().map(item -> {
            if (!definitions.add(item.definitionId())) {
                throw invalid("产品参数模板不能包含重复标准参数");
            }
            Definition definition = repository.findDefinition(item.definitionId())
                    .orElseThrow(() -> DeviceParameterErrors.error(404,
                            DeviceParameterErrors.DEFINITION_NOT_FOUND, "标准参数定义不存在"));
            Applicability applicability = repository.findApplicability(
                    product.equipmentTypeCode(), definition.definitionId()).orElse(null);
            if (!"ENABLED".equals(definition.status()) || applicability == null
                    || !"ENABLED".equals(applicability.status())) {
                throw invalid("模板只能引用产品设备类型已启用的标准适用关系");
            }
            String unit = item.unit().trim().toUpperCase(Locale.ROOT);
            if (!definition.standardUnit().equals(unit)) {
                throw DeviceParameterErrors.error(400,
                        DeviceParameterErrors.UNIT_INCOMPATIBLE,
                        "模板非标准单位必须先通过已确认映射归一化");
            }
            if (item.value().stripTrailingZeros().scale() > definition.storageScale()) {
                throw DeviceParameterErrors.error(400,
                        DeviceParameterErrors.PRECISION_INVALID, "模板值超过标准参数存储精度");
            }
            if (applicability.hardMin() != null
                    && item.value().compareTo(applicability.hardMin()) < 0
                    || applicability.hardMax() != null
                    && item.value().compareTo(applicability.hardMax()) > 0) {
                throw DeviceParameterErrors.error(400,
                        DeviceParameterErrors.VALUE_OUT_OF_RANGE, "模板值违反专业硬限制");
            }
            return new TemplateValue(definition.definitionId(), item.value(), unit,
                    item.value(), item.sourceReference().trim(), item.sortOrder());
        }).toList();
    }
}
