package com.platform.carbon;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 目录按不可变版本保存；查询保留实际值原单位，参考值的年度单位单独返回。 */
public final class CarbonIntensityContracts {
    private CarbonIntensityContracts() { }
    public record Rule(String regionPrefix, String buildingCategory, String subcategory, String climateLabel,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal constraintValue,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal baselineValue,
                       @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal guideValue,
                       String gasBasis, String boundaryCode, String areaBasis, String boundaryNote, String areaNote, String timeStatus,
                       LocalDate applicableFrom, LocalDate applicableTo) {
        public Rule {
            regionPrefix=regionPrefix==null?null:regionPrefix.trim();
            if(regionPrefix!=null && regionPrefix.matches("[0-9]{6}")) {
                if(regionPrefix.endsWith("0000")) regionPrefix=regionPrefix.substring(0,2);
                else if(regionPrefix.endsWith("00")) regionPrefix=regionPrefix.substring(0,4);
            }
            buildingCategory=buildingCategory==null?null:buildingCategory.trim();
            subcategory=subcategory==null || subcategory.isBlank()?null:subcategory.trim();
            climateLabel=climateLabel==null?"":climateLabel.trim();
            boundaryNote=boundaryNote==null?"":boundaryNote.trim(); areaNote=areaNote==null?"":areaNote.trim();
            if(constraintValue!=null) constraintValue=new BigDecimal(constraintValue.stripTrailingZeros().toPlainString());
            if(baselineValue!=null) baselineValue=new BigDecimal(baselineValue.stripTrailingZeros().toPlainString());
            if(guideValue!=null) guideValue=new BigDecimal(guideValue.stripTrailingZeros().toPlainString());
        }
    }
    public record Save(String catalogId, String expectedVersionId, String name, String documentVersion,
                       String sourceReference, LocalDate publishedOn, List<Rule> entries) { }
    public record Version(String catalogId, String versionId, int revision, String name, String documentVersion,
                          String sourceReference, LocalDate publishedOn, long createdBy, String createdAt,
                          String evidenceHash, List<Rule> entries) { }
    public record Reference(String versionId, String evidenceHash, String name, String documentVersion,
                            String sourceReference, LocalDate publishedOn, Rule rule, String unit) { }
    public record Actual(@JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal value, String unit,
                         String status, String gasBasis, String boundaryStatus, String areaStatus,
                         String batchId, String evidenceHash, String denominatorVersionId) { }
    public record View(String buildingId, String mode, LocalDate start, LocalDate endExclusive, String timezoneId,
                       Actual actual, String matchStatus, String comparisonStatus, List<String> reasons,
                       Reference reference, @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal percentage,
                       String percentageStatus, String percentageBasis, List<String> percentageReasons,
                       String evidenceHash) { }
}
