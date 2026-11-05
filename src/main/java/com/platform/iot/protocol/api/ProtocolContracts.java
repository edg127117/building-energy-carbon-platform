package com.platform.iot.protocol.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/** 协议草稿与无写预览契约；样例只出现在临时请求中，不属于持久配置。 */
public final class ProtocolContracts {
    private ProtocolContracts() {}

    public record Configuration(
            @NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=32) String productId,
            @NotBlank @Pattern(regexp="[A-Z0-9_]{1,50}") String profileCode,
            @NotBlank @Size(max=200) String sourceTopic,
            @NotBlank @Size(max=20) String identityType,
            @NotBlank @Size(max=200) String identityPath,
            @Size(max=200) String discriminatorPath,
            @Size(max=50) String discriminatorValue,
            @Size(max=200) String timestampPath,
            @Size(max=200) String seqPath,
            @Size(max=200) String messageIdPath,
            @Size(max=200) String bootIdPath,
            @Size(max=200) String batchIdPath,
            @Size(max=200) String retransmittedAtPath,
            @Size(max=50) String maxAckMode,
            @Size(max=50) String correlationPolicy,
            @NotEmpty @Size(max=128) List<@NotNull @Valid Mapping> mappings) {

        /** 兼容未认识可靠性字段的旧客户端；缺省值由运行时适配器按旧规则处理。 */
        public Configuration(String name,String productId,String profileCode,String sourceTopic,String identityType,
                String identityPath,String discriminatorPath,String discriminatorValue,String timestampPath,
                List<Mapping> mappings) {
            this(name,productId,profileCode,sourceTopic,identityType,identityPath,discriminatorPath,discriminatorValue,
                    timestampPath,null,null,null,null,null,null,null,mappings);
        }
    }

    public record Mapping(
            @NotBlank @Size(max=200) String sourcePath,
            @NotBlank @Size(max=100) String metricCode,
            @NotBlank @Size(max=20) String sourceUnit,
            @NotBlank @Size(max=20) String targetUnit,
            @NotNull @Digits(integer=11,fraction=9) @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal scale,
            @NotNull @Digits(integer=11,fraction=9) @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal offset,
            @NotNull Boolean required,
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(10000) Integer sortOrder,
            @Pattern(regexp="DECIMAL|ELECTRICITY_KWH|CO2_FACTOR|INTERVAL_START|INTERVAL_END|INTERVAL_REVISION") String valueType) {
        public Mapping(String sourcePath,String metricCode,String sourceUnit,String targetUnit,BigDecimal scale,BigDecimal offset,
                Boolean required,Boolean enabled,Integer sortOrder) {
            this(sourcePath,metricCode,sourceUnit,targetUnit,scale,offset,required,enabled,sortOrder,null);
        }
        public String effectiveValueType(){return com.platform.adapter.parser.IntervalMappings.type(valueType);}
    }

    public record UpdateRequest(@NotNull @Min(1) Long revision,
                                @NotNull @Valid Configuration configuration) {}
    public record Detail(String id, long revision, String status,
                         Configuration configuration, long updatedAt) {}
    public record InspectRequest(@NotBlank @Size(max=65536) String samplePayload) {}
    public record Field(String path, String type, String value) {}
    public record Inspection(List<Field> fields) {}
    public record PreviewRequest(@NotNull @Valid Configuration configuration,
                                 @NotBlank @Size(max=65536) String samplePayload,
                                 @NotNull @Min(1000000000000L) @Max(9999999999999L) Long receivedTime) {}
    public record PreviewError(String code, String path, String message) {}
    public record PreviewMetric(String metricCode, String sourcePath, String rawValue,
                                String value, String unit, String status) {}
    public record Preview(boolean success, List<PreviewError> errors,
                          String identityType, String identityValue, String timeSource,
                          Long eventTime, List<PreviewMetric> metrics) {}
}
