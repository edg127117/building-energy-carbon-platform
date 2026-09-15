package com.platform.iot.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.iot.onboarding.DeviceProductService;
import com.platform.iot.onboarding.OnboardingAuditService;
import com.platform.iot.protocol.api.ProtocolContracts.*;
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
    public ProtocolDraftService(ProtocolDraftRepository repository, DeviceProductService products,
                                OnboardingAuditService audit, ObjectMapper mapper) {
        this.repository=repository; this.products=products; this.audit=audit; this.mapper=mapper;
    }
    public static void requireAdmin(Set<String> roles) {
        if (roles==null || !roles.contains("PLATFORM_ADMIN"))
            throw new BusinessException(403,"PROTOCOL_FORBIDDEN","仅平台管理员可维护协议配置");
    }
    public PageResponse<Detail> list(int page,int size,Set<String> roles) {
        requireAdmin(roles);
        if(page<1 || size<1 || size>100) throw ProtocolErrors.invalid("页码必须大于零，每页最多100条");
        return new PageResponse<>(page,size,repository.count(),repository.list(size,((long)page-1)*size).stream().map(this::view).toList());
    }
    public Detail detail(String id,Set<String> roles) {
        requireAdmin(roles);
        return view(repository.find(id).orElseThrow(ProtocolErrors::notFound));
    }
    @Transactional
    public Detail create(Configuration config,Long operator,Set<String> roles) {
        validate(config,roles);
        var row=new ProtocolDraftRepository.Row(UUID.randomUUID().toString().replace("-",""),1,encode(config),System.currentTimeMillis());
        repository.insert(row,operator);
        audit.record(operator,null,"PROTOCOL_DRAFT_CREATE","PROTOCOL_DRAFT",row.id(),Map.of(),Map.of("revision",1));
        return view(row);
    }
    @Transactional
    public Detail update(String id,UpdateRequest request,Long operator,Set<String> roles) {
        validate(request.configuration(),roles);
        var existing=repository.find(id).orElseThrow(ProtocolErrors::notFound);
        if(existing.revision()!=request.revision() || existing.revision()==Long.MAX_VALUE) throw ProtocolErrors.conflict();
        var next=new ProtocolDraftRepository.Row(id,existing.revision()+1,encode(request.configuration()),System.currentTimeMillis());
        if(repository.update(next,request.revision(),operator)!=1) throw ProtocolErrors.conflict();
        audit.record(operator,null,"PROTOCOL_DRAFT_UPDATE","PROTOCOL_DRAFT",id,
                Map.of("revision",existing.revision()),Map.of("revision",next.revision()));
        return view(next);
    }
    public void validate(Configuration c,Set<String> roles) {
        requireAdmin(roles);
        if(c.sourceTopic().contains("+") || c.sourceTopic().contains("#") || c.sourceTopic().chars().anyMatch(Character::isISOControl)
                || !c.sourceTopic().equals(c.sourceTopic().trim())) throw ProtocolErrors.invalid("Topic 必须是无通配符的精确主题");
        pointer(c.identityPath());
        if(has(c.timestampPath())) pointer(c.timestampPath());
        if(has(c.discriminatorPath())!=has(c.discriminatorValue())) throw ProtocolErrors.invalid("判别路径与取值必须同时填写");
        if(has(c.discriminatorPath())) pointer(c.discriminatorPath());
        var product=products.detail(c.productId(),roles);
        if(!product.expectedProfileCode().equals(c.profileCode()) || !product.identityType().equals(c.identityType()))
            throw ProtocolErrors.invalid("协议编码和身份类型必须与关联产品一致");
        Set<String> paths=new HashSet<>(), codes=new HashSet<>(), active=new HashSet<>();
        var points=new HashMap<String,com.platform.iot.onboarding.api.DeviceProductContracts.PointTemplateView>();
        product.points().stream().filter(p->p.enabled()).forEach(p->points.put(p.metricCode(),p));
        for(var m:c.mappings()) {
            pointer(m.sourcePath());
            if(!paths.add(m.sourcePath()) || !codes.add(m.metricCode())) throw ProtocolErrors.invalid("字段路径或指标编码重复");
            var point=points.get(m.metricCode());
            if(point==null || !point.unit().equals(m.targetUnit())) throw ProtocolErrors.invalid("映射指标及目标单位必须与产品启用测点一致");
            if(m.enabled()) {
                active.add(m.metricCode());
                if(point.required() && !m.required()) throw ProtocolErrors.invalid("产品必需测点不可配置为可选");
            }
        }
        if(active.isEmpty()) throw ProtocolErrors.invalid("至少启用一个测点映射");
        if(points.values().stream().anyMatch(p->p.required() && !active.contains(p.metricCode())))
            throw ProtocolErrors.invalid("请映射产品的全部必需测点");
    }
    static boolean has(String value) { return value!=null && !value.isBlank(); }
    static void pointer(String value) {
        if(value==null || !value.startsWith("/") || value.chars().anyMatch(Character::isISOControl)
                || value.matches(".*~(?![01]).*")) throw ProtocolErrors.invalid("字段路径必须是正确转义的 JSON Pointer");
    }
    private String encode(Configuration config) {
        try {return mapper.writeValueAsString(config);} catch(JsonProcessingException e) {throw ProtocolErrors.invalid("配置无法序列化");}
    }
    private Detail view(ProtocolDraftRepository.Row row) {
        try {return new Detail(row.id(),row.revision(),"DRAFT",mapper.readValue(row.json(),Configuration.class),row.updatedAt());}
        catch(JsonProcessingException e) {throw new BusinessException(500,"PROTOCOL_STORAGE_INVALID","配置存储内容无法读取");}
    }
}
