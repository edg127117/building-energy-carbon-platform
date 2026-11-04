package com.platform.adapter.model;

import java.math.BigDecimal;

/** V2显式区间电量；点码由平台绑定解析，接收时刻不能代替计量时界。 */
public record ElectricityInterval(String metricCode,long startMs,long endMs,long revision,
        BigDecimal electricityKwh,BigDecimal factorKgCo2PerKwh,String electricityUnit,String factorUnit,
        String sourceNature,String sourceReference) {
    public void validate() {
        if(metricCode==null||!metricCode.matches("[A-Za-z0-9_.:-]{1,100}")
                ||startMs<946656000000L||startMs>=10000000000000L||endMs>=10000000000000L||startMs%900000!=0||endMs-startMs!=900000||revision<1
                ||electricityKwh==null&&factorKgCo2PerKwh==null
                ||!"kWh".equals(electricityUnit)||!"kgCO2/kWh".equals(factorUnit)
                ||!("SOURCE_LABELLED".equals(sourceNature)||"SIMULATED".equals(sourceNature))
                ||sourceReference==null||sourceReference.isBlank()||sourceReference.length()>500)
            throw new IllegalArgumentException("INVALID_ELECTRICITY_INTERVAL");
        decimal(electricityKwh,12);decimal(factorKgCo2PerKwh,18);
    }
    private static void decimal(BigDecimal value,int fraction) {
        if(value!=null&&(value.signum()<0||Math.max(0,value.scale())>fraction||value.precision()-value.scale()>12))
            throw new IllegalArgumentException("INVALID_ELECTRICITY_INTERVAL_VALUE");
    }
}
