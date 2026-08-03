package com.platform.generator.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 已导入业务表的代码生成配置，对应 {@code gen_table}。
 *
 * <p>这里保存“整张表”级别的生成策略，不保存业务数据，也不会改变原业务表结构。
 * 角色列表使用 JSON 字符串持久化，由服务层统一校验和转换。</p>
 */
@Data
@TableName("gen_table")
public class GenTable implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    /** 生成配置主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 被导入的真实数据库表名。 */
    private String tableName;
    /** 数据库表注释，用于配置展示和生成说明。 */
    private String tableComment;
    /** Java 子模块名，会参与生成包路径。 */
    private String moduleName;
    /** 业务资源名，默认作为 Controller 请求路径。 */
    private String businessName;
    /** 生成的 Java 实体类名。 */
    private String className;
    /** 基础包名，例如 {@code com.platform}。 */
    private String packageName;
    /** MyBatis-Plus 主键策略：AUTO、ASSIGN_ID 或 INPUT。 */
    private String idType;
    /** 逻辑删除列名；为空表示不生成 {@code @TableLogic}。 */
    private String logicDeleteColumn;
    /** 数据范围类型：NONE 或 BUILDING。 */
    private String scopeType;
    /** BUILDING 数据范围对应的数据库字段名。 */
    private String scopeColumn;
    /** 允许读取的四类正式角色，JSON 数组格式。 */
    private String readRoles;
    /** 允许新增、修改和删除的四类正式角色，JSON 数组格式。 */
    private String writeRoles;
    /** 输出模式；当前唯一合法值为 JAVA_ZIP。 */
    private String generateMode;
    /** 配置状态：1 表示正常。 */
    private Integer status;
    private Date createTime;
    private Date updateTime;
}
