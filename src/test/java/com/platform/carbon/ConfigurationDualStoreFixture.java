package com.platform.carbon;

import com.platform.config.TdengineConfig;
import com.platform.config.TdengineProperties;
import com.platform.energy.classification.ClassificationContracts.Reading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 双库验收的合成夹具。所有目录、关系和因子均只用于隔离测试事实，
 * 不是现场档案、设备读数或专业排放因子结论。
 */
final class ConfigurationDualStoreFixture {
    static final long OPERATOR = 98101L;
    static final long REVIEWER = 98102L;
    static final String BUILDING = "CDUAL_B1";
    static final String OTHER_BUILDING = "CDUAL_B2";
    static final String RELATION_VERSION = "CDUAL_REL_V1";
    static final String BOUNDARY = "CDUAL_BOUNDARY";
    static final String SPACE = "CDUAL_SPACE";
    static final String MAIN = "CDUAL_MAIN";
    static final String SUB_600 = "CDUAL_SUB600";
    static final String SUB_300 = "CDUAL_SUB300";
    static final String TD_FAILURE = "CDUAL_TDFAIL";
    static final String MYSQL_ROLLBACK = "CDUAL_ROLLBACK";
    static final String PENDING = "CDUAL_PENDING";
    static final String ITEM = "DUAL_ELECTRICITY";
    static final String GAS = "CDUAL_GAS";
    static final String HEAT = "CDUAL_HEAT";
    static final String UNIT = "KWH";
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final LocalDate MONTH = LocalDate.of(2025, 1, 1);
    static final Instant START = MONTH.atStartOfDay(ZONE).toInstant();
    static final Instant END = MONTH.plusMonths(1).atStartOfDay(ZONE).toInstant();

    private static final Pattern MYSQL_URL = Pattern.compile(
            "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/iot_platform(?:\\?.*)?");
    private static final Pattern TD_URL = Pattern.compile(
            "jdbc:TAOS-RS://127\\.0\\.0\\.1(?::[0-9]+)?(?:[/?].*)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");
    private static final AtomicBoolean MYSQL_EMPTY_VALIDATED = new AtomicBoolean();

    final JdbcTemplate mysql;
    final JdbcTemplate taos;
    final TdengineProperties td;

    ConfigurationDualStoreFixture(JdbcTemplate mysql, JdbcTemplate taos, TdengineProperties td) {
        this.mysql = mysql;
        this.taos = taos;
        this.td = td;
    }

    static void requireEnvironment() {
        if (!"true".equals(System.getenv("CONFIGURATION_DUAL_IT_ISOLATED"))) {
            throw new IllegalStateException("双库验收必须显式设置 CONFIGURATION_DUAL_IT_ISOLATED=true");
        }
        String mysql = required("CONFIGURATION_DUAL_IT_MYSQL_URL");
        String tdUrl = required("CONFIGURATION_DUAL_IT_TD_URL");
        required("CONFIGURATION_DUAL_IT_MYSQL_USER");
        present("CONFIGURATION_DUAL_IT_MYSQL_PASSWORD");
        required("CONFIGURATION_DUAL_IT_TD_USER");
        present("CONFIGURATION_DUAL_IT_TD_PASSWORD");
        String database = required("CONFIGURATION_DUAL_IT_TD_DB");
        if (!MYSQL_URL.matcher(mysql).matches()) {
            throw new IllegalStateException("双库验收只允许本机 127.0.0.1 的 iot_platform MySQL");
        }
        if (!TD_URL.matcher(tdUrl).matches()) {
            throw new IllegalStateException("双库验收只允许本机 127.0.0.1 的 TDengine REST JDBC 地址");
        }
        if (!database.startsWith("cfg_dual_") || !IDENTIFIER.matcher(database).matches()) {
            throw new IllegalStateException("双库验收 TD 数据库必须是 cfg_dual_ 前缀的合法标识符");
        }
        assertEmptyMySqlOnce(mysql, System.getenv("CONFIGURATION_DUAL_IT_MYSQL_USER"),
                System.getenv("CONFIGURATION_DUAL_IT_MYSQL_PASSWORD"));
    }

