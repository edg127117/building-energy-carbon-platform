package com.platform.generator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.platform.framework.exception.BusinessException;
import com.platform.generator.mapper.GenColumnMapper;
import com.platform.generator.mapper.GenTableMapper;
import com.platform.generator.model.dto.GeneratorDtos.ColumnUpdate;
import com.platform.generator.model.dto.GeneratorDtos.GeneratorConfigView;
import com.platform.generator.model.dto.GeneratorDtos.ImportTableRequest;
import com.platform.generator.model.dto.GeneratorDtos.UpdateGeneratorConfigRequest;
import com.platform.generator.model.entity.GenColumn;
import com.platform.generator.service.GeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生成配置服务集成测试：使用 H2 的真实表结构验证导入、更新、校验和上下文构建。
 */
@SpringBootTest
@ActiveProfiles("test")
class GeneratorServiceTest {
    @Autowired private GeneratorService service;
    @Autowired private GenTableMapper tableMapper;
    @Autowired private GenColumnMapper columnMapper;

    /** 每个用例只清理生成配置，不清理或改动被发现的业务测试表。 */
    @BeforeEach
    void cleanGeneratorConfig() {
        columnMapper.delete(null);
        tableMapper.delete(null);
    }

    /** 验证设备表从发现、导入到 BUILDING 数据范围上下文的完整服务流程。 */
    @Test
    void should_import_table_update_scope_and_build_neutral_context() {
        GeneratorConfigView imported = service.importTable(new ImportTableRequest(
                "biz_equipment", "hvac", "equipment", "BizEquipment", "com.platform"));

        assertThat(imported.id()).isNotNull();
        assertThat(imported.idType()).isEqualTo("INPUT");
        assertThat(imported.logicDeleteColumn()).isEqualTo("del_flag");
        assertThat(imported.readRoles()).containsExactly("PLATFORM_ADMIN");
        assertThat(imported.columns()).extracting(c -> c.columnName()).contains("equip_id", "building_id", "del_flag");
        assertThat(imported.columns().stream().filter(c -> c.primaryKey()).map(c -> c.columnName()).toList())
                .containsExactly("equip_id");

        List<ColumnUpdate> columns = imported.columns().stream().map(column -> new ColumnUpdate(
                column.id(), column.javaType(), column.javaField(), column.list(),
                "equip_name".equals(column.columnName()),
                "equip_name".equals(column.columnName()) ? "LIKE" : column.queryType(),
                column.edit(), column.required(), column.componentType(), column.sortOrder())).toList();
        GeneratorConfigView updated = service.update(imported.id(), new UpdateGeneratorConfigRequest(
                "hvac", "equipment", "BizEquipment", "com.platform", "INPUT", "del_flag",
                "BUILDING", "building_id", List.of("BUILDING_OWNER", "ENERGY_MANAGER", "PLATFORM_ADMIN"),
                List.of("PLATFORM_ADMIN"), columns));

        assertThat(updated.scopeType()).isEqualTo("BUILDING");
        assertThat(service.buildContext(imported.id()).table().dataScope().type()).isEqualTo("BUILDING");
        assertThat(service.buildContext(imported.id()).primaryKey().javaField()).isEqualTo("equipId");
        assertThat(service.buildContext(imported.id()).imports()).contains("java.math.BigDecimal", "java.util.Date");
        assertThat(columnMapper.selectCount(new LambdaQueryWrapper<GenColumn>()
                .eq(GenColumn::getTableId, imported.id()))).isEqualTo(imported.columns().size());

        assertThatThrownBy(() -> service.importTable(new ImportTableRequest(
                "biz_equipment", "hvac", "equipment", "BizEquipment", "com.platform")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已经导入");
    }

    /** 建筑范围字段必须真实存在，避免生成后绕过或错误应用数据权限。 */
    @Test
    void should_reject_invalid_building_scope_column() {
        GeneratorConfigView imported = service.importTable(new ImportTableRequest(
                "biz_data_point", "hvac", "dataPoint", "BizDataPoint", "com.platform"));
        List<ColumnUpdate> columns = imported.columns().stream().map(column -> new ColumnUpdate(
                column.id(), column.javaType(), column.javaField(), column.list(), column.query(),
                column.queryType(), column.edit(), column.required(), column.componentType(), column.sortOrder())).toList();

        assertThatThrownBy(() -> service.update(imported.id(), new UpdateGeneratorConfigRequest(
                "hvac", "dataPoint", "BizDataPoint", "com.platform", "INPUT", "del_flag",
                "BUILDING", "missing_building_id", List.of("PLATFORM_ADMIN"),
                List.of("PLATFORM_ADMIN"), columns)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("范围字段不存在");
    }
}
