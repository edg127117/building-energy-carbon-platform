package com.platform.carbon;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonPlanContracts.*;
import static com.platform.carbon.CarbonErrors.*;

/** 基准归一与精确配额运算；不按展示位数逐月舍入，不把缺失值补零。 */
@Component
class CarbonPlanCore {
    static final String ZONE="Asia/Shanghai";
    static final String UNIT="tCO2e";
    Baseline normalize(Baseline b,String building) {
        if(b==null || !Set.of("FORMAL_YEAR","EXTERNAL_CONFIRMED","TEST_FIXTURE").contains(Objects.toString(b.nature(),""))
                || !ZONE.equals(b.timezoneId()) || b.months()==null || b.months().size()!=12 || b.months().stream().anyMatch(Objects::isNull)) invalid("基准须为北京时间完整12个月，来源性质须明确");
        text(b.source(),500,"基准来源"); text(b.boundary(),500,"核算边界");
        if("FORMAL_YEAR".equals(b.nature()) && (b.sourceYear()==null || b.sourceYear()<2000 || b.sourceYear()>2200)) invalid("基准年无效");
        var sorted=b.months().stream().sorted(Comparator.comparingInt(BaselineMonth::month)).map(m->{
            amount(m.value());
            return new BaselineMonth(m.month(),canonical(m.value()),m.unit(),m.source(),m.batchId(),m.evidenceHash(),m.referenceVersionId());
        }).toList();
        BigDecimal total=BigDecimal.ZERO;
        for(int i=0;i<12;i++) {
            var m=sorted.get(i);
            if(m.month()!=i+1 || !UNIT.equals(m.unit())) invalid("基准月份须无重复覆盖1至12月，单位须为tCO2e");
            amount(m.value()); text(m.source(),500,"月基准来源");
            if("FORMAL_YEAR".equals(b.nature())) { text(m.batchId(),32,"正式来源批次"); text(m.evidenceHash(),64,"正式来源证据"); }
            total=total.add(m.value());
        }
        if(b.annualBaseline()!=null && b.annualBaseline().compareTo(total)!=0) invalid("年基准与12个月合计不一致");
        // 指纹包含建筑、冻结值及全部来源证据；审核后只读该快照，不再查源年重算基准。
        String hash=CarbonCalculationCore.sha256(building+"|"+b.nature()+"|"+b.sourceYear()+"|"+b.boundary()+"|"+b.source()+"|"+sorted);
        return new Baseline(b.nature(),b.sourceYear(),ZONE,b.boundary(),b.source(),List.copyOf(sorted),total,hash);
    }
    Configuration normalize(Configuration c,String building) {
        if(c==null || c.reductionPercent()==null || c.reductionPercent().signum()<0 || c.reductionPercent().compareTo(new BigDecimal("100"))>0 || c.reductionPercent().scale()>6) invalid("消减率须在0至100之间且最多6位小数");
        return new Configuration(normalize(c.baseline(),building),c.reductionPercent());
    }
    BigDecimal quota(BigDecimal baseline,BigDecimal percent) {
        return baseline.multiply(BigDecimal.ONE.subtract(percent.movePointLeft(2)));
    }
    static void amount(BigDecimal n) {
        // MySQL DECIMAL(38,18) 会补尾零；精度限制按数值有效小数位判断，不能误拒正式六位月结果。
        if(n==null || n.signum()<0 || n.stripTrailingZeros().scale()>12 || n.precision()-n.scale()>18) invalid("月碳排须为非负有限十进制，最多18位整数和12位小数");
    }
    private static BigDecimal canonical(BigDecimal n) { var value=n.stripTrailingZeros();return value.scale()<0?value.setScale(0):value; }
    static void text(String value,int length,String name) {
        if(value==null || value.isBlank() || value.length()>length) invalid(name+"必填且不能超过"+length+"字");
    }
    static void invalid(String message) { throw error(400,VALIDATION_FAILED,message); }
}
