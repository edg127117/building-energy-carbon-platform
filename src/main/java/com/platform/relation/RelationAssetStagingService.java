package com.platform.relation;

import com.platform.hvac.service.EquipmentCodeAllocator;
import com.platform.hvac.service.PointCodeNamingValidator;
import com.platform.system.service.BuildingScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static com.platform.relation.RelationErrors.*;
import static com.platform.relation.RelationStagingContracts.*;
import static com.platform.relation.RelationStagingRepository.*;

/** 治理建筑的新档案先进入当前关系草稿；编排不创建运行身份、别名或现场事实。 */
@Service
@Transactional(readOnly=true)
public class RelationAssetStagingService {
    private final RelationGovernanceRepository relations;
    private final RelationStagingRepository staging;
    private final BuildingScopeService scope;
    private final EquipmentCodeAllocator allocator;
    private final PointCodeNamingValidator naming;
    public RelationAssetStagingService(RelationGovernanceRepository relations,RelationStagingRepository staging,
                                       BuildingScopeService scope,EquipmentCodeAllocator allocator,PointCodeNamingValidator naming) {
        this.relations=relations;this.staging=staging;this.scope=scope;this.allocator=allocator;this.naming=naming;
    }
    public Mutation list(long user,Collection<String> roles,String building,String version) {
        var selected=version(user,roles,building,version,false);
        return view(selected);
    }
    public Options options(long user,Collection<String> roles,String building,String version) {
        version(user,roles,building,version,false);return staging.options();
    }
    @Transactional(rollbackFor=Exception.class)
    public Mutation equipment(long user,Collection<String> roles,String building,String version,EquipmentRequest request) {
        var selected=editable(user,roles,building,version,request.expectedRevision(),request.reason());
        text(request.name(),100);optionalText(request.manufacturer(),100);
        var type=staging.type(request.typeCode());
        if(!Integer.valueOf(1).equals(type.get("status")))throw error(409,REFERENCE_CONFLICT,"设备类型已停用");
        String productId=trim(request.productId()),productHash=null;
        if(productId!=null) {
            var product=staging.product(productId);
            if(!"ENABLED".equals(product.get("status"))||!request.typeCode().equals(product.get("equipment_type_code")))
                throw error(409,REFERENCE_CONFLICT,"产品不可用或与设备类型不一致");
            productHash=fingerprint(product);
        }
        String space=trim(request.spaceId()),system=trim(request.systemGroupId());
        reference(selected,"SPACE",space);reference(selected,"SYSTEM",system);
        String code=allocator.next(string(type,"asset_code_prefix"),staging.codes(building,"EQUIPMENT"));
        text(code,50);
        var definition=new EquipmentDefinition(request.typeCode(),string(type,"equip_category"),productId,trim(request.manufacturer()),fingerprint(type),productHash);
        insert(selected,"EQUIPMENT",code,request.name().trim(),encode(definition),user,space,system,null);
        audit(selected,user,"STAGE_EQUIPMENT",request.reason());return view(relations.findVersion(version,false).orElseThrow());
    }
    @Transactional(rollbackFor=Exception.class)
    public Mutation point(long user,Collection<String> roles,String building,String version,PointRequest request) {
        var selected=editable(user,roles,building,version,request.expectedRevision(),request.reason());
        text(request.name(),100);optionalText(request.unit(),20);optionalText(request.suffixCode(),20);
        String dataType=Objects.toString(request.dataType(),"").toUpperCase(Locale.ROOT);
        String status=Objects.toString(request.status(),"").toUpperCase(Locale.ROOT);
        if(!Set.of("ANALOG","DIGITAL","ACCUMULATE").contains(dataType)||!Set.of("ONLINE","OFFLINE").contains(status)
                ||request.isForCalc()==null||!Set.of(0,1).contains(request.isForCalc()))throw error(400,VALIDATION_FAILED,"测点数据类型、启用或计算标志无效");
        if(request.isForCalc()==1&&trim(request.unit())==null)throw error(400,VALIDATION_FAILED,"参与计算的测点必须明确单位");
        var ruleValues=staging.rule(request.namingRuleId());var rule=namingRule(ruleValues);
        if(!Integer.valueOf(1).equals(rule.getStatus()))throw error(409,REFERENCE_CONFLICT,"命名规则已停用");
        String equipment=trim(request.equipmentId()),system=trim(request.systemGroupId());
        boolean environment="ENV".equals(rule.getComponentCode());
        if(environment&&equipment!=null)throw error(400,VALIDATION_FAILED,"建筑环境测点不能绑定设备");
        if(!environment&&(equipment==null||system==null||trim(request.suffixCode())==null))throw error(400,VALIDATION_FAILED,"设备测点必须明确设备、系统与参数后缀");
        reference(selected,"EQUIPMENT",equipment);reference(selected,"SYSTEM",system);
        if(equipment!=null) {
            var assignment=relations.listAssignments(version).stream().filter(a->a.objectType().equals("EQUIPMENT")&&a.objectId().equals(equipment)).findFirst()
                    .orElseThrow(()->error(409,REFERENCE_CONFLICT,"设备不在当前关系版本中"));
            if(!Objects.equals(system,assignment.systemGroupId()))throw error(409,REFERENCE_CONFLICT,"测点必须与设备处于同一系统");
        }
        String code=trim(request.pointCode());
        if(code==null) {
            if(environment&&!rule.getCodeTemplate().contains("[n]"))code=rule.getCodeTemplate();
            else if(equipment!=null) {
                String equipmentCode=relations.currentOrStagedCode(version,building,"EQUIPMENT",equipment);
                String candidate=equipmentCode+"_"+trim(request.suffixCode());
                if(naming.matches(rule,candidate))code=candidate;
            }
            if(code==null)throw error(400,VALIDATION_FAILED,"该命名模板无法唯一生成编码，请明确填写标准测点编码");
        }
        text(code,100);
        if(!naming.matches(rule,code)||!environment&&!code.endsWith("_"+trim(request.suffixCode())))throw error(400,VALIDATION_FAILED,"测点编码不符合所选命名规则或后缀");
        if(staging.codes(building,"POINT").contains(code))throw error(409,REFERENCE_CONFLICT,"测点编码已存在或已被历史暂存保留，不能重复使用");
        var definition=new PointDefinition(rule.getRuleId(),rule.getFamilyCode(),rule.getComponentCode(),trim(request.suffixCode()),dataType,trim(request.unit()),request.isForCalc(),status,fingerprint(ruleValues));
        insert(selected,"POINT",code,request.name().trim(),encode(definition),user,null,system,equipment);
        audit(selected,user,"STAGE_POINT",request.reason());return view(relations.findVersion(version,false).orElseThrow());
    }
    @Transactional(rollbackFor=Exception.class)
    public Mutation remove(long user,Collection<String> roles,String building,String version,String id,RemoveRequest request) {
        var selected=editable(user,roles,building,version,request.expectedRevision(),request.reason());
        var row=staging.list(version).stream().filter(r->r.id().equals(id)&&r.status().equals("STAGED")).findFirst()
                .orElseThrow(()->error(404,NOT_FOUND,"暂存档案不存在或已发布"));
        staging.remove(row);audit(selected,user,"CANCEL_STAGED_ASSET",request.reason());return view(relations.findVersion(version,false).orElseThrow());
    }
    private void insert(RelationGovernanceRepository.VersionRow version,String type,String code,String name,String definition,
                        long user,String space,String system,String equipment) {
        if(staging.list(version.versionId()).size()>=1000)throw error(409,VALIDATION_FAILED,"每个版本最多暂存1000个档案，请分批发布");
        staging.insert(id(),version.versionId(),version.buildingId(),type,id(),id(),code,name,definition,user,space,system,equipment);
    }
    private RelationGovernanceRepository.VersionRow version(long user,Collection<String> roles,String building,String id,boolean lock) {
        if(roles==null||Collections.disjoint(roles,List.of("ENERGY_MANAGER","PLATFORM_ADMIN")))throw error(403,FORBIDDEN,"只有关系维护或审核人员可读取暂存档案");
        scope.checkAccess(user,roles,building);
        var version=relations.findVersion(id,lock).orElseThrow(()->error(404,NOT_FOUND,"关系版本不存在"));
        if(!building.equals(version.buildingId()))throw error(409,CROSS_BUILDING,"关系版本不属于指定建筑");
        return version;
    }
    private RelationGovernanceRepository.VersionRow editable(long user,Collection<String> roles,String building,String id,Long revision,String reason) {
        if(roles==null||!roles.contains("ENERGY_MANAGER"))throw error(403,FORBIDDEN,"只有能源管理员可以维护暂存档案");
        text(reason,500);var version=version(user,roles,building,id,true);
        if(!relations.isGoverned(building))throw error(409,GOVERNANCE_REQUIRED,"请先发布建筑关系初始化版本，再在新关系草稿建档");
        if(!"DRAFT".equals(version.status())||revision==null||relations.bumpDraft(id,revision)!=1)throw error(409,VERSION_CONFLICT,"关系版本修订已变化或不可编辑");
        return version;
    }
    private void reference(RelationGovernanceRepository.VersionRow version,String type,String id) {
        if(id!=null&&!relations.objectExistsInVersion(version.versionId(),type,id,version.buildingId()))throw error(409,CROSS_BUILDING,"档案归属不存在、跨建筑或引用了其他草稿的对象");
    }
    private Mutation view(RelationGovernanceRepository.VersionRow version) {
        return new Mutation(version.versionId(),version.configRevision(),staging.list(version.versionId()).stream().map(r->new Asset(r.id(),r.type(),r.object(),r.node(),r.code(),r.name(),r.status(),r.definition(),r.space(),r.system(),r.equipment())).toList());
    }
    private void audit(RelationGovernanceRepository.VersionRow version,long user,String action,String reason) {
        relations.clearIssues(version.versionId());
        relations.insertAudit(version.buildingId(),user,action,"RELATION_VERSION",version.versionId(),version.versionId(),null,"DRAFT","DRAFT",reason,"暂存档案尚未进入运行资产；发布沿用独立关系审核",null,null);
    }
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
    private static String trim(String text){return text==null||text.isBlank()?null:text.trim();}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw error(400,VALIDATION_FAILED,"配置文本缺失或超长");}
    private static void optionalText(String value,int max){if(value!=null&&value.length()>max)throw error(400,VALIDATION_FAILED,"配置文本超长");}
}