    static String mysqlUrl() { return required("CONFIGURATION_DUAL_IT_MYSQL_URL"); }
    static String mysqlUser() { return required("CONFIGURATION_DUAL_IT_MYSQL_USER"); }
    static String mysqlPassword() { return present("CONFIGURATION_DUAL_IT_MYSQL_PASSWORD"); }
    static String tdUrl() { return required("CONFIGURATION_DUAL_IT_TD_URL"); }
    static String tdUser() { return required("CONFIGURATION_DUAL_IT_TD_USER"); }
    static String tdPassword() { return present("CONFIGURATION_DUAL_IT_TD_PASSWORD"); }
    static String tdDatabase() { return required("CONFIGURATION_DUAL_IT_TD_DB"); }

    void assertMigratedTo83() {
        String version = mysql.queryForObject("""
                SELECT version FROM flyway_schema_history
                WHERE success=1 ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
        if (!"83".equals(version)) {
            throw new AssertionError("双库验收应使用当前迁移链 V83，实际为 " + version);
        }
        Integer columns = mysql.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema=DATABASE()
                  AND table_name='biz_energy_period_result_current'
                  AND index_name='uk_energy_period_current_scope'
                """, Integer.class);
        if (!Integer.valueOf(5).equals(columns)) {
            throw new AssertionError("周期当前投影的建筑/测点/月度唯一索引缺失");
        }
    }

    void initializeTdEnergyStore() {
        String database = identifier(td.getDatabase());
        taos.execute("CREATE DATABASE IF NOT EXISTS " + database + " KEEP 3650 DURATION 10d WAL_LEVEL 1 VGROUPS 1");
        List<Map<String, Object>> tables = taos.queryForList("SHOW " + database + ".TABLES");
        List<Map<String, Object>> stables = taos.queryForList("SHOW " + database + ".STABLES");
        if (!tables.isEmpty() || !stables.isEmpty()) {
            throw new AssertionError("调用方必须提供空的 cfg_dual_ TDengine 数据库");
        }
        ReflectionTestUtils.invokeMethod(new TdengineConfig(td), "initializeEnergyPeriodSchema", taos);
    }

    void recreateTdEnergyStore() {
        ReflectionTestUtils.invokeMethod(new TdengineConfig(td), "initializeEnergyPeriodSchema", taos);
    }

    void dropTdEnergyStore() {
        taos.execute("DROP STABLE " + qualifiedStable());
    }

    long tdRowsForPoint(String pointId) {
        Long rows = taos.queryForObject("SELECT COUNT(*) FROM " + qualifiedStable()
                + " WHERE point_id='" + quoted(pointId) + "'", Long.class);
        return rows == null ? 0 : rows;
    }

    DecimalEvidence tdEvidence(String resultKey) {
        List<Map<String, Object>> rows = taos.queryForList("SELECT native_quantity_decimal,"
                + "tce_value_decimal FROM " + qualifiedStable() + " WHERE result_key='"
                + quoted(resultKey) + "'");
        if (rows.size() != 1) {
            throw new AssertionError("TDengine 应只有一条确定性周期数值，实际=" + rows.size());
        }
        Map<String, Object> row = rows.getFirst();
        return new DecimalEvidence(binary(row, "native_quantity_decimal"),
                nullableBinary(row, "tce_value_decimal"));
    }

    void seedSyntheticBase() {
        insertBuilding(BUILDING, "双库合成验收建筑");
        insertBuilding(OTHER_BUILDING, "跨楼范围合成建筑");
        grantDuties();
        insertPointNamingRule();
        insertSpace();
        insertPoints();
        insertApprovedEnergyCatalogAndBindings();
        insertEffectiveRelation();
        insertAdditionalEnergy(GAS, "DUAL_GAS", "M3", "NATURAL_GAS", "STATIONARY_COMBUSTION");
        insertAdditionalEnergy(HEAT, "DUAL_HEAT", "GJ", "HEAT", "PURCHASED_HEAT");
        insertSyntheticConfirmedFactors();
    }

