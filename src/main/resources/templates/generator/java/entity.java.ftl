<#-- V1 Java 实体模板：根据已校验的字段元数据生成 MyBatis-Plus 实体。 -->
package ${basePackage}.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
<#if table.logicDeleteColumn??>
import com.baomidou.mybatisplus.annotation.TableLogic;
</#if>
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
<#list imports as importType>
import ${importType};
</#list>

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据表 {@code ${table.tableName}} 对应的持久化实体。
 * 本文件由代码生成器创建，合并到项目后可按具体业务补充校验规则。
 */
@Data
@TableName("${table.tableName}")
public class ${table.className} implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

<#list table.columns as column>
    <#if column.columnComment??>/** ${column.columnComment} */<#else>/** 数据库字段 {@code ${column.columnName}}。 */</#if>
    <#if column.primaryKey>@TableId(type = IdType.${table.idType})
    </#if><#if column.logicDelete>@TableLogic
    </#if>private ${column.javaType} ${column.javaField};

</#list>}
