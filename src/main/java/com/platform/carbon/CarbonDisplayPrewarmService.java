package com.platform.carbon;

import com.platform.energy.period.EnergyPeriodAuthorization;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import static com.platform.carbon.CarbonDisplayQueryContracts.*;

/** 主动登记默认期间，复用既有单通道重建；就绪只读检查，不计算、不审核、不修改建筑业务配置。 */
@Service
public class CarbonDisplayPrewarmService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final CarbonDisplayQueryProperties properties;
    private final CarbonDisplayTaskIdentity identity;
    private final EnergyPeriodAuthorization authorization;
    private final CarbonDisplayQueryService display;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @Autowired
    public CarbonDisplayPrewarmService(CarbonDisplayQueryProperties properties,CarbonDisplayTaskIdentity identity,
            EnergyPeriodAuthorization authorization,CarbonDisplayQueryService display,@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc){
        this(properties,identity,authorization,display,jdbc,Clock.systemUTC());
    }
    CarbonDisplayPrewarmService(CarbonDisplayQueryProperties properties,CarbonDisplayTaskIdentity identity,
            EnergyPeriodAuthorization authorization,CarbonDisplayQueryService display,JdbcTemplate jdbc,Clock clock){
        this.properties=properties;this.identity=identity;this.authorization=authorization;this.display=display;this.jdbc=jdbc;this.clock=clock;
    }
    public record Item(String code,String periodType,String period,String state,boolean ready,String businessState,String errorCode,String sourceVersion) { }
    public record Readiness(String buildingId,String scope,String timezoneId,Instant checkedAt,boolean configured,
                            boolean ready,String state,List<Item> items) { }
    record Plan(String code,Query query) { }

    void registerDefaults(){
        for(var target:targets()) {
            try {
                String building=resolveBuilding(target.buildingCode());
                var roles=identity.currentRoles(target.actorId());
                authorization.requireReader(roles);authorization.checkBuilding(target.actorId(),roles,building);
                for(var plan:plans(building)) {
                    if(plan.query()==null)continue;
                    try{display.prepare(target.actorId(),roles,plan.query());}
                    catch(RuntimeException e){logFailure(plan.code(),e);}
                }
            }catch(RuntimeException e){logFailure("TARGET",e);}
        }
    }
    public Readiness readiness(long user,Collection<String> roles,String building){
        authorization.requireReader(roles);authorization.checkBuilding(user,roles,building);
        CarbonDisplayQueryProperties.PrewarmTarget selected=null;
        try {
            for(var target:targets())if(resolveBuilding(target.buildingCode()).equals(building)){selected=target;break;}
        }catch(RuntimeException e){return result(building,false,"CONFIGURATION_ERROR",List.of());}
        if(!properties.isEnabled()||selected==null)return result(building,false,"NOT_CONFIGURED",List.of());
        String taskError=null;
        // 发布就绪还要求后台身份仍能维护这些结果；旧缓存可展示不代表撤权后的预热仍可持续运行。
        try{var taskRoles=identity.currentRoles(selected.actorId());authorization.checkBuilding(selected.actorId(),taskRoles,building);}
        catch(RuntimeException e){taskError=errorCode(e);}
        var items=new ArrayList<Item>();
        for(var plan:plans(building)) {
            if(taskError!=null){items.add(item(plan,"FAILED",false,null,taskError,null));continue;}
            if(plan.query()==null){items.add(new Item(plan.code(),"YEAR",String.valueOf(YearMonth.now(clock.withZone(ZONE)).getYear()),"NOT_APPLICABLE",false,"NO_CLOSED_MONTH","NO_CLOSED_MONTH",null));continue;}
            Query q=plan.query();
            try {
                View view=display.inspect(user,roles,q);
                if(!"READY".equals(view.state()))items.add(item(plan,view.state(),false,null,null,view.sourceVersion()));
                else {
                    String business=businessState(view.data(),q);
                    boolean ready=Set.of("AVAILABLE","AVAILABLE_WITH_WARNINGS").contains(business);
                    items.add(item(plan,"READY",ready,business,ready?null:business,view.sourceVersion()));
                }
            }catch(RuntimeException e){items.add(item(plan,"FAILED",false,null,errorCode(e),null));}
        }
        String state=items.stream().allMatch(Item::ready)?"READY":items.stream().allMatch(i->i.ready()||"PREPARING".equals(i.state()))?"PREPARING":"NOT_READY";
        return result(building,true,state,List.copyOf(items));
    }
    private Readiness result(String building,boolean configured,String state,List<Item> items){
        return new Readiness(building,"DEFAULT_FLOW_AND_INTENSITY_ONLY",ZONE.getId(),clock.instant(),configured,
                configured&&"READY".equals(state),state,items);
    }
    private Item item(Plan plan,String state,boolean ready,String business,String error,String version){
        Query q=plan.query();if(q==null)return new Item(plan.code(),"YEAR",String.valueOf(YearMonth.now(clock.withZone(ZONE)).getYear()),state,ready,business,error,version);
        String period=q.period()!=null?q.period():q.mode().equals("MONTH")?YearMonth.of(q.year(),q.month()).toString():q.year()+"-through-"+String.format(Locale.ROOT,"%02d",q.month());
        return new Item(plan.code(),q.mode(),period,state,ready,business,error,version);
    }
    List<Plan> plans(String building){
        YearMonth current=YearMonth.now(clock.withZone(ZONE)),previous=current.minusMonths(1);int year=current.getYear();
        // 与B版一致：当年累计截止最近结束月；1月没有本年结束月，不把去年数据冒充本年累计。
        Query annualFlow=previous.getYear()==year?CarbonDisplayQueryContracts.flow(new JointFlowContracts.Request(building,"YEAR",String.valueOf(year),previous.toString(),null)):null;
        Query annualIntensity=previous.getYear()==year?CarbonDisplayQueryContracts.intensity(building,"MONTHLY_YTD",year,previous.getMonthValue(),ZONE.getId()):null;
        return List.of(new Plan("FLOW_YEAR",annualFlow),new Plan("INTENSITY_YTD",annualIntensity),
                new Plan("FLOW_MONTH",CarbonDisplayQueryContracts.flow(new JointFlowContracts.Request(building,"MONTH",previous.toString(),null,null))),
                new Plan("INTENSITY_MONTH",CarbonDisplayQueryContracts.intensity(building,"MONTH",previous.getYear(),previous.getMonthValue(),ZONE.getId())));
    }
    private List<CarbonDisplayQueryProperties.PrewarmTarget> targets(){
        var values=properties.getPrewarmTargets();if(values==null)return List.of();
        if(values.size()>20)throw invalid();
        var active=new ArrayList<CarbonDisplayQueryProperties.PrewarmTarget>();var codes=new HashSet<String>();
        for(var target:values){
            if(target==null)throw invalid();String code=target.buildingCode();
            if((code==null||code.isBlank())&&target.actorId()==0)continue;
            if(code==null||code.isBlank()||code.length()>80||target.actorId()<=0||!codes.add(code))throw invalid();
            active.add(target);
        }
        return active;
    }
    private String resolveBuilding(String code){
        var ids=jdbc.queryForList("SELECT building_id FROM building WHERE building_code=? AND del_flag=0 ORDER BY building_id LIMIT 2",String.class,code);
        if(ids.size()!=1)throw new BusinessException(409,"CARBON_DISPLAY_TARGET_AMBIGUOUS","大屏建筑编码不存在或不唯一");
        return ids.getFirst();
    }
    private static String businessState(Object data,Query query){
        if(data instanceof JointFlowContracts.View v){
            if(!"CONFIGURED_VIEW".equals(v.classificationBasis())||v.missingMonths()==null||!v.missingMonths().isEmpty())return "FLOW_SOURCE_INCOMPLETE";
            if(!renderable(v.carbon(),"tCO2e")||!renderable(v.energy(),"tce"))return "FLOW_GRAPH_UNAVAILABLE";
            return "PARTIAL".equals(v.carbon().state())||"PARTIAL".equals(v.energy().state())?"AVAILABLE_WITH_WARNINGS":"AVAILABLE";
        }
        if(data instanceof CarbonIntensityContracts.View v){
            if(v.actual()==null||!"AVAILABLE".equals(v.actual().status())||v.actual().value()==null||v.actual().value().signum()<0
                    ||!Set.of("kgCO2e/m²","kgCO2/m²").contains(Objects.toString(v.actual().unit(),"")))return "ACTUAL_UNAVAILABLE";
            var ref=v.reference();
            if(ref==null||ref.rule()==null||ref.rule().constraintValue()==null||ref.rule().baselineValue()==null||ref.rule().guideValue()==null)return "REFERENCE_UNAVAILABLE";
            // 月度原契约不计算全年参考比例；YTD不年化，不以正式全年可比性阻断已确认的参考展示。
            if(!"MONTH".equals(query.mode())&&(!"AVAILABLE".equals(v.percentageStatus())||v.percentage()==null))return "REFERENCE_PERCENTAGE_UNAVAILABLE";
            return "AVAILABLE";
        }
        return "RESULT_INVALID";
    }
    private static boolean renderable(JointFlowContracts.Graph graph,String unit){
        return graph!=null&&unit.equals(graph.unit())&&Set.of("READY","PARTIAL").contains(graph.state())&&graph.total()!=null&&graph.total().signum()>=0
                &&graph.nodes()!=null&&!graph.nodes().isEmpty()&&graph.links()!=null&&!graph.links().isEmpty();
    }
    private static BusinessException invalid(){return new BusinessException(503,"CARBON_DISPLAY_PREWARM_CONFIGURATION","大屏预热部署参数无效");}
    private static String errorCode(RuntimeException e){return e instanceof BusinessException b&&b.getErrorCode()!=null?b.getErrorCode():"CARBON_DISPLAY_PREWARM_FAILED";}
    private static void logFailure(String stage,RuntimeException e){LoggerFactory.getLogger(CarbonDisplayPrewarmService.class).warn("Carbon display prewarm unavailable: stage={}, code={}",stage,errorCode(e));}
}
