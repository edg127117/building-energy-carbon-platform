package com.platform.generator.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.platform.generator.model.entity.GenColumn;
import org.apache.ibatis.annotations.Mapper;

/**
 * 生成字段配置的数据访问接口。
 *
 * <p>使用 MyBatis-Plus 提供的通用 CRUD，保存每张已导入业务表的字段映射、
 * 查询方式和表单属性；它只操作 {@code gen_column}，不会修改真实业务表。</p>
 */
@Mapper
public interface GenColumnMapper extends BaseMapper<GenColumn> {}
