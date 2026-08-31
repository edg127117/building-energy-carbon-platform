package com.platform.relation.importing;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "relation-governance.metering-import")
/** 平台标准表计模板的资源安全上限，不承载能源专业规则。 */
public class MeteringImportProperties {
    @Min(1_024)
    @Max(20_000_000)
    private int maxFileBytes = 5_000_000;

    @Min(1)
    @Max(20_000)
    private int maxRows = 5_000;

    @Min(16)
    @Max(2_000)
    private int maxTextLength = 500;
}
