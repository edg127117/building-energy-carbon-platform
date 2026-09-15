package com.platform.adapter.publication;

import com.platform.adapter.profile.ProtocolFieldMapping;
import com.platform.adapter.profile.ProtocolProfile;

import java.util.List;

/** 平台与适配器之间发布完整协议快照所共用的 JSON 契约。 */
public final class ProtocolSnapshotContracts {

    private ProtocolSnapshotContracts() {
    }

    public record Envelope(long sequence, String digest, String contentJson) {
    }

    public record Snapshot(int schemaVersion, String outputVersion, List<Entry> profiles) {
        public Snapshot {
            profiles = profiles == null ? null : List.copyOf(profiles);
        }
    }

    public record Entry(ProtocolProfile profile, List<ProtocolFieldMapping> mappings) {
        public Entry {
            mappings = mappings == null ? null : List.copyOf(mappings);
        }
    }

    /** 旧 JDBC 规则的一次性迁移文件；archived 不得交给运行快照加载器。 */
    public record MigrationExport(Snapshot snapshot, Snapshot archived) {
    }

    public record Receipt(long sequence, String digest, String status, String errorCode) {
    }
}
