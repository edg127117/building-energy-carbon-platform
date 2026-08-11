package com.platform.iot.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.hvac.mapper.BizDeviceIdentityMapper;
import com.platform.hvac.mapper.BizEquipmentMapper;
import com.platform.hvac.model.entity.BizDeviceIdentity;
import com.platform.hvac.model.entity.BizEquipment;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 本地 MySQL 设备预注册关系的原子内存快照。
 *
 * <p>每次刷新同时读取身份和设备台账，并验证身份声明的建筑与设备实际建筑一致。
 * 任一配置不完整时拒绝整次刷新，继续使用上一完整快照，避免 MQTT 热路径发生串楼。</p>
 */
public class MySqlDeviceIdentityProvider implements DeviceIdentityProvider {

    private final BizDeviceIdentityMapper identityMapper;
    private final BizEquipmentMapper equipmentMapper;
    private volatile Map<DeviceIdentityKey, DeviceIdentityBinding> snapshot = Map.of();
    private volatile boolean snapshotAvailable;

    @PostConstruct
    public void initialize() {
        refreshAll();
    }

    @Scheduled(fixedDelayString = "${ingestion.device-identity-refresh-ms:60000}")
    public void refreshAll() {
        try {
            List<BizEquipment> equipment = equipmentMapper.selectList(new LambdaQueryWrapper<>());
            Map<String, BizEquipment> equipmentById = new LinkedHashMap<>();
            for (BizEquipment item : equipment) {
                equipmentById.put(item.getEquipId(), item);
            }

            List<BizDeviceIdentity> identities = identityMapper.selectList(new LambdaQueryWrapper<>());
            Map<DeviceIdentityKey, DeviceIdentityBinding> next = new LinkedHashMap<>();
            for (BizDeviceIdentity identity : identities) {
                if (!Integer.valueOf(1).equals(identity.getStatus())) {
                    continue;
                }
                BizEquipment owner = equipmentById.get(identity.getEquipId());
                if (owner == null || !identity.getBuildingId().equals(owner.getBuildingId())) {
                    throw new IllegalStateException(
                            "设备身份归属与设备台账不一致: identityId=" + identity.getIdentityId());
                }
                DeviceIdentityKey key = new DeviceIdentityKey(
                        identity.getIdentityType(), identity.getIdentityValue());
                DeviceIdentityBinding binding = new DeviceIdentityBinding(
                        identity.getIdentityId(),
                        key,
                        owner.getEquipId(),
                        owner.getEquipCode(),
                        owner.getBuildingId(),
                        identity.getExpectedProfileCode());
                if (next.putIfAbsent(key, binding) != null) {
                    throw new IllegalStateException("存在重复启用设备身份: type=" + key.type());
                }
            }
            snapshot = Map.copyOf(next);
            snapshotAvailable = true;
            log.debug("设备身份快照刷新完成: identities={}", next.size());
        } catch (RuntimeException exception) {
            log.warn("设备身份快照刷新失败，继续使用上一完整版本: {}", exception.getMessage());
        }
    }

    @Override
    public Optional<DeviceIdentityBinding> find(DeviceIdentityKey key) {
        if (!snapshotAvailable) {
            throw new DeviceIdentitySnapshotUnavailableException("设备身份快照尚未成功加载");
        }
        return Optional.ofNullable(snapshot.get(key));
    }
}
