package com.platform.weather;

import com.platform.framework.exception.BusinessException;
import com.platform.framework.web.PageResponse;
import com.platform.system.service.BuildingScopeService;
import com.platform.system.service.SysUserService;
import com.platform.system.mapper.SysRoleMapper;
import com.platform.energy.aggregation.EnergyAggregationAuthorization;
import com.platform.weather.source.WeatherSourceModels.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.platform.weather.WeatherModels.*;

/** 统一天气建筑权限、查询和命令入队；查询绝不访问第三方或触发用电计算。 */
@Service
public class WeatherService {
    private final WeatherRepository repo;
    private final BuildingScopeService scope;
    private final EnergyAggregationAuthorization energyAuth;
    private final com.platform.weather.energy.DailyElectricityAdapter energy;
    private final SysUserService users;
    private final SysRoleMapper rolesMapper;
    public WeatherService(WeatherRepository repo,BuildingScopeService scope,EnergyAggregationAuthorization energyAuth,
            SysUserService users,SysRoleMapper rolesMapper,com.platform.weather.energy.DailyElectricityAdapter energy) {
        this.repo=repo;this.scope=scope;this.energyAuth=energyAuth;this.users=users;this.rolesMapper=rolesMapper;this.energy=energy;
    }
    public List<String> liveRoles(long actor) {
        var user=users.getById(actor);
        if(user==null || !Integer.valueOf(1).equals(user.getStatus()) || Integer.valueOf(1).equals(user.getActivationPending()))throw new BusinessException(403,"AUTHORIZATION_CHANGED","执行账号不可用");
        return rolesMapper.selectRoleKeysByUserId(actor);
    }
    public void reader(long user,Collection<String> roles,String building) {
        energyAuth.requireReader(roles);scope.checkAccess(user,roles,building);
    }
    public void admin(long user,Collection<String> roles,String building) {
        reader(user,roles,building);
        if(!roles.contains("PLATFORM_ADMIN"))throw new BusinessException(403,"WEATHER_ADMIN_REQUIRED","仅管理员可维护天气配置");
    }
    public void runner(long user,Collection<String> roles,String building) {
        energyAuth.requireRunner(user,roles);scope.checkAccess(user,roles,building);
    }
    static void range(LocalDate start,LocalDate end,int max) {
        if(start==null || end==null || start.isBefore(HISTORY_START) || end.isBefore(start) || ChronoUnit.DAYS.between(start,end)>=max)
            throw new BusinessException(400,"INVALID_DATE_RANGE","日期范围无效或超过限制");
    }
    static void key(String key) {
        if(key==null || key.isBlank() || key.length()>100)throw new BusinessException(400,"INVALID_IDEMPOTENCY_KEY","需要有效的幂等键");
    }
    public Binding saveBinding(long actor,Collection<String> roles,String building,String key,BindingRequest request) {
        admin(actor,roles,building);key(key);
        if(request.latitude()==null || request.longitude()==null || !Double.isFinite(request.latitude()) || !Double.isFinite(request.longitude()) || request.effectiveFrom()==null
                || request.effectiveFrom().isBefore(HISTORY_START.atStartOfDay(ZONE).toInstant()))
            throw new BusinessException(400,"INVALID_LOCATION","位置或生效日期无效");
        // 位置版本以完整业务日切换，避免一个日对照混合两个位置。
        if(!request.effectiveFrom().atZone(ZONE).toLocalTime().equals(LocalTime.MIDNIGHT))
            throw new BusinessException(400,"INVALID_LOCATION_BOUNDARY","位置生效时间必须为北京时间零点");
        return repo.saveBinding(building,actor,key,request);
    }
    public PageResponse<Binding> locations(long user,Collection<String> roles,int page,int size) {
        energyAuth.requireReader(roles);paging(page,size);
        Set<String> allowed=scope.getAccessibleBuildingIds(user,roles);
        return repo.locations(allowed,page,size,Instant.now());
    }
    static void paging(int page,int size) { if(page<1||page>1000000||size<1||size>100)throw new BusinessException(400,"INVALID_PAGE","分页参数无效"); }
    public List<JobView> submit(long user,Collection<String> roles,String key,FetchRequest req) {
        admin(user,roles,req.buildingId());key(key);range(req.start(),req.end(),3660);
        return repo.command(user,"weather:"+key,req,()->submitWeatherJobs(user,key,req));
    }
    private List<JobView> submitWeatherJobs(long user,String key,FetchRequest req) {
        if(req.source()==Source.CHINA_WEATHER && req.product()!=Product.FORECAST_DAILY)
            throw new BusinessException(400,"UNSUPPORTED_SOURCE_PRODUCT","中国天气网仅支持日预报");
        boolean history=req.product()==Product.HISTORY_HOURLY || req.product()==Product.HISTORY_DAILY;
        LocalDate today=LocalDate.now(ZONE);
        if(history && !req.end().isBefore(today))throw new BusinessException(400,"HISTORY_NOT_ENDED","历史任务只处理已结束日期");
        if(!history && (!req.start().equals(today)|| !req.end().equals(req.product()==Product.CURRENT?today:today.plusDays(6))))
            throw new BusinessException(400,"INVALID_FORECAST_RANGE","预报范围必须是今天及未来六天");
        List<JobView> result=new ArrayList<>();
        // 月份和位置版本分别拆分；整段先校验再入队，避免中途配置缺失留下半套命令。
        List<Job> jobs=new ArrayList<>();
        for(LocalDate from=req.start(); !from.isAfter(req.end());) {
            Binding b=repo.effective(req.buildingId(),from.atStartOfDay(ZONE).toInstant());
            if(b==null || !b.enabled())throw new BusinessException(409,"LOCATION_NOT_CONFIGURED","范围内没有启用的位置配置");
            if(req.source()==Source.CHINA_WEATHER && b.cityCode()==null)throw new BusinessException(409,"REGION_NOT_CONFIGURED","尚未确认区县或城市映射");
            LocalDate to=history?from.withDayOfMonth(from.lengthOfMonth()):req.end();
            if(to.isAfter(req.end()))to=req.end();
            if(history && b.effectiveTo()!=null) {
                LocalDate last=b.effectiveTo().atZone(ZONE).toLocalDate().minusDays(1);
                if(to.isAfter(last))to=last;
            }
            jobs.add(newJob("WEATHER",req.buildingId(),b.id(),req.source(),req.product(),from,to,null,null,user));
            from=to.plusDays(1);
        }
        return repo.transaction(()->{
            for(Job j:jobs)result.add(JobView.of(repo.enqueue(j,"manual:"+key+":"+j.start()+":"+j.bindingId(),List.of(req,j.bindingId(),j.start(),j.end()))));
            return result;
        });
    }
    static Job newJob(String kind,String building,String binding,Source source,Product product,LocalDate from,LocalDate to,String system,String point,long actor) {
        return new Job(WeatherRepository.id(),kind,building,binding,source,product,from,to,system,point,actor,"QUEUED",0,0,0,0,null,null,System.currentTimeMillis());
    }
    public List<JobView> submitEnergy(long user,Collection<String> roles,String building,String key,EnergyRequest req) {
        runner(user,roles,building);key(key);range(req.start(),req.end(),366);
        validateEnergyObject(user,roles,building,req.systemId(),req.pointId());
        if(!req.end().isBefore(LocalDate.now(ZONE)))throw new BusinessException(400,"DAY_NOT_ENDED","日量仅计算已结束日期");
        Job j=newJob("ENERGY",building,null,null,null,req.start().isAfter(HISTORY_START)?req.start().minusDays(1):req.start(),req.end().plusDays(1).isBefore(LocalDate.now(ZONE))?req.end().plusDays(1):req.end(),req.systemId(),req.pointId(),user);
        return List.of(JobView.of(repo.enqueue(j,"energy:"+key,List.of(building,req))));
    }
    public JobView job(long user,Collection<String> roles,String id) {
        Job j=repo.job(id);if(j==null)throw new BusinessException(404,"JOB_NOT_FOUND","任务不存在");
        reader(user,roles,j.buildingId());return JobView.of(j);
    }
    public PageResponse<JobView> jobs(long user,Collection<String> roles,String building,int page,int size) {
        reader(user,roles,building);paging(page,size);
        return new PageResponse<>(page,size,repo.jobCount(building),repo.jobs(building,size,(page-1)*size).stream().map(JobView::of).toList());
    }
    public void validateEnergyObject(long user,Collection<String> roles,String building,String system,String point) {
        reader(user,roles,building);
        var result=energy.validateObject(user,roles,building,system,point);
        if(!result.valid())throw new BusinessException(409,"METERING_OBJECT_NOT_ELIGIBLE","计量对象归属或口径未确认");
    }
    public void authorizeJob(Job j) {
        var roles=liveRoles(j.actorId());
        if(j.kind().equals("ENERGY"))runner(j.actorId(),roles,j.buildingId());else admin(j.actorId(),roles,j.buildingId());
    }
}
