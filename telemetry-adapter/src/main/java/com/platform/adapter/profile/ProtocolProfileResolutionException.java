package com.platform.adapter.profile;

/** 原始 Topic 与载荷无法唯一命中启用协议模板。 */
public class ProtocolProfileResolutionException extends RuntimeException {

    public ProtocolProfileResolutionException(String message) {
        super(message);
    }
}
