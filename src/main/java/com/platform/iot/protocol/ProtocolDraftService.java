package com.platform.iot.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.parser.IntervalMappings;
import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.OnboardingAuditService;
import com.platform.iot.protocol.api.ProtocolContracts.*;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 管理尚未发布的配置；校验产品语义但不修改产品、设备身份或运行快照。 */
@Service
public class ProtocolDraftService {
    private final ProtocolDraftRepository repository;
    private final DeviceProductService products;
    private final OnboardingAuditService audit;
    private final ObjectMapper mapper;
    private final Validator validator;
    public ProtocolDraftService(ProtocolDraftRepository repository, DeviceProductService products,
                                OnboardingAuditService audit, ObjectMapper mapper, Validator validator) {
        this.repository=repository; this.products=products; this.audit=audit; this.mapper=mapper; this.validator=validator;
    }
    public static void requireAdmin(Set<String> roles) {
        if (roles==null || !roles.contains("PLATFORM_ADMIN"))
            throw new BusinessException(403,"PROTOCOL_FORBIDDEN","仅平台管理员可维护协议配置");
    }
    @Transactional(readOnly = true)
    public PageResponse<Detail> list(int page,int size,String keyword,String productId,Set<String> roles) {
        requireAdmin(roles);
        if(page<1 || size<1 || size>100) throw ProtocolErrors.invalid("页码必须大于零，每页最多100条");
        var result = repository.list(has(keyword) ? keyword.strip() : null, has(productId) ? productId : null,
                size, ((long) page - 1) * size);
        return new PageResponse<>(page, size, result.total(), result.rows().stream().map(this::view).toList());
    }
    public Detail detail(String id,Set<String> roles) {
        requireAdmin(roles);
        return view(requireDraft(id, false));
    }

    // 删除和冻结都必须持有这条草稿的行锁，直到版本或删除事务结束。
    Detail detailForUpdate(String id, Set<String> roles) {
        requireAdmin(roles);
        return view(requireDraft(id, true));
    }

    @Transactional
    public void delete(String id, long operator, Set<String> roles) {
        requireAdmin(roles);
        var row = requireDraft(id, true);
        if (repository.hasFrozenVersions(id)) throw ProtocolErrors.draftReferenced();
        repository.delete(id);
        audit.record(operator, null, "PROTOCOL_DRAFT_DELETE", "PROTOCOL_DRAFT", id,
                Map.of("revision", row.revision()), Map.of());
    }

    private ProtocolDraftRepository.Row requireDraft(String id, boolean lock) {
        return repository.find(id, lock).orElseThrow(ProtocolErrors::notFound);
    }

    @Transactional
    public Detail create(Configuration config,Long operator,Set<String> roles) {
        requireAdmin(roles);
        validateShape(config);
        validate(config, roles, false);
        var row=new ProtocolDraftRepository.Row(UUID.randomUUID().toString().replace("-",""),1,encode(config),System.currentTimeMillis());
        repository.insert(row,operator);
        audit.record(operator,null,"PROTOCOL_DRAFT_CREATE","PROTOCOL_DRAFT",row.id(),Map.of(),Map.of("revision",1));
        return view(row);
    }
    @Transactional
    public Detail update(String id,UpdateRequest request,Long operator,Set<String> roles) {
        requireAdmin(roles);
        var existing = requireDraft(id, false);
        // 旧客户端不会回传新增运行元数据；未传字段沿用已保存值，避免普通编辑静默降级运行契约。
        Configuration config=request.configuration();
        validateShape(config);
        var previous=view(existing).configuration();
        config=new Configuration(config.name(),config.productId(),config.profileCode(),config.sourceTopic(),config.identityType(),config.identityPath(),
                config.discriminatorPath(),config.discriminatorValue(),config.timestampPath(),
                preserve(config.seqPath(),previous.seqPath()),preserve(config.messageIdPath(),previous.messageIdPath()),
                preserve(config.bootIdPath(),previous.bootIdPath()),preserve(config.batchIdPath(),previous.batchIdPath()),
                preserve(config.retransmittedAtPath(),previous.retransmittedAtPath()),preserve(config.maxAckMode(),previous.maxAckMode()),
                preserve(config.correlationPolicy(),previous.correlationPolicy()),config.mappings().stream().map(m->{
                    if(m.valueType()!=null)return m;
                    var prior=previous.mappings().stream().filter(p->p.metricCode().equals(m.metricCode())&&Objects.equals(p.sourcePath(),m.sourcePath())).findFirst().orElse(null);
                    return prior==null?m:new Mapping(m.sourcePath(),m.metricCode(),m.sourceUnit(),m.targetUnit(),m.scale(),m.offset(),m.required(),m.enabled(),m.sortOrder(),prior.valueType());
                }).toList());
        validate(config, roles, false);
        if(existing.revision()!=request.revision() || existing.revision()==Long.MAX_VALUE) throw ProtocolErrors.conflict();
        var next=new ProtocolDraftRepository.Row(id,existing.revision()+1,encode(config),System.currentTimeMillis());
        if(repository.update(next,request.revision(),operator)!=1) throw ProtocolErrors.conflict();
        audit.record(operator,null,"PROTOCOL_DRAFT_UPDATE","PROTOCOL_DRAFT",id,
                Map.of("revision",existing.revision()),Map.of("revision",next.revision()));
        return view(next);
    }
    public void validate(Configuration configuration, Set<String> roles) {
        requireAdmin(roles);
        validate(configuration, roles, true);
    }

