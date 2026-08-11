package com.platform.iot.quality;

/** 本地测点与别名快照尚未成功加载，采集报文应保留等待重投。 */
public class DataPointConfigSnapshotUnavailableException extends RuntimeException {

    public DataPointConfigSnapshotUnavailableException(String message) {
        super(message);
    }
}
