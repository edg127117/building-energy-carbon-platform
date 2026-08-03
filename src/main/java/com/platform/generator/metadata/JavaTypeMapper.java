package com.platform.generator.metadata;

import com.platform.framework.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 数据库字段类型到 Java 类型的白名单映射器。
 *
 * <p>对未知类型选择明确报错，而不是猜测为 {@code Object}，避免生成代码虽然能输出，
 * 却在序列化、查询或数据库写入时产生隐蔽错误。</p>
 */
@Component
public class JavaTypeMapper {
    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("tinyint", "java.lang.Integer"),
            Map.entry("smallint", "java.lang.Integer"),
            Map.entry("mediumint", "java.lang.Integer"),
            Map.entry("int", "java.lang.Integer"),
            Map.entry("integer", "java.lang.Integer"),
            Map.entry("bigint", "java.lang.Long"),
            Map.entry("decimal", "java.math.BigDecimal"),
            Map.entry("numeric", "java.math.BigDecimal"),
            Map.entry("float", "java.lang.Float"),
            Map.entry("double", "java.lang.Double"),
            Map.entry("real", "java.lang.Double"),
            Map.entry("char", "java.lang.String"),
            Map.entry("character", "java.lang.String"),
            Map.entry("varchar", "java.lang.String"),
            Map.entry("character varying", "java.lang.String"),
            Map.entry("longvarchar", "java.lang.String"),
            Map.entry("text", "java.lang.String"),
            Map.entry("tinytext", "java.lang.String"),
            Map.entry("mediumtext", "java.lang.String"),
            Map.entry("longtext", "java.lang.String"),
            Map.entry("json", "java.lang.String"),
            Map.entry("date", "java.util.Date"),
            Map.entry("datetime", "java.util.Date"),
            Map.entry("timestamp", "java.util.Date"),
            Map.entry("timestamp without time zone", "java.util.Date"),
            Map.entry("time", "java.util.Date"),
            Map.entry("bit", "java.lang.Boolean"),
            Map.entry("boolean", "java.lang.Boolean"),
            Map.entry("bool", "java.lang.Boolean"),
            Map.entry("blob", "byte[]"),
            Map.entry("tinyblob", "byte[]"),
            Map.entry("mediumblob", "byte[]"),
            Map.entry("longblob", "byte[]"),
            Map.entry("binary", "byte[]"),
            Map.entry("varbinary", "byte[]")
    );

    /**
     * 将 JDBC/数据库类型转换为完整 Java 类型名。
     * 类型长度和 MySQL 的 {@code unsigned} 修饰不会影响映射结果。
     */
    public String map(String jdbcType) {
        if (jdbcType == null || jdbcType.isBlank()) {
            throw new BusinessException(400, "数据库字段类型不能为空");
        }
        String normalized = jdbcType.toLowerCase(Locale.ROOT)
                .replace(" unsigned", "")
                .replaceAll("\\(.*\\)", "")
                .trim();
        String javaType = TYPE_MAP.get(normalized);
        if (javaType == null) {
            throw new BusinessException(400, "暂不支持数据库字段类型: " + jdbcType);
        }
        return javaType;
    }

    /** 获取模板字段声明使用的简单类名，例如 {@code java.math.BigDecimal -> BigDecimal}。 */
    public String simpleName(String javaType) {
        if (javaType.endsWith("[]")) return javaType;
        int dot = javaType.lastIndexOf('.');
        return dot >= 0 ? javaType.substring(dot + 1) : javaType;
    }
}
