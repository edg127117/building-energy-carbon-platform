package com.platform.carbon;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Collection;
import static com.platform.carbon.CarbonIntensityContracts.Actual;

/** 集成年度候选后只传递其累计强度与整体证据，不重新累计月量或计算面积。 */
@Component
@RequiredArgsConstructor
public class AnnualCarbonIntensityYtdReader implements CarbonIntensityYtdReader {
    private final AnnualOverviewService annual;
    private final StoredCarbonIntensityReader stored;
    @Override
    public Actual read(long user, Collection<String> roles, String buildingId, int year, int throughMonth, String timezoneId) {
        var response=annual.overview(user,roles,buildingId,year,"MONTHLY_YTD",throughMonth,timezoneId);
        var matches=response.current().metrics().stream().filter(m->"YTD_AREA_INTENSITY".equals(m.code())).toList();
        if(matches.size()!=1) return StoredCarbonIntensityReader.unavailable("YTD_METRIC_UNAVAILABLE");
        var metric=matches.getFirst();
        return new Actual(metric.value(),metric.unit(),metric.status(),
                "AVAILABLE".equals(metric.status())?stored.gasBasisForBatches(buildingId,metric.sources().stream().map(AnnualOverviewService.Source::batchId).distinct().toList()):"UNKNOWN","UNKNOWN",
                "AVAILABLE".equals(response.current().areaStatus())?"BUILDING_AREA":"UNKNOWN",null,response.evidenceHash(),null);
    }
}
