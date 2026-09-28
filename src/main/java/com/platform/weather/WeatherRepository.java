package com.platform.weather;

import com.platform.framework.exception.BusinessException;
import com.platform.weather.source.WeatherSourceModels.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.platform.weather.WeatherModels.*;

/** MySQL 保存位置、任务和发布指针；在途载荷不对业务查询开放。 */
@Repository
public class WeatherRepository {
    private final JdbcTemplate jdbc;
    private final WeatherCodec codec;
    private final WeatherAudit audit;
    private final TransactionTemplate tx;
    public WeatherRepository(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,WeatherCodec codec,WeatherAudit audit) {
        this.jdbc=jdbc; this.codec=codec;this.audit=audit;
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public static String id() { return UUID.randomUUID().toString().replace("-",""); }
    public <T> T transaction(Supplier<T> fn) { return tx.execute(s->fn.get()); }
    private <T> T one(String sql,RowMapper<T> map,Object...args) {
        var rows=jdbc.query(sql,map,args);return rows.isEmpty()?null:rows.getFirst();
    }
    private Binding mapBinding(ResultSet r,int n)throws SQLException {
        Long end=(Long)r.getObject("effective_to");
        return new Binding(r.getString("id"),r.getString("building_id"),r.getLong("version_no"),
                r.getString("name"),r.getDouble("latitude"),r.getDouble("longitude"),r.getString("city_code"),
                r.getString("coverage"),Instant.ofEpochMilli(r.getLong("effective_from")),
                end==null?null:Instant.ofEpochMilli(end),r.getBoolean("enabled"));
    }
    public Binding binding(String id) { return one("SELECT * FROM biz_weather_binding_version WHERE id=?",this::mapBinding,id); }
    public Binding effective(String building,Instant at) {
        return one("SELECT * FROM biz_weather_binding_version WHERE building_id=? AND effective_from<=? AND (effective_to IS NULL OR effective_to>?) ORDER BY version_no DESC LIMIT 1",
                this::mapBinding,building,at.toEpochMilli(),at.toEpochMilli());
    }
    public List<Binding> bindings() {
        return jdbc.query("SELECT * FROM biz_weather_binding_version ORDER BY building_id,version_no",this::mapBinding);
    }
    public com.platform.framework.web.PageResponse<Binding> locations(Set<String> allowed,int page,int size,Instant now) {
        if(allowed!=null&&allowed.isEmpty())return new com.platform.framework.web.PageResponse<>(page,size,0,List.of());
        List<Object> args=new ArrayList<>(List.of(now.toEpochMilli(),now.toEpochMilli()));
        String where=" WHERE effective_from<=? AND (effective_to IS NULL OR effective_to>?)";
        if(allowed!=null) {
            where+=" AND building_id IN ("+String.join(",",Collections.nCopies(allowed.size(),"?"))+")";
            args.addAll(allowed);
        }
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM biz_weather_binding_version"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var items=jdbc.query("SELECT * FROM biz_weather_binding_version"+where+" ORDER BY building_id LIMIT ? OFFSET ?",this::mapBinding,args.toArray());
        return new com.platform.framework.web.PageResponse<>(page,size,count==null?0:count,items);
    }
    /** 建筑元数据行充当位置写锁，避免首次配置竞态与生效区间重叠。 */
    public Binding saveBinding(String building,long actor,String key,BindingRequest req) {
        return transaction(()-> {
            var exists=jdbc.queryForList("SELECT building_id FROM building WHERE building_id=? FOR UPDATE",String.class,building);
            if(exists.isEmpty()) throw new BusinessException(404,"BUILDING_NOT_FOUND","建筑不存在");
            var repeat=jdbc.queryForList("SELECT id,request_hash FROM biz_weather_binding_version WHERE building_id=? AND idempotency_key=?",building,key);
            String hash=codec.hash(req);
            if(!repeat.isEmpty()) {
                if(!hash.equals(repeat.getFirst().get("request_hash"))) throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","请求键已用于不同内容");
                return binding((String)repeat.getFirst().get("id"));
            }
            Long version=one("SELECT current_version FROM biz_weather_location WHERE building_id=?",(r,n)->r.getLong(1),building);
            long current=version==null?0:version;
            if(req.expectedVersion()!=current) throw new BusinessException(409,"VERSION_CONFLICT","位置配置已变化");
            Binding last=one("SELECT * FROM biz_weather_binding_version WHERE building_id=? ORDER BY version_no DESC LIMIT 1",this::mapBinding,building);
            if(last!=null && !req.effectiveFrom().isAfter(last.effectiveFrom())) throw new BusinessException(409,"EFFECTIVE_RANGE_CONFLICT","新位置必须晚于当前版本生效");
            if(version==null) jdbc.update("INSERT INTO biz_weather_location(building_id,current_version) VALUES(?,?)",building,1);
            else {
                jdbc.update("UPDATE biz_weather_location SET current_version=? WHERE building_id=?",current+1,building);
                jdbc.update("UPDATE biz_weather_binding_version SET effective_to=? WHERE id=?",req.effectiveFrom().toEpochMilli(),last.id());
            }
            String id=id();
            jdbc.update("INSERT INTO biz_weather_binding_version(id,building_id,version_no,name,latitude,longitude,city_code,coverage,effective_from,enabled,actor_id,idempotency_key,request_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,building,current+1,req.name(),req.latitude(),req.longitude(),req.cityCode(),req.coverage(),req.effectiveFrom().toEpochMilli(),req.enabled(),actor,key,hash);
            audit.record(actor,building,"WEATHER_BINDING_CREATED",id,"SUCCEEDED",null);
            return binding(id);
        });
    }
    private Job mapJob(ResultSet r,int n)throws SQLException {
        return new Job(r.getString("id"),r.getString("kind"),r.getString("building_id"),r.getString("binding_id"),
                r.getString("source")==null?null:Source.valueOf(r.getString("source")),r.getString("product")==null?null:Product.valueOf(r.getString("product")),
                r.getDate("start_day").toLocalDate(),r.getDate("end_day").toLocalDate(),r.getString("system_id"),r.getString("point_id"),
                r.getLong("actor_id"),r.getString("state"),r.getInt("attempts"),r.getLong("fence"),r.getLong("lease_until"),
                r.getLong("next_attempt"),r.getString("dataset_id"),r.getString("reason"),r.getLong("created_at"));
    }
    public Job job(String id) { return one("SELECT * FROM biz_weather_job WHERE id=?",this::mapJob,id); }
    /** 整个补采请求共用幂等键，不能因拆分月份或位置版本而绕过同键内容冲突。 */
    public List<JobView> command(long actor,String key,Object request,Supplier<List<JobView>> action) {
        return transaction(()->{
            String hash=codec.hash(request);
            try { jdbc.update("INSERT INTO biz_weather_command(actor_id,idempotency_key,request_hash) VALUES(?,?,?)",actor,key,hash); }
            catch(org.springframework.dao.DuplicateKeyException ignored) { /* 后续持有行锁核对同键内容。 */ }
            var row=jdbc.queryForMap("SELECT request_hash,result_json FROM biz_weather_command WHERE actor_id=? AND idempotency_key=? FOR UPDATE",actor,key);
            if(!hash.equals(row.get("request_hash")))throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","请求键已用于不同内容");
            if(row.get("result_json")!=null) {
                var views=codec.read((String)row.get("result_json"),JobView[].class);
                return Arrays.stream(views).map(v->JobView.of(job(v.id()))).toList();
            }
            var result=action.get();
            jdbc.update("UPDATE biz_weather_command SET result_json=? WHERE actor_id=? AND idempotency_key=?",codec.json(result),actor,key);
            return result;
        });
    }
    public Job enqueue(Job j,String key,Object command) {
        String hash=codec.hash(command);
        try {
            jdbc.update("INSERT INTO biz_weather_job(id,kind,building_id,binding_id,source,product,start_day,end_day,system_id,point_id,actor_id,idempotency_key,request_hash,state,next_attempt,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED',?,?)",
                    j.id(),j.kind(),j.buildingId(),j.bindingId(),j.source()==null?null:j.source().name(),j.product()==null?null:j.product().name(),
                    j.start(),j.end(),j.systemId(),j.pointId(),j.actorId(),key,hash,j.createdAt(),j.createdAt());
            return job(j.id());
        } catch(org.springframework.dao.DuplicateKeyException e) {
            var existing=one("SELECT * FROM biz_weather_job WHERE actor_id=? AND idempotency_key=?",this::mapJob,j.actorId(),key);
            if(existing==null) throw e;
            String stored=jdbc.queryForObject("SELECT request_hash FROM biz_weather_job WHERE id=?",String.class,existing.id());
            if(!hash.equals(stored)) throw new BusinessException(409,"IDEMPOTENCY_CONFLICT","请求键已用于不同内容");
            return existing;
        }
    }
    public List<Job> jobs(String building,int limit,int offset) {
        return jdbc.query("SELECT * FROM biz_weather_job WHERE building_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::mapJob,building,limit,offset);
    }
    public long jobCount(String building) { return jdbc.queryForObject("SELECT COUNT(*) FROM biz_weather_job WHERE building_id=?",Long.class,building); }
    /** 领取短事务结束后才访问源站；SKIP LOCKED 防多个节点重复领取。 */
    public Job claim(String lane,long now) {
        return transaction(()->{
            var gates=jdbc.queryForList("SELECT * FROM biz_weather_lane WHERE lane=? FOR UPDATE",lane);
            if(gates.isEmpty())throw new IllegalStateException("WEATHER_LANE_MISSING");
            var gate=gates.getFirst();
            if(((Number)gate.get("lease_until")).longValue()>now || ((Number)gate.get("next_allowed")).longValue()>now)return null;
            Job j=one("SELECT * FROM biz_weather_job WHERE "+(lane.equals("ENERGY")?"kind='ENERGY'":"source=?")+
                    " AND ((state IN ('QUEUED','RETRY_WAIT') AND next_attempt<=?) OR (state='RUNNING' AND lease_until<?)) ORDER BY CASE WHEN product IN ('CURRENT','FORECAST_DAILY','FORECAST_HOURLY') THEN 0 ELSE 1 END,start_day DESC,created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                    this::mapJob,lane.equals("ENERGY")?new Object[]{now,now}:new Object[]{lane,now,now});
            if(j==null)return null;
            jdbc.update("UPDATE biz_weather_job SET state='RUNNING',attempts=attempts+1,fence=fence+1,lease_until=? WHERE id=?",now+180000,j.id());
            jdbc.update("UPDATE biz_weather_lane SET job_id=?,lease_until=?,next_allowed=? WHERE lane=?",j.id(),now+180000,now+2000,lane);
            return job(j.id());
        });
    }
    public boolean renew(Job j,long now) {
        return transaction(()->{
            jdbc.queryForList("SELECT lane FROM biz_weather_lane WHERE lane=? FOR UPDATE",j.kind().equals("ENERGY")?"ENERGY":j.source().name());
            boolean ok=jdbc.update("UPDATE biz_weather_job SET lease_until=? WHERE id=? AND fence=? AND state='RUNNING' AND lease_until>=?",now+180000,j.id(),j.fence(),now)==1;
            if(ok)jdbc.update("UPDATE biz_weather_lane SET lease_until=? WHERE job_id=?",now+180000,j.id());
            return ok;
        });
    }
    private void requireLease(Job j) {
        jdbc.queryForList("SELECT lane FROM biz_weather_lane WHERE lane=? FOR UPDATE",j.kind().equals("ENERGY")?"ENERGY":j.source().name());
        Job live=one("SELECT * FROM biz_weather_job WHERE id=? FOR UPDATE",this::mapJob,j.id());
        if(live==null || !live.state().equals("RUNNING") || live.fence()!=j.fence() || live.leaseUntil()<System.currentTimeMillis())
            throw new IllegalStateException("LEASE_LOST");
    }
    public Dataset dataset(String id) {
        if(id==null)return null;
        return one("SELECT * FROM biz_weather_dataset WHERE id=?",(r,n)->new Dataset(r.getString("id"),r.getString("binding_id"),
                Source.valueOf(r.getString("source")),Product.valueOf(r.getString("product")),Instant.ofEpochMilli(r.getLong("fetched_at")),
                r.getString("state"),r.getString("table_name"),codec.unpack(r.getBytes("payload"),FetchResult.class)),id);
    }
    public Dataset prepare(Job j,FetchResult result) {
        return transaction(()-> {
            requireLease(j);
            Job live=job(j.id());
            if(live.datasetId()!=null)return dataset(live.datasetId());
            String id=id();
            jdbc.update("INSERT INTO biz_weather_dataset(id,binding_id,source,product,fetched_at,content_hash,state,table_name,payload,row_count,created_at) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?,?)",
                    id,j.bindingId(),j.source().name(),j.product().name(),result.fetchedAt().toEpochMilli(),codec.hash(result),j.product()==Product.CURRENT?"wc_"+j.bindingId()+"_"+result.fetchedAt().atZone(ZONE).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")):"w_"+id,codec.pack(result),result.samples().size(),System.currentTimeMillis());
            jdbc.update("UPDATE biz_weather_job SET dataset_id=? WHERE id=?",id,j.id());
            return dataset(id);
        });
    }
    public void publish(Job j,Dataset data) {
        transaction(()->{
            requireLease(j);
            // 日期指针按抓取时刻单调推进；旧任务即使迟写 TDengine 也不能回退在线版本。
            jdbc.update("UPDATE biz_weather_dataset SET state='PUBLISHED' WHERE id=?",data.id());
            var days=data.result().samples().stream().map(x->x.time().atZone(ZONE).toLocalDate()).distinct().toList();
            for(var day:days) {
                var head=jdbc.queryForList("SELECT fetched_at,dataset_id FROM biz_weather_dataset_head WHERE binding_id=? AND source=? AND product=? AND business_day=? FOR UPDATE",data.bindingId(),data.source().name(),data.product().name(),day);
                if(!head.isEmpty()) {
                    Dataset old=dataset((String)head.getFirst().get("dataset_id"));
                    if(old!=null && coverage(old,day)>coverage(data,day))continue;
                }
                if(head.isEmpty()) jdbc.update("INSERT INTO biz_weather_dataset_head VALUES(?,?,?,?,?,?)",data.bindingId(),data.source().name(),data.product().name(),day,data.id(),data.fetchedAt().toEpochMilli());
                else if(((Number)head.getFirst().get("fetched_at")).longValue()<=data.fetchedAt().toEpochMilli())
                    jdbc.update("UPDATE biz_weather_dataset_head SET dataset_id=?,fetched_at=? WHERE binding_id=? AND source=? AND product=? AND business_day=?",data.id(),data.fetchedAt().toEpochMilli(),data.bindingId(),data.source().name(),data.product().name(),day);
            }
            finish(j,"SUCCEEDED",null);return null;
        });
    }
    private static long coverage(Dataset d,LocalDate day) {
        return d.result().samples().stream().filter(s->s.time().atZone(ZONE).toLocalDate().equals(day))
                .mapToLong(s->s.values().values().stream().filter(Objects::nonNull).count()).sum();
    }
    public Dataset head(String binding,Source source,Product product,LocalDate day) {
        String id=one("SELECT dataset_id FROM biz_weather_dataset_head WHERE binding_id=? AND source=? AND product=? AND business_day=?",(r,n)->r.getString(1),binding,source.name(),product.name(),day);
        return dataset(id);
    }
    public List<Dataset> latest(String binding,Source source,Product product) {
        var ids=jdbc.queryForList("SELECT id FROM biz_weather_dataset WHERE binding_id=? AND source=? AND product=? AND state='PUBLISHED' ORDER BY fetched_at DESC LIMIT 8",String.class,binding,source.name(),product.name());
        return ids.stream().map(this::dataset).toList();
    }
    public Job latestJob(String binding,Source source,Product product) {
        return one("SELECT * FROM biz_weather_job WHERE binding_id=? AND source=? AND product=? AND state IN ('SUCCEEDED','FAILED','BLOCKED','RETRY_WAIT') ORDER BY COALESCE((SELECT MAX(b.fetched_at) FROM biz_weather_batch b WHERE b.job_id=biz_weather_job.id),created_at) DESC LIMIT 1",this::mapJob,binding,source.name(),product.name());
    }
    public void finish(Job j,String state,String reason) { finish(j,state,reason,0); }
    public void finish(Job j,String state,String reason,long retryAfterMillis) {
        transaction(()->{
            requireLease(j);
            long now=System.currentTimeMillis();
            long delay=Math.max(j.attempts()==1?30000:120000,retryAfterMillis);
            long next=delay>Long.MAX_VALUE-now?Long.MAX_VALUE:now+delay;
            jdbc.update("UPDATE biz_weather_job SET state=?,reason=?,next_attempt=?,lease_until=0 WHERE id=? AND fence=?",state,reason,next,j.id(),j.fence());
            jdbc.update("INSERT INTO biz_weather_batch(id,job_id,attempt_no,fetched_at,state,reason,dataset_id) VALUES(?,?,?,?,?,?,?)",
                    id(),j.id(),j.attempts(),System.currentTimeMillis(),state,reason,job(j.id()).datasetId());
            audit.record(j.actorId(),j.buildingId(),j.kind().equals("ENERGY")?"DAILY_ENERGY_CALCULATED":"WEATHER_ACQUIRED",j.id(),state,reason);
            jdbc.update("UPDATE biz_weather_lane SET job_id=NULL,lease_until=0,next_allowed=? WHERE job_id=?",retryAfterMillis>0?next:System.currentTimeMillis()+2000,j.id());
            return null;
        });
    }
    public void saveEnergy(Job j,Map<LocalDate,?> results) {
        transaction(()->{
            requireLease(j);
            // 批次外已有日结果若使用不同的共享日界，本批不能单侧更新；要求扩大补算范围。
            if(!results.isEmpty()) {
                LocalDate first=Collections.min(results.keySet()),last=Collections.max(results.keySet());
                checkAdjacent(j,first.minusDays(1),results.get(first),true);
                checkAdjacent(j,last.plusDays(1),results.get(last),false);
            }
            for(var e:results.entrySet()) {
                // 输入已清理时保留已有日结果，不能用一次空查询覆盖已完成的计算。
                if(e.getValue() instanceof com.platform.weather.energy.DailyElectricityAdapter.DailyResult result
                        && result.energyKwh()==null && result.reasons().stream().anyMatch(r->r.equals("RAW_HISTORY_UNAVAILABLE")||r.equals("RAW_HISTORY_EMPTY"))) {
                    Integer existing=jdbc.queryForObject("SELECT COUNT(*) FROM biz_energy_daily_comparison_result WHERE building_id=? AND system_id=? AND point_id=? AND business_day=?",Integer.class,j.buildingId(),j.systemId(),j.pointId(),e.getKey());
                    if(existing!=null&&existing>0) {
                        String stored=jdbc.queryForObject("SELECT result_json FROM biz_energy_daily_comparison_result WHERE building_id=? AND system_id=? AND point_id=? AND business_day=?",String.class,j.buildingId(),j.systemId(),j.pointId(),e.getKey());
                        @SuppressWarnings("unchecked") Map<String,Object> previous=new LinkedHashMap<>(codec.read(stored,Map.class));
                        List<String> reasons=new ArrayList<>();
                        Object existingReasons=previous.get("reasons");
                        if(existingReasons instanceof List<?> items)items.forEach(item->reasons.add(String.valueOf(item)));
                        if(!reasons.contains("RECALCULATION_RAW_HISTORY_UNAVAILABLE"))reasons.add("RECALCULATION_RAW_HISTORY_UNAVAILABLE");
                        previous.put("reasons",reasons);
                        jdbc.update("UPDATE biz_energy_daily_comparison_result SET result_json=? WHERE building_id=? AND system_id=? AND point_id=? AND business_day=?",codec.json(previous),j.buildingId(),j.systemId(),j.pointId(),e.getKey());
                        continue;
                    }
                }
                jdbc.update("DELETE FROM biz_energy_daily_comparison_result WHERE building_id=? AND system_id=? AND point_id=? AND business_day=?",j.buildingId(),j.systemId(),j.pointId(),e.getKey());
                jdbc.update("INSERT INTO biz_energy_daily_comparison_result VALUES(?,?,?,?,?,?)",j.buildingId(),j.systemId(),j.pointId(),e.getKey(),codec.json(e.getValue()),System.currentTimeMillis());
            }
            finish(j,"SUCCEEDED",null);return null;
        });
    }
    private void checkAdjacent(Job job,LocalDate outside,Object value,boolean before) {
        if(!(value instanceof com.platform.weather.energy.DailyElectricityAdapter.DailyResult result)||result.energyKwh()==null)return;
        var neighbor=energy(job.buildingId(),job.systemId(),job.pointId(),outside,outside).get(outside);
        if(neighbor==null||neighbor.energyKwh()==null||!Objects.equals(result.relationVersion(),neighbor.relationVersion()))return;
        Long boundary=before?result.startTime():result.endTime();
        Long adjacent=before?neighbor.endTime():neighbor.startTime();
        if(!Objects.equals(boundary,adjacent))throw new BusinessException(409,"ADJACENT_DAY_BOUNDARY_CHANGED","相邻日期日界已变化，请扩大补算范围");
    }
    public Map<LocalDate,com.platform.weather.energy.DailyElectricityAdapter.DailyResult> energy(String building,String system,String point,LocalDate start,LocalDate end) {
        Map<LocalDate,com.platform.weather.energy.DailyElectricityAdapter.DailyResult> map=new LinkedHashMap<>();
        jdbc.query("SELECT business_day,result_json FROM biz_energy_daily_comparison_result WHERE building_id=? AND system_id=? AND point_id=? AND business_day>=? AND business_day<=?",(RowCallbackHandler)r->map.put(r.getDate(1).toLocalDate(),codec.read(r.getString(2),com.platform.weather.energy.DailyElectricityAdapter.DailyResult.class)),building,system,point,start,end);
        return map;
    }
    /** 历史载荷在模块使用期间保留；普通批次只在过期且有更新版本、没有活动任务时进入清理。 */
    public List<Dataset> cleanupCandidates(long now) {
        return transaction(()->{
            var ids=jdbc.queryForList("SELECT d.id FROM biz_weather_dataset d WHERE (d.state='DELETING' OR (d.state='PUBLISHED' AND ((d.product='CURRENT' AND d.fetched_at<?) OR (d.product IN ('FORECAST_HOURLY','FORECAST_DAILY') AND d.fetched_at<?)) AND EXISTS (SELECT 1 FROM biz_weather_dataset n WHERE n.binding_id=d.binding_id AND n.source=d.source AND n.product=d.product AND n.state='PUBLISHED' AND n.fetched_at>d.fetched_at))) AND NOT EXISTS (SELECT 1 FROM biz_weather_job j WHERE j.dataset_id=d.id AND j.state IN ('RUNNING','QUEUED','RETRY_WAIT')) ORDER BY d.fetched_at LIMIT 20 FOR UPDATE",String.class,now-90L*86400000,now-365L*86400000);
            List<Dataset> result=new ArrayList<>();
            for(String id:ids) {
                jdbc.update("DELETE FROM biz_weather_dataset_head WHERE dataset_id=?",id);
                jdbc.update("UPDATE biz_weather_dataset SET state='DELETING' WHERE id=?",id);
                result.add(dataset(id));
            }
            jdbc.update("DELETE FROM biz_weather_batch WHERE fetched_at<? AND job_id IN (SELECT id FROM biz_weather_job WHERE state IN ('SUCCEEDED','FAILED','BLOCKED','CANCELLED'))",now-90L*86400000);
            return result;
        });
    }
    public void cleaned(String id) { jdbc.update("DELETE FROM biz_weather_dataset WHERE id=? AND state='DELETING'",id); }

}
