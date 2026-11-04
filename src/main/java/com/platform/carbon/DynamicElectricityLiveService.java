package com.platform.carbon;

import com.platform.iot.identity.DeviceIdentityProvider;
import com.platform.security.AuthenticationStateService;
import com.platform.system.mapper.SysUserMapper;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DynamicElectricityContracts.*;
import static com.platform.carbon.DynamicElectricityRepository.*;
import static com.platform.carbon.DynamicElectricityCore.problem;

/** 适配层一次上传即持久入队。后台重读当前身份和来源，结果发布不依赖浏览器请求。 */
@Service
@RequiredArgsConstructor
public class DynamicElectricityLiveService {
    private final CarbonAuthorization authorization;
    private final DeviceIdentityProvider devices;
    private final DynamicElectricityLiveRepository tasks;
    private final DynamicElectricityRepository results;
    private final DynamicElectricityLiveInput inputs;
    private final DynamicElectricityService calculations;
    private final SysUserMapper users;
    private final SysRoleMapper roleMapper;
    private final PlatformTransactionManager transactionManager;

    @Transactional(rollbackFor=Exception.class)
    public DeviceIntervalReceipt ingest(long user,Collection<String> roles,String building,DeviceIntervalRequest request) {
        authorization.requireCalculationRunner(user,roles,building);
        return store(user,building,request,"HTTP");
    }

    /** MQTT仅由受信主题处理器调用。建筑从启用身份解析，0表示固定建筑范围的系统任务，无浏览器账号或职责配置。 */
    @Transactional(rollbackFor=Exception.class)
    public DeviceIntervalReceipt ingestFromMqtt(DeviceIntervalRequest request) {
        var device=devices.find(request.deviceIdentity()).orElseThrow(()->problem("DYNAMIC_DEVICE_NOT_ACTIVE","设备尚未启用绑定"));
        return store(0,device.buildingId(),request,"MQTT_STANDARD_V1");
    }

    private DeviceIntervalReceipt store(long user,String building,DeviceIntervalRequest request,String transport) {
        var from=request.startInclusive();var to=request.endExclusive();
        if(from==null||to==null||from.getNano()!=0||from.getEpochSecond()%900!=0||!to.equals(from.plusSeconds(900))
                ||to.isAfter(Instant.now())||from.atZone(ZoneId.of("Asia/Shanghai")).getYear()<2000
                ||request.revision()<1||request.electricityKwh()==null&&request.factorKgCo2PerKwh()==null)
            throw problem("DYNAMIC_DEVICE_INTERVAL_INVALID","设备上报须为已结束的完整15分钟，至少含电量或因子");
        var device=devices.find(request.deviceIdentity()).orElseThrow(()->problem("DYNAMIC_DEVICE_NOT_ACTIVE","设备尚未启用绑定"));
        if(!building.equals(device.buildingId())||!request.profileCode().equals(device.expectedProfileCode())
                ||!tasks.pointOnDevice(building,request.pointId(),device.equipmentId()))
            throw problem("DYNAMIC_DEVICE_SCOPE","设备、测点、建筑、协议或千瓦时单位不匹配");
        var task=tasks.locked(building,from,to,user);
        var readings=new TreeMap<>(task.readings());
        var prior=readings.get(request.pointId());
        if(prior==null)prior=new DynamicElectricityLiveRepository.Reading(null,0,null,0,request.sourceNature(),request.sourceReference());
        BigDecimal q=merge(prior.quantity(),prior.quantityRevision(),request.electricityKwh(),request.revision());
        BigDecimal f=merge(prior.factor(),prior.factorRevision(),request.factorKgCo2PerKwh(),request.revision());
        long qr=request.electricityKwh()!=null?Math.max(prior.quantityRevision(),request.revision()):prior.quantityRevision();
        long fr=request.factorKgCo2PerKwh()!=null?Math.max(prior.factorRevision(),request.revision()):prior.factorRevision();
        if(request.revision()<Math.max(prior.quantityRevision(),prior.factorRevision()))
            return new DeviceIntervalReceipt(building,from,to,task.revision(),task.state());
        if(request.revision()==Math.max(prior.quantityRevision(),prior.factorRevision())
                &&!request.sourceNature().equals(prior.nature()))throw problem("DYNAMIC_DEVICE_REVISION_CONFLICT","同修订的数据性质不一致");
        var next=new DynamicElectricityLiveRepository.Reading(q,qr,f,fr,request.sourceNature(),request.sourceReference(),device.identityId(),transport);
        readings.put(request.pointId(),next);
        if(readings.size()>500)throw problem("DYNAMIC_CAPACITY","一个建筑时段最多500个测点");
        if(next.equals(prior))return new DeviceIntervalReceipt(building,from,to,task.revision(),task.state());
        tasks.update(task,readings,user);
        return new DeviceIntervalReceipt(building,from,to,task.revision()+1,"PENDING");
    }