    Reading reading(String pointId, String quantity, String status, String supersedesFactId) {
        String item = GAS.equals(pointId) ? "DUAL_GAS" : HEAT.equals(pointId) ? "DUAL_HEAT" : ITEM;
        String unit = GAS.equals(pointId) ? "M3" : HEAT.equals(pointId) ? "GJ" : UNIT;
        return new Reading(pointId, START, END, item, unit, "PERIOD_TOTAL", null, null,
                new BigDecimal(quantity), BigDecimal.ONE, false, status,
                "合成夹具人工期间量，不代表现场读数", "EXACT", supersedesFactId);
    }

    private void insertBuilding(String building, String name) {
        mysql.update("""
                INSERT INTO building
                (building_id,building_name,building_type,total_gfa,climate_zone,region_code)
                VALUES (?,?, '办公',1,'夏热冬冷','330100')
                """, building, name);
    }

    private void insertAdditionalEnergy(String point, String energy, String unit,
                                        String category, String pathway) {
        mysql.update("""
                INSERT INTO biz_data_point
                (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                 component_code,suffix_code,data_type,unit,is_for_calc,status)
                VALUES (?,?,?,?, 'CDUAL_RULE','DUAL','ENERGY',?,'DECIMAL',?,1,'ONLINE')
                """, point, point, "合成" + point, BUILDING, point, unit);
        mysql.update("INSERT INTO biz_energy_item(item_id,item_code,created_by) VALUES (?,?,?)",
                point + "_EI", energy, OPERATOR);
        mysql.update("""
                INSERT INTO biz_energy_item_version
                (version_id,item_id,version_no,item_name,compatible_category,status,source_type,
                 source_reference,effective_from,config_revision,created_by,approved_by,approved_at)
                VALUES (?,?,1,?,?,'APPROVED','MANUAL','合成夹具已确认目录，非现场规则',
                        '2000-01-01',0,?,?,CURRENT_TIMESTAMP(3))
                """, point + "_EIV", point + "_EI", energy, category, OPERATOR, REVIEWER);
        mysql.update("INSERT INTO biz_energy_item_version_scope(version_id,usage_scope) VALUES (?,?)",
                point + "_EIV", pathway);
        mysql.update("""
                INSERT INTO biz_energy_point_item_binding(binding_id,building_id,point_id,created_by)
                VALUES (?,?,?,?)
                """, point + "_B", BUILDING, point, OPERATOR);
        mysql.update("""
                INSERT INTO biz_energy_point_item_binding_version
                (binding_version_id,binding_id,binding_version,energy_item_version_id,
                 effective_from,confirmation_status,evidence_reference,config_revision,
                 created_by,approved_by,approved_at)
                VALUES (?,?,1,?,'2000-01-01','CONFIRMED','合成夹具已确认绑定',0,?,?,CURRENT_TIMESTAMP(3))
                """, point + "_BV", point + "_B", point + "_EIV", OPERATOR, REVIEWER);
        node(point + "_N", "POINT", point);
        node(point + "_BN", "METERING_BOUNDARY", point + "_BOUNDARY");
        mysql.update("""
                INSERT INTO biz_metering_boundary
                (boundary_id,building_id,boundary_code,boundary_name,energy_type,
                 confirmation_status,evidence_reference,status,create_by,update_by)
                VALUES (?,?,?,?,?,'CONFIRMED','合成夹具已确认边界','ACTIVE',?,?)
                """, point + "_BOUNDARY", BUILDING, point, "合成" + energy, energy, OPERATOR, OPERATOR);
        mysql.update("""
                INSERT INTO biz_metering_assignment_version_item
                (assignment_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 target_node_id,allocation_status,evidence_reference,create_by)
                VALUES (?,?,?,?,?,?,'ASSIGNED','合成夹具已确认分配',?)
                """, point + "_A", RELATION_VERSION, BUILDING, point + "_BOUNDARY",
                point + "_N", "CDUAL_N_SPACE", OPERATOR);
        mysql.update("""
                INSERT INTO biz_meter_structure_version_item
                (structure_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 meter_role,meter_direction,confirmation_status,evidence_reference,
                 description,source_type,create_by,update_by)
                VALUES (?,?,?,?,?,'MAIN','INBOUND','CONFIRMED','合成夹具已确认总表',
                        '双库合成能源结构','MANUAL',?,?)
                """, point + "_S", RELATION_VERSION, BUILDING, point + "_BOUNDARY",
                point + "_N", OPERATOR, OPERATOR);
    }

