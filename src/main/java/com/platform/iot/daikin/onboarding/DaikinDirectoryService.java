package com.platform.iot.daikin.onboarding;

import com.platform.framework.exception.BusinessException;
import com.platform.iot.daikin.model.DaikinDeviceKey;
import com.platform.iot.daikin.model.DaikinDeviceObservation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 大金完整目录进入既有待接入区的唯一持久化边界。
 *
 * <p>厂家目录只登记来源身份与待接入候选，不创建正式设备，也不配置或返回厂家凭据。完整目录
 * 在单来源事务锁内落库；人工忽略、绑定状态和正式身份始终由既有接入状态机维护。</p>
 */
@Service
public class DaikinDirectoryService {
    static final String IDENTITY_TYPE = "DAIKIN_UNIT";
    static final int MAX_DEVICES = 10_000;
    private static final ZoneId MYSQL_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ADMIN = "PLATFORM_ADMIN";
    private static final String SAMPLE_INDOOR = "{\"catalog\":true,\"kind\":\"INDOOR\"}";
    private static final String SAMPLE_OUTDOOR = "{\"catalog\":true,\"kind\":\"OUTDOOR\"}";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public DaikinDirectoryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc,
                                  TransactionTemplate transaction) {
        this(jdbc, transaction, Clock.systemUTC());
    }

    DaikinDirectoryService(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.clock = Objects.requireNonNull(clock);
    }

    public void registerSource(String sourceId, Long operatorId, Set<String> roles) {
        registerSource(sourceId, "大金空调数据源", operatorId, roles);
    }

    public void registerSource(String sourceId, String sourceName, Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        sourceId = requireText(sourceId, 200, "大金来源身份无效");
        sourceName = requireText(sourceName, 200, "大金来源名称无效");
        requireOperator(operatorId);
        String registeredSource = sourceId;
        String registeredName = sourceName;
        transaction.executeWithoutResult(status -> {
            List<String> names = jdbc.queryForList(
                    "SELECT source_name FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                    String.class, registeredSource);
            if (!names.isEmpty()) {
                if (!names.getFirst().equals(registeredName)) {
                    jdbc.update("UPDATE biz_daikin_source SET source_name=? WHERE source_id=?",
                            registeredName, registeredSource);
                }
                return;
            }
            try {
                jdbc.update("""
                        INSERT INTO biz_daikin_source(source_id,source_name,registered_by,create_time)
                        VALUES (?,?,?,CURRENT_TIMESTAMP)
                        """, registeredSource, registeredName, operatorId);
            } catch (DuplicateKeyException ignored) {
                // 并发注册同一稳定来源是幂等操作；来源行不包含任何凭据或可变配置。
            }
        });
    }

    public void mapProject(String sourceId, String siteId, String buildingId,
                           Long operatorId, Set<String> roles) {
        requireAdmin(roles);
        String validSource = requireText(sourceId, 200, "大金来源身份无效");
        String validSite = requireText(siteId, 200, "大金项目身份无效");
        String validBuilding = requireText(buildingId, 32, "平台建筑无效");
        requireOperator(operatorId);
        transaction.executeWithoutResult(status -> {
            lockSource(validSource);
            Integer building = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM building WHERE building_id=? AND del_flag=0",
                    Integer.class, validBuilding);
            if (building == null || building != 1) throw notFound("平台建筑不存在");
            List<Mapping> current = jdbc.query("""
                    SELECT building_id,mapping_version FROM biz_daikin_project_mapping
                    WHERE source_id=? AND site_id=? FOR UPDATE
                    """, (rs, row) -> new Mapping(rs.getString(1), rs.getInt(2)),
                    validSource, validSite);
            if (!current.isEmpty() && current.get(0).buildingId().equals(validBuilding)) return;
            if (!current.isEmpty() && hasBoundDirectory(validSource, validSite)) {
                throw conflict("已有设备完成绑定，不能改写其厂家项目建筑归属");
            }
            if (!current.isEmpty() && current.get(0).version() == Integer.MAX_VALUE) {
                throw conflict("厂家项目建筑映射版本已达到上限");
            }
            int nextVersion = current.isEmpty() ? 1 : current.get(0).version() + 1;
            if (current.isEmpty()) {
                jdbc.update("""
                        INSERT INTO biz_daikin_project_mapping
                          (source_id,site_id,building_id,mapping_version,mapped_by,mapped_at)
                        VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                        """, validSource, validSite, validBuilding, nextVersion, operatorId);
            } else {
                jdbc.update("""
                        UPDATE biz_daikin_project_mapping
                        SET building_id=?,mapping_version=?,mapped_by=?,mapped_at=CURRENT_TIMESTAMP
                        WHERE source_id=? AND site_id=?
                        """, validBuilding, nextVersion, operatorId, validSource, validSite);
            }
            jdbc.update("""
                    INSERT INTO biz_daikin_project_mapping_version
                      (source_id,site_id,mapping_version,building_id,mapped_by,mapped_at)
                    VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, validSource, validSite, nextVersion, validBuilding, operatorId);
        });
    }

    /**
     * 接收单来源、单类型的完整目录。调用方必须先用 {@code DaikinCatalogReader} 完成全部分页及
     * 总数校验；本方法只能复核列表内部的身份、重复、上限和时序，无法从一个 List 证明未漏页。
     */
    public void acceptCompleteCatalog(String sourceId, DaikinDeviceKey.Kind kind,
                                      List<DaikinDeviceObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            throw invalid("空目录必须显式提供完整轮次时间");
        }
        Instant roundAt = Instant.MIN;
        for (DaikinDeviceObservation observation : observations) {
            if (observation == null) throw invalid("目录设备不能为空");
            if (observation.observedAt().isAfter(roundAt)) roundAt = observation.observedAt();
        }
        acceptCompleteCatalog(sourceId, kind, roundAt, observations);
    }

    public void acceptCompleteCatalog(String sourceId, DaikinDeviceKey.Kind kind, Instant roundAt,
                                      List<DaikinDeviceObservation> observations) {
        ValidatedCatalog catalog = validateCatalog(sourceId, kind, roundAt, observations);
        transaction.executeWithoutResult(status -> acceptLocked(catalog));
    }

    public boolean isDirectoryPending(String pendingId) {
        if (pendingId == null || pendingId.isBlank()) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM biz_daikin_directory WHERE pending_id=?",
                Integer.class, pendingId);
        return count != null && count == 1;
    }

    public String requireMappedBuilding(String pendingId) {
        DirectoryView view = detail(pendingId);
        if (view.buildingId() == null) throw conflict("厂家项目尚未映射平台建筑");
        return view.buildingId();
    }

    /** 供既有绑定事务在写正式身份前调用，防止请求篡改建筑、产品类型或摘要身份。 */
    public void requireBinding(String pendingId, String buildingId, String profileCode) {
        transaction.executeWithoutResult(status -> requireBindingLocked(pendingId, buildingId, profileCode));
    }

    private void requireBindingLocked(String pendingId, String buildingId, String profileCode) {
        List<DirectoryView> directory = queryDirectory(pendingId);
        if (directory.size() != 1) throw notFound("大金目录待接入记录不存在");
        DirectoryView unlocked = directory.get(0);
        lockSource(unlocked.sourceId());
        List<String> mappings = jdbc.queryForList("""
                SELECT building_id FROM biz_daikin_project_mapping
                WHERE source_id=? AND site_id=? FOR UPDATE
                """, String.class, unlocked.sourceId(), unlocked.siteId());
        DirectoryView view = new DirectoryView(unlocked.pendingId(), unlocked.sourceId(),
                unlocked.siteId(), unlocked.controllerId(), unlocked.kind(), unlocked.unitId(),
                unlocked.siteName(), unlocked.deviceName(), unlocked.equipmentId(),
                mappings.size() == 1 ? mappings.get(0) : null, unlocked.missing(), unlocked.observedAt());
        String expectedProfile = profile(view.kind());
        if (view.buildingId() == null || !Objects.equals(view.buildingId(), buildingId)
                || !expectedProfile.equals(profileCode)) {
            throw conflict("厂家目录、项目建筑映射与绑定请求不一致");
        }
        String expectedHash = identityHash(new DaikinDeviceKey(view.sourceId(), view.siteId(),
                view.controllerId(), view.kind(), view.unitId()));
        List<PendingIdentity> identities = jdbc.query("""
                SELECT identity_type,identity_value FROM biz_pending_device
                WHERE pending_id=? FOR UPDATE
                """, (rs, row) -> new PendingIdentity(rs.getString(1), rs.getString(2)), pendingId);
        if (identities.size() != 1 || !IDENTITY_TYPE.equals(identities.get(0).type())
                || !expectedHash.equals(identities.get(0).value())) {
            throw conflict("待接入记录与厂家原始身份不一致");
        }
    }

    public DirectoryView detail(String pendingId) {
        List<DirectoryView> rows = queryDirectory(pendingId);
        if (rows.size() != 1) throw notFound("大金目录待接入记录不存在");
        return rows.get(0);
    }

    private List<DirectoryView> queryDirectory(String pendingId) {
        return jdbc.query("""
                SELECT d.pending_id,d.source_id,d.site_id,d.controller_id,d.device_kind,d.unit_id,
                       d.site_name,d.device_name,d.equipment_id,m.building_id,d.missing,d.observed_at
                FROM biz_daikin_directory d
                LEFT JOIN biz_daikin_project_mapping m
                  ON m.source_id=d.source_id AND m.site_id=d.site_id
                WHERE d.pending_id=?
                """, (rs, row) -> new DirectoryView(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), DaikinDeviceKey.Kind.valueOf(rs.getString(5)),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getBoolean(11), instant(rs.getTimestamp(12))), pendingId);
    }

    public List<String> pendingIdsForBuildings(Set<String> buildingIds) {
        if (buildingIds == null || buildingIds.isEmpty()) return List.of();
        if (buildingIds.size() > MAX_DEVICES) throw invalid("建筑范围数量超过上限");
        List<String> ids = buildingIds.stream()
                .map(value -> requireText(value, 32, "平台建筑无效"))
                .distinct().sorted().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForList("""
                SELECT d.pending_id FROM biz_daikin_directory d
                JOIN biz_daikin_project_mapping m
                  ON m.source_id=d.source_id AND m.site_id=d.site_id
                WHERE m.building_id IN (%s)
                ORDER BY d.pending_id
                """.formatted(placeholders), String.class, ids.toArray());
    }

    private void acceptLocked(ValidatedCatalog catalog) {
        lockSource(catalog.sourceId());
        List<SyncState> states = jdbc.query("""
                SELECT completed_at,catalog_hash FROM biz_daikin_catalog_sync
                WHERE source_id=? AND device_kind=?
                """, (rs, row) -> new SyncState(instant(rs.getTimestamp(1)), rs.getString(2)),
                catalog.sourceId(), catalog.kind().name());
        if (!states.isEmpty()) {
            int order = catalog.roundAt().compareTo(states.get(0).completedAt());
            if (order < 0) return;
            if (order == 0) {
                if (!catalog.hash().equals(states.get(0).hash())) {
                    throw conflict("同一目录轮次出现不同完整目录");
                }
                return;
            }
        }
        for (DaikinDeviceObservation observation : catalog.observations()) upsert(observation, catalog.roundAt());
        jdbc.update("""
                UPDATE biz_daikin_directory SET missing=1,update_time=CURRENT_TIMESTAMP
                WHERE source_id=? AND device_kind=? AND last_catalog_at<?
                """, catalog.sourceId(), catalog.kind().name(), timestamp(catalog.roundAt()));
        if (states.isEmpty()) {
            jdbc.update("""
                    INSERT INTO biz_daikin_catalog_sync(source_id,device_kind,completed_at,catalog_hash)
                    VALUES (?,?,?,?)
                    """, catalog.sourceId(), catalog.kind().name(), timestamp(catalog.roundAt()),
                    catalog.hash());
        } else {
            jdbc.update("""
                    UPDATE biz_daikin_catalog_sync SET completed_at=?,catalog_hash=?
                    WHERE source_id=? AND device_kind=?
                    """, timestamp(catalog.roundAt()), catalog.hash(), catalog.sourceId(),
                    catalog.kind().name());
        }
    }

    private void upsert(DaikinDeviceObservation observation, Instant roundAt) {
        DaikinDeviceKey key = observation.key();
        String identity = identityHash(key);
        List<PendingRow> rows = jdbc.query("""
                SELECT p.pending_id,p.last_seen_time,d.source_id,d.site_id,d.controller_id,
                       d.device_kind,d.unit_id,d.observed_at
                FROM biz_pending_device p
                LEFT JOIN biz_daikin_directory d ON d.pending_id=p.pending_id
                WHERE p.identity_type=? AND p.identity_value=?
                """, (rs, row) -> new PendingRow(rs.getString(1), instant(rs.getTimestamp(2)),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), instant(rs.getTimestamp(8))),
                IDENTITY_TYPE, identity);
        if (rows.isEmpty()) {
            insert(observation, identity, roundAt);
            return;
        }
        PendingRow row = rows.get(0);
        if (!row.matches(key)) throw conflict("摘要身份与已保存厂家原始身份冲突");
        Instant observedAt = millis(observation.observedAt());
        if (observedAt.isAfter(row.observedAt())) {
            jdbc.update("""
                    UPDATE biz_pending_device
                    SET profile_code=?,last_seen_time=?,report_count=CASE
                        WHEN report_count<9223372036854775807 THEN report_count+1 ELSE report_count END,
                        latest_event_time=?,latest_metrics_json=?,update_time=CURRENT_TIMESTAMP
                    WHERE pending_id=?
                    """, profile(key.kind()), timestamp(observedAt),
                    timestamp(observedAt), sample(key.kind()), row.pendingId());
            jdbc.update("""
                    UPDATE biz_daikin_directory
                    SET equipment_id=?,site_name=?,device_name=?,observed_at=?,last_catalog_at=?,
                        missing=0,update_time=CURRENT_TIMESTAMP
                    WHERE pending_id=?
                    """, observation.equipmentId(), observation.siteName(), observation.deviceName(),
                    timestamp(observedAt), timestamp(roundAt), row.pendingId());
        } else {
            jdbc.update("""
                    UPDATE biz_daikin_directory SET last_catalog_at=?,missing=0,update_time=CURRENT_TIMESTAMP
                    WHERE pending_id=?
                    """, timestamp(roundAt), row.pendingId());
        }
    }

    private void insert(DaikinDeviceObservation observation, String identity, Instant roundAt) {
        DaikinDeviceKey key = observation.key();
        String pendingId = UUID.randomUUID().toString().replace("-", "");
        Timestamp observed = timestamp(millis(observation.observedAt()));
        jdbc.update("""
                INSERT INTO biz_pending_device
                  (pending_id,identity_type,identity_value,profile_code,last_profile_version,
                   first_seen_time,last_seen_time,report_count,latest_event_time,latest_time_source,
                   latest_metrics_json,sample_truncated,status,bound_identity_id,create_time,update_time)
                VALUES (?,?,?,?,2,?,?,1,?,'SERVER_RECEIVED',?,0,'DISCOVERED',NULL,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, pendingId, IDENTITY_TYPE, identity, profile(key.kind()), observed, observed,
                observed, sample(key.kind()));
        jdbc.update("""
                INSERT INTO biz_daikin_directory
                  (pending_id,identity_hash,source_id,site_id,controller_id,device_kind,unit_id,equipment_id,
                   site_name,device_name,observed_at,last_catalog_at,missing,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, pendingId, identity, key.sourceId(), key.siteId(), key.controllerId(), key.kind().name(),
                key.unitId(), observation.equipmentId(), observation.siteName(), observation.deviceName(),
                observed, timestamp(roundAt));
    }

    private ValidatedCatalog validateCatalog(String sourceId, DaikinDeviceKey.Kind kind,
                                              Instant roundAt,
                                              List<DaikinDeviceObservation> observations) {
        String validSource = requireText(sourceId, 200, "大金来源身份无效");
        Objects.requireNonNull(kind, "设备类型不能为空");
        if (roundAt == null || roundAt.isAfter(clock.instant())) {
            throw invalid("完整目录轮次时间无效");
        }
        roundAt = millis(roundAt);
        if (observations == null || observations.size() > MAX_DEVICES) {
            throw invalid("完整目录最多允许10000台设备");
        }
        Set<DaikinDeviceKey> keys = new HashSet<>();
        List<DaikinDeviceObservation> sorted = new ArrayList<>(observations.size());
        for (DaikinDeviceObservation observation : observations) {
            if (observation == null || !validSource.equals(observation.key().sourceId())
                    || kind != observation.key().kind() || !keys.add(observation.key())) {
                throw invalid("目录来源、类型或身份重复无效");
            }
            validateOptional(observation.equipmentId(), 200);
            validateOptional(observation.siteName(), 500);
            validateOptional(observation.deviceName(), 500);
            if (millis(observation.observedAt()).isAfter(roundAt)) {
                throw invalid("设备观察时间不能晚于完整目录轮次");
            }
            sorted.add(observation);
        }
        sorted.sort(Comparator.comparing(item -> canonical(item.key())));
        return new ValidatedCatalog(validSource, kind, List.copyOf(sorted), roundAt,
                hash(sorted.stream().map(DaikinDirectoryService::canonicalObservation).toList()));
    }

    private boolean hasBoundDirectory(String sourceId, String siteId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM biz_daikin_directory d
                JOIN biz_pending_device p ON p.pending_id=d.pending_id
                WHERE d.source_id=? AND d.site_id=?
                  AND (p.status='BOUND' OR p.bound_identity_id IS NOT NULL)
                """, Integer.class, sourceId, siteId);
        return count != null && count > 0;
    }

    private void lockSource(String sourceId) {
        List<String> rows = jdbc.queryForList(
                "SELECT source_id FROM biz_daikin_source WHERE source_id=? FOR UPDATE",
                String.class, sourceId);
        if (rows.size() != 1) throw notFound("大金来源未注册");
    }

    static String identityHash(DaikinDeviceKey key) {
        return hash(List.of(key.sourceId(), key.siteId(), key.controllerId(), key.kind().name(), key.unitId()));
    }

    private static String canonical(DaikinDeviceKey key) {
        return lengthPrefix(key.sourceId()) + lengthPrefix(key.siteId())
                + lengthPrefix(key.controllerId()) + lengthPrefix(key.kind().name())
                + lengthPrefix(key.unitId());
    }

    private static String canonicalObservation(DaikinDeviceObservation observation) {
        return canonical(observation.key()) + nullable(observation.equipmentId())
                + nullable(observation.siteName()) + nullable(observation.deviceName())
                + lengthPrefix(Long.toString(millis(observation.observedAt()).toEpochMilli()));
    }

    private static String nullable(String value) {
        return value == null ? "-1:" : lengthPrefix(value);
    }

    private static String hash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) digest.update(lengthPrefix(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM缺少SHA-256", exception);
        }
    }

    private static String lengthPrefix(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return bytes.length + ":" + value;
    }

    private static Instant millis(Instant value) {
        return value.truncatedTo(ChronoUnit.MILLIS);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(millis(value), MYSQL_ZONE));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().atZone(MYSQL_ZONE).toInstant();
    }

    private static String profile(DaikinDeviceKey.Kind kind) {
        return kind == DaikinDeviceKey.Kind.INDOOR ? "DAIKIN_INDOOR_V2" : "DAIKIN_OUTDOOR_V2";
    }

    private static String sample(DaikinDeviceKey.Kind kind) {
        return kind == DaikinDeviceKey.Kind.INDOOR ? SAMPLE_INDOOR : SAMPLE_OUTDOOR;
    }

    private static void validateOptional(String value, int maxLength) {
        if (value != null && (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl))) {
            throw invalid("目录展示字段无效");
        }
    }

    private static String requireText(String value, int maxLength, String message) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > maxLength || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(message);
        }
        return value;
    }

    private static void requireOperator(Long operatorId) {
        if (operatorId == null || operatorId <= 0) throw invalid("操作人无效");
    }

    private static void requireAdmin(Set<String> roles) {
        if (roles == null || roles.stream().noneMatch(ADMIN::equalsIgnoreCase)) {
            throw new BusinessException(403, "DAIKIN_DIRECTORY_FORBIDDEN", "仅平台管理员可维护大金来源映射");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, "DAIKIN_DIRECTORY_VALIDATION_FAILED", message);
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(404, "DAIKIN_DIRECTORY_NOT_FOUND", message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, "DAIKIN_DIRECTORY_CONFLICT", message);
    }

    public record DirectoryView(String pendingId, String sourceId, String siteId,
                                String controllerId, DaikinDeviceKey.Kind kind, String unitId,
                                String siteName, String deviceName, String equipmentId,
                                String buildingId, boolean missing, Instant observedAt) {
    }

    private record Mapping(String buildingId, int version) { }
    private record PendingIdentity(String type, String value) { }
    private record SyncState(Instant completedAt, String hash) { }
    private record ValidatedCatalog(String sourceId, DaikinDeviceKey.Kind kind,
                                    List<DaikinDeviceObservation> observations,
                                    Instant roundAt, String hash) { }
    private record PendingRow(String pendingId, Instant lastSeenAt, String sourceId,
                              String siteId, String controllerId, String kind, String unitId,
                              Instant observedAt) {
        boolean matches(DaikinDeviceKey key) {
            return Objects.equals(sourceId, key.sourceId()) && Objects.equals(siteId, key.siteId())
                    && Objects.equals(controllerId, key.controllerId())
                    && Objects.equals(kind, key.kind().name()) && Objects.equals(unitId, key.unitId());
        }
    }
}
