package com.platform.adapter.profile;

import com.platform.adapter.publication.ProtocolSnapshotContracts.Envelope;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Receipt;

import java.io.IOException;
import java.util.Optional;

/** 可替换测试实现的适配器配置拉取边界。 */
public interface AdapterConfigurationClient {

    Optional<Envelope> fetch() throws IOException, InterruptedException;

    void sendReceipt(Receipt receipt) throws IOException, InterruptedException;
}
