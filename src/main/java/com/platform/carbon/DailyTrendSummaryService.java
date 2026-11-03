package com.platform.carbon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static com.platform.carbon.DailyTrendSummaryContracts.*;

/** 查询不执行31天计算：授权后校验来源头版本，读取缓存或持久化摘要；未生成时返回可轮询状态。 */
@Service
public class DailyTrendSummaryService {
    private final CarbonAuthorization authorization;
    private final DailyTrendSummaryRepository repository;
    private final DailyTrendSummaryCache cache;
    private final DailyElectricityCarbonService detailed;
    private final ObjectMapper mapper;
    public DailyTrendSummaryService(CarbonAuthorization authorization,DailyTrendSummaryRepository repository,DailyTrendSummaryCache cache,
            DailyElectricityCarbonService detailed,ObjectMapper mapper){this.authorization=authorization;this.repository=repository;this.cache=cache;this.detailed=detailed;this.mapper=mapper;}
    public View query(long user,Collection<String> roles,String building,String month) {
        YearMonth selected=validate(building,month);
        authorization.requireReader(user,roles,building);
        String version=repository.sourceVersion(building,selected);
        var head=repository.head(building,month);
        if(head!=null && version.equals(head.builtVersion()) && version.equals(head.sourceVersion()) && "READY".equals(head.state())) {
            String payload=cache.get(building,month,version);
            View view=decode(payload,head);
            if(view!=null)return view;
            payload=repository.payload(head);view=decode(payload,head);
            if(view!=null){cache.put(building,month,version,payload);return view;}
            throw new BusinessException(503,"DAILY_SUMMARY_INTEGRITY","日趋势摘要完整性校验失败");
        }
        repository.request(building,month,user,version,System.currentTimeMillis());
        return preparing(building,month,version);
    }
    boolean rebuild(DailyTrendSummaryRepository.Head head,String token,Collection<String> roles) {
        // 仍调用既有授权、独立日事务、总分表去重及冻结因子校验，不从月总量或图形反推结果。
        var result=detailed.query(head.user(),roles,head.building(),head.month());
        String current=repository.sourceVersion(head.building(),YearMonth.parse(head.month()));
        if(!head.sourceVersion().equals(current)) {
            repository.request(head.building(),head.month(),head.user(),current,System.currentTimeMillis());
            return false;
        }
        View view=from(result,current);
        String payload;
        try {payload=mapper.writeValueAsString(view);} catch(JsonProcessingException e){throw new IllegalStateException("日趋势摘要无法编码",e);}
        if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>200000)throw new BusinessException(503,"DAILY_SUMMARY_CAPACITY","日趋势摘要超过展示容量");
        String hash=CarbonCalculationCore.sha256(payload);
        if(!repository.publish(head,token,payload,hash,System.currentTimeMillis()))return false;
        cache.put(head.building(),head.month(),current,payload);
        return true;
    }
    private View decode(String payload,DailyTrendSummaryRepository.Head head) {
        if(payload==null || !Objects.equals(head.hash(),CarbonCalculationCore.sha256(payload)))return null;
        try {
            View v=mapper.readValue(payload,View.class);
            return head.building().equals(v.buildingId()) && head.month().equals(v.month()) && head.builtVersion().equals(v.sourceVersion())
                    && CONTRACT.equals(v.contractVersion()) ? v : null;
        } catch(JsonProcessingException e){return null;}
    }
    private YearMonth validate(String building,String month) {
        try {
            if(building==null || !building.matches("[A-Za-z0-9_-]{1,32}") || month==null || !month.matches("\\d{4}-\\d{2}"))throw new DateTimeException("identity");
            var value=YearMonth.parse(month);if(value.getYear()<2000 || value.getYear()>2200)throw new DateTimeException("year");return value;
        } catch(DateTimeException e){throw new BusinessException(400,"DAILY_TREND_QUERY_INVALID","建筑或月份无效");}
    }
}