    private void grantDuties() {
        List<String> operator = List.of(
                "ENERGY_RULE_MAINTAIN", "ENERGY_CALCULATION_RUN", "ENERGY_LOCK_SUBMIT",
                "CARBON_RULE_MAINTAIN", "CARBON_RULE_ACTIVATE", "CARBON_CALCULATION_RUN",
                "CARBON_RECALCULATION_TRIGGER");
        List<String> reviewer = List.of(
                "ENERGY_RULE_REVIEW", "ENERGY_EXCEPTION_APPROVE", "ENERGY_LOCK_APPROVE",
                "CARBON_RULE_REVIEW", "CARBON_RECALCULATION_APPROVE");
        operator.forEach(duty -> grant(OPERATOR, duty));
        reviewer.forEach(duty -> grant(REVIEWER, duty));
    }

    private void grant(long userId, String duty) {
        mysql.update("""
                INSERT INTO sys_user_backend_duty
                (assignment_id,user_id,duty_key,status,effective_at,created_by)
                VALUES (?,?,?,'ACTIVE','2024-01-01 00:00:00',?)
                """, id(), userId, duty, OPERATOR);
    }

    private void insertPointNamingRule() {
        mysql.update("""
                INSERT INTO biz_point_naming_rule
                (rule_id,standard_version,family_code,component_code,code_template,standard_source,status)
                VALUES ('CDUAL_RULE','SYNTHETIC','DUAL','ENERGY','CDUAL_[n]','TEST_FIXTURE',1)
                """);
    }

    private void insertSpace() {
        mysql.update("""
                INSERT INTO biz_space
                (space_id,building_id,parent_space_id,space_name,space_code,space_type)
                VALUES (?,?,NULL,'合成空间','CDUAL-S','ROOM')
                """, SPACE, BUILDING);
    }

    private void insertPoints() {
        for (String point : List.of(MAIN, SUB_600, SUB_300, TD_FAILURE, MYSQL_ROLLBACK, PENDING)) {
            mysql.update("""
                    INSERT INTO biz_data_point
                    (point_id,point_code,point_name,building_id,naming_rule_id,family_code,
                     component_code,suffix_code,data_type,unit,is_for_calc,status)
                    VALUES (?,?,?,?, 'CDUAL_RULE','DUAL','ENERGY',?,'DECIMAL',?,1,'ONLINE')
                    """, point, point, "合成" + point, BUILDING, point, UNIT);
        }
    }

    private void insertApprovedEnergyCatalogAndBindings() {
        mysql.update("INSERT INTO biz_energy_item(item_id,item_code,created_by) VALUES ('CDUAL_EI',?,?)",
                ITEM, OPERATOR);
        mysql.update("""
                INSERT INTO biz_energy_item_version
                (version_id,item_id,version_no,item_name,compatible_category,status,source_type,
                 source_reference,effective_from,config_revision,created_by,approved_by,approved_at)
                VALUES ('CDUAL_EIV','CDUAL_EI',1,'双库合成电力','ELECTRICITY','APPROVED','MANUAL',
                        '合成夹具已确认目录事实，非现场专业目录','2000-01-01',0,?,?,CURRENT_TIMESTAMP(3))
                """, OPERATOR, REVIEWER);
        mysql.update("""
                INSERT INTO biz_energy_item_version_scope(version_id,usage_scope)
                VALUES ('CDUAL_EIV','PURCHASED_ELECTRICITY')
                """);
        for (String point : List.of(MAIN, SUB_600, SUB_300, TD_FAILURE, MYSQL_ROLLBACK)) {
            String binding = "B" + point.substring("CDUAL_".length());
            String version = "V" + point.substring("CDUAL_".length());
            mysql.update("""
                    INSERT INTO biz_energy_point_item_binding
                    (binding_id,building_id,point_id,created_by)
                    VALUES (?,?,?,?)
                    """, binding, BUILDING, point, OPERATOR);
            mysql.update("""
                    INSERT INTO biz_energy_point_item_binding_version
                    (binding_version_id,binding_id,binding_version,energy_item_version_id,
                     effective_from,confirmation_status,evidence_reference,config_revision,
                     created_by,approved_by,approved_at)
                    VALUES (?,?,1,'CDUAL_EIV','2000-01-01','CONFIRMED',
                            '合成夹具已确认测点绑定，非现场证据',0,?,?,CURRENT_TIMESTAMP(3))
                    """, version, binding, OPERATOR, REVIEWER);
        }
    }

