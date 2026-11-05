package com.platform.alarm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.framework.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.platform.alarm.AlarmModels.*;

/** 建筑级DIGITAL状态字典；历史版本只追加，规则快照始终按显式版本解析。 */
@Service
@Transactional
public class AlarmDictionaryService {
    private static final String CONFIG = "/configuration/rules/alarmRules";
    private final JdbcTemplate jdbc;
    private final AlarmAccess access;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public AlarmDictionaryService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, AlarmAccess access, ObjectMapper json) {
        this(jdbc, access, json, Clock.systemUTC());
    }

    public AlarmDictionaryService(JdbcTemplate jdbc, AlarmAccess access, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    public List<StateDictionary> list(Long user, Set<String> roles, String building) {
        access.require(user, roles, building, CONFIG, false);
        return jdbc.query("SELECT dictionary_id,dictionary_name,revision,states_json,updated_at,updated_by FROM biz_alarm_dictionary WHERE building_id=? ORDER BY dictionary_name,dictionary_id",
                (rs, n) -> dictionary(building, rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5), nullableLong(rs, 6)), building);
    }

    public String create(Long user, Set<String> roles, String building, SaveDictionary request) {
        access.require(user, roles, building, CONFIG, true);
        validate(request);
        if (request.revision() != null && request.revision() != 0) throw new BusinessException("新字典版本必须从1开始");
        String id = UUID.randomUUID().toString();
        long now = clock.millis();
        String states = encode(request.states());
        jdbc.update("INSERT INTO biz_alarm_dictionary(building_id,dictionary_id,dictionary_name,revision,states_json,updated_at,updated_by) VALUES(?,?,?,1,?,?,?)",
                building, id, request.name().trim(), states, now, user);
        insertVersion(building, id, request.name().trim(), 1, states, now, user);
        return id;
    }

    public void update(Long user, Set<String> roles, String building, String id, SaveDictionary request) {
        access.require(user, roles, building, CONFIG, true);
        validate(request);
        if (id == null || id.isBlank()) throw new BusinessException("字典标识无效");
        if (request.revision() == null) throw new BusinessException("请提供当前字典版本");
        DictionaryRow previous = current(building, id, true);
        if (previous.revision() != request.revision()) throw new BusinessException(409, "字典已更新，请刷新后重试");
        long revision = previous.revision() + 1;
        long now = clock.millis();
        String states = encode(request.states());
        int changed = jdbc.update("UPDATE biz_alarm_dictionary SET dictionary_name=?,revision=?,states_json=?,updated_at=?,updated_by=? WHERE building_id=? AND dictionary_id=? AND revision=?",
                request.name().trim(), revision, states, now, user, building, id, previous.revision());
        if (changed != 1) throw new BusinessException(409, "字典已更新，请刷新后重试");
        insertVersion(building, id, request.name().trim(), revision, states, now, user);
    }

    public StateDictionary getVersion(Long user, Set<String> roles, String building, String id, long revision) {
        access.require(user, roles, building, CONFIG, false);
        return getVersion(building, id, revision);
    }

    /** 已由主报警服务完成建筑授权的路径，用于冻结指定版本的状态展示快照。 */
    public StateDictionary getVersion(String building, String id, long revision) {
        List<StateDictionary> rows = jdbc.query("SELECT dictionary_name,states_json,updated_at,updated_by FROM biz_alarm_dictionary_version WHERE building_id=? AND dictionary_id=? AND revision=?",
                (rs, n) -> dictionary(building, id, rs.getString(1), revision, rs.getString(2), rs.getLong(3), nullableLong(rs, 4)), building, id, revision);
        if (rows.isEmpty()) throw new BusinessException("字典版本不存在或不属于当前建筑");
        return rows.getFirst();
    }

    public void bind(Long user, Set<String> roles, String building, String point, DictionaryBinding binding) {
        access.require(user, roles, building, CONFIG, true);
        if (point == null || point.isBlank() || binding == null) throw new BusinessException("测点字典绑定无效");
        updatePointBindings(building, Map.of(point, binding));
    }

    /** 返回当前测点绑定；空值表示未绑定。调用方须先完成建筑授权。 */
    public DictionaryBinding binding(String building, String point) {
        List<DictionaryBinding> rows = jdbc.query("SELECT dictionary_id,dictionary_revision FROM biz_alarm_point_dictionary WHERE building_id=? AND point_id=?",
                (rs, n) -> new DictionaryBinding(rs.getString(1), rs.getLong(2)), building, point);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** 将引用解析成规则内冻结的状态列表；旧规则的内嵌 states 原样保留。 */
    public Condition resolve(String building, Condition condition) {
        if (condition == null) throw new BusinessException("测点条件不能为空");
        if (condition.dictionaryId() == null || condition.dictionaryId().isBlank()) {
            if (condition.dictionaryRevision() != null) throw new BusinessException("字典标识和版本必须同时提供");
            return condition;
        }
        if (condition.dictionaryRevision() == null || condition.pointId() == null || condition.pointId().isBlank()) {
            throw new BusinessException("字典引用或测点标识无效");
        }
        StateDictionary version = getVersion(building, condition.dictionaryId(), condition.dictionaryRevision());
        return new Condition(condition.pointId(), condition.operator(), condition.value(), condition.upperValue(),
                version.states(), version.id(), version.revision());
    }

    /** 批量写入已鉴权规则流程确认过的测点绑定；只更新传入项，不删除其他绑定。 */
    void updatePointBindings(String building, Map<String, DictionaryBinding> bindings) {
        if (building == null || building.isBlank() || bindings == null || bindings.isEmpty()) return;
        for (Map.Entry<String, DictionaryBinding> entry : bindings.entrySet()) {
            String point = entry.getKey();
            DictionaryBinding binding = entry.getValue();
            if (point == null || point.isBlank() || binding == null || binding.dictionaryId() == null
                    || binding.dictionaryId().isBlank() || binding.dictionaryRevision() == null) {
                throw new BusinessException("测点字典绑定无效");
            }
            Long count = jdbc.queryForObject("SELECT COUNT(*) FROM biz_data_point p JOIN biz_equipment e ON e.equip_id=p.equip_id AND e.building_id=p.building_id AND e.del_flag=0 WHERE p.point_id=? AND p.building_id=? AND p.data_type='DIGITAL' AND p.status='ONLINE' AND p.del_flag=0",
                    Long.class, point, building);
            if (count == null || count == 0) throw new BusinessException("状态字典仅能绑定当前建筑内已上线DIGITAL测点");
            getVersion(building, binding.dictionaryId(), binding.dictionaryRevision());
            jdbc.update("INSERT INTO biz_alarm_point_dictionary(building_id,point_id,dictionary_id,dictionary_revision) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE dictionary_id=VALUES(dictionary_id),dictionary_revision=VALUES(dictionary_revision)",
                    building, point, binding.dictionaryId(), binding.dictionaryRevision());
        }
    }

    private DictionaryRow current(String building, String id, boolean lock) {
        List<DictionaryRow> rows = jdbc.query("SELECT dictionary_name,revision,states_json,updated_at,updated_by FROM biz_alarm_dictionary WHERE building_id=? AND dictionary_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new DictionaryRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), nullableLong(rs, 5)), building, id);
        if (rows.isEmpty()) throw new BusinessException("字典不存在或不属于当前建筑");
        return rows.getFirst();
    }

    private void insertVersion(String building, String id, String name, long revision, String states, long now, Long actor) {
        jdbc.update("INSERT INTO biz_alarm_dictionary_version(building_id,dictionary_id,revision,dictionary_name,states_json,updated_at,updated_by) VALUES(?,?,?,?,?,?,?)",
                building, id, revision, name, states, now, actor);
    }

    private void validate(SaveDictionary request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().trim().length() > 100) {
            throw new BusinessException("字典名称须为1至100字");
        }
        List<StateOption> states = request.states() == null ? List.of() : request.states();
        if (states.isEmpty() || states.size() > 32) throw new BusinessException("状态项须为1至32项");
        Set<BigDecimal> values = new TreeSet<>(BigDecimal::compareTo);
        Set<String> labels = new HashSet<>();
        for (StateOption state : states) {
            if (state == null || state.value() == null || state.label() == null || state.label().isBlank()
                    || state.label().trim().length() > 40) throw new BusinessException("状态编码不能为空，状态名称须为1至40字");
            if (!values.add(state.value())) throw new BusinessException("状态编码不可重复");
            if (!labels.add(state.label().trim())) throw new BusinessException("状态名称不可重复");
        }
    }

    private String encode(List<StateOption> states) {
        List<StateOption> normalized = states.stream().map(state -> new StateOption(state.value(), state.label().trim())).toList();
        try { return json.writeValueAsString(normalized); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("无法保存状态字典", failure); }
    }

    private StateDictionary dictionary(String building, String id, String name, long revision, String states, long updatedAt, Long updatedBy) {
        try {
            List<StateOption> options = states == null || states.isBlank() ? List.of() : json.readValue(states, new TypeReference<>() { });
            return new StateDictionary(id, name, revision, options, updatedAt, updatedBy);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("状态字典数据无法解析", failure);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record DictionaryRow(String name, long revision, String states, long updatedAt, Long updatedBy) { }
}
