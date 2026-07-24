package com.platform.generator.service;

import com.platform.generator.model.dto.GeneratorDtos.GeneratorConfigView;
import com.platform.generator.model.dto.GeneratorDtos.ImportTableRequest;
import com.platform.generator.model.dto.GeneratorDtos.TableSummary;
import com.platform.generator.model.dto.GeneratorDtos.UpdateGeneratorConfigRequest;
import com.platform.generator.model.meta.GeneratorMetadata.GenerationContext;

import java.util.List;

/**
 * V1 后端代码生成器的核心用例接口。
 *
 * <p>负责数据库表发现、生成配置生命周期和中立生成上下文构建；
 * 预览、ZIP 等具体输出形式由 {@code GenerationTarget} 负责。</p>
 */
public interface GeneratorService {
    /** 列出当前数据库可导入的业务表，并标记是否已经导入。 */
    List<TableSummary> listTables();

    /** 根据数据库元数据建立一套可编辑的默认生成配置。 */
    GeneratorConfigView importTable(ImportTableRequest request);

    /** 查询一套生成配置及其全部字段。 */
    GeneratorConfigView detail(Long id);

    /** 完整校验并更新表级、字段级生成配置。 */
    GeneratorConfigView update(Long id, UpdateGeneratorConfigRequest request);

    /** 删除生成配置，不删除也不修改真实业务表。 */
    void delete(Long id);

    /** 将持久化配置转换为经过校验、可供输出目标消费的中立上下文。 */
    GenerationContext buildContext(Long id);
}