    private void insertEffectiveRelation() {
        mysql.update("""
                INSERT INTO biz_relation_model
                (model_id,scope_type,scope_id,building_id,governance_mode,active_version_id,
                 draft_version_id,config_revision,create_by,update_by)
                VALUES ('CDUAL_MODEL','BUILDING',?,?, 'GOVERNED',NULL,NULL,1,?,?)
                """, BUILDING, BUILDING, OPERATOR, OPERATOR);
        mysql.update("""
                INSERT INTO biz_relation_version
                (version_id,model_id,building_id,version_no,status,config_revision,change_reason,
                 created_by,submitted_by,approved_by,activated_by,effective_at)
                VALUES (?, 'CDUAL_MODEL',?,1,'EFFECTIVE',1,'合成夹具已审核关系快照',?,?,?,?, '2024-01-01 00:00:00')
                """, RELATION_VERSION, BUILDING, OPERATOR, OPERATOR, REVIEWER, REVIEWER);
        mysql.update("UPDATE biz_relation_model SET active_version_id=? WHERE model_id='CDUAL_MODEL'",
                RELATION_VERSION);
        node("CDUAL_N_SPACE", "SPACE", SPACE);
        node("CDUAL_N_MAIN", "POINT", MAIN);
        node("CDUAL_N_SUB600", "POINT", SUB_600);
        node("CDUAL_N_SUB300", "POINT", SUB_300);
        node("CDUAL_N_BOUNDARY", "METERING_BOUNDARY", BOUNDARY);
        mysql.update("""
                INSERT INTO biz_metering_boundary
                (boundary_id,building_id,boundary_code,boundary_name,energy_type,
                 confirmation_status,evidence_reference,status,create_by,update_by)
                VALUES (?,?,'CDUAL-B','合成计量边界',?,'CONFIRMED',
                        '合成夹具已确认边界，非现场计量结论','ACTIVE',?,?)
                """, BOUNDARY, BUILDING, ITEM, OPERATOR, OPERATOR);
        assignment("CDUAL_A_MAIN", "CDUAL_N_MAIN");
        assignment("CDUAL_A_SUB600", "CDUAL_N_SUB600");
        assignment("CDUAL_A_SUB300", "CDUAL_N_SUB300");
        structure("CDUAL_S_MAIN", "CDUAL_N_MAIN", "MAIN", null);
        structure("CDUAL_S_SUB600", "CDUAL_N_SUB600", "SUB", "CDUAL_N_MAIN");
        structure("CDUAL_S_SUB300", "CDUAL_N_SUB300", "SUB", "CDUAL_N_MAIN");
    }

    private void node(String id, String type, String object) {
        mysql.update("""
                INSERT INTO biz_relation_node
                (node_id,building_id,node_type,business_object_id,status,create_by,update_by)
                VALUES (?,?,?,?,'ACTIVE',?,?)
                """, id, BUILDING, type, object, OPERATOR, OPERATOR);
    }

