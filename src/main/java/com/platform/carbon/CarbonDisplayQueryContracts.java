package com.platform.carbon;

import java.time.*;
import java.util.*;
import com.platform.framework.exception.BusinessException;

/** 只描述两个只读展示查询；业务结果仍采用原服务契约，不在这里计算或补齐指标。 */
public final class CarbonDisplayQueryContracts {
    private CarbonDisplayQueryContracts() { }
    public static final String CONTRACT="CARBON_DISPLAY_QUERY_V1";
    public record View(String contractVersion,String kind,String buildingId,String queryKey,String sourceVersion,
                       String state,Object data,int retryAfterSeconds) { }
    record Query(String kind,String buildingId,String mode,String period,String throughMonth,String configurationVersionId,
                 int year,Integer month,String timezoneId) {
        String key(){return CarbonCalculationCore.sha256(CarbonEvidence.canonical(CarbonEvidence.tree(this)));}
        JointFlowContracts.Request flow(){return new JointFlowContracts.Request(buildingId,mode,period,throughMonth,configurationVersionId);}
    }
    static Query flow(JointFlowContracts.Request input) {
        try {
            building(input.buildingId());
            int year;
            if("MONTH".equals(input.periodType())) {
                year=YearMonth.parse(input.period()).getYear();
                if(input.throughMonth()!=null&&!input.throughMonth().isBlank())throw new DateTimeException("cutoff");
            } else if("YEAR".equals(input.periodType())&&input.period()!=null&&input.period().matches("\\d{4}")) {
                year=Integer.parseInt(input.period());
                if(YearMonth.parse(input.throughMonth()).getYear()!=year)throw new DateTimeException("cutoff");
            } else throw new DateTimeException("mode");
            if(year<2000||year>2200 || input.configurationVersionId()!=null&&input.configurationVersionId().length()>32)throw new DateTimeException("range");
            return new Query("JOINT_FLOW",input.buildingId(),input.periodType(),input.period(),blank(input.throughMonth()),blank(input.configurationVersionId()),year,null,"Asia/Shanghai");
        } catch(DateTimeException|NumberFormatException|NullPointerException e){throw invalid();}
    }
    static Query intensity(String building,String mode,int year,Integer month,String timezone) {
        try {
            building(building);
            if(year<2000||year>2200||!Set.of("FORMAL_YEAR","MONTHLY_YTD","MONTH").contains(Objects.toString(mode,""))
                    ||("FORMAL_YEAR".equals(mode)?month!=null:month==null||month<1||month>12))throw new DateTimeException("period");
            return new Query("CARBON_INTENSITY",building,mode,null,null,null,year,month,ZoneId.of(timezone).getId());
        } catch(DateTimeException|NullPointerException e){throw invalid();}
    }
    private static String blank(String value){return value==null||value.isBlank()?null:value;}
    private static void building(String value){if(value==null||value.isBlank()||value.length()>32)throw invalid();}
    private static BusinessException invalid(){return new BusinessException(400,"CARBON_DISPLAY_QUERY_INVALID","展示查询的建筑、期间、模式或时区无效");}
}
