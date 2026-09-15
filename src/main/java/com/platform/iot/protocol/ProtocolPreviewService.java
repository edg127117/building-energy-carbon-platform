package com.platform.iot.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.platform.adapter.parser.JsonTelemetryParser;
import com.platform.adapter.parser.TelemetryAdaptationException;
import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;
import com.platform.iot.protocol.api.ProtocolContracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 有界、无发布的解析预览；业务结果由运行适配器同一解析核心产生。 */
@Service
public class ProtocolPreviewService {
    private final ProtocolDraftService drafts;
    private final int maxBytes;
    private final int maxFields;
    private final ObjectMapper mapper;
    private final JsonTelemetryParser parser;
    public ProtocolPreviewService(ProtocolDraftService drafts,
            @Value("${protocol-preview.max-bytes:65536}") int maxBytes,
            @Value("${protocol-preview.max-depth:20}") int maxDepth,
            @Value("${protocol-preview.max-fields:1024}") int maxFields) {
        if(maxBytes<1 || maxDepth<1 || maxFields<1) throw new IllegalArgumentException("协议预览限制必须为正数");
        this.drafts=drafts; this.maxBytes=maxBytes; this.maxFields=maxFields;
        var factory=JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(maxDepth).maxNumberLength(100).maxStringLength(maxBytes).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.mapper=new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.parser=new JsonTelemetryParser(mapper);
    }
    public Inspection inspect(String payload,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);
        var fields=new ArrayList<Field>();
        collect(read(payload),"",fields);
        return new Inspection(List.copyOf(fields));
    }
    public Preview preview(PreviewRequest request,Set<String> roles) {
        drafts.validate(request.configuration(),roles);
        JsonNode root=read(request.samplePayload());
        collect(root,"",new ArrayList<>());
        var c=request.configuration();
        if(ProtocolDraftService.has(c.discriminatorPath())) {
            var discriminator=root.at(c.discriminatorPath());
            if(!discriminator.isValueNode() || !c.discriminatorValue().equals(discriminator.asText()))
                return failure("PROFILE_NOT_MATCHED",c.discriminatorPath(),"样例未命中当前协议的判别条件");
        }
        var profile=new ProtocolProfile("PREVIEW",c.profileCode(),1,c.sourceTopic(),c.identityType(),c.identityPath(),
                c.discriminatorPath(),c.discriminatorValue(),c.timestampPath(),null,null,null,null,null,"EVIDENCE_ONLY","NONE",true);
        var mappings=c.mappings().stream().map(m->new ProtocolFieldMapping("PREVIEW", "PREVIEW",m.sourcePath(),m.metricCode(),
                "DECIMAL",m.sourceUnit(),m.targetUnit(),m.scale(),m.offset(),m.required(),m.enabled(),m.sortOrder())).toList();
        try {
            var parsed=parser.adapt(c.sourceTopic(),request.samplePayload().getBytes(StandardCharsets.UTF_8),request.receivedTime(),profile,mappings);
            var values=new HashMap<String,String>();
            parsed.metrics().forEach(m->values.put(m.code(),m.value().toPlainString()));
            var result=c.mappings().stream().filter(Mapping::enabled).sorted(Comparator.comparingInt(Mapping::sortOrder))
                    .map(m->{var raw=root.at(m.sourcePath()); var value=values.get(m.metricCode());
                        return new PreviewMetric(m.metricCode(),m.sourcePath(),raw.isNumber()?raw.decimalValue().toPlainString():null,
                                value,m.targetUnit(),value==null?"MISSING_OPTIONAL":"PRESENT");}).toList();
            return new Preview(true,List.of(),parsed.deviceIdentity().type(),parsed.deviceIdentity().value(),
                    parsed.timeSource().name(),parsed.collectedAt()==null?parsed.adapterReceivedAt():parsed.collectedAt(),result);
        } catch(TelemetryAdaptationException e) {
            String path="";
            if(e.code().equals("DEVICE_IDENTITY_MISSING")) path=c.identityPath();
            else if(e.code().contains("TIMESTAMP")) path=c.timestampPath();
            else if(e.getMessage().contains(": ")) path=e.getMessage().substring(e.getMessage().indexOf(": ")+2);
            return failure(e.code(),path,e.getMessage());
        }
    }
    private Preview failure(String code,String path,String message) {
        return new Preview(false,List.of(new PreviewError(code,path,message)),null,null,null,null,List.of());
    }
    private JsonNode read(String payload) {
        if(payload==null || payload.length()>maxBytes || payload.getBytes(StandardCharsets.UTF_8).length>maxBytes)
            throw ProtocolErrors.invalid("样例报文超过大小限制");
        try {
            var root=mapper.readTree(payload);
            if(root==null || !root.isObject()) throw ProtocolErrors.invalid("样例必须是 JSON 对象");
            return root;
        } catch(java.io.IOException e) {throw ProtocolErrors.invalid("JSON 无效、键重复或超过嵌套/数值限制");}
    }
    private void collect(JsonNode node,String path,List<Field> fields) {
        if(node.isObject()) {
            var entries=node.fields();
            while(entries.hasNext()) {var entry=entries.next();collect(entry.getValue(),path+"/"+entry.getKey().replace("~","~0").replace("/","~1"),fields);}
        } else if(node.isArray()) {
            for(int i=0;i<node.size();i++) collect(node.get(i),path+"/"+i,fields);
        } else {
            if(fields.size()>=maxFields) throw ProtocolErrors.invalid("样例字段数超过限制");
            fields.add(new Field(path,node.isNumber()?"NUMBER":node.isBoolean()?"BOOLEAN":node.isNull()?"NULL":"STRING",node.isNull()?"null":node.asText()));
        }
    }
}