    void validatePreview(Configuration configuration, Set<String> roles) {
        requireAdmin(roles);
        validateShape(configuration);
        validate(configuration, roles, true);
    }

    private void validateShape(Configuration configuration) {
        validator.validate(configuration).stream()
                .min(Comparator.comparing(violation -> violation.getPropertyPath().toString()))
                .ifPresent(violation -> {
                    String path = configurationPath(violation.getPropertyPath(), configuration);
                    String message = switch (violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()) {
                        case "Digits" -> "系数或偏移量最多支持 11 位整数和 9 位小数";
                        case "NotBlank", "NotNull" -> "请填写该配置项";
                        case "Size" -> "字数或条数超过配置上限";
                        default -> "配置项格式无效";
                    };
                    throw ProtocolErrors.invalid(path, message);
                });
    }

    private static String configurationPath(Path path, Configuration configuration) {
        if (!path.toString().startsWith("mappings")) return path.toString();
        for (Path.Node node : path) {
            Integer index = node.getIndex();
            if (index == null) continue;
            var mapping = configuration.mappings().get(index);
            if (mapping == null || !has(mapping.metricCode())) return "mappings";
            String row = "mappings." + mapping.metricCode();
            return node.getName().startsWith("<") ? row : row + "." + node.getName();
        }
        return "mappings";
    }

