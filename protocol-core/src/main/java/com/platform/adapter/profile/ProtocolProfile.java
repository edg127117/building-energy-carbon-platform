package com.platform.adapter.profile;

/** 一种 JSON 上行协议的身份、版本和时间字段位置。 */
public record ProtocolProfile(
        String profileId,
        String profileCode,
        int profileVersion,
        String sourceTopic,
        String deviceIdentityType,
        String deviceIdentityPath,
        String protocolVersionPath,
        String expectedProtocolVersion,
        String timestampPath,
        String seqPath,
        String messageIdPath,
        String bootIdPath,
        String batchIdPath,
        String retransmittedAtPath,
        String maxAckMode,
        String correlationPolicy,
        boolean enabled) {
}
