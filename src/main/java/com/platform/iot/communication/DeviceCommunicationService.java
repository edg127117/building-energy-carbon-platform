package com.platform.iot.communication;

import com.platform.framework.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.platform.iot.communication.CommunicationModels.*;

/**
 * MySQL 持久化的通讯状态机。身份锁先于目标锁，接入与超时扫描按同一顺序串行化；
 * 不读取 TDengine 或重算业务值，也不依赖浏览器访问来驱动状态。
 */
@Service
@Slf4j
public class DeviceCommunicationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final CommunicationProperties properties;
    private final Clock clock;
    private final int daikinPeriod;
    private final String observerId = UUID.randomUUID().toString();
    private static final String SCOPE_SQL = """
        SELECT i.identity_id,i.identity_value,i.identity_type,i.expected_profile_code,i.status,
          i.update_time,e.equip_id,e.building_id,e.product_id,pr.status AS product_status,
          d.source_id,m.mapping_version
        FROM biz_device_identity i JOIN biz_equipment e ON e.equip_id=i.equip_id
          AND e.building_id=i.building_id AND e.del_flag=0
        LEFT JOIN biz_device_product pr ON pr.product_id=e.product_id
        LEFT JOIN biz_pending_device pd ON pd.bound_identity_id=i.identity_id AND pd.status='BOUND'
        LEFT JOIN biz_daikin_directory d ON d.pending_id=pd.pending_id AND i.identity_type='DAIKIN_UNIT'
        LEFT JOIN biz_daikin_project_mapping m ON m.source_id=d.source_id AND m.site_id=d.site_id AND m.building_id=e.building_id
        WHERE i.identity_id=?
        """;

    @Autowired
    public DeviceCommunicationService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, PlatformTransactionManager manager,
            CommunicationProperties properties, @Value("${daikin.monitoring.interval-seconds:60}") int daikinPeriod) {
        this(jdbc, manager, properties, Clock.systemUTC(), daikinPeriod);
    }
    public DeviceCommunicationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            CommunicationProperties properties, Clock clock, int daikinPeriod) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(manager); this.properties=properties;
        this.clock=clock; this.daikinPeriod=daikinPeriod;
    }

    /** 只供可信接入层调用；身份/协议已验证仍在这里复查当前建筑和映射，历史或重放不续租。 */
    public boolean observe(Signal signal) {
        if(signal==null||signal.identityId()==null||signal.evidenceKey()==null||signal.evidenceKey().length()>200
                ||signal.source()==null||!Set.of("LIVE_TELEMETRY","LIVE_POLL","DEVICE_REPORTED_OFFLINE").contains(signal.reason()))return false;
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Scope scope = scope(signal.identityId(), true);
            if (scope==null || !scope.eligible() || !scope.equipment().equals(signal.equipmentId())
                    || !scope.building().equals(signal.buildingId()) || !scope.source().equals(signal.source())) return false;
            if(scope.source().startsWith("DAIKIN:")&&(signal.mappingRevision()==null||signal.mappingRevision()!=scope.mappingRevision()))return false;
            if(signal.profileCode()!=null&&!scope.profile().equals(signal.profileCode()))return false;
            Policy policy = policy(scope);
            if (!policy.monitored()) return false;
            long now=clock.millis(), jitter=jitter(policy.periodSeconds());
            // 接收时间只能截断、不能把缓存/重传重新标记为新观测；允许有限设备时钟抖动。
            long at=Math.min(signal.observedAt(), signal.receivedAt());
            if(scope.updated()!=null&&at<scope.updated().getTime())return false;
            if (at<=0 || signal.observedAt()>now+jitter || signal.receivedAt()>now+jitter
                    || now-at>offlineMillis(policy.periodSeconds())) return false;
            Target target=ensure(scope,policy,now);
            if (at<=target.lastEvidenceAt() || signal.evidenceKey().equals(target.evidenceKey())) return false;
            // 新鲜且可归属的首包本身也证明当前入口可工作，不等待下一次连接健康脉冲。
            if(!sourceHealthy(scope.source(),now))sourceHealth(scope.source(),"HEALTHY",now);
            long contact=signal.offline()?target.lastContact():Math.min(at,now);
            int good=signal.offline()?0:Math.min(2,target.goodCount()+1);
            boolean alarm=signal.offline() || target.alarm() && good<2;
            String value=signal.offline()?"OFFLINE":"ONLINE";
            String reason=signal.offline()?"DEVICE_REPORTED_OFFLINE":signal.reason();
            jdbc.update("""
                UPDATE biz_device_comm_target SET last_evidence_at=?,evidence_key=?,last_contact_at=?,negative_at=?,
                  good_count=?,alarm_open=?,state=?,reason=?,report_delayed=0,revision=revision+1 WHERE identity_id=?
                """,at,signal.evidenceKey(),contact,signal.offline()?at:0,good,alarm,value,reason,scope.identity());
            transition(scope,target.state(),value,reason,now,policy.revision());
            return true;
        }));
    }

    /** 独立实例租约；一个实例断线不会覆盖仍健康的另一个消费者。网关 DOWN 是单独的上游证据。 */
    public void sourceHealth(String source, String state, long evidenceAt) {
        if (!Set.of("HEALTHY","FAILED","DOWN").contains(state) || source==null || source.length()>240) throw invalid("来源状态无效");
        if (evidenceAt>clock.millis()+30_000 || evidenceAt<=0) return;
        transaction.executeWithoutResult(status -> {
            String observer=source.startsWith("GATEWAY:")?"GATEWAY":observerId;
            long lease=source.startsWith("DAIKIN:")?Math.max(properties.getSourceLeaseSeconds()*1000L,2L*daikinPeriod*1000+jitter(daikinPeriod)):properties.getSourceLeaseSeconds()*1000L;
            int changed=jdbc.update("UPDATE biz_device_comm_source SET health=?,observed_at=?,lease_until=? WHERE source_key=? AND observer_id=? AND observed_at<=?",
                    state,evidenceAt,evidenceAt+lease,source,observer,evidenceAt);
            if(changed==0 && jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_source WHERE source_key=? AND observer_id=?",Integer.class,source,observer)==0) {
                try { jdbc.update("INSERT INTO biz_device_comm_source(source_key,observer_id,health,observed_at,lease_until) VALUES(?,?,?,?,?)",
                        source,observer,state,evidenceAt,evidenceAt+lease); }
                catch(DuplicateKeyException raced) { /* 下一个租约脉冲重试；旧证据不能覆盖新观测。 */ }
            }
        });
    }

    public void reconcile(String identityId) {
        transaction.executeWithoutResult(status -> {
            Scope scope=scope(identityId,true);
            if(scope==null) {
                jdbc.update("DELETE FROM biz_device_comm_target WHERE identity_id=?",identityId);
                return;
            }
            Policy policy=policy(scope);
            long now=clock.millis();
            Target target=ensure(scope,policy,now);
            View view=evaluate(scope,policy,target,now);
            boolean alarm=target.alarm() || view.applicable() && "OFFLINE".equals(view.value());
            jdbc.update("UPDATE biz_device_comm_target SET state=?,reason=?,report_delayed=?,alarm_open=?,good_count=?,revision=revision+1 WHERE identity_id=?",
                    view.value(),view.reason(),view.delayed(),alarm,"OFFLINE".equals(view.value())?0:target.goodCount(),identityId);
            transition(scope,target.state(),view.value(),view.reason(),now,policy.revision());
        });
    }

    public List<String> identitiesAfter(String cursor, int limit) {
        return jdbc.queryForList("SELECT identity_id FROM biz_device_identity WHERE identity_id>? ORDER BY identity_id LIMIT ?",String.class,cursor,limit);
    }
    public View equipment(String equipmentId, String buildingId) {
        String sql=equipmentProjectionSql().replace("/* COMM_SCOPE_FILTER */"," AND e.equip_id=:communicationEquipment AND e.building_id=:communicationBuilding ");
        var rows=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(sql,
                Map.of("commNow",clock.millis(),"communicationEquipment",equipmentId,"communicationBuilding",buildingId),
                (rs,n)->new View(true,rs.getString("communication_value"),rs.getString("reason"),rs.getString("source_key"),
                        rs.getObject("observed_at")==null?null:rs.getLong("observed_at"),"DEVICE","REPORT_DELAYED".equals(rs.getString("reason")),
                        rs.getBoolean("alarm_open"),rs.getObject("next_deadline")==null?null:rs.getLong("next_deadline"),rs.getLong("policy_revision")));
        return rows.isEmpty()?View.unmonitored():rows.getFirst();
    }
    /** 统一读取当前规则、租约和证据的 SQL 投影；查询不更新状态，权限由调用方复核，多路径任一路径可用。 */
    public String equipmentProjectionSql() {
        String period="COALESCE(pp.period_seconds,pf.period_seconds,CASE WHEN i.identity_type='DAIKIN_UNIT' THEN "
                +daikinPeriod+" ELSE pdflt.period_seconds END,"+properties.getDefaultPeriodSeconds()+")";
        String jitter="(CASE WHEN ("+period+")*200>15000 THEN ("+period+")*200 ELSE 15000 END)";
        String valid="x.equip_id=e.equip_id AND x.building_id=e.building_id AND x.scope_profile=i.expected_profile_code"
                +" AND COALESCE(x.scope_product,'')=COALESCE(e.product_id,'') AND x.scope_identity=i.identity_value"
                +" AND (x.identity_updated=i.update_time OR x.identity_updated IS NULL AND i.update_time IS NULL)"
                +" AND x.mapping_revision=COALESCE(m.mapping_version,0)";
        String source="CASE WHEN i.identity_type='DAIKIN_UNIT' THEN CONCAT('DAIKIN:',d.source_id) ELSE 'MQTT' END";
        valid+=" AND x.source_key=("+source+")";
        String gatewayDown="EXISTS(SELECT 1 FROM biz_device_comm_source gw WHERE gw.source_key=x.gateway_key AND gw.observer_id='GATEWAY' AND gw.health='DOWN')";
        String healthy="EXISTS(SELECT 1 FROM biz_device_comm_source cs WHERE cs.source_key=("+source+") AND cs.health='HEALTHY' AND cs.lease_until>:commNow)";
        String expired=":commNow>=COALESCE(NULLIF(x.last_contact_at,0),x.monitoring_since,:commNow)+("+period+")*3000+"+jitter;
        String delayed=":commNow>=x.last_contact_at+("+period+")*2000+"+jitter;
        String negative="x.negative_at>0 AND x.negative_at>=x.last_contact_at";
        String base="""
            FROM biz_device_identity i JOIN biz_equipment e ON e.equip_id=i.equip_id AND e.building_id=i.building_id AND e.del_flag=0
            LEFT JOIN biz_device_product pr ON pr.product_id=e.product_id
            LEFT JOIN biz_pending_device pend ON pend.bound_identity_id=i.identity_id AND pend.status='BOUND'
            LEFT JOIN biz_daikin_directory d ON d.pending_id=pend.pending_id AND i.identity_type='DAIKIN_UNIT'
            LEFT JOIN biz_daikin_project_mapping m ON m.source_id=d.source_id AND m.site_id=d.site_id AND m.building_id=e.building_id
            LEFT JOIN biz_device_comm_target x ON x.identity_id=i.identity_id
            LEFT JOIN biz_device_comm_policy pp ON pp.scope_key=CONCAT('PRODUCT:',e.product_id)
            LEFT JOIN biz_device_comm_policy pf ON pf.scope_key=CONCAT('PROFILE:',i.expected_profile_code)
            LEFT JOIN biz_device_comm_policy pdflt ON pdflt.scope_key='DEFAULT'
            WHERE i.status=1 AND (e.product_id IS NULL OR pr.status='ENABLED')
              AND (i.identity_type<>'DAIKIN_UNIT' OR m.mapping_version IS NOT NULL)
              AND COALESCE(pp.monitored,pf.monitored,pdflt.monitored,1)=1 /* COMM_SCOPE_FILTER */
            """;
        String state="CASE WHEN x.identity_id IS NULL OR NOT ("+valid+") THEN 'UNKNOWN' WHEN "+gatewayDown
                +" OR ("+negative+") THEN 'OFFLINE' WHEN NOT ("+healthy+") THEN 'UNKNOWN' WHEN "+expired
                +" THEN 'OFFLINE' WHEN x.last_contact_at=0 THEN 'UNKNOWN' ELSE 'ONLINE' END";
        String reason="CASE WHEN x.identity_id IS NULL THEN 'WAITING_INITIAL_CONTACT' WHEN NOT ("+valid+") THEN 'STALE_SCOPE'"
                +" WHEN "+gatewayDown+" THEN 'UPSTREAM_GATEWAY_OFFLINE' WHEN "+negative+" THEN 'DEVICE_REPORTED_OFFLINE'"
                +" WHEN NOT ("+healthy+") THEN 'PLATFORM_PIPELINE_UNAVAILABLE' WHEN "+expired+" THEN 'DEVICE_TIMEOUT'"
                +" WHEN x.last_contact_at=0 THEN 'WAITING_INITIAL_CONTACT' WHEN "+delayed+" THEN 'REPORT_DELAYED'"
                +" WHEN i.identity_type='DAIKIN_UNIT' THEN 'LIVE_POLL' ELSE 'LIVE_TELEMETRY' END";
        return "SELECT q.equip_id,q.building_id,CASE WHEN SUM(CASE WHEN q.communication_value='ONLINE' THEN 1 ELSE 0 END)>0 THEN 'ONLINE'"
                +" WHEN SUM(CASE WHEN q.communication_value='UNKNOWN' THEN 1 ELSE 0 END)>0 THEN 'UNKNOWN' ELSE 'OFFLINE' END AS communication_value,"
                +" CASE WHEN SUM(CASE WHEN q.communication_value='ONLINE' AND q.reason<>'REPORT_DELAYED' THEN 1 ELSE 0 END)>0 THEN MAX(CASE WHEN q.communication_value='ONLINE' AND q.reason<>'REPORT_DELAYED' THEN q.reason END)"
                +" WHEN SUM(CASE WHEN q.communication_value='ONLINE' THEN 1 ELSE 0 END)>0 THEN 'REPORT_DELAYED'"
                +" WHEN SUM(CASE WHEN q.communication_value='UNKNOWN' THEN 1 ELSE 0 END)>0 THEN MAX(CASE WHEN q.communication_value='UNKNOWN' THEN q.reason END) ELSE MAX(q.reason) END AS reason,"
                +" MAX(q.observed_at) AS observed_at,CASE WHEN COUNT(DISTINCT q.source_key)>1 THEN 'MULTIPLE' ELSE MAX(q.source_key) END AS source_key,MAX(CASE WHEN q.communication_value='OFFLINE' THEN 1 ELSE q.alarm_open END) AS alarm_open,MAX(q.next_deadline) AS next_deadline,MAX(q.policy_revision) AS policy_revision FROM (SELECT e.equip_id,e.building_id,"
                +state+" AS communication_value,"+reason+" AS reason,CASE WHEN "+valid+" THEN NULLIF(x.last_contact_at,0) ELSE NULL END AS observed_at,"
                +source+" AS source_key,CASE WHEN "+valid+" THEN x.alarm_open ELSE 0 END AS alarm_open,COALESCE(NULLIF(x.last_contact_at,0),x.monitoring_since,:commNow)+("+period+")*3000+"+jitter+" AS next_deadline,COALESCE(pp.revision,pf.revision,pdflt.revision,1) AS policy_revision "+base+") q GROUP BY q.equip_id,q.building_id";
    }

    public List<Policy> policies() { return jdbc.query("SELECT * FROM biz_device_comm_policy ORDER BY scope_key",(rs,n)->new Policy(rs.getString("scope_key"),rs.getInt("period_seconds"),rs.getBoolean("monitored"),rs.getString("evidence"),rs.getLong("revision"))); }
    public Map<String,Object> gateway(String identityId,Set<String> roles) {
        admin(roles);
        return transaction.execute(status->{
            Scope scope=scope(identityId,true);
            if(scope==null)throw new BusinessException(404,"COMMUNICATION_IDENTITY_NOT_FOUND","正式身份不存在");
            ensure(scope,policy(scope),clock.millis());
            return jdbc.queryForMap("SELECT identity_id,gateway_key,gateway_revision AS revision FROM biz_device_comm_target WHERE identity_id=?",identityId);
        });
    }
    /** 网关归属是明确配置，不从电表名称、空间或 MQTT 客户端猜测；迁移绑定须有版本和审计。 */
    public Map<String,Object> attachGateway(String identityId,GatewayRequest request,Long actor,Set<String> roles) {
        admin(roles);
        return transaction.execute(status->{
            Scope scope=scope(identityId,true);
            if(scope==null||!scope.eligible())throw new BusinessException(404,"COMMUNICATION_IDENTITY_NOT_FOUND","启用的正式身份不存在");
            Policy policy=policy(scope);
            ensure(scope,policy,clock.millis());
            String key=request.gatewayKey()==null||request.gatewayKey().isBlank()?null:"GATEWAY:"+request.gatewayKey().trim();
            if(jdbc.update("UPDATE biz_device_comm_target SET gateway_key=?,gateway_revision=gateway_revision+1,revision=revision+1 WHERE identity_id=? AND gateway_revision=?",key,identityId,request.expectedRevision())!=1)throw conflict();
            jdbc.update("INSERT INTO biz_device_comm_policy_audit(scope_key,period_seconds,monitored,evidence,revision,changed_by,gateway_key) VALUES(?,?,?,?,?,?,?)",
                    "GATEWAY:"+identityId,policy.periodSeconds(),policy.monitored(),request.evidence(),request.expectedRevision()+1,actor,key);
            return jdbc.queryForMap("SELECT identity_id,gateway_key,gateway_revision AS revision FROM biz_device_comm_target WHERE identity_id=?",identityId);
        });
    }
    public Policy savePolicy(String key,PolicyRequest request,Long actor,Set<String> roles) {
        admin(roles);
        if(request.monitored()==null||request.periodSeconds()<5||request.periodSeconds()>86400||request.expectedRevision()<0
                ||request.evidence()==null||request.evidence().isBlank())throw invalid("通讯策略参数无效");
        if(!(key.equals("DEFAULT")||key.startsWith("PROFILE:")||key.startsWith("PRODUCT:"))||key.length()>150) throw invalid("策略范围无效");
        if(key.startsWith("PRODUCT:") && jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_product WHERE product_id=?",Integer.class,key.substring(8))!=1)throw invalid("策略产品不存在");
        if(key.startsWith("PROFILE:") && jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM biz_device_product WHERE expected_profile_code=?)+(SELECT COUNT(*) FROM biz_device_identity WHERE expected_profile_code=?)",Integer.class,key.substring(8),key.substring(8))==0)throw invalid("策略协议不存在");
        return transaction.execute(status->{
            List<Policy> old=policies().stream().filter(p->p.scopeKey().equals(key)).toList();
            long next=request.expectedRevision()+1;
            if(old.isEmpty()) {
                if(request.expectedRevision()!=0) throw conflict();
                try{jdbc.update("INSERT INTO biz_device_comm_policy(scope_key,period_seconds,monitored,evidence,revision,updated_by) VALUES(?,?,?,?,?,?)",key,request.periodSeconds(),request.monitored(),request.evidence(),next,actor);}
                catch(DuplicateKeyException raced){throw conflict();}
            } else if(jdbc.update("UPDATE biz_device_comm_policy SET period_seconds=?,monitored=?,evidence=?,revision=?,updated_by=? WHERE scope_key=? AND revision=?",request.periodSeconds(),request.monitored(),request.evidence(),next,actor,key,request.expectedRevision())!=1)throw conflict();
            jdbc.update("INSERT INTO biz_device_comm_policy_audit(scope_key,period_seconds,monitored,evidence,revision,changed_by) VALUES(?,?,?,?,?,?)",key,request.periodSeconds(),request.monitored(),request.evidence(),next,actor);
            return new Policy(key,request.periodSeconds(),request.monitored(),request.evidence(),next);
        });
    }

    public List<Transition> history(String identityId,int limit,Set<String> roles) {
        admin(roles);
        if(limit<1||limit>200)throw invalid("历史条数无效");
        return jdbc.query("SELECT * FROM biz_device_comm_event WHERE identity_id=? ORDER BY observed_at DESC,event_id DESC LIMIT ?",(rs,n)->new Transition(rs.getString("event_id"),rs.getString("identity_id"),rs.getString("building_id"),rs.getString("before_state"),rs.getString("after_state"),rs.getString("reason"),rs.getLong("observed_at"),rs.getLong("policy_revision")),identityId,limit);
    }

    private Scope scope(String id,boolean lock) {
        if(lock) jdbc.queryForList("SELECT identity_id FROM biz_device_identity WHERE identity_id=? FOR UPDATE",String.class,id);
        var scopes=jdbc.query(SCOPE_SQL,(rs,n)->{
            String source="DAIKIN_UNIT".equals(rs.getString("identity_type"))?"DAIKIN:"+rs.getString("source_id"):"MQTT";
            boolean eligible=rs.getInt("status")==1 && (rs.getString("product_id")==null||"ENABLED".equals(rs.getString("product_status")))
                    && (!source.startsWith("DAIKIN:")||rs.getObject("mapping_version")!=null);
            // 数据库版本戳按原文比较；不经 JVM 默认时区转换后再生成归属指纹。
            String stamp=rs.getString("identity_value")+":"+rs.getString("building_id")+":"+rs.getString("expected_profile_code")+":"+rs.getString("product_id")+":"+rs.getString("update_time")+":"+rs.getInt("mapping_version")+":"+source;
            return new Scope(id,rs.getString("equip_id"),rs.getString("building_id"),rs.getString("expected_profile_code"),rs.getString("product_id"),source,hash(stamp),eligible,rs.getString("identity_value"),rs.getTimestamp("update_time"),rs.getInt("mapping_version"));
        },id);
        return scopes.size()==1?scopes.getFirst():null;
    }
    private Policy policy(Scope s) {
        var all=policies();
        return all.stream().filter(p->s.product()!=null && p.scopeKey().equals("PRODUCT:"+s.product())).findFirst()
                .orElseGet(()->all.stream().filter(p->p.scopeKey().equals("PROFILE:"+s.profile())).findFirst()
                .orElseGet(()->all.stream().filter(p->p.scopeKey().equals("DEFAULT")).findFirst()
                .map(p->s.source().startsWith("DAIKIN:")?new Policy("DAIKIN_CONFIG",daikinPeriod,p.monitored(),p.evidence(),p.revision()):p)
                .orElse(new Policy("DEFAULT",properties.getDefaultPeriodSeconds(),true,"项目通讯默认策略",1))));
    }
    private Target ensure(Scope s,Policy p,long now) {
        var old=targets(s.identity());
        if(old.isEmpty()) {
            jdbc.update("""
                INSERT INTO biz_device_comm_target(identity_id,equip_id,building_id,source_key,scope_token,scope_profile,scope_product,scope_identity,identity_updated,
                  monitored,monitoring_since,period_seconds,policy_revision,mapping_revision,state,reason,last_contact_at,last_evidence_at,evidence_key,negative_at,good_count,alarm_open,report_delayed,revision)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'UNKNOWN','WAITING_INITIAL_CONTACT',0,0,'',0,0,0,0,1)
                """,s.identity(),s.equipment(),s.building(),s.source(),s.token(),s.profile(),s.product(),s.identityValue(),s.updated(),s.eligible()&&p.monitored(),now,p.periodSeconds(),p.revision(),s.mappingRevision());
        } else if(!s.token().equals(old.getFirst().token())) {
            jdbc.update("""
                UPDATE biz_device_comm_target SET equip_id=?,building_id=?,source_key=?,scope_token=?,scope_profile=?,scope_product=?,scope_identity=?,identity_updated=?,
                  monitored=?,monitoring_since=?,period_seconds=?,policy_revision=?,mapping_revision=?,state='UNKNOWN',reason='WAITING_INITIAL_CONTACT',
                  last_contact_at=0,last_evidence_at=0,evidence_key='',negative_at=0,good_count=0,alarm_open=0,report_delayed=0,gateway_key=NULL,gateway_revision=gateway_revision+1,revision=revision+1 WHERE identity_id=?
                """,s.equipment(),s.building(),s.source(),s.token(),s.profile(),s.product(),s.identityValue(),s.updated(),s.eligible()&&p.monitored(),now,p.periodSeconds(),p.revision(),s.mappingRevision(),s.identity());
        } else {
            if(!old.getFirst().monitored() && s.eligible() && p.monitored()) {
                // 重新纳入监测重新等待首包，不用维护前的旧读数制造刚启用即离线。
                jdbc.update("UPDATE biz_device_comm_target SET monitoring_since=?,last_contact_at=0,negative_at=0,good_count=0,alarm_open=0,state='UNKNOWN',reason='WAITING_INITIAL_CONTACT',report_delayed=0 WHERE identity_id=?",now,s.identity());
            }
            jdbc.update("UPDATE biz_device_comm_target SET monitored=?,period_seconds=?,policy_revision=? WHERE identity_id=?",s.eligible()&&p.monitored(),p.periodSeconds(),p.revision(),s.identity());
        }
        return targets(s.identity()).getFirst();
    }
    private List<Target> targets(String id) {
        return jdbc.query("SELECT * FROM biz_device_comm_target WHERE identity_id=?",(rs,n)->new Target(rs.getString("scope_token"),rs.getLong("monitoring_since"),rs.getLong("last_contact_at"),rs.getLong("last_evidence_at"),rs.getString("evidence_key"),rs.getLong("negative_at"),rs.getInt("good_count"),rs.getBoolean("alarm_open"),rs.getString("state"),rs.getString("gateway_key"),rs.getBoolean("monitored")),id);
    }
    private View waiting(Scope scope,Policy p) { return scope.eligible()&&p.monitored()?new View(true,"UNKNOWN","WAITING_INITIAL_CONTACT",scope.source(),null,"DEVICE",false,false,null,p.revision()):View.unmonitored(); }
    private View evaluate(Scope s,Policy p,Target t,long now) {
        if(!s.eligible()||!p.monitored())return View.unmonitored();
        if(!s.token().equals(t.token()))return new View(true,"UNKNOWN","STALE_SCOPE",s.source(),null,"DEVICE",false,false,null,p.revision());
        long deadline=(t.lastContact()>0?t.lastContact():t.since())+offlineMillis(p.periodSeconds());
        Long last=t.lastContact()>0?t.lastContact():null;
        if(t.gateway()!=null && sourceDown(t.gateway()))return new View(true,"OFFLINE","UPSTREAM_GATEWAY_OFFLINE",s.source(),last,"DEVICE",false,true,deadline,p.revision());
        if(t.negativeAt()>0 && t.negativeAt()>=t.lastContact())return new View(true,"OFFLINE","DEVICE_REPORTED_OFFLINE",s.source(),last,"DEVICE",false,true,deadline,p.revision());
        if(!sourceHealthy(s.source(),now))return new View(true,"UNKNOWN","PLATFORM_PIPELINE_UNAVAILABLE",s.source(),last,"DEVICE",false,t.alarm(),deadline,p.revision());
        if(now>=deadline)return new View(true,"OFFLINE","DEVICE_TIMEOUT",s.source(),last,"DEVICE",false,true,deadline,p.revision());
        if(last==null)return waiting(s,p);
        boolean delayed=now-last>=2L*p.periodSeconds()*1000+jitter(p.periodSeconds());
        return new View(true,"ONLINE",delayed?"REPORT_DELAYED":s.source().startsWith("DAIKIN:")?"LIVE_POLL":"LIVE_TELEMETRY",s.source(),last,"DEVICE",delayed,t.alarm(),deadline,p.revision());
    }
    private boolean sourceHealthy(String source,long now) { return jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_source WHERE source_key=? AND health='HEALTHY' AND lease_until>?",Integer.class,source,now)>0; }
    private boolean sourceDown(String source) { return jdbc.queryForObject("SELECT COUNT(*) FROM biz_device_comm_source WHERE source_key=? AND observer_id='GATEWAY' AND health='DOWN'",Integer.class,source)>0; }
    private void transition(Scope s,String before,String after,String reason,long now,long revision) {
        if(before.equals(after))return;
        jdbc.update("INSERT INTO biz_device_comm_event(event_id,identity_id,building_id,before_state,after_state,reason,observed_at,policy_revision) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),s.identity(),s.building(),before,after,reason,now,revision);
    }
    public static long jitter(int period) { return Math.max(15_000L,period*200L); }
    public static long offlineMillis(int period) { return period*3000L+jitter(period); }
    private static String hash(String text) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);} }
    private static BusinessException invalid(String message){return new BusinessException(400,"COMMUNICATION_VALIDATION_FAILED",message);}
    private static BusinessException conflict(){return new BusinessException(409,"COMMUNICATION_REVISION_CONFLICT","通讯策略已变更，请重新读取");}
    private static void admin(Set<String> roles){if(roles==null||!roles.contains("PLATFORM_ADMIN"))throw new BusinessException(403,"ASSET_FORBIDDEN","仅平台管理员可维护通讯策略");}
    private record Scope(String identity,String equipment,String building,String profile,String product,String source,String token,boolean eligible,String identityValue,java.sql.Timestamp updated,int mappingRevision) { }
    private record Target(String token,long since,long lastContact,long lastEvidenceAt,String evidenceKey,long negativeAt,int goodCount,boolean alarm,String state,String gateway,boolean monitored) { }
}
