package com.platform.generator.metadata;

import com.platform.framework.exception.BusinessException;

import javax.lang.model.SourceVersion;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 生成器统一的命名转换与合法性校验工具。
 *
 * <p>所有会进入包名、类名、字段名或 ZIP 路径的名称都必须先经过这里校验，
 * 防止生成不可编译的 Java 源码，也避免通过异常名称构造不安全路径。</p>
 */
public final class GeneratorNames {
    private static final Pattern DATABASE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private GeneratorNames() {}

    /** 校验数据库表名或字段名只包含受支持的安全字符。 */
    public static String requireDatabaseName(String value, String label) {
        if (value == null || !DATABASE_NAME.matcher(value).matches()) {
            throw new BusinessException(400, label + "格式不合法");
        }
        return value;
    }

    /** 校验名称是合法且非关键字的 Java 标识符。 */
    public static String requireJavaIdentifier(String value, String label) {
        if (value == null || !SourceVersion.isIdentifier(value) || SourceVersion.isKeyword(value)) {
            throw new BusinessException(400, label + "不是合法 Java 标识符");
        }
        return value;
    }

    /** 分段校验 Java 包名，确保生成后的 package 声明可以编译。 */
    public static String requirePackageName(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "包名不能为空");
        }
        Arrays.stream(value.split("\\.", -1))
                .forEach(part -> requireJavaIdentifier(part, "包名片段"));
        return value;
    }

    /** 将下划线命名转换为小驼峰，例如 {@code building_id -> buildingId}。 */
    public static String toCamelCase(String value) {
        requireDatabaseName(value, "数据库名称");
        String[] parts = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                result.append(Character.toUpperCase(parts[i].charAt(0)))
                        .append(parts[i].substring(1));
            }
        }
        return result.toString();
    }

    /** 将下划线命名转换为大驼峰，例如 {@code biz_equipment -> BizEquipment}。 */
    public static String toPascalCase(String value) {
        String camel = toCamelCase(value);
        return Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
    }

    /** 将 Java 包名转换为可放入 ZIP 的正斜杠目录路径。 */
    public static String packagePath(String packageName) {
        return requirePackageName(packageName).replace('.', '/');
    }
}