    private void validate(Configuration c, Set<String> roles, boolean complete) {
        if (complete || c.sourceTopic() != null && !c.sourceTopic().isEmpty()) {
            String topic = c.sourceTopic();
            if (!has(topic) || topic.contains("+") || topic.contains("#") || !topic.equals(topic.strip())
                    || topic.chars().anyMatch(Character::isISOControl)) {
                throw ProtocolErrors.invalid("sourceTopic", "请填写无通配符的精确 Topic");
            }
        }
        if (complete || has(c.identityPath())) pointer("identityPath", c.identityPath());
        optionalPointer("timestampPath", c.timestampPath());
        optionalPointer("seqPath", c.seqPath());
        optionalPointer("messageIdPath", c.messageIdPath());
        optionalPointer("bootIdPath", c.bootIdPath());
        optionalPointer("batchIdPath", c.batchIdPath());
        optionalPointer("retransmittedAtPath", c.retransmittedAtPath());
        if (has(c.discriminatorPath()) != has(c.discriminatorValue())) {
            throw ProtocolErrors.invalid(has(c.discriminatorPath()) ? "discriminatorValue" : "discriminatorPath",
                    "判别路径与取值必须同时填写");
        }
        optionalPointer("discriminatorPath", c.discriminatorPath());

        var product = products.detail(c.productId(), roles);
        if (!product.expectedProfileCode().equals(c.profileCode())) {
            throw ProtocolErrors.invalid("profileCode", "协议编码必须与关联产品一致");
        }
        if (!product.identityType().equals(c.identityType())) {
            throw ProtocolErrors.invalid("identityType", "身份类型必须与关联产品一致");
        }
        var points = new LinkedHashMap<String,com.platform.iot.onboarding.api.DeviceProductContracts.PointTemplateView>();
        product.points().stream().filter(p -> p.enabled()).forEach(p -> points.put(p.metricCode(), p));
        Set<String> paths = new HashSet<>(), codes = new HashSet<>(), active = new HashSet<>();
        for (var mapping : c.mappings()) {
            String row = "mappings." + mapping.metricCode();
            boolean enabled = Boolean.TRUE.equals(mapping.enabled());
            validateMapping(mapping, row, complete && enabled, paths, codes);
            if (!IntervalMappings.parameter(mapping.valueType())) {
                var point = points.get(mapping.metricCode());
                if (point == null) throw ProtocolErrors.invalid(row, "映射指标必须对应产品启用测点");
                if (has(mapping.targetUnit()) && !point.unit().equals(mapping.targetUnit())) {
                    throw ProtocolErrors.invalid(row + ".targetUnit", "目标单位必须与产品启用测点一致");
                }
                // 周期量可拆分报文，由任务配对判断必需输入，普通遥测沿用整包必需规则。
                if (complete && enabled && point.required() && !mapping.required() && "DECIMAL".equals(mapping.effectiveValueType())) {
                    throw ProtocolErrors.invalid(row, "产品必需测点不可配置为可选");
                }
            }
            if (enabled) active.add(mapping.metricCode());
        }
        if (!complete) return;
        if (active.isEmpty()) throw ProtocolErrors.invalid("mappings", "至少启用一个测点映射");
        for (var point : points.values()) {
            if (point.required() && !active.contains(point.metricCode())) {
                throw ProtocolErrors.invalid("mappings." + point.metricCode(), "请映射产品的全部必需测点");
            }
        }
        try {
            IntervalMappings.validate(c.mappings().stream().filter(Mapping::enabled).map(m -> new ProtocolFieldMapping(
                    m.metricCode(), "DRAFT", m.sourcePath(), m.metricCode(), m.effectiveValueType(), m.sourceUnit(),
                    m.targetUnit(), m.scale(), m.offset(), m.required(), true, m.sortOrder())).toList());
        } catch (IllegalArgumentException ex) {
            throw ProtocolErrors.invalid("mappings", ex.getMessage());
        }
    }

    private static void validateMapping(Mapping mapping, String row, boolean complete, Set<String> paths, Set<String> codes) {
        if (!codes.add(mapping.metricCode())) throw ProtocolErrors.invalid(row, "指标编码重复");
        if (complete || has(mapping.sourcePath())) {
            pointer(row + ".sourcePath", mapping.sourcePath());
            if (!paths.add(mapping.sourcePath())) throw ProtocolErrors.invalid(row + ".sourcePath", "字段路径重复");
        }
        if (!complete) return;
        if (!has(mapping.sourceUnit())) throw ProtocolErrors.invalid(row + ".sourceUnit", "请确认原始单位");
        if (!has(mapping.targetUnit())) throw ProtocolErrors.invalid(row + ".targetUnit", "请填写目标单位");
        if (mapping.scale() == null) throw ProtocolErrors.invalid(row + ".scale", "请填写换算系数");
        if (mapping.offset() == null) throw ProtocolErrors.invalid(row + ".offset", "请填写换算偏移量");
    }

    static boolean has(String value) { return value!=null && !value.isBlank(); }

    private static void optionalPointer(String field, String value) {
        if (has(value)) pointer(field, value);
    }

    private static void pointer(String field, String value) {
        if (value==null || !value.startsWith("/") || value.chars().anyMatch(Character::isISOControl)
                || value.matches(".*~(?![01]).*")) {
            throw ProtocolErrors.invalid(field, "请填写正确转义的 JSON Pointer 路径");
        }
    }
    private static String preserve(String current,String previous) { return current==null?previous:current; }
    private String encode(Configuration config) {
        try {return mapper.writeValueAsString(config);} catch(JsonProcessingException e) {throw ProtocolErrors.invalid("配置无法序列化");}
    }
    private Detail view(ProtocolDraftRepository.Row row) {
        try {return new Detail(row.id(),row.revision(),"DRAFT",mapper.readValue(row.json(),Configuration.class),row.updatedAt());}
        catch(JsonProcessingException e) {throw new BusinessException(500,"PROTOCOL_STORAGE_INVALID","配置存储内容无法读取");}
    }
}