    private static BigDecimal merge(BigDecimal old,long oldRevision,BigDecimal value,long revision) {
        if(value==null||revision<oldRevision)return old;
        if(value.signum()<0)throw problem("DYNAMIC_DEVICE_VALUE_INVALID","电量和因子须为非负数");
        if(revision==oldRevision&&old!=null&&old.compareTo(value)!=0)
            throw problem("DYNAMIC_DEVICE_REVISION_CONFLICT","同一测点时段及修订号对应不同值");
        return revision==oldRevision&&old!=null?old:value;
    }

    /** 不保存Token或角色。租约与版本复核使过期任务无法把新修订重新标成完成。 */
    void process(DynamicElectricityLiveRepository.Task task,String token) {
        process(task,token,new DynamicElectricityLiveInput.Capture());
    }
    private void process(DynamicElectricityLiveRepository.Task task,String token,DynamicElectricityLiveInput.Capture capture) {
        boolean systemTask=task.user()==0&&"DEVICE_INTERVAL".equals(task.sourceKind());
        Collection<String> roles;
        if(systemTask) {
            // MQTT接入已冻结归属；内部读取始终使用任务建筑和逐点目录校验，不接受消息里的角色、账号或建筑。
            if(task.readings().values().stream().noneMatch(r->"MQTT_STANDARD_V1".equals(r.transport())&&r.identityId()!=null))
                throw problem("DYNAMIC_SYSTEM_TASK_INVALID","系统任务缺少受信设备接入证据");
            roles=List.of("PLATFORM_ADMIN");
        } else {
            if(!AuthenticationStateService.isEnabled(users.selectSecurityState(task.user())))throw problem("DYNAMIC_ACTOR_DISABLED","接入账号已停用");
            roles=roleMapper.selectRoleKeysByUserId(task.user());
            authorization.requireCalculationRunner(task.user(),roles,task.building());
        }
        if(!"DEVICE_INTERVAL".equals(task.sourceKind())) {
            Instant from=Instant.ofEpochMilli(task.from()),to=Instant.ofEpochMilli(task.to());
            if(calculations.sourceVersion(task.building(),from,to,task.sourceKind()).equals(task.metadata()))tasks.checked(task,token);
            else calculations.calculate(task.user(),roles,task.building(),new CalculationRequest(task.grain(),from,to,task.sourceKind()));
            return;
        }
        String metadata=tasks.metadataVersion(task.building(),results);
        capture.pin(task.building()+":"+task.user()+":"+new TreeSet<>(roles)+":"+metadata);
        var applied=inputs.calculate(task,roles,capture);
        String version=hash(encode(List.of("DEVICE_INTERVAL_V1",task.inputHash(),metadata)));
        String id=hash(encode(List.of(task.building(),task.from(),task.to(),version)));
        var evidence=CarbonEvidence.tree(task.readings());
        var result=new Result(id,task.building(),"INTERVAL",Instant.ofEpochMilli(task.from()),Instant.ofEpochMilli(task.to()),version,"DEVICE_INTERVAL",
                applied,task.inputHash(),evidence,hash(encode(applied.factorIds())),System.currentTimeMillis());
        // 大屏读取轻量汇总，证据查询读取完整不可变结果；二者引用同一摘要。
        var summaryValues=new DynamicElectricityCore.Applied(applied.quantity(),applied.emissionKgCo2(),applied.factor(),
                List.of(result.factorHash()),applied.sourceNatures(),applied.timeAveraged());
        var summary=new Result(id,task.building(),"INTERVAL",result.start(),result.end(),version,"DEVICE_INTERVAL",summaryValues,
                task.inputHash(),CarbonEvidence.tree(Map.of("resultId",id)),result.factorHash(),result.capturedAt());
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            if(!systemTask)authorization.requireCalculationRunner(task.user(),roles,task.building());
            if(!metadata.equals(tasks.metadataVersion(task.building(),results)))throw problem("DYNAMIC_SOURCE_CHANGED","计算期间计量或能源配置变更");
            if(tasks.publish(task,token,metadata,summary))results.save(result);
        });
    }

    void refresh() {
        var capture=new DynamicElectricityLiveInput.Capture();
        for(var task:tasks.due(System.currentTimeMillis())) {
            String token=tasks.claim(task,System.currentTimeMillis());
            if(token==null)continue;
            try {process(task,token,capture);}
            catch(RuntimeException ex) {
                String code=ex instanceof BusinessException b?Objects.toString(b.getErrorCode(),"DYNAMIC_COMPUTE_FAILED"):"DYNAMIC_COMPUTE_FAILED";
                tasks.failed(task,token,code);
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dynamic interval awaiting calculation: building={}, start={}, code={}",task.building(),task.from(),code);
            }
        }
    }
    void refreshMetadata() {
        for(String building:tasks.buildings())tasks.metadataChanged(building,tasks.metadataVersion(building,results));
    }
}
