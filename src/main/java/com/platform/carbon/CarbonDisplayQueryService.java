package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import com.platform.energy.period.EnergyPeriodAuthorization;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.platform.carbon.CarbonDisplayQueryContracts.*;

/** 授权后读取准备结果；原始服务只由后台调用，查询线程不承担年度冻结证据重建。 */
@Service
public class CarbonDisplayQueryService {
    private final EnergyPeriodAuthorization authorization;
    private final CarbonDisplayQueryRepository repository;
    private final CarbonDisplaySourceVersion versions;
    private final CarbonDisplayQueryCache cache;
    private final JointFlowService flow;
    private final CarbonIntensityService intensity;
    private final ObjectMapper mapper;
    public CarbonDisplayQueryService(EnergyPeriodAuthorization authorization,CarbonDisplayQueryRepository repository,CarbonDisplaySourceVersion versions,
            CarbonDisplayQueryCache cache,JointFlowService flow,CarbonIntensityService intensity,ObjectMapper mapper){
        this.authorization=authorization;this.repository=repository;this.versions=versions;this.cache=cache;this.flow=flow;this.intensity=intensity;this.mapper=mapper;
    }
    public View jointFlow(long user,Collection<String> roles,JointFlowContracts.Request input){return query(user,roles,CarbonDisplayQueryContracts.flow(input));}
    public View intensity(long user,Collection<String> roles,String building,String mode,int year,Integer month,String timezone){return query(user,roles,CarbonDisplayQueryContracts.intensity(building,mode,year,month,timezone));}
    private View query(long user,Collection<String> roles,Query q){return read(user,roles,q,true);}
    View inspect(long user,Collection<String> roles,Query q){return read(user,roles,q,false);}
    void prepare(long user,Collection<String> roles,Query q){
        authorize(user,roles,q);
        // 仅登记四个默认查询；不在预热发现或就绪检查中执行原服务，也不清空已有结果/租约。
        repository.request(q,encode(q),user,versions.version(q),System.currentTimeMillis());
    }
    private View read(long user,Collection<String> roles,Query q,boolean register){
        authorize(user,roles,q);
        String version=versions.version(q);var h=repository.head(q.key());
        if(h!=null&&version.equals(h.source())&&version.equals(h.built())&&"READY".equals(h.state())) {
            String payload=cache.get(q.key(),version);Object result=decode(payload,h,q);
            if(result==null){payload=repository.payload(h);result=decode(payload,h,q);if(result!=null)cache.put(q.key(),version,payload);}
            if(result==null)throw new BusinessException(503,"CARBON_DISPLAY_INTEGRITY","能碳展示结果完整性校验失败");
            return new View(CONTRACT,q.kind(),q.buildingId(),q.key(),version,"READY",result,0);
        }
        // 后台发起人被撤权不代表当前读者被撤权；当前读者通过授权后可重新登记任务。
        if(h!=null&&version.equals(h.source())&&"FAILED".equals(h.state())&&!Integer.valueOf(403).equals(h.errorStatus()))
            throw new BusinessException(h.errorStatus()==null?503:h.errorStatus(),h.error(),"能碳展示结果准备失败，请根据错误码检查来源或稍后重试");
        if(register)repository.request(q,encode(q),user,version,System.currentTimeMillis());
        return new View(CONTRACT,q.kind(),q.buildingId(),q.key(),version,"PREPARING",null,1);
    }
    private void authorize(long user,Collection<String> roles,Query q){authorization.requireReader(roles);authorization.checkBuilding(user,roles,q.buildingId());}
    boolean rebuild(CarbonDisplayQueryRepository.Head h,String token,Collection<String> roles){
        Query q=parse(h);authorize(h.user(),roles,q);
        Object data="JOINT_FLOW".equals(q.kind())?flow.query(h.user(),roles,q.flow()):intensity.query(h.user(),roles,q.buildingId(),q.mode(),q.year(),q.month(),q.timezoneId());
        String current=versions.version(q);
        if(!h.source().equals(current)){repository.request(q,encode(q),h.user(),current,System.currentTimeMillis());return false;}
        String payload=encode(data);
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1000000)throw new BusinessException(503,"CARBON_DISPLAY_CAPACITY","能碳展示结果超过容量");
        if(!repository.publish(h,token,payload,CarbonCalculationCore.sha256(payload),System.currentTimeMillis()))return false;
        cache.put(h.key(),current,payload);return true;
    }
    Query parse(CarbonDisplayQueryRepository.Head h){
        try {
            Query q=mapper.readValue(h.query(),Query.class);
            if(!Set.of("JOINT_FLOW","CARBON_INTENSITY").contains(q.kind())||!q.key().equals(h.key())||!q.buildingId().equals(h.building())||!q.kind().equals(h.kind()))throw new IllegalStateException("展示任务范围不匹配");
            return q;
        }catch(JsonProcessingException e){throw new IllegalStateException("展示任务无法读取",e);}
    }
    private Object decode(String payload,CarbonDisplayQueryRepository.Head h,Query q){
        if(payload==null||!Objects.equals(h.hash(),CarbonCalculationCore.sha256(payload)))return null;
        try{
            if("JOINT_FLOW".equals(q.kind())) {
                var v=mapper.readValue(payload,JointFlowContracts.View.class);
                return q.buildingId().equals(v.buildingId())&&q.mode().equals(v.periodType())&&q.period().equals(v.period())
                        &&Objects.equals(q.throughMonth(),v.throughMonth())?v:null;
            }
            var v=mapper.readValue(payload,CarbonIntensityContracts.View.class);
            java.time.LocalDate start=java.time.LocalDate.of(q.year(),"MONTH".equals(q.mode())?q.month():1,1);
            java.time.LocalDate end="FORMAL_YEAR".equals(q.mode())?start.plusYears(1):start.plusMonths("MONTH".equals(q.mode())?1:q.month());
            return q.buildingId().equals(v.buildingId())&&q.mode().equals(v.mode())&&q.timezoneId().equals(v.timezoneId())
                    &&start.equals(v.start())&&end.equals(v.endExclusive())?v:null;
        }
        catch(JsonProcessingException|IllegalArgumentException|NullPointerException e){return null;}
    }
    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("能碳展示结果无法编码",e);}}
}
