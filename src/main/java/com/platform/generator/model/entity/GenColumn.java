package com.platform.generator.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 已导入字段的代码生成配置，对应 {@code gen_column}。
 *
 * <p>一条记录同时保留导入时的数据库字段事实和管理员可调整的生成选项。当前 Java
 * 后端模板消费字段名、Java 类型、主键、逻辑删除和通用查询配置；列表、编辑、必填及
 * 组件类型仍作为配置元数据持久化，但不改变当前 ZIP 中的模板输出。</p>
 */
@Data
@TableName("gen_column")
public class GenColumn implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /** 字段配置主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属 {@link GenTable} 配置 ID。 */
    private Long tableId;
    /** 原数据库字段名。 */
    private String columnName;
    private String columnComment;
    /** JDBC/数据库原始类型。 */
    private String jdbcType;
    /** 完整 Java 类型名，例如 {@code java.math.BigDecimal}。 */
    private String javaType;
    /** 生成实体中的 Java 字段名。 */
    private String javaField;
    /** 以下布尔配置在数据库中使用 0/1 持久化。 */
    private Integer isPrimaryKey;
    private Integer isNullable;
    private Integer isLogicDelete;
    /** 是否用于列表展示。 */
    private Integer isList;
    /** 是否参与通用查询条件。 */
    private Integer isQuery;
    /** 查询方式，例如 EQ、LIKE 或 BETWEEN。 */
    private String queryType;
    private Integer isEdit;
    private Integer isRequired;
    /** 字段的表单组件配置，例如 TEXT、NUMBER、DATETIME；当前后端模板不消费该值。 */
    private String componentType;
    /** 字段生成顺序，默认沿用数据库字段顺序。 */
    private Integer sortOrder;
}
