package com.platform.carbon;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static com.platform.carbon.CarbonPlanContracts.*;
import static org.assertj.core.api.Assertions.*;

class CarbonPlanCoreTest {
    private final CarbonPlanCore core=new CarbonPlanCore();
    @Test void exactAnnualAndMonthlyQuotasAgreeIncludingFractionalPrecisionAndRealZero() {
        var c=core.normalize(CarbonPlanFixture.configuration(),"BLD001");
        assertThat(c.baseline().annualBaseline()).isEqualByComparingTo("160");
        assertThat(core.quota(c.baseline().annualBaseline(),c.reductionPercent())).isEqualByComparingTo("136");
        BigDecimal sum=c.baseline().months().stream().map(m->core.quota(m.value(),new BigDecimal("15.123456"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(core.quota(new BigDecimal("160"),new BigDecimal("15.123456")));
        assertThat(core.quota(BigDecimal.ZERO,BigDecimal.ZERO)).isEqualByComparingTo("0");
    }
    @Test void validatesMissingDuplicateNegativeUnitTotalAndRate() {
        var b=CarbonPlanFixture.configuration().baseline();
        bad(new Baseline(b.nature(),null,b.timezoneId(),b.boundary(),b.source(),b.months().subList(0,11),null,null));
        var duplicate=new ArrayList<>(b.months());duplicate.set(11,duplicate.getFirst());bad(withMonths(b,duplicate));
        for(var value:List.of(new BigDecimal("-1"),new BigDecimal("0.0000000000001"),new BigDecimal("1e50"))) {
            var invalid=new ArrayList<>(b.months());invalid.set(0,new BaselineMonth(1,value,"tCO2e","测试",null,null,null));bad(withMonths(b,invalid));
        }
        var invalid=new ArrayList<>(b.months());invalid.set(0,new BaselineMonth(1,BigDecimal.ONE,"kgCO2e","测试",null,null,null));bad(withMonths(b,invalid));
        bad(new Baseline(b.nature(),null,b.timezoneId(),b.boundary(),b.source(),b.months(),BigDecimal.ONE,null));
        for(var rate:List.of("-1","100.1","0.1234567")) assertThatThrownBy(()->core.normalize(new Configuration(b,new BigDecimal(rate)),"b")).isInstanceOf(com.platform.framework.exception.BusinessException.class);
    }
    @Test void fingerprintBindsBuildingAndFrozenSources() {
        var b=CarbonPlanFixture.configuration().baseline();
        assertThat(core.normalize(b,"a").evidenceHash()).isNotEqualTo(core.normalize(b,"b").evidenceHash());
    }
    @Test void mysqlPaddedDecimalsAreAcceptedWithoutChangingValueOrEvidenceFingerprint() {
        var b=CarbonPlanFixture.configuration().baseline();
        var padded=b.months().stream().map(m->new BaselineMonth(m.month(),m.value().setScale(18),m.unit(),m.source(),null,null,null)).toList();
        assertThat(core.normalize(withMonths(b,padded),"b")).isEqualTo(core.normalize(b,"b"));
    }
    private Baseline withMonths(Baseline b,List<BaselineMonth> months) { return new Baseline(b.nature(),null,b.timezoneId(),b.boundary(),b.source(),months,null,null); }
    private void bad(Baseline b) { assertThatThrownBy(()->core.normalize(b,"b")).isInstanceOf(com.platform.framework.exception.BusinessException.class); }
}
