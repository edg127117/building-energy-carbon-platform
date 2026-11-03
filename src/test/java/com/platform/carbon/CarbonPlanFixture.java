package com.platform.carbon;

import java.math.BigDecimal;
import java.util.stream.IntStream;
import static com.platform.carbon.CarbonPlanContracts.*;

/** 仅自动化测试：年基准160，十二月分布10×8+20×4，消减15%；不注入生产配置。 */
final class CarbonPlanFixture {
    static Configuration configuration() {
        var months=IntStream.rangeClosed(1,12).mapToObj(m->new BaselineMonth(m,new BigDecimal(m<=8?"10":"20"),"tCO2e","自动化模拟基准",null,null,null)).toList();
        return new Configuration(new Baseline("TEST_FIXTURE",null,"Asia/Shanghai","隔离自动化建筑范围一、二测试边界","TEST ONLY / carbon-plan-fixture-v1",months,new BigDecimal("160"),null),new BigDecimal("15"));
    }
}
