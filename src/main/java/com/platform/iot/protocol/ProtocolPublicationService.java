package com.platform.iot.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.profile.*;
import com.platform.adapter.publication.ProtocolSnapshotContracts.*;
import com.platform.adapter.publication.SnapshotValidator;
import com.platform.adapter.publication.SnapshotValidationException;
import com.platform.audit.sensitive.*;
import com.platform.framework.exception.BusinessException;
import com.platform.iot.onboarding.OnboardingAuditService;
import com.platform.iot.protocol.api.ProtocolContracts.Configuration;
import com.platform.iot.protocol.api.ProtocolPublicationContracts.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** 冻结配置、审批与运行状态分别管理；只有匹配当前目标序号和摘要的回执才能确认加载。 */
@Service
public class ProtocolPublicationService {
    static final Set<String> ADMIN=Set.of("PLATFORM_ADMIN");
    private final ProtocolPublicationRepository repository;
    private final ProtocolDraftService drafts;
    private final OnboardingAuditService audit;
    private final ObjectMapper mapper;
    private final long contactTimeout;
    private final String sourceSystem;
    public ProtocolPublicationService(ProtocolPublicationRepository repository,ProtocolDraftService drafts,
            OnboardingAuditService audit,ObjectMapper mapper,
            @Value("${protocol-publication.contact-timeout-ms:180000}") long contactTimeout,
            @Value("${ingestion.standard-source-system:MQTT_STANDARD_V1}") String sourceSystem) {
        this.repository=repository;this.drafts=drafts;this.audit=audit;this.mapper=mapper;
        if(contactTimeout<1000||contactTimeout>86400000) throw new IllegalArgumentException("目标联系超时必须在1秒至24小时之间");
        this.contactTimeout=contactTimeout;
        this.sourceSystem=sourceSystem;
    }
    public List<TargetView> targets(Set<String> roles) {ProtocolDraftService.requireAdmin(roles);return repository.targets().stream().map(this::view).toList();}
    @Transactional
    public TargetCreated register(TargetRequest request,long actor,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);
        if(repository.targets().size()>=100) throw ProtocolErrors.invalid("适配器目标最多100个");
        var topics=new TreeSet<>(request.allowedTopics());
        for(String topic:topics) if(topic.contains("+")||topic.contains("#")||!topic.equals(topic.trim())||topic.chars().anyMatch(Character::isISOControl))
            throw ProtocolErrors.invalid("目标范围必须是精确 Topic");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var target=new ProtocolPublicationRepository.Target(id(),request.name(),request.outputVersion(),json(topics),sha(key),0,0);
        repository.insertTarget(target);
        audit.record(actor,null,"REGISTER_PROTOCOL_TARGET","PROTOCOL_TARGET",target.id(),Map.of(),Map.of("outputVersion",target.output(),"topicCount",topics.size()));
        return new TargetCreated(view(target),key);
    }
    @Transactional
    public VersionView freeze(String draftId,long revision,long actor,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);
        var draft=drafts.detail(draftId,roles);
        if(draft.revision()!=revision) throw ProtocolErrors.conflict();
        drafts.validate(draft.configuration(),roles);
        String productHash=productHash(draft.configuration());
        var old=repository.frozen(draftId,revision);
        if(old.isPresent()) return versionView(old.get());
        String content=json(draft.configuration());
        var version=new ProtocolPublicationRepository.Version(id(),draftId,revision,content,null,productHash,sha(content),System.currentTimeMillis());
        repository.insertVersion(version);
        audit.record(actor,null,"FREEZE_PROTOCOL_VERSION","PROTOCOL_VERSION",version.id(),Map.of(),Map.of("draftRevision",revision,"digest",version.digest()));
        return versionView(version);
    }
    public List<VersionView> versions(Set<String> roles) {ProtocolDraftService.requireAdmin(roles);return repository.versions().stream().map(this::versionView).toList();}
    /** 历史高级字段原样冻结；禁用规则只归档，导入本身不分配发布序号或覆盖运行配置。 */
    @Transactional
    public List<VersionView> importLegacy(ImportRequest request,long actor,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);
        if(request.snapshotJson().getBytes(StandardCharsets.UTF_8).length>1048576
                ||request.archiveJson()!=null&&request.archiveJson().getBytes(StandardCharsets.UTF_8).length>1048576)
            throw ProtocolErrors.invalid("迁移文件超过1 MiB限制");
        Snapshot snapshot=read(request.snapshotJson(),Snapshot.class);
        try {SnapshotValidator.validate(snapshot,snapshot.outputVersion());}catch(IllegalArgumentException e){throw ProtocolErrors.invalid("历史启用配置集合无效");}
        Set<String> profileIds=new HashSet<>();snapshot.profiles().forEach(p->profileIds.add(p.profile().profileId()));
        if(!profileIds.equals(request.productBindings().keySet())) throw ProtocolErrors.invalid("每条历史协议必须且只能关联一个产品");
        List<VersionView> result=new ArrayList<>();
        for(Entry entry:snapshot.profiles()) {
            var p=entry.profile();
            var mappings=entry.mappings().stream().map(m->new com.platform.iot.protocol.api.ProtocolContracts.Mapping(m.sourcePath(),m.metricCode(),m.sourceUnit(),m.targetUnit(),m.scale(),m.offset(),m.required(),m.enabled(),m.sortOrder())).toList();
            var c=new Configuration("导入："+p.profileCode(),request.productBindings().get(p.profileId()),p.profileCode(),p.sourceTopic(),p.deviceIdentityType(),p.deviceIdentityPath(),p.protocolVersionPath(),p.expectedProtocolVersion(),p.timestampPath(),mappings);
            drafts.validate(c,roles);
            String key=id();String entryJson=json(entry);
            var version=new ProtocolPublicationRepository.Version(key,key,p.profileVersion(),json(c),entryJson,productHash(c),sha(entryJson),System.currentTimeMillis());
            repository.insertVersion(version);result.add(versionView(version));
        }
        if(request.archiveJson()!=null&&!request.archiveJson().isBlank()) {
            Snapshot archive=read(request.archiveJson(),Snapshot.class);
            if(archive.schemaVersion()!=1||!Objects.equals(archive.outputVersion(),snapshot.outputVersion())||archive.profiles()==null||archive.profiles().size()>100)
                throw ProtocolErrors.invalid("历史归档结构无效");
            String sanitized=json(archive);
            repository.archive(id(),sanitized,sha(sanitized),actor);
        }
        audit.record(actor,null,"IMPORT_PROTOCOL_VERSIONS","PROTOCOL_MIGRATION",id(),Map.of(),Map.of("profileCount",result.size(),"digest",sha(json(snapshot))));
        return List.copyOf(result);
    }
    public List<DeploymentView> deployments(String target,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);var t=target(target,false);
        return repository.deployments(target).stream().map(d->deploymentView(d,t)).toList();
    }
    public FrozenCommand prepare(PublishRequest request,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);
        var target=target(request.targetId(),false);
        requireReady(target,request.expectedSequence());
        var ids=request.versionIds().stream().sorted().toList();
        if(ids.size()!=new HashSet<>(ids).size()) throw ProtocolErrors.invalid("协议版本重复");
        // 普通发布按协议编码更新，未选择的现有协议必须保留；删减仅能通过显式历史回退。
        Map<String,String> merged=new TreeMap<>();
        repository.deployment(target.id(),target.sequence()).ifPresent(current -> {
            for(String versionId:readIds(current.versions())) {
                var version=repository.version(versionId).orElseThrow(ProtocolErrors::notFound);
                merged.put(read(version.json(),Configuration.class).profileCode(),versionId);
            }
        });
        Set<String> selectedCodes=new HashSet<>();
        for(String versionId:ids) {
            String code=entry(versionId).profile().profileCode();
            if(!selectedCodes.add(code)) throw ProtocolErrors.invalid("同一协议只能选择一个版本");
            merged.put(code,versionId);
        }
        ids=merged.values().stream().sorted().toList();
        if(ids.size()>100) throw ProtocolErrors.invalid("完整配置集合最多包含100个协议");
        var entries=ids.stream().map(this::entry).toList();
        Snapshot snapshot=new Snapshot(1,target.output(),entries);
        validateSnapshot(target,snapshot);
        String content=json(snapshot);
        return new FrozenCommand(target.id(),target.sequence(),sha(content),content,ids);
    }
    public FrozenCommand rollback(RollbackRequest request,Set<String> roles) {
        ProtocolDraftService.requireAdmin(roles);
        var target=target(request.targetId(),false);
        requireReady(target,request.expectedSequence());
        var previous=repository.deployment(target.id(),request.historicalSequence()).orElseThrow(ProtocolErrors::notFound);
        if(!"LOADED".equals(previous.status())) throw ProtocolErrors.invalid("仅可回退到曾确认加载的配置");
        var ids=readIds(previous.versions());
        ids.forEach(this::entry);
        validateSnapshot(target,read(previous.json(),Snapshot.class));
        return new FrozenCommand(target.id(),target.sequence(),previous.digest(),previous.json(),ids,true);
    }
    /** 审批处理器重新规范化冻结命令，禁止从通用审批入口提交任意未经约束的快照。 */
    public FrozenCommand validateCommand(FrozenCommand command) {
        if(command.rollback()) {
            var target=target(command.targetId(),false);
            requireReady(target,command.expectedSequence());
            // 回退只接受本目标曾加载的完整历史快照，不能借回退标记提交任意删减集合。
            var previous=repository.deployments(target.id()).stream().filter(d -> "LOADED".equals(d.status())
                    && Objects.equals(d.digest(),command.digest()) && Objects.equals(d.json(),command.contentJson())
                    && Objects.equals(readIds(d.versions()),command.versionIds())).findFirst()
                    .orElseThrow(()->ProtocolErrors.invalid("回退内容不属于已加载的历史配置"));
            return rollback(new RollbackRequest(target.id(),previous.sequence(),target.sequence(),"validation"),ADMIN);
        }
        var expected=prepare(new PublishRequest(command.targetId(),command.expectedSequence(),command.versionIds(),"validation"),ADMIN);
        if(!Objects.equals(command.digest(),expected.digest())||!Objects.equals(command.contentJson(),expected.contentJson()))
            throw ProtocolErrors.invalid("审批内容与冻结协议版本不一致");
        return expected;
    }
    @Transactional
    public void execute(FrozenCommand command,SensitiveOperationContext context) {
        var target=target(command.targetId(),true);
        requireReady(target,command.expectedSequence());
        validateCommand(command);
        if(target.sequence()==Long.MAX_VALUE) throw ProtocolErrors.conflict();
        long sequence=target.sequence()+1;
        repository.insertDeployment(new ProtocolPublicationRepository.Deployment(target.id(),sequence,command.digest(),
                command.contentJson(),json(command.versionIds()),context.requestId(),"PENDING_SYNC",null,System.currentTimeMillis(),0));
        repository.advance(target.id(),sequence);
    }
    /** 目标密钥仅以摘要保存；独立适配器身份不能复用浏览器管理员 JWT。 */
    public void authenticate(String targetId,String key) {
        if(key==null||key.length()>256) throw unauthorized();
        var target=repository.target(targetId,false).orElseThrow(this::unauthorized);
        if(!MessageDigest.isEqual(sha(key).getBytes(StandardCharsets.US_ASCII),target.keyHash().getBytes(StandardCharsets.US_ASCII))) throw unauthorized();
    }
    @Transactional
    public Envelope pull(String targetId,int schema,String output) {
        var target=target(targetId,true);
        if(schema!=1||!target.output().equals(output)) throw ProtocolErrors.invalid("适配器能力与登记目标不匹配");
        repository.seen(target.id(),System.currentTimeMillis());
        return repository.deployment(target.id(),target.sequence()).map(d->new Envelope(d.sequence(),d.digest(),d.json())).orElse(null);
    }
    @Transactional
    public void receipt(String targetId,Receipt receipt) {
        if(receipt==null||receipt.sequence()<1||receipt.digest()==null||!receipt.digest().matches("[a-f0-9]{64}")) throw ProtocolErrors.invalid("加载回执格式无效");
        var target=target(targetId,true);
        if(receipt.sequence()<target.sequence()) return;
        var deployment=repository.deployment(target.id(),receipt.sequence()).orElseThrow(ProtocolErrors::notFound);
        if(receipt.sequence()!=target.sequence()||!Objects.equals(deployment.digest(),receipt.digest())) throw ProtocolErrors.conflict();
        if(!"LOADED".equals(receipt.status())&&!"FAILED".equals(receipt.status())) throw ProtocolErrors.invalid("回执状态无效");
        // 同一版本成功后忽略迟到失败，避免网络重试把已确认加载倒退为失败。
        if("LOADED".equals(deployment.status())) return;
        String error="FAILED".equals(receipt.status())?safeError(receipt.errorCode()):null;
        long now=System.currentTimeMillis();
        repository.receipt(target.id(),receipt.sequence(),receipt.status(),error,"LOADED".equals(receipt.status())?now:0);
        repository.seen(target.id(),now);
    }
    private String productHash(Configuration configuration) {
        return sha(json(repository.productContract(configuration.productId())));
    }
    private Entry entry(String id) {
        var version=repository.version(id).orElseThrow(ProtocolErrors::notFound);
        Configuration c=read(version.json(),Configuration.class);
        drafts.validate(c,ADMIN);
        if(!version.productHash().equals(productHash(c))) throw ProtocolErrors.invalid("关联产品已变更，请重新冻结协议版本");
        repository.validateBindings(c.productId(),c.profileCode(),c.mappings().stream().filter(m->m.enabled()).map(m->m.metricCode()).toList(),sourceSystem);
        if(version.entryJson()!=null) return read(version.entryJson(),Entry.class);
        if(version.revision()>Integer.MAX_VALUE) throw ProtocolErrors.invalid("协议修订号超限");
        var profile=new ProtocolProfile(version.id(),c.profileCode(),(int)version.revision(),c.sourceTopic(),c.identityType(),c.identityPath(),
                c.discriminatorPath(),c.discriminatorValue(),c.timestampPath(),null,null,null,null,null,"EVIDENCE_ONLY","NONE",true);
        var mappings=c.mappings().stream().filter(m->m.enabled()).map(m->new ProtocolFieldMapping(UUID.nameUUIDFromBytes((version.id()+m.sourcePath()).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""),
                version.id(),m.sourcePath(),m.metricCode(),"DECIMAL",m.sourceUnit(),m.targetUnit(),m.scale(),m.offset(),m.required(),m.enabled(),m.sortOrder())).toList();
        return new Entry(profile,mappings);
    }
    private void validateSnapshot(ProtocolPublicationRepository.Target target,Snapshot snapshot) {
        try {SnapshotValidator.validate(snapshot,target.output());}
        catch(SnapshotValidationException e) {
            throw new BusinessException(400,"PROTOCOL_SNAPSHOT_"+e.getErrorCode(),"完整配置集合校验失败");
        }
        Set<String> allowed=new HashSet<>(readIds(target.topics()));
        if(snapshot.profiles().stream().anyMatch(p->!allowed.contains(p.profile().sourceTopic()))) throw ProtocolErrors.invalid("协议 Topic 超出目标授权范围");
        if(json(snapshot).getBytes(StandardCharsets.UTF_8).length>1048576) throw ProtocolErrors.invalid("完整配置集合超过1 MiB");
    }
    private void requireReady(ProtocolPublicationRepository.Target target,long sequence) {
        if(target.sequence()!=sequence) throw ProtocolErrors.conflict();
        if(System.currentTimeMillis()-target.seen()>contactTimeout) throw ProtocolErrors.invalid("目标尚未联系或已离线，不能发布");
    }
    private ProtocolPublicationRepository.Target target(String id,boolean lock) {return repository.target(id,lock).orElseThrow(ProtocolErrors::notFound);}
    private TargetView view(ProtocolPublicationRepository.Target t) {
        var d=repository.deployment(t.id(),t.sequence());
        String status=System.currentTimeMillis()-t.seen()>contactTimeout?"UNKNOWN":d.map(ProtocolPublicationRepository.Deployment::status).orElse("READY");
        return new TargetView(t.id(),t.name(),t.output(),readIds(t.topics()),t.seen(),t.sequence(),status,d.map(ProtocolPublicationRepository.Deployment::error).orElse(null));
    }
    private DeploymentView deploymentView(ProtocolPublicationRepository.Deployment d,ProtocolPublicationRepository.Target t) {
        String status=d.sequence()==t.sequence()&&System.currentTimeMillis()-t.seen()>contactTimeout?"UNKNOWN":d.status();
        return new DeploymentView(d.target(),d.sequence(),d.digest(),d.approval(),status,d.error(),d.created(),d.loaded());
    }
    private VersionView versionView(ProtocolPublicationRepository.Version v) {return new VersionView(v.id(),v.draftId(),v.revision(),v.digest(),read(v.json(),Configuration.class),v.created());}
    private String safeError(String code) {return code!=null&&code.matches("[A-Z0-9_]{1,80}")?code:"LOAD_FAILED";}
    private BusinessException unauthorized() {return new BusinessException(401,"ADAPTER_UNAUTHORIZED","适配器身份无效");}
    public String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw ProtocolErrors.invalid("配置无法编码");}}
    public <T>T read(String json,Class<T> type) {try{return mapper.readValue(json,type);}catch(Exception e){throw ProtocolErrors.invalid("配置格式无效");}}
    private List<String> readIds(String json) {try{return mapper.readValue(json,new TypeReference<List<String>>(){});}catch(Exception e){throw ProtocolErrors.invalid("配置列表格式无效");}}
    static String sha(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}
}
