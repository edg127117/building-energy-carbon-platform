package com.platform.adapter.profile;

/** 协议配置尚未成功加载，当前报文应保留并等待配置恢复后重投。 */
public class ProtocolProfileUnavailableException extends RuntimeException {

    public ProtocolProfileUnavailableException(String message) {
        super(message);
    }
}
