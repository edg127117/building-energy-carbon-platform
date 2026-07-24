package com.platform.generator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.generator.model.entity.GenTable;
import org.apache.ibatis.annotations.Mapper;

/**
 * 生成表配置的数据访问接口。
 *
 * <p>一条记录描述一张业务表如何生成代码，例如包名、类名、权限和数据范围；
 * 具体字段配置由 {@link GenColumnMapper} 管理。</p>
 */
@Mapper
public interface GenTableMapper extends BaseMapper<GenTable> {}