    private void assignment(String item, String pointNode) {
        mysql.update("""
                INSERT INTO biz_metering_assignment_version_item
                (assignment_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 target_node_id,allocation_status,evidence_reference,create_by)
                VALUES (?,?,?,?,?,?,'ASSIGNED','合成夹具已确认分配，非现场证据',?)
                """, item, RELATION_VERSION, BUILDING, BOUNDARY, pointNode, "CDUAL_N_SPACE", OPERATOR);
    }

    private void structure(String id, String pointNode, String role, String parent) {
        mysql.update("""
                INSERT INTO biz_meter_structure_version_item
                (structure_item_id,version_id,building_id,metering_boundary_id,meter_point_node_id,
                 meter_role,parent_meter_point_node_id,meter_direction,confirmation_status,
                 evidence_reference,description,source_type,create_by,update_by)
                VALUES (?,?,?,?,?,?,?,'INBOUND','CONFIRMED',
                        '合成夹具已确认表计结构，非现场证据','双库验收合成结构','MANUAL',?,?)
                """, id, RELATION_VERSION, BUILDING, BOUNDARY, pointNode, role, parent,
                OPERATOR, OPERATOR);
    }

    private void insertSyntheticConfirmedFactors() {
        String source = "CDUAL_FACTOR_SRC";
        String sourceVersion = "CDUAL_FACTOR_SV";
        mysql.update("""
                INSERT INTO biz_carbon_factor_source(source_id,source_code,created_by)
                VALUES (?,?,?)
                """, source, source, OPERATOR);
        mysql.update("""
                INSERT INTO biz_carbon_factor_source_version
                (source_version_id,source_id,version_no,source_name,publisher,document_reference,
                 applicability_note,evidence_reference,usage_nature,created_by)
                VALUES (?, ?,1,'SYNTHETIC CONFIRMED TEST RULE','dual-store fixture',
                        'not a business factor','isolated software acceptance only',
                        'synthetic fixture evidence','FORMAL',?)
                """, sourceVersion, source, OPERATOR);
        factor("CDUAL_F_ELEC", ITEM, "SCOPE_2", "PURCHASED_ELECTRICITY_LOCATION",
                "CO2E_DIRECT", "CO2e", UNIT, "CFV_ELECTRICITY_CO2E_V1", "0.5", sourceVersion);
        factor("CDUAL_F_HEAT", "DUAL_HEAT", "SCOPE_2", "PURCHASED_HEAT",
                "CO2E_DIRECT", "CO2e", "GJ", "CFV_HEAT_CO2E_V1", "100", sourceVersion);
        stationaryFactor(sourceVersion);
    }

    private void factor(String code, String energy, String scope, String category, String basis,
                        String gas, String unit, String formula, String value, String sourceVersion) {
        String factor = code + "_ID";
        String version = code + "_V1";
        mysql.update("""
                INSERT INTO biz_carbon_factor
                (factor_id,factor_code,scope_type,energy_item_code,factor_category,result_basis,
                 gas_code,gas_coverage,created_by)
                VALUES (?,?,?,?,?,?,?,'SYNTHETIC_TEST',?)
                """, factor, code, scope, energy, category, basis, gas, OPERATOR);
        mysql.update("""
                INSERT INTO biz_carbon_factor_version
                (factor_version_id,factor_id,version_no,source_version_id,applicability_level,
                 input_unit_code,usage_nature,status,effective_from,formula_version_id,
                 rounding_policy_version_id,created_by,reviewed_by,activated_by)
                VALUES (?,?,1,?,'NATIONAL',?,'FORMAL','ACTIVE','2000-01-01',
                        ?,'CRP_DECIMAL128_V1',?,?,?)
                """, version, factor, sourceVersion, unit, formula, OPERATOR, REVIEWER, REVIEWER);
        mysql.update("""
                INSERT INTO biz_carbon_factor_component
                (component_id,factor_version_id,component_type,component_value,component_unit,
                 source_version_id,evidence_reference)
                VALUES (?,?,'DIRECT_EMISSION_FACTOR',?,?,?,
                        'synthetic confirmed test fixture; not field factor')
                """, code + "_C", version, new BigDecimal(value), "KG_CO2E/" + unit, sourceVersion);
    }

