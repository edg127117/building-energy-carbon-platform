package com.platform.adapter.model;

import java.math.BigDecimal;

/** V2周期量；零修订表示由平台管理版本，缺失时界只允许从已声明的设备边界时间推导。 */
public record ElectricityInterval(String metricCode,long startMs,long endMs,long revision,
        BigDecimal electricityKwh,BigDecimal factorKgCo2PerKwh,String electricityUnit,String factorUnit,
        String sourceNature,String sourceReference,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String sequenceSource) {
    public ElectricityInterval(String metricCode,long startMs,long endMs,long revision,BigDecimal electricityKwh,BigDecimal factorKgCo2PerKwh,
            String electricityUnit,String factorUnit,String sourceNature,String sourceReference) {
        this(metricCode,startMs,endMs,revision,electricityKwh,factorKgCo2PerKwh,electricityUnit,factorUnit,sourceNature,sourceReference,null);
    }
    public ElectricityInterval boundaries(Long deviceEnd) {
        long end=endMs,start=startMs;
        if(start==0&&end==0) {
            if(deviceEnd==null)throw new IllegalArgumentException("INTERVAL_DEVICE_TIME_MISSING");
            end=deviceEnd;
        }
        if(start==0)start=end-900000;
        if(end==0)end=start+900000;
        var value=new ElectricityInterval(metricCode,start,end,revision,electricityKwh,factorKgCo2PerKwh,electricityUnit,factorUnit,sourceNature,sourceReference,sequenceSource);
        value.validate();return value;
    }
    public void validate() {
        if(metricCode==null||!metricCode.matches("[A-Za-z0-9_.:-]{1,100}")
                ||startMs<0||endMs<0||revision<0
                ||startMs!=0&&(startMs<946656000000L||startMs>=10000000000000L||startMs%900000!=0)
                ||endMs!=0&&(endMs<946656900000L||endMs>=10000000000000L||endMs%900000!=0)
                ||startMs!=0&&endMs!=0&&endMs-startMs!=900000
                ||electricityKwh==null&&factorKgCo2PerKwh==null
                ||!"kWh".equals(electricityUnit)||!"kgCO2/kWh".equals(factorUnit)
                ||!("SOURCE_LABELLED".equals(sourceNature)||"SIMULATED".equals(sourceNature))
                ||sourceReference==null||sourceReference.isBlank()||sourceReference.length()>500
                ||sequenceSource!=null&&!java.util.Set.of("DEVICE_FIELDS","ADAPTER_POLL").contains(sequenceSource))
            throw new IllegalArgumentException("INVALID_ELECTRICITY_INTERVAL");
        decimal(electricityKwh,12);decimal(factorKgCo2PerKwh,18);
    }
    private static void decimal(BigDecimal value,int fraction) {
        if(value!=null&&(value.signum()<0||Math.max(0,value.scale())>fraction||value.precision()-value.scale()>12))
            throw new IllegalArgumentException("INVALID_ELECTRICITY_INTERVAL_VALUE");
    }
}
