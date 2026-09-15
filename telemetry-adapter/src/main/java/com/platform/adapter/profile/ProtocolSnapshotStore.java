package com.platform.adapter.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.adapter.publication.ProtocolSnapshotContracts.Envelope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** 将最后一次成功加载的完整信封保存在单个原子替换文件中。 */
public class ProtocolSnapshotStore {

    private final ObjectMapper objectMapper;
    private final Path snapshotFile;
    private final int maxBytes;
    private final String targetId;

    public ProtocolSnapshotStore(
            ObjectMapper objectMapper, Path snapshotFile, int maxBytes, String targetId) {
        this.objectMapper = objectMapper;
        this.snapshotFile = snapshotFile.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("本地协议快照必须绑定targetId");
        }
        this.targetId = targetId.trim();
    }

    public Optional<Envelope> load() throws IOException {
        if (!Files.exists(snapshotFile)) {
            return Optional.empty();
        }
        long size = Files.size(snapshotFile);
        if (size <= 0 || size > maxBytes) {
            throw new IOException("本地协议快照文件大小非法");
        }
        StoredEnvelope stored = objectMapper.readValue(
                Files.readAllBytes(snapshotFile), StoredEnvelope.class);
        if (!targetId.equals(stored.targetId())) {
            throw new IOException("本地协议快照不属于当前targetId");
        }
        return Optional.of(stored.envelope());
    }

    public void save(Envelope envelope) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(new StoredEnvelope(targetId, envelope));
        if (bytes.length > maxBytes) {
            throw new IOException("本地协议快照超过大小限制");
        }
        Path parent = snapshotFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, snapshotFile.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, snapshotFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record StoredEnvelope(String targetId, Envelope envelope) {
    }
}