    private void stationaryFactor(String sourceVersion) {
        String factor = "CDUAL_F_GAS_ID";
        String version = "CDUAL_F_GAS_V1";
        mysql.update("""
                INSERT INTO biz_carbon_factor
                (factor_id,factor_code,scope_type,energy_item_code,factor_category,result_basis,
                 gas_code,gas_coverage,created_by)
                VALUES (?,'CDUAL_F_GAS','SCOPE_1','DUAL_GAS','STATIONARY_COMBUSTION',
                        'GAS_MASS','CO2','SYNTHETIC_TEST',?)
                """, factor, OPERATOR);
        mysql.update("""
                INSERT INTO biz_carbon_factor_version
                (factor_version_id,factor_id,version_no,source_version_id,applicability_level,
                 input_unit_code,usage_nature,status,effective_from,formula_version_id,
                 rounding_policy_version_id,created_by,reviewed_by,activated_by)
                VALUES (?,?,1,?,'NATIONAL','M3','FORMAL','ACTIVE','2000-01-01',
                        'CFV_STATIONARY_CO2_V1','CRP_DECIMAL128_V1',?,?,?)
                """, version, factor, sourceVersion, OPERATOR, REVIEWER, REVIEWER);
        component(version, "CDUAL_F_GAS_LHV", "LOWER_HEATING_VALUE", "1", "MJ/M3", sourceVersion);
        component(version, "CDUAL_F_GAS_C", "CARBON_CONTENT_PER_HEAT", "1", "KG_C_PER_MJ", sourceVersion);
        component(version, "CDUAL_F_GAS_O", "OXIDATION_RATE", "1", "RATIO", sourceVersion);
    }

    private void component(String version, String id, String type, String value, String unit,
                           String sourceVersion) {
        mysql.update("""
                INSERT INTO biz_carbon_factor_component
                (component_id,factor_version_id,component_type,component_value,component_unit,
                 source_version_id,evidence_reference)
                VALUES (?,?,?,?,?,?, 'synthetic confirmed test fixture; not field factor')
                """, id, version, type, new BigDecimal(value), unit, sourceVersion);
    }

    private String qualifiedStable() {
        return "`" + identifier(td.getDatabase()) + "`.`" + identifier(td.getStEnergyPeriodResult()) + "`";
    }

    private static String binary(Map<String, Object> row, String column) {
        Object value = value(row, column);
        if (value == null) throw new AssertionError(column + " 不应为 null");
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    private static String nullableBinary(Map<String, Object> row, String column) {
        Object value = value(row, column);
        return value == null ? null : value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
    }

    private static Object value(Map<String, Object> row, String expected) {
        return row.entrySet().stream().filter(entry -> expected.equalsIgnoreCase(entry.getKey()))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }

    private static String required(String name) {
        String value = present(name);
        if (value.isBlank()) throw new IllegalStateException(name + " 必须非空");
        return value;
    }

    private static String present(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " 必须显式设置");
        return value;
    }

    private static void assertEmptyMySqlOnce(String url, String user, String password) {
        if (!MYSQL_EMPTY_VALIDATED.compareAndSet(false, true)) return;
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
                if (!result.next() || !"iot_platform".equals(result.getString(1))) {
                    throw new IllegalStateException("双库验收 MySQL 必须连接 iot_platform");
                }
            }
            try (ResultSet result = statement.executeQuery("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema IN ('iot_platform','iot_platform_archive')
                    """)) {
                if (!result.next() || result.getLong(1) != 0) {
                    throw new IllegalStateException("双库验收只允许一次性空的项目和归档 MySQL schema");
                }
            }
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("无法确认隔离 MySQL 环境", exception);
        }
    }

    private static String identifier(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("TDengine 标识符无效");
        }
        return value;
    }

    private static String quoted(String value) {
        return value.replace("'", "''");
    }

    static String id() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    record DecimalEvidence(String nativeDecimal, String tceDecimal) { }
}
