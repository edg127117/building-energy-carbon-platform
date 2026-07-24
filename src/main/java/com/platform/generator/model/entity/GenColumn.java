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
 * <p>一条记录同时保留数据库原始信息和可编辑的 Java/UI 配置。V1 只使用其中的
 * Java 类型、查询方式等后端属性；列表、编辑和组件属性为后续可视化及 V2 预留。</p>
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
    /** 预留的表单组件类型，例如 TEXT、NUMBER、DATETIME。 */
    private String componentType;
    /** 字段生成顺序，默认沿用数据库字段顺序。 */
    private Integer sortOrder;
}
